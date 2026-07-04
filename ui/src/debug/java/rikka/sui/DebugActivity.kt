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

import android.os.Bundle
import android.os.Build
import android.util.TypedValue
import android.widget.Toolbar
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import rikka.sui.management.ManagementFragment
import rikka.sui.util.MonetSettings

class DebugActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Sui)
        if (MonetSettings.isMonetEnabled(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            theme.applyStyle(R.style.Theme_Sui_Monet, true)
        }
        MonetSettings.syncFromServerAsync(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContentView(R.layout.appbar_fragment_activity)

        val toolbarContainer: android.view.ViewGroup = findViewById(R.id.toolbar_container)
        ViewCompat.setOnApplyWindowInsetsListener(toolbarContainer) { v, insets ->
            val statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, statusBarHeight, v.paddingRight, v.paddingBottom)
            insets
        }

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

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.fragment_container, ManagementFragment())
                .commit()
        }
    }
}
