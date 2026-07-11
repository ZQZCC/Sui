/*
 * This file is part of Sui.
 *
 * Sui is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Sui is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Sui. If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2021-2026 Sui Contributors
 */
package rikka.sui.management

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import rikka.sui.model.AppInfo
import rikka.sui.util.AppInfoComparator
import rikka.sui.util.AppLabelCache
import rikka.sui.util.BridgeServiceClient
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

class ManagementController(context: Context) : Closeable {

    fun interface Listener {
        fun onStateChanged(state: Resource<List<AppInfo>?>)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateThread = HandlerThread("SuiManagementState", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val stateHandler = Handler(stateThread.looper)
    private val requestExecutor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "SuiManagementRequest").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val labelExecutor: ExecutorService = Executors.newFixedThreadPool(labelThreadCount()) { runnable ->
        Thread(runnable, "SuiLabelLoader").apply { priority = Thread.MIN_PRIORITY }
    }
    private val reloadGeneration = AtomicInteger()
    private val reloadLock = Any()
    private val settingsLock = Any()

    private val fullList = ArrayList<AppInfo>()
    private var reloadFuture: Future<*>? = null

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var closed = false

    @Volatile
    var showOnlyShizukuApps = false
        private set

    @Volatile
    var isMonetEnabled = false
        private set

    @Volatile
    var query: String? = null
        private set

    @Volatile
    var state: Resource<List<AppInfo>?> = Resource.success(emptyList())
        private set

    fun attach(listener: Listener) {
        if (closed) return
        this.listener = listener
        dispatchToMain {
            if (this.listener === listener) {
                listener.onStateChanged(state)
            }
        }
    }

    fun detach(listener: Listener) {
        if (this.listener === listener) {
            this.listener = null
        }
    }

    fun filter(query: String?) {
        if (closed) return
        stateHandler.post {
            this.query = query
            publish(Resource.success(buildDisplayList()))
        }
    }

    fun toggleShizukuFilter(enable: Boolean, onResult: (Boolean) -> Unit) {
        if (closed) return
        if (showOnlyShizukuApps == enable) {
            dispatchToMain { onResult(true) }
            return
        }

        requestExecutor.execute {
            val success = try {
                synchronized(settingsLock) {
                    val currentFlags = BridgeServiceClient.getGlobalSettings()
                    val newFlags = if (enable) {
                        currentFlags or BridgeServiceClient.FLAG_SHOW_ONLY_SHIZUKU_APPS
                    } else {
                        currentFlags and BridgeServiceClient.FLAG_SHOW_ONLY_SHIZUKU_APPS.inv()
                    }
                    BridgeServiceClient.setGlobalSettings(newFlags)
                }
            } catch (_: Throwable) {
                false
            }

            stateHandler.post {
                if (success) {
                    showOnlyShizukuApps = enable
                    reload()
                }
                dispatchToMain { onResult(success) }
            }
        }
    }

    fun toggleMonetSetting(onResult: (Boolean) -> Unit) {
        if (closed) return
        val newState = !isMonetEnabled

        requestExecutor.execute {
            val success = try {
                synchronized(settingsLock) {
                    val currentFlags = BridgeServiceClient.getGlobalSettings()
                    val newFlags = if (!newState) {
                        currentFlags or BridgeServiceClient.FLAG_MONET_DISABLED
                    } else {
                        currentFlags and BridgeServiceClient.FLAG_MONET_DISABLED.inv()
                    }
                    BridgeServiceClient.setGlobalSettings(newFlags)
                }
            } catch (_: Throwable) {
                false
            }

            stateHandler.post {
                if (success) {
                    isMonetEnabled = newState
                    appContext
                        .getSharedPreferences("sui_settings", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("monet_enabled", newState)
                        .apply()
                }
                dispatchToMain { onResult(success) }
            }
        }
    }

    fun batchUpdate(targetMode: Int) {
        if (closed) return
        requestExecutor.execute {
            try {
                BridgeServiceClient.batchUpdateUnconfigured(targetMode)
                reload()
            } catch (error: Throwable) {
                stateHandler.post {
                    publish(Resource.error(error, state.data))
                }
            }
        }
    }

    fun reload() {
        synchronized(reloadLock) {
            if (closed) return
            val generation = reloadGeneration.incrementAndGet()
            stateHandler.post {
                if (generation == reloadGeneration.get()) {
                    publish(Resource.loading(state.data))
                }
            }
            reloadFuture?.cancel(true)
            reloadFuture = requestExecutor.submit {
                try {
                    val flags = BridgeServiceClient.getGlobalSettings()
                    val showOnly = (flags and BridgeServiceClient.FLAG_SHOW_ONLY_SHIZUKU_APPS) != 0
                    val monetEnabled = (flags and BridgeServiceClient.FLAG_MONET_DISABLED) == 0
                    val result = BridgeServiceClient.getApplications(-1, showOnly)

                    loadLabels(result)
                    if (Thread.currentThread().isInterrupted || generation != reloadGeneration.get()) {
                        return@submit
                    }

                    stateHandler.post {
                        if (generation != reloadGeneration.get()) return@post
                        showOnlyShizukuApps = showOnly
                        isMonetEnabled = monetEnabled
                        fullList.clear()
                        fullList.addAll(result)
                        publish(Resource.success(buildDisplayList()))
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: Throwable) {
                    stateHandler.post {
                        if (generation == reloadGeneration.get()) {
                            publish(Resource.error(error, state.data))
                        }
                    }
                }
            }
        }
    }

    fun cancelReload() {
        synchronized(reloadLock) {
            if (closed) return
            reloadGeneration.incrementAndGet()
            reloadFuture?.cancel(true)
            reloadFuture = null
        }
        stateHandler.post {
            publish(Resource.success(buildDisplayList()))
        }
    }

    override fun close() {
        synchronized(reloadLock) {
            if (closed) return
            closed = true
            listener = null
            reloadGeneration.incrementAndGet()
            reloadFuture?.cancel(true)
            reloadFuture = null
        }
        requestExecutor.shutdownNow()
        labelExecutor.shutdownNow()
        stateThread.quitSafely()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun buildDisplayList(): List<AppInfo> {
        val activeQuery = query
        return fullList
            .asSequence()
            .filter { appInfo ->
                activeQuery.isNullOrBlank() ||
                    (appInfo.label ?: "").contains(activeQuery, ignoreCase = true) ||
                    appInfo.packageInfo.packageName.contains(activeQuery, ignoreCase = true)
            }
            .sortedWith(AppInfoComparator())
            .toList()
    }

    @Throws(InterruptedException::class)
    private fun loadLabels(apps: List<AppInfo>) {
        val packageManager = appContext.packageManager
        val tasks = apps.mapNotNull { app ->
            val applicationInfo = app.packageInfo.applicationInfo ?: return@mapNotNull null
            Callable {
                app.label = AppLabelCache.loadLabel(packageManager, applicationInfo)
            }
        }
        if (tasks.isNotEmpty()) {
            labelExecutor.invokeAll(tasks)
        }
    }

    private fun publish(newState: Resource<List<AppInfo>?>) {
        state = newState
        dispatchToMain {
            listener?.onStateChanged(newState)
        }
    }

    private fun dispatchToMain(action: () -> Unit) {
        val guardedAction = {
            if (!closed) action()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            guardedAction()
        } else {
            mainHandler.post(guardedAction)
        }
    }

    private companion object {
        fun labelThreadCount(): Int {
            val processors = try {
                Runtime.getRuntime().availableProcessors()
            } catch (_: Throwable) {
                2
            }
            return processors.coerceIn(2, 4)
        }
    }
}
