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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.nfc.Constants;
import android.os.Build;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.cardemulation.AidRoutingManager;
import com.android.nfc.cardemulation.CardEmulationManager;
import com.android.nfc.cardemulation.HostNfcFEmulationManager;
import com.android.nfc.cardemulation.RegisteredAidCache;
import com.android.nfc.cardemulation.RegisteredT3tIdentifiersCache;
import com.android.nfc.cardemulation.RoutingOptionManager;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import com.android.nfc.flags.Flags;

@RunWith(AndroidJUnit4.class)
public class HostNfcFEmulationManagerTest {

    private static final String TAG = HostNfcFEmulationManagerTest.class.getSimpleName();
    private boolean mNfcSupported;
    private MockitoSession mStaticMockSession;
    private HostNfcFEmulationManager mHostNfcFEmulationManager;

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

            public Context createContextAsUser(@NonNull UserHandle user,
                    @CreatePackageOptions int flags) {
                if (Build.IS_ENG) {
                    throw new IllegalStateException("createContextAsUser not overridden!");
                }
                return this;
            }

            public Intent registerReceiverForAllUsers(@Nullable BroadcastReceiver receiver,
                    @NonNull IntentFilter filter, @Nullable String broadcastPermission,
                    @Nullable Handler scheduler) {
                return mock(Intent.class);
            }

            public ContentResolver getContentResolver() {
                return mock(ContentResolver.class);
            }


        };

        RegisteredT3tIdentifiersCache t3tIdentifiersCache = mock(
                RegisteredT3tIdentifiersCache.class);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mHostNfcFEmulationManager =
                        new HostNfcFEmulationManager(mockContext, t3tIdentifiersCache));
        Assert.assertNotNull(mHostNfcFEmulationManager);
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testOnEnabledForegroundNfcFServiceChanged() {
        if (!mNfcSupported) return;

        String packageName = mHostNfcFEmulationManager.getEnabledFgServiceName();
        Assert.assertNull(packageName);
        ComponentName componentName = mock(ComponentName.class);
        when(componentName.getPackageName()).thenReturn("com.android.nfc");
        mHostNfcFEmulationManager.onEnabledForegroundNfcFServiceChanged(0, componentName);
        packageName = mHostNfcFEmulationManager.getEnabledFgServiceName();
        Assert.assertNotNull(packageName);
        Assert.assertEquals("com.android.nfc", packageName);

    }
}
