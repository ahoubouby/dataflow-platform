package com.dataflow.sinks.domain

/**
 * Health status for sinks.
 */
sealed trait HealthStatus

object HealthStatus {
  case object Healthy extends HealthStatus
  case class Degraded(reason: String) extends HealthStatus
  case class Unhealthy(reason: String) extends HealthStatus
}
