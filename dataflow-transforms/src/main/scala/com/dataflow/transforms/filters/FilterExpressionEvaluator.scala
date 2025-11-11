package com.dataflow.transforms.filters

import cats.implicits._
import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.errors._

/**
 * Evaluator for filter expression AST.
 *
 * Recursively evaluates filter expressions against data records.
 * Supports:
 * - Simple comparisons (==, !=, >, >=, <, <=)
 * - Logical AND (all must be true)
 * - Logical OR (at least one must be true)
 * - Logical NOT (negation)
 * - Nested expressions with proper precedence
 *
 * Returns Either[FilterError, Boolean] for functional error handling.
 */
object FilterExpressionEvaluator {

  /**
   * Evaluate a filter expression AST against a data record.
   */
  def evaluate(record: DataRecord, ast: FilterExpressionAST): Either[FilterError, Boolean] = ast match {
    case SimpleExpression(field, operator, value) =>
      evaluateSimple(record, field, operator, value)

    case AndExpression(expressions) =>
      evaluateAnd(record, expressions)

    case OrExpression(expressions) =>
      evaluateOr(record, expressions)

    case NotExpression(expression) =>
      evaluateNot(record, expression)
  }

  /**
   * Evaluate a simple comparison expression.
   */
  private def evaluateSimple(
    record: DataRecord,
    field: String,
    operator: ComparisonOperator,
    expectedValue: String
  ): Either[FilterError, Boolean] = {
    record.data.get(field) match {
      case Some(actualValue) =>
        applyOperator(operator, actualValue, expectedValue)

      case None =>
        Left(FieldNotFound(field, record.id))
    }
  }

  /**
   * Evaluate AND expression - all expressions must be true.
   */
  private def evaluateAnd(
    record: DataRecord,
    expressions: List[FilterExpressionAST]
  ): Either[FilterError, Boolean] = {
    // Use traverse to short-circuit on first error
    // Use .map(_.forall(identity)) to check all are true
    expressions.traverse(evaluate(record, _)).map(_.forall(identity))
  }

  /**
   * Evaluate OR expression - at least one expression must be true.
   */
  private def evaluateOr(
    record: DataRecord,
    expressions: List[FilterExpressionAST]
  ): Either[FilterError, Boolean] = {
    // Use traverse to collect all results
    // Use .map(_.exists(identity)) to check at least one is true
    expressions.traverse(evaluate(record, _)).map(_.exists(identity))
  }

  /**
   * Evaluate NOT expression - negate the result.
   */
  private def evaluateNot(
    record: DataRecord,
    expression: FilterExpressionAST
  ): Either[FilterError, Boolean] = {
    evaluate(record, expression).map(!_)
  }

  /**
   * Apply comparison operator to values.
   */
  private def applyOperator(
    operator: ComparisonOperator,
    actual: String,
    expected: String
  ): Either[FilterError, Boolean] = {
    operator match {
      case ComparisonOperator.Equal =>
        Right(actual == expected)

      case ComparisonOperator.NotEqual =>
        Right(actual != expected)

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
    op: (Double, Double) => Boolean
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
    Either.catchNonFatal(value.toDouble)
      .leftMap(_ => NumericParseError(value))
  }
}
