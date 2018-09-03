// See LICENSE for license details.

package firrtl

import logger._
import java.io.Writer

import annotations._

import scala.collection.mutable
import firrtl.annotations._
import firrtl.ir.{Circuit, Expression}
import firrtl.Utils.{error, throwInternalError}
import firrtl.annotations.TargetToken._
import firrtl.annotations.transforms.{EliminateTargetPaths, ResolvePaths}

object RenameMap {
  def apply(map: Map[Target, Seq[Target]]) = {
    val rm = new RenameMap
    rm.addMap(map)
    rm
  }
  def apply() = new RenameMap
}
/** Map old names to new names
  *
  * Transforms that modify names should return a [[RenameMap]] with the [[CircuitState]]
  * These are mutable datastructures for convenience
  */
// TODO This should probably be refactored into immutable and mutable versions
final class RenameMap private () {
  private val underlying = mutable.HashMap[Target, Seq[Target]]()

  private val sensitivity = mutable.HashSet[Target]()

  def recordSensitivity(from: Target, to: Target): Unit = {
    sensitivity ++= (from.levels.toSet -- to.levels)
  }

  def apply(t: Target): Seq[Target] = get(t).getOrElse(Seq(t))

  /** Recursive Get
    *
    *
    *
    * @param set
    * @param cache
    * @param errors
    * @param key
    * @return
    */
  private def recursiveGet(set: mutable.HashSet[Target], cache: mutable.HashMap[Target, Seq[Target]], errors: mutable.ArrayBuffer[String])(key: Target): Seq[Target] = {
    if(cache.contains(key)) {
      cache(key)
    } else {
      // First, check whole key
      val remapped = underlying.getOrElse(key, Seq(key))

      // If it matches, then
      val all = key +: remapped
      val isCircuitNames = (key +: remapped).forall(_.isCircuitName)
      val isModuleNames = (key +: remapped).forall(_.isModuleName)
      val isReferences = (key +: remapped).forall(_.isReference)

      val checked = remapped
        // If remapped doesn't share structure of key
        //if(!(all.forall(_.isCircuitName) || all.forall(_.isModuleName) || all.forall(_.isReference))) {
        //  // If remapped is
        //  if(key.isReference && remapped.forall(r => r.isModuleName || r.isReference)) {
        //    // Ok to map from references to modules
        //  } else {
        //    errors += s"Cannot rename from $key to $remapped!"
        //  }
        //  Seq(key)
        //} else if(key.isCircuitName && remapped.size > 1) {
        //  errors += s"Cannot rename from $key to $remapped!"
        //  Seq(key)
        //} else remapped

      if(set.contains(key) && !key.isCircuitName) {
        throwInternalError(s"Bad rename: $key is in $set")
      }
      //println("  " * set.size + key.serialize)

      set += key
      val getter = recursiveGet(set, cache, errors)(_)
      val ret = checked.flatMap {

        /** If t is a CircuitTarget, return it */
        case t @ CircuitTarget(_) =>
          Seq(t)

        /** If t is a ModuleTarget, return it, check changes to CircuitTarget */
        case t @ ModuleTarget(_, module) =>
          getter(t.circuitTarget).map(_.module(module))

        /** If t is a PathlessComponentTarget with a root reference, check ModuleTarget */
        case t @ PathlessComponentTarget(_, _, Seq(Ref(r))) =>
          getter(t.moduleTarget).map(_.ref(r))

        /** If t is a PathlessComponentTarget with fields/indexes, check parent reference */
        case t @ PathlessComponentTarget(_, _, tokens) =>
          getter(t.parentReference).map(x => x.add(tokens.last))

        /** If t is an InstanceTarget (has a path) but has no references:
          * 1) Check whether the reference to the instance has been renamed
          * 2) Check whether the ofModule of the instance has been renamed (only 1:1 renaming is ok)
          * 3) Check whether the path to t has been changed
          * */
        case t @ InstanceTarget(_, module, path) =>
          val (instance, of) = path.last

          // 1) Check whether the reference to the instance has been renamed
          val renamedInstance = getter(t.remove(2).ref(instance.value)).map{
            case c @ ComponentTarget(_, _, _, Seq(Ref(newRef))) => c.remove(1).instOf(newRef, of.value)
          }

          // 2) Check whether the ofModule of the instnace has been renamed
          // If the module has a 1:1 renaming, it is obvious which module the instance is referring to
          // If the module has a 1:0 or 1:N renaming, it is unclear how to rename and thus do nothing
          // TODO: Determine whether these semantics are ok
          val renamedOfModule = getter(t.circuitTarget.module(of.value)) match {
            case Seq(ModuleTarget(_, newOfModule)) => renamedInstance.map(_.remove(1).of(newOfModule))
            case other => renamedInstance
          }

          // 3) Check changes to the parent path
          renamedOfModule.flatMap {
            case x @ InstanceTarget(_, _, path) =>
              val (newI, newO) = path.last
              getter(x.remove(2)).map(_.instOf(newI.value, newO.value))
            case other => throwInternalError("Bad?")
          }

        /** If t is a ComponentTarget:
          * 1) Check child path to reference
          * 2) Check path to instance containing reference
          */
        case t @ ComponentTarget(_, module, path, reference) =>
          val (i, o) = path.head
          val inlined = getter(t.stripHierarchy(1)).map { _.addHierarchy(module, i.value) }
          val ret = inlined.flatMap { x =>
            if(sensitivity.intersect(x.levels.toSet).nonEmpty) getter(x.pathTarget).map(_.addAll(x.notPath)) else Seq(x)
          }
          ret
      }
      set -= key
      cache(key) = ret
      ret
    }
  }

