# AURA v0.17.1 Voice Reliability Hotfix

This hotfix is based on the exact v0.17.0 source collected on 16 Aug 2026.

## Fixes
- Safer wake-word pronunciation set:
  - removes dangerous broad single-word variants such as `OR A`, `O R A`, and `OUR A`
  - keeps useful Aura / Hey Aura / OK Aura variants
  - retunes the remaining phrases instead of blindly lowering every threshold
- Wake noise control:
  - reduces aggressive amplification of very quiet room noise
  - requires recent real acoustic energy before accepting a KWS trigger
- Owner voice reliability:
  - profile schema v2: old weak enrollment must be registered again
  - 5 enrollment samples instead of 3
  - minimum owner sample length increased
  - noise-floor / voiced-duration / clipping quality gate
  - trims excess silence before speaker embedding
  - normalizes speaker sample level
  - enrollment consistency requirement raised
  - effective owner threshold cannot fall below 0.60
  - clearer rejection reason shown in UI/notification
- Version:
  - versionCode 26
  - versionName 0.17.1-voice-reliability

## Important
After installing the APK as an UPDATE:
1. Open AURA Settings.
2. Register Owner Voice again. The old v0.16/v0.17 voice profile is intentionally not trusted.
3. Enroll in a quiet room at normal phone distance.
4. For daily use, "Hey Aura" should be the most robust phrase in noisy rooms.
5. Sensitive actions must still keep AURA's normal confirmation layer.

This hotfix does NOT:
- install or require Ollama
- deploy or change Modal
- change Qwen/Gemma
- change News API
- change Whisper/Piper
- upgrade Sherpa-ONNX from v1.13.4 (that belongs in the controlled v0.18 voice-runtime test)
