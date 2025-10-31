# DataFlow Platform - Complete Architecture & Roadmap

> **Goal**: Build a production-grade, distributed data pipeline orchestration platform (like Apache NiFi) using Apache Pekko

---

## 🎯 **Vision**

A **horizontally scalable, event-sourced data pipeline platform** that:
- Ingests data from multiple sources (files, Kafka, APIs, databases)
- Transforms data through configurable pipelines
- Outputs to multiple sinks (files, Kafka, Cassandra, Elasticsearch)
- Provides real-time monitoring and metrics
- Supports exactly-once processing semantics
- Scales across cluster nodes
- Has complete audit trail via Event Sourcing

---

## 🏗️ **High-Level Architecture**

```
┌─────────────────────────────────────────────────────────────────────┐
│                          API Layer (HTTP)                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │   Pipeline   │  │   Monitor    │  │   Admin      │              │
│  │   Management │  │   Dashboard  │  │   Console    │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
└──────────────┬──────────────────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────────────────┐
│                    Coordinator (Cluster Singleton)                   │
│  ┌────────────────────────────────────────────────────────────┐     │
│  │  - Pipeline Registry                                       │     │
│  │  - Resource Allocation                                     │     │
│  │  - Health Monitoring                                       │     │
│  │  - Load Balancing                                         │     │
│  └────────────────────────────────────────────────────────────┘     │
└──────────────┬──────────────────────────────────────────────────────┘
               │
      ┌────────┴────────┬────────────────┬────────────────┐
      │                 │                │                │
┌─────▼─────┐     ┌─────▼─────┐   ┌─────▼─────┐   ┌─────▼─────┐
│  Node 1   │     │  Node 2   │   │  Node 3   │   │  Node N   │
│           │     │           │   │           │   │           │
│ ┌───────┐ │     │ ┌───────┐ │   │ ┌───────┐ │   │ ┌───────┐ │
│ │Pipeline│ │     │ │Pipeline│ │   │ │Pipeline│ │   │ │Pipeline│ │
│ │   1    │ │     │ │   2    │ │   │ │   3    │ │   │ │   N    │ │
│ │        │ │     │ │        │ │   │ │        │ │   │ │        │ │
│ │┌──────┐│ │     │ │┌──────┐│ │   │ │┌──────┐│ │   │ │┌──────┐│ │
│ ││Source││ │     │ ││Source││ │   │ ││Source││ │   │ ││Source││ │
│ │└───┬──┘│ │     │ │└───┬──┘│ │   │ │└───┬──┘│ │   │ │└───┬──┘│ │
│ │    │   │ │     │ │    │   │ │   │ │    │   │ │   │ │    │   │ │
│ │┌───▼──┐│ │     │ │┌───▼──┐│ │   │ │┌───▼──┐│ │   │ │┌───▼──┐│ │
│ ││Trans-││ │     │ ││Trans-││ │   │ ││Trans-││ │   │ ││Trans-││ │
│ ││ form ││ │     │ ││ form ││ │   │ ││ form ││ │   │ ││ form ││ │
│ │└───┬──┘│ │     │ │└───┬──┘│ │   │ │└───┬──┘│ │   │ │└───┬──┘│ │
│ │    │   │ │     │ │    │   │ │   │ │    │   │ │   │ │    │   │ │
│ │┌───▼──┐│ │     │ │┌───▼──┐│ │   │ │┌───▼──┐│ │   │ │┌───▼──┐│ │
│ ││ Sink ││ │     │ ││ Sink ││ │   │ ││ Sink ││ │   │ ││ Sink ││ │
│ │└──────┘│ │     │ │└──────┘│ │   │ │└──────┘│ │   │ │└──────┘│ │
│ └───────┘ │     │ └───────┘ │   │ └───────┘ │   │ └───────┘ │
└───────────┘     └───────────┘   └───────────┘   └───────────┘
      │                 │                │                │
      └─────────────────┴────────────────┴────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────────────┐
│                      Persistence Layer                              │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐   │
│  │ Cassandra  │  │   Kafka    │  │ PostgreSQL │  │   Redis    │   │
│  │  (Events)  │  │ (Streaming)│  │   (Read    │  │  (Cache)   │   │
│  │            │  │            │  │   Models)  │  │            │   │
│  └────────────┘  └────────────┘  └────────────┘  └────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 📦 **Module Breakdown**

### **Module 1: Core (dataflow-core)** ⭐ CURRENT ITERATION

**Purpose**: Event-sourced aggregates and domain logic

**Components**:
```
dataflow-core/
├── domain/
│   ├── models/
│   │   ├── PipelineConfig.scala      # Pipeline configuration
│   │   ├── SourceConfig.scala        # Source settings
│   │   ├── SinkConfig.scala          # Sink settings
│   │   ├── TransformConfig.scala     # Transform rules
│   │   └── Checkpoint.scala          # Offset tracking
│   ├── commands/
│   │   └── PipelineCommands.scala    # All pipeline commands
│   ├── events/
│   │   └── PipelineEvents.scala      # All pipeline events
│   └── state/
│       └── PipelineState.scala       # Pipeline states
│
├── aggregates/
│   ├── PipelineAggregate.scala       # Main event-sourced aggregate
│   └── coordinator/
│       └── CoordinatorAggregate.scala # System coordinator
│
└── serialization/
    └── CborSerializable.scala        # Serialization marker
