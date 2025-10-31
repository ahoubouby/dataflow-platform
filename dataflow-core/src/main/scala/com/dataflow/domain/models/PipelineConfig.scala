package com.dataflow.domain.models

import com.dataflow.serialization.CborSerializable

/**
 * Complete pipeline configuration.
 */
final case class PipelineConfig(
                                 source: SourceConfig,
                                 transforms: List[TransformConfig],
                                 sink: SinkConfig
                               ) extends CborSerializable
