package com.dataflow.transforms.mapping

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.{FlatMapConfig, StatelessTransform, ErrorHandlingStrategy, TransformType}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.LoggerFactory

import scala.util.{Try, Success, Failure}

/**
 * FlatMap transformation: Split one record into multiple records.
 *
 * Takes a field containing a comma-separated list and creates separate
 * records for each value.
 *
 * Example:
 * Input:  {id: "1", items: "apple,banana,cherry", price: "10"}
 * Output: [
 *   {id: "1", item: "apple", price: "10"},
 *   {id: "1", item: "banana", price: "10"},
 *   {id: "1", item: "cherry", price: "10"}
 * ]
 *
 * Configuration:
 * - splitField: The field to split (e.g., "items")
 * - targetField: The name for individual items (default: splitField without 's')
 * - preserveParent: Whether to include parent record fields (default: true)
 *
 * Note: Currently supports comma-separated strings. Future versions could
 * support JSON arrays or other delimiters.
 *
 * @param config FlatMap transformation configuration
 */
class FlatMapTransform(config: FlatMapConfig) extends StatelessTransform {

  private val logger = LoggerFactory.getLogger(getClass)

  override def transformType: TransformType = TransformType.FlatMap

  override def flow: Flow[DataRecord, DataRecord, NotUsed] = {
    Flow[DataRecord].mapConcat { record =>
      splitRecord(record) match {
        case Success(records) =>
          if (records.nonEmpty) {
            logger.debug(s"Split record ${record.id} into ${records.size} records")
            records
          } else {
            logger.debug(s"Record ${record.id} has empty split field, returning empty list")
            List.empty
          }

        case Failure(ex) =>
          errorHandler match {
            case ErrorHandlingStrategy.Skip =>
              logger.warn(s"Failed to split record ${record.id}, skipping", ex)
              List.empty // Skip record on error

            case ErrorHandlingStrategy.Fail =>
              logger.error(s"Failed to split record ${record.id}, failing", ex)
              throw ex

            case ErrorHandlingStrategy.DeadLetter =>
              logger.warn(s"Failed to split record ${record.id}, sending to DLQ", ex)
              // TODO: Implement dead letter queue
              List.empty
          }
      }
    }
  }

  /**
   * Split a record into multiple records based on configuration.
   *
   * @param record The input record
   * @return Success with list of records, or Failure on error
   */
  private def splitRecord(record: DataRecord): Try[List[DataRecord]] = {
    Try {
      // Get the field to split
      record.data.get(config.splitField) match {
        case Some(value) =>
          // Split the value (comma-separated by default)
          val items = splitValue(value)

          if (items.isEmpty) {
            // Empty array - return empty list
            List.empty
          } else {
            // Create a new record for each item
            items.zipWithIndex.map { case (item, index) =>
              val targetField = config.targetField.getOrElse(deriveSingularName(config.splitField))

              val newData = if (config.preserveParent) {
                // Include parent fields, but remove the original split field
                record.data - config.splitField + (targetField -> item.trim)
              } else {
                // Only the target field
                Map(targetField -> item.trim)
              }

              // Generate new ID for child record
              val newId = s"${record.id}-${index + 1}"

              DataRecord(
                id = newId,
                data = newData,
                metadata = record.metadata + ("parentId" -> record.id) + ("splitIndex" -> index.toString)
              )
            }.toList
          }

        case None =>
          logger.warn(s"Split field '${config.splitField}' not found in record ${record.id}")
          // Return original record in a list if field not found
          List(record)
      }
    }
  }

  /**
   * Split a value into individual items.
   *
   * Currently supports:
   * - Comma-separated strings: "a,b,c" -> ["a", "b", "c"]
   *
   * Future: Could support JSON arrays, other delimiters, etc.
   *
   * @param value The value to split
   * @return List of individual items
   */
  private def splitValue(value: String): List[String] = {
    if (value.trim.isEmpty) {
      List.empty
    } else {
      // Split by comma and filter out empty strings
      value.split(",").map(_.trim).filter(_.nonEmpty).toList
    }
  }

  /**
   * Derive singular field name from plural.
   *
   * Examples:
   * - "items" -> "item"
   * - "users" -> "user"
   * - "categories" -> "category"
   *
   * Simple implementation: remove trailing 's' or 'es'
   */
  private def deriveSingularName(plural: String): String = {
    if (plural.endsWith("ies")) {
      // categories -> category
      plural.dropRight(3) + "y"
    } else if (plural.endsWith("es")) {
      // classes -> class
      plural.dropRight(2)
    } else if (plural.endsWith("s")) {
      // items -> item
      plural.dropRight(1)
    } else {
      // No change if not plural
      plural
    }
  }
}
