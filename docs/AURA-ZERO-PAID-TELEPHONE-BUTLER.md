# AURA Telephone Butler — zero-paid telephony architecture

**Status date:** 24 November 2026  
**Repository branch:** `feat/aura-cross-domain-completion-phase-2`  
**Policy:** no paid third-party telephone, SIP/PSTN gateway, AI calling, STT or TTS platform.

## 1. Non-negotiable cost and privacy policy

AURA must not depend on Twilio, Plivo, Vonage, Telnyx, Vapi, Retell AI, paid cloud telephony, paid speech APIs, paid AI calling subscriptions, or a free trial that later requires payment. No PSTN gateway or external calling provider is part of this design.

Default processing is on-device. Call metadata and any future AURA-VoIP transcripts remain local and encrypted. No call audio, transcript, contact list or personal memory is sent to an external AI provider. Missing local models must produce an honest unavailable/please-repeat response, never a silent paid-cloud fallback.

Normal cellular calls use the SIM and the owner's existing carrier plan. Those calls remain subject to the carrier's ordinary tariff. No new virtual phone number is purchased.

## 2. Capability A — Android-native cellular Butler

### Current source implementation on this branch

The following native SIM-call-management code has been added to the PR #3 feature-branch working copy:

- `AuraPhoneActivity`: explicit Android default-phone role request, `ACTION_DIAL` entry point, dial pad, owner-initiated `TelecomManager.placeCall`, call permission flow, and call-history UI.
- `AuraInCallService`: Telecom service registration and foreground in-call UI callback.
- `AuraCallRegistry`: tracks Telecom `Call` objects by opaque session IDs and exposes lifecycle snapshots to Compose; manual answer, reject, disconnect, hold, resume, mute and audio-route operations.
- `AuraCallNotifications` / `AuraCallActionReceiver`: incoming and ongoing call notifications with explicit answer/reject/end actions. Caller number is private on the lock screen; the public notification preview is generic. Full-screen alerts are gated by Android 14+ special access.
- `EncryptedCallHistoryStore`: AndroidX Security Crypto / Android Keystore-backed encrypted metadata history, bounded to 100 entries, configurable retention of 30, 90, 180 or 365 days, per-record deletion and confirmed clear-all.
- Settings entry to open AURA Native Phone.

**This is implementation present in source, not yet CI-verified or device-accepted.** The provided execution environment lacks the Gradle 8.13 distribution, Android SDK and a connected handset. A Gradle test attempt was blocked because the wrapper could not reach `services.gradle.org`; no APK result is claimed.

### Permission and role model

AURA asks the user to select it as `RoleManager.ROLE_DIALER`. Android grants the role only after user confirmation. The app declares:

- `android.permission.BIND_INCALL_SERVICE` as the binding permission on its `InCallService` declaration (not as an app-granted permission).
- `android.permission.CALL_PHONE` for owner-confirmed `TelecomManager.placeCall`, requested at runtime when needed.
- `android.permission.USE_FULL_SCREEN_INTENT` for the incoming-call notification experience; Android 14+ access is checked and can be managed in system settings.
- The existing notification permission is requested from the phone UI on Android 13+ where needed.

The app intentionally does **not** request `READ_CALL_LOG`, `CAPTURE_AUDIO_OUTPUT`, `CAPTURE_MEDIA_OUTPUT`, root, accessibility privileges or hidden APIs. It does not use speakerphone recording as a workaround.

### What is supported

| Capability | Support boundary |
|---|---|
| Receive incoming/ongoing Telecom call callbacks | User-selected default dialer role and bound `InCallService` |
| Show caller number/handle where the carrier exposes it | `Call.Details.handle`; withheld/private numbers remain unknown |
| Answer/reject/end | Explicit owner action through the `Call` object |
| Hold/resume | Only when Telecom reports hold capability |
| Earpiece/speaker routing | Public Telecom routing controls; device routes still depend on OEM and active hardware |
| Place SIM call | Explicit owner tap, `CALL_PHONE` as required, existing SIM/carrier plan |
| Call history | Only AURA-managed metadata, encrypted locally; not a scrape of the system call log |
| Automatic answer | **Disabled; manual approval only** |
| Spoken owner command to answer/reject | **Not connected yet**; use the AURA call screen or notification action |
| AI speaking/hearing through a SIM call | **Not available to an ordinary app** |
| Full two-sided cellular call recording | **Not available to an ordinary app through supported APIs; not implemented** |

