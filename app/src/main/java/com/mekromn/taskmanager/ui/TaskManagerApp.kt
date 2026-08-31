package com.mekromn.taskmanager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dangerous
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekromn.taskmanager.BuildConfig
import com.mekromn.taskmanager.data.AccessMode
import com.mekromn.taskmanager.data.AccessPreference
import com.mekromn.taskmanager.data.InstalledAppEntry
import com.mekromn.taskmanager.data.ProcessEntry
import com.mekromn.taskmanager.data.ProcessFilter
import com.mekromn.taskmanager.data.SortMode
import com.mekromn.taskmanager.data.ThemeMode
import com.mekromn.taskmanager.ui.theme.TaskManagerTheme
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskManagerApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    TaskManagerTheme(state.themeMode) {
        val snackbarHostState = remember { SnackbarHostState() }
        var detailProcess by remember { mutableStateOf<ProcessEntry?>(null) }

        LaunchedEffect(state.message) {
            val message = state.message ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }

        val background = Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.background,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.045f),
                MaterialTheme.colorScheme.background
            )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    AppBottomBar(
                        selected = state.section,
                        onSelected = viewModel::setSection
                    )
                }
            ) { padding ->
                when (state.section) {
                    AppSection.OVERVIEW -> OverviewScreen(
                        state = state,
                        modifier = Modifier.padding(padding),
                        onRefresh = viewModel::refresh,
                        onProcessClick = { detailProcess = it },
                        onShowAll = { viewModel.setSection(AppSection.PROCESSES) },
                        onAccess = { viewModel.setSection(AppSection.SETTINGS) }
                    )

                    AppSection.PROCESSES -> ProcessesScreen(
                        state = state,
                        modifier = Modifier.padding(padding),
                        onRefresh = viewModel::refresh,
                        onQuery = viewModel::setQuery,
                        onFilter = viewModel::setProcessFilter,
                        onSort = viewModel::setSortMode,
                        onToggleSelection = viewModel::toggleSelection,
                        onClearSelection = viewModel::clearSelection,
                        onStopSelected = viewModel::stopSelected,
                        onProcessClick = { detailProcess = it }
                    )

                    AppSection.APPS -> AppsScreen(
                        state = state,
                        modifier = Modifier.padding(padding),
                        onQuery = viewModel::setQuery,
                        onRefresh = viewModel::refreshApps
                    )

                    AppSection.SETTINGS -> SettingsScreen(
                        state = state,
                        modifier = Modifier.padding(padding),
                        onRequestShizuku = viewModel::requestShizuku,
                        onEnableRoot = viewModel::testAndEnableRoot,
                        onDisableRoot = viewModel::disableRoot,
                        onAccessPreference = viewModel::setAccessPreference,
                        onTheme = viewModel::setThemeMode,
                        onAutoRefresh = viewModel::setAutoRefresh,
                        onRefreshInterval = viewModel::setRefreshInterval
                    )
                }
            }

            detailProcess?.let { entry ->
                ProcessDetailSheet(
                    entry = entry,
                    state = state,
                    onDismiss = {
                        detailProcess = null
                        viewModel.clearMemoryDump()
                    },
                    onEnd = { viewModel.endProcess(entry, force = false) },
                    onKill = { viewModel.endProcess(entry, force = true) },
                    onForceStop = { viewModel.forceStop(entry) },
                    onMemoryDump = { viewModel.loadMemoryDump(entry) }
                )
            }

            if (state.memoryDump != null || state.memoryDumpLoading) {
                MemoryDumpDialog(
                    text = state.memoryDump,
                    loading = state.memoryDumpLoading,
                    onDismiss = viewModel::clearMemoryDump
                )
            }
        }
    }
}

@Composable
private fun AppBottomBar(
    selected: AppSection,
    onSelected: (AppSection) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 0.dp
    ) {
        BottomDestination(AppSection.OVERVIEW, Icons.Rounded.DataUsage, selected, onSelected)
        BottomDestination(AppSection.PROCESSES, Icons.Rounded.Memory, selected, onSelected)
        BottomDestination(AppSection.APPS, Icons.Rounded.Apps, selected, onSelected)
        BottomDestination(AppSection.SETTINGS, Icons.Rounded.Settings, selected, onSelected)
    }
}

