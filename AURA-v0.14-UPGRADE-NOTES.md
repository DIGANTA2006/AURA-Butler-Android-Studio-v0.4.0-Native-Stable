# AURA v0.14 upgrade notes

This build merges the user-provided v0.13 Student Agent Core with the previously audited v0.12.1 safety fixes and adds:

1. OpenDocument attachment button in Butler chat.
2. Private PDF/text/code document analysis with Qwen and encrypted local RAG chunks.
3. Hybrid local retrieval (lexical + character n-gram + recency).
4. SSE streaming for normal private Qwen chat.
5. Bounded multi-step Qwen planner that dispatches to deterministic Android expert modules.
6. Five-second screen-capture handoff so the user can switch screens after MediaProjection approval.
7. App moves behind the target task during one-shot capture, improving Android Studio screen-mirroring usability.
8. Preserved v0.12.1 protections: trusted Modal 303 redirects, hidden Qwen thinking removal, Qwen thinking sampling, and bounded research fetch streaming.
9. Student-budget counters remain on phone and Modal; scale-to-zero and one-container ceilings remain.

The expert router is system-level/MoE-inspired orchestration. It does not claim to reproduce proprietary large-model weights or training clusters.
