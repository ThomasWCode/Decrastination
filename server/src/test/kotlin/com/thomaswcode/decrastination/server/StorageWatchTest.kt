package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.FixedClock
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The storage watched (6.10.0): Cloudflare's answers read as recorded (never asked in a test), R2's requests by class, each allowance's
 * share and the date it'd be passed, the alerts once a month each, the website's notice, the view, and a day's reading kept.
 */
class StorageWatchTest {
    private val zone = ZoneId.of("Europe/London")
    private val dir: File = Files.createTempDirectory("storage").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    /** Cloudflare's answers as they came on 20 Nov 2026, the bucket's own figures: sizes and counts alone. */
    private val storedAnswer = Json.parseToJsonElement(
        """{"data":{"viewer":{"accounts":[{"r2StorageAdaptiveGroups":[{"dimensions":{"datetime":"2026-11-20T00:50:00Z"},"max":{"metadataSize":94099,"objectCount":1103,"payloadSize":3328669739}}]}]}},"errors":null}""",
    ).jsonObject
    private val requestsAnswer = Json.parseToJsonElement(
        """{"data":{"viewer":{"accounts":[{"r2OperationsAdaptiveGroups":[
            {"dimensions":{"actionType":"GetObject"},"sum":{"requests":28635}},
            {"dimensions":{"actionType":"ListObjects"},"sum":{"requests":1166721}},
            {"dimensions":{"actionType":"GetBucketCors"},"sum":{"requests":3}},
            {"dimensions":{"actionType":"CompleteMultipartUpload"},"sum":{"requests":65}},
            {"dimensions":{"actionType":"PutObject"},"sum":{"requests":97891}},
            {"dimensions":{"actionType":"DeleteObject"},"sum":{"requests":4000}}
        ]}]}},"errors":null}""",
    ).jsonObject

    private fun reading(at: Long, r2Stored: Long? = 3 * StorageRules.GB, classA: Long? = 100_000, classB: Long? = 30_000, problem: String? = null, b2: Long = 50_000_000) =
        StorageReading(at, database = 150_000_000, log = 4_000_000, photos = 2_000_000, diskFree = 30 * StorageRules.GB, diskTotal = 40 * StorageRules.GB, r2Stored = r2Stored, r2ClassA = classA, r2ClassB = classB, r2Problem = problem, b2Kept = b2)

    @Test
    fun `Cloudflare's answers are read as recorded, requests counted by their class and the free ones by none`() {
        assertEquals(3328669739L + 94099, StorageRules.stored(storedAnswer))
        assertEquals(1166721L + 65 + 97891 to 28635L + 3, StorageRules.requests(requestsAnswer))
        assertEquals('A', StorageRules.classOf("ListObjects"))
        assertEquals('B', StorageRules.classOf("HeadObject"))
        assertNull(StorageRules.classOf("DeleteObject"))
        val refused = Json.parseToJsonElement("""{"data":null,"errors":[{"message":"not authorized for that account"}]}""").jsonObject
        assertNull(StorageRules.stored(refused))
        assertEquals("Cloudflare said: not authorized for that account", StorageRules.problem(refused))
    }

    @Test
    fun `each allowance's share, and the date it'd be passed at the last week's rate`() {
        val week = listOf(reading(at("2026-11-13T05:00:00Z"), r2Stored = 3 * StorageRules.GB), reading(at("2026-11-20T05:00:00Z"), r2Stored = 4 * StorageRules.GB))
        assertEquals(40, StorageRules.percent(Allowance.R2Stored, week.last()))
        // A gigabyte a week: six more weeks.
        assertEquals(LocalDate.of(2027, 1, 1), StorageRules.passes(Allowance.R2Stored, week, zone))
        // Not growing: never.
        assertNull(StorageRules.passes(Allowance.R2Stored, listOf(week.last().copy(at = week.first().at), week.last()), zone))
        // A month's requests: passed this month at the week's rate, or not at all.
        val requests = listOf(reading(at("2026-11-13T05:00:00Z"), classA = 400_000), reading(at("2026-11-20T05:00:00Z"), classA = 750_000))
        // 50 000 a day, 250 000 left: five days.
        assertEquals(LocalDate.of(2026, 11, 25), StorageRules.passes(Allowance.R2ClassA, requests, zone))
        assertNull(StorageRules.passes(Allowance.R2ClassA, listOf(reading(at("2026-11-13T05:00:00Z"), classA = 10_000), reading(at("2026-11-20T05:00:00Z"), classA = 20_000)), zone))
        // So slow it lands in this month of next year: not this month's.
        assertNull(StorageRules.passes(Allowance.R2ClassA, listOf(reading(at("2026-11-13T05:00:00Z"), classA = 628_000), reading(at("2026-11-20T05:00:00Z"), classA = 635_000)), zone))
        // One unread: no share.
        assertNull(StorageRules.percent(Allowance.R2ClassA, reading(at("2026-11-20T05:00:00Z"), classA = null)))
    }

