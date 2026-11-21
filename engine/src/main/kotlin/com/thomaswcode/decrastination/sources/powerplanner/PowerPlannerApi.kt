package com.thomaswcode.decrastination.sources.powerplanner

import com.thomaswcode.decrastination.core.PpClass
import com.thomaswcode.decrastination.core.PpItem
import com.thomaswcode.decrastination.core.PpSchedule
import com.thomaswcode.decrastination.core.Timetable
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
import kotlinx.serialization.json.JsonObjectBuilder

/**
 * Power Planner's web API, as its open-source web app uses it (docs/spec/sources.md): every
 * call a JSON POST carrying the web app's "rate-limited development key". Verified with your
 * account (docs/spec/sources.md).
 */
class PowerPlannerApi(private val http: Http) {

    /** What every call after logging in sends: the session goes in the Password field, as the web app does it. */
    data class Login(val accountId: Long, val username: String, val session: String)

    /** A semester as one call gives it: its timetable, and every item it holds, those long done among them. */
    data class Semester(val timetable: Timetable, val items: List<PpItem>)

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
     * The semester's classes, their timetable and its items, in the one call the web app makes. An answer
     * without the items is a failed read, not a semester with none: a missing list taken as empty would
     * finish every stored item (BUG-P2-007). The timetable's slots come in a list of their own, each
     * naming its class, and are put with their classes here.
     */
    fun semester(login: Login, semesterId: String): Semester {
        val response = call(
            ITEMS,
            withLogin(login) { put("SemesterIdentifier", semesterId) },
            ItemsResponse.serializer(),
        )
        val items = response.items ?: throw ApiError(ITEMS, "the answer had no list of items")
        val slots = response.schedules.orEmpty().groupBy({ it.classIdentifier }, { PpSchedule(it.startTime, it.endTime, it.dayOfWeek, it.scheduleWeek) })
        return Semester(Timetable(response.weekOneStartsOn, response.classes.orEmpty().map { it.copy(schedules = slots[it.identifier].orEmpty()) }), items)
    }

    private fun withLogin(login: Login, more: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
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
        private const val ITEMS = "GetClassesSchedulesAndMegaItems"

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
data class ItemsResponse(
    @SerialName("WeekOneStartsOn") val weekOneStartsOn: String? = null,
    @SerialName("Classes") val classes: List<PpClass>? = null,
    @SerialName("Schedules") val schedules: List<ClassSchedule>? = null,
    @SerialName("MegaItems") val items: List<PpItem>? = null,
    @SerialName("Error") override val error: String? = null,
) : WithError

/** A timetable slot as the answer lists it, beside the classes: `UpperIdentifier` is its class's id. */
@Serializable
data class ClassSchedule(
    @SerialName("UpperIdentifier") val classIdentifier: String,
    @SerialName("StartTime") val startTime: String,
    @SerialName("EndTime") val endTime: String,
    @SerialName("DayOfWeek") val dayOfWeek: Int,
    @SerialName("ScheduleWeek") val scheduleWeek: Int = 3,
)
