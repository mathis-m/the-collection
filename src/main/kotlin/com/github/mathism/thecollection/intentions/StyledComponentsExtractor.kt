package com.github.mathism.thecollection.intentions

import com.github.mathism.thecollection.helpers.DummyFileFactory
import com.github.mathism.thecollection.helpers.isFragment
import com.github.mathism.thecollection.helpers.isJsxIntrinsicElement
import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.CodeInsightUtilCore
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.template.*
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateState
import com.intellij.codeInsight.template.impl.TextExpression
import com.intellij.lang.ecmascript6.JSXHarmonyFileType
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.TypeScriptJSXFileType
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.impl.FinishMarkAction
import com.intellij.openapi.command.impl.StartMarkAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.psi.util.endOffset
import com.intellij.psi.util.startOffset
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

        val containingFile = element.containingFile
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false) ?: return
        val defaultStyledName = "Styled${NameCaseUtils.toPascalCase(tag.name)}"

        WriteCommandAction.runWriteCommandAction(project) {
            val targetFile = tag.containingFile
            extractStyledComponent(project, targetFile, tag, defaultStyledName)
        }

        var varStatement = containingFile.lastChild
        varStatement = CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(varStatement) ?: return
        if (varStatement !is JSVarStatement) {
            return
        }
        val tsVariable = varStatement.variables.firstOrNull() ?: return
        val nameIdentifier = tsVariable.nameIdentifier ?: return

        editor.caretModel.moveToOffset(nameIdentifier.startOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)

        var scope = PsiTreeUtil.findCommonParent(tsVariable, tag)
        val context: PsiElement? =
            InjectedLanguageManager.getInstance(containingFile.project).getInjectionHost(containingFile)
        val topLevelEditor = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        scope = (if (context != null && topLevelEditor !== editor) context.containingFile else scope) as PsiElement

        val builder = TemplateBuilderImpl(scope)

        val tagNames = tag.children.filter { it is XmlToken && it.tokenType == XmlTokenType.XML_TAG_NAME }
        for ((index, tagName) in tagNames.withIndex()) {
            builder.replaceElement(
                tag, tagName.textRangeInParent, "OtherVariable_$index", "PrimaryVariable", false
            )
        }

        builder.replaceElement(
            tsVariable, nameIdentifier.textRangeInParent, "PrimaryVariable", TextExpression(defaultStyledName), true
        )

        val markAction = StartMarkAction.start(editor, project, "Styled Components Extractor")
        val caretRangeMarker = editor.document.createRangeMarker(
            editor.caretModel.offset, editor.caretModel.offset
        )
        caretRangeMarker.isGreedyToLeft = true
        caretRangeMarker.isGreedyToRight = true

        WriteCommandAction.writeCommandAction(project).withName("Styled Components Extractor").run<RuntimeException> {
            val range = scope.textRange

            val rangeMarker = topLevelEditor.document.createRangeMarker(range)
            val template = builder.buildInlineTemplate()
            template.isToShortenLongNames = false
            template.isToReformat = false
            topLevelEditor.caretModel.moveToOffset(rangeMarker.startOffset)
            TemplateManager.getInstance(project)
                .startTemplate(topLevelEditor, template, object : TemplateEditingAdapter() {
                    override fun templateCancelled(template: Template?) {
                        try {
                            val documentManager = PsiDocumentManager.getInstance(project)
                            documentManager.commitAllDocuments()
                        } finally {
                            FinishMarkAction.finish(
                                project, editor, markAction
                            )
                        }
                    }

                    override fun templateFinished(template: Template, brokenOff: Boolean) {
                        val templateStringExpression =
                            PsiTreeUtil.findChildOfType(varStatement, JSStringTemplateExpression::class.java)
                        val templateContentElement =
                            PsiTreeUtil.collectElements(templateStringExpression) { it.elementType == JSTokenTypes.STRING_TEMPLATE_PART }
                                .firstOrNull()

                        if (templateContentElement != null) {
                            editor.caretModel.moveToOffset(templateContentElement.endOffset - 1 - placeholderText.length)
                            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)

                            editor.selectionModel.setSelection(
                                templateContentElement.endOffset - 1 - placeholderText.length,
                                templateContentElement.endOffset - 1
                            )
                        }

                        FinishMarkAction.finish(
                            project, editor, markAction
                        )
                    }

                    override fun beforeTemplateFinished(state: TemplateState, template: Template?, brokenOff: Boolean) {
                        fixVarNamesIfNeeded(template, state, editor)
                    }

                    private fun fixVarNamesIfNeeded(
                        template: Template?,
                        state: TemplateState,
                        editor: Editor
                    ) {
                        if (template == null) return

                        val userDefinedName = state.getVariableValue("PrimaryVariable")
                        if (userDefinedName == null || userDefinedName.toString().isBlank()) {
                            val vars = template.variables
                            for (variable in vars) {
                                val varRange = state.getVariableRange(variable.name) ?: continue
                                WriteCommandAction.runWriteCommandAction(
                                    project
                                ) {
                                    editor.document.replaceString(
                                        varRange.startOffset, varRange.endOffset, defaultStyledName
                                    )
                                }
                            }

                            return
                        }

                        val userDefinedNamePascalCase = NameCaseUtils.toPascalCase(userDefinedName.toString())
                        if (userDefinedName.toString() != userDefinedNamePascalCase) {
                            val vars = template.variables
                            for (variable in vars) {
                                val varRange = state.getVariableRange(variable.name) ?: continue

                                WriteCommandAction.runWriteCommandAction(
                                    project
                                ) {
                                    editor.document.replaceString(
                                        varRange.startOffset, varRange.endOffset, userDefinedNamePascalCase
                                    )
                                }
                            }
                        }
                    }
                })

            val templateState = TemplateManagerImpl.getTemplateState(topLevelEditor)
            if (templateState != null) {
                DaemonCodeAnalyzer.getInstance(project).disableUpdateByTimer(templateState)
            }
        }
    }

    private fun extractStyledComponent(project: Project, file: PsiFile, tag: XmlTag, styledName: String?) {
        val fileType = file.fileType

        val codeStyleSettings = CodeStyle.getSettings(file)
        val indent = " ".repeat(codeStyleSettings.indentOptions.INDENT_SIZE)

        val originalTagName = tag.name
        val defaultStyledName = "Styled${NameCaseUtils.toPascalCase(tag.name)}"

        val variableName = if (styledName.isNullOrBlank()) {
            defaultStyledName
        } else {
            NameCaseUtils.toPascalCase(styledName)
        }

        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)

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

        val dummyContent = generateNewPsiString(variableName, tag, originalTagName, indent, styledImportIdentifier)
        val dummyFile = DummyFileFactory.createFile(project, fileType, dummyContent)

        val styledComponentDeclaration = PsiTreeUtil.findChildOfType(dummyFile, JSVarStatement::class.java) ?: return
        val renamedTag = PsiTreeUtil.findChildOfType(dummyFile, XmlTag::class.java) ?: return
        val renamedTagToken =
            renamedTag.children.find { it is XmlToken && it.tokenType == XmlTokenType.XML_TAG_NAME } ?: return

        val importElement = if (existingStyledComponentsImport != null) {
            null
        } else {
            PsiTreeUtil.findChildOfType(dummyFile, ES6ImportDeclaration::class.java)
        }

        if (importElement != null) {
            file.addBefore(importElement, file.firstChild)
        }

        file.addAfter(styledComponentDeclaration, file.lastChild)

        val tagNames = tag.children.filter { it is XmlToken && it.tokenType == XmlTokenType.XML_TAG_NAME }
        for (tagName in tagNames) {
            tagName.replace(renamedTagToken)
        }
    }

    private fun generateNewPsiString(
        variableName: String, tag: XmlTag, originalTagName: String, indent: String, styledImportIdentifier: String?
    ): String {
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
              |${indent}$placeholderText
              |`;
            """.trimMargin()
            )
        } else {
            sb.appendLine(
                """
              |(${originalTagName})`
              |${indent}$placeholderText
              |`;
            """.trimMargin()
            )
        }

        sb.append(
            """
            <$variableName />
          """.trimIndent()
        )

        return sb.toString()
    }

    override fun startInWriteAction(): Boolean {
        return false
    }

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        if (!file.isValid) {
            return IntentionPreviewInfo.EMPTY
        }

        val tag = PsiTreeUtil.getParentOfType(getElement(editor, file), XmlTag::class.java, false)
            ?: return IntentionPreviewInfo.EMPTY

        extractStyledComponent(project, file, tag, null)

        return IntentionPreviewInfo.DIFF
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

    private val placeholderText = """// TODO: add styling"""
}
