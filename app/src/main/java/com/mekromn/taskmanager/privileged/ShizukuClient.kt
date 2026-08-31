package com.mekromn.taskmanager.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ShizukuClient(context: Context) {
    private val appContext = context.applicationContext
    private val serviceMutex = Mutex()

    @Volatile
    private var service: IPrivilegedProcessService? = null

    @Volatile
    private var connection: ServiceConnection? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, PrivilegedProcessService::class.java)
    )
        .daemon(false)
        .tag("task-manager-privileged-v1")
        .version(1)
        .processNameSuffix("privileged")

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean {
        if (!isRunning()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun serverUid(): Int? {
        if (!isRunning()) return null
        return runCatching { Shizuku.getUid() }.getOrNull()
    }

    fun requestPermission(requestCode: Int = REQUEST_CODE): Boolean {
        if (!isRunning()) return false
        return runCatching {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            }
            true
        }.getOrDefault(false)
    }

    suspend fun exec(command: String): String {
        val remote = getService()
        return withContext(Dispatchers.IO) { remote.exec(command) }
    }

    fun reset() {
        val conn = connection
        service = null
        connection = null
        if (conn != null) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, conn, false) }
        }
    }

    private suspend fun getService(): IPrivilegedProcessService = serviceMutex.withLock {
        service?.takeIf { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) }?.let {
            return@withLock it
        }

        if (!isRunning()) error("Shizuku is not running")
        if (!hasPermission()) error("Shizuku permission has not been granted")

        return@withLock withTimeout(6_000L) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val remote = binder
                                ?.takeIf { it.pingBinder() }
                                ?.let { IPrivilegedProcessService.Stub.asInterface(it) }
                            if (remote == null) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        IllegalStateException("Invalid Shizuku UserService binder")
                                    )
                                }
                                return
                            }
                            service = remote
                            connection = this
                            if (continuation.isActive) continuation.resume(remote)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            service = null
                            connection = null
                        }
                    }

                    try {
                        Shizuku.bindUserService(userServiceArgs, conn)
                    } catch (t: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(t)
                    }

                    continuation.invokeOnCancellation {
                        if (service == null) {
                            runCatching { Shizuku.unbindUserService(userServiceArgs, conn, false) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val REQUEST_CODE = 40117
    }
}
