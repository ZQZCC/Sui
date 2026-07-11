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

package rikka.sui.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toolbar;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import rikka.sui.R;
import rikka.sui.ktx.ResourcesKt;
import rikka.sui.util.MonetSettings;

public class AppActivity extends Activity {

    private final Application application;
    private final Resources resources;

    private ViewGroup rootView;
    private ViewGroup contentContainer;
    private ViewGroup toolbarContainer;

    public AppActivity(Application application, Resources resources) {
        this.application = application;
        this.resources = resources;
    }

    @Override
    public Context getApplicationContext() {
        return application;
    }

    @Override
    public ClassLoader getClassLoader() {
        return AppActivity.class.getClassLoader();
    }

    @Override
    public Resources getResources() {
        return resources;
    }

    @Override
    public android.content.ComponentName getComponentName() {
        return new android.content.ComponentName(
                getPackageName(), "com.android.settings.Settings$WifiSettingsActivity");
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(R.style.Theme_Sui);

        final boolean monetEnabled = MonetSettings.isMonetEnabled(this);
        if (monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getTheme().applyStyle(R.style.Theme_Sui_Monet, true);
        }
        MonetSettings.syncFromServerAsync(this, (changed, enabled) -> {
            if (!changed) {
                return;
            }
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    recreate();
                }
            });
        });
        super.onCreate(savedInstanceState);

        try {
            super.setContentView(R.layout.appbar_fragment_activity);

            rootView = findViewById(R.id.root);
            contentContainer = findViewById(R.id.fragment_container);
            toolbarContainer = findViewById(R.id.toolbar_container);
            Toolbar toolbar = findViewById(R.id.toolbar);

            if (toolbar != null) {
                setActionBar(toolbar);
            } else {
                android.util.Log.e("Sui", "Toolbar not found in appbar_fragment_activity layout");
            }

            toolbarContainer.setOnApplyWindowInsetsListener((v, insets) -> {
                int statusBarHeight;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    statusBarHeight = insets.getInsets(WindowInsets.Type.systemBars()).top;
                } else {
                    statusBarHeight = insets.getSystemWindowInsetTop();
                }
                v.setPadding(v.getPaddingLeft(), statusBarHeight, v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });

            enableEdgeToEdge();

            boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            if (isNight && monetEnabled) {
                int primaryColor = ResourcesKt.resolveColor(getTheme(), R.attr.colorPrimary);
                int blendedBg = ColorUtils.blendARGB(Color.BLACK, primaryColor, 0.10f);
                rootView.setBackgroundColor(blendedBg);
                if (toolbarContainer != null) {
                    toolbarContainer.setBackgroundColor(blendedBg);
                }
                getWindow().setBackgroundDrawable(new ColorDrawable(blendedBg));
            }
        } catch (Throwable t) {
            android.util.Log.e("Sui", "Fatal error in AppActivity.onCreate", t);
        }
    }

    @Override
    public void setContentView(int layoutResID) {
        getLayoutInflater().inflate(layoutResID, rootView, true);
    }

    public void setContentView(@Nullable View view) {
        setContentView(
                view,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setContentView(@Nullable View view, @Nullable ViewGroup.LayoutParams params) {
        contentContainer.removeAllViews();
        contentContainer.addView(view, params);
    }

    @SuppressWarnings("deprecation")
    private void enableEdgeToEdge() {
        Window window = getWindow();
        boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(isNight ? 0 : mask, mask);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!isNight) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
    }
}
