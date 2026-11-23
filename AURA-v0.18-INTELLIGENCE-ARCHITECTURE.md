# AURA v0.18 Intelligence Architecture

## Overview

This release introduces a sophisticated multi-layered intelligence architecture that transforms AURA from a reactive command executor into a proactive, reasoning-capable personal assistant with long-term memory and autonomous planning capabilities.

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                    EnhancedButlerEngine                         │
│              (Integration & Backward Compatibility)             │
└─────────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌───────────────┐    ┌───────────────┐    ┌───────────────┐
│  Intelligence │    │   Semantic    │    │   Proactive   │
│  Coordinator  │◄───│    Memory     │───►│    Engine     │
└───────┬───────┘    └───────────────┘    └───────┬───────┘
        │                                         │
        ▼                                         ▼
┌───────────────┐                         ┌───────────────┐
│  Reasoning    │                         │  Background   │
│  Engine       │                         │  Agents       │
│  (CoT)        │                         │  (Scheduled)  │
└───────┬───────┘                         └───────────────┘
        │
        ▼
┌───────────────┐
│  Task Planner │
│  (Multi-step) │
└───────────────┘
```

## Core Components

### 1. Reasoning Engine (`ReasoningEngine.kt`)

**Chain-of-Thought Reasoning with Tool Use**

- **Structured reasoning traces** with typed steps: OBSERVATION, ANALYSIS, PLANNING, TOOL_USE, REFLECTION, CONCLUSION
- **Tool registry** with 12+ built-in tools (weather, news, timer, reminders, device control, web search, etc.)
- **Configurable reasoning modes**: FAST, STANDARD, DEEP, REFLECTIVE
- **Self-reflection** capability for answer verification
- **Streaming support** for real-time reasoning display

**Key Features:**
- Automatic tool selection based on query keywords
- Structured output parsing with JSON schema validation
- Confidence scoring for reasoning quality
- Extensible tool system for custom capabilities

### 2. Task Planner (`TaskPlanner.kt`)

**Multi-Step Autonomous Planning & Execution**

- **Goal decomposition** into executable tasks with dependencies
- **Execution engine** with parallel task support and retry logic
- **Plan persistence** with JSON serialization
- **Rollback support** for failed executions
- **Progress tracking** and real-time status updates

**Task Types Supported:**
- TOOL_CALL: Execute registered reasoning tools
- USER_ACTION: Request user confirmation/approval
- WAIT: Time-based delays
- CONDITIONAL: Branch based on previous results
- PARALLEL_GROUP: Concurrent task execution
- LOOP: Iterative task execution

### 3. Semantic Memory (`SemanticMemory.kt`)

**Long-Term Memory with Embeddings**

- **Vector embeddings** for semantic similarity search (384-dim local, 1536-dim remote)
- **Rich metadata**: entities, temporal tags, sentiment, confidence
- **Multiple retrieval strategies**: semantic, hybrid, temporal, entity-based, importance-weighted
- **Automatic consolidation**: duplicate merging, pattern extraction, concept formation
- **Forgetting curve**: importance-weighted decay with configurable half-lives
- **Memory sources**: USER_INPUT, AI_GENERATED, TOOL_RESULT, OBSERVATION, CONSOLIDATED

**Storage:**
- Local pseudo-embeddings (deterministic hash-based) for offline operation
- Remote embedding provider support (OpenAI-compatible)
- ConcurrentHashMap-based storage with access tracking

### 4. Proactive Engine (`ProactiveEngine.kt`)

**Anticipatory Intelligence & Background Agents**

**Suggestion Types:**
- REMINDER: Time-based reminders
- FOLLOW_UP: Conversation continuity
- PREPARATION: Upcoming event preparation
- OPTIMIZATION: Better workflow suggestions
- INSIGHT: Pattern discovery
- MAINTENANCE: Device health alerts
- LEARNING: Knowledge acquisition offers
- AUTOMATION: Repetitive task automation

**Built-in Background Agents:**
1. **Daily Briefing** (7 AM cron): Weather, schedule, priorities
2. **Memory Consolidation** (daily): Pattern extraction, forgetting curve
3. **Battery Optimization** (hourly): Low battery alerts
4. **Storage Monitor** (daily): Low storage warnings
5. **Habit Learning** (weekly): Automation opportunities
6. **Research Follow-up** (daily): Topic update checks

**Scheduling:** Hourly, daily, weekly, cron expressions, event-triggered

### 5. Intelligence Coordinator (`IntelligenceCoordinator.kt`)

**Unified Intelligence Orchestration**

- **Four intelligence modes**: MINIMAL, BALANCED, ENHANCED, AUTONOMOUS
- **Automatic strategy selection** based on query analysis
- **Seamless integration** with existing LocalButlerEngine
- **Context enhancement** with relevant memories
- **Cross-component coordination** (reasoning → planning → memory → proactive)

**Processing Strategies:**
- LOCAL_COMMAND: Simple device commands (open app, set timer, etc.)
- AI_REASONING: Complex questions needing analysis
- PLAN_AND_EXECUTE: Multi-step goals requiring planning
- AUTONOMOUS: Full autonomous execution with learning

## Integration with Existing AURA

### Backward Compatibility

The `EnhancedButlerEngine` wraps the existing `LocalButlerEngine` and:

1. **Preserves all existing local commands** (open app, weather, timers, reminders, etc.)
2. **Maintains identical ButlerReply format** for UI compatibility
3. **Uses intelligence only when beneficial** (complex queries, planning requests)
4. **Falls back gracefully** if intelligence components fail

### Configuration

Intelligence features are controlled via `ButlerSettings`:

```kotlin
// Settings that control intelligence
aiEnabled: Boolean                    // Master AI toggle
proactiveSuggestionsEnabled: Boolean  // Proactive engine
proactiveAutoExecute: Boolean         // Auto-execute high-confidence actions
intelligenceMode: IntelligenceMode    // MINIMAL/BALANCED/ENHANCED/AUTONOMOUS
```

### Initialization

```kotlin
// In your Application or Activity
val coordinator = IntelligenceCoordinator(
    aiProvider = hybridAiProvider,
    settings = butlerSettings,
    contextProvider = object : IntelligenceCoordinator.ContextProvider {
        override fun getCurrentContext(): ConversationContext = currentContext
        override fun getDeviceState(): ProactiveEngine.DeviceState = getDeviceState()
    }
)

