/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.nfc.emulator;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

import com.android.nfc.service.ScreenOffPaymentService;
import com.android.nfc.utils.HceUtils;

public class ScreenOffPaymentEmulatorActivity extends BaseEmulatorActivity {
    private static final String TAG = "ScreenOffPaymentEm";
    private static final int STATE_SCREEN_ON = 0;
    private static final int STATE_SCREEN_OFF = 1;

    private int mState = STATE_SCREEN_ON;
    private ScreenOnOffReceiver mScreenOnOffReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mState = STATE_SCREEN_ON;
        setupServices(ScreenOffPaymentService.COMPONENT);
        makeDefaultWalletRoleHolder();

        if (mAdapter.isSecureNfcSupported() && mAdapter.isSecureNfcEnabled()) {
            boolean res = HceUtils.disableSecureNfc(mAdapter);
            if (!res) {
                Log.e(TAG, "Problem while attempting to disable secure NFC");
            }
        }

        mScreenOnOffReceiver = new ScreenOnOffReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(mScreenOnOffReceiver, filter, RECEIVER_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mScreenOnOffReceiver);
    }

    @Override
    public void onApduSequenceComplete(ComponentName component, long duration) {
        if (component.equals(ScreenOffPaymentService.COMPONENT)
                && mState == STATE_SCREEN_OFF) {
            setTestPassed();
        }
    }

    @Override
    public ComponentName getPreferredServiceComponent() {
        return ScreenOffPaymentService.COMPONENT;
    }

    private class ScreenOnOffReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Received intent " + action);
            if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mState = STATE_SCREEN_OFF;
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mState = STATE_SCREEN_ON;
            }
        }
    }
}
