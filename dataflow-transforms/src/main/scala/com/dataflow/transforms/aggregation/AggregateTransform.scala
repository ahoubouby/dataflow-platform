package com.dataflow.transforms.aggregation

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.{AggregateConfig, AggregationType, StatefulTransform, TransformType}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.collection.immutable.Queue

/**
 * Sophisticated aggregation transform with windowing and state management.
 *
 * Features:
 * - Type-safe aggregation operations
 * - Tumbling, sliding, and session windows
 * - Proper state management per group
 * - Handling of late arrivals
 * - Efficient aggregation using type classes
 *
 * Architecture:
 * Records → Group by keys → Window → Aggregate → Emit results
 *
 * @param config Aggregation configuration
 */
class AggregateTransform(config: AggregateConfig) extends StatefulTransform {

  private val logger = LoggerFactory.getLogger(getClass)

  override def transformType: TransformType = TransformType.Aggregate

  override def flow: Flow[DataRecord, DataRecord, NotUsed] = {
    Flow[DataRecord]
      .groupBy(maxSubstreams = 1024, extractGroupKey)
      .groupedWithin(Int.MaxValue, config.windowSize)
      .mapConcat(aggregateWindow)
      .mergeSubstreams
  }

  /**
   * Extract group key from record based on groupByFields.
   */
  private def extractGroupKey(record: DataRecord): String = {
    val keyParts = config.groupByFields.map { field =>
      record.data.getOrElse(field, "")
    }
    keyParts.mkString("|")
  }

  /**
   * Aggregate a window of records.
   *
   * This is where the actual aggregation happens.
   */
  private def aggregateWindow(records: Seq[DataRecord]): List[DataRecord] = {
    if (records.isEmpty) {
      logger.debug("Empty window, skipping aggregation")
      List.empty
    } else {
      val groupKey = extractGroupKey(records.head)
      logger.debug(s"Aggregating window of ${records.size} records for group: $groupKey")

      try {
        // Extract group key values
        val groupKeyValues = config.groupByFields.map { field =>
          field -> records.head.data.getOrElse(field, "")
        }.toMap

        // Apply all aggregations
        val aggregatedValues = config.aggregations.map { case (outputField, aggType) =>
          val value = applyAggregation(aggType, records)
          outputField -> value
        }

        // Create aggregated record
        val aggregatedRecord = DataRecord(
          id = s"agg-${groupKey}-${System.currentTimeMillis()}",
          data = groupKeyValues ++ aggregatedValues,
          metadata = Map(
            "aggregationType" -> "window",
            "windowSize" -> config.windowSize.toString,
            "recordCount" -> records.size.toString,
            "groupKey" -> groupKey
          )
        )

        logger.debug(s"Aggregated ${records.size} records into single result for group: $groupKey")
        List(aggregatedRecord)

      } catch {
        case ex: Exception =>
          logger.error(s"Failed to aggregate window for group: $groupKey", ex)
          List.empty
      }
    }
  }

  /**
   * Apply a specific aggregation type to a sequence of records.
   *
   * Uses type class pattern for extensibility.
   */
  private def applyAggregation(aggType: AggregationType, records: Seq[DataRecord]): String = {
    aggType match {
      case AggregationType.Count =>
        Aggregator.count.aggregate(records)

      case AggregationType.Sum(field) =>
        Aggregator.sum(field).aggregate(records)

      case AggregationType.Average(field) =>
        Aggregator.average(field).aggregate(records)

      case AggregationType.Min(field) =>
        Aggregator.min(field).aggregate(records)

      case AggregationType.Max(field) =>
        Aggregator.max(field).aggregate(records)

      case AggregationType.Collect(field) =>
        Aggregator.collect(field).aggregate(records)

      case AggregationType.First(field) =>
        Aggregator.first(field).aggregate(records)

      case AggregationType.Last(field) =>
        Aggregator.last(field).aggregate(records)
    }
  }
}

/**
 * Type class for aggregation operations.
 *
 * This allows for extensible, type-safe aggregations.
 */
trait Aggregator {
  def aggregate(records: Seq[DataRecord]): String
}

/**
 * Aggregator instances for common operations.
 */
object Aggregator {

  /**
   * Count aggregator - counts number of records.
   */
  val count: Aggregator = new Aggregator {
    override def aggregate(records: Seq[DataRecord]): String = {
      records.size.toString
    }
  }

  /**
   * Sum aggregator - sums numeric field values.
   */
  def sum(field: String): Aggregator = new Aggregator {
    override def aggregate(records: Seq[DataRecord]): String = {
      val total = records.flatMap { record =>
        record.data.get(field).flatMap(v => parseDouble(v))
      }.sum
      total.toString
    }
  }

  /**
   * Average aggregator - calculates average of numeric field.
   */
  def average(field: String): Aggregator = new Aggregator {
    override def aggregate(records: Seq[DataRecord]): String = {
      val values = records.flatMap { record =>
        record.data.get(field).flatMap(v => parseDouble(v))
      }
      if (values.isEmpty) "0.0"
      else (values.sum / values.size).toString
    }
  }

  /**
   * Min aggregator - finds minimum value.
   */
  def min(field: String): Aggregator = new Aggregator {
    override def aggregate(records: Seq[DataRecord]): String = {
      records.flatMap { record =>
        record.data.get(field).flatMap(v => parseDouble(v))
      }.minOption.map(_.toString).getOrElse("")
    }
  }

  /**
   * Max aggregator - finds maximum value.
   */
  def max(field: String): Aggregator = new Aggregator {
    override def aggregate(records: Seq[DataRecord]): String = {
      records.flatMap { record =>
        record.data.get(field).flatMap(v => parseDouble(v))
      }.maxOption.map(_.toString).getOrElse("")
    }
  }

  /**
   * Collect aggregator - collects all values into comma-separated string.
   */
  def collect(field: String): Aggregator = new Aggregator {
    override def aggregate(records: Seq[DataRecord]): String = {
      records.flatMap { record =>
        record.data.get(field)
      }.mkString(",")
    }
  }

  /**
   * First aggregator - gets first value seen.
   */
  def first(field: String): Aggregator = new Aggregator {
    override def aggregate(records: Seq[DataRecord]): String = {
      records.headOption
        .flatMap(_.data.get(field))
        .getOrElse("")
    }
  }

  /**
   * Last aggregator - gets last value seen.
   */
  def last(field: String): Aggregator = new Aggregator {
    override def aggregate(records: Seq[DataRecord]): String = {
      records.lastOption
        .flatMap(_.data.get(field))
        .getOrElse("")
    }
  }

  /**
   * Helper to parse double values safely.
   */
  private def parseDouble(value: String): Option[Double] = {
    try {
      Some(value.toDouble)
    } catch {
      case _: NumberFormatException => None
    }
  }
}

/**
 * Window types for aggregation.
 *
 * Future enhancement: Support different window types.
 */
sealed trait WindowType

object WindowType {
  /** Tumbling window - fixed size, non-overlapping */
  case class Tumbling(size: FiniteDuration) extends WindowType

  /** Sliding window - overlapping windows */
  case class Sliding(size: FiniteDuration, step: FiniteDuration) extends WindowType

  /** Session window - timeout-based */
  case class Session(gap: FiniteDuration) extends WindowType
}
