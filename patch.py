import re

with open("app/src/main/java/com/yomitanmobile/ui/cardstyle/CardStyleScreen.kt", "r") as f:
    content = f.read()

# Add imports
content = content.replace("import androidx.compose.runtime.LaunchedEffect", "import androidx.compose.runtime.DisposableEffect\nimport androidx.compose.foundation.layout.ExperimentalLayoutApi\nimport androidx.compose.foundation.layout.FlowRow\nimport androidx.compose.runtime.LaunchedEffect")

# Add state variables
state_vars = """    var showSentence by remember { mutableStateOf(true) }

    var randomFontsEnabled by remember { mutableStateOf(false) }
    var randomFonts by remember { mutableStateOf<Set<String>>(emptySet()) }
    var randomVoicesEnabled by remember { mutableStateOf(false) }
    var randomVoices by remember { mutableStateOf<Set<String>>(emptySet()) }
    var availableVoices by remember { mutableStateOf<List<String>>(emptyList()) }

    DisposableEffect(context) {
        var tts: android.speech.tts.TextToSpeech? = null
        tts = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                try {
                    val voices = tts?.voices?.filter { it.locale.language == "ja" }?.map { it.name } ?: emptyList()
                    availableVoices = voices.sorted()
                } catch (e: Exception) {}
            }
        }
        onDispose { tts?.shutdown() }
    }
"""
content = re.sub(r"    var showSentence by remember \{ mutableStateOf\(true\) \}\n", state_vars, content)

# Load preferences
load_prefs = """        showSentence = prefs[MainActivity.CARD_SHOW_SENTENCE] ?: true
        randomFontsEnabled = prefs[MainActivity.CARD_RANDOM_FONTS_ENABLED] ?: false
        randomFonts = prefs[MainActivity.CARD_RANDOM_FONTS] ?: emptySet()
        randomVoicesEnabled = prefs[MainActivity.TTS_RANDOM_VOICES_ENABLED] ?: false
        randomVoices = prefs[MainActivity.TTS_RANDOM_VOICES] ?: emptySet()
"""
content = content.replace("        showSentence = prefs[MainActivity.CARD_SHOW_SENTENCE] ?: true\n", load_prefs)


# currentPreferences()
curr_prefs = """        showSentence = showSentence,
        randomFontsEnabled = randomFontsEnabled,
        randomFonts = randomFonts,
        randomVoicesEnabled = randomVoicesEnabled,
        randomVoices = randomVoices
    )"""
content = re.sub(r"        showSentence = showSentence\n    \)", curr_prefs, content)

# savePreferences()
save_prefs = """                prefs[MainActivity.CARD_SHOW_SENTENCE] = showSentence
                prefs[MainActivity.CARD_RANDOM_FONTS_ENABLED] = randomFontsEnabled
                prefs[MainActivity.CARD_RANDOM_FONTS] = randomFonts
                prefs[MainActivity.CARD_RANDOM_FONTS_ENABLED] = randomFontsEnabled
                prefs[MainActivity.TTS_RANDOM_VOICES_ENABLED] = randomVoicesEnabled
                prefs[MainActivity.TTS_RANDOM_VOICES] = randomVoices
"""
content = content.replace("                prefs[MainActivity.CARD_SHOW_SENTENCE] = showSentence\n", save_prefs)

# Add UI sections before Save button
ui_sections = """
            // RANDOM FONTS
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Losowanie czcionki", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("Losuj wybraną czcionkę dla wyrażenia", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = randomFontsEnabled, onCheckedChange = { randomFontsEnabled = it })
                        }
                        if (randomFontsEnabled) {
                            Spacer(Modifier.height(8.dp))
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CardStylePreferences.FONT_FAMILIES.forEach { font ->
                                    FilterChip(
                                        selected = randomFonts.contains(font),
                                        onClick = {
                                            val newSet = randomFonts.toMutableSet()
                                            if (newSet.contains(font)) newSet.remove(font) else newSet.add(font)
                                            randomFonts = newSet
                                        },
                                        label = { Text(font) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // RANDOM VOICES
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Losowanie głosu TTS", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("Jeśli włączone, dźwięk wygeneruje jeden z losowych głosów", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = randomVoicesEnabled, onCheckedChange = { randomVoicesEnabled = it })
                        }
                        if (randomVoicesEnabled) {
                            Spacer(Modifier.height(8.dp))
                            if (availableVoices.isEmpty()) {
                                Text("Pobieranie lub brak japońskich głosów...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    availableVoices.forEach { voiceName ->
                                        FilterChip(
                                            selected = randomVoices.contains(voiceName),
                                            onClick = {
                                                val newSet = randomVoices.toMutableSet()
                                                if (newSet.contains(voiceName)) newSet.remove(voiceName) else newSet.add(voiceName)
                                                randomVoices = newSet
                                            },
                                            label = { Text(voiceName.takeLast(10)) } // names can be very long
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Save button
"""
content = content.replace("            // Save button\n", ui_sections)


with open("app/src/main/java/com/yomitanmobile/ui/cardstyle/CardStyleScreen.kt", "w") as f:
    f.write(content)

