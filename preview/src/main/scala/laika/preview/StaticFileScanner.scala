/*
 * Copyright 2012-2021 the original author or authors.
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

package laika.preview

import java.io.File
import java.nio.file.{Files, Path => JPath}

import cats.syntax.all._
import cats.effect.Async
import laika.api.builder.OperationConfig
import laika.ast.Path
import laika.ast.Path.Root
import laika.config.ConfigException
import laika.io.config.SiteConfig
import laika.io.model.BinaryInput
import laika.io.runtime.DirectoryScanner
import laika.rewrite.Versions

private[preview] object StaticFileScanner {

  private def collect[F[_]: Async] (filePath: JPath, vPath: Path = Root): F[List[(Path, SiteResult[F])]] = {
    DirectoryScanner.scanDirectory(filePath) { paths =>
      paths.toList
        .map { path =>
          val vChild = vPath / path.getFileName.toString
          def result: (Path, SiteResult[F]) = (vChild, StaticResult(BinaryInput.fromFile(vPath, path.toFile).input))
          if (Files.isDirectory(path)) collect(path, vChild)
          else Async[F].pure(List(result))
        }
        .sequence
        .map(_.flatten)
    }
  }
  
  def collectVersionedFiles[F[_]: Async] (config: OperationConfig): F[Map[Path, SiteResult[F]]] = {

    def otherVersions (versions: Option[Versions]): F[List[(Path, SiteResult[F])]] = {
      (versions, versions.flatMap(_.scannerConfig)) match {
        case (Some(v), Some(scanner)) =>
          val versionRoot = new File(scanner.rootDirectory)
          (v.olderVersions ++ v.newerVersions)
            .toList
            .map { v =>
              collect(new File(versionRoot, v.pathSegment).toPath, Root / v.pathSegment)
            }
            .sequence
            .map(_.flatten)
        case _ => List.empty[(Path, SiteResult[F])].pure[F]
    }
      
    }
    
    for {
      versions <- Async[F].fromEither(config.baseConfig.getOpt[Versions].leftMap(ConfigException.apply))
      files    <- otherVersions(versions)
    } yield files.toMap
  }

  def collectAPIFiles[F[_]: Async] (config: OperationConfig, apiDir: File): F[Map[Path, SiteResult[F]]] =
    for {
      apiPath  <- Async[F].fromEither(SiteConfig.apiPath(config.baseConfig).leftMap(ConfigException.apply))
      files    <- collect(apiDir.toPath, apiPath)
    } yield files.toMap
  
}
