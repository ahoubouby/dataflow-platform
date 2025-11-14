# dataflow-api

> **Application Module**: Cluster runtime, Cassandra persistence, HTTP API, and pipeline execution orchestration

---

## 🎯 **Purpose**

**dataflow-api** is the **application module** that brings everything together. It provides:

- ✅ **Cluster runtime** (Pekko Cluster with sharding)
- ✅ **Cassandra persistence** (event journal and snapshots)
- ✅ **HTTP REST API** (pipeline management)
- ✅ **Pipeline execution** (Source → Transform → Sink orchestration)
- ✅ **Metrics & monitoring** (Kamon/Prometheus)
- ✅ **WebSocket** (real-time updates)

**IMPORTANT**: This is an **application**, not a library!

---

## ✨ **What This Module Contains**

- ✅ **Cluster dependencies** (`pekko-cluster-typed`, `pekko-cluster-sharding-typed`)
- ✅ **Cassandra driver** (`pekko-persistence-cassandra`, `java-driver-core`)
- ✅ **HTTP server** (`pekko-http`)
- ✅ **Metrics collection** (`kamon-core`, `kamon-prometheus`)
- ✅ **Execution orchestration** (`PipelineExecutor`, `ExecutionOrchestrator`)
- ✅ **Main entry point** (`ApiMain.scala`)

**Why?** This is the **application layer** that runs the distributed system. It depends on dataflow-core (domain library) but adds all the infrastructure.

---

## 🏗️ **Architecture**

### **Dependency Hierarchy**

```
dataflow-api (THIS MODULE - APPLICATION)
    ├── depends on → dataflow-core (domain library)
    ├── depends on → dataflow-sources (connectors)
    ├── depends on → dataflow-transforms (processing)
    └── depends on → dataflow-sinks (output)

Adds:
    ├── Cluster runtime (Pekko Cluster)
    ├── Cassandra client (persistence)
    ├── HTTP API (management)
    ├── Execution orchestration
    └── Metrics (Kamon)
```

### **Module Structure**

```
dataflow-api/
├── src/main/scala/com/dataflow/
│   ├── api/
│   │   ├── ApiMain.scala                    # 🚀 Application entry point
│   │   ├── models/                          # API DTOs
│   │   │   ├── CreatePipelineRequest.scala
│   │   │   ├── PipelineResponse.scala
│   │   │   └── ErrorResponse.scala
│   │   ├── routes/                          # HTTP routes
│   │   │   ├── PipelineRoutes.scala         # CRUD operations
│   │   │   ├── HealthRoutes.scala           # Health checks
│   │   │   └── MetricsRoutes.scala          # Metrics endpoint
│   │   └── http/
│   │       └── HttpServer.scala             # HTTP server setup
│   │
│   └── execution/                           # 🆕 Pipeline execution
│       ├── PipelineExecutor.scala           # Runs Source → Transform → Sink
│       ├── ExecutionOrchestrator.scala      # Manages executor lifecycle
│       ├── PipelineEventListener.scala      # Reads events from Cassandra
│       ├── TransformConfigMapper.scala      # Maps configs to transforms
│       └── SinkFactory.scala                # Creates sink instances
│
└── src/main/resources/
    ├── application.conf                     # ✅ ALL cluster/Cassandra config
    ├── cluster.conf                         # Cluster settings
    ├── kamon-local.conf                     # Metrics configuration
    └── logback.xml                          # Logging configuration
```

---

## 📦 **Dependencies**

**build.sbt** (complete):

```scala
lazy val dataflowApi = (project in file("dataflow-api"))
  .dependsOn(
    dataflowCore % "compile->compile;test->test",
    dataflowSources % "compile->compile",
    dataflowTransforms % "compile->compile",
    dataflowSinks % "compile->compile"
  )
  .settings(
    libraryDependencies ++=
      commonDependencies ++
      testDependencies ++
      httpDependencies ++           // Pekko HTTP
      validationDependencies ++
      metricsDependencies ++        // ✅ Kamon metrics
      clusterDependencies ++        // ✅ Pekko Cluster
      persistenceDependencies       // ✅ Cassandra driver
  )
```