`ROLE_CALL_SCREENING` and `CallScreeningService` are separate from the dialer role. They can allow, silence or block a call and must answer Telecom's screening request within five seconds. They do not answer the call on AURA's behalf and do not provide a call-audio stream. That role is not added in this first native-call implementation because it is not needed for user-approved answer/reject control. The current code also does not connect an owner's spoken phrase such as “AURA, answer this call” to the call registry; approval is by the in-call screen or its explicit notification action only.

A default dialer must provide an actual dial pad and incoming/ongoing in-call UI. Telecom routes emergency calls through the preloaded system dialer even when AURA holds the default-dialer role. The app must handle Telecom calls generally and cannot assume every observed call is a SIM call. Role eligibility and OEM behaviour still need a physical-device acceptance test.

### Protected cellular-audio boundary

`InCallService` and `Call` provide call state, call metadata, call control and supported endpoint routing. They do not give a third-party default dialer decoded two-way cellular PCM or a public API to inject arbitrary speech into the carrier uplink. Android's `CAPTURE_AUDIO_OUTPUT` permission is system/privileged; declaring it does not grant it to an ordinary app. `VOICE_CALL`, `VOICE_UPLINK` and `VOICE_DOWNLINK` capture therefore are not a valid AURA implementation path.

Consequently, the SIM call UI must never claim that AURA's AI is speaking with or listening to the caller. The current implementation does not attempt cellular recording, ASR, TTS injection, or covert audio capture.

### Device-specific position

The repository setup notes identify the intended handset as Vivo 1901 / API 30 (Vivo Y15). The source uses standard Android Telecom APIs available at that level, but no physical device was available during this audit. Vivo/Funtouch OS behaviour for default-dialer selection, incoming UI, full-screen notifications, call endpoints, Bluetooth routing, background startup and battery controls remains to be tested on that handset. No publicly documented Vivo third-party API granting cellular call PCM access was identified; no such OEM privilege is assumed.

## 3. Capability B — Android AURA app to owner-hosted local AI VoIP

The first version is implemented in source, but is **not yet build-, container-, model- or device-verified**. It is intentionally narrower than a general AURA-to-AURA peer room: one authenticated Android AURA app connects to the owner's local AI endpoint. It does not create a free PSTN gateway or support arbitrary human-to-human rooms.

### Endpoint and media architecture

- Android client modules: `AuraVoipConfigStore`, `AuraVoipSignalingClient`, `AuraWebRtcAudioPeer`, `AuraVoipCallManager`, `AuraVoipForegroundService` and `AuraVoipCard`.
- Owner-hosted server modules: `server/voip/server.py`, `aura_security.py`, local model configuration, Caddy WSS reverse proxy, Ollama, coturn and Docker Compose.
- Use audio-only WebRTC with DTLS-SRTP. The Android client forces relay-only ICE and accepts short-lived coturn credentials returned by the authenticated server.
- Use owner-operated TLS WebSocket signalling for authenticated device join and SDP offer/answer. Signalling carries control/SDP metadata, not audio frames.
- The first server endpoint accepts only the Android AURA caller role. A browser guest client, general two-human peer rooms, direct peer mode, SIP gateway and Android self-managed `ConnectionService` are not implemented.
- Device tokens are bound to a validated client ID and compared in constant time. TURN credentials are short-lived coturn REST HMAC credentials. Server-side utterance queues and conversation context are bounded.

### Local AI conversation pipeline

`WebRTC receive track → 16 kHz resampling + WebRTC VAD → local faster-whisper ASR → local Ollama conversation policy → local Piper TTS → WebRTC send track`.

