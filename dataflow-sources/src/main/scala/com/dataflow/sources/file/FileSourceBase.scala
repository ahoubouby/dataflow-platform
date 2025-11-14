package com.dataflow.sources.file

import java.nio.charset.Charset
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import com.dataflow.domain.commands.{Command, IngestBatch}
import com.dataflow.domain.models.{DataRecord, SourceConfig}
import com.dataflow.sources.{Source, SourceMetricsReporter}
import com.dataflow.sources.models._
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.{KillSwitches, SystemMaterializer}
import org.apache.pekko.stream.scaladsl.{FileIO, Framing, Keep, Sink, Source => PekkoSource}
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory

abstract class FileSourceBase(
  val pipelineId: String,
  protected val fileConfig: FileSourceConfig,
)(implicit system: ActorSystem[_]) extends Source {

  protected val log = LoggerFactory.getLogger(getClass)

  implicit protected val ec: ExecutionContext = system.executionContext
  implicit protected val mat = SystemMaterializer(system).materializer

  override val sourceId: String = s"file-source-$pipelineId-${UUID.randomUUID()}"

  // Thread-safe state management
  private val stateRef = new AtomicReference[FileSourceStateSnapshot](
    FileSourceStateSnapshot.initial(),
  )

  log.info(
    "Initialized {} id={} path={} batchSize={}",
    getClass.getSimpleName,
    sourceId,
    fileConfig.filePath,
    fileConfig.batchSize,
  )

  // Initialize metrics
  initializeMetrics()

  // ============================================================================
  // Abstract Methods - Must be implemented by subclasses
  // ============================================================================

  /**
   * Format name for logging and metrics (e.g., "csv", "json", "text").
   */
  protected def formatName: String

  /**
   * Build the format-specific data stream.
   * Subclasses implement this to parse their specific format.
   *
   * @return Either an error or a stream of DataRecords
   */
  protected def buildFormatStream(): Either[FileSourceError, PekkoSource[DataRecord, NotUsed]]

  // ============================================================================
  // State Management
  // ============================================================================

  protected def getCurrentState: FileSourceStateSnapshot = stateRef.get()

  protected def updateState(f: FileSourceStateSnapshot => FileSourceStateSnapshot): Unit = {
    stateRef.updateAndGet(current => f(current))
    ()
  }

  protected def updateStateAndGet(f: FileSourceStateSnapshot => FileSourceStateSnapshot): FileSourceStateSnapshot =
    stateRef.updateAndGet(current => f(current))

  // ============================================================================
  // Configuration Access
  // ============================================================================

  protected def filePath:           Path    = fileConfig.filePath
  protected def encoding:           Charset = fileConfig.encoding
  protected def batchSize:          Int     = fileConfig.batchSize
  protected def maximumFrameLength: Int     = fileConfig.maximumFrameLength

  // ============================================================================
  // Metrics
  // ============================================================================

  private def initializeMetrics(): Unit = {
    import cats.syntax.either._
    Either.catchNonFatal {
      if (Files.exists(filePath)) {
        SourceMetricsReporter.updateFileSize(pipelineId, Files.size(filePath))
      }
    }.leftMap {
      ex =>
        log.warn(s"Failed to initialize metrics: ${ex.getMessage}")
    }
    ()
  }

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
   * Record metrics for stream error.
   */
  protected def recordStreamError(): Unit =
    SourceMetricsReporter.recordError(pipelineId, "file", "stream_failure")

  // ============================================================================
  // Metadata Creation
  // ============================================================================

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

  // ============================================================================
  // Line Stream Creation
  // ============================================================================

  /**
   * Create a line-based stream from the file.
   * Handles framing, encoding, offset filtering with functional error handling.
   */
  protected def createLineStream(): Either[FileSourceError, PekkoSource[(String, Long), NotUsed]] = {
    import cats.syntax.either._
    Either.catchNonFatal {
      val state = getCurrentState

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
        .filter { case (_, idx) => idx >= state.resumeFromLineNumber }
        .map {
          case (line, idx) =>
            updateState(_.withLineNumber(idx))
            (line, idx)
        }
        .mapMaterializedValue(_ => NotUsed)
    }.leftMap(ex => FileSourceError.StreamFailure(ex))
  }

  // ============================================================================
  // Source Interface Implementation
  // ============================================================================

  /**
   * Create streaming source from file.
   */
  override def stream(): PekkoSource[DataRecord, NotUsed] = {
    buildFormatStream().fold(
      {
        err =>
          log.error(s"Failed to build format stream: ${err.message}")
          PekkoSource.failed(new RuntimeException(err.message))
      },
      src =>
        src.mapMaterializedValue(_ => NotUsed),
    )
  }

  private def updateReadProgress(currentOffset: Long): Unit = {
    import cats.syntax.either._
    Either.catchNonFatal {
      if (Files.exists(filePath)) {
        val totalLines = Files.lines(filePath).count()
        if (totalLines > 0) {
          val progress = Math.min(1.0, currentOffset.toDouble / totalLines.toDouble)
          SourceMetricsReporter.updateFileReadProgress(pipelineId, progress)
        }
      }
    }.leftMap {
      ex =>
        log.warn(s"Failed to update read progress: ${ex.getMessage}")
    }
    ()
  }

  /**
   * Stop reading file.
   */
  override def stop(): Future[Done] = {
    val state = getCurrentState

    if (!state.isRunning) {
      log.warn(s"{} not running: {}", getClass.getSimpleName, sourceId)
      return Future.successful(Done)
    }

    log.info(s"Stopping {}: {}", getClass.getSimpleName, sourceId)

    // Shutdown kill switch
    state.killSwitch.foreach(_.shutdown())

    // Update state
    updateState(_.withRunState(FileSourceRunState.Stopped).clearKillSwitch())

    // Update health metrics
    SourceMetricsReporter.updateHealth(pipelineId, "file", isHealthy = false)

    Future.successful(Done)
  }

  /**
   * Get current offset (line number).
   */
  override def currentOffset(): Long = getCurrentState.currentLineNumber

  /**
   * Resume from a specific offset.
   */
  override def resumeFrom(offset: Long): Unit = {
    log.info(s"Resuming {} from offset: {}", getClass.getSimpleName, offset)
    updateState {
      state =>
        state.copy(
          resumeFromLineNumber = offset,
          currentLineNumber = offset,
        )
    }
  }

  /**
   * Check if source is healthy.
   */
  override def isHealthy: Boolean = {
    val state        = getCurrentState
    val fileExists   = Files.exists(filePath)
    val fileReadable = fileExists && Files.isReadable(filePath)

    fileReadable && state.isRunning && state.lastError.isEmpty
  }

  /**
   * Get current source state.
   */
  override def state: SourceState = {
    val currentState = getCurrentState

    currentState.runState match {
      case FileSourceRunState.Running => SourceState.Running
      case FileSourceRunState.Failed  => SourceState.Failed
      case FileSourceRunState.Stopped => SourceState.Stopped
      case FileSourceRunState.Idle    => SourceState.Stopped
    }
  }

  /**
   * Get last error if any.
   */
  def lastError: Option[FileSourceError] = getCurrentState.lastError
}

// ============================================================================
// Companion Object
// ============================================================================

object FileSourceBase {

  /**
   * Helper to create FileSourceConfig from SourceConfig.
   */
  def createConfig(sourceConfig: SourceConfig): Either[FileSourceError, FileSourceConfig] =
    FileSourceConfig.create(sourceConfig)

  /**
   * Validate file accessibility.
   */
  def validateFile(path: Path): Either[FileSourceError, Path] = {
    if (!Files.exists(path)) {
      Left(FileSourceError.FileNotFound(path.toString))
    } else if (!Files.isReadable(path)) {
      Left(FileSourceError.FileNotReadable(path.toString))
    } else {
      Right(path)
    }
  }
}
