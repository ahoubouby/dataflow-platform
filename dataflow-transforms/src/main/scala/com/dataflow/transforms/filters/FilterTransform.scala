package com.dataflow.transforms.filters

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.{FilterConfig, StatelessTransform, ErrorHandlingStrategy, TransformType}
import io.circe.parser._
import io.circe.Json
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.LoggerFactory

import scala.util.{Try, Success, Failure}

/**
 * Filter transformation: Keep only records matching a condition.
 *
 * Supports simple field comparisons:
 *    - "status == active"
 *    - "type != test"
 *    - "age > 18"
 *    - "level >= 5"
 *
 * For complex JSON queries, use circe-optics or implement JSONPath support later.
 *
 * Records that don't match the filter are dropped from the stream.
 * Malformed records can be skipped (default) or cause pipeline failure.
 *
 * @param config Filter configuration
 */
class FilterTransform(config: FilterConfig) extends StatelessTransform {

  private val logger = LoggerFactory.getLogger(getClass)

  override def transformType: TransformType = TransformType.Filter

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
      evaluateSimpleExpression(record, expression)
    }
  }

  /**
   * Evaluate simple field comparisons like "status == active" or "age > 18"
   */
  private def evaluateSimpleExpression(record: DataRecord, expression: String): Boolean = {
    // Parse expression: "field operator value"
    val parts = expression.split("\\s+")

    if (parts.length != 3) {
      logger.warn(s"Invalid expression format: $expression (expected: field operator value)")
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
}
