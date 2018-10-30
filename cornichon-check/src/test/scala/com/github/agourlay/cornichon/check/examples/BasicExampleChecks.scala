package com.github.agourlay.cornichon.check.examples

import com.github.agourlay.cornichon.CornichonFeature
import com.github.agourlay.cornichon.check._
import com.github.agourlay.cornichon.steps.regular.EffectStep

class BasicExampleChecks extends CornichonFeature with CheckDsl {

  def integerGen(rc: RandomContext): ValueGenerator[Int] = ValueGenerator(
    name = "Integer",
    genFct = () ⇒ rc.seededRandom.nextInt(100))

  def stringGen(rc: RandomContext): ValueGenerator[String] = ValueGenerator(
    name = "String",
    genFct = () ⇒ rc.seededRandom.alphanumeric.take(20).mkString(""))

  // Those examples are only here to illustrate the API.
  // `cornichon-check` is to be used for more complex state-machine
  // often involving communication with a remote server.
  def feature = Feature("Basic examples of checks") {

    Scenario("reverse string") {
      Given I check_model(maxNumberOfRuns = 10, maxNumberOfTransitions = 1)(
        modelRunner = ModelRunner.make[String](stringGen)(
          model = {

            val generateStringAction = Action1[String](
              description = "generate a string",
              preConditions = session_value("random-input").isAbsent :: Nil,
              effect = g ⇒ {
                val randomString = g()
                EffectStep.fromSyncE("save random string", _.addValue("random-input", randomString))
              },
              postConditions = Nil)

            val reverseAction = Action1[String](
              description = "reverse a string",
              preConditions = session_value("random-input").isPresent :: session_value("reversed-random-input").isAbsent :: Nil,
              effect = _ ⇒ EffectStep.fromSyncE("save reversed random string", s ⇒ {
                for {
                  value ← s.get("random-input")
                  reversed = value.reverse
                  s1 ← s.addValue("reversed-random-input", reversed)
                } yield s1
              }),
              postConditions = Nil)

            Model(
              description = "reversing a string",
              startingAction = generateStringAction,
              transitions = Map(
                generateStringAction -> ((1.0 -> reverseAction) :: Nil)
              )
            )
          }
        )
      )
    }
  }
}