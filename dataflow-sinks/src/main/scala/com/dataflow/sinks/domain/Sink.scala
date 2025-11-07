package com.dataflow.sinks.domain

import scala.concurrent.Future

import com.dataflow.domain.models.DataRecord
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink

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
