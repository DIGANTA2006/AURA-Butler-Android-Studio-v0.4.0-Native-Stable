# Roadmap

## Included in 0.4 Vivo

- Local encrypted memory and command engine
- Multilingual Whisper Tiny
- Piper/Lessac offline voice
- “Aura” foreground wake listening
- Vivo-compatible 16-bit media speech with Android TTS fallback
- Named-place weather, topic news, short factual internet answers, device
  status, reminders, alarms, timers, calendar drafts, maps, calls, messages,
  WhatsApp/email composers, installed apps, settings, volume, brightness,
  flashlight, camera, music, calculator, food, travel, shopping, and web search
- Approval cards and action history

## Sensible future work

- Tune the custom “Aura” keyword threshold using recordings from the Vivo Y15
- Add a Bengali-capable local TTS voice while retaining the English butler voice
- Add contact lookup with explicit Android permission and recipient confirmation
- Add calendar reading with explicit permission
- Add an optional local-PC brain using the audited AURA 8.2/Ollama runtime for
  general conversation; keep it disabled by default and never require a cloud
  key
- Add signed release builds and on-device instrumentation tests
- Add owner-selected PDF/document import with local chunking and embeddings
- Add a narrowly sandboxed code runner on the private server; never execute
  generated code directly inside the Android app
- Evaluate Gemma 4/Qwen successors only after measuring quality, cold-start time
  and monthly cost against the current 4-bit models

## Intentionally excluded

- Hidden background microphone recording
- Accessibility-service auto-clicking
- Automatic checkout, payment, OTP entry, call placement, or message sending
- Storing passwords, PINs, OTPs, card data, or identity numbers
- Claiming that a booking/order succeeded without provider confirmation


## 24 November 2026 — PR #3 and zero-paid telephone implementation status

The PR #3 working-copy source now includes native Android Telecom call management and an independent self-hosted AI VoIP endpoint. Native SIM control is owner-manual only and has no cellular audio capture/injection. The VoIP path is Android-app-to-owner-local-AI, using WSS, relay-only WebRTC/coturn, local faster-whisper, local Ollama and local Piper. It is not a PSTN gateway, general human-to-human room, or appointment-calling system.

The VoIP server source includes WSS/WebRTC/local-AI orchestration, TURN credential security and a deterministic English/Bengali/Hindi caller transcript-consent gate. Twenty Python security/privacy/ASR-quality tests pass. The Dockerfile packages all server helpers and Compose image versions are pinned. Model weights, certificates, Docker build/runtime, Android Gradle build and Vivo acceptance remain release gates. PR #3 remains draft/unmerged; do not merge while CI is red or source changes remain only in the local package.


### Follow-on source hardening

- Corrected no-consent/no-transcript post-call notification wording.
- Added API 34 annotations to public CallEndpoint callbacks; retained legacy routing only below API 34.
- Fixed duplicate VoIP start ordering so a second tap cannot stop an existing active call's foreground service.
- Fail closed on communication audio-focus denial.
- Ensure Piper subprocess cleanup and enqueue a qualifying final utterance when the remote audio track ends abruptly.
- Separate Whisper's language-identification probability from segment-quality repeat heuristics; prompt instead of presenting language probability as word confidence.
- Treat partial transcript/summary consent as ambiguous and keep transcript retention disabled unless both items are clearly accepted.

The above remains uncompiled on Android and unverified on the Vivo handset.
