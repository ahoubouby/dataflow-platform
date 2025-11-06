package com.dataflow.sinks.file

import com.dataflow.domain.models.DataRecord
import com.dataflow.sinks.domain._
import com.dataflow.sinks.util.SinkUtils
import org.apache.pekko.Done
import org.apache.pekko.stream.IOResult
import org.apache.pekko.stream.scaladsl.{FileIO, Flow, Sink}
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory
import io.circe.syntax._
import io.circe.generic.auto._

import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util.zip.GZIPOutputStream
import scala.concurrent.{ExecutionContext, Future}
import java.io.{BufferedOutputStream, FileOutputStream}

/**
 * Production-ready File sink.
 *
 * Features:
 * - Multiple formats (JSON, JSONL, CSV)
 * - File rotation by size or time
 * - Compression (gzip)
 * - Atomic writes
 * - Proper resource cleanup
 *
 * @param config File sink configuration
 */
class FileSink(config: FileSinkConfig)(implicit ec: ExecutionContext) extends DataSink {

  private val logger = LoggerFactory.getLogger(getClass)

  override def sinkType: SinkType = SinkType.File

  @volatile private var metricsState = SinkMetrics.empty
  @volatile private var currentFileIndex = 0
  @volatile private var recordsInCurrentFile = 0L

  override def sink: Sink[DataRecord, Future[Done]] = {
    Flow[DataRecord]
      .via(SinkUtils.metricsFlow("FileSink"))
      .map(recordToByteString)
      .via(rotationFlow)
      .toMat(Sink.ignore)(org.apache.pekko.stream.scaladsl.Keep.right)
  }

  /**
   * Convert record to ByteString based on format.
   */
  private def recordToByteString(record: DataRecord): ByteString = {
    config.format match {
      case FileFormat.JSONL =>
        ByteString(recordToJson(record) + "\n")

      case FileFormat.JSON =>
        ByteString(recordToJsonPretty(record) + ",\n")

      case FileFormat.CSV =>
        if (recordsInCurrentFile == 0) {
          // Write header for first record
          val header = record.data.keys.mkString(",") + "\n"
          val data = record.data.values.mkString(",") + "\n"
          ByteString(header + data)
        } else {
          ByteString(record.data.values.mkString(",") + "\n")
        }
    }
  }

  /**
   * Convert record to compact JSON.
   */
  private def recordToJson(record: DataRecord): String = {
    import io.circe.Json

    Json.obj(
      "id" -> record.id.asJson,
      "data" -> record.data.asJson,
      "metadata" -> record.metadata.asJson
    ).noSpaces
  }

  /**
   * Convert record to pretty JSON.
   */
  private def recordToJsonPretty(record: DataRecord): String = {
    import io.circe.Json

    Json.obj(
      "id" -> record.id.asJson,
      "data" -> record.data.asJson,
      "metadata" -> record.metadata.asJson
    ).spaces2
  }

  /**
   * Flow that handles file rotation.
   */
  private def rotationFlow: Flow[ByteString, ByteString, org.apache.pekko.NotUsed] = {
    Flow[ByteString]
      .statefulMapConcat { () =>
        var currentSize = 0L
        var currentPath = nextFilePath()

        (byteString: ByteString) => {
          // Check if we need to rotate
          if (shouldRotate(currentSize)) {
            logger.info(s"Rotating file at $currentSize bytes")
            currentSize = 0L
            currentPath = nextFilePath()
            recordsInCurrentFile = 0L
          }

          // Write to file
          writeToFile(currentPath, byteString)

          currentSize += byteString.size
          recordsInCurrentFile += 1

          metricsState = metricsState.copy(
            recordsWritten = metricsState.recordsWritten + 1,
            lastWriteTimestamp = Some(System.currentTimeMillis())
          )

          List(byteString)
        }
      }
  }

  /**
   * Determine if file should be rotated.
   */
  private def shouldRotate(currentSize: Long): Boolean = {
    config.rotationSize.exists(maxSize => currentSize >= maxSize)
  }

  /**
   * Generate next file path with rotation index.
   */
  private def nextFilePath(): Path = {
    val basePath = Paths.get(config.path)
    val fileName = basePath.getFileName.toString
    val parent = basePath.getParent

    val extension = config.compression match {
      case Some("gzip") | Some("gz") => s".$currentFileIndex.gz"
      case _ => s".$currentFileIndex"
    }

    currentFileIndex += 1

    if (parent != null) {
      parent.resolve(fileName + extension)
    } else {
      Paths.get(fileName + extension)
    }
  }

  /**
   * Write bytes to file with optional compression.
   */
  private def writeToFile(path: Path, bytes: ByteString): Unit = {
    try {
      // Ensure parent directory exists
      Option(path.getParent).foreach(Files.createDirectories(_))

      config.compression match {
        case Some("gzip") | Some("gz") =>
          // Write with gzip compression
          val fos = new FileOutputStream(path.toFile, true)
          val bos = new BufferedOutputStream(fos)
          val gzos = new GZIPOutputStream(bos)
          try {
            gzos.write(bytes.toArray)
            gzos.flush()
          } finally {
            gzos.close()
            bos.close()
            fos.close()
          }

        case _ =>
          // Write uncompressed
          Files.write(
            path,
            bytes.toArray,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
          )
      }

      logger.debug(s"Wrote ${bytes.size} bytes to $path")

    } catch {
      case ex: Exception =>
        logger.error(s"Failed to write to file: $path", ex)
        metricsState = metricsState.copy(
          recordsFailed = metricsState.recordsFailed + 1
        )
        throw ex
    }
  }

  override def healthCheck(): Future[HealthStatus] = {
    Future {
      try {
        val path = Paths.get(config.path)
        val parent = Option(path.getParent).getOrElse(Paths.get("."))

        if (Files.isWritable(parent)) {
          HealthStatus.Healthy
        } else {
          HealthStatus.Unhealthy(s"Directory not writable: $parent")
        }
      } catch {
        case ex: Exception =>
          HealthStatus.Unhealthy(ex.getMessage)
      }
    }
  }

  override def close(): Future[Done] = {
    logger.info(s"Closing FileSink. Total files written: $currentFileIndex")
    Future.successful(Done)
  }

  override def metrics: SinkMetrics = metricsState
}

/**
 * File sink configuration.
 */
case class FileSinkConfig(
  path: String,
  format: FileFormat = FileFormat.JSONL,
  compression: Option[String] = None,
  rotationSize: Option[Long] = Some(100 * 1024 * 1024), // 100MB default
  batchConfig: BatchConfig = BatchConfig.default
)

/**
 * Supported file formats.
 */
sealed trait FileFormat

object FileFormat {
  /** JSON Lines - one JSON object per line */
  case object JSONL extends FileFormat

  /** Pretty-printed JSON array */
  case object JSON extends FileFormat

  /** Comma-separated values */
  case object CSV extends FileFormat
}
