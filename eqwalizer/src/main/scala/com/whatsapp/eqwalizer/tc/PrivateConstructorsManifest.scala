/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.tc

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import java.nio.file.{Files, Path, Paths}

object PrivateConstructorsManifest {
  private val ManifestName = ".eqwalizer-private-constructors"
  private val EnvName = "EQWALIZER_PRIVATE_CONSTRUCTORS"
  private val PropName = "eqwalizer.private_constructors"

  private val NameRe = "^[a-z][a-zA-Z0-9_@]*$".r

  private implicit val codec: JsonValueCodec[Map[String, String]] =
    JsonCodecMaker.make

  def load(): Map[String, Set[String]] =
    manifestPath() match {
      case Some(path) if Files.exists(path) =>
        val owners = readFromArray[Map[String, String]](Files.readAllBytes(path))
        validate(path, owners)
        owners.view.mapValues(owner => Set(owner)).toMap

      case _ =>
        System.err.println(s"***** `$ManifestName` not found *****")
        Map.empty
    }

  private def manifestPath(): Option[Path] =
    configuredPath().orElse(findInParents(Paths.get("").toAbsolutePath.normalize()))

  private def configuredPath(): Option[Path] =
    sys.props
      .get(PropName)
      .orElse(sys.env.get(EnvName))
      .map(Paths.get(_).toAbsolutePath.normalize())

  private def findInParents(start: Path): Option[Path] = {
    var dir: Path | Null = start
    while (dir != null) {
      val candidate = dir.nn.resolve(ManifestName)
      if (Files.exists(candidate))
        return Some(candidate)
      dir = dir.nn.getParent
    }
    None
  }

  private def validate(path: Path, owners: Map[String, String]): Unit =
    owners.foreach { case (record, owner) =>
      validateName(path, "record", record)
      validateName(path, "owner", owner)
    }

  private def validateName(path: Path, label: String, value: String): Unit =
    value match {
      case NameRe() =>
        ()
      case _ =>
        throw new IllegalArgumentException(
          s"$path: invalid $label name in private-constructor manifest: $value"
        )
    }
}