**Key Dependencies:**
- ✅ `clusterDependencies` - Cluster sharding, split-brain resolution
- ✅ `persistenceDependencies` - Cassandra driver, persistence plugins
- ✅ `metricsDependencies` - Kamon, Prometheus reporter
- ✅ `httpDependencies` - Pekko HTTP, CORS support

---

## ⚙️ **Configuration**

### **application.conf** (comprehensive)

**This module contains ALL configuration** for:

1. **Cluster Configuration**
```hocon
pekko {
  actor.provider = cluster  # ✅ Runs as a cluster

  remote.artery {
    canonical {
      hostname = "127.0.0.1"
      port = 2551
    }
  }

  cluster {
    seed-nodes = ["pekko://DataFlowSystem@127.0.0.1:2551"]

    sharding {
      number-of-shards = 100
    }
  }
}
```

2. **Cassandra Persistence**
```hocon
pekko.persistence.cassandra {
  journal {
    keyspace = "dataflow_journal"
    table = "messages"
    keyspace-autocreate = true
    tables-autocreate = true
  }

  snapshot {
    keyspace = "dataflow_snapshot"
    table = "snapshots"
  }
}

datastax-java-driver {
  basic {
    contact-points = ["127.0.0.1:9042"]
    load-balancing-policy.local-datacenter = "datacenter1"
  }

  advanced {
    reconnection-policy {
      class = ExponentialReconnectionPolicy
      base-delay = 1 second
      max-delay = 60 seconds
    }
  }
}
```

3. **Kamon Metrics**
```hocon
include "kamon-local.conf"

kamon {
  prometheus {
    embedded-server {
      hostname = "0.0.0.0"
      port = 9095
    }
  }
}
```

4. **HTTP API**
```hocon
dataflow.api {
  host = "0.0.0.0"
  port = 8080
}

pekko.http {
  server {
    request-timeout = 30s
    idle-timeout = 60s
  }
}
```

---

## 🚀 **Running the Application**

### **Prerequisites**

1. **Start Infrastructure**:
```bash
cd docker
docker-compose up -d
```

This starts:
- Cassandra (port 9042)
- Kafka (port 9093)
- PostgreSQL (port 5432)
- Elasticsearch (port 9200)
- Grafana (port 3000)
- Prometheus (port 9090)

2. **Initialize Cassandra**:
```bash
cd docker/cassandra-init
./init-cassandra.sh
```

Or manually:
```bash
docker exec -i dataflow-cassandra cqlsh < docker/cassandra-init/01-init-keyspaces.cql
```

3. **Wait for Cassandra** (use the wait script):
```bash
./scripts/wait-for-cassandra.sh
```

### **Start the Application**

```bash
sbt "project dataflow-api" run
```

**Expected output:**
```
[INFO] Starting DataFlow Platform API...
[INFO] Cluster bootstrap starting
[INFO] Pekko Management started on http://127.0.0.1:8558
[INFO] Cassandra session initialized
[INFO] Cluster joined, member status: Up
[INFO] Initializing PipelineAggregate sharding
[INFO] Starting ExecutionOrchestrator
[INFO] PipelineEventListener started successfully
[INFO] Kamon metrics reporter started on http://0.0.0.0:9095/metrics
[INFO] HTTP server online at http://0.0.0.0:8080/
[INFO] DataFlow Platform API started successfully!
```

### **Verify Services**

```bash
# Health check
curl http://localhost:8080/health

# Metrics (Prometheus format)
curl http://localhost:9095/metrics

# List pipelines
curl http://localhost:8080/api/v1/pipelines
```

---

## 🎭 **Architecture Components**

### **1. ApiMain (Entry Point)**

The main application entry point:

```scala
object ApiMain extends App {
  // Initialize Kamon metrics
  Kamon.init()

  // Create actor system
  val system = ActorSystem[Nothing](Behaviors.setup[Nothing] { context =>
    // Initialize cluster sharding
    val sharding = ClusterSharding(system)
    sharding.init(Entity(PipelineAggregate.TypeKey) { entityContext =>
      PipelineAggregate(entityContext.entityId)
    })

    // Start execution orchestrator
    val orchestrator = context.spawn(ExecutionOrchestrator(), "execution-orchestrator")

    // Start event listener (reads from Cassandra)
    PipelineEventListener.start(orchestrator)

    // Start HTTP server
    val routes = new PipelineRoutes(sharding)
    HttpServer.start(routes.routes)

    Behaviors.empty
  }, "DataFlowSystem")

  // Graceful shutdown
  sys.addShutdownHook {
    Kamon.stop()
    system.terminate()
  }
}
```

