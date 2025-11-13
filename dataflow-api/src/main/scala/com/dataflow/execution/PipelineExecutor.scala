package com.dataflow.execution

import com.dataflow.domain.models.{DataRecord, PipelineConfig, SinkConfig, SourceConfig, TransformConfig}
import com.dataflow.sources.Source
import com.dataflow.sinks.domain.DataSink
import com.dataflow.transforms.domain.Transform
import kamon.Kamon
import kamon.metric.Counter
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.stream.scaladsl.{Keep, RunnableGraph}
import org.apache.pekko.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import org.apache.pekko.{Done, NotUsed}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Actor responsible for executing a single pipeline.
 *
 * Responsibilities:
 * - Instantiate source, transforms, and sink based on PipelineConfig
 * - Build and run the Pekko Streams graph
 * - Collect and report metrics during execution
 * - Handle lifecycle (start, pause, stop)
 * - Report status back to PipelineAggregate
 */
object PipelineExecutor {

  private val logger = LoggerFactory.getLogger(getClass)

  // ============================================
  // PROTOCOL
  // ============================================

  sealed trait Command

  /**
   * Start executing the pipeline.
   */
  final case class Start(
    pipelineId: String,
    config: PipelineConfig,
    replyTo: ActorRef[Response]) extends Command

  /**
   * Stop executing the pipeline.
   */
  final case class Stop(replyTo: ActorRef[Response]) extends Command

  /**
   * Pause the pipeline (stop processing but keep resources).
   */
  final case class Pause(replyTo: ActorRef[Response]) extends Command

  /**
   * Resume a paused pipeline.
   */
  final case class Resume(replyTo: ActorRef[Response]) extends Command

  /**
   * Get current execution metrics.
   */
  final case class GetMetrics(replyTo: ActorRef[MetricsResponse]) extends Command

  /**
   * Internal message when stream completes.
   */
  private final case class StreamCompleted(result: Done) extends Command

  /**
   * Internal message when stream fails.
   */
  private final case class StreamFailed(error: Throwable) extends Command

  // ============================================
  // RESPONSES
  // ============================================

  sealed trait Response

  final case class Started(pipelineId: String) extends Response
  final case class Stopped(pipelineId: String) extends Response
  final case class Paused(pipelineId: String) extends Response
  final case class Resumed(pipelineId: String) extends Response
  final case class ExecutionFailed(pipelineId: String, error: String) extends Response

  final case class MetricsResponse(
    recordsProcessed: Long,
    recordsFailed: Long,
    bytesProcessed: Long,
    isRunning: Boolean)

  // ============================================
  // STATE
  // ============================================

  private final case class ExecutionState(
    pipelineId: String,
    config: PipelineConfig,
    source: Option[Source] = None,
    transforms: List[Transform] = Nil,
    sink: Option[DataSink] = None,
    killSwitch: Option[UniqueKillSwitch] = None,
    streamFuture: Option[Future[Done]] = None,
    recordsProcessed: Long = 0,
    recordsFailed: Long = 0,
    bytesProcessed: Long = 0,
    metricsCounter: Option[Counter] = None)

  // ============================================
  // BEHAVIOR
  // ============================================

  def apply(): Behavior[Command] = {
    Behaviors.setup { context =>
      idle(context)
    }
  }

