# DataFlow Platform - Testing Guide

## Table of Contents
1. [Environment Setup](#environment-setup)
2. [Starting Services](#starting-services)
3. [Running the Application](#running-the-application)
4. [Testing with Sample Data](#testing-with-sample-data)
5. [Monitoring and Debugging](#monitoring-and-debugging)
6. [Common Issues](#common-issues)

---

## Environment Setup

### Prerequisites

```bash
# Required software
- Docker & Docker Compose (version 20.10+)
- SBT (1.9.0+)
- Java JDK 11 or 17
- Git

# Verify installations
docker --version
docker-compose --version
sbt --version
java --version
```

### Directory Structure

```
dataflow-platform/
├── docker/
│   ├── docker-compose.yml          # Infrastructure services
│   ├── prometheus/
│   │   └── prometheus.yml          # Prometheus config
│   └── grafana/
│       ├── provisioning/
│       │   ├── datasources/        # Grafana datasources
│       │   └── dashboards/         # Dashboard provisioning
│       └── dashboards/             # Dashboard JSON files
├── dataflow-core/
│   └── src/main/scala/com/dataflow/
│       └── Main.scala              # Application entry point
└── dataflow-sources/
    └── src/main/scala/com/dataflow/sources/
        └── TestSource.scala        # Sample data generator
```

---

## Starting Services

### Step 1: Start Infrastructure (Docker Compose)

```bash
# Navigate to docker directory
cd docker

# Start all services
docker-compose up -d

# Wait for services to be healthy (1-2 minutes)
docker-compose ps

# Check service health
docker-compose logs -f cassandra    # Check Cassandra logs
docker-compose logs -f kafka        # Check Kafka logs
docker-compose logs -f prometheus   # Check Prometheus logs
```

### Service Startup Order

The services will start in this order (handled by depends_on):

1. **Zookeeper** → Required by Kafka
2. **Cassandra** → Event store
3. **Kafka** → Message broker
4. **Elasticsearch** → Log storage
5. **Logstash** → Log processing
6. **Kibana** → Log visualization
7. **PostgreSQL** → Read models
8. **Redis** → Caching
9. **Prometheus** → Metrics collection
10. **Grafana** → Dashboards

### Verify Services are Running

```bash
# Check all services are healthy
docker-compose ps

# Expected output:
NAME                    STATUS
dataflow-cassandra      Up (healthy)
dataflow-elasticsearch  Up (healthy)
dataflow-grafana        Up (healthy)
dataflow-kafka          Up (healthy)
dataflow-kafka-ui       Up
dataflow-kibana         Up (healthy)
dataflow-logstash       Up
dataflow-postgres       Up (healthy)
dataflow-prometheus     Up (healthy)
dataflow-redis          Up (healthy)
dataflow-zookeeper      Up (healthy)
```

### Access Service UIs

Open these URLs in your browser:

- **Grafana**: http://localhost:3000 (admin/admin)
- **Prometheus**: http://localhost:9090
- **Kafka UI**: http://localhost:8090
- **Kibana**: http://localhost:5601
- **Elasticsearch**: http://localhost:9200

---

## Running the Application

### Step 1: Build the Application

```bash
# Navigate to project root
cd /path/to/dataflow-platform

# Clean and compile
sbt clean compile

# Run tests (optional)
sbt test

# Create assembly JAR (optional - for production)
sbt assembly
```

### Step 2: Initialize Cassandra Schema

```bash
# Connect to Cassandra
docker exec -it dataflow-cassandra cqlsh

# Create keyspace
CREATE KEYSPACE IF NOT EXISTS dataflow
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

# Create journal table
USE dataflow;

CREATE TABLE IF NOT EXISTS messages (
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
  PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp)
) WITH CLUSTERING ORDER BY (sequence_nr DESC, timestamp DESC);

# Create snapshot table
CREATE TABLE IF NOT EXISTS snapshots (
  persistence_id text,
  sequence_nr bigint,
  timestamp bigint,
  ser_id int,
  ser_manifest text,
  snapshot_data blob,
  snapshot blob,
  meta_ser_id int,
  meta_ser_manifest text,
  meta blob,
  PRIMARY KEY (persistence_id, sequence_nr)
) WITH CLUSTERING ORDER BY (sequence_nr DESC);

# Exit
exit
```

### Step 3: Run the Application

```bash
# Option 1: Run directly with SBT
sbt "dataflow-core/run"

# Option 2: Run with specific configuration
sbt "dataflow-core/run -Dconfig.resource=local.conf"

# Option 3: Run from JAR (if you built assembly)
java -jar dataflow-core/target/scala-2.13/dataflow-core-assembly-0.1.0-SNAPSHOT.jar
```

### Step 4: Verify Application Started

Check logs for these messages:

```
[INFO] Starting DataFlow Platform
[INFO] Cluster address: pekko://DataflowPlatform@127.0.0.1:2551
[INFO] Cluster roles: core
[INFO] Initializing metrics collection (Kamon)...
[INFO] ✓ Metrics collection initialized
[INFO] Starting Pekko Management...
[INFO] ✓ Pekko Management started
[INFO] Initializing Pipeline Cluster Sharding...
[INFO] ✓ Pipeline Sharding initialized
[INFO] Initializing Coordinator Singleton...
[INFO] ✓ Coordinator Singleton initialized
[INFO] ================================================================================
[INFO] DataFlow Platform started successfully!
[INFO] ================================================================================
[INFO] Metrics endpoint: http://localhost:9095/metrics
[INFO] Kamon status: http://localhost:5266
[INFO] ================================================================================
```

### Verify Application is Running

```bash
# Check metrics endpoint (Kamon)
curl http://localhost:9095/metrics

# Check Kamon status page
curl http://localhost:5266

# Check Prometheus is scraping metrics
curl http://localhost:9090/api/v1/targets
```

---

## Testing with Sample Data

### Option 1: Using TestSource (Programmatically)

Create a test script:

```scala
// File: TestRunner.scala
package com.dataflow.test

import com.dataflow.Main
import com.dataflow.domain.commands._
import com.dataflow.sources.TestSource
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding

import scala.concurrent.duration._

object TestRunner {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem(Behaviors.empty, "DataflowPlatform")
    val sharding = ClusterSharding(system)

    // Get pipeline shard region
    val pipelineShardRegion = sharding.entityRefFor(
      Main.PipelineTypeKey,
      "test-pipeline-1"
    )

    // Create test source
    val testSource = system.systemActorOf(
      TestSource(
        "test-pipeline-1",
        pipelineShardRegion,
        TestSource.Config(
          batchSize = 100,
          batchInterval = 5.seconds,
          recordType = "user-event"
        )
      ),
      "test-source-1"
    )

    // Start generating data
    testSource ! TestSource.Start

    println("Test source started, generating data every 5 seconds...")
    println("Press CTRL+C to stop")
  }
}
```

Run the test:

```bash
sbt "test:runMain com.dataflow.test.TestRunner"
```

### Option 2: Using SBT Console

```bash
sbt dataflow-core/console

# In the console:
scala> import com.dataflow.Main._
scala> import com.dataflow.sources.TestSource
scala> import org.apache.pekko.actor.typed.ActorSystem
scala> import org.apache.pekko.actor.typed.scaladsl.Behaviors

scala> val system = ActorSystem(Behaviors.empty, "DataflowPlatform")
scala> // ... continue with test setup
```

### Option 3: Using Kafka UI (Manual Testing)

1. Open Kafka UI: http://localhost:8090
2. Create a topic: `test-events`
3. Produce messages manually:

```json
{
  "id": "evt-123",
  "data": {
    "event_type": "login",
    "user_id": "user-1",
    "timestamp": "2024-03-15T10:30:00Z"
  },
  "metadata": {
    "source": "manual-test"
  }
}
```

---

## Monitoring and Debugging

### Prometheus Metrics

**Access**: http://localhost:9090

**Useful Queries**:

```promql
# Check if app is reporting metrics
up{job="dataflow-platform"}

# Pipeline metrics
dataflow_pipelines_running

# Batch throughput (batches per second)
rate(dataflow_batches_processed_total[1m])

# Record throughput (records per second)
rate(dataflow_records_processed_total[1m])

# Processing time (95th percentile)
histogram_quantile(0.95, rate(dataflow_batch_processing_time_ms_bucket[5m]))

# Error rate
rate(dataflow_batches_failed_total[5m])
```

### Grafana Dashboards

**Access**: http://localhost:3000 (admin/admin)

**Pre-configured Datasource**: Prometheus (already configured)

**Create a Dashboard**:

1. Go to Dashboards → New Dashboard
2. Add Panel
3. Use Prometheus queries from above
4. Save dashboard

### Cassandra (Event Store)

```bash
# Connect to Cassandra
docker exec -it dataflow-cassandra cqlsh

# Check keyspace
USE dataflow;

# View recent events
SELECT persistence_id, sequence_nr, timestamp
FROM messages
LIMIT 10;

# View snapshots
SELECT persistence_id, sequence_nr
FROM snapshots
LIMIT 10;

# Count events by pipeline
SELECT persistence_id, count(*)
FROM messages
GROUP BY persistence_id;
```

### Application Logs

```bash
# If running with SBT
# Logs appear in console

# If running as JAR
tail -f logs/application.log

# Docker logs (if containerized)
docker logs -f dataflow-app
```

### Debugging Tips

**Problem**: Metrics not appearing in Prometheus

```bash
# Check Kamon endpoint
curl http://localhost:9095/metrics

# Check Prometheus targets
curl http://localhost:9090/api/v1/targets | jq

# Check Prometheus config
cat docker/prometheus/prometheus.yml
```

**Problem**: Events not persisting to Cassandra

```bash
# Check Cassandra connectivity
docker exec -it dataflow-cassandra nodetool status

# Check keyspace exists
docker exec -it dataflow-cassandra cqlsh -e "DESCRIBE KEYSPACES;"

# Check app can connect
# Look for logs: "Connected to Cassandra"
```

**Problem**: Cluster not forming

```bash
# Check cluster members
curl http://localhost:8558/cluster/members

# Check Pekko Management
curl http://localhost:8558/health

# Review cluster logs
# Look for: "Cluster Member is Up"
```

---

## Common Issues

### Issue 1: Docker Services Not Starting

**Symptoms**:
- `docker-compose ps` shows services as "Exit 1" or "Restarting"

**Solutions**:

```bash
# Check logs
docker-compose logs [service-name]

# Common fixes:
# 1. Increase Docker memory (Docker Desktop → Settings → Resources)
# 2. Clean up old containers
docker-compose down -v
docker system prune -a

# 3. Start services individually
docker-compose up -d cassandra
docker-compose up -d zookeeper
docker-compose up -d kafka
```

### Issue 2: Application Can't Connect to Cassandra

**Symptoms**:
- Error: "Failed to connect to Cassandra"

**Solutions**:

```bash
# 1. Check Cassandra is healthy
docker-compose ps cassandra

# 2. Test connectivity from host
telnet localhost 9042

# 3. Update application.conf
# Change: contact-points = ["cassandra:9042"]
# To:     contact-points = ["localhost:9042"]  # For local dev

# 4. Wait longer for Cassandra to start
# Cassandra can take 60-90 seconds to be ready
```

### Issue 3: Port Already in Use

**Symptoms**:
- Error: "port is already allocated"

**Solutions**:

```bash
# Find process using port
lsof -i :9042  # Example for Cassandra port

# Kill process
kill -9 <PID>

# Or change port in docker-compose.yml
ports:
  - "9043:9042"  # Map to different host port
```

### Issue 4: Metrics Not Showing in Grafana

**Symptoms**:
- Grafana shows "No data"

**Solutions**:

```bash
# 1. Check Prometheus datasource
# Grafana → Configuration → Datasources → Prometheus
# Test connection

# 2. Check Prometheus is scraping
# http://localhost:9090/targets
# Should show "dataflow-platform" as UP

# 3. Check metrics are being exported
curl http://localhost:9095/metrics | grep dataflow

# 4. Wait a few minutes
# Metrics take time to appear (scrape interval: 15s)
```

### Issue 5: OOM (Out of Memory) Errors

**Symptoms**:
- Java heap space errors
- Docker containers crashing

**Solutions**:

```bash
# 1. Increase JVM memory
sbt -J-Xms512m -J-Xmx2g "dataflow-core/run"

# 2. Increase Docker memory limits
# docker-compose.yml:
deploy:
  resources:
    limits:
      memory: 2g

# 3. Reduce batch sizes
# application.conf:
dataflow.pipeline.default-batch-size = 500  # Instead of 1000
```

---

## Next Steps

1. **Create a Pipeline**:
   - Use API (once implemented) or programmatically
   - Configure source, transforms, sink

2. **Monitor Performance**:
   - Watch Grafana dashboards
   - Set up alerts in Prometheus

3. **Scale Up**:
   - Add more application nodes
   - Increase Docker resources
   - Tune batch sizes

4. **Production Deployment**:
   - Review security settings
   - Set up persistent volumes
   - Configure monitoring alerts

---

## Quick Reference

### Service Ports

| Service | Port | URL |
|---------|------|-----|
| Grafana | 3000 | http://localhost:3000 |
| Prometheus | 9090 | http://localhost:9090 |
| Kamon Metrics | 9095 | http://localhost:9095/metrics |
| Kamon Status | 5266 | http://localhost:5266 |
| Kafka UI | 8090 | http://localhost:8090 |
| Kibana | 5601 | http://localhost:5601 |
| Elasticsearch | 9200 | http://localhost:9200 |
| Cassandra | 9042 | localhost:9042 (CQL) |
| Kafka | 9093 | localhost:9093 (external) |
| PostgreSQL | 5432 | localhost:5432 |
| Redis | 6379 | localhost:6379 |

### Useful Commands

```bash
# Docker Compose
docker-compose up -d              # Start all services
docker-compose down               # Stop all services
docker-compose ps                 # Check service status
docker-compose logs -f [service]  # View logs
docker-compose restart [service]  # Restart service

# Application
sbt clean compile                 # Build
sbt test                          # Run tests
sbt "dataflow-core/run"           # Run application
sbt console                       # Interactive console

# Monitoring
curl http://localhost:9095/metrics         # Kamon metrics
curl http://localhost:9090/api/v1/targets  # Prometheus targets
curl http://localhost:8558/cluster/members # Cluster members
```

---

## Support

For issues or questions:

1. Check logs first
2. Review this guide's troubleshooting section
3. Check GitHub issues
4. Open a new issue with:
   - Error logs
   - Steps to reproduce
   - Environment details (OS, Docker version, Java version)
