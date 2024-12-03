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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.os.ParcelFileDescriptor;

import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.cardemulation.RegisteredT3tIdentifiersCache.T3tIdentifier;

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

@RunWith(AndroidJUnit4.class)
public class RegisteredT3tIdentifiersCacheTest {

  private static final String NFCID2 = "nfcid2";
  private static final String SYSTEM_CODE = "system code";
  private static final String T3TPMM = "t3tpmm";
  private static final String ANOTHER_SYSTEM_CODE = "another system code";
  private static final int USER_ID = 1;
  private static final String WALLET_HOLDER_PACKAGE_NAME = "com.android.test.walletroleholder";
  private static final String ON_HOST_SERVICE_NAME
      = "com.android.test.walletroleholder.OnHostApduService";
  private static final String NON_PAYMENT_NFC_PACKAGE_NAME = "com.android.test.nonpaymentnfc";
  private static final String NON_PAYMENT_SERVICE_NAME
      = "com.android.test.nonpaymentnfc.NonPaymentApduService";
  private static final ComponentName WALLET_HOLDER_SERVICE_COMPONENT =
      new ComponentName(WALLET_HOLDER_PACKAGE_NAME, ON_HOST_SERVICE_NAME);
  private static final ComponentName NON_PAYMENT_SERVICE_COMPONENT =
      new ComponentName(NON_PAYMENT_NFC_PACKAGE_NAME, NON_PAYMENT_SERVICE_NAME);
  @Mock
  private Context mContext;
  @Mock
  private NfcFServiceInfo mNfcFServiceInfo;
  @Mock
  private PrintWriter mPw;
  @Mock
  private ParcelFileDescriptor mPfd;
  @Mock
  private SystemCodeRoutingManager mRoutingManager;
  @Captor
  ArgumentCaptor<List<T3tIdentifier>> identifiersCaptor;

  private RegisteredT3tIdentifiersCache cache;
  private MockitoSession mStaticMockSession;

  @Before
  public void setUp() throws Exception {
    mStaticMockSession = ExtendedMockito.mockitoSession()
        .mockStatic(ParcelFileDescriptor.class)
        .strictness(Strictness.LENIENT)
        .startMocking();
    MockitoAnnotations.initMocks(this);
    when(mNfcFServiceInfo.toString()).thenReturn("");
    when(mNfcFServiceInfo.getSystemCode()).thenReturn(SYSTEM_CODE);
    when(mNfcFServiceInfo.getNfcid2()).thenReturn(NFCID2);
    when(mNfcFServiceInfo.getT3tPmm()).thenReturn(T3TPMM);
    when(mNfcFServiceInfo.getComponent()).thenReturn(NON_PAYMENT_SERVICE_COMPONENT);
    when(ParcelFileDescriptor.dup(any())).thenReturn(mPfd);
  }

  @After
  public void tearDown() {
    mStaticMockSession.finishMocking();
  }

  @Test
  public void testConstructor() {
    cache = new RegisteredT3tIdentifiersCache(mContext);

    assertEquals(mContext, cache.mContext);
    assertNotNull(cache.mRoutingManager);
  }

  @Test
  public void testT3tIdentifierEquals_ReturnsTrue() {
    cache = new RegisteredT3tIdentifiersCache(mContext);
    T3tIdentifier firstIdentifier = cache.new T3tIdentifier(SYSTEM_CODE, NFCID2, T3TPMM);
    T3tIdentifier secondIdentifier
        = cache.new T3tIdentifier(SYSTEM_CODE.toUpperCase(Locale.ROOT), NFCID2, "");

    boolean result = firstIdentifier.equals(secondIdentifier);

    assertTrue(result);
  }

  @Test
  public void testT3tIdentifierEquals_ReturnsFalse() {
    cache = new RegisteredT3tIdentifiersCache(mContext);
    T3tIdentifier firstIdentifier = cache.new T3tIdentifier(SYSTEM_CODE, NFCID2, T3TPMM);
    T3tIdentifier secondIdentifier = cache.new T3tIdentifier(ANOTHER_SYSTEM_CODE, NFCID2, T3TPMM);

    boolean result = firstIdentifier.equals(secondIdentifier);

    assertFalse(result);
  }

