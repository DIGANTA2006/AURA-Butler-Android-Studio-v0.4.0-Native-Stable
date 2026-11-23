# AURA Butler 0.4 — Native Stable

## Fixed

- Removed cross-thread `KeywordSpotter.release()` from `Service.onDestroy()`.
- Keyword spotter and stream now have single-worker ownership.
- Wake model is released before Whisper and Piper are used.
- Whisper recognizer is created per transcription and explicitly released.
- Piper native model is allocated per utterance and freed afterwards.
- Native stage recovery prevents repeated wake-service crash loops.
- Foreground service handles speech and manual capture without retaining all
  native models simultaneously.
- Added bounded microphone read-error handling and detailed Logcat tags.
- Respects English, Bengali, Hindi or automatic Whisper language selection.
- Corrected misleading “male Lessac” wording; Android offline male TTS remains
  the fallback when available.

## Improved

- Added teaching and broader factual prompt recognition: teach, how, why, where,
  when, describe and meaning questions.
- Factual prefixes are stripped before Wikipedia/DuckDuckGo lookup.
- User-facing settings state that local operation is real, not mock mode.
- Version bumped to `0.4.0-native-stable` / code 4.

## Still bounded

AURA can use keyless public feeds and open provider pages, but a general-purpose
cloud-quality conversational model cannot be obtained “from the internet” with
no API, no hosted model and no local compute cost. Android also prevents silent
cross-app actions and final payments/bookings.
