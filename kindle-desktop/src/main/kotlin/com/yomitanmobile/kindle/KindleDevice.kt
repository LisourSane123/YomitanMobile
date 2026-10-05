package com.yomitanmobile.kindle

import java.io.File

/**
 * Gets `system/vocabulary/vocab.db` off a plugged-in Kindle. The Kindle is
 * only ever read.
 *
 * Older Kindles are USB mass storage — a drive, on every OS. Newer ones (the
 * 2021+ Paperwhite included) are MTP only, and every OS reaches MTP its own way:
 *
 *  - Linux: through the desktop's MTP layer — KIO on KDE (Dolphin's worker
 *    holds the device, so libmtp could not open it), gio on GNOME and others.
 *  - Windows: through Explorer's shell namespace, the same "This PC → Kindle"
 *    a person would click through, driven from PowerShell.
 *  - macOS: has no MTP of its own; libmtp's tools (`brew install libmtp`)
 *    when present.
 *
 * When none of that works the window offers to pick the file by hand.
 */
class KindleDevice(private val log: (String) -> Unit) {

    /** Copies vocab.db to [dest]. True when it got there. */
    fun copyVocab(dest: File): Boolean =
        fromMassStorage(dest) || when (Platform.os) {
            Platform.Os.LINUX -> fromKio(dest) || fromGio(dest)
            Platform.Os.WINDOWS -> fromWindowsShell(dest)
            Platform.Os.MAC -> fromLibMtp(dest)
        }

    /**
     * Whether a Kindle is on the USB bus at all — tells "no Kindle here" from
     * "a Kindle that will not hand over vocab.db", two different things to fix.
     * Only Linux can tell cheaply; elsewhere this answers null (unknown).
     */
    fun onUsb(): Boolean? {
        if (Platform.os != Platform.Os.LINUX) return null
        return File("/sys/bus/usb/devices").listFiles().orEmpty().any { dev ->
            File(dev, "idVendor").takeIf { it.canRead() }?.readText()?.trim() == AMAZON_VENDOR
        }
    }

    private fun fromMassStorage(dest: File): Boolean {
        val user = System.getProperty("user.name")
        val roots: List<File> = when (Platform.os) {
            Platform.Os.LINUX -> listOf(File("/run/media/$user"), File("/media/$user"), File("/media"))
                .flatMap { it.listFiles()?.toList().orEmpty() }
            Platform.Os.MAC -> File("/Volumes").listFiles()?.toList().orEmpty()
            Platform.Os.WINDOWS -> File.listRoots().toList()
        }
        // The volume called Kindle first; any other drive holding the file after.
        for (root in roots.sortedByDescending { it.name.equals("Kindle", ignoreCase = true) }) {
            val vocab = File(root, VOCAB_PATH)
            if (runCatching { vocab.isFile }.getOrDefault(false)) {
                vocab.copyTo(dest, overwrite = true)
                log("vocab.db from $root (mass storage)")
                return true
            }
        }
        return false
    }

    // ---- Linux -------------------------------------------------------------

    /**
     * kioclient is a Qt program and aborts without a display — which a service
     * started at boot does not have yet. Copying a file needs no window, so
     * `offscreen`, which never looks for one.
     */
    private fun kio(vararg args: String): String? {
        val kioclient = Platform.which("kioclient") ?: Platform.which("kioclient5") ?: return null
        return Platform.run(kioclient, *args, timeoutSeconds = 60, env = mapOf("QT_QPA_PLATFORM" to "offscreen"))
    }

    private fun fromKio(dest: File): Boolean {
        val device = kio("ls", "mtp:/")?.lineSequence()?.firstOrNull { it.contains("kindle", ignoreCase = true) }
            ?: return false
        val storages = kio("ls", "mtp:/$device")?.lines().orEmpty().filter { it.isNotBlank() && it != "." }
        for (storage in storages) {
            // Storage names have spaces ("Internal Storage"); whether a KIO url
            // wants them raw or encoded is tried rather than guessed.
            for (path in listOf(
                "mtp:/$device/$storage/$VOCAB_PATH",
                "mtp:/${device.replace(" ", "%20")}/${storage.replace(" ", "%20")}/$VOCAB_PATH"
            )) {
                dest.delete()
                if (kio("--noninteractive", "copy", path, "file://" + dest.absolutePath) != null && dest.isFile) {
                    log("vocab.db from $path (kio)")
                    return true
                }
            }
        }
        log("kio: a Kindle is listed as $device but vocab.db could not be copied (unlock its screen?)")
        return false
    }

