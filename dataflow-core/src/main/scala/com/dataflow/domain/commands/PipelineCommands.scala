package com.dataflow.domain.commands

import com.dataflow.domain.models._
import com.dataflow.domain.state.State
import com.dataflow.serialization.CborSerializable
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.pattern.StatusReply

// ============================================
// COMMANDS - Requests to change state
// ============================================

sealed trait Command extends CborSerializable {
  def pipelineId: String
}

/**
 * Create a new pipeline with configuration.
 * This is the initial command that creates the pipeline entity.
 */
final case class CreatePipeline(
  pipelineId: String,
  name: String,
  description: String,
  sourceConfig: SourceConfig,
  transformConfigs: List[TransformConfig],
  sinkConfig: SinkConfig,
  replyTo: ActorRef[StatusReply[State]]) extends Command

/**
 * Start the pipeline (begin processing data).
 * Only works if pipeline is in Configured or Stopped state.
 */
final case class StartPipeline(
  pipelineId: String,
  replyTo: ActorRef[StatusReply[State]]) extends Command

/**
 * Stop the pipeline (stop processing data).
 * Checkpoints current position before stopping.
 */
final case class StopPipeline(
  pipelineId: String,
  reason: String,
  replyTo: ActorRef[StatusReply[State]]) extends Command

/**
 * Pause the pipeline (temporarily stop processing).
 * Can be resumed without losing state.
 */
final case class PausePipeline(
  pipelineId: String,
  reason: String,
  replyTo: ActorRef[StatusReply[State]]) extends Command

/**
 * Resume a paused pipeline.
 */
final case class ResumePipeline(
  pipelineId: String,
  replyTo: ActorRef[StatusReply[State]]) extends Command

/**
 * Ingest a batch of data into the pipeline.
 * This is the core command for processing data.
 *
 * Idempotency: Same batchId can be sent multiple times safely.
 */
final case class IngestBatch(
  pipelineId: String,
  batchId: String,
  records: List[DataRecord],
  sourceOffset: Long,
  replyTo: ActorRef[StatusReply[BatchResult]]) extends Command

/**
 * Update the checkpoint (processed offset).
 * This enables exactly-once semantics.
 */
final case class UpdateCheckpoint(
  pipelineId: String,
  checkpoint: Checkpoint) extends Command

/**
 * Report an error in pipeline processing.
 * Pipeline may transition to Failed state depending on error severity.
 */
final case class ReportFailure(
  pipelineId: String,
  error: PipelineError,
  replyTo: ActorRef[StatusReply[State]]) extends Command

/**
 * Reset a failed pipeline to allow restart.
 */
final case class ResetPipeline(
  pipelineId: String,
  replyTo: ActorRef[StatusReply[State]]) extends Command

/**
 * Get current state (read-only command).
 */
final case class GetState(
  pipelineId: String,
  replyTo: ActorRef[State]) extends Command

/**
 * Update pipeline configuration.
 * Only allowed when pipeline is stopped.
 */
final case class UpdateConfig(
  pipelineId: String,
  newConfig: PipelineConfig,
  replyTo: ActorRef[StatusReply[State]]) extends Command

/**
 * Internal command for batch timeout.
 * Triggered when a batch exceeds processing timeout.
 */
final case class BatchTimeout(
  pipelineId: String,
  batchId: String) extends Command