  /**
   * Idle state - waiting for Start command.
   */
  private def idle(context: ActorContext[Command]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case Start(pipelineId, config, replyTo) =>
        context.log.info("Starting pipeline execution: {}", pipelineId)

        try {
          // Initialize metrics counter
          val metricsCounter = Kamon.counter("pipeline.records.processed")
            .withTag("pipeline_id", pipelineId)

          val state = ExecutionState(
            pipelineId = pipelineId,
            config = config,
            metricsCounter = Some(metricsCounter))

          // Instantiate components
          val updatedState = instantiateComponents(state, context)

          // Build and start the stream
          startStream(updatedState, context) match {
            case Success(runningState) =>
              replyTo ! Started(pipelineId)
              running(runningState, context)

            case Failure(ex) =>
              context.log.error("Failed to start pipeline: {}", ex.getMessage, ex)
              replyTo ! ExecutionFailed(pipelineId, ex.getMessage)
              Behaviors.stopped
          }

        } catch {
          case ex: Exception =>
            context.log.error("Failed to start pipeline: {}", ex.getMessage, ex)
            replyTo ! ExecutionFailed(pipelineId, ex.getMessage)
            Behaviors.stopped
        }

      case Stop(replyTo) =>
        replyTo ! Stopped("unknown")
        Behaviors.stopped

      case _ =>
        Behaviors.unhandled
    }
  }

  /**
   * Running state - pipeline is actively processing data.
   */
  private def running(
    state: ExecutionState,
    context: ActorContext[Command],
  ): Behavior[Command] = {
    Behaviors.receiveMessage {
      case Stop(replyTo) =>
        context.log.info("Stopping pipeline execution: {}", state.pipelineId)
        stopStream(state, context)
        replyTo ! Stopped(state.pipelineId)
        Behaviors.stopped

      case Pause(replyTo) =>
        context.log.info("Pausing pipeline execution: {}", state.pipelineId)
        state.killSwitch.foreach(_.shutdown())
        replyTo ! Paused(state.pipelineId)
        paused(state, context)

      case GetMetrics(replyTo) =>
        replyTo ! MetricsResponse(
          recordsProcessed = state.recordsProcessed,
          recordsFailed = state.recordsFailed,
          bytesProcessed = state.bytesProcessed,
          isRunning = true)
        Behaviors.same

      case StreamCompleted(result) =>
        context.log.info("Pipeline stream completed successfully: {}", state.pipelineId)
        cleanup(state, context)
        Behaviors.stopped

      case StreamFailed(error) =>
        context.log.error("Pipeline stream failed: {}", error.getMessage, error)
        cleanup(state, context)
        Behaviors.stopped

      case _ =>
        Behaviors.unhandled
    }
  }

  /**
   * Paused state - pipeline is paused but can be resumed.
   */
  private def paused(
    state: ExecutionState,
    context: ActorContext[Command],
  ): Behavior[Command] = {
    Behaviors.receiveMessage {
      case Resume(replyTo) =>
        context.log.info("Resuming pipeline execution: {}", state.pipelineId)
        startStream(state, context) match {
          case Success(runningState) =>
            replyTo ! Resumed(state.pipelineId)
            running(runningState, context)

          case Failure(ex) =>
            context.log.error("Failed to resume pipeline: {}", ex.getMessage, ex)
            replyTo ! ExecutionFailed(state.pipelineId, ex.getMessage)
            Behaviors.stopped
        }

      case Stop(replyTo) =>
        context.log.info("Stopping paused pipeline: {}", state.pipelineId)
        cleanup(state, context)
        replyTo ! Stopped(state.pipelineId)
        Behaviors.stopped

      case GetMetrics(replyTo) =>
        replyTo ! MetricsResponse(
          recordsProcessed = state.recordsProcessed,
          recordsFailed = state.recordsFailed,
          bytesProcessed = state.bytesProcessed,
          isRunning = false)
        Behaviors.same

      case _ =>
        Behaviors.unhandled
    }
  }

  // ============================================
  // HELPERS
  // ============================================

  /**
   * Instantiate source, transforms, and sink from configuration.
   */
  private def instantiateComponents(
    state: ExecutionState,
    context: ActorContext[Command],
  ): ExecutionState = {
    implicit val system = context.system

    // Instantiate source
    val source = Source(state.pipelineId, state.config.source)
    context.log.info("Instantiated source: {}", state.config.source.sourceType)

    // Instantiate transforms
    val transforms = state.config.transforms.flatMap { transformConfig =>
      TransformConfigMapper.toTransformationConfig(transformConfig) match {
        case Success(config) =>
          com.dataflow.transforms.TransformFactory.create(config) match {
            case Success(transform) =>
              context.log.info("Instantiated transform: {}", transformConfig.transformType)
              Some(transform)
            case Failure(ex) =>
              context.log.error("Failed to create transform: {}", ex.getMessage, ex)
              None
          }
        case Failure(ex) =>
          context.log.error("Failed to map transform config: {}", ex.getMessage, ex)
          None
      }
    }.toList

    // Instantiate sink
    val sink = SinkFactory.create(state.pipelineId, state.config.sink)
    context.log.info("Instantiated sink: {}", state.config.sink.sinkType)

    state.copy(
      source = Some(source),
      transforms = transforms,
      sink = Some(sink))
  }

  /**
   * Build and start the Pekko Streams pipeline.
   */
  private def startStream(
    state: ExecutionState,
    context: ActorContext[Command],
  ): scala.util.Try[ExecutionState] = {
    scala.util.Try {
      implicit val system = context.system
      implicit val ec: ExecutionContext = system.executionContext
      implicit val mat: Materializer = Materializer(system)

      val source = state.source.getOrElse(throw new IllegalStateException("Source not initialized"))
      val sink = state.sink.getOrElse(throw new IllegalStateException("Sink not initialized"))

      // Build the stream graph: Source -> Transform(s) -> Sink
      val streamSource = source.stream()

      // Apply all transforms in sequence
      val transformedStream = state.transforms.foldLeft(streamSource) { (stream, transform) =>
        stream
          .via(transform.flow)
          .map { record =>
            // Increment metrics counter
            state.metricsCounter.foreach(_.increment())
            record
          }
      }

      // Add kill switch for graceful shutdown
      val graph: RunnableGraph[(UniqueKillSwitch, Future[Done])] =
        transformedStream
          .viaMat(KillSwitches.single)(Keep.right)
          .toMat(sink.sink)(Keep.both)

      // Run the stream
      val (killSwitch, streamFuture) = graph.run()

      // Handle stream completion
      streamFuture.onComplete {
        case Success(done) =>
          context.self ! StreamCompleted(done)
        case Failure(error) =>
          context.self ! StreamFailed(error)
      }

      context.log.info("Pipeline stream started successfully: {}", state.pipelineId)

      state.copy(
        killSwitch = Some(killSwitch),
        streamFuture = Some(streamFuture))
    }
  }

  /**
   * Stop the stream gracefully.
   */
  private def stopStream(
    state: ExecutionState,
    context: ActorContext[Command],
  ): Unit = {
    state.killSwitch.foreach { ks =>
      context.log.info("Shutting down stream for pipeline: {}", state.pipelineId)
      ks.shutdown()
    }

    cleanup(state, context)
  }

  /**
   * Cleanup resources when stopping.
   */
  private def cleanup(
    state: ExecutionState,
    context: ActorContext[Command],
  ): Unit = {
    implicit val ec: ExecutionContext = context.system.executionContext

    // Stop source
    state.source.foreach { source =>
      source.stop().onComplete {
        case Success(_) =>
          context.log.debug("Source stopped successfully")
        case Failure(ex) =>
          context.log.error("Error stopping source: {}", ex.getMessage, ex)
      }
    }

    // Close sink
    state.sink.foreach { sink =>
      sink.close().onComplete {
        case Success(_) =>
          context.log.debug("Sink closed successfully")
        case Failure(ex) =>
          context.log.error("Error closing sink: {}", ex.getMessage, ex)
      }
    }
  }
}
