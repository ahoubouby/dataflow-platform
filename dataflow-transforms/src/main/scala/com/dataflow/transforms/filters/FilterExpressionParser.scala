package com.dataflow.transforms.filters

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

  // -------------------------------------------
  // public API
  // -------------------------------------------
  def parse(expression: String): Either[FilterError, FilterExpressionAST] = {
    val tokens = tokenize(expression.trim)
    parseExpression(tokens).map(_._1)
  }

  // -------------------------------------------
  // tokenizer
  // -------------------------------------------
  private def tokenize(expr: String): List[String] = {
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

  // -------------------------------------------
  // expression -> OR -> AND -> NOT -> primary
  // -------------------------------------------
  private def parseExpression(tokens: List[String]): Either[FilterError, (FilterExpressionAST, List[String])] =
    parseOrExpression(tokens)

  // OR ------------------------------------------------------------------
  private def parseOrExpression(tokens: List[String]): Either[FilterError, (FilterExpressionAST, List[String])] =
    parseAndExpression(tokens).flatMap {
      case (firstExpr, rest1) =>
        collectOrExpressions(firstExpr, rest1)
    }

  private def collectOrExpressions(
    firstExpr: FilterExpressionAST,
    tokens: List[String],
  ): Either[FilterError, (FilterExpressionAST, List[String])] =
    tokens match {
      case "OR" :: rest =>
        // parse the right side of OR
        parseAndExpression(rest).flatMap {
          case (nextExpr, rest2) =>
            // keep collecting chained ORs
            collectOrExpressions(nextExpr, rest2).map {
              case (tailExpr, rest3) =>
                val combined = combineOr(firstExpr, tailExpr)
                (combined, rest3)
            }
        }

      case _ =>
        Right((firstExpr, tokens))
    }

  private def combineOr(left: FilterExpressionAST, right: FilterExpressionAST): FilterExpressionAST =
    (left, right) match {
      case (OrExpression(ls), OrExpression(rs)) => OrExpression(ls ++ rs)
      case (OrExpression(ls), _)                => OrExpression(ls :+ right)
      case (_, OrExpression(rs))                => OrExpression(left :: rs)
      case _                                    => OrExpression(List(left, right))
    }

  // AND -----------------------------------------------------------------
  private def parseAndExpression(tokens: List[String]): Either[FilterError, (FilterExpressionAST, List[String])] =
    parseNotExpression(tokens).flatMap {
      case (firstExpr, rest1) =>
        collectAndExpressions(firstExpr, rest1)
    }

  private def collectAndExpressions(
    firstExpr: FilterExpressionAST,
    tokens: List[String],
  ): Either[FilterError, (FilterExpressionAST, List[String])] =
    tokens match {
      case "AND" :: rest =>
        parseNotExpression(rest).flatMap {
          case (nextExpr, rest2) =>
            collectAndExpressions(nextExpr, rest2).map {
              case (tailExpr, rest3) =>
                val combined = combineAnd(firstExpr, tailExpr)
                (combined, rest3)
            }
        }

      case _ =>
        Right((firstExpr, tokens))
    }

  private def combineAnd(left: FilterExpressionAST, right: FilterExpressionAST): FilterExpressionAST =
    (left, right) match {
      case (AndExpression(ls), AndExpression(rs)) => AndExpression(ls ++ rs)
      case (AndExpression(ls), _)                 => AndExpression(ls :+ right)
      case (_, AndExpression(rs))                 => AndExpression(left :: rs)
      case _                                      => AndExpression(List(left, right))
    }

  // NOT -----------------------------------------------------------------
  private def parseNotExpression(tokens: List[String]): Either[FilterError, (FilterExpressionAST, List[String])] =
    tokens match {
      case "NOT" :: rest =>
        parsePrimary(rest).map {
          case (expr, remaining) => (NotExpression(expr), remaining)
        }
      case _             =>
        parsePrimary(tokens)
    }

  // PRIMARY -------------------------------------------------------------
  private def parsePrimary(tokens: List[String]): Either[FilterError, (FilterExpressionAST, List[String])] =
    tokens match {
      case "(" :: rest =>
        parseExpression(rest).flatMap {
          case (expr, rest2) =>
            rest2 match {
              case ")" :: rest3 => Right((expr, rest3))
              case _            => Left(InvalidExpressionFormat(tokens.mkString(" "), "Missing closing parenthesis"))
            }
        }

      case _ =>
        parseSimpleExpression(tokens)
    }

  // SIMPLE --------------------------------------------------------------
  private def parseSimpleExpression(tokens: List[String]): Either[FilterError, (FilterExpressionAST, List[String])] =
    tokens match {
      case field :: opStr :: value :: rest =>
        for {
          operator <- parseOperator(opStr)
        } yield (SimpleExpression(field, operator, value), rest)

      case _ =>
        Left(
          InvalidExpressionFormat(
            tokens.mkString(" "),
            "Expected format: field operator value",
          ),
        )
    }

  private def parseOperator(op: String): Either[FilterError, ComparisonOperator] =
    op match {
      case "==" => Right(ComparisonOperator.Equal)
      case "!=" => Right(ComparisonOperator.NotEqual)
      case ">"  => Right(ComparisonOperator.GreaterThan)
      case ">=" => Right(ComparisonOperator.GreaterThanOrEqual)
      case "<"  => Right(ComparisonOperator.LessThan)
      case "<=" => Right(ComparisonOperator.LessThanOrEqual)
      case _    => Left(UnsupportedOperator(op))
    }

  // ---------------------------------------------------------------------
  // for debugging
  // ---------------------------------------------------------------------
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
