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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Handler;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;


@RunWith(AndroidJUnit4.class)
public class DtaServiceConnectorTest {

    private static final String TAG = DtaServiceConnectorTest.class.getSimpleName();
    private MockitoSession mStaticMockSession;
    private Context mockContext;
    private DtaServiceConnector mDtaServiceConnector;
    private Intent implicitIntent;

    @Before
    public void setUp() throws Exception {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .strictness(Strictness.LENIENT)
                .startMocking();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources mockResources = Mockito.mock(Resources.class);
        when(mockResources.getBoolean(eq(R.bool.tag_intent_app_pref_supported)))
                .thenReturn(false);

        implicitIntent = mock(Intent.class);

        mockContext = new ContextWrapper(context) {

            public PackageManager getPackageManager() {
                Log.i(TAG, "[Mock] getPackageManager");
                PackageManager packageManager = mock(PackageManager.class);
                ResolveInfo resolveInfo = mock(ResolveInfo.class);
                resolveInfo.serviceInfo = mock(ServiceInfo.class);
                resolveInfo.serviceInfo.packageName = "com.android.nfc";
                resolveInfo.serviceInfo.name = "Nfc";
                List<ResolveInfo> resolveInfos = new ArrayList<>();
                resolveInfos.add(resolveInfo);
                when(implicitIntent.getAction()).thenReturn("nfcaction");
                when(packageManager.queryIntentServices(implicitIntent, 0)).thenReturn(
                        resolveInfos);
                return packageManager;
            }

            @Override
            public Resources getResources() {
                Log.i(TAG, "[Mock] getResources");
                return mockResources;
            }

            @Override
            public Intent registerReceiverForAllUsers(@Nullable BroadcastReceiver receiver,
                    @NonNull IntentFilter filter, @Nullable String broadcastPermission,
                    @Nullable Handler scheduler) {
                Log.i(TAG, "[Mock] getIntent");
                return Mockito.mock(Intent.class);
            }
        };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mDtaServiceConnector = new DtaServiceConnector(mockContext));
        Assert.assertNotNull(mDtaServiceConnector);
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testCreateExplicitFromImplicitIntent() {
        Intent intent = DtaServiceConnector.createExplicitFromImplicitIntent(mockContext,
                implicitIntent);
        Assert.assertNotNull(intent);
        ComponentName componentName = intent.getComponent();
        Assert.assertNotNull(componentName);
        Assert.assertEquals("com.android.nfc", componentName.getPackageName());
    }
}
