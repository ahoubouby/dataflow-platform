package com.dataflow.sources.models

/**
 * Source lifecycle states.
 */
sealed trait SourceState

object SourceState {
  case object Initialized extends SourceState
  case object Starting extends SourceState
  case object Running extends SourceState
  case object Stopping extends SourceState
  case object Stopped extends SourceState
  case object Failed extends SourceState
}
