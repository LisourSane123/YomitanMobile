package com.yomitanmobile.ui.cardstyle

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.edit
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.CardStylePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CardStyleScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Style state
    var expressionBold by remember { mutableStateOf(true) }
    var expressionFontSize by remember { mutableFloatStateOf(48f) }
    var readingFontSize by remember { mutableFloatStateOf(28f) }
    var meaningFontSize by remember { mutableFloatStateOf(20f) }
    var selectedFont by remember { mutableStateOf("Hiragino Sans") }
    var backgroundColor by remember { mutableStateOf("#1a1a1a") }
    var expressionColor by remember { mutableStateOf("#ffffff") }
    var readingColor by remember { mutableStateOf("#80cbc4") }
    var meaningColor by remember { mutableStateOf("#e0e0e0") }
    var accentColor by remember { mutableStateOf("#80cbc4") }
    var showPitchAccent by remember { mutableStateOf(true) }
    var showFrequency by remember { mutableStateOf(true) }
    var showSentence by remember { mutableStateOf(true) }

    var randomFontsEnabled by remember { mutableStateOf(false) }
    var randomFonts by remember { mutableStateOf<Set<String>>(emptySet()) }
    var randomVoicesEnabled by remember { mutableStateOf(false) }
    var randomVoices by remember { mutableStateOf<Set<String>>(emptySet()) }
    var useOnlineSentenceApi by remember { mutableStateOf(false) }
    var sentenceApiConsentGranted by remember { mutableStateOf(false) }
    var showSentenceApiConsentDialog by remember { mutableStateOf(false) }
    var availableVoices by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeTts by remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }

    DisposableEffect(context) {
        var ttsInstance: android.speech.tts.TextToSpeech? = null
        ttsInstance = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                try {
                    val voices = ttsInstance?.voices?.filter { it.locale.language == "ja" }?.map { it.name } ?: emptyList()
                    availableVoices = voices.sorted()
                } catch (e: Exception) {}
            }
        }
        activeTts = ttsInstance
        onDispose { ttsInstance?.shutdown() }
    }

    // Load current preferences
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        expressionBold = prefs[MainActivity.CARD_EXPRESSION_BOLD] ?: true
        expressionFontSize = (prefs[MainActivity.CARD_EXPRESSION_FONT_SIZE] ?: 48).toFloat()
        readingFontSize = (prefs[MainActivity.CARD_READING_FONT_SIZE] ?: 28).toFloat()
        meaningFontSize = (prefs[MainActivity.CARD_MEANING_FONT_SIZE] ?: 20).toFloat()
        selectedFont = prefs[MainActivity.CARD_FONT_FAMILY] ?: "Hiragino Sans"
        backgroundColor = prefs[MainActivity.CARD_BACKGROUND_COLOR] ?: "#1a1a1a"
        expressionColor = prefs[MainActivity.CARD_EXPRESSION_COLOR] ?: "#ffffff"
        readingColor = prefs[MainActivity.CARD_READING_COLOR] ?: "#80cbc4"
        meaningColor = prefs[MainActivity.CARD_MEANING_COLOR] ?: "#e0e0e0"
        accentColor = prefs[MainActivity.CARD_ACCENT_COLOR] ?: "#80cbc4"
        showPitchAccent = prefs[MainActivity.CARD_SHOW_PITCH] ?: true
        showFrequency = prefs[MainActivity.CARD_SHOW_FREQUENCY] ?: true
        showSentence = prefs[MainActivity.CARD_SHOW_SENTENCE] ?: true
        randomFontsEnabled = prefs[MainActivity.CARD_RANDOM_FONTS_ENABLED] ?: false
        randomFonts = prefs[MainActivity.CARD_RANDOM_FONTS] ?: emptySet()
        randomVoicesEnabled = prefs[MainActivity.TTS_RANDOM_VOICES_ENABLED] ?: false
        randomVoices = prefs[MainActivity.TTS_RANDOM_VOICES] ?: emptySet()
        useOnlineSentenceApi = prefs[MainActivity.CARD_USE_ONLINE_SENTENCE_API] ?: false
        sentenceApiConsentGranted = prefs[MainActivity.SENTENCE_API_CONSENT_GRANTED] ?: false
    }

    fun currentPreferences() = CardStylePreferences(
        expressionBold = expressionBold,
        expressionFontSize = expressionFontSize.roundToInt(),
        readingFontSize = readingFontSize.roundToInt(),
        meaningFontSize = meaningFontSize.roundToInt(),
        fontFamily = selectedFont,
        cardBackgroundColor = backgroundColor,
        expressionColor = expressionColor,
        readingColor = readingColor,
        meaningColor = meaningColor,
        accentColor = accentColor,
        showPitchAccent = showPitchAccent,
        showFrequency = showFrequency,
        showSentence = showSentence,
        randomFontsEnabled = randomFontsEnabled,
        randomFonts = randomFonts,
        randomVoicesEnabled = randomVoicesEnabled,
        randomVoices = randomVoices,
        useOnlineSentenceApi = useOnlineSentenceApi,
        onlineSentenceApiConsentGranted = sentenceApiConsentGranted
    )

    fun savePreferences() {
        coroutineScope.launch {
            context.dataStore.edit { prefs ->
                prefs[MainActivity.CARD_EXPRESSION_BOLD] = expressionBold
                prefs[MainActivity.CARD_EXPRESSION_FONT_SIZE] = expressionFontSize.roundToInt()
                prefs[MainActivity.CARD_READING_FONT_SIZE] = readingFontSize.roundToInt()
                prefs[MainActivity.CARD_MEANING_FONT_SIZE] = meaningFontSize.roundToInt()
                prefs[MainActivity.CARD_FONT_FAMILY] = selectedFont
                prefs[MainActivity.CARD_BACKGROUND_COLOR] = backgroundColor
                prefs[MainActivity.CARD_EXPRESSION_COLOR] = expressionColor
                prefs[MainActivity.CARD_READING_COLOR] = readingColor
                prefs[MainActivity.CARD_MEANING_COLOR] = meaningColor
                prefs[MainActivity.CARD_ACCENT_COLOR] = accentColor
                prefs[MainActivity.CARD_SHOW_PITCH] = showPitchAccent
                prefs[MainActivity.CARD_SHOW_FREQUENCY] = showFrequency
                prefs[MainActivity.CARD_SHOW_SENTENCE] = showSentence
                prefs[MainActivity.CARD_RANDOM_FONTS_ENABLED] = randomFontsEnabled
                prefs[MainActivity.CARD_RANDOM_FONTS] = randomFonts
                prefs[MainActivity.TTS_RANDOM_VOICES_ENABLED] = randomVoicesEnabled
                prefs[MainActivity.TTS_RANDOM_VOICES] = randomVoices
                prefs[MainActivity.CARD_USE_ONLINE_SENTENCE_API] = useOnlineSentenceApi
                prefs[MainActivity.SENTENCE_API_CONSENT_GRANTED] = sentenceApiConsentGranted
            }
        }
    }

    var previewExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    if (showSentenceApiConsentDialog) {
        AlertDialog(
            onDismissRequest = { showSentenceApiConsentDialog = false },
            title = { Text("Zgoda na API zdań") },
            text = {
                Text(
                    "Po włączeniu aplikacja będzie wysyłać wyszukiwane słowo do zewnętrznego API " +
                        "w celu pobrania przykładowego zdania. Możesz cofnąć zgodę w Ustawieniach."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sentenceApiConsentGranted = true
                    useOnlineSentenceApi = true
                    coroutineScope.launch {
                        context.dataStore.edit { prefs ->
                            prefs[MainActivity.SENTENCE_API_CONSENT_GRANTED] = true
                        }
                    }
                    showSentenceApiConsentDialog = false
                }) {
                    Text("Wyrażam zgodę")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    useOnlineSentenceApi = false
                    showSentenceApiConsentDialog = false
                }) {
                    Text("Anuluj")
                }
            }
        )
    }

    // Preview HTML updates live (without saving)
    val previewHtml = remember(
        expressionBold, expressionFontSize, readingFontSize, meaningFontSize,
        selectedFont, backgroundColor, expressionColor, readingColor,
        meaningColor, accentColor, showPitchAccent, showFrequency, showSentence
    ) {
        AnkiCardCreator.buildPreviewHtml(currentPreferences())
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Wygląd fiszki") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wróć")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        // Reset to defaults
                        expressionBold = true
                        expressionFontSize = 48f
                        readingFontSize = 28f
                        meaningFontSize = 20f
                        selectedFont = "Hiragino Sans"
                        backgroundColor = "#1a1a1a"
                        expressionColor = "#ffffff"
                        readingColor = "#80cbc4"
                        meaningColor = "#e0e0e0"
                        accentColor = "#80cbc4"
                        showPitchAccent = true
                        showFrequency = true
                        showSentence = true
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Live Preview
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Podgląd fiszki",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { previewExpanded = !previewExpanded }) {
                        Text(if (previewExpanded) "Zwiń" else "Rozwiń")
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            if (previewExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (previewExpanded) 640.dp else 320.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = false
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                isVerticalScrollBarEnabled = true
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                loadDataWithBaseURL(null, previewHtml, "text/html", "utf-8", null)
                            }
                        },
                        update = { webView ->
                            webView.loadDataWithBaseURL(null, previewHtml, "text/html", "utf-8", null)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Font section
            item {
                Text(
                    "Czcionka",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CardStylePreferences.FONT_FAMILIES.forEach { font ->
                        FilterChip(
                            selected = selectedFont == font,
                            onClick = { selectedFont = font },
                            label = { Text(font, fontSize = 12.sp) }
                        )
                    }
                }
            }

            // Bold toggle
            item {
                SettingRow(
                    title = "Pogrubione słowo",
                    subtitle = "Główne wyrażenie na fiszce będzie pogrubione"
                ) {
                    Switch(
                        checked = expressionBold,
                        onCheckedChange = { expressionBold = it }
                    )
                }
            }

            // Font sizes
            item {
                Text(
                    "Rozmiary czcionek",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                FontSizeSlider(
                    label = "Wyrażenie",
                    value = expressionFontSize,
                    onValueChange = { expressionFontSize = it },
                    valueRange = 24f..72f
                )
            }

            item {
                FontSizeSlider(
                    label = "Czytanie",
                    value = readingFontSize,
                    onValueChange = { readingFontSize = it },
                    valueRange = 16f..48f
                )
            }

            item {
                FontSizeSlider(
                    label = "Znaczenie",
                    value = meaningFontSize,
                    onValueChange = { meaningFontSize = it },
                    valueRange = 12f..36f
                )
            }

            // Colors
            item {
                Text(
                    "Kolory",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                ColorPickerRow(
                    label = "Tło karty",
                    currentColor = backgroundColor,
                    presetColors = listOf("#1a1a1a", "#000000", "#1e1e2e", "#2d2d2d", "#0d1117", "#1a1b26"),
                    onColorSelected = { backgroundColor = it }
                )
            }

            item {
                ColorPickerRow(
                    label = "Kolor wyrażenia",
                    currentColor = expressionColor,
                    presetColors = listOf("#ffffff", "#e0e0e0", "#bb86fc", "#03dac6", "#ff7043", "#ffb74d"),
                    onColorSelected = { expressionColor = it }
                )
            }

            item {
                ColorPickerRow(
                    label = "Kolor czytania",
                    currentColor = readingColor,
                    presetColors = listOf("#80cbc4", "#03dac6", "#64b5f6", "#81c784", "#ffb74d", "#ce93d8"),
                    onColorSelected = { readingColor = it }
                )
            }

            item {
                ColorPickerRow(
                    label = "Kolor znaczenia",
                    currentColor = meaningColor,
                    presetColors = listOf("#e0e0e0", "#ffffff", "#b0bec5", "#cfd8dc", "#a5d6a7", "#ffcc80"),
                    onColorSelected = { meaningColor = it }
                )
            }

            item {
                ColorPickerRow(
                    label = "Kolor akcentu",
                    currentColor = accentColor,
                    presetColors = listOf("#80cbc4", "#03dac6", "#bb86fc", "#ff7043", "#64b5f6", "#ffb74d"),
                    onColorSelected = { accentColor = it }
                )
            }

            // Visibility toggles
            item {
                Text(
                    "Widoczność elementów",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                SettingRow(
                    title = "Pitch accent",
                    subtitle = "Pokaż wzorzec akcentu tonalnego"
                ) {
                    Switch(
                        checked = showPitchAccent,
                        onCheckedChange = { showPitchAccent = it }
                    )
                }
            }

            item {
                SettingRow(
                    title = "Częstotliwość",
                    subtitle = "Pokaż ranking częstotliwości słowa"
                ) {
                    Switch(
                        checked = showFrequency,
                        onCheckedChange = { showFrequency = it }
                    )
                }
            }

            item {
                SettingRow(
                    title = "Przykładowe zdanie",
                    subtitle = "Pokaż przykładowe zdanie na fiszce"
                ) {
                    Switch(
                        checked = showSentence,
                        onCheckedChange = { showSentence = it }
                    )
                }
            }

            item {
                SettingRow(
                    title = "Zdanie z internetu (API)",
                    subtitle = "Pobieraj online zdanie do fiszki, gdy dostępne"
                ) {
                    Switch(
                        checked = useOnlineSentenceApi,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                useOnlineSentenceApi = false
                            } else if (sentenceApiConsentGranted) {
                                useOnlineSentenceApi = true
                            } else {
                                showSentenceApiConsentDialog = true
                            }
                        }
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Losowa czcionka słowa",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Przy eksporcie fiszki, słowo na froncie otrzyma losową czcionkę z wybranych",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Switch(
                                checked = randomFontsEnabled,
                                onCheckedChange = { randomFontsEnabled = it }
                            )
                        }

                        if (randomFontsEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CardStylePreferences.FONT_FAMILIES.forEach { font ->
                                    val isSelected = randomFonts.contains(font)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            randomFonts = if (isSelected) {
                                                randomFonts.minus(font)
                                            } else {
                                                randomFonts.plus(font)
                                            }
                                        },
                                        label = { Text(text = font) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Losowy głos TTS (Japoński)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Przy eksporcie fiszki z audio TTS, użyty zostanie losowy głos z wybranych",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Switch(
                                checked = randomVoicesEnabled,
                                onCheckedChange = { randomVoicesEnabled = it }
                            )
                        }

                        if (randomVoicesEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                availableVoices.forEach { voice ->
                                    val isSelected = randomVoices.contains(voice)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            randomVoices = if (isSelected) {
                                                randomVoices.minus(voice)
                                            } else {
                                                activeTts?.let { tmpTts ->
                                                    tmpTts.voices?.find { it.name == voice }?.let { selectedVoice ->
                                                        tmpTts.voice = selectedVoice
                                                        tmpTts.speak("たべる", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
                                                    }
                                                }
                                                randomVoices.plus(voice)
                                            }
                                        },
                                        label = { Text(text = voice) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Save button
            item {
                Button(
                    onClick = {
                        savePreferences()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Ustawienia fiszki zapisane ✓")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Zapisz", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            trailing()
        }
    }
}

@Composable
private fun FontSizeSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, fontWeight = FontWeight.Medium)
                Text(
                    "${value.roundToInt()} px",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = ((valueRange.endInclusive - valueRange.start) / 2).roundToInt() - 1
            )
        }
    }
}

@Composable
private fun ColorPickerRow(
    label: String,
    currentColor: String,
    presetColors: List<String>,
    onColorSelected: (String) -> Unit
) {
    var showCustomPicker by remember { mutableStateOf(false) }
    var customR by remember { mutableFloatStateOf(128f) }
    var customG by remember { mutableFloatStateOf(128f) }
    var customB by remember { mutableFloatStateOf(128f) }

    // Re-init sliders from current color whenever dialog opens
    LaunchedEffect(showCustomPicker) {
        if (showCustomPicker) {
            val c = try {
                android.graphics.Color.parseColor(currentColor)
            } catch (_: Exception) {
                android.graphics.Color.parseColor("#808080")
            }
            customR = android.graphics.Color.red(c).toFloat()
            customG = android.graphics.Color.green(c).toFloat()
            customB = android.graphics.Color.blue(c).toFloat()
        }
    }

    val customHex = "#%02x%02x%02x".format(customR.toInt(), customG.toInt(), customB.toInt())
    val customPreviewColor = Color(customR / 255f, customG / 255f, customB / 255f)
    val isCustomSelected = currentColor !in presetColors

    if (showCustomPicker) {
        AlertDialog(
            onDismissRequest = { showCustomPicker = false },
            title = { Text("Własny kolor – $label") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Color preview swatch
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(customPreviewColor)
                    )
                    Text(
                        customHex.uppercase(),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(4.dp))
                    // R slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("R", fontWeight = FontWeight.Bold, color = Color.Red)
                        Text("${customR.toInt()}", fontWeight = FontWeight.Bold, color = Color.Red)
                    }
                    Slider(
                        value = customR,
                        onValueChange = { customR = it },
                        valueRange = 0f..255f
                    )
                    // G slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("G", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                        Text("${customG.toInt()}", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                    }
                    Slider(
                        value = customG,
                        onValueChange = { customG = it },
                        valueRange = 0f..255f
                    )
                    // B slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("B", fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                        Text("${customB.toInt()}", fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                    }
                    Slider(
                        value = customB,
                        onValueChange = { customB = it },
                        valueRange = 0f..255f
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onColorSelected(customHex)
                    showCustomPicker = false
                }) { Text("Wybierz") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomPicker = false }) { Text("Anuluj") }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                presetColors.forEach { colorHex ->
                    val color = parseHexColor(colorHex)
                    val isSelected = colorHex == currentColor
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (isSelected) Modifier.border(
                                    3.dp,
                                    MaterialTheme.colorScheme.primary,
                                    CircleShape
                                ) else Modifier.border(
                                    1.dp,
                                    Color.Gray.copy(alpha = 0.5f),
                                    CircleShape
                                )
                            )
                            .clickable { onColorSelected(colorHex) }
                    )
                }
                // Custom color button — shows current custom color if selected, or a + circle
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCustomSelected) parseHexColor(currentColor)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            if (isCustomSelected) 3.dp else 2.dp,
                            if (isCustomSelected) MaterialTheme.colorScheme.primary
                            else Color.Gray.copy(alpha = 0.6f),
                            CircleShape
                        )
                        .clickable { showCustomPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (!isCustomSelected) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Własny kolor",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun parseHexColor(hex: String): Color {
    return try {
        val colorInt = android.graphics.Color.parseColor(hex)
        Color(colorInt)
    } catch (_: Exception) {
        Color.Gray
    }
}
