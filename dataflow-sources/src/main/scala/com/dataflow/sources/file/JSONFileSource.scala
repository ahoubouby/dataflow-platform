package com.dataflow.sources.file

import java.util.UUID

import scala.util.{Failure, Success, Try}

import com.dataflow.domain.models.{DataRecord, SourceConfig}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Source => PekkoSource}
import spray.json._

/**
 * JSON file source connector.
 *
 * Reads newline-delimited JSON files (NDJSON).
 * Each line should be a valid JSON object.
 *
 * Configuration:
 * {{{
 *   SourceConfig(
 *     sourceType = SourceType.File,
 *     connectionString = "/path/to/file.json",
 *     options = Map(
 *       "format" -> "json",
 *       "encoding" -> "UTF-8"
 *     ),
 *     batchSize = 1000
 *   )
 * }}}
 */
class JSONFileSource(
  pipelineId: String,
  config: SourceConfig,
)(implicit system: ActorSystem[_]) extends FileSourceBaseV3(pipelineId, config) {

  override protected val formatName: String = "json"

  // JSON lines can be large, increase frame length
  override protected val maximumFrameLength: Int = 1024 * 1024

  override protected def buildFormatStream(): PekkoSource[DataRecord, NotUsed] = {
    createLineStream()
      .map {
        case (line, idx) => parseJsonLine(line, idx)
      }
      .collect { case Some(record) => record }
      .mapMaterializedValue(_ => NotUsed)
  }

  /**
   * Parse a JSON line into a DataRecord.
   */
  private def parseJsonLine(line: String, lineNumber: Long): Option[DataRecord] = {
    Try {
      val json = line.parseJson.asJsObject

      // Extract all fields from JSON
      val data = json.fields.map {
        case (key, value) =>
          key -> value.toString.stripPrefix("\"").stripSuffix("\"")
      }

      // Use "id" field from JSON if present, otherwise generate UUID
      val id = json.fields
        .get("id")
        .map(_.toString.stripPrefix("\"").stripSuffix("\""))
        .getOrElse(UUID.randomUUID().toString)

      val record = DataRecord(
        id = id,
        data = data - "id", // Remove id from data fields since it's the record ID
        metadata = createMetadata(lineNumber),
      )

      // Record metrics
      recordLineMetrics(line)

      record
    } match {
      case Success(record) => Some(record)
      case Failure(ex)     =>
        recordParseError()
        log.warn("Failed to parse JSON line {}: {}", lineNumber, ex.getMessage)
        None
    }
  }
}

/**
 * Companion object with factory method.
 */
object JSONFileSource {

  def apply(pipelineId: String, config: SourceConfig)(implicit system: ActorSystem[_]): JSONFileSource =
    new JSONFileSource(pipelineId, config)
}
