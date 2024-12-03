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

import static com.android.nfc.cardemulation.AidRoutingManager.AID_MATCHING_EXACT_ONLY;
import static com.android.nfc.cardemulation.AidRoutingManager.AID_MATCHING_EXACT_OR_PREFIX;
import static com.android.nfc.cardemulation.AidRoutingManager.AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX;
import static com.android.nfc.cardemulation.AidRoutingManager.AID_MATCHING_PREFIX_ONLY;
import static com.android.nfc.cardemulation.AidRoutingManager.ROUTE_HOST;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.NfcService;
import com.android.nfc.NfcStatsLog;
import com.android.nfc.cardemulation.AidRoutingManager.AidEntry;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public class AidRoutingManagerTest {

  private AidRoutingManager manager;
  private MockitoSession mStaticMockSession;

  @Mock
  private RoutingOptionManager mRoutingOptionManager;
  @Mock
  private NfcService mNfcService;
  @Mock
  private PrintWriter mPw;

  @Captor
  private ArgumentCaptor<String> unroutedAidsCaptor;
  @Captor
  private ArgumentCaptor<String> routedAidsCaptor;
  @Captor
  private ArgumentCaptor<Integer> routeCaptor;
  @Captor
  private ArgumentCaptor<Integer> aidTypeCaptor;
  @Captor
  private ArgumentCaptor<Integer> powerCaptor;
  @Captor
  private ArgumentCaptor<Integer> codeCaptor;
  @Captor
  private ArgumentCaptor<Integer> arg1Captor;
  @Captor
  private ArgumentCaptor<Integer> arg2Captor;
  @Captor
  private ArgumentCaptor<Integer> arg3Captor;

  private static final int DEFAULT_ROUTE = 0;
  private static final int OVERRIDE_DEFAULT_ROUTE = 10;
  private static final int DEFAULT_OFFHOST_ROUTE = 20;
  private static final int OVERRIDE_ISODEP_ROUTE = 30;
  private static final byte[] OFFHOST_ROUTE_UICC = new byte[] {5, 6, 7, 8};
  private static final byte[] OFFHOST_ROUTE_ESE = new byte[] {1, 2, 3, 4};
  private static final int FIRST_AID_ENTRY_POWER = 1;
  private static final int FIRST_AID_ENTRY_AID_INFO = 2;
  private static final int SECOND_AID_ENTRY_POWER = 3;
  private static final int SECOND_AID_ENTRY_AID_INFO = 4;
  private static final int THIRD_AID_ENTRY_POWER = 5;
  private static final int THIRD_AID_ENTRY_AID_INFO = 6;
  private static final int FOURTH_AID_ENTRY_POWER = 7;
  private static final int FOURTH_AID_ENTRY_AID_INFO = 8;

  @Before
  public void setUp() {
    mStaticMockSession = ExtendedMockito.mockitoSession()
        .mockStatic(RoutingOptionManager.class)
        .mockStatic(NfcService.class)
        .mockStatic(NfcStatsLog.class)
        .strictness(Strictness.LENIENT)
        .startMocking();
    MockitoAnnotations.initMocks(this);
    when(RoutingOptionManager.getInstance()).thenReturn(mRoutingOptionManager);
    when(NfcService.getInstance()).thenReturn(mNfcService);
  }

  @After
  public void tearDown() {
    mStaticMockSession.finishMocking();
  }

  @Test
  public void testConstructor() {
    manager = new AidRoutingManager();
  }

  @Test
  public void testSupportsAidPrefixRouting_ReturnsTrue() {
    when(mRoutingOptionManager.getAidMatchingSupport()).thenReturn(AID_MATCHING_EXACT_OR_PREFIX);
    manager = new AidRoutingManager();

    boolean result = manager.supportsAidPrefixRouting();

    assertThat(result).isTrue();
  }

  @Test
  public void testSupportsAidPrefixRouting_ReturnsFalse() {
    when(mRoutingOptionManager.getAidMatchingSupport()).thenReturn(AID_MATCHING_EXACT_ONLY);
    manager = new AidRoutingManager();

    boolean result = manager.supportsAidPrefixRouting();

    assertThat(result).isFalse();
  }

  @Test
  public void testSupportsAidSubsetRouting_ReturnsTrue() {
    when(mRoutingOptionManager.getAidMatchingSupport())
        .thenReturn(AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX);
    manager = new AidRoutingManager();

    boolean result = manager.supportsAidSubsetRouting();

    assertThat(result).isTrue();
  }

  @Test
  public void testSupportsAidSubsetRouting_ReturnsFalse() {
    when(mRoutingOptionManager.getAidMatchingSupport()).thenReturn(AID_MATCHING_EXACT_OR_PREFIX);
    manager = new AidRoutingManager();

    boolean result = manager.supportsAidSubsetRouting();

    assertThat(result).isFalse();
  }

  @Test
  public void testCalculateAidRouteSizeWithEmptyCache() {
    manager = new AidRoutingManager();

    int result = manager.calculateAidRouteSize(new HashMap<String, AidEntry>());

    assertThat(result).isEqualTo(0);
  }

  @Test
  public void testCalculateAidRouteSizeWithNonEmptyCache() {
    String firstAidEntry = "0000000000";
    String secondAidEntry = "000000000000000";
    HashMap<String, AidEntry> cache = new HashMap<>();
    cache.put(firstAidEntry + "*", null);
    cache.put(secondAidEntry, null);
    manager = new AidRoutingManager();

    int result = manager.calculateAidRouteSize(cache);

    int expected = (firstAidEntry.length() / 2) + 4 + (secondAidEntry.length() / 2) + 4;
    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void testConfigureRoutingWithEmptyMap_ReturnsFalse() {
    manager = new AidRoutingManager();

    boolean result = manager.configureRouting(/* aidMap = */ new HashMap<String, AidEntry>(),
        /* force = */ false);

    assertThat(result).isFalse();
  }

  /**
   * Tests the case wherein:
   *  (1) The default route (mDefaultRoute) is overridden to the value OVERRIDE_DEFAULT_ROUTE.
   *  (2) Both mOffHostRouteUicc and mOffHostRouteEse are non-null.
   *  (3) mAidMatchingSupport is equal to AID_MATCHING_PREFIX_ONLY
   *  (4) mDefaultIsoDepRoute is equal to ROUTE_HOST (so that the default route is registered)
   *  (5) NCI Version 2 is used.
   *
   *  Ultimately, the contents of aidMap should be committed.
   */
  @Test
  public void testConfigureRoutingTestCase1_CommitsCache() {
    when(mRoutingOptionManager.isRoutingTableOverrided()).thenReturn(true);
    when(mRoutingOptionManager.getDefaultOffHostRoute()).thenReturn(DEFAULT_OFFHOST_ROUTE);
    when(mRoutingOptionManager.getOverrideDefaultRoute()).thenReturn(OVERRIDE_DEFAULT_ROUTE);
    when(mRoutingOptionManager.getOffHostRouteUicc()).thenReturn(OFFHOST_ROUTE_UICC);
    when(mRoutingOptionManager.getOffHostRouteEse()).thenReturn(OFFHOST_ROUTE_ESE);
    when(mRoutingOptionManager.getAidMatchingSupport()).thenReturn(AID_MATCHING_PREFIX_ONLY);
    when(mRoutingOptionManager.getDefaultIsoDepRoute()).thenReturn(ROUTE_HOST);
    when(mNfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_2_0);
    when(mNfcService.getAidRoutingTableSize()).thenReturn(0);
    manager = new AidRoutingManager();
    manager.mRouteForAid.put("first*", 0);
    manager.mRouteForAid.put("second#", 0);
    manager.mRouteForAid.put("third", 0);

    boolean result = manager.configureRouting(getAidMap(), /* force = */ false);

    assertThat(result).isTrue();
    verify(mNfcService, times(4)).unrouteAids(unroutedAidsCaptor.capture());
    assertThat(unroutedAidsCaptor.getAllValues().contains("first")).isTrue();
    assertThat(unroutedAidsCaptor.getAllValues().contains("second#")).isTrue();
    assertThat(unroutedAidsCaptor.getAllValues().contains("third")).isTrue();
    assertThat(unroutedAidsCaptor.getAllValues().contains("")).isTrue();
    verify(mNfcService, times(3)).routeAids(routedAidsCaptor.capture(),
                                            routeCaptor.capture(),
                                            aidTypeCaptor.capture(),
                                            powerCaptor.capture());
    assertThat(routedAidsCaptor.getAllValues().get(0)).isEqualTo("");
    assertThat(routedAidsCaptor.getAllValues().get(1)).isEqualTo("firstAidEntry");
    assertThat(routedAidsCaptor.getAllValues().get(2)).isEqualTo("fourthAidEntry");
    assertThat(routeCaptor.getAllValues().get(0)).isEqualTo(OVERRIDE_DEFAULT_ROUTE);
    assertThat(routeCaptor.getAllValues().get(1)).isEqualTo(OFFHOST_ROUTE_ESE[1]);
    assertThat(routeCaptor.getAllValues().get(2)).isEqualTo(OFFHOST_ROUTE_UICC[0]);
    assertThat(aidTypeCaptor.getAllValues().get(0))
        .isEqualTo(RegisteredAidCache.AID_ROUTE_QUAL_PREFIX);
    assertThat(aidTypeCaptor.getAllValues().get(1)).isEqualTo(FIRST_AID_ENTRY_AID_INFO);
    assertThat(aidTypeCaptor.getAllValues().get(2)).isEqualTo(FOURTH_AID_ENTRY_AID_INFO);
    assertThat(powerCaptor.getAllValues().get(0)).isEqualTo(RegisteredAidCache.POWER_STATE_ALL);
    assertThat(powerCaptor.getAllValues().get(1)).isEqualTo(FIRST_AID_ENTRY_POWER);
    assertThat(powerCaptor.getAllValues().get(2)).isEqualTo(FOURTH_AID_ENTRY_POWER);
    verify(mNfcService).commitRouting();
    assertThat(manager.mDefaultRoute).isEqualTo(OVERRIDE_DEFAULT_ROUTE);
    assertThat(manager.mRouteForAid.size()).isEqualTo(3);
    assertThat(manager.mPowerForAid.size()).isEqualTo(3);
    assertThat(manager.mAidRoutingTable.size()).isEqualTo(3);
  }

  /**
   * Tests the case wherein:
   *  (1) The default route (mDefaultRoute) is unmodified (DEFAULT_ROUTE).
   *  (2) Both mOffHostRouteUicc and mOffHostRouteEse are non-null.
   *  (3) mAidMatchingSupport is equal to AID_MATCHING_PREFIX_ONLY
   *  (4) mDefaultIsoDepRoute is equal to ROUTE_HOST (so that the default route is registered)
   *  (5) NCI Version 1 is used.
   *
   *  Ultimately, nothing is committed and an error message is written to NfcStatsLog.
   */
  @Test
  public void testConfigureRoutingTestCase2_WritesError() {
    when(mRoutingOptionManager.isRoutingTableOverrided()).thenReturn(false);
    when(mRoutingOptionManager.getDefaultOffHostRoute()).thenReturn(DEFAULT_OFFHOST_ROUTE);
    when(mRoutingOptionManager.getDefaultRoute()).thenReturn(DEFAULT_ROUTE);
    when(mRoutingOptionManager.getOffHostRouteUicc()).thenReturn(OFFHOST_ROUTE_UICC);
    when(mRoutingOptionManager.getOffHostRouteEse()).thenReturn(OFFHOST_ROUTE_ESE);
    when(mRoutingOptionManager.getAidMatchingSupport()).thenReturn(AID_MATCHING_PREFIX_ONLY);
    when(mRoutingOptionManager.getDefaultIsoDepRoute()).thenReturn(ROUTE_HOST);
    when(mRoutingOptionManager.isAutoChangeEnabled()).thenReturn(true);
    when(mNfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_1_0);
    when(mNfcService.getAidRoutingTableSize()).thenReturn(0);
    manager = new AidRoutingManager();

    boolean result = manager.configureRouting(getAidMap(), /* force = */ false);

    assertThat(result).isTrue();
    verify(mNfcService, never()).unrouteAids(anyString());
    verify(mNfcService, never()).routeAids(anyString(), anyInt(), anyInt(), anyInt());
    verify(mNfcService, never()).commitRouting();
    ExtendedMockito.verify(() -> NfcStatsLog.write(codeCaptor.capture(),
                                                   arg1Captor.capture(),
                                                   arg2Captor.capture(),
                                                   arg3Captor.capture()));
    assertThat(codeCaptor.getValue()).isEqualTo(NfcStatsLog.NFC_ERROR_OCCURRED);
    assertThat(arg1Captor.getValue()).isEqualTo(NfcStatsLog.NFC_ERROR_OCCURRED__TYPE__AID_OVERFLOW);
    assertThat(arg2Captor.getValue()).isEqualTo(0);
    assertThat(arg3Captor.getValue()).isEqualTo(0);
    assertThat(manager.mDefaultRoute).isEqualTo(OFFHOST_ROUTE_ESE[1]);
    assertThat(manager.mRouteForAid.size()).isEqualTo(3);
    assertThat(manager.mPowerForAid.size()).isEqualTo(3);
    assertThat(manager.mAidRoutingTable.size()).isEqualTo(3);
  }

  /**
   * Tests the case wherein:
   *  (1) The default route (mDefaultRoute) is unmodified (DEFAULT_ROUTE).
   *  (2) Both mOffHostRouteUicc and mOffHostRouteEse are non-null.
   *  (3) mAidMatchingSupport is equal to AID_MATCHING_ONLY
   *  (4) mDefaultIsoDepRoute is equal to OVERRIDE_ISODEP_ROUTE (so that the default route is not
   *  registered)
   *  (5) NCI Version 2 is used.
   *
   *  Ultimately, the routing table is not updated and no other action is taken.
   */
  @Test
  public void testConfigureRoutingTestCase3_DoNothing() {
    when(mRoutingOptionManager.isRoutingTableOverrided()).thenReturn(false);
    when(mRoutingOptionManager.getDefaultOffHostRoute()).thenReturn(DEFAULT_OFFHOST_ROUTE);
    when(mRoutingOptionManager.getDefaultRoute()).thenReturn(DEFAULT_ROUTE);
    when(mRoutingOptionManager.getOffHostRouteUicc()).thenReturn(OFFHOST_ROUTE_UICC);
    when(mRoutingOptionManager.getOffHostRouteEse()).thenReturn(OFFHOST_ROUTE_ESE);
    when(mRoutingOptionManager.getAidMatchingSupport()).thenReturn(AID_MATCHING_EXACT_ONLY);
    when(mRoutingOptionManager.getDefaultIsoDepRoute()).thenReturn(OVERRIDE_ISODEP_ROUTE);
    when(mNfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_1_0);
    when(mNfcService.getAidRoutingTableSize()).thenReturn(0);
    manager = new AidRoutingManager();
    manager.mRouteForAid.put("first*", 0);
    manager.mRouteForAid.put("second#", 0);
    manager.mRouteForAid.put("third", 0);
    // Create a HashMap with only one AidEntry
    HashMap<String, AidEntry> aidMap = new HashMap<>();
    AidEntry aidEntry = manager.new AidEntry();
    aidEntry.isOnHost = false;
    aidEntry.offHostSE = "eSE2";
    aidEntry.power = 1;
    aidEntry.aidInfo = 2;
    aidMap.put("firstAidEntry*", aidEntry);

    boolean result = manager.configureRouting(aidMap, /* force = */ false);

    assertThat(result).isTrue();

    verify(mNfcService, times(3)).unrouteAids(unroutedAidsCaptor.capture());
    assertThat(unroutedAidsCaptor.getAllValues().contains("first*")).isTrue();
    assertThat(unroutedAidsCaptor.getAllValues().contains("second#")).isTrue();
    assertThat(unroutedAidsCaptor.getAllValues().contains("third")).isTrue();
    ExtendedMockito.verify(() ->
        NfcStatsLog.write(anyInt(), anyInt(), anyInt(), anyInt()), times(0));
    assertThat(manager.mDefaultRoute).isEqualTo(DEFAULT_ROUTE);
    assertThat(manager.mRouteForAid.size()).isEqualTo(1);
    assertThat(manager.mPowerForAid.size()).isEqualTo(1);
    assertThat(manager.mAidRoutingTable.size()).isEqualTo(1);
  }

  /**
   * Tests the case wherein:
   *  (1) The default route (mDefaultRoute) is unmodified (DEFAULT_ROUTE).
   *  (2) Both mOffHostRouteUicc and mOffHostRouteEse are non-null.
   *  (3) mAidMatchingSupport is equal to AID_MATCHING_ONLY
   *  (4) mDefaultIsoDepRoute is equal to OVERRIDE_ISODEP_ROUTE (so that the default route is not
   *  registered)
   *  (5) NCI Version 2 is used.
   *
   *  This case is identical to Test Case 3, with the exception of the value of the force variable,
   *  which causes the cache to be committed.
   */
  @Test
  public void testConfigureRoutingTestCase4_CommitsCache() {
    when(mRoutingOptionManager.isRoutingTableOverrided()).thenReturn(false);
    when(mRoutingOptionManager.getDefaultOffHostRoute()).thenReturn(DEFAULT_OFFHOST_ROUTE);
    when(mRoutingOptionManager.getDefaultRoute()).thenReturn(DEFAULT_ROUTE);
    when(mRoutingOptionManager.getOffHostRouteUicc()).thenReturn(OFFHOST_ROUTE_UICC);
    when(mRoutingOptionManager.getOffHostRouteEse()).thenReturn(OFFHOST_ROUTE_ESE);
    when(mRoutingOptionManager.getAidMatchingSupport()).thenReturn(AID_MATCHING_EXACT_ONLY);
    when(mRoutingOptionManager.getDefaultIsoDepRoute()).thenReturn(OVERRIDE_ISODEP_ROUTE);
    when(mNfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_1_0);
    when(mNfcService.getAidRoutingTableSize()).thenReturn(0);
    manager = new AidRoutingManager();
    manager.mRouteForAid.put("first*", 0);
    manager.mRouteForAid.put("second#", 0);
    manager.mRouteForAid.put("third", 0);
    // Create a HashMap with only one AidEntry
    HashMap<String, AidEntry> aidMap = new HashMap<>();
    AidEntry aidEntry = manager.new AidEntry();
    aidEntry.isOnHost = false;
    aidEntry.offHostSE = "eSE2";
    aidEntry.power = 1;
    aidEntry.aidInfo = 2;
    aidMap.put("firstAidEntry*", aidEntry);

    boolean result = manager.configureRouting(aidMap, /* force = */ true);

    assertThat(result).isTrue();
    verify(mNfcService).commitRouting();
  }

  /**
   * Tests the case wherein:
   *  (1) The default route (mDefaultRoute) is overridden to the value OVERRIDE_DEFAULT_ROUTE.
   *  (2) Both mOffHostRouteUicc and mOffHostRouteEse are null.
   *  (3) mAidMatchingSupport is equal to AID_MATCHING_EXACT_OR_PREFIX
   *  (4) mDefaultIsoDepRoute is equal to ROUTE_HOST (so that the default route is registered)
   *  (5) NCI Version 2 is used.
   *
   *  Ultimately, the contents of aidMap should be committed.
   */
  @Test
  public void testConfigureRoutingTestCase5_CommitsCache() {
    when(mRoutingOptionManager.isRoutingTableOverrided()).thenReturn(true);
    when(mRoutingOptionManager.getOverrideDefaultRoute()).thenReturn(OVERRIDE_DEFAULT_ROUTE);
    when(mRoutingOptionManager.getOverrideDefaultOffHostRoute()).thenReturn(DEFAULT_OFFHOST_ROUTE);
    when(mRoutingOptionManager.getOverrideDefaultIsoDepRoute()).thenReturn(ROUTE_HOST);
    when(mRoutingOptionManager.getOffHostRouteUicc()).thenReturn(null);
    when(mRoutingOptionManager.getOffHostRouteEse()).thenReturn(null);
    when(mRoutingOptionManager.getAidMatchingSupport()).thenReturn(AID_MATCHING_EXACT_OR_PREFIX);
    when(mNfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_2_0);
    when(mNfcService.getAidRoutingTableSize()).thenReturn(0);
    manager = new AidRoutingManager();
    manager.mRouteForAid.put("first*", 0);
    manager.mRouteForAid.put("second#", 0);
    manager.mRouteForAid.put("third", 0);

    boolean result = manager.configureRouting(getAidMap(), /* force = */ false);

    assertThat(result).isTrue();
    verify(mNfcService, times(4)).unrouteAids(unroutedAidsCaptor.capture());
    assertThat(unroutedAidsCaptor.getAllValues().contains("first")).isTrue();
    assertThat(unroutedAidsCaptor.getAllValues().contains("second#")).isTrue();
    assertThat(unroutedAidsCaptor.getAllValues().contains("third")).isTrue();
    assertThat(unroutedAidsCaptor.getAllValues().contains("")).isTrue();
    verify(mNfcService, times(4)).routeAids(routedAidsCaptor.capture(),
                                            routeCaptor.capture(),
                                            aidTypeCaptor.capture(),
                                            powerCaptor.capture());
    assertThat(routedAidsCaptor.getAllValues().get(0)).isEqualTo("");
    assertThat(routedAidsCaptor.getAllValues().get(1)).isEqualTo("fourthAidEntry");
    assertThat(routedAidsCaptor.getAllValues().get(2)).isEqualTo("thirdAidEntry");
    assertThat(routedAidsCaptor.getAllValues().get(3)).isEqualTo("firstAidEntry");
    assertThat(routeCaptor.getAllValues().get(0)).isEqualTo(OVERRIDE_DEFAULT_ROUTE);
    assertThat(routeCaptor.getAllValues().get(1)).isEqualTo(DEFAULT_OFFHOST_ROUTE);
    assertThat(routeCaptor.getAllValues().get(2)).isEqualTo(DEFAULT_OFFHOST_ROUTE);
    assertThat(routeCaptor.getAllValues().get(3)).isEqualTo(DEFAULT_OFFHOST_ROUTE);
    assertThat(aidTypeCaptor.getAllValues().get(0))
        .isEqualTo(RegisteredAidCache.AID_ROUTE_QUAL_PREFIX);
    assertThat(aidTypeCaptor.getAllValues().get(1)).isEqualTo(FOURTH_AID_ENTRY_AID_INFO);
    assertThat(aidTypeCaptor.getAllValues().get(2)).isEqualTo(THIRD_AID_ENTRY_AID_INFO);
    assertThat(aidTypeCaptor.getAllValues().get(3)).isEqualTo(FIRST_AID_ENTRY_AID_INFO);
    assertThat(powerCaptor.getAllValues().get(0)).isEqualTo(RegisteredAidCache.POWER_STATE_ALL);
    assertThat(powerCaptor.getAllValues().get(1)).isEqualTo(FOURTH_AID_ENTRY_POWER);
    assertThat(powerCaptor.getAllValues().get(2)).isEqualTo(THIRD_AID_ENTRY_POWER);
    assertThat(powerCaptor.getAllValues().get(3)).isEqualTo(FIRST_AID_ENTRY_POWER);
    verify(mNfcService).commitRouting();
    assertThat(manager.mDefaultRoute).isEqualTo(OVERRIDE_DEFAULT_ROUTE);
    assertThat(manager.mRouteForAid.size()).isEqualTo(4);
    assertThat(manager.mPowerForAid.size()).isEqualTo(4);
    assertThat(manager.mAidRoutingTable.size()).isEqualTo(2);
  }

  /**
   * Tests the case wherein:
   *  (1) The default route (mDefaultRoute) is overridden to the value OVERRIDE_DEFAULT_ROUTE.
   *  (2) Both mOffHostRouteUicc and mOffHostRouteEse are null.
   *  (3) mAidMatchingSupport is equal to AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX
   *  (4) mDefaultIsoDepRoute is equal to ROUTE_HOST (so that the default route is registered)
   *  (5) NCI Version 2 is used.
   *
   *  Ultimately, the contents of aidMap should be committed.
   */
  @Test
  public void testConfigureRoutingTestCase6_CommitsCache() {
    when(mRoutingOptionManager.isRoutingTableOverrided()).thenReturn(true);
    when(mRoutingOptionManager.getOverrideDefaultRoute()).thenReturn(OVERRIDE_DEFAULT_ROUTE);
    when(mRoutingOptionManager.getOverrideDefaultOffHostRoute()).thenReturn(DEFAULT_OFFHOST_ROUTE);
    when(mRoutingOptionManager.getOverrideDefaultIsoDepRoute()).thenReturn(ROUTE_HOST);
    when(mRoutingOptionManager.getOffHostRouteUicc()).thenReturn(null);
    when(mRoutingOptionManager.getOffHostRouteEse()).thenReturn(null);
    when(mRoutingOptionManager.getAidMatchingSupport())
        .thenReturn(AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX);
    when(mNfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_2_0);
    when(mNfcService.getAidRoutingTableSize()).thenReturn(0);
    manager = new AidRoutingManager();
    manager.mRouteForAid.put("first*", 0);
    manager.mRouteForAid.put("second#", 0);
    manager.mRouteForAid.put("third", 0);

    boolean result = manager.configureRouting(getAidMap(), /* force = */ false);

    assertThat(result).isTrue();
    verify(mNfcService, times(4)).unrouteAids(unroutedAidsCaptor.capture());
    assertThat(unroutedAidsCaptor.getAllValues().contains("first")).isTrue();
    assertThat(unroutedAidsCaptor.getAllValues().contains("second")).isTrue();
    assertThat(unroutedAidsCaptor.getAllValues().contains("third")).isTrue();
    assertThat(unroutedAidsCaptor.getAllValues().contains("")).isTrue();
    verify(mNfcService, times(5)).routeAids(routedAidsCaptor.capture(),
                                            routeCaptor.capture(),
                                            aidTypeCaptor.capture(),
                                            powerCaptor.capture());
    assertThat(routedAidsCaptor.getAllValues().get(0)).isEqualTo("");
    assertThat(routedAidsCaptor.getAllValues().get(1)).isEqualTo("secondAidEntry");
    assertThat(routedAidsCaptor.getAllValues().get(2)).isEqualTo("fourthAidEntry");
    assertThat(routedAidsCaptor.getAllValues().get(3)).isEqualTo("thirdAidEntry");
    assertThat(routedAidsCaptor.getAllValues().get(4)).isEqualTo("firstAidEntry");
    assertThat(routeCaptor.getAllValues().get(0)).isEqualTo(OVERRIDE_DEFAULT_ROUTE);
    assertThat(routeCaptor.getAllValues().get(1)).isEqualTo(DEFAULT_ROUTE);
    assertThat(routeCaptor.getAllValues().get(2)).isEqualTo(DEFAULT_OFFHOST_ROUTE);
    assertThat(routeCaptor.getAllValues().get(3)).isEqualTo(DEFAULT_OFFHOST_ROUTE);
    assertThat(routeCaptor.getAllValues().get(4)).isEqualTo(DEFAULT_OFFHOST_ROUTE);
    assertThat(aidTypeCaptor.getAllValues().get(0))
        .isEqualTo(RegisteredAidCache.AID_ROUTE_QUAL_PREFIX);
    assertThat(aidTypeCaptor.getAllValues().get(1)).isEqualTo(SECOND_AID_ENTRY_AID_INFO);
    assertThat(aidTypeCaptor.getAllValues().get(2)).isEqualTo(FOURTH_AID_ENTRY_AID_INFO);
    assertThat(aidTypeCaptor.getAllValues().get(3)).isEqualTo(THIRD_AID_ENTRY_AID_INFO);
    assertThat(aidTypeCaptor.getAllValues().get(4)).isEqualTo(FIRST_AID_ENTRY_AID_INFO);
    assertThat(powerCaptor.getAllValues().get(0)).isEqualTo(RegisteredAidCache.POWER_STATE_ALL);
    assertThat(powerCaptor.getAllValues().get(1)).isEqualTo(SECOND_AID_ENTRY_POWER);
    assertThat(powerCaptor.getAllValues().get(2)).isEqualTo(FOURTH_AID_ENTRY_POWER);
    assertThat(powerCaptor.getAllValues().get(3)).isEqualTo(THIRD_AID_ENTRY_POWER);
    assertThat(powerCaptor.getAllValues().get(4)).isEqualTo(FIRST_AID_ENTRY_POWER);
    verify(mNfcService).commitRouting();
    assertThat(manager.mDefaultRoute).isEqualTo(OVERRIDE_DEFAULT_ROUTE);
    assertThat(manager.mRouteForAid.size()).isEqualTo(4);
    assertThat(manager.mPowerForAid.size()).isEqualTo(4);
    assertThat(manager.mAidRoutingTable.size()).isEqualTo(2);
  }

  /**
   * Tests the case wherein:
   *  (1) The default route (mDefaultRoute) is unmodified (DEFAULT_ROUTE).
   *  (2) Both mOffHostRouteUicc and mOffHostRouteEse are null.
   *  (3) mAidMatchingSupport is equal to AID_MATCHING_PREFIX_ONLY
   *  (4) mDefaultIsoDepRoute is equal to ROUTE_HOST (so that the default route is registered)
   *  (5) NCI Version 1 is used.
   *
   *  Ultimately, due to the value of mAidRoutingTableSize, the contents of the cache are committed.
   */
  @Test
  public void testConfigureRoutingTestCase7_CommitsCache() {
    when(mRoutingOptionManager.isRoutingTableOverrided()).thenReturn(false);
    when(mRoutingOptionManager.getDefaultOffHostRoute()).thenReturn(DEFAULT_OFFHOST_ROUTE);
    when(mRoutingOptionManager.getDefaultRoute()).thenReturn(DEFAULT_ROUTE);
    when(mRoutingOptionManager.getOffHostRouteUicc()).thenReturn(null);
    when(mRoutingOptionManager.getOffHostRouteEse()).thenReturn(null);
    when(mRoutingOptionManager.getAidMatchingSupport()).thenReturn(AID_MATCHING_PREFIX_ONLY);
    when(mRoutingOptionManager.getDefaultIsoDepRoute()).thenReturn(ROUTE_HOST);
    when(mNfcService.getNciVersion()).thenReturn(NfcService.NCI_VERSION_1_0);
    when(mNfcService.getAidRoutingTableSize()).thenReturn(100);
    manager = new AidRoutingManager();

    boolean result = manager.configureRouting(getAidMap(), /* force = */ false);

    assertThat(result).isTrue();
    verify(mNfcService, never()).unrouteAids(anyString());
    verify(mNfcService, times(3)).routeAids(routedAidsCaptor.capture(),
                                            routeCaptor.capture(),
                                            aidTypeCaptor.capture(),
                                            powerCaptor.capture());
    assertThat(routedAidsCaptor.getAllValues().get(0)).isEqualTo("fourthAidEntry");
    assertThat(routedAidsCaptor.getAllValues().get(1)).isEqualTo("firstAidEntry");
    assertThat(routedAidsCaptor.getAllValues().get(2)).isEqualTo("thirdAidEntry");
    assertThat(routeCaptor.getAllValues().get(0)).isEqualTo(DEFAULT_OFFHOST_ROUTE);
    assertThat(routeCaptor.getAllValues().get(1)).isEqualTo(DEFAULT_OFFHOST_ROUTE);
    assertThat(routeCaptor.getAllValues().get(2)).isEqualTo(DEFAULT_OFFHOST_ROUTE);
    assertThat(aidTypeCaptor.getAllValues().get(0)).isEqualTo(FOURTH_AID_ENTRY_AID_INFO);
    assertThat(aidTypeCaptor.getAllValues().get(1)).isEqualTo(FIRST_AID_ENTRY_AID_INFO);
    assertThat(aidTypeCaptor.getAllValues().get(2)).isEqualTo(THIRD_AID_ENTRY_AID_INFO);
    assertThat(powerCaptor.getAllValues().get(0)).isEqualTo(FOURTH_AID_ENTRY_POWER);
    assertThat(powerCaptor.getAllValues().get(1)).isEqualTo(FIRST_AID_ENTRY_POWER);
    assertThat(powerCaptor.getAllValues().get(2)).isEqualTo(THIRD_AID_ENTRY_POWER);
    verify(mNfcService).commitRouting();
    assertThat(manager.mDefaultRoute).isEqualTo(DEFAULT_ROUTE);
    assertThat(manager.mRouteForAid.size()).isEqualTo(4);
    assertThat(manager.mPowerForAid.size()).isEqualTo(4);
    assertThat(manager.mAidRoutingTable.size()).isEqualTo(2);
  }

  @Test
  public void testOnNfccRoutingTableCleared() {
    manager = new AidRoutingManager();
    manager.mAidRoutingTable.put(0, new HashSet<String>());
    manager.mRouteForAid.put("", 0);
    manager.mPowerForAid.put("", 0);

    manager.onNfccRoutingTableCleared();

    assertThat(manager.mAidRoutingTable.size()).isEqualTo(0);
    assertThat(manager.mRouteForAid.isEmpty()).isTrue();
    assertThat(manager.mPowerForAid.isEmpty()).isTrue();
  }

  @Test
  public void testDump() {
    manager = new AidRoutingManager();
    HashSet<String> routingTableSet = new HashSet<>();
    routingTableSet.add("");
    manager.mAidRoutingTable.put(0, routingTableSet);

    manager.dump(/* fd = */ null, mPw, /* args = */ null);

    verify(mPw, times(4)).println(anyString());
  }

  private HashMap<String, AidEntry> getAidMap() {
    HashMap<String, AidEntry> aidMap = new HashMap<>();
    AidEntry firstAidEntry = manager.new AidEntry();
    firstAidEntry.isOnHost = false;
    firstAidEntry.offHostSE = "eSE2";
    firstAidEntry.power = FIRST_AID_ENTRY_POWER;
    firstAidEntry.aidInfo = FIRST_AID_ENTRY_AID_INFO;
    aidMap.put("firstAidEntry*", firstAidEntry);

    AidEntry secondAidEntry = manager.new AidEntry();
    secondAidEntry.isOnHost = true;
    secondAidEntry.power = SECOND_AID_ENTRY_POWER;
    secondAidEntry.aidInfo = SECOND_AID_ENTRY_AID_INFO;
    aidMap.put("secondAidEntry#", secondAidEntry);

    AidEntry thirdAidEntry = manager.new AidEntry();
    thirdAidEntry.isOnHost = false;
    thirdAidEntry.offHostSE = "invalid SE";
    thirdAidEntry.power = THIRD_AID_ENTRY_POWER;
    thirdAidEntry.aidInfo = THIRD_AID_ENTRY_AID_INFO;
    aidMap.put("thirdAidEntry", thirdAidEntry);

    AidEntry fourthAidEntry = manager.new AidEntry();
    fourthAidEntry.isOnHost = false;
    fourthAidEntry.offHostSE = "SIM1";
    fourthAidEntry.power = FOURTH_AID_ENTRY_POWER;
    fourthAidEntry.aidInfo = FOURTH_AID_ENTRY_AID_INFO;
    aidMap.put("fourthAidEntry", fourthAidEntry);

    return aidMap;
  }
}
