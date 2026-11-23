# AURA 0.13 student architecture

## What is included now

| Layer | Implementation | Cost behavior |
| --- | --- | --- |
| Wake, speech input and reply voice | sherpa-onnx keyword spotting, Whisper Tiny, Piper/Android TTS | On phone; no per-use fee |
| Daily assistant actions | Deterministic intent router plus typed Android actions and confirmation cards | On phone; no per-use fee |
| Personal memory | Keystore-backed encrypted local memory with bounded retrieval | On phone; no per-use fee |
| Current basic facts | Open-Meteo, Google News RSS, Wikipedia and approved web screens | Keyless where available |
| Private text reasoning | Qwen3 8B, 4-bit, OpenAI-compatible endpoint | Optional T4, scale to zero, capped |
| Private screen/image analysis | Gemma 3 4B, 4-bit | Optional T4, scale to zero, capped |
| Deep research | Private SearXNG fetch plus Qwen planning/synthesis | Optional, two jobs/day by default |
| Laptop fallback | Ollama `qwen3:4b` through ADB reverse | Uses the owner's laptop; not cloud-metered |

The Android and server limits are intentionally independent. The phone prevents
accidental overuse; the server still protects the deployment if another client
obtains the endpoint. A limit of zero means unlimited.

## Why this is the correct budget boundary

Transformer layers, MoE routing, tokenizers, RLHF/GRPO, KV cache and quantization
belong inside the selected model and inference runtime. Reimplementing or
training them is not an assistant feature and is far beyond a student budget.
AURA instead uses open-weight models and invests the project code in privacy,
orchestration, memory, typed tools, voice, safe actions and cost control.

As of 15 August 2026, Modal lists T4 at $0.000164/second and L4 at
$0.000222/second, with $30/month compute included on Starter. Pricing can change;
check https://modal.com/pricing before raising limits. Scale-to-zero means idle
GPU time is not kept running, but model cold start and the short warm window are
still billable compute.

## Deliberately deferred modules

- PDF/document RAG with embeddings and an owner-managed document lifecycle
- Sandboxed code execution with filesystem/network limits
- Contact/calendar reading after recipient resolution and Android permissions
- Smart-home adapters with per-device capability permissions
- Signed release/Play distribution and physical-device instrumentation tests
- Newer Gemma/Qwen models after measuring memory, latency, quality and cost
- Fine-tuning/LoRA only after a real evaluation set shows RAG and prompting are
  insufficient

These are extension modules, not rewrites. The `AiProvider`, typed
`ButlerAction`, memory, analyzer and voice boundaries remain stable.
