package com.dataflow.api.services

import com.dataflow.domain.commands._
import com.dataflow.domain.models._
import com.dataflow.domain.state._
import com.dataflow.api.models._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import java.util.UUID
import java.time.Instant

/**
 * Service for managing pipeline operations.
 * Provides high-level API for pipeline CRUD and lifecycle management.
 */
class PipelineService(implicit system: ActorSystem[_], ec: ExecutionContext) {

  private implicit val timeout: Timeout = Timeout(30.seconds)

  // Sharding is configured elsewhere, we just get reference to it
  private val sharding: ClusterSharding = ClusterSharding(system)

  /**
   * Get entity reference for a pipeline
   */
  private def getPipelineEntity(pipelineId: String): EntityRef[Command] = {
    sharding.entityRefFor(PipelineService.TypeKey, pipelineId)
  }

  /**
   * Create a new pipeline
   */
  def createPipeline(request: CreatePipelineRequest): Future[Either[String, CreatePipelineResponse]] = {
    val pipelineId = UUID.randomUUID().toString

    val config = PipelineConfig(
      source = request.source,
      transforms = request.transforms,
      sink = request.sink
    )

    val entity = getPipelineEntity(pipelineId)

    entity
      .ask[StatusReply[State]](replyTo => CreatePipeline(
        pipelineId = pipelineId,
        name = request.name,
        description = request.description,
        config = config,
        replyTo = replyTo
      ))
      .map {
        case StatusReply.Success(state) =>
          Right(CreatePipelineResponse(
            pipelineId = pipelineId,
            status = "created",
            message = s"Pipeline '${request.name}' created successfully"
          ))
        case StatusReply.Error(error) =>
          Left(s"Failed to create pipeline: $error")
      }
      .recover {
        case ex: Exception =>
          Left(s"Failed to create pipeline: ${ex.getMessage}")
      }
  }

  /**
   * Get pipeline details
   */
  def getPipeline(pipelineId: String): Future[Either[String, PipelineDetails]] = {
    val entity = getPipelineEntity(pipelineId)

    entity
      .ask[State](replyTo => GetState(pipelineId, replyTo))
      .map { state =>
        state match {
          case EmptyState =>
            Left(s"Pipeline not found: $pipelineId")
          case _ =>
            Right(ApiModelConverters.stateToDetails(state, pipelineId))
        }
      }
      .recover {
        case ex: Exception =>
          Left(s"Failed to get pipeline: ${ex.getMessage}")
      }
  }

  /**
   * List all pipelines (simplified - in production would use projections)
   */
  def listPipelines(): Future[PipelineListResponse] = {
    // TODO: In production, this should query a projection/read model
    // For now, return empty list as we don't have a registry yet
    Future.successful(PipelineListResponse(pipelines = List.empty, total = 0))
  }

  /**
   * Update pipeline configuration
   */
  def updatePipeline(
    pipelineId: String,
    request: UpdatePipelineRequest
  ): Future[Either[String, PipelineOperationResponse]] = {
    // First get current state to merge with updates
    getPipeline(pipelineId).flatMap {
      case Left(error) => Future.successful(Left(error))
      case Right(current) =>
        // Build new config with updates
        val newConfig = PipelineConfig(
          source = request.source.getOrElse(current.source),
          transforms = request.transforms.getOrElse(current.transforms),
          sink = request.sink.getOrElse(current.sink)
        )

        val entity = getPipelineEntity(pipelineId)
        entity
          .ask[StatusReply[State]](replyTo => UpdateConfig(pipelineId, newConfig, replyTo))
          .map {
            case StatusReply.Success(_) =>
              Right(PipelineOperationResponse(
                pipelineId = pipelineId,
                status = "updated",
                message = "Pipeline configuration updated successfully",
                timestamp = Instant.now()
              ))
            case StatusReply.Error(error) =>
              Left(s"Failed to update pipeline: $error")
          }
          .recover {
            case ex: Exception =>
              Left(s"Failed to update pipeline: ${ex.getMessage}")
          }
    }
  }

  /**
   * Start a pipeline
   */
  def startPipeline(pipelineId: String): Future[Either[String, PipelineOperationResponse]] = {
    val entity = getPipelineEntity(pipelineId)

    entity
      .ask[StatusReply[State]](replyTo => StartPipeline(pipelineId, replyTo))
      .map {
        case StatusReply.Success(_) =>
          Right(PipelineOperationResponse(
            pipelineId = pipelineId,
            status = "started",
            message = "Pipeline started successfully",
            timestamp = Instant.now()
          ))
        case StatusReply.Error(error) =>
          Left(s"Failed to start pipeline: $error")
      }
      .recover {
        case ex: Exception =>
          Left(s"Failed to start pipeline: ${ex.getMessage}")
      }
  }

