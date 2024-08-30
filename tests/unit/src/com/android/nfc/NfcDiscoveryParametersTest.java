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

import android.content.Context;
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

@RunWith(AndroidJUnit4.class)
public class NfcDiscoveryParametersTest {

    private static final String TAG = NfcDiscoveryParametersTest.class.getSimpleName();
    private boolean mNfcSupported;
    private MockitoSession mStaticMockSession;

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
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }


    private NfcDiscoveryParameters computeDiscoveryParameters(boolean isP2pEnable) {
        // Recompute discovery parameters based on screen state
        NfcDiscoveryParameters.Builder paramsBuilder = NfcDiscoveryParameters.newBuilder();
        paramsBuilder.setTechMask(1);
        paramsBuilder.setEnableLowPowerDiscovery(true);
        paramsBuilder.setEnableHostRouting(true);
        if (isP2pEnable) {
            paramsBuilder.setEnableP2p(true);
        } else {
            paramsBuilder.setEnableReaderMode(true);
        }
        return paramsBuilder.build();
    }

    @Test
    public void testGetTechMask() {
        if (!mNfcSupported) return;

        NfcDiscoveryParameters nfcDiscoveryParameters = computeDiscoveryParameters(false);
        int techMask = nfcDiscoveryParameters.getTechMask();
        Assert.assertEquals(1, techMask);
    }

    @Test
    public void testShouldEnableP2p() {
        if (!mNfcSupported) return;

        NfcDiscoveryParameters nfcDiscoveryParameters = computeDiscoveryParameters(false);
        boolean shouldP2pEnable = nfcDiscoveryParameters.shouldEnableP2p();
        Assert.assertFalse(shouldP2pEnable);
    }

    @Test
    public void testDiscoveryParameters() {
        if (!mNfcSupported) return;

        NfcDiscoveryParameters.Builder paramsBuilder = NfcDiscoveryParameters.newBuilder();
        NfcDiscoveryParameters nfcDiscoveryParameters = paramsBuilder.build();
        boolean shouldEnableDiscovery = nfcDiscoveryParameters.shouldEnableDiscovery();
        boolean shouldEnableLowPowerDiscovery =
                nfcDiscoveryParameters.shouldEnableLowPowerDiscovery();
        boolean shouldEnableReaderMode = nfcDiscoveryParameters.shouldEnableReaderMode();
        boolean shouldEnableHostRouting = nfcDiscoveryParameters.shouldEnableHostRouting();

        Assert.assertFalse(shouldEnableDiscovery);
        Assert.assertTrue(shouldEnableLowPowerDiscovery);
        Assert.assertFalse(shouldEnableReaderMode);
        Assert.assertFalse(shouldEnableHostRouting);

        nfcDiscoveryParameters = computeDiscoveryParameters(false);
        shouldEnableDiscovery = nfcDiscoveryParameters.shouldEnableDiscovery();
        shouldEnableLowPowerDiscovery = nfcDiscoveryParameters.shouldEnableLowPowerDiscovery();
        shouldEnableReaderMode = nfcDiscoveryParameters.shouldEnableReaderMode();
        shouldEnableHostRouting = nfcDiscoveryParameters.shouldEnableHostRouting();

        Assert.assertTrue(shouldEnableDiscovery);
        Assert.assertFalse(shouldEnableLowPowerDiscovery);
        Assert.assertTrue(shouldEnableReaderMode);
        Assert.assertTrue(shouldEnableHostRouting);

    }

}
