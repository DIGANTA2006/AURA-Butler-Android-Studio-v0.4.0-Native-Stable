package `in`.diganta.aurabutler.architecture.actions

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ActionRiskTier {
    LOW,
    MODERATE,
    HIGH,
    CRITICAL
}

enum class ContentTrustLevel {
    OWNER_DIRECT_UI,
    OWNER_VERIFIED_VOICE,
    MODEL_PROPOSED_FROM_OWNER_PROMPT,
    RETRIEVED_DOCUMENT,
    NOTIFICATION,
    OCR_SCREEN,
    WEB_RESULT
}

data class ActionAuthContext(
    val sourceTrustLevel: ContentTrustLevel,
    val ownerVoiceVerified: Boolean = false,
    val explicitUserConfirmed: Boolean = false,
    val strongOwnerAuthCompleted: Boolean = false
)

data class ActionSchemaDefinition(
    val actionId: String,
    val schemaVersion: Int = 1,
    val riskTier: ActionRiskTier,
    val requiredParams: Set<String> = emptySet(),
    val allowedParams: Set<String> = emptySet(),
    val requireConfirmation: Boolean = riskTier != ActionRiskTier.LOW
)

data class TypedActionRequest(
    val idempotencyKey: String = UUID.randomUUID().toString(),
    val actionId: String,
    val schemaVersion: Int = 1,
    val parameters: Map<String, String> = emptyMap()
)

sealed class ActionAuthorizationOutcome {
    data class Allowed(val request: TypedActionRequest, val schema: ActionSchemaDefinition) :
        ActionAuthorizationOutcome()

    data class ConfirmationRequired(
        val request: TypedActionRequest,
        val reason: String
    ) : ActionAuthorizationOutcome()

    data class Denied(val request: TypedActionRequest, val reason: String) :
        ActionAuthorizationOutcome()
}

data class ActionExecutionResult(
    val idempotencyKey: String,
    val actionId: String,
    val success: Boolean,
    val message: String,
    val deduplicated: Boolean = false
)

data class RedactedActionAuditEvent(
    val timestampEpochMs: Long,
    val idempotencyKey: String,
    val actionId: String,
    val riskTier: ActionRiskTier?,
    val sourceTrustLevel: ContentTrustLevel,
    val decision: String,
    val reason: String
)

/**
 * Deterministic capability broker (Architecture v0.22.0 Section 7 & voice + instruction.md Section 6).
 * Model output can only propose a typed action; this broker validates schema, trust origin,
 * risk tier, voice-vs-strong authentication, confirmation, and idempotency.
 */
class TypedActionBroker {
    private val schemas = ConcurrentHashMap<String, ActionSchemaDefinition>()
    private val executedResults = ConcurrentHashMap<String, ActionExecutionResult>()
    private val auditEvents = mutableListOf<RedactedActionAuditEvent>()
    private val auditLock = Any()

    private val untrustedOrigins = setOf(
        ContentTrustLevel.RETRIEVED_DOCUMENT,
        ContentTrustLevel.NOTIFICATION,
        ContentTrustLevel.OCR_SCREEN,
        ContentTrustLevel.WEB_RESULT
    )

    fun registerSchema(schema: ActionSchemaDefinition) {
        schemas[schema.actionId] = schema
    }

