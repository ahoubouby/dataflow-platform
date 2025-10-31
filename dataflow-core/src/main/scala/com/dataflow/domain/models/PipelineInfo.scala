package com.dataflow.domain.models

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
  lastHeartbeat: java.time.Instant)
