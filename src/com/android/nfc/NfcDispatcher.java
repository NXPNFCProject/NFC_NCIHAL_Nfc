/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.content.pm.PackageManager.MATCH_CLONE_PROFILE;
import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;
import static android.nfc.Flags.enableNfcMainline;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProtoEnums;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.ResolveInfoFlags;
import android.content.pm.ResolveInfo;
import android.content.res.Resources.NotFoundException;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcBarcode;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.sysprop.NfcProperties;
import android.text.TextUtils;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.nfc.RegisteredComponentCache.ComponentInfo;
import com.android.nfc.handover.HandoverDataParser;
import com.android.nfc.handover.PeripheralHandoverService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Dispatch of NFC events to start activities
 */
class NfcDispatcher {
    private static final boolean DBG =
            NfcProperties.debug_enabled().orElse(true);
    private static final String TAG = "NfcDispatcher";

    static final int DISPATCH_SUCCESS = 1;
    static final int DISPATCH_FAIL = 2;
    static final int DISPATCH_UNLOCK = 3;

    private final Context mContext;
    private final RegisteredComponentCache mTechListFilters;
    private final ContentResolver mContentResolver;
    private final HandoverDataParser mHandoverDataParser;
    private final String[] mProvisioningMimes;
    private final ScreenStateHelper mScreenStateHelper;
    private final NfcUnlockManager mNfcUnlockManager;
    private final boolean mDeviceSupportsBluetooth;
    private final NfcInjector mNfcInjector;
    private final Handler mMessageHandler = new MessageHandler();
    private final Messenger mMessenger = new Messenger(mMessageHandler);
    private AtomicBoolean mBluetoothEnabledByNfc = new AtomicBoolean();

    // Locked on this
    private PendingIntent mOverrideIntent;
    private IntentFilter[] mOverrideFilters;
    private String[][] mOverrideTechLists;
    private int mForegroundUid;
    private ForegroundUtils mForegroundUtils;
    private boolean mProvisioningOnly;
    private NfcAdapter mNfcAdapter;
    private boolean mIsTagAppPrefSupported;

    NfcDispatcher(Context context,
                  HandoverDataParser handoverDataParser,
                  NfcInjector nfcInjector,
                  boolean provisionOnly) {
        mContext = context;
        mTechListFilters = new RegisteredComponentCache(mContext,
                NfcAdapter.ACTION_TECH_DISCOVERED, NfcAdapter.ACTION_TECH_DISCOVERED);
        mContentResolver = context.getContentResolver();
        mHandoverDataParser = handoverDataParser;
        mNfcInjector = nfcInjector;
        mScreenStateHelper = new ScreenStateHelper(context);
        mNfcUnlockManager = NfcUnlockManager.getInstance();
        mDeviceSupportsBluetooth = BluetoothAdapter.getDefaultAdapter() != null;
        mForegroundUid = Process.INVALID_UID;
        mForegroundUtils = ForegroundUtils.getInstance(
                context.getSystemService(ActivityManager.class));
        synchronized (this) {
            mProvisioningOnly = provisionOnly;
        }
        String[] provisionMimes = null;
        if (provisionOnly) {
            try {
                // Get accepted mime-types
                provisionMimes = context.getResources().
                        getStringArray(R.array.provisioning_mime_types);
            } catch (NotFoundException e) {
               provisionMimes = null;
            }
        }
        mProvisioningMimes = provisionMimes;
        mIsTagAppPrefSupported =
                mContext.getResources().getBoolean(R.bool.tag_intent_app_pref_supported);

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiver(mBluetoothStatusReceiver, filter);
    }

    @Override
    protected void finalize() throws Throwable {
        mContext.unregisterReceiver(mBluetoothStatusReceiver);
        super.finalize();
    }

    public synchronized void resetForegroundDispatch() {
        setForegroundDispatch(null, null, new String[][]{});
    }

    public synchronized void setForegroundDispatch(PendingIntent intent,
            IntentFilter[] filters, String[][] techLists) {
        if (DBG) Log.d(TAG, "Set Foreground Dispatch");
        mOverrideIntent = intent;
        mOverrideFilters = filters;
        mOverrideTechLists = techLists;

        if (mOverrideIntent != null) {
            int callingUid = Binder.getCallingUid();
            if (mForegroundUid != callingUid) {
                mForegroundUtils.registerUidToBackgroundCallback(mForegroundCallback, callingUid);
                mForegroundUid = callingUid;
            }
        }
    }

    final ForegroundUtils.Callback mForegroundCallback = new ForegroundCallbackImpl();

    class ForegroundCallbackImpl implements ForegroundUtils.Callback {
        @Override
        public void onUidToBackground(int uid) {
            synchronized (NfcDispatcher.this) {
                if (mForegroundUid == uid) {
                    if (DBG) Log.d(TAG, "Uid " + uid + " switch to background.");
                    mForegroundUid = Process.INVALID_UID;
                    setForegroundDispatch(null, null, null);
                }
            }
        }
    }

    public synchronized void disableProvisioningMode() {
       mProvisioningOnly = false;
    }

    private static Intent createNfcResolverIntent(
            Intent target,
            CharSequence title,
            List<ResolveInfo> resolutionList) {
        Intent resolverIntent = new Intent(NfcAdapter.ACTION_SHOW_NFC_RESOLVER);
        resolverIntent.putExtra(Intent.EXTRA_INTENT, target);
        resolverIntent.putExtra(Intent.EXTRA_TITLE, title);
        resolverIntent.putParcelableArrayListExtra(
                NfcAdapter.EXTRA_RESOLVE_INFOS, new ArrayList<>(resolutionList));
        resolverIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return resolverIntent;
    }

