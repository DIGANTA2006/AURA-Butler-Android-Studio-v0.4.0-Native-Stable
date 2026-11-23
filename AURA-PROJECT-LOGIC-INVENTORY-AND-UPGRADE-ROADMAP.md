# AURA Butler — Source Inventory and Upgrade Roadmap

**Repository:** `DIGANTA2006/AURA-Butler-Android-Studio-v0.4.0-Native-Stable`  
**Default branch:** `main`  
**Working branch:** `feat/aura-domain-logic-upgrade`  
**Inventory snapshot:** 2026-11-23  
**PR-branch Android version:** `0.17.3-voice-continuity` (versionCode 28), min API 26, target API 36, Java/Kotlin 17.

## 1. Inventory scope

This inventory is generated from the repository's recursive Git tree, not from a hand-written 96-file list. The live feature-branch tree contains **604 tracked files**, **100 production Kotlin source files**, and **34 Kotlin test/instrumentation source files** at this snapshot. Counts are taken from the recursive Git tree and include the current cross-domain implementation and regression tests.

This is a source inventory and initial architecture map. It does **not** claim that every listed feature has completed a line-by-line audit or passed runtime verification. Each domain must be audited against actual call sites, configuration, tests, and device behaviour before being marked complete.

## 2. Production source inventory by subsystem

### app-core — 3 source files

Application lifecycle, entry activity, and UI view-model coordination

- `android/app/src/main/java/in/diganta/aurabutler/AuraApplication.kt`
- `android/app/src/main/java/in/diganta/aurabutler/ButlerViewModel.kt`
- `android/app/src/main/java/in/diganta/aurabutler/MainActivity.kt`

### analyzer — 9 source files

Notification ingestion, classification, local vault, priority sentinel, and proactive alert delivery

- `android/app/src/main/java/in/diganta/aurabutler/analyzer/AnalyzerModels.kt`
- `android/app/src/main/java/in/diganta/aurabutler/analyzer/AuraNotificationListenerService.kt`
- `android/app/src/main/java/in/diganta/aurabutler/analyzer/ImportantMessageSentinel.kt`
- `android/app/src/main/java/in/diganta/aurabutler/analyzer/NotificationAccessState.kt`
- `android/app/src/main/java/in/diganta/aurabutler/analyzer/NotificationAnalyzer.kt`
- `android/app/src/main/java/in/diganta/aurabutler/analyzer/NotificationClassifier.kt`
- `android/app/src/main/java/in/diganta/aurabutler/analyzer/NotificationVault.kt`
- `android/app/src/main/java/in/diganta/aurabutler/analyzer/ProactiveAlertManager.kt`
- `android/app/src/main/java/in/diganta/aurabutler/analyzer/UnifiedAnalyzerEngine.kt`

### background — 4 source files

Wake-word foreground service, command processing, reminder firing, and scheduled digest work

- `android/app/src/main/java/in/diganta/aurabutler/background/AnalyzerDigestWorker.kt`
- `android/app/src/main/java/in/diganta/aurabutler/background/BackgroundCommandProcessor.kt`
- `android/app/src/main/java/in/diganta/aurabutler/background/ReminderWorker.kt`
- `android/app/src/main/java/in/diganta/aurabutler/background/WakeWordService.kt`

### data — 20 source files

AI provider contracts/routing, public information providers, secure persistence, HTTP boundaries, document/vision providers, and response formatting

- `android/app/src/main/java/in/diganta/aurabutler/data/AiProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/AiResponseFormatter.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/AuraIdentity.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/ButlerSystemPrompt.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/DeepResearchProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/DetailedNewsProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/FreeNewsProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/GeminiAiProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/GeminiAnalyzerProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/GeminiCommandInterpreter.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/GroqAiProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/HybridAiProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/KeylessLiveInfoProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/OpenAiCompatibleProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/PrivateDocumentProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/PrivateVisionProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/SafeHttpClient.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/SecureStore.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/StructuredAgentProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/data/YouTubeVideoResolver.kt`

### device — 1 source files

Battery, storage, thermal, and device-health logic

- `android/app/src/main/java/in/diganta/aurabutler/device/DeviceHealthManager.kt`

### document — 2 source files

Document ingestion and repository/source-code intelligence

- `android/app/src/main/java/in/diganta/aurabutler/document/DocumentInputReader.kt`
- `android/app/src/main/java/in/diganta/aurabutler/document/RepositoryIntelligence.kt`

### domain — 16 source files

