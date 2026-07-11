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
package rikka.sui;

import android.app.ActivityManager;
import android.app.Application;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import androidx.annotation.Nullable;
import java.util.Objects;
import rikka.sui.app.AppActivity;
import rikka.sui.management.ManagementController;
import rikka.sui.management.ManagementScreen;

public class SuiActivity extends AppActivity {

    private ManagementController managementController;
    private ManagementScreen managementScreen;

    public SuiActivity(Application application, Resources resources) {
        super(application, resources);
    }

    @Override
    public android.content.ComponentName getComponentName() {
        return new android.content.ComponentName(
                getPackageName(), "com.android.settings.Settings$WifiSettingsActivity");
    }

    private int resolveThemeColor(@androidx.annotation.AttrRes int attrRes) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(attrRes, typedValue, true);
        return typedValue.data;
    }

    @Override
    @Deprecated
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            savedInstanceState.remove("android:support:fragments");
            savedInstanceState.remove("android:fragments");
        }
        super.onCreate(savedInstanceState);

        Object retained = getLastNonConfigurationInstance();
        boolean loadInitially = !(retained instanceof ManagementController);
        managementController =
                loadInitially ? new ManagementController(getApplicationContext()) : (ManagementController) retained;
        managementScreen = new ManagementScreen(this, managementController);
        managementScreen.create(loadInitially);

        setTitle("Sui");
        Objects.requireNonNull(getActionBar()).setSubtitle(BuildConfig.VERSION_NAME);
        invalidateOptionsMenu();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU /*API33/A13*/) {
            ActivityManager.TaskDescription taskDescription = new ActivityManager.TaskDescription.Builder()
                    .setLabel("Sui")
                    .setPrimaryColor(resolveThemeColor(R.attr.colorPrimary))
                    .build();
            setTaskDescription(taskDescription);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P /*API28/A9*/) {
            setTaskDescription(new ActivityManager.TaskDescription("Sui", 0, resolveThemeColor(R.attr.colorPrimary)));
        } else {
            setTaskDescription(new ActivityManager.TaskDescription("Sui"));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (managementScreen == null) {
            return false;
        }
        managementScreen.onCreateOptionsMenu(menu, getMenuInflater());
        return true;
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return managementController;
    }

    @Override
    protected void onDestroy() {
        boolean changingConfigurations = isChangingConfigurations();
        if (managementScreen != null) {
            managementScreen.destroy();
            managementScreen = null;
        }
        if (!changingConfigurations && managementController != null) {
            managementController.close();
            managementController = null;
        }
        super.onDestroy();
    }
}
