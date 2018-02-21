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
*  Copyright 2018 NXP
*
******************************************************************************/
package com.android.nfc.cardemulation;

import android.util.Log;
import android.util.SparseArray;

import com.android.nfc.NfcService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AidRoutingManager {

    static final String TAG = "AidRoutingManager";

    static final boolean DBG = true;

    static final int ROUTE_HOST = 0x00;

    // Every routing table entry is matched exact
    static final int AID_MATCHING_EXACT_ONLY = 0x00;
    // Every routing table entry can be matched either exact or prefix
    static final int AID_MATCHING_EXACT_OR_PREFIX = 0x01;
    // Every routing table entry is matched as a prefix
    static final int AID_MATCHING_PREFIX_ONLY = 0x02;
    // Every routing table entry can be matched either exact or prefix or subset only
    static final int AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX = 0x03;
    // This is the default IsoDep protocol route; it means
    // that for any AID that needs to be routed to this
    // destination, we won't need to add a rule to the routing
    // table, because this destination is already the default route.
    //
    // For Nexus devices, the default route is always 0x00.
    final int mDefaultRoute;
    final int mDefaultAidRoute;
    // For Nexus devices, just a static route to the eSE
    // OEMs/Carriers could manually map off-host AIDs
    // to the correct eSE/UICC based on state they keep.
    final int mDefaultOffHostRoute;

    // How the NFC controller can match AIDs in the routing table;
    // see AID_MATCHING constants
    final int mAidMatchingSupport;

    final Object mLock = new Object();

    // mAidRoutingTable contains the current routing table. The index is the route ID.
    // The route can include routes to a eSE/UICC.
    SparseArray<Set<String>> mAidRoutingTable =
            new SparseArray<Set<String>>();

    // Easy look-up what the route is for a certain AID
    HashMap<String, Integer> mRouteForAid = new HashMap<String, Integer>();

    private native int doGetDefaultRouteDestination();
    private native int doGetDefaultOffHostRouteDestination();
    private native int doGetAidMatchingMode();

    final class AidEntry {
        boolean isOnHost;
        int aidInfo;
        int powerstate;
        int route;
    }

    public AidRoutingManager() {
        mDefaultRoute = doGetDefaultRouteDestination();
        if (DBG) Log.d(TAG, "mDefaultRoute=0x" + Integer.toHexString(mDefaultRoute));
        mDefaultOffHostRoute = doGetDefaultOffHostRouteDestination();
        if (DBG) Log.d(TAG, "mDefaultOffHostRoute=0x" + Integer.toHexString(mDefaultOffHostRoute));
        mAidMatchingSupport = doGetAidMatchingMode();
        if (DBG) Log.d(TAG, "mAidMatchingSupport=0x" + Integer.toHexString(mAidMatchingSupport));
        mDefaultAidRoute =   NfcService.getInstance().GetDefaultRouteEntry() >> 0x08;
    }

    public boolean supportsAidPrefixRouting() {
        return mAidMatchingSupport == AID_MATCHING_EXACT_OR_PREFIX ||
                mAidMatchingSupport == AID_MATCHING_PREFIX_ONLY ||
                 mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX;
    }

    public boolean supportsAidSubsetRouting() {
        return mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX;
    }

    void clearNfcRoutingTableLocked() {
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
    NfcService.getInstance().unrouteAids("FFFFFFFFFFFFFFFF");
    }

    public boolean configureRouting(HashMap<String, AidEntry> aidMap) {
        SparseArray<Set<String>> aidRoutingTable = new SparseArray<Set<String>>(aidMap.size());
        HashMap<String, Integer> routeForAid = new HashMap<String, Integer>(aidMap.size());
        HashMap<String, Integer> infoForAid = new HashMap<String, Integer>(aidMap.size());
        HashMap<String, Integer> powerForAid = new HashMap<String, Integer>(aidMap.size());        
// Then, populate internal data structures first
        for (Map.Entry<String, AidEntry> aidEntry : aidMap.entrySet())  {
            int route = aidEntry.getValue().isOnHost ? ROUTE_HOST : aidEntry.getValue().route;
            if(route == -1)
                route = mDefaultOffHostRoute;
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

        synchronized (mLock) {
            if (routeForAid.equals(mRouteForAid)) {
                if (DBG) Log.d(TAG, "Routing table unchanged, not updating");
                return false;
            }

            // Otherwise, update internal structures and commit new routing
            clearNfcRoutingTableLocked();
            mRouteForAid = routeForAid;
            mAidRoutingTable = aidRoutingTable;
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
                                NfcService.getInstance().routeAids(defaultRouteAid, mDefaultRoute,
                                        infoForAid.get(defaultRouteAid),powerForAid.get(defaultRouteAid));
                            }
                        }
                    }
                }
            }

            // Add AID entries for all non-default routes
            for (int i = 0; i < mAidRoutingTable.size(); i++) {
                int route = mAidRoutingTable.keyAt(i);
                if (route != mDefaultAidRoute) {
                    Set<String> aidsForRoute = mAidRoutingTable.get(route);
                    for (String aid : aidsForRoute) {
                        if (aid.endsWith("*")) {
                            if (mAidMatchingSupport == AID_MATCHING_EXACT_ONLY) {
                                Log.e(TAG, "This device does not support prefix AIDs.");
                            } else if (mAidMatchingSupport == AID_MATCHING_PREFIX_ONLY) {
                                if (DBG) Log.d(TAG, "Routing prefix AID " + aid + " to route "
                                        + Integer.toString(route));
                                // Cut off '*' since controller anyway treats all AIDs as a prefix
                                NfcService.getInstance().routeAids(aid.substring(0,
                                                aid.length() - 1), route, infoForAid.get(aid),powerForAid.get(aid));
                            } else if (mAidMatchingSupport == AID_MATCHING_EXACT_OR_PREFIX ||
                              mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX) {
                                if (DBG) Log.d(TAG, "Routing prefix AID " + aid + " to route "
                                        + Integer.toString(route));
                                NfcService.getInstance().routeAids(aid.substring(0,aid.length() - 1),
                                        route, infoForAid.get(aid),powerForAid.get(aid));
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
                                  NfcService.getInstance().routeAids(aid.substring(0,aid.length() - 1),
                                          route, infoForAid.get(aid),powerForAid.get(aid));
                            }
                        } else {
                            if (DBG) Log.d(TAG, "Routing exact AID " + aid + " to route "
                                    + Integer.toString(route));
                            NfcService.getInstance().routeAids(aid, route, infoForAid.get(aid),powerForAid.get(aid));
                        }
                    }
                }
            }
        }

        // And finally commit the routing
        NfcService.getInstance().commitRouting();

        return true;
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
}
