package com.dataflow.aggregates

import java.time.Instant
import com.dataflow.domain.commands._
import com.dataflow.domain.events._
import com.dataflow.domain.models._
import com.dataflow.domain.state._
import com.dataflow.validation.{PipelineValidators, ValidationHelper}
import com.dataflow.recovery.{ErrorRecovery, TimeoutConfig}
import com.wix.accord._
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import scala.concurrent.duration._

object PipelineAggregate {

  // ============================================
  // CONFIGURATION
  // ============================================

  private val timeoutConfig = TimeoutConfig.Default
  private val retryConfig = ErrorRecovery.DefaultRetryConfig

  // ============================================
  // EVENT SOURCED BEHAVIOR
  // ============================================

  def apply(pipelineId: String): EventSourcedBehavior[Command, Event, State] = {
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(pipelineId),
        emptyState = EmptyState,
        commandHandler = (state, command) => commandHandler(pipelineId, state, command),
        eventHandler = eventHandler
      )
      .withRetention(
        // Take snapshot every 100 events, keep last 2 snapshots
        RetentionCriteria
          .snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2)
          .withDeleteEventsOnSnapshot
      )
      .withTagger(event => event.tags)
      .receiveSignal {
        case (state, org.apache.pekko.persistence.typed.RecoveryCompleted) =>
          state match {
            case running: RunningState =>
              // Use context.log for structured logging
              org.slf4j.LoggerFactory.getLogger(getClass).info(
                "Pipeline {} recovered in running state. Checkpoint: offset={}, records={}",
                pipelineId,
                running.checkpoint.offset,
                running.checkpoint.recordsProcessed
              )
            case _ =>
              org.slf4j.LoggerFactory.getLogger(getClass).info(
                "Pipeline {} recovered: {}",
                pipelineId,
                state.getClass.getSimpleName
              )
          }

        case (state, org.apache.pekko.persistence.typed.SnapshotCompleted(metadata)) =>
          org.slf4j.LoggerFactory.getLogger(getClass).info(
            "Snapshot completed for {} at sequence {}",
            pipelineId,
            metadata.sequenceNr
          )
      }
  }

  // ============================================
  // COMMAND HANDLER - Business Logic
  // ============================================

  private def commandHandler(
    pipelineId: String,
    state: State,
    command: Command
  ): ReplyEffect[Event, State] = {
    val log = org.slf4j.LoggerFactory.getLogger(getClass)

    log.debug("Handling command {} in state {}", command.getClass.getSimpleName, state.getClass.getSimpleName)

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
    val log = org.slf4j.LoggerFactory.getLogger(getClass)

    command match {
      case cmd @ CreatePipeline(id, name, desc, source, transforms, sink, replyTo) =>
        // Use comprehensive validation
        import PipelineValidators._

        validate(cmd) match {
          case Success =>
            log.info("Creating pipeline {} with name '{}'", id, name)
            Effect
              .persist(PipelineCreated(id, name, desc, source, transforms, sink, Instant.now()))
              .thenReply(replyTo)(state => StatusReply.success(state))

          case Failure(violations) =>
            val errorMessage = ValidationHelper.formatViolations(violations)
            log.warn("Pipeline creation validation failed: {}", errorMessage)
            Effect.reply(replyTo)(StatusReply.error(s"Validation failed: $errorMessage"))
        }

      case _ =>
        Effect.reply(command.asInstanceOf[{ def replyTo: ActorRef[StatusReply[State]] }].replyTo)(
          StatusReply.error("Pipeline must be created first")
        )
    }
  }

  private def handleConfiguredState(
    state: ConfiguredState,
    command: Command
  ): ReplyEffect[Event, State] = {
    val log = org.slf4j.LoggerFactory.getLogger(getClass)

    command match {
      case StartPipeline(_, replyTo) =>
        log.info("Starting pipeline {}", state.pipelineId)
        Effect
          .persist(PipelineStarted(state.pipelineId, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case UpdateConfig(_, newConfig, replyTo) =>
        import PipelineValidators._

        validate(newConfig) match {
          case Success =>
            log.info("Updating configuration for pipeline {}", state.pipelineId)
            Effect
              .persist(ConfigUpdated(state.pipelineId, newConfig, Instant.now()))
              .thenReply(replyTo)(newState => StatusReply.success(newState))

          case Failure(violations) =>
            val errorMessage = ValidationHelper.formatViolations(violations)
            log.warn("Configuration update validation failed: {}", errorMessage)
            Effect.reply(replyTo)(StatusReply.error(s"Validation failed: $errorMessage"))
        }

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case _ =>
        Effect.unhandled.thenNoReply()
    }
  }

  private def handleRunningState(
    state: RunningState,
    command: Command
  ): ReplyEffect[Event, State] = {
    val log = org.slf4j.LoggerFactory.getLogger(getClass)

    command match {
      case cmd @ IngestBatch(_, batchId, records, sourceOffset, replyTo) =>
        // Validate batch
        import PipelineValidators._

        validate(cmd) match {
          case Failure(violations) =>
            val errorMessage = ValidationHelper.formatViolations(violations)
            log.warn("Batch validation failed for pipeline {}: {}", state.pipelineId, errorMessage)
            Effect.reply(replyTo)(StatusReply.error(s"Validation failed: $errorMessage"))

          case Success =>
            // IDEMPOTENCY CHECK - Critical for exactly-once semantics
            if (state.processedBatchIds.contains(batchId)) {
              log.debug("Batch {} already processed for pipeline {} (idempotency)", batchId, state.pipelineId)
              Effect.reply(replyTo)(
                StatusReply.success(BatchResult(batchId, records.size, 0, 0))
              )
            } else {
              log.debug("Processing batch {} with {} records for pipeline {}", batchId, records.size, state.pipelineId)

              val startTime = System.currentTimeMillis()

              // Simulate processing (in real system, this would be async)
              val successCount = records.size
              val failureCount = 0

              val endTime = System.currentTimeMillis()
              val processingTime = endTime - startTime

              // Reset retry count on successful processing
              val updatedRetryCount = ErrorRecovery.resetRetryCount()

              log.info("Successfully processed batch {} with {} records in {}ms for pipeline {}",
                batchId, successCount, processingTime, state.pipelineId)

              Effect
                .persist(
                  BatchIngested(state.pipelineId, batchId, records.size, sourceOffset, Instant.now()),
                  BatchProcessed(state.pipelineId, batchId, successCount, failureCount, processingTime, Instant.now()),
                  CheckpointUpdated(
                    state.pipelineId,
                    Checkpoint(sourceOffset, Instant.now(), state.checkpoint.recordsProcessed + successCount),
                    Instant.now()
                  )
                )
                .thenReply(replyTo)(_ =>
                  StatusReply.success(BatchResult(batchId, successCount, failureCount, processingTime))
                )
            }
        }

      case StopPipeline(_, reason, replyTo) =>
        log.info("Stopping pipeline {} with reason: {}", state.pipelineId, reason)
        Effect
          .persist(PipelineStopped(state.pipelineId, reason, state.metrics, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case PausePipeline(_, reason, replyTo) =>
        log.info("Pausing pipeline {} with reason: {}", state.pipelineId, reason)
        Effect
          .persist(PipelinePaused(state.pipelineId, reason, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case ReportFailure(_, error, replyTo) =>
        import PipelineValidators._

        validate(error) match {
          case Failure(violations) =>
            val errorMessage = ValidationHelper.formatViolations(violations)
            log.warn("Error validation failed: {}", errorMessage)
            Effect.reply(replyTo)(StatusReply.error(s"Invalid error: $errorMessage"))

          case Success =>
            // Implement retry logic with exponential backoff
            if (ErrorRecovery.shouldRetry(error.code, state.retryCount, retryConfig.maxRetries)) {
              val newRetryCount = ErrorRecovery.incrementRetryCount(state.retryCount)
              val backoff = ErrorRecovery.calculateExponentialBackoff(state.retryCount, retryConfig)

              log.warn("Transient error {} in pipeline {}. {} (backoff: {}ms)",
                error.code, state.pipelineId,
                ErrorRecovery.retryDescription(newRetryCount, backoff), backoff)

              Effect
                .persist(RetryScheduled(state.pipelineId, error, newRetryCount, backoff, Instant.now()))
                .thenReply(replyTo)(newState => StatusReply.success(newState))
            } else {
              // Fatal error or exhausted retries
              if (state.retryCount >= retryConfig.maxRetries) {
                log.error("Pipeline {} failed after {} retry attempts with error: {}",
                  state.pipelineId, state.retryCount, error.message)
              } else {
                log.error("Pipeline {} failed with non-retryable error {}: {}",
                  state.pipelineId, error.code, error.message)
              }

              Effect
                .persist(PipelineFailed(state.pipelineId, error, Instant.now()))
                .thenReply(replyTo)(newState => StatusReply.success(newState))
            }
        }

      case UpdateCheckpoint(_, checkpoint) =>
        log.debug("Updating checkpoint for pipeline {} to offset {}", state.pipelineId, checkpoint.offset)
        Effect
          .persist(CheckpointUpdated(state.pipelineId, checkpoint, Instant.now()))
          .thenNoReply()

      case BatchTimeout(_, batchId) =>
        if (state.activeBatchId.contains(batchId)) {
          log.error("Batch {} timed out in pipeline {} after {}ms",
            batchId, state.pipelineId, timeoutConfig.batchTimeout.toMillis)

          Effect
            .persist(BatchTimedOut(state.pipelineId, batchId, timeoutConfig.batchTimeout.toMillis, Instant.now()))
            .thenNoReply()
        } else {
          // Batch completed before timeout - ignore
          log.debug("Ignoring timeout for completed batch {} in pipeline {}", batchId, state.pipelineId)
          Effect.none
        }

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case _ =>
        Effect.unhandled.thenNoReply()
    }
  }

  private def handlePausedState(
    state: PausedState,
    command: Command
  ): ReplyEffect[Event, State] = {
    val log = org.slf4j.LoggerFactory.getLogger(getClass)

    command match {
      case ResumePipeline(_, replyTo) =>
        log.info("Resuming pipeline {}", state.pipelineId)
        Effect
          .persist(PipelineResumed(state.pipelineId, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case StopPipeline(_, reason, replyTo) =>
        log.info("Stopping paused pipeline {} with reason: {}", state.pipelineId, reason)
        Effect
          .persist(PipelineStopped(state.pipelineId, reason, state.metrics, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case IngestBatch(_, _, _, _, replyTo) =>
        log.warn("Attempted to ingest batch while pipeline {} is paused", state.pipelineId)
        Effect.reply(replyTo)(StatusReply.error("Pipeline is paused"))

      case _ =>
        Effect.unhandled.thenNoReply()
    }
  }

  private def handleStoppedState(
    state: StoppedState,
    command: Command
  ): ReplyEffect[Event, State] = {
    val log = org.slf4j.LoggerFactory.getLogger(getClass)

    command match {
      case StartPipeline(_, replyTo) =>
        log.info("Restarting pipeline {} from checkpoint offset {}",
          state.pipelineId, state.lastCheckpoint.offset)
        Effect
          .persist(PipelineStarted(state.pipelineId, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case UpdateConfig(_, newConfig, replyTo) =>
        import PipelineValidators._

        validate(newConfig) match {
          case Success =>
            log.info("Updating configuration for stopped pipeline {}", state.pipelineId)
            Effect
              .persist(ConfigUpdated(state.pipelineId, newConfig, Instant.now()))
              .thenReply(replyTo)(newState => StatusReply.success(newState))

          case Failure(violations) =>
            val errorMessage = ValidationHelper.formatViolations(violations)
            log.warn("Configuration update validation failed: {}", errorMessage)
            Effect.reply(replyTo)(StatusReply.error(s"Validation failed: $errorMessage"))
        }

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case IngestBatch(_, _, _, _, replyTo) =>
        log.warn("Attempted to ingest batch while pipeline {} is stopped", state.pipelineId)
        Effect.reply(replyTo)(StatusReply.error("Pipeline is stopped. Start it first."))

      case _ =>
        Effect.unhandled.thenNoReply()
    }
  }

  private def handleFailedState(
    state: FailedState,
    command: Command
  ): ReplyEffect[Event, State] = {
    val log = org.slf4j.LoggerFactory.getLogger(getClass)

    command match {
      case ResetPipeline(_, replyTo) =>
        log.info("Resetting failed pipeline {} to configured state", state.pipelineId)
        Effect
          .persist(PipelineReset(state.pipelineId, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(state)

      case _ =>
        log.warn("Pipeline {} is in failed state. Reset required. Error: {}",
          state.pipelineId, state.error.message)
        Effect.reply(command.asInstanceOf[{ def replyTo: ActorRef[Any] }].replyTo)(
          StatusReply.error(s"Pipeline failed: ${state.error.message}. Reset required.")
        )
    }
  }

  // ============================================
  // EVENT HANDLER - State Updates
  // ============================================

  private val eventHandler: (State, Event) => State = {
    (state, event) =>
      val log = org.slf4j.LoggerFactory.getLogger(getClass)

      (state, event) match {
        case (EmptyState, PipelineCreated(id, name, desc, source, transforms, sink, timestamp)) =>
          log.debug("Pipeline {} created with name '{}'", id, name)
          ConfiguredState(
            pipelineId = id,
            name = name,
            description = desc,
            config = PipelineConfig(source, transforms, sink),
            createdAt = timestamp
          )

        case (configured: ConfiguredState, PipelineStarted(_, timestamp)) =>
          log.debug("Pipeline {} started", configured.pipelineId)
          RunningState(
            pipelineId = configured.pipelineId,
            name = configured.name,
            description = configured.description,
            config = configured.config,
            startedAt = timestamp,
            checkpoint = Checkpoint.initial,
            metrics = PipelineMetrics.empty,
            processedBatchIds = Set.empty,
            retryCount = 0,
            activeBatchId = None
          )

        case (running: RunningState, BatchIngested(_, batchId, _, _, _)) =>
          // Track active batch for timeout detection
          running.copy(activeBatchId = Some(batchId))

        case (running: RunningState, BatchProcessed(_, batchId, success, failed, processingTime, _)) =>
          running.copy(
            metrics = running.metrics.incrementBatch(success, failed, processingTime),
            processedBatchIds = running.processedBatchIds + batchId,
            activeBatchId = None, // Clear active batch after successful processing
            retryCount = ErrorRecovery.resetRetryCount() // Reset retry count on success
          )

        case (running: RunningState, CheckpointUpdated(_, checkpoint, _)) =>
          running.copy(checkpoint = checkpoint)

        case (running: RunningState, RetryScheduled(_, error, retryCount, backoffMs, _)) =>
          log.debug("Retry {} scheduled for pipeline {} with backoff {}ms",
            retryCount, running.pipelineId, backoffMs)
          running.copy(retryCount = retryCount)

        case (running: RunningState, BatchTimedOut(_, batchId, timeoutMs, _)) =>
          log.warn("Batch {} timed out in pipeline {} after {}ms",
            batchId, running.pipelineId, timeoutMs)
          // Clear the timed-out batch
          running.copy(activeBatchId = None)

        case (running: RunningState, PipelineStopped(_, reason, finalMetrics, timestamp)) =>
          log.debug("Pipeline {} stopped: {}", running.pipelineId, reason)
          StoppedState(
            pipelineId = running.pipelineId,
            name = running.name,
            description = running.description,
            config = running.config,
            stoppedAt = timestamp,
            stopReason = reason,
            lastCheckpoint = running.checkpoint,
            finalMetrics = finalMetrics
          )

        case (running: RunningState, PipelinePaused(_, reason, timestamp)) =>
          log.debug("Pipeline {} paused: {}", running.pipelineId, reason)
          PausedState(
            pipelineId = running.pipelineId,
            name = running.name,
            description = running.description,
            config = running.config,
            pausedAt = timestamp,
            pauseReason = reason,
            checkpoint = running.checkpoint,
            metrics = running.metrics
          )

        case (paused: PausedState, PipelineResumed(_, timestamp)) =>
          log.debug("Pipeline {} resumed", paused.pipelineId)
          RunningState(
            pipelineId = paused.pipelineId,
            name = paused.name,
            description = paused.description,
            config = paused.config,
            startedAt = timestamp,
            checkpoint = paused.checkpoint,
            metrics = paused.metrics,
            processedBatchIds = Set.empty,
            retryCount = 0,
            activeBatchId = None
          )

        case (stopped: StoppedState, PipelineStarted(_, timestamp)) =>
          log.debug("Pipeline {} restarted from checkpoint", stopped.pipelineId)
          RunningState(
            pipelineId = stopped.pipelineId,
            name = stopped.name,
            description = stopped.description,
            config = stopped.config,
            startedAt = timestamp,
            checkpoint = stopped.lastCheckpoint,
            metrics = stopped.finalMetrics,
            processedBatchIds = Set.empty,
            retryCount = 0,
            activeBatchId = None
          )

        case (running: RunningState, PipelineFailed(_, error, timestamp)) =>
          log.error("Pipeline {} failed with error: {}", running.pipelineId, error.message)
          FailedState(
            pipelineId = running.pipelineId,
            name = running.name,
            description = running.description,
            error = error,
            failedAt = timestamp,
            lastCheckpoint = Some(running.checkpoint)
          )

        case (failed: FailedState, PipelineReset(_, timestamp)) =>
          log.info("Pipeline {} reset from failed state", failed.pipelineId)
          ConfiguredState(
            pipelineId = failed.pipelineId,
            name = failed.name,
            description = failed.description,
            config = PipelineConfig(
              SourceConfig("", "", 0, 0),
              List.empty,
              SinkConfig("", "", 0)
            ),
            createdAt = timestamp
          )

        case (configured: ConfiguredState, ConfigUpdated(_, newConfig, _)) =>
          log.debug("Configuration updated for pipeline {}", configured.pipelineId)
          configured.copy(config = newConfig)

        case (stopped: StoppedState, ConfigUpdated(_, newConfig, _)) =>
          log.debug("Configuration updated for stopped pipeline {}", stopped.pipelineId)
          stopped.copy(config = newConfig)

        case _ => state
      }
  }
}
