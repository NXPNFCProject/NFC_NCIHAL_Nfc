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
import android.nfc.cardemulation.PollingFrame;
import android.os.Bundle;
import android.util.Log;

import com.android.nfc.service.PollingLoopService;
import com.android.nfc.service.PollingLoopService2;

import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.Queue;

public class TwoPollingFrameEmulatorActivity extends BaseEmulatorActivity {
    private static final String TAG = "TwoPollingFrameActivity";

    private static final String SERVICE_2_FRAME = "bbbbbbbb";

    // Number of loops to track in queue
    public static final String SEEN_CORRECT_POLLING_LOOP_ACTION =
            PACKAGE_NAME + ".SEEN_CORRECT_POLLING_LOOP_ACTION";

    private int mService1Count = 0;
    private boolean mService2Matched = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupServices(PollingLoopService.COMPONENT, PollingLoopService2.COMPONENT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(PollingLoopService.POLLING_FRAME_ACTION);
        registerReceiver(mFieldStateReceiver, filter, RECEIVER_EXPORTED);
        ComponentName serviceName1 =
                new ComponentName(this.getApplicationContext(), PollingLoopService.class);
        mCardEmulation.setShouldDefaultToObserveModeForService(serviceName1, true);

        ComponentName serviceName2 =
                new ComponentName(this.getApplicationContext(), PollingLoopService2.class);
        mCardEmulation.setShouldDefaultToObserveModeForService(serviceName2, true);

        mCardEmulation.setPreferredService(this, serviceName1);
        waitForPreferredService();
        waitForObserveModeEnabled(true);
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.e(TAG, "onPause");
        unregisterReceiver(mFieldStateReceiver);
        mCardEmulation.unsetPreferredService(this);
    }

    @Override
    public ComponentName getPreferredServiceComponent() {
        return PollingLoopService.COMPONENT;
    }

    void processPollingFrames(List<PollingFrame> frames, String serviceName) {
        Log.d(TAG, "processPollingFrames of size " + frames.size());
        for (PollingFrame frame : frames) {
            processPollingFrame(frame, serviceName);
        }
    }

    void processPollingFrame(PollingFrame frame, String serviceName) {
        Log.d(TAG, "processPollingFrame: " + (char) (frame.getType()) + " service: " + serviceName);

        if (frame.getType() == PollingFrame.POLLING_LOOP_TYPE_UNKNOWN) {
            Log.e(TAG, "got custom frame: " + HexFormat.of().formatHex(frame.getData()));
            byte[] data = frame.getData();
            if (serviceName.equals(PollingLoopService2.class.getName()) &&
                    SERVICE_2_FRAME.equals(HexFormat.of().formatHex(data))) {
                Intent intent = new Intent(SEEN_CORRECT_POLLING_LOOP_ACTION);
                sendBroadcast(intent);
                mService2Matched = true;
                Log.d(TAG, "Correct custom polling frame seen. Sent broadcast");
            }
        } else if (mService2Matched && serviceName.equals(PollingLoopService.class.getName())) {
            mService1Count++;
            if (mService1Count >= 6) {
                Intent intent = new Intent(SEEN_CORRECT_POLLING_LOOP_ACTION);
                sendBroadcast(intent);
            }
        }
    }

    final BroadcastReceiver mFieldStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action.equals(PollingLoopService.POLLING_FRAME_ACTION)) {
                        processPollingFrames(
                                intent.getParcelableArrayListExtra(
                                        PollingLoopService.POLLING_FRAME_EXTRA,
                                        PollingFrame.class),
                                intent.getStringExtra(PollingLoopService.SERVICE_NAME_EXTRA));
                    }
                }
            };
}
