package com.dataflow.sinks.console

import java.io.PrintStream
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.{ExecutionContext, Future}

import cats.syntax.either._
import com.dataflow.domain.models.DataRecord
import com.dataflow.sinks.domain._
import com.dataflow.sinks.domain.exceptions._
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.{Flow, Sink}
import org.slf4j.LoggerFactory
// ============================================================================
// Console Sink Implementation
// ============================================================================

class ConsoleSink(
  config: ConsoleSinkConfig,
)(implicit ec: ExecutionContext) extends DataSink {

  private val logger = LoggerFactory.getLogger(getClass)

  // Thread-safe state
  private val metricsRef    = new AtomicReference[SinkMetrics](
    SinkMetrics.empty.copy(startTime = Some(System.currentTimeMillis())),
  )
  private val closedRef     = new AtomicReference[Boolean](false)
  private val errorCountRef = new AtomicReference[Long](0L)

  // Output stream selection
  private val output: PrintStream = config.target match {
    case OutputTarget.StdOut => System.out
    case OutputTarget.StdErr => System.err
  }

  // Color detection
  private val colorsEnabled: Boolean = config.colorScheme match {
    case ColorScheme.Enabled  => true
    case ColorScheme.Disabled => false
    case ColorScheme.Auto     => AnsiColors.isTerminalSupported
  }

  // Formatter selection
  private val formatter: RecordFormatter = config.format match {
    case OutputFormat.PrettyJSON  => PrettyJsonFormatter
    case OutputFormat.CompactJSON => CompactJsonFormatter
    case OutputFormat.Simple      => SimpleFormatter
    case OutputFormat.Table       => TableFormatter
    case OutputFormat.Structured  => StructuredFormatter
    case OutputFormat.KeyValue    => KeyValueFormatter
  }

  // ============================================================================
  // DataSink Interface Implementation
  // ============================================================================

  override def sinkType: SinkType = SinkType.Console

  override def sink: Sink[DataRecord, Future[Done]] = {
    Flow[DataRecord]
      .map(processRecord)
      .mapConcat {
        case Right(formatted) =>
          writeToConsole(formatted)
          List(())
        case Left(error)      =>
          handleError(error)
          List(())
      }
      .toMat(Sink.ignore)((_, doneFuture) => doneFuture.map(_ => Done))
  }

  override def healthCheck(): Future[HealthStatus] = Future {
    if (closedRef.get()) {
      HealthStatus.Unhealthy("Console sink is closed")
    } else {
      val metrics   = metricsRef.get()
      val errorRate = if (metrics.recordsWritten > 0) {
        metrics.recordsFailed.toDouble / (metrics.recordsWritten + metrics.recordsFailed)
      } else {
        0.0
      }

      errorRate match {
        case rate if rate == 0.0 => HealthStatus.Healthy
        case rate if rate < 0.05 => HealthStatus.Degraded(s"Error rate: ${(rate * 100).formatted("%.2f")}%")
        case rate                => HealthStatus.Unhealthy(s"High error rate: ${(rate * 100).formatted("%.2f")}%")
      }
    }
  }

  override def close(): Future[Done] = Future {
    if (closedRef.compareAndSet(false, true)) {
      if (config.printSummary) {
        printSummary()
      }
      output.flush()
      logger.info(s"ConsoleSink closed. Total records written: ${metricsRef.get().recordsWritten}")
    }
    Done
  }

  override def metrics: SinkMetrics = metricsRef.get()

  // ============================================================================
  // Private Methods
  // ============================================================================

  private def processRecord(record: DataRecord): Either[SinkError, String] =
    formatter.format(record, config, colorsEnabled)

  private def writeToConsole(formatted: String): Unit = {
    Either.catchNonFatal {
      output.println(formatted)
      if (metricsRef.get().recordsWritten % config.flushInterval == 0) {
        output.flush()
      }
      updateMetrics(_.incrementWritten())
    }.leftMap {
      ex =>
        val error = OutputError(ex)
        handleError(error)
        error
    }
    ()
  }

  private def handleError(error: SinkError): Unit = {
    errorCountRef.updateAndGet(_ + 1)
    updateMetrics(_.incrementFailed())
    logger.error(s"ConsoleSink error: ${error.message}")
  }

  private def updateMetrics(f: SinkMetrics => SinkMetrics): Unit = {
    metricsRef.updateAndGet(current => f(current))
    ()
  }

  private def printSummary(): Unit = {
    val metrics   = metricsRef.get()
    val uptime    = metrics.startTime.getOrElse(0)
    val uptimeSec = uptime / 1000.0

    import AnsiColors._

    val summary = if (colorsEnabled) {
      s"""
         |${colored("═" * 60, Bold + Cyan, colorsEnabled)}
         |${colored("Console Sink Summary", Bold + BrightWhite, colorsEnabled)}
         |${colored("═" * 60, Bold + Cyan, colorsEnabled)}
         |${colored("Records Written:", Green, colorsEnabled)} ${colored(
          metrics.recordsWritten.toString,
          BrightWhite,
          colorsEnabled,
        )}
         |${colored("Records Failed:", Red, colorsEnabled)}  ${colored(
          metrics.recordsFailed.toString,
          BrightWhite,
          colorsEnabled,
        )}
         |${colored("Success Rate:", Yellow, colorsEnabled)}    ${colored(
          f"${metrics.recordsWritten * 100}%.2f%%",
          BrightWhite,
          colorsEnabled,
        )}
         |${colored("Throughput:", Cyan, colorsEnabled)}       ${colored(
          f"${metrics.recordsWritten}%.2f",
          BrightWhite,
          colorsEnabled,
        )} records/sec
         |${colored("Uptime:", Magenta, colorsEnabled)}          ${colored(
          f"$uptimeSec%.2f",
          BrightWhite,
          colorsEnabled,
        )} seconds
         |${colored("Format:", Blue, colorsEnabled)}           ${colored(
          config.format.toString,
          BrightWhite,
          colorsEnabled,
        )}
         |${colored("═" * 60, Bold + Cyan, colorsEnabled)}
         |""".stripMargin
    } else {
      s"""
         |${"=" * 60}
         |Console Sink Summary
         |${"=" * 60}
         |Records Written: ${metrics.recordsWritten}
         |Records Failed:  ${metrics.recordsFailed}
         |Success Rate:    ${f"${metrics.recordsWritten * 100}%.2f%%"}
         |Throughput:       ${f"${metrics.batchesWritten}%.2f"} records/sec
         |Uptime:          ${f"$uptimeSec%.2f"} seconds
         |Format:           ${config.format}
         |${"=" * 60}
         |""".stripMargin
    }

    output.println(summary)
    output.flush()
  }
}

