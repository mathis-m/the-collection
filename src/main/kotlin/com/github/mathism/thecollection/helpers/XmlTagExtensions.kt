package com.github.mathism.thecollection.helpers

import com.intellij.psi.xml.XmlTag

internal val XmlTag.isFragment: Boolean
  get() {
    val isImplicitFragment = name.isEmpty()
    val isExplicitFragment = name == "Fragment"
    val isExplicitReactFragment = name == "React.Fragment"

    return isImplicitFragment || isExplicitFragment || isExplicitReactFragment
  }


internal val XmlTag.isJsxIntrinsicElement get() = name.isNotEmpty() && name[0].isLowerCase()

