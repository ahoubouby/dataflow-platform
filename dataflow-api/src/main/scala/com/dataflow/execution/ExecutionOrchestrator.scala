package com.dataflow.execution

import com.dataflow.api.services.PipelineService
import com.dataflow.domain.events._
import com.dataflow.domain.state._
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.persistence.query.{EventEnvelope, Offset}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.scaladsl.SourceProvider
import org.apache.pekko.projection.ProjectionId
import org.slf4j.LoggerFactory

import scala.collection.mutable

/**
 * Orchestrates pipeline execution based on pipeline events.
 *
 * This actor:
 * - Subscribes to pipeline events from the event journal
 * - Spawns/stops PipelineExecutor actors based on state transitions
 * - Manages the lifecycle of all running pipelines
 *
 * Architecture:
 * - PipelineAggregate (in dataflow-core): Pure event sourcing, state management
 * - ExecutionOrchestrator (in dataflow-api): Listens to events, manages executors
 * - PipelineExecutor (in dataflow-api): Actual pipeline execution with sources/transforms/sinks
 */
object ExecutionOrchestrator {

  private val log = LoggerFactory.getLogger(getClass)

  // ============================================
  // PROTOCOL
  // ============================================

  sealed trait Command

  /**
   * Handle a pipeline event from the journal.
   */
  final case class HandleEvent(event: Event, pipelineId: String) extends Command

  /**
   * Internal message when an executor stops.
   */
  private final case class ExecutorStopped(pipelineId: String, ref: ActorRef[PipelineExecutor.Command]) extends Command

  // ============================================
  // STATE
  // ============================================

  private final case class ExecutionState(
    // Map of pipelineId -> executor actor reference
    executors: mutable.Map[String, ActorRef[PipelineExecutor.Command]] = mutable.Map.empty
  )

  // ============================================
  // BEHAVIOR
  // ============================================

  def apply(): Behavior[Command] = {
    Behaviors.setup { context =>
      log.info("ExecutionOrchestrator started")

      running(ExecutionState(), context)
    }
  }

  /**
   * Running state - handles events and manages executors.
   */
  private def running(
    state: ExecutionState,
    context: ActorContext[Command]
  ): Behavior[Command] = {
    Behaviors.receiveMessage {
      case HandleEvent(event, pipelineId) =>
        handlePipelineEvent(event, pipelineId, state, context)
        Behaviors.same

      case ExecutorStopped(pipelineId, ref) =>
        state.executors.get(pipelineId) match {
          case Some(existingRef) if existingRef == ref =>
            log.info("Executor stopped for pipeline: {}", pipelineId)
            state.executors.remove(pipelineId)
          case _ =>
            log.debug("Ignoring stop for unknown executor: {}", pipelineId)
        }
        Behaviors.same
    }
  }

  // ============================================
  // EVENT HANDLING
  // ============================================

  private def handlePipelineEvent(
    event: Event,
    pipelineId: String,
    state: ExecutionState,
    context: ActorContext[Command]
  ): Unit = {
    event match {
      case PipelineStarted(id, _) =>
        log.info("Pipeline started event received for: {}", id)
        startExecutor(id, state, context)

      case PipelineStopped(id, reason, _, _) =>
        log.info("Pipeline stopped event received for: {} (reason: {})", id, reason)
        stopExecutor(id, state, context)

      case PipelinePaused(id, reason, _) =>
        log.info("Pipeline paused event received for: {} (reason: {})", id, reason)
        pauseExecutor(id, state, context)

      case PipelineResumed(id, _) =>
        log.info("Pipeline resumed event received for: {}", id)
        resumeExecutor(id, state, context)

      case PipelineFailed(id, error, _) =>
        log.error("Pipeline failed event received for: {} (error: {})", id, error.message)
        stopExecutor(id, state, context)

      case _ =>
        log.debug("Ignoring event: {} for pipeline: {}", event.getClass.getSimpleName, pipelineId)
    }
  }

  // ============================================
  // EXECUTOR MANAGEMENT
  // ============================================

  private def startExecutor(
    pipelineId: String,
    state: ExecutionState,
    context: ActorContext[Command]
  ): Unit = {
    state.executors.get(pipelineId) match {
      case Some(_) =>
        log.warn("Executor already running for pipeline: {}", pipelineId)

      case None =>
        // Spawn new executor
        val executor = context.spawn(
          PipelineExecutor(),
          s"executor-$pipelineId"
        )

        context.watchWith(executor, ExecutorStopped(pipelineId, executor))

        state.executors.put(pipelineId, executor)

        log.info("Spawned executor for pipeline: {}", pipelineId)

        // Get pipeline config from sharding and start execution
        // Note: In real implementation, we need to fetch the config first
        // For now, we'll let the PipelineService handle this
        val sharding = ClusterSharding(context.system)
        val pipelineEntity = sharding.entityRefFor(PipelineService.TypeKey, pipelineId)

        // Ask for current state to get config
        import context.executionContext
        import org.apache.pekko.actor.typed.scaladsl.AskPattern._
        import org.apache.pekko.util.Timeout
        import scala.concurrent.duration._
        import com.dataflow.domain.commands.GetState

        implicit val timeout: Timeout = Timeout(5.seconds)

        pipelineEntity.ask(GetState(pipelineId, _)).foreach {
          case runningState: RunningState =>
            log.info("Starting executor with config for pipeline: {}", pipelineId)
            executor ! PipelineExecutor.Start(
              pipelineId = pipelineId,
              config = runningState.config,
              replyTo = null
            )
          case configuredState: ConfiguredState =>
            log.warn("Pipeline {} is in ConfiguredState but received PipelineStarted event - starting anyway", pipelineId)
            executor ! PipelineExecutor.Start(
              pipelineId = pipelineId,
              config = configuredState.config,
              replyTo = null
            )
          case otherState =>
            log.error("Cannot start executor - pipeline {} is in unexpected state: {}", pipelineId, otherState.getClass.getSimpleName)
        }
    }
  }

  private def stopExecutor(
    pipelineId: String,
    state: ExecutionState,
    context: ActorContext[Command]
  ): Unit = {
    state.executors.get(pipelineId) match {
      case Some(executor) =>
        log.info("Stopping executor for pipeline: {}", pipelineId)
        executor ! PipelineExecutor.Stop(replyTo = null)
        // Will be removed from map when ExecutorStopped message arrives

      case None =>
        log.debug("No executor running for pipeline: {}", pipelineId)
    }
  }

  private def pauseExecutor(
    pipelineId: String,
    state: ExecutionState,
    context: ActorContext[Command]
  ): Unit = {
    state.executors.get(pipelineId) match {
      case Some(executor) =>
        log.info("Pausing executor for pipeline: {}", pipelineId)
        executor ! PipelineExecutor.Pause(replyTo = null)

      case None =>
        log.warn("Cannot pause - no executor running for pipeline: {}", pipelineId)
    }
  }

  private def resumeExecutor(
    pipelineId: String,
    state: ExecutionState,
    context: ActorContext[Command]
  ): Unit = {
    state.executors.get(pipelineId) match {
      case Some(executor) =>
        log.info("Resuming executor for pipeline: {}", pipelineId)
        executor ! PipelineExecutor.Resume(replyTo = null)

      case None =>
        log.warn("Cannot resume - no executor running for pipeline: {}, will start new executor", pipelineId)
        startExecutor(pipelineId, state, context)
    }
  }
}
