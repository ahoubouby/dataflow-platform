package com.dataflow.domain.models

/**
 * Pipeline status enumeration.
 */
sealed trait PipelineStatus

object PipelineStatus {
  case object Configured extends PipelineStatus
  case object Running extends PipelineStatus
  case object Paused extends PipelineStatus
  case object Stopped extends PipelineStatus
  case object Failed extends PipelineStatus

  def fromString(s: String): PipelineStatus = s.toLowerCase match {
    case "configured" => Configured
    case "running"    => Running
    case "paused"     => Paused
    case "stopped"    => Stopped
    case "failed"     => Failed
    case _            => throw new IllegalArgumentException(s"Unknown status: $s")
  }

  def toString(status: PipelineStatus): String = status match {
    case Configured => "configured"
    case Running    => "running"
    case Paused     => "paused"
    case Stopped    => "stopped"
    case Failed     => "failed"
  }
}
