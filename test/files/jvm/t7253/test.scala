import scala.tools.partest._

import scala.tools.nsc.util.JavaClassPath
import java.io.InputStream
import org.objectweb.asm
import asm.ClassReader
import asm.tree.{ClassNode, InsnList}
import scala.collection.JavaConverters._

object Test extends BytecodeTest {
  import instructions._

  def show: Unit = {
    val instrBaseSeqs = Seq("ScalaClient_1", "JavaClient_1") map (name => instructions.fromMethod(getMethod(loadClassNode(name), "foo")))
    val instrSeqs = instrBaseSeqs map (_ filter isInvoke)
    cmpInstructions(instrSeqs(0), instrSeqs(1))
  }

  def cmpInstructions(isa: List[Instruction], isb: List[Instruction]) = {
    if (isa == isb) println("bytecode identical")
    else diffInstructions(isa, isb)
  }

  def isInvoke(node: Instruction): Boolean = {
    val opcode = node.opcode
    (opcode == "INVOKEVIRTUAL") || (opcode == "INVOKEINTERFACE")
  }
}
