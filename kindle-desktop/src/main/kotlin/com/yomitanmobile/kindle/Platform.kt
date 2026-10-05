package com.yomitanmobile.kindle

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Everything that differs between Linux, Windows and macOS, in one place: where
 * files live, where Anki keeps its add-ons, how Anki is started, where adb is.
 * The pipeline above it never asks which OS it is on.
 */
object Platform {

    enum class Os { LINUX, WINDOWS, MAC }

    val os: Os = System.getProperty("os.name").lowercase().let {
        when {
            "win" in it -> Os.WINDOWS
            "mac" in it || "darwin" in it -> Os.MAC
            else -> Os.LINUX
        }
    }

    private val home = File(System.getProperty("user.home"))
    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    /**
     * Downloads, the phone's settings.json, VOICEVOX. On Linux the folder the
     * shell version used, so a machine that ran it keeps its data.
     */
    val dataDir: File = when (os) {
        Os.LINUX -> File(env("XDG_DATA_HOME") ?: "$home/.local/share", "kindle-sync")
        Os.MAC -> File(home, "Library/Application Support/kindle-sync")
        Os.WINDOWS -> File(env("APPDATA") ?: "$home/AppData/Roaming", "kindle-sync")
    }

    /** The last-run marker and the last run's reports; the shell version's on Linux. */
    val stateDir: File = when (os) {
        Os.LINUX -> File(env("XDG_STATE_HOME") ?: "$home/.local/state", "kindle-sync")
        else -> File(dataDir, "state")
    }

    /** Every place an Anki profile folder (holding addons21) can be. */
    val ankiDirs: List<File> = when (os) {
        Os.LINUX -> listOf(
            File(env("XDG_DATA_HOME") ?: "$home/.local/share", "Anki2"),
            // Anki from Flathub keeps everything inside its sandbox.
            File(home, ".var/app/net.ankiweb.Anki/data/Anki2")
        )
        Os.MAC -> listOf(File(home, "Library/Application Support/Anki2"))
        Os.WINDOWS -> listOf(File(env("APPDATA") ?: "$home/AppData/Roaming", "Anki2"))
    }

    /**
     * The AutoReorder add-on's folder. Found by name: the folder itself is an
     * AnkiWeb id, not a name.
     */
    fun autoReorderAddon(): File? = ankiDirs.asSequence()
        .mapNotNull { File(it, "addons21").listFiles()?.asSequence() }
        .flatten()
        .firstOrNull { dir ->
            File(dir, "meta.json").takeIf { it.isFile }?.readText()
                ?.contains(Regex("\"name\"\\s*:\\s*\"AutoReorder\"")) == true
        }

    /** Starts Anki if it can be found. False when there is nothing to start. */
    fun startAnki(): Boolean {
        val candidates: List<List<String>> = when (os) {
            Os.LINUX -> listOfNotNull(
                which("anki")?.let { listOf(it) },
                which("flatpak")?.let { listOf(it, "run", "net.ankiweb.Anki") }
                    ?.takeIf { File(home, ".var/app/net.ankiweb.Anki").isDirectory }
            )
            Os.MAC -> listOf(listOf("open", "-a", "Anki"))
            Os.WINDOWS -> listOfNotNull(
                env("LOCALAPPDATA")?.let { File(it, "Programs/Anki/anki.exe") },
                env("ProgramFiles")?.let { File(it, "Anki/anki.exe") }
            ).filter { it.isFile }.map { listOf(it.path) }
        }
        for (command in candidates) {
            val started = runCatching {
                ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }.isSuccess
            if (started) return true
        }
        return false
    }

    /** adb from PATH, or from the Android SDK's default place on this OS. */
    fun adb(): String? = which("adb") ?: listOf(
        File(home, "Android/Sdk/platform-tools/adb"),
        File(home, "Library/Android/sdk/platform-tools/adb"),
        File(env("LOCALAPPDATA") ?: "$home/AppData/Local", "Android/Sdk/platform-tools/adb.exe")
    ).firstOrNull { it.canExecute() }?.path

    /** A program on PATH, or null. */
    fun which(name: String): String? {
        val path = env("PATH") ?: return null
        val names = if (os == Os.WINDOWS) listOf("$name.exe", "$name.cmd", "$name.bat", name) else listOf(name)
        return path.split(File.pathSeparator).asSequence()
            .flatMap { dir -> names.asSequence().map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }?.path
    }

    /** Output of a short command, or null when it failed or took too long. */
    fun run(vararg command: String, timeoutSeconds: Long = 60, env: Map<String, String> = emptyMap()): String? =
        runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true)
                .apply { environment().putAll(env) }.start()
            val output = StringBuilder()
            val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }.apply { start() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            reader.join(2_000)
            output.toString().takeIf { process.exitValue() == 0 }
        }.getOrNull()
}
