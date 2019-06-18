package com.github.agourlay.cornichon.core

import cats.Foldable
import cats.data.{ NonEmptyList, StateT, ValidatedNel }
import cats.syntax.either._
import cats.syntax.apply._
import cats.instances.list._
import cats.data.NonEmptyList._
import cats.data.Validated._
import monix.eval.Task

import scala.concurrent.duration.Duration
import com.github.agourlay.cornichon.core.Done._
import com.github.agourlay.cornichon.core.Engine._
import com.github.agourlay.cornichon.core.core.StepResult
import com.github.agourlay.cornichon.resolver.PlaceholderResolver

import scala.util.control.NonFatal

class Engine(stepPreparers: List[StepPreparer]) {

  final def runScenario(session: Session, context: FeatureExecutionContext = FeatureExecutionContext.empty)(scenario: Scenario): Task[ScenarioReport] =
    context.isIgnored(scenario) match {
      case Some(reason) ⇒
        Task.now(IgnoreScenarioReport(scenario.name, reason, session))
      case None if context isPending scenario ⇒
        Task.now(PendingScenarioReport(scenario.name, session))
      case _ ⇒
        val stages = for {
          beforeResult ← regularStage(context.beforeSteps, beforeLog)
          mainResult ← if (beforeResult.isValid) regularStage(scenario.steps, mainLog) else noOpStage
          mainCleanupResult ← cleanupStage()
          finallyResult ← regularStage(context.finallySteps, finallyLog)
          finallyCleanupResult ← cleanupStage()
        } yield Foldable[List].fold(beforeResult :: mainResult :: mainCleanupResult :: finallyResult :: finallyCleanupResult :: Nil)

        val titleLog = ScenarioTitleLogInstruction(s"Scenario : ${scenario.name}", initMargin)
        val initialRunState = RunState(session, titleLog :: Nil, initMargin + 1, Nil)
        val now = System.nanoTime
        stages.run(initialRunState).map {
          case (lastState, aggregatedResult) ⇒
            ScenarioReport.build(scenario.name, lastState, aggregatedResult, Duration.fromNanos(System.nanoTime - now))
        }
    }

  private def regularStage(steps: List[Step], stageTitle: InfoLogInstruction): StateT[Task, RunState, FailedStep ValidatedNel Done] = StateT { runState ⇒
    if (steps.isEmpty)
      Task.now((runState, validDone))
    else
      runStepsShortCircuiting(steps, runState.recordLog(stageTitle)).map {
        case (resultState, resultReport) ⇒ (resultState, resultReport.toValidatedNel)
      }
  }

  private def cleanupStage(): StateT[Task, RunState, FailedStep ValidatedNel Done] = StateT { runState ⇒
    if (runState.cleanupSteps.isEmpty)
      Task.now((runState, validDone))
    else {
      // Cleanup steps are consumed to avoid double run
      val cleanupState = runState.resetCleanupSteps.recordLog(cleanupLog)
      runStepsWithoutShortCircuiting(runState.cleanupSteps, cleanupState).map {
        case (resultState, resultReport) ⇒ (resultState, resultReport.toValidated)
      }
    }
  }

  final def runStepsShortCircuiting(steps: List[Step], initialRunState: RunState): StepResult = {
    val initAcc = Task.now(initialRunState -> Done.rightDone)
    steps.foldLeft[Task[(RunState, FailedStep Either Done)]](initAcc) {
      case (runStateF, currentStep) ⇒
        runStateF.flatMap {
          case (runState, Right(_))    ⇒ prepareAndRunStep(currentStep, runState)
          case (runState, l @ Left(_)) ⇒ Task.now((runState, l))
        }
    }
  }

  private def runStepsWithoutShortCircuiting(steps: List[Step], initialRunState: RunState): Task[(RunState, Either[NonEmptyList[FailedStep], Done])] = {
    val initAcc = Task.now((initialRunState, Done: Done).asRight[NonEmptyList[(RunState, FailedStep)]])
    steps.foldLeft(initAcc) {
      case (runStateF, currentStep) ⇒ runStateF.flatMap(prepareAndRunStepsAccumulatingErrors(currentStep))
    }.map(_.fold(
      { errors ⇒ (errors.head._1, Left(errors.map(_._2))) },
      { case (r, _) ⇒ (r, rightDone) }
    ))
  }

