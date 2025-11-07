package com.dataflow.sinks.domain.exceptions

/**
 * Exception for file sink failures.
 */
class FileSinkException(error: SinkError) extends RuntimeException(error.message)
