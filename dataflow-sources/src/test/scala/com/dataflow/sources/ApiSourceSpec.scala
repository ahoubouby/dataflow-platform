package com.dataflow.sources

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

import com.dataflow.domain.commands.{Command, IngestBatch}
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials, OAuth2BearerToken, RawHeader}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Integration tests for ApiSource connector.
 *
 * Uses Pekko HTTP to create a mock API server for testing.
 *
 * Tests:
 * - Basic JSON response parsing
 * - Pagination (offset, page, cursor)
 * - Authentication (none, basic, bearer, API key)
 * - Polling intervals
 * - Error handling
 * - Response path extraction
 */
class ApiSourceSpec extends AnyWordSpec with Matchers with SourceTestFixtures with BeforeAndAfterAll {

  private var mockServerBinding: Option[Http.ServerBinding] = None
  private var serverPort:        Int                        = 0

  // Test data
  @volatile private var usersData: List[Map[String, Any]] = List(
    Map("id" -> "user-1", "name" -> "Alice", "age"   -> 30),
    Map("id" -> "user-2", "name" -> "Bob", "age"     -> 25),
    Map("id" -> "user-3", "name" -> "Charlie", "age" -> 35),
    Map("id" -> "user-4", "name" -> "Diana", "age"   -> 28),
    Map("id" -> "user-5", "name" -> "Eve", "age"     -> 32),
  )

