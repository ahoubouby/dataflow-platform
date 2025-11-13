package com.dataflow.api.models

import com.dataflow.domain.models._
import com.dataflow.domain.state.State
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
  sourceConfig: SourceConfig,
  transformConfigs: List[TransformConfig],
  sinkConfig: SinkConfig
)

/**
 * Request to update pipeline configuration
 */
final case class UpdatePipelineRequest(
  name: Option[String],
  description: Option[String],
  sourceConfig: Option[SourceConfig],
  transformConfigs: Option[List[TransformConfig]],
  sinkConfig: Option[SinkConfig]
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
  sourceConfig: SourceConfig,
  transformConfigs: List[TransformConfig],
  sinkConfig: SinkConfig,
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
      case state: State.Configured =>
        PipelineDetails(
          pipelineId = pipelineId,
          name = state.config.name,
          description = state.config.description,
          status = "configured",
          sourceConfig = state.config.sourceConfig,
          transformConfigs = state.config.transformConfigs,
          sinkConfig = state.config.sinkConfig,
          metrics = None,
          checkpoint = None,
          createdAt = state.createdAt,
          updatedAt = state.updatedAt
        )

      case state: State.Running =>
        PipelineDetails(
          pipelineId = pipelineId,
          name = state.config.name,
          description = state.config.description,
          status = "running",
          sourceConfig = state.config.sourceConfig,
          transformConfigs = state.config.transformConfigs,
          sinkConfig = state.config.sinkConfig,
          metrics = Some(state.metrics),
          checkpoint = Some(state.checkpoint),
          createdAt = state.createdAt,
          updatedAt = state.updatedAt
        )

      case state: State.Paused =>
        PipelineDetails(
          pipelineId = pipelineId,
          name = state.config.name,
          description = state.config.description,
          status = "paused",
          sourceConfig = state.config.sourceConfig,
          transformConfigs = state.config.transformConfigs,
          sinkConfig = state.config.sinkConfig,
          metrics = Some(state.metrics),
          checkpoint = Some(state.checkpoint),
          createdAt = state.createdAt,
          updatedAt = state.updatedAt
        )

      case state: State.Stopped =>
        PipelineDetails(
          pipelineId = pipelineId,
          name = state.config.name,
          description = state.config.description,
          status = "stopped",
          sourceConfig = state.config.sourceConfig,
          transformConfigs = state.config.transformConfigs,
          sinkConfig = state.config.sinkConfig,
          metrics = Some(state.metrics),
          checkpoint = Some(state.checkpoint),
          createdAt = state.createdAt,
          updatedAt = state.updatedAt
        )

      case state: State.Failed =>
        PipelineDetails(
          pipelineId = pipelineId,
          name = state.config.name,
          description = state.config.description,
          status = "failed",
          sourceConfig = state.config.sourceConfig,
          transformConfigs = state.config.transformConfigs,
          sinkConfig = state.config.sinkConfig,
          metrics = Some(state.metrics),
          checkpoint = Some(state.checkpoint),
          createdAt = state.createdAt,
          updatedAt = state.updatedAt
        )

      case State.Empty =>
        throw new IllegalStateException("Cannot convert Empty state to PipelineDetails")
    }
  }

  /**
   * Convert State to PipelineSummary
   */
  def stateToSummary(state: State, pipelineId: String): PipelineSummary = {
    state match {
      case state: State.Configured =>
        PipelineSummary(
          pipelineId = pipelineId,
          name = state.config.name,
          description = state.config.description,
          status = "configured",
          createdAt = state.createdAt,
          updatedAt = state.updatedAt
        )

      case state: State.Running =>
        PipelineSummary(
          pipelineId = pipelineId,
          name = state.config.name,
          description = state.config.description,
          status = "running",
          createdAt = state.createdAt,
          updatedAt = state.updatedAt
        )

      case state: State.Paused =>
        PipelineSummary(
          pipelineId = pipelineId,
          name = state.config.name,
          description = state.config.description,
          status = "paused",
          createdAt = state.createdAt,
          updatedAt = state.updatedAt
        )

      case state: State.Stopped =>
        PipelineSummary(
          pipelineId = pipelineId,
          name = state.config.name,
          description = state.config.description,
          status = "stopped",
          createdAt = state.createdAt,
          updatedAt = state.updatedAt
        )

      case state: State.Failed =>
        PipelineSummary(
          pipelineId = pipelineId,
          name = state.config.name,
          description = state.config.description,
          status = "failed",
          createdAt = state.createdAt,
          updatedAt = state.updatedAt
        )

      case State.Empty =>
        throw new IllegalStateException("Cannot convert Empty state to PipelineSummary")
    }
  }
}
