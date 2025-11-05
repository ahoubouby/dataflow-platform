package com.dataflow.domain.state


import com.dataflow.domain.models.{PipelineInfo, PipelineStatus}

import java.time.Instant

/**
 * State for the Coordinator aggregate.
 *
 * The coordinator maintains a registry of all pipelines, their current status,
 * and aggregate resource usage across the system.
 */
final case class CoordinatorState(
  pipelines: Map[String, PipelineInfo],
  totalCpuPercent: Double,
  totalMemoryMB: Long,
  lastUpdated: Instant) {

  /**
   * Get information about a specific pipeline.
   *
   * @param pipelineId The pipeline ID
   * @return Pipeline info if found
   */
  def getPipeline(pipelineId: String): Option[PipelineInfo] =
    pipelines.get(pipelineId)

  /**
   * List all pipelines, optionally filtered by status.
   *
   * @param statusFilter Optional status to filter by
   * @return List of pipeline info
   */
  def listPipelines(statusFilter: Option[PipelineStatus] = None): List[PipelineInfo] = {
    val allPipelines = pipelines.values.toList
    statusFilter match {
      case Some(status) => allPipelines.filter(_.status == status)
      case None         => allPipelines
    }
  }

  /**
   * Get count of pipelines by status.
   *
   * @return Map of status to count
   */
  def countByStatus: Map[PipelineStatus, Int] =
    pipelines.values.groupBy(_.status).view.mapValues(_.size).toMap

  /**
   * Check if resources are available for a new pipeline.
   *
   * @param estimatedCpu Estimated CPU usage (0-100)
   * @param estimatedMemory Estimated memory in MB
   * @param maxCpuPercent Maximum total CPU allowed (default 80%)
   * @param maxMemoryMB Maximum total memory allowed (default 80GB)
   * @return True if resources are available
   */
  def hasResourcesAvailable(
    estimatedCpu: Double,
    estimatedMemory: Long,
    maxCpuPercent: Double = 80.0,
    maxMemoryMB: Long = 80000,
  ): Boolean = {
    val projectedCpu    = totalCpuPercent + estimatedCpu
    val projectedMemory = totalMemoryMB + estimatedMemory
    projectedCpu <= maxCpuPercent && projectedMemory <= maxMemoryMB
  }

  /**
   * Get system health summary.
   *
   * @return SystemHealth summary
   */
  def getSystemHealth: SystemHealth = {
    val counts       = countByStatus
    val runningCount = counts.getOrElse(PipelineStatus.Running, 0)
    val failedCount  = counts.getOrElse(PipelineStatus.Failed, 0)
    val totalCount   = pipelines.size

    val healthStatus = if (failedCount > runningCount * 0.5) {
      HealthStatus.Critical // More than 50% failed
    } else if (failedCount > 0) {
      HealthStatus.Degraded // Some failures
    } else if (totalCount == 0) {
      HealthStatus.Idle // No pipelines
    } else {
      HealthStatus.Healthy // All good
    }

    SystemHealth(
      status = healthStatus,
      totalPipelines = totalCount,
      runningPipelines = runningCount,
      failedPipelines = failedCount,
      totalCpuPercent = totalCpuPercent,
      totalMemoryMB = totalMemoryMB,
      lastUpdated = lastUpdated,
    )
  }
}

object CoordinatorState {

  val empty: CoordinatorState = CoordinatorState(
    pipelines = Map.empty,
    totalCpuPercent = 0.0,
    totalMemoryMB = 0L,
    lastUpdated = Instant.now(),
  )
}

/**
 * System health summary.
 *
 * @param status Overall health status
 * @param totalPipelines Total number of pipelines
 * @param runningPipelines Number of running pipelines
 * @param failedPipelines Number of failed pipelines
 * @param totalCpuPercent Total CPU usage across all pipelines
 * @param totalMemoryMB Total memory usage across all pipelines
 * @param lastUpdated When the health was last updated
 */
final case class SystemHealth(
  status: HealthStatus,
  totalPipelines: Int,
  runningPipelines: Int,
  failedPipelines: Int,
  totalCpuPercent: Double,
  totalMemoryMB: Long,
  lastUpdated: Instant)

/**
 * Overall system health status.
 */
sealed trait HealthStatus

object HealthStatus {
  case object Healthy extends HealthStatus  // All pipelines running normally
  case object Degraded extends HealthStatus // Some pipelines failed
  case object Critical extends HealthStatus // Majority of pipelines failed
  case object Idle extends HealthStatus     // No pipelines registered
}
