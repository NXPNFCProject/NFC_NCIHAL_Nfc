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
*  Copyright 2019 NXP
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
import android.nfc.cardemulation.NfcFServiceInfo;
import android.nfc.cardemulation.NfcFCardEmulation;
import android.nfc.cardemulation.HostNfcFService;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

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
import android.os.SystemProperties;

public class RegisteredNfcFServicesCache {
    static final String XML_INDENT_OUTPUT_FEATURE = "http://xmlpull.org/v1/doc/features.html#indent-output";
    static final String TAG = "RegisteredNfcFServicesCache";
    static final boolean DBG = ((SystemProperties.get("persist.nfc.ce_debug").equals("1")) ? true : false);

    final Context mContext;
    final AtomicReference<BroadcastReceiver> mReceiver;

    final Object mLock = new Object();
    // All variables below synchronized on mLock

    // mUserServices holds the card emulation services that are running for each user
    final SparseArray<UserServices> mUserServices = new SparseArray<UserServices>();
    final Callback mCallback;
    final AtomicFile mDynamicSystemCodeNfcid2File;
    boolean mActivated = false;
    boolean mUserSwitched = false;

    public interface Callback {
        void onNfcFServicesUpdated(int userId, final List<NfcFServiceInfo> services);
    };

    static class DynamicSystemCode {
        public final int uid;
        public final String systemCode;

        DynamicSystemCode(int uid, String systemCode) {
            this.uid = uid;
            this.systemCode = systemCode;
        }
    };

    static class DynamicNfcid2 {
        public final int uid;
        public final String nfcid2;

        DynamicNfcid2(int uid, String nfcid2) {
            this.uid = uid;
            this.nfcid2 = nfcid2;
        }
    };

    private static class UserServices {
        /**
         * All services that have registered
         */
        final HashMap<ComponentName, NfcFServiceInfo> services =
                Maps.newHashMap(); // Re-built at run-time
        final HashMap<ComponentName, DynamicSystemCode> dynamicSystemCode =
                Maps.newHashMap(); // In memory cache of dynamic System Code store
        final HashMap<ComponentName, DynamicNfcid2> dynamicNfcid2 =
                Maps.newHashMap(); // In memory cache of dynamic NFCID2 store
    };

    private UserServices findOrCreateUserLocked(int userId) {
        UserServices userServices = mUserServices.get(userId);
        if (userServices == null) {
            userServices = new UserServices();
            mUserServices.put(userId, userServices);
        }
        return userServices;
    }

