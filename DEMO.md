# DataFlow Platform Demo

This document explains how to run the DataFlow Platform demo, which demonstrates a complete end-to-end data pipeline.

## What the Demo Does

The demo creates and runs a simple data pipeline that:

1. **Reads data** from a text file (`/tmp/dataflow-demo/sample-data.txt`)
2. **Processes data** through the DataFlow Platform using event-sourced aggregates
3. **Outputs data** to the console (simulated sink)

This demonstrates:
- Pipeline creation and management
- Event sourcing with Cassandra persistence
- Cluster sharding for distributed processing
- Command handling through the coordinator and pipeline aggregates

## Prerequisites

### 1. Start Cassandra

The platform requires Cassandra for event persistence:

```bash
# Using Docker Compose
cd docker
docker-compose up cassandra -d

# Wait for Cassandra to be ready
docker-compose logs -f cassandra
# Wait until you see "Starting listening for CQL clients"
```

Alternatively, if you have Cassandra installed locally:
```bash
# Start Cassandra service
cassandra -f
```

### 2. Verify Cassandra is Running

```bash
# Check if Cassandra is listening on port 9042
nc -z localhost 9042 && echo "Cassandra is running" || echo "Cassandra is not running"

# Or use cqlsh
cqlsh localhost 9042
```

## Running the Demo

### Option 1: Using the Demo Script (Recommended)

```bash
# Make the script executable (if not already)
chmod +x run-demo.sh

# Run the demo
./run-demo.sh
```

### Option 2: Manual Execution

```bash
# Set environment variables
export DATAFLOW_RUN_DEMO=true
export DATAFLOW_ENV=local
export PEKKO_LOG_LEVEL=INFO

# Run with SBT
sbt "dataflow-core/run"
```

## Expected Output

When you run the demo, you should see output similar to:

```
[INFO] Starting DataFlow Platform
[INFO] Cluster address: pekko://DataflowPlatform@127.0.0.1:2551
[INFO] Cluster roles: core, pipeline-processor
[INFO] ✓ Metrics collection initialized
[INFO] ✓ Pekko Management started
[INFO] ✓ Cluster Bootstrap started
[INFO] ✓ Pipeline Sharding initialized
[INFO] ✓ Coordinator Singleton initialized
================================================================================
DataFlow Platform started successfully!
================================================================================
Cluster address: pekko://DataflowPlatform@127.0.0.1:2551
Roles: core, pipeline-processor
Management endpoint: http://localhost:8558
Metrics endpoint: http://localhost:9095/metrics
Kamon status: http://localhost:5266
================================================================================
[INFO] Demo mode enabled - running demo pipeline...
================================================================================
Starting DataFlow Platform Demo
================================================================================
[INFO] Creating demo pipeline...
[INFO] ✓ Pipeline created successfully: ConfiguredState(...)
[INFO] Starting pipeline...
[INFO] ✓ Pipeline started successfully: RunningState(...)
[INFO] Demo pipeline is now running and processing data...
[INFO] Simulating data ingestion...
[INFO] ✓ Data ingestion completed: BatchResult(...)
================================================================================
Demo completed successfully!
The pipeline processed all records from the demo file.
================================================================================
```

## Inspecting the Data

### View Cassandra Tables

The platform automatically creates keyspaces and tables:

```bash
# Connect to Cassandra
cqlsh localhost 9042

# View keyspaces
DESCRIBE KEYSPACES;

# View journal tables
USE dataflow_journal;
DESCRIBE TABLES;

# View events
SELECT * FROM messages LIMIT 10;

# View snapshot tables
USE dataflow_snapshot;
DESCRIBE TABLES;
SELECT * FROM snapshots LIMIT 10;
```

### Check Cluster Status

The Pekko Management endpoint provides cluster information:

```bash
# Cluster members
curl http://localhost:8558/cluster/members

# Cluster shards
curl http://localhost:8558/cluster/shards

# Health check
curl http://localhost:8558/ready
curl http://localhost:8558/alive
```

### View Metrics

```bash
# Prometheus metrics
curl http://localhost:9095/metrics

# Kamon status (if available)
curl http://localhost:5266
```

## Understanding the Demo Code

### 1. Demo Data File

Location: `/tmp/dataflow-demo/sample-data.txt`

The demo creates a simple text file with sample data lines.

### 2. DemoRunner

Location: `dataflow-core/src/main/scala/com/dataflow/demo/DemoRunner.scala`

The `DemoRunner` object demonstrates:
- Creating a pipeline with `CreatePipeline` command
- Starting the pipeline with `StartPipeline` command
- Ingesting data with `IngestBatch` command

### 3. Pipeline Configuration

The demo configures:

**Source**: Text file source
```scala
SourceConfig(
  sourceType = SourceType.File,
  connectionString = "/tmp/dataflow-demo/sample-data.txt",
  batchSize = 10,
  pollIntervalMs = 1000,
  options = Map("format" -> "text", "encoding" -> "UTF-8")
)
```

**Transforms**: None (pass-through)

**Sink**: Console sink (prints to stdout)
```scala
SinkConfig(
  sinkType = "console",
  connectionString = "stdout",
  batchSize = 10
)
```

## Troubleshooting

### Issue: Cassandra connection failed

**Solution**: Ensure Cassandra is running and listening on port 9042
```bash
docker-compose -f docker/docker-compose.yml up cassandra -d
```

### Issue: Port already in use

**Solution**: Check if another instance is running
```bash
# Check ports
lsof -i :2551  # Pekko remoting
lsof -i :8558  # Pekko Management
lsof -i :9095  # Metrics

# Kill processes if needed
kill -9 <PID>
```

### Issue: Keyspace not created

**Solution**: Ensure `keyspace-autocreate` is enabled in `application.conf`:
```hocon
pekko.persistence.cassandra {
  keyspace-autocreate = true
  tables-autocreate = true
}
```

### Issue: Demo doesn't start

**Solution**: Check environment variable:
```bash
echo $DATAFLOW_RUN_DEMO  # Should output "true"
```

## Next Steps

After running the demo:

1. **Explore the Code**: Review the aggregates, commands, and events
2. **Try Different Sources**: Modify the demo to use Kafka or database sources
3. **Add Transforms**: Add data transformation logic
4. **Custom Sinks**: Implement custom sink connectors
5. **Multiple Pipelines**: Create multiple pipelines running concurrently
6. **Scaling**: Run multiple nodes to see cluster sharding in action

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    DataFlow Platform                         │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐      ┌──────────────┐     ┌────────────┐ │
│  │   Source     │─────▶│   Pipeline   │────▶│    Sink    │ │
│  │  (TextFile)  │      │   Aggregate  │     │ (Console)  │ │
│  └──────────────┘      └──────────────┘     └────────────┘ │
│                               │                              │
│                               ▼                              │
│                        ┌─────────────┐                       │
│                        │  Cassandra  │                       │
│                        │   (Events)  │                       │
│                        └─────────────┘                       │
└─────────────────────────────────────────────────────────────┘
```

## Additional Resources

- [Architecture Documentation](docs/ARCHITECTURE_AND_SHARDING.md)
- [Source Connectors](docs/SOURCE_CONNECTORS.md)
- [Testing Guide](docs/TESTING_GUIDE.md)
- [Docker Infrastructure](docs/DOCKER_INFRASTRUCTURE.md)
