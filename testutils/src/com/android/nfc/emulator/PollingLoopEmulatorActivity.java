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
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.PollingFrame;
import android.os.Bundle;
import android.util.Log;

import com.android.nfc.service.PollingLoopService;

import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.Queue;

public class PollingLoopEmulatorActivity extends BaseEmulatorActivity {
    private static final String TAG = "PollingLoopActivity";
    int mNfcTech = 0;
    int mNfcACount = 0;
    int mNfcBCount = 0;
    int mNfcOnCount = 0;
    int mNfcOffCount = 0;
    String mCustomFrame = null;

    // Number of loops to track in queue
    public static final int NUM_LOOPS = 4;
    public static final String NFC_TECH_KEY = "NFC_TECH";
    public static final String NFC_CUSTOM_FRAME_KEY = "NFC_CUSTOM_FRAME";
    public static final String SEEN_CORRECT_POLLING_LOOP_ACTION =
            PACKAGE_NAME + ".SEEN_CORRECT_POLLING_LOOP_ACTION";
    private boolean mSentBroadcast = false;

    // Keeps track of last mCapacity PollingFrames
    private Queue<PollingFrame> mQueue = new ArrayDeque<PollingFrame>();

    private int mCapacity;

    private int mLoopSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupServices(PollingLoopService.COMPONENT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(PollingLoopService.POLLING_FRAME_ACTION);
        registerReceiver(mFieldStateReceiver, filter, RECEIVER_EXPORTED);
        mNfcTech = getIntent().getIntExtra(NFC_TECH_KEY, NfcAdapter.FLAG_READER_NFC_A);
        ComponentName serviceName =
                new ComponentName(this.getApplicationContext(), PollingLoopService.class);
        mCardEmulation.setShouldDefaultToObserveModeForService(serviceName, true);

        mCustomFrame = getIntent().getStringExtra(NFC_CUSTOM_FRAME_KEY);
        boolean isPreferredServiceSet = mCardEmulation.setPreferredService(this, serviceName);
        waitForPreferredService();
        waitForObserveModeEnabled(true);

        mNfcACount = 0;
        mNfcBCount = 0;
        mNfcOnCount = 0;
        mNfcOffCount = 0;
        mSentBroadcast = false;
        mQueue = new ArrayDeque<PollingFrame>();

        // A-B loop: 0-A-B-X
        // A loop: 0-A-X
        // B loop: 0-B-X
        mLoopSize =
                mNfcTech == (NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B) ? 4 : 3;
        mCapacity = mLoopSize * NUM_LOOPS;
        Log.d(
                TAG,
                "onResume. mNfcTech: "
                        + mNfcTech
                        + ", isPreferredServiceSet: "
                        + isPreferredServiceSet);
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.e(TAG, "onPause");
        unregisterReceiver(mFieldStateReceiver);
        mCardEmulation.unsetPreferredService(this);
    }

    @Override
    protected void onApduSequenceComplete(ComponentName component, long duration) {
        if (component.equals(PollingLoopService.COMPONENT)) {
            setTestPassed();
        }
    }

    @Override
    public ComponentName getPreferredServiceComponent() {
        return PollingLoopService.COMPONENT;
    }

    void processPollingFrames(List<PollingFrame> frames) {
        Log.d(TAG, "processPollingFrames of size " + frames.size());
        for (PollingFrame frame : frames) {
            processPollingFrame(frame);
        }
        Log.d(
                TAG,
                "seenCorrectPollingLoop?: " + seenCorrectPollingLoop()
                        + ", mNfcACount: "
                        + mNfcACount
                        + ", mNfcBCount: "
                        + mNfcBCount
                        + ", mNfcOffCount: "
                        + mNfcOffCount
                        + ", mNfcOnCount: "
                        + mNfcOnCount
                        + ", mNfcTech: "
                        + mNfcTech);

        if (seenCorrectPollingLoop() && !mSentBroadcast) {
            Intent intent = new Intent(SEEN_CORRECT_POLLING_LOOP_ACTION);
            sendBroadcast(intent);
            mSentBroadcast = true;
            Log.d(TAG, "Correct polling loop seen. Sent broadcast");
        }
    }

    private boolean seenCorrectPollingLoop() {
        if (mCustomFrame != null) {
            return false;
        }
        if (mNfcTech == NfcAdapter.FLAG_READER_NFC_A) {
            if (mNfcACount >= 3
                    && mNfcBCount == 0
                    && mNfcOnCount >= 3
                    && mNfcOffCount >= 3
                    && Math.abs(mNfcOffCount - mNfcOnCount) <= 1) {
                return true;
            }
        } else if (mNfcTech == NfcAdapter.FLAG_READER_NFC_B) {
            if (mNfcBCount >= 3
                    && mNfcACount == 0
                    && mNfcOnCount >= 3
                    && mNfcOffCount >= 3
                    && Math.abs(mNfcOffCount - mNfcOnCount) <= 1) {
                return true;
            }
        } else if (mNfcTech == (NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B)) {
            if (mNfcACount >= 3
                    && mNfcBCount >= 3
                    && mNfcOnCount >= 3
                    && mNfcOffCount >= 3
                    && Math.abs(mNfcOffCount - mNfcOnCount) <= 1) {
                return true;
            }
        }
        return false;
    }

    void processPollingFrame(PollingFrame frame) {
        int type = frame.getType();
        Log.d(TAG, "processPollingFrame: " + (char) (frame.getType()));

        if (mQueue.size() == mCapacity) {
            removeFirstElement();
        }
        mQueue.add(frame);
        switch (type) {
            case PollingFrame.POLLING_LOOP_TYPE_A:
                ++mNfcACount;
                break;
            case PollingFrame.POLLING_LOOP_TYPE_B:
                ++mNfcBCount;
                break;
            case PollingFrame.POLLING_LOOP_TYPE_ON:
                ++mNfcOnCount;
                break;
            case PollingFrame.POLLING_LOOP_TYPE_OFF:
                ++mNfcOffCount;
                break;
            case PollingFrame.POLLING_LOOP_TYPE_UNKNOWN:
                Log.e(TAG, "got custom frame: " + HexFormat.of().formatHex(frame.getData()));
                if (mCustomFrame != null && !seenCorrectPollingLoop()) {
                    byte[] data = frame.getData();
                    if (mCustomFrame.equals(HexFormat.of().formatHex(data))) {
                        Intent intent = new Intent(SEEN_CORRECT_POLLING_LOOP_ACTION);
                        sendBroadcast(intent);
                        Log.d(TAG, "Correct custom polling frame seen. Sent broadcast");
                    }
                }
                break;
        }
    }

    private void removeFirstElement() {
        PollingFrame frame = mQueue.poll();
        if (frame == null) {
            return;
        }
        switch (frame.getType()) {
            case PollingFrame.POLLING_LOOP_TYPE_A:
                --mNfcACount;
                break;
            case PollingFrame.POLLING_LOOP_TYPE_B:
                --mNfcBCount;
                break;
            case PollingFrame.POLLING_LOOP_TYPE_ON:
                --mNfcOnCount;
                break;
            case PollingFrame.POLLING_LOOP_TYPE_OFF:
                --mNfcOffCount;
                break;
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
                                        PollingFrame.class));
                    }
                }
            };
}
