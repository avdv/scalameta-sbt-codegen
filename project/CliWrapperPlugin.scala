package cliwrapper

import java.io.ByteArrayOutputStream
import java.io.PrintStream

import sbt._
import Keys._
import sbt.plugins.JvmPlugin

// generic plugin for wrapping any command-line interface as an sbt plugin
object CliWrapperPlugin extends AutoPlugin {

  def createSyntheticProject(id: String, base: File): Project =
    Project(id, base).settings(publish := {}, publishLocal := {})
  class HasMain(reflectiveMain: Main) {
    def main(args: Array[String]): Unit = reflectiveMain.main(args)
  }
  type Main = {
    def main(args: Array[String]): Unit
  }
  object autoImport {
    val cliWrapperClasspath =
      taskKey[Classpath]("classpath to run code generation in")
    val cliWrapperMainClass =
      taskKey[String]("Fully qualified name of main class")
    val cliWrapperMain =
      taskKey[HasMain]("Classloaded instance of main")
    // Returns emitted output from (stdout, stderr) while evaluating thunk.
    def cliWrapperRun[T](thunk: => T): (String, String) = {
      val out: ByteArrayOutputStream = new ByteArrayOutputStream()
      val err: ByteArrayOutputStream = new ByteArrayOutputStream()
      scala.Console.withOut(new PrintStream(out)) {
        scala.Console.withErr(new PrintStream(err)) {
          thunk
        }
      }
      out.toString -> err.toString
    }
    // (optional) wrap cliWrapperRun in this function to skip running
    // cli on files that have not changed since last run.
    def cliWrapperIncremental(init: Set[File], cacheDir: File)(
        onChange: Set[File] => Set[File]): Seq[File] = {
      def handleUpdate(inReport: ChangeReport[File],
                       outReport: ChangeReport[File]) = {
        onChange(inReport.modified -- inReport.removed)
      }
      val x = FileFunction.cached(cacheDir)(
        FilesInfo.lastModified,
        FilesInfo.lastModified)(handleUpdate)(init)
      x.toSeq
    }
  }
  import autoImport._
  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    cliWrapperMain := {
      val cp = cliWrapperClasspath.value.map(_.data.toURI.toURL)
      val cl = new java.net.URLClassLoader(cp.toArray, null)
      val cls = cl.loadClass(cliWrapperMainClass.value)
      val constuctor = cls.getDeclaredConstructor()
      constuctor.setAccessible(true)
      val main = constuctor.newInstance().asInstanceOf[Main]
      new HasMain(main)
    }
  )
}

object ClippyCodegenPlugin extends AutoPlugin {
  override def requires: Plugins = CliWrapperPlugin && JvmPlugin
  object autoImport {
    lazy val clippyCodegenIn = taskKey[Seq[File]]("--in files")
    lazy val clippyCodegenOut = settingKey[File]("--out directory")
  }
  import autoImport._
  import CliWrapperPlugin.autoImport._
  lazy val clippyCodegen = CliWrapperPlugin
    .createSyntheticProject(
      "clippyCodegen",
      file("codegen") // should be something like file("project/clippy-codgen")
    )
    .settings(
      scalaVersion := "2.11.11",
      libraryDependencies += "com.github.alexarchambault" %% "case-app" % "1.2.0-M3",
      libraryDependencies += "org.scalameta" %% "scalameta" % "1.7.0"
    )

  override def extraProjects: Seq[Project] = Seq(clippyCodegen)

  override def projectSettings: Seq[_root_.sbt.Def.Setting[_]] = Seq(
    clippyCodegenOut := resourceManaged.in(Compile).value / "clippy",
    clippyCodegenIn := sources.in(Compile).value.**("*.scala").get,
    // can be managedClasspath if clippyCodegen project has no main.
    cliWrapperClasspath := fullClasspath.in(clippyCodegen, Compile).value,
    cliWrapperMainClass := "mycodegen.Codegen$",
    resourceGenerators.in(Compile) += Def.task {
      val log = streams.value.log
      val cache = streams.value.cacheDirectory / "clippy-codegen"
      val in = clippyCodegenIn.value.toSet
      cliWrapperIncremental(in, cache)(_.map {
        in =>
          val relative =
            in.relativeTo(baseDirectory.value).getOrElse(in).toPath
          val out =
            clippyCodegenOut.value.toPath
              .resolve(relative.toString + ".clippy")
          val args =
            Array("--in", in.getAbsolutePath, "--out", out.toString)
          log.info(s"Running codegen for $in")
          cliWrapperRun(cliWrapperMain.value.main(args))
          out.toFile
      })
    }
  )
}
