package com.dataflow.sinks.console

sealed trait OutputTarget extends Product with Serializable
object OutputTarget {
  case object StdOut extends OutputTarget
  case object StdErr extends OutputTarget
}
