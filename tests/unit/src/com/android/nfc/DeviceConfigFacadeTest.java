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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.util.Log;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;


@RunWith(AndroidJUnit4.class)
public class DeviceConfigFacadeTest {

    private static final String TAG = DeviceConfigFacadeTest.class.getSimpleName();
    private boolean mNfcSupported;
    private MockitoSession mStaticMockSession;
    private DeviceConfigFacade mDeviceConfigFacade;

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


        Resources mockResources = mock(Resources.class);
        when(mockResources.getBoolean(eq(R.bool.enable_antenna_blocked_alert)))
                .thenReturn(true);


        Context mockContext = new ContextWrapper(context) {

            @Override
            public Resources getResources() {
                Log.i(TAG, "[Mock] getResources");
                return mockResources;
            }
        };

        Handler handler = mock(Handler.class);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mDeviceConfigFacade = new DeviceConfigFacade(mockContext, handler));
        Assert.assertNotNull(mDeviceConfigFacade);
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testIsAntennaBlockedAlertEnabled() {
        if (!mNfcSupported) return;

        boolean isAlertEnabled = mDeviceConfigFacade.isAntennaBlockedAlertEnabled();
        Log.d(TAG, "isAlertEnabled -" + isAlertEnabled);
        Assert.assertTrue(isAlertEnabled);
    }
}
