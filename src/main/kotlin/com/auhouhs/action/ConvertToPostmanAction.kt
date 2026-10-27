package com.auhouhs.action

import com.auhouhs.MyMessageBundle
import com.auhouhs.convert.ConversionRunner
import com.auhouhs.ui.ConvertToPostmanDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import java.util.Locale

/**
 * Converts the selected `.http` / `.rest` files (or folders holding them) into a
 * Postman Collection v2.1 JSON file.
 */
class ConvertToPostmanAction : AnAction(
    MyMessageBundle.message("action.convertToPostman.text"),
    MyMessageBundle.message("action.convertToPostman.description"),
    AllIcons.Actions.Upload
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && selectedSources(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = ConvertToPostmanDialog(project, selectedSources(e))
        if (!dialog.showAndGet()) {
            return
        }
        ConversionRunner.run(project, dialog.createRequest(), dialog.isOpenAfterConversion())
    }

    private fun selectedSources(e: AnActionEvent): List<VirtualFile> {
        val selection = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.asList().orEmpty()
        val currentFile = listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        return (selection + currentFile).distinct().filter { file ->
            file.isValid && file.isInLocalFileSystem && (file.isDirectory || isHttpSource(file.name))
        }
    }

    companion object {
        private val HTTP_EXTENSIONS = setOf("http", "rest")

        /** `true` for JetBrains HTTP Client (`*.http`) and REST Client (`*.rest`) files. */
        fun isHttpSource(fileName: String): Boolean =
            HTTP_EXTENSIONS.contains(fileName.substringAfterLast('.', "").lowercase(Locale.ROOT))
    }
}
