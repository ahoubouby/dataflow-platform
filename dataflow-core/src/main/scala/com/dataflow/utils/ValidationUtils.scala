package com.dataflow.utils

import com.wix.accord._

object ValidationUtils {
  def oneOf[T](allowed: T*): Validator[T] = new Validator[T] {
    override def apply(value: T): Result =
      if (allowed.contains(value)) Success
      else Failure(Set(RuleViolation(value, s"must be one of: ${allowed.mkString(", ")}")))
  }
}
