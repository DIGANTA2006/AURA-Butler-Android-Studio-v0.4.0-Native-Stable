package `in`.diganta.aurabutler.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationClassifierTest {
    @Test
    fun identifiesWhatsAppAndMailChannels() {
        assertEquals(
            AnalyzerChannel.WHATSAPP,
            NotificationClassifier.channelFor("com.whatsapp", "WhatsApp")
        )
        assertEquals(
            AnalyzerChannel.MAIL,
            NotificationClassifier.channelFor("com.google.android.gm", "Gmail")
        )
    }

    @Test
    fun prioritizesUrgentDeadlines() {
        val priority = NotificationClassifier.priorityFor(
            title = "College examination notice",
            text = "Important: form submission deadline today",
            subText = "",
            channel = AnalyzerChannel.MAIL
        )
        assertTrue(priority.weight >= NotificationPriority.IMPORTANT.weight)
    }

    @Test
    fun lowersPromotionalNotifications() {
        assertEquals(
            NotificationPriority.LOW,
            NotificationClassifier.priorityFor(
                title = "Sale",
                text = "Sponsored discount offer and coupon",
                subText = "",
                channel = AnalyzerChannel.OTHER
            )
        )
    }
    @Test
    fun prioritizesDirectUrgentMessagesAcrossMessagingApps() {
        val whatsapp = NotificationClassifier.priorityFor(
            title = "Maa",
            text = "Please call me asap, it is important",
            subText = "",
            channel = AnalyzerChannel.WHATSAPP
        )
        val mail = NotificationClassifier.priorityFor(
            title = "College office",
            text = "Your lab has been rescheduled. Reply today.",
            subText = "",
            channel = AnalyzerChannel.MAIL
        )

        assertTrue(whatsapp.weight >= NotificationPriority.IMPORTANT.weight)
        assertTrue(mail.weight >= NotificationPriority.IMPORTANT.weight)
    }

}
