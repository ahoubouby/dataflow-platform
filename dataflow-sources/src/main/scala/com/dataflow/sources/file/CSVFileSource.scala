package com.dataflow.sources.file

import java.util.UUID

import scala.util.{Failure, Success, Try}

import com.dataflow.domain.models.{DataRecord, SourceConfig}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Source => PekkoSource}

/**
 * CSV file source connector.
 *
 * Reads CSV files with configurable delimiters and optional headers.
 *
 * Configuration:
 * {{{
 *   SourceConfig(
 *     sourceType = SourceType.File,
 *     connectionString = "/path/to/file.csv",
 *     options = Map(
 *       "format" -> "csv",
 *       "delimiter" -> ",",
 *       "has-header" -> "true",
 *       "encoding" -> "UTF-8"
 *     ),
 *     batchSize = 1000
 *   )
 * }}}
 */
class CSVFileSource(
  pipelineId: String,
  config: SourceConfig,
)(implicit system: ActorSystem[_]) extends FileSourceBase(pipelineId, config) {

  override protected val formatName: String = "csv"

  private val delimiter: String =
    config.options.getOrElse("delimiter", ",")

  private val hasHeader: Boolean =
    config.options.getOrElse("has-header", "true").toBoolean

  @volatile private var headerColumns: Option[Vector[String]] = None

  override protected def buildFormatStream(): PekkoSource[DataRecord, NotUsed] = {
    createLineStream()
      .map {
        case (line, idx) =>
          // If we have a header, the first line (idx == 0) becomes column names
          if (hasHeader && idx == 0 && resumeFromLineNumber == 0) {
            val cols = line.split(delimiter, -1).map(_.trim).toVector
            headerColumns = Some(cols)
            None // Don't emit a DataRecord for the header
          } else {
            parseCsvLine(line, idx)
          }
      }
      .collect { case Some(record) => record }
      .mapMaterializedValue(_ => NotUsed)
  }

  /**
   * Parse a CSV line into a DataRecord.
   */
  private def parseCsvLine(line: String, lineNumber: Long): Option[DataRecord] = {
    Try {
      val values = line.split(delimiter, -1).map(_.trim).toVector

      // Map values to column names if header exists
      val data: Map[String, String] = headerColumns match {
        case Some(cols) =>
          // Zip with header columns
          cols.zip(values).toMap
        case None       =>
          // Generate field1, field2, ... names
          values.zipWithIndex.map { case (v, i) => s"field${i + 1}" -> v }.toMap
      }

      val record = DataRecord(
        id = data.getOrElse("id", UUID.randomUUID().toString),
        data = data,
        metadata = createMetadata(lineNumber),
      )

      // Record metrics
      recordLineMetrics(line)

      record
    } match {
      case Success(record) => Some(record)
      case Failure(ex)     =>
        recordParseError()
        log.warn("Failed to parse CSV line {}: {}", lineNumber, ex.getMessage)
        None
    }
  }
}

/**
 * Companion object with factory method.
 */
object CSVFileSource {

  def apply(pipelineId: String, config: SourceConfig)(implicit system: ActorSystem[_]): CSVFileSource =
    new CSVFileSource(pipelineId, config)
}
