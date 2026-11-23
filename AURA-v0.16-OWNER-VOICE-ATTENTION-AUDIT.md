# AURA v0.16 — Owner Voice + Attention Layer

## Objective

This release moves AURA closer to a professional personal assistant without increasing the Modal GPU footprint. It concentrates on two owner-specific capabilities and three confirmed reliability defects:

1. **Owner Voice Lock** — spoken commands are executed only after on-device speaker verification against Diganta's enrolled voice profile.
2. **Proactive Important-Message Sentinel** — important WhatsApp, mail, SMS/messaging, Slack and Jira notification previews are classified locally and can be spoken immediately.
3. **Wake state race repair** — an older AI turn can no longer force the runtime to IDLE over a newer LISTENING state.
4. **Wake SecureStore reuse** — WakeWordService reuses one encrypted settings store instead of repeatedly initializing Android Keystore-backed preferences.
5. **Private-memory hard cap** — normal memory inserts now enforce the intended 1,500-record limit.

## Owner Voice Lock

- Uses the existing sherpa-onnx Android runtime already bundled in AURA.
- Adds a local speaker-embedding model under `assets/voice/speaker/`.
- Enrollment records three short microphone samples and stores only their averaged normalized embedding in AURA's encrypted preferences.
- Raw enrollment audio is never written to disk by `OwnerVoiceGuard`.
- Manual microphone commands, wake-word commands and barge-in commands all pass through owner verification when the lock is enabled.
- Wake phrases include **Aura**, **Hey Aura**, and **OK Aura**.
- If a non-owner speaks after a wake trigger, AURA discards the command before Whisper transcription/action routing and resumes wake listening.
- Voice registration temporarily pauses WakeWordService so the persistent wake microphone does not compete with enrollment recording, then restores it.

### Security boundary

Speaker verification is a useful local access gate, not a bank-grade biometric or anti-spoof system. A good recording or synthetic imitation may still fool a speaker model. AURA therefore keeps its existing confirmation and action-safety policies for consequential operations.

## Important-message attention

`AuraNotificationListenerService` already receives user-approved Android notification previews. v0.16 adds a local attention layer after deduplication:

- IMPORTANT/CRITICAL notifications from WhatsApp, mail, messaging/SMS, Slack and Jira can be announced immediately.
- The classifier is local and does not require Gemini/Qwen for every notification.
- Promotional terms lower priority; urgency, deadline, security, call/reply, college, travel and similar phrases increase priority.
- If the phone is locked, the default is privacy-preserving: AURA says app + sender only and asks the owner to check the phone.
- Reading message details while locked is a separate opt-in setting.
- Secret-like text is redacted before speech.
- During an active call, the spoken alert is suppressed while the normal visual AURA alert remains.

## Cost impact

- No new Modal function or GPU is required.
- No Qwen/Gemma cloud redeployment is performed by this installer.
- The speaker model adds about 28.3 MB to Android assets/APK.
- Important-message classification and speaker verification run on-device.

## Current model ceiling

This release improves the *assistant system* rather than pretending Qwen3 8B has become a frontier-scale model. The largest remaining intelligence gap is raw model capacity on very difficult coding/reasoning. The next high-value upgrades, when budget/hardware allows, are repository tools + sandboxed compile/test/repair loops, learned semantic embeddings, and an on-demand larger coding/reasoning model routed only for the hardest tasks.

## Required real-device acceptance tests

1. Register owner voice in Settings using all three samples.
2. Enable `Only accept my enrolled voice` and wake listening.
3. Owner: say `Aura`, then a safe command — command should proceed.
4. Another person: say the wake phrase and a safe command — AURA should reject it and resume listening.
5. Send a test WhatsApp/mail message containing a safe urgency phrase such as `Please call me asap` — AURA should announce it.
6. Lock the phone and repeat — by default AURA should not read the full message body aloud.
7. Screen-off/idle test on Vivo for 30–60 minutes, then repeat the owner wake test.

## Automated verification

`Test-AURA-AutoQA-v9.ps1` checks source wiring, exact speaker-model SHA256, wake keywords, owner-verification paths, memory-cap repair, runtime-state repair, Gradle tests/lint/build, ADB/device health, and existing AURA checks. Acoustic identity accuracy and real notification speech remain manual physical-world tests.
