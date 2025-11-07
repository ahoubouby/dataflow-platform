package com.dataflow.sinks.file

import java.io.{BufferedOutputStream, FileOutputStream}
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import scala.concurrent.{ExecutionContext, Future}
import cats.implicits._
import cats.syntax.either._
import cats.syntax.option._
import com.dataflow.domain.models.DataRecord
import com.dataflow.sinks.domain._
import com.dataflow.sinks.domain.exceptions._
import com.dataflow.sinks.file.FileSinkConfig.validateConfig
import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.{Flow, Sink}
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory

/**
 * Production-ready File sink with functional programming approach.
 *
 * Features:
 * - Multiple formats (JSON, JSONL, CSV)
 * - File rotation by size
 * - GZIP compression
 * - Atomic writes
 * - Proper resource cleanup
 * - Functional error handling with Cats
 * - Thread-safe metrics
 *
 * @param config File sink configuration
 */
class FileSink(config: FileSinkConfig)(implicit ec: ExecutionContext) extends DataSink {

  private val logger = LoggerFactory.getLogger(getClass)

  override def sinkType: SinkType = SinkType.File

  // Thread-safe state management
  private val metricsRef     = new AtomicReference[SinkMetrics](SinkMetrics.empty)
  private val fileIndexRef   = new AtomicReference[Int](0)
  private val recordCountRef = new AtomicReference[Long](0L)

  // Validate configuration at construction
  validateConfig(config).leftMap {
    error =>
      logger.error(s"Invalid file sink configuration: ${error.message}")
      throw new FileSinkException(error)
  }

  override def sink: Sink[DataRecord, Future[Done]] = {
    Flow[DataRecord]
      .map(recordToByteString)
      .via(rotationFlow)
      .toMat(Sink.ignore)(org.apache.pekko.stream.scaladsl.Keep.right)
  }

  /**
   * Convert record to ByteString based on configured format.
   */
  private def recordToByteString(record: DataRecord): ByteString = {
    encodeRecord(record, config.format).fold(
      error => {
        logger.error(s"Failed to encode record ${record.id}: ${error.message}")
        updateMetrics(_.incrementFailed())
        ByteString.empty
      },
      byteString => byteString,
    )
  }

  /**
   * Encode record to ByteString with functional error handling.
   */
  private def encodeRecord(record: DataRecord, format: FileFormat): Either[SinkError, ByteString] = {
    format match {
      case FileFormat.JSONL =>
        encodeToJson(record).map(json => ByteString(json + "\n"))

      case FileFormat.JSON =>
        encodeToJsonPretty(record).map(json => ByteString(json + ",\n"))

      case FileFormat.CSV =>
        encodeToCsv(record).map(ByteString(_))
    }
  }

  /**
   * Encode record to compact JSON.
   */
  private def encodeToJson(record: DataRecord): Either[SinkError, String] = {
    Either.catchNonFatal {
      Json.obj(
        "id"       -> record.id.asJson,
        "data"     -> record.data.asJson,
        "metadata" -> record.metadata.asJson,
      ).noSpaces
    }.leftMap(EncodingError)
  }

  /**
   * Encode record to pretty JSON.
   */
  private def encodeToJsonPretty(record: DataRecord): Either[SinkError, String] = {
    Either.catchNonFatal {
      Json.obj(
        "id"       -> record.id.asJson,
        "data"     -> record.data.asJson,
        "metadata" -> record.metadata.asJson,
      ).spaces2
    }.leftMap(EncodingError)
  }

  /**
   * Encode record to CSV format.
   */
  private def encodeToCsv(record: DataRecord): Either[SinkError, String] = {
    Either.catchNonFatal {
      val currentCount = recordCountRef.get()
      if (currentCount == 0) {
        // Include header for first record
        val header = record.data.keys.mkString(",")
        val data   = record.data.values.mkString(",")
        s"$header\n$data\n"
      } else {
        record.data.values.mkString(",") + "\n"
      }
    }.leftMap(EncodingError)
  }

  /**
   * Flow that handles file rotation.
   */
  private def rotationFlow: Flow[ByteString, ByteString, org.apache.pekko.NotUsed] = {
    Flow[ByteString]
      .statefulMapConcat {
        () =>
          var currentSize = 0L
          var currentPath = nextFilePath()

          (byteString: ByteString) => {
            // Check if rotation is needed
            if (shouldRotate(currentSize)) {
              logger.info(s"Rotating file at $currentSize bytes, creating new file")
              currentSize = 0L
              currentPath = nextFilePath()
              recordCountRef.set(0L)
            }

            // Write to file
            writeToFile(currentPath, byteString).fold(
              error => {
                logger.error(s"Failed to write to file: ${error.message}")
                updateMetrics(_.incrementFailed())
              },
              _ => {
                currentSize += byteString.size
                recordCountRef.incre(_.+(1))
                updateMetrics(_.incrementWritten())
              },
            )

            List(byteString)
          }
      }
  }

