# DataFlow Platform - Source Connectors

This guide covers the data source connectors available in the DataFlow Platform, including configuration, usage, and best practices.

## Table of Contents

- [Overview](#overview)
- [Source Abstraction](#source-abstraction)
- [File Source](#file-source)
- [Kafka Source](#kafka-source)
- [Test Source](#test-source)
- [Configuration Reference](#configuration-reference)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

## Overview

Source connectors are responsible for ingesting data from external systems into the DataFlow Platform. All sources implement a common interface (`Source` trait) and produce `DataRecord` objects that flow through the pipeline.

### Key Features

- **Streaming Architecture**: Built on Pekko Streams for reactive, backpressure-aware data ingestion
- **Offset Management**: Track and resume from specific positions
- **Batch Processing**: Configurable batch sizes for efficient processing
- **Error Handling**: Graceful error handling with malformed data
- **Health Monitoring**: Built-in health checks for monitoring
- **Pluggable Design**: Easy to add new source types

## Source Abstraction

All sources implement the `Source` trait:

```scala
trait Source {
  def sourceId: String
  def pipelineId: String
  def config: SourceConfig

  // Main stream creation method
  def stream(): PekkoSource[DataRecord, Future[Done]]

  // Lifecycle management
  def start(pipelineShardRegion: ActorRef[ShardingEnvelope[Command]]): Future[Done]
  def stop(): Future[Done]

  // Checkpointing
  def currentOffset(): Long
  def resumeFrom(offset: Long): Unit

  // Health monitoring
  def isHealthy: Boolean
}
```

### Creating a Source

Use the factory method to create a source from configuration:

```scala
import com.dataflow.sources.Source
import com.dataflow.domain.models.SourceConfig

val config = SourceConfig(
  sourceType = "file",  // or "kafka", "test"
  connectionString = "/path/to/data.csv",
  config = Map(
    "format" -> "csv",
    "has-header" -> "true"
  ),
  batchSize = 1000,
  pollIntervalMs = 1000
)

val source = Source("my-pipeline-id", config)
```

## File Source

The File Source connector reads data from files in various formats: CSV, JSON, and plain text.

### Supported Formats

- **CSV**: Comma-separated values with optional header
- **JSON**: Newline-delimited JSON (NDJSON)
- **Text**: Plain text, line-by-line

### Configuration

#### Basic CSV Configuration

```scala
SourceConfig(
  sourceType = "file",
  connectionString = "/data/users.csv",
  config = Map(
    "format" -> "csv",
    "has-header" -> "true",
    "delimiter" -> ",",
    "encoding" -> "UTF-8"
  ),
  batchSize = 1000,
  pollIntervalMs = 1000
)
```

#### JSON Configuration

```scala
SourceConfig(
  sourceType = "file",
  connectionString = "/data/events.json",
  config = Map(
    "format" -> "json",
    "encoding" -> "UTF-8"
  ),
  batchSize = 1000,
  pollIntervalMs = 1000
)
```

#### Plain Text Configuration

```scala
SourceConfig(
  sourceType = "file",
  connectionString = "/data/logs.txt",
  config = Map(
    "format" -> "text",
    "encoding" -> "UTF-8"
  ),
  batchSize = 1000,
  pollIntervalMs = 1000
)
```

### CSV Format Details

#### With Header
```csv
id,name,age,email
1,Alice,30,alice@example.com
2,Bob,25,bob@example.com
```

Generated DataRecord:
```scala
DataRecord(
  id = "1",  // From first column or generated
  data = Map(
    "id" -> "1",
    "name" -> "Alice",
    "age" -> "30",
    "email" -> "alice@example.com"
  ),
  metadata = Map(
    "source" -> "file",
    "file_path" -> "/data/users.csv",
    "format" -> "csv",
    "line_number" -> "2",
    "timestamp" -> "2024-01-01T10:00:00Z"
  )
)
```

#### Without Header
```csv
1,Alice,30,alice@example.com
2,Bob,25,bob@example.com
```

Generated DataRecord:
```scala
DataRecord(
  id = "<generated-uuid>",
  data = Map(
    "field0" -> "1",
    "field1" -> "Alice",
    "field2" -> "30",
    "field3" -> "alice@example.com"
  ),
  metadata = Map(...)
)
```

#### Custom Delimiter (TSV)

```scala
SourceConfig(
  sourceType = "file",
  connectionString = "/data/data.tsv",
  config = Map(
    "format" -> "csv",
    "has-header" -> "true",
    "delimiter" -> "\t",  // Tab delimiter
    "encoding" -> "UTF-8"
  ),
  batchSize = 1000,
  pollIntervalMs = 1000
)
```

### JSON Format Details

The File Source expects **newline-delimited JSON** (NDJSON):

```json
{"id":"evt-1","type":"login","user":"alice","timestamp":"2024-01-01T10:00:00Z"}
{"id":"evt-2","type":"page_view","user":"bob","page":"/home","timestamp":"2024-01-01T10:01:00Z"}
{"id":"evt-3","type":"click","user":"alice","element":"button1","timestamp":"2024-01-01T10:02:00Z"}
```

Generated DataRecord:
```scala
DataRecord(
  id = "evt-1",  // Extracted from JSON "id" field
  data = Map(
    "type" -> "login",
    "user" -> "alice",
    "timestamp" -> "2024-01-01T10:00:00Z"
  ),
  metadata = Map(
    "source" -> "file",
    "file_path" -> "/data/events.json",
    "format" -> "json",
    "line_number" -> "1",
    "timestamp" -> "2024-01-01T10:00:00Z"
  )
)
```

### Text Format Details

Plain text files are read line-by-line:

```text
2024-01-01 10:00:00 INFO Application started
2024-01-01 10:00:01 INFO Connected to database
2024-01-01 10:00:02 WARN Slow query detected
```

Generated DataRecord:
```scala
DataRecord(
  id = "<generated-uuid>",
  data = Map(
    "line" -> "2024-01-01 10:00:00 INFO Application started",
    "line_number" -> "1"
  ),
  metadata = Map(
    "source" -> "file",
    "file_path" -> "/data/logs.txt",
    "format" -> "text",
    "line_number" -> "1",
    "timestamp" -> "2024-01-01T10:00:00Z"
  )
)
```

### Offset Management

The File Source tracks the current line number as the offset:

```scala
val source = FileSource("pipeline-1", config)

// Start reading
source.start(pipelineShardRegion)

// Check current offset (line number)
val currentLine = source.currentOffset()

// Resume from specific line
source.resumeFrom(1000)  // Skip first 1000 lines
```

### Error Handling

The File Source handles errors gracefully:

- **Malformed CSV lines**: Skipped with warning log
- **Invalid JSON**: Falls back to string parsing
- **Missing files**: Throws exception with clear error message
- **Encoding errors**: Handled by Java NIO with configured charset

### Performance Considerations

- **Large Files**: FileSource uses streaming I/O, reading line-by-line without loading entire file into memory
- **Batch Size**: Configure based on downstream processing capacity (typically 100-10000)
- **Buffer Size**: Default frame size is 8KB, adjust for very long lines

## Kafka Source

The Kafka Source connector consumes data from Apache Kafka topics using Pekko Kafka (Alpakka Kafka).

### Features

- **Consumer Groups**: Horizontal scalability with multiple consumers
- **Offset Management**: Automatic offset commits with exactly-once semantics
- **Backpressure**: Built-in backpressure handling
- **Format Support**: JSON and string message parsing
- **Fault Tolerance**: Automatic reconnection and error handling

### Configuration

#### Basic JSON Configuration

```scala
SourceConfig(
  sourceType = "kafka",
  connectionString = "localhost:9092",  // Bootstrap servers
  config = Map(
    "topic" -> "my-topic",
    "group-id" -> "my-consumer-group",
    "format" -> "json",
    "auto-offset-reset" -> "earliest",
    "enable-auto-commit" -> "false"
  ),
  batchSize = 1000,
  pollIntervalMs = 1000
)
```

#### String Format Configuration

```scala
SourceConfig(
  sourceType = "kafka",
  connectionString = "kafka-broker1:9092,kafka-broker2:9092",
  config = Map(
    "topic" -> "raw-logs",
    "group-id" -> "logs-consumer",
    "format" -> "string",
    "auto-offset-reset" -> "latest",
    "max-poll-records" -> "500"
  ),
  batchSize = 500,
  pollIntervalMs = 1000
)
```

### Configuration Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `topic` | Yes | - | Kafka topic name |
| `group-id` | No | `dataflow-{pipelineId}` | Consumer group ID |
| `format` | No | `json` | Message format: `json` or `string` |
| `auto-offset-reset` | No | `earliest` | Offset reset policy: `earliest` or `latest` |
| `enable-auto-commit` | No | `false` | Enable auto-commit (use manual commit) |
| `max-poll-records` | No | `500` | Maximum records per poll |

### JSON Message Format

Kafka messages with JSON values:

```json
{"id":"user-123","name":"Alice","age":30,"city":"New York"}
```

Generated DataRecord:
```scala
DataRecord(
  id = "user-123",  // Extracted from JSON or Kafka key
  data = Map(
    "name" -> "Alice",
    "age" -> "30",
    "city" -> "New York"
  ),
  metadata = Map(
    "source" -> "kafka",
    "source_id" -> "kafka-source-pipeline-1-...",
    "topic" -> "my-topic",
    "partition" -> "0",
    "offset" -> "12345",
    "kafka_timestamp" -> "1704110400000",
    "kafka_key" -> "user-123",
    "format" -> "json",
    "timestamp" -> "2024-01-01T10:00:00Z"
  )
)
```

### String Message Format

Simple key-value messages:

```
Key: user-123
Value: Alice,30,New York
```

Generated DataRecord:
```scala
DataRecord(
  id = "user-123",  // From Kafka key or generated
  data = Map(
    "key" -> "user-123",
    "value" -> "Alice,30,New York"
  ),
  metadata = Map(
    "source" -> "kafka",
    "topic" -> "my-topic",
    "partition" -> "0",
    "offset" -> "12345",
    "format" -> "string",
    ...
  )
)
```

### Consumer Groups

Consumer groups enable horizontal scaling:

```scala
// Consumer 1
val config1 = SourceConfig(
  sourceType = "kafka",
  connectionString = "localhost:9092",
  config = Map(
    "topic" -> "orders",
    "group-id" -> "order-processors",  // Same group ID
    "format" -> "json"
  ),
  batchSize = 1000,
  pollIntervalMs = 1000
)

// Consumer 2 (different pipeline, same group)
val config2 = config1.copy()

// Consumers will share partitions automatically
val source1 = KafkaSource("pipeline-1", config1)
val source2 = KafkaSource("pipeline-2", config2)
```

### Offset Management

Kafka manages offsets automatically through the consumer group:

```scala
val source = KafkaSource("pipeline-1", config)

// Current offset (last consumed)
val currentOffset = source.currentOffset()

// Kafka manages offset commits automatically
// Offsets are committed after successful batch processing
```

### Offset Reset Strategies

#### Start from Beginning (earliest)

```scala
config = Map(
  "topic" -> "my-topic",
  "group-id" -> "new-consumer",
  "auto-offset-reset" -> "earliest"  // Read from beginning
)
```

#### Start from Latest (latest)

```scala
config = Map(
  "topic" -> "my-topic",
  "group-id" -> "new-consumer",
  "auto-offset-reset" -> "latest"  // Only new messages
)
```

### Error Handling

- **Malformed JSON**: Falls back to string parsing
- **Connection Errors**: Automatic reconnection with exponential backoff
- **Deserialization Errors**: Logged and skipped
- **Commit Failures**: Retried automatically

### Performance Tuning

#### Batch Size

```scala
batchSize = 1000  // Number of records per batch
```

Recommendations:
- **Low latency**: 100-500 records
- **High throughput**: 1000-5000 records
- **Large messages**: 100-500 records

#### Poll Interval

```scala
pollIntervalMs = 1000  // 1 second
```

Recommendations:
- **Real-time**: 100-500ms
- **Batch processing**: 1000-5000ms

#### Max Poll Records

```scala
config = Map(
  "max-poll-records" -> "500"
)
```

Limit records fetched per poll to control memory usage.

### Monitoring

Key metrics to monitor:

- **Consumer Lag**: Difference between last produced and consumed offset
- **Throughput**: Messages per second
- **Error Rate**: Failed message parsing/processing
- **Rebalances**: Consumer group rebalances

## Test Source

The Test Source generates sample data for testing and development.

### Configuration

```scala
SourceConfig(
  sourceType = "test",
  connectionString = "test",
  config = Map(
    "record-type" -> "user",  // user, event, order, sensor
    "total-records" -> "1000",
    "records-per-second" -> "100"
  ),
  batchSize = 100,
  pollIntervalMs = 1000
)
```

### Record Types

- **user**: User profile data (id, name, age, email)
- **event**: Event data (event_type, user_id, timestamp)
- **order**: Order data (order_id, product, quantity, amount)
- **sensor**: Sensor readings (sensor_id, temperature, humidity)

## Configuration Reference

### SourceConfig Model

```scala
case class SourceConfig(
  sourceType: String,        // Type: file, kafka, test
  connectionString: String,  // Connection details (file path, Kafka servers)
  config: Map[String, String], // Type-specific configuration
  batchSize: Int,           // Records per batch
  pollIntervalMs: Long      // Polling interval in milliseconds
)
```

### Common Configuration

| Field | Type | Description |
|-------|------|-------------|
| `sourceType` | String | Source type: `file`, `kafka`, `test` |
| `connectionString` | String | Connection string (file path, Kafka servers) |
| `batchSize` | Int | Number of records per batch (100-10000) |
| `pollIntervalMs` | Long | Polling interval in milliseconds |

### File-Specific Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `format` | String | `csv` | File format: `csv`, `json`, `text` |
| `has-header` | Boolean | `true` | CSV has header row |
| `delimiter` | String | `,` | CSV delimiter character |
| `encoding` | String | `UTF-8` | File encoding |

### Kafka-Specific Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `topic` | String | Required | Kafka topic name |
| `group-id` | String | `dataflow-{pipelineId}` | Consumer group ID |
| `format` | String | `json` | Message format: `json`, `string` |
| `auto-offset-reset` | String | `earliest` | Offset reset: `earliest`, `latest` |
| `enable-auto-commit` | Boolean | `false` | Enable auto-commit |
| `max-poll-records` | Int | `500` | Max records per poll |

## Best Practices

### 1. Batch Size Selection

Choose batch size based on:

- **Message Size**: Smaller batches for large messages
- **Latency Requirements**: Smaller batches for low latency
- **Throughput**: Larger batches for high throughput
- **Memory**: Larger batches use more memory

Recommended ranges:
- **Low latency**: 10-100 records
- **Balanced**: 100-1000 records
- **High throughput**: 1000-10000 records

### 2. Error Handling

Implement proper error handling:

```scala
val source = Source("pipeline-1", config)

try {
  source.start(pipelineShardRegion).onComplete {
    case Success(_) =>
      log.info("Source completed successfully")
    case Failure(ex) =>
      log.error("Source failed", ex)
      // Implement retry logic or alerting
  }
} catch {
  case ex: Exception =>
    log.error("Failed to start source", ex)
}
```

### 3. Health Monitoring

Regularly check source health:

```scala
// Schedule periodic health checks
system.scheduler.scheduleWithFixedDelay(
  initialDelay = 1.minute,
  delay = 1.minute
) { () =>
  if (!source.isHealthy) {
    log.warn(s"Source unhealthy: ${source.sourceId}")
    // Trigger alerts or restart
  }
}
```

### 4. Offset Persistence

For File Source, persist offsets for resume capability:

```scala
// Before shutdown
val currentOffset = source.currentOffset()
persistOffset(pipelineId, currentOffset)

// On restart
val savedOffset = loadOffset(pipelineId)
source.resumeFrom(savedOffset)
```

### 5. Resource Cleanup

Always clean up resources:

```scala
// Graceful shutdown
try {
  Await.result(source.stop(), 30.seconds)
} catch {
  case ex: TimeoutException =>
    log.error("Source stop timeout", ex)
}
```

### 6. Testing

Use TestContainers for integration tests:

```scala
class KafkaSourceSpec extends AnyWordSpec
  with ForAllTestContainer {

  override val container = KafkaContainer()

  "KafkaSource" should {
    "consume messages" in {
      val config = createKafkaSourceConfig(
        topic = "test-topic",
        bootstrapServers = container.bootstrapServers
      )
      val source = KafkaSource("test-pipeline", config)
      // Test logic...
    }
  }
}
```

## Troubleshooting

### File Source Issues

#### Problem: "File not found"

**Solution**:
- Verify file path is absolute
- Check file permissions
- Ensure file exists at runtime

#### Problem: "Malformed CSV lines"

**Solution**:
- Verify delimiter is correct
- Check for inconsistent column counts
- Enable error logging to identify problematic lines

#### Problem: "Out of memory with large files"

**Solution**:
- File Source streams data, so this is unusual
- Reduce batch size
- Check for very long lines (increase frame size)

### Kafka Source Issues

#### Problem: "Connection timeout"

**Solution**:
- Verify Kafka broker addresses
- Check network connectivity
- Ensure Kafka is running

#### Problem: "Consumer group rebalancing"

**Solution**:
- Increase `session.timeout.ms`
- Reduce `max.poll.records`
- Speed up message processing

#### Problem: "High consumer lag"

**Solution**:
- Increase number of consumers in group
- Increase batch size
- Optimize downstream processing
- Add more partitions to topic

#### Problem: "Offset commit failures"

**Solution**:
- Check Kafka broker health
- Verify consumer group permissions
- Increase commit timeout

### General Issues

#### Problem: "Backpressure building up"

**Solution**:
- Reduce batch size
- Increase poll interval
- Optimize downstream processing
- Add more pipeline instances

#### Problem: "Memory leaks"

**Solution**:
- Ensure proper resource cleanup
- Check for long-running streams
- Verify all futures are completed

## Examples

### Complete File Source Example

```scala
import com.dataflow.sources.FileSource
import com.dataflow.domain.models.SourceConfig
import scala.concurrent.duration._

// Configuration
val config = SourceConfig(
  sourceType = "file",
  connectionString = "/data/users.csv",
  config = Map(
    "format" -> "csv",
    "has-header" -> "true",
    "delimiter" -> ",",
    "encoding" -> "UTF-8"
  ),
  batchSize = 1000,
  pollIntervalMs = 1000
)

// Create source
val source = FileSource("user-pipeline", config)

// Start ingestion
source.start(pipelineShardRegion).onComplete {
  case Success(_) =>
    log.info("File ingestion completed")
  case Failure(ex) =>
    log.error("File ingestion failed", ex)
}

// Monitor health
if (source.isHealthy) {
  log.info(s"Processed ${source.currentOffset()} lines")
}

// Graceful stop
Await.result(source.stop(), 30.seconds)
```

### Complete Kafka Source Example

```scala
import com.dataflow.sources.KafkaSource
import com.dataflow.domain.models.SourceConfig

// Configuration
val config = SourceConfig(
  sourceType = "kafka",
  connectionString = "localhost:9092",
  config = Map(
    "topic" -> "orders",
    "group-id" -> "order-processors",
    "format" -> "json",
    "auto-offset-reset" -> "earliest",
    "max-poll-records" -> "500"
  ),
  batchSize = 1000,
  pollIntervalMs = 1000
)

// Create source
val source = KafkaSource("order-pipeline", config)

// Start consumption
source.start(pipelineShardRegion)

// Monitor progress
log.info(s"Current offset: ${source.currentOffset()}")

// Graceful stop
source.stop()
```

## Next Steps

- Implement custom source connectors by extending the `Source` trait
- Add monitoring and alerting for source health
- Configure data quality checks on ingested data
- Set up proper offset persistence for resumability
- Review [Transform Connectors](TRANSFORM_CONNECTORS.md) for data processing
- Review [Sink Connectors](SINK_CONNECTORS.md) for data output
