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
*  Copyright 2018-2021 NXP
*
******************************************************************************/
package com.android.nfc.cardemulation;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.nfc.cardemulation.AidGroup;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.nfc.cardemulation.OffHostApduService;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;
import com.nxp.nfc.NfcConstants;

import com.android.internal.util.FastXmlSerializer;
import com.google.android.collect.Maps;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.android.nfc.NfcService;
import android.os.SystemProperties;

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
    static final boolean DEBUG =
        ((SystemProperties.get("persist.nfc.ce_debug").equals("1")) ? true : false);
    static final String SERVICE_STATE_FILE_VERSION="1.0";

    final Context mContext;
    final AtomicReference<BroadcastReceiver> mReceiver;

    final Object mLock = new Object();
    // All variables below synchronized on mLock

    // mUserServices holds the card emulation services that are running for each user
    final SparseArray<UserServices> mUserServices = new SparseArray<UserServices>();
    final Callback mCallback;
    final AtomicFile mDynamicSettingsFile;
    final AtomicFile mServiceStateFile;
    HashMap<String, HashMap<ComponentName, Integer>> installedServices = new HashMap<>();

    public interface Callback {
        void onServicesUpdated(int userId, final List<ApduServiceInfo> services);
    };

    static class DynamicSettings {
        public final int uid;
        public final HashMap<String, AidGroup> aidGroups = Maps.newHashMap();
        public String offHostSE;

        DynamicSettings(int uid) {
            this.uid = uid;
        }
    };

    private static class UserServices {
        /**
         * All services that have registered
         */
        final HashMap<ComponentName, ApduServiceInfo> services =
                Maps.newHashMap(); // Re-built at run-time
        final HashMap<ComponentName, DynamicSettings> dynamicSettings =
                Maps.newHashMap(); // In memory cache of dynamic settings
    };

    private UserServices findOrCreateUserLocked(int userId) {
        UserServices services = mUserServices.get(userId);
        if (services == null) {
            services = new UserServices();
            mUserServices.put(userId, services);
        }
        return services;
    }

    public RegisteredServicesCache(Context context, Callback callback) {
        mContext = context;
        mCallback = callback;

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                String action = intent.getAction();
                if (DEBUG) Log.d(TAG, "Intent action: " + action);
                if (uid != -1) {
                    int currentUser = ActivityManager.getCurrentUser();
                    if (currentUser == UserHandle.getUserId(uid)) {
                        if(Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                            Uri uri = intent.getData();
                            String pkg = uri != null ? uri.getSchemeSpecificPart() : null;
                        }
                        boolean replaced = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) &&
                                (Intent.ACTION_PACKAGE_ADDED.equals(action) ||
                                 Intent.ACTION_PACKAGE_REMOVED.equals(action));
                        if (!replaced) {
                        invalidateCache(UserHandle.getUserId(uid));
                        } else {
                            // Cache will automatically be updated on user switch
                            if (DEBUG) Log.d(TAG, " Not removing service here " + replaced);
                        }
                    } else {
                        if (DEBUG) Log.d(TAG, "Ignoring package intent due to package being replaced.");
                    }
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
        intentFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(mReceiver.get(), UserHandle.ALL, intentFilter, null, null);

        // Register for events related to sdcard operations
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiverAsUser(mReceiver.get(), UserHandle.ALL, sdFilter, null, null);

        File dataDir = mContext.getFilesDir();
        mDynamicSettingsFile = new AtomicFile(new File(dataDir, "dynamic_aids.xml"));
        mServiceStateFile = new AtomicFile(new File(dataDir, "service_state.xml"));
    }

    void initialize() {
        synchronized (mLock) {
            readDynamicSettingsLocked();
        }
        invalidateCache(ActivityManager.getCurrentUser());
    }

    void dump(ArrayList<ApduServiceInfo> services) {
        for (ApduServiceInfo service : services) {
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
                    new UserHandle(userId)).getPackageManager();
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not create user package context");
            return null;
        }

        ArrayList<ApduServiceInfo> validServices = new ArrayList<ApduServiceInfo>();

        List<ResolveInfo> resolvedServices = new ArrayList<>(pm.queryIntentServicesAsUser(
                new Intent(HostApduService.SERVICE_INTERFACE),
                PackageManager.GET_META_DATA, userId));

        List<ResolveInfo> resolvedOffHostServices = pm.queryIntentServicesAsUser(
                new Intent(OffHostApduService.SERVICE_INTERFACE),
                PackageManager.GET_META_DATA, userId);
        resolvedServices.addAll(resolvedOffHostServices);

        for (ResolveInfo resolvedService : resolvedServices) {
            try {
                boolean onHost = !resolvedOffHostServices.contains(resolvedService);
                ServiceInfo si = resolvedService.serviceInfo;
                ComponentName componentName = new ComponentName(si.packageName, si.name);
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
                ApduServiceInfo service = new ApduServiceInfo(pm, resolvedService, onHost);
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

    public void invalidateCache(int userId) {
        final ArrayList<ApduServiceInfo> validServices = getInstalledServices(userId);
        if (validServices == null) {
            return;
        }
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);

            // Find removed services
            Iterator<Map.Entry<ComponentName, ApduServiceInfo>> it =
                    userServices.services.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<ComponentName, ApduServiceInfo> entry =
                        (Map.Entry<ComponentName, ApduServiceInfo>) it.next();
                if (!containsServiceLocked(validServices, entry.getKey())) {
                    Log.d(TAG, "Service removed: " + entry.getKey());
                    it.remove();
                }
            }
            for (ApduServiceInfo service : validServices) {
                if (DEBUG) Log.d(TAG, "Adding service: " + service.getComponent() +
                        " AIDs: " + service.getAids());
                userServices.services.put(service.getComponent(), service);
            }

            // Apply dynamic settings mappings
            ArrayList<ComponentName> toBeRemoved = new ArrayList<ComponentName>();
            for (Map.Entry<ComponentName, DynamicSettings> entry :
                    userServices.dynamicSettings.entrySet()) {
                // Verify component / uid match
                ComponentName component = entry.getKey();
                DynamicSettings dynamicSettings = entry.getValue();
                ApduServiceInfo serviceInfo = userServices.services.get(component);
                if (serviceInfo == null || (serviceInfo.getUid() != dynamicSettings.uid)) {
                    toBeRemoved.add(component);
                    continue;
                } else {
                    for (AidGroup group : dynamicSettings.aidGroups.values()) {
                        serviceInfo.setOrReplaceDynamicAidGroup(group);
                    }
                    if (dynamicSettings.offHostSE != null) {
                        serviceInfo.setOffHostSecureElement(dynamicSettings.offHostSE);
                    }
                }
            }
            if (toBeRemoved.size() > 0) {
                for (ComponentName component : toBeRemoved) {
                    Log.d(TAG, "Removing dynamic AIDs registered by " + component);
                    userServices.dynamicSettings.remove(component);
                }
                // Persist to filesystem
                writeDynamicSettingsLocked();
            }
        }
        mCallback.onServicesUpdated(userId, Collections.unmodifiableList(validServices));
        dump(validServices);
    }

    private void readDynamicSettingsLocked() {
        FileInputStream fis = null;
        try {
            if (!mDynamicSettingsFile.getBaseFile().exists()) {
                Log.d(TAG, "Dynamic AIDs file does not exist.");
                return;
            }
            fis = mDynamicSettingsFile.openRead();
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
                ArrayList<AidGroup> currentGroups = new ArrayList<AidGroup>();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    tagName = parser.getName();
                    if (eventType == XmlPullParser.START_TAG) {
                        if ("service".equals(tagName) && parser.getDepth() == 2) {
                            String compString = parser.getAttributeValue(null, "component");
                            String uidString = parser.getAttributeValue(null, "uid");
                            String offHostString = parser.getAttributeValue(null, "offHostSE");
                            if (compString == null || uidString == null) {
                                Log.e(TAG, "Invalid service attributes");
                            } else {
                                try {
                                    currentUid = Integer.parseInt(uidString);
                                    currentComponent = ComponentName.unflattenFromString(compString);
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
                                final int userId = UserHandle.getUserId(currentUid);
                                DynamicSettings dynSettings = new DynamicSettings(currentUid);
                                for (AidGroup group : currentGroups) {
                                    dynSettings.aidGroups.put(group.getCategory(), group);
                                }
                                dynSettings.offHostSE = currentOffHostSE;
                                UserServices services = findOrCreateUserLocked(userId);
                                services.dynamicSettings.put(currentComponent, dynSettings);
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
            Log.e(TAG, "Could not parse dynamic AIDs file, trashing.");
            mDynamicSettingsFile.delete();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private boolean writeDynamicSettingsLocked() {
        FileOutputStream fos = null;
        try {
            fos = mDynamicSettingsFile.startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);
            out.setFeature(XML_INDENT_OUTPUT_FEATURE, true);
            out.startTag(null, "services");
            for (int i = 0; i < mUserServices.size(); i++) {
                final UserServices user = mUserServices.valueAt(i);
                for (Map.Entry<ComponentName, DynamicSettings> service : user.dynamicSettings.entrySet()) {
                    out.startTag(null, "service");
                    out.attribute(null, "component", service.getKey().flattenToString());
                    out.attribute(null, "uid", Integer.toString(service.getValue().uid));
                    if(service.getValue().offHostSE != null) {
                        out.attribute(null, "offHostSE", service.getValue().offHostSE);
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
        mCallback.onServicesUpdated(userId, newServices);
        return true;
    }

    public boolean unsetOffHostSecureElement(int userId, int uid, ComponentName componentName) {
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
            serviceInfo.unsetOffHostSecureElement();
            DynamicSettings dynSettings = services.dynamicSettings.get(componentName);
            if (dynSettings != null) {
              String offHostSE = dynSettings.offHostSE;
              dynSettings.offHostSE = serviceInfo.getOffHostSecureElement();
              boolean success = writeDynamicSettingsLocked();
              if (!success) {
                Log.e(TAG, "Failed to persist AID group.");
                dynSettings.offHostSE = offHostSE;
                return false;
              }
            }

            newServices = new ArrayList<ApduServiceInfo>(services.services.values());
        }
        // Make callback without the lock held
        mCallback.onServicesUpdated(userId, newServices);
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
            serviceInfo.setOrReplaceDynamicAidGroup(aidGroup);
            DynamicSettings dynSettings = services.dynamicSettings.get(componentName);
            if (dynSettings == null) {
                dynSettings = new DynamicSettings(uid);
                dynSettings.offHostSE = serviceInfo.getOffHostSecureElement();
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
            mCallback.onServicesUpdated(userId, newServices);
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
            mCallback.onServicesUpdated(userId, newServices);
        }
        return success;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Registered HCE services for current user: ");
        UserServices userServices = findOrCreateUserLocked(ActivityManager.getCurrentUser());
        for (ApduServiceInfo service : userServices.services.values()) {
            service.dump(fd, pw, args);
            pw.println("");
        }
        pw.println("");
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