The current server holds audio and conversation context in memory only. It does not persist recordings. Android saves transcript and local summary in encrypted call history only after affirmative caller consent. A clear refusal or two ambiguous consent answers disables transcript/summary retention. AEC/noise suppression is requested from Android's hardware audio device module where supported; effective acoustic performance is not established by enabling those flags.

The current LLM system prompt is constrained to the following policies; these are prompt-level controls, not a formal guarantee of perfect behavior:

1. Introduce itself honestly as AURA, an AI assistant.
2. Ask the caller's name and purpose without inferring identity from caller ID alone.
3. Say clearly when it does not know something.
4. Never disclose credentials, OTPs, bank data, private notes, contacts or unapproved schedule details.
5. Capture only minimal message/appointment details.
6. Ask clarifying questions when ASR confidence is low or a sentence is ambiguous.
7. Require owner approval before making commitments, bookings, payments or external actions.
8. Support interruption/barge-in and graceful model/network failure.

The server requires a locally provisioned faster-whisper model directory, an owner-selected local Ollama model and three Piper ONNX voice/config pairs (`en`, `bn`, `hi`). The ZIP does not include these model weights; Compose does not auto-download them. The code's language configuration is not proof of language quality. English/Bengali/Hindi and mixed-language behavior remain **unverified** until the real assets are provisioned, licences reviewed, and end-to-end calls pass on target hardware.

If mobile hardware cannot run ASR + LLM + TTS with acceptable latency, an optional local inference host can run on the owner's own computer/server over an authenticated encrypted connection. It must not be a managed external AI/telephony vendor. If that host is unreachable, AURA must fail closed and tell the caller it cannot continue the conversation.

### PSTN and ordinary phone-number boundary

A self-hosted WebRTC/SIP system does not automatically reach an arbitrary ordinary mobile or landline number. PSTN connectivity requires a carrier interconnect, paid SIP trunk, SIM gateway or another carrier-backed route. Such a route brings carrier charges, hardware/SIM operating costs and regulatory obligations. This architecture excludes PSTN bridging to maintain the no-paid-third-party telephony requirement.

Ordinary phone numbers are reached through the owner's normal SIM calling. Full AI conversations are available only when both sides use AURA-compatible VoIP endpoints.

## 4. Recording, consent and retention policy

- No cellular call recording or call transcript is implemented.
- VoIP recording is off by default and is not part of the first VoIP implementation.
- A future recording feature requires legal review, visible recording state, an audible disclosure, explicit participant consent, and a hard refusal/stop path. If anyone refuses or withdraws consent, recording stops and the session continues without recording only if the product and legal policy permit it.
- Keep only the minimum message and metadata needed. Encrypt at rest with Android Keystore-backed keys. Provide deletion and retention controls.
- A local transcript is different from a recording; it still contains personal data and requires the same privacy policy, access control, retention and deletion design.

This policy is a conservative product-security rule, not a legal conclusion about every recording scenario in India. Obtain Indian legal advice before shipping recording or operating an externally accessible calling service.

## 5. Zero-recurring-platform cost model

| Component | Expected owner cost |
|---|---|
| SIM calls | Existing mobile plan and normal carrier tariff |
| Local ASR / LLM / TTS | Device compute and model storage/download |
| Encrypted metadata | Device storage |
| AURA signalling | Owner-controlled computer/server, power and internet |
| Peer-to-peer WebRTC | Existing internet connections |
| coturn relay | Owner hardware, bandwidth and power |
| PSTN access | Not included; requires carrier-backed service and costs |

WebRTC and coturn are open-source components with permissive upstream licensing, but the precise library versions, codec implementations, bundled binaries and model/voice weights must each be licence-audited before redistribution. Open-source software does not make carrier interconnection or broadband free.

## 6. Verification and release gates

Before treating native SIM support as complete:

