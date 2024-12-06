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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.nfc.DeviceConfigFacade;
import com.android.nfc.NfcService;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class RoutingOptionManager {
    public final String TAG = "RoutingOptionManager";

    static final boolean DBG = SystemProperties.getBoolean("persist.nfc.debug_enabled", false);

    static final int ROUTE_UNKNOWN = -1;

    static final int ROUTE_DEFAULT = -2;

    public static final String DEVICE_HOST = "DH";
    public static final String SE_PREFIX_SIM = "SIM";
    public static final String SE_PREFIX_ESE = "eSE";

    public static final String PREF_ROUTING_OPTIONS = "RoutingOptionPrefs";
    public static final String KEY_DEFAULT_ROUTE = "default_route";
    public static final String KEY_DEFAULT_ISO_DEP_ROUTE = "default_iso_dep_route";
    public static final String KEY_DEFAULT_OFFHOST_ROUTE = "default_offhost_route";
    public static final String KEY_DEFAULT_SC_ROUTE = "default_sc_route";
    public static final String KEY_AUTO_CHANGE_CAPABLE = "allow_auto_routing_changed";
    Context mContext;
    private SharedPreferences mPrefs;


    int mDefaultRoute;
    int mDefaultIsoDepRoute;
    int mDefaultOffHostRoute;
    int mDefaultFelicaRoute;
    int mDefaultScRoute;
    final byte[] mOffHostRouteUicc;
    final byte[] mOffHostRouteEse;
    final int mAidMatchingSupport;

    int mOverrideDefaultRoute = ROUTE_UNKNOWN;
    int mOverrideDefaultIsoDepRoute = ROUTE_UNKNOWN;
    int mOverrideDefaultOffHostRoute = ROUTE_UNKNOWN;
    int mOverrideDefaultFelicaRoute = ROUTE_UNKNOWN;
    int mOverrideDefaultScRoute = ROUTE_UNKNOWN;

    boolean mIsRoutingTableOverrided = false;

    boolean mIsAutoChangeCapable = true;

    // Look up table for secure element name to route id
    HashMap<String, Integer> mRouteForSecureElement = new HashMap<>();

    // Look up table for route id to secure element name
    HashMap<Integer, String> mSecureElementForRoute = new HashMap<>();


    @VisibleForTesting
    native int doGetDefaultRouteDestination();
    @VisibleForTesting
    native int doGetDefaultIsoDepRouteDestination();
    @VisibleForTesting
    native int doGetDefaultOffHostRouteDestination();
    @VisibleForTesting
    native int doGetDefaultFelicaRouteDestination();
    @VisibleForTesting
    native int doGetDefaultScRouteDestination();
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
        if (DBG) Log.d(TAG, "mDefaultRoute=0x" + Integer.toHexString(mDefaultRoute));
        mDefaultIsoDepRoute = doGetDefaultIsoDepRouteDestination();
        if (DBG) Log.d(TAG, "mDefaultIsoDepRoute=0x" + Integer.toHexString(mDefaultIsoDepRoute));
        mDefaultOffHostRoute = doGetDefaultOffHostRouteDestination();
        if (DBG) Log.d(TAG, "mDefaultOffHostRoute=0x" + Integer.toHexString(mDefaultOffHostRoute));
        mDefaultFelicaRoute = doGetDefaultFelicaRouteDestination();
        if (DBG) Log.d(TAG, "mDefaultFelicaRoute=0x" + Integer.toHexString(mDefaultFelicaRoute));
        mDefaultScRoute = doGetDefaultScRouteDestination();
        if (DBG) Log.d(TAG, "mDefaultScRoute=0x" + Integer.toHexString(mDefaultScRoute));
        mOffHostRouteUicc = doGetOffHostUiccDestination();
        if (DBG) Log.d(TAG, "mOffHostRouteUicc=" + Arrays.toString(mOffHostRouteUicc));
        mOffHostRouteEse = doGetOffHostEseDestination();
        if (DBG) Log.d(TAG, "mOffHostRouteEse=" + Arrays.toString(mOffHostRouteEse));
        mAidMatchingSupport = doGetAidMatchingMode();
        if (DBG) Log.d(TAG, "mAidMatchingSupport=0x" + Integer.toHexString(mAidMatchingSupport));

        createLookUpTable();
    }

    public void overwriteRoutingTable() {
        Log.d(TAG, "overwriteRoutingTable()");
        if (mOverrideDefaultRoute != ROUTE_UNKNOWN) {
            if (mOverrideDefaultRoute == ROUTE_DEFAULT) {
                Log.i(TAG, "overwrite mDefaultRoute with default config value");
                mDefaultRoute = doGetDefaultRouteDestination();
            } else {
                Log.d(TAG, "overwriteRoutingTable() - mDefaultRoute : "
                    + Integer.toHexString(mOverrideDefaultRoute));
                mDefaultRoute = mOverrideDefaultRoute;
            }
            writeRoutingOption(KEY_DEFAULT_ROUTE, getSecureElementForRoute(mDefaultRoute));
        }

        if (mOverrideDefaultIsoDepRoute != ROUTE_UNKNOWN) {
            if (mOverrideDefaultIsoDepRoute == ROUTE_DEFAULT) {
                Log.i(TAG, "overwrite mDefaultIsoDepRoute with default config value");
                mDefaultIsoDepRoute = doGetDefaultIsoDepRouteDestination();
            } else {
                Log.d(TAG, "overwriteRoutingTable() - mDefaultIsoDepRoute : "
                        + Integer.toHexString(mOverrideDefaultIsoDepRoute));
                mDefaultIsoDepRoute = mOverrideDefaultIsoDepRoute;
            }
            writeRoutingOption(
                    KEY_DEFAULT_ISO_DEP_ROUTE, getSecureElementForRoute(mDefaultIsoDepRoute));
        }

        if (mOverrideDefaultOffHostRoute != ROUTE_UNKNOWN) {
            if (mOverrideDefaultOffHostRoute == ROUTE_DEFAULT) {
                Log.i(TAG, "overwrite mDefaultOffHostRoute with default config value");
                mDefaultOffHostRoute = doGetDefaultOffHostRouteDestination();
            } else {
                Log.d(TAG, "overwriteRoutingTable() - mDefaultOffHostRoute : "
                        + Integer.toHexString(mOverrideDefaultOffHostRoute));
                mDefaultOffHostRoute = mOverrideDefaultOffHostRoute;
            }
            writeRoutingOption(
                    KEY_DEFAULT_OFFHOST_ROUTE, getSecureElementForRoute(mDefaultOffHostRoute));
        }

        if (mOverrideDefaultScRoute != ROUTE_UNKNOWN) {
            if (mOverrideDefaultScRoute == ROUTE_DEFAULT) {
                Log.i(TAG, "overwriteRoutingTable() - mDefaultScRoute with default config value");
                mDefaultScRoute = doGetDefaultScRouteDestination();
            } else {
                Log.d(TAG,
                        "overwriteRoutingTable() - mDefaultScRoute : "
                            + Integer.toHexString(mOverrideDefaultScRoute));
                mDefaultScRoute = mOverrideDefaultScRoute;
            }
            writeRoutingOption(
                    KEY_DEFAULT_SC_ROUTE, getSecureElementForRoute(mDefaultScRoute));
        }

        mOverrideDefaultRoute = mOverrideDefaultIsoDepRoute = mOverrideDefaultOffHostRoute =
            mOverrideDefaultScRoute = ROUTE_UNKNOWN;
    }

    public void overrideDefaultRoute(int defaultRoute) {
        mOverrideDefaultRoute = defaultRoute;
    }

    public void overrideDefaultIsoDepRoute(int isoDepRoute) {
        mOverrideDefaultIsoDepRoute = isoDepRoute;
        NfcService.getInstance().setIsoDepProtocolRoute(isoDepRoute);
    }

    public void overrideDefaultOffHostRoute(int offHostRoute) {
        mOverrideDefaultOffHostRoute = offHostRoute;
        mOverrideDefaultFelicaRoute = offHostRoute;
        NfcService.getInstance().setTechnologyABFRoute(offHostRoute, offHostRoute);
    }

    public void overrideDefaultScRoute(int scRoute) {
        mOverrideDefaultScRoute = scRoute;
        NfcService.getInstance().setSystemCodeRoute(scRoute);
    }

    public void recoverOverridedRoutingTable() {
        NfcService.getInstance().setIsoDepProtocolRoute(mDefaultIsoDepRoute);
        NfcService.getInstance().setTechnologyABFRoute(mDefaultOffHostRoute, mDefaultFelicaRoute);
        mOverrideDefaultRoute = mOverrideDefaultIsoDepRoute = mOverrideDefaultOffHostRoute =
            mOverrideDefaultFelicaRoute = ROUTE_UNKNOWN;
    }

    public int getOverrideDefaultRoute() {
        return mOverrideDefaultRoute;
    }

    public int getDefaultRoute() {
        return mDefaultRoute;
    }

    public int getOverrideDefaultIsoDepRoute() { return mOverrideDefaultIsoDepRoute;}

    public int getDefaultIsoDepRoute() {
        return mDefaultIsoDepRoute;
    }

    public int getOverrideDefaultOffHostRoute() {
        return mOverrideDefaultOffHostRoute;
    }
    public int getDefaultOffHostRoute() {
        return mDefaultOffHostRoute;
    }

    public int getDefaultFelicaRoute() {
        return mDefaultFelicaRoute;
    }

    public int getOverrideDefaultFelicaRoute() {
        return mOverrideDefaultFelicaRoute;
    }
    public int getOverrideDefaultScRoute() {
        return mOverrideDefaultScRoute;
    }
    public int getDefaultScRoute() {
        return mDefaultScRoute;
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
        return mOverrideDefaultRoute != ROUTE_UNKNOWN
            || mOverrideDefaultIsoDepRoute != ROUTE_UNKNOWN
            || mOverrideDefaultOffHostRoute != ROUTE_UNKNOWN
            || mOverrideDefaultFelicaRoute != ROUTE_UNKNOWN
            || mOverrideDefaultScRoute != ROUTE_UNKNOWN;
    }

    private void createLookUpTable() {
        mRouteForSecureElement.putIfAbsent(DEVICE_HOST, 0);
        mSecureElementForRoute.put(0, DEVICE_HOST);

        mRouteForSecureElement.putIfAbsent("UNKNOWN", ROUTE_UNKNOWN);
        mSecureElementForRoute.put(ROUTE_UNKNOWN, "UNKNOWN");

        mRouteForSecureElement.putIfAbsent("default", ROUTE_DEFAULT);
        mSecureElementForRoute.put(ROUTE_DEFAULT, "default");

        addOrUpdateTableItems(SE_PREFIX_SIM, mOffHostRouteUicc);
        addOrUpdateTableItems(SE_PREFIX_ESE, mOffHostRouteEse);
    }

    boolean isRoutingTableOverwrittenOrOverlaid(
            DeviceConfigFacade deviceConfigFacade, SharedPreferences prefs) {
        return !TextUtils.isEmpty(deviceConfigFacade.getDefaultRoute())
                || !TextUtils.isEmpty(deviceConfigFacade.getDefaultIsoDepRoute())
                || !TextUtils.isEmpty(deviceConfigFacade.getDefaultOffHostRoute())
                || !TextUtils.isEmpty(deviceConfigFacade.getDefaultScRoute())
                || !prefs.getAll().isEmpty();
    }

    public void readRoutingOptionsFromPrefs(
            Context context, DeviceConfigFacade deviceConfigFacade) {
        Log.d(TAG, "readRoutingOptions with Context");
        if (mPrefs == null) {
            Log.d(TAG, "create mPrefs in readRoutingOptions");
            mContext = context;
            mPrefs = context.getSharedPreferences(PREF_ROUTING_OPTIONS, Context.MODE_PRIVATE);
        }

        // If the OEM does not set default routes in the overlay and if no app has overwritten
        // the routing table using `overwriteRoutingTable`, skip this preference reading.
        if (!isRoutingTableOverwrittenOrOverlaid(deviceConfigFacade, mPrefs)) {
            Log.d(TAG, "Routing table not overwritten or overlaid");
            return;
        }

        // read default route
        if (!mPrefs.contains(KEY_DEFAULT_ROUTE)) {
            writeRoutingOption(KEY_DEFAULT_ROUTE, deviceConfigFacade.getDefaultRoute());
        }
        mDefaultRoute = getRouteForSecureElement(mPrefs.getString(KEY_DEFAULT_ROUTE, null));

        // read default iso dep route
        if (!mPrefs.contains(KEY_DEFAULT_ISO_DEP_ROUTE)) {
            writeRoutingOption(
                KEY_DEFAULT_ISO_DEP_ROUTE, deviceConfigFacade.getDefaultIsoDepRoute());
        }
        mDefaultIsoDepRoute =
            getRouteForSecureElement(mPrefs.getString(KEY_DEFAULT_ISO_DEP_ROUTE, null));

        // read default offhost route
        if (!mPrefs.contains(KEY_DEFAULT_OFFHOST_ROUTE)) {
            writeRoutingOption(
                KEY_DEFAULT_OFFHOST_ROUTE, deviceConfigFacade.getDefaultOffHostRoute());
        }
        mDefaultOffHostRoute =
            getRouteForSecureElement(mPrefs.getString(KEY_DEFAULT_OFFHOST_ROUTE, null));

        // read default system code route
        if (!mPrefs.contains(KEY_DEFAULT_SC_ROUTE)) {
            writeRoutingOption(
                KEY_DEFAULT_SC_ROUTE, deviceConfigFacade.getDefaultScRoute());
        }
        mDefaultScRoute =
            getRouteForSecureElement(mPrefs.getString(KEY_DEFAULT_SC_ROUTE, null));

        // read auto change capable
        if (!mPrefs.contains(KEY_AUTO_CHANGE_CAPABLE)) {
            writeRoutingOption(KEY_AUTO_CHANGE_CAPABLE, true);
        }
        mIsAutoChangeCapable = mPrefs.getBoolean(KEY_AUTO_CHANGE_CAPABLE, true);
        Log.d(TAG, "ReadOptions - " + toString());
    }

    public void setAutoChangeStatus(boolean status) {
        mIsAutoChangeCapable = status;
    }

    public boolean isAutoChangeEnabled() { return mIsAutoChangeCapable;}

    private void writeRoutingOption(String key, String name) {
        mPrefs.edit().putString(key, name).apply();
    }

    private void writeRoutingOption(String key, boolean value) {
        mPrefs.edit().putBoolean(key, value).apply();
    }

    public int getRouteForSecureElement(String se) {
        return Optional.ofNullable(mRouteForSecureElement.get(se)).orElseGet(()->0x00);
    }

    public String getSecureElementForRoute(int route) {
        return Optional.ofNullable(mSecureElementForRoute.get(route)).orElseGet(()->DEVICE_HOST);
    }


    private void addOrUpdateTableItems(String prefix, byte[] routes) {
        if (routes != null && routes.length != 0) {
            for (int index = 1; index <= routes.length; index++) {
                int route = routes[index - 1] & 0xFF;
                String name = prefix + index;
                mRouteForSecureElement.putIfAbsent(name, route);
                mSecureElementForRoute.putIfAbsent(route, name);
            }
        }

        for (Map.Entry<String, Integer> entry : mRouteForSecureElement.entrySet()) {
            Log.d(TAG, "addOrUpdateTableItems() - route: " + entry.getKey() + ", nfceeId: "
                    + Integer.toHexString(entry.getValue()));
        }
        for (Map.Entry<Integer, String> entry : mSecureElementForRoute.entrySet()) {
            Log.d(TAG, "addOrUpdateTableItems() - nfceeId: "
                    + Integer.toHexString(entry.getKey()) + ", route: " + entry.getValue());
        }
    }
}
