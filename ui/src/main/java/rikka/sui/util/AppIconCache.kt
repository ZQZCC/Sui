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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Sui.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2026 Sui Contributors
 */

package rikka.sui.util

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import me.zhanghai.android.appiconloader.AppIconLoader
import rikka.sui.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

object AppIconCache {

    private class AppIconLruCache(maxSize: Int) : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val lruCache: LruCache<String, Bitmap>
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadIconExecutor: ExecutorService

    private val appIconLoaders = ConcurrentHashMap<Int, AppIconLoader>()

    init {
        // Initialize app icon lru cache
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        val availableCacheSize = (maxMemory / 8)
            .coerceAtMost(MAX_CACHE_SIZE_KB.toLong())
            .coerceAtLeast(1024L)
            .toInt()
        lruCache = AppIconLruCache(availableCacheSize)

        // Initialize load icon scheduler
        val availableProcessorsCount = try {
            Runtime.getRuntime().availableProcessors()
        } catch (ignored: Exception) {
            1
        }
        val threadCount = (availableProcessorsCount / 2).coerceIn(1, 4)
        loadIconExecutor = Executors.newFixedThreadPool(threadCount) { r ->
            Thread(r).apply {
                name = "SuiIconLoader"
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    private fun get(packageName: String, userId: Int, size: Int): Bitmap? = lruCache["$packageName:$userId:$size"]

    private fun put(packageName: String, userId: Int, size: Int, bitmap: Bitmap) {
        lruCache.put("$packageName:$userId:$size", bitmap)
    }

    private fun loadIconBitmap(context: Context, info: ApplicationInfo, userId: Int, size: Int): Bitmap {
        val cachedBitmap = get(info.packageName, userId, size)
        if (cachedBitmap != null) {
            return cachedBitmap
        }

        val loader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appIconLoaders.computeIfAbsent(size) { _ ->
                AppIconLoader(
                    size,
                    AppIconUtil.shouldShrinkNonAdaptiveIcons(context),
                    object : ContextWrapper(context) {
                        override fun getApplicationContext(): Context = context
                    },
                )
            }
        } else {
            appIconLoaders[size] ?: AppIconLoader(
                size,
                AppIconUtil.shouldShrinkNonAdaptiveIcons(context),
                object : ContextWrapper(context) {
                    override fun getApplicationContext(): Context = context
                },
            ).also { newLoader ->
                appIconLoaders.putIfAbsent(size, newLoader)
            }
        }
        val bitmap = loader.loadIcon(info, false)

        put(info.packageName, userId, size, bitmap)
        return bitmap
    }

    @JvmStatic
    fun loadIconBitmapAsync(
        context: Context,
        info: ApplicationInfo,
        userId: Int,
        view: ImageView,
        size: Int,
    ): Future<*>? {
        val requestKey = "${info.packageName}:$userId:$size"
        view.setTag(R.id.tag_app_icon_request, requestKey)
        val cachedBitmap = get(info.packageName, userId, size)
        if (cachedBitmap != null) {
            view.setImageBitmap(cachedBitmap)
            return null
        }

        view.setImageDrawable(null)

        return loadIconExecutor.submit {
            val bitmap = try {
                loadIconBitmap(context, info, userId, size)
            } catch (e: Throwable) {
                Log.w("AppIconCache", "Load icon for $userId:${info.packageName}", e)
                null
            }

            if (Thread.currentThread().isInterrupted) return@submit
            mainHandler.post {
                if (view.getTag(R.id.tag_app_icon_request) != requestKey) return@post
                view.setImageBitmap(bitmap)
            }
        }
    }

    @JvmStatic
    fun cancel(view: ImageView) {
        view.setTag(R.id.tag_app_icon_request, null)
    }

    @JvmStatic
    fun releaseLoaders() {
        appIconLoaders.clear()
    }

    private const val MAX_CACHE_SIZE_KB = 16 * 1024
}