// Initialize (async)
coroutineScope.launch { coordinator.initialize() }

// Use enhanced engine
val enhancedEngine = EnhancedButlerEngine.create(
    liveInfo = liveInfoProvider,
    generalAi = hybridAiProvider,
    settingsProvider = { butlerSettings },
    contextProvider = contextProvider
)

// Chat with intelligence
val reply = enhancedEngine.chat("Plan my week and set up reminders for each meeting", context)
```

## Intelligence Modes

| Mode | Reasoning | Planning | Memory | Proactive | Auto-Execute | Use Case |
|------|-----------|----------|--------|-----------|--------------|----------|
| MINIMAL | ❌ | ❌ | Basic | ❌ | ❌ | Battery saving, privacy |
| BALANCED | ✅ | On-demand | ✅ | On-demand | ❌ | Default daily use |
| ENHANCED | ✅ Deep | ✅ | ✅ Full | ✅ | Confirmation | Power users |
| AUTONOMOUS | ✅ Reflective | ✅ Full | ✅ Full | ✅ Full | ✅ High-confidence | Hands-free operation |

## Performance Considerations

### Local-First Design

- **Embeddings**: Local pseudo-embeddings work completely offline
- **Reasoning**: Can use local LLM (Ollama/LM Studio) via HybridAiProvider
- **Memory**: All storage on-device, encrypted via existing SecureStore
- **Agents**: Run on background threads, respect battery optimization

### Resource Usage

| Component | Memory | CPU | Network |
|-----------|--------|-----|---------|
| Reasoning Engine | ~2MB | Low (during reasoning) | Only for AI calls |
| Task Planner | ~1MB | Low | Only for tool execution |
| Semantic Memory | ~5-20MB | Low (embedding gen) | Optional remote embeddings |
| Proactive Engine | ~2MB | Periodic (agents) | Only for weather/news tools |
| **Total** | **~10-25MB** | **Low** | **Configurable** |

## Security & Privacy

1. **No data leaves device** without explicit user consent
2. **Encrypted memory storage** via existing Android Keystore-backed SecureStore
3. **Secret redaction** before any AI processing (API keys, passwords, PII)
4. **Prompt injection guards** on all external data
5. **Local-only crash journal** for debugging
6. **User controls**: Clear memory, disable intelligence, delete suggestions

## Testing

### Unit Tests (Add to existing test suite)

```kotlin
// ReasoningEngineTest.kt
@Test fun testChainOfThoughtReasoning()
@Test fun testToolSelection()
@Test fun testReflectionMode()

