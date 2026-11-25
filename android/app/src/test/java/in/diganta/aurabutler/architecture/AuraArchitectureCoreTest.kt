package `in`.diganta.aurabutler.architecture

import `in`.diganta.aurabutler.architecture.actions.ActionAuthContext
import `in`.diganta.aurabutler.architecture.actions.ActionAuthorizationOutcome
import `in`.diganta.aurabutler.architecture.actions.ActionRiskTier
import `in`.diganta.aurabutler.architecture.actions.ActionSchemaDefinition
import `in`.diganta.aurabutler.architecture.actions.ContentTrustLevel
import `in`.diganta.aurabutler.architecture.actions.TypedActionBroker
import `in`.diganta.aurabutler.architecture.actions.TypedActionRequest
import `in`.diganta.aurabutler.architecture.policy.ConsentPolicy
import `in`.diganta.aurabutler.architecture.policy.DataSensitivity
import `in`.diganta.aurabutler.architecture.policy.ExecutionMode
import `in`.diganta.aurabutler.architecture.policy.ExecutionPolicyEngine
import `in`.diganta.aurabutler.architecture.policy.PolicyRequest
import `in`.diganta.aurabutler.architecture.policy.ProviderId
import `in`.diganta.aurabutler.architecture.policy.WorkloadType
import `in`.diganta.aurabutler.architecture.resource.AdaptiveResourceGovernor
import `in`.diganta.aurabutler.architecture.resource.DeviceCapabilitySnapshot
import `in`.diganta.aurabutler.architecture.resource.ThermalLevel
import `in`.diganta.aurabutler.architecture.resource.WorkPriority
import `in`.diganta.aurabutler.architecture.voice.CommandCompletenessValidator
import `in`.diganta.aurabutler.architecture.voice.CommandValidationDecision
import `in`.diganta.aurabutler.architecture.voice.ConversationTurnContext
import `in`.diganta.aurabutler.architecture.voice.PriorityControlCommand
import `in`.diganta.aurabutler.architecture.voice.SpeechTaskManager
import `in`.diganta.aurabutler.architecture.voice.UtteranceValidationInput
import `in`.diganta.aurabutler.architecture.voice.VoiceConversationStateMachine
import `in`.diganta.aurabutler.architecture.voice.VoicePriorityControlParser
import `in`.diganta.aurabutler.architecture.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuraArchitectureCoreTest {

    @Test
    fun deviceOnlyModeNeverRequiresCloudBudgetOrNetwork() {
        val plan = ExecutionPolicyEngine.resolvePlan(
            request = PolicyRequest(
                mode = ExecutionMode.DEVICE_ONLY,
                workload = WorkloadType.TEXT_REASONING,
                canRunLocally = true
            ),
            consent = ConsentPolicy(enablePrivateCloud = false)
        )
        assertTrue(plan.allowed)
        assertTrue(plan.zeroNetworkGuaranteed)
        assertFalse(plan.requiresCloudBudget)
        assertEquals(listOf(ProviderId.DEVICE), plan.steps.map { it.providerId })
    }

    @Test
    fun legacyLocalModeMigrationRequiresExplicitConfirmation() {
        val unconfirmed = ExecutionPolicyEngine.migrateLegacyMode("local", userConfirmedPrivateCloud = false)
        assertEquals(ExecutionMode.DEVICE_ONLY, unconfirmed.resolvedMode)
        assertTrue(unconfirmed.requiresUserConfirmation)

        val confirmed = ExecutionPolicyEngine.migrateLegacyMode("local", userConfirmedPrivateCloud = true)
        assertEquals(ExecutionMode.PRIVATE_CLOUD, confirmed.resolvedMode)
        assertFalse(confirmed.requiresUserConfirmation)
    }

    @Test
    fun imageGenerationNeverRoutesToNemotronUltraOrVisionModel() {
        val consent = ConsentPolicy(
            enableNvidiaReasoning = true,
            enableNvidiaVision = true,
            enableNvidiaImageGen = true,
            allowImageGeneration = true
        )
        val genPlan = ExecutionPolicyEngine.resolvePlan(
            request = PolicyRequest(
                mode = ExecutionMode.PUBLIC_CLOUD,
                workload = WorkloadType.IMAGE_GENERATION
            ),
            consent = consent
        )
        assertTrue(genPlan.allowed)
        assertEquals(listOf(ProviderId.NVIDIA_COSMOS_NANO), genPlan.steps.map { it.providerId })

        val badPreferred = ExecutionPolicyEngine.resolvePlan(
            request = PolicyRequest(
                mode = ExecutionMode.PUBLIC_CLOUD,
                workload = WorkloadType.IMAGE_GENERATION,
                preferredProvider = ProviderId.NVIDIA_NEMOTRON_ULTRA
            ),
            consent = consent
        )
        assertFalse(badPreferred.allowed)
    }

    @Test
    fun publicFallbackNeverExpandsConsentOrLeaksPrivateContext() {
        val consent = ConsentPolicy(
            enablePrivateCloud = true,
            enableNvidiaReasoning = true,
            enableGemini = true,
            allowPublicFallback = false,
            allowPrivateContextToPrivateCloud = true,
            allowPrivateContextToPublicCloud = false
        )
        val plan = ExecutionPolicyEngine.resolvePlan(
            request = PolicyRequest(
                mode = ExecutionMode.AUTOMATIC,
                workload = WorkloadType.TEXT_REASONING,
                sensitivity = DataSensitivity.PERSONAL_PRIVATE
            ),
            consent = consent
        )
        assertTrue(plan.allowed)
        assertFalse(plan.steps.any { it.providerId == ProviderId.GEMINI })
        val nvidiaStep = plan.steps.first { it.providerId == ProviderId.NVIDIA_NEMOTRON_ULTRA }
        assertFalse(nvidiaStep.allowPrivateContext)
        val qwenStep = plan.steps.first { it.providerId == ProviderId.AURA_QWEN }
        assertTrue(qwenStep.allowPrivateContext)
    }

    @Test
    fun fallbackBlockedAfterVisibleOutputEmitted() {
        val plan = ExecutionPolicyEngine.resolvePlan(
            request = PolicyRequest(mode = ExecutionMode.AUTOMATIC, workload = WorkloadType.TEXT_REASONING),
            consent = ConsentPolicy(enablePrivateCloud = true, enableNvidiaReasoning = true, allowPublicFallback = true)
        )
        assertTrue(ExecutionPolicyEngine.canFallbackToNextProvider(plan, 0, hasEmittedVisibleOutput = false))
        assertFalse(ExecutionPolicyEngine.canFallbackToNextProvider(plan, 0, hasEmittedVisibleOutput = true))
    }

    @Test
    fun adaptiveGovernorPrioritizesVoiceAndDefersBackgroundUnderPressure() {
        val governor = AdaptiveResourceGovernor()
        val stressed = DeviceCapabilitySnapshot(
            totalRamMb = 4096,
            availableRamMb = 200,
            isLowMemory = true,
            cpuCores = 8,
            thermalLevel = ThermalLevel.SEVERE,
            isPowerSaveMode = true,
            availableStorageMb = 1024,
            isForeground = true
        )
        val voiceDecision = governor.decide(WorkPriority.INTERACTIVE_VOICE, stressed)
        assertTrue(voiceDecision.proceedImmediately)

        val bgDecision = governor.decide(WorkPriority.BACKGROUND_INDEXING, stressed)
        assertFalse(bgDecision.proceedImmediately)
        assertTrue(bgDecision.defer)
    }

    @Test
    fun voiceStateMachineSupportsFullDuplexBargeInAndResumeFlow() {
        val sm = VoiceConversationStateMachine()
        assertEquals(VoiceState.IDLE, sm.currentState)
        assertTrue(sm.transitionTo(VoiceState.WAKE_WORD_DETECTED, "Wake word heard"))
        assertTrue(sm.transitionTo(VoiceState.AUTHENTICATING_SPEAKER, "Verifying admin voice"))
        assertTrue(sm.transitionTo(VoiceState.LISTENING, "Admin verified"))
        assertTrue(sm.transitionTo(VoiceState.TRANSCRIBING, "Streaming ASR"))
        assertTrue(sm.transitionTo(VoiceState.VALIDATING_COMMAND, "Checking completeness"))
        assertTrue(sm.transitionTo(VoiceState.PROCESSING_COMMAND, "Executing"))
        assertTrue(sm.transitionTo(VoiceState.GENERATING_RESPONSE, "Model generating"))
        assertTrue(sm.transitionTo(VoiceState.SPEAKING, "TTS active"))
        // Barge-in
        assertTrue(sm.transitionTo(VoiceState.INTERRUPTED, "Authorized barge-in"))
        assertTrue(sm.transitionTo(VoiceState.LISTENING, "Listening to interruption"))
        assertTrue(sm.transitionTo(VoiceState.VALIDATING_COMMAND, "Validating interruption"))
        assertTrue(sm.transitionTo(VoiceState.PROCESSING_COMMAND, "Handling interruption"))
        assertTrue(sm.transitionTo(VoiceState.SPEAKING, "Speaking interruption answer"))
        assertTrue(sm.transitionTo(VoiceState.RESUMING, "Resuming previous report"))
        assertTrue(sm.transitionTo(VoiceState.SPEAKING, "Continuing saved checkpoint"))
        assertEquals(VoiceState.SPEAKING, sm.currentState)
    }

    @Test
    fun voicePriorityControlsInterceptBeforeGeneralCommands() {
        assertEquals(PriorityControlCommand.STOP_SPEECH, VoicePriorityControlParser.parse("AURA, stop!"))
        assertEquals(PriorityControlCommand.PAUSE_SPEECH, VoicePriorityControlParser.parse("pause"))
        assertEquals(PriorityControlCommand.CONTINUE_SPEECH, VoicePriorityControlParser.parse("continue"))
        assertEquals(PriorityControlCommand.REPEAT_LAST, VoicePriorityControlParser.parse("repeat that"))
        assertEquals(PriorityControlCommand.SPEAK_FASTER, VoicePriorityControlParser.parse("speak faster"))
        assertEquals(PriorityControlCommand.ASSISTANT_VOLUME_UP, VoicePriorityControlParser.parse("volume up"))
        assertEquals(PriorityControlCommand.MUTE_ASSISTANT, VoicePriorityControlParser.parse("mute yourself"))
        assertNull(VoicePriorityControlParser.parse("explain quantum computing"))
    }

    @Test
    fun commandValidatorHandlesIncompleteAmbiguousUnclearUnauthorizedAndContextFollowUp() {
        val validator = CommandCompletenessValidator()
        val idleCtx = ConversationTurnContext(isCurrentlySpeaking = false)

        // Incomplete
        val incomplete = validator.validate(
            UtteranceValidationInput("AURA open", 0.92f, true, 0.90f, 0.85f),
            idleCtx
        )
        assertTrue(incomplete is CommandValidationDecision.WaitOrAskCompletion)

        // Ambiguous without context
        val ambiguous = validator.validate(
            UtteranceValidationInput("turn it up", 0.95f, true, 0.90f, 0.85f),
            idleCtx
        )
        assertTrue(ambiguous is CommandValidationDecision.RequestClarification)

        // Same phrase resolved with active speaking context
        val followUp = validator.validate(
            UtteranceValidationInput("make it louder", 0.95f, true, 0.90f, 0.85f),
            ConversationTurnContext(isCurrentlySpeaking = true, lastSpokenMediaOrNews = true)
        )
        assertTrue(followUp is CommandValidationDecision.Execute)
        assertEquals("volume up", (followUp as CommandValidationDecision.Execute).normalizedCommand)

        // Unauthorized speaker ignored
        val stranger = validator.validate(
            UtteranceValidationInput("open chrome", 0.95f, false, 0.30f, 0.95f),
            idleCtx
        )
        assertTrue(stranger is CommandValidationDecision.RejectUnauthorizedSpeaker)

        // Unclear transcript rejected
        val unclear = validator.validate(
            UtteranceValidationInput("mumble", 0.35f, true, 0.90f, 0.90f),
            idleCtx
        )
        assertTrue(unclear is CommandValidationDecision.RejectUnclear)
    }

    @Test
    fun speechTaskManagerResumesExactCheckpointAndHonorsExplicitStop() {
        val manager = SpeechTaskManager(maxSegmentChars = 120)
        val report = "Sentence one explains AI. Sentence two explains neural networks. Sentence three explains robotics."
        manager.startTask(report, topicTag = "ai_report")

        assertEquals("Sentence one explains AI.", manager.currentSegment()?.text)
        manager.markCurrentSegmentSpoken()
        assertEquals("Sentence two explains neural networks.", manager.currentSegment()?.text)

        // Barge-in saves checkpoint at segment index 1
        val saved = manager.interruptForBargeIn()
        assertNotNull(saved)
        assertEquals(1, saved?.currentSegmentIndex)

        // Answer urgent interruption
        manager.startTask("Machine learning is a subset of AI.")
        manager.markCurrentSegmentSpoken()

        // Resume prior report without repeating sentence one
        val resumed = manager.resumeFromCheckpoint()
        assertNotNull(resumed)
        assertEquals(1, resumed?.currentSegmentIndex)
        assertEquals("Sentence two explains neural networks.", manager.currentSegment()?.text)

        // Explicit stop prevents unintended auto-resume
        manager.stopAndCancelAll()
        assertFalse(manager.hasResumableTask())
        assertNull(manager.resumeFromCheckpoint())
    }

    @Test
    fun speechTaskManagerDoesNotTruncateLongLocalOutputs() {
        val longOutput = (1..400).joinToString(" ") { "Paragraph $it contains complete technical analysis." }
        assertTrue(longOutput.length > 20_000)
        val segments = SpeechTaskManager.segmentWithoutTruncation(longOutput, maxChars = 200)
        val reconstructedWords = segments.joinToString(" ") { it.text }.split(Regex("\\s+"))
        val originalWords = longOutput.split(Regex("\\s+"))
        assertEquals(originalWords.size, reconstructedWords.size)
    }

    @Test
    fun actionBrokerBlocksUntrustedPromptInjectionAndVoiceOnlyCriticalActions() {
        val broker = TypedActionBroker()
        broker.registerSchema(
            ActionSchemaDefinition(
                actionId = "send_sms",
                riskTier = ActionRiskTier.HIGH,
                requiredParams = setOf("to", "body")
            )
        )
        broker.registerSchema(
            ActionSchemaDefinition(
                actionId = "wipe_local_vault",
                riskTier = ActionRiskTier.CRITICAL
            )
        )

        val injected = broker.authorize(
            TypedActionRequest(actionId = "send_sms", parameters = mapOf("to" to "123", "body" to "hacked")),
            ActionAuthContext(sourceTrustLevel = ContentTrustLevel.RETRIEVED_DOCUMENT, explicitUserConfirmed = true)
        )
        assertTrue(injected is ActionAuthorizationOutcome.Denied)

        val voiceOnlyCritical = broker.authorize(
            TypedActionRequest(actionId = "wipe_local_vault"),
            ActionAuthContext(
                sourceTrustLevel = ContentTrustLevel.OWNER_VERIFIED_VOICE,
                ownerVoiceVerified = true,
                explicitUserConfirmed = true,
                strongOwnerAuthCompleted = false
            )
        )
        assertTrue(voiceOnlyCritical is ActionAuthorizationOutcome.Denied)

        val unconfirmedHigh = broker.authorize(
            TypedActionRequest(actionId = "send_sms", parameters = mapOf("to" to "123", "body" to "hi")),
            ActionAuthContext(
                sourceTrustLevel = ContentTrustLevel.OWNER_VERIFIED_VOICE,
                ownerVoiceVerified = true,
                explicitUserConfirmed = false
            )
        )
        assertTrue(unconfirmedHigh is ActionAuthorizationOutcome.ConfirmationRequired)
    }

    @Test
    fun actionBrokerEnforcesIdempotencyForDuplicateRequests() {
        val broker = TypedActionBroker()
        broker.registerSchema(
            ActionSchemaDefinition(
                actionId = "read_weather",
                riskTier = ActionRiskTier.LOW,
                requireConfirmation = false
            )
        )
        var calls = 0
        val req = TypedActionRequest(idempotencyKey = "req-100", actionId = "read_weather")
        val auth = ActionAuthContext(sourceTrustLevel = ContentTrustLevel.OWNER_DIRECT_UI)

        val first = broker.execute(req, auth) { calls += 1; "26C clear" }
        val second = broker.execute(req, auth) { calls += 1; "26C clear" }

        assertTrue(first.success)
        assertFalse(first.deduplicated)
        assertTrue(second.success)
        assertTrue(second.deduplicated)
        assertEquals(1, calls)
    }
}
