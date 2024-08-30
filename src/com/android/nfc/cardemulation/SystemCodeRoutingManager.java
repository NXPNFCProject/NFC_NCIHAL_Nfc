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
*  Copyright 2020-2021 NXP
*
******************************************************************************/
package com.android.nfc.cardemulation;

import android.sysprop.NfcProperties;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.nfc.NfcService;
import com.android.nfc.cardemulation.RegisteredT3tIdentifiersCache.T3tIdentifier;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class SystemCodeRoutingManager {
    static final String TAG = "SystemCodeRoutingManager";

    static final boolean DBG = NfcProperties.debug_enabled().orElse(true);

    final Object mLock = new Object();

    List<T3tIdentifier> mConfiguredT3tIdentifiers =
            new ArrayList<T3tIdentifier>();

    public boolean configureRouting(List<T3tIdentifier> t3tIdentifiers) {
        if (DBG) Log.d(TAG, "configureRouting");
        List<T3tIdentifier> toBeAdded = new ArrayList<T3tIdentifier>();
        List<T3tIdentifier> toBeRemoved = new ArrayList<T3tIdentifier>();
        synchronized (mLock) {
            for (T3tIdentifier t3tIdentifier : t3tIdentifiers) {
                if (!mConfiguredT3tIdentifiers.contains(t3tIdentifier)) {
                    toBeAdded.add(t3tIdentifier);
                }
            }
            for (T3tIdentifier t3tIdentifier : mConfiguredT3tIdentifiers) {
                if (!t3tIdentifiers.contains(t3tIdentifier)) {
                    toBeRemoved.add(t3tIdentifier);
                }
            }
            if (toBeAdded.size() <= 0 && toBeRemoved.size() <= 0) {
                Log.d(TAG, "Routing table unchanged, not updating");
                return false;
            }
            // Update internal structures
            for (T3tIdentifier t3tIdentifier : toBeRemoved) {
                if (DBG) Log.d(TAG, "deregisterNfcFSystemCodeonDh:");
                NfcService.getInstance().deregisterT3tIdentifier(
                        t3tIdentifier.systemCode, t3tIdentifier.nfcid2, t3tIdentifier.t3tPmm);
            }
            for (T3tIdentifier t3tIdentifier : toBeAdded) {
                if (DBG) Log.d(TAG, "registerNfcFSystemCodeonDh:");
                NfcService.getInstance().registerT3tIdentifier(
                        t3tIdentifier.systemCode, t3tIdentifier.nfcid2 , t3tIdentifier.t3tPmm);
            }
            if (DBG) {
                Log.d(TAG, "(Before) mConfiguredT3tIdentifiers: size=" +
                        mConfiguredT3tIdentifiers.size());
                for (T3tIdentifier t3tIdentifier : mConfiguredT3tIdentifiers) {
                    Log.d(TAG, "    " + t3tIdentifier.systemCode +
                            "/" + t3tIdentifier.t3tPmm);
                }
                Log.d(TAG, "(After) mConfiguredT3tIdentifiers: size=" +
                        t3tIdentifiers.size());
                for (T3tIdentifier t3tIdentifier : t3tIdentifiers) {
                    Log.d(TAG, "    " + t3tIdentifier.systemCode +
                            "/" + t3tIdentifier.nfcid2 +
                            "/" + t3tIdentifier.t3tPmm);
                }
            }
            mConfiguredT3tIdentifiers = t3tIdentifiers;
        }

        return true;
    }

    /**
     * This notifies that the SystemCode routing table in the controller
     * has been cleared (usually due to NFC being turned off).
     */
    public void onNfccRoutingTableCleared() {
        // The routing table in the controller was cleared
        // To stay in sync, clear our own tables.
        synchronized (mLock) {
            if (DBG) Log.d(TAG, "onNfccRoutingTableCleared");
            NfcService.getInstance().clearT3tIdentifiersCache();
            mConfiguredT3tIdentifiers.clear();
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("HCE-F routing table:");
        synchronized (mLock) {
            for (T3tIdentifier t3tIdentifier : mConfiguredT3tIdentifiers) {
                pw.println("    " + t3tIdentifier.systemCode +
                        "/" + t3tIdentifier.nfcid2);
            }
        }
    }

    /**
     * Dump debugging information as a SystemCodeRoutingManagerProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/card_emulation.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    void dumpDebug(ProtoOutputStream proto) {
        synchronized (mLock) {
            for (T3tIdentifier t3tIdentifier : mConfiguredT3tIdentifiers) {
                long token = proto.start(SystemCodeRoutingManagerProto.T3T_IDENTIFIERS);
                proto.write(SystemCodeRoutingManagerProto.T3tIdentifier.SYSTEM_CODE,
                        t3tIdentifier.systemCode);
                proto.write(SystemCodeRoutingManagerProto.T3tIdentifier.NFCID2,
                        t3tIdentifier.nfcid2);
                proto.end(token);
            }
        }
    }
}
