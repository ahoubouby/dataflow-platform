package com.dataflow.domain.state

import org.slf4j.{Logger, LoggerFactory}

/**
 * State transition validator for pipeline state machine.
 *
 * Valid transitions:
 * - Empty → Configured (via CreatePipeline)
 * - Configured → Running (via StartPipeline)
 * - Running → Paused (via PausePipeline)
 * - Running → Stopped (via StopPipeline)
 * - Running → Failed (via ReportFailure)
 * - Paused → Running (via ResumePipeline)
 * - Paused → Stopped (via StopPipeline)
 * - Stopped → Running (via StartPipeline)
 * - Stopped → Configured (via UpdateConfig)
 * - Failed → Configured (via ResetPipeline)
 */
object StateTransitions {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  /**
   * Checks if a state transition is valid according to the state machine.
   *
   * @param from The current state
   * @param to   The target state
   * @return true if the transition is valid
   */
  def isValidTransition(from: State, to: State): Boolean = {
    val valid = (from, to) match {
      // From EmptyState
      case (EmptyState, _: ConfiguredState) => true

      // From ConfiguredState
      case (_: ConfiguredState, _: RunningState)    => true
      case (_: ConfiguredState, _: ConfiguredState) => true // UpdateConfig

      // From RunningState
      case (_: RunningState, _: PausedState)        => true
      case (_: RunningState, _: StoppedState)       => true
      case (_: RunningState, _: FailedState)        => true
      case (_: RunningState, _: RunningState)       => true // Batch processing updates

      // From PausedState
      case (_: PausedState, _: RunningState)  => true
      case (_: PausedState, _: StoppedState)  => true
      case (_: PausedState, _: PausedState)   => true // Stay in same state

      // From StoppedState
      case (_: StoppedState, _: RunningState)     => true
      case (_: StoppedState, _: ConfiguredState)  => true
      case (_: StoppedState, _: StoppedState)     => true // UpdateConfig

      // From FailedState
      case (_: FailedState, _: ConfiguredState) => true
      case (_: FailedState, _: FailedState)     => true // Stay in same state

      // No other transitions are valid
      case _ => false
    }

    if (!valid) {
      log.warn(
        "msg=Invalid state transition from={} to={}",
        from.getClass.getSimpleName,
        to.getClass.getSimpleName,
      )
    }

    valid
  }

  /**
   * Validates a state transition and returns either Unit or an error message.
   *
   * @param from The current state
   * @param to   The target state
   * @return Right(()) if valid, Left(errorMessage) if invalid
   */
  def validateTransition(from: State, to: State): Either[String, Unit] = {
    if (isValidTransition(from, to)) {
      Right(())
    } else {
      Left(
        s"Invalid state transition from ${from.getClass.getSimpleName} to ${to.getClass.getSimpleName}",
      )
    }
  }

  /**
   * Returns all valid target states from the given state.
   *
   * @param from The current state
   * @return Set of valid target state classes
   */
  def validTargetStates(from: State): Set[Class[_ <: State]] = from match {
    case EmptyState           => Set(classOf[ConfiguredState])
    case _: ConfiguredState   => Set(classOf[RunningState], classOf[ConfiguredState])
    case _: RunningState      => Set(classOf[PausedState], classOf[StoppedState], classOf[FailedState], classOf[RunningState])
    case _: PausedState       => Set(classOf[RunningState], classOf[StoppedState], classOf[PausedState])
    case _: StoppedState      => Set(classOf[RunningState], classOf[ConfiguredState], classOf[StoppedState])
    case _: FailedState       => Set(classOf[ConfiguredState], classOf[FailedState])
  }

  /**
   * Returns a human-readable description of valid transitions from the given state.
   *
   * @param from The current state
   * @return Description of valid transitions
   */
  def describe(from: State): String = from match {
    case EmptyState         => "Can transition to: Configured (via CreatePipeline)"
    case _: ConfiguredState => "Can transition to: Running (via StartPipeline)"
    case _: RunningState    => "Can transition to: Paused (via PausePipeline), Stopped (via StopPipeline), Failed (on error)"
    case _: PausedState     => "Can transition to: Running (via ResumePipeline), Stopped (via StopPipeline)"
    case _: StoppedState    => "Can transition to: Running (via StartPipeline), Configured (via UpdateConfig)"
    case _: FailedState     => "Can transition to: Configured (via ResetPipeline)"
  }

  /**
   * State machine diagram as ASCII art.
   */
  val stateMachineDiagram: String =
    """
      |Pipeline State Machine:
      |
      |  [Empty] --CreatePipeline--> [Configured]
      |                                    |
      |                              StartPipeline
      |                                    |
      |                                    v
      |            +------------------[Running]------------------+
      |            |                      |                      |
      |      PausePipeline          StopPipeline          ReportFailure
      |            |                      |                   (fatal)
      |            v                      v                      |
      |        [Paused]               [Stopped]                  v
      |            |                      |                  [Failed]
      |      ResumePipeline          StartPipeline              |
      |            |                      |                ResetPipeline
      |            +------------------    |                      |
      |                             |     |                      |
      |                             +-----+----------------- [Configured]
      |
      |Notes:
      |- Running → Running: Batch processing (BatchProcessed, CheckpointUpdated)
      |- Configured → Configured: UpdateConfig
      |- Stopped → Stopped: UpdateConfig
      |- Paused → Stopped: StopPipeline (when paused)
      """.stripMargin
}
