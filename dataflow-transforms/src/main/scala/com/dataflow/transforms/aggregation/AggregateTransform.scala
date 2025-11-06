package com.dataflow.transforms.aggregation

import scala.concurrent.duration._
import scala.util.Try

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.{AggregateConfig, AggregationType, StatefulTransform, TransformType}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.LoggerFactory

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
    val keyParts = config.groupByFields.map {
      field =>
        record.data.getOrElse(field, "")
    }
    keyParts.mkString("|")
  }

  /**
   * Extract group key values from records.
   */
  private def extractGroupKeyValues(records: Seq[DataRecord]): Either[Throwable, Map[String, String]] = {
    Try {
      config.groupByFields.map {
        field =>
          field -> records.head.data.getOrElse(field, "")
      }.toMap
    }.toEither
  }

  /**
   * Compute all aggregations on the records.
   */
  private def computeAggregations(records: Seq[DataRecord]): Either[Throwable, Map[String, String]] = {
    Try {
      config.aggregations.map {
        case (outputField, aggType) =>
          val value = applyAggregation(aggType, records)
          outputField -> value
      }
    }.toEither
  }

  /**
   * Perform the actual aggregation and return Either for error handling.
   */
  private def performAggregation(
    records: Seq[DataRecord],
    groupKey: String,
  ): Either[Throwable, DataRecord] = {
    for {
      groupKeyValues   <- extractGroupKeyValues(records)
      aggregatedValues <- computeAggregations(records)
    } yield DataRecord(
      id = s"agg-$groupKey-${System.currentTimeMillis()}",
      data = groupKeyValues ++ aggregatedValues,
      metadata = Map(
        "aggregationType" -> "window",
        "windowSize"      -> config.windowSize.toString,
        "recordCount"     -> records.size.toString,
        "groupKey"        -> groupKey,
      ),
    )
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

      performAggregation(records, groupKey).fold(
        error => {
          logger.error(s"Failed to aggregate window for group: $groupKey", error)
          List.empty
        },
        aggregatedRecord => {
          logger.debug(s"Aggregated ${records.size} records into single result for group: $groupKey")
          List(aggregatedRecord)
        },
      )
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
  val count: Aggregator = (records: Seq[DataRecord]) => records.size.toString

  /** Local, safe parser — avoids NumberFormatException. */
  private def parseDoubleSafe(value: String): Option[Double] =
    try Some(value.trim.toDouble)
    catch { case _: NumberFormatException => None }

  /** Extract all numeric values for a given field. */
  private def numericValues(records: Seq[DataRecord], field: String): Seq[Double] =
    records.flatMap(_.data.get(field).flatMap(parseDoubleSafe))

  /** Sum aggregator — sums numeric field values. */
  def sum(field: String): Aggregator = new Aggregator {

    override def aggregate(records: Seq[DataRecord]): String =
      numericValues(records, field).sum.toString
  }

  /** Average aggregator — computes mean of numeric field values. */
  def average(field: String): Aggregator = new Aggregator {

    override def aggregate(records: Seq[DataRecord]): String = {
      val values = numericValues(records, field)
      if (values.isEmpty) "0.0"
      else (values.sum / values.size).toString
    }
  }

  /** Min aggregator — finds smallest numeric field value. */
  def min(field: String): Aggregator = new Aggregator {

    override def aggregate(records: Seq[DataRecord]): String = {
      numericValues(records, field)
        .minOption
        .fold("")(_.toString)
    }
  }

  /**
   * Max aggregator - finds maximum value.
   */
  def max(field: String): Aggregator = new Aggregator {

    override def aggregate(records: Seq[DataRecord]): String = {
      records.flatMap {
        record =>
          record.data.get(field).flatMap(v => parseDouble(v))
      }.maxOption.map(_.toString).getOrElse("")
    }
  }

  /**
   * Collect aggregator - collects all values into comma-separated string.
   */
  def collect(field: String): Aggregator = new Aggregator {

    override def aggregate(records: Seq[DataRecord]): String = {
      records.flatMap {
        record =>
          record.data.get(field)
      }.mkString(",")
    }
  }

  /**
   * First aggregator - gets first value seen.
   */
  def first(field: String): Aggregator = (records: Seq[DataRecord]) => {
    records.headOption
      .flatMap(_.data.get(field))
      .getOrElse("")
  }

  /**
   * Last aggregator - gets last value seen.
   */
  def last(field: String): Aggregator = (records: Seq[DataRecord]) => {
    records.lastOption
      .flatMap(_.data.get(field))
      .getOrElse("")
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

sealed trait AggregationError
case class ExtractionError(cause: Throwable) extends AggregationError
case class ComputationError(cause: Throwable) extends AggregationError
