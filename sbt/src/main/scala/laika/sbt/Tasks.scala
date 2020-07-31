/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laika.sbt

import java.util.concurrent.Executors

import cats.effect.{Blocker, ContextShift, IO}
import cats.implicits._
import laika.api.Renderer
import laika.ast.Path.Root
import laika.factory.{BinaryPostProcessor, RenderFormat, TwoPhaseRenderFormat}
import laika.format._
import laika.io.config.SiteConfig
import laika.io.implicits._
import laika.io.model._
import laika.rewrite.nav.ChoiceGroupsConfig
import laika.sbt.LaikaPlugin.ArtifactDescriptor
import laika.sbt.LaikaPlugin.autoImport._
import sbt.Keys._
import sbt._
import sbt.util.CacheStore

import scala.concurrent.ExecutionContext

/** Implementations for Laika's sbt tasks.
  *
  * @author Jens Halm
  */
object Tasks {

  import Def._

  implicit lazy val processingContext: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  lazy val blocker: Blocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(Executors.newCachedThreadPool()))

  /** Generates and copies the API documentation to the target directory of the site task.
    * Does nothing if the `laikaIncludeAPI` setting is set to false (the default).
    */
  val generateAPI: Initialize[Task[Seq[String]]] = taskDyn {
    val config = Settings.parser.value.config.baseConfig
    val targetDir = (laikaSite / target).value / SiteConfig.apiPath(config).relativeTo(Root).toString
    if (laikaIncludeAPI.value) task {

      val cacheDir = streams.value.cacheDirectory / "laika" / "api"
      val apiMappings = (laikaGenerateAPI / sbt.Keys.mappings).value
      val targetMappings = apiMappings map { case (file, name) => (file, targetDir / name) }

      Sync.sync(CacheStore(cacheDir))(targetMappings)

      streams.value.log.info("Copied API documentation to " + targetDir)
      apiMappings.map(_._2)
    }
    else task {
      sbt.IO.delete(targetDir)
      Nil
    }
  }
  
  /** The main transformation task of the sbt plugin.
    *
    * It accepts one or more arguments specifying the output formats. Valid arguments
    * are `html`, `pdf`, `xsl-fo` and `ast`.
    *
    * The implementation parses the input document only once and then uses the AST
    * obtained from the parsing operation to feed all specified renderers.
    *
    * The implementation is slightly bulky due to the nature of the sbt macros
    * which would require any method being factored out to be tasks, too, which
    * is not always desirable.
    */
  val generate: Initialize[InputTask[Set[File]]] = inputTask {

    val formats = spaceDelimited("<format>").parsed.map(OutputFormat.fromString)
    if (formats.isEmpty) throw new IllegalArgumentException("At least one format must be specified")

    val userConfig = laikaConfig.value
    val parser = Settings.parser.value
    val downloadPath = (laikaSite / target).value / SiteConfig.downloadPath(parser.config.baseConfig).relativeTo(Root).toString
    val apiPath = SiteConfig.apiPath(parser.config.baseConfig)

    lazy val tree = {
      val inputs = generateAPI.value.foldLeft(laikaInputs.value.delegate) {
        (inputs, path) => inputs.addString("", apiPath / path) // TODO - temporary hack until the builder API is enhanced
      }
      val tree = parser.fromInput(inputs).parse.unsafeRunSync()

      Logs.runtimeMessages(streams.value.log, tree.root, userConfig.logMessages)

      tree.copy(staticDocuments = tree.staticDocuments.filterNot(_.path.isSubPath(apiPath))) // TODO - remove filter when the above gets fixed
    }
    
    def renderWithFormat[FMT] (format: RenderFormat[FMT], targetDir: File, formatDesc: String): Set[File] = {
      
      val apiPath = targetDir / SiteConfig.apiPath(parser.config.baseConfig).relativeTo(Root).toString
      val filesToDelete = (targetDir.allPaths --- targetDir --- 
        downloadPath.allPaths --- collectParents(downloadPath) --- apiPath.allPaths --- collectParents(apiPath)).get
      sbt.IO.delete(filesToDelete)

      if (!targetDir.exists) targetDir.mkdirs()
 
      Renderer
        .of(format)
        .withConfig(parser.config)
        .io(blocker)
        .parallel[IO]
        .build
        .from(tree.root)
        .copying(tree.staticDocuments)
        .toDirectory(targetDir)(userConfig.encoding)
        .render
        .unsafeRunSync()      

      streams.value.log.info(Logs.outputs(tree.root, formatDesc))
      streams.value.log.info(s"Generated $formatDesc in $targetDir")

      targetDir.allPaths.get.toSet.filter(_.isFile)
    }

    def renderWithProcessor[FMT] (format: TwoPhaseRenderFormat[FMT,BinaryPostProcessor], formatDesc: String): Set[File] = {
      
      downloadPath.mkdirs()
      
      val artifactDesc = ArtifactDescriptor(name.value, version.value, format.interimFormat.fileSuffix)
      val nameBuilder = laikaArtifactNameBuilder.value
      
      val roots = ChoiceGroupsConfig.createChoiceCombinations(tree.root)
      val ops = roots.map { case (root, classifiers) =>
        val classifier = if (classifiers.value.isEmpty) None else Some(classifiers.value.mkString("-"))
        val file = downloadPath / nameBuilder(artifactDesc.copy(classifier = classifier))
        Renderer
          .of(format)
          .withConfig(parser.config)
          .io(blocker)
          .parallel[IO]
          .build
          .from(root)
          .copying(tree.staticDocuments)
          .toFile(file)
          .render
          .as(file)
      }
      val res = ops.sequence.unsafeRunSync()

      res.toList.foreach { f =>
        streams.value.log.info(s"Generated $formatDesc in $f")
      }

      res.toList.toSet
    }

    val cacheDir = streams.value.cacheDirectory / "laika"
    val inputCollection = laikaInputs.value.delegate.build(parser.config.docTypeMatcher).unsafeRunSync()
    streams.value.log.info(Logs.inputs(inputCollection))
    val inputFiles = collectInputFiles(inputCollection)

    val results = formats map { format =>
      
      val cacheFormatDir = format.toString.toLowerCase

      val fun = FileFunction.cached(cacheDir / cacheFormatDir, FilesInfo.lastModified, FilesInfo.exists) { _ =>
        format match {
          case OutputFormat.HTML  => renderWithFormat(HTML, (laikaSite / target).value, "HTML")
          case OutputFormat.AST   => renderWithFormat(AST, (laikaAST / target).value, "Formatted AST")
          case OutputFormat.XSLFO => renderWithFormat(XSLFO, (laikaXSLFO / target).value, "XSL-FO")
          case OutputFormat.EPUB  => renderWithProcessor(EPUB, "EPUB")
          case OutputFormat.PDF   => renderWithProcessor(fopFactory.value.fold[PDF](PDF)(PDF.withFopFactory), "PDF")
        }
      }
      fun(inputFiles)
    }

    val outputFiles = results.reduce(_ ++ _)
    outputFiles.intersect(Settings.allTargets.value)
  }

  /** The site task combines the html generator with optionally also
    * rendering a PDF document from the same input and creating scaladoc
    * documentation and copying both over to the target directory.
    *
    * PDF rendering and API generation is triggered by the `laikaIncludeAPI`
    * and `laikaIncludePDF` settings respectively.
    */
  val site: Initialize[Task[Set[File]]] = taskDyn {
    val epub = if (laikaIncludeEPUB.value) " epub" else ""
    val pdf  = if (laikaIncludePDF.value) " pdf" else ""
    generate.toTask(" html" + epub + pdf)
  }
  
  val mappings: Initialize[Task[Seq[(File, String)]]] = task {
    sbt.Path.allSubpaths((laikaSite / target).value).toSeq
  }

  /** Packages the generated html site and (optionally) the included
    * API documentation and PDF/EPUB files into a zip archive.
    */
  val packageSite: Initialize[Task[File]] = task {
    val artifactDesc = ArtifactDescriptor(name.value, version.value, "zip")
    val artifactName = laikaArtifactNameBuilder.value(artifactDesc)
    val zipFile = (Laika / target).value / artifactName
    streams.value.log.info(s"Packaging $zipFile ...")

    sbt.IO.zip((laikaSite / sbt.Keys.mappings).value, zipFile)

    streams.value.log.info("Done packaging.")
    zipFile
  }

  /** Cleans the target directory of the site task.
    */
  val clean: Initialize[Task[Unit]] = task {
    sbt.IO.delete((laikaSite / target).value)
  }

  /** Collects all input files from the specified
    * input tree. Ignores any virtual inputs in the input trees.
    */
  def collectInputFiles (inputs: InputTree[IO]): Set[File] = 
    inputs.textInputs.flatMap(_.sourceFile).toSet ++ 
      inputs.binaryInputs.flatMap(_.sourceFile)

  /** Collects all parent directories of the specified file or directory.
    */
  def collectParents (file: File): Set[File] = {
    def collect (file: File, acc: Set[File]): Set[File] = {
      file.getParentFile match {
        case null => acc
        case p => collect(p, acc + p)
      }
    }
    collect(file, Set())
  }

  /** Enumeration of output formats supported by the plugin.
    */
  sealed trait OutputFormat

  object OutputFormat {

    case object HTML extends OutputFormat

    case object EPUB extends OutputFormat

    case object PDF extends OutputFormat

    case object XSLFO extends OutputFormat

    case object AST extends OutputFormat

    def fromString (name: String): OutputFormat = name.toLowerCase match {
      case "html" => HTML
      case "epub" => EPUB
      case "pdf" => PDF
      case "fo" | "xslfo" | "xsl-fo" => XSLFO
      case "formatted-ast" | "ast" => AST
      case _ => throw new IllegalArgumentException(s"Unsupported format: $name")
    }

  }

}
