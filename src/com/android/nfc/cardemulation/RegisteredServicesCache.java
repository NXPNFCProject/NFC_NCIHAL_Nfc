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
*  Copyright 2018-2022 NXP
*
******************************************************************************/
package com.android.nfc.cardemulation;

import android.annotation.TargetApi;
import android.annotation.FlaggedApi;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.ResolveInfoFlags;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.nfc.cardemulation.AidGroup;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.nfc.cardemulation.OffHostApduService;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.sysprop.NfcProperties;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import androidx.annotation.VisibleForTesting;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastXmlSerializer;
import com.android.nfc.Utils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class is inspired by android.content.pm.RegisteredServicesCache
 * That class was not re-used because it doesn't support dynamically
 * registering additional properties, but generates everything from
 * the manifest. Since we have some properties that are not in the manifest,
 * it's less suited.
 */
public class RegisteredServicesCache {
    static final String XML_INDENT_OUTPUT_FEATURE = "http://xmlpull.org/v1/doc/features.html#indent-output";
    static final String TAG = "RegisteredServicesCache";
    static final String AID_XML_PATH = "dynamic_aids.xml";
    static final String OTHER_STATUS_PATH = "other_status.xml";
    static final String PACKAGE_DATA = "package";
    static final boolean DEBUG = NfcProperties.debug_enabled().orElse(true);
    private static final boolean VDBG = false; // turn on for local testing.

    final Context mContext;
    final AtomicReference<BroadcastReceiver> mReceiver;

    final Object mLock = new Object();
    // All variables below synchronized on mLock

    // mUserHandles holds the UserHandles of all the profiles that belong to current user
    @GuardedBy("mLock")
    List<UserHandle> mUserHandles;

    // mUserServices holds the card emulation services that are running for each user
    final SparseArray<UserServices> mUserServices = new SparseArray<UserServices>();
    final Callback mCallback;
    final SettingsFile mDynamicSettingsFile;
    final SettingsFile mOthersFile;
    final ServiceParser mServiceParser;
    final RoutingOptionManager mRoutingOptionManager;

    public interface Callback {
        /**
         * ServicesUpdated for specific userId.
         */
        void onServicesUpdated(int userId, List<ApduServiceInfo> services,
                boolean validateInstalled);
    };

    static class DynamicSettings {
        public final int uid;
        public final HashMap<String, AidGroup> aidGroups = new HashMap<>();
        public String offHostSE;
        public String shouldDefaultToObserveModeStr;

        DynamicSettings(int uid) {
            this.uid = uid;
        }
    };

    static class OtherServiceStatus {
        public final int uid;
        public boolean checked;

        OtherServiceStatus(int uid, boolean checked) {
            this.uid = uid;
            this.checked = checked;
        }
    };

    @VisibleForTesting
    static class UserServices {
        /**
         * All services that have registered
         */
        final HashMap<ComponentName, ApduServiceInfo> services =
                new HashMap<>(); // Re-built at run-time
        final HashMap<ComponentName, DynamicSettings> dynamicSettings =
                new HashMap<>(); // In memory cache of dynamic settings
        final HashMap<ComponentName, OtherServiceStatus> others =
                new HashMap<>();
    };

    @VisibleForTesting
    static class SettingsFile {
        final AtomicFile mFile;
        SettingsFile(Context context, String path) {
            File dir = context.getFilesDir();
            mFile = new AtomicFile(new File(dir, path));
        }

        boolean exists() {
            return mFile.getBaseFile().exists();
        }

        InputStream openRead() throws FileNotFoundException {
            return mFile.openRead();
        }

        void delete() {
            mFile.delete();
        }

        FileOutputStream startWrite() throws IOException {
            return mFile.startWrite();
        }

        void finishWrite(FileOutputStream fileOutputStream) {
            mFile.finishWrite(fileOutputStream);
        }

        void failWrite(FileOutputStream fileOutputStream) {
            mFile.failWrite(fileOutputStream);
        }

        File getBaseFile() {
            return mFile.getBaseFile();
        }
    }

    @VisibleForTesting
    interface ServiceParser {
        ApduServiceInfo parseApduService(PackageManager packageManager,
                                         ResolveInfo resolveInfo,
                                         boolean onHost) throws XmlPullParserException, IOException;
    }

    private static class RealServiceParser implements ServiceParser {

        @Override
        public ApduServiceInfo parseApduService(PackageManager packageManager,
                                                ResolveInfo resolveInfo, boolean onHost)
                throws XmlPullParserException, IOException {
            return new ApduServiceInfo(packageManager, resolveInfo, onHost);
        }
    }

    private UserServices findOrCreateUserLocked(int userId) {
        UserServices services = mUserServices.get(userId);
        if (services == null) {
            services = new UserServices();
            mUserServices.put(userId, services);
        }
        return services;
    }

    private int getProfileParentId(Context context, int userId) {
        UserManager um = context.getSystemService(UserManager.class);
        UserHandle uh = um.getProfileParent(UserHandle.of(userId));
        return uh == null ? userId : uh.getIdentifier();
    }

    private int getProfileParentId(int userId) {
        return getProfileParentId(mContext.createContextAsUser(
                UserHandle.of(userId), /*flags=*/0), userId);
    }

    public RegisteredServicesCache(Context context, Callback callback) {
        this(context, callback, new SettingsFile(context, AID_XML_PATH),
                new SettingsFile(context, OTHER_STATUS_PATH), new RealServiceParser(),
                RoutingOptionManager.getInstance());
    }

    @VisibleForTesting
    RegisteredServicesCache(Context context, Callback callback,
                                   RoutingOptionManager routingOptionManager) {
        this(context, callback, new SettingsFile(context, AID_XML_PATH),
                new SettingsFile(context, OTHER_STATUS_PATH), new RealServiceParser(),
                routingOptionManager);
    }

