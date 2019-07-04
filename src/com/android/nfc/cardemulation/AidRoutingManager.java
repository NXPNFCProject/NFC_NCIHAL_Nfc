/*
 * Copyright (C) 2013 The Android Open Source Project
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
/******************************************************************************
*
*  The original Work has been changed by NXP.
*
*  Licensed under the Apache License, Version 2.0 (the "License");
*  you may not use this file except in compliance with the License.
*  You may obtain a copy of the License at
*
*  http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software
*  distributed under the License is distributed on an "AS IS" BASIS,
*  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*  See the License for the specific language governing permissions and
*  limitations under the License.
*
*  Copyright 2018-2019 NXP
*
******************************************************************************/
package com.android.nfc.cardemulation;

import android.util.Log;
import android.util.SparseArray;
import android.content.Context;
import android.app.ActivityThread;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import com.android.nfc.NfcService;
import android.util.SparseArray;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Hashtable;
import android.os.SystemProperties;
public class AidRoutingManager {

    static final String TAG = "AidRoutingManager";

    static final boolean DBG =
        ((SystemProperties.get("persist.nfc.ce_debug").equals("1")) ? true : false);

    static final int ROUTE_HOST = 0x00;

    // Every routing table entry is matched exact
    static final int AID_MATCHING_EXACT_ONLY = 0x00;
    // Every routing table entry can be matched either exact or prefix
    static final int AID_MATCHING_EXACT_OR_PREFIX = 0x01;
    // Every routing table entry is matched as a prefix
    static final int AID_MATCHING_PREFIX_ONLY = 0x02;
    // Every routing table entry can be matched either exact or prefix or subset only
    static final int AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX = 0x03;

    int mDefaultIsoDepRoute;
    //Let mDefaultRoute as default aid route
    int mDefaultRoute;

    int mMaxAidRoutingTableSize;
    int mDefaultAidRoute;
    final byte[] mOffHostRouteUicc;
    final byte[] mOffHostRouteEse;
    // Used for backward compatibility in case application doesn't specify the
    // SE
    final int mDefaultOffHostRoute;

    // How the NFC controller can match AIDs in the routing table;
    // see AID_MATCHING constants
    final int mAidMatchingSupport;
    private int mAidRoutingTableSize;
    // Maximum AID routing table size
    final Object mLock = new Object();
    //set the status of last AID routes commit to routing table
    //if true, last commit was successful,
    //if false, there was an overflow of routing table for commit using last set of AID's in (mRouteForAid)
    boolean mLastCommitStatus;

    // mAidRoutingTable contains the current routing table. The index is the route ID.
    // The route can include routes to a eSE/UICC.
    SparseArray<Set<String>> mAidRoutingTable =
            new SparseArray<Set<String>>();

    // Easy look-up what the route is for a certain AID
    HashMap<String, Integer> mRouteForAid = new HashMap<String, Integer>();

    private native int doGetDefaultRouteDestination();
    private native int doGetDefaultOffHostRouteDestination();
    private native byte[] doGetOffHostUiccDestination();
    private native byte[] doGetOffHostEseDestination();
    private native int doGetAidMatchingMode();
    private native int doGetDefaultIsoDepRouteDestination();
    final ActivityManager mActivityManager;
    final class AidEntry {
        boolean isOnHost;
        String offHostSE;
        int aidInfo;
        int powerstate;
        int route;
    }

