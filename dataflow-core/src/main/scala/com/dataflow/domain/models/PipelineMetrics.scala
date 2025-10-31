package com.dataflow.domain.models

import java.time.Instant

import com.dataflow.serialization.CborSerializable

/**
 * Metrics track pipeline performance.
 */
final case class PipelineMetrics(
  totalRecordsProcessed: Long,
  totalRecordsFailed: Long,
  totalBatchesProcessed: Long,
  averageProcessingTimeMs: Double,
  lastProcessedAt: Option[Instant],
  throughputPerSecond: Double) extends CborSerializable {

  /**
   * Update metrics after processing a batch.
   */
  def incrementBatch(
    successCount: Int,
    failureCount: Int,
    processingTimeMs: Long,
  ): PipelineMetrics = {
    val newTotalBatches = totalBatchesProcessed + 1
    val newAvgTime      = (averageProcessingTimeMs * totalBatchesProcessed + processingTimeMs) / newTotalBatches
    val newTotalRecords = totalRecordsProcessed + successCount

    // Calculate throughput (records per second)
    val throughput = if (processingTimeMs > 0) {
      (successCount.toDouble / processingTimeMs) * 1000
    } else averageProcessingTimeMs

    copy(
      totalRecordsProcessed = newTotalRecords,
      totalRecordsFailed = totalRecordsFailed + failureCount,
      totalBatchesProcessed = newTotalBatches,
      averageProcessingTimeMs = newAvgTime,
      lastProcessedAt = Some(Instant.now()),
      throughputPerSecond = throughput,
    )
  }
}

object PipelineMetrics {
  val empty: PipelineMetrics = PipelineMetrics(0, 0, 0, 0.0, None, 0.0)
}
