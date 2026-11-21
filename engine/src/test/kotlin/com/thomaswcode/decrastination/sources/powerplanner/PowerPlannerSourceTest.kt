package com.thomaswcode.decrastination.sources.powerplanner

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.PpClass
import com.thomaswcode.decrastination.core.PpSchedule
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Timetable
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.net.HttpResponse
import com.thomaswcode.decrastination.sources.ReadContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The source against a fake web API: two accounts, each with its own semester. */
class PowerPlannerSourceTest {

    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(sealed: ByteArray) = sealed
    }

    /** Answers like the web API: logins for "a" (account 1) and "b" (account 2), sessions that expire on request. */
    private class FakeApi : Http {
        val calls = mutableListOf<String>()
        var expired = mutableSetOf<String>()
        /** GetClassesSchedulesAndMegaItems' answer, where a test gives one. */
        var semester: String? = null

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            val endpoint = url.substringAfterLast('/')
            val json = Json.parseToJsonElement(body).jsonObject
            val login = json["Login"]?.jsonObject
            val account = login?.get("AccountId")?.jsonPrimitive?.content
            val session = login?.get("Password")?.jsonPrimitive?.content
            calls += "$endpoint:${account ?: json["Username"]?.jsonPrimitive?.content}:${json["SemesterIdentifier"]?.jsonPrimitive?.content}"
            if (session != null && session in expired) return HttpResponse(200, """{"Error":"Session expired"}""")
            return when (endpoint) {
                "LoginWeb" -> HttpResponse(
                    200,
                    when (json["Username"]?.jsonPrimitive?.content) {
                        "a" -> """{"AccountId":1,"Session":"s1"}"""
                        "b" -> """{"AccountId":2,"Session":"s2"}"""
                        else -> """{"AccountId":0,"Session":null,"Error":"No account under that username exists."}"""
                    },
                )
                "GetSelectedSemesterId" -> HttpResponse(200, """{"SelectedSemesterId":"semester-$account"}""")
                "GetClassesSchedulesAndMegaItems" -> HttpResponse(
                    200,
                    semester ?: """{"WeekOneStartsOn":"2026-08-24T00:00:00","Classes":[],"Schedules":[],"MegaItems":[{"Identifier":"item-$account","Name":"Task","Date":"2026-10-14T16:00:04","EndTime":"1970-01-01T00:00:00","UpperIdentifier":"semester-$account","MegaItemType":3,"PercentComplete":0.0}]}""",
                )
                // As the API answers a call it no longer has, GetAgenda and GetClassesAndSchedules among them.
                else -> HttpResponse(404, "")
            }
        }
    }

    private val dir: File = Files.createTempDirectory("pp").toFile()
    private val secrets = SecretStore(File(dir, "secrets.bin"), Plain)
    private val api = FakeApi()
    private val source = PowerPlannerSource(PowerPlannerApi(api), secrets)
    private val context = ReadContext(Fixtures.at("2026-10-09T17:00"), LONDON, Settings(), emptyList(), null)

    /** A semester's answer holding [items], each `"id", type, "date", percent`, of the semester's own. */
    private fun answer(vararg items: String) = items.joinToString(",", """{"WeekOneStartsOn":"2026-08-24T00:00:00","Classes":[],"Schedules":[],"MegaItems":[""", "]}") {
        val (id, type, date, percent) = it.split(' ')
        """{"Identifier":"$id","Name":"$id","Date":"$date","EndTime":"1970-01-01T00:00:00","UpperIdentifier":"semester-1","MegaItemType":$type,"PercentComplete":$percent}"""
    }

    @Test
    fun `the session is reused, and the semester read once`() = runTest {
        secrets.put(mapOf(Secret.PowerPlannerUsername to "a", Secret.PowerPlannerPassword to "pw"))
        assertEquals(listOf("item-1"), source.read(context).items.map { it.sourceId })
        source.read(context)
        assertEquals(
            listOf("LoginWeb", "GetSelectedSemesterId", "GetClassesSchedulesAndMegaItems", "GetClassesSchedulesAndMegaItems"),
            api.calls.map { it.substringBefore(':') },
        )
    }

    @Test
    fun `a read carries the timetable it used`() = runTest {
        secrets.put(mapOf(Secret.PowerPlannerUsername to "a", Secret.PowerPlannerPassword to "pw"))
        assertEquals(Timetable("2026-08-24T00:00:00", emptyList()), source.read(context).timetable)
    }

    @Test
    fun `the timetable's slots, listed beside the classes, are put with the class each names`() = runTest {
        secrets.put(mapOf(Secret.PowerPlannerUsername to "a", Secret.PowerPlannerPassword to "pw"))
        api.semester = """{"WeekOneStartsOn":"2026-08-24T00:00:00","Error":null,
            "Classes":[{"Identifier":"german","Name":"German","Color":"#F09609","Schedules":null},{"Identifier":"art","Name":"Art","Color":"#1BA1E2","Schedules":null}],
            "Schedules":[
              {"Identifier":"s1","UpperIdentifier":"german","Room":"","StartTime":"2026-09-05T09:50:00","EndTime":"2026-09-05T10:45:00","DayOfWeek":3,"ScheduleWeek":1},
              {"Identifier":"s2","UpperIdentifier":"gone","Room":"","StartTime":"2026-09-05T11:00:00","EndTime":"2026-09-05T11:55:00","DayOfWeek":3,"ScheduleWeek":1},
              {"Identifier":"s3","UpperIdentifier":"german","Room":"","StartTime":"2026-09-05T08:50:00","EndTime":"2026-09-05T09:45:00","DayOfWeek":5,"ScheduleWeek":2}],
            "MegaItems":[]}"""
        assertEquals(
            Timetable(
                "2026-08-24T00:00:00",
                listOf(
                    PpClass("german", "German", listOf(PpSchedule("2026-09-05T09:50:00", "2026-09-05T10:45:00", 3, 1), PpSchedule("2026-09-05T08:50:00", "2026-09-05T09:45:00", 5, 2))),
                    PpClass("art", "Art", emptyList()),
                ),
            ),
            source.read(context).timetable,
        )
    }

    @Test
    fun `another account's login reads its own semester, not the cached one`() = runTest {
        secrets.put(mapOf(Secret.PowerPlannerUsername to "a", Secret.PowerPlannerPassword to "pw"))
        source.read(context)
        // As Setup does: a new login, and the old session cleared.
        secrets.put(mapOf(Secret.PowerPlannerUsername to "b", Secret.PowerPlannerSession to null, Secret.PowerPlannerAccountId to null))
        assertEquals(listOf("item-2"), source.read(context).items.map { it.sourceId })
        assertEquals("GetClassesSchedulesAndMegaItems:2:semester-2", api.calls.last())
    }

    @Test
    fun `an expired session logs in again, once`() = runTest {
        secrets.put(mapOf(Secret.PowerPlannerUsername to "a", Secret.PowerPlannerPassword to "pw"))
        source.read(context)
        api.expired += "s1"
        secrets.put(Secret.PowerPlannerSession, "s1")
        api.calls.clear()
        runCatching { source.read(context) }
        assertEquals("LoginWeb", api.calls[1].substringBefore(':'))
    }

    @Test
    fun `an answer without its items fails the read, while an empty list is a semester with none`() = runTest {
        secrets.put(mapOf(Secret.PowerPlannerUsername to "a", Secret.PowerPlannerPassword to "pw"))
        api.semester = """{"Error":null}"""
        assertFailsWith<PowerPlannerApi.ApiError> { source.read(context) }
        api.semester = """{"Classes":[],"Schedules":[],"MegaItems":null}"""
        assertFailsWith<PowerPlannerApi.ApiError> { source.read(context) }
        api.semester = """{"Classes":[],"Schedules":[],"MegaItems":[],"Error":null}"""
        assertEquals(emptyList(), source.read(context).items)
    }

    @Test
    fun `of the semester's items, those on the agenda are read`() = runTest {
        secrets.put(mapOf(Secret.PowerPlannerUsername to "a", Secret.PowerPlannerPassword to "pw"))
        // Read on Friday 9 Oct: a task stays till it's complete and its day has passed, an event till its day has.
        api.semester = answer(
            "done-before 3 2026-09-25T00:00:00 1.0",
            "open-before 3 2026-09-25T00:00:00 0.0",
            "done-today 3 2026-10-09T00:00:00 1.0",
            "open-ahead 3 2026-10-14T16:00:04 0.0",
            "event-before 4 2026-10-08T14:00:00 0.0",
            "event-today 4 2026-10-09T08:00:00 0.0",
            "event-ahead 4 2026-10-10T14:00:00 0.0",
            "holiday-ahead 2 2026-10-26T00:00:00 0.0",
        )
        val read = source.read(context).items
        assertEquals(listOf("open-before", "done-today", "open-ahead", "event-today", "event-ahead"), read.map { it.sourceId })
        assertEquals(listOf(false, true, false, false, false), read.map { it.done })
    }

    @Test
    fun `the agenda's day is the day where it's read`() = runTest {
        secrets.put(mapOf(Secret.PowerPlannerUsername to "a", Secret.PowerPlannerPassword to "pw"))
        api.semester = answer("event 4 2026-10-09T08:00:00 0.0", "done 3 2026-10-09T00:00:00 1.0")
        // Half past midnight in London on the 10th is still the 9th by UTC: the 9th's items have left the agenda.
        assertEquals(listOf("event", "done"), source.read(context.copy(now = Fixtures.at("2026-10-09T23:59"))).items.map { it.sourceId })
        assertEquals(emptyList(), source.read(context.copy(now = Fixtures.at("2026-10-10T00:30"))).items.map { it.sourceId })
    }

    @Test
    fun `a call the API no longer has fails the read`() = runTest {
        secrets.put(mapOf(Secret.PowerPlannerUsername to "a", Secret.PowerPlannerPassword to "pw"))
        val gone = PowerPlannerSource(PowerPlannerApi { url, headers, body -> if (url.endsWith("/GetSelectedSemesterId")) HttpResponse(404, "") else api.post(url, headers, body) }, secrets)
        assertEquals("Power Planner GetSelectedSemesterId: HTTP 404", assertFailsWith<IOException> { gone.read(context) }.message)
    }
}
