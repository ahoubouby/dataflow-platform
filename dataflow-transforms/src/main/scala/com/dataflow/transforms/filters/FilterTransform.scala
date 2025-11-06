package com.dataflow.transforms.filters

import cats.implicits._
import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.{ErrorHandlingStrategy, FilterConfig, StatelessTransform, TransformType}
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

  private val logger = LoggerFactory.getLogger(getClass)

  override def transformType: TransformType = TransformType.Filter

  override def flow: Flow[DataRecord, DataRecord, NotUsed] =
    Flow[DataRecord]
      .filter(record => evaluateRecordWithErrorHandling(record))

  /**
   * Evaluate record with configured error handling strategy.
   */
  private def evaluateRecordWithErrorHandling(record: DataRecord): Boolean = {
    evaluateFilter(record).fold(
      error => handleFilterError(record, error),
      matches => {
        if (!matches) {
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
  def evaluateFilter(record: DataRecord): Either[FilterError, Boolean] = {
    for {
      expression <- parseExpression(config.expression.trim)
      result     <- evaluateExpression(record, expression)
    } yield result
  }

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
 * Comparison operators as ADT.
 */
sealed trait ComparisonOperator

object ComparisonOperator {
  case object Equal extends ComparisonOperator
  case object NotEqual extends ComparisonOperator
  case object GreaterThan extends ComparisonOperator
  case object GreaterThanOrEqual extends ComparisonOperator
  case object LessThan extends ComparisonOperator
  case object LessThanOrEqual extends ComparisonOperator

  /**
   * All supported operators.
   */
  val all: Set[ComparisonOperator] = Set(
    Equal,
    NotEqual,
    GreaterThan,
    GreaterThanOrEqual,
    LessThan,
    LessThanOrEqual,
  )

  /**
   * Get string representation of operator.
   */
  def toString(op: ComparisonOperator): String = op match {
    case Equal              => "=="
    case NotEqual           => "!="
    case GreaterThan        => ">"
    case GreaterThanOrEqual => ">="
    case LessThan           => "<"
    case LessThanOrEqual    => "<="
  }
}

/**
 * Filter evaluation errors as ADT.
 */
sealed trait FilterError {
  def message: String
}

case class InvalidExpressionFormat(expression: String, reason: String) extends FilterError {
  override def message: String = s"Invalid expression format: '$expression' - $reason"
}

case class UnsupportedOperator(operator: String) extends FilterError {
  override def message: String = s"Unsupported operator: '$operator'"
}

case class FieldNotFound(field: String, recordId: String) extends FilterError {
  override def message: String = s"Field '$field' not found in record $recordId"
}

case class NumericParseError(value: String) extends FilterError {
  override def message: String = s"Cannot parse '$value' as numeric value"
}

object FilterError {

  /**
   * Create error with context.
   */
  def invalidFormat(expr: String, reason: String): FilterError =
    InvalidExpressionFormat(expr, reason)

  def unsupportedOp(op: String): FilterError =
    UnsupportedOperator(op)

  def fieldNotFound(field: String, recordId: String): FilterError =
    FieldNotFound(field, recordId)

  def numericParse(value: String): FilterError =
    NumericParseError(value)

  /**
   * Convert to descriptive message.
   */
  def describe(error: FilterError): String = error match {
    case InvalidExpressionFormat(expr, reason) =>
      s"Expression '$expr' is invalid: $reason"
    case UnsupportedOperator(op)               =>
      s"Operator '$op' is not supported. Valid operators: ==, !=, >, >=, <, <="
    case FieldNotFound(field, recordId)        =>
      s"Field '$field' does not exist in record $recordId"
    case NumericParseError(value)              =>
      s"Value '$value' cannot be converted to a number"
  }
}

/**
 * Exception for filter evaluation failures.
 */
class FilterEvaluationException(error: FilterError)
  extends RuntimeException(FilterError.describe(error))

/**
 * Advanced filter operations for complex scenarios.
 */
object AdvancedFilters {

  /**
   * Combine multiple filters with AND logic.
   */
  def and(filters: FilterTransform*): DataRecord => Either[FilterError, Boolean] = {
    record =>
      filters.toList
        .traverse(filter => filter.evaluateFilter(record))
        .map(_.forall(identity))
  }

  /**
   * Combine multiple filters with OR logic.
   */
  def or(filters: FilterTransform*): DataRecord => Either[FilterError, Boolean] = {
    record =>
      filters.toList
        .traverse(filter => filter.evaluateFilter(record))
        .map(_.exists(identity))
  }

  /**
   * Negate a filter.
   */
  def not(filter: FilterTransform): DataRecord => Either[FilterError, Boolean] = {
    record =>
      filter.evaluateFilter(record).map(!_)
  }
}

/**
 * Filter statistics for monitoring.
 */
case class FilterStats(
  totalRecords: Long,
  matchedRecords: Long,
  filteredRecords: Long,
  errorRecords: Long) {

  def matchRate: Double =
    if (totalRecords == 0) 0.0
    else matchedRecords.toDouble / totalRecords.toDouble

  def errorRate: Double =
    if (totalRecords == 0) 0.0
    else errorRecords.toDouble / totalRecords.toDouble
}

object FilterStats {
  val empty: FilterStats = FilterStats(0, 0, 0, 0)

  /**
   * Combine stats using Monoid-like pattern.
   */
  def combine(a: FilterStats, b: FilterStats): FilterStats =
    FilterStats(
      totalRecords = a.totalRecords + b.totalRecords,
      matchedRecords = a.matchedRecords + b.matchedRecords,
      filteredRecords = a.filteredRecords + b.filteredRecords,
      errorRecords = a.errorRecords + b.errorRecords,
    )
}

/**
 * Filter expression builder for programmatic construction.
 */
object FilterExpressionBuilder {

  /**
   * Build equality filter.
   */
  def equals(field: String, value: String): FilterExpression =
    FilterExpression(field, ComparisonOperator.Equal, value)

  /**
   * Build inequality filter.
   */
  def notEquals(field: String, value: String): FilterExpression =
    FilterExpression(field, ComparisonOperator.NotEqual, value)

  /**
   * Build greater than filter.
   */
  def greaterThan(field: String, value: String): FilterExpression =
    FilterExpression(field, ComparisonOperator.GreaterThan, value)

  /**
   * Build greater than or equal filter.
   */
  def greaterThanOrEqual(field: String, value: String): FilterExpression =
    FilterExpression(field, ComparisonOperator.GreaterThanOrEqual, value)

  /**
   * Build less than filter.
   */
  def lessThan(field: String, value: String): FilterExpression =
    FilterExpression(field, ComparisonOperator.LessThan, value)

  /**
   * Build less than or equal filter.
   */
  def lessThanOrEqual(field: String, value: String): FilterExpression =
    FilterExpression(field, ComparisonOperator.LessThanOrEqual, value)

  /**
   * Convert expression to string format.
   */
  def toExpressionString(expr: FilterExpression): String =
    s"${expr.field} ${ComparisonOperator.toString(expr.operator)} ${expr.value}"
}

/**
 * Validation for filter expressions.
 */
object FilterValidator {

  /**
   * Validate filter expression syntax.
   */
  def validate(expression: String): Either[FilterError, Unit] = {
    for {
      _    <- Either.cond(
                expression.trim.nonEmpty,
                (),
                InvalidExpressionFormat(expression, "Expression cannot be empty"),
              )
      parts = expression.trim.split("\\s+")
      _    <- Either.cond(
                parts.length == 3,
                (),
                InvalidExpressionFormat(expression, "Expression must have 3 parts: field operator value"),
              )
      _    <- Either.cond(
                parts(0).nonEmpty,
                (),
                InvalidExpressionFormat(expression, "Field name cannot be empty"),
              )
      _    <- Either.cond(
                parts(2).nonEmpty,
                (),
                InvalidExpressionFormat(expression, "Value cannot be empty"),
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
