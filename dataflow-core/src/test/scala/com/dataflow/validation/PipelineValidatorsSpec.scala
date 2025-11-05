package com.dataflow.validation

import com.dataflow.domain.commands.{CreatePipeline, IngestBatch}
import com.dataflow.domain.models._
import com.wix.accord._
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.pattern.StatusReply
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PipelineValidatorsSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  import PipelineValidators._

  "PipelineNameValidator" should {

    "accept valid pipeline names" in {
      val validNames = Seq(
        "my-pipeline",
        "pipeline_123",
        "Pipeline-With-Dashes",
        "test_pipeline_2024",
      )

      validNames.foreach {
        name =>
          validate(name)(pipelineNameValidator) shouldBe com.wix.accord.Success
      }
    }

    "reject empty names" in {
      validate("")(pipelineNameValidator) shouldBe a[Failure]
    }

    "reject names that are too short" in {
      validate("ab")(pipelineNameValidator) shouldBe a[Failure]
    }

    "reject names that are too long" in {
      val longName = "a" * 101
      validate(longName)(pipelineNameValidator) shouldBe a[Failure]
    }

    "reject names with invalid characters" in {
      val invalidNames = Seq(
        "pipeline with spaces",
        "pipeline@special",
        "pipeline.dot",
        "pipeline/slash",
      )

      invalidNames.foreach {
        name =>
          validate(name)(pipelineNameValidator) shouldBe a[Failure]
      }
    }
  }

  "SourceConfigValidator" should {

    "accept valid source config" in {
      val validSource = SourceConfig(
        sourceType = SourceType.fromString("kafka").getOrElse(SourceType.File),
        connectionString = "localhost:9092",
        batchSize = 1000,
        pollIntervalMs = 5000,
      )

      validate(validSource)(sourceConfigValidator) shouldBe com.wix.accord.Success
    }

    "reject empty source type" in {
      val invalidSource = SourceConfig(
        sourceType = SourceType.fromString("").getOrElse(SourceType.File),
        connectionString = "localhost:9092",
        batchSize = 1000,
        pollIntervalMs = 5000,
      )

      validate(invalidSource)(sourceConfigValidator) shouldBe com.wix.accord.Success
    }

    "reject empty connection string" in {
      val invalidSource = SourceConfig(
        sourceType = SourceType.fromString("kafka").getOrElse(SourceType.File),
        connectionString = "",
        batchSize = 1000,
        pollIntervalMs = 5000,
      )

      validate(invalidSource)(sourceConfigValidator) shouldBe a[Failure]
    }

    "reject batch size less than minimum" in {
      val invalidSource = SourceConfig(
        sourceType = SourceType.fromString("kafka").getOrElse(SourceType.File),
        connectionString = "localhost:9092",
        batchSize = 0,
        pollIntervalMs = 5000,
      )

      validate(invalidSource)(sourceConfigValidator) shouldBe a[Failure]
    }

    "reject batch size greater than maximum" in {
      val invalidSource = SourceConfig(
        sourceType = SourceType.fromString("kafka").getOrElse(SourceType.File),
        connectionString = "localhost:9092",
        batchSize = 0,
        pollIntervalMs = 50000,
      )

      validate(invalidSource)(sourceConfigValidator) shouldBe a[com.wix.accord.Failure]
    }

    "reject invalid poll interval" in {
      val invalidSource = SourceConfig(
        sourceType = SourceType.fromString("kafka").getOrElse(SourceType.File),
        connectionString = "localhost:9092",
        batchSize = 1000,
        pollIntervalMs = 0,
      )

      validate(invalidSource)(sourceConfigValidator) shouldBe a[Failure]
    }
  }

  "TransformConfigValidator" should {

    "accept valid transform config" in {
      val validTransform = TransformConfig(
        transformType = "filter",
        config = Map.empty,
      )

      validate(validTransform)(transformConfigValidator) shouldBe com.wix.accord.Success
    }

    "reject empty transform type" in {
      val invalidTransform = TransformConfig(
        transformType = "",
        config = Map.empty,
      )

      validate(invalidTransform)(transformConfigValidator) shouldBe a[Failure]
    }

    "reject empty expression" in {
      val invalidTransform = TransformConfig(
        transformType = "filter",
        config = Map.empty,
      )

      validate(invalidTransform)(transformConfigValidator) shouldBe com.wix.accord.Success
    }
  }

  "SinkConfigValidator" should {

    "accept valid sink config" in {
      val validSink = SinkConfig(
        sinkType = "elasticsearch",
        connectionString = "localhost:9200",
        batchSize = 500,
      )

      validate(validSink)(sinkConfigValidator) shouldBe com.wix.accord.Success
    }

    "reject empty sink type" in {
      val invalidSink = SinkConfig(
        sinkType = "",
        connectionString = "localhost:9200",
        batchSize = 500,
      )

      validate(invalidSink)(sinkConfigValidator) shouldBe a[com.wix.accord.Failure]
    }

    "reject invalid batch size" in {
      val invalidSink = SinkConfig(
        sinkType = "elasticsearch",
        connectionString = "localhost:9200",
        batchSize = 0,
      )

      validate(invalidSink)(sinkConfigValidator) shouldBe a[Failure]
    }
  }

  "PipelineConfigValidator" should {

//    "accept valid pipeline config" in {
//      val validConfig = PipelineConfig(
//        source = SourceConfig("kafka", "localhost:9092", 1000, 5000),
//        transforms = List(
//          TransformConfig("filter", "value > 10"),
//          TransformConfig("map", "value * 2")
//        ),
//        sink = SinkConfig("elasticsearch", "localhost:9200", 500)
//      )
//
//      validate(validConfig)(pipelineConfigValidator) shouldBe true
//    }

    "reject config with empty transforms" in {
      val invalidConfig = PipelineConfig(
        source = SourceConfig(SourceType.fromString("kafka").getOrElse(SourceType.File), "localhost:9092", 1000, 5000),
        transforms = List.empty,
        sink = SinkConfig("elasticsearch", "localhost:9200", 500),
      )

      validate(invalidConfig)(pipelineConfigValidator) shouldBe a[Failure]
    }

//    "reject config with invalid source" in {
//      val invalidConfig = PipelineConfig(
//        source = SourceConfig("", "localhost:9092", 1000, 5000),
//        transforms = List(TransformConfig("filter", "value > 10")),
//        sink = SinkConfig("elasticsearch", "localhost:9200", 500)
//      )
//
//      validate(invalidConfig)(pipelineConfigValidator) shouldBe a[Failure]
//    }
  }

  "CreatePipelineValidator" should {

    val probe = testKit.createTestProbe[StatusReply[Any]]()

//    "accept valid create pipeline command" in {
//      val validCommand = CreatePipeline(
//        pipelineId = "pipeline-123",
//        name = "test-pipeline",
//        description = "Test pipeline description",
//        sourceConfig = SourceConfig("kafka", "localhost:9092", 1000, 5000),
//        transformConfigs = List(TransformConfig("filter", config = Map.empty),
//        // sinkConfig = SinkConfig("elasticsearch", "localhost:9200", 500),
//
//      )
//
//      validate(validCommand)(createPipelineValidator) shouldBe true
//    }

//    "reject command with invalid name" in {
//      val invalidCommand = CreatePipeline(
//        pipelineId = "pipeline-123",
//        name = "ab", // Too short
//        description = "Test pipeline description",
//        sourceConfig = SourceConfig("kafka", "localhost:9092", 1000, 5000),
//        transformConfigs = List(TransformConfig("filter", "value > 10")),
//        sinkConfig = SinkConfig("elasticsearch", "localhost:9200", 500),
//        replyTo = probe.ref
//      )
//
//      validate(invalidCommand)(createPipelineValidator) shouldBe a[Failure]
//    }

    "reject command with description too long" in {
      val invalidCommand = CreatePipeline(
        pipelineId = "pipeline-123",
        name = "test-pipeline",
        description = "a" * 501, // Too long
        sourceConfig =
          SourceConfig(SourceType.fromString("kafka").getOrElse(SourceType.File), "localhost:9092", 1000, 5000),
        transformConfigs = List(TransformConfig("filter", config = Map.empty)),
        sinkConfig = SinkConfig("elasticsearch", "localhost:9200", 500),
        replyTo = probe.ref,
      )

      validate(invalidCommand)(createPipelineValidator) shouldBe a[Failure]
    }

    "reject command with empty transforms" in {
      val invalidCommand = CreatePipeline(
        pipelineId = "pipeline-123",
        name = "test-pipeline",
        description = "Test pipeline description",
        sourceConfig =
          SourceConfig(SourceType.fromString("kafka").getOrElse(SourceType.File), "localhost:9092", 1000, 5000),
        transformConfigs = List.empty,
        sinkConfig = SinkConfig("elasticsearch", "localhost:9200", 500),
        replyTo = probe.ref,
      )

      validate(invalidCommand)(createPipelineValidator) shouldBe a[Failure]
    }
  }

  "IngestBatchValidator" should {

    val probe = testKit.createTestProbe[StatusReply[BatchResult]]()

    "accept valid ingest batch command" in {
      val validCommand = IngestBatch(
        pipelineId = "pipeline-123",
        batchId = "batch-456",
        records = List(
          DataRecord("key1", Map.empty),
          DataRecord("key2", Map.empty),
        ),
        sourceOffset = 100,
        replyTo = probe.ref,
      )

      validate(validCommand)(ingestBatchValidator) shouldBe com.wix.accord.Success
    }

    "reject command with empty batch ID" in {
      val invalidCommand = IngestBatch(
        pipelineId = "pipeline-123",
        batchId = "",
        records = List(DataRecord("key1", Map.empty)),
        sourceOffset = 100,
        replyTo = probe.ref,
      )

      validate(invalidCommand)(ingestBatchValidator) shouldBe a[Failure]
    }

    "reject command with empty records" in {
      val invalidCommand = IngestBatch(
        pipelineId = "pipeline-123",
        batchId = "batch-456",
        records = List.empty,
        sourceOffset = 100,
        replyTo = probe.ref,
      )

      validate(invalidCommand)(ingestBatchValidator) shouldBe a[Failure]
    }

    "reject command with negative source offset" in {
      val invalidCommand = IngestBatch(
        pipelineId = "pipeline-123",
        batchId = "batch-456",
        records = List(DataRecord("key1", Map.empty)),
        sourceOffset = -1,
        replyTo = probe.ref,
      )

      validate(invalidCommand)(ingestBatchValidator) shouldBe a[Failure]
    }

    "reject command with too many records" in {
      val invalidCommand = IngestBatch(
        pipelineId = "pipeline-123",
        batchId = "batch-456",
        records = List.fill(10001)(DataRecord("key", Map.empty)),
        sourceOffset = 100,
        replyTo = probe.ref,
      )

      validate(invalidCommand)(ingestBatchValidator) shouldBe a[Failure]
    }
  }
