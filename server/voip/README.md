# AURA self-hosted AI VoIP endpoint

This is a separate Android AURA app-to-owner-hosted AI WebRTC endpoint. It is **not** a cellular-call bridge and it does not call or connect to the PSTN. It answers a WebRTC session from the Android AURA app using local speech recognition, a local language model and local voice synthesis. No Twilio, PSTN gateway, paid telephony API, paid ASR/TTS API, or third-party AI service is included.

## Data flow

1. The Android app connects to `wss://YOUR_DOMAIN/ws/signaling` with a per-device bearer token stored in Android Keystore-backed preferences.
2. The server verifies the token and returns a short-lived TURN credential (HMAC-SHA1 REST credential) for the owner's coturn server.
3. Android forces `iceTransportsType=RELAY`; media traverses the configured owner-operated TURN service. WebRTC uses DTLS-SRTP for encrypted media.
4. The server runs VAD, faster-whisper ASR, Ollama local inference, and Piper local TTS. It separates Whisper's language-identification probability from segment-quality heuristics (`avg_logprob`, `no_speech_prob`, `compression_ratio`); the latter are conservative repeat prompts, not calibrated word-confidence percentages. It never sends call audio to an external AI provider.
5. Transcript and generated summary events are sent back over WSS. The Android app saves them in its encrypted local call-history store after the call ends.

The server holds audio frames and conversation context in memory only. It does not write recordings or raw audio to disk. Before retaining a transcript or summary, AURA discloses local speech processing and asks for explicit yes/no consent in English, Bengali or Hindi. Ambiguous or partial requests (such as "summary only") fail closed and trigger one repeat question; after a second unclear answer the service automatically disables transcript retention for that call. If the caller declines or consent remains unclear, the session continues without sending or saving transcript text or a summary; only call metadata is saved on the device. Temporary Piper WAV files are created under `/tmp` and removed when synthesis completes. The AI container runs as an unprivileged UID with a read-only root filesystem, a bounded private `/tmp`, and no-new-privileges. Configure host-level swap/core-dump policy appropriately if your threat model requires minimizing remnants of process memory.

## Prerequisites

- Owner-controlled Linux host or self-managed server with Docker Engine and Compose plugin.
- A domain you control, public DNS to the host and inbound ports 80/443, 3478 TCP/UDP, 5349 TCP and 49160–49200 UDP. A local LAN deployment can use a private DNS name and a certificate trusted by the Android device, but the app requires WSS and certificate validation.
- Owner-selected local faster-whisper model files in `models/whisper/` (no model download is performed by this service).
- Three Piper ONNX voices and their `.onnx.json` companion files in `models/tts/`: an English, Bengali and Hindi voice. Verify the voice model license and redistribution terms individually.
- A local Ollama model already present in its persistent volume. The example uses `qwen2.5:3b`; the compose stack does not auto-pull it, and the AI service now checks `/api/show` at startup and refuses readiness when that exact model is missing.
- A TLS certificate and private key for coturn in `certs/fullchain.pem` and `certs/privkey.pem`; Caddy obtains/renews the WSS certificate, but coturn needs its own mounted copy or separately provisioned certificate. Never commit private keys.

## Provisioning

1. Copy `.env.example` to `.env`, then replace all placeholders. Create a unique device token with:

   ```bash
   python3 create_device_token.py android-main
   ```

   Put the generated value in `AURA_DEVICE_TOKENS_JSON` and enter the same value in AURA's Native Phone → AURA Independent AI Call settings. Use a separate random token for every device. Generate `TURN_SHARED_SECRET` independently with `openssl rand -hex 32`. Do not re-use the device token as the TURN secret.

2. Put the local ASR model under `models/whisper/`. Do not put an arbitrary Hugging Face model repository name in the environment; this runtime requires an already downloaded, local CTranslate2 model directory.

3. Put three licensed Piper `.onnx` voices plus their companion JSON files in `models/tts/`. Use the actual full path mapping in `AURA_PIPER_MODELS_JSON`. The default mapping in `.env.example` is illustrative and is not a supplied model.

