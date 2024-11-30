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

import static com.android.nfc.NfcService.PREF_NFC_ON;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.backup.BackupManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.nfc.NfcAdapter;
import android.nfc.NfcAntennaInfo;
import android.nfc.NfcServiceManager;
import android.nfc.tech.Ndef;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.sysprop.NfcProperties;

import androidx.test.ext.junit.runners.AndroidJUnit4;


import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@RunWith(AndroidJUnit4.class)
public final class NfcServiceTest {
    private static final String PKG_NAME = "com.test";
    private static final int[] ANTENNA_POS_X = { 5 };
    private static final int[] ANTENNA_POS_Y = { 6 };
    private static final int ANTENNA_DEVICE_WIDTH = 9;
    private static final int ANTENNA_DEVICE_HEIGHT = 10;
    private static final boolean ANTENNA_DEVICE_FOLDABLE = true;
    @Mock Application mApplication;
    @Mock NfcInjector mNfcInjector;
    @Mock DeviceHost mDeviceHost;
    @Mock NfcEventLog mNfcEventLog;
    @Mock NfcDispatcher mNfcDispatcher;
    @Mock NfcUnlockManager mNfcUnlockManager;
    @Mock SharedPreferences mPreferences;
    @Mock SharedPreferences.Editor mPreferencesEditor;
    @Mock PowerManager mPowerManager;
    @Mock PackageManager mPackageManager;
    @Mock ScreenStateHelper mScreenStateHelper;
    @Mock Resources mResources;
    @Mock KeyguardManager mKeyguardManager;
    @Mock UserManager mUserManager;
    @Mock ActivityManager mActivityManager;
    @Mock NfcServiceManager.ServiceRegisterer mNfcManagerRegisterer;
    @Mock NfcDiagnostics mNfcDiagnostics;
    @Mock DeviceConfigFacade mDeviceConfigFacade;
    @Mock ContentResolver mContentResolver;
    @Mock Bundle mUserRestrictions;
    @Mock BackupManager mBackupManager;
    @Mock AlarmManager mAlarmManager;
    @Captor ArgumentCaptor<DeviceHost.DeviceHostListener> mDeviceHostListener;
    @Captor ArgumentCaptor<BroadcastReceiver> mGlobalReceiver;
    @Captor ArgumentCaptor<AlarmManager.OnAlarmListener> mAlarmListener;
    @Captor ArgumentCaptor<IBinder> mIBinderArgumentCaptor;
    TestLooper mLooper;
    NfcService mNfcService;
    private MockitoSession mStaticMockSession;

