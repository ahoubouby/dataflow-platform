package com.dataflow.sources.api

import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import com.dataflow.domain.commands.{Command, IngestBatch}
import com.dataflow.domain.models.{DataRecord, SourceConfig}
import com.dataflow.sources.models.SourceState
import com.dataflow.sources.{Source, SourceMetricsReporter}
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials, OAuth2BearerToken}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.{KillSwitches, SystemMaterializer}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source => PekkoSource}
import org.slf4j.LoggerFactory
import spray.json._

/**
 * API source connector for reading data from REST APIs.
 *
 * Supported features:
 * - HTTP GET/POST requests
 * - Polling with configurable intervals
 * - Pagination support (offset-based, cursor-based, page-based)
 * - Authentication (Basic, Bearer token, API key)
 * - JSON response parsing
 * - Rate limiting
 *
 * Configuration:
 *  {{{
 *    SourceConfig(
 *      sourceType = SourceType.Api,
 *      connectionString = "https://api.example.com/data",
 *      options = Map(
 *        "method" -> "GET",                               // HTTP method
 *        "auth-type" -> "bearer",                         // none, basic, bearer, api-key
 *        "auth-token" -> "your-token",                    // For bearer/api-key auth
 *        "auth-username" -> "user",                       // For basic auth
 *        "auth-password" -> "pass",                       // For basic auth
 *        "pagination-type" -> "offset",                   // none, offset, cursor, page
 *        "pagination-param" -> "offset",                  // Query param name
 *        "pagination-size-param" -> "limit",              // Size param name
 *        "pagination-size" -> "100",                      // Records per request
 *        "response-path" -> "data",                       // JSON path to data array
 *        "rate-limit-enabled" -> "true",                  // Enable rate limiting
 *        "rate-limit-requests-per-second" -> "10",        // Max requests per second
 *        "rate-limit-burst" -> "20",                      // Burst capacity
 *        "headers" -> """{"Content-Type":"application/json"}"""
 *      ),
 *      batchSize = 100,
 *      pollIntervalMs = 5000  // Poll every 5 seconds
 *    )
 *  }}}
 */
