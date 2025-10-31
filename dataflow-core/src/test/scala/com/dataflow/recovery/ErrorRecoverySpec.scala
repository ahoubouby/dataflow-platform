package com.dataflow.recovery

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ErrorRecoverySpec extends AnyWordSpec with Matchers {

//  "ErrorRecovery.calculateExponentialBackoff" should {
//
//    "return correct backoff for retry count 0" in {
//      val backoff = ErrorRecovery.calculateExponentialBackoff(0)
//      backoff should be >= 800L // 1000ms with jitter (±20%)
//      backoff should be <= 1200L
//    }
//
//    "return exponentially increasing backoff" in {
//      val backoff0 = ErrorRecovery.calculateExponentialBackoff(0)
//      val backoff1 = ErrorRecovery.calculateExponentialBackoff(1)
//      val backoff2 = ErrorRecovery.calculateExponentialBackoff(2)
//
//      // Each backoff should be roughly double the previous (with jitter)
//      backoff1 should be > backoff0
//      backoff2 should be > backoff1
//    }
//
//    "cap backoff at maxBackoff" in {
//      val config = ErrorRecovery.RetryConfig(maxBackoff = 10.seconds)
//      val backoff = ErrorRecovery.calculateExponentialBackoff(10, config)
//      backoff should be <= 10000L + (10000L * 0.2) // Max + jitter
//    }
//
//    "use custom initial backoff" in {
//      val config = ErrorRecovery.RetryConfig(initialBackoff = 500.millis)
//      val backoff = ErrorRecovery.calculateExponentialBackoff(0, config)
//
//      backoff should be >= 400L // 500ms with jitter (±20%)
//      backoff should be <= 600L
//    }
//
//    "handle large retry counts" in {
//      val backoff = ErrorRecovery.calculateExponentialBackoff(10)
//      backoff should be <= 3000L + (3000L * 0.2) // Should be capped at maxBackoff
//    }
//
//    "throw exception for negative retry count" in {
//      assertThrows[IllegalArgumentException] {
//        ErrorRecovery.calculateExponentialBackoff(-1)
//      }
//    }
//
//    "apply random jitter" in {
//      // Multiple calculations should produce different results due to jitter
//      val backoffs = (1 to 10).map(_ => ErrorRecovery.calculateExponentialBackoff(2))
//      backoffs.distinct.size should be > 1
//    }
//  }

  "ErrorRecovery.shouldRetry" should {

    "return true for transient errors with retries left" in {
      ErrorRecovery.shouldRetry("TIMEOUT", 0, maxRetries = 5) shouldBe true
      ErrorRecovery.shouldRetry("CONNECTION_FAILED", 2, maxRetries = 5) shouldBe true
      ErrorRecovery.shouldRetry("RATE_LIMITED", 4, maxRetries = 5) shouldBe true
    }

    "return false when max retries reached" in {
      ErrorRecovery.shouldRetry("TIMEOUT", 5, maxRetries = 5) shouldBe false
      ErrorRecovery.shouldRetry("CONNECTION_FAILED", 6, maxRetries = 5) shouldBe false
    }

    "return false for non-transient errors" in {
      ErrorRecovery.shouldRetry("VALIDATION_ERROR", 0, maxRetries = 5) shouldBe false
      ErrorRecovery.shouldRetry("AUTH_FAILED", 0, maxRetries = 5) shouldBe false
      ErrorRecovery.shouldRetry("UNKNOWN_ERROR", 0, maxRetries = 5) shouldBe false
    }

    "use default max retries when not specified" in {
      ErrorRecovery.shouldRetry("TIMEOUT", 0) shouldBe true
      ErrorRecovery.shouldRetry("TIMEOUT", 5) shouldBe false
    }
  }

  "ErrorRecovery.isTransientError" should {

    "identify transient errors correctly" in {
      val transientErrors = Seq(
        "TIMEOUT",
        "CONNECTION_FAILED",
        "SERVICE_UNAVAILABLE",
        "RATE_LIMITED",
        "TEMPORARY_FAILURE",
        "NETWORK_ERROR",
        "TOO_MANY_REQUESTS",
        "DEADLOCK",
        "RESOURCE_EXHAUSTED"
      )

      transientErrors.foreach { errorCode =>
        ErrorRecovery.isTransientError(errorCode) shouldBe true
      }
    }

    "identify non-transient errors correctly" in {
      val nonTransientErrors = Seq(
        "VALIDATION_ERROR",
        "AUTH_FAILED",
        "AUTHORIZATION_ERROR",
        "DATA_FORMAT_ERROR",
        "NOT_FOUND",
        "UNKNOWN_ERROR"
      )

      nonTransientErrors.foreach { errorCode =>
        ErrorRecovery.isTransientError(errorCode) shouldBe false
      }
    }

    "be case insensitive" in {
      ErrorRecovery.isTransientError("timeout") shouldBe true
      ErrorRecovery.isTransientError("TimeOut") shouldBe true
      ErrorRecovery.isTransientError("TIMEOUT") shouldBe true
    }
  }

  "ErrorRecovery.resetRetryCount" should {

    "return 0" in {
      ErrorRecovery.resetRetryCount() shouldBe 0
    }
  }

  "ErrorRecovery.incrementRetryCount" should {

    "increment count by 1" in {
      ErrorRecovery.incrementRetryCount(0) shouldBe 1
      ErrorRecovery.incrementRetryCount(5) shouldBe 6
      ErrorRecovery.incrementRetryCount(100) shouldBe 101
    }
  }

  "ErrorRecovery.retryDescription" should {

    "format description correctly" in {
      val description = ErrorRecovery.retryDescription(1, 2000)
      description should include("Retry attempt 1")
      description should include("2.0s")
    }

    "handle milliseconds correctly" in {
      val description = ErrorRecovery.retryDescription(3, 5500)
      description should include("Retry attempt 3")
      description should include("5.5s")
    }
  }

  "RetryConfig" should {

    "have reasonable defaults" in {
      val config = ErrorRecovery.RetryConfig()

      config.maxRetries shouldBe 5
      config.initialBackoff shouldBe 1.second
      config.maxBackoff shouldBe 30.seconds
      config.randomFactor shouldBe 0.2
    }

    "allow custom configuration" in {
      val config = ErrorRecovery.RetryConfig(
        maxRetries = 10,
        initialBackoff = 500.millis,
        maxBackoff = 1.minute,
        randomFactor = 0.1
      )

      config.maxRetries shouldBe 10
      config.initialBackoff shouldBe 500.millis
      config.maxBackoff shouldBe 1.minute
      config.randomFactor shouldBe 0.1
    }
  }

  "TimeoutConfig" should {

    "have reasonable defaults" in {
      val config = TimeoutConfig.Default

      config.batchTimeout shouldBe 5.minutes
      config.operationTimeout shouldBe 30.seconds
    }

    "allow custom configuration" in {
      val config = TimeoutConfig(
        batchTimeout = 10.minutes,
        operationTimeout = 1.minute
      )

      config.batchTimeout shouldBe 10.minutes
      config.operationTimeout shouldBe 1.minute
    }
  }
}
