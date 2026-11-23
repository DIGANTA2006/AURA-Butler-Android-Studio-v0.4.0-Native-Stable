# AURA 8.2 Android port notes

The uploaded `AURA-8.2-Deep-Audit-Hardened` project was audited before this
Android update. Its 61 Python tests passed in an isolated temporary data
directory.

## What was portable

- The private-first design and explicit approval policy
- Local Whisper speech recognition
- Local Piper speech
- Wake-word activation
- Personal memory, reminders, alarms, time/date, weather, device status, app
  opening, and safe tool routing
- Clear refusal to store passwords, PINs, OTPs, card numbers, or identity
  numbers

## Android replacements

| Desktop AURA 8.2 | Android AURA 0.4 |
|---|---|
| Python services | Kotlin app and foreground microphone service |
| SQLite/Chroma | Encrypted on-device preferences |
| faster-whisper | sherpa-onnx Whisper Tiny int8 |
| Piper process | Bundled Piper ONNX offline voice |
| Porcupine | Bundled local keyword spotter |
| Desktop tools | Approval-gated Android intents and protected-setting flows |
| Web/weather tools | Keyless HTTPS public feeds |

## What was not copied

The desktop program’s optional `llama3.1:8b` Ollama brain is far beyond the
practical memory and sustained compute budget of the Vivo Y15. AURA 0.4
therefore uses a reliable bounded local command engine and offers web lookup for
unsupported questions. It does not silently send private conversation to a
cloud AI service.

Android also intentionally blocks ordinary apps from hidden microphone use,
silent call/message sending, automatic payment, OTP entry, and arbitrary
control of other apps. AURA opens the trusted Android/provider screen and keeps
the final decision with the user.
