package com.dataflow.sinks.console

sealed trait OutputFormat extends Product with Serializable

object OutputFormat {
  case object PrettyJSON extends OutputFormat
  case object CompactJSON extends OutputFormat
  case object Simple extends OutputFormat
  case object Table extends OutputFormat
  case object Structured extends OutputFormat
  case object KeyValue extends OutputFormat

  def fromString(s: String): Either[String, OutputFormat] = s.toLowerCase match {
    case "prettyjson" | "pretty"            => Right(PrettyJSON)
    case "compactjson" | "compact" | "json" => Right(CompactJSON)
    case "simple"                           => Right(Simple)
    case "table"                            => Right(Table)
    case "structured"                       => Right(Structured)
    case "keyvalue" | "kv"                  => Right(KeyValue)
    case other                              => Left(s"Unknown format: $other")
  }
}


