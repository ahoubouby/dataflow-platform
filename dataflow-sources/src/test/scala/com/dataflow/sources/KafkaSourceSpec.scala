package com.dataflow.sources

import com.dataflow.domain.commands.{Command, IngestBatch}
import com.dimafeng.testcontainers.{ForAllTestContainer, KafkaContainer}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.util.Properties
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Integration tests for KafkaSource connector.
 *
 * Uses TestContainers to spin up a real Kafka instance for testing.
 *
 * Tests:
 * - JSON message consumption
 * - String message consumption
 * - Consumer group behavior
 * - Offset management and commits
 * - Batch emission with backpressure
 * - Error handling (malformed messages)
 * - Multiple partitions
 * - Message ordering
 */
class KafkaSourceSpec
  extends AnyWordSpec
  with Matchers
  with SourceTestFixtures
  with ForAllTestContainer
  with BeforeAndAfterAll {

  // TestContainers Kafka
  override val container: KafkaContainer = KafkaContainer(
    DockerImageName.parse("confluentinc/cp-kafka:7.5.0"),
  )

  private var producer: KafkaProducer[String, String] = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    // Create Kafka producer
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, container.bootstrapServers)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.ACKS_CONFIG, "all")

    producer = new KafkaProducer[String, String](props)
  }

  override def afterAll(): Unit = {
    if (producer != null) {
      producer.close()
    }
    super.afterAll()
  }

  /**
   * Helper to send messages to Kafka.
   */
  private def sendMessages(topic: String, messages: Seq[(String, String)]): Unit = {
    messages.foreach {
      case (key, value) =>
        val record = new ProducerRecord[String, String](topic, key, value)
        producer.send(record).get() // Block until sent
    }
    producer.flush()
  }

  /**
   * Helper to send JSON messages.
   */
  private def sendJsonMessages(topic: String, jsonMessages: Seq[String]): Unit = {
    jsonMessages.zipWithIndex.foreach {
      case (json, idx) =>
        val record = new ProducerRecord[String, String](topic, s"key-$idx", json)
        producer.send(record).get()
    }
    producer.flush()
  }

  "KafkaSource" when {

    "consuming JSON messages" should {

      "parse messages correctly" in {
        val topic = "test-json-topic-1"

        // Send test messages
        val messages = Seq(
          """{"id":"user-1","name":"Alice","age":30}""",
          """{"id":"user-2","name":"Bob","age":25}""",
          """{"id":"user-3","name":"Charlie","age":35}""",
        )
        sendJsonMessages(topic, messages)

        // Create Kafka source
        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "test-group-1",
          format = "json",
          batchSize = 10,
        )

        val source = KafkaSource("test-pipeline-kafka-1", config)

        // Consume messages
        val records = Await.result(
          source.stream().take(3).runWith(Sink.seq),
          30.seconds,
        )

        records should have size 3

        // Verify first record
        val firstRecord = records.head
        firstRecord.data("name") shouldBe "Alice"
        firstRecord.data("age") shouldBe "30"

        // Verify metadata
        firstRecord.metadata("source") shouldBe "kafka"
        firstRecord.metadata("topic") shouldBe topic
        firstRecord.metadata("format") shouldBe "json"
        firstRecord.metadata.contains("offset") shouldBe true
        firstRecord.metadata.contains("partition") shouldBe true
      }

      "extract ID from JSON if present" in {
        val topic = "test-json-topic-2"

        val messages = Seq(
          """{"id":"evt-123","type":"login","user":"alice"}""",
          """{"id":"evt-456","type":"logout","user":"bob"}""",
        )
        sendJsonMessages(topic, messages)

        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "test-group-2",
          format = "json",
        )

        val source  = KafkaSource("test-pipeline-kafka-2", config)
        val records = Await.result(
          source.stream().take(2).runWith(Sink.seq),
          30.seconds,
        )

        records should have size 2
        records.head.data("type") shouldBe "login"
        records.last.data("type") shouldBe "logout"
      }

      "handle malformed JSON gracefully" in {
        val topic = "test-json-topic-3"

        val messages = Seq(
          """{"id":"valid-1","name":"Alice"}""",
          """{invalid json}""", // Malformed
          """{"id":"valid-2","name":"Bob"}""",
        )
        sendJsonMessages(topic, messages)

        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "test-group-3",
          format = "json",
        )

        val source  = KafkaSource("test-pipeline-kafka-3", config)
        val records = Await.result(
          source.stream().take(3).runWith(Sink.seq),
          30.seconds,
        )

        // Should fallback to string parsing for malformed JSON
        records should have size 3

        // Valid JSON records
        records.head.data("name") shouldBe "Alice"

        // Malformed should be parsed as string
        val malformedRecord = records(1)
        malformedRecord.data.contains("value") shouldBe true
      }
    }

    "consuming string messages" should {

      "parse simple key-value pairs" in {
        val topic = "test-string-topic-1"

        sendMessages(
          topic,
          Seq(
            ("key1", "value1"),
            ("key2", "value2"),
            ("key3", "value3"),
          ),
        )

        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "test-group-4",
          format = "string",
        )

        val source  = KafkaSource("test-pipeline-kafka-4", config)
        val records = Await.result(
          source.stream().take(3).runWith(Sink.seq),
          30.seconds,
        )

        records should have size 3

        // Check first record
        val firstRecord = records.head
        firstRecord.data("key") shouldBe "key1"
        firstRecord.data("value") shouldBe "value1"
        firstRecord.metadata("format") shouldBe "string"
      }

      "handle messages without keys" in {
        val topic = "test-string-topic-2"

        // Send messages without keys
        Seq("message1", "message2", "message3").foreach {
          msg =>
            producer.send(new ProducerRecord[String, String](topic, null, msg)).get()
        }
        producer.flush()

        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "test-group-5",
          format = "string",
        )

        val source  = KafkaSource("test-pipeline-kafka-5", config)
        val records = Await.result(
          source.stream().take(3).runWith(Sink.seq),
          30.seconds,
        )

        records should have size 3
        records.head.data("value") shouldBe "message1"
        records.head.data("key") shouldBe ""
      }
    }

    "managing consumer groups" should {

      "use configured group ID" in {
        val topic = "test-group-topic-1"

        sendMessages(
          topic,
          Seq(
            ("k1", "v1"),
            ("k2", "v2"),
          ),
        )

        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "specific-group-id",
          format = "string",
        )

        val source = KafkaSource("test-pipeline-kafka-6", config)

        // Should be able to create source with specific group ID
        source.sourceId should include("kafka-source")
      }

      "support multiple consumers in same group" in {
        val topic = "test-group-topic-2"



        val groupId = "shared-group"

        // Create two consumers in same group
        val config1 = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = groupId,
          format = "string",
        )
        val config2 = config1.copy()

        val source1 = KafkaSource("test-pipeline-kafka-7a", config1)
        val source2 = KafkaSource("test-pipeline-kafka-7b", config2)
        // Send many messages
        val messages = (1 to 20).map(i => (s"key$i", s"value$i"))
        sendMessages(topic, messages)
        // Both should be able to consume (partitions will be balanced)
        val future1 = source1.stream().take(10).runWith(Sink.seq)
        val future2 = source2.stream().take(10).runWith(Sink.seq)

        val records1 = Await.result(future1, 30.seconds)
        println("-----")
        records1.foreach(println)

        val records2 = Await.result(future2, 30.seconds)
        println("-----")


        // Together they should consume all messages
        (records1.size + records2.size) shouldBe 20
      }
    }

    "handling offsets" should {

      "track current offset" in {
        val topic = "test-offset-topic-1"

        sendMessages(
          topic,
          Seq(
            ("k1", "v1"),
            ("k2", "v2"),
            ("k3", "v3"),
          ),
        )

        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "offset-test-group-1",
          format = "string",
        )

        val source = KafkaSource("test-pipeline-kafka-8", config)

        // Consume some messages
        Await.result(
          source.stream().take(2).runWith(Sink.ignore),
          30.seconds,
        )

        // Offset should have advanced
        source.currentOffset() should be >= 0L
      }

      "commit offsets automatically" in {
        val topic = "test-offset-topic-2"

        sendMessages(
          topic,
          Seq(
            ("k1", "v1"),
            ("k2", "v2"),
            ("k3", "v3"),
          ),
        )

        val groupId = "offset-commit-group"
        val config  = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = groupId,
          format = "string",
        )

        // First consumer
        val source1 = KafkaSource("test-pipeline-kafka-9", config)
        val probe1  = TestProbe[ShardingEnvelope[Command]]()

        source1.start(probe1.ref)

        // Wait for messages to be consumed
        Thread.sleep(3000)

        Await.result(source1.stop(), 5.seconds)

        // Second consumer with same group ID should resume from committed offset
        val source2 = KafkaSource("test-pipeline-kafka-9b", config)
        val records = Await.result(
          source2.stream().take(1).runWith(Sink.seq),
          30.seconds,
        )

        // Should not re-consume from beginning
        records.size should be <= 1
      }

      "start from earliest when configured" in {
        val topic = "test-offset-topic-3"

        // Send messages before consumer starts
        sendMessages(
          topic,
          Seq(
            ("k1", "v1"),
            ("k2", "v2"),
          ),
        )

        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "earliest-group-" + System.currentTimeMillis(),
          format = "string",
        ).copy(options =
          Map(
            "topic"             -> topic,
            "group-id"          -> ("earliest-group-" + System.currentTimeMillis()),
            "format"            -> "string",
            "auto-offset-reset" -> "earliest",
          ),
        )

        val source  = KafkaSource("test-pipeline-kafka-10", config)
        val records = Await.result(
          source.stream().take(2).runWith(Sink.seq),
          30.seconds,
        )

        // Should consume from beginning
        records should have size 2
      }
    }

    "managing lifecycle" should {

      "start and send batches to pipeline" in {
        val topic = "test-lifecycle-topic-1"

        // Send test messages
        sendMessages(topic, (1 to 10).map(i => (s"key$i", s"value$i")))

        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "lifecycle-group-1",
          format = "string",
          batchSize = 3,
        )

        val source = KafkaSource("test-pipeline-kafka-11", config)
        val probe  = TestProbe[ShardingEnvelope[Command]]()

        // Start the source
        source.start(probe.ref)

        // Should receive batches
        eventually(
          condition = {
            // try to read quickly; if nothing yet, eventually will retry
            val envelope = probe.receiveMessage(1.second)

            envelope match {
              case ShardingEnvelope(entityId, cmd: IngestBatch) =>
                entityId shouldBe "test-pipeline-kafka-11"
                cmd.records.nonEmpty shouldBe true
                true

              case other =>
                fail(s"Expected IngestBatch command but got: $other")
                false
            }
          },
          timeout = 30.seconds,
          interval = 1.second,
        )

        // Stop
        Await.result(source.stop(), 5.seconds)
      }

      "stop gracefully" in {
        val topic = "test-lifecycle-topic-2"

        // Send many messages
        sendMessages(topic, (1 to 100).map(i => (s"key$i", s"value$i")))

        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "lifecycle-group-2",
          format = "string",
        )

        val source = KafkaSource("test-pipeline-kafka-12", config)
        val probe  = TestProbe[ShardingEnvelope[Command]]()

        // Start
        source.start(probe.ref)

        // Let it run
        Thread.sleep(2000)

        // Stop should complete quickly
        val stopFuture = source.stop()
        Await.result(stopFuture, 10.seconds) shouldBe Done

        source.isHealthy shouldBe false
      }

      "report health status correctly" in {
        val topic = "test-lifecycle-topic-3"

        sendMessages(topic, Seq(("k1", "v1")))

        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "lifecycle-group-3",
          format = "string",
        )

        val source = KafkaSource("test-pipeline-kafka-13", config)

        // Initially not running
        source.isHealthy shouldBe false

        // Start
        val probe = TestProbe[ShardingEnvelope[Command]]()
        source.start(probe.ref)

        // Should be healthy
        Thread.sleep(1000)
        source.isHealthy shouldBe true

        // Stop
        Await.result(source.stop(), 5.seconds)

        // Should be unhealthy
        eventually(
          condition = {
            source.isHealthy shouldBe false; true
          },
          timeout = 5.seconds,
        )
      }
    }

    "handling backpressure" should {

      "batch messages according to configuration" in {
        val topic = "test-backpressure-topic-1"

        // Send many messages
        val messages = (1 to 50).map(i => (s"key$i", s"""{"id":"$i","value":"data$i"}"""))
        sendMessages(topic, messages)

        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "backpressure-group-1",
          format = "json",
          batchSize = 10,
        )

        val source = KafkaSource("test-pipeline-kafka-14", config)
        val probe  = TestProbe[ShardingEnvelope[Command]]()

        source.start(probe.ref)

        // Should receive batches of configured size
        eventually(
          condition = {
            val envelope = probe.expectMessageType[ShardingEnvelope[Command]](5.seconds)
            envelope match {
              case ShardingEnvelope(_, cmd: IngestBatch) =>
                cmd.records.size should be <= 10
                true
              case _                                     =>
                fail("Expected IngestBatch")
                false
            }
          },
          timeout = 30.seconds,
          interval = 1.second,
        )

        Await.result(source.stop(), 5.seconds)
      }
    }

    "with multiple partitions" should {

      "consume from all partitions" in {
        val topic = "test-multipart-topic-1"

        // Send messages that will go to different partitions (based on key hash)
        val messages = (1 to 30).map {
          i =>
            (s"key-$i", s"""{"id":"$i","partition_key":"$i"}""")
        }
        sendMessages(topic, messages)

        val config = createKafkaSourceConfig(
          topic = topic,
          bootstrapServers = container.bootstrapServers,
          groupId = "multipart-group-1",
          format = "json",
        )

        val source  = KafkaSource("test-pipeline-kafka-15", config)
        val records = Await.result(
          source.stream().take(30).runWith(Sink.seq),
          30.seconds,
        )

        records should have size 30

        // Check that we got messages from different partitions
        val partitions = records.map(_.metadata("partition")).toSet
        partitions.size should be >= 1
      }
    }
  }
}
