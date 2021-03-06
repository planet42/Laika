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

import laika.ast.RelativePath.CurrentDocument
import laika.rewrite.nav.TargetFormats

/** Represents a target that can be referred to by links, either within the virtual tree or external.
  */
sealed trait Target {
  def render (internalTargetsAbsolute: Boolean = false): String
}

object Target {

  /** Creates a new target by parsing the specified URL.
    *
    * If the target is an absolute URL (starting with '/' or 'http'/'https') the
    * result will be an external target. 
    *
    * Relative URLs will be interpreted as pointing to the target within the virtual tree of input and output
    * documents and will be validated during transformation, 
    * resulting in errors if the target does not exist. 
    *
    * External targets on the other hand are not validated, 
    * as the availability of the external resource during validation cannot be guaranteed.
    */
  def parse (url: String): Target =
    if (url.startsWith("http:") || url.startsWith("https:") || url.startsWith("/")) ExternalTarget(url)
    else RelativeInternalTarget(RelativePath.parse(url))

  private[laika] def parseInternal (url: String): Either[RelativeInternalTarget, ExternalTarget] =
    if (url.startsWith("http:") || url.startsWith("https:") || url.startsWith("/")) Right(ExternalTarget(url))
    else Left(RelativeInternalTarget(RelativePath.parse(url)))

}

/** An external link, outside of the virtual tree of the current transformation.
  */
case class ExternalTarget (url: String) extends Target {
  def render (internalTargetsAbsolute: Boolean = false): String = url
}

/** Represents a target within the virtual tree that can be referred to by links.
  */
trait InternalTarget extends Target {
  def relativeTo (refPath: Path): ResolvedInternalTarget
}

object InternalTarget {

  /** Creates an internal target based on the specified relative or absolute path.
    */
  def apply (path: PathBase): InternalTarget = path match {
    case p: RelativePath => RelativeInternalTarget(p)
    case p: Path => AbsoluteInternalTarget(p)
  }

}

/** Represents a resolved internal target where both the absolute and relative path are known, 
  * the latter relative to the document that referred to the target. 
  *
  * The `internalFormats` property describes which of the output formats treat this as an internal link.
  * For other formats the link gets translated to an external target based on the `siteBaseURL` setting.
  * This might be useful for cases where some pages get rendered to a site, but not included in an e-book
  * format like EPUB and PDF.
  */
case class ResolvedInternalTarget (absolutePath: Path, 
                                   relativePath: RelativePath, 
                                   internalFormats: TargetFormats = TargetFormats.All) extends InternalTarget {
  def relativeTo (refPath: Path): ResolvedInternalTarget =
    ResolvedInternalTarget(absolutePath, absolutePath.relativeTo(refPath))
  def render (internalTargetsAbsolute: Boolean = false): String =
    if (internalTargetsAbsolute) absolutePath.toString
    else relativePath.toString
}

/** Represents a target defined by an absolute path.
  */
case class AbsoluteInternalTarget (path: Path) extends InternalTarget {
  def relativeTo (refPath: Path): ResolvedInternalTarget = ResolvedInternalTarget(path, path.relativeTo(refPath))
  def render (internalTargetsAbsolute: Boolean = false): String = path.toString
}

/** Represents a target defined by a relative path; 
  * the absolute path of such a target needs to be resolved later in the context of the containing document and its path.
  */
case class RelativeInternalTarget (path: RelativePath) extends InternalTarget {
  def relativeTo (refPath: Path): ResolvedInternalTarget = path match {
    case p: CurrentDocument => ResolvedInternalTarget(refPath / p, p)
    case p: RelativePath    => ResolvedInternalTarget(refPath.parent / p, p)
  }
  def render (internalTargetsAbsolute: Boolean = false): String = path.toString
}