  @Test
  public void testResolveNfcid2() {
    cache = new RegisteredT3tIdentifiersCache(mContext);
    cache.mForegroundT3tIdentifiersCache.put(NFCID2, mNfcFServiceInfo);

    NfcFServiceInfo result = cache.resolveNfcid2(NFCID2);

    assertEquals(mNfcFServiceInfo, result);
  }

  @Test
  public void testOnSecureNfcToggledWithNfcDisabled_ReturnsEarly() {
    cache = new RegisteredT3tIdentifiersCache(mContext, mRoutingManager);
    cache.mNfcEnabled = false;

    cache.onSecureNfcToggled();

    verify(mRoutingManager, never()).configureRouting(any());
  }

  @Test
  public void testOnSecureNfcToggledWithNfcEnabled_ConfiguresRouting() {
    cache = new RegisteredT3tIdentifiersCache(mContext, mRoutingManager);
    cache.mNfcEnabled = true;
    cache.mForegroundT3tIdentifiersCache.put(NFCID2, mNfcFServiceInfo);

    cache.onSecureNfcToggled();

    verify(mRoutingManager, times(2)).configureRouting(identifiersCaptor.capture());
    List<T3tIdentifier> firstList = identifiersCaptor.getAllValues().get(0);
    List<T3tIdentifier> secondList = identifiersCaptor.getAllValues().get(1);
    assertEquals(1, firstList.size());
    T3tIdentifier identifier = firstList.get(0);
    assertEquals(SYSTEM_CODE, identifier.systemCode);
    assertEquals(NFCID2, identifier.nfcid2);
    assertEquals(T3TPMM, identifier.t3tPmm);
    assertEquals(1, secondList.size());
    assertEquals(secondList.get(0), identifier);
  }

  @Test
  public void testOnServicesUpdated() {
    cache = new RegisteredT3tIdentifiersCache(mContext);
    ArrayList<NfcFServiceInfo> serviceList = getServiceList();

    cache.onServicesUpdated(USER_ID, serviceList);

    assertEquals(1, cache.mUserNfcFServiceInfo.size());
    assertEquals(serviceList, cache.mUserNfcFServiceInfo.get(USER_ID));
  }

  /**
   * Tests the case where the component passed in is null and mEnabledForegroundService is also
   * null. Ultimately, no update is performed.
   */
  @Test
  public void testOnEnabledForegroundNfcFServiceChangedCase1() {
    cache = new RegisteredT3tIdentifiersCache(mContext, mRoutingManager);
    cache.mEnabledForegroundService = null;
    cache.mEnabledForegroundServiceUserId = USER_ID;
    cache.mNfcEnabled = true;

    cache.onEnabledForegroundNfcFServiceChanged(USER_ID, /* component = */ null);

    assertEquals(USER_ID, cache.mEnabledForegroundServiceUserId);
    assertNull(cache.mEnabledForegroundService);
    verify(mRoutingManager, never()).configureRouting(any());
  }

  /**
   * Tests the case where the component passed in is null and mEnabledForegroundService is non-null.
   * Ultimately, routing is configured.
   */
  @Test
  public void testOnEnabledForegroundNfcFServiceChangedCase2() {
    cache = new RegisteredT3tIdentifiersCache(mContext, mRoutingManager);
    cache.mEnabledForegroundService = WALLET_HOLDER_SERVICE_COMPONENT;
    cache.mEnabledForegroundServiceUserId = USER_ID;
    cache.mNfcEnabled = true;

    cache.onEnabledForegroundNfcFServiceChanged(USER_ID, /* component = */ null);

    assertNull(cache.mEnabledForegroundService);
    assertEquals(-1, cache.mEnabledForegroundServiceUserId);
    verify(mRoutingManager).configureRouting(identifiersCaptor.capture());
    assertTrue(identifiersCaptor.getValue().isEmpty());
  }

