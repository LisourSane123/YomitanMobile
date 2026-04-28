package com.yomitanmobile.ui.statistics

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.util.WordCategoryClassifier
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val isEnglish = LocalConfiguration.current.locales.get(0).language.equals("en", ignoreCase = true)
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val weeklyWordsForCopy = remember(state.weeklyLearnedWords, isEnglish) {
        StatisticsViewModel.buildWeeklyLearnedWordsCopyText(state.weeklyLearnedWords, isEnglish)
    }
    val allTimeCategoryStats = remember(state.categoryActivityAllTime, isEnglish) {
        val countsByCode = state.categoryActivityAllTime.associate { it.categoryCode to it.count }
        WordCategoryClassifier.mostImportantCategories(isEnglish).map { (code, label) ->
            CategoryActivity(
                categoryCode = code,
                categoryLabel = label,
                count = countsByCode[code] ?: 0
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statystyki") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wróć")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Streak card (only if daily goal is set)
                if (state.dailyGoal > 0) {
                    item {
                        StreakCard(
                            streak = state.streak,
                            dailyGoal = state.dailyGoal,
                            todayCount = state.dailyCounts.lastOrNull()?.count ?: 0
                        )
                    }
                }

                item {
                    Text(
                        "Przegląd",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // Decorative banner / image for statistics (requested)
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // simple placeholder graphic — keeps UI consistent without external assets
                            Text("[Grafika statystyk]", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Removed total entries, installed dictionaries and search history per request
                
                item {
                    HourlyImmersionCard(
                        mostActiveHour = state.mostActiveHour,
                        mostActiveHourCount = state.mostActiveHourCount,
                        hourlyActivity = state.hourlyActivity
                    )
                }

                item {
                    // Keep category overview small here; the detailed list is in Categories screen
                    CategoryImmersionCard(
                        mostActiveCategoryCount = state.mostActiveCategoryCount,
                        categoryActivity = state.categoryActivity,
                        isEnglish = isEnglish
                    )
                }

                item {
                    CategoryDistributionCard(
                        categoryStats = allTimeCategoryStats,
                        isEnglish = isEnglish
                    )
                }

                // Chart section
                if (state.dailyCounts.any { it.count > 0 }) {
                    item {
                        Text(
                            "Fiszki dziennie",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    item {
                            DailyFlashcardChart(
                                dailyCounts = state.dailyCounts,
                                dailyGoal = state.dailyGoal
                            )
                    }
                }

                item {
                    Text(
                        "Słówka z ostatnich 7 dni",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                item {
                    WeeklyLearnedWordsCard(
                        words = state.weeklyLearnedWords,
                        isEnglish = isEnglish,
                        onCopyClick = {
                            if (weeklyWordsForCopy.isBlank()) {
                                Toast.makeText(context, "Brak słówek do skopiowania", Toast.LENGTH_SHORT).show()
                            } else {
                                clipboardManager.setText(AnnotatedString(weeklyWordsForCopy))
                                Toast.makeText(context, "Skopiowano listę słówek", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

private val CategoryChartColors = listOf(
    Color(0xFFEF5350),
    Color(0xFF42A5F5),
    Color(0xFF66BB6A),
    Color(0xFFFFCA28),
    Color(0xFFAB47BC),
    Color(0xFF26A69A),
    Color(0xFFFF7043),
    Color(0xFF7E57C2),
    Color(0xFF8D6E63),
    Color(0xFFEC407A),
    Color(0xFF29B6F6),
    Color(0xFFD4E157)
)

@Composable
private fun CategoryDistributionCard(
    categoryStats: List<CategoryActivity>,
    isEnglish: Boolean
) {
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl
    val colorByCode = remember(categoryStats) {
        categoryStats.mapIndexed { index, stat ->
            stat.categoryCode to CategoryChartColors[index % CategoryChartColors.size]
        }.toMap()
    }
    val nonZeroStats = categoryStats.filter { it.count > 0 }
    val totalCount = nonZeroStats.sumOf { it.count }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                tr("Kategorie kopanych slow", "Mined word categories"),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                tr(
                    "Rozklad wszystkich skopanych slow wedlug kategorii.",
                    "Distribution of all mined words by category."
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )

            if (totalCount <= 0) {
                Text(
                    tr(
                        "Brak danych kategorii. Skop pierwsze slowa, aby zobaczyc wykres.",
                        "No category data yet. Mine your first words to see the chart."
                    ),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            } else {
                val donutCenterColor = MaterialTheme.colorScheme.surface
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(220.dp)) {
                        var startAngle = -90f
                        nonZeroStats.forEach { stat ->
                            val sweep = 360f * stat.count.toFloat() / totalCount.toFloat()
                            drawArc(
                                color = colorByCode[stat.categoryCode] ?: Color.Gray,
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = true
                            )
                            startAngle += sweep
                        }

                        drawCircle(
                            color = donutCenterColor,
                            radius = size.minDimension * 0.28f
                        )
                    }

                    Text(
                        text = totalCount.toString(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    tr("Top 3 kategorie", "Top 3 categories"),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                nonZeroStats
                    .sortedByDescending { it.count }
                    .take(3)
                    .forEachIndexed { index, stat ->
                        val percent = if (totalCount == 0) 0f else (stat.count.toFloat() * 100f / totalCount.toFloat())
                        val color = colorByCode[stat.categoryCode] ?: Color.Gray
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(color = color, shape = CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${index + 1}. ${stat.categoryLabel}: ${String.format(Locale.getDefault(), "%.1f", percent)}% (${stat.count})",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                Text(
                    tr("Wszystkie kategorie", "All categories"),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )

                categoryStats.forEach { stat ->
                    val color = colorByCode[stat.categoryCode] ?: Color.Gray
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(color = color, shape = CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${stat.categoryLabel}: ${stat.count}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryImmersionCard(
    mostActiveCategoryCount: Int,
    categoryActivity: List<CategoryActivity>,
    isEnglish: Boolean
) {
    val topCategories = categoryActivity
        .filter { it.count > 0 }
        .sortedByDescending { it.count }
        .take(6)
    val mostActiveCategory = topCategories.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Kategorie kopanych słów (7 dni)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (mostActiveCategory != null && mostActiveCategoryCount > 0) {
                val localizedMostActive = WordCategoryClassifier.displayName(
                    mostActiveCategory.categoryCode,
                    isEnglish
                )
                Text(
                    "Najczęstsza kategoria: $localizedMostActive",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Liczba słów: $mostActiveCategoryCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Brak danych kategorii z ostatniego tygodnia.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (topCategories.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                topCategories.forEachIndexed { index, item ->
                    val localizedLabel = WordCategoryClassifier.displayName(item.categoryCode, isEnglish)
                    Text(
                        text = "${index + 1}. $localizedLabel -> ${item.count}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HourlyImmersionCard(
    mostActiveHour: Int?,
    mostActiveHourCount: Int,
    hourlyActivity: List<HourlyActivity>
) {
    val topHours = hourlyActivity
        .filter { it.count > 0 }
        .sortedByDescending { it.count }
        .take(5)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Aktywność godzinowa (7 dni)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (mostActiveHour != null && mostActiveHourCount > 0) {
                Text(
                    "Najbardziej aktywna pora: ${StatisticsViewModel.hourRangeLabel(mostActiveHour)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Skopanych słów: $mostActiveHourCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Brak danych aktywności godzinowej z ostatniego tygodnia.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (topHours.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                topHours.forEachIndexed { index, item ->
                    Text(
                        text = "${index + 1}. ${StatisticsViewModel.hourRangeLabel(item.hour)} -> ${item.count}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StreakCard(
    streak: Int,
    dailyGoal: Int,
    todayCount: Int
) {
    val goalMet = todayCount >= dailyGoal
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (goalMet) Color(0xFFFF6D00).copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = if (streak > 0) Color(0xFFFF6D00) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Streak",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (streak > 0) "$streak ${if (streak == 1) "dzień" else "dni"} z rzędu!"
                    else "Zacznij streak! Cel: $dailyGoal fiszek/dzień",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Dzisiaj: $todayCount / $dailyGoal",
                    fontSize = 12.sp,
                    color = if (goalMet) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = if (goalMet) FontWeight.Bold else FontWeight.Normal
                )
            }
            Text(
                if (streak > 0) "\uD83D\uDD25 $streak" else "0",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = if (streak > 0) Color(0xFFFF6D00) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun WeeklyLearnedWordsCard(
    words: List<WeeklyLearnedWord>,
    isEnglish: Boolean,
    onCopyClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Nauczone słowa: ${words.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Lista do szybkiego wysłania np. korepetytorowi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onCopyClick, enabled = words.isNotEmpty()) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Kopiuj listę słów")
                }
            }

            if (words.isEmpty()) {
                Text(
                    "Brak wyeksportowanych słów w ostatnim tygodniu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val previewLimit = 40
                words.take(previewLimit).forEachIndexed { index, word ->
                    val readingPart = when {
                        word.reading.isBlank() -> ""
                        word.reading == word.expression -> ""
                        else -> " (${word.reading})"
                    }
                    val category = StatisticsViewModel.categoryLabel(word.exportCategory, isEnglish)
                    Text(
                        text = "${index + 1}. ${word.expression}$readingPart - $category",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (words.size > previewLimit) {
                    Text(
                        text = "...oraz ${words.size - previewLimit} kolejnych",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyFlashcardChart(
    dailyCounts: List<DailyCount>,
    dailyGoal: Int
) {
    val barColor = MaterialTheme.colorScheme.primary
    val goalColor = Color(0xFFFF6D00)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (dailyGoal > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.EmojiEvents, contentDescription = null, modifier = Modifier.size(16.dp), tint = goalColor)
                    Spacer(Modifier.width(4.dp))
                    Text("Cel: $dailyGoal fiszek/dzień", fontSize = 12.sp, color = goalColor)
                }
                Spacer(Modifier.height(8.dp))
            }

            // Denser bars for better visibility when there are few data points
            val barWidth = 12f
            val spacing = 4f
            val chartWidth = dailyCounts.size * (barWidth + spacing)
            val maxCount = (dailyCounts.maxOfOrNull { it.count } ?: 1).coerceAtLeast(
                if (dailyGoal > 0) dailyGoal else 1
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Canvas(
                    modifier = Modifier
                        .width((chartWidth + 40).dp)
                        .height(200.dp)
                ) {
                    val chartHeight = size.height - 40f
                    val startX = 10f

                    // Draw goal line
                    if (dailyGoal > 0) {
                        val goalY = chartHeight - (dailyGoal.toFloat() / maxCount * chartHeight)
                        drawLine(
                            color = goalColor.copy(alpha = 0.6f),
                            start = Offset(0f, goalY),
                            end = Offset(size.width, goalY),
                            strokeWidth = 2f
                        )
                    }

                    // Draw grid lines
                    for (i in 0..4) {
                        val y = chartHeight - (i.toFloat() / 4f * chartHeight)
                        drawLine(
                            color = gridColor.copy(alpha = 0.3f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                    }

                    // Draw bars
                    dailyCounts.forEachIndexed { index, daily ->
                        val x = startX + index * (barWidth + spacing)
                        val barHeight = if (maxCount > 0) daily.count.toFloat() / maxCount * chartHeight else 0f
                        val y = chartHeight - barHeight

                        val color = when {
                            dailyGoal > 0 && daily.count >= dailyGoal -> Color(0xFF4CAF50)
                            daily.count > 0 -> barColor
                            else -> barColor.copy(alpha = 0.15f)
                        }

                        // Bar
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barHeight.coerceAtLeast(2f)),
                            cornerRadius = CornerRadius(4f, 4f)
                        )

                        // Day label (every 2nd day or last) to improve density
                        if (index % 2 == 0 || index == dailyCounts.size - 1) {
                            drawContext.canvas.nativeCanvas.drawText(
                                daily.dayLabel,
                                x + barWidth / 2,
                                size.height - 2f,
                                android.graphics.Paint().apply {
                                    this.color = textColor.toArgb()
                                    textSize = 22f
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    isAntiAlias = true
                                }
                            )
                        }

                        // Count on top of bar (if > 0)
                        if (daily.count > 0) {
                            drawContext.canvas.nativeCanvas.drawText(
                                daily.count.toString(),
                                x + barWidth / 2,
                                y - 6f,
                                android.graphics.Paint().apply {
                                    this.color = textColor.toArgb()
                                    textSize = 20f
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    isAntiAlias = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String,
    containerColor: Color,
    contentColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = contentColor
                )
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }
            Text(
                value,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

private fun formatNumber(n: Int): String {
    return when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
        else -> n.toString()
    }
}
