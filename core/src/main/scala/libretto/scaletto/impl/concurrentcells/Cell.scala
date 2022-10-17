package libretto.scaletto.impl.concurrentcells

import java.util.concurrent.atomic.AtomicReference
import libretto.lambda.UnhandledCase
import libretto.scaletto.impl.FreeScaletto._
import libretto.util.Async
import libretto.util.atomic._
import scala.annotation.tailrec

opaque type Cell[A] <: AnyRef = AtomicReference[CellContent[A]]

object Cell {
  import CellContent.ActionResult
  import ActionResult.{ChooseL, ChooseR}

  def apply[A](content: CellContent[A]): Cell[A] =
    new AtomicReference(content)

  def empty[A]: Cell[A] =
    Cell(CellContent.Empty())

  def one: Cell[One] =
    empty[One]

  def lsplit[A, B](cell: Cell[A |*| B]): (Cell[A], Cell[B], ActionResult) =
    cell.modifyContentWith(cell, CellContent.lsplit)

  def rsplit[A, B](cell: Cell[A |*| B]): (Cell[A], Cell[B], ActionResult) =
    cell.modifyContentWith(cell, CellContent.rsplit)

  def unify[A](l: Cell[A], r: Cell[A]): ActionResult =
    r.modifyContentWith((l, r), CellContent.unifyInit)

  def rInvertSignal(d: Cell[Done], n: Cell[Need]): ActionResult =
    rInvert(d, Inversion.DoneNeed, n)

  private def rInvert[A, B](src: Cell[A], i: Inversion[A, B], tgt: Cell[B]): ActionResult =
    tgt.modifyContentWith((src, i, tgt), CellContent.rInvertInit)

  def lInvertSignal(n: Cell[Need], d: Cell[Done]): ActionResult =
    lInvert(n, Inversion.DoneNeed, d)

  private def lInvert[A, B](src: Cell[B], i: Inversion[A, B], tgt: Cell[A]): ActionResult =
    tgt.modifyContentWith((src, i, tgt), CellContent.lInvertInit)

  def injectL[A, B](src: Cell[A], tgt: Cell[A |+| B]): ActionResult =
    tgt.modifyContentWith(src, CellContent.injectL)

  def injectR[A, B](src: Cell[B], tgt: Cell[A |+| B]): ActionResult =
    tgt.modifyContentWith((src, tgt), CellContent.injectR)

  def either[A, B, C](src: Cell[A |+| B], f: A -⚬ C, g: B -⚬ C, tgt: Cell[C]): ActionResult =
    src.modifyContentWith(CellContent.EitherSwitch(f, g, tgt), CellContent.eitherSwitch)

  def chooseL[A, B](choiceCell: Cell[A |&| B], resultCell: Cell[A]): ActionResult =
    ChooseL(choiceCell, resultCell).execute()

  def chooseR[A, B](choiceCell: Cell[A |&| B], resultCell: Cell[B]): ActionResult =
    ChooseR(choiceCell, resultCell).execute()

  def choice[A, B, C](tgt: Cell[A], f: A -⚬ B, g: A -⚬ C, src: Cell[B |&| C]): ActionResult =
    src.modifyContentWith(CellContent.ChooseFrom(tgt, f, g), CellContent.chooseFrom)

  def choiceWith[A, B, C](tgt: Cell[(A |*| B) |&| (A |*| C)], addendum: Cell[A], src: Cell[B |&| C]): ActionResult =
    src.modifyContentWith(CellContent.ChoiceWith(tgt, addendum), CellContent.choiceWith)

  def join(src1: Cell[Done], src2: Cell[Done], tgt: Cell[Done]): ActionResult =
    tgt.modifyContentWith((tgt, CellContent.JoinOf(src1, src2)), CellContent.makeAJoinOf)

  def joinPong(tgt: Cell[Pong], src1: Cell[Pong], src2: Cell[Pong]): ActionResult =
    tgt.modifyContentWith((tgt, CellContent.JoinPongOf(src1, src2)), CellContent.makeAJoinPongOf)

  def supplyDone(tgt: Cell[Done]): ActionResult =
    tgt.modifyContentWith(tgt, CellContent.supplyDone)

  def supplyPong(tgt: Cell[Pong]): ActionResult =
    tgt.modifyContentWith(tgt, CellContent.supplyPong)

  def select(choiceCell: Cell[One |&| One], contestant1: Cell[Pong], contestant2: Cell[Pong]): ActionResult =
    choiceCell.modifyContentWith(
      (choiceCell, CellContent.SelectOf(contestant1, contestant2)),
      CellContent.makeASelectOf,
    )

  def notifyEither[A, B](src: Cell[A |+| B], pingCell: Cell[Ping], out: Cell[A |+| B]): ActionResult =
    src.modifyContentWith((src, CellContent.RNotifyEither(pingCell, out)), CellContent.notifyEither)

  def notifyNeed(pongCell: Cell[Pong], tgt: Cell[Need], src: Cell[Need]): ActionResult =
    src.modifyContentWith((CellContent.LNotifyNeed(pongCell, tgt), src), CellContent.notifyNeed)

  def notifyChoice[A, B](pongCell: Cell[Pong], tgt: Cell[A |&| B], src: Cell[A |&| B]): ActionResult =
    src.modifyContentWith((CellContent.LNotifyChoice(pongCell, tgt), src), CellContent.notifyChoice)

  def awaitDone(
    src: Cell[Done],
    listener: Either[Throwable, Unit] => Unit,
  ): ActionResult =
    src.modifyContentWith((src, listener), CellContent.awaitDone)

  def awaitEither[A, B](
    src: Cell[A |+| B],
    listener: Either[Throwable, Either[Cell[A], Cell[B]]] => Unit,
  ): ActionResult =
    src.modifyContentWith((src, listener), CellContent.awaitEither)

  extension [A](cell: Cell[A]) {
    // def modifyContent[R](f: CellContent[A] => (CellContent[A], R)): R =
    //   cell.modifyOpaque(f)

    def modifyContentWith[B, R](b: B, f: (CellContent[A], B) => (CellContent[A], R)): R =
      cell.modifyOpaqueWith(b, f)

    def modifyContentOptWith[B, R](b: B, f: (CellContent[A], B) => (Option[CellContent[A]], R)) : R =
      cell.modifyOpaqueOptWith(b, f)
  }
}

sealed trait CellContent[A] {
  import CellContent._

  final override def toString(): String = {
    def cellOrLBypassing[A](a: Cell[A] | LBypassing[A]): String =
      a match
        case c: Cell[A]       => addr(c)
        case b: LBypassing[A] => b.toString

    this match {
      case Empty()                 => "Empty()"
      case LFwd(l)                 => s"LFwd(${addr(l)})"
      case RFwd(r)                 => s"RFwd(${addr(r)})"
      case LRole(tgt, role)        => s"LRole(${addr(tgt)}, $role)"
      case RRole(role, tgt)        => s"RRole($role, ${addr(tgt)})"
      case SelectOf(c1, c2)        => s"SelectOf(${addr(c1)}, ${addr(c2)})"
      case JoinOf(c1, c2)          => s"JoinOf(${addr(c1)}, ${addr(c2)})"
      case JoinPongOf(p1, p2)      => s"JoinPongOf(${addr(p1)}, ${addr(p2)})"
      case JoinPong1(p1)           => s"JoinPong1(${addr(p1)})"
      case JoinPong2(p2)           => s"JoinPong2(${addr(p2)})"
      case BiDef(l, r)             => s"BiDef($l, $r)"
      case Slated(l, r)            => s"Slated($l, $r)"
      case DoneSupplied            => "DoneSupplied"
      case PongSupplied            => "PongSupplied"
      case Consumed()              => "Consumed()"
      case LSplit(c1, c2)          => s"LSplit(${addr(c1)}, ${addr(c2)})"
      case RSplit(c1, c2)          => s"RSplit(${addr(c1)}, ${addr(c2)})"
      case ChooseFrom(c, f, g)     => s"ChooseFrom(${addr(c)}, $f, $g)"
      case ChoiceWith(c, a)        => s"ChoiceWith(${addr(c)}, ${addr(a)})"
      case ChosenL(c)              => s"ChosenL(${addr(c)})"
      case ChosenR(c)              => s"ChosenR(${addr(c)})"
      case InjectedL(c)            => s"InjctedL(${addr(c)})"
      case InjectedR(c)            => s"InjctedR(${addr(c)})"
      case EitherCallback(f)       => s"EitherCallback($f)"
      case DoneCallback(f)         => s"DoneCallback($f)"
      case LBypassing(x, y)        => s"LBypassing(${addr(x)}, ${addr(y)})"
      case LBypassingTo(x, l, y)   => s"LBypassingTo(${addr(x)}, $l, ${addr(y)})"
      case LSkippingUp(s, i, l)    => s"LSkippingUp(${addr(s)}, $i, ${addr(l)})"
      case LSkippingDown(t, i, l)  => s"LSkippingDown(${addr(t)}, $i, ${addr(l)})"
      case RInvertSrc(i, c)        => s"RInvertSrc($i, ${addr(c)})"
      case RInvertTgt(c, i)        => s"RInvertTgt(${addr(c)}, $i)"
      case LInvertSrc(i, c)        => s"LInvertSrc($i, ${addr(c)})"
      case LInvertTgt(c, i)        => s"LInvertTgt(${addr(c)}, $i)"
      case LInvertTgtClaimed(c, i) => s"LInvertTgtClaimed(${addr(c)}, $i)"
      case RInvertSrcBypassingTgt(i, x, y) => s"RInvertSrcBypassingTgt($i, ${addr(x)}, ${addr(y)})"
      case RInvertTgtBypassingSrc(x, y, i) => s"RInvertTgtBypassingSrc(${addr(x)}, ${addr(y)}, $i)"
      case l: LNotifyChoice[x, y] =>
        val ns = cellOrLBypassing(l.notification)
        val ts = cellOrLBypassing(l.tgt)
        s"LNotifyChoice($ns, $ts)"
      case l: LNotifyNeed =>
        val ns = cellOrLBypassing(l.notification)
        val ts = cellOrLBypassing(l.tgt)
        s"LNotifyNeed($ns, $ts)"
    }
  }
}
object CellContent {
  case class Empty[A]() extends CellContent[A]
  sealed trait LDefined[A] extends CellContent[A]
  sealed trait RDefined[A] extends CellContent[A]

  /** Cell content with left and right part, such that the parts form a stable conformation. */
  case class BiDef[A](l: LDefined[A], r: RDefined[A]) extends CellContent[A]

  /** Cell content with left and right part, where the collocation of these parts has triggered a reduction.
   *  The cell with this content will soon be consumed, unless the scheduled reduction is obstructed
   *  by a higher-priority activity (i.e. activity originating on the right of this cell).
   */
  case class Slated[A](l: LDefined[A], r: RDefined[A]) extends CellContent[A]

  case class Consumed[A]() extends CellContent[A]

  sealed trait LComplete[A] extends LDefined[A] {
    def rInvert[B](i: Inversion[A, B]): RComplete[B] =
      i match
        case Inversion.DoneNeed =>
          this match
            case DoneSupplied => NeedSupplied
        case Inversion.PingPong =>
          this match
            case PingSupplied => PongSupplied
        case Inversion.Universal() =>
          RCompleteInv(this)

    def rUnInvert[B](i: Inversion[B, A]): RComplete[B] =
      i match
        case Inversion.DoneNeed =>
          this match
            case NeedCallback(f) => DoneCallback(f)
        case Inversion.PingPong =>
          this match
            case DoneSupplied => throw new AssertionError("Impossible: Done =!= Pong")
        case Inversion.Universal() =>
          this match
            case LCompleteInv(src) => src
  }

  sealed trait RComplete[A] extends RDefined[A] {
    def lInvert[B](i: Inversion[A, B]): LComplete[B] =
      i match
        case Inversion.DoneNeed =>
          this match
            case DoneCallback(f) => NeedCallback(f)
        case Inversion.PingPong =>
          this match
            case DoneCallback(f) => throw new AssertionError("Impossible: Done =!= Ping")
        case Inversion.Universal() =>
          LCompleteInv(this)


    def lUnInvert[B](i: Inversion[B, A]): LComplete[B] =
      i match
        case Inversion.DoneNeed =>
          this match
            case NeedSupplied => DoneSupplied
        case Inversion.PingPong =>
          this match
            case PongSupplied => PingSupplied
        case Inversion.Universal() =>
          LCompleteUnInv(this)
  }

  case class LCompleteInv[A](src: RComplete[A]) extends LComplete[-[A]]
  case class LCompleteUnInv[A](src: RComplete[-[A]]) extends LComplete[A]
  case class RCompleteInv[A](src: LComplete[A]) extends RComplete[-[A]]

  case class LFwd[A](tgt: Cell[A]) extends LDefined[A]
  case class RFwd[A](tgt: Cell[A]) extends RDefined[A]

  case class RInvertSrc[A, B](inv: Inversion[A, B], tgt: Cell[B]) extends RDefined[A]
  case class RInvertTgt[A, B](src: Cell[A], inv: Inversion[A, B]) extends RDefined[B]

  case class LInvertSrc[A, B](inv: Inversion[B, A], tgt: Cell[B]) extends LDefined[A]
  case class LInvertTgt[A, B](src: Cell[A], inv: Inversion[B, A]) extends LDefined[B]

  /** Claimed by an action coming from the left (i.e. from the corresponding [[LInvertSrc]]).
   *  Prevents the cell from being slated and removed by an action originating from the right.
   *  An action from the left has to eventually release the claim (even if the original action is obstructed).
   */
  case class LInvertTgtClaimed[A, B](src: Cell[A], inv: Inversion[B, A]) extends LDefined[B]

  // case class BypassingL[A, B, C](
  //   newTgt: Cell[A],
  //   newLink: Link[A, C],
  //   oldTgt: Cell[B],
  //   oldLink: Link[B, C],
  // ) extends LDefined[C]
  case class LBypassing[A](newTgt: Cell[A], oldTgt: Cell[A]) extends LDefined[A]
  case class LBypassingTo[A, B](newTgt: Cell[A], newRole: RoleL[A, B], oldTgt: Cell[B]) extends LDefined[B]
  case class LSkippingDown[A, B](invTgt: Cell[A], lInv: Inversion[A, B], oldTgt: Cell[B]) extends LDefined[B]
  case class LSkippingUp[A, B](invSrc: Cell[A], lInv: Inversion[B, A], oldTgt: Cell[B]) extends LDefined[B]

