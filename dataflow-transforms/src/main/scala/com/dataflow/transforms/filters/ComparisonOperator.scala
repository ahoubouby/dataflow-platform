package com.dataflow.transforms.filters

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
