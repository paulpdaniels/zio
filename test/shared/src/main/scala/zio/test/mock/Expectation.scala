/*
 * Copyright 2017-2020 John A. De Goes and the ZIO Contributors
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

package zio.test.mock

import scala.language.implicitConversions

import zio.test.Assertion
import zio.test.mock.Expectation.{ And, Chain, Or, Repeated }
import zio.test.mock.ReturnExpectation.{ Fail, Succeed }
import zio.test.mock.internal.{ MockException, ProxyFactory, State }
import zio.{ Has, IO, Managed, Tagged, ULayer, URLayer, ZLayer }

/**
 * An `Expectation[R]` is an immutable tree structure that represents
 * expectations on environment `R`.
 */
sealed trait Expectation[R <: Has[_]] { self =>

  /**
   * Operator alias for `and`.
   */
  def &&[R0 <: Has[_]](that: Expectation[R0])(implicit tag: Tagged[R with R0]): Expectation[R with R0] =
    and[R0](that)

  /**
   * Operator alias for `or`.
   */
  def ||[R0 <: Has[_]](that: Expectation[R0])(implicit tag: Tagged[R with R0]): Expectation[R with R0] =
    or[R0](that)

  /**
   * Operator alias for `andThen`.
   */
  def ++[R0 <: Has[_]](that: Expectation[R0])(implicit tag: Tagged[R with R0]): Expectation[R with R0] =
    andThen[R0](that)

  /**
   * Compose two expectations, producing a new expectation to satisfy both.
   *
   * {{
   * val mockEnv = (MockClock.sleep(equalTo(1.second)) returns unit) and (MockConsole.getStrLn returns value("foo"))
   * }}
   */
  def and[R0 <: Has[_]](that: Expectation[R0])(implicit tag: Tagged[R with R0]): Expectation[R with R0] =
    (self, that) match {
      case (And.Items(xs1), And.Items(xs2)) => And(cast(xs1 ++ xs2))
      case (And.Items(xs), _)               => And(cast(xs :+ that))
      case (_, And.Items(xs))               => And(cast(self :: xs))
      case _                                => And(cast(self :: that :: Nil))
    }

  /**
   * Compose two expectations, producing a new expectation to satisfy both sequentially.
   *
   * {{
   * val mockEnv = (MockClock.sleep(equalTo(1.second)) returns unit) andThen (MockConsole.getStrLn returns value("foo"))
   * }}
   */
  def andThen[R0 <: Has[_]](that: Expectation[R0])(implicit tag: Tagged[R with R0]): Expectation[R with R0] =
    (self, that) match {
      case (Chain.Items(xs1), Chain.Items(xs2)) => Chain(cast(xs1 ++ xs2))
      case (Chain.Items(xs), _)                 => Chain(cast(xs :+ that))
      case (_, Chain.Items(xs))                 => Chain(cast(self :: xs))
      case _                                    => Chain(cast(self :: that :: Nil))
    }

  /**
   * Repeated this expectation producing a new expectation to
   * satisfy the itself sequentially at least given number of times.
   */
  def atLeast(min: Int): Expectation[R] =
    Repeated(self, min to -1)

  /**
   * Repeated this expectation producing a new expectation to
   * satisfy the itself sequentially at most given number of times.
   */
  def atMost(max: Int): Expectation[R] =
    Repeated(self, 1 to max)

  /**
   * Compose two expectations, producing a new expectation to satisfy one of them.
   *
   * {{
   * val mockEnv = (MockClock.sleep(equalTo(1.second)) returns unit) or (MockConsole.getStrLn returns value("foo"))
   * }}
   */
  def or[R0 <: Has[_]](that: Expectation[R0])(implicit tag: Tagged[R with R0]): Expectation[R with R0] =
    (self, that) match {
      case (Or.Items(xs1), Or.Items(xs2)) => Or(cast(xs1 ++ xs2))
      case (Or.Items(xs), _)              => Or(cast(xs :+ that))
      case (_, Or.Items(xs))              => Or(cast(self :: xs))
      case _                              => Or(cast(self :: that :: Nil))
    }

  /**
   * Repeates this expectation withing given bounds, producing a new expectation to
   * satisfy the itself sequentially given number of times.
   *
   * {{
   * val mockEnv = (MockClock.sleep(equalTo(1.second)) returns unit).repeats(1, 5)
   * }}
   *
   * NOTE: once another repetition starts executing, it must be completed in order to satisfy
   * the composite expectation. For example (A ++ B).repeats(1, 2) will be satisfied by either
   * A->B (one repetition) or A->B->A->B (two repetitions), but will fail on A->B->A
   * (incomplete second repetition).
   */
  def repeats(range: Range): Expectation[R] =
    Repeated(self, range)

  /**
   * Utility method to cast a list of expectations into desired `R` type.
   */
  private def cast[R1 <: Has[_]](children: List[Expectation[_]]): List[Expectation[R1]] =
    children.asInstanceOf[List[Expectation[R1]]]

  /**
   * Invocations log.
   */
  private[test] val invocations: List[Int]

  /**
   * Provided a `Proxy` constructs a layer with environment `R`.
   */
  private[test] def envBuilder: URLayer[Has[Proxy], R]

  /**
   * Mock execution flag.
   */
  private[test] val satisfied: Boolean

  /**
   * Short-circuit flag. If an expectation has been saturated
   * it's branch will be skipped in the invocation search.
   */
  private[test] val saturated: Boolean
}

object Expectation {