  /**
   * Stop a pipeline
   */
  def stopPipeline(pipelineId: String, reason: String): Future[Either[String, PipelineOperationResponse]] = {
    val entity = getPipelineEntity(pipelineId)

    entity
      .ask[StatusReply[State]](replyTo => StopPipeline(pipelineId, reason, replyTo))
      .map {
        case StatusReply.Success(_) =>
          Right(PipelineOperationResponse(
            pipelineId = pipelineId,
            status = "stopped",
            message = s"Pipeline stopped: $reason",
            timestamp = Instant.now()
          ))
        case StatusReply.Error(error) =>
          Left(s"Failed to stop pipeline: $error")
      }
      .recover {
        case ex: Exception =>
          Left(s"Failed to stop pipeline: ${ex.getMessage}")
      }
  }

  /**
   * Pause a pipeline
   */
  def pausePipeline(pipelineId: String, reason: String): Future[Either[String, PipelineOperationResponse]] = {
    val entity = getPipelineEntity(pipelineId)

    entity
      .ask[StatusReply[State]](replyTo => PausePipeline(pipelineId, reason, replyTo))
      .map {
        case StatusReply.Success(_) =>
          Right(PipelineOperationResponse(
            pipelineId = pipelineId,
            status = "paused",
            message = s"Pipeline paused: $reason",
            timestamp = Instant.now()
          ))
        case StatusReply.Error(error) =>
          Left(s"Failed to pause pipeline: $error")
      }
      .recover {
        case ex: Exception =>
          Left(s"Failed to pause pipeline: ${ex.getMessage}")
      }
  }

  /**
   * Resume a paused pipeline
   */
  def resumePipeline(pipelineId: String): Future[Either[String, PipelineOperationResponse]] = {
    val entity = getPipelineEntity(pipelineId)

    entity
      .ask[StatusReply[State]](replyTo => ResumePipeline(pipelineId, replyTo))
      .map {
        case StatusReply.Success(_) =>
          Right(PipelineOperationResponse(
            pipelineId = pipelineId,
            status = "resumed",
            message = "Pipeline resumed successfully",
            timestamp = Instant.now()
          ))
        case StatusReply.Error(error) =>
          Left(s"Failed to resume pipeline: $error")
      }
      .recover {
        case ex: Exception =>
          Left(s"Failed to resume pipeline: ${ex.getMessage}")
      }
  }

  /**
   * Get pipeline metrics
   */
  def getMetrics(pipelineId: String): Future[Either[String, MetricsResponse]] = {
    getPipeline(pipelineId).map {
      case Left(error) => Left(error)
      case Right(details) =>
        details.metrics match {
          case Some(metrics) =>
            Right(MetricsResponse(
              pipelineId = pipelineId,
              metrics = metrics,
              timestamp = Instant.now()
            ))
          case None =>
            Left(s"No metrics available for pipeline: $pipelineId")
        }
    }
  }

  /**
   * Get pipeline health status
   */
  def getHealth(pipelineId: String): Future[Either[String, HealthResponse]] = {
    getPipeline(pipelineId).map {
      case Left(error) => Left(error)
      case Right(details) =>
        val healthy = details.status match {
          case "running" => true
          case "paused"  => true
          case "stopped" => true
          case "failed"  => false
          case _         => true
        }

        Right(HealthResponse(
          pipelineId = pipelineId,
          healthy = healthy,
          status = details.status,
          lastCheck = Instant.now(),
          details = if (healthy) None else Some(s"Pipeline is in ${details.status} state")
        ))
    }
  }

  /**
   * Reset a failed pipeline
   */
  def resetPipeline(pipelineId: String): Future[Either[String, PipelineOperationResponse]] = {
    val entity = getPipelineEntity(pipelineId)

    entity
      .ask[StatusReply[State]](replyTo => ResetPipeline(pipelineId, replyTo))
      .map {
        case StatusReply.Success(_) =>
          Right(PipelineOperationResponse(
            pipelineId = pipelineId,
            status = "reset",
            message = "Pipeline reset successfully",
            timestamp = Instant.now()
          ))
        case StatusReply.Error(error) =>
          Left(s"Failed to reset pipeline: $error")
      }
      .recover {
        case ex: Exception =>
          Left(s"Failed to reset pipeline: ${ex.getMessage}")
      }
  }
}

object PipelineService {
  import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey

  // Entity type key for cluster sharding
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Pipeline")
}
