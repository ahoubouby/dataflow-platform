# DataFlow Sources Architecture

## Overview

The `dataflow-sources` module provides source connectors for ingesting data from various external systems into the DataFlow platform. The architecture is designed for extensibility, performance, and reliability.

## Directory Structure

```
dataflow-sources/
├── src/main/scala/com/dataflow/sources/
│   ├── Source.scala                      # Base trait and factory
│   ├── SourceMetricsReporter.scala       # Metrics instrumentation
│   ├── file/
│   │   ├── FileSourceBase.scala          # Abstract base for file sources
│   │   ├── CSVFileSource.scala           # CSV parsing
│   │   ├── JSONFileSource.scala          # JSON parsing
│   │   └── TextFileSource.scala          # Plain text
│   ├── kafka/
│   │   └── KafkaSource.scala             # Kafka consumer
│   ├── api/
│   │   ├── RestApiSource.scala           # REST API polling
│   │   └── WebSocketSource.scala         # WebSocket streaming
│   ├── database/
│   │   └── JdbcSource.scala              # JDBC database queries
│   └── test/
│       └── TestSource.scala              # Test data generator
```

## Design Patterns

### 1. Stream-Based Sources (Primary Pattern)

**Used by**: FileSource, KafkaSource, RestApiSource, JdbcSource, WebSocketSource

**Rationale**:
- **Backpressure**: Pekko Streams automatically handle backpressure, preventing memory overflow
- **Composability**: Streams can be easily composed with operators (map, filter, throttle, etc.)
- **Error Handling**: Built-in supervision strategies and restart logic
- **Performance**: Optimized for continuous data flow
- **Resource Management**: Automatic cleanup and lifecycle management

**Example**:
```scala
class RestApiSource extends Source {
  def stream(): PekkoSource[DataRecord, Future[Done]] = {
    PekkoSource
      .tick(0.seconds, pollInterval, ())
      .throttle(requestsPerSecond, 1.second)  // Rate limiting
      .mapAsync(1)(_ => fetchFromApi())
      .mapConcat(identity)
      .mapMaterializedValue(_ => Future.successful(Done))
  }
}
```

### 2. Template Method Pattern

**Used by**: File sources (FileSourceBase + format-specific subclasses)

**Rationale**:
- **Code Reuse**: Common file I/O logic in base class
- **Extensibility**: Easy to add new file formats
- **Separation of Concerns**: Format-specific parsing isolated in subclasses

**Structure**:
```scala
abstract class FileSourceBase {
  protected def formatName: String  // Must implement
  protected def buildFormatStream(): PekkoSource[DataRecord, NotUsed]  // Must implement

  // Common logic provided by base class
  protected def createLineStream(): PekkoSource[(String, Long), NotUsed]
  protected def recordLineMetrics(line: String): Unit
  protected def createMetadata(lineNumber: Long): Map[String, String]
}
```

### 3. Factory Pattern

**Used by**: Source companion object

**Rationale**:
- **Centralized Creation**: Single point for source instantiation
- **Type Safety**: Compile-time checking of source types
- **Easy Testing**: Can inject mock sources

**Example**:
```scala
object Source {
  def apply(pipelineId: String, config: SourceConfig): Source = {
    config.sourceType match {
      case SourceType.File     => // Auto-detect format from config
        val format = config.options.getOrElse("format", "text")
        format match {
          case "csv"  => CSVFileSource(pipelineId, config)
          case "json" => JSONFileSource(pipelineId, config)
          case "text" => TextFileSource(pipelineId, config)
        }
      case SourceType.Kafka    => KafkaSource(pipelineId, config)
      case SourceType.Api      => RestApiSource(pipelineId, config)
      case SourceType.Database => JdbcSource(pipelineId, config)
    }
  }
}
```

## SourceActor.scala - Decision NOT to Implement

### Question

Should we create a `SourceActor.scala` base trait for actor-based sources?

### Decision: NO

**Rationale**:

1. **Stream-based is superior for data ingestion**:
   - Pekko Streams provide natural backpressure
   - Better resource management
   - Optimized for continuous data flow
   - Built-in error recovery with RestartSource

2. **Actors serve different purpose**:
   - TestSource uses actors because it's a **control/testing** tool, not a real data source
   - Actors are better for command/control patterns (Start, Stop, GetStatus)
   - Actors have limited backpressure support compared to streams

3. **Mixed approach is intentional**:
   - Production sources: Stream-based (File, Kafka, API, Database, WebSocket)
   - Test/control sources: Actor-based (TestSource)
   - This separation is by design, not an architectural inconsistency

4. **Complexity vs benefit**:
   - Creating a SourceActor base trait would encourage actor-based sources
   - This would be counter to best practices for data ingestion
   - Would add complexity without real benefit

