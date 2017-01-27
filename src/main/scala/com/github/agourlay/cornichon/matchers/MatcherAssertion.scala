package com.github.agourlay.cornichon.matchers

import cats.data.Validated.{ invalidNel, valid }
import cats.data.{ NonEmptyList, ValidatedNel }
import com.github.agourlay.cornichon.steps.regular.assertStep.Assertion
import cats.syntax.either._
import com.github.agourlay.cornichon.core.{ CornichonError, Done }
import com.github.agourlay.cornichon.json.JsonPath
import io.circe.Json

trait MatcherAssertion extends Assertion {
  val m: Matcher
  val input: Json

  override def validated =
    Either.catchNonFatal(m.predicate(input))
      .leftMap(e ⇒ MatcherAssertionEvaluationError(m, input, e))
      .fold[ValidatedNel[CornichonError, Done]](
        errors ⇒ invalidNel(errors),
        booleanResult ⇒ if (booleanResult) valid(Done) else invalidNel(MatcherAssertionError(m, input))
      )
}

case class MatcherAssertionEvaluationError(m: Matcher, input: Json, error: Throwable) extends CornichonError {
  val baseErrorMessage = s"evaluation of matcher '${m.key}' (${m.description}) failed for input '${input.spaces2}'"
  override val causedBy = Some(NonEmptyList.of(CornichonError.fromThrowable(error)))
}

case class MatcherAssertionError(m: Matcher, input: Json) extends CornichonError {
  val baseErrorMessage = s"matcher '${m.key}' (${m.description}) failed for input '${input.spaces2}'"
}

object MatcherAssertion {
  def atJsonPath(jsonPath: JsonPath, json: Json, matcher: Matcher) =
    new MatcherAssertion {
      val m = matcher
      val input = jsonPath.run(json)
    }
}
