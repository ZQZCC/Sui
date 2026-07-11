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
 * Copyright (c) 2021-2026 Sui Contributors
 */
package rikka.sui.management

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import rikka.sui.model.AppInfo
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

class ManagementAdapter(
    context: Context,
) : RecyclerView.Adapter<ManagementAppItemViewHolder>() {

    private val inflater = LayoutInflater.from(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val diffExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuiListDiff").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val updateGeneration = AtomicInteger()
    private val items = ArrayList<AppInfo>()
    private var updateFuture: Future<*>? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        val item = items[position]
        val uid = item.packageInfo.applicationInfo?.uid?.toLong() ?: 0L
        return uid * 31L + item.packageInfo.packageName.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ManagementAppItemViewHolder = ManagementAppItemViewHolder.create(inflater, parent)

    override fun onBindViewHolder(holder: ManagementAppItemViewHolder, position: Int) {
        holder.bind(items[position], this)
    }

    override fun onViewRecycled(holder: ManagementAppItemViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    fun updateData(data: List<AppInfo>) {
        val generation = updateGeneration.incrementAndGet()
        updateFuture?.cancel(true)
        val newData = ArrayList(data)
        val oldData = ArrayList(items)

        updateFuture = diffExecutor.submit {
            val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldData.size

                override fun getNewListSize(): Int = newData.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldItem = oldData[oldItemPosition]
                    val newItem = newData[newItemPosition]
                    return oldItem.packageInfo.packageName == newItem.packageInfo.packageName &&
                        oldItem.packageInfo.applicationInfo?.uid == newItem.packageInfo.applicationInfo?.uid
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean = oldData[oldItemPosition] == newData[newItemPosition]
            })

            mainHandler.post {
                if (generation != updateGeneration.get()) return@post
                items.clear()
                items.addAll(newData)
                result.dispatchUpdatesTo(this@ManagementAdapter)
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        updateGeneration.incrementAndGet()
        updateFuture?.cancel(true)
        updateFuture = null
        diffExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromRecyclerView(recyclerView)
    }
}
