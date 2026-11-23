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
