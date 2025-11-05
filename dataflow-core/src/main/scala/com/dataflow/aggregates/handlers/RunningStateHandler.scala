package com.dataflow.aggregates.handlers

import java.time.Instant
import com.dataflow.domain.commands._
import com.dataflow.domain.events._
import com.dataflow.domain.models._
import com.dataflow.domain.state._
import com.dataflow.validation.{PipelineValidators, ValidationHelper}
import com.dataflow.recovery.{ErrorRecovery, TimeoutConfig}
import com.wix.accord._
import com.wix.accord.{Failure => VFailure, Success => VSuccess}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.scaladsl.{Effect, ReplyEffect}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Handles commands when pipeline is in RunningState.
 * This is the main operational state with complex logic for batch processing,
 * error recovery, and timeout handling.
 */
object RunningStateHandler {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def handle(
    state: RunningState,
    command: Command,
    timeoutConfig: TimeoutConfig,
    retryConfig: ErrorRecovery.RetryConfig,
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
            // Idempotency check
            if (state.processedBatchIds.contains(batchId)) {
              log.debug("msg=Idempotent batch pipelineId={} batchId={}", state.pipelineId, batchId)
              Effect.reply(replyTo)(StatusReply.success(BatchResult(batchId, records.size, 0, 0)))
            } else {
              val t0           = System.nanoTime()

              // Print records to console (sink simulation)
              log.info("=" * 80)
              log.info(s"PIPELINE OUTPUT (batchId=$batchId)")
              log.info("=" * 80)
              records.zipWithIndex.foreach { case (record, idx) =>
                log.info(s"Record ${idx + 1}:")
                log.info(s"  ID: ${record.id}")
                log.info(s"  Data: ${record.data.mkString(", ")}")
                log.info(s"  Metadata: ${record.metadata.mkString(", ")}")
              }
              log.info("=" * 80)

              val successCount = records.size
              val failureCount = 0
              val elapsedMs    = (System.nanoTime() - t0) / 1000000L

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
                .persist(
                  Seq(
                    BatchIngested(state.pipelineId, batchId, records.size, sourceOffset, Instant.now()),
                    BatchProcessed(state.pipelineId, batchId, successCount, failureCount, elapsedMs, Instant.now()),
                    CheckpointUpdated(
                      state.pipelineId,
                      Checkpoint(sourceOffset, Instant.now(), state.checkpoint.recordsProcessed + successCount),
                      Instant.now(),
                    ),
                  ),
                )
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
              if (state.retryCount >= retryConfig.maxRetries) {
                log.error(
                  "msg=Retries exhausted pipelineId={} code={} message={}",
                  state.pipelineId,
                  error.code,
                  error.message,
                )
              } else {
                log.error(
                  "msg=Non-retryable error pipelineId={} code={} message={}",
                  state.pipelineId,
                  error.code,
                  error.message,
                )
              }

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
          Effect
            .persist(BatchTimedOut(state.pipelineId, batchId, timeoutConfig.batchTimeout.toMillis, Instant.now()))
            .thenNoReply()
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
}
