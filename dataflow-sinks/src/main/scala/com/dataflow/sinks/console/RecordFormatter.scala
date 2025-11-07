package com.dataflow.sinks.console

import java.time.Instant

import cats.syntax.either._
import com.dataflow.domain.models.DataRecord
import com.dataflow.sinks.domain.exceptions._
import io.circe._
import io.circe.syntax._

trait RecordFormatter {

  def format(
    record: DataRecord,
    config: ConsoleSinkConfig,
    colorsEnabled: Boolean,
  ): Either[SinkError, String]
}

object PrettyJsonFormatter extends RecordFormatter {

  override def format(
    record: DataRecord,
    config: ConsoleSinkConfig,
    colorsEnabled: Boolean,
  ): Either[SinkError, String] = {
    Either.catchNonFatal {
      val jsonObj    = buildJsonObject(record, config)
      val prettyJson = jsonObj.spaces2

      if (colorsEnabled) {
        colorizeJson(prettyJson)
      } else {
        prettyJson
      }
    }.leftMap(EncodingError)
  }

  private def buildJsonObject(record: DataRecord, config: ConsoleSinkConfig): Json = {
    val baseFields = List(
      "id"   -> record.id.asJson,
      "data" -> record.data.asJson,
    )

    val withMetadata = if (config.showMetadata && record.metadata.nonEmpty) {
      baseFields :+ ("metadata" -> record.metadata.asJson)
    } else {
      baseFields
    }

    val withTimestamp = if (config.showTimestamp) {
      withMetadata :+ ("timestamp" -> Instant.now().toString.asJson)
    } else {
      withMetadata
    }

    Json.obj(withTimestamp: _*)
  }

  private def colorizeJson(json: String): String = {
    import AnsiColors._
    json
      .replaceAll("\"([^\"]+)\"\\s*:", s"$Cyan$Bold\"$$1\"$Reset$White:")
      .replaceAll(": \"([^\"]+)\"", s": $Green\"$$1\"$Reset")
      .replaceAll(": (\\d+\\.?\\d*)", s": $Yellow$$1$Reset")
      .replaceAll(": (true|false)", s": $Magenta$$1$Reset")
      .replaceAll(": null", s": ${Dim}null$Reset")
  }
}

object CompactJsonFormatter extends RecordFormatter {

  override def format(
    record: DataRecord,
    config: ConsoleSinkConfig,
    colorsEnabled: Boolean,
  ): Either[SinkError, String] = {
    Either.catchNonFatal {
      val jsonObj = Json.obj(
        "id"       -> record.id.asJson,
        "data"     -> record.data.asJson,
        "metadata" -> (if (config.showMetadata) record.metadata.asJson else Json.obj()),
      )
      jsonObj.noSpaces
    }.leftMap(EncodingError)
  }
}

object SimpleFormatter extends RecordFormatter {

  override def format(
    record: DataRecord,
    config: ConsoleSinkConfig,
    colorsEnabled: Boolean,
  ): Either[SinkError, String] = {
    Either.catchNonFatal {
      import AnsiColors._

      val timestamp = if (config.showTimestamp) {
        val ts = config.timestampFormat.format(Instant.now())
        colored(s"[$ts]", Dim, colorsEnabled) + " "
      } else ""

      val idPart = colored("ID:", Bold + Cyan, colorsEnabled) +
        s" ${colored(record.id, BrightWhite, colorsEnabled)}"

      val dataPart = if (record.data.nonEmpty) {
        val fields = record.data.map {
          case (k, v) =>
            colored(k, Green, colorsEnabled) + "=" + colored(truncate(v, config.maxFieldWidth), Yellow, colorsEnabled)
        }.mkString(", ")
        s" ${colored("Data:", Bold + Cyan, colorsEnabled)} {$fields}"
      } else ""

      val metaPart = if (config.showMetadata && record.metadata.nonEmpty) {
        val fields = record.metadata.map {
          case (k, v) =>
            colored(k, Magenta, colorsEnabled) + "=" + colored(
              truncate(v, config.maxFieldWidth),
              BrightBlack,
              colorsEnabled,
            )
        }.mkString(", ")
        s" ${colored("Meta:", Bold + Cyan, colorsEnabled)} {$fields}"
      } else ""

      s"$timestamp$idPart$dataPart$metaPart"
    }.leftMap(FormattingError)
  }

  private def truncate(s: String, maxLen: Int): String =
    if (s.length <= maxLen) s else s.take(maxLen - 3) + "..."
}

object TableFormatter extends RecordFormatter {