    private fun fromGio(dest: File): Boolean {
        val gio = Platform.which("gio") ?: return false
        val root = Platform.run(gio, "mount", "-li")?.lineSequence()
            ?.firstOrNull { it.contains("activation_root=mtp://", ignoreCase = true) && it.contains("kindle", ignoreCase = true) }
            ?.substringAfter("activation_root=")?.trim() ?: return false
        Platform.run(gio, "mount", root)
        val storages = Platform.run(gio, "list", root, "-u")?.lines().orEmpty().filter { it.isNotBlank() }
            .map { it.trimEnd('/') + "/" } + listOf("${root}Internal Storage/", "${root}Internal%20Storage/")
        for (storage in storages) {
            if (Platform.run(gio, "copy", storage + VOCAB_PATH, dest.path) != null && dest.isFile) {
                log("vocab.db from $storage (gio)")
                return true
            }
        }
        return false
    }

    // ---- Windows -----------------------------------------------------------

    /**
     * Explorer's shell namespace: "This PC" (ShellSpecialFolder 17) → the
     * device whose name has Kindle in it → each storage → system → vocabulary.
     * CopyHere is asynchronous, so the script waits for the file to land.
     */
    private fun fromWindowsShell(dest: File): Boolean {
        val target = dest.parentFile.absolutePath.replace("'", "''")
        val script = """
            ${'$'}shell = New-Object -ComObject Shell.Application
            ${'$'}pc = ${'$'}shell.Namespace(17)
            ${'$'}kindle = ${'$'}pc.Items() | Where-Object { ${'$'}_.Name -like '*Kindle*' } | Select-Object -First 1
            if (-not ${'$'}kindle) { exit 2 }
            foreach (${'$'}storage in ${'$'}kindle.GetFolder.Items()) {
                ${'$'}sys = ${'$'}storage.GetFolder.Items() | Where-Object { ${'$'}_.Name -eq 'system' }
                if (-not ${'$'}sys) { continue }
                ${'$'}voc = ${'$'}sys.GetFolder.Items() | Where-Object { ${'$'}_.Name -eq 'vocabulary' }
                if (-not ${'$'}voc) { continue }
                ${'$'}db = ${'$'}voc.GetFolder.Items() | Where-Object { ${'$'}_.Name -eq 'vocab.db' -or ${'$'}_.Name -eq 'vocab' }
                if (-not ${'$'}db) { continue }
                ${'$'}out = '$target'
                Remove-Item -ErrorAction SilentlyContinue (Join-Path ${'$'}out 'vocab.db')
                ${'$'}shell.Namespace(${'$'}out).CopyHere(${'$'}db, 0x14)
                for (${'$'}i = 0; ${'$'}i -lt 120; ${'$'}i++) {
                    Start-Sleep -Milliseconds 500
                    ${'$'}f = Get-Item -ErrorAction SilentlyContinue (Join-Path ${'$'}out 'vocab.db')
                    if (${'$'}f -and ${'$'}f.Length -gt 0) { Start-Sleep -Milliseconds 500; exit 0 }
                }
                exit 3
            }
            exit 4
        """.trimIndent()
        dest.parentFile.mkdirs()
        val landed = File(dest.parentFile, "vocab.db")
        val ok = Platform.run("powershell", "-NoProfile", "-NonInteractive", "-Command", script, timeoutSeconds = 120) != null
        if (ok && landed.isFile) {
            if (landed != dest) landed.renameTo(dest)
            log("vocab.db from the Kindle (Windows shell)")
            return dest.isFile
        }
        return false
    }

    // ---- macOS -------------------------------------------------------------

    /** libmtp: find vocab.db's object id with mtp-files, fetch it with mtp-getfile. */
    private fun fromLibMtp(dest: File): Boolean {
        val files = Platform.which("mtp-files") ?: return false
        val get = Platform.which("mtp-getfile") ?: return false
        val listing = Platform.run(files, timeoutSeconds = 120) ?: return false
        // Blocks of "File ID: n" … "Filename: vocab.db".
        var id: String? = null
        var found: String? = null
        for (line in listing.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("File ID:")) id = t.substringAfter(':').trim()
            if (t.startsWith("Filename:") && t.substringAfter(':').trim() == "vocab.db") { found = id; break }
        }
        val fileId = found ?: return false
        if (Platform.run(get, fileId, dest.path, timeoutSeconds = 120) != null && dest.isFile) {
            log("vocab.db from the Kindle (libmtp)")
            return true
        }
        return false
    }

    companion object {
        const val VOCAB_PATH = "system/vocabulary/vocab.db"
        /** Amazon/Lab126, the vendor id the udev watcher matches. */
        const val AMAZON_VENDOR = "1949"
    }
}
