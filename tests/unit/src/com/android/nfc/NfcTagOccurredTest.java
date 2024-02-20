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

import static android.nfc.tech.Ndef.EXTRA_NDEF_MSG;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.bluetooth.BluetoothProtoEnums;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.handover.HandoverDataParser;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public final class NfcTagOccurredTest {

    private static final String TAG = NfcTagOccurredTest.class.getSimpleName();
    private boolean mNfcSupported;

    private MockitoSession mStaticMockSession;
    private NfcDispatcher mNfcDispatcher;

    private Context mockContext;

    @Before
    public void setUp() {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(NfcStatsLog.class)
                .mockStatic(Ndef.class)
                .mockStatic(NfcWifiProtectedSetup.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC_ANY)) {
            mNfcSupported = false;
            return;
        }
        mNfcSupported = true;

        PowerManager mockPowerManager = Mockito.mock(PowerManager.class);
        when(mockPowerManager.isInteractive()).thenReturn(false);
        Resources mockResources = Mockito.mock(Resources.class);
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
                    @Nullable Handler scheduler){
                Log.i(TAG, "[Mock] getIntent");
                return Mockito.mock(Intent.class);
            }
        };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
              () -> mNfcDispatcher = new NfcDispatcher(mockContext,
                      new HandoverDataParser(), false));
        Assert.assertNotNull(mNfcDispatcher);
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testLogOthers() {
        if (!mNfcSupported) return;

        Tag tag = Tag.createMockTag(null, new int[0], new Bundle[0], 0L);
        mNfcDispatcher.dispatchTag(tag);
        ExtendedMockito.verify(() -> NfcStatsLog.write(
                NfcStatsLog.NFC_TAG_OCCURRED,
                NfcStatsLog.NFC_TAG_OCCURRED__TYPE__OTHERS,
                -1,
                tag.getTechCodeList(),
                BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED,
                ""));
    }
        @Test
        public void testSetForegroundDispatchForWifiConnect() {
            if (!mNfcSupported) return;
            PendingIntent pendingIntent = mock(PendingIntent.class);
            mNfcDispatcher.setForegroundDispatch(pendingIntent, new IntentFilter[]{},
                    new String[][]{});
            Bundle bundle = mock(Bundle.class);
            when(bundle.getParcelable(EXTRA_NDEF_MSG, android.nfc.NdefMessage.class)).thenReturn(
                    mock(
                            NdefMessage.class));
            Tag tag = Tag.createMockTag(null, new int[]{1}, new Bundle[]{bundle}, 0L);
            Ndef ndef = mock(Ndef.class);
            when(Ndef.get(tag)).thenReturn(ndef);
            NdefMessage ndefMessage = mock(NdefMessage.class);
            when(ndef.getCachedNdefMessage()).thenReturn(ndefMessage);
            NdefRecord ndefRecord = mock(NdefRecord.class);
            NdefRecord[] records = {ndefRecord};
            when(ndefMessage.getRecords()).thenReturn(records);
            when(NfcWifiProtectedSetup.tryNfcWifiSetup(ndef, mockContext)).thenReturn(true);
            mNfcDispatcher.dispatchTag(tag);
            ExtendedMockito.verify(() -> NfcStatsLog.write(
                    NfcStatsLog.NFC_TAG_OCCURRED,
                    NfcStatsLog.NFC_TAG_OCCURRED__TYPE__WIFI_CONNECT,
                    -1,
                    tag.getTechCodeList(),
                    BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED,
                    ""));
        }
}
