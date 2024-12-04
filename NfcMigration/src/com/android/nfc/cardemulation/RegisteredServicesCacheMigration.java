/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.nfc.cardemulation.CardEmulation.CATEGORY_OTHER;
import static android.nfc.cardemulation.CardEmulation.CATEGORY_PAYMENT;

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
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.AidGroup;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.nfc.cardemulation.OffHostApduService;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Used to migrate persistent cache files stored by
 * {@link com.android.nfc.cardemulation.RegisteredServicesCache} from AOSP stack to NFC mainline
 * module.
 */
public class RegisteredServicesCacheMigration {
    static final String TAG = "RegisteredServicesCacheMigration";
    static final String AID_XML_PATH = "dynamic_aids.xml";
    static final String OTHER_STATUS_PATH = "other_status.xml";

    private final Context mContext;
    private final CardEmulation mCardEmulation;
    private final SparseArray<UserServices> mUserServices = new SparseArray<UserServices>();
    private final SettingsFile mDynamicSettingsFile;
    private final SettingsFile mOthersFile;
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
    }

    private UserServices findOrCreateUserLocked(int userId) {
        UserServices services = mUserServices.get(userId);
        if (services == null) {
            services = new UserServices();
            mUserServices.put(userId, services);
        }
        return services;
    }

    public RegisteredServicesCacheMigration(Context context) {
        mContext = context;
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(mContext);
        if (nfcAdapter == null) {
            throw new IllegalStateException("Failed to get NFC adapter");
        }
        mCardEmulation = CardEmulation.getInstance(nfcAdapter);
        if (mCardEmulation == null) {
            throw new IllegalStateException("Failed to get card emulation");
        }
        // Invoke this call to ensure the cache is populated in the NFC stack before triggering
        // the migration.
        mCardEmulation.isDefaultServiceForAid(
                new ComponentName(context, getClass()), CATEGORY_PAYMENT);
        SettingsFile dynamicSettingsFile = new SettingsFile(context, AID_XML_PATH);
        SettingsFile othersFile = new SettingsFile(context, OTHER_STATUS_PATH);
        // Check both CE & DE directory for migration.
        if (!dynamicSettingsFile.exists() && !dynamicSettingsFile.exists()) {
            Log.d(TAG, "Searching for NFC service info in CE directory");
            Context ceContext = context.createCredentialProtectedStorageContext();
            dynamicSettingsFile = new SettingsFile(ceContext, AID_XML_PATH);
            othersFile = new SettingsFile(ceContext, OTHER_STATUS_PATH);
        }
        mDynamicSettingsFile = dynamicSettingsFile;
        mOthersFile = othersFile;
        readDynamicSettingsLocked();
        readOthersLocked();
    }

    public void handleMigration() {
        UserManager um = mContext.createContextAsUser(
                        UserHandle.of(ActivityManager.getCurrentUser()), /*flags=*/0)
                .getSystemService(UserManager.class);
        Log.i(TAG, "Migrating cache files: " + mDynamicSettingsFile + ", " + mOthersFile);
        for (UserHandle uh : um.getEnabledProfiles()) {
            handleMigrationDynamicSettings(uh.getIdentifier());
            handleMigrationOtherServices(uh.getIdentifier());
        }
    }

    private List<ApduServiceInfo> getAllServices(int userId) {
        List<ApduServiceInfo> validPaymentServices =
                mCardEmulation.getServices(CATEGORY_PAYMENT, userId);
        List<ApduServiceInfo> validOtherServices =
                mCardEmulation.getServices(CATEGORY_OTHER, userId);
        List<ApduServiceInfo> validServices = Stream.of(validPaymentServices, validOtherServices)
                .flatMap(List::stream)
                .collect(Collectors.toList());
        Log.d(TAG, "getAllServices (all): " + validServices);
        return validServices;
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

    /**
     * invalidateCache for specific userId.
     */
    private void handleMigrationDynamicSettings(int userId) {
        final List<ApduServiceInfo> validServices = getAllServices(userId);
        if (validServices == null || validServices.isEmpty()) {
            Log.i(TAG, "No installed services");
            return;
        }
        UserServices userServices = findOrCreateUserLocked(userId);
        for (ApduServiceInfo service : validServices) {
            userServices.services.put(service.getComponent(), service);
        }
        // Apply dynamic settings mappings
        for (Map.Entry<ComponentName, DynamicSettings> entry :
                userServices.dynamicSettings.entrySet()) {
            // Verify component / uid match
            ComponentName component = entry.getKey();
            DynamicSettings dynamicSettings = entry.getValue();
            ApduServiceInfo serviceInfo = userServices.services.get(component);
            if (serviceInfo == null || (serviceInfo.getUid() != dynamicSettings.uid)) {
                continue;
            } else {
                for (AidGroup group : dynamicSettings.aidGroups.values()) {
                    mCardEmulation.registerAidsForService(
                            component, group.getCategory(), group.getAids());
                }
                if (dynamicSettings.offHostSE != null) {
                    mCardEmulation.setOffHostForService(component, dynamicSettings.offHostSE);
                }
                if (dynamicSettings.shouldDefaultToObserveModeStr != null) {
                    mCardEmulation.setShouldDefaultToObserveModeForService(
                            component, convertValueToBoolean(
                                    dynamicSettings.shouldDefaultToObserveModeStr, false));
                }
            }
        }

    }

    private void handleMigrationOtherServices(int userId) {
        List<ApduServiceInfo> validOtherServices =
                mCardEmulation.getServices(CATEGORY_OTHER, userId);
        if (validOtherServices == null || validOtherServices.isEmpty()) {
            Log.i(TAG, "No installed other services");
            return;
        }
        UserServices userServices = findOrCreateUserLocked(userId);
        for (ApduServiceInfo service : validOtherServices) {
            if (!service.hasCategory(CATEGORY_OTHER)) {
                Log.e(TAG, "service does not have other category");
                continue;
            }
            ComponentName component = service.getComponent();
            OtherServiceStatus status = userServices.others.get(component);
            if (status != null) {
                try {
                    Method setServiceEnabledForCategoryOtherMethod =
                            CardEmulation.class.getMethod(
                                    "setServiceEnabledForCategoryOther", ComponentName.class,
                                    boolean.class, int.class);
                    setServiceEnabledForCategoryOtherMethod.invoke(
                            mCardEmulation, component, status.checked, userId);
                    // TODO: Add formal API
                    // mCardEmulation.setServiceEnabledForCategoryOther(
                    //      component, status.checked, userId);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to set other service status", e);

                }
            }
        }
    }

    private Map<Integer, List<Pair<ComponentName, DynamicSettings>>>
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

    private Map<Integer, List<Pair<ComponentName, OtherServiceStatus>>>
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
}
