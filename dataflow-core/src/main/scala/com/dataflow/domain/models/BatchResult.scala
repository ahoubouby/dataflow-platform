package com.dataflow.domain.models

import com.dataflow.serialization.CborSerializable

/**
 * Result of processing a batch.
 */
final case class BatchResult(
  batchId: String,
  successCount: Int,
  failureCount: Int,
  processingTimeMs: Long) extends CborSerializable
