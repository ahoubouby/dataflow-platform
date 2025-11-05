# DataFlow Platform: Architecture, Sharding & Module Interaction

## Table of Contents
1. [System Architecture Overview](#system-architecture-overview)
2. [Cluster Sharding Explained](#cluster-sharding-explained)
3. [Module Interaction](#module-interaction)
4. [Data Flow](#data-flow)
5. [Entry Points](#entry-points)
6. [Complete Example](#complete-example)

---

## System Architecture Overview

```
┌────────────────────────────────────────────────────────────────────────┐
│                         DataFlow Platform                              │
│                                                                        │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐        │
│  │   Node 1   │  │   Node 2   │  │   Node 3   │  │   Node N   │        │
│  │            │  │            │  │            │  │            │        │
│  │ ┌────────┐ │  │ ┌────────┐ │  │ ┌────────┐ │  │ ┌────────┐ │        │
│  │ │Pipeline│ │  │ │Pipeline│ │  │ │Pipeline│ │  │ │Pipeline│ │        │
│  │ │  Shard │ │  │ │  Shard │ │  │ │  Shard │ │  │ │  Shard │ │        │
│  │ └────────┘ │  │ └────────┘ │  │ └────────┘ │  │ └────────┘ │        │
│  │            │  │            │  │            │  │            │        │
│  │ ┌────────┐ │  │ ┌────────┐ │  │ ┌────────┐ │  │ ┌────────┐ │        │
│  │ │  API   │ │  │ │Sources │ │  │ │Transform││  │ │  Sinks │ │        │
│  │ │ Server │ │  │ │Connector││  │ │ Engine  ││  │ │Connector││        │
│  │ └────────┘ │  │ └────────┘ │  │ └────────┘ │  │ └────────┘ │        │
│  └────────────┘  └────────────┘  └────────────┘  └────────────┘        │
│                                                                        │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              Shared Infrastructure                              │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐         │   │
│  │  │Cassandra │  │  Kafka   │  │   ELK    │  │PostgreSQL│         │   │
│  │  │(Events)  │  │(Streams) │  │  (Logs)  │  │(ReadModel)│        │   │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘         │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Cluster Sharding Explained

### What is Cluster Sharding?

Cluster sharding distributes **Pipeline Aggregates** across multiple nodes. Each pipeline is an **entity** that lives on exactly one node at a time.

### Sharding Architecture

```
                    ┌─────────────────────────────────────┐
                    │      Cluster Shard Region          │
                    │   (Manages Pipeline Distribution)   │
                    └─────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
         ┌──────────▼──────────┐       ┌───────────▼──────────┐
         │      Node 1          │       │      Node 2          │
         │                      │       │                      │
         │  ┌────────────────┐ │       │  ┌────────────────┐ │
         │  │ Shard: 0-127   │ │       │  │ Shard: 128-255 │ │
         │  │                │ │       │  │                │ │
         │  │ pipeline-1     │ │       │  │ pipeline-5     │ │
         │  │ pipeline-2     │ │       │  │ pipeline-6     │ │
         │  │ pipeline-3     │ │       │  │ pipeline-7     │ │
         │  │ pipeline-4     │ │       │  │ pipeline-8     │ │
         │  └────────────────┘ │       │  └────────────────┘ │
         └─────────────────────┘       └─────────────────────┘

                        Message Routing
                        ───────────────

    Command for "pipeline-3"  ──────►  Routed to Node 1 (Shard 0-127)
    Command for "pipeline-7"  ──────►  Routed to Node 2 (Shard 128-255)
```

### How Sharding Works

1. **Entity ID**: Each pipeline has a unique ID (e.g., `pipeline-123`)
2. **Hash Function**: ID is hashed to determine shard number (0-255)
3. **Shard Allocation**: Each node owns a range of shards
4. **Message Routing**: Commands are routed to the node owning that shard
5. **Location Transparency**: Clients don't know which node hosts the pipeline

### Code: Setting Up Cluster Sharding

**In dataflow-core module** (the module that handles sharding):

```scala
package com.dataflow.cluster

import com.dataflow.aggregates.PipelineAggregate
import com.dataflow.domain.commands.Command
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope

object PipelineSharding {

  // Define entity type key (namespace for all pipelines)
  val TypeKey: EntityTypeKey[Command] =
    EntityTypeKey[Command]("Pipeline")

  /**
   * Initialize cluster sharding for pipelines.
   * Call this once at application startup.
   *
   * @param system The actor system
   * @return ShardRegion that routes messages to pipelines
   */
  def init(system: ActorSystem[_]): ActorRef[ShardingEnvelope[Command]] = {
    val sharding = ClusterSharding(system)

    // Initialize shard region
    sharding.init(Entity(TypeKey) { entityContext =>
      // Create PipelineAggregate actor for this pipeline ID
      val pipelineId = entityContext.entityId
      PipelineAggregate(pipelineId)
    })
  }

  /**
   * Send a command to a pipeline (sharded).
   *
   * @param shardRegion The shard region (from init)
   * @param pipelineId The target pipeline ID
   * @param command The command to send
   */
  def sendCommand(
    shardRegion: ActorRef[ShardingEnvelope[Command]],
    pipelineId: String,
    command: Command
  ): Unit = {
    // Wrap command in ShardingEnvelope with entity ID
    shardRegion ! ShardingEnvelope(pipelineId, command)
  }
}
```

**Using the Shard Region**:

```scala
// At application startup
val system: ActorSystem[_] = ...
val pipelineShardRegion = PipelineSharding.init(system)

// Send command to pipeline-123 (automatically routed to correct node)
pipelineShardRegion ! ShardingEnvelope(
  "pipeline-123",
  CreatePipeline(...)
)

// Send command to pipeline-456 (may be on different node)
pipelineShardRegion ! ShardingEnvelope(
  "pipeline-456",
  IngestBatch(...)
)
```

---

## Module Interaction

### Module Responsibilities

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Module Structure                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌────────────┐                                                      │
│  │dataflow-api│  HTTP API for users                                 │
│  │            │  - REST endpoints (create/start/stop pipelines)     │
│  │            │  - WebSocket (real-time status)                     │
│  │            │  - Sends commands to dataflow-core                  │
│  └─────┬──────┘                                                      │
│        │ Commands                                                    │
│        ▼                                                             │
│  ┌────────────┐                                                      │
│  │dataflow-   │  Core business logic & persistence                  │
│  │core        │  - PipelineAggregate (event sourcing)               │
│  │            │  - CoordinatorAggregate (registry)                  │
│  │            │  - Cluster Sharding (distribution)                  │
│  │            │  - Events → Cassandra                               │
│  │            │  - Orchestrates sources/transforms/sinks            │
│  └─────┬──────┘                                                      │
│        │ Orchestration                                              │
│        ├──────────┬──────────┬──────────┐                          │
│        ▼          ▼          ▼          ▼                           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐              │
│  │dataflow- │ │dataflow- │ │dataflow- │ │dataflow- │              │
│  │sources   │ │transforms│ │sinks     │ │projections│             │
│  │          │ │          │ │          │ │           │              │
│  │Kafka     │ │Filter    │ │Kafka     │ │Status View│             │
│  │File      │ │Map       │ │Postgres  │ │Metrics    │             │
│  │JDBC      │ │Aggregate │ │Elastic   │ │Audit Log  │             │
│  │REST API  │ │Join      │ │S3        │ │Search     │             │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘              │
│       │            │            │              │                     │
│       └────────────┴────────────┴──────────────┘                    │
│                        │                                             │
│                        ▼                                             │
│              ┌──────────────────┐                                    │
│              │ External Systems │                                    │
│              │ (Kafka, DB, etc) │                                    │
│              └──────────────────┘                                    │
└─────────────────────────────────────────────────────────────────────┘
```

### Detailed Module Dependencies

```
dataflow-api
    └─ depends on ──► dataflow-core

dataflow-core
    └─ is used by ──► dataflow-sources
    └─ is used by ──► dataflow-transforms
    └─ is used by ──► dataflow-sinks
    └─ is used by ──► dataflow-projections

dataflow-sources
    └─ uses ──► dataflow-core (commands/events)

dataflow-transforms
    └─ uses ──► dataflow-core (domain models)

dataflow-sinks
    └─ uses ──► dataflow-core (domain models)

dataflow-projections
    └─ uses ──► dataflow-core (events/read models)
```

---

## Data Flow

### Complete Pipeline Lifecycle

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         Pipeline Lifecycle                                │
└──────────────────────────────────────────────────────────────────────────┘

1️⃣  CREATE PIPELINE (via API)
    ────────────────────────

    User Request (HTTP POST)
         │
         ├──► dataflow-api: POST /api/pipelines
         │         │
         │         ├─ Validate request
         │         │
         │         └─ Send command to core
         │
         ▼
    PipelineShardRegion (dataflow-core)
         │
         ├──► Route to shard for "pipeline-123"
         │
         ▼
    PipelineAggregate Actor (dataflow-core)
         │
         ├──► Validate CreatePipeline command
         ├──► Persist PipelineCreated event → Cassandra
         ├──► Update state: EmptyState → ConfiguredState
         │
         └──► Reply: StatusReply.Success


2️⃣  START PIPELINE (via API)
    ─────────────────────────

    User Request (HTTP POST)
         │
         ├──► dataflow-api: POST /api/pipelines/123/start
         │
         ▼
    PipelineShardRegion
         │
         ▼
    PipelineAggregate ("pipeline-123")
         │
         ├──► Validate StartPipeline command
         ├──► Persist PipelineStarted event → Cassandra
         ├──► Update state: ConfiguredState → RunningState
         │
         ├──► Initialize Source Connector (dataflow-sources)
         │      │
         │      ├─ Connect to Kafka/File/DB
         │      └─ Start polling for data
         │
         ├──► Initialize Transform Engine (dataflow-transforms)
         │      │
         │      └─ Compile transform logic
         │
         └──► Initialize Sink Connector (dataflow-sinks)
                │
                └─ Connect to destination


3️⃣  INGEST & PROCESS DATA (runtime)
    ────────────────────────────────

    Source Connector (dataflow-sources)
         │
         ├──► Poll Kafka/File/DB
         │      │
         │      └─ Read batch of records (e.g., 1000 records)
         │
         ├──► Create DataRecords
         │
         └──► Send IngestBatch command
                │
                ▼
    PipelineAggregate ("pipeline-123")
         │
         ├──► Validate batch
         ├──► Check idempotency (already processed?)
         │
         ├──► Process batch:
         │      │
         │      ├─► Transform Engine (dataflow-transforms)
         │      │      │
         │      │      ├─ Apply filters
         │      │      ├─ Apply maps
         │      │      └─ Apply aggregations
         │      │
         │      └─► Sink Connector (dataflow-sinks)
         │             │
         │             ├─ Write to Kafka
         │             ├─ Write to Postgres
         │             └─ Write to Elasticsearch
         │
         ├──► Persist BatchProcessed event → Cassandra
         ├──► Update metrics
         ├──► Update checkpoint
         │
         └──► Reply: StatusReply.Success


4️⃣  PROJECTION & MONITORING (continuous)
    ──────────────────────────────────────

    Cassandra Event Journal
         │
         ├──► Events: PipelineCreated, BatchProcessed, etc.
         │
         ▼
    Pekko Projections (dataflow-projections)
         │
         ├──► Read events
         │
         ├──► Update Read Models:
         │      │
         │      ├─ Pipeline Status View → PostgreSQL
         │      ├─ Metrics Aggregation → PostgreSQL
         │      ├─ Audit Log → Elasticsearch
         │      └─ Search Index → Elasticsearch
         │
         └──► Publish metrics → Prometheus
                │
                ▼
          Grafana Dashboards
```

---

## Entry Points

### Entry Point 1: HTTP API (dataflow-api)

**File**: `dataflow-api/src/main/scala/com/dataflow/api/PipelineRoutes.scala`

```scala
package com.dataflow.api

import com.dataflow.domain.commands._
import com.dataflow.domain.models._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

class PipelineRoutes(
  pipelineShardRegion: ActorRef[ShardingEnvelope[Command]]
)(implicit system: ActorSystem[_]) {

  val routes: Route =
    pathPrefix("api" / "pipelines") {
      concat(
        // POST /api/pipelines - Create pipeline
        post {
          entity(as[CreatePipelineRequest]) { request =>
            val probe = createTestProbe[StatusReply[State]]()

            pipelineShardRegion ! ShardingEnvelope(
              request.pipelineId,
              CreatePipeline(
                request.pipelineId,
                request.name,
                request.description,
                request.source,
                request.transforms,
                request.sink,
                probe.ref
              )
            )

            onSuccess(probe.receiveMessage(5.seconds)) {
              case StatusReply.Success(state) =>
                complete(StatusCodes.Created, state)
              case StatusReply.Error(error) =>
                complete(StatusCodes.BadRequest, error)
            }
          }
        },

        // POST /api/pipelines/:id/start - Start pipeline
        path(Segment / "start") { pipelineId =>
          post {
            val probe = createTestProbe[StatusReply[State]]()

            pipelineShardRegion ! ShardingEnvelope(
              pipelineId,
              StartPipeline(pipelineId, probe.ref)
            )

            onSuccess(probe.receiveMessage(5.seconds)) {
              case StatusReply.Success(state) =>
                complete(StatusCodes.OK, state)
              case StatusReply.Error(error) =>
                complete(StatusCodes.BadRequest, error)
            }
          }
        },

        // GET /api/pipelines/:id - Get pipeline state
        path(Segment) { pipelineId =>
          get {
            val probe = createTestProbe[State]()

            pipelineShardRegion ! ShardingEnvelope(
              pipelineId,
              GetState(pipelineId, probe.ref)
            )

            onSuccess(probe.receiveMessage(5.seconds)) { state =>
              complete(StatusCodes.OK, state)
            }
          }
        }
      )
    }
}
```

### Entry Point 2: Main Application (dataflow-core)

**File**: `dataflow-core/src/main/scala/com/dataflow/Main.scala`

```scala
package com.dataflow

import com.dataflow.cluster.PipelineSharding
import com.dataflow.aggregates.coordinator.CoordinatorAggregate
import com.dataflow.metrics.MetricsReporter
import com.dataflow.api.PipelineRoutes
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.{Cluster, ClusterSingleton, SingletonActor}
import org.apache.pekko.http.scaladsl.Http

object Main {

  def main(args: Array[String]): Unit = {
    // 1. Create actor system
    val system = ActorSystem(Behaviors.empty, "DataflowPlatform")

    // 2. Initialize metrics
    MetricsReporter.init()

    // 3. Initialize cluster sharding for pipelines
    val pipelineShardRegion = PipelineSharding.init(system)
    system.log.info("Pipeline sharding initialized")

    // 4. Initialize coordinator (cluster singleton)
    val singletonManager = ClusterSingleton(system)
    val coordinator = singletonManager.init(
      SingletonActor(
        CoordinatorAggregate(),
        CoordinatorAggregate.CoordinatorId
      )
    )
    system.log.info("Coordinator initialized")

    // 5. Start HTTP API (on leader node only)
    if (Cluster(system).selfMember.hasRole("api")) {
      val routes = new PipelineRoutes(pipelineShardRegion)(system)
      val bindingFuture = Http()(system)
        .newServerAt("0.0.0.0", 8080)
        .bind(routes.routes)

      system.log.info("HTTP API started on port 8080")
    }

    // 6. Graceful shutdown
    sys.addShutdownHook {
      system.log.info("Shutting down DataFlow Platform")
      MetricsReporter.shutdown()
      system.terminate()
    }
  }
}
```

### Entry Point 3: Source Connector (dataflow-sources)

**File**: `dataflow-sources/src/main/scala/com/dataflow/sources/KafkaSource.scala`

```scala
package com.dataflow.sources

import com.dataflow.domain.commands.IngestBatch
import com.dataflow.domain.models.DataRecord
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.kafka.{ConsumerSettings, Subscriptions}
import org.apache.pekko.stream.scaladsl.{Sink, Source}

class KafkaSource(
  pipelineId: String,
  pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
  kafkaConfig: KafkaSourceConfig
)(implicit system: ActorSystem[_]) {

  private val consumerSettings = ConsumerSettings(...)

  def start(): Unit = {
    Consumer
      .plainSource(consumerSettings, Subscriptions.topics(kafkaConfig.topic))
      .map { record =>
        // Convert Kafka record to DataRecord
        DataRecord(
          id = record.key(),
          data = parseJson(record.value()),
          metadata = Map(
            "topic" -> record.topic(),
            "partition" -> record.partition().toString,
            "offset" -> record.offset().toString
          )
        )
      }
      .grouped(kafkaConfig.batchSize) // Batch records
      .map { records =>
        val batchId = UUID.randomUUID().toString
        val offset = records.last.metadata("offset").toLong

        // Create IngestBatch command
        IngestBatch(
          pipelineId,
          batchId,
          records.toList,
          offset,
          replyTo = system.ignoreRef
        )
      }
      .to(Sink.foreach { command =>
        // Send to pipeline shard region
        pipelineShardRegion ! ShardingEnvelope(pipelineId, command)
      })
      .run()
  }
}
```

---

## Complete Example: User Creates Pipeline

```
┌─────────┐
│  User   │
└────┬────┘
     │
     │ 1. POST /api/pipelines
     │    {
     │      "pipelineId": "user-events-pipeline",
     │      "name": "User Events",
     │      "source": {
     │        "type": "kafka",
     │        "topic": "user-events"
     │      },
     │      "transforms": [
     │        {"type": "filter", "condition": "age > 18"}
     │      ],
     │      "sink": {
     │        "type": "postgres",
     │        "table": "users"
     │      }
     │    }
     │
     ▼
┌──────────────────┐
│  dataflow-api    │
│  PipelineRoutes  │
└────────┬─────────┘
         │
         │ 2. Validate request
         │    Convert to CreatePipeline command
         │
         ▼
┌────────────────────────┐
│ PipelineShardRegion    │
│ (Cluster Sharding)     │
└────────┬───────────────┘
         │
         │ 3. Hash("user-events-pipeline") = shard 42
         │    Route to Node 2 (owns shard 42)
         │
         ▼
┌─────────────────────────┐
│ Node 2                  │
│ ┌─────────────────────┐ │
│ │ PipelineAggregate   │ │
│ │ (user-events-...)   │ │
│ └─────────┬───────────┘ │
│           │             │
│           │ 4. Process CreatePipeline command
│           │    - Validate inputs
│           │    - Persist PipelineCreated event
│           │    - Update state: Empty → Configured
│           │    - Record metrics
│           │
└───────────┼─────────────┘
            │
            ▼
      ┌────────────┐
      │ Cassandra  │ 5. Event stored
      │ (Journal)  │
      └────────────┘

            │
            │ 6. Projection reads event
            ▼
      ┌────────────┐
      │ PostgreSQL │ 7. Read model updated
      │ (View)     │
      └────────────┘

            │
            │ 8. Success response
            ▼
      ┌────────────┐
      │    User    │ 9. Receives: {"status": "created"}
      └────────────┘
```

---

## Summary

### Which Module Handles Sharding?

**Answer: dataflow-core** module handles cluster sharding.

**Specifically**:
- `PipelineSharding.scala` initializes the shard region
- `PipelineAggregate.scala` defines the sharded entities
- Each pipeline is an entity distributed across cluster nodes

### How Modules Interact

```
dataflow-api (Entry Point)
    │
    ├─ HTTP requests
    │
    └──► dataflow-core (Orchestrator)
           │
           ├─ Manages pipeline lifecycle (PipelineAggregate)
           ├─ Distributes pipelines (Cluster Sharding)
           ├─ Stores events (Cassandra)
           │
           └──► Calls modules:
                  │
                  ├─► dataflow-sources: Read data
                  ├─► dataflow-transforms: Transform data
                  ├─► dataflow-sinks: Write data
                  └─► dataflow-projections: Update views
```

### Core Module Exposes Entry Points

1. **PipelineShardRegion**: `ActorRef[ShardingEnvelope[Command]]`
   - Send commands to any pipeline
   - Automatically routed to correct node

2. **CoordinatorSingleton**: `ActorRef[CoordinatorCommand]`
   - Query system status
   - List all pipelines

3. **Main.scala**: Application entry point
   - Initializes all modules
   - Starts HTTP server
   - Sets up sharding

This architecture provides:
- ✅ Horizontal scalability (add more nodes)
- ✅ Location transparency (don't need to know which node)
- ✅ Fault tolerance (pipelines migrate on node failure)
- ✅ Clear separation of concerns (each module has a purpose)
