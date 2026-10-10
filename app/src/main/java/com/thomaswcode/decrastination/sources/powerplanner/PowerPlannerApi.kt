package com.thomaswcode.decrastination.sources.powerplanner

import com.thomaswcode.decrastination.net.Http
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * Power Planner's web API, as its open-source web app uses it (docs/data-sources.md §2): every
 * call a JSON POST carrying the web app's "rate-limited development key". Verified with your
 * account on 8 Oct (docs/phase0-findings.md §2).
 */
class PowerPlannerApi(private val http: Http) {

    /** What every call after logging in sends: the session goes in the Password field, as the web app does it. */
    data class Login(val accountId: Long, val username: String, val session: String)

    /** The API answered, but with an error, such as a wrong password or an expired session. */
    class ApiError(val endpoint: String, message: String) : IOException("Power Planner $endpoint: $message")

    fun login(username: String, password: String): Login {
        val response = call(
            "LoginWeb",
            buildJsonObject {
                put("Username", username)
                put("Password", password)
            },
            LoginResponse.serializer(),
        )
        val session = response.session ?: throw ApiError("LoginWeb", response.error ?: "no session")
        return Login(response.accountId, username, session)
    }

    fun selectedSemester(login: Login): String {
        val response = call("GetSelectedSemesterId", withLogin(login), SemesterResponse.serializer())
        return response.selectedSemesterId ?: throw ApiError("GetSelectedSemesterId", response.error ?: "no semester selected")
    }

    /**
     * The agenda's items. An answer without them is a failed read, not an empty agenda: an empty one
     * comes as `"Items":[]` (checked on 10 Oct), and a missing list taken as empty would finish every
     * stored item (BUG-P2-007).
     */
    fun agenda(login: Login, semesterId: String, nowIso: String): List<PpItem> =
        call(
            "GetAgenda",
            withLogin(login) {
                put("SemesterIdentifier", semesterId)
                put("CurrentTime", nowIso)
            },
            AgendaResponse.serializer(),
        ).items ?: throw ApiError("GetAgenda", "the answer had no list of items")

    fun classes(login: Login, semesterId: String): Timetable {
        val response = call(
            "GetClassesAndSchedules",
            withLogin(login) { put("SemesterIdentifier", semesterId) },
            ClassesResponse.serializer(),
        )
        return Timetable(response.weekOneStartsOn, response.classes.orEmpty())
    }

    private fun withLogin(login: Login, more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        putJsonObject("Login") {
            put("AccountId", login.accountId)
            put("Username", login.username)
            put("Password", login.session)
        }
        more()
    }

    private fun <T : WithError> call(endpoint: String, body: JsonObject, serializer: KSerializer<T>): T {
        val response = http.post(
            "$BASE_URL/$endpoint",
            mapOf("Content-Type" to "application/json", "HashedKey" to HASHED_KEY),
            body.toString(),
        )
        if (!response.ok) throw IOException("Power Planner $endpoint: HTTP ${response.code}")
        val parsed = json.decodeFromString(serializer, response.body)
        // LoginWeb's error is read by login() itself, to say what was wrong.
        if (endpoint != "LoginWeb") parsed.error?.let { throw ApiError(endpoint, it) }
        return parsed
    }

    companion object {
        const val BASE_URL = "https://web.api.powerplanner.net/api"

        /** The "rate-limited development key" the official web app ships with. */
        const val HASHED_KEY = "3a4d3d842fd5c63e8c8ba5677c18abcc59affe2f3a8179081180d56a67376a74"

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}

interface WithError {
    val error: String?
}

@Serializable
data class LoginResponse(
    @SerialName("AccountId") val accountId: Long = 0,
    @SerialName("Session") val session: String? = null,
    @SerialName("Error") override val error: String? = null,
) : WithError

@Serializable
data class SemesterResponse(
    @SerialName("SelectedSemesterId") val selectedSemesterId: String? = null,
    @SerialName("Error") override val error: String? = null,
) : WithError

@Serializable
data class AgendaResponse(
    @SerialName("Items") val items: List<PpItem>? = null,
    @SerialName("Error") override val error: String? = null,
) : WithError

@Serializable
data class ClassesResponse(
    @SerialName("WeekOneStartsOn") val weekOneStartsOn: String? = null,
    @SerialName("Classes") val classes: List<PpClass>? = null,
    @SerialName("Error") override val error: String? = null,
) : WithError

/** One agenda item. `ItemType` 5 is a task (Homework in the API), 6 an event (Exam). */
@Serializable
data class PpItem(
    @SerialName("Identifier") val identifier: String,
    @SerialName("Name") val name: String = "",
    @SerialName("ShortDetails") val details: String? = null,
    @SerialName("Date") val date: String? = null,
    @SerialName("EndTime") val endTime: String? = null,
    @SerialName("ClassIdentifier") val classIdentifier: String? = null,
    @SerialName("ItemType") val itemType: Int = 0,
    @SerialName("PercentComplete") val percentComplete: Double = 0.0,
)

@Serializable
data class PpClass(
    @SerialName("Identifier") val identifier: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Schedules") val schedules: List<PpSchedule>? = null,
)

/** A timetable slot. `DayOfWeek` is .NET's (0 is Sunday); `ScheduleWeek` 1, 2, or 3 for both. */
@Serializable
data class PpSchedule(
    @SerialName("StartTime") val startTime: String,
    @SerialName("EndTime") val endTime: String,
    @SerialName("DayOfWeek") val dayOfWeek: Int,
    @SerialName("ScheduleWeek") val scheduleWeek: Int = 3,
)

data class Timetable(val weekOneStartsOn: String?, val classes: List<PpClass>)
