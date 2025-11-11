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
)(implicit system: ActorSystem[_]) extends FileSourceBaseV3(pipelineId, config) {

  override protected val formatName: String = "csv"

  private val delimiter: String  = config.options.getOrElse("delimiter", ",")
  private val hasHeader: Boolean = config.options.getOrElse("has-header", "true").toBoolean

  override protected def buildFormatStream(): PekkoSource[DataRecord, NotUsed] = {
    createLineStream()
      .statefulMapConcat {
        () =>
          var headerCols: Option[Vector[String]] = None

          {
            case (line, idx) =>
              // case 1: treat first line as header (only if we start from 0)
              if (hasHeader && idx == 0 && resumeFromLineNumber == 0) {
                headerCols = Some(parseHeader(line, delimiter))
                Nil // don’t emit a record for the header line
              } else {
                // parse data line with current header (or without)
                parseCsvLine(line, idx, headerCols).toList
              }
          }
      }
      .mapMaterializedValue(_ => NotUsed)
  }

  /**
   * Parse header line into columns.
   */
  private def parseHeader(line: String, delimiter: String): Vector[String] =
    line.split(delimiter, -1).map(_.trim).toVector

  /**
   * Parse a CSV line into a DataRecord (pure, no Try).
   */
  private def parseCsvLine(
    line: String,
    lineNumber: Long,
    headerCols: Option[Vector[String]],
  ): Option[DataRecord] = {
    // record raw line metrics (like original code)
    recordLineMetrics(line)

    val values: Vector[String] =
      line.split(delimiter, -1).map(_.trim).toVector

    // map to columns if we have header, otherwise field1, field2, ...
    val data: Map[String, String] =
      headerCols match {
        case Some(cols) =>
          cols.zipAll(values, "", "").toMap
        case None       =>
          values.zipWithIndex.map { case (v, i) => s"field${i + 1}" -> v }.toMap
      }

    // we can still fail gracefully if data is empty
    if (data.isEmpty) {
      recordParseError()
      log.warn("Failed to parse CSV line {}: empty data", Long.box(lineNumber))
      None
    } else {
      val id = data.getOrElse("id", UUID.randomUUID().toString)
      Some(
        DataRecord(
          id = id,
          data = data,
          metadata = createMetadata(lineNumber),
        ),
      )
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
