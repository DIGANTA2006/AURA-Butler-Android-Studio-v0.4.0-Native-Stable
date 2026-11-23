# AURA v0.17.3 — Voice Continuity Upgrade

## Scope

This PR delivers the first implementation slice of the requested voice upgrade. It builds on the existing sherpa-onnx wake-word, offline Whisper, owner-voice guard, Android TTS session and foreground service. It does not claim that the complete Alexa/Google-style full-duplex voice specification is finished.

## Changes

- Added `VoiceCommandClassifier` for exact stop/pause/resume/stop-listening phrases and conservative detection of obvious incomplete utterances.
- Added `SpeechContinuationStack`, a synchronized six-entry LIFO stack for interrupted responses.
- Integrated validation into normal wake-command handling and wake-triggered barge-in handling.
- When a new command interrupts speech, AURA queues the unfinished response, answers the new command, then resumes queued speech. Nested interruptions resume newest-first.
- Explicit stop/cancel clears continuations. Pause preserves the remaining speech until continue/resume.
- Added unit tests for command controls, incomplete command fragments, accepted complete commands, continuation ordering, capacity and clearing.
- Added GitHub Actions Android CI for unit tests, lint and debug assembly.
- Updated Android versionCode/versionName and README current-build identification.

## Current interaction contract

The current service still requires its configured wake phrase to begin a barge-in capture. After the wake phrase, it stops the active Android TTS utterance, prompts for the new command, performs a bounded recording, optionally verifies the enrolled owner voice according to the existing setting, and transcribes with offline Whisper.

This PR improves what happens after that command is captured:
1. Exact speech controls are handled before normal command routing.
2. Obvious incomplete fragments are not sent to the action engine.
3. A complete follow-up command is processed.
4. The new answer completes before the suspended answer resumes.
5. A pause holds the continuation queue; stop clears it.

## Known limitations / not claimed complete

- Saying only “stop” during TTS, without first saying the wake phrase, is not yet guaranteed to interrupt; the active trigger is still wake-word detection.
- The current microphone path is not yet verified as true full-duplex streaming ASR.
- AEC/noise-suppression quality has not been measured on the target phone.
- Android TTS progress callbacks may be unavailable or coarse; the existing speaker estimates position when needed, so exact word-perfect resume is not guaranteed on every TTS engine.
- The new completeness analyser is intentionally conservative and does not replace intent-specific slot validation or ASR confidence calibration.
- Bengali/Hindi natural-language voice-control phrases are not yet comprehensively classified.
- Owner voice authentication remains governed by the existing opt-in setting; this PR does not change enrollment defaults.
- Physical-device validation has not been performed as part of this PR.

## Validation

Automated validation is configured in `.github/workflows/android-ci.yml`:
- `./gradlew testDebugUnitTest`
- `./gradlew lintDebug`
- `./gradlew assembleDebug`

Report CI results separately from real-device audio acceptance. A successful APK build is not evidence of acoustic interruption reliability.
