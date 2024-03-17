package kindville.lib

import kindville.{::, Box, TNil, decodeExpr}

// TODO: use an opaque type alias when
// https://github.com/scala/scala3/issues/13461#issuecomment-2002566051
// is resolved
class ExistK[K, F <: AnyKind](
  value: Box[F :: TNil, ExistK.Code[K]]
) {
  /** Returns `[R] => ([A, ...] => F[A, ...] => R) => R`. */
  transparent inline def visit: Any =
    Box.unpack(value)
}

object ExistK {
  private[lib] type Code[K] =
    [⋅⋅[_]] =>> [F0[_]] =>>
      [R] => ([A <: ⋅⋅[K]] => F0[A] => R) => R

  /** Returns `[A, ...] => F[A, ...] => ExistK[K, F]`. */
  transparent inline def apply[K, F <: AnyKind] =
    decodeExpr[F :: TNil](
      [⋅⋅[_], F0[_]] =>
        (pack: ([R] => ([A <: ⋅⋅[K]] => F0[A] => R) => R) => Box[F :: TNil, Code[K]]) =>
          [A <: ⋅⋅[K]] => (fa: F0[A]) =>
            new ExistK[K, F](
              pack(
                [R] => (f: [X <: ⋅⋅[K]] => F0[X] => R) => f[A](fa)
              )
            )
    )(Box.pack[F :: TNil, Code[K]])



  def types[As]: ExistsTypes[As] =
    new ExistsTypes[As]

  final class ExistsTypes[As] {
    transparent inline def suchThat[K, F <: AnyKind]: Any =
      decodeExpr[F :: As :: TNil](
        [⋅⋅[_], F0[_], A <: ⋅⋅[K]] =>
          (create: [X <: ⋅⋅[K]] => F0[X] => ExistK[K, F]) =>
            create[A]
      )(apply[K, F])
  }
}
