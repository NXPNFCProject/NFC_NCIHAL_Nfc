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
import android.os.SystemProperties;
import android.text.TextUtils;
import androidx.annotation.VisibleForTesting;

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

    public static final String KEY_READER_OPTION_DEFAULT = "reader_option_default";
    public static final String KEY_ENABLE_NFC_DEFAULT = "enable_nfc_default";
    public static final String KEY_ENABLE_READER_OPTION_SUPPORT = "enable_reader_option_support";
    public static final String KEY_SECURE_NFC_CAPABLE = "enable_secure_nfc_support";
    public static final String KEY_SECURE_NFC_DEFAULT = "secure_nfc_default";

    private boolean mNfcDefaultState;
    private boolean mReaderOptionSupport;
    private boolean mReaderOptionDefault;
    private boolean mSecureNfcCapable;
    private boolean mSecureNfcDefault;

    private static DeviceConfigFacade sInstance;
    public static DeviceConfigFacade getInstance(Context context, Handler handler) {
        if (sInstance == null) {
            sInstance = new DeviceConfigFacade(context, handler);
        }
        return sInstance;
    }

    @VisibleForTesting
    public DeviceConfigFacade(Context context, Handler handler) {
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

        mNfcDefaultState = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_NFC,
            KEY_ENABLE_NFC_DEFAULT,
            mContext.getResources().getBoolean(R.bool.enable_nfc_default));

        mReaderOptionSupport = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_NFC,
            KEY_ENABLE_READER_OPTION_SUPPORT,
            mContext.getResources().getBoolean(R.bool.enable_reader_option_support));

        mReaderOptionDefault = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_NFC,
            KEY_READER_OPTION_DEFAULT,
            mContext.getResources().getBoolean(R.bool.reader_option_default));

        mSecureNfcCapable = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_NFC,
            KEY_SECURE_NFC_CAPABLE, isSecureNfcCapableDefault());

        mSecureNfcDefault = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_NFC,
            KEY_SECURE_NFC_DEFAULT,
            mContext.getResources().getBoolean(R.bool.secure_nfc_default));
    }

    private boolean isSecureNfcCapableDefault() {
        if (mContext.getResources().getBoolean(R.bool.enable_secure_nfc_support)) {
            return true;
        }
        String[] skuList = mContext.getResources().getStringArray(
                R.array.config_skuSupportsSecureNfc);
        String sku = SystemProperties.get("ro.boot.hardware.sku");
        if (TextUtils.isEmpty(sku) || !Utils.arrayContains(skuList, sku)) {
            return false;
        }
        return true;
    }

    /**
     * Get whether antenna blocked alert is enabled or not.
     */
    public boolean isAntennaBlockedAlertEnabled() {
        return mAntennaBlockedAlertEnabled;
    }

    public boolean getNfcDefaultState(){ return mNfcDefaultState; }
    public boolean isReaderOptionCapable() { return mReaderOptionSupport; }
    public boolean getDefaultReaderOption() { return mReaderOptionDefault; }
    public boolean isSecureNfcCapable() {return mSecureNfcCapable; }
    public boolean getDefaultSecureNfcState() { return mSecureNfcDefault; }
}