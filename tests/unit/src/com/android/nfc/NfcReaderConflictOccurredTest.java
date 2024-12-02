/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.nfc.NfcDispatcher.DISPATCH_SUCCESS;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ResolveInfoFlags;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.util.Log;
import androidx.test.core.content.pm.ApplicationInfoBuilder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.handover.HandoverDataParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public final class NfcReaderConflictOccurredTest {

    private static final String TAG = NfcReaderConflictOccurredTest.class.getSimpleName();
    @Mock private NfcInjector mNfcInjector;
    private MockitoSession mStaticMockSession;
    private NfcDispatcher mNfcDispatcher;

    @Before
    public void setUp() {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(NfcStatsLog.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
	Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager mockPackageManager = Mockito.mock(PackageManager.class);
        // multiple resolveInfos for Tag
        when(mockPackageManager.queryIntentActivitiesAsUser(
                any(Intent.class),
                any(ResolveInfoFlags.class),
                any(UserHandle.class))).thenReturn(constructConflictingResolveInfos());
        PowerManager mockPowerManager = Mockito.mock(PowerManager.class);
        when(mockPowerManager.isInteractive()).thenReturn(false);
        Resources mockResources = Mockito.mock(Resources.class);
        when(mockResources.getBoolean(eq(R.bool.tag_intent_app_pref_supported)))
                .thenReturn(false);

        Context mockContext = new ContextWrapper(context) {
            @Override
            public PackageManager getPackageManager() {
                Log.i(TAG, "[Mock] getPackageManager");
                return mockPackageManager;
            }

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
                return Mockito.mock(Intent.class);
            }
        };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
              () -> mNfcDispatcher = new NfcDispatcher(
                      mockContext, new HandoverDataParser(), mNfcInjector, false));
        Assert.assertNotNull(mNfcDispatcher);
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testLogReaderConflict() {
        Tag tag = Tag.createMockTag(null, new int[0], new Bundle[0], 0L);
        int result = mNfcDispatcher.dispatchTag(tag);
        ExtendedMockito.verify(() -> NfcStatsLog.write(
                NfcStatsLog.NFC_READER_CONFLICT_OCCURRED));
    }

    @Test
    public void testLogReaderSuccess() {
        Tag tag = Tag.createMockTag(null, new int[0], new Bundle[0], 0L);
        int result = mNfcDispatcher.dispatchTag(tag);
        Assert.assertEquals(result,DISPATCH_SUCCESS);
    }

    public List<ResolveInfo> constructConflictingResolveInfos() {
        List<ResolveInfo> mockResolves = new ArrayList<>();
        mockResolves.add(constructResolveInfo("appName1", "packageName1", 111));
        mockResolves.add(constructResolveInfo("appName2", "packageName2", 112));
        return mockResolves;
    }

    public ResolveInfo constructResolveInfo(String appName, String packageName, int uid) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo =
            ApplicationInfoBuilder.newBuilder()
                .setName(appName)
                .setPackageName(packageName)
                .build();
        resolveInfo.activityInfo.applicationInfo.uid = uid;
        resolveInfo.activityInfo.exported = true;
        return resolveInfo;
    }
}