```

**Key Aggregate: PipelineAggregate**
```scala
// Commands
- CreatePipeline(config)
- StartPipeline()
- StopPipeline()
- PausePipeline()
- ResumePipeline()
- IngestBatch(data)
- UpdateCheckpoint(offset)
- ReportMetrics(stats)
- HandleFailure(error)

// Events
- PipelineCreated
- PipelineStarted
- PipelineStopped
- PipelinePaused
- PipelineResumed
- BatchIngested
- BatchProcessed
- CheckpointUpdated
- MetricsReported
- PipelineFailed

// States
- UninitializedState
- ConfiguredState
- RunningState
- PausedState
- StoppedState
- FailedState
```

**Learning Focus**:
- Event Sourcing with complex state machine
- Checkpoint management (exactly-once semantics)
- Metrics tracking
- Error handling and recovery

---

### **Module 2: Sources (dataflow-sources)** 🔜 ITERATION 2

**Purpose**: Data ingestion from various sources

**Components**:
```
dataflow-sources/
├── file/
│   ├── FileSource.scala              # Read from files
│   ├── CSVFileSource.scala           # CSV parsing
│   └── JSONFileSource.scala          # JSON parsing
│
├── kafka/
│   ├── KafkaSource.scala             # Kafka consumer
│   └── KafkaSourceConfig.scala       # Consumer settings
│
├── api/
│   ├── RestApiSource.scala           # Poll REST APIs
│   └── WebSocketSource.scala        # WebSocket streaming
│
├── database/
│   ├── JdbcSource.scala              # JDBC polling
│   └── ChangeDataCapture.scala      # CDC streaming
│
└── SourceActor.scala                 # Base source actor trait
```

**Key Patterns**:
- Backpressure handling (Pekko Streams)
- Offset management (checkpoints)
- Error recovery strategies
- Rate limiting

**Example Implementation**:
```scala
trait SourceActor {
  def start(): Unit
  def stop(): Unit
  def pause(): Unit
  def resume(): Unit
  def getMetrics(): SourceMetrics
}

class KafkaSource(
  topics: Set[String],
  pipelineRef: ActorRef[PipelineAggregate.Command]
) extends SourceActor {
  // Kafka consumer with Alpakka
  // Send batches to pipeline
  // Manage offsets
  // Handle backpressure
}
```

**Learning Focus**:
- Pekko Streams integration
- Alpakka connectors
- Backpressure strategies
- Exactly-once semantics

---

### **Module 3: Transforms (dataflow-transforms)** 🔜 ITERATION 3

**Purpose**: Data transformation and enrichment

**Components**:
```
dataflow-transforms/
├── filter/
│   └── FilterTransform.scala         # Filter records
│
├── map/
│   ├── MapTransform.scala            # Transform fields
│   └── FlatMapTransform.scala        # 1-to-many transform
│
├── aggregate/
│   ├── GroupByTransform.scala        # Group records
│   └── WindowTransform.scala         # Time windows
│
├── join/
│   ├── StreamJoinTransform.scala     # Join streams
│   └── LookupTransform.scala         # Enrich from cache
│
├── schema/
│   ├── SchemaValidator.scala         # Validate schema
│   └── SchemaEvolution.scala         # Handle schema changes
│
└── TransformActor.scala              # Base transform trait
```

**Key Patterns**:
- Stateless vs stateful transforms
- Stream composition
- Error handling
- Schema evolution

**Example Implementation**:
```scala
trait Transform[In, Out] {
  def transform(input: In): Try[Out]
}

