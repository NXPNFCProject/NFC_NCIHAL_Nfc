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
*  Copyright 2019-2021 NXP
*
******************************************************************************/

package com.android.nfc.cardemulation;

import android.content.ComponentName;
import android.content.Context;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.sysprop.NfcProperties;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class RegisteredT3tIdentifiersCache {
    static final String TAG = "RegisteredT3tIdentifiersCache";

    static final boolean DBG = NfcProperties.debug_enabled().orElse(true);

    // All NFC-F services that have registered
    final Map<Integer, List<NfcFServiceInfo>> mUserNfcFServiceInfo =
            new HashMap<Integer, List<NfcFServiceInfo>>();

    final HashMap<String, NfcFServiceInfo> mForegroundT3tIdentifiersCache =
            new HashMap<String, NfcFServiceInfo>();

    ComponentName mEnabledForegroundService;
    int mEnabledForegroundServiceUserId = -1;

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

    void generateUserNfcFServiceInfoLocked(int userId, List<NfcFServiceInfo> services) {
        mUserNfcFServiceInfo.put(userId, services);
    }

    private int getProfileParentId(int userId) {
        UserManager um = mContext.createContextAsUser(
                UserHandle.of(userId), /*flags=*/0)
                .getSystemService(UserManager.class);
        UserHandle uh = um.getProfileParent(UserHandle.of(userId));
        return uh == null ? userId : uh.getIdentifier();
    }

    void generateForegroundT3tIdentifiersCacheLocked() {
        if (DBG) Log.d(TAG, "generateForegroundT3tIdentifiersCacheLocked");
        mForegroundT3tIdentifiersCache.clear();
        if (mEnabledForegroundService != null) {
            for (NfcFServiceInfo service :
                    mUserNfcFServiceInfo.get(mEnabledForegroundServiceUserId)) {
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

        updateRoutingLocked(false);
    }

    void updateRoutingLocked(boolean force) {
        if (DBG) Log.d(TAG, "updateRoutingLocked");
        if (!mNfcEnabled) {
            Log.d(TAG, "Not updating routing table because NFC is off.");
            return;
        }

        List<T3tIdentifier> t3tIdentifiers = new ArrayList<T3tIdentifier>();

        // Sending an empty table will de-register all entries
        if (force) {
            mRoutingManager.configureRouting(t3tIdentifiers);
        }
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

    public void onSecureNfcToggled() {
        synchronized(mLock) {
            updateRoutingLocked(true);
      }
    }

    public void onServicesUpdated(int userId, List<NfcFServiceInfo> services) {
        if (DBG) Log.d(TAG, "onServicesUpdated");
        synchronized (mLock) {
            mUserNfcFServiceInfo.put(userId, services);
        }
    }

    /**
     * Enabled Foreground NfcF service changed
     */
    public void onEnabledForegroundNfcFServiceChanged(int userId, ComponentName component) {
        if (DBG) Log.d(TAG, "Enabled foreground service changed.");
        synchronized (mLock) {
            if (component != null) {
                if (mEnabledForegroundService != null
                        && mEnabledForegroundServiceUserId == userId) {
                    return;
                }
                mEnabledForegroundService = component;
                mEnabledForegroundServiceUserId = userId;
            } else {
                if (mEnabledForegroundService == null) {
                    return;
                }
                mEnabledForegroundService = null;
                mEnabledForegroundServiceUserId = -1;
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
            mEnabledForegroundServiceUserId = -1;
        }
        mRoutingManager.onNfccRoutingTableCleared();
    }

    public void onUserSwitched() {
        synchronized (mLock) {
            mForegroundT3tIdentifiersCache.clear();
            updateRoutingLocked(false);
            mEnabledForegroundService = null;
            mEnabledForegroundServiceUserId = -1;
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("T3T Identifier cache entries: ");
        ParcelFileDescriptor pFd;
        try {
            pFd = ParcelFileDescriptor.dup(fd);
            for (Map.Entry<String, NfcFServiceInfo> entry
                    : mForegroundT3tIdentifiersCache.entrySet()) {
                pw.println("    NFCID2: " + entry.getKey());
                pw.println("    NfcFServiceInfo: ");
                entry.getValue().dump(pFd, pw, args);
            }
            pw.println("");
            mRoutingManager.dump(fd, pw, args);
            pw.println("");
            pFd.close();
        } catch (IOException e) {
            pw.println("Failed to dump T3T idenitifier cache entries: " + e);
        }
    }

    /**
     * Dump debugging information as a RegisteredT3tIdentifiersCacheProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/card_emulation.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    void dumpDebug(ProtoOutputStream proto) {
        for (NfcFServiceInfo serviceInfo : mForegroundT3tIdentifiersCache.values()) {
            long token = proto.start(
                    RegisteredT3tIdentifiersCacheProto.T3T_IDENTIFIER_CACHE_ENTRIES);
            serviceInfo.dumpDebug(proto);
            proto.end(token);
        }
        long token = proto.start(RegisteredT3tIdentifiersCacheProto.ROUTING_MANAGER);
        mRoutingManager.dumpDebug(proto);
        proto.end(token);
    }
}
