package `in`.diganta.aurabutler.analyzer

import java.util.Locale

object NotificationClassifier {
    private val criticalTerms = setOf(
        "unauthorized", "suspicious login", "security alert", "account locked",
        "payment failed", "transaction failed", "emergency", "fraud",
        "urgent action", "exam cancelled", "deadline today", "overdue",
        "accident", "hospital", "call me urgently", "urgent call", "account compromised"
    )

    private val importantTerms = setOf(
        "urgent", "important", "deadline", "exam", "admit card", "result",
        "interview", "appointment", "meeting", "payment", "credited",
        "debited", "refund", "delivery", "college", "university", "makaut",
        "scholarship", "application", "verification", "approved", "rejected",
        "action required", "tomorrow", "today", "asap", "please call",
        "call me", "reply", "respond", "where are you", "come home",
        "arrived", "reached", "cancelled", "rescheduled", "flight", "train",
        "class", "lab", "attendance", "fee", "otp", "one time password"
    )

    private val lowPriorityTerms = setOf(
        "sale", "offer", "discount", "recommended", "suggested", "promotion",
        "sponsored", "trending", "watch now", "new video", "coupon"
    )

    fun channelFor(packageName: String, appName: String): AnalyzerChannel {
        val packageLower = packageName.lowercase(Locale.ROOT)
        val appLower = appName.lowercase(Locale.ROOT)
        return when {
            packageLower in WHATSAPP_PACKAGES || appLower.contains("whatsapp") ->
                AnalyzerChannel.WHATSAPP

            packageLower in MAIL_PACKAGES ||
                appLower.contains("gmail") ||
                appLower.contains("mail") ||
                appLower.contains("outlook") -> AnalyzerChannel.MAIL

            packageLower.contains("slack") || appLower.contains("slack") ->
                AnalyzerChannel.SLACK

            packageLower.contains("jira") ||
                packageLower.contains("atlassian") ||
                appLower.contains("jira") -> AnalyzerChannel.JIRA

            packageLower in MESSAGE_PACKAGES ||
                appLower.contains("message") ||
                appLower.contains("telegram") ||
                appLower.contains("messenger") ||
                appLower.contains("truecaller") -> AnalyzerChannel.MESSAGING

            packageLower.contains("news") || appLower.contains("news") ->
                AnalyzerChannel.NEWS

            packageLower.startsWith("android") ||
                packageLower.startsWith("com.android") ||
                appLower.contains("system") -> AnalyzerChannel.SYSTEM

            else -> AnalyzerChannel.OTHER
        }
    }

    fun priorityFor(
        title: String,
        text: String,
        subText: String,
        channel: AnalyzerChannel
    ): NotificationPriority {
        val normalized = "$title $text $subText"
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")

        if (criticalTerms.any(normalized::contains)) {
            return NotificationPriority.CRITICAL
        }

        var score = 0
        if (importantTerms.any(normalized::contains)) score += 2
        if (channel in setOf(
                AnalyzerChannel.WHATSAPP,
                AnalyzerChannel.MAIL,
                AnalyzerChannel.SLACK,
                AnalyzerChannel.JIRA,
                AnalyzerChannel.MESSAGING
            )
        ) {
            score += 1
        }
        if (Regex("""\b(?:today|tomorrow|tonight|deadline|due)\b""")
                .containsMatchIn(normalized)
        ) {
            score += 1
        }
        if (lowPriorityTerms.any(normalized::contains)) score -= 2

        return when {
            score >= 3 -> NotificationPriority.IMPORTANT
            score <= -1 -> NotificationPriority.LOW
            else -> NotificationPriority.NORMAL
        }
    }

    fun senderFrom(title: String, subText: String): String = when {
        title.isNotBlank() -> title.trim().take(120)
        subText.isNotBlank() -> subText.trim().take(120)
        else -> "Unknown sender"
    }

    private val WHATSAPP_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b"
    )

    private val MAIL_PACKAGES = setOf(
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.yahoo.mobile.client.android.mail",
        "com.samsung.android.email.provider"
    )

    private val MESSAGE_PACKAGES = setOf(
        "com.google.android.apps.messaging",
        "com.android.mms",
        "org.telegram.messenger",
        "com.facebook.orca",
        "com.instagram.android",
        "com.truecaller"
    )

    val supportedMessagingPackages: Set<String>
        get() = WHATSAPP_PACKAGES + MAIL_PACKAGES + MESSAGE_PACKAGES
}