  case class RInvertSrcBypassingTgt[A, B](rInv: Inversion[A, B], oldTgt: Cell[B], newTgt: Cell[B]) extends RDefined[A]
  case class RInvertTgtBypassingSrc[A, B](newTgt: Cell[A], oldTgt: Cell[A], rInv: Inversion[A, B]) extends RDefined[B]

  case class LRole[A, B](tgt: Cell[A], role: RoleL[A, B]) extends LDefined[B]
  case class RRole[A, B](role: RoleR[A, B], tgt: Cell[B]) extends RDefined[A]

  case object DoneSupplied extends LComplete[Done]
  case class DoneCallback(f: Either[Throwable, Unit] => Unit) extends RComplete[Done]

  case object NeedSupplied extends RComplete[Need]
  case class NeedCallback(f: Either[Throwable, Unit] => Unit) extends LComplete[Need]

  case object PingSupplied extends LComplete[Ping]
  case object PongSupplied extends RComplete[Pong]

  case class LSplit[A1, A2](cell1: Cell[A1], cell2: Cell[A2]) extends LDefined[A1 |*| A2]
  case class RSplit[A1, A2](cell1: Cell[A1], cell2: Cell[A2]) extends RDefined[A1 |*| A2]

  case class InjectedL[A, B](value: Cell[A]) extends LComplete[A |+| B]
  case class InjectedR[A, B](value: Cell[B]) extends LComplete[A |+| B]
  case class EitherSwitch[A, B, C](f: A -⚬ C, g: B -⚬ C, tgt: Cell[C]) extends RDefined[A |+| B]
  case class EitherCallback[A, B](f: Either[Throwable, Either[Cell[A], Cell[B]]] => Unit) extends RComplete[A |+| B]

  case class ChosenL[A, B](resultCell: Cell[A]) extends RComplete[A |&| B]
  case class ChosenR[A, B](resultCell: Cell[B]) extends RComplete[A |&| B]
  case class ChooseFrom[A, B, C](tgt: Cell[A], f: A -⚬ B, g: A -⚬ C) extends LDefined[B |&| C]
  case class ChoiceWith[A, B, C](tgt: Cell[(A |*| B) |&| (A |*| C)], addendum: Cell[A]) extends LDefined[B |&| C]

  case class JoinOf(src1: Cell[Done], src2: Cell[Done]) extends LDefined[Done]
  case class JoinPongOf(src1: Cell[Pong], src2: Cell[Pong]) extends RDefined[Pong]
  case class JoinPong1(src1: Cell[Pong]) extends RDefined[Pong] // after Pong from src2 has arrived
  case class JoinPong2(src2: Cell[Pong]) extends RDefined[Pong] // after Pong from src1 has arrived

  case class SelectOf(contestant1: Cell[Pong], contestant2: Cell[Pong]) extends RDefined[One |&| One]

  case class RNotifyEither[A, B](notificationCell: Cell[Ping], outCell: Cell[A |+| B]) extends RDefined[A |+| B]
  case class LNotifyChoice[A, B](
    notification: Cell[Pong], // | LBypassing[Pong],
    tgt: Cell[A |&| B], // | LBypassing[A |&| B],
  ) extends LDefined[A |&| B]
  // {
  //   def notificationCell: Cell[Pong] =
  //     notification match
  //       case c: Cell[Pong]       => c
  //       case l: LBypassing[Pong] => l.newTgt

  //   def tgtCell: Cell[A |&| B] =
  //     tgt match
  //       case c: Cell[A |&| B]       => c
  //       case l: LBypassing[A |&| B] => l.newTgt
  // }

  case class LNotifyNeed(
    notification: Cell[Pong],// | LBypassing[Pong],
    tgt: Cell[Need],// | LBypassing[Need],
  ) extends LDefined[Need]
  // {
  //   def notificationCell: Cell[Pong] =
  //     notification match
  //       case c: Cell[Pong]       => c
  //       // case l: LBypassing[Pong] => l.newTgt

  //   def tgtCell: Cell[Need] =
  //     tgt match
  //       case c: Cell[Need]       => c
  //       // case l: LBypassing[Need] => l.newTgt
  // }

  sealed trait ActionResult
  object ActionResult {
    case object AllDone extends ActionResult

    sealed trait FollowUpAction extends ActionResult {
      def and(that: FollowUpAction): FollowUpAction =
        Two(this, that)
    }

    case class Two(_1: FollowUpAction, _2: FollowUpAction) extends FollowUpAction

    case class UnificationRequest[X](l: Cell[X], r: Cell[X]) extends FollowUpAction
    case class ConnectionRequest[X, Y](l: Cell[X], f: X -⚬ Y, r: Cell[Y]) extends FollowUpAction

    case class CallbackTriggered[X](f: X => Unit, x: X) extends FollowUpAction

    sealed trait Instruction extends FollowUpAction {
      def execute(): ActionResult
    }

    /** An action to refine `src` by pointing it at `receiver` to its right.
      *
      * @param src must be yet unconnected to the right
      * @param role the role `src` plays in `receiver`
      * @param receiver must already know that it will be fed from `src`
      */
    case class DirectToR[A, B](src: Cell[A], role: RoleR[A, B], receiver: Cell[B]) extends Instruction {
      override def execute(): ActionResult =
        src.modifyContentOptWith((src, RRole(role, receiver)), CellContent.directToR)
    }

    /** An action to refine `src` by pointing it at `receiver` to its left.
      *
      * @param src must be yet unconnected to the left
      * @param role the role `src` plays in `receiver`
      * @param receiver must already know that it will be fed from `src`
      */
    case class DirectToL[A, B](receiver: Cell[A], role: RoleL[A, B], src: Cell[B]) extends Instruction {
      override def execute(): ActionResult =
        src.modifyContentWith((LRole(receiver, role), src), CellContent.directToL)
    }

    case class PropagateLSplit[A, B](from: Cell[A |*| B], payload: LSplit[A, B], to: Cell[A |*| B]) extends Instruction {
      override def execute(): ActionResult =
        to.modifyContentOptWith(this, CellContent.propagateLSplit)
    }

    case class PropagateRSplit[A, B](to: Cell[A |*| B], payload: RSplit[A, B], from: Cell[A |*| B]) extends Instruction {
      override def execute(): ActionResult =
        to.modifyContentOptWith(this, CellContent.propagateRSplit)
    }

    case class LSplitPropagated[A, B](from: Cell[A |*| B], payload: LSplit[A, B], to: Cell[A |*| B]) extends Instruction {
      override def execute(): ActionResult =
        from.modifyContentOptWith(this, CellContent.lsplitPropagated)
    }

    case class ChooseL[A, B](lCell: Cell[A |&| B], rCell: Cell[A]) extends Instruction {
      override def execute(): ActionResult =
        lCell.modifyContentWith(this, CellContent.chooseL)
    }

    case class ChooseR[A, B](lCell: Cell[A |&| B], rCell: Cell[B]) extends Instruction {
      override def execute(): ActionResult =
        lCell.modifyContentWith(this, CellContent.chooseR)
    }

    case class RefineLFwd[A](cell: Cell[A], expectedLTarget: Cell[A], payload: LComplete[A]) extends Instruction {
      override def execute(): ActionResult =
        cell.modifyContentWith(this, CellContent.refineLFwd)
    }

    case class RefineLRole[A, B](expectedLTarget: Cell[A], lRole: RoleL[A, B], cell: Cell[B], payload: LComplete[B]) extends Instruction {
      override def execute(): ActionResult = ???
    }

    // case class RefineRLink[A, B](
    //   cell: Cell[A],
    //   link: Link[A, B],
    //   expectedRTarget: Cell[B],
    //   payload: RComplete[B],
    // ) extends Instruction {
    //   override def execute(): ActionResult =
    //     cell.modifyContentWith(this, CellContent.refineRLink)
    // }

    case class RefineRFwd[A](cell: Cell[A], expectedRTarget: Cell[A], payload: RComplete[A]) extends Instruction {
      override def execute(): ActionResult =
        cell.modifyContentWith(this, CellContent.refineRFwd)
    }

    case class RefineRRole[A, B](cell: Cell[A], rRole: RoleR[A, B], expectedRTarget: Cell[B], payload: RComplete[A]) extends Instruction {
      override def execute(): ActionResult =
        cell.modifyContentWith(this, CellContent.refineRRole)
    }

    case class RefineRPart[A, B](cell: Cell[A], lRole: RoleL[A, B], expectedRTarget: Cell[B], payload: RComplete[B]) extends Instruction {
      override def execute(): ActionResult =
        cell.modifyContentOptWith(this, CellContent.refineRPart)
    }

    case class RefineRInvertSrc[A, B](invSrc: Cell[A], rInv: Inversion[A, B], expectedInvTgt: Cell[B], payload: RComplete[A]) extends Instruction {
      override def execute(): ActionResult =
        invSrc.modifyContentOptWith(this, CellContent.refineRInvertSrc)
    }

    case class RefineRInvertTgt[A, B](expectedInvSrc: Cell[A], rInv: Inversion[A, B], invTgt: Cell[B], payload: RComplete[B]) extends Instruction {
      override def execute(): ActionResult =
        invTgt.modifyContentOptWith(this, CellContent.refineRInvertTgt)
    }

    case class RefineLInvertSrc[A, B](expectedInvTgt: Cell[B], lInv: Inversion[B, A], invSrc: Cell[A], payload: LComplete[A]) extends Instruction {
      override def execute(): ActionResult =
        invSrc.modifyContentOptWith(this, CellContent.refineLInvertSrc)
    }

    case class RefineLInvertTgt[A, B](expectedInvSrc: Cell[A], lInv: Inversion[B, A], invTgt: Cell[B], payload: LComplete[B]) extends Instruction {
      override def execute(): ActionResult =
        invTgt.modifyContentOptWith(this, CellContent.refineLInvertTgt)
    }

    case class RReciprocateForward[A](src: Cell[A], tgt: Cell[A]) extends Instruction {
      override def execute(): ActionResult =
        src.modifyContentOptWith((src, tgt), CellContent.rReciprocateFwd)
    }

    case class RReciprocateRInvert[A, B](src: Cell[A], inv: Inversion[A, B], tgt: Cell[B]) extends Instruction {
      override def execute(): ActionResult =
        src.modifyContentOptWith(this, CellContent.rReciprocateRInvert)
    }

    case class LReciprocateLInvert[A, B](src: Cell[A], inv: Inversion[B, A], tgt: Cell[B]) extends Instruction {
      override def execute(): ActionResult =
        src.modifyContentOptWith(this, CellContent.lReciprocateLInvert)
    }

    // case class ContractCell[A, B, C](
    //   l: Cell[A],
    //   lLink: Link[A, B],
    //   slated: Cell[B],
    //   rLink: Link[B, C],
    //   r: Cell[C],
    //   newLink: Link[A, C],
    // ) extends Instruction {
    //   override def execute(): ActionResult =
    //     r.modifyContentOptWith(this, CellContent.initContraction)
    // }

    case class ContractBiFwd[A](l: Cell[A], slated: Cell[A], r: Cell[A]) extends Instruction {
      override def execute(): ActionResult =
        r.modifyContentOptWith((l, slated, r), CellContent.initLBypass)
    }

    // case class ContractLFwd[A, B](l: Cell[A], slated: Cell[A], rRel: RoleR[A, B], r: Cell[B]) extends Instruction {
    //   override def execute(): ActionResult =
    //     r.modifyContentOptWith(this, CellContent.initContractLFwd)
    // }

    // case class ContractRFwd[A, B](l: Cell[A], lRel: RoleL[A, B], slated: Cell[B], r: Cell[B]) extends Instruction {
    //   override def execute(): ActionResult =
    //     r.modifyContentOptWith(this, CellContent.initContractRFwd)
    // }

    case class ContractLFwdRInvTgt[A, B](lCell: Cell[A], slated: Cell[A], rInv: Inversion[B, A], src: Cell[B]) extends Instruction {
      override def execute(): ActionResult =
        src.modifyContentOptWith(this, CellContent.initLFwdRInvTgtContraction)
    }

    case class ContractLFwdRInvSrc[A, B](lCell: Cell[A], slated: Cell[A], rInv: Inversion[A, B], tgt: Cell[B]) extends Instruction {
      override def execute(): ActionResult =
        tgt.modifyContentOptWith(this, CellContent.initLFwdRInvSrcContraction)
    }

    case class ContractLInvSrcRFwd[A, B](tgt: Cell[A], lInv: Inversion[A, B], slated: Cell[B], rCell: Cell[B]) extends Instruction {
      override def execute(): ActionResult =
        tgt.modifyContentOptWith(this, CellContent.initContractionLInvSrcRFwdClaimTgt)
    }

    case class ContractionLInvSrcRFwdClaimedTgt[A, B](contraction: ContractLInvSrcRFwd[A, B]) extends Instruction {
      override def execute(): ActionResult =
        contraction.rCell.modifyContentOptWith(contraction, CellContent.initContractionLInvSrcRFwdAfterTgtClaimed)
    }

    case class ContractLInvTgtRFwd[A, B](src: Cell[A], lInv: Inversion[B, A], slated: Cell[B], rCell: Cell[B]) extends Instruction {
      override def execute(): ActionResult =
        rCell.modifyContentOptWith(this, CellContent.initContractionLInvTgtRFwd)
    }

    // case class SkipSlatedCell[A, B, C](contraction: ContractCell[A, B, C]) extends Instruction {
    //   override def execute(): ActionResult =
    //     contraction.l.modifyContentOptWith(contraction, Cell.skipSlatedCell)
    // }

    case class SkipAhead[A](l: Cell[A], m: Cell[A], r: Cell[A]) extends Instruction {
      override def execute(): ActionResult =
        l.modifyContentOptWith(this, CellContent.skipAhead)
    }

    // case class SkipAheadTo[A, B](l: Cell[A], lRole: RoleL[A, B], slated: Cell[B], r: Cell[B]) extends Instruction {
    //   override def execute(): ActionResult =
    //     l.modifyContentOptWith(this, CellContent.skipAheadTo)
    // }

    // case class SkipAheadLink[A, B](l: Cell[A], slated: Cell[A], rRole: RoleR[A, B], r: Cell[B]) extends Instruction {
    //   override def execute(): ActionResult =
    //     l.modifyContentOptWith(this, CellContent.skipAheadLink)
    // }

    case class SkipToRInvSrc[A, B](contraction: ContractLFwdRInvTgt[A, B]) extends Instruction {
      override def execute(): ActionResult =
        contraction.lCell.modifyContentOptWith(contraction, CellContent.skipToRInvSrc)
    }

    case class SkipToRInvTgt[A, B](contraction: ContractLFwdRInvSrc[A, B]) extends Instruction {
      override def execute(): ActionResult =
        contraction.lCell.modifyContentOptWith(contraction, CellContent.skipToRInvTgt)
    }