class RestApiSource(
  val pipelineId: String,
  val config: SourceConfig,
)(implicit system: ActorSystem[_]) extends Source {

  private val log = LoggerFactory.getLogger(getClass)

  implicit private val ec: ExecutionContext = system.executionContext
  implicit private val mat = SystemMaterializer(system).materializer

  override val sourceId: String = s"api-source-$pipelineId-${UUID.randomUUID()}"

  // ----- options -----
  private val apiUrl: String = config.connectionString

  private val method: HttpMethod =
    config.options.getOrElse("method", "GET").toUpperCase match {
      case "GET"  => HttpMethods.GET
      case "POST" => HttpMethods.POST
      case other  => throw new IllegalArgumentException(s"Unsupported HTTP method: $other")
    }

  private val authType: String =
    config.options.getOrElse("auth-type", "none").toLowerCase

  private val paginationType: String =
    config.options.getOrElse("pagination-type", "none").toLowerCase

  private val paginationParam: String =
    config.options.getOrElse("pagination-param", "offset")

  private val paginationSizeParam: String =
    config.options.getOrElse("pagination-size-param", "limit")

  private val paginationSize: Int =
    config.options.getOrElse("pagination-size", "100").toInt

  private val responsePath: String =
    config.options.getOrElse("response-path", "data")

  // Rate limiting configuration
  private val rateLimitEnabled: Boolean =
    config.options.getOrElse("rate-limit-enabled", "false").toBoolean

  private val rateLimitRequestsPerSecond: Int =
    config.options.getOrElse("rate-limit-requests-per-second", "10").toInt

  private val rateLimitBurst: Int =
    config.options.getOrElse("rate-limit-burst", "20").toInt

  // State
  @volatile private var currentPage:   Long                                             = 0
  @volatile private var currentCursor: Option[String]                                   = None
  @volatile private var isRunning:     Boolean                                          = false
  @volatile private var killSwitch:    Option[org.apache.pekko.stream.UniqueKillSwitch] = None

  log.info(
    "Initialized RestApiSource id={} url={} method={} pagination={} batchSize={}",
    sourceId,
    apiUrl,
    method.value,
    paginationType,
    config.batchSize,
  )

  /**
   * Create streaming source from API.
   */
  override def stream(): PekkoSource[DataRecord, Future[Done]] = {
    val base: PekkoSource[DataRecord, NotUsed] =
      buildDataStream()

    base.mapMaterializedValue(_ => Future.successful(Done))
  }

  private def buildDataStream(): PekkoSource[DataRecord, NotUsed] = {
    // Create a tick source that polls the API at regular intervals
    val tickSource = PekkoSource
      .tick(0.seconds, config.pollIntervalMs.milliseconds, ())

    // Apply rate limiting if enabled
    val rateLimitedSource = if (rateLimitEnabled) {
      log.info(
        "Rate limiting enabled: {} req/s with burst of {}",
        rateLimitRequestsPerSecond,
        rateLimitBurst,
      )
      tickSource.throttle(
        rateLimitRequestsPerSecond,
        1.second,
        rateLimitBurst,
        org.apache.pekko.stream.ThrottleMode.Shaping,
      )
    } else {
      tickSource
    }

    rateLimitedSource
      .mapAsync(1) {
        _ =>
          fetchFromApi()
      }
      .mapConcat(identity) // Flatten List[DataRecord] to individual records
      .mapMaterializedValue(_ => NotUsed)
  }

  /**
   * Fetch data from API.
   */
  private def fetchFromApi(): Future[List[DataRecord]] = {
    val request     = buildRequest()
    val startTimeMs = System.currentTimeMillis()

    Http()
      .singleRequest(request)
      .flatMap {
        response =>
          val responseTimeMs = System.currentTimeMillis() - startTimeMs
          val statusCode     = response.status.intValue()

          // Record API request metrics
          SourceMetricsReporter.recordApiRequest(
            pipelineId,
            apiUrl,
            method.value,
            responseTimeMs,
            statusCode,
          )

          response.status match {
            case StatusCodes.OK =>
              Unmarshal(response.entity).to[String].map {
                body =>
                  SourceMetricsReporter.recordBytesRead(pipelineId, "api", body.getBytes("UTF-8").length.toLong)
                  parseResponse(body)
              }

            case status =>
              response.discardEntityBytes()
              log.warn("API request failed with status: {}", status)
              SourceMetricsReporter.recordError(pipelineId, "api", s"http_${statusCode}")
              Future.successful(List.empty)
          }
      }
      .recover {
        case ex =>
          log.error("API request failed: {}", ex.getMessage)
          SourceMetricsReporter.recordConnectionError(pipelineId, "api")
          SourceMetricsReporter.recordError(pipelineId, "api", "connection_failure")
          List.empty
      }
  }

  /**
   * Build HTTP request with authentication and pagination.
   */
  private def buildRequest(): HttpRequest = {
    // Build URI with pagination params
    val uriWithPagination = paginationType match {
      case "offset" =>
        val offset = currentPage * paginationSize
        s"$apiUrl?$paginationParam=$offset&$paginationSizeParam=$paginationSize"

      case "page" =>
        s"$apiUrl?$paginationParam=${currentPage + 1}&$paginationSizeParam=$paginationSize"

      case "cursor" =>
        currentCursor match {
          case Some(cursor) => s"$apiUrl?$paginationParam=$cursor&$paginationSizeParam=$paginationSize"
          case None         => s"$apiUrl?$paginationSizeParam=$paginationSize"
        }

      case _ =>
        apiUrl
    }

    val uri = Uri(uriWithPagination)

    // Build headers with authentication
    val authHeader = authType match {
      case "basic" =>
        val username = config.options.getOrElse("auth-username", "")
        val password = config.options.getOrElse("auth-password", "")
        Some(Authorization(BasicHttpCredentials(username, password)))

      case "bearer" =>
        val token = config.options.getOrElse("auth-token", "")
        Some(Authorization(OAuth2BearerToken(token)))

      case "api-key" =>
        val keyName  = config.options.getOrElse("auth-key-name", "X-API-Key")
        val keyValue = config.options.getOrElse("auth-token", "")
        Some(headers.RawHeader(keyName, keyValue))

      case _ =>
        None
    }

    // Parse additional headers from config
    val additionalHeaders = config.options.get("headers").flatMap {
      headersJson =>
        Try {
          headersJson.parseJson.asJsObject.fields.map {
            case (name, JsString(value)) => headers.RawHeader(name, value)
            case (name, other)           => headers.RawHeader(name, other.toString)
          }.toList
        }.toOption
    }.getOrElse(List.empty)

    val allHeaders = authHeader.toList ++ additionalHeaders

    HttpRequest(
      method = method,
      uri = uri,
      headers = allHeaders,
    )
  }

  /**
   * Parse JSON response to DataRecords.
   */
  private def parseResponse(body: String): List[DataRecord] = {
    Try {
      val json = body.parseJson.asJsObject

      // Extract data array from response (follow responsePath)
      val dataArray = responsePath.split("\\.").foldLeft(json: JsValue) {
        case (obj: JsObject, key) => obj.fields.getOrElse(key, JsArray())
        case (arr: JsArray, _)    => arr
        case _                    => JsArray()
      }

      dataArray match {
        case JsArray(elements) =>
          val records = elements.flatMap {
            element =>
              element match {
                case obj: JsObject => Some(parseJsonObject(obj))
                case _             => None
              }
          }.toList

          // Update pagination state
          updatePaginationState(json, records.size)

          records

        case _ =>
          log.warn("Response path '{}' did not return array", responsePath)
          SourceMetricsReporter.recordError(pipelineId, "api", "invalid_response_path")
          List.empty
      }
    } match {
      case Success(records) => records
      case Failure(ex)      =>
        log.warn("Failed to parse API response: {}", ex.getMessage)
        SourceMetricsReporter.recordParseError(pipelineId, "api", "json")
        List.empty
    }
  }

  /**
   * Parse JSON object to DataRecord.
   */
  private def parseJsonObject(obj: JsObject): DataRecord = {
    val data = obj.fields.map {
      case (key, value) =>
        key -> value.toString.stripPrefix("\"").stripSuffix("\"")
    }

    val id = obj.fields
      .get("id")
      .map(_.toString.stripPrefix("\"").stripSuffix("\""))
      .getOrElse(UUID.randomUUID().toString)

    // Record metrics
    SourceMetricsReporter.recordRecordsRead(pipelineId, "api", 1)

    DataRecord(
      id = id,
      data = data - "id",
      metadata = Map(
        "source"    -> "api",
        "source_id" -> sourceId,
        "api_url"   -> apiUrl,
        "page"      -> currentPage.toString,
        "format"    -> "json",
        "timestamp" -> Instant.now().toString,
      ),
    )
  }

  /**
   * Update pagination state based on response.
   */
  private def updatePaginationState(response: JsObject, recordCount: Int): Unit = {
    paginationType match {
      case "offset" | "page" =>
        if (recordCount > 0) {
          currentPage += 1
          SourceMetricsReporter.updateApiPaginationPage(pipelineId, currentPage.toInt)
        }

      case "cursor" =>
        // Look for next cursor in response (common field names)
        val nextCursor = response.fields.get("next_cursor")
          .orElse(response.fields.get("nextCursor"))
          .orElse(response.fields.get("cursor"))
          .map(_.toString.stripPrefix("\"").stripSuffix("\""))

        currentCursor = nextCursor

      case _ => // no pagination
    }
  }

  /**
   * Start polling API and sending batches to pipeline.
   */
  override def start(
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
  ): Future[Done] = {
    if (isRunning) {
      log.warn("RestApiSource {} already running", sourceId)
      Future.successful(Done)
    } else {
      log.info("Starting RestApiSource {} for {}", sourceId, apiUrl)
      isRunning = true

      // Update health metrics
      SourceMetricsReporter.updateHealth(pipelineId, "api", isHealthy = true)

      val (switch, doneF) =
        buildDataStream()
          .viaMat(KillSwitches.single)(Keep.right)
          .grouped(config.batchSize)
          .mapAsync(1)(records => sendBatch(records.toList, pipelineShardRegion))
          .toMat(Sink.ignore)(Keep.both)
          .run()

      killSwitch = Some(switch)

      doneF.onComplete {
        case Success(_)  =>
          log.info("RestApiSource {} completed", sourceId)
          isRunning = false
          SourceMetricsReporter.updateHealth(pipelineId, "api", isHealthy = false)
        case Failure(ex) =>
          log.error("RestApiSource {} failed: {}", sourceId, ex.getMessage, ex)
          isRunning = false
          SourceMetricsReporter.recordError(pipelineId, "api", "stream_failure")
          SourceMetricsReporter.updateHealth(pipelineId, "api", isHealthy = false)
      }

      Future.successful(Done)
    }
  }

  /**
   * Send batch of records to pipeline.
   */
  private def sendBatch(
    records: List[DataRecord],
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
  ): Future[Done] = {
    if (records.isEmpty) {
      return Future.successful(Done)
    }

    val batchId     = UUID.randomUUID().toString
    val offset      = currentPage
    val sendTimeMs  = System.currentTimeMillis()

    log.debug(
      "Sending batch: batchId={} records={} page={} url={}",
      batchId,
      records.size,
      offset,
      apiUrl,
    )

    val command = IngestBatch(
      pipelineId = pipelineId,
      batchId = batchId,
      records = records,
      sourceOffset = offset,
      replyTo = system.ignoreRef,
    )

    pipelineShardRegion ! ShardingEnvelope(pipelineId, command)

    // Record batch metrics
    val latencyMs = System.currentTimeMillis() - sendTimeMs
    SourceMetricsReporter.recordBatchSent(pipelineId, "api", records.size, latencyMs)
    SourceMetricsReporter.updateOffset(pipelineId, "api", offset)

    Future.successful(Done)
  }

  /**
   * Stop polling API.
   */
  override def stop(): Future[Done] = {
    if (!isRunning) {
      log.warn("RestApiSource {} not running", sourceId)
      return Future.successful(Done)
    }

    log.info("Stopping RestApiSource {}", sourceId)

    killSwitch.foreach(_.shutdown())
    killSwitch = None
    isRunning = false

    // Update health metrics
    SourceMetricsReporter.updateHealth(pipelineId, "api", isHealthy = false)

    Future.successful(Done)
  }

  override def currentOffset(): Long = currentPage

  override def resumeFrom(offset: Long): Unit = {
    log.info("Resuming RestApiSource from page: {}", offset)
    currentPage = offset
  }

  override def isHealthy: Boolean = isRunning

  override def state: SourceState =
    if (isRunning) SourceState.Running
    else SourceState.Stopped
}

/**
 * Companion object with factory method.
 */
object RestApiSource {

  def apply(pipelineId: String, config: SourceConfig)(implicit system: ActorSystem[_]): RestApiSource =
    new RestApiSource(pipelineId, config)
}
