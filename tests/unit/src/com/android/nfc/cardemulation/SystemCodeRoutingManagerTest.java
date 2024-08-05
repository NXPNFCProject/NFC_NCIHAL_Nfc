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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.NfcService;
import com.android.nfc.cardemulation.RegisteredT3tIdentifiersCache;
import com.android.nfc.cardemulation.RegisteredT3tIdentifiersCache.T3tIdentifier;

import java.io.PrintWriter;
import java.util.ArrayList;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

public class SystemCodeRoutingManagerTest {
  private SystemCodeRoutingManager manager;
  private MockitoSession mStaticMockSession;
  private T3tIdentifier mIdentifier;
  @Mock
  private NfcService mNfcService;
  @Mock
  private PrintWriter mPw;

  @Captor
  private ArgumentCaptor<String> systemCodeCaptor;
  @Captor
  private ArgumentCaptor<String> nfcId2Captor;
  @Captor
  private ArgumentCaptor<String> t3tPmmCaptor;

  private static final String IDENTIFIER_SYSTEM_CODE = "systemCode";
  private static final String IDENTIFIER_NFCID_2 = "nfcid2";
  private static final String IDENTIFIER_T3TPMM = "t3tpmm";

  @Before
  public void setUp() {
    mStaticMockSession = ExtendedMockito.mockitoSession()
        .mockStatic(NfcService.class)
        .strictness(Strictness.LENIENT)
        .startMocking();
    MockitoAnnotations.initMocks(this);

    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    mIdentifier = new RegisteredT3tIdentifiersCache(context)
        .new T3tIdentifier(IDENTIFIER_SYSTEM_CODE, IDENTIFIER_NFCID_2, IDENTIFIER_T3TPMM);
    manager = new SystemCodeRoutingManager();

    when(NfcService.getInstance()).thenReturn(mNfcService);
    doNothing().when(mNfcService).deregisterT3tIdentifier(anyString(), anyString(), anyString());
    doNothing().when(mNfcService).registerT3tIdentifier(anyString(), anyString(), anyString());
  }

  @After
  public void tearDown() {
    mStaticMockSession.finishMocking();
  }

  @Test
  public void testConfigureRoutingWithNoItemsAddedOrRemoved() {
    boolean result = manager.configureRouting(new ArrayList<T3tIdentifier>());

    assertThat(result).isFalse();
    verify(mNfcService, never()).deregisterT3tIdentifier(anyString(), anyString(), anyString());
    verify(mNfcService, never()).registerT3tIdentifier(anyString(), anyString(), anyString());
    verify(mNfcService, never()).commitRouting();
    assertThat(manager.mConfiguredT3tIdentifiers).isEmpty();
  }

  @Test
  public void testConfigureRoutingWithItemsAdded() {
    ArrayList<T3tIdentifier> list = new ArrayList<>();
    list.add(mIdentifier);

    boolean result = manager.configureRouting(list);

    assertThat(result).isTrue();
    verify(mNfcService, never()).deregisterT3tIdentifier(anyString(), anyString(), anyString());
    verify(mNfcService).registerT3tIdentifier(systemCodeCaptor.capture(),
                                              nfcId2Captor.capture(),
                                              t3tPmmCaptor.capture());
    assertThat(systemCodeCaptor.getValue()).isEqualTo(IDENTIFIER_SYSTEM_CODE);
    assertThat(nfcId2Captor.getValue()).isEqualTo(IDENTIFIER_NFCID_2);
    assertThat(t3tPmmCaptor.getValue()).isEqualTo(IDENTIFIER_T3TPMM);
    verify(mNfcService).commitRouting();
    assertThat(manager.mConfiguredT3tIdentifiers.size()).isEqualTo(1);
    assertThat(manager.mConfiguredT3tIdentifiers.get(0)).isEqualTo(mIdentifier);
  }

  @Test
  public void testConfigureRoutingWithItemsRemoved() {
    manager.mConfiguredT3tIdentifiers.add(mIdentifier);

    boolean result = manager.configureRouting(new ArrayList<T3tIdentifier>());

    assertThat(result).isTrue();
    verify(mNfcService).deregisterT3tIdentifier(systemCodeCaptor.capture(),
                                                nfcId2Captor.capture(),
                                                t3tPmmCaptor.capture());
    assertThat(systemCodeCaptor.getValue()).isEqualTo(IDENTIFIER_SYSTEM_CODE);
    assertThat(nfcId2Captor.getValue()).isEqualTo(IDENTIFIER_NFCID_2);
    assertThat(t3tPmmCaptor.getValue()).isEqualTo(IDENTIFIER_T3TPMM);
    verify(mNfcService, never()).registerT3tIdentifier(anyString(), anyString(), anyString());
    verify(mNfcService).commitRouting();
    assertThat(manager.mConfiguredT3tIdentifiers).isEmpty();
  }

  @Test
  public void testOnNfccRoutingTableCleared() {
    manager.mConfiguredT3tIdentifiers.add(mIdentifier);

    manager.onNfccRoutingTableCleared();

    verify(mNfcService).clearT3tIdentifiersCache();
    assertThat(manager.mConfiguredT3tIdentifiers).isEmpty();
  }

  @Test
  public void testDump() {
    manager.mConfiguredT3tIdentifiers.add(mIdentifier);

    manager.dump(/* fd = */ null, mPw, /* args = */ null);

    verify(mPw, times(2)).println(anyString());
  }
}
