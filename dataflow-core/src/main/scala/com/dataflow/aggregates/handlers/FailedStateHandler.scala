package com.dataflow.aggregates.handlers

import java.time.Instant
import com.dataflow.domain.commands._
import com.dataflow.domain.events._
import com.dataflow.domain.state._
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.scaladsl.{Effect, ReplyEffect}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Handles commands when pipeline is in FailedState.
 * Valid commands: ResetPipeline, GetState
 * All other commands are rejected with error message.
 */
object FailedStateHandler {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def handle(state: FailedState, command: Command): ReplyEffect[Event, State] = command match {
    case ResetPipeline(_, replyTo) =>
      log.info("msg=Reset failed pipeline pipelineId={}", state.pipelineId)
      Effect
        .persist(PipelineReset(state.pipelineId, Instant.now()))
        .thenReply(replyTo)(ns => StatusReply.success(ns))

    case GetState(_, replyTo) =>
      Effect.reply(replyTo)(state)

    case other =>
      log.warn(
        "msg=Command invalid in FailedState pipelineId={} cmd={} error={}",
        state.pipelineId,
        other.getClass.getSimpleName,
        state.error.message,
      )
      // Try to reply if possible
      tryReply(other, s"Pipeline failed: ${state.error.message}. Reset required.")
  }

  private def tryReply(command: Command, errorMessage: String): ReplyEffect[Event, State] = {
    command match {
      case cmd: CreatePipeline      => Effect.reply(cmd.replyTo)(StatusReply.error(errorMessage))
      case cmd: StartPipeline       => Effect.reply(cmd.replyTo)(StatusReply.error(errorMessage))
      case cmd: StopPipeline        => Effect.reply(cmd.replyTo)(StatusReply.error(errorMessage))
      case cmd: PausePipeline       => Effect.reply(cmd.replyTo)(StatusReply.error(errorMessage))
      case cmd: ResumePipeline      => Effect.reply(cmd.replyTo)(StatusReply.error(errorMessage))
      case cmd: IngestBatch         => Effect.reply(cmd.replyTo)(StatusReply.error(errorMessage))
      case cmd: ReportFailure       => Effect.reply(cmd.replyTo)(StatusReply.error(errorMessage))
      case cmd: ResetPipeline       => Effect.reply(cmd.replyTo)(StatusReply.error(errorMessage))
      case cmd: UpdateConfig        => Effect.reply(cmd.replyTo)(StatusReply.error(errorMessage))
      case _: UpdateCheckpoint      => Effect.noReply
      case _: BatchTimeout          => Effect.noReply
      case cmd: GetState            => Effect.reply(cmd.replyTo)(EmptyState) // Shouldn't happen
    }
  }
}