    fun authorize(
        request: TypedActionRequest,
        auth: ActionAuthContext
    ): ActionAuthorizationOutcome {
        val schema = schemas[request.actionId]
        if (schema == null) {
            return recordDeny(request, null, auth, "Action '${request.actionId}' is not in the allow-list.")
        }
        if (request.schemaVersion != schema.schemaVersion) {
            return recordDeny(
                request,
                schema,
                auth,
                "Schema version mismatch: expected ${schema.schemaVersion}, got ${request.schemaVersion}."
            )
        }
        val missingParams = schema.requiredParams.filter { request.parameters[it].isNullOrBlank() }
        if (missingParams.isNotEmpty()) {
            return recordDeny(
                request,
                schema,
                auth,
                "Missing required parameters: ${missingParams.joinToString(", ")}."
            )
        }
        if (schema.allowedParams.isNotEmpty()) {
            val unknownParams = request.parameters.keys - schema.allowedParams - schema.requiredParams
            if (unknownParams.isNotEmpty()) {
                return recordDeny(
                    request,
                    schema,
                    auth,
                    "Unexpected parameters rejected: ${unknownParams.joinToString(", ")}."
                )
            }
        }

        // Prompt-injection boundary: untrusted content can NEVER execute moderate/high/critical actions.
        if (auth.sourceTrustLevel in untrustedOrigins && schema.riskTier != ActionRiskTier.LOW) {
            return recordDeny(
                request,
                schema,
                auth,
                "Blocked potential prompt injection: untrusted source ${auth.sourceTrustLevel.name} cannot invoke ${schema.riskTier.name} action."
            )
        }

        // Critical actions are never autonomous and never authorized by voice alone.
        if (schema.riskTier == ActionRiskTier.CRITICAL) {
            if (!auth.strongOwnerAuthCompleted || !auth.explicitUserConfirmed) {
                return recordDeny(
                    request,
                    schema,
                    auth,
                    "CRITICAL action requires strong owner authentication and explicit confirmation (voice verification alone is insufficient)."
                )
            }
        }

        // High-risk actions require explicit per-action confirmation (voice similarity alone is not enough).
        if (schema.riskTier == ActionRiskTier.HIGH && !auth.explicitUserConfirmed) {
            recordAudit(request, schema, auth, "CONFIRMATION_REQUIRED", "Explicit confirmation required for HIGH risk action.")
            return ActionAuthorizationOutcome.ConfirmationRequired(
                request = request,
                reason = "Please explicitly confirm '${request.actionId}' before execution."
            )
        }

        if (schema.requireConfirmation && !auth.explicitUserConfirmed && auth.sourceTrustLevel != ContentTrustLevel.OWNER_DIRECT_UI) {
            recordAudit(request, schema, auth, "CONFIRMATION_REQUIRED", "Schema requires explicit confirmation.")
            return ActionAuthorizationOutcome.ConfirmationRequired(
                request = request,
                reason = "Confirmation required for '${request.actionId}'."
            )
        }

        recordAudit(request, schema, auth, "ALLOWED", "Authorized by TypedActionBroker.")
        return ActionAuthorizationOutcome.Allowed(request, schema)
    }

    fun execute(
        request: TypedActionRequest,
        auth: ActionAuthContext,
        executor: (TypedActionRequest) -> String
    ): ActionExecutionResult {
        executedResults[request.idempotencyKey]?.let { prior ->
            return prior.copy(deduplicated = true)
        }
        return when (val decision = authorize(request, auth)) {
            is ActionAuthorizationOutcome.Denied -> ActionExecutionResult(
                idempotencyKey = request.idempotencyKey,
                actionId = request.actionId,
                success = false,
                message = decision.reason
            )
            is ActionAuthorizationOutcome.ConfirmationRequired -> ActionExecutionResult(
                idempotencyKey = request.idempotencyKey,
                actionId = request.actionId,
                success = false,
                message = decision.reason
            )
            is ActionAuthorizationOutcome.Allowed -> {
                val output = executor(request)
                val result = ActionExecutionResult(
                    idempotencyKey = request.idempotencyKey,
                    actionId = request.actionId,
                    success = true,
                    message = output
                )
                executedResults[request.idempotencyKey] = result
                result
            }
        }
    }

    fun getAuditSnapshot(): List<RedactedActionAuditEvent> = synchronized(auditLock) {
        auditEvents.toList()
    }

    private fun recordDeny(
        request: TypedActionRequest,
        schema: ActionSchemaDefinition?,
        auth: ActionAuthContext,
        reason: String
    ): ActionAuthorizationOutcome.Denied {
        recordAudit(request, schema, auth, "DENIED", reason)
        return ActionAuthorizationOutcome.Denied(request, reason)
    }

    private fun recordAudit(
        request: TypedActionRequest,
        schema: ActionSchemaDefinition?,
        auth: ActionAuthContext,
        decision: String,
        reason: String
    ) = synchronized(auditLock) {
        auditEvents.add(
            RedactedActionAuditEvent(
                timestampEpochMs = System.currentTimeMillis(),
                idempotencyKey = request.idempotencyKey,
                actionId = request.actionId,
                riskTier = schema?.riskTier,
                sourceTrustLevel = auth.sourceTrustLevel,
                decision = decision,
                reason = reason
            )
        )
    }
}
