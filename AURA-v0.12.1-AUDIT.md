# AURA v0.12.1 Audit

Audit basis: full shared Android + Modal source package dated 2026-08-15.

## Verified in source / prior successful build

- Android v0.12 source had already passed `testDebugUnitTest` and `assembleDebug` in the owner’s Windows environment.
- All 28 `ActionKind` values have execution/routing references and safety validation.
- All 42 `ButlerSettings` fields are persisted by `SecureStore`.
- Qwen3 8B, Gemma 3 4B and ResearchGateway endpoints are present and authenticated with the private AURA bearer token.
- Screen capture requires explicit Android MediaProjection approval.
- Notification history is locally encrypted and bounded.
- Wake word + local Whisper + adaptive VAD are implemented.
- English/Bengali/Hindi speech routing is implemented.
- Deep research includes query planning, private SearXNG, source ranking, web/PDF extraction, prompt-injection isolation, citations and an additional verification pass in Deep/Exhaustive modes.

## Corrections applied in 0.12.1

1. **Gemma long cold-start fix** — private vision now follows only trusted Modal 303 result redirects, matching Qwen/research behavior.
2. **Qwen hidden-reasoning boundary** — Qwen3 thinking blocks are parsed out at token level on Modal; Android strips `<think>...</think>` again as defense in depth.
3. **Qwen thinking generation settings** — thinking mode uses Qwen’s recommended sampling profile (`temperature 0.6`, `top_p 0.95`, `top_k 20`).
4. **Research fetch memory/network bound** — arbitrary source bodies are streamed into a fixed maximum rather than being fully downloaded before the size check.
5. **Version/user-agent consistency** — app version is `0.12.1-audited-stable`; active provider user-agent labels are aligned.
6. **Tests** — response quality tests now cover complete and unfinished Qwen thinking blocks.
7. **Documentation** — README now describes the current dual-model/deep-research architecture instead of the old 0.4 build.

## Known platform / operational boundaries (not source defects)

- Modal GPU scale-to-zero means the first Qwen/Gemma request after idle can still be slower than a warm request.
- Deep research may take substantially longer than normal chat because it performs multiple searches, fetches, synthesis and optionally verification.
- Android/OEM background restrictions can stop microphone wake listening after force-stop, reboot or aggressive battery management; open AURA and re-enable as needed.
- Screen sharing cannot be silently approved by AURA; Android intentionally asks the user.
- Notification summaries only include notifications captured while notification access is enabled.
- Song identification delegates to an installed system/Google song-identification experience.
- `EncryptedSharedPreferences`/`MasterKey` still work but AndroidX marks them deprecated. A future data-store migration should be done separately and transactionally so existing API keys/settings are not lost.

## Physical-device acceptance tests still required

Run these after installing 0.12.1:

1. “Who created you?”
2. Normal Qwen question, then repeat within 3 minutes to compare cold/warm latency.
3. User-approved screenshot analysis through Gemma.
4. Standard and Deep research with cited sources.
5. Bengali and Hindi speech input/output.
6. Wake “Aura” and “Hey Aura” in quiet, fan noise and 1–3 metre distances.
7. Notification summary after granting notification access and receiving a new Gmail/WhatsApp notification.
8. Flashlight, volume, brightness, alarm, timer, reminder, app launch, maps, email/message draft.
9. Lock screen/background wake test for 30–60 minutes and an OEM battery-optimization test.
10. Laptop fully off: Qwen, Gemma and research should still work over mobile data/Wi-Fi because the deployed Modal service is independent of the laptop.
