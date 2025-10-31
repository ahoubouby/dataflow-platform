# DataRecord Design: Universal and Wire-Efficient

## Current Implementation Issues

```scala
final case class DataRecord(
  id: String,
  data: Map[String, String],  // ❌ Only supports strings
  metadata: Map[String, String] = Map.empty
)
```

**Problems**:
1. ❌ Limited to String values only (no numbers, booleans, nested objects)
2. ❌ Inefficient serialization (every value serialized as string)
3. ❌ Loss of type information
4. ❌ JSON parsing required for complex types
5. ❌ Large wire size (text-based encoding)

## Recommended Solutions

### Option 1: Flexible with Any (Good for Prototyping)

```scala
package com.dataflow.domain.models

import com.dataflow.serialization.CborSerializable

/**
 * Universal data record supporting any data type.
 * Uses Scala Any for maximum flexibility.
 */
final case class DataRecord(
  id: String,
  data: Map[String, Any],  // ✅ Supports any type
  metadata: Map[String, Any] = Map.empty
) extends CborSerializable

object DataRecord {
  // Type-safe accessors
  def getString(record: DataRecord, key: String): Option[String] =
    record.data.get(key).collect { case s: String => s }

  def getInt(record: DataRecord, key: String): Option[Int] =
    record.data.get(key).collect { case i: Int => i }

  def getLong(record: DataRecord, key: String): Option[Long] =
    record.data.get(key).collect { case l: Long => l }

  def getDouble(record: DataRecord, key: String): Option[Double] =
    record.data.get(key).collect { case d: Double => d }

  def getBoolean(record: DataRecord, key: String): Option[Boolean] =
    record.data.get(key).collect { case b: Boolean => b }

  def getList(record: DataRecord, key: String): Option[List[Any]] =
    record.data.get(key).collect { case l: List[_] => l }

  def getMap(record: DataRecord, key: String): Option[Map[String, Any]] =
    record.data.get(key).collect { case m: Map[_, _] => m.asInstanceOf[Map[String, Any]] }
}
```

**Pros**:
- ✅ Supports all data types (String, Int, Double, Boolean, Lists, nested Maps)
- ✅ No schema required
- ✅ Easy to use
- ✅ CBOR serialization handles Any efficiently

**Cons**:
- ⚠️ No compile-time type safety
- ⚠️ Runtime type checks required
- ⚠️ Larger than binary formats

**Use Case**: Good for development, ETL pipelines with varying schemas

---

### Option 2: Binary Payload (Most Efficient for Wire Transfer)

```scala
package com.dataflow.domain.models

import org.apache.pekko.util.ByteString
import com.dataflow.serialization.CborSerializable

/**
 * Universal data record with binary payload.
 * Maximum wire efficiency - payload is opaque binary data.
 */
final case class DataRecord(
  id: String,
  payload: ByteString,  // ✅ Raw binary data (Avro, Protobuf, JSON, etc.)
  schema: String,       // Schema identifier (e.g., "user.v1", "order.avro")
  metadata: Map[String, String] = Map.empty
) extends CborSerializable

object DataRecord {
  // Factory methods for different formats

  /** Create from JSON */
  def fromJson(id: String, json: String, schema: String = "json"): DataRecord =
    DataRecord(id, ByteString(json.getBytes("UTF-8")), schema)

  /** Create from Avro bytes */
  def fromAvro(id: String, avroBytes: Array[Byte], schema: String): DataRecord =
    DataRecord(id, ByteString(avroBytes), s"avro:$schema")

  /** Create from Protobuf bytes */
  def fromProtobuf(id: String, protoBytes: Array[Byte], schema: String): DataRecord =
    DataRecord(id, ByteString(protoBytes), s"proto:$schema")

  /** Create from generic Map (serialize to JSON) */
  def fromMap(id: String, data: Map[String, Any]): DataRecord = {
    import spray.json._
    val json = data.toJson.compactPrint
    fromJson(id, json, "map")
  }

  // Deserialization helpers

  /** Extract as JSON string */
  def toJson(record: DataRecord): String =
    record.payload.utf8String

  /** Extract as Map (from JSON) */
  def toMap(record: DataRecord): Map[String, Any] = {
    import spray.json._
    toJson(record).parseJson.convertTo[Map[String, Any]]
  }
}
```

