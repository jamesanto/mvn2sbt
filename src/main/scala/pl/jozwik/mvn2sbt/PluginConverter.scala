package pl.jozwik.mvn2sbt

import org.maven.{Configuration4, Plugin}
import scala.xml.{NodeSeq, Node}
import scalaxb.DataRecord

import java.io.File

object PluginConverter {

  def toPath(path: File, rootDir: File): String = {
    val dir = path.getCanonicalPath
    val root = rootDir.getCanonicalPath
    val diff = dir.substring(root.length)
    if (diff.startsWith(File.separator)) {
      diff.substring(1)
    } else {
      diff
    }
  }

  def toPath(path: String, rootDir: File): String = toPath(new File(path), rootDir)

  type PluginConverter = (File, Plugin) => Set[String]

  val cxfConverter: PluginConverter = (rootDir, plugin) => CxfPluginConverter(rootDir).convert(plugin)

  val defaultConverter: PluginConverter = (rootDir, plugin) => Set()

  val thriftConverter = defaultConverter

  val groovyConverter = defaultConverter

  val warConverter = defaultConverter
}


case class CxfPluginConverter(rootDir: File) extends PomToSbtPluginConverter {

  import PluginConverter.toPath

  private val ignoredArgs = Set("-wsdlLocation", "-autoNameResolution")

  def convert(plugin: Plugin): Set[String] = {
    val execution = plugin.executions.get.execution
    val configuration4 = execution.map { ex =>
      ex.configuration
    }
    configuration4 match {
      case (Some(confHead: Configuration4) :: tail) =>
        val defaultOptSeq = extractMap(confHead, "defaultOptions").getOrElse("", Seq[String]())

        val wsdlOptionSeq = extractMap(confHead, "wsdlOptions", "wsdlOption")

        val wsdls = wsdlOptionSeq.map {
          case (wsdl, seq) =>
            val diff = toPath(wsdl, rootDir)
            val s = defaultOptSeq ++ seq
            s"""cxf.Wsdl(file("$diff"), Seq(${
              s.mkString("\"", "\",\"", "\"")
            }), "$diff")"""
        }

        Set( s"""cxf.wsdls :=Seq(${
          wsdls.mkString(",\n\t")
        })""")
      case _ => Set()
    }
  }

  def extractMap(confHead: Configuration4, name: String, elements: String*): Map[String, Seq[String]] = extractElement(confHead, name) match {
    case Some(node) =>
      val cast = node.value.asInstanceOf[Node]
      extractWsdlOption(cast, elements: _*)
    case _ => Map.empty
  }

  def extractElement(confHead: Configuration4, name: String): Option[DataRecord[Any]] = {
    confHead.any.find { r =>
      r.key == Some(name)
    }
  }

  private def extractNode(elem: Node, first: String, names: String*): NodeSeq =
    names.foldLeft(elem \ first)((acc, name) => acc \ name)


  private def extractWsdlOption(elem: Node, elements: String*) = {
    val wsdlOption = elements.foldLeft(elem.asInstanceOf[NodeSeq])((acc, name) => acc \ name)
    wsdlOptionFromNode(wsdlOption)
  }

  private def wsdlOptionFromNode(wsdlOption: NodeSeq): Map[String, Seq[String]] =
    wsdlOption.map(w => (extractNode(w, "wsdl").text, buildSeq(w))).toMap


  private def buildSeq(node: Node) =
    buildPackage(node) ++ buildExtraArgs(node) ++ buildBindings(node)


  private def buildPackage(node: Node) = extractNode(node, "packagenames", "packagename").flatMap(n => Seq("-p", n.text))

  private def buildExtraArgs(node: Node) = extractNode(node, "extraargs", "extraarg").map(_.text).filterNot(ignoredArgs.contains)

  private def buildBindings(node: Node) =
    extractNode(node, "bindingFiles", "bindingFile").flatMap(x => Seq("-b", toPath(x.text, rootDir)))

}


sealed trait PomToSbtPluginConverter {
  def convert(plugin: Plugin): Set[String]

}