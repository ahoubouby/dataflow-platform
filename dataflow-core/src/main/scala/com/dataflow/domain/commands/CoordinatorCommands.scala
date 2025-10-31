package com.dataflow.domain.commands

import com.dataflow.domain.models.{PipelineInfo, PipelineStatus}
import com.dataflow.domain.state.CoordinatorState
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
  replyTo: ActorRef[StatusReply[CoordinatorState]]) extends CoordinatorCommand

/**
 * Unregister a pipeline from the coordinator.
 *
 * @param pipelineId The pipeline ID to unregister
 * @param replyTo Reply destination
 */
final case class UnregisterPipeline(
  pipelineId: String,
  replyTo: ActorRef[StatusReply[CoordinatorState]]) extends CoordinatorCommand

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
  failedRecords: Long) extends CoordinatorCommand

/**
 * Get the current system status (all pipelines, resource usage, etc.).
 *
 * @param replyTo Reply destination
 */
final case class GetSystemStatus(
  replyTo: ActorRef[CoordinatorState]) extends CoordinatorCommand

/**
 * Get information about a specific pipeline.
 *
 * @param pipelineId The pipeline ID
 * @param replyTo Reply destination
 */
final case class GetPipelineInfo(
  pipelineId: String,
  replyTo: ActorRef[Option[PipelineInfo]]) extends CoordinatorCommand

/**
 * List all pipelines matching a filter.
 *
 * @param status Optional status filter
 * @param replyTo Reply destination
 */
final case class ListPipelines(
  status: Option[PipelineStatus],
  replyTo: ActorRef[List[PipelineInfo]]) extends CoordinatorCommand

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
  memoryMB: Long) extends CoordinatorCommand

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
  replyTo: ActorRef[StatusReply[Boolean]]) extends CoordinatorCommand