    private static List<ResolveInfo> queryNfcIntentActivitiesAsUser(
            PackageManager packageManager, Intent intent, UserHandle uh) {
        return packageManager.queryIntentActivitiesAsUser(intent,
                ResolveInfoFlags.of(MATCH_DEFAULT_ONLY | MATCH_CLONE_PROFILE),
                uh);
    }

    /**
     * Helper for re-used objects and methods during a single tag dispatch.
     */
    static class DispatchInfo {
        public final Intent intent;
        public final Tag tag;

        Intent rootIntent;
        final Uri ndefUri;
        final String ndefMimeType;
        final PackageManager packageManager;
        final Context context;
        final NfcAdapter mNfcAdapter;
        final boolean mIsTagAppPrefSupported;

        public DispatchInfo(Context context, Tag tag, NdefMessage message) {
            intent = new Intent();
            intent.putExtra(NfcAdapter.EXTRA_TAG, tag);
            intent.putExtra(NfcAdapter.EXTRA_ID, tag.getId());
            if (message != null) {
                intent.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, new NdefMessage[] {message});
                ndefUri = message.getRecords()[0].toUri();
                ndefMimeType = message.getRecords()[0].toMimeType();
            } else {
                ndefUri = null;
                ndefMimeType = null;
            }
            this.tag = tag;

            rootIntent = new Intent(context, NfcRootActivity.class);
            rootIntent.putExtra(NfcRootActivity.EXTRA_LAUNCH_INTENT, intent);
            rootIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            this.context = context;
            packageManager = context.getPackageManager();
            mIsTagAppPrefSupported =
                    context.getResources().getBoolean(R.bool.tag_intent_app_pref_supported);
            if (mIsTagAppPrefSupported) {
                mNfcAdapter = NfcAdapter.getDefaultAdapter(context.getApplicationContext());
            } else {
                mNfcAdapter = null;
            }
        }

        public Intent setNdefIntent() {
            intent.setAction(NfcAdapter.ACTION_NDEF_DISCOVERED);
            if (ndefUri != null) {
                intent.setData(ndefUri);
                return intent;
            } else if (ndefMimeType != null) {
                intent.setType(ndefMimeType);
                return intent;
            }
            return null;
        }

        public Intent setViewIntent() {
            intent.setAction(Intent.ACTION_VIEW);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            if (ndefUri != null) {
                intent.setData(ndefUri);
                return intent;
            } else if (ndefMimeType != null) {
                intent.setType(ndefMimeType);
                return intent;
            }
            return null;
        }

        public Intent setTechIntent() {
            intent.setData(null);
            intent.setType(null);
            intent.setAction(NfcAdapter.ACTION_TECH_DISCOVERED);
            return intent;
        }

        public Intent setTagIntent() {
            intent.setData(null);
            intent.setType(null);
            intent.setAction(NfcAdapter.ACTION_TAG_DISCOVERED);
            return intent;
        }

        public boolean hasIntentReceiver() {
            boolean status = false;
            List<UserHandle> luh = getCurrentActiveUserHandles();
            for (UserHandle uh : luh) {
                List<ResolveInfo> activities = queryNfcIntentActivitiesAsUser(
                        packageManager, intent, uh);;
                activities = activities.stream().filter(activity -> activity.activityInfo.exported)
                        .collect(Collectors.toList());
                if (activities.size() > 0) {
                    status = true;
                }
            }
            return status;
        }

        public boolean isWebIntent() {
            return ndefUri != null && ndefUri.normalizeScheme().getScheme() != null &&
                ndefUri.normalizeScheme().getScheme().startsWith("http");
        }

        public String getUri() {
            return ndefUri.toString();
        }

        List<ResolveInfo> checkPrefList(List<ResolveInfo> activities, int userId) {
            if (!mIsTagAppPrefSupported) return activities;
            ArrayList<ResolveInfo> filtered = new ArrayList<>(activities);
            int muteAppCount = 0;
            for (ResolveInfo resolveInfo : activities) {
                ActivityInfo activityInfo = resolveInfo.activityInfo;
                ComponentName cmp = new ComponentName(activityInfo.packageName, activityInfo.name);
                if (DBG) {
                    Log.d(TAG, "activityInfo.packageName= " + activityInfo.packageName);
                    Log.d(TAG, "activityInfo.name= " + activityInfo.name);
                    Log.d(TAG, "cmp.flattenToString= " + cmp.flattenToString());
                }
                Map<String, Boolean> preflist =
                        mNfcAdapter.getTagIntentAppPreferenceForUser(userId);
                if (preflist.containsKey(activityInfo.packageName)) {
                    if (!preflist.get(activityInfo.packageName)) {
                        if (DBG) Log.d(TAG, "mute pkg:" + cmp.flattenToString());
                        muteAppCount++;
                        filtered.remove(resolveInfo);
                        logMuteApp(activityInfo.applicationInfo.uid);
                    }
                } else {
                    // Default sets allow to the preference list
                    mNfcAdapter.setTagIntentAppPreferenceForUser(userId, activityInfo.packageName,
                            true);
                }
            }
            if (muteAppCount > 0) {
                if (DBG) Log.d(TAG, "muteAppCount = " + muteAppCount);
                if (filtered.size() > 0) {
                    if (enableNfcMainline()) {
                        rootIntent = createNfcResolverIntent(intent, null, filtered);
                    } else {
                        rootIntent.setClass(context, TechListChooserActivity.class);
                        rootIntent.putExtra(Intent.EXTRA_INTENT, intent);
                        rootIntent.putParcelableArrayListExtra(
                                TechListChooserActivity.EXTRA_RESOLVE_INFOS, filtered);
                    }
                }
            }
            return filtered;
        }

