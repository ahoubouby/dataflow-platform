package com.dataflow.execution

import com.dataflow.domain.models.TransformConfig
import com.dataflow.transforms.domain._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Maps from core TransformConfig (Map[String, String]) to typed TransformationConfig.
 *
 * This bridges the gap between:
 * - API layer: Simple Map-based configuration for flexibility
 * - Transform layer: Type-safe case classes for correctness
 */
object TransformConfigMapper {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Convert generic TransformConfig to type-safe TransformationConfig.
   *
   * @param config The generic transform configuration
   * @return Try containing typed configuration or error
   */
  def toTransformationConfig(config: TransformConfig): Try[TransformationConfig] = {
    config.transformType.toLowerCase match {
      case "filter" => parseFilterConfig(config)
      case "map" => parseMapConfig(config)
      case "flatmap" => parseFlatMapConfig(config)
      case "aggregate" => parseAggregateConfig(config)
      case "enrich" => parseEnrichConfig(config)
      case other =>
        Failure(new IllegalArgumentException(s"Unsupported transform type: $other"))
    }
  }

  // ============================================
  // FILTER
  // ============================================

  private def parseFilterConfig(config: TransformConfig): Try[FilterConfig] = Try {
    val expression = config.config.getOrElse("expression",
      throw new IllegalArgumentException("Filter transform requires 'expression' parameter"))

    val errorStrategy = config.config.get("errorHandling") match {
      case Some("fail") => ErrorHandlingStrategy.Fail
      case Some("skip") | None => ErrorHandlingStrategy.Skip
      case Some("deadletter") => ErrorHandlingStrategy.DeadLetter
      case Some(other) => throw new IllegalArgumentException(s"Unknown error handling strategy: $other")
    }

    FilterConfig(expression, errorStrategy)
  }

  // ============================================
  // MAP
  // ============================================

  private def parseMapConfig(config: TransformConfig): Try[MapConfig] = Try {
    // Parse mappings from config
    // Expected format: "field1->newField1,field2->newField2"
    val mappingsStr = config.config.getOrElse("mappings",
      throw new IllegalArgumentException("Map transform requires 'mappings' parameter"))

    val mappings = mappingsStr.split(",").map { mapping =>
      val parts = mapping.split("->").map(_.trim)
      if (parts.length != 2) {
        throw new IllegalArgumentException(s"Invalid mapping format: $mapping. Expected 'source->target'")
      }
      parts(0) -> parts(1)
    }.toMap

    val preserveUnmapped = config.config.get("preserveUnmapped")
      .forall(_.toLowerCase == "true")

    MapConfig(mappings, preserveUnmapped)
  }

  // ============================================
  // FLATMAP
  // ============================================

  private def parseFlatMapConfig(config: TransformConfig): Try[FlatMapConfig] = Try {
    val splitField = config.config.getOrElse("splitField",
      throw new IllegalArgumentException("FlatMap transform requires 'splitField' parameter"))

    val targetField = config.config.get("targetField")

    val preserveParent = config.config.get("preserveParent")
      .forall(_.toLowerCase == "true")

    FlatMapConfig(splitField, targetField, preserveParent)
  }

  // ============================================
  // AGGREGATE
  // ============================================

  private def parseAggregateConfig(config: TransformConfig): Try[AggregateConfig] = Try {
    // Parse groupBy fields
    val groupByStr = config.config.getOrElse("groupBy",
      throw new IllegalArgumentException("Aggregate transform requires 'groupBy' parameter"))
    val groupByFields = groupByStr.split(",").map(_.trim).toSeq

    // Parse aggregations
    // Expected format: "count:_count,sum:amount,avg:price"
    val aggregationsStr = config.config.getOrElse("aggregations",
      throw new IllegalArgumentException("Aggregate transform requires 'aggregations' parameter"))

    val aggregations = aggregationsStr.split(",").map { agg =>
      val parts = agg.split(":").map(_.trim)
      if (parts.length < 1) {
        throw new IllegalArgumentException(s"Invalid aggregation format: $agg")
      }

      val (aggType, field) = parts(0).toLowerCase match {
        case "count" =>
          (parts.lift(1).getOrElse("_count"), AggregationType.Count)
        case "sum" if parts.length >= 2 =>
          (parts(1), AggregationType.Sum(parts(1)))
        case "avg" | "average" if parts.length >= 2 =>
          (parts(1), AggregationType.Average(parts(1)))
        case "min" if parts.length >= 2 =>
          (parts(1), AggregationType.Min(parts(1)))
        case "max" if parts.length >= 2 =>
          (parts(1), AggregationType.Max(parts(1)))
        case "collect" if parts.length >= 2 =>
          (parts(1), AggregationType.Collect(parts(1)))
        case "first" if parts.length >= 2 =>
          (parts(1), AggregationType.First(parts(1)))
        case "last" if parts.length >= 2 =>
          (parts(1), AggregationType.Last(parts(1)))
        case other =>
          throw new IllegalArgumentException(s"Unknown aggregation type: $other")
      }

      field -> aggType
    }.toMap

    // Parse window size
    val windowSizeMs = config.config.getOrElse("windowSize", "60000").toLong
    val windowSize = windowSizeMs.milliseconds

    AggregateConfig(groupByFields, aggregations, windowSize)
  }

  // ============================================
  // ENRICH
  // ============================================

  private def parseEnrichConfig(config: TransformConfig): Try[EnrichConfig] = Try {
    val lookupField = config.config.getOrElse("lookupField",
      throw new IllegalArgumentException("Enrich transform requires 'lookupField' parameter"))

    // Parse target fields
    val targetFieldsStr = config.config.getOrElse("targetFields",
      throw new IllegalArgumentException("Enrich transform requires 'targetFields' parameter"))
    val targetFields = targetFieldsStr.split(",").map(_.trim).toSeq

    // Parse lookup source
    val sourceType = config.config.getOrElse("lookupSource", "inmemory").toLowerCase
    val lookupSource = sourceType match {
      case "redis" =>
        val host = config.config.getOrElse("redis.host", "localhost")
        val port = config.config.getOrElse("redis.port", "6379").toInt
        val keyPattern = config.config.getOrElse("redis.keyPattern", "${" + lookupField + "}")
        LookupSource.Redis(host, port, keyPattern)

      case "cassandra" =>
        val keyspace = config.config.getOrElse("cassandra.keyspace",
          throw new IllegalArgumentException("Cassandra lookup requires 'cassandra.keyspace'"))
        val table = config.config.getOrElse("cassandra.table",
          throw new IllegalArgumentException("Cassandra lookup requires 'cassandra.table'"))
        val keyColumn = config.config.getOrElse("cassandra.keyColumn", lookupField)
        LookupSource.Cassandra(keyspace, table, keyColumn)

      case "inmemory" =>
        // For testing: parse inline data if provided
        LookupSource.InMemory(Map.empty)

      case other =>
        throw new IllegalArgumentException(s"Unknown lookup source type: $other")
    }

    val cacheEnabled = config.config.get("cacheEnabled")
      .forall(_.toLowerCase == "true")

    val cacheTtl = config.config.get("cacheTtl").map(_.toLong.milliseconds)

    EnrichConfig(lookupField, lookupSource, targetFields, cacheEnabled, cacheTtl)
  }
}
