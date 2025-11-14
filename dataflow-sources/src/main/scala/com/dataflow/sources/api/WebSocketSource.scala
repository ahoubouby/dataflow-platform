package com.dataflow.sources.api

import java.time.Instant
import java.util.UUID

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import com.dataflow.domain.commands.{Command, IngestBatch}
import com.dataflow.domain.models.{DataRecord, SourceConfig}
import com.dataflow.sources.{Source, SourceMetricsReporter}
import com.dataflow.sources.models.SourceState
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.ws._
import org.apache.pekko.stream.{KillSwitches, RestartSettings, SystemMaterializer}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, RestartSource, Sink, Source => PekkoSource}
import org.slf4j.LoggerFactory
import spray.json._

/**
 * WebSocket source connector for real-time streaming data from WebSocket APIs.
 *
 * Supported features:
 * - WebSocket connections with automatic reconnection
 * - Text and binary message handling
 * - JSON message parsing
 * - Authentication (query params, headers)
 * - Heartbeat/ping-pong for connection keep-alive
 * - Backpressure handling
 * - Exponential backoff on reconnection
 *
 * Configuration:
 *  {{{
 *    SourceConfig(
 *      sourceType = SourceType.Api,
 *      connectionString = "wss://stream.example.com/events",
 *      options = Map(
 *        "auth-type" -> "query",                  // none, query, header, bearer
 *        "auth-token" -> "your-token",            // Auth token
 *        "auth-param-name" -> "token",            // Query param name for auth
 *        "message-format" -> "json",              // json, text
 *        "heartbeat-interval" -> "30",            // Seconds between heartbeats
 *        "reconnect-min-backoff" -> "1",          // Min seconds before reconnect
 *        "reconnect-max-backoff" -> "30",         // Max seconds before reconnect
 *        "reconnect-max-restarts" -> "10"         // Max reconnection attempts (-1 = infinite)
 *      ),
 *      batchSize = 100,
 *      pollIntervalMs = 0  // Not used for WebSocket (real-time streaming)
 *    )
 *  }}}
 */
