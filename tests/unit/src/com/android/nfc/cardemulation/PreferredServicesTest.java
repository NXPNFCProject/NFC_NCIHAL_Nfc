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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ContextWrapper;
import android.database.ContentObserver;
import android.nfc.Constants;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.net.Uri;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.provider.Settings;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.ForegroundUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
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

@RunWith(AndroidJUnit4.class)
public class PreferredServicesTest {

    private PreferredServices services;
    private MockitoSession mStaticMockSession;
    private Context mContext;

    @Mock
    private RegisteredServicesCache mServicesCache;
    @Mock
    private PreferredServices.Callback mCallback;
    @Mock
    private RegisteredAidCache mAidCache;
    @Mock
    private WalletRoleObserver mObserver;
    @Mock
    private ForegroundUtils mForegroundUtils;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private UserManager mUserManager;
    @Mock
    private ActivityManager mActivityManager;
    @Mock
    private ApduServiceInfo mServiceInfoPayment;
    @Mock
    private ApduServiceInfo mServiceInfoNonPayment;
    @Mock
    private UserHandle mUserHandle;
    @Mock
    private PrintWriter mPrintWriter;
    @Mock
    private RegisteredAidCache.AidResolveInfo mResolveInfo;
    @Captor
    private ArgumentCaptor<Integer> userIdCaptor;
    @Captor
    private ArgumentCaptor<ComponentName> candidateCaptor;
    @Captor
    private ArgumentCaptor<ContentObserver> mSettingsObserverCaptor;

    private static final String WALLET_HOLDER_PACKAGE_NAME = "com.android.test.walletroleholder";
    private static final ComponentName TEST_COMPONENT
            = new ComponentName(WALLET_HOLDER_PACKAGE_NAME,
            "com.android.test.walletroleholder.WalletRoleHolderApduService");
    private static final int USER_ID = 1;
    private static final int FOREGROUND_UID = 7;

    @Before
    public void setUp() throws Exception {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(ForegroundUtils.class)
                .mockStatic(ActivityManager.class)
                .mockStatic(UserHandle.class)
                .mockStatic(Settings.Secure.class)
                .mockStatic(ComponentName.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);
        mContext = new ContextWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override
            public Object getSystemService(String name) {
                if (Context.ACTIVITY_SERVICE.equals(name)) {
                    return (ActivityManager) mActivityManager;
                } else if (Context.USER_SERVICE.equals(name)) {
                    return (UserManager) mUserManager;
                } else {
                    return null;
                }
            }

            @Override
            public Context createContextAsUser(UserHandle user, int flags) {
                return mContext;
            }

            @Override
            public ContentResolver getContentResolver() {
                return mContentResolver;
            }
        };

        when(ForegroundUtils.getInstance(any(ActivityManager.class))).thenReturn(mForegroundUtils);
        when(ActivityManager.getCurrentUser()).thenReturn(USER_ID);
        doNothing().when(mContentResolver)
                .registerContentObserverAsUser(any(Uri.class), anyBoolean(), any(),
                        any(UserHandle.class));
        doNothing().when(mCallback).onPreferredPaymentServiceChanged(anyInt(), any());
        when(Settings.Secure.getString(any(ContentResolver.class), anyString()))
                .thenReturn("com.android.test.walletroleholder/com.android"
                        + ".test.walletroleholder.WalletRoleHolderApduService");
        when(Settings.Secure.getInt(any(ContentResolver.class), anyString())).thenReturn(USER_ID);
        when(UserHandle.getUserHandleForUid(anyInt())).thenReturn(mUserHandle);
        when(UserHandle.of(anyInt())).thenReturn(mUserHandle);
        when(mUserHandle.getIdentifier()).thenReturn(FOREGROUND_UID);
        when(mObserver.getDefaultWalletRoleHolder(anyInt())).thenReturn(null);
        when(mServiceInfoPayment.getComponent()).thenReturn(TEST_COMPONENT);
        when(mServiceInfoPayment.getAids()).thenReturn(getAids());
        when(mServiceInfoPayment
                .getCategoryForAid(anyString())).thenReturn(CardEmulation.CATEGORY_PAYMENT);
        when(mServiceInfoPayment.hasCategory(eq(CardEmulation.CATEGORY_PAYMENT)))
                .thenReturn(true);
        when(mServiceInfoNonPayment.hasCategory(eq(CardEmulation.CATEGORY_PAYMENT))).thenReturn(
                false);
        when(mServiceInfoNonPayment.getAids()).thenReturn(getAids());
        when(mAidCache.resolveAid(anyString())).thenReturn(mResolveInfo);
        when(mUserManager.getEnabledProfiles()).thenReturn(getUserHandles());
        // Wallet role feature is enabled by default; several test cases set this value to false
        when(mObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testConstructorWhenWalletRoleFeatureIsNotEnabled() {
        when(mObserver.isWalletRoleFeatureEnabled()).thenReturn(false);

        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);

        assertThat(services.mContext).isEqualTo(mContext);
        assertThat(services.mWalletRoleObserver).isEqualTo(mObserver);
        assertThat(services.mForegroundUtils).isEqualTo(mForegroundUtils);
        assertThat(services.mServiceCache).isEqualTo(mServicesCache);
        assertThat(services.mAidCache).isEqualTo(mAidCache);
        assertThat(services.mCallback).isEqualTo(mCallback);
        assertThat(services.mSettingsObserver).isNotNull();
        verify(mContentResolver, times(2))
                .registerContentObserverAsUser(any(), anyBoolean(), any(), any(UserHandle.class));
        verify(mUserManager).getEnabledProfiles();
        verify(mObserver, never()).getDefaultWalletRoleHolder(anyInt());
    }

