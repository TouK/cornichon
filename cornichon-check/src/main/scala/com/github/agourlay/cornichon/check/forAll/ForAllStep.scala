package com.github.agourlay.cornichon.check.forAll

import cats.data.StateT
import com.github.agourlay.cornichon.check.{ Generator, NoValueGenerator, RandomContext }
import com.github.agourlay.cornichon.core.Done.rightDone
import com.github.agourlay.cornichon.core.core.StepState
import com.github.agourlay.cornichon.core._
import com.github.agourlay.cornichon.util.Timing._
import monix.eval.Task

import scala.util.Random

case class ForAllStep[A, B, C, D, E, F](description: String, maxNumberOfRuns: Int, withSeed: Option[Long] = None)(ga: RandomContext ⇒ Generator[A], gb: RandomContext ⇒ Generator[B], gc: RandomContext ⇒ Generator[C], gd: RandomContext ⇒ Generator[D], ge: RandomContext ⇒ Generator[E], gf: RandomContext ⇒ Generator[F])(f: A ⇒ B ⇒ C ⇒ D ⇒ E ⇒ F ⇒ Step) extends WrapperStep {

  private val initialSeed = withSeed.getOrElse(System.currentTimeMillis())
  private val randomContext = RandomContext(new Random(new java.util.Random(initialSeed)))

  val genA = ga(randomContext)
  val genB = gb(randomContext)
  val genC = gc(randomContext)
  val genD = gd(randomContext)
  val genE = ge(randomContext)
  val genF = gf(randomContext)

  val concreteGens = List(genA, genB, genC, genD, genE, genF).filter(_ != NoValueGenerator)

  val baseTitle = s"ForAll '${concreteGens.map(_.name).mkString(",")}' check '$description'"
  val title = s"$baseTitle with maxNumberOfRuns=$maxNumberOfRuns and seed=$initialSeed"

  private def repeatModelOnSuccess(runNumber: Int)(engine: Engine, initialRunState: RunState): Task[(RunState, Either[FailedStep, Done])] =
    if (runNumber > maxNumberOfRuns)
      Task.now((initialRunState, rightDone))
    else {
      val s = initialRunState.session
      val generatedA = genA.value(s)()
      val generatedB = genB.value(s)()
      val generatedC = genC.value(s)()
      val generatedD = genD.value(s)()
      val generatedE = genE.value(s)()
      val generatedF = genF.value(s)()

      val preRunLog = InfoLogInstruction(s"Run #$runNumber", initialRunState.depth)
      val invariantRunState = initialRunState.nestedContext.recordLog(preRunLog)
      val invariantStep = f(generatedA)(generatedB)(generatedC)(generatedD)(generatedE)(generatedF)

      invariantStep.runOnEngine(engine, invariantRunState).flatMap {
        case (newState, l @ Left(_)) ⇒
          val postRunLog = InfoLogInstruction(s"Run #$runNumber - Failed", initialRunState.depth)
          val failedState = initialRunState.mergeNested(newState).recordLog(postRunLog)
          Task.now((failedState, l))
        case (newState, _) ⇒
          val postRunLog = InfoLogInstruction(s"Run #$runNumber", initialRunState.depth)
          // success case we are not propagating the Session so runs do not interfere with each-others
          val nextRunState = initialRunState.recordLogStack(newState.logStack).recordLog(postRunLog).registerCleanupSteps(newState.cleanupSteps)
          repeatModelOnSuccess(runNumber + 1)(engine, nextRunState)
      }
    }

  def onEngine(engine: Engine): StepState = StateT { initialRunState ⇒
    withDuration {
      repeatModelOnSuccess(1)(engine, initialRunState.nestedContext)
    }.map {
      case (run, executionTime) ⇒
        val depth = initialRunState.depth
        val (checkState, res) = run
        val fullLogs = res match {
          case Left(_) ⇒
            FailureLogInstruction(s"$baseTitle block failed ", depth, Some(executionTime)) +: checkState.logStack :+ failedTitleLog(depth)

          case _ ⇒
            SuccessLogInstruction(s"$baseTitle block succeeded", depth, Some(executionTime)) +: checkState.logStack :+ successTitleLog(depth)
        }
        (initialRunState.mergeNested(checkState, fullLogs), res)
    }
  }
}