    @VisibleForTesting
    RegisteredServicesCache(Context context, Callback callback, SettingsFile dynamicSettings,
                            SettingsFile otherSettings, ServiceParser serviceParser,
                            RoutingOptionManager routingOptionManager) {
        mContext = context;
        mCallback = callback;
        mServiceParser = serviceParser;
        mRoutingOptionManager = routingOptionManager;

        synchronized (mLock) {
            refreshUserProfilesLocked(false);
        }

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                String action = intent.getAction();
                if (VDBG) Log.d(TAG, "Intent action: " + action);

                if (mRoutingOptionManager.isRoutingTableOverrided()) {
                    if (DEBUG) Log.d(TAG, "Routing table overrided. Skip invalidateCache()");
                }
                if (uid == -1) return;
                int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
                int currentUser = ActivityManager.getCurrentUser();
                if (currentUser != getProfileParentId(context, userId)) {
                    // Cache will automatically be updated on user switch
                    if (VDBG) Log.d(TAG, "Ignoring package change intent from non-current user");
                    return;
                }
                // If app not removed, check if the app has any valid CE services.
                if (!Intent.ACTION_PACKAGE_REMOVED.equals(action) &&
                        !Utils.hasCeServicesWithValidPermissions(mContext, intent, userId)) {
                    if (VDBG) Log.d(TAG, "Ignoring package change intent from non-CE app");
                    return;
                }
                boolean replaced = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        && (Intent.ACTION_PACKAGE_ADDED.equals(action)
                        || Intent.ACTION_PACKAGE_REMOVED.equals(action));
                if (!replaced) {
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                        invalidateCache(UserHandle.
                                getUserHandleForUid(uid).getIdentifier(), true);
                    } else {
                        invalidateCache(UserHandle.
                                getUserHandleForUid(uid).getIdentifier(), false);
                    }
                } else {
                    if (DEBUG) Log.d(TAG, "Ignoring package intent due to package being replaced.");
                }
            }
        };
        mReceiver = new AtomicReference<BroadcastReceiver>(receiver);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_FIRST_LAUNCH);
        intentFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        intentFilter.addDataScheme(PACKAGE_DATA);
        mContext.registerReceiverForAllUsers(mReceiver.get(), intentFilter, null, null);

        // Register for events related to sdcard operations
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiverForAllUsers(mReceiver.get(), sdFilter, null, null);

        mDynamicSettingsFile = dynamicSettings;
        mOthersFile = otherSettings;
    }

    void initialize() {
        synchronized (mLock) {
            readDynamicSettingsLocked();
            readOthersLocked();
            for (UserHandle uh : mUserHandles) {
                invalidateCache(uh.getIdentifier(), false);
            }
        }
    }

    public void onUserSwitched() {
        synchronized (mLock) {
            refreshUserProfilesLocked(false);
            invalidateCache(ActivityManager.getCurrentUser(), true);
        }
    }

    public void onManagedProfileChanged() {
        synchronized (mLock) {
            refreshUserProfilesLocked(true);
            invalidateCache(ActivityManager.getCurrentUser(), true);
        }
    }

    private void refreshUserProfilesLocked(boolean invalidateCache) {
        UserManager um = mContext.createContextAsUser(
                UserHandle.of(ActivityManager.getCurrentUser()), /*flags=*/0)
                .getSystemService(UserManager.class);
        mUserHandles = um.getEnabledProfiles();
        List<UserHandle> removeUserHandles = new ArrayList<UserHandle>();

        for (UserHandle uh : mUserHandles) {
            if (um.isQuietModeEnabled(uh)) {
                removeUserHandles.add(uh);
            }
        }
        mUserHandles.removeAll(removeUserHandles);
        if (invalidateCache) {
            for (UserHandle uh : mUserHandles) {
                invalidateCache(uh.getIdentifier(), false);
            }
        }
    }

    void dump(List<ApduServiceInfo> services) {
        for (ApduServiceInfo service : services) {
            if (DEBUG) Log.d(TAG, service.toString());
        }
    }

    void dump(ArrayList<ComponentName> services) {
        for (ComponentName service : services) {
            if (DEBUG) Log.d(TAG, service.toString());
        }
    }

    boolean containsServiceLocked(ArrayList<ApduServiceInfo> services, ComponentName serviceName) {
        for (ApduServiceInfo service : services) {
            if (service.getComponent().equals(serviceName)) return true;
        }
        return false;
    }

    public boolean hasService(int userId, ComponentName service) {
        return getService(userId, service) != null;
    }

    public ApduServiceInfo getService(int userId, ComponentName service) {
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);
            return userServices.services.get(service);
        }
    }

    public List<ApduServiceInfo> getServices(int userId) {
        final ArrayList<ApduServiceInfo> services = new ArrayList<ApduServiceInfo>();
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);
            services.addAll(userServices.services.values());
        }
        return services;
    }

    public List<ApduServiceInfo> getServicesForCategory(int userId, String category) {
        final ArrayList<ApduServiceInfo> services = new ArrayList<ApduServiceInfo>();
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);
            for (ApduServiceInfo service : userServices.services.values()) {
                if (service.hasCategory(category)) services.add(service);
            }
        }
        return services;
    }

    ArrayList<ApduServiceInfo> getInstalledServices(int userId) {
        PackageManager pm;
        try {
            pm = mContext.createPackageContextAsUser("android", 0,
                    UserHandle.of(userId)).getPackageManager();
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not create user package context");
            return null;
        }

        ArrayList<ApduServiceInfo> validServices = new ArrayList<ApduServiceInfo>();

        List<ResolveInfo> resolvedServices = new ArrayList<>(pm.queryIntentServicesAsUser(
                new Intent(HostApduService.SERVICE_INTERFACE),
                ResolveInfoFlags.of(PackageManager.GET_META_DATA), UserHandle.of(userId)));

        List<ResolveInfo> resolvedOffHostServices = pm.queryIntentServicesAsUser(
                new Intent(OffHostApduService.SERVICE_INTERFACE),
                ResolveInfoFlags.of(PackageManager.GET_META_DATA), UserHandle.of(userId));
        resolvedServices.addAll(resolvedOffHostServices);
        for (ResolveInfo resolvedService : resolvedServices) {
            try {
                boolean onHost = !resolvedOffHostServices.contains(resolvedService);
                ServiceInfo si = resolvedService.serviceInfo;
                ComponentName componentName = new ComponentName(si.packageName, si.name);
                // Check if the package exported the service in manifest
                if (!si.exported) {
                    Log.e(TAG, "Skipping application component " + componentName +
                            ": it must configured as exported");
                    continue;
                }
                // Check if the package holds the NFC permission
                if (pm.checkPermission(android.Manifest.permission.NFC, si.packageName) !=
                        PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Skipping application component " + componentName +
                            ": it must request the permission " +
                            android.Manifest.permission.NFC);
                    continue;
                }
                if (!android.Manifest.permission.BIND_NFC_SERVICE.equals(
                        si.permission)) {
                    Log.e(TAG, "Skipping APDU service " + componentName +
                            ": it does not require the permission " +
                            android.Manifest.permission.BIND_NFC_SERVICE);
                    continue;
                }
                ApduServiceInfo service = mServiceParser.parseApduService(pm, resolvedService,
                        onHost);
                if (service != null) {
                    validServices.add(service);
                }
            } catch (XmlPullParserException e) {
                Log.w(TAG, "Unable to load component info " + resolvedService.toString(), e);
            } catch (IOException e) {
                Log.w(TAG, "Unable to load component info " + resolvedService.toString(), e);
            }
        }

        return validServices;
    }

    /**
     * invalidateCache for specific userId.
     */
    public void invalidateCache(int userId, boolean validateInstalled) {
        final ArrayList<ApduServiceInfo> validServices = getInstalledServices(userId);
        if (validServices == null) {
            return;
        }
        ArrayList<ApduServiceInfo> toBeAdded = new ArrayList<>();
        ArrayList<ApduServiceInfo> toBeRemoved = new ArrayList<>();
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);

            // Find removed services
            Iterator<Map.Entry<ComponentName, ApduServiceInfo>> it =
                    userServices.services.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<ComponentName, ApduServiceInfo> entry =
                        (Map.Entry<ComponentName, ApduServiceInfo>) it.next();
                if (!containsServiceLocked(validServices, entry.getKey())) {
                    toBeRemoved.add(entry.getValue());
                    it.remove();
                }
            }
            for (ApduServiceInfo service : validServices) {
                toBeAdded.add(service);
                userServices.services.put(service.getComponent(), service);
            }

            // Apply dynamic settings mappings
            ArrayList<ComponentName> toBeRemovedComponent = new ArrayList<ComponentName>();
            for (Map.Entry<ComponentName, DynamicSettings> entry :
                    userServices.dynamicSettings.entrySet()) {
                // Verify component / uid match
                ComponentName component = entry.getKey();
                DynamicSettings dynamicSettings = entry.getValue();
                ApduServiceInfo serviceInfo = userServices.services.get(component);
                if (serviceInfo == null || (serviceInfo.getUid() != dynamicSettings.uid)) {
                    toBeRemovedComponent.add(component);
                    continue;
                } else {
                    for (AidGroup group : dynamicSettings.aidGroups.values()) {
                        serviceInfo.setDynamicAidGroup(group);
                    }
                    if (dynamicSettings.offHostSE != null) {
                        serviceInfo.setOffHostSecureElement(dynamicSettings.offHostSE);
                    }
                    if (dynamicSettings.shouldDefaultToObserveModeStr != null) {
                        serviceInfo.setShouldDefaultToObserveMode(
                                convertValueToBoolean(dynamicSettings.shouldDefaultToObserveModeStr,
                                false));
                    }
                }
            }
            if (toBeRemoved.size() > 0) {
                for (ComponentName component : toBeRemovedComponent) {
                    Log.d(TAG, "Removing dynamic AIDs registered by " + component);
                    userServices.dynamicSettings.remove(component);
                }
                // Persist to filesystem
                writeDynamicSettingsLocked();
            }
        }

        List<ApduServiceInfo> otherServices = getServicesForCategory(userId,
                CardEmulation.CATEGORY_OTHER);
        invalidateOther(userId, otherServices);

        mCallback.onServicesUpdated(userId, Collections.unmodifiableList(validServices),
                validateInstalled);
        if (VDBG) {
            Log.i(TAG, "Services => ");
            dump(validServices);
        } else {
            // dump only new services added or removed
            Log.i(TAG, "New Services => ");
            dump(toBeAdded);
            Log.i(TAG, "Removed Services => ");
            dump(toBeRemoved);
        }
    }

    private void invalidateOther(int userId, List<ApduServiceInfo> validOtherServices) {
        Log.d(TAG, "invalidate : " + userId);
        ArrayList<ComponentName> toBeAdded = new ArrayList<>();
        ArrayList<ComponentName> toBeRemoved = new ArrayList<>();
        // remove services
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);
            boolean needToWrite = false;
            Iterator<Map.Entry<ComponentName, OtherServiceStatus>> it =
                    userServices.others.entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry<ComponentName, OtherServiceStatus> entry = it.next();
                if (!containsServiceLocked((ArrayList<ApduServiceInfo>) validOtherServices,
                        entry.getKey())) {
                    toBeRemoved.add(entry.getKey());
                    needToWrite = true;
                    it.remove();
                }
            }

            UserManager um = mContext.createContextAsUser(
                            UserHandle.of(ActivityManager.getCurrentUser()), /*flags=*/0)
                    .getSystemService(UserManager.class);
            boolean isManagedProfile = um.isManagedProfile(userId);
            Log.i(TAG, "current user: " + ActivityManager.getCurrentUser() +
                    ", is managed profile : " + isManagedProfile );
            boolean isChecked = !(isManagedProfile);
            // TODO: b/313040065 temperatory set isChecked always true due to there's no UI in AOSP
            isChecked = true;

            for (ApduServiceInfo service : validOtherServices) {
                if (VDBG) {
                    Log.d(TAG, "update valid otherService: " + service.getComponent()
                            + " AIDs: " + service.getAids());
                }
                if (!service.hasCategory(CardEmulation.CATEGORY_OTHER)) {
                    Log.e(TAG, "service does not have other category");
                    continue;
                }

                ComponentName component = service.getComponent();
                OtherServiceStatus status = userServices.others.get(component);

                if (status == null) {
                    toBeAdded.add(service.getComponent());
                    status = new OtherServiceStatus(service.getUid(), isChecked);
                    needToWrite = true;
                }
                service.setCategoryOtherServiceEnabled(status.checked);
                userServices.others.put(component, status);
            }

            if (needToWrite) {
                writeOthersLocked();
            }
        }
        if (VDBG) {
            Log.i(TAG, "Other Services => ");
            dump(validOtherServices);
        } else {
            // dump only new services added or removed
            Log.i(TAG, "New Other Services => ");
            dump(toBeAdded);
            Log.i(TAG, "Removed Other Services => ");
            dump(toBeRemoved);
        }
    }

    private static final boolean convertValueToBoolean(CharSequence value, boolean defaultValue) {
       boolean result = false;

        if (TextUtils.isEmpty(value)) {
            return defaultValue;
        }

        if (value.equals("1")
        ||  value.equals("true")
        ||  value.equals("TRUE"))
            result = true;

        return result;
    }

    @VisibleForTesting
    static Map<Integer, List<Pair<ComponentName, DynamicSettings>>>
    readDynamicSettingsFromFile(SettingsFile settingsFile) {
        Log.d(TAG, "Reading dynamic AIDs.");
        Map<Integer, List<Pair<ComponentName, DynamicSettings>>> readSettingsMap =
                new HashMap<>();
        InputStream fis = null;
        try {
            if (!settingsFile.exists()) {
                Log.d(TAG, "Dynamic AIDs file does not exist.");
                return new HashMap<>();
            }
            fis = settingsFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }
            String tagName = parser.getName();
            if ("services".equals(tagName)) {
                boolean inService = false;
                ComponentName currentComponent = null;
                int currentUid = -1;
                String currentOffHostSE = null;
                String shouldDefaultToObserveModeStr = null;
                ArrayList<AidGroup> currentGroups = new ArrayList<AidGroup>();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    tagName = parser.getName();
                    if (eventType == XmlPullParser.START_TAG) {
                        if ("service".equals(tagName) && parser.getDepth() == 2) {
                            String compString = parser.getAttributeValue(null, "component");
                            String uidString = parser.getAttributeValue(null, "uid");
                            String offHostString
                                    = parser.getAttributeValue(null, "offHostSE");
                            shouldDefaultToObserveModeStr =
                                    parser.getAttributeValue(null, "shouldDefaultToObserveMode");
                            if (compString == null || uidString == null) {
                                Log.e(TAG, "Invalid service attributes");
                            } else {
                                try {
                                    currentUid = Integer.parseInt(uidString);
                                    currentComponent = ComponentName
                                            .unflattenFromString(compString);
                                    currentOffHostSE = offHostString;
                                    inService = true;
                                } catch (NumberFormatException e) {
                                    Log.e(TAG, "Could not parse service uid");
                                }
                            }
                        }
                        if ("aid-group".equals(tagName) && parser.getDepth() == 3 && inService) {
                            AidGroup group = AidGroup.createFromXml(parser);
                            if (group != null) {
                                currentGroups.add(group);
                            } else {
                                Log.e(TAG, "Could not parse AID group.");
                            }
                        }
                    } else if (eventType == XmlPullParser.END_TAG) {
                        if ("service".equals(tagName)) {
                            // See if we have a valid service
                            if (currentComponent != null && currentUid >= 0 &&
                                    (currentGroups.size() > 0 || currentOffHostSE != null)) {
                                final int userId = UserHandle.
                                        getUserHandleForUid(currentUid).getIdentifier();
                                Log.d(TAG, " ## user id - " + userId);
                                DynamicSettings dynSettings = new DynamicSettings(currentUid);
                                for (AidGroup group : currentGroups) {
                                    dynSettings.aidGroups.put(group.getCategory(), group);
                                }
                                dynSettings.offHostSE = currentOffHostSE;
                                dynSettings.shouldDefaultToObserveModeStr
                                        = shouldDefaultToObserveModeStr;
                                if (!readSettingsMap.containsKey(userId)) {
                                    readSettingsMap.put(userId, new ArrayList<>());
                                }
                                readSettingsMap.get(userId)
                                        .add(new Pair<>(currentComponent, dynSettings));
                            }
                            currentUid = -1;
                            currentComponent = null;
                            currentGroups.clear();
                            inService = false;
                            currentOffHostSE = null;
                        }
                    }
                    eventType = parser.next();
                };
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not parse dynamic AIDs file, trashing.", e);
            settingsFile.delete();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }
        return readSettingsMap;
    }

    private void readDynamicSettingsLocked() {
        Map<Integer, List<Pair<ComponentName, DynamicSettings>>> readSettingsMap
                = readDynamicSettingsFromFile(mDynamicSettingsFile);
        for(Integer userId: readSettingsMap.keySet()) {
            UserServices services = findOrCreateUserLocked(userId);
            List<Pair<ComponentName, DynamicSettings>> componentNameDynamicServiceStatusPairs
                    = readSettingsMap.get(userId);
            int pairsSize = componentNameDynamicServiceStatusPairs.size();
            for(int i = 0; i < pairsSize; i++) {
                Pair<ComponentName, DynamicSettings> pair
                        = componentNameDynamicServiceStatusPairs.get(i);
                services.dynamicSettings.put(pair.first, pair.second);
            }
        }
    }

    @VisibleForTesting
    static Map<Integer, List<Pair<ComponentName, OtherServiceStatus>>>
    readOtherFromFile(SettingsFile settingsFile) {
        Map<Integer, List<Pair<ComponentName, OtherServiceStatus>>> readSettingsMap =
                new HashMap<>();
        Log.d(TAG, "read others locked");

        InputStream fis = null;
        try {
            if (!settingsFile.exists()) {
                Log.d(TAG, "Other settings file does not exist.");
                return new HashMap<>();
            }
            fis = settingsFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }
            String tagName = parser.getName();
            if ("services".equals(tagName)) {
                boolean checked = false;
                ComponentName currentComponent = null;
                int currentUid = -1;

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    tagName = parser.getName();
                    if (eventType == XmlPullParser.START_TAG) {
                        if ("service".equals(tagName) && parser.getDepth() == 2) {
                            String compString = parser.getAttributeValue(null, "component");
                            String uidString = parser.getAttributeValue(null, "uid");
                            String checkedString = parser.getAttributeValue(null, "checked");
                            if (compString == null || uidString == null || checkedString == null) {
                                Log.e(TAG, "Invalid service attributes");
                            } else {
                                try {
                                    currentUid = Integer.parseInt(uidString);
                                    currentComponent =
                                            ComponentName.unflattenFromString(compString);
                                    checked = checkedString.equals("true") ? true : false;
                                } catch (NumberFormatException e) {
                                    Log.e(TAG, "Could not parse service uid");
                                }
                            }
                        }
                    } else if (eventType == XmlPullParser.END_TAG) {
                        if ("service".equals(tagName)) {
                            // See if we have a valid service
                            if (currentComponent != null && currentUid >= 0) {
                                Log.d(TAG, " end of service tag");
                                final int userId =
                                        UserHandle.getUserHandleForUid(currentUid).getIdentifier();
                                OtherServiceStatus status =
                                        new OtherServiceStatus(currentUid, checked);
                                Log.d(TAG, " ## user id - " + userId);
                                if (!readSettingsMap.containsKey(userId)) {
                                    readSettingsMap.put(userId, new ArrayList<>());
                                }
                                readSettingsMap.get(userId)
                                        .add(new Pair<>(currentComponent, status));
                            }
                            currentUid = -1;
                            currentComponent = null;
                            checked = false;
                        }
                    }
                    eventType = parser.next();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not parse others AIDs file, trashing.", e);
            settingsFile.delete();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    // It is safe to ignore I/O exceptions when closing FileInputStream
                }
            }
        }
        return readSettingsMap;
    }

    private void readOthersLocked() {
        Map<Integer, List<Pair<ComponentName, OtherServiceStatus>>> readSettingsMap
                = readOtherFromFile(mOthersFile);
        for(Integer userId: readSettingsMap.keySet()) {
            UserServices services = findOrCreateUserLocked(userId);
            List<Pair<ComponentName, OtherServiceStatus>> componentNameOtherServiceStatusPairs
                    = readSettingsMap.get(userId);
            int pairsSize = componentNameOtherServiceStatusPairs.size();
            for(int i = 0; i < pairsSize; i++) {
                Pair<ComponentName, OtherServiceStatus> pair
                        = componentNameOtherServiceStatusPairs.get(i);
                services.others.put(pair.first,
                        pair.second);
            }
        }
    }

    private boolean writeDynamicSettingsLocked() {
        FileOutputStream fos = null;
        try {
            fos = mDynamicSettingsFile.startWrite();
            XmlSerializer out = Xml.newSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);
            out.setFeature(XML_INDENT_OUTPUT_FEATURE, true);
            out.startTag(null, "services");
            for (int i = 0; i < mUserServices.size(); i++) {
                final UserServices user = mUserServices.valueAt(i);
                for (Map.Entry<ComponentName, DynamicSettings> service :
                        user.dynamicSettings.entrySet()) {
                    out.startTag(null, "service");
                    out.attribute(null, "component", service.getKey().flattenToString());
                    out.attribute(null, "uid", Integer.toString(service.getValue().uid));
                    if(service.getValue().offHostSE != null) {
                        out.attribute(null, "offHostSE", service.getValue().offHostSE);
                    }
                    if (service.getValue().shouldDefaultToObserveModeStr != null) {
                        out.attribute(null, "shouldDefaultToObserveMode",
                                service.getValue().shouldDefaultToObserveModeStr);
                    }
                    for (AidGroup group : service.getValue().aidGroups.values()) {
                        group.writeAsXml(out);
                    }
                    out.endTag(null, "service");
                }
            }
            out.endTag(null, "services");
            out.endDocument();
            mDynamicSettingsFile.finishWrite(fos);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error writing dynamic AIDs", e);
            if (fos != null) {
                mDynamicSettingsFile.failWrite(fos);
            }
            return false;
        }
    }

    private boolean writeOthersLocked() {
        Log.d(TAG, "write Others Locked()");

        FileOutputStream fos = null;
        try {
            fos = mOthersFile.startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);
            out.setFeature(XML_INDENT_OUTPUT_FEATURE, true);
            out.startTag(null, "services");

            Log.d(TAG, "userServices.size: " + mUserServices.size());
            for (int i = 0; i < mUserServices.size(); i++) {
                final UserServices user = mUserServices.valueAt(i);
                int userId = mUserServices.keyAt(i);
                // Checking for 1 times
                Log.d(TAG, "userId: " + userId);
                Log.d(TAG, "others size: " + user.others.size());
                ArrayList<ComponentName> currentService = new ArrayList<ComponentName>();
                for (Map.Entry<ComponentName, OtherServiceStatus> service :
                        user.others.entrySet()) {
                    Log.d(TAG, "component: " + service.getKey().flattenToString() +
                            ", checked: " + service.getValue().checked);

                    boolean hasDupe = false;
                    for (ComponentName cn : currentService) {
                        if (cn.equals(service.getKey())) {
                            hasDupe = true;
                            break;
                        }
                    }
                    if (hasDupe) {
                        continue;
                    } else {
                        Log.d(TAG, "Already written.");
                        currentService.add(service.getKey());
                    }

                    out.startTag(null, "service");
                    out.attribute(null, "component", service.getKey().flattenToString());
                    out.attribute(null, "uid", Integer.toString(service.getValue().uid));
                    out.attribute(null, "checked", Boolean.toString(service.getValue().checked));
                    out.endTag(null, "service");
                }
            }
            out.endTag(null, "services");
            out.endDocument();
            mOthersFile.finishWrite(fos);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error writing other status", e);
            if (fos != null) {
                mOthersFile.failWrite(fos);
            }
            return false;
        }
    }

    public boolean setOffHostSecureElement(int userId, int uid, ComponentName componentName,
            String offHostSE) {
        ArrayList<ApduServiceInfo> newServices = null;
        synchronized (mLock) {
            UserServices services = findOrCreateUserLocked(userId);
            // Check if we can find this service
            ApduServiceInfo serviceInfo = getService(userId, componentName);
            if (serviceInfo == null) {
                Log.e(TAG, "Service " + componentName + " does not exist.");
                return false;
            }
            if (serviceInfo.getUid() != uid) {
                // This is probably a good indication something is wrong here.
                // Either newer service installed with different uid (but then
                // we should have known about it), or somebody calling us from
                // a different uid.
                Log.e(TAG, "UID mismatch.");
                return false;
            }
            if (offHostSE == null || serviceInfo.isOnHost()) {
                Log.e(TAG, "OffHostSE mismatch with Service type");
                return false;
            }

            DynamicSettings dynSettings = services.dynamicSettings.get(componentName);
            if (dynSettings == null) {
                dynSettings = new DynamicSettings(uid);
            }
            dynSettings.offHostSE = offHostSE;
            boolean success = writeDynamicSettingsLocked();
            if (!success) {
                Log.e(TAG, "Failed to persist AID group.");
                dynSettings.offHostSE = null;
                return false;
            }

            serviceInfo.setOffHostSecureElement(offHostSE);
            newServices = new ArrayList<ApduServiceInfo>(services.services.values());
        }
        // Make callback without the lock held
        mCallback.onServicesUpdated(userId, newServices, true);
        return true;
    }

    public boolean resetOffHostSecureElement(int userId, int uid, ComponentName componentName) {
        ArrayList<ApduServiceInfo> newServices = null;
        synchronized (mLock) {
            UserServices services = findOrCreateUserLocked(userId);
            // Check if we can find this service
            ApduServiceInfo serviceInfo = getService(userId, componentName);
            if (serviceInfo == null) {
                Log.e(TAG, "Service " + componentName + " does not exist.");
                return false;
            }
            if (serviceInfo.getUid() != uid) {
                // This is probably a good indication something is wrong here.
                // Either newer service installed with different uid (but then
                // we should have known about it), or somebody calling us from
                // a different uid.
                Log.e(TAG, "UID mismatch.");
                return false;
            }
            if (serviceInfo.isOnHost() || serviceInfo.getOffHostSecureElement() == null) {
                Log.e(TAG, "OffHostSE is not set");
                return false;
            }

            DynamicSettings dynSettings = services.dynamicSettings.get(componentName);
            String offHostSE = dynSettings.offHostSE;
            dynSettings.offHostSE = null;
            boolean success = writeDynamicSettingsLocked();
            if (!success) {
                Log.e(TAG, "Failed to persist AID group.");
                dynSettings.offHostSE = offHostSE;
                return false;
            }

            serviceInfo.resetOffHostSecureElement();
            newServices = new ArrayList<ApduServiceInfo>(services.services.values());
        }
        // Make callback without the lock held
        mCallback.onServicesUpdated(userId, newServices, true);
        return true;
    }

    public boolean setShouldDefaultToObserveModeForService(int userId, int uid,
            ComponentName componentName, boolean enable) {
        synchronized (mLock) {
            UserServices services = findOrCreateUserLocked(userId);
            // Check if we can find this service
            ApduServiceInfo serviceInfo = getService(userId, componentName);
            if (serviceInfo == null) {
                Log.e(TAG, "Service " + componentName + " does not exist.");
                return false;
            }
            if (serviceInfo.getUid() != uid) {
                // This is probably a good indication something is wrong here.
                // Either newer service installed with different uid (but then
                // we should have known about it), or somebody calling us from
                // a different uid.
                Log.e(TAG, "UID mismatch.");
                return false;
            }
            serviceInfo.setShouldDefaultToObserveMode(enable);
            DynamicSettings dynSettings = services.dynamicSettings.get(componentName);
            if (dynSettings == null) {
                dynSettings = new DynamicSettings(uid);
                dynSettings.offHostSE = null;
                services.dynamicSettings.put(componentName, dynSettings);
            }
            dynSettings.shouldDefaultToObserveModeStr =  Boolean.toString(enable);
        }
        return true;
    }

    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public boolean registerPollingLoopFilterForService(int userId, int uid,
            ComponentName componentName, String pollingLoopFilter,
            boolean autoTransact) {
        ArrayList<ApduServiceInfo> newServices = null;
        synchronized (mLock) {
            UserServices services = findOrCreateUserLocked(userId);
            // Check if we can find this service
            ApduServiceInfo serviceInfo = getService(userId, componentName);
            if (serviceInfo == null) {
                Log.e(TAG, "Service " + componentName + " does not exist.");
                return false;
            }
            if (serviceInfo.getUid() != uid) {
                // This is probably a good indication something is wrong here.
                // Either newer service installed with different uid (but then
                // we should have known about it), or somebody calling us from
                // a different uid.
                Log.e(TAG, "UID mismatch.");
                return false;
            }
            if (!serviceInfo.isOnHost() && !autoTransact) {
                return false;
            }
            serviceInfo.addPollingLoopFilter(pollingLoopFilter, autoTransact);
            newServices = new ArrayList<ApduServiceInfo>(services.services.values());
        }
        mCallback.onServicesUpdated(userId, newServices, true);
        return true;
    }

    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public boolean removePollingLoopFilterForService(int userId, int uid,
            ComponentName componentName, String pollingLoopFilter) {
        ArrayList<ApduServiceInfo> newServices = null;
        synchronized (mLock) {
            UserServices services = findOrCreateUserLocked(userId);
            // Check if we can find this service
            ApduServiceInfo serviceInfo = getService(userId, componentName);
            if (serviceInfo == null) {
                Log.e(TAG, "Service " + componentName + " does not exist.");
                return false;
            }
            if (serviceInfo.getUid() != uid) {
                // This is probably a good indication something is wrong here.
                // Either newer service installed with different uid (but then
                // we should have known about it), or somebody calling us from
                // a different uid.
                Log.e(TAG, "UID mismatch.");
                return false;
            }
            serviceInfo.removePollingLoopFilter(pollingLoopFilter);
            newServices = new ArrayList<ApduServiceInfo>(services.services.values());
        }
        mCallback.onServicesUpdated(userId, newServices, true);
        return true;
    }

    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public boolean registerPollingLoopPatternFilterForService(int userId, int uid,
            ComponentName componentName, String pollingLoopPatternFilter,
            boolean autoTransact) {
        ArrayList<ApduServiceInfo> newServices = null;
        synchronized (mLock) {
            UserServices services = findOrCreateUserLocked(userId);
            // Check if we can find this service
            ApduServiceInfo serviceInfo = getService(userId, componentName);
            if (serviceInfo == null) {
                Log.e(TAG, "Service " + componentName + " does not exist.");
                return false;
            }
            if (serviceInfo.getUid() != uid) {
                // This is probably a good indication something is wrong here.
                // Either newer service installed with different uid (but then
                // we should have known about it), or somebody calling us from
                // a different uid.
                Log.e(TAG, "UID mismatch.");
                return false;
            }
            if (!serviceInfo.isOnHost() && !autoTransact) {
                return false;
            }
            serviceInfo.addPollingLoopPatternFilter(pollingLoopPatternFilter, autoTransact);
            newServices = new ArrayList<ApduServiceInfo>(services.services.values());
        }
        mCallback.onServicesUpdated(userId, newServices, true);
        return true;
    }

    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public boolean removePollingLoopPatternFilterForService(int userId, int uid,
            ComponentName componentName, String pollingLoopPatternFilter) {
        ArrayList<ApduServiceInfo> newServices = null;
        synchronized (mLock) {
            UserServices services = findOrCreateUserLocked(userId);
            // Check if we can find this service
            ApduServiceInfo serviceInfo = getService(userId, componentName);
            if (serviceInfo == null) {
                Log.e(TAG, "Service " + componentName + " does not exist.");
                return false;
            }
            if (serviceInfo.getUid() != uid) {
                // This is probably a good indication something is wrong here.
                // Either newer service installed with different uid (but then
                // we should have known about it), or somebody calling us from
                // a different uid.
                Log.e(TAG, "UID mismatch.");
                return false;
            }
            serviceInfo.removePollingLoopPatternFilter(pollingLoopPatternFilter);
            newServices = new ArrayList<ApduServiceInfo>(services.services.values());
        }
        mCallback.onServicesUpdated(userId, newServices, true);
        return true;
    }



    public boolean registerAidGroupForService(int userId, int uid,
            ComponentName componentName, AidGroup aidGroup) {
        ArrayList<ApduServiceInfo> newServices = null;
        boolean success;
        synchronized (mLock) {
            UserServices services = findOrCreateUserLocked(userId);
            // Check if we can find this service
            ApduServiceInfo serviceInfo = getService(userId, componentName);
            if (serviceInfo == null) {
                Log.e(TAG, "Service " + componentName + " does not exist.");
                return false;
            }
            if (serviceInfo.getUid() != uid) {
                // This is probably a good indication something is wrong here.
                // Either newer service installed with different uid (but then
                // we should have known about it), or somebody calling us from
                // a different uid.
                Log.e(TAG, "UID mismatch.");
                return false;
            }
            // Do another AID validation, since a caller could have thrown in a
            // modified AidGroup object with invalid AIDs over Binder.
            List<String> aids = aidGroup.getAids();
            for (String aid : aids) {
                if (!CardEmulation.isValidAid(aid)) {
                    Log.e(TAG, "AID " + aid + " is not a valid AID");
                    return false;
                }
            }
            serviceInfo.setDynamicAidGroup(aidGroup);
            DynamicSettings dynSettings = services.dynamicSettings.get(componentName);
            if (dynSettings == null) {
                dynSettings = new DynamicSettings(uid);
                dynSettings.offHostSE = null;
                services.dynamicSettings.put(componentName, dynSettings);
            }
            dynSettings.aidGroups.put(aidGroup.getCategory(), aidGroup);
            success = writeDynamicSettingsLocked();
            if (success) {
                newServices =
                    new ArrayList<ApduServiceInfo>(services.services.values());
            } else {
                Log.e(TAG, "Failed to persist AID group.");
                // Undo registration
                dynSettings.aidGroups.remove(aidGroup.getCategory());
            }
        }
        if (success) {
            // Make callback without the lock held
            mCallback.onServicesUpdated(userId, newServices, true);
        }
        return success;
    }

    public boolean registerOtherForService(int userId,
            ComponentName componentName, boolean checked) {
        if (DEBUG) Log.d(TAG, "[register other] checked:" + checked + ", "  + componentName);

        ArrayList<ApduServiceInfo> newServices = null;
        boolean success = false;

        synchronized (mLock) {

            Log.d(TAG, "registerOtherForService / ComponentName" + componentName);
            ApduServiceInfo serviceInfo = getService(userId, componentName);

            if (serviceInfo == null) {
                Log.e(TAG, "Service " + componentName + "does not exist");
                return false;
            }

            success = updateOtherServiceStatus(userId, serviceInfo, checked);

            if (success) {
                UserServices userService = findOrCreateUserLocked(userId);
                newServices = new ArrayList<ApduServiceInfo>(userService.services.values());
            } else {
                Log.e(TAG, "Fail to other checked");
            }
        }

        if (success) {
            if (DEBUG) Log.d(TAG, "other list update due to User Select " + componentName);
            mCallback.onServicesUpdated(userId, Collections.unmodifiableList(newServices),false);
        }

        return success;
    }

    public AidGroup getAidGroupForService(int userId, int uid, ComponentName componentName,
            String category) {
        ApduServiceInfo serviceInfo = getService(userId, componentName);
        if (serviceInfo != null) {
            if (serviceInfo.getUid() != uid) {
                Log.e(TAG, "UID mismatch");
                return null;
            }
            return serviceInfo.getDynamicAidGroupForCategory(category);
        } else {
            Log.e(TAG, "Could not find service " + componentName);
            return null;
        }
    }

    public boolean removeAidGroupForService(int userId, int uid, ComponentName componentName,
            String category) {
        boolean success = false;
        ArrayList<ApduServiceInfo> newServices = null;
        synchronized (mLock) {
            UserServices services = findOrCreateUserLocked(userId);
            ApduServiceInfo serviceInfo = getService(userId, componentName);
            if (serviceInfo != null) {
                if (serviceInfo.getUid() != uid) {
                    // Calling from different uid
                    Log.e(TAG, "UID mismatch");
                    return false;
                }
                if (!serviceInfo.removeDynamicAidGroupForCategory(category)) {
                    Log.e(TAG," Could not find dynamic AIDs for category " + category);
                    return false;
                }
                // Remove from local cache
                DynamicSettings dynSettings = services.dynamicSettings.get(componentName);
                if (dynSettings != null) {
                    AidGroup deletedGroup = dynSettings.aidGroups.remove(category);
                    success = writeDynamicSettingsLocked();
                    if (success) {
                        newServices = new ArrayList<ApduServiceInfo>(services.services.values());
                    } else {
                        Log.e(TAG, "Could not persist deleted AID group.");
                        dynSettings.aidGroups.put(category, deletedGroup);
                        return false;
                    }
                } else {
                    Log.e(TAG, "Could not find aid group in local cache.");
                }
            } else {
                Log.e(TAG, "Service " + componentName + " does not exist.");
            }
        }
        if (success) {
            mCallback.onServicesUpdated(userId, newServices, true);
        }
        return success;
    }

    boolean doesServiceShouldDefaultToObserveMode(int userId, ComponentName service) {
        UserServices services = findOrCreateUserLocked(userId);
        ApduServiceInfo serviceInfo = services.services.get(service);
        if (serviceInfo == null) {
            Log.d(TAG, "serviceInfo is null");
            return false;
        }
        return serviceInfo.shouldDefaultToObserveMode();
    }

    private boolean updateOtherServiceStatus(int userId, ApduServiceInfo service, boolean checked) {
        UserServices userServices = findOrCreateUserLocked(userId);

        OtherServiceStatus status = userServices.others.get(service.getComponent());
        // This is Error handling code if otherServiceStatus is null
        if (status == null) {
            Log.d(TAG, service.getComponent() + " status is could not be null");
            return false;
        }

        if (service.isCategoryOtherServiceEnabled() == checked) {
            Log.d(TAG, "already same status: " + checked);
            return false;
        }

        service.setCategoryOtherServiceEnabled(checked);
        status.checked = checked;

        return writeOthersLocked();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Registered HCE services for current user: ");
        ParcelFileDescriptor pFd;
        try {
            pFd = ParcelFileDescriptor.dup(fd);
            synchronized (mLock) {
                for (UserHandle uh : mUserHandles) {
                    UserManager um = mContext.createContextAsUser(
                            uh, /*flags=*/0).getSystemService(UserManager.class);
                    pw.println("User " + Utils.maskSubstring(um.getUserName(), 3));
                    UserServices userServices = findOrCreateUserLocked(uh.getIdentifier());
                    for (ApduServiceInfo service : userServices.services.values()) {
                        service.dump(pFd, pw, args);
                        pw.println("");
                    }
                    pw.println("");
                }
            }
            pFd.close();
        } catch (IOException e) {
            pw.println("Failed to dump HCE services: " + e);
        }
    }

    /**
     * Dump debugging information as a RegisteredServicesCacheProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/card_emulation.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    void dumpDebug(ProtoOutputStream proto) {
        UserServices userServices = findOrCreateUserLocked(ActivityManager.getCurrentUser());
        for (ApduServiceInfo service : userServices.services.values()) {
            long token = proto.start(RegisteredServicesCacheProto.APDU_SERVICE_INFOS);
            service.dumpDebug(proto);
            proto.end(token);
        }
    }
}
