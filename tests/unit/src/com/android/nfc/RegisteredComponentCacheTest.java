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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.nfc.NfcAdapter;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.util.Log;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import com.android.nfc.flags.Flags;

import org.mockito.ArgumentMatchers;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class RegisteredComponentCacheTest {

    private RegisteredComponentCache mRegisteredComponentCache;
    private boolean mNfcSupported;
    private Context mockContext;
    private static final String TAG = RegisteredComponentCacheTest.class.getSimpleName();

    @Before
    public void setUp() {

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC_ANY)) {
            mNfcSupported = false;
            return;
        }
        mNfcSupported = true;

        PowerManager mockPowerManager = mock(PowerManager.class);
        when(mockPowerManager.isInteractive()).thenReturn(false);
        Resources mockResources = mock(Resources.class);
        when(mockResources.getBoolean(eq(R.bool.tag_intent_app_pref_supported)))
                .thenReturn(false);

        mockContext = new ContextWrapper(context) {
            @Override
            public Object getSystemService(String name) {
                if (Context.POWER_SERVICE.equals(name)) {
                    Log.i(TAG, "[Mock] mockPowerManager");
                    return mockPowerManager;
                }
                return super.getSystemService(name);
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
                return mock(Intent.class);
            }

            @NonNull
            public Context createPackageContextAsUser(
                    @NonNull String packageName, @CreatePackageOptions int flags,
                    @NonNull UserHandle user) {
                Log.i(TAG, "[Mock] createPackageContextAsUser");
                Context mockedContext = mock(Context.class);
                PackageManager packageManager = mock(PackageManager.class);
                when(mockedContext.getPackageManager()).thenReturn(packageManager);
                ResolveInfo resolveInfo = mock(ResolveInfo.class);
                ActivityInfo mockActivityInfo = mock(ActivityInfo.class);
                mockActivityInfo.packageName = "com.android.nfc";
                mockActivityInfo.name = "NfcRootActivity";
                XmlResourceParser parser = mock(XmlResourceParser.class);
                try {
                    when(parser.getEventType()).thenReturn(XmlPullParser.START_TAG);
                    when(parser.next()).thenReturn(XmlPullParser.START_TAG, XmlPullParser.END_TAG,
                            XmlPullParser.END_DOCUMENT);
                    when(parser.getName()).thenReturn("tech", "tech-list");
                } catch (XmlPullParserException | IOException ignored) {
                }
                when(mockActivityInfo.loadXmlMetaData(packageManager,
                        NfcAdapter.ACTION_TECH_DISCOVERED)).thenReturn(parser);
                resolveInfo.activityInfo = mockActivityInfo;
                List<ResolveInfo> resolveInfoList = new ArrayList<ResolveInfo>();
                resolveInfoList.add(resolveInfo);
                when(packageManager.queryIntentActivitiesAsUser(Mockito.any(Intent.class),
                        Mockito.any(PackageManager.ResolveInfoFlags.class),
                        Mockito.any(UserHandle.class))).thenReturn(resolveInfoList);

                return mockedContext;
            }
        };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mRegisteredComponentCache = new RegisteredComponentCache(mockContext,
                        NfcAdapter.ACTION_TECH_DISCOVERED, NfcAdapter.ACTION_TECH_DISCOVERED));
        Assert.assertNotNull(mRegisteredComponentCache);
    }

    @Test
    public void testGetComponents() {
        if (!mNfcSupported) return;

        ArrayList<RegisteredComponentCache.ComponentInfo> componentInfos =
                mRegisteredComponentCache.getComponents();
        Assert.assertNotNull(componentInfos);
        Assert.assertTrue(componentInfos.size() > 0);
    }
}