package com.dataflow.transforms.mapping

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.{MapConfig, StatelessTransform, ErrorHandlingStrategy, TransformType}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.LoggerFactory

import scala.util.{Try, Success, Failure}

/**
 * Map transformation: Transform record fields.
 *
 * Supports multiple operations:
 * 1. Field renaming: {"oldName": "newName"}
 * 2. Field extraction from nested: {"user.name": "userName"}
 * 3. Field deletion: {"fieldToRemove": null}
 * 4. Constant injection: {"newField": "constantValue"}
 *
 * By default, unmapped fields are preserved in the output.
 *
 * Examples:
 * - Rename: mapping = {"firstName": "first_name"}
 *   Input: {firstName: "John", lastName: "Doe"}
 *   Output: {first_name: "John", lastName: "Doe"}
 *
 * - Extract nested: mapping = {"user.email": "email"}
 *   Input: {user.email: "john@example.com", user.name: "John"}
 *   Output: {email: "john@example.com", user.name: "John"}
 *
 * - Delete: mapping = {"temporaryField": null}
 *   Input: {temporaryField: "temp", importantField: "keep"}
 *   Output: {importantField: "keep"}
 *
 * @param config Map transformation configuration
 */
class MapTransform(config: MapConfig) extends StatelessTransform {

  private val logger = LoggerFactory.getLogger(getClass)

  override def transformType: TransformType = TransformType.Map

  override def flow: Flow[DataRecord, DataRecord, NotUsed] = {
    Flow[DataRecord].map { record =>
      applyMappings(record) match {
        case Success(transformedRecord) =>
          logger.debug(s"Successfully mapped record ${record.id}")
          transformedRecord

        case Failure(ex) =>
          errorHandler match {
            case ErrorHandlingStrategy.Skip =>
              logger.warn(s"Failed to map record ${record.id}, returning original", ex)
              record // Return original on error

            case ErrorHandlingStrategy.Fail =>
              logger.error(s"Failed to map record ${record.id}, failing", ex)
              throw ex

            case ErrorHandlingStrategy.DeadLetter =>
              logger.warn(s"Failed to map record ${record.id}, sending to DLQ", ex)
              // TODO: Implement dead letter queue
              record
          }
      }
    }
  }

  /**
   * Apply all mappings to a record.
   *
   * @param record The input record
   * @return Success with transformed record, or Failure on error
   */
  private def applyMappings(record: DataRecord): Try[DataRecord] = {
    Try {
      var transformedData = if (config.preserveUnmapped) {
        record.data
      } else {
        Map.empty[String, String]
      }

      // Apply each mapping
      config.mappings.foreach { case (sourceField, targetField) =>
        if (targetField == null || targetField == "null") {
          // Delete operation
          transformedData = transformedData - sourceField
          logger.trace(s"Deleted field: $sourceField from record ${record.id}")
        } else {
          // Rename, extract, or inject operation
          val value = extractValue(record, sourceField)

          value match {
            case Some(v) =>
              // Remove old field if renaming
              if (transformedData.contains(sourceField) && sourceField != targetField) {
                transformedData = transformedData - sourceField
              }
              // Add with new name
              transformedData = transformedData + (targetField -> v)
              logger.trace(s"Mapped $sourceField -> $targetField = $v in record ${record.id}")

            case None =>
              // Check if this is a constant injection (source field doesn't exist)
              if (!sourceField.contains(".") && !record.data.contains(sourceField)) {
                // Treat source as constant value
                transformedData = transformedData + (targetField -> sourceField)
                logger.trace(s"Injected constant: $targetField = $sourceField in record ${record.id}")
              } else {
                logger.debug(s"Source field $sourceField not found in record ${record.id}, skipping")
              }
          }
        }
      }

      record.copy(data = transformedData)
    }
  }

  /**
   * Extract value from record, supporting nested field access.
   *
   * Supports:
   * - Simple field: "name"
   * - Nested field: "user.name" (dot notation)
   *
   * @param record The record to extract from
   * @param fieldPath The field path (may include dots for nesting)
   * @return Some(value) if found, None otherwise
   */
  private def extractValue(record: DataRecord, fieldPath: String): Option[String] = {
    if (!fieldPath.contains(".")) {
      // Simple field access
      record.data.get(fieldPath)
    } else {
      // Nested field access - look for exact match first
      record.data.get(fieldPath) match {
        case Some(value) => Some(value)
        case None =>
          // Try to extract from nested structure
          // For now, we support simple dot notation stored as "parent.child" keys
          // In the future, we could parse nested JSON structures
          None
      }
    }
  }
}
