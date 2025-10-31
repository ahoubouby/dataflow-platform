package com.dataflow.aggregates

import java.time.Instant

import scala.util._

import com.dataflow.domain.commands._
import com.dataflow.domain.events._
import com.dataflow.domain.models._
import com.dataflow.domain.state._
import com.dataflow.recovery.{ErrorRecovery, TimeoutConfig}
import com.dataflow.validation.{PipelineValidators, ValidationHelper}
import com.wix.accord._
import com.wix.accord.{Failure => VFailure, Success => VSuccess}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.{RecoveryCompleted, SnapshotCompleted}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
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
  // COMMAND HANDLER - Business Logic
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

    state match {
      case EmptyState         => handleEmptyState(command)
      case s: ConfiguredState => handleConfiguredState(s, command)
      case s: RunningState    => handleRunningState(s, command)
      case s: PausedState     => handlePausedState(s, command)
      case s: StoppedState    => handleStoppedState(s, command)
      case s: FailedState     => handleFailedState(s, command)
    }
  }

  private def handleEmptyState(command: Command): ReplyEffect[Event, State] = {
    import PipelineValidators._

    command match {
      case cmd @ CreatePipeline(id, name, desc, source, transforms, sink, replyTo) =>
        validate(cmd) match {
          case VSuccess =>
            log.info("msg=Create pipeline id={} name='{}'", id, name)
            Effect
              .persist(PipelineCreated(id, name, desc, source, transforms, sink, Instant.now()))
              .thenReply(replyTo)(state => StatusReply.success(state))

          case VFailure(violations) =>
            val err = ValidationHelper.formatViolations(violations)
            log.warn("msg=Create validation failed id={} error={}", cmd.pipelineId, err)
            Effect.reply(replyTo)(StatusReply.error(s"Validation failed: $err"))
        }

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(EmptyState)

      case other =>
        log.warn("msg=Command invalid in EmptyState cmd={}", other.getClass.getSimpleName)
        Effect.noReply
    }
  }

  private def handleConfiguredState(
    state: ConfiguredState,
    command: Command,
  ): ReplyEffect[Event, State] = {
    import PipelineValidators._

    command match {
      case StartPipeline(_, replyTo) =>
        log.info("msg=Start pipeline pipelineId={}", state.pipelineId)
        Effect
          .persist(PipelineStarted(state.pipelineId, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case UpdateConfig(_, newConfig, replyTo) =>
        validate(newConfig) match {
          case VSuccess =>
            log.info("msg=Update config pipelineId={}", state.pipelineId)
            Effect
              .persist(ConfigUpdated(state.pipelineId, newConfig, Instant.now()))
              .thenReply(replyTo)(newState => StatusReply.success(newState))

          case VFailure(violations) =>
            val err = ValidationHelper.formatViolations(violations)
            log.warn("msg=Config validation failed pipelineId={} error={}", state.pipelineId, err)
            Effect.reply(replyTo)(StatusReply.error(s"Validation failed: $err"))
        }

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case other =>
        log.warn(
          "msg=Command invalid in ConfiguredState pipelineId={} cmd={}",
          state.pipelineId,
          other.getClass.getSimpleName,
        )
        Effect.noReply
    }
  }

  private def handleRunningState(
    state: RunningState,
    command: Command,
  ): ReplyEffect[Event, State] = {
    import PipelineValidators._

    command match {
      case cmd @ IngestBatch(_, batchId, records, sourceOffset, replyTo) =>
        validate(cmd) match {
          case VFailure(violations) =>
            val err = ValidationHelper.formatViolations(violations)
            log.warn("msg=Batch validation failed pipelineId={} batchId={} error={}", state.pipelineId, batchId, err)
            Effect.reply(replyTo)(StatusReply.error(s"Validation failed: $err"))

          case VSuccess =>
            if (state.processedBatchIds.contains(batchId)) {
              log.debug("msg=Idempotent batch pipelineId={} batchId={}", state.pipelineId, batchId)
              Effect.reply(replyTo)(StatusReply.success(BatchResult(batchId, records.size, 0, 0)))
            } else {
              val t0           = System.nanoTime()
              val successCount = records.size
              val failureCount = 0
              val elapsedMs    = (System.nanoTime() - t0) / 1000000L

              // reset retry count after success
              val _ = ErrorRecovery.resetRetryCount()

              log.info(
                "msg=Processed batch pipelineId={} batchId={} records={} ok={} ko={} timeMs={}",
                state.pipelineId,
                batchId,
                records.size,
                successCount,
                failureCount,
                elapsedMs,
              )

              Effect
                .persist(Seq(
                  BatchIngested(state.pipelineId, batchId, records.size, sourceOffset, Instant.now()),
                  BatchProcessed(state.pipelineId, batchId, successCount, failureCount, elapsedMs, Instant.now()),
                  CheckpointUpdated(
                    state.pipelineId,
                    Checkpoint(sourceOffset, Instant.now(), state.checkpoint.recordsProcessed + successCount),
                    Instant.now(),
                  ),
                ))
                .thenReply(replyTo)(_ => StatusReply.success(BatchResult(batchId, successCount, failureCount, elapsedMs)))
            }
        }

      case StopPipeline(_, reason, replyTo) =>
        log.info("msg=Stop pipeline pipelineId={} reason={}", state.pipelineId, reason)
        Effect
          .persist(PipelineStopped(state.pipelineId, reason, state.metrics, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case PausePipeline(_, reason, replyTo) =>
        log.info("msg=Pause pipeline pipelineId={} reason={}", state.pipelineId, reason)
        Effect
          .persist(PipelinePaused(state.pipelineId, reason, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case ReportFailure(_, error, replyTo) =>
        validate(error) match {
          case VFailure(violations) =>
            val err = ValidationHelper.formatViolations(violations)
            log.warn("msg=Error validation failed pipelineId={} error={}", state.pipelineId, err)
            Effect.reply(replyTo)(StatusReply.error(s"Invalid error: $err"))

          case VSuccess =>
            if (ErrorRecovery.shouldRetry(error.code, state.retryCount, retryConfig.maxRetries)) {
              val newRetryCount = ErrorRecovery.incrementRetryCount(state.retryCount)
              val backoff       = ErrorRecovery.calculateExponentialBackoff(state.retryCount, retryConfig)
              log.warn(
                "msg=Transient error retry pipelineId={} code={} retryCount={} backoffMs={}",
                state.pipelineId,
                error.code,
                newRetryCount,
                backoff,
              )
              Effect
                .persist(RetryScheduled(state.pipelineId, error, newRetryCount, backoff, Instant.now()))
                .thenReply(replyTo)(newState => StatusReply.success(newState))
            } else {
              if (state.retryCount >= retryConfig.maxRetries)
                log.error(
                  "msg=Retries exhausted pipelineId={} code={} message={}",
                  state.pipelineId,
                  error.code,
                  error.message,
                )
              else
                log.error(
                  "msg=Non-retryable error pipelineId={} code={} message={}",
                  state.pipelineId,
                  error.code,
                  error.message,
                )

              Effect
                .persist(PipelineFailed(state.pipelineId, error, Instant.now()))
                .thenReply(replyTo)(newState => StatusReply.success(newState))
            }
        }

      case UpdateCheckpoint(_, checkpoint) =>
        log.debug("msg=Update checkpoint pipelineId={} offset={}", state.pipelineId, checkpoint.offset)
        Effect.persist(CheckpointUpdated(state.pipelineId, checkpoint, Instant.now())).thenNoReply()

      case BatchTimeout(_, batchId) =>
        if (state.activeBatchId.contains(batchId)) {
          log.error(
            "msg=Batch timeout pipelineId={} batchId={} timeoutMs={}",
            state.pipelineId,
            batchId,
            timeoutConfig.batchTimeout.toMillis,
          )
          Effect.persist(
            BatchTimedOut(state.pipelineId, batchId, timeoutConfig.batchTimeout.toMillis, Instant.now()),
          ).thenNoReply()
        } else {
          log.debug("msg=Ignore timeout (already completed) pipelineId={} batchId={}", state.pipelineId, batchId)
          Effect.noReply
        }

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case other =>
        log.warn(
          "msg=Command invalid in RunningState pipelineId={} cmd={}",
          state.pipelineId,
          other.getClass.getSimpleName,
        )
        Effect.noReply
    }
  }

  private def handlePausedState(
    state: PausedState,
    command: Command,
  ): ReplyEffect[Event, State] = command match {
    case ResumePipeline(_, replyTo) =>
      log.info("msg=Resume pipeline pipelineId={}", state.pipelineId)
      Effect.persist(PipelineResumed(state.pipelineId, Instant.now())).thenReply(replyTo)(ns => StatusReply.success(ns))

    case StopPipeline(_, reason, replyTo) =>
      log.info("msg=Stop paused pipeline pipelineId={} reason={}", state.pipelineId, reason)
      Effect.persist(PipelineStopped(state.pipelineId, reason, state.metrics, Instant.now()))
        .thenReply(replyTo)(ns => StatusReply.success(ns))

    case GetState(_, replyTo) =>
      Effect.reply(replyTo)(state)

    case IngestBatch(_, _, _, _, replyTo) =>
      log.warn("msg=Ingest while paused pipelineId={}", state.pipelineId)
      Effect.reply(replyTo)(StatusReply.error("Pipeline is paused"))

    case other =>
      log.warn("msg=Command invalid in PausedState pipelineId={} cmd={}", state.pipelineId, other.getClass.getSimpleName)
      Effect.noReply
  }

  private def handleStoppedState(
    state: StoppedState,
    command: Command,
  ): ReplyEffect[Event, State] = {
    import PipelineValidators._

    command match {
      case StartPipeline(_, replyTo) =>
        log.info("msg=Restart pipeline pipelineId={} offset={}", state.pipelineId, state.lastCheckpoint.offset)
        Effect.persist(PipelineStarted(state.pipelineId, Instant.now())).thenReply(replyTo)(ns => StatusReply.success(ns))

      case UpdateConfig(_, newConfig, replyTo) =>
        validate(newConfig) match {
          case VSuccess             =>
            log.info("msg=Update config (stopped) pipelineId={}", state.pipelineId)
            Effect.persist(ConfigUpdated(state.pipelineId, newConfig, Instant.now()))
              .thenReply(replyTo)(ns => StatusReply.success(ns))
          case VFailure(violations) =>
            val err = ValidationHelper.formatViolations(violations)
            log.warn("msg=Config validation failed (stopped) pipelineId={} error={}", state.pipelineId, err)
            Effect.reply(replyTo)(StatusReply.error(s"Validation failed: $err"))
        }

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case IngestBatch(_, _, _, _, replyTo) =>
        log.warn("msg=Ingest while stopped pipelineId={}", state.pipelineId)
        Effect.reply(replyTo)(StatusReply.error("Pipeline is stopped. Start it first."))

      case other =>
        log.warn(
          "msg=Command invalid in StoppedState pipelineId={} cmd={}",
          state.pipelineId,
          other.getClass.getSimpleName,
        )
        Effect.noReply
    }
  }

  private def handleFailedState(
    state: FailedState,
    command: Command,
  ): ReplyEffect[Event, State] = command match {
    case ResetPipeline(_, replyTo) =>
      log.info("msg=Reset failed pipeline pipelineId={}", state.pipelineId)
      Effect.persist(PipelineReset(state.pipelineId, Instant.now())).thenReply(replyTo)(ns => StatusReply.success(ns))

    case GetState(_, replyTo) =>
      Effect.reply(replyTo)(state)

    case other =>
      log.warn(
        "msg=Command invalid in FailedState pipelineId={} cmd={} error={}",
        state.pipelineId,
        other.getClass.getSimpleName,
        state.error.message,
      )
      // If you want to always answer when invalid:
      other match {
        case c: CommandWithReply[_] => // <-- OPTIONAL: if you have such a trait in your model
          Effect.reply(c.replyTo.asInstanceOf[ActorRef[StatusReply[Any]]])(
            StatusReply.error(s"Pipeline failed: ${state.error.message}. Reset required."),
          )
        case _                      =>
          Effect.noReply
      }
  }

  // ============================================
  // EVENT HANDLER - State Updates
  // ============================================

  private val eventHandler: (State, Event) => State = {
    (state, event) =>
      event match {
        case PipelineCreated(id, name, desc, source, transforms, sink, ts) =>
          log.debug("msg=Evt PipelineCreated id={} name='{}'", id, name)
          ConfiguredState(
            pipelineId = id,
            name = name,
            description = desc,
            config = PipelineConfig(source, transforms, sink),
            createdAt = ts,
          )

        case PipelineStarted(_, ts) =>
          val cfg = state.asInstanceOf[ConfiguredState]
          log.debug("msg=Evt PipelineStarted pipelineId={}", cfg.pipelineId)
          RunningState(
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

        case BatchIngested(_, batchId, _, _, _) =>
          val r = state.asInstanceOf[RunningState]
          r.copy(activeBatchId = Some(batchId))

        case BatchProcessed(_, batchId, ok, ko, timeMs, _) =>
          val r = state.asInstanceOf[RunningState]
          r.copy(
            metrics = r.metrics.incrementBatch(ok, ko, timeMs),
            processedBatchIds = r.processedBatchIds + batchId,
            activeBatchId = None,
            retryCount = ErrorRecovery.resetRetryCount(),
          )

        case CheckpointUpdated(_, checkpoint, _) =>
          state match {
            case r: RunningState => r.copy(checkpoint = checkpoint)
            case s               => s
          }

        case RetryScheduled(_, _, retryCount, _, _) =>
          state match {
            case r: RunningState =>
              log.debug("msg=Evt RetryScheduled pipelineId={} retryCount={}", r.pipelineId, retryCount)
              r.copy(retryCount = retryCount)
            case s               => s
          }

        case BatchTimedOut(_, batchId, timeoutMs, _) =>
          state match {
            case r: RunningState =>
              log.warn("msg=Evt BatchTimedOut pipelineId={} batchId={} timeoutMs={}", r.pipelineId, batchId, timeoutMs)
              r.copy(activeBatchId = None)
            case s               => s
          }

        case PipelineStopped(_, reason, finalMetrics, ts) =>
          val r = state.asInstanceOf[RunningState]
          log.debug("msg=Evt PipelineStopped pipelineId={} reason={}", r.pipelineId, reason)
          StoppedState(
            pipelineId = r.pipelineId,
            name = r.name,
            description = r.description,
            config = r.config,
            stoppedAt = ts,
            stopReason = reason,
            lastCheckpoint = r.checkpoint,
            finalMetrics = finalMetrics,
          )

        case PipelinePaused(_, reason, ts) =>
          val r = state.asInstanceOf[RunningState]
          log.debug("msg=Evt PipelinePaused pipelineId={} reason={}", r.pipelineId, reason)
          PausedState(
            pipelineId = r.pipelineId,
            name = r.name,
            description = r.description,
            config = r.config,
            pausedAt = ts,
            pauseReason = reason,
            checkpoint = r.checkpoint,
            metrics = r.metrics,
          )

        case PipelineResumed(_, ts) =>
          val p = state.asInstanceOf[PausedState]
          log.debug("msg=Evt PipelineResumed pipelineId={}", p.pipelineId)
          RunningState(
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

        case PipelineFailed(_, error, ts) =>
          val r = state.asInstanceOf[RunningState]
          log.error("msg=Evt PipelineFailed pipelineId={} code={} message={}", r.pipelineId, error.code, error.message)
          FailedState(
            pipelineId = r.pipelineId,
            name = r.name,
            description = r.description,
            error = error,
            failedAt = ts,
            lastCheckpoint = Some(r.checkpoint),
          )

        case PipelineReset(_, ts) =>
          val f = state.asInstanceOf[FailedState]
          log.info("msg=Evt PipelineReset pipelineId={}", f.pipelineId)
          ConfiguredState(
            pipelineId = f.pipelineId,
            name = f.name,
            description = f.description,
            config = PipelineConfig(SourceConfig("", "", 0, 0), List.empty, SinkConfig("", "", 0)),
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
