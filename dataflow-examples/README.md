# DataFlow Platform - Integration Examples

This module contains sophisticated integration examples demonstrating the complete dataflow platform capabilities.

## Overview

The examples showcase production-ready patterns for building streaming data pipelines using:

- **File Sources**: CSV and JSON file reading with sophisticated parsing
- **Transforms**: Filtering, mapping, and aggregation with type-safe operators
- **Sinks**: Console output with rich formatting and colors
- **Functional Programming**: Cats Effect for error handling and resource management
- **Type Safety**: ADTs and type classes throughout

## Examples

### 1. FileToConsoleApp

A comprehensive integration application demonstrating three complete pipelines:

#### Pipeline 1: CSV Source → Filter → Console
- Reads user activity data from CSV file
- Filters to show only purchase actions
- Outputs results in pretty JSON format with colors

#### Pipeline 2: JSON Source → Map Transform → Console
- Reads e-commerce orders from JSON file
- Maps and transforms fields (calculates totals, renames fields)
- Outputs in structured format

#### Pipeline 3: JSON Source → Filter → Aggregate → Console
- Reads orders and filters by status
- Aggregates by customer with multiple aggregation functions:
  - Count of orders
  - Sum, average, min, max of prices
- Outputs results in table format

## Running the Examples

### Prerequisites

- Scala 2.13.16
- SBT 1.9+
- JDK 11 or higher

### Run the Integration Application

```bash
# From the project root
sbt "dataflow-examples/runMain com.dataflow.examples.FileToConsoleApp"
```

The application will:
1. Create sample CSV and JSON data files in `dataflow-examples/data/`
2. Run all three pipelines sequentially
3. Display results to console with rich formatting
4. Print metrics and statistics at the end

## Sample Data

The application automatically generates sample data:

### users.csv
User activity log with columns: id, user, action, timestamp, value

### orders.json
E-commerce orders in newline-delimited JSON format with fields:
- id: Order ID
- customer: Customer name
- product: Product name
- price: Product price
- quantity: Quantity ordered
- status: Order status (shipped/pending/delivered)

## Architecture Highlights

### Functional Error Handling
Uses Cats Effect IO monad for:
- Composable error handling
- Resource management
- Referential transparency

### Resource Management
```scala
Resource.make(
  acquire = IO(createActorSystem)
)(
  release = system => IO(system.terminate())
)
```

### Type-Safe Pipeline Composition
```scala
source.stream()
  .via(filterTransform.flow)
  .via(mapTransform.flow)
  .via(aggregateTransform.flow)
  .runWith(consoleSink.sink)
```

### Production Patterns
- Health checks on sinks
- Metrics collection (throughput, success rate)
- Graceful shutdown
- Comprehensive error handling
- Backpressure management

## Key Features Demonstrated

### Sources
- CSV file reading with header parsing
- JSON file reading (NDJSON format)
- Configurable batch sizes
- Offset tracking and resumption

### Transforms
- **FilterTransform**: Type-safe comparison operators (Equals, In, GreaterThan, etc.)
- **MapTransform**: Field mapping and transformation with expressions
- **AggregateTransform**: Windowed aggregations with type class-based aggregators

### Sinks
- **ConsoleSink**: Multiple output formats (JSON, Table, Structured, KeyValue)
- ANSI color support with auto-detection
- Configurable formatting and metadata display
- Automatic metrics and summary reporting

## Customization

### Add Your Own Pipeline

```scala
def runCustomPipeline(): IO[Unit] = {
  resourcePipeline(
    name = "My Custom Pipeline",
    description = "Your pipeline description"
  ) { implicit system =>
    implicit val ec: ExecutionContext = system.executionContext

    for {
      source <- IO(createYourSource())
      transform <- IO(createYourTransform())
      sink <- IO.fromEither(createYourSink())
      _ <- IO.fromFuture(IO {
        source.stream()
          .via(transform.flow)
          .runWith(sink.sink)
      })
      _ <- IO.fromFuture(IO(sink.close()))
    } yield ()
  }
}
```

### Configure Console Output

```scala
ConsoleSink.builder()
  .withFormat(OutputFormat.PrettyJSON)    // or Table, Structured, KeyValue, Simple
  .withColorScheme(ColorScheme.Enabled)   // or Disabled, Auto
  .withTimestamp(true)                    // show timestamps
  .withMetadata(false)                    // hide metadata
  .withSummary(true)                      // print summary at end
  .build()
```

## Testing

Sample data is automatically created by the application. To use your own data:

1. Place your files in `dataflow-examples/data/`
2. Update the file paths in the pipeline configuration
3. Adjust the transforms as needed for your data schema

## Advanced Topics

### Cats Effect Integration
All pipelines use Cats Effect IO for:
- Pure functional programming
- Composable error handling
- Resource safety

### Type Class Pattern
The AggregateTransform uses type classes for extensible aggregators:

```scala
trait Aggregator {
  def aggregate(records: Seq[DataRecord]): String
}

val customAggregator: Aggregator = new Aggregator {
  override def aggregate(records: Seq[DataRecord]): String = {
    // Your custom aggregation logic
  }
}
```

### ADT-Based Type Safety
All types use sealed traits for exhaustive pattern matching:
- TransformType
- SinkType
- HealthStatus
- ComparisonOperator

## Troubleshooting

### Actor System Errors
Ensure only one actor system is running at a time. The examples use Resource management to ensure proper cleanup.

### File Not Found
The application creates sample data automatically. If you see file errors, check write permissions in `dataflow-examples/data/`.

### Compilation Errors
Ensure all dependencies are properly resolved:
```bash
sbt clean compile
```

## Next Steps

1. Modify the sample data to match your use case
2. Create custom transforms for your business logic
3. Integrate with real data sources (Kafka, databases, APIs)
4. Add more sophisticated aggregations
5. Implement custom sinks for your target systems

## References

- [Apache Pekko Documentation](https://pekko.apache.org/docs/pekko/current/)
- [Cats Effect Documentation](https://typelevel.org/cats-effect/)
- [DataFlow Platform Documentation](../docs/)