    public AidRoutingManager() {
        mDefaultRoute = doGetDefaultRouteDestination();
        if (DBG)
          Log.d(TAG, "mDefaultRoute=0x" + Integer.toHexString(mDefaultRoute));
        mDefaultOffHostRoute = doGetDefaultOffHostRouteDestination();
        if (DBG)
          Log.d(TAG, "mDefaultOffHostRoute=0x" + Integer.toHexString(mDefaultOffHostRoute));
        mOffHostRouteUicc = doGetOffHostUiccDestination();
        if (DBG)
            Log.d(TAG, "mOffHostRouteUicc=" + Arrays.toString(mOffHostRouteUicc));
        mOffHostRouteEse = doGetOffHostEseDestination();
        if (DBG)
          Log.d(TAG, "mOffHostRouteEse=" + Arrays.toString(mOffHostRouteEse));
        mAidMatchingSupport = doGetAidMatchingMode();
        if (DBG) Log.d(TAG, "mAidMatchingSupport=0x" + Integer.toHexString(mAidMatchingSupport));
        mDefaultAidRoute =   NfcService.getInstance().GetDefaultRouteEntry() >> 0x08;
        if (DBG)
          Log.d(TAG, "mDefaultAidRoute=0x" + Integer.toHexString(mDefaultAidRoute));
        mDefaultIsoDepRoute = doGetDefaultIsoDepRouteDestination();
        if (DBG) Log.d(TAG, "mDefaultIsoDepRoute=0x" + Integer.toHexString(mDefaultIsoDepRoute));
        mLastCommitStatus = true;

        Context context = (Context) ActivityThread.currentApplication();
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    }

    public boolean supportsAidPrefixRouting() {
        return mAidMatchingSupport == AID_MATCHING_EXACT_OR_PREFIX ||
                mAidMatchingSupport == AID_MATCHING_PREFIX_ONLY ||
                 mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX;
    }

    public boolean supportsAidSubsetRouting() {
        return mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX;
    }

    public int calculateAidRouteSize(HashMap<String, AidEntry> routeCache) {
        // TAG + ROUTE + LENGTH_BYTE + POWER
        int AID_HDR_LENGTH = 0x04;
        int routeTableSize = 0x00;
        for(Map.Entry<String, AidEntry> aidEntry : routeCache.entrySet()) {
            String aid = aidEntry.getKey();
            // removing prefix length
            if(aid.endsWith("*")) {
                routeTableSize += ((aid.length() - 0x01) / 0x02) + AID_HDR_LENGTH;
            } else {
                routeTableSize += (aid.length() / 0x02)+ AID_HDR_LENGTH;
            }
        }
        if (DBG) Log.d(TAG, "calculateAidRouteSize: " + routeTableSize);
        return routeTableSize;
    }

    private void clearNfcRoutingTableLocked() {
        for (Map.Entry<String, Integer> aidEntry : mRouteForAid.entrySet())  {
            String aid = aidEntry.getKey();
            if (aid.endsWith("*")) {
                if (mAidMatchingSupport == AID_MATCHING_EXACT_ONLY) {
                    Log.e(TAG, "Device does not support prefix AIDs but AID [" + aid
                            + "] is registered");
                } else if (mAidMatchingSupport == AID_MATCHING_PREFIX_ONLY) {
                    if (DBG) Log.d(TAG, "Unrouting prefix AID " + aid);
                    // Cut off '*' since controller anyway treats all AIDs as a prefix
                    aid = aid.substring(0, aid.length() - 1);
                } else if (mAidMatchingSupport == AID_MATCHING_EXACT_OR_PREFIX ||
                    mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX) {
                    aid = aid.substring(0, aid.length() - 1);
                    if (DBG) Log.d(TAG, "Unrouting prefix AID " + aid);
                }
            }  else if (aid.endsWith("#")) {
                if (mAidMatchingSupport == AID_MATCHING_EXACT_ONLY) {
                    Log.e(TAG, "Device does not support subset AIDs but AID [" + aid
                            + "] is registered");
                } else if (mAidMatchingSupport == AID_MATCHING_PREFIX_ONLY ||
                    mAidMatchingSupport == AID_MATCHING_EXACT_OR_PREFIX) {
                    Log.e(TAG, "Device does not support subset AIDs but AID [" + aid
                            + "] is registered");
                } else if (mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX) {
                    if (DBG) Log.d(TAG, "Unrouting subset AID " + aid);
                    aid = aid.substring(0, aid.length() - 1);
                }
            } else {
                if (DBG) Log.d(TAG, "Unrouting exact AID " + aid);
            }

            NfcService.getInstance().unrouteAids(aid);
        }
        if (NfcService.getInstance().getNciVersion() != NfcService.getInstance().NCI_VERSION_1_0) {
          // unRoute EmptyAid
          NfcService.getInstance().unrouteAids("");
        }
    }