@Composable
private fun BottomDestination(
    section: AppSection,
    icon: ImageVector,
    selected: AppSection,
    onSelected: (AppSection) -> Unit
) {
    NavigationBarItem(
        selected = selected == section,
        onClick = { onSelected(section) },
        icon = { Icon(icon, contentDescription = section.title) },
        label = { Text(section.title) }
    )
}

@Composable
private fun OverviewScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    onProcessClick: (ProcessEntry) -> Unit,
    onShowAll: () -> Unit,
    onAccess: () -> Unit
) {
    val processes = state.snapshot.processes
    val topCpu = remember(processes) { processes.sortedByDescending { it.cpuPercent }.take(5) }
    val topRam = remember(processes) { processes.sortedByDescending { it.memoryBytes }.take(5) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeader(
                title = "Task Manager",
                subtitle = "Live Android process control",
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh
            )
        }
        item {
            SystemHeroCard(state = state, onAccess = onAccess)
        }
        state.snapshot.notice?.let { notice ->
            item { RestrictionNotice(notice, onAccess) }
        }
        item {
            HistoryCard(
                cpuHistory = state.cpuHistory,
                ramHistory = state.ramHistory
            )
        }
        item {
            SectionTitle(
                title = "Top CPU",
                action = "All processes",
                onAction = onShowAll
            )
        }
        if (topCpu.isEmpty()) {
            item { EmptyState("No processes are visible yet.", Icons.Rounded.Memory) }
        } else {
            items(topCpu, key = { "cpu-${it.pid}" }) { entry ->
                CompactProcessRow(entry, onProcessClick)
            }
        }
        item {
            SectionTitle(title = "Top memory")
        }
        items(topRam, key = { "ram-${it.pid}" }) { entry ->
            CompactProcessRow(entry, onProcessClick)
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SystemHeroCard(
    state: MainUiState,
    onAccess: () -> Unit
) {
    val metrics = state.snapshot.metrics
    val cpu by animateFloatAsState(metrics.cpuPercent, label = "cpu")
    val ram by animateFloatAsState(metrics.ramPercent, label = "ram")
    val access = state.snapshot.accessMode
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(28.dp), ambientColor = primary.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    primary.copy(alpha = 0.45f),
                    secondary.copy(alpha = 0.20f),
                    Color.Transparent
                )
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "SYSTEM NOW",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        access.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                AccessPill(access, onAccess)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricRing(
                    value = cpu / 100f,
                    valueText = "${cpu.roundToInt()}%",
                    label = "CPU",
                    color = primary
                )
                MetricRing(
                    value = ram / 100f,
                    valueText = "${ram.roundToInt()}%",
                    label = "RAM",
                    color = secondary
                )
                MetricRing(
                    value = (state.snapshot.processes.size / 250f).coerceIn(0f, 1f),
                    valueText = state.snapshot.processes.size.toString(),
                    label = "Processes",
                    color = tertiary
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MiniStat("Used", formatBytes(metrics.usedRamBytes))
                MiniStat("Available", formatBytes(metrics.availableRamBytes))
                MiniStat("Load", String.format("%.2f", metrics.loadAverage1m))
                MiniStat("Uptime", formatUptime(metrics.uptimeSeconds))
            }
        }
    }
}

