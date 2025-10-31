package com.dataflow.domain.coordinator

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.pattern.StatusReply

/**
 * Commands for the Coordinator aggregate.
 *
 * The Coordinator manages the registry of all pipelines in the system,
 * tracks resource usage, and provides global operations.
 */
sealed trait CoordinatorCommand

/**
 * Register a new pipeline with the coordinator.
 *
 * @param pipelineId The unique pipeline ID
 * @param name The pipeline name
 * @param replyTo Reply destination
 */
final case class RegisterPipeline(
  pipelineId: String,
  name: String,
  replyTo: ActorRef[StatusReply[CoordinatorState]],
) extends CoordinatorCommand

/**
 * Unregister a pipeline from the coordinator.
 *
 * @param pipelineId The pipeline ID to unregister
 * @param replyTo Reply destination
 */
final case class UnregisterPipeline(
  pipelineId: String,
  replyTo: ActorRef[StatusReply[CoordinatorState]],
) extends CoordinatorCommand

/**
 * Update pipeline status (health check, state change, metrics update).
 *
 * @param pipelineId The pipeline ID
 * @param status The current pipeline status
 * @param totalRecords Total records processed by this pipeline
 * @param failedRecords Total failed records
 */
final case class UpdatePipelineStatus(
  pipelineId: String,
  status: PipelineStatus,
  totalRecords: Long,
  failedRecords: Long,
) extends CoordinatorCommand

/**
 * Get the current system status (all pipelines, resource usage, etc.).
 *
 * @param replyTo Reply destination
 */
final case class GetSystemStatus(
  replyTo: ActorRef[CoordinatorState],
) extends CoordinatorCommand

/**
 * Get information about a specific pipeline.
 *
 * @param pipelineId The pipeline ID
 * @param replyTo Reply destination
 */
final case class GetPipelineInfo(
  pipelineId: String,
  replyTo: ActorRef[Option[PipelineInfo]],
) extends CoordinatorCommand

/**
 * List all pipelines matching a filter.
 *
 * @param status Optional status filter
 * @param replyTo Reply destination
 */
final case class ListPipelines(
  status: Option[PipelineStatus],
  replyTo: ActorRef[List[PipelineInfo]],
) extends CoordinatorCommand

/**
 * Report resource usage by a pipeline.
 *
 * @param pipelineId The pipeline ID
 * @param cpuPercent CPU usage percentage (0-100)
 * @param memoryMB Memory usage in MB
 */
final case class ReportResourceUsage(
  pipelineId: String,
  cpuPercent: Double,
  memoryMB: Long,
) extends CoordinatorCommand

/**
 * Check if system resources are available for a new pipeline.
 *
 * @param estimatedCpuPercent Estimated CPU usage
 * @param estimatedMemoryMB Estimated memory usage
 * @param replyTo Reply destination
 */
final case class CheckResourceAvailability(
  estimatedCpuPercent: Double,
  estimatedMemoryMB: Long,
  replyTo: ActorRef[StatusReply[Boolean]],
) extends CoordinatorCommand

/**
 * Pipeline status enumeration.
 */
sealed trait PipelineStatus

object PipelineStatus {
  case object Configured extends PipelineStatus
  case object Running extends PipelineStatus
  case object Paused extends PipelineStatus
  case object Stopped extends PipelineStatus
  case object Failed extends PipelineStatus

  def fromString(s: String): PipelineStatus = s.toLowerCase match {
    case "configured" => Configured
    case "running"    => Running
    case "paused"     => Paused
    case "stopped"    => Stopped
    case "failed"     => Failed
    case _            => throw new IllegalArgumentException(s"Unknown status: $s")
  }

  def toString(status: PipelineStatus): String = status match {
    case Configured => "configured"
    case Running    => "running"
    case Paused     => "paused"
    case Stopped    => "stopped"
    case Failed     => "failed"
  }
}

/**
 * Pipeline information tracked by coordinator.
 *
 * @param pipelineId The pipeline ID
 * @param name The pipeline name
 * @param status Current status
 * @param totalRecords Total records processed
 * @param failedRecords Total failed records
 * @param cpuPercent Current CPU usage
 * @param memoryMB Current memory usage
 * @param registeredAt When the pipeline was registered
 * @param lastHeartbeat Last status update timestamp
 */
final case class PipelineInfo(
  pipelineId: String,
  name: String,
  status: PipelineStatus,
  totalRecords: Long,
  failedRecords: Long,
  cpuPercent: Double,
  memoryMB: Long,
  registeredAt: java.time.Instant,
  lastHeartbeat: java.time.Instant,
)