- Run `testDebugUnitTest`, `lintDebug`, `assembleDebug` and a release build on Android SDK 36 / Java 17.
- Install on Vivo 1901/API30 and test incoming/outgoing SIM calls, default role grant/removal, answer/reject/end, hold/resume, speaker/earpiece/Bluetooth, notification denial, lock screen, process death and reboot.
- Test Android 14+ full-screen intent access and endpoint-routing changes on a second handset.
- Verify encrypted history, retention, deletion, corrupted preference recovery and no call audio/transcript storage.
- Prove that no default path auto-answers and no AI fallback attempts to process SIM-call audio.

Before treating VoIP as complete:

- Build and run the owner-hosted Docker Compose stack; verify WSS TLS, local model loading, Ollama health, coturn authentication, NAT and relay-only WebRTC media.
- Add an Android self-managed `ConnectionService` / Core-Telecom wrapper only if system-integrated VoIP presentation is needed; current UI is app-owned.
- Measure latency, jitter, packet loss, AEC, VAD, barge-in, battery, thermal and memory behaviour.
- Test English, Bengali, Hindi and mixed-language conversations using local models only.
- Verify all participant consent, retention, deletion and owner-approval flows.
- Verify no ordinary PSTN number is represented as reachable unless a real owner-approved carrier route has been configured.

## 7. Official Android references

- [Build a default phone application](https://developer.android.com/develop/connectivity/telecom/dialer-app)
- [InCallService](https://developer.android.com/reference/android/telecom/InCallService)
- [Build a default phone application](https://developer.android.com/develop/connectivity/telecom/dialer-app)
- [Android roles](https://source.android.com/docs/core/permissions/android-roles)
- [Android 14 full-screen intent limits](https://source.android.com/docs/core/permissions/fsi-limits)
- [Call](https://developer.android.com/reference/android/telecom/Call)
- [CallScreeningService](https://developer.android.com/reference/android/telecom/CallScreeningService)
- [TelecomManager.placeCall](https://developer.android.com/reference/android/telecom/TelecomManager#placeCall(android.net.Uri,android.os.Bundle))
- [Manifest permissions — CAPTURE_AUDIO_OUTPUT](https://developer.android.com/reference/android/Manifest.permission#CAPTURE_AUDIO_OUTPUT)
- [Digital Personal Data Protection Act, 2023](https://www.meity.gov.in/static/uploads/2024/06/2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf)
- [DPDP Rules and phased commencement notifications](https://www.meity.gov.in/documents/act-and-policies/digital-personal-data-protection-rules-2025-gDOxUjMtQWa)
- [Foreground-service background restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android Telecom overview](https://developer.android.com/develop/connectivity/telecom)

### Follow-on hardening — source-only increment (24 November 2026)

The current working copy adds the following corrections after the initial phone/VoIP implementation:

- Android 14+ `InCallService` endpoint callbacks and registry adapters are annotated for API 34; Android 8–13 retain the guarded legacy route fallback only.
- If a VoIP call has neither a retained summary nor transcript, its notification now says that no transcript/summary is available instead of incorrectly claiming a transcript exists.
- The VoIP foreground-service start path reserves the single-call slot before starting the service. A duplicate start no longer stops the foreground service belonging to an already active call.
- The audio service now terminates the new session if Android refuses communication audio focus; it does not silently continue with an unmanaged audio mode.
- On Android 13+, the UI requires notification permission before microphone permission and foreground-service creation, making the ongoing microphone-use indicator an explicit prerequisite.
- Server-side Piper child processes are terminated on timeout/cancellation, guarding the process-kill race. If the caller closes the audio track before VAD observes its trailing-silence boundary, a sufficiently long final utterance is queued for processing.
- The greeting now identifies the owner's self-hosted server as the speech-processing location and names the encrypted AURA call history as the proposed transcript-retention destination.
- Partial consent (such as "summary only") is treated as ambiguous, not as permission to store both transcript and summary.
- Whisper language-identification probability is kept separate from segment-quality heuristics; low `avg_logprob`, high `no_speech_prob`, or unusually high compression ratio can request a repeat, but these diagnostics are not presented as calibrated word-confidence probabilities.

These are source changes and static checks only. The Android app has not been compiled after them, and live media/audio behaviour has not been tested. The server still requires owner-provisioned local Whisper, Ollama and Piper models; there is no external fallback.