class FilterTransform(predicate: Record => Boolean) 
  extends Transform[Record, Option[Record]] {
  
  def transform(input: Record): Try[Option[Record]] = {
    Try {
      if (predicate(input)) Some(input)
      else None
    }
  }
}

class MapTransform(mapper: Record => Record)
  extends Transform[Record, Record] {
  
  def transform(input: Record): Try[Record] = {
    Try(mapper(input))
  }
}
```

**Learning Focus**:
- Stream processing patterns
- Stateful vs stateless operations
- Windowing and aggregation
- Schema management

---

### **Module 4: Sinks (dataflow-sinks)** 🔜 ITERATION 4

**Purpose**: Data output to various destinations

**Components**:
```
dataflow-sinks/
├── file/
│   ├── FileSink.scala                # Write to files
│   ├── CSVFileSink.scala             # CSV format
│   └── JSONFileSink.scala            # JSON format
│
├── kafka/
│   ├── KafkaSink.scala               # Kafka producer
│   └── KafkaSinkConfig.scala         # Producer settings
│
├── database/
│   ├── CassandraSink.scala           # Cassandra writer
│   ├── PostgreSQLSink.scala          # PostgreSQL writer
│   └── JdbcSink.scala                # Generic JDBC
│
├── search/
│   └── ElasticsearchSink.scala       # Elasticsearch indexer
│
├── cloud/
│   ├── S3Sink.scala                  # AWS S3
│   └── GCSSink.scala                 # Google Cloud Storage
│
└── SinkActor.scala                   # Base sink trait
```

**Key Patterns**:
- Batching for efficiency
- At-least-once delivery
- Idempotency handling
- Connection pooling

**Example Implementation**:
```scala
trait SinkActor {
  def write(batch: Batch): Future[WriteResult]
  def flush(): Future[Unit]
  def close(): Future[Unit]
}

class CassandraSink(
  keyspace: String,
  table: String,
  session: CqlSession
) extends SinkActor {
  
  def write(batch: Batch): Future[WriteResult] = {
    // Batch insert to Cassandra
    // Handle failures
    // Return acknowledgment
  }
}
```

**Learning Focus**:
- Alpakka connectors
- Batching strategies
- Error recovery
- Performance optimization

---

### **Module 5: API (dataflow-api)** 🔜 ITERATION 5

**Purpose**: HTTP API for pipeline management

**Components**:
```
dataflow-api/
├── routes/
│   ├── PipelineRoutes.scala          # Pipeline CRUD
│   ├── MonitoringRoutes.scala        # Metrics & health
│   ├── AdminRoutes.scala             # Admin operations
│   └── WebSocketRoutes.scala         # Real-time updates
│
├── models/
│   ├── PipelineDTO.scala             # Data transfer objects
│   └── ApiResponses.scala            # API responses
│
├── validation/
│   └── RequestValidator.scala        # Input validation
│
└── HttpServer.scala                  # HTTP server setup
```

**API Endpoints**:
```
POST   /api/v1/pipelines              # Create pipeline
GET    /api/v1/pipelines              # List pipelines
GET    /api/v1/pipelines/:id          # Get pipeline
PUT    /api/v1/pipelines/:id          # Update pipeline
DELETE /api/v1/pipelines/:id          # Delete pipeline

POST   /api/v1/pipelines/:id/start   # Start pipeline
POST   /api/v1/pipelines/:id/stop    # Stop pipeline
POST   /api/v1/pipelines/:id/pause   # Pause pipeline
POST   /api/v1/pipelines/:id/resume  # Resume pipeline

GET    /api/v1/pipelines/:id/metrics # Get metrics
GET    /api/v1/pipelines/:id/health  # Health check
GET    /api/v1/pipelines/:id/events  # Event history

