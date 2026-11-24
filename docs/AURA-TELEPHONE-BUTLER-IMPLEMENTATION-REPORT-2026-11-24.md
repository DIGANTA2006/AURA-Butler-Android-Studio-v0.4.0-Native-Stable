# AURA Butler — implementation and verification report

**Audit date:** 24 November 2026  
**Repository:** `DIGANTA2006/AURA-Butler-Android-Studio-v0.4.0-Native-Stable`  
**Working branch basis:** `feat/aura-cross-domain-completion-phase-2`  
**Remote PR #3 head last verified:** `a5da8783466e2a46076a2dca9532529e471e6e82`  
**Delivery status:** updated local source package only; no remote commit, push, merge, or deployment performed.

## Executive status

The source now contains two distinct telephony capabilities:

1. **Native cellular call management:** Android Telecom default-dialer integration with manual SIM call controls and metadata-only encrypted history. It cannot listen to, record, transcribe, or inject audio into an ordinary SIM call.
2. **Independent owner-hosted AI VoIP:** Android AURA connects to a self-hosted WSS/WebRTC endpoint. The endpoint runs local Whisper, Ollama and Piper components; no paid telephony, AI, ASR or TTS platform is configured. This path is an Android-app-to-local-AI session, not PSTN, and not a general two-human peer-to-peer room.

Both paths have source code, but neither has passed a complete Android build or a physical-device test in this environment. The owner must provide the domain/host, TURN ports/certificates, device token, local model files and local inference resources before VoIP can start. No neural voice weights are bundled or silently downloaded.

**Release decision: do not merge PR #3 yet.** Its last checked CI is red, and this local working copy has not been pushed for a fresh CI result. No successful debug APK, release APK, Android lint or Vivo acceptance is claimed.

Compared with the original uploaded ZIP, this working copy contains **39 added files and 9 modified files, with no source files removed**. This includes the native dialer/VoIP modules, owner-hosted server and security/privacy tests, CI job, documentation and Windows verification script. The copy has no `.git` metadata; it has not been committed or pushed.

## 1. Existing Phase 1–5 baseline

PR #3 contains source for cancellation propagation, semantic ONNX memory, durable encrypted planning, approval-gated typed executors, proactive WorkManager agents, encrypted checkpoints and partial production integration through `ProductionButlerEngine` / `ButlerViewModel`.

| Phase | Source status | Verification status | Main gaps |
|---|---|---|---|
| 1. Cancellation/lifecycle | Improvements present | Original CI compiles source; full suite not green | Blocking HTTP cancellation/connection abort and lifecycle regression |
| 2. ONNX semantic memory | Provider, model worker, tokenizer, encrypted persistence and retrieval integration present | Tokenizer/unit coverage exists; real handset ONNX inference not verified | Real model provisioning, tensor/output quality, ARM64 performance, process restart, Keystore invalidation/corruption tests |
| 3. Planning/executors | Durable plan store and typed execution boundaries present | Full process-death and idempotent side-effect flow unverified | End-to-end resume, approval/rejection/expiry/retry/dependency-failure testing |
| 4. Proactive intelligence | Agents, WorkManager scheduler, encrypted persistence and approval gateway present | Previous full test suite failed one test; runtime/reboot verification absent | WorkManager reboot, time-zone, deduplication, notification permission and stale approval tests |
| 5. Production integration | Partial active-path wiring present | Source trace only; no complete UI-to-storage acceptance | Settings, lifecycle, errors, actual device behavior and duplicate-path review |

Approximate engineering assessment of the pre-existing Phases 1–5 implementation remains around 75% implemented. This is not a test-coverage value or production-readiness score.

## 2. Original CI failure and local test correction

Last remotely checked run: `45319787869`, Android CI job `verify`.

- Kotlin production compilation passed.
- 182 unit tests ran; 181 passed and one failed:
  `ProactiveEngineLifecycleTest.detectsRecurringTopicsOnlyFromUserMessagesOnDistinctDays`.
- Android lint and debug APK assembly were skipped because the unit-test task failed.

The failing test asserts that a recurring-topic description contains `AURA conversations` and does not contain `other apps`. The prior description contradicted the latter assertion with wording about “activity in other apps.” The working copy now uses: `This insight uses only messages you shared with AURA.` The assertion was not weakened. A focused harness confirmed the topic suggestion itself is generated, but the full Gradle test has not been rerun on this patched working copy.

The local Gradle 8.13 wrapper could not download its distribution because `services.gradle.org` was not resolvable in this execution environment. Android SDK, `adb` and a connected phone are absent. Therefore no post-patch Kotlin compile, unit-test, lint, APK or instrumentation result is claimed.

Run on the Android development machine:

