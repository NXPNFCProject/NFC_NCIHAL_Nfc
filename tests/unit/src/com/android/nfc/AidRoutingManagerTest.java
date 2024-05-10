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
/******************************************************************************
 *
 *  The original Work has been changed by NXP.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  Copyright 2024 NXP
 *
 ******************************************************************************/

package com.android.nfc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.NfcService;
import com.android.nfc.cardemulation.AidRoutingManager;
import com.android.nfc.cardemulation.RoutingOptionManager;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class AidRoutingManagerTest {

    private static final String TAG = AidRoutingManagerTest.class.getSimpleName();
    private boolean mNfcSupported;
    private MockitoSession mStaticMockSession;
    private AidRoutingManager mAidRoutingManager;

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

        when(NfcService.getInstance()).thenReturn(mock(NfcService.class));
        RoutingOptionManager routingOptionManager = mock(RoutingOptionManager.class);
        when(RoutingOptionManager.getInstance()).thenReturn(routingOptionManager);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mAidRoutingManager = new AidRoutingManager());
        Assert.assertNotNull(mAidRoutingManager);
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testCalculateAidRouteSize() {
        if (!mNfcSupported) return;

        HashMap<String, AidRoutingManager.AidEntry> aidEntryMap = new HashMap<>();
        int size = mAidRoutingManager.calculateAidRouteSize(aidEntryMap);
        Assert.assertEquals(0, size);
        AidRoutingManager.AidEntry aidEntry = mAidRoutingManager.new AidEntry();
        aidEntryMap.put("test*", aidEntry);
        size = mAidRoutingManager.calculateAidRouteSize(aidEntryMap);
        Assert.assertEquals(6, size);
    }

    @Test
    public void testOnNfccRoutingTableCleared() {
        if (!mNfcSupported) return;

        mAidRoutingManager.onNfccRoutingTableCleared();
        boolean isTableCleared = mAidRoutingManager.isRoutingTableCleared();
        Assert.assertTrue(isTableCleared);
    }

    @Test
    public void testSupportsAidPrefixRouting() {
        if (!mNfcSupported) return;

        boolean isSupportPrefixRouting = mAidRoutingManager.supportsAidPrefixRouting();
        Assert.assertFalse(isSupportPrefixRouting);
    }

    @Test
    public void testSupportsAidSubsetRouting() {
        if (!mNfcSupported) return;

        boolean isSupportSubsetRouting = mAidRoutingManager.supportsAidSubsetRouting();
        Assert.assertFalse(isSupportSubsetRouting);
    }

    @Test
    public void testConfigureRoutingErrorOccurred() {
        if (!mNfcSupported) return;

        NfcService nfcService = mock(NfcService.class);
        when(NfcService.getInstance()).thenReturn(nfcService);
        when(nfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_2_0);
        HashMap<String, AidRoutingManager.AidEntry> aidEntryMap = new HashMap<>();
        boolean isConfigureRouting = mAidRoutingManager.configureRouting(aidEntryMap, true);
        Assert.assertTrue(isConfigureRouting);
        ExtendedMockito.verify(() -> NfcStatsLog.write(
                NfcStatsLog.NFC_ERROR_OCCURRED,
                NfcStatsLog.NFC_ERROR_OCCURRED__TYPE__AID_OVERFLOW,
                0,
                0));
    }
}
