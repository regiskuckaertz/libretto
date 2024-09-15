package libretto.lambda.examples.workflow.generic.vis

import libretto.lambda.util.Exists

trait Approximation[approximates[_, _]] {
  /** The coarsest approximation, lumping everything into a single wire. */
  def lump[A]: Wire `approximates` A

  extension [X, A](x: X `approximates` A)
    infix def unify[Y](y: Y `approximates` A): Exists[[Z] =>> (
      Z `approximates` A,
      Z Refines X,
      Z Refines Y,
    )]
}
