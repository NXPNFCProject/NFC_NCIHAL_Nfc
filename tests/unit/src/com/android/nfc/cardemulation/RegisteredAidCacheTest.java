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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Pair;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.NfcService;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class RegisteredAidCacheTest {

    private static final String PREFIX_AID = "ASDASD*";
    private static final String SUBSET_AID = "ASDASD#";
    private static final String EXACT_AID = "TASDASD";
    private static final String PAYMENT_AID_1 = "A000000004101012";
    private static final String PAYMENT_AID_2 = "A000000004101018";
    private static final String NON_PAYMENT_AID_1 = "F053414950454D";
    private static final String PREFIX_PAYMENT_AID = "A000000004*";
    private static final String NFC_FOREGROUND_PACKAGE_NAME = "com.android.test.foregroundnfc";
    private static final String NON_PAYMENT_NFC_PACKAGE_NAME = "com.android.test.nonpaymentnfc";
    private static final String WALLET_HOLDER_PACKAGE_NAME = "com.android.test.walletroleholder";
    private static final String WALLET_HOLDER_2_PACKAGE_NAME = "com.android.test.walletroleholder2";

    private static final ComponentName WALLET_PAYMENT_SERVICE
            = new ComponentName(WALLET_HOLDER_PACKAGE_NAME,
            "com.android.test.walletroleholder.WalletRoleHolderApduService");

    private static final ComponentName WALLET_PAYMENT_SERVICE_2
            = new ComponentName(WALLET_HOLDER_PACKAGE_NAME,
            "com.android.test.walletroleholder.XWalletRoleHolderApduService");
    private static final ComponentName FOREGROUND_SERVICE
            = new ComponentName(NFC_FOREGROUND_PACKAGE_NAME,
            "com.android.test.foregroundnfc.ForegroundApduService");
    private static final ComponentName NON_PAYMENT_SERVICE =
            new ComponentName(NON_PAYMENT_NFC_PACKAGE_NAME,
                    "com.android.test.nonpaymentnfc.NonPaymentApduService");

    private static final ComponentName PAYMENT_SERVICE =
            new ComponentName(WALLET_HOLDER_2_PACKAGE_NAME,
                    "com.android.test.walletroleholder.WalletRoleHolderXApduService");

    private static final int USER_ID = 0;
    private static final UserHandle USER_HANDLE = UserHandle.of(USER_ID);

    @Mock
    private Context mContext;
    @Mock
    private WalletRoleObserver mWalletRoleObserver;
    @Mock
    private AidRoutingManager mAidRoutingManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private NfcService mNfcService;
    @Captor
    private ArgumentCaptor<HashMap<String, AidRoutingManager.AidEntry>> mRoutingEntryMapCaptor;

    private MockitoSession mStaticMockSession;

    RegisteredAidCache mRegisteredAidCache;

    @Before
    public void setUp() {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(ActivityManager.class)
                .mockStatic(NfcService.class)
                .strictness(Strictness.LENIENT)
                .initMocks(this)
                .startMocking();
        when(ActivityManager.getCurrentUser()).thenReturn(USER_ID);
        when(NfcService.getInstance()).thenReturn(mNfcService);
        when(mNfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_1_0);
        when(mUserManager.getProfileParent(eq(USER_HANDLE))).thenReturn(USER_HANDLE);
        when(mContext.createContextAsUser(
                any(), anyInt())).thenReturn(mContext);
        when(mContext.getSystemService(eq(UserManager.class))).thenReturn(mUserManager);
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testConstructor_supportsPrefixAndSubset() {
        supportPrefixAndSubset(true);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);

        verify(mAidRoutingManager).supportsAidPrefixRouting();
        verify(mAidRoutingManager).supportsAidSubsetRouting();
        Assert.assertTrue(mRegisteredAidCache.supportsAidPrefixRegistration());
        Assert.assertTrue(mRegisteredAidCache.supportsAidSubsetRegistration());
    }

    @Test
    public void testConstructor_doesNotSupportsPrefixAndSubset() {
        supportPrefixAndSubset(false);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);

        verify(mAidRoutingManager).supportsAidPrefixRouting();
        verify(mAidRoutingManager).supportsAidSubsetRouting();
        Assert.assertFalse(mRegisteredAidCache.supportsAidPrefixRegistration());
        Assert.assertFalse(mRegisteredAidCache.supportsAidSubsetRegistration());
    }

    @Test
    public void testAidStaticMethods() {
        Assert.assertTrue(RegisteredAidCache.isPrefix(PREFIX_AID));
        Assert.assertTrue(RegisteredAidCache.isSubset(SUBSET_AID));
        Assert.assertTrue(RegisteredAidCache.isExact(EXACT_AID));

        Assert.assertFalse(RegisteredAidCache.isPrefix(EXACT_AID));
        Assert.assertFalse(RegisteredAidCache.isSubset(EXACT_AID));
        Assert.assertFalse(RegisteredAidCache.isExact(PREFIX_AID));
        Assert.assertFalse(RegisteredAidCache.isExact(SUBSET_AID));
    }

    @Test
    public void testAidConflictResolution_walletRoleEnabledNfcDisabled_foregroundWins() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = false;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                FOREGROUND_SERVICE,
                true,
                List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                NON_PAYMENT_SERVICE,
                true,
                List.of(NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onPreferredForegroundServiceChanged(USER_ID, FOREGROUND_SERVICE);
        RegisteredAidCache.AidResolveInfo resolveInfo
                = mRegisteredAidCache.resolveAid(PAYMENT_AID_1);

        verify(mAidRoutingManager).supportsAidPrefixRouting();
        verify(mAidRoutingManager).supportsAidSubsetRouting();
        Assert.assertEquals(resolveInfo.defaultService.getComponent(), FOREGROUND_SERVICE);
        Assert.assertEquals(mRegisteredAidCache.getPreferredService(),
                new Pair<>(USER_ID, FOREGROUND_SERVICE));
        Assert.assertEquals(resolveInfo.services.size(), 1);
        Assert.assertEquals(resolveInfo.category, CardEmulation.CATEGORY_PAYMENT);
        verifyNoMoreInteractions(mAidRoutingManager);
    }

    @Test
    public void testAidConflictResolution_walletRoleEnabledNfcEnabled_walletWins() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                NON_PAYMENT_SERVICE,
                true,
                List.of(NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);
        RegisteredAidCache.AidResolveInfo paymentResolveInfo
                = mRegisteredAidCache.resolveAid(PAYMENT_AID_1);
        RegisteredAidCache.AidResolveInfo nonPaymentResolveInfo
                = mRegisteredAidCache.resolveAid(NON_PAYMENT_AID_1);

        Assert.assertEquals(paymentResolveInfo.defaultService.getComponent(),
                WALLET_PAYMENT_SERVICE);
        Assert.assertEquals(paymentResolveInfo.services.size(), 1);
        Assert.assertEquals(paymentResolveInfo.category, CardEmulation.CATEGORY_PAYMENT);
        Assert.assertEquals(nonPaymentResolveInfo.defaultService.getComponent(),
                NON_PAYMENT_SERVICE);
        Assert.assertEquals(nonPaymentResolveInfo.services.size(), 1);
        Assert.assertEquals(nonPaymentResolveInfo.category, CardEmulation.CATEGORY_OTHER);
        verify(mAidRoutingManager).configureRouting(mRoutingEntryMapCaptor.capture(),
                eq(false));
        HashMap<String, AidRoutingManager.AidEntry> routingEntries =
                mRoutingEntryMapCaptor.getValue();
        Assert.assertTrue(routingEntries.containsKey(PAYMENT_AID_1));
        Assert.assertTrue(routingEntries.containsKey(NON_PAYMENT_AID_1));
        Assert.assertTrue(routingEntries.get(PAYMENT_AID_1).isOnHost);
        Assert.assertTrue(routingEntries.get(NON_PAYMENT_AID_1).isOnHost);
        Assert.assertNull(routingEntries.get(PAYMENT_AID_1).offHostSE);
        Assert.assertNull(routingEntries.get(NON_PAYMENT_AID_1).offHostSE);
        Assert.assertTrue(mRegisteredAidCache.isRequiresScreenOnServiceExist());
    }

    @Test
    public void testAidConflictResolution_walletRoleEnabledNfcEnabledPreFixAid_walletWins() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(true);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PREFIX_PAYMENT_AID),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                NON_PAYMENT_SERVICE,
                true,
                List.of(NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);
        RegisteredAidCache.AidResolveInfo paymentResolveInfo
                = mRegisteredAidCache.resolveAid(PAYMENT_AID_1);
        RegisteredAidCache.AidResolveInfo nonPaymentResolveInfo
                = mRegisteredAidCache.resolveAid(NON_PAYMENT_AID_1);

        Assert.assertEquals(paymentResolveInfo.defaultService.getComponent(),
                WALLET_PAYMENT_SERVICE);
        Assert.assertEquals(paymentResolveInfo.services.size(), 1);
        Assert.assertEquals(paymentResolveInfo.category, CardEmulation.CATEGORY_PAYMENT);
        Assert.assertEquals(nonPaymentResolveInfo.defaultService.getComponent(),
                NON_PAYMENT_SERVICE);
        Assert.assertEquals(nonPaymentResolveInfo.services.size(), 1);
        Assert.assertEquals(nonPaymentResolveInfo.category, CardEmulation.CATEGORY_OTHER);
    }

    @Test
    public void testAidConflictResolution_walletRoleEnabled_twoServicesOnWallet_firstServiceWins() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE_2,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                false,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                false,
                USER_ID,
                true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);
        RegisteredAidCache.AidResolveInfo resolveInfo
                = mRegisteredAidCache.resolveAid(PAYMENT_AID_1);
        Assert.assertEquals(resolveInfo.defaultService.getComponent(), WALLET_PAYMENT_SERVICE);
        Assert.assertEquals(resolveInfo.services.size(), 2);
        Assert.assertEquals(resolveInfo.category, CardEmulation.CATEGORY_PAYMENT);
    }

    @Test
    public void testAidConflictResolution_walletOtherServiceDisabled_nonDefaultServiceWins() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                false));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);
        RegisteredAidCache.AidResolveInfo resolveInfo
                = mRegisteredAidCache.resolveAid(NON_PAYMENT_AID_1);
        Assert.assertEquals(resolveInfo.defaultService.getComponent(), PAYMENT_SERVICE);
        Assert.assertEquals(resolveInfo.services.size(), 1);
    }

    @Test
    public void testAidConflictResolution_walletOtherServiceDisabled_emptyServices() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                false));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);
        RegisteredAidCache.AidResolveInfo resolveInfo
                = mRegisteredAidCache.resolveAid(NON_PAYMENT_AID_1);
        Assert.assertNull(resolveInfo.defaultService);
        Assert.assertTrue(resolveInfo.services.isEmpty());
    }

    @Test
    public void testOnServicesUpdated_walletRoleEnabled() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                true,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                NON_PAYMENT_SERVICE,
                true,
                List.of(NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_OTHER),
                false,
                true,
                USER_ID,
                true));

        mRegisteredAidCache.onServicesUpdated(USER_ID, apduServiceInfos);

        verify(mAidRoutingManager).supportsAidPrefixRouting();
        verify(mAidRoutingManager).supportsAidSubsetRouting();
        Assert.assertTrue(mRegisteredAidCache.mAidServices.containsKey(PAYMENT_AID_1));
        Assert.assertTrue(mRegisteredAidCache.mAidServices.containsKey(NON_PAYMENT_AID_1));
        Assert.assertEquals(mRegisteredAidCache.mAidServices.get(PAYMENT_AID_1).size(), 2);
        Assert.assertEquals(mRegisteredAidCache.mAidServices.get(NON_PAYMENT_AID_1).size(), 1);
        Assert.assertEquals(mRegisteredAidCache.mAidServices.get(PAYMENT_AID_1).get(0)
                .service.getComponent(), WALLET_PAYMENT_SERVICE);
        Assert.assertEquals(mRegisteredAidCache.mAidServices.get(PAYMENT_AID_1).get(1)
                        .service.getComponent(), PAYMENT_SERVICE);
        verify(mAidRoutingManager).configureRouting(mRoutingEntryMapCaptor.capture(),
                eq(false));
        HashMap<String, AidRoutingManager.AidEntry> routingEntries =
                mRoutingEntryMapCaptor.getValue();
        Assert.assertTrue(routingEntries.containsKey(NON_PAYMENT_AID_1));
        Assert.assertTrue(routingEntries.get(NON_PAYMENT_AID_1).isOnHost);
        Assert.assertNull(routingEntries.get(NON_PAYMENT_AID_1).offHostSE);
        Assert.assertTrue(mRegisteredAidCache.isRequiresScreenOnServiceExist());
    }

    @Test
    public void testOnNfcEnabled() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                false,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT),
                false,
                false,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                NON_PAYMENT_SERVICE,
                true,
                List.of(NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));

        mRegisteredAidCache.generateUserApduServiceInfoLocked(USER_ID, apduServiceInfos);
        mRegisteredAidCache.generateServiceMapLocked(apduServiceInfos);
        mRegisteredAidCache.onNfcEnabled();

        verify(mAidRoutingManager).supportsAidPrefixRouting();
        verify(mAidRoutingManager).supportsAidSubsetRouting();
        verify(mAidRoutingManager).configureRouting(mRoutingEntryMapCaptor.capture(),
                eq(false));
        Assert.assertFalse(mRegisteredAidCache.isRequiresScreenOnServiceExist());
    }

    @Test
    public void testOnNfcDisabled() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        mRegisteredAidCache.onNfcDisabled();

        verify(mAidRoutingManager).supportsAidPrefixRouting();
        verify(mAidRoutingManager).supportsAidSubsetRouting();
        verify(mAidRoutingManager).onNfccRoutingTableCleared();
    }

    @Test
    public void testPollingLoopFilterToForeground_walletRoleEnabled_walletSet() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                FOREGROUND_SERVICE,
                true,
                List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                NON_PAYMENT_SERVICE,
                true,
                List.of(NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));

        mRegisteredAidCache.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);
        mRegisteredAidCache.onPreferredForegroundServiceChanged(USER_ID, FOREGROUND_SERVICE);

        ApduServiceInfo resolvedApdu =
                mRegisteredAidCache.resolvePollingLoopFilterConflict(apduServiceInfos);

        Assert.assertEquals(resolvedApdu, apduServiceInfos.get(1));
    }

    @Test
    public void testPollingLoopFilterToWallet_walletRoleEnabled_walletSet() {
        setWalletRoleFlag(true);
        supportPrefixAndSubset(false);
        mRegisteredAidCache = new RegisteredAidCache(mContext, mWalletRoleObserver,
                mAidRoutingManager);
        mRegisteredAidCache.mNfcEnabled = true;

        List<ApduServiceInfo> apduServiceInfos = new ArrayList<>();
        apduServiceInfos.add(createServiceInfoForAidRouting(
                WALLET_PAYMENT_SERVICE,
                true,
                List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                FOREGROUND_SERVICE,
                true,
                List.of(PAYMENT_AID_1, NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_PAYMENT, CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));
        apduServiceInfos.add(createServiceInfoForAidRouting(
                NON_PAYMENT_SERVICE,
                true,
                List.of(NON_PAYMENT_AID_1),
                List.of(CardEmulation.CATEGORY_OTHER),
                false,
                false,
                USER_ID,
                true));

        mRegisteredAidCache.mDefaultWalletHolderPackageName = WALLET_HOLDER_PACKAGE_NAME;

        ApduServiceInfo resolvedApdu =
                mRegisteredAidCache.resolvePollingLoopFilterConflict(apduServiceInfos);

        Assert.assertEquals(resolvedApdu, apduServiceInfos.get(0));
    }

    private void setWalletRoleFlag(boolean flag) {
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(flag);
    }

    private void supportPrefixAndSubset(boolean support) {
        when(mAidRoutingManager.supportsAidPrefixRouting()).thenReturn(support);
        when(mAidRoutingManager.supportsAidSubsetRouting()).thenReturn(support);
    }

    private static ApduServiceInfo createServiceInfoForAidRouting(ComponentName componentName,
            boolean onHost,
            List<String> aids,List<String> categories, boolean requiresUnlock, boolean requiresScreenOn,
            int uid, boolean isCategoryOtherServiceEnabled) {
        ApduServiceInfo apduServiceInfo = Mockito.mock(ApduServiceInfo.class);
        when(apduServiceInfo.isOnHost()).thenReturn(onHost);
        when(apduServiceInfo.getAids()).thenReturn(aids);
        when(apduServiceInfo.getUid()).thenReturn(uid);
        when(apduServiceInfo.requiresUnlock()).thenReturn(requiresUnlock);
        when(apduServiceInfo.requiresScreenOn()).thenReturn(requiresScreenOn);
        when(apduServiceInfo.isCategoryOtherServiceEnabled())
                .thenReturn(isCategoryOtherServiceEnabled);
        when(apduServiceInfo.getComponent()).thenReturn(componentName);
        for (int i = 0; i < aids.size(); i++) {
            String aid = aids.get(i);
            String category = categories.get(i);
            when(apduServiceInfo.getCategoryForAid(eq(aid))).thenReturn(category);
        }
        return apduServiceInfo;
    }

}
