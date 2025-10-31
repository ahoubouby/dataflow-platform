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
 * Handles commands when pipeline is in ConfiguredState.
 * Valid commands: StartPipeline, UpdateConfig, GetState
 */
object ConfiguredStateHandler {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def handle(state: ConfiguredState, command: Command): ReplyEffect[Event, State] = {
    import PipelineValidators._

    command match {
      case StartPipeline(_, replyTo) =>
        log.info("msg=Start pipeline pipelineId={}", state.pipelineId)
        Effect
          .persist(PipelineStarted(state.pipelineId, Instant.now()))
          .thenReply(replyTo)(newState => StatusReply.success(newState))

      case UpdateConfig(_, newConfig, replyTo) =>
        validate(newConfig) match {
          case VSuccess             =>
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
}