```powershell
cd android
.\gradlew.bat --no-daemon testDebugUnitTest --tests "*ProactiveEngineLifecycleTest.detectsRecurringTopicsOnlyFromUserMessagesOnDistinctDays" --info --stacktrace
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## 3. Capability A — native SIM cellular management

The working copy includes:

- `AuraPhoneActivity`: ACTION_DIAL UI, default-dialer role request, keypad, explicit SIM call placement, call history and retention controls.
- `AuraInCallService` and `AuraCallRegistry`: Telecom lifecycle, manual answer/reject/end, hold/resume, mute and advertised endpoint selection.
- `AuraCallNotifications` and `AuraCallActionReceiver`: incoming/ongoing call notification controls; public lock-screen preview avoids caller-number disclosure.
- `EncryptedCallHistoryStore`: local encrypted call metadata, bounded history and deletion/retention controls.
- Android 14+ `CallEndpoint` selection with deprecated legacy route fallback only for API 26–33.

No `READ_CALL_LOG`, `CAPTURE_AUDIO_OUTPUT`, `CAPTURE_MEDIA_OUTPUT`, root, hidden APIs or accessibility automation is used. SIM audio capture/recording/transcription/TTS injection is not implemented. Manual UI/notification owner action is the only answer path. Spoken voice command routing, contact allowlists, DND rules, call screening and automatic answer policies remain incomplete.

The target device mentioned in the project setup is Vivo 1901 / API 30. Source-level API compatibility does not prove OEM/carrier behavior. Test default role acquisition/removal, real incoming/outgoing calls, hold, speaker/earpiece/Bluetooth, lock screen, notification denial, background process death and reboot on the handset. Emergency calls remain handled by Android's preloaded dialer.

## 4. Capability B — owner-hosted AI VoIP source implementation

### Android client modules

- `AuraVoipConfigStore`: persists the WSS URL, device ID, token and preferred language through the project's encrypted-preferences mechanism.
- `AuraVoipSignalingClient`: TLS WebSocket bearer authentication, room join, bounded signalling JSON and unexpected-close reporting.
- `AuraWebRtcAudioPeer`: audio-only WebRTC, relay-only ICE, owner-provided TURN credentials, DTLS-SRTP transport, Android microphone/audio device integration, hardware AEC/NS where available, and resource disposal.
- `AuraVoipCallManager`: lifecycle coordination, ICE disconnect recovery window, transcript collection, local encrypted history persistence and post-call notification.
- `AuraVoipForegroundService`: visible microphone-use notification, Android communication audio mode/focus and wake-listener arbitration.
- `AuraVoipCard`: owner-configured endpoint/device token/language controls and manual start/end UI.

### Self-hosted server modules

`server/voip/` contains:

- `server.py`: FastAPI WSS signalling and audio-only aiortc endpoint, VAD, local ASR, local LLM conversation orchestration, local Piper speech synthesis and local summary generation.
- `aura_security.py`: strict device/room/host validation, constant-time token comparison and short-lived coturn REST credentials.
- `test_aura_security.py`: pure standard-library tests for those security helpers.
- `aura_privacy.py` and `test_aura_privacy.py`: deterministic, fail-closed English/Bengali/Hindi transcript-consent parsing and per-call consent gate tests. Partial consent (for example, "summary only") remains ambiguous rather than enabling both transcript and summary retention.
- `aura_asr_quality.py` and `test_aura_asr_quality.py`: conservative Whisper segment-quality repeat heuristics, kept separate from language-identification probability.
- `Dockerfile`, `docker-compose.yml`, `Caddyfile.example`, `.env.example`, `.dockerignore`, `create_device_token.py`, `README.md`, `LICENSES.md`.

The server has no external ASR/LLM/TTS fallback. It requires the owner to provision a local faster-whisper model directory, three Piper voice model/config pairs for `en`, `bn`, `hi`, a local Ollama model, WSS domain/certificate and coturn certificate/secret. Audio remains in memory; the server does not write call recordings. Transcript and generated summary are sent to Android over WSS only after affirmative caller consent, then saved locally in encrypted history. A clear refusal or two ambiguous consent responses disables transcript/summary retention for the session.

### Correctly scoped protocol

The only implemented endpoint role is `caller`: the Android AURA app talks to the owner's local AI endpoint. The server does not yet provide a general two-human room relay, browser client, Android `ConnectionService`/Core-Telecom call presentation, or PSTN/SIP gateway. It cannot call ordinary mobile or landline numbers. A carrier-backed PSTN route entails carrier infrastructure and costs and is excluded by design.

The local LLM has no tools for placing calls, booking appointments, sending messages, payments or reading owner memory/calendar. It can take a message and explain limitations. Owner-approved follow-up actions still require separate app flows. Appointment negotiation/booking is not implemented.

### Local model language boundary

The code is configured for multilingual Whisper ASR and Piper voices in English, Bengali and Hindi. These are model interfaces/configuration, not proof that suitable voice weights exist or work. The source ZIP does not contain the needed Whisper/Piper/Ollama weights. Language accuracy, accents, mixed-language behavior, interruption, echo, latency and voice quality are all unverified until locally provisioned models and physical calls are tested.

## 5. Deployment packaging correction

Static inspection found a container-startup defect: `server.py` imports `aura_security.py`, but the Dockerfile previously copied only `server.py`. The current working copy now copies both source files into `/app`. Compose service image references are pinned to explicit versions rather than floating `latest` tags:

- `ollama/ollama:0.40.17`
- `coturn/coturn:4.19.2-r0`
- `caddy:2.11.9`

The AI container runs unprivileged with a read-only root filesystem, read-only model volume, a bounded `/tmp`, and `no-new-privileges`. Coturn receives a static container IP so the documented public/private external-IP mapping is stable. This configuration has passed YAML parsing, but Docker Compose config validation, image pulls, container build/startup, TLS, NAT and real TURN media tests were unavailable (Docker/Podman not installed).

## 6. Checks performed in this working copy

| Check | Result |
|---|---|
| Original ZIP / branch-file identity comparison | Inspected files matched the stated PR #3 source checkpoint |
| Python `compileall` for `server/voip` | Passed |
| `python -m unittest -v test_aura_security.py test_aura_privacy.py test_aura_asr_quality.py` | 20 passed |
| Docker Compose YAML parse with PyYAML | Passed |
| Manifest XML parse | Passed |
| Prohibited paid telephony provider scan in new telephony source | No named paid provider integration found |
| Prohibited cellular audio-capture permission/API scan in new phone module | No protected capture API added |
| Dockerfile / container build | Not run; Docker unavailable |
| Gradle build/test/lint/APK | Not run; wrapper download/Android SDK unavailable |
| Vivo physical-device testing | Not run |
| GitHub push / CI rerun / PR merge | Not performed |

## 7. Critical remaining work before release

1. Run the corrected full Android Gradle pipeline and resolve every failure without weakening tests.
2. Build the Docker image and run Compose config/startup tests on a Linux host; verify the `aura_security.py` import, health check, local model loading and Ollama connection.
3. Configure local Whisper/Piper/Ollama weights and verify actual English/Bengali/Hindi ASR/TTS, language detection and CPU/GPU resource envelope.
4. Perform real WSS/TURN/WebRTC testing across mobile networks/NAT, relay-only media, ICE interruption/recovery, audio focus, microphone permissions, barge-in, jitter, packet loss and thermal/battery load.
5. Test encrypted call transcript/summary storage, retention, deletion and Android process-death behavior.
6. Complete native SIM tests on Vivo 1901; wire the owner voice command path to `AuraCallRegistry` only after speaker identity/owner authorization is confirmed.
7. Implement a self-managed VoIP `ConnectionService` if the VoIP call must appear in the Android system call UI; it remains separate from native SIM management.
8. Add owner-approved contact/workflow integrations only where real local data/API support exists; do not claim appointments, messages or payments are completed before external confirmation.
9. Update PR #3 description and branch after reconciliation with latest `main`, then rerun CI. Do not merge until all software and device release gates are met.

## 8. Final engineering statement

This delivery is a source-level implementation increment, not a production release. Native SIM call control is separated from AI audio. The owner-hosted WebRTC local-AI path is implemented in source but awaits container/model/device acceptance. No paid telephony or AI provider is required by the design, and no such provider has been integrated. No GitHub change or merge has been made.

### Follow-on hardening — source-only increment (24 November 2026)

The current working copy adds the following corrections after the initial phone/VoIP implementation:

- Android 14+ `InCallService` endpoint callbacks and registry adapters are annotated for API 34; Android 8–13 retain the guarded legacy route fallback only.
- If a VoIP call has neither a retained summary nor transcript, its notification now says that no transcript/summary is available instead of incorrectly claiming a transcript exists.
- The VoIP foreground-service start path reserves the single-call slot before starting the service. A duplicate start no longer stops the foreground service belonging to an already active call.
- The audio service now terminates the new session if Android refuses communication audio focus; it does not silently continue with an unmanaged audio mode.
- On Android 13+, the UI requires notification permission before microphone permission and foreground-service creation, making the ongoing microphone-use indicator an explicit prerequisite.
- Server-side Piper child processes are terminated on timeout/cancellation, guarding the process-kill race. If the caller closes the audio track before VAD observes its trailing-silence boundary, a sufficiently long final utterance is queued for processing.
- Whisper language-identification probability is no longer treated as word-recognition confidence. The server also evaluates segment `avg_logprob`, `no_speech_prob` and `compression_ratio` with explicitly documented conservative heuristics; these request a repeat but are not a calibrated transcript-accuracy percentage. Six pure tests cover this quality gate.
- The initial greeting now tells callers that speech is processed on Diganta's self-hosted AURA server and asks consent to retain text in Diganta's encrypted AURA call history, rather than the ambiguous phrase "on this device."

These are source changes and static checks only. The Android app has not been compiled after them, and live media/audio behaviour has not been tested. The server still requires owner-provisioned local Whisper, Ollama and Piper models; there is no external fallback.


### CI follow-up

The local workflow now adds an independent Python 3.12 `voip_backend` job that installs pinned runtime requirements, imports the FastAPI/WebRTC server module, compiles the self-hosted server source and runs the 20 security/privacy/ASR-quality tests. It does not download model weights. The existing Android job remains separate. This workflow change is not yet in GitHub and therefore has no remote CI result.
