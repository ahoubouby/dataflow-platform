package com.dataflow.validation

import com.dataflow.domain.commands._
import com.dataflow.domain.models._
import com.wix.accord.dsl._

/**
 * Validators for pipeline commands and domain objects.
 * Isolated in separate package for easy testing and reusability.
 */
object PipelineValidators {

  /**
   * Maximum number of consecutive retries before failing permanently.
   */
  val MaxRetries: Int = 5

  /**
   * Maximum batch size allowed.
   */
  val MaxBatchSize: Int = 10000

  /**
   * Minimum batch size.
   */
  val MinBatchSize: Int = 1

  /**
   * Maximum poll interval in milliseconds.
   */
  val MaxPollIntervalMs: Int = 3600000 // 1 hour

  /**
   * Validator for pipeline names.
   * Names must be alphanumeric with hyphens and underscores, 3-100 characters.
   */
  implicit val pipelineNameValidator = validator[String] { name =>
    name is notEmpty
    name should matchRegex("""^[a-zA-Z0-9-_]+$""".r)
    name.size should be >= 3
    name.size should be <= 100
  }

  /**
   * Validator for SourceConfig.
   */
  implicit val sourceConfigValidator = validator[SourceConfig] { source =>
    source.sourceType is notEmpty
    source.connectionString is notEmpty
    source.batchSize should be >= MinBatchSize
    source.batchSize should be <= MaxBatchSize
    source.pollIntervalMs should be > 0
    source.pollIntervalMs should be <= MaxPollIntervalMs
  }

  /**
   * Validator for TransformConfig.
   */
  implicit val transformConfigValidator = validator[TransformConfig] { transform =>
    transform.transformType is notEmpty
    transform.expression is notEmpty
  }

  /**
   * Validator for SinkConfig.
   */
  implicit val sinkConfigValidator = validator[SinkConfig] { sink =>
    sink.sinkType is notEmpty
    sink.connectionString is notEmpty
    sink.batchSize should be >= MinBatchSize
    sink.batchSize should be <= MaxBatchSize
  }

  /**
   * Validator for PipelineConfig.
   */
  implicit val pipelineConfigValidator = validator[PipelineConfig] { config =>
    config.source is valid(sourceConfigValidator)
    config.transforms is notEmpty
    config.transforms.each is valid(transformConfigValidator)
    config.sink is valid(sinkConfigValidator)
  }

  /**
   * Validator for CreatePipeline command.
   */
  implicit val createPipelineValidator = validator[CreatePipeline] { cmd =>
    cmd.name is valid(pipelineNameValidator)
    cmd.description.size should be <= 500
    cmd.sourceConfig is valid(sourceConfigValidator)
    cmd.transformConfigs is notEmpty
    cmd.transformConfigs.each is valid(transformConfigValidator)
    cmd.sinkConfig is valid(sinkConfigValidator)
  }

  /**
   * Validator for IngestBatch command.
   */
  implicit val ingestBatchValidator = validator[IngestBatch] { cmd =>
    cmd.batchId is notEmpty
    cmd.records is notEmpty
    cmd.records.size should be <= MaxBatchSize
    cmd.sourceOffset should be >= 0
  }

  /**
   * Validator for PipelineError.
   */
  implicit val pipelineErrorValidator = validator[PipelineError] { error =>
    error.code is notEmpty
    error.message is notEmpty
  }
}

/**
 * Helper object for validation result handling.
 */
object ValidationHelper {
  import com.wix.accord._

  /**
   * Validates a value and returns either the value or an error message.
   */
  def validate[T](value: T)(implicit validator: Validator[T]): Either[String, T] = {
    accord.validate(value) match {
      case Success => Right(value)
      case Failure(violations) =>
        val errorMessage = violations.map { violation =>
          s"${violation.path}: ${violation.constraint}"
        }.mkString("; ")
        Left(errorMessage)
    }
  }

  /**
   * Formats validation violations into a human-readable string.
   */
  def formatViolations(violations: Set[Violation]): String = {
    violations.map { violation =>
      val path = if (violation.path.toString.isEmpty) "value" else violation.path.toString
      s"$path: ${violation.constraint}"
    }.mkString("; ")
  }
}
