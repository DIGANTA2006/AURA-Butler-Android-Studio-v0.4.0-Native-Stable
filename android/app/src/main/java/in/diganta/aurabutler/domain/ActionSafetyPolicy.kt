package `in`.diganta.aurabutler.domain

import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ButlerAction
import java.net.URI
import java.time.Instant

data class SafetyCheck(
    val allowed: Boolean,
    val reason: String,
    val targetLabel: String = ""
)

object ActionSafetyPolicy {
    fun check(action: ButlerAction, nowEpochMs: Long = System.currentTimeMillis()): SafetyCheck {
        return when (action.kind) {
            ActionKind.OPEN_URL,
            ActionKind.PLAY_MEDIA,
            ActionKind.WHATSAPP_MESSAGE,
            ActionKind.FOOD_SEARCH,
            ActionKind.TRAVEL_SEARCH,
            ActionKind.SHOPPING_SEARCH -> checkHttpsUrl(action.payload["url"])

            ActionKind.OPEN_APP,
            ActionKind.OPEN_APP_STORAGE -> {
                val app = action.payload["app"].orEmpty().trim()
                if (app.isBlank() || app.length > 80) {
                    SafetyCheck(false, "The requested app name is invalid.")
                } else {
                    SafetyCheck(true, "Android will open the matching installed app.", app)
                }
            }

            ActionKind.OPEN_SYSTEM_SETTING -> {
                val setting = action.payload["setting"].orEmpty()
                if (
                    setting in setOf(
                        "wifi", "bluetooth", "display", "sound",
                        "battery", "location", "apps", "general",
                        "notification_access", "storage"
                    )
                ) {
                    SafetyCheck(true, "Android will open this settings screen.", setting)
                } else {
                    SafetyCheck(false, "That settings screen is not allowlisted.")
                }
            }

            ActionKind.DIAL_PHONE -> {
                val phone = action.payload["phone"].orEmpty()
                if (phone.matches(Regex("""[+()\-\s0-9]{5,25}"""))) {
                    SafetyCheck(true, "The Android dialer will open; the call is not placed.", phone)
                } else {
                    SafetyCheck(false, "The proposed phone number is invalid.")
                }
            }

            ActionKind.MAP_ROUTE -> {
                val destination = action.payload["address"]
                    ?: action.payload["query"]
                    ?: ""
                if (destination.isBlank() || destination.length > 300) {
                    SafetyCheck(false, "The proposed destination is missing or invalid.")
                } else {
                    SafetyCheck(true, "A maps app will open.", destination)
                }
            }

            ActionKind.CALENDAR_EVENT -> {
                val title = action.payload["title"].orEmpty()
                val start = action.payload["startIso"]?.let(::parseInstant)
                if (title.isBlank() || start == null) {
                    SafetyCheck(false, "The calendar title or start time is invalid.")
                } else {
                    SafetyCheck(true, "The calendar editor will open.", title)
                }
            }

            ActionKind.SET_ALARM -> {
                val hour = action.payload["hour"]?.toIntOrNull()
                val minute = action.payload["minute"]?.toIntOrNull()
                if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                    SafetyCheck(
                        true,
                        "Android's alarm screen will open.",
                        "%02d:%02d".format(hour, minute)
                    )
                } else {
                    SafetyCheck(false, "The proposed alarm time is invalid.")
                }
            }

            ActionKind.SET_TIMER -> {
                val seconds = action.payload["seconds"]?.toIntOrNull()
                if (seconds != null && seconds in 1..86_400) {
                    SafetyCheck(
                        true,
                        "Android's timer screen will open.",
                        "${seconds}s"
                    )
                } else {
                    SafetyCheck(false, "The proposed timer duration is invalid.")
                }
            }

            ActionKind.DRAFT_MESSAGE -> {
                val phone = action.payload["phone"].orEmpty()
                val body = action.payload["body"].orEmpty()
                if (
                    phone.matches(Regex("""[+()\-\s0-9]{5,25}""")) &&
                    body.isNotBlank() &&
                    body.length <= 2000
                ) {
                    SafetyCheck(
                        true,
                        "The message composer will open; AURA cannot press Send.",
                        phone
                    )
                } else {
                    SafetyCheck(false, "The message recipient or body is invalid.")
                }
            }

            ActionKind.COMPOSE_EMAIL -> {
                val recipient = action.payload["recipient"].orEmpty().trim()
                val subject = action.payload["subject"].orEmpty()
                val body = action.payload["body"].orEmpty()
                val validRecipient = recipient.isBlank() || recipient.matches(
                    Regex("""[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
                )
                if (
                    validRecipient &&
                    subject.length <= 300 &&
                    body.length <= 8_000
                ) {
                    SafetyCheck(
                        true,
                        "The email composer will open; AURA cannot press Send.",
                        recipient.ifBlank { "Email composer" }
                    )
                } else {
                    SafetyCheck(false, "The email recipient or content is invalid.")
                }
            }

            ActionKind.SHARE_TEXT -> {
                val body = action.payload["body"].orEmpty()
                if (body.isBlank() || body.length > 4000) {
                    SafetyCheck(false, "The proposed text is empty or too long.")
                } else {
                    SafetyCheck(true, "Android's share sheet will open.", "Share sheet")
                }
            }

            ActionKind.SYSTEM_VOLUME -> {
                val operation = action.payload["action"].orEmpty()
                val amount = action.payload["amount"]?.toIntOrNull()
                if (
                    operation in setOf(
                        "up", "down", "set", "mute", "unmute", "max", "min"
                    ) &&
                    action.payload["stream"].orEmpty().ifBlank { "media" } in setOf(
                        "media", "ring", "alarm", "notification", "call", "system"
                    ) &&
                    (operation != "set" || (amount != null && amount in 0..100))
                ) {
                    SafetyCheck(true, "AURA will change a local audio stream.", operation)
                } else {
                    SafetyCheck(false, "The proposed volume change is invalid.")
                }
            }

            ActionKind.SYSTEM_BRIGHTNESS -> {
                val level = action.payload["level"]?.toIntOrNull()
                if (level != null && level in 1..100) {
                    SafetyCheck(true, "AURA will change system brightness.", "$level%")
                } else {
                    SafetyCheck(false, "Brightness must be from 1 to 100 percent.")
                }
            }

            ActionKind.FLASHLIGHT -> {
                val enabled = action.payload["enabled"]
                if (enabled == "true" || enabled == "false") {
                    SafetyCheck(
                        true,
                        "AURA will change the camera flashlight.",
                        if (enabled == "true") "On" else "Off"
                    )
                } else {
                    SafetyCheck(false, "The flashlight action is invalid.")
                }
            }

            ActionKind.OPEN_CAMERA ->
                SafetyCheck(true, "Android's camera app will open.", "Camera")

            ActionKind.IDENTIFY_SONG ->
                SafetyCheck(
                    true,
                    "The installed system song-identification experience will open.",
                    "Song identification"
                )

            ActionKind.ANALYZE_SCREEN,
            ActionKind.CAPTURE_SCREEN -> {
                val prompt = action.payload["prompt"].orEmpty()
                if (prompt.length <= 1_200) {
                    SafetyCheck(
                        true,
                        "Android will request one explicit screen-sharing approval.",
                        "One screen frame"
                    )
                } else {
                    SafetyCheck(false, "The screen-analysis prompt is too long.")
                }
            }

            ActionKind.CLEAR_AURA_CACHE ->
                SafetyCheck(
                    true,
                    "Only AURA's temporary cache will be deleted.",
                    "AURA cache"
                )

            ActionKind.COOLING_ASSIST ->
                SafetyCheck(
                    true,
                    "AURA will reduce display power use when permitted and open Battery Saver controls.",
                    "Cooling Assist"
                )

            ActionKind.ANALYZE_IMAGE -> {
                val prompt = action.payload["prompt"].orEmpty()
                if (prompt.length <= 1_200) {
                    SafetyCheck(
                        true,
                        "You will choose one image before AURA analyzes it.",
                        "Selected image"
                    )
                } else {
                    SafetyCheck(false, "The image-analysis prompt is too long.")
                }
            }

            ActionKind.REMINDER -> {
                val title = action.payload["title"].orEmpty()
                val triggerAt = action.payload["triggerAtIso"]?.let(::parseInstant)
                if (
                    title.isBlank() ||
                    title.length > 300 ||
                    triggerAt == null ||
                    triggerAt.toEpochMilli() <= nowEpochMs
                ) {
                    SafetyCheck(false, "The reminder title or future time is invalid.")
                } else {
                    SafetyCheck(true, "A local notification will be scheduled.", title)
                }
            }
        }
    }

    private fun checkHttpsUrl(rawUrl: String?): SafetyCheck {
        if (rawUrl.isNullOrBlank() || rawUrl.length > 2000) {
            return SafetyCheck(false, "The proposed web address is missing or too long.")
        }
        return runCatching {
            val uri = URI(rawUrl)
            when {
                uri.scheme.lowercase() != "https" ->
                    SafetyCheck(false, "Only secure HTTPS links are allowed.")
                uri.host.isNullOrBlank() ->
                    SafetyCheck(false, "The proposed web address has no valid domain.")
                uri.userInfo != null ->
                    SafetyCheck(false, "Links containing embedded credentials are blocked.")
                else ->
                    SafetyCheck(
                        true,
                        "A secure web page or installed app will open.",
                        uri.host.lowercase()
                    )
            }
        }.getOrElse {
            SafetyCheck(false, "The proposed web address is invalid.")
        }
    }

    private fun parseInstant(value: String): Instant? = runCatching {
        Instant.parse(value)
    }.getOrNull()
}
