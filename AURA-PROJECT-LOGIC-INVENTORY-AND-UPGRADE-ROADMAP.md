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

### Git and verification boundary after merge

- Pull request [#2](https://github.com/DIGANTA2006/AURA-Butler-Android-Studio-v0.4.0-Native-Stable/pull/2) was merged on **24 November 2026 at 01:37 UTC**.
- Production `main` is merge commit `d1167ac9f334ae90d98faeae65b789bee92ecad8`.
- The merged source inventory is **604 tracked files, 100 production Kotlin files and 34 Kotlin test/instrumentation files**, reconciled from the recursive `main` tree.
- Post-merge Android CI [#45306443108](https://github.com/DIGANTA2006/AURA-Butler-Android-Studio-v0.4.0-Native-Stable/actions/runs/45306443108) passed on the merge commit: unit tests, Android lint and `assembleDebug` all succeeded. This is runner validation, not physical-device voice acceptance.

## 9. Post-merge full-source audit and remaining implementation — 24 November 2026

The merged `main` tree was re-enumerated and all **100 production Kotlin source files** were fetched for a source-level static pass. This completes the inventory/reconciliation step; it does **not** mean every logic file has been redesigned or individually rewritten.

### Confirmed additional cancellation defects and follow-up patch

The static pass found that keyless knowledge fallback and ViewModel chat/document/image-analysis error paths could convert coroutine cancellation into a fallback, error message or alternate-provider attempt. These paths have been corrected on the follow-up branch `feat/aura-cross-domain-completion-phase-2` using `runCatchingCancellable` and explicit `CancellationException` rethrows. This follow-up is not yet merged; it requires CI validation.

### Remaining high-priority work — not complete

1. **Semantic memory durability and genuine embeddings.** The live app's ordinary private memories and temporal history are persisted through `SecureStore` → `PrivateMemoryStore` / `TemporalMemoryStore`. The separate `SemanticMemory.MemoryStore` used by `IntelligenceCoordinator` is still process-local, and it is not backed by those durable stores. `LocalEmbeddingProvider` currently uses deterministic lexical feature hashing; the configured ONNX model path is not loaded. Actual ONNX inference, embedding-quality evaluation and durable semantic-index restoration remain unimplemented.
2. **Durable task plans.** `PlanStore` is still an in-memory repository. Plan history and checkpoints do not survive process death.
3. **Real task executors.** `TaskPlanner` has safe cancellation/state handling and honest result reporting, but research, device action, creation, verification, communication and decision task types do not have complete live executors. Unsupported task types fail rather than claim success.
4. **Proactive action integration.** `ProactiveEngine` can generate and manage suggestions, but its action executor intentionally returns `false`. Suggestions cannot perform real actions until a reviewed executor is wired through the production approval, safety and audit path.
5. **Advanced intelligence production wiring.** The running UI constructs `ProductionButlerEngine` around `LocalButlerEngine`. `IntelligenceCoordinator` and `EnhancedButlerEngine` remain a separate, non-production-wired path. Integrating them safely requires shared persistence, settings/context lifecycle, and tests that prove existing deterministic commands and approvals still win.
6. **Voice hardware acceptance.** The code includes an AEC-gated natural-speech capture path plus wake-word fallback, but platform effect availability is not proof of effective echo cancellation. Test full-duplex capture, TTS self-echo rejection, owner speech sensitivity, latency, headset/speaker routing and Piper fallback on the target handset.
7. **Blocking network interruption audit.** `SafeHttpClient` provides bounded synchronous HTTP calls. The remaining provider call sites need an explicit cancellation/connection-abort acceptance pass so a cancelled coroutine does not leave a blocking socket operation running until its read timeout.

### Configuration decision, not a claimed defect

`StudentBudgetConfig` deliberately documents zero daily limits as unlimited (“JARVIS MODE”). Confirm that this is still the intended owner policy before changing it; do not silently introduce artificial caps or treat the documented unlimited setting as an implementation defect.

### Completion accounting

- **Inventory and static source pass:** 100/100 production Kotlin files enumerated and scanned.
- **PR #2 direct production-file touch count:** 17/100 files (17%). It also changed eight test files and the roadmap; shared changes such as `SafeHttpClient` and cancellation helpers affect multiple consumers, so this ratio is a direct-touch measure, not a feature-completion score.
- **Automated build state on merged main:** green (unit tests + lint + debug APK).
- **Overall request “upgrade every logic fully”:** **not complete**. The remaining work above is substantive engineering and hardware validation, not cosmetic cleanup.

## 10. Completion policy

A subsystem is complete only when its source-level design, active call paths, settings/defaults, failure cases, unit tests, integration tests and relevant device behaviour have been verified. Report separately what is implemented, what is tested in CI, what is verified on physical hardware, and what remains unavailable or unverified.

## 11. PR #3 implementation and verification status — 24 November 2026

This section supersedes the Phase 2–5 implementation-gap bullets above where PR #3 now contains concrete code. It does not change the status of the merged `main` branch: all items below are on the open draft branch `feat/aura-cross-domain-completion-phase-2` until PR #3 is reviewed, validated and merged.

### Implemented on the PR #3 branch

- **Cancellation/lifecycle:** added explicit cancellation preservation through keyless knowledge fallback and the chat, document and private image-analysis ViewModel paths. Cancellation is rethrown instead of being converted into a fallback result or alternate-provider attempt.
- **Phase 2 — ONNX semantic memory:** added a real ONNX Runtime sentence-embedding provider, WordPiece tokenizer, quantized MiniLM model/vocabulary download pinned to a fixed source revision and SHA-256 digests, bounded model download, verified installation marker, and AES-GCM/Android-Keystore encrypted SQLite storage for semantic memories and vectors. The live `ProductionButlerEngine` path reconciles the owner's private memories into the semantic store and uses hybrid retrieval when semantic memory is enabled. A further PR #3 fix synchronizes model download/cancellation with live settings changes; restart is not required.
- **Phase 3 — planning:** added encrypted durable plan/checkpoint storage, reload support, a production Android planning composition root, and typed executors for live research, analysis, creation, verification, decisions, approval-gated actions, and communication drafts. Communication remains draft-only; the app does not claim to send messages. Side effects still require an explicit approval card.
- **Phase 4 — proactive intelligence:** added encrypted persistence for suggestions and agent checkpoints, WorkManager scheduling/rehydration, local device/context agents, recurring-topic detection from user-authored AURA chat only, research follow-up from explicitly tagged local memories, notification handling and a production action gateway. The gateway routes external/device effects through the existing approval policy; arbitrary command execution from proactive suggestions is blocked.
- **Phase 5 — production integration:** wired semantic retrieval into the active `ProductionButlerEngine` unresolved-request path; wired plan commands, plan approvals/resumption, proactive suggestions, approvals and encrypted proactive state into `ButlerViewModel`. `IntelligenceCoordinator` remains a separate legacy/orchestration class and is not the active chat composition root; do not count that class being present as proof that it is in the production call path.
- **Live configuration correction:** enabling semantic memory after startup enqueues the verified model worker; disabling it cancels the unique pending model job. Both paths retain WorkManager's unmetered-network and battery-not-low constraints.

### Validation boundary

- The original PR #3 workflow run `45318149144` compiled Kotlin successfully and ran 182 unit tests, of which 179 passed and three failed in proactive-agent tests. Lint and APK assembly were skipped because the test task failed.
- The three failing assertions were tied to the newer scheduled-agent behavior: habit detection is executed by the Habit Learning agent, disabled semantic consolidation is persisted as a disabled agent, and the Daily Briefing/Habit Learning/Research Follow-up agents are now enabled. Their tests were updated on the PR branch to exercise the agent execution path and assert the current enabled-agent contract.
- The test updates and live semantic-memory settings correction are committed on the PR branch. **A passing rerun of the full workflow has not yet been verified.** The PR remains a draft and is not merged into `main`.
- The ONNX tokenizer has unit coverage, but an actual downloaded model session, embedding output quality, encrypted Android Keystore/SQLite behavior, WorkManager recovery and plan-resume behavior still require device or integration acceptance. A successful Kotlin compilation alone does not validate those runtime paths.

### Remaining work before production completion

1. Obtain a fresh green PR workflow for the latest head: unit tests, Android lint and debug APK assembly. Fix any further failures rather than weakening assertions.
2. Add or run Android integration tests against the actual SQLite/Keystore persistence adapters, verifying restart restoration, deletion, corruption handling, key invalidation and bounded retention.
3. Run the pinned ONNX model on a supported ARM64 handset, validate model download/install/retry/cancellation, tensor compatibility, embedding dimension/finite values, semantic retrieval quality and memory/performance impact.
4. Exercise complete task-plan flows on Android: create, execute, checkpoint, process death, restore, approval, rejection, expiry, resume, cancellation, retry and dependency failure. Verify every executor has honest outputs and idempotent side-effect boundaries.
5. Exercise proactive WorkManager behavior across process death/reboot, duplicate scheduling, disabled settings, stale approvals, notification permission denial, retries and time-zone boundaries.
6. Finish the blocking HTTP cancellation/connection-abort audit so cancellation does not leave synchronous sockets running until their read timeout.
7. Complete physical-device voice acceptance on the target handset: full-duplex capture, AEC/NS effectiveness, TTS self-echo rejection, arbitrary-phrase barge-in, owner voice sensitivity, headset/speaker routing, latency, Piper fallback, long-session cleanup and false-trigger behavior.

### Completion reporting

Report these independently: implementation present on the PR branch; automated CI status for the exact latest commit; Android runtime/integration results; physical-device acceptance. Until each relevant item passes, the PR #3 upgrade must be described as implemented-in-progress, not fully production-complete.


## 12. Zero-paid telephone Butler implementation and feasibility — 24 November 2026

**Hard architecture decision:** the telephone feature must not introduce a paid third-party telephony, SIP/PSTN, AI-calling, ASR or TTS subscription. No Twilio, Plivo, Vonage, Telnyx, Vapi, Retell, paid gateway or paid voice API is permitted. The normal SIM path uses only the owner's existing SIM/carrier plan; the full conversational-AI path is a separate owner-controlled AURA-to-AURA VoIP service.

### Native SIM call-management source added to the PR #3 working copy

- Added `AuraPhoneActivity`, `AuraInCallService`, `AuraCallRegistry`, explicit call notifications/actions, call models and encrypted metadata-only call history.
- Added `ROLE_DIALER` request flow, `ACTION_DIAL` intent handling, explicit user-initiated `TelecomManager.placeCall`, manual answer/reject/end, capability-aware hold/resume and audio-route controls.
- Added history retention (30/90/180/365 days), bounded to 100 records, with deletion and clear-all controls. If Keystore-backed history cannot open, the UI reports that it is unavailable.
- Added the phone entry point in Settings and a detailed architecture/acceptance document at `docs/AURA-ZERO-PAID-TELEPHONE-BUTLER.md`.
- No cellular audio capture, audio injection, cellular recording, STT/TTS over SIM, covert recording, `CAPTURE_AUDIO_OUTPUT`, `READ_CALL_LOG`, root, hidden APIs or accessibility workaround is included.

**Not yet wired or verified:** the existing voice command pipeline does not yet route spoken “answer/reject this call” commands into `AuraCallRegistry`; incoming approval is via the call UI or explicit notification action. There is no contact enrichment, screening/allowlist/automatic-answer policy, caller-purpose AI or post-call conversational report. Android CI and physical-device validation of these new files have not been run. Treat this as source implementation, not a release-ready dialer.

### Independent AI VoIP path — implemented in source, validation pending

The working copy now includes an Android AURA app-to-owner-hosted-local-AI WebRTC path: authenticated WSS signalling, short-lived owner coturn credentials, relay-only ICE, audio-only WebRTC/DTLS-SRTP, local VAD, faster-whisper ASR, Ollama local LLM and Piper local TTS. The service refuses startup when owner-provisioned local ASR/Piper models or credentials are missing; it does not download model weights or use external AI fallbacks. English, Bengali and Hindi voice paths are configured, but actual model quality and end-to-end audio acceptance remain unverified.

Transcript/summary retention requires a clear caller yes in English, Bengali or Hindi. A clear no disables retention; two ambiguous answers also fail closed. No audio recording is written. Consent parsing and the two-step gate are deterministic and independently unit-tested. Transcript events are sent to Android only after consent, and the Android client stores them in encrypted local call history.

The current endpoint supports only the Android AURA app calling the owner's local AI endpoint. It is not a general human-to-human AURA room relay, browser client, Android self-managed `PhoneAccount`/`ConnectionService`, PSTN bridge, or appointment-calling system. The source requires full Gradle, Docker, model, TURN/network and physical-device acceptance before it can be called working. Calling ordinary mobile/landline numbers through AI still requires carrier/PSTN interconnection and fees; this remains excluded.

### Current PR #3 validation checkpoint

The latest checked run `45319787869` compiled Kotlin and ran 182 tests: 181 passed and one failed in `ProactiveEngineLifecycleTest.detectsRecurringTopicsOnlyFromUserMessagesOnDistinctDays`; lint and debug assembly were skipped. Source inspection found that the suggestion text contained `other apps` while the test explicitly forbids that phrase, although it also requires the `AURA conversations` marker. The working-copy description now says `This insight uses only messages you shared with AURA.`; the test was not weakened. A focused harness produced the expected recurring-topic suggestion, but the full Gradle suite was not rerun. The exact failure assertion was not included in the fetched CI log, so only the deterministic source mismatch is confirmed, not a new passing CI result.

### Next gates

1. Run the failing test and complete unit/lint/debug tasks against the patched source on a machine with the pinned Gradle distribution and Android SDK 36; push only after local inspection.
2. Obtain a fresh green Actions run on the feature branch. Keep PR #3 open/draft and do not merge until the full diff is reviewed and reconciled with `main`.
3. Validate `ROLE_DIALER`, incoming/outgoing SIM calls, notification and lock-screen behaviour, hold/resume, audio routes, role revocation, process death and reboot on Vivo 1901/API30; repeat on a newer Android release.
4. Validate encryption, key invalidation, corrupted history recovery, retention and deletion; verify that no SIM audio/transcript is captured.
5. Add voice-command approval only through a trusted owner-authorization flow; keep manual UI approval as the default.
6. Complete end-to-end validation of the implemented Android-app-to-local-AI WebRTC path: local model loading, WSS, coturn relay-only media, language acceptance, audio focus, lifecycle, latency/battery, and caller consent. Do not add PSTN bridging or paid vendors.
7. If needed as a separate future scope, implement a general human-to-human AURA room relay and Android self-managed `ConnectionService`; neither is included in the current AI endpoint.
