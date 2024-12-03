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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import android.net.Uri;
import android.nfc.cardemulation.NfcFCardEmulation;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AtomicFile;
import android.util.Xml;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.cardemulation.RegisteredNfcFServicesCache.DynamicNfcid2;
import com.android.nfc.cardemulation.RegisteredNfcFServicesCache.DynamicSystemCode;
import com.android.nfc.cardemulation.RegisteredNfcFServicesCache.UserServices;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

@RunWith(AndroidJUnit4.class)
public class RegisteredNfcFServicesCacheTest {

  private static final File DIR = new File("/");
  private static final int USER_ID = 1;
  private static final int ANOTHER_USER_ID = 2;
  private static final UserHandle USER_HANDLE = UserHandle.of(USER_ID);
  private static final UserHandle USER_HANDLE_QUIET_MODE = UserHandle.of(ANOTHER_USER_ID);
  private static final String WALLET_HOLDER_PACKAGE_NAME = "com.android.test.walletroleholder";
  private static final String NON_PAYMENT_NFC_PACKAGE_NAME = "com.android.test.nonpaymentnfc";
  private static final String ON_HOST_SERVICE_NAME
      = "com.android.test.walletroleholder.OnHostApduService";
  private static final String NON_PAYMENT_SERVICE_NAME
      = "com.android.test.nonpaymentnfc.NonPaymentApduService";
  private static final int SERVICE_UID = 4;
  private static final int SYSTEM_CODE_UID = 5;
  private static final int NFCID2_UID = 6;
  private static final String SYSTEM_CODE = "dynamic system code";
  private static final String NFCID2 = "RANDOM";
  private static final ComponentName WALLET_COMPONENT
      = new ComponentName(WALLET_HOLDER_PACKAGE_NAME,
      "com.android.test.walletroleholder.WalletRoleHolderApduService");

  private RegisteredNfcFServicesCache cache;
  private MockitoSession mStaticMockSession;
  @Mock
  private Context mContext;
  @Mock
  private RegisteredNfcFServicesCache.Callback mCallback;
  @Mock
  private UserManager mUserManager;
  @Mock
  private NfcFServiceInfo mNfcFServiceInfo;
  @Mock
  private PackageManager mPackageManager;
  @Mock
  private AtomicFile mAtomicFile;
  @Mock
  private FileOutputStream mFos;
  @Mock
  private FileInputStream mFis;
  @Mock
  private XmlSerializer mSerializer;
  @Mock
  private XmlPullParser mParser;
  @Mock
  private PrintWriter mPw;
  @Mock
  private ParcelFileDescriptor mPfd;
  @Mock
  private File mFile;

  @Captor
  private ArgumentCaptor<BroadcastReceiver> receiverCaptor;
  @Captor
  private ArgumentCaptor<IntentFilter> intentFilterCaptor;
  @Captor
  private ArgumentCaptor<String> broadcastPermissionCaptor;
  @Captor
  private ArgumentCaptor<Handler> schedulerCaptor;
  @Captor
  private ArgumentCaptor<List<NfcFServiceInfo>> servicesCaptor;
  @Captor
  private ArgumentCaptor<Integer> userIdCaptor;
  @Captor
  private ArgumentCaptor<String> systemCodeCaptor;
  @Captor
  private ArgumentCaptor<String> nfcid2Captor;

