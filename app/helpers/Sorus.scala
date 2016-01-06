/*
 * Copyright 2014
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package helpers

import play.api.data.Form
import play.api.data.validation.ValidationError
import play.api.libs.json.{JsPath, JsResult}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import scalaz.syntax.either._
import scalaz.syntax.std.option._
import scalaz._

/**
 * Inspiration :
 *  - http://fr.slideshare.net/normation/nrm-scala-iocorrectlymanagingerrorsinscalav13
 *  - https://github.com/Kanaka-io/play-monadic-actions
 */
object FutureOptionDSL {

  type Step[A] = EitherT[Future, Fail, A]
  type JsErrorContent = Seq[(JsPath, Seq[ValidationError])]

  private[FutureOptionDSL] def fromFuture[A](onFailure: Throwable => Fail)(future: Future[A])(implicit ec: ExecutionContext): Step[A] =
    EitherT[Future, Fail, A](
      future.map(_.right).recover {
        case NonFatal(t) => onFailure(t).left
      }
    )

  private[FutureOptionDSL] def fromFOption[A](onNone: => Fail)(fOption: Future[Option[A]])(implicit ec: ExecutionContext): Step[A] =
    EitherT[Future, Fail, A](fOption.map(_ \/> onNone))

  private[FutureOptionDSL] def fromFEither[A, B](onLeft: B => Fail)(fEither: Future[Either[B, A]])(implicit ec: ExecutionContext): Step[A] =
    EitherT[Future, Fail, A](fEither.map(_.fold(onLeft andThen \/.left, \/.right)))

  private[FutureOptionDSL] def fromFDisjunction[A, B](onLeft: B => Fail)(fDisjunction: Future[B \/ A])(implicit ec: ExecutionContext): Step[A] =
    EitherT[Future, Fail, A](fDisjunction.map(_.leftMap(onLeft)))

  private[FutureOptionDSL] def fromOption[A](onNone: => Fail)(option: Option[A]): Step[A] =
    EitherT[Future, Fail, A](Future.successful(option \/> onNone))

  private[FutureOptionDSL] def fromEither[A, B](onLeft: B => Fail)(either: Either[B, A])(implicit ec: ExecutionContext): Step[A] =
    EitherT[Future, Fail, A](Future.successful(either.fold(onLeft andThen \/.left, \/.right)))

  private[FutureOptionDSL] def fromJsResult[A](onJsError: JsErrorContent => Fail)(jsResult: JsResult[A]): Step[A] =
    EitherT[Future, Fail, A](Future.successful(jsResult.fold(onJsError andThen \/.left, \/.right)))

  private[FutureOptionDSL] def fromForm[A](onError: Form[A] => Fail)(form: Form[A]): Step[A] =
    EitherT[Future, Fail, A](Future.successful(form.fold(onError andThen \/.left, \/.right)))

  private[FutureOptionDSL] def fromBoolean(onFalse: => Fail)(boolean: Boolean): Step[Unit] =
    EitherT[Future, Fail, Unit](Future.successful(if (boolean) ().right else onFalse.left))

  private[FutureOptionDSL] def fromTry[A](onFailure: Throwable => Fail)(tryValue: Try[A]): Step[A] =
    EitherT[Future, Fail, A](Future.successful(tryValue match {
      case Failure(t) => onFailure(t).left
      case Success(v) => v.right
    }))

  trait StepOps[A, B] {
    def orFailWith(failureHandler: B => Fail): Step[A]
    def ?|(failureHandler: B => Fail): Step[A] = orFailWith(failureHandler)
    //def ?|(failureThunk: => Fail): Step[A] = orFailWith(_ => failureThunk)
    def ?|(failureThunk: => String): Step[A] = orFailWith {
      case err: Throwable => Fail(failureThunk).withEx(err)
      case b              => Fail(b.toString).info(failureThunk)
    }
  }

  case class Fail(message: String, cause: Option[\/[Throwable, Fail]] = None) {

    def info(s: String) = Fail(s, Some(\/-(this)))

    def withEx(ex: Throwable) = this.copy(cause = Some(-\/(ex)))

    def messages(): NonEmptyList[String] = cause match {
      case None              => NonEmptyList(message)
      case Some(-\/(exp))    => message <:: message <:: NonEmptyList(exp.getMessage)
      case Some(\/-(parent)) => message <:: message <:: parent.messages
    }

    def userMessage(): String = messages.list.mkString("", " <- ", "")

    def getRootException(): Option[Throwable] = cause flatMap {
      _ match {
        case -\/(exp)    => Some(exp)
        case \/-(parent) => parent.getRootException
      }
    }
  }

