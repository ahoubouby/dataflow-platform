# dataflow-core

> **Domain Library**: Event-sourced aggregates and pure business logic

---

## 🎯 **Purpose**

**dataflow-core** is a **domain library module** containing pure business logic for the DataFlow Platform. It provides:

- ✅ Domain models (Commands, Events, States)
- ✅ Event-sourced aggregates (PipelineAggregate)
- ✅ Serialization configuration
- ✅ Validation rules

**IMPORTANT**: This is a **library**, not an application!

---

## 🚫 **What This Module Does NOT Contain**

- ❌ **NO cluster dependencies** (no pekko-cluster-typed)
- ❌ **NO Cassandra driver** (only Pekko Persistence API)
- ❌ **NO cluster configuration** (no remote.artery, seed-nodes)
- ❌ **NO Cassandra connection details** (no contact-points, keyspaces)
- ❌ **NO application runtime** (no main method, no HTTP server)

**Why?** This ensures dataflow-core can be used as a library in different contexts without forcing cluster/Cassandra dependencies on consumers.

---

## 🏗️ **Architecture**

### **Dependency Hierarchy**

```
dataflow-api (APPLICATION)
    ├── depends on → dataflow-sources
    ├── depends on → dataflow-transforms
    ├── depends on → dataflow-sinks
    └── depends on → dataflow-core (THIS MODULE)
                        ↑
                        │
            (Pure domain library - no cluster deps)
```

### **Module Structure**

```
dataflow-core/
├── src/main/scala/com/dataflow/
│   ├── domain/
│   │   ├── models/              # Domain models
│   │   │   ├── PipelineConfig.scala
│   │   │   ├── SourceConfig.scala
│   │   │   ├── SinkConfig.scala
│   │   │   ├── TransformConfig.scala
│   │   │   └── Checkpoint.scala
│   │   ├── commands/            # Commands
│   │   │   └── PipelineCommands.scala
│   │   ├── events/              # Events
│   │   │   └── PipelineEvents.scala
│   │   └── state/               # States
│   │       └── PipelineState.scala
│   ├── aggregates/              # Aggregates
│   │   └── PipelineAggregate.scala
│   └── serialization/           # Serialization
│       └── CborSerializable.scala
│
└── src/main/resources/
    └── application.conf         # Library configuration (plugin selection only)
```

---

## 📦 **Dependencies**

**build.sbt** (simplified):

```scala
lazy val dataflowCore = (project in file("dataflow-core"))
  .settings(
    libraryDependencies ++=
      commonDependencies ++
      testDependencies ++
      // ✅ Only Pekko Persistence API (for EventSourcedBehavior)
      Seq(
        "org.apache.pekko" %% "pekko-persistence-typed"     % pekkoVersion,
        "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
        // Testing
        "org.apache.pekko" %% "pekko-persistence-testkit"   % pekkoVersion % Test,
      ) ++
      validationDependencies
  )
```

**Key Points:**
- ❌ **NO** `clusterDependencies` - This is not a clustered application
- ❌ **NO** `persistenceDependencies` - No Cassandra driver
- ✅ **YES** `pekko-persistence-typed` - API for event sourcing
- ✅ **YES** `pekko-serialization-jackson` - Serialization

---

## ⚙️ **Configuration**

**application.conf** (simplified):

```hocon
# ============================================
# DataFlow Platform - Core Library Configuration
# ============================================
# Minimal configuration for the domain library
# The application module (dataflow-api) provides cluster and Cassandra config

dataflow {
  pipeline {
    default-batch-size = 1000
    max-pipelines = 100
    checkpoint-interval = 10 seconds
  }
}

pekko {
  actor {
    # Serialization configuration
    serialization-bindings {
      "com.dataflow.serialization.CborSerializable" = jackson-cbor
    }
  }

  # ✅ Plugin selection ONLY (no connection details)
  persistence {
    journal.plugin = "pekko.persistence.cassandra.journal"
    snapshot-store.plugin = "pekko.persistence.cassandra.snapshot"
  }
}

# ❌ NO cluster configuration
# ❌ NO Cassandra connection details
# ❌ NO remote.artery configuration
```

