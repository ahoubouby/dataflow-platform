package com.dataflow.aggregates.coordinator

import java.time.Instant

import com.dataflow.domain.commands._
import com.dataflow.domain.events._
import com.dataflow.domain.models._
import com.dataflow.domain.state.CoordinatorState
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Coordinator aggregate manages the global registry of pipelines.
 *
 * Responsibilities:
 * - Track all registered pipelines
 * - Monitor pipeline status and health
 * - Track resource usage across all pipelines
 * - Provide system-wide operations and queries
 *
 * The coordinator is a singleton in the cluster (single instance per system).
 */
object CoordinatorAggregate {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  val CoordinatorId = "global-coordinator"

  // Resource limits (can be configured via application.conf in future)
  private val MaxCpuPercent: Double = 80.0   // 80% total CPU
  private val MaxMemoryMB:   Long   = 80000L // 80GB total memory

  /**
   * Create the event sourced behavior for the coordinator.
   */
  def apply(): EventSourcedBehavior[CoordinatorCommand, CoordinatorEvent, CoordinatorState] = {
    EventSourcedBehavior
      .withEnforcedReplies[CoordinatorCommand, CoordinatorEvent, CoordinatorState](
        persistenceId = PersistenceId.ofUniqueId(CoordinatorId),
        emptyState = CoordinatorState.empty,
        commandHandler = commandHandler,
        eventHandler = eventHandler,
      )
      .withRetention(
        RetentionCriteria
          .snapshotEvery(numberOfEvents = 50, keepNSnapshots = 2)
          .withDeleteEventsOnSnapshot,
      )
      .withTagger(event => event.tags)
  }

  // ============================================
  // COMMAND HANDLER
  // ============================================

  private def commandHandler(
    state: CoordinatorState,
    command: CoordinatorCommand,
  ): ReplyEffect[CoordinatorEvent, CoordinatorState] = {

    command match {
      case RegisterPipeline(pipelineId, name, replyTo) =>
        if (state.pipelines.contains(pipelineId)) {
          log.warn("msg=Pipeline already registered pipelineId={}", pipelineId)
          Effect.reply(replyTo)(
            StatusReply.error(s"Pipeline already registered: $pipelineId"),
          )
        } else {
          log.info("msg=Register pipeline pipelineId={} name={}", pipelineId, name)
          Effect
            .persist(PipelineRegistered(pipelineId, name, Instant.now()))
            .thenReply(replyTo)(newState => StatusReply.success(newState))
        }

      case UnregisterPipeline(pipelineId, replyTo) =>
        if (!state.pipelines.contains(pipelineId)) {
          log.warn("msg=Pipeline not found pipelineId={}", pipelineId)
          Effect.reply(replyTo)(
            StatusReply.error(s"Pipeline not found: $pipelineId"),
          )
        } else {
          log.info("msg=Unregister pipeline pipelineId={}", pipelineId)
          Effect
            .persist(PipelineUnregistered(pipelineId, Instant.now()))
            .thenReply(replyTo)(newState => StatusReply.success(newState))
        }

      case UpdatePipelineStatus(pipelineId, status, totalRecords, failedRecords) =>
        if (!state.pipelines.contains(pipelineId)) {
          log.warn("msg=Status update for unknown pipeline pipelineId={}", pipelineId)
          // Silently ignore - pipeline may have been unregistered
          Effect
            .none
            .thenNoReply()
        } else {
          log.debug(
            "msg=Update pipeline status pipelineId={} status={} records={} failed={}",
            pipelineId,
            PipelineStatus.toString(status),
            totalRecords,
            failedRecords,
          )
          Effect
            .persist(PipelineStatusUpdated(pipelineId, status, totalRecords, failedRecords, Instant.now()))
            .thenNoReply()
        }

      case GetSystemStatus(replyTo) =>
        Effect.reply(replyTo)(state)

      case GetPipelineInfo(pipelineId, replyTo) =>
        Effect.reply(replyTo)(state.getPipeline(pipelineId))

      case ListPipelines(statusFilter, replyTo) =>
        Effect.reply(replyTo)(state.listPipelines(statusFilter))

      case ReportResourceUsage(pipelineId, cpuPercent, memoryMB) =>
        if (!state.pipelines.contains(pipelineId)) {
          log.warn("msg=Resource report for unknown pipeline pipelineId={}", pipelineId)
          Effect.none
            .thenNoReply()
        } else {
          log.debug(
            "msg=Report resource usage pipelineId={} cpu={}% memory={}MB",
            pipelineId,
            cpuPercent,
            memoryMB,
          )
          Effect
            .persist(ResourceUsageReported(pipelineId, cpuPercent, memoryMB, Instant.now()))
            .thenNoReply()
        }

      case CheckResourceAvailability(estimatedCpu, estimatedMemory, replyTo) =>
        val available = state.hasResourcesAvailable(estimatedCpu, estimatedMemory, MaxCpuPercent, MaxMemoryMB)

        if (available) {
          log.debug(
            "msg=Resources available cpu={}% memory={}MB",
            estimatedCpu,
            estimatedMemory,
          )
        } else {
          log.warn(
            "msg=Resources unavailable cpu={}% (current={}) memory={}MB (current={})",
            estimatedCpu,
            state.totalCpuPercent,
            estimatedMemory,
            state.totalMemoryMB,
          )
        }

        Effect.reply(replyTo)(StatusReply.success(available))
    }
  }