    @Before
    public void setUp() {
        mLooper = new TestLooper();
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(NfcProperties.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);
        AsyncTask.setDefaultExecutor(new HandlerExecutor(new Handler(mLooper.getLooper())));

        when(mNfcInjector.getMainLooper()).thenReturn(mLooper.getLooper());
        when(mNfcInjector.getNfcEventLog()).thenReturn(mNfcEventLog);
        when(mNfcInjector.makeDeviceHost(any())).thenReturn(mDeviceHost);
        when(mNfcInjector.getScreenStateHelper()).thenReturn(mScreenStateHelper);
        when(mNfcInjector.getNfcDiagnostics()).thenReturn(mNfcDiagnostics);
        when(mNfcInjector.getDeviceConfigFacade()).thenReturn(mDeviceConfigFacade);
        when(mNfcInjector.getNfcManagerRegisterer()).thenReturn(mNfcManagerRegisterer);
        when(mNfcInjector.getBackupManager()).thenReturn(mBackupManager);
        when(mNfcInjector.getNfcDispatcher()).thenReturn(mNfcDispatcher);
        when(mNfcInjector.getNfcUnlockManager()).thenReturn(mNfcUnlockManager);
        when(mApplication.getSharedPreferences(anyString(), anyInt())).thenReturn(mPreferences);
        when(mApplication.getSystemService(PowerManager.class)).thenReturn(mPowerManager);
        when(mApplication.getSystemService(UserManager.class)).thenReturn(mUserManager);
        when(mApplication.getSystemService(ActivityManager.class)).thenReturn(mActivityManager);
        when(mApplication.getSystemService(KeyguardManager.class)).thenReturn(mKeyguardManager);
        when(mApplication.getSystemService(AlarmManager.class)).thenReturn(mAlarmManager);
        when(mApplication.getPackageManager()).thenReturn(mPackageManager);
        when(mApplication.getResources()).thenReturn(mResources);
        when(mApplication.createContextAsUser(any(), anyInt())).thenReturn(mApplication);
        when(mApplication.getContentResolver()).thenReturn(mContentResolver);
        when(mUserManager.getUserRestrictions()).thenReturn(mUserRestrictions);
        when(mResources.getStringArray(R.array.nfc_allow_list)).thenReturn(new String[0]);
        when(mPreferences.edit()).thenReturn(mPreferencesEditor);
        when(mPowerManager.newWakeLock(anyInt(), anyString()))
                .thenReturn(mock(PowerManager.WakeLock.class));
        when(mResources.getIntArray(R.array.antenna_x)).thenReturn(new int[0]);
        when(mResources.getIntArray(R.array.antenna_y)).thenReturn(new int[0]);
        when(NfcProperties.info_antpos_X()).thenReturn(List.of());
        when(NfcProperties.info_antpos_Y()).thenReturn(List.of());
        createNfcService();
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    private void createNfcService() {
        mNfcService = new NfcService(mApplication, mNfcInjector);
        mLooper.dispatchAll();
        verify(mNfcInjector).makeDeviceHost(mDeviceHostListener.capture());
        verify(mApplication).registerReceiverForAllUsers(
                mGlobalReceiver.capture(),
                argThat(intent -> intent.hasAction(Intent.ACTION_SCREEN_ON)), any(), any());
        clearInvocations(mDeviceHost, mNfcInjector, mApplication);
    }

    private void enableAndVerify() throws Exception {
        when(mDeviceHost.initialize()).thenReturn(true);
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        mNfcService.mNfcAdapter.enable(PKG_NAME);
        verify(mPreferencesEditor).putBoolean(PREF_NFC_ON, true);
        mLooper.dispatchAll();
        verify(mDeviceHost).initialize();
        clearInvocations(mDeviceHost, mPreferencesEditor);
    }

    private void disableAndVerify() throws Exception {
        when(mDeviceHost.deinitialize()).thenReturn(true);
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(false);
        mNfcService.mNfcAdapter.disable(true, PKG_NAME);
        verify(mPreferencesEditor).putBoolean(PREF_NFC_ON, false);
        mLooper.dispatchAll();
        verify(mDeviceHost).deinitialize();
        verify(mNfcDispatcher).resetForegroundDispatch();
        clearInvocations(mDeviceHost, mPreferencesEditor, mNfcDispatcher);
    }


    @Test
    public void testEnable() throws Exception {
        enableAndVerify();
    }

    @Test
    public void testDisable() throws Exception {
        enableAndVerify();
        disableAndVerify();
    }

    @Test
    public void testBootupWithNfcOn() throws Exception {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        mNfcService = new NfcService(mApplication, mNfcInjector);
        mLooper.dispatchAll();
        verify(mNfcInjector).makeDeviceHost(mDeviceHostListener.capture());
        verify(mApplication).registerReceiverForAllUsers(
                mGlobalReceiver.capture(),
                argThat(intent -> intent.hasAction(Intent.ACTION_SCREEN_ON)), any(), any());
        verify(mDeviceHost).initialize();
    }

    @Test
    public void testBootupWithNfcOn_WhenOemExtensionEnabled() throws Exception {
        when(mResources.getBoolean(R.bool.enable_oem_extension)).thenReturn(true);
        createNfcService();

        verifyNoMoreInteractions(mDeviceHost);
    }

    @Test
    public void testBootupWithNfcOn_WhenOemExtensionEnabled_ThenAllowBoot() throws Exception {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        when(mResources.getBoolean(R.bool.enable_oem_extension)).thenReturn(true);
        createNfcService();

        mNfcService.mNfcAdapter.allowBoot();
        mLooper.dispatchAll();
        verify(mDeviceHost).initialize();
    }

    @Test
    public void testBootupWithNfcOn_WhenOemExtensionEnabled_ThenTimeout() throws Exception {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        when(mResources.getBoolean(R.bool.enable_oem_extension)).thenReturn(true);
        createNfcService();
        verify(mAlarmManager).setExact(
                anyInt(), anyLong(), anyString(), mAlarmListener.capture(), any());

        mAlarmListener.getValue().onAlarm();
        mLooper.dispatchAll();
        verify(mDeviceHost).initialize();
    }

    @Test
    public void testSetObserveMode_nfcDisabled() throws Exception {
        mNfcService.mNfcAdapter.disable(true, PKG_NAME);

        Assert.assertFalse(mNfcService.mNfcAdapter.setObserveMode(true, null));
    }

    @Test
    public void testIsObserveModeEnabled_nfcDisabled() throws Exception {
        mNfcService.mNfcAdapter.disable(true, PKG_NAME);

        Assert.assertFalse(mNfcService.mNfcAdapter.isObserveModeEnabled());
    }

    @Test
    public void testIsObserveModeSupported_nfcDisabled() throws Exception {
        mNfcService.mNfcAdapter.disable(true, PKG_NAME);

        Assert.assertFalse(mNfcService.mNfcAdapter.isObserveModeSupported());
    }

    @Test
    public void testEnableNfc_changeStateRestricted() throws Exception {
        when(mUserRestrictions.getBoolean(
                UserManager.DISALLOW_CHANGE_NEAR_FIELD_COMMUNICATION_RADIO)).thenReturn(true);
        mNfcService.mNfcAdapter.enable(PKG_NAME);
        assert(mNfcService.mState == NfcAdapter.STATE_OFF);
    }

    @Test
    public void testDisableNfc_changeStateRestricted() throws Exception {
        enableAndVerify();
        when(mUserRestrictions.getBoolean(
                UserManager.DISALLOW_CHANGE_NEAR_FIELD_COMMUNICATION_RADIO)).thenReturn(true);
        mNfcService.mNfcAdapter.disable(true, PKG_NAME);
        assert(mNfcService.mState == NfcAdapter.STATE_ON);
    }

    @Test
    public void testHandlerResumePolling() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        handler.handleMessage(handler.obtainMessage(NfcService.MSG_RESUME_POLLING));
        verify(mNfcManagerRegisterer).register(mIBinderArgumentCaptor.capture());
        Assert.assertNotNull(mIBinderArgumentCaptor.getValue());
        Assert.assertFalse(handler.hasMessages(NfcService.MSG_RESUME_POLLING));
        Assert.assertEquals(mIBinderArgumentCaptor.getValue(), mNfcService.mNfcAdapter);
    }