### **2. Pipeline Execution Flow**

```
1. HTTP POST /api/v1/pipelines/{id}/start
   ↓
2. PipelineRoutes → PipelineAggregate (via cluster sharding)
   ↓
3. PipelineAggregate emits PipelineStarted event → Cassandra
   ↓
4. PipelineEventListener reads event from Cassandra journal
   ↓
5. ExecutionOrchestrator spawns PipelineExecutor
   ↓
6. PipelineExecutor builds Pekko Streams graph:
   Source → Transform(s) → Sink
   ↓
7. Data flows through pipeline
   ↓
8. Metrics collected via Kamon
```

### **3. ExecutionOrchestrator**

Manages the lifecycle of all pipeline executors:

```scala
object ExecutionOrchestrator {
  sealed trait Command
  case class HandleEvent(event: Event, pipelineId: String) extends Command

  def apply(): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case HandleEvent(PipelineStarted(id, _), pipelineId) =>
          // Spawn PipelineExecutor
          val executor = context.spawn(PipelineExecutor(pipelineId), s"executor-$pipelineId")
          executor ! PipelineExecutor.Start
          Behaviors.same

        case HandleEvent(PipelineStopped(id, _, _, _), pipelineId) =>
          // Stop executor
          context.child(s"executor-$pipelineId").foreach(context.stop)
          Behaviors.same
      }
    }
  }
}
```

### **4. PipelineEventListener**

Reads events from Cassandra and forwards to orchestrator:

```scala
object PipelineEventListener {
  def start(orchestrator: ActorRef[ExecutionOrchestrator.Command])(implicit system: ActorSystem[_]): Future[Unit] = {
    // Create Cassandra read journal
    val readJournal = PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

    // Stream events with tag "pipeline"
    RestartSource.withBackoff(minBackoff = 3.seconds, maxBackoff = 30.seconds) { () =>
      readJournal.eventsByTag("pipeline", Offset.noOffset)
        .map { envelope =>
          val pipelineId = envelope.persistenceId.split("-", 2).lastOption.getOrElse("unknown")
          orchestrator ! ExecutionOrchestrator.HandleEvent(envelope.event, pipelineId)
        }
    }.runWith(Sink.ignore)
  }
}
```

### **5. PipelineExecutor**

Actually runs pipelines (Source → Transform → Sink):

```scala
class PipelineExecutor(pipelineId: String) {
  def buildGraph(config: PipelineConfig): RunnableGraph[KillSwitch] = {
    val source = SourceFactory.create(config.source)
    val transforms = config.transforms.map(TransformConfigMapper.toTransform)
    val sink = SinkFactory.create(config.sink)

    // Build stream: Source → Transform(s) → Sink
    val streamSource = source.stream()
    val transformedStream = transforms.foldLeft(streamSource) { (stream, transform) =>
      stream.via(transform.flow)
    }

    transformedStream
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(sink.sink)(Keep.left)
  }
}
```

---

## 📡 **HTTP API**

### **Endpoints**

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/pipelines` | Create pipeline |
| GET | `/api/v1/pipelines` | List all pipelines |
| GET | `/api/v1/pipelines/{id}` | Get pipeline details |
| POST | `/api/v1/pipelines/{id}/start` | Start pipeline |
| POST | `/api/v1/pipelines/{id}/stop` | Stop pipeline |
| POST | `/api/v1/pipelines/{id}/pause` | Pause pipeline |
| POST | `/api/v1/pipelines/{id}/resume` | Resume pipeline |
| GET | `/api/v1/pipelines/{id}/metrics` | Get metrics |
| GET | `/health` | Health check |

### **Example Usage**

```bash
# Create a pipeline
curl -X POST http://localhost:8080/api/v1/pipelines \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Pipeline",
    "description": "File to console pipeline",
    "source": {
      "sourceType": "file",
      "connectionString": "/data/input.csv",
      "batchSize": 100
    },
    "transforms": [
      {
        "transformType": "filter",
        "config": {"field": "status", "value": "active"}
      }
    ],
    "sink": {
      "sinkType": "console",
      "connectionString": "",
      "batchSize": 10
    }
  }'

