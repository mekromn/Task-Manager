package com.mekromn.taskmanager.privileged

import android.content.Context
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku UserService.
 *
 * This is intentionally an IBinder implementation rather than an Android Service.
 * Shizuku launches it under shell (UID 2000) or root (UID 0), which lets the
 * normal app process ask for process snapshots and privileged app-management
 * operations without bundling hidden API reflection into the UI process.
 */
class PrivilegedProcessService : IPrivilegedProcessService.Stub {
    constructor() : super()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val code = process.waitFor()
            buildString {
                append(output)
                if (output.isNotEmpty() && !output.endsWith('\n')) append('\n')
                append(EXIT_MARKER)
                append(code)
            }
        } catch (t: Throwable) {
            "${t::class.java.simpleName}: ${t.message.orEmpty()}\n${EXIT_MARKER}127"
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    companion object {
        const val EXIT_MARKER = "__TM_EXIT__="
    }
}