    case class LUpSkip[A, B](contraction: ContractLInvSrcRFwd[A, B]) extends Instruction {
      override def execute(): ActionResult =
        contraction.tgt.modifyContentOptWith(contraction, CellContent.lUpSkip)
    }

    case class LDownSkip[A, B](contraction: ContractLInvTgtRFwd[A, B]) extends Instruction {
      override def execute(): ActionResult =
        contraction.src.modifyContentOptWith(contraction, CellContent.lDownSkip)
    }

    case class CompleteLBypass[A](l: Cell[A], bypassed: Cell[A], r: Cell[A]) extends Instruction {
      override def execute(): ActionResult =
        r.modifyContentOptWith(this, CellContent.completeLBypass)
    }

    case class CompleteContractRFwd[A, B](l: Cell[A], link: RoleL[A, B], bypassed: Cell[B], r: Cell[B]) extends Instruction {
      override def execute(): ActionResult =
        r.modifyContentOptWith(this, CellContent.completeContractRFwd)
    }

    // case class CompleteContractLFwd[A, B](l: Cell[A], bypassed: Cell[A], link: RoleR[A, B], r: Cell[B]) extends Instruction {
    //   override def execute(): ActionResult =
    //     r.modifyContentOptWith(this, CellContent.completeContractLFwd)
    // }

    case class CompleteLFwdRInvTgtContraction[A, B](contraction: ContractLFwdRInvTgt[A, B]) extends Instruction {
      override def execute(): ActionResult =
        contraction.src.modifyContentOptWith(contraction, CellContent.completeLFwdRInvTgtContraction)
    }

    case class CompleteLFwdRInvSrcContraction[A, B](contraction: ContractLFwdRInvSrc[A, B]) extends Instruction {
      override def execute(): ActionResult =
        contraction.tgt.modifyContentOptWith(contraction, CellContent.completeLFwdRInvSrcContraction)
    }

    case class CompleteLInvSrcRFwdContraction[A, B](contraction: ContractLInvSrcRFwd[A, B]) extends Instruction {
      override def execute(): ActionResult =
        contraction.rCell.modifyContentOptWith(contraction, CellContent.completeLInvSrcRFwdContraction)
    }

    case class CompleteLInvTgtRFwdContraction[A, B](contraction: ContractLInvTgtRFwd[A, B]) extends Instruction {
      override def execute(): ActionResult =
        contraction.rCell.modifyContentOptWith(contraction, CellContent.completeLInvTgtRFwdContraction)
    }

    case class ConsumeSlatedCell[A, B, C](l: Cell[A], bypassed: Cell[B], r: Cell[C]) extends Instruction {
      override def execute(): ActionResult =
        bypassed.modifyContentOptWith(this, CellContent.consumeSlatedCell)
    }
  }
  import ActionResult._

  val supplyDone: (CellContent[Done], Cell[Done]) => (CellContent[Done], ActionResult) = {
    (content, cell) =>
      content match
        case Empty()      => (DoneSupplied, AllDone)
        case DoneSupplied => throw new IllegalStateException("Double completion")
        case RFwd(tgt)    => (Consumed(), RefineLFwd(tgt, expectedLTarget = cell, DoneSupplied))
  }

  val supplyPong: (CellContent[Pong], Cell[Pong]) => (CellContent[Pong], ActionResult) = {
    (content, self) =>
      content match
        case Empty()             => (PongSupplied, AllDone)
        case l: LBypassing[Pong] => (BiDef(l, PongSupplied), AllDone)
  }

  def lsplit[A, B]: (CellContent[A |*| B], Cell[A |*| B]) => (CellContent[A |*| B], (Cell[A], Cell[B], ActionResult)) = {
    (content, self) =>
      content match
        case Empty() =>
          val a = Cell.empty[A]
          val b = Cell.empty[B]
          (LSplit(a, b), (a, b, AllDone))
        case RSplit(a, b) =>
          (Consumed(), (a, b, AllDone))
        case r @ RFwd(tgt) =>
          val a = Cell.empty[A]
          val b = Cell.empty[B]
          val l = LSplit(a, b)
          (Slated(l, r), (a, b, PropagateLSplit(from = self, payload = l, to = tgt)))
        case _: LDefined[A |*| B] | Consumed() =>
          throw new IllegalStateException("The cell is already left-connected")
  }

  def rsplit[A, B]: (CellContent[A |*| B], Cell[A |*| B]) => (CellContent[A |*| B], (Cell[A], Cell[B], ActionResult)) = {
    (content, self) =>
      content match
        case Empty() =>
          val a = Cell.empty[A]
          val b = Cell.empty[B]
          (RSplit(a, b), (a, b, AllDone))
        case LSplit(a, b) =>
          (Consumed(), (a, b, AllDone))
        case LFwd(tgt) =>
          val a = Cell.empty[A]
          val b = Cell.empty[B]
          (Consumed(), (a, b, PropagateRSplit(to = tgt, payload = RSplit(a, b), from = self)))
        case bp @ LBypassing(_, _) =>
          val a = Cell.empty[A]
          val b = Cell.empty[B]
          (BiDef(bp, RSplit(a, b)), (a, b, AllDone))
        case _: RDefined[A |*| B] | Consumed() =>
          throw new IllegalStateException("The cell is already right-connected")
  }

  def propagateLSplit[A, B]: (CellContent[A |*| B], PropagateLSplit[A, B]) => (Option[CellContent[A |*| B]], ActionResult) = {
    (rContent, propagation) =>
      val PropagateLSplit(lCell, payload, self) = propagation

      def checkL(l: LDefined[A |*| B]): Unit =
        l match {
          case LFwd(lTgt) =>
            assert(lTgt eq lCell)
          case LBypassing(newTgt, _) =>
            assert(newTgt eq lCell)
        }

      def goR(r: RDefined[A |*| B]): (Option[CellContent[A |*| B]], ActionResult) =
        val (content, res) = combineLSplitRDefined(self, payload, r)
        (Some(content), res)

      rContent match {
        case l: LDefined[A |*| B] =>
          checkL(l)
          (Some(payload), LSplitPropagated(lCell, payload, self))
        case BiDef(l, r) =>
          checkL(l)
          goR(r)
        case Slated(_, _) => // obstructed
          (None, AllDone)
        case Consumed() =>
          (None, AllDone)
      }
  }

  def propagateRSplit[A, B]: (CellContent[A |*| B], PropagateRSplit[A, B]) => (Option[CellContent[A |*| B]], ActionResult) = {
    (lContent, propagation) =>
      val PropagateRSplit(self, payload, rCell) = propagation

      def checkR(r: RDefined[A |*| B]): Unit =
        r match {
          case RFwd(rTgt) =>
            assert(rTgt eq rCell)
        }

      def goL(l: LDefined[A |*| B]): (Option[CellContent[A |*| B]], ActionResult) =
        val (content, res) = combineLDefinedRSplit(self, l, payload)
        (Some(content), res)

      lContent match {
        case r: RDefined[A |*| B] =>
          checkR(r)
          (Some(payload), AllDone)
        case Empty() =>
          (Some(payload), AllDone)
        case l: LDefined[A |*| B] =>
          goL(l)
        case BiDef(l, r) =>
          checkR(r)
          goL(l)
        case Slated(l, r) =>
          checkR(r)
          goL(l)
      }
  }

  def lsplitPropagated[A, B]: (CellContent[A |*| B], LSplitPropagated[A, B]) => (Option[CellContent[A |*| B]], ActionResult) = {
    (lContent, prop) =>
      val  LSplitPropagated(self, payload, to) = prop
      lContent match {
        case Slated(`payload`, RFwd(`to`)) =>
          (Some(Consumed()), AllDone)
        case other =>
          throw IllegalStateException(s"Unexpected content $other. Expected ${Slated(payload, RFwd(to))}")
      }
  }

  def injectL[A, B]: (CellContent[A |+| B], Cell[A]) => (CellContent[A |+| B], ActionResult) = {
    (tgt, src) =>
      tgt match {
        case Empty() =>
          (InjectedL(src), AllDone)
        case EitherCallback(f) =>
          (Consumed(), CallbackTriggered(f, Right(Left(src))))
        case EitherSwitch(f, g, tgt) =>
          (Consumed(), ConnectionRequest(src, f, tgt))
        case _: LDefined[A |+| B] | Consumed() =>
          throw new IllegalStateException(s"The target cell is already left-connected: $tgt")
      }
  }

  def injectR[A, B]: (CellContent[A |+| B], (Cell[B], Cell[A |+| B])) => (CellContent[A |+| B], ActionResult) = {
    (tgtContent, cells) =>
      val (srcCell, eitherCell) = cells
      tgtContent match {
        case Empty() =>
          (InjectedR(srcCell), AllDone)
        case EitherCallback(f) =>
          (Consumed(), CallbackTriggered(f, Right(Right(srcCell))))
        case EitherSwitch(f, g, tgtCell) =>
          (Consumed(), ConnectionRequest(srcCell, g, tgtCell))
        case RFwd(tgt1) =>
          (Consumed(), RefineLFwd(tgt1, expectedLTarget = eitherCell, InjectedR(srcCell)))
        case _: LDefined[A |+| B] | Consumed() =>
          throw new IllegalStateException("The target cell is already left-connected")
      }
  }

  def eitherSwitch[A, B, C]: (CellContent[A |+| B], EitherSwitch[A, B, C]) => (CellContent[A |+| B], ActionResult) = {
    (eitherContent, es) =>
      eitherContent match {
        case Empty() => (es, AllDone)
      }
  }

  def chooseL[A, B]: (CellContent[A |&| B], ChooseL[A, B]) => (CellContent[A |&| B], ActionResult) = {
    (choiceContent, cells) =>
      val ChooseL(choiceCell, resultCell) = cells
      choiceContent match {
        case Empty() =>
          (ChosenL(resultCell), AllDone)
        case ChooseFrom(lTgt, f, _) =>
          (Consumed(), ConnectionRequest(lTgt, f, resultCell))
        case LFwd(tgt) =>
          (Consumed(), RefineRFwd(tgt, expectedRTarget = choiceCell, ChosenL(resultCell)))
        case LNotifyChoice(notification, tgt) =>
          val followUp =
            RefineRRole(notification, RoleR.ChoiceNotification(), choiceCell, PongSupplied) and
            RefineRRole(tgt, RoleR.NotifyChoiceTgt(), choiceCell, ChosenL(resultCell))
          (Consumed(), followUp)
        case l: LBypassing[A |&| B] =>
          (BiDef(l, ChosenL(resultCell)), AllDone)
      }
  }

  def chooseR[A, B]: (CellContent[A |&| B], ChooseR[A, B]) => (CellContent[A |&| B], ActionResult) = {
    (choiceContent, cells) =>
      val ChooseR(choiceCell, resultCell) = cells
      choiceContent match {
        case Empty() =>
          (ChosenR(resultCell), AllDone)
        case ChooseFrom(lTgt, _, g) =>
          (Consumed(), ConnectionRequest(lTgt, g, resultCell))
        case LFwd(tgt) =>
          (Consumed(), RefineRFwd(tgt, expectedRTarget = choiceCell, ChosenR(resultCell)))
        case l: LBypassing[A |&| B] =>
          (BiDef(l, ChosenR(resultCell)), AllDone)
        case l: LNotifyChoice[A, B] =>
          val followUp =
            RefineRRole(l.notification, RoleR.ChoiceNotification(), expectedRTarget = choiceCell, PongSupplied) and
            RefineRRole(l.tgt, RoleR.NotifyChoiceTgt(), expectedRTarget = choiceCell, ChosenR(resultCell))
          (Consumed(), followUp)
      }
  }

  def chooseFrom[A, B, C]: (CellContent[B |&| C], ChooseFrom[A, B, C]) => (CellContent[B |&| C], ActionResult) = {
    (src, ch) =>
      src match {
        case Empty() => (ch, AllDone)
        case rFwd @ RFwd(_) => (BiDef(ch, rFwd), AllDone)
        case ChosenL(rTgt)  => (Consumed(), ConnectionRequest(ch.tgt, ch.f, rTgt))
        case ChosenR(rTgt)  => (Consumed(), ConnectionRequest(ch.tgt, ch.g, rTgt))
        case r: RRole[B |&| C, d] => (BiDef(ch, r), AllDone)
      }
  }

  def choiceWith[A, B, C]: (CellContent[B |&| C], ChoiceWith[A, B, C]) => (CellContent[B |&| C], ActionResult) = {
    (src, ch) =>
      src match {
        case Empty()        => (ch, AllDone)
        case rFwd @ RFwd(_) => (BiDef(ch, rFwd), AllDone)
        case ChosenR(rTgt)  => (Consumed(), ChooseR(ch.tgt, Cell(RSplit(ch.addendum, rTgt))))
        case ChosenL(rTgt)  => (Consumed(), ChooseL(ch.tgt, Cell(RSplit(ch.addendum, rTgt))))
      }
  }

  val makeAJoinOf: (CellContent[Done], (Cell[Done], JoinOf)) => (CellContent[Done], ActionResult) = {
    (content, cellAndJoiners) =>
      import RoleR.{Joiner1, Joiner2}

      val (self, joiners) = cellAndJoiners
      content match {
        case Empty() =>
          val followUp =
            DirectToR(joiners.src1, Joiner1, self) and
            DirectToR(joiners.src2, Joiner2, self)
          (joiners, followUp)
        case r: RDefined[Done] =>
          val followUp =
            DirectToR(joiners.src1, Joiner1, self) and
            DirectToR(joiners.src2, Joiner2, self)
          (BiDef(joiners, r), followUp)
      }
  }

  val makeAJoinPongOf: (CellContent[Pong], (Cell[Pong], JoinPongOf)) => (CellContent[Pong], ActionResult) = {
    (content, cellAndJoiners) =>
      import RoleL.{JoinerPong1, JoinerPong2}

      val (self, joiners) = cellAndJoiners
      content match {
        case Empty() =>
          val followUp =
            DirectToL(self, JoinerPong1, joiners.src1) and
            DirectToL(self, JoinerPong2, joiners.src2)
          (joiners, followUp)
        case l: LDefined[Pong] =>
          val followUp =
            DirectToL(self, JoinerPong1, joiners.src1) and
            DirectToL(self, JoinerPong2, joiners.src2)
          (BiDef(l, joiners), followUp)
      }
  }

