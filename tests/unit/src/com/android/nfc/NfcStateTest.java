/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.NfcAdapter.ControllerAlwaysOnListener;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
public final class NfcStateTest {

    private static final String TAG = NfcStateTest.class.getSimpleName();
    private static final int MAX_TIMEOUT_MS = 20000;
    private Context mContext;
    private NfcAdapter mNfcAdapter;
    private BroadcastReceiver mAdapterStateChangedReceiver;
    private int mState;
    private boolean mIsAlwaysOnEnabled;
    private boolean mNfcSupported;
    private ControllerAlwaysOnListener mListener;

    class SynchronousExecutor implements Executor {
        public void execute(Runnable r) {
            r.run();
        }
    }

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        IntentFilter filter = new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        mAdapterStateChangedReceiver = new AdapterStateChangedReceiver();
        mContext.registerReceiver(mAdapterStateChangedReceiver, filter);
        PackageManager pm = mContext.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC_ANY)) {
            mNfcSupported = false;
            return;
        }
        mNfcSupported = true;
        mNfcAdapter = NfcAdapter.getDefaultAdapter(mContext);
        Assert.assertNotNull(mNfcAdapter);
        if (mNfcAdapter.isEnabled()) {
            mState = NfcAdapter.STATE_ON;
        } else {
            mState = NfcAdapter.STATE_OFF;
        }
        if (mNfcAdapter.isControllerAlwaysOnSupported()) {
            mListener = new AlwaysOnStateListener();
            mNfcAdapter.registerControllerAlwaysOnListener(new SynchronousExecutor(),
                    mListener);
            mIsAlwaysOnEnabled = mNfcAdapter.isControllerAlwaysOn();
        }
    }

    @After
    public void tearDown() throws Exception {
        mContext.unregisterReceiver(mAdapterStateChangedReceiver);
        if (mNfcSupported && mNfcAdapter.isControllerAlwaysOnSupported()) {
            mNfcAdapter.unregisterControllerAlwaysOnListener(mListener);
        }
    }

    @Test
    public void testSetControllerAlwaysOnTrueFromFalseWhenDisabled() {
        if (!mNfcSupported || !mNfcAdapter.isControllerAlwaysOnSupported()) return;
        if (mNfcAdapter.isControllerAlwaysOn()) {
            mNfcAdapter.setControllerAlwaysOn(false);
            wait_for_always_on(false);
            assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(false);
        }
        if (mNfcAdapter.isEnabled()) {
            mNfcAdapter.disable();
            wait_for_state(NfcAdapter.STATE_OFF);
        }
        mNfcAdapter.setControllerAlwaysOn(true);
        wait_for_always_on(true);
        assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(true);
    }

    @Test
    public void testSetControllerAlwaysOnFalseFromTrueWhenDisabled() {
        if (!mNfcSupported || !mNfcAdapter.isControllerAlwaysOnSupported()) return;
        if (!mNfcAdapter.isControllerAlwaysOn()) {
            mNfcAdapter.setControllerAlwaysOn(true);
            wait_for_always_on(true);
            assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(true);
        }
        if (mNfcAdapter.isEnabled()) {
            mNfcAdapter.disable();
            wait_for_state(NfcAdapter.STATE_OFF);
        }
        mNfcAdapter.setControllerAlwaysOn(false);
        wait_for_always_on(false);
        assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(false);
    }

    @Test
    public void testSetControllerAlwaysOnFalseFromFalseWhenDisabled() {
        if (!mNfcSupported || !mNfcAdapter.isControllerAlwaysOnSupported()) return;
        if (!mNfcAdapter.isControllerAlwaysOn()) {
            mNfcAdapter.setControllerAlwaysOn(false);
            wait_for_always_on(false);
            assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(false);
        }
        if (mNfcAdapter.isEnabled()) {
            mNfcAdapter.disable();
            wait_for_state(NfcAdapter.STATE_OFF);
        }
        mNfcAdapter.setControllerAlwaysOn(false);
        wait_for_always_on(false);
        assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(false);
    }

    @Test
    public void testSetControllerAlwaysOnTrueFromTrueWhenDisabled() {
        if (!mNfcSupported || !mNfcAdapter.isControllerAlwaysOnSupported()) return;
        if (!mNfcAdapter.isControllerAlwaysOn()) {
            mNfcAdapter.setControllerAlwaysOn(true);
            wait_for_always_on(true);
            assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(true);
        }
        if (mNfcAdapter.isEnabled()) {
            mNfcAdapter.disable();
            wait_for_state(NfcAdapter.STATE_OFF);
        }
        mNfcAdapter.setControllerAlwaysOn(true);
        wait_for_always_on(true);
        assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(true);
    }

    @Test
    public void testSetControllerAlwaysOnTrueFromFalseWhenEnabled() {
        if (!mNfcSupported || !mNfcAdapter.isControllerAlwaysOnSupported()) return;
        if (mNfcAdapter.isControllerAlwaysOn()) {
            mNfcAdapter.setControllerAlwaysOn(false);
            wait_for_always_on(false);
            assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(false);
        }
        if (!mNfcAdapter.isEnabled()) {
            mNfcAdapter.enable();
            wait_for_state(NfcAdapter.STATE_ON);
        }
        mNfcAdapter.setControllerAlwaysOn(true);
        wait_for_always_on(true);
        assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(true);
    }

    @Test
    public void testSetAlwaysOnFalseFromTrueWhenEnabled() {
        if (!mNfcSupported || !mNfcAdapter.isControllerAlwaysOnSupported()) return;
        if (!mNfcAdapter.isControllerAlwaysOn()) {
            mNfcAdapter.setControllerAlwaysOn(true);
            wait_for_always_on(true);
            assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(true);
        }
        if (!mNfcAdapter.isEnabled()) {
            mNfcAdapter.enable();
            wait_for_state(NfcAdapter.STATE_ON);
        }
        mNfcAdapter.setControllerAlwaysOn(false);
        wait_for_always_on(false);
        assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(false);
    }

    @Test
    public void testSetControllerAlwaysOnFalseFromFalseWhenEnabled() {
        if (!mNfcSupported || !mNfcAdapter.isControllerAlwaysOnSupported()) return;
        if (!mNfcAdapter.isControllerAlwaysOn()) {
            mNfcAdapter.setControllerAlwaysOn(false);
            wait_for_always_on(false);
            assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(false);
        }
        if (!mNfcAdapter.isEnabled()) {
            mNfcAdapter.enable();
            wait_for_state(NfcAdapter.STATE_ON);
        }
        mNfcAdapter.setControllerAlwaysOn(false);
        wait_for_always_on(false);
        assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(false);
    }

    @Test
    public void testSetControllerAlwaysOnTrueFromTrueWhenEnabled() {
        if (!mNfcSupported || !mNfcAdapter.isControllerAlwaysOnSupported()) return;
        if (!mNfcAdapter.isControllerAlwaysOn()) {
            mNfcAdapter.setControllerAlwaysOn(true);
            wait_for_always_on(true);
            assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(true);
        }
        if (!mNfcAdapter.isEnabled()) {
            mNfcAdapter.enable();
            wait_for_state(NfcAdapter.STATE_ON);
        }
        mNfcAdapter.setControllerAlwaysOn(true);
        wait_for_always_on(true);
        assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(true);
    }

    @Test
    public void testEnableWhenSetControllerAlwaysOnTrueAndDisabled() {
        if (!mNfcSupported || !mNfcAdapter.isControllerAlwaysOnSupported()) return;
        if (!mNfcAdapter.isControllerAlwaysOn()) {
            mNfcAdapter.setControllerAlwaysOn(true);
            wait_for_always_on(true);
            assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(true);
        }
        if (mNfcAdapter.isEnabled()) {
            mNfcAdapter.disable();
            wait_for_state(NfcAdapter.STATE_OFF);
            assertThat(mNfcAdapter.isEnabled()).isEqualTo(false);
        }
        mNfcAdapter.enable();
        wait_for_state(NfcAdapter.STATE_ON);
        assertThat(mState).isEqualTo(NfcAdapter.STATE_ON);
        assertThat(mNfcAdapter.isEnabled()).isEqualTo(true);
    }

    @Test
    public void testDisableWhenSetControllerAlwaysOnTrueAndEnabled() {
        if (!mNfcSupported || !mNfcAdapter.isControllerAlwaysOnSupported()) return;
        if (!mNfcAdapter.isControllerAlwaysOn()) {
            mNfcAdapter.setControllerAlwaysOn(true);
            wait_for_always_on(true);
            assertThat(mNfcAdapter.isControllerAlwaysOn()).isEqualTo(true);
        }
        if (!mNfcAdapter.isEnabled()) {
            mNfcAdapter.enable();
            wait_for_state(NfcAdapter.STATE_ON);
            assertThat(mNfcAdapter.isEnabled()).isEqualTo(true);
        }
        mNfcAdapter.disable();
        wait_for_state(NfcAdapter.STATE_OFF);
        assertThat(mState).isEqualTo(NfcAdapter.STATE_OFF);
        assertThat(mNfcAdapter.isEnabled()).isEqualTo(false);
    }

    @Test
    public void testDisableWhenEnabled() {
        if (!mNfcSupported) return;
        if (!mNfcAdapter.isEnabled()) {
            mNfcAdapter.enable();
            wait_for_state(NfcAdapter.STATE_ON);
        }
        mNfcAdapter.disable();
        wait_for_state(NfcAdapter.STATE_OFF);
        assertThat(mState).isEqualTo(NfcAdapter.STATE_OFF);
        assertThat(mNfcAdapter.isEnabled()).isEqualTo(false);
    }

    @Test
    public void testEnableWhenDisabled() {
        if (!mNfcSupported) return;
        if (mNfcAdapter.isEnabled()) {
            mNfcAdapter.disable();
            wait_for_state(NfcAdapter.STATE_OFF);
        }
        mNfcAdapter.enable();
        wait_for_state(NfcAdapter.STATE_ON);
        assertThat(mState).isEqualTo(NfcAdapter.STATE_ON);
        assertThat(mNfcAdapter.isEnabled()).isEqualTo(true);
    }

    @Test
    public void testDisableWhenDisabled() {
        if (!mNfcSupported) return;
        if (mNfcAdapter.isEnabled()) {
            mNfcAdapter.disable();
            wait_for_state(NfcAdapter.STATE_OFF);
        }
        mNfcAdapter.disable();
        wait_for_state(NfcAdapter.STATE_OFF);
        assertThat(mState).isEqualTo(NfcAdapter.STATE_OFF);
        assertThat(mNfcAdapter.isEnabled()).isEqualTo(false);
    }

    @Test
    public void testEnableWhenEnabled() {
        if (!mNfcSupported) return;
        if (!mNfcAdapter.isEnabled()) {
            mNfcAdapter.enable();
            wait_for_state(NfcAdapter.STATE_ON);
        }
        mNfcAdapter.enable();
        wait_for_state(NfcAdapter.STATE_ON);
        assertThat(mState).isEqualTo(NfcAdapter.STATE_ON);
        assertThat(mNfcAdapter.isEnabled()).isEqualTo(true);
    }

    private class AdapterStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(intent.getAction())) {
                mState = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE,
                        NfcAdapter.STATE_OFF);
                Log.i(TAG, "mState = " + mState);
            }
        }
    }
    private class AlwaysOnStateListener implements ControllerAlwaysOnListener {
        @Override
        public void onControllerAlwaysOnChanged(boolean isEnabled) {
            Log.i(TAG, "onControllerAlwaysOnChanged, mIsAlwaysOnEnabled = " + isEnabled);
            mIsAlwaysOnEnabled = isEnabled;
        }
    }
    private void wait_for_state(int targetState) {
        int duration = 100;
        for (int i = 0; i < MAX_TIMEOUT_MS / duration; i++) {
            msleep(duration);
            if (mState == targetState) break;
        }
    }
    private void wait_for_always_on(boolean isEnabled) {
        int duration = 1000;
        for (int i = 0; i < MAX_TIMEOUT_MS / duration; i++) {
            msleep(duration);
            if (isEnabled == mIsAlwaysOnEnabled) break;
        }
    }

    private void msleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
        }
    }
}
