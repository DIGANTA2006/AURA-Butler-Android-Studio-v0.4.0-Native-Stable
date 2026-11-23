package `in`.diganta.aurabutler.domain

import `in`.diganta.aurabutler.data.AiProvider
import `in`.diganta.aurabutler.data.AuraIdentity
import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.ButlerReply
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.MemoryItem
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import java.util.UUID

class LocalButlerEngine(
    private val liveInfo: LiveInfoProvider,
    private val generalAi: AiProvider? = null
) {
    suspend fun chat(
        message: String,
        context: ConversationContext,
        onPartial: (String) -> Unit = {}
    ): ButlerReply {
        val parts = splitRequests(message).map { handleOne(it, context, onPartial) }
        val text = parts.mapNotNull { it.text.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
            .ifBlank {
                "I’m ready, ${context.settings.formOfAddress}. Please tell me what to handle."
            }
        return ButlerReply(
            assistantMessage = text,
            speechText = text.replace(Regex("""https?://\S+"""), "the prepared link"),
            pendingActions = parts.flatMap(Part::actions),
            memoryWrites = parts.flatMap(Part::memories)
        )
    }

    private suspend fun handleOne(
        raw: String,
        context: ConversationContext,
        onPartial: (String) -> Unit = {}
    ): Part {
        val request = raw.trim()
            .replaceFirst(Regex("""(?i)^(?:hey\s+)?aura[,:]?\s*"""), "")
            .trim()
        val lower = request.lowercase(Locale.ROOT)
        val address = context.settings.formOfAddress

        if (request.isBlank()) return Part("Yes, $address?")

        if (AuraIdentity.isCreatorQuestion(lower)) {
            return Part(AuraIdentity.creatorReply(address))
        }

        if (AuraIdentity.isOwnerProfileQuestion(lower)) {
            return Part(AuraIdentity.ownerReply(address))
        }

        if (AuraIdentity.isNameQuestion(lower)) {
            return Part(AuraIdentity.nameReply(address))
        }

        parseMemory(request, lower)?.let { memory ->
            if (containsSensitiveInformation(memory.value)) {
                return Part(
                    "For your safety, I will not save passwords, PINs, OTPs, card details, " +
                        "or identity numbers."
                )
            }
            return Part(
                text = "Certainly. I’ll remember that ${memory.value}. It stays encrypted on this phone.",
                memories = listOf(memory)
            )
        }

        if (lower.containsAny(
                "what do you remember", "what have you remembered", "show my memory",
                "মনে আছে", "क्या याद है"
            )
        ) {
            val memories = context.memories
            return if (memories.isEmpty()) {
                Part("I have not saved any personal preferences yet.")
            } else {
                Part(
                    "Here is what I remember on this phone:\n" +
                        memories.joinToString("\n") { "• ${it.label}: ${it.value}" }
                )
            }
        }

        parseReminder(request, lower)?.let { return it }
        parseAlarm(request, lower)?.let { return it }
        parseTimer(request, lower)?.let { return it }
        parseCalendar(request, lower)?.let { return it }
        parseWhatsApp(request, lower)?.let { return it }
        parseEmail(request, lower)?.let { return it }
        parseMessage(request, lower)?.let { return it }
        parseCall(request, lower)?.let { return it }
        parseNavigation(request, lower)?.let { return it }
        parseSystemSetting(request, lower)?.let { return it }
        parseVolume(request, lower)?.let { return it }
        parseBrightness(request, lower)?.let { return it }
        parseFlashlight(lower)?.let { return it }
        parseCamera(lower)?.let { return it }
        parseMusic(request, lower)?.let { return it }
        parseOpenApp(request, lower)?.let { return it }
        parseShare(request, lower)?.let { return it }
        parseCalculation(request, lower)?.let { return it }

        if (lower.containsAny("weather", "temperature", "forecast", "আবহাওয়া", "মৌসম", "मौसम")) {
            val requestedLocation = weatherLocation(request)
            val forecastDays = ForecastDayParser.parse(request)
            return runCatching {
                if (forecastDays != null) {
                    liveInfo.weatherForecast(
                        settings = context.settings,
                        location = requestedLocation,
                        days = forecastDays
                    )
                } else {
                    liveInfo.weatherFor(context.settings, requestedLocation)
                }
            }
                .fold(
                    onSuccess = { Part(it) },
                    onFailure = { error ->
                        Part(
                            if (error.message?.contains("find that place", true) == true) {
                                "I could not find that place. Try saying the town, district, or city name."
                            } else {
                                "I couldn’t reach the live weather feed just now. " +
                                    "Your private memory and normal phone commands still work offline."
                            }
                        )
                    }
                )
        }

        if (lower.containsAny("news", "headlines", "খবর", "समाचार")) {
            val topic = newsTopic(request)
            return runCatching { liveInfo.headlinesFor(5, topic) }
                .fold(
                    onSuccess = { headlines ->
                        if (headlines.isEmpty()) {
                            Part("The news feed is reachable, but it returned no headlines.")
                        } else {
                            Part(
                                "Here are the latest ${topic?.let { "$it " }.orEmpty()}headlines:\n" +
                                    headlines.mapIndexed { index, title ->
                                        "${index + 1}. $title"
                                    }.joinToString("\n")
                            )
                        }
                    },
                    onFailure = {
                        Part(
                            text = "I couldn’t read the live headlines just now. I can open Google News instead.",
                            actions = listOf(
                                webAction(
                                    title = "Open current news",
                                    summary = "Open the current India news feed.",
                                    url = "https://news.google.com/home?hl=en-IN&gl=IN&ceid=IN:en"
                                )
                            )
                        )
                    }
                )
        }

        if (lower.containsAny(
                "phone status", "device status", "system status", "battery status",
                "battery level", "battery percentage", "battery percent",
                "how much battery", "what is my battery", "free memory",
                "free storage", "system vitals"
            )
        ) {
            return Part(liveInfo.deviceStatus())
        }

        if (lower.matches(Regex(""".*\b(time|সময়|समय)\b.*"""))) {
            val now = LocalTime.now()
            return Part(
                "It is ${now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))}, $address."
            )
        }

        if (lower.matches(Regex(""".*\b(date|day|today|তারিখ|आज)\b.*"""))) {
            val date = LocalDate.now()
            return Part(
                "Today is ${date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))}."
            )
        }

        if (lower.containsAny("plan my day", "plan today", "আজকের পরিকল্পনা", "दिन की योजना")) {
            val memoryHint = context.memories.firstOrNull()?.let {
                " I’ll also keep your saved preference—${it.value}—in mind."
            }.orEmpty()
            return Part(
                "Certainly. Tell me your fixed appointments, your three most important tasks, " +
                    "and when you want to finish for the day.$memoryHint"
            )
        }

        parseFood(request, lower, context)?.let { return it }
        parseTravel(request, lower)?.let { return it }
        parseShopping(request, lower)?.let { return it }
        parseWebSearch(request, lower)?.let { return it }

        if (lower.containsAny(
                "what can you do", "help", "commands", "কি করতে পারো", "क्या कर सकते हो"
            )
        ) {
            return Part(
                "I can remember preferences and notes, manage reminders, alarms, timers and " +
                    "calendar drafts, open installed apps and Android settings, control media " +
                    "volume, brightness and flashlight, prepare calls, SMS, WhatsApp and email, " +
                    "open maps, camera and music, calculate, read phone status, weather, topic " +
                    "news and short internet knowledge answers, and prepare food, travel and " +
                    "shopping searches. Sensitive actions always ask for your approval."
            )
        }

        if (lower.containsAny("hello", "hi", "good morning", "good evening", "নমস্কার", "হ্যালো", "नमस्ते")) {
            return Part("At your service, $address. What may I take care of?")
        }

        if (lower.containsAny("how are you", "how do you feel")) {
            return Part("All systems are ready, $address. What shall I handle?")
        }

        if (lower.containsAny("tell me a joke", "make me laugh", "joke")) {
            return Part(
                listOf(
                    "Why did the programmer prefer dark mode? Because light attracts bugs.",
                    "I told the computer I needed a break. It replied: no problem, I’ll go to sleep.",
                    "There are ten kinds of people: those who understand binary and those who do not."
                )[request.hashCode().and(Int.MAX_VALUE) % 3]
            )
        }

        if (lower.containsAny("thank you", "thanks", "ধন্যবাদ", "शुक्रिया", "धन्यवाद")) {
            return Part("Always a pleasure, $address.")
        }

        if (isKnowledgeRequest(lower)) {
            val topic = knowledgeTopic(request)
            val answer = try {
                liveInfo.knowledge(topic)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            return if (answer.isNullOrBlank()) {
                knowledgeSearch(topic)
            } else {
                Part(answer)
            }
        }

        val aiAttempt = try {
            Result.success(generalAi?.answerStreaming(request, context, onPartial))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

        aiAttempt.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let { return Part(it) }

        val optionalAiConfigured = context.settings.aiEnabled && when (
            context.settings.aiProvider
        ) {
            "local" -> context.settings.localAiEnabled &&
                context.settings.localAiEndpoint.isNotBlank()
            "gemini" -> context.settings.geminiApiKey.isNotBlank()
            "groq" -> context.settings.groqApiKey.isNotBlank()
            else -> (context.settings.localAiEnabled &&
                context.settings.localAiEndpoint.isNotBlank()) ||
                (context.settings.allowPublicAiFallback &&
                    (context.settings.geminiApiKey.isNotBlank() ||
                        context.settings.groqApiKey.isNotBlank()))
        }

        if (optionalAiConfigured && aiAttempt.isFailure) {
            return Part(
                aiAttempt.exceptionOrNull()?.message
                    ?: "The optional AI service could not answer just now."
            )
        }

        return Part(
            "I handled everything available locally. This request needs optional AI reasoning. " +
                "Enable private Qwen, explicitly select Gemini/Groq, or approve a web search.",
            actions = listOf(
                searchAction(
                    kind = ActionKind.OPEN_URL,
                    title = "Search this request",
                    summary = "Search the web for: $request",
                    query = request
                )
            )
        )
    }

    private fun parseMemory(request: String, lower: String): MemoryItem? {
        val prefixes = listOf(
            "remember that ", "remember ", "please remember that ",
            "make a note that ", "take a note that ", "note that ",
            "মনে রাখো ", "याद रखो "
        )
        val prefix = prefixes.firstOrNull(lower::startsWith) ?: return null
        val value = request.drop(prefix.length).trim().trimEnd('.', '!', '?')
        if (value.isBlank()) return null
        val label = value
            .replace(Regex("""\s+"""), " ")
            .take(42)
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        return MemoryItem(label = label, value = value)
    }

    private fun parseReminder(request: String, lower: String): Part? {
        val patterns = listOf(
            Regex(
                """(?i)\bremind\s+me\s+in\s+(\d+)\s*(minute|minutes|min|hour|hours|day|days)\s+(?:to\s+)?(.+)"""
            ),
            Regex(
                """(?i)\bremind\s+me\s+to\s+(.+?)\s+in\s+(\d+)\s*(minute|minutes|min|hour|hours|day|days)\b"""
            )
        )
        val first = patterns[0].find(request)
        val second = patterns[1].find(request)
        val amount: Long
        val unit: String
        val title: String
        when {
            first != null -> {
                amount = first.groupValues[1].toLongOrNull() ?: return null
                unit = first.groupValues[2]
                title = first.groupValues[3].trim()
            }

            second != null -> {
                title = second.groupValues[1].trim()
                amount = second.groupValues[2].toLongOrNull() ?: return null
                unit = second.groupValues[3]
            }

            else -> return if (lower.startsWith("remind me")) {
                Part("Tell me both the task and a relative time, for example: “Remind me in 20 minutes to drink water.”")
            } else {
                null
            }
        }
        if (amount !in 1..10_000 || title.isBlank()) return Part("That reminder time is not valid.")
        val duration = when {
            unit.startsWith("min", true) -> Duration.ofMinutes(amount)
            unit.startsWith("hour", true) -> Duration.ofHours(amount)
            else -> Duration.ofDays(amount)
        }
        val trigger = Instant.now().plus(duration)
        val formatted = ZonedDateTime.ofInstant(trigger, java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEE, d MMM 'at' h:mm a", Locale.ENGLISH))
        return Part(
            text = "I prepared a private reminder for “$title” on $formatted.",
            actions = listOf(
                ButlerAction(
                    id = UUID.randomUUID().toString(),
                    kind = ActionKind.REMINDER,
                    title = "Schedule reminder",
                    summary = "Remind you to $title on $formatted.",
                    risk = ActionRisk.LOW,
                    payload = mapOf(
                        "title" to title.take(300),
                        "triggerAtIso" to trigger.toString()
                    )
                )
            )
        )
    }

    private fun parseAlarm(request: String, lower: String): Part? {
        if (!lower.contains("alarm")) return null
        val match = Regex(
            """(?i)\balarm(?:\s+for|\s+at)?\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?"""
        ).find(request) ?: return Part("Tell me the alarm time, for example: “Set an alarm for 6:30 AM.”")
        var hour = match.groupValues[1].toIntOrNull() ?: return Part("That alarm time is not valid.")
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val amPm = match.groupValues[3].lowercase(Locale.ROOT)
        if (amPm == "pm" && hour in 1..11) hour += 12
        if (amPm == "am" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return Part("That alarm time is not valid.")
        val display = "%02d:%02d".format(Locale.US, hour, minute)
        return Part(
            "I prepared an alarm for $display.",
            actions = listOf(
                ButlerAction(
                    id = UUID.randomUUID().toString(),
                    kind = ActionKind.SET_ALARM,
                    title = "Set alarm",
                    summary = "Open Android’s alarm screen for $display.",
                    risk = ActionRisk.LOW,
                    payload = mapOf(
                        "hour" to hour.toString(),
                        "minute" to minute.toString(),
                        "title" to "AURA alarm"
                    )
                )
            )
        )
    }

    private fun parseTimer(request: String, lower: String): Part? {
        if (!lower.contains("timer")) return null
        val match = Regex(
            """(?i)\b(?:set|start)?\s*(?:a\s+)?timer(?:\s+for)?\s+(\d+)\s*(second|seconds|minute|minutes|hour|hours)\b"""
        ).find(request)
            ?: return Part(
                "Tell me a duration, for example: “Set a timer for 10 minutes.”"
            )
        val amount = match.groupValues[1].toIntOrNull()
            ?: return Part("That timer duration is invalid.")
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        val seconds = when {
            unit.startsWith("hour") -> amount * 3600
            unit.startsWith("minute") -> amount * 60
            else -> amount
        }
        if (seconds !in 1..86_400) {
            return Part("A timer must be between one second and 24 hours.")
        }
        return Part(
            "I prepared a timer for $amount $unit.",
            actions = listOf(
                ButlerAction(
                    id = UUID.randomUUID().toString(),
                    kind = ActionKind.SET_TIMER,
                    title = "Set timer",
                    summary = "Open Android's timer for $amount $unit.",
                    risk = ActionRisk.LOW,
                    payload = mapOf(
                        "seconds" to seconds.toString(),
                        "title" to "AURA timer"
                    )
                )
            )
        )
    }

    private fun parseCalendar(request: String, lower: String): Part? {
        val looksLikeCalendarRequest =
            lower.contains("calendar") ||
                lower.startsWith("schedule ") ||
                lower.contains(" appointment")
        if (!looksLikeCalendarRequest) return null
        if (lower.matches(Regex("""(?i)^(?:open|launch|start)\s+(?:my\s+)?calendar(?:\s+app)?$"""))) {
            return null
        }

        val date = when {
            lower.contains("day after tomorrow") -> LocalDate.now().plusDays(2)
            lower.contains("tomorrow") -> LocalDate.now().plusDays(1)
            lower.contains("today") -> LocalDate.now()
            else -> Regex("""\b\d{4}-\d{2}-\d{2}\b""")
                .find(request)
                ?.value
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        }
        val timeMatch = Regex(
            """(?i)\bat\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b"""
        ).find(request) ?: Regex(
            """(?i)\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b"""
        ).find(request)
        if (date == null || timeMatch == null) {
            return Part(
                "Tell me the event, day and time, for example: “Schedule doctor tomorrow at 3 PM.”"
            )
        }

        var hour = timeMatch.groupValues[1].toIntOrNull()
            ?: return Part("That calendar time is invalid.")
        val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
        val amPm = timeMatch.groupValues[3].lowercase(Locale.ROOT)
        if (amPm == "pm" && hour in 1..11) hour += 12
        if (amPm == "am" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) {
            return Part("That calendar time is invalid.")
        }

        val explicitTitle = Regex("""(?i)\b(?:called|named|titled)\s+(.+)$""")
            .find(request)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.trimEnd('.', '!', '?')
        val inferredTitle = request
            .replace(
                Regex(
                    """(?i)^(?:please\s+)?(?:add|create|schedule|put)\s+(?:an?\s+)?"""
                ),
                ""
            )
            .replace(Regex("""(?i)\b(?:to|in|on)\s+(?:my\s+)?calendar\b"""), " ")
            .replace(Regex("""(?i)\b(?:calendar\s+event|event|appointment)\b"""), " ")
            .replace(Regex("""(?i)\bday after tomorrow\b|\btomorrow\b|\btoday\b"""), " ")
            .replace(Regex("""\b\d{4}-\d{2}-\d{2}\b"""), " ")
            .replace(timeMatch.value, " ")
            .replace(Regex("""(?i)\b(?:called|named|titled|at|on)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.', '!', '?')
        val title = explicitTitle
            ?.takeIf(String::isNotBlank)
            ?: inferredTitle.takeIf(String::isNotBlank)
            ?: "AURA event"
        val start = date
            .atTime(hour, minute)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
        val end = start.plus(Duration.ofHours(1))
        val display = ZonedDateTime.ofInstant(start, java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEE, d MMM 'at' h:mm a", Locale.ENGLISH))
        return Part(
            "I prepared the calendar event “$title” for $display.",
            actions = listOf(
                simpleAction(
                    kind = ActionKind.CALENDAR_EVENT,
                    title = "Add $title to calendar",
                    summary = "Open Android's calendar editor for $display.",
                    risk = ActionRisk.LOW,
                    payload = mapOf(
                        "title" to title.take(300),
                        "startIso" to start.toString(),
                        "endIso" to end.toString()
                    )
                )
            )
        )
    }

    private fun parseWhatsApp(request: String, lower: String): Part? {
        if (!lower.contains("whatsapp")) return null
        if (lower.matches(Regex("""(?i)^(?:open|launch|start)\s+whatsapp(?:\s+app)?$"""))) {
            return null
        }
        val match = Regex(
            """(?i)^(?:send\s+)?(?:a\s+)?whatsapp(?:\s+message)?(?:\s+to)?\s+([+()\-\s0-9]{5,25})\s+(?:saying|say|that)\s+(.+)"""
        ).find(request)
            ?: return Part(
                "Say the number and message, for example: “WhatsApp 9876543210 saying I will be late.”"
            )
        val phone = match.groupValues[1].filter(Char::isDigit)
        val body = match.groupValues[2].trim().take(2_000)
        if (phone.length !in 8..15 || body.isBlank()) {
            return Part("The WhatsApp number or message is invalid.")
        }
        val url = "https://wa.me/$phone?text=${encode(body)}"
        return Part(
            "I prepared the WhatsApp message. You will review it and press Send.",
            actions = listOf(
                webAction(
                    kind = ActionKind.WHATSAPP_MESSAGE,
                    title = "Open WhatsApp message",
                    summary = "Prepare a WhatsApp message to $phone: “$body”",
                    url = url,
                    risk = ActionRisk.HIGH
                )
            )
        )
    }

    private fun parseEmail(request: String, lower: String): Part? {
        if (
            !lower.startsWith("email ") &&
            !lower.startsWith("send email") &&
            !lower.startsWith("send an email") &&
            !lower.startsWith("compose email") &&
            !lower.startsWith("write email")
        ) {
            return null
        }
        val recipient = Regex(
            """[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""
        ).find(request)?.value.orEmpty()
        val subject = Regex(
            """(?i)\bsubject\s+(.+?)(?=\s+(?:saying|say|body)\b|$)"""
        ).find(request)?.groupValues?.get(1)?.trim()?.take(300).orEmpty()
        val body = Regex("""(?i)\b(?:saying|say|body)\s+(.+)""")
            .find(request)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.take(8_000)
            .orEmpty()
        return Part(
            "I prepared an email draft. You will choose or verify the recipient and press Send.",
            actions = listOf(
                ButlerAction(
                    id = UUID.randomUUID().toString(),
                    kind = ActionKind.COMPOSE_EMAIL,
                    title = "Compose email",
                    summary = buildString {
                        append("Open the email composer")
                        if (recipient.isNotBlank()) append(" for $recipient")
                        if (subject.isNotBlank()) append(" with subject “$subject”")
                        append(".")
                    },
                    risk = ActionRisk.HIGH,
                    payload = mapOf(
                        "recipient" to recipient,
                        "subject" to subject,
                        "body" to body
                    )
                )
            )
        )
    }

    private fun parseSystemSetting(request: String, lower: String): Part? {
        val asksForSetting = lower.contains("settings") ||
            lower.startsWith("turn on ") ||
            lower.startsWith("turn off ") ||
            lower.startsWith("enable ") ||
            lower.startsWith("disable ")
        if (!asksForSetting) return null
        val setting = when {
            lower.contains("wi-fi") || lower.contains("wifi") -> "wifi"
            lower.contains("bluetooth") -> "bluetooth"
            lower.contains("brightness") || lower.contains("display") -> "display"
            lower.contains("sound") || lower.contains("volume") -> "sound"
            lower.contains("battery") -> "battery"
            lower.contains("location") || lower.contains("gps") -> "location"
            lower.contains("app") -> "apps"
            lower == "settings" || lower.contains("phone settings") -> "general"
            else -> return null
        }
        return Part(
            "Android protects this switch, so I prepared the correct settings screen for you.",
            actions = listOf(
                simpleAction(
                    kind = ActionKind.OPEN_SYSTEM_SETTING,
                    title = "Open ${setting.replaceFirstChar { it.uppercase() }} settings",
                    summary = "Open Android's $setting settings.",
                    risk = ActionRisk.LOW,
                    payload = mapOf("setting" to setting)
                )
            )
        )
    }

    private fun parseVolume(request: String, lower: String): Part? {
        val operation: String
        val amount: Int?
        when {
            lower.matches(Regex("""(?i).*\bunmute(?:\s+(?:the\s+)?(?:phone|sound|volume))?.*""")) -> {
                operation = "unmute"
                amount = null
            }
            lower.matches(Regex("""(?i).*\bmute(?:\s+(?:the\s+)?(?:phone|sound|volume))?.*""")) -> {
                operation = "mute"
                amount = null
            }
            Regex("""(?i)\b(?:set\s+)?volume\s+(?:to|at)?\s*(\d{1,3})\s*%?""")
                .find(request) != null -> {
                val match = Regex(
                    """(?i)\b(?:set\s+)?volume\s+(?:to|at)?\s*(\d{1,3})\s*%?"""
                ).find(request)!!
                operation = "set"
                amount = match.groupValues[1].toIntOrNull()
            }
            lower.containsAny("volume up", "increase volume", "raise volume", "make it louder") -> {
                operation = "up"
                amount = null
            }
            lower.containsAny("volume down", "decrease volume", "lower volume", "make it quieter") -> {
                operation = "down"
                amount = null
            }
            else -> return null
        }
        if (operation == "set" && (amount == null || amount !in 0..100)) {
            return Part("Volume must be between 0 and 100 percent.")
        }
        val description = if (operation == "set") "$amount%" else operation
        return Part(
            "I prepared the media-volume change to $description.",
            actions = listOf(
                simpleAction(
                    kind = ActionKind.SYSTEM_VOLUME,
                    title = "Change media volume",
                    summary = "Set media volume action to $description.",
                    risk = ActionRisk.LOW,
                    payload = buildMap {
                        put("action", operation)
                        if (amount != null) put("amount", amount.toString())
                    }
                )
            )
        )
    }

    private fun parseBrightness(request: String, lower: String): Part? {
        if (!lower.contains("brightness") && !lower.containsAny("dim the screen", "screen brighter")) {
            return null
        }
        val explicit = Regex("""(?i)\bbrightness\s+(?:to|at)?\s*(\d{1,3})\s*%?""")
            .find(request)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
        val level = explicit ?: when {
            lower.containsAny("maximum", "full brightness") -> 100
            lower.containsAny("minimum", "very dim") -> 10
            lower.containsAny("decrease", "lower", "dim the screen") -> 30
            lower.containsAny("increase", "raise", "screen brighter") -> 75
            else -> null
        } ?: return Part("Tell me a brightness level from 1 to 100 percent.")
        if (level !in 1..100) return Part("Brightness must be from 1 to 100 percent.")
        return Part(
            "I prepared a brightness change to $level%. Android may first ask you to allow system-setting control.",
            actions = listOf(
                simpleAction(
                    kind = ActionKind.SYSTEM_BRIGHTNESS,
                    title = "Change brightness",
                    summary = "Set screen brightness to $level%.",
                    risk = ActionRisk.MEDIUM,
                    payload = mapOf("level" to level.toString())
                )
            )
        )
    }

    private fun parseFlashlight(lower: String): Part? {
        if (!lower.contains("flashlight") && !lower.contains("torch")) return null
        val enabled = when {
            lower.containsAny("turn off", "switch off", "disable", "flashlight off", "torch off") ->
                false
            lower.containsAny("turn on", "switch on", "enable", "flashlight on", "torch on") ->
                true
            else -> return Part("Should I turn the flashlight on or off?")
        }
        return Part(
            "I prepared the flashlight change.",
            actions = listOf(
                simpleAction(
                    kind = ActionKind.FLASHLIGHT,
                    title = if (enabled) "Turn on flashlight" else "Turn off flashlight",
                    summary = if (enabled) {
                        "Use the rear camera flash as a torch."
                    } else {
                        "Turn off the camera torch."
                    },
                    risk = ActionRisk.LOW,
                    payload = mapOf("enabled" to enabled.toString())
                )
            )
        )
    }

    private fun parseCamera(lower: String): Part? {
        if (
            !lower.matches(
                Regex("""(?i).*\b(?:open|start|launch)\s+(?:the\s+)?camera(?:\s+app)?\b.*""")
            ) &&
            !lower.containsAny("take a photo", "take a picture", "open camera")
        ) {
            return null
        }
        return Part(
            "I prepared the camera.",
            actions = listOf(
                simpleAction(
                    kind = ActionKind.OPEN_CAMERA,
                    title = "Open camera",
                    summary = "Open Android's camera app.",
                    risk = ActionRisk.LOW
                )
            )
        )
    }

    private fun parseOpenApp(request: String, lower: String): Part? {
        val normalizedRequest = request
            .trim()
            .replaceFirst(Regex("""(?i)^please\s+"""), "")
            .replaceFirst(
                Regex("""(?i)^(?:can|could|would|will)\s+you\s+"""),
                ""
            )
            .replaceFirst(Regex("""(?i)^please\s+"""), "")
            .trim()

        val match = Regex(
            """(?i)^(?:open|launch|start|run|show)\s+(?:the\s+)?(.+?)(?:\s+(?:app|application))?(?:\s+please)?$"""
        ).find(normalizedRequest) ?: return null

        val app = match.groupValues[1]
            .trim()
            .trimEnd('.', '!', '?', ',')
            .replace(Regex("""(?i)\s+(?:app|application)$"""), "")
            .trim()

        if (
            app.contains(
                Regex(
                    """(?i)\b(?:and|then)\s+(?:play|find|search|open|launch|start)\b"""
                )
            )
        ) {
            return null
        }

        if (
            app.isBlank() ||
            app.length > 80 ||
            lower.contains("http://") ||
            lower.contains("https://")
        ) {
            return null
        }

        return Part(
            "Opening $app.",
            actions = listOf(
                simpleAction(
                    kind = ActionKind.OPEN_APP,
                    title = "Open $app",
                    summary = "Find and open the installed app named $app.",
                    risk = ActionRisk.LOW,
                    payload = mapOf("app" to app)
                )
            )
        )
    }

    private fun parseMusic(request: String, lower: String): Part? {
        if (
            !lower.startsWith("play ") &&
            !lower.containsAny("play music", "listen to", "put on some")
        ) {
            return null
        }
        val query = request
            .replaceFirst(Regex("""(?i)^(?:please\s+)?(?:play|listen\s+to|put\s+on)\s+"""), "")
            .replace(Regex("""(?i)\s+on\s+(?:youtube(?:\s+music)?|spotify)\s*$"""), "")
            .trim()
        if (query.isBlank()) return Part("What song, artist, playlist, or genre should I play?")
        val useSpotify = lower.contains("spotify")
        val url = if (useSpotify) {
            "https://open.spotify.com/search/${encode(query)}"
        } else {
            "https://www.youtube.com/results?search_query=${encode(query)}"
        }
        return Part(
            "I prepared ${if (useSpotify) "Spotify" else "YouTube"} results for $query.",
            actions = listOf(
                webAction(
                    kind = ActionKind.PLAY_MEDIA,
                    title = "Play $query",
                    summary = "Open current music results for $query.",
                    url = url,
                    risk = ActionRisk.LOW
                )
            )
        )
    }

    private fun parseShare(request: String, lower: String): Part? {
        val match = Regex("""(?i)^share\s+(?:this\s+)?(?:text\s+)?[:\-]?\s*(.+)""")
            .find(request)
            ?: return null
        if (!lower.startsWith("share")) return null
        val body = match.groupValues[1].trim().take(4_000)
        if (body.isBlank()) return Part("Tell me what text you want to share.")
        return Part(
            "I prepared Android's share sheet.",
            actions = listOf(
                simpleAction(
                    kind = ActionKind.SHARE_TEXT,
                    title = "Share text",
                    summary = "Share: “${body.take(180)}”",
                    risk = ActionRisk.MEDIUM,
                    payload = mapOf("body" to body)
                )
            )
        )
    }

    private fun parseCalculation(request: String, lower: String): Part? {
        val expression = when {
            lower.startsWith("calculate ") -> request.drop(10).trim()
            lower.startsWith("compute ") -> request.drop(8).trim()
            lower.startsWith("what is ") &&
                request.any(Char::isDigit) &&
                request.any { it in "+-*/×÷^%" } -> request.drop(8).trim()
            else -> return null
        }
        return runCatching { SafeCalculator.calculate(expression) }
            .fold(
                onSuccess = { Part("The answer is $it.") },
                onFailure = {
                    Part(it.message ?: "I could not safely calculate that expression.")
                }
            )
    }

    private fun parseCall(request: String, lower: String): Part? {
        if (!lower.startsWith("call ") && !lower.startsWith("dial ")) return null
        val phone = Regex("""[+()\-\s0-9]{5,25}""").find(request)?.value?.trim()
            ?: return Part("Please say or type the phone number. I will open the dialer but never place the call myself.")
        return Part(
            "I prepared the dialer for $phone.",
            actions = listOf(
                ButlerAction(
                    id = UUID.randomUUID().toString(),
                    kind = ActionKind.DIAL_PHONE,
                    title = "Open dialer",
                    summary = "Put $phone into the phone dialer. You remain in control of the call button.",
                    risk = ActionRisk.MEDIUM,
                    payload = mapOf("phone" to phone)
                )
            )
        )
    }

    private fun parseMessage(request: String, lower: String): Part? {
        if (!lower.startsWith("message ") && !lower.startsWith("text ") && !lower.startsWith("sms ")) {
            return null
        }
        val match = Regex(
            """(?i)^(?:message|text|sms)\s+([+()\-\s0-9]{5,25})\s+(?:saying|say|that)\s+(.+)"""
        ).find(request)
            ?: return Part("Say the number and message, for example: “Message 9876543210 saying I will be late.”")
        val phone = match.groupValues[1].trim()
        val body = match.groupValues[2].trim().take(2000)
        return Part(
            "I drafted the message. Android will still require you to press Send.",
            actions = listOf(
                ButlerAction(
                    id = UUID.randomUUID().toString(),
                    kind = ActionKind.DRAFT_MESSAGE,
                    title = "Draft message",
                    summary = "Open a message to $phone with: “$body”",
                    risk = ActionRisk.HIGH,
                    payload = mapOf("phone" to phone, "body" to body)
                )
            )
        )
    }

    private fun parseNavigation(request: String, lower: String): Part? {
        val match = Regex(
            """(?i)^(?:navigate|directions|take me|go)\s+to\s+(.+)"""
        ).find(request) ?: return null
        val destination = match.groupValues[1].trim()
        if (destination.isBlank()) return null
        return Part(
            "I prepared directions to $destination.",
            actions = listOf(
                ButlerAction(
                    id = UUID.randomUUID().toString(),
                    kind = ActionKind.MAP_ROUTE,
                    title = "Open directions",
                    summary = "Open your maps app for $destination.",
                    risk = ActionRisk.LOW,
                    payload = mapOf("address" to destination.take(300))
                )
            )
        )
    }

    private fun parseFood(
        request: String,
        lower: String,
        context: ConversationContext
    ): Part? {
        if (!lower.containsAny("food", "dinner", "lunch", "breakfast", "eat", "restaurant", "খাবার")) {
            return null
        }
        val query = "$request near ${context.settings.homeCity}"
        return Part(
            "I can help choose the food and open current local options. You will review the restaurant, address, total, and payment before ordering.",
            actions = listOf(
                searchAction(
                    kind = ActionKind.FOOD_SEARCH,
                    title = "Find food options",
                    summary = "Search current food and delivery options near ${context.settings.homeCity}.",
                    query = query
                )
            )
        )
    }

    private fun parseTravel(request: String, lower: String): Part? {
        if (!lower.containsAny("ticket", "train", "flight", "hotel", "travel", "trip", "book")) {
            return null
        }
        return Part(
            "I prepared a travel search. Check the route, date, passenger details, fare, and cancellation rules before payment.",
            actions = listOf(
                searchAction(
                    kind = ActionKind.TRAVEL_SEARCH,
                    title = "Search travel",
                    summary = "Search the web for: $request",
                    query = request
                )
            )
        )
    }

    private fun parseShopping(request: String, lower: String): Part? {
        if (!lower.containsAny("buy ", "shopping", "purchase", "price of", "order ")) return null
        return Part(
            "I prepared a shopping search. I will never confirm a purchase or payment without you.",
            actions = listOf(
                searchAction(
                    kind = ActionKind.SHOPPING_SEARCH,
                    title = "Search shopping",
                    summary = "Search current listings for: $request",
                    query = request
                )
            )
        )
    }

    private fun parseWebSearch(request: String, lower: String): Part? {
        val query = when {
            lower.startsWith("search for ") -> request.drop(11).trim()
            lower.startsWith("search ") -> request.drop(7).trim()
            lower.startsWith("look up ") -> request.drop(8).trim()
            else -> return null
        }
        if (query.isBlank()) return Part("What would you like me to search for?")
        return Part(
            "I prepared a secure web search for $query.",
            actions = listOf(
                searchAction(
                    kind = ActionKind.OPEN_URL,
                    title = "Search the web",
                    summary = "Search for: $query",
                    query = query
                )
            )
        )
    }

    private fun weatherLocation(request: String): String? {
        val value = Regex(
            """(?i)\b(?:in|at|for)\s+([\p{L}][\p{L}\s,.'-]{1,100})\s*[?.!]*$"""
        ).find(request)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.trimEnd('.', '!', '?')
            ?: return null
        return value.takeUnless {
            it.lowercase(Locale.ROOT) in setOf(
                "today", "tomorrow", "this week", "the weekend", "home"
            )
        }
    }

    private fun newsTopic(request: String): String? {
        val lower = request.lowercase(Locale.ROOT)
        val category = listOf(
            "technology", "tech", "sports", "business", "science",
            "politics", "entertainment", "health", "world", "india"
        ).firstOrNull { lower.contains(it) }
        if (category != null) return if (category == "tech") "technology" else category
        return Regex("""(?i)\b(?:news|headlines)\s+(?:about|on|for|from)\s+(.+?)\s*[?.!]*$""")
            .find(request)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.take(80)
    }

    private fun isKnowledgeRequest(lower: String): Boolean =
        listOf(
            "who is ", "who was ", "what is ", "what are ", "what was ",
            "tell me about ", "teach me about ", "teach me ", "explain ",
            "define ", "describe ", "meaning of ", "how does ", "how do ",
            "how did ", "why does ", "why do ", "why is ", "where is ",
            "where are ", "when is ", "when was ", "give me information about "
        ).any(lower::startsWith)

    private fun knowledgeTopic(request: String): String {
        val cleaned = request.trim().trimEnd('.', '!', '?')
        val prefixes = listOf(
            "give me information about ", "teach me about ", "tell me about ",
            "meaning of ", "teach me ", "explain ", "define ", "describe ",
            "who is ", "who was ", "what is ", "what are ", "what was ",
            "how does ", "how do ", "how did ", "why does ", "why do ",
            "why is ", "where is ", "where are ", "when is ", "when was "
        )
        val lower = cleaned.lowercase(Locale.ROOT)
        val prefix = prefixes.firstOrNull(lower::startsWith)
        var topic = if (prefix == null) cleaned else cleaned.drop(prefix.length).trim()
        topic = topic.replace(Regex("""(?i)\s+(?:work|works)$"""), "").trim()
        topic = topic.replace(Regex("""(?i)^(?:a|an|the)\s+"""), "").trim()
        return topic.ifBlank { cleaned }
    }

    private fun knowledgeSearch(request: String): Part = Part(
        text = "I could not obtain a short verified answer, but I prepared an internet search.",
        actions = listOf(
            searchAction(
                kind = ActionKind.OPEN_URL,
                title = "Search for an answer",
                summary = "Search the web for: $request",
                query = request
            )
        )
    )

    private fun searchAction(
        kind: ActionKind,
        title: String,
        summary: String,
        query: String
    ): ButlerAction = webAction(
        kind = kind,
        title = title,
        summary = summary,
        url = "https://www.google.com/search?q=${encode(query)}"
    )

    private fun simpleAction(
        kind: ActionKind,
        title: String,
        summary: String,
        risk: ActionRisk,
        payload: Map<String, String> = emptyMap()
    ) = ButlerAction(
        id = UUID.randomUUID().toString(),
        kind = kind,
        title = title,
        summary = summary,
        risk = risk,
        payload = payload
    )

    private fun webAction(
        kind: ActionKind = ActionKind.OPEN_URL,
        title: String,
        summary: String,
        url: String,
        risk: ActionRisk = ActionRisk.MEDIUM
    ) = ButlerAction(
        id = UUID.randomUUID().toString(),
        kind = kind,
        title = title,
        summary = summary,
        risk = risk,
        payload = mapOf("url" to url)
    )

    private fun splitRequests(message: String): List<String> {
        val normalized = message.trim()
        if (normalized.isBlank()) return emptyList()

        // Never split prose, source code, logs or engineering prompts. Older
        // builds split on every semicolon/newline/"then"/"also", which could
        // destroy a C/Python/PowerShell program before it ever reached Qwen.
        if (!CommandNormalizer.isLikelyCommand(normalized)) {
            return listOf(normalized)
        }
        if (
            normalized.length > 420 ||
            normalized.contains("```") ||
            normalized.contains(Regex("""(?m)^\s*(?:class|fun|def|function|import|package|#include|using)\b"""))
        ) {
            return listOf(normalized)
        }

        val pieces = normalized
            .split(Regex("""(?i)\s*(?:;|\n|\band then\b|\bthen\b|\balso\b)\s*"""))
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(6)

        // Split only when every piece still looks like an executable phone
        // command. Otherwise preserve the owner's original request verbatim.
        return if (
            pieces.size >= 2 &&
            pieces.all(CommandNormalizer::isLikelyCommand)
        ) {
            pieces
        } else {
            listOf(normalized)
        }
    }

    private fun containsSensitiveInformation(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return lower.containsAny(
            "password", "passcode", "pin is", "otp", "cvv", "card number",
            "aadhaar", "aadhar", "pan number", "bank account", "upi pin"
        )
    }

    private fun String.containsAny(vararg values: String): Boolean =
        values.any { contains(it, ignoreCase = true) }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private data class Part(
        val text: String,
        val actions: List<ButlerAction> = emptyList(),
        val memories: List<MemoryItem> = emptyList()
    )
}