  // ============================================
  // EVENT HANDLER
  // ============================================

  private val eventHandler: (CoordinatorState, CoordinatorEvent) => CoordinatorState = {
    (state, event) =>
      event match {
        case PipelineRegistered(pipelineId, name, ts) =>
          log.debug("msg=Evt PipelineRegistered pipelineId={} name={}", pipelineId, name)
          val info = PipelineInfo(
            pipelineId = pipelineId,
            name = name,
            status = PipelineStatus.Configured,
            totalRecords = 0L,
            failedRecords = 0L,
            cpuPercent = 0.0,
            memoryMB = 0L,
            registeredAt = ts,
            lastHeartbeat = ts,
          )
          state.copy(
            pipelines = state.pipelines + (pipelineId -> info),
            lastUpdated = ts,
          )

        case PipelineUnregistered(pipelineId, ts) =>
          log.debug("msg=Evt PipelineUnregistered pipelineId={}", pipelineId)
          val oldInfo = state.pipelines.get(pipelineId)

          val newState = state.copy(
            pipelines = state.pipelines - pipelineId,
            lastUpdated = ts,
          )

          // Recalculate resource totals after removing pipeline
          oldInfo match {
            case Some(info) =>
              newState.copy(
                totalCpuPercent = state.totalCpuPercent - info.cpuPercent,
                totalMemoryMB = state.totalMemoryMB - info.memoryMB,
              )
            case None       => newState
          }

        case PipelineStatusUpdated(pipelineId, status, totalRecords, failedRecords, ts) =>
          state.pipelines.get(pipelineId) match {
            case Some(info) =>
              val updatedInfo = info.copy(
                status = status,
                totalRecords = totalRecords,
                failedRecords = failedRecords,
                lastHeartbeat = ts,
              )
              state.copy(
                pipelines = state.pipelines + (pipelineId -> updatedInfo),
                lastUpdated = ts,
              )
            case None       =>
              log.warn("msg=Evt status update for unknown pipeline pipelineId={}", pipelineId)
              state
          }

        case ResourceUsageReported(pipelineId, cpuPercent, memoryMB, ts) =>
          state.pipelines.get(pipelineId) match {
            case Some(info) =>
              val oldCpu    = info.cpuPercent
              val oldMemory = info.memoryMB

              val updatedInfo = info.copy(
                cpuPercent = cpuPercent,
                memoryMB = memoryMB,
                lastHeartbeat = ts,
              )

              // Update totals by removing old values and adding new values
              state.copy(
                pipelines = state.pipelines + (pipelineId -> updatedInfo),
                totalCpuPercent = state.totalCpuPercent - oldCpu + cpuPercent,
                totalMemoryMB = state.totalMemoryMB - oldMemory + memoryMB,
                lastUpdated = ts,
              )
            case None       =>
              log.warn("msg=Evt resource report for unknown pipeline pipelineId={}", pipelineId)
              state
          }
      }
  }
}