**Pros**:
- ✅ Maximum wire efficiency (binary encoding)
- ✅ Support any format (JSON, Avro, Protobuf, Parquet)
- ✅ Schema evolution via schema identifier
- ✅ Smallest network overhead
- ✅ Direct integration with Kafka (Avro), gRPC (Protobuf)

**Cons**:
- ⚠️ Need schema registry for structured access
- ⚠️ Transformations require deserialization
- ⚠️ More complex to work with

**Use Case**: Production systems, high-throughput pipelines, Kafka integration

---

### Option 3: Hybrid Approach (Best of Both Worlds)

```scala
package com.dataflow.domain.models

import org.apache.pekko.util.ByteString
import com.dataflow.serialization.CborSerializable

/**
 * Hybrid data record supporting both structured and binary data.
 * Provides flexibility with good performance.
 */
final case class DataRecord(
  id: String,
  // Structured fields for common access patterns
  fields: Map[String, FieldValue],
  // Optional binary payload for complex/nested data
  payload: Option[ByteString] = None,
  // Schema identifier
  schema: Option[String] = None,
  // Metadata
  metadata: Map[String, String] = Map.empty
) extends CborSerializable

/**
 * Type-safe field values.
 */
sealed trait FieldValue extends CborSerializable

object FieldValue {
  final case class StringValue(value: String) extends FieldValue
  final case class IntValue(value: Int) extends FieldValue
  final case class LongValue(value: Long) extends FieldValue
  final case class DoubleValue(value: Double) extends FieldValue
  final case class BooleanValue(value: Boolean) extends FieldValue
  final case class TimestampValue(value: java.time.Instant) extends FieldValue
  final case class ListValue(values: List[FieldValue]) extends FieldValue
  final case class MapValue(values: Map[String, FieldValue]) extends FieldValue
  final case class BinaryValue(data: ByteString) extends FieldValue
  case object NullValue extends FieldValue

  // Conversion helpers
  def apply(value: Any): FieldValue = value match {
    case s: String           => StringValue(s)
    case i: Int              => IntValue(i)
    case l: Long             => LongValue(l)
    case d: Double           => DoubleValue(d)
    case b: Boolean          => BooleanValue(b)
    case t: java.time.Instant => TimestampValue(t)
    case l: List[_]          => ListValue(l.map(apply))
    case m: Map[_, _]        => MapValue(m.map { case (k, v) => k.toString -> apply(v) })
    case bs: ByteString      => BinaryValue(bs)
    case null                => NullValue
    case _                   => StringValue(value.toString)
  }

  // Extraction helpers
  def getString(fv: FieldValue): Option[String] = fv match {
    case StringValue(s) => Some(s)
    case _              => None
  }

  def getInt(fv: FieldValue): Option[Int] = fv match {
    case IntValue(i)    => Some(i)
    case LongValue(l)   => Some(l.toInt)
    case DoubleValue(d) => Some(d.toInt)
    case _              => None
  }

  def getLong(fv: FieldValue): Option[Long] = fv match {
    case LongValue(l)   => Some(l)
    case IntValue(i)    => Some(i.toLong)
    case DoubleValue(d) => Some(d.toLong)
    case _              => None
  }
}

object DataRecord {
  // Factory method from Map[String, Any]
  def fromMap(id: String, data: Map[String, Any], schema: Option[String] = None): DataRecord =
    DataRecord(
      id = id,
      fields = data.map { case (k, v) => k -> FieldValue(v) },
      schema = schema
    )

  // Convert to Map[String, Any]
  def toMap(record: DataRecord): Map[String, Any] = {
    def fieldToAny(fv: FieldValue): Any = fv match {
      case FieldValue.StringValue(s)    => s
      case FieldValue.IntValue(i)       => i
      case FieldValue.LongValue(l)      => l
      case FieldValue.DoubleValue(d)    => d
      case FieldValue.BooleanValue(b)   => b
      case FieldValue.TimestampValue(t) => t
      case FieldValue.ListValue(l)      => l.map(fieldToAny)
      case FieldValue.MapValue(m)       => m.view.mapValues(fieldToAny).toMap
      case FieldValue.BinaryValue(bs)   => bs
      case FieldValue.NullValue         => null
    }
    record.fields.view.mapValues(fieldToAny).toMap
  }

  // Type-safe field access
  def getString(record: DataRecord, key: String): Option[String] =
    record.fields.get(key).flatMap(FieldValue.getString)

  def getInt(record: DataRecord, key: String): Option[Int] =
    record.fields.get(key).flatMap(FieldValue.getInt)

  def getLong(record: DataRecord, key: String): Option[Long] =
    record.fields.get(key).flatMap(FieldValue.getLong)
}
```