        /**
         * Launch the activity via a (single) NFC root task, so that it
         * creates a new task stack instead of interfering with any existing
         * task stack for that activity.
         * NfcRootActivity acts as the task root, it immediately calls
         * start activity on the intent it is passed.
         */
        boolean tryStartActivity() {
            // Ideally we'd have used startActivityForResult() to determine whether the
            // NfcRootActivity was able to launch the intent, but startActivityForResult()
            // is not available on Context. Instead, we query the PackageManager beforehand
            // to determine if there is an Activity to handle this intent, and base the
            // result of off that.
            // try current user if there is an Activity to handle this intent
            List<ResolveInfo> activities = queryNfcIntentActivitiesAsUser(
                    packageManager, intent, UserHandle.of(ActivityManager.getCurrentUser()));
            activities = activities.stream().filter(activity -> activity.activityInfo.exported)
                    .collect(Collectors.toList());
            if (mIsTagAppPrefSupported) {
                activities = checkPrefList(activities, ActivityManager.getCurrentUser());
            }
            if (DBG) Log.d(TAG, "activities.size() = " + activities.size());
            if (activities.size() > 0) {
                if (DBG) Log.d(TAG, "tryStartActivity currentUser");
                context.startActivityAsUser(rootIntent, UserHandle.CURRENT);

                int uid = -1;
                if (activities.size() == 1) {
                    uid = activities.get(0).activityInfo.applicationInfo.uid;
                } else {
                    NfcStatsLog.write(NfcStatsLog.NFC_READER_CONFLICT_OCCURRED);
                }
                NfcStatsLog.write(NfcStatsLog.NFC_TAG_OCCURRED,
                        NfcStatsLog.NFC_TAG_OCCURRED__TYPE__APP_LAUNCH,
                        uid,
                        tag.getTechCodeList(),
                        BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED,
                        "");
                return true;
            }
            // try other users when there is no Activity in current user to handle this intent
            List<UserHandle> userHandles = getCurrentActiveUserHandles();
            userHandles.remove(UserHandle.of(ActivityManager.getCurrentUser()));
            for (UserHandle uh : userHandles) {
                activities = queryNfcIntentActivitiesAsUser(packageManager, intent, uh);
                activities = activities.stream().filter(activity -> activity.activityInfo.exported)
                        .collect(Collectors.toList());
                if (mIsTagAppPrefSupported) {
                    activities = checkPrefList(activities, uh.getIdentifier());
                }
                if (activities.size() > 0) {
                    if (DBG) Log.d(TAG, "tryStartActivity other user");
                    rootIntent.putExtra(NfcRootActivity.EXTRA_LAUNCH_INTENT_USER_HANDLE, uh);
                    context.startActivityAsUser(rootIntent, uh);

                    int uid = -1;
                    if (activities.size() == 1) {
                        uid = activities.get(0).activityInfo.applicationInfo.uid;
                    } else {
                        NfcStatsLog.write(NfcStatsLog.NFC_READER_CONFLICT_OCCURRED);
                    }
                    NfcStatsLog.write(NfcStatsLog.NFC_TAG_OCCURRED,
                            NfcStatsLog.NFC_TAG_OCCURRED__TYPE__APP_LAUNCH,
                            uid,
                            tag.getTechCodeList(),
                            BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED,
                            "");
                    return true;
                }
            }
            return false;
        }

        boolean tryStartActivity(Intent intentToStart) {
            // try current user if there is an Activity to handle this intent
            List<ResolveInfo> activities = queryNfcIntentActivitiesAsUser(
                    packageManager, intentToStart, UserHandle.of(ActivityManager.getCurrentUser()));
            activities = activities.stream().filter(activity -> activity.activityInfo.exported)
                    .collect(Collectors.toList());
            if (activities.size() > 0) {
                if (DBG) Log.d(TAG, "tryStartActivity(Intent) currentUser");
                rootIntent.putExtra(NfcRootActivity.EXTRA_LAUNCH_INTENT, intentToStart);
                context.startActivityAsUser(rootIntent, UserHandle.CURRENT);

                int uid = -1;
                if (activities.size() == 1) {
                    uid = activities.get(0).activityInfo.applicationInfo.uid;
                }
                NfcStatsLog.write(NfcStatsLog.NFC_TAG_OCCURRED,
                        NfcStatsLog.NFC_TAG_OCCURRED__TYPE__APP_LAUNCH,
                        uid,
                        tag.getTechCodeList(),
                        BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED,
                        "");
                return true;
            }
            // try other users when there is no Activity in current user to handle this intent
            List<UserHandle> userHandles = getCurrentActiveUserHandles();
            userHandles.remove(UserHandle.of(ActivityManager.getCurrentUser()));
            for (UserHandle uh : userHandles) {
                activities = queryNfcIntentActivitiesAsUser(packageManager, intentToStart, uh);
                activities = activities.stream().filter(activity -> activity.activityInfo.exported)
                        .collect(Collectors.toList());
                if (mIsTagAppPrefSupported) {
                    activities = checkPrefList(activities, uh.getIdentifier());
                }
                if (activities.size() > 0) {
                    if (DBG) Log.d(TAG, "tryStartActivity(Intent) other user");
                    rootIntent.putExtra(NfcRootActivity.EXTRA_LAUNCH_INTENT, intentToStart);
                    rootIntent.putExtra(NfcRootActivity.EXTRA_LAUNCH_INTENT_USER_HANDLE, uh);
                    context.startActivityAsUser(rootIntent, uh);

                    int uid = -1;
                    if (activities.size() == 1) {
                        uid = activities.get(0).activityInfo.applicationInfo.uid;
                    }
                    NfcStatsLog.write(NfcStatsLog.NFC_TAG_OCCURRED,
                            NfcStatsLog.NFC_TAG_OCCURRED__TYPE__APP_LAUNCH,
                            uid,
                            tag.getTechCodeList(),
                            BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED,
                            "");
                    return true;
                }
            }
            return false;
        }

