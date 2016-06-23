package dotty.tools
package dottydoc

import core.{ AddImplicitsPhase, DocPhase }
import dotc.config.CompilerCommand
import dotc.config.Printers.dottydoc
import dotc.core.Contexts._
import dotc.core.Phases.Phase
import dotc.typer.FrontEnd
import dotc.{ CompilationUnit, Compiler, Driver, Run }
import io.PlainFile

import scala.util.control.NonFatal

/** Custom Compiler with phases for the documentation tool
 *
 *  The idea here is to structure `dottydoc` around the new infrastructure. As
 *  such, dottydoc will itself be a compiler. It will, however, produce a format
 *  that can be used by other tools or web-browsers.
 *
 *  Example:
 *    1. Use the existing FrontEnd to typecheck the code being fed to dottydoc
 *    2. Create an AST that is serializable
 *    3. Serialize to JS-Object and write to file
 *    4. Deserialize on client side with Scala.js
 *    5. Serve content using Scala.js
 */
class DottyDocCompiler extends Compiler {
  override def phases: List[List[Phase]] =
    List(new DocFrontEnd) ::
    List(new AddImplicitsPhase) ::
    List(new DocPhase) ::
    Nil

  override def newRun(implicit ctx: Context): Run = {
    reset()
    new DocRun(this)(rootContext)
  }
}

/** TODO: this object is only temporary, should be factored out, just wanted it to demo dottydoc */
object ImplicitlyAdded {
  import dotc.core.Symbols.Symbol
  import dotc.ast.tpd.DefDef
  import scala.collection.mutable

  private var _defs: Map[Symbol, Set[Symbol]] = Map.empty
  def defs(sym: Symbol): Set[Symbol] = _defs.get(sym).getOrElse(Set.empty)

  def addDef(s: Symbol, d: Symbol): Unit = _defs = (_defs + {
    s -> _defs.get(s).map(xs => xs + d).getOrElse(Set(d))
  })
}

class DocFrontEnd extends FrontEnd {
  override protected def discardAfterTyper(unit: CompilationUnit)(implicit ctx: Context) =
    unit.isJava
}

class DocRun(comp: Compiler)(implicit ctx: Context) extends Run(comp)(ctx) {
  def fromDirectory(f: String): List[String] = {
    val file = new PlainFile(f)

    if (!file.isDirectory && f.endsWith(".scala")) List(f)
    else if (!file.isDirectory) Nil
    else file.iterator.flatMap {
      case x if x.isDirectory => fromDirectory(x.canonicalPath)
      case x => List(x.canonicalPath)
    }.toList
  }

  /** If DocRecursive is set, then try to find all scala files! */
  override def compile(fileNames: List[String]): Unit = super.compile(
    if (ctx.settings.DocRecursive.value) fileNames flatMap fromDirectory
    else fileNames
  )
}

trait DottyDoc extends Driver {
  override def setup(args: Array[String], rootCtx: Context): (List[String], Context) = {
    val ctx     = rootCtx.fresh
    val summary = CompilerCommand.distill(args)(ctx)

    ctx.setSettings(summary.sstate)
    ctx.setSetting(ctx.settings.YkeepComments, true)

    val fileNames = CompilerCommand.checkUsage(summary, sourcesRequired)(ctx)
    (fileNames, ctx)
  }

  override def newCompiler(implicit ctx: Context): Compiler = new DottyDocCompiler
}

object Main extends DottyDoc
