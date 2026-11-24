package `in`.diganta.aurabutler.runtime

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class AiWorkload(val wireName: String) {
    TEXT("text"),
    VISION("vision"),
    RESEARCH("research"),
    AUXILIARY("auxiliary")
}

data class BudgetDecision(
    val allowed: Boolean,
    val remainingToday: Int?,
    val message: String
)

data class BudgetLease internal constructor(
    val allowed: Boolean,
    val remainingToday: Int?,
    val message: String,
    internal val token: String? = null,
    internal val workload: AiWorkload? = null,
    internal val metered: Boolean = false
)

data class BudgetSnapshot(
    val utcDay: String,
    val text: Int = 0,
    val vision: Int = 0,
    val research: Int = 0,
    val auxiliary: Int = 0
) {
    fun count(workload: AiWorkload): Int = when (workload) {
        AiWorkload.TEXT -> text
        AiWorkload.VISION -> vision
        AiWorkload.RESEARCH -> research
        AiWorkload.AUXILIARY -> auxiliary
    }

    fun increment(workload: AiWorkload): BudgetSnapshot = add(workload, 1)

    fun add(workload: AiWorkload, amount: Int): BudgetSnapshot {
        val safe = amount.coerceAtLeast(0)
        return when (workload) {
            AiWorkload.TEXT -> copy(text = text + safe)
            AiWorkload.VISION -> copy(vision = vision + safe)
            AiWorkload.RESEARCH -> copy(research = research + safe)
            AiWorkload.AUXILIARY -> copy(auxiliary = auxiliary + safe)
        }
    }
}

/**
 * Student budget limits loaded from remote manifest (with hardcoded fallbacks).
 * Limits are fetched once at startup via StudentBudgetConfig.initialize().
 */
object StudentBudgetPolicy {
    /** Current daily text request limit (from remote manifest or fallback). */
    val DAILY_TEXT_LIMIT: Int
        get() = StudentBudgetConfig.DAILY_TEXT_LIMIT

    /** Current daily vision request limit (from remote manifest or fallback). */
    val DAILY_VISION_LIMIT: Int
        get() = StudentBudgetConfig.DAILY_VISION_LIMIT

    /** Current daily research request limit (from remote manifest or fallback). */
    val DAILY_RESEARCH_LIMIT: Int
        get() = StudentBudgetConfig.DAILY_RESEARCH_LIMIT

    /** Current daily auxiliary request limit (from remote manifest or fallback). */
    val DAILY_AUXILIARY_LIMIT: Int
        get() = StudentBudgetConfig.DAILY_AUXILIARY_LIMIT

    fun limitFor(workload: AiWorkload): Int = when (workload) {
        AiWorkload.TEXT -> DAILY_TEXT_LIMIT
        AiWorkload.VISION -> DAILY_VISION_LIMIT
        AiWorkload.RESEARCH -> DAILY_RESEARCH_LIMIT
        AiWorkload.AUXILIARY -> DAILY_AUXILIARY_LIMIT
    }

    fun isMeteredEndpoint(endpoint: String): Boolean {
        val host = runCatching { URI(endpoint.trim()).host }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return host !in setOf(
            "localhost",
            "127.0.0.1",
            "::1",
            "0:0:0:0:0:0:0:1"
        )
    }

    fun evaluate(
        workload: AiWorkload,
        snapshot: BudgetSnapshot
    ): BudgetDecision {
        val limit = limitFor(workload)
        if (limit == 0) {
            return BudgetDecision(true, null, "Unlimited by owner configuration.")
        }
        val used = snapshot.count(workload)
        if (used >= limit) {
            return BudgetDecision(
                allowed = false,
                remainingToday = 0,
                message = limitReachedMessage(workload, limit)
            )
        }
        return BudgetDecision(
            allowed = true,
            remainingToday = (limit - used - 1).coerceAtLeast(0),
            message = "External AI request allowed."
        )
    }

    private fun limitReachedMessage(workload: AiWorkload, limit: Int): String = buildString {
        append(
            "The student-budget ${workload.wireName} limit of $limit external AI requests " +
                "has been reached for today. It resets at ${localResetTimeText()}."
        )
        if (workload == AiWorkload.RESEARCH) {
            append(
                " AURA can still use cached results, private Lite Search, and the " +
                    "remaining private-Qwen text budget for sourced answers."
            )
        }
        append(
            " Local commands, encrypted memory, reminders, weather and keyless news " +
                "remain available directly on the phone."
        )
    }

    fun summary(snapshot: BudgetSnapshot): String = buildString {
        append("Student budget for ${snapshot.utcDay} UTC: ")
        append("text ${snapshot.text}/${label(DAILY_TEXT_LIMIT)}, ")
        append("vision ${snapshot.vision}/${label(DAILY_VISION_LIMIT)}, ")
        append("research ${snapshot.research}/${label(DAILY_RESEARCH_LIMIT)}, ")
        append("small helper calls ${snapshot.auxiliary}/${label(DAILY_AUXILIARY_LIMIT)}. ")
        append("Next reset: ${localResetTimeText()}. Local phone features are not counted.")
    }

    private fun localResetTimeText(): String = runCatching {
        val nextUtcMidnight = LocalDate.now(ZoneOffset.UTC)
            .plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
        nextUtcMidnight
            .withZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd MMM, h:mm a", Locale.ENGLISH))
    }.getOrDefault("the next UTC day")

    private fun label(limit: Int): String = if (limit == 0) "unlimited" else limit.toString()
}

