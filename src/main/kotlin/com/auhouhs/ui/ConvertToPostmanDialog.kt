package com.auhouhs.ui

import com.auhouhs.MyMessageBundle
import com.auhouhs.http2postman.api.ConversionRequest
import com.auhouhs.http2postman.api.PostmanConversion
import com.auhouhs.http2postman.util.Names
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Options for one `.http` -> Postman conversion: where to write the collection
 * plus the CLI-equivalent switches (collection name, environment, `.env` file).
 */
class ConvertToPostmanDialog(
    private val project: Project,
    sources: List<VirtualFile>
) : DialogWrapper(project, true) {

    private val files = sources.filter { !it.isDirectory }
    private val directories = sources.filter { it.isDirectory }

    private val noneEnvironment = MyMessageBundle.message("dialog.environment.none")

    private val sourceLabel = JBLabel(sourceSummary()).apply { toolTipText = sourceSummaryTooltip() }

    private val outputDirField = TextFieldWithBrowseButton()
    private val fileNameField = JBTextField(DEFAULT_FILE_NAME, 30)
    private val collectionNameField = JBTextField(defaultCollectionName(), 30)
    private val environmentCombo = ComboBox<String>()
    private val envFileField = TextFieldWithBrowseButton()

//    private val includeSiblingsCheckBox = JBCheckBox(MyMessageBundle.message("dialog.includeSiblings.label"))
    private val openAfterCheckBox = JBCheckBox(MyMessageBundle.message("dialog.openAfter.label"), true)

    private val commentLabel = JBLabel(MyMessageBundle.message("dialog.comment")).apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    init {
        title = MyMessageBundle.message("dialog.title")
        setOKButtonText(MyMessageBundle.message("dialog.ok"))

        outputDirField.text = defaultOutputDirectory()
        outputDirField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle(MyMessageBundle.message("dialog.outputDir.chooser"))
        )

        envFileField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile()
                .withTitle(MyMessageBundle.message("dialog.envFile.chooser"))
        )
        envFileField.toolTipText = MyMessageBundle.message("dialog.envFile.hint")

//        includeSiblingsCheckBox.isVisible = files.size == 1 && directories.isEmpty()
//        includeSiblingsCheckBox.toolTipText = MyMessageBundle.message("dialog.includeSiblings.hint")

        populateEnvironmentCombo()
        applyFieldWidth(outputDirField, fileNameField, collectionNameField, environmentCombo, envFileField)

        init()
    }

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(MyMessageBundle.message("dialog.source.label")), sourceLabel)
            .addLabeledComponent(JBLabel(MyMessageBundle.message("dialog.outputDir.label")), outputDirField)
            .addLabeledComponent(JBLabel(MyMessageBundle.message("dialog.fileName.label")), fileNameField)
            .addLabeledComponent(JBLabel(MyMessageBundle.message("dialog.collectionName.label")), collectionNameField)
            .addLabeledComponent(JBLabel(MyMessageBundle.message("dialog.environment.label")), environmentCombo)
            .addLabeledComponent(JBLabel(MyMessageBundle.message("dialog.envFile.label")), envFileField)
            .addVerticalGap(6)
//        if (includeSiblingsCheckBox.isVisible) {
//            builder.addComponent(includeSiblingsCheckBox)
//        }
        return builder
            .addComponent(openAfterCheckBox)
            .addComponent(commentLabel)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = outputDirField

    override fun doValidate(): ValidationInfo? {
        val outputDir = outputDirField.text.trim()
        if (outputDir.isEmpty()) {
            return ValidationInfo(MyMessageBundle.message("dialog.validation.outputDir"), outputDirField)
        }
        try {
            Path.of(outputDir)
        } catch (e: InvalidPathException) {
            return ValidationInfo(MyMessageBundle.message("dialog.validation.outputDirInvalid"), outputDirField)
        }
        if (!isValidFileName(fileNameField.text.trim())) {
            return ValidationInfo(MyMessageBundle.message("dialog.validation.fileName"), fileNameField)
        }
        if (collectionNameField.text.isBlank()) {
            return ValidationInfo(MyMessageBundle.message("dialog.validation.collectionName"), collectionNameField)
        }
        return null
    }

    override fun doOKAction() {
        val target = outputFile()
        if (target != null && Files.exists(target)) {
            val answer = Messages.showYesNoDialog(
                project,
                MyMessageBundle.message("dialog.overwrite.message", target.toString()),
                MyMessageBundle.message("dialog.overwrite.title"),
                Messages.getQuestionIcon()
            )
            if (answer != Messages.YES) {
                return
            }
        }
        super.doOKAction()
    }

    /** Snapshot of the dialog values, ready for [PostmanConversion.convert]. */
    fun createRequest(): ConversionRequest = ConversionRequest(
        sourcePaths(),
        outputFile() ?: throw IllegalStateException("invalid output path"),
        collectionNameField.text.trim(),
        envFileField.text.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) },
        (environmentCombo.selectedItem as? String)?.takeIf { it != noneEnvironment }
    )

    fun isOpenAfterConversion(): Boolean = openAfterCheckBox.isSelected

    // ------------------------------------------------------------------
    // defaults
    // ------------------------------------------------------------------

    private fun defaultOutputDirectory(): String =
        project.basePath ?: System.getProperty("user.home")

    private fun defaultCollectionName(): String {
        val sourceName = files.firstOrNull()?.nameWithoutExtension
            ?: directories.firstOrNull()?.name
            ?: return ""
        return Names.titleCase(sourceName)
    }

    private fun populateEnvironmentCombo() {
        val names = firstSourceDirectory()
            ?.let { PostmanConversion.environmentNames(it) }
            .orEmpty()
        environmentCombo.addItem(noneEnvironment)
        names.forEach { environmentCombo.addItem(it) }
        environmentCombo.selectedIndex = 0
        environmentCombo.isEnabled = names.isNotEmpty()
        environmentCombo.toolTipText = if (names.isEmpty()) {
            MyMessageBundle.message("dialog.environment.unavailable")
        } else {
            MyMessageBundle.message("dialog.environment.hint")
        }
    }

    private fun firstSourceDirectory(): Path? {
        files.firstOrNull()?.let { file ->
            return file.parent?.toNioPath() ?: file.toNioPath().parent
        }
        return directories.firstOrNull()?.toNioPath()
    }

    // ------------------------------------------------------------------
    // request building
    // ------------------------------------------------------------------

    private fun sourcePaths(): List<Path> {
//        if (includeSiblingsCheckBox.isSelected) {
//            files.firstOrNull()?.parent?.let { return listOf(it.toNioPath()) }
//        }
        return (files.map { it.toNioPath() } + directories.map { it.toNioPath() }).distinct()
    }

    private fun outputFile(): Path? = try {
        Path.of(outputDirField.text.trim(), fileNameField.text.trim())
    } catch (e: InvalidPathException) {
        null
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun sourceSummary(): String = when {
        files.size + directories.size == 1 -> (files.firstOrNull() ?: directories.first()).name
        else -> MyMessageBundle.message("dialog.source.count", files.size + directories.size)
    }

    private fun sourceSummaryTooltip(): String =
        (files.map { it.path } + directories.map { it.path }).joinToString("\n")

    private fun isValidFileName(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") {
            return false
        }
        return name.none { it in "\\/:*?\"<>|" }
    }

    private fun applyFieldWidth(vararg components: JComponent) {
        components.forEach { component ->
            component.preferredSize = JBUI.size(JBUI.scale(430), component.preferredSize.height)
        }
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "postman_collection.json"
    }
}
