package com.dataflow.transforms.domain

import com.dataflow.serialization.CborSerializable
import scala.concurrent.duration.FiniteDuration

/**
 * Sealed trait hierarchy for transform configurations.
 *
 * This replaces the simple Map[String, String] config from core
 * with type-safe, structured configuration for each transform type.
 */
sealed trait TransformationConfig extends CborSerializable {
  def transformType: TransformType
}

// ============================================
// STATELESS TRANSFORMS
// ============================================

/**
 * Filter transformation: Keep only records matching a condition.
 *
 * Examples:
 * - JSONPath: "$.age > 18"
 * - Simple: "status == active"
 * - Complex: "$.user.premium == true && $.amount > 100"
 *
 * @param expression The filter expression (JSONPath or simple comparison)
 */
final case class FilterConfig(
  expression: String
) extends TransformationConfig {
  override def transformType: TransformType = TransformType.Filter
}

/**
 * Map transformation: Transform record fields.
 *
 * Supports:
 * - Field renaming: {"oldName": "newName"}
 * - Field extraction: {"user.name": "userName"} (nested field access)
 * - Field deletion: {"fieldToRemove": null}
 * - Constant injection: {"newField": "constantValue"}
 *
 * @param mappings Map of source field -> target field
 * @param preserveUnmapped If true, keep fields not mentioned in mappings
 */
final case class MapConfig(
  mappings: Map[String, String],
  preserveUnmapped: Boolean = true
) extends TransformationConfig {
  override def transformType: TransformType = TransformType.Map
}

/**
 * FlatMap transformation: Split one record into multiple records.
 *
 * Example:
 * Input:  {id: 1, items: ["a", "b", "c"]}
 * Output: [{id: 1, item: "a"}, {id: 1, item: "b"}, {id: 1, item: "c"}]
 *
 * @param splitField The array field to split on
 * @param targetField The name of the field in output records (default: same as splitField without 's')
 * @param preserveParent If true, include parent record fields in each output
 */
final case class FlatMapConfig(
  splitField: String,
  targetField: Option[String] = None,
  preserveParent: Boolean = true
) extends TransformationConfig {
  override def transformType: TransformType = TransformType.FlatMap
}

// ============================================
// STATEFUL TRANSFORMS
// ============================================

/**
 * Aggregate transformation: Group and aggregate records.
 *
 * Example:
 * - Group by userId
 * - Count events per user
 * - Sum purchase amounts per user
 * - Emit aggregated result every 1 minute
 *
 * @param groupByFields Fields to group by (e.g., ["userId", "country"])
 * @param aggregations Map of field -> aggregation function
 * @param windowSize Time window for aggregation
 */
final case class AggregateConfig(
  groupByFields: Seq[String],
  aggregations: Map[String, AggregationType],
  windowSize: FiniteDuration
) extends TransformationConfig {
  override def transformType: TransformType = TransformType.Aggregate
}

/**
 * Types of aggregation operations.
 */
sealed trait AggregationType extends CborSerializable

object AggregationType {
  /** Count number of records in group */
  case object Count extends AggregationType

  /** Sum a numeric field */
  final case class Sum(field: String) extends AggregationType

  /** Calculate average of a numeric field */
  final case class Average(field: String) extends AggregationType

  /** Find minimum value of a field */
  final case class Min(field: String) extends AggregationType

  /** Find maximum value of a field */
  final case class Max(field: String) extends AggregationType

  /** Collect all values of a field into an array */
  final case class Collect(field: String) extends AggregationType

  /** Get first value seen */
  final case class First(field: String) extends AggregationType

  /** Get last value seen */
  final case class Last(field: String) extends AggregationType
}

/**
 * Enrich transformation: Lookup and add data from external source.
 *
 * Example:
 * - Input: {userId: "123"}
 * - Lookup: Get user profile from Redis/Cassandra
 * - Output: {userId: "123", userName: "John", userTier: "Premium"}
 *
 * @param lookupField Field to use for lookup (e.g., "userId")
 * @param lookupSource External source configuration
 * @param targetFields Fields to add from lookup result
 * @param cacheEnabled Whether to cache lookup results
 * @param cacheTtl Cache TTL if enabled
 */
final case class EnrichConfig(
  lookupField: String,
  lookupSource: LookupSource,
  targetFields: Seq[String],
  cacheEnabled: Boolean = true,
  cacheTtl: Option[FiniteDuration] = None
) extends TransformationConfig {
  override def transformType: TransformType = TransformType.Enrich
}

/**
 * External lookup source configuration.
 */
sealed trait LookupSource extends CborSerializable

object LookupSource {
  /** Lookup from Redis cache */
  final case class Redis(
    host: String,
    port: Int,
    keyPattern: String // e.g., "user:${userId}"
  ) extends LookupSource

  /** Lookup from Cassandra table */
  final case class Cassandra(
    keyspace: String,
    table: String,
    keyColumn: String
  ) extends LookupSource

  /** Lookup from in-memory map (for testing) */
  final case class InMemory(
    data: Map[String, Map[String, String]]
  ) extends LookupSource
}