  /**
   * Tests the case where the component passed in is non-null and mEnabledForegroundService is null.
   * Ultimately, routing is configured.
   */
  @Test
  public void testOnEnabledForegroundNfcFServiceChangedCase3() {
    cache = new RegisteredT3tIdentifiersCache(mContext, mRoutingManager);
    cache.mEnabledForegroundService = null;
    cache.mEnabledForegroundServiceUserId = -1;
    cache.mUserNfcFServiceInfo.put(USER_ID, getServiceList());
    cache.mNfcEnabled = true;

    cache.onEnabledForegroundNfcFServiceChanged(USER_ID, NON_PAYMENT_SERVICE_COMPONENT);

    assertEquals(NON_PAYMENT_SERVICE_COMPONENT, cache.mEnabledForegroundService);
    assertEquals(USER_ID, cache.mEnabledForegroundServiceUserId);
    verify(mRoutingManager).configureRouting(identifiersCaptor.capture());
    assertEquals(1, identifiersCaptor.getValue().size());
    T3tIdentifier identifier = identifiersCaptor.getValue().get(0);
    assertEquals(SYSTEM_CODE, identifier.systemCode);
    assertEquals(NFCID2, identifier.nfcid2);
    assertEquals(T3TPMM, identifier.t3tPmm);
  }

  /**
   * Tests the case where the component passed in is non-null and mEnabledForegroundService is also
   * non-null. Ultimately, no action is taken.
   */
  @Test
  public void testOnEnabledForegroundNfcFServiceChangedCase4() {
    cache = new RegisteredT3tIdentifiersCache(mContext, mRoutingManager);
    cache.mEnabledForegroundService = WALLET_HOLDER_SERVICE_COMPONENT;
    cache.mEnabledForegroundServiceUserId = USER_ID;
    cache.mNfcEnabled = true;

    cache.onEnabledForegroundNfcFServiceChanged(USER_ID, NON_PAYMENT_SERVICE_COMPONENT);

    assertEquals(WALLET_HOLDER_SERVICE_COMPONENT, cache.mEnabledForegroundService);
    assertEquals(USER_ID, cache.mEnabledForegroundServiceUserId);
    verify(mRoutingManager, never()).configureRouting(any());
  }

  @Test
  public void testOnNfcEnabled() {
    cache = new RegisteredT3tIdentifiersCache(mContext);

    cache.onNfcEnabled();

    assertTrue(cache.mNfcEnabled);
  }

  @Test
  public void testOnNfcDisabled() {
    cache = new RegisteredT3tIdentifiersCache(mContext, mRoutingManager);
    cache.mNfcEnabled = true;
    cache.mForegroundT3tIdentifiersCache.put(NFCID2, mNfcFServiceInfo);
    cache.mEnabledForegroundService = WALLET_HOLDER_SERVICE_COMPONENT;
    cache.mEnabledForegroundServiceUserId = USER_ID;

    cache.onNfcDisabled();

    assertFalse(cache.mNfcEnabled);
    assertTrue(cache.mForegroundT3tIdentifiersCache.isEmpty());
    assertNull(cache.mEnabledForegroundService);
    assertEquals(-1, cache.mEnabledForegroundServiceUserId);
    verify(mRoutingManager).onNfccRoutingTableCleared();
  }

  @Test
  public void testOnUserSwitched() {
    cache = new RegisteredT3tIdentifiersCache(mContext, mRoutingManager);
    cache.mNfcEnabled = true;
    cache.mForegroundT3tIdentifiersCache.put(NFCID2, mNfcFServiceInfo);
    cache.mEnabledForegroundService = WALLET_HOLDER_SERVICE_COMPONENT;
    cache.mEnabledForegroundServiceUserId = USER_ID;

    cache.onUserSwitched();

    assertTrue(cache.mForegroundT3tIdentifiersCache.isEmpty());
    assertNull(cache.mEnabledForegroundService);
    assertEquals(-1, cache.mEnabledForegroundServiceUserId);
    verify(mRoutingManager).configureRouting(identifiersCaptor.capture());
    assertTrue(identifiersCaptor.getValue().isEmpty());
  }

  @Test
  public void testDump() throws Exception {
    cache = new RegisteredT3tIdentifiersCache(mContext, mRoutingManager);
    cache.mForegroundT3tIdentifiersCache.put(NFCID2, mNfcFServiceInfo);

    cache.dump(/* fd = */ null, mPw, /* args = */ null);

    verify(mPw, times(5)).println(anyString());
    verify(mPfd).close();
    verify(mNfcFServiceInfo).dump(any(), any(), any());
    verify(mRoutingManager).dump(any(), any(), any());
  }

  private ArrayList<NfcFServiceInfo> getServiceList() {
    ArrayList<NfcFServiceInfo> list = new ArrayList<NfcFServiceInfo>();
    list.add(mNfcFServiceInfo);
    return list;
  }
}