package `in`.diganta.aurabutler.architecture.policy

import java.util.Locale
import java.util.UUID

/**
 * Target operating modes defined in AURA Architecture v0.22.0 (Section 4.2 & ADR-002).
 * Eliminates the ambiguous legacy "local" label that pointed to remote Modal Qwen.
 */
enum class ExecutionMode {
    DEVICE_ONLY,
    PRIVATE_CLOUD,
    PUBLIC_CLOUD,
    AUTOMATIC
}

/**
 * Normalized provider registry separating local execution, AURA private Modal workloads,
 * capability-specific NVIDIA API models, and optional external providers.
 */
enum class ProviderId(val isCloud: Boolean, val isPublicExternal: Boolean) {
    DEVICE(isCloud = false, isPublicExternal = false),
    AURA_QWEN(isCloud = true, isPublicExternal = false),
    AURA_GEMMA_VISION(isCloud = true, isPublicExternal = false),
    NVIDIA_NEMOTRON_ULTRA(isCloud = true, isPublicExternal = true),
    NVIDIA_SUPER_VL(isCloud = true, isPublicExternal = true),
    NVIDIA_COSMOS_NANO(isCloud = true, isPublicExternal = true),
    ANTHROPIC_CLAUDE(isCloud = true, isPublicExternal = true),
    GEMINI(isCloud = true, isPublicExternal = true),
    GROQ(isCloud = true, isPublicExternal = true),
    RESEARCH(isCloud = true, isPublicExternal = true)
}

enum class WorkloadType {
    LOCAL_COMMAND,
    TEXT_REASONING,
    AGENTIC_TOOL_PLAN,
    VISION_UNDERSTANDING,
    IMAGE_GENERATION,
    ASYNC_RESEARCH
}

enum class DataSensitivity {
    PUBLIC,
    PERSONAL_PRIVATE,
    RESTRICTED_SECRET
}

/**
 * Independent, granular consent switches (Architecture v0.22.0 Section 6.4).
 * Private context to external providers defaults to OFF.
 */
data class ConsentPolicy(
    val enablePrivateCloud: Boolean = true,
    val enableNvidiaReasoning: Boolean = false,
    val enableNvidiaVision: Boolean = false,
    val enableNvidiaImageGen: Boolean = false,
    val enableAnthropicClaudeRuntime: Boolean = false,
    val enableGemini: Boolean = false,
    val enableGroq: Boolean = false,
    val allowPublicFallback: Boolean = false,
    val allowPrivateContextToPrivateCloud: Boolean = true,
    val allowPrivateContextToPublicCloud: Boolean = false,
    val allowCloudVisionUpload: Boolean = false,
    val allowImageGeneration: Boolean = false,
    val allowExternalResearch: Boolean = false
)

data class PolicyRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val mode: ExecutionMode,
    val workload: WorkloadType,
    val preferredProvider: ProviderId? = null,
    val sensitivity: DataSensitivity = DataSensitivity.PERSONAL_PRIVATE,
    val canRunLocally: Boolean = false,
    val policyVersion: String = "v0.22.0"
)

data class ProviderRouteStep(
    val providerId: ProviderId,
    val modelIdHint: String,
    val allowPrivateContext: Boolean,
    val requiresCloudBudget: Boolean
)

data class ExecutionPlan(
    val requestId: String,
    val allowed: Boolean,
    val effectiveMode: ExecutionMode,
    val steps: List<ProviderRouteStep>,
    val zeroNetworkGuaranteed: Boolean,
    val requiresCloudBudget: Boolean,
    val denialReason: String? = null,
    val policyVersion: String = "v0.22.0"
)

data class LegacyModeMigrationResult(
    val resolvedMode: ExecutionMode,
    val requiresUserConfirmation: Boolean,
    val explanation: String
)

