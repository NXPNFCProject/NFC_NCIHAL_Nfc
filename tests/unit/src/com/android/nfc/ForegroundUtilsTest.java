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

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.util.SparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class ForegroundUtilsTest {
    private static final String TAG = ForegroundUtilsTest.class.getSimpleName();
    private MockitoSession mStaticMockSession;
    private ForegroundUtils mForegroundUtils;
    private ActivityManager mActivityManager;

    @Before
    public void setUp() throws Exception {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .strictness(Strictness.LENIENT)
                .startMocking();
        mActivityManager = mock(ActivityManager.class);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mForegroundUtils = new ForegroundUtils(mActivityManager));
        Assert.assertNotNull(mForegroundUtils);
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testRegisterUidToBackgroundCallback() {
        ForegroundUtils.Callback callback = uid -> {
            Log.d(TAG, "testRegisterUidToBackgroundCallback callback received");
        };
        when(mActivityManager.getUidImportance(0)).thenReturn(ActivityManager.
                RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        boolean isRegistered = mForegroundUtils.registerUidToBackgroundCallback(callback, 0);
        Assert.assertTrue(isRegistered);
    }

    @Test
    public void testIsInForeground() {
        when(mActivityManager.getUidImportance(0)).thenReturn(100);
        when(mActivityManager.getUidImportance(10)).thenReturn(1);
        boolean isInForegroundTrue = mForegroundUtils.isInForeground(0);
        Assert.assertTrue(isInForegroundTrue);
        isInForegroundTrue = mForegroundUtils.isInForeground(10);
        Assert.assertFalse(isInForegroundTrue);
    }

    @Test
    public void testOnUidImportance() {
        mForegroundUtils.clearForegroundlist();
        mForegroundUtils.onUidImportance(0,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        List<Integer> uids = mForegroundUtils.getForegroundUids();
        Assert.assertNotNull(uids);
        Assert.assertTrue(uids.size() > 0);
        mForegroundUtils.onUidImportance(0,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE);
        uids = mForegroundUtils.getForegroundUids();
        Assert.assertNotNull(uids);
        Assert.assertTrue(uids.isEmpty());
    }

    @Test
    public void testOnUidImportanceBackground() {
        mForegroundUtils.onUidImportance(0,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        List<Integer> uids = mForegroundUtils.getForegroundUids();
        Assert.assertNotNull(uids);
        Assert.assertTrue(uids.size() > 0);
        ForegroundUtils.Callback callback = uid -> {
            Log.d(TAG, "testOnUidImportanceBackground callback received");
        };
        mForegroundUtils.registerUidToBackgroundCallback(callback, 0);

        SparseArray<List<ForegroundUtils.Callback>>
                backGroundCallbacks = mForegroundUtils.getBackgroundCallbacks();
        List<ForegroundUtils.Callback> callbacks = backGroundCallbacks.get(0);
        Assert.assertNotNull(callbacks);
        Assert.assertFalse(callbacks.isEmpty());
        mForegroundUtils.onUidImportance(0,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND);
        backGroundCallbacks = mForegroundUtils.getBackgroundCallbacks();
        callbacks = backGroundCallbacks.get(0);
        Assert.assertNull(callbacks);
    }

   @Test
    public void testGetForegroundUids() {
        mForegroundUtils.onUidImportance(0,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        mForegroundUtils.onUidImportance(1,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        mForegroundUtils.onUidImportance(2,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        List<Integer> uids = mForegroundUtils.getForegroundUids();
        Assert.assertNotNull(uids);
        int uid = uids.get(0);
        Assert.assertEquals(0, uid);
    }
}
