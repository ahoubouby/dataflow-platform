package com.dataflow.sources

import scala.concurrent.Future

import com.dataflow.domain.commands.Command
import com.dataflow.domain.models.{DataRecord, SourceConfig, SourceType}
import com.dataflow.sources.api.RestApiSource
import com.dataflow.sources.database.JdbcSource
import com.dataflow.sources.file.{CSVFileSource, JSONFileSource, TextFileSource}
import com.dataflow.sources.kafka.KafkaSource
import com.dataflow.sources.models.SourceState
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.stream.scaladsl.{Source => PekkoSource}

/**
 * Source abstraction for data ingestion.
 *
 * A Source is responsible for:
 * - Reading data from external systems (files, Kafka, databases, APIs)
 * - Converting raw data to DataRecords
 * - Batching records for efficient processing
 * - Checkpointing for exactly-once semantics
 * - Error handling and retry logic
 *
 * Sources are typically long-running and emit records continuously.
 */
trait Source {

  /**
   * Unique identifier for this source instance.
   */
  def sourceId: String

  /**
   * The pipeline ID this source feeds data to.
   */
  def pipelineId: String

  /**
   * Source configuration.
   */
  def config: SourceConfig

  /**
   * Create a Pekko Stream source that emits DataRecords.
   *
   * This is the main method sources must implement. It should return
   * a stream that:
   * - Reads data from the external system
   * - Converts to DataRecords
   * - Handles errors appropriately
   * - Respects backpressure
   *
   * @return Pekko Stream Source of DataRecords
   */
  def stream(): PekkoSource[DataRecord, Future[Done]]

  /**
   * Start the source (begin reading data).
   *
   * This method:
   * - Initializes connections to external systems
   * - Starts the stream
   * - Sends batched records to the pipeline
   *
   * @param pipelineShardRegion The pipeline shard region to send data to
   * @return Future that completes when source is started
   */
  def start(pipelineShardRegion: ActorRef[ShardingEnvelope[Command]]): Future[Done]

  /**
   * Stop the source (stop reading data).
   *
   * This method:
   * - Closes connections gracefully
   * - Flushes any pending data
   * - Releases resources
   *
   * @return Future that completes when source is stopped
   */
  def stop(): Future[Done]

  /**
   * Get the current offset/position in the source.
   *
   * Used for checkpointing and resuming from last position.
   *
   * @return Current offset as Long, or 0 if not applicable
   */
  def currentOffset(): Long

  /**
   * Resume from a specific offset/position.
   *
   * Used when restarting a pipeline to continue from last checkpoint.
   *
   * @param offset The offset to resume from
   */
  def resumeFrom(offset: Long): Unit

  /**
   * Health check for the source.
   *
   * @return true if source is healthy and can read data, false otherwise
   */
  def isHealthy: Boolean

  def state: SourceState
}

/**
 * Companion object with factory methods and utilities.
 */
object Source {

  /**
   * Source metrics for monitoring.
   */
  final case class SourceMetrics(
    recordsRead: Long,
    bytesRead: Long,
    batchesSent: Long,
    errors: Long,
    lastReadTime: Option[java.time.Instant],
    currentOffset: Long)

  /**
   * Factory method to create sources from configuration.
   *
   * @param pipelineId The pipeline ID
   * @param config Source configuration
   * @return Source instance
   */
  def apply(
    pipelineId: String,
    config: SourceConfig,
  )(implicit system: org.apache.pekko.actor.typed.ActorSystem[_],
  ): Source = {
    config.sourceType match {
      case SourceType.File =>
        // Determine file format from config options
        val format = config.options.getOrElse("format", "text").toLowerCase
        format match {
          case "csv"  => CSVFileSource(pipelineId, config)
          case "json" => JSONFileSource(pipelineId, config)
          case "text" => TextFileSource(pipelineId, config)
          case other  => throw new IllegalArgumentException(s"Unsupported file format: $other")
        }

      case SourceType.Kafka    => KafkaSource(pipelineId, config)
      case SourceType.Api      => RestApiSource(pipelineId, config)
      case SourceType.Database => JdbcSource(pipelineId, config)
      case other               => throw new IllegalArgumentException(s"Unsupported source type: $other")
    }
  }
}

/**
 * Backward-compatible helper objects for source instantiation.
 * These delegate to the Source factory method but provide format-specific names.
 */
object FileSource {

  def apply(
    pipelineId: String,
    config: SourceConfig,
  )(implicit system: ActorSystem[_],
  ): Source =
    Source(pipelineId, config)
}

object ApiSource {

  def apply(
    pipelineId: String,
    config: SourceConfig,
  )(implicit system: ActorSystem[_],
  ): Source =
    Source(pipelineId, config)
}

object DatabaseSource {

  def apply(
    pipelineId: String,
    config: SourceConfig,
  )(implicit system: ActorSystem[_],
  ): Source =
    Source(pipelineId, config)
}

/**
 * Adapter to wrap TestSource in the Source trait.
 */
private class TestSourceAdapter(
  val pipelineId: String,
  val config: SourceConfig,
)(implicit system: org.apache.pekko.actor.typed.ActorSystem[_]) extends Source {

  override def sourceId: String = s"test-source-$pipelineId"

  override def stream(): PekkoSource[DataRecord, Future[Done]] =
    // TestSource is actor-based, so we'd need to adapt it
    // For now, return empty source
    PekkoSource.empty[DataRecord].mapMaterializedValue(_ => Future.successful(Done))

  override def start(pipelineShardRegion: ActorRef[ShardingEnvelope[Command]]): Future[Done] =
    // Spawn TestSource actor
    // testSource ! TestSource.Start
    Future.successful(Done)

  override def stop(): Future[Done] =
    // testSource ! TestSource.Stop
    Future.successful(Done)

  override def currentOffset(): Long = 0

  override def resumeFrom(offset: Long): Unit = ()

  override def isHealthy: Boolean = true

  override def state: SourceState = SourceState.Starting
}
