package com.dataflow.aggregates

import java.time.Instant
import java.util.UUID

import com.dataflow.domain.commands._
import com.dataflow.domain.events._
import com.dataflow.domain.models._
import com.dataflow.domain.state._
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}

object PipelineAggregate {

  // ============================================
  // COMMANDS - Requests to change state
  // ============================================

  // ============================================
  // EVENT SOURCED BEHAVIOR
  // ============================================

  def apply(pipelineId: String): EventSourcedBehavior[Command, Event, State] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(pipelineId),
      emptyState = EmptyState,
      commandHandler = commandHandler,
      eventHandler = eventHandler,
    )
      .withRetention(
        // Take snapshot every 100 events, keep last 2 snapshots
        RetentionCriteria
          .snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2)
          .withDeleteEventsOnSnapshot,
      )
      .withTagger(event => event.tags)
      .receiveSignal {
        case (state, org.apache.pekko.persistence.typed.RecoveryCompleted) =>
          state match {
            case running: RunningState =>
              println(s"Pipeline $pipelineId recovered in running state. " +
                s"Checkpoint: offset=${running.checkpoint.offset}, " +
                s"records=${running.checkpoint.recordsProcessed}")
            case _                     =>
              println(s"Pipeline $pipelineId recovered: ${state.getClass.getSimpleName}")
          }

        case (state, org.apache.pekko.persistence.typed.SnapshotCompleted(metadata)) =>
          println(s"Snapshot completed for $pipelineId at sequence ${metadata.sequenceNr}")
      }
  }

  // ============================================
  // COMMAND HANDLER - Business Logic
  // ============================================

  private val commandHandler: (State, Command) => ReplyEffect[Event, State] = {
    (state, command) =>
      state match {
        case EmptyState                  => handleEmptyState(command)
        case configured: ConfiguredState => handleConfiguredState(configured, command)
        case running: RunningState       => handleRunningState(running, command)
        case paused: PausedState         => handlePausedState(paused, command)
        case stopped: StoppedState       => handleStoppedState(stopped, command)
        case failed: FailedState         => handleFailedState(failed, command)
      }
  }

  private def handleEmptyState(command: Command): ReplyEffect[Event, State] = {
    command match {
      case CreatePipeline(id, name, desc, source, transforms, sink, replyTo) =>
        // Validation
        if (name.isEmpty) {
          Effect.reply(replyTo)(StatusReply.error("Pipeline name cannot be empty"))
        } else if (source.batchSize <= 0 || source.batchSize > 10000) {
          Effect.reply(replyTo)(StatusReply.error("Batch size must be between 1 and 10000"))
        } else if (transforms.isEmpty) {
          Effect.reply(replyTo)(StatusReply.error("At least one transform is required"))
        } else {
          Effect
            .persist(PipelineCreated(id, name, desc, source, transforms, sink, Instant.now()))
            .thenReply(replyTo)(state => StatusReply.success(state))
        }

      case _ =>
        Effect.reply(command.asInstanceOf[{ def replyTo: ActorRef[StatusReply[State]] }].replyTo)(
          StatusReply.error("Pipeline must be created first"),
        )
    }
  }

  private def handleConfiguredState(
    state: ConfiguredState,
    command: Command,
  ): ReplyEffect[Event, State] = {
    command match {
      case StartPipeline(_, replyTo) =>
        Effect
          .persist(PipelineStarted(state.pipelineId, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case UpdateConfig(_, newConfig, replyTo) =>
        // Can update config when stopped
        Effect
          .persist(ConfigUpdated(state.pipelineId, newConfig, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case _ =>
        Effect.unhandled.thenNoReply()
    }
  }

  private def handleRunningState(
    state: RunningState,
    command: Command,
  ): ReplyEffect[Event, State] = {
    command match {
      case IngestBatch(_, batchId, records, sourceOffset, replyTo) =>
        // IDEMPOTENCY CHECK - Critical for exactly-once semantics
        if (state.processedBatchIds.contains(batchId)) {
          // Already processed - return success without re-processing
          Effect.reply(replyTo)(
            StatusReply.success(BatchResult(batchId, records.size, 0, 0)),
          )
        } else {
          val startTime = System.currentTimeMillis()

          // Simulate processing (in real system, this would be async)
          val successCount = records.size // All succeeded
          val failureCount = 0

          val endTime        = System.currentTimeMillis()
          val processingTime = endTime - startTime

          Effect
            .persist(
              BatchIngested(state.pipelineId, batchId, records.size, sourceOffset, Instant.now()),
              BatchProcessed(state.pipelineId, batchId, successCount, failureCount, processingTime, Instant.now()),
              CheckpointUpdated(
                state.pipelineId,
                Checkpoint(sourceOffset, Instant.now(), state.checkpoint.recordsProcessed + successCount),
                Instant.now(),
              ),
            )
            .thenReply(replyTo)(
              _ =>
                StatusReply.success(BatchResult(batchId, successCount, failureCount, processingTime)),
            )
        }

      case StopPipeline(_, reason, replyTo) =>
        Effect
          .persist(PipelineStopped(state.pipelineId, reason, state.metrics, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case PausePipeline(_, reason, replyTo) =>
        Effect
          .persist(PipelinePaused(state.pipelineId, reason, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case ReportFailure(_, error, replyTo) =>
        if (error.retryable) {
          // Transient error - log but don't fail
          Effect.reply(replyTo)(StatusReply.success(state))
        } else {
          // Fatal error - fail pipeline
          Effect
            .persist(PipelineFailed(state.pipelineId, error, Instant.now()))
            .thenReply(replyTo)(newState => StatusReply.success(newState))
        }

      case UpdateCheckpoint(_, checkpoint) =>
        Effect
          .persist(CheckpointUpdated(state.pipelineId, checkpoint, Instant.now()))
          .thenNoReply()

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case _ =>
        Effect.unhandled.thenNoReply()
    }
  }

  private def handlePausedState(
    state: PausedState,
    command: Command,
  ): ReplyEffect[Event, State] = {
    command match {
      case ResumePipeline(_, replyTo) =>
        Effect
          .persist(PipelineResumed(state.pipelineId, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case StopPipeline(_, reason, replyTo) =>
        Effect
          .persist(PipelineStopped(state.pipelineId, reason, state.metrics, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case IngestBatch(_, _, _, _, replyTo) =>
        Effect.reply(replyTo)(StatusReply.error("Pipeline is paused"))

      case _ =>
        Effect.unhandled.thenNoReply()
    }
  }

  private def handleStoppedState(
    state: StoppedState,
    command: Command,
  ): ReplyEffect[Event, State] = {
    command match {
      case StartPipeline(_, replyTo) =>
        // Restart from last checkpoint
        Effect
          .persist(PipelineStarted(state.pipelineId, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case UpdateConfig(_, newConfig, replyTo) =>
        Effect
          .persist(ConfigUpdated(state.pipelineId, newConfig, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case IngestBatch(_, _, _, _, replyTo) =>
        Effect.reply(replyTo)(StatusReply.error("Pipeline is stopped. Start it first."))

      case _ =>
        Effect.unhandled.thenNoReply()
    }
  }

  private def handleFailedState(
    state: FailedState,
    command: Command,
  ): ReplyEffect[Event, State] = {
    command match {
      case ResetPipeline(_, replyTo) =>
        // Reset to configured state, allowing restart
        Effect
          .persist(PipelineReset(state.pipelineId, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case _ =>
        Effect.reply(command.asInstanceOf[{ def replyTo: ActorRef[Any] }].replyTo)(
          StatusReply.error(s"Pipeline failed: ${state.error.message}. Reset required."),
        )
    }
  }

  // ============================================
  // EVENT HANDLER - State Updates
  // ============================================

  private val eventHandler: (State, Event) => State = {
    (state, event) =>
      (state, event) match {
        case (EmptyState, PipelineCreated(id, name, desc, source, transforms, sink, timestamp)) =>
          ConfiguredState(
            pipelineId = id,
            name = name,
            description = desc,
            config = PipelineConfig(source, transforms, sink),
            createdAt = timestamp,
          )

        case (configured: ConfiguredState, PipelineStarted(_, timestamp)) =>
          RunningState(
            pipelineId = configured.pipelineId,
            name = configured.name,
            description = configured.description,
            config = configured.config,
            startedAt = timestamp,
            checkpoint = Checkpoint.initial,
            metrics = PipelineMetrics.empty,
            processedBatchIds = Set.empty,
          )

        case (running: RunningState, BatchProcessed(_, batchId, success, failed, processingTime, _)) =>
          running.copy(
            metrics = running.metrics.incrementBatch(success, failed, processingTime),
            processedBatchIds = running.processedBatchIds + batchId,
          )

        case (running: RunningState, CheckpointUpdated(_, checkpoint, _)) =>
          running.copy(checkpoint = checkpoint)

        case (running: RunningState, PipelineStopped(_, reason, finalMetrics, timestamp)) =>
          StoppedState(
            pipelineId = running.pipelineId,
            name = running.name,
            description = running.description,
            config = running.config,
            stoppedAt = timestamp,
            stopReason = reason,
            lastCheckpoint = running.checkpoint,
            finalMetrics = finalMetrics,
          )

        case (running: RunningState, PipelinePaused(_, reason, timestamp)) =>
          PausedState(
            pipelineId = running.pipelineId,
            name = running.name,
            description = running.description,
            config = running.config,
            pausedAt = timestamp,
            pauseReason = reason,
            checkpoint = running.checkpoint,
            metrics = running.metrics,
          )

        case (paused: PausedState, PipelineResumed(_, timestamp)) =>
          RunningState(
            pipelineId = paused.pipelineId,
            name = paused.name,
            description = paused.description,
            config = paused.config,
            startedAt = timestamp,
            checkpoint = paused.checkpoint,
            metrics = paused.metrics,
            processedBatchIds = Set.empty,
          )

        case (stopped: StoppedState, PipelineStarted(_, timestamp)) =>
          RunningState(
            pipelineId = stopped.pipelineId,
            name = stopped.name,
            description = stopped.description,
            config = stopped.config,
            startedAt = timestamp,
            checkpoint = stopped.lastCheckpoint,
            metrics = stopped.finalMetrics,
            processedBatchIds = Set.empty,
          )

        case (running: RunningState, PipelineFailed(_, error, timestamp)) =>
          FailedState(
            pipelineId = running.pipelineId,
            name = running.name,
            description = running.description,
            error = error,
            failedAt = timestamp,
            lastCheckpoint = Some(running.checkpoint),
          )

        case (failed: FailedState, PipelineReset(_, timestamp)) =>
          ConfiguredState(
            pipelineId = failed.pipelineId,
            name = failed.name,
            description = failed.description,
            config = PipelineConfig(
              SourceConfig("", "", 0, 0),
              List.empty,
              SinkConfig("", "", 0),
            ),
            createdAt = timestamp,
          )

        case (configured: ConfiguredState, ConfigUpdated(_, newConfig, _)) =>
          configured.copy(config = newConfig)

        case (stopped: StoppedState, ConfigUpdated(_, newConfig, _)) =>
          stopped.copy(config = newConfig)

        case _ => state
      }
  }
}
