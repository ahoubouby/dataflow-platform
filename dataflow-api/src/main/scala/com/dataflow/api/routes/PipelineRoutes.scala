package com.dataflow.api.routes

import com.dataflow.api.models._
import com.dataflow.api.models.JsonProtocol._
import com.dataflow.api.services.PipelineService
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import scala.concurrent.ExecutionContext
import java.time.Instant

/**
 * HTTP routes for pipeline management API.
 * Provides REST endpoints for pipeline CRUD and lifecycle operations.
 */
class PipelineRoutes(pipelineService: PipelineService)(implicit system: ActorSystem[_], ec: ExecutionContext) {

  val routes: Route = pathPrefix("api" / "v1" / "pipelines") {
    concat(
      // GET /api/v1/pipelines - List all pipelines
      pathEnd {
        get {
          complete {
            pipelineService.listPipelines()
          }
        } ~
        // POST /api/v1/pipelines - Create new pipeline
        post {
          entity(as[CreatePipelineRequest]) { request =>
            onSuccess(pipelineService.createPipeline(request)) {
              case Right(response) =>
                complete(StatusCodes.Created -> response)
              case Left(error) =>
                complete(StatusCodes.BadRequest -> ErrorResponse(
                  error = "create_failed",
                  message = error,
                  timestamp = Instant.now()
                ))
            }
          }
        }
      },

      // GET /api/v1/pipelines/:id - Get pipeline details
      path(Segment) { pipelineId =>
        get {
          onSuccess(pipelineService.getPipeline(pipelineId)) {
            case Right(details) =>
              complete(StatusCodes.OK -> details)
            case Left(error) =>
              complete(StatusCodes.NotFound -> ErrorResponse(
                error = "not_found",
                message = error,
                timestamp = Instant.now()
              ))
          }
        } ~
        // PUT /api/v1/pipelines/:id - Update pipeline
        put {
          entity(as[UpdatePipelineRequest]) { request =>
            onSuccess(pipelineService.updatePipeline(pipelineId, request)) {
              case Right(response) =>
                complete(StatusCodes.OK -> response)
              case Left(error) =>
                complete(StatusCodes.BadRequest -> ErrorResponse(
                  error = "update_failed",
                  message = error,
                  timestamp = Instant.now()
                ))
            }
          }
        }
      },

      // POST /api/v1/pipelines/:id/start - Start pipeline
      path(Segment / "start") { pipelineId =>
        post {
          onSuccess(pipelineService.startPipeline(pipelineId)) {
            case Right(response) =>
              complete(StatusCodes.OK -> response)
            case Left(error) =>
              complete(StatusCodes.BadRequest -> ErrorResponse(
                error = "start_failed",
                message = error,
                timestamp = Instant.now()
              ))
          }
        }
      },

      // POST /api/v1/pipelines/:id/stop - Stop pipeline
      path(Segment / "stop") { pipelineId =>
        post {
          entity(as[StopPipelineRequest]) { request =>
            onSuccess(pipelineService.stopPipeline(pipelineId, request.reason)) {
              case Right(response) =>
                complete(StatusCodes.OK -> response)
              case Left(error) =>
                complete(StatusCodes.BadRequest -> ErrorResponse(
                  error = "stop_failed",
                  message = error,
                  timestamp = Instant.now()
                ))
            }
          }
        }
      },

      // POST /api/v1/pipelines/:id/pause - Pause pipeline
      path(Segment / "pause") { pipelineId =>
        post {
          entity(as[StopPipelineRequest]) { request =>
            onSuccess(pipelineService.pausePipeline(pipelineId, request.reason)) {
              case Right(response) =>
                complete(StatusCodes.OK -> response)
              case Left(error) =>
                complete(StatusCodes.BadRequest -> ErrorResponse(
                  error = "pause_failed",
                  message = error,
                  timestamp = Instant.now()
                ))
            }
          }
        }
      },

      // POST /api/v1/pipelines/:id/resume - Resume pipeline
      path(Segment / "resume") { pipelineId =>
        post {
          onSuccess(pipelineService.resumePipeline(pipelineId)) {
            case Right(response) =>
              complete(StatusCodes.OK -> response)
            case Left(error) =>
              complete(StatusCodes.BadRequest -> ErrorResponse(
                error = "resume_failed",
                message = error,
                timestamp = Instant.now()
              ))
          }
        }
      },

      // POST /api/v1/pipelines/:id/reset - Reset failed pipeline
      path(Segment / "reset") { pipelineId =>
        post {
          onSuccess(pipelineService.resetPipeline(pipelineId)) {
            case Right(response) =>
              complete(StatusCodes.OK -> response)
            case Left(error) =>
              complete(StatusCodes.BadRequest -> ErrorResponse(
                error = "reset_failed",
                message = error,
                timestamp = Instant.now()
              ))
          }
        }
      },

      // GET /api/v1/pipelines/:id/metrics - Get pipeline metrics
      path(Segment / "metrics") { pipelineId =>
        get {
          onSuccess(pipelineService.getMetrics(pipelineId)) {
            case Right(response) =>
              complete(StatusCodes.OK -> response)
            case Left(error) =>
              complete(StatusCodes.NotFound -> ErrorResponse(
                error = "not_found",
                message = error,
                timestamp = Instant.now()
              ))
          }
        }
      },

      // GET /api/v1/pipelines/:id/health - Get pipeline health
      path(Segment / "health") { pipelineId =>
        get {
          onSuccess(pipelineService.getHealth(pipelineId)) {
            case Right(response) =>
              complete(StatusCodes.OK -> response)
            case Left(error) =>
              complete(StatusCodes.NotFound -> ErrorResponse(
                error = "not_found",
                message = error,
                timestamp = Instant.now()
              ))
          }
        }
      }
    )
  }
}