  private def prepareAndRunStepsAccumulatingErrors(currentStep: Step)(failureOrDoneWithRunState: Either[NonEmptyList[(RunState, FailedStep)], (RunState, Done)]) = {
    val runState = failureOrDoneWithRunState.fold(_.head._1, _._1)
    // Inject RunState into Either to align on aggregation shape
    val stepResult = prepareAndRunStep(currentStep, runState).map {
      case (r, Right(_))         ⇒ Right((r, Done))
      case (r, Left(failedStep)) ⇒ Left((r, failedStep))
    }

    stepResult
      .onErrorRecover { case NonFatal(ex) ⇒ (runState, FailedStep.fromSingle(currentStep, StepExecutionError(ex))).asLeft[(RunState, Done)] }
      .map(res ⇒ (res.toValidatedNel <* failureOrDoneWithRunState.toValidated).toEither) // if current step is successful, it is propagated
  }

  private def prepareAndRunStep(currentStep: Step, runState: RunState): Task[(RunState, FailedStep Either Done)] =
    stepPreparers.foldLeft[CornichonError Either Step](currentStep.asRight) {
      (stepOrError, stepPreparer) ⇒ stepOrError.flatMap(stepPreparer.run(runState.session))
    }.fold(
      ce ⇒ Task.now(Engine.handleErrors(currentStep, runState, NonEmptyList.one(ce))),
      ps ⇒ runStep(runState, ps)
    )

  private def runStep(runState: RunState, step: Step): Task[(RunState, FailedStep Either Done)] =
    Either
      .catchNonFatal(step.runOnEngine(this, runState))
      .fold(
        e ⇒ Task.now(handleThrowable(step, runState, e)),
        _.onErrorRecover { case NonFatal(t) ⇒ handleThrowable(step, runState, t) }
      )
}

object Engine {

  private val initMargin = 1
  private val beforeLog = InfoLogInstruction("before steps", initMargin + 1)
  private val mainLog = InfoLogInstruction("main steps", initMargin + 1)
  private val finallyLog = InfoLogInstruction("finally steps", initMargin + 1)
  private val cleanupLog = InfoLogInstruction("cleanup steps", initMargin + 1)

  private val noOpStage: StateT[Task, RunState, FailedStep ValidatedNel Done] = StateT { s ⇒ Task.now((s, validDone)) }

  def withStepTitleResolver(resolver: PlaceholderResolver) =
    new Engine(stepPreparers = StepPreparerTitleResolver(resolver) :: Nil)

  def successLog(title: String, depth: Int, show: Boolean, duration: Duration): LogInstruction =
    if (show)
      SuccessLogInstruction(title, depth, Some(duration))
    else
      NoShowLogInstruction(title, depth, Some(duration))

  def errorsToFailureStep(currentStep: Step, depth: Int, errors: NonEmptyList[CornichonError]): (List[LogInstruction], FailedStep) = {
    val runLogs = errorLogStack(currentStep.title, errors, depth)
    val failedStep = FailedStep(currentStep, errors)
    (runLogs, failedStep)
  }

  def handleErrors(currentStep: Step, runState: RunState, errors: NonEmptyList[CornichonError]): (RunState, FailedStep Either Done) = {
    val (errorLogStack, failedStep) = errorsToFailureStep(currentStep, runState.depth, errors)
    (runState.recordLogStack(errorLogStack), failedStep.asLeft)
  }

  def handleThrowable(currentStep: Step, runState: RunState, error: Throwable): (RunState, FailedStep Either Done) = {
    val (errorLogStack, failedStep) = errorsToFailureStep(currentStep, runState.depth, NonEmptyList.one(CornichonError.fromThrowable(error)))
    (runState.recordLogStack(errorLogStack), failedStep.asLeft)
  }

  def errorLogStack(title: String, errors: NonEmptyList[CornichonError], depth: Int): List[FailureLogInstruction] = {
    val failureLogTitle = FailureLogInstruction(s"$title *** FAILED ***", depth)
    val errorLogs = errors.toList.map(e ⇒ FailureLogInstruction(e.renderedMessage, depth)).reverse
    errorLogs :+ failureLogTitle
  }
}