  val makeASelectOf: (CellContent[One |&| One], (Cell[One |&| One], SelectOf)) => (CellContent[One |&| One], ActionResult) = {
    (content, cellAndContestants) =>
      import RoleL.{SelectContestant1, SelectContestant2}

      val (self, contestants) = cellAndContestants
      content match {
        case Empty() =>
          val followUp =
            DirectToL(self, SelectContestant1, contestants.contestant1) and
            DirectToL(self, SelectContestant2, contestants.contestant2)
          (contestants, followUp)
        case l @ LFwd(_) =>
          val followUp =
            DirectToL(self, SelectContestant1, contestants.contestant1) and
            DirectToL(self, SelectContestant2, contestants.contestant2)
          (BiDef(l, contestants), followUp)
      }
  }

  def notifyEither[A, B]: (CellContent[A |+| B], (Cell[A |+| B], RNotifyEither[A, B])) => (CellContent[A |+| B], ActionResult) = {
    (srcContent, cellAndPayload) =>
      import RoleL.{EitherNotification, NotifyEitherTgt}

      val (self, payload) = cellAndPayload

      def followUp =
        DirectToL(self, EitherNotification(), payload.notificationCell) and
        DirectToL(self, NotifyEitherTgt(), payload.outCell)

      srcContent match {
        case Empty() =>
          (payload, followUp)
        case i @ InjectedL(_) =>
          val followUp =
            RefineLRole(self, EitherNotification(), payload.notificationCell, PingSupplied) and
            RefineLRole(self, NotifyEitherTgt(), payload.outCell, i)
          (Consumed(), followUp)
        case l: LFwd[A |+| B] =>
          (BiDef(l, payload), followUp)
        case l: LBypassing[A |+| B] =>
          (BiDef(l, payload), followUp)
      }
  }

  val notifyNeed: (CellContent[Need], (LNotifyNeed, Cell[Need])) => (CellContent[Need], ActionResult) = {
    (srcContent, payloadAndCell) =>
      import RoleR.{NeedNotification, NotifyNeedTgt}

      val (payload, self) = payloadAndCell

      def followUp =
        DirectToR(payload.notification, NeedNotification, self) and
        DirectToR(payload.tgt, NotifyNeedTgt, self)

      srcContent match {
        case Empty() =>
          (payload, followUp)
        case r: RFwd[Need] =>
          (BiDef(payload, r), followUp)
        case r: RInvertTgt[x, Need] =>
          (BiDef(payload, r), followUp)
        case r: RInvertTgtBypassingSrc[x, Need] =>
          (BiDef(payload, r), followUp)
      }
  }

  def notifyChoice[A, B]: (CellContent[A |&| B], (LNotifyChoice[A, B], Cell[A |&| B])) => (CellContent[A |&| B], ActionResult) = {
    (srcContent, payloadAndCell) =>
      import RoleR.{ChoiceNotification, NotifyChoiceTgt}

      val (payload, self) = payloadAndCell
      srcContent match {
        case Empty() =>
          val followUp =
            DirectToR(payload.notification, ChoiceNotification(), self) and
            DirectToR(payload.tgt, NotifyChoiceTgt(), self)
          (payload, followUp)
        case cl @ ChosenL(_) =>
          val followUp =
            RefineRRole(payload.notification, ChoiceNotification(), self, PongSupplied) and
            RefineRRole(payload.tgt, NotifyChoiceTgt(), self, cl)
          (Consumed(), followUp)
      }
  }

  def directToR[A, B]: (CellContent[A], (Cell[A], RRole[A, B])) => (Option[CellContent[A]], ActionResult) = {
    (src, cellAndLink) =>
      val (self, rRole) = cellAndLink
      src match {
        case Empty()                => (Some(rRole), AllDone)
        case l @ LFwd(lTgt)         => (Some(BiDef(l, rRole)), AllDone) // (Some(Slated(l, rRole)), ContractLFwd(lTgt, slated = self, rRole.role, rRole.tgt))
        case l: LBypassing[A]       => (Some(BiDef(l, rRole)), AllDone)
        case l: LBypassingTo[x, A]  => (Some(BiDef(l, rRole)), AllDone)
        case l: JoinOf              => (Some(BiDef(l, rRole)), AllDone)
        case l: ChooseFrom[x, y, z] => (Some(BiDef(l, rRole)), AllDone)
        case l: LRole[x, A]         => (Some(BiDef(l, rRole)), AllDone)
        case Consumed()             => (None, AllDone)
      }
  }

  def directToL[A, B]: (CellContent[B], (LRole[A, B], Cell[B])) => (CellContent[B], ActionResult) = {
    (src, lRoleSelf) =>
      val (lRole, self) = lRoleSelf
      src match {
        case Empty()        => (lRole, AllDone)
        case r @ RFwd(rTgt) => (BiDef(lRole, r), AllDone)
        case PongSupplied   => (Consumed(), RefineRPart(lRole.tgt, lRole.role, self, PongSupplied))
      }
  }

  def unifyInit[A]: (CellContent[A], (Cell[A], Cell[A])) => (CellContent[A], ActionResult) = {
    (rContent, cells) =>
      val (lCell, rCell) = cells
      rContent match {
        case Empty()                         => (LFwd(lCell), RReciprocateForward(lCell, rCell))
        case c: ChosenL[a1, a2]              => (Consumed(), RefineRFwd[a1 |&| a2](lCell, expectedRTarget = rCell, c))
        case c: ChosenR[a1, a2]              => (Consumed(), RefineRFwd[a1 |&| a2](lCell, expectedRTarget = rCell, c))
        case e: EitherCallback[x, y]         => (Consumed(), RefineRFwd[x |+| y](lCell, expectedRTarget = rCell, e))
        case r: RSplit[x, y]                 => (Consumed(), PropagateRSplit[x, y](to = lCell, r, from = rCell))
        case cb: DoneCallback                => (Consumed(), RefineRFwd[Done](lCell, expectedRTarget = rCell, cb))
        case NeedSupplied                    => (Consumed(), RefineRFwd(lCell, expectedRTarget = rCell, NeedSupplied))
        case PongSupplied                    => (Consumed(), RefineRFwd(lCell, expectedRTarget = rCell, PongSupplied))
        case rFwd: RFwd[a]                   => (Slated(LFwd(lCell), rFwd), ContractBiFwd(lCell, rCell, rFwd.tgt))
        case rLnk @ RRole(role, tgt)         => (BiDef(LFwd(lCell), rLnk), RReciprocateForward(lCell, rCell)) // (Slated(LFwd(lCell), rLnk), ContractLFwd(lCell, rCell, role, tgt))
        case r @ RInvertSrc(i, tgt)          => (Slated(LFwd(lCell), r), ContractLFwdRInvSrc(lCell, rCell, i, tgt))
        case s: SelectOf                     => (BiDef(LFwd(lCell), s), RReciprocateForward(lCell, rCell))
        case j: JoinPongOf                   => (BiDef(LFwd(lCell), j), RReciprocateForward(lCell, rCell))
        case r @ RInvertTgt(src, i)          => (Slated(LFwd(lCell), r), ContractLFwdRInvTgt(lCell, rCell, i, src))
        case r: RInvertTgtBypassingSrc[x, A] => (BiDef(LFwd(lCell), r), RReciprocateForward(lCell, rCell))
      }
  }

  def rReciprocateFwd[A]: (CellContent[A], (Cell[A], Cell[A])) => (Option[CellContent[A]], ActionResult) = {
    (lContent, cells) =>
      val (self, rCell) = cells
      lContent match {
        case Empty()                => (Some(RFwd(rCell)), AllDone)
        case DoneSupplied           => (Some(Consumed()), RefineLFwd(rCell, expectedLTarget = self, DoneSupplied))
        case l: NeedCallback        => (Some(Consumed()), RefineLFwd(rCell, expectedLTarget = self, l))
        case i: InjectedR[x, y]     => (Some(Consumed()), RefineLFwd(rCell, expectedLTarget = self, i))
        case l: LSplit[x, y]        => (Some(Slated(l, RFwd(rCell))), PropagateLSplit[x, y](from = self, payload = l, to = rCell))
        case l: LFwd[x]             => (Some(Slated(l, RFwd(rCell))), ContractBiFwd(l.tgt, slated = self, rCell))
        case l: LInvertSrc[A, x]    => (Some(Slated(l, RFwd(rCell))), ContractLInvSrcRFwd(l.tgt, l.inv, slated = self, rCell))
        case l: LRole[x, y]         => (Some(BiDef(l, RFwd(rCell))), AllDone)
        case l: LBypassing[x]       => (Some(BiDef(l, RFwd(rCell))), AllDone)
        case l: LBypassingTo[x, y]  => (Some(BiDef(l, RFwd(rCell))), AllDone)
        case l: LSkippingUp[x, A]   => (Some(BiDef(l, RFwd(rCell))), AllDone)
        case j: JoinOf              => (Some(BiDef(j, RFwd(rCell))), AllDone)
        case c: ChooseFrom[x, y, z] => (Some(BiDef(c, RFwd(rCell))), AllDone)
        case c: ChoiceWith[x, y, z] => (Some(BiDef(c, RFwd(rCell))), AllDone)
        case l: LNotifyNeed         => (Some(BiDef(l, RFwd(rCell))), AllDone)
        case l: LInvertTgtClaimed[x, A] => (Some(BiDef(l, RFwd(rCell))), AllDone)

        // overtaken
        case BiDef(_, _)      => (None, AllDone)
        case RSplit(_, _)     => (None, AllDone)
        case ChosenR(_)       => (None, AllDone)
        case ChosenL(_)       => (None, AllDone)
        case Consumed()       => (None, AllDone)
        case RInvertSrc(_, _) => (None, AllDone)
        case RFwd(_)          => (None, AllDone)
        case Slated(_, _)     => (None, AllDone)
      }
  }

  def rInvertInit[A, B]: (CellContent[B], (Cell[A], Inversion[A, B], Cell[B])) => (CellContent[B], ActionResult) = {
    (tgtContent, srcInvTgt) =>
      val (src, inv, tgt) = srcInvTgt

      def newContentR = RInvertTgt(src, inv)
      def nextStep    = RReciprocateRInvert(src, inv, tgt)

      tgtContent match {
        case Empty()          => (newContentR, nextStep)
        case l: LFwd[B]       => (Slated(l, newContentR), ContractLFwdRInvTgt(l.tgt, tgt, inv, src))
        case l: LBypassing[B] => (BiDef(l, newContentR), nextStep)
      }
  }

  def lInvertInit[A, B]: (CellContent[B], (Cell[A], Inversion[B, A], Cell[B])) => (CellContent[B], ActionResult) = {
    (tgtContent, srcInvTgt) =>
      val (src, inv, tgt) = srcInvTgt
      tgtContent match {
        case Empty() =>
          (LInvertTgt(src, inv), LReciprocateLInvert(src, inv, tgt))
        case r: RFwd[B] =>
          (Slated(LInvertTgt(src, inv), r), ContractLInvTgtRFwd(src, inv, tgt, r.tgt))
        case r: RInvertSrc[B, y] =>
          // not contracting in the current version
          // TODO: Do contract. Use Iso[A, y].
          (BiDef(LInvertTgt(src, inv), r), LReciprocateLInvert(src, inv, tgt))
      }
  }

  def rReciprocateRInvert[A, B]: (CellContent[A], RReciprocateRInvert[A, B]) => (Option[CellContent[A]], ActionResult) = {
    (invSrcContent, inversion) =>
      val RReciprocateRInvert(self, inv, invTgt) = inversion
      invSrcContent match {
        case Empty() =>
          (Some(RInvertSrc(inv, invTgt)), AllDone)
      }
  }

  def lReciprocateLInvert[A, B]: (CellContent[A], LReciprocateLInvert[A, B]) => (Option[CellContent[A]], ActionResult) = {
    (invSrcContent, inversion) =>
      val LReciprocateLInvert(self, inv, invTgt) = inversion
      invSrcContent match {
        case Empty() =>
          (Some(LInvertSrc(inv, invTgt)), AllDone)
      }
  }

  def refineLFwd[A]: (CellContent[A], RefineLFwd[A]) => (CellContent[A], ActionResult) = {
    (rContent, refinement) =>
      val RefineLFwd(self, expectedLCell, payload) = refinement

      def goL(l: LDefined[A]): LComplete[A] = {
        l match {
          case LFwd(tgt) =>
            if (tgt eq expectedLCell)
              payload
            else
              throw new IllegalStateException(s"Actual LFwd target did not equal expected: $tgt vs. $expectedLCell")
          case LBypassing(newTgt, oldTgt) =>
            if ((newTgt eq expectedLCell) || (oldTgt eq expectedLCell))
              payload
            else
              throw new IllegalStateException(s"Neither old ($oldTgt) nor new ($newTgt) target of LBypassing equals the expected target $expectedLCell")
        }
      }

      rContent match {
        case l: LDefined[A] =>
          (goL(l), AllDone)
        case BiDef(l, r) =>
          val l1 = goL(l)
          combineLCompleteRDefined(self, l1, r)
        case Slated(l, r) =>
          val l1 = goL(l)
          combineLCompleteRDefined(self, l1, r)
        case Empty() =>
          (payload, AllDone)
        case Consumed() =>
          (Consumed(), AllDone)
      }
  }

  // def refineRLink[A, B]: (CellContent[A], RefineRLink[A, B]) => (CellContent[A], ActionResult) = {
  //   (lContent, refinement) =>
  //     val RefineRLink(self, link, expectedRCell, payload) = refinement
  //     (lContent, link) match {
  //       case (Empty() | _: LDefined[A], lRole: RoleL[A, B]) =>
  //         throw new IllegalStateException(s"Cannot refine $lRole part of $lContent")
  //       case (BiDef(l, r), link) =>
  //         val r1 = refineRLink(r, link, expectedRCell, payload)
  //         combine(self, l, r1)
  //       case (Slated(l, r), link) =>
  //         // even if already slated, refinement from the right is higher-priority
  //         // and has already precluded the slated-related action
  //         val r1 = refineRLink(r, link, expectedRCell, payload)
  //         combine(self, l, r1)
  //       case _ =>
  //         UnhandledCase.raise(s"($lContent, $link)")
  //     }
  // }

  // private def refineRLink[A, B](
  //   content: RDefined[A],
  //   link: Link[A, B],
  //   expectedRCell: Cell[B],
  //   payload: RComplete[B],
  // ): RDefined[A] = {
  //   def checkRCell[X](cell: Cell[X]): Unit =
  //     if (cell eq expectedRCell) ()
  //     else throw IllegalStateException(s"Unexpected source cell of refinement ${addr(expectedRCell)}. Was expecting ${addr(cell)}")

