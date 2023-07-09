package libretto.typology.toylang.types

/** Used as a phantom type representing a reference to a surrounding recursive function. */
sealed trait RecCall[A, B]

case class ScalaTypeParam(filename: String, line: Int, name: String)
case class ScalaTypeParams(values: Set[ScalaTypeParam]) {
  require(values.nonEmpty)
}
object ScalaTypeParams {
  def one(filename: String, line: Int, name: String): ScalaTypeParams =
    ScalaTypeParams(Set(ScalaTypeParam(filename, line, name)))
}
