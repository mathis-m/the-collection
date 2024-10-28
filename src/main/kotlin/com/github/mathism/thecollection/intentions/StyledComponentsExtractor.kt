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
import com.intellij.psi.util.childLeafs
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
        templateStringExpression?.childLeafs()?.toList()?.find { it.elementType == JSTokenTypes.STRING_TEMPLATE_PART }


      if (templateContentElement != null) {
        editor.caretModel.moveToOffset(templateContentElement.textOffset + 1 + codeStyleSettings.indentOptions.INDENT_SIZE)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER_UP)
      }

    }


    /*


    // Get the parent of the "?" element in the ternary statement to find the conditional expression that contains it
    var conditionalExpression: PsiConditionalExpression? =
      PsiTreeUtil.getParentOfType(element, PsiConditionalExpression::class.java, false)
    if (conditionalExpression == null) {
      return
    }
    // Verify the conditional expression exists and has two outcomes in the ternary statement.
    val thenExpression: PsiExpression? = conditionalExpression.getThenExpression()
    val elseExpression: PsiExpression? = conditionalExpression.getElseExpression()
    if (thenExpression == null || elseExpression == null) {
      return
    }

    // Keep searching up the PSI Tree in case the ternary is part of a FOR statement.
    var originalStatement: PsiElement? = PsiTreeUtil.getParentOfType(
      conditionalExpression, PsiStatement::class.java, false
    )
    while (originalStatement is PsiForStatement) {
      originalStatement = PsiTreeUtil.getParentOfType(originalStatement, PsiStatement::class.java, true)
    }
    if (originalStatement == null) {
      return
    }

    // If the original statement is a declaration based on a ternary operator,
    // split the declaration and assignment
    if (originalStatement is PsiDeclarationStatement) {
      // Find the local variable within the declaration statement
      val declaredElements: Array<PsiElement> = originalStatement.getDeclaredElements()
      var variable: PsiLocalVariable? = null
      for (declaredElement: PsiElement? in declaredElements) {
        if (declaredElement is PsiLocalVariable && PsiTreeUtil.isAncestor(
            declaredElement,
            conditionalExpression,
            true
          )
        ) {
          variable = declaredElement as PsiLocalVariable
          break
        }
      }
      if (variable == null) {
        return
      }

      // Ensure that the variable declaration is not combined with other declarations, and add a mark
      variable.normalizeDeclaration()
      val marker: Any = Any()
      PsiTreeUtil.mark(conditionalExpression, marker)

      // Create a new expression to declare the local variable
      var statement: PsiExpressionStatement =
        factory.createStatementFromText(variable.getName() + " = 0;", null) as PsiExpressionStatement
      statement = codeStylist.reformat(statement) as PsiExpressionStatement

      // Replace initializer with the ternary expression, making an assignment statement using the ternary
      val rExpression: PsiExpression? = (statement.getExpression() as PsiAssignmentExpression).getRExpression()
      val variableInitializer: PsiExpression? = variable.getInitializer()
      if (rExpression == null || variableInitializer == null) {
        return
      }
      rExpression.replace(variableInitializer)

      // Remove the initializer portion of the local variable statement,
      // making it a declaration statement with no initializer
      variableInitializer.delete()

      // Get the grandparent of the local var declaration, and add the new declaration just beneath it
      val variableParent: PsiElement = variable.getParent()
      originalStatement = variableParent.getParent().addAfter(statement, variableParent)
      conditionalExpression = PsiTreeUtil.releaseMark(originalStatement, marker) as PsiConditionalExpression?
    }
    if (conditionalExpression == null) {
      return
    }

    // Create an IF statement from a string with placeholder elements.
    // This will replace the ternary statement
    var newIfStmt: PsiIfStatement =
      factory.createStatementFromText("if (true) {a=b;} else {c=d;}", null) as PsiIfStatement
    newIfStmt = codeStylist.reformat(newIfStmt) as PsiIfStatement

    // Replace the conditional expression with the one from the original ternary expression
    val condition: PsiReferenceExpression = conditionalExpression.getCondition().copy() as PsiReferenceExpression
    val newIfStmtCondition: PsiExpression? = newIfStmt.getCondition()
    if (newIfStmtCondition == null) {
      return
    }
    newIfStmtCondition.replace(condition)

    // Begin building the assignment string for the THEN and ELSE clauses using the
    // parent of the ternary conditional expression
    val assignmentExpression: PsiAssignmentExpression? =
      PsiTreeUtil.getParentOfType(conditionalExpression, PsiAssignmentExpression::class.java, false)
    if (assignmentExpression == null) {
      return
    }
    // Get the contents of the assignment expression up to the start of the ternary expression
    val exprFrag: String =
      (assignmentExpression.getLExpression().getText() + assignmentExpression.getOperationSign().getText())

    // Build the THEN statement string for the new IF statement,
    // make a PsiExpressionStatement from the string, and switch the placeholder
    val thenStr: String = (exprFrag + thenExpression.getText()).toString() + ";"
    val thenStmt: PsiExpressionStatement = factory.createStatementFromText(thenStr, null) as PsiExpressionStatement
    val thenBranch: PsiBlockStatement? = newIfStmt.getThenBranch() as PsiBlockStatement?
    if (thenBranch == null) {
      return
    }
    thenBranch.getCodeBlock().getStatements().get(0).replace(thenStmt)

    // Build the ELSE statement string for the new IF statement,
    // make a PsiExpressionStatement from the string, and switch the placeholder
    val elseStr: String = (exprFrag + elseExpression.getText()).toString() + ";"
    val elseStmt: PsiExpressionStatement = factory.createStatementFromText(elseStr, null) as PsiExpressionStatement
    val elseBranch: PsiBlockStatement? = newIfStmt.getElseBranch() as PsiBlockStatement?
    if (elseBranch == null) {
      return
    }
    elseBranch.getCodeBlock().getStatements().get(0).replace(elseStmt)

    // Replace the entire original statement with the new IF
    originalStatement.replace(newIfStmt)*/
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