  /** Get renames of a [[Target]]
    *
    * @note A [[Target]] can only be renamed to one-or-more [[Target]]s
    */
  def get(key: Target): Option[Seq[Target]] = {
    val errors = mutable.ArrayBuffer[String]()
    require(key.isComplete, "Can only rename a complete target")
    //println(underlying.map{case (k, v) => k.serialize -> v.map(_.serialize)})
    if(hasChanges /*&& (circuitsWithChanges.contains(key.circuitOpt.get) || modulesWithChanges.intersect(key.moduleSensitivity).nonEmpty) */) {
      val ret = recursiveGet(mutable.HashSet.empty[Target], mutable.HashMap.empty[Target, Seq[Target]], errors)(key)
      if(errors.nonEmpty) throwInternalError(errors.mkString("\n"))
      if(ret.size == 1 && ret.head == key) None else Some(ret)
    } else None
  }

  def hasChanges: Boolean = underlying.nonEmpty


  // Mutable helpers
  private var circuitName: String = ""
  private var moduleName: String = ""
  def setModule(s: String) =
    moduleName = s
  def setCircuit(s: String) =
    circuitName = s
  def rename(from: String, to: String): Unit = rename(from, Seq(to))
  def rename(from: String, tos: Seq[String]): Unit = {
    val fromName = Target.convertNamed2Target(ComponentName(from, ModuleName(moduleName, CircuitName(circuitName))))
    val tosName = tos map { to =>
      Target.convertNamed2Target(ComponentName(to, ModuleName(moduleName, CircuitName(circuitName))))
    }
    rename(fromName, tosName)
  }
  def rename(from: Target, to: Target): Unit = {
    rename(from, Seq(to))
  }
  def rename(from: Target, tos: Seq[Target]): Unit = {
    require(from.isComplete, s"Cannot rename from an incomplete target: $from")
    tos.foreach { to =>
      require(to.isPathless || (to.path.size == 1 && to.notPath.isEmpty), s"Cannot rename to a target with a path: $to")
      require(to.isComplete, s"Cannot rename to an incomplete target: $to")
    }
    def check(from: Target, to: Target)(t: Target): Unit = {
      require(from != t, s"Cannot rename $from to $to, as it is a circular constraint")
      t.foreach(check(from, to))
    }
    tos.foreach { to => check(from, to)(to) }
    (from, tos) match {
      case (x, Seq(y)) if x == y => // TODO is this check expensive in common case?
      case _ =>
        tos.foreach{recordSensitivity(from, _)}
        underlying(from) = underlying.getOrElse(from, Seq.empty) ++ tos
    }
  }

  def delete(names: Seq[String]): Unit = names.foreach(delete(_))
  def delete(name: String): Unit = {
    delete(Target(Some(circuitName), Some(moduleName), AnnotationUtils.toSubComponents(name)))
  }

