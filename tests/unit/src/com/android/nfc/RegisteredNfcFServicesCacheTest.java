
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

package com.android.nfc;

import static org.mockito.Mockito.mock;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.cardemulation.HostNfcFEmulationManager;
import com.android.nfc.cardemulation.RegisteredNfcFServicesCache;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class RegisteredNfcFServicesCacheTest {

    private static String TAG = RegisteredNfcFServicesCacheTest.class.getSimpleName();
    private boolean mNfcSupported;
    private MockitoSession mStaticMockSession;
    private RegisteredNfcFServicesCache mNfcFServicesCache;

    @Before
    public void setUp() throws Exception {

        mStaticMockSession = ExtendedMockito.mockitoSession()
                .strictness(Strictness.LENIENT)
                .startMocking();

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC_ANY)) {
            mNfcSupported = false;
            return;
        }
        mNfcSupported = true;

        Context mockContext = new ContextWrapper(context) {
            public Intent registerReceiverForAllUsers(@Nullable BroadcastReceiver receiver,
                    @NonNull IntentFilter filter, @Nullable String broadcastPermission,
                    @Nullable Handler scheduler) {
                return mock(Intent.class);
            }

        };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mNfcFServicesCache =
                        new RegisteredNfcFServicesCache(mockContext,
                                (userId, services) -> {
                                    Log.d(TAG, "Callback is called for userId - " + userId);
                                }));
        Assert.assertNotNull(mNfcFServicesCache);

    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testOnHostEmulationActivated() {
        if (!mNfcSupported) return;

        boolean isActive = mNfcFServicesCache.isActivated();
        Assert.assertFalse(isActive);
        mNfcFServicesCache.onHostEmulationActivated();
        isActive = mNfcFServicesCache.isActivated();
        Assert.assertTrue(isActive);
    }

    @Test
    public void testOnHostEmulationDeactivated() {
        if (!mNfcSupported) return;

        mNfcFServicesCache.onHostEmulationActivated();
        boolean isActive = mNfcFServicesCache.isActivated();
        Assert.assertTrue(isActive);
        mNfcFServicesCache.onHostEmulationDeactivated();
        isActive = mNfcFServicesCache.isActivated();
        Assert.assertFalse(isActive);
    }

    @Test
    public void testOnNfcDisabled() {
        if (!mNfcSupported) return;

        mNfcFServicesCache.onHostEmulationActivated();
        boolean isActive = mNfcFServicesCache.isActivated();
        Assert.assertTrue(isActive);
        mNfcFServicesCache.onNfcDisabled();
        isActive = mNfcFServicesCache.isActivated();
        Assert.assertFalse(isActive);
    }
}
