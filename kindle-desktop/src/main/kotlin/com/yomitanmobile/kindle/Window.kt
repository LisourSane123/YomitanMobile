package com.yomitanmobile.kindle

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Plug the Kindle in, click the button. The whole interface is one window that
 * says what it is doing and stays open when it is done — which is the point:
 * a run started by hand never leaves anyone guessing whether it finished.
 */
class Window internal constructor(private var config: Config) {

    private val frame = JFrame("Kindle → Anki")
    private val status = JLabel()
    private val progress = JProgressBar().apply { isVisible = false }
    private val logArea = JTextArea(12, 60).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private val syncButton = JButton(tr("Synchronizuj z Kindle", "Sync from Kindle", "Kindleから同期"))
    private val fileButton = JButton(tr("Z pliku vocab.db…", "From a vocab.db file…", "vocab.dbファイルから…"))
    private val settingsButton = JButton(tr("Ustawienia…", "Settings…", "設定…"))
    private val reportButton = JButton(tr("Raport", "Report", "レポート"))
    private val dryRun = JCheckBox(tr("Tylko podgląd (nic nie dodawaj)", "Preview only (add nothing)", "プレビューのみ（何も追加しない）"))

    internal fun build(visible: Boolean = true) {
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        val root = JPanel(BorderLayout(0, 12)).apply { border = BorderFactory.createEmptyBorder(16, 16, 16, 16) }

        val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val title = JLabel("Kindle → Anki").apply { font = font.deriveFont(Font.BOLD, 22f) }
        status.font = status.font.deriveFont(15f)
        progress.maximumSize = Dimension(Int.MAX_VALUE, 8)
        listOf(title, Box.createVerticalStrut(8), status, Box.createVerticalStrut(10), progress).forEach {
            (it as? javax.swing.JComponent)?.alignmentX = 0f
            top.add(it)
        }

        syncButton.font = syncButton.font.deriveFont(Font.BOLD, 15f)
        syncButton.addActionListener { start(SyncJob.Options(dryRun = dryRun.isSelected, waitSeconds = 20)) }
        fileButton.addActionListener {
            val chooser = JFileChooser().apply {
                dialogTitle = tr("Wskaż vocab.db (Kindle: system/vocabulary)", "Pick vocab.db (Kindle: system/vocabulary)", "vocab.dbを選ぶ（Kindle: system/vocabulary）")
                fileFilter = FileNameExtensionFilter("vocab.db", "db")
            }
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                start(SyncJob.Options(dryRun = dryRun.isSelected, vocab = chooser.selectedFile))
            }
        }
        settingsButton.addActionListener { settings() }
        reportButton.addActionListener { open(File(Platform.stateDir, "last-run/kindle-sync.tsv")) }
        reportButton.isEnabled = File(Platform.stateDir, "last-run/kindle-sync.tsv").isFile

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(syncButton); add(Box.createHorizontalStrut(8))
            add(fileButton); add(Box.createHorizontalStrut(8))
            add(settingsButton); add(Box.createHorizontalStrut(8))
            add(reportButton)
        }
        val controls = JPanel(BorderLayout(0, 6)).apply {
            add(buttons, BorderLayout.NORTH)
            add(dryRun, BorderLayout.SOUTH)
        }
        val north = JPanel(BorderLayout(0, 14)).apply {
            add(top, BorderLayout.NORTH)
            add(controls, BorderLayout.SOUTH)
        }
        root.add(north, BorderLayout.NORTH)
        root.add(JScrollPane(logArea).apply { border = BorderFactory.createTitledBorder(tr("Szczegóły", "Details", "詳細")) }, BorderLayout.CENTER)

        frame.contentPane = root
        // The accent-coloured button, and the one Enter presses.
        frame.rootPane.defaultButton = syncButton
        frame.minimumSize = Dimension(620, 440)
        frame.pack()
        frame.setLocationRelativeTo(null)
        showIdle()
        frame.isVisible = visible
    }

    /** The window's content, for the screenshot test. */
    internal val content get() = frame.contentPane

    private fun showIdle() {
        val problems = config.problems()
        if (problems.isNotEmpty()) {
            setStatus(problems.joinToString(" "), failed = true)
            syncButton.isEnabled = false
            fileButton.isEnabled = false
        } else {
            setStatus(tr("Podłącz Kindle kablem USB, odblokuj jego ekran i kliknij „Synchronizuj”.",
                "Plug the Kindle in over USB, unlock its screen and click “Sync”.", "KindleをUSBで接続し、画面のロックを解除して「Kindleから同期」をクリックしてください。"), failed = null)
            syncButton.isEnabled = true
            fileButton.isEnabled = true
        }
    }

    /** [failed]: null = neutral, false = done, true = failed. */
    private fun setStatus(text: String, failed: Boolean?) {
        val color = when (failed) {
            true -> "#d9534f"
            false -> "#3c9a5f"
            null -> null
        }
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;")
        status.text = "<html><body style='width: 520px'>" +
            (if (color != null) "<span style='color: $color'>$escaped</span>" else escaped) + "</body></html>"
    }

    private fun appendLog(line: String) {
        logArea.append(line + "\n")
        logArea.caretPosition = logArea.document.length
    }

    private fun start(options: SyncJob.Options) {
        listOf(syncButton, fileButton, settingsButton, reportButton, dryRun).forEach { it.isEnabled = false }
        progress.isIndeterminate = true
        progress.isVisible = true
        logArea.text = ""
        val reporter = object : Reporter {
            override fun progress(message: String) = SwingUtilities.invokeLater {
                setStatus(message, failed = null)
                appendLog("» $message")
            }
            override fun log(message: String) = SwingUtilities.invokeLater { appendLog(message) }
        }
        Thread({
            val outcome = runCatching { runBlocking { SyncJob(config, reporter).sync(options) } }
                .getOrElse { Outcome(true, tr("Nie wykonano: ", "Not done: ", "未完了: ") + (it.message ?: it.toString())) }
            SwingUtilities.invokeLater {
                progress.isVisible = false
                setStatus(outcome.message, failed = outcome.failed)
                appendLog("» ${outcome.message}")
                listOf(syncButton, fileButton, settingsButton, dryRun).forEach { it.isEnabled = true }
                reportButton.isEnabled = File(Platform.stateDir, "last-run/kindle-sync.tsv").isFile
                frame.toFront()
            }
        }, "kindle-sync").apply { isDaemon = true }.start()
    }

    private fun open(file: File) {
        runCatching { Desktop.getDesktop().open(file) }
            .onFailure { appendLog(tr("Nie da się otworzyć ${file.path}", "Cannot open ${file.path}", "${file.path}を開けません")) }
    }

    /** Everything [Config] holds, one row each. */
    private fun settings() {
        val dialog = JDialog(frame, tr("Ustawienia", "Settings", "設定"), true)
        val panel = JPanel(GridBagLayout()).apply { border = BorderFactory.createEmptyBorder(14, 14, 14, 14) }
        var row = 0
        fun label(text: String) = panel.add(JLabel(text), GridBagConstraints().apply {
            gridx = 0; gridy = row; anchor = GridBagConstraints.WEST; insets = Insets(4, 0, 4, 10)
        })
        fun field(component: java.awt.Component, extra: java.awt.Component? = null) {
            panel.add(component, GridBagConstraints().apply {
                gridx = 1; gridy = row; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = Insets(4, 0, 4, 6)
            })
            extra?.let { panel.add(it, GridBagConstraints().apply { gridx = 2; gridy = row }) }
            row++
        }
        fun fileRow(text: String, current: File?, directory: Boolean = false): JTextField {
            label(text)
            val box = JTextField(current?.path.orEmpty(), 34)
            val pick = JButton("…").apply {
                addActionListener {
                    val chooser = JFileChooser(box.text.takeIf { it.isNotBlank() }?.let { File(it).parentFile })
                    if (directory) chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    else chooser.fileFilter = FileNameExtensionFilter("Yomitan .zip", "zip")
                    if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) box.text = chooser.selectedFile.path
                }
            }
            field(box, pick)
            return box
        }

        val dict = fileRow(tr("Słownik (Jitendex .zip) *", "Dictionary (Jitendex .zip) *", "辞書（Jitendex .zip）*"), config.dictionary)
        val freq = fileRow(tr("Lista częstości (.zip)", "Frequency list (.zip)", "頻度リスト（.zip）"), config.frequency)
        val pitch = fileRow(tr("Akcent (kanjium .zip)", "Pitch accent (kanjium .zip)", "ピッチアクセント（kanjium .zip）"), config.pitch)
        val kanji = fileRow(tr("Kanji (KANJIDIC .zip)", "Kanji (KANJIDIC .zip)", "漢字（KANJIDIC .zip）"), config.kanji)
        val monolingual = (0 until 3).map { i ->
            fileRow(tr("Słownik JP-JP ${i + 1} (opcjonalnie)", "JP-JP dictionary ${i + 1} (optional)", "国語辞典 ${i + 1}（任意）"), config.monolingual.getOrNull(i))
        }
        val onomatopoeia = fileRow(tr("Onomatopeje (surasura .zip)", "Onomatopoeia (surasura .zip)", "オノマトペ（surasura .zip）"), config.onomatopoeia)
        label(tr("Talia", "Deck", "デッキ")); val deck = JTextField(config.deck); field(deck)
        label("AnkiConnect"); val connect = JTextField(config.ankiConnect); field(connect)
        val sync = JCheckBox(tr("Synchronizuj z AnkiWeb przed i po", "Sync with AnkiWeb before and after", "前後にAnkiWebと同期する"), config.syncAnkiWeb)
        label(""); field(sync)
        val languages = arrayOf("Polski", "English")
        val uiCodes = arrayOf("pl", "en", "ja")
        label(tr("Język okna", "Window language", "ウィンドウの言語"))
        // The window can speak Japanese; the card labels stay PL/EN (a Japanese
        // meaning gets Japanese labels by itself).
        val ui = JComboBox(languages + "日本語").apply { selectedIndex = uiCodes.indexOf(config.language).coerceAtLeast(0) }; field(ui)
        label(tr("Język etykiet na fiszkach", "Card label language", "カードのラベルの言語"))
        val labels = JComboBox(languages).apply { selectedIndex = if (config.englishLabels) 1 else 0 }; field(labels)
        val voicevox = fileRow(tr("VOICEVOX (folder, opcjonalnie)", "VOICEVOX (folder, optional)", "VOICEVOX（フォルダー、任意）"), config.voicevox, directory = true)
        val archive = fileRow(tr("Własne nagrania (folder, opcjonalnie)", "Own recordings (folder, optional)", "自分の録音（フォルダー、任意）"), config.audioArchive, directory = true)

        val hint = JLabel(tr(
            "<html>* Wymagane. Słowniki to te same pliki .zip, które instaluje aplikacja na telefonie (Yomitan).<br>Anki musi mieć dodatek AnkiConnect (kod 2055492159).</html>",
            "<html>* Required. The dictionaries are the same Yomitan .zip files the phone app installs.<br>Anki needs the AnkiConnect add-on (code 2055492159).</html>", "<html>* 必須。辞書はスマホのアプリがインストールするのと同じYomitanの.zipファイルです。<br>AnkiにはAnkiConnectアドオン（コード 2055492159）が必要です。</html>")).apply { foreground = Color.GRAY }
        panel.add(hint, GridBagConstraints().apply { gridx = 0; gridy = row++; gridwidth = 3; anchor = GridBagConstraints.WEST; insets = Insets(10, 0, 6, 0) })

        val save = JButton(tr("Zapisz", "Save", "保存")).apply {
            addActionListener {
                fun f(box: JTextField) = box.text.trim().takeIf { it.isNotEmpty() }?.let(::File)
                config = config.copy(
                    dictionary = f(dict), frequency = f(freq), pitch = f(pitch), kanji = f(kanji),
                    // A fourth one put in config.properties by hand is kept.
                    monolingual = monolingual.mapNotNull(::f) + config.monolingual.drop(monolingual.size),
                    onomatopoeia = f(onomatopoeia),
                    deck = deck.text.trim().ifEmpty { "Japanese" },
                    ankiConnect = connect.text.trim().ifEmpty { "http://127.0.0.1:8765" },
                    syncAnkiWeb = sync.isSelected,
                    language = uiCodes[ui.selectedIndex],
                    englishLabels = labels.selectedIndex == 1,
                    voicevox = f(voicevox) ?: config.voicevox,
                    audioArchive = f(archive) ?: config.audioArchive
                )
                config.save()
                dialog.dispose()
                if (config.language != uiLanguage) {
                    appendLog(tr("Język okna zmieni się po ponownym uruchomieniu.", "The window language changes after a restart.", "ウィンドウの言語は再起動後に変わります。"))
                }
                showIdle()
            }
        }
        val cancel = JButton(tr("Anuluj", "Cancel", "キャンセル")).apply { addActionListener { dialog.dispose() } }
        panel.add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { add(cancel); add(save) },
            GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 3; anchor = GridBagConstraints.EAST; insets = Insets(8, 0, 0, 0) })

        dialog.contentPane = panel
        dialog.pack()
        dialog.setLocationRelativeTo(frame)
        dialog.isVisible = true
    }

    companion object {
        fun open(config: Config) {
            // Follow the system's dark mode where the JVM can see it (GNOME/KDE
            // export it differently; a dark GTK theme name is the common signal).
            val dark = System.getenv("GTK_THEME")?.contains("dark", ignoreCase = true) == true ||
                Platform.run("defaults", "read", "-g", "AppleInterfaceStyle", timeoutSeconds = 3)?.contains("Dark") == true
            runCatching { if (dark) FlatDarkLaf.setup() else FlatLightLaf.setup() }
                .onFailure { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            SwingUtilities.invokeLater { Window(config).build() }
        }
    }
}
