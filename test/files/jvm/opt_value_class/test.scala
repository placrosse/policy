import scala.tools.partest._

// import scala.tools.nsc.util.JavaClassPath
// import java.io.InputStream
// import org.objectweb.asm
// import asm.ClassReader
// import asm.tree.{ClassNode, InsnList}
// import scala.collection.JavaConverters._

object Test extends BytecodeTest {
  def show: Unit = {
    val classNode1 = loadClassNode("ValueExtract")
    val classNode2 = loadClassNode("Direct")
    sameMethodAndFieldDescriptors(classNode1, classNode2)
  }
}
