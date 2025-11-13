package com.dataflow.api.models

import com.dataflow.domain.models._
import spray.json._
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * JSON protocol for API models using spray-json.
 * Provides serialization/deserialization for all API request/response models.
 */
object JsonProtocol extends DefaultJsonProtocol {

  // ============================================
  // CUSTOM FORMATS
  // ============================================

  /**
   * JSON format for java.time.Instant
   */
  implicit object InstantJsonFormat extends RootJsonFormat[Instant] {
    private val formatter = DateTimeFormatter.ISO_INSTANT

    override def write(instant: Instant): JsValue = JsString(formatter.format(instant))

    override def read(json: JsValue): Instant = json match {
      case JsString(s) => Instant.parse(s)
      case other       => deserializationError(s"Expected ISO-8601 instant string, got $other")
    }
  }

  /**
   * JSON format for SourceType
   */
  implicit object SourceTypeJsonFormat extends RootJsonFormat[SourceType] {
    override def write(sourceType: SourceType): JsValue = JsString(sourceType.name)

    override def read(json: JsValue): SourceType = json match {
      case JsString(s) =>
        SourceType.fromString(s).getOrElse(
          deserializationError(s"Invalid source type: $s. Valid types: ${SourceType.values.map(_.name).mkString(", ")}")
        )
      case other => deserializationError(s"Expected source type string, got $other")
    }
  }

  // ============================================
  // DOMAIN MODEL FORMATS
  // ============================================

  implicit val sourceConfigFormat: RootJsonFormat[SourceConfig] = jsonFormat5(SourceConfig.apply)
  implicit val transformConfigFormat: RootJsonFormat[TransformConfig] = jsonFormat2(TransformConfig.apply)
  implicit val sinkConfigFormat: RootJsonFormat[SinkConfig] = jsonFormat3(SinkConfig.apply)
  implicit val checkpointFormat: RootJsonFormat[Checkpoint] = jsonFormat3(Checkpoint.apply)
  implicit val pipelineMetricsFormat: RootJsonFormat[PipelineMetrics] = jsonFormat6(PipelineMetrics.apply)

  // ============================================
  // REQUEST MODEL FORMATS
  // ============================================

  implicit val createPipelineRequestFormat: RootJsonFormat[CreatePipelineRequest] =
    jsonFormat5(CreatePipelineRequest.apply)

  implicit val updatePipelineRequestFormat: RootJsonFormat[UpdatePipelineRequest] =
    jsonFormat5(UpdatePipelineRequest.apply)

  implicit val stopPipelineRequestFormat: RootJsonFormat[StopPipelineRequest] =
    jsonFormat1(StopPipelineRequest.apply)

  // ============================================
  // RESPONSE MODEL FORMATS
  // ============================================

  implicit val createPipelineResponseFormat: RootJsonFormat[CreatePipelineResponse] =
    jsonFormat3(CreatePipelineResponse.apply)

  implicit val pipelineOperationResponseFormat: RootJsonFormat[PipelineOperationResponse] =
    jsonFormat4(PipelineOperationResponse.apply)

  implicit val pipelineSummaryFormat: RootJsonFormat[PipelineSummary] =
    jsonFormat6(PipelineSummary.apply)

  implicit val pipelineDetailsFormat: RootJsonFormat[PipelineDetails] =
    jsonFormat11(PipelineDetails.apply)

  implicit val pipelineListResponseFormat: RootJsonFormat[PipelineListResponse] =
    jsonFormat2(PipelineListResponse.apply)

  implicit val healthResponseFormat: RootJsonFormat[HealthResponse] =
    jsonFormat5(HealthResponse.apply)

  implicit val metricsResponseFormat: RootJsonFormat[MetricsResponse] =
    jsonFormat3(MetricsResponse.apply)

  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse] =
    jsonFormat4(ErrorResponse.apply)

  implicit val successResponseFormat: RootJsonFormat[SuccessResponse] =
    jsonFormat2(SuccessResponse.apply)
}
