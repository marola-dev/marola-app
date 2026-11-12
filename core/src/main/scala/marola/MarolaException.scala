package marola

import scala.util.control.NoStackTrace

/** The root for marola's own expected failures (`.claude/rules/scala.md`, "Expected failures"). */
trait MarolaException extends Throwable with NoStackTrace:
  def message: String
  override def getMessage(): String = message
