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

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.PopupMenu
import android.widget.SearchView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DefaultItemAnimator
import rikka.sui.BuildConfig
import rikka.sui.R
import rikka.sui.databinding.ManagementBinding
import rikka.sui.ktx.resolveColor
import rikka.sui.model.AppInfo
import rikka.sui.server.SuiConfig
import rikka.sui.util.AppIconCache
import rikka.sui.util.BridgeServiceClient
import rikka.sui.util.EdgeDragFastScroller
import rikka.sui.util.MiuixBounceEdgeEffectFactory
import rikka.sui.util.MiuixPopupDimOverlay
import rikka.sui.util.MiuixPressHelper
import rikka.sui.util.MiuixPullToRefreshView
import rikka.sui.util.MiuixSmoothCardDrawable
import rikka.sui.util.MiuixSquircleUtils
import rikka.sui.util.applyMiuixPopupStyle
import rikka.sui.widget.MiuixBottomSheetDialog

class ManagementScreen(
    private val activity: Activity,
    private val controller: ManagementController,
) {

    private lateinit var binding: ManagementBinding
    private val adapter by lazy { ManagementAdapter(activity) }
    private var destroyed = false

    private val stateListener = ManagementController.Listener {
        when (it.status) {
            Status.LOADING -> onLoading()
            Status.SUCCESS -> onSuccess(it)
            Status.ERROR -> onError(it.error)
        }
    }

    private val bounceEdgeEffectFactory by lazy {
        MiuixBounceEdgeEffectFactory {
            controller.reload()
        }
    }

    private var lastMenuClickTime = 0L
    private var lastPopupDismissTime = 0L
    private var overflowPopupMenu: PopupMenu? = null

    private fun cancelRefreshForFastScroll() {
        if (!bounceEdgeEffectFactory.isRefreshActive() && controller.state.status != Status.LOADING) {
            return
        }

        bounceEdgeEffectFactory.cancelRefresh()
        binding.pullToRefreshIndicator.state = MiuixPullToRefreshView.RefreshState.IDLE
        binding.pullToRefreshIndicator.dragOffset = 0f
        binding.pullToRefreshIndicator.pullProgress = 0f
        controller.cancelReload()
    }

    fun create(loadInitially: Boolean) {
        val container = activity.findViewById<ViewGroup>(R.id.fragment_container)
        binding = ManagementBinding.inflate(activity.layoutInflater, container, false)
        container.removeAllViews()
        container.addView(binding.root)
        val view = binding.root

        view.post {
            val parentView = view.parent as? ViewGroup
            if (parentView != null) {
                val hostPaddingLeft = parentView.paddingLeft
                val hostPaddingRight = parentView.paddingRight
                if (hostPaddingLeft > 0 || hostPaddingRight > 0) {
                    val layoutParams = view.layoutParams as? ViewGroup.MarginLayoutParams
                    if (layoutParams != null) {
                        layoutParams.leftMargin = -hostPaddingLeft
                        layoutParams.rightMargin = -hostPaddingRight
                        view.layoutParams = layoutParams
                    }
                }
            }
        }
        val density = activity.resources.displayMetrics.density

        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val navBarBottom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                insets.systemWindowInsetBottom
            }

            val basePaddingBottom = if (navBarBottom > 0) (32f * density).toInt() else (16f * density).toInt()
            val extraPadding = Integer.max(0, navBarBottom - basePaddingBottom)

            binding.list.setPadding(
                binding.list.paddingLeft,
                0,
                binding.list.paddingRight,
                basePaddingBottom + extraPadding,
            )

            insets
        }

        bounceEdgeEffectFactory.stateListener = object : MiuixBounceEdgeEffectFactory.PullStateChangeListener {
            override fun onPullStateChanged(dragOffset: Float, state: MiuixPullToRefreshView.RefreshState, thresholdOffset: Float, maxDragDistancePx: Float) {
                if (!destroyed) {
                    binding.pullToRefreshIndicator.apply {
                        this.state = state
                        this.dragOffset = dragOffset
                        this.thresholdOffset = thresholdOffset
                        this.maxDragDistancePx = maxDragDistancePx
                        val progress = dragOffset / thresholdOffset
                        this.pullProgress = progress
                    }
                }
            }
        }

        binding.list.apply {
            setHasFixedSize(true)
            adapter = this@ManagementScreen.adapter
            (itemAnimator as DefaultItemAnimator).supportsChangeAnimations = false
            this.edgeEffectFactory = bounceEdgeEffectFactory
            this.setItemViewCacheSize(20)
            this.recycledViewPool.setMaxRecycledViews(0, 20)
            EdgeDragFastScroller(this) {
                cancelRefreshForFastScroll()
            }
        }

        controller.attach(stateListener)
        if (loadInitially) {
            controller.reload()
        }
    }

    fun onCreateOptionsMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.management_menu, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        var isSearchViewInitialized = false
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                if (!isSearchViewInitialized && newText.isNullOrEmpty()) {
                    isSearchViewInitialized = true
                    return true
                }
                controller.filter(newText)
                return true
            }
        })
        controller.query?.takeIf(String::isNotEmpty)?.let { query ->
            searchItem.expandActionView()
            searchView.setQuery(query, false)
        }

        val overflowItem = menu.findItem(R.id.action_overflow)
        activity.findViewById<View>(R.id.toolbar)?.post {
            val searchButtonView = activity.findViewById<View>(R.id.action_search)
            if (searchButtonView != null) {
                searchButtonView.background = ContextCompat.getDrawable(activity, R.drawable.miuix_action_icon_bg)
                searchButtonView.setOnLongClickListener { true }
                searchButtonView.setOnTouchListener(MiuixPressHelper())
            }

            val overflowButtonView = activity.findViewById<View>(R.id.action_overflow)
            if (overflowButtonView != null) {
                overflowButtonView.background = ContextCompat.getDrawable(activity, R.drawable.miuix_action_icon_bg)
                overflowButtonView.setOnLongClickListener { true }
                overflowButtonView.setOnTouchListener(MiuixPressHelper())
                overflowButtonView.setOnClickListener(::showOverflowPopupMenu)
            } else {
                overflowItem.setOnMenuItemClickListener {
                    showOverflowPopupMenu(activity.findViewById(R.id.toolbar))
                    true
                }
            }
        }
    }

    private fun showOverflowPopupMenu(anchorView: View) {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastPopupDismissTime < 200 || currentTime - lastMenuClickTime < 300) {
            return
        }
        lastMenuClickTime = currentTime

        if (overflowPopupMenu != null) {
            overflowPopupMenu?.dismiss()
            overflowPopupMenu = null
            return
        }

        val contextWrapper = ContextThemeWrapper(activity, R.style.Theme_Sui_PopupMenu_OverflowRightOffset)
        val popupMenu = PopupMenu(contextWrapper, anchorView, Gravity.END)
        overflowPopupMenu = popupMenu
        popupMenu.inflate(R.menu.overflow_popup_menu)

        anchorView.isActivated = true
        popupMenu.setOnDismissListener {
            MiuixPopupDimOverlay.hide()
            anchorView.isActivated = false
            lastPopupDismissTime = SystemClock.elapsedRealtime()
            if (overflowPopupMenu === popupMenu) {
                overflowPopupMenu = null
            }
        }
        MiuixPopupDimOverlay.show(activity)

        val highlightColor = activity.theme.resolveColor(R.attr.colorPrimary)
        val filterItem = popupMenu.menu.findItem(R.id.action_filter_shizuku)
        val isChecked = controller.showOnlyShizukuApps
        filterItem?.isChecked = isChecked

        filterItem?.title?.let { title ->
            val plainTitle = title.toString()
            filterItem.title = if (isChecked) {
                val ssb = SpannableString(plainTitle)
                ssb.setSpan(
                    ForegroundColorSpan(highlightColor),
                    0,
                    plainTitle.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                ssb
            } else {
                plainTitle
            }
        }

        val monetItem = popupMenu.menu.findItem(R.id.action_monet)
        val isMonetEnabled = controller.isMonetEnabled
        monetItem?.isChecked = isMonetEnabled

        monetItem?.title?.let { title ->
            val plainTitle = title.toString()
            monetItem.title = if (isMonetEnabled) {
                val ssb = SpannableString(plainTitle)
                ssb.setSpan(
                    ForegroundColorSpan(highlightColor),
                    0,
                    plainTitle.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                ssb
            } else {
                plainTitle
            }
        }

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_filter_shizuku -> {
                    val newState = !controller.showOnlyShizukuApps
                    controller.toggleShizukuFilter(newState) { success ->
                        if (!success) {
                            Toast.makeText(activity, "Failed to apply filter", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }

                R.id.action_batch_unconfigured -> {
                    showBatchOptionsMenu(anchorView)
                    true
                }

                R.id.action_add_shortcut -> {
                    try {
                        BridgeServiceClient.requestPinnedShortcut()
                        Toast.makeText(activity, R.string.toast_request_shortcut, Toast.LENGTH_SHORT).show()
                    } catch (e: Throwable) {
                        android.util.Log.e("SuiShortcutRPC", "Failed to request pinned shortcut via RPC", e)
                        Toast.makeText(activity, activity.getString(R.string.toast_request_shortcut_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                    true
                }

                R.id.action_about -> {
                    showAboutDialog()
                    true
                }

                R.id.action_monet -> {
                    controller.toggleMonetSetting { success ->
                        if (success) {
                            activity.recreate()
                        } else {
                            Toast.makeText(activity, "Failed to toggle Monet", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }

                else -> false
            }
        }
        popupMenu.applyMiuixPopupStyle(anchorView)
    }
    private fun showBatchOptionsMenu(anchorView: View) {
        val contextWrapper = ContextThemeWrapper(activity, R.style.Theme_Sui_PopupMenu_OverflowRightOffset)
        val popupMenu = PopupMenu(contextWrapper, anchorView, Gravity.END)
        popupMenu.inflate(R.menu.batch_options_menu)

        anchorView.isActivated = true
        popupMenu.setOnDismissListener {
            MiuixPopupDimOverlay.hide()
            anchorView.isActivated = false
        }
        MiuixPopupDimOverlay.show(activity)

        val currentDefaultMode = controller.state.data?.firstOrNull()?.defaultFlags
            ?.and(SuiConfig.MASK_PERMISSION) ?: 0

        val highlightColor = activity.theme.resolveColor(R.attr.colorPrimary)

        val menu = popupMenu.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)

            val isSelected = when (item.itemId) {
                R.id.batch_allow -> currentDefaultMode == SuiConfig.FLAG_ALLOWED
                R.id.batch_allow_shell -> currentDefaultMode == SuiConfig.FLAG_ALLOWED_SHELL
                R.id.batch_deny -> currentDefaultMode == SuiConfig.FLAG_DENIED
                R.id.batch_hidden -> currentDefaultMode == SuiConfig.FLAG_HIDDEN
                R.id.batch_ask -> currentDefaultMode == 0
                else -> false
            }
            if (isSelected) {
                item.isChecked = true
                val spannableTitle = SpannableString(item.title)
                spannableTitle.setSpan(
                    ForegroundColorSpan(highlightColor),
                    0,
                    spannableTitle.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                spannableTitle.setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    spannableTitle.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )

                item.title = spannableTitle
            }
        }
        popupMenu.setOnMenuItemClickListener { item ->
            val targetMode = when (item.itemId) {
                R.id.batch_allow -> SuiConfig.FLAG_ALLOWED
                R.id.batch_allow_shell -> SuiConfig.FLAG_ALLOWED_SHELL
                R.id.batch_deny -> SuiConfig.FLAG_DENIED
                R.id.batch_hidden -> SuiConfig.FLAG_HIDDEN
                R.id.batch_ask -> 0
                else -> -1
            }

            if (targetMode != -1) {
                performBatchUpdate(targetMode)
            }
            true
        }
        popupMenu.applyMiuixPopupStyle(anchorView)
    }
    private fun performBatchUpdate(targetMode: Int) {
        if (adapter.itemCount == 0) {
            binding.pullToRefreshIndicator.state = MiuixPullToRefreshView.RefreshState.REFRESHING
        }
        controller.batchUpdate(targetMode)
    }

    @android.annotation.SuppressLint("StringFormatInvalid")
    private fun showAboutDialog() {
        val versionName = try {
            BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            "Unknown"
        }
        val message = SpannableStringBuilder().apply {
            append(activity.getString(R.string.about_version, versionName))
            val break1 = length
            append("\n\n")
            setSpan(RelativeSizeSpan(0.5f), break1, break1 + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(activity.getString(R.string.about_license_part1))
            append(" ")
            val startGithub = length
            append(activity.getString(R.string.about_license_part2))
            setSpan(URLSpan("https://github.com/XiaoTong6666/Sui"), startGithub, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" ")
            append(activity.getString(R.string.about_license_part3))
            val break2 = length
            append("\n\n")
            setSpan(RelativeSizeSpan(0.5f), break2, break2 + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            val githubLinks = mapOf(
                "RikkaW" to "https://github.com/RikkaW",
                "XiaoTong6666" to "https://github.com/XiaoTong6666",
                "yangFenTuoZi" to "https://github.com/yangFenTuoZi",
                "yujincheng08" to "https://github.com/yujincheng08",
                "0xSoul24" to "https://github.com/0xSoul24",
                "Howard20181" to "https://github.com/Howard20181",
                "Kr328" to "https://github.com/Kr328",
                "binyaminyblatt" to "https://github.com/binyaminyblatt",
                "Re*Index.(ot_inc)" to "https://github.com/reindex-ot",
                "IshaParihariya" to "https://github.com/IshaParihariya",
            )

            val contributorsNamesString = githubLinks.keys.joinToString(", ")
            val contributorsText = activity.getString(R.string.about_contributors, contributorsNamesString)
            val spannableContributors = SpannableStringBuilder(contributorsText)

            for ((name, link) in githubLinks) {
                val index = contributorsText.indexOf(name)
                if (index != -1) {
                    spannableContributors.setSpan(
                        object : URLSpan(link) {
                            override fun updateDrawState(ds: android.text.TextPaint) {
                            }
                        },
                        index,
                        index + name.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            append(spannableContributors)
        }

        val contentView = activity.layoutInflater.inflate(R.layout.miuix_about_bottom_sheet, null)

        val root = contentView.findViewById<android.widget.LinearLayout>(R.id.miuix_bottom_sheet_root)
        val titleView = contentView.findViewById<android.widget.TextView>(R.id.text_title)
        val textView = contentView.findViewById<android.widget.TextView>(R.id.text_about)
        val buttonOk = contentView.findViewById<android.widget.TextView>(R.id.button_ok)
        val density = activity.resources.displayMetrics.density

        val sheetColor = ContextCompat.getColor(activity, R.color.miuix_bottom_sheet_bg_color)
        val baseRadiusPx = MiuixSquircleUtils.getBottomCornerRadius(activity)
        val dynamicRadiusPx = baseRadiusPx + 12f * density
        root?.background = MiuixSmoothCardDrawable(dynamicRadiusPx, sheetColor, topCornersOnly = false)

        val primaryColor = activity.theme.resolveColor(R.attr.colorPrimary)
        val isNight = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isMonetEnabled = controller.isMonetEnabled

        val btnColor = if (isMonetEnabled) {
            if (isNight) {
                ColorUtils.blendARGB(sheetColor, primaryColor, 0.20f)
            } else {
                ColorUtils.blendARGB(sheetColor, primaryColor, 0.10f)
            }
        } else {
            activity.getColor(R.color.miuix_button_bg_color)
        }
        val btnRadiusPx = 16f * density
        buttonOk?.background = MiuixSmoothCardDrawable.createSelectorWithOverlay(
            activity,
            btnColor,
            16f,
            topCornersOnly = false,
        )

        val dialogDiagonalOffset = dynamicRadiusPx * 0.2928f
        val buttonDiagonalOffset = btnRadiusPx * 0.2928f
        val bottomPaddingOffset = (dialogDiagonalOffset - buttonDiagonalOffset).coerceAtLeast(0f)
        val basePaddingBottomPx = 24f * density

        if (root != null) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                val navBarBottom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    insets.systemWindowInsetBottom
                }
                v.setPadding(
                    v.paddingLeft,
                    v.paddingTop,
                    v.paddingRight,
                    (basePaddingBottomPx + bottomPaddingOffset + navBarBottom).toInt(),
                )
                insets
            }
        }

        titleView?.text = "Sui"
        textView?.text = message
        textView?.movementMethod = LinkMovementMethod.getInstance()

        val bottomSheetDialog = MiuixBottomSheetDialog(activity, contentView)
        buttonOk?.setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    fun destroy() {
        destroyed = true
        controller.detach(stateListener)
        overflowPopupMenu?.dismiss()
        overflowPopupMenu = null
        binding.list.adapter = null
        bounceEdgeEffectFactory.stateListener = null
        AppIconCache.releaseLoaders()
        MiuixPopupDimOverlay.cleanUp()
    }

    private fun onLoading() {
        if (adapter.itemCount == 0) {
            binding.list.visibility = View.GONE
            binding.pullToRefreshIndicator.apply {
                state = MiuixPullToRefreshView.RefreshState.REFRESHING
                pullProgress = 1f

                post {
                    val targetOffset = (parent as? View)?.height?.toFloat() ?: (activity.resources.displayMetrics.heightPixels.toFloat() * 0.8f)
                    thresholdOffset = targetOffset
                    dragOffset = targetOffset
                    invalidate()
                }
            }
        }
    }

    private fun onError(e: Throwable?) {
        binding.list.visibility = View.VISIBLE
        bounceEdgeEffectFactory.finishRefresh()
        binding.pullToRefreshIndicator.state = MiuixPullToRefreshView.RefreshState.IDLE
    }

    private fun onSuccess(data: Resource<List<AppInfo>?>) {
        binding.list.visibility = View.VISIBLE
        bounceEdgeEffectFactory.finishRefresh()
        binding.pullToRefreshIndicator.state = MiuixPullToRefreshView.RefreshState.IDLE

        data.data?.let {
            adapter.updateData(it)

            if (it.isNotEmpty()) {
                binding.list.scheduleLayoutAnimation()
            }
        }
    }
}