object ConsoleSink {

  /**
   * Create a ConsoleSink with validated configuration.
   *
   * @param config The console sink configuration
   * @param ec Execution context for async operations
   * @return Either an error or a configured ConsoleSink
   */
  def create(
    config: ConsoleSinkConfig,
  )(implicit ec: ExecutionContext,
  ): Either[SinkError, ConsoleSink] = {
    Either.catchNonFatal {
      new ConsoleSink(config)
    }.leftMap(ex => ConfigurationError(ex.getMessage))
  }

  /**
   * Create a ConsoleSink with default configuration.
   */
  def default(implicit ec: ExecutionContext): ConsoleSink =
    new ConsoleSink(ConsoleSinkConfig.default)

  /**
   * Builder for fluent configuration.
   */
  def builder(): ConsoleSinkBuilder = new ConsoleSinkBuilder()

  class ConsoleSinkBuilder {
    private var format:          OutputFormat              = OutputFormat.PrettyJSON
    private var colorScheme:     ColorScheme               = ColorScheme.Auto
    private var target:          OutputTarget              = OutputTarget.StdOut
    private var showTimestamp:   Boolean                   = true
    private var showMetadata:    Boolean                   = true
    private var maxFieldWidth:   Int                       = 80
    private var bufferSize:      Int                       = 100
    private var flushInterval:   Int                       = 10
    private var printSummary:    Boolean                   = true
    private var timestampFormat: Option[DateTimeFormatter] = None

    def withFormat(f: OutputFormat):                 ConsoleSinkBuilder = { format = f; this }
    def withColorScheme(cs: ColorScheme):            ConsoleSinkBuilder = { colorScheme = cs; this }
    def withTarget(t: OutputTarget):                 ConsoleSinkBuilder = { target = t; this }
    def withTimestamp(show: Boolean):                ConsoleSinkBuilder = { showTimestamp = show; this }
    def withMetadata(show: Boolean):                 ConsoleSinkBuilder = { showMetadata = show; this }
    def withMaxFieldWidth(width: Int):               ConsoleSinkBuilder = { maxFieldWidth = width; this }
    def withBufferSize(size: Int):                   ConsoleSinkBuilder = { bufferSize = size; this }
    def withFlushInterval(interval: Int):            ConsoleSinkBuilder = { flushInterval = interval; this }
    def withSummary(show: Boolean):                  ConsoleSinkBuilder = { printSummary = show; this }

    def withTimestampFormat(fmt: DateTimeFormatter): ConsoleSinkBuilder = {
      timestampFormat = Some(fmt); this
    }

    def build()(implicit ec: ExecutionContext): Either[SinkError, ConsoleSink] = {
      for {
        config <- ConsoleSinkConfig.create(
                    format,
                    colorScheme,
                    target,
                    showTimestamp,
                    showMetadata,
                    maxFieldWidth,
                    bufferSize,
                    flushInterval,
                    printSummary,
                    timestampFormat,
                  )
        sink   <- ConsoleSink.create(config)
      } yield sink
    }
  }
}
