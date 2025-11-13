package com.dataflow.domain.models

import com.dataflow.serialization.CborSerializable
import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo, JsonTypeName}

/**
 * Source configuration (where data comes from).
 */
final case class SourceConfig(
  sourceType: SourceType,   // now strongly typed
  connectionString: String, // Connection details
  batchSize: Int,           // Records per batch
  pollIntervalMs: Int = 0,      // Milliseconds between polls
  options: Map[String, String] = Map.empty
) extends CborSerializable

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[SourceType.Kafka.type], name = "kafka"),
  new JsonSubTypes.Type(value = classOf[SourceType.File.type], name = "file"),
  new JsonSubTypes.Type(value = classOf[SourceType.Api.type], name = "api"),
  new JsonSubTypes.Type(value = classOf[SourceType.Database.type], name = "database")
))
sealed trait SourceType extends Product with Serializable {
  def name: String = this.productPrefix.toLowerCase
}

object SourceType {
  @JsonTypeName("kafka")
  case object Kafka extends SourceType

  @JsonTypeName("file")
  case object File extends SourceType

  @JsonTypeName("api")
  case object Api extends SourceType

  @JsonTypeName("database")
  case object Database extends SourceType

  val values: Seq[SourceType] = Seq(Kafka, File, Api, Database)

  /** Parse from string (case-insensitive). */
  def fromString(s: String): Option[SourceType] =
    values.find(_.name.equalsIgnoreCase(s.trim))
}
