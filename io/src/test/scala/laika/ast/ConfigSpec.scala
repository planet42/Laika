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

package laika.ast

import java.io.File

import cats.effect._
import laika.api.MarkupParser
import laika.ast.Path.Root
import laika.ast.sample.TestSourceBuilders
import laika.bundle.BundleProvider
import laika.config.Origin.{DocumentScope, TreeScope}
import laika.config._
import laika.format.{Markdown, ReStructuredText}
import laika.io.helper.{InputBuilder, TestThemeBuilder}
import laika.io.implicits._
import laika.io.model.{InputTreeBuilder, ParsedTree}
import laika.io.{FileIO, IOWordSpec}
import laika.rewrite.{DefaultTemplatePath, TemplateContext, TemplateRewriter}


class ConfigSpec extends IOWordSpec 
  with FileIO
  with TestSourceBuilders {


  trait Inputs extends InputBuilder  {
      
    object Contents {

      val templateWithRef =
        """<h1>${foo}</h1>
          |<div>${cursor.currentDocument.content}</div>
          |CCC""".stripMargin

      val templateWithMissingRef =
        """<h1>${foox}</h1>
          |<div>${cursor.currentDocument.content}</div>
          |CCC""".stripMargin

      val templateWithOptRef =
        """<h1>${?foox}</h1>
          |<div>${cursor.currentDocument.content}</div>
          |CCC""".stripMargin

      val templateWithConfig =
        """{% foo: bar %}
          |<div>${cursor.currentDocument.content}</div>
          |CCC""".stripMargin

      val templateWithoutConfig =
        """<div>${cursor.currentDocument.content}</div>
          |CCC""".stripMargin

      val markupWithConfig =
        """{% foo: bar %}
          |aaa
          |bbb""".stripMargin

      val markupWithConfigAfterWhitespace =
        """
          |
          |  {% foo: bar %}
          |aaa
          |bbb""".stripMargin

      val markupWithPathConfig =
        """{% foo: ../foo.txt %}
          |aaa
          |bbb""".stripMargin

      val markupWithArrayConfig =
        """{% foo: [a,b,c] %}
          |aaa
          |bbb""".stripMargin

      val markupWithRef =
        """aaa
          |${foo}
          |bbb""".stripMargin

      val markupWithRefs =
        """aaa
          |${a}
          |${b}
          |${c}
          |bbb""".stripMargin
      
      val configDoc = """foo = bar"""
      
      val configWithCpInclude =
        """
          |a = 1
          |
          |include classpath("/config/b.conf")""".stripMargin

      def configWithFileInclude(fileName: String): String =
        s"""
          |a = 1
          |
          |include file("$fileName")""".stripMargin

      val configDocWithPath = """foo = ../foo.txt"""

      val markupWithMergeableConfig =
        """{% foo.bar: 7 %}
          |${foo.bar}
          |${foo.baz}""".stripMargin
      
      val mergeableConfig = """{ foo.baz = 9 }"""
    }
    
    def toResult (parsed: ParsedTree[IO]): RootElement = resultDoc(parsed.root).content

    def resultDoc (root: DocumentTreeRoot): Document =
      resultTree(root).content.collect{case doc: Document => doc}.head

    def resultTree (root: DocumentTreeRoot): DocumentTree =
      TemplateRewriter.applyTemplates(root, TemplateContext("html")).toOption.get.tree

    val markdownParser = MarkupParser.of(Markdown).parallel[IO].build

    val rstParser = MarkupParser.of(ReStructuredText).parallel[IO].build

    def parseMD (input: InputTreeBuilder[IO]): IO[RootElement] = parseMDTree(input).map(toResult)
    def parseMDTree (input: InputTreeBuilder[IO]): IO[ParsedTree[IO]] = markdownParser.use { p =>
      p.fromInput(input).parse
    }
    def parseRST (input: InputTreeBuilder[IO]): IO[RootElement] = rstParser.use { p =>
      p.fromInput(input).parse.map(toResult)
    }
  }
  
  "The Config parser" should {
    
    def result(span: TemplateSpan): RootElement = RootElement(
      TemplateRoot(
        TemplateString("<h1>"),
        span,
        TemplateString("</h1>\n<div>"),
        EmbeddedRoot("aaa\nbbb"),
        TemplateString("</div>\nCCC")
      )
    )

    "parse configuration sections embedded in Markdown documents" in new Inputs {
      val inputs = Seq(
        Root / "default.template.html" -> Contents.templateWithRef,
        Root / "input.md" -> Contents.markupWithConfig
      )
      val expected = result(TemplateString("bar"))
      parseMD(build(inputs)).assertEquals(expected)
    }

    "parse configuration sections embedded in Markdown documents after blank lines and whitespace" in new Inputs {
      val inputs = Seq(
        Root / "default.template.html" -> Contents.templateWithRef,
        Root / "input.md" -> Contents.markupWithConfigAfterWhitespace
      )
      val expected = result(TemplateString("bar"))
      parseMD(build(inputs)).assertEquals(expected)
    }

    "parse configuration sections embedded in reStructuredText documents" in new Inputs {
      val inputs = Seq(
        DefaultTemplatePath.forHTML -> Contents.templateWithRef,
        Root / "input.rst" -> Contents.markupWithConfig
      )
      val expected = result(TemplateString("bar"))
      parseRST(build(inputs)).assertEquals(expected)
    }

    "insert an invalid element when a required context reference is missing" in new Inputs {
      val inputs = Seq(
        DefaultTemplatePath.forHTML -> Contents.templateWithMissingRef,
        Root / "input.rst" -> Contents.markupWithConfig
      )
      val msg = RuntimeMessage(MessageLevel.Error, "Missing required reference: 'foox'")
      val src = source("${foox}", Contents.templateWithMissingRef, DefaultTemplatePath.forHTML)
      val expected = result(TemplateElement(InvalidSpan(msg, src)))
      parseRST(build(inputs)).assertEquals(expected)
    }

    "insert an empty string when an optional context reference is missing" in new Inputs {
      val inputs = Seq(
        DefaultTemplatePath.forHTML -> Contents.templateWithOptRef,
        Root / "input.rst" -> Contents.markupWithConfig
      )
      val expected = result(TemplateString(""))
      parseRST(build(inputs)).assertEquals(expected)
    }

    "make directory configuration available for references in markup" in new Inputs {
      val inputs = Seq(
        Root / "directory.conf" -> Contents.configDoc,
        DefaultTemplatePath.forHTML -> Contents.templateWithoutConfig,
        Root / "input.md" -> Contents.markupWithRef
      )
      val expected = RootElement(
        TemplateRoot(
          TemplateString("<div>"),
          EmbeddedRoot("aaa\nbar\nbbb"),
          TemplateString("</div>\nCCC")
        )
      )
      parseMD(build(inputs)).assertEquals(expected)
    }

    "include classpath resources in directory configuration" in new Inputs {
      val inputs = Seq(
        Root / "directory.conf" -> Contents.configWithCpInclude,
        DefaultTemplatePath.forHTML -> Contents.templateWithoutConfig,
        Root / "input.md" -> Contents.markupWithRefs
      )
      val expected = RootElement(
        TemplateRoot(
          TemplateString("<div>"),
          EmbeddedRoot("aaa\n1\n2\n3\nbbb"),
          TemplateString("</div>\nCCC")
        )
      )
      parseMD(build(inputs)).assertEquals(expected)
    }

    "include file resources in directory configuration" in new Inputs {
      def inputs(file: File) = Seq(
        Root / "directory.conf" -> Contents.configWithFileInclude(file.getPath),
        DefaultTemplatePath.forHTML -> Contents.templateWithoutConfig,
        Root / "input.md" -> Contents.markupWithRefs
      )
      val expected = RootElement(
        TemplateRoot(
          TemplateString("<div>"),
          EmbeddedRoot("aaa\n1\n2\n3\nbbb"),
          TemplateString("</div>\nCCC")
        )
      )
      val bConf =
        """include "c.conf" 
          |
          |b = 2
        """.stripMargin
      
      val res = for {
        tempDir <- newTempDirectory
        conf    =  new File(tempDir, "b.conf")
        _       <- writeFile(conf, bConf)
        _       <- writeFile(new File(tempDir, "c.conf"), "c = 3")
        res     <- parseMD(build(inputs(conf)))
      } yield res
      
      res.assertEquals(expected)
    }

    "merge objects from config headers in markup with objects in directory configuration" in new Inputs {
      val inputs = Seq(
        Root / "directory.conf" -> Contents.mergeableConfig,
        DefaultTemplatePath.forHTML -> Contents.templateWithoutConfig,
        Root / "input.md" -> Contents.markupWithMergeableConfig
      )
      parseMDTree(build(inputs)).asserting { tree => 
        val doc = tree.root.tree.content.head.asInstanceOf[Document]
        doc.config.get[ConfigValue]("foo").toOption.get.asInstanceOf[ObjectValue].values.sortBy(_.key) should be(Seq(
          Field("bar", LongValue(7), Origin(DocumentScope, Root / "input.md")),
          Field("baz", LongValue(9), Origin(TreeScope, Root / "directory.conf"))
        ))
      }
    }

    "decode merged objects as a Map" in new Inputs {
      val inputs = Seq(
        Root / "directory.conf" -> Contents.mergeableConfig,
        DefaultTemplatePath.forHTML -> Contents.templateWithoutConfig,
        Root / "input.md" -> Contents.markupWithMergeableConfig
      )
      parseMDTree(build(inputs)).asserting { tree =>
        val doc = tree.root.tree.content.head.asInstanceOf[Document]
        doc.config.get[Map[String, Int]]("foo").toOption.get.toSeq.sortBy(_._1) should be(Seq(
          ("bar", 7),
          ("baz", 9)
        ))
      }
    }

    "make directory configuration available for references in templates" in new Inputs {
      val inputs = Seq(
        Root / "directory.conf" -> Contents.configDoc,
        DefaultTemplatePath.forHTML -> Contents.templateWithRef,
        Root / "input.rst" -> "txt"
      )
      val expected = RootElement(
        TemplateRoot(
          TemplateString("<h1>"),
          TemplateString("bar"),
          TemplateString("</h1>\n<div>"),
          EmbeddedRoot("txt"),
          TemplateString("</div>\nCCC")
        )
      )
      parseRST(build(inputs)).assertEquals(expected)
    }

    "merge configuration found in documents, templates, directories, programmatic setup, bundles and themes" in new Inputs {

      val template =
        """{% key2: val2 %}
          |${key1}
          |${key2}
          |${key3}
          |${key4}
          |${key5}
          |${key6}""".stripMargin

      val md =
        """{% key1: val1 %}
          |aaa""".stripMargin

      val config3 = "key3: val3"
      val config4 = "key4: val4"
      val config5 = "key5: val5"
      val config6 = "key6: val6"

      val inputs = Seq(
        Root / "directory.conf" -> config4,
        Root / "dir" / DefaultTemplatePath.forHTML.relative -> template,
        Root / "dir" / "directory.conf" -> config3,
        Root / "dir" / "input.md" -> md,
      )

      val expected = RootElement(
        TemplateRoot(
          (1 to 6) map (n => List(TemplateString("val" + n))) reduce (_ ++ List(TemplateString("\n")) ++ _)
        )
      )

      MarkupParser
        .of(Markdown)
        .using(BundleProvider.forConfigString(config5))
        .parallel[IO]
        .withTheme(TestThemeBuilder.forBundle(BundleProvider.forConfigString(config6)))
        .build
        .use { p =>
          p.fromInput(build(inputs)).parse
        }
        .map(p => resultTree(p.root))
        .asserting { tree =>
          tree.selectDocument(RelativePath.CurrentTree / "dir" / "input.md").get.content should be(expected)
        }
      
    }

    "decode a path in a document config header" in new Inputs {
      val inputs = Seq(
        Root / "dir" / "input.md" -> Contents.markupWithPathConfig
      )

      parseMDTree(build(inputs))
        .map(p => resultTree(p.root))
        .asserting { tree =>
          val doc = tree.selectDocument(RelativePath.CurrentTree / "dir" / "input.md")
          doc.get.config.get[Path]("foo") shouldBe Right(Root / "foo.txt")
        }
    }

    "decode a local path in a document config header" in new Inputs {
      val markupWithPathConfig =
        """{% foo: "#ref" %}
          |aaa
          |bbb""".stripMargin
      val inputs = Seq(
        Root / "dir" / "input.md" -> markupWithPathConfig
      )

      parseMDTree(build(inputs))
        .map(p => resultTree(p.root))
        .asserting { tree =>
          val doc = tree.selectDocument(RelativePath.CurrentTree / "dir" / "input.md")
          doc.get.config.get[Path]("foo") shouldBe Right(Root / "dir" / "input.md#ref")
        }
    }

    "decode a path in a directory config file in a nested directory" in new Inputs {
      val inputs = Seq(
        Root / "dir" / "directory.conf" -> Contents.configDocWithPath
      )

      parseMDTree(build(inputs))
        .map(p => resultTree(p.root))
        .asserting { tree =>
          val subTree = tree.selectSubtree(RelativePath.CurrentTree / "dir")
          subTree.get.config.get[Path]("foo") shouldBe Right(Root / "foo.txt")
        }
      
    }

    "decode an array element in a document config header" in new Inputs {
      val inputs = Seq(
        Root / "dir" / "input.md" -> Contents.markupWithArrayConfig
      )

      parseMDTree(build(inputs))
        .map(p => resultTree(p.root))
        .asserting { tree =>
          val doc = tree.selectDocument(RelativePath.CurrentTree / "dir" / "input.md")
          doc.get.config.get[String]("foo.2") shouldBe Right("c")
        }
    }
  }
}
