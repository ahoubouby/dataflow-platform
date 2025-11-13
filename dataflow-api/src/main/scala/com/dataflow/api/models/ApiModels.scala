package com.dataflow.api.models

import com.dataflow.domain.models._
import com.dataflow.domain.state._
import java.time.Instant

// ============================================
// REQUEST MODELS
// ============================================

/**
 * Request to create a new pipeline
 */
final case class CreatePipelineRequest(
  name: String,
  description: String,
  source: SourceConfig,
  transforms: List[TransformConfig],
  sink: SinkConfig
)

/**
 * Request to update pipeline configuration
 */
final case class UpdatePipelineRequest(
  name: Option[String],
  description: Option[String],
  source: Option[SourceConfig],
  transforms: Option[List[TransformConfig]],
  sink: Option[SinkConfig]
)

/**
 * Request to stop or pause pipeline
 */
final case class StopPipelineRequest(
  reason: String
)

// ============================================
// RESPONSE MODELS
// ============================================

/**
 * Response for pipeline creation
 */
final case class CreatePipelineResponse(
  pipelineId: String,
  status: String,
  message: String
)

/**
 * Response for pipeline operations
 */
final case class PipelineOperationResponse(
  pipelineId: String,
  status: String,
  message: String,
  timestamp: Instant
)

/**
 * Pipeline summary for list responses
 */
final case class PipelineSummary(
  pipelineId: String,
  name: String,
  description: String,
  status: String,
  createdAt: Instant,
  updatedAt: Instant
)

/**
 * Detailed pipeline information
 */
final case class PipelineDetails(
  pipelineId: String,
  name: String,
  description: String,
  status: String,
  source: SourceConfig,
  transforms: List[TransformConfig],
  sink: SinkConfig,
  metrics: Option[PipelineMetrics],
  checkpoint: Option[Checkpoint],
  createdAt: Instant,
  updatedAt: Instant
)

/**
 * List of pipelines response
 */
final case class PipelineListResponse(
  pipelines: List[PipelineSummary],
  total: Int
)

/**
 * Health status response
 */
final case class HealthResponse(
  pipelineId: String,
  healthy: Boolean,
  status: String,
  lastCheck: Instant,
  details: Option[String]
)

/**
 * Metrics response
 */
final case class MetricsResponse(
  pipelineId: String,
  metrics: PipelineMetrics,
  timestamp: Instant
)

/**
 * Error response
 */
final case class ErrorResponse(
  error: String,
  message: String,
  details: Option[String] = None,
  timestamp: Instant = Instant.now()
)

/**
 * Generic success response
 */
final case class SuccessResponse(
  message: String,
  timestamp: Instant = Instant.now()
)

// ============================================
// HELPER CONVERTERS
// ============================================

object ApiModelConverters {

  /**
   * Convert State to PipelineDetails
   */
  def stateToDetails(state: State, pipelineId: String): PipelineDetails = {
    state match {
      case state: ConfiguredState =>
        PipelineDetails(
          pipelineId = pipelineId,
          name = state.name,
          description = state.description,
          status = "configured",
          source = state.config.source,
          transforms = state.config.transforms,
          sink = state.config.sink,
          metrics = None,
          checkpoint = None,
          createdAt = state.createdAt,
          updatedAt = state.createdAt // ConfiguredState only has createdAt
        )

      case state: RunningState =>
        PipelineDetails(
          pipelineId = pipelineId,
          name = state.name,
          description = state.description,
          status = "running",
          source = state.config.source,
          transforms = state.config.transforms,
          sink = state.config.sink,
          metrics = Some(state.metrics),
          checkpoint = Some(state.checkpoint),
          createdAt = state.startedAt, // Use startedAt as createdAt
          updatedAt = state.metrics.lastProcessedAt.getOrElse(state.startedAt)
        )

      case state: PausedState =>
        PipelineDetails(
          pipelineId = pipelineId,
          name = state.name,
          description = state.description,
          status = "paused",
          source = state.config.source,
          transforms = state.config.transforms,
          sink = state.config.sink,
          metrics = Some(state.metrics),
          checkpoint = Some(state.checkpoint),
          createdAt = state.pausedAt, // Best approximation
          updatedAt = state.pausedAt
        )

      case state: StoppedState =>
        PipelineDetails(
          pipelineId = pipelineId,
          name = state.name,
          description = state.description,
          status = "stopped",
          source = state.config.source,
          transforms = state.config.transforms,
          sink = state.config.sink,
          metrics = Some(state.finalMetrics),
          checkpoint = Some(state.lastCheckpoint),
          createdAt = state.stoppedAt, // Best approximation
          updatedAt = state.stoppedAt
        )

      case state: FailedState =>
        PipelineDetails(
          pipelineId = pipelineId,
          name = state.name,
          description = state.description,
          status = "failed",
          source = SourceConfig(SourceType.File, "", 0), // FailedState doesn't have config
          transforms = List.empty,
          sink = SinkConfig("", "", 0),
          metrics = None,
          checkpoint = state.lastCheckpoint,
          createdAt = state.failedAt,
          updatedAt = state.failedAt
        )

      case EmptyState =>
        throw new IllegalStateException("Cannot convert EmptyState to PipelineDetails")
    }
  }

  /**
   * Convert State to PipelineSummary
   */
  def stateToSummary(state: State, pipelineId: String): PipelineSummary = {
    state match {
      case state: ConfiguredState =>
        PipelineSummary(
          pipelineId = pipelineId,
          name = state.name,
          description = state.description,
          status = "configured",
          createdAt = state.createdAt,
          updatedAt = state.createdAt
        )

      case state: RunningState =>
        PipelineSummary(
          pipelineId = pipelineId,
          name = state.name,
          description = state.description,
          status = "running",
          createdAt = state.startedAt,
          updatedAt = state.metrics.lastProcessedAt.getOrElse(state.startedAt)
        )

      case state: PausedState =>
        PipelineSummary(
          pipelineId = pipelineId,
          name = state.name,
          description = state.description,
          status = "paused",
          createdAt = state.pausedAt,
          updatedAt = state.pausedAt
        )

      case state: StoppedState =>
        PipelineSummary(
          pipelineId = pipelineId,
          name = state.name,
          description = state.description,
          status = "stopped",
          createdAt = state.stoppedAt,
          updatedAt = state.stoppedAt
        )

      case state: FailedState =>
        PipelineSummary(
          pipelineId = pipelineId,
          name = state.name,
          description = state.description,
          status = "failed",
          createdAt = state.failedAt,
          updatedAt = state.failedAt
        )

      case EmptyState =>
        throw new IllegalStateException("Cannot convert EmptyState to PipelineSummary")
    }
  }
}
