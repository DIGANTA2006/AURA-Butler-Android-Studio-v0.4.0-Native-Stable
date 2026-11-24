# Verification

Verified on 28 July 2026:

- `compileDebugKotlin`: passed
- `testDebugUnitTest`: 17 passed, 0 failed
- `lintDebug`: passed with no errors
- `assembleDebug`: passed
- APK Signature Scheme v2 verification: passed
- APK package: `in.diganta.aurabutler`
- Version: `0.4.0-vivo` (`versionCode 3`)
- Minimum Android: API 26
- Target Android SDK: API 36
- Native ABI: `arm64-v8a`
- Cleartext network traffic: disabled

The APK contains the keyword detector, multilingual Whisper Tiny models, Piper
Lessac model, eSpeak NG data, and the required arm64 sherpa-onnx runtime.

The tests cover encrypted-memory intent handling, rejection of obvious secrets,
reminder/calendar preparation, live-weather routing, multi-command parsing,
phone controls, safe arithmetic, local identity handling, and external action
URL safety.

Real-device behavior still depends on microphone quality, available storage,
media volume, network access for live weather/news, and Vivo/Funtouch OS battery
management.


## 24 November 2026 — current working-copy verification checkpoint

The historical 28 July 2026 baseline above is retained as historical evidence. It does not verify the PR #3 feature branch or the telephone additions.

Current PR #3 source checkpoint inspected: `a5da8783466e2a46076a2dca9532529e471e6e82`; PR #3 is open, draft and unmerged. The last checked CI run `45319787869` compiled Kotlin and ran 182 tests (181 passed, one proactive lifecycle test failed); lint and debug APK tasks were skipped. The test's contradictory description wording was corrected in the local working copy without changing its assertions. No fresh CI run is available for that patch.

Additional local checks for the telephone source: Python `compileall` passed, twenty pure standard-library security/privacy/ASR-quality tests passed (`test_aura_security.py`, `test_aura_privacy.py` and `test_aura_asr_quality.py`), Docker Compose YAML parsed with PyYAML, and AndroidManifest.xml parsed as XML. Consent parsing is deterministic and fails closed; ambiguous replies are asked once again, then transcript retention is disabled. The AI container had an import-packaging defect; the working copy now copies `server.py`, `aura_security.py`, `aura_privacy.py` and `aura_asr_quality.py`. The compose services reference pinned Ollama, coturn and Caddy tags. No Docker/Podman CLI, Gradle 8.13 distribution, Android SDK, adb or Vivo handset was available. Thus Docker build, Android compilation/tests/lint/APK, real local model inference, network media and physical-device behavior remain unverified.


### Follow-on hardening checks (24 November 2026)

The same 20 Python security/privacy/ASR-quality tests still pass after the server-side process-cleanup and final-audio-segment changes. Static validation also passed for Android manifest XML, Docker Compose YAML, Dockerfile helper packaging, absence of protected cellular-audio/call-log permissions and named paid telephony integrations in the new phone module, metadata-only notification fallback wording, and API 34 callback annotations. These static checks are not a substitute for Kotlin compilation, Android lint, APK builds, Docker startup or device acceptance.


### CI coverage addition

The working copy's Android CI workflow now defines an independent `voip_backend` job using Python 3.12. It compiles `server/voip` and runs the pure security and transcript-consent unit tests. This job is source-only and does not install model weights or call a paid service. It has not run on GitHub because the working copy has not been pushed.


The local Whisper quality gate now treats language-identification probability separately from segment text diagnostics. `avg_logprob`, `no_speech_prob` and `compression_ratio` are used only as conservative repeat heuristics, not calibrated confidence. Six independent unit tests cover this logic.