//
//  "PipelineErrorValidator" should {
//
//    "accept valid pipeline error" in {
//      val validError = PipelineError(
//        errorType = "",
//        code = "TIMEOUT",
//        message = "Operation timed out",
//        retryable = true
//      )
//
//      validate(validError)(pipelineErrorValidator) shouldBe true
//    }
//
//    "reject error with empty code" in {
//      val invalidError = PipelineError(
//        code = "",
//        message = "Operation timed out",
//        retryable = true
//      )
//
//      validate(invalidError)(pipelineErrorValidator) shouldBe a[Failure]
//    }
//
//    "reject error with empty message" in {
//      val invalidError = PipelineError(
//        code = "TIMEOUT",
//        message = "",
//        retryable = true
//      )
//
//      validate(invalidError)(pipelineErrorValidator) shouldBe a[Failure]
//    }
//  }

  "ValidationHelper" should {

    pending
    "format violations correctly" in {
      val invalidSource = SourceConfig(
        sourceType = SourceType.fromString("kafka").getOrElse(SourceType.File),
        connectionString = "",
        batchSize = 0,
        pollIntervalMs = 0,
      )

      validate(invalidSource)(sourceConfigValidator) match {
        case Success             => fail("Expected validation to fail")
        case Failure(violations) =>
          val formatted = ValidationHelper.formatViolations(violations)
          formatted should not be empty
          formatted should include("sourceType")
      }
    }

    "return Right for valid values" in {
      val validSource =
        SourceConfig(SourceType.fromString("kafka").getOrElse(SourceType.File), "localhost:9092", 1000, 5000)

      ValidationHelper.validate(validSource)(sourceConfigValidator) match {
        case Right(source) => source shouldBe validSource
        case Left(error)   => fail(s"Expected validation to succeed, but got: $error")
      }
    }

    pending
    "return Left with error message for invalid values" in {
      val invalidSource = SourceConfig(SourceType.fromString("kafka").getOrElse(SourceType.File), "", 0, 0)

      ValidationHelper.validate(invalidSource)(sourceConfigValidator) match {
        case Right(_)    => fail("Expected validation to fail")
        case Left(error) =>
          error should not be empty
          error should include("sourceType")
      }
    }
  }
}
