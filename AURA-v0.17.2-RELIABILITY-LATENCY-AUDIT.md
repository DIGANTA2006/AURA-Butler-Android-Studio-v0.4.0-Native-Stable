# AURA v0.17.2 — Reliability + Latency Upgrade

Target Android version:
- versionCode: 27
- versionName: 0.17.2-reliability-latency

This upgrade is baseline-aware and accepts the exact v0.17.0 full-share source, the exact v0.17.1 voice-hotfix state, a partially applied v0.17.1 voice hotfix, or an already-patched v0.17.2 file.

## Repairs included

### P0 foreground-service crash
Repairs the confirmed Android crash where WakeWordService was launched by startForegroundService() but could take an early return before completing the foreground-service handshake.

### Voice reliability
Preserves/integrates the v0.17.1 voice changes: safer Aura keyword variants, five owner-enrollment samples, profile schema v2, speaker sample quality gates, a minimum owner threshold of 0.60, and safer low-level wake audio handling. Sherpa-ONNX remains 1.13.4 in this reliability release.

### Response latency
Adds one total turn deadline and phase timing instead of allowing routing, research and provider waits to stack without a useful overall bound. It reduces research/provider timeouts and prevents a duplicate non-stream request after visible streaming output already arrived.

### Privacy/provider routing
Automatic AI mode uses private Qwen first. Public Gemini/Groq fallback is OFF by default. Public AI is used only when explicitly selected or when the owner enables the public-fallback switch.

### Student-budget behavior
Uses reserve/commit/release for the Android budget ledger, removes stale user-facing "laptop Ollama" fallback messages, shows the daily reset in local time, and serializes cloud budget mutation with a short-lived lock.

### News follow-up
"Explain headline 1" resolves encrypted recent-news state first and can use normal private-Qwen TEXT budget instead of needlessly spending Deep Research.

### Pending action safety
Pending Butler actions gain creation/expiry timestamps. Expired actions are purged and cannot be approved later out of context.

### Chat UX
Streaming auto-scroll reacts to a growing streamed response only when the user is already near the bottom and not manually scrolling. Bottom padding is increased.

### Proactive alerts
Important-message voice alerts gain TTL, deduplication and a bounded scheduled queue.

### QA/release
Adds an Android instrumentation regression test for the foreground-service contract. The updater builds unit tests, lint, debug and release variants.

### Cloud reproducibility
Pins known-good versions observed in the existing AURA Modal build logs:
torch 2.13.0, transformers 4.57.6, accelerate 1.14.0, bitsandbytes 0.50.1, huggingface-hub 0.36.2, fastapi 0.141.1, pillow 11.3.0, pypdf 6.16.1, modal 1.5.3, grpclib 0.4.9, httpx 0.28.1, beautifulsoup4 4.15.0 and trafilatura 2.2.0.

### Cloud source hygiene
After successful validation, old active-looking aura_models backup files are moved, not deleted, into archive/legacy-model-sources/<timestamp>. Use -KeepLegacyCloudSources to keep them in place.

## Not changed
- No Ollama installation.
- No giant laptop LLM download.
- No Qwen/Gemma model replacement.
- No News API removal.
- No Whisper/Piper replacement.
- No Sherpa native-runtime upgrade.
- No Git push.

## Optional cloud warm time
The existing student-budget warm window is preserved unless -CloudWarmSeconds is explicitly supplied. A longer warm window may reduce repeated cold starts but can increase cloud cost.