/**
 * Local non-secret usage ledger.
 *
 * v0.17.2 uses a reservation/commit flow: selecting a provider does not burn a
 * local quota unit. A unit is committed only after that provider returns a
 * usable response. Failed fallbacks release their reservation.
 */
object StudentBudgetGuard {
    private data class Reservation(
        val workload: AiWorkload,
        val createdAtEpochMs: Long
    )

    private val lock = Any()
    private val reservations = LinkedHashMap<String, Reservation>()
    private val initializationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var appContext: Context? = null
    @Volatile private var initializationJob: Job? = null

    /**
     * Startup must remain non-blocking. The hardcoded safe limits are available
     * immediately; remote manifest limits are loaded on IO and atomically applied.
     */
    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        synchronized(lock) {
            if (StudentBudgetConfig.isInitialized() || initializationJob?.isActive == true) {
                return
            }
            initializationJob = initializationScope.launch {
                // Preserve structured cancellation; a stopped app scope must not
                // turn CancellationException into an ordinary config failure.
                runCatchingCancellable { StudentBudgetConfig.initialize(app) }
            }
        }
    }

    fun tryReserve(
        workload: AiWorkload,
        endpoint: String
    ): BudgetLease {
        if (!StudentBudgetPolicy.isMeteredEndpoint(endpoint)) {
            return BudgetLease(
                allowed = true,
                remainingToday = null,
                message = "Local endpoint is not charged against the cloud budget.",
                workload = workload,
                metered = false
            )
        }

        synchronized(lock) {
            pruneStaleReservationsLocked()
            val current = currentSnapshotLocked()
            val reserved = reservations.values.count { it.workload == workload }
            val decision = StudentBudgetPolicy.evaluate(
                workload,
                current.add(workload, reserved)
            )
            if (!decision.allowed) {
                return BudgetLease(
                    allowed = false,
                    remainingToday = decision.remainingToday,
                    message = decision.message,
                    workload = workload,
                    metered = true
                )
            }

            val token = UUID.randomUUID().toString()
            reservations[token] = Reservation(workload, System.currentTimeMillis())
            return BudgetLease(
                allowed = true,
                remainingToday = decision.remainingToday,
                message = decision.message,
                token = token,
                workload = workload,
                metered = true
            )
        }
    }

    fun commit(lease: BudgetLease) {
        if (!lease.allowed || !lease.metered) return
        val token = lease.token ?: return
        synchronized(lock) {
            val reservation = reservations.remove(token) ?: return
            val current = currentSnapshotLocked()
            writeSnapshotLocked(current.increment(reservation.workload))
        }
    }

    fun release(lease: BudgetLease) {
        val token = lease.token ?: return
        synchronized(lock) {
            reservations.remove(token)
        }
    }

    /**
     * Compatibility shim for older code. New provider paths should use
     * tryReserve()/commit()/release() so failures do not consume local quota.
     */
    @Deprecated("Use tryReserve/commit/release")
    fun tryAcquire(
        workload: AiWorkload,
        endpoint: String
    ): BudgetDecision {
        val lease = tryReserve(workload, endpoint)
        if (lease.allowed) commit(lease)
        return BudgetDecision(lease.allowed, lease.remainingToday, lease.message)
    }

    fun statusText(): String = synchronized(lock) {
        StudentBudgetPolicy.summary(currentSnapshotLocked())
    }

    internal fun currentSnapshotForTest(
        utcDay: String,
        stored: BudgetSnapshot?
    ): BudgetSnapshot = if (stored?.utcDay == utcDay) {
        stored
    } else {
        BudgetSnapshot(utcDay = utcDay)
    }

    private fun currentSnapshotLocked(): BudgetSnapshot {
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val stored = readSnapshotLocked()
        return currentSnapshotForTest(today, stored)
    }

    private fun pruneStaleReservationsLocked() {
        val cutoff = System.currentTimeMillis() - RESERVATION_TTL_MS
        val iterator = reservations.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.createdAtEpochMs < cutoff) iterator.remove()
        }
    }

    private fun readSnapshotLocked(): BudgetSnapshot? {
        val file = ledgerFile() ?: return null
        return runCatching {
            val root = file.openRead().use { input ->
                JSONObject(input.bufferedReader(Charsets.UTF_8).readText())
            }
            BudgetSnapshot(
                utcDay = root.optString("utc_day"),
                text = root.optInt("text", 0).coerceAtLeast(0),
                vision = root.optInt("vision", 0).coerceAtLeast(0),
                research = root.optInt("research", 0).coerceAtLeast(0),
                auxiliary = root.optInt("auxiliary", 0).coerceAtLeast(0)
            ).takeIf { it.utcDay.isNotBlank() }
        }.getOrNull()
    }

    private fun writeSnapshotLocked(snapshot: BudgetSnapshot) {
        val file = ledgerFile() ?: return
        val payload = JSONObject()
            .put("utc_day", snapshot.utcDay)
            .put("text", snapshot.text)
            .put("vision", snapshot.vision)
            .put("research", snapshot.research)
            .put("auxiliary", snapshot.auxiliary)
            .toString()
            .toByteArray(Charsets.UTF_8)
        var stream: java.io.FileOutputStream? = null
        try {
            val output = file.startWrite()
            stream = output
            output.write(payload)
            output.flush()
            file.finishWrite(output)
        } catch (_: Exception) {
            stream?.let { file.failWrite(it) }
        }
    }

    private fun ledgerFile(): AtomicFile? {
        val context = appContext ?: return null
        return AtomicFile(File(context.noBackupFilesDir, LEDGER_FILE))
    }

    private const val RESERVATION_TTL_MS = 10 * 60_000L
    private const val LEDGER_FILE = "aura-student-budget-v1.json"
}
