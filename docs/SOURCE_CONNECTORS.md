# DataFlow Platform - Source Connectors

This guide covers the data source connectors available in the DataFlow Platform, including configuration, usage, and best practices.

## Table of Contents

- [Overview](#overview)
- [Source Abstraction](#source-abstraction)
- [File Source](#file-source)
- [Kafka Source](#kafka-source)
- [API Source](#api-source)
- [WebSocket Source](#websocket-source)
- [Database Source](#database-source)
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

## API Source

The API Source connector ingests data from REST APIs using HTTP polling. It supports various authentication methods, pagination strategies, and can handle JSON responses.

### Key Features

- **HTTP Methods**: GET, POST requests
- **Authentication**: Basic, Bearer token, API key, or none
- **Pagination**: Offset-based, page-based, cursor-based, or none
- **Polling**: Configurable interval-based polling
- **JSON Parsing**: Automatic parsing with configurable response paths
- **Rate Limiting**: Built-in backpressure and polling interval control
- **Retry Logic**: Automatic recovery from transient failures

### Configuration

#### Basic API Configuration

```scala
SourceConfig(
  sourceType = SourceType.Api,
  connectionString = "https://api.example.com/users",
  options = Map(
    "method" -> "GET",
    "auth-type" -> "none",
    "response-path" -> "data"  // JSON path to data array
  ),
  batchSize = 100,
  pollIntervalMs = 5000  // Poll every 5 seconds
)
```

#### Authentication Types

**No Authentication**:
```scala
Map(
  "auth-type" -> "none"
)
```

**Basic Authentication**:
```scala
Map(
  "auth-type" -> "basic",
  "auth-username" -> "myuser",
  "auth-password" -> "mypassword"
)
```

**Bearer Token**:
```scala
Map(
  "auth-type" -> "bearer",
  "auth-token" -> "your-jwt-token-here"
)
```

**API Key**:
```scala
Map(
  "auth-type" -> "api-key",
  "auth-token" -> "your-api-key",
  "auth-key-name" -> "X-API-Key"  // Optional, defaults to X-API-Key
)
```

#### Pagination Strategies

**No Pagination** (single request):
```scala
Map(
  "pagination-type" -> "none"
)
```

**Offset-Based Pagination**:
```scala
Map(
  "pagination-type" -> "offset",
  "pagination-param" -> "offset",        // Query param name
  "pagination-size-param" -> "limit",    // Size param name
  "pagination-size" -> "100"             // Records per request
)
// Generates: /api/users?offset=0&limit=100, /api/users?offset=100&limit=100, ...
```

**Page-Based Pagination**:
```scala
Map(
  "pagination-type" -> "page",
  "pagination-param" -> "page",
  "pagination-size-param" -> "per_page",
  "pagination-size" -> "50"
)
// Generates: /api/users?page=1&per_page=50, /api/users?page=2&per_page=50, ...
```

**Cursor-Based Pagination**:
```scala
Map(
  "pagination-type" -> "cursor",
  "pagination-param" -> "cursor",
  "pagination-size-param" -> "limit",
  "pagination-size" -> "100"
)
// Looks for "next_cursor", "nextCursor", or "cursor" in response
// Generates: /api/users?cursor=abc123&limit=100
```

#### Response Path Configuration

Use dot notation to navigate nested JSON:

```scala
Map(
  "response-path" -> "data"           // { "data": [...] }
)

Map(
  "response-path" -> "result.users"   // { "result": { "users": [...] } }
)
```

#### Custom Headers

```scala
Map(
  "headers" -> """{"Content-Type":"application/json","X-Custom-Header":"value"}"""
)
```

#### Rate Limiting

The API Source supports built-in rate limiting to prevent overwhelming external APIs:

```scala
Map(
  "rate-limit-enabled" -> "true",               // Enable rate limiting
  "rate-limit-requests-per-second" -> "10",     // Max 10 requests/second
  "rate-limit-burst" -> "20"                    // Allow bursts up to 20
)
```

**How it works**:
- Uses token bucket algorithm via Pekko Streams throttle
- `requests-per-second`: Sustained rate limit
- `burst`: Allows temporary bursts above sustained rate
- `ThrottleMode.Shaping`: Smooths out request distribution

**When to use**:
- External APIs with strict rate limits
- Preventing 429 (Too Many Requests) errors
- Protecting third-party services
- Compliance with API terms of service

**Example with rate limiting**:
```scala
SourceConfig(
  sourceType = SourceType.Api,
  connectionString = "https://api.example.com/data",
  options = Map(
    "method" -> "GET",
    "auth-type" -> "bearer",
    "auth-token" -> "your-token",
    "rate-limit-enabled" -> "true",
    "rate-limit-requests-per-second" -> "5",  // Max 5 req/s
    "rate-limit-burst" -> "10",               // Burst up to 10
    "pagination-type" -> "offset",
    "pagination-size" -> "100"
  ),
  batchSize = 100,
  pollIntervalMs = 1000  // Poll every second, but rate limited to 5/s
)
```

### Complete Example: GitHub API

```scala
SourceConfig(
  sourceType = SourceType.Api,
  connectionString = "https://api.github.com/repos/apache/pekko/issues",
  options = Map(
    "method" -> "GET",
    "auth-type" -> "bearer",
    "auth-token" -> System.getenv("GITHUB_TOKEN"),
    "pagination-type" -> "page",
    "pagination-param" -> "page",
    "pagination-size-param" -> "per_page",
    "pagination-size" -> "100",
    "response-path" -> ".",  // Response is array at root
    "headers" -> """{"Accept":"application/vnd.github.v3+json"}"""
  ),
  batchSize = 100,
  pollIntervalMs = 60000  // Poll every minute
)
```

### Monitoring

Key metrics to monitor:

- **Response Time**: API response latency (histogram)
- **Status Codes**: HTTP status code distribution
- **Request Rate**: API requests per second
- **Pagination Progress**: Current page/cursor
- **Parse Errors**: Failed JSON parsing count
- **Connection Errors**: Network failures

### Best Practices

1. **Rate Limiting**: Set appropriate `pollIntervalMs` to respect API rate limits
2. **Authentication**: Store tokens in environment variables, not code
3. **Pagination**: Use cursor-based when available for better consistency
4. **Response Path**: Verify the JSON structure matches your `response-path`
5. **Error Handling**: Monitor status codes and parse errors
6. **Timeouts**: Consider API response times when setting poll intervals

### Troubleshooting

#### Problem: "API request failed with status: 429"

**Cause**: Rate limit exceeded

**Solution**:
- Increase `pollIntervalMs`
- Check API rate limit documentation
- Implement exponential backoff (future enhancement)

#### Problem: "Response path 'data' did not return array"

**Cause**: JSON structure doesn't match configured path

**Solution**:
- Verify API response structure
- Use browser or curl to inspect response
- Adjust `response-path` to match actual structure
- Ensure path points to an array

#### Problem: "Failed to parse API response"

**Cause**: Invalid JSON or unexpected format

**Solution**:
- Check API response format
- Verify Content-Type header
- Enable debug logging to see raw response
- Check for API version changes

## WebSocket Source

The WebSocket Source connector provides real-time streaming data ingestion from WebSocket APIs. Unlike the REST API Source which polls periodically, WebSocket Source maintains a persistent connection for instant data delivery.

### Key Features

- **Real-Time Streaming**: Persistent WebSocket connection for instant data delivery
- **Automatic Reconnection**: Exponential backoff on connection failures
- **Heartbeat/Keep-Alive**: Periodic ping-pong to maintain connection
- **Authentication**: Query params, headers, or bearer tokens
- **Message Formats**: JSON or plain text
- **Backpressure Handling**: Built-in via Pekko Streams
- **Error Recovery**: Graceful handling of connection drops

### Configuration

#### Basic WebSocket Configuration

```scala
SourceConfig(
  sourceType = SourceType.Api,  // WebSocket uses Api source type
  connectionString = "wss://stream.example.com/events",
  options = Map(
    "source-impl" -> "websocket",      // Specify WebSocket implementation
    "message-format" -> "json",         // or "text"
    "auth-type" -> "none"
  ),
  batchSize = 100,
  pollIntervalMs = 0  // Not used for WebSocket
)
```

#### Authentication

**No Authentication**:
```scala
Map(
  "auth-type" -> "none"
)
```

**Query Parameter Authentication**:
```scala
Map(
  "auth-type" -> "query",
  "auth-token" -> "your-token",
  "auth-param-name" -> "token"  // Query param name
)
// Generates: wss://api.example.com/stream?token=your-token
```

**Header-Based Authentication**:
```scala
Map(
  "auth-type" -> "header",
  "auth-token" -> "your-api-key",
  "auth-param-name" -> "X-API-Key"
)
```

**Bearer Token**:
```scala
Map(
  "auth-type" -> "bearer",
  "auth-token" -> "your-jwt-token"
)
```

#### Heartbeat Configuration

Keep the connection alive with periodic pings:

```scala
Map(
  "heartbeat-interval" -> "30"  // Send ping every 30 seconds
)
```

Set to `"0"` to disable heartbeat (not recommended for long-lived connections).

#### Reconnection Strategy

Configure automatic reconnection with exponential backoff:

```scala
Map(
  "reconnect-min-backoff" -> "1",      // Start with 1 second
  "reconnect-max-backoff" -> "30",     // Cap at 30 seconds
  "reconnect-max-restarts" -> "-1"     // Infinite retries (-1), or set a limit
)
```

**Backoff behavior**:
- First retry: After 1 second
- Second retry: After ~2 seconds (1 * 2^1 * random factor)
- Third retry: After ~4 seconds
- ...continues doubling until max-backoff

#### Message Formats

**JSON Messages**:
```scala
Map(
  "message-format" -> "json"
)
```

Expected JSON structure:
```json
{
  "id": "event-123",
  "type": "user_action",
  "data": {...}
}
```

If `id` field is missing, a UUID is generated automatically.

**Text Messages**:
```scala
Map(
  "message-format" -> "text"
)
```

Each text message becomes a DataRecord with `message` field.

### Complete Examples

#### Example 1: Crypto Price Stream

```scala
SourceConfig(
  sourceType = SourceType.Api,
  connectionString = "wss://stream.binance.com:9443/ws/btcusdt@trade",
  options = Map(
    "source-impl" -> "websocket",
    "message-format" -> "json",
    "auth-type" -> "none",
    "heartbeat-interval" -> "30",
    "reconnect-min-backoff" -> "1",
    "reconnect-max-backoff" -> "60",
    "reconnect-max-restarts" -> "-1"
  ),
  batchSize = 50,
  pollIntervalMs = 0
)
```

#### Example 2: Authenticated Event Stream

```scala
SourceConfig(
  sourceType = SourceType.Api,
  connectionString = "wss://api.example.com/events",
  options = Map(
    "source-impl" -> "websocket",
    "message-format" -> "json",
    "auth-type" -> "bearer",
    "auth-token" -> System.getenv("WS_TOKEN"),
    "heartbeat-interval" -> "20",
    "reconnect-min-backoff" -> "2",
    "reconnect-max-backoff" -> "30",
    "reconnect-max-restarts" -> "10"  // Give up after 10 retries
  ),
  batchSize = 100,
  pollIntervalMs = 0
)
```

#### Example 3: Simple Text Stream

```scala
SourceConfig(
  sourceType = SourceType.Api,
  connectionString = "ws://localhost:8080/logs",
  options = Map(
    "source-impl" -> "websocket",
    "message-format" -> "text",
    "auth-type" -> "none",
    "heartbeat-interval" -> "0"  // No heartbeat needed
  ),
  batchSize = 200,
  pollIntervalMs = 0
)
```

### Monitoring

Key metrics to monitor:

- **Connection Status**: Up/down state
- **Reconnection Count**: How many times reconnected
- **Message Rate**: Messages received per second
- **Message Size**: Bytes received
- **Parse Errors**: Failed message parsing
- **Latency**: Time from server send to client receive (if timestamp in message)

### Best Practices

1. **Always Enable Heartbeat**: Use `heartbeat-interval` to detect dead connections
2. **Set Reasonable Backoff**: Start small (1-2s), cap at reasonable max (30-60s)
3. **Monitor Connection Health**: Track reconnection frequency
4. **Handle Partial Messages**: WebSocket can send streamed messages (handled automatically)
5. **Secure Connections**: Use `wss://` (secure) instead of `ws://` in production
6. **Authentication Tokens**: Store in environment variables, refresh if needed
7. **Backpressure**: Use appropriate `batchSize` to handle bursts

### Troubleshooting

#### Problem: "WebSocket upgrade failed"

**Cause**: Connection could not be established

**Solution**:
- Verify URL is correct (wss:// or ws://)
- Check firewall/network allows WebSocket connections
- Verify authentication credentials
- Check server supports WebSocket protocol

#### Problem: Frequent disconnections/reconnections

**Cause**: Network instability or server-side issues

**Solution**:
- Enable heartbeat to detect dead connections faster
- Increase `reconnect-max-backoff` for less aggressive retries
- Check server logs for errors
- Verify network stability

#### Problem: "Failed to parse JSON message"

**Cause**: Message format doesn't match expected JSON

**Solution**:
- Verify `message-format` is set correctly
- Check actual messages received (enable debug logging)
- Ensure server sends valid JSON
- Handle both strict and streamed text messages

#### Problem: High memory usage

**Cause**: Messages arriving faster than processing

**Solution**:
- Reduce `batchSize` for more frequent pipeline sends
- Check pipeline processing performance
- Monitor backpressure metrics
- Consider adding throttling if needed

### WebSocket vs REST API Source

| Feature | WebSocket Source | REST API Source |
|---------|------------------|-----------------|
| Connection | Persistent | Request/response |
| Latency | Instant (< 100ms) | Poll interval dependent |
| Server Load | Low (one connection) | Higher (repeated requests) |
| Use Case | Real-time events | Periodic data fetch |
| Complexity | Medium | Simple |
| Reliability | Requires reconnection logic | Stateless |

**Choose WebSocket when**:
- Need real-time data (< 1 second latency)
- High-frequency updates (multiple per second)
- Server supports push notifications
- Examples: Stock tickers, chat messages, live logs

**Choose REST API when**:
- Periodic data fetch is acceptable (minutes/hours)
- Server doesn't support WebSocket
- Simpler to implement and debug
- Examples: Daily reports, user lists, config data

## Database Source

The Database Source connector ingests data from relational databases using JDBC. It supports periodic polling, incremental queries, and various database systems.

### Key Features

- **JDBC Support**: Works with PostgreSQL, MySQL, Oracle, SQL Server, etc.
- **Incremental Sync**: Timestamp-based or ID-based incremental queries
- **Periodic Polling**: Configurable poll intervals
- **Batch Fetching**: Efficient result set streaming with JDBC fetch size
- **Connection Management**: Automatic connection lifecycle
- **SQL Queries**: Full SQL query support with parameter binding
- **Multiple Data Types**: Automatic type conversion to strings

### Configuration

#### Basic Database Configuration

```scala
SourceConfig(
  sourceType = SourceType.Database,
  connectionString = "jdbc:postgresql://localhost:5432/mydb",
  options = Map(
    "username" -> "dbuser",
    "password" -> "dbpass",
    "driver" -> "org.postgresql.Driver",
    "query" -> "SELECT * FROM users",
    "fetch-size" -> "1000"
  ),
  batchSize = 1000,
  pollIntervalMs = 60000  // Poll every minute
)
```

#### Supported Databases

**PostgreSQL**:
```scala
Map(
  "driver" -> "org.postgresql.Driver",
  // connectionString: jdbc:postgresql://host:5432/database
)
```

**MySQL**:
```scala
Map(
  "driver" -> "com.mysql.cj.jdbc.Driver",
  // connectionString: jdbc:mysql://host:3306/database
)
```

**Oracle**:
```scala
Map(
  "driver" -> "oracle.jdbc.OracleDriver",
  // connectionString: jdbc:oracle:thin:@host:1521:sid
)
```

**SQL Server**:
```scala
Map(
  "driver" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver",
  // connectionString: jdbc:sqlserver://host:1433;databaseName=mydb
)
```

#### Incremental Queries

**Timestamp-Based Incremental Sync**:
```scala
Map(
  "query" -> "SELECT * FROM events WHERE created_at > ?",
  "incremental-column" -> "created_at",
  "incremental-type" -> "timestamp",
  "start-time" -> "2024-01-01 00:00:00"  // Optional start time
)
```

The `?` placeholder is automatically filled with the last seen timestamp.

**ID-Based Incremental Sync**:
```scala
Map(
  "query" -> "SELECT * FROM users WHERE id > ?",
  "incremental-column" -> "id",
  "incremental-type" -> "id",
  "start-id" -> "0"  // Optional start ID
)
```

The `?` placeholder is automatically filled with the last seen ID.

**No Incremental Sync** (full table scan each poll):
```scala
Map(
  "query" -> "SELECT * FROM users WHERE active = true"
  // No incremental-column configured
)
```

#### Performance Tuning

**Fetch Size**: Controls how many rows are fetched from the database at once
```scala
Map(
  "fetch-size" -> "5000"  // Larger = fewer round trips, more memory
)
```

**Poll Interval**: How often to check for new data
```scala
// pollIntervalMs in SourceConfig
pollIntervalMs = 300000  // 5 minutes
```

**Batch Size**: How many records to batch before sending to pipeline
```scala
// batchSize in SourceConfig
batchSize = 1000
```

### Complete Examples

#### Incremental CDC-style Pattern

```scala
SourceConfig(
  sourceType = SourceType.Database,
  connectionString = "jdbc:postgresql://localhost:5432/orders_db",
  options = Map(
    "username" -> System.getenv("DB_USER"),
    "password" -> System.getenv("DB_PASS"),
    "driver" -> "org.postgresql.Driver",
    "query" -> """
      SELECT id, customer_id, product_id, amount, status, created_at
      FROM orders
      WHERE created_at > ?
      ORDER BY created_at ASC
    """,
    "incremental-column" -> "created_at",
    "incremental-type" -> "timestamp",
    "start-time" -> "2024-01-01 00:00:00",
    "fetch-size" -> "2000"
  ),
  batchSize = 500,
  pollIntervalMs = 30000  // Check every 30 seconds
)
```

#### Complex JOIN Query

```scala
SourceConfig(
  sourceType = SourceType.Database,
  connectionString = "jdbc:mysql://localhost:3306/analytics",
  options = Map(
    "username" -> "analyst",
    "password" -> "password",
    "driver" -> "com.mysql.cj.jdbc.Driver",
    "query" -> """
      SELECT
        u.id, u.email, u.name,
        o.order_id, o.amount, o.status,
        o.created_at as order_date
      FROM users u
      JOIN orders o ON u.id = o.user_id
      WHERE o.created_at > ?
      AND o.status = 'completed'
    """,
    "incremental-column" -> "created_at",
    "incremental-type" -> "timestamp",
    "fetch-size" -> "1000"
  ),
  batchSize = 1000,
  pollIntervalMs = 60000
)
```

#### Read-Only Replica Configuration

```scala
SourceConfig(
  sourceType = SourceType.Database,
  connectionString = "jdbc:postgresql://replica.example.com:5432/production",
  options = Map(
    "username" -> "readonly_user",
    "password" -> System.getenv("REPLICA_PASSWORD"),
    "driver" -> "org.postgresql.Driver",
    "query" -> """
      SELECT * FROM large_table
      WHERE id > ?
      AND processed = false
    """,
    "incremental-column" -> "id",
    "incremental-type" -> "id",
    "start-id" -> "0",
    "fetch-size" -> "10000"  // Large fetch for better performance
  ),
  batchSize = 5000,
  pollIntervalMs = 120000  // Poll every 2 minutes
)
```

### Data Type Handling

All database column values are converted to strings in `DataRecord`:

```scala
// Database row:
// id=123, name="Alice", age=30, balance=1000.50, active=true, created_at=2024-01-01 10:00:00

// Becomes DataRecord:
DataRecord(
  id = "123",
  data = Map(
    "id" -> "123",
    "name" -> "Alice",
    "age" -> "30",
    "balance" -> "1000.5",
    "active" -> "true",
    "created_at" -> "2024-01-01 10:00:00.0"
  ),
  metadata = Map(
    "source" -> "database",
    "jdbc_url" -> "jdbc:postgresql://...",
    "format" -> "sql"
  )
)
```

NULL values are converted to empty strings.

### Monitoring

Key metrics to monitor:

- **Query Execution Time**: Time taken to execute SQL query (histogram)
- **Rows Fetched**: Number of rows read from database
- **Incremental Watermark**: Current timestamp/ID value for incremental sync
- **Connection Errors**: Failed database connections
- **Query Errors**: SQL execution failures

### Best Practices

1. **Read-Only Access**: Use read-only database users for safety
2. **Indexes**: Ensure incremental columns are indexed for performance
3. **Replicas**: Read from replicas, not production master
4. **Connection Pooling**: Consider HikariCP for connection management (future)
5. **Query Optimization**: Use EXPLAIN to optimize queries
6. **Monitoring**: Track watermark progress to detect stuck pipelines
7. **Security**: Store credentials in environment variables or secrets management

### Troubleshooting

#### Problem: "Failed to load JDBC driver"

**Cause**: Driver class not on classpath

**Solution**:
- Add appropriate JDBC driver dependency to build.sbt
- Verify driver class name is correct
- Check driver compatibility with Java version

#### Problem: "Slow query performance"

**Cause**: Missing indexes or inefficient query

**Solution**:
- Add index on incremental column
- Use EXPLAIN to analyze query plan
- Increase `fetch-size` for better throughput
- Consider partitioning large tables
- Add WHERE clauses to reduce result set

#### Problem: "Connection timeout"

**Cause**: Database unreachable or slow

**Solution**:
- Verify network connectivity
- Check database firewall rules
- Increase connection timeout in JDBC URL
- Verify credentials are correct

#### Problem: "Incremental sync not progressing"

**Cause**: Watermark not updating or no new data

**Solution**:
- Check incremental column exists and has values
- Verify query has `?` placeholder
- Check metrics for watermark value
- Ensure ORDER BY on incremental column
- Verify data is being inserted into source table

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
