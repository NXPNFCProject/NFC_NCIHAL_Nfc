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
    private MockitoSession mStaticMockSession;
    private DeviceConfigFacade mDeviceConfigFacade;
    private DeviceConfigFacade mDeviceConfigFacadeFalse;

    @Before
    public void setUp() throws Exception {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .strictness(Strictness.LENIENT)
                .startMocking();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Handler handler = mock(Handler.class);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mDeviceConfigFacade = new DeviceConfigFacade(getMockedContext(
                        true, context), handler));
        Assert.assertNotNull(mDeviceConfigFacade);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mDeviceConfigFacadeFalse = new DeviceConfigFacade(getMockedContext(
                        false, context), handler));
        Assert.assertNotNull(mDeviceConfigFacadeFalse);
    }

    private Context getMockedContext(boolean isAntenaEnabled, Context context) {
        Resources mockResources = mock(Resources.class);
        when(mockResources.getBoolean(eq(R.bool.enable_antenna_blocked_alert)))
                .thenReturn(isAntenaEnabled);

        return new ContextWrapper(context) {

            @Override
            public Resources getResources() {
                Log.i(TAG, "[Mock] getResources");
                return mockResources;
            }
        };
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testIsAntennaBlockedAlertEnabled() {
        boolean isAlertEnabled = mDeviceConfigFacade.isAntennaBlockedAlertEnabled();
        Log.d(TAG, "isAlertEnabled -" + isAlertEnabled);
        Assert.assertTrue(isAlertEnabled);
    }

    @Test
    public void testIsAntennaBlockedAlertDisabled() {
        boolean isAlertEnabled = mDeviceConfigFacadeFalse.isAntennaBlockedAlertEnabled();
        Log.d(TAG, "isAlertEnabled -" + isAlertEnabled);
        Assert.assertFalse(isAlertEnabled);
    }
}
