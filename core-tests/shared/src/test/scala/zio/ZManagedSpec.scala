package zio

import zio.Cause.Interrupt
import zio.Exit.Failure
import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky
import zio.test._
import zio.test.environment._

object ZManagedSpec extends ZIOBaseSpec {

  import ZIOTag._

  def spec = suite("ZManaged")(
    suite("absorbWith")(
      testM("on fail") {
        assertM(ZManagedExampleError.absorbWith(identity).use[Any, Throwable, Int](ZIO.succeedNow).run)(
          fails(equalTo(ExampleError))
        )
      } @@ zioTag(errors),
      testM("on die") {
        assertM(ZManagedExampleDie.absorbWith(identity).use[Any, Throwable, Int](ZIO.succeedNow).run)(
          fails(equalTo(ExampleError))
        )
      } @@ zioTag(errors),
      testM("on success") {
        assertM(ZIO.succeedNow(1).absorbWith(_ => ExampleError))(equalTo(1))
      }
    ),
    suite("make")(
      testM("Invokes cleanups in reverse order of acquisition.") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res     = (x: Int) => ZManaged.make(effects.update(x :: _))(_ => effects.update(x :: _))
          program = res(1) *> res(2) *> res(3)
          values  <- program.use_(ZIO.unit) *> effects.get
        } yield assert(values)(equalTo(List(1, 2, 3, 3, 2, 1)))
      },
      testM("Properly performs parallel acquire and release") {
        for {
          log      <- Ref.make[List[String]](Nil)
          a        = ZManaged.make(UIO.succeedNow("A"))(_ => log.update("A" :: _))
          b        = ZManaged.make(UIO.succeedNow("B"))(_ => log.update("B" :: _))
          result   <- a.zipWithPar(b)(_ + _).use(ZIO.succeedNow)
          cleanups <- log.get
        } yield assert(result.length)(equalTo(2)) && assert(cleanups)(hasSize(equalTo(2)))
      },
      testM("Constructs an uninterruptible Managed value") {
        doInterrupt(io => ZManaged.make(io)(_ => IO.unit), _ => None)
      },
      testM("Infers the environment type correctly") {
        trait R
        trait R1
        trait R2 extends R1
        trait E
        trait A
        def acquire1: ZIO[R, E, A]               = ???
        def acquire2: ZIO[R1, E, A]              = ???
        def acquire3: ZIO[R2, E, A]              = ???
        def release1: A => ZIO[R, Nothing, Any]  = ???
        def release2: A => ZIO[R1, Nothing, Any] = ???
        def release3: A => ZIO[R2, Nothing, Any] = ???
        def managed1: ZManaged[R with R1, E, A]  = ZManaged.make(acquire1)(release2)
        def managed2: ZManaged[R with R1, E, A]  = ZManaged.make(acquire2)(release1)
        def managed3: ZManaged[R2, E, A]         = ZManaged.make(acquire2)(release3)
        def managed4: ZManaged[R2, E, A]         = ZManaged.make(acquire3)(release2)
        lazy val result                          = (managed1, managed2, managed3, managed4)
        ZIO.succeedNow(assert(result)(anything))
      }
    ),
    suite("makeEffect")(
      testM("Invokes cleanups in reverse order of acquisition.") {
        var effects               = List[Int]()
        def acquire(x: Int): Int  = { effects = x :: effects; x }
        def release(x: Int): Unit = effects = x :: effects

        val res     = (x: Int) => ZManaged.makeEffect(acquire(x))(release)
        val program = res(1) *> res(2) *> res(3)

        for {
          _ <- program.use_(ZIO.unit)
        } yield assert(effects)(equalTo(List(1, 2, 3, 3, 2, 1)))
      }
    ),
    suite("reserve")(
      testM("Interruption is possible when using this form") {
        doInterrupt(
          io => ZManaged.reserve(Reservation(io, _ => IO.unit)),
          selfId => Some(Failure(Interrupt(selfId)))
        )
      }
    ),
    suite("makeExit")(
      testM("Invokes with the failure of the use") {
        val ex = new RuntimeException("Use died")

        def res(exits: Ref[List[Exit[Any, Any]]]) =
          for {
            _ <- ZManaged.makeExit(UIO.unit)((_, e) => exits.update(e :: _))
            _ <- ZManaged.makeExit(UIO.unit)((_, e) => exits.update(e :: _))
          } yield ()

        for {
          exits  <- Ref.make[List[Exit[Any, Any]]](Nil)
          _      <- res(exits).use_(ZIO.die(ex)).run
          result <- exits.get
        } yield assert(result)(equalTo(List[Exit[Any, Any]](Exit.Failure(Cause.Die(ex)), Exit.Failure(Cause.Die(ex)))))
      } @@ zioTag(errors),
      testM("Invokes with the failure of the subsequent acquire") {
        val useEx     = new RuntimeException("Use died")
        val acquireEx = new RuntimeException("Acquire died")

        def res(exits: Ref[List[Exit[Any, Any]]]) =
          for {
            _ <- ZManaged.makeExit(UIO.unit)((_, e) => exits.update(e :: _))
            _ <- ZManaged.makeExit(ZIO.die(acquireEx))((_, e) => exits.update(e :: _))
          } yield ()

        for {
          exits  <- Ref.make[List[Exit[Any, Any]]](Nil)
          _      <- res(exits).use_(ZIO.die(useEx)).run
          result <- exits.get
        } yield assert(result)(equalTo(List[Exit[Any, Any]](Exit.Failure(Cause.Die(acquireEx)))))
      }
    ) @@ zioTag(errors),
    suite("fromEffect")(
      testM("Performed interruptibly") {
        assertM(ZManaged.fromEffect(ZIO.checkInterruptible(ZIO.succeedNow)).use(ZIO.succeedNow))(
          equalTo(InterruptStatus.interruptible)
        )
      }
    ) @@ zioTag(interruption),
    suite("fromEffectUninterruptible")(
      testM("Performed uninterruptibly") {
        assertM(ZManaged.fromEffectUninterruptible(ZIO.checkInterruptible(ZIO.succeedNow)).use(ZIO.succeedNow))(
          equalTo(InterruptStatus.uninterruptible)
        )
      }
    ) @@ zioTag(interruption),
    suite("ensuring")(
      testM("Runs on successes") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _ <- ZManaged
                .finalizer(effects.update("First" :: _))
                .ensuring(effects.update("Second" :: _))
                .use_(ZIO.unit)
          result <- effects.get
        } yield assert(result)(equalTo(List("Second", "First")))
      },
      testM("Runs on failures") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _       <- ZManaged.fromEffect(ZIO.fail(())).ensuring(effects.update("Ensured" :: _)).use_(ZIO.unit).either
          result  <- effects.get
        } yield assert(result)(equalTo(List("Ensured")))
      } @@ zioTag(errors),
      testM("Works when finalizers have defects") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _ <- ZManaged
                .finalizer(ZIO.dieMessage("Boom"))
                .ensuring(effects.update("Ensured" :: _))
                .use_(ZIO.unit)
                .run
          result <- effects.get
        } yield assert(result)(equalTo(List("Ensured")))
      }
    ),
    suite("ensuringFirst")(
      testM("Runs on successes") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _ <- ZManaged
                .finalizer(effects.update("First" :: _))
                .ensuringFirst(effects.update("Second" :: _))
                .use_(ZIO.unit)
          result <- effects.get
        } yield assert(result)(equalTo(List("First", "Second")))
      },
      testM("Runs on failures") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _       <- ZManaged.fromEffect(ZIO.fail(())).ensuringFirst(effects.update("Ensured" :: _)).use_(ZIO.unit).either
          result  <- effects.get
        } yield assert(result)(equalTo(List("Ensured")))
      } @@ zioTag(errors),
      testM("Works when finalizers have defects") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _ <- ZManaged
                .finalizer(ZIO.dieMessage("Boom"))
                .ensuringFirst(effects.update("Ensured" :: _))
                .use_(ZIO.unit)
                .run
          result <- effects.get
        } yield assert(result)(equalTo(List("Ensured")))
      }
    ),
    testM("eventually") {
      def acquire(ref: Ref[Int]) =
        for {
          v <- ref.get
          r <- if (v < 10) ref.update(_ + 1) *> IO.fail("Ouch")
              else UIO.succeedNow(v)
        } yield r

      for {
        ref <- Ref.make(0)
        _   <- ZManaged.make(acquire(ref))(_ => UIO.unit).eventually.use(_ => UIO.unit)
        r   <- ref.get
      } yield assert(r)(equalTo(10))
    },
    suite("flatMap")(
      testM("All finalizers run even when finalizers have defects") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _ <- (for {
                _ <- ZManaged.finalizer(ZIO.dieMessage("Boom"))
                _ <- ZManaged.finalizer(effects.update("First" :: _))
                _ <- ZManaged.finalizer(ZIO.dieMessage("Boom"))
                _ <- ZManaged.finalizer(effects.update("Second" :: _))
                _ <- ZManaged.finalizer(ZIO.dieMessage("Boom"))
                _ <- ZManaged.finalizer(effects.update("Third" :: _))
              } yield ()).use_(ZIO.unit).run
          result <- effects.get
        } yield assert(result)(equalTo(List("First", "Second", "Third")))
      }
    ),
    suite("foldM")(
      testM("Runs onFailure on failure") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res     = (x: Int) => Managed.make(effects.update(x :: _))(_ => effects.update(x :: _))
          program = Managed.fromEffect(IO.fail(())).foldM(_ => res(1), _ => Managed.unit)
          values  <- program.use_(ZIO.unit).ignore *> effects.get
        } yield assert(values)(equalTo(List(1, 1)))
      } @@ zioTag(errors),
      testM("Runs onSuccess on success") {
        implicit val canFail = CanFail
        for {
          effects <- Ref.make[List[Int]](Nil)
          res     = (x: Int) => Managed.make(effects.update(x :: _))(_ => effects.update(x :: _))
          program = ZManaged.succeedNow(()).foldM(_ => Managed.unit, _ => res(1))
          values  <- program.use_(ZIO.unit).ignore *> effects.get
        } yield assert(values)(equalTo(List(1, 1)))
      },
      testM("Invokes cleanups") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res     = (x: Int) => Managed.make(effects.update(x :: _))(_ => effects.update(x :: _))
          program = res(1).flatMap(_ => ZManaged.fail(())).foldM(_ => res(2), _ => res(3))
          values  <- program.use_(ZIO.unit).ignore *> effects.get
        } yield assert(values)(equalTo(List(1, 2, 2, 1)))
      },
      testM("Invokes cleanups on interrupt - 1") {
        implicit val canFail = CanFail
        for {
          effects <- Ref.make[List[Int]](Nil)
          res     = (x: Int) => Managed.make(effects.update(x :: _))(_ => effects.update(x :: _))
          program = res(1).flatMap(_ => ZManaged.interrupt).foldM(_ => res(2), _ => res(3))
          values  <- program.use_(ZIO.unit).sandbox.ignore *> effects.get
        } yield assert(values)(equalTo(List(1, 1)))
      } @@ zioTag(interruption),
      testM("Invokes cleanups on interrupt - 2") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res     = (x: Int) => Managed.make(effects.update(x :: _))(_ => effects.update(x :: _))
          program = res(1).flatMap(_ => ZManaged.fail(())).foldM(_ => res(2), _ => res(3))
          values  <- program.use_(ZIO.interrupt).sandbox.ignore *> effects.get
        } yield assert(values)(equalTo(List(1, 2, 2, 1)))
      } @@ zioTag(interruption),
      testM("Invokes cleanups on interrupt - 3") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res     = (x: Int) => Managed.make(effects.update(x :: _))(_ => effects.update(x :: _))
          program = res(1).flatMap(_ => ZManaged.fail(())).foldM(_ => res(2) *> ZManaged.interrupt, _ => res(3))
          values  <- program.use_(ZIO.unit).sandbox.ignore *> effects.get
        } yield assert(values)(equalTo(List(1, 2, 2, 1)))
      } @@ zioTag(interruption)
    ),
    suite("foreach")(
      testM("Returns elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeedNow(int)

        val managed = ZManaged.foreach(List(1, 2, 3, 4))(res)
        managed.use[Any, Nothing, TestResult](res => ZIO.succeedNow(assert(res)(equalTo(List(1, 2, 3, 4)))))
      },
      testM("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.foreach(List(1, 2, 3, 4))(_ => res))
      },
      testM("Invokes cleanups in reverse order of acquisition") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res     = (x: Int) => ZManaged.make(effects.update(x :: _))(_ => effects.update(x :: _))
          program = ZManaged.foreach(List(1, 2, 3))(res)
          values  <- program.use_(ZIO.unit) *> effects.get
        } yield assert(values)(equalTo(List(1, 2, 3, 3, 2, 1)))
      }
    ),
    suite("foreach for Option")(
      testM("Returns elements if Some") {
        def res(int: Int) =
          ZManaged.succeed(int)

        val managed = ZManaged.foreach(Some(3))(res)
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(equalTo(Some(3)))))
      },
      testM("Returns nothing if None") {
        def res(int: Int) =
          ZManaged.succeed(int)

        val managed = ZManaged.foreach(None)(res)
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(equalTo(None))))
      },
      testM("Runs finalizers") {
        testFinalizersPar(1, res => ZManaged.foreach(Some(4))(_ => res))
      }
    ),
    suite("foreachPar")(
      testM("Returns elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeedNow(int)

        val managed = ZManaged.foreachPar(List(1, 2, 3, 4))(res)
        managed.use[Any, Nothing, TestResult](res => ZIO.succeedNow(assert(res)(equalTo(List(1, 2, 3, 4)))))
      },
      testM("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.foreachPar(List(1, 2, 3, 4))(_ => res))
      },
      testM("Runs reservations in parallel") {
        testReservePar(4, res => ZManaged.foreachPar(List(1, 2, 3, 4))(_ => res))
      },
      testM("Runs acquisitions in parallel") {
        testAcquirePar(4, res => ZManaged.foreachPar(List(1, 2, 3, 4))(_ => res))
      }
    ),
    suite("foreachParN")(
      testM("Returns elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeedNow(int)

        val managed = ZManaged.foreachParN(2)(List(1, 2, 3, 4))(res)
        managed.use[Any, Nothing, TestResult](res => ZIO.succeedNow(assert(res)(equalTo(List(1, 2, 3, 4)))))
      },
      testM("Uses at most n fibers for reservation") {
        testFinalizersPar(4, res => ZManaged.foreachParN(2)(List(1, 2, 3, 4))(_ => res))
      },
      testM("Uses at most n fibers for acquisition") {
        testReservePar(2, res => ZManaged.foreachParN(2)(List(1, 2, 3, 4))(_ => res))
      },
      testM("Runs finalizers") {
        testAcquirePar(2, res => ZManaged.foreachParN(2)(List(1, 2, 3, 4))(_ => res))
      }
    ),
    suite("foreach_")(
      testM("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.foreach_(List(1, 2, 3, 4))(_ => res))
      }
    ),
    suite("foreachPar_")(
      testM("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.foreachPar_(List(1, 2, 3, 4))(_ => res))
      },
      testM("Runs reservations in parallel") {
        testReservePar(4, res => ZManaged.foreachPar_(List(1, 2, 3, 4))(_ => res))
      },
      testM("Runs acquisitions in parallel") {
        testAcquirePar(4, res => ZManaged.foreachPar_(List(1, 2, 3, 4))(_ => res))
      }
    ),
    suite("foreachParN_")(
      testM("Uses at most n fibers for reservation") {
        testFinalizersPar(4, res => ZManaged.foreachParN_(2)(List(1, 2, 3, 4))(_ => res))
      },
      testM("Uses at most n fibers for acquisition") {
        testReservePar(2, res => ZManaged.foreachParN_(2)(List(1, 2, 3, 4))(_ => res))
      },
      testM("Runs finalizers") {
        testReservePar(2, res => ZManaged.foreachParN_(2)(List(1, 2, 3, 4))(_ => res))
      }
    ),
    suite("fork")(
      testM("Runs finalizers properly") {
        for {
          finalized <- Ref.make(false)
          latch     <- Promise.make[Nothing, Unit]
          _ <- ZManaged
                .reserve(Reservation(latch.succeed(()) *> ZIO.never, _ => finalized.set(true)))
                .fork
                .use_(latch.await)
          result <- finalized.get
        } yield assert(result)(isTrue)
      },
      testM("Acquires interruptibly") {
        for {
          finalized    <- Ref.make(false)
          acquireLatch <- Promise.make[Nothing, Unit]
          useLatch     <- Promise.make[Nothing, Unit]
          fib <- ZManaged
                  .reserve(
                    Reservation(
                      acquireLatch.succeed(()) *> ZIO.never,
                      _ => finalized.set(true)
                    )
                  )
                  .fork
                  .use_(useLatch.succeed(()) *> ZIO.never)
                  .fork
          _      <- acquireLatch.await
          _      <- useLatch.await
          _      <- fib.interrupt
          result <- finalized.get
        } yield assert(result)(isTrue)
      } @@ zioTag(interruption)
    ),
    suite("fromAutoCloseable")(
      testM("Runs finalizers properly") {
        for {
          runtime <- ZIO.runtime[Any]
          effects <- Ref.make(List[String]())
          closeable = UIO(new AutoCloseable {
            def close(): Unit = runtime.unsafeRun(effects.update("Closed" :: _))
          })
          _      <- ZManaged.fromAutoCloseable(closeable).use_(ZIO.unit)
          result <- effects.get
        } yield assert(result)(equalTo(List("Closed")))
      }
    ),
    suite("ifM")(
      testM("runs `onTrue` if result of `b` is `true`") {
        val managed = ZManaged.ifM(ZManaged.succeedNow(true))(ZManaged.succeedNow(true), ZManaged.succeedNow(false))
        assertM(managed.use(ZIO.succeedNow))(isTrue)
      },
      testM("runs `onFalse` if result of `b` is `false`") {
        val managed = ZManaged.ifM(ZManaged.succeedNow(false))(ZManaged.succeedNow(true), ZManaged.succeedNow(false))
        assertM(managed.use(ZIO.succeedNow))(isFalse)
      },
      testM("infers correctly") {
        trait R
        trait R1 extends R
        trait E1
        trait E extends E1
        trait A
        val b: ZManaged[R, E, Boolean]   = ZManaged.succeedNow(true)
        val onTrue: ZManaged[R1, E1, A]  = ZManaged.succeedNow(new A {})
        val onFalse: ZManaged[R1, E1, A] = ZManaged.succeedNow(new A {})
        val _                            = ZManaged.ifM(b)(onTrue, onFalse)
        ZIO.succeed(assertCompletes)
      }
    ),
    suite("mergeAll")(
      testM("Merges elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeedNow(int)

        val managed = ZManaged.mergeAll(List(1, 2, 3, 4).map(res))(List[Int]()) { case (acc, a) => a :: acc }
        managed.use[Any, Nothing, TestResult](res => ZIO.succeedNow(assert(res)(equalTo(List(4, 3, 2, 1)))))
      },
      testM("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.mergeAll(List.fill(4)(res))(()) { case (_, b) => b })
      }
    ),
    suite("mergeAllPar")(
      testM("Merges elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeedNow(int)

        val managed = ZManaged.mergeAllPar(List(1, 2, 3, 4).map(res))(List[Int]()) { case (acc, a) => a :: acc }
        managed.use[Any, Nothing, TestResult](res => ZIO.succeedNow(assert(res)(equalTo(List(4, 3, 2, 1)))))
      },
      testM("Runs reservations in parallel") {
        testReservePar(4, res => ZManaged.mergeAllPar(List.fill(4)(res))(()) { case (_, b) => b })
      },
      testM("Runs acquisitions in parallel") {
        testAcquirePar(4, res => ZManaged.mergeAllPar(List.fill(4)(res))(()) { case (_, b) => b })
      },
      testM("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.mergeAllPar(List.fill(4)(res))(()) { case (_, b) => b })
      }
    ),
    suite("mergeAllParN")(
      testM("Merges elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeedNow(int)
        val managed = ZManaged.mergeAllParN(2)(List(1, 2, 3, 4).map(res))(List[Int]()) { case (acc, a) => a :: acc }
        managed.use[Any, Nothing, TestResult](res => ZIO.succeedNow(assert(res)(equalTo(List(4, 3, 2, 1)))))
      },
      testM("Uses at most n fibers for reservation") {
        testReservePar(2, res => ZManaged.mergeAllParN(2)(List.fill(4)(res))(0) { case (a, _) => a })
      },
      testM("Uses at most n fibers for acquisition") {
        testAcquirePar(2, res => ZManaged.mergeAllParN(2)(List.fill(4)(res))(0) { case (a, _) => a })
      },
      testM("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.mergeAllParN(2)(List.fill(4)(res))(0) { case (a, _) => a })
      },
      testM("All finalizers run even when finalizers have defects") {
        for {
          releases <- Ref.make[Int](0)
          _ <- ZManaged
                .mergeAllParN(2)(
                  List(
                    ZManaged.finalizer(ZIO.dieMessage("Boom")),
                    ZManaged.finalizer(releases.update(_ + 1)),
                    ZManaged.finalizer(ZIO.dieMessage("Boom")),
                    ZManaged.finalizer(releases.update(_ + 1)),
                    ZManaged.finalizer(ZIO.dieMessage("Boom")),
                    ZManaged.finalizer(releases.update(_ + 1))
                  )
                )(())((_, _) => ())
                .use_(ZIO.unit)
                .run
          count <- releases.get
        } yield assert(count)(equalTo(3))
      }
    ),
    suite("onExit")(
      testM("Calls the cleanup") {
        for {
          finalizersRef <- Ref.make[List[String]](Nil)
          resultRef     <- Ref.make[Option[Exit[Nothing, String]]](None)
          _ <- ZManaged
                .make(UIO.succeedNow("42"))(_ => finalizersRef.update("First" :: _))
                .onExit(e => finalizersRef.update("Second" :: _) *> resultRef.set(Some(e)))
                .use_(ZIO.unit)
          finalizers <- finalizersRef.get
          result     <- resultRef.get
        } yield assert(finalizers)(equalTo(List("Second", "First"))) && assert(result)(isSome(succeeds(equalTo("42"))))
      }
    ),
    suite("onExitFirst")(
      testM("Calls the cleanup") {
        for {
          finalizersRef <- Ref.make[List[String]](Nil)
          resultRef     <- Ref.make[Option[Exit[Nothing, String]]](None)
          _ <- ZManaged
                .make(UIO.succeedNow("42"))(_ => finalizersRef.update("First" :: _))
                .onExitFirst(e => finalizersRef.update("Second" :: _) *> resultRef.set(Some(e)))
                .use_(ZIO.unit)
          finalizers <- finalizersRef.get
          result     <- resultRef.get
        } yield assert(finalizers)(equalTo(List("First", "Second"))) && assert(result)(isSome(succeeds(equalTo("42"))))
      }
    ),
    suite("orElseFail")(
      testM("executes this effect and returns its value if it succeeds") {
        implicit val canFail = CanFail
        val managed          = ZManaged.succeedNow(true).orElseFail(false)
        assertM(managed.use(ZIO.succeedNow))(isTrue)
      },
      testM("otherwise fails with the specified error") {
        val managed = ZManaged.fail(false).orElseFail(true).flip
        assertM(managed.use(ZIO.succeedNow))(isTrue)
      }
    ) @@ zioTag(errors),
    suite("orElseSucceed")(
      testM("executes this effect and returns its value if it succeeds") {
        implicit val canFail = CanFail
        val managed          = ZManaged.succeedNow(true).orElseSucceed(false)
        assertM(managed.use(ZIO.succeedNow))(isTrue)
      },
      testM("otherwise succeeds with the specified value") {
        val managed = ZManaged.fail(false).orElseSucceed(true)
        assertM(managed.use(ZIO.succeedNow))(isTrue)
      }
    ) @@ zioTag(errors),
    suite("preallocate")(
      testM("runs finalizer on interruption") {
        for {
          ref    <- Ref.make(0)
          res    = ZManaged.reserve(Reservation(ZIO.interrupt, _ => ref.update(_ + 1)))
          _      <- res.preallocate.run.ignore
          result <- assertM(ref.get)(equalTo(1))
        } yield result
      } @@ zioTag(interruption),
      testM("runs finalizer when resource closes") {
        for {
          ref    <- Ref.make(0)
          res    = ZManaged.reserve(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
          _      <- res.preallocate.flatMap(_.use_(ZIO.unit))
          result <- assertM(ref.get)(equalTo(1))
        } yield result
      },
      testM("propagates failures in acquire") {
        for {
          exit <- ZManaged.fromEffect(ZIO.fail("boom")).preallocate.either
        } yield assert(exit)(isLeft(equalTo("boom")))
      } @@ zioTag(errors),
      testM("propagates failures in reserve") {
        for {
          exit <- ZManaged.make(ZIO.fail("boom"))(_ => ZIO.unit).preallocate.either
        } yield assert(exit)(isLeft(equalTo("boom")))
      } @@ zioTag(errors)
    ),
    suite("preallocateManaged")(
      testM("run release on interrupt while entering inner scope") {
        Ref.make(0).flatMap { ref =>
          ZManaged
            .reserve(Reservation(ZIO.interrupt, _ => ref.update(_ + 1)))
            .preallocateManaged
            .use_(ZIO.unit)
            .run *> assertM(ref.get)(equalTo(1))
        }
      } @@ zioTag(interruption),
      testM("eagerly run acquisition when preallocateManaged is invoked") {
        for {
          ref <- Ref.make(0)
          result <- ZManaged
                     .reserve(Reservation(ref.update(_ + 1), _ => ZIO.unit))
                     .preallocateManaged
                     .use(r => ref.get.zip(r.use_(ref.get)))
        } yield assert(result)(equalTo((1, 1)))
      },
      testM("run release on scope exit") {
        Ref.make(0).flatMap { ref =>
          ZManaged.reserve(Reservation(ZIO.unit, _ => ref.update(_ + 1))).preallocateManaged.use_(ZIO.unit) *> assertM(
            ref.get
          )(equalTo(1))
        }
      },
      testM("don't run release twice") {
        Ref.make(0).flatMap { ref =>
          ZManaged
            .reserve(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
            .preallocateManaged
            .use(_.use_(ZIO.unit)) *> assertM(ref.get)(equalTo(1))
        }
      }
    ),
    suite("reduceAll")(
      testM("Reduces elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeedNow(List(int))

        val managed = ZManaged.reduceAll(ZManaged.succeedNow(Nil), List(1, 2, 3, 4).map(res)) {
          case (a1, a2) => a1 ++ a2
        }
        managed.use[Any, Nothing, TestResult](res => ZIO.succeedNow(assert(res)(equalTo(List(1, 2, 3, 4)))))
      },
      testM("Runs finalizers") {
        testFinalizersPar(
          4,
          res => ZManaged.reduceAll(ZManaged.succeedNow(0), List.fill(4)(res)) { case (a, _) => a }
        )
      }
    ),
    suite("reduceAllPar")(
      testM("Reduces elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeedNow(List(int))

        val managed = ZManaged.reduceAllPar(ZManaged.succeedNow(Nil), List(1, 2, 3, 4).map(res)) {
          case (a1, a2) => a1 ++ a2
        }
        managed.use[Any, Nothing, TestResult](res => ZIO.succeedNow(assert(res)(equalTo(List(1, 2, 3, 4)))))
      },
      testM("Runs reservations in parallel") {
        testReservePar(
          4,
          res => ZManaged.reduceAllPar(ZManaged.succeedNow(0), List.fill(4)(res)) { case (a, _) => a }
        )
      },
      testM("Runs acquisitions in parallel") {
        testAcquirePar(
          4,
          res => ZManaged.reduceAllPar(ZManaged.succeedNow(0), List.fill(4)(res)) { case (a, _) => a }
        )
      },
      testM("Runs finalizers") {
        testFinalizersPar(
          4,
          res => ZManaged.reduceAllPar(ZManaged.succeedNow(0), List.fill(4)(res)) { case (a, _) => a }
        )
      }
    ),
    suite("reduceAllParN")(
      testM("Reduces elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeedNow(List(int))

        val managed = ZManaged.reduceAllParN(2)(ZManaged.succeedNow(Nil), List(1, 2, 3, 4).map(res)) {
          case (acc, a) => a ++ acc
        }
        managed.use[Any, Nothing, TestResult](res => ZIO.succeedNow(assert(res)(equalTo(List(4, 3, 2, 1)))))
      },
      testM("Uses at most n fibers for reservation") {
        testFinalizersPar(
          4,
          res => ZManaged.reduceAllParN(2)(ZManaged.succeedNow(0), List.fill(4)(res)) { case (a, _) => a }
        )
      },
      testM("Uses at most n fibers for acquisition") {
        testReservePar(
          2,
          res => ZManaged.reduceAllParN(2)(ZManaged.succeedNow(0), List.fill(4)(res)) { case (a, _) => a }
        )
      },
      testM("Runs finalizers") {
        testAcquirePar(
          2,
          res => ZManaged.reduceAllParN(2)(ZManaged.succeedNow(0), List.fill(4)(res)) { case (a, _) => a }
        )
      },
      testM("All finalizers run even when finalizers have defects") {
        for {
          releases <- Ref.make[Int](0)
          _ <- ZManaged
                .reduceAllParN(2)(
                  ZManaged.finalizer(ZIO.dieMessage("Boom")),
                  List(
                    ZManaged.finalizer(releases.update(_ + 1)),
                    ZManaged.finalizer(ZIO.dieMessage("Boom")),
                    ZManaged.finalizer(releases.update(_ + 1)),
                    ZManaged.finalizer(ZIO.dieMessage("Boom")),
                    ZManaged.finalizer(releases.update(_ + 1))
                  )
                )((_, _) => ())
                .use_(ZIO.unit)
                .run
          count <- releases.get
        } yield assert(count)(equalTo(3))
      }
    ),
    suite("reject")(
      testM("returns failure ignoring value") {
        val goodCase =
          ZManaged.succeedNow(0).reject({ case v if v != 0 => "Partial failed!" }).sandbox.either

        val badCase = ZManaged
          .succeedNow(1)
          .reject({ case v if v != 0 => "Partial failed!" })
          .sandbox
          .either
          .map(_.left.map(_.failureOrCause))

        for {
          goodCaseCheck <- goodCase.use(r => ZIO.succeedNow(assert(r)(isRight(equalTo(0)))))
          badCaseCheck  <- badCase.use(r => ZIO.succeedNow(assert(r)(isLeft(isLeft(equalTo("Partial failed!"))))))
        } yield goodCaseCheck && badCaseCheck
      }
    ) @@ zioTag(errors),
    suite("rejectM")(
      testM("returns failure ignoring value") {
        val goodCase =
          ZManaged
            .succeedNow(0)
            .rejectM[Any, String]({ case v if v != 0 => ZManaged.succeedNow("Partial failed!") })
            .sandbox
            .either

        val partialBadCase =
          ZManaged
            .succeedNow(1)
            .rejectM({ case v if v != 0 => ZManaged.fail("Partial failed!") })
            .sandbox
            .either
            .map(_.left.map(_.failureOrCause))

        val badCase =
          ZManaged
            .succeedNow(1)
            .rejectM({ case v if v != 0 => ZManaged.fail("Partial failed!") })
            .sandbox
            .either
            .map(_.left.map(_.failureOrCause))

        for {
          r1 <- goodCase.use(r => ZIO.succeedNow(assert(r)(isRight(equalTo(0)))))
          r2 <- partialBadCase.use(r => ZIO.succeedNow(assert(r)(isLeft(isLeft(equalTo("Partial failed!"))))))
          r3 <- badCase.use(r => ZIO.succeedNow(assert(r)(isLeft(isLeft(equalTo("Partial failed!"))))))
        } yield r1 && r2 && r3
      }
    ) @@ zioTag(errors),
    suite("retry")(
      testM("Should retry the reservation") {
        for {
          retries <- Ref.make(0)
          program = ZManaged
            .make(retries.updateAndGet(_ + 1).flatMap(r => if (r == 3) ZIO.unit else ZIO.fail(())))(_ => ZIO.unit)
          _ <- program.retry(Schedule.recurs(3)).use(_ => ZIO.unit).ignore
          r <- retries.get
        } yield assert(r)(equalTo(3))
      },
      testM("Should retry the acquisition") {
        for {
          retries <- Ref.make(0)
          program = Managed.reserve(
            Reservation(
              retries.updateAndGet(_ + 1).flatMap(r => if (r == 3) ZIO.unit else ZIO.fail(())),
              _ => ZIO.unit
            )
          )
          _ <- program.retry(Schedule.recurs(3)).use(_ => ZIO.unit).ignore
          r <- retries.get
        } yield assert(r)(equalTo(3))
      },
      testM("Should share retries between both") {
        for {
          retries1 <- Ref.make(0)
          retries2 <- Ref.make(0)
          program = ZManaged {
            retries1.updateAndGet(_ + 1).flatMap { r1 =>
              if (r1 < 3) ZIO.fail(())
              else
                ZIO.succeedNow {
                  Reservation(
                    retries2.updateAndGet(_ + 1).flatMap(r2 => if (r2 == 3) ZIO.unit else ZIO.fail(())),
                    _ => ZIO.unit
                  )
                }
            }
          }
          _  <- program.retry(Schedule.recurs(6)).use(_ => ZIO.unit).ignore
          r1 <- retries1.get
          r2 <- retries2.get
        } yield assert(r1)(equalTo(3)) && assert(r2)(equalTo(3))
      }
    ) @@ zioTag(errors),
    suite("preallocationScope")(
      testM("runs finalizer on interruption") {
        ZManaged.preallocationScope.use { preallocate =>
          for {
            ref    <- Ref.make(0)
            res    = ZManaged.reserve(Reservation(ZIO.interrupt, _ => ref.update(_ + 1)))
            _      <- preallocate(res).run.ignore
            result <- assertM(ref.get)(equalTo(1))
          } yield result
        }
      } @@ zioTag(interruption),
      testM("runs finalizer when resource closes") {
        ZManaged.preallocationScope.use { preallocate =>
          for {
            ref    <- Ref.make(0)
            res    = ZManaged.reserve(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
            _      <- preallocate(res).flatMap(_.use_(ZIO.unit))
            result <- assertM(ref.get)(equalTo(1))
          } yield result
        }
      },
      testM("propagates failures in acquire") {
        ZManaged.preallocationScope.use { preallocate =>
          for {
            exit <- preallocate(ZManaged.fromEffect(ZIO.fail("boom"))).either
          } yield assert(exit)(isLeft(equalTo("boom")))
        }
      } @@ zioTag(errors),
      testM("propagates failures in reserve") {
        ZManaged.preallocationScope.use { preallocate =>
          for {
            exit <- preallocate(ZManaged.make(ZIO.fail("boom"))(_ => ZIO.unit)).either
          } yield assert(exit)(isLeft(equalTo("boom")))
        }
      } @@ zioTag(errors),
      testM("eagerly run acquisition when preallocate is invoked") {
        ZManaged.preallocationScope.use { preallocate =>
          for {
            ref <- Ref.make(0)
            res <- preallocate(ZManaged.reserve(Reservation(ref.update(_ + 1), _ => ZIO.unit)))
            r1  <- ref.get
            _   <- res.use_(ZIO.unit)
            r2  <- ref.get
          } yield assert(r1)(equalTo(1)) && assert(r2)(equalTo(1))
        }
      },
      testM("run release on scope exit") {
        Ref.make(0).flatMap { ref =>
          ZManaged.preallocationScope.use { preallocate =>
            preallocate(ZManaged.reserve(Reservation(ZIO.unit, _ => ref.update(_ + 1))))
          } *> assertM(ref.get)(equalTo(1))
        }
      },
      testM("don't run release twice") {
        Ref.make(0).flatMap { ref =>
          ZManaged.preallocationScope.use { preallocate =>
            preallocate(ZManaged.reserve(Reservation(ZIO.unit, _ => ref.update(_ + 1)))).flatMap(_.use_(ZIO.unit))
          } *> assertM(ref.get)(equalTo(1))
        }
      },
      testM("can be used multiple times") {
        Ref.make(0).flatMap { ref =>
          ZManaged.preallocationScope.use { preallocate =>
            val res = ZManaged.reserve(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
            preallocate(res) *> preallocate(res)
          } *> assertM(ref.get)(equalTo(2))
        }
      }
    ),
    suite("scope")(
      testM("runs finalizer on interruption") {
        for {
          ref     <- Ref.make(0)
          managed = makeTestManaged(ref)
          zio     = ZManaged.scope.use(scope => scope(managed).fork.flatMap(_.join))
          fiber   <- zio.fork
          _       <- fiber.interrupt
          result  <- ref.get
        } yield assert(result)(equalTo(0))
      } @@ zioTag(interruption) @@ nonFlaky,
      testM("runs finalizer when close is called") {
        ZManaged.scope.use { scope =>
          for {
            ref <- Ref.make(0)
            res = ZManaged.reserve(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
            result <- scope(res).flatMap {
                       case (_, close) =>
                         for {
                           res1 <- ref.get
                           _    <- close
                           res2 <- ref.get
                         } yield (res1, res2)
                     }
          } yield assert(result)(equalTo((0, 1)))
        }
      },
      testM("propagates failures in acquire") {
        ZManaged.scope.use { scope =>
          for {
            exit <- scope(ZManaged.fromEffect(ZIO.fail("boom"))).either
          } yield assert(exit)(isLeft(equalTo("boom")))
        }
      } @@ zioTag(errors),
      testM("propagates failures in reserve") {
        ZManaged.scope.use { scope =>
          for {
            exit <- scope(ZManaged.make(ZIO.fail("boom"))(_ => ZIO.unit)).either
          } yield assert(exit)(isLeft(equalTo("boom")))
        }
      } @@ zioTag(errors),
      testM("run release on scope exit") {
        Ref.make(0).flatMap { ref =>
          ZManaged.scope.use { scope =>
            scope(ZManaged.reserve(Reservation(ZIO.unit, _ => ref.update(_ + 1))))
          } *> assertM(ref.get)(equalTo(1))
        }
      },
      testM("don't run release twice") {
        Ref.make(0).flatMap { ref =>
          ZManaged.scope.use { scope =>
            scope(ZManaged.reserve(Reservation(ZIO.unit, _ => ref.update(_ + 1)))).flatMap(_._2)
          } *> assertM(ref.get)(equalTo(1))
        }
      },
      testM("can be used multiple times") {
        Ref.make(0).flatMap { ref =>
          ZManaged.scope.use { scope =>
            val res = ZManaged.reserve(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
            scope(res) *> scope(res)
          } *> assertM(ref.get)(equalTo(2))
        }
      }
    ),
    suite("tap")(
      testM("Doesn't change the managed resource") {
        ZManaged
          .succeedNow(1)
          .tap(n => ZManaged.succeedNow(n + 1))
          .map(actual => assert(1)(equalTo(actual)))
          .use(ZIO.succeedNow)
      },
      testM("Runs given effect") {
        Ref
          .make(0)
          .toManaged_
          .tap(_.update(_ + 1).toManaged_)
          .mapM(_.get)
          .map(i => assert(i)(equalTo(1)))
          .use(ZIO.succeedNow)
      }
    ),
    suite("tapBoth")(
      testM("Doesn't change the managed resource") {
        ZManaged
          .fromEither(Right[String, Int](1))
          .tapBoth(_ => ZManaged.unit, n => ZManaged.succeedNow(n + 1))
          .map(actual => assert(1)(equalTo(actual)))
          .use(ZIO.succeedNow)
      },
      testM("Runs given effect on failure") {
        (
          for {
            ref <- Ref.make(0).toManaged_
            _ <- ZManaged
                  .fromEither(Left(1))
                  .tapBoth(e => ref.update(_ + e).toManaged_, (_: Any) => ZManaged.unit)
            actual <- ref.get.toManaged_
          } yield assert(actual)(equalTo(2))
        ).fold(e => assert(e)(equalTo(1)), identity).use(ZIO.succeedNow)
      } @@ zioTag(errors),
      testM("Runs given effect on success") {
        (
          for {
            ref <- Ref.make(1).toManaged_
            _ <- ZManaged
                  .fromEither(Right[String, Int](2))
                  .tapBoth(_ => ZManaged.unit, n => ref.update(_ + n).toManaged_)
            actual <- ref.get.toManaged_
          } yield assert(actual)(equalTo(3))
        ).use(ZIO.succeedNow)
      }
    ),
    suite("tapCause")(
      testM("effectually peeks at the cause of the failure of the acquired resource") {
        (for {
          ref    <- Ref.make(false).toManaged_
          result <- ZManaged.dieMessage("die").tapCause(_ => ref.set(true).toManaged_).run
          effect <- ref.get.toManaged_
        } yield assert(result)(dies(hasMessage(equalTo("die")))) &&
          assert(effect)(isTrue)).use(ZIO.succeedNow)
      }
    ) @@ zioTag(errors),
    suite("tapError")(
      testM("Doesn't change the managed resource") {
        ZManaged
          .fromEither(Right[String, Int](1))
          .tapError(str => ZManaged.succeedNow(str.length))
          .map(actual => assert(1)(equalTo(actual)))
          .use(ZIO.succeedNow)
      },
      testM("Runs given effect on failure") {
        (
          for {
            ref <- Ref.make(0).toManaged_
            _ <- ZManaged
                  .fromEither(Left(1))
                  .tapError(e => ref.update(_ + e).toManaged_)
            actual <- ref.get.toManaged_
          } yield assert(actual)(equalTo(2))
        ).fold(e => assert(e)(equalTo(1)), identity).use(ZIO.succeedNow)
      } @@ zioTag(errors),
      testM("Doesn't run given effect on success") {
        (
          for {
            ref <- Ref.make(1).toManaged_
            _ <- ZManaged
                  .fromEither(Right[Int, Int](2))
                  .tapError(n => ref.update(_ + n).toManaged_)
            actual <- ref.get.toManaged_
          } yield assert(actual)(equalTo(1))
        ).use(ZIO.succeedNow)
      }
    ),
    suite("timed")(
      testM("Should time both the reservation and the acquisition") {
        val managed = ZManaged(
          clock.sleep(20.milliseconds) *> ZIO.succeedNow(Reservation(clock.sleep(20.milliseconds), _ => ZIO.unit))
        )
        val test = managed.timed.use {
          case (duration, _) =>
            ZIO.succeedNow(assert(duration.toNanos)(isGreaterThanEqualTo(40.milliseconds.toNanos)))
        }
        def awaitSleeps(n: Int): ZIO[TestClock with Live, Nothing, Unit] =
          TestClock.sleeps.flatMap {
            case x if x.length >= n => ZIO.unit
            case _                  => Live.live(ZIO.sleep(20.milliseconds)) *> awaitSleeps(n)
          }
        for {
          f      <- test.fork
          _      <- awaitSleeps(1)
          _      <- TestClock.adjust(20.milliseconds)
          _      <- awaitSleeps(1)
          _      <- TestClock.adjust(20.milliseconds)
          result <- f.join
        } yield result
      }
    ),
    suite("timeout")(
      testM("Returns Some if the timeout isn't reached") {
        val managed = ZManaged.make(ZIO.succeedNow(1))(_ => ZIO.unit)
        managed.timeout(Duration.Infinity).use(res => ZIO.succeedNow(assert(res)(isSome(equalTo(1)))))
      },
      testM("Returns None if the reservation takes longer than d") {
        for {
          latch   <- Promise.make[Nothing, Unit]
          managed = ZManaged.make(latch.await)(_ => ZIO.unit)
          res     <- managed.timeout(Duration.Zero).use(res => ZIO.succeedNow(assert(res)(isNone)))
          _       <- latch.succeed(())
        } yield res
      },
      testM("Returns None if the acquisition takes longer than d") {
        for {
          latch   <- Promise.make[Nothing, Unit]
          managed = ZManaged.reserve(Reservation(latch.await, _ => ZIO.unit))
          res     <- managed.timeout(Duration.Zero).use(res => ZIO.succeedNow(assert(res)(isNone)))
          _       <- latch.succeed(())
        } yield res
      },
      testM("Runs finalizers if returning None and reservation is successful") {
        for {
          reserveLatch <- Promise.make[Nothing, Unit]
          releaseLatch <- Promise.make[Nothing, Unit]
          managed      = ZManaged.reserve(Reservation(reserveLatch.await, _ => releaseLatch.succeed(())))
          res          <- managed.timeout(Duration.Zero).use(ZIO.succeedNow)
          _            <- reserveLatch.succeed(())
          _            <- releaseLatch.await
        } yield assert(res)(isNone)
      },
      testM("Runs finalizers if returning None and reservation is successful after timeout") {
        for {
          acquireLatch <- Promise.make[Nothing, Unit]
          releaseLatch <- Promise.make[Nothing, Unit]
          managed = ZManaged(
            acquireLatch.await *> ZIO.succeedNow(Reservation(ZIO.unit, _ => releaseLatch.succeed(())))
          )
          res <- managed.timeout(Duration.Zero).use(ZIO.succeedNow)
          _   <- acquireLatch.succeed(())
          _   <- releaseLatch.await
        } yield assert(res)(isNone)
      }
    ),
    suite("toLayerMany")(
      testM("converts a managed effect to a layer") {
        val managed = ZEnv.live.build
        val layer   = managed.toLayerMany
        val zio1    = ZIO.environment[ZEnv]
        val zio2    = zio1.provideLayer(layer)
        assertM(zio2)(anything)
      }
    ),
    suite("withEarlyRelease")(
      testM("Provides a canceler that can be used to eagerly evaluate the finalizer") {
        for {
          ref     <- Ref.make(false)
          managed = ZManaged.make(ZIO.unit)(_ => ref.set(true)).withEarlyRelease
          result <- managed.use {
                     case (canceler, _) => canceler *> ref.get
                   }
        } yield assert(result)(isTrue)
      },
      testM("The canceler should run uninterruptibly") {
        for {
          ref     <- Ref.make(true)
          latch   <- Promise.make[Nothing, Unit]
          managed = Managed.make(ZIO.unit)(_ => latch.succeed(()) *> ZIO.never.whenM(ref.get)).withEarlyRelease
          result <- managed.use {
                     case (canceler, _) =>
                       for {
                         fiber        <- canceler.forkDaemon
                         _            <- latch.await
                         interruption <- withLive(fiber.interrupt)(_.timeout(5.seconds))
                         _            <- ref.set(false)
                       } yield interruption
                   }
        } yield assert(result)(isNone)
      } @@ zioTag(interruption),
      testM("If completed, the canceler should cause the regular finalizer to not run") {
        for {
          latch   <- Promise.make[Nothing, Unit]
          ref     <- Ref.make(0)
          managed = ZManaged.make(ZIO.unit)(_ => ref.update(_ + 1)).withEarlyRelease
          _       <- managed.use(_._1).ensuring(latch.succeed(()))
          _       <- latch.await
          result  <- ref.get
        } yield assert(result)(equalTo(1))
      },
      testM("The canceler will run with an exit value indicating the effect was interrupted") {
        for {
          ref     <- Ref.make(false)
          managed = ZManaged.makeExit(ZIO.unit)((_, e) => ref.set(e.interrupted))
          _       <- managed.withEarlyRelease.use(_._1)
          result  <- ref.get
        } yield assert(result)(isTrue)
      }
    ) @@ zioTag(interruption),
    suite("withEarlyReleaseExit")(
      testM("Allows specifying an exit value") {
        for {
          ref     <- Ref.make(false)
          managed = ZManaged.makeExit(ZIO.unit)((_, e) => ref.set(e.succeeded))
          _       <- managed.withEarlyReleaseExit(Exit.unit).use(_._1)
          result  <- ref.get
        } yield assert(result)(isTrue)
      }
    ),
    suite("zipPar")(
      testM("Does not swallow exit cause if one reservation fails") {
        (for {
          latch  <- Promise.make[Nothing, Unit]
          first  = ZManaged.fromEffect(latch.succeed(()) *> ZIO.sleep(Duration.Infinity))
          second = ZManaged.fromEffect(latch.await *> ZIO.fail(()))
          _      <- first.zipPar(second).use_(ZIO.unit)
        } yield ()).run
          .map(assert(_)(fails(equalTo(()))))
      } @@ zioTag(errors),
      testM("Runs finalizers if one acquisition fails") {
        for {
          releases <- Ref.make(0)
          first    = ZManaged.unit
          second   = ZManaged.reserve(Reservation(ZIO.fail(()), _ => releases.update(_ + 1)))
          _        <- first.zipPar(second).use(_ => ZIO.unit).ignore
          r        <- releases.get
        } yield assert(r)(equalTo(1))
      } @@ zioTag(errors),
      testM("Does not swallow acquisition if one acquisition fails") {
        ZIO.fiberId.flatMap { selfId =>
          (for {
            latch  <- Promise.make[Nothing, Unit]
            first  = ZManaged.fromEffect(latch.succeed(()) *> ZIO.sleep(Duration.Infinity))
            second = ZManaged.reserve(Reservation(latch.await *> ZIO.fail(()), _ => ZIO.unit))
            _      <- first.zipPar(second).use_(ZIO.unit)
          } yield ()).run
            .map(assert(_)(equalTo(Exit.Failure(Cause.Both(Cause.Fail(()), Cause.interrupt(selfId))))))
        }
      } @@ zioTag(errors),
      testM("Run finalizers if one reservation fails") {
        for {
          reserveLatch <- Promise.make[Nothing, Unit]
          releases     <- Ref.make[Int](0)
          first        = ZManaged.reserve(Reservation(reserveLatch.succeed(()), _ => releases.update(_ + 1)))
          second       = ZManaged.fromEffect(reserveLatch.await *> ZIO.fail(()))
          _            <- first.zipPar(second).use_(ZIO.unit).orElse(ZIO.unit)
          count        <- releases.get
        } yield assert(count)(equalTo(1))
      } @@ zioTag(errors),
      testM("Runs finalizers if it is interrupted") {
        for {
          ref1     <- Ref.make(0)
          ref2     <- Ref.make(0)
          managed1 = makeTestManaged(ref1)
          managed2 = makeTestManaged(ref2)
          managed3 = managed1 <&> managed2
          fiber    <- managed3.use_(ZIO.unit).fork
          _        <- fiber.interrupt
          result1  <- ref1.get
          result2  <- ref2.get
        } yield assert(result1)(equalTo(0)) && assert(result2)(equalTo(0))
      } @@ zioTag(interruption) @@ nonFlaky
    ),
    suite("flatten")(
      testM("Returns the same as ZManaged.flatten") {
        checkM(Gen.string(Gen.alphaNumericChar)) { str =>
          val test = for {
            flatten1 <- ZManaged.succeedNow(ZManaged.succeedNow(str)).flatten
            flatten2 <- ZManaged.flatten(ZManaged.succeedNow(ZManaged.succeedNow(str)))
          } yield assert(flatten1)(equalTo(flatten2))
          test.use[Any, Nothing, TestResult](r => ZIO.succeedNow(r))
        }
      }
    ),
    suite("absolve")(
      testM("Returns the same as ZManaged.absolve") {
        checkM(Gen.string(Gen.alphaNumericChar)) { str =>
          val managedEither: ZManaged[Any, Nothing, Either[Nothing, String]] = ZManaged.succeedNow(Right(str))
          val test = for {
            abs1 <- managedEither.absolve
            abs2 <- ZManaged.absolve(managedEither)
          } yield assert(abs1)(equalTo(abs2))
          test.use[Any, Nothing, TestResult](result => ZIO.succeedNow(result))
        }
      }
    ),
    suite("switchable")(
      testM("runs the right finalizer on interruption") {
        for {
          effects <- Ref.make(List[String]())
          latch   <- Promise.make[Nothing, Unit]
          fib <- ZManaged
                  .switchable[Any, Nothing, Unit]
                  .use { switch =>
                    switch(ZManaged.finalizer(effects.update("First" :: _))) *>
                      switch(ZManaged.finalizer(effects.update("Second" :: _))) *>
                      latch.succeed(()) *>
                      ZIO.never
                  }
                  .fork
          _      <- latch.await
          _      <- fib.interrupt
          result <- effects.get
        } yield assert(result)(equalTo(List("Second", "First")))
      } @@ zioTag(interruption)
    ),
    suite("memoize")(
      testM("acquires and releases exactly once") {
        for {
          effects  <- Ref.make[List[Int]](Nil)
          res      = (x: Int) => ZManaged.make(effects.update(x :: _))(_ => effects.update(x :: _))
          program  = res(1) *> res(2) *> res(3)
          memoized = program.memoize
          _ <- memoized.use { managed =>
                val use = managed.use_(ZIO.unit)
                use *> use *> use
              }
          res <- effects.get
        } yield assert(res)(equalTo(List(1, 2, 3, 3, 2, 1)))
      },
      testM("acquires and releases nothing if the inner managed is never used") {
        for {
          acquired <- Ref.make(false)
          released <- Ref.make(false)
          managed  = Managed.make(acquired.set(true))(_ => released.set(true))
          _        <- managed.memoize.use_(ZIO.unit)
          res      <- assertM(acquired.get zip released.get)(equalTo((false, false)))
        } yield res
      },
      testM("behaves like an ordinary ZManaged if flattened") {
        for {
          resource <- Ref.make(0)
          acquire  = resource.update(_ + 1)
          release  = resource.update(_ - 1)
          managed  = ZManaged.make(acquire)(_ => release).memoize.flatten
          res1     <- managed.use_(assertM(resource.get)(equalTo(1)))
          res2     <- assertM(resource.get)(equalTo(0))
        } yield res1 && res2
      },
      testM("properly raises an error if acquiring fails") {
        for {
          released <- Ref.make(false)
          error    = ":-o"
          managed  = Managed.make(ZIO.fail(error))(_ => released.set(true))
          res1 <- managed.memoize.use { memoized =>
                   for {
                     v1 <- memoized.use_(ZIO.unit).either
                     v2 <- memoized.use_(ZIO.unit).either
                   } yield assert(v1)(equalTo(v2)) && assert(v1)(isLeft(equalTo(error)))
                 }
          res2 <- assertM(released.get)(isFalse)
        } yield res1 && res2
      } @@ zioTag(errors),
      testM("behaves properly if acquiring dies") {
        for {
          released <- Ref.make(false)
          ohNoes   = ";-0"
          managed  = Managed.make(ZIO.dieMessage(ohNoes))(_ => released.set(true))
          res1 <- managed.memoize.use { memoized =>
                   assertM(memoized.use_(ZIO.unit).run)(dies(hasMessage(equalTo(ohNoes))))
                 }
          res2 <- assertM(released.get)(isFalse)
        } yield res1 && res2
      },
      testM("behaves properly if releasing dies") {
        val myBad   = "#@*!"
        val managed = Managed.make(ZIO.unit)(_ => ZIO.dieMessage(myBad))

        val program = managed.memoize.use(memoized => memoized.use_(ZIO.unit))

        assertM(program.run)(dies(hasMessage(equalTo(myBad))))
      },
      testM("behaves properly if use dies") {
        val darn = "darn"
        for {
          latch    <- Promise.make[Nothing, Unit]
          released <- Ref.make(false)
          managed  = Managed.make(ZIO.unit)(_ => released.set(true) *> latch.succeed(()))
          v1       <- managed.memoize.use(memoized => memoized.use_(ZIO.dieMessage(darn))).run
          v2       <- released.get
        } yield assert(v1)(dies(hasMessage(equalTo(darn)))) && assert(v2)(isTrue)
      },
      testM("behaves properly if use is interrupted") {
        for {
          latch1   <- Promise.make[Nothing, Unit]
          latch2   <- Promise.make[Nothing, Unit]
          latch3   <- Promise.make[Nothing, Unit]
          resource <- Ref.make(0)
          acquire  = resource.update(_ + 1)
          release  = resource.update(_ - 1) *> latch3.succeed(())
          managed  = ZManaged.make(acquire)(_ => release)
          fiber    <- managed.memoize.use(memoized => memoized.use_(latch1.succeed(()) *> latch2.await)).fork
          _        <- latch1.await
          res1     <- assertM(resource.get)(equalTo(1))
          _        <- fiber.interrupt
          _        <- latch3.await
          res2     <- assertM(resource.get)(equalTo(0))
          res3     <- assertM(latch2.isDone)(isFalse)
        } yield res1 && res2 && res3
      } @@ zioTag(interruption)
    ),
    suite("merge")(
      testM("on flipped result") {
        val managed: Managed[Int, Int] = ZManaged.succeedNow(1)

        for {
          a <- managed.merge.use(ZIO.succeedNow)
          b <- managed.flip.merge.use(ZIO.succeedNow)
        } yield assert(a)(equalTo(b))
      }
    ),
    suite("catch")(
      testM("catchAllCause") {
        val zm: ZManaged[Any, String, String] =
          for {
            _ <- ZManaged.succeedNow("foo")
            f <- ZManaged.fail("Uh oh!")
          } yield f

        val errorToVal = zm.catchAllCause(c => ZManaged.succeedNow(c.failureOption.getOrElse(c.toString)))
        assertM(errorToVal.use(ZIO.succeedNow))(equalTo("Uh oh!"))
      },
      testM("catchAllSomeCause transforms cause if matched") {
        val zm: ZManaged[Any, String, String] =
          for {
            _ <- ZManaged.succeedNow("foo")
            f <- ZManaged.fail("Uh oh!")
          } yield f

        val errorToVal = zm.catchSomeCause {
          case Cause.Fail("Uh oh!") => ZManaged.succeedNow("matched")
        }
        assertM(errorToVal.use(ZIO.succeedNow))(equalTo("matched"))
      } @@ zioTag(errors),
      testM("catchAllSomeCause keeps the failure cause if not matched") {
        val zm: ZManaged[Any, String, String] =
          for {
            _ <- ZManaged.succeedNow("foo")
            f <- ZManaged.fail("Uh oh!")
          } yield f

        val errorToVal = zm.catchSomeCause {
          case Cause.Fail("not matched") => ZManaged.succeedNow("matched")
        }
        val executed = errorToVal.use[Any, String, String](ZIO.succeedNow).run
        assertM(executed)(fails(equalTo("Uh oh!")))
      } @@ zioTag(errors)
    ),
    suite("collect")(
      testM("collectM maps value, if PF matched") {
        val managed = ZManaged.succeedNow(42).collectM("Oh No!") {
          case 42 => ZManaged.succeedNow(84)
        }
        val effect: ZIO[Any, String, Int] = managed.use(ZIO.succeedNow)

        assertM(effect)(equalTo(84))
      },
      testM("collectM produces given error, if PF not matched") {
        val managed = ZManaged.succeedNow(42).collectM("Oh No!") {
          case 43 => ZManaged.succeedNow(84)
        }
        val effect: ZIO[Any, String, Int] = managed.use(ZIO.succeedNow)

        assertM(effect.run)(fails(equalTo("Oh No!")))
      }
    )
  )

  val ExampleError = new Throwable("Oh noes!")

  val ZManagedExampleError: ZManaged[Any, Throwable, Int] = ZManaged.fail[Throwable](ExampleError)

  val ZManagedExampleDie: ZManaged[Any, Throwable, Int] = ZManaged.effectTotal(throw ExampleError)

  def countDownLatch(n: Int): UIO[URIO[Live, Unit]] =
    Ref.make(n).map { counter =>
      counter.update(_ - 1) *> {
        def await: URIO[Live, Unit] = counter.get.flatMap { n =>
          if (n <= 0) ZIO.unit
          else Live.live(ZIO.sleep(10.milliseconds)) *> await
        }
        await
      }
    }

  def doInterrupt(
    managed: IO[Nothing, Unit] => ZManaged[Any, Nothing, Unit],
    expected: Fiber.Id => Option[Exit[Nothing, Unit]]
  ) =
    for {
      fiberId            <- ZIO.fiberId
      never              <- Promise.make[Nothing, Unit]
      reachedAcquisition <- Promise.make[Nothing, Unit]
      managedFiber       <- managed(reachedAcquisition.succeed(()) *> never.await).use_(IO.unit).forkDaemon
      _                  <- reachedAcquisition.await
      interruption       <- Live.live(managedFiber.interruptAs(fiberId).timeout(5.seconds))
    } yield assert(interruption.map(_.untraced))(equalTo(expected(fiberId)))

  def makeTestManaged(ref: Ref[Int]): Managed[Nothing, Unit] =
    Managed {
      val reserve = ref.update(_ + 1)
      val acquire = ref.update(_ + 1)
      val release = ref.update(n => if (n > 0) 0 else -1)
      reserve *> ZIO.succeedNow(Reservation(acquire, _ => release))
    }

  def testFinalizersPar[R, E](
    n: Int,
    f: ZManaged[Any, Nothing, Unit] => ZManaged[R, E, Any]
  ) =
    for {
      releases <- Ref.make[Int](0)
      baseRes  = ZManaged.make(ZIO.succeedNow(()))(_ => releases.update(_ + 1))
      res      = f(baseRes)
      _        <- res.use_(ZIO.unit)
      count    <- releases.get
    } yield assert(count)(equalTo(n))

  def testAcquirePar[R, E](
    n: Int,
    f: ZManaged[Live, Nothing, Unit] => ZManaged[R, E, Any]
  ) =
    for {
      effects      <- Ref.make(0)
      countDown    <- countDownLatch(n + 1)
      reserveLatch <- Promise.make[Nothing, Unit]
      baseRes = ZManaged.reserve(
        Reservation(effects.update(_ + 1) *> countDown *> reserveLatch.await, _ => ZIO.unit)
      )
      res   = f(baseRes)
      _     <- res.use_(ZIO.unit).fork *> countDown
      count <- effects.get
      _     <- reserveLatch.succeed(())
    } yield assert(count)(equalTo(n))

  def testReservePar[R, E, A](
    n: Int,
    f: ZManaged[Live, Nothing, Unit] => ZManaged[R, E, A]
  ) =
    for {
      effects      <- Ref.make(0)
      countDown    <- countDownLatch(n + 1)
      reserveLatch <- Promise.make[Nothing, Unit]
      baseRes      = ZManaged.make(effects.update(_ + 1) *> countDown *> reserveLatch.await)(_ => ZIO.unit)
      res          = f(baseRes)
      _            <- res.use_(ZIO.unit).fork *> countDown
      count        <- effects.get
      _            <- reserveLatch.succeed(())
    } yield assert(count)(equalTo(n))
}