  def delete(name: Target): Unit =
    underlying(name) = Seq.empty
  def addMap(map: Map[Target, Seq[Target]]) =
    underlying ++= map
  def serialize: String = underlying.map { case (k, v) =>
    k.serialize + "=>" + v.map(_.serialize).mkString(", ")
  }.mkString("\n")
}

/** Container of all annotations for a Firrtl compiler */
class AnnotationSeq private (private[firrtl] val underlying: List[Annotation]) {
  def toSeq: Seq[Annotation] = underlying.toSeq
}
object AnnotationSeq {
  def apply(xs: Seq[Annotation]) = new AnnotationSeq(xs.toList)
}

/** Current State of the Circuit
  *
  * @constructor Creates a CircuitState object
  * @param circuit The current state of the Firrtl AST
  * @param form The current form of the circuit
  * @param annotations The current collection of [[firrtl.annotations.Annotation Annotation]]
  * @param renames A map of [[firrtl.annotations.Named Named]] things that have been renamed.
  *   Generally only a return value from [[Transform]]s
  */
case class CircuitState(
    circuit: Circuit,
    form: CircuitForm,
    annotations: AnnotationSeq,
    renames: Option[RenameMap]) {

  /** Helper for getting just an emitted circuit */
  def emittedCircuitOption: Option[EmittedCircuit] =
    emittedComponents collectFirst { case x: EmittedCircuit => x }
  /** Helper for getting an [[EmittedCircuit]] when it is known to exist */
  def getEmittedCircuit: EmittedCircuit = emittedCircuitOption match {
    case Some(emittedCircuit) => emittedCircuit
    case None =>
      throw new FIRRTLException(s"No EmittedCircuit found! Did you delete any annotations?\n$deletedAnnotations")
  }

  /** Helper function for extracting emitted components from annotations */
  def emittedComponents: Seq[EmittedComponent] =
    annotations.collect { case emitted: EmittedAnnotation[_] => emitted.value }
  def deletedAnnotations: Seq[Annotation] =
    annotations.collect { case anno: DeletedAnnotation => anno }

  def resolvePaths(targets: Seq[Target]): CircuitState = {
    val newCS = new EliminateTargetPaths().runTransform(this.copy(annotations = ResolvePaths(targets) +: annotations ))
    newCS.copy(form = form)
  }

  def resolvePathsOf(annoClasses: Class[_]*): CircuitState = {
    val targets = getAnnotationsOf(annoClasses:_*).flatMap(_.getTargets)
    if(targets.nonEmpty) resolvePaths(targets) else this
  }

  def getAnnotationsOf(annoClasses: Class[_]*): AnnotationSeq = {
    annotations.collect { case a if annoClasses.contains(a.getClass) => a }
  }
}
object CircuitState {
  def apply(circuit: Circuit, form: CircuitForm): CircuitState = apply(circuit, form, Seq())
  def apply(circuit: Circuit, form: CircuitForm, annotations: AnnotationSeq) =
    new CircuitState(circuit, form, annotations, None)
}

/** Current form of the Firrtl Circuit
  *
  * Form is a measure of addition restrictions on the legality of a Firrtl
  * circuit.  There is a notion of "highness" and "lowness" implemented in the
  * compiler by extending scala.math.Ordered. "Lower" forms add additional
  * restrictions compared to "higher" forms. This means that "higher" forms are
  * strictly supersets of the "lower" forms. Thus, that any transform that
  * operates on [[HighForm]] can also operate on [[MidForm]] or [[LowForm]]
  */
sealed abstract class CircuitForm(private val value: Int) extends Ordered[CircuitForm] {
  // Note that value is used only to allow comparisons
  def compare(that: CircuitForm): Int = this.value - that.value
}
/** Chirrtl Form
  *
  * The form of the circuit emitted by Chisel. Not a true Firrtl form.
  * Includes cmem, smem, and mport IR nodes which enable declaring memories
  * separately form their ports. A "Higher" form than [[HighForm]]
  *
  * See [[CDefMemory]] and [[CDefMPort]]
  */
