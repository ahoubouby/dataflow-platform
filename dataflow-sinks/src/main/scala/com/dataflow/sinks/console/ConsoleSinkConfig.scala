package com.dataflow.sinks.console

import java.time.ZoneId
import java.time.format.DateTimeFormatter

import com.dataflow.sinks.domain.exceptions._

final case class ConsoleSinkConfig private (
  format: OutputFormat,
  colorScheme: ColorScheme,
  target: OutputTarget,
  showTimestamp: Boolean,
  showMetadata: Boolean,
  maxFieldWidth: Int,
  bufferSize: Int,
  flushInterval: Int,
  printSummary: Boolean,
  timestampFormat: DateTimeFormatter) {
  def withFormat(format: OutputFormat): ConsoleSinkConfig = copy(format = format)

  def withColors(enabled: Boolean): ConsoleSinkConfig =
    copy(colorScheme = if (enabled) ColorScheme.Enabled else ColorScheme.Disabled)
  def withTimestamp(show: Boolean): ConsoleSinkConfig = copy(showTimestamp = show)
  def withMetadata(show: Boolean):  ConsoleSinkConfig = copy(showMetadata = show)
  def withSummary(show: Boolean):   ConsoleSinkConfig = copy(printSummary = show)
}

object ConsoleSinkConfig {

  private val DefaultTimestampFormat = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    .withZone(ZoneId.systemDefault())

  def create(
    format: OutputFormat = OutputFormat.PrettyJSON,
    colorScheme: ColorScheme = ColorScheme.Auto,
    target: OutputTarget = OutputTarget.StdOut,
    showTimestamp: Boolean = true,
    showMetadata: Boolean = true,
    maxFieldWidth: Int = 80,
    bufferSize: Int = 100,
    flushInterval: Int = 10,
    printSummary: Boolean = true,
    timestampFormat: Option[DateTimeFormatter] = None,
  ): Either[SinkError, ConsoleSinkConfig] = {
    for {
      validatedWidth <- if (maxFieldWidth > 0 && maxFieldWidth <= 1000)
                          Right(maxFieldWidth)
                        else
                          Left(ConfigurationError(s"maxFieldWidth must be between 1 and 1000, got: $maxFieldWidth"))

      validatedBuffer <- if (bufferSize > 0 && bufferSize <= 10000)
                           Right(bufferSize)
                         else
                           Left(ConfigurationError(s"bufferSize must be between 1 and 10000, got: $bufferSize"))

      validatedInterval <- if (flushInterval > 0)
                             Right(flushInterval)
                           else
                             Left(ConfigurationError(s"flushInterval must be positive, got: $flushInterval"))
    } yield ConsoleSinkConfig(
      format = format,
      colorScheme = colorScheme,
      target = target,
      showTimestamp = showTimestamp,
      showMetadata = showMetadata,
      maxFieldWidth = validatedWidth,
      bufferSize = validatedBuffer,
      flushInterval = validatedInterval,
      printSummary = printSummary,
      timestampFormat = timestampFormat.getOrElse(DefaultTimestampFormat),
    )
  }

  val default: ConsoleSinkConfig = create().fold(
    err => throw new IllegalStateException(s"Failed to create default config: ${err.message}"),
    identity,
  )
}
