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
*  Copyright 2020-2022 NXP
*
******************************************************************************/
package com.android.nfc.cardemulation;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.nfc.Constants;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.Utils;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.flags.Flags;

import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.sysprop.NfcProperties;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.nfc.ForegroundUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * This class keeps track of what HCE/SE-based services are
 * preferred by the user. It currently has 3 inputs:
 * 1) The default set in tap&pay menu for payment category
 * 2) An app in the foreground asking for a specific
 *    service for a specific category
 * 3) If we had to disambiguate a previous tap (because no
 *    preferred service was there), we need to temporarily
 *    store the user's choice for the next tap.
 *
 * This class keeps track of all 3 inputs, and computes a new
 * preferred services as needed. It then passes this service
 * (if it changed) through a callback, which allows other components
 * to adapt as necessary (ie the AID cache can update its AID
 * mappings and the routing table).
 */
public class PreferredServices implements com.android.nfc.ForegroundUtils.Callback {
    static final String TAG = "PreferredCardEmulationServices";
    static final boolean DBG = NfcProperties.debug_enabled().orElse(true);
    static final Uri paymentDefaultUri = Settings.Secure.getUriFor(
            Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT);
    static final Uri paymentForegroundUri = Settings.Secure.getUriFor(
            Constants.SETTINGS_SECURE_NFC_PAYMENT_FOREGROUND);

    final SettingsObserver mSettingsObserver;
    final Context mContext;
    final WalletRoleObserver mWalletRoleObserver;
    final RegisteredServicesCache mServiceCache;
    final RegisteredAidCache mAidCache;
    final Callback mCallback;
    final ForegroundUtils mForegroundUtils;
    final Handler mHandler = new Handler(Looper.getMainLooper());

    final class PaymentDefaults {
        boolean preferForeground; // The current selection mode for this category
        ComponentName settingsDefault; // The component preferred in settings (eg Tap&Pay)
        ComponentName currentPreferred; // The computed preferred component
        UserHandle mUserHandle;
    }

    final Object mLock = new Object();
    // Variables below synchronized on mLock
    PaymentDefaults mPaymentDefaults = new PaymentDefaults();

    ComponentName mForegroundRequested; // The component preferred by fg app
    int mForegroundUid; // The UID of the fg app, or -1 if fg app didn't request

    ComponentName mNextTapDefault; // The component preferred by active disambig dialog
    int mNextTapDefaultUserId;
    boolean mClearNextTapDefault = false; // Set when the next tap default must be cleared

    ComponentName mForegroundCurrent; // The currently computed foreground component
    int mForegroundCurrentUid; // The UID of the currently computed foreground component

    ComponentName mDefaultWalletHolderPaymentService;

    int mUserIdDefaultWalletHolder;

    public interface Callback {
        /**
         * Notify when preferred payment service is changed
         */
        void onPreferredPaymentServiceChanged(int userId, ComponentName service);
        /**
         * Notify when preferred foreground service is changed
         */
        void onPreferredForegroundServiceChanged(int userId, ComponentName service);
    }

