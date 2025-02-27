package mill.scalalib

import mill.api.PathRef
import mill.{T, Task}

/**
 * A [[ScalaModule]] which is suited to be used with [[mill.define.Cross]].
 * It supports additional source directories with the scala version pattern
 * as suffix (`src-{scalaversionprefix}`), e.g.
 *
 * - src
 * - src-2.11
 * - src-2.12.3
 */
trait CrossScalaModule extends ScalaModule with CrossModuleBase {
  override def sources: T[Seq[PathRef]] = Task.Sources {
    super.sources() ++
      scalaVersionDirectoryNames.map(s => PathRef(moduleDir / s"src-$s"))
  }
}
