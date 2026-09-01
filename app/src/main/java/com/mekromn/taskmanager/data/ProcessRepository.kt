package com.mekromn.taskmanager.data

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import com.mekromn.taskmanager.privileged.PrivilegedProcessService
import com.mekromn.taskmanager.privileged.ShizukuClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

class ProcessRepository(
    context: Context,
    private val shizuku: ShizukuClient
) {
    private val appContext = context.applicationContext
    private val pm = appContext.packageManager
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)

    @Volatile
    private var rootAvailableCache: Boolean? = null

    private var previousCpuTotal: Long? = null
    private var previousCpuIdle: Long? = null

    suspend fun snapshot(
        preference: AccessPreference,
        rootEnabled: Boolean
    ): ProcessSnapshot = withContext(Dispatchers.IO) {
        val resolution = resolveAccess(preference, rootEnabled)
        val processes = when (resolution.mode) {
            AccessMode.STANDARD -> scanStandard()
            AccessMode.SHIZUKU, AccessMode.SHIZUKU_ROOT -> scanPrivileged(
                sourceMode = resolution.mode,
                executor = { command -> parseMarkedResult(shizuku.exec(command)) }
            )
            AccessMode.ROOT -> scanPrivileged(
                sourceMode = AccessMode.ROOT,
                executor = { command -> execRoot(command) }
            )
        }

        ProcessSnapshot(
            processes = processes,
            metrics = readSystemMetrics(),
            accessMode = resolution.mode,
            scannedAtMillis = System.currentTimeMillis(),
            notice = resolution.notice ?: if (resolution.mode == AccessMode.STANDARD) {
                "Standard Android mode shows only processes the OS exposes to this app. Enable Shizuku or root for a true system-wide process list and force-stop controls."
            } else null
        )
    }

    fun accessStatus(rootEnabled: Boolean): AccessStatus {
        val running = shizuku.isRunning()
        val granted = running && shizuku.hasPermission()
        return AccessStatus(
            shizukuRunning = running,
            shizukuGranted = granted,
            shizukuUid = if (granted) shizuku.serverUid() else null,
            rootEnabled = rootEnabled,
            rootAvailable = rootAvailableCache
        )
    }

    suspend fun testRoot(): Boolean = withContext(Dispatchers.IO) {
        val result = execRoot("id -u")
        val ok = result.success && result.output.lineSequence().any { it.trim() == "0" }
        rootAvailableCache = ok
        ok
    }

    fun requestShizukuPermission(): Boolean = shizuku.requestPermission()

    suspend fun endProcess(entry: ProcessEntry, force: Boolean, mode: AccessMode): CommandResult {
        if (entry.isProtectedCritical) {
            return CommandResult("Critical Android process is protected by Task Manager.", 126)
        }
        if (entry.pid == Process.myPid() || entry.packageName == appContext.packageName) {
            return CommandResult("Task Manager protects its own process from this control.", 126)
        }
        if (mode == AccessMode.STANDARD) {
            return CommandResult("Ending arbitrary PIDs requires Shizuku/root enhanced access.", 126)
        }
        val signal = if (force) "-9" else "-15"
        return executeForMode(mode, "kill $signal ${entry.pid}")
    }

    suspend fun forceStopPackage(packageName: String, mode: AccessMode): CommandResult {
        if (packageName == appContext.packageName) {
            return CommandResult("Task Manager protects itself from force-stop.", 126)
        }
        if (mode == AccessMode.STANDARD) {
            return runCatching {
                activityManager.killBackgroundProcesses(packageName)
                CommandResult(
                    "Android accepted a background-stop request. Modern Android may ignore it for another app.",
                    0
                )
            }.getOrElse { CommandResult(it.message.orEmpty(), 1) }
        }
        return executeForMode(
            mode,
            "am force-stop --user current ${shellQuote(packageName)}"
        )
    }

    suspend fun setPackageEnabled(
        packageName: String,
        enabled: Boolean,
        mode: AccessMode
    ): CommandResult {
        if (packageName == appContext.packageName) {
            return CommandResult("Task Manager will not disable itself.", 126)
        }
        if (mode == AccessMode.STANDARD) {
            return CommandResult("Package enable/disable requires Shizuku/root.", 126)
        }
        val command = if (enabled) {
            "pm enable --user current ${shellQuote(packageName)}"
        } else {
            "pm disable-user --user current ${shellQuote(packageName)}"
        }
        return executeForMode(mode, command)
    }

    suspend fun memoryDump(packageName: String, mode: AccessMode): CommandResult {
        if (mode == AccessMode.STANDARD) {
            return CommandResult("Detailed dumpsys memory data requires Shizuku/root.", 126)
        }
        return executeForMode(mode, "dumpsys meminfo ${shellQuote(packageName)}")
    }

    suspend fun installedApps(): List<InstalledAppEntry> = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val lastUsed = queryLastUsed()
        apps.map { info ->
            val packageName = info.packageName
            @Suppress("DEPRECATION")
            val packageInfo = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
            InstalledAppEntry(
                packageName = packageName,
                label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(packageName),
                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                hasLauncher = pm.getLaunchIntentForPackage(packageName) != null,
                versionName = packageInfo?.versionName,
                targetSdk = info.targetSdkVersion,
                uid = info.uid,
                lastUsedMillis = lastUsed[packageName]
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun hasUsageAccess(): Boolean {
        val stats = appContext.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        return runCatching {
            stats.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 60 * 60 * 1000L,
                now
            ).isNotEmpty()
        }.getOrDefault(false)
    }

    private data class AccessResolution(
        val mode: AccessMode,
        val notice: String? = null
    )

    private suspend fun resolveAccess(
        preference: AccessPreference,
        rootEnabled: Boolean
    ): AccessResolution {
        val shizukuGranted = shizuku.isRunning() && shizuku.hasPermission()
        val shizukuUid = if (shizukuGranted) shizuku.serverUid() else null
        val rootAvailable = if (rootEnabled) {
            rootAvailableCache ?: testRoot()
        } else false

        val shizukuMode = if (shizukuUid == 0) AccessMode.SHIZUKU_ROOT else AccessMode.SHIZUKU

        return when (preference) {
            AccessPreference.STANDARD -> AccessResolution(AccessMode.STANDARD)
            AccessPreference.SHIZUKU -> if (shizukuGranted) {
                AccessResolution(shizukuMode)
            } else {
                AccessResolution(
                    AccessMode.STANDARD,
                    "Shizuku was selected but is not running or permission is not granted."
                )
            }
            AccessPreference.ROOT -> if (rootAvailable) {
                AccessResolution(AccessMode.ROOT)
            } else {
                AccessResolution(
                    AccessMode.STANDARD,
                    "Root was selected but root access is unavailable or not enabled."
                )
            }
            AccessPreference.AUTO -> when {
                shizukuGranted && shizukuUid == 0 -> AccessResolution(AccessMode.SHIZUKU_ROOT)
                rootAvailable -> AccessResolution(AccessMode.ROOT)
                shizukuGranted -> AccessResolution(AccessMode.SHIZUKU)
                else -> AccessResolution(AccessMode.STANDARD)
            }
        }
    }

    private fun scanStandard(): List<ProcessEntry> {
        @Suppress("DEPRECATION")
        val running = activityManager.runningAppProcesses.orEmpty()
        if (running.isEmpty()) return emptyList()

        val pids = running.map { it.pid }.toIntArray()
        @Suppress("DEPRECATION")
        val memoryInfo = runCatching { activityManager.getProcessMemoryInfo(pids) }
            .getOrElse { emptyArray() }

        return running.mapIndexed { index, proc ->
            val packageName = proc.pkgList?.firstOrNull()
                ?: resolvePackage(proc.processName, proc.uid)?.first
            val appInfo = packageName?.let { getApplicationInfo(it) }
            val memoryBytes = memoryInfo.getOrNull(index)?.totalPss?.toLong()?.times(1024L) ?: 0L
            ProcessEntry(
                pid = proc.pid,
                ppid = null,
                linuxUser = null,
                uid = proc.uid,
                processName = proc.processName,
                packageName = packageName,
                appLabel = appInfo?.let { loadLabel(it) } ?: proc.processName,
                memoryBytes = memoryBytes,
                cpuPercent = 0f,
                importance = proc.importance,
                isSystemApp = appInfo?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 } ?: true,
                hasLauncher = packageName?.let { pm.getLaunchIntentForPackage(it) != null } ?: false,
                source = ProcessSource.ANDROID_API
            )
        }.distinctBy { it.pid }
    }

    private suspend fun scanPrivileged(
        sourceMode: AccessMode,
        executor: suspend (String) -> CommandResult
    ): List<ProcessEntry> {
        val command = """
            printf '__TM_PS__\n'
            (ps -A -o PID,PPID,UID,RSS,NAME 2>/dev/null || ps -A -o PID,PPID,USER,RSS,NAME 2>/dev/null)
            printf '__TM_TOP__\n'
            (top -b -n 1 -m 512 -o PID,%CPU 2>/dev/null || top -b -n 1 -m 512 2>/dev/null)
        """.trimIndent()

        val result = executor(command)
        if (!result.success && !result.output.contains("__TM_PS__")) {
            return emptyList()
        }

        val psSection = result.output.substringAfter("__TM_PS__", "")
            .substringBefore("__TM_TOP__", "")
        val topSection = result.output.substringAfter("__TM_TOP__", "")
        val cpuByPid = parseTopCpu(topSection)

        return parsePs(psSection).map { row ->
            val resolved = resolvePackage(row.name, row.uid)
            val packageName = resolved?.first
            val appInfo = resolved?.second
            ProcessEntry(
                pid = row.pid,
                ppid = row.ppid,
                linuxUser = row.user,
                uid = row.uid ?: appInfo?.uid,
                processName = row.name,
                packageName = packageName,
                appLabel = appInfo?.let { loadLabel(it) } ?: row.name.substringAfterLast('/'),
                memoryBytes = row.rssKb * 1024L,
                cpuPercent = cpuByPid[row.pid] ?: 0f,
                importance = null,
                isSystemApp = appInfo?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
                    ?: packageName.isNullOrEmpty(),
                hasLauncher = packageName?.let { pm.getLaunchIntentForPackage(it) != null } ?: false,
                source = ProcessSource.PRIVILEGED_PS
            )
        }.distinctBy { it.pid }
    }

    private data class PsRow(
        val pid: Int,
        val ppid: Int?,
        val uid: Int?,
        val user: String?,
        val rssKb: Long,
        val name: String
    )

    private fun parsePs(text: String): List<PsRow> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()

        val header = lines.first().split(Regex("\\s+"))
        fun indexOf(vararg candidates: String): Int {
            return header.indexOfFirst { token ->
                candidates.any { candidate -> token.equals(candidate, ignoreCase = true) }
            }
        }

        val pidIndex = indexOf("PID")
        val ppidIndex = indexOf("PPID")
        val uidIndex = indexOf("UID")
        val userIndex = indexOf("USER")
        val rssIndex = indexOf("RSS", "RES")
        val nameIndex = indexOf("NAME", "ARGS", "CMD", "COMMAND")

        if (pidIndex < 0 || nameIndex < 0) return emptyList()
        val minColumns = listOf(pidIndex, ppidIndex, uidIndex, userIndex, rssIndex, nameIndex)
            .filter { it >= 0 }
            .maxOrNull()
            ?.plus(1)
            ?: 2

        return lines.drop(1).mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size < minColumns) return@mapNotNull null
            val pid = parts.getOrNull(pidIndex)?.toIntOrNull() ?: return@mapNotNull null
            val ppid = parts.getOrNull(ppidIndex)?.toIntOrNull()
            val uid = parts.getOrNull(uidIndex)?.toIntOrNull()
            val user = when {
                userIndex >= 0 -> parts.getOrNull(userIndex)
                uidIndex >= 0 -> parts.getOrNull(uidIndex)
                else -> null
            }
            val rss = parts.getOrNull(rssIndex)?.toLongOrNull() ?: 0L
            val name = if (nameIndex == parts.lastIndex) {
                parts[nameIndex]
            } else {
                parts.drop(nameIndex).joinToString(" ")
            }.ifBlank { "pid-$pid" }

            PsRow(pid, ppid, uid, user, rss, name)
        }
    }

    private fun parseTopCpu(text: String): Map<Int, Float> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val headerIndex = lines.indexOfFirst { line ->
            val upper = line.uppercase()
            upper.contains("PID") && upper.contains("CPU")
        }
        if (headerIndex < 0) return emptyMap()

        val header = lines[headerIndex]
            .replace("[", "")
            .replace("]", "")
            .split(Regex("\\s+"))
        val pidIndex = header.indexOfFirst { it.equals("PID", ignoreCase = true) }
        val cpuIndex = header.indexOfFirst { it.uppercase().contains("CPU") }
        if (pidIndex < 0 || cpuIndex < 0) return emptyMap()

        return buildMap {
            lines.drop(headerIndex + 1).forEach { line ->
                val parts = line.split(Regex("\\s+"))
                val pid = parts.getOrNull(pidIndex)?.toIntOrNull() ?: return@forEach
                val cpu = parts.getOrNull(cpuIndex)
                    ?.removeSuffix("%")
                    ?.toFloatOrNull()
                    ?: return@forEach
                put(pid, cpu.coerceAtLeast(0f))
            }
        }
    }

    private fun resolvePackage(
        processName: String,
        uid: Int?
    ): Pair<String, ApplicationInfo>? {
        val normalized = processName.substringAfterLast('/').substringBefore(':')
        getApplicationInfo(normalized)?.let { return normalized to it }

        if (uid != null) {
            // A Shizuku/root snapshot can include processes from work profiles and
            // secondary users. Calling the normal app-process PackageManager with a
            // cross-user UID throws SecurityException unless the app holds a
            // signature-only cross-user permission. Package resolution is enrichment,
            // not a reason to discard/fail an otherwise valid system process snapshot.
            val candidates = runCatching { pm.getPackagesForUid(uid) }
                .getOrNull()
                .orEmpty()
            val preferred = candidates.firstOrNull { candidate ->
                processName.startsWith(candidate)
            } ?: candidates.firstOrNull()
            if (preferred != null) {
                getApplicationInfo(preferred)?.let { return preferred to it }
            }
        }
        return null
    }

    private fun getApplicationInfo(packageName: String): ApplicationInfo? {
        @Suppress("DEPRECATION")
        return runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
    }

    private fun loadLabel(info: ApplicationInfo): String {
        return runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName)
    }

    private fun queryLastUsed(): Map<String, Long> {
        val stats = appContext.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        return runCatching {
            stats.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 7L * 24L * 60L * 60L * 1000L,
                now
            ).associate { it.packageName to it.lastTimeUsed }
        }.getOrDefault(emptyMap())
    }

    private fun readSystemMetrics(): SystemMetrics {
        val memInfo = runCatching { File("/proc/meminfo").readLines() }.getOrDefault(emptyList())
        fun memoryValue(key: String): Long {
            val kb = memInfo.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')
                ?.trim()
                ?.substringBefore(' ')
                ?.toLongOrNull()
                ?: 0L
            return kb * 1024L
        }

        var totalRam = memoryValue("MemTotal")
        var availableRam = memoryValue("MemAvailable")
        if (totalRam == 0L) {
            val fallback = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(fallback)
            totalRam = fallback.totalMem
            availableRam = fallback.availMem
        }

        val stat = runCatching { File("/proc/stat").useLines { it.firstOrNull() } }.getOrNull()
        val cpu = stat?.let { line ->
            val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size >= 4) {
                val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L }
                val total = values.sum()
                val oldTotal = previousCpuTotal
                val oldIdle = previousCpuIdle
                previousCpuTotal = total
                previousCpuIdle = idle
                if (oldTotal != null && oldIdle != null && total > oldTotal) {
                    val deltaTotal = total - oldTotal
                    val deltaIdle = idle - oldIdle
                    ((deltaTotal - deltaIdle).toFloat() / deltaTotal.toFloat() * 100f)
                        .coerceIn(0f, 100f)
                } else 0f
            } else 0f
        } ?: 0f

        val uptime = runCatching {
            File("/proc/uptime").readText().substringBefore(' ').toDouble().roundToLong()
        }.getOrDefault(0L)

        val load = runCatching {
            File("/proc/loadavg").readText().substringBefore(' ').toFloat()
        }.getOrDefault(0f)

        return SystemMetrics(
            cpuPercent = cpu,
            totalRamBytes = totalRam,
            availableRamBytes = availableRam,
            uptimeSeconds = uptime,
            loadAverage1m = load
        )
    }

    private suspend fun executeForMode(mode: AccessMode, command: String): CommandResult {
        return when (mode) {
            AccessMode.SHIZUKU, AccessMode.SHIZUKU_ROOT -> runCatching {
                parseMarkedResult(shizuku.exec(command))
            }.getOrElse { CommandResult(it.message.orEmpty(), 1) }
            AccessMode.ROOT -> withContext(Dispatchers.IO) { execRoot(command) }
            AccessMode.STANDARD -> CommandResult("Enhanced access required.", 126)
        }
    }

    private fun execRoot(command: String): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            CommandResult(output.trim(), code)
        } catch (t: Throwable) {
            CommandResult("${t::class.java.simpleName}: ${t.message.orEmpty()}", 127)
        }
    }

    private fun parseMarkedResult(raw: String): CommandResult {
        val markerIndex = raw.lastIndexOf(PrivilegedProcessService.EXIT_MARKER)
        if (markerIndex < 0) return CommandResult(raw.trim(), 0)
        val output = raw.substring(0, markerIndex).trim()
        val code = raw.substring(markerIndex + PrivilegedProcessService.EXIT_MARKER.length)
            .trim()
            .lineSequence()
            .firstOrNull()
            ?.toIntOrNull()
            ?: 1
        return CommandResult(output, code)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
