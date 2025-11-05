package com.dataflow.transforms

import com.dataflow.transforms.domain._
import com.dataflow.transforms.filters.FilterTransform
import com.dataflow.transforms.mapping.{MapTransform, FlatMapTransform}
import com.dataflow.transforms.aggregation.AggregateTransform
import com.dataflow.transforms.enrichment.EnrichTransform
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}

/**
 * Factory for creating Transform instances from configuration.
 *
 * This factory:
 * - Pattern matches on TransformationConfig type
 * - Creates appropriate Transform implementation
 * - Validates configuration
 * - Handles instantiation errors gracefully
 */
object TransformFactory {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Create a Transform from configuration.
   *
   * @param config The transform configuration
   * @return Success with Transform, or Failure with error
   */
  def create(config: TransformationConfig): Try[Transform] = {
    logger.info(s"Creating transform of type: ${config.transformType}")

    Try {
      config match {
        // Stateless transforms
        case cfg: FilterConfig =>
          validateFilterConfig(cfg)
          new FilterTransform(cfg)

        case cfg: MapConfig =>
          validateMapConfig(cfg)
          new MapTransform(cfg)

        case cfg: FlatMapConfig =>
          validateFlatMapConfig(cfg)
          new FlatMapTransform(cfg)

        // Stateful transforms
        case cfg: AggregateConfig =>
          validateAggregateConfig(cfg)
          new AggregateTransform(cfg)

        case cfg: EnrichConfig =>
          validateEnrichConfig(cfg)
          new EnrichTransform(cfg)
      }
    } match {
      case Success(transform) =>
        logger.info(s"Successfully created transform: ${transform.transformType}")
        Success(transform)

      case Failure(ex) =>
        logger.error(s"Failed to create transform: ${config.transformType}", ex)
        Failure(new TransformCreationException(
          s"Failed to create transform: ${config.transformType}",
          ex
        ))
    }
  }

  /**
   * Create multiple transforms from configurations.
   *
   * @param configs Sequence of transform configurations
   * @return Success with sequence of Transforms, or Failure if any fails
   */
  def createChain(configs: Seq[TransformationConfig]): Try[Seq[Transform]] = {
    Try {
      configs.map { config =>
        create(config) match {
          case Success(transform) => transform
          case Failure(ex) => throw ex
        }
      }
    }
  }

  // ============================================
  // VALIDATION
  // ============================================

  private def validateFilterConfig(config: FilterConfig): Unit = {
    if (config.expression.trim.isEmpty) {
      throw new IllegalArgumentException("Filter expression cannot be empty")
    }
  }

  private def validateMapConfig(config: MapConfig): Unit = {
    if (config.mappings.isEmpty) {
      throw new IllegalArgumentException("Map transform requires at least one mapping")
    }

    // Check for null values in keys
    config.mappings.keys.foreach { key =>
      if (key == null || key.trim.isEmpty) {
        throw new IllegalArgumentException("Map transform keys cannot be null or empty")
      }
    }
  }

  private def validateFlatMapConfig(config: FlatMapConfig): Unit = {
    if (config.splitField.trim.isEmpty) {
      throw new IllegalArgumentException("FlatMap split field cannot be empty")
    }
  }

  private def validateAggregateConfig(config: AggregateConfig): Unit = {
    if (config.groupByFields.isEmpty) {
      throw new IllegalArgumentException("Aggregate transform requires at least one groupBy field")
    }

    if (config.aggregations.isEmpty) {
      throw new IllegalArgumentException("Aggregate transform requires at least one aggregation")
    }

    if (config.windowSize.toMillis <= 0) {
      throw new IllegalArgumentException("Window size must be positive")
    }
  }

  private def validateEnrichConfig(config: EnrichConfig): Unit = {
    if (config.lookupField.trim.isEmpty) {
      throw new IllegalArgumentException("Enrich lookup field cannot be empty")
    }

    if (config.targetFields.isEmpty) {
      throw new IllegalArgumentException("Enrich transform requires at least one target field")
    }
  }
}

/**
 * Exception thrown when transform creation fails.
 */
class TransformCreationException(message: String, cause: Throwable)
  extends RuntimeException(message, cause)
