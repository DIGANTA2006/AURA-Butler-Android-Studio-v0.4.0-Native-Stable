package `in`.diganta.aurabutler.domain

import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSafetyPolicyTest {
    @Test
    fun blocksNonHttpsLinks() {
        val check = ActionSafetyPolicy.check(
            action(
                kind = ActionKind.OPEN_URL,
                payload = mapOf("url" to "intent://malicious")
            )
        )
        assertFalse(check.allowed)
    }

    @Test
    fun allowsHttpsLinksWithVisibleDomain() {
        val check = ActionSafetyPolicy.check(
            action(
                kind = ActionKind.FOOD_SEARCH,
                payload = mapOf("url" to "https://www.google.com/search?q=dinner")
            )
        )
        assertTrue(check.allowed)
        assertTrue(check.targetLabel.contains("google.com"))
    }

    @Test
    fun blocksPastReminders() {
        val check = ActionSafetyPolicy.check(
            action(
                kind = ActionKind.REMINDER,
                payload = mapOf(
                    "title" to "Old reminder",
                    "triggerAtIso" to "2020-01-01T00:00:00Z"
                )
            ),
            nowEpochMs = 1_800_000_000_000
        )
        assertFalse(check.allowed)
    }

    @Test
    fun validatesPhoneControlRanges() {
        assertTrue(
            ActionSafetyPolicy.check(
                action(
                    kind = ActionKind.SYSTEM_VOLUME,
                    payload = mapOf("action" to "set", "amount" to "60")
                )
            ).allowed
        )
        assertFalse(
            ActionSafetyPolicy.check(
                action(
                    kind = ActionKind.SYSTEM_BRIGHTNESS,
                    payload = mapOf("level" to "140")
                )
            ).allowed
        )
    }


    @Test
    fun allowsNotificationAccessAndBoundedAnalysisPrompts() {
        assertTrue(
            ActionSafetyPolicy.check(
                action(
                    kind = ActionKind.OPEN_SYSTEM_SETTING,
                    payload = mapOf("setting" to "notification_access")
                )
            ).allowed
        )
        assertTrue(
            ActionSafetyPolicy.check(
                action(
                    kind = ActionKind.ANALYZE_IMAGE,
                    payload = mapOf("prompt" to "Explain this image")
                )
            ).allowed
        )
    }


    @Test
    fun validatesDeviceIntelligenceActions() {
        assertTrue(
            ActionSafetyPolicy.check(
                action(
                    kind = ActionKind.CAPTURE_SCREEN,
                    payload = mapOf("mode" to "save")
                )
            ).allowed
        )
        assertTrue(
            ActionSafetyPolicy.check(
                action(
                    kind = ActionKind.CLEAR_AURA_CACHE,
                    payload = emptyMap()
                )
            ).allowed
        )
        assertTrue(
            ActionSafetyPolicy.check(
                action(
                    kind = ActionKind.COOLING_ASSIST,
                    payload = emptyMap()
                )
            ).allowed
        )
    }

    private fun action(
        kind: ActionKind,
        payload: Map<String, String>
    ) = ButlerAction(
        id = "test",
        kind = kind,
        title = "Test",
        summary = "Test action",
        risk = ActionRisk.LOW,
        payload = payload
    )
}
