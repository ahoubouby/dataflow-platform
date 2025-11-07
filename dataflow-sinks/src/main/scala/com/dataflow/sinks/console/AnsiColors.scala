package com.dataflow.sinks.console

object AnsiColors {
  // Reset
  val Reset = "\u001b[0m"

  // Regular colors
  val Black   = "\u001b[30m"
  val Red     = "\u001b[31m"
  val Green   = "\u001b[32m"
  val Yellow  = "\u001b[33m"
  val Blue    = "\u001b[34m"
  val Magenta = "\u001b[35m"
  val Cyan    = "\u001b[36m"
  val White   = "\u001b[37m"

  // Bright colors
  val BrightBlack   = "\u001b[90m"
  val BrightRed     = "\u001b[91m"
  val BrightGreen   = "\u001b[92m"
  val BrightYellow  = "\u001b[93m"
  val BrightBlue    = "\u001b[94m"
  val BrightMagenta = "\u001b[95m"
  val BrightCyan    = "\u001b[96m"
  val BrightWhite   = "\u001b[97m"

  // Styles
  val Bold      = "\u001b[1m"
  val Dim       = "\u001b[2m"
  val Italic    = "\u001b[3m"
  val Underline = "\u001b[4m"

  def colored(
    text: String,
    color: String,
    enabled: Boolean,
  ): String =
    if (enabled) s"$color$text$Reset" else text

  def isTerminalSupported: Boolean = {
    System.console() != null &&
    System.getenv().getOrDefault("TERM", "").nonEmpty &&
    !System.getenv().getOrDefault("TERM", "").toLowerCase.contains("dumb")
  }
}
