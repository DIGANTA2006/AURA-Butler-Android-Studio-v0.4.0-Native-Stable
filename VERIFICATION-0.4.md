# AURA Butler 0.4 Verification

Verification date: 2026-07-28

## Crash evidence reviewed

The supplied Logcat shows native `SIGABRT` on a `DefaultDispatch` worker but does
not include Android's native tombstone/backtrace. Therefore the exact failing C++
function cannot be proven from that excerpt alone.

The source did contain a concrete unsafe lifecycle: `Service.onDestroy()` could
release the shared native `KeywordSpotter` while the `Dispatchers.IO` listening
coroutine was still creating/using its stream. The service also retained the KWS
model while loading Whisper and Piper, increasing native memory pressure.

## Repairs verified in source

- Keyword spotter ownership/release is confined to one worker thread.
- KWS is released before command transcription.
- Whisper is created and released per transcription.
- Piper native weights are allocated only while speaking and freed afterwards.
- Native stage recovery prevents automatic repeated crash loops.
- Service null-intent/sticky restart respects saved wake settings.
- Manual capture and reply speech interrupt AudioRecord without freeing native
  objects from a second thread.
- Microphone read failures are bounded and logged.
- Whisper language selection supports auto/en/bn/hi.
- Knowledge prompts now include teach/how/why/where/when/describe/meaning forms.
- Settings show the voice pack name from `VOICE_NAME.txt`.

## Checks completed

1. Domain source compiled with Kotlin and executed logic checks for:
   - `Teach me about photosynthesis` -> topic `photosynthesis`
   - `How does a solar cell work?` -> topic `solar cell`
2. Modified voice runtime and wake service compiled against:
   - Android API compatibility stubs
   - the actual bundled `sherpa-onnx-v1.13.4.aar` class interfaces
3. Android manifest and XML resources parsed successfully.
4. Bundled AAR and generated ZIP integrity were checked.
5. Required KWS, Whisper and Piper asset files were checked for presence and
   non-zero size.

## Full Gradle build limitation

`./gradlew testDebugUnitTest` was attempted, but this isolated environment has no
Android SDK and no cached Gradle 8.13 distribution. The wrapper could not fetch
`services.gradle.org` because container DNS/network access is disabled. No APK is
claimed as built or device-tested here.

Use Android Studio or `Build-And-Install-AURA.ps1` on the Windows development
computer to run the real Gradle unit tests, assemble the APK and install it.

## Male voice

The included source initially retains the desktop-compatible Lessac model. Run
`Install-HFC-Male-Voice.ps1` to install the official sherpa-onnx converted HFC
Male medium pack before building. The resulting app speaks with that model fully
offline and does not require a voice API.
