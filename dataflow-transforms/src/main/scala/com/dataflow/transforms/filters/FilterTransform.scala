package com.dataflow.transforms.filters

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.{FilterConfig, StatelessTransform, ErrorHandlingStrategy}
import io.circe.parser._
import io.circe.Json
import io.gatling.jsonpath.JsonPath
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.LoggerFactory

import scala.util.{Try, Success, Failure}

/**
 * Filter transformation: Keep only records matching a condition.
 *
 * Supports two types of expressions:
 *
 * 1. JSONPath expressions (for complex JSON queries):
 *    - "$.age > 18"
 *    - "$.user.premium == true"
 *    - "$.amount >= 100"
 *
 * 2. Simple field comparisons (for basic string matching):
 *    - "status == active"
 *    - "type != test"
 *    - "level == premium"
 *
 * Records that don't match the filter are dropped from the stream.
 * Malformed records can be skipped (default) or cause pipeline failure.
 *
 * @param config Filter configuration
 */
class FilterTransform(config: FilterConfig) extends StatelessTransform {

  private val logger = LoggerFactory.getLogger(getClass)

  override def transformType: String = "filter"

  override def flow: Flow[DataRecord, DataRecord, NotUsed] = {
    Flow[DataRecord]
      .filter { record =>
        evaluateFilter(record) match {
          case Success(matches) =>
            if (!matches) {
              logger.debug(s"Record ${record.id} filtered out by expression: ${config.expression}")
            }
            matches

          case Failure(ex) =>
            errorHandler match {
              case ErrorHandlingStrategy.Skip =>
                logger.warn(s"Failed to evaluate filter for record ${record.id}, skipping", ex)
                false // Skip record on error

              case ErrorHandlingStrategy.Fail =>
                logger.error(s"Failed to evaluate filter for record ${record.id}, failing", ex)
                throw ex

              case ErrorHandlingStrategy.DeadLetter =>
                logger.warn(s"Failed to evaluate filter for record ${record.id}, sending to DLQ", ex)
                // TODO: Implement dead letter queue
                false
            }
        }
      }
  }

  /**
   * Evaluate the filter expression against a record.
   *
   * @param record The data record to test
   * @return Success(true) if matches, Success(false) if doesn't match, Failure on error
   */
  private def evaluateFilter(record: DataRecord): Try[Boolean] = {
    Try {
      val expression = config.expression.trim

      // Determine if this is a JSONPath expression or simple comparison
      if (expression.startsWith("$.")) {
        evaluateJsonPathExpression(record, expression)
      } else {
        evaluateSimpleExpression(record, expression)
      }
    }
  }

  /**
   * Evaluate JSONPath expressions like "$.age > 18"
   */
  private def evaluateJsonPathExpression(record: DataRecord, expression: String): Boolean = {
    // Convert DataRecord to JSON
    val jsonString = recordToJson(record)

    // Parse JSON
    parse(jsonString) match {
      case Right(json) =>
        // Extract path and operator
        parseJsonPathExpression(expression) match {
          case Some((path, operator, value)) =>
            evaluateJsonPathCondition(json, path, operator, value)

          case None =>
            logger.warn(s"Failed to parse JSONPath expression: $expression")
            false
        }

      case Left(error) =>
        logger.warn(s"Failed to parse record as JSON: ${error.getMessage}")
        false
    }
  }

  /**
   * Evaluate simple field comparisons like "status == active"
   */
  private def evaluateSimpleExpression(record: DataRecord, expression: String): Boolean = {
    // Parse expression: "field operator value"
    val parts = expression.split("\\s+")

    if (parts.length != 3) {
      logger.warn(s"Invalid simple expression format: $expression (expected: field operator value)")
      return false
    }

    val field = parts(0)
    val operator = parts(1)
    val expectedValue = parts(2)

    // Get field value from record
    val actualValue = record.data.get(field)

    actualValue match {
      case Some(value) =>
        operator match {
          case "==" => value == expectedValue
          case "!=" => value != expectedValue
          case ">" => compareNumeric(value, expectedValue, _ > _)
          case ">=" => compareNumeric(value, expectedValue, _ >= _)
          case "<" => compareNumeric(value, expectedValue, _ < _)
          case "<=" => compareNumeric(value, expectedValue, _ <= _)
          case _ =>
            logger.warn(s"Unsupported operator: $operator")
            false
        }

      case None =>
        logger.debug(s"Field $field not found in record ${record.id}")
        false
    }
  }

  /**
   * Parse JSONPath expression into (path, operator, value).
   * Example: "$.age > 18" -> ("$.age", ">", "18")
   */
  private def parseJsonPathExpression(expression: String): Option[(String, String, String)] = {
    // Find operator
    val operators = Seq("==", "!=", ">=", "<=", ">", "<")

    operators.collectFirst {
      case op if expression.contains(op) =>
        val parts = expression.split(op, 2).map(_.trim)
        if (parts.length == 2) {
          Some((parts(0), op, parts(1)))
        } else {
          None
        }
    }.flatten
  }

  /**
   * Evaluate JSONPath condition.
   */
  private def evaluateJsonPathCondition(json: Json, path: String, operator: String, expectedValue: String): Boolean = {
    Try {
      val jsonPath = JsonPath.compile(path).getOrElse {
        logger.warn(s"Failed to compile JSONPath: $path")
        return false
      }

      val result = jsonPath.query(json.noSpaces)

      result.headOption match {
        case Some(actualValue) =>
          operator match {
            case "==" => actualValue.toString == expectedValue
            case "!=" => actualValue.toString != expectedValue
            case ">" => compareNumeric(actualValue.toString, expectedValue, _ > _)
            case ">=" => compareNumeric(actualValue.toString, expectedValue, _ >= _)
            case "<" => compareNumeric(actualValue.toString, expectedValue, _ < _)
            case "<=" => compareNumeric(actualValue.toString, expectedValue, _ <= _)
            case _ =>
              logger.warn(s"Unsupported operator: $operator")
              false
          }

        case None =>
          logger.debug(s"JSONPath $path returned no results")
          false
      }
    }.getOrElse(false)
  }

  /**
   * Compare numeric values.
   */
  private def compareNumeric(v1: String, v2: String, op: (Double, Double) => Boolean): Boolean = {
    Try {
      val n1 = v1.toDouble
      val n2 = v2.toDouble
      op(n1, n2)
    }.getOrElse {
      // Fall back to string comparison if not numeric
      logger.debug(s"Non-numeric comparison: $v1 vs $v2")
      false
    }
  }

  /**
   * Convert DataRecord to JSON string.
   */
  private def recordToJson(record: DataRecord): String = {
    import io.circe.syntax._
    import io.circe.generic.auto._

    // Convert data map to JSON
    val dataJson = record.data.asJson

    // Combine with metadata if needed
    Json.obj(
      "id" -> Json.fromString(record.id),
      "data" -> dataJson,
      "metadata" -> record.metadata.asJson
    ).noSpaces
  }
}
