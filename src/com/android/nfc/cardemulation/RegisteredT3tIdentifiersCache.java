/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class RegisteredT3tIdentifiersCache {
    static final String TAG = "RegisteredT3tIdentifiersCache";

    static final boolean DBG = false;

    // All NFC-F services that have registered
    List<NfcFServiceInfo> mServices = new ArrayList<NfcFServiceInfo>();

    final HashMap<String, NfcFServiceInfo> mForegroundT3tIdentifiersCache =
            new HashMap<String, NfcFServiceInfo>();

    ComponentName mEnabledForegroundService;

    final class T3tIdentifier {
        public final String systemCode;
        public final String nfcid2;
        public final String t3tPmm;

        T3tIdentifier(String systemCode, String nfcid2, String t3tPmm) {
            this.systemCode = systemCode;
            this.nfcid2 = nfcid2;
            this.t3tPmm = t3tPmm;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            T3tIdentifier that = (T3tIdentifier) o;
            if (!systemCode.equalsIgnoreCase(that.systemCode)) return false;
            if (!nfcid2.equalsIgnoreCase(that.nfcid2)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = systemCode.hashCode();
            result = 31 * result + nfcid2.hashCode();
            return result;
        }
    }

    final Context mContext;
    final SystemCodeRoutingManager mRoutingManager;

    final Object mLock = new Object();

    boolean mNfcEnabled = false;

    public RegisteredT3tIdentifiersCache(Context context) {
        Log.d(TAG, "RegisteredT3tIdentifiersCache");
        mContext = context;
        mRoutingManager = new SystemCodeRoutingManager();
    }

    public NfcFServiceInfo resolveNfcid2(String nfcid2) {
        synchronized (mLock) {
            if (DBG) Log.d(TAG, "resolveNfcid2: resolving NFCID " + nfcid2);
            NfcFServiceInfo resolveInfo;
            resolveInfo = mForegroundT3tIdentifiersCache.get(nfcid2);
            Log.d(TAG,
                    "Resolved to: " + (resolveInfo == null ? "null" : resolveInfo.toString()));
            return resolveInfo;
        }
    }

    void generateForegroundT3tIdentifiersCacheLocked() {
        if (DBG) Log.d(TAG, "generateForegroundT3tIdentifiersCacheLocked");
        mForegroundT3tIdentifiersCache.clear();
        if (mEnabledForegroundService != null) {
            for (NfcFServiceInfo service : mServices) {
                if (mEnabledForegroundService.equals(service.getComponent())) {
                    if (!service.getSystemCode().equalsIgnoreCase("NULL") &&
                            !service.getNfcid2().equalsIgnoreCase("NULL")) {
                        mForegroundT3tIdentifiersCache.put(service.getNfcid2(), service);
                    }
                    break;
                }
            }
        }

        if (DBG) {
            Log.d(TAG, "mForegroundT3tIdentifiersCache: size=" +
                    mForegroundT3tIdentifiersCache.size());
            for (Map.Entry<String, NfcFServiceInfo> entry :
                    mForegroundT3tIdentifiersCache.entrySet()) {
                Log.d(TAG, "    " + entry.getKey() +
                        "/" + entry.getValue().getComponent().toString());
            }
        }

        updateRoutingLocked();
    }

    void updateRoutingLocked() {
        if (DBG) Log.d(TAG, "updateRoutingLocked");
        if (!mNfcEnabled) {
            Log.d(TAG, "Not updating routing table because NFC is off.");
            return;
        }
        List<T3tIdentifier> t3tIdentifiers = new ArrayList<T3tIdentifier>();
        Iterator<Map.Entry<String, NfcFServiceInfo>> it;
        // Register foreground service
        it = mForegroundT3tIdentifiersCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, NfcFServiceInfo> entry =
                    (Map.Entry<String, NfcFServiceInfo>) it.next();
            t3tIdentifiers.add(new T3tIdentifier(
                    entry.getValue().getSystemCode(), entry.getValue().getNfcid2(), entry.getValue().getT3tPmm()));
        }
        mRoutingManager.configureRouting(t3tIdentifiers);
    }

    public void onServicesUpdated(int userId, List<NfcFServiceInfo> services) {
        if (DBG) Log.d(TAG, "onServicesUpdated");
        synchronized (mLock) {
            if (ActivityManager.getCurrentUser() == userId) {
                // Rebuild our internal data-structures
                mServices = services;
            } else {
                Log.d(TAG, "Ignoring update because it's not for the current user.");
            }
        }
    }

    public void onEnabledForegroundNfcFServiceChanged(ComponentName component) {
        if (DBG) Log.d(TAG, "Enabled foreground service changed.");
        synchronized (mLock) {
            if (component != null) {
                if (mEnabledForegroundService != null) {
                    return;
                }
                mEnabledForegroundService = component;
            } else {
                if (mEnabledForegroundService == null) {
                    return;
                }
                mEnabledForegroundService = null;
            }
            generateForegroundT3tIdentifiersCacheLocked();
        }
    }

    public void onNfcEnabled() {
        synchronized (mLock) {
            mNfcEnabled = true;
        }
    }

    public void onNfcDisabled() {
        synchronized (mLock) {
            mNfcEnabled = false;
            mForegroundT3tIdentifiersCache.clear();
            mEnabledForegroundService = null;
        }
        mRoutingManager.onNfccRoutingTableCleared();
    }

    public void onUserSwitched() {
        synchronized (mLock) {
            mForegroundT3tIdentifiersCache.clear();
            updateRoutingLocked();
            mEnabledForegroundService = null;
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("T3T Identifier cache entries: ");
        for (Map.Entry<String, NfcFServiceInfo> entry : mForegroundT3tIdentifiersCache.entrySet()) {
            pw.println("    NFCID2: " + entry.getKey());
            pw.println("    NfcFServiceInfo: ");
            entry.getValue().dump(fd, pw, args);
        }
        pw.println("");
        mRoutingManager.dump(fd, pw, args);
        pw.println("");
    }
}
