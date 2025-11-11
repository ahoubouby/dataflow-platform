package com.dataflow.transforms.filters

import java.util.concurrent.atomic.AtomicReference

import cats.implicits._
import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.{ErrorHandlingStrategy, FilterConfig, StatelessTransform, TransformType}
import com.dataflow.transforms.errors._
import com.dataflow.transforms.filters.models.FilterStats
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.LoggerFactory

/**
 * Filter transformation with AST-based expression parsing.
 *
 * Supports simple and combined filter expressions:
 * Simple:
 *    - "status == active"
 *    - "age > 18"
 *    - "level >= 5"
 *
 * Combined with AND:
 *    - "status == active AND age > 18"
 *    - "type == premium AND level >= 5 AND verified == true"
 *
 * Combined with OR:
 *    - "status == active OR status == pending"
 *    - "type == premium OR level >= 10"
 *
 * Combined with NOT:
 *    - "NOT status == inactive"
 *    - "status == active AND NOT verified == false"
 *
 * Complex expressions with parentheses:
 *    - "(status == active AND age > 18) OR level >= 10"
 *    - "status == active AND (type == premium OR level >= 5)"
 *
 * Features:
 * - Type-safe error handling with Cats
 * - AST-based expression parsing
 * - Configurable error strategies
 * - Numeric and string comparisons
 * - Logical operators (AND, OR, NOT)
 * - Parentheses for grouping
 *
 * Records that don't match the filter are dropped from the stream.
 * Malformed records can be skipped, failed, or sent to DLQ based on strategy.
 *
 * @param config Filter configuration
 */
class FilterTransformV2(config: FilterConfig) extends StatelessTransform {

  private val logger   = LoggerFactory.getLogger(getClass)
  private val statsRef = new AtomicReference[FilterStats](FilterStats.empty)

  // Parse and validate expression at construction time
  private val validatedAST: FilterExpressionAST =
    FilterValidatorV2.validate(config.expression)
      .flatMap(_ => FilterExpressionParser.parse(config.expression.trim))
      .fold(
        error => throw new FilterEvaluationException(error),
        identity
      )

  override def transformType: TransformType = TransformType.Filter

  override def flow: Flow[DataRecord, DataRecord, NotUsed] =
    Flow[DataRecord]
      .filter(record => evaluateRecordWithErrorHandling(record))

  /**
   * Update statistics atomically.
   */
  private def updateStats(f: FilterStats => FilterStats): Unit = {
    statsRef.updateAndGet(current => f(current))
    ()
  }

  /**
   * Evaluate record with configured error handling strategy.
   */
  private def evaluateRecordWithErrorHandling(record: DataRecord): Boolean = {
    updateStats(_.incrementTotal())

    evaluateFilter(record).fold(
      error => {
        updateStats(_.incrementErrors())
        handleFilterError(record, error)
      },
      matches => {
        if (matches) {
          updateStats(_.incrementMatched())
          logger.debug(s"Record ${record.id} matched filter: ${config.expression}")
        } else {
          updateStats(_.incrementFiltered())
          logger.debug(s"Record ${record.id} filtered out by expression: ${config.expression}")
        }
        matches
      }
    )
  }

  /**
   * Handle filter evaluation errors based on configured strategy.
   */
  private def handleFilterError(record: DataRecord, error: FilterError): Boolean = {
    errorHandler match {
      case ErrorHandlingStrategy.Skip =>
        logger.warn(s"Failed to evaluate filter for record ${record.id}, skipping: ${error.message}")
        false // Skip record on error

      case ErrorHandlingStrategy.Fail =>
        logger.error(s"Failed to evaluate filter for record ${record.id}, failing: ${error.message}")
        throw new FilterEvaluationException(error)

      case ErrorHandlingStrategy.DeadLetter =>
        logger.warn(s"Failed to evaluate filter for record ${record.id}, sending to DLQ: ${error.message}")
        // TODO: Implement dead letter queue integration
        false
    }
  }

  /**
   * Evaluate the filter expression against a record using Either for error handling.
   */
  def evaluateFilter(record: DataRecord): Either[FilterError, Boolean] =
    FilterExpressionEvaluator.evaluate(record, validatedAST)

  /**
   * Get current statistics.
   */
  def stats: FilterStats = statsRef.get()
}

/**
 * Validation for filter expressions.
 */
object FilterValidatorV2 {

  /**
   * Validate filter expression syntax.
   */
  def validate(expression: String): Either[FilterError, Unit] = {
    val trimmed = expression.trim

    if (trimmed.isEmpty)
      return Left(InvalidExpressionFormat(expression, "Expression cannot be empty"))

    // Basic validation - the parser will do detailed validation
    Right(())
  }
}

object FilterTransformV2 {

  /**
   * Create filter with validation.
   */
  def create(
    expression: String,
    errorStrategy: ErrorHandlingStrategy = ErrorHandlingStrategy.Skip
  ): Either[FilterError, FilterTransformV2] = {
    FilterValidatorV2.validate(expression)
      .flatMap(_ => FilterExpressionParser.parse(expression))
      .map(_ => new FilterTransformV2(FilterConfig(expression, errorStrategy)))
  }

  /**
   * Combine multiple filters with AND logic (programmatic).
   */
  def and(filters: FilterTransformV2*): FilterTransformV2 = {
    require(filters.nonEmpty, "At least one filter required")

    val combinedExpression = filters.map(f => s"(${f.config.expression})").mkString(" AND ")
    new FilterTransformV2(FilterConfig(combinedExpression, ErrorHandlingStrategy.Skip))
  }

  /**
   * Combine multiple filters with OR logic (programmatic).
   */
  def or(filters: FilterTransformV2*): FilterTransformV2 = {
    require(filters.nonEmpty, "At least one filter required")

    val combinedExpression = filters.map(f => s"(${f.config.expression})").mkString(" OR ")
    new FilterTransformV2(FilterConfig(combinedExpression, ErrorHandlingStrategy.Skip))
  }

  /**
   * Negate a filter (programmatic).
   */
  def not(filter: FilterTransformV2): FilterTransformV2 = {
    val negatedExpression = s"NOT (${filter.config.expression})"
    new FilterTransformV2(FilterConfig(negatedExpression, ErrorHandlingStrategy.Skip))
  }
}