  //   link match
  //     case RoleL.JoinerPong1 =>
  //       assert(payload == PongSupplied, s"Did not expect $payload: RComplete[Pong]")
  //       content match
  //         case JoinPongOf(src1, src2) =>
  //           checkRCell(src1)
  //           JoinPong2(src2)
  //         case JoinPong1(src1) =>
  //           checkRCell(src1)
  //           PongSupplied
  //     case RoleL.JoinerPong2 =>
  //       assert(payload == PongSupplied, s"Did not expect $payload: RComplete[Pong]")
  //       content match
  //         case JoinPongOf(src1, src2) =>
  //           checkRCell(src2)
  //           JoinPong1(src1)
  //         case JoinPong2(src2) =>
  //           checkRCell(src2)
  //           PongSupplied
  // }

  def refineRFwd[A]: (CellContent[A], RefineRFwd[A]) => (CellContent[A], ActionResult) = {
    (lContent, refinement) =>
      val RefineRFwd(self, expectedRCell, payload) = refinement

      def goBi(l: LDefined[A], r: RDefined[A]): (CellContent[A], ActionResult) = {
        val r1 = r match {
          case RFwd(tgt) =>
            if (tgt eq expectedRCell) {
              payload
            } else {
              throw new IllegalStateException(s"Actual RFwd target did not equal expected: $tgt vs. $expectedRCell")
            }
        }
        combineLDefinedRComplete(self, l, r1)
      }

      lContent match {
        case RFwd(tgt) =>
          if (tgt eq expectedRCell) {
            (payload, AllDone)
          } else {
            throw new IllegalStateException(s"Actual RFwd target did not equal expected: $tgt vs. $expectedRCell")
          }
        case BiDef(l, r) =>
          goBi(l, r)
        case Slated(l, r) =>
          goBi(l, r)
        case l: LDefined[A] =>
          combineLDefinedRComplete(self, l, payload)
        case Empty() =>
          (payload, AllDone)
        case Consumed() =>
          (Consumed(), AllDone)
      }
  }

  def refineRRole[A, B]: (CellContent[A], RefineRRole[A, B]) => (CellContent[A], ActionResult) = {
    (lContent, refinement) =>
      val RefineRRole(self, rRole, expectedRCell, payload) = refinement

      def goR(r: RDefined[A]): RComplete[A] = {
        r match {
          case r @ RRole(rRole1, rTgt) =>
            if ((rTgt eq expectedRCell) && (rRole1 == rRole))
              payload
            else
              throw new IllegalStateException(s"Actual $r did not equal expected ${RRole(rRole, expectedRCell)}")
        }
      }

      def goBi(l: LDefined[A], r: RDefined[A]): (CellContent[A], ActionResult) = {
        val r1 = goR(r)
        combineLDefinedRComplete(self, l, r1)
      }

      lContent match {
        case r @ RRole(rRole1, rTgt) =>
          if ((rTgt eq expectedRCell) && (rRole1 == rRole))
            (payload, AllDone)
          else
            throw new IllegalStateException(s"Actual $r did not equal expected ${RRole(rRole, expectedRCell)}")
        case BiDef(l, r) =>
          goBi(l, r)
        case Slated(l, r) =>
          goBi(l, r)
        case l: LDefined[A] =>
          combineLDefinedRComplete(self, l, payload)
        case Empty() =>
          (payload, AllDone)
      }
  }

  def refineRPart[A, B]: (CellContent[A], RefineRPart[A, B]) => (Option[CellContent[A]], ActionResult) = {
    import RoleL._

    (lContent, refinement) =>
      val RefineRPart(self, lRole, expectedRCell, payload) = refinement

      def goBi(l: LDefined[A], r: RDefined[A]): (Option[CellContent[A]], ActionResult) = {
        def goPong(self: Cell[Pong], l: LDefined[Pong], src: Cell[Pong], newPayload: RDefined[Pong]): (Option[CellContent[Pong]], ActionResult) =
          assert(src eq expectedRCell)
          payload match
            case PongSupplied =>
              val (content, action) = combine(self, l, newPayload)
              (Some(content), action)

        lRole match
          case JoinerPong1 =>
            r match
              case JoinPongOf(src1, src2) =>
                goPong(self, l, src1, newPayload = JoinPong2(src2))
              case JoinPong1(src1) =>
                goPong(self, l, src1, newPayload = PongSupplied)
              case JoinPong2(_) =>
                throw IllegalStateException(s"Double arrival of first Pong joiner")
          case JoinerPong2 =>
            r match
              case JoinPongOf(src1, src2) =>
                goPong(self, l, src2, newPayload = JoinPong1(src1))
              case JoinPong2(src2) =>
                goPong(self, l, src2, newPayload = PongSupplied)
              case JoinPong1(src1) =>
                throw IllegalStateException(s"Double arrival of second Pong joiner")
          case SelectContestant1 =>
            r match
              case SelectOf(contestant1, contestant2) =>
                assert(contestant1 eq expectedRCell)
                payload match
                  case PongSupplied =>
                    val (content, action) = combine(self, l, ChosenL[One, One](Cell.one))
                    (Some(content), action)
          case SelectContestant2 =>
            r match
              case SelectOf(contestant1, contestant2) =>
                assert(contestant2 eq expectedRCell)
                payload match
                  case PongSupplied =>
                    val (content, action) = combine(self, l, ChosenR[One, One](Cell.one))
                    (Some(content), action)
      }

      lContent match {
        case BiDef(l, r) =>
          goBi(l, r)
        case Slated(l, r) =>
          goBi(l, r)
        case Consumed() =>
          (None, AllDone)
        case Empty() =>
          throw new IllegalStateException(s"Unexpected empty cell linked to by $lRole from $expectedRCell")
      }
  }

  def refineRInvertSrc[A, B]: (CellContent[A], RefineRInvertSrc[A, B]) => (Option[CellContent[A]], ActionResult) = {
    (invSrcContent, refinement) =>
      val RefineRInvertSrc(self, rInv, invTgt, payload) = refinement

      invSrcContent match {
        case RInvertSrc(i, tgt) =>
          assert(rInv == i, s"Unexpected inversion $rInv, expected $i")
          assert(invTgt eq tgt, s"Unexpected r-inversion target ${addr(invTgt)}, expected ${addr(tgt)}")
          (Some(payload), AllDone)
      }
  }

  def refineRInvertTgt[A, B]: (CellContent[B], RefineRInvertTgt[A, B]) => (Option[CellContent[B]], ActionResult) = {
    (invTgtContent, refinement) =>
      val RefineRInvertTgt(invSrc, rInv, self, payload) = refinement

      def checkR(r: RDefined[B]): Unit =
        r match {
          case RInvertTgt(src, rInv1) =>
            assert(rInv == rInv1, s"Unexpected inversion $rInv, expected $rInv1")
            assert(invSrc eq src, s"Unexpected r-inversion source ${addr(invSrc)}, expected ${addr(src)}")
          case RInvertTgtBypassingSrc(newSrc, oldSrc, rInv1) =>
            assert(rInv == rInv1, s"Unexpected inversion $rInv, expected $rInv1")
            assert(
              (invSrc eq newSrc) || (invSrc eq oldSrc),
              s"Unexpected r-inversion source ${addr(invSrc)}, expected ${addr(newSrc)} or ${addr(oldSrc)}",
            )
        }

      invTgtContent match {
        case r: RDefined[B] =>
          checkR(r)
          (Some(payload), AllDone)
        case BiDef(l, r) =>
          checkR(r)
          val (content, res) = combineLDefinedRComplete(self, l, payload)
          (Some(content), res)
        case Empty() =>
          (Some(payload), AllDone)
      }
  }

  def refineLInvertSrc[A, B]: (CellContent[A], RefineLInvertSrc[A, B]) => (Option[CellContent[A]], ActionResult) = {
    (invSrcContent, refinement) =>
      val RefineLInvertSrc(invTgt, lInv, self, payload) = refinement

      def checkL(l: LDefined[A]): Unit = {
        def go[X](i: Inversion[X, A], tgt: Cell[X]): Unit =
          assert(invTgt eq tgt, s"Unexpected l-inversion target ${addr(invTgt)}, expected ${addr(tgt)}")
          assert(lInv == i, s"Unexpected inversion $lInv, expected $i")

        l match {
          case LInvertSrc(i, tgt) => go(i, tgt)
        }
      }

      invSrcContent match {
        case l: LDefined[A] =>
          checkL(l)
          (Some(payload), AllDone)
        case Slated(l, r) =>
          checkL(l)
          val (content, res) = combineLCompleteRDefined(self, payload, r)
          (Some(content), res)
        case Empty() =>
          (Some(payload), AllDone)
      }
  }

  def refineLInvertTgt[A, B]: (CellContent[B], RefineLInvertTgt[A, B]) => (Option[CellContent[B]], ActionResult) = {
    (invTgtContent, refinement) =>
      val RefineLInvertTgt(invSrc, lInv, self, payload) = refinement

      def checkL(l: LDefined[B]): Unit = {
        def go[X](invSrc0: Cell[X], lInv0: Inversion[B, X]): Unit =
          assert(invSrc eq invSrc0, s"Unexpected l-inversion source ${addr(invSrc)}, expected ${addr(invSrc0)}")
          assert(lInv == lInv0, s"Unexpected inversion $lInv, expected $lInv0")

        l match {
          case LInvertTgt(src, i)        => go(src, i)
          case LInvertTgtClaimed(src, i) => go(src, i)
          case LSkippingUp(src, i, _)    => go(src, i)
        }
      }

      invTgtContent match {
        case BiDef(l, r) =>
          checkL(l)
          val (content, res) = combineLCompleteRDefined(self, payload, r)
          (Some(content), res)
        case Empty() =>
          (Some(payload), AllDone)
      }
  }

  private def combineLDefinedRComplete[A](self: Cell[A], l: LDefined[A], r: RComplete[A]): (CellContent[A], ActionResult) =
    r match
      case cb @ DoneCallback(listener) =>
        l match
          case DoneSupplied       => (Consumed(), CallbackTriggered(listener, Right(())))
          case j: JoinOf          => (BiDef(j, cb), AllDone)
          case LFwd(lTgt)         => (Consumed(), RefineRFwd(lTgt, expectedRTarget = self, cb))
          case LInvertTgt(src, i) => (Consumed(), RefineLInvertSrc(expectedInvTgt = self, i, src, cb.lInvert(i)))
      case cb @ EitherCallback(listener) =>
        l match
          case InjectedL(cell) => (Consumed(), CallbackTriggered(listener, Right(Left(cell))))
          case InjectedR(cell) => (Consumed(), CallbackTriggered(listener, Right(Right(cell))))
      case cr @ ChosenR(resultCell) =>
        l match
          case ChooseFrom(lTgt, _, g) => (Consumed(), ConnectionRequest(lTgt, g, resultCell))
          case ChoiceWith(lTgt, add)  => (Consumed(), ChooseR(lTgt, Cell(RSplit(add, resultCell))))
          case LFwd(lTgt)             => (Consumed(), RefineRFwd(lTgt, expectedRTarget = self, cr))
          case l: LBypassing[A]       => (BiDef(l, r), AllDone)
      case cl @ ChosenL(resultCell) =>
        l match
          case ChooseFrom(lTgt, f, _) => (Consumed(), ConnectionRequest(lTgt, f, resultCell))
          case ChoiceWith(lTgt, add)  => (Consumed(), ChooseL(lTgt, Cell(RSplit(add, resultCell))))
          case LFwd(lTgt)             => (Consumed(), RefineRFwd(lTgt, expectedRTarget = self, cl))
          case l: LBypassing[A]       => (BiDef(l, r), AllDone)
      case NeedSupplied =>
        l match
          case LFwd(lTgt)         => (Consumed(), RefineRFwd(lTgt, expectedRTarget = self, NeedSupplied))
          case LInvertSrc(i, tgt) => (Consumed(), RefineLInvertTgt(expectedInvSrc = self, i, tgt, NeedSupplied.lUnInvert(i)))
          case l: LBypassing[A]   => (BiDef(l, r), AllDone)
          case l: LNotifyNeed =>
            import RoleR.{NeedNotification, NotifyNeedTgt}
            val followUp =
              RefineRRole(l.notification, NeedNotification, expectedRTarget = self, PongSupplied) and
              RefineRRole(l.tgt, NotifyNeedTgt, expectedRTarget = self, NeedSupplied)
            (Consumed(), followUp)
      case PongSupplied =>
        l match
          case LFwd(lTgt)             => (Consumed(), RefineRFwd(lTgt, expectedRTarget = self, PongSupplied))
          case LRole(lTgt, lRole)     => (Consumed(), RefineRPart(lTgt, lRole, expectedRTarget = self, PongSupplied))
          case l: LBypassing[A]       => (BiDef(l, r), AllDone)
          case l: LBypassingTo[x, A]  => (BiDef(l, r), AllDone)
          // case l: BypassingL[x, y, z] => (BiDef(l, r), AllDone)

  private def combineLCompleteRDefined[A](self: Cell[A], l: LComplete[A], r: RDefined[A]): (CellContent[A], ActionResult) =
    l match
      case DoneSupplied =>
        r match
          case RFwd(tgt) =>
            (Consumed(), RefineLFwd(tgt, expectedLTarget = self, DoneSupplied))
          case RInvertSrc(inv, tgt) =>
            (Consumed(), RefineRInvertTgt(expectedInvSrc = self, inv, tgt, DoneSupplied.rInvert(inv)))
          case r: RInvertSrcBypassingTgt[A, y] =>
            (BiDef(l, r), AllDone)
      case cb @ NeedCallback(f) =>
        r match
          case RFwd(tgt) =>
            (Consumed(), RefineLFwd(tgt, expectedLTarget = self, cb))
          case NeedSupplied =>
            (Consumed(), CallbackTriggered(f, Right(())))
          case RInvertTgt(src, i) =>
            (Consumed(), RefineRInvertSrc(src, i, self, cb.rUnInvert(i)))

  private def combineLDefinedRSplit[A, B](self: Cell[A |*| B], l: LDefined[A |*| B], r: RSplit[A, B]): (CellContent[A |*| B], ActionResult) =
    l match
      case LSplit(l1, l2)   => (Consumed(), UnificationRequest(l1, r.cell1) and UnificationRequest(l2, r.cell2))
      case LFwd(lTgt)       => (Consumed(), PropagateRSplit(to = lTgt, r, from = self))
      case l: LBypassing[A] => (BiDef(l, r), AllDone)

  private def combineLSplitRDefined[A, B](self: Cell[A |*| B], l: LSplit[A, B], r: RDefined[A |*| B]): (CellContent[A |*| B], ActionResult) =
    r match
      case RSplit(r1, r2) => (Consumed(), UnificationRequest(l.cell1, r1) and UnificationRequest(l.cell2, r2))
      case r @ RFwd(rTgt) => (Slated(l, r), PropagateLSplit(from = self, l, to = rTgt))

