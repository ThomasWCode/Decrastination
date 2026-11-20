package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.views.StorageLine
import com.thomaswcode.decrastination.views.StorageView
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A day's reading of what the server's storage comes to (6.10.0, D-161): the box's own (the database's file and its write-ahead log,
 * the photos' folder, the disk and its free room), R2's from Cloudflare's analytics (stored, and this month's requests by class, null
 * where they couldn't be read, [r2Problem] why), and B2's as the server knows it (what it's written there that B2 still keeps, as its
 * key can't list the bucket).
 */
@Serializable
data class StorageReading(
    val at: Long,
    val database: Long,
    val log: Long,
    val photos: Long,
    val diskFree: Long,
    val diskTotal: Long,
    val r2Stored: Long? = null,
    val r2ClassA: Long? = null,
    val r2ClassB: Long? = null,
    val r2Problem: String? = null,
    val b2Kept: Long = 0,
)

/** A nightly file written to B2: when, and how many bytes. */
@Serializable
data class B2Write(val at: Long, val bytes: Long)

/**
 * The storage's record (6.10.0), the server's own file (`storage.json` in its data folder), never a store: the last month's readings,
 * the alerts given (an allowance and a share, to the month it was given in, so each comes once a month), and the nightly files B2 keeps.
 */
@Serializable
data class StorageRecord(
    val readings: List<StorageReading> = emptyList(),
    val alerted: Map<String, String> = emptyMap(),
    val b2Writes: List<B2Write> = emptyList(),
)

/** A free allowance watched (6.10.0), and what it's called where it's shown. */
enum class Allowance(val label: String) {
    R2Stored("R2, stored"),
    R2ClassA("R2, Class A requests this month"),
    R2ClassB("R2, Class B requests this month"),
    B2Kept("B2, the nightly files kept"),
    Disk("The box's disk"),
}

/** What the storage's figures come to (6.10.0): each allowance's use and share, the date it'd be passed, the alerts and the view. Pure. */
object StorageRules {
    const val GB = 1_000_000_000L
    private const val DAY_MS = 86_400_000L

    /** R2's free tier, a month (its pricing page, read 20 Nov 2026): 10 GB-month stored, a million Class A requests, ten million Class B. */
    const val R2_STORED = 10 * GB
    const val R2_CLASS_A = 1_000_000L
    const val R2_CLASS_B = 10_000_000L

    /** B2's free 10 GB. */
    const val B2_STORED = 10 * GB

    /** How long B2 keeps a nightly file: hidden 60 days after it's written, deleted 60 days after that (`plan_web/hosting.md`). */
    const val B2_KEPT_MS = 120 * DAY_MS

    /** The shares of an allowance an alert's given at, each once a month (the owner's answer, 19 Nov). */
    val ALERT_AT = listOf(80, 95)

    /** The readings kept: a month's, for the last week's rate. */
    const val KEPT_READINGS = 31

    /** R2's requests by class, by their action's name, as its pricing page lists them (20 Nov 2026); the rest are free. */
    private val CLASS_A = setOf(
        "ListBuckets", "PutBucket", "ListObjects", "PutObject", "CopyObject", "CompleteMultipartUpload", "CreateMultipartUpload", "LifecycleStorageTierTransition",
        "ListMultipartUploads", "UploadPart", "UploadPartCopy", "ListParts", "PutBucketEncryption", "PutBucketCors", "PutBucketLifecycleConfiguration",
    )
    private val CLASS_B = setOf("HeadBucket", "HeadObject", "GetObject", "UsageSummary", "GetBucketEncryption", "GetBucketLocation", "GetBucketCors", "GetBucketLifecycleConfiguration")

    /** [action]'s class, 'A' or 'B', or null for a free one. */
    fun classOf(action: String): Char? = when (action) {
        in CLASS_A -> 'A'
        in CLASS_B -> 'B'
        else -> null
    }

    /** What Cloudflare's answer about the bucket's storage says it holds, its objects and their metadata; null where it says nothing. */
    fun stored(answer: JsonObject): Long? {
        val groups = accounts(answer)?.firstOrNull()?.jsonObject?.get("r2StorageAdaptiveGroups")?.jsonArray ?: return null
        val max = groups.firstOrNull()?.jsonObject?.get("max")?.jsonObject ?: return null
        val payload = max["payloadSize"]?.jsonPrimitive?.longOrNull ?: return null
        return payload + (max["metadataSize"]?.jsonPrimitive?.longOrNull ?: 0)
    }

    /** What Cloudflare's answer about the bucket's requests comes to by class: Class A's, then Class B's; null where it says nothing. */
    fun requests(answer: JsonObject): Pair<Long, Long>? {
        val groups = accounts(answer)?.firstOrNull()?.jsonObject?.get("r2OperationsAdaptiveGroups")?.jsonArray ?: return null
        var a = 0L
        var b = 0L
        for (group in groups) {
            val action = group.jsonObject["dimensions"]?.jsonObject?.get("actionType")?.jsonPrimitive?.contentOrNull ?: continue
            val count = group.jsonObject["sum"]?.jsonObject?.get("requests")?.jsonPrimitive?.longOrNull ?: continue
            when (classOf(action)) {
                'A' -> a += count
                'B' -> b += count
                else -> Unit
            }
        }
        return a to b
    }

    /** What went wrong, as Cloudflare's answer says it, in a line; null where nothing did. */
    fun problem(answer: JsonObject): String? = (answer["errors"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull?.let { "Cloudflare said: ${it.take(120)}" }

    private fun accounts(answer: JsonObject) = (answer["data"] as? JsonObject)?.get("viewer")?.jsonObject?.get("accounts")?.jsonArray

    /** What [reading] has used of [allowance]; null where it couldn't be read. */
    fun used(allowance: Allowance, reading: StorageReading): Long? = when (allowance) {
        Allowance.R2Stored -> reading.r2Stored
        Allowance.R2ClassA -> reading.r2ClassA
        Allowance.R2ClassB -> reading.r2ClassB
        Allowance.B2Kept -> reading.b2Kept
        Allowance.Disk -> reading.diskTotal - reading.diskFree
    }

    /** [allowance]'s size, the disk's as [reading] found it. */
    fun limit(allowance: Allowance, reading: StorageReading): Long = when (allowance) {
        Allowance.R2Stored -> R2_STORED
        Allowance.R2ClassA -> R2_CLASS_A
        Allowance.R2ClassB -> R2_CLASS_B
        Allowance.B2Kept -> B2_STORED
        Allowance.Disk -> reading.diskTotal
    }

    /** The share of [allowance] [reading] has used, in whole percent; null where it couldn't be read. */
    fun percent(allowance: Allowance, reading: StorageReading): Int? {
        val used = used(allowance, reading) ?: return null
        val limit = limit(allowance, reading).takeIf { it > 0 } ?: return null
        return (used * 100 / limit).toInt()
    }

    /** Whether [allowance] counts a month's use, which starts again each month, rather than what's held. */
    private fun monthly(allowance: Allowance) = allowance == Allowance.R2ClassA || allowance == Allowance.R2ClassB

    /**
     * When [allowance] would be passed at the last week's rate, by [readings], oldest first, as of [zone]'s days: what's held, at its growth
     * over the week (none where it isn't growing); a month's requests, at the week's rate within this month, only where that's before the
     * month's out. Null where it won't be, or is past already.
     */
    fun passes(allowance: Allowance, readings: List<StorageReading>, zone: ZoneId): LocalDate? {
        val latest = readings.lastOrNull() ?: return null
        val used = used(allowance, latest) ?: return null
        val limit = limit(allowance, latest)
        if (used >= limit) return null
        val today = Instant.ofEpochMilli(latest.at).atZone(zone).toLocalDate()
        val month = today.withDayOfMonth(1)
        val earlier = readings.filter { latest.at - it.at in 1..8 * DAY_MS && used(allowance, it) != null && (!monthly(allowance) || !Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate().isBefore(month)) }.minByOrNull { it.at }
        val perDay = if (earlier != null) {
            (used - used(allowance, earlier)!!).toDouble() / ((latest.at - earlier.at).toDouble() / DAY_MS)
        } else if (monthly(allowance)) {
            // No reading from earlier this month to measure against: its use so far, a day at a time.
            used.toDouble() / (today.dayOfMonth.toDouble())
        } else {
            return null
        }
        if (perDay <= 0) return null
        val date = today.plusDays(Math.ceil((limit - used) / perDay).toLong())
        // A month's requests start again with the next: a date in another month, or another year's same month, isn't this one's.
        return date.takeIf { !monthly(allowance) || YearMonth.from(it) == YearMonth.from(today) }
    }

    /** [bytes] as the page says them, in MB or GB. */
    fun size(bytes: Long): String = if (bytes >= GB) String.format(Locale.UK, "%.2f GB", bytes.toDouble() / GB) else String.format(Locale.UK, "%.0f MB", bytes.toDouble() / 1_000_000)

    /** [count] requests as the page says them, in thousands or millions. */
    fun count(count: Long): String = if (count >= 1_000_000) String.format(Locale.UK, "%.2f million", count / 1_000_000.0) else String.format(Locale.UK, "%,d", count)

    private fun amount(allowance: Allowance, value: Long) = if (monthly(allowance)) count(value) else size(value)

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val AT = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

    /** [allowance]'s line in [reading], with its date to pass from [readings]: none where it couldn't be read. */
    private fun line(allowance: Allowance, reading: StorageReading, readings: List<StorageReading>, zone: ZoneId): StorageLine? {
        val used = used(allowance, reading) ?: return null
        val limit = limit(allowance, reading)
        val share = percent(allowance, reading) ?: return null
        val passes = when {
            used >= limit -> "past it" + if (monthly(allowance)) " this month" else ""
            else -> passes(allowance, readings, zone)?.let { "passes it about ${it.format(DAY)}, at the last week's rate" }
        }
        val of = if (allowance == Allowance.Disk) "of ${size(limit)}" else "of ${amount(allowance, limit)} free"
        return StorageLine(allowance.label, "${amount(allowance, used)} $of", share, passes, share >= ALERT_AT.first())
    }

    /** The storage in Settings (6.10.0), from [record] as of [zone]: each allowance's line, the box's own beside them, when it was read, and what couldn't be. */
    fun view(record: StorageRecord, zone: ZoneId): StorageView? {
        val latest = record.readings.lastOrNull() ?: return null
        val lines = Allowance.entries.mapNotNull { line(it, latest, record.readings, zone) }
        val box = listOf(
            "The database: ${size(latest.database)}, and its log ${size(latest.log)}",
            "Photos: ${size(latest.photos)}",
        )
        val problems = listOfNotNull(latest.r2Problem?.let { "R2 couldn't be read: $it" })
        return StorageView(lines, box, "Read ${Instant.ofEpochMilli(latest.at).atZone(zone).format(AT)}, once a day", problems)
    }

    /** The website's notice of it (6.10.0): the allowance nearest its end, where one's past [ALERT_AT]'s first share; none else. */
    fun notice(record: StorageRecord): String? {
        val latest = record.readings.lastOrNull() ?: return null
        val (allowance, share) = Allowance.entries.mapNotNull { a -> percent(a, latest)?.let { a to it } }.maxByOrNull { it.second }?.takeIf { it.second >= ALERT_AT.first() } ?: return null
        return "${allowance.label}: ${share}% of its free allowance (Settings)"
    }

    /**
     * The alerts [reading] gives (6.10.0), each share of [ALERT_AT] an allowance has reached, once a month for each: their words, and
     * [alerted] with them, an earlier month's let go.
     */
    fun alerts(alerted: Map<String, String>, reading: StorageReading, zone: ZoneId): Pair<List<String>, Map<String, String>> {
        val month = Instant.ofEpochMilli(reading.at).atZone(zone).toLocalDate().withDayOfMonth(1).toString().take(7)
        val kept = alerted.filterValues { it == month }.toMutableMap()
        val said = mutableListOf<String>()
        for (allowance in Allowance.entries) {
            val share = percent(allowance, reading) ?: continue
            // The highest share it's reached, once: past 95, the 80 isn't said as well.
            val at = ALERT_AT.lastOrNull { share >= it } ?: continue
            val key = "${allowance.name}:$at"
            if (key in kept) continue
            ALERT_AT.filter { it <= at }.forEach { kept["${allowance.name}:$it"] = month }
            val used = used(allowance, reading)!!
            said += "${allowance.label} is at $share% of its free allowance (${amount(allowance, used)} of ${amount(allowance, limit(allowance, reading))})."
        }
        return said to kept
    }
}

/**
 * Cloudflare's analytics for R2's bucket (6.10.0): what it holds, and this month's requests, by a token that can read analytics alone
 * (`CF_ANALYTICS_TOKEN`), one question each a day. Nothing of the stores goes: the account and the bucket's names.
 */
/** What R2 holds and its requests this month (6.10.0), and why where they couldn't be read: Cloudflare's, or a test's. */
fun interface R2Reader {
    fun read(now: Long): Triple<Long?, Pair<Long, Long>?, String?>
}

class CloudflareR2(private val token: String, private val account: String, private val bucket: String, private val timeout: Duration = Duration.ofSeconds(30)) : R2Reader {
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** What R2 holds and its requests this month, at [now]; and why, where it couldn't be read. */
    override fun read(now: Long): Triple<Long?, Pair<Long, Long>?, String?> {
        val end = Instant.ofEpochMilli(now)
        val stored = ask(STORED, end.minus(Duration.ofDays(2)), end)
        val month = end.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val requests = ask(REQUESTS, month, end)
        val bytes = (stored as? Answer.Read)?.body?.let(StorageRules::stored)
        val counts = (requests as? Answer.Read)?.body?.let(StorageRules::requests)
        val problem = listOf(stored, requests).firstNotNullOfOrNull { (it as? Answer.Failed)?.why }
            ?: listOfNotNull((stored as? Answer.Read)?.body, (requests as? Answer.Read)?.body).firstNotNullOfOrNull(StorageRules::problem)
        return Triple(bytes, counts, problem)
    }

    private sealed interface Answer {
        class Read(val body: JsonObject) : Answer
        class Failed(val why: String) : Answer
    }

    private fun ask(query: String, start: Instant, end: Instant): Answer {
        val body = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject {
                put("account", account)
                put("bucket", bucket)
                put("start", start.toString())
                put("end", end.toString())
            })
        }
        val request = HttpRequest.newBuilder(URI.create("https://api.cloudflare.com/client/v4/graphql")).timeout(timeout)
            .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        return try {
            val answer = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (answer.statusCode() != 200) Answer.Failed("Cloudflare answered ${answer.statusCode()}") else Answer.Read(json.parseToJsonElement(answer.body()).jsonObject)
        } catch (failed: Exception) {
            Answer.Failed("Cloudflare couldn't be reached (${failed::class.simpleName})")
        }
    }

    companion object {
        private const val STORED = "query(\$account: string!, \$bucket: string, \$start: Time, \$end: Time) { viewer { accounts(filter: {accountTag: \$account}) { " +
            "r2StorageAdaptiveGroups(limit: 1, filter: {datetime_geq: \$start, datetime_leq: \$end, bucketName: \$bucket}, orderBy: [datetime_DESC]) { max { objectCount payloadSize metadataSize } dimensions { datetime } } } } }"
        private const val REQUESTS = "query(\$account: string!, \$bucket: string, \$start: Time, \$end: Time) { viewer { accounts(filter: {accountTag: \$account}) { " +
            "r2OperationsAdaptiveGroups(limit: 100, filter: {datetime_geq: \$start, datetime_leq: \$end, bucketName: \$bucket}) { sum { requests } dimensions { actionType } } } } }"

        /** From the box's `.env`: none where any of the three is missing, and R2 says it isn't read. */
        fun from(env: (String) -> String?): CloudflareR2? {
            val token = env("CF_ANALYTICS_TOKEN")?.takeIf { it.isNotBlank() } ?: return null
            val account = env("R2_ACCOUNT_ID")?.takeIf { it.isNotBlank() } ?: return null
            val bucket = env("R2_BUCKET")?.takeIf { it.isNotBlank() } ?: return null
            return CloudflareR2(token, account, bucket)
        }
    }
}

/**
 * The storage watched (6.10.0, D-161): a reading a day ([read], one of the server's jobs), a month of them kept with the alerts given in
 * [file], never a store; the nightly files written to B2 noted as they go ([noteB2]); a reading's alerts handed to [alert] as one, a line each.
 */
class StorageWatch(
    private val file: File,
    private val dataDir: File,
    private val r2: R2Reader?,
    private val clock: WallClock,
    private val alert: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var held: StorageRecord? = null

    /** The record as it is: read from its file the first time, under the writers' lock, so a first read never puts back one older than theirs. */
    @Synchronized
    fun record(): StorageRecord = held ?: (runCatching { json.decodeFromString(StorageRecord.serializer(), file.readText()) }.getOrNull() ?: StorageRecord()).also { held = it }

    /** A nightly file of [bytes] written to B2 now: kept in the record while B2 keeps it. */
    @Synchronized
    fun noteB2(bytes: Long) {
        val now = clock.now()
        record().let { save(it.copy(b2Writes = (it.b2Writes + B2Write(now, bytes)).filter { w -> now - w.at < StorageRules.B2_KEPT_MS })) }
    }

    /** Today's reading, kept with the month's, and the alerts it gives sent. What can't be read is said in it, never thrown. */
    suspend fun read() {
        val now = clock.now()
        val reading = withContext(Dispatchers.IO) {
            val (stored, requests, problem) = r2?.read(now) ?: Triple(null, null, "no analytics token is set on the box")
            val kept = record().b2Writes.filter { now - it.at < StorageRules.B2_KEPT_MS }.sumOf { it.bytes }
            StorageReading(
                at = now,
                database = File(dataDir, DATABASE).length(),
                log = File(dataDir, "$DATABASE-wal").length(),
                photos = File(dataDir, "photos").listFiles()?.sumOf { it.length() } ?: 0,
                diskFree = dataDir.usableSpace,
                diskTotal = dataDir.totalSpace,
                r2Stored = stored,
                r2ClassA = requests?.first,
                r2ClassB = requests?.second,
                r2Problem = problem,
                b2Kept = kept,
            )
        }
        val (said, alerted) = synchronized(this) {
            val was = record()
            StorageRules.alerts(was.alerted, reading, clock.zone()).also { (_, alerted) ->
                save(was.copy(readings = (was.readings + reading).takeLast(StorageRules.KEPT_READINGS), alerted = alerted))
            }
        }
        if (reading.r2Problem != null) Log.w(Log.TAG, "R2's storage couldn't be read: ${reading.r2Problem}")
        // A reading's alerts as one, as each goes under the same name and would take another's place.
        if (said.isNotEmpty()) alert(said.joinToString("\n"))
    }

    /** [record] kept, whole, under a name of its own first, so a stop part-way leaves the one before. */
    private fun save(record: StorageRecord) {
        held = record
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        part.writeText(json.encodeToString(StorageRecord.serializer(), record))
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /** The database's file in the data folder, as `Main` opens it. */
        const val DATABASE = "decrastination.db"
    }
}
