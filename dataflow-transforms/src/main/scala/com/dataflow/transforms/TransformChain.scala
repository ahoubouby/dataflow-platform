package com.dataflow.transforms

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.{Transform, TransformationConfig}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.LoggerFactory

import scala.util.{Try, Success, Failure}

/**
 * Utility for chaining multiple transforms together.
 *
 * Provides convenient methods for composing transforms into a single Flow.
 */
object TransformChain {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Chain multiple transforms into a single Flow.
   *
   * Example:
   * {{{
   * val flow = TransformChain.compose(
   *   filterTransform,
   *   mapTransform,
   *   flatMapTransform
   * )
   *
   * Source(records).via(flow).runWith(sink)
   * }}}
   *
   * @param transforms Sequence of transforms to chain
   * @return A single Flow that applies all transforms in order
   */
  def compose(transforms: Transform*): Flow[DataRecord, DataRecord, NotUsed] = {
    logger.info(s"Composing ${transforms.size} transforms into chain")

    transforms.foldLeft(Flow[DataRecord]) { (flow, transform) =>
      logger.debug(s"Adding ${transform.transformType.name} to chain")
      flow.via(transform.flow)
    }
  }

  /**
   * Chain transforms from a sequence.
   *
   * Same as compose but takes a Seq instead of varargs.
   *
   * @param transforms Sequence of transforms
   * @return A single composed Flow
   */
  def chain(transforms: Seq[Transform]): Flow[DataRecord, DataRecord, NotUsed] = {
    compose(transforms: _*)
  }

  /**
   * Create and chain transforms from configurations.
   *
   * This combines TransformFactory.createChain with TransformChain.chain.
   *
   * Example:
   * {{{
   * val configs = Seq(
   *   FilterConfig("age > 18"),
   *   MapConfig(Map("firstName" -> "first_name")),
   *   FlatMapConfig("items")
   * )
   *
   * val flow = TransformChain.fromConfigs(configs) match {
   *   case Success(f) => f
   *   case Failure(ex) => throw ex
   * }
   * }}}
   *
   * @param configs Sequence of transform configurations
   * @return Success with composed Flow, or Failure if any transform fails to create
   */
  def fromConfigs(configs: Seq[TransformationConfig]): Try[Flow[DataRecord, DataRecord, NotUsed]] = {
    TransformFactory.createChain(configs).map { transforms =>
      chain(transforms)
    }
  }

  /**
   * Create a transform chain builder for fluent API.
   *
   * Example:
   * {{{
   * val flow = TransformChain.builder()
   *   .add(filterTransform)
   *   .add(mapTransform)
   *   .add(flatMapTransform)
   *   .build()
   * }}}
   */
  def builder(): TransformChainBuilder = new TransformChainBuilder()
}

/**
 * Fluent builder for transform chains.
 */
class TransformChainBuilder {
  private var transforms: List[Transform] = List.empty

  /**
   * Add a transform to the chain.
   */
  def add(transform: Transform): TransformChainBuilder = {
    transforms = transforms :+ transform
    this
  }

  /**
   * Add multiple transforms to the chain.
   */
  def addAll(ts: Transform*): TransformChainBuilder = {
    transforms = transforms ++ ts
    this
  }

  /**
   * Add a transform from configuration.
   *
   * @return This builder, or throws exception if transform creation fails
   */
  def addFromConfig(config: TransformationConfig): TransformChainBuilder = {
    TransformFactory.create(config) match {
      case Success(transform) =>
        transforms = transforms :+ transform
        this
      case Failure(ex) =>
        throw ex
    }
  }

  /**
   * Build the final Flow from all added transforms.
   */
  def build(): Flow[DataRecord, DataRecord, NotUsed] = {
    TransformChain.chain(transforms)
  }

  /**
   * Get the number of transforms in this chain.
   */
  def size: Int = transforms.size

  /**
   * Check if this chain is empty.
   */
  def isEmpty: Boolean = transforms.isEmpty
}
