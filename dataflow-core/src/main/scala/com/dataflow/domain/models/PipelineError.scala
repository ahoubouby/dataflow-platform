package com.dataflow.domain.models

import com.dataflow.serialization.CborSerializable

/**
 * Pipeline error details.
 */
final case class PipelineError(
  errorType: String, // "source", "transform", "sink", "system"
  message: String,
  stackTrace: Option[String],
  retryable: Boolean) extends CborSerializable
