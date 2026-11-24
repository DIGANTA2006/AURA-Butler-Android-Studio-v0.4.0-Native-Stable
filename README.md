# AURA Butler 0.17.3 Voice Continuity + Agent + RAG for Android

AURA is Diganta's private Android personal assistant for arm64 phones such as
Vivo 1901. Version 0.14 keeps the stable offline voice foundation and adds a cost-bounded expert-router, streamed private Qwen replies, explicit document attachment/RAG, and a safer delayed screen-capture workflow.

AURA is **not in mock mode**. Local commands do not need an OpenAI key, a Node
backend, or a cloud AI account. The following components run on the phone:

- “Aura” / “Hey Aura” wake-word detection with sherpa-onnx
- Whisper Tiny multilingual speech recognition
- Piper offline reply speech
- encrypted profile, chat history, memory, notes and reminders
- alarms, timers, calculator and supported Android phone commands
- the deterministic AURA command and safety engine

Internet is used only when current public data or an external service is needed:

- Open-Meteo weather
- Google News RSS headlines
- Wikipedia and DuckDuckGo short factual lookups
- approved Google searches, Maps and provider pages
- food, shopping, travel, media and ticket search pages

Those public endpoints can change or rate-limit access; they are not a general AI
model. Unsupported reasoning requests are offered as an approval-gated web search
instead of AURA pretending to know the answer.

## Version 0.14 architecture upgrade

- Chat now has an **Attach file** control using Android's system document picker.
- PDF/text/code documents are sent only after explicit selection to the owner-authenticated private Qwen endpoint; bounded document chunks are then stored in encrypted local memory for later retrieval.
- Local memory retrieval combines token overlap, typo-tolerant character n-grams and recency. It is a lightweight private retrieval index, not a claim of learned embeddings.
- Qwen normal replies can stream token text into the chat bubble while generation is still running. Detailed/thinking mode stays non-streaming so hidden reasoning is removed before display.
- Multi-step requests can use a bounded Qwen planner that routes 2-5 commands to deterministic local expert modules. Qwen proposes; Android executes and retains the existing approval/safety boundary.
- Screen analysis now starts the MediaProjection foreground session, moves AURA behind the target app, waits about five seconds, then captures exactly one approved frame. This makes switching screens practical during Android Studio mirroring.
- The v0.12.1 audited fixes remain merged: trusted Modal 303 handling for Gemma, Qwen hidden-thinking removal, recommended Qwen thinking sampling, and streaming-bounded research downloads.

The expert router is **MoE-inspired at the assistant-system level**: different specialists handle device actions, communication, memory, live information, vision and research. It does not pretend that an 8B model has been converted into the proprietary internal MoE architecture of a trillion-parameter model.

## Version 0.13 student-budget architecture

- Local commands, encrypted memory, reminders, voice, weather and keyless news
  are always tried before any external model.
- Laptop Ollama through ADB reverse is treated as a free local endpoint.
- Optional Qwen3 8B text, Gemma 3 4B vision and private research are metered by
  independent daily counters on the phone and again on the server.
- Say or type `AI budget status` to see today's phone-side counters.
- The Modal deployment uses one scale-to-zero T4 container per model, a short
  warm window and strict request/output caps. No GPU stays running while idle.
- Model/provider interfaces remain replaceable. Larger models, embeddings,
  document RAG and a sandbox can be added later without replacing the Android
  voice, memory or safety layers.

The defaults are intentionally conservative: 12 external text requests, 6
vision requests, 2 research requests and 4 small helper requests per UTC day.
Rerunning the v0.13 PowerShell upgrade with different limits rebuilds those
owner-controlled caps.

## Version 0.4 crash repair

The previous build could keep wake-word recognition, Whisper and Piper native
models alive together. It could also release the keyword spotter from Android's
main service thread while a `Dispatchers.IO` thread was decoding audio. On some
phones this could terminate the process with native `SIGABRT`.

Version 0.4 changes the ownership model:

1. The wake keyword model is created, used and released on the same worker thread.
2. It is released before Whisper is loaded.
3. Whisper is one-shot and is released immediately after transcription.
4. Piper allocates native weights only while speaking and frees them afterwards.
5. A small stage marker detects an interrupted native operation and disables wake
   listening once, preventing a repeated crash loop.
6. Microphone read failures now stop with a visible diagnostic rather than spin.

No audio recording or transcript is written by the diagnostic marker.

## Voice note

The package retains Piper `en_US-lessac-medium` because it is the same offline
voice configured by the desktop AURA project. That bundled voice should not be
mislabelled as male. If Piper cannot load, Android Text-to-Speech is used and AURA
prefers an installed offline English male voice when the phone provides one.

For a guaranteed offline male voice, run `Install-HFC-Male-Voice.ps1` on the
Windows development computer before building. It backs up Lessac, downloads the
official sherpa-onnx converted `en_US-hfc_male-medium` pack, validates its
structure, installs the model/tokens/eSpeak data, and optionally runs the tests
and debug build. This is a one-time build-time download; the installed Android
app then speaks offline and needs no voice API.

## Zero-paid-cost telephone Butler (native SIM + self-hosted AURA AI VoIP)