// TaskPlannerTest.kt
@Test fun testPlanCreation()
@Test fun testParallelExecution()
@Test fun testRollbackOnFailure()

// SemanticMemoryTest.kt
@Test fun testEmbeddingSimilarity()
@Test fun testMemoryConsolidation()
@Test fun testForgettingCurve()

// ProactiveEngineTest.kt
@Test fun testSuggestionGeneration()
@Test fun testBackgroundAgents()
@Test fun testHabitDetection()

// IntelligenceCoordinatorTest.kt
@Test fun testStrategySelection()
@Test fun testModeSwitching()
@Test fun testIntegrationWithLocalEngine()
```

### Integration Tests

```kotlin
// EnhancedButlerEngineIntegrationTest.kt
@Test fun testLocalCommandStillWorks()
@Test fun testComplexQueryUsesReasoning()
@Test fun testPlanningRequestCreatesPlan()
@Test fun testProactiveSuggestionsAppear()
@Test fun testMemoryPersistsAcrossSessions()
```

## Migration Guide

### From v0.17 to v0.18

1. **No breaking changes** - LocalButlerEngine unchanged
2. **Opt-in intelligence** - Enable via settings
3. **New dependencies** - Kotlinx Coroutines, JSON (already in project)
4. **Asset files** - Add embedding model to `assets/embeddings/` for local embeddings

### Enabling Intelligence Features

```xml
<!-- In settings UI -->
<SwitchPreferenceCompat
    android:key="ai_enabled"
    android:title="Enable AI Intelligence"
    android:summary="Chain-of-thought reasoning, planning, and proactive suggestions" />

<ListPreference
    android:key="intelligence_mode"
    android:title="Intelligence Mode"
    android:entries="@array/intelligence_modes"
    android:entryValues="@array/intelligence_mode_values"
    android:defaultValue="balanced" />
```

```xml
<!-- res/values/arrays.xml -->
<string-array name="intelligence_modes">
    <item>Minimal (Local Only)</item>
    <item>Balanced (Recommended)</item>
    <item>Enhanced (Full Reasoning)</item>
    <item>Autonomous (Hands-Free)</item>
</string-array>
<string-array name="intelligence_mode_values">
    <item>minimal</item>
    <item>balanced</item>
    <item>enhanced</item>
    <item>autonomous</item>
</string-array>
```

## Future Extensions (v0.19+)

1. **Multimodal Reasoning**: Image + text reasoning for screen analysis
2. **Collaborative Planning**: Multi-user plan sharing
3. **Knowledge Graph**: Entity relationship extraction from memories
4. **Learning from Feedback**: RLHF-style preference learning
5. **Plugin System**: Third-party tool/agent extensions
6. **Federated Memory**: Encrypted sync across devices
7. **Voice-First Reasoning**: Streaming reasoning with barge-in support

## Files Added

```
android/app/src/main/java/in/diganta/aurabutler/intelligence/
├── ReasoningEngine.kt          # Chain-of-thought reasoning with tools
├── TaskPlanner.kt              # Multi-step planning and execution
├── ProactiveEngine.kt          # Proactive suggestions & background agents
├── IntelligenceCoordinator.kt  # Unified orchestration
└── (package-info.java)

android/app/src/main/java/in/diganta/aurabutler/memory/
└── SemanticMemory.kt           # Embeddings-based long-term memory

android/app/src/main/java/in/diganta/aurabutler/domain/
└── EnhancedButlerEngine.kt     # Integration layer with backward compatibility
```

## Verification Checklist

- [ ] All unit tests pass (`./gradlew testDebugUnitTest`)
- [ ] Lint passes (`./gradlew lintDebug`)
- [ ] Debug build succeeds (`./gradlew assembleDebug`)
- [ ] Local commands still work (open app, timer, weather, etc.)
- [ ] Complex questions trigger reasoning engine
- [ ] Planning requests create executable plans
- [ ] Memories persist and retrieve semantically
- [ ] Proactive suggestions appear contextually
- [ ] Background agents run on schedule
- [ ] Intelligence modes switch correctly
- [ ] Memory consolidation runs without errors
- [ ] No regression in existing functionality

---

*This architecture maintains AURA's core principles: local-first, privacy-preserving, user-controlled, and transparent about AI usage.*