  override def format(
    record: DataRecord,
    config: ConsoleSinkConfig,
    colorsEnabled: Boolean,
  ): Either[SinkError, String] = {
    Either.catchNonFatal {
      import AnsiColors._

      val allFields = record.data ++ (if (config.showMetadata) record.metadata else Map.empty)
      val maxKeyLen = (allFields.keys.map(_.length) ++ Seq(2)).max.min(30)
      val maxValLen = config.maxFieldWidth - maxKeyLen - 7

      val separator = colored("+" + "-" * (maxKeyLen + 2) + "+" + "-" * (maxValLen + 2) + "+", Dim, colorsEnabled)
      val header    =
        colored(s"| ${"Field".padTo(maxKeyLen, ' ')} | ${"Value".padTo(maxValLen, ' ')} |", Bold + Cyan, colorsEnabled)

      val idRow = formatRow("ID", record.id, maxKeyLen, maxValLen, colorsEnabled, BrightWhite)

      val dataRows = record.data.toList.sortBy(_._1).map {
        case (k, v) =>
          formatRow(k, v, maxKeyLen, maxValLen, colorsEnabled, Green)
      }

      val metaRows = if (config.showMetadata) {
        record.metadata.toList.sortBy(_._1).map {
          case (k, v) =>
            formatRow(k, v, maxKeyLen, maxValLen, colorsEnabled, Magenta)
        }
      } else Nil

      val rows = separator :: header :: separator :: idRow :: dataRows ::: metaRows ::: List(separator)
      rows.mkString("\n")
    }.leftMap(FormattingError)
  }

  private def formatRow(
    key: String,
    value: String,
    keyLen: Int,
    valLen: Int,
    colorsEnabled: Boolean,
    keyColor: String,
  ): String = {
    import AnsiColors._
    val truncKey = key.take(keyLen).padTo(keyLen, ' ')
    val truncVal = value.take(valLen).padTo(valLen, ' ')
    s"| ${colored(truncKey, keyColor, colorsEnabled)} | ${colored(truncVal, Yellow, colorsEnabled)} |"
  }
}

object StructuredFormatter extends RecordFormatter {

  override def format(
    record: DataRecord,
    config: ConsoleSinkConfig,
    colorsEnabled: Boolean,
  ): Either[SinkError, String] = {
    Either.catchNonFatal {
      import AnsiColors._

      val timestamp = if (config.showTimestamp) {
        config.timestampFormat.format(Instant.now())
      } else ""

      val structuredData = Map(
        "timestamp"   -> timestamp,
        "record_id"   -> record.id,
        "data_fields" -> record.data.size.toString,
        "meta_fields" -> record.metadata.size.toString,
      ) ++ record.data.map {
        case (k, v) => s"data.$k" -> v
      } ++
        (if (config.showMetadata) record.metadata.map { case (k, v) => s"meta.$k" -> v }
         else Map.empty)

      structuredData.map {
        case (k, v) =>
          colored(k, Cyan, colorsEnabled) + "=" + colored(s"\"$v\"", Green, colorsEnabled)
      }.mkString(" ")
    }.leftMap(FormattingError)
  }
}

object KeyValueFormatter extends RecordFormatter {

  override def format(
    record: DataRecord,
    config: ConsoleSinkConfig,
    colorsEnabled: Boolean,
  ): Either[SinkError, String] = {
    Either.catchNonFatal {
      import AnsiColors._

      val lines = collection.mutable.ArrayBuffer.empty[String]

      if (config.showTimestamp) {
        val ts = config.timestampFormat.format(Instant.now())
        lines += colored("timestamp:", Bold + Dim, colorsEnabled) + s" $ts"
      }

      lines += colored("id:", Bold + Cyan, colorsEnabled) + s" ${colored(record.id, BrightWhite, colorsEnabled)}"

      if (record.data.nonEmpty) {
        lines += colored("data:", Bold + Green, colorsEnabled)
        record.data.toList.sortBy(_._1).foreach {
          case (k, v) =>
            lines += s"  ${colored(k, Green, colorsEnabled)}: ${colored(v, Yellow, colorsEnabled)}"
        }
      }

      if (config.showMetadata && record.metadata.nonEmpty) {
        lines += colored("metadata:", Bold + Magenta, colorsEnabled)
        record.metadata.toList.sortBy(_._1).foreach {
          case (k, v) =>
            lines += s"  ${colored(k, Magenta, colorsEnabled)}: ${colored(v, BrightBlack, colorsEnabled)}"
        }
      }

      lines.mkString("\n") + "\n" + colored("─" * 80, Dim, colorsEnabled)
    }.leftMap(FormattingError)
  }
}
