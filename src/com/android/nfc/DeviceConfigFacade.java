/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.content.Context;
import android.os.Handler;
import android.provider.DeviceConfig;

/**
 * This class allows getting all configurable flags from DeviceConfig.
 */
public class DeviceConfigFacade {
    // TODO: Temporary hack to copy string from DeviceConfig.NAMESPACE_NFC. Use API constant
    // once build problems are resolved.
    private static final String DEVICE_CONFIG_NAMESPACE_NFC = "nfc";

    private final Context mContext;

    // Cached values of fields updated via updateDeviceConfigFlags()
    private boolean mAntennaBlockedAlertEnabled;

    private static DeviceConfigFacade sInstance;
    public static DeviceConfigFacade getInstance() {
        return sInstance;
    }

    public DeviceConfigFacade(Context context, Handler handler) {
        if (sInstance != null) {
            throw new IllegalStateException("DeviceConfigFacade should be a singleton");
        }
        mContext = context;
        updateDeviceConfigFlags();
        DeviceConfig.addOnPropertiesChangedListener(
                DEVICE_CONFIG_NAMESPACE_NFC,
                command -> handler.post(command),
                properties -> {
                    updateDeviceConfigFlags();
                });

        sInstance = this;
    }

    private void updateDeviceConfigFlags() {
        mAntennaBlockedAlertEnabled = DeviceConfig.getBoolean(DEVICE_CONFIG_NAMESPACE_NFC,
                "enable_antenna_blocked_alert",
                mContext.getResources().getBoolean(R.bool.enable_antenna_blocked_alert));
    }

    /**
     * Get whether antenna blocked alert is enabled or not.
     */
    public boolean isAntennaBlockedAlertEnabled() {
        return mAntennaBlockedAlertEnabled;
    }
}