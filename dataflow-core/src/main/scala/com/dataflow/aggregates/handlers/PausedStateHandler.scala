package com.dataflow.aggregates.handlers

import java.time.Instant
import com.dataflow.domain.commands._
import com.dataflow.domain.events._
import com.dataflow.domain.state._
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.scaladsl.{Effect, ReplyEffect}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Handles commands when pipeline is in PausedState.
 * Valid commands: ResumePipeline, StopPipeline, GetState
 */
object PausedStateHandler {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def handle(state: PausedState, command: Command): ReplyEffect[Event, State] = command match {
    case ResumePipeline(_, replyTo) =>
      log.info("msg=Resume pipeline pipelineId={}", state.pipelineId)
      Effect
        .persist(PipelineResumed(state.pipelineId, Instant.now()))
        .thenReply(replyTo)(ns => StatusReply.success(ns))

    case StopPipeline(_, reason, replyTo) =>
      log.info("msg=Stop paused pipeline pipelineId={} reason={}", state.pipelineId, reason)
      Effect
        .persist(PipelineStopped(state.pipelineId, reason, state.metrics, Instant.now()))
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
}