**What's missing?**
- ❌ `pekko.actor.provider = cluster` - Defined in dataflow-api
- ❌ `pekko.remote.artery` - Defined in dataflow-api
- ❌ `pekko.cluster` - Defined in dataflow-api
- ❌ `datastax-java-driver` - Defined in dataflow-api
- ❌ `pekko.persistence.cassandra` (connection details) - Defined in dataflow-api

**Why?** Configuration is the responsibility of the application layer (dataflow-api).

---

## 🎭 **PipelineAggregate**

The core event-sourced aggregate managing pipeline lifecycle.

### **State Machine**

```
UninitializedState
    ↓ CreatePipeline
ConfiguredState
    ↓ StartPipeline
RunningState
    ↓ PausePipeline
PausedState
    ↓ ResumePipeline
RunningState
    ↓ StopPipeline
StoppedState
    ↓ StartPipeline (resume with checkpoint)
RunningState
```

### **Commands**

```scala
sealed trait Command
case class CreatePipeline(config: PipelineConfig, replyTo: ActorRef[Response]) extends Command
case class StartPipeline(replyTo: ActorRef[Response]) extends Command
case class StopPipeline(reason: StopReason, replyTo: ActorRef[Response]) extends Command
case class PausePipeline(reason: String, replyTo: ActorRef[Response]) extends Command
case class ResumePipeline(replyTo: ActorRef[Response]) extends Command
case class IngestBatch(records: Seq[DataRecord], replyTo: ActorRef[Response]) extends Command
case class UpdateCheckpoint(checkpoint: Checkpoint, replyTo: ActorRef[Response]) extends Command
```

### **Events**

```scala
sealed trait Event extends CborSerializable
case class PipelineCreated(id: String, name: String, config: PipelineConfig, timestamp: Instant) extends Event
case class PipelineStarted(id: String, timestamp: Instant) extends Event
case class PipelineStopped(id: String, reason: StopReason, metrics: PipelineMetrics, timestamp: Instant) extends Event
case class PipelinePaused(id: String, reason: String, timestamp: Instant) extends Event
case class PipelineResumed(id: String, timestamp: Instant) extends Event
case class BatchProcessed(id: String, recordCount: Int, checkpoint: Checkpoint, timestamp: Instant) extends Event
```

### **States**

```scala
sealed trait State
case object UninitializedState extends State
case class ConfiguredState(id: String, name: String, config: PipelineConfig, createdAt: Instant) extends State
case class RunningState(id: String, name: String, config: PipelineConfig, startedAt: Instant, checkpoint: Checkpoint) extends State
case class PausedState(id: String, name: String, config: PipelineConfig, pausedAt: Instant, checkpoint: Checkpoint) extends State
case class StoppedState(id: String, name: String, config: PipelineConfig, stoppedAt: Instant, lastCheckpoint: Checkpoint) extends State
```

---

## 🔒 **Type Safety**

The aggregate uses **pattern matching on (State, Event) tuples** for type-safe state transitions:

```scala
// ❌ OLD (WRONG - unsafe cast)
case PipelineStarted(_, ts) =>
  val cfg = state.asInstanceOf[ConfiguredState]  // Crashes if state is StoppedState!
  RunningState(...)

// ✅ NEW (CORRECT - type-safe pattern matching)
(state, event) match {
  case (cfg: ConfiguredState, PipelineStarted(_, ts)) =>
    RunningState(..., checkpoint = Checkpoint.initial)

  case (stopped: StoppedState, PipelineStarted(_, ts)) =>
    RunningState(..., checkpoint = stopped.lastCheckpoint)  // Resume from checkpoint!

  case (currentState, event) =>
    log.warn("Invalid transition: {} + {}", currentState, event)
    currentState  // Don't crash
}
```

