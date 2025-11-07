package com.dataflow.sinks.domain

/**
 * Batching configuration.
 */
case class BatchConfig(
  maxSize: Int = 100,
  maxDuration: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(5).seconds)

object BatchConfig {
  val default: BatchConfig = BatchConfig()
}
