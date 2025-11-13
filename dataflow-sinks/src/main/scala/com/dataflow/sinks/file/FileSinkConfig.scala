package com.dataflow.sinks.file

import com.dataflow.sinks.domain.BatchConfig
import com.dataflow.sinks.domain.exceptions.{ConfigurationError, SinkError}

/**
 * File sink configuration with validation.
 */
case class FileSinkConfig(
  path: String,
  format: FileFormat = FileFormat.JSONL,
  compression: Option[String] = None,
  rotationSize: Option[Long] = Some(100 * 1024 * 1024), // 100MB default
  batchConfig: BatchConfig = BatchConfig.default)

object FileSinkConfig {

  /**
   * Create config with validation.
   */
  def create(
    path: String,
    format: FileFormat = FileFormat.JSONL,
    compression: Option[String] = None,
    rotationSize: Option[Long] = Some(100 * 1024 * 1024),
    batchConfig: BatchConfig = BatchConfig.default,
  ): Either[SinkError, FileSinkConfig] = {
    for {
      _ <- Either.cond(path.nonEmpty, (), ConfigurationError("Path cannot be empty"))
      _ <- validateCompression(compression)
      _ <- validateRotationSize(rotationSize)
    } yield FileSinkConfig(path, format, compression, rotationSize, batchConfig)
  }

  private def validateCompression(compression: Option[String]): Either[SinkError, Unit] = {
    compression match {
      case None                      => Right(())
      case Some("gzip") | Some("gz") => Right(())
      case Some(other)               => Left(ConfigurationError(s"Unsupported compression: $other"))
    }
  }

  private def validateRotationSize(size: Option[Long]): Either[SinkError, Unit] = {
    size match {
      case None             => Right(())
      case Some(s) if s > 0 => Right(())
      case Some(s)          => Left(ConfigurationError(s"Rotation size must be positive: $s"))
    }
  }

  /**
   * Validate file sink configuration.
   */
  def validateConfig(config: FileSinkConfig): Either[SinkError, Unit] = {
    for {
      _ <- Either.cond(
             config.path.nonEmpty,
             (),
             ConfigurationError("File path cannot be empty"),
           )
      _ <- Either.cond(
             config.rotationSize.forall(_ > 0),
             (),
             ConfigurationError("Rotation size must be positive"),
           )
    } yield ()
  }
}