    @Test
    public void testHandlerRoute_Aid() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_ROUTE_AID);
        msg.arg1 = 1;
        msg.arg2 = 2;
        msg.obj = "test";
        handler.handleMessage(msg);
        verify(mDeviceHost).routeAid(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testHandlerUnRoute_Aid() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_UNROUTE_AID);
        msg.obj = "test";
        handler.handleMessage(msg);
        verify(mDeviceHost).unrouteAid(any());
    }

    @Test
    public void testGetAntennaInfo_NoneSet() throws Exception {
        enableAndVerify();
        NfcAntennaInfo nfcAntennaInfo = mNfcService.mNfcAdapter.getNfcAntennaInfo();
        assertThat(nfcAntennaInfo).isNotNull();
        assertThat(nfcAntennaInfo.getDeviceWidth()).isEqualTo(0);
        assertThat(nfcAntennaInfo.getDeviceHeight()).isEqualTo(0);
        assertThat(nfcAntennaInfo.isDeviceFoldable()).isEqualTo(false);
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas()).isEmpty();
    }

    @Test
    public void testGetAntennaInfo_ReadFromResources() throws Exception {
        enableAndVerify();
        when(mResources.getIntArray(R.array.antenna_x)).thenReturn(ANTENNA_POS_X);
        when(mResources.getIntArray(R.array.antenna_y)).thenReturn(ANTENNA_POS_Y);
        when(mResources.getInteger(R.integer.device_width)).thenReturn(ANTENNA_DEVICE_WIDTH);
        when(mResources.getInteger(R.integer.device_height)).thenReturn(ANTENNA_DEVICE_HEIGHT);
        when(mResources.getBoolean(R.bool.device_foldable)).thenReturn(ANTENNA_DEVICE_FOLDABLE);
        NfcAntennaInfo nfcAntennaInfo = mNfcService.mNfcAdapter.getNfcAntennaInfo();
        assertThat(nfcAntennaInfo).isNotNull();
        assertThat(nfcAntennaInfo.getDeviceWidth()).isEqualTo(ANTENNA_DEVICE_WIDTH);
        assertThat(nfcAntennaInfo.getDeviceHeight()).isEqualTo(ANTENNA_DEVICE_HEIGHT);
        assertThat(nfcAntennaInfo.isDeviceFoldable()).isEqualTo(ANTENNA_DEVICE_FOLDABLE);
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas()).isNotEmpty();
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas().get(0).getLocationX())
                .isEqualTo(ANTENNA_POS_X[0]);
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas().get(0).getLocationY())
                .isEqualTo(ANTENNA_POS_Y[0]);
    }

    @Test
    public void testGetAntennaInfo_ReadFromSysProp() throws Exception {
        enableAndVerify();
        when(NfcProperties.info_antpos_X())
                .thenReturn(Arrays.stream(ANTENNA_POS_X).boxed().toList());
        when(NfcProperties.info_antpos_Y())
                .thenReturn(Arrays.stream(ANTENNA_POS_Y).boxed().toList());
        when(NfcProperties.info_antpos_device_width())
                .thenReturn(Optional.of(ANTENNA_DEVICE_WIDTH));
        when(NfcProperties.info_antpos_device_height())
                .thenReturn(Optional.of(ANTENNA_DEVICE_HEIGHT));
        when(NfcProperties.info_antpos_device_foldable())
                .thenReturn(Optional.of(ANTENNA_DEVICE_FOLDABLE));
        NfcAntennaInfo nfcAntennaInfo = mNfcService.mNfcAdapter.getNfcAntennaInfo();
        assertThat(nfcAntennaInfo).isNotNull();
        assertThat(nfcAntennaInfo.getDeviceWidth()).isEqualTo(ANTENNA_DEVICE_WIDTH);
        assertThat(nfcAntennaInfo.getDeviceHeight()).isEqualTo(ANTENNA_DEVICE_HEIGHT);
        assertThat(nfcAntennaInfo.isDeviceFoldable()).isEqualTo(ANTENNA_DEVICE_FOLDABLE);
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas()).isNotEmpty();
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas().get(0).getLocationX())
                .isEqualTo(ANTENNA_POS_X[0]);
        assertThat(nfcAntennaInfo.getAvailableNfcAntennas().get(0).getLocationY())
                .isEqualTo(ANTENNA_POS_Y[0]);
    }
}
