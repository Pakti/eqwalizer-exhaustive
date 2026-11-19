package com.whatsapp.eqwalizer.tc

import com.whatsapp.eqwalizer.ast.Forms.*
import com.whatsapp.eqwalizer.ast.Id
import com.whatsapp.eqwalizer.util.Diagnostic.Diagnostic

/** Raw comments are loaded only by full-module checking, before any functions. */
object StrictDirectives {
  case class Loaded(options: Options, invalids: List[Diagnostic])

  def load(forms: List[InternalForm], options: Options): Loaded = {
    val raw = forms
      .collect { case File(path, _) => path }
      .distinct
      .map(RawEqwalizerStrictAttributes.load)
      .foldLeft(RawEqwalizerStrictAttributes()) { (lhs, rhs) =>
        RawEqwalizerStrictAttributes(
          lhs.disabledErrors ++ rhs.disabledErrors,
          mergeOwners(lhs.privateConstructorOwners, rhs.privateConstructorOwners),
          lhs.invalids ++ rhs.invalids,
        )
      }
    Loaded(
      options.copy(
        disabledErrors = options.disabledErrors ++ raw.disabledErrors,
        privateConstructorOwners = mergeOwners(options.privateConstructorOwners, raw.privateConstructorOwners),
      ),
      raw.invalids,
    )
  }

  def isDisabled(options: Options, id: Id, error: Diagnostic): Boolean =
    options.disabledErrors.contains(error.errorName -> id)

  private def mergeOwners(lhs: Map[String, Set[String]], rhs: Map[String, Set[String]]): Map[String, Set[String]] =
    (lhs.keySet ++ rhs.keySet).map { key =>
      key -> (lhs.getOrElse(key, Set.empty) ++ rhs.getOrElse(key, Set.empty))
    }.toMap
}
