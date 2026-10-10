package com.thomaswcode.decrastination.sources.powerplanner

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.net.HttpResponse
import com.thomaswcode.decrastination.sources.ReadContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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
        /** GetAgenda's answer, where a test gives one. */
        var agenda: String? = null

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            val endpoint = url.substringAfterLast('/')
            val json = Json.parseToJsonElement(body).jsonObject
            val login = json["Login"]?.jsonObject
            val account = login?.get("AccountId")?.jsonPrimitive?.content
            val session = login?.get("Password")?.jsonPrimitive?.content
            calls += "$endpoint:${account ?: json["Username"]?.jsonPrimitive?.content}:${json["SemesterIdentifier"]?.jsonPrimitive?.content}"
            if (session != null && session in expired) return HttpResponse(200, """{"Error":"Session expired"}""")
            return HttpResponse(
                200,
                when (endpoint) {
                    "LoginWeb" -> when (json["Username"]?.jsonPrimitive?.content) {
                        "a" -> """{"AccountId":1,"Session":"s1"}"""
                        "b" -> """{"AccountId":2,"Session":"s2"}"""
                        else -> """{"AccountId":0,"Session":null,"Error":"No account under that username exists."}"""
                    }
                    "GetSelectedSemesterId" -> """{"SelectedSemesterId":"semester-$account"}"""
                    "GetClassesAndSchedules" -> """{"WeekOneStartsOn":"2026-08-24T00:00:00","Classes":[]}"""
                    "GetAgenda" -> agenda ?: """{"Items":[{"Identifier":"item-$account","Name":"Task","Date":"2026-10-14T16:00:04","ClassIdentifier":"semester-$account","ItemType":5}]}"""
                    else -> """{"Error":"Unknown"}"""
                },
            )
        }
    }

    private val dir: File = Files.createTempDirectory("pp").toFile()
    private val secrets = SecretStore(File(dir, "secrets.bin"), Plain)
    private val api = FakeApi()
    private val source = PowerPlannerSource(PowerPlannerApi(api), secrets)
    private val context = ReadContext(Fixtures.at("2026-10-09T17:00"), LONDON, Settings(), emptyList(), null)

    @Test
    fun `the session is reused, and the semester read once`() = runTest {
        secrets.put(mapOf(Secret.PowerPlannerUsername to "a", Secret.PowerPlannerPassword to "pw"))
        assertEquals(listOf("item-1"), source.read(context).items.map { it.sourceId })
        source.read(context)
        assertEquals(listOf("LoginWeb", "GetSelectedSemesterId", "GetClassesAndSchedules", "GetAgenda", "GetAgenda"), api.calls.map { it.substringBefore(':') })
    }

    @Test
    fun `another account's login reads its own semester, not the cached one`() = runTest {
        secrets.put(mapOf(Secret.PowerPlannerUsername to "a", Secret.PowerPlannerPassword to "pw"))
        source.read(context)
        // As Setup does: a new login, and the old session cleared.
        secrets.put(mapOf(Secret.PowerPlannerUsername to "b", Secret.PowerPlannerSession to null, Secret.PowerPlannerAccountId to null))
        assertEquals(listOf("item-2"), source.read(context).items.map { it.sourceId })
        assertEquals("GetAgenda:2:semester-2", api.calls.last())
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
    fun `an agenda answer without its items fails the read, while an empty list is an empty agenda`() = runTest {
        secrets.put(mapOf(Secret.PowerPlannerUsername to "a", Secret.PowerPlannerPassword to "pw"))
        api.agenda = """{"Error":null}"""
        assertFailsWith<PowerPlannerApi.ApiError> { source.read(context) }
        api.agenda = """{"Items":null}"""
        assertFailsWith<PowerPlannerApi.ApiError> { source.read(context) }
        api.agenda = """{"Items":[],"Error":null}"""
        assertEquals(emptyList(), source.read(context).items)
    }
}
