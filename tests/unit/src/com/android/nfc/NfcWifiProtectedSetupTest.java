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

import static com.android.nfc.NfcWifiProtectedSetup.NFC_TOKEN_MIME_TYPE;

import static com.google.common.primitives.Bytes.concat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.wifi.WifiConfiguration;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.tech.Ndef;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import junit.framework.TestCase;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.nio.ByteBuffer;


@RunWith(AndroidJUnit4.class)
public class NfcWifiProtectedSetupTest extends TestCase {

    private static final String TAG = NfcWifiProtectedSetupTest.class.getSimpleName();
    private boolean mNfcSupported;
    private MockitoSession mStaticMockSession;
    private Context mockContext;
    public static final byte[] CREDENTIAL = {0x10, 0x0e};
    public static final byte[] NETWORK_IDX = {0x10, 0x26};
    public static final byte[] NETWORK_NAME = {0x10, 0x45};
    public static final byte[] AUTH_TYPE = {0x10, 0x03};
    public static final byte[] CRYPT_TYPE = {0x10, 0x0F};
    public static final byte[] AUTH_WPA_PERSONAL = {0x00, 0x02};
    public static final byte[] CRYPT_WEP = {0x00, 0x02};
    public static final byte[] CRYPT_AES_TKIP = {0x00, 0x0C};
    public static final byte[] NETWORK_KEY = {0x10, 0x27};
    public static final byte[] WPS_AUTH_WPA_PERSONAL = {0x00, 0x02};
    public static final byte[] MAC_ADDRESS = {0x10, 0x20};


    @Before
    public void setUp() throws Exception {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(Ndef.class)
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
                    @Nullable Handler scheduler) {
                Log.i(TAG, "[Mock] getIntent");
                return Mockito.mock(Intent.class);
            }
        };
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testTryNfcWifiSetupFailed() {
        if (!mNfcSupported) return;

        Ndef ndef = mock(Ndef.class);
        NdefMessage ndefMessage = mock(NdefMessage.class);

        NdefRecord[] ndefRecords = new NdefRecord[2];
        byte[] version = new byte[]{(0x1 << 4) | (0x2)};
        ndefRecords[0] = new NdefRecord(NdefRecord.TNF_WELL_KNOWN,
                NdefRecord.RTD_HANDOVER_REQUEST, new byte[0], version);
        ndefRecords[1] = createWifiRecord(new String[]{"Nfctest", "", "Open", "wet"});
        when(ndefMessage.getRecords()).thenReturn(ndefRecords);
        when(ndef.getCachedNdefMessage()).thenReturn(ndefMessage);
        boolean isSucceeded = NfcWifiProtectedSetup.tryNfcWifiSetup(ndef, mockContext);
        Log.d(TAG, "testTryNfcWifiSetupFailed - " + isSucceeded);
        Assert.assertFalse(isSucceeded);
    }

    private NdefRecord createWifiRecord(String[] data) {
        String ssid = data[0];
        String password = data[1];
        byte[] ssidByte = ssid.getBytes();
        byte[] passwordByte = password.getBytes();
        byte[] ssidLength = {(byte) ((int) Math.floor(ssid.length() / 256)),
                (byte) (ssid.length() % 256)};
        byte[] passwordLength = {(byte) ((int) Math.floor(password.length() / 256)),
                (byte) (password.length() % 256)};
        byte[] cred = {0x00, 0x36};
        byte[] idx = {0x00, 0x01, 0x01};
        byte[] mac = {};

        byte[] payload = concat(CREDENTIAL, cred,
                NETWORK_IDX, idx,
                NETWORK_NAME, ssidLength, ssidByte,
                AUTH_TYPE, AUTH_WPA_PERSONAL, WPS_AUTH_WPA_PERSONAL,
                CRYPT_TYPE, CRYPT_WEP, CRYPT_AES_TKIP,
                NETWORK_KEY, passwordLength, passwordByte,
                MAC_ADDRESS, mac);
        return NdefRecord.createMime(NFC_TOKEN_MIME_TYPE, payload);
    }
}