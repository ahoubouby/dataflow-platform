package com.dataflow.sources

import com.dataflow.domain.commands.{Command, IngestBatch}
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.sql.{Connection, DriverManager, Timestamp}
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Integration tests for DatabaseSource connector.
 *
 * Uses TestContainers to spin up a real PostgreSQL database for testing.
 *
 * Tests:
 * - Basic SQL query execution and result parsing
 * - Incremental sync (timestamp-based)
 * - Incremental sync (ID-based)
 * - Periodic polling behavior
 * - Batch fetching
 * - Connection management and health checks
 * - Error handling
 * - Lifecycle management
 * - Offset tracking and resume
 */
class DatabaseSourceSpec
  extends AnyWordSpec
  with Matchers
  with SourceTestFixtures
  with TestContainerForAll
  with BeforeAndAfterAll {

  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def(
    dockerImageName = DockerImageName.parse("postgres:15-alpine"),
    databaseName = "testdb",
    username = "testuser",
    password = "testpass",
  )

  private var jdbcUrl: String = _
  private var connection: Connection = _

  override def afterContainersStart(container: Containers): Unit = {
    super.afterContainersStart(container)
    val postgres = container.asInstanceOf[PostgreSQLContainer]
    jdbcUrl = postgres.jdbcUrl

    // Create connection
    connection = DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password)

    // Initialize test schema and data
    initializeDatabase()
  }

  override def afterAll(): Unit = {
    if (connection != null) {
      connection.close()
    }
    super.afterAll()
  }

  /**
   * Initialize test database schema and data.
   */
  private def initializeDatabase(): Unit = {
    val stmt = connection.createStatement()

    // Create users table
    stmt.execute("""
      CREATE TABLE users (
        id SERIAL PRIMARY KEY,
        name VARCHAR(100) NOT NULL,
        email VARCHAR(100) NOT NULL,
        age INTEGER,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      )
    """)

    // Insert test data
    stmt.execute("""
      INSERT INTO users (name, email, age, created_at) VALUES
        ('Alice', 'alice@example.com', 30, '2024-01-01 10:00:00'),
        ('Bob', 'bob@example.com', 25, '2024-01-01 11:00:00'),
        ('Charlie', 'charlie@example.com', 35, '2024-01-01 12:00:00'),
        ('Diana', 'diana@example.com', 28, '2024-01-01 13:00:00'),
        ('Eve', 'eve@example.com', 32, '2024-01-01 14:00:00')
    """)

    // Create events table for incremental testing
    stmt.execute("""
      CREATE TABLE events (
        id SERIAL PRIMARY KEY,
        event_type VARCHAR(50) NOT NULL,
        user_id INTEGER,
        data TEXT,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      )
    """)

    // Insert test events
    stmt.execute("""
      INSERT INTO events (event_type, user_id, data, created_at) VALUES
        ('login', 1, '{"ip": "192.168.1.1"}', '2024-01-01 10:00:00'),
        ('page_view', 1, '{"page": "/home"}', '2024-01-01 10:05:00'),
        ('click', 2, '{"button": "submit"}', '2024-01-01 10:10:00'),
        ('logout', 1, '{"duration": 300}', '2024-01-01 10:15:00')
    """)

    // Create orders table for ID-based incremental sync
    stmt.execute("""
      CREATE TABLE orders (
        id SERIAL PRIMARY KEY,
        user_id INTEGER,
        product VARCHAR(100),
        amount DECIMAL(10, 2),
        status VARCHAR(20),
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      )
    """)

    stmt.execute("""
      INSERT INTO orders (user_id, product, amount, status, created_at) VALUES
        (1, 'Product A', 50.00, 'completed', '2024-01-01 10:00:00'),
        (2, 'Product B', 75.50, 'pending', '2024-01-01 11:00:00'),
        (1, 'Product C', 25.99, 'completed', '2024-01-01 12:00:00')
    """)

    stmt.close()
  }

  /**
   * Helper to insert more rows into a table.
   */
  private def insertUsers(count: Int, startId: Int = 100): Unit = {
    val stmt = connection.createStatement()
    val values = (startId until startId + count).map {
      i =>
        s"('User$i', 'user$i@example.com', ${20 + (i % 50)}, CURRENT_TIMESTAMP)"
    }.mkString(",")

    stmt.execute(s"INSERT INTO users (name, email, age, created_at) VALUES $values")
    stmt.close()
  }

  private def insertEvents(startTimestamp: String): Unit = {
    val stmt = connection.createStatement()
    stmt.execute(s"""
      INSERT INTO events (event_type, user_id, data, created_at) VALUES
        ('new_event', 3, '{"test": "data"}', '$startTimestamp')
    """)
    stmt.close()
  }

  "DatabaseSource" when {

    "executing basic SQL queries" should {

      "fetch all rows from a table" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM users",
          batchSize = 10,
        )

        val source = DatabaseSource("test-pipeline-db-1", config)
        val records = Await.result(
          source.stream().take(5).runWith(Sink.seq),
          10.seconds,
        )

        records should have size 5
        records.head.data.contains("name") shouldBe true
        records.head.data.contains("email") shouldBe true
        records.head.data("name") shouldBe "Alice"
        records.head.data("email") shouldBe "alice@example.com"
        records.head.metadata("source") shouldBe "database"
        records.head.metadata("jdbc_url") shouldBe jdbcUrl
      }

      "extract ID from database row" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM users ORDER BY id",
        )

        val source = DatabaseSource("test-pipeline-db-2", config)
        val records = Await.result(
          source.stream().take(3).runWith(Sink.seq),
          10.seconds,
        )

        records.head.id shouldBe "1"
        records(1).id shouldBe "2"
        records(2).id shouldBe "3"
      }

      "handle WHERE clauses correctly" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM users WHERE age > 30",
        )

        val source = DatabaseSource("test-pipeline-db-3", config)
        val records = Await.result(
          source.stream().take(10).runWith(Sink.seq),
          10.seconds,
        )

        records.forall(r => r.data("age").toInt > 30) shouldBe true
      }

      "handle JOIN queries" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = """
            SELECT u.name, u.email, o.product, o.amount
            FROM users u
            JOIN orders o ON u.id = o.user_id
            WHERE o.status = 'completed'
          """,
        )

        val source = DatabaseSource("test-pipeline-db-4", config)
        val records = Await.result(
          source.stream().take(10).runWith(Sink.seq),
          10.seconds,
        )

        records should not be empty
        records.head.data.contains("name") shouldBe true
        records.head.data.contains("product") shouldBe true
        records.head.data.contains("amount") shouldBe true
      }
    }

    "handling incremental sync" should {

      "support timestamp-based incremental queries" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM events WHERE created_at > ?",
          incrementalColumn = "created_at",
          incrementalType = "timestamp",
        )

        val source = DatabaseSource("test-pipeline-db-5", config)

        // First poll - should get all 4 events
        val records1 = Await.result(
          source.stream().take(4).runWith(Sink.seq),
          10.seconds,
        )

        records1 should have size 4

        // Insert new event with future timestamp
        insertEvents("2024-01-01 15:00:00")
        Thread.sleep(1000)

        // Second poll - should only get the new event
        // Note: In real scenario this would use the source's internal state tracking
        val records2 = Await.result(
          source.stream().take(1).runWith(Sink.seq),
          10.seconds,
        )

        records2 should have size 1
        records2.head.data("event_type") shouldBe "new_event"
      }

      "support ID-based incremental queries" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM orders WHERE id > ?",
          incrementalColumn = "id",
          incrementalType = "id",
        )

        val source = DatabaseSource("test-pipeline-db-6", config)

        // Poll should get existing orders
        val records = Await.result(
          source.stream().take(3).runWith(Sink.seq),
          10.seconds,
        )

        records should have size 3
        records.head.data.contains("product") shouldBe true
        records.head.data.contains("amount") shouldBe true
      }
    }

    "handling batching and performance" should {

      "respect batch size configuration" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM users",
          batchSize = 2,
        )

        val source = DatabaseSource("test-pipeline-db-7", config)
        val records = Await.result(
          source.stream().take(5).runWith(Sink.seq),
          10.seconds,
        )

        records should have size 5
      }

      "handle large result sets efficiently" in {
        // Insert 100 more users
        insertUsers(100)

        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM users",
          fetchSize = 50,
          batchSize = 20,
        )

        val source = DatabaseSource("test-pipeline-db-8", config)

        val startTime = System.currentTimeMillis()
        val records = Await.result(
          source.stream().take(100).runWith(Sink.seq),
          20.seconds,
        )
        val duration = System.currentTimeMillis() - startTime

        records should have size 100
        // Should complete reasonably fast (under 10 seconds)
        duration should be < 10000L
      }
    }

    "managing lifecycle" should {

      "start and send batches to pipeline" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM users LIMIT 5",
          batchSize = 3,
        )

        val source = DatabaseSource("test-pipeline-db-9", config)
        val probe = TestProbe[ShardingEnvelope[Command]]()

        source.start(probe.ref)

        // Should receive batch within poll interval
        eventually(timeout = 15.seconds, interval = 500.milliseconds) {
          val envelope = probe.expectMessageType[ShardingEnvelope[Command]](5.seconds)
          envelope match {
            case ShardingEnvelope(entityId, cmd: IngestBatch) =>
              entityId shouldBe "test-pipeline-db-9"
              cmd.records.nonEmpty shouldBe true
            case _ => fail("Expected IngestBatch command")
          }
        }

        Await.result(source.stop(), 5.seconds)
      }

      "stop gracefully" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM users",
        )

        val source = DatabaseSource("test-pipeline-db-10", config)
        val probe = TestProbe[ShardingEnvelope[Command]]()

        source.start(probe.ref)
        Thread.sleep(1000)

        val stopFuture = source.stop()
        Await.result(stopFuture, 5.seconds) shouldBe Done

        source.isHealthy shouldBe false
      }

      "report health status correctly" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM users",
        )

        val source = DatabaseSource("test-pipeline-db-11", config)

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
        eventually(timeout = 5.seconds) {
          source.isHealthy shouldBe false
        }
      }
    }

    "handling offsets and resume" should {

      "track current offset (record count)" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM users",
        )

        val source = DatabaseSource("test-pipeline-db-12", config)

        // Initial offset should be 0
        source.currentOffset() shouldBe 0

        // Consume some records
        Await.result(
          source.stream().take(5).runWith(Sink.ignore),
          10.seconds,
        )

        // Offset should have advanced
        source.currentOffset() should be >= 5L
      }

      "resume from specified offset" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM users",
        )

        val source = DatabaseSource("test-pipeline-db-13", config)

        // Resume from offset 10
        source.resumeFrom(10)

        // Should start from offset 10
        source.currentOffset() shouldBe 10
      }
    }

    "handling errors" should {

      "handle connection failures gracefully" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = "jdbc:postgresql://invalid-host:5432/testdb",
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM users",
        )

        val source = DatabaseSource("test-pipeline-db-14", config)

        // Should return empty list on connection error, not crash
        val records = Await.result(
          source.stream().take(1).runWith(Sink.seq),
          10.seconds,
        )

        // Should be empty due to connection error
        records shouldBe empty
      }

      "handle SQL errors gracefully" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM nonexistent_table",
        )

        val source = DatabaseSource("test-pipeline-db-15", config)

        val records = Await.result(
          source.stream().take(1).runWith(Sink.seq),
          10.seconds,
        )

        // Should be empty when table doesn't exist
        records shouldBe empty
      }

      "handle authentication failures gracefully" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "invalid_user",
          password = "wrong_password",
          query = "SELECT * FROM users",
        )

        val source = DatabaseSource("test-pipeline-db-16", config)

        val records = Await.result(
          source.stream().take(1).runWith(Sink.seq),
          10.seconds,
        )

        records shouldBe empty
      }
    }

    "handling data types" should {

      "correctly parse various SQL data types" in {
        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT id, name, age, created_at FROM users LIMIT 1",
        )

        val source = DatabaseSource("test-pipeline-db-17", config)
        val records = Await.result(
          source.stream().take(1).runWith(Sink.seq),
          10.seconds,
        )

        records should have size 1
        val record = records.head

        // Check that all columns are present and converted to strings
        record.data.contains("id") shouldBe true
        record.data.contains("name") shouldBe true
        record.data.contains("age") shouldBe true
        record.data.contains("created_at") shouldBe true

        // Values should be non-empty strings
        record.data("id").nonEmpty shouldBe true
        record.data("name").nonEmpty shouldBe true
        record.data("age").nonEmpty shouldBe true
        record.data("created_at").nonEmpty shouldBe true
      }

      "handle NULL values correctly" in {
        // Insert user with NULL age
        val stmt = connection.createStatement()
        stmt.execute("INSERT INTO users (name, email, age) VALUES ('NullAge', 'null@example.com', NULL)")
        stmt.close()

        val config = createDatabaseSourceConfig(
          jdbcUrl = jdbcUrl,
          username = "testuser",
          password = "testpass",
          query = "SELECT * FROM users WHERE name = 'NullAge'",
        )

        val source = DatabaseSource("test-pipeline-db-18", config)
        val records = Await.result(
          source.stream().take(1).runWith(Sink.seq),
          10.seconds,
        )

        records should have size 1
        // NULL should be converted to empty string
        records.head.data("age") shouldBe ""
      }
    }
  }
}