WS     /api/v1/ws/pipelines/:id      # Real-time updates
```

**Example Implementation**:
```scala
class PipelineRoutes(sharding: ClusterSharding)(implicit system: ActorSystem[_]) {
  
  val routes: Route = pathPrefix("api" / "v1" / "pipelines") {
    concat(
      post {
        entity(as[CreatePipelineRequest]) { request =>
          val pipelineId = UUID.randomUUID().toString
          val response = sharding
            .entityRefFor(PipelineAggregate.TypeKey, pipelineId)
            .ask(CreatePipeline(request.config, _))
          
          onSuccess(response) {
            case Success(state) => complete(StatusCodes.Created, state)
            case Failure(error) => complete(StatusCodes.BadRequest, error)
          }
        }
      },
      pathPrefix(Segment) { pipelineId =>
        concat(
          get {
            val response = sharding
              .entityRefFor(PipelineAggregate.TypeKey, pipelineId)
              .ask(GetState(_))
            
            onSuccess(response) { state =>
              complete(state)
            }
          },
          post {
            path("start") {
              // Start pipeline
            }
          }
        )
      }
    )
  }
}
```

**Learning Focus**:
- Pekko HTTP
- REST API design
- WebSocket for real-time
- Request validation

---

### **Module 6: Projections (dataflow-projections)** 🔜 ITERATION 6

**Purpose**: CQRS read models for queries

**Components**:
```
dataflow-projections/
├── pipeline-status/
│   ├── PipelineStatusProjection.scala    # Current status view
│   └── PipelineStatusRepository.scala     # Read model storage
│
├── metrics/
│   ├── MetricsProjection.scala           # Aggregated metrics
│   └── TimeSeriesRepository.scala        # Time-series data
│
├── audit/
│   ├── AuditLogProjection.scala          # Complete audit trail
│   └── AuditRepository.scala             # Audit storage
│
└── search/
    ├── SearchIndexProjection.scala       # Elasticsearch indexer
    └── SearchRepository.scala            # Search queries
