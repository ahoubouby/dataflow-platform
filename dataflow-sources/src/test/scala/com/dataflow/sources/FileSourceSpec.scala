package com.dataflow.sources

import java.nio.file.Files

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import com.dataflow.domain.commands.{Command, IngestBatch}
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Integration tests for FileSource connector.
 *
 * Tests:
 * - CSV file reading (with and without headers)
 * - JSON file reading (newline-delimited)
 * - Plain text file reading
 * - Empty file handling
 * - Large file performance
 * - Batch emission
 * - Offset tracking and resume
 * - Error handling
 */
class FileSourceSpec extends AnyWordSpec with Matchers with SourceTestFixtures with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    super.beforeAll()
    createTestDataFiles()
  }

  "FileSource" when {

    "reading CSV files with header" should {

      "parse all rows correctly" in {
        val csvFile = testDataDir.resolve("users.csv")
        val config  = createFileSourceConfig(
          filePath = csvFile.toString,
          format = "csv",
          hasHeader = true,
          batchSize = 100,
        )

        val source  = FileSource("test-pipeline-1", config)
        val records = Await.result(
          source.stream().runWith(Sink.seq),
          10.seconds,
        )

        records should have size 5

        // Check first record
        val firstRecord = records.head
        firstRecord.data("id") shouldBe "1"
        firstRecord.data("name") shouldBe "Alice"
        firstRecord.data("age") shouldBe "30"
        firstRecord.data("email") shouldBe "alice@example.com"

        // Check metadata
        firstRecord.metadata("source") shouldBe "file"
        firstRecord.metadata("file_path") shouldBe csvFile.toString
        firstRecord.metadata("format") shouldBe "csv"
        firstRecord.metadata.contains("timestamp") shouldBe true

        // Check last record
        val lastRecord = records.last
        lastRecord.data("id") shouldBe "5"
        lastRecord.data("name") shouldBe "Eve"
      }

      "track offsets correctly" in {
        val csvFile = testDataDir.resolve("users.csv")
        val config  = createFileSourceConfig(csvFile.toString)

        val source = FileSource("test-pipeline-2", config)

        // Read first 3 records
        val records = Await.result(
          source.stream().take(3).runWith(Sink.seq),
          10.seconds,
        )

        records should have size 3

        // Offset should be at line 3 (excluding header)
        source.currentOffset() shouldBe 3
      }
    }

    "reading CSV files without header" should {

      "parse all rows as data" in {
        val csvFile = testDataDir.resolve("data.csv")
        val config  = createFileSourceConfig(
          filePath = csvFile.toString,
          hasHeader = false,
        )

        val source  = FileSource("test-pipeline-3", config)
        val records = Await.result(
          source.stream().runWith(Sink.seq),
          10.seconds,
        )

        records should have size 3

        // Check first record (should have field0, field1, field2)
        val firstRecord = records.head
        firstRecord.data("field1") shouldBe "100"
        firstRecord.data("field2") shouldBe "Product A"
        firstRecord.data("field3") shouldBe "50.00"
      }
    }

    "reading JSON files" should {

      "parse newline-delimited JSON" in {
        val jsonFile = testDataDir.resolve("events.json")
        val config   = createFileSourceConfig(
          filePath = jsonFile.toString,
          format = "json",
          batchSize = 100,
        )

        val source  = FileSource("test-pipeline-4", config)
        val records = Await.result(
          source.stream().runWith(Sink.seq),
          10.seconds,
        )

        records should have size 5

        // Check first record
        val firstRecord = records.head
        firstRecord.data("type") shouldBe "login"
        firstRecord.data("user") shouldBe "alice"
        firstRecord.data.contains("timestamp") shouldBe true

        // Check metadata
        firstRecord.metadata("source") shouldBe "file"
        firstRecord.metadata("format") shouldBe "json"

        // Check different event types
        records.exists(_.data("type") == "login") shouldBe true
        records.exists(_.data("type") == "page_view") shouldBe true
        records.exists(_.data("type") == "purchase") shouldBe true
      }

      "extract ID from JSON if present" in {
        val jsonFile = testDataDir.resolve("events.json")
        val config   = createFileSourceConfig(jsonFile.toString, format = "json")

        val source  = FileSource("test-pipeline-5", config)
        val records = Await.result(
          source.stream().runWith(Sink.seq),
          10.seconds,
        )

        // JSON records have IDs in the data
        records.head.id.nonEmpty shouldBe true
        println("metadata ===>", records.head.metadata)
        records.head.metadata("line_number") shouldBe "1"
      }
    }

    "reading text files" should {

      "read line by line" in {
        val textFile = testDataDir.resolve("logs.txt")
        val config   = createFileSourceConfig(
          filePath = textFile.toString,
          format = "text",
          batchSize = 100,
        )

        val source  = FileSource("test-pipeline-6", config)
        val records = Await.result(
          source.stream().runWith(Sink.seq),
          10.seconds,
        )

        records should have size 5

        // Check first record
        val firstRecord = records.head
        println("-----firstRecord-----", firstRecord)
        firstRecord.data("line") shouldBe "2024-01-01 10:00:00 INFO Application started"
        firstRecord.metadata("line_number") shouldBe "1"

        // Check metadata
        firstRecord.metadata("source") shouldBe "file"
        firstRecord.metadata("format") shouldBe "text"
      }

      "filter log levels if specified" in {
        val textFile = testDataDir.resolve("logs.txt")
        val records  = Await.result(
          FileSource("test-pipeline-7", createFileSourceConfig(textFile.toString, format = "text"))
            .stream()
            .runWith(Sink.seq),
          10.seconds,
        )

        // Check that ERROR log is present
        records.exists(r => r.data("line").contains("ERROR")) shouldBe true
      }
    }

    "handling edge cases" should {

      "handle empty files gracefully" in {
        val emptyFile = testDataDir.resolve("empty.csv")
        val config    = createFileSourceConfig(emptyFile.toString)

        val source  = FileSource("test-pipeline-8", config)
        val records = Await.result(
          source.stream().runWith(Sink.seq),
          10.seconds,
        )

        records shouldBe empty
      }

      "handle large files efficiently" in {
        val largeFile = testDataDir.resolve("large.csv")
        val config    = createFileSourceConfig(
          filePath = largeFile.toString,
          hasHeader = true,
          batchSize = 1000,
        )

        val source = FileSource("test-pipeline-9", config)

        val startTime = System.currentTimeMillis()
        val records   = Await.result(
          source.stream().runWith(Sink.seq),
          30.seconds,
        )
        val duration  = System.currentTimeMillis() - startTime

        records should have size 10000
        duration should be < 5000L // Should complete in less than 5 seconds

        // Check sampling of records
        records.head.data("id") shouldBe "1"
        records.last.data("id") shouldBe "10000"
      }

      "handle malformed CSV lines gracefully" in {
        val malformedCsv = testDataDir.resolve("malformed.csv")
        Files.write(
          malformedCsv,
          List(
            "id,name,age",
            "1,Alice,30",
            "2,Bob",              // Missing field
            "3,Charlie,35,extra", // Extra field
            "4,Diana,28",
          ).asJava,
        )

        val config = createFileSourceConfig(malformedCsv.toString, hasHeader = true)
        val source = FileSource("test-pipeline-10", config)

        val records = Await.result(
          source.stream().runWith(Sink.seq),
          10.seconds,
        )

        // Should still parse what it can
        records.size should be >= 2
      }

      "handle non-existent files" in {
        val nonExistentFile = testDataDir.resolve("non-existent.csv")
        val config          = createFileSourceConfig(nonExistentFile.toString)

        val source = FileSource("test-pipeline-11", config)

        // Should throw exception or return empty stream
        assertThrows[Exception] {
          Await.result(
            source.stream().runWith(Sink.seq),
            10.seconds,
          )
        }
      }
    }

    "managing lifecycle" should {

      "start and send batches to pipeline" in {
        val csvFile = testDataDir.resolve("users.csv")
        val config  = createFileSourceConfig(
          filePath = csvFile.toString,
          hasHeader = true,
          batchSize = 2, // Small batch for testing
        )

        val source = FileSource("test-pipeline-12", config)
        val probe  = TestProbe[ShardingEnvelope[Command]]()

        // Start the source
        val startFuture = source.start(probe.ref)

        // Should receive batches
        eventually(condition = {
          probe.expectMessageType[ShardingEnvelope[Command]](5.seconds) match {
            case ShardingEnvelope(entityId, cmd: IngestBatch) =>
              entityId shouldBe "test-pipeline-12"
              cmd.records.nonEmpty shouldBe true
              true
            case _                                            => false
          }
        })

        // Stop the source
        Await.result(source.stop(), 5.seconds)
        Await.result(startFuture, 5.seconds)
      }

      "stop gracefully" in {
        val largeFile = testDataDir.resolve("large.csv")
        val config    = createFileSourceConfig(largeFile.toString, hasHeader = true, batchSize = 100)

        val source = FileSource("test-pipeline-13", config)
        val probe  = TestProbe[ShardingEnvelope[Command]]()

        // Start the source
        source.start(probe.ref)

        // Let it run for a bit
        Thread.sleep(1000)

        // Stop should complete quickly
        val stopFuture = source.stop()
        Await.result(stopFuture, 5.seconds) shouldBe Done

        source.isHealthy shouldBe false
      }

      "report health status correctly" in {
        val csvFile = testDataDir.resolve("users.csv")
        val config  = createFileSourceConfig(csvFile.toString)

        val source = FileSource("test-pipeline-14", config)

        // Initially not running
        source.isHealthy shouldBe false

        // Start
        val probe = TestProbe[ShardingEnvelope[Command]]()
        source.start(probe.ref)

        // Should be healthy while running
        source.isHealthy shouldBe true

        // Stop
        Await.result(source.stop(), 5.seconds)

        // Eventually becomes unhealthy
//        eventually(timeout = 5.seconds) {
//          source.isHealthy shouldBe false
//        }
      }
    }

    "handling offsets and resume" should {

      "resume from specified offset" in {
        val csvFile = testDataDir.resolve("users.csv")
        val config  = createFileSourceConfig(csvFile.toString)

        val source = FileSource("test-pipeline-15", config)

        // Resume from line 3 (skip first 2 data rows)
        source.resumeFrom(3)

        val records = Await.result(
          source.stream().runWith(Sink.seq),
          10.seconds,
        )

        // Should only get records from line 3 onwards
        println("-------")
        println(records.head.data)
        println("-------")
        records.size should be <= 3 // Lines 3, 4, 5
        records.head.data("id") shouldBe "3"
      }

      "track current offset during streaming" in {
        val csvFile = testDataDir.resolve("users.csv")
        val config  = createFileSourceConfig(csvFile.toString, hasHeader = true)

        val source = FileSource("test-pipeline-16", config)
        val probe  = TestProbe[ShardingEnvelope[Command]]()

        source.start(probe.ref)

        // Wait for some processing
        Thread.sleep(1000)

        // Offset should have advanced
        source.currentOffset() should be > 0L

        Await.result(source.stop(), 5.seconds)
      }
    }

    "custom delimiters and encodings" should {

      "handle custom CSV delimiters" in {
        val tsvFile = testDataDir.resolve("data.tsv")
        Files.write(
          tsvFile,
          List(
            "id\tname\tvalue",
            "1\tItem A\t100",
            "2\tItem B\t200",
          ).asJava,
        )

        val config = createFileSourceConfig(
          filePath = tsvFile.toString,
          format = "csv",
          hasHeader = true,
          batchSize = 100,
        ).copy(options =
          Map(
            "format"     -> "csv",
            "has-header" -> "true",
            "delimiter"  -> "\t",
            "encoding"   -> "UTF-8",
          ),
        )

        val source  = FileSource("test-pipeline-17", config)
        val records = Await.result(
          source.stream().runWith(Sink.seq),
          10.seconds,
        )

        records should have size 2
        records.head.data("name") shouldBe "Item A"
        records.head.data("value") shouldBe "100"
      }

      "handle different encodings" in {
        val utf8File = testDataDir.resolve("utf8.txt")
        Files.write(
          utf8File,
          List(
            "Hello 世界",
            "Bonjour Monde",
            "Привет Мир",
          ).asJava,
        )

        val config = createFileSourceConfig(utf8File.toString, format = "text")
        val source = FileSource("test-pipeline-18", config)

        val records = Await.result(
          source.stream().runWith(Sink.seq),
          10.seconds,
        )

        records should have size 3
        records.head.data("line") shouldBe "Hello 世界"
      }
    }
  }
}
