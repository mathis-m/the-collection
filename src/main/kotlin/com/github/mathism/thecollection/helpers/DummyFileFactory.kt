package com.github.mathism.thecollection.helpers

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.util.LocalTimeCounter


object DummyFileFactory {
  fun createFile(project: Project, fileType: FileType, text: String): PsiFile {
    return PsiFileFactory.getInstance(project).createFileFromText(
      "temp." + fileType.defaultExtension,
      fileType,
      text, LocalTimeCounter.currentTime(), false
    )
  }
}
