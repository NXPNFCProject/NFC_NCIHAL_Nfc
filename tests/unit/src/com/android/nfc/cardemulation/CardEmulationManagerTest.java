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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.AidGroup;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.nfc.cardemulation.PollingFrame;
import android.os.Binder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Pair;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.ForegroundUtils;
import com.android.nfc.NfcPermissions;
import com.android.nfc.NfcService;
import com.android.nfc.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.List;

public class CardEmulationManagerTest {

    private static final int USER_ID = 0;
    private static final UserHandle USER_HANDLE = UserHandle.of(USER_ID);
    private static final byte[] TEST_DATA_1 = new byte[] {(byte) 0xd2};
    private static final byte[] TEST_DATA_2 = new byte[] {(byte) 0xd3};
    private static final byte[] PROPER_SKIP_DATA_NDF1_HEADER = new byte[]
            {0x00, (byte) 0xa4, 0x04, 0x00, (byte)0x07, (byte) 0xd2, 0x76, 0x00, 0x00,
                    (byte) 0x85, 0x01, 0x00};
    private static final byte[] PROPER_SKIP_DATA_NDF2_HEADER = new byte[]
            {0x00, (byte) 0xa4, 0x04, 0x00, (byte)0x07, (byte) 0xd2, 0x76, 0x00, 0x00,
                    (byte) 0x85, 0x01, 0x01};
    private static final String WALLET_HOLDER_PACKAGE_NAME = "com.android.test.walletroleholder";
    private static final List<PollingFrame> POLLING_LOOP_FRAMES = List.of();
    private static final List<ApduServiceInfo> UPDATED_SERVICES = List.of();
    private static final List<NfcFServiceInfo> UPDATED_NFC_SERVICES = List.of();
    private static final ComponentName WALLET_PAYMENT_SERVICE
            = new ComponentName(WALLET_HOLDER_PACKAGE_NAME,
            "com.android.test.walletroleholder.WalletRoleHolderApduService");
    private static final String PAYMENT_AID_1 = "A000000004101012";

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private ForegroundUtils mForegroundUtils;
    @Mock
    private WalletRoleObserver mWalletRoleObserver;
    @Mock
    private RegisteredAidCache mRegisteredAidCache;
    @Mock
    private RegisteredT3tIdentifiersCache mRegisteredT3tIdentifiersCache;
    @Mock
    private HostEmulationManager mHostEmulationManager;
    @Mock
    private HostNfcFEmulationManager mHostNfcFEmulationManager;
    @Mock
    private RegisteredServicesCache mRegisteredServicesCache;
    @Mock
    private RegisteredNfcFServicesCache mRegisteredNfcFServicesCache;
    @Mock
    private PreferredServices mPreferredServices;
    @Mock
    private EnabledNfcFServices mEnabledNfcFServices;
    @Mock
    private RoutingOptionManager mRoutingOptionManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private NfcService mNfcService;
    @Mock
    private UserManager mUserManager;
    @Mock
    private NfcAdapter mNfcAdapter;
    @Captor
    private ArgumentCaptor<List<PollingFrame>> mPollingLoopFrameCaptor;
    @Captor
    private ArgumentCaptor<byte[]> mDataCaptor;
    @Captor
    private ArgumentCaptor<List<ApduServiceInfo>> mServiceListCaptor;
    @Captor
    private ArgumentCaptor<List<NfcFServiceInfo>> mNfcServiceListCaptor;
    private MockitoSession mStaticMockSession;
    private CardEmulationManager mCardEmulationManager;
    @Before
    public void setUp() {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(ActivityManager.class)
                .mockStatic(NfcPermissions.class)
                .mockStatic(android.nfc.Flags.class)
                .strictness(Strictness.LENIENT)
                .mockStatic(NfcService.class)
                .mockStatic(Binder.class)
                .mockStatic(UserHandle.class)
                .mockStatic(NfcAdapter.class)
                .startMocking();
        MockitoAnnotations.initMocks(this);
        when(NfcAdapter.getDefaultAdapter(mContext)).thenReturn(mNfcAdapter);
        when(NfcService.getInstance()).thenReturn(mNfcService);
        when(ActivityManager.getCurrentUser()).thenReturn(USER_ID);
        when(UserHandle.getUserHandleForUid(anyInt())).thenReturn(USER_HANDLE);
        when(mContext.createContextAsUser(
                any(), anyInt())).thenReturn(mContext);
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getSystemService(eq(UserManager.class))).thenReturn(mUserManager);
        when(mResources.getBoolean(R.bool.indicate_user_activity_for_hce)).thenReturn(true);
        mCardEmulationManager = createInstanceWithMockParams();
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testConstructor() {
        assertConstructorMethodCalls();
    }

