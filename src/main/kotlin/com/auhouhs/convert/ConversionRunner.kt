package com.auhouhs.convert

import com.auhouhs.MyMessageBundle
import com.auhouhs.http2postman.api.ConversionRequest
import com.auhouhs.http2postman.api.ConversionResult
import com.auhouhs.http2postman.api.PostmanConversion
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/** Runs a conversion in the background and reports the outcome as a notification. */
object ConversionRunner {

    private const val NOTIFICATION_GROUP = "Http2postman"
    private const val MAX_WARNING_LINES = 10

    fun run(project: Project, request: ConversionRequest, openAfterConversion: Boolean) {
        object : Task.Backgroundable(project, MyMessageBundle.message("progress.title"), true) {
            private var result: ConversionResult? = null
            private var failure: Throwable? = null
            private var outputFile: VirtualFile? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val converted = PostmanConversion.convert(request)
                    result = converted
                    outputFile = LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(converted.outputFile().toFile())
                } catch (e: Throwable) {
                    failure = e
                }
            }

            override fun onSuccess() {
                val converted = result
                if (converted == null) {
                    notifyFailure(project, failure)
                    return
                }
                notifySuccess(project, converted, outputFile)
                notifyWarnings(project, converted)
                val target = outputFile
                if (openAfterConversion && target != null) {
                    OpenFileDescriptor(project, target).navigate(true)
                }
            }
        }.queue()
    }

    private fun notifySuccess(project: Project, result: ConversionResult, file: VirtualFile?) {
        val notification = Notification(
            NOTIFICATION_GROUP,
            MyMessageBundle.message("notification.success.title"),
            MyMessageBundle.message(
                "notification.success.content",
                result.outputFile().toString(),
                result.requests(),
                result.folders(),
                result.variables(),
                result.scripts()
            ),
            NotificationType.INFORMATION
        )
        if (file != null) {
            notification.addAction(object : NotificationAction(MyMessageBundle.message("notification.action.open")) {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    OpenFileDescriptor(project, file).navigate(true)
                }
            })
        }
        notification.addAction(
            object : NotificationAction(MyMessageBundle.message("notification.action.showInExplorer")) {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    RevealFileAction.openFile(result.outputFile())
                }
            }
        )
        notify(project, notification)
    }

    private fun notifyWarnings(project: Project, result: ConversionResult) {
        if (!result.hasWarnings()) {
            return
        }
        val warnings = result.warnings()
        val lines = warnings.take(MAX_WARNING_LINES).map { warning ->
            val fileName = File(warning.sourceFile()).name
            "$fileName:${warning.sourceLine()} ${warning.message()}"
        }
        val text = buildString {
            append("<br>")
            append(lines.joinToString("<br>"))
            if (warnings.size > MAX_WARNING_LINES) {
                append("<br>…")
            }
        }
        notify(
            project,
            Notification(
                NOTIFICATION_GROUP,
                MyMessageBundle.message("notification.warnings.title"),
                MyMessageBundle.message("notification.warnings.content", text),
                NotificationType.WARNING
            )
        )
    }

    private fun notifyFailure(project: Project, failure: Throwable?) {
        if (failure != null) {
            Logger.getInstance(ConversionRunner::class.java).warn("Postman conversion failed", failure)
        }
        val detail = failure?.message ?: MyMessageBundle.message("notification.error.unknown")
        notify(
            project,
            Notification(
                NOTIFICATION_GROUP,
                MyMessageBundle.message("notification.error.title"),
                detail,
                NotificationType.ERROR
            )
        )
    }

    private fun notify(project: Project, notification: Notification) {
        Notifications.Bus.notify(notification, project)
    }
}