4. Configure `AURA_DOMAIN`, `TURN_PUBLIC_HOST`, `TURN_EXTERNAL_IP`, `TURN_REALM`, and port forwarding/firewall rules. `TURN_PUBLIC_HOST` is the DNS host/IP used in TURN URLs; `TURN_EXTERNAL_IP` is the coturn public/private mapping. The compose file assigns coturn the static private container IP `172.30.240.10`; the example therefore uses `PUBLIC_IP/172.30.240.10`. Replace the subnet and both addresses if `172.30.240.0/24` overlaps your LAN/VPN, and keep the static container IP and mapping in sync. The coturn container must be the service using that static address; do not set the public/private pair to the Docker host's unrelated LAN address. The public side must be the IPv4 address reachable by the Android phone. Provision the coturn TLS certificate and key. Verify that `TURN_SHARED_SECRET` exactly matches the secret passed to coturn.

5. Start Ollama and explicitly pull a model you have reviewed and accepted under its own license. Example:

   ```bash
   docker compose up -d ollama
   docker exec -it aura-ollama ollama pull qwen2.5:3b
   ```

   This is a one-time owner-initiated model-weight download, not a paid API or an AI request relay. Once model weights are present, the Ollama service can operate without external inference. Do not expose port 11434 publicly.

6. Start the stack:

   ```bash
   docker compose up -d --build
   docker compose logs -f aura-voip
   ```

7. Confirm `https://YOUR_DOMAIN/health` reports `ready`, then configure `wss://YOUR_DOMAIN/ws/signaling` in AURA and use the provisioned client ID/token.

## Supported protocol

- Auth: `Authorization: Bearer <device-token>` and `X-AURA-Client: <client-id>` headers.
- First WebSocket message: `join` with `room`, `client_id`, `role=caller`, `preferred_language=auto|en|bn|hi`.
- Server response: `joined` containing short-lived owner TURN ICE servers.
- Client: `offer` with SDP after local ICE gathering.
- Server: `answer` with SDP. ICE candidates are bundled in SDP; trickle signalling is not used in this first protocol version.
- Transcript events are emitted only after the caller explicitly consents to saving a written transcript/summary. The initial AI greeting asks consent; the consent response is parsed by a deterministic language-specific helper, not by the LLM. A refusal or a second ambiguous answer prevents transcript events and summary generation. The client saves only metadata when retention is disabled. Summary event: `call_summary`; end event: `call_ended`.
- Client ends with `hangup`. Room identifiers are ephemeral and only one caller is permitted per room.

## Safety and capability boundaries

- This server cannot answer, record, listen to, inject speech into, or control a SIM/cellular call. Android Telecom remains a separate module.
- It can receive and speak audio only inside this explicitly initiated AURA WebRTC session. Android microphone permission and POST_NOTIFICATIONS permission on Android 13+ are requested before starting, so Android can show the ongoing microphone foreground-service notification.
- The AI may collect caller identity and call purpose in the conversation, but it has no tools to call numbers, make bookings, disclose owner data, send messages or authorize payments. It never claims an appointment was booked. AURA saves a transcript and local summary only after affirmative caller consent; the owner must perform/approve follow-up actions separately.
- Only English, Bengali and Hindi TTS voice paths are configured. ASR is multilingual via the locally provisioned Whisper model. Accent quality and Bengali/Hindi output quality depend on the selected model weights and must be tested; code presence is not language acceptance.
- No automatic reconnection is attempted after a terminal WebSocket or ICE failure. A transient ICE disconnect is given a short recovery window; if it does not recover, the app closes the session and reports that a new call is required.
- This version supports an Android AURA app calling the owner's local AI endpoint. It is not yet a general-purpose room relay between two human AURA clients.

## Infrastructure and cost

No vendor platform subscription or per-minute fee is required. The owner still supplies electricity, storage, network access and optionally a domain/host. A rented VPS may cost money; running on existing owner-controlled hardware avoids a recurring hosting fee but requires reachability and firewall configuration. Calling ordinary telephone numbers remains outside scope and requires carrier/PSTN service, which has unavoidable costs.