    private void assertConstructorMethodCalls() {
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mWalletRoleObserver).isWalletRoleFeatureEnabled();
        verify(mWalletRoleObserver).getDefaultWalletRoleHolder(eq(USER_ID));
        verify(mPreferredServices).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mRegisteredAidCache).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
    }

    @Test
    public void testGetters() {
        assertNotNull(mCardEmulationManager.getNfcCardEmulationInterface());
        assertNotNull(mCardEmulationManager.getNfcFCardEmulationInterface());
    }

    @Test
    public void testPollingLoopDetected() {
        mCardEmulationManager.onPollingLoopDetected(POLLING_LOOP_FRAMES);

        verify(mHostEmulationManager).onPollingLoopDetected(mPollingLoopFrameCaptor.capture());
        assertEquals(POLLING_LOOP_FRAMES, mPollingLoopFrameCaptor.getValue());
    }

    @Test
    public void testOnHostCardEmulationActivated_technologyApdu() {
        mCardEmulationManager.onHostCardEmulationActivated(CardEmulationManager.NFC_HCE_APDU);

        verify(mPowerManager).userActivity(anyLong(), eq(PowerManager.USER_ACTIVITY_EVENT_TOUCH),
                eq(PowerManager.USER_ACTIVITY_FLAG_INDIRECT));
        verify(mHostEmulationManager).onHostEmulationActivated();
        verify(mPreferredServices).onHostEmulationActivated();
        assertFalse(mCardEmulationManager.mNotSkipAid);
        verifyZeroInteractions(mHostNfcFEmulationManager);
        verifyZeroInteractions(mEnabledNfcFServices);
    }

    @Test
    public void testOnHostCardEmulationActivated_technologyNfcf() {
        mCardEmulationManager.onHostCardEmulationActivated(CardEmulationManager.NFC_HCE_NFCF);

        assertConstructorMethodCalls();
        verify(mPowerManager).userActivity(anyLong(), eq(PowerManager.USER_ACTIVITY_EVENT_TOUCH),
                eq(PowerManager.USER_ACTIVITY_FLAG_INDIRECT));
        verify(mHostNfcFEmulationManager).onHostEmulationActivated();
        verify(mRegisteredNfcFServicesCache).onHostEmulationActivated();
        verify(mEnabledNfcFServices).onHostEmulationActivated();
        verifyZeroInteractions(mHostEmulationManager);
        verifyZeroInteractions(mPreferredServices);
    }

    @Test
    public void testSkipAid_nullData_isFalse() {
        mCardEmulationManager.mNotSkipAid = false;
        assertFalse(mCardEmulationManager.isSkipAid(null));
    }

    @Test
    public void testSkipAid_notSkipTrue_isFalse() {
        mCardEmulationManager.mNotSkipAid = true;
        assertFalse(mCardEmulationManager.isSkipAid(TEST_DATA_1));
    }

    @Test
    public void testSkipAid_wrongData_isFalse() {
        mCardEmulationManager.mNotSkipAid = false;
        assertFalse(mCardEmulationManager.isSkipAid(TEST_DATA_1));
    }

    @Test
    public void testSkipAid_ndf1_isTrue() {
        mCardEmulationManager.mNotSkipAid = false;
        assertTrue(mCardEmulationManager.isSkipAid(PROPER_SKIP_DATA_NDF1_HEADER));
    }

    @Test
    public void testSkipAid_ndf2_isTrue() {
        mCardEmulationManager.mNotSkipAid = false;
        assertTrue(mCardEmulationManager.isSkipAid(PROPER_SKIP_DATA_NDF2_HEADER));
    }

    @Test
    public void testOnHostCardEmulationData_technologyApdu_skipData() {
        mCardEmulationManager.onHostCardEmulationData(CardEmulationManager.NFC_HCE_APDU,
                PROPER_SKIP_DATA_NDF1_HEADER);

        verify(mHostEmulationManager).onHostEmulationData(mDataCaptor.capture());
        assertEquals(PROPER_SKIP_DATA_NDF1_HEADER, mDataCaptor.getValue());
        verifyZeroInteractions(mHostNfcFEmulationManager);
        verifyZeroInteractions(mPowerManager);
    }

    @Test
    public void testOnHostCardEmulationData_technologyNfcf_DontSkipData() {
        mCardEmulationManager.onHostCardEmulationData(CardEmulationManager.NFC_HCE_NFCF,
                PROPER_SKIP_DATA_NDF1_HEADER);

        verify(mHostNfcFEmulationManager).onHostEmulationData(mDataCaptor.capture());
        assertEquals(PROPER_SKIP_DATA_NDF1_HEADER, mDataCaptor.getValue());
        verifyZeroInteractions(mHostEmulationManager);
        verify(mPowerManager).userActivity(anyLong(), eq(PowerManager.USER_ACTIVITY_EVENT_TOUCH),
                eq(0));
    }

    @Test
    public void testOnHostCardEmulationDeactivated_technologyApdu() {
        mCardEmulationManager.onHostCardEmulationDeactivated(CardEmulationManager.NFC_HCE_APDU);

        assertConstructorMethodCalls();
        verify(mHostEmulationManager).onHostEmulationDeactivated();
        verify(mPreferredServices).onHostEmulationDeactivated();
        verifyZeroInteractions(mHostNfcFEmulationManager);
        verifyZeroInteractions(mRegisteredNfcFServicesCache);
        verifyZeroInteractions(mEnabledNfcFServices);
    }

    @Test
    public void testOnHostCardEmulationDeactivated_technologyNfcf() {
        mCardEmulationManager.onHostCardEmulationDeactivated(CardEmulationManager.NFC_HCE_NFCF);

        assertConstructorMethodCalls();
        verify(mHostNfcFEmulationManager).onHostEmulationDeactivated();
        verify(mRegisteredNfcFServicesCache).onHostEmulationDeactivated();
        verify(mEnabledNfcFServices).onHostEmulationDeactivated();
        verifyZeroInteractions(mHostEmulationManager);
        verifyZeroInteractions(mPreferredServices);
    }

    @Test
    public void testOnOffHostAidSelected() {
        mCardEmulationManager.onOffHostAidSelected();

        assertConstructorMethodCalls();
        verify(mHostEmulationManager).onOffHostAidSelected();
    }

    @Test
    public void testOnUserSwitched() {
        mCardEmulationManager.onUserSwitched(USER_ID);

        assertConstructorMethodCalls();
        verify(mWalletRoleObserver).onUserSwitched(eq(USER_ID));
        verify(mRegisteredServicesCache).onUserSwitched();
        verify(mPreferredServices).onUserSwitched(eq(USER_ID));
        verify(mHostNfcFEmulationManager).onUserSwitched();
        verify(mRegisteredT3tIdentifiersCache).onUserSwitched();
        verify(mEnabledNfcFServices).onUserSwitched(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache).onUserSwitched();
    }

    @Test
    public void testOnManagedProfileChanged() {
        mCardEmulationManager.onManagedProfileChanged();

        assertConstructorMethodCalls();
        verify(mRegisteredServicesCache).onManagedProfileChanged();
        verify(mRegisteredNfcFServicesCache).onManagedProfileChanged();
    }

    @Test
    public void testOnNfcEnabled() {
        mCardEmulationManager.onNfcEnabled();

        assertConstructorMethodCalls();
        verify(mRegisteredAidCache).onNfcEnabled();
        verify(mRegisteredT3tIdentifiersCache).onNfcEnabled();
    }

    @Test
    public void testOnNfcDisabled() {
        mCardEmulationManager.onNfcDisabled();

        assertConstructorMethodCalls();
        verify(mRegisteredAidCache).onNfcDisabled();
        verify(mHostNfcFEmulationManager).onNfcDisabled();
        verify(mRegisteredNfcFServicesCache).onNfcDisabled();
        verify(mEnabledNfcFServices).onNfcDisabled();
        verify(mRegisteredT3tIdentifiersCache).onNfcDisabled();
    }

    @Test
    public void testOnSecureNfcToggled() {
        mCardEmulationManager.onSecureNfcToggled();

        verify(mRegisteredAidCache).onSecureNfcToggled();
        verify(mRegisteredT3tIdentifiersCache).onSecureNfcToggled();
    }

    @Test
    public void testOnServicesUpdated_walletEnabledPollingLoopDisabled() {
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(android.nfc.Flags.nfcReadPollingLoop()).thenReturn(false);

        mCardEmulationManager.onServicesUpdated(USER_ID, UPDATED_SERVICES, false);

        verify(mWalletRoleObserver, times(2)).isWalletRoleFeatureEnabled();
        verify(mRegisteredAidCache).onServicesUpdated(eq(USER_ID), mServiceListCaptor.capture());
        verify(mPreferredServices).onServicesUpdated();
        assertEquals(UPDATED_SERVICES, mServiceListCaptor.getValue());
        verifyZeroInteractions(mHostEmulationManager);
        verify(mNfcService).onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_UPDATED));
    }

    @Test
    public void testOnServicesUpdated_walletEnabledPollingLoopEnabled() {
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(android.nfc.Flags.nfcReadPollingLoop()).thenReturn(true);

        mCardEmulationManager.onServicesUpdated(USER_ID, UPDATED_SERVICES, false);

        verify(mWalletRoleObserver, times(2)).isWalletRoleFeatureEnabled();
        verify(mRegisteredAidCache).onServicesUpdated(eq(USER_ID), mServiceListCaptor.capture());
        verify(mPreferredServices).onServicesUpdated();
        verify(mHostEmulationManager).updatePollingLoopFilters(eq(USER_ID),
                mServiceListCaptor.capture());
        verify(mNfcService).onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_UPDATED));
        assertEquals(UPDATED_SERVICES, mServiceListCaptor.getAllValues().getFirst());
        assertEquals(UPDATED_SERVICES, mServiceListCaptor.getAllValues().getLast());
    }

    @Test
    public void testOnNfcFServicesUpdated() {
        mCardEmulationManager.onNfcFServicesUpdated(USER_ID, UPDATED_NFC_SERVICES);

        verify(mRegisteredT3tIdentifiersCache).onServicesUpdated(eq(USER_ID),
                mNfcServiceListCaptor.capture());
        assertEquals(UPDATED_NFC_SERVICES, mNfcServiceListCaptor.getValue());
    }

    @Test
    public void testIsServiceRegistered_serviceExists() {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);

        assertTrue(mCardEmulationManager
                .isServiceRegistered(USER_ID, WALLET_PAYMENT_SERVICE));

        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testIsServiceRegistered_serviceDoesNotExists() {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);

        assertFalse(mCardEmulationManager
                .isServiceRegistered(USER_ID, WALLET_PAYMENT_SERVICE));

        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testIsNfcServiceInstalled_serviceExists() {
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);

        assertTrue(mCardEmulationManager
                .isNfcFServiceInstalled(USER_ID, WALLET_PAYMENT_SERVICE));

        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testIsNfcServiceInstalled_serviceDoesNotExists() {
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);

        assertFalse(mCardEmulationManager
                .isNfcFServiceInstalled(USER_ID, WALLET_PAYMENT_SERVICE));

        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testPackageHasPreferredService() {
        when(mPreferredServices.packageHasPreferredService(eq(WALLET_HOLDER_PACKAGE_NAME)))
                .thenReturn(true);

        assertTrue(mCardEmulationManager
                .packageHasPreferredService(WALLET_HOLDER_PACKAGE_NAME));

        verify(mPreferredServices).packageHasPreferredService(eq(WALLET_HOLDER_PACKAGE_NAME));
    }

    @Test
    public void testCardEmulationIsDefaultServiceForCategory_serviceExistsWalletEnabled()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(mWalletRoleObserver.getDefaultWalletRoleHolder(eq(USER_ID)))
                .thenReturn(WALLET_HOLDER_PACKAGE_NAME);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .isDefaultServiceForCategory(USER_ID, WALLET_PAYMENT_SERVICE,
                        CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testCardEmulationIsDefaultServiceForCategory_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);

        assertConstructorMethodCalls();
        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .isDefaultServiceForCategory(USER_ID, WALLET_PAYMENT_SERVICE,
                        CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verifyZeroInteractions(mWalletRoleObserver);
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testCardEmulationIsDefaultServiceForAid_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredAidCache.isDefaultServiceForAid(eq(USER_ID), any(), eq(PAYMENT_AID_1)))
                .thenReturn(true);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .isDefaultServiceForAid(USER_ID, WALLET_PAYMENT_SERVICE,
                        PAYMENT_AID_1));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredAidCache).isDefaultServiceForAid(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE),
                eq(PAYMENT_AID_1));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testCardEmulationIsDefaultServiceForAid_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .isDefaultServiceForAid(USER_ID, WALLET_PAYMENT_SERVICE,
                        PAYMENT_AID_1));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredAidCache).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyZeroInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationSetDefaultForNextTap_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mPreferredServices.setDefaultForNextTap(anyInt(), any())).thenReturn(true);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .setDefaultForNextTap(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateProfileId(mContext, USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceAdminPermissions(mContext);
        });
        verify(mPreferredServices).setDefaultForNextTap(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testCardEmulationSetDefaultForNextTap_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .setDefaultForNextTap(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateProfileId(mContext, USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceAdminPermissions(mContext);
        });
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mPreferredServices).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verifyZeroInteractions(mPreferredServices);
    }

    @Test
    public void testCardEmulationSetShouldDefaultToObserveModeForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.setShouldDefaultToObserveModeForService(anyInt(), anyInt(),
                any(), anyBoolean())).thenReturn(true);
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(),
                any())).thenReturn(false);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .setShouldDefaultToObserveModeForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        true));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache)
                .doesServiceShouldDefaultToObserveMode(anyInt(), any());
        verify(mRegisteredServicesCache).setShouldDefaultToObserveModeForService(eq(USER_ID),
                anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetShouldDefaultToObserveModeForService_ignoreNoopStateChange()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.setShouldDefaultToObserveModeForService(anyInt(), anyInt(),
                any(), anyBoolean())).thenReturn(true);
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(),
                any())).thenReturn(false);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .setShouldDefaultToObserveModeForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        true));

        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(),
                any())).thenReturn(true);

        // Called twice with the same value. Calls to update should be ignored.
        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .setShouldDefaultToObserveModeForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        true));

        ExtendedMockito.verify(() -> NfcPermissions.validateUserId(USER_ID), times(2));
        ExtendedMockito.verify(() -> NfcPermissions.enforceUserPermissions(mContext), times(2));
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .doesServiceShouldDefaultToObserveMode(anyInt(), any());
        verify(mRegisteredServicesCache, times(4))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));

        // Importantly this should only be called once.
        verify(mRegisteredServicesCache, times(1))
                .setShouldDefaultToObserveModeForService(eq(USER_ID), anyInt(),
                        eq(WALLET_PAYMENT_SERVICE), eq(true));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetShouldDefaultToObserveModeForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .setShouldDefaultToObserveModeForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        false));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRegisterAidGroupForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.registerAidGroupForService(eq(USER_ID), anyInt(), any(),
                any())).thenReturn(true);
        AidGroup aidGroup = Mockito.mock(AidGroup.class);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .registerAidGroupForService(USER_ID, WALLET_PAYMENT_SERVICE, aidGroup));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache).registerAidGroupForService(eq(USER_ID), anyInt(),
                eq(WALLET_PAYMENT_SERVICE), eq(aidGroup));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRegisterAidGroupForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.registerAidGroupForService(eq(USER_ID), anyInt(), any(),
                any())).thenReturn(true);
        AidGroup aidGroup = Mockito.mock(AidGroup.class);

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .registerAidGroupForService(USER_ID, WALLET_PAYMENT_SERVICE, aidGroup));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredAidCache).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                        eq(USER_ID));
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationRegisterPollingLoopFilterForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.registerPollingLoopFilterForService(eq(USER_ID), anyInt(),
                any(), any(),anyBoolean())).thenReturn(true);
        String pollingLoopFilter = "filter";

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .registerPollingLoopFilterForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        pollingLoopFilter, true));

        verify(mRegisteredServicesCache).initialize();
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache).registerPollingLoopFilterForService(eq(USER_ID),
                anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(pollingLoopFilter), eq(true));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRegisterPollingLoopFilterForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.registerPollingLoopFilterForService(eq(USER_ID), anyInt(),
                any(), any(),anyBoolean())).thenReturn(true);
        String pollingLoopFilter = "filter";

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .registerPollingLoopFilterForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        pollingLoopFilter, true));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRemovePollingLoopFilterForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.removePollingLoopFilterForService(eq(USER_ID), anyInt(),
                any(), any())).thenReturn(true);
        String pollingLoopFilter = "filter";

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .removePollingLoopFilterForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        pollingLoopFilter));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache).removePollingLoopFilterForService(eq(USER_ID),
                anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(pollingLoopFilter));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRemovePollingLoopFilterForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.removePollingLoopFilterForService(eq(USER_ID), anyInt(),
                any(), any())).thenReturn(true);
        String pollingLoopFilter = "filter";

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .removePollingLoopFilterForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        pollingLoopFilter));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRegisterPollingLoopPatternFilterForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.registerPollingLoopPatternFilterForService(eq(USER_ID),
                anyInt(), any(), any(), anyBoolean())).thenReturn(true);
        String pollingLoopFilter = "filter";

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .registerPollingLoopPatternFilterForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        pollingLoopFilter, true));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache).registerPollingLoopPatternFilterForService(eq(USER_ID),
                anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(pollingLoopFilter), eq(true));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRegisterPollingLoopPatternFilterForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.registerPollingLoopPatternFilterForService(eq(USER_ID),
                anyInt(), any(), any(), anyBoolean())).thenReturn(true);
        String pollingLoopFilter = "filter";

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .registerPollingLoopPatternFilterForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        pollingLoopFilter, true));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRemovePollingLoopPatternFilterForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.removePollingLoopPatternFilterForService(eq(USER_ID),
                anyInt(), any(), any())).thenReturn(true);
        String pollingLoopFilter = "filter";

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .removePollingLoopPatternFilterForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        pollingLoopFilter));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache).removePollingLoopPatternFilterForService(eq(USER_ID),
                anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(pollingLoopFilter));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRemovePollingLoopPatternFilterForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.removePollingLoopPatternFilterForService(eq(USER_ID),
                anyInt(), any(), any())).thenReturn(true);
        String pollingLoopFilter = "filter";

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .removePollingLoopPatternFilterForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        pollingLoopFilter));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetOffHostForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.setOffHostSecureElement(eq(USER_ID),
                anyInt(), any(), any())).thenReturn(true);
        String offhostse = "offhostse";

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .setOffHostForService(USER_ID, WALLET_PAYMENT_SERVICE, offhostse));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache).setOffHostSecureElement(eq(USER_ID), anyInt(),
                eq(WALLET_PAYMENT_SERVICE) , eq(offhostse));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetOffHostForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.setOffHostSecureElement(eq(USER_ID),
                anyInt(), any(), any())).thenReturn(true);
        String offhostse = "offhostse";

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .setOffHostForService(USER_ID, WALLET_PAYMENT_SERVICE, offhostse));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationUnsetOffHostForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.resetOffHostSecureElement(eq(USER_ID),
                anyInt(), any())).thenReturn(true);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .unsetOffHostForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache).resetOffHostSecureElement(eq(USER_ID), anyInt(),
                eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
        verify(mNfcService).onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_UPDATED));
    }

    @Test
    public void testCardEmulationUnsetOffHostForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.resetOffHostSecureElement(eq(USER_ID),
                anyInt(), any())).thenReturn(true);

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .unsetOffHostForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationGetAidGroupForService_serviceExists()
            throws RemoteException {
        AidGroup aidGroup = Mockito.mock(AidGroup.class);
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.getAidGroupForService(eq(USER_ID),
                anyInt(), any(), eq(CardEmulation.CATEGORY_PAYMENT))).thenReturn(aidGroup);

        assertEquals(aidGroup, mCardEmulationManager.getNfcCardEmulationInterface()
                .getAidGroupForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache).getAidGroupForService(eq(USER_ID), anyInt(),
                eq(WALLET_PAYMENT_SERVICE), eq(CardEmulation.CATEGORY_PAYMENT));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationGetAidGroupForService_serviceDoesNotExists()
            throws RemoteException {
        AidGroup aidGroup = Mockito.mock(AidGroup.class);
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.getAidGroupForService(eq(USER_ID),
                anyInt(), any(), eq(CardEmulation.CATEGORY_PAYMENT))).thenReturn(aidGroup);

        assertNull(mCardEmulationManager.getNfcCardEmulationInterface()
                .getAidGroupForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRemoveAidGroupForService_serviceExists()
            throws RemoteException {
        AidGroup aidGroup = Mockito.mock(AidGroup.class);
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.removeAidGroupForService(eq(USER_ID),
                anyInt(), any(), eq(CardEmulation.CATEGORY_PAYMENT))).thenReturn(true);
        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .removeAidGroupForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache).removeAidGroupForService(eq(USER_ID), anyInt(),
                eq(WALLET_PAYMENT_SERVICE), eq(CardEmulation.CATEGORY_PAYMENT));
        verifyNoMoreInteractions(mRegisteredServicesCache);
        verify(mNfcService).onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_UPDATED));
    }

    @Test
    public void testCardEmulationRemoveAidGroupForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.removeAidGroupForService(eq(USER_ID),
                anyInt(), any(), eq(CardEmulation.CATEGORY_PAYMENT))).thenReturn(true);

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .removeAidGroupForService(USER_ID, WALLET_PAYMENT_SERVICE,
                        CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationGetServices()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.getServicesForCategory(eq(USER_ID),
                eq(CardEmulation.CATEGORY_PAYMENT))).thenReturn(UPDATED_SERVICES);

        assertEquals(UPDATED_SERVICES, mCardEmulationManager.getNfcCardEmulationInterface()
                .getServices(USER_ID, CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateProfileId(mContext, USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceAdminPermissions(mContext);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).getServicesForCategory(eq(USER_ID),
                eq(CardEmulation.CATEGORY_PAYMENT));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetPreferredService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mPreferredServices.registerPreferredForegroundService(eq(WALLET_PAYMENT_SERVICE),
                anyInt())).thenReturn(true);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .setPreferredService(WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
        verify(mPreferredServices).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mPreferredServices).registerPreferredForegroundService(eq(WALLET_PAYMENT_SERVICE),
                anyInt());
        verifyNoMoreInteractions(mPreferredServices);
    }

    @Test
    public void testCardEmulationSetPreferredService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mPreferredServices.registerPreferredForegroundService(eq(WALLET_PAYMENT_SERVICE),
                anyInt())).thenReturn(false);

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .setPreferredService(WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
        verify(mPreferredServices).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verifyNoMoreInteractions(mPreferredServices);
    }

    @Test
    public void testCardEmulationUnsetPreferredService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mPreferredServices.unregisteredPreferredForegroundService(anyInt()))
                .thenReturn(true);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .unsetPreferredService());

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mPreferredServices).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mPreferredServices).unregisteredPreferredForegroundService(anyInt());
        verifyNoMoreInteractions(mPreferredServices);
    }

    @Test
    public void testCardEmulationUnsetPreferredService_serviceDoesNotExists()
            throws RemoteException {
        when(mPreferredServices.unregisteredPreferredForegroundService(anyInt()))
                .thenReturn(false);

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface().unsetPreferredService());

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mPreferredServices).unregisteredPreferredForegroundService(anyInt());
    }

    @Test
    public void testCardEmulationSupportsAidPrefixRegistration_doesSupport()
            throws RemoteException {
        when(mRegisteredAidCache.supportsAidPrefixRegistration()).thenReturn(true);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .supportsAidPrefixRegistration());

        verify(mRegisteredAidCache).supportsAidPrefixRegistration();
    }

    @Test
    public void testCardEmulationSupportsAidPrefixRegistration_doesNotSupport()
            throws RemoteException {
        when(mRegisteredAidCache.supportsAidPrefixRegistration()).thenReturn(false);

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .supportsAidPrefixRegistration());

        verify(mRegisteredAidCache).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mRegisteredAidCache).supportsAidPrefixRegistration();
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationGetPreferredPaymentService()
            throws RemoteException {
        ApduServiceInfo apduServiceInfo = Mockito.mock(ApduServiceInfo.class);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new Pair<>(USER_ID, WALLET_PAYMENT_SERVICE));
        when(mRegisteredServicesCache.getService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE)))
                .thenReturn(apduServiceInfo);

        assertEquals(apduServiceInfo, mCardEmulationManager.getNfcCardEmulationInterface()
                .getPreferredPaymentService(USER_ID));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforcePreferredPaymentInfoPermissions(mContext);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredAidCache).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mRegisteredAidCache).getPreferredService();
        verify(mRegisteredServicesCache).getService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredAidCache);
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetServiceEnabledForCategoryOther_resourceTrue()
            throws RemoteException {
        when(mResources.getBoolean(R.bool.enable_service_for_category_other)).thenReturn(true);
        when(mRegisteredServicesCache.registerOtherForService(anyInt(), any(), anyBoolean()))
                .thenReturn(true);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .setServiceEnabledForCategoryOther(USER_ID, WALLET_PAYMENT_SERVICE, true));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).registerOtherForService(eq(USER_ID),
                eq(WALLET_PAYMENT_SERVICE), eq(true));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetServiceEnabledForCategoryOther_resourceFalse()
            throws RemoteException {
        when(mResources.getBoolean(R.bool.enable_service_for_category_other)).thenReturn(false);
        when(mRegisteredServicesCache.registerOtherForService(anyInt(), any(), anyBoolean()))
                .thenReturn(true);

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .setServiceEnabledForCategoryOther(USER_ID, WALLET_PAYMENT_SERVICE, true));

        verify(mRegisteredServicesCache).initialize();
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationIsDefaultPaymentRegistered_walletRoleEnabledWalletSet()
            throws RemoteException {
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(mWalletRoleObserver.getDefaultWalletRoleHolder(anyInt()))
                .thenReturn(WALLET_HOLDER_PACKAGE_NAME);
        when(Binder.getCallingUserHandle()).thenReturn(USER_HANDLE);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface()
                .isDefaultPaymentRegistered());

        verify(mWalletRoleObserver, times(2)).isWalletRoleFeatureEnabled();
        verify(mWalletRoleObserver, times(2))
                .getDefaultWalletRoleHolder(eq(USER_ID));
    }

    @Test
    public void testCardEmulationIsDefaultPaymentRegistered_walletRoleEnabledWalletNone()
            throws RemoteException {
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(mWalletRoleObserver.getDefaultWalletRoleHolder(anyInt()))
                .thenReturn(null);
        when(Binder.getCallingUserHandle()).thenReturn(USER_HANDLE);

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface()
                .isDefaultPaymentRegistered());

        verify(mWalletRoleObserver, times(2)).isWalletRoleFeatureEnabled();
        verify(mWalletRoleObserver, times(2))
                .getDefaultWalletRoleHolder(eq(USER_ID));
    }

    @Test
    public void testCardEmulationOverrideRoutingTable_callerNotForeground()
            throws RemoteException {
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt()))
                .thenReturn(false);
        String protocol = "DH";
        String technology = "DH";

        assertThrows(IllegalArgumentException.class,
                () -> mCardEmulationManager.getNfcCardEmulationInterface()
                .overrideRoutingTable(USER_ID, protocol, technology, WALLET_HOLDER_PACKAGE_NAME));

        verify(mRegisteredAidCache).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationOverrideRoutingTable_callerForegroundRouteNull()
            throws RemoteException {
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt()))
                .thenReturn(true);

        mCardEmulationManager.getNfcCardEmulationInterface()
                .overrideRoutingTable(USER_ID, null, null, WALLET_HOLDER_PACKAGE_NAME);

        verify(mRegisteredAidCache).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mRoutingOptionManager).overrideDefaultIsoDepRoute(eq(-1));
        verify(mRoutingOptionManager).overrideDefaultOffHostRoute(eq(-1));
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verify(mRegisteredAidCache).onRoutingOverridedOrRecovered();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationOverrideRoutingTable_callerForegroundRouteDH()
            throws RemoteException {
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt()))
                .thenReturn(true);
        String protocol = "DH";
        String technology = "DH";

        mCardEmulationManager.getNfcCardEmulationInterface()
                .overrideRoutingTable(USER_ID, protocol, technology, WALLET_HOLDER_PACKAGE_NAME);

        verify(mRegisteredAidCache).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mRoutingOptionManager).overrideDefaultIsoDepRoute(eq(0));
        verify(mRoutingOptionManager).overrideDefaultOffHostRoute(eq(0));
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verify(mRegisteredAidCache).onRoutingOverridedOrRecovered();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationOverrideRoutingTable_callerForegroundRouteeSE()
            throws RemoteException {
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt()))
                .thenReturn(true);
        String protocol = "eSE1";
        String technology = "eSE1";

        mCardEmulationManager.getNfcCardEmulationInterface()
                .overrideRoutingTable(USER_ID, protocol, technology, WALLET_HOLDER_PACKAGE_NAME);

        verify(mRegisteredAidCache).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mRoutingOptionManager).overrideDefaultIsoDepRoute(eq(TEST_DATA_1[0] & 0xFF));
        verify(mRoutingOptionManager).overrideDefaultOffHostRoute(eq(TEST_DATA_1[0] & 0xFF));
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verify(mRegisteredAidCache).onRoutingOverridedOrRecovered();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationOverrideRoutingTable_callerForegroundRouteSIM()
            throws RemoteException {
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt()))
                .thenReturn(true);
        String protocol = "SIM1";
        String technology = "SIM1";

        mCardEmulationManager.getNfcCardEmulationInterface()
                .overrideRoutingTable(USER_ID, protocol, technology, WALLET_HOLDER_PACKAGE_NAME);

        verify(mRegisteredAidCache).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mRoutingOptionManager).overrideDefaultIsoDepRoute(eq(TEST_DATA_2[0] & 0xFF));
        verify(mRoutingOptionManager).overrideDefaultOffHostRoute(eq(TEST_DATA_2[0] & 0xFF));
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verify(mRegisteredAidCache).onRoutingOverridedOrRecovered();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationRecoverRoutingTable_callerForeground()
            throws RemoteException {
        when(mForegroundUtils.isInForeground(anyInt()))
                .thenReturn(true);

        mCardEmulationManager.getNfcCardEmulationInterface()
                .recoverRoutingTable(USER_ID);

        verify(mRegisteredAidCache).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mRoutingOptionManager).recoverOverridedRoutingTable();
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verify(mRegisteredAidCache).onRoutingOverridedOrRecovered();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationRecoverRoutingTable_callerNotForeground()
            throws RemoteException {
        when(mForegroundUtils.isInForeground(anyInt()))
                .thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> mCardEmulationManager.getNfcCardEmulationInterface()
                .recoverRoutingTable(USER_ID));

        verify(mRegisteredAidCache).onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME),
                eq(USER_ID));
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testNfcFCardEmulationGetSystemCodeForService_serviceExists()
            throws RemoteException {
        String systemCode = "systemCode";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any()))
                .thenReturn(true);
        when(mRegisteredNfcFServicesCache.getSystemCodeForService(anyInt(),
                anyInt(), any())).thenReturn(systemCode);

        assertEquals(systemCode, mCardEmulationManager.getNfcFCardEmulationInterface()
                .getSystemCodeForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredNfcFServicesCache).getSystemCodeForService(eq(USER_ID), anyInt(),
                eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationGetSystemCodeForService_serviceDoesNotExists()
            throws RemoteException {
        String systemCode = "systemCode";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any()))
                .thenReturn(false);
        when(mRegisteredNfcFServicesCache.getSystemCodeForService(anyInt(),
                anyInt(), any())).thenReturn(systemCode);

        assertNull(mCardEmulationManager.getNfcFCardEmulationInterface()
                .getSystemCodeForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationRegisterSystemCodeForService_serviceExists()
            throws RemoteException {
        String systemCode = "systemCode";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any()))
                .thenReturn(true);
        when(mRegisteredNfcFServicesCache.registerSystemCodeForService(anyInt(),
                anyInt(), any(), anyString())).thenReturn(true);

        assertTrue(mCardEmulationManager.getNfcFCardEmulationInterface()
                .registerSystemCodeForService(USER_ID, WALLET_PAYMENT_SERVICE, systemCode));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredNfcFServicesCache).registerSystemCodeForService(eq(USER_ID), anyInt(),
                eq(WALLET_PAYMENT_SERVICE), eq(systemCode));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationRegisterSystemCodeForService_serviceDoesNotExists()
            throws RemoteException {
        String systemCode = "systemCode";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any()))
                .thenReturn(false);
        when(mRegisteredNfcFServicesCache.registerSystemCodeForService(anyInt(),
                anyInt(), any(), anyString())).thenReturn(true);

        assertFalse(mCardEmulationManager.getNfcFCardEmulationInterface()
                .registerSystemCodeForService(USER_ID, WALLET_PAYMENT_SERVICE, systemCode));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationRemoveSystemCodeForService_serviceExists()
            throws RemoteException {
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any()))
                .thenReturn(true);
        when(mRegisteredNfcFServicesCache.removeSystemCodeForService(anyInt(),
                anyInt(), any())).thenReturn(true);

        assertTrue(mCardEmulationManager.getNfcFCardEmulationInterface()
                .removeSystemCodeForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredNfcFServicesCache).removeSystemCodeForService(eq(USER_ID), anyInt(),
                eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationRemoveSystemCodeForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any()))
                .thenReturn(false);
        when(mRegisteredNfcFServicesCache.removeSystemCodeForService(anyInt(),
                anyInt(), any())).thenReturn(true);

        assertFalse(mCardEmulationManager.getNfcFCardEmulationInterface()
                .removeSystemCodeForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationGetNfcid2ForService_serviceExists()
            throws RemoteException {
        String nfcid2 = "nfcid2";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any()))
                .thenReturn(true);
        when(mRegisteredNfcFServicesCache.getNfcid2ForService(anyInt(),
                anyInt(), any())).thenReturn(nfcid2);

        assertEquals(nfcid2, mCardEmulationManager.getNfcFCardEmulationInterface()
                .getNfcid2ForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredNfcFServicesCache).getNfcid2ForService(eq(USER_ID), anyInt(),
                eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationGetNfcid2ForService_serviceDoesNotExists()
            throws RemoteException {
        String nfcid2 = "nfcid2";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any()))
                .thenReturn(false);
        when(mRegisteredNfcFServicesCache.getNfcid2ForService(anyInt(),
                anyInt(), any())).thenReturn(nfcid2);

        assertNull(mCardEmulationManager.getNfcFCardEmulationInterface()
                .getNfcid2ForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationSetNfcid2ForService_serviceExists()
            throws RemoteException {
        String nfcid2 = "nfcid2";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any()))
                .thenReturn(true);
        when(mRegisteredNfcFServicesCache.setNfcid2ForService(anyInt(),
                anyInt(), any(), anyString())).thenReturn(true);

        assertTrue(mCardEmulationManager.getNfcFCardEmulationInterface()
                .setNfcid2ForService(USER_ID, WALLET_PAYMENT_SERVICE, nfcid2));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredNfcFServicesCache).setNfcid2ForService(eq(USER_ID), anyInt(),
                eq(WALLET_PAYMENT_SERVICE), eq(nfcid2));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationSetNfcid2ForService_serviceDoesNotExists()
            throws RemoteException {
        String nfcid2 = "nfcid2";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any()))
                .thenReturn(false);
        when(mRegisteredNfcFServicesCache.setNfcid2ForService(anyInt(),
                anyInt(), any(), anyString())).thenReturn(true);

        assertFalse(mCardEmulationManager.getNfcFCardEmulationInterface()
                .setNfcid2ForService(USER_ID, WALLET_PAYMENT_SERVICE, nfcid2));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateUserId(USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationEnableNfcFForegroundService_serviceExists()
            throws RemoteException {
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any()))
                .thenReturn(true);
        when(mEnabledNfcFServices.registerEnabledForegroundService(any(),
                anyInt())).thenReturn(true);
        when(Binder.getCallingUserHandle()).thenReturn(USER_HANDLE);

        assertTrue(mCardEmulationManager.getNfcFCardEmulationInterface()
                .enableNfcFForegroundService(WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verify(mEnabledNfcFServices).registerEnabledForegroundService(eq(WALLET_PAYMENT_SERVICE),
                anyInt());
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationEnableNfcFForegroundService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any()))
                .thenReturn(false);
        when(mEnabledNfcFServices.registerEnabledForegroundService(any(),
                anyInt())).thenReturn(true);

        assertFalse(mCardEmulationManager.getNfcFCardEmulationInterface()
                .enableNfcFForegroundService(WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID),eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
        verifyNoMoreInteractions(mEnabledNfcFServices);
    }

    @Test
    public void testNfcFCardEmulationDisableNfcFForegroundService_serviceDoesNotExists()
            throws RemoteException {
        when(mEnabledNfcFServices.unregisteredEnabledForegroundService(anyInt()))
                .thenReturn(true);

        assertTrue(mCardEmulationManager.getNfcFCardEmulationInterface()
                .disableNfcFForegroundService());

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mEnabledNfcFServices).unregisteredEnabledForegroundService(anyInt());
        verifyNoMoreInteractions(mEnabledNfcFServices);
    }

    @Test
    public void testNfcFCardEmulationGetServices()
            throws RemoteException {
        when(mRegisteredNfcFServicesCache.getServices(anyInt()))
                .thenReturn(UPDATED_NFC_SERVICES);

        assertEquals(UPDATED_NFC_SERVICES, mCardEmulationManager.getNfcFCardEmulationInterface()
                .getNfcFServices(USER_ID));

        ExtendedMockito.verify(() -> {
            NfcPermissions.validateProfileId(mContext, USER_ID);
        });
        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).getServices(eq(USER_ID));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationGetMaxNumOfRegisterableSystemCodes()
            throws RemoteException {
        int MAX = 3;
        when(mNfcService.getLfT3tMax()).thenReturn(MAX);

        assertEquals(MAX, mCardEmulationManager.getNfcFCardEmulationInterface()
                .getMaxNumOfRegisterableSystemCodes());

        ExtendedMockito.verify(() -> {
            NfcPermissions.enforceUserPermissions(mContext);
        });
        verify(mNfcService).getLfT3tMax();
        verifyNoMoreInteractions(mNfcService);
    }

    @Test
    public void testOnPreferredPaymentServiceChanged_observeModeEnabled() {
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new Pair<>(-1, null));
        mCardEmulationManager.onPreferredPaymentServiceChanged(0, null);

        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(true);
        when(android.nfc.Flags.nfcObserveMode()).thenReturn(true);

        mCardEmulationManager.onPreferredPaymentServiceChanged(USER_ID, WALLET_PAYMENT_SERVICE);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new Pair<>(USER_ID, WALLET_PAYMENT_SERVICE));

        verify(mHostEmulationManager).onPreferredPaymentServiceChanged(eq(USER_ID),
                eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredAidCache).onWalletRoleHolderChanged(
                eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRegisteredAidCache).onPreferredPaymentServiceChanged(eq(USER_ID),
                eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache).initialize();
        verify(mNfcService, times(2))
                .onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_CHANGED));
    }

    @Test
    public void testOnPreferredPaymentServiceChanged_observeModeDisabled() {
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(true);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new Pair<>(USER_ID, WALLET_PAYMENT_SERVICE));
        when(android.nfc.Flags.nfcObserveMode()).thenReturn(false);

        mCardEmulationManager.onPreferredPaymentServiceChanged(USER_ID, WALLET_PAYMENT_SERVICE);

        verify(mHostEmulationManager).onPreferredPaymentServiceChanged(eq(USER_ID),
                eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredAidCache).onWalletRoleHolderChanged(
                eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRegisteredAidCache).onPreferredPaymentServiceChanged(eq(USER_ID),
                eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache).initialize();
        verify(mNfcService).onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_CHANGED));
        assertUpdateForShouldDefaultToObserveMode(false);
    }

    @Test
    public void testOnPreferredForegroundServiceChanged_observeModeEnabled() {
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(true);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new Pair<>(USER_ID, WALLET_PAYMENT_SERVICE));
        when(android.nfc.Flags.nfcObserveMode()).thenReturn(true);

        mCardEmulationManager.onPreferredForegroundServiceChanged(USER_ID, WALLET_PAYMENT_SERVICE);

        verify(mRegisteredAidCache).onWalletRoleHolderChanged(
                eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mHostEmulationManager).onPreferredForegroundServiceChanged(eq(USER_ID),
                eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache).initialize();
        verify(mNfcService).onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_CHANGED));
    }

    @Test
    public void testOnPreferredForegroundServiceChanged_observeModeDisabled() {
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(true);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new Pair<>(USER_ID, WALLET_PAYMENT_SERVICE));
        when(android.nfc.Flags.nfcObserveMode()).thenReturn(false);

        mCardEmulationManager.onPreferredForegroundServiceChanged(USER_ID, WALLET_PAYMENT_SERVICE);

        verify(mHostEmulationManager).onPreferredForegroundServiceChanged(eq(USER_ID),
                eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredAidCache).onWalletRoleHolderChanged(
                eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRegisteredServicesCache).initialize();
        verify(mNfcService).onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_CHANGED));
        assertUpdateForShouldDefaultToObserveMode(false);
    }

    @Test
    public void testOnPreferredPaymentServiceChanged_toNull_dontUpdateObserveMode() {
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(true);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new Pair<>(USER_ID, WALLET_PAYMENT_SERVICE));
        when(android.nfc.Flags.nfcObserveMode()).thenReturn(true);

        mCardEmulationManager.onPreferredPaymentServiceChanged(USER_ID, null);

        verify(mRegisteredServicesCache).initialize();
        assertUpdateForShouldDefaultToObserveMode(false);
    }

    @Test
    public void testOnPreferredForegroundServiceChanged_toNull_dontUpdateObserveMode() {
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(true);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new Pair<>(USER_ID, WALLET_PAYMENT_SERVICE));
        when(android.nfc.Flags.nfcObserveMode()).thenReturn(true);

        mCardEmulationManager.onPreferredForegroundServiceChanged(USER_ID, null);

        verify(mRegisteredServicesCache).initialize();
        assertUpdateForShouldDefaultToObserveMode(false);
    }

    @Test
    public void testOnWalletRoleHolderChanged() {
        mCardEmulationManager.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);

        verify(mPreferredServices, times(2))
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRegisteredAidCache, times(2))
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verifyNoMoreInteractions(mPreferredServices);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testOnEnabledForegroundNfcFServiceChanged() {
        mCardEmulationManager.onEnabledForegroundNfcFServiceChanged(USER_ID,
                WALLET_PAYMENT_SERVICE);

        verify(mRegisteredT3tIdentifiersCache).onEnabledForegroundNfcFServiceChanged(eq(USER_ID),
                eq(WALLET_PAYMENT_SERVICE));
        verify(mHostNfcFEmulationManager)
                .onEnabledForegroundNfcFServiceChanged(eq(USER_ID),
                        eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredT3tIdentifiersCache);
        verifyNoMoreInteractions(mHostNfcFEmulationManager);
    }

    @Test
    public void testGetRegisteredAidCategory() {
        RegisteredAidCache.AidResolveInfo aidResolveInfo = Mockito.mock(
                RegisteredAidCache.AidResolveInfo.class);
        when(aidResolveInfo.getCategory()).thenReturn(CardEmulation.CATEGORY_PAYMENT);

        when(mRegisteredAidCache.resolveAid(anyString())).thenReturn(aidResolveInfo);

        assertEquals(CardEmulation.CATEGORY_PAYMENT,
            mCardEmulationManager.getRegisteredAidCategory(PAYMENT_AID_1));

        verify(mRegisteredAidCache).resolveAid(eq(PAYMENT_AID_1));
        verify(aidResolveInfo).getCategory();
    }

    @Test
    public void testIsRequiresScreenOnServiceExist() {
        when(mRegisteredAidCache.isRequiresScreenOnServiceExist()).thenReturn(true);

        assertTrue(mCardEmulationManager.isRequiresScreenOnServiceExist());
    }


    private void assertUpdateForShouldDefaultToObserveMode(boolean flagEnabled) {
        if (flagEnabled) {
            ExtendedMockito.verify(() -> {
                NfcAdapter.getDefaultAdapter(mContext);
            });
            verify(mRegisteredAidCache).getPreferredService();
            verify(mRegisteredServicesCache).doesServiceShouldDefaultToObserveMode(eq(USER_ID),
                    eq(WALLET_PAYMENT_SERVICE));
            verify(mNfcAdapter).setObserveModeEnabled(eq(true));
        }
        verifyNoMoreInteractions(mNfcAdapter);
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    private CardEmulationManager createInstanceWithMockParams() {
        when(mRoutingOptionManager.getOffHostRouteEse()).thenReturn(TEST_DATA_1);
        when(mRoutingOptionManager.getOffHostRouteUicc()).thenReturn(TEST_DATA_2);
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(mWalletRoleObserver.getDefaultWalletRoleHolder(eq(USER_ID)))
                .thenReturn(WALLET_HOLDER_PACKAGE_NAME);

        return new CardEmulationManager(mContext, mForegroundUtils, mWalletRoleObserver,
                mRegisteredAidCache, mRegisteredT3tIdentifiersCache, mHostEmulationManager,
                mHostNfcFEmulationManager, mRegisteredServicesCache, mRegisteredNfcFServicesCache,
                mPreferredServices, mEnabledNfcFServices, mRoutingOptionManager, mPowerManager);
    }
}