/**
 * Deterministic policy & routing engine enforcing:
 * 1. Full local capability: DEVICE_ONLY and LOCAL_COMMAND never touch cloud or cloud budgets.
 * 2. Modality separation: reasoning, vision, and image generation never cross-route.
 * 3. Consent integrity: automatic/fallback routing never broadens user consent.
 * 4. No provider replay after visible streamed tokens have been shown.
 */
object ExecutionPolicyEngine {

    private val defaultModelIds: Map<ProviderId, String> = mapOf(
        ProviderId.DEVICE to "on-device-runtime",
        ProviderId.AURA_QWEN to "aura-modal-qwen-text",
        ProviderId.AURA_GEMMA_VISION to "aura-modal-gemma-vision",
        ProviderId.NVIDIA_NEMOTRON_ULTRA to "nvidia/nemotron-3-ultra-550b-a55b",
        ProviderId.NVIDIA_SUPER_VL to "nvidia/nemotron-3.5-super-vl-120b-a12b",
        ProviderId.NVIDIA_COSMOS_NANO to "nvidia/cosmos3-nano",
        ProviderId.ANTHROPIC_CLAUDE to "claude-api-configured",
        ProviderId.GEMINI to "gemini-configured",
        ProviderId.GROQ to "groq-configured",
        ProviderId.RESEARCH to "aura-research-worker"
    )

    fun migrateLegacyMode(
        rawMode: String?,
        userConfirmedPrivateCloud: Boolean
    ): LegacyModeMigrationResult {
        val normalized = rawMode?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (normalized) {
            "device_only", "offline" -> LegacyModeMigrationResult(
                resolvedMode = ExecutionMode.DEVICE_ONLY,
                requiresUserConfirmation = false,
                explanation = "Device-only mode selected."
            )
            "private_cloud" -> LegacyModeMigrationResult(
                resolvedMode = ExecutionMode.PRIVATE_CLOUD,
                requiresUserConfirmation = false,
                explanation = "Private AURA cloud mode selected."
            )
            "public_cloud", "gemini", "groq", "nvidia", "claude" -> LegacyModeMigrationResult(
                resolvedMode = ExecutionMode.PUBLIC_CLOUD,
                requiresUserConfirmation = false,
                explanation = "Explicit external provider mode selected."
            )
            "automatic", "auto", "" -> LegacyModeMigrationResult(
                resolvedMode = ExecutionMode.AUTOMATIC,
                requiresUserConfirmation = false,
                explanation = "Policy-driven automatic mode selected."
            )
            "local" -> if (userConfirmedPrivateCloud) {
                LegacyModeMigrationResult(
                    resolvedMode = ExecutionMode.PRIVATE_CLOUD,
                    requiresUserConfirmation = false,
                    explanation = "Legacy 'local' preference migrated to PRIVATE_CLOUD with confirmation."
                )
            } else {
                LegacyModeMigrationResult(
                    resolvedMode = ExecutionMode.DEVICE_ONLY,
                    requiresUserConfirmation = true,
                    explanation = "Legacy 'local' pointed to Modal cloud Qwen; defaulting to DEVICE_ONLY until user confirms PRIVATE_CLOUD."
                )
            }
            else -> LegacyModeMigrationResult(
                resolvedMode = ExecutionMode.DEVICE_ONLY,
                requiresUserConfirmation = true,
                explanation = "Unknown mode '$rawMode'; failing closed to DEVICE_ONLY."
            )
        }
    }