Command normalization and interpretation, action execution/safety, device/media/news/calculator engines, and persona

- `android/app/src/main/java/in/diganta/aurabutler/domain/ActionExecutor.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/ActionSafetyPolicy.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/ButlerPersona.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/CommandNormalizer.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/DeviceCommandEngine.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/DirectActionPolicy.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/EnhancedButlerEngine.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/ForecastDayParser.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/LiveInfoProvider.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/LocalButlerEngine.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/MultilingualCommandBridge.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/NewsCommandEngine.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/NewsCommandParsing.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/ProductionButlerEngine.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/SafeCalculator.kt`
- `android/app/src/main/java/in/diganta/aurabutler/domain/YouTubeAutoplayEngine.kt`

### intelligence — 3 source files

Reasoning orchestration, proactive suggestion logic, and intelligence coordination

- `android/app/src/main/java/in/diganta/aurabutler/intelligence/IntelligenceCoordinator.kt`
- `android/app/src/main/java/in/diganta/aurabutler/intelligence/ProactiveEngine.kt`
- `android/app/src/main/java/in/diganta/aurabutler/intelligence/ReasoningEngine.kt`

### maintenance — 1 source files

Diagnostics and repair-oriented health reporting

- `android/app/src/main/java/in/diganta/aurabutler/maintenance/AuraDoctor.kt`

### media — 1 source files

Song and media discovery

- `android/app/src/main/java/in/diganta/aurabutler/media/SongFinder.kt`

### memory — 4 source files

Private, semantic, temporal, and hybrid memory retrieval/storage

- `android/app/src/main/java/in/diganta/aurabutler/memory/HybridRetrievalScorer.kt`
- `android/app/src/main/java/in/diganta/aurabutler/memory/PrivateMemoryStore.kt`
- `android/app/src/main/java/in/diganta/aurabutler/memory/SemanticMemory.kt`
- `android/app/src/main/java/in/diganta/aurabutler/memory/TemporalMemoryStore.kt`

### model — 1 source files

Shared application/domain data models

- `android/app/src/main/java/in/diganta/aurabutler/model/Models.kt`

### planning — 1 source files

Multi-step task planning and dependency execution

- `android/app/src/main/java/in/diganta/aurabutler/planning/TaskPlanner.kt`

### runtime — 13 source files

Runtime state, intent routing, capability policy, injection defense, auditing, crash journal, endpoint validation, redaction, quality gates, and budget policy

- `android/app/src/main/java/in/diganta/aurabutler/runtime/ActionAuditLog.kt`
- `android/app/src/main/java/in/diganta/aurabutler/runtime/AuraRuntimeState.kt`
- `android/app/src/main/java/in/diganta/aurabutler/runtime/CapabilityPolicy.kt`
- `android/app/src/main/java/in/diganta/aurabutler/runtime/CoroutineSafety.kt`
- `android/app/src/main/java/in/diganta/aurabutler/runtime/LocalCrashJournal.kt`
- `android/app/src/main/java/in/diganta/aurabutler/runtime/ProductionIntentRouter.kt`
- `android/app/src/main/java/in/diganta/aurabutler/runtime/PromptInjectionGuard.kt`
- `android/app/src/main/java/in/diganta/aurabutler/runtime/RemoteConfig.kt`
- `android/app/src/main/java/in/diganta/aurabutler/runtime/ResponseQualityGate.kt`
- `android/app/src/main/java/in/diganta/aurabutler/runtime/SafeEndpointValidator.kt`
- `android/app/src/main/java/in/diganta/aurabutler/runtime/SecretRedactor.kt`
- `android/app/src/main/java/in/diganta/aurabutler/runtime/StudentBudgetConfig.kt`
- `android/app/src/main/java/in/diganta/aurabutler/runtime/StudentBudgetPolicy.kt`

### screen — 2 source files

Screen capture and image input preparation

- `android/app/src/main/java/in/diganta/aurabutler/screen/ImageInputReader.kt`
- `android/app/src/main/java/in/diganta/aurabutler/screen/ScreenCaptureService.kt`

### ui — 2 source files

Compose application surfaces and theme

- `android/app/src/main/java/in/diganta/aurabutler/ui/AuraApp.kt`
- `android/app/src/main/java/in/diganta/aurabutler/ui/theme/Theme.kt`

### voice — 17 source files

Audio capture, wake/ASR/TTS, owner speaker checks, VAD, transcript/speech sanitation, diagnostics, and interruption handling

