package `in`.diganta.aurabutler.runtime

import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction

enum class PolicyDecision {
    ALLOW,
    REQUIRE_CONFIRMATION,
    DENY
}

class CapabilityPolicy(
    private val deniedActions: Set<ActionKind> = emptySet(),
    private val allowHighRisk: Boolean = true
) {
    fun evaluate(
        action: ButlerAction,
        confirmed: Boolean = false
    ): PolicyDecision {
        if (action.kind in deniedActions) return PolicyDecision.DENY
        if (action.risk == ActionRisk.HIGH && !allowHighRisk) return PolicyDecision.DENY

        val hasUnsafeSecret = action.payload.any { (key, value) ->
            SecretRedactor.containsSensitive(key) ||
                (
                    action.kind !in setOf(
                        ActionKind.DRAFT_MESSAGE,
                        ActionKind.COMPOSE_EMAIL,
                        ActionKind.WHATSAPP_MESSAGE
                    ) &&
                        SecretRedactor.containsSensitive(value)
                    )
        }
        if (hasUnsafeSecret) return PolicyDecision.DENY

        return if (confirmed) {
            PolicyDecision.ALLOW
        } else {
            PolicyDecision.REQUIRE_CONFIRMATION
        }
    }
}