  private def combine[A](self: Cell[A], l: LDefined[A], r: RDefined[A]): (CellContent[A], ActionResult) =
    (l, r) match {
      case (l: LComplete[A], r)                    => combineLCompleteRDefined(self, l, r)
      case (l, r: RComplete[A])                    => combineLDefinedRComplete(self, l, r)
      case (l: LSplit[x, y], r)                    => combineLSplitRDefined[x, y](self, l, r)
      case (l, r: RSplit[x, y])                    => combineLDefinedRSplit[x, y](self, l, r)
      case (l: LFwd[A], r: RFwd[A])                => (Slated(l, r), ContractBiFwd(l.tgt, self, r.tgt))
      case (l: LFwd[A], r: RInvertSrc[A, b])       => (Slated(l, r), ContractLFwdRInvSrc(l.tgt, self, r.inv, r.tgt))
      case (l: LFwd[A], r: RInvertTgt[x, A])       => (Slated(l, r), ContractLFwdRInvTgt(l.tgt, self, r.inv, r.src))
      case (l: LInvertSrc[A, x], r: RFwd[A])       => (Slated(l, r), ContractLInvSrcRFwd(l.tgt, l.inv, self, r.tgt))
      case (l: LInvertTgt[x, A], r: RFwd[A])       => (Slated(l, r), ContractLInvTgtRFwd(l.src, l.inv, self, r.tgt))
      case (l: LInvertTgt[x, A], r: RInvertSrc[A, y])    => (BiDef(l, r), AllDone) // TODO: Do contract. Use Iso[x, y] in RFwd, LFwd
      case (l: LFwd[A], r: RInvertSrcBypassingTgt[A, b]) => (BiDef(l, r), AllDone)
      case (l: LFwd[A], r: JoinPongOf)             => (BiDef(l, r), AllDone)
      case (l: LRole[x, A], r: JoinPongOf)         => (BiDef(l, r), AllDone)
      case (l: LRole[x, A], r: RFwd[A])            => (BiDef(l, r), AllDone)
      case (l: LFwd[A], r: RRole[A, x])            => (BiDef(l, r), AllDone) // (Slated(l, r), ContractLFwd(l.tgt, self, r.role, r.tgt))
      case (l: LRole[x, A], r: RRole[A, y])        => (BiDef(l, r), AllDone)
      case (l: LInvertSrc[A, x], r: RRole[A, y])   => (BiDef(l, r), AllDone)
      case (l: LBypassing[A], r)                   => (BiDef(l, r), AllDone)
      case (l: LBypassingTo[x, A], r)              => (BiDef(l, r), AllDone)
      case (l: LSkippingUp[x, A], r)               => (BiDef(l, r), AllDone)
      case (l: LSkippingDown[x, A], r)             => (BiDef(l, r), AllDone)
      case (l @ LFwd(lTgt), r @ JoinPong1(src))    => (Slated(l, r), ContractBiFwd(lTgt, slated = self, src))
      case (l @ LFwd(lTgt), r @ JoinPong2(src))    => (Slated(l, r), ContractBiFwd(lTgt, slated = self, src))
      case (l: LRole[x, A], r @ JoinPong1(src))    => (BiDef(l, r), AllDone)
      case (l: LRole[x, A], r @ JoinPong2(src))    => (BiDef(l, r), AllDone)
      case (l: ChooseFrom[x, y, z], r: RFwd[A])    => (BiDef(l, r), AllDone)
      case (l: ChoiceWith[x, y, z], r: RFwd[A])    => (BiDef(l, r), AllDone)
      case (l: LNotifyNeed, r: RInvertTgt[x, y])   => (BiDef(l, r), AllDone)
      case (l: LNotifyNeed, r: RInvertTgtBypassingSrc[x, y]) => (BiDef(l, r), AllDone)
      case (l: LInvertTgtClaimed[x, A], r: RInvertSrc[A, y]) => (BiDef(l, r), AllDone)
      case _ =>
        UnhandledCase.raise(s"($l, $r)")
    }

  // def initContraction[A, B, C]: (CellContent[C], ContractCell[A, B, C]) => (Option[CellContent[C]], ActionResult) = {
  //   (rContent, contraction) =>
  //     val ContractCell(lCell, lLink, slatedCell, rLink, self, newLink) = contraction
  //     rContent match {
  //       case Empty() =>
  //         (Some(BypassingL(lCell, newLink, slatedCell, rLink)), SkipSlatedCell(contraction))
  //       case LRole(lTgt, lRole) =>
  //         if (lTgt eq slatedCell)
  //           assert(lRole == rLink)
  //           (Some(BypassingL(lCell, newLink, slatedCell, rLink)), SkipSlatedCell(contraction))
  //         else
  //           (None, AllDone)
  //       case Slated(l, r) => // obstructed
  //         (None, AllDone)
  //       case PongSupplied =>
  //         (Some(Consumed()), RefineRLink(slatedCell, rLink, expectedRTarget = self, PongSupplied))
  //       case Consumed() =>
  //         (None, AllDone)
  //     }
  // }

  def initLBypass[A]: (CellContent[A], (Cell[A], Cell[A], Cell[A])) => (Option[CellContent[A]], ActionResult) = {
    (rContent, cells) =>
      val (lCell, mCell, rCell) = cells

      def goL(l: LDefined[A]): Option[LDefined[A]] = {
        def goTgt[X](lTgt: Cell[X]): Option[LDefined[A]] =
          if (lTgt eq mCell) Some(LBypassing(lCell, mCell))
          else               None

        l match {
          case LFwd(tgt)             => goTgt(tgt)
          case LRole(tgt, _)         => goTgt(tgt)
          case LBypassing(newTgt, _) => goTgt(newTgt)
        }
      }

      def followUp: ActionResult =
        SkipAhead(lCell, mCell, rCell)

      rContent match {
        case Empty() =>
          (Some(LBypassing(lCell, mCell)), followUp)
        case l: LDefined[A] =>
          goL(l) match
            case None => (None, AllDone)
            case some => (some, followUp)
        case BiDef(l, r) =>
          goL(l) match
            case None => (None, AllDone)
            case Some(l) => (Some(BiDef(l, r)), followUp)
        case Slated(_, _) => // obstructed
          (None, AllDone)
        case Consumed() | DoneSupplied => // overtaken
          (None, AllDone)
      }
  }

  // def initContractRFwd[A, B]: (CellContent[B], ContractRFwd[A, B]) => (Option[CellContent[B]], ActionResult) = {
  //   (rContent, contraction) =>
  //     val ContractRFwd(lCell, link, slatedCell, self) = contraction

  //     def goL(l: LDefined[B]): Option[LDefined[B]] = {
  //       def goTgt[X](lTgt: Cell[X]): Option[LDefined[B]] =
  //         if (lTgt eq slatedCell) Some(LBypassingTo(lCell, link, slatedCell))
  //         else                    None

  //       l match {
  //         case LFwd(lTgt)            => goTgt(lTgt)
  //         case LRole(lTgt, lRole)    => goTgt(lTgt)
  //         case LBypassing(newTgt, _) => goTgt(newTgt)
  //       }
  //     }

  //     def followUp: ActionResult =
  //       SkipAheadTo(lCell, link, slatedCell, self)

  //     rContent match {
  //       case Empty() =>
  //         (Some(LBypassingTo(lCell, link, slatedCell)), followUp)
  //       case l: LDefined[B] =>
  //         goL(l) match
  //           case None => (None, AllDone)
  //           case some => (some, followUp)
  //       case BiDef(l, r) =>
  //         goL(l) match
  //           case Some(l) => (Some(BiDef(l, r)), followUp)
  //           case None    => (None, AllDone)
  //       case Slated(_, _) =>
  //         (None, AllDone)
  //       case r: RRole[B, c] =>
  //         (Some(BiDef(LBypassingTo(lCell, link, slatedCell), r)), SkipAheadTo(lCell, link, slatedCell, self))
  //       case Consumed() =>
  //         (None, AllDone)
  //     }
  // }

  // def initContractLFwd[A, B]: (CellContent[B], ContractLFwd[A, B]) => (Option[CellContent[B]], ActionResult) = {
  //   (rContent, contraction) =>
  //     val ContractLFwd(lCell, slatedCell, rRole, self) = contraction

  //     def goL(l: LDefined[B]): (Option[LDefined[B]], ActionResult) = {
  //       import RoleR._
  //       l match {
  //         case l: LNotifyChoice[x, y] =>
  //           rRole match {
  //             case ChoiceNotification() =>
  //               if (l.notificationCell eq slatedCell)
  //                 ( Some(LNotifyChoice(LBypassing(lCell, slatedCell), l.tgt))
  //                 , SkipAheadLink(lCell, slatedCell, rRole, self)
  //                 )
  //               else // overtaken
  //                 (None, AllDone)
  //             case NotifyChoiceTgt() =>
  //               if (l.tgtCell eq slatedCell)
  //                 ( Some(LNotifyChoice(l.notification, LBypassing[x |&| y](lCell, slatedCell)))
  //                 , SkipAheadLink(lCell, slatedCell, rRole, self)
  //                 )
  //               else // overtaken
  //                 (None, AllDone)
  //           }
  //         case l: LNotifyNeed =>
  //           rRole match {
  //             case NeedNotification =>
  //               if (l.notificationCell eq slatedCell)
  //                 ( Some(LNotifyNeed(LBypassing(lCell, slatedCell), l.tgt))
  //                 , SkipAheadLink(lCell, slatedCell, rRole, self)
  //                 )
  //               else // overtaken
  //                 (None, AllDone)
  //             case NotifyNeedTgt =>
  //               if (l.tgtCell eq slatedCell)
  //                 ( Some(LNotifyNeed(l.notification, LBypassing(lCell, slatedCell)))
  //                 , SkipAheadLink(lCell, slatedCell, rRole, self)
  //                 )
  //               else // overtaken
  //                 (None, AllDone)
  //           }
  //       }
  //     }

  //     rContent match {
  //       case l: LDefined[B] =>
  //         goL(l)
  //       case BiDef(l, r) =>
  //         val (l1, res) = goL(l)
  //         (l1.map(BiDef(_, r)), res)
  //       case Consumed() =>
  //         (None, AllDone)
  //       case LRole(_, lRole) =>
  //         throw new IllegalStateException(s"Unexpected reciprocal roles: $rRole <-> $lRole")
  //     }
  // }

  def initLFwdRInvTgtContraction[A, B]: (CellContent[B], ContractLFwdRInvTgt[A, B]) => (Option[CellContent[B]], ActionResult) = {
    (invSrcContent, contraction) =>
      val ContractLFwdRInvTgt(lCell, slatedRInvTgt, rInv, rInvSrc) = contraction

      def newContentR = RInvertSrcBypassingTgt(rInv, oldTgt = slatedRInvTgt, newTgt = lCell)
      def followUp    = SkipToRInvSrc(contraction)

      def goR(r: RDefined[B]): Option[RDefined[B]] = {
        r match
          case RInvertSrc(i, tgt) =>
            assert(i == rInv, s"Unexpected inversion $rInv, expected $i")
            if (tgt eq slatedRInvTgt)
              Some(newContentR)
            else
              None
          case RInvertSrcBypassingTgt(i, _, newTgt) =>
            assert(i == rInv, s"Unexpected inversion $rInv, expected $i")
            if (newTgt eq slatedRInvTgt)
              Some(newContentR)
            else
              None
      }

      invSrcContent match {
        case Empty() =>
          (Some(newContentR), followUp)
        case r: RDefined[B] =>
          goR(r) match
            case None => (None, AllDone)
            case some => (some, followUp)
        case l: LDefined[B] =>
          (Some(BiDef(l, newContentR)), followUp)
        case BiDef(l, r) =>
          goR(r) match
            case Some(r) => (Some(BiDef(l, r)), followUp)
            case None    => (None, AllDone)
        case Slated(l, r) =>
          // proceed anyway, since the current contraction is higher priority
          // and has already obstructed whatever action marked this cell as slated
          goR(r) match
            case Some(r) => (Some(BiDef(l, r)), followUp)
            case None    => (None, AllDone)
      }
  }

  def initLFwdRInvSrcContraction[A, B]: (CellContent[B], ContractLFwdRInvSrc[A, B]) => (Option[CellContent[B]], ActionResult) = {
    (invTgtContent, contraction) =>
      val ContractLFwdRInvSrc(lCell, slatedInvSrc, inv, invTgt) = contraction

      def goR(r: RDefined[B]): Option[RDefined[B]] = {
        def goInvSrc[X](src: Cell[X], inv1: Inversion[X, B]): Option[RDefined[B]] = {
          assert(inv1 == inv, s"Unexpected inversion $inv1, expected $inv")
          if (src eq slatedInvSrc)
            Some(RInvertTgtBypassingSrc(newTgt = lCell, slatedInvSrc, inv))
          else
            None
        }

        r match {
          case RInvertTgt(src, inv1) =>
            goInvSrc(src, inv1)
          case RInvertTgtBypassingSrc(newSrc, oldSrc, inv1) =>
            goInvSrc(newSrc, inv1)
        }
      }

      def followUp: ActionResult =
        SkipToRInvTgt(contraction)

      invTgtContent match {
        case Empty() =>
          ( Some(RInvertTgtBypassingSrc(newTgt = lCell, oldTgt = slatedInvSrc, inv))
          , followUp
          )
        case r: RDefined[B] =>
          goR(r) match
            case None => (None, AllDone)
            case some => (some, followUp)
        case BiDef(l, r) =>
          goR(r) match
            case Some(r) => (Some(BiDef(l, r)), followUp)
            case None    => (None, AllDone)
        case Slated(_, _) =>
          // obstructed, as we consider inversion target to be higher priority than inversion source
          (None, AllDone)
      }
  }

  def initContractionLInvSrcRFwdClaimTgt[A, B]: (CellContent[A], ContractLInvSrcRFwd[A, B]) => (Option[CellContent[A]], ActionResult) = {
    (invTgtContent, contraction) =>
      val ContractLInvSrcRFwd(self, lInv, slated, rCell) = contraction

      def newContentL = LInvertTgtClaimed(slated, lInv)
      def followUp    = ContractionLInvSrcRFwdClaimedTgt(contraction)

      def goL(l: LDefined[A]): Option[LDefined[A]] =
        l match {
          case LInvertTgt(src, i) =>
            assert(slated eq src)
            assert(lInv == i)
            Some(newContentL)
          case LInvertTgtClaimed(src, i) =>
            // a previous attempt at contraction was made, but was obstructed after the claim
            assert(slated eq src)
            assert(lInv == i)
            Some(newContentL)
          case LSkippingUp(src, i, _) =>
            assert(slated eq src)
            assert(lInv == i)
            Some(newContentL)
        }

      invTgtContent match {
        case Empty() =>
          (Some(newContentL), followUp)
        case l: LDefined[A] =>
          goL(l) match
            case None => (None, AllDone)
            case some => (some, followUp)
        case BiDef(l, r) =>
          goL(l) match
            case Some(l) => (Some(BiDef(l, r)), followUp)
            case None    => (None, AllDone)
        case Consumed() =>
          (None, AllDone)
      }
  }

