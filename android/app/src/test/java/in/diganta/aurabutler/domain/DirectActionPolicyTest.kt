package `in`.diganta.aurabutler.domain

import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectActionPolicyTest {
    @Test
    fun appLaunchesCanRunImmediately() {
        assertTrue(
            DirectActionPolicy.canExecuteImmediately(
                action(
                    kind = ActionKind.OPEN_APP,
                    risk = ActionRisk.LOW,
                    payload = mapOf("app" to "YouTube")
                )
            )
        )
    }

    @Test
    fun communicationAndSchedulingStillNeedApproval() {
        assertFalse(
            DirectActionPolicy.canExecuteImmediately(
                action(
                    kind = ActionKind.DRAFT_MESSAGE,
                    risk = ActionRisk.LOW,
                    payload = mapOf("phone" to "12345", "body" to "Hello")
                )
            )
        )
        assertFalse(
            DirectActionPolicy.canExecuteImmediately(
                action(
                    kind = ActionKind.REMINDER,
                    risk = ActionRisk.MEDIUM,
                    payload = mapOf(
                        "title" to "Test",
                        "triggerAtIso" to "2030-01-01T00:00:00Z"
                    )
                )
            )
        )
    }

    @Test
    fun highRiskActionNeverRunsImmediately() {
        assertFalse(
            DirectActionPolicy.canExecuteImmediately(
                action(
                    kind = ActionKind.OPEN_APP,
                    risk = ActionRisk.HIGH,
                    payload = mapOf("app" to "YouTube")
                )
            )
        )
    }


    @Test
    fun songIdentificationCanRunButPrivateAnalysisNeedsApproval() {
        assertTrue(
            DirectActionPolicy.canExecuteImmediately(
                action(
                    kind = ActionKind.IDENTIFY_SONG,
                    risk = ActionRisk.LOW,
                    payload = emptyMap()
                )
            )
        )
        assertFalse(
            DirectActionPolicy.canExecuteImmediately(
                action(
                    kind = ActionKind.ANALYZE_SCREEN,
                    risk = ActionRisk.MEDIUM,
                    payload = mapOf("prompt" to "Explain this screen")
                )
            )
        )
    }


    @Test
    fun volumeIsImmediateButCleanupAndCaptureNeedApproval() {
        assertTrue(
            DirectActionPolicy.canExecuteImmediately(
                action(
                    kind = ActionKind.SYSTEM_VOLUME,
                    risk = ActionRisk.LOW,
                    payload = mapOf("action" to "set", "stream" to "media", "amount" to "40")
                )
            )
        )
        assertFalse(
            DirectActionPolicy.canExecuteImmediately(
                action(
                    kind = ActionKind.CLEAR_AURA_CACHE,
                    risk = ActionRisk.MEDIUM,
                    payload = emptyMap()
                )
            )
        )
        assertFalse(
            DirectActionPolicy.canExecuteImmediately(
                action(
                    kind = ActionKind.CAPTURE_SCREEN,
                    risk = ActionRisk.MEDIUM,
                    payload = mapOf("mode" to "save")
                )
            )
        )
    }

    private fun action(
        kind: ActionKind,
        risk: ActionRisk,
        payload: Map<String, String>
    ) = ButlerAction(
        id = "test",
        kind = kind,
        title = "Test",
        summary = "Test action",
        risk = risk,
        payload = payload
    )
}
