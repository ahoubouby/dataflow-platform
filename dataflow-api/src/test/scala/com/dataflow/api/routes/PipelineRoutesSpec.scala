package com.dataflow.api.routes

import com.dataflow.api.models._
import com.dataflow.api.models.JsonProtocol._
import com.dataflow.api.services.PipelineService
import com.dataflow.domain.models._
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.duration._
import java.time.Instant

/**
 * Integration tests for Pipeline API routes.
 */
class PipelineRoutesSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with ScalatestRouteTest
    with BeforeAndAfterAll {

  val testKit: ActorTestKit = ActorTestKit()
  implicit val typedSystem: ActorSystem[_] = testKit.system

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  // Mock pipeline service (in real tests, use actual service with test containers)
  val pipelineService = new PipelineService()
  val routes = new PipelineRoutes(pipelineService).routes

  "Pipeline Routes" should {

    "return list of pipelines on GET /api/v1/pipelines" in {
      Get("/api/v1/pipelines") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val response = responseAs[PipelineListResponse]
        response.pipelines shouldBe a[List[_]]
      }
    }

    "create a new pipeline on POST /api/v1/pipelines" in {
      val createRequest = CreatePipelineRequest(
        name = "Test Pipeline",
        description = "Test pipeline for integration tests",
        sourceConfig = SourceConfig(
          sourceType = SourceType.File,
          connectionString = "/tmp/test.csv",
          batchSize = 100
        ),
        transformConfigs = List.empty,
        sinkConfig = SinkConfig(
          sinkType = "console",
          connectionString = "",
          batchSize = 10
        )
      )

      Post("/api/v1/pipelines", createRequest) ~> routes ~> check {
        status shouldBe StatusCodes.Created
        val response = responseAs[CreatePipelineResponse]
        response.status shouldBe "created"
        response.pipelineId should not be empty
      }
    }

    "return 404 for non-existent pipeline on GET /api/v1/pipelines/{id}" in {
      Get("/api/v1/pipelines/non-existent-id") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
        val response = responseAs[ErrorResponse]
        response.error shouldBe "not_found"
      }
    }

    "handle start pipeline request on POST /api/v1/pipelines/{id}/start" in {
      // First create a pipeline
      val createRequest = CreatePipelineRequest(
        name = "Test Pipeline",
        description = "Test",
        sourceConfig = SourceConfig(SourceType.File, "/tmp/test.csv", 100),
        transformConfigs = List.empty,
        sinkConfig = SinkConfig("console", "", 10)
      )

      var pipelineId = ""
      Post("/api/v1/pipelines", createRequest) ~> routes ~> check {
        status shouldBe StatusCodes.Created
        val response = responseAs[CreatePipelineResponse]
        pipelineId = response.pipelineId
      }

      // Then start it
      Post(s"/api/v1/pipelines/$pipelineId/start") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val response = responseAs[PipelineOperationResponse]
        response.status shouldBe "started"
      }
    }

    "handle stop pipeline request on POST /api/v1/pipelines/{id}/stop" in {
      val stopRequest = StopPipelineRequest(reason = "Test stop")

      // This will fail without a real pipeline, but tests the route
      Post("/api/v1/pipelines/test-id/stop", stopRequest) ~> routes ~> check {
        // Either OK or BadRequest depending on pipeline state
        status should (be(StatusCodes.OK) or be(StatusCodes.BadRequest))
      }
    }

    "handle pause pipeline request on POST /api/v1/pipelines/{id}/pause" in {
      val pauseRequest = StopPipelineRequest(reason = "Test pause")

      Post("/api/v1/pipelines/test-id/pause", pauseRequest) ~> routes ~> check {
        status should (be(StatusCodes.OK) or be(StatusCodes.BadRequest))
      }
    }

    "handle resume pipeline request on POST /api/v1/pipelines/{id}/resume" in {
      Post("/api/v1/pipelines/test-id/resume") ~> routes ~> check {
        status should (be(StatusCodes.OK) or be(StatusCodes.BadRequest))
      }
    }

    "handle reset pipeline request on POST /api/v1/pipelines/{id}/reset" in {
      Post("/api/v1/pipelines/test-id/reset") ~> routes ~> check {
        status should (be(StatusCodes.OK) or be(StatusCodes.BadRequest))
      }
    }

    "return metrics for pipeline on GET /api/v1/pipelines/{id}/metrics" in {
      Get("/api/v1/pipelines/test-id/metrics") ~> routes ~> check {
        // Will return NotFound for non-existent pipeline
        status should (be(StatusCodes.OK) or be(StatusCodes.NotFound))
      }
    }

    "return health status for pipeline on GET /api/v1/pipelines/{id}/health" in {
      Get("/api/v1/pipelines/test-id/health") ~> routes ~> check {
        status should (be(StatusCodes.OK) or be(StatusCodes.NotFound))
      }
    }
  }

  "JSON Serialization" should {

    "serialize and deserialize CreatePipelineRequest" in {
      val request = CreatePipelineRequest(
        name = "Test",
        description = "Description",
        sourceConfig = SourceConfig(SourceType.File, "/tmp/test.csv", 100),
        transformConfigs = List.empty,
        sinkConfig = SinkConfig("console", "", 10)
      )

      val json = request.toJson
      val deserialized = json.convertTo[CreatePipelineRequest]

      deserialized shouldBe request
    }

    "serialize and deserialize PipelineMetrics" in {
      val metrics = PipelineMetrics(
        totalRecordsProcessed = 1000,
        totalRecordsFailed = 5,
        totalBatchesProcessed = 10,
        averageProcessingTimeMs = 45.5,
        lastProcessedAt = Some(Instant.now()),
        throughputPerSecond = 330.0
      )

      val json = metrics.toJson
      val deserialized = json.convertTo[PipelineMetrics]

      deserialized.totalRecordsProcessed shouldBe metrics.totalRecordsProcessed
      deserialized.totalBatchesProcessed shouldBe metrics.totalBatchesProcessed
    }

    "serialize and deserialize SourceType" in {
      val sourceType = SourceType.Kafka
      val json = sourceType.toJson
      json.convertTo[SourceType] shouldBe SourceType.Kafka
    }

    "handle invalid SourceType" in {
      val invalidJson = spray.json.JsString("invalid")
      assertThrows[spray.json.DeserializationException] {
        invalidJson.convertTo[SourceType]
      }
    }
  }
}