    @Test
    public void testConstructorWhenWalletRoleFeatureIsEnabled() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);

        assertThat(services.mContext).isEqualTo(mContext);
        assertThat(services.mWalletRoleObserver).isEqualTo(mObserver);
        assertThat(services.mForegroundUtils).isEqualTo(mForegroundUtils);
        assertThat(services.mServiceCache).isEqualTo(mServicesCache);
        assertThat(services.mAidCache).isEqualTo(mAidCache);
        assertThat(services.mCallback).isEqualTo(mCallback);
        assertThat(services.mSettingsObserver).isNotNull();
        verify(mContentResolver, times(2))
                .registerContentObserverAsUser(any(), anyBoolean(), any(), any(UserHandle.class));
        verify(mUserManager).getEnabledProfiles();
        verify(mObserver).getDefaultWalletRoleHolder(anyInt());
        assertThat(services.mDefaultWalletHolderPaymentService).isNull();
        verify(mCallback).onPreferredPaymentServiceChanged(anyInt(), any());
    }

    @Test
    public void testOnWalletRoleHolderChangedWithNullPackageName() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);

        services.onWalletRoleHolderChanged(null, USER_ID);

        verify(mCallback, times(2))
                .onPreferredPaymentServiceChanged(userIdCaptor.capture(),
                        candidateCaptor.capture());
        List<Integer> userIds = userIdCaptor.getAllValues();
        assertThat(userIds.get(0)).isEqualTo(USER_ID);
        assertThat(userIds.get(1)).isEqualTo(USER_ID);
        List<ComponentName> candidates = candidateCaptor.getAllValues();
        assertThat(candidates.get(0)).isNull();
        assertThat(candidates.get(1)).isNull();
        assertThat(services.mDefaultWalletHolderPaymentService).isNull();
    }

    @Test
    public void testOnWalletRoleHolderChangedWithExistingPackageNameAndExistingServiceInfos() {
        when(mServicesCache.getInstalledServices(eq(USER_ID))).thenReturn(getPaymentServices());
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);

        services.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);

        assertThat(services.mUserIdDefaultWalletHolder).isEqualTo(USER_ID);
        verify(mCallback, times(2))
                .onPreferredPaymentServiceChanged(userIdCaptor.capture(),
                        candidateCaptor.capture());
        List<Integer> userIds = userIdCaptor.getAllValues();
        assertThat(userIds.get(0)).isEqualTo(USER_ID);
        assertThat(userIds.get(1)).isEqualTo(USER_ID);
        List<ComponentName> candidates = candidateCaptor.getAllValues();
        assertThat(candidates.get(0)).isNull();
        assertThat(candidates.get(1)).isEqualTo(TEST_COMPONENT);
        assertThat(services.mDefaultWalletHolderPaymentService).isEqualTo(TEST_COMPONENT);
    }

    @Test
    public void testOnWalletRoleHolderChangedWithExistingPackageNameAndNoServiceInfo() {
        ArrayList<ApduServiceInfo> emptyList = new ArrayList<>();
        when(mServicesCache.getInstalledServices(eq(USER_ID))).thenReturn(emptyList);
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);

        services.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);

        assertThat(services.mUserIdDefaultWalletHolder).isEqualTo(USER_ID);
        verify(mCallback).onPreferredPaymentServiceChanged(anyInt(), any());
        assertThat(services.mDefaultWalletHolderPaymentService).isNull();
    }

    @Test
    public void testOnWalletRoleHolderChangedWithIncorrectPackageName() {
        when(mServicesCache.getInstalledServices(eq(USER_ID))).thenReturn(getPaymentServices());
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);

        services.onWalletRoleHolderChanged(/* defaultWalletHolderPackageName = */ "", USER_ID);

        assertThat(services.mUserIdDefaultWalletHolder).isEqualTo(USER_ID);
        verify(mCallback).onPreferredPaymentServiceChanged(anyInt(), any());
        assertThat(services.mDefaultWalletHolderPaymentService).isNull();
    }

    @Test
    public void testSetDefaultForNextTapWithNonNullService_NotifyChange() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mForegroundUid = FOREGROUND_UID;

        boolean result = services.setDefaultForNextTap(USER_ID, TEST_COMPONENT);

        assertThat(result).isTrue();
        assertThat(services.mNextTapDefault).isEqualTo(TEST_COMPONENT);
        assertThat(services.mNextTapDefaultUserId).isEqualTo(USER_ID);
        assertThat(services.mForegroundCurrent).isEqualTo(TEST_COMPONENT);
        assertThat(services.mForegroundCurrentUid).isEqualTo(FOREGROUND_UID);
        verify(mCallback)
                .onPreferredForegroundServiceChanged(userIdCaptor.capture(),
                        candidateCaptor.capture());
        assertThat(userIdCaptor.getValue()).isEqualTo(USER_ID);
        assertThat(candidateCaptor.getValue()).isEqualTo(TEST_COMPONENT);
    }

    @Test
    public void testSetDefaultForNextTapWithNullService_NoChange() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mForegroundUid = FOREGROUND_UID;
        services.mForegroundRequested = null;
        services.mForegroundCurrent = null;

        boolean result = services.setDefaultForNextTap(USER_ID, /* service = */ null);

        assertThat(result).isTrue();
        assertThat(services.mNextTapDefault).isNull();
        assertThat(services.mNextTapDefaultUserId).isEqualTo(USER_ID);
        assertThat(services.mForegroundCurrent).isEqualTo(null);
        assertThat(services.mForegroundCurrentUid).isEqualTo(0);
        verify(mCallback, never()).onPreferredForegroundServiceChanged(anyInt(), any());
    }

    @Test
    public void testSetDefaultForNextTapWithNonNullService_NoChange() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mForegroundCurrent = TEST_COMPONENT;

        boolean result = services.setDefaultForNextTap(FOREGROUND_UID, TEST_COMPONENT);

        assertThat(result).isTrue();
        assertThat(services.mNextTapDefault).isEqualTo(TEST_COMPONENT);
        assertThat(services.mNextTapDefaultUserId).isEqualTo(FOREGROUND_UID);
        assertThat(services.mForegroundCurrent).isEqualTo(TEST_COMPONENT);
        assertThat(services.mForegroundCurrentUid).isEqualTo(0);
        verify(mCallback, never()).onPreferredForegroundServiceChanged(anyInt(), any());
    }

    @Test
    public void testSetDefaultForNextTapWithNullService_NotifyChange() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mForegroundUid = FOREGROUND_UID;
        services.mForegroundRequested = null;
        services.mForegroundCurrent = TEST_COMPONENT;

        boolean result = services.setDefaultForNextTap(USER_ID, /* service = */ null);

        assertThat(result).isTrue();
        assertThat(services.mNextTapDefault).isNull();
        assertThat(services.mNextTapDefaultUserId).isEqualTo(USER_ID);
        assertThat(services.mForegroundCurrent).isEqualTo(null);
        assertThat(services.mForegroundCurrentUid).isEqualTo(FOREGROUND_UID);
        verify(mCallback)
                .onPreferredForegroundServiceChanged(userIdCaptor.capture(),
                        candidateCaptor.capture());
        assertThat(userIdCaptor.getValue()).isEqualTo(FOREGROUND_UID);
        assertThat(candidateCaptor.getValue()).isNull();
    }

    @Test
    public void testOnServicesUpdatedWithNullForeground_NoChange() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mForegroundCurrent = null;

        services.onServicesUpdated();

        assertThat(services.mForegroundRequested).isNull();
        assertThat(services.mForegroundUid).isEqualTo(0);
        assertThat(services.mForegroundCurrentUid).isEqualTo(0);
    }

    @Test
    public void testOnServicesUpdatedWithNonNullForegroundAndPaymentServiceInfo_CommitsChange() {
        when(mObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(mServicesCache.getInstalledServices(eq(USER_ID))).thenReturn(getPaymentServices());
        when(mServicesCache.getService(anyInt(), any())).thenReturn(mServiceInfoPayment);
        when(mObserver.getDefaultWalletRoleHolder(eq(USER_ID))).thenReturn(
                WALLET_HOLDER_PACKAGE_NAME);
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mUserIdDefaultWalletHolder = USER_ID;
        services.mForegroundCurrent = TEST_COMPONENT;
        services.mForegroundCurrentUid = FOREGROUND_UID;
        services.mPaymentDefaults.currentPreferred = null;
        services.mPaymentDefaults.preferForeground = false;

        services.onServicesUpdated();

        assertThat(services.mForegroundRequested).isNull();
        assertThat(services.mForegroundUid).isEqualTo(Process.INVALID_UID);
        assertThat(services.mForegroundCurrentUid).isEqualTo(Process.INVALID_UID);
        assertWalletRoleHolderUpdated();
    }

    @Test
    public void testOnServicesUpdatedWithNonNullForegroundAndNonPaymentServiceInfo_CommitsChange() {
        when(mObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(mServicesCache.getInstalledServices(eq(USER_ID))).thenReturn(getPaymentServices());
        when(mServicesCache.getService(anyInt(), any())).thenReturn(mServiceInfoNonPayment);
        when(mObserver.getDefaultWalletRoleHolder(eq(USER_ID))).thenReturn(
                WALLET_HOLDER_PACKAGE_NAME);
        mResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        mResolveInfo.defaultService = mServiceInfoNonPayment;
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mUserIdDefaultWalletHolder = USER_ID;
        services.mForegroundCurrent = TEST_COMPONENT;
        services.mForegroundCurrentUid = FOREGROUND_UID;
        services.mPaymentDefaults.currentPreferred = null;
        services.mPaymentDefaults.mUserHandle = mUserHandle;
        services.mPaymentDefaults.preferForeground = false;

        services.onServicesUpdated();

        assertThat(services.mForegroundRequested).isNull();
        assertThat(services.mForegroundUid).isEqualTo(Process.INVALID_UID);
        assertThat(services.mForegroundCurrentUid).isEqualTo(Process.INVALID_UID);
        assertWalletRoleHolderUpdated();
    }

    @Test
    public void testOnServicesUpdatedWithNonNullForegroundAndNonPaymentServiceInfo_NoChange() {
        when(mObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(mServicesCache.getInstalledServices(eq(USER_ID))).thenReturn(getPaymentServices());
        when(mServicesCache.getService(anyInt(), any())).thenReturn(mServiceInfoNonPayment);
        when(mObserver.getDefaultWalletRoleHolder(eq(USER_ID))).thenReturn(
                WALLET_HOLDER_PACKAGE_NAME);
        mResolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
        mResolveInfo.defaultService = null;
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mUserIdDefaultWalletHolder = USER_ID;
        services.mForegroundCurrent = TEST_COMPONENT;
        services.mForegroundCurrentUid = FOREGROUND_UID;
        services.mPaymentDefaults.currentPreferred = null;
        services.mPaymentDefaults.mUserHandle = mUserHandle;
        services.mPaymentDefaults.preferForeground = false;

        services.onServicesUpdated();

        assertThat(services.mForegroundRequested).isNull();
        assertThat(services.mForegroundUid).isEqualTo(0);
        assertThat(services.mForegroundCurrentUid).isEqualTo(FOREGROUND_UID);
        assertWalletRoleHolderUpdated();
    }

    @Test
    public void testRegisterPreferredForegroundServiceWithSuccess() {
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt())).thenReturn(true);
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mPaymentDefaults.currentPreferred = TEST_COMPONENT;

        boolean result = services.registerPreferredForegroundService(TEST_COMPONENT, USER_ID);

        assertThat(result).isTrue();
        assertThat(services.mForegroundRequested).isEqualTo(TEST_COMPONENT);
        assertThat(services.mForegroundUid).isEqualTo(USER_ID);
    }

    @Test
    public void testRegisterPreferredForegroundServiceWithFailure() {
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt())).thenReturn(false);
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mPaymentDefaults.currentPreferred = TEST_COMPONENT;

        boolean result = services.registerPreferredForegroundService(TEST_COMPONENT, USER_ID);

        assertThat(result).isFalse();
        assertThat(services.mForegroundRequested).isNull();
        assertThat(services.mForegroundUid).isEqualTo(0);
    }

    @Test
    public void testUnregisteredPreferredForegroundServiceInForeground_ReturnsSuccess() {
        when(mForegroundUtils.isInForeground(anyInt())).thenReturn(true);
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mForegroundUid = FOREGROUND_UID;

        boolean result = services.unregisteredPreferredForegroundService(FOREGROUND_UID);

        assertThat(result).isTrue();
        assertThat(services.mForegroundRequested).isNull();
        assertThat(services.mForegroundUid).isEqualTo(Process.INVALID_UID);
    }

    @Test
    public void testUnregisteredPreferredForegroundServiceInForeground_ReturnsFailure() {
        when(mForegroundUtils.isInForeground(anyInt())).thenReturn(true);
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mForegroundUid = FOREGROUND_UID;

        boolean result = services.unregisteredPreferredForegroundService(USER_ID);

        assertThat(result).isFalse();
        assertThat(services.mForegroundRequested).isNull();
        assertThat(services.mForegroundUid).isEqualTo(FOREGROUND_UID);
    }

    @Test
    public void testUnregisteredPreferredForegroundServiceNotInForeground_ReturnsFailure() {
        when(mForegroundUtils.isInForeground(anyInt())).thenReturn(false);
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);

        boolean result = services.unregisteredPreferredForegroundService(USER_ID);

        assertThat(result).isFalse();
        assertThat(services.mForegroundRequested).isNull();
        assertThat(services.mForegroundUid).isEqualTo(0);
    }

    @Test
    public void testOnUidToBackground_SuccessfullyUnregistersService() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mForegroundUid = FOREGROUND_UID;

        services.onUidToBackground(FOREGROUND_UID);

        assertThat(services.mForegroundUid).isEqualTo(Process.INVALID_UID);
    }

    @Test
    public void testOnUidToBackground_FailsToUnregisterService() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mForegroundUid = FOREGROUND_UID;

        services.onUidToBackground(USER_ID);

        assertThat(services.mForegroundUid).isEqualTo(FOREGROUND_UID);
    }

    @Test
    public void testOnHostEmulationActivated() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mNextTapDefault = TEST_COMPONENT;

        services.onHostEmulationActivated();

        assertThat(services.mClearNextTapDefault).isTrue();
    }

    @Test
    public void testOnHostEmulationDeactivated() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mClearNextTapDefault = true;
        services.mNextTapDefault = TEST_COMPONENT;

        services.onHostEmulationDeactivated();

        assertThat(services.mNextTapDefault).isNull();
        assertThat(services.mClearNextTapDefault).isFalse();
    }

    @Test
    public void testOnUserSwitchedWithChange() {
        when(mObserver.isWalletRoleFeatureEnabled()).thenReturn(false);
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mPaymentDefaults.preferForeground = false;
        services.mPaymentDefaults.currentPreferred = TEST_COMPONENT;

        services.onUserSwitched(USER_ID);

        assertThat(services.mPaymentDefaults.preferForeground).isTrue();
        assertThat(services.mPaymentDefaults.settingsDefault).isEqualTo(null);
        assertThat(services.mPaymentDefaults.currentPreferred).isEqualTo(null);
        assertThat(services.mPaymentDefaults.mUserHandle).isEqualTo(mUserHandle);
        verify(mCallback)
                .onPreferredPaymentServiceChanged(userIdCaptor.capture(),
                        candidateCaptor.capture());
        assertThat(userIdCaptor.getValue()).isEqualTo(FOREGROUND_UID);
        assertThat(candidateCaptor.getValue()).isEqualTo(null);
    }

    @Test
    public void testOnUserSwitchedWithNoChange() throws Exception {
        when(mUserManager.getEnabledProfiles()).thenReturn(getUserHandles());
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mPaymentDefaults.preferForeground = false;
        services.mPaymentDefaults.currentPreferred = null;
        verify(mCallback).onPreferredPaymentServiceChanged(anyInt(), any());

        services.onUserSwitched(USER_ID);

        assertThat(services.mPaymentDefaults.preferForeground).isTrue();
        assertThat(services.mPaymentDefaults.settingsDefault).isEqualTo(null);
        assertThat(services.mPaymentDefaults.currentPreferred).isEqualTo(null);
        assertThat(services.mPaymentDefaults.mUserHandle).isEqualTo(null);
        verifyNoMoreInteractions(mCallback);
    }

    @Test
    public void testPackageHasPreferredServiceWithNullPackageName_ReturnsFalse() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);

        boolean result = services.packageHasPreferredService(/* packageName = */ null);

        assertThat(result).isFalse();
    }

    @Test
    public void testPackageHasPreferredServiceWithMatchingPackageName_ReturnsTrue() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mPaymentDefaults.currentPreferred = TEST_COMPONENT;

        boolean result = services.packageHasPreferredService(WALLET_HOLDER_PACKAGE_NAME);

        assertThat(result).isTrue();
    }

    @Test
    public void testPackageHasPreferredServiceWithNonMatchingPackageName_ReturnsFalse() {
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);

        boolean result = services.packageHasPreferredService(WALLET_HOLDER_PACKAGE_NAME);

        assertThat(result).isFalse();
    }

    @Test
    public void testDump() {
        when(mObserver.isWalletRoleFeatureEnabled()).thenReturn(false);
        when(mUserManager.getUserName()).thenReturn("");
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);

        services.dump(null, mPrintWriter, null);

        verify(mPrintWriter, times(8)).println(anyString());
    }

    @Test
    public void testDump_withWalletRole() {
        when(mObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(mUserManager.getUserName()).thenReturn("");
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);

        services.dump(null, mPrintWriter, null);

        verify(mPrintWriter, times(7)).println(anyString());
    }

    private void assertWalletRoleHolderUpdated() {
        verify(mObserver, times(4)).isWalletRoleFeatureEnabled();
        verify(mObserver, times(2)).getDefaultWalletRoleHolder(eq(USER_ID));
        assertThat(services.mUserIdDefaultWalletHolder).isEqualTo(USER_ID);
        verify(mCallback)
                .onPreferredPaymentServiceChanged(userIdCaptor.capture(),
                        candidateCaptor.capture());
        List<Integer> userIds = userIdCaptor.getAllValues();
        assertThat(userIds.get(0)).isEqualTo(USER_ID);
        List<ComponentName> candidates = candidateCaptor.getAllValues();
        assertThat(candidates.get(0)).isEqualTo(TEST_COMPONENT);
        assertThat(services.mDefaultWalletHolderPaymentService).isEqualTo(TEST_COMPONENT);
    }

    private ArrayList<String> getAids() {
        ArrayList<String> aids = new ArrayList<>();
        aids.add("aid");
        return aids;
    }

    private ArrayList<ApduServiceInfo> getPaymentServices() {
        ArrayList<ApduServiceInfo> serviceInfos = new ArrayList<>();
        serviceInfos.add(mServiceInfoPayment);
        return serviceInfos;
    }

    private ArrayList<UserHandle> getUserHandles() {
        ArrayList<UserHandle> list = new ArrayList<>();
        list.add(mUserHandle);
        return list;
    }

    @Test
    public void testSettingObserverOnChange() {
        when(mUserManager.getUserName()).thenReturn("");
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        verify(mContentResolver, times(2)).registerContentObserverAsUser(
                any(), anyBoolean(), mSettingsObserverCaptor.capture(), any());
        Uri uri = Settings.Secure.getUriFor(
                Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT);
        mSettingsObserverCaptor.getValue().onChange(true, uri);
        verify(mObserver, atLeast(1)).isWalletRoleFeatureEnabled();
        assertTrue(services.mPaymentDefaults.preferForeground);
    }

    @Test
    public void testSettingObserverOnChange_compute() {
        when(mUserManager.getUserName()).thenReturn("");
        services = new PreferredServices(mContext, mServicesCache, mAidCache, mObserver, mCallback);
        services.mForegroundUid = FOREGROUND_UID;
        boolean result = services.setDefaultForNextTap(USER_ID, TEST_COMPONENT);
        assertThat(result).isTrue();
        verify(mContentResolver, times(2)).registerContentObserverAsUser(
                any(), anyBoolean(), mSettingsObserverCaptor.capture(), any());
        Uri uri = Settings.Secure.getUriFor(
                Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT);
        mSettingsObserverCaptor.getValue().onChange(true, uri);
        verify(mObserver, atLeast(1)).isWalletRoleFeatureEnabled();
        assertTrue(services.mPaymentDefaults.preferForeground);
        verify(mCallback).onPreferredForegroundServiceChanged(anyInt(), any());
    }
}