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
import android.content.Context;
import android.app.ActivityThread;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import com.android.nfc.NfcService;
import android.util.SparseArray;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
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
    int mDefaultRoute;
    int mDefaultAidRoute;
    // For Nexus devices, just a static route to the eSE
    // OEMs/Carriers could manually map off-host AIDs
    // to the correct eSE/UICC based on state they keep.
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
    private native int doGetAidMatchingMode();
    final ActivityManager mActivityManager;
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

    public boolean configureApduPatternRouting(List<ApduPatternResolveInfo> apduPatternList) {
        NfcService.getInstance().unrouteApduPattern("FFFFFFFFFFFFFFEF");
        if(apduPatternList.size() == 0x00 || apduPatternList == null)
            return false;
        for(ApduPatternResolveInfo apduPatternInfo : apduPatternList) {
            NfcService.getInstance().routeApduPattern(apduPatternInfo.referenceData,apduPatternInfo.mask,
                    apduPatternInfo.route,apduPatternInfo.powerState);
        }
        return true;
    }

    public boolean configureRouting(HashMap<String, AidEntry> aidMap) {
        SparseArray<Set<String>> aidRoutingTable = new SparseArray<Set<String>>(aidMap.size());
        HashMap<String, Integer> routeForAid = new HashMap<String, Integer>(aidMap.size());
        HashMap<String, Integer> infoForAid = new HashMap<String, Integer>(aidMap.size());
        HashMap<String, Integer> powerForAid = new HashMap<String, Integer>(aidMap.size());
        Hashtable<String, AidEntry> routeCache = new Hashtable<String, AidEntry>(50);
        mDefaultRoute = NfcService.getInstance().GetDefaultRouteLoc();
        mAidRoutingTableSize = NfcService.getInstance().getAidRoutingTableSize();
        mDefaultAidRoute =   NfcService.getInstance().GetDefaultRouteEntry() >> 0x08;
        DefaultAidRouteResolveCache defaultRouteCache = new DefaultAidRouteResolveCache();
        Log.e(TAG, "Size of routing table"+mAidRoutingTableSize);
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
                if (DBG) Log.d(TAG, "Routing table unchanged, but commit the routing");
                if(mLastCommitStatus == false){
                    NfcService.getInstance().updateStatusOfServices(false);
                }
                else
                {/*If last commit status was success, And a new service is added whose AID's are
                already resolved by previously installed services, service state of newly installed app needs to be updated*/
                    NfcService.getInstance().updateStatusOfServices(true);
                }
                NfcService.getInstance().commitRouting();
                return false;
            }

            // Otherwise, update internal structures and commit new routing
            clearNfcRoutingTableLocked();
            mRouteForAid = routeForAid;
            mAidRoutingTable = aidRoutingTable;
          for(int routeIndex=0; routeIndex < 0x03; routeIndex++) {
            if (DBG) Log.d(TAG, "Routing table index"+routeIndex);
              routeCache.clear();
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
                                routeCache.put(defaultRouteAid, aidMap.get(defaultRouteAid));
                                 /*
                                NfcService.getInstance().routeAids(defaultRouteAid, mDefaultRoute,
                                        infoForAid.get(defaultRouteAid),powerForAid.get(defaultRouteAid));*/
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
                                /*
                                NfcService.getInstance().routeAids(aid.substring(0,
                                                aid.length() - 1), route, infoForAid.get(aid),powerForAid.get(aid)); */
                                routeCache.put(aid.substring(0,
                                                aid.length() - 1), aidMap.get(aid));
                            } else if (mAidMatchingSupport == AID_MATCHING_EXACT_OR_PREFIX ||
                              mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX) {
                                if (DBG) Log.d(TAG, "Routing prefix AID " + aid + " to route "
                                        + Integer.toString(route));
                                 routeCache.put(aid.substring(0,
                                                aid.length() - 1), aidMap.get(aid));
                                /*
                                NfcService.getInstance().routeAids(aid.substring(0,aid.length() - 1),
                                        route, infoForAid.get(aid),powerForAid.get(aid));
                                 */
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
                                  routeCache.put(aid.substring(0,
                                                aid.length() - 1), aidMap.get(aid));
                                  /*
                                  NfcService.getInstance().routeAids(aid.substring(0,aid.length() - 1),
                                          route, infoForAid.get(aid),powerForAid.get(aid));
                                   */
                            }
                        } else {
                            if (DBG) Log.d(TAG, "Routing exact AID " + aid + " to route "
                                    + Integer.toString(route));
                             routeCache.put(aid, aidMap.get(aid));
                            /*
                            NfcService.getInstance().routeAids(aid, route, infoForAid.get(aid),powerForAid.get(aid));
                            */
                        }
                      }
                    }
                }
                defaultRouteCache.updateDefaultAidRouteCache(routeCache , mDefaultRoute);
                if(defaultRouteCache.mAidRouteResolvedStatus)
                    break;
                else
                    mDefaultRoute = defaultRouteCache.getNextRouteLoc();
            }
        }
        if(defaultRouteCache.mAidRouteResolvedStatus == false) {
            if(mAidRoutingTable.size() == 0x00) {
                defaultRouteCache.mAidRouteResolvedStatus = true;
            if (DBG) Log.d(TAG, "Routing size calculation resolved true ");
                //update the cache , no applications present.
            }
            else if(defaultRouteCache.resolveDefaultAidRoute() == true) {
                if (DBG) Log.d(TAG, "Routing size calculation resolved true again ");
                defaultRouteCache.mAidRouteResolvedStatus = true;
                routeCache = defaultRouteCache.getResolvedAidRouteCache();
                //update preferences if different
                NfcService.getInstance().setDefaultAidRouteLoc(defaultRouteCache.getResolvedAidRoute());
            } else {
                if (DBG) Log.d(TAG, "Routing size calculation resolved routing table full ");
                NfcService.getInstance().notifyRoutingTableFull();
            }
        }
        // And finally commit the routing and update the status of commit for each service
        if(defaultRouteCache.mAidRouteResolvedStatus == true) {
            if (DBG) Log.d(TAG, "Routing size calculation resolved commit ");
            commit(routeCache);
            NfcService.getInstance().updateStatusOfServices(true);
            mLastCommitStatus = true;
        }
        else{
            if (DBG) Log.d(TAG, "Routing size calculation resolved commit false ");
            NfcService.getInstance().updateStatusOfServices(false);
            mLastCommitStatus = false;
        }
        NfcService.getInstance().commitRouting();

        return true;
    }

    private void commit(Hashtable<String, AidEntry> routeCache ) {
       if(routeCache == null)
       {
         return;
       }
       //List<AidEntry> list = Collections.list(routeCache.elements());
            //Collections.sort(list);
            //NfcService.getInstance().clearRouting();
        for (Map.Entry<String, AidEntry> aidEntry : routeCache.entrySet())  {
            AidEntry element = aidEntry.getValue();
            if (DBG) Log.d (TAG, element.toString());
            NfcService.getInstance().routeAids(
                 aidEntry.getKey(),
                 element.route,
                 element.aidInfo,
                 element.powerstate);
        }
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

    final class ApduPatternResolveInfo {
        public String  mask;
        public String  referenceData;
        public int route;
        public int powerState;
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
    final class DefaultAidRouteResolveCache {
        static final int AID_HDR_LENGTH = 0x04; // TAG + ROUTE + LENGTH_BYTE + POWER
        static final int MAX_AID_ENTRIES = 32;
        //AidCacheTable contains the current aid routing table for particular route.
        //The index is the route ID.
        private SparseArray<Hashtable<String, AidEntry>> aidCacheTable;
        private HashMap <Integer , Integer> aidCacheSize;
        //HashMap containing aid routing size .
        //The index is route ID.

        private Hashtable<String, AidEntry> resolvedAidCache;//HashTable containing resolved default aid routeCache
        public int mCurrDefaultAidRoute; // current default route in preferences
        private int mResolvedAidRoute;    //resolved aid route location
        public boolean mAidRouteResolvedStatus; // boolean value
        private ArrayList<Integer> aidRoutes;//non-default route location

        DefaultAidRouteResolveCache () {
            aidCacheTable = new SparseArray<Hashtable<String, AidEntry>>(0x03);
            resolvedAidCache = new Hashtable<String, AidEntry>();
            aidCacheSize= new HashMap <Integer , Integer>(0x03);
            aidRoutes = new ArrayList<Integer>();
            mCurrDefaultAidRoute = NfcService.getInstance().GetDefaultRouteLoc();
            if (DBG) Log.d(TAG, "mCurrDefaultAidRoute"+mCurrDefaultAidRoute);
            mAidRouteResolvedStatus = false;
            aidRoutes.add(mCurrDefaultAidRoute);
        }

        private Hashtable<String, AidEntry> extractResolvedCache()
        {
            if(mAidRouteResolvedStatus == false) {
                return null;
            }
            for(int i=0;i< aidCacheTable.size() ;i++) {
                int route = aidCacheTable.keyAt(i);
                if(route == mResolvedAidRoute) {
                    return aidCacheTable.get(route);
                }
            }
            return null;
        }


        public int calculateAidRouteSize(Hashtable<String, AidEntry> routeCache) {
            int routeTableSize = 0x00;
            int routeAidCount = 0x00;
            for(Map.Entry<String, AidEntry> aidEntry : routeCache.entrySet()) {
                String aid = aidEntry.getKey();
                if(aid.endsWith("*") || aid.endsWith("#")) {
                    routeTableSize += ((aid.length() - 0x01) / 0x02) + AID_HDR_LENGTH; // removing prefix length
                } else {
                    routeTableSize += (aid.length() / 0x02)+ AID_HDR_LENGTH;
                }
                routeAidCount++;
            }
            Log.d(TAG, "calculateAidRouteSize final Routing table size" +routeTableSize);
            if(routeTableSize <= mAidRoutingTableSize && routeAidCount > MAX_AID_ENTRIES) {
                routeTableSize = mAidRoutingTableSize + 0x01;
            }
                return routeTableSize;
        }

       public boolean updateDefaultAidRouteCache(Hashtable<String, AidEntry> routeCache , int route) {
           int routesize = 0x00;
           Hashtable<String, AidEntry> tempRouteCache = new Hashtable<String, AidEntry>(0x50);
           tempRouteCache.putAll(routeCache);
           routesize = calculateAidRouteSize(tempRouteCache);
           Log.d(TAG, "updateDefaultAidRouteCache Routing table size" +routesize);
           if(route == mCurrDefaultAidRoute)
           {
                if(routesize <= mAidRoutingTableSize) {
                // maximum aid table size is less than  AID route table size
                    if (DBG) Log.d(TAG, " updateDefaultAidRouteCache Routing size calculation resolved ");
                    mAidRouteResolvedStatus = true;
                }
            } else {
                aidCacheTable.put(route, tempRouteCache);
                aidCacheSize.put(route, routesize);
                mAidRouteResolvedStatus = false;
                if (DBG) Log.d(TAG, " updateDefaultAidRouteCache Routing size calculation resolved false ");
            }
           return mAidRouteResolvedStatus;
       }

       public boolean resolveDefaultAidRoute () {

           int minRoute = 0xFF;
           int minAidRouteSize = mAidRoutingTableSize;
           int tempRoute = 0x00;
           int tempCacheSize = 0x00;
           Hashtable<String, AidEntry> aidRouteCache = new Hashtable<String, AidEntry>();
           Set<Integer> keys = aidCacheSize.keySet();
           for (Integer key : keys) {
               tempRoute = key;
               tempCacheSize = aidCacheSize.get(key);
               if (tempCacheSize <= minAidRouteSize) {
                   minAidRouteSize = tempCacheSize;
                   minRoute = tempRoute;
               }
           }
           if(minRoute != 0xFF) {
               mAidRouteResolvedStatus = true;
               mResolvedAidRoute = minRoute;
               Log.d(TAG, "min route found "+mResolvedAidRoute);
           }

           return mAidRouteResolvedStatus;
       }

       public int getResolvedAidRoute () {
           return mResolvedAidRoute;
       }

       public Hashtable<String, AidEntry>  getResolvedAidRouteCache() {

           return extractResolvedCache();
       }

       public int getNextRouteLoc() {
           for (int i = 0; i < 0x03; i++) {
               if(!aidRoutes.contains(i))
               {
                   aidRoutes.add(i);
                   return i;
               }
           }
           return 0xFF;
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
