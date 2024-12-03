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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.AidGroup;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.nfc.cardemulation.OffHostApduService;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Pair;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.NfcService;
import com.android.nfc.NfcStatsLog;

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
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class RegisteredServicesCacheTest {

    private static final int USER_ID = 1;
    private static final int SERVICE_UID = 1;
    private static final int SYSTEM_ID = 0;
    private static final UserHandle USER_HANDLE = UserHandle.of(USER_ID);
    private static final UserHandle SYSTEM_USER_HANDLE = UserHandle.of(SYSTEM_ID);
    private static final UserHandle USER_HANDLE_QUITE_MODE = UserHandle.of(2);
    private static final File DIR = new File("/");
    private static final String MOCK_FILE_PATH = "mockfile";
    private static final String MOCK_FILE_EXT = ".xml";
    private static final String ANOTHER_PACKAGE_NAME = "com.android.test.another";
    private static final String NON_PAYMENT_NFC_PACKAGE_NAME = "com.android.test.nonpaymentnfc";
    private static final String WALLET_HOLDER_PACKAGE_NAME = "com.android.test.walletroleholder";
    private static final String ON_HOST_SERVICE_NAME
            = "com.android.test.walletroleholder.OnHostApduService";
    private static final String OFF_HOST_SERVICE_NAME
            = "com.android.test.another.OffHostApduService";
    private static final String NON_PAYMENT_SERVICE_NAME
            = "com.android.test.nonpaymentnfc.NonPaymentApduService";
    private static final ComponentName WALLET_HOLDER_SERVICE_COMPONENT =
            new ComponentName(WALLET_HOLDER_PACKAGE_NAME, ON_HOST_SERVICE_NAME);
    private static final ComponentName NON_PAYMENT_SERVICE_COMPONENT =
            new ComponentName(NON_PAYMENT_NFC_PACKAGE_NAME, NON_PAYMENT_SERVICE_NAME);
    private static final ComponentName ANOTHER_SERVICE_COMPONENT =
            new ComponentName(ANOTHER_PACKAGE_NAME, OFF_HOST_SERVICE_NAME);
    private static final String OFFHOST_SE_STRING = "offhostse";
    private static final String TRUE_STRING = "true";
    private static final String FALSE_STRING = "false";
    private static final String ANDROID_STRING = "android";
    private static final List<String> PAYMENT_AIDS = List.of("A000000004101011",
            "A000000004101012", "A000000004101013");
    private static final List<String> NON_PAYMENT_AID = List.of("F053414950454D");

    @Mock
    private Context mContext;
    @Mock
    private RegisteredServicesCache.Callback mCallback;
    @Mock
    private UserManager mUserManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private RegisteredServicesCache.SettingsFile mDynamicSettingsFile;
    @Mock
    private RegisteredServicesCache.SettingsFile mOtherSettingsFile;
    @Mock
    private RegisteredServicesCache.ServiceParser mServiceParser;
    @Mock
    private RoutingOptionManager mRoutingOptionManager;
    @Captor
    private ArgumentCaptor<BroadcastReceiver> mReceiverArgumentCaptor;
    @Captor
    private ArgumentCaptor<IntentFilter> mIntentFilterArgumentCaptor;
    @Captor
    private ArgumentCaptor<PackageManager.ResolveInfoFlags> mFlagArgumentCaptor;
    @Captor
    private ArgumentCaptor<Intent> mIntentArgumentCaptor;
    @Captor
    private ArgumentCaptor<List<ApduServiceInfo>> mApduServiceListCaptor;
    @Captor
    private ArgumentCaptor<FileOutputStream> fileOutputStreamArgumentCaptor;

    private MockitoSession mStaticMockSession;
    private RegisteredServicesCache mRegisteredServicesCache;
    private Map<String, ApduServiceInfo> mMappedServices;
    private File mMockFile = new File(MOCK_FILE_PATH + MOCK_FILE_EXT);

    @Before
    public void setUp() throws PackageManager.NameNotFoundException, XmlPullParserException,
            IOException {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(com.android.nfc.flags.Flags.class)
                .mockStatic(ActivityManager.class)
                .mockStatic(NfcStatsLog.class)
                .mockStatic(UserHandle.class)
                .mockStatic(NfcAdapter.class)
                .mockStatic(NfcService.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);
        if (!mMockFile.exists()) {
            mMockFile = File.createTempFile(MOCK_FILE_PATH, MOCK_FILE_EXT);
        }
        mMappedServices = new HashMap<>();
        when(UserHandle.getUserHandleForUid(eq(USER_ID))).thenReturn(USER_HANDLE);
        when(UserHandle.getUserHandleForUid(eq(SYSTEM_ID))).thenReturn(SYSTEM_USER_HANDLE);
        when(UserHandle.of(eq(SYSTEM_ID))).thenReturn(SYSTEM_USER_HANDLE);
        when(UserHandle.of(eq(USER_ID))).thenReturn(USER_HANDLE);
        when(ActivityManager.getCurrentUser()).thenReturn(USER_ID);
        when(mContext.getSystemService(eq(UserManager.class))).thenReturn(mUserManager);
        when(mContext.getFilesDir()).thenReturn(DIR);
        when(mContext.createContextAsUser(
                any(), anyInt())).thenReturn(mContext);
        when(mContext.createPackageContextAsUser(
                any(), anyInt(), any())).thenReturn(mContext);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        List<UserHandle> enabledProfiles = new ArrayList<>();
        enabledProfiles.add(USER_HANDLE);
        enabledProfiles.add(USER_HANDLE_QUITE_MODE);
        when(mUserManager.getEnabledProfiles()).thenReturn(enabledProfiles);
        when(mUserManager.isQuietModeEnabled(eq(USER_HANDLE))).thenReturn(false);
        when(mUserManager.isQuietModeEnabled(eq(USER_HANDLE_QUITE_MODE))).thenReturn(true);
        when(mUserManager.getProfileParent(eq(USER_HANDLE))).thenReturn(USER_HANDLE);
        List<ResolveInfo> onHostServicesList = new ArrayList<>();
        onHostServicesList.add(createServiceResolveInfo(true,
                WALLET_HOLDER_PACKAGE_NAME, ON_HOST_SERVICE_NAME,
                List.of(CardEmulation.CATEGORY_PAYMENT)));
        onHostServicesList.add(createServiceResolveInfo(false,
                NON_PAYMENT_NFC_PACKAGE_NAME, NON_PAYMENT_SERVICE_NAME,
                List.of(CardEmulation.CATEGORY_OTHER)));
        List<ResolveInfo> offHostServicesList = new ArrayList<>();
        offHostServicesList.add(createServiceResolveInfo(true, ANOTHER_PACKAGE_NAME,
                OFF_HOST_SERVICE_NAME, List.of(CardEmulation.CATEGORY_OTHER)));
        when(mPackageManager.queryIntentServicesAsUser(
                any(), any(), any())).thenAnswer(invocation -> {
                    Intent intent = invocation.getArgument(0);
                    if(intent.getAction().equals(OffHostApduService.SERVICE_INTERFACE)) {
                        return offHostServicesList;
                    }
                    if(intent.getAction().equals(HostApduService.SERVICE_INTERFACE)) {
                        return onHostServicesList;
                    }
                    return List.of();
                });
        when(mServiceParser.parseApduService(any(), any(), anyBoolean()))
                .thenAnswer(invocation -> {
                    ResolveInfo resolveInfo = invocation.getArgument(1);
                    if(mMappedServices.containsKey(resolveInfo.serviceInfo.name)) {
                        return mMappedServices.get(resolveInfo.serviceInfo.name);
                    }
                    return null;
                });
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
        if (mMockFile.exists()) {
            mMockFile.delete();
        }
    }

    // Intent filter registration is actually not happening. It's just a mock verification.
    @SuppressWarnings({"UnspecifiedRegisterReceiverFlag", "GuardedBy"})
    @Test
    public void testConstructor() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mRoutingOptionManager);

        // Verify that the users handles are populated correctly
        assertEquals(1, mRegisteredServicesCache.mUserHandles.size());
        assertEquals(USER_HANDLE, mRegisteredServicesCache.mUserHandles.get(0));
        // Verify that broadcast receivers for apk changes are created and registered properly
        assertNotNull(mRegisteredServicesCache.mReceiver.get());
        verify(mContext).createContextAsUser(eq(USER_HANDLE), eq(0));
        verify(mContext, times(2)).registerReceiverForAllUsers(
                mReceiverArgumentCaptor.capture(), mIntentFilterArgumentCaptor.capture(),
                eq(null), eq(null));
        IntentFilter packageInstallTrackerIntent = mIntentFilterArgumentCaptor
                .getAllValues().get(0);
        assertTrue(packageInstallTrackerIntent.hasAction(Intent.ACTION_PACKAGE_ADDED));
        assertTrue(packageInstallTrackerIntent.hasAction(Intent.ACTION_PACKAGE_CHANGED));
        assertTrue(packageInstallTrackerIntent.hasAction(Intent.ACTION_PACKAGE_REMOVED));
        assertTrue(packageInstallTrackerIntent.hasAction(Intent.ACTION_PACKAGE_REPLACED));
        assertTrue(packageInstallTrackerIntent.hasAction(Intent.ACTION_PACKAGE_FIRST_LAUNCH));
        assertTrue(packageInstallTrackerIntent.hasAction(Intent.ACTION_PACKAGE_RESTARTED));
        assertTrue(packageInstallTrackerIntent
                .hasDataScheme(RegisteredServicesCache.PACKAGE_DATA));
        IntentFilter sdCardIntentFilter = mIntentFilterArgumentCaptor.getAllValues().get(1);
        assertTrue(sdCardIntentFilter.hasAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE));
        assertTrue(sdCardIntentFilter.hasAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE));
        assertEquals(mRegisteredServicesCache.mReceiver.get(),
                mReceiverArgumentCaptor.getAllValues().get(0));
        assertEquals(mRegisteredServicesCache.mReceiver.get(),
                mReceiverArgumentCaptor.getAllValues().get(1));
        verify(mContext, times(2)).getFilesDir();
        // Verify that correct file setting directories are set
        assertEquals(DIR,
            mRegisteredServicesCache.mDynamicSettingsFile.getBaseFile().getParentFile());
        assertEquals(DIR + RegisteredServicesCache.AID_XML_PATH,
            mRegisteredServicesCache.mDynamicSettingsFile.getBaseFile().getAbsolutePath());
        assertEquals(DIR, mRegisteredServicesCache.mOthersFile.getBaseFile().getParentFile());
        assertEquals(DIR + RegisteredServicesCache.OTHER_STATUS_PATH,
            mRegisteredServicesCache.mOthersFile.getBaseFile().getAbsolutePath());
    }

    @Test
    public void testInitialize_filesExist() throws IOException,
            PackageManager.NameNotFoundException {
        InputStream dynamicSettingsIs = InstrumentationRegistry.getInstrumentation()
                .getContext().getResources().getAssets()
                .open(RegisteredServicesCache.AID_XML_PATH);
        InputStream otherSettingsIs = InstrumentationRegistry.getInstrumentation()
                .getContext().getResources().getAssets()
                .open(RegisteredServicesCache.OTHER_STATUS_PATH);
        when(mDynamicSettingsFile.openRead()).thenReturn(dynamicSettingsIs);
        when(mOtherSettingsFile.openRead()).thenReturn(otherSettingsIs);
        when(mDynamicSettingsFile.exists()).thenReturn(true);
        when(mOtherSettingsFile.exists()).thenReturn(true);
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);

        mRegisteredServicesCache.initialize();

        // Verify that file operations are called
        verify(mDynamicSettingsFile).exists();
        verify(mOtherSettingsFile).exists();
        verify(mDynamicSettingsFile).openRead();
        verify(mOtherSettingsFile).openRead();
        verifyNoMoreInteractions(mDynamicSettingsFile);
        verifyNoMoreInteractions(mOtherSettingsFile);
        // Verify that user services are read properly
        assertEquals(1, mRegisteredServicesCache.mUserServices.size());
        RegisteredServicesCache.UserServices userServices
                = mRegisteredServicesCache.mUserServices.get(USER_ID);
        assertEquals(2, userServices.services.size());
        assertTrue(userServices.services.containsKey(WALLET_HOLDER_SERVICE_COMPONENT));
        assertTrue(userServices.services.containsKey(ANOTHER_SERVICE_COMPONENT));
        assertEquals(3, userServices.dynamicSettings.size());
        // Verify that dynamic settings are read properly
        assertTrue(userServices.dynamicSettings.containsKey(WALLET_HOLDER_SERVICE_COMPONENT));
        assertTrue(userServices.dynamicSettings.containsKey(NON_PAYMENT_SERVICE_COMPONENT));
        // Verify that dynamic settings are properly populated for each service in the xml
        // Verify the details of service 1
        RegisteredServicesCache.DynamicSettings walletHolderSettings =
                userServices.dynamicSettings.get(WALLET_HOLDER_SERVICE_COMPONENT);
        assertEquals(OFFHOST_SE_STRING + "1", walletHolderSettings.offHostSE);
        assertEquals(1, walletHolderSettings.uid);
        assertEquals(TRUE_STRING, walletHolderSettings.shouldDefaultToObserveModeStr);
        assertTrue(walletHolderSettings.aidGroups.containsKey(CardEmulation.CATEGORY_PAYMENT));
        assertTrue(walletHolderSettings.aidGroups.get(CardEmulation.CATEGORY_PAYMENT)
                        .getAids().containsAll(PAYMENT_AIDS));
        assertFalse(walletHolderSettings.aidGroups.containsKey(CardEmulation.CATEGORY_OTHER));
        // Verify the details of service 2
        RegisteredServicesCache.DynamicSettings nonPaymentSettings =
                userServices.dynamicSettings.get(NON_PAYMENT_SERVICE_COMPONENT);
        assertEquals(OFFHOST_SE_STRING + "2", nonPaymentSettings.offHostSE);
        assertEquals(1, nonPaymentSettings.uid);
        assertEquals(FALSE_STRING, nonPaymentSettings.shouldDefaultToObserveModeStr);
        assertTrue(nonPaymentSettings.aidGroups.containsKey(CardEmulation.CATEGORY_OTHER));
        assertTrue(nonPaymentSettings.aidGroups.get(CardEmulation.CATEGORY_OTHER)
                .getAids().containsAll(NON_PAYMENT_AID));
        // Verify that other settings are read properly
        assertEquals(1, userServices.others.size());
        assertTrue(userServices.others.containsKey(ANOTHER_SERVICE_COMPONENT));
        RegisteredServicesCache.OtherServiceStatus otherServiceStatus
                = userServices.others.get(ANOTHER_SERVICE_COMPONENT);
        assertTrue(otherServiceStatus.checked);
        assertEquals(1, otherServiceStatus.uid);
        // Verify that the installed services are populated properly
        verify(mContext)
                .createPackageContextAsUser(eq(ANDROID_STRING), eq(0), eq(USER_HANDLE));
        verify(mContext).getPackageManager();
        verify(mPackageManager, times(2))
                .queryIntentServicesAsUser(mIntentArgumentCaptor.capture(),
                        mFlagArgumentCaptor.capture(), eq(USER_HANDLE));
        Intent onHostIntent = mIntentArgumentCaptor.getAllValues().get(0);
        assertEquals(HostApduService.SERVICE_INTERFACE, onHostIntent.getAction());
        Intent offHostIntent = mIntentArgumentCaptor.getAllValues().get(1);
        assertEquals(OffHostApduService.SERVICE_INTERFACE, offHostIntent.getAction());
        PackageManager.ResolveInfoFlags onHostFlag = mFlagArgumentCaptor.getAllValues().get(0);
        assertEquals(PackageManager.GET_META_DATA, onHostFlag.getValue());
        PackageManager.ResolveInfoFlags offHostFlag = mFlagArgumentCaptor.getAllValues().get(1);
        assertEquals(PackageManager.GET_META_DATA, offHostFlag.getValue());
        // Verify that the installed services are filtered properly
        verify(mPackageManager).checkPermission(eq(android.Manifest.permission.NFC),
                eq(WALLET_HOLDER_PACKAGE_NAME));
        verify(mPackageManager).checkPermission(eq(android.Manifest.permission.NFC),
                eq(NON_PAYMENT_NFC_PACKAGE_NAME));
        verify(mPackageManager).checkPermission(eq(android.Manifest.permission.NFC),
                eq(ANOTHER_PACKAGE_NAME));
        // Verify that the callback is called with properly installed and filtered services.
        verify(mCallback).onServicesUpdated(eq(USER_ID), mApduServiceListCaptor.capture(),
                eq(false));
        List<ApduServiceInfo> apduServiceInfos = mApduServiceListCaptor.getValue();
        assertEquals(2, apduServiceInfos.size());
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, apduServiceInfos.get(0).getComponent());
        assertEquals(ANOTHER_SERVICE_COMPONENT, apduServiceInfos.get(1).getComponent());
    }

    @Test
    public void testInitialize_filesDontExist() throws IOException {
        MockFileOutputStream mockFileOutputStream = new MockFileOutputStream();
        when(mDynamicSettingsFile.exists()).thenReturn(false);
        when(mOtherSettingsFile.exists()).thenReturn(false);
        when(mOtherSettingsFile.startWrite()).thenReturn(mockFileOutputStream);

        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        // Verify that files are NOT read
        verify(mDynamicSettingsFile).exists();
        verifyNoMoreInteractions(mDynamicSettingsFile);
        verify(mOtherSettingsFile).exists();
        verify(mOtherSettingsFile).startWrite();
        verify(mOtherSettingsFile).finishWrite(fileOutputStreamArgumentCaptor.capture());
        verifyNoMoreInteractions(mOtherSettingsFile);
        // Validate that no dynamic settings are read
        assertEquals(1, mRegisteredServicesCache.mUserServices.size());
        RegisteredServicesCache.UserServices userServices
                = mRegisteredServicesCache.mUserServices.get(USER_ID);
        assertTrue(userServices.dynamicSettings.isEmpty());
        // Verify that other settings are only read from system services
        assertEquals(1, userServices.others.size());
        assertTrue(userServices.others.containsKey(ANOTHER_SERVICE_COMPONENT));
        RegisteredServicesCache.OtherServiceStatus otherServiceStatus
                = userServices.others.get(ANOTHER_SERVICE_COMPONENT);
        assertTrue(otherServiceStatus.checked);
        assertEquals(USER_ID, otherServiceStatus.uid);
        // Verify that the callback is called with properly installed and filtered services.
        verify(mCallback).onServicesUpdated(eq(USER_ID), mApduServiceListCaptor.capture(),
                eq(false));
        List<ApduServiceInfo> apduServiceInfos = mApduServiceListCaptor.getValue();
        assertEquals(2, apduServiceInfos.size());
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, apduServiceInfos.get(0).getComponent());
        assertEquals(ANOTHER_SERVICE_COMPONENT, apduServiceInfos.get(1).getComponent());
        // Validate that other setting file is written properly with a setting
        // that previously did not exists.
        InputStream otherSettingsIs = mockFileOutputStream.toInputStream();
        RegisteredServicesCache.SettingsFile otherSettingsFile
                = Mockito.mock(RegisteredServicesCache.SettingsFile.class);
        when(otherSettingsFile.openRead()).thenReturn(otherSettingsIs);
        when(otherSettingsFile.exists()).thenReturn(true);
        Map<Integer, List<Pair<ComponentName, RegisteredServicesCache.OtherServiceStatus>>>
                readOtherSettingsFromFile = RegisteredServicesCache
                .readOtherFromFile(otherSettingsFile);
        assertEquals(mockFileOutputStream, fileOutputStreamArgumentCaptor.getValue());
        assertTrue(readOtherSettingsFromFile.containsKey(USER_ID));
        assertFalse(readOtherSettingsFromFile.get(USER_ID).isEmpty());
        assertEquals(ANOTHER_SERVICE_COMPONENT,
            readOtherSettingsFromFile.get(USER_ID).get(0).first);
        assertTrue(readOtherSettingsFromFile.get(USER_ID).get(0).second.checked);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testOnUserSwitched() {
        List<UserHandle> enabledProfiles = new ArrayList<>();
        // Do not trigger user switch in constructor. Send empty list.
        when(mUserManager.getEnabledProfiles()).thenReturn(enabledProfiles);
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        enabledProfiles.add(USER_HANDLE);
        enabledProfiles.add(USER_HANDLE_QUITE_MODE);
        when(mUserManager.getEnabledProfiles()).thenReturn(enabledProfiles);

        mRegisteredServicesCache.onUserSwitched();

        // Validate that quite mode profiles get filtered out.
        assertEquals(1, mRegisteredServicesCache.mUserHandles.size());
        assertEquals(USER_HANDLE, mRegisteredServicesCache.mUserHandles.get(0));
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testOnManagedProfileChanged() {
        List<UserHandle> enabledProfiles = new ArrayList<>();
        // Do not trigger user switch in constructor. Send empty list.
        when(mUserManager.getEnabledProfiles()).thenReturn(enabledProfiles);
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        enabledProfiles.add(USER_HANDLE);
        enabledProfiles.add(USER_HANDLE_QUITE_MODE);
        when(mUserManager.getEnabledProfiles()).thenReturn(enabledProfiles);

        mRegisteredServicesCache.onManagedProfileChanged();

        // Validate that quite mode profiles get filtered out.
        assertEquals(1, mRegisteredServicesCache.mUserHandles.size());
        assertEquals(USER_HANDLE, mRegisteredServicesCache.mUserHandles.get(0));
    }

    @Test
    public void testHasService() {
        when(mDynamicSettingsFile.exists()).thenReturn(false);
        when(mOtherSettingsFile.exists()).thenReturn(false);
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertTrue(mRegisteredServicesCache.hasService(USER_ID, ANOTHER_SERVICE_COMPONENT));
        assertTrue(mRegisteredServicesCache.hasService(USER_ID, WALLET_HOLDER_SERVICE_COMPONENT));
    }

    @Test
    public void testGetService() {
        when(mDynamicSettingsFile.exists()).thenReturn(false);
        when(mOtherSettingsFile.exists()).thenReturn(false);
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        ApduServiceInfo serviceInfo = mRegisteredServicesCache.getService(USER_ID,
                WALLET_HOLDER_SERVICE_COMPONENT);
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, serviceInfo.getComponent());
        assertEquals(SERVICE_UID, serviceInfo.getUid());
    }

    @Test
    public void testGetServices() {
        when(mDynamicSettingsFile.exists()).thenReturn(false);
        when(mOtherSettingsFile.exists()).thenReturn(false);
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        List<ApduServiceInfo> serviceInfos = mRegisteredServicesCache.getServices(USER_ID);
        assertFalse(serviceInfos.isEmpty());
        assertEquals(2, serviceInfos.size());
        assertEquals(ANOTHER_SERVICE_COMPONENT, serviceInfos.get(0).getComponent());
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, serviceInfos.get(1).getComponent());
    }

    @Test
    public void testGetServicesForCategory_paymentCategory() {
        when(mDynamicSettingsFile.exists()).thenReturn(false);
        when(mOtherSettingsFile.exists()).thenReturn(false);
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        List<ApduServiceInfo> serviceInfos = mRegisteredServicesCache
                .getServicesForCategory(USER_ID, CardEmulation.CATEGORY_PAYMENT);
        assertFalse(serviceInfos.isEmpty());
        assertEquals(1, serviceInfos.size());
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, serviceInfos.get(0).getComponent());
    }

    @Test
    public void testGetServicesForCategory_otherCategory() {
        when(mDynamicSettingsFile.exists()).thenReturn(false);
        when(mOtherSettingsFile.exists()).thenReturn(false);
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        List<ApduServiceInfo> serviceInfos = mRegisteredServicesCache
                .getServicesForCategory(USER_ID, CardEmulation.CATEGORY_OTHER);
        assertFalse(serviceInfos.isEmpty());
        assertEquals(1, serviceInfos.size());
        assertEquals(ANOTHER_SERVICE_COMPONENT, serviceInfos.get(0).getComponent());
    }

    @Test
    public void testSetOffhostSecureElement_nonExistingService() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        ComponentName wrongComponentName = new ComponentName("test","com.wrong.class");

        assertFalse(mRegisteredServicesCache.setOffHostSecureElement(USER_ID,
                SERVICE_UID, wrongComponentName, "offhostse1"));
    }

    @Test
    public void testSetOffhostSecureElement_wrongServiceUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertFalse(mRegisteredServicesCache.setOffHostSecureElement(USER_ID,
                3, WALLET_HOLDER_SERVICE_COMPONENT, "offhostse1"));
    }

    @Test
    public void testSetOffhostSecureElement_nullOffHostSet() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertFalse(mRegisteredServicesCache.setOffHostSecureElement(USER_ID,
                SERVICE_UID, WALLET_HOLDER_SERVICE_COMPONENT, null));
    }

    @Test
    public void testSetOffhostSecureElement_existingService_correctUid_nonNullSE()
            throws IOException {
        InputStream dynamicSettingsIs = InstrumentationRegistry.getInstrumentation()
                .getContext().getResources().getAssets()
                .open(RegisteredServicesCache.AID_XML_PATH);
        MockFileOutputStream mockFileOutputStream = new MockFileOutputStream();
        when(mDynamicSettingsFile.exists()).thenReturn(true);
        when(mDynamicSettingsFile.openRead()).thenReturn(dynamicSettingsIs);
        when(mOtherSettingsFile.exists()).thenReturn(false);
        when(mDynamicSettingsFile.startWrite()).thenReturn(mockFileOutputStream);
        when(mOtherSettingsFile.startWrite()).thenReturn(new MockFileOutputStream());
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        String newOffHostValue = "newOffhostValue";

        assertTrue(mRegisteredServicesCache.setOffHostSecureElement(USER_ID,
                SERVICE_UID, WALLET_HOLDER_SERVICE_COMPONENT, newOffHostValue));
        verify(mDynamicSettingsFile).exists();
        verify(mDynamicSettingsFile).openRead();
        verify(mDynamicSettingsFile).startWrite();
        verify(mDynamicSettingsFile).finishWrite(fileOutputStreamArgumentCaptor.capture());
        verifyNoMoreInteractions(mDynamicSettingsFile);
        assertEquals(mockFileOutputStream, fileOutputStreamArgumentCaptor.getValue());
        // Verify that the callback is called with properly installed and filtered services.
        verify(mCallback).onServicesUpdated(eq(USER_ID), mApduServiceListCaptor.capture(),
                eq(true));
        List<ApduServiceInfo> apduServiceInfos = mApduServiceListCaptor.getValue();
        assertEquals(2, apduServiceInfos.size());
        assertEquals(ANOTHER_SERVICE_COMPONENT, apduServiceInfos.get(0).getComponent());
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, apduServiceInfos.get(1).getComponent());
        verify(apduServiceInfos.get(1)).setOffHostSecureElement(eq(newOffHostValue));
        // Verify that dynamic settings file is updated
        InputStream dynamicSettingsMockIs = mockFileOutputStream.toInputStream();
        RegisteredServicesCache.SettingsFile dynamicSettingsFile
                = Mockito.mock(RegisteredServicesCache.SettingsFile.class);
        when(dynamicSettingsFile.openRead()).thenReturn(dynamicSettingsMockIs);
        when(dynamicSettingsFile.exists()).thenReturn(true);
        Map<Integer, List<Pair<ComponentName, RegisteredServicesCache.DynamicSettings>>>
                readDynamicSettingsFromFile = RegisteredServicesCache
                .readDynamicSettingsFromFile(dynamicSettingsFile);
        assertFalse(readDynamicSettingsFromFile.isEmpty());
        assertTrue(readDynamicSettingsFromFile.containsKey(USER_ID));
        RegisteredServicesCache.DynamicSettings dynamicSettings
                = readDynamicSettingsFromFile.get(USER_ID).get(1).second;
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT,
                readDynamicSettingsFromFile.get(USER_ID).get(1).first);
        assertEquals(newOffHostValue, dynamicSettings.offHostSE);
    }

    @Test
    public void testResetOffhostSecureElement_nonExistingService() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        ComponentName wrongComponentName = new ComponentName("test","com.wrong.class");

        assertFalse(mRegisteredServicesCache.resetOffHostSecureElement(USER_ID,
                SERVICE_UID, wrongComponentName));
    }

    @Test
    public void testResetOffhostSecureElement_wrongServiceUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertFalse(mRegisteredServicesCache.resetOffHostSecureElement(USER_ID,
                3, WALLET_HOLDER_SERVICE_COMPONENT));
    }

    @Test
    public void testResetOffhostSecureElement_nullOffHostSet() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertFalse(mRegisteredServicesCache.resetOffHostSecureElement(USER_ID,
                SERVICE_UID, WALLET_HOLDER_SERVICE_COMPONENT));
    }

    @Test
    public void testResetOffhostSecureElement_existingService_correctUid()
            throws IOException {
        InputStream dynamicSettingsIs = InstrumentationRegistry.getInstrumentation()
                .getContext().getResources().getAssets()
                .open(RegisteredServicesCache.AID_XML_PATH);
        MockFileOutputStream mockFileOutputStream = new MockFileOutputStream();
        when(mDynamicSettingsFile.exists()).thenReturn(true);
        when(mDynamicSettingsFile.openRead()).thenReturn(dynamicSettingsIs);
        when(mOtherSettingsFile.exists()).thenReturn(false);
        when(mDynamicSettingsFile.startWrite()).thenReturn(mockFileOutputStream);
        when(mOtherSettingsFile.startWrite()).thenReturn(new MockFileOutputStream());
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        when(mRegisteredServicesCache.getService(USER_ID, ANOTHER_SERVICE_COMPONENT)
                .getOffHostSecureElement()).thenReturn("offhost");

        assertTrue(mRegisteredServicesCache.resetOffHostSecureElement(USER_ID,
                SERVICE_UID, ANOTHER_SERVICE_COMPONENT));
        verify(mDynamicSettingsFile).exists();
        verify(mDynamicSettingsFile).openRead();
        verify(mDynamicSettingsFile).startWrite();
        verify(mDynamicSettingsFile).finishWrite(fileOutputStreamArgumentCaptor.capture());
        verifyNoMoreInteractions(mDynamicSettingsFile);
        assertEquals(mockFileOutputStream, fileOutputStreamArgumentCaptor.getValue());
        // Verify that the callback is called with properly installed and filtered services.
        verify(mCallback).onServicesUpdated(eq(USER_ID), mApduServiceListCaptor.capture(),
                eq(true));
        List<ApduServiceInfo> apduServiceInfos = mApduServiceListCaptor.getValue();
        assertEquals(2, apduServiceInfos.size());
        assertEquals(ANOTHER_SERVICE_COMPONENT, apduServiceInfos.get(0).getComponent());
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, apduServiceInfos.get(1).getComponent());
        verify(apduServiceInfos.get(0)).resetOffHostSecureElement();
        // Verify that dynamic settings file is updated
        InputStream dynamicSettingsMockIs = mockFileOutputStream.toInputStream();
        RegisteredServicesCache.SettingsFile dynamicSettingsFile
                = Mockito.mock(RegisteredServicesCache.SettingsFile.class);
        when(dynamicSettingsFile.openRead()).thenReturn(dynamicSettingsMockIs);
        when(dynamicSettingsFile.exists()).thenReturn(true);
        Map<Integer, List<Pair<ComponentName, RegisteredServicesCache.DynamicSettings>>>
                readDynamicSettingsFromFile = RegisteredServicesCache
                .readDynamicSettingsFromFile(dynamicSettingsFile);
        assertFalse(readDynamicSettingsFromFile.isEmpty());
        assertTrue(readDynamicSettingsFromFile.containsKey(USER_ID));
        RegisteredServicesCache.DynamicSettings dynamicSettings
                = readDynamicSettingsFromFile.get(USER_ID).get(0).second;
        assertEquals(ANOTHER_SERVICE_COMPONENT,
                readDynamicSettingsFromFile.get(USER_ID).get(0).first);
        assertNull(dynamicSettings.offHostSE);
    }

    @Test
    public void testSetShouldDefaultToObserveModeForService_nonExistingService() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        ComponentName wrongComponentName = new ComponentName("test","com.wrong.class");

        assertFalse(mRegisteredServicesCache.setShouldDefaultToObserveModeForService(USER_ID,
                SERVICE_UID, wrongComponentName, true));
    }

    @Test
    public void testSetShouldDefaultToObserveModeForService_wrongServiceUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertFalse(mRegisteredServicesCache.setShouldDefaultToObserveModeForService(USER_ID,
                3, WALLET_HOLDER_SERVICE_COMPONENT, true));
    }

    @Test
    public void testSetShouldDefaultToObserveModeForService_existingService_correctUid()
            throws IOException {
        InputStream dynamicSettingsIs = InstrumentationRegistry.getInstrumentation()
                .getContext().getResources().getAssets()
                .open(RegisteredServicesCache.AID_XML_PATH);
        when(mDynamicSettingsFile.exists()).thenReturn(true);
        when(mDynamicSettingsFile.openRead()).thenReturn(dynamicSettingsIs);
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertTrue(mRegisteredServicesCache.setShouldDefaultToObserveModeForService(USER_ID,
                SERVICE_UID, WALLET_HOLDER_SERVICE_COMPONENT, true));
        assertEquals("true", mRegisteredServicesCache.mUserServices.get(USER_ID)
                .dynamicSettings.get(WALLET_HOLDER_SERVICE_COMPONENT)
                .shouldDefaultToObserveModeStr);
        verify(mRegisteredServicesCache.getService(USER_ID, WALLET_HOLDER_SERVICE_COMPONENT),
                times(2)).setShouldDefaultToObserveMode(eq(true));
    }

    @Test
    public void testRegisterPollingLoopFilterForService_nonExistingService() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        ComponentName wrongComponentName = new ComponentName("test","com.wrong.class");

        assertFalse(mRegisteredServicesCache.registerPollingLoopFilterForService(USER_ID,
                SERVICE_UID, wrongComponentName, "empty", true));
    }

    @Test
    public void testRegisterPollingLoopFilterForService_wrongUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertFalse(mRegisteredServicesCache.registerPollingLoopFilterForService(USER_ID,
                3, WALLET_HOLDER_SERVICE_COMPONENT, "empty", true));
    }

    @Test
    public void testRegisterPollingLoopFilterForService_existingService_correctUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        String plFilter = "afilter";

        assertTrue(mRegisteredServicesCache.registerPollingLoopFilterForService(USER_ID,
                SERVICE_UID, WALLET_HOLDER_SERVICE_COMPONENT, plFilter, true));
        // Verify that the callback is called with properly installed and filtered services.
        verify(mCallback).onServicesUpdated(eq(USER_ID), mApduServiceListCaptor.capture(),
                eq(true));
        List<ApduServiceInfo> apduServiceInfos = mApduServiceListCaptor.getValue();
        assertEquals(2, apduServiceInfos.size());
        assertEquals(ANOTHER_SERVICE_COMPONENT, apduServiceInfos.get(0)
                .getComponent());
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, apduServiceInfos.get(1)
                .getComponent());
        verify(apduServiceInfos.get(1)).addPollingLoopFilter(eq(plFilter), eq(true));
    }

    @Test
    public void testRemovePollingLoopFilterForService_nonExistingService() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        ComponentName wrongComponentName = new ComponentName("test","com.wrong.class");

        assertFalse(mRegisteredServicesCache.removePollingLoopFilterForService(USER_ID,
                SERVICE_UID, wrongComponentName, "empty"));
    }

    @Test
    public void testRemovePollingLoopFilterForService_wrongUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertFalse(mRegisteredServicesCache.removePollingLoopFilterForService(USER_ID,
                3, WALLET_HOLDER_SERVICE_COMPONENT, "empty"));
    }

    @Test
    public void testRemovePollingLoopFilterForService_existingService_correctUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        String plFilter = "afilter";

        assertTrue(mRegisteredServicesCache.removePollingLoopFilterForService(USER_ID,
                SERVICE_UID, WALLET_HOLDER_SERVICE_COMPONENT, plFilter));
        // Verify that the callback is called with properly installed and filtered services.
        verify(mCallback).onServicesUpdated(eq(USER_ID), mApduServiceListCaptor.capture(),
                eq(true));
        List<ApduServiceInfo> apduServiceInfos = mApduServiceListCaptor.getValue();
        assertEquals(2, apduServiceInfos.size());
        assertEquals(ANOTHER_SERVICE_COMPONENT, apduServiceInfos.get(0).getComponent());
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, apduServiceInfos.get(1).getComponent());
        verify(apduServiceInfos.get(1)).removePollingLoopFilter(eq(plFilter));
    }

    @Test
    public void testRegisterPollingLoopPatternFilterForService_nonExistingService() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        ComponentName wrongComponentName = new ComponentName("test","com.wrong.class");

        assertFalse(mRegisteredServicesCache.registerPollingLoopPatternFilterForService(
                USER_ID, SERVICE_UID, wrongComponentName, "empty",
                true));
    }

    @Test
    public void testRegisterPollingLoopPatterFilterForService_wrongUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertFalse(mRegisteredServicesCache
                .registerPollingLoopPatternFilterForService(USER_ID, 3,
                        WALLET_HOLDER_SERVICE_COMPONENT, "empty", true));
    }

    @Test
    public void testRegisterPollingLoopPatternFilterForService_existingService_correctUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        String plFilter = "afilter";

        assertTrue(mRegisteredServicesCache.registerPollingLoopPatternFilterForService(
                USER_ID, SERVICE_UID, WALLET_HOLDER_SERVICE_COMPONENT, plFilter, true));
        // Verify that the callback is called with properly installed and filtered services.
        verify(mCallback).onServicesUpdated(eq(USER_ID), mApduServiceListCaptor.capture(),
                eq(true));
        List<ApduServiceInfo> apduServiceInfos = mApduServiceListCaptor.getValue();
        assertEquals(2, apduServiceInfos.size());
        assertEquals(ANOTHER_SERVICE_COMPONENT, apduServiceInfos.get(0).getComponent());
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, apduServiceInfos.get(1).getComponent());
        verify(apduServiceInfos.get(1)).addPollingLoopPatternFilter(eq(plFilter), eq(true));
    }

    @Test
    public void testRemovePollingLoopPatternFilterForService_nonExistingService() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        ComponentName wrongComponentName = new ComponentName("test","com.wrong.class");

        assertFalse(mRegisteredServicesCache.removePollingLoopPatternFilterForService(
                USER_ID, SERVICE_UID, wrongComponentName, "empty"));
    }

    @Test
    public void testRemovePollingLoopPatternFilterForService_wrongUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertFalse(mRegisteredServicesCache.removePollingLoopFilterForService(USER_ID,
                3, WALLET_HOLDER_SERVICE_COMPONENT, "empty"));
    }

    @Test
    public void testRemovePollingLoopPatternFilterForService_existingService_correctUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        String plFilter = "afilter";

        assertTrue(mRegisteredServicesCache.removePollingLoopPatternFilterForService(USER_ID,
                SERVICE_UID, WALLET_HOLDER_SERVICE_COMPONENT, plFilter));
        // Verify that the callback is called with properly installed and filtered services.
        verify(mCallback).onServicesUpdated(eq(USER_ID), mApduServiceListCaptor.capture(),
                eq(true));
        List<ApduServiceInfo> apduServiceInfos = mApduServiceListCaptor.getValue();
        assertEquals(2, apduServiceInfos.size());
        assertEquals(ANOTHER_SERVICE_COMPONENT, apduServiceInfos.get(0).getComponent());
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, apduServiceInfos.get(1).getComponent());
        verify(apduServiceInfos.get(1)).removePollingLoopPatternFilter(eq(plFilter));
    }

    @Test
    public void testRegisterAidGroupForService_nonExistingService() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        ComponentName wrongComponentName = new ComponentName("test","com.wrong.class");
        AidGroup aidGroup = createAidGroup(CardEmulation.CATEGORY_PAYMENT);

        assertFalse(mRegisteredServicesCache.registerAidGroupForService(
                USER_ID, SERVICE_UID, wrongComponentName, aidGroup));
    }

    @Test
    public void testRegisterAidGroupForService_wrongUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        AidGroup aidGroup = createAidGroup(CardEmulation.CATEGORY_PAYMENT);

        assertFalse(mRegisteredServicesCache.registerAidGroupForService(USER_ID,
                3, WALLET_HOLDER_SERVICE_COMPONENT, aidGroup));
    }

    @Test
    public void testRegisterAidGroupForService_existingService_correctUid() throws IOException {
        InputStream dynamicSettingsIs = InstrumentationRegistry.getInstrumentation()
                .getContext().getResources().getAssets()
                .open(RegisteredServicesCache.AID_XML_PATH);
        MockFileOutputStream mockFileOutputStream = new MockFileOutputStream();
        when(mDynamicSettingsFile.exists()).thenReturn(true);
        when(mDynamicSettingsFile.openRead()).thenReturn(dynamicSettingsIs);
        when(mOtherSettingsFile.exists()).thenReturn(false);
        when(mDynamicSettingsFile.startWrite()).thenReturn(mockFileOutputStream);
        when(mOtherSettingsFile.startWrite()).thenReturn(new MockFileOutputStream());
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        AidGroup aidGroup = createAidGroup(CardEmulation.CATEGORY_OTHER);

        assertTrue(mRegisteredServicesCache.registerAidGroupForService(USER_ID,
                SERVICE_UID, WALLET_HOLDER_SERVICE_COMPONENT, aidGroup));

        ApduServiceInfo serviceInfo = mRegisteredServicesCache.getService(USER_ID,
                WALLET_HOLDER_SERVICE_COMPONENT);
        verify(serviceInfo).setDynamicAidGroup(eq(aidGroup));
        assertEquals(aidGroup, mRegisteredServicesCache.mUserServices.get(USER_ID)
                .dynamicSettings.get(WALLET_HOLDER_SERVICE_COMPONENT)
                .aidGroups.get(CardEmulation.CATEGORY_OTHER));
        // Verify that the callback is called with properly installed and filtered services.
        verify(mCallback).onServicesUpdated(eq(USER_ID), mApduServiceListCaptor.capture(),
                eq(true));
        List<ApduServiceInfo> apduServiceInfos = mApduServiceListCaptor.getValue();
        assertEquals(2, apduServiceInfos.size());
        assertEquals(ANOTHER_SERVICE_COMPONENT, apduServiceInfos.get(0).getComponent());
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, apduServiceInfos.get(1).getComponent());
        // Verify that dynamic settings file is updated
        InputStream dynamicSettingsMockIs = mockFileOutputStream.toInputStream();
        RegisteredServicesCache.SettingsFile dynamicSettingsFile
                = Mockito.mock(RegisteredServicesCache.SettingsFile.class);
        when(dynamicSettingsFile.openRead()).thenReturn(dynamicSettingsMockIs);
        when(dynamicSettingsFile.exists()).thenReturn(true);
        Map<Integer, List<Pair<ComponentName, RegisteredServicesCache.DynamicSettings>>>
                readDynamicSettingsFromFile = RegisteredServicesCache
                .readDynamicSettingsFromFile(dynamicSettingsFile);
        assertFalse(readDynamicSettingsFromFile.isEmpty());
        assertTrue(readDynamicSettingsFromFile.containsKey(USER_ID));
        RegisteredServicesCache.DynamicSettings dynamicSettings
                = readDynamicSettingsFromFile.get(USER_ID).get(0).second;
        assertEquals(ANOTHER_SERVICE_COMPONENT,
                readDynamicSettingsFromFile.get(USER_ID).get(0).first);
        assertTrue(dynamicSettings.aidGroups.containsKey(CardEmulation.CATEGORY_OTHER));
        assertEquals(aidGroup.getAids(),
                dynamicSettings.aidGroups.get(CardEmulation.CATEGORY_OTHER).getAids());
    }

    @Test
    public void testGetAidGroupForService_nonExistingService() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        ComponentName wrongComponentName = new ComponentName("test","com.wrong.class");

        assertNull(mRegisteredServicesCache.getAidGroupForService(
                USER_ID, SERVICE_UID, wrongComponentName, CardEmulation.CATEGORY_PAYMENT));
    }

    @Test
    public void testGetAidGroupForService_wrongUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertNull(mRegisteredServicesCache.getAidGroupForService(USER_ID,
                3, WALLET_HOLDER_SERVICE_COMPONENT, CardEmulation.CATEGORY_PAYMENT));
    }

    @Test
    public void testGetAidGroupForService_existingService_correctUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        ApduServiceInfo serviceInfo = mRegisteredServicesCache.getService(USER_ID,
                WALLET_HOLDER_SERVICE_COMPONENT);
        AidGroup aidGroup = createAidGroup(CardEmulation.CATEGORY_OTHER);
        when(serviceInfo.getDynamicAidGroupForCategory(eq(CardEmulation.CATEGORY_OTHER)))
                .thenReturn(aidGroup);

        AidGroup aidGroupReceived = mRegisteredServicesCache.getAidGroupForService(USER_ID,
                SERVICE_UID, WALLET_HOLDER_SERVICE_COMPONENT, CardEmulation.CATEGORY_OTHER);
        assertEquals(aidGroup, aidGroupReceived);
    }

    @Test
    public void testRemoveAidGroupForService_nonExistingService() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        ComponentName wrongComponentName = new ComponentName("test","com.wrong.class");

        assertFalse(mRegisteredServicesCache.removeAidGroupForService(
                USER_ID, SERVICE_UID, wrongComponentName, CardEmulation.CATEGORY_PAYMENT));
    }

    @Test
    public void testRemoveAidGroupForService_wrongUid() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();

        assertFalse(mRegisteredServicesCache.removeAidGroupForService(USER_ID,
                3, WALLET_HOLDER_SERVICE_COMPONENT, CardEmulation.CATEGORY_PAYMENT));
    }

    @Test
    public void testRemoveAidGroupForService_existingService_correctUid() throws IOException {
        InputStream dynamicSettingsIs = InstrumentationRegistry.getInstrumentation()
                .getContext().getResources().getAssets()
                .open(RegisteredServicesCache.AID_XML_PATH);
        MockFileOutputStream mockFileOutputStream = new MockFileOutputStream();
        when(mDynamicSettingsFile.exists()).thenReturn(true);
        when(mDynamicSettingsFile.openRead()).thenReturn(dynamicSettingsIs);
        when(mOtherSettingsFile.exists()).thenReturn(false);
        when(mDynamicSettingsFile.startWrite()).thenReturn(mockFileOutputStream);
        when(mOtherSettingsFile.startWrite()).thenReturn(new MockFileOutputStream());
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        mRegisteredServicesCache.initialize();
        ApduServiceInfo serviceInfo = mRegisteredServicesCache.getService(USER_ID,
                WALLET_HOLDER_SERVICE_COMPONENT);
        when(serviceInfo.removeDynamicAidGroupForCategory(eq(CardEmulation.CATEGORY_PAYMENT)))
                .thenReturn(true);

        assertTrue(mRegisteredServicesCache.removeAidGroupForService(USER_ID,
                SERVICE_UID, WALLET_HOLDER_SERVICE_COMPONENT, CardEmulation.CATEGORY_PAYMENT));

        verify(serviceInfo).removeDynamicAidGroupForCategory(eq(CardEmulation.CATEGORY_PAYMENT));
        assertFalse(mRegisteredServicesCache.mUserServices.get(USER_ID)
                .dynamicSettings.get(WALLET_HOLDER_SERVICE_COMPONENT)
                .aidGroups.containsKey(CardEmulation.CATEGORY_PAYMENT));
        // Verify that the callback is called with properly installed and filtered services.
        verify(mCallback).onServicesUpdated(eq(USER_ID), mApduServiceListCaptor.capture(),
                eq(true));
        List<ApduServiceInfo> apduServiceInfos = mApduServiceListCaptor.getValue();
        assertEquals(2, apduServiceInfos.size());
        assertEquals(ANOTHER_SERVICE_COMPONENT, apduServiceInfos.get(0)
                .getComponent());
        assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, apduServiceInfos.get(1)
                .getComponent());
        // Verify that dynamic settings file is updated
        InputStream dynamicSettingsMockIs = mockFileOutputStream.toInputStream();
        RegisteredServicesCache.SettingsFile dynamicSettingsFile
                = Mockito.mock(RegisteredServicesCache.SettingsFile.class);
        when(dynamicSettingsFile.openRead()).thenReturn(dynamicSettingsMockIs);
        when(dynamicSettingsFile.exists()).thenReturn(true);
        Map<Integer, List<Pair<ComponentName, RegisteredServicesCache.DynamicSettings>>>
                readDynamicSettingsFromFile = RegisteredServicesCache
                .readDynamicSettingsFromFile(dynamicSettingsFile);
        assertFalse(readDynamicSettingsFromFile.isEmpty());
        assertTrue(readDynamicSettingsFromFile.containsKey(USER_ID));
        RegisteredServicesCache.DynamicSettings dynamicSettings
                = readDynamicSettingsFromFile.get(USER_ID).get(0).second;
        assertEquals(ANOTHER_SERVICE_COMPONENT,
                readDynamicSettingsFromFile.get(USER_ID).get(0).first);
        assertFalse(dynamicSettings.aidGroups.containsKey(CardEmulation.CATEGORY_PAYMENT));
    }
    @Test
    public void testHandlePackageRemoved() {
        mRegisteredServicesCache = new RegisteredServicesCache(mContext, mCallback,
                mDynamicSettingsFile, mOtherSettingsFile, mServiceParser, mRoutingOptionManager);
        verify(mContext).registerReceiverForAllUsers(
                mReceiverArgumentCaptor.capture(),
                argThat(intent -> intent.hasAction(Intent.ACTION_PACKAGE_ADDED)),
                eq(null), eq(null));
        mRegisteredServicesCache.initialize();
        assertNotNull(mRegisteredServicesCache.getService(USER_ID, WALLET_HOLDER_SERVICE_COMPONENT));

        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.putExtra(Intent.EXTRA_UID, USER_ID);
        // return empty list
        Mockito.reset(mPackageManager);
        when(mPackageManager.queryIntentServicesAsUser(any(), any(), any())).thenReturn(List.of());
        mReceiverArgumentCaptor.getValue().onReceive(mContext, intent);
        assertNull(mRegisteredServicesCache.getService(USER_ID, WALLET_HOLDER_SERVICE_COMPONENT));
    }

    private class MockFileOutputStream extends FileOutputStream {

        ByteArrayOutputStream mByteOutputStream;

        public MockFileOutputStream() throws FileNotFoundException {
            // Does not actually matter what the path is since we won't be writing to it.
            super(mMockFile);
            mByteOutputStream = new ByteArrayOutputStream();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            mByteOutputStream.write(b, off, len);
        }

        @Override
        public void write(byte[] b) throws IOException {
            mByteOutputStream.write(b);
        }

        @Override
        public void write(int b) throws IOException {
            mByteOutputStream.write(b);
        }

        @Override
        public void close() throws IOException {
            mByteOutputStream.close();
        }

        @Override
        public void flush() throws IOException {
            mByteOutputStream.flush();
        }

        InputStream toInputStream() {
            return new ByteArrayInputStream(mByteOutputStream.toByteArray());
        }
    }

    private ResolveInfo createServiceResolveInfo(boolean hasPermission,
                                                 String packageName, String className,
                                                 List<String> categories) {
        when(mPackageManager.checkPermission(any(), eq(packageName)))
                .thenReturn(hasPermission ? PackageManager.PERMISSION_GRANTED
                        : PackageManager.PERMISSION_DENIED);
        ApduServiceInfo apduServiceInfo = Mockito.mock(ApduServiceInfo.class);
        when(apduServiceInfo.getComponent()).thenReturn(new ComponentName(packageName, className));
        when(apduServiceInfo.getUid()).thenReturn(SERVICE_UID);
        if (!categories.isEmpty()) {
            for(String category : categories) {
               when(apduServiceInfo.hasCategory(category)).thenReturn(true);
               when(apduServiceInfo.getDynamicAidGroupForCategory(eq(category)))
                       .thenReturn(createAidGroup(category));
            }
        }
        mMappedServices.put(className, apduServiceInfo);

        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.permission = android.Manifest.permission.BIND_NFC_SERVICE;
        resolveInfo.serviceInfo.exported = true;
        resolveInfo.serviceInfo.packageName = packageName;
        resolveInfo.serviceInfo.name = className;
        return resolveInfo;
    }

    private static AidGroup createAidGroup(String category) {
        return new AidGroup(category.equals(
                CardEmulation.CATEGORY_PAYMENT) ? PAYMENT_AIDS : NON_PAYMENT_AID, category);
    }
}