  /**
   * Determine if file rotation is needed.
   */
  private def shouldRotate(currentSize: Long): Boolean =
    config.rotationSize.exists(maxSize => currentSize >= maxSize)

  /**
   * Generate next file path with rotation index.
   */
  private def nextFilePath(): Path = {
    val index    = fileIndexRef.getAndIncrement()
    val basePath = Paths.get(config.path)
    val fileName = basePath.getFileName.toString
    val parent   = Option(basePath.getParent)

    val extension = config.compression match {
      case Some("gzip") | Some("gz") => s".$index.gz"
      case _                         => s".$index"
    }

    val rotatedFileName = fileName + extension
    parent.fold(Paths.get(rotatedFileName))(_.resolve(rotatedFileName))
  }

  /**
   * Write bytes to file with functional error handling.
   */
  private def writeToFile(path: Path, bytes: ByteString): Either[SinkError, Unit] = {
    for {
      _      <- ensureParentDirectory(path)
      result <- performWrite(path, bytes)
    } yield result
  }

  /**
   * Ensure parent directory exists.
   */
  private def ensureParentDirectory(path: Path): Either[SinkError, Unit] = {
    Either.catchNonFatal {
      Option(path.getParent).foreach(Files.createDirectories(_))
    }.leftMap(FileOperationError)
  }

  /**
   * Perform the actual write operation with optional compression.
   */
  private def performWrite(path: Path, bytes: ByteString): Either[SinkError, Unit] = {
    config.compression match {
      case Some("gzip") | Some("gz") =>
        writeCompressed(path, bytes)
      case _                         =>
        writeUncompressed(path, bytes)
    }
  }

  /**
   * Write compressed data using GZIP.
   */
  private def writeCompressed(path: Path, bytes: ByteString): Either[SinkError, Unit] = {
    Either.catchNonFatal {
      val fos  = new FileOutputStream(path.toFile, true)
      val bos  = new BufferedOutputStream(fos)
      val gzos = new GZIPOutputStream(bos)
      try {
        gzos.write(bytes.toArray)
        gzos.flush()
        logger.debug(s"Wrote ${bytes.size} compressed bytes to $path")
      } finally {
        gzos.close()
        bos.close()
        fos.close()
      }
    }.leftMap(CompressionError)
  }

  /**
   * Write uncompressed data.
   */
  private def writeUncompressed(path: Path, bytes: ByteString): Either[SinkError, Unit] = {
    Either.catchNonFatal {
      Files.write(
        path,
        bytes.toArray,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
      )
      logger.debug(s"Wrote ${bytes.size} bytes to $path")
    }.leftMap(FileOperationError)
  }

  /**
   * Update metrics atomically.
   */
  private def updateMetrics(f: SinkMetrics => SinkMetrics): Unit = {
    metricsRef.updateAndGet(current => f(current))
    ()
  }

  override def healthCheck(): Future[HealthStatus] = {
    performHealthCheck().value.map {
      case Right(status) => status
      case Left(error)   => HealthStatus.Unhealthy(error.message)
    }
  }

  private def performHealthCheck(): cats.data.EitherT[Future, SinkError, HealthStatus] = {
    cats.data.EitherT {
      Future {
        Either.catchNonFatal {
          val path   = Paths.get(config.path)
          val parent = Option(path.getParent).getOrElse(Paths.get("."))

          if (Files.isWritable(parent)) {
            HealthStatus.Healthy: HealthStatus
          } else {
            HealthStatus.Unhealthy(s"Directory not writable: $parent"): HealthStatus
          }
        }.leftMap(FileOperationError)
      }
    }
  }

  override def close(): Future[Done] = {
    val totalFiles = fileIndexRef.get()
    logger.info(s"Closing FileSink. Total files written: $totalFiles")
    Future.successful(Done)
  }

  override def metrics: SinkMetrics = metricsRef.get()
}

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

  /**
   * Parse format from string.
   */
  def fromString(s: String): Either[SinkError, FileFormat] = {
    s.toLowerCase match {
      case "jsonl" | "ndjson" => Right(JSONL)
      case "json"             => Right(JSON)
      case "csv"              => Right(CSV)
      case other              => Left(ConfigurationError(s"Unknown format: $other"))
    }
  }
}
