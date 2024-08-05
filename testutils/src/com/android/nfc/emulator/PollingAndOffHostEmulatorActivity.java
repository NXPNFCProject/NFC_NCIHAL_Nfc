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
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import com.android.nfc.service.OffHostService;
import com.android.nfc.service.PollingLoopService;

public class PollingAndOffHostEmulatorActivity extends PollingLoopEmulatorActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupServices(PollingLoopService.COMPONENT, OffHostService.COMPONENT);
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(SEEN_CORRECT_POLLING_LOOP_ACTION);
        registerReceiver(mSeenCorrectLoopReceiver, filter, RECEIVER_EXPORTED);
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(mSeenCorrectLoopReceiver);
    }

    final BroadcastReceiver mSeenCorrectLoopReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action.equals(SEEN_CORRECT_POLLING_LOOP_ACTION)) {
                        mAdapter.setObserveModeEnabled(false);
                    }
                }
            };
}