@Composable
private fun AccessPill(access: AccessMode, onClick: () -> Unit) {
    val icon = when (access) {
        AccessMode.STANDARD -> Icons.Rounded.Android
        AccessMode.SHIZUKU -> Icons.Rounded.DeveloperMode
        AccessMode.SHIZUKU_ROOT, AccessMode.ROOT -> Icons.Rounded.Security
    }
    AssistChip(
        onClick = onClick,
        label = { Text(access.title) },
        leadingIcon = { Icon(icon, null, Modifier.size(17.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            labelColor = MaterialTheme.colorScheme.primary,
            leadingIconContentColor = MaterialTheme.colorScheme.primary
        ),
        border = null
    )
}

@Composable
private fun MetricRing(
    value: Float,
    valueText: String,
    label: String,
    color: Color
) {
    val animated by animateFloatAsState(value.coerceIn(0f, 1f), label = "ring")
    Box(
        modifier = Modifier.size(88.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            drawArc(
                color = color.copy(alpha = 0.14f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(valueText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RestrictionNotice(text: String, onAccess: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.06f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Rounded.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Android access boundary", fontWeight = FontWeight.SemiBold)
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onAccess) { Text("Access") }
        }
    }
}

@Composable
private fun HistoryCard(cpuHistory: List<Float>, ramHistory: List<Float>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Speed, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("60-sample history", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            Sparkline(
                values = cpuHistory,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("CPU", style = MaterialTheme.typography.labelSmall)
                Text(
                    "${cpuHistory.lastOrNull()?.roundToInt() ?: 0}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(12.dp))
            Sparkline(
                values = ramHistory,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RAM", style = MaterialTheme.typography.labelSmall)
                Text(
                    "${ramHistory.lastOrNull()?.roundToInt() ?: 0}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        if (values.size < 2) {
            drawLine(
                color.copy(alpha = 0.18f),
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 1.dp.toPx()
            )
            return@Canvas
        }
        val path = Path()
        val maxValue = 100f
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.lastIndex.toFloat())
            val y = size.height - (value.coerceIn(0f, maxValue) / maxValue) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing?.invoke()
        FilledIconButton(
            onClick = onRefresh,
            enabled = !isRefreshing
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.Refresh, "Refresh")
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactProcessRow(
    entry: ProcessEntry,
    onClick: (ProcessEntry) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onClick(entry) }),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(entry.packageName, entry.appLabel, Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.appLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    entry.processName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${formatCpu(entry.cpuPercent)} CPU",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    formatBytes(entry.memoryBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProcessesScreen(
    state: MainUiState,
    modifier: Modifier,
    onRefresh: () -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (ProcessFilter) -> Unit,
    onSort: (SortMode) -> Unit,
    onToggleSelection: (Int) -> Unit,
    onClearSelection: () -> Unit,
    onStopSelected: () -> Unit,
    onProcessClick: (ProcessEntry) -> Unit
) {
    val filtered = remember(
        state.snapshot.processes,
        state.query,
        state.processFilter,
        state.sortMode
    ) {
        filterAndSortProcesses(
            state.snapshot.processes,
            state.query,
            state.processFilter,
            state.sortMode
        )
    }

    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ScreenHeader(
                title = "Processes",
                subtitle = "${filtered.size} shown · ${state.snapshot.accessMode.title}",
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                trailing = {
                    if (state.selectedPids.isNotEmpty()) {
                        TextButton(onClick = onClearSelection) {
                            Text("${state.selectedPids.size} selected")
                        }
                    }
                }
            )

            SearchField(state.query, onQuery, "Search process, app, package or PID")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProcessFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.processFilter == filter,
                        onClick = { onFilter(filter) },
                        label = { Text(filter.title) }
                    )
                }
                Spacer(Modifier.width(2.dp))
                SortMenu(state.sortMode, onSort)
            }

            AnimatedVisibility(state.selectedPids.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${state.selectedPids.size} selected",
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold
                        )
                        FilledTonalButton(onClick = onStopSelected) {
                            Icon(Icons.Rounded.StopCircle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    if (state.query.isBlank()) "No matching processes are visible." else "No process matches “${state.query}”.",
                    Icons.Rounded.Memory
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.pid }) { entry ->
                    ProcessRow(
                        entry = entry,
                        selected = entry.pid in state.selectedPids,
                        selectionMode = state.selectedPids.isNotEmpty(),
                        onToggleSelection = { onToggleSelection(entry.pid) },
                        onClick = { onProcessClick(entry) }
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (value.isNotBlank()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Rounded.Close, "Clear")
                }
            }
        },
        placeholder = { Text(placeholder) }
    )
}

@Composable
private fun SortMenu(sortMode: SortMode, onSort: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            leadingIcon = { Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)) },
            label = { Text(sortMode.title) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.title) },
                    onClick = {
                        onSort(mode)
                        expanded = false
                    },
                    leadingIcon = {
                        if (mode == sortMode) Icon(Icons.Rounded.Check, null)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProcessRow(
    entry: ProcessEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit
) {
    val selectedColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
        label = "row color"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelection() else onClick()
                },
                onLongClick = onToggleSelection
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = selectedColor)
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(entry.packageName, entry.appLabel, Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.appLabel,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (entry.isProtectedCritical) {
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            Icons.Rounded.Shield,
                            contentDescription = "Protected critical process",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(
                    entry.processName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TinyStat("PID", entry.pid.toString())
                    TinyStat("CPU", formatCpu(entry.cpuPercent))
                    TinyStat("RAM", formatBytes(entry.memoryBytes))
                }
            }
            if (selectionMode) {
                IconButton(onClick = onToggleSelection) {
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.MoreVert,
                        contentDescription = "Select",
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.width(54.dp)
                ) {
                    Text(
                        formatCpu(entry.cpuPercent),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { (entry.cpuPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .width(44.dp)
                            .height(4.dp)
                            .clip(CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun TinyStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AppsScreen(
    state: MainUiState,
    modifier: Modifier,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val apps = remember(state.installedApps, state.query, showSystem) {
        state.installedApps.filter { app ->
            (showSystem || !app.isSystem) &&
                (state.query.isBlank() ||
                    app.label.contains(state.query, true) ||
                    app.packageName.contains(state.query, true))
        }
    }
    val runningPackages = remember(state.snapshot.processes) {
        state.snapshot.processes.mapNotNull { it.packageName }.toSet()
    }

    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ScreenHeader(
                title = "Apps",
                subtitle = "${apps.size} packages",
                isRefreshing = state.appsLoading,
                onRefresh = onRefresh
            )
            SearchField(state.query, onQuery, "Search installed apps")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showSystem,
                    onClick = { showSystem = false },
                    label = { Text("User apps") }
                )
                FilterChip(
                    selected = showSystem,
                    onClick = { showSystem = true },
                    label = { Text("All apps") }
                )
            }
            if (!state.usageAccessGranted) {
                UsageAccessCard(compact = true)
            }
        }

        if (state.appsLoading && apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        running = app.packageName in runningPackages,
                        onLaunch = { launchApp(context, app.packageName) },
                        onInfo = { openAppInfo(context, app.packageName) }
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun UsageAccessCard(compact: Boolean = false) {
    val context = LocalContext.current
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 16.dp else 20.dp)
    ) {
        Row(
            modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Timer, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Usage access", fontWeight = FontWeight.SemiBold)
                if (!compact) {
                    Text(
                        "Shows last-used times in the Apps browser.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }
            ) { Text("Grant") }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledAppEntry,
    running: Boolean,
    onLaunch: () -> Unit,
    onInfo: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                AppIcon(app.packageName, app.label, Modifier.size(46.dp))
                if (running) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    app.packageName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    buildString {
                        if (running) append("Running")
                        app.lastUsedMillis?.takeIf { it > 0 }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(relativeTime(it))
                        }
                    }.ifBlank { "Not recently used" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (app.hasLauncher) {
                IconButton(onClick = onLaunch) { Icon(Icons.Rounded.PlayArrow, "Launch") }
            }
            IconButton(onClick = onInfo) { Icon(Icons.Rounded.Info, "App info") }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: MainUiState,
    modifier: Modifier,
    onRequestShizuku: () -> Unit,
    onEnableRoot: () -> Unit,
    onDisableRoot: () -> Unit,
    onAccessPreference: (AccessPreference) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onAutoRefresh: (Boolean) -> Unit,
    onRefreshInterval: (Long) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Access, monitoring and appearance",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SettingsGroup("Enhanced access", Icons.Rounded.Security) {
            Text(
                "Choose the deepest available backend automatically or pin one mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccessPreference.entries.forEach { pref ->
                    FilterChip(
                        selected = state.accessPreference == pref,
                        onClick = { onAccessPreference(pref) },
                        label = { Text(pref.title) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            ShizukuSettingsRow(state, onRequestShizuku)
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            RootSettingsRow(state, onEnableRoot, onDisableRoot)
        }

        SettingsGroup("Monitoring", Icons.Rounded.Speed) {
            SettingSwitchRow(
                title = "Live refresh",
                subtitle = "Keep process and system metrics updating while the app is open.",
                checked = state.autoRefresh,
                onChecked = onAutoRefresh
            )
            Spacer(Modifier.height(12.dp))
            Text("Refresh interval", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(1_000L to "1 sec", 2_000L to "2 sec", 5_000L to "5 sec", 10_000L to "10 sec")
                    .forEach { (ms, label) ->
                        FilterChip(
                            selected = state.refreshIntervalMs == ms,
                            onClick = { onRefreshInterval(ms) },
                            label = { Text(label) }
                        )
                    }
            }
        }

        SettingsGroup("Appearance", Icons.Rounded.Palette) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { onTheme(mode) },
                        label = { Text(mode.title) }
                    )
                }
            }
            Text(
                "AMOLED uses a true-black base. Dark/System use Material dynamic color when available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsGroup("App activity", Icons.Rounded.Timer) {
            if (state.usageAccessGranted) {
                StatusLine(true, "Usage access granted")
            } else {
                UsageAccessCard()
            }
        }

        SettingsGroup("About", Icons.Rounded.PhoneAndroid) {
            KeyValueRow("Version", BuildConfig.VERSION_NAME)
            KeyValueRow("Package", BuildConfig.APPLICATION_ID)
            KeyValueRow("Current backend", state.snapshot.accessMode.title)
            Text(
                "Designed as a sideloaded power-user process manager. Android intentionally restricts cross-app process visibility and termination for ordinary apps, so Shizuku/root unlock the full backend.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun ShizukuSettingsRow(state: MainUiState, onRequest: () -> Unit) {
    val status = state.accessStatus
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.DeveloperMode,
            null,
            tint = if (status.shizukuGranted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Shizuku", fontWeight = FontWeight.SemiBold)
            val text = when {
                status.shizukuGranted && status.shizukuUid == 0 -> "Granted · root backend"
                status.shizukuGranted -> "Granted · ADB shell backend"
                status.shizukuRunning -> "Running · permission needed"
                else -> "Not running"
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusDot(status.shizukuGranted)
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onRequest) {
            Text(if (status.shizukuGranted) "Granted" else "Grant")
        }
    }
}

@Composable
private fun RootSettingsRow(
    state: MainUiState,
    onEnable: () -> Unit,
    onDisable: () -> Unit
) {
    val enabled = state.accessStatus.rootEnabled && state.accessStatus.rootAvailable == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.Terminal,
            null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Root", fontWeight = FontWeight.SemiBold)
            Text(
                when (state.accessStatus.rootAvailable) {
                    true -> if (enabled) "Granted and enabled" else "Available"
                    false -> "Not granted / unavailable"
                    null -> "Not tested"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (enabled) {
            OutlinedButton(onClick = onDisable) { Text("Disable") }
        } else {
            FilledTonalButton(onClick = onEnable) { Text("Test root") }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
            null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    Box(
        Modifier
            .size(9.dp)
            .background(
                if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape
            )
    )
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            key,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessDetailSheet(
    entry: ProcessEntry,
    state: MainUiState,
    onDismiss: () -> Unit,
    onEnd: () -> Unit,
    onKill: () -> Unit,
    onForceStop: () -> Unit,
    onMemoryDump: () -> Unit
) {
    val context = LocalContext.current
    val enhanced = state.snapshot.accessMode != AccessMode.STANDARD
    var confirmKill by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(entry.packageName, entry.appLabel, Modifier.size(66.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.appLabel,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        entry.processName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    entry.packageName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (entry.isProtectedCritical) {
                    Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text("Protected", Modifier.padding(4.dp))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailMetric("PID", entry.pid.toString(), Modifier.weight(1f))
                DetailMetric("CPU", formatCpu(entry.cpuPercent), Modifier.weight(1f))
                DetailMetric("RAM", formatBytes(entry.memoryBytes), Modifier.weight(1f))
            }

            if (entry.isProtectedCritical) {
                OutlinedCard(
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                    )
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Critical Android process. Direct termination is intentionally disabled.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            SectionTitle("Process controls")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onEnd,
                    enabled = enhanced && !entry.isProtectedCritical
                ) {
                    Icon(Icons.Rounded.Stop, null)
                    Spacer(Modifier.width(6.dp))
                    Text("End")
                }
                OutlinedButton(
                    onClick = { confirmKill = true },
                    enabled = enhanced && !entry.isProtectedCritical
                ) {
                    Icon(Icons.Rounded.Dangerous, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Kill -9")
                }
                Button(
                    onClick = onForceStop,
                    enabled = entry.packageName != null && !entry.isProtectedCritical
                ) {
                    Icon(Icons.Rounded.StopCircle, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Force stop")
                }
            }

            if (!enhanced) {
                Text(
                    "End/Kill PID require Shizuku or root. Force stop falls back to Android's limited background-stop request in Standard mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            entry.packageName?.let { pkg ->
                SectionTitle("App controls")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (entry.hasLauncher) {
                        OutlinedButton(onClick = { launchApp(context, pkg) }) {
                            Icon(Icons.Rounded.Launch, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Launch")
                        }
                    }
                    OutlinedButton(onClick = { openAppInfo(context, pkg) }) {
                        Icon(Icons.Rounded.Info, null)
                        Spacer(Modifier.width(6.dp))
                        Text("App info")
                    }
                    OutlinedButton(onClick = { copyText(context, "Package", pkg) }) {
                        Icon(Icons.Rounded.ContentCopy, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Copy package")
                    }
                    OutlinedButton(
                        onClick = onMemoryDump,
                        enabled = enhanced
                    ) {
                        Icon(Icons.Rounded.Memory, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Memory dump")
                    }
                    OutlinedButton(onClick = { uninstallApp(context, pkg) }) {
                        Icon(Icons.Rounded.Delete, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Uninstall")
                    }
                }
            }

            SectionTitle("Details")
            KeyValueRow("Parent PID", entry.ppid?.toString() ?: "—")
            KeyValueRow("UID", entry.uid?.toString() ?: entry.linuxUser ?: "—")
            KeyValueRow("Type", if (entry.isNativeProcess) "Native/system process" else if (entry.isSystemApp) "System app" else "User app")
            KeyValueRow("Source", if (entry.source.name == "PRIVILEGED_PS") "Privileged ps/top" else "Android API")
        }
    }

    if (confirmKill) {
        AlertDialog(
            onDismissRequest = { confirmKill = false },
            icon = { Icon(Icons.Rounded.WarningAmber, null) },
            title = { Text("Force-kill PID ${entry.pid}?") },
            text = {
                Text("SIGKILL gives the process no cleanup opportunity. Android may immediately restart managed services.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmKill = false
                        onKill()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Kill") }
            },
            dismissButton = {
                TextButton(onClick = { confirmKill = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DetailMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        )
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.Bold)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MemoryDumpDialog(
    text: String?,
    loading: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("dumpsys meminfo") },
        text = {
            if (loading) {
                Box(
                    Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun AppIcon(
    packageName: String?,
    label: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    if (packageName == null) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.fillMaxSize().padding(11.dp)
                )
            }
        }
        return
    }

    val image = remember(packageName) {
        runCatching {
            context.packageManager
                .getApplicationIcon(packageName)
                .toBitmap(112, 112)
                .asImageBitmap()
        }.getOrNull()
    }

    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(RoundedCornerShape(13.dp))
        )
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun EmptyState(text: String, icon: ImageVector) {
    Column(
        modifier = Modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            null,
            modifier = Modifier.size(42.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun filterAndSortProcesses(
    processes: List<ProcessEntry>,
    query: String,
    filter: ProcessFilter,
    sort: SortMode
): List<ProcessEntry> {
    val q = query.trim()
    val filtered = processes.filter { entry ->
        val matchesFilter = when (filter) {
            ProcessFilter.APPS -> entry.packageName != null
            ProcessFilter.USER -> entry.packageName != null && !entry.isSystemApp
            ProcessFilter.SYSTEM -> entry.isSystemApp || entry.isNativeProcess
            ProcessFilter.ALL -> true
        }
        val matchesQuery = q.isBlank() ||
            entry.appLabel.contains(q, true) ||
            entry.processName.contains(q, true) ||
            entry.packageName?.contains(q, true) == true ||
            entry.pid.toString() == q
        matchesFilter && matchesQuery
    }
    return when (sort) {
        SortMode.CPU -> filtered.sortedWith(
            compareByDescending<ProcessEntry> { it.cpuPercent }
                .thenByDescending { it.memoryBytes }
        )
        SortMode.MEMORY -> filtered.sortedByDescending { it.memoryBytes }
        SortMode.NAME -> filtered.sortedBy { it.appLabel.lowercase() }
        SortMode.PID -> filtered.sortedBy { it.pid }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kib = bytes / 1024.0
    if (kib < 1024) return "${kib.roundToInt()} KB"
    val mib = kib / 1024.0
    if (mib < 1024) return String.format("%.1f MB", mib)
    val gib = mib / 1024.0
    return String.format("%.2f GB", gib)
}

private fun formatCpu(cpu: Float): String {
    return if (cpu >= 10f) "${cpu.roundToInt()}%" else String.format("%.1f%%", cpu)
}

private fun formatUptime(seconds: Long): String {
    if (seconds <= 0) return "—"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

private fun relativeTime(time: Long): String {
    return DateUtils.getRelativeTimeSpanString(
        time,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
}

private fun launchApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openAppInfo(context: Context, packageName: String) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun uninstallApp(context: Context, packageName: String) {
    val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun copyText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
