package com.dataflow.transforms.filters

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
