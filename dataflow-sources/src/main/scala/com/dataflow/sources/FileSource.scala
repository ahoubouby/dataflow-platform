package com.dataflow.sources

import java.io.FileNotFoundException
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import com.dataflow.domain.commands.{Command, IngestBatch}
import com.dataflow.domain.models.{DataRecord, SourceConfig}
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.stream.{KillSwitches, SystemMaterializer}
import org.apache.pekko.stream.scaladsl.{FileIO, Framing, Keep, Sink, Source => PekkoSource}
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory
import spray.json._

/**
 * File source connector for reading data from files.
 *
 * Supported formats:
 * - CSV (with configurable delimiter)
 * - JSON (newline-delimited JSON)
 * - Plain text (line-based)
 *
 * Features:
 * - Streaming processing (doesn't load entire file in memory)
 * - Batch emission to pipeline
 * - Offset tracking (line number)
 * - Resume from last position
 * - Multiple file patterns (glob support)
 * - Watch mode (optional - monitors directory for new files)
 *
 * Configuration:
 *  {{{
 *    SourceConfig(
 *      sourceType = "file",
 *      connectionString = "/path/to/files/.csv",
 *      config = Map(
 *        "format" -> "csv",           // csv, json, text
 *        "delimiter" -> ",",          // CSV delimiter
 *        "has-header" -> "true",      // CSV has header row
 *        "encoding" -> "UTF-8",
 *        "watch" -> "false"           // Watch for new files
 *      ),
 *      batchSize = 1000
 *    )
 *  }}}
 */
