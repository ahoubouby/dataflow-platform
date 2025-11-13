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
 * Filter transformation with functional programming approach.
 *
 * Supports simple field comparisons:
 *    - "status == active"
 *    - "type != test"
 *    - "age > 18"
 *    - "level >= 5"
 *
 * Features:
 * - Type-safe error handling with Cats
 * - Custom error ADT
 * - Configurable error strategies
 * - Numeric and string comparisons
 * - Extensible operator support
 *
 * Records that don't match the filter are dropped from the stream.
 * Malformed records can be skipped, failed, or sent to DLQ based on strategy.
 *
 * @param config Filter configuration
 */
class FilterTransform(config: FilterConfig) extends StatelessTransform {

  private val logger   = LoggerFactory.getLogger(getClass)
  private val statsRef = new AtomicReference[FilterStats](FilterStats.empty)

  private val validatedExpression: FilterExpression =
    FilterValidator.validate(config.expression) // ← USED HERE!
      .flatMap(_ => parseExpression(config.expression.trim))
      .fold(
        error => throw new FilterEvaluationException(error),
        identity,
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
      },
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
    evaluateExpression(record, validatedExpression)

  /**
   * Parse filter expression into structured format.
   */
  private def parseExpression(expr: String): Either[FilterError, FilterExpression] = {
    import cats.syntax.either._
    val parts = expr.split("\\s+")

    if (parts.length != 3) {
      InvalidExpressionFormat(expr, "Expected format: field operator value").asLeft
    } else {
      for {
        operator <- parseOperator(parts(1))
      } yield FilterExpression(
        field = parts(0),
        operator = operator,
        value = parts(2),
      )
    }
  }

  /**
   * Parse operator string into typed operator.
   */
  private def parseOperator(op: String): Either[FilterError, ComparisonOperator] = {
    import cats.syntax.either._
    op match {
      case "==" => ComparisonOperator.Equal.asRight
      case "!=" => ComparisonOperator.NotEqual.asRight
      case ">"  => ComparisonOperator.GreaterThan.asRight
      case ">=" => ComparisonOperator.GreaterThanOrEqual.asRight
      case "<"  => ComparisonOperator.LessThan.asRight
      case "<=" => ComparisonOperator.LessThanOrEqual.asRight
      case _    => UnsupportedOperator(op).asLeft
    }
  }

  /**
   * Evaluate parsed expression against a record.
   */
  private def evaluateExpression(
    record: DataRecord,
    expr: FilterExpression,
  ): Either[FilterError, Boolean] = {
    import cats.syntax.either._
    record.data.get(expr.field) match {
      case Some(actualValue) =>
        applyOperator(expr.operator, actualValue, expr.value)

      case None =>
        FieldNotFound(expr.field, record.id).asLeft
    }
  }

  /**
   * Apply comparison operator to values.
   */
  private def applyOperator(
    operator: ComparisonOperator,
    actual: String,
    expected: String,
  ): Either[FilterError, Boolean] = {
    import cats.syntax.either._
    operator match {

      case ComparisonOperator.Equal =>
        (actual == expected).asRight

      case ComparisonOperator.NotEqual =>
        (actual != expected).asRight

      case ComparisonOperator.GreaterThan =>
        compareNumeric(actual, expected, _ > _)

      case ComparisonOperator.GreaterThanOrEqual =>
        compareNumeric(actual, expected, _ >= _)

      case ComparisonOperator.LessThan =>
        compareNumeric(actual, expected, _ < _)

      case ComparisonOperator.LessThanOrEqual =>
        compareNumeric(actual, expected, _ <= _)
    }
  }

  /**
   * Compare numeric values with functional error handling.
   */
  private def compareNumeric(
    v1: String,
    v2: String,
    op: (Double, Double) => Boolean,
  ): Either[FilterError, Boolean] = {
    for {
      n1 <- parseNumeric(v1)
      n2 <- parseNumeric(v2)
    } yield op(n1, n2)
  }

  /**
   * Parse string to numeric value.
   */
  private def parseNumeric(value: String): Either[FilterError, Double] = {
    import cats.syntax.either._
    Either.catchNonFatal(value.toDouble)
      .leftMap(_ => NumericParseError(value))
  }
}