    private int getRouteForSecureElement(String se) {
      if (se == null || se.length() <= 3) {
        return 0;
      }
      try {
        if (se.startsWith("eSE") && mOffHostRouteEse != null) {
          int index = Integer.parseInt(se.substring(3));
          if (mOffHostRouteEse.length >= index && index > 0) {
            return mOffHostRouteEse[index - 1] & 0xFF;
          }
        } else if (se.startsWith("SIM") && mOffHostRouteUicc != null) {
          int index = Integer.parseInt(se.substring(3));
          if (mOffHostRouteUicc.length >= index && index > 0) {
            return mOffHostRouteUicc[index - 1] & 0xFF;
          }
          if (mOffHostRouteEse == null && mOffHostRouteUicc == null)
            return mDefaultOffHostRoute;
        }
      } catch (NumberFormatException e) {
      }
      return 0;
    }

    public boolean configureRouting(HashMap<String, AidEntry> aidMap, boolean force) {
        boolean aidRouteResolved = false;
        HashMap<String, AidEntry> aidRoutingTableCache = new HashMap<String, AidEntry>(aidMap.size());
        ArrayList<Integer> seList = new ArrayList<Integer>();
        SparseArray<Set<String>> aidRoutingTable = new SparseArray<Set<String>>(aidMap.size());
        HashMap<String, Integer> routeForAid = new HashMap<String, Integer>(aidMap.size());
        HashMap<String, Integer> infoForAid = new HashMap<String, Integer>(aidMap.size());
        HashMap<String, Integer> powerForAid = new HashMap<String, Integer>(aidMap.size());
        mDefaultRoute = NfcService.getInstance().GetDefaultRouteLoc();
        mAidRoutingTableSize = NfcService.getInstance().getAidRoutingTableSize();
        mDefaultAidRoute =   NfcService.getInstance().GetDefaultRouteEntry() >> 0x08;
        Log.e(TAG, "Size of routing table"+mAidRoutingTableSize);
        seList.add(mDefaultAidRoute);
        // Then, populate internal data structures first
        for (Map.Entry<String, AidEntry> aidEntry : aidMap.entrySet())  {
            int route = ROUTE_HOST;
            if (!aidEntry.getValue().isOnHost) {
                String offHostSE = aidEntry.getValue().offHostSE;
                if (offHostSE == null) {
                    route = mDefaultOffHostRoute;
                } else {
                    route = getRouteForSecureElement(offHostSE);
                    if (route == 0) {
                        Log.e(TAG, "Invalid Off host Aid Entry " + offHostSE);
                        continue;
                    }
                }
            }
            if (!seList.contains(route))
              seList.add(route);
            aidEntry.getValue().route = route;
            int aidType = aidEntry.getValue().aidInfo;
            int power = aidEntry.getValue().powerstate;
            String aid = aidEntry.getKey();
            Set<String> entries = aidRoutingTable.get(route, new HashSet<String>());
            entries.add(aid);
            aidRoutingTable.put(route, entries);
            routeForAid.put(aid, route);
            infoForAid.put(aid, aidType);
            powerForAid.put(aid, power);
            if (DBG) Log.d(TAG, "#######Routing AID " + aid + " to route "
                        + Integer.toString(route) + " with power "+ power);
        }
        if (!seList.contains(ROUTE_HOST))
          seList.add(ROUTE_HOST);

        synchronized (mLock) {
            if (routeForAid.equals(mRouteForAid) && !force) {
                if (DBG) Log.d(TAG, "Routing table unchanged, not updating");
                return false;
            }

            // Otherwise, update internal structures and commit new routing
            clearNfcRoutingTableLocked();
            mRouteForAid = routeForAid;
            mAidRoutingTable = aidRoutingTable;
            mMaxAidRoutingTableSize = NfcService.getInstance().getAidRoutingTableSize();
            if (DBG) Log.d(TAG, "mMaxAidRoutingTableSize: " + mMaxAidRoutingTableSize);

          for(int index=0; index < seList.size(); index++) {
            mDefaultRoute = seList.get(index);
            if(index != 0)
              if (DBG) Log.d(TAG, "AidRoutingTable is full, try to switch mDefaultRoute to 0x" + Integer.toHexString(mDefaultRoute));

              aidRoutingTableCache.clear();
            if (mAidMatchingSupport == AID_MATCHING_PREFIX_ONLY) {
                /* If a non-default route registers an exact AID which is shorter
                 * than this exact AID, this will create a problem with controllers
                 * that treat every AID in the routing table as a prefix.
                 * For example, if App A registers F0000000041010 as an exact AID,
                 * and App B registers F000000004 as an exact AID, and App B is not
                 * the default route, the following would be added to the routing table:
                 * F000000004 -> non-default destination
                 * However, because in this mode, the controller treats every routing table
                 * entry as a prefix, it means F0000000041010 would suddenly go to the non-default
                 * destination too, whereas it should have gone to the default.
                 *
                 * The only way to prevent this is to add the longer AIDs of the
                 * default route at the top of the table, so they will be matched first.
                 */
                Set<String> defaultRouteAids = mAidRoutingTable.get(mDefaultRoute);
                if (defaultRouteAids != null) {
                    for (String defaultRouteAid : defaultRouteAids) {
                        // Check whether there are any shorted AIDs routed to non-default
                        // TODO this is O(N^2) run-time complexity...
                        for (Map.Entry<String, Integer> aidEntry : mRouteForAid.entrySet()) {
                            String aid = aidEntry.getKey();
                            int route = aidEntry.getValue();
                            if (defaultRouteAid.startsWith(aid) && route != mDefaultRoute) {
                                if (DBG) Log.d(TAG, "Adding AID " + defaultRouteAid + " for default " +
                                        "route, because a conflicting shorter AID will be " +
                                        "added to the routing table");
                                aidRoutingTableCache.put(defaultRouteAid, aidMap.get(defaultRouteAid));
                            }
                        }
                    }
                }
            }
            // Add AID entries for
            // 1. all non-default routes
            // 2. default route but only payment AID
            // Add AID entries for all non-default routes
            for (int i = 0; i < mAidRoutingTable.size(); i++) {
                int route = mAidRoutingTable.keyAt(i);
                if (route != mDefaultRoute) {
                    Set<String> aidsForRoute = mAidRoutingTable.get(route);
                    for (String aid : aidsForRoute) {
                        if (aid.endsWith("*")) {
                            if (mAidMatchingSupport == AID_MATCHING_EXACT_ONLY) {
                                Log.e(TAG, "This device does not support prefix AIDs.");
                            } else if (mAidMatchingSupport == AID_MATCHING_PREFIX_ONLY) {
                                if (DBG) Log.d(TAG, "Routing prefix AID " + aid + " to route "
                                        + Integer.toString(route));
                                // Cut off '*' since controller anyway treats all AIDs as a prefix
                                  aidRoutingTableCache.put(aid.substring(0,aid.length() - 1), aidMap.get(aid));
                            } else if (mAidMatchingSupport == AID_MATCHING_EXACT_OR_PREFIX ||
                              mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX) {
                                if (DBG) Log.d(TAG, "Routing prefix AID " + aid + " to route "
                                        + Integer.toString(route));
                             aidRoutingTableCache.put(aid.substring(0,aid.length() - 1), aidMap.get(aid));
                            }
                        } else if (aid.endsWith("#")) {
                            if (mAidMatchingSupport == AID_MATCHING_EXACT_ONLY) {
                                Log.e(TAG, "Device does not support subset AIDs but AID [" + aid
                                        + "] is registered");
                            } else if (mAidMatchingSupport == AID_MATCHING_PREFIX_ONLY ||
                                mAidMatchingSupport == AID_MATCHING_EXACT_OR_PREFIX) {
                                Log.e(TAG, "Device does not support subset AIDs but AID [" + aid
                                        + "] is registered");
                            } else if (mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX) {
                                if (DBG) Log.d(TAG, "Routing subset AID " + aid + " to route "
                                        + Integer.toString(route));
                                  aidRoutingTableCache.put(aid.substring(0,aid.length() - 1), aidMap.get(aid));
                            }
                        } else {
                            if (DBG) Log.d(TAG, "Routing exact AID " + aid + " to route "
                                    + Integer.toString(route));
                             aidRoutingTableCache.put(aid, aidMap.get(aid));
                        }
                      }
                    }
                }
                if( calculateAidRouteSize(aidRoutingTableCache) <= mMaxAidRoutingTableSize) {
                aidRouteResolved = true;
                break;
              }
            }
            if(aidRouteResolved == true) {
              commit(aidRoutingTableCache);
              NfcService.getInstance().updateDefaultAidRoute(mDefaultRoute);
              mLastCommitStatus = true;
          } else {
              Log.e(TAG, "RoutingTable unchanged because it's full, not updating");
              NfcService.getInstance().notifyRoutingTableFull();
              mLastCommitStatus = false;
          }
        }
        if (NfcService.getInstance().isNfcEnabled())
          NfcService.getInstance().commitRouting();
        return true;
    }