- `android/app/src/main/java/in/diganta/aurabutler/voice/AdaptiveSpeechDetector.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/AudioCaptureEffects.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/AuraVoiceRuntime.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/FastSystemSpeaker.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/OfflineWhisperEngine.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/OwnerVoiceGuard.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/PiperSpeaker.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/Speaker.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/SpeechContinuationStack.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/SpeechLanguageSupport.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/SpeechTextSanitizer.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/TranscriptGuard.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/VoiceAudioRecorder.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/VoiceCommandClassifier.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/VoiceDiagnostics.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/VoiceManager.kt`
- `android/app/src/main/java/in/diganta/aurabutler/voice/VoiceTimingDiagnostics.kt`

## 3. Existing Kotlin test and instrumentation inventory

The test source tree currently contains 34 Kotlin test/instrumentation files. The inventory below is reconciled against the current feature-branch Git tree.

- `android/app/src/androidTest/java/in/diganta/aurabutler/background/WakeWordServiceForegroundContractTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/analyzer/NotificationClassifierTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/data/ButlerSystemPromptTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/data/DeepResearchProviderTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/data/HybridAiProviderRoutingTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/data/SafeHttpClientTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/data/YouTubeVideoResolverTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/document/RepositoryIntelligenceTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/domain/ActionSafetyPolicyTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/domain/AppCommandParsingTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/domain/ButlerPersonaTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/domain/CommandNormalizerTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/domain/DirectActionPolicyTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/domain/ForecastDayParserTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/domain/LocalButlerEngineTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/domain/MultilingualCommandBridgeTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/domain/NewsCommandParsingTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/domain/SafeCalculatorTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/domain/YouTubeAutoplayParsingTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/intelligence/IntelligenceTestSuite.kt`
- `android/app/src/test/java/in/diganta/aurabutler/memory/HybridRetrievalScorerTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/memory/SemanticMemoryFeatureVectorTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/model/ButlerActionExpiryTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/runtime/CoroutineSafetyTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/runtime/PromptInjectionGuardTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/runtime/ResponseQualityGateTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/runtime/SafeEndpointValidatorTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/runtime/SecretRedactorTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/runtime/StudentBudgetPolicyTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/voice/AdaptiveSpeechDetectorTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/voice/AudioCaptureEffectsTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/voice/SpeechTextSanitizerTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/voice/TranscriptGuardTest.kt`
- `android/app/src/test/java/in/diganta/aurabutler/voice/VoiceCommandClassifierTest.kt`

## 4. Baseline findings verified from source and project documents

1. The Gradle app module is the source of truth for the installable version. The previous default-branch configuration declared `0.17.2-reliability-latency` (versionCode 27), while the README described older v0.14 features and the v0.18 notes documented several integration/device-verification tasks as pending. This PR increments the build to `0.17.3-voice-continuity` (versionCode 28).
2. The voice service performs wake-word detection with sherpa-onnx, captures a command, uses the offline Whisper runtime, conditionally verifies the enrolled owner voice when the corresponding setting is enabled, and routes the transcript into the background command processor.
3. During Android TTS, `WakeWordService` monitors the playback session and can report either a wake-word event or sustained captured speech (`CapturedSpeech(samples)`) from the barge-in path. The recorder prefers `VOICE_COMMUNICATION`; optional Android Acoustic Echo Canceler and Noise Suppressor effects are attached where the platform supports them. With the Piper fallback, barge-in is disabled for that utterance because it cannot safely share the native wake/ASR runtime. Natural-speech capture is implemented in code, but recognition quality, self-echo rejection, and device-specific acoustic performance have not yet been accepted on physical hardware.
4. `FastSystemSpeaker` tracks Android TTS range callbacks when available and calculates an estimated progress position as a fallback. Therefore a resume position is best-effort on devices/engines that do not report range callbacks.
5. Owner voice verification is conditional on `ownerVoiceLockEnabled`. This PR does not silently force-enable biometric gating or lock out a device that has not enrolled; the project-wide security pass must make the enrollment, defaults, recovery, and sensitive-action policy explicit.
6. The v0.18 implementation summary and architecture documents describe reasoning, planning, semantic memory, and proactive components, but also list UI wiring, end-to-end integration and device/performance validation as pending. Class existence alone is not proof that a path is active.
7. The initial repository snapshot had no `.github/workflows/` CI workflow. This PR adds a GitHub Actions workflow for unit tests, lint, and debug APK assembly.

## 5. This PR — voice continuity slice

