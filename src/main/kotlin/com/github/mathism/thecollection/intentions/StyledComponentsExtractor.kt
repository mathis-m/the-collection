package com.github.mathism.thecollection.intentions

import com.github.mathism.thecollection.helpers.DummyFileFactory
import com.github.mathism.thecollection.helpers.isFragment
import com.github.mathism.thecollection.helpers.isJsxIntrinsicElement
import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.lang.ecmascript6.JSXHarmonyFileType
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.TypeScriptJSXFileType
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.util.IncorrectOperationException
import com.intellij.webSymbols.utils.NameCaseUtils
import org.jetbrains.annotations.NonNls

/**
 * Implements an intention action to replace a ternary statement with if-then-else.
 */
@NonNls
class StyledComponentsExtractor : PsiElementBaseIntentionAction(), IntentionAction {
  /**
   * Checks whether this intention is available at the caret offset in file - the caret must sit just before a "?"
   * character in a ternary statement. If this condition is met, this intention's entry is shown in the available
   * intentions list.
   *
   * <p>Note: this method must do its checks quickly and return.</p>
   *
   * @param project a reference to the Project object being edited.
   * @param editor  a reference to the object editing the project source
   * @param element a reference to the PSI element currently under the caret
   * @return {@code true} if the caret is in a literal string element, so this functionality should be added to the
   * intention menu or {@code false} for all other types of caret positions
   */
  override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
    // Quick sanity check
    val fileType = element.containingFile.fileType
    val isJsxFile = fileType is TypeScriptJSXFileType || fileType is JSXHarmonyFileType
    if (editor == null || !isJsxFile || element !is XmlToken) {
      return false
    }

    val allowedTokens = arrayOf(
      XmlTokenType.XML_START_TAG_START,
      XmlTokenType.XML_END_TAG_START,
      XmlTokenType.XML_TAG_END,
      XmlTokenType.XML_TAG_NAME,
    )

    if (element.tokenType !in allowedTokens) {
      return false
    }

    val tag = PsiTreeUtil.getParentOfType(element.parent, XmlTag::class.java, false) ?: return false

    return !tag.isFragment
  }

  /**
   * Modifies the PSI to change a ternary expression to an if-then-else statement.
   * If the ternary is part of a declaration, the declaration is separated and moved above the if-then-else statement.
   * Called when user selects this intention action from the available intentions list.
   *
   * @param project a reference to the Project object being edited.
   * @param editor  a reference to the object editing the project source
   * @param element a reference to the PSI element currently under the caret
   * @throws IncorrectOperationException Thrown by underlying (PSI model) write action context
   *                                     when manipulation of the PSI tree fails.
   */
  @kotlin.Throws(IncorrectOperationException::class)
  override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
    if (editor == null) return

    // Get the factory for making new PsiElements, and the code style manager to format new statements
    val containingFile = element.containingFile
    val codeStyleSettings = CodeStyle.getSettings(containingFile)
    val indent = " ".repeat(codeStyleSettings.indentOptions.INDENT_SIZE)

    val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false) ?: return
    val originalTagName = tag.name

    val defaultStyledName = "Styled${NameCaseUtils.toPascalCase(tag.name)}"

    var variableName = Messages.showInputDialog(
      "Enter the name for the new styled component",
      "Styled Component Name",
      Messages.getQuestionIcon(),
      defaultStyledName,
      null
    ) ?: defaultStyledName

    variableName = if (variableName.isBlank()) {
      defaultStyledName
    } else {
      NameCaseUtils.toPascalCase(variableName)
    }

    // get styled-components import
    val imports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)

    val importPkg = "styled-components"
    val existingStyledComponentsImport = imports.find { declaration ->
      val referenceText = declaration.fromClause?.referenceText
      if (referenceText == null) {
        false
      } else {
        referenceText.length == importPkg.length + 2 && referenceText.contains(importPkg)
      }
    }

    val styledImportIdentifier = existingStyledComponentsImport?.importedBindings?.firstOrNull()?.declaredName
    val sb = StringBuilder()

    if (styledImportIdentifier == null) {
      sb.appendLine(
        """
          import styled from "styled-components";
        """.trimIndent()
      )
    }

    sb.append(
      "const $variableName = ${styledImportIdentifier ?: "styled"}"
    )


    if (tag.isJsxIntrinsicElement) {
      sb.appendLine(
        """
          |.${originalTagName}`
          |$indent
          |`;
        """.trimMargin()
      )
    } else {
      sb.appendLine(
        """
          |(${originalTagName})`
          |$indent
          |`;
        """.trimMargin()
      )
    }

    sb.append(
      """
        <$variableName />
      """.trimIndent()
    )

    WriteCommandAction.runWriteCommandAction(project) {
      val fileType = containingFile.fileType

      if (!containingFile.isValid) {
        return@runWriteCommandAction
      }
      val dummyFile = DummyFileFactory.createFile(project, fileType, sb.toString())

      val styledComponentDeclaration =
        PsiTreeUtil.findChildOfType(dummyFile, JSVarStatement::class.java) ?: return@runWriteCommandAction
      val renamedTag = PsiTreeUtil.findChildOfType(dummyFile, XmlTag::class.java) ?: return@runWriteCommandAction
      val renamedTagToken = renamedTag.children.find { it is XmlToken && it.tokenType == XmlTokenType.XML_TAG_NAME }
        ?: return@runWriteCommandAction

      val importElement = if (existingStyledComponentsImport != null) {
        null
      } else {
        PsiTreeUtil.findChildOfType(dummyFile, ES6ImportDeclaration::class.java)
      }

      if (importElement != null) {
        containingFile.addBefore(importElement, containingFile.firstChild)
      }

      containingFile.addAfter(styledComponentDeclaration, containingFile.lastChild)

      val tagNames = tag.children.filter { it is XmlToken && it.tokenType == XmlTokenType.XML_TAG_NAME }
      for (tagName in tagNames) {
        tagName.replace(renamedTagToken)
      }

      val templateStringExpression =
        PsiTreeUtil.findChildOfType(containingFile.lastChild, JSStringTemplateExpression::class.java)

      val templateContentElement =
        PsiTreeUtil.collectElements(templateStringExpression) { it.elementType == JSTokenTypes.STRING_TEMPLATE_PART}.firstOrNull()
      //templateStringExpression?.childLeafs()?.toList()?.find { it.elementType == JSTokenTypes.STRING_TEMPLATE_PART }


      if (templateContentElement != null) {
        editor.caretModel.moveToOffset(templateContentElement.textOffset + 1 + codeStyleSettings.indentOptions.INDENT_SIZE)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER_UP)
      }

    }
  }

  /**
   * Returns text for name of this family of intentions.
   * It is used to externalize "auto-show" state of intentions.
   * It is also the directory name for the descriptions.
   */
  override fun getFamilyName(): String {
    return "Extract styled component"
  }

  override fun getText(): String {
    return "Extract styled component"
  }

}
