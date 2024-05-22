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

import static com.android.nfc.cardemulation.HostEmulationManager.STATE_W4_SELECT;
import static com.android.nfc.cardemulation.HostEmulationManager.STATE_W4_SERVICE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothProtoEnums;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.flags.Flags;
import com.android.nfc.NfcService;
import com.android.nfc.cardemulation.RegisteredAidCache.AidResolveInfo;
import com.android.nfc.NfcStatsLog;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public final class NfcCardEmulationOccurredTest {

    private static final String TAG = NfcCardEmulationOccurredTest.class.getSimpleName();
    private boolean mNfcSupported;

    private MockitoSession mStaticMockSession;
    private HostEmulationManager mHostEmulation;
    private RegisteredAidCache mockAidCache;
    private Context mockContext;
    private PackageManager packageManager;
    private final TestLooper mTestLooper = new TestLooper();

    private static final int UID_1 = 111;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(NfcStatsLog.class)
                .mockStatic(Flags.class)
                .mockStatic(NfcService.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        packageManager = context.getPackageManager();
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            mNfcSupported = false;
            return;
        }
        mNfcSupported = true;

        initMockContext(context);

        mockAidCache = Mockito.mock(RegisteredAidCache.class);
        ApduServiceInfo apduServiceInfo = Mockito.mock(ApduServiceInfo.class);
        when(apduServiceInfo.requiresUnlock()).thenReturn(false);
        when(apduServiceInfo.requiresScreenOn()).thenReturn(false);
        when(apduServiceInfo.isOnHost()).thenReturn(true);
        when(apduServiceInfo.getComponent()).thenReturn(new ComponentName("packageName", "name"));
        when(apduServiceInfo.getUid()).thenReturn(UID_1);
        AidResolveInfo aidResolveInfo = mockAidCache.new AidResolveInfo();
        aidResolveInfo.defaultService = apduServiceInfo;
        aidResolveInfo.category = CardEmulation.CATEGORY_OTHER;
        aidResolveInfo.services = new ArrayList<ApduServiceInfo>();
        aidResolveInfo.services.add(apduServiceInfo);
        when(mockAidCache.resolveAid(anyString())).thenReturn(aidResolveInfo);
        when(NfcService.getInstance()).thenReturn(mock(NfcService.class));
        when(Flags.statsdCeEventsFlag()).thenReturn(false);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mHostEmulation = new HostEmulationManager(
                        mockContext, mTestLooper.getLooper(), mockAidCache));
        assertNotNull(mHostEmulation);
        mHostEmulation.onHostEmulationActivated();
    }

    private void initMockContext(Context context) {
        mockContext = new ContextWrapper(context) {
            @Override
            public void sendBroadcastAsUser(Intent intent, UserHandle user) {
                Log.i(TAG, "[Mock] sendBroadcastAsUser");
            }

            @Override
            public PackageManager getPackageManager() {
                Log.i(TAG, "[Mock] getPackageManager");
                return packageManager;
            }

            public boolean bindServiceAsUser(
                    @NonNull @RequiresPermission Intent service,
                    @NonNull ServiceConnection conn, int flags,
                    @NonNull UserHandle user) {
                Log.i(TAG, "[Mock] bindServiceAsUser");
                return true;
            }

            public void unbindService(@NonNull ServiceConnection conn){
                Log.i(TAG, "[Mock] unbindService");
            }
        };
    }

    @After
    public void tearDown() {
        mHostEmulation.onHostEmulationDeactivated();
        mStaticMockSession.finishMocking();
    }

    // TODO: Remove after aosp/2902507
    // @RequiresFlagsDisabled(Flags.FLAG_STATSD_CE_EVENTS_FLAG)
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
                NfcStatsLog.NFC_CARDEMULATION_OCCURRED,
                NfcStatsLog.NFC_CARDEMULATION_OCCURRED__CATEGORY__HCE_OTHER,
                "HCE",
                UID_1));
    }

    @Test
    public void testOnPollingLoopDetectedSTATE_XFER() {
        if (!mNfcSupported) return;

        ComponentName componentName = mock(ComponentName.class);
        when(componentName.getPackageName()).thenReturn("com.android.nfc");
        IBinder iBinder = new Binder();
        ServiceConnection serviceConnection = mHostEmulation.getServiceConnection();
        serviceConnection.onServiceConnected(componentName, iBinder);
        int state = mHostEmulation.getState();
        Log.d(TAG, "testOnPollingLoopDetectedSTATE_XFER() - state = "+state);

        byte[] aidBytes = new byte[] {
                0x00, (byte)0xA4, 0x04, 0x00,  // command
                0x08,  // data length
                (byte)0xA0, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00,
                0x00,  // card manager AID
                0x00  // trailer
        };
        mHostEmulation.onHostEmulationData(aidBytes);
        state = mHostEmulation.getState();
        assertEquals(state, STATE_W4_SERVICE);
    }

    @Test
    public void testOnOffHostAidSelected() {
        if (!mNfcSupported) return;

        mHostEmulation.onOffHostAidSelected();
        int state = mHostEmulation.getState();
        assertEquals(state, STATE_W4_SELECT);
    }

    @Test
    public void testOnPollingLoopDetected() {
        if (!mNfcSupported) return;

        Bundle pollingFrame = mock(Bundle.class);
        ComponentName componentName = mock(ComponentName.class);
        when(componentName.getPackageName()).thenReturn("com.android.nfc");
        when(mockAidCache.getPreferredService()).thenReturn(componentName);
        mHostEmulation.onPollingLoopDetected(pollingFrame);
        Bundle resultBundle = mHostEmulation.mPendingPollingLoopFrames.get(0);
        Assert.assertEquals(pollingFrame, resultBundle);
    }

    @Test
    public void testOnPollingLoopDetectedServiceBound() {
        if (!mNfcSupported) return;

        Bundle pollingLoopTypeOnFrame = mock(Bundle.class);
        Bundle pollingLoopTypeOffFrame = mock(Bundle.class);
        when(pollingLoopTypeOnFrame.getChar(HostApduService.POLLING_LOOP_TYPE_KEY))
                .thenReturn(HostApduService.POLLING_LOOP_TYPE_ON);
        when(pollingLoopTypeOffFrame.getChar(HostApduService.POLLING_LOOP_TYPE_KEY))
                .thenReturn(HostApduService.POLLING_LOOP_TYPE_OFF);
        ComponentName componentName = mock(ComponentName.class);
        when(componentName.getPackageName()).thenReturn("com.android.nfc");
        when(mockAidCache.getPreferredService()).thenReturn(componentName);
        IBinder iBinder = new Binder();
        ServiceConnection serviceConnection = mHostEmulation.getServiceConnection();
        serviceConnection.onServiceConnected(componentName, iBinder);
        mHostEmulation.onPollingLoopDetected(pollingLoopTypeOnFrame);
        mHostEmulation.onPollingLoopDetected(pollingLoopTypeOnFrame);
        mHostEmulation.onPollingLoopDetected(pollingLoopTypeOffFrame);
        mHostEmulation.onPollingLoopDetected(pollingLoopTypeOffFrame);
        IBinder mActiveService = mHostEmulation.getMessenger();
        Assert.assertNotNull(mActiveService);
        Assert.assertEquals(iBinder, mActiveService);
    }

    @Test
    public void testOnHostEmulationActivated() {
        if (!mNfcSupported) return;

        mHostEmulation.onHostEmulationActivated();
        int value = mHostEmulation.getState();
        Assert.assertEquals(value, STATE_W4_SELECT);
    }

    @Test
    public void testOnPreferredPaymentServiceChanged() {
        if (!mNfcSupported) return;

        ComponentName componentName = mock(ComponentName.class);
        when(componentName.getPackageName()).thenReturn("com.android.nfc");
        int userId = 0;
        mHostEmulation.onPreferredPaymentServiceChanged(userId, componentName);
        mTestLooper.dispatchAll();
        ComponentName serviceName = mHostEmulation.getServiceName();
        assertNotNull(serviceName);
        assertEquals(componentName, serviceName);
    }

    @Test
    public void testOnPreferredForegroundServiceChanged() {
        if (!mNfcSupported) return;

        ComponentName componentName = mock(ComponentName.class);
        when(componentName.getPackageName()).thenReturn("com.android.nfc");
        int userId = 0;
        mHostEmulation.onPreferredForegroundServiceChanged(userId, componentName);
        Boolean isServiceBounded = mHostEmulation.isServiceBounded();
        assertNotNull(isServiceBounded);
        assertTrue(isServiceBounded);
    }
}