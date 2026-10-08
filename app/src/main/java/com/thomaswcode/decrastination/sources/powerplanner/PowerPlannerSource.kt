package com.thomaswcode.decrastination.sources.powerplanner

import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sources.SourceUnavailable
import com.thomaswcode.decrastination.sources.TaskSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Power Planner through its web API, with the username and password from the encrypted store.
 * The session is kept and reused until a call fails, then one fresh login is tried. The semester
 * and timetable change rarely, so they're read at most every [TIMETABLE_MAX_AGE_MS]; a read is
 * then a single `GetAgenda` call.
 */
class PowerPlannerSource(private val api: PowerPlannerApi, private val secrets: SecretStore) : TaskSource {
    override val source = Source.PowerPlanner

    /** The selected semester and its timetable, as one account read them. */
    private data class Semester(val accountId: Long, val id: String, val timetable: Timetable, val readAt: Long)

    @Volatile
    private var semester: Semester? = null

    override suspend fun read(context: ReadContext): SourceRead = withContext(Dispatchers.IO) {
        val saved = savedLogin()
        val items = if (saved != null) {
            try {
                readWith(saved, context)
            } catch (_: PowerPlannerApi.ApiError) {
                // Most likely the session expired: log in again, once, and read the semester
                // afresh too, in case it's the semester that changed.
                semester = null
                readWith(freshLogin(), context)
            }
        } else {
            readWith(freshLogin(), context)
        }
        SourceRead(items)
    }

    private fun readWith(login: PowerPlannerApi.Login, context: ReadContext) = run {
        // A login typed into Setup may be another account's: its semester isn't this one's.
        val current = semester?.takeIf { it.accountId == login.accountId && context.now - it.readAt < TIMETABLE_MAX_AGE_MS }
            ?: api.selectedSemester(login).let { id -> Semester(login.accountId, id, api.classes(login, id), context.now) }.also { semester = it }
        val now = Instant.ofEpochMilli(context.now).truncatedTo(ChronoUnit.SECONDS).toString()
        api.agenda(login, current.id, now).mapNotNull { PowerPlannerItems.fetched(it, current.id, current.timetable, context.zone) }
    }

    private fun savedLogin(): PowerPlannerApi.Login? {
        val username = secrets[Secret.PowerPlannerUsername] ?: return null
        val session = secrets[Secret.PowerPlannerSession] ?: return null
        val accountId = secrets[Secret.PowerPlannerAccountId]?.toLongOrNull() ?: return null
        return PowerPlannerApi.Login(accountId, username, session)
    }

    private suspend fun freshLogin(): PowerPlannerApi.Login {
        val username = secrets[Secret.PowerPlannerUsername]
        val password = secrets[Secret.PowerPlannerPassword]
        if (username.isNullOrBlank() || password.isNullOrBlank()) throw SourceUnavailable("No Power Planner login saved")
        val login = api.login(username, password)
        secrets.put(mapOf(Secret.PowerPlannerSession to login.session, Secret.PowerPlannerAccountId to login.accountId.toString()))
        return login
    }

    private companion object {
        const val TIMETABLE_MAX_AGE_MS = 6 * 3_600_000L
    }
}