### When to Use Actors vs Streams

**Use Actors when**:
- Implementing control/command patterns
- Managing stateful entities with complex lifecycle
- Building test/mock sources (like TestSource)

**Use Streams when**:
- Ingesting continuous data
- Need backpressure handling
- Processing data pipelines
- External system integration (Kafka, databases, APIs)

### Conclusion

**Do NOT create SourceActor.scala**. The current architecture is correct:
- ✅ Stream-based sources for production data ingestion
- ✅ Actor-based TestSource for testing
- ✅ Clear separation of concerns

If future requirements demand actor-based sources, evaluate on a case-by-case basis, but the default should always be stream-based.

## Source Features

### Rate Limiting (RestApiSource)

RestApiSource implements rate limiting using Pekko Streams' `throttle` operator:

```scala
val rateLimitedSource = if (rateLimitEnabled) {
  tickSource.throttle(
    requestsPerSecond,
    1.second,
    burst,
    ThrottleMode.Shaping  // Smooths out bursts
  )
} else {
  tickSource
}
```

**Configuration**:
- `rate-limit-enabled`: Enable/disable rate limiting
- `rate-limit-requests-per-second`: Max requests per second
- `rate-limit-burst`: Burst capacity for occasional spikes

### WebSocket Streaming (WebSocketSource)

WebSocketSource provides real-time streaming with:
- **Automatic reconnection** with exponential backoff
- **Heartbeat/ping-pong** for connection keep-alive
- **Backpressure handling** via Pekko Streams
- **Authentication** support (query params, headers, bearer tokens)

**Features**:
```scala
RestartSource.withBackoff(restartSettings) { () =>
  messageSource
    .viaMat(webSocketFlow)(Keep.right)
    .collect {
      case TextMessage.Strict(text) => text
    }
    .map(parseMessage)
}
```

### Metrics Instrumentation

All sources report metrics via `SourceMetricsReporter`:
- Records read (counter)
- Bytes read (counter)
- Batches sent (counter)
- Errors (counter by type)
- Latency (histogram)
- Health status (gauge)
- Offset/position (gauge)

**Special metrics**:
- **Kafka**: Consumer lag per partition
- **API**: Response time, HTTP status codes, pagination page
- **Database**: Query execution time, watermark (timestamp/ID tracking)

## Adding New Sources

### Stream-Based Source (Recommended)

1. Extend the `Source` trait
2. Implement required methods: `stream()`, `start()`, `stop()`, etc.
3. Use Pekko Streams for data flow
4. Report metrics via `SourceMetricsReporter`
5. Add to factory in `Source.scala`

### Example Template

```scala
class MyNewSource(
  val pipelineId: String,
  val config: SourceConfig,
)(implicit system: ActorSystem[_]) extends Source {

  override val sourceId: String = s"mynew-source-$pipelineId-${UUID.randomUUID()}"

  override def stream(): PekkoSource[DataRecord, Future[Done]] = {
    PekkoSource
      .tick(0.seconds, pollInterval, ())
      .mapAsync(1)(_ => fetchData())
      .mapConcat(identity)
      .map { data =>
        SourceMetricsReporter.recordRecordsRead(pipelineId, "mynew", 1)
        DataRecord(id = data.id, data = data.fields, metadata = createMetadata())
      }
      .mapMaterializedValue(_ => Future.successful(Done))
  }

  override def start(pipelineShardRegion: ActorRef[ShardingEnvelope[Command]]): Future[Done] = {
    // Connect to external system and start streaming
  }

  override def stop(): Future[Done] = {
    // Graceful shutdown
  }

  // ... other required methods
}
```

## Best Practices

1. **Always use streams for data ingestion**
2. **Report metrics for observability**
3. **Implement graceful shutdown** (stop() method)
4. **Handle errors gracefully** (use RestartSource for auto-recovery)
5. **Document configuration options** in class scaladoc
6. **Add unit and integration tests**
7. **Follow the naming convention**: `{Type}Source` (e.g., RestApiSource, JdbcSource)

## Testing

- Unit tests: Test parsing, configuration, error handling
- Integration tests: Test with real external systems (using testcontainers)
- Performance tests: Verify throughput and latency
- Chaos tests: Test failure recovery and reconnection

## Future Enhancements

Potential sources to add:
- `ChangeDataCaptureSource`: Database CDC (Debezium-style)
- `GrpcSource`: gRPC streaming
- `S3Source`: AWS S3 file watching
- `ParquetFileSource`: Parquet format support (extend FileSourceBase)
- `AvroFileSource`: Avro format support (extend FileSourceBase)