```

**Key Patterns**:
- Event-driven projections
- Eventual consistency
- Offset management
- Projection recovery

**Example Implementation**:
```scala
class PipelineStatusProjection(
  repository: PipelineStatusRepository
) {
  
  def handler(): Handler[EventEnvelope[PipelineEvent]] = {
    Handler[EventEnvelope[PipelineEvent]] { envelope =>
      envelope.event match {
        case PipelineCreated(id, name, config, timestamp) =>
          repository.insert(PipelineStatus(
            id = id,
            name = name,
            status = "configured",
            createdAt = timestamp
          ))
          
        case PipelineStarted(id, timestamp) =>
          repository.updateStatus(id, "running", timestamp)
          
        case PipelineStopped(id, timestamp, metrics) =>
          repository.updateStatus(id, "stopped", timestamp)
          repository.updateMetrics(id, metrics)
          
        // ... handle other events
      }
    }
  }
}
```

**Learning Focus**:
- Pekko Projections
- CQRS pattern
- Read model design
- Event tagging

---

## 🗺️ **Implementation Roadmap**

### **Phase 1: Foundation** (Weeks 1-2) ⭐ CURRENT

**Goal**: Event-sourced Pipeline aggregate with tests

**Deliverables**:
- [x] Project structure (Pekko)
- [x] Build configuration
- [x] Cassandra setup
- [ ] PipelineAggregate (complete)
- [ ] Domain models (commands, events, state)
- [ ] Comprehensive tests
- [ ] Documentation

**Key Learning**:
- Event Sourcing fundamentals
- State machines
- Command/Event/State pattern
- Testing strategies

---

### **Phase 2: Sources** (Week 3)

**Goal**: Data ingestion from multiple sources

**Deliverables**:
- [ ] FileSource (CSV, JSON)
- [ ] KafkaSource (Alpakka Kafka)
- [ ] Source actor trait
- [ ] Backpressure handling
- [ ] Tests

**Key Learning**:
- Pekko Streams
- Alpakka connectors
- Backpressure
- Checkpointing

---

### **Phase 3: Transforms** (Week 4)

**Goal**: Data transformation pipeline

**Deliverables**:
- [ ] FilterTransform
- [ ] MapTransform
- [ ] Transform composition
- [ ] Error handling
- [ ] Tests

**Key Learning**:
- Stream processing
- Composition patterns
- Error recovery
- Performance

---

### **Phase 4: Sinks** (Week 5)

**Goal**: Data output to destinations

**Deliverables**:
- [ ] FileSink
- [ ] KafkaSink
- [ ] CassandraSink
- [ ] Batching logic
- [ ] Tests

**Key Learning**:
- Output patterns
- Batching strategies
- Idempotency
- Connection management

---

### **Phase 5: Integration** (Week 6)

**Goal**: End-to-end pipeline working

**Deliverables**:
- [ ] Complete Source → Transform → Sink flow
- [ ] Integration tests
- [ ] Performance tests
- [ ] Documentation

**Key Learning**:
- System integration
- E2E testing
- Performance tuning
- Debugging

---

### **Phase 6: Cluster Sharding** (Week 7)

**Goal**: Horizontal scalability

**Deliverables**:
- [ ] Cluster configuration
- [ ] Sharding strategy
- [ ] Multi-node tests
- [ ] Load balancing

**Key Learning**:
- Cluster sharding
- Entity distribution
- Rebalancing
- Split-brain resolution

---

### **Phase 7: API** (Week 8)

**Goal**: HTTP interface for management

**Deliverables**:
- [ ] REST API (CRUD)
- [ ] WebSocket (real-time)
- [ ] API documentation
- [ ] Postman collection

**Key Learning**:
- Pekko HTTP
- REST design
- WebSockets
- API security

---

### **Phase 8: Projections** (Week 9)

**Goal**: CQRS read models

**Deliverables**:
- [ ] Status projection
- [ ] Metrics projection
- [ ] Search index
- [ ] Query API

**Key Learning**:
- Pekko Projections
- CQRS
- Read models
- Eventual consistency

---

### **Phase 9: Observability** (Week 10)

**Goal**: Production-ready monitoring

**Deliverables**:
- [ ] Metrics (Kamon/Prometheus)
- [ ] Tracing (OpenTelemetry)
- [ ] Dashboards (Grafana)
- [ ] Alerting

**Key Learning**:
- Observability patterns
- Metrics design
- Distributed tracing
- Alerting strategies

---

### **Phase 10: Production Hardening** (Week 11-12)

**Goal**: Production deployment

**Deliverables**:
- [ ] Docker images
- [ ] Kubernetes deployment
- [ ] CI/CD pipeline
- [ ] Load testing
- [ ] Chaos engineering

**Key Learning**:
- Containerization
- Orchestration
- Deployment strategies
- Resilience testing

---

## 📊 **Technology Stack**

### **Core**
- Apache Pekko 1.1.x (Actor system, Persistence, Cluster)
- Scala 2.13.x
- SBT 1.9.x

### **Persistence**
- Cassandra 4.x (Event store)
- PostgreSQL 15 (Read models)
- Redis 7.x (Caching)

### **Streaming**
- Pekko Streams (Stream processing)
- Alpakka Kafka (Kafka integration)
- Apache Kafka 3.x (Message broker)

### **API**
- Pekko HTTP (REST API)
- WebSockets (Real-time updates)

### **Observability**
- Kamon (Metrics)
- Prometheus (Metrics storage)
- Grafana (Dashboards)
- OpenTelemetry (Tracing)

### **Deployment**
- Docker (Containerization)
- Kubernetes (Orchestration)
- Helm (K8s package manager)

---

## 🎯 **Success Criteria**

By the end of this project, you will have:

✅ **Production-ready platform** that:
- Processes millions of events per day
- Scales horizontally across cluster
- Has complete audit trail
- Provides exactly-once semantics
- Self-heals from failures

✅ **Deep understanding** of:
- Event Sourcing at scale
- CQRS pattern
- Distributed systems
- Stream processing
- Cluster sharding
- Production operations

✅ **Portfolio project** demonstrating:
- System design skills
- Scala expertise
- Distributed systems knowledge
- Production-grade code
- Comprehensive testing

---

## 📝 **Next Steps**

1. **Complete Phase 1**: Implement PipelineAggregate
2. **Review architecture**: Understand all modules
3. **Plan Phase 2**: Design source actors
4. **Set milestones**: Weekly goals
5. **Start coding**: Iterate and learn!

---

**Ready to build something real?** Let's start with Phase 1! 🚀
