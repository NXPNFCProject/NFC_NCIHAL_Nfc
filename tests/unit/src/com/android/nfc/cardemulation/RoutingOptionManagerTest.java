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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.NfcService;

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
public class RoutingOptionManagerTest {

  @Mock
  private NfcService mNfcService;

  @Captor
  private ArgumentCaptor<Integer> routeCaptor;

  private static final int DEFAULT_ROUTE = 0;
  private static final int DEFAULT_ISO_DEP_ROUTE = 1;
  private static final int OVERRIDDEN_ISO_DEP_ROUTE = 10;
  private static final int OVERRIDDEN_OFF_HOST_ROUTE = 20;
  private static final int DEFAULT_OFF_HOST_ROUTE = 2;
  private static final byte[] OFF_HOST_UICC = new byte[] {1, 2};
  private static final byte[] OFF_HOST_ESE = new byte[] {3, 4};
  private static final int AID_MATCHING_MODE = 3;

  private static class TestRoutingOptionManager extends RoutingOptionManager {
    @Override
    int doGetDefaultRouteDestination() {
      return DEFAULT_ROUTE;
    }

    @Override
    int doGetDefaultIsoDepRouteDestination() {
      return DEFAULT_ISO_DEP_ROUTE;
    }

    @Override
    int doGetDefaultOffHostRouteDestination() {
      return DEFAULT_OFF_HOST_ROUTE;
    }

    @Override
    byte[] doGetOffHostUiccDestination() {
      return OFF_HOST_UICC;
    }

    @Override
    byte[] doGetOffHostEseDestination() {
      return OFF_HOST_ESE;
    }

    @Override
    int doGetAidMatchingMode() {
      return AID_MATCHING_MODE;
    }
  }

  private TestRoutingOptionManager manager;
  private MockitoSession mStaticMockSession;

  @Before
  public void setUp() throws Exception {
    mStaticMockSession = ExtendedMockito.mockitoSession()
        .mockStatic(NfcService.class)
        .strictness(Strictness.LENIENT)
        .startMocking();
    MockitoAnnotations.initMocks(this);

    when(NfcService.getInstance()).thenReturn(mNfcService);
  }

  @After
  public void tearDown() {
    mStaticMockSession.finishMocking();
  }

  @Test
  public void testConstructor() {
    manager = new TestRoutingOptionManager();

    assertEquals(DEFAULT_ROUTE, manager.mDefaultRoute);
    assertEquals(DEFAULT_ISO_DEP_ROUTE, manager.mDefaultIsoDepRoute);
    assertEquals(DEFAULT_OFF_HOST_ROUTE, manager.mDefaultOffHostRoute);
    assertEquals(OFF_HOST_UICC, manager.mOffHostRouteUicc);
    assertEquals(OFF_HOST_ESE, manager.mOffHostRouteEse);
    assertEquals(AID_MATCHING_MODE, manager.mAidMatchingSupport);
  }

  @Test
  public void testOverrideDefaultIsoDepRoute() {
    manager = new TestRoutingOptionManager();

    manager.overrideDefaultIsoDepRoute(OVERRIDDEN_ISO_DEP_ROUTE);

    assertEquals(OVERRIDDEN_ISO_DEP_ROUTE, manager.getOverrideDefaultIsoDepRoute());
    verify(mNfcService).setIsoDepProtocolRoute(routeCaptor.capture());
    assertEquals(Integer.valueOf(OVERRIDDEN_ISO_DEP_ROUTE), routeCaptor.getValue());
  }

  @Test
  public void testOverrideDefaultOffHostRoute() {
    manager = new TestRoutingOptionManager();

    manager.overrideDefaultOffHostRoute(OVERRIDDEN_OFF_HOST_ROUTE);

    assertEquals(OVERRIDDEN_OFF_HOST_ROUTE, manager.getOverrideDefaultOffHostRoute());
    verify(mNfcService).setTechnologyABFRoute(routeCaptor.capture());
    assertEquals(Integer.valueOf(OVERRIDDEN_OFF_HOST_ROUTE), routeCaptor.getValue());
  }

  @Test
  public void testOverrideDefaulttRoute() {
    manager = new TestRoutingOptionManager();

    manager.overrideDefaultRoute(OVERRIDDEN_OFF_HOST_ROUTE);

    assertEquals(OVERRIDDEN_OFF_HOST_ROUTE, manager.getOverrideDefaultRoute());
  }

  @Test
  public void testRecoverOverridedRoutingTable() {
    manager = new TestRoutingOptionManager();

    manager.recoverOverridedRoutingTable();

    verify(mNfcService).setIsoDepProtocolRoute(anyInt());
    verify(mNfcService).setTechnologyABFRoute(anyInt());
    assertEquals(RoutingOptionManager.ROUTE_UNKNOWN, manager.mOverrideDefaultRoute);
    assertEquals(RoutingOptionManager.ROUTE_UNKNOWN, manager.mOverrideDefaultIsoDepRoute);
    assertEquals(RoutingOptionManager.ROUTE_UNKNOWN, manager.mOverrideDefaultOffHostRoute);
  }

  @Test
  public void testGetters() {
    manager = new TestRoutingOptionManager();

    int overrideDefaultRoute = manager.getOverrideDefaultRoute();
    int defaultRoute = manager.getDefaultRoute();
    int defaultIsoDepRoute = manager.getDefaultIsoDepRoute();
    int defaultOffHostRoute = manager.getDefaultOffHostRoute();
    byte[] offHostRouteUicc = manager.getOffHostRouteUicc();
    byte[] offHostRouteEse = manager.getOffHostRouteEse();
    int aidMatchingSupport = manager.getAidMatchingSupport();

    assertEquals(-1, overrideDefaultRoute);
    assertEquals(DEFAULT_ROUTE, defaultRoute);
    assertEquals(DEFAULT_ISO_DEP_ROUTE, defaultIsoDepRoute);
    assertEquals(DEFAULT_OFF_HOST_ROUTE, defaultOffHostRoute);
    assertEquals(OFF_HOST_UICC, offHostRouteUicc);
    assertEquals(OFF_HOST_ESE, offHostRouteEse);
    assertEquals(AID_MATCHING_MODE, aidMatchingSupport);
  }

  @Test
  public void testIsRoutingTableOverrided() {
    manager = new TestRoutingOptionManager();

    boolean result = manager.isRoutingTableOverrided();

    assertFalse(result);
  }
}