final case object ChirrtlForm extends CircuitForm(3)
/** High Form
  *
  * As detailed in the Firrtl specification
  * [[https://github.com/ucb-bar/firrtl/blob/master/spec/spec.pdf]]
  *
  * Also see [[firrtl.ir]]
  */
final case object HighForm extends CircuitForm(2)
/** Middle Form
  *
  * A "lower" form than [[HighForm]] with the following restrictions:
  *  - All widths must be explicit
  *  - All whens must be removed
  *  - There can only be a single connection to any element
  */
final case object MidForm extends CircuitForm(1)
/** Low Form
  *
  * The "lowest" form. In addition to the restrictions in [[MidForm]]:
  *  - All aggregate types (vector/bundle) must have been removed
  *  - All implicit truncations must be made explicit
  */
final case object LowForm extends CircuitForm(0)
/** Unknown Form
  *
  * Often passes may modify a circuit (e.g. InferTypes), but return
  * a circuit in the same form it was given.
  *
  * For this use case, use UnknownForm. It cannot be compared against other
  * forms.
  *
  * TODO(azidar): Replace with PreviousForm, which more explicitly encodes
  * this requirement.
  */
final case object UnknownForm extends CircuitForm(-1) {
  override def compare(that: CircuitForm): Int = { sys.error("Illegal to compare UnknownForm"); 0 }
}

/** The basic unit of operating on a Firrtl AST */
abstract class Transform extends LazyLogging {
  /** A convenience function useful for debugging and error messages */
  def name: String = this.getClass.getSimpleName
  /** The [[firrtl.CircuitForm]] that this transform requires to operate on */
  def inputForm: CircuitForm
  /** The [[firrtl.CircuitForm]] that this transform outputs */
  def outputForm: CircuitForm
  /** Perform the transform, encode renaming with RenameMap, and can
    *   delete annotations
    * Called by [[runTransform]].
    *
    * @param state Input Firrtl AST
    * @return A transformed Firrtl AST
    */
  protected def execute(state: CircuitState): CircuitState

  /** Convenience method to get annotations relevant to this Transform
    *
    * @param state The [[CircuitState]] form which to extract annotations
    * @return A collection of annotations
    */
  @deprecated("Just collect the actual Annotation types the transform wants", "1.1")
  final def getMyAnnotations(state: CircuitState): Seq[Annotation] = {
    val msg = "getMyAnnotations is deprecated, use collect and match on concrete types"
    Driver.dramaticWarning(msg)
    state.annotations.collect { case a: LegacyAnnotation if a.transform == this.getClass => a }
  }

  def prepare(state: CircuitState): CircuitState = state

  /** Perform the transform and update annotations.
    *
    * @param state Input Firrtl AST
    * @return A transformed Firrtl AST
    */
  final def runTransform(state: CircuitState): CircuitState = {
    logger.info(s"======== Starting Transform $name ========")

    val (timeMillis, result) = Utils.time { execute(prepare(state)) }

    logger.info(s"""----------------------------${"-" * name.size}---------\n""")
    logger.info(f"Time: $timeMillis%.1f ms")

    val remappedAnnotations = propagateAnnotations(state.annotations, result.annotations, result.renames)

    logger.info(s"Form: ${result.form}")
    logger.debug(s"Annotations:")
    remappedAnnotations.foreach { a =>
      logger.debug(a.serialize)
    }
    logger.trace(s"Circuit:\n${result.circuit.serialize}")
    logger.info(s"======== Finished Transform $name ========\n")
    CircuitState(result.circuit, result.form, remappedAnnotations, None)
  }

  /** Propagate annotations and update their names.
    *
    * @param inAnno input AnnotationSeq
    * @param resAnno result AnnotationSeq
    * @param renameOpt result RenameMap
    * @return the updated annotations
    */
  final private def propagateAnnotations(
      inAnno: AnnotationSeq,
      resAnno: AnnotationSeq,
      renameOpt: Option[RenameMap]): AnnotationSeq = {
    val newAnnotations = {
      val inSet = mutable.LinkedHashSet() ++ inAnno
      val resSet = mutable.LinkedHashSet() ++ resAnno
      val deleted = (inSet -- resSet).map {
        case DeletedAnnotation(xFormName, delAnno) => DeletedAnnotation(s"$xFormName+$name", delAnno)
        case anno => DeletedAnnotation(name, anno)
      }
      val created = resSet -- inSet
      val unchanged = resSet & inSet
      (deleted ++ created ++ unchanged)
    }

    // For each annotation, rename all annotations.
    val renames = renameOpt.getOrElse(RenameMap())
    for {
      anno <- newAnnotations.toSeq
      newAnno <- anno.update(renames)
    } yield newAnno
  }
}

