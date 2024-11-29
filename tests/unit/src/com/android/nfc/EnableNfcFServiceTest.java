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

import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.os.UserHandle;
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
    private MockitoSession mStaticMockSession;
    private ComponentName mComponentName;
    private NfcFServiceInfo mNfcFServiceInfo;
    private EnabledNfcFServices mEnabledNfcFServices;
    private ForegroundUtils mForegroundUtils;

    @Before
    public void setUp() throws Exception {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(RoutingOptionManager.class)
                .mockStatic(NfcService.class)
                .mockStatic(NfcStatsLog.class)
                .mockStatic(UserHandle.class)
                .mockStatic(ForegroundUtils.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context mockContext = new ContextWrapper(context) {
        };

        mForegroundUtils = mock(ForegroundUtils.class);
        when(ForegroundUtils.getInstance(
                mockContext.getSystemService(ActivityManager.class))).thenReturn(mForegroundUtils);
        RegisteredNfcFServicesCache registeredNfcFServicesCache = mock(
                RegisteredNfcFServicesCache.class);
        mComponentName = mock(ComponentName.class);
        mNfcFServiceInfo = mock(NfcFServiceInfo.class);
        when(registeredNfcFServicesCache.getService(1, mComponentName)).thenReturn(
                mNfcFServiceInfo);
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
        boolean isActivated = mEnabledNfcFServices.isActivated();
        Assert.assertFalse(isActivated);
        mEnabledNfcFServices.onHostEmulationActivated();
        isActivated = mEnabledNfcFServices.isActivated();
        Assert.assertTrue(isActivated);
    }

    @Test
    public void testOnHostEmulationDeactivated() {
        mEnabledNfcFServices.onHostEmulationActivated();
        boolean isActivated = mEnabledNfcFServices.isActivated();
        Assert.assertTrue(isActivated);
        mEnabledNfcFServices.onHostEmulationDeactivated();
        isActivated = mEnabledNfcFServices.isActivated();
        Assert.assertFalse(isActivated);
    }

    @Test
    public void testRegisterEnabledForegroundService() {
        UserHandle userHandle = mock(UserHandle.class);
        when(userHandle.getIdentifier()).thenReturn(1);
        when(UserHandle.getUserHandleForUid(1)).thenReturn(userHandle);
        when(mNfcFServiceInfo.getSystemCode()).thenReturn("Nfc");
        when(mNfcFServiceInfo.getNfcid2()).thenReturn("NfcId");
        when(mNfcFServiceInfo.getT3tPmm()).thenReturn("T3");
        when(mForegroundUtils.registerUidToBackgroundCallback(mEnabledNfcFServices, 1)).thenReturn(
                true);
        boolean isRegistered = mEnabledNfcFServices.registerEnabledForegroundService(mComponentName,
                1);
        Assert.assertTrue(isRegistered);
    }


    @Test
    public void testOnNfcDisabled() {
        mEnabledNfcFServices.onNfcDisabled();
        boolean isNfcDisabled = mEnabledNfcFServices.isNfcDisabled();
        Assert.assertTrue(isNfcDisabled);
    }

    @Test
    public void testOnUserSwitched() {
        mEnabledNfcFServices.onUserSwitched(0);
        boolean isUserSwitched = mEnabledNfcFServices.isUserSwitched();
        Assert.assertTrue(isUserSwitched);

    }
}