  def initContractionLInvSrcRFwdAfterTgtClaimed[A, B]: (CellContent[B], ContractLInvSrcRFwd[A, B]) => (Option[CellContent[B]], ActionResult) = {
    (rContent, contraction) =>
      val ContractLInvSrcRFwd(invTgt, lInv, slated, self) = contraction

      def newContentL = LSkippingDown(invTgt, lInv, oldTgt = slated)
      def followUp    = LUpSkip(contraction)

      def goL(l: LDefined[B]): Option[LDefined[B]] =
        l match {
          case LFwd(lTgt) =>
            if (lTgt eq slated) Some(newContentL)
            else                None
          case LBypassing(newTgt, _) =>
            if (newTgt eq slated) Some(newContentL)
            else                  None
        }

      rContent match {
        case Empty() =>
          (Some(newContentL), followUp)
        case l: LDefined[B] =>
          goL(l) match
            case None => (None, AllDone)
            case some => (some, followUp)
        case BiDef(l, r) =>
          goL(l) match
            case Some(l) => (Some(BiDef(l, r)), followUp)
            case None    => (None, AllDone)
        case Slated(_, _) => // obstructed
          (None, AllDone)
        case Consumed() =>
          (None, AllDone)
      }
  }

  def initContractionLInvTgtRFwd[A, B]: (CellContent[B], ContractLInvTgtRFwd[A, B]) => (Option[CellContent[B]], ActionResult) = {
    (rContent, contraction) =>
      val ContractLInvTgtRFwd(invSrc, lInv, slatedInvTgt, self) = contraction

      def newContentL = LSkippingUp(invSrc, lInv, oldTgt = slatedInvTgt)
      def followUp    = LDownSkip(contraction)

      def lStillCurrent(l: LDefined[B]): Boolean =
        l match {
          case LFwd(tgt)             => tgt eq slatedInvTgt
          case LBypassing(newTgt, _) => newTgt eq slatedInvTgt
        }

      rContent match {
        case l: LDefined[B] =>
          if (lStillCurrent(l)) (Some(newContentL), followUp)
          else                  (None, AllDone)
        case BiDef(l, r) =>
          if (lStillCurrent(l)) (Some(BiDef(newContentL, r)), followUp)
          else                  (None, AllDone)
        case Slated(_, _) => // obstructed
          (None, AllDone)
        case Consumed() =>
          (None, AllDone)
      }
  }

  // def skipSlatedCell[A, B, C]: (CellContent[A], ContractCell[A, B, C]) => (Option[CellContent[A]], ActionResult) = {
  //   (lContent, contraction) =>
  //     val ContractCell(lCell, lLink, slatedCell, rLink, rCell, newLink) = contraction
  //     lContent match {
  //       case Empty() =>
  //     }
  // }

  def skipAhead[A]: (CellContent[A], SkipAhead[A]) => (Option[CellContent[A]], ActionResult) = {
    (lContent, cells) =>
      val SkipAhead(lCell, mCell, rCell) = cells

      def goBi(l: LDefined[A], r: RDefined[A]): (Option[CellContent[A]], ActionResult) = {
          r match {
            case RFwd(tgt) =>
              if (tgt eq mCell)
                val (content, res) = combine(lCell, l, RFwd(rCell))
                val action = res match {
                  case a: FollowUpAction => a
                  case AllDone           => CompleteLBypass(lCell, mCell, rCell)
                }
                val followUp = action and ConsumeSlatedCell(lCell, mCell, rCell)
                (Some(content), followUp)
              else // overtaken
                (None, AllDone)
          }
      }

      lContent match {
        case RFwd(tgt) =>
          if (tgt eq mCell)
            (Some(RFwd(rCell)), CompleteLBypass(lCell, mCell, rCell) and ConsumeSlatedCell(lCell, mCell, rCell))
          else // overtaken
            (None, AllDone)
        case BiDef(l, r) =>
          goBi(l, r)
        case Slated(l, r) =>
          goBi(l, r)
        case Consumed() => // overtaken
          (None, AllDone)
        case DoneSupplied =>
          (
            Some(Consumed()),
            RefineLFwd(rCell, expectedLTarget = lCell, DoneSupplied) and
              ConsumeSlatedCell(lCell, mCell, rCell)
          )
        case Empty() =>
          (Some(RFwd(rCell)), CompleteLBypass(lCell, mCell, rCell) and ConsumeSlatedCell(lCell, mCell, rCell))
        case l @ LFwd(lTgt) =>
          (Some(Slated(l, RFwd(rCell))), ContractBiFwd(lTgt, lCell, rCell) and ConsumeSlatedCell(lCell, mCell, rCell))
        case l @ LRole(lTgt, lRole) =>
          (Some(BiDef(l, RFwd(rCell))), CompleteLBypass(lCell, mCell, rCell) and ConsumeSlatedCell(lCell, mCell, rCell))
        case l: LSplit[x, y] =>
          (Some(Slated(l, RFwd(rCell))), PropagateLSplit[x, y](from = lCell, l, to = rCell) and ConsumeSlatedCell(lCell, mCell, rCell))
        case l: LBypassing[A] =>
          (Some(BiDef(l, RFwd(rCell))), CompleteLBypass(lCell, mCell, rCell) and ConsumeSlatedCell(lCell, mCell, rCell))
        case l: LBypassingTo[x, A] =>
          (Some(BiDef(l, RFwd(rCell))), CompleteLBypass(lCell, mCell, rCell) and ConsumeSlatedCell(lCell, mCell, rCell))
        case l: ChooseFrom[x, a1, a2] =>
          (Some(BiDef(l, RFwd(rCell))), CompleteLBypass(lCell, mCell, rCell) and ConsumeSlatedCell(lCell, mCell, rCell))
        case l: ChoiceWith[x, y, z] =>
          (Some(BiDef(l, RFwd(rCell))), CompleteLBypass(lCell, mCell, rCell) and ConsumeSlatedCell(lCell, mCell, rCell))
      }
  }

  // def skipAheadTo[A, B]: (CellContent[A], SkipAheadTo[A, B]) => (Option[CellContent[A]], ActionResult) = {
  //   (lContent, payload) =>
  //     val SkipAheadTo(lCell, lRole, slatedCell, rCell) = payload

  //     def goR(r: RDefined[A]): Option[RDefined[A]] = {
  //       import RoleL._
  //       r match {
  //         case JoinPongOf(src1, src2) =>
  //           lRole match {
  //             case JoinerPong1 =>
  //               if (src1 eq slatedCell)
  //                 Some(JoinPongOf(rCell, src2))
  //               else // overtaken
  //                 None
  //             case JoinerPong2 =>
  //               if (src2 eq slatedCell)
  //                 Some(JoinPongOf(src1, rCell))
  //               else // overtaken
  //                 None
  //           }
  //         case SelectOf(contestant1, contestant2) =>
  //           lRole match {
  //             case SelectContestant1 =>
  //               if (contestant1 eq slatedCell)
  //                 Some(SelectOf(rCell, contestant2))
  //               else // overtaken
  //                 None
  //             case SelectContestant2 =>
  //               if (contestant2 eq slatedCell)
  //                 Some(SelectOf(contestant1, rCell))
  //               else // overtaken
  //                 None
  //           }
  //         case RRole(rRole, _) =>
  //           throw new IllegalStateException(s"Unexpected reciprocal roles: $rRole <-> $lRole")
  //       }
  //     }

  //     def followUp: ActionResult =
  //       CompleteContractRFwd(lCell, lRole, slatedCell, rCell) and
  //       ConsumeSlatedCell(lCell, slatedCell, rCell)

  //     lContent match {
  //       case r: RDefined[A] =>
  //         goR(r) match
  //           case None => (None, AllDone)
  //           case some => (some, followUp)
  //       case BiDef(l, r) =>
  //         goR(r) match
  //           case Some(r) => (Some(BiDef(l, r)), followUp)
  //           case None    => (None, AllDone)
  //       case Slated(l, r) =>
  //         goR(r) match
  //           case Some(r) =>
  //             val (content, res) = combine(lCell, l, r)
  //             val action = res match {
  //               case a: FollowUpAction => a
  //               case AllDone           => CompleteContractRFwd(lCell, lRole, slatedCell, rCell)
  //             }
  //             val followUp = action and ConsumeSlatedCell(lCell, slatedCell, rCell)
  //             (Some(content), followUp)
  //           case None =>
  //             (None, AllDone)
  //       case Consumed() =>
  //         (None, AllDone)
  //       case Empty() | _: LDefined[A] =>
  //         throw new IllegalStateException(s"Unexpected right-unconnected cell ($lContent) linked to by $lRole from $slatedCell")
  //     }
  // }

  // def skipAheadLink[A, B]: (CellContent[A], SkipAheadLink[A, B]) => (Option[CellContent[A]], ActionResult) = {
  //   (lContent, payload) =>
  //     val SkipAheadLink(lCell, slatedCell, rRole, rCell) = payload

  //     def goR(r: RDefined[A]): Option[RDefined[A]] =
  //       r match {
  //         case RFwd(tgt) =>
  //           if (tgt eq slatedCell)
  //             Some(RRole(rRole, rCell))
  //           else // overtaken
  //             None
  //       }

  //     def goCombine(l: LDefined[A], r: RDefined[A]): (Option[CellContent[A]], ActionResult) = {
  //       val (content, res) = combine(lCell, l, r)
  //       val action = res match {
  //         case a: FollowUpAction => a
  //         case AllDone           => CompleteContractLFwd(lCell, slatedCell, rRole, rCell)
  //       }
  //       (Some(content), action and ConsumeSlatedCell(lCell, slatedCell, rCell))
  //     }

  //     def followUp =
  //       CompleteContractLFwd(lCell, slatedCell, rRole, rCell) and
  //       ConsumeSlatedCell(lCell, slatedCell, rCell)

  //     lContent match {
  //       case Empty() =>
  //         (Some(RRole(rRole, rCell)), followUp)
  //       case r: RFwd[A] =>
  //         goR(r) match
  //           case r @ Some(_) => (r, followUp)
  //           case None        => (None, AllDone)
  //       case BiDef(l, r) =>
  //         goR(r) match
  //           case Some(r) => (Some(BiDef(l, r)), followUp)
  //           case None    => (None, AllDone)
  //       case l: LDefined[A] =>
  //         goCombine(l, RRole(rRole, rCell))
  //       case Slated(l, r) =>
  //         goR(r) match
  //           case Some(r) => goCombine(l, r)
  //           case None    => (None, AllDone)
  //     }
  // }

  def skipToRInvSrc[A, B]: (CellContent[A], ContractLFwdRInvTgt[A, B]) => (Option[CellContent[A]], ActionResult) = {
    (lContent, contraction) =>
      val ContractLFwdRInvTgt(lCell, slatedCell, inversion, invSrc) = contraction

      def newContentR =
        RInvertTgt(invSrc, inversion)
      def followUp =
        CompleteLFwdRInvTgtContraction(contraction) and
        ConsumeSlatedCell(lCell, slatedCell, invSrc)

      def goR(r: RDefined[A]): Option[RDefined[A]] =
        r match {
          case RFwd(rTgt) =>
            if (rTgt eq slatedCell)
              Some(newContentR)
            else // overtaken
              None
        }

      def goCombine(l: LDefined[A], r: RDefined[A]): (Option[CellContent[A]], ActionResult) =
        val (content, res) = combine(lCell, l, r)
        val action = res match
          case a: FollowUpAction => a
          case AllDone           => CompleteLFwdRInvTgtContraction(contraction)
        (Some(content), action and ConsumeSlatedCell(lCell, slatedCell, invSrc))

      lContent match {
        case Empty() =>
          (Some(newContentR), followUp)
        case r: RDefined[A] =>
          goR(r) match
            case None => (None, AllDone)
            case some => (some, followUp)
        case BiDef(l, r) =>
          goR(r) match
            case Some(r) => (Some(BiDef(l, r)), followUp)
            case None    => (None, AllDone)
        case l: LDefined[A] =>
          goCombine(l, newContentR)
        case Slated(l, r) =>
          goR(r) match
            case Some(r) => goCombine(l, r)
            case None    => (None, AllDone)
      }
  }

  def skipToRInvTgt[A, B]: (CellContent[A], ContractLFwdRInvSrc[A, B]) => (Option[CellContent[A]], ActionResult) = {
    (lContent, contraction) =>
      val ContractLFwdRInvSrc(lCell, slatedCell, inversion, invTgt) = contraction

      def goR(r: RDefined[A]): Option[RDefined[A]] =
        r match {
          case RFwd(rTgt) =>
            if (rTgt eq slatedCell)
              Some(RInvertSrc(inversion, invTgt))
            else // overtaken
              None
        }

      def goCombine(l: LDefined[A], r: RDefined[A]): (Option[CellContent[A]], ActionResult) =
        val (content, res) = combine(lCell, l, r)
        val action = res match
          case a: FollowUpAction => a
          case AllDone           => CompleteLFwdRInvSrcContraction(contraction)
        (Some(content), action and ConsumeSlatedCell(lCell, slatedCell, invTgt))

      def followUp =
        CompleteLFwdRInvSrcContraction(contraction) and
        ConsumeSlatedCell(lCell, slatedCell, invTgt)

      lContent match {
        case Empty() =>
          (Some(RInvertSrc(inversion, invTgt)), followUp)
        case r: RDefined[A] =>
          goR(r) match
            case None => (None, AllDone)
            case some => (some, followUp)
        case BiDef(l, r) =>
          goR(r) match
            case Some(r) => goCombine(l, r)
            case None    => (None, AllDone)
        case Slated(l, r) =>
          // proceed anyway, since the current contraction on the right is higher priority
          // and has already obstructed whatever action slated this cell
          goR(r) match
            case Some(r) => goCombine(l, r)
            case None    => (None, AllDone)
        case l: LDefined[A] =>
          goCombine(l, RInvertSrc(inversion, invTgt))
        case Consumed() =>
          (None, AllDone)
      }
  }

