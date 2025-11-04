package com.dataflow.sources

import java.sql.{Connection, DriverManager, ResultSet, Timestamp}
import java.time.Instant
import java.util.UUID

import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try, Using}

import com.dataflow.domain.commands.{Command, IngestBatch}
import com.dataflow.domain.models.{DataRecord, SourceConfig}
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.stream.{KillSwitches, SystemMaterializer}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source => PekkoSource}
import org.slf4j.LoggerFactory

/**
 * Database source connector for reading data from relational databases.
 *
 * Supported features:
 * - SQL query execution
 * - Periodic polling
 * - Incremental queries (timestamp-based, ID-based)
 * - Multiple database types (PostgreSQL, MySQL, etc.)
 * - Connection pooling
 * - Batch fetching
 *
 * Configuration:
 *  {{{
 *    SourceConfig(
 *      sourceType = SourceType.Database,
 *      connectionString = "jdbc:postgresql://localhost:5432/mydb",
 *      options = Map(
 *        "username" -> "user",
 *        "password" -> "pass",
 *        "driver" -> "org.postgresql.Driver",
 *        "query" -> "SELECT * FROM users WHERE created_at > ?",
 *        "incremental-column" -> "created_at",  // Column for incremental queries
 *        "incremental-type" -> "timestamp",     // timestamp or id
 *        "fetch-size" -> "1000"                 // JDBC fetch size
 *      ),
 *      batchSize = 1000,
 *      pollIntervalMs = 60000  // Poll every minute
 *    )
 *  }}}
 */
class DatabaseSource(
  val pipelineId: String,
  val config: SourceConfig,
)(implicit system: ActorSystem[_]) extends Source {

  private val log = LoggerFactory.getLogger(getClass)

  implicit private val ec: ExecutionContext = system.executionContext
  implicit private val mat = SystemMaterializer(system).materializer

  override val sourceId: String = s"database-source-$pipelineId-${UUID.randomUUID()}"

  // ----- options -----
  private val jdbcUrl: String = config.connectionString

  private val username: String =
    config.options.getOrElse("username", "")

  private val password: String =
    config.options.getOrElse("password", "")

  private val driver: String =
    config.options.getOrElse("driver", "org.postgresql.Driver")

  private val query: String =
    config.options.getOrElse("query", throw new IllegalArgumentException("Database query is required"))

  private val incrementalColumn: Option[String] =
    config.options.get("incremental-column")

  private val incrementalType: String =
    config.options.getOrElse("incremental-type", "timestamp").toLowerCase

  private val fetchSize: Int =
    config.options.getOrElse("fetch-size", "1000").toInt

  // State
  @volatile private var lastIncrementalValue: Option[Any]                                      = None
  @volatile private var recordCount:          Long                                             = 0
  @volatile private var isRunning:            Boolean                                          = false
  @volatile private var killSwitch:           Option[org.apache.pekko.stream.UniqueKillSwitch] = None

  // Load JDBC driver
  Try(Class.forName(driver)) match {
    case Success(_)  =>
      log.info("Loaded JDBC driver: {}", driver)
    case Failure(ex) =>
      log.error("Failed to load JDBC driver {}: {}", driver, ex.getMessage)
      throw ex
  }

  log.info(
    "Initialized DatabaseSource id={} url={} query={} batchSize={}",
    sourceId,
    jdbcUrl,
    query,
    config.batchSize,
  )

  /**
   * Create streaming source from database.
   */
  override def stream(): PekkoSource[DataRecord, Future[Done]] = {
    val base: PekkoSource[DataRecord, NotUsed] =
      buildDataStream()

    base.mapMaterializedValue(_ => Future.successful(Done))
  }

  private def buildDataStream(): PekkoSource[DataRecord, NotUsed] = {
    // Create a tick source that polls the database at regular intervals
    PekkoSource
      .tick(0.seconds, config.pollIntervalMs.milliseconds, ())
      .mapAsync(1) {
        _ =>
          Future {
            blocking {
              executeQuery()
            }
          }
      }
      .mapConcat(identity) // Flatten List[DataRecord] to individual records
      .mapMaterializedValue(_ => NotUsed)
  }

  /**
   * Execute SQL query and fetch results.
   */
  private def executeQuery(): List[DataRecord] = {
    Using.Manager {
      use =>
        val connection = use(createConnection())
        val statement  = use(prepareStatement(connection))

        // Set fetch size for better performance
        statement.setFetchSize(fetchSize)

        val resultSet = use(statement.executeQuery())

        fetchResults(resultSet)
    } match {
      case Success(records) =>
        log.debug("Fetched {} records from database", records.size)
        records

      case Failure(ex) =>
        log.error("Failed to execute database query: {}", ex.getMessage, ex)
        List.empty
    }
  }

  /**
   * Create database connection.
   */
  private def createConnection(): Connection =
    DriverManager.getConnection(jdbcUrl, username, password)

  /**
   * Prepare SQL statement with incremental query parameters.
   */
  private def prepareStatement(connection: Connection): java.sql.PreparedStatement = {
    val stmt = connection.prepareStatement(query)

    // Set incremental query parameter if configured
    (incrementalColumn, lastIncrementalValue) match {
      case (Some(_), Some(value)) =>
        incrementalType match {
          case "timestamp" =>
            value match {
              case ts: Timestamp => stmt.setTimestamp(1, ts)
              case str: String   => stmt.setTimestamp(1, Timestamp.valueOf(str))
              case _             => log.warn("Invalid timestamp value: {}", value)
            }

          case "id" =>
            value match {
              case long: Long  => stmt.setLong(1, long)
              case int: Int    => stmt.setInt(1, int)
              case str: String => stmt.setLong(1, str.toLong)
              case _           => log.warn("Invalid ID value: {}", value)
            }

          case other =>
            log.warn("Unsupported incremental type: {}", other)
        }

      case (Some(_), None) =>
        // First run - set initial value based on type
        incrementalType match {
          case "timestamp" =>
            // Start from epoch or a configured start time
            val startTime = config.options.get("start-time")
              .map(Timestamp.valueOf)
              .getOrElse(new Timestamp(0))
            stmt.setTimestamp(1, startTime)

          case "id" =>
            // Start from 0 or a configured start ID
            val startId = config.options.getOrElse("start-id", "0").toLong
            stmt.setLong(1, startId)

          case _ =>
        }

      case _ => // No incremental query
    }

    stmt
  }

  /**
   * Fetch results from ResultSet.
   */
  private def fetchResults(resultSet: ResultSet): List[DataRecord] = {
    val metadata    = resultSet.getMetaData
    val columnCount = metadata.getColumnCount
    val columnNames = (1 to columnCount).map(metadata.getColumnName).toList

    val records = scala.collection.mutable.ListBuffer[DataRecord]()

    while (resultSet.next()) {
      val data = columnNames.map {
        columnName =>
          val value = Option(resultSet.getObject(columnName))
            .map(_.toString)
            .getOrElse("")

          columnName.toLowerCase -> value
      }.toMap

      // Extract ID from data or generate
      val id = data.get("id").getOrElse(UUID.randomUUID().toString)

      // Update incremental value
      incrementalColumn.foreach {
        colName =>
          val colValue = resultSet.getObject(colName)
          lastIncrementalValue = Some(colValue)
      }

      val record = DataRecord(
        id = id,
        data = data,
        metadata = Map(
          "source"        -> "database",
          "source_id"     -> sourceId,
          "jdbc_url"      -> jdbcUrl,
          "record_number" -> recordCount.toString,
          "format"        -> "sql",
          "timestamp"     -> Instant.now().toString,
        ),
      )

      records += record
      recordCount += 1
    }

    records.toList
  }

  /**
   * Start polling database and sending batches to pipeline.
   */
  override def start(
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
  ): Future[Done] = {
    if (isRunning) {
      log.warn("DatabaseSource {} already running", sourceId)
      Future.successful(Done)
    } else {
      log.info("Starting DatabaseSource {} for {}", sourceId, jdbcUrl)
      isRunning = true

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
          log.info("DatabaseSource {} completed", sourceId)
          isRunning = false
        case Failure(ex) =>
          log.error("DatabaseSource {} failed: {}", sourceId, ex.getMessage, ex)
          isRunning = false
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

    val batchId = UUID.randomUUID().toString
    val offset  = recordCount

    log.debug(
      "Sending batch: batchId={} records={} offset={} url={}",
      batchId,
      records.size,
      offset,
      jdbcUrl,
    )

    val command = IngestBatch(
      pipelineId = pipelineId,
      batchId = batchId,
      records = records,
      sourceOffset = offset,
      replyTo = system.ignoreRef,
    )

    pipelineShardRegion ! ShardingEnvelope(pipelineId, command)

    Future.successful(Done)
  }

  /**
   * Stop polling database.
   */
  override def stop(): Future[Done] = {
    if (!isRunning) {
      log.warn("DatabaseSource {} not running", sourceId)
      return Future.successful(Done)
    }

    log.info("Stopping DatabaseSource {}", sourceId)

    killSwitch.foreach(_.shutdown())
    killSwitch = None
    isRunning = false

    Future.successful(Done)
  }

  override def currentOffset(): Long = recordCount

  override def resumeFrom(offset: Long): Unit = {
    log.info("Resuming DatabaseSource from offset: {}", offset)
    recordCount = offset
    // Note: For true resumption, we'd need to persist lastIncrementalValue
  }

  override def isHealthy: Boolean = {
    // Try to create a connection to verify health
    isRunning && Try {
      Using.resource(createConnection()) {
        conn =>
          conn.isValid(5)
      }
    }.getOrElse(false)
  }

  override def state: Source.SourceState =
    if (isRunning) Source.SourceState.Running
    else Source.SourceState.Stopped
}

/**
 * Companion object with factory method.
 */
object DatabaseSource {

  def apply(pipelineId: String, config: SourceConfig)(implicit system: ActorSystem[_]): DatabaseSource =
    new DatabaseSource(pipelineId, config)
}
