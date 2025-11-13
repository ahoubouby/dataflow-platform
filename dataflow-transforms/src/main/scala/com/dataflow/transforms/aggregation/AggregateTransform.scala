package com.dataflow.transforms.aggregation

import scala.concurrent.duration._

import cats.implicits._
import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.{AggregateConfig, AggregationType, StatefulTransform, TransformType}
import com.dataflow.transforms.errors._
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.LoggerFactory

/**
 * Sophisticated aggregation transform with windowing and state management.
 *
 * Features:
 * - Type-safe aggregation operations with Cats
 * - Functional error handling with custom error types
 * - Tumbling, sliding, and session windows
 * - Proper state management per group
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
    config.groupByFields
      .map(field => record.data.getOrElse(field, ""))
      .mkString("|")
  }

  /**
   * Extract group key values from records with functional error handling.
   */
  private def extractGroupKeyValues(records: Seq[DataRecord]): Either[AggregationError, Map[String, String]] = {
    import cats.syntax.either._
    Either.catchNonFatal {
      config.groupByFields.map {
        field =>
          field -> records.head.data.getOrElse(field, "")
      }.toMap
    }.leftMap(ExtractionError)
  }

  /**
   * Compute all aggregations on the records with functional error handling.
   */
  private def computeAggregations(records: Seq[DataRecord]): Either[AggregationError, Map[String, String]] = {
    import cats.syntax.either._
    Either.catchNonFatal {
      config.aggregations.map {
        case (outputField, aggType) =>
          val value = applyAggregation(aggType, records)
          outputField -> value
      }
    }.leftMap(ComputationError)
  }

  /**
   * Perform the actual aggregation using Either for error handling.
   */
  private def performAggregation(
    records: Seq[DataRecord],
    groupKey: String,
  ): Either[AggregationError, DataRecord] = {
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
   * Aggregate a window of records with functional error handling.
   */
  private def aggregateWindow(records: Seq[DataRecord]): List[DataRecord] = {
    if (records.isEmpty) {
      logger.debug("[Aggregate-Transform]: Empty window, skipping aggregation")
      List.empty
    } else {
      val groupKey = extractGroupKey(records.head)
      logger.debug(s"[Aggregate-Transform]: Aggregating window of ${records.size} records for group: $groupKey")
      performAggregation(records, groupKey).fold(
        error => {
          logAggregationError(groupKey, error)
          List.empty
        },
        aggregatedRecord => {
          logger.debug(s"[Aggregate-Transform]: Aggregated ${records.size} records into single result for group: $groupKey")
          List(aggregatedRecord)
        },
      )
    }
  }

  /**
   * Log aggregation errors with context.
   */
  private def logAggregationError(groupKey: String, error: AggregationError): Unit = {
    val message = AggregationError.getMessage(error)
    logger.error(s"[Aggregate-Transform]: $message : values for group: $groupKey")
  }

  /**
   * Apply a specific aggregation type to a sequence of records.
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
 * Allows for extensible, type-safe aggregations.
 */
trait Aggregator {
  def aggregate(records: Seq[DataRecord]): String
}

/**
 * Aggregator instances for common operations using functional programming.
 */
object Aggregator {

  /**
   * Count aggregator - counts number of records.
   */
  val count: Aggregator = (records: Seq[DataRecord]) => records.size.toString

  /**
   * Safe parser for numeric values using Either.
   */
  private def parseDoubleSafe(value: String): Option[Double] = {
    import cats.syntax.either._
    Either.catchNonFatal(value.trim.toDouble).toOption
  }

  /**
   * Extract all numeric values for a given field.
   */
  private def numericValues(records: Seq[DataRecord], field: String): Seq[Double] =
    records.flatMap(_.data.get(field).flatMap(parseDoubleSafe))

  /**
   * Sum aggregator - sums numeric field values.
   */
  def sum(field: String): Aggregator = new Aggregator {

    override def aggregate(records: Seq[DataRecord]): String =
      numericValues(records, field).sum.toString
  }

  /**
   * Average aggregator - computes mean of numeric field values.
   */
  def average(field: String): Aggregator = new Aggregator {

    override def aggregate(records: Seq[DataRecord]): String = {
      val values = numericValues(records, field)
      values.nonEmpty
        .guard[Option]
        .map(_ => values.sum / values.size)
        .fold("0.0")(_.toString)
    }
  }

  /**
   * Min aggregator - finds smallest numeric field value.
   */
  def min(field: String): Aggregator = (records: Seq[DataRecord]) =>
    numericValues(records, field)
      .minOption
      .fold("")(_.toString)

  /**
   * Max aggregator - finds maximum numeric field value.
   */
  def max(field: String): Aggregator = new Aggregator {

    override def aggregate(records: Seq[DataRecord]): String =
      numericValues(records, field)
        .maxOption
        .fold("")(_.toString)
  }

  /**
   * Collect aggregator - collects all field values into comma-separated string.
   */
  def collect(field: String): Aggregator = new Aggregator {

    override def aggregate(records: Seq[DataRecord]): String =
      records
        .flatMap(_.data.get(field))
        .mkString(",")
  }

  /**
   * First aggregator - gets first value seen.
   */
  def first(field: String): Aggregator = (records: Seq[DataRecord]) =>
    records
      .headOption
      .flatMap(_.data.get(field))
      .getOrElse("")

  /**
   * Last aggregator - gets last value seen.
   */
  def last(field: String): Aggregator = (records: Seq[DataRecord]) =>
    records
      .lastOption
      .flatMap(_.data.get(field))
      .getOrElse("")
}

/**
 * Window types for aggregation with ADT pattern.
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

/**
 * Statistics for aggregation results.
 */
case class AggregationStats(
  recordCount: Int,
  groupCount: Int,
  errorCount: Int,
  lastProcessedTimestamp: Option[Long] = None)

object AggregationStats {
  val empty: AggregationStats = AggregationStats(0, 0, 0, None)

  /**
   * Combine two stats using Monoid-like pattern.
   */
  def combine(a: AggregationStats, b: AggregationStats): AggregationStats =
    AggregationStats(
      recordCount = a.recordCount + b.recordCount,
      groupCount = a.groupCount + b.groupCount,
      errorCount = a.errorCount + b.errorCount,
      lastProcessedTimestamp = (a.lastProcessedTimestamp, b.lastProcessedTimestamp) match {
        case (Some(t1), Some(t2)) => Some(Math.max(t1, t2))
        case (Some(t), None)      => Some(t)
        case (None, Some(t))      => Some(t)
        case (None, None)         => None
      },
    )
}

/**
 * Type class for custom aggregation strategies.
 * Allows users to define their own aggregation logic.
 */
trait AggregationStrategy[A] {
  def zero: A
  def combine(a: A, b: A): A
  def finalize(a: A): String
}

object AggregationStrategy {

  /**
   * Numeric aggregation strategy.
   */
  implicit val numericStrategy: AggregationStrategy[Double] = new AggregationStrategy[Double] {
    override def zero:                          Double = 0.0
    override def combine(a: Double, b: Double): Double = a + b
    override def finalize(a: Double):           String = a.toString
  }

  /**
   * String concatenation strategy.
   */
  implicit val stringStrategy: AggregationStrategy[String] = new AggregationStrategy[String] {
    override def zero: String = ""

    override def combine(a: String, b: String): String =
      if (a.isEmpty) b else if (b.isEmpty) a else s"$a,$b"
    override def finalize(a: String):           String = a
  }

  /**
   * List collection strategy.
   */
  implicit def listStrategy[A]: AggregationStrategy[List[A]] = new AggregationStrategy[List[A]] {
    override def zero:                            List[A] = List.empty
    override def combine(a: List[A], b: List[A]): List[A] = a ++ b
    override def finalize(a: List[A]):            String  = a.mkString(",")
  }
}

/**
 * Advanced aggregation operations using type classes.
 */
object AdvancedAggregator {

  /**
   * Generic fold aggregator using AggregationStrategy.
   */
  def foldAggregator[A](
    field: String,
    extract: String => Option[A],
  )(implicit strategy: AggregationStrategy[A],
  ): Aggregator = new Aggregator {

    override def aggregate(records: Seq[DataRecord]): String = {
      val result = records
        .flatMap(_.data.get(field).flatMap(extract))
        .foldLeft(strategy.zero)(strategy.combine)
      strategy.finalize(result)
    }
  }

  /**
   * Percentile aggregator.
   */
  def percentile(field: String, percentile: Double): Aggregator = new Aggregator {
    import cats.syntax.either._

    override def aggregate(records: Seq[DataRecord]): String = {
      val values = records
        .flatMap(_.data.get(field).flatMap(v => Either.catchNonFatal(v.toDouble).toOption))
        .sorted

      if (values.isEmpty) {
        ""
      } else {
        val index = ((percentile / 100.0) * values.size).toInt
        values(Math.min(index, values.size - 1)).toString
      }
    }
  }

  /**
   * Standard deviation aggregator.
   */
  def stdDev(field: String): Aggregator = new Aggregator {

    override def aggregate(records: Seq[DataRecord]): String = {
      import cats.syntax.either._
      val values = records
        .flatMap(_.data.get(field).flatMap(v => Either.catchNonFatal(v.toDouble).toOption))

      if (values.isEmpty) {
        "0.0"
      } else {
        val mean     = values.sum / values.size
        val variance = values.map(v => Math.pow(v - mean, 2)).sum / values.size
        Math.sqrt(variance).toString
      }
    }
  }

  /**
   * Distinct count aggregator.
   */
  def distinctCount(field: String): Aggregator = new Aggregator {

    override def aggregate(records: Seq[DataRecord]): String =
      records
        .flatMap(_.data.get(field))
        .distinct
        .size
        .toString
  }
}