  @volatile private var requestCount:   Int                = 0
  @volatile private var lastAuthHeader: Option[HttpHeader] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    startMockServer()
  }

  override def afterAll(): Unit = {
    stopMockServer()
    super.afterAll()
  }

  private def startMockServer(): Unit = {
    val route: Route = concat(
      // Simple endpoint - returns all users
      path("users") {
        get {
          requestCount += 1
          extractRequest {
            request =>
              lastAuthHeader = request.headers.find(h => h.is("authorization") || h.name() == "x-api-key")
              complete(
                HttpEntity(
                  ContentTypes.`application/json`,
                  s"""{"data": ${toJson(usersData)}}""",
                ),
              )
          }
        }
      },
      // Pagination endpoint - offset-based
      path("users" / "paginated") {
        get {
          parameters("offset".as[Int] ? 0, "limit".as[Int] ? 2) {
            (offset, limit) =>
              val page = usersData.slice(offset, offset + limit)
              complete(
                HttpEntity(
                  ContentTypes.`application/json`,
                  s"""{"data": ${toJson(page)}, "total": ${usersData.size}, "offset": $offset, "limit": $limit}""",
                ),
              )
          }
        }
      },
      // Pagination endpoint - page-based
      path("users" / "pages") {
        get {
          parameters("page".as[Int] ? 1, "limit".as[Int] ? 2) {
            (page, limit) =>
              val offset   = (page - 1) * limit
              val pageData = usersData.slice(offset, offset + limit)
              val hasMore  = offset + limit < usersData.size
              complete(
                HttpEntity(
                  ContentTypes.`application/json`,
                  s"""{"data": ${toJson(pageData)}, "page": $page, "has_more": $hasMore}""",
                ),
              )
          }
        }
      },
      // Pagination endpoint - cursor-based
      path("users" / "cursor") {
        get {
          parameters("cursor" ? "", "limit".as[Int] ? 2) {
            (cursor, limit) =>
              val offset     = if (cursor.isEmpty) 0 else cursor.toInt
              val pageData   = usersData.slice(offset, offset + limit)
              val nextCursor = if (offset + limit < usersData.size) (offset + limit).toString else ""
              complete(
                HttpEntity(
                  ContentTypes.`application/json`,
                  s"""{"data": ${toJson(pageData)}, "next_cursor": "$nextCursor"}""",
                ),
              )
          }
        }
      },
      // Nested response path
      path("api" / "v1" / "users") {
        get {
          complete(
            HttpEntity(
              ContentTypes.`application/json`,
              s"""{"status": "ok", "result": {"users": ${toJson(usersData)}}}""",
            ),
          )
        }
      },
      // Error endpoint
      path("error") {
        get {
          complete(StatusCodes.InternalServerError -> "Server error")
        }
      },
    )

    val bindingFuture = Http().newServerAt("localhost", 0).bind(route)
    val binding       = Await.result(bindingFuture, 10.seconds)
    serverPort = binding.localAddress.getPort
    mockServerBinding = Some(binding)

    println(s"Mock API server started on port $serverPort")
  }

  private def stopMockServer(): Unit = {
    mockServerBinding.foreach {
      binding =>
        Await.result(binding.unbind(), 5.seconds)
    }
  }

  private def toJson(data: List[Map[String, Any]]): String = {
    val items = data.map {
      item =>
        val fields = item.map {
          case (k, v: String) => s""""$k": "$v""""
          case (k, v: Int)    => s""""$k": $v"""
          case (k, v)         => s""""$k": "$v""""
        }.mkString(", ")
        s"{$fields}"
    }.mkString(", ")
    s"[$items]"
  }

  "ApiSource" when {

    "consuming basic JSON responses" should {

      "parse JSON response correctly" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users",
          method = "GET",
          authType = "none",
          paginationType = "none",
          responsePath = "data",
        )

        val source  = ApiSource("test-pipeline-api-1", config)
        val records = Await.result(
          source.stream().take(5).runWith(Sink.seq),
          10.seconds,
        )

        records should have size 5
        records.head.data("name") shouldBe "Alice"
        records.head.data("age") shouldBe "30"
        records.head.metadata("source") shouldBe "api"
        records.head.metadata("api_url") shouldBe s"http://localhost:$serverPort/users"
      }

      "extract ID from JSON" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users",
          responsePath = "data",
        )

        val source  = ApiSource("test-pipeline-api-2", config)
        val records = Await.result(
          source.stream().take(5).runWith(Sink.seq),
          10.seconds,
        )

        records.head.id shouldBe "user-1"
        records(1).id shouldBe "user-2"
      }

      "handle nested response path" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/api/v1/users",
          responsePath = "result.users",
        )

        val source  = ApiSource("test-pipeline-api-3", config)
        val records = Await.result(
          source.stream().take(5).runWith(Sink.seq),
          10.seconds,
        )

        records should have size 5
        records.head.data("name") shouldBe "Alice"
      }
    }

    "handling pagination" should {

      "support offset-based pagination" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users/paginated",
          paginationType = "offset",
          paginationParam = "offset",
          paginationSizeParam = "limit",
          paginationSize = 2,
          responsePath = "data",
        )

        val source = ApiSource("test-pipeline-api-4", config)

        // Poll twice to get 2 pages
        val records = Await.result(
          source.stream().take(4).runWith(Sink.seq),
          10.seconds,
        )

        records should have size 4
        records.head.data("name") shouldBe "Alice"
        records(1).data("name") shouldBe "Bob"
        records(2).data("name") shouldBe "Charlie"
        records(3).data("name") shouldBe "Diana"
      }

      "support page-based pagination" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users/pages",
          paginationType = "page",
          paginationParam = "page",
          paginationSizeParam = "limit",
          paginationSize = 2,
          responsePath = "data",
        )

        val source = ApiSource("test-pipeline-api-5", config)

        val records = Await.result(
          source.stream().take(4).runWith(Sink.seq),
          10.seconds,
        )

        records should have size 4
      }

      "support cursor-based pagination" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users/cursor",
          paginationType = "cursor",
          paginationParam = "cursor",
          paginationSizeParam = "limit",
          paginationSize = 2,
          responsePath = "data",
        )

        val source = ApiSource("test-pipeline-api-6", config)

        val records = Await.result(
          source.stream().take(4).runWith(Sink.seq),
          10.seconds,
        )

        records should have size 4
      }
    }

    "handling authentication" should {

      "support no authentication" in {
        lastAuthHeader = None

        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users",
          authType = "none",
          responsePath = "data",
        )

        val source = ApiSource("test-pipeline-api-7", config)
        Await.result(
          source.stream().take(1).runWith(Sink.ignore),
          10.seconds,
        )

        lastAuthHeader shouldBe None
      }

      "support basic authentication" in {
        lastAuthHeader = None

        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users",
          authType = "basic",
          authUsername = "testuser",
          authPassword = "testpass",
          responsePath = "data",
        )

        val source = ApiSource("test-pipeline-api-8", config)
        Await.result(
          source.stream().take(1).runWith(Sink.ignore),
          10.seconds,
        )

        lastAuthHeader should not be None
        lastAuthHeader.get.is("authorization") shouldBe true
      }

      "support bearer token authentication" in {
        lastAuthHeader = None

        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users",
          authType = "bearer",
          authToken = "my-bearer-token",
          responsePath = "data",
        )

        val source = ApiSource("test-pipeline-api-9", config)
        Await.result(
          source.stream().take(1).runWith(Sink.ignore),
          10.seconds,
        )

        lastAuthHeader should not be None
        lastAuthHeader.get.is("authorization") shouldBe true
      }

      "support API key authentication" in {
        lastAuthHeader = None

        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users",
          authType = "api-key",
          authToken = "my-api-key",
          responsePath = "data",
        )

        val source = ApiSource("test-pipeline-api-10", config)
        Await.result(
          source.stream().take(1).runWith(Sink.ignore),
          10.seconds,
        )

        lastAuthHeader should not be None
        lastAuthHeader.get.name() shouldBe "X-API-Key"
      }
    }

    "managing lifecycle" should {

      "start and send batches to pipeline" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users",
          responsePath = "data",
          batchSize = 3,
        )

        val source = ApiSource("test-pipeline-api-11", config)
        val probe  = TestProbe[ShardingEnvelope[Command]]()

        source.start(probe.ref)

        // Should receive batch
        eventually(
          condition = {
            val envelope = probe.expectMessageType[ShardingEnvelope[Command]](5.seconds)
            envelope match {
              case ShardingEnvelope(entityId, cmd: IngestBatch) =>
                entityId shouldBe "test-pipeline-api-11"
                cmd.records.nonEmpty shouldBe true
                true
              case _                                            => fail("Expected IngestBatch command")
            }
          },
          timeout = 10.seconds,
          interval = 500.milliseconds,
        )

        Await.result(source.stop(), 5.seconds)
      }

      "stop gracefully" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users",
          responsePath = "data",
        )

        val source = ApiSource("test-pipeline-api-12", config)
        val probe  = TestProbe[ShardingEnvelope[Command]]()

        source.start(probe.ref)
        Thread.sleep(1000)

        val stopFuture = source.stop()
        Await.result(stopFuture, 5.seconds) shouldBe Done

        source.isHealthy shouldBe false
      }

      "report health status correctly" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users",
          responsePath = "data",
        )

        val source = ApiSource("test-pipeline-api-13", config)

        // Initially not running
        source.isHealthy shouldBe false

        // Start
        val probe = TestProbe[ShardingEnvelope[Command]]()
        source.start(probe.ref)

        // Should be healthy
        Thread.sleep(500)
        source.isHealthy shouldBe true

        // Stop
        Await.result(source.stop(), 5.seconds)

        // Should be unhealthy
        eventually(
          condition = {
            source.isHealthy shouldBe false
            true
          },
          timeout = 5.seconds,
        )
      }
    }

    "handling offsets and resume" should {

      "track current offset (page number)" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users/paginated",
          paginationType = "offset",
          paginationSize = 2,
          responsePath = "data",
        )

        val source = ApiSource("test-pipeline-api-14", config)

        // Take from 2 pages
        Await.result(
          source.stream().take(4).runWith(Sink.ignore),
          10.seconds,
        )

        // Offset should have advanced
        source.currentOffset() should be >= 1L
      }

      "resume from specified offset" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users/paginated",
          paginationType = "offset",
          paginationSize = 2,
          responsePath = "data",
        )

        val source = ApiSource("test-pipeline-api-15", config)

        // Resume from page 2
        source.resumeFrom(2)

        // Should start from offset 2 (skipping first 2 pages)
        source.currentOffset() shouldBe 2
      }
    }

    "handling errors" should {

      "handle HTTP errors gracefully" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/error",
          responsePath = "data",
        )

        val source = ApiSource("test-pipeline-api-16", config)

        // Should return empty list on error, not crash
        val records = Await.result(
          source.stream().take(1).runWith(Sink.seq),
          10.seconds,
        )

        // Should be empty due to error
        records shouldBe empty
      }

      "handle invalid response path gracefully" in {
        val config = createApiSourceConfig(
          apiUrl = s"http://localhost:$serverPort/users",
          responsePath = "nonexistent.path",
        )

        val source = ApiSource("test-pipeline-api-17", config)

        val records = Await.result(
          source.stream().take(1).runWith(Sink.seq),
          10.seconds,
        )

        // Should be empty when path doesn't exist
        records shouldBe empty
      }
    }
  }
}
