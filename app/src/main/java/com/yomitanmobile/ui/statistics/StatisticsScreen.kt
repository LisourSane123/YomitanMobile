package com.yomitanmobile.ui.statistics

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

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

                item {
                    StatCard(
                        icon = Icons.Default.Book,
                        title = "Wpisy w słownikach",
                        value = formatNumber(state.totalEntries),
                        subtitle = "Łączna liczba wpisów we wszystkich słownikach",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                item {
                    StatCard(
                        icon = Icons.Default.MenuBook,
                        title = "Zainstalowane słowniki",
                        value = state.dictionaryCount.toString(),
                        subtitle = "Liczba zaimportowanych słowników",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                item {
                    StatCard(
                        icon = Icons.Default.Style,
                        title = "Wyeksportowane fiszki",
                        value = formatNumber(state.exportedCount),
                        subtitle = "Słówka wysłane do AnkiDroid",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                item {
                    StatCard(
                        icon = Icons.Default.History,
                        title = "Historia wyszukiwań",
                        value = formatNumber(state.searchHistoryCount),
                        subtitle = "Unikalne wyszukiwania",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
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

                item { Spacer(Modifier.height(32.dp)) }
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

            val barWidth = 28f
            val spacing = 6f
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

                        // Day label (every 5th day or last)
                        if (index % 5 == 0 || index == dailyCounts.size - 1) {
                            drawContext.canvas.nativeCanvas.drawText(
                                daily.dayLabel,
                                x + barWidth / 2,
                                size.height - 2f,
                                android.graphics.Paint().apply {
                                    this.color = textColor.hashCode()
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
                                    this.color = textColor.hashCode()
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
