# DataFlow Platform

> **A production-grade, distributed data pipeline orchestration platform built with Apache Pekko (Scala)**

[![Scala](https://img.shields.io/badge/Scala-2.13.16-red.svg)](https://www.scala-lang.org/)
[![Apache Pekko](https://img.shields.io/badge/Apache%20Pekko-1.1.2-blue.svg)](https://pekko.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

---

## 🎯 **Project Vision**

DataFlow Platform is a **horizontally scalable, event-sourced data pipeline orchestration system** inspired by Apache NiFi, built to demonstrate production-grade distributed systems patterns using:

- **Event Sourcing** with Apache Pekko Persistence
- **CQRS** (Command Query Responsibility Segregation)
- **Cluster Sharding** for horizontal scalability
- **Exactly-once processing** semantics
- **Complete audit trail** via event sourcing
- **Stream processing** with Pekko Streams

---

## 🏗️ **Architecture Overview**

```
┌─────────────────────────────────────────────────────────────────────┐
│                         API Layer (HTTP/WebSocket)                  │
│                   Pipeline Management & Monitoring                  │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────┐
│                    Coordinator (Cluster Singleton)                  │
│          Pipeline Registry • Resource Allocation • Health           │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
      ┌────────────────────────┴────────────────────────┐
      │                                                  │
┌─────▼─────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐
│  Node 1   │  │ Node 2  │  │ Node 3  │  │  Node N   │
│ Pipeline  │  │Pipeline │  │Pipeline │  │  Pipeline │
│   Actor   │  │ Actor   │  │ Actor   │  │   Actor   │
│           │  │         │  │         │  │           │
│ Source    │  │ Source  │  │ Source  │  │  Source   │
│    ↓      │  │    ↓    │  │    ↓    │  │     ↓     │
│Transform  │  │Transform│  │Transform│  │ Transform │
│    ↓      │  │    ↓    │  │    ↓    │  │     ↓     │
│  Sink     │  │  Sink   │  │  Sink   │  │   Sink    │
└───────────┘  └─────────┘  └─────────┘  └───────────┘
      │              │            │              │
      └──────────────┴────────────┴──────────────┘
                     │
┌────────────────────▼────────────────────────────────────────────────┐
│                      Persistence Layer                              │
│  Cassandra (Events) • Kafka (Streaming) • PostgreSQL (Read Models) │
│                   Redis (Cache) • Elasticsearch (Search)            │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 📦 **Module Structure**

| Module | Purpose | Status |
|--------|---------|--------|
| **dataflow-core** | Event-sourced aggregates, domain models, cluster setup | ✅ Implemented |
| **dataflow-sources** | Data ingestion (Kafka, Files, APIs, Databases) | 🚧 Planned |
| **dataflow-transforms** | Data transformation (Filter, Map, Aggregate, Join) | 🚧 Planned |
| **dataflow-sinks** | Data output (Kafka, Files, Databases, Elasticsearch) | 🚧 Planned |
| **dataflow-api** | HTTP REST API and WebSocket management interface | 🚧 Planned |
| **dataflow-projections** | CQRS read models (Status, Metrics, Audit logs) | 🚧 Planned |

---

## 🚀 **Quick Start**

### **Prerequisites**

- **JDK 11+** (recommended: Temurin 11 or 17)
- **SBT 1.11.0+**
- **Docker & Docker Compose** (for local infrastructure)
- **Git**

### **1. Clone the Repository**

```bash
git clone <repository-url>
cd dataflow-platform
```

### **2. Start Infrastructure Services**

```bash
cd docker
docker-compose up -d
```

This starts:
- **Cassandra** (Event Store) - Port 9042
- **Kafka + Zookeeper** (Streaming) - Port 9093
- **Kafka UI** - http://localhost:8090
- **Elasticsearch** (Search) - Port 9200
- **Logstash** (Log Processing) - Ports 5000, 5044
- **Kibana** (Visualization) - http://localhost:5601
- **PostgreSQL** (Read Models) - Port 5432
- **Redis** (Cache) - Port 6379

**Verify services:**
```bash
docker-compose ps
```

### **3. Compile the Project**

```bash
sbt compile
```

### **4. Run Tests** (Coming Soon)

```bash
sbt test
```

### **5. Build Docker Images** (Coming Soon)

```bash
sbt docker:publishLocal
```

---

## 📚 **Documentation**

| Document | Description |
|----------|-------------|
| [ARCHITECTURE_AND_ROADMAP.md](docs/ARCHITECTURE_AND_ROADMAP.md) | Complete architecture guide with 10-phase implementation roadmap |
| [CORE_ANALYSIS_AND_REFACTORING.md](docs/CORE_ANALYSIS_AND_REFACTORING.md) | Core module analysis with refactoring recommendations |
| [SPRINT_PLANNING.md](docs/SPRINT_PLANNING.md) | Sprint-based development plan with tasks and timelines |
| [dataflow-core/README.md](dataflow-core/README.md) | Core module documentation |

---

## 💡 **Key Features**

### **Event Sourcing**
- All state changes persisted as immutable events
- Complete audit trail for compliance and debugging
- Time-travel queries and replay capabilities
- Event tagging for projection building

### **CQRS Pattern**
- Separate write (commands) and read (projections) models
- Optimized queries without impacting write performance
- Multiple read models for different use cases

### **Cluster Sharding**
- Automatic distribution of pipeline actors across cluster nodes
- Horizontal scalability - add nodes to increase capacity
- Automatic rebalancing on node failure
- Split-brain resolver for network partitions

### **Exactly-Once Processing**
- Checkpoint management for source offset tracking
- Idempotent batch processing (duplicate detection)
- Transactional guarantees end-to-end

### **Observability**
- Structured logging with Logback
- Metrics export (Kamon/Prometheus ready)
- Distributed tracing support
- Real-time monitoring dashboards

---

## 🛠️ **Technology Stack**

### **Core**
- **Apache Pekko 1.1.2** - Actor system, persistence, clustering
- **Scala 2.13.16** - Programming language
- **SBT 1.11.0** - Build tool

### **Persistence**
- **Cassandra 4.1** - Event store (journal & snapshots)
- **PostgreSQL 15** - Read models and projections
- **Redis 7** - Caching layer

### **Streaming**
- **Apache Kafka 3.x** - Message broker
- **Pekko Streams** - Stream processing
- **Pekko Connectors** - Source/sink connectors

### **Observability**
- **ELK Stack** (Elasticsearch, Logstash, Kibana)
- **Kamon** (metrics collection - planned)
- **Prometheus** (metrics storage - planned)
- **Grafana** (dashboards - planned)

### **Development**
- **Docker** - Containerization
- **TestContainers** - Integration testing
- **ScalaTest** - Unit testing
- **Scalafmt** - Code formatting
- **Scoverage** - Code coverage

---

## 🗓️ **Development Roadmap**

### **Phase 1: Foundation** (Current - Weeks 1-2) ⭐
- [x] Project structure and build configuration
- [x] Domain models (Commands, Events, State)
- [x] PipelineAggregate with event sourcing
- [x] Cassandra persistence setup
- [x] Docker infrastructure setup
- [ ] Comprehensive unit tests
- [ ] Integration tests
- [ ] Documentation

### **Phase 2: Sources** (Week 3)
- [ ] FileSource (CSV, JSON)
- [ ] KafkaSource
- [ ] Source actor abstraction
- [ ] Backpressure handling
- [ ] Checkpoint management

### **Phase 3: Transforms** (Week 4)
- [ ] FilterTransform
- [ ] MapTransform
- [ ] Transform composition
- [ ] Error handling

### **Phase 4: Sinks** (Week 5)
- [ ] FileSink
- [ ] KafkaSink
- [ ] CassandraSink
- [ ] Batching logic

### **Phase 5-10: Advanced Features** (Weeks 6-12)
- Integration testing
- Cluster sharding
- HTTP API
- CQRS projections
- Observability
- Production hardening

📖 **Full roadmap:** [ARCHITECTURE_AND_ROADMAP.md](docs/ARCHITECTURE_AND_ROADMAP.md)

---

## 🧪 **Testing**

### **Run Unit Tests**
```bash
sbt test
```

### **Run Integration Tests**
```bash
sbt it:test
```

### **Run Tests with Coverage**
```bash
sbt clean coverage test coverageReport
```

### **Run Multi-Node Cluster Tests**
```bash
sbt multi-jvm:test
```

### **Run Benchmarks**
```bash
sbt jmh:run
```

---

## 📊 **Monitoring & Observability**

### **Access Dashboards**

| Service | URL | Purpose |
|---------|-----|---------|
| Kafka UI | http://localhost:8090 | Monitor Kafka topics, consumers, messages |
| Kibana | http://localhost:5601 | Log analysis and visualization |
| Elasticsearch | http://localhost:9200 | Search and analytics |

### **Service Health Checks**

```bash
# Cassandra
docker exec -it dataflow-cassandra cqlsh -e "DESCRIBE KEYSPACES"

# Kafka
docker exec -it dataflow-kafka kafka-topics --list --bootstrap-server localhost:9092

# Elasticsearch
curl http://localhost:9200/_cluster/health?pretty

# PostgreSQL
docker exec -it dataflow-postgres psql -U dataflow -c "\l"
```

---

## 🔧 **Development Workflow**

### **Code Formatting**
```bash
sbt scalafmt        # Format code
sbt scalafmtCheck   # Check formatting
```

### **Dependency Management**
```bash
sbt dependencyTree      # View dependency tree
sbt dependencyUpdates   # Check for updates
```

### **Build Artifacts**
```bash
sbt assembly            # Create fat JAR
sbt docker:publishLocal # Build Docker image
```

### **Release**
```bash
sbt release             # Execute release process
```

---

## 🤝 **Contributing**

This is a learning project demonstrating production-grade distributed systems patterns. Contributions, suggestions, and feedback are welcome!

### **Development Guidelines**
1. Follow existing code style and patterns
2. Write tests for new functionality
3. Update documentation
4. Use feature branches
5. Submit pull requests

---

## 📖 **Learning Resources**

### **Event Sourcing & CQRS**
- "Implementing Domain-Driven Design" by Vaughn Vernon
- "Reactive Design Patterns" by Roland Kuhn

### **Apache Pekko**
- [Official Pekko Documentation](https://pekko.apache.org/docs/pekko/current/)
- "Akka in Action" (patterns apply to Pekko)

### **Distributed Systems**
- "Designing Data-Intensive Applications" by Martin Kleppmann
- "Release It!" by Michael Nygard

---

## 📝 **License**

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.

---

## 🎓 **Project Goals**

This project serves as:

1. **Learning Platform**: Hands-on experience with event sourcing, CQRS, and distributed systems
2. **Portfolio Piece**: Demonstrates advanced Scala and distributed systems skills
3. **Reference Implementation**: Production-grade patterns for building scalable data platforms
4. **Teaching Tool**: Well-documented codebase for others to learn from

---

## 🔗 **Project Status**

**Current Phase:** Phase 1 - Foundation

**Last Updated:** 2025-10-31

**Next Milestone:** Complete Phase 1 with comprehensive testing and documentation

---

## 📧 **Contact & Support**

For questions, issues, or discussions:
- Open an issue in the repository
- Check existing documentation
- Review the architecture guide

---

**Happy Data Processing! 🚀**