  trait MonadicExtensions {

    import scala.language.implicitConversions

    val executionContext: ExecutionContext = play.api.libs.concurrent.Execution.defaultContext

    implicit val futureIsAFunctor = new Functor[Future] {
      override def map[A, B](fa: Future[A])(f: (A) => B) = fa.map(f)(executionContext)
    }

    implicit val futureIsAMonad = new Monad[Future] {
      override def point[A](a: => A) = Future(a)(executionContext)

      override def bind[A, B](fa: Future[A])(f: (A) => Future[B]) = fa.flatMap(f)(executionContext)
    }

    // This instance is needed to enable filtering/pattern-matching in for-comprehensions
    // It is acceptable for pattern-matching
    implicit val failIsAMonoid = new Monoid[Fail] {
      override def zero = Fail("")

      override def append(f1: Fail, f2: => Fail) = throw new IllegalStateException("should not happen")
    }

    implicit def futureToStepOps[A](future: Future[A]): StepOps[A, Throwable] = new StepOps[A, Throwable] {
      override def orFailWith(failureHandler: (Throwable) => Fail) = fromFuture(failureHandler)(future)(executionContext)
    }

    implicit def fOptionToStepOps[A](fOption: Future[Option[A]]): StepOps[A, Unit] = new StepOps[A, Unit] {
      override def orFailWith(failureHandler: Unit => Fail) = fromFOption(failureHandler(()))(fOption)(executionContext)
    }

    implicit def fEitherToStepOps[A, B](fEither: Future[Either[B, A]]): StepOps[A, B] = new StepOps[A, B] {
      override def orFailWith(failureHandler: (B) => Fail) = fromFEither(failureHandler)(fEither)(executionContext)
    }

    implicit def fDisjunctionToStepOps[A, B](fDisjunction: Future[B \/ A]): StepOps[A, B] = new StepOps[A, B] {
      override def orFailWith(failureHandler: (B) => Fail) = fromFDisjunction(failureHandler)(fDisjunction)(executionContext)
    }

    implicit def optionToStepOps[A](option: Option[A]): StepOps[A, Unit] = new StepOps[A, Unit] {
      override def orFailWith(failureHandler: (Unit) => Fail) = fromOption(failureHandler(()))(option)
    }

    implicit def eitherToStepOps[A, B](either: Either[B, A]): StepOps[A, B] = new StepOps[A, B] {
      override def orFailWith(failureHandler: (B) => Fail) = fromEither(failureHandler)(either)(executionContext)
    }

    implicit def jsResultToStepOps[A](jsResult: JsResult[A]): StepOps[A, JsErrorContent] = new StepOps[A, JsErrorContent] {
      override def orFailWith(failureHandler: (JsErrorContent) => Fail) = fromJsResult(failureHandler)(jsResult)
    }

    implicit def formToStepOps[A](form: Form[A]): StepOps[A, Form[A]] = new StepOps[A, Form[A]] {
      override def orFailWith(failureHandler: (Form[A]) => Fail) = fromForm(failureHandler)(form)
    }

    implicit def booleanToStepOps(boolean: Boolean): StepOps[Unit, Unit] = new StepOps[Unit, Unit] {
      override def orFailWith(failureHandler: (Unit) => Fail) = fromBoolean(failureHandler(()))(boolean)
    }

    implicit def tryToStepOps[A](tryValue: Try[A]): StepOps[A, Throwable] = new StepOps[A, Throwable] {
      override def orFailWith(failureHandler: (Throwable) => Fail) = fromTry(failureHandler)(tryValue)
    }

    implicit def stepToResult(step: Step[Fail]): Future[Fail] = step.run.map(_.toEither.merge)(executionContext)

    implicit def stepToEither[A](step: Step[A]): Future[Either[Fail, A]] = step.run.map(_.toEither)(executionContext)

    implicit def stepToDisjonction[A](step: Step[A]): Future[\/[Fail, A]] = step.run
  }
}