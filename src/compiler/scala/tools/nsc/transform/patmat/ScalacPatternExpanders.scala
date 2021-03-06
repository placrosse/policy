/* NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author  Paul Phillips
 */

package scala
package tools
package nsc
package transform
package patmat

/** This is scalac-specific logic layered on top of the scalac-agnostic
 *  "matching products to patterns" logic defined in PatternExpander.
 */
trait ScalacPatternExpanders {
  val global: Global

  import global._
  import definitions._
  import treeInfo._

  type PatternAligned = ScalacPatternExpander#Aligned

  implicit class AlignedOps(val aligned: PatternAligned) {
    import aligned._
    def expectedTypes     = typedPatterns map (_.tpe)
    def unexpandedFormals = extractor.varargsTypes
  }
  trait ScalacPatternExpander extends PatternExpander[Tree, Type] {
    def NoPattern = EmptyTree
    def NoType    = global.NoType

    def newPatterns(patterns: List[Tree]): Patterns = patterns match {
      case init :+ last if isStar(last) => Patterns(init, last)
      case _                            => Patterns(patterns, NoPattern)
    }
    def elementTypeOf(tpe: Type) = {
      val seq = repeatedToSeq(tpe)

      ( typeOfMemberNamedHead(seq)
          orElse typeOfMemberNamedApply(seq)
          orElse definitions.elementType(ArrayClass, seq)
      )
    }
    def newExtractor(whole: Type, fixed: List[Type], repeated: Repeated): Extractor = Extractor(whole, fixed, repeated)

    // Turn Seq[A] into Repeated(Seq[A], A, A*)
    def repeatedFromSeq(seqType: Type): Repeated = {
      val elem     = elementTypeOf(seqType)
      val repeated = scalaRepeatedType(elem)

      Repeated(seqType, elem, repeated)
    }
    // Turn A* into Repeated(Seq[A], A, A*)
    def repeatedFromVarargs(repeated: Type): Repeated =
      Repeated(repeatedToSeq(repeated), repeatedToSingle(repeated), repeated)

    /** In this case we are basing the pattern expansion on a case class constructor.
     *  The argument is the MethodType carried by the primary constructor.
     */
    def applyMethodTypes(method: Type): Extractor = {
      val whole = method.finalResultType

      method.paramTypes match {
        case init :+ last if isScalaRepeatedParamType(last) => newExtractor(whole, init, repeatedFromVarargs(last))
        case tps                                            => newExtractor(whole, tps, NoRepeated)
      }
    }

    /** In this case, expansion is based on an unapply or unapplySeq method.
     *  Unfortunately the MethodType does not carry the information of whether
     *  it was unapplySeq, so we have to funnel that information in separately.
     */
    def unapplyMethodTypes(method: Type, isSeq: Boolean): Extractor = {
      val whole    = firstParamType(method)
      val result   = method.finalResultType
      val expanded = (
        if (result =:= BooleanTpe) Nil
        else typeOfMemberNamedGet(result) match {
          case rawGet if !hasSelectors(rawGet) => rawGet :: Nil
          case rawGet                          => typesOfSelectors(rawGet)
        }
      )
      expanded match {
        case init :+ last if isSeq => newExtractor(whole, init, repeatedFromSeq(last))
        case tps                   => newExtractor(whole, tps, NoRepeated)
      }
    }
  }
  object alignPatterns extends ScalacPatternExpander {
    /** Converts a T => (A, B, C) extractor to a T => ((A, B, CC)) extractor.
     */
    def tupleExtractor(extractor: Extractor): Extractor =
      extractor.copy(fixed = tupleType(extractor.fixed) :: Nil)

    private def validateAligned(tree: Tree, aligned: Aligned): Aligned = {
      import aligned._

      def owner         = tree.symbol.owner
      def offering      = extractor.offeringString
      def offerString   = if (extractor.isErroneous) "" else s" offering $offering"
      def arityExpected = ( if (extractor.hasSeq) "at least " else "" ) + productArity

      def err(msg: String)  = reporter.error(tree.pos, msg)
      def warn(msg: String) = reporter.warning(tree.pos, msg)
      def arityError(what: String) = err(s"$what patterns for $owner$offerString: expected $arityExpected, found $totalArity")

      if (isStar && !isSeq)
        err("Star pattern must correspond with varargs or unapplySeq")
      else if (elementArity < 0)
        arityError("not enough")
      else if (elementArity > 0 && !extractor.hasSeq)
        arityError("too many")

      aligned
    }

    def apply(sel: Tree, args: List[Tree]): Aligned = {
      val fn = sel match {
        case Unapplied(fn) => fn
        case _             => sel
      }
      val patterns  = newPatterns(args)
      val isUnapply = sel.symbol.name == nme.unapply
      val extractor = sel.symbol.name match {
        case nme.unapply    => unapplyMethodTypes(fn.tpe, isSeq = false)
        case nme.unapplySeq => unapplyMethodTypes(fn.tpe, isSeq = true)
        case _              => applyMethodTypes(fn.tpe)
      }

      /** Rather than let the error that is SI-6675 pollute the entire matching
       *  process, we will tuple the extractor before creation Aligned so that
       *  it contains known good values.
       */
      def productArity    = extractor.productArity
      def acceptMessage   = if (extractor.isErroneous) "" else s" to hold ${extractor.offeringString}"
      val requiresTupling = isUnapply && patterns.totalArity == 1 && productArity > 1

      if (requiresTupling && effectivePatternArity(args) == 1) {
        val sym = sel.symbol.owner
        currentRun.reporting.deprecationWarning(sel.pos, sym, s"${sym} expects $productArity patterns$acceptMessage but crushing into $productArity-tuple to fit single pattern (SI-6675)")
      }

      val normalizedExtractor = if (requiresTupling) tupleExtractor(extractor) else extractor
      validateAligned(fn, Aligned(patterns, normalizedExtractor))
    }

    def apply(tree: Tree): Aligned = tree match {
      case Apply(fn, args)   => apply(fn, args)
      case UnApply(fn, args) => apply(fn, args)
    }
  }
}
