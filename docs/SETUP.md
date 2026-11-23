# Setup on the Vivo Y15

## 1. Open the correct folder

In Android Studio choose **File → Open** and select:

```text
AURA-Butler\android
```

Do not start a backend and do not paste a token. The old PowerShell filename is
kept only as a harmless notice; the offline edition starts entirely from the
Android app.

## 2. Wait for Gradle

Let the status bar finish both **Gradle Sync** and indexing. The first sync may
take several minutes. The project includes its native speech library and voice
models, so there is no model download during the first app launch.

## 3. Connect the phone

1. On the Vivo, enable Developer options.
2. Enable USB debugging.
3. Connect the phone by USB and choose a data/file-transfer USB mode.
4. Accept the **Allow USB debugging** prompt on the phone.
5. In Android Studio, select **vivo 1901 API 30**.
6. Press **Run ▶** beside the `app` configuration.

The app is large because Whisper, Piper, and their native runtime are inside the
APK. Installation can take longer than an ordinary app.

## 4. Enable private voice

On first launch:

1. Allow the microphone.
2. Open **Settings** in AURA.
3. Leave **Speak replies** on.
4. Raise the phone’s **media volume** to at least 50%.
5. Press **Test AURA voice** and wait; the first local sentence can take several
   seconds on the Vivo.
6. Turn on **Wake when I say “Aura”**.
7. Choose **Auto** for mixed English/Bengali/Hindi speech.
8. Press **Save on-device settings**.

Android shows a permanent low-priority notification while wake listening is
active. This is required for microphone access on Android 11. Do not hide or
force-stop it if you want “Aura” to work with the screen off.

## 5. Vivo battery setting

Funtouch OS names vary, but use the closest available route:

```text
Settings → Battery → Background power consumption management → AURA → Allow
```

Also enable **Auto start** or **Allow background activity** for AURA if those
options appear. If wake listening stops after a few minutes, this battery rule
is the first setting to check.

## 6. Test in this order

Type these first:

```text
What time is it?
Remember that I prefer window seats.
What do you remember?
Tell me the weather at home.
Remind me in 20 minutes to drink water.
Calculate (12 + 8) * 3.
Open YouTube.
Tell me my phone status.
Schedule doctor tomorrow at 3 PM.
```

Then test the microphone button. Finally turn on wake listening and say:

```text
Aura
```

Wait for the short tone, then say:

```text
Tell me the latest India headlines.
```

For a complex command:

```text
Aura, tell me the weather and then remind me in one hour to call home.
```

## Troubleshooting

### “Aura” does not wake

- Confirm the AURA wake-listening notification is visible.
- Reopen AURA and save the wake switch again.
- Check microphone permission.
- Set Vivo battery/background use to Unrestricted/Allow.
- Say **Aura** as a clear two-syllable word, then wait for the tone.

### Microphone opens but no text appears

- Speak closer to the phone in a quiet room.
- Keep the command under 25 seconds.
- Wait after speaking; Whisper runs locally and an older Vivo may need several
  seconds to transcribe.

### Male voice does not play

- Open AURA **Settings** and press **Test AURA voice**.
- Keep **media** volume above zero; ringtone volume does not control AURA.
- Disable Bluetooth temporarily to check the phone speaker.
- Version 0.4 routes Piper as 16-bit media audio for Vivo/Android 11
  compatibility.
- The app falls back to an installed offline Android English voice if Piper
  cannot start.

### Weather or news fails

Live weather, news, factual summaries, maps, searches, media, and provider pages
require internet. Voice, memory, reminders, alarms, timers, calculator, and
basic phone controls remain local. Check Wi-Fi/mobile data and try again.

### “App not installed” while upgrading

An Android Studio debug build on your PC can have a different signing key from
the ready-made APK. Either run the updated source from Android Studio, or
uninstall the old AURA before installing the ready-made APK. Uninstalling clears
that installation’s saved memory.

### Android Studio tries an x86 emulator

This package is optimized for the connected arm64 Vivo. Select **vivo 1901**,
not an x86 virtual device.
