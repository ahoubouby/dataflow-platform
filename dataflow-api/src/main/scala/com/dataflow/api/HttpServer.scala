package com.dataflow.api

import com.dataflow.api.routes.{PipelineRoutes, WebSocketRoutes}
import com.dataflow.api.services.PipelineService
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{Directive0, ExceptionHandler, RejectionHandler, Route}
import com.dataflow.api.models.ErrorResponse
import com.dataflow.api.models.JsonProtocol._
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import java.time.Instant

/**
 * HTTP server for DataFlow Platform API.
 * Provides REST API and WebSocket endpoints for pipeline management.
 */
class HttpServer(implicit system: ActorSystem[_], ec: ExecutionContext) {

  private val pipelineService = new PipelineService()
  private val pipelineRoutes = new PipelineRoutes(pipelineService)
  private val webSocketRoutes = new WebSocketRoutes(pipelineService)

  /**
   * Manual CORS implementation for Pekko HTTP
   */
  private def corsHandler: Directive0 = {
    respondWithHeaders(
      `Access-Control-Allow-Origin`.*,
      `Access-Control-Allow-Methods`(HttpMethods.GET, HttpMethods.POST, HttpMethods.PUT, HttpMethods.DELETE, HttpMethods.OPTIONS),
      `Access-Control-Allow-Headers`("Content-Type", "Authorization", "X-Requested-With"),
      `Access-Control-Max-Age`(1728000) // 20 days
    ) & options {
      complete(HttpResponse(StatusCodes.OK))
    }
  }

  // Exception handler
  private implicit val exceptionHandler: ExceptionHandler = ExceptionHandler {
    case ex: IllegalArgumentException =>
      extractUri { uri =>
        system.log.error(s"Request to $uri failed with illegal argument: ${ex.getMessage}", ex)
        complete(StatusCodes.BadRequest -> ErrorResponse(
          error = "bad_request",
          message = ex.getMessage,
          timestamp = Instant.now()
        ))
      }
    case ex: Exception =>
      extractUri { uri =>
        system.log.error(s"Request to $uri failed with exception: ${ex.getMessage}", ex)
        complete(StatusCodes.InternalServerError -> ErrorResponse(
          error = "internal_error",
          message = "An internal error occurred",
          details = Some(ex.getMessage),
          timestamp = Instant.now()
        ))
      }
  }

  // Rejection handler
  private implicit val rejectionHandler: RejectionHandler = RejectionHandler.default

  /**
   * Combined routes with middleware
   */
  private val allRoutes: Route = {
    handleExceptions(exceptionHandler) {
      handleRejections(rejectionHandler) {
        corsHandler {
          concat(
            // Health check endpoint
            path("health") {
              get {
                complete(StatusCodes.OK -> Map("status" -> "ok", "timestamp" -> Instant.now().toString))
              }
            },
            // API routes
            pipelineRoutes.routes,
            // WebSocket routes
            webSocketRoutes.routes,
            // Root endpoint
            pathSingleSlash {
              get {
                complete(StatusCodes.OK -> Map(
                  "service" -> "DataFlow Platform API",
                  "version" -> "1.0.0",
                  "status" -> "running"
                ))
              }
            }
          )
        }
      }
    }
  }

  /**
   * Start the HTTP server
   */
  def start(host: String = "0.0.0.0", port: Int = 8080): Future[Http.ServerBinding] = {
    val bindingFuture = Http()
      .newServerAt(host, port)
      .bind(allRoutes)

    bindingFuture.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info(
          s"DataFlow Platform API server online at http://${address.getHostString}:${address.getPort}/"
        )
      case Failure(ex) =>
        system.log.error(s"Failed to bind HTTP server to $host:$port", ex)
        system.terminate()
    }

    bindingFuture
  }

  /**
   * Stop the HTTP server
   */
  def stop(binding: Http.ServerBinding): Future[Http.HttpTerminated] = {
    system.log.info("Stopping DataFlow Platform API server...")
    binding.terminate(hardDeadline = scala.concurrent.duration.Duration(10, "seconds"))
  }
}

object HttpServer {
  def apply()(implicit system: ActorSystem[_], ec: ExecutionContext): HttpServer =
    new HttpServer()
}
