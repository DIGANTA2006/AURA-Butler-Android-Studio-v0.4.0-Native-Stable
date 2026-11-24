package `in`.diganta.aurabutler.intelligence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.diganta.aurabutler.domain.ActionSafetyPolicy
import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.planning.AndroidPlanningRuntime
import `in`.diganta.aurabutler.runtime.SecretRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/** Production action gateway for accepted proactive suggestions. */
class AndroidProactiveActionHandler(
    context: Context,
    private val planningRuntime: AndroidPlanningRuntime
) : ProactiveEngine.ActionHandler {
    private val appContext = context.applicationContext

    override suspend fun execute(
        action: ProactiveEngine.ProactiveAction,
        context: ConversationContext
    ): ProactiveEngine.ActionResult = withContext(Dispatchers.IO) {
        try {
            when (action.type) {
                ProactiveEngine.ActionType.SHOW_NOTIFICATION -> postNotification(action)
                ProactiveEngine.ActionType.ASK_QUESTION ->
                    ProactiveEngine.ActionResult(
                        completed = true,
                        detail = action.payload["question"]?.toString()
                            ?: "AURA has a follow-up question for you."
                    )
                ProactiveEngine.ActionType.SHOW_INSIGHT ->
                    ProactiveEngine.ActionResult(
                        completed = true,
                        detail = action.payload["insight"]?.toString()
                            ?: "The proactive insight has been marked as reviewed."
                    )
                ProactiveEngine.ActionType.OPEN_APP -> openAppProposal(action)
                ProactiveEngine.ActionType.CREATE_REMINDER -> reminderProposal(action)
                ProactiveEngine.ActionType.SCHEDULE_TASK -> runPlan(action, context, false)
                ProactiveEngine.ActionType.START_RESEARCH -> runPlan(action, context, true)
                ProactiveEngine.ActionType.RUN_COMMAND ->
                    ProactiveEngine.ActionResult(
                        completed = false,
                        error = "AURA blocks arbitrary command execution from proactive suggestions."
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SecurityException) {
            ProactiveEngine.ActionResult(
                completed = false,
                error = "Android denied the operation: " +
                    (error.message ?: "permission not granted")
            )
        } catch (error: Exception) {
            ProactiveEngine.ActionResult(
                completed = false,
                error = (error.message ?: error.javaClass.simpleName).take(500)
            )
        }
    }

    private fun postNotification(action: ProactiveEngine.ProactiveAction): ProactiveEngine.ActionResult {
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return ProactiveEngine.ActionResult(
                completed = false,
                error = "AURA notifications are disabled in Android settings."
            )
        }
        val title = action.payload["title"]?.toString()?.trim()?.take(80)
            .orEmpty().ifBlank { "AURA suggestion" }
        val raw = (action.payload["message"] ?: action.payload["body"])?.toString().orEmpty()
        if (raw.isBlank() || SecretRedactor.containsLikelySecret(raw)) {
            return ProactiveEngine.ActionResult(
                completed = false,
                error = "The notification content is empty or contains a likely secret."
            )
        }
        val message = SecretRedactor.redact(raw, 500)
        createNotificationChannel()
        val intent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: return ProactiveEngine.ActionResult(
                completed = false,
                error = "AURA could not resolve its launcher activity for the notification."
            )
        val pending = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(appContext).notify(
            (title + message).hashCode().and(0x7fffffff).coerceAtLeast(1),
            notification
        )
        return ProactiveEngine.ActionResult(
            completed = true,
            detail = "AURA notification posted: " + title
        )
    }

    private fun openAppProposal(
        action: ProactiveEngine.ProactiveAction
    ): ProactiveEngine.ActionResult {
        val app = action.payload["app"]?.toString()?.trim().orEmpty()
        return if (app.equals("Settings > Storage", ignoreCase = true)) {
            approval(
                kind = ActionKind.OPEN_SYSTEM_SETTING,
                title = "Open Android storage settings",
                summary = "Review storage usage and choose what to remove.",
                payload = mapOf("setting" to "storage")
            )
        } else {
            approval(
                kind = ActionKind.OPEN_APP,
                title = "Open suggested app",
                summary = "Open the app suggested by AURA.",
                payload = mapOf("app" to app)
            )
        }
    }

    private fun reminderProposal(
        action: ProactiveEngine.ProactiveAction
    ): ProactiveEngine.ActionResult {
        val title = action.payload["title"]?.toString()?.trim().orEmpty()
        val iso = action.payload["triggerAtIso"]?.toString()?.trim().orEmpty()
        if (title.isBlank() || iso.isBlank()) {
            return ProactiveEngine.ActionResult(
                completed = false,
                error = "A reminder requires a title and an exact triggerAtIso timestamp."
            )
        }
        val timestamp = try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            return ProactiveEngine.ActionResult(
                completed = false,
                error = "The reminder timestamp must be valid ISO-8601 UTC."
            )
        }
        val now = System.currentTimeMillis()
        if (timestamp <= now || timestamp > now + MAX_REMINDER_HORIZON_MS) {
            return ProactiveEngine.ActionResult(
                completed = false,
                error = "The reminder must be in the future and within 365 days."
            )
        }
        return approval(
            kind = ActionKind.REMINDER,
            title = "Create reminder: " + title.take(80),
            summary = "Create a reminder at " + iso,
            payload = mapOf("title" to title.take(160), "triggerAtIso" to iso),
            risk = ActionRisk.HIGH
        )
    }

    private suspend fun runPlan(
        action: ProactiveEngine.ProactiveAction,
        context: ConversationContext,
        research: Boolean
    ): ProactiveEngine.ActionResult {
        val goal = (
            action.payload["goal"] ?: action.payload["topic"] ?: action.payload["task"]
        )?.toString()?.trim().orEmpty()
        if (goal.isBlank() || SecretRedactor.containsLikelySecret(goal)) {
            return ProactiveEngine.ActionResult(
                completed = false,
                error = "The proposed plan goal is missing or contains private credentials."
            )
        }
        val plan = planningRuntime.planner.createPlanFromGoal(
            goal = if (research) "Research: " + goal else goal,
            context = context
        )
        val result = planningRuntime.planner.executePlan(plan.id, context)
        return when {
            result.pendingApproval != null ->
                ProactiveEngine.ActionResult(
                    completed = false,
                    detail = "Plan " + plan.id + " is waiting for your approval.",
                    pendingApproval = result.pendingApproval
                )
            result.success ->
                ProactiveEngine.ActionResult(
                    completed = true,
                    detail = "Plan '" + plan.title + "' completed (ID " + plan.id + ")."
                )
            else ->
                ProactiveEngine.ActionResult(
                    completed = false,
                    error = result.errorSummary ?: "Plan " + plan.id + " did not complete."
                )
        }
    }

    private fun approval(
        kind: ActionKind,
        title: String,
        summary: String,
        payload: Map<String, String>,
        risk: ActionRisk = ActionRisk.MEDIUM
    ): ProactiveEngine.ActionResult {
        if (payload.values.any { SecretRedactor.containsLikelySecret(it) }) {
            return ProactiveEngine.ActionResult(
                completed = false,
                error = "AURA blocked an approval payload containing private credentials."
            )
        }
        val proposal = ButlerAction(
            id = UUID.randomUUID().toString(),
            kind = kind,
            title = title.take(100),
            summary = summary.take(500),
            risk = risk,
            payload = payload
        )
        val check = ActionSafetyPolicy.check(proposal)
        if (!check.allowed) {
            return ProactiveEngine.ActionResult(
                completed = false,
                error = "AURA action policy rejected the proposal: " + check.reason
            )
        }
        return ProactiveEngine.ActionResult(
            completed = false,
            detail = "Waiting for explicit approval.",
            pendingApproval = proposal
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        appContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Accepted AURA proactive notifications"
                }
            )
    }

    private companion object {
        const val CHANNEL_ID = "aura_accepted_proactive"
        const val CHANNEL_NAME = "AURA proactive suggestions"
        const val NOTIFICATION_REQUEST_CODE = 7410
        const val MAX_REMINDER_HORIZON_MS = 365L * 24 * 60 * 60 * 1_000
    }
}