AURA's telephone architecture has a strict no-paid-third-party-telephony policy: no Twilio, Plivo, Vonage, Telnyx, Vapi, Retell, paid calling gateway, paid STT/TTS API or paid AI-calling subscription. Native SIM calls use the owner's existing carrier plan.

The PR #3 working copy includes Android Telecom dialer-role handling, `InCallService`, owner-confirmed SIM dialing, manual answer/reject/end, capability-aware hold/resume and audio routing, incoming/ongoing notifications, and encrypted local call metadata history. The source is not yet Android-build- or Vivo-device-verified. Automatic answering remains disabled; spoken answer/reject voice commands are not yet connected. Emergency calls remain with Android's preloaded system dialer.

The working copy also includes a separate app-to-owner-hosted-local-AI WebRTC caller. It uses authenticated WSS signalling, owner-operated coturn relay-only media, and locally provisioned faster-whisper, Ollama and Piper models. Transcript/summary retention is opt-in and fails closed when consent is unclear. A caller who declines can continue without transcript/summary retention. Android 13+ notification permission and microphone permission are requested before creating the foreground microphone service; communication audio-focus failure ends the session. This VoIP path is not a SIM audio bridge, PSTN gateway, or ordinary phone-number dialer; Docker, model and handset acceptance are still pending.

**Cellular audio limitation:** an ordinary third-party app—even as default dialer—does not receive supported two-way SIM-call PCM or an API to inject AI speech into the carrier uplink. AURA does not record, secretly capture, transcribe or inject speech into SIM calls. Manual owner action controls native calls; no SIM call is represented as an AI conversation.

A separate **Android AURA app → owner-hosted local AI endpoint** WebRTC path is now implemented in source: WSS device-token authentication, short-lived credentials for the owner's coturn relay, relay-only DTLS-SRTP audio, local faster-whisper ASR, local Ollama responses, local Piper English/Bengali/Hindi TTS, bounded in-memory conversation context, and encrypted on-device transcript/summary history. The endpoint is not a PSTN gateway or a general human-to-human AURA room. The Docker deployment and model weights must be provisioned by the owner; no models are automatically downloaded by the service. The local Python security/privacy/ASR-quality suite passes 20 tests and a separate Python CI job has been added to the working copy. That job has not run remotely because the changes have not been pushed. Android Gradle build, container build/runtime, actual model inference and physical-device media tests remain unverified.

Calling ordinary mobile/landline numbers still requires a carrier-backed route and normal charges; no free PSTN bridge is claimed. See [the telephone architecture and verification gates](docs/AURA-ZERO-PAID-TELEPHONE-BUTLER.md).

## What AURA can do

- Remember non-sensitive preferences and notes in encrypted local storage
- Read, remove and use its own saved memory
- Create reminders, alarms, timers and calendar drafts
- Open installed apps and Android settings
- Prepare calls, SMS, WhatsApp and email with user review
- Control supported media volume, brightness and flashlight operations
- Open camera, music, maps, food, travel and shopping searches
- Calculate locally and report date, time and device status
- Answer weather, news and many factual/teaching questions through keyless public
  sources; teaching prompts such as “Teach me about photosynthesis” and “How does
  a solar cell work?” are now recognized

Android does not allow an ordinary third-party app to silently control every
other app, bypass confirmations, complete payments, or book services without the
provider's own interface. AURA opens trusted screens and leaves final approval to
the user.

## Upgrade/install and test

1. Prefer an in-place install (`adb install -r`) so encrypted settings and local
   memory stay on the phone. The v0.13 upgrade script uses this mode. Uninstall
   only to recover from an incompatible legacy/mock package, after accepting
   that Android will erase that package's private data.
2. For the requested male voice, right-click `Install-HFC-Male-Voice.ps1`,
   choose **Run with PowerShell**, and allow it to finish.
3. Open the `android` folder in Android Studio.
4. Let Gradle Sync finish and select the physical arm64 Android phone.
5. Run the debug build.
6. Allow microphone and notification permissions.
7. Open Settings, keep **Speak replies** enabled, raise **media volume**, and
   press **Test AURA voice**.
8. Test the microphone button with wake listening off.
9. Enable **Wake when I say “Aura”**, save, keep the foreground notification,
   then say “Aura”, wait for the tone, and speak.
10. On Vivo/Funtouch OS, allow Auto start/background activity and set battery use
   to Unrestricted; otherwise Android may stop the service when the screen is off.

If voice still aborts, capture Logcat with filters `AuraWakeService`,
`AuraVoiceManager`, `libc`, `DEBUG`, and `AndroidRuntime`, including the tombstone
lines immediately following `Fatal signal 6`.

## Project layout

```text
AURA-Butler-Android-Studio-v0.4.0-Native-Stable/
├── android/                 Android Studio project and bundled models
├── docs/
├── CHANGELOG-0.4.md
├── PRIVACY.md
├── SECURITY.md
└── THIRD_PARTY_NOTICES.md
```

## Target

- Minimum Android: 8.0 / API 26
- Compile/target SDK: 36
- ABI: `arm64-v8a`
- Java/Kotlin target: 17