/**
 * Structured representation of a filter expression.
 */
case class FilterExpression(
  field: String,
  operator: ComparisonOperator,
  value: String)

/**
 * Exception for filter evaluation failures.
 */
class FilterEvaluationException(error: FilterError)
  extends RuntimeException(FilterError.describe(error))

/**
 * Validation for filter expressions.
 */
object FilterValidator {

  /**
   * Validate filter expression syntax.
   */
  def validate(expression: String): Either[FilterError, Unit] = {
    val trimmed = expression.trim

    if (trimmed.isEmpty)
      return Left(InvalidExpressionFormat(expression, "Expression cannot be empty"))

    if (isCombinedExpression(trimmed))
      return Right(())

    validateSimple(trimmed)
  }

  private def isCombinedExpression(expr: String): Boolean = {
    // very simple detection; you can enrich it later
    val upper = expr.toUpperCase
    upper.contains("AND") || upper.contains("OR")
  }

  private def validateSimple(expr: String): Either[FilterError, Unit] = {
    val parts = expr.split("\\s+")
    for {
      _ <- Either.cond(
             parts.length == 3,
             (),
             InvalidExpressionFormat(expr, "Expression must have 3 parts: field operator value"),
           )
      _ <- Either.cond(
             parts(0).nonEmpty,
             (),
             InvalidExpressionFormat(expr, "Field name cannot be empty"),
           )
      _ <- Either.cond(
             parts(2).nonEmpty,
             (),
             InvalidExpressionFormat(expr, "Value cannot be empty"),
           )
    } yield ()
  }

  /**
   * Validate operator is supported.
   */
  def validateOperator(operator: String): Either[FilterError, Unit] = {
    val validOps = Set("==", "!=", ">", ">=", "<", "<=")
    Either.cond(
      validOps.contains(operator),
      (),
      UnsupportedOperator(operator),
    )
  }
}

object FilterTransform {

  /**
   * Create filter from FilterExpression using FilterExpressionBuilder.
   */
  def fromExpression(
    expr: FilterExpression,
    errorStrategy: ErrorHandlingStrategy = ErrorHandlingStrategy.Skip,
  ): FilterTransform = {
    val exprString = FilterExpressionBuilder.toExpressionString(expr)
    new FilterTransform(FilterConfig(exprString, errorStrategy))
  }

  /**
   * Create filter with validation.
   */
  def create(expression: String, errorStrategy: ErrorHandlingStrategy = ErrorHandlingStrategy.Skip): Either[
    FilterError,
    FilterTransform,
  ] = {
    FilterValidator.validate(expression).map {
      _ =>
        new FilterTransform(FilterConfig(expression, errorStrategy))
    }
  }

  /**
   * Combine multiple filters with AND logic.
   */
  def and(filters: FilterTransform*): FilterTransform = {
    require(filters.nonEmpty, "At least one filter required")

    new FilterTransform(FilterConfig("AND", ErrorHandlingStrategy.Skip)) {
      override def evaluateFilter(record: DataRecord): Either[FilterError, Boolean] = {
        filters.toList
          .traverse(_.evaluateFilter(record))
          .map(_.forall(identity))
      }
    }
  }

  /**
   * Combine multiple filters with OR logic.
   */
  def or(filters: FilterTransform*): FilterTransform = {
    require(filters.nonEmpty, "At least one filter required")

    new FilterTransform(FilterConfig("OR", ErrorHandlingStrategy.Skip)) {
      override def evaluateFilter(record: DataRecord): Either[FilterError, Boolean] = {
        filters.toList
          .traverse(_.evaluateFilter(record))
          .map(_.exists(identity))
      }
    }
  }

  /**
   * Negate a filter.
   */
  def not(filter: FilterTransform): FilterTransform = {
    new FilterTransform(FilterConfig("negated", ErrorHandlingStrategy.Skip)) {
      override def evaluateFilter(record: DataRecord): Either[FilterError, Boolean] =
        filter.evaluateFilter(record).map(!_)
    }
  }
}