- Adds exact-phrase high-priority voice control classification for stop, pause, resume and wake-listening shutdown.
- Adds a conservative command-completeness gate for obvious unfinished phrases and bare action verbs. It intentionally does not pretend to solve full semantic slot completion or expose ASR confidence that the current transcription interface does not return.
- Adds a bounded six-entry LIFO speech continuation stack. A completed follow-up response drains the newest interrupted response first; nested interruptions remain bounded.
- Explicit stop clears queued continuations; pause holds them; continue/resume drains the queue.
- Adds unit tests for control parsing, incomplete utterances, normal command acceptance, queue order, capacity and clearing.
- Adds Android CI checks: `testDebugUnitTest`, `lintDebug`, and `assembleDebug`.

## 6. Remaining voice work before the voice phase can be called complete

- Verify true full-duplex capture on supported devices, including Android audio focus and microphone routing.
- Verify that Android AEC and Noise Suppressor actually attach and remain enabled on target handsets; measure residual echo, speaker feedback and false triggers. Platform effect availability is now handled, but this is not proof of effective acoustic echo cancellation on every device.
- Validate the sustained-speech barge-in path with arbitrary natural phrases, VAD thresholds, streaming-ASR resource arbitration, false positives, self-audio rejection and multilingual commands on supported physical devices. The capture path exists; hardware acceptance remains outstanding.
- Add explicit utterance/session timeouts and a follow-up listening window after the response.
- Improve semantic command completeness with intent-specific required slots, not only conservative phrase rules.
- Add robust multilingual (English/Bengali/Hindi) voice-control intent handling.
- Review administrator voice enrollment, owner-lock defaults, spoof/replay limitations, liveness options and recovery flows.
- Add tests for playback checkpoint accuracy, nested queue behaviour against Android TTS callbacks, microphone errors, concurrent events and long sessions.
- Run end-to-end tests on the target Vivo device. CI cannot prove acoustic performance or hardware audio behaviour.

## 7. Project-wide upgrade sequence

Voice remains the first implementation priority. The later domain sequence is dependency-aware; each phase must receive an actual code audit, a design note, implementation, unit/integration tests, and regression verification.

| Phase | Domain | Audit and engineering focus |
|---|---|---|
| 1 | Voice pipeline | Finish full-duplex interruption, owner verification, command validation, resumable speech, audio lifecycle, multilingual controls and device tests |
| 2 | Runtime and safety | Validate state ownership/races, capability policy, risk tiers, audit trail, prompt-injection boundaries, redaction and budget invariants |
| 3 | AI providers and intelligence | Task-complexity routing, provider health, bounded fallbacks, deadlines, cancellation, response validation and cost-aware routing |
| 4 | Reasoning, commands and planning | Intent/slot validation, dependency graphs, deterministic execution, replanning, idempotency, rollback/approval boundaries |
| 5 | Memory and retrieval | Data lifecycle, semantic/lexical calibration, temporal ranking, deduplication, encryption, retention and retrieval evaluation |
| 6 | Data, network and live information | Request bounds, retry/circuit-breaker policy, endpoint validation, freshness/source quality, caching and cancellation |
| 7 | Analyzer, notifications and proactive logic | Classification calibration, alert ranking, deduplication, privacy while locked, scheduling and user feedback |
| 8 | Vision, screen, documents and repository intelligence | Capture permissions/lifecycle, input limits, untrusted content isolation, chunking/retrieval quality and cancellation |
| 9 | Background automation and reminders | WorkManager lifecycle, exact/inexact scheduling constraints, delivery idempotency, time zones, retries and battery use |
| 10 | Device health, maintenance and UI | Permission transparency, resource pressure, diagnostics, accessible state feedback, settings consistency and recovery workflows |
| 11 | Cross-system integration | Voice-to-tool end-to-end paths, concurrency/race tests, performance profiling, privacy/security regression and device acceptance |

## 8. Project-wide implementation continuation — 23 November 2026

This section records the repository changes made after the original source inventory. It distinguishes implemented code from integrations that still require durable storage, production wiring, or device acceptance.

### Implemented and source-reviewed in this continuation

