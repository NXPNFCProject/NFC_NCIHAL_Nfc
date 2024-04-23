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

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

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
import com.android.nfc.cardemulation.AidRoutingManager;
import com.android.nfc.cardemulation.RegisteredAidCache;

@RunWith(AndroidJUnit4.class)
public class RegisteredAidCacheTest {

    private static final String TAG = RegisteredAidCacheTest.class.getSimpleName();
    private boolean mNfcSupported;
    private MockitoSession mStaticMockSession;
    private RegisteredAidCache mRegisteredAidCache;
    private Context mockContext;

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

        mockContext = new ContextWrapper(context) {

        };

        AidRoutingManager routingManager = mock(AidRoutingManager.class);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mRegisteredAidCache = new RegisteredAidCache(mockContext, routingManager));
        Assert.assertNotNull(mRegisteredAidCache);
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }


    @Test
    public void testOnPreferredForegroundServiceChanged() {
        if (!mNfcSupported) return;

        ComponentName componentName = mRegisteredAidCache.getPreferredService();
        Assert.assertNull(componentName);

        componentName = new ComponentName("com.android.nfc",
                RegisteredAidCacheTest.class.getName());
        mRegisteredAidCache.onPreferredForegroundServiceChanged(0, componentName);
        ComponentName preferredService = mRegisteredAidCache.getPreferredService();

        Assert.assertNotNull(preferredService);
        Assert.assertEquals(componentName.getClassName(), preferredService.getClassName());
    }

}