package com.mekromn.taskmanager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mekromn.taskmanager.data.AccessPreference
import com.mekromn.taskmanager.data.AccessStatus
import com.mekromn.taskmanager.data.InstalledAppEntry
import com.mekromn.taskmanager.data.ProcessEntry
import com.mekromn.taskmanager.data.ProcessFilter
import com.mekromn.taskmanager.data.ProcessRepository
import com.mekromn.taskmanager.data.ProcessSnapshot
import com.mekromn.taskmanager.data.SettingsStore
import com.mekromn.taskmanager.data.SortMode
import com.mekromn.taskmanager.data.ThemeMode
import com.mekromn.taskmanager.privileged.ShizukuClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AppSection(val title: String) {
    OVERVIEW("Overview"),
    PROCESSES("Processes"),
    APPS("Apps"),
    SETTINGS("Settings")
}

data class MainUiState(
    val section: AppSection = AppSection.OVERVIEW,
    val snapshot: ProcessSnapshot = ProcessSnapshot(),
    val installedApps: List<InstalledAppEntry> = emptyList(),
    val accessStatus: AccessStatus = AccessStatus(),
    val usageAccessGranted: Boolean = false,
    val isRefreshing: Boolean = false,
    val appsLoading: Boolean = false,
    val query: String = "",
    val processFilter: ProcessFilter = ProcessFilter.APPS,
    val sortMode: SortMode = SortMode.CPU,
    val selectedPids: Set<Int> = emptySet(),
    val themeMode: ThemeMode = ThemeMode.DARK,
    val refreshIntervalMs: Long = 2_000L,
    val autoRefresh: Boolean = true,
    val accessPreference: AccessPreference = AccessPreference.AUTO,
    val cpuHistory: List<Float> = emptyList(),
    val ramHistory: List<Float> = emptyList(),
    val message: String? = null,
    val memoryDump: String? = null,
    val memoryDumpLoading: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val shizuku = ShizukuClient(application)
    private val repository = ProcessRepository(application, shizuku)
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow(
        MainUiState(
            themeMode = settings.themeMode,
            refreshIntervalMs = settings.refreshIntervalMs,
            autoRefresh = settings.autoRefresh,
            accessPreference = settings.accessPreference,
            accessStatus = repository.accessStatus(settings.rootEnabled),
            usageAccessGranted = repository.hasUsageAccess()
        )
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var autoRefreshJob: Job? = null

    init {
        refresh()
        refreshApps()
        startAutoRefreshLoop()
    }

    fun setSection(section: AppSection) {
        _state.update { it.copy(section = section) }
        if (section == AppSection.APPS && _state.value.installedApps.isEmpty()) refreshApps()
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun setProcessFilter(filter: ProcessFilter) = _state.update {
        it.copy(processFilter = filter, selectedPids = emptySet())
    }

    fun setSortMode(sortMode: SortMode) = _state.update { it.copy(sortMode = sortMode) }

    fun toggleSelection(pid: Int) {
        _state.update { state ->
            val selected = state.selectedPids.toMutableSet()
            if (!selected.add(pid)) selected.remove(pid)
            state.copy(selectedPids = selected)
        }
    }

    fun clearSelection() = _state.update { it.copy(selectedPids = emptySet()) }

    fun refresh() {
        viewModelScope.launch {
            refreshMutex.withLock {
                _state.update { it.copy(isRefreshing = true) }
                val current = _state.value
                runCatching {
                    repository.snapshot(
                        preference = current.accessPreference,
                        rootEnabled = settings.rootEnabled
                    )
                }.onSuccess { snapshot ->
                    _state.update { old ->
                        old.copy(
                            snapshot = snapshot,
                            isRefreshing = false,
                            accessStatus = repository.accessStatus(settings.rootEnabled),
                            cpuHistory = appendHistory(old.cpuHistory, snapshot.metrics.cpuPercent),
                            ramHistory = appendHistory(old.ramHistory, snapshot.metrics.ramPercent)
                        )
                    }
                }.onFailure { error ->
                    _state.update {
                        it.copy(
                            isRefreshing = false,
                            message = "Refresh failed: ${error.message ?: error::class.java.simpleName}"
                        )
                    }
                }
            }
        }
    }

    fun refreshApps() {
        viewModelScope.launch {
            _state.update { it.copy(appsLoading = true) }
            runCatching { repository.installedApps() }
                .onSuccess { apps ->
                    _state.update {
                        it.copy(
                            installedApps = apps,
                            appsLoading = false,
                            usageAccessGranted = repository.hasUsageAccess()
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            appsLoading = false,
                            message = "App scan failed: ${error.message.orEmpty()}"
                        )
                    }
                }
        }
    }

    fun onResume() {
        _state.update {
            it.copy(
                accessStatus = repository.accessStatus(settings.rootEnabled),
                usageAccessGranted = repository.hasUsageAccess()
            )
        }
        refresh()
    }

    fun onShizukuPermissionResult() {
        _state.update { it.copy(accessStatus = repository.accessStatus(settings.rootEnabled)) }
        refresh()
    }

    fun requestShizuku() {
        val requested = repository.requestShizukuPermission()
        if (!requested) {
            postMessage("Start Shizuku first, then grant Task Manager access.")
        } else if (_state.value.accessStatus.shizukuGranted) {
            postMessage("Shizuku access is already granted.")
            refresh()
        }
    }

    fun testAndEnableRoot() {
        viewModelScope.launch {
            postMessage("Testing root access…")
            val success = repository.testRoot()
            settings.rootEnabled = success
            _state.update {
                it.copy(accessStatus = repository.accessStatus(settings.rootEnabled))
            }
            postMessage(if (success) "Root access enabled." else "Root access was not granted.")
            if (success) refresh()
        }
    }

    fun disableRoot() {
        settings.rootEnabled = false
        _state.update { it.copy(accessStatus = repository.accessStatus(false)) }
        refresh()
    }

    fun setAccessPreference(preference: AccessPreference) {
        settings.accessPreference = preference
        _state.update { it.copy(accessPreference = preference) }
        refresh()
    }

    fun setThemeMode(mode: ThemeMode) {
        settings.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun setAutoRefresh(enabled: Boolean) {
        settings.autoRefresh = enabled
        _state.update { it.copy(autoRefresh = enabled) }
    }

    fun setRefreshInterval(intervalMs: Long) {
        settings.refreshIntervalMs = intervalMs
        _state.update { it.copy(refreshIntervalMs = intervalMs) }
    }

    fun endProcess(entry: ProcessEntry, force: Boolean) {
        viewModelScope.launch {
            val result = repository.endProcess(entry, force, _state.value.snapshot.accessMode)
            postMessage(
                if (result.success) {
                    if (force) "Killed ${entry.processName}." else "Ended ${entry.processName}."
                } else {
                    result.output.ifBlank { "Process action failed (code ${result.exitCode})." }
                }
            )
            delay(250)
            refresh()
        }
    }

    fun forceStop(entry: ProcessEntry) {
        val packageName = entry.packageName ?: run {
            postMessage("This native process has no Android package to force-stop.")
            return
        }
        viewModelScope.launch {
            val result = repository.forceStopPackage(packageName, _state.value.snapshot.accessMode)
            postMessage(
                if (result.success) "Force-stop sent to ${entry.appLabel}."
                else result.output.ifBlank { "Force-stop failed (code ${result.exitCode})." }
            )
            delay(250)
            refresh()
        }
    }

    fun stopSelected() {
        val selected = _state.value.snapshot.processes.filter { it.pid in _state.value.selectedPids }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            var succeeded = 0
            var skipped = 0
            val mode = _state.value.snapshot.accessMode
            selected.forEach { entry ->
                val result = when {
                    entry.isProtectedCritical -> {
                        skipped++
                        return@forEach
                    }
                    entry.packageName != null -> repository.forceStopPackage(entry.packageName, mode)
                    else -> repository.endProcess(entry, force = false, mode = mode)
                }
                if (result.success) succeeded++ else skipped++
            }
            _state.update { it.copy(selectedPids = emptySet()) }
            postMessage("Stopped $succeeded selected item${if (succeeded == 1) "" else "s"}${if (skipped > 0) "; $skipped skipped/failed" else ""}.")
            delay(300)
            refresh()
        }
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            val result = repository.setPackageEnabled(
                packageName,
                enabled,
                _state.value.snapshot.accessMode
            )
            postMessage(
                if (result.success) {
                    if (enabled) "Package enabled." else "Package disabled for the current user."
                } else result.output.ifBlank { "Package action failed." }
            )
            refreshApps()
            refresh()
        }
    }

    fun loadMemoryDump(entry: ProcessEntry) {
        val packageName = entry.packageName ?: run {
            postMessage("Detailed meminfo is only available for Android packages.")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(memoryDumpLoading = true, memoryDump = null) }
            val result = repository.memoryDump(packageName, _state.value.snapshot.accessMode)
            _state.update {
                it.copy(
                    memoryDumpLoading = false,
                    memoryDump = if (result.success) result.output else result.output.ifBlank {
                        "Unable to read dumpsys meminfo."
                    }
                )
            }
        }
    }

    fun clearMemoryDump() = _state.update { it.copy(memoryDump = null, memoryDumpLoading = false) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun postMessage(message: String) = _state.update { it.copy(message = message) }

    private fun startAutoRefreshLoop() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                val delayMs = _state.value.refreshIntervalMs.coerceIn(1_000L, 30_000L)
                delay(delayMs)
                if (_state.value.autoRefresh) refresh()
            }
        }
    }

    private fun appendHistory(history: List<Float>, value: Float): List<Float> {
        return (history + value).takeLast(60)
    }

    override fun onCleared() {
        shizuku.reset()
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