- **Provider selection and consent:** restored shared provider helpers. An explicitly selected Gemini or Groq provider is allowed; automatic public-provider fallback remains gated by the separate `allowPublicAiFallback` setting.
- **Task planning:** cancellation is persisted as a `CANCELLED` plan/task state and rethrown to the caller; active/running/terminal task transitions are saved; analysis cannot report success when the provider returns no credible answer; in-memory plan-history mutations are synchronized.
- **Memory retrieval:** replaced nondeterministic random vectors with bounded deterministic lexical feature hashing over tokens, bigrams and character trigrams. This is lexical retrieval, not neural semantic inference. Recall bounds query length, result count, scores and embedding dimensions. Pattern/concept validation is bounded, confidence-gated and restricted to existing memory IDs. The process-local semantic index now has a 2,000-entry LRU capacity; inverted time ranges return no matches instead of broadening the search.
- **Shared HTTP:** cloud JSON endpoints require HTTPS and reject URL-embedded credentials. Connect/read timeouts must be positive and no more than five minutes. Request bodies are capped at **16 MiB** to support the existing 10 MiB inline-image payloads after Base64 encoding; response bodies remain capped at **8 MiB**. Redirects are limited to the original host or trusted Modal hosts, use standard HTTPS ports, and never receive the original host's bearer token when the hostname changes.
- **Cancellation consistency:** budget-manifest initialization, live weather/news, keyed GNews fallback, saved-news follow-up, and YouTube command repair/resolution use cancellation-preserving handling. A caller cancellation is not converted into an ordinary provider failure or fallback reply.
- **Regression coverage:** added/updated tests for planner cancellation and truthful analysis outcomes; deterministic lexical vectors and validated concept references; memory LRU eviction and invalid time ranges; HTTP request/response/timeout/URL limits; and cancellation through the live weather/news paths.

### Integration gaps — not to be labelled complete

- `ButlerViewModel` uses `ProductionButlerEngine` around `LocalButlerEngine`. The separate `EnhancedButlerEngine`/`IntelligenceCoordinator`/`SemanticMemory` path is not established as the live production conversation path; wiring it in requires a deliberate integration design and tests that preserve existing action approvals.
- `SemanticMemory.MemoryStore` and planner `PlanStore` are process-local. The app's separate encrypted `PrivateMemoryStore` is not yet the persistence backend for semantic concepts, embeddings, plan checkpoints or plan history. The semantic index is bounded in memory but is not restored after process death.
- `LocalEmbeddingProvider` produces deterministic lexical feature vectors, not neural semantic embeddings; its configured ONNX model path is not loaded. `RemoteEmbeddingProvider` remains a deprecated compatibility fallback and does not make a remote request. Real ONNX inference and any remote embedding adapter remain future implementation work.
- `TaskPlanner` has safer execution state transitions, but research, device-action, creation, verification, communication and decision executors are not all implemented. Unsupported task types explicitly fail rather than report simulated success. Durable checkpoint recovery, parallel scheduling and dependency-aware replanning are not complete.
- `ProactiveEngine` currently generates and manages suggestions; it has no production-injected action executor. It must not autonomously claim or perform an action until an executor is integrated through `ActionSafetyPolicy`, explicit approvals, audit logging and idempotency controls.
- `StudentBudgetConfig` defaults currently use zero/unlimited limits until a manifest supplies limits. Confirm that this is the intended deployment policy before enabling broad public-provider usage.
- Physical Android acceptance remains outstanding for full-duplex capture, device AEC/NS effectiveness, sustained arbitrary-phrase barge-in, Piper fallback behaviour, long-session resource cleanup and false-trigger/echo performance. Code-path presence and emulator/CI success do not prove these acoustic behaviours on a handset.

### Current Git and verification boundary

- Working branch: `feat/aura-domain-logic-upgrade`.
- Pull request: [#2](https://github.com/DIGANTA2006/AURA-Butler-Android-Studio-v0.4.0-Native-Stable/pull/2), still deliberately a draft and unmerged.
- Base `main`: `ed97788c9d74d2377190cd47eb7150606aef6270`; no commits in this continuation were pushed to `main`.
- Source inventory at this snapshot: **604 tracked files, 100 production Kotlin files, 34 Kotlin test/instrumentation files** (reconciled from the recursive branch tree).
- Full Android CI passed on branch head `9881cdc636356030efc3ed38072a9c38a489393d`, run [#45229881956](https://github.com/DIGANTA2006/AURA-Butler-Android-Studio-v0.4.0-Native-Stable/actions/runs/45229881956): unit tests passed, Android lint passed, and `assembleDebug` passed. This validates the repository build and unit tests on the runner; it does not replace physical-device voice acceptance.

## 10. Completion policy

A subsystem is complete only when its source-level design, active call paths, settings/defaults, failure cases, unit tests, integration tests and relevant device behaviour have been verified. Report separately what is implemented, what is tested in CI, what is verified on physical hardware, and what remains unavailable or unverified.