  def lUpSkip[A, B]: (CellContent[A], ContractLInvSrcRFwd[A, B]) => (Option[CellContent[A]], ActionResult) = {
    (invTgtContent, contraction) =>
      val ContractLInvSrcRFwd(self, lInv, slated, rCell) = contraction

      def newContentL   = LInvertTgt(rCell, lInv)
      def complete      = CompleteLInvSrcRFwdContraction(contraction)
      def consumeSlated = ConsumeSlatedCell(self, slated, rCell)
      def followUp      = complete and consumeSlated

      def checkL(l: LDefined[A]): Unit =
        l match {
          case LInvertTgtClaimed(src, i) =>
            assert(slated eq src, s"Unexpected l-inversion source ${addr(slated)}, expected ${addr(src)}")
            assert(lInv == i, s"Unexpected inversion $lInv, expected $i")
        }

      invTgtContent match {
        case l: LDefined[A] =>
          checkL(l)
          (Some(newContentL), followUp)
        case BiDef(l, r) =>
          checkL(l)
          val (content, res) = combine(self, newContentL, r)
          val action = res match
            case a: FollowUpAction => a
            case AllDone           => complete
          (Some(content), action and consumeSlated)
        case Empty() =>
          (Some(newContentL), followUp)
      }
  }

  def lDownSkip[A, B]: (CellContent[A], ContractLInvTgtRFwd[A, B]) => (Option[CellContent[A]], ActionResult) = {
    (invSrcContent, contraction) =>
      val ContractLInvTgtRFwd(self, lInv, slated, rCell) = contraction

      def newContentL    = LInvertSrc(lInv, rCell)
      def completeAction = CompleteLInvTgtRFwdContraction(contraction)
      def consumeSlated  = ConsumeSlatedCell(self, slated, rCell)
      def followUp       = completeAction and consumeSlated

      def goL(l: LDefined[A]): Option[LDefined[A]] =
        l match {
          case LInvertSrc(i, tgt) =>
            if (slated eq tgt)
              assert(lInv == i, s"Unexpected inversion $lInv, expected $i")
              Some(newContentL)
            else
              None
        }

      def goCombine(l: LDefined[A], r: RDefined[A]): (Option[CellContent[A]], ActionResult) = {
        val (content, res) = combine(self, l, r)
        val action = res match
          case a: FollowUpAction => a
          case AllDone           => completeAction
        (Some(content), action and consumeSlated)
      }

      invSrcContent match
        case BiDef(l, r) =>
          goL(l) match
            case Some(l) => goCombine(l, r)
            case None    => (None, AllDone)
        case r: RDefined[A] =>
          goCombine(newContentL, r)
        case Empty() =>
          (Some(newContentL), followUp)
  }

  def completeLBypass[A]: (CellContent[A], CompleteLBypass[A]) => (Option[CellContent[A]], ActionResult) = {
    (rContent, cells) =>
      val CompleteLBypass(lCell, mCell, rCell) = cells
      rContent match {
        case LBypassing(newTgt, oldTgt) =>
          if (newTgt eq lCell)
            (Some(LFwd(lCell)), AllDone)
          else // overtaken
            (None, AllDone)
        case BiDef(LBypassing(newTgt, oldTgt), rr) =>
          if (newTgt eq lCell)
            val (newContent, res) = combine(rCell, LFwd(lCell), rr)
            (Some(newContent), res)
          else // overtaken
            (None, AllDone)
        case LFwd(_) => // overtaken
          (None, AllDone)
        case Consumed() =>
          (None, AllDone)
      }
  }

  def completeContractRFwd[A, B]: (CellContent[B], CompleteContractRFwd[A, B]) => (Option[CellContent[B]], ActionResult) = {
    (rContent, payload) =>
      val CompleteContractRFwd(lCell, lLink, bypassedCell, rCell) = payload
      rContent match {
        case LBypassingTo(`lCell`, `lLink`, `bypassedCell`) =>
          (Some(LRole(lCell, lLink)), AllDone)
        case BiDef(l, r) =>
          l match {
            case LBypassingTo(`lCell`, `lLink`, `bypassedCell`) =>
              val (content, action) = combine(rCell, LRole(lCell, lLink), r)
              (Some(content), action)
          }
      }
  }

  // def completeContractLFwd[A, B]: (CellContent[B], CompleteContractLFwd[A, B]) => (Option[CellContent[B]], ActionResult) = {
  //   import RoleR._

  //   (rContent, payload) =>
  //     val CompleteContractLFwd(lCell, bypassedCell, rRole, rCell) = payload

  //     def goL(l: LDefined[B]): Option[LDefined[B]] = {
  //       l match {
  //         case l: LNotifyChoice[x, y] =>
  //           rRole match {
  //             case ChoiceNotification() =>
  //               l.notification match
  //                 case LBypassing(`lCell`, _) => Some(LNotifyChoice(lCell, l.tgt))
  //                 case _                      => None // overtaken
  //             case NotifyChoiceTgt() =>
  //               l.tgt match
  //                 case LBypassing(`lCell`, _) => Some(LNotifyChoice[x, y](l.notification, lCell))
  //                 case _                      => None // overtaken
  //           }
  //         case l: LNotifyNeed =>
  //           rRole match {
  //             case NeedNotification =>
  //               l.notification match
  //                 case LBypassing(`lCell`, _) => Some(LNotifyNeed(lCell, l.tgt))
  //                 case _                      => None // overtaken
  //             case NotifyNeedTgt =>
  //               l.tgt match
  //                 case LBypassing(`lCell`, _) => Some(LNotifyNeed(l.notification, lCell))
  //                 case _                      => None // overtaken
  //           }
  //       }
  //     }

  //     rContent match {
  //       case l: LDefined[B] =>
  //         (goL(l), AllDone)
  //       case BiDef(l, r) =>
  //         goL(l) match
  //           case Some(l) =>
  //             val (content, res) = combine(rCell, l, r)
  //             (Some(content), res)
  //           case None =>
  //             (None, AllDone)
  //       case Consumed() =>
  //         (None, AllDone)
  //     }
  // }

  def completeLFwdRInvTgtContraction[A, B]: (CellContent[B], ContractLFwdRInvTgt[A, B]) => (Option[CellContent[B]], ActionResult) = {
    (invSrcContent, contraction) =>
      val ContractLFwdRInvTgt(lCell, slatedCell, inv, self) = contraction
      invSrcContent match {
        case RInvertSrcBypassingTgt(rInv, _, newTgt) =>
          if (newTgt eq lCell) {
            (Some(RInvertSrc(rInv, newTgt)), AllDone)
          } else { // overtaken
            (None, AllDone)
          }
        case BiDef(l, r) =>
          r match {
            case RInvertSrcBypassingTgt(rInv, _, newTgt) =>
              if (newTgt eq lCell) {
                val (content, action) = combine(self, l, RInvertSrc(rInv, newTgt))
                (Some(content), action)
              } else { // overtaken
                (None, AllDone)
              }
          }
      }
  }

  def completeLFwdRInvSrcContraction[A, B]: (CellContent[B], ContractLFwdRInvSrc[A, B]) => (Option[CellContent[B]], ActionResult) = {
    (invTgtContent, contraction) =>
      val ContractLFwdRInvSrc(lCell, bypassedCell, i, self) = contraction

      def goR(r: RDefined[B]): Option[RDefined[B]] = {
        r match {
          case RInvertTgtBypassingSrc(newTgt, _, rInv) =>
            if (newTgt eq lCell) Some(RInvertTgt(newTgt, rInv))
            else                 None
          case RInvertTgt(_, _) => // overtaken
            None
        }
      }

      invTgtContent match {
        case r: RDefined[B] =>
          (goR(r), AllDone)
        case BiDef(l, r) =>
          goR(r) match
            case Some(r) =>
              val (content, res) = combine(self, l, r)
              (Some(content), res)
            case None =>
              (None, AllDone)
      }
  }

  def completeLInvSrcRFwdContraction[A, B]: (CellContent[B], ContractLInvSrcRFwd[A, B]) => (Option[CellContent[B]], ActionResult) = {
      (rContent, contraction) =>
        val ContractLInvSrcRFwd(tgt, lInv, slated, rCell) = contraction

        def goL(l: LDefined[B]): Option[LDefined[B]] =
          l match {
            case LSkippingDown(tgt0, lInv0, _) =>
              if (tgt eq tgt0)
                assert(lInv == lInv0, s"Unexpected inversion $lInv, expected $lInv0")
                Some(LInvertSrc(lInv, tgt))
              else // overtaken
                None
          }

        rContent match {
          case l: LDefined[B] =>
            (goL(l), AllDone)
          case BiDef(l, r) =>
            goL(l) match
              case Some(l) =>
                val (content, res) = combine(rCell, l, r)
                (Some(content), res)
              case None =>
                (None, AllDone)
        }
  }

  def completeLInvTgtRFwdContraction[A, B]: (CellContent[B], ContractLInvTgtRFwd[A, B]) => (Option[CellContent[B]], ActionResult) =
    ???

  def consumeSlatedCell[A, B, C]: (CellContent[B], ConsumeSlatedCell[A, B, C]) => (Option[CellContent[B]], ActionResult) = {
    (mContent, cells) =>
      val ConsumeSlatedCell(lCell, mCell, rCell) = cells
      mContent match {
        case Slated(l, r) =>
          // check that `l` still points to `lCell`
          l match {
            case LFwd(`lCell`) =>
            case LRole(`lCell`, _) =>
            case LInvertTgt(`lCell`, _) =>
            case LInvertSrc(_, `lCell`) =>
          }
          // check that `r` still points to `rCell`
          r match {
            case RFwd(`rCell`) =>
            case RRole(_, `rCell`) =>
            case RInvertTgt(`rCell`, _) =>
            case RInvertSrc(_, `rCell`) =>
            case JoinPong1(`rCell`) =>
            case JoinPong2(`rCell`) =>
          }
          (Some(Consumed()), AllDone)
      }
  }

  def awaitDone(
    content: CellContent[Done],
    cellAndListener: (Cell[Done], Either[Throwable, Unit] => Unit),
  ): (CellContent[Done], ActionResult) = {
    val (cell, listener) = cellAndListener
    content match
      case Empty() =>
        (DoneCallback(listener), AllDone)
      case j: JoinOf =>
        (BiDef(j, DoneCallback(listener)), AllDone)
      case LFwd(tgt) =>
        (Consumed(), RefineRFwd(tgt, expectedRTarget = cell, DoneCallback(listener)))
      case l: LBypassing[Done] =>
        (BiDef(l, DoneCallback(listener)), AllDone)
  }

  def awaitEither[A, B](
    eitherContent: CellContent[A |+| B],
    cellAndListener: (Cell[A |+| B], Either[Throwable, Either[Cell[A], Cell[B]]] => Unit),
  ): (CellContent[A |+| B], ActionResult) = {
    val (eitherCell, listener) = cellAndListener
    eitherContent match {
      case Empty() =>
        (EitherCallback(listener), AllDone)
      case InjectedL(cell) =>
        (Consumed(), CallbackTriggered(listener, Right(Left(cell))))
      case InjectedR(cell) =>
        (Consumed(), CallbackTriggered(listener, Right(Right(cell))))
      case LFwd(tgt) =>
        (Consumed(), RefineRFwd(tgt, expectedRTarget = eitherCell, EitherCallback(listener)))
      case EitherCallback(_) | Consumed() =>
        throw new IllegalStateException("Double observer")
    }
  }

  private def addr[X](c: Cell[X]): String =
    "@" + System.identityHashCode(c).toHexString
}

sealed trait Link[A, B]

case class Fwd[A]() extends Link[A, A]

/** Role an `A`-cell plays in a `B`-cell to its right. */
enum RoleR[A, B] extends Link[A, B] {
  case Joiner1 extends RoleR[Done, Done]
  case Joiner2 extends RoleR[Done, Done]
  case NeedNotification extends RoleR[Pong, Need]
  case NotifyNeedTgt extends RoleR[Need, Need]
  case ChoiceNotification[A, B]() extends RoleR[Pong, A |&| B]
  case NotifyChoiceTgt[A, B]() extends RoleR[A |&| B, A |&| B]
}

/** Role a `B`-cell plays in an `A`-cell to its left. */
enum RoleL[A, B] extends Link[A, B] {
  case JoinerPong1 extends RoleL[Pong, Pong]
  case JoinerPong2 extends RoleL[Pong, Pong]
  case SelectContestant1 extends RoleL[One |&| One, Pong]
  case SelectContestant2 extends RoleL[One |&| One, Pong]
  case EitherNotification[A, B]() extends RoleL[A |+| B, Ping]
  case NotifyEitherTgt[A, B]() extends RoleL[A |+| B, A |+| B]
}

enum Iso[A, B] {
  case Id[A]() extends Iso[A, A]
  case NeedInvDone extends Iso[Need, -[Done]]
  case InvDoneNeed extends Iso[-[Done], Need]
  case PongInvPing extends Iso[Pong, -[Ping]]
  case InvPingPong extends Iso[-[Ping], Pong]

  def reverse: Iso[B, A] =
    this match
      case Id() => Id()
      case NeedInvDone => InvDoneNeed
      case InvDoneNeed => NeedInvDone
      case PongInvPing => InvPingPong
      case InvPingPong => PongInvPing

  def andThen[C](that: Iso[B, C]): Iso[A, C] =
    (this, that) match
      case (Id(), that) => that
      case (thiz, Id()) => thiz
      case (InvDoneNeed, NeedInvDone) => Id()
      case (NeedInvDone, InvDoneNeed) => Id()
      case (InvPingPong, PongInvPing) => Id()
      case (PongInvPing, InvPingPong) => Id()
}

enum Inversion[A, B] {
  case DoneNeed extends Inversion[Done, Need]
  case PingPong extends Inversion[Ping, Pong]
  case Universal[A]() extends Inversion[A, -[A]]

  def alsoTo[X](that: Inversion[A, X]): Iso[B, X] =
    (this, that) match {
      case (DoneNeed, DoneNeed)       => Iso.Id()
      case (PingPong, PingPong)       => Iso.Id()
      case (Universal(), Universal()) => Iso.Id()
      case (DoneNeed, Universal())    => Iso.NeedInvDone
      case (Universal(), DoneNeed)    => Iso.InvDoneNeed
      case (PingPong, Universal())    => Iso.PongInvPing
      case (Universal(), PingPong)    => Iso.InvPingPong
    }

  def alsoFrom[X](that: Inversion[X, B]): A =:= X =
    (this, that) match {
      case (DoneNeed, DoneNeed)       => summon
      case (PingPong, PingPong)       => summon
      case (Universal(), Universal()) => summon
    }
}
