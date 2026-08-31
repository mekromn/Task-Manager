package com.mekromn.taskmanager.data

enum class AccessMode(val title: String) {
    STANDARD("Standard"),
    SHIZUKU("Shizuku"),
    SHIZUKU_ROOT("Shizuku · root"),
    ROOT("Root")
}

enum class AccessPreference(val title: String) {
    AUTO("Auto"),
    STANDARD("Standard"),
    SHIZUKU("Shizuku"),
    ROOT("Root")
}

enum class ProcessSource {
    ANDROID_API,
    PRIVILEGED_PS
}

enum class SortMode(val title: String) {
    CPU("CPU"),
    MEMORY("Memory"),
    NAME("Name"),
    PID("PID")
}

enum class ProcessFilter(val title: String) {
    APPS("Apps"),
    USER("User"),
    SYSTEM("System"),
    ALL("All")
}

enum class ThemeMode(val title: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    AMOLED("AMOLED")
}

data class ProcessEntry(
    val pid: Int,
    val ppid: Int?,
    val linuxUser: String?,
    val uid: Int?,
    val processName: String,
    val packageName: String?,
    val appLabel: String,
    val memoryBytes: Long,
    val cpuPercent: Float,
    val importance: Int?,
    val isSystemApp: Boolean,
    val hasLauncher: Boolean,
    val source: ProcessSource
) {
    val isNativeProcess: Boolean get() = packageName == null
    val isProtectedCritical: Boolean
        get() = pid <= 1 || processName.substringAfterLast('/').substringBefore(':') in PROTECTED_NAMES

    companion object {
        private val PROTECTED_NAMES = setOf(
            "init",
            "system_server",
            "zygote",
            "zygote64",
            "surfaceflinger",
            "servicemanager",
            "hwservicemanager",
            "vndservicemanager",
            "lmkd",
            "keystore2"
        )
    }
}

data class SystemMetrics(
    val cpuPercent: Float = 0f,
    val totalRamBytes: Long = 0L,
    val availableRamBytes: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val loadAverage1m: Float = 0f
) {
    val usedRamBytes: Long get() = (totalRamBytes - availableRamBytes).coerceAtLeast(0L)
    val ramPercent: Float
        get() = if (totalRamBytes > 0) usedRamBytes.toFloat() / totalRamBytes.toFloat() * 100f else 0f
}

data class ProcessSnapshot(
    val processes: List<ProcessEntry> = emptyList(),
    val metrics: SystemMetrics = SystemMetrics(),
    val accessMode: AccessMode = AccessMode.STANDARD,
    val scannedAtMillis: Long = 0L,
    val notice: String? = null
)

data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val hasLauncher: Boolean,
    val versionName: String?,
    val targetSdk: Int,
    val uid: Int,
    val lastUsedMillis: Long?
)

data class AccessStatus(
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false,
    val shizukuUid: Int? = null,
    val rootEnabled: Boolean = false,
    val rootAvailable: Boolean? = null
)

data class CommandResult(
    val output: String,
    val exitCode: Int
) {
    val success: Boolean get() = exitCode == 0
}
