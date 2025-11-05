package com.dataflow.recovery

import scala.concurrent.duration._

/**
 * Error recovery utilities for implementing retry logic with exponential backoff.
 */
object ErrorRecovery {

  /**
   * Configuration for retry behavior.
   */
  case class RetryConfig(
    maxRetries: Int = 5,
    initialBackoff: FiniteDuration = 1.second,
    maxBackoff: FiniteDuration = 30.seconds,
    randomFactor: Double = 0.2)

  /**
   * Default retry configuration.
   */
  val DefaultRetryConfig: RetryConfig = RetryConfig()

  /**
   * Calculates exponential backoff duration based on retry count.
   *
   * Formula: min(maxBackoff, initialBackoff * 2^retryCount) with random jitter
   *
   * @param retryCount The current retry attempt (0-based)
   * @param config Retry configuration
   * @return The backoff duration in milliseconds
   */
  def calculateExponentialBackoff(
    retryCount: Int,
    config: RetryConfig = DefaultRetryConfig,
  ): Long = {
    require(retryCount >= 0, "Retry count must be non-negative")

    val exponentialBackoff = config.initialBackoff * Math.pow(2, retryCount)
    val cappedBackoff      = Math.min(exponentialBackoff.toMillis, config.maxBackoff.toMillis)

    // Add random jitter to prevent thundering herd
    val jitter            = cappedBackoff * config.randomFactor * (Math.random() * 2 - 1)
    val backoffWithJitter = cappedBackoff + jitter

    Math.max(0, backoffWithJitter.toLong)
  }

  /**
   * Determines if an error is retryable based on error characteristics.
   *
   * @param errorCode The error code
   * @param retryCount Current retry count
   * @param maxRetries Maximum allowed retries
   * @return True if the error should be retried
   */
  def shouldRetry(
    errorCode: String,
    retryCount: Int,
    maxRetries: Int = DefaultRetryConfig.maxRetries,
  ): Boolean = {
    val isTransient    = isTransientError(errorCode)
    val hasRetriesLeft = retryCount < maxRetries

    isTransient && hasRetriesLeft
  }

  /**
   * Checks if an error code represents a transient (retryable) error.
   *
   * Transient errors include:
   * - Network timeouts
   * - Temporary unavailability
   * - Rate limiting
   * - Connection issues
   *
   * Non-transient errors include:
   * - Validation errors
   * - Authentication failures
   * - Authorization errors
   * - Data format errors
   */
  def isTransientError(errorCode: String): Boolean = {
    val transientCodes = Set(
      "TIMEOUT",
      "CONNECTION_FAILED",
      "SERVICE_UNAVAILABLE",
      "RATE_LIMITED",
      "TEMPORARY_FAILURE",
      "NETWORK_ERROR",
      "TOO_MANY_REQUESTS",
      "DEADLOCK",
      "RESOURCE_EXHAUSTED",
    )

    transientCodes.contains(errorCode.toUpperCase)
  }

  /**
   * Resets retry count after successful operation.
   * Returns 0 for use in state updates.
   */
  def resetRetryCount(): Int = 0

  /**
   * Increments retry count for next attempt.
   */
  def incrementRetryCount(currentCount: Int): Int = currentCount + 1

  /**
   * Creates a human-readable description of the retry attempt.
   */
  def retryDescription(retryCount: Int, backoffMs: Long): String = {
    val backoffSeconds = backoffMs / 1000.0
    s"Retry attempt $retryCount scheduled in ${backoffSeconds}s"
  }
}

/**
 * Configuration for timeout handling.
 */
case class TimeoutConfig(
  batchTimeout: FiniteDuration = 5.minutes,
  operationTimeout: FiniteDuration = 30.seconds)

object TimeoutConfig {
  val Default: TimeoutConfig = TimeoutConfig()
}
