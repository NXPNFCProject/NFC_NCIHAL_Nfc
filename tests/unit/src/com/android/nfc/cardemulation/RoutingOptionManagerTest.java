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
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public class RoutingOptionManagerTest {

    @Mock
    private NfcService mNfcService;

    @Captor
    private ArgumentCaptor<Integer> mRouteCaptor;

    private static final int DEFAULT_ROUTE = 0;
    private static final int DEFAULT_ISO_DEP_ROUTE = 1;
    private static final int OVERRIDDEN_ISO_DEP_ROUTE = 10;
    private static final int OVERRIDDEN_OFF_HOST_ROUTE = 20;
    private static final int DEFAULT_OFF_HOST_ROUTE = 2;
    private static final int DEFAULT_FELICA_ROUTE = 3;
    private static final int DEFAULT_SC_ROUTE = 2;
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
        int doGetDefaultFelicaRouteDestination() {
            return DEFAULT_FELICA_ROUTE;
        }

        @Override
        int doGetDefaultScRouteDestination() {
            return DEFAULT_SC_ROUTE;
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

    private TestRoutingOptionManager mManager;
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
        mManager = new TestRoutingOptionManager();

        assertEquals(DEFAULT_ROUTE, mManager.mDefaultRoute);
        assertEquals(DEFAULT_ISO_DEP_ROUTE, mManager.mDefaultIsoDepRoute);
        assertEquals(DEFAULT_OFF_HOST_ROUTE, mManager.mDefaultOffHostRoute);
        assertEquals(DEFAULT_FELICA_ROUTE, mManager.mDefaultFelicaRoute);
        assertEquals(OFF_HOST_UICC, mManager.mOffHostRouteUicc);
        assertEquals(OFF_HOST_ESE, mManager.mOffHostRouteEse);
        assertEquals(AID_MATCHING_MODE, mManager.mAidMatchingSupport);
    }

    @Test
    public void testOverrideDefaultIsoDepRoute() {
        mManager = new TestRoutingOptionManager();

        mManager.overrideDefaultIsoDepRoute(OVERRIDDEN_ISO_DEP_ROUTE);

        assertEquals(OVERRIDDEN_ISO_DEP_ROUTE, mManager.getOverrideDefaultIsoDepRoute());
        verify(mNfcService).setIsoDepProtocolRoute(mRouteCaptor.capture());
        assertEquals(Integer.valueOf(OVERRIDDEN_ISO_DEP_ROUTE), mRouteCaptor.getValue());
    }

    @Test
    public void testOverrideDefaultOffHostRoute() {
        mManager = new TestRoutingOptionManager();

        mManager.overrideDefaultOffHostRoute(OVERRIDDEN_OFF_HOST_ROUTE);

        assertEquals(OVERRIDDEN_OFF_HOST_ROUTE, mManager.getOverrideDefaultOffHostRoute());
        verify(mNfcService).setTechnologyABFRoute(mRouteCaptor.capture(), mRouteCaptor.capture());
        assertEquals(Integer.valueOf(OVERRIDDEN_OFF_HOST_ROUTE), mRouteCaptor.getValue());
    }

    @Test
    public void testOverrideDefaulttRoute() {
        mManager = new TestRoutingOptionManager();

        mManager.overrideDefaultRoute(OVERRIDDEN_OFF_HOST_ROUTE);

        assertEquals(OVERRIDDEN_OFF_HOST_ROUTE, mManager.getOverrideDefaultRoute());
    }

    @Test
    public void testRecoverOverridedRoutingTable() {
        mManager = new TestRoutingOptionManager();

        mManager.recoverOverridedRoutingTable();

        verify(mNfcService).setIsoDepProtocolRoute(anyInt());
        verify(mNfcService).setTechnologyABFRoute(anyInt(), anyInt());
        assertEquals(RoutingOptionManager.ROUTE_UNKNOWN, mManager.mOverrideDefaultRoute);
        assertEquals(RoutingOptionManager.ROUTE_UNKNOWN, mManager.mOverrideDefaultIsoDepRoute);
        assertEquals(RoutingOptionManager.ROUTE_UNKNOWN, mManager.mOverrideDefaultOffHostRoute);
    }

    @Test
    public void testGetters() {
        mManager = new TestRoutingOptionManager();

        int overrideDefaultRoute = mManager.getOverrideDefaultRoute();
        int defaultRoute = mManager.getDefaultRoute();
        int defaultIsoDepRoute = mManager.getDefaultIsoDepRoute();
        int defaultOffHostRoute = mManager.getDefaultOffHostRoute();
        int defaultFelicaRoute = mManager.getDefaultFelicaRoute();
        byte[] offHostRouteUicc = mManager.getOffHostRouteUicc();
        byte[] offHostRouteEse = mManager.getOffHostRouteEse();
        int aidMatchingSupport = mManager.getAidMatchingSupport();

        assertEquals(-1, overrideDefaultRoute);
        assertEquals(DEFAULT_ROUTE, defaultRoute);
        assertEquals(DEFAULT_ISO_DEP_ROUTE, defaultIsoDepRoute);
        assertEquals(DEFAULT_OFF_HOST_ROUTE, defaultOffHostRoute);
        assertEquals(DEFAULT_FELICA_ROUTE, defaultFelicaRoute);
        assertEquals(OFF_HOST_UICC, offHostRouteUicc);
        assertEquals(OFF_HOST_ESE, offHostRouteEse);
        assertEquals(AID_MATCHING_MODE, aidMatchingSupport);
    }

    @Test
    public void testIsRoutingTableOverrided() {
        mManager = new TestRoutingOptionManager();

        boolean result = mManager.isRoutingTableOverrided();

        assertFalse(result);
    }
}
