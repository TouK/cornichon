package com.github.agourlay.cornichon.dsl

import org.scalatest.{ WordSpec, Matchers }

class MacroErrorSpec extends WordSpec with Matchers {

  "Macro" should {
    "compile valid feature definitions" in {
      """
        import com.github.agourlay.cornichon.dsl.BaseFeature
        import com.github.agourlay.cornichon.dsl.CoreDsl
        import com.github.agourlay.cornichon.steps.regular.EffectStep
        import scala.concurrent.Future

        class Foo extends BaseFeature with CoreDsl {
          val feature =
            Feature("foo") {
              Scenario("aaa") {
                EffectStep.fromSync("just testing", _.session)

                Repeat(10) {
                  EffectStep.fromAsync("just testing repeat", sc => Future.successful(sc.session))
                }
              }
            }
        }
      """ should compile
    }

    "not compile feature definitions containing imports in DSL" in {
      """
        import com.github.agourlay.cornichon.dsl.BaseFeature
        import com.github.agourlay.cornichon.dsl.CoreDsl
        import com.github.agourlay.cornichon.steps.regular.EffectStep

        class Foo extends BaseFeature with CoreDsl {
          val feature =
            Feature("foo") {
              import scala.concurrent.Future
              Scenario("aaa") {
                EffectStep.fromSync("just testing", _.session)

                Repeat(10) {
                  EffectStep.fromAsync("just testing repeat", sc => Future.successful(sc.session))
                }
              }
            }
        }
      """ shouldNot typeCheck
    }

    "not compile if feature definition contains invalid expressions" in {
      """
        import com.github.agourlay.cornichon.dsl.BaseFeature
        import com.github.agourlay.cornichon.dsl.CoreDsl
        import com.github.agourlay.cornichon.steps.regular.EffectStep

        class Foo extends BaseFeature with CoreDsl {
          val feature =
            Feature("foo") {
              Scenario("aaa") {
                EffectStep.fromAsync("just testing", sc => Future.successful(sc.session))

                val oops = "Hello World!"

                Repeat(10) {
                  EffectStep.fromSync("just testing repeat", _.session)
                }
              }
            }
        }
      """ shouldNot typeCheck
    }
  }

}
