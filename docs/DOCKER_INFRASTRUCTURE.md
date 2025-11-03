# DataFlow Platform - Docker Infrastructure Guide

An educational guide to understanding and using the Docker-based local development environment for the DataFlow Platform.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Services Deep Dive](#services-deep-dive)
  - [Cassandra - Event Store](#cassandra---event-store)
  - [Kafka & Zookeeper - Message Streaming](#kafka--zookeeper---message-streaming)
  - [Elasticsearch, Logstash, Kibana (ELK Stack)](#elasticsearch-logstash-kibana-elk-stack)
  - [PostgreSQL - Relational Database](#postgresql---relational-database)
  - [Redis - Caching Layer](#redis---caching-layer)
  - [Prometheus & Grafana - Monitoring](#prometheus--grafana---monitoring)
- [Getting Started](#getting-started)
- [Service Interactions](#service-interactions)
- [Usage Patterns](#usage-patterns)
- [Monitoring & Observability](#monitoring--observability)
- [Data Persistence](#data-persistence)
- [Networking](#networking)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)
- [Advanced Topics](#advanced-topics)

---

## Overview

The DataFlow Platform uses a **microservices-oriented architecture** with multiple backing services orchestrated via Docker Compose. This setup provides a complete local development environment that mirrors production architecture.

### Why Docker?

Docker provides several key benefits for the DataFlow Platform:

1. **Environment Consistency**: Every developer has identical infrastructure
2. **Service Isolation**: Each service runs in its own container with specific resource limits
3. **Easy Setup**: One command to start the entire stack
4. **Production Parity**: Local environment closely matches production
5. **Resource Management**: Control memory, CPU, and storage per service
6. **Disposability**: Easy to tear down and rebuild without affecting host system

### Technology Stack Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    DataFlow Platform Application                 │
│              (Scala/Pekko - Event Sourced CQRS)                 │
└─────────────────────────────────────────────────────────────────┘
         │              │              │              │
         ▼              ▼              ▼              ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│  Cassandra   │ │    Kafka     │ │ Elasticsearch│ │  PostgreSQL  │
│ Event Store  │ │  Streaming   │ │    Logs      │ │ Read Models  │
└──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
         │              │              │              │
         └──────────────┴──────────────┴──────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                       Monitoring Layer                           │
│         Prometheus (Metrics) + Grafana (Visualization)          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Architecture

### Service Categories

The infrastructure is organized into **four logical layers**:

#### 1. **Persistence Layer**
- **Cassandra**: Event sourcing journal and snapshots
- **PostgreSQL**: Read models and projections
- **Redis**: Caching and temporary data

#### 2. **Streaming Layer**
- **Zookeeper**: Kafka coordination
- **Kafka**: Message broker and event streaming
- **Kafka UI**: Visual management interface

#### 3. **Observability Layer**
- **Elasticsearch**: Log storage and search
- **Logstash**: Log processing pipeline
- **Kibana**: Log visualization
- **Prometheus**: Metrics collection
- **Grafana**: Metrics visualization and dashboards

#### 4. **Application Layer**
- DataFlow Platform (runs on host or in container)

### Data Flow Architecture

```
Application Events → Cassandra (Event Store)
                  ↓
Application Logs → Logstash → Elasticsearch → Kibana
                  ↓
Application Metrics → Prometheus → Grafana
                  ↓
Pipeline Data → Kafka → Source Connectors → Processing → Sink Connectors
```

---

## Services Deep Dive

### Cassandra - Event Store

**Image**: `cassandra:4.1`
**Ports**: 9042 (CQL), 7000 (inter-node), 7199 (JMX)
**Purpose**: Primary event store for Event Sourcing

#### What is Cassandra?

Apache Cassandra is a **distributed NoSQL database** designed for handling massive amounts of write-heavy workloads with **no single point of failure**.

#### Why Cassandra for Event Sourcing?

Event Sourcing requires a database that can:

1. **Handle high write throughput**: Events are append-only and never updated
2. **Provide durability**: Events must never be lost
3. **Scale horizontally**: Add more nodes as event volume grows
4. **Time-series optimization**: Events are naturally time-ordered
5. **Fast reads by partition key**: Retrieve all events for a specific entity

#### Configuration Explained

```yaml
environment:
  CASSANDRA_CLUSTER_NAME: DataFlowCluster    # Logical cluster name
  CASSANDRA_DC: datacenter1                   # Data center (for multi-DC setups)
  CASSANDRA_RACK: rack1                       # Rack (for rack-aware placement)
  MAX_HEAP_SIZE: 512M                         # JVM heap for local dev
  HEAP_NEWSIZE: 128M                          # Young generation size
```

**Memory Settings**: In production, Cassandra typically uses 8-32GB heap. For local development, we use 512MB to conserve resources.

#### Cassandra in DataFlow Platform

The DataFlow Platform uses Cassandra to store:

1. **Pipeline Events**: All state changes to pipeline aggregates
2. **Coordinator Events**: All state changes to coordinator aggregate
3. **Snapshots**: Periodic snapshots for faster recovery
4. **Offset Store**: For Pekko Projections

#### Schema Structure

```cql
-- Journal table (stores all events)
CREATE TABLE akka.messages (
  persistence_id text,
  partition_nr bigint,
  sequence_nr bigint,
  timestamp timeuuid,
  timebucket text,
  writer_uuid text,
  ser_id int,
  ser_manifest text,
  event_manifest text,
  event blob,
  meta_ser_id int,
  meta_ser_manifest text,
  meta blob,
  tags set<text>,
  PRIMARY KEY ((persistence_id, partition_nr), sequence_nr)
);

-- Snapshot table (stores aggregate snapshots)
CREATE TABLE akka.snapshots (
  persistence_id text,
  sequence_nr bigint,
  timestamp bigint,
  ser_id int,
  ser_manifest text,
  snapshot_data blob,
  meta_ser_id int,
  meta_ser_manifest text,
  meta blob,
  PRIMARY KEY (persistence_id, sequence_nr)
);
```

#### Connecting to Cassandra

```bash
# From Docker container
docker exec -it dataflow-cassandra cqlsh

# View keyspaces
cqlsh> DESCRIBE KEYSPACES;

# Use akka keyspace
cqlsh> USE akka;

# View tables
cqlsh> DESCRIBE TABLES;

# Query events for a pipeline
cqlsh> SELECT persistence_id, sequence_nr, event_manifest
       FROM messages
       WHERE persistence_id = 'pipeline-my-pipeline-id'
       LIMIT 10;
```

#### Health Check

```bash
# Check if Cassandra is ready
docker exec dataflow-cassandra cqlsh -e "describe keyspaces"

# View Cassandra logs
docker logs dataflow-cassandra

# Check cluster status
docker exec dataflow-cassandra nodetool status
```

---

### Kafka & Zookeeper - Message Streaming

**Kafka Image**: `confluentinc/cp-kafka:7.6.0`
**Zookeeper Image**: `confluentinc/cp-zookeeper:7.6.0`
**Kafka Ports**: 9092 (internal), 9093 (external), 9101 (JMX)
**Zookeeper Ports**: 2181

#### What is Kafka?

Apache Kafka is a **distributed event streaming platform** designed for high-throughput, fault-tolerant, real-time data pipelines.

#### Why Kafka?

Kafka provides critical capabilities for data pipelines:

1. **Decoupling**: Producers and consumers are independent
2. **Buffering**: Absorbs traffic spikes between systems
3. **Replay**: Consumers can re-read historical data
4. **Fan-out**: Multiple consumers can read same data
5. **Durability**: Messages are persisted to disk
6. **Ordering**: Messages within a partition are ordered

#### Kafka Concepts

##### Topics
A **topic** is a category or feed name to which records are published.

```bash
# Example topics in DataFlow Platform
- user-events          # User activity events
- orders               # Order transactions
- pipeline-metrics     # Pipeline performance metrics
- dead-letter-queue    # Failed records
```

##### Partitions
Topics are divided into **partitions** for parallelism.

```
Topic: user-events (3 partitions)
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ Partition 0  │ │ Partition 1  │ │ Partition 2  │
│ [msg1, msg3] │ │ [msg2, msg5] │ │ [msg4, msg6] │
└──────────────┘ └──────────────┘ └──────────────┘
```

Records with the same key go to the same partition, ensuring ordering per key.

##### Consumer Groups
A **consumer group** is a set of consumers that cooperate to consume data from topics.

```
Topic Partitions:    [P0] [P1] [P2] [P3]
                      │    │    │    │
Consumer Group:      [C1] [C2] [C3] [C3]
                   (Partitions automatically balanced)
```

#### Configuration Explained

```yaml
environment:
  # Listeners: How Kafka is accessed
  KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,PLAINTEXT_HOST://0.0.0.0:9093
  # Advertised: What clients see
  KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:9093

  # Why two listeners?
  # - PLAINTEXT (9092): For inter-container communication
  # - PLAINTEXT_HOST (9093): For host machine access

  # Replication factors (1 for local dev, 3+ for production)
  KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  # Auto-create topics when first used
  KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"

  # Default partitions per topic
  KAFKA_NUM_PARTITIONS: 3
```

#### Kafka in DataFlow Platform

The DataFlow Platform uses Kafka for:

1. **Source Connector**: Ingest data from Kafka topics
2. **Sink Connector**: Output processed data to Kafka
3. **Event Streaming**: Stream events between pipelines
4. **Logging**: Application logs → Kafka → Logstash → Elasticsearch

#### Using Kafka

##### Create a Topic

```bash
# Create topic with 3 partitions
docker exec -it dataflow-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --create \
  --topic my-test-topic \
  --partitions 3 \
  --replication-factor 1
```

##### List Topics

```bash
docker exec -it dataflow-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --list
```

##### Produce Messages

```bash
# Console producer
docker exec -it dataflow-kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic my-test-topic

# Type messages, press Enter after each
> {"id": "1", "name": "Alice"}
> {"id": "2", "name": "Bob"}
```

##### Consume Messages

```bash
# Console consumer (from beginning)
docker exec -it dataflow-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic my-test-topic \
  --from-beginning
```

##### Describe Topic

```bash
docker exec -it dataflow-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic my-test-topic
```

##### View Consumer Groups

```bash
docker exec -it dataflow-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --list
```

##### Check Consumer Lag

```bash
docker exec -it dataflow-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --group my-consumer-group
```

#### Kafka UI

Access at: **http://localhost:8090**

The Kafka UI provides:
- Visual topic management
- Message browsing
- Consumer group monitoring
- Broker status
- Topic configuration

---

### Elasticsearch, Logstash, Kibana (ELK Stack)

**Elasticsearch**: `8.12.0` (Port 9200, 9300)
**Logstash**: `8.12.0` (Port 5000, 5044, 9600)
**Kibana**: `8.12.0` (Port 5601)

#### What is the ELK Stack?

The ELK Stack is a popular open-source solution for **centralized logging**:

- **Elasticsearch**: Search and analytics engine
- **Logstash**: Log processing pipeline
- **Kibana**: Visualization and exploration UI

#### Why ELK for Logging?

Traditional file-based logging has limitations:

- **Scattered logs**: Logs across multiple machines
- **No search**: Hard to find specific events
- **No correlation**: Can't correlate logs across services
- **No visualization**: Hard to spot trends

ELK solves these problems:

1. **Centralization**: All logs in one place
2. **Full-text search**: Find any log event instantly
3. **Structured data**: JSON-based log structure
4. **Real-time**: See logs as they happen
5. **Visualization**: Charts, graphs, dashboards

#### Elasticsearch Deep Dive

**Purpose**: Store, search, and analyze log data

Elasticsearch is a **distributed search engine** built on Apache Lucene.

##### Key Concepts

**Index**: Similar to a database, stores documents
```
dataflow-logs-2024.01.01
dataflow-logs-2024.01.02
...
```

**Document**: A single log entry (JSON)
```json
{
  "@timestamp": "2024-01-01T10:00:00Z",
  "log_level": "INFO",
  "pipeline_id": "my-pipeline",
  "message": "Pipeline started successfully",
  "total_records": 1000,
  "environment": "development"
}
```

**Mapping**: Schema definition for documents (similar to database schema)

##### Configuration Explained

```yaml
environment:
  - discovery.type=single-node        # Single node (no cluster)
  - cluster.name=dataflow-cluster     # Cluster identifier
  - ES_JAVA_OPTS=-Xms512m -Xmx512m   # JVM heap (512MB for local)
  - xpack.security.enabled=false      # Disable security for local dev
```

##### Using Elasticsearch

```bash
# Check cluster health
curl http://localhost:9200/_cluster/health?pretty

# List indices
curl http://localhost:9200/_cat/indices?v

# Search logs
curl -X GET "http://localhost:9200/dataflow-logs-*/_search?pretty" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "match": {
        "log_level": "ERROR"
      }
    }
  }'

# Get document by ID
curl http://localhost:9200/dataflow-logs-2024.01.01/_doc/abc123?pretty

# Delete old indices (free space)
curl -X DELETE http://localhost:9200/dataflow-logs-2024.01.01
```

#### Logstash Deep Dive

**Purpose**: Process, transform, and route logs to Elasticsearch

Logstash is a **data processing pipeline** with three stages:

##### 1. Input (Ingest)

Receives logs from multiple sources:

```ruby
input {
  # TCP socket (application sends JSON logs)
  tcp {
    port => 5000
    codec => json_lines
  }

  # Beats (Filebeat, Metricbeat)
  beats {
    port => 5044
  }

  # Kafka topics
  kafka {
    bootstrap_servers => "kafka:9092"
    topics => ["application-logs", "pipeline-metrics"]
  }
}
```

##### 2. Filter (Transform)

Enriches and transforms logs:

```ruby
filter {
  # Parse JSON
  json {
    source => "message"
    target => "parsed"
  }

  # Extract timestamp
  date {
    match => ["[parsed][timestamp]", "ISO8601"]
    target => "@timestamp"
  }

  # Add metadata
  mutate {
    add_field => {
      "environment" => "development"
      "platform" => "dataflow-platform"
    }
  }

  # Extract log level
  grok {
    match => { "message" => "%{LOGLEVEL:log_level}" }
  }
}
```

##### 3. Output (Send)

Sends processed logs to destinations:

```ruby
output {
  # Primary: Elasticsearch
  elasticsearch {
    hosts => ["elasticsearch:9200"]
    index => "dataflow-logs-%{+YYYY.MM.dd}"  # Daily indices
  }

  # Debug: Console (development only)
  stdout {
    codec => rubydebug
  }
}
```

##### Sending Logs to Logstash

From your application:

```scala
// Using TCP socket (port 5000)
import java.net.Socket
import java.io.PrintWriter

val socket = new Socket("localhost", 5000)
val writer = new PrintWriter(socket.getOutputStream, true)

// Send JSON log
val logEntry = s"""{"timestamp":"2024-01-01T10:00:00Z","log_level":"INFO","message":"Hello"}"""
writer.println(logEntry)
```

Or configure Logback appender:

```xml
<appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
  <destination>localhost:5000</destination>
  <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
</appender>
```

#### Kibana Deep Dive

**Purpose**: Visualize and explore logs

Access at: **http://localhost:5601**

##### Getting Started with Kibana

1. **Create Index Pattern**
   - Navigate to: Stack Management → Index Patterns
   - Pattern: `dataflow-logs-*` (matches all daily indices)
   - Time field: `@timestamp`

2. **Discover Logs**
   - Navigate to: Discover
   - Search: `log_level:ERROR` or `pipeline_id:"my-pipeline"`
   - Filter by time range: Last 15 minutes, Last hour, etc.

3. **Create Visualizations**
   - Bar chart: Logs over time
   - Pie chart: Log level distribution
   - Line chart: Pipeline throughput
   - Data table: Top errors

4. **Build Dashboards**
   - Combine multiple visualizations
   - Add filters and drilldowns
   - Save and share

##### KQL (Kibana Query Language)

```
# Find errors
log_level:ERROR

# Find specific pipeline
pipeline_id:"user-pipeline"

# Find errors in specific pipeline
log_level:ERROR AND pipeline_id:"user-pipeline"

# Find high record counts
total_records >= 1000

# Find specific time range
@timestamp >= "2024-01-01" AND @timestamp < "2024-01-02"

# Wildcard search
message:*connection*timeout*
```

---

### PostgreSQL - Relational Database

**Image**: `postgres:15-alpine`
**Port**: 5432
**Purpose**: Read models and projections

#### What is PostgreSQL?

PostgreSQL is a powerful **open-source relational database** with strong ACID guarantees and rich query capabilities.

#### Why PostgreSQL in Event Sourcing?

In CQRS/Event Sourcing architecture:

- **Write Side**: Cassandra (event store)
- **Read Side**: PostgreSQL (read models / projections)

PostgreSQL is ideal for read models because:

1. **Complex Queries**: JOINs, aggregations, subqueries
2. **ACID Transactions**: Consistent read model updates
3. **Indexes**: Fast lookups on any column
4. **JSON Support**: Store flexible data structures
5. **Materialized Views**: Pre-computed aggregations

#### PostgreSQL in DataFlow Platform

Used for:

1. **Pipeline Status View**: Current state of all pipelines
2. **Metrics Aggregation**: Historical metrics and statistics
3. **Audit Log**: User actions and changes
4. **Search Index**: Searchable pipeline catalog
5. **Grafana Storage**: Dashboard configurations

#### Configuration Explained

```yaml
environment:
  POSTGRES_DB: dataflow          # Database name
  POSTGRES_USER: dataflow        # Username
  POSTGRES_PASSWORD: dataflow    # Password (change in production!)
  PGDATA: /var/lib/postgresql/data/pgdata  # Data directory
```

#### Using PostgreSQL

```bash
# Connect via psql
docker exec -it dataflow-postgres psql -U dataflow -d dataflow

# List databases
\l

# List tables
\dt

# Describe table
\d pipeline_status

# Run query
SELECT * FROM pipeline_status WHERE status = 'running';

# Export query result to CSV
\copy (SELECT * FROM pipeline_status) TO '/tmp/pipelines.csv' CSV HEADER;
```

#### Schema Example

```sql
-- Pipeline status read model
CREATE TABLE pipeline_status (
  pipeline_id VARCHAR(255) PRIMARY KEY,
  pipeline_name VARCHAR(255) NOT NULL,
  status VARCHAR(50) NOT NULL,
  source_type VARCHAR(50),
  sink_type VARCHAR(50),
  total_records BIGINT DEFAULT 0,
  total_batches BIGINT DEFAULT 0,
  total_failures BIGINT DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_pipeline_status ON pipeline_status(status);
CREATE INDEX idx_pipeline_updated ON pipeline_status(updated_at DESC);

-- Pipeline metrics aggregation
CREATE TABLE pipeline_metrics_hourly (
  pipeline_id VARCHAR(255),
  hour TIMESTAMP,
  records_processed BIGINT,
  avg_batch_size DOUBLE PRECISION,
  error_rate DOUBLE PRECISION,
  PRIMARY KEY (pipeline_id, hour)
);
```

---

### Redis - Caching Layer

**Image**: `redis:7-alpine`
**Port**: 6379
**Purpose**: Caching and temporary data

#### What is Redis?

Redis is an in-memory **key-value store** known for blazing-fast performance.

#### Why Redis?

Redis provides:

1. **Speed**: Sub-millisecond latency (data in RAM)
2. **Data Structures**: Strings, lists, sets, sorted sets, hashes
3. **TTL**: Automatic expiration
4. **Pub/Sub**: Real-time messaging
5. **Atomic Operations**: Thread-safe increments, etc.

#### Redis in DataFlow Platform

Used for:

1. **Cache**: Frequently accessed read models
2. **Rate Limiting**: Track request rates per user/pipeline
3. **Session Storage**: User session data
4. **Real-time Counters**: Active pipelines, current throughput
5. **Distributed Locks**: Coordinate across cluster nodes
6. **Pub/Sub**: Real-time notifications

#### Configuration Explained

```yaml
command: redis-server
  --appendonly yes              # Enable AOF persistence
  --maxmemory 256mb            # Maximum memory
  --maxmemory-policy allkeys-lru  # Eviction policy (Least Recently Used)
```

**Persistence**:
- **AOF (Append-Only File)**: Logs every write operation
- **RDB (Snapshot)**: Periodic full snapshots

**Eviction Policy**:
- `allkeys-lru`: When memory limit reached, evict least recently used keys

#### Using Redis

```bash
# Connect via redis-cli
docker exec -it dataflow-redis redis-cli

# Set key-value
127.0.0.1:6379> SET pipeline:my-pipeline:status "running"
127.0.0.1:6379> GET pipeline:my-pipeline:status

# Set with expiration (TTL)
127.0.0.1:6379> SETEX session:user123 3600 "user-data"
127.0.0.1:6379> TTL session:user123

# Increment counter
127.0.0.1:6379> INCR pipeline:my-pipeline:records_processed

# Hash (object storage)
127.0.0.1:6379> HSET pipeline:my-pipeline name "User Pipeline" status "running"
127.0.0.1:6379> HGETALL pipeline:my-pipeline

# List (queue)
127.0.0.1:6379> LPUSH queue:tasks "task1" "task2"
127.0.0.1:6379> RPOP queue:tasks

# Sorted set (leaderboard)
127.0.0.1:6379> ZADD pipeline:throughput 1000 "pipeline1" 500 "pipeline2"
127.0.0.1:6379> ZREVRANGE pipeline:throughput 0 10 WITHSCORES

# Pub/Sub
127.0.0.1:6379> SUBSCRIBE pipeline:events
127.0.0.1:6379> PUBLISH pipeline:events "Pipeline started"
```

---

### Prometheus & Grafana - Monitoring

**Prometheus**: `v2.50.1` (Port 9090)
**Grafana**: `10.3.3` (Port 3000)

#### What is Prometheus?

Prometheus is a **time-series database** and **monitoring system** designed for reliability and simplicity.

#### Why Prometheus?

1. **Pull Model**: Prometheus scrapes metrics from targets
2. **Time-Series Data**: Optimized for numeric data over time
3. **Powerful Queries**: PromQL query language
4. **Alerting**: Built-in alerting rules
5. **Service Discovery**: Automatically discover targets

#### Prometheus Concepts

##### Metrics Types

1. **Counter**: Only increases (e.g., total requests)
   ```
   pipeline_records_processed_total{pipeline="my-pipeline"} 1000
   ```

2. **Gauge**: Can go up or down (e.g., active connections)
   ```
   pipeline_active_connections{pipeline="my-pipeline"} 5
   ```

3. **Histogram**: Distribution of values (e.g., request latency)
   ```
   pipeline_batch_duration_seconds_bucket{le="1.0"} 100
   pipeline_batch_duration_seconds_bucket{le="5.0"} 150
   ```

4. **Summary**: Similar to histogram, with percentiles
   ```
   pipeline_batch_duration_seconds{quantile="0.95"} 2.3
   ```

##### Scrape Configuration

```yaml
scrape_configs:
  - job_name: 'dataflow-platform'
    metrics_path: '/metrics'           # Where to scrape
    scrape_interval: 10s               # How often
    static_configs:
      - targets:
          - 'host.docker.internal:9095'  # Your app (Kamon endpoint)
```

#### Using Prometheus

Access at: **http://localhost:9090**

##### PromQL Examples

```promql
# Current value
pipeline_records_processed_total{pipeline="my-pipeline"}

# Rate (records per second)
rate(pipeline_records_processed_total[5m])

# Sum across all pipelines
sum(rate(pipeline_records_processed_total[5m]))

# Average batch size
avg(pipeline_batch_size)

# Error rate
rate(pipeline_errors_total[5m]) / rate(pipeline_records_processed_total[5m])

# 95th percentile latency
histogram_quantile(0.95, rate(pipeline_batch_duration_seconds_bucket[5m]))
```

##### Viewing Targets

Navigate to: **Status → Targets**

Shows all scrape targets and their health.

#### What is Grafana?

Grafana is a **visualization platform** that creates beautiful dashboards from data sources like Prometheus.

#### Why Grafana?

1. **Beautiful Dashboards**: Professional-looking visualizations
2. **Multiple Data Sources**: Prometheus, PostgreSQL, Elasticsearch
3. **Alerting**: Visual alert rules with notifications
4. **Sharing**: Export/import dashboards
5. **Variables**: Dynamic dashboards with dropdowns

#### Using Grafana

Access at: **http://localhost:3000**
**Credentials**: admin / admin

##### Create Your First Dashboard

1. **Add Panel**
   - Click "+ Create" → Dashboard → Add new panel

2. **Query Prometheus**
   ```promql
   rate(pipeline_records_processed_total[5m])
   ```

3. **Choose Visualization**
   - Time series (line chart)
   - Bar chart
   - Gauge
   - Stat (single number)

4. **Customize**
   - Title: "Records Processed Per Second"
   - Legend: Show values
   - Axes: Labels, units

5. **Save Dashboard**

##### Dashboard Variables

Create dynamic dashboards with variables:

```
Name: pipeline
Type: Query
Query: label_values(pipeline_records_processed_total, pipeline)
```

Then use in queries:
```promql
rate(pipeline_records_processed_total{pipeline="$pipeline"}[5m])
```

##### Sample Dashboard Panels

1. **Total Records Processed**
   - Type: Stat
   - Query: `sum(pipeline_records_processed_total)`

2. **Processing Rate (Records/sec)**
   - Type: Time series
   - Query: `rate(pipeline_records_processed_total[5m])`

3. **Active Pipelines**
   - Type: Gauge
   - Query: `count(pipeline_status{status="running"})`

4. **Error Rate**
   - Type: Time series
   - Query: `rate(pipeline_errors_total[5m])`

5. **95th Percentile Latency**
   - Type: Time series
   - Query: `histogram_quantile(0.95, rate(pipeline_batch_duration_seconds_bucket[5m]))`

---

## Getting Started

### Prerequisites

- Docker Engine 20.10+
- Docker Compose 1.29+
- 8GB RAM available
- 20GB disk space

### Start All Services

```bash
cd docker
docker-compose up -d
```

This starts all services in the background.

### Start Specific Services

```bash
# Core services only
docker-compose up -d cassandra kafka

# With monitoring
docker-compose up -d cassandra kafka prometheus grafana

# Full stack
docker-compose up -d
```

### Check Service Status

```bash
# View running services
docker-compose ps

# Check service health
docker-compose ps | grep "healthy"

# View logs for all services
docker-compose logs -f

# View logs for specific service
docker-compose logs -f cassandra
```

### Wait for Services to be Ready

```bash
# Wait for Cassandra (takes ~60 seconds)
docker exec dataflow-cassandra cqlsh -e "describe keyspaces"

# Wait for Kafka (takes ~30 seconds)
docker exec dataflow-kafka kafka-topics --bootstrap-server localhost:9092 --list

# Wait for Elasticsearch (takes ~30 seconds)
curl -f http://localhost:9200/_cluster/health
```

### Stop Services

```bash
# Stop all services (keeps data)
docker-compose down

# Stop and remove data (WARNING: deletes all data!)
docker-compose down -v
```

---

## Service Interactions

### Example Flow: Log Processing

```
1. Application writes logs
   ↓
2. Logstash receives via TCP (port 5000)
   ↓
3. Logstash processes and enriches
   ↓
4. Logstash sends to Elasticsearch
   ↓
5. Kibana queries Elasticsearch
   ↓
6. User views logs in Kibana UI
```

### Example Flow: Kafka Pipeline

```
1. Producer sends messages to Kafka topic
   ↓
2. DataFlow KafkaSource consumes messages
   ↓
3. Pipeline processes records
   ↓
4. Events stored in Cassandra (event sourcing)
   ↓
5. Projection updates PostgreSQL (read model)
   ↓
6. Metrics sent to Prometheus
   ↓
7. Grafana displays pipeline metrics
```

---

## Usage Patterns

### Pattern 1: Development Workflow

```bash
# 1. Start core services
docker-compose up -d cassandra kafka postgres redis

# 2. Wait for readiness
sleep 30

# 3. Run your application
sbt "dataflow-core/run"

# 4. View logs
docker-compose logs -f cassandra kafka

# 5. Stop services when done
docker-compose down
```

### Pattern 2: Full Observability

```bash
# 1. Start all services
docker-compose up -d

# 2. Access UIs
open http://localhost:3000  # Grafana
open http://localhost:5601  # Kibana
open http://localhost:9090  # Prometheus
open http://localhost:8090  # Kafka UI

# 3. Create test data
docker exec -it dataflow-kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic test-topic

# 4. Monitor in Grafana
```

### Pattern 3: Integration Testing

```bash
# 1. Start test environment
docker-compose up -d cassandra kafka postgres redis

# 2. Run tests
sbt test

# 3. Clean up
docker-compose down -v
```

---

## Monitoring & Observability

### Metrics Collection Flow

```
DataFlow App (Kamon) → Prometheus → Grafana
         ↓
    Application Logs → Logstash → Elasticsearch → Kibana
```

### Key Metrics to Monitor

#### Application Metrics (from Kamon)

- **Throughput**: `rate(pipeline_records_processed_total[5m])`
- **Latency**: `histogram_quantile(0.95, pipeline_batch_duration_seconds_bucket)`
- **Errors**: `rate(pipeline_errors_total[5m])`
- **Active Pipelines**: `count(pipeline_status{status="running"})`

#### System Metrics

- **CPU Usage**: `process_cpu_usage`
- **Memory**: `jvm_memory_used_bytes`
- **GC**: `jvm_gc_pause_seconds`
- **Threads**: `jvm_threads_live_threads`

#### Infrastructure Metrics

- **Kafka Lag**: Consumer group lag
- **Cassandra Latency**: Read/write latency
- **Elasticsearch Query Time**: Search latency

### Alerting Rules

Create alerts in Grafana:

```
Name: High Error Rate
Condition: rate(pipeline_errors_total[5m]) > 10
Message: Pipeline error rate exceeded threshold
```

---

## Data Persistence

### Volumes Overview

All data is stored in Docker volumes:

```bash
# List volumes
docker volume ls | grep dataflow

# Inspect volume
docker volume inspect docker_cassandra-data

# Backup volume
docker run --rm -v docker_cassandra-data:/data -v $(pwd):/backup alpine \
  tar czf /backup/cassandra-backup.tar.gz /data

# Restore volume
docker run --rm -v docker_cassandra-data:/data -v $(pwd):/backup alpine \
  tar xzf /backup/cassandra-backup.tar.gz -C /data --strip 1
```

### Volume Locations

- **Cassandra**: `/var/lib/cassandra`
- **Kafka**: `/var/lib/kafka/data`
- **Elasticsearch**: `/usr/share/elasticsearch/data`
- **PostgreSQL**: `/var/lib/postgresql/data`
- **Redis**: `/data`
- **Prometheus**: `/prometheus`
- **Grafana**: `/var/lib/grafana`

---

## Networking

### Network: `dataflow-network`

All services are on a bridge network with subnet `172.25.0.0/16`.

### Service Resolution

**From containers**: Use service name
```
cassandra:9042
kafka:9092
elasticsearch:9200
```

**From host**: Use localhost
```
localhost:9042
localhost:9092
localhost:9200
```

### Port Mapping

| Service       | Container Port | Host Port | Purpose               |
|---------------|----------------|-----------|------------------------|
| Cassandra     | 9042           | 9042      | CQL                    |
| Kafka         | 9092           | 9093      | Broker (external)      |
| Kafka         | 9092           | -         | Broker (internal)      |
| Elasticsearch | 9200           | 9200      | REST API               |
| Kibana        | 5601           | 5601      | Web UI                 |
| PostgreSQL    | 5432           | 5432      | Database               |
| Redis         | 6379           | 6379      | Cache                  |
| Prometheus    | 9090           | 9090      | Web UI                 |
| Grafana       | 3000           | 3000      | Web UI                 |
| Kafka UI      | 8080           | 8090      | Web UI                 |
| Logstash      | 5000           | 5000      | TCP input              |

---

## Best Practices

### 1. Resource Management

```yaml
# Add resource limits
services:
  cassandra:
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
        reservations:
          memory: 1G
```

### 2. Health Checks

Always wait for health checks before using services:

```bash
# Check all services are healthy
docker-compose ps | grep "healthy"
```

### 3. Log Rotation

Prevent disk space issues:

```yaml
services:
  cassandra:
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
```

### 4. Backup Strategy

Regular backups:

```bash
# Automated backup script
#!/bin/bash
DATE=$(date +%Y%m%d)
docker-compose exec -T cassandra nodetool snapshot
docker exec dataflow-postgres pg_dump -U dataflow dataflow > backup-$DATE.sql
```

### 5. Monitoring

Always monitor:
- Service health
- Disk usage
- Memory usage
- Log volume

---

## Troubleshooting

### Service Won't Start

```bash
# Check logs
docker-compose logs service-name

# Check resource usage
docker stats

# Check port conflicts
netstat -an | grep LISTEN | grep 9042
```

### Cassandra Issues

#### "Connection refused"
- Wait 60 seconds for full startup
- Check: `docker logs dataflow-cassandra`

#### "Out of memory"
- Increase MAX_HEAP_SIZE
- Reduce other services

### Kafka Issues

#### "Broker not available"
- Ensure Zookeeper is healthy
- Check: `docker logs dataflow-zookeeper`

#### "Cannot connect from host"
- Use port 9093 (PLAINTEXT_HOST)
- Check advertised.listeners configuration

### Elasticsearch Issues

#### "Disk watermark exceeded"
- Free disk space
- Delete old indices

#### "Circuit breaker tripped"
- Reduce memory pressure
- Increase heap size

### Network Issues

#### "Service not reachable"
- Check network: `docker network inspect docker_dataflow-network`
- Verify service name resolution

#### "Connection timeout"
- Check firewall rules
- Verify port mappings

---

## Advanced Topics

### Multi-Node Cassandra Cluster

```yaml
services:
  cassandra-1:
    image: cassandra:4.1
    environment:
      - CASSANDRA_SEEDS=cassandra-1,cassandra-2

  cassandra-2:
    image: cassandra:4.1
    environment:
      - CASSANDRA_SEEDS=cassandra-1,cassandra-2
    depends_on:
      - cassandra-1
```

### Multi-Broker Kafka Cluster

```yaml
services:
  kafka-1:
    environment:
      KAFKA_BROKER_ID: 1

  kafka-2:
    environment:
      KAFKA_BROKER_ID: 2
```

### Production Considerations

1. **Security**
   - Enable authentication (Cassandra, Kafka, etc.)
   - Use SSL/TLS for connections
   - Change default passwords

2. **High Availability**
   - Multi-node clusters
   - Replication factor ≥ 3
   - Cross-datacenter replication

3. **Monitoring**
   - External monitoring (Datadog, New Relic)
   - Alerting (PagerDuty, Slack)
   - Log aggregation (Splunk, ELK)

4. **Performance Tuning**
   - Adjust heap sizes
   - Tune thread pools
   - Optimize queries

---

## Summary

The DataFlow Platform uses a sophisticated Docker-based infrastructure that provides:

✅ **Event Sourcing** with Cassandra
✅ **Message Streaming** with Kafka
✅ **Centralized Logging** with ELK
✅ **Read Models** with PostgreSQL
✅ **Caching** with Redis
✅ **Metrics & Monitoring** with Prometheus & Grafana

This architecture provides a **production-ready** environment for building scalable data pipelines with comprehensive observability.

### Quick Reference

```bash
# Start everything
docker-compose up -d

# Check status
docker-compose ps

# View logs
docker-compose logs -f

# Stop everything
docker-compose down

# Access UIs
http://localhost:3000  # Grafana (admin/admin)
http://localhost:5601  # Kibana
http://localhost:9090  # Prometheus
http://localhost:8090  # Kafka UI

# Connect to services
localhost:9042   # Cassandra
localhost:9093   # Kafka (from host)
localhost:5432   # PostgreSQL (dataflow/dataflow)
localhost:6379   # Redis
localhost:9200   # Elasticsearch
```

---

## Next Steps

1. **Explore Services**: Access each UI and familiarize yourself
2. **Create Test Data**: Use console producers/consumers
3. **Build Dashboards**: Create monitoring dashboards in Grafana
4. **Run Pipelines**: Start DataFlow pipelines and observe
5. **Review Logs**: Search logs in Kibana
6. **Monitor Metrics**: Track pipeline performance in Grafana

For more information, see:
- [TESTING_GUIDE.md](TESTING_GUIDE.md) - Complete testing guide
- [SOURCE_CONNECTORS.md](SOURCE_CONNECTORS.md) - Source connector documentation
- [Docker Compose Docs](https://docs.docker.com/compose/)