---

## 🧪 **Testing**

### **Unit Tests**

```bash
sbt "project dataflow-core" test
```

Tests use `pekko-persistence-testkit` for in-memory event sourcing:

```scala
class PipelineAggregateSpec extends ScalaTestWithActorTestKit {
  "PipelineAggregate" should {
    "create pipeline from uninitialized state" in {
      val probe = testKit.createTestProbe[Response]()
      val aggregate = testKit.spawn(PipelineAggregate("test-pipeline"))

      aggregate ! CreatePipeline(config, probe.ref)
      probe.expectMessageType[PipelineCreatedResponse]
    }
  }
}
```

---

## 📚 **Usage**

### **As a Library**

Other modules depend on dataflow-core:

```scala
// build.sbt
lazy val dataflowApi = (project in file("dataflow-api"))
  .dependsOn(dataflowCore % "compile->compile;test->test")
  .settings(...)
```

### **Creating an Aggregate**

```scala
import com.dataflow.aggregates.PipelineAggregate
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding

val sharding: ClusterSharding = ClusterSharding(system)

// Initialize sharding for PipelineAggregate
sharding.init(Entity(PipelineAggregate.TypeKey) { entityContext =>
  PipelineAggregate(entityContext.entityId)
})

// Send commands
val pipelineRef = sharding.entityRefFor(PipelineAggregate.TypeKey, "pipeline-123")
pipelineRef ! CreatePipeline(config, replyTo)
```

---

## 🤔 **Why This Architecture?**

### **Problem Without Separation**

If dataflow-core had cluster dependencies:
```scala
// ❌ BAD ARCHITECTURE
lazy val dataflowCore = (project in file("dataflow-core"))
  .settings(
    libraryDependencies ++=
      clusterDependencies ++        // ← Forces cluster on all consumers
      persistenceDependencies ++    // ← Forces Cassandra client on all consumers
```

**Issues:**
- Any module depending on dataflow-core gets unwanted dependencies
- Cannot use domain logic without cluster
- Cannot test without Cassandra
- Circular dependency risk
- Violates Single Responsibility Principle

### **Solution With Separation**

```scala
// ✅ GOOD ARCHITECTURE
lazy val dataflowCore = (project in file("dataflow-core"))
  .settings(
    libraryDependencies ++=
      Seq(
        "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,  // API only
```

**Benefits:**
- ✅ Clean separation of concerns
- ✅ Domain logic is reusable
- ✅ Easy to test (no cluster required)
- ✅ No circular dependencies
- ✅ Follows Hexagonal Architecture

---

## 🔗 **Related Modules**

| Module | Relationship |
|--------|-------------|
| **dataflow-api** | **Application** - Runs cluster, connects to Cassandra, uses dataflow-core |
| **dataflow-sources** | **Library** - Data ingestion, depends on dataflow-core |
| **dataflow-transforms** | **Library** - Data transformation, depends on dataflow-core |
| **dataflow-sinks** | **Library** - Data output, depends on dataflow-core |

---

## 📖 **Key Concepts**

### **Event Sourcing**
State is derived from a sequence of immutable events, not stored directly.

### **CQRS**
Commands change state (write side), Projections query state (read side).

### **Aggregate**
A consistency boundary - all changes go through the aggregate.

### **Type Safety**
Pattern matching on (State, Event) ensures invalid transitions are caught at compile time.

---

## 🚀 **Next Steps**

For **running the application**, see:
- [dataflow-api/README.md](../dataflow-api/README.md) - Application module
- [Main README.md](../README.md) - Quick start guide
- [ARCHITECTURE_AND_ROADMAP.md](../docs/ARCHITECTURE_AND_ROADMAP.md) - Complete architecture

---

**dataflow-core**: Pure domain logic, zero infrastructure dependencies ✨
