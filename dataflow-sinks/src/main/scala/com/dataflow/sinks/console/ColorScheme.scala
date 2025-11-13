package com.dataflow.sinks.console

sealed trait ColorScheme extends Product with Serializable
object ColorScheme {
  case object Enabled extends ColorScheme
  case object Disabled extends ColorScheme
  case object Auto extends ColorScheme
}