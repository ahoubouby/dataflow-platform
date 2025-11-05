package com.dataflow.domain.events

import com.dataflow.domain.models.PipelineStatus
import com.dataflow.serialization.CborSerializable

import java.time.Instant

/**
 * Events for the Coordinator aggregate.
 *
 * These events capture changes to the pipeline registry and resource tracking.
 */
sealed trait CoordinatorEvent extends CborSerializable {
  def timestamp: Instant
  def tags: Set[String] = Set("coordinator")
}

/**
 * A pipeline was registered with the coordinator.
 *
 * @param pipelineId The pipeline ID
 * @param name The pipeline name
 * @param timestamp When the registration occurred
 */
final case class PipelineRegistered(
  pipelineId: String,
  name: String,
  timestamp: Instant) extends CoordinatorEvent

/**
 * A pipeline was unregistered from the coordinator.
 *
 * @param pipelineId The pipeline ID
 * @param timestamp When the unregistration occurred
 */
final case class PipelineUnregistered(
  pipelineId: String,
  timestamp: Instant) extends CoordinatorEvent

/**
 * Pipeline status was updated (health check, state change, metrics).
 *
 * @param pipelineId The pipeline ID
 * @param status The new status
 * @param totalRecords Total records processed
 * @param failedRecords Total failed records
 * @param timestamp When the status update occurred
 */
final case class PipelineStatusUpdated(
  pipelineId: String,
  status: PipelineStatus,
  totalRecords: Long,
  failedRecords: Long,
  timestamp: Instant) extends CoordinatorEvent

/**
 * Resource usage was reported by a pipeline.
 *
 * @param pipelineId The pipeline ID
 * @param cpuPercent CPU usage percentage
 * @param memoryMB Memory usage in MB
 * @param timestamp When the resource usage was reported
 */
final case class ResourceUsageReported(
  pipelineId: String,
  cpuPercent: Double,
  memoryMB: Long,
  timestamp: Instant) extends CoordinatorEvent