trait SeqTransformBased {
  def transforms: Seq[Transform]
  protected def runTransforms(state: CircuitState): CircuitState =
    transforms.foldLeft(state) { (in, xform) => xform.runTransform(in) }
}

/** For transformations that are simply a sequence of transforms */
abstract class SeqTransform extends Transform with SeqTransformBased {
  def execute(state: CircuitState): CircuitState = {
    /*
    require(state.form <= inputForm,
      s"[$name]: Input form must be lower or equal to $inputForm. Got ${state.form}")
    */
    val ret = runTransforms(state)
    CircuitState(ret.circuit, outputForm, ret.annotations, ret.renames)
  }
}

trait ResolvedAnnotationPaths {
  this: Transform =>

  val annotationClasses: Traversable[Class[_]]

  override def prepare(state: CircuitState): CircuitState = {
    state.resolvePathsOf(annotationClasses.toSeq:_*)
  }
}

/** Defines old API for Emission. Deprecated */
trait Emitter extends Transform {
  @deprecated("Use emission annotations instead", "firrtl 1.0")
  def emit(state: CircuitState, writer: Writer): Unit
}

object CompilerUtils extends LazyLogging {
  /** Generates a sequence of [[Transform]]s to lower a Firrtl circuit
    *
    * @param inputForm [[CircuitForm]] to lower from
    * @param outputForm [[CircuitForm]] to lower to
    * @return Sequence of transforms that will lower if outputForm is lower than inputForm
    */
  def getLoweringTransforms(inputForm: CircuitForm, outputForm: CircuitForm): Seq[Transform] = {
    // If outputForm is equal-to or higher than inputForm, nothing to lower
    if (outputForm >= inputForm) {
      Seq.empty
    } else {
      inputForm match {
        case ChirrtlForm =>
          Seq(new ChirrtlToHighFirrtl) ++ getLoweringTransforms(HighForm, outputForm)
        case HighForm =>
          Seq(new IRToWorkingIR, new ResolveAndCheck, new transforms.DedupModules,
              new HighFirrtlToMiddleFirrtl) ++ getLoweringTransforms(MidForm, outputForm)
        case MidForm => Seq(new MiddleFirrtlToLowFirrtl) ++ getLoweringTransforms(LowForm, outputForm)
        case LowForm => throwInternalError("getLoweringTransforms - LowForm") // should be caught by if above
        case UnknownForm => throwInternalError("getLoweringTransforms - UnknownForm") // should be caught by if above
      }
    }
  }

  /** Merge a Seq of lowering transforms with custom transforms
    *
    * Custom Transforms are inserted based on their [[Transform.inputForm]] and
    * [[Transform.outputForm]]. Custom transforms are inserted in order at the
    * last location in the Seq of transforms where previous.outputForm ==
    * customTransform.inputForm. If a customTransform outputs a higher form
    * than input, [[getLoweringTransforms]] is used to relower the circuit.
    *
    * @example
    *   {{{
    *     // Let Transforms be represented by CircuitForm => CircuitForm
    *     val A = HighForm => MidForm
    *     val B = MidForm => LowForm
    *     val lowering = List(A, B) // Assume these transforms are used by getLoweringTransforms
    *     // Some custom transforms
    *     val C = LowForm => LowForm
    *     val D = MidForm => MidForm
    *     val E = LowForm => HighForm
    *     // All of the following comparisons are true
    *     mergeTransforms(lowering, List(C)) == List(A, B, C)
    *     mergeTransforms(lowering, List(D)) == List(A, D, B)
    *     mergeTransforms(lowering, List(E)) == List(A, B, E, A, B)
    *     mergeTransforms(lowering, List(C, E)) == List(A, B, C, E, A, B)
    *     mergeTransforms(lowering, List(E, C)) == List(A, B, E, A, B, C)
    *     // Notice that in the following, custom transform order is NOT preserved (see note)
    *     mergeTransforms(lowering, List(C, D)) == List(A, D, B, C)
    *   }}}
    *
    * @note Order will be preserved for custom transforms so long as the
    * inputForm of a latter transforms is equal to or lower than the outputForm
    * of the previous transform.
    */
  def mergeTransforms(lowering: Seq[Transform], custom: Seq[Transform]): Seq[Transform] = {
    custom.foldLeft(lowering) { case (transforms, xform) =>
      val index = transforms lastIndexWhere (_.outputForm == xform.inputForm)
      assert(index >= 0 || xform.inputForm == ChirrtlForm, // If ChirrtlForm just put at front
        s"No transform in $lowering has outputForm ${xform.inputForm} as required by $xform")
      val (front, back) = transforms.splitAt(index + 1) // +1 because we want to be AFTER index
      front ++ List(xform) ++ getLoweringTransforms(xform.outputForm, xform.inputForm) ++ back
    }
  }

}

