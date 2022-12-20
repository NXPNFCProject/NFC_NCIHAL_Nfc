/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.nfc;

import android.app.ActivityManager;
import android.os.SystemProperties;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import java.util.ArrayList;
import java.util.List;

public class ForegroundUtils implements ActivityManager.OnUidImportanceListener {
    static final boolean DBG = SystemProperties.getBoolean("persist.nfc.debug_enabled", false);;
    private final String TAG = "ForegroundUtils";
    private final ActivityManager mActivityManager;

    private final Object mLock = new Object();
    // We need to keep track of the individual PIDs per UID,
    // since a single UID may have multiple processes running
    // that transition into foreground/background state.
    private final SparseArray<SparseBooleanArray> mForegroundUidPids =
            new SparseArray<SparseBooleanArray>();
    private final SparseArray<List<Callback>> mBackgroundCallbacks =
            new SparseArray<List<Callback>>();

    private final SparseBooleanArray mForegroundUids = new SparseBooleanArray();

    private static class Singleton {
        private static ForegroundUtils sInstance = null;
    }

    private ForegroundUtils(ActivityManager am) {
        mActivityManager = am;
        try {
            mActivityManager.addOnUidImportanceListener(this,
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        } catch (Exception e) {
            // Should not happen!
            Log.e(TAG, "ForegroundUtils: could not register UidImportanceListener");
        }
    }

    public interface Callback {
        void onUidToBackground(int uid);
    }

    /**
     * Get an instance of the ForegroundUtils sinleton
     *
     * @param am The ActivityManager instance for initialization
     * @return the instance
     */
    public static ForegroundUtils getInstance(ActivityManager am) {
        if (Singleton.sInstance == null) {
            Singleton.sInstance = new ForegroundUtils(am);
        }
        return Singleton.sInstance;
    }

    /**
     * Checks whether the specified UID has any activities running in the foreground,
     * and if it does, registers a callback for when that UID no longer has any foreground
     * activities. This is done atomically, so callers can be ensured that they will
     * get a callback if this method returns true.
     *
     * @param callback Callback to be called
     * @param uid The UID to be checked
     * @return true when the UID has an Activity in the foreground and the callback
     * , false otherwise
     */
    public boolean registerUidToBackgroundCallback(Callback callback, int uid) {
        synchronized (mLock) {
            if (!isInForegroundLocked(uid)) {
                return false;
            }
            // This uid is in the foreground; register callback for when it moves
            // into the background.
            List<Callback> callbacks = mBackgroundCallbacks.get(uid, new ArrayList<Callback>());
            callbacks.add(callback);
            mBackgroundCallbacks.put(uid, callbacks);
            return true;
        }
    }

    /**
     * @param uid The UID to be checked
     * @return whether the UID has any activities running in the foreground
     */
    public boolean isInForeground(int uid) {
        synchronized (mLock) {
            return isInForegroundLocked(uid);
        }
    }

    /**
     * @return a list of UIDs currently in the foreground, or an empty list
     *         if none are found.
     */
    public List<Integer> getForegroundUids() {
        ArrayList<Integer> uids = new ArrayList<Integer>(mForegroundUids.size());
        synchronized (mLock) {
            for (int i = 0; i < mForegroundUids.size(); i++) {
                if (mForegroundUids.valueAt(i)) {
                    uids.add(mForegroundUids.keyAt(i));
                }
            }
        }
        return uids;
    }

    private boolean isInForegroundLocked(int uid) {
        if (mForegroundUids.get(uid)) {
            return true;
        }
        if (DBG) Log.d(TAG, "Checking UID:" + Integer.toString(uid));
        // If the onForegroundActivitiesChanged() has not yet been called,
        // check whether the UID is in an active state to use the NFC.
        return (mActivityManager.getUidImportance(uid)
                == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
    }

    private void handleUidToBackground(int uid) {
        ArrayList<Callback> pendingCallbacks = null;
        synchronized (mLock) {
            List<Callback> callbacks = mBackgroundCallbacks.get(uid);
            if (callbacks != null) {
                pendingCallbacks = new ArrayList<Callback>(callbacks);
                // Only call them once
                mBackgroundCallbacks.remove(uid);
            }
        }
        // Release lock for callbacks
        if (pendingCallbacks != null) {
            for (Callback callback : pendingCallbacks) {
                callback.onUidToBackground(uid);
            }
        }
    }

    @Override
    public void onUidImportance(int uid, int importance) {
        boolean uidToBackground = false;
        synchronized (mLock) {
            if (importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) {
                mForegroundUids.delete(uid);
                mBackgroundCallbacks.remove(uid);
                if (DBG) Log.d(TAG, "UID: " + Integer.toString(uid) + " deleted.");
                return;
            }
            if (importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                mForegroundUids.put(uid, true);
            } else {
                if (mForegroundUids.get(uid)) {
                    uidToBackground = true;
                    mForegroundUids.put(uid, false);
                }
            }
        }
        if (uidToBackground) {
            handleUidToBackground(uid);
        }
        if (DBG) {
            Log.d(TAG, "Foreground UID status:");
            synchronized (mLock) {
                for (int j = 0; j < mForegroundUids.size(); j++) {
                    Log.d(TAG, "UID: " + Integer.toString(mForegroundUids.keyAt(j))
                            + " is in foreground: " + Boolean.toString(mForegroundUids.valueAt(j)));
                }
            }
        }
    }
}
