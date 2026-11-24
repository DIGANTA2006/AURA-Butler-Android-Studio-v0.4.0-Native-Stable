# Third-party components and model licensing

This folder does not include third-party model weights. Before distributing a deployment or voice pack, verify each model's individual license, attribution and redistribution conditions.

| Component | Version | License | Scope |
|---|---:|---|---|
| aiortc | 1.16.0 | BSD-3-Clause | Python WebRTC endpoint |
| FastAPI | 0.165.0 | MIT | WSS/health server |
| Uvicorn | 0.54.0 | BSD-3-Clause | ASGI server |
| faster-whisper | 1.2.1 | MIT | Local ASR runtime |
| webrtcvad-wheels | 2.0.14.post1 | MIT | Voice activity detection |
| Piper TTS | 1.8.0 | GPL-3.0-or-later | Isolated self-hosted TTS runtime; not bundled into Android APK |
| Android WebRTC SDK | 150.7871.08 | BSD-3-Clause | Android audio transport |
| OkHttp | 5.5.1 | Apache-2.0 | Android WSS signalling |
| Ollama | owner-selected image | MIT (project code) | Local model runner; model weights have a separate license |
| coturn | owner-selected image | BSD-3-Clause | Owner-operated TURN relay |
| Caddy | owner-selected image | Apache-2.0 | WSS TLS reverse proxy |

The server is an isolated deployment. Piper's GPL license obligations must be reviewed and met when redistributing the server image/source. It is not copied into the proprietary/Android app package and the Android side uses only the published WebRTC transport library.
