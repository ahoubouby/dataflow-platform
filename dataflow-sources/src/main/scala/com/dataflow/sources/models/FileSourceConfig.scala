package com.dataflow.sources.models

import java.io.FileNotFoundException
import java.nio.charset.Charset
import java.nio.file.{Files, Path, Paths}

import com.dataflow.domain.models.SourceConfig

final case class FileSourceConfig private (
  filePath: Path,
  encoding: Charset,
  batchSize: Int,
  maximumFrameLength: Int,
  connectionString: String,
  options: Map[String, String]) {

  def withBatchSize(size: Int): Either[FileSourceError, FileSourceConfig] =
    if (size > 0 && size <= 10000) Right(copy(batchSize = size))
    else Left(FileSourceError.InvalidConfiguration(s"batchSize must be 1-10000, got: $size"))

  def withMaxFrameLength(length: Int): Either[FileSourceError, FileSourceConfig] =
    if (length > 0 && length <= 1024 * 1024) Right(copy(maximumFrameLength = length))
    else Left(FileSourceError.InvalidConfiguration(s"maximumFrameLength must be 1-1MB, got: $length"))
}

object FileSourceConfig {
  private val DefaultBatchSize      = 100
  private val DefaultMaxFrameLength = 8192

  def create(sourceConfig: SourceConfig): Either[FileSourceError, FileSourceConfig] = {
    for {
      path           <- validatePath(sourceConfig.connectionString)
      encoding       <- validateEncoding(sourceConfig.options.getOrElse("encoding", "UTF-8"))
      batchSize      <- validateBatchSize(sourceConfig.batchSize)
      maxFrameLength <- validateMaxFrameLength(
                          sourceConfig.options.get("maxFrameLength").flatMap(s => scala.util.Try(s.toInt).toOption)
                            .getOrElse(DefaultMaxFrameLength),
                        )
    } yield FileSourceConfig(
      filePath = path,
      encoding = encoding,
      batchSize = batchSize,
      maximumFrameLength = maxFrameLength,
      connectionString = sourceConfig.connectionString,
      options = sourceConfig.options,
    )
  }

  private def validatePath(pathStr: String): Either[FileSourceError, Path] = {
    import cats.syntax.either._
    Either.catchNonFatal {
      val path = Paths.get(pathStr)
      if (!Files.exists(path)) {
        throw new FileNotFoundException(pathStr)
      }
      if (!Files.isReadable(path)) {
        throw new SecurityException(s"File not readable: $pathStr")
      }
      path
    }.leftMap {
      case _: FileNotFoundException => FileSourceError.FileNotFound(pathStr)
      case _: SecurityException     => FileSourceError.FileNotReadable(pathStr)
      case ex                       => FileSourceError.InvalidConfiguration(ex.getMessage)
    }
  }

  private def validateEncoding(encodingName: String): Either[FileSourceError, Charset] = {
    import cats.syntax.either._
    Either.catchNonFatal {
      Charset.forName(encodingName)
    }.leftMap(ex => FileSourceError.EncodingError(encodingName, ex))
  }

  private def validateBatchSize(size: Int): Either[FileSourceError, Int] =
    if (size > 0 && size <= 10000) Right(size)
    else Left(FileSourceError.InvalidConfiguration(s"batchSize must be 1-10000, got: $size"))

  private def validateMaxFrameLength(length: Int): Either[FileSourceError, Int] =
    if (length > 0 && length <= 1024 * 1024) Right(length)
    else Left(FileSourceError.InvalidConfiguration(s"maximumFrameLength must be 1-1MB, got: $length"))
}