    fun resolvePlan(request: PolicyRequest, consent: ConsentPolicy): ExecutionPlan {
        // Rule 1: Device-only commands and device-only mode NEVER invoke cloud or cloud budgets.
        if (request.mode == ExecutionMode.DEVICE_ONLY || request.workload == WorkloadType.LOCAL_COMMAND) {
            return ExecutionPlan(
                requestId = request.requestId,
                allowed = true,
                effectiveMode = ExecutionMode.DEVICE_ONLY,
                steps = listOf(
                    ProviderRouteStep(
                        providerId = ProviderId.DEVICE,
                        modelIdHint = defaultModelIds.getValue(ProviderId.DEVICE),
                        allowPrivateContext = true,
                        requiresCloudBudget = false
                    )
                ),
                zeroNetworkGuaranteed = true,
                requiresCloudBudget = false,
                policyVersion = request.policyVersion
            )
        }

        // Rule 2: Modality-specific permission gates
        if (request.workload == WorkloadType.VISION_UNDERSTANDING && !consent.allowCloudVisionUpload) {
            return denied(request, "Cloud vision upload permission is disabled.")
        }
        if (request.workload == WorkloadType.IMAGE_GENERATION && !consent.allowImageGeneration) {
            return denied(request, "Image generation permission is disabled.")
        }
        if (request.workload == WorkloadType.ASYNC_RESEARCH && !consent.allowExternalResearch) {
            return denied(request, "External research permission is disabled.")
        }

        val candidates = LinkedHashSet<ProviderId>()

        when (request.mode) {
            ExecutionMode.PRIVATE_CLOUD -> {
                if (!consent.enablePrivateCloud) {
                    return denied(request, "Private AURA cloud is disabled.")
                }
                when (request.workload) {
                    WorkloadType.TEXT_REASONING, WorkloadType.AGENTIC_TOOL_PLAN ->
                        candidates.add(ProviderId.AURA_QWEN)
                    WorkloadType.VISION_UNDERSTANDING ->
                        candidates.add(ProviderId.AURA_GEMMA_VISION)
                    WorkloadType.ASYNC_RESEARCH ->
                        candidates.add(ProviderId.RESEARCH)
                    WorkloadType.IMAGE_GENERATION ->
                        return denied(request, "No private-cloud image generation endpoint is verified; FLUX remains disabled until deployed.")
                    WorkloadType.LOCAL_COMMAND ->
                        candidates.add(ProviderId.DEVICE)
                }
            }

            ExecutionMode.PUBLIC_CLOUD -> {
                val allowedForWorkload = publicProvidersFor(request.workload, consent)
                if (request.preferredProvider != null) {
                    if (request.preferredProvider in allowedForWorkload) {
                        candidates.add(request.preferredProvider)
                    } else {
                        return denied(
                            request,
                            "Provider ${request.preferredProvider.name} is not enabled or not capable of ${request.workload.name}."
                        )
                    }
                }
                if (candidates.isEmpty() || consent.allowPublicFallback) {
                    candidates.addAll(allowedForWorkload)
                }
            }

            ExecutionMode.AUTOMATIC -> {
                if (request.canRunLocally) {
                    candidates.add(ProviderId.DEVICE)
                }
                when (request.workload) {
                    WorkloadType.TEXT_REASONING, WorkloadType.AGENTIC_TOOL_PLAN -> {
                        if (consent.enableNvidiaReasoning) candidates.add(ProviderId.NVIDIA_NEMOTRON_ULTRA)
                        if (consent.enablePrivateCloud) candidates.add(ProviderId.AURA_QWEN)
                        if (consent.allowPublicFallback) {
                            candidates.addAll(publicProvidersFor(request.workload, consent))
                        }
                    }
                    WorkloadType.VISION_UNDERSTANDING -> {
                        if (consent.enableNvidiaVision) candidates.add(ProviderId.NVIDIA_SUPER_VL)
                        if (consent.enablePrivateCloud) candidates.add(ProviderId.AURA_GEMMA_VISION)
                        if (consent.allowPublicFallback && consent.enableGemini) {
                            candidates.add(ProviderId.GEMINI)
                        }
                    }
                    WorkloadType.IMAGE_GENERATION -> {
                        if (consent.enableNvidiaImageGen) candidates.add(ProviderId.NVIDIA_COSMOS_NANO)
                    }
                    WorkloadType.ASYNC_RESEARCH -> {
                        candidates.add(ProviderId.RESEARCH)
                    }
                    WorkloadType.LOCAL_COMMAND -> {
                        candidates.add(ProviderId.DEVICE)
                    }
                }
            }

            ExecutionMode.DEVICE_ONLY -> {
                candidates.add(ProviderId.DEVICE)
            }
        }

        if (candidates.isEmpty()) {
            return denied(
                request,
                "No permitted provider is enabled for ${request.workload.name} in ${request.mode.name} mode."
            )
        }

        val steps = candidates.map { provider ->
            val privateContextAllowed = when {
                !provider.isCloud -> true
                request.sensitivity == DataSensitivity.RESTRICTED_SECRET -> false
                provider.isPublicExternal -> consent.allowPrivateContextToPublicCloud
                else -> consent.allowPrivateContextToPrivateCloud
            }
            ProviderRouteStep(
                providerId = provider,
                modelIdHint = defaultModelIds.getValue(provider),
                allowPrivateContext = privateContextAllowed,
                requiresCloudBudget = provider.isCloud
            )
        }

        val allLocal = steps.all { !it.providerId.isCloud }
        return ExecutionPlan(
            requestId = request.requestId,
            allowed = true,
            effectiveMode = if (allLocal) ExecutionMode.DEVICE_ONLY else request.mode,
            steps = steps,
            zeroNetworkGuaranteed = allLocal,
            requiresCloudBudget = steps.any { it.requiresCloudBudget },
            policyVersion = request.policyVersion
        )
    }

