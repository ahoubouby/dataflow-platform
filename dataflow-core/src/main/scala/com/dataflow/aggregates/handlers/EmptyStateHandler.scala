package com.dataflow.aggregates.handlers

import java.time.Instant
import com.dataflow.domain.commands._
import com.dataflow.domain.events._
import com.dataflow.domain.models._
import com.dataflow.domain.state._
import com.dataflow.validation.{PipelineValidators, ValidationHelper}
import com.dataflow.recovery.ErrorRecovery
import com.wix.accord._
import com.wix.accord.{Failure => VFailure, Success => VSuccess}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.scaladsl.{Effect, ReplyEffect}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Handles commands when pipeline is in EmptyState.
 * Only CreatePipeline and GetState are valid in this state.
 */
object EmptyStateHandler {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def handle(command: Command): ReplyEffect[Event, State] = {
    import PipelineValidators._

    command match {
      case cmd @ CreatePipeline(id, name, desc, source, transforms, sink, replyTo) =>
        validate(cmd) match {
          case VSuccess             =>
            log.info("msg=Create pipeline id={} name='{}'", id, name)
            Effect
              .persist(PipelineCreated(id, name, desc, source, transforms, sink, Instant.now()))
              .thenReply(replyTo)(state => StatusReply.success(state))
          case VFailure(violations) =>
            val err = ValidationHelper.formatViolations(violations)
            log.warn("msg=Create validation failed id={} error={}", id, err)
            Effect.reply(replyTo)(StatusReply.error(s"Validation failed: $err"))
        }

      case GetState(_, replyTo) =>
        Effect.reply(replyTo)(EmptyState)

      case other =>
        log.warn("msg=Command invalid in EmptyState cmd={}", other.getClass.getSimpleName)
        Effect.noReply
    }
  }
}