  /**
   * Models expectations conjunction on environment `R`. Expectations are checked in the order they are provided,
   * meaning that earlier expectations may shadow later ones.
   */
  private[test] case class And[R <: Has[_]: Tagged](
    children: List[Expectation[R]],
    satisfied: Boolean,
    saturated: Boolean,
    invocations: List[Int]
  ) extends Expectation[R] {
    def envBuilder: URLayer[Has[Proxy], R] = children.map(_.envBuilder).reduce(_ ++ _)
  }

  private[test] object And {

    def apply[R <: Has[_]: Tagged](children: List[Expectation[R]]): And[R] =
      And(children, false, false, List.empty)

    private[test] object Items {
      def unapply[R <: Has[_]](and: And[R]): Option[List[Expectation[R]]] =
        Some(and.children)
    }
  }

  /**
   * Models a call in environment `R` that takes input arguments `I` and returns an effect
   * that may fail with an error `E` or produce a single `A`.
   */
  private[test] case class Call[R <: Has[_], I, +E, A](
    method: Method[R, I, A],
    assertion: Assertion[I],
    returns: I => IO[E, A],
    satisfied: Boolean,
    saturated: Boolean,
    invocations: List[Int]
  ) extends Expectation[R] {
    def envBuilder: URLayer[Has[Proxy], R] = method.envBuilder
  }

  private[test] object Call {

    def apply[R <: Has[_], I, E, A](
      method: Method[R, I, A],
      assertion: Assertion[I],
      returns: I => IO[E, A]
    ): Call[R, I, E, A] =
      Call(method, assertion, returns, false, false, List.empty)
  }

  /**
   * Models sequential expectations on environment `R`.
   */
  private[test] case class Chain[R <: Has[_]: Tagged](
    children: List[Expectation[R]],
    satisfied: Boolean,
    saturated: Boolean,
    invocations: List[Int]
  ) extends Expectation[R] {
    def envBuilder: URLayer[Has[Proxy], R] = children.map(_.envBuilder).reduce(_ ++ _)
  }

  private[test] object Chain {

    def apply[R <: Has[_]: Tagged](children: List[Expectation[R]]): Chain[R] =
      Chain(children, false, false, List.empty)

    private[test] object Items {
      def unapply[R <: Has[_]](chain: Chain[R]): Option[List[Expectation[R]]] =
        Some(chain.children)
    }
  }

  /**
   * Models expectations disjunction on environment `R`. Expectations are checked in the order they are provided,
   * meaning that earlier expectations may shadow later ones.
   */
  private[test] case class Or[R <: Has[_]: Tagged](
    children: List[Expectation[R]],
    satisfied: Boolean,
    saturated: Boolean,
    invocations: List[Int]
  ) extends Expectation[R] {
    def envBuilder: URLayer[Has[Proxy], R] = children.map(_.envBuilder).reduce(_ ++ _)
  }

  private[test] object Or {

    def apply[R <: Has[_]: Tagged](children: List[Expectation[R]]): Or[R] =
      Or(children, false, false, List.empty)

    private[test] object Items {
      def unapply[R <: Has[_]](or: Or[R]): Option[List[Expectation[R]]] =
        Some(or.children)
    }
  }

  /**
   * Models expectation repetition on environment `R`.
   */
  private[test] final case class Repeated[R <: Has[_]](
    child: Expectation[R],
    range: Range,
    satisfied: Boolean,
    saturated: Boolean,
    invocations: List[Int],
    started: Int,
    completed: Int
  ) extends Expectation[R] {
    def envBuilder: URLayer[Has[Proxy], R] = child.envBuilder
  }

  private[test] object Repeated {

    def apply[R <: Has[_]](child: Expectation[R], range: Range): Repeated[R] =
      if (range.step <= 0) throw MockException.InvalidRangeException(range)
      else Repeated(child, range, false, false, List.empty, 0, 0)
  }

  /**
   * Returns a return expectation to fail with `E`.
   */
  def failure[E](failure: E): Fail[Any, E] = Fail(_ => IO.fail(failure))

  /**
   * Maps the input arguments `I` to a return expectation to fail with `E`.
   */
  def failureF[I, E](f: I => E): Fail[I, E] = Fail(i => IO.succeed(i).map(f).flip)

  /**
   * Effectfully maps the input arguments `I` to a return expectation to fail with `E`.
   */
  def failureM[I, E](f: I => IO[E, Nothing]): Fail[I, E] = Fail(f)

  /**
   * Returns a return expectation to compute forever.
   */
  def never: Succeed[Any, Nothing] = valueM(_ => IO.never)

  /**
   * Returns a return expectation to succeed with `Unit`.
   */
  def unit: Succeed[Any, Unit] = value(())

  /**
   * Returns a return expectation to succeed with `A`.
   */
  def value[A](value: A): Succeed[Any, A] = Succeed(_ => IO.succeed(value))

  /**
   * Maps the input arguments `I` to a return expectation to succeed with `A`.
   */
  def valueF[I, A](f: I => A): Succeed[I, A] = Succeed(i => IO.succeed(i).map(f))

  /**
   * Effectfully maps the input arguments `I` to a return expectation to succeed with `A`.
   */
  def valueM[I, A](f: I => IO[Nothing, A]): Succeed[I, A] = Succeed(f)

  /**
   * Implicitly converts Expectation to ZLayer mock environment.
   */
  implicit def toLayer[R <: Has[_]: Tagged](trunk: Expectation[R]): ULayer[R] =
    ZLayer.fromManagedMany(
      for {
        state <- Managed.make(State.make(trunk))(State.checkUnmetExpectations)
        env   <- (ProxyFactory.mockProxy(state) >>> trunk.envBuilder).build
      } yield env
    )
}
