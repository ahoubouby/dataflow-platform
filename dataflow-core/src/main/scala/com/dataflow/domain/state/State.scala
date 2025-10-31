package com.dataflow.domain.state

import java.time.Instant

import com.dataflow.domain.models._
import com.dataflow.serialization.CborSerializable

sealed trait State extends CborSerializable {
  def pipelineId: String
}

/**
 * Initial state - pipeline doesn't exist yet.
 */
case object EmptyState extends State {
  override def pipelineId: String = ""
}

/**
 * Pipeline has been configured but not started.
 */
final case class ConfiguredState(
  pipelineId: String,
  name: String,
  description: String,
  config: PipelineConfig,
  createdAt: Instant) extends State

/**
 * Pipeline is actively processing data.
 * This is the main operational state.
 */
final case class RunningState(
  pipelineId: String,
  name: String,
  description: String,
  config: PipelineConfig,
  startedAt: Instant,
  checkpoint: Checkpoint,
  metrics: PipelineMetrics,
  processedBatchIds: Set[String], // For idempotency
) extends State

/**
 * Pipeline is temporarily paused.
 * Can be resumed without losing checkpoint.
 */
final case class PausedState(
  pipelineId: String,
  name: String,
  description: String,
  config: PipelineConfig,
  pausedAt: Instant,
  pauseReason: String,
  checkpoint: Checkpoint,
  metrics: PipelineMetrics) extends State

/**
 * Pipeline has been stopped.
 * Checkpoint is saved, can be restarted.
 */
final case class StoppedState(
  pipelineId: String,
  name: String,
  description: String,
  config: PipelineConfig,
  stoppedAt: Instant,
  stopReason: String,
  lastCheckpoint: Checkpoint,
  finalMetrics: PipelineMetrics) extends State

/**
 * Pipeline encountered an error and failed.
 * Must be reset before restarting.
 */
final case class FailedState(
  pipelineId: String,
  name: String,
  description: String,
  error: PipelineError,
  failedAt: Instant,
  lastCheckpoint: Option[Checkpoint]) extends State
