package com.dataflow.sinks.domain

import cats.implicits.catsSyntaxOptionId

/**
 * Sink metrics for observability.
 */
case class SinkMetrics(
  recordsWritten: Long = 0,
  recordsFailed: Long = 0,
  batchesWritten: Long = 0,
  retriesAttempted: Long = 0,
  lastWriteTimestamp: Option[Long] = None,
  startTime: Option[Long] = None)

object SinkMetrics {
  val empty: SinkMetrics = SinkMetrics()
  implicit class SinkMetricsOps(metrics: SinkMetrics) {

    def incrementWritten(): SinkMetrics = metrics.copy(
      recordsWritten = metrics.recordsWritten + 1,
      lastWriteTimestamp = System.currentTimeMillis().some,
    )

    def incrementFailed(): SinkMetrics = metrics.copy(
      recordsFailed = metrics.recordsFailed + 1,
    )

    def incrementBatches(): SinkMetrics = metrics.copy(
      batchesWritten = metrics.batchesWritten + 1,
    )
  }
}
