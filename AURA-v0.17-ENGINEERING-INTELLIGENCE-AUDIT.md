# AURA v0.17 Engineering Intelligence — Upgrade Audit

## Scope

This release deliberately improves AURA's *engineering system* around the existing private Qwen3 8B model. It does not claim to turn an 8B model into a frontier-scale model and it does not deploy or cold-start Modal.

## Added now

### Repository-aware project analysis

A new deterministic `RepositoryIntelligence` layer runs on the phone before a selected project ZIP is sent to private Qwen.

It extracts and ranks:

- source/config/entrypoint/test roles,
- declared classes/functions/symbols,
- import/reference relationships,
- source-to-test affinity,
- prompt/path/content relevance,
- architecture/config spine,
- directory/language coverage.

The selected project snapshot now contains a synthetic:

`__AURA__/PROJECT_MAP.md`

inside a normal `===== FILE: ... =====` block. The existing private cloud project compactor therefore keeps the repository map inside model evidence instead of losing it during context reduction.

This is deterministic repository navigation metadata, not a learned embedding model.

### Better private memory retrieval

`HybridRetrievalScorer` now prepares query features once per search and combines:

- token F1,
- character trigram similarity,
- ordered word bigrams,
- long/specific term coverage,
- phrase boost,
- recency.

This remains fully on-device and adds no model/API cost.

### Stronger engineering contract

Private Qwen is instructed to work repository-first and distinguish:

- verified defects,
- hypotheses,
- patch plan,
- code/diff,
- compatibility review,
- verification commands.

It is explicitly forbidden from claiming tests/commands ran without evidence.

### Portable private streaming

Low-latency SSE is no longer enabled only for `.modal.run` hostnames. AURA attempts streaming on any safe owner-configured OpenAI-compatible endpoint and falls back to the hardened non-streaming path if streaming is unsupported.

Complex engineering/detailed turns still use the non-streaming hidden-thinking/review path.

### Better engineering continuity

Private engineering turns keep up to 10 recent messages rather than 6, and the private system prompt can receive up to 12 already-filtered relevant memories.

### Startup reduction

Heavy orchestration experts in `ProductionButlerEngine` are initialized lazily instead of all being constructed when the engine object is created.

## Not added yet

The following still require a later stage and/or more budget/hardware:

- a real arbitrary-code execution sandbox,
- full Gradle/Android compilation inside the phone,
- learned semantic embedding model,
- a larger 14B/32B+ reasoning/coding model,
- bank-grade anti-spoof voice biometrics.

AURA must not claim those capabilities until they actually exist.

## Cost impact

- Android source-only upgrade.
- No new permanent cloud service.
- No Modal deployment.
- No Qwen/Gemma cold-start required by the installer.
- No new Android model download.
- Project analysis continues to use the existing private Qwen text budget when the owner explicitly attaches a project.

## Validation boundaries

The installer runs:

- JVM unit tests,
- Android lintDebug,
- debug APK build.

`Test-AURA-AutoQA-v11.ps1 -Full -SkipCloud` validates the new static wiring plus existing Android/device/runtime checks without consuming Modal GPU budget.

Repository selection algorithms were additionally written as pure Kotlin and can be unit tested independently, but the final authority remains the user's real Gradle build and physical-device QA.
