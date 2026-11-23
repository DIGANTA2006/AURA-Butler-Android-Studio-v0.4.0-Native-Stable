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
