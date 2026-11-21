package com.thomaswcode.decrastination.sources.powerplanner

import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.PowerPlannerItems
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Timetable
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sources.SourceUnavailable
import com.thomaswcode.decrastination.sources.TaskSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Power Planner through its web API, with the username and password from the encrypted store.
 * The session is kept and reused until a call fails, then one fresh login is tried. Which semester
 * is selected changes rarely, so it's read at most every [SEMESTER_MAX_AGE_MS]; a read is then the
 * single call that gives the timetable and the items. That call gives every item of the semester,
 * so the agenda is made of them here: an item that has left it is one the merge takes as done.
 */
class PowerPlannerSource(private val api: PowerPlannerApi, private val secrets: SecretStore) : TaskSource {
    override val source = Source.PowerPlanner

    /** The selected semester, as one account read it. */
    private data class Semester(val accountId: Long, val id: String, val readAt: Long)

    @Volatile
    private var semester: Semester? = null

    override suspend fun read(context: ReadContext): SourceRead = withContext(Dispatchers.IO) {
        val saved = savedLogin()
        val (items, timetable) = if (saved != null) {
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
        SourceRead(items, timetable = timetable)
    }

    /** The agenda's items, and the timetable they were read against. */
    private fun readWith(login: PowerPlannerApi.Login, context: ReadContext): Pair<List<Fetched>, Timetable> = run {
        // A login typed into Setup may be another account's: its semester isn't this one's.
        val current = semester?.takeIf { it.accountId == login.accountId && context.now - it.readAt < SEMESTER_MAX_AGE_MS }
            ?: Semester(login.accountId, api.selectedSemester(login), context.now).also { semester = it }
        val read = api.semester(login, current.id)
        val today = Instant.ofEpochMilli(context.now).atZone(context.zone).toLocalDate()
        read.items.filter { PowerPlannerItems.onAgenda(it, today) }.mapNotNull { PowerPlannerItems.fetched(it, current.id, read.timetable, context.zone) } to read.timetable
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
        const val SEMESTER_MAX_AGE_MS = 6 * 3_600_000L
    }
}
