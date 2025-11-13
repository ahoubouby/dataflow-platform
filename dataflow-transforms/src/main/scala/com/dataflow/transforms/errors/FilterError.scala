package com.dataflow.transforms.errors

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
