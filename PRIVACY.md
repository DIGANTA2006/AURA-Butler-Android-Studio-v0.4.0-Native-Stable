# Privacy

AURA’s durable personal state stays on the Android phone.

## Stored locally

- preferred name, form of address, and home area/coordinates;
- voice and wake-listening settings;
- up to 60 chat messages;
- up to 100 preference memories;
- reminders, pending approval cards, and action history.

These values use Android Keystore-backed encrypted preferences. Android app
backup is disabled.

## Voice data

Wake-word audio and command audio are processed in memory by bundled local
models. AURA does not upload recordings or save audio files.

## Network use

Most core features work offline. For a requested weather place, news topic, or
short factual answer, AURA contacts a key-free HTTPS weather, RSS, Wikipedia, or
DuckDuckGo endpoint. It sends only that requested place/topic/question. It does
not send chat history, saved memories, contact data, or recordings.

Opening maps, media, food, travel, shopping, WhatsApp, or web search passes only
the displayed and approved destination, message, or query to the chosen
app/site.

## Never store

Do not give AURA passwords, OTPs, PINs, card numbers, CVVs, private keys,
government identity numbers, or medical secrets. The local engine refuses
obvious secret-memory requests.

## User controls

- Clear conversation history from the Butler screen.
- Delete individual memories.
- Cancel reminders.
- Turn spoken replies or wake listening off.
- Stop wake listening from its notification.
- Clear everything through **Android Settings → Apps → AURA → Storage → Clear
  data**, or uninstall AURA.
