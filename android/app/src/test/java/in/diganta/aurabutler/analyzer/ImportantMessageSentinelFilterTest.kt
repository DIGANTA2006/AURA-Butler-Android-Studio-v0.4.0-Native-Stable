package `in`.diganta.aurabutler.analyzer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportantMessageSentinelFilterTest {
    private fun notification(
        packageName: String,
        appName: String,
        channel: AnalyzerChannel,
        title: String,
        text: String
    ) = CapturedNotification(
        notificationKey = "key-$packageName-$text",
        packageName = packageName,
        appName = appName,
        title = title,
        text = text,
        subText = "",
        sender = title,
        channel = channel,
        priority = NotificationPriority.IMPORTANT,
        postedAtEpochMs = 0L
    )

    @Test
    fun speaksWhatsAppAndMail() {
        assertTrue(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.whatsapp", "WhatsApp", AnalyzerChannel.WHATSAPP, "Ravi", "hi")
            )
        )
        assertTrue(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.google.android.gm", "Gmail", AnalyzerChannel.MAIL, "Prof", "Assignment due")
            )
        )
    }

    @Test
    fun speaksCollegeNotificationsFromAnyApp() {
        assertTrue(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.google.android.apps.classroom", "Classroom", AnalyzerChannel.OTHER, "Teacher", "New post")
            )
        )
        assertTrue(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.example.news", "Daily", AnalyzerChannel.OTHER, "Notice", "College exam schedule")
            )
        )
    }

    @Test
    fun keepsOtherAppsSilentEvenWhenCritical() {
        assertFalse(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.example.shop", "Shop", AnalyzerChannel.OTHER, "Sale", "Big discount")
            )
        )
        assertFalse(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.slack", "Slack", AnalyzerChannel.SLACK, "Team", "deploy done")
            )
        )
        assertFalse(
            ImportantMessageSentinel.isSpeakableSource(
                notification(
                    "com.google.android.apps.messaging",
                    "Messages",
                    AnalyzerChannel.MESSAGING,
                    "HDFC",
                    "Account debited Rs 500"
                ).copy(priority = NotificationPriority.CRITICAL)
            )
        )
    }

    @Test
    fun wordBoundariesStopLabelMatchingLab() {
        assertFalse(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.example.x", "X", AnalyzerChannel.OTHER, "Label", "new label printed")
            )
        )
    }
}