    public RegisteredNfcFServicesCache(Context context, Callback callback) {
        mContext = context;
        mCallback = callback;

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                String action = intent.getAction();
                if (DBG) Log.d(TAG, "Intent action: " + action);
                if (uid != -1) {
                    boolean replaced = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) &&
                            (Intent.ACTION_PACKAGE_ADDED.equals(action) ||
                             Intent.ACTION_PACKAGE_REMOVED.equals(action));
                    if (!replaced) {
                        int currentUser = ActivityManager.getCurrentUser();
                        if (currentUser == UserHandle.getUserId(uid)) {
                            invalidateCache(UserHandle.getUserId(uid));
                        } else {
                            // Cache will automatically be updated on user switch
                        }
                    } else {
                        if (DBG) Log.d(TAG,
                                "Ignoring package intent due to package being replaced.");
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
        mDynamicSystemCodeNfcid2File =
                new AtomicFile(new File(dataDir, "dynamic_systemcode_nfcid2.xml"));
    }

    void initialize() {
        synchronized (mLock) {
            readDynamicSystemCodeNfcid2Locked();
        }
        invalidateCache(ActivityManager.getCurrentUser());
    }

    void dump(ArrayList<NfcFServiceInfo> services) {
        for (NfcFServiceInfo service : services) {
            Log.d(TAG, service.toString());
        }
    }

    boolean containsServiceLocked(ArrayList<NfcFServiceInfo> services,
            ComponentName componentName) {
        for (NfcFServiceInfo service : services) {
            if (service.getComponent().equals(componentName)) return true;
        }
        return false;
    }

    public boolean hasService(int userId, ComponentName componentName) {
        return getService(userId, componentName) != null;
    }

    public NfcFServiceInfo getService(int userId, ComponentName componentName) {
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);
            return userServices.services.get(componentName);
        }
    }

    public List<NfcFServiceInfo> getServices(int userId) {
        final ArrayList<NfcFServiceInfo> services = new ArrayList<NfcFServiceInfo>();
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);
            services.addAll(userServices.services.values());
        }
        return services;
    }

    ArrayList<NfcFServiceInfo> getInstalledServices(int userId) {
        if (DBG) Log.d(TAG, "getInstalledServices");
        PackageManager pm;
        try {
            pm = mContext.createPackageContextAsUser("android", 0,
                    new UserHandle(userId)).getPackageManager();
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not create user package context");
            return null;
        }

        ArrayList<NfcFServiceInfo> validServices = new ArrayList<NfcFServiceInfo>();

        List<ResolveInfo> resolvedServices = pm.queryIntentServicesAsUser(
                new Intent(HostNfcFService.SERVICE_INTERFACE),
                PackageManager.GET_META_DATA, userId);

        for (ResolveInfo resolvedService : resolvedServices) {
            try {
                ServiceInfo si = resolvedService.serviceInfo;
                ComponentName componentName = new ComponentName(si.packageName, si.name);
                // Check if the package holds the NFC permission
                if (pm.checkPermission(android.Manifest.permission.NFC, si.packageName) !=
                        PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Skipping NfcF service " + componentName +
                            ": it does not require the permission " +
                            android.Manifest.permission.NFC);
                    continue;
                }
                if (!android.Manifest.permission.BIND_NFC_SERVICE.equals(
                        si.permission)) {
                    Log.e(TAG, "Skipping NfcF service " + componentName +
                            ": it does not require the permission " +
                            android.Manifest.permission.BIND_NFC_SERVICE);
                    continue;
                }
                NfcFServiceInfo service = new NfcFServiceInfo(pm, resolvedService);
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
        if (DBG) Log.d(TAG, "invalidateCache");
        final ArrayList<NfcFServiceInfo> validServices = getInstalledServices(userId);
        if (validServices == null) {
            return;
        }
        ArrayList<NfcFServiceInfo> newServices = null;
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);

            // Check update
            ArrayList<NfcFServiceInfo> cachedServices =
                    new ArrayList<NfcFServiceInfo>(userServices.services.values());
            ArrayList<NfcFServiceInfo> toBeAdded = new ArrayList<NfcFServiceInfo>();
            ArrayList<NfcFServiceInfo> toBeRemoved = new ArrayList<NfcFServiceInfo>();
            boolean matched = false;
            for (NfcFServiceInfo validService : validServices) {
                for (NfcFServiceInfo cachedService : cachedServices) {
                    if (validService.equals(cachedService)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    toBeAdded.add(validService);
                }
                matched = false;
            }
            for (NfcFServiceInfo cachedService : cachedServices) {
                for (NfcFServiceInfo validService : validServices) {
                    if (cachedService.equals(validService)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    toBeRemoved.add(cachedService);
                }
                matched = false;
            }
            if (mUserSwitched) {
                Log.d(TAG, "User switched, rebuild internal cache");
                mUserSwitched = false;
            } else if (toBeAdded.size() == 0 && toBeRemoved.size() == 0) {
                Log.d(TAG, "Service unchanged, not updating");
                return;
            }

            // Update cache
            for (NfcFServiceInfo service : toBeAdded) {
                userServices.services.put(service.getComponent(), service);
                if (DBG) Log.d(TAG, "Added service: " + service.getComponent());
            }
            for (NfcFServiceInfo service : toBeRemoved) {
                userServices.services.remove(service.getComponent());
                if (DBG) Log.d(TAG, "Removed service: " + service.getComponent());
            }
            // Apply dynamic System Code mappings
            ArrayList<ComponentName> toBeRemovedDynamicSystemCode =
                    new ArrayList<ComponentName>();
            for (Map.Entry<ComponentName, DynamicSystemCode> entry :
                    userServices.dynamicSystemCode.entrySet()) {
                // Verify component / uid match
                ComponentName componentName = entry.getKey();
                DynamicSystemCode dynamicSystemCode = entry.getValue();
                NfcFServiceInfo service = userServices.services.get(componentName);
                if (service == null || (service.getUid() != dynamicSystemCode.uid)) {
                    toBeRemovedDynamicSystemCode.add(componentName);
                    continue;
                } else {
                    service.setOrReplaceDynamicSystemCode(dynamicSystemCode.systemCode);
                }
            }
            // Apply dynamic NFCID2 mappings
            ArrayList<ComponentName> toBeRemovedDynamicNfcid2 =
                    new ArrayList<ComponentName>();
            for (Map.Entry<ComponentName, DynamicNfcid2> entry :
                    userServices.dynamicNfcid2.entrySet()) {
                // Verify component / uid match
                ComponentName componentName = entry.getKey();
                DynamicNfcid2 dynamicNfcid2 = entry.getValue();
                NfcFServiceInfo service = userServices.services.get(componentName);
                if (service == null || (service.getUid() != dynamicNfcid2.uid)) {
                    toBeRemovedDynamicNfcid2.add(componentName);
                    continue;
                } else {
                    service.setOrReplaceDynamicNfcid2(dynamicNfcid2.nfcid2);
                }
            }
            for (ComponentName removedComponent : toBeRemovedDynamicSystemCode) {
                Log.d(TAG, "Removing dynamic System Code registered by " +
                        removedComponent);
                userServices.dynamicSystemCode.remove(removedComponent);
            }
            for (ComponentName removedComponent : toBeRemovedDynamicNfcid2) {
                Log.d(TAG, "Removing dynamic NFCID2 registered by " +
                        removedComponent);
                userServices.dynamicNfcid2.remove(removedComponent);
            }
            // Assign a NFCID2 for services requesting a random NFCID2, then apply
            boolean nfcid2Assigned = false;
            for (Map.Entry<ComponentName, NfcFServiceInfo> entry :
                userServices.services.entrySet()) {
                NfcFServiceInfo service = entry.getValue();
                if (service.getNfcid2().equalsIgnoreCase("RANDOM")) {
                    String randomNfcid2 = generateRandomNfcid2();
                    service.setOrReplaceDynamicNfcid2(randomNfcid2);
                    DynamicNfcid2 dynamicNfcid2 =
                            new DynamicNfcid2(service.getUid(), randomNfcid2);
                    userServices.dynamicNfcid2.put(entry.getKey(), dynamicNfcid2);
                    nfcid2Assigned = true;
                }
            }

            // Persist to filesystem
            if (toBeRemovedDynamicSystemCode.size() > 0 ||
                    toBeRemovedDynamicNfcid2.size() > 0 ||
                    nfcid2Assigned) {
                writeDynamicSystemCodeNfcid2Locked();
            }

            newServices = new ArrayList<NfcFServiceInfo>(userServices.services.values());
        }
        mCallback.onNfcFServicesUpdated(userId, Collections.unmodifiableList(newServices));
        if (DBG) dump(newServices);
    }

    private void readDynamicSystemCodeNfcid2Locked() {
        if (DBG) Log.d(TAG, "readDynamicSystemCodeNfcid2Locked");
        FileInputStream fis = null;
        try {
            if (!mDynamicSystemCodeNfcid2File.getBaseFile().exists()) {
                Log.d(TAG, "Dynamic System Code, NFCID2 file does not exist.");
                return;
            }
            fis = mDynamicSystemCodeNfcid2File.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }
            String tagName = parser.getName();
            if ("services".equals(tagName)) {
                ComponentName componentName = null;
                int currentUid = -1;
                String systemCode = null;
                String nfcid2 = null;
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    tagName = parser.getName();
                    if (eventType == XmlPullParser.START_TAG) {
                        if ("service".equals(tagName) && parser.getDepth() == 2) {
                            String compString =
                                    parser.getAttributeValue(null, "component");
                            String uidString =
                                    parser.getAttributeValue(null, "uid");
                            String systemCodeString =
                                    parser.getAttributeValue(null, "system-code");
                            String nfcid2String =
                                    parser.getAttributeValue(null, "nfcid2");
                            if (compString == null || uidString == null) {
                                Log.e(TAG, "Invalid service attributes");
                            } else {
                                try {
                                    componentName = ComponentName.unflattenFromString(compString);
                                    currentUid = Integer.parseInt(uidString);
                                    systemCode = systemCodeString;
                                    nfcid2 = nfcid2String;
                                } catch (NumberFormatException e) {
                                    Log.e(TAG, "Could not parse service uid");
                                }
                            }
                        }
                    } else if (eventType == XmlPullParser.END_TAG) {
                        if ("service".equals(tagName)) {
                            // See if we have a valid service
                            if (componentName != null && currentUid >= 0) {
                                final int userId = UserHandle.getUserId(currentUid);
                                UserServices userServices = findOrCreateUserLocked(userId);
                                if (systemCode != null) {
                                    DynamicSystemCode dynamicSystemCode =
                                            new DynamicSystemCode(currentUid, systemCode);
                                    userServices.dynamicSystemCode.put(
                                            componentName, dynamicSystemCode);
                                }
                                if (nfcid2 != null) {
                                    DynamicNfcid2 dynamicNfcid2 =
                                            new DynamicNfcid2(currentUid, nfcid2);
                                    userServices.dynamicNfcid2.put(
                                            componentName, dynamicNfcid2);
                                }
                            }
                            componentName = null;
                            currentUid = -1;
                            systemCode = null;
                            nfcid2 = null;
                        }
                    }
                    eventType = parser.next();
                };
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not parse dynamic System Code, NFCID2 file, trashing.");
            mDynamicSystemCodeNfcid2File.delete();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private boolean writeDynamicSystemCodeNfcid2Locked() {
        if (DBG) Log.d(TAG, "writeDynamicSystemCodeNfcid2Locked");
        FileOutputStream fos = null;
        try {
            fos = mDynamicSystemCodeNfcid2File.startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);
            out.setFeature(XML_INDENT_OUTPUT_FEATURE, true);
            out.startTag(null, "services");
            for (int i = 0; i < mUserServices.size(); i++) {
                final UserServices userServices = mUserServices.valueAt(i);
                for (Map.Entry<ComponentName, DynamicSystemCode> entry :
                        userServices.dynamicSystemCode.entrySet()) {
                    out.startTag(null, "service");
                    out.attribute(null, "component", entry.getKey().flattenToString());
                    out.attribute(null, "uid", Integer.toString(entry.getValue().uid));
                    out.attribute(null, "system-code", entry.getValue().systemCode);
                    if (userServices.dynamicNfcid2.containsKey(entry.getKey())) {
                        out.attribute(null, "nfcid2",
                                userServices.dynamicNfcid2.get(entry.getKey()).nfcid2);
                    }
                    out.endTag(null, "service");
                }
                for (Map.Entry<ComponentName, DynamicNfcid2> entry :
                        userServices.dynamicNfcid2.entrySet()) {
                    if (!userServices.dynamicSystemCode.containsKey(entry.getKey())) {
                        out.startTag(null, "service");
                        out.attribute(null, "component", entry.getKey().flattenToString());
                        out.attribute(null, "uid", Integer.toString(entry.getValue().uid));
                        out.attribute(null, "nfcid2", entry.getValue().nfcid2);
                        out.endTag(null, "service");
                    }
                }
            }
            out.endTag(null, "services");
            out.endDocument();
            mDynamicSystemCodeNfcid2File.finishWrite(fos);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error writing dynamic System Code, NFCID2", e);
            if (fos != null) {
                mDynamicSystemCodeNfcid2File.failWrite(fos);
            }
            return false;
        }
    }

    public boolean registerSystemCodeForService(int userId, int uid,
            ComponentName componentName, String systemCode) {
        if (DBG) Log.d(TAG, "registerSystemCodeForService");
        ArrayList<NfcFServiceInfo> newServices = null;
        boolean success;
        synchronized (mLock) {
            if (mActivated) {
                Log.d(TAG, "failed to register System Code during activation");
                return false;
            }
            UserServices userServices = findOrCreateUserLocked(userId);
            // Check if we can find this service
            NfcFServiceInfo service = getService(userId, componentName);
            if (service == null) {
                Log.e(TAG, "Service " + componentName + " does not exist.");
                return false;
            }
            if (service.getUid() != uid) {
                // This is probably a good indication something is wrong here.
                // Either newer service installed with different uid (but then
                // we should have known about it), or somebody calling us from
                // a different uid.
                Log.e(TAG, "UID mismatch.");
                return false;
            }
            if (!systemCode.equalsIgnoreCase("NULL") &&
                    !NfcFCardEmulation.isValidSystemCode(systemCode)) {
                Log.e(TAG, "System Code " + systemCode + " is not a valid System Code");
                return false;
            }
            // Apply dynamic System Code mappings
            systemCode = systemCode.toUpperCase();
            DynamicSystemCode oldDynamicSystemCode =
                    userServices.dynamicSystemCode.get(componentName);
            DynamicSystemCode dynamicSystemCode = new DynamicSystemCode(uid, systemCode);
            userServices.dynamicSystemCode.put(componentName, dynamicSystemCode);
            success = writeDynamicSystemCodeNfcid2Locked();
            if (success) {
                service.setOrReplaceDynamicSystemCode(systemCode);
                newServices = new ArrayList<NfcFServiceInfo>(userServices.services.values());
            } else {
                Log.e(TAG, "Failed to persist System Code.");
                // Undo registration
                if (oldDynamicSystemCode == null) {
                    userServices.dynamicSystemCode.remove(componentName);
                } else {
                    userServices.dynamicSystemCode.put(componentName, oldDynamicSystemCode);
                }
            }
        }
        if (success) {
            // Make callback without the lock held
            mCallback.onNfcFServicesUpdated(userId, newServices);
        }
        return success;
    }

    public String getSystemCodeForService(int userId, int uid, ComponentName componentName) {
        if (DBG) Log.d(TAG, "getSystemCodeForService");
        NfcFServiceInfo service = getService(userId, componentName);
        if (service != null) {
            if (service.getUid() != uid) {
                Log.e(TAG, "UID mismatch");
                return null;
            }
            return service.getSystemCode();
        } else {
            Log.e(TAG, "Could not find service " + componentName);
            return null;
        }
    }

    public boolean removeSystemCodeForService(int userId, int uid, ComponentName componentName) {
        if (DBG) Log.d(TAG, "removeSystemCodeForService");
        return registerSystemCodeForService(userId, uid, componentName, "NULL");
    }

    public boolean setNfcid2ForService(int userId, int uid,
            ComponentName componentName, String nfcid2) {
        if (DBG) Log.d(TAG, "setNfcid2ForService");
        ArrayList<NfcFServiceInfo> newServices = null;
        boolean success;
        synchronized (mLock) {
            if (mActivated) {
                Log.d(TAG, "failed to set NFCID2 during activation");
                return false;
            }
            UserServices userServices = findOrCreateUserLocked(userId);
            // Check if we can find this service
            NfcFServiceInfo service = getService(userId, componentName);
            if (service == null) {
                Log.e(TAG, "Service " + componentName + " does not exist.");
                return false;
            }
            if (service.getUid() != uid) {
                // This is probably a good indication something is wrong here.
                // Either newer service installed with different uid (but then
                // we should have known about it), or somebody calling us from
                // a different uid.
                Log.e(TAG, "UID mismatch.");
                return false;
            }
            if (!NfcFCardEmulation.isValidNfcid2(nfcid2)) {
                Log.e(TAG, "NFCID2 " + nfcid2 + " is not a valid NFCID2");
                return false;
            }
            // Apply dynamic NFCID2 mappings
            nfcid2 = nfcid2.toUpperCase();
            DynamicNfcid2 oldDynamicNfcid2 = userServices.dynamicNfcid2.get(componentName);
            DynamicNfcid2 dynamicNfcid2 = new DynamicNfcid2(uid, nfcid2);
            userServices.dynamicNfcid2.put(componentName, dynamicNfcid2);
            success = writeDynamicSystemCodeNfcid2Locked();
            if (success) {
                service.setOrReplaceDynamicNfcid2(nfcid2);
                newServices = new ArrayList<NfcFServiceInfo>(userServices.services.values());
            } else {
                Log.e(TAG, "Failed to persist NFCID2.");
                // Undo registration
                if (oldDynamicNfcid2 == null) {
                    userServices.dynamicNfcid2.remove(componentName);
                } else {
                    userServices.dynamicNfcid2.put(componentName, oldDynamicNfcid2);
                }
            }
        }
        if (success) {
            // Make callback without the lock held
            mCallback.onNfcFServicesUpdated(userId, newServices);
        }
        return success;
    }

    public String getNfcid2ForService(int userId, int uid, ComponentName componentName) {
        if (DBG) Log.d(TAG, "getNfcid2ForService");
        NfcFServiceInfo service = getService(userId, componentName);
        if (service != null) {
            if (service.getUid() != uid) {
                Log.e(TAG, "UID mismatch");
                return null;
            }
            return service.getNfcid2();
        } else {
            Log.e(TAG, "Could not find service " + componentName);
            return null;
        }
    }

    public void onHostEmulationActivated() {
        if (DBG) Log.d(TAG, "onHostEmulationActivated");
        synchronized (mLock) {
            mActivated = true;
        }
    }

    public void onHostEmulationDeactivated() {
        if (DBG) Log.d(TAG, "onHostEmulationDeactivated");
        synchronized (mLock) {
            mActivated = false;
        }
    }

    public void onNfcDisabled() {
        synchronized (mLock) {
            mActivated = false;
        }
    }

    public void onUserSwitched() {
        synchronized (mLock) {
            mUserSwitched = true;
        }
    }

    private String generateRandomNfcid2() {
        long min = 0L;
        long max = 0xFFFFFFFFFFFFL;

        long randomNfcid2 = (long)Math.floor(Math.random() * (max-min+1)) + min;
        return String.format("02FE%02X%02X%02X%02X%02X%02X",
                (randomNfcid2 >>> 8 * 5) & 0xFF, (randomNfcid2 >>> 8 * 4) & 0xFF,
                (randomNfcid2 >>> 8 * 3) & 0xFF, (randomNfcid2 >>> 8 * 2) & 0xFF,
                (randomNfcid2 >>> 8 * 1) & 0xFF, (randomNfcid2 >>> 8 * 0) & 0xFF);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Registered HCE-F services for current user: ");
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(ActivityManager.getCurrentUser());
            for (NfcFServiceInfo service : userServices.services.values()) {
                service.dump(fd, pw, args);
                pw.println("");
            }
            pw.println("");
        }
    }

}