        List<UserHandle> getCurrentActiveUserHandles() {
            UserManager um = context.createContextAsUser(
                    UserHandle.of(ActivityManager.getCurrentUser()), /*flags=*/0)
                    .getSystemService(UserManager.class);
            List<UserHandle> luh = um.getEnabledProfiles();
            List<UserHandle> rluh = new ArrayList<UserHandle>();
            for (UserHandle uh : luh) {
                if (um.isQuietModeEnabled(uh)) {
                    rluh.add(uh);
                }
            }
            luh.removeAll(rluh);
            return luh;
        }

        private void logMuteApp(int uid) {
            int muteType;
            switch (intent.getAction()) {
                case NfcAdapter.ACTION_NDEF_DISCOVERED:
                    muteType = NfcStatsLog.NFC_TAG_OCCURRED__TYPE__APP_LAUNCH_NDEF_MUTE;
                    break;
                case NfcAdapter.ACTION_TECH_DISCOVERED:
                    muteType = NfcStatsLog.NFC_TAG_OCCURRED__TYPE__APP_LAUNCH_TECH_MUTE;
                    break;
                case NfcAdapter.ACTION_TAG_DISCOVERED:
                default:
                    muteType = NfcStatsLog.NFC_TAG_OCCURRED__TYPE__APP_LAUNCH_TAG_MUTE;
            }
            NfcStatsLog.write(NfcStatsLog.NFC_TAG_OCCURRED,
                    muteType,
                    uid,
                    tag.getTechCodeList(),
                    BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED,
                    "");
        }
    }

    /** Returns:
     * <ul>
     *  <li /> DISPATCH_SUCCESS if dispatched to an activity,
     *  <li /> DISPATCH_FAIL if no activities were found to dispatch to,
     *  <li /> DISPATCH_UNLOCK if the tag was used to unlock the device
     * </ul>
     */
    public int dispatchTag(Tag tag) {
        PendingIntent overrideIntent;
        IntentFilter[] overrideFilters;
        String[][] overrideTechLists;
        String[] provisioningMimes;
        boolean provisioningOnly;
        NdefMessage message = null;
        Ndef ndef = Ndef.get(tag);
        if (mIsTagAppPrefSupported) {
            mNfcAdapter = NfcAdapter.getDefaultAdapter(mContext.getApplicationContext());
        }

        synchronized (this) {
            overrideFilters = mOverrideFilters;
            overrideIntent = mOverrideIntent;
            overrideTechLists = mOverrideTechLists;
            provisioningOnly = mProvisioningOnly;
            provisioningMimes = mProvisioningMimes;
        }

        boolean screenUnlocked = false;
        if (!provisioningOnly &&
                mScreenStateHelper.checkScreenState(
                        mContext.getResources().getBoolean(
                                R.bool.check_display_state_for_screen_state))
                        == ScreenStateHelper.SCREEN_STATE_ON_LOCKED) {
            screenUnlocked = handleNfcUnlock(tag);
            if (!screenUnlocked)
                return DISPATCH_FAIL;
        }

        if (ndef != null) {
            message = ndef.getCachedNdefMessage();
        } else {
            NfcBarcode nfcBarcode = NfcBarcode.get(tag);
            if (nfcBarcode != null && nfcBarcode.getType() == NfcBarcode.TYPE_KOVIO) {
                message = decodeNfcBarcodeUri(nfcBarcode);
            }
        }

        if (DBG) Log.d(TAG, "dispatch tag: " + tag.toString() + " message: " + message);

        DispatchInfo dispatch = new DispatchInfo(mContext, tag, message);

        resumeAppSwitches();

        if (tryOverrides(dispatch, tag, message, overrideIntent, overrideFilters,
                overrideTechLists)) {
            NfcStatsLog.write(
                    NfcStatsLog.NFC_TAG_OCCURRED,
                    NfcStatsLog.NFC_TAG_OCCURRED__TYPE__FOREGROUND_DISPATCH,
                    mForegroundUid,
                    tag.getTechCodeList(),
                    BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED,
                    "");
            return screenUnlocked ? DISPATCH_UNLOCK : DISPATCH_SUCCESS;
        }

        if (tryPeripheralHandover(message, tag)) {
            if (DBG) Log.i(TAG, "matched BT HANDOVER");
            return screenUnlocked ? DISPATCH_UNLOCK : DISPATCH_SUCCESS;
        }

        if (NfcWifiProtectedSetup.tryNfcWifiSetup(ndef, mContext)) {
            if (DBG) Log.i(TAG, "matched NFC WPS TOKEN");
            NfcStatsLog.write(
                    NfcStatsLog.NFC_TAG_OCCURRED,
                    NfcStatsLog.NFC_TAG_OCCURRED__TYPE__WIFI_CONNECT,
                    -1,
                    tag.getTechCodeList(),
                    BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED,
                    "");
            return screenUnlocked ? DISPATCH_UNLOCK : DISPATCH_SUCCESS;
        }

        if (provisioningOnly) {
            NfcStatsLog.write(
                    NfcStatsLog.NFC_TAG_OCCURRED,
                    NfcStatsLog.NFC_TAG_OCCURRED__TYPE__PROVISION,
                    -1,
                    tag.getTechCodeList(),
                    BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED,
                    "");
            if (message == null) {
                // We only allow NDEF-message dispatch in provisioning mode
                return DISPATCH_FAIL;
            }
            // Restrict to mime-types in allowlist.
            String ndefMimeType = message.getRecords()[0].toMimeType();
            if (provisioningMimes == null ||
                    !(Arrays.asList(provisioningMimes).contains(ndefMimeType))) {
                Log.e(TAG, "Dropping NFC intent in provisioning mode.");
                return DISPATCH_FAIL;
            }
        }

        if (tryNdef(dispatch, message)) {
            return screenUnlocked ? DISPATCH_UNLOCK : DISPATCH_SUCCESS;
        }

        if (screenUnlocked) {
            // We only allow NDEF-based mimeType matching in case of an unlock
            return DISPATCH_UNLOCK;
        }

        // Only allow NDEF-based mimeType matching for unlock tags
        if (tryTech(dispatch, tag)) {
            return DISPATCH_SUCCESS;
        }

        dispatch.setTagIntent();
        if (dispatch.tryStartActivity()) {
            if (DBG) Log.i(TAG, "matched TAG");
            return DISPATCH_SUCCESS;
        }

        if (DBG) Log.i(TAG, "no match");
        NfcStatsLog.write(NfcStatsLog.NFC_TAG_OCCURRED,
              NfcStatsLog.NFC_TAG_OCCURRED__TYPE__OTHERS,
              -1,
              tag.getTechCodeList(),
              BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED,
              "");
        return DISPATCH_FAIL;
    }

    private boolean handleNfcUnlock(Tag tag) {
        return mNfcUnlockManager.tryUnlock(tag);
    }

    /**
     * Checks for the presence of a URL stored in a tag with tech NfcBarcode.
     * If found, decodes URL and returns NdefMessage message containing an
     * NdefRecord containing the decoded URL. If not found, returns null.
     *
     * URLs are decoded as follows:
     *
     * Ignore first byte (which is 0x80 ORd with a manufacturer ID, corresponding
     * to ISO/IEC 7816-6).
     * The second byte describes the payload data format. There are four defined data
     * format values that identify URL data. Depending on the data format value, the
     * associated prefix is appended to the URL data:
     *
     * 0x01: URL with "http://www." prefix
     * 0x02: URL with "https://www." prefix
     * 0x03: URL with "http://" prefix
     * 0x04: URL with "https://" prefix
     *
     * Other data format values do not identify URL data and are not handled by this function.
     * URL payload is encoded in US-ASCII, following the limitations defined in RFC3987.
     * see http://www.ietf.org/rfc/rfc3987.txt
     *
     * The final two bytes of a tag with tech NfcBarcode are always reserved for CRC data,
     * and are therefore not part of the payload. They are ignored in the decoding of a URL.
     *
     * The default assumption is that the URL occupies the entire payload of the NfcBarcode
     * ID and all bytes of the NfcBarcode payload are decoded until the CRC (final two bytes)
     * is reached. However, the OPTIONAL early terminator byte 0xfe can be used to signal
     * an early end of the URL. Once this function reaches an early terminator byte 0xfe,
     * URL decoding stops and the NdefMessage is created and returned. Any payload data after
     * the first early terminator byte is ignored for the purposes of URL decoding.
     */
    private NdefMessage decodeNfcBarcodeUri(NfcBarcode nfcBarcode) {
        final byte URI_PREFIX_HTTP_WWW  = (byte) 0x01; // "http://www."
        final byte URI_PREFIX_HTTPS_WWW = (byte) 0x02; // "https://www."
        final byte URI_PREFIX_HTTP      = (byte) 0x03; // "http://"
        final byte URI_PREFIX_HTTPS     = (byte) 0x04; // "https://"

        NdefMessage message = null;
        byte[] tagId = nfcBarcode.getTag().getId();
        // All tags of NfcBarcode technology and Kovio type have lengths of a multiple of 16 bytes
        if (tagId.length >= 4
                && (tagId[1] == URI_PREFIX_HTTP_WWW || tagId[1] == URI_PREFIX_HTTPS_WWW
                    || tagId[1] == URI_PREFIX_HTTP || tagId[1] == URI_PREFIX_HTTPS)) {
            // Look for optional URI terminator (0xfe), used to indicate the end of a URI prior to
            // the end of the full NfcBarcode payload. No terminator means that the URI occupies the
            // entire length of the payload field. Exclude checking the CRC in the final two bytes
            // of the NfcBarcode tagId.
            int end = 2;
            for (; end < tagId.length - 2; end++) {
                if (tagId[end] == (byte) 0xfe) {
                    break;
                }
            }
            byte[] payload = new byte[end - 1]; // Skip also first byte (manufacturer ID)
            System.arraycopy(tagId, 1, payload, 0, payload.length);
            NdefRecord uriRecord = new NdefRecord(
                    NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_URI, tagId, payload);
            message = new NdefMessage(uriRecord);
        }
        return message;
    }

    boolean tryOverrides(DispatchInfo dispatch, Tag tag, NdefMessage message, PendingIntent overrideIntent,
            IntentFilter[] overrideFilters, String[][] overrideTechLists) {
        if (overrideIntent == null) {
            return false;
        }
        Intent intent;

        // NDEF
        if (message != null) {
            intent = dispatch.setNdefIntent();
            if (intent != null &&
                    isFilterMatch(intent, overrideFilters, overrideTechLists != null)) {
                try {
                    overrideIntent.send(mContext, Activity.RESULT_OK, intent);
                    if (DBG) Log.i(TAG, "matched NDEF override");
                    return true;
                } catch (CanceledException e) {
                    return false;
                }
            }
        }

        // TECH
        intent = dispatch.setTechIntent();
        if (isTechMatch(tag, overrideTechLists)) {
            try {
                overrideIntent.send(mContext, Activity.RESULT_OK, intent);
                if (DBG) Log.i(TAG, "matched TECH override");
                return true;
            } catch (CanceledException e) {
                return false;
            }
        }

        // TAG
        intent = dispatch.setTagIntent();
        if (isFilterMatch(intent, overrideFilters, overrideTechLists != null)) {
            try {
                overrideIntent.send(mContext, Activity.RESULT_OK, intent);
                if (DBG) Log.i(TAG, "matched TAG override");
                return true;
            } catch (CanceledException e) {
                return false;
            }
        }
        return false;
    }

    boolean isFilterMatch(Intent intent, IntentFilter[] filters, boolean hasTechFilter) {
        if (filters != null) {
            for (IntentFilter filter : filters) {
                if (filter.match(mContentResolver, intent, false, TAG) >= 0) {
                    return true;
                }
            }
        } else if (!hasTechFilter) {
            return true;  // always match if both filters and techlists are null
        }
        return false;
    }

    boolean isTechMatch(Tag tag, String[][] techLists) {
        if (techLists == null) {
            return false;
        }

        String[] tagTechs = tag.getTechList();
        Arrays.sort(tagTechs);
        for (String[] filterTechs : techLists) {
            if (filterMatch(tagTechs, filterTechs)) {
                return true;
            }
        }
        return false;
    }

    boolean tryNdef(DispatchInfo dispatch, NdefMessage message) {
        if (message == null) {
            return false;
        }
        Intent intent = dispatch.setNdefIntent();

        // Bail out if the intent does not contain filterable NDEF data
        if (intent == null) return false;

        // Try to start AAR activity with matching filter
        List<String> aarPackages = extractAarPackages(message);
        for (String pkg : aarPackages) {
            dispatch.intent.setPackage(pkg);
            if (dispatch.tryStartActivity()) {
                if (DBG) Log.i(TAG, "matched AAR to NDEF");
                return true;
            }
        }

        List<UserHandle> luh = dispatch.getCurrentActiveUserHandles();
        // Try to perform regular launch of the first AAR
        if (aarPackages.size() > 0) {
            String firstPackage = aarPackages.get(0);
            PackageManager pm;
            for (UserHandle uh : luh) {
                try {
                    pm = mContext.createPackageContextAsUser("android", 0,
                            uh).getPackageManager();
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Could not create user package context");
                    return false;
                }
                Intent appLaunchIntent = pm.getLaunchIntentForPackage(firstPackage);
                if (appLaunchIntent != null) {
                    ResolveInfo ri = pm.resolveActivity(appLaunchIntent, 0);
                    if (ri != null && ri.activityInfo != null && ri.activityInfo.exported
                            && dispatch.tryStartActivity(appLaunchIntent)) {
                        if (DBG) Log.i(TAG, "matched AAR to application launch");
                        return true;
                    }
                }
            }
            // Find the package in Market:
            Intent marketIntent = getAppSearchIntent(firstPackage);
            if (marketIntent != null && dispatch.tryStartActivity(marketIntent)) {
                if (DBG) Log.i(TAG, "matched AAR to market launch");
                return true;
            }
        }

        // regular launch
        dispatch.intent.setPackage(null);

        if (dispatch.isWebIntent()) {
            if (mNfcInjector.getFeatureFlags().sendViewIntentForUrlTagDispatch()) {
                dispatch.setViewIntent();
                Log.d(TAG, "Sending VIEW intent instead of NFC specific intent");
            }
            if (dispatch.hasIntentReceiver()) {
                if (showWebLinkConfirmation(dispatch)) {
                    if (DBG) Log.i(TAG, "matched Web link - prompting user");
                    NfcStatsLog.write(
                            NfcStatsLog.NFC_TAG_OCCURRED,
                            NfcStatsLog.NFC_TAG_OCCURRED__TYPE__URL,
                            -1,
                            dispatch.tag.getTechCodeList(),
                            BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED,
                            "");
                    return true;
                }
                return false;
            }
        }

        for (UserHandle uh : luh) {
            try {
                PackageManager pm = mContext.createPackageContextAsUser("android", 0,
                        uh).getPackageManager();
                ResolveInfo ri = pm.resolveActivity(intent, 0);

                if (ri != null && ri.activityInfo != null && ri.activityInfo.exported
                        && dispatch.tryStartActivity()) {
                    if (DBG) Log.i(TAG, "matched NDEF");
                    return true;
                }
            } catch (NameNotFoundException ignore) {
                Log.e(TAG, "Could not create user package context");
            }
        }
        if (DBG) Log.i(TAG, "No match NDEF");
        return false;
    }

    static List<String> extractAarPackages(NdefMessage message) {
        List<String> aarPackages = new LinkedList<String>();
        for (NdefRecord record : message.getRecords()) {
            String pkg = checkForAar(record);
            if (pkg != null) {
                aarPackages.add(pkg);
            }
        }
        return aarPackages;
    }

    boolean tryTech(DispatchInfo dispatch, Tag tag) {
        dispatch.setTechIntent();

        String[] tagTechs = tag.getTechList();
        Arrays.sort(tagTechs);

        // Standard tech dispatch path
        ArrayList<ResolveInfo> matches = new ArrayList<ResolveInfo>();
        List<ComponentInfo> registered = mTechListFilters.getComponents();

        PackageManager pm;
        List<UserHandle> luh = dispatch.getCurrentActiveUserHandles();

        for (UserHandle uh : luh) {
            try {
                pm = mContext.createPackageContextAsUser("android", 0,
                        uh).getPackageManager();
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Could not create user package context");
                return false;
            }
            // Check each registered activity to see if it matches
            for (ComponentInfo info : registered) {
                // Don't allow wild card matching
                if (filterMatch(tagTechs, info.techs)
                        && isComponentEnabled(pm, info.resolveInfo)) {
                    // Add the activity as a match if it's not already in the list
                    // Check if exported flag is not explicitly set to false to prevent
                    // SecurityExceptions.
                    if (!matches.contains(info.resolveInfo)
                            && info.resolveInfo.activityInfo.exported) {
                        if (!mIsTagAppPrefSupported) {
                            matches.add(info.resolveInfo);
                        } else {
                            String pkgName = info.resolveInfo.activityInfo.packageName;
                            int userId = uh.getIdentifier();
                            Map<String, Boolean> preflist =
                                    mNfcAdapter.getTagIntentAppPreferenceForUser(userId);
                            if (preflist.getOrDefault(pkgName, true)) {
                                matches.add(info.resolveInfo);
                                if (!preflist.containsKey(pkgName)) {
                                    // Default sets allow to the preference list
                                    mNfcAdapter.setTagIntentAppPreferenceForUser(userId,
                                            pkgName, true);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (matches.size() == 1) {
            // Single match, launch directly
            ResolveInfo info = matches.get(0);
            dispatch.intent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
            if (dispatch.tryStartActivity()) {
                if (DBG) Log.i(TAG, "matched single TECH");
                return true;
            }
            dispatch.intent.setComponent(null);
        } else if (matches.size() > 1) {
            // Multiple matches, show a custom activity chooser dialog
            Intent intent;
            if (enableNfcMainline()) {
                intent = createNfcResolverIntent(dispatch.intent, null, matches);
            } else {
                intent = new Intent(mContext, TechListChooserActivity.class);
                intent.putExtra(Intent.EXTRA_INTENT, dispatch.intent);
                intent.putParcelableArrayListExtra(TechListChooserActivity.EXTRA_RESOLVE_INFOS,
                        matches);
            }
            if (DBG) Log.i(TAG, "matched multiple TECH");
            NfcStatsLog.write(NfcStatsLog.NFC_READER_CONFLICT_OCCURRED);
            return dispatch.tryStartActivity(intent);
        }
        return false;
    }

    private String getPeripheralName(HandoverDataParser.BluetoothHandoverData handover) {
        if (!TextUtils.isEmpty(handover.name)) {
            return handover.name;
        }
        // If name is empty in the handover data, use a generic name.
        return mContext.getResources().getString(R.string.device);
    }

    public boolean tryPeripheralHandover(NdefMessage m, Tag tag) {
        if (m == null || !mDeviceSupportsBluetooth) return false;
        if (DBG) Log.d(TAG, "tryHandover(): " + m.toString());

        HandoverDataParser.BluetoothHandoverData handover = mHandoverDataParser.parseBluetooth(m);
        if (handover == null || !handover.valid) return false;
        UserManager um = mContext.getSystemService(UserManager.class);
        if (um.hasUserRestrictionForUser(UserManager.DISALLOW_CONFIG_BLUETOOTH,
                // hasUserRestriction does not support UserHandle.CURRENT
                UserHandle.of(ActivityManager.getCurrentUser()))) {
            return false;
        }

        Intent intent = new Intent(mContext, PeripheralHandoverService.class);
        intent.putExtra(PeripheralHandoverService.EXTRA_PERIPHERAL_DEVICE, handover.device);
        intent.putExtra(
            PeripheralHandoverService.EXTRA_PERIPHERAL_NAME, getPeripheralName(handover));
        intent.putExtra(PeripheralHandoverService.EXTRA_PERIPHERAL_TRANSPORT, handover.transport);
        if (handover.oobData != null) {
            intent.putExtra(PeripheralHandoverService.EXTRA_PERIPHERAL_OOB_DATA, handover.oobData);
        }
        if (handover.uuids != null) {
            intent.putExtra(PeripheralHandoverService.EXTRA_PERIPHERAL_UUIDS, handover.uuids);
        }
        if (handover.btClass != null) {
            intent.putExtra(PeripheralHandoverService.EXTRA_PERIPHERAL_CLASS, handover.btClass);
        }
        intent.putExtra(PeripheralHandoverService.EXTRA_BT_ENABLED, mBluetoothEnabledByNfc.get());
        intent.putExtra(PeripheralHandoverService.EXTRA_CLIENT, mMessenger);
        Context contextAsUser = mContext.createContextAsUser(UserHandle.CURRENT, /* flags= */ 0);
        contextAsUser.startService(intent);

        int btClass = BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED;
        String btName = "";
        if (handover.btClass != null) {
            if (DBG) Log.d(TAG, "handover.btClass: " + handover.btClass.getMajorDeviceClass());
            btClass = handover.btClass.getMajorDeviceClass();

            Set<Integer> knownBtClasses = Set.of(BluetoothProtoEnums.MAJOR_CLASS_MISC,
                    BluetoothProtoEnums.MAJOR_CLASS_COMPUTER,
                    BluetoothProtoEnums.MAJOR_CLASS_PHONE,
                    BluetoothProtoEnums.MAJOR_CLASS_NETWORKING,
                    BluetoothProtoEnums.MAJOR_CLASS_AUDIO_VIDEO,
                    BluetoothProtoEnums.MAJOR_CLASS_PERIPHERAL,
                    BluetoothProtoEnums.MAJOR_CLASS_IMAGING,
                    BluetoothProtoEnums.MAJOR_CLASS_WEARABLE,
                    BluetoothProtoEnums.MAJOR_CLASS_TOY,
                    BluetoothProtoEnums.MAJOR_CLASS_HEALTH);

            if (!knownBtClasses.contains(btClass)) {
                // invalid values out of defined enum
                btClass = BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED;

            } else if (btClass != BluetoothProtoEnums.MAJOR_CLASS_UNCATEGORIZED &&
                    btClass != BluetoothProtoEnums.MAJOR_CLASS_HEALTH) {
                // do not collect names for HEALTH and UNKNOWN
                if (DBG) Log.d(TAG, "handover.name: " + handover.name);
                btName = handover.name;
            }
        }

        NfcStatsLog.write(NfcStatsLog.NFC_TAG_OCCURRED,
                NfcStatsLog.NFC_TAG_OCCURRED__TYPE__BT_PAIRING,
                -1,
                tag.getTechCodeList(),
                btClass,
                btName);

        return true;
    }


    /**
     * Tells the ActivityManager to resume allowing app switches.
     *
     * If the current app called stopAppSwitches() then our startActivity() can
     * be delayed for several seconds. This happens with the default home
     * screen.  As a system service we can override this behavior with
     * resumeAppSwitches().
    */
    void resumeAppSwitches() {
        //// Should be auto resumed after S
        // try {
        //     mIActivityManager.resumeAppSwitches();
        // } catch (RemoteException e) { }
    }

    /** Returns true if the tech list filter matches the techs on the tag */
    boolean filterMatch(String[] tagTechs, String[] filterTechs) {
        if (filterTechs == null || filterTechs.length == 0) return false;

        for (String tech : filterTechs) {
            if (Arrays.binarySearch(tagTechs, tech) < 0) {
                return false;
            }
        }
        return true;
    }

    static String checkForAar(NdefRecord record) {
        if (record.getTnf() == NdefRecord.TNF_EXTERNAL_TYPE &&
                Arrays.equals(record.getType(), NdefRecord.RTD_ANDROID_APP)) {
            return new String(record.getPayload(), StandardCharsets.US_ASCII);
        }
        return null;
    }

    /**
     * Returns an intent that can be used to find an application not currently
     * installed on the device.
     */
    static Intent getAppSearchIntent(String pkg) {
        Intent market = new Intent(Intent.ACTION_VIEW);
        market.setData(Uri.parse("market://details?id=" + pkg));
        return market;
    }

    static boolean isComponentEnabled(PackageManager pm, ResolveInfo info) {
        boolean enabled = false;
        ComponentName compname = new ComponentName(
                info.activityInfo.packageName, info.activityInfo.name);
        try {
            // Note that getActivityInfo() will internally call
            // isEnabledLP() to determine whether the component
            // enabled. If it's not, null is returned.
            if (pm.getActivityInfo(compname,0) != null) {
                enabled = true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            enabled = false;
        }
        if (!enabled) {
            Log.d(TAG, "Component not enabled: " + compname);
        }
        return enabled;
    }

    private boolean isTablet() {
        return Arrays.asList(SystemProperties.get("ro.build.characteristics").split(","))
                .contains("tablet");
    }

    boolean showWebLinkConfirmation(DispatchInfo dispatch) {
        if (!mContext.getResources().getBoolean(R.bool.enable_nfc_url_open_dialog)) {
            return dispatch.tryStartActivity();
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(
                mContext.getApplicationContext(),
                R.style.DialogAlertDayNight);
        builder.setTitle(R.string.title_confirm_url_open);
        LayoutInflater inflater = LayoutInflater.from(mContext);
        View view = inflater.inflate(
            isTablet() ? R.layout.url_open_confirmation_tablet : R.layout.url_open_confirmation,
            null);
        if (view != null) {
            TextView url = view.findViewById(R.id.url_open_confirmation_link);
            if (url != null) {
                url.setText(dispatch.getUri());
            }
            builder.setView(view);
        }
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> {});
        builder.setPositiveButton(R.string.action_confirm_url_open, (dialog, which) -> {
            dispatch.tryStartActivity();
        });
        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        dialog.show();
        return true;
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (this) {
            pw.println("mOverrideIntent=" + mOverrideIntent);
            pw.println("mOverrideFilters=" + Arrays.toString(mOverrideFilters));
            pw.println("mOverrideTechLists=" + Arrays.deepToString(mOverrideTechLists));
        }
    }

    void dumpDebug(ProtoOutputStream proto) {
        proto.write(NfcDispatcherProto.DEVICE_SUPPORTS_BLUETOOTH, mDeviceSupportsBluetooth);
        proto.write(NfcDispatcherProto.BLUETOOTH_ENABLED_BY_NFC, mBluetoothEnabledByNfc.get());

        synchronized (this) {
            proto.write(NfcDispatcherProto.PROVISIONING_ONLY, mProvisioningOnly);
            if (mOverrideTechLists != null) {
                StringJoiner techListsJoiner = new StringJoiner(System.lineSeparator());
                for (String[] list : mOverrideTechLists) {
                    techListsJoiner.add(Arrays.toString(list));
                }
                proto.write(NfcDispatcherProto.OVERRIDE_TECH_LISTS, techListsJoiner.toString());
            }
            if (mOverrideIntent != null) {
                Utils.dumpDebugPendingIntent(
                        mOverrideIntent, proto, NfcDispatcherProto.OVERRIDE_INTENT);
            }
            if (mOverrideFilters != null) {
                for (IntentFilter filter : mOverrideFilters) {
                    Utils.dumpDebugIntentFilter(filter, proto, NfcDispatcherProto.OVERRIDE_FILTERS);
                }
            }
        }
    }

    private class MessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (DBG) Log.d(TAG, "handleMessage: msg=" + msg);

            switch (msg.what) {
                case PeripheralHandoverService.MSG_HEADSET_CONNECTED:
                case PeripheralHandoverService.MSG_HEADSET_NOT_CONNECTED:
                    mBluetoothEnabledByNfc.set(msg.arg1 != 0);
                    break;
                default:
                    break;
            }
        }
    }

    final BroadcastReceiver mBluetoothStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                handleBluetoothStateChanged(intent);
            }
        }

        private void handleBluetoothStateChanged(Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_OFF) {
                mBluetoothEnabledByNfc.set(false);
            }
        }
    };
}
