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
package com.android.nfc.cardemulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.nfc.cardemulation.PollingFrame;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Pair;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.NfcEventLog;
import com.android.nfc.NfcInjector;
import com.android.nfc.NfcService;
import com.android.nfc.NfcStatsLog;
import com.android.nfc.cardemulation.util.StatsdUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class HostEmulationManagerTest {

    private static final String WALLET_HOLDER_PACKAGE_NAME = "com.android.test.walletroleholder";
    private static final ComponentName WALLET_PAYMENT_SERVICE
            = new ComponentName(WALLET_HOLDER_PACKAGE_NAME,
            "com.android.test.walletroleholder.WalletRoleHolderApduService");
    private static final ComponentName ANOTHER_WALLET_SERVICE
            = new ComponentName(WALLET_HOLDER_PACKAGE_NAME,
            "com.android.test.walletroleholder.XRoleHolderApduService");
    private static final int USER_ID = 0;
    private static final UserHandle USER_HANDLE = UserHandle.of(USER_ID);
    private static final String PL_FILTER = "66696C746572";
    private static final Pattern PL_PATTERN = Pattern.compile("66696C*46572");
    private static final List<String> POLLING_LOOP_FILTER = List.of(PL_FILTER);
    private static final List<Pattern> POLLING_LOOP_PATTEN_FILTER
            = List.of(PL_PATTERN);
    private static final String MOCK_AID = "A000000476416E64726F6964484340";

    @Mock
    private Context mContext;
    @Mock
    private RegisteredAidCache mRegisteredAidCache;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private KeyguardManager mKeyguardManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private NfcAdapter mNfcAdapter;
    @Mock
    private Messenger mMessenger;
    @Mock
    private NfcService mNfcService;
    @Mock
    private NfcInjector mNfcInjector;
    @Mock
    private NfcEventLog mNfcEventLog;
    @Mock
    private StatsdUtils mStatsUtils;
    @Captor
    private ArgumentCaptor<Intent> mIntentArgumentCaptor;
    @Captor
    private ArgumentCaptor<ServiceConnection> mServiceConnectionArgumentCaptor;
    @Captor
    private ArgumentCaptor<List<ApduServiceInfo>> mServiceListArgumentCaptor;
    @Captor
    private ArgumentCaptor<Message> mMessageArgumentCaptor;

    private MockitoSession mStaticMockSession;
    private TestableLooper mTestableLooper;
    private HostEmulationManager mHostEmulationManager;

    @Before
    public void setUp() {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(com.android.nfc.flags.Flags.class)
                .mockStatic(NfcStatsLog.class)
                .mockStatic(UserHandle.class)
                .mockStatic(NfcAdapter.class)
                .mockStatic(NfcService.class)
                .mockStatic(NfcInjector.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        when(NfcAdapter.getDefaultAdapter(mContext)).thenReturn(mNfcAdapter);
        when(UserHandle.getUserHandleForUid(eq(USER_ID))).thenReturn(USER_HANDLE);
        when(UserHandle.of(eq(USER_ID))).thenReturn(USER_HANDLE);
        when(NfcService.getInstance()).thenReturn(mNfcService);
        when(NfcInjector.getInstance()).thenReturn(mNfcInjector);
        when(mNfcInjector.getNfcEventLog()).thenReturn(mNfcEventLog);
        when(com.android.nfc.flags.Flags.statsdCeEventsFlag()).thenReturn(true);
        when(mContext.getSystemService(eq(PowerManager.class))).thenReturn(mPowerManager);
        when(mContext.getSystemService(eq(KeyguardManager.class))).thenReturn(mKeyguardManager);
        when(mRegisteredAidCache.getPreferredPaymentService()).thenReturn(new Pair<>(null, null));
        mHostEmulationManager = new HostEmulationManager(mContext, mTestableLooper.getLooper(),
                mRegisteredAidCache, mStatsUtils);
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testConstructor() {
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnPreferredPaymentServiceChanged_nullService() {
        mHostEmulationManager.mPaymentServiceBound = true;

        mHostEmulationManager.onPreferredPaymentServiceChanged(USER_ID, null);
        mTestableLooper.processAllMessages();

        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).unbindService(eq(mHostEmulationManager.getPaymentConnection()));
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnPreferredPaymentServiceChanged_noPreviouslyBoundService() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);
        UserHandle userHandle = UserHandle.of(USER_ID);

        mHostEmulationManager.onPreferredPaymentServiceChanged(USER_ID, WALLET_PAYMENT_SERVICE);
        mTestableLooper.processAllMessages();

        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).bindServiceAsUser(mIntentArgumentCaptor.capture(),
                mServiceConnectionArgumentCaptor.capture(),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS),
                eq(userHandle));
        verifyNoMoreInteractions(mContext);
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(HostApduService.SERVICE_INTERFACE, intent.getAction());
        assertEquals(WALLET_PAYMENT_SERVICE, intent.getComponent());
        assertTrue(mHostEmulationManager.mPaymentServiceBound);
        assertEquals(WALLET_PAYMENT_SERVICE, mHostEmulationManager.mLastBoundPaymentServiceName);
        assertEquals(USER_ID, mHostEmulationManager.mPaymentServiceUserId);
    }

    @Test
    public void testOnPreferredPaymentServiceChanged_previouslyBoundService() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);
        UserHandle userHandle = UserHandle.of(USER_ID);
        mHostEmulationManager.mPaymentServiceBound = true;

        mHostEmulationManager.onPreferredPaymentServiceChanged(USER_ID, WALLET_PAYMENT_SERVICE);
        mTestableLooper.processAllMessages();

        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).unbindService(eq(mHostEmulationManager.getPaymentConnection()));
        verify(mContext).bindServiceAsUser(mIntentArgumentCaptor.capture(),
                mServiceConnectionArgumentCaptor.capture(),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS),
                eq(userHandle));
        verifyNoMoreInteractions(mContext);
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(HostApduService.SERVICE_INTERFACE, intent.getAction());
        assertEquals(WALLET_PAYMENT_SERVICE, intent.getComponent());
        assertTrue(mHostEmulationManager.mPaymentServiceBound);
        assertEquals(WALLET_PAYMENT_SERVICE, mHostEmulationManager.mLastBoundPaymentServiceName);
        assertEquals(USER_ID, mHostEmulationManager.mPaymentServiceUserId);
        assertNotNull(mServiceConnectionArgumentCaptor.getValue());
    }

    @Test
    public void testUpdatePollingLoopFilters() {
        ApduServiceInfo serviceWithFilter = mock(ApduServiceInfo.class);
        when(serviceWithFilter.getPollingLoopFilters()).thenReturn(POLLING_LOOP_FILTER);
        when(serviceWithFilter.getPollingLoopPatternFilters()).thenReturn(List.of());
        ApduServiceInfo serviceWithPatternFilter = mock(ApduServiceInfo.class);
        when(serviceWithPatternFilter.getPollingLoopFilters()).thenReturn(List.of());
        when(serviceWithPatternFilter.getPollingLoopPatternFilters())
                .thenReturn(POLLING_LOOP_PATTEN_FILTER);

        mHostEmulationManager.updatePollingLoopFilters(USER_ID, List.of(serviceWithFilter,
                serviceWithPatternFilter));

        Map<Integer, Map<String, List<ApduServiceInfo>>> pollingLoopFilters
                = mHostEmulationManager.getPollingLoopFilters();
        Map<Integer, Map<Pattern, List<ApduServiceInfo>>> pollingLoopPatternFilters
                = mHostEmulationManager.getPollingLoopPatternFilters();
        assertTrue(pollingLoopFilters.containsKey(USER_ID));
        assertTrue(pollingLoopPatternFilters.containsKey(USER_ID));
        Map<String, List<ApduServiceInfo>> filtersForUser = pollingLoopFilters.get(USER_ID);
        Map<Pattern, List<ApduServiceInfo>> patternFiltersForUser = pollingLoopPatternFilters
                .get(USER_ID);
        assertTrue(filtersForUser.containsKey(PL_FILTER));
        assertTrue(patternFiltersForUser.containsKey(PL_PATTERN));
        assertTrue(filtersForUser.get(PL_FILTER).contains(serviceWithFilter));
        assertTrue(patternFiltersForUser.get(PL_PATTERN).contains(serviceWithPatternFilter));
    }

    @Test
    public void testOnPollingLoopDetected_activeServiceAlreadyBound_overlappingServices()
            throws PackageManager.NameNotFoundException, RemoteException {
        ApduServiceInfo serviceWithFilter = mock(ApduServiceInfo.class);
        when(serviceWithFilter.getPollingLoopFilters()).thenReturn(POLLING_LOOP_FILTER);
        when(serviceWithFilter.getPollingLoopPatternFilters()).thenReturn(List.of());
        when(serviceWithFilter.getShouldAutoTransact(anyString())).thenReturn(true);
        when(serviceWithFilter.getComponent()).thenReturn(WALLET_PAYMENT_SERVICE);
        when(serviceWithFilter.getUid()).thenReturn(USER_ID);
        ApduServiceInfo overlappingServiceWithFilter = mock(ApduServiceInfo.class);
        when(overlappingServiceWithFilter.getPollingLoopFilters()).thenReturn(POLLING_LOOP_FILTER);
        when(overlappingServiceWithFilter.getPollingLoopPatternFilters()).thenReturn(List.of());
        ApduServiceInfo serviceWithPatternFilter = mock(ApduServiceInfo.class);
        when(serviceWithPatternFilter.getPollingLoopFilters()).thenReturn(List.of());
        when(serviceWithPatternFilter.getPollingLoopPatternFilters())
                .thenReturn(POLLING_LOOP_PATTEN_FILTER);
        when(mRegisteredAidCache.resolvePollingLoopFilterConflict(anyList()))
                .thenReturn(serviceWithFilter);
        mHostEmulationManager.updatePollingLoopFilters(USER_ID, List.of(serviceWithFilter,
                serviceWithPatternFilter, overlappingServiceWithFilter));
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new Pair<>(USER_ID, WALLET_PAYMENT_SERVICE));
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = USER_ID;
        mHostEmulationManager.mPaymentServiceName = WALLET_PAYMENT_SERVICE;
        when(mPackageManager.getApplicationInfo(eq(WALLET_HOLDER_PACKAGE_NAME), eq(0)))
                .thenReturn(applicationInfo);
        String data = "filter";
        PollingFrame frame1 = new PollingFrame(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                data.getBytes(), 0, 0, false);
        PollingFrame frame2 = new PollingFrame(PollingFrame.POLLING_LOOP_TYPE_OFF,
                null, 0, 0, false);

        mHostEmulationManager.mActiveService = mMessenger;

        mHostEmulationManager.onPollingLoopDetected(List.of(frame1, frame2));
        mTestableLooper.processAllMessages();

        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mRegisteredAidCache)
                .resolvePollingLoopFilterConflict(mServiceListArgumentCaptor.capture());
        assertTrue(mServiceListArgumentCaptor.getValue().contains(serviceWithFilter));
        assertTrue(mServiceListArgumentCaptor.getValue().contains(overlappingServiceWithFilter));
        verify(mNfcAdapter).setObserveModeEnabled(eq(false));
        assertTrue(mHostEmulationManager.mEnableObserveModeAfterTransaction);
        assertTrue(frame1.getTriggeredAutoTransact());
        assertEquals(HostEmulationManager.STATE_POLLING_LOOP, mHostEmulationManager.mState);
    }

    @Test
    public void testOnPollingLoopDetected_paymentServiceAlreadyBound_4Frames()
            throws PackageManager.NameNotFoundException, RemoteException {
        ApduServiceInfo serviceWithFilter = mock(ApduServiceInfo.class);
        when(serviceWithFilter.getPollingLoopFilters()).thenReturn(POLLING_LOOP_FILTER);
        when(serviceWithFilter.getPollingLoopPatternFilters()).thenReturn(List.of());
        when(serviceWithFilter.getShouldAutoTransact(anyString())).thenReturn(true);
        when(serviceWithFilter.getComponent()).thenReturn(WALLET_PAYMENT_SERVICE);
        when(serviceWithFilter.getUid()).thenReturn(USER_ID);
        mHostEmulationManager.updatePollingLoopFilters(USER_ID, List.of(serviceWithFilter));
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new Pair<>(USER_ID, WALLET_PAYMENT_SERVICE));
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = USER_ID;
        when(mPackageManager.getApplicationInfo(eq(WALLET_HOLDER_PACKAGE_NAME), eq(0)))
                .thenReturn(applicationInfo);
        PollingFrame frame1 = new PollingFrame(PollingFrame.POLLING_LOOP_TYPE_ON,
                null, 0, 0, false);
        PollingFrame frame2 = new PollingFrame(PollingFrame.POLLING_LOOP_TYPE_ON,
                null, 0, 0, false);
        PollingFrame frame3 = new PollingFrame(PollingFrame.POLLING_LOOP_TYPE_OFF,
                null, 0, 0, false);
        PollingFrame frame4 = new PollingFrame(PollingFrame.POLLING_LOOP_TYPE_OFF,
                null, 0, 0, false);
        mHostEmulationManager.mPaymentService = mMessenger;
        mHostEmulationManager.mPaymentServiceName = WALLET_PAYMENT_SERVICE;

        mHostEmulationManager.onPollingLoopDetected(List.of(frame1, frame2, frame3, frame4));

        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mMessenger).send(mMessageArgumentCaptor.capture());
        Message message = mMessageArgumentCaptor.getValue();
        Bundle bundle = message.getData();
        assertEquals(HostApduService.MSG_POLLING_LOOP, message.what);
        assertTrue(bundle.containsKey(HostApduService.KEY_POLLING_LOOP_FRAMES_BUNDLE));
        ArrayList<PollingFrame> sentFrames = bundle
                .getParcelableArrayList(HostApduService.KEY_POLLING_LOOP_FRAMES_BUNDLE);
        assertTrue(sentFrames.contains(frame1));
        assertTrue(sentFrames.contains(frame2));
        assertTrue(sentFrames.contains(frame3));
        assertTrue(sentFrames.contains(frame4));
        assertNull(mHostEmulationManager.mPendingPollingLoopFrames);
    }

    @Test
    public void testOnPreferredForegroundServiceChanged_noPreviouslyBoundService() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);
        UserHandle userHandle = UserHandle.of(USER_ID);

        mHostEmulationManager.onPreferredForegroundServiceChanged(USER_ID, WALLET_PAYMENT_SERVICE);

        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).bindServiceAsUser(mIntentArgumentCaptor.capture(),
                mServiceConnectionArgumentCaptor.capture(),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS),
                eq(userHandle));
        verifyNoMoreInteractions(mContext);
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(HostApduService.SERVICE_INTERFACE, intent.getAction());
        assertEquals(WALLET_PAYMENT_SERVICE, intent.getComponent());
        assertTrue(mHostEmulationManager.mServiceBound);
        assertEquals(USER_ID, mHostEmulationManager.mServiceUserId);
    }

    @Test
    public void testOnPreferredForegroundServiceChanged_nullService() {
        mHostEmulationManager.mServiceBound = true;

        mHostEmulationManager.onPreferredForegroundServiceChanged(USER_ID, null);

        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).unbindService(eq(mHostEmulationManager.getServiceConnection()));
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnPreferredForegroundServiceChanged_nullService_previouslyBoundService() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);
        UserHandle userHandle = UserHandle.of(USER_ID);
        mHostEmulationManager.mServiceBound = true;

        mHostEmulationManager.onPreferredForegroundServiceChanged(USER_ID, WALLET_PAYMENT_SERVICE);

        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).unbindService(eq(mHostEmulationManager.getServiceConnection()));
        verify(mContext).bindServiceAsUser(mIntentArgumentCaptor.capture(),
                mServiceConnectionArgumentCaptor.capture(),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS),
                eq(userHandle));
        verifyNoMoreInteractions(mContext);
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(HostApduService.SERVICE_INTERFACE, intent.getAction());
        assertEquals(WALLET_PAYMENT_SERVICE, intent.getComponent());
        assertTrue(mHostEmulationManager.mServiceBound);
        assertEquals(USER_ID, mHostEmulationManager.mServiceUserId);
        assertNotNull(mServiceConnectionArgumentCaptor.getValue());
    }

    @Test
    public void testOnFieldChangeDetected_fieldOff_returnToIdle() {
        mHostEmulationManager.mState = HostEmulationManager.STATE_XFER;

        // Should not change state immediately
        mHostEmulationManager.onFieldChangeDetected(false);
        assertEquals(HostEmulationManager.STATE_XFER, mHostEmulationManager.getState());

        mTestableLooper.moveTimeForward(5000);
        mTestableLooper.processAllMessages();
        assertEquals(HostEmulationManager.STATE_IDLE, mHostEmulationManager.getState());
    }

    @Test
    public void testOnPollingLoopDetected_fieldOff_returnToIdle() {
        PollingFrame frame1 = new PollingFrame(PollingFrame.POLLING_LOOP_TYPE_ON,
                null, 0, 0, false);
        PollingFrame frame2 = new PollingFrame(PollingFrame.POLLING_LOOP_TYPE_OFF,
                null, 0, 0, false);
        mHostEmulationManager.onPollingLoopDetected(List.of(frame1, frame2));
        assertEquals(HostEmulationManager.STATE_POLLING_LOOP, mHostEmulationManager.getState());

        mTestableLooper.moveTimeForward(5000);
        mTestableLooper.processAllMessages();
        assertEquals(HostEmulationManager.STATE_IDLE, mHostEmulationManager.getState());
    }

    @Test
    public void testOnHostEmulationActivated() {
        mHostEmulationManager.onHostEmulationActivated();

        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).sendBroadcastAsUser(mIntentArgumentCaptor.capture(),
                eq(UserHandle.ALL));
        verifyNoMoreInteractions(mContext);
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(TapAgainDialog.ACTION_CLOSE, intent.getAction());
        assertEquals(HostEmulationManager.NFC_PACKAGE, intent.getPackage());
        assertEquals(HostEmulationManager.STATE_W4_SELECT, mHostEmulationManager.getState());
    }

    @Test
    public void testOnHostEmulationActivated_doesNotReturnToIdle() {
        mHostEmulationManager.onFieldChangeDetected(false);
        mHostEmulationManager.onHostEmulationActivated();

        mTestableLooper.moveTimeForward(5000);
        mTestableLooper.processAllMessages();
        assertEquals(HostEmulationManager.STATE_W4_SELECT, mHostEmulationManager.getState());
    }

    @Test
    public void testOnHostEmulationData_stateIdle() {
        byte[] emptyData = new byte[1];
        mHostEmulationManager.mState = HostEmulationManager.STATE_IDLE;

        mHostEmulationManager.onHostEmulationData(emptyData);

        verifyZeroInteractions(mNfcService);
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateW4Deactivate() {
        byte[] emptyData = new byte[1];
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_DEACTIVATE;

        mHostEmulationManager.onHostEmulationData(emptyData);

        verifyZeroInteractions(mNfcService);
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateW4Select_hceAid() {
        byte[] hceAidData = createSelectAidData(HostEmulationManager.ANDROID_HCE_AID);
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;

        mHostEmulationManager.onHostEmulationData(hceAidData);

        verify(mNfcService).sendData(eq(HostEmulationManager.ANDROID_HCE_RESPONSE));
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateW4Select_nullResolveInfo() {
        byte[] mockAidData = createSelectAidData(MOCK_AID);
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;
        when(mRegisteredAidCache.resolveAid(eq(MOCK_AID))).thenReturn(null);

        mHostEmulationManager.onHostEmulationData(mockAidData);

        verify(mRegisteredAidCache).resolveAid(eq(MOCK_AID));
        verify(mNfcService).sendData(eq(HostEmulationManager.AID_NOT_FOUND));
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateW4Select_emptyResolveInfoServices() {
        byte[] mockAidData = createSelectAidData(MOCK_AID);
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mRegisteredAidCache.new AidResolveInfo();
        aidResolveInfo.services = new ArrayList<>();
        when(mRegisteredAidCache.resolveAid(eq(MOCK_AID))).thenReturn(aidResolveInfo);

        mHostEmulationManager.onHostEmulationData(mockAidData);

        verify(mRegisteredAidCache).resolveAid(eq(MOCK_AID));
        verify(mNfcService).sendData(eq(HostEmulationManager.AID_NOT_FOUND));
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateW4Select_defaultServiceExists_requiresUnlock() {
        byte[] mockAidData = createSelectAidData(MOCK_AID);
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mRegisteredAidCache.new AidResolveInfo();
        aidResolveInfo.services = new ArrayList<>();
        aidResolveInfo.services.add(apduServiceInfo);
        aidResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        when(apduServiceInfo.requiresUnlock()).thenReturn(true);
        when(apduServiceInfo.getUid()).thenReturn(USER_ID);
        when(mNfcService.isSecureNfcEnabled()).thenReturn(false);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        aidResolveInfo.defaultService = apduServiceInfo;
        when(mRegisteredAidCache.resolveAid(eq(MOCK_AID))).thenReturn(aidResolveInfo);

        mHostEmulationManager.onHostEmulationData(mockAidData);

        verify(apduServiceInfo, times(2)).getUid();
        verify(mStatsUtils).setCardEmulationEventCategory(eq(CardEmulation.CATEGORY_PAYMENT));
        verify(mStatsUtils).setCardEmulationEventUid(eq(USER_ID));
        verify(mStatsUtils).logCardEmulationWrongSettingEvent();
        verify(mRegisteredAidCache).resolveAid(eq(MOCK_AID));
        verify(mNfcService).sendRequireUnlockIntent();
        verify(mNfcService).sendData(eq(HostEmulationManager.AID_NOT_FOUND));
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyTapAgainLaunched(apduServiceInfo, CardEmulation.CATEGORY_PAYMENT);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateW4Select_defaultServiceExists_secureNfcEnabled() {
        byte[] mockAidData = createSelectAidData(MOCK_AID);
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mRegisteredAidCache.new AidResolveInfo();
        aidResolveInfo.services = new ArrayList<>();
        aidResolveInfo.services.add(apduServiceInfo);
        aidResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        when(apduServiceInfo.requiresUnlock()).thenReturn(false);
        when(mNfcService.isSecureNfcEnabled()).thenReturn(true);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        aidResolveInfo.defaultService = apduServiceInfo;
        when(mRegisteredAidCache.resolveAid(eq(MOCK_AID))).thenReturn(aidResolveInfo);

        mHostEmulationManager.onHostEmulationData(mockAidData);

        verify(apduServiceInfo, times(2)).getUid();
        verify(mStatsUtils).setCardEmulationEventCategory(eq(CardEmulation.CATEGORY_PAYMENT));
        verify(mStatsUtils).setCardEmulationEventUid(eq(USER_ID));
        verify(mStatsUtils).logCardEmulationWrongSettingEvent();
        verify(mRegisteredAidCache).resolveAid(eq(MOCK_AID));
        verify(mNfcService).sendRequireUnlockIntent();
        verify(mNfcService).sendData(eq(HostEmulationManager.AID_NOT_FOUND));
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyTapAgainLaunched(apduServiceInfo, CardEmulation.CATEGORY_PAYMENT);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateW4Select_defaultServiceExists_requiresScreenOn() {
        byte[] mockAidData = createSelectAidData(MOCK_AID);
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mRegisteredAidCache.new AidResolveInfo();
        aidResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        aidResolveInfo.services = new ArrayList<>();
        aidResolveInfo.services.add(apduServiceInfo);
        when(apduServiceInfo.requiresUnlock()).thenReturn(false);
        when(apduServiceInfo.requiresScreenOn()).thenReturn(true);
        when(mNfcService.isSecureNfcEnabled()).thenReturn(false);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(false);
        aidResolveInfo.defaultService = apduServiceInfo;
        when(mRegisteredAidCache.resolveAid(eq(MOCK_AID))).thenReturn(aidResolveInfo);

        mHostEmulationManager.onHostEmulationData(mockAidData);

        verify(apduServiceInfo).getUid();
        verify(mStatsUtils).setCardEmulationEventCategory(eq(CardEmulation.CATEGORY_PAYMENT));
        verify(mStatsUtils).setCardEmulationEventUid(eq(USER_ID));
        verify(mStatsUtils).logCardEmulationWrongSettingEvent();
        verify(mRegisteredAidCache).resolveAid(eq(MOCK_AID));
        verify(mNfcService).sendData(eq(HostEmulationManager.AID_NOT_FOUND));
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateW4Select_defaultServiceExists_notOnHost() {
        byte[] mockAidData = createSelectAidData(MOCK_AID);
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mRegisteredAidCache.new AidResolveInfo();
        aidResolveInfo.services = new ArrayList<>();
        aidResolveInfo.services.add(apduServiceInfo);
        aidResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        when(apduServiceInfo.requiresUnlock()).thenReturn(false);
        when(apduServiceInfo.requiresScreenOn()).thenReturn(false);
        when(apduServiceInfo.isOnHost()).thenReturn(false);
        when(mNfcService.isSecureNfcEnabled()).thenReturn(false);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        aidResolveInfo.defaultService = apduServiceInfo;
        when(mRegisteredAidCache.resolveAid(eq(MOCK_AID))).thenReturn(aidResolveInfo);

        mHostEmulationManager.onHostEmulationData(mockAidData);

        verify(apduServiceInfo).getUid();
        verify(mStatsUtils).setCardEmulationEventCategory(eq(CardEmulation.CATEGORY_PAYMENT));
        verify(mStatsUtils).setCardEmulationEventUid(eq(USER_ID));
        verify(mStatsUtils).logCardEmulationNoRoutingEvent();
        verify(mRegisteredAidCache).resolveAid(eq(MOCK_AID));
        verify(mNfcService).sendData(eq(HostEmulationManager.AID_NOT_FOUND));
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateW4Select_noDefaultService_noActiveService() {
        byte[] mockAidData = createSelectAidData(MOCK_AID);
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mRegisteredAidCache.new AidResolveInfo();
        aidResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        aidResolveInfo.services = new ArrayList<>();
        aidResolveInfo.services.add(apduServiceInfo);
        when(mRegisteredAidCache.resolveAid(eq(MOCK_AID))).thenReturn(aidResolveInfo);
        when(apduServiceInfo.getComponent()).thenReturn(WALLET_PAYMENT_SERVICE);

        mHostEmulationManager.onHostEmulationData(mockAidData);

        ExtendedMockito.verify(() -> {
            NfcStatsLog.write(NfcStatsLog.NFC_AID_CONFLICT_OCCURRED, MOCK_AID);
        });
        verify(mStatsUtils).setCardEmulationEventCategory(eq(CardEmulation.CATEGORY_OTHER));
        verify(mStatsUtils).logCardEmulationWrongSettingEvent();
        assertEquals(HostEmulationManager.STATE_W4_DEACTIVATE, mHostEmulationManager.getState());
        verify(mRegisteredAidCache).resolveAid(eq(MOCK_AID));
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyResolverLaunched((ArrayList)aidResolveInfo.services, null,
                CardEmulation.CATEGORY_PAYMENT);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateW4Select_noDefaultService_matchingActiveService()
            throws RemoteException {
        byte[] mockAidData = createSelectAidData(MOCK_AID);
        IBinder binder = mock(IBinder.class);
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mRegisteredAidCache.new AidResolveInfo();
        aidResolveInfo.services = new ArrayList<>();
        aidResolveInfo.services.add(apduServiceInfo);
        aidResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        when(apduServiceInfo.requiresUnlock()).thenReturn(false);
        when(apduServiceInfo.requiresScreenOn()).thenReturn(false);
        when(apduServiceInfo.isOnHost()).thenReturn(false);
        when(apduServiceInfo.getComponent()).thenReturn(WALLET_PAYMENT_SERVICE);
        when(mNfcService.isSecureNfcEnabled()).thenReturn(false);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(false);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mRegisteredAidCache.resolveAid(eq(MOCK_AID))).thenReturn(aidResolveInfo);
        mHostEmulationManager.mActiveServiceName = WALLET_PAYMENT_SERVICE;
        mHostEmulationManager.mActiveService = mMessenger;
        when(mMessenger.getBinder()).thenReturn(binder);
        mHostEmulationManager.mPaymentServiceBound = true;
        mHostEmulationManager.mPaymentServiceName = WALLET_PAYMENT_SERVICE;
        mHostEmulationManager.mPaymentService = mMessenger;

        mHostEmulationManager.onHostEmulationData(mockAidData);

        assertEquals(HostEmulationManager.STATE_XFER, mHostEmulationManager.getState());
        verify(apduServiceInfo).getUid();
        verify(mStatsUtils).setCardEmulationEventCategory(eq(CardEmulation.CATEGORY_PAYMENT));
        verify(mStatsUtils).setCardEmulationEventUid(eq(USER_ID));
        verify(mStatsUtils).notifyCardEmulationEventWaitingForResponse();
        verify(mRegisteredAidCache).resolveAid(eq(MOCK_AID));
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyNoMoreInteractions(mContext);
        verify(mMessenger).send(mMessageArgumentCaptor.capture());
        Message message = mMessageArgumentCaptor.getValue();
        Bundle bundle = message.getData();
        assertEquals(HostApduService.MSG_COMMAND_APDU, message.what);
        assertTrue(bundle.containsKey(HostEmulationManager.DATA_KEY));
        assertEquals(mockAidData, bundle.getByteArray(HostEmulationManager.DATA_KEY));
        assertEquals(mHostEmulationManager.getLocalMessenger(), message.replyTo);
    }

    @Test
    public void testOnHostEmulationData_stateW4Select_noDefaultService_noBoundActiveService() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);
        byte[] mockAidData = createSelectAidData(MOCK_AID);
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mRegisteredAidCache.new AidResolveInfo();
        aidResolveInfo.services = new ArrayList<>();
        aidResolveInfo.services.add(apduServiceInfo);
        aidResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        when(apduServiceInfo.requiresUnlock()).thenReturn(false);
        when(apduServiceInfo.requiresScreenOn()).thenReturn(false);
        when(apduServiceInfo.isOnHost()).thenReturn(false);
        when(apduServiceInfo.getComponent()).thenReturn(WALLET_PAYMENT_SERVICE);
        when(apduServiceInfo.getUid()).thenReturn(USER_ID);
        when(mNfcService.isSecureNfcEnabled()).thenReturn(false);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(false);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mRegisteredAidCache.resolveAid(eq(MOCK_AID))).thenReturn(aidResolveInfo);
        mHostEmulationManager.mActiveServiceName = WALLET_PAYMENT_SERVICE;
        mHostEmulationManager.mPaymentServiceBound = false;
        mHostEmulationManager.mServiceBound = false;

        mHostEmulationManager.onHostEmulationData(mockAidData);

        assertEquals(HostEmulationManager.STATE_W4_SERVICE, mHostEmulationManager.getState());
        assertEquals(mockAidData, mHostEmulationManager.mSelectApdu);
        verify(apduServiceInfo).getUid();
        verify(mStatsUtils).setCardEmulationEventCategory(eq(CardEmulation.CATEGORY_PAYMENT));
        verify(mStatsUtils).setCardEmulationEventUid(eq(USER_ID));
        verify(mStatsUtils).notifyCardEmulationEventWaitingForResponse();
        verify(mRegisteredAidCache).resolveAid(eq(MOCK_AID));
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).bindServiceAsUser(mIntentArgumentCaptor.capture(),
                mServiceConnectionArgumentCaptor.capture(),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS),
                eq(USER_HANDLE));
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(HostApduService.SERVICE_INTERFACE, intent.getAction());
        assertEquals(WALLET_PAYMENT_SERVICE, intent.getComponent());
        assertEquals(mHostEmulationManager.getServiceConnection(),
                mServiceConnectionArgumentCaptor.getValue());
        assertTrue(mHostEmulationManager.mServiceBound);
        assertEquals(USER_ID, mHostEmulationManager.mServiceUserId);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateW4Select_noSelectAid() {
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;
        mHostEmulationManager.mPaymentServiceBound = true;
        mHostEmulationManager.mPaymentServiceName = WALLET_PAYMENT_SERVICE;
        mHostEmulationManager.mPaymentService = mMessenger;

        mHostEmulationManager.onHostEmulationData(null);

        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mNfcService).sendData(eq(HostEmulationManager.UNKNOWN_ERROR));
        verifyNoMoreInteractions(mNfcService);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateW4Service() {
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SERVICE;

        mHostEmulationManager.onHostEmulationData(null);

        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyNoMoreInteractions(mNfcService);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateXfer_nullAid_activeService() throws RemoteException {
        byte[] data = new byte[3];
        mHostEmulationManager.mState = HostEmulationManager.STATE_XFER;
        mHostEmulationManager.mActiveServiceName = WALLET_PAYMENT_SERVICE;
        mHostEmulationManager.mActiveService = mMessenger;

        mHostEmulationManager.onHostEmulationData(data);

        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mMessenger).send(mMessageArgumentCaptor.capture());
        Message message = mMessageArgumentCaptor.getValue();
        Bundle bundle = message.getData();
        assertEquals(HostApduService.MSG_COMMAND_APDU, message.what);
        assertTrue(bundle.containsKey(HostEmulationManager.DATA_KEY));
        assertEquals(data, bundle.getByteArray(HostEmulationManager.DATA_KEY));
        assertEquals(mHostEmulationManager.getLocalMessenger(), message.replyTo);
        verifyNoMoreInteractions(mNfcService);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateXfer_selectAid_activeService() throws RemoteException {
        byte[] mockAidData = createSelectAidData(MOCK_AID);
        IBinder binder = mock(IBinder.class);
        mHostEmulationManager.mState = HostEmulationManager.STATE_XFER;
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mRegisteredAidCache.new AidResolveInfo();
        aidResolveInfo.services = new ArrayList<>();
        aidResolveInfo.services.add(apduServiceInfo);
        aidResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        when(apduServiceInfo.requiresUnlock()).thenReturn(false);
        when(apduServiceInfo.requiresScreenOn()).thenReturn(false);
        when(apduServiceInfo.isOnHost()).thenReturn(false);
        when(apduServiceInfo.getComponent()).thenReturn(WALLET_PAYMENT_SERVICE);
        when(mNfcService.isSecureNfcEnabled()).thenReturn(false);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(false);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mRegisteredAidCache.resolveAid(eq(MOCK_AID))).thenReturn(aidResolveInfo);
        mHostEmulationManager.mActiveServiceName = WALLET_PAYMENT_SERVICE;
        mHostEmulationManager.mActiveService = mMessenger;
        when(mMessenger.getBinder()).thenReturn(binder);
        mHostEmulationManager.mPaymentServiceBound = true;
        mHostEmulationManager.mPaymentServiceName = WALLET_PAYMENT_SERVICE;
        mHostEmulationManager.mPaymentService = mMessenger;

        mHostEmulationManager.onHostEmulationData(mockAidData);

        assertEquals(HostEmulationManager.STATE_XFER, mHostEmulationManager.getState());
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mMessenger).send(mMessageArgumentCaptor.capture());
        Message message = mMessageArgumentCaptor.getValue();
        Bundle bundle = message.getData();
        assertEquals(HostApduService.MSG_COMMAND_APDU, message.what);
        assertTrue(bundle.containsKey(HostEmulationManager.DATA_KEY));
        assertEquals(mockAidData, bundle.getByteArray(HostEmulationManager.DATA_KEY));
        assertEquals(mHostEmulationManager.getLocalMessenger(), message.replyTo);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_stateXfer_selectAid_noActiveService() throws RemoteException {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);
        byte[] mockAidData = createSelectAidData(MOCK_AID);
        mHostEmulationManager.mState = HostEmulationManager.STATE_XFER;
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        RegisteredAidCache.AidResolveInfo aidResolveInfo = mRegisteredAidCache.new AidResolveInfo();
        aidResolveInfo.services = new ArrayList<>();
        aidResolveInfo.services.add(apduServiceInfo);
        aidResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        when(apduServiceInfo.requiresUnlock()).thenReturn(false);
        when(apduServiceInfo.requiresScreenOn()).thenReturn(false);
        when(apduServiceInfo.isOnHost()).thenReturn(false);
        when(apduServiceInfo.getComponent()).thenReturn(WALLET_PAYMENT_SERVICE);
        when(apduServiceInfo.getUid()).thenReturn(USER_ID);
        when(mNfcService.isSecureNfcEnabled()).thenReturn(false);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(false);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mRegisteredAidCache.resolveAid(eq(MOCK_AID))).thenReturn(aidResolveInfo);
        mHostEmulationManager.mActiveServiceName = WALLET_PAYMENT_SERVICE;
        mHostEmulationManager.mPaymentServiceBound = false;
        mHostEmulationManager.mServiceBound = false;

        mHostEmulationManager.onHostEmulationData(mockAidData);

        assertEquals(HostEmulationManager.STATE_W4_SERVICE, mHostEmulationManager.getState());
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).bindServiceAsUser(mIntentArgumentCaptor.capture(),
                mServiceConnectionArgumentCaptor.capture(),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS),
                eq(USER_HANDLE));
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(HostApduService.SERVICE_INTERFACE, intent.getAction());
        assertEquals(WALLET_PAYMENT_SERVICE, intent.getComponent());
        assertEquals(mHostEmulationManager.getServiceConnection(),
                mServiceConnectionArgumentCaptor.getValue());
        assertTrue(mHostEmulationManager.mServiceBound);
        assertEquals(USER_ID, mHostEmulationManager.mServiceUserId);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationData_doesNotReturnToIdle() {
        mHostEmulationManager.mState = HostEmulationManager.STATE_XFER;
        mHostEmulationManager.onFieldChangeDetected(false);

        byte[] emptyData = new byte[1];
        mHostEmulationManager.onHostEmulationData(emptyData);

        mTestableLooper.moveTimeForward(5000);
        mTestableLooper.processAllMessages();
        assertEquals(HostEmulationManager.STATE_XFER, mHostEmulationManager.getState());
    }

    @Test
    public void testOnHostEmulationDeactivated_activeService_enableObserveModeAfterTransaction()
            throws RemoteException {
        mHostEmulationManager.mActiveService = mMessenger;
        mHostEmulationManager.mServiceBound = true;
        mHostEmulationManager.mServiceUserId = USER_ID;
        mHostEmulationManager.mServiceName = WALLET_PAYMENT_SERVICE;
        mHostEmulationManager.mEnableObserveModeAfterTransaction = true;

        mHostEmulationManager.onHostEmulationDeactivated();
        mTestableLooper.processAllMessages();

        assertNull(mHostEmulationManager.mActiveService);
        assertNull(mHostEmulationManager.mActiveServiceName);
        assertNull(mHostEmulationManager.mServiceName);
        assertNull(mHostEmulationManager.mService);
        assertNull(mHostEmulationManager.mPendingPollingLoopFrames);
        assertEquals(Process.INVALID_UID, mHostEmulationManager.mActiveServiceUserId);
        assertEquals(Process.INVALID_UID, mHostEmulationManager.mServiceUserId);
        assertEquals(HostEmulationManager.STATE_IDLE, mHostEmulationManager.getState());
        assertFalse(mHostEmulationManager.mServiceBound);
        verify(mMessenger).send(mMessageArgumentCaptor.capture());
        Message message = mMessageArgumentCaptor.getValue();
        assertEquals(HostApduService.MSG_DEACTIVATED, message.what);
        assertEquals(HostApduService.DEACTIVATION_LINK_LOSS, message.arg1);
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).unbindService(mServiceConnectionArgumentCaptor.capture());
        assertEquals(mHostEmulationManager.getServiceConnection(),
                mServiceConnectionArgumentCaptor.getValue());
        verify(mStatsUtils).logCardEmulationDeactivatedEvent();

        mTestableLooper.moveTimeForward(5000);
        mTestableLooper.processAllMessages();
        verify(mNfcAdapter).setObserveModeEnabled(eq(true));
        assertFalse(mHostEmulationManager.mEnableObserveModeAfterTransaction);
        verifyNoMoreInteractions(mMessenger);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnHostEmulationDeactivated_noActiveService() throws RemoteException {
        mHostEmulationManager.mActiveService = null;
        mHostEmulationManager.mServiceBound = false;
        mHostEmulationManager.mServiceUserId = USER_ID;
        mHostEmulationManager.mEnableObserveModeAfterTransaction = false;

        mHostEmulationManager.onHostEmulationDeactivated();

        assertNull(mHostEmulationManager.mActiveService);
        assertNull(mHostEmulationManager.mActiveServiceName);
        assertNull(mHostEmulationManager.mServiceName);
        assertNull(mHostEmulationManager.mService);
        assertNull(mHostEmulationManager.mPendingPollingLoopFrames);
        assertEquals(Process.INVALID_UID, mHostEmulationManager.mActiveServiceUserId);
        assertEquals(Process.INVALID_UID, mHostEmulationManager.mServiceUserId);
        assertEquals(HostEmulationManager.STATE_IDLE, mHostEmulationManager.getState());
        assertFalse(mHostEmulationManager.mEnableObserveModeAfterTransaction);
        assertFalse(mHostEmulationManager.mServiceBound);
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verifyZeroInteractions(mMessenger);
        verifyNoMoreInteractions(mMessenger);
        verifyNoMoreInteractions(mContext);
        verify(mStatsUtils).logCardEmulationDeactivatedEvent();
    }

    @Test
    public void testOnOffHostAidSelected_noActiveService_stateXfer() {
        mHostEmulationManager.mActiveService = null;
        mHostEmulationManager.mServiceBound = false;
        mHostEmulationManager.mState = HostEmulationManager.STATE_XFER;

        mHostEmulationManager.onOffHostAidSelected();

        assertNull(mHostEmulationManager.mActiveService);
        assertNull(mHostEmulationManager.mActiveServiceName);
        assertEquals(Process.INVALID_UID, mHostEmulationManager.mActiveServiceUserId);
        assertFalse(mHostEmulationManager.mServiceBound);
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).sendBroadcastAsUser(mIntentArgumentCaptor.capture(), eq(UserHandle.ALL));
        assertEquals(HostEmulationManager.STATE_W4_SELECT, mHostEmulationManager.getState());
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(TapAgainDialog.ACTION_CLOSE, intent.getAction());
        assertEquals(HostEmulationManager.NFC_PACKAGE, intent.getPackage());
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnOffHostAidSelected_activeServiceBound_stateXfer() throws RemoteException {
        mHostEmulationManager.mActiveService = mMessenger;
        mHostEmulationManager.mServiceBound = true;
        mHostEmulationManager.mState = HostEmulationManager.STATE_XFER;

        mHostEmulationManager.onOffHostAidSelected();

        assertNull(mHostEmulationManager.mActiveService);
        assertNull(mHostEmulationManager.mActiveServiceName);
        assertEquals(Process.INVALID_UID, mHostEmulationManager.mActiveServiceUserId);
        assertFalse(mHostEmulationManager.mServiceBound);
        verify(mContext).unbindService(mServiceConnectionArgumentCaptor.capture());
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).sendBroadcastAsUser(mIntentArgumentCaptor.capture(), eq(UserHandle.ALL));
        assertEquals(HostEmulationManager.STATE_W4_SELECT, mHostEmulationManager.getState());
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(TapAgainDialog.ACTION_CLOSE, intent.getAction());
        assertEquals(HostEmulationManager.NFC_PACKAGE, intent.getPackage());
        verify(mMessenger).send(mMessageArgumentCaptor.capture());
        Message message = mMessageArgumentCaptor.getValue();
        assertEquals(HostApduService.MSG_DEACTIVATED, message.what);
        assertEquals(HostApduService.DEACTIVATION_DESELECTED, message.arg1);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testOnOffHostAidSelected_activeServiceBound_stateNonXfer() throws RemoteException {
        mHostEmulationManager.mActiveService = mMessenger;
        mHostEmulationManager.mServiceBound = true;
        mHostEmulationManager.mState = HostEmulationManager.STATE_IDLE;

        mHostEmulationManager.onOffHostAidSelected();

        assertNull(mHostEmulationManager.mActiveService);
        assertNull(mHostEmulationManager.mActiveServiceName);
        assertEquals(Process.INVALID_UID, mHostEmulationManager.mActiveServiceUserId);
        assertFalse(mHostEmulationManager.mServiceBound);
        verify(mContext).unbindService(mServiceConnectionArgumentCaptor.capture());
        verify(mContext).getSystemService(eq(PowerManager.class));
        verify(mContext).getSystemService(eq(KeyguardManager.class));
        verify(mContext).sendBroadcastAsUser(mIntentArgumentCaptor.capture(), eq(UserHandle.ALL));
        assertEquals(HostEmulationManager.STATE_W4_SELECT, mHostEmulationManager.getState());
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(TapAgainDialog.ACTION_CLOSE, intent.getAction());
        assertEquals(HostEmulationManager.NFC_PACKAGE, intent.getPackage());
        verifyZeroInteractions(mMessenger);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testServiceConnectionOnServiceConnected_stateSelectW4_selectApdu()
            throws RemoteException {
        IBinder service = mock(IBinder.class);
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;
        mHostEmulationManager.mSelectApdu = new byte[3];

        mHostEmulationManager.getServiceConnection().onServiceConnected(WALLET_PAYMENT_SERVICE,
                service);

        assertEquals(WALLET_PAYMENT_SERVICE, mHostEmulationManager.mServiceName);
        assertNotNull(mHostEmulationManager.mService);
        assertTrue(mHostEmulationManager.mServiceBound);
        verify(mStatsUtils).notifyCardEmulationEventServiceBound();
        assertEquals(HostEmulationManager.STATE_XFER, mHostEmulationManager.getState());
        assertNull(mHostEmulationManager.mSelectApdu);
        verify(service).transact(eq(1), any(), eq(null), eq(1));
    }

    @Test
    public void testServiceConnectionOnServiceConnected_stateSelectW4_pollingLoopFrames()
            throws RemoteException {
        IBinder service = mock(IBinder.class);
        mHostEmulationManager.mState = HostEmulationManager.STATE_W4_SELECT;
        mHostEmulationManager.mSelectApdu = null;
        mHostEmulationManager.mPollingFramesToSend = new HashMap();
        mHostEmulationManager.mPollingFramesToSend.put(WALLET_PAYMENT_SERVICE,
                new ArrayList<>(List.of()));

        mHostEmulationManager.getServiceConnection().onServiceConnected(WALLET_PAYMENT_SERVICE,
                service);

        assertEquals(WALLET_PAYMENT_SERVICE, mHostEmulationManager.mServiceName);
        assertNotNull(mHostEmulationManager.mService);
        assertTrue(mHostEmulationManager.mServiceBound);
        assertEquals(HostEmulationManager.STATE_W4_SELECT, mHostEmulationManager.getState());
        assertNull(mHostEmulationManager.mPollingFramesToSend.get(WALLET_PAYMENT_SERVICE));
        verify(service).transact(eq(1), any(), eq(null), eq(1));
    }

    @Test
    public void testServiceConnectionOnServiceConnected_stateIdle() {
        IBinder service = mock(IBinder.class);
        mHostEmulationManager.mState = HostEmulationManager.STATE_IDLE;

        mHostEmulationManager.getServiceConnection().onServiceConnected(WALLET_PAYMENT_SERVICE,
                service);

        verifyZeroInteractions(service);
    }

    @Test
    public void testServiceConnectionOnServiceDisconnected() {
        mHostEmulationManager.mService = mMessenger;
        mHostEmulationManager.mServiceBound = true;
        mHostEmulationManager.mServiceName = WALLET_PAYMENT_SERVICE;

        mHostEmulationManager.getServiceConnection().onServiceDisconnected(WALLET_PAYMENT_SERVICE);

        assertNull(mHostEmulationManager.mService);
        assertFalse(mHostEmulationManager.mServiceBound);
        assertNull(mHostEmulationManager.mServiceName);
    }

    @Test
    public void testPaymentServiceConnectionOnServiceConnected() {
        IBinder service = mock(IBinder.class);
        mHostEmulationManager.mLastBoundPaymentServiceName = WALLET_PAYMENT_SERVICE;

        mHostEmulationManager.getPaymentConnection().onServiceConnected(WALLET_PAYMENT_SERVICE,
                service);

        assertNotNull(mHostEmulationManager.mPaymentServiceName);
        assertEquals(WALLET_PAYMENT_SERVICE, mHostEmulationManager.mPaymentServiceName);
    }

    @Test
    public void testPaymentServiceConnectionOnServiceDisconnected() {
        mHostEmulationManager.mPaymentService = mMessenger;
        mHostEmulationManager.mPaymentServiceBound = true;
        mHostEmulationManager.mPaymentServiceName = WALLET_PAYMENT_SERVICE;

        mHostEmulationManager.getPaymentConnection().onServiceDisconnected(WALLET_PAYMENT_SERVICE);

        assertNull(mHostEmulationManager.mPaymentService);
        assertNull(mHostEmulationManager.mPaymentServiceName);
    }

    @Test
    public void testPaymentServiceConnectionOnBindingDied_successfulRebind() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);

        UserHandle userHandle = UserHandle.of(USER_ID);

        mHostEmulationManager.mPaymentService = mMessenger;
        mHostEmulationManager.mPaymentServiceBound = true;
        mHostEmulationManager.mPaymentServiceName = WALLET_PAYMENT_SERVICE;

        mHostEmulationManager.getPaymentConnection().onServiceDisconnected(WALLET_PAYMENT_SERVICE);
        assertNull(mHostEmulationManager.mPaymentService);
        assertNull(mHostEmulationManager.mPaymentServiceName);

        mHostEmulationManager.getPaymentConnection().onBindingDied(WALLET_PAYMENT_SERVICE);

        verify(mContext).unbindService(eq(mHostEmulationManager.getPaymentConnection()));
        verify(mContext).bindServiceAsUser(mIntentArgumentCaptor.capture(),
                mServiceConnectionArgumentCaptor.capture(),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS),
                eq(userHandle));

        assertEquals(USER_ID, mHostEmulationManager.mPaymentServiceUserId);
        assertTrue(mHostEmulationManager.mPaymentServiceBound);
    }

    @Test
    public void testPaymentServiceConnectionOnBindingDied_rebindOnTap() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(false);
        when(mRegisteredAidCache.getPreferredPaymentService()).
                thenReturn(new Pair<>(USER_ID, WALLET_PAYMENT_SERVICE));

        UserHandle userHandle = UserHandle.of(USER_ID);

        mHostEmulationManager.mPaymentService = mMessenger;
        mHostEmulationManager.mPaymentServiceBound = true;
        mHostEmulationManager.mPaymentServiceName = WALLET_PAYMENT_SERVICE;

        mHostEmulationManager.getPaymentConnection().onServiceDisconnected(WALLET_PAYMENT_SERVICE);
        assertNull(mHostEmulationManager.mPaymentService);
        assertNull(mHostEmulationManager.mPaymentServiceName);

        mHostEmulationManager.getPaymentConnection().onBindingDied(WALLET_PAYMENT_SERVICE);

        verify(mContext).unbindService(eq(mHostEmulationManager.getPaymentConnection()));
        assertFalse(verify(mContext).bindServiceAsUser(mIntentArgumentCaptor.capture(),
                mServiceConnectionArgumentCaptor.capture(),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS),
                eq(userHandle)));

        assertFalse(mHostEmulationManager.mPaymentServiceBound);

        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);

        mHostEmulationManager.bindServiceIfNeededLocked(USER_ID, WALLET_PAYMENT_SERVICE);

        verify(mContext, times(2)).bindServiceAsUser(mIntentArgumentCaptor.capture(),
                mServiceConnectionArgumentCaptor.capture(),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS),
                eq(userHandle));

        assertEquals(USER_ID, mHostEmulationManager.mPaymentServiceUserId);
        assertTrue(mHostEmulationManager.mPaymentServiceBound);
    }

    @Test
    public void testFindSelectAid_properAid() {
        final byte[] aidData = createSelectAidData(MOCK_AID);

        String aidString = mHostEmulationManager.findSelectAid(aidData);

        assertEquals(MOCK_AID, aidString);
    }

    @Test
    public void testFindSelectAid_nullData() {
        String aidString = mHostEmulationManager.findSelectAid(null);

        assertNull(aidString);
    }

    @Test
    public void testFindSelectAid_shortLength() {
        final byte[] aidData = new byte[1];

        String aidString = mHostEmulationManager.findSelectAid(aidData);

        assertNull(aidString);
    }

    @Test
    public void testFindSelectAid_longAidLength() {
        final byte[] aidData = createSelectAidData(MOCK_AID);
        aidData[4] = (byte)(HostEmulationManager.SELECT_APDU_HDR_LENGTH
                + HexFormat.of().parseHex(MOCK_AID).length + 1);

        String aidString = mHostEmulationManager.findSelectAid(aidData);

        assertNull(aidString);
    }

    @Test
    public void testOnPollingLoopDetected_noServiceBound() {
        ApduServiceInfo serviceWithFilter = mock(ApduServiceInfo.class);
        when(serviceWithFilter.getPollingLoopFilters()).thenReturn(POLLING_LOOP_FILTER);
        when(serviceWithFilter.getPollingLoopPatternFilters()).thenReturn(List.of());
        mHostEmulationManager.updatePollingLoopFilters(USER_ID, List.of(serviceWithFilter));

        // Preferred payment service is defined, but not bound
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new Pair<>(USER_ID, WALLET_PAYMENT_SERVICE));
        when(mRegisteredAidCache.getPreferredPaymentService())
            .thenReturn(new Pair<>(USER_ID, WALLET_PAYMENT_SERVICE));
        mHostEmulationManager.mPaymentServiceName = WALLET_PAYMENT_SERVICE;
        assertNull(mHostEmulationManager.mPaymentService);
        assertFalse(mHostEmulationManager.mPaymentServiceBound);

        PollingFrame frame1 = new PollingFrame(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                HexFormat.of().parseHex("42"), 0, 0, false);
        PollingFrame offFrame = new PollingFrame(PollingFrame.POLLING_LOOP_TYPE_OFF,
                null, 0, 0, false);

        mHostEmulationManager.onPollingLoopDetected(List.of(frame1, offFrame));

        assertEquals(HostEmulationManager.STATE_POLLING_LOOP, mHostEmulationManager.mState);
        assertNotNull(mHostEmulationManager.mPollingFramesToSend);
        assertNotNull(mHostEmulationManager.mUnprocessedPollingFrames);

        // Return to idle state after timeout
        mTestableLooper.moveTimeForward(mHostEmulationManager.FIELD_OFF_IDLE_DELAY_MS);
        mTestableLooper.processAllMessages();

        assertEquals(HostEmulationManager.STATE_IDLE, mHostEmulationManager.mState);
        assertNull(mHostEmulationManager.mPollingFramesToSend);
        assertNull(mHostEmulationManager.mUnprocessedPollingFrames);
    }

    private void verifyTapAgainLaunched(ApduServiceInfo service, String category) {
        verify(mContext).getPackageName();
        verify(mContext).startActivityAsUser(mIntentArgumentCaptor.capture(), eq(USER_HANDLE));
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(category, intent.getStringExtra(TapAgainDialog.EXTRA_CATEGORY));
        assertEquals(service, intent.getParcelableExtra(TapAgainDialog.EXTRA_APDU_SERVICE));
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;
        assertEquals(flags, intent.getFlags());
        assertEquals(TapAgainDialog.class.getCanonicalName(),
                intent.getComponent().getClassName());
    }

    private void verifyResolverLaunched(ArrayList<ApduServiceInfo> services,
                                        ComponentName failedComponent, String category) {
        verify(mContext).getPackageName();
        verify(mContext).startActivityAsUser(mIntentArgumentCaptor.capture(), eq(UserHandle.CURRENT));
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(category, intent.getStringExtra(AppChooserActivity.EXTRA_CATEGORY));
        assertEquals(services,
                intent.getParcelableArrayListExtra(AppChooserActivity.EXTRA_APDU_SERVICES));
        if (failedComponent != null) {
            assertEquals(failedComponent,
                    intent.getParcelableExtra(AppChooserActivity.EXTRA_FAILED_COMPONENT));
        }
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;
        assertEquals(flags, intent.getFlags());
        assertEquals(AppChooserActivity.class.getCanonicalName(),
                intent.getComponent().getClassName());
    }

    private static byte[] createSelectAidData(String aid) {
        byte[] aidStringData = HexFormat.of().parseHex(aid);
        byte[] aidData = new byte[HostEmulationManager.SELECT_APDU_HDR_LENGTH
                + aidStringData.length];
        aidData[0] = 0x00;
        aidData[1] = HostEmulationManager.INSTR_SELECT;
        aidData[2] = 0x04;
        aidData[3] = 0x00;
        aidData[4] = (byte)aidStringData.length;
        System.arraycopy(aidStringData, 0, aidData, HostEmulationManager.SELECT_APDU_HDR_LENGTH,
                aidData.length - HostEmulationManager.SELECT_APDU_HDR_LENGTH);
        return aidData;
    }

}
