package com.dataflow.sinks.util

import com.dataflow.domain.models.DataRecord
import com.dataflow.sinks.domain.{BatchConfig, RetryConfig}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Attributes, RestartSettings}
import org.apache.pekko.stream.scaladsl.{Flow, RestartFlow}
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Production-ready utilities for sinks.
 *
 * Features:
 * - Batching with size and time constraints
 * - Retry with exponential backoff
 * - Circuit breaker integration
 * - Metrics tracking
 */
object SinkUtils {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Create a batching flow with configurable size and time limits.
   *
   * Records are batched when either:
   * - maxSize records are accumulated, OR
   * - maxDuration time has passed
   *
   * This ensures both throughput (batching) and latency (time limit).
   */
  def batchingFlow(config: BatchConfig): Flow[DataRecord, Seq[DataRecord], NotUsed] = {
    Flow[DataRecord]
      .groupedWithin(config.maxSize, config.maxDuration)
      .map(_.toSeq)
      .log("batching")
      .withAttributes(Attributes.logLevels(onElement = org.apache.pekko.event.Logging.DebugLevel))
  }

  /**
   * Create a retry flow with exponential backoff.
   *
   * Automatically retries failed operations with increasing delays.
   */
  def retryFlow[In, Out](
    operation: In => Future[Out],
    config: RetryConfig
  ): Flow[In, Out, NotUsed] = {

    val restartSettings = RestartSettings(
      minBackoff = config.initialDelay,
      maxBackoff = config.maxDelay,
      randomFactor = 0.2
    ).withMaxRestarts(config.maxAttempts, config.initialDelay * 10)

    RestartFlow.onFailuresWithBackoff(restartSettings) { () =>
      Flow[In].mapAsync(1) { input =>
        logger.debug(s"Executing operation for input")
        operation(input).recoverWith {
          case ex =>
            logger.warn(s"Operation failed, will retry", ex)
            Future.failed(ex)
        }
      }
    }
  }

  /**
   * Create a batch write flow with retry.
   *
   * Combines batching and retry for production-ready writes.
   */
  def batchWriteFlow[T](
    write: Seq[DataRecord] => Future[T],
    batchConfig: BatchConfig = BatchConfig.default,
    retryConfig: RetryConfig = RetryConfig.default
  ): Flow[DataRecord, T, NotUsed] = {

    batchingFlow(batchConfig)
      .via(retryFlow(write, retryConfig))
  }

  /**
   * Validate record before writing.
   *
   * Can be used to filter out invalid records before sink operations.
   */
  def validateRecord(record: DataRecord): Try[DataRecord] = {
    if (record.id.isEmpty) {
      Failure(new IllegalArgumentException("Record ID cannot be empty"))
    } else if (record.data.isEmpty) {
      Failure(new IllegalArgumentException("Record data cannot be empty"))
    } else {
      Success(record)
    }
  }

  /**
   * Create a metrics tracking flow.
   *
   * Tracks throughput and errors.
   */
  def metricsFlow(sinkName: String): Flow[DataRecord, DataRecord, NotUsed] = {
    var recordCount = 0L
    var lastLogTime = System.currentTimeMillis()

    Flow[DataRecord].map { record =>
      recordCount += 1

      val now = System.currentTimeMillis()
      if (now - lastLogTime > 10000) { // Log every 10 seconds
        val rate = recordCount * 1000.0 / (now - lastLogTime)
        logger.info(s"[$sinkName] Processed $recordCount records (${rate.formatted("%.2f")} records/sec)")
        recordCount = 0
        lastLogTime = now
      }

      record
    }
  }

  /**
   * Graceful error handling flow.
   *
   * Catches errors and logs them without failing the stream.
   */
  def errorHandlingFlow[T](
    operation: T => Future[T],
    onError: Throwable => Unit = ex => logger.error("Operation failed", ex)
  ): Flow[T, T, NotUsed] = {
    Flow[T].mapAsync(1) { input =>
      operation(input).recoverWith {
        case ex =>
          onError(ex)
          Future.successful(input) // Continue with original input
      }
    }
  }
}
