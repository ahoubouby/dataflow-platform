# DataFlow Platform - Transforms Module

> **Learning Material: Understanding and Using Data Transformations**

---

## 📚 Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Transform Types](#transform-types)
4. [Configuration](#configuration)
5. [Error Handling](#error-handling)
6. [Integration Patterns](#integration-patterns)
7. [Performance Considerations](#performance-considerations)
8. [Examples](#examples)

---

## Overview

The Transforms module provides a powerful, composable framework for data transformation using **Apache Pekko Streams**. Transforms process data records flowing through the pipeline, enabling filtering, mapping, aggregation, and enrichment operations.

### Key Features

- ✅ **Backpressure Handling**: Built on Pekko Streams for automatic flow control
- ✅ **Composability**: Chain multiple transforms together seamlessly
- ✅ **Type Safety**: Compile-time guarantees with sealed trait hierarchies
- ✅ **Error Handling**: Configurable strategies (skip, fail, dead-letter)
- ✅ **Observability**: Structured logging for debugging and monitoring

### Design Principles

```
DataRecord → [Transform Chain] → DataRecord
                    ↓
          Pekko Streams Flow
          (Automatic Backpressure)
```

Every transform is a `Flow[DataRecord, DataRecord, NotUsed]`, which means:
- **Input**: Stream of DataRecord
- **Output**: Stream of DataRecord (transformed)
- **Materialized Value**: NotUsed (pure transformation)

---

## Architecture

### Core Abstractions

#### 1. Transform Trait

```scala
trait Transform {
  /** Name/type of this transform */
  def transformType: String

  /** The Pekko Streams Flow that performs transformation */
  def flow: Flow[DataRecord, DataRecord, NotUsed]

  /** Error handling strategy */
  def errorHandler: ErrorHandlingStrategy = ErrorHandlingStrategy.Skip
}
```

#### 2. Transform Categories

**Stateless Transforms** (process each record independently):
- `FilterTransform` - Keep only matching records
- `MapTransform` - Transform record fields
- `FlatMapTransform` - Split one record into many

**Stateful Transforms** (maintain state across records):
- `AggregateTransform` - Group and aggregate records (coming in Day 3-4)
- `EnrichTransform` - Lookup from external sources (coming in Day 4)

#### 3. Configuration Types

```scala
sealed trait TransformationConfig {
  def transformType: String
}

// Stateless
case class FilterConfig(expression: String)
case class MapConfig(mappings: Map[String, String], preserveUnmapped: Boolean)
case class FlatMapConfig(splitField: String, targetField: Option[String])

// Stateful
case class AggregateConfig(groupByFields: Seq[String], aggregations: Map[String, AggregationType], windowSize: FiniteDuration)
case class EnrichConfig(lookupField: String, lookupSource: LookupSource, targetFields: Seq[String])
```

---

## Transform Types

### 1. FilterTransform

**Purpose**: Keep only records that match a condition.

#### Supported Expression Types

##### A. JSONPath Expressions

Use JSONPath for complex queries on nested data:

```scala
// Filter records where age > 18
FilterConfig("$.age > 18")

// Filter premium users
FilterConfig("$.user.premium == true")

// Complex condition
FilterConfig("$.amount >= 100")
```

**Supported Operators**: `==`, `!=`, `>`, `>=`, `<`, `<=`

##### B. Simple Field Comparisons

For basic string matching:

```scala
// Exact match
FilterConfig("status == active")

// Not equal
FilterConfig("type != test")

// Numeric comparison
FilterConfig("priority > 5")
```

#### Examples

```scala
// Example 1: Filter adult users
val filter = new FilterTransform(FilterConfig("$.age > 18"))

// Input:  {id: "1", data: {age: "25", name: "John"}}
// Output: {id: "1", data: {age: "25", name: "John"}}  ✓ passes

// Input:  {id: "2", data: {age: "15", name: "Jane"}}
// Output: (filtered out) ✗

// Example 2: Filter active records
val filter = new FilterTransform(FilterConfig("status == active"))

// Input:  {id: "3", data: {status: "active", value: "100"}}
// Output: {id: "3", data: {status: "active", value: "100"}}  ✓ passes

// Input:  {id: "4", data: {status: "inactive", value: "50"}}
// Output: (filtered out) ✗
```

#### How It Works

1. **Expression Parsing**: Detects JSONPath (starts with `$.`) vs simple comparison
2. **Evaluation**: Evaluates condition against each record
3. **Filtering**: Passes through matching records, drops non-matching
4. **Error Handling**: Configurable behavior for evaluation errors

#### Code Example

```scala
import com.dataflow.transforms.filters.FilterTransform
import com.dataflow.transforms.domain.FilterConfig

// Create filter
val config = FilterConfig("$.age > 18")
val transform = new FilterTransform(config)

// Use in Pekko Streams
Source(records)
  .via(transform.flow)
  .runWith(Sink.foreach(println))
```

---

### 2. MapTransform

**Purpose**: Transform record fields through renaming, extraction, deletion, or injection.

#### Supported Operations

| Operation | Mapping Example | Description |
|-----------|----------------|-------------|
| **Rename** | `{"firstName": "first_name"}` | Rename field |
| **Extract** | `{"user.email": "email"}` | Extract nested field |
| **Delete** | `{"tempField": null}` | Remove field |
| **Inject** | `{"status": "processed"}` | Add constant |

#### Examples

```scala
// Example 1: Rename fields
val config = MapConfig(
  mappings = Map(
    "firstName" -> "first_name",
    "lastName" -> "last_name"
  ),
  preserveUnmapped = true
)

// Input:  {firstName: "John", lastName: "Doe", age: "30"}
// Output: {first_name: "John", last_name: "Doe", age: "30"}

// Example 2: Extract nested fields
val config = MapConfig(
  mappings = Map(
    "user.email" -> "email",
    "user.name" -> "userName"
  )
)

// Input:  {user.email: "john@example.com", user.name: "John", id: "123"}
// Output: {email: "john@example.com", userName: "John", id: "123"}

// Example 3: Delete sensitive fields
val config = MapConfig(
  mappings = Map(
    "password" -> null,
    "ssn" -> null
  )
)

// Input:  {username: "john", password: "secret", ssn: "123-45-6789"}
// Output: {username: "john"}

// Example 4: Inject constant values
val config = MapConfig(
  mappings = Map(
    "processed" -> "status",
    "v1.0" -> "version"
  )
)

// Input:  {data: "value"}
// Output: {data: "value", status: "processed", version: "v1.0"}
```

#### Configuration Options

- **`preserveUnmapped`** (default: `true`): Keep fields not mentioned in mappings
  - `true`: Include all original fields + apply mappings
  - `false`: Only include fields from mappings

#### Code Example

```scala
import com.dataflow.transforms.mapping.MapTransform
import com.dataflow.transforms.domain.MapConfig

// Create transform
val config = MapConfig(
  mappings = Map(
    "old_name" -> "new_name",
    "nested.field" -> "flat_field"
  ),
  preserveUnmapped = true
)
val transform = new MapTransform(config)

// Use in pipeline
Source(records)
  .via(transform.flow)
  .runWith(sink)
```

---

### 3. FlatMapTransform

**Purpose**: Split one record into multiple records (1-to-N transformation).

#### Use Cases

- Split comma-separated lists into individual records
- Explode array fields
- Denormalize data for downstream processing

#### Examples

```scala
// Example 1: Split items list
val config = FlatMapConfig(
  splitField = "items",
  targetField = Some("item"),
  preserveParent = true
)

// Input:  {id: "1", items: "apple,banana,cherry", price: "10"}
// Output: [
//   {id: "1-1", item: "apple", price: "10", parentId: "1", splitIndex: "0"},
//   {id: "1-2", item: "banana", price: "10", parentId: "1", splitIndex: "1"},
//   {id: "1-3", item: "cherry", price: "10", parentId: "1", splitIndex: "2"}
// ]

// Example 2: Auto-derive singular name
val config = FlatMapConfig(
  splitField = "users",  // Will auto-create "user" field
  targetField = None,    // Auto: "users" -> "user"
  preserveParent = false // Only keep split field
)

// Input:  {id: "1", users: "john,jane,bob"}
// Output: [
//   {id: "1-1", user: "john"},
//   {id: "1-2", user: "jane"},
//   {id: "1-3", user: "bob"}
// ]

// Example 3: Categories explosion
val config = FlatMapConfig(
  splitField = "categories",
  targetField = Some("category"),
  preserveParent = true
)

// Input:  {productId: "P123", categories: "electronics,computers,laptops"}
// Output: [
//   {productId: "P123", category: "electronics", ...},
//   {productId: "P123", category: "computers", ...},
//   {productId: "P123", category: "laptops", ...}
// ]
```

#### Configuration Options

- **`splitField`**: Field containing comma-separated values
- **`targetField`**: Name for individual items (optional, auto-derived)
- **`preserveParent`**: Include parent record fields (default: `true`)

#### Auto-Derivation Rules

The transform automatically derives singular names:
- `items` → `item`
- `users` → `user`
- `categories` → `category`
- `classes` → `class`

#### Generated Metadata

Each split record includes:
- **`parentId`**: Original record ID
- **`splitIndex`**: Position in the split array (0-based)
- **New ID**: `{parentId}-{index+1}` (e.g., "1-1", "1-2")

#### Code Example

```scala
import com.dataflow.transforms.mapping.FlatMapTransform
import com.dataflow.transforms.domain.FlatMapConfig

// Create transform
val config = FlatMapConfig(
  splitField = "tags",
  targetField = Some("tag"),
  preserveParent = true
)
val transform = new FlatMapTransform(config)

// Use in pipeline
Source(records)
  .via(transform.flow)  // 1 input -> N outputs
  .runWith(sink)
```

---

## Configuration

### Creating Transforms

#### Option 1: Direct Instantiation

```scala
// Create config
val filterConfig = FilterConfig("$.age > 18")
val mapConfig = MapConfig(Map("old" -> "new"))

// Create transform
val filterTransform = new FilterTransform(filterConfig)
val mapTransform = new MapTransform(mapConfig)
```

#### Option 2: Using TransformFactory

```scala
import com.dataflow.transforms.TransformFactory

// Create single transform
val transform = TransformFactory.create(filterConfig) match {
  case Success(t) => t
  case Failure(ex) => throw ex
}

// Create transform chain
val configs = Seq(filterConfig, mapConfig, flatMapConfig)
val transforms = TransformFactory.createChain(configs) match {
  case Success(ts) => ts
  case Failure(ex) => throw ex
}
```

### Chaining Transforms

```scala
// Method 1: Individual flows
Source(records)
  .via(filterTransform.flow)
  .via(mapTransform.flow)
  .via(flatMapTransform.flow)
  .runWith(sink)

// Method 2: Compose into single flow
val composedFlow = Flow[DataRecord]
  .via(filterTransform.flow)
  .via(mapTransform.flow)
  .via(flatMapTransform.flow)

Source(records)
  .via(composedFlow)
  .runWith(sink)
```

---

## Error Handling

### Error Handling Strategies

```scala
sealed trait ErrorHandlingStrategy

object ErrorHandlingStrategy {
  case object Skip       // Skip failed records, continue processing
  case object Fail       // Fail entire pipeline on first error
  case object DeadLetter // Send failed records to DLQ (TODO)
}
```

### Configuration

```scala
// Default: Skip errors
val transform = new FilterTransform(config) // Uses Skip by default

// Custom error handling (future enhancement)
class CustomFilter(config: FilterConfig) extends FilterTransform(config) {
  override def errorHandler = ErrorHandlingStrategy.Fail
}
```

### Error Scenarios

| Scenario | Skip Behavior | Fail Behavior |
|----------|---------------|---------------|
| Invalid JSONPath expression | Log warning, skip record | Throw exception, stop pipeline |
| Field not found | Log debug, skip record | Throw exception, stop pipeline |
| Type conversion error | Log warning, skip record | Throw exception, stop pipeline |
| Malformed data | Log warning, skip record | Throw exception, stop pipeline |

### Logging

All transforms provide structured logging:

```scala
// Info level: Transform creation, major operations
logger.info(s"Creating transform of type: ${config.transformType}")

// Debug level: Individual record processing
logger.debug(s"Record ${record.id} filtered out by expression")

// Warn level: Recoverable errors
logger.warn(s"Failed to evaluate filter for record ${record.id}, skipping", ex)

// Error level: Pipeline failures
logger.error(s"Failed to map record ${record.id}, failing", ex)
```

---

## Integration Patterns

### 1. Simple Pipeline

```scala
// Source → Transform → Sink
val pipeline = Source(records)
  .via(filterTransform.flow)
  .via(mapTransform.flow)
  .runWith(sink)
```

### 2. Multi-Stage Pipeline

```scala
// Multiple transform stages
val pipeline = Source(records)
  // Stage 1: Filter
  .via(new FilterTransform(FilterConfig("status == active")).flow)

  // Stage 2: Map fields
  .via(new MapTransform(MapConfig(Map("old" -> "new"))).flow)

  // Stage 3: Split arrays
  .via(new FlatMapTransform(FlatMapConfig("items")).flow)

  // Output
  .runWith(sink)
```

### 3. Fan-Out Pattern

```scala
// Send transformed data to multiple sinks
val transformedSource = Source(records)
  .via(filterTransform.flow)
  .via(mapTransform.flow)

// Branch 1: Write to Kafka
transformedSource.runWith(kafkaSink)

// Branch 2: Write to file
transformedSource.runWith(fileSink)
```

### 4. Conditional Transforms

```scala
// Apply different transforms based on record type
val pipeline = Source(records)
  .via(Flow[DataRecord].mapConcat { record =>
    record.data.get("type") match {
      case Some("user") =>
        Source.single(record)
          .via(userTransform.flow)
          .runWith(Sink.seq)
          .futureValue

      case Some("order") =>
        Source.single(record)
          .via(orderTransform.flow)
          .runWith(Sink.seq)
          .futureValue

      case _ => List(record)
    }
  })
  .runWith(sink)
```

### 5. Error Recovery

```scala
// Recover from transform errors
val pipeline = Source(records)
  .via(filterTransform.flow)
  .recover {
    case ex: TransformException =>
      logger.error("Transform failed", ex)
      DataRecord.empty // Or alternative handling
  }
  .via(mapTransform.flow)
  .runWith(sink)
```

---

## Performance Considerations

### 1. Backpressure

All transforms respect Pekko Streams backpressure:

```scala
// Slow sink automatically slows down transforms
Source(millionsOfRecords)
  .via(filterTransform.flow)    // Processes only as fast as sink can consume
  .via(mapTransform.flow)
  .runWith(slowSink)
```

### 2. Parallelization

```scala
// Process records in parallel
Source(records)
  .via(filterTransform.flow)
  .mapAsync(parallelism = 4) { record =>
    Future {
      // Expensive transformation
      expensiveTransform(record)
    }
  }
  .runWith(sink)
```

### 3. Batching

```scala
// Batch records for efficiency
Source(records)
  .via(filterTransform.flow)
  .via(mapTransform.flow)
  .grouped(100)  // Process in batches of 100
  .mapAsync(1)(batch => processBatch(batch))
  .runWith(sink)
```

### 4. Memory Management

```scala
// Avoid accumulating state in stateless transforms
// ✓ Good: FilterTransform doesn't accumulate records
val filter = new FilterTransform(config)
Source(infiniteStream).via(filter.flow)  // Safe

// ✗ Bad: Don't collect all records in memory
Source(infiniteStream).runWith(Sink.seq)  // OOM risk
```

### 5. Expression Compilation

FilterTransform compiles JSONPath expressions on each evaluation. For better performance:

```scala
// Future optimization: Cache compiled expressions
// Current: Recompiles for each record
// Future: Compile once, reuse
```

---

## Examples

### Example 1: User Data Pipeline

```scala
// Filter adult users, normalize names, split interests

val pipeline = Source(userRecords)
  // Keep only adults
  .via(new FilterTransform(
    FilterConfig("$.age >= 18")
  ).flow)

  // Normalize field names
  .via(new MapTransform(
    MapConfig(
      mappings = Map(
        "firstName" -> "first_name",
        "lastName" -> "last_name",
        "emailAddress" -> "email"
      ),
      preserveUnmapped = true
    )
  ).flow)

  // Split interests into separate records
  .via(new FlatMapTransform(
    FlatMapConfig(
      splitField = "interests",
      targetField = Some("interest"),
      preserveParent = true
    )
  ).flow)

  .runWith(Sink.foreach(println))

// Input:  {id: "1", firstName: "John", age: "25", interests: "music,sports,reading"}
// Output: [
//   {id: "1-1", first_name: "John", age: "25", interest: "music", ...},
//   {id: "1-2", first_name: "John", age: "25", interest: "sports", ...},
//   {id: "1-3", first_name: "John", age: "25", interest: "reading", ...}
// ]
```

### Example 2: E-commerce Order Processing

```scala
// Process orders: filter valid, enrich, split line items

val pipeline = Source(orderRecords)
  // Only process confirmed orders
  .via(new FilterTransform(
    FilterConfig("status == confirmed")
  ).flow)

  // Add processing metadata
  .via(new MapTransform(
    MapConfig(
      mappings = Map(
        "processed" -> "processingStatus",
        "v1.0" -> "version"
      ),
      preserveUnmapped = true
    )
  ).flow)

  // Split line items
  .via(new FlatMapTransform(
    FlatMapConfig(
      splitField = "lineItems",
      targetField = Some("item"),
      preserveParent = true
    )
  ).flow)

  .runWith(kafkaSink)
```

### Example 3: Log Processing

```scala
// Filter errors, normalize fields, remove sensitive data

val pipeline = Source(logRecords)
  // Only ERROR level logs
  .via(new FilterTransform(
    FilterConfig("level == ERROR")
  ).flow)

  // Normalize and clean
  .via(new MapTransform(
    MapConfig(
      mappings = Map(
        "msg" -> "message",
        "ts" -> "timestamp",
        "stackTrace" -> null,  // Remove stack traces
        "password" -> null     // Remove sensitive data
      ),
      preserveUnmapped = true
    )
  ).flow)

  .runWith(elasticsearchSink)
```

### Example 4: Data Quality Pipeline

```scala
// Multi-stage data cleaning

val pipeline = Source(rawRecords)
  // Stage 1: Remove test data
  .via(new FilterTransform(
    FilterConfig("env != test")
  ).flow)

  // Stage 2: Remove incomplete records
  .via(new FilterTransform(
    FilterConfig("$.required_field != null")
  ).flow)

  // Stage 3: Standardize field names
  .via(new MapTransform(
    MapConfig(
      mappings = Map(
        "userId" -> "user_id",
        "createdAt" -> "created_at",
        "updatedAt" -> "updated_at"
      )
    )
  ).flow)

  // Stage 4: Add quality score
  .via(new MapTransform(
    MapConfig(
      mappings = Map("high" -> "quality_score")
    )
  ).flow)

  .runWith(cassandraSink)
```

---

## Next Steps

### Upcoming Features (Sprint 3 Day 3-5)

1. **AggregateTransform** (Day 3-4)
   - Window-based aggregation (tumbling, sliding, session)
   - GroupBy with state management
   - Aggregation functions (count, sum, average, min, max, collect)

2. **EnrichTransform** (Day 4)
   - External lookups (Redis, Cassandra)
   - Caching for performance
   - Fallback handling

3. **Transform Chaining** (Day 5)
   - Chain composition utilities
   - Transform pipeline builder
   - Configuration validation

4. **Testing** (Day 5)
   - Comprehensive test suite
   - TestKit examples
   - Integration tests

---

## Additional Resources

- **Source Code**: `dataflow-transforms/src/main/scala/com/dataflow/transforms/`
- **Integration Guide**: `docs/TRANSFORM_INTEGRATION.md`
- **Examples**: `examples/transform-examples.scala`
- **Sprint Planning**: `docs/SPRINT_PLANNING.md` (Sprint 3)

---

## Questions & Support

For questions or issues:
1. Check the Sprint Planning document for roadmap
2. Review integration examples
3. Check logs for debugging information

---

**Last Updated**: Sprint 3, Day 1-2 Complete
**Status**: ✅ Stateless Transforms Implemented | 🔄 Stateful Transforms In Progress
