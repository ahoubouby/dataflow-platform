package com.dataflow.sinks.domain

/**
 * Configuration for retry behavior.
 */
case class RetryConfig(
  maxAttempts: Int = 3,
  initialDelay: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(1).second,
  maxDelay: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(30).seconds,
  backoffFactor: Double = 2.0)

object RetryConfig {
  val default: RetryConfig = RetryConfig()
}
