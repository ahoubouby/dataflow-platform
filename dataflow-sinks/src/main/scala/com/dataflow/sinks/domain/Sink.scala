package com.dataflow.sinks.domain

import com.dataflow.domain.models.DataRecord
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Future

/**
 * Base trait for all data sinks.
 *
 * Production-ready features:
 * - Batching for efficiency
 * - Retry with exponential backoff
 * - Health checks
 * - Metrics integration
 * - Graceful shutdown
 */
trait DataSink {
  /**
   * The name/type of this sink.
   */
  def sinkType: SinkType

  /**
   * The Pekko Streams Sink that writes data.
   */
  def sink: Sink[DataRecord, Future[Done]]

  /**
   * Health check - verify sink is operational.
   */
  def healthCheck(): Future[HealthStatus]

  /**
   * Close/cleanup resources.
   */
  def close(): Future[Done]

  /**
   * Metrics for this sink.
   */
  def metrics: SinkMetrics = SinkMetrics.empty
}

/**
 * Type-safe sink types.
 */
sealed trait SinkType {
  def name: String
}

object SinkType {
  case object Kafka extends SinkType {
    override def name: String = "kafka"
  }

  case object Cassandra extends SinkType {
    override def name: String = "cassandra"
  }

  case object File extends SinkType {
    override def name: String = "file"
  }

  case object Elasticsearch extends SinkType {
    override def name: String = "elasticsearch"
  }

  case object Console extends SinkType {
    override def name: String = "console"
  }
}

/**
 * Health status for sinks.
 */
sealed trait HealthStatus

object HealthStatus {
  case object Healthy extends HealthStatus
  case class Degraded(reason: String) extends HealthStatus
  case class Unhealthy(reason: String) extends HealthStatus
}

/**
 * Sink metrics for observability.
 */
case class SinkMetrics(
  recordsWritten: Long = 0,
  recordsFailed: Long = 0,
  batchesWritten: Long = 0,
  retriesAttempted: Long = 0,
  lastWriteTimestamp: Option[Long] = None
)

object SinkMetrics {
  val empty: SinkMetrics = SinkMetrics()
}

/**
 * Configuration for retry behavior.
 */
case class RetryConfig(
  maxAttempts: Int = 3,
  initialDelay: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(1).second,
  maxDelay: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(30).seconds,
  backoffFactor: Double = 2.0
)

object RetryConfig {
  val default: RetryConfig = RetryConfig()
}

/**
 * Batching configuration.
 */
case class BatchConfig(
  maxSize: Int = 100,
  maxDuration: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(5).seconds
)

object BatchConfig {
  val default: BatchConfig = BatchConfig()
}