# Start the pipeline
curl -X POST http://localhost:8080/api/v1/pipelines/{pipeline-id}/start

# Get metrics
curl http://localhost:8080/api/v1/pipelines/{pipeline-id}/metrics
```

---

## 📊 **Metrics & Monitoring**

### **Kamon Metrics**

Metrics are exposed at `http://localhost:9095/metrics` in Prometheus format:

```
# HELP jvm_memory_used_bytes JVM memory used
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap"} 1.23456789e8

# HELP pekko_actor_mailbox_size Actor mailbox size
# TYPE pekko_actor_mailbox_size gauge
pekko_actor_mailbox_size{actor="pipeline-123"} 5

# HELP dataflow_pipeline_records_processed_total Total records processed
# TYPE dataflow_pipeline_records_processed_total counter
dataflow_pipeline_records_processed_total{pipeline="test-pipeline"} 10000
```

### **Grafana Dashboards**

Access Grafana at `http://localhost:3000` (admin/admin):

- JVM metrics (heap, threads, GC)
- Actor metrics (mailbox size, processing time)
- Pipeline metrics (throughput, latency)
- System metrics (CPU, memory)

---

## 🧪 **Testing**

### **Run Tests**

```bash
sbt "project dataflow-api" test
```

### **Integration Tests**

```bash
sbt "project dataflow-api" it:test
```

### **End-to-End Test**

```bash
# Start infrastructure
docker-compose up -d

# Wait for Cassandra
./scripts/wait-for-cassandra.sh

# Run application
sbt "project dataflow-api" run

# In another terminal, run tests
./scripts/api-usage-examples.sh
```

---

## 🔧 **Troubleshooting**

### **Cassandra Connection Issues**

**Error**: `Could not reach any contact point /127.0.0.1:9042`

**Solution**:
```bash
# 1. Check Cassandra is running
docker ps | grep cassandra

# 2. Wait for Cassandra to be ready (takes 60s)
./scripts/wait-for-cassandra.sh

# 3. Verify connectivity
docker exec dataflow-cassandra cqlsh -e "describe keyspaces"
```

### **Cluster Not Forming**

**Error**: `Cluster node not joining`

**Solution**:
- Check seed nodes in `application.conf`
- Ensure port 2551 is available
- Check firewall settings

### **HTTP Server Won't Start**

**Error**: `Address already in use: 8080`

**Solution**:
```bash
# Change port via environment variable
API_PORT=8081 sbt "project dataflow-api" run
```

---

## 🤔 **Why This Architecture?**

### **Separation of Concerns**

- **dataflow-core**: Pure domain logic (library)
- **dataflow-api**: Infrastructure + runtime (application)

This allows:
- ✅ Testing domain logic without cluster
- ✅ Reusing domain in different contexts
- ✅ Clear dependency boundaries
- ✅ No circular dependencies

### **Event-Driven Orchestration**

Instead of spawning executors directly from aggregates:

```scala
// ❌ BAD: Aggregate spawns executors (wrong layer)
case PipelineStarted(...) =>
  val executor = context.spawn(PipelineExecutor(...))  // Breaks separation!

// ✅ GOOD: Event-driven orchestration
case PipelineStarted(...) =>
  persist(event)  // Pure event sourcing in aggregate

// Separately:
PipelineEventListener reads event → ExecutionOrchestrator spawns executor
```

---

## 🔗 **Related Modules**

| Module | Relationship |
|--------|-------------|
| **dataflow-core** | **Domain library** - This module depends on it |
| **dataflow-sources** | **Connectors** - This module depends on it |
| **dataflow-transforms** | **Processing** - This module depends on it |
| **dataflow-sinks** | **Output** - This module depends on it |

---

## 🚀 **Next Steps**

- See [Main README.md](../README.md) for quick start
- See [API_DOCUMENTATION.md](../docs/API_DOCUMENTATION.md) for complete API reference
- See [ARCHITECTURE_AND_ROADMAP.md](../docs/ARCHITECTURE_AND_ROADMAP.md) for architecture details

---

**dataflow-api**: Where everything comes together! 🎉