    public PreferredServices(Context context, RegisteredServicesCache serviceCache,
            RegisteredAidCache aidCache, WalletRoleObserver walletRoleObserver,
            Callback callback) {
        mContext = context;
        mWalletRoleObserver = walletRoleObserver;
        mForegroundUtils = ForegroundUtils.getInstance(
                context.getSystemService(ActivityManager.class));
        mServiceCache = serviceCache;
        mAidCache = aidCache;
        mCallback = callback;
        mSettingsObserver = new SettingsObserver(mHandler);
        mContext.getContentResolver().registerContentObserverAsUser(
                paymentDefaultUri,
                true, mSettingsObserver, UserHandle.ALL);

        mContext.getContentResolver().registerContentObserverAsUser(
                paymentForegroundUri,
                true, mSettingsObserver, UserHandle.ALL);

        // Load current settings defaults for payments
        loadDefaultsFromSettings(ActivityManager.getCurrentUser(), false);
    }

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            // Do it just for the current user. If it was in fact
            // a change made for another user, we'll sync it down
            // on user switch.
            int currentUser = ActivityManager.getCurrentUser();
            loadDefaultsFromSettings(currentUser, false);
        }
    };

    @TargetApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public void onWalletRoleHolderChanged(String defaultWalletHolderPackageName, int userId) {
        if (defaultWalletHolderPackageName == null) {
            mDefaultWalletHolderPaymentService = null;
            mCallback.onPreferredPaymentServiceChanged(userId, null);
            return;
        }
        List<ApduServiceInfo> serviceInfos = mServiceCache.getInstalledServices(userId);
        List<ComponentName> roleHolderPaymentServices = new ArrayList<>();
        int servicesCount = serviceInfos.size();
        for(int i = 0; i < servicesCount; i++) {
            ApduServiceInfo serviceInfo = serviceInfos.get(i);
            ComponentName componentName = serviceInfo.getComponent();
            if (componentName.getPackageName()
                    .equals(defaultWalletHolderPackageName)) {
                List<String> aids = serviceInfo.getAids();
                int aidsCount = aids.size();
                for (int j = 0; j < aidsCount; j++) {
                    String aid = aids.get(j);
                    if (serviceInfo.getCategoryForAid(aid)
                            .equals(CardEmulation.CATEGORY_PAYMENT)) {
                        roleHolderPaymentServices.add(componentName);
                        break;
                    }
                }
            }
        }
        mUserIdDefaultWalletHolder = userId;
        ComponentName candidate = !roleHolderPaymentServices.isEmpty()
                ? roleHolderPaymentServices.get(0) : null;
        if (!Objects.equals(candidate, mDefaultWalletHolderPaymentService)) {
            mCallback.onPreferredPaymentServiceChanged(userId, candidate);
        }
        mDefaultWalletHolderPaymentService = candidate;
    }

    void loadDefaultsFromSettings(int userId, boolean force) {
        boolean paymentDefaultChanged = false;
        boolean paymentPreferForegroundChanged = false;
        // Load current payment default from settings
        UserHandle currentUser = UserHandle.of(ActivityManager.getCurrentUser());
        UserManager um = mContext.createContextAsUser(currentUser, /*flags=*/0)
                .getSystemService(UserManager.class);
        List<UserHandle> userHandles = um.getEnabledProfiles();

        String name = null;
        String newDefaultName = null;
        UserHandle newUser = null;
        // search for default payment setting within enabled profiles
        for (UserHandle uh : userHandles) {
            name = Settings.Secure.getString(
                    mContext.createContextAsUser(uh, 0).getContentResolver(),
                    Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT);
            if (name != null) {
                newUser = uh;
                newDefaultName = name;
            }
            if (uh.getIdentifier() == userId) {
                currentUser = uh;
            }
        }
        if (currentUser == null) {
            Log.e(TAG, "NULL/ Error fetching currentUser info");
            return;
        }
        // no default payment setting in all profles
        if (newUser == null) {
            newUser = currentUser;
        }
        ComponentName newDefault = newDefaultName != null
                ? ComponentName.unflattenFromString(newDefaultName) : null;
        boolean preferForeground = false;
        try {
            // get the setting from the main user instead of from the user profiles.
            preferForeground = mWalletRoleObserver.isWalletRoleFeatureEnabled()
                    || Settings.Secure.getInt(mContext
                            .createContextAsUser(currentUser, 0).getContentResolver(),
                    Constants.SETTINGS_SECURE_NFC_PAYMENT_FOREGROUND) != 0;
        } catch (SettingNotFoundException e) {
        }
        synchronized (mLock) {
            paymentPreferForegroundChanged = (preferForeground != mPaymentDefaults.preferForeground);
            mPaymentDefaults.preferForeground = preferForeground;

            mPaymentDefaults.settingsDefault = newDefault;
            if (newDefault != null && (!newDefault.equals(mPaymentDefaults.currentPreferred)
                    || ((newUser!=null)
                        && mPaymentDefaults.mUserHandle.getIdentifier()
                        != newUser.getIdentifier()))) {
                paymentDefaultChanged = true;
                mPaymentDefaults.currentPreferred = newDefault;
                mPaymentDefaults.mUserHandle = newUser;
            } else if (newDefault == null && mPaymentDefaults.currentPreferred != null) {
                paymentDefaultChanged = true;
                mPaymentDefaults.currentPreferred = newDefault;
                mPaymentDefaults.mUserHandle = newUser;
            } else {
                // Same default as before
            }
        }
        // Notify if anything changed
        if (newUser!=null && (paymentDefaultChanged || force)) {
            mCallback.onPreferredPaymentServiceChanged(newUser.getIdentifier(), newDefault);
        }
        if (paymentPreferForegroundChanged || force) {
            computePreferredForegroundService();
        }
    }

    void computePreferredForegroundService() {
        ComponentName preferredService = null;
        int preferredServiceUserId;
        boolean changed = false;
        synchronized (mLock) {
            // Prio 1: next tap default
            preferredService = mNextTapDefault;
            preferredServiceUserId = mNextTapDefaultUserId;
            if (preferredService == null) {
                // Prio 2: foreground requested by app
                preferredService = mForegroundRequested;
                preferredServiceUserId =
                        UserHandle.getUserHandleForUid(mForegroundUid).getIdentifier();
            }
            if (preferredService != null && (!preferredService.equals(mForegroundCurrent)
                      || preferredServiceUserId
                      != UserHandle.getUserHandleForUid(mForegroundCurrentUid).getIdentifier())) {
                mForegroundCurrent = preferredService;
                mForegroundCurrentUid = mForegroundUid;
                changed = true;
            } else if (preferredService == null && mForegroundCurrent != null){
                mForegroundCurrent = preferredService;
                mForegroundCurrentUid = mForegroundUid;
                changed = true;
            }
        }
        // Notify if anything changed
        if (changed) {
            mCallback.onPreferredForegroundServiceChanged(preferredServiceUserId, preferredService);
        }
    }

    /**
     *  Set default service for next tap
     */
    public boolean setDefaultForNextTap(int userId, ComponentName service) {
        // This is a trusted API, so update without checking
        synchronized (mLock) {
            mNextTapDefault = service;
            mNextTapDefaultUserId = userId;
        }
        computePreferredForegroundService();
        return true;
    }

    public void onServicesUpdated() {
        // If this service is the current foreground service, verify
        // there are no conflicts
        boolean changed = false;
        synchronized (mLock) {
            // Check if the current foreground service is still allowed to override;
            // it could have registered new AIDs that make it conflict with user
            // preferences.
            if (mForegroundCurrent != null) {
                if (!isForegroundAllowedLocked(mForegroundCurrent, mForegroundCurrentUid))  {
                    Log.d(TAG, "Removing foreground preferred service.");
                    mForegroundRequested = null;
                    mForegroundUid = -1;
                    mForegroundCurrentUid = -1;
                    changed = true;
                }
            } else {
                // Don't care about this service
            }
        }
        if (changed) {
            computePreferredForegroundService();
        }
    }

    // Verifies whether a service is allowed to register as preferred
    boolean isForegroundAllowedLocked(ComponentName service, int callingUid) {
        if (service.equals(mPaymentDefaults.currentPreferred)) {
            // If the requester is already the payment default, allow it to request foreground
            // override as well (it could use this to make sure it handles AIDs of category OTHER)
            return true;
        }
        ApduServiceInfo serviceInfo = mServiceCache.getService(
                UserHandle.getUserHandleForUid(callingUid).getIdentifier(), service);
        if (serviceInfo == null) {
            Log.d(TAG, "Requested foreground service unexpectedly removed");
            return false;
        }
        // Do some quick checking
        if (!mPaymentDefaults.preferForeground) {
            // Foreground apps are not allowed to override payment default
            // Check if this app registers payment AIDs, in which case we'll fail anyway
            if (serviceInfo.hasCategory(CardEmulation.CATEGORY_PAYMENT)) {
                Log.d(TAG, "User doesn't allow payment services to be overridden.");
                return false;
            }
            // If no payment AIDs, get AIDs of category other, and see if there's any
            // conflict with payment AIDs of current default payment app. That means
            // the current default payment app said this was a payment AID, and the
            // foreground app says it was not. In this case we'll still prefer the payment
            // app, since that is the one that the user has explicitly selected (and said
            // it's not allowed to be overridden).
            final List<String> otherAids = serviceInfo.getAids();
            ApduServiceInfo paymentServiceInfo = mServiceCache.getService(
                    mPaymentDefaults.mUserHandle.getIdentifier(),
                    mPaymentDefaults.currentPreferred);
            if (paymentServiceInfo != null && otherAids != null && otherAids.size() > 0) {
                for (String aid : otherAids) {
                    RegisteredAidCache.AidResolveInfo resolveInfo = mAidCache.resolveAid(aid);
                    if (CardEmulation.CATEGORY_PAYMENT.equals(resolveInfo.category) &&
                            paymentServiceInfo.equals(resolveInfo.defaultService)) {
                        if (DBG) Log.d(TAG, "AID " + aid + " is handled by the default payment app,"
                                + " and the user has not allowed payments to be overridden.");
                        return false;
                    }
                }
                return true;
            } else {
                // Could not find payment service or fg app doesn't register other AIDs;
                // okay to proceed.
                return true;
            }
        } else {
            // Payment allows override, so allow anything.
            return true;
        }
    }

    public boolean registerPreferredForegroundService(ComponentName service, int callingUid) {
        boolean success = false;
        synchronized (mLock) {
            if (isForegroundAllowedLocked(service, callingUid)) {
                if (mForegroundUtils.registerUidToBackgroundCallback(this, callingUid)) {
                    mForegroundRequested = service;
                    mForegroundUid = callingUid;
                    success = true;
                } else {
                    Log.e(TAG, "Calling UID is not in the foreground, ignorning!");
                    success = false;
                }
            } else {
                Log.e(TAG, "Requested foreground service conflicts or was removed.");
            }
        }
        if (success) {
            computePreferredForegroundService();
        }
        return success;
    }

    boolean unregisterForegroundService(int uid) {
        boolean success = false;
        synchronized (mLock) {
            if (mForegroundUid == uid) {
                mForegroundRequested = null;
                mForegroundUid = -1;
                success = true;
            } // else, other UID in foreground
        }
        if (success) {
            computePreferredForegroundService();
        }
        return success;
    }

    public boolean unregisteredPreferredForegroundService(int callingUid) {
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
        unregisterForegroundService(uid);
    }

    public void onHostEmulationActivated() {
        synchronized (mLock) {
            mClearNextTapDefault = (mNextTapDefault != null);
        }
    }

    public void onHostEmulationDeactivated() {
        // If we had any next tap defaults set, clear them out
        boolean changed = false;
        synchronized (mLock) {
            if (mClearNextTapDefault) {
                // The reason we need to check this boolean is because the next tap
                // default may have been set while the user held the phone
                // on the reader; when the user then removes his phone from
                // the reader (causing the "onHostEmulationDeactivated" event),
                // the next tap default would immediately be cleared
                // again. Instead, clear out defaults only if a next tap default
                // had already been set at time of activation, which is captured
                // by mClearNextTapDefault.
                if (mNextTapDefault != null) {
                    mNextTapDefault = null;
                    changed = true;
                }
                mClearNextTapDefault = false;
            }
        }
        if (changed) {
            computePreferredForegroundService();
        }
    }

    public void onUserSwitched(int userId) {
        loadDefaultsFromSettings(userId, true);
    }

    public boolean packageHasPreferredService(String packageName) {
        if (packageName == null) return false;
        synchronized (mLock) {
            if (mPaymentDefaults.currentPreferred != null &&
                    packageName.equals(mPaymentDefaults.currentPreferred.getPackageName())) {
                return true;
            } else if (mForegroundCurrent != null &&
                    packageName.equals(mForegroundCurrent.getPackageName())) {
                return true;
            } else {
                return false;
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            pw.println("Preferred services (in order of importance): ");
            pw.println("    *** Current preferred foreground service: " + mForegroundCurrent
                    + " (UID:" + mForegroundCurrentUid + ")");
            pw.println("    *** Current preferred payment service: "
                    + mPaymentDefaults.currentPreferred + "("
                    + getUserName(mPaymentDefaults.mUserHandle) + ")");
            pw.println("        Next tap default: " + mNextTapDefault
                    + " (" + getUserName(UserHandle.of(mNextTapDefaultUserId)) + ")");
            pw.println("        Default for foreground app (UID: " + mForegroundUid
                    + "): " + mForegroundRequested);
            pw.println("        Default in payment settings: " + mPaymentDefaults.settingsDefault
                    + "(" + getUserName(mPaymentDefaults.mUserHandle) + ")");
            pw.println("        Payment settings allows override: " + mPaymentDefaults.preferForeground);
            pw.println("");
        }
    }

    private String getUserName(UserHandle uh) {
        if (uh == null) {
            return null;
        }
        UserManager um = mContext.createContextAsUser(
                uh, /*flags=*/0).getSystemService(UserManager.class);
        if (um == null) {
            return null;
        }
        return com.android.nfc.Utils.maskSubstring(um.getUserName(), 3);
    }

    /**
     * Dump debugging information as a PreferredServicesProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/card_emulation.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    void dumpDebug(ProtoOutputStream proto) {
        synchronized (mLock) {
            if (mForegroundCurrent != null) {
                Utils.dumpDebugComponentName(
                        mForegroundCurrent, proto, PreferredServicesProto.FOREGROUND_CURRENT);
            }
            if (mPaymentDefaults.currentPreferred != null) {
                Utils.dumpDebugComponentName(
                        mPaymentDefaults.currentPreferred, proto,
                        PreferredServicesProto.FOREGROUND_CURRENT);
            }
            if (mNextTapDefault != null) {
                Utils.dumpDebugComponentName(
                        mNextTapDefault, proto, PreferredServicesProto.NEXT_TAP_DEFAULT);
            }
            proto.write(PreferredServicesProto.FOREGROUND_UID, mForegroundUid);
            if (mForegroundRequested != null) {
                Utils.dumpDebugComponentName(
                        mForegroundRequested, proto, PreferredServicesProto.FOREGROUND_REQUESTED);
            }
            if (mPaymentDefaults.settingsDefault != null) {
                Utils.dumpDebugComponentName(
                        mPaymentDefaults.settingsDefault, proto,
                        PreferredServicesProto.SETTINGS_DEFAULT);
            }
            proto.write(PreferredServicesProto.PREFER_FOREGROUND, mPaymentDefaults.preferForeground);
        }
    }
}
