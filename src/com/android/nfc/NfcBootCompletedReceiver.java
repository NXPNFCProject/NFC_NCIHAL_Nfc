/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.nfc;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nfc.Constants;

/**
 * Boot completed receiver. used to disable the application if the device doesn't
 * support NFC when device boots.
 */
public class NfcBootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            PackageManager pm = context.getPackageManager();
            if (!pm.hasSystemFeature(Constants.FEATURE_NFC_ANY)) {
                pm.setApplicationEnabledSetting(context.getPackageName(),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
            } else {
                // If the device does support NFC, we know that we don't need to run
                // this code again, so disabling the boot receiver. This is for saving
                // CPU cycles spent on NFC cold-start during boot for devices that
                // actually support NFC.
                pm.setComponentEnabledSetting(new ComponentName(context, this.getClass()),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            }
        }
    }
}
