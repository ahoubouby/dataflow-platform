package com.dataflow.sources.models

// ============================================================================
// Error ADT
// ============================================================================

sealed trait FileSourceError extends Product with Serializable {
  def message: String
}

object FileSourceError {

  final case class FileNotFound(path: String) extends FileSourceError {
    override def message: String = s"File not found: $path"
  }

  final case class FileNotReadable(path: String) extends FileSourceError {
    override def message: String = s"File not readable: $path"
  }

  final case class InvalidConfiguration(reason: String) extends FileSourceError {
    override def message: String = s"Invalid configuration: $reason"
  }

  final case class ParseError(lineNumber: Long, cause: Throwable) extends FileSourceError {
    override def message: String = s"Parse error at line $lineNumber: ${cause.getMessage}"
  }

  final case class EncodingError(encodingName: String, cause: Throwable) extends FileSourceError {
    override def message: String = s"Invalid encoding '$encodingName': ${cause.getMessage}"
  }

  final case class StreamFailure(cause: Throwable) extends FileSourceError {
    override def message: String = s"Stream failure: ${cause.getMessage}"
  }

  final case class AlreadyRunning(sourceId: String) extends FileSourceError {
    override def message: String = s"Source $sourceId is already running"
  }

  final case class NotRunning(sourceId: String) extends FileSourceError {
    override def message: String = s"Source $sourceId is not running"
  }
}
