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
package com.android.nfc.cardemulation;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothProtoEnums;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.os.UserHandle;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.cardemulation.RegisteredAidCache.AidResolveInfo;
import com.android.nfc.NfcStatsLog;
import com.android.nfc.flags.Flags;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoSession;

@RunWith(AndroidJUnit4.class)
public final class NfcAidConflictOccurredTest {

    private static final String TAG = NfcAidConflictOccurredTest.class.getSimpleName();
    private boolean mNfcSupported;

    private MockitoSession mStaticMockSession;
    private HostEmulationManager mHostEmulation;
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(NfcStatsLog.class)
                .startMocking();

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            mNfcSupported = false;
            return;
        }
        mNfcSupported = true;

        RegisteredAidCache mockAidCache = Mockito.mock(RegisteredAidCache.class);
        ApduServiceInfo apduServiceInfo = Mockito.mock(ApduServiceInfo.class);
        AidResolveInfo aidResolveInfo = mockAidCache.new AidResolveInfo();
        // no defaultService and no activeService
        aidResolveInfo.services = new ArrayList<ApduServiceInfo>();
        aidResolveInfo.services.add(apduServiceInfo);
        when(mockAidCache.resolveAid(anyString())).thenReturn(aidResolveInfo);

        Context mockContext = new ContextWrapper(context) {
            @Override
            public void startActivityAsUser(Intent intent, UserHandle user) {
                Log.i(TAG, "[Mock] startActivityAsUser");
            }

            @Override
            public void sendBroadcastAsUser(Intent intent, UserHandle user) {
                Log.i(TAG, "[Mock] sendBroadcastAsUser");
            }
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
              () -> mHostEmulation = new HostEmulationManager(mockContext, mockAidCache));
        Assert.assertNotNull(mHostEmulation);

        mHostEmulation.onHostEmulationActivated();
    }

    @After
    public void tearDown() {
        mHostEmulation.onHostEmulationDeactivated();
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testHCEOther() {
        if (!mNfcSupported) return;

        byte[] aidBytes = new byte[] {
            0x00, (byte)0xA4, 0x04, 0x00,  // command
            0x08,  // data length
            (byte)0xA0, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00,
            0x00,  // card manager AID
            0x00  // trailer
        };
        mHostEmulation.onHostEmulationData(aidBytes);
        ExtendedMockito.verify(() -> NfcStatsLog.write(
                NfcStatsLog.NFC_AID_CONFLICT_OCCURRED,
                "A000000003000000"));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TEST_FLAG)
    public void testHCEOtherWithTestFlagEnabled() {
        if (!mNfcSupported) return;

        byte[] aidBytes = new byte[] {
                0x00, (byte)0xA4, 0x04, 0x00,  // command
                0x08,  // data length
                (byte)0xA0, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00,
                0x00,  // card manager AID
                0x00  // trailer
        };
        mHostEmulation.onHostEmulationData(aidBytes);
        ExtendedMockito.verify(() -> NfcStatsLog.write(
                NfcStatsLog.NFC_AID_CONFLICT_OCCURRED,
                "A000000003000000"));
    }
}
