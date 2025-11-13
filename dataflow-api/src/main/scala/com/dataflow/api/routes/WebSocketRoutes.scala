package com.dataflow.api.routes

import com.dataflow.api.models.JsonProtocol._
import com.dataflow.api.services.PipelineService
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.stream.OverflowStrategy
import spray.json._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * WebSocket routes for real-time pipeline updates.
 * Provides streaming updates for pipeline metrics and status changes.
 */
class WebSocketRoutes(pipelineService: PipelineService)(implicit system: ActorSystem[_], ec: ExecutionContext) {

  val routes: Route = pathPrefix("api" / "v1" / "ws") {
    // WS /api/v1/ws/pipelines/:id - Real-time pipeline updates
    path("pipelines" / Segment) { pipelineId =>
      handleWebSocketMessages(pipelineUpdatesFlow(pipelineId))
    }
  }

  /**
   * WebSocket flow for pipeline updates.
   * Streams metrics and status updates every 2 seconds.
   */
  private def pipelineUpdatesFlow(pipelineId: String): Flow[Message, Message, Any] = {
    // Create a source that polls for updates
    val updateSource = Source
      .tick(0.seconds, 2.seconds, ())
      .mapAsync(1) { _ =>
        pipelineService.getPipeline(pipelineId)
      }
      .collect {
        case Right(details) =>
          // Convert to JSON and wrap in TextMessage
          val json = Map(
            "type" -> "pipeline_update".toJson,
            "pipelineId" -> pipelineId.toJson,
            "status" -> details.status.toJson,
            "metrics" -> details.metrics.toJson,
            "timestamp" -> java.time.Instant.now().toString.toJson
          ).toJson.compactPrint

          TextMessage(json)
      }
      .recover {
        case ex: Exception =>
          val errorJson = Map(
            "type" -> "error".toJson,
            "message" -> ex.getMessage.toJson,
            "timestamp" -> java.time.Instant.now().toString.toJson
          ).toJson.compactPrint

          TextMessage(errorJson)
      }

    // Ignore incoming messages from client, just stream updates
    Flow.fromSinkAndSource(
      sink = Flow[Message].to(org.apache.pekko.stream.scaladsl.Sink.ignore),
      source = updateSource
    )
  }
}
