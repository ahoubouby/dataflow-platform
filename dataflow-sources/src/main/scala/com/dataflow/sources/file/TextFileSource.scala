package com.dataflow.sources.file

import java.util.UUID

import com.dataflow.domain.models.{DataRecord, SourceConfig}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Source => PekkoSource}

/**
 * Plain text file source connector.
 *
 * Reads plain text files line by line.
 * Each line becomes a DataRecord with a single "line" field.
 *
 * Configuration:
 * {{{
 *   SourceConfig(
 *     sourceType = SourceType.File,
 *     connectionString = "/path/to/file.txt",
 *     options = Map(
 *       "format" -> "text",
 *       "encoding" -> "UTF-8"
 *     ),
 *     batchSize = 1000
 *   )
 * }}}
 */
class TextFileSource(
  pipelineId: String,
  config: SourceConfig,
)(implicit system: ActorSystem[_]) extends FileSourceBase(pipelineId, config) {

  override protected val formatName: String = "text"

  override protected def buildFormatStream(): PekkoSource[DataRecord, NotUsed] = {
    createLineStream()
      .map {
        case (line, idx) =>
          // Record metrics
          recordLineMetrics(line)

          // Create DataRecord with line as single field
          DataRecord(
            id = UUID.randomUUID().toString,
            data = Map("line" -> line),
            metadata = createMetadata(idx),
          )
      }
      .mapMaterializedValue(_ => NotUsed)
  }
}

/**
 * Companion object with factory method.
 */
object TextFileSource {
  def apply(pipelineId: String, config: SourceConfig)(implicit system: ActorSystem[_]): TextFileSource =
    new TextFileSource(pipelineId, config)
}
