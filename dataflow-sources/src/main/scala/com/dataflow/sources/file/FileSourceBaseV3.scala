package com.dataflow.sources.file

import java.io.FileNotFoundException
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import com.dataflow.domain.commands.{Command, IngestBatch}
import com.dataflow.domain.models.{DataRecord, SourceConfig}
import com.dataflow.sources.{Source, SourceMetricsReporter}
import com.dataflow.sources.models.SourceState
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.{KillSwitches, SystemMaterializer}
import org.apache.pekko.stream.scaladsl.{FileIO, Framing, Keep, Sink, Source => PekkoSource}
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory

/**
 * Base class for file-based source connectors.
 *
 * Provides common functionality for reading files:
 * - File I/O streaming
 * - Offset tracking (line-based)
 * - Lifecycle management (start, stop, health checks)
 * - Batch sending to pipeline
 * - Metrics collection
 *
 * Subclasses implement format-specific parsing logic.
 */
abstract class FileSourceBaseV3(
  val pipelineId: String,
  val config: SourceConfig,
)(implicit system: ActorSystem[_]) extends Source {

  protected val log = LoggerFactory.getLogger(getClass)

  implicit protected val ec: ExecutionContext = system.executionContext
  implicit protected val mat = SystemMaterializer(system).materializer

  override val sourceId: String = s"file-source-$pipelineId-${UUID.randomUUID()}"

  // ----- Configuration -----
  protected val filePath: Path = Paths.get(config.connectionString)

  protected val encoding: String =
    config.options.getOrElse("encoding", "UTF-8")

  // ----- State -----
  @volatile protected var currentLineNumber:    Long                                             = 0
  @volatile protected var resumeFromLineNumber: Long                                             = 0
  @volatile private var isRunning:              Boolean                                          = false
  @volatile private var killSwitch:             Option[org.apache.pekko.stream.UniqueKillSwitch] = None

  log.info(
    "Initialized {} id={} path={} batchSize={}",
    getClass.getSimpleName,
    sourceId,
    filePath,
    config.batchSize,
  )

  // Initialize metrics
  if (Files.exists(filePath)) {
    SourceMetricsReporter.updateFileSize(pipelineId, Files.size(filePath))
  }

  /**
   * Format name for logging and metrics (e.g., "csv", "json", "text").
   * Must be implemented by subclasses.
   */
  protected def formatName: String

  /**
   * Build the format-specific data stream.
   * Subclasses implement this to parse their specific format.
   *
   * @return Stream of DataRecords from the file
   */
  protected def buildFormatStream(): PekkoSource[DataRecord, NotUsed]

  /**
   * Maximum frame length for line framing.
   * Can be overridden by subclasses (e.g., JSON needs larger frames).
   */
  protected def maximumFrameLength: Int = 8192

  /**
   * Create a line-based stream from the file.
   * Handles framing, encoding, offset filtering.
   */
  protected def createLineStream(): PekkoSource[(String, Long), NotUsed] =
    FileIO
      .fromPath(filePath)
      .via(
        Framing.delimiter(
          ByteString("\n"),
          maximumFrameLength = maximumFrameLength,
          allowTruncation = true,
        ),
      )
      .map(_.decodeString(encoding))
      .zipWithIndex
      .filter {
        case (_, idx) => idx >= resumeFromLineNumber
      }
      .map {
        case (line, idx) =>
          currentLineNumber = idx
          (line, idx)
      }.mapMaterializedValue(_ => NotUsed)

  /**
   * Record metrics for a successfully parsed line.
   */
  protected def recordLineMetrics(line: String): Unit = {
    SourceMetricsReporter.recordRecordsRead(pipelineId, "file", 1)
    SourceMetricsReporter.recordBytesRead(pipelineId, "file", line.getBytes(encoding).length.toLong)
    SourceMetricsReporter.recordFileLinesRead(pipelineId, filePath.toString, 1)
  }

  /**
   * Record metrics for a parse error.
   */
  protected def recordParseError(): Unit =
    SourceMetricsReporter.recordParseError(pipelineId, "file", formatName)

  /**
   * Create common metadata for DataRecords.
   */
  protected def createMetadata(lineNumber: Long): Map[String, String] = Map(
    "source"      -> "file",
    "source_id"   -> sourceId,
    "file_path"   -> filePath.toString,
    "line_number" -> (lineNumber + 1).toString,
    "format"      -> formatName,
    "timestamp"   -> Instant.now().toString,
  )

  /**
   * Create streaming source from file.
   */
  override def stream(): PekkoSource[DataRecord, NotUsed] = {
    if (!Files.exists(filePath)) {
      log.error("File not found: {}", filePath)
      throw new FileNotFoundException(s"File not found: $filePath")
    }

    buildFormatStream()
  }

  /**
   * Stop reading file.
   */
  override def stop(): Future[Done] = {
    if (!isRunning) {
      log.warn(s"{} not running: {}", getClass.getSimpleName, sourceId)
      return Future.successful(Done)
    }

    log.info(s"Stopping {}: {}", getClass.getSimpleName, sourceId)

    killSwitch.foreach(_.shutdown())
    killSwitch = None
    isRunning = false

    // Update health metrics
    SourceMetricsReporter.updateHealth(pipelineId, "file", isHealthy = false)

    Future.successful(Done)
  }

  override def currentOffset(): Long = currentLineNumber

  override def resumeFrom(offset: Long): Unit = {
    log.info(s"Resuming {} from offset: {}", getClass.getSimpleName, offset)
    resumeFromLineNumber = offset
    currentLineNumber = offset
  }

  override def isHealthy: Boolean =
    Files.exists(filePath) && Files.isReadable(filePath) && isRunning

  override def state: SourceState = {
    if (!isHealthy) SourceState.Failed
    else if (isRunning) SourceState.Running
    else SourceState.Stopped
  }
}
