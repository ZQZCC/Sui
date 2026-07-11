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
package rikka.sui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toolbar
import rikka.sui.management.ManagementController
import rikka.sui.management.ManagementScreen
import rikka.sui.util.MonetSettings

class DebugActivity : Activity() {

    private var managementController: ManagementController? = null
    private var managementScreen: ManagementScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Sui)
        if (MonetSettings.isMonetEnabled(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            theme.applyStyle(R.style.Theme_Sui_Monet, true)
        }
        MonetSettings.syncFromServerAsync(this)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.appbar_fragment_activity)

        val toolbarContainer: android.view.ViewGroup = findViewById(R.id.toolbar_container)
        toolbarContainer.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            val statusBarHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars()).top
            } else {
                insets.systemWindowInsetTop
            }
            v.setPadding(v.paddingLeft, statusBarHeight, v.paddingRight, v.paddingBottom)
            insets
        }
        enableEdgeToEdge()

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setActionBar(toolbar)
        try {
            val typedValue = TypedValue()
            theme.resolveAttribute(R.attr.colorAccent, typedValue, true)
            val accentColor = typedValue.data
            val accentHex = String.format("#%06X", 0xFFFFFF and accentColor)
            val subtitleHtml = "<font color='$accentHex'>$accentHex</font>"
            val coloredSubtitle =
                androidx.core.text.HtmlCompat
                    .fromHtml(subtitleHtml, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
            actionBar?.title = "Sui(Debug)"
            actionBar?.subtitle = coloredSubtitle
        } catch (e: Exception) {
            actionBar?.title = "Sui(Debug)"
        }

        val retained = lastNonConfigurationInstance
        val loadInitially = retained !is ManagementController
        managementController = if (loadInitially) ManagementController(applicationContext) else retained
        managementScreen = ManagementScreen(this, requireNotNull(managementController)).also {
            it.create(loadInitially)
        }
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val screen = managementScreen ?: return false
        screen.onCreateOptionsMenu(menu, menuInflater)
        return true
    }

    override fun onRetainNonConfigurationInstance(): Any? = managementController

    override fun onDestroy() {
        managementScreen?.destroy()
        managementScreen = null
        if (!isChangingConfigurations) {
            managementController?.close()
            managementController = null
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun enableEdgeToEdge() {
        val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (isNight) 0 else mask, mask)
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!isNight) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            window.decorView.systemUiVisibility = flags
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
