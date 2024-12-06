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

import static android.nfc.NfcAdapter.ACTION_PREFERRED_PAYMENT_CHANGED;

import static com.android.nfc.NfcService.INVALID_NATIVE_HANDLE;
import static com.android.nfc.NfcService.PREF_NFC_ON;
import static com.android.nfc.NfcService.SOUND_END;
import static com.android.nfc.NfcService.SOUND_ERROR;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.media.SoundPool;
import android.nfc.INfcOemExtensionCallback;
import android.nfc.INfcUnlockHandler;
import android.nfc.INfcVendorNciCallback;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.NfcAntennaInfo;
import android.nfc.NfcOemExtension;
import android.nfc.NfcServiceManager;
import android.nfc.Tag;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.PollingFrame;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.se.omapi.ISecureElementService;
import android.sysprop.NfcProperties;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.cardemulation.CardEmulationManager;
import com.android.nfc.cardemulation.util.StatsdUtils;
import com.android.nfc.flags.FeatureFlags;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    @Mock SoundPool mSoundPool;
    @Mock FeatureFlags mFeatureFlags;
    @Mock DisplayManager mDisplayManager;
    @Mock CardEmulationManager mCardEmulationManager;
    @Mock StatsdUtils mStatsdUtils;
    @Captor ArgumentCaptor<DeviceHost.DeviceHostListener> mDeviceHostListener;
    @Captor ArgumentCaptor<BroadcastReceiver> mGlobalReceiver;
    @Captor ArgumentCaptor<IBinder> mIBinderArgumentCaptor;
    @Captor ArgumentCaptor<Integer> mSoundCaptor;
    @Captor ArgumentCaptor<Intent> mIntentArgumentCaptor;
    @Captor ArgumentCaptor<ContentObserver> mContentObserverArgumentCaptor;
    @Captor ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverArgumentCaptor;
    TestLooper mLooper;
    NfcService mNfcService;
    private MockitoSession mStaticMockSession;
    private ContentObserver mContentObserver;

    @Before
    public void setUp() {
        mLooper = new TestLooper();
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(NfcProperties.class)
                .mockStatic(android.nfc.Flags.class)
                .mockStatic(com.android.nfc.flags.Flags.class)
                .mockStatic(NfcStatsLog.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);
        AsyncTask.setDefaultExecutor(new HandlerExecutor(new Handler(mLooper.getLooper())));

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
                .thenReturn(true);
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
        when(mNfcInjector.getFeatureFlags()).thenReturn(mFeatureFlags);
        when(mNfcInjector.isSatelliteModeSensitive()).thenReturn(true);
        when(mNfcInjector.getCardEmulationManager()).thenReturn(mCardEmulationManager);
        when(mApplication.getSharedPreferences(anyString(), anyInt())).thenReturn(mPreferences);
        when(mApplication.getSystemService(PowerManager.class)).thenReturn(mPowerManager);
        when(mApplication.getSystemService(UserManager.class)).thenReturn(mUserManager);
        when(mApplication.getSystemService(ActivityManager.class)).thenReturn(mActivityManager);
        when(mApplication.getSystemService(KeyguardManager.class)).thenReturn(mKeyguardManager);
        when(mApplication.getSystemService(AlarmManager.class)).thenReturn(mAlarmManager);
        when(mApplication.getPackageManager()).thenReturn(mPackageManager);
        when(mResources.getBoolean(R.bool.check_display_state_for_screen_state)).thenReturn(true);
        when(mApplication.getResources()).thenReturn(mResources);
        when(mApplication.createContextAsUser(any(), anyInt())).thenReturn(mApplication);
        when(mApplication.getContentResolver()).thenReturn(mContentResolver);
        when(mApplication.getSystemService(DisplayManager.class)).thenReturn(mDisplayManager);
        when(mUserManager.getUserRestrictions()).thenReturn(mUserRestrictions);
        when(mResources.getStringArray(R.array.nfc_allow_list)).thenReturn(new String[0]);
        when(mResources.getBoolean(R.bool.tag_intent_app_pref_supported)).thenReturn(true);
        when(mResources.getBoolean(R.bool.nfcc_always_on_allowed)).thenReturn(true);
        when(mPreferences.edit()).thenReturn(mPreferencesEditor);
        when(mPowerManager.newWakeLock(anyInt(), anyString()))
                .thenReturn(mock(PowerManager.WakeLock.class));
        when(mResources.getIntArray(R.array.antenna_x)).thenReturn(new int[0]);
        when(mResources.getIntArray(R.array.antenna_y)).thenReturn(new int[0]);
        when(mResources.getStringArray(R.array.tag_intent_blocked_app_list))
                .thenReturn(new String[]{});
        when(NfcProperties.info_antpos_X()).thenReturn(List.of());
        when(NfcProperties.info_antpos_Y()).thenReturn(List.of());
        when(NfcProperties.initialized()).thenReturn(Optional.of(Boolean.TRUE));
        createNfcService();
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    private void createNfcService() {
        when(android.nfc.Flags.enableNfcCharging()).thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_CHARGING))
                .thenReturn(true);
        mNfcService = new NfcService(mApplication, mNfcInjector);
        mLooper.dispatchAll();
        verify(mContentResolver, atLeastOnce()).registerContentObserver(any(),
                anyBoolean(), mContentObserverArgumentCaptor.capture());
        mContentObserver = mContentObserverArgumentCaptor.getValue();
        Assert.assertNotNull(mContentObserver);
        verify(mNfcInjector).makeDeviceHost(mDeviceHostListener.capture());
        verify(mApplication).registerReceiverForAllUsers(
                mGlobalReceiver.capture(),
                argThat(intent -> intent.hasAction(Intent.ACTION_SCREEN_ON)), any(), any());
        verify(mApplication).registerReceiver(mBroadcastReceiverArgumentCaptor.capture(),
                argThat(intent -> intent.hasAction(UserManager.ACTION_USER_RESTRICTIONS_CHANGED)));
        clearInvocations(mDeviceHost, mNfcInjector, mApplication);
    }

    private void createNfcServiceWithoutStatsdUtils() {
        when(mNfcInjector.getStatsdUtils()).thenReturn(mStatsdUtils);
        createNfcService();
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
    public void testEnable_WheOemExtensionEnabledAndNotInitialized() throws Exception {
        when(mResources.getBoolean(R.bool.enable_oem_extension)).thenReturn(true);
        when(NfcProperties.initialized()).thenReturn(Optional.of(Boolean.FALSE));

        createNfcService();

        when(mDeviceHost.initialize()).thenReturn(true);
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        mNfcService.mNfcAdapter.enable(PKG_NAME);
        verify(mPreferencesEditor, never()).putBoolean(PREF_NFC_ON, true);
        mLooper.dispatchAll();
        verify(mDeviceHost, never()).initialize();
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

        mNfcService.mNfcAdapter.triggerInitialization();
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

    @Test
    public void testHandlerMsgRegisterT3tIdentifier() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_REGISTER_T3T_IDENTIFIER);
        msg.obj = "test".getBytes();
        handler.handleMessage(msg);
        verify(mDeviceHost).disableDiscovery();
        verify(mDeviceHost).registerT3tIdentifier(any());
        verify(mDeviceHost).enableDiscovery(any(), anyBoolean());
        Message msgDeregister = handler.obtainMessage(NfcService.MSG_DEREGISTER_T3T_IDENTIFIER);
        msgDeregister.obj = "test".getBytes();
        handler.handleMessage(msgDeregister);
        verify(mDeviceHost, times(2)).disableDiscovery();
        verify(mDeviceHost, times(2)).enableDiscovery(any(), anyBoolean());
    }

    @Test
    public void testHandlerMsgCommitRouting() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_COMMIT_ROUTING);
        mNfcService.mState = NfcAdapter.STATE_OFF;
        handler.handleMessage(msg);
        verify(mDeviceHost, never()).commitRouting();
        mNfcService.mState = NfcAdapter.STATE_ON;
        NfcDiscoveryParameters nfcDiscoveryParameters = mock(NfcDiscoveryParameters.class);
        when(nfcDiscoveryParameters.shouldEnableDiscovery()).thenReturn(true);
        mNfcService.mCurrentDiscoveryParameters = nfcDiscoveryParameters;
        handler.handleMessage(msg);
        verify(mDeviceHost).commitRouting();
    }

    @Test
    public void testHandlerMsgMockNdef() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_MOCK_NDEF);
        NdefMessage ndefMessage = mock(NdefMessage.class);
        msg.obj = ndefMessage;
        handler.handleMessage(msg);
        verify(mNfcDispatcher).dispatchTag(any());
    }

    @Test
    public void testInitSoundPool_Start() {
        mNfcService.playSound(SOUND_END);

        verify(mSoundPool, never()).play(mSoundCaptor.capture(),
                anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat());
        mNfcService.mSoundPool = mSoundPool;
        mNfcService.playSound(SOUND_END);
        verify(mSoundPool, atLeastOnce()).play(mSoundCaptor.capture(),
                anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat());
        Integer value = mSoundCaptor.getValue();
        Assert.assertEquals(mNfcService.mEndSound, (int) value);
    }

    @Test
    public void testInitSoundPool_End() {
        mNfcService.playSound(SOUND_END);

        verify(mSoundPool, never()).play(mSoundCaptor.capture(),
                anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat());
        mNfcService.mSoundPool = mSoundPool;
        mNfcService.playSound(SOUND_END);
        verify(mSoundPool, atLeastOnce()).play(mSoundCaptor.capture(),
                anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat());
        Integer value = mSoundCaptor.getValue();
        Assert.assertEquals(mNfcService.mEndSound, (int) value);
    }

    @Test
    public void testInitSoundPool_Error() {
        mNfcService.playSound(SOUND_ERROR);

        verify(mSoundPool, never()).play(mSoundCaptor.capture(),
                anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat());
        mNfcService.mSoundPool = mSoundPool;
        mNfcService.playSound(SOUND_ERROR);
        verify(mSoundPool, atLeastOnce()).play(mSoundCaptor.capture(),
                anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat());
        Integer value = mSoundCaptor.getValue();
        Assert.assertEquals(mNfcService.mErrorSound, (int) value);
    }

    @Test
    public void testMsg_Rf_Field_Activated() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_RF_FIELD_ACTIVATED);
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        mNfcService.mIsSecureNfcEnabled = true;
        mNfcService.mIsRequestUnlockShowed = false;
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        handler.handleMessage(msg);
        verify(mApplication).sendBroadcastAsUser(mIntentArgumentCaptor.capture(), any());
        Intent intent = mIntentArgumentCaptor.getValue();
        Assert.assertNotNull(intent);
        Assert.assertEquals(NfcService.ACTION_RF_FIELD_ON_DETECTED, intent.getAction());
        verify(mApplication).sendBroadcast(mIntentArgumentCaptor.capture());
        intent = mIntentArgumentCaptor.getValue();
        Assert.assertEquals(NfcAdapter.ACTION_REQUIRE_UNLOCK_FOR_NFC, intent.getAction());
    }

    @Test
    public void testMsg_Rf_Field_Deactivated() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_RF_FIELD_DEACTIVATED);
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        handler.handleMessage(msg);
        verify(mApplication).sendBroadcastAsUser(mIntentArgumentCaptor.capture(), any());
        Intent intent = mIntentArgumentCaptor.getValue();
        Assert.assertNotNull(intent);
        Assert.assertEquals(NfcService.ACTION_RF_FIELD_OFF_DETECTED, intent.getAction());
    }

    @Test
    public void testMsg_Tag_Debounce() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_TAG_DEBOUNCE);
        handler.handleMessage(msg);
        Assert.assertEquals(INVALID_NATIVE_HANDLE, mNfcService.mDebounceTagNativeHandle);
    }

    @Test
    public void testMsg_Apply_Screen_State() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_APPLY_SCREEN_STATE);
        msg.obj = ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;
        handler.handleMessage(msg);
      //  verify(mDeviceHost).doSetScreenState(anyInt(), anyBoolean());
    }

    @Test
    public void testMsg_Transaction_Event_Cardemulation_Occurred() {
        CardEmulationManager cardEmulationManager = mock(CardEmulationManager.class);
        when(cardEmulationManager.getRegisteredAidCategory(anyString())).
                thenReturn(CardEmulation.CATEGORY_PAYMENT);
        mNfcService.mCardEmulationManager = cardEmulationManager;
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_TRANSACTION_EVENT);
        byte[][] data = {NfcService.hexStringToBytes("F00102030405"),
                NfcService.hexStringToBytes("02FE00010002"),
                NfcService.hexStringToBytes("03000000")};
        msg.obj = data;
        handler.handleMessage(msg);
        ExtendedMockito.verify(() -> NfcStatsLog.write(NfcStatsLog.NFC_CARDEMULATION_OCCURRED,
                NfcStatsLog
                        .NFC_CARDEMULATION_OCCURRED__CATEGORY__OFFHOST_PAYMENT,
                new String(NfcService.hexStringToBytes("03000000"), "UTF-8"),
                -1));
    }

    @Test
    public void testMsg_Transaction_Event() throws RemoteException {
        CardEmulationManager cardEmulationManager = mock(CardEmulationManager.class);
        when(cardEmulationManager.getRegisteredAidCategory(anyString())).
                thenReturn(CardEmulation.CATEGORY_PAYMENT);
        mNfcService.mCardEmulationManager = cardEmulationManager;
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_TRANSACTION_EVENT);
        byte[][] data = {NfcService.hexStringToBytes("F00102030405"),
                NfcService.hexStringToBytes("02FE00010002"),
                NfcService.hexStringToBytes("03000000")};
        msg.obj = data;
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        ISecureElementService iSecureElementService = mock(ISecureElementService.class);
        IBinder iBinder = mock(IBinder.class);
        when(iSecureElementService.asBinder()).thenReturn(iBinder);
        boolean[] nfcAccess = {true};
        when(iSecureElementService.isNfcEventAllowed(anyString(), any(), any(), anyInt()))
                .thenReturn(nfcAccess);
        when(mNfcInjector.connectToSeService()).thenReturn(iSecureElementService);
        handler.handleMessage(msg);
        verify(mApplication).sendBroadcastAsUser(mIntentArgumentCaptor.capture(),
                any(), any(), any());
    }

    @Test
    public void testMsg_Preferred_Payment_Changed()
            throws RemoteException, PackageManager.NameNotFoundException {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_PREFERRED_PAYMENT_CHANGED);
        msg.obj = 1;
        List<String> packagesList = new ArrayList<>();
        packagesList.add("com.android.nfc");
        packagesList.add("com.sample.nfc");
        mNfcService.mNfcPreferredPaymentChangedInstalledPackages.put(1, packagesList);
        ISecureElementService iSecureElementService = mock(ISecureElementService.class);
        IBinder iBinder = mock(IBinder.class);
        when(iSecureElementService.asBinder()).thenReturn(iBinder);
        when(iSecureElementService.getReaders()).thenReturn(new String[]{"com.android.nfc"});
        when(iSecureElementService.isNfcEventAllowed(anyString(), isNull(), any(), anyInt()))
                .thenReturn(new boolean[]{true});
        boolean[] nfcAccess = {true};
        when(iSecureElementService.isNfcEventAllowed(anyString(), any(), any(), anyInt()))
                .thenReturn(nfcAccess);
        when(mNfcInjector.connectToSeService()).thenReturn(iSecureElementService);
        PackageInfo info = mock(PackageInfo.class);
        ApplicationInfo applicationInfo = mock(ApplicationInfo.class);
        applicationInfo.flags = 1;
        info.applicationInfo = applicationInfo;
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenReturn(info);
        handler.handleMessage(msg);
        verify(mApplication, times(2))
                .sendBroadcastAsUser(mIntentArgumentCaptor.capture(), any());
        Intent intent = mIntentArgumentCaptor.getValue();
        Assert.assertEquals(ACTION_PREFERRED_PAYMENT_CHANGED, intent.getAction());
    }

    @Test
    public void testMSG_NDEF_TAG() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_NDEF_TAG);
        mNfcService.mState = NfcAdapter.STATE_ON;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getConnectedTechnology()).thenReturn(TagTechnology.NDEF);
        NdefMessage ndefMessage = mock(NdefMessage.class);
        when(tagEndpoint.findAndReadNdef()).thenReturn(ndefMessage);
        msg.obj = tagEndpoint;
        handler.handleMessage(msg);
        verify(tagEndpoint, atLeastOnce()).startPresenceChecking(anyInt(), any());
    }

    @Test
    public void testMsg_Ndef_Tag_Wlc_Enabled() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_NDEF_TAG);
        mNfcService.mState = NfcAdapter.STATE_ON;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getConnectedTechnology()).thenReturn(TagTechnology.NDEF);
        when(tagEndpoint.getUid()).thenReturn(NfcService
                .hexStringToBytes("0x040000010100000000000000"));
        when(tagEndpoint.getTechList()).thenReturn(new int[]{Ndef.NDEF});
        when(tagEndpoint.getTechExtras()).thenReturn(new Bundle[]{});
        when(tagEndpoint.getHandle()).thenReturn(1);
        NdefMessage ndefMessage = mock(NdefMessage.class);
        when(tagEndpoint.findAndReadNdef()).thenReturn(ndefMessage);
        msg.obj = tagEndpoint;
        mNfcService.mIsWlcEnabled = true;
        mNfcService.mIsRWCapable = true;
        handler.handleMessage(msg);
        verify(tagEndpoint, atLeastOnce()).startPresenceChecking(anyInt(), any());
        ArgumentCaptor<Tag> tagCaptor = ArgumentCaptor
                .forClass(Tag.class);
        verify(mNfcDispatcher).dispatchTag(tagCaptor.capture());
        Tag tag = tagCaptor.getValue();
        Assert.assertNotNull(tag);
        Assert.assertEquals("android.nfc.tech.Ndef", tag.getTechList()[0]);
    }

    @Test
    public void testMsg_Clear_Routing_Table() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_CLEAR_ROUTING_TABLE);
        mNfcService.mState = NfcAdapter.STATE_ON;
        msg.obj = 1;
        handler.handleMessage(msg);
        ArgumentCaptor<Integer> flagCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mDeviceHost).clearRoutingEntry(flagCaptor.capture());
        int flag = flagCaptor.getValue();
        Assert.assertEquals(1, flag);
    }

    @Test
    public void testMsg_Update_Isodep_Protocol_Route() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_UPDATE_ISODEP_PROTOCOL_ROUTE);
        msg.obj = 1;
        handler.handleMessage(msg);
        ArgumentCaptor<Integer> flagCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mDeviceHost).setIsoDepProtocolRoute(flagCaptor.capture());
        int flag = flagCaptor.getValue();
        Assert.assertEquals(1, flag);
    }

    @Test
    public void testMsg_Update_Technology_Abf_Route() {
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_UPDATE_TECHNOLOGY_ABF_ROUTE);
        msg.arg1 = 1;
        msg.arg2 = 2;
        handler.handleMessage(msg);
        ArgumentCaptor<Integer> flagCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> flagCaptor2 = ArgumentCaptor.forClass(Integer.class);
        verify(mDeviceHost).setTechnologyABFRoute(flagCaptor.capture(), flagCaptor2.capture());
        int flag = flagCaptor.getValue();
        Assert.assertEquals(1, flag);
        int flag2 = flagCaptor2.getValue();
        Assert.assertEquals(2, flag2);
    }

    @Test
    public void testDirectBootAware() throws Exception {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        when(mFeatureFlags.enableDirectBootAware()).thenReturn(true);
        mNfcService = new NfcService(mApplication, mNfcInjector);
        mLooper.dispatchAll();
        verify(mNfcInjector).makeDeviceHost(mDeviceHostListener.capture());
        verify(mApplication).registerReceiverForAllUsers(
                mGlobalReceiver.capture(),
                argThat(intent -> intent.hasAction(Intent.ACTION_USER_UNLOCKED)), any(), any());
        verify(mDeviceHost).initialize();

        clearInvocations(mApplication, mPreferences, mPreferencesEditor);
        Context ceContext = mock(Context.class);
        when(mApplication.createCredentialProtectedStorageContext()).thenReturn(ceContext);
        when(ceContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mPreferences);
        when(mApplication.moveSharedPreferencesFrom(ceContext, NfcService.PREF)).thenReturn(true);
        when(mApplication.moveSharedPreferencesFrom(ceContext, NfcService.PREF_TAG_APP_LIST))
            .thenReturn(true);
        mGlobalReceiver.getValue().onReceive(mApplication, new Intent(Intent.ACTION_USER_UNLOCKED));
        verify(mApplication).moveSharedPreferencesFrom(ceContext, NfcService.PREF);
        verify(mApplication).getSharedPreferences(eq(NfcService.PREF), anyInt());
        verify(mPreferences).edit();
        verify(mPreferencesEditor).putBoolean(NfcService.PREF_MIGRATE_TO_DE_COMPLETE, true);
        verify(mPreferencesEditor).apply();
    }

    @Test
    public void testAllowOemOnNdefReadCallback() throws Exception {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        Handler handler = mNfcService.getHandler();
        Assert.assertNotNull(handler);
        Message msg = handler.obtainMessage(NfcService.MSG_NDEF_TAG);
        mNfcService.mState = NfcAdapter.STATE_ON;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getConnectedTechnology()).thenReturn(TagTechnology.NDEF);
        when(tagEndpoint.getUid()).thenReturn(NfcService
                .hexStringToBytes("0x040000010100000000000000"));
        when(tagEndpoint.getTechList()).thenReturn(new int[]{Ndef.NDEF});
        when(tagEndpoint.getTechExtras()).thenReturn(new Bundle[]{});
        when(tagEndpoint.getHandle()).thenReturn(1);
        NdefMessage ndefMessage = mock(NdefMessage.class);
        when(tagEndpoint.findAndReadNdef()).thenReturn(ndefMessage);
        msg.obj = tagEndpoint;
        mNfcService.mIsWlcEnabled = true;
        mNfcService.mIsRWCapable = true;
        handler.handleMessage(msg);
        verify(tagEndpoint, atLeastOnce()).startPresenceChecking(anyInt(), any());
        ArgumentCaptor<Tag> tagCaptor = ArgumentCaptor
                .forClass(Tag.class);
        verify(mNfcDispatcher).dispatchTag(tagCaptor.capture());
        Tag tag = tagCaptor.getValue();
        Assert.assertNotNull(tag);
        Assert.assertEquals("android.nfc.tech.Ndef", tag.getTechList()[0]);

        doAnswer(new Answer() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                ResultReceiver r = invocation.getArgument(0);
                r.send(1, null);
                return null;
            }
        }).when(callback).onNdefRead(any(ResultReceiver.class));
        mContentObserver.onChange(true);
        ArgumentCaptor<ResultReceiver> receiverArgumentCaptor = ArgumentCaptor
                .forClass(ResultReceiver.class);
        verify(callback).onNdefRead(receiverArgumentCaptor.capture());
        ResultReceiver resultReceiver = receiverArgumentCaptor.getValue();
        Assert.assertNotNull(resultReceiver);
    }

    @Test
    public void testAllowOemOnApplyRoutingCallback() throws Exception {
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        mNfcService.mState = NfcAdapter.STATE_ON;
        INfcUnlockHandler binder = mock(INfcUnlockHandler.class);
        mNfcService.mNfcAdapter.removeNfcUnlockHandler(binder);

        doAnswer(new Answer() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                ResultReceiver r = invocation.getArgument(0);
                r.send(1, null);
                return null;
            }
        }).when(callback).onApplyRouting(any(ResultReceiver.class));
        mContentObserver.onChange(true);
        ArgumentCaptor<ResultReceiver> receiverArgumentCaptor = ArgumentCaptor
                .forClass(ResultReceiver.class);
        verify(callback).onApplyRouting(receiverArgumentCaptor.capture());
        ResultReceiver resultReceiver = receiverArgumentCaptor.getValue();
        Assert.assertNotNull(resultReceiver);
    }

    @Test
    public void testThermalStatusChangeListener() {
        Assert.assertNotNull(mPowerManager);
        ArgumentCaptor<PowerManager.OnThermalStatusChangedListener> argumentCaptor =
                ArgumentCaptor.forClass(PowerManager.OnThermalStatusChangedListener.class);
        verify(mPowerManager).addThermalStatusListener(any(), argumentCaptor.capture());
        PowerManager.OnThermalStatusChangedListener changedListener =
                argumentCaptor.getValue();
        Assert.assertNotNull(changedListener);
        changedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_MODERATE);
        changedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_SEVERE);
        changedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_CRITICAL);
        changedListener.onThermalStatusChanged(0);
    }

    @Test
    public void testClearRoutingTable() {
        mNfcService.mState = NfcAdapter.STATE_ON;
        mNfcService.clearRoutingTable(1);
        mLooper.dispatchAll();
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(mDeviceHost).clearRoutingEntry(captor.capture());
        int flag = captor.getValue();
        Assert.assertEquals(1, flag);
    }

    @Test
    public void testDeregisterT3tIdentifier() {
        NfcDiscoveryParameters nfcDiscoveryParameters = mock(NfcDiscoveryParameters.class);
        when(nfcDiscoveryParameters.shouldEnableDiscovery()).thenReturn(true);
        mNfcService.mCurrentDiscoveryParameters = nfcDiscoveryParameters;
        mNfcService.deregisterT3tIdentifier("02FE", "02FEC1DE32456789", "F0010203");
        mLooper.dispatchAll();
        verify(mDeviceHost).disableDiscovery();
        ArgumentCaptor<byte[]> t3tIdentifierByteArray = ArgumentCaptor.forClass(byte[].class);
        verify(mDeviceHost).deregisterT3tIdentifier(t3tIdentifierByteArray.capture());
        byte[] data = t3tIdentifierByteArray.getValue();
        Assert.assertNotNull(data);
        String msg = new String(data, StandardCharsets.UTF_8);
        Assert.assertNotNull(msg);
        verify(mDeviceHost).enableDiscovery(any(), anyBoolean());
    }

    @Test
    public void testFindAndRemoveObject() {
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getHandle()).thenReturn(1);
        mNfcService.registerTagObject(tagEndpoint);
        DeviceHost.TagEndpoint device = (DeviceHost.TagEndpoint) mNfcService.mObjectMap.get(1);
        Assert.assertNotNull(device);
        Assert.assertEquals(tagEndpoint, device);
        mNfcService.findAndRemoveObject(1);
        Object obj = mNfcService.mObjectMap.get(1);
        Assert.assertNull(obj);
    }

    @Test
    public void testDisplayManagerCallback() {
        ArgumentCaptor<DisplayManager.DisplayListener> displayListenerArgumentCaptor =
                ArgumentCaptor.forClass(DisplayManager.DisplayListener.class);
        ArgumentCaptor<NfcService.NfcServiceHandler> nfcServiceHandlerArgumentCaptor =
                ArgumentCaptor.forClass(NfcService.NfcServiceHandler.class);
        verify(mDisplayManager).registerDisplayListener(displayListenerArgumentCaptor.capture(),
                nfcServiceHandlerArgumentCaptor.capture());
        DisplayManager.DisplayListener displayListener = displayListenerArgumentCaptor.getValue();
        Assert.assertNotNull(displayListener);
        NfcService.NfcServiceHandler handler = nfcServiceHandlerArgumentCaptor.getValue();
        Assert.assertNotNull(handler);
        displayListener.onDisplayAdded(Display.DEFAULT_DISPLAY);
        displayListener.onDisplayRemoved(Display.DEFAULT_DISPLAY);
        mNfcService.mIsWlcCapable = false;
        when(mScreenStateHelper.checkScreenState(anyBoolean()))
                .thenReturn(ScreenStateHelper.SCREEN_STATE_ON_LOCKED);
        mNfcService.mScreenState = ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;
        displayListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
        mLooper.dispatchAll();
        Assert.assertFalse(handler.hasMessages(NfcService.MSG_DELAY_POLLING));
        Assert.assertFalse(mNfcService.mIsRequestUnlockShowed);
        // verify(mDeviceHost).doSetScreenState(anyInt(), anyBoolean());
    }

    @Test
    public void testThermalStatusListener() {
        Assert.assertNotNull(mPowerManager);
        ArgumentCaptor<PowerManager.OnThermalStatusChangedListener> argumentCaptor =
                ArgumentCaptor.forClass(PowerManager.OnThermalStatusChangedListener.class);
        verify(mPowerManager).addThermalStatusListener(any(), argumentCaptor.capture());
        PowerManager.OnThermalStatusChangedListener thermalStatusChangedListener =
                argumentCaptor.getValue();
        Assert.assertNotNull(thermalStatusChangedListener);
        thermalStatusChangedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_MODERATE);
        thermalStatusChangedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_SEVERE);
        thermalStatusChangedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_CRITICAL);
        thermalStatusChangedListener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_SHUTDOWN);
    }

    @Test
    public void testGetAppName() throws RemoteException, PackageManager.NameNotFoundException {
        String[] packages = {"com.android.test1"};
        when(mResources.getStringArray(R.array.nfc_allow_list)).thenReturn(packages);
        mNfcService.mNfcAdapter.enable("com.android.test");
        ArgumentCaptor<String> stringArgumentCaptor = ArgumentCaptor.forClass(String.class);
        verify(mPackageManager).getApplicationInfoAsUser(stringArgumentCaptor.capture(), anyInt(),
                any());
        assertThat("com.android.test").isEqualTo(stringArgumentCaptor.getValue());
        verify(mPackageManager, atLeastOnce()).getApplicationLabel(any());
    }

    @Test
    public void testFindObject() {
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getHandle()).thenReturn(1);
        mNfcService.registerTagObject(tagEndpoint);
        DeviceHost.TagEndpoint device = (DeviceHost.TagEndpoint) mNfcService.mObjectMap.get(1);
        Assert.assertNotNull(device);
        Assert.assertEquals(tagEndpoint, device);
        Object obj = mNfcService.findObject(1);
        Assert.assertNotNull(obj);
        Object object = mNfcService.mObjectMap.get(1);
        Assert.assertNotNull(object);
        assertThat(obj).isEqualTo(object);
    }

    @Test
    public void testGetEnabledUserIds() {
        when(mPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        Assert.assertTrue(mNfcService.getNfcOnSetting());
        when(mNfcInjector.isSatelliteModeOn()).thenReturn(false);
        when(mUserRestrictions.getBoolean(UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO))
                .thenReturn(false);
        NfcService.sIsNfcRestore = true;
        UserHandle uh = mock(UserHandle.class);
        when(uh.getIdentifier()).thenReturn(1);
        List<UserHandle> luh = new ArrayList<>();
        luh.add(uh);
        when(mUserManager.getEnabledProfiles()).thenReturn(luh);
        mNfcService.enableNfc();
        verify(mPreferences).edit();
        verify(mPreferencesEditor).putBoolean(PREF_NFC_ON, true);
        verify(mPreferencesEditor).apply();
        verify(mBackupManager).dataChanged();
        mLooper.dispatchAll();
        verify(mUserManager, atLeastOnce()).getEnabledProfiles();
    }

    @Test
    public void testGetLfT3tMax() {
        int lfT3t = mNfcService.getLfT3tMax();
        assertThat(lfT3t).isEqualTo(0);
        when(mDeviceHost.getLfT3tMax()).thenReturn(100);
        lfT3t = mNfcService.getLfT3tMax();
        assertThat(lfT3t).isEqualTo(100);
        verify(mDeviceHost, atLeastOnce()).getLfT3tMax();
    }

    @Test
    public void testGetNfcPollTech() {
        int pollTech = mNfcService.getNfcPollTech();
        assertThat(pollTech).isEqualTo(0);
        when(mPreferences.getInt(NfcService.PREF_POLL_TECH, NfcService.DEFAULT_POLL_TECH))
                .thenReturn(NfcService.DEFAULT_LISTEN_TECH);
        pollTech = mNfcService.getNfcPollTech();
        assertThat(pollTech).isEqualTo(0xf);
        verify(mPreferences, atLeastOnce()).getInt(anyString(), anyInt());
    }

    @Test
    public void testIsPackageInstalled() {
        when(mPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        String jsonString = "{}";
        when(mPreferences.getString(anyString(), anyString())).thenReturn(jsonString);
        Assert.assertTrue(mNfcService.getNfcOnSetting());
        when(mNfcInjector.isSatelliteModeOn()).thenReturn(false);
        when(mUserRestrictions.getBoolean(UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO))
                .thenReturn(false);
        NfcService.sIsNfcRestore = true;
        UserHandle uh = mock(UserHandle.class);
        when(uh.getIdentifier()).thenReturn(1);
        List<UserHandle> luh = new ArrayList<>();
        luh.add(uh);
        when(mUserManager.getEnabledProfiles()).thenReturn(luh);
        mNfcService.mTagAppDefaultBlockList.add("com.android.test");
        mNfcService.enableNfc();
        verify(mPreferences).edit();
        verify(mPreferencesEditor).putBoolean(PREF_NFC_ON, true);
        verify(mPreferencesEditor).apply();
        verify(mBackupManager).dataChanged();
        mLooper.dispatchAll();
        verify(mUserManager, atLeastOnce()).getEnabledProfiles();
        verify(mApplication, atLeastOnce()).createContextAsUser(any(), anyInt());
    }

    @Test
    public void testIsSecureNfcEnabled() {
        mNfcService.mIsSecureNfcEnabled = true;
        boolean isSecureNfcEnabled = mNfcService.isSecureNfcEnabled();
        assertThat(isSecureNfcEnabled).isTrue();
        mNfcService.mIsSecureNfcEnabled = false;
        isSecureNfcEnabled = mNfcService.isSecureNfcEnabled();
        assertThat(isSecureNfcEnabled).isFalse();
    }

    @Test
    public void testIsTagPresent() throws RemoteException {
        boolean isTagPresent = mNfcService.mNfcAdapter.isTagPresent();
        assertThat(isTagPresent).isFalse();
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.isPresent()).thenReturn(true);
        mNfcService.mObjectMap.put(1, tagEndpoint);
        isTagPresent = mNfcService.mNfcAdapter.isTagPresent();
        assertThat(isTagPresent).isTrue();

    }

    @Test
    public void testOnObserveModeStateChanged() {
        when(com.android.nfc.flags.Flags.postCallbacks()).thenReturn(true);
        mNfcService.onObserveModeStateChanged(true);
        mLooper.dispatchAll();
        verify(mCardEmulationManager, atLeastOnce()).onObserveModeStateChange(anyBoolean());
        when(com.android.nfc.flags.Flags.postCallbacks()).thenReturn(false);
        mNfcService.onObserveModeStateChanged(false);
        verify(mCardEmulationManager, atLeastOnce()).onObserveModeStateChange(anyBoolean());
    }

    @Test
    public void testOnPollingLoopDetected() {
        PollingFrame pollingFrame = mock(PollingFrame.class);
        List<PollingFrame> frames = new ArrayList<>();
        frames.add(pollingFrame);
        when(android.nfc.Flags.nfcReadPollingLoop()).thenReturn(true);
        when(com.android.nfc.flags.Flags.postCallbacks()).thenReturn(true);
        mNfcService.onPollingLoopDetected(frames);
        mLooper.dispatchAll();
        ArgumentCaptor<List<PollingFrame>> listArgumentCaptor = ArgumentCaptor.forClass(List.class);
        verify(mCardEmulationManager).onPollingLoopDetected(listArgumentCaptor.capture());
        assertThat(frames).isEqualTo(listArgumentCaptor.getValue());
        when(com.android.nfc.flags.Flags.postCallbacks()).thenReturn(false);
        mNfcService.onPollingLoopDetected(frames);
        verify(mCardEmulationManager, atLeastOnce()).onPollingLoopDetected(listArgumentCaptor.capture());
        assertThat(frames).isEqualTo(listArgumentCaptor.getValue());
    }

    @Test
    public void testOnVendorSpecificEvent() throws RemoteException {
        INfcVendorNciCallback callback = mock(INfcVendorNciCallback.class);
        mNfcService.mNfcAdapter.registerVendorExtensionCallback(callback);
        verify(mDeviceHost).enableVendorNciNotifications(true);
        mNfcService.onVendorSpecificEvent(1, 2, "test".getBytes());
        mLooper.dispatchAll();
        verify(callback).onVendorNotificationReceived(anyInt(), anyInt(), any());
    }

    @Test
    public void testOnHostCardEmulationActivated() throws RemoteException {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        verify(callback).onCardEmulationActivated(anyBoolean());
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.onHostCardEmulationActivated(1);
        verify(mCardEmulationManager).onHostCardEmulationActivated(anyInt());
        verify(mNfcEventLog, times(2)).logEvent(any());
    }

    @Test
    public void testOnHostCardEmulationDeactivated()  throws RemoteException {
        when(mPreferences.getBoolean(eq(PREF_NFC_ON), anyBoolean())).thenReturn(true);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        verify(callback).onCardEmulationActivated(false);
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.onHostCardEmulationDeactivated(1);
        verify(mCardEmulationManager).onHostCardEmulationDeactivated(anyInt());
        verify(mNfcEventLog, times(2)).logEvent(any());
    }

    @Test
    public void testOnEeUpdated() {
        mNfcService.onEeUpdated();
        mLooper.dispatchAll();
        Assert.assertEquals(0, mNfcService.mScreenState);
    }

    @Test
    public void testOnHwErrorReported() {
        when(mPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        Assert.assertTrue(mNfcService.getNfcOnSetting());
        when(mNfcInjector.isSatelliteModeOn()).thenReturn(false);
        when(mUserRestrictions.getBoolean(UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO))
                .thenReturn(false);
        NfcService.sIsNfcRestore = true;
        mNfcService.mState = NfcAdapter.STATE_OFF;
        mNfcService.onHwErrorReported();
        verify(mApplication).unregisterReceiver(any());
        assertThat(mNfcService.mIsRecovering).isTrue();
        mLooper.dispatchAll();
        verify(mUserManager, atLeastOnce()).getEnabledProfiles();
    }

    @Test
    public void testOnNfcTransactionEvent() throws RemoteException {
        ISecureElementService iSecureElementService = mock(ISecureElementService.class);
        IBinder iBinder = mock(IBinder.class);
        when(iSecureElementService.asBinder()).thenReturn(iBinder);
        boolean[] nfcAccess = {true};
        when(iSecureElementService.isNfcEventAllowed(anyString(), any(), any(), anyInt()))
                .thenReturn(nfcAccess);
        when(mNfcInjector.connectToSeService()).thenReturn(iSecureElementService);
        List<String> packages = new ArrayList<>();
        packages.add("com.android.test");
        mNfcService.mNfcEventInstalledPackages.put(1, packages);
        when(mCardEmulationManager.getRegisteredAidCategory(anyString()))
                .thenReturn(CardEmulation.CATEGORY_PAYMENT);
        byte[] aid = { 0x0A, 0x00, 0x00, 0x00 };
        byte[] data = { 0x12, 0x34, 0x56, 0x78, 0x78 };
        mNfcService.onNfcTransactionEvent(aid, data, "SecureElement1");
        mLooper.dispatchAll();
        verify(mCardEmulationManager).onOffHostAidSelected();
        verify(mPackageManager).queryBroadcastReceiversAsUser(any(), anyInt(), any());
        verify(mApplication).sendBroadcastAsUser(any(), any(), ArgumentMatchers.isNull(), any());
    }

    @Test
    public void testOnRemoteEndpointDiscovered() {
        mNfcService.mState = NfcAdapter.STATE_ON;
        NfcService.ReaderModeParams readerModeParams = mock(NfcService.ReaderModeParams.class);
        readerModeParams.presenceCheckDelay = 1;
        readerModeParams.flags = 129;
        mNfcService.mReaderModeParams = readerModeParams;
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        mNfcService.onRemoteEndpointDiscovered(tagEndpoint);
        mLooper.dispatchAll();
        verify(tagEndpoint).startPresenceChecking(anyInt(), any());
    }

    @Test
    public void testOnRemoteFieldActivated() throws RemoteException {
        createNfcServiceWithoutStatsdUtils();
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mIsSecureNfcEnabled = true;
        mNfcService.mIsRequestUnlockShowed = false;
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.onRemoteFieldActivated();
        verify(callback, atLeastOnce()).onRfFieldActivated(anyBoolean());
        mLooper.dispatchAll();
        verify(mCardEmulationManager).onFieldChangeDetected(anyBoolean());
        verify(mApplication).sendBroadcastAsUser(any(), any());
        verify(mApplication).sendBroadcast(any());
        verify(mStatsdUtils).logFieldChanged(anyBoolean(), anyInt());
        verify(mNfcEventLog, times(3)).logEvent(any());
    }

    @Test
    public void testOnRemoteFieldDeactivated() throws RemoteException {
        createNfcServiceWithoutStatsdUtils();
        List<String> userlist = new ArrayList<>();
        userlist.add("com.android.nfc");
        mNfcService.mIsSecureNfcEnabled = true;
        mNfcService.mIsRequestUnlockShowed = false;
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        mNfcService.mNfcEventInstalledPackages.put(1, userlist);
        INfcOemExtensionCallback callback = mock(INfcOemExtensionCallback.class);
        mNfcService.mNfcAdapter.registerOemExtensionCallback(callback);
        when(android.nfc.Flags.nfcPersistLog()).thenReturn(true);
        mNfcService.onRemoteFieldDeactivated();
        verify(callback, atLeastOnce()).onRfFieldActivated(anyBoolean());
        mLooper.dispatchAll();
        verify(mCardEmulationManager).onFieldChangeDetected(anyBoolean());
        verify(mApplication).sendBroadcastAsUser(any(), any());
        verify(mStatsdUtils).logFieldChanged(anyBoolean(), anyInt());
        verify(mNfcEventLog, times(3)).logEvent(any());
    }

    @Test
    public void testOnSeSelected() {
        mNfcService.onSeSelected();
        mLooper.dispatchAll();
        verify(mCardEmulationManager).onOffHostAidSelected();
    }

    @Test
    public void testOnUidToBackground() throws RemoteException {
        mNfcService.mState = NfcAdapter.STATE_ON;
        mNfcService.mNfcAdapter.disable(false, "com.android.test");
        mLooper.dispatchAll();
        NfcService.ReaderModeParams readerModeParams = mock(NfcService.ReaderModeParams.class);
        mNfcService.mReaderModeParams = readerModeParams;
        readerModeParams.uid = 1;
        IBinder binder = mock(IBinder.class);
        readerModeParams.binder = binder;
        NfcService.DiscoveryTechParams discoveryTechParams =
                mock(NfcService.DiscoveryTechParams.class);
        discoveryTechParams.uid = 1;
        discoveryTechParams.binder = binder;
        mNfcService.mDiscoveryTechParams = discoveryTechParams;
        mNfcService.onUidToBackground(1);
        verify(binder, times(2)).unlinkToDeath(any(), anyInt());
        Assert.assertNull(mNfcService.mReaderModeParams);
        verify(binder, times(2)).unlinkToDeath(any(), anyInt());
        verify(mDeviceHost).resetDiscoveryTech();
        Assert.assertNull(mNfcService.mDiscoveryTechParams);
    }

    @Test
    public void testStateToProtoEnum() {
        int result = NfcService.stateToProtoEnum(NfcAdapter.STATE_OFF);
        assertThat(result).isEqualTo(NfcServiceDumpProto.STATE_OFF);
        result = NfcService.stateToProtoEnum(NfcAdapter.STATE_TURNING_ON);
        assertThat(result).isEqualTo(NfcServiceDumpProto.STATE_TURNING_ON);
        result = NfcService.stateToProtoEnum(NfcAdapter.STATE_ON);
        assertThat(result).isEqualTo(NfcServiceDumpProto.STATE_ON);
        result = NfcService.stateToProtoEnum(NfcAdapter.STATE_TURNING_OFF);
        assertThat(result).isEqualTo(NfcServiceDumpProto.STATE_TURNING_OFF);
        result = NfcService.stateToProtoEnum(0);
        assertThat(result).isEqualTo(NfcServiceDumpProto.STATE_UNKNOWN);
    }
    @Test
    public void testUnregisterObject() {
        DeviceHost.TagEndpoint tagEndpoint = mock(DeviceHost.TagEndpoint.class);
        when(tagEndpoint.getHandle()).thenReturn(1);
        mNfcService.registerTagObject(tagEndpoint);
        mNfcService.unregisterObject(1);
        assertThat(mNfcService.mObjectMap.get(1)).isNull();
    }

    @Test
    public void testNfcServiceOnReceive() {
        BroadcastReceiver receiver = mBroadcastReceiverArgumentCaptor.getValue();
        Bundle bundle = new Bundle();
        bundle.putBoolean(UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO, true);
        when(mUserManager.getUserRestrictions()).thenReturn(bundle);
        Assert.assertNotNull(receiver);
        mNfcService.mIsNfcUserRestricted = false;
        when(mPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        when(mNfcInjector.isSatelliteModeOn()).thenReturn(false);
        receiver.onReceive(mApplication, new Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED));
        verify(mUserManager, atLeastOnce()).getUserRestrictions();

    }

    @Test
    public void testDiscoveryTechDeathRecipient_BinderDied() {
        mNfcService.mState = NfcAdapter.STATE_ON;
        mNfcService.mDiscoveryTechParams = mock(NfcService.DiscoveryTechParams.class);
        NfcService.DiscoveryTechDeathRecipient discoveryTechDeathRecipient = mNfcService
                .new DiscoveryTechDeathRecipient();
        discoveryTechDeathRecipient.binderDied();
        verify(mDeviceHost).resetDiscoveryTech();
        assertThat(mNfcService.mDiscoveryTechParams).isNull();
    }

    @Test
    public void testDisableAlwaysOnInternal() throws RemoteException {
        mNfcService.mAlwaysOnState = NfcAdapter.STATE_OFF;
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.DISABLE);
        mLooper.dispatchAll();

        mNfcService.mAlwaysOnState = NfcAdapter.STATE_TURNING_OFF;
        mNfcService.mAlwaysOnMode = NfcOemExtension.DISABLE;
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.DISABLE);
        mLooper.dispatchAll();
        assertThat(mNfcService.mAlwaysOnMode).isEqualTo(NfcOemExtension.DISABLE);

        mNfcService.mState = NfcAdapter.STATE_ON;
        mNfcService.mAlwaysOnState = NfcAdapter.STATE_TURNING_ON;
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.DISABLE);
        mLooper.dispatchAll();
        verify(mDeviceHost).setNfceePowerAndLinkCtrl(false);

        mNfcService.mState = NfcAdapter.STATE_OFF;
        mNfcService.mNfcAdapter.setControllerAlwaysOn(NfcOemExtension.DISABLE);
        mLooper.dispatchAll();
        verify(mDeviceHost).setNfceePowerAndLinkCtrl(false);

    }

}
