package com.yomitanmobile.kindle

/**
 * Desktop notifications for a run nobody started by hand — the Linux watcher
 * runs this on plug-in, and the bubble is the only thing that says what the
 * laptop is doing while the Kindle shows nothing. The window has no use for it:
 * it shows its own progress and stays open.
 *
 * One rule matters more than the backends: the progress bubble never expires
 * and the result is a NEW bubble. Plasma expires a bubble after ~5 s and then
 * silently drops every replace aimed at it, so updating one bubble in place
 * gave "two pop-ups, then silence" — the result of every run was lost.
 */
class Notifier {

    private var progressId: String? = null
    private val title = "Kindle → Anki"

    fun progress(message: String) {
        when (Platform.os) {
            Platform.Os.LINUX -> progressId = linux(message, replaces = progressId, urgent = false) ?: progressId
            // Notification Center has no replace; a step each is what it does.
            Platform.Os.MAC -> mac(message)
            // A toast per step would be noise on Windows; only the result.
            Platform.Os.WINDOWS -> Unit
        }
    }

    /** The result. [failed] is shown as an error and stays on servers that honour urgency. */
    fun done(message: String, failed: Boolean) {
        when (Platform.os) {
            Platform.Os.LINUX -> {
                progressId?.let(::closeLinux)
                progressId = null
                linux(message, replaces = null, urgent = failed)
            }
            Platform.Os.MAC -> mac(message)
            Platform.Os.WINDOWS -> windows(message, failed)
        }
    }

    /** Returns the bubble's id, when the server said it. */
    private fun linux(message: String, replaces: String?, urgent: Boolean): String? {
        val icon = if (urgent) "dialog-error" else "emblem-synchronizing"
        Platform.which("notify-send")?.let { send ->
            val command = mutableListOf(
                send, "-a", title, "-i", icon, "-u", if (urgent) "critical" else "normal", "-t", "0", "-p"
            )
            if (replaces != null) command += listOf("-r", replaces)
            command += listOf(title, message)
            return Platform.run(*command.toTypedArray(), timeoutSeconds = 10)?.trim()?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
        }
        Platform.which("gdbus")?.let { gdbus ->
            val out = Platform.run(
                gdbus, "call", "--session", "--dest", "org.freedesktop.Notifications",
                "--object-path", "/org/freedesktop/Notifications",
                "--method", "org.freedesktop.Notifications.Notify",
                title, replaces ?: "0", icon, title, message, "[]",
                "{'urgency': <byte ${if (urgent) 2 else 1}>}", "0",
                timeoutSeconds = 10
            )
            return out?.let { Regex("uint32 (\\d+)").find(it)?.groupValues?.get(1) }
        }
        return null
    }

    private fun closeLinux(id: String) {
        val gdbus = Platform.which("gdbus") ?: return
        Platform.run(
            gdbus, "call", "--session", "--dest", "org.freedesktop.Notifications",
            "--object-path", "/org/freedesktop/Notifications",
            "--method", "org.freedesktop.Notifications.CloseNotification", id,
            timeoutSeconds = 10
        )
    }

    private fun mac(message: String) {
        Platform.run(
            "osascript", "-e",
            "display notification ${appleString(message)} with title ${appleString(title)}",
            timeoutSeconds = 10
        )
    }

    private fun appleString(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** A balloon from PowerShell, which every Windows since 7 has. */
    private fun windows(message: String, failed: Boolean) {
        fun ps(text: String) = "'" + text.replace("'", "''") + "'"
        val script = """
            Add-Type -AssemblyName System.Windows.Forms
            ${'$'}n = New-Object System.Windows.Forms.NotifyIcon
            ${'$'}n.Icon = [System.Drawing.SystemIcons]::${if (failed) "Error" else "Information"}
            ${'$'}n.Visible = ${'$'}true
            ${'$'}n.ShowBalloonTip(15000, ${ps(title)}, ${ps(message)}, '${if (failed) "Error" else "Info"}')
            Start-Sleep -Seconds 15
            ${'$'}n.Dispose()
        """.trimIndent()
        runCatching {
            ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
    }
}