  @Before
  public void setUp() throws Exception {
    mStaticMockSession = ExtendedMockito.mockitoSession()
        .mockStatic(ActivityManager.class)
        .mockStatic(UserHandle.class)
        .mockStatic(NfcFCardEmulation.class)
        .mockStatic(Xml.class)
        .mockStatic(ParcelFileDescriptor.class)
        .strictness(Strictness.LENIENT)
        .startMocking();
    MockitoAnnotations.initMocks(this);
    when(mContext.getSystemService(eq(UserManager.class))).thenReturn(mUserManager);
    when(mContext.createContextAsUser(any(), anyInt())).thenReturn(mContext);
    when(mContext.getFilesDir()).thenReturn(DIR);
    when(mContext.createPackageContextAsUser(any(), anyInt(), any())).thenReturn(mContext);
    when(mContext.getPackageManager()).thenReturn(mPackageManager);
    ArrayList<UserHandle> userHandles = new ArrayList<UserHandle>();
    userHandles.add(USER_HANDLE);
    userHandles.add(USER_HANDLE_QUIET_MODE);
    when(mUserManager.getEnabledProfiles()).thenReturn(userHandles);
    when(mUserManager.isQuietModeEnabled(eq(USER_HANDLE))).thenReturn(false);
    when(mUserManager.isQuietModeEnabled(eq(USER_HANDLE_QUIET_MODE))).thenReturn(true);
    when(ParcelFileDescriptor.dup(any())).thenReturn(mPfd);
    when(UserHandle.getUserHandleForUid(anyInt())).thenReturn(USER_HANDLE);
    when(Xml.newSerializer()).thenReturn(mSerializer);
    when(Xml.newPullParser()).thenReturn(mParser);
    when(mAtomicFile.startWrite()).thenReturn(mFos);
    when(mAtomicFile.openRead()).thenReturn(mFis);
    when(mAtomicFile.getBaseFile()).thenReturn(mFile);
    when(mFile.exists()).thenReturn(true);
  }

  @After
  public void tearDown() {
    mStaticMockSession.finishMocking();
  }

