package com.dataflow.sources.models

import org.apache.pekko.stream.UniqueKillSwitch

// ============================================================================
// Thread-Safe State Management
// ============================================================================

sealed trait FileSourceRunState extends Product with Serializable

object FileSourceRunState {
  case object Idle extends FileSourceRunState
  case object Running extends FileSourceRunState
  case object Stopped extends FileSourceRunState
  case object Failed extends FileSourceRunState
}

final case class FileSourceStateSnapshot(
  currentLineNumber: Long,
  resumeFromLineNumber: Long,
  runState: FileSourceRunState,
  killSwitch: Option[UniqueKillSwitch],
  lastError: Option[FileSourceError]) {
  def isRunning: Boolean = runState == FileSourceRunState.Running
  def canStart:  Boolean = runState == FileSourceRunState.Idle || runState == FileSourceRunState.Stopped

  def withLineNumber(line: Long): FileSourceStateSnapshot =
    copy(currentLineNumber = line)

  def withRunState(state: FileSourceRunState): FileSourceStateSnapshot =
    copy(runState = state)

  def withKillSwitch(switch: UniqueKillSwitch): FileSourceStateSnapshot =
    copy(killSwitch = Some(switch))

  def withError(error: FileSourceError): FileSourceStateSnapshot =
    copy(lastError = Some(error), runState = FileSourceRunState.Failed)

  def clearKillSwitch(): FileSourceStateSnapshot =
    copy(killSwitch = None)
}

object FileSourceStateSnapshot {

  def initial(resumeFrom: Long = 0): FileSourceStateSnapshot = FileSourceStateSnapshot(
    currentLineNumber = resumeFrom,
    resumeFromLineNumber = resumeFrom,
    runState = FileSourceRunState.Idle,
    killSwitch = None,
    lastError = None,
  )
}
