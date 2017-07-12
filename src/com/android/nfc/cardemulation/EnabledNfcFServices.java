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

import java.io.FileDescriptor;
import java.io.PrintWriter;

import com.android.nfc.ForegroundUtils;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class EnabledNfcFServices implements com.android.nfc.ForegroundUtils.Callback {
    static final String TAG = "EnabledNfcFCardEmulationServices";
    static final boolean DBG = false;

    final Context mContext;
    final RegisteredNfcFServicesCache mNfcFServiceCache;
    final RegisteredT3tIdentifiersCache mT3tIdentifiersCache;
    final Callback mCallback;
    final ForegroundUtils mForegroundUtils = ForegroundUtils.getInstance();
    final Handler mHandler = new Handler(Looper.getMainLooper());

    final Object mLock = new Object();
    // Variables below synchronized on mLock
    ComponentName mForegroundComponent = null; // The computed enabled foreground component
    ComponentName mForegroundRequested = null; // The component requested to be enabled by fg app
    int mForegroundUid = -1; // The UID of the fg app, or -1 if fg app didn't request

    boolean mComputeFgRequested = false;
    boolean mActivated = false;

    public interface Callback {
        void onEnabledForegroundNfcFServiceChanged(ComponentName service);
    }

    public EnabledNfcFServices(Context context,
            RegisteredNfcFServicesCache nfcFServiceCache,
            RegisteredT3tIdentifiersCache t3tIdentifiersCache, Callback callback) {
        if (DBG) Log.d(TAG, "EnabledNfcFServices");
        mContext = context;
        mNfcFServiceCache = nfcFServiceCache;
        mT3tIdentifiersCache = t3tIdentifiersCache;
        mCallback = callback;
    }

    void computeEnabledForegroundService() {
        if (DBG) Log.d(TAG, "computeEnabledForegroundService");
        ComponentName foregroundRequested = null;
        boolean changed = false;
        synchronized (mLock) {
            if (mActivated) {
                Log.d(TAG, "configuration will be postponed until deactivation");
                mComputeFgRequested = true;
                return;
            }
            mComputeFgRequested = false;
            foregroundRequested = mForegroundRequested;
            if (mForegroundRequested != null &&
                    (mForegroundComponent == null ||
                    !mForegroundRequested.equals(mForegroundComponent))) {
                mForegroundComponent = mForegroundRequested;
                changed = true;
            } else if (mForegroundRequested == null && mForegroundComponent != null){
                mForegroundComponent = mForegroundRequested;
                changed = true;
            }
        }
        // Notify if anything changed
        if (changed) {
            mCallback.onEnabledForegroundNfcFServiceChanged(foregroundRequested);
        }
    }

    public void onServicesUpdated() {
        if (DBG) Log.d(TAG, "onServicesUpdated");
        // If enabled foreground service is set, remove it
        boolean changed = false;
        synchronized (mLock) {
            if (mForegroundComponent != null) {
                Log.d(TAG, "Removing foreground enabled service because of service update.");
                mForegroundRequested = null;
                mForegroundUid = -1;
                changed = true;
            }
        }
        if (changed) {
            computeEnabledForegroundService();
        }
    }

    public boolean registerEnabledForegroundService(ComponentName service, int callingUid) {
        if (DBG) Log.d(TAG, "registerEnabledForegroundService");
        boolean success = false;
        synchronized (mLock) {
            NfcFServiceInfo serviceInfo = mNfcFServiceCache.getService(
                    ActivityManager.getCurrentUser(), service);
            if (serviceInfo == null) {
                return false;
            } else {
                if (serviceInfo.getSystemCode().equalsIgnoreCase("NULL") ||
                        serviceInfo.getNfcid2().equalsIgnoreCase("NULL") ||
                        serviceInfo.getT3tPmm().equalsIgnoreCase("NULL")) {
                    return false;
                }
            }
            if (service.equals(mForegroundRequested)) {
                Log.e(TAG, "The servcie is already requested to the foreground service.");
                return true;
            }
            if (mForegroundUtils.registerUidToBackgroundCallback(this, callingUid)) {
                mForegroundRequested = service;
                mForegroundUid = callingUid;
                success = true;
            } else {
                Log.e(TAG, "Calling UID is not in the foreground, ignorning!");
            }
        }
        if (success) {
            computeEnabledForegroundService();
        }
        return success;
    }

    boolean unregisterForegroundService(int uid) {
        if (DBG) Log.d(TAG, "unregisterForegroundService");
        boolean success = false;
        synchronized (mLock) {
            if (mForegroundUid == uid) {
                mForegroundRequested = null;
                mForegroundUid = -1;
                success = true;
            } // else, other UID in foreground
        }
        if (success) {
            computeEnabledForegroundService();
        }
        return success;
    }

    public boolean unregisteredEnabledForegroundService(int callingUid) {
        if (DBG) Log.d(TAG, "unregisterEnabledForegroundService");
        // Verify the calling UID is in the foreground
        if (mForegroundUtils.isInForeground(callingUid)) {
            return unregisterForegroundService(callingUid);
        } else {
            Log.e(TAG, "Calling UID is not in the foreground, ignorning!");
            return false;
        }
    }

    @Override
    public void onUidToBackground(int uid) {
        if (DBG) Log.d(TAG, "onUidToBackground");
        unregisterForegroundService(uid);
    }

    public void onHostEmulationActivated() {
        if (DBG) Log.d(TAG, "onHostEmulationActivated");
        synchronized (mLock) {
            mActivated = true;
        }
    }

    public void onHostEmulationDeactivated() {
        if (DBG) Log.d(TAG, "onHostEmulationDeactivated");
        boolean needComputeFg = false;
        synchronized (mLock) {
            mActivated = false;
            if (mComputeFgRequested) {
                needComputeFg = true;
            }
        }
        if (needComputeFg) {
            Log.d(TAG, "do postponed configuration");
            computeEnabledForegroundService();
        }
    }

    public void onNfcDisabled() {
        synchronized (mLock) {
            mForegroundComponent = null;
            mForegroundRequested = null;
            mActivated = false;
            mComputeFgRequested = false;
            mForegroundUid = -1;
        }
    }

    public void onUserSwitched(int userId) {
        synchronized (mLock) {
            mForegroundComponent = null;
            mForegroundRequested = null;
            mActivated = false;
            mComputeFgRequested = false;
            mForegroundUid = -1;
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
    }
}