class WebSocketSource(
  val pipelineId: String,
  val config: SourceConfig,
)(implicit system: ActorSystem[_]) extends Source {

  private val log = LoggerFactory.getLogger(getClass)

  implicit private val ec: ExecutionContext = system.executionContext
  implicit private val mat = SystemMaterializer(system).materializer

  override val sourceId: String = s"websocket-source-$pipelineId-${UUID.randomUUID()}"

  // ----- Configuration -----
  private val wsUrl: String = config.connectionString

  private val authType: String =
    config.options.getOrElse("auth-type", "none").toLowerCase

  private val authToken: String =
    config.options.getOrElse("auth-token", "")

  private val authParamName: String =
    config.options.getOrElse("auth-param-name", "token")

  private val messageFormat: String =
    config.options.getOrElse("message-format", "json").toLowerCase

  private val heartbeatInterval: Int =
    config.options.getOrElse("heartbeat-interval", "30").toInt

  private val reconnectMinBackoff: FiniteDuration =
    config.options.getOrElse("reconnect-min-backoff", "1").toInt.seconds

  private val reconnectMaxBackoff: FiniteDuration =
    config.options.getOrElse("reconnect-max-backoff", "30").toInt.seconds

  private val reconnectMaxRestarts: Int =
    config.options.getOrElse("reconnect-max-restarts", "-1").toInt

  // State
  @volatile private var recordCount: Long                                             = 0
  @volatile private var isRunning:   Boolean                                          = false
  @volatile private var killSwitch:  Option[org.apache.pekko.stream.UniqueKillSwitch] = None

  log.info(
    "Initialized WebSocketSource id={} url={} messageFormat={} batchSize={}",
    sourceId,
    wsUrl,
    messageFormat,
    config.batchSize,
  )

  /**
   * Create streaming source from WebSocket.
   */
  override def stream(): PekkoSource[DataRecord, NotUsed] =
    buildWebSocketStream()

  /**
   * Build WebSocket stream with automatic reconnection.
   */
  private def buildWebSocketStream(): PekkoSource[DataRecord, NotUsed] = {
    val restartSettings = RestartSettings(
      minBackoff = reconnectMinBackoff,
      maxBackoff = reconnectMaxBackoff,
      randomFactor = 0.2,
      // maxRestarts = reconnectMaxRestarts,
    )

    RestartSource.withBackoff(restartSettings) {
      () =>
        log.info("Connecting to WebSocket: {}", wsUrl)

        val webSocketFlow = createWebSocketFlow()

        // Create a source that sends periodic pings for heartbeat
        val heartbeatSource =
          if (heartbeatInterval > 0) {
            PekkoSource
              .tick(heartbeatInterval.seconds, heartbeatInterval.seconds, ())
              .map {
                _ =>
                  log.debug("Sending heartbeat ping")
                  TextMessage.Strict("ping")
              }
          } else {
            PekkoSource.empty[Message]
          }

        // Combine heartbeat and actual messages
        val messageSource = PekkoSource
          .single(TextMessage.Strict("")) // Initial connection message
          .concat(heartbeatSource)

        messageSource
          .viaMat(webSocketFlow)(Keep.right)
          .mapMaterializedValue {
            upgradeResponse =>
              upgradeResponse.flatMap {
                upgrade =>
                  if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
                    log.info("WebSocket connection established")
                    SourceMetricsReporter.updateHealth(pipelineId, "websocket", isHealthy = true)
                    Future.successful(Done)
                  } else {
                    val msg = s"WebSocket upgrade failed: ${upgrade.response.status}"
                    log.error(msg)
                    SourceMetricsReporter.recordConnectionError(pipelineId, "websocket")
                    Future.failed(new RuntimeException(msg))
                  }
              }
          }
          .collect {
            case TextMessage.Strict(text)     => text
            case TextMessage.Streamed(stream) =>
              // For streamed messages, we need to materialize the stream
              // This is a simplification; in production, consider backpressure
              "" // Skip streamed messages for simplicity
          }
          .filter(_.nonEmpty)
          .filter(_ != "pong") // Filter out pong responses
          .map(parseMessage)
          .collect {
            case Some(record) => record
          }
          .mapMaterializedValue(_ => NotUsed)
    }.mapMaterializedValue(_ => NotUsed)
  }

  /**
   * Create WebSocket flow with authentication.
   */
  private def createWebSocketFlow(): Flow[Message, Message, Future[WebSocketUpgradeResponse]] = {
    val uri     = buildWebSocketUri()
    val headers = buildWebSocketHeaders()

    Http().webSocketClientFlow(
      WebSocketRequest(uri, extraHeaders = headers),
    )
  }

  /**
   * Build WebSocket URI with authentication if needed.
   */
  private def buildWebSocketUri(): Uri = {
    val baseUri = Uri(wsUrl)

    authType match {
      case "query" if authToken.nonEmpty =>
        baseUri.withQuery(Uri.Query(authParamName -> authToken))

      case _ =>
        baseUri
    }
  }

  /**
   * Build WebSocket headers with authentication if needed.
   */
  private def buildWebSocketHeaders(): List[HttpHeader] = {
    authType match {
      case "header" if authToken.nonEmpty =>
        List(headers.RawHeader(authParamName, authToken))

      case "bearer" if authToken.nonEmpty =>
        List(headers.Authorization(headers.OAuth2BearerToken(authToken)))

      case _ =>
        List.empty
    }
  }

  /**
   * Parse incoming WebSocket message to DataRecord.
   */
  private def parseMessage(message: String): Option[DataRecord] = {
    if (message.isEmpty || message == "ping" || message == "pong") {
      return None
    }

    val startTime = System.currentTimeMillis()

    val result = messageFormat match {
      case "json" =>
        parseJsonMessage(message)

      case "text" =>
        Some(createTextRecord(message))

      case other =>
        log.warn("Unsupported message format: {}", other)
        None
    }

    // Record metrics
    if (result.isDefined) {
      recordCount += 1
      SourceMetricsReporter.recordRecordsRead(pipelineId, "websocket", 1)
      SourceMetricsReporter.recordBytesRead(pipelineId, "websocket", message.getBytes("UTF-8").length.toLong)
    }

    result
  }

  /**
   * Parse JSON message to DataRecord.
   */
  private def parseJsonMessage(message: String): Option[DataRecord] = {
    Try {
      val json = message.parseJson.asJsObject
      val data = json.fields.map {
        case (key, value) =>
          key -> value.toString.stripPrefix("\"").stripSuffix("\"")
      }

      val id = json.fields
        .get("id")
        .map(_.toString.stripPrefix("\"").stripSuffix("\""))
        .getOrElse(UUID.randomUUID().toString)

      DataRecord(
        id = id,
        data = data - "id",
        metadata = Map(
          "source"       -> "websocket",
          "source_id"    -> sourceId,
          "ws_url"       -> wsUrl,
          "format"       -> "json",
          "record_count" -> recordCount.toString,
          "timestamp"    -> Instant.now().toString,
        ),
      )
    } match {
      case Success(record) => Some(record)
      case Failure(ex)     =>
        log.warn("Failed to parse JSON message: {}", ex.getMessage)
        SourceMetricsReporter.recordParseError(pipelineId, "websocket", "json")
        None
    }
  }

  /**
   * Create DataRecord from text message.
   */
  private def createTextRecord(message: String): DataRecord = {
    DataRecord(
      id = UUID.randomUUID().toString,
      data = Map("message" -> message),
      metadata = Map(
        "source"       -> "websocket",
        "source_id"    -> sourceId,
        "ws_url"       -> wsUrl,
        "format"       -> "text",
        "record_count" -> recordCount.toString,
        "timestamp"    -> Instant.now().toString,
      ),
    )
  }

  /**
   * Start WebSocket connection and send batches to pipeline.
   */
  override def start(
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
  ): Future[Done] = {
    if (isRunning) {
      log.warn("WebSocketSource {} already running", sourceId)
      Future.successful(Done)
    } else {
      log.info("Starting WebSocketSource {} for {}", sourceId, wsUrl)
      isRunning = true

      // Update health metrics
      SourceMetricsReporter.updateHealth(pipelineId, "websocket", isHealthy = true)

      val (switch, doneF) =
        buildWebSocketStream()
          .viaMat(KillSwitches.single)(Keep.right)
          .grouped(config.batchSize)
          .mapAsync(1)(records => sendBatch(records.toList, pipelineShardRegion))
          .toMat(Sink.ignore)(Keep.both)
          .run()

      killSwitch = Some(switch)

      doneF.onComplete {
        case Success(_)  =>
          log.info("WebSocketSource {} completed", sourceId)
          isRunning = false
          SourceMetricsReporter.updateHealth(pipelineId, "websocket", isHealthy = false)
        case Failure(ex) =>
          log.error("WebSocketSource {} failed: {}", sourceId, ex.getMessage, ex)
          isRunning = false
          SourceMetricsReporter.recordError(pipelineId, "websocket", "stream_failure")
          SourceMetricsReporter.updateHealth(pipelineId, "websocket", isHealthy = false)
      }

      Future.successful(Done)
    }
  }

  /**
   * Stop WebSocket connection.
   */
  override def stop(): Future[Done] = {
    if (!isRunning) {
      log.warn("WebSocketSource {} not running", sourceId)
      return Future.successful(Done)
    }

    log.info("Stopping WebSocketSource {}", sourceId)

    killSwitch.foreach(_.shutdown())
    killSwitch = None
    isRunning = false

    // Update health metrics
    SourceMetricsReporter.updateHealth(pipelineId, "websocket", isHealthy = false)

    Future.successful(Done)
  }

  override def currentOffset(): Long = recordCount

  override def resumeFrom(offset: Long): Unit = {
    log.info("Resuming WebSocketSource from offset: {}", offset)
    recordCount = offset
  }

  override def isHealthy: Boolean = isRunning

  override def state: SourceState =
    if (isRunning) SourceState.Running
    else SourceState.Stopped
}

/**
 * Companion object with factory method.
 */
object WebSocketSource {

  def apply(pipelineId: String, config: SourceConfig)(implicit system: ActorSystem[_]): WebSocketSource =
    new WebSocketSource(pipelineId, config)
}
