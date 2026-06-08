/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.ast

import com.whatsapp.eqwalizer.ast.Types._

enum Specifier {
  case SignedIntegerSpecifier,
    UnsignedIntegerSpecifier,
    FloatSpecifier,
    BinarySpecifier,
    BytesSpecifier,
    BitstringSpecifier,
    BitsSpecifier,
    Utf8Specifier,
    Utf16Specifier,
    Utf32Specifier
}

object Specifier {
  def expType(s: Specifier, stringLiteral: Boolean): Type =
    s match {
      case UnsignedIntegerSpecifier | Utf8Specifier | Utf16Specifier | Utf32Specifier =>
        if (stringLiteral) stringType
        else NumberType
      case SignedIntegerSpecifier =>
        NumberType
      case FloatSpecifier =>
        floatType
      case BinarySpecifier | BytesSpecifier | BitstringSpecifier | BitsSpecifier =>
        BinaryType
    }
}
