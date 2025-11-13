package com.dataflow.aggregates

import com.dataflow.aggregates.handlers._
import com.dataflow.domain.commands._
import com.dataflow.domain.events._
import com.dataflow.domain.models._
import com.dataflow.domain.state._
import com.dataflow.metrics.MetricsReporter
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
  // EVENT HANDLER - State Updates
  // ============================================

  private val eventHandler: (State, Event) => State = {
    (state, event) =>
      event match {
        case PipelineCreated(id, name, desc, config, ts) =>
          log.debug("msg=Evt PipelineCreated id={} name='{}'", id, name)
          ConfiguredState(
            pipelineId = id,
            name = name,
            description = desc,
            config = config,
            createdAt = ts,
          )

        case PipelineStarted(_, ts) =>
          val cfg      = state.asInstanceOf[ConfiguredState]
          log.debug("msg=Evt PipelineStarted pipelineId={}", cfg.pipelineId)
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
          MetricsReporter.recordStateTransition(cfg.pipelineId, state, newState)
          newState

        case BatchIngested(_, batchId, _, _, _) =>
          val r = state.asInstanceOf[RunningState]
          r.copy(activeBatchId = Some(batchId))

        case BatchProcessed(pipelineId, batchId, ok, ko, timeMs, _) =>
          val r          = state.asInstanceOf[RunningState]
          // Record batch processing metrics
          MetricsReporter.recordBatchProcessed(pipelineId, ok, ko, timeMs)
          val newMetrics = r.metrics.incrementBatch(ok, ko, timeMs)
          MetricsReporter.updatePipelineMetrics(pipelineId, newMetrics)
          r.copy(
            metrics = newMetrics,
            processedBatchIds = r.processedBatchIds + batchId,
            activeBatchId = None,
            retryCount = ErrorRecovery.resetRetryCount(),
          )

        case CheckpointUpdated(pipelineId, checkpoint, _) =>
          state match {
            case r: RunningState =>
              MetricsReporter.recordCheckpointUpdate(pipelineId, checkpoint.offset)
              r.copy(checkpoint = checkpoint)
            case s               => s
          }

        case RetryScheduled(pipelineId, error, retryCount, _, _) =>
          state match {
            case r: RunningState =>
              log.debug("msg=Evt RetryScheduled pipelineId={} retryCount={}", r.pipelineId, retryCount)
              MetricsReporter.recordRetryScheduled(pipelineId, error.code, retryCount)
              r.copy(retryCount = retryCount)
            case s               => s
          }

        case BatchTimedOut(pipelineId, batchId, timeoutMs, _) =>
          state match {
            case r: RunningState =>
              log.warn("msg=Evt BatchTimedOut pipelineId={} batchId={} timeoutMs={}", r.pipelineId, batchId, timeoutMs)
              MetricsReporter.recordBatchTimeout(pipelineId, batchId)
              r.copy(activeBatchId = None)
            case s               => s
          }

        case PipelineStopped(_, reason, finalMetrics, ts) =>
          val r        = state.asInstanceOf[RunningState]
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
          MetricsReporter.recordStateTransition(r.pipelineId, state, newState)
          newState

        case PipelinePaused(_, reason, ts) =>
          val r        = state.asInstanceOf[RunningState]
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
          MetricsReporter.recordStateTransition(r.pipelineId, state, newState)
          newState

        case PipelineResumed(_, ts) =>
          val p        = state.asInstanceOf[PausedState]
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
          MetricsReporter.recordStateTransition(p.pipelineId, state, newState)
          newState

        case PipelineFailed(_, error, ts) =>
          val r        = state.asInstanceOf[RunningState]
          log.error("msg=Evt PipelineFailed pipelineId={} code={} message={}", r.pipelineId, error.code, error.message)
          MetricsReporter.recordBatchFailed(r.pipelineId, error.code)
          val newState = FailedState(
            pipelineId = r.pipelineId,
            name = r.name,
            description = r.description,
            error = error,
            failedAt = ts,
            lastCheckpoint = Some(r.checkpoint),
          )
          MetricsReporter.recordStateTransition(r.pipelineId, state, newState)
          newState

        case PipelineReset(_, ts) =>
          val f = state.asInstanceOf[FailedState]
          log.info("msg=Evt PipelineReset pipelineId={}", f.pipelineId)
          ConfiguredState(
            pipelineId = f.pipelineId,
            name = f.name,
            description = f.description,
            config = PipelineConfig(SourceConfig( SourceType.fromString("kafka").getOrElse(SourceType.File), "", 0), List.empty, SinkConfig("", "", 0)),
            createdAt = ts,
          )

        case ConfigUpdated(_, newConfig, _) =>
          state match {
            case c: ConfiguredState =>
              log.debug("msg=Evt ConfigUpdated pipelineId={}", c.pipelineId)
              c.copy(config = newConfig)
            case s: StoppedState    =>
              log.debug("msg=Evt ConfigUpdated (stopped) pipelineId={}", s.pipelineId)
              s.copy(config = newConfig)
            case s                  => s
          }

        case other =>
          // No state change
          log.debug("msg=Evt ignored type={}", other.getClass.getSimpleName)
          state
      }
  }
}
