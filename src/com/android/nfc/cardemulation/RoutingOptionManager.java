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
package com.android.nfc.cardemulation;

import android.os.SystemProperties;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.nfc.NfcService;

import java.util.Arrays;

public class RoutingOptionManager {
    public final String TAG = "RoutingOptionManager";

    static final boolean DBG = SystemProperties.getBoolean("persist.nfc.debug_enabled", false);

    static final int ROUTE_UNKNOWN = -1;

    int mDefaultRoute;
    int mDefaultIsoDepRoute;
    int mDefaultOffHostRoute;
    final byte[] mOffHostRouteUicc;
    final byte[] mOffHostRouteEse;
    final int mAidMatchingSupport;

    int mOverrideDefaultRoute = ROUTE_UNKNOWN;
    int mOverrideDefaultIsoDepRoute = ROUTE_UNKNOWN;
    int mOverrideDefaultOffHostRoute = ROUTE_UNKNOWN;

    boolean mIsRoutingTableOverrided = false;

    @VisibleForTesting
    native int doGetDefaultRouteDestination();
    @VisibleForTesting
    native int doGetDefaultIsoDepRouteDestination();
    @VisibleForTesting
    native int doGetDefaultOffHostRouteDestination();
    @VisibleForTesting
    native byte[] doGetOffHostUiccDestination();
    @VisibleForTesting
    native byte[] doGetOffHostEseDestination();
    @VisibleForTesting
    native int doGetAidMatchingMode();

    public static RoutingOptionManager getInstance() {
        return RoutingOptionManager.Singleton.INSTANCE;
    }

    private static class Singleton {
        private static final RoutingOptionManager INSTANCE = new RoutingOptionManager();
    }

    @VisibleForTesting
    RoutingOptionManager() {
        mDefaultRoute = doGetDefaultRouteDestination();
        if (DBG)
            Log.d(TAG, "mDefaultRoute=0x" + Integer.toHexString(mDefaultRoute));
        mDefaultIsoDepRoute = doGetDefaultIsoDepRouteDestination();
        if (DBG)
            Log.d(TAG, "mDefaultIsoDepRoute=0x" + Integer.toHexString(mDefaultIsoDepRoute));
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
    }

//    public void overrideDefaultRoute(int defaultRoute) {
//        mOverrideDefaultRoute = defaultRoute;
//        NfcService.getInstance().setIsoDepProtocolRoute(defaultRoute);
//    }

    public void overrideDefaultIsoDepRoute(int isoDepRoute) {
        mOverrideDefaultRoute = isoDepRoute;
        mOverrideDefaultIsoDepRoute = isoDepRoute;
        NfcService.getInstance().setIsoDepProtocolRoute(isoDepRoute);
    }

    public void overrideDefaultOffHostRoute(int offHostRoute) {
        mOverrideDefaultOffHostRoute = offHostRoute;
        NfcService.getInstance().setTechnologyABFRoute(offHostRoute);
    }

    public void recoverOverridedRoutingTable() {
        NfcService.getInstance().setIsoDepProtocolRoute(mDefaultIsoDepRoute);
        NfcService.getInstance().setTechnologyABFRoute(mDefaultOffHostRoute);
        mOverrideDefaultRoute = mOverrideDefaultIsoDepRoute = mOverrideDefaultOffHostRoute
            = ROUTE_UNKNOWN;
    }

    public int getOverrideDefaultRoute() {
        return mOverrideDefaultRoute;
    }

    public int getDefaultRoute() {
        return mDefaultRoute;
    }

    public int getDefaultIsoDepRoute() {
        return mDefaultIsoDepRoute;
    }

    public int getDefaultOffHostRoute() {
        return mDefaultOffHostRoute;
    }

    public byte[] getOffHostRouteUicc() {
        return mOffHostRouteUicc;
    }

    public byte[] getOffHostRouteEse() {
        return mOffHostRouteEse;
    }

    public int getAidMatchingSupport() {
        return mAidMatchingSupport;
    }

    public boolean isRoutingTableOverrided() {
        return mOverrideDefaultIsoDepRoute != ROUTE_UNKNOWN
            || mOverrideDefaultOffHostRoute != ROUTE_UNKNOWN;
    }
}
