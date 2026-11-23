# AURA v0.15 Professional Coding / Architecture Audit

This upgrade was produced from the 2026-08-16 full-share project snapshot.

## High-impact defects corrected

- Removed the response formatter that deleted `*`, `_` and backticks from generated code.
- Prevented command splitting from breaking multiline code/logs on semicolons and newlines.
- Private Qwen now receives bounded recent conversation history **and relevant encrypted on-device retrieval memory** by default; external providers still require private-context opt-in.
- Document/project memories now reject likely real credentials instead of rejecting harmless programming vocabulary such as `api key` or `authorization`.
- Migrated stale Gemini defaults to `gemini-3.5-flash-lite` and `gemini-3.6-flash`.
- Groq quality fallback uses `openai/gpt-oss-120b`; fast fallback uses `openai/gpt-oss-20b`.
- Increased engineering context/output budgets and preserved fenced code in the UI.
- Complex private-Qwen coding requests now use a second senior-engineering verifier pass before the final answer.
- Added safe client-side compaction of large ZIP software projects (up to 300 MB compressed) into a bounded source snapshot before upload.
- Added server-side project snapshot selection that parses file blocks instead of truncating the beginning of a large snapshot, preserving relevant cross-file source evidence rather than spending the model context on the manifest.
- Added server-side project analysis with a senior-engineering prompt, exact-file evidence, and project-specific runtime markers.
- Replaced broad keyword-based secret blocking with high-confidence credential detection so ordinary code discussing API keys is not disabled.
- Removed retired “Master Diganta” defaults and vendor-ownership identity wording.
- Fixed runtime-locale formatter caching and Compose display-metric configuration invalidation found by Android lint.
- Routes natural live/current questions that ask for sources or detailed verification to the private ResearchGateway even when the owner does not explicitly type a `research` prefix.
- Replaced the misleading `On-device butler • private` subtitle with `Private-first butler • local + private cloud` so the UI matches the actual runtime architecture.

## Professional reasoning boundary

AURA's private Qwen3 8B can be made substantially more reliable through better context, project retrieval, deterministic tools, hidden reasoning and a verifier pass, but an 8B model cannot be transformed into a frontier-scale model merely by prompts or lightweight local training. For the hardest coding tasks, the optional current Gemini/Groq fallbacks remain the quality-escalation path when the owner enables them.

## Project-analysis behavior

For a large ZIP project, AURA now performs a bounded phone-side source scan, skips build/cache/vendor folders, ranks text/source files against the owner's prompt, and sends a compact private project snapshot to Qwen. The cloud re-ranks source blocks and includes architecture/config files plus prompt-relevant files within a bounded evidence window. Returned evidence chunks are stored in encrypted on-device memory so follow-up questions can retrieve relevant project context without requiring external-provider memory sharing.

## Validation required on the user's machine

The upgrade installer runs JVM unit tests, Android lint, debug APK build, Python syntax checks and Modal deployment/health checks. AutoQA v5 additionally verifies Qwen streaming, the engineering verifier pass, project-document analysis, agent parameter preservation, Gemma OCR and deep research with fetched pages. Hardware behaviors (wake-word acoustics, long-idle OEM survival, MediaProjection consent and real communications) still require device acceptance because Android deliberately gates them.
