package com.dataflow.transforms.filters

/**
 * ADT representing filter expressions with support for combinations.
 *
 * This sealed trait hierarchy allows composing complex filter logic:
 * - SimpleExpression: Basic field comparison
 * - AndExpression: Logical AND of multiple expressions
 * - OrExpression: Logical OR of multiple expressions
 * - NotExpression: Logical negation
 *
 * Examples:
 * - status == active                                    →  SimpleExpression
 * - status == active AND age > 18                       →  AndExpression
 * - type == premium OR level >= 5                       →  OrExpression
 * - (status == active AND age > 18) OR level >= 10      →  OrExpression with AndExpression
 * - NOT status == inactive                              →  NotExpression
 */
sealed trait FilterExpressionAST

/**
 * Simple comparison expression: field operator value
 * Example: "status == active", "age > 18"
 */
case class SimpleExpression(
  field: String,
  operator: ComparisonOperator,
  value: String
) extends FilterExpressionAST

/**
 * AND combination of multiple expressions.
 * All expressions must evaluate to true.
 * Example: "status == active AND age > 18 AND level >= 5"
 */
case class AndExpression(
  expressions: List[FilterExpressionAST]
) extends FilterExpressionAST {
  require(expressions.nonEmpty, "AndExpression must have at least one expression")
}

/**
 * OR combination of multiple expressions.
 * At least one expression must evaluate to true.
 * Example: "type == premium OR level >= 10 OR status == vip"
 */
case class OrExpression(
  expressions: List[FilterExpressionAST]
) extends FilterExpressionAST {
  require(expressions.nonEmpty, "OrExpression must have at least one expression")
}

/**
 * NOT negation of an expression.
 * Inverts the result of the wrapped expression.
 * Example: "NOT status == inactive"
 */
case class NotExpression(
  expression: FilterExpressionAST
) extends FilterExpressionAST
