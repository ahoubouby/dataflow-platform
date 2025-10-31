package com.dataflow.domain.events

import java.time.Instant

import com.dataflow.domain.models._
import com.dataflow.serialization.CborSerializable

sealed trait Event extends CborSerializable {
  def pipelineId: String
  def timestamp: Instant

  /**
   * Tags for event querying and projections.
   * Used by Pekko Projections to build read models.
   */
  def tags: Set[String] = Set("pipeline-events", "all-events")
}

final case class PipelineCreated(
  pipelineId: String,
  name: String,
  description: String,
  sourceConfig: SourceConfig,
  transformConfigs: List[TransformConfig],
  sinkConfig: SinkConfig,
  timestamp: Instant) extends Event {

  override def tags: Set[String] =
    super.tags ++ Set(s"source-${sourceConfig.sourceType}", s"sink-${sinkConfig.sinkType}")
}

final case class PipelineStarted(
  pipelineId: String,
  timestamp: Instant) extends Event {
  override def tags: Set[String] = super.tags ++ Set("pipeline-lifecycle")
}

final case class PipelineStopped(
  pipelineId: String,
  reason: String,
  finalMetrics: PipelineMetrics,
  timestamp: Instant) extends Event {
  override def tags: Set[String] = super.tags ++ Set("pipeline-lifecycle")
}

final case class PipelinePaused(
  pipelineId: String,
  reason: String,
  timestamp: Instant) extends Event

final case class PipelineResumed(
  pipelineId: String,
  timestamp: Instant) extends Event

final case class BatchIngested(
  pipelineId: String,
  batchId: String,
  recordCount: Int,
  sourceOffset: Long,
  timestamp: Instant) extends Event {
  override def tags: Set[String] = super.tags ++ Set("batch-events")
}

final case class BatchProcessed(
  pipelineId: String,
  batchId: String,
  successCount: Int,
  failureCount: Int,
  processingTimeMs: Long,
  timestamp: Instant) extends Event {
  override def tags: Set[String] = super.tags ++ Set("batch-events", "metrics")
}

final case class CheckpointUpdated(
  pipelineId: String,
  checkpoint: Checkpoint,
  timestamp: Instant) extends Event

final case class PipelineFailed(
  pipelineId: String,
  error: PipelineError,
  timestamp: Instant) extends Event {
  override def tags: Set[String] = super.tags ++ Set("failure-events")
}

final case class PipelineReset(
  pipelineId: String,
  timestamp: Instant) extends Event

final case class ConfigUpdated(
  pipelineId: String,
  newConfig: PipelineConfig,
  timestamp: Instant) extends Event

/**
 * Event indicating a retry has been scheduled for a failed operation.
 * Used for error recovery with exponential backoff.
 */
final case class RetryScheduled(
  pipelineId: String,
  error: PipelineError,
  retryCount: Int,
  backoffMs: Long,
  timestamp: Instant) extends Event {
  override def tags: Set[String] = super.tags ++ Set("retry-events", "error-recovery")
}

/**
 * Event indicating a batch processing has timed out.
 * Used to detect and handle stuck batches.
 */
final case class BatchTimedOut(
  pipelineId: String,
  batchId: String,
  timeoutMs: Long,
  timestamp: Instant) extends Event {
  override def tags: Set[String] = super.tags ++ Set("timeout-events", "failure-events")
}
