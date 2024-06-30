package libretto.typology.inference

import libretto.lambda.Partitioning
import libretto.lambda.util.SourcePos
import libretto.scaletto.StarterKit.{|| as |, *}
import scala.annotation.targetName

trait Propagator[F[_], T, V] {
  type Label
  type Tp
  type TypeOutlet

  def Tp: F[Tp] -⚬ Tp = lift

  def lift: F[Tp] -⚬ Tp
  def outlet: F[TypeOutlet] -⚬ TypeOutlet
  def merge: (Tp |*| Tp) -⚬ Tp
  def split: Tp -⚬ (Tp |*| Tp)
  def tap: Tp -⚬ TypeOutlet
  def peek: TypeOutlet -⚬ (F[TypeOutlet] |+| TypeOutlet)
  def write: TypeOutlet -⚬ Val[T]
  def output: Tp -⚬ Val[T] = tap > write
  def close: Tp -⚬ Done

  def label(v: V): One -⚬ Label
  def unwrapLabel: Label -⚬ Val[V]
  def abstractTypeTap: Label -⚬ (Tp |*| Val[T])

  trait Nested {
    val propagator: Propagator[F, T, V]

    def lower: propagator.TypeOutlet -⚬ Tp
    def unnest: propagator.Tp -⚬ Tp
  }

  def nested: Nested

  // TODO: eliminate
  given junctionPositiveTp: Junction.Positive[Tp]
}

object Propagator {

  def instance[F[_], T, V](tparam: V => T)(using TypeOps[F, T, V], Ordering[V]): Propagator[F, T, V] =
    val labels = Labels[V]
    PropagatorImpl[F, T, labels.Label, V](
      labels,
      summon[TypeOps[F, T, V]],
      splitTypeParam = labels.split,
      typeParamLink = labels.split,
      outputTypeParam = labels.unwrapOriginal > mapVal(tparam),
    )
}

private[inference] object PropagatorImpl {
  def apply[F[_], T, P, V](
    labels: Labels[V],
    F: TypeOps[F, T, V],
    splitTypeParam: P -⚬ (P |*| P),
    typeParamLink: labels.Label -⚬ (P |*| P),
    outputTypeParam: P -⚬ Val[T],
  )(using
    Junction.Positive[P],
  ): PropagatorImpl[F, T, P, V, labels.type] =
    new PropagatorImpl(
      labels,
      F,
      splitTypeParam,
      typeParamLink,
      outputTypeParam,
    )
}

