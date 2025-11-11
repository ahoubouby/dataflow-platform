package com.dataflow.transforms.filters

import cats.implicits._
import com.dataflow.transforms.errors._

/**
 * Parser for filter expressions supporting AND, OR, NOT, and parentheses.
 *
 * Grammar:
 * {{{
 * expression    ::= orExpression
 * orExpression  ::= andExpression ("OR" andExpression)*
 * andExpression ::= notExpression ("AND" notExpression)*
 * notExpression ::= "NOT" primary | primary
 * primary       ::= simpleExpression | "(" expression ")"
 * simpleExpression ::= field operator value
 * operator      ::= "==" | "!=" | ">" | ">=" | "<" | "<="
 * }}}
 *
 * Examples:
 * - status == active
 * - status == active AND age > 18
 * - type == premium OR level >= 5
 * - (status == active AND age > 18) OR level >= 10
 * - NOT status == inactive
 * - status == active AND (type == premium OR level >= 5)
 */
object FilterExpressionParser {

  /**
   * Parse a filter expression string into an AST.
   */
  def parse(expression: String): Either[FilterError, FilterExpressionAST] = {
    val tokens = tokenize(expression.trim)
    parseExpression(tokens).map(_._1)
  }

  /**
   * Tokenize the expression string.
   * Splits on spaces but preserves operators and parentheses.
   */
  private def tokenize(expr: String): List[String] = {
    // Replace operators and parentheses with spaced versions to ensure proper splitting
    val spaced = expr
      .replaceAll("\\(", " ( ")
      .replaceAll("\\)", " ) ")
      .replaceAll("==", " == ")
      .replaceAll("!=", " != ")
      .replaceAll(">=", " >= ")
      .replaceAll("<=", " <= ")
      .replaceAll(">", " > ")
      .replaceAll("<", " < ")

    spaced.split("\\s+").filter(_.nonEmpty).toList
  }

  /**
   * Parse top-level expression (handles OR precedence).
   */
  private def parseExpression(tokens: List[String]): Either[FilterError, (FilterExpressionAST, List[String])] = {
    parseOrExpression(tokens)
  }

  /**
   * Parse OR expression (lowest precedence).
   */
  private def parseOrExpression(tokens: List[String]): Either[FilterError, (FilterExpressionAST, List[String])] = {
    for {
      (firstExpr, rest1) <- parseAndExpression(tokens)
      (finalExpr, rest2) <- collectOrExpressions(firstExpr, rest1)
    } yield (finalExpr, rest2)
  }

  /**
   * Collect multiple expressions joined by OR.
   */
  private def collectOrExpressions(
    firstExpr: FilterExpressionAST,
    tokens: List[String]
  ): Either[FilterError, (FilterExpressionAST, List[String])] = {
    tokens match {
      case "OR" :: rest =>
        for {
          (nextExpr, rest2) <- parseAndExpression(rest)
          (finalExpr, rest3) <- collectOrExpressions(nextExpr, rest2)
          combined = firstExpr match {
            case OrExpression(exprs) => OrExpression(exprs :+ finalExpr)
            case _ => finalExpr match {
              case OrExpression(exprs) => OrExpression(firstExpr :: exprs)
              case _ => OrExpression(List(firstExpr, finalExpr))
            }
          }
        } yield (combined, rest3)

      case _ =>
        Right((firstExpr, tokens))
    }
  }

  /**
   * Parse AND expression (medium precedence).
   */
  private def parseAndExpression(tokens: List[String]): Either[FilterError, (FilterExpressionAST, List[String])] = {
    for {
      (firstExpr, rest1) <- parseNotExpression(tokens)
      (finalExpr, rest2) <- collectAndExpressions(firstExpr, rest1)
    } yield (finalExpr, rest2)
  }

  /**
   * Collect multiple expressions joined by AND.
   */
  private def collectAndExpressions(
    firstExpr: FilterExpressionAST,
    tokens: List[String]
  ): Either[FilterError, (FilterExpressionAST, List[String])] = {
    tokens match {
      case "AND" :: rest =>
        for {
          (nextExpr, rest2) <- parseNotExpression(rest)
          (finalExpr, rest3) <- collectAndExpressions(nextExpr, rest2)
          combined = firstExpr match {
            case AndExpression(exprs) => AndExpression(exprs :+ finalExpr)
            case _ => finalExpr match {
              case AndExpression(exprs) => AndExpression(firstExpr :: exprs)
              case _ => AndExpression(List(firstExpr, finalExpr))
            }
          }
        } yield (combined, rest3)

      case _ =>
        Right((firstExpr, tokens))
    }
  }

  /**
   * Parse NOT expression (high precedence).
   */
  private def parseNotExpression(tokens: List[String]): Either[FilterError, (FilterExpressionAST, List[String])] = {
    tokens match {
      case "NOT" :: rest =>
        for {
          (expr, remaining) <- parsePrimary(rest)
        } yield (NotExpression(expr), remaining)

      case _ =>
        parsePrimary(tokens)
    }
  }

  /**
   * Parse primary expression (highest precedence) - simple expression or parenthesized expression.
   */
  private def parsePrimary(tokens: List[String]): Either[FilterError, (FilterExpressionAST, List[String])] = {
    tokens match {
      case "(" :: rest =>
        // Parenthesized expression
        for {
          (expr, rest2) <- parseExpression(rest)
          remaining <- rest2 match {
            case ")" :: rest3 => Right(rest3)
            case _ => Left(InvalidExpressionFormat(tokens.mkString(" "), "Missing closing parenthesis"))
          }
        } yield (expr, remaining)

      case _ =>
        // Simple expression: field operator value
        parseSimpleExpression(tokens)
    }
  }

  /**
   * Parse simple expression: field operator value
   */
  private def parseSimpleExpression(tokens: List[String]): Either[FilterError, (FilterExpressionAST, List[String])] = {
    tokens match {
      case field :: opStr :: value :: rest =>
        for {
          operator <- parseOperator(opStr)
        } yield (SimpleExpression(field, operator, value), rest)

      case _ =>
        Left(InvalidExpressionFormat(
          tokens.mkString(" "),
          "Expected format: field operator value"
        ))
    }
  }

  /**
   * Parse operator string into typed operator.
   */
  private def parseOperator(op: String): Either[FilterError, ComparisonOperator] = {
    op match {
      case "==" => Right(ComparisonOperator.Equal)
      case "!=" => Right(ComparisonOperator.NotEqual)
      case ">"  => Right(ComparisonOperator.GreaterThan)
      case ">=" => Right(ComparisonOperator.GreaterThanOrEqual)
      case "<"  => Right(ComparisonOperator.LessThan)
      case "<=" => Right(ComparisonOperator.LessThanOrEqual)
      case _    => Left(UnsupportedOperator(op))
    }
  }

  /**
   * Convert AST back to expression string (for debugging/logging).
   */
  def toExpressionString(ast: FilterExpressionAST): String = ast match {
    case SimpleExpression(field, operator, value) =>
      s"$field ${ComparisonOperator.toString(operator)} $value"

    case AndExpression(exprs) =>
      exprs.map(toExpressionString).mkString("(", " AND ", ")")

    case OrExpression(exprs) =>
      exprs.map(toExpressionString).mkString("(", " OR ", ")")

    case NotExpression(expr) =>
      s"NOT ${toExpressionString(expr)}"
  }
}
