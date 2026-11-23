# Security model

## Protections

- No cloud-AI key or device token is hard-coded in the app. Optional endpoint
  URLs are configuration defaults; bearer tokens remain in encrypted settings.
- Chat, settings, memory, reminders, and pending actions are encrypted with an
  Android Keystore-backed key.
- Android backup is disabled.
- Wake and speech models run locally.
- External actions are typed, displayed, approved, and validated immediately
  before execution.
- Web actions allow HTTPS only and reject embedded URL credentials.
- Calls use the dialer; messages use a composer; calendar/alarm actions use
  Android’s trusted UI.
- The microphone background service is visible and can be stopped from its
  notification.
- External AI calls have local daily limits and independent server-side limits.
  Laptop Ollama through localhost/ADB reverse is not charged against the cloud
  counters.

## Consequential actions

Do not weaken the confirmation boundary by automatically pressing checkout,
book, buy, send, call, cancel, or payment buttons. Do not use an accessibility
service to imitate user taps or bypass OTP/authentication prompts.

Provider integrations should use official APIs, verify returned status, and
retain a final confirmation for money, communication, account, or travel
changes.

## Model and dependency integrity

The project vendors sherpa-onnx 1.13.4 and selected official model files. Source
and model provenance are recorded in `THIRD_PARTY_NOTICES.md`. Before publishing
a release, record SHA-256 hashes for the APK and bundled model assets.