    /**
     * Architecture v0.22.0 Section 6.3 / 9.4 invariant:
     * Never switch providers and replay after visible output has been emitted to the user.
     */
    fun canFallbackToNextProvider(
        plan: ExecutionPlan,
        currentStepIndex: Int,
        hasEmittedVisibleOutput: Boolean
    ): Boolean {
        if (!plan.allowed) return false
        if (hasEmittedVisibleOutput) return false
        return currentStepIndex + 1 < plan.steps.size
    }

    private fun publicProvidersFor(
        workload: WorkloadType,
        consent: ConsentPolicy
    ): List<ProviderId> {
        val result = mutableListOf<ProviderId>()
        when (workload) {
            WorkloadType.TEXT_REASONING, WorkloadType.AGENTIC_TOOL_PLAN -> {
                if (consent.enableNvidiaReasoning) result.add(ProviderId.NVIDIA_NEMOTRON_ULTRA)
                if (consent.enableAnthropicClaudeRuntime) result.add(ProviderId.ANTHROPIC_CLAUDE)
                if (consent.enableGemini) result.add(ProviderId.GEMINI)
                if (consent.enableGroq) result.add(ProviderId.GROQ)
            }
            WorkloadType.VISION_UNDERSTANDING -> {
                if (consent.enableNvidiaVision) result.add(ProviderId.NVIDIA_SUPER_VL)
                if (consent.enableGemini) result.add(ProviderId.GEMINI)
            }
            WorkloadType.IMAGE_GENERATION -> {
                // Strictly capability-separated: never Nemotron Ultra or Super VL.
                if (consent.enableNvidiaImageGen) result.add(ProviderId.NVIDIA_COSMOS_NANO)
            }
            WorkloadType.ASYNC_RESEARCH -> {
                result.add(ProviderId.RESEARCH)
            }
            WorkloadType.LOCAL_COMMAND -> {
                result.add(ProviderId.DEVICE)
            }
        }
        return result
    }

    private fun denied(request: PolicyRequest, reason: String): ExecutionPlan {
        return ExecutionPlan(
            requestId = request.requestId,
            allowed = false,
            effectiveMode = request.mode,
            steps = emptyList(),
            zeroNetworkGuaranteed = true,
            requiresCloudBudget = false,
            denialReason = reason,
            policyVersion = request.policyVersion
        )
    }
}
