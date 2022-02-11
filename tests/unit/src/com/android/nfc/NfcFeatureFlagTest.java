/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.os.SystemProperties;
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.util.ArrayUtils;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class NfcFeatureFlagTest {

    private static final String TAG = NfcFeatureFlagTest.class.getSimpleName();
    private Context mContext;
    private NfcAdapter mNfcAdapter;
    private boolean mNfcSupported;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        PackageManager pm = mContext.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC_ANY)) {
            mNfcSupported = false;
            return;
        }
        mNfcSupported = true;
        mNfcAdapter = NfcAdapter.getDefaultAdapter(mContext);
        Assert.assertNotNull(mNfcAdapter);
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testIsSecureNfcSupported() {
        if (!mNfcSupported) return;
        String[] skuList = mContext.getResources().getStringArray(
                R.array.config_skuSupportsSecureNfc);
        String sku = SystemProperties.get("ro.boot.hardware.sku");
        if (TextUtils.isEmpty(sku) || !ArrayUtils.contains(skuList, sku)) {
            assertFalse(mNfcAdapter.isSecureNfcSupported());
        } else {
            assertTrue(mNfcAdapter.isSecureNfcSupported());
        }
    }

    @Test
    public void testIsControllerAlwaysOnSupported() {
        if (!mNfcSupported) return;
        assertThat(mContext.getResources()
                .getBoolean(R.bool.nfcc_always_on_allowed))
                .isEqualTo(mNfcAdapter.isControllerAlwaysOnSupported());
    }
}