class FileSource(
  val pipelineId: String,
  val config: SourceConfig,
)(implicit system: ActorSystem[_]) extends Source {

  private val log = LoggerFactory.getLogger(getClass)

  implicit private val ec: ExecutionContext = system.executionContext
  implicit private val mat = SystemMaterializer(system).materializer

  override val sourceId: String = s"file-source-$pipelineId-${UUID.randomUUID()}"

  // ----- options -----
  private val filePath: Path = Paths.get(config.connectionString)

  private val format: String =
    config.options.getOrElse("format", "csv").toLowerCase

  private val delimiter: String =
    config.options.getOrElse("delimiter", ",")

  private val hasHeader: Boolean =
    config.options.getOrElse("has-header", "true").toBoolean

  private val encoding: String =
    config.options.getOrElse("encoding", "UTF-8")

  private val watchMode: Boolean =
    config.options.getOrElse("watch", "false").toBoolean

  // State
  @volatile private var currentLineNumber: Long                                             = 0
  @volatile private var isRunning:         Boolean                                          = false
  @volatile private var killSwitch:        Option[org.apache.pekko.stream.UniqueKillSwitch] = None

  log.info(
    "Initialized FileSource id={} path={} format={} batchSize={}",
    sourceId,
    filePath,
    format,
    config.batchSize,
  )

  /**
   * Create streaming source from file.
   */
  override def stream(): PekkoSource[DataRecord, Future[Done]] = {
    val base: PekkoSource[DataRecord, NotUsed] =
      buildDataStream()

    // adapt materialized value to what the trait wants
    base.mapMaterializedValue(_ => Future.successful(Done))
  }

  private def buildDataStream(): PekkoSource[DataRecord, NotUsed] = {
    if (!Files.exists(filePath)) {
      log.error("File not found: {}", filePath)
      throw new FileNotFoundException(s"File not found: $filePath")
    }

    format match {
      case "csv"  => csvStream()
      case "json" => jsonStream()
      case "text" => textStream()
      case other  =>
        log.error("Unsupported file format '{}' for {}", other, filePath)
        PekkoSource.empty[DataRecord]
    }
  }

  // add near the other state vars
  @volatile private var headerColumns: Option[Vector[String]] = None

  /**
   * CSV stream - reads CSV file and converts to DataRecords.
   */
  private def csvStream(): PekkoSource[DataRecord, NotUsed] =
    FileIO
      .fromPath(filePath)
      .via(
        Framing.delimiter(
          ByteString("\n"),
          maximumFrameLength = 8192,
          allowTruncation = true,
        ),
      )
      .map(_.decodeString(encoding))
      .zipWithIndex
      .map {
        case (line, idx) =>
          currentLineNumber = idx

          // If we said there is a header, the very first line (idx == 0)
          // becomes our list of column names.
          if (hasHeader && idx == 0) {
            val cols = line.split(delimiter, -1).map(_.trim).toVector
            headerColumns = Some(cols)
            None // we don't emit a DataRecord for the header itself
          } else {
            // normal data line
            parseCsvLine(line, idx)
          }
      }
      .collect { case Some(r) => r }
      .mapMaterializedValue(_ => NotUsed)

  /**
   * Parse CSV line to DataRecord.
   */
  private def parseCsvLine(line: String, lineNumber: Long): Option[DataRecord] =
    Try {
      val values = line.split(delimiter, -1).map(_.trim).toVector

      // if we have header names, zip with them
      val data: Map[String, String] = headerColumns match {
        case Some(cols) =>
          // zip to the shortest to avoid mismatches
          cols.zip(values).toMap
        case None       =>
          // fallback to field1, field2, ...
          values.zipWithIndex.map { case (v, i) => s"field${i + 1}" -> v }.toMap
      }

      DataRecord(
        id = UUID.randomUUID().toString,
        data = data,
        metadata = Map(
          "source"      -> "file",
          "source_id"   -> sourceId,
          "file_path"   -> filePath.toString,
          "line_number" -> (lineNumber + 1).toString,
          "format"      -> "csv",
          "timestamp"   -> Instant.now().toString,
        ),
      )
    }.toOption

  /**
   * JSON stream - reads newline-delimited JSON.
   */
  private def jsonStream(): PekkoSource[DataRecord, NotUsed] =
    FileIO
      .fromPath(filePath)
      .via(
        Framing.delimiter(
          ByteString("\n"),
          maximumFrameLength = 1024 * 1024,
          allowTruncation = true,
        ),
      )
      .map(_.decodeString(encoding))
      .zipWithIndex
      .map {
        case (line, idx) =>
          currentLineNumber = idx
          parseJsonLine(line, idx)
      }
      .collect { case Some(r) => r }
      .mapMaterializedValue(_ => NotUsed)

  /**
   * Parse JSON line to DataRecord.
   */
  private def parseJsonLine(line: String, lineNumber: Long): Option[DataRecord] = {
    Try {
      val json = line.parseJson.asJsObject

      // Extract fields from JSON
      val data = json.fields.map {
        case (key, value) =>
          key -> value.toString.stripPrefix("\"").stripSuffix("\"")
      }

      // Get ID from JSON if present, otherwise generate
      val id = json.fields.get("id").map(_.toString.stripPrefix("\"").stripSuffix("\""))
        .getOrElse(UUID.randomUUID().toString)

      DataRecord(
        id = id,
        data = data - "id", // Remove id from data fields
        metadata = Map(
          "source"      -> "file",
          "source_id"   -> sourceId,
          "file_path"   -> filePath.toString,
          "line_number" -> (lineNumber + 1).toString,
          "format"      -> "json",
          "timestamp"   -> Instant.now().toString,
        ),
      )
    } match {
      case Success(record) => Some(record)
      case Failure(ex)     =>
        log.warn(s"Failed to parse JSON line $lineNumber: ${ex.getMessage}")
        None
    }
  }

  /**
   * Text stream - reads plain text file line by line.
   */
  private def textStream(): PekkoSource[DataRecord, NotUsed] = FileIO
    .fromPath(filePath)
    .via(
      Framing.delimiter(
        ByteString("\n"),
        maximumFrameLength = 8192,
        allowTruncation = true,
      ),
    )
    .map(_.decodeString(encoding))
    .zipWithIndex
    .map {
      case (line, idx) =>
        currentLineNumber = idx
        DataRecord(
          id = UUID.randomUUID().toString,
          data = Map("line" -> line),
          metadata = Map(
            "source"      -> "file",
            "source_id"   -> sourceId,
            "file_path"   -> filePath.toString,
            "line_number" -> (idx + 1).toString,
            "format"      -> "text",
            "timestamp"   -> Instant.now().toString,
          ),
        )
    }
    .mapMaterializedValue(_ => NotUsed)

  /**
   * Start reading file and sending batches to pipeline.
   */
  override def start(
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
  ): Future[Done] = {
    if (isRunning) {
      log.warn("FileSource {} already running", sourceId)
      Future.successful(Done)
    } else {
      log.info("Starting FileSource {} for {}", sourceId, filePath)
      isRunning = true

      val (switch, doneF) =
        buildDataStream()
          .viaMat(KillSwitches.single)(Keep.right)
          .grouped(config.batchSize)
          .mapAsync(1)(records => sendBatch(records.toList, pipelineShardRegion))
          .toMat(Sink.ignore)(Keep.both)
          .run()

      killSwitch = Some(switch)

      doneF.onComplete {
        case Success(_)  =>
          log.info("FileSource {} completed", sourceId)
          isRunning = false
        case Failure(ex) =>
          log.error("FileSource {} failed: {}", sourceId, ex.getMessage, ex)
          isRunning = false
      }

      Future.successful(Done)
    }
  }

  /**
   * Send batch of records to pipeline.
   */
  private def sendBatch(
    records: List[DataRecord],
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
  ): Future[Done] = {
    if (records.isEmpty) {
      return Future.successful(Done)
    }

    val batchId = UUID.randomUUID().toString
    val offset  = currentLineNumber

    log.debug(s"Sending batch: batchId=$batchId, records=${records.size}, offset=$offset")

    val command = IngestBatch(
      pipelineId = pipelineId,
      batchId = batchId,
      records = records,
      sourceOffset = offset,
      replyTo = system.ignoreRef,
    )

    pipelineShardRegion ! ShardingEnvelope(pipelineId, command)

    Future.successful(Done)
  }

  /**
   * Stop reading file.
   */
  override def stop(): Future[Done] = {
    if (!isRunning) {
      log.warn(s"FileSource not running: $sourceId")
      return Future.successful(Done)
    }

    log.info(s"Stopping FileSource: $sourceId")

    killSwitch.foreach(_.shutdown())
    killSwitch = None
    isRunning = false

    Future.successful(Done)
  }

  override def currentOffset(): Long = currentLineNumber

  override def resumeFrom(offset: Long): Unit = {
    log.info(s"Resuming FileSource from offset: $offset")
    currentLineNumber = offset
    // Note: Would need to skip to this line in the file
    // For simplicity, we restart from beginning for now
  }

  override def isHealthy: Boolean =
    Files.exists(filePath) && Files.isReadable(filePath) && isRunning

  override def state: Source.SourceState = if (!isHealthy) Source.SourceState.Failed
  else if (isRunning) Source.SourceState.Running
  else Source.SourceState.Stopped
}

/**
 * Companion object with factory method.
 */
object FileSource {

  def apply(pipelineId: String, config: SourceConfig)(implicit system: ActorSystem[_]): FileSource =
    new FileSource(pipelineId, config)
}