    private void commit(HashMap<String, AidEntry> routeCache ) {
       if(routeCache == null)
       {
         return;
       }
        for (Map.Entry<String, AidEntry> aidEntry : routeCache.entrySet())  {
            if(aidEntry.getKey().isEmpty())
                continue;
            AidEntry element = aidEntry.getValue();
            if (DBG) Log.d (TAG, element.toString());
            NfcService.getInstance().routeAids(
                 aidEntry.getKey(),
                 element.route,
                 element.aidInfo,
                 element.powerstate);
        }
        AidEntry emptyAidEntry = routeCache.get("");
        if (emptyAidEntry != null)
          NfcService.getInstance().routeAids(
              "", emptyAidEntry.route, emptyAidEntry.aidInfo, emptyAidEntry.powerstate);
    }
    /**
     * This notifies that the AID routing table in the controller
     * has been cleared (usually due to NFC being turned off).
     */
    public void onNfccRoutingTableCleared() {
        // The routing table in the controller was cleared
        // To stay in sync, clear our own tables.
        synchronized (mLock) {
            mAidRoutingTable.clear();
            mRouteForAid.clear();
        }
    }

    public boolean getLastCommitRoutingStatus() {
        return mLastCommitStatus;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Routing table:");
        pw.println("    Default route: " + ((mDefaultRoute == 0x00) ? "host" : "secure element"));
        synchronized (mLock) {
            for (int i = 0; i < mAidRoutingTable.size(); i++) {
                Set<String> aids = mAidRoutingTable.valueAt(i);
                pw.println("    Routed to 0x" + Integer.toHexString(mAidRoutingTable.keyAt(i)) + ":");
                for (String aid : aids) {
                    pw.println("        \"" + aid + "\"");
                }
            }
        }
    }

    // Returns true if AppChooserActivity is foreground to restart RF discovery so that
    // TapAgainDialog is dismissed when an external reader detects the device.
    private boolean isProcessingTapAgain() {
        String appChooserActivityClassName = AppChooserActivity.class.getName();
        return appChooserActivityClassName.equals(getTopClass());
    }

    private String getTopClass() {
        String topClass = null;
        List<RunningTaskInfo> tasks = mActivityManager.getRunningTasks(1);
        if (tasks != null && tasks.size() > 0) {
            topClass = tasks.get(0).topActivity.getClassName();
        }
        return topClass;
    }
}
