package com.dataflow.sinks.domain.exceptions

/**
 * File sink errors as ADT.
 */
sealed trait SinkError {
  def message: String
}

case class ConfigurationError(msg: String) extends SinkError {
  override def message: String = s"Configuration error: $msg"
}

case class EncodingError(cause: Throwable) extends SinkError {
  override def message: String = s"Encoding error: ${cause.getMessage}"
}

case class FileOperationError(cause: Throwable) extends SinkError {
  override def message: String = s"File operation error: ${cause.getMessage}"
}

case class CompressionError(cause: Throwable) extends SinkError {
  override def message: String = s"Compression error: ${cause.getMessage}"
}

case class ConsoleSinkError(cause: Throwable) extends SinkError {
  override def message: String = s"Console Sink Error error: ${cause.getMessage}"
}

case class FormattingError(cause : Throwable) extends SinkError {
  override def message: String = s"Console Sink Error error: ${cause.getMessage}"
}

final case class OutputError(cause: Throwable) extends SinkError {
  override def message: String = s"Failed to write to console: ${cause.getMessage}"
}