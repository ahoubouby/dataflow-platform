package com.dataflow.sources

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters._

import com.dataflow.domain.models.{DataRecord, SourceConfig, SourceType}
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.{BeforeAndAfterAll, Suite}

/**
 * Test fixtures for source connector tests.
 *
 * Provides:
 * - Sample data files (CSV, JSON, text)
 * - Mock configurations
 * - Test data generators
 * - Cleanup utilities
 */
trait SourceTestFixtures extends BeforeAndAfterAll { this: Suite =>

  // Test data directory
  val testDataDir: Path = Paths.get(System.getProperty("java.io.tmpdir"), "dataflow-test-" + UUID.randomUUID())

  // Actor system for tests
  val testKit:         ActorTestKit         = ActorTestKit()
  implicit val system: ActorSystem[Nothing] = testKit.system

  override def beforeAll(): Unit = {
    super.beforeAll()
    Files.createDirectories(testDataDir)
    createTestDataFiles()
  }

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
    deleteDirectory(testDataDir)
    super.afterAll()
  }

  /**
   * Create test data files for various formats.
   */
  def createTestDataFiles(): Unit = {
    // CSV with header
    val csvWithHeader = testDataDir.resolve("users.csv")
    Files.write(
      csvWithHeader,
      List(
        "id,name,age,email",
        "1,Alice,30,alice@example.com",
        "2,Bob,25,bob@example.com",
        "3,Charlie,35,charlie@example.com",
        "4,Diana,28,diana@example.com",
        "5,Eve,32,eve@example.com",
      ).asJava,
    )

    // CSV without header
    val csvWithoutHeader = testDataDir.resolve("data.csv")
    Files.write(
      csvWithoutHeader,
      List(
        "100,Product A,50.00",
        "101,Product B,75.50",
        "102,Product C,25.99",
      ).asJava,
    )

    // JSON (newline-delimited)
    val jsonFile = testDataDir.resolve("events.json")
    Files.write(
      jsonFile,
      List(
        """{"id":"evt-1","type":"login","user":"alice","timestamp":"2024-01-01T10:00:00Z"}""",
        """{"id":"evt-2","type":"page_view","user":"bob","page":"/home","timestamp":"2024-01-01T10:01:00Z"}""",
        """{"id":"evt-3","type":"click","user":"alice","element":"button1","timestamp":"2024-01-01T10:02:00Z"}""",
        """{"id":"evt-4","type":"purchase","user":"charlie","amount":99.99,"timestamp":"2024-01-01T10:03:00Z"}""",
        """{"id":"evt-5","type":"logout","user":"alice","timestamp":"2024-01-01T10:04:00Z"}""",
      ).asJava,
    )

    // Plain text file
    val textFile = testDataDir.resolve("logs.txt")
    Files.write(
      textFile,
      List(
        "2024-01-01 10:00:00 INFO Application started",
        "2024-01-01 10:00:01 INFO Connected to database",
        "2024-01-01 10:00:02 WARN Slow query detected",
        "2024-01-01 10:00:03 ERROR Connection timeout",
        "2024-01-01 10:00:04 INFO Recovery successful",
      ).asJava,
    )

    // Empty file (edge case)
    val emptyFile = testDataDir.resolve("empty.csv")
    Files.write(emptyFile, List.empty[String].asJava)

    // Large file (performance testing)
    val largeFile = testDataDir.resolve("large.csv")
    val largeData = (1 to 10000).map {
      i =>
        s"$i,User$i,${20 + (i % 50)},user$i@example.com"
    }
    Files.write(largeFile, ("id,name,age,email" :: largeData.toList).asJava)
  }

  /**
   * Delete directory recursively.
   */
  def deleteDirectory(dir: Path): Unit = {
    if (Files.exists(dir)) {
      Files.walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete)
    }
  }

  /**
   * Create sample SourceConfig for File source.
   */
  def createFileSourceConfig(
    filePath: String,
    format: String = "csv",
    hasHeader: Boolean = true,
    batchSize: Int = 100,
  ): SourceConfig = {
    SourceConfig(
      sourceType = SourceType.File,
      connectionString = filePath,
      options = Map(
        "format"     -> format,
        "has-header" -> hasHeader.toString,
        "delimiter"  -> ",",
        "encoding"   -> "UTF-8",
      ),
      batchSize = batchSize,
      pollIntervalMs = 1000,
    )
  }

  /**
   * Create sample SourceConfig for Kafka source.
   */
  def createKafkaSourceConfig(
    topic: String,
    bootstrapServers: String = "localhost:9092",
    groupId: String = "test-group",
    format: String = "json",
    batchSize: Int = 100,
  ): SourceConfig = {
    SourceConfig(
      sourceType = SourceType.Kafka,
      connectionString = bootstrapServers,
      options = Map(
        "topic"              -> topic,
        "group-id"           -> groupId,
        "format"             -> format,
        "auto-offset-reset"  -> "earliest",
        "enable-auto-commit" -> "false",
      ),
      batchSize = batchSize,
      pollIntervalMs = 1000,
    )
  }

  /**
   * Create sample SourceConfig for API source.
   */
  def createApiSourceConfig(
    apiUrl: String,
    method: String = "GET",
    authType: String = "none",
    authToken: String = "",
    authUsername: String = "",
    authPassword: String = "",
    paginationType: String = "none",
    paginationParam: String = "offset",
    paginationSizeParam: String = "limit",
    paginationSize: Int = 100,
    responsePath: String = "data",
    batchSize: Int = 100,
  ): SourceConfig = {
    SourceConfig(
      sourceType = SourceType.Api,
      connectionString = apiUrl,
      options = Map(
        "method"                 -> method,
        "auth-type"              -> authType,
        "auth-token"             -> authToken,
        "auth-username"          -> authUsername,
        "auth-password"          -> authPassword,
        "pagination-type"        -> paginationType,
        "pagination-param"       -> paginationParam,
        "pagination-size-param"  -> paginationSizeParam,
        "pagination-size"        -> paginationSize.toString,
        "response-path"          -> responsePath,
      ).filter(_._2.nonEmpty),
      batchSize = batchSize,
      pollIntervalMs = 5000,
    )
  }

  /**
   * Create sample SourceConfig for Database source.
   */
  def createDatabaseSourceConfig(
    jdbcUrl: String,
    username: String = "test",
    password: String = "test",
    driver: String = "org.postgresql.Driver",
    query: String = "SELECT * FROM users",
    incrementalColumn: String = "",
    incrementalType: String = "timestamp",
    fetchSize: Int = 1000,
    batchSize: Int = 100,
  ): SourceConfig = {
    SourceConfig(
      sourceType = SourceType.Database,
      connectionString = jdbcUrl,
      options = Map(
        "username"           -> username,
        "password"           -> password,
        "driver"             -> driver,
        "query"              -> query,
        "incremental-column" -> incrementalColumn,
        "incremental-type"   -> incrementalType,
        "fetch-size"         -> fetchSize.toString,
      ).filter(_._2.nonEmpty),
      batchSize = batchSize,
      pollIntervalMs = 10000,
    )
  }

  /**
   * Generate sample DataRecords for testing.
   */
  def generateSampleRecords(count: Int, recordType: String = "user"): List[DataRecord] = {
    (1 to count).map {
      i =>
        val (data, metadata) = recordType match {
          case "user" =>
            (
              Map(
                "id"     -> i.toString,
                "name"   -> s"User$i",
                "age"    -> (20 + (i % 50)).toString,
                "email"  -> s"user$i@example.com",
              ),
              Map("type" -> "user", "index" -> i.toString),
            )

          case "event" =>
            (
              Map(
                "event_type" -> (if (i % 2 == 0) "login" else "logout"),
                "user_id"    -> s"user-${i % 10}",
                "timestamp"  -> Instant.now().toString,
              ),
              Map("type"     -> "event", "index" -> i.toString),
            )

          case "order" =>
            (
              Map(
                "order_id" -> UUID.randomUUID().toString,
                "product"  -> s"Product${i % 5}",
                "quantity" -> (i % 10 + 1).toString,
                "amount"   -> (i * 10.5).toString,
              ),
              Map("type"   -> "order", "index" -> i.toString),
            )

          case _ =>
            (
              Map("field1" -> s"value$i", "field2" -> i.toString),
              Map("type"   -> "generic", "index"   -> i.toString),
            )
        }

        DataRecord(
          id = UUID.randomUUID().toString,
          data = data,
          metadata = metadata,
        )
    }.toList
  }

  /**
   * Assert that DataRecord has expected structure.
   */
  def assertDataRecordValid(record: DataRecord): Unit = {
    assert(record.id.nonEmpty, "Record ID should not be empty")
    assert(record.data.nonEmpty, "Record data should not be empty")
    assert(record.metadata.contains("source"), "Record should have 'source' metadata")
    assert(record.metadata.contains("timestamp"), "Record should have 'timestamp' metadata")
  }

  /**
   * Wait for condition with timeout.
   */
  def eventually[T](
    condition: => Boolean,
    timeout: scala.concurrent.duration.FiniteDuration = 5.seconds,
    interval: scala.concurrent.duration.FiniteDuration = 5.seconds,
  ): Unit = {
    val deadline = timeout.fromNow
    while (!condition && deadline.hasTimeLeft()) {
      Thread.sleep(interval.toMillis)
    }
  }
}

/**
 * Companion object with utility methods.
 */
object SourceTestFixtures {

  /**
   * Create temporary file with content.
   */
  def createTempFile(name: String, content: String): Path = {
    val tempDir = Files.createTempDirectory("dataflow-test")
    val file    = tempDir.resolve(name)
    Files.write(file, content.getBytes("UTF-8"))
    file
  }

  /**
   * Create temporary CSV file.
   */
  def createTempCsvFile(
    name: String,
    rows: List[List[String]],
    hasHeader: Boolean = false,
  ): Path = {
    val content = rows.map(_.mkString(",")).mkString("\n")
    createTempFile(name, content)
  }

  /**
   * Create temporary JSON file (newline-delimited).
   */
  def createTempJsonFile(name: String, jsonObjects: List[String]): Path = {
    val content = jsonObjects.mkString("\n")
    createTempFile(name, content)
  }
}
