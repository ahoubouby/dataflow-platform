package com.dataflow.aggregates.handlers

import java.time.Instant
import com.dataflow.domain.commands._
import com.dataflow.domain.events._
import com.dataflow.domain.state._
import com.dataflow.validation.{PipelineValidators, ValidationHelper}
import com.wix.accord._
import com.wix.accord.{Failure => VFailure, Success => VSuccess}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.scaladsl.{Effect, ReplyEffect}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Handles commands when pipeline is in StoppedState.
 * Valid commands: StartPipeline, UpdateConfig, GetState
 */
object StoppedStateHandler {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def handle(state: StoppedState, command: Command): ReplyEffect[Event, State] = {
    import PipelineValidators._

    command match {
      case StartPipeline(_, replyTo) =>
        log.info("msg=Restart pipeline pipelineId={} offset={}", state.pipelineId, state.lastCheckpoint.offset)
        Effect
          .persist(PipelineStarted(state.pipelineId, Instant.now()))
          .thenReply(replyTo)(ns => StatusReply.success(ns))

      case UpdateConfig(_, newConfig, replyTo) =>
        validate(newConfig) match {
          case VSuccess             =>
            log.info("msg=Update config (stopped) pipelineId={}", state.pipelineId)
            Effect
              .persist(ConfigUpdated(state.pipelineId, newConfig, Instant.now()))
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
}
