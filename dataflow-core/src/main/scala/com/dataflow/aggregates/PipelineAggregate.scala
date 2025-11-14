package com.dataflow.aggregates

import com.dataflow.aggregates.handlers._
import com.dataflow.domain.commands._
import com.dataflow.domain.events._
import com.dataflow.domain.models._
import com.dataflow.domain.state._
import com.dataflow.recovery.{ErrorRecovery, TimeoutConfig}
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted, SnapshotCompleted}
import org.apache.pekko.persistence.typed.scaladsl.{EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import org.slf4j.LoggerFactory

object PipelineAggregate {

  // ============================================
  // CONFIGURATION
  // ============================================

  private val timeoutConfig = TimeoutConfig.Default
  private val retryConfig   = ErrorRecovery.DefaultRetryConfig
  private val log           = LoggerFactory.getLogger("PipelineAggregate")

  // ============================================
  // EVENT SOURCED BEHAVIOR
  // ============================================

  def apply(pipelineId: String): EventSourcedBehavior[Command, Event, State] = {
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(pipelineId),
        emptyState = EmptyState,
        commandHandler = (state, command) => commandHandler(pipelineId, state, command),
        eventHandler = eventHandler,
      )
      .withRetention(
        RetentionCriteria
          .snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2)
          .withDeleteEventsOnSnapshot,
      )
      .withTagger(event => event.tags) // tags: Set[String]
      .receiveSignal {
        case (state, RecoveryCompleted) =>
          state match {
            case r: RunningState =>
              log.info(
                "msg=Recovered pipeline state stage=running pipelineId={} offset={} recordsProcessed={}",
                pipelineId,
                r.checkpoint.offset,
                r.checkpoint.recordsProcessed,
              )
            case other           =>
              log.info(
                "msg=Recovered pipeline state pipelineId={} state={}",
                pipelineId,
                other.getClass.getSimpleName,
              )
          }

        case (_, SnapshotCompleted(metadata)) =>
          log.info(
            "msg=Snapshot completed pipelineId={} seqNr={}",
            pipelineId,
            metadata.sequenceNr,
          )
      }
  }

  // ============================================
  // COMMAND HANDLER - Delegates to State Handlers
  // ============================================

  private def commandHandler(
    pipelineId: String,
    state: State,
    command: Command,
  ): ReplyEffect[Event, State] = {

    log.debug(
      "msg=Handle command pipelineId={} cmd={} state={}",
      pipelineId,
      command.getClass.getSimpleName,
      state.getClass.getSimpleName,
    )

    // Delegate to appropriate state handler
    state match {
      case EmptyState         => EmptyStateHandler.handle(command)
      case s: ConfiguredState => ConfiguredStateHandler.handle(s, command)
      case s: RunningState    => RunningStateHandler.handle(s, command, timeoutConfig, retryConfig)
      case s: PausedState     => PausedStateHandler.handle(s, command)
      case s: StoppedState    => StoppedStateHandler.handle(s, command)
      case s: FailedState     => FailedStateHandler.handle(s, command)
    }
  }

  // ============================================
  // EVENT HANDLER - State Updates with Proper Transitions
  // ============================================

  private val eventHandler: (State, Event) => State = {
    (state, event) =>
      // Pattern match on BOTH state and event for safe transitions
      (state, event) match {
        // EmptyState → ConfiguredState
        case (EmptyState, PipelineCreated(id, name, desc, config, ts)) =>
          log.debug("msg=Evt PipelineCreated id={} name='{}'", id, name)
          ConfiguredState(
            pipelineId = id,
            name = name,
            description = desc,
            config = config,
            createdAt = ts,
          )

        // ConfiguredState → RunningState
        case (cfg: ConfiguredState, PipelineStarted(_, ts)) =>
          log.debug("msg=Evt PipelineStarted pipelineId={} from=ConfiguredState", cfg.pipelineId)
          val newState = RunningState(
            pipelineId = cfg.pipelineId,
            name = cfg.name,
            description = cfg.description,
            config = cfg.config,
            startedAt = ts,
            checkpoint = Checkpoint.initial,
            metrics = PipelineMetrics.empty,
            processedBatchIds = Set.empty,
            retryCount = 0,
            activeBatchId = None,
          )
          newState

        // StoppedState → RunningState (restart)
        case (stopped: StoppedState, PipelineStarted(_, ts)) =>
          log.info("msg=Evt PipelineStarted pipelineId={} from=StoppedState (restart)", stopped.pipelineId)
          val newState = RunningState(
            pipelineId = stopped.pipelineId,
            name = stopped.name,
            description = stopped.description,
            config = stopped.config,
            startedAt = ts,
            checkpoint = stopped.lastCheckpoint, // Resume from last checkpoint!
            metrics = PipelineMetrics.empty,     // Reset metrics for new run
            processedBatchIds = Set.empty,
            retryCount = 0,
            activeBatchId = None,
          )
          newState

        // RunningState batch events
        case (r: RunningState, BatchIngested(_, batchId, _, _, _)) =>
          r.copy(activeBatchId = Some(batchId))

        case (r: RunningState, BatchProcessed(pipelineId, batchId, ok, ko, timeMs, _)) =>
          // Record batch processing metrics
          val newMetrics = r.metrics.incrementBatch(ok, ko, timeMs)
          r.copy(
            metrics = newMetrics,
            processedBatchIds = r.processedBatchIds + batchId,
            activeBatchId = None,
            retryCount = ErrorRecovery.resetRetryCount(),
          )

        case (r: RunningState, CheckpointUpdated(pipelineId, checkpoint, _)) =>
          r.copy(checkpoint = checkpoint)

        case (r: RunningState, RetryScheduled(pipelineId, error, retryCount, _, _)) =>
          log.debug("msg=Evt RetryScheduled pipelineId={} retryCount={}", r.pipelineId, retryCount)
          r.copy(retryCount = retryCount)

        case (r: RunningState, BatchTimedOut(pipelineId, batchId, timeoutMs, _)) =>
          log.warn("msg=Evt BatchTimedOut pipelineId={} batchId={} timeoutMs={}", r.pipelineId, batchId, timeoutMs)
          r.copy(activeBatchId = None)

        // RunningState → StoppedState
        case (r: RunningState, PipelineStopped(_, reason, finalMetrics, ts)) =>
          log.debug("msg=Evt PipelineStopped pipelineId={} reason={}", r.pipelineId, reason)
          val newState = StoppedState(
            pipelineId = r.pipelineId,
            name = r.name,
            description = r.description,
            config = r.config,
            stoppedAt = ts,
            stopReason = reason,
            lastCheckpoint = r.checkpoint,
            finalMetrics = finalMetrics,
          )
          newState

        // RunningState → PausedState
        case (r: RunningState, PipelinePaused(_, reason, ts)) =>
          log.debug("msg=Evt PipelinePaused pipelineId={} reason={}", r.pipelineId, reason)
          val newState = PausedState(
            pipelineId = r.pipelineId,
            name = r.name,
            description = r.description,
            config = r.config,
            pausedAt = ts,
            pauseReason = reason,
            checkpoint = r.checkpoint,
            metrics = r.metrics,
          )
          newState

        // PausedState → RunningState
        case (p: PausedState, PipelineResumed(_, ts)) =>
          log.debug("msg=Evt PipelineResumed pipelineId={}", p.pipelineId)
          val newState = RunningState(
            pipelineId = p.pipelineId,
            name = p.name,
            description = p.description,
            config = p.config,
            startedAt = ts,
            checkpoint = p.checkpoint,
            metrics = p.metrics,
            processedBatchIds = Set.empty,
            retryCount = 0,
            activeBatchId = None,
          )
          newState

        // RunningState → FailedState
        case (r: RunningState, PipelineFailed(_, error, ts)) =>
          log.error("msg=Evt PipelineFailed pipelineId={} code={} message={}", r.pipelineId, error.code, error.message)
          val newState = FailedState(
            pipelineId = r.pipelineId,
            name = r.name,
            description = r.description,
            error = error,
            failedAt = ts,
            lastCheckpoint = Some(r.checkpoint),
          )
          newState

        // FailedState → ConfiguredState
        case (f: FailedState, PipelineReset(_, ts)) =>
          log.info("msg=Evt PipelineReset pipelineId={}", f.pipelineId)
          ConfiguredState(
            pipelineId = f.pipelineId,
            name = f.name,
            description = f.description,
            config = PipelineConfig(SourceConfig( SourceType.fromString("kafka").getOrElse(SourceType.File), "", 0), List.empty, SinkConfig("", "", 0)),
            createdAt = ts,
          )

        // Config updates (allowed in ConfiguredState and StoppedState)
        case (c: ConfiguredState, ConfigUpdated(_, newConfig, _)) =>
          log.debug("msg=Evt ConfigUpdated pipelineId={}", c.pipelineId)
          c.copy(config = newConfig)

        case (s: StoppedState, ConfigUpdated(_, newConfig, _)) =>
          log.debug("msg=Evt ConfigUpdated (stopped) pipelineId={}", s.pipelineId)
          s.copy(config = newConfig)

        // Invalid state transitions - log warning but don't crash
        case (currentState, event) =>
          log.warn(
            "msg=Invalid state transition pipelineId={} currentState={} event={}",
            event match {
              case e: Event => e.pipelineId
              case _ => "unknown"
            },
            currentState.getClass.getSimpleName,
            event.getClass.getSimpleName
          )
          currentState // Return current state unchanged
      }
  }
}