    @Test
    fun `an alert's given at 80 and 95 percent, each once a month, the higher alone where both are reached at once`() {
        val now = at("2026-11-20T05:00:00Z")
        val (first, alerted) = StorageRules.alerts(emptyMap(), reading(now, classA = 1_170_000), zone)
        assertEquals(listOf("R2, Class A requests this month is at 117% of its free allowance (1.17 million of 1.00 million)."), first)
        assertEquals(setOf("R2ClassA:80", "R2ClassA:95"), alerted.keys)
        assertTrue(StorageRules.alerts(alerted, reading(now + 86_400_000L, classA = 1_200_000), zone).first.isEmpty(), "once a month")
        // A new month starts again.
        assertEquals(1, StorageRules.alerts(alerted, reading(at("2026-12-20T05:00:00Z"), classA = 900_000), zone).first.size)
        // By the UTC month, as Cloudflare counts it: a reading in the first hour of July in summer time is June's.
        assertEquals(setOf("2027-06"), StorageRules.alerts(emptyMap(), reading(at("2027-06-30T23:30:00Z"), classA = 1_100_000), zone).second.values.toSet())
        // 80 first, then 95 when it's reached.
        val (eighty, then) = StorageRules.alerts(emptyMap(), reading(now, r2Stored = 8_500_000_000), zone)
        assertTrue(eighty.single().startsWith("R2, stored is at 85%"))
        assertTrue(StorageRules.alerts(then, reading(now, r2Stored = 9_600_000_000), zone).first.single().startsWith("R2, stored is at 96%"))
    }

    @Test
    fun `the view says each allowance, the box's own and what couldn't be read, and the notice the nearest past 80`() {
        val record = StorageRecord(readings = listOf(reading(at("2026-11-20T05:00:00Z"), r2Stored = null, classA = null, classB = null, problem = "no analytics token is set on the box")))
        val view = assertNotNull(StorageRules.view(record, zone))
        assertEquals(listOf("B2, the nightly files kept", "The box's disk"), view.lines.map { it.label })
        assertEquals("50 MB of 10.00 GB free", view.lines.first().used)
        assertEquals(listOf("R2 couldn't be read: no analytics token is set on the box"), view.problems)
        assertTrue(view.read.startsWith("Read Fri 20 Nov 05:00"), view.read)
        assertNull(StorageRules.notice(record))
        val near = StorageRecord(readings = listOf(reading(at("2026-11-20T05:00:00Z"), classA = 1_170_000)))
        assertEquals("R2, Class A requests this month: 117% of its free allowance (Settings)", StorageRules.notice(near))
        assertEquals("past it this month", StorageRules.view(near, zone)!!.lines.single { it.label.startsWith("R2, Class A") }.passes)
        assertNull(StorageRules.view(StorageRecord(), zone))
    }

    @Test
    fun `a day's reading is kept with the month's, B2's from what was written, its alerts sent, and read back after a start`() = runBlocking {
        val clock = FixedClock(at("2026-11-20T05:00:00Z"), zone)
        File(dir, StorageWatch.DATABASE).writeBytes(ByteArray(1_000))
        File(dir, "photos").mkdirs()
        File(dir, "photos/a.jpg").writeBytes(ByteArray(300))
        val said = mutableListOf<String>()
        val watch = StorageWatch(File(dir, "storage.json"), dir, r2 = null, clock = clock) { said += it }
        watch.noteB2(400_000)
        watch.read()
        val reading = watch.record().readings.single()
        assertEquals(1_000, reading.database)
        assertEquals(300, reading.photos)
        assertEquals(400_000, reading.b2Kept)
        assertEquals("no analytics token is set on the box", reading.r2Problem)
        // Read back as it was written, after a start.
        assertEquals(watch.record(), StorageWatch(File(dir, "storage.json"), dir, null, clock) {}.record())
        // A B2 file past what B2 keeps no longer counts.
        clock.time += StorageRules.B2_KEPT_MS
        watch.read()
        assertEquals(0, watch.record().readings.last().b2Kept)
        assertEquals(2, watch.record().readings.size)
        assertTrue(said.none { "B2" in it })
    }

    @Test
    fun `a reading's alerts go as one, each its line, as one would take another's place`() = runBlocking {
        val clock = FixedClock(at("2026-11-20T05:00:00Z"), zone)
        // R2's stored and its requests past their shares, and B2's: three at once.
        val said = mutableListOf<String>()
        val watch = StorageWatch(File(dir, "storage.json"), dir, r2 = { Triple(9_600_000_000, 1_200_000L to 30_000L, null) }, clock = clock) { said += it }
        repeat(30) { watch.noteB2(330_000_000) }
        watch.read()
        assertEquals(1, said.size)
        val lines = said.single().lines()
        assertTrue(lines.any { it.startsWith("R2, stored is at 96%") } && lines.any { it.startsWith("R2, Class A requests this month is at 120%") } && lines.any { it.startsWith("B2, the nightly files kept is at 99%") }, said.single())
    }
}
