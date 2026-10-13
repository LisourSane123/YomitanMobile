package com.yomitanmobile.kindle

import java.io.File

/**
 * The operating system's own Japanese voice: the last slot, as system TTS is
 * on the phone. It is what a card gets when no person recorded the word and
 * VOICEVOX is not installed (or failed on that word) — a plain voice beats a
 * silent card.
 *
 * It is given the READING, never the headword: a system voice guesses a
 * kanji word's reading from no context (行った, 一日, 開く), while kana says
 * exactly what the card prints. It knows nothing of the pitch accent.
 *
 * - Linux: espeak-ng (or espeak) with its `ja` voice — robotic, but it is
 *   installed nearly everywhere.
 * - macOS: `say` with the first ja_JP voice (Kyoko ships with the system).
 * - Windows: SAPI through PowerShell's System.Speech, with a ja-JP voice —
 *   Haruka, present once the Japanese speech pack is installed.
 *
 * Each [wav] writes a WAV file and returns whether it did; null from [find]
 * means this machine has no Japanese voice at all.
 */
class SystemVoice private constructor(val name: String, private val say: (String, File) -> Boolean) {

    fun wav(text: String, out: File): Boolean {
        if (text.isBlank()) return false
        out.delete()
        return say(text, out) && out.length() > WAV_HEADER
    }

    companion object {
        private const val WAV_HEADER = 44L

        fun find(): SystemVoice? = when (Platform.os) {
            Platform.Os.LINUX -> espeak()
            Platform.Os.MAC -> macSay()
            Platform.Os.WINDOWS -> windowsSapi()
        }

        private fun espeak(): SystemVoice? {
            val binary = Platform.which("espeak-ng") ?: Platform.which("espeak") ?: return null
            val voices = Platform.run(binary, "--voices=ja", timeoutSeconds = 10) ?: return null
            if (voices.lineSequence().drop(1).none { it.trim().split(Regex("\\s+")).getOrNull(1) == "ja" }) return null
            return SystemVoice(File(binary).name) { text, out ->
                Platform.run(binary, "-v", "ja", "-s", "140", "-w", out.absolutePath, text, timeoutSeconds = 30) != null
            }
        }

        private fun macSay(): SystemVoice? {
            val binary = Platform.which("say") ?: return null
            // "Kyoko               ja_JP    # こんにちは、私の名前はKyokoです。"
            val voice = Platform.run(binary, "-v", "?", timeoutSeconds = 10)?.lineSequence()
                ?.firstOrNull { "ja_JP" in it }
                ?.substringBefore("ja_JP")?.trim()
                ?: return null
            return SystemVoice("say ($voice)") { text, out ->
                Platform.run(
                    binary, "-v", voice, "--file-format=WAVE", "--data-format=LEI16@22050",
                    "-o", out.absolutePath, text, timeoutSeconds = 30
                ) != null
            }
        }

        private fun windowsSapi(): SystemVoice? {
            val shell = Platform.which("powershell") ?: return null
            val voice = Platform.run(
                shell, "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Speech; " +
                    "(New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices() | " +
                    "Where-Object { \$_.Enabled -and \$_.VoiceInfo.Culture.Name -eq 'ja-JP' } | " +
                    "Select-Object -First 1 | ForEach-Object { \$_.VoiceInfo.Name }",
                timeoutSeconds = 30
            )?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // The text and the path go in through the environment: nothing
            // the dictionary holds can then break out of the script's quoting.
            val script = "Add-Type -AssemblyName System.Speech; " +
                "\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                "\$s.SelectVoice(\$env:KS_VOICE); " +
                "\$s.SetOutputToWaveFile(\$env:KS_OUT); " +
                "\$s.Speak(\$env:KS_TEXT); \$s.Dispose()"
            return SystemVoice("SAPI ($voice)") { text, out ->
                Platform.run(
                    shell, "-NoProfile", "-NonInteractive", "-Command", script, timeoutSeconds = 60,
                    env = mapOf("KS_VOICE" to voice, "KS_OUT" to out.absolutePath, "KS_TEXT" to text)
                ) != null
            }
        }
    }
}