private[inference] class PropagatorImpl[
  F[_],
  T,
  P,
  V,
  Lbls <: Labels[V],
](
  val labels: Lbls,
  F: TypeOps[F, T, V],
  splitTypeParam: P -⚬ (P |*| P),
  typeParamLink: labels.Label -⚬ (P |*| P),
  outputTypeParam: P -⚬ Val[T],
)(using
  Junction.Positive[P],
) extends Propagator[F, T, V] { self =>

  override type Label = labels.Label

  private type TypeOutletF[X] = OneOf
    [ "TypeFormer" :: F[X]
    | "Abstract"   :: P
    ]

  override opaque type TypeOutlet = Rec[TypeOutletF]

  object Refinement {
    opaque type Request[T] = -[Response[T]]
    opaque type Response[T] = T |+| -[TypeOutlet]

    def makeRequest[T]: One -⚬ (Request[T] |*| Response[T]) =
      forevert

    extension [T](req: $[Request[T]]) {
      def grant(t: $[T])(using SourcePos, LambdaContext): $[One] =
        injectL(t) supplyTo req

      def decline(using SourcePos, LambdaContext): $[TypeOutlet] =
        die(req contramap injectR)

      @targetName("closeRefinementRequest")
      def close(using SourcePos, LambdaContext): $[Done] =
        TypeOutlet.close(req.decline)
    }

    extension [T](resp: $[Response[T]])
      def toEither: $[T |+| -[TypeOutlet]] =
        resp

      def mapWith[X, U](f: (X |*| T) -⚬ U)(x: $[X])(using SourcePos, LambdaContext)(using X: Closeable[X]): $[Response[U]] =
        given Junction.Positive[-[TypeOutlet]] = Junction.Positive.insideInversion[TypeOutlet]
        resp either {
          case Left(t)  => injectL(f(x |*| t))
          case Right(t) => injectR(t waitFor X.close(x))
        }
  }

  object AbsTp {
    type Proper[T] = Label |*| Refinement.Request[T]
    type Prelim[T] = Label |*| T
  }
  type AbsTp[T] = AbsTp.Proper[T] |+| AbsTp.Prelim[T]

  private type TpF[Tp] = OneOf
    [ "TypeFormer" :: F[Tp]
    | "TypeBroker" :: AbsTp[Tp]
    ]

  override opaque type Tp = Rec[TpF]

  private val tpPartitioning =
    recPartitioning[TpF](OneOf.partition[TpF[Tp]])

  val TypeFormer = OneOf.extractorOf(tpPartitioning)("TypeFormer")
  val TypeBroker = OneOf.extractorOf(tpPartitioning)("TypeBroker")

  private def abstType: (Label |*| Refinement.Request[Tp]) -⚬ Tp =
    injectL > TypeBroker.reinject

  private def preliminary: (Label |*| Tp) -⚬ Tp =
    injectR > TypeBroker.reinject

  override val lift: F[Tp] -⚬ Tp =
    TypeFormer.reinject

  override val outlet: F[TypeOutlet] -⚬ TypeOutlet =
    TypeOutlet.TypeFormer.reinject

  def makeAbstractType: Label -⚬ (Tp |*| Refinement.Response[Tp]) =
    λ { case +(lbl) =>
      val req |*| resp = constant(Refinement.makeRequest[Tp])
      abstType(lbl |*| req) |*| resp.mapWith(occursCheck)(lbl)
    }

  private def occursCheck: (Label |*| Tp) -⚬ Tp = rec { self =>
    λ { case lbl0 |*| t =>
      switch(t)
        .is { case TypeBroker(InL(lbl |*| req)) =>
          switch( labels.testEqual(lbl0 |*| lbl) )
            .is { case InL(l) => // forbidden label found
              val +(v) = labels.unwrapOriginal(l)
              val t1 = TypeFormer(F.forbiddenSelfReference(v))
              val t2 = TypeFormer(F.forbiddenSelfReference(v))
              returning(t1, req grant t2)
            }
            .is { case InR(lbl0 |*| lbl) =>
              abstType(lbl.waitFor(labels.neglect(lbl0)) |*| req)
            }
            .end
        }
        .is { case TypeBroker(InR(lbl |*| t)) => // preliminary
          switch( labels.testEqual(lbl0 |*| lbl) )
            .is { case InL(l) => // forbidden label found
              returning(
                TypeFormer(F.forbiddenSelfReference(labels.unwrapOriginal(l))),
                hackyDiscard(close(t)),
              )
            }
            .is { case InR(lbl0 |*| lbl) =>
              preliminary(lbl |*| self(lbl0 |*| t))
            }
            .end
        }
        .is { case TypeFormer(ft) =>
          TypeFormer(F.mapWith(self)(lbl0 |*| ft))
        }
        .end
    }
  }

  override lazy val merge: (Tp |*| Tp) -⚬ Tp =
    rec { self =>
      merge_(split_(self))
    }

  override lazy val split: Tp -⚬ (Tp |*| Tp) =
    rec { self =>
      split_(merge_(self))
    }

  private def merge_(
    split: Tp -⚬ (Tp |*| Tp),
  ): (Tp |*| Tp) -⚬ Tp = rec { self =>
    λ { case a |*| b =>
      switch(a |*| b)
        .is { case TypeBroker(a) |*| TypeBroker(b) => mergeAbstractTypes(self, split)(a |*| b) }
        .is { case TypeBroker(a) |*| TypeFormer(b) => mergeConcreteAbstract(self, split)(b |*| a) }
        .is { case TypeFormer(a) |*| TypeBroker(b) => mergeConcreteAbstract(self, split)(a |*| b) }
        .is { case TypeFormer(a) |*| TypeFormer(b) => TypeFormer(F.merge(self)(a |*| b)) }
        .end
    }
  }

  private def mergeAbstractTypes(
    merge: (Tp |*| Tp) -⚬ Tp,
    split: Tp -⚬ (Tp |*| Tp),
  ): (AbsTp[Tp] |*| AbsTp[Tp]) -⚬ Tp =
    λ { case a |*| b =>
      switch( a |*| b )
        .is { case InL(a) |*| InL(b) => mergeAbstractTypesProper(merge, split)(a |*| b) }
        .is { case InL(a) |*| InR(b) => mergeAbstractProperPreliminary(merge, split)(a |*| b) }
        .is { case InR(a) |*| InL(b) => mergeAbstractProperPreliminary(merge, split)(b |*| a) }
        .is { case InR(a) |*| InR(b) => mergePreliminaries(merge)(a |*| b) }
        .end
    }

  /** Ignores the input via a (local) deadlock. */
  private val hackyDiscard: Done -⚬ One =
    λ { d0 =>
      val n |*| d1 = constant(lInvertSignal)
      val d = join(d0 |*| d1)
      rInvertSignal(d |*| n)
    }

  private def mergeAbstractTypesProper(
    merge: (Tp |*| Tp) -⚬ Tp,
    split: Tp -⚬ (Tp |*| Tp),
  ): (AbsTp.Proper[Tp] |*| AbsTp.Proper[Tp]) -⚬ Tp =
    λ { case (aLbl |*| aReq) |*| (bLbl |*| bReq) =>
      switch( labels.compare(aLbl |*| bLbl) )
        .is { case InL(lbl) =>
          // Labels are same, i.e. both refer to the same type.
          // Propagate one (arbitrary) of them, close the other.
          returning(
            abstType(lbl |*| aReq),
            hackyDiscard(bReq.close),
          )
        }
        .is { case InR(res) =>
          def go: (AbsTp.Proper[Tp] |*| Refinement.Request[Tp]) -⚬ Tp =
            λ { case absTp |*| bReq =>
              val t1 |*| t2 = splitAbstractProper(merge, split)(absTp)
              returning(t1, bReq grant t2)
            }

          switch(res)
            .is { case InL(aLbl)  => go(aLbl |*| aReq |*| bReq) }
            .is { case InR(bLbl) => go(bLbl |*| bReq |*| aReq) }
            .end
        }
        .end
    }

  private def mergeAbstractProperPreliminary(
    merge: (Tp |*| Tp) -⚬ Tp,
    split: Tp -⚬ (Tp |*| Tp),
  ): (AbsTp.Proper[Tp] |*| AbsTp.Prelim[Tp]) -⚬ Tp =
    λ { case (aLbl |*| aReq) |*| (bLbl |*| b) =>
      val bl1 |*| bl2 = labels.split(bLbl)
      switch( labels.compare(aLbl |*| bl1) )
        .is { case InL(lbl) =>
          // Labels are equal, refer to the same type.
          // Close the refinement request, propagate the preliminary.
          returning(
            preliminary(bl2.waitFor(labels.neglect(lbl)) |*| b),
            hackyDiscard(aReq.close),
          )
        }
        .is { case InR(InL(aLbl)) =>
          // refinement request wins over preliminary,
          // but must still propagate the preliminary immediately
          preliminary(bl2 |*| merge(abstType(aLbl |*| aReq) |*| b))
        }
        .is { case InR(InR(bLbl)) =>
          // preliminary refines the refinement request
          val t1 |*| t2 = split(preliminary(bLbl |*| b))
          returning(
            t1 waitFor labels.neglect(bl2),
            aReq grant t2,
          )
        }
        .end
    }

  private def mergePreliminaries(
    merge: (Tp |*| Tp) -⚬ Tp,
  ): (AbsTp.Prelim[Tp] |*| AbsTp.Prelim[Tp]) -⚬ Tp =
    λ { case (aLbl |*| a) |*| (bLbl |*| b) =>
      switch( labels.compare(aLbl |*| bLbl) )
        .is { case InL(lbl) =>
          // labels are same
          preliminary(lbl |*| merge(a |*| b))
        }
        .is { case InR(InL(aLbl)) =>
          val al1 |*| al2 = labels.split(aLbl)
          val a1 = preliminary(al1 |*| a) // winner (`a`) must keep checking for its own label in the loser (`b`)
          preliminary(al2 |*| merge(a1 |*| b))
        }
        .is { case InR(InR(bLbl)) =>
          val bl1 |*| bl2 = labels.split(bLbl)
          val b1 = preliminary(bl1 |*| b) // winner (`b`) must keep checking for its own label in the loser (`a`)
          preliminary(bl2 |*| merge(a |*| b1))
        }
        .end
    }

  private def mergeConcreteAbstract(
    merge: (Tp |*| Tp) -⚬ Tp,
    split: Tp -⚬ (Tp |*| Tp),
  ): (F[Tp] |*| AbsTp[Tp]) -⚬ Tp =
    λ { case a |*| b =>
      switch(b)
        .is { case InL(b) => mergeConcreteAbstractProper(split)(a |*| b) }
        .is { case InR(b) => mergeConcreteAbstractPrelim(merge)(a |*| b) }
        .end
    }

  private def mergeConcreteAbstractProper(
    split: Tp -⚬ (Tp |*| Tp),
  ): (F[Tp] |*| AbsTp.Proper[Tp]) -⚬ Tp =
    λ { case t |*| (lbl |*| req) =>
      val t1 |*| t2 = split(TypeFormer(t).waitFor(labels.neglect(lbl)))
      returning(
        t1,
        req grant t2,
      )
    }

  private def mergeConcreteAbstractPrelim(
    merge: (Tp |*| Tp) -⚬ Tp,
  ): (F[Tp] |*| AbsTp.Prelim[Tp]) -⚬ Tp =
    λ { case ft |*| (lbl |*| t) =>
      preliminary(lbl |*| merge(TypeFormer(ft) |*| t))
    }

  private def split_(
    merge: (Tp |*| Tp) -⚬ Tp,
  ): Tp -⚬ (Tp |*| Tp) = rec { self =>
    λ { t =>
      switch(t)
        .is { case TypeBroker(a) =>
          splitAbstract(merge, self)(a)
        }
        .is { case TypeFormer(ft) =>
          val ft1 |*| ft2 = F.split(self)(ft)
          TypeFormer(ft1) |*| TypeFormer(ft2)
        }
        .end
    }
  }

  private def splitAbstract(
    merge: (Tp |*| Tp) -⚬ Tp,
    split: Tp -⚬ (Tp |*| Tp),
  ): AbsTp[Tp] -⚬ (Tp |*| Tp) =
    λ { a =>
      switch(a)
        .is { case InL(a) => splitAbstractProper(merge, split)(a) }
        .is { case InR(a) => splitPreliminary(split)(a) }
        .end
    }

  private def splitPreliminary(
    split: Tp -⚬ (Tp |*| Tp),
  ): AbsTp.Prelim[Tp] -⚬ (Tp |*| Tp) =
    λ { case lbl |*| t =>
      val l1 |*| l2 = labels.split(lbl)
      val t1 |*| t2 = split(t)
      preliminary(l1 |*| t1) |*| preliminary(l2 |*| t2)
    }

  private def splitAbstractProper(
    merge: (Tp |*| Tp) -⚬ Tp,
    split: Tp -⚬ (Tp |*| Tp),
  ): AbsTp.Proper[Tp] -⚬ (Tp |*| Tp) =
    λ { case lbl |*| req =>
      val l1 |*| l2 = labels.split(lbl)
      val t1 |*| resp1 = makeAbstractType(l1)
      val t2 |*| resp2 = makeAbstractType(l2)
      returning(
        t1 |*| t2,
        resp1.toEither either {
          case Left(t1) =>
            resp2.toEither either {
              case Left(t2) =>
                req grant merge(t1 |*| t2)
              case Right(req2) =>
                val t11 |*| t12 = split(t1)
                returning(
                  req grant t11,
                  tap(t12) supplyTo req2,
                )
            }
          case Right(req1) =>
            resp2.toEither either {
              case Left(t2) =>
                val t21 |*| t22 = split(t2)
                returning(
                  req grant t21,
                  tap(t22) supplyTo req1,
                )
              case Right(req2) =>
                val t1 |*| t2 = TypeOutlet.split(req.decline)
                returning(
                  t1 supplyTo req1,
                  t2 supplyTo req2,
                )
            }
        },
      )
    }

  private def awaitPosFst: (Done |*| Tp) -⚬ Tp =
    rec { self =>
      λ { case d |*| t =>
        switch(t)
          .is { case TypeBroker(InL(lbl |*| req)) => abstType(lbl.waitFor(d) |*| req) }
          .is { case TypeBroker(InR(lbl |*| t))   => preliminary(lbl |*| self(d |*| t)) }
          .is { case TypeFormer(ft)               => TypeFormer(F.awaitPosFst(self)(d |*| ft)) }
          .end
      }
    }

  override given junctionPositiveTp: Junction.Positive[Tp] =
    Junction.Positive.from(awaitPosFst)

  override lazy val abstractTypeTap: Label -⚬ (Tp |*| Val[T]) =
    λ { lbl =>
      val l1 |*| l2 = labels.split(lbl)
      val res |*| resp = makeAbstractType(l1)
        res |*| (resp.toEither either {
          case Left(t) =>
            output(t) waitFor labels.neglect(l2)
          case Right(req) =>
            val p1 |*| p2 = typeParamLink(l2)
            val t = outputTypeParam(p1)
            returning(t, TypeOutlet.Abstract(p2) supplyTo req)
        })
    }

  private def abstractLink: Label -⚬ (Tp |*| Tp) =
    λ { lbl =>
      // val lbl1 = labels.alsoDebugPrint(s => s"Creating link for $s")(lbl)
      val l1 |*| l2 = labels.split(lbl)
      val l3 |*| l4 = labels.split(l2)
      val t1 |*| resp = makeAbstractType(l1)
      val nt2 |*| t2 = curry(preliminary)(l3)
      returning(
        t1 |*| t2,
        resp.toEither either {
          case Left(t) =>
            // TODO: occurs check for `lbl` in `t`
            val l4_ = l4 //:>> labels.alsoDebugPrint(s => s"Link-req of $s returned as REFINED")
            t.waitFor(labels.neglect(l4_)) supplyTo nt2
          case Right(req1) =>
            val l4_ = l4 //:>> labels.alsoDebugPrint(s => s"Link-req of $s returned as DECLINED. Sending opposite request.")
            val l5 |*| l6 = labels.split(l4_)
            val t2 |*| resp = makeAbstractType(l5)
            returning(
              resp.toEither either {
                case Left(t) =>
                  // TODO: occurs check for `lbl` in `t`
                  val l6_ = l6 //:>> labels.alsoDebugPrint(s => s"Op-req of $s returned as REFINED")
                  tap(t waitFor labels.neglect(l6_)) supplyTo req1
                case Right(req2) =>
                  val l6_ = l6 //:>> labels.alsoDebugPrint(s => s"Op-req of $s returned as DECLINED")
                  val p1 |*| p2 = typeParamLink(l6_)
                  returning(
                    TypeOutlet.Abstract(p1) supplyTo req1,
                    TypeOutlet.Abstract(p2) supplyTo req2,
                  )
              },
              t2 supplyTo nt2,
            )
        },
      )
    }

  override val close: Tp -⚬ Done = rec { self =>
    λ { t =>
      switch(t)
        .is { case TypeBroker(InL(lbl |*| req)) =>
          joinAll(TypeOutlet.close(req.decline), labels.neglect(lbl))
        }
        .is { case TypeBroker(InR(lbl |*| t)) => join(labels.neglect(lbl) |*| self(t)) }
        .is { case TypeFormer(ft)             => F.close(self)(ft) }
        .end
    }
  }

  override def label(v: V): One -⚬ Label =
    labels.create(v)

  override def unwrapLabel: Label -⚬ Val[V] =
    labels.unwrapOriginal

  override lazy val nested: Nested = {
    val nl = labels.nested

    type NLabel  = nl.labels.Label

    type Q = NLabel |*| (-[Tp] |+| Tp)

    val splitQ: Q -⚬ (Q |*| Q) =
      λ { case lbl |*| q =>
        val l1 |*| l2 = nl.labels.split(lbl)
        val q1 |*| q2 = switch(q)
          .is { case InL(nt) =>
            val nt1 |*| nt2 = contrapositive(self.merge)(nt) :>> distributeInversion
            injectL(nt1) |*| injectL(nt2)
          }
          .is { case InR(t) =>
            val t1 |*| t2 = self.split(t)
            injectR(t1) |*| injectR(t2)
          }
          .end
        (l1 |*| q1) |*| (l2 |*| q2)
      }

    val qLink: NLabel -⚬ (Q |*| Q) =
      λ { lbl =>
        val ntp |*| tp = constant(demand[Tp])
        val l1 |*| l2 = nl.labels.split(lbl)
        (l1 |*| injectL(ntp)) |*| (l2 |*| injectR(tp))
      }

    val outputQ: Q -⚬ Val[T] =
      λ { case lbl |*| q =>
        switch(q)
          .is { case InL(nt) =>
            val t |*| t0 = abstractTypeTap(nl.lower(lbl))
            returning(t0, t supplyTo nt)
          }
          .is { case InR(t) =>
            self.output(t)
              .waitFor(nl.labels.neglect(lbl))
          }
          .end
      }

    new Nested {
      override val propagator: PropagatorImpl[F, T, Q, V, nl.labels.type] =
        PropagatorImpl[F, T, Q, V](
          nl.labels,
          F,
          splitQ,
          qLink,
          outputQ,
        )(using
          Junction.Positive.byFst,
        )

      override val lower: propagator.TypeOutlet -⚬ Tp = rec { self =>
        λ { t =>
          switch(t)
            .is { case propagator.TypeOutlet.Abstract(lbl |*| InL(nt)) =>
              val t1 |*| t2 = abstractLink(nl.lower(lbl))
              returning(
                t1,
                t2 supplyTo nt,
              )
            }
            .is { case propagator.TypeOutlet.Abstract(lbl |*| InR(t)) =>
              t waitFor nl.labels.neglect(lbl)
            }
            .is { case propagator.TypeOutlet.TypeFormer(ft) =>
              TypeFormer(F.map(self)(ft))
            }
            .end
        }
      }

      override def unnest: propagator.Tp -⚬ Tp =
        propagator.tap > lower
    }
  }

  override val tap: Tp -⚬ TypeOutlet = rec { self =>
    λ { t =>
      switch(t)
        .is { case TypeBroker(InL(lbl |*| req)) => req.decline waitFor labels.neglect(lbl) }
        .is { case TypeBroker(InR(lbl |*| t))   => self(t waitFor labels.neglect(lbl)) }
        .is { case TypeFormer(ft)               => TypeOutlet.TypeFormer(F.map(self)(ft)) }
        .end
    }
  }

  override val peek: TypeOutlet -⚬ (F[TypeOutlet] |+| TypeOutlet) =
    λ { t =>
      switch(t)
        .is { case TypeOutlet.Abstract(p)    => injectR(TypeOutlet.Abstract(p)) }
        .is { case TypeOutlet.TypeFormer(ft) => injectL(ft) }
        .end
    }

  override val write: TypeOutlet -⚬ Val[T] = rec { self =>
    λ { switch(_)
      .is { case TypeOutlet.Abstract(p) => outputTypeParam(p) }
      .is { case TypeOutlet.TypeFormer(t) => F.output(self)(t) }
      .end
    }
  }

  object TypeOutlet {
    private val partitioning =
      recPartitioning[TypeOutletF](OneOf.partition[TypeOutletF[TypeOutlet]])

    val TypeFormer = OneOf.extractorOf(partitioning)("TypeFormer")
    val Abstract   = OneOf.extractorOf(partitioning)("Abstract")

    val split: TypeOutlet -⚬ (TypeOutlet |*| TypeOutlet) =
      rec { self =>
        λ { t =>
          switch(t)
            .is { case Abstract(p) =>
              val p1 |*| p2 = splitTypeParam(p)
              Abstract(p1) |*| Abstract(p2)
            }
            .is { case TypeFormer(ft) =>
              val ft1 |*| ft2 = F.split(self)(ft)
              TypeFormer(ft1) |*| TypeFormer(ft2)
            }
            .end
        }
      }

    val close: TypeOutlet -⚬ Done =
      rec { self =>
        λ { switch(_)
          .is { case Abstract(p)   => neglect(outputTypeParam(p)) }
          .is { case TypeFormer(t) => F.close(self)(t) }
          .end
        }
      }

    val awaitPosFst: (Done |*| TypeOutlet) -⚬ TypeOutlet = rec { self =>
      λ { case d |*| t =>
        switch(t)
          .is { case Abstract(p)    => Abstract(p waitFor d) }
          .is { case TypeFormer(ft) => TypeFormer(F.awaitPosFst(self)(d |*| ft)) }
          .end
      }
    }
  }

  private given Junction.Positive[TypeOutlet] =
    Junction.Positive.from(TypeOutlet.awaitPosFst)
}