  @Test
  public void testConstructor() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);

    verify(mContext, times(2))
        .registerReceiverForAllUsers(receiverCaptor.capture(), intentFilterCaptor.capture(),
            broadcastPermissionCaptor.capture(), schedulerCaptor.capture());
    assertEquals(cache.mReceiver.get(), receiverCaptor.getAllValues().get(0));
    assertEquals(cache.mReceiver.get(), receiverCaptor.getAllValues().get(1));
    assertNotNull(cache.mReceiver.get());
    IntentFilter intentFilter = intentFilterCaptor.getAllValues().get(0);
    IntentFilter sdFilter = intentFilterCaptor.getAllValues().get(1);
    assertTrue(intentFilter.hasAction(Intent.ACTION_PACKAGE_ADDED));
    assertTrue(intentFilter.hasAction(Intent.ACTION_PACKAGE_CHANGED));
    assertTrue(intentFilter.hasAction(Intent.ACTION_PACKAGE_REMOVED));
    assertTrue(intentFilter.hasAction(Intent.ACTION_PACKAGE_REPLACED));
    assertTrue(intentFilter.hasAction(Intent.ACTION_PACKAGE_FIRST_LAUNCH));
    assertTrue(intentFilter.hasAction(Intent.ACTION_PACKAGE_RESTARTED));
    assertTrue(intentFilter.hasDataScheme("package"));
    assertTrue(sdFilter.hasAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE));
    assertTrue(sdFilter.hasAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE));
    assertNull(broadcastPermissionCaptor.getAllValues().get(0));
    assertNull(broadcastPermissionCaptor.getAllValues().get(1));
    assertNull(schedulerCaptor.getAllValues().get(0));
    assertNull(schedulerCaptor.getAllValues().get(1));
    synchronized (cache.mLock) {
      assertEquals(USER_HANDLE, cache.mUserHandles.get(0));
    }
    assertEquals(mContext, cache.mContext);
    assertEquals(mCallback, cache.mCallback);
    assertEquals(DIR, cache.mDynamicSystemCodeNfcid2File.getBaseFile().getParentFile());
    assertEquals(DIR + "dynamic_systemcode_nfcid2.xml",
        cache.mDynamicSystemCodeNfcid2File.getBaseFile().getAbsolutePath());
  }

  @Test
  public void testBroadcastReceiverOnReceive_DoesNothing() {
    when(ActivityManager.getCurrentUser()).thenReturn(ANOTHER_USER_ID);
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setResolveInfoList();
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");

    cache.mReceiver.get().onReceive(mContext, getBroadcastReceiverIntent());

    verify(mCallback, never()).onNfcFServicesUpdated(anyInt(), any());
  }

  @Test
  public void testBroadcastReceiverOnReceive_CommitsCache() {
    when(ActivityManager.getCurrentUser()).thenReturn(USER_ID);
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setResolveInfoList();
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");

    cache.mReceiver.get().onReceive(mContext, getBroadcastReceiverIntent());

    verify(mCallback).onNfcFServicesUpdated(userIdCaptor.capture(), servicesCaptor.capture());
    assertEquals(Integer.valueOf(USER_ID), userIdCaptor.getValue());
    assertEquals(mNfcFServiceInfo, servicesCaptor.getValue().get(0));
  }

  @Test
  public void testHasService_ReturnsFalse() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);

    boolean result = cache.hasService(USER_ID, WALLET_COMPONENT);

    assertFalse(result);
  }

  @Test
  public void testHasService_ReturnsTrue() throws Exception {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");

    boolean result = cache.hasService(USER_ID, WALLET_COMPONENT);

    assertTrue(result);
  }

  @Test
  public void testGetService_ReturnsNull() throws Exception {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);

    NfcFServiceInfo result = cache.getService(USER_ID, WALLET_COMPONENT);

    assertNull(result);
  }

  @Test
  public void testGetService_ReturnsService() throws Exception {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");

    NfcFServiceInfo result = cache.getService(USER_ID, WALLET_COMPONENT);

    assertEquals(mNfcFServiceInfo, result);
  }

  @Test
  public void testGetServices() throws Exception {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");

    List<NfcFServiceInfo> result = cache.getServices(USER_ID);

    assertEquals(mNfcFServiceInfo, result.get(0));
  }


  @Test
  public void testInvalidateCacheWithNoValidServices_ReturnsEarly() throws Exception {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);

    cache.invalidateCache(USER_ID);

    verify(mCallback, never()).onNfcFServicesUpdated(anyInt(), any());
  }

  /**
   * Tests the case wherein:
   * -) All three objects in mUserServices (one each in services, dynamicSystemCode, and
   * dynamicNfcid2) have unique UIDs.
   * -) A random NFCID2 is not requested
   *
   * Ultimately, an update (consisting of mNfcFService) is committed through the callback class,
   * and the contents of dynamicSystemCode and dynamicNfcid2 are erased.
   */
  @Test
  public void testInvalidateCacheWithValidServicesCase1_CommitsCache() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");
    setResolveInfoList();

    cache.invalidateCache(USER_ID);

    verify(mCallback).onNfcFServicesUpdated(userIdCaptor.capture(), servicesCaptor.capture());
    assertEquals(Integer.valueOf(USER_ID), userIdCaptor.getValue());
    assertEquals(mNfcFServiceInfo, servicesCaptor.getValue().get(0));
    UserServices userServicesResult = cache.mUserServices.get(USER_ID);
    assertEquals(mNfcFServiceInfo, userServicesResult.services.get(WALLET_COMPONENT));
    assertTrue(userServicesResult.dynamicSystemCode.isEmpty());
    assertTrue(userServicesResult.dynamicNfcid2.isEmpty());
    verify(mNfcFServiceInfo, never()).setDynamicSystemCode(anyString());
    verify(mNfcFServiceInfo, never()).setDynamicNfcid2(anyString());
  }

  /**
   * Tests the case wherein:
   * -) The mNfcServiceInfo object in mUserServices.services shares the same UID as the object
   * in dynamicSystemCode.
   * -) A random NFCID2 is not requested
   *
   * Ultimately, an update (consisting of mNfcFService) is committed through the callback class,
   * and the contents of dynamicNfcid2 are erased.
   */
  @Test
  public void testInvalidateCacheWithValidServicesCase2_CommitsCache() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SYSTEM_CODE_UID, /* serviceInfoNfcid2 = */ "");
    setResolveInfoList();

    cache.invalidateCache(USER_ID);

    verify(mCallback).onNfcFServicesUpdated(userIdCaptor.capture(), servicesCaptor.capture());
    assertEquals(Integer.valueOf(USER_ID), userIdCaptor.getValue());
    assertEquals(mNfcFServiceInfo, servicesCaptor.getValue().get(0));
    UserServices userServicesResult = cache.mUserServices.get(USER_ID);
    assertEquals(mNfcFServiceInfo, userServicesResult.services.get(WALLET_COMPONENT));
    assertEquals(1, userServicesResult.dynamicSystemCode.size());
    verify(mNfcFServiceInfo).setDynamicSystemCode(systemCodeCaptor.capture());
    assertEquals(SYSTEM_CODE, systemCodeCaptor.getValue());
    assertTrue(userServicesResult.dynamicNfcid2.isEmpty());
    verify(mNfcFServiceInfo, never()).setDynamicNfcid2(anyString());
  }

  /**
   * Tests the case wherein:
   * -) The mNfcServiceInfo object in mUserServices.services shares the same UID as the object
   * in dynamicNfcid2.
   * -) A random NFCID2 is not requested
   *
   * Ultimately, an update (consisting of mNfcFService) is committed through the callback class,
   * and the contents of dynamicSystemCode are erased.
   */
  @Test
  public void testInvalidateCacheWithValidServicesCase3_CommitsCache() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ NFCID2_UID, /* serviceInfoNfcid2 = */ "");
    setResolveInfoList();

    cache.invalidateCache(USER_ID);

    verify(mCallback).onNfcFServicesUpdated(userIdCaptor.capture(), servicesCaptor.capture());
    assertEquals(Integer.valueOf(USER_ID), userIdCaptor.getValue());
    assertEquals(mNfcFServiceInfo, servicesCaptor.getValue().get(0));
    UserServices userServicesResult = cache.mUserServices.get(USER_ID);
    assertEquals(mNfcFServiceInfo, userServicesResult.services.get(WALLET_COMPONENT));
    assertTrue(userServicesResult.dynamicSystemCode.isEmpty());
    verify(mNfcFServiceInfo, never()).setDynamicSystemCode(anyString());
    assertEquals(1, userServicesResult.dynamicNfcid2.size());
    verify(mNfcFServiceInfo).setDynamicNfcid2(nfcid2Captor.capture());
    assertEquals(NFCID2, nfcid2Captor.getValue());
  }

  /**
   * Tests the case wherein:
   * -) All three objects in mUserServices (one each in services, dynamicSystemCode, and
   * dynamicNfcid2) have unique UIDs.
   * -) A random NFCID2 is requested
   *
   * Ultimately, an update (consisting of mNfcFService) is committed through the callback class,
   * and the contents of dynamicSystemCode are erased.
   */
  @Test
  public void testInvalidateCacheWithValidServicesCase4_CommitsCache() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "RANDOM");
    setResolveInfoList();

    cache.invalidateCache(USER_ID);

    verify(mCallback).onNfcFServicesUpdated(userIdCaptor.capture(), servicesCaptor.capture());
    assertEquals(Integer.valueOf(USER_ID), userIdCaptor.getValue());
    assertEquals(mNfcFServiceInfo, servicesCaptor.getValue().get(0));
    UserServices userServicesResult = cache.mUserServices.get(USER_ID);
    assertEquals(mNfcFServiceInfo, userServicesResult.services.get(WALLET_COMPONENT));
    assertTrue(userServicesResult.dynamicSystemCode.isEmpty());
    verify(mNfcFServiceInfo, never()).setDynamicSystemCode(anyString());
    assertEquals(1, userServicesResult.dynamicNfcid2.size());
    verify(mNfcFServiceInfo).setDynamicNfcid2(anyString());
  }

  @Test
  public void testRegisterSystemCodeForServiceWhenActivated_ReturnsFalse() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");
    cache.mActivated = true;

    boolean result
        = cache.registerSystemCodeForService(USER_ID, SERVICE_UID, WALLET_COMPONENT, SYSTEM_CODE);

    assertFalse(result);
  }

  @Test
  public void testRegisterSystemCodeForServiceWithNullService_ReturnsFalse() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    cache.mActivated = false;

    boolean result
        = cache.registerSystemCodeForService(USER_ID, SERVICE_UID, WALLET_COMPONENT, SYSTEM_CODE);

    assertFalse(result);
  }

  @Test
  public void testRegisterSystemCodeForServiceWithUidMismatch_ReturnsFalse() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ NFCID2_UID, /* serviceInfoNfcid2 = */ "");
    cache.mActivated = false;

    boolean result
        = cache.registerSystemCodeForService(USER_ID, SERVICE_UID, WALLET_COMPONENT, SYSTEM_CODE);

    assertFalse(result);
  }

  @Test
  public void testRegisterSystemCodeForServiceWithInvalidSystemCode_ReturnsFalse() {
    when(NfcFCardEmulation.isValidSystemCode(eq(SYSTEM_CODE))).thenReturn(false);
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");
    cache.mActivated = false;

    boolean result
        = cache.registerSystemCodeForService(USER_ID, SERVICE_UID, WALLET_COMPONENT, SYSTEM_CODE);

    assertFalse(result);
  }

  @Test
  public void testRegisterSystemCodeForServiceWhenFailedtoPersistSystemCode_ReturnsFalse() {
    when(NfcFCardEmulation.isValidSystemCode(eq(SYSTEM_CODE))).thenReturn(true);
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");
    cache.mActivated = false;

    boolean result
        = cache.registerSystemCodeForService(USER_ID, SERVICE_UID, WALLET_COMPONENT, SYSTEM_CODE);

    assertFalse(result);
    verify(mCallback, never()).onNfcFServicesUpdated(anyInt(), any());
  }

  @Test
  public void testRegisterSystemCodeForService_ReturnsTrue() throws Exception {
    when(NfcFCardEmulation.isValidSystemCode(eq(SYSTEM_CODE))).thenReturn(true);
    cache = new RegisteredNfcFServicesCache(mContext, mCallback, mAtomicFile);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");
    cache.mActivated = false;

    boolean result
        = cache.registerSystemCodeForService(USER_ID, SERVICE_UID, WALLET_COMPONENT, SYSTEM_CODE);

    assertTrue(result);
    verify(mNfcFServiceInfo).setDynamicSystemCode(systemCodeCaptor.capture());
    assertEquals(SYSTEM_CODE.toUpperCase(Locale.ROOT), systemCodeCaptor.getValue());
    UserServices userServicesResult = cache.mUserServices.get(USER_ID);
    assertEquals(1, userServicesResult.dynamicSystemCode.size());
    DynamicSystemCode resultSystemCode = userServicesResult.dynamicSystemCode.get(WALLET_COMPONENT);
    assertEquals(SERVICE_UID, resultSystemCode.uid);
    assertEquals(SYSTEM_CODE.toUpperCase(Locale.ROOT), resultSystemCode.systemCode);
    verify(mCallback).onNfcFServicesUpdated(userIdCaptor.capture(), servicesCaptor.capture());
    assertEquals(Integer.valueOf(USER_ID), userIdCaptor.getValue());
    assertEquals(mNfcFServiceInfo, servicesCaptor.getValue().get(0));
  }

  @Test
  public void testGetSystemCodeForServiceWithNullService_ReturnsNull() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);

    String result = cache.getSystemCodeForService(USER_ID, SERVICE_UID, WALLET_COMPONENT);

    assertNull(result);
  }

  @Test
  public void testGetSystemCodeForServiceWithMismatchedUid_ReturnsNull() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SYSTEM_CODE_UID, /* serviceInfoNfcid2 = */ "");

    String result = cache.getSystemCodeForService(USER_ID, SERVICE_UID, WALLET_COMPONENT);

    assertNull(result);
  }

  @Test
  public void testGetSystemCodeForService_ReturnsSystemCode() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");

    String result = cache.getSystemCodeForService(USER_ID, SERVICE_UID, WALLET_COMPONENT);

    assertEquals(SYSTEM_CODE, result);
  }

  @Test
  public void testRemoveSystemCodeForService_ReturnsTrue() throws Exception {
    when(NfcFCardEmulation.isValidSystemCode(any())).thenReturn(true);
    cache = new RegisteredNfcFServicesCache(mContext, mCallback, mAtomicFile);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");
    cache.mActivated = false;

    boolean result = cache.removeSystemCodeForService(USER_ID, SERVICE_UID, WALLET_COMPONENT);

    assertTrue(result);
    verify(mNfcFServiceInfo).setDynamicSystemCode(systemCodeCaptor.capture());
    assertEquals("NULL", systemCodeCaptor.getValue());
    DynamicSystemCode resultSystemCode
        = cache.mUserServices.get(USER_ID).dynamicSystemCode.get(WALLET_COMPONENT);
    assertEquals(SERVICE_UID, resultSystemCode.uid);
    assertEquals("NULL", resultSystemCode.systemCode);
    verify(mCallback).onNfcFServicesUpdated(userIdCaptor.capture(), servicesCaptor.capture());
    assertEquals(Integer.valueOf(USER_ID), userIdCaptor.getValue());
    assertEquals(mNfcFServiceInfo, servicesCaptor.getValue().get(0));
  }


  @Test
  public void testSetNfcid2ForServiceWhenActivated_ReturnsFalse() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");
    cache.mActivated = true;

    boolean result = cache.setNfcid2ForService(USER_ID, SERVICE_UID, WALLET_COMPONENT, NFCID2);

    assertFalse(result);
  }

  @Test
  public void testSetNfcid2ForServiceWithNullService_ReturnsFalse() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    cache.mActivated = false;

    boolean result = cache.setNfcid2ForService(USER_ID, SERVICE_UID, WALLET_COMPONENT, NFCID2);

    assertFalse(result);
  }

  @Test
  public void testSetNfcid2ForServiceWithUidMismatch_ReturnsFalse() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ NFCID2_UID, /* serviceInfoNfcid2 = */ "");
    cache.mActivated = false;

    boolean result = cache.setNfcid2ForService(USER_ID, SERVICE_UID, WALLET_COMPONENT, NFCID2);

    assertFalse(result);
  }

  @Test
  public void testSetNfcid2ForServiceWithInvalidSystemCode_ReturnsFalse() {
    when(NfcFCardEmulation.isValidNfcid2(eq(NFCID2))).thenReturn(false);
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");
    cache.mActivated = false;

    boolean result = cache.setNfcid2ForService(USER_ID, SERVICE_UID, WALLET_COMPONENT, NFCID2);

    assertFalse(result);
  }

  @Test
  public void testSetNfcid2ForServiceWhenFailedtoPersistSystemCode_ReturnsFalse() {
    when(NfcFCardEmulation.isValidNfcid2(eq(NFCID2))).thenReturn(true);
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");
    cache.mActivated = false;

    boolean result = cache.setNfcid2ForService(USER_ID, SERVICE_UID, WALLET_COMPONENT, NFCID2);

    assertFalse(result);
    verify(mCallback, never()).onNfcFServicesUpdated(anyInt(), any());
  }

  @Test
  public void testSetNfcid2ForService_ReturnsTrue() throws Exception {
    when(NfcFCardEmulation.isValidNfcid2(eq(NFCID2))).thenReturn(true);
    cache = new RegisteredNfcFServicesCache(mContext, mCallback, mAtomicFile);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");
    cache.mActivated = false;

    boolean result = cache.setNfcid2ForService(USER_ID, SERVICE_UID, WALLET_COMPONENT, NFCID2);

    assertTrue(result);
    verify(mNfcFServiceInfo).setDynamicNfcid2(nfcid2Captor.capture());
    assertEquals(NFCID2.toUpperCase(Locale.ROOT), nfcid2Captor.getValue());
    DynamicNfcid2 resultNfcid2
        = cache.mUserServices.get(USER_ID).dynamicNfcid2.get(WALLET_COMPONENT);
    assertEquals(SERVICE_UID, resultNfcid2.uid);
    assertEquals(NFCID2.toUpperCase(Locale.ROOT), resultNfcid2.nfcid2);
    verify(mCallback).onNfcFServicesUpdated(userIdCaptor.capture(), servicesCaptor.capture());
    assertEquals(Integer.valueOf(USER_ID), userIdCaptor.getValue());
    assertEquals(mNfcFServiceInfo, servicesCaptor.getValue().get(0));
  }

  @Test
  public void testgetNfcid2ForServiceWithNullService_ReturnsNull() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);

    String result = cache.getNfcid2ForService(USER_ID, SERVICE_UID, WALLET_COMPONENT);

    assertNull(result);
  }

  @Test
  public void testGetNfcid2ForServiceWithMismatchedUid_ReturnsNull() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SYSTEM_CODE_UID, /* serviceInfoNfcid2 = */ "");

    String result = cache.getNfcid2ForService(USER_ID, SERVICE_UID, WALLET_COMPONENT);

    assertNull(result);
  }

  @Test
  public void testGetNfcid2ForService_ReturnsSystemCode() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ NFCID2);

    String result = cache.getNfcid2ForService(USER_ID, SERVICE_UID, WALLET_COMPONENT);

    assertEquals(NFCID2, result);
  }

  @Test
  public void testOnHostEmulationActivated() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);

    cache.onHostEmulationActivated();

    assertTrue(cache.mActivated);
  }

  @Test
  public void testOnHostEmulationDeactivated() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);

    cache.onHostEmulationDeactivated();

    assertFalse(cache.mActivated);
  }

  @Test
  public void testOnNfcDisabled() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);

    cache.onNfcDisabled();

    assertFalse(cache.mActivated);
  }

  @Test
  public void testOnUserSwitched() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);

    cache.onUserSwitched();

    assertTrue(cache.mUserSwitched);
    synchronized (cache.mLock) {
      assertEquals(USER_HANDLE, cache.mUserHandles.get(0));
    }
  }

  @Test
  public void testOnManagedProfileChanged() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);

    cache.onManagedProfileChanged();

    synchronized (cache.mLock) {
      assertEquals(USER_HANDLE, cache.mUserHandles.get(0));
    }
  }

  @Test
  public void testDump() {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");

    cache.dump(/* fd = */ null, mPw, /* args = */ null);

    verify(mPw, times(4)).println(anyString());
    verify(mNfcFServiceInfo).dump(any(), any(), any());
  }

  @Test
  public void testWriteDynamicSystemCodeNfcid2LockedWithInvalidSerializer_ReturnsFalse()
      throws Exception {
    when(mAtomicFile.startWrite()).thenThrow(IOException.class);
    cache = new RegisteredNfcFServicesCache(mContext, mCallback, mAtomicFile);

    boolean result = cache.writeDynamicSystemCodeNfcid2Locked();

    assertFalse(result);
    verify(mAtomicFile, never()).failWrite(any(FileOutputStream.class));
  }

  @Test
  public void testWriteDynamicSystemCodeNfcid2LockedWithNoServices_ReturnsTrue() throws Exception {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback, mAtomicFile);

    boolean result = cache.writeDynamicSystemCodeNfcid2Locked();

    assertTrue(result);
    verify(mAtomicFile).startWrite();
    verify(mAtomicFile).finishWrite(any(FileOutputStream.class));
    verify(mAtomicFile, never()).failWrite(any(FileOutputStream.class));
  }

  @Test
  public void testWriteDynamicSystemCodeNfcid2LockedWithServices_ReturnsTrue() throws Exception {
    cache = new RegisteredNfcFServicesCache(mContext, mCallback, mAtomicFile);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");

    boolean result = cache.writeDynamicSystemCodeNfcid2Locked();

    assertTrue(result);
    verify(mAtomicFile).startWrite();
    verify(mAtomicFile).finishWrite(any(FileOutputStream.class));
    verify(mAtomicFile, never()).failWrite(any(FileOutputStream.class));
    verify(mSerializer, times(4)).attribute(any(), anyString(), anyString());
  }

  @Test
  public void testReadDynamicSystemCodeNfcid2Locked() throws Exception {
    when(mParser.getEventType()).thenReturn(XmlPullParser.START_TAG);
    when(mParser.next()).thenReturn(XmlPullParser.END_TAG).thenReturn(XmlPullParser.END_DOCUMENT);
    when(mParser.getName()).thenReturn("services").thenReturn("service");
    when(mParser.getDepth()).thenReturn(2);
    when(mParser.getAttributeValue(any(), eq("component")))
        .thenReturn(WALLET_COMPONENT.flattenToString());
    when(mParser.getAttributeValue(any(), eq("uid"))).thenReturn("1");
    when(mParser.getAttributeValue(any(), eq("system-code"))).thenReturn(SYSTEM_CODE);
    when(mParser.getAttributeValue(any(), eq("nfcid2"))).thenReturn(NFCID2);
    cache = new RegisteredNfcFServicesCache(mContext, mCallback, mAtomicFile);
    setUserServices(/* serviceInfoUid = */ SERVICE_UID, /* serviceInfoNfcid2 = */ "");

    cache.readDynamicSystemCodeNfcid2Locked();

    verify(mAtomicFile, never()).delete();
    verify(mParser, times(2)).next();
    verify(mParser, times(5)).getAttributeValue(any(), anyString());
    assertEquals(1, cache.mUserServices.get(USER_ID).dynamicSystemCode.size());
    assertEquals(1, cache.mUserServices.get(USER_ID).dynamicNfcid2.size());
  }

  private void setResolveInfoList() {
    ArrayList<ResolveInfo> list = new ArrayList<ResolveInfo>();
    list.add(getResolveInfo(true, WALLET_HOLDER_PACKAGE_NAME, ON_HOST_SERVICE_NAME));
    list.add(
        getResolveInfo(false, NON_PAYMENT_NFC_PACKAGE_NAME, NON_PAYMENT_SERVICE_NAME));
    when(mPackageManager.queryIntentServicesAsUser(any(), any(), any())).thenReturn(list);
  }

  private ResolveInfo getResolveInfo(boolean hasPermission, String packageName, String className) {
    when(mPackageManager.checkPermission(any(), eq(packageName)))
        .thenReturn(hasPermission ? PackageManager.PERMISSION_GRANTED
            : PackageManager.PERMISSION_DENIED);

    ResolveInfo resolveInfo = new ResolveInfo();
    resolveInfo.serviceInfo = new ServiceInfo();
    resolveInfo.serviceInfo.permission = android.Manifest.permission.BIND_NFC_SERVICE;
    resolveInfo.serviceInfo.exported = true;
    resolveInfo.serviceInfo.packageName = packageName;
    resolveInfo.serviceInfo.name = className;
    return resolveInfo;
  }

  private void setUserServices(int serviceInfoUid, String serviceInfoNfcid2) {
    when(mNfcFServiceInfo.getSystemCode()).thenReturn(SYSTEM_CODE);
    when(mNfcFServiceInfo.getUid()).thenReturn(serviceInfoUid);
    when(mNfcFServiceInfo.getNfcid2()).thenReturn(serviceInfoNfcid2);

    UserServices userServices = new UserServices();
    userServices.services.put(WALLET_COMPONENT, mNfcFServiceInfo);
    userServices.dynamicSystemCode.put(
        WALLET_COMPONENT, new DynamicSystemCode(SYSTEM_CODE_UID, SYSTEM_CODE));
    userServices.dynamicNfcid2.put(WALLET_COMPONENT, new DynamicNfcid2(NFCID2_UID, NFCID2));
    cache.mUserServices.put(USER_ID, userServices);
  }

  private Intent getBroadcastReceiverIntent() {
    Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
        RegisteredNfcFServicesCache.class);
    intent.putExtra(Intent.EXTRA_UID, SERVICE_UID);
    intent.putExtra(Intent.EXTRA_REPLACING, false);
    intent.setData(Uri.EMPTY);
    return intent;
  }
}