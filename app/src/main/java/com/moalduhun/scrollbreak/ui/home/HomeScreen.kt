package com.moalduhun.scrollbreak.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moalduhun.scrollbreak.data.DailyBlocks
import com.moalduhun.scrollbreak.ui.theme.ScrollBreakTheme
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private const val CHART_TRACK_HEIGHT_DP = 108

private data class HowItWorksItem(val text: String)

private val HOW_IT_WORKS = listOf(
    HowItWorksItem("Watches for Instagram Reels and YouTube Shorts"),
    HowItWorksItem("Blocks them the moment they appear, before they hook you"),
    HowItWorksItem("DMs, posts, stories, search and normal videos keep working")
)

@Composable
fun HomeRoute(viewModel: HomeViewModel = viewModel()) {
    val isEnabled by viewModel.isBlockingEnabled.collectAsState()
    val coverInstagram by viewModel.coverInstagram.collectAsState()
    val coverYouTube by viewModel.coverYouTube.collectAsState()
    val todayCount by viewModel.todayBlockedCount.collectAsState()
    val totalCount by viewModel.totalBlockedCount.collectAsState()
    val weekly by viewModel.weeklyBlocks.collectAsState()

    HomeScreen(
        isEnabled = isEnabled,
        coverInstagram = coverInstagram,
        coverYouTube = coverYouTube,
        todayCount = todayCount,
        totalCount = totalCount,
        weekly = weekly,
        onToggle = viewModel::setBlockingEnabled,
        onToggleInstagram = viewModel::setCoverInstagram,
        onToggleYouTube = viewModel::setCoverYouTube
    )
}

@Composable
fun HomeScreen(
    isEnabled: Boolean,
    coverInstagram: Boolean,
    coverYouTube: Boolean,
    todayCount: Int,
    totalCount: Int,
    weekly: List<DailyBlocks>,
    onToggle: (Boolean) -> Unit,
    onToggleInstagram: (Boolean) -> Unit,
    onToggleYouTube: (Boolean) -> Unit
) {
    var showAppsDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "ScrollBreak",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Reels & Shorts blocker",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { StatusCard(isEnabled = isEnabled, onToggle = onToggle) }

            item {
                CoveredAppsCard(
                    coverInstagram = coverInstagram,
                    coverYouTube = coverYouTube,
                    onEdit = { showAppsDialog = true }
                )
            }

            item { WeeklyChartCard(weekly = weekly) }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StatCard(label = "Blocked today", value = todayCount, modifier = Modifier.weight(1f))
                    StatCard(label = "Blocked in total", value = totalCount, modifier = Modifier.weight(1f))
                }
            }

            item { HowItWorksCard() }
        }
    }

    if (showAppsDialog) {
        AppsCoverageDialog(
            coverInstagram = coverInstagram,
            coverYouTube = coverYouTube,
            onToggleInstagram = onToggleInstagram,
            onToggleYouTube = onToggleYouTube,
            onDismiss = { showAppsDialog = false }
        )
    }
}

@Composable
private fun StatusCard(isEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    val containerColor by animateColorAsState(
        targetValue = if (isEnabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(300),
        label = "statusCardColor"
    )
    val iconColor by animateColorAsState(
        targetValue = if (isEnabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(300),
        label = "statusIconColor"
    )

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isEnabled) Icons.Filled.Security else Icons.Filled.PauseCircle,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isEnabled) "Blocking is on" else "Blocking is paused",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isEnabled) {
                        "Reels and Shorts will be blocked as soon as they open"
                    } else {
                        "Reels and Shorts will not be blocked right now"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun CoveredAppsCard(
    coverInstagram: Boolean,
    coverYouTube: Boolean,
    onEdit: () -> Unit
) {
    val covered = buildList {
        if (coverInstagram) add("Instagram Reels")
        if (coverYouTube) add("YouTube Shorts")
    }
    val summary = if (covered.isEmpty()) "No apps selected" else covered.joinToString(" · ")

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Blocked apps",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Edit")
            }
        }
    }
}

@Composable
private fun WeeklyChartCard(weekly: List<DailyBlocks>) {
    val weekTotal = weekly.sumOf { it.count }
    val maxCount = (weekly.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    val today = LocalDate.now()

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "This week",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (weekTotal == 1) "1 block" else "$weekTotal blocks",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                weekly.forEach { day ->
                    ChartBar(
                        day = day,
                        maxCount = maxCount,
                        isToday = day.date == today,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartBar(
    day: DailyBlocks,
    maxCount: Int,
    isToday: Boolean,
    modifier: Modifier = Modifier
) {
    val fraction = day.count.toFloat() / maxCount
    val targetHeight by animateDpAsState(
        targetValue = (fraction * CHART_TRACK_HEIGHT_DP).dp,
        animationSpec = tween(500),
        label = "barHeight"
    )
    val barColor = if (isToday) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (day.count > 0) day.count.toString() else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_TRACK_HEIGHT_DP.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(if (day.count > 0) targetHeight.coerceAtLeast(6.dp) else 0.dp)
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .background(barColor)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HowItWorksCard() {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "How it works",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(14.dp))
            HOW_IT_WORKS.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (index != HOW_IT_WORKS.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun AppsCoverageDialog(
    coverInstagram: Boolean,
    coverYouTube: Boolean,
    onToggleInstagram: (Boolean) -> Unit,
    onToggleYouTube: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Blocked apps",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Choose what ScrollBreak watches for.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(20.dp))

                AppToggleRow(
                    icon = Icons.Filled.Movie,
                    name = "Instagram Reels",
                    subtitle = "Reels tab, Explore, DMs and stories",
                    checked = coverInstagram,
                    onCheckedChange = onToggleInstagram
                )
                Spacer(Modifier.height(12.dp))
                AppToggleRow(
                    icon = Icons.Filled.PlayCircle,
                    name = "YouTube Shorts",
                    subtitle = "Shorts feed and player",
                    checked = coverYouTube,
                    onCheckedChange = onToggleYouTube
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "Done",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun AppToggleRow(
    icon: ImageVector,
    name: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    val today = LocalDate.now()
    val sample = (6 downTo 0).map { offset ->
        DailyBlocks(today.minusDays(offset.toLong()), listOf(2, 5, 1, 8, 3, 6, 4)[6 - offset])
    }
    ScrollBreakTheme {
        HomeScreen(
            isEnabled = true,
            coverInstagram = true,
            coverYouTube = true,
            todayCount = 4,
            totalCount = 42,
            weekly = sample,
            onToggle = {},
            onToggleInstagram = {},
            onToggleYouTube = {}
        )
    }
}
