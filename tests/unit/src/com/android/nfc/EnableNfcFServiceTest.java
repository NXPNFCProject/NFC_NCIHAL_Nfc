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
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.cardemulation.AidRoutingManager;
import com.android.nfc.cardemulation.EnabledNfcFServices;
import com.android.nfc.cardemulation.RegisteredNfcFServicesCache;
import com.android.nfc.cardemulation.RegisteredT3tIdentifiersCache;
import com.android.nfc.cardemulation.RoutingOptionManager;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public class EnableNfcFServiceTest {

    private static final String TAG = EnableNfcFServiceTest.class.getSimpleName();
    private boolean mNfcSupported;
    private MockitoSession mStaticMockSession;
    private EnabledNfcFServices mEnabledNfcFServices;

    @Before
    public void setUp() throws Exception {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(RoutingOptionManager.class)
                .mockStatic(NfcService.class)
                .mockStatic(NfcStatsLog.class)
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

        };

        RegisteredNfcFServicesCache registeredNfcFServicesCache = mock(
                RegisteredNfcFServicesCache.class);
        RegisteredT3tIdentifiersCache registeredT3tIdentifiersCache = mock(
                RegisteredT3tIdentifiersCache.class);

        RoutingOptionManager routingOptionManager = mock(RoutingOptionManager.class);
        when(RoutingOptionManager.getInstance()).thenReturn(routingOptionManager);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mEnabledNfcFServices = new EnabledNfcFServices(mockContext,
                        registeredNfcFServicesCache, registeredT3tIdentifiersCache,
                        (userId, service) -> {
                            Log.d(TAG, "CallBack is Received for userid " + userId);

                        }));
        Assert.assertNotNull(mEnabledNfcFServices);
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testOnHostEmulationActivated() {
        if (!mNfcSupported) return;

        boolean isActivated = mEnabledNfcFServices.isActivated();
        Assert.assertFalse(isActivated);
        mEnabledNfcFServices.onHostEmulationActivated();
        isActivated = mEnabledNfcFServices.isActivated();
        Assert.assertTrue(isActivated);
    }

    @Test
    public void testOnHostEmulationDeactivated() {
        if (!mNfcSupported) return;

        mEnabledNfcFServices.onHostEmulationActivated();
        boolean isActivated = mEnabledNfcFServices.isActivated();
        Assert.assertTrue(isActivated);
        mEnabledNfcFServices.onHostEmulationDeactivated();
        isActivated = mEnabledNfcFServices.isActivated();
        Assert.assertFalse(isActivated);
    }
}