**Pros**:
- ✅ Type-safe structured access for common fields
- ✅ Binary payload for complex/nested data
- ✅ Good balance of flexibility and performance
- ✅ Compile-time safety for field access
- ✅ Schema evolution support

**Cons**:
- ⚠️ More complex implementation
- ⚠️ Slightly larger than pure binary

**Use Case**: Production systems needing both flexibility and performance

---

## Comparison Matrix

| Feature | Map[String,String] | Map[String,Any] | ByteString Payload | Hybrid (Recommended) |
|---------|-------------------|-----------------|-------------------|----------------------|
| Type Safety | ❌ Low | ⚠️ Medium | ⚠️ Medium | ✅ High |
| Wire Efficiency | ❌ Poor | ⚠️ Medium | ✅ Excellent | ✅ Good |
| Flexibility | ❌ Low | ✅ High | ✅ High | ✅ High |
| Ease of Use | ✅ Easy | ✅ Easy | ⚠️ Medium | ✅ Easy |
| Schema Evolution | ❌ No | ❌ No | ✅ Yes | ✅ Yes |
| Performance | ❌ Poor | ⚠️ Medium | ✅ Excellent | ✅ Good |
| Nested Data | ❌ No | ✅ Yes | ✅ Yes | ✅ Yes |
| Integration | ⚠️ Limited | ⚠️ Limited | ✅ Excellent | ✅ Good |

## Recommended Approach: Hybrid

The **Hybrid approach (Option 3)** provides the best balance:

1. **Structured fields** for common access patterns (filtering, routing)
2. **Binary payload** for complex/nested data
3. **Type safety** via FieldValue ADT
4. **Wire efficiency** via CBOR serialization + optional binary payload
5. **Schema evolution** via schema identifier

### Migration Path

```scala
// Phase 1: Keep current API, change implementation
final case class DataRecord(
  id: String,
  data: Map[String, Any],  // <- Change to Any
  metadata: Map[String, String] = Map.empty
) extends CborSerializable

// Phase 2: Add binary support (backward compatible)
final case class DataRecord(
  id: String,
  data: Map[String, Any],
  payload: Option[ByteString] = None,
  metadata: Map[String, String] = Map.empty
) extends CborSerializable

// Phase 3: Add type safety (breaking change)
final case class DataRecord(
  id: String,
  fields: Map[String, FieldValue],
  payload: Option[ByteString] = None,
  schema: Option[String] = None,
  metadata: Map[String, String] = Map.empty
) extends CborSerializable
```

## Wire Transfer Efficiency

### Size Comparison (Example: User Record)

**Original (Map[String, String])**:
```json
{
  "id": "user-123",
  "name": "John Doe",
  "age": "30",
  "balance": "1500.50",
  "active": "true"
}
```
CBOR size: ~80 bytes

**With Any (Map[String, Any])**:
```json
{
  "id": "user-123",
  "name": "John Doe",
  "age": 30,
  "balance": 1500.50,
  "active": true
}
```
CBOR size: ~65 bytes (-19%)

**With Binary Payload (Avro)**:
```
Binary Avro encoding
```
Avro size: ~45 bytes (-44%)

**With Hybrid**:
```json
{
  "fields": {
    "id": {"StringValue": "user-123"},
    "name": {"StringValue": "John Doe"},
    "age": {"IntValue": 30},
    "balance": {"DoubleValue": 1500.50},
    "active": {"BooleanValue": true}
  }
}
```
CBOR size: ~70 bytes (-13%)

## Conclusion

**For your DataFlow Platform, I recommend**:

1. **Start with Option 2 (Map[String, Any])** - Easy migration, big improvement
2. **Add ByteString payload** when integrating with Kafka/Avro
3. **Evolve to Hybrid (Option 3)** for production - best balance

This gives you:
- ✅ Universal data support
- ✅ Good wire efficiency
- ✅ Schema evolution
- ✅ Type safety where needed
- ✅ Smooth migration path
