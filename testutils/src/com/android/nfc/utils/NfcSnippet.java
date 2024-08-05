
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
package com.android.nfc.utils;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.os.RemoteException;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.google.android.mobly.snippet.Snippet;
import com.google.android.mobly.snippet.event.SnippetEvent;
import com.google.android.mobly.snippet.rpc.Rpc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public abstract class NfcSnippet implements Snippet {
    protected static final String TAG = "NfcSnippet";
    protected final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final UiDevice mDevice =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    @Rpc(description = "Checks if NFC supported on device")
    public boolean isNfcSupported() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC);
    }

    @Rpc(description = "Checks if NFC HCE (host-card emulation) supported on device")
    public boolean isNfcHceSupported() {
        return mContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
    }

    /** Turns device screen off */
    @Rpc(description = "Turns device screen off")
    public void turnScreenOff() {
        try {
            mDevice.sleep();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException", e);
        }
    }

    /** Turns device screen on */
    @Rpc(description = "Turns device screen on")
    public void turnScreenOn() {
        try {
            mDevice.wakeUp();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException", e);
        }
    }

    /** Press device menu button to return device to home screen between tests. */
    @Rpc(description = "Press menu button")
    public void pressMenu() {
        mDevice.pressMenu();
    }

    @Rpc(description = "Log info level message to device logcat")
    public void logInfo(String message) {
        Log.i(TAG, message);
    }

    /** Toggle NFC state */
    @Rpc(description = "Blocking call to toggle NFC state")
    public void setNfcState(boolean enable) throws InterruptedException {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(mContext);
        int expectedState = enable ? NfcAdapter.STATE_ON : NfcAdapter.STATE_OFF;
        if (nfcAdapter.getAdapterState() == expectedState) {
            Log.i(TAG, "toggleNfc: Already in expected state: " + expectedState);
            return;
        }
        CountDownLatch countDownLatch = new CountDownLatch(1);
        IntentFilter intentFilter = new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(intent.getAction())) {
                    int state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE,
                            NfcAdapter.STATE_OFF);
                    if (expectedState == state) countDownLatch.countDown();
                }
            }
        };
        mContext.registerReceiver(receiver, intentFilter);
        if (enable) {
            nfcAdapter.enable();
        } else {
            nfcAdapter.disable();
        }
        if (!countDownLatch.await(5, TimeUnit.SECONDS)) {
            throw  new IllegalStateException("Waiting for NFC state change failed");
        }
    }

    /** Creates a SnippetBroadcastReceiver that listens for when the specified action is received */
    protected void registerSnippetBroadcastReceiver(
            String callbackId, String eventName, String action) {
        IntentFilter filter = new IntentFilter(action);
        mContext.registerReceiver(
                new SnippetBroadcastReceiver(
                        mContext, new SnippetEvent(callbackId, eventName), action),
                filter,
                Context.RECEIVER_EXPORTED);
    }
}
