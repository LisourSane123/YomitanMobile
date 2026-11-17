package com.yomitanmobile.kindle

import kotlinx.coroutines.runBlocking
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """kindle-sync — Kindle Vocabulary Builder → Anki

  (no arguments)          open the window
  --gui                   open the window
  --dry-run               only report what would be added
  --all                   ignore the last-run marker and process every lookup
  --deck NAME             target deck (default: the configured one, Japanese)
  --wait SECONDS          how long to wait for the Kindle to appear
  --vocab FILE            use this vocab.db instead of the Kindle's
  --no-sync               skip the AnkiWeb sync before and after
  --quiet                 no desktop notifications
  --pull-settings         copy the phone's card style over adb and exit
  --refresh-audio         re-record the Audio field of every from_kindle card
  --refresh-cards         bring every Yomitan-Mobile-v8 note up to date (with --dry-run: report only)
  --convert-jj            give unlearned cards (never reviewed, not suspended) a JP-JP meaning (with --dry-run: report only)
  --scope SEARCH          with --convert-jj: only notes matching this Anki search, e.g. "tag:from_kindle added:4"
  --exclude WORD          with --refresh-cards / --convert-jj: leave this note alone (repeatable)
  --restyle               rewrite the note type's CSS and templates (with --dry-run: files only)
  --install-native-audio  download Kanji alive's native-speaker recordings (~74 MB)
  --check-duplicates Q    which notes matching Anki search Q hold a word another note holds

Settings: """

/**
 * With no arguments — a double-click — the window. With arguments, the
 * command line the Linux plug-in watcher (and anyone scripting it) uses: the
 * same flags kindle-sync.sh always took, so the watcher did not change.
 */
fun main(args: Array<String>) {
    val config = Config.load()
    uiLanguage = config.language
    if (args.isEmpty() || args.contentEquals(arrayOf("--gui"))) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display: run with --help for the command line.")
            exitProcess(2)
        }
        Window.open(config)
        return
    }
    exitProcess(commandLine(config, args.toList()))
}

private fun commandLine(config: Config, args: List<String>): Int {
    var options = SyncJob.Options()
    var command = "sync"
    var quiet = false
    var query = ""
    var scope = ""
    val excluded = LinkedHashSet<String>()
    var i = 0
    fun value(): String = args.getOrNull(++i) ?: run { System.err.println("${args[i - 1]} needs a value"); exitProcess(2) }
    while (i < args.size) {
        when (val a = args[i]) {
            "--dry-run" -> options = options.copy(dryRun = true)
            "--all" -> options = options.copy(all = true)
            "--deck" -> options = options.copy(deck = value())
            "--wait" -> options = options.copy(waitSeconds = value().toIntOrNull() ?: 0)
            "--vocab" -> options = options.copy(vocab = File(value()))
            "--no-sync" -> options = options.copy(sync = false)
            "--quiet" -> quiet = true
            "--pull-settings", "--refresh-audio", "--refresh-cards", "--convert-jj", "--restyle", "--install-native-audio" -> command = a.removePrefix("--")
            "--check-duplicates" -> { command = "check-duplicates"; query = value() }
            "--exclude" -> excluded += value()
            "--scope" -> scope = value()
            "-h", "--help" -> { println(USAGE + Config.FILE.path); return 0 }
            else -> { System.err.println("unknown option: $a\n\n$USAGE${Config.FILE.path}"); return 2 }
        }
        i++
    }
    val notifier = if (quiet) null else Notifier()
    val reporter = object : Reporter {
        override fun progress(message: String) {
            System.err.println("[kindle-sync] $message")
            notifier?.progress(message)
        }
        override fun log(message: String) = System.err.println("[kindle-sync] $message")
    }
    // Killed from outside (the watcher's timeout sends SIGTERM): the JVM still
    // runs its shutdown hooks, and the user is told the run did not finish.
    var finished = false
    Runtime.getRuntime().addShutdownHook(Thread {
        if (!finished) notifier?.done(tr("Nie wykonano: przebieg przerwany z zewnątrz.", "Not done: the run was stopped from outside.", "未完了: 外部から実行が停止されました。"), failed = true)
    })
    val outcome = try {
        runBlocking {
            val job = SyncJob(config, reporter)
            val maintenance = Maintenance(if (options.sync == false) config.copy(syncAnkiWeb = false) else config, reporter)
            when (command) {
                "sync" -> job.sync(options)
                "pull-settings" -> if (pullPhoneSettings(config, reporter::log)) {
                    Outcome(false, tr("Wykonano: styl fiszek pobrany z telefonu.", "Done: card style copied from the phone.", "完了: スマホのカードスタイルをコピーしました。"))
                } else {
                    Outcome(true, tr("Nie wykonano: nie pobrano stylu z telefonu (szczegóły wyżej).", "Not done: the phone's style was not copied (see above).", "未完了: スマホのスタイルをコピーできませんでした（上記参照）。"))
                }
                "refresh-audio" -> maintenance.refreshAudio()
                "refresh-cards" -> maintenance.refreshCards(options.dryRun, excluded)
                "convert-jj" -> maintenance.convertToJapanese(options.dryRun, excluded, scope)
                "restyle" -> maintenance.restyle(options.dryRun)
                "install-native-audio" -> maintenance.installNativeAudio()
                "check-duplicates" -> maintenance.checkDuplicates(query)
                else -> error(command)
            }
        }
    } catch (t: Throwable) {
        // Every way a run can end says so: a run that died silently used to
        // leave the last progress bubble standing, which reads as "still working".
        reporter.log("failed: $t")
        Outcome(true, tr("Nie wykonano: przebieg przerwany (${t.message ?: t.javaClass.simpleName}).", "Not done: the run stopped (${t.message ?: t.javaClass.simpleName}).", "未完了: 実行が停止しました（${t.message ?: t.javaClass.simpleName}）。"))
    }
    reporter.log(outcome.message)
    notifier?.done(outcome.message, outcome.failed)
    finished = true
    return if (outcome.failed) 1 else 0
}