trait Compiler extends LazyLogging {
  def emitter: Emitter

  /** The sequence of transforms this compiler will execute
    * @note The inputForm of a given transform must be higher than or equal to the ouputForm of the
    *       preceding transform. See [[CircuitForm]]
    */
  def transforms: Seq[Transform]

  // Similar to (input|output)Form on [[Transform]] but derived from this Compiler's transforms
  def inputForm = transforms.head.inputForm
  def outputForm = transforms.last.outputForm

  private def transformsLegal(xforms: Seq[Transform]): Boolean =
    if (xforms.size < 2) {
      true
    } else {
      xforms.sliding(2, 1)
            .map { case Seq(p, n) => n.inputForm >= p.outputForm }
            .reduce(_ && _)
    }

  assert(transformsLegal(transforms),
    "Illegal Compiler, each transform must be able to accept the output of the previous transform!")

  /** Perform compilation
    *
    * @param state The Firrtl AST to compile
    * @param writer The java.io.Writer where the output of compilation will be emitted
    * @param customTransforms Any custom [[Transform]]s that will be inserted
    *   into the compilation process by [[CompilerUtils.mergeTransforms]]
    */
  @deprecated("Please use compileAndEmit or other compile method instead", "firrtl 1.0")
  def compile(state: CircuitState,
              writer: Writer,
              customTransforms: Seq[Transform] = Seq.empty): CircuitState = {
    val finalState = compileAndEmit(state, customTransforms)
    writer.write(finalState.getEmittedCircuit.value)
    finalState
  }

  /** Perform compilation and emit the whole Circuit
    *
    * This is intended as a convenience method wrapping up Annotation creation for the common case.
    * It creates a [[EmitCircuitAnnotation]] that will be consumed by this Transform's emitter. The
    * [[EmittedCircuit]] can be extracted from the returned [[CircuitState]] via
    * [[CircuitState.emittedCircuitOption]]
    *
    * @param state The Firrtl AST to compile
    * @param customTransforms Any custom [[Transform]]s that will be inserted
    *   into the compilation process by [[CompilerUtils.mergeTransforms]]
    * @return result of compilation with emitted circuit annotated
    */
  def compileAndEmit(state: CircuitState,
                     customTransforms: Seq[Transform] = Seq.empty): CircuitState = {
    val emitAnno = EmitCircuitAnnotation(emitter.getClass)
    compile(state.copy(annotations = emitAnno +: state.annotations), customTransforms)
  }

  /** Perform compilation
    *
    * Emission will only be performed if [[EmitAnnotation]]s are present
    *
    * @param state The Firrtl AST to compile
    * @param customTransforms Any custom [[Transform]]s that will be inserted into the compilation
    *   process by [[CompilerUtils.mergeTransforms]]
    * @return result of compilation
    */
  def compile(state: CircuitState, customTransforms: Seq[Transform]): CircuitState = {
    val allTransforms = CompilerUtils.mergeTransforms(transforms, customTransforms) :+ emitter

    val (timeMillis, finalState) = Utils.time {
      allTransforms.foldLeft(state) { (in, xform) => xform.runTransform(in) }
    }

    logger.error(f"Total FIRRTL Compile Time: $timeMillis%.1f ms")

    finalState
  }

}

