# Transform Integration Guide

> **How to integrate Transforms with Sources, Sinks, and Pipeline Components**

---

## 📚 Table of Contents

1. [Overview](#overview)
2. [Integration Architecture](#integration-architecture)
3. [Source → Transform Integration](#source--transform-integration)
4. [Transform → Sink Integration](#transform--sink-integration)
5. [PipelineAggregate Integration](#pipelineaggregate-integration)
6. [Complete Pipeline Examples](#complete-pipeline-examples)
7. [Advanced Patterns](#advanced-patterns)
8. [Testing Integration](#testing-integration)
9. [Troubleshooting](#troubleshooting)

---

## Overview

The DataFlow Platform follows a **Source → Transform → Sink** architecture, where:

- **Sources** ingest data (Kafka, Files, APIs)
- **Transforms** process and modify data
- **Sinks** output data (Kafka, Cassandra, Elasticsearch, Files)

All components use **Pekko Streams** for seamless integration with automatic backpressure.

---

## Integration Architecture

### High-Level Flow

```
┌─────────┐      ┌─────────────┐      ┌──────┐
│  Source │ ───▶ │  Transform  │ ───▶ │ Sink │
│ (Kafka) │      │   Chain     │      │(File)│
└─────────┘      └─────────────┘      └──────┘
                        │
                        ▼
                ┌──────────────┐
                │   Pipeline   │
                │  Aggregate   │
                │ (Monitoring) │
                └──────────────┘
```

### Component Types

```scala
// Source: Produces data
trait DataSource {
  def source: Source[DataRecord, NotUsed]
}

// Transform: Processes data
trait Transform {
  def flow: Flow[DataRecord, DataRecord, NotUsed]
}

// Sink: Consumes data
trait DataSink {
  def sink: Sink[DataRecord, Future[Done]]
}
```

### Key Integration Points

1. **Source Output → Transform Input**: Both use `DataRecord`
2. **Transform Output → Sink Input**: Both use `DataRecord`
3. **PipelineAggregate**: Orchestrates the entire pipeline
4. **Backpressure**: Automatic flow control across all components

---

## Source → Transform Integration

### Pattern 1: Single Transform

```scala
import com.dataflow.sources.kafka.KafkaSource
import com.dataflow.transforms.filters.FilterTransform
import com.dataflow.transforms.domain.FilterConfig

// Create source
val kafkaSource = new KafkaSource(KafkaSourceConfig(
  topic = "raw-events",
  bootstrapServers = "localhost:9092"
))

// Create transform
val filterTransform = new FilterTransform(
  FilterConfig("$.eventType == purchase")
)

// Connect: Source → Transform
val transformedSource = kafkaSource.source
  .via(filterTransform.flow)

// Continue to sink...
transformedSource.runWith(sink)
```

### Pattern 2: Transform Chain

```scala
import com.dataflow.sources.file.FileSource
import com.dataflow.transforms.filters.FilterTransform
import com.dataflow.transforms.mapping.{MapTransform, FlatMapTransform}

// Create source
val fileSource = new FileSource(FileSourceConfig(
  path = "/data/users.csv",
  format = CSV
))

// Create transform chain
val filterConfig = FilterConfig("$.age >= 18")
val mapConfig = MapConfig(Map("firstName" -> "first_name"))
val flatMapConfig = FlatMapConfig("interests")

val pipeline = fileSource.source
  .via(new FilterTransform(filterConfig).flow)
  .via(new MapTransform(mapConfig).flow)
  .via(new FlatMapTransform(flatMapConfig).flow)

// Continue to sink...
```

### Pattern 3: Conditional Transforms

```scala
// Apply different transforms based on source type
val pipeline = source match {
  case kafka: KafkaSource =>
    kafka.source
      .via(kafkaSpecificTransform.flow)
      .via(commonTransform.flow)

  case file: FileSource =>
    file.source
      .via(fileSpecificTransform.flow)
      .via(commonTransform.flow)
}
```

### Pattern 4: Source with Transform Configuration

```scala
// Source provides transform configuration
case class SourceConfig(
  sourceConfig: KafkaSourceConfig,
  transforms: Seq[TransformationConfig]
)

class ConfigurableSource(config: SourceConfig) {
  private val source = new KafkaSource(config.sourceConfig)
  private val transforms = TransformFactory.createChain(config.transforms).get

  def pipeline: Source[DataRecord, NotUsed] = {
    transforms.foldLeft(source.source) { (src, transform) =>
      src.via(transform.flow)
    }
  }
}
```

---

## Transform → Sink Integration

### Pattern 1: Simple Transform → Sink

```scala
import com.dataflow.sinks.kafka.KafkaSink
import com.dataflow.transforms.mapping.MapTransform

// Create transform
val transform = new MapTransform(
  MapConfig(Map("raw_field" -> "processed_field"))
)

// Create sink
val kafkaSink = new KafkaSink(KafkaSinkConfig(
  topic = "processed-events",
  bootstrapServers = "localhost:9092"
))

// Connect: Source → Transform → Sink
val pipeline = source.source
  .via(transform.flow)
  .runWith(kafkaSink.sink)
```

### Pattern 2: Multi-Sink Fanout

```scala
// Send transformed data to multiple destinations
val transformedSource = source.source
  .via(filterTransform.flow)
  .via(mapTransform.flow)

// Branch to multiple sinks
val kafkaSink = transformedSource.runWith(kafkaSink1.sink)
val fileSink = transformedSource.runWith(fileSink.sink)
val esSink = transformedSource.runWith(elasticsearchSink.sink)

// Wait for all to complete
val completion = for {
  _ <- kafkaSink
  _ <- fileSink
  _ <- esSink
} yield Done
```

### Pattern 3: Conditional Sink Routing

```scala
// Route to different sinks based on data
val pipeline = source.source
  .via(transform.flow)
  .alsoTo(Flow[DataRecord].filter(_.data.get("priority") == Some("high"))
    .to(highPrioritySink.sink))
  .filter(_.data.get("priority") == Some("normal"))
  .runWith(normalPrioritySink.sink)
```

### Pattern 4: Batching Before Sink

```scala
// Batch records for efficient sink writes
val pipeline = source.source
  .via(filterTransform.flow)
  .via(mapTransform.flow)
  .grouped(100)  // Batch 100 records
  .mapAsync(1) { batch =>
    // Sink expects individual records, so flatten
    Source(batch)
      .runWith(batchSink.sink)
  }
  .runWith(Sink.ignore)
```

---

## PipelineAggregate Integration

The **PipelineAggregate** is an event-sourced entity that manages pipeline lifecycle and tracks execution.

### Integration Architecture

```
┌────────────────────┐
│ PipelineAggregate  │
│  (Event Sourced)   │
└─────────┬──────────┘
          │
          │ Commands
          ▼
┌──────────────────────┐
│   Pipeline Runner    │
│                      │
│  Source → Transform  │
│    ↓         ↓       │
│  Sink ← ─ ← ─┘       │
└──────────────────────┘
```

### Pattern 1: Create Pipeline with Transforms

```scala
import com.dataflow.domain.commands.PipelineCommands._
import com.dataflow.domain.models._

// Define pipeline configuration
val pipelineConfig = PipelineConfig(
  pipelineId = "user-processing-pipeline",
  name = "User Data Processing",
  source = SourceConfig(
    sourceType = "kafka",
    config = Map(
      "topic" -> "raw-users",
      "bootstrap.servers" -> "localhost:9092"
    )
  ),
  transforms = Seq(
    // Old format (will be migrated to new format)
    TransformConfig(
      transformType = "filter",
      config = Map("expression" -> "$.age >= 18")
    ),
    TransformConfig(
      transformType = "map",
      config = Map(
        "mappings" -> "{\"firstName\":\"first_name\"}",
        "preserveUnmapped" -> "true"
      )
    )
  ),
  sink = SinkConfig(
    sinkType = "kafka",
    config = Map(
      "topic" -> "processed-users",
      "bootstrap.servers" -> "localhost:9092"
    )
  )
)

// Send command to aggregate
pipelineAggregate ! CreatePipeline(pipelineConfig)
```

### Pattern 2: Start Pipeline with Transforms

```scala
// After pipeline is created, start it
pipelineAggregate ! StartPipeline

// Internally, the aggregate will:
// 1. Load transform configurations
// 2. Create Transform instances via TransformFactory
// 3. Build Source → Transform Chain → Sink
// 4. Start the Pekko Streams pipeline
```

### Pattern 3: Monitor Transform Execution

```scala
// PipelineAggregate tracks transform metrics
case class PipelineMetrics(
  recordsProcessed: Long,
  recordsFiltered: Long,    // by FilterTransform
  recordsTransformed: Long, // by MapTransform
  recordsSplit: Long,       // by FlatMapTransform
  errors: Long,
  lastUpdated: Instant
)

// Query metrics
pipelineAggregate ! GetMetrics
```

### Pattern 4: Dynamic Transform Updates

```scala
// Update transforms at runtime
val newTransforms = Seq(
  TransformConfig(
    transformType = "filter",
    config = Map("expression" -> "$.age >= 21")  // Changed threshold
  )
)

pipelineAggregate ! UpdatePipeline(
  pipelineConfig.copy(transforms = newTransforms)
)

// Aggregate will:
// 1. Pause current pipeline
// 2. Recreate transform chain
// 3. Resume with new transforms
```

---

## Complete Pipeline Examples

### Example 1: Kafka → Transform → Cassandra

```scala
package com.dataflow.examples

import com.dataflow.sources.kafka.KafkaSource
import com.dataflow.transforms._
import com.dataflow.sinks.cassandra.CassandraSink
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Source

object KafkaToCassandraPipeline {

  def run(implicit system: ActorSystem[_]): Unit = {
    // 1. Configure source
    val kafkaSource = new KafkaSource(KafkaSourceConfig(
      topic = "raw-events",
      bootstrapServers = "localhost:9092",
      groupId = "event-processor"
    ))

    // 2. Configure transforms
    val filterConfig = FilterConfig("$.eventType == user_action")
    val mapConfig = MapConfig(
      mappings = Map(
        "userId" -> "user_id",
        "eventTime" -> "event_time",
        "sessionId" -> null  // Remove PII
      ),
      preserveUnmapped = true
    )

    val filterTransform = new FilterTransform(filterConfig)
    val mapTransform = new MapTransform(mapConfig)

    // 3. Configure sink
    val cassandraSink = new CassandraSink(CassandraSinkConfig(
      keyspace = "analytics",
      table = "user_events",
      consistencyLevel = "QUORUM",
      batchSize = 100
    ))

    // 4. Build and run pipeline
    val pipeline = kafkaSource.source
      .via(filterTransform.flow)
      .via(mapTransform.flow)
      .runWith(cassandraSink.sink)

    // 5. Monitor completion
    pipeline.onComplete {
      case Success(_) => println("Pipeline completed successfully")
      case Failure(ex) => println(s"Pipeline failed: ${ex.getMessage}")
    }
  }
}
```

### Example 2: File → Multi-Transform → Multiple Sinks

```scala
package com.dataflow.examples

import com.dataflow.sources.file.FileSource
import com.dataflow.transforms._
import com.dataflow.sinks._
import org.apache.pekko.stream.scaladsl.{Source, Sink, Broadcast, GraphDSL, RunnableGraph}
import org.apache.pekko.stream.{ClosedShape, ActorMaterializer}

object FileProcessingPipeline {

  def run(implicit system: ActorSystem[_], mat: ActorMaterializer): Unit = {
    // Source: CSV file
    val fileSource = new FileSource(FileSourceConfig(
      path = "/data/orders.csv",
      format = CSV
    ))

    // Transforms
    val filterConfirmed = new FilterTransform(
      FilterConfig("status == confirmed")
    )

    val normalizeFields = new MapTransform(MapConfig(
      mappings = Map(
        "customerId" -> "customer_id",
        "orderDate" -> "order_date",
        "totalAmount" -> "total_amount"
      )
    ))

    val splitLineItems = new FlatMapTransform(FlatMapConfig(
      splitField = "lineItems",
      targetField = Some("item")
    ))

    // Sinks
    val kafkaSink = new KafkaSink(KafkaSinkConfig(
      topic = "order-events",
      bootstrapServers = "localhost:9092"
    ))

    val fileSink = new FileSink(FileSinkConfig(
      path = "/data/processed/orders.json",
      format = JSON
    ))

    val elasticsearchSink = new ElasticsearchSink(ElasticsearchSinkConfig(
      index = "orders",
      hosts = Seq("localhost:9200")
    ))

    // Build graph with fanout
    val graph = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      // Transform chain
      val source = fileSource.source
      val filtered = source.via(filterConfirmed.flow)
      val mapped = filtered.via(normalizeFields.flow)
      val split = mapped.via(splitLineItems.flow)

      // Broadcast to multiple sinks
      val broadcast = builder.add(Broadcast[DataRecord](3))

      split ~> broadcast.in
      broadcast.out(0) ~> kafkaSink.sink
      broadcast.out(1) ~> fileSink.sink
      broadcast.out(2) ~> elasticsearchSink.sink

      ClosedShape
    })

    graph.run()
  }
}
```

### Example 3: API → Transform → Kafka (with PipelineAggregate)

```scala
package com.dataflow.examples

import com.dataflow.domain.commands.PipelineCommands._
import com.dataflow.domain.models._
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.AskPattern._

object ApiToKafkaPipeline {

  def create(implicit system: ActorSystem[_]): Unit = {
    // Get PipelineAggregate reference
    val pipelineAggregate = ... // Get from cluster sharding

    // Define pipeline configuration
    val config = PipelineConfig(
      pipelineId = "api-to-kafka",
      name = "API Data Ingestion",
      source = SourceConfig(
        sourceType = "api",
        config = Map(
          "url" -> "https://api.example.com/events",
          "method" -> "GET",
          "pollInterval" -> "10s"
        )
      ),
      transforms = Seq(
        // Filter valid events
        TransformConfig(
          transformType = "filter",
          config = Map(
            "expression" -> "$.status == success"
          )
        ),
        // Normalize fields
        TransformConfig(
          transformType = "map",
          config = Map(
            "mappings" -> """{"eventId":"event_id","timestamp":"event_time"}""",
            "preserveUnmapped" -> "true"
          )
        ),
        // Add metadata
        TransformConfig(
          transformType = "map",
          config = Map(
            "mappings" -> """{"ingested":"status","api":"source"}"""
          )
        )
      ),
      sink = SinkConfig(
        sinkType = "kafka",
        config = Map(
          "topic" -> "ingested-events",
          "bootstrap.servers" -> "localhost:9092"
        )
      )
    )

    // Create pipeline
    pipelineAggregate ! CreatePipeline(config)

    // Start pipeline
    pipelineAggregate ! StartPipeline
  }
}
```

---

## Advanced Patterns

### Pattern 1: Dynamic Transform Injection

```scala
// Add transforms dynamically based on runtime conditions
class DynamicPipeline(baseConfig: PipelineConfig) {

  def withDataQualityChecks(): PipelineConfig = {
    val qualityTransforms = Seq(
      TransformConfig("filter", Map("expression" -> "$.quality >= 0.8")),
      TransformConfig("map", Map("mappings" -> """{"checked":"quality_status"}"""))
    )

    baseConfig.copy(
      transforms = baseConfig.transforms ++ qualityTransforms
    )
  }

  def withPIIRedaction(): PipelineConfig = {
    val redactionTransform = TransformConfig(
      "map",
      Map("mappings" -> """{"ssn":null,"creditCard":null}""")
    )

    baseConfig.copy(
      transforms = baseConfig.transforms :+ redactionTransform
    )
  }
}

// Usage
val pipeline = new DynamicPipeline(baseConfig)
  .withDataQualityChecks()
  .withPIIRedaction()
```

### Pattern 2: Error Handling Pipeline

```scala
// Separate pipelines for success and errors
val (successSink, errorSink) = source.source
  .via(transform.flow)
  .divertTo(errorSink, record => isError(record))
  .alsoToMat(successSink)(Keep.right)
  .run()
```

### Pattern 3: A/B Testing Transforms

```scala
// Split traffic to test different transforms
val splitFlow = Flow[DataRecord].partition(2, record =>
  if (record.id.hashCode % 2 == 0) 0 else 1
)

val graph = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
  import GraphDSL.Implicits._

  val split = builder.add(splitFlow)
  val merge = builder.add(Merge[DataRecord](2))

  source.source ~> split
  split.out(0) ~> transformA.flow ~> merge
  split.out(1) ~> transformB.flow ~> merge
  merge ~> sink.sink

  ClosedShape
})
```

### Pattern 4: Transform Pipeline with Circuit Breaker

```scala
import org.apache.pekko.pattern.CircuitBreaker

val breaker = CircuitBreaker(
  system.scheduler,
  maxFailures = 5,
  callTimeout = 10.seconds,
  resetTimeout = 1.minute
)

val resilientPipeline = source.source
  .via(transform.flow)
  .mapAsync(1) { record =>
    breaker.withCircuitBreaker(Future(record))
  }
  .runWith(sink.sink)
```

---

## Testing Integration

### Unit Testing Transforms

```scala
import org.apache.pekko.stream.scaladsl.{Source, Sink}
import org.apache.pekko.stream.testkit.scaladsl.{TestSource, TestSink}
import org.scalatest.wordspec.AnyWordSpec

class FilterTransformSpec extends AnyWordSpec {

  "FilterTransform" should {
    "filter records matching condition" in {
      implicit val system = ActorSystem()

      val transform = new FilterTransform(FilterConfig("$.age > 18"))

      val input = Seq(
        DataRecord("1", Map("age" -> "25", "name" -> "John")),
        DataRecord("2", Map("age" -> "15", "name" -> "Jane"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      result.head.id shouldBe "1"
    }
  }
}
```

### Integration Testing: Source → Transform → Sink

```scala
class PipelineIntegrationSpec extends AnyWordSpec {

  "Complete pipeline" should {
    "process records end-to-end" in {
      // Use TestContainers for Kafka
      val kafka = KafkaContainer()
      kafka.start()

      val source = new KafkaSource(...)
      val transform = new FilterTransform(...)
      val sink = new KafkaSink(...)

      val pipeline = source.source
        .via(transform.flow)
        .runWith(sink.sink)

      // Verify results
      pipeline.futureValue shouldBe Done
    }
  }
}
```

---

## Troubleshooting

### Common Issues

#### Issue 1: Backpressure Buildup

**Symptom**: Pipeline slows down, memory usage increases

**Cause**: Sink is slower than source, buffer fills up

**Solution**:
```scala
// Add buffering
source.source
  .buffer(1000, OverflowStrategy.backpressure)
  .via(transform.flow)
  .runWith(sink.sink)

// Or add throttling
source.source
  .throttle(100, 1.second)
  .via(transform.flow)
  .runWith(sink.sink)
```

#### Issue 2: Transform Errors Stopping Pipeline

**Symptom**: Pipeline stops on first error

**Cause**: ErrorHandlingStrategy.Fail (default may vary)

**Solution**:
```scala
// Use Skip strategy
transform.errorHandler = ErrorHandlingStrategy.Skip

// Or add supervision
source.source
  .via(transform.flow)
  .withAttributes(ActorAttributes.supervisionStrategy(resumingDecider))
  .runWith(sink.sink)
```

#### Issue 3: Transform Configuration Not Applied

**Symptom**: Records not transformed as expected

**Cause**: Configuration error or transform not in chain

**Solution**:
```scala
// Verify transform is in pipeline
println(s"Transform type: ${transform.transformType}")

// Check configuration
println(s"Config: ${config}")

// Add logging
source.source
  .via(Flow[DataRecord].map { r => println(s"Before: $r"); r })
  .via(transform.flow)
  .via(Flow[DataRecord].map { r => println(s"After: $r"); r })
  .runWith(sink.sink)
```

---

## Next Steps

- Review [TRANSFORMS.md](TRANSFORMS.md) for detailed transform documentation
- Check [SPRINT_PLANNING.md](SPRINT_PLANNING.md) for upcoming features
- Explore `examples/` directory for more integration patterns

---

**Last Updated**: Sprint 3, Day 1-2 Complete
**Status**: ✅ Stateless Transforms + Integration Patterns
