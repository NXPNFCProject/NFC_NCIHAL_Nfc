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
 *  The original Work has been changed by NXP Semiconductors.
 *
 *  Copyright (C) 2015 NXP Semiconductors
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
 ******************************************************************************/
package com.android.nfc.cardemulation;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.nfc.INfcCardEmulation;
import android.nfc.INfcFCardEmulation;
import android.nfc.cardemulation.AidGroup;
import android.nfc.cardemulation.NxpAidGroup;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.NxpApduServiceInfo;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.NfcFCardEmulation;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.android.nfc.NfcPermissions;
import com.android.nfc.NfcService;
import com.android.nfc.cardemulation.RegisteredServicesCache;
import com.gsma.nfc.internal.RegisteredNxpServicesCache;
import com.android.nfc.cardemulation.RegisteredNfcFServicesCache;

/**
 * CardEmulationManager is the central entity
 * responsible for delegating to individual components
 * implementing card emulation:
 * - RegisteredServicesCache keeping track of HCE and SE services on the device
 * - RegisteredNfcFServicesCache keeping track of HCE-F services on the device
 * - RegisteredAidCache keeping track of AIDs registered by those services and manages
 *   the routing table in the NFCC.
 * - RegisteredT3tIdentifiersCache keeping track of T3T Identifier registered by
 *   those services and manages the routing table in the NFCC.
 * - HostEmulationManager handles incoming APDUs for the host and forwards to HCE
 *   services as necessary.
 * - HostNfcFEmulationManager handles incoming NFC-F packets for the host and
 *   forwards to HCE-F services as necessary.
 */
public class CardEmulationManager implements RegisteredServicesCache.Callback,
        RegisteredNfcFServicesCache.Callback, PreferredServices.Callback,
        EnabledNfcFServices.Callback {
    static final String TAG = "CardEmulationManager";
    static final boolean DBG = true;
    static final int NFC_HCE_APDU = 0x01;
    static final int NFC_HCE_NFCF = 0x04;

    final RegisteredAidCache mAidCache;
    final RegisteredT3tIdentifiersCache mT3tIdentifiersCache;
    final RegisteredServicesCache mServiceCache;
    final RegisteredNfcFServicesCache mNfcFServicesCache;
    final HostEmulationManager mHostEmulationManager;
    final HostNfcFEmulationManager mHostNfcFEmulationManager;
    final PreferredServices mPreferredServices;
    final EnabledNfcFServices mEnabledNfcFServices;
    final Context mContext;
    final CardEmulationInterface mCardEmulationInterface;
    final RegisteredNxpServicesCache mRegisteredNxpServicesCache;
    final NfcFCardEmulationInterface mNfcFCardEmulationInterface;
    final PowerManager mPowerManager;

    public CardEmulationManager(Context context, AidRoutingManager aidRoutingManager) {
        mContext = context;
        mCardEmulationInterface = new CardEmulationInterface();
        mNfcFCardEmulationInterface = new NfcFCardEmulationInterface();
        mAidCache = new RegisteredAidCache(context, aidRoutingManager);
        mT3tIdentifiersCache = new RegisteredT3tIdentifiersCache(context);
        mServiceCache = new RegisteredServicesCache(context, this);
        mNfcFServicesCache = new RegisteredNfcFServicesCache(context, this);
        mHostEmulationManager = new HostEmulationManager(context, mAidCache);
        mHostNfcFEmulationManager = new HostNfcFEmulationManager(context, mT3tIdentifiersCache);
        mPreferredServices = new PreferredServices(context, mServiceCache, mAidCache, this);
        mRegisteredNxpServicesCache = new RegisteredNxpServicesCache(context, mServiceCache);
        mEnabledNfcFServices = new EnabledNfcFServices(
                context, mNfcFServicesCache, mT3tIdentifiersCache, this);

        mServiceCache.initialize(mRegisteredNxpServicesCache);
        mNfcFServicesCache.initialize();
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    public RegisteredNxpServicesCache getRegisteredNxpServicesCache() {
        return mRegisteredNxpServicesCache;
    }

    public INfcFCardEmulation getNfcFCardEmulationInterface() {
        return mNfcFCardEmulationInterface;
    }

    // To get Object of RegisteredAidCache to get the Default Offhost service.
    public RegisteredAidCache getRegisteredAidCache() {
        return mAidCache;
    }

    public INfcCardEmulation getNfcCardEmulationInterface() {
        return mCardEmulationInterface;
    }

    public void onHostCardEmulationActivated(int technology) {
        if (mPowerManager != null) {
            mPowerManager.userActivity(SystemClock.uptimeMillis(), PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
        }
        if (technology == NFC_HCE_APDU) {
            mHostEmulationManager.onHostEmulationActivated();
            mPreferredServices.onHostEmulationActivated();
        } else if (technology == NFC_HCE_NFCF) {
            mHostNfcFEmulationManager.onHostEmulationActivated();
            mNfcFServicesCache.onHostEmulationActivated();
            mEnabledNfcFServices.onHostEmulationActivated();
        }
    }

    public void onHostCardEmulationData(int technology, byte[] data) {
        if (mPowerManager != null) {
            mPowerManager.userActivity(SystemClock.uptimeMillis(), PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
        }
        if (technology == NFC_HCE_APDU) {
            mHostEmulationManager.onHostEmulationData(data);
        } else if (technology == NFC_HCE_NFCF) {
            mHostNfcFEmulationManager.onHostEmulationData(data);
        }
    }

    public void onHostCardEmulationDeactivated(int technology) {
        if (technology == NFC_HCE_APDU) {
            mHostEmulationManager.onHostEmulationDeactivated();
            mPreferredServices.onHostEmulationDeactivated();
        } else if (technology == NFC_HCE_NFCF) {
            mHostNfcFEmulationManager.onHostEmulationDeactivated();
            mNfcFServicesCache.onHostEmulationDeactivated();
            mEnabledNfcFServices.onHostEmulationDeactivated();
        }
    }

    public void onOffHostAidSelected() {
        mHostEmulationManager.onOffHostAidSelected();
    }

    public void onUserSwitched(int userId) {
        //For HCE
        mServiceCache.invalidateCache(userId);
        mPreferredServices.onUserSwitched(userId);
        //For HCE-F
        mHostNfcFEmulationManager.onUserSwitched();
        mT3tIdentifiersCache.onUserSwitched();
        mEnabledNfcFServices.onUserSwitched(userId);
        mNfcFServicesCache.onUserSwitched();
        mNfcFServicesCache.invalidateCache(userId);

    }

    public void onT3tConfigure()
    {
        mT3tIdentifiersCache.clearT3tidentifiercache();
        mT3tIdentifiersCache.generateForegroundT3tIdentifiersCacheLocked();
    }

    public void onReRoutingEntry()
    {
        Log.e(TAG, "onReRoutingEntry: notify service.");
        mAidCache.clearRoutingTable();
        mAidCache.generateAidCacheLocked();
    }

    public void onNfcEnabled() {
        mAidCache.onNfcEnabled();
        // for HCE-F
        mT3tIdentifiersCache.onNfcEnabled();
    }

    public void onNfcDisabled() {
        mAidCache.onNfcDisabled();
        // for HCE-F
        mHostNfcFEmulationManager.onNfcDisabled();
        mNfcFServicesCache.onNfcDisabled();
        mT3tIdentifiersCache.onNfcDisabled();
        mEnabledNfcFServices.onNfcDisabled();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mServiceCache.dump(fd, pw, args);
        mNfcFServicesCache.dump(fd, pw ,args);
        mEnabledNfcFServices.dump(fd, pw, args);
        mPreferredServices.dump(fd, pw, args);
        mAidCache.dump(fd, pw, args);
        mT3tIdentifiersCache.dump(fd, pw, args);
        mHostEmulationManager.dump(fd, pw, args);
        mHostNfcFEmulationManager.dump(fd, pw, args);
    }

    @Override
    public void onServicesUpdated(int userId, List<NxpApduServiceInfo> services) {
        // Verify defaults are still sane
        verifyDefaults(userId, services);
        // Update the AID cache
        mAidCache.onServicesUpdated(userId, services);
        // Update the preferred services list
        mPreferredServices.onServicesUpdated();
    }

    @Override
    public void onNfcFServicesUpdated(int userId, List<NfcFServiceInfo> services) {
        // Update the T3T identifier cache
        mT3tIdentifiersCache.onServicesUpdated(userId, services);
        // Update the enabled services list
        mEnabledNfcFServices.onServicesUpdated();
    }

    void verifyDefaults(int userId, List<NxpApduServiceInfo> services) {
        ComponentName defaultPaymentService =
                getDefaultServiceForCategory(userId, CardEmulation.CATEGORY_PAYMENT, false);
        if (DBG) Log.d(TAG, "Current default: " + defaultPaymentService);
        if (defaultPaymentService == null) {
            // A payment service may have been removed, leaving only one;
            // in that case, automatically set that app as default.
            int numPaymentServices = 0;
            ComponentName lastFoundPaymentService = null;
            for (NxpApduServiceInfo service : services) {
                if ((service.hasCategory(CardEmulation.CATEGORY_PAYMENT))&&(!service.getAids().isEmpty())) {
                    numPaymentServices++;
                    lastFoundPaymentService = service.getComponent();
                }
            }
            if (numPaymentServices > 1) {
                // More than one service left, leave default unset
                if (DBG) Log.d(TAG, "No default set, more than one service left.");
            } else if (numPaymentServices == 1) {
                // Make single found payment service the default
                if (DBG) Log.d(TAG, "No default set, making single service default.");
                setDefaultServiceForCategoryChecked(userId, lastFoundPaymentService,
                        CardEmulation.CATEGORY_PAYMENT);
            } else {
                // No payment services left, leave default at null
                if (DBG) Log.d(TAG, "No default set, last payment service removed.");
            }
        }
    }

    ComponentName getDefaultServiceForCategory(int userId, String category,
             boolean validateInstalled) {
        if (!CardEmulation.CATEGORY_PAYMENT.equals(category)) {
            Log.e(TAG, "Not allowing defaults for category " + category);
            return null;
        }
        // Load current payment default from settings
        String name = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                userId);
        if (name != null) {
            ComponentName service = ComponentName.unflattenFromString(name);
            if (!validateInstalled || service == null) {
                return service;
            } else {
                return mServiceCache.hasService(userId, service) ? service : null;
             }
        } else {
            return null;
        }
    }

    boolean isNfcFServiceInstalled(int userId, ComponentName service) {
        boolean serviceFound = mNfcFServicesCache.hasService(userId, service);
        if (!serviceFound) {
            // If we don't know about this service yet, it may have just been enabled
            // using PackageManager.setComponentEnabledSetting(). The PackageManager
            // broadcasts are delayed by 10 seconds in that scenario, which causes
            // calls to our APIs referencing that service to fail.
            // Hence, update the cache in case we don't know about the service.
            if (DBG) Log.d(TAG, "Didn't find passed in service, invalidating cache.");
            mNfcFServicesCache.invalidateCache(userId);
        }
        return mNfcFServicesCache.hasService(userId, service);
    }

    boolean setDefaultServiceForCategoryChecked(int userId, ComponentName service,
            String category) {
        if (!CardEmulation.CATEGORY_PAYMENT.equals(category)) {
            Log.e(TAG, "Not allowing defaults for category " + category);
            return false;
        }
        // TODO Not really nice to be writing to Settings.Secure here...
        // ideally we overlay our local changes over whatever is in
        // Settings.Secure
        if (service == null || mServiceCache.hasService(userId, service)) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                    service != null ? service.flattenToString() : null, userId);
        } else {
            Log.e(TAG, "Could not find default service to make default: " + service);
        }
        return true;
    }

    boolean isServiceRegistered(int userId, ComponentName service) {
        boolean serviceFound = mServiceCache.hasService(userId, service);
        if (!serviceFound) {
            // If we don't know about this service yet, it may have just been enabled
            // using PackageManager.setComponentEnabledSetting(). The PackageManager
            // broadcasts are delayed by 10 seconds in that scenario, which causes
            // calls to our APIs referencing that service to fail.
            // Hence, update the cache in case we don't know about the service.
            if (DBG) Log.d(TAG, "Didn't find passed in service, invalidating cache.");
            mServiceCache.invalidateCache(userId);
        }
        return mServiceCache.hasService(userId, service);
    }


   /**
     * Returns whether a service in this package is preferred,
     * either because it's the default payment app or it's running
     * in the foreground.
     */
    public boolean packageHasPreferredService(String packageName) {
        return mPreferredServices.packageHasPreferredService(packageName);
    }


    /**
     * This class implements the application-facing APIs
     * and are called from binder. All calls must be
     * permission-checked.
     *
     */
    final class CardEmulationInterface extends INfcCardEmulation.Stub {
        @Override
        public boolean isDefaultServiceForCategory(int userId, ComponentName service,
                String category) {
            NfcPermissions.enforceUserPermissions(mContext);
            NfcPermissions.validateUserId(userId);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            ComponentName defaultService =
                    getDefaultServiceForCategory(userId, category, true);
            return (defaultService != null && defaultService.equals(service));
        }

        @Override
        public boolean isDefaultServiceForAid(int userId,
                ComponentName service, String aid) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            return mAidCache.isDefaultServiceForAid(userId, service, aid);
        }

        @Override
        public boolean setDefaultServiceForCategory(int userId,
                ComponentName service, String category) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceAdminPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            return setDefaultServiceForCategoryChecked(userId, service, category);
        }

        @Override
        public boolean setDefaultForNextTap(int userId, ComponentName service)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceAdminPermissions(mContext);
            if (service != null && !isServiceRegistered(userId, service)) {
                return false;
            }
            return mPreferredServices.setDefaultForNextTap(service);
        }

        @Override
        public boolean registerAidGroupForService(int userId,
                ComponentName service, AidGroup aidGroup) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            return mServiceCache.registerAidGroupForService(userId, Binder.getCallingUid(), service,
                    new NxpAidGroup(aidGroup));
        }

        @Override
        public AidGroup getAidGroupForService(int userId,
                ComponentName service, String category) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return null;
            }
            return mServiceCache.getAidGroupForService(userId, Binder.getCallingUid(), service,
                    category);
        }

        @Override
        public boolean removeAidGroupForService(int userId,
                ComponentName service, String category) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            return mServiceCache.removeAidGroupForService(userId, Binder.getCallingUid(), service,
                    category);
        }

        @Override
        public List<ApduServiceInfo> getServices(int userId, String category)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceAdminPermissions(mContext);
            List<NxpApduServiceInfo> nxpApduServices = mServiceCache.getServicesForCategory(userId, category);
            ArrayList<ApduServiceInfo> apduServices = new ArrayList<ApduServiceInfo>();
            for(NxpApduServiceInfo nxpApdu : nxpApduServices) {
                ApduServiceInfo apduService = nxpApdu.createApduServiceInfo();
                apduServices.add(apduService);
            }
            if(DBG) Log.d(TAG, "getServices() size: " + apduServices.size());
            return apduServices;
        }

        @Override
        public boolean setPreferredService(ComponentName service)
                throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(UserHandle.getCallingUserId(), service)) {
                Log.e(TAG, "setPreferredService: unknown component.");
                return false;
            }
            return mPreferredServices.registerPreferredForegroundService(service,
                    Binder.getCallingUid());
        }

        @Override
        public boolean unsetPreferredService() throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            return mPreferredServices.unregisteredPreferredForegroundService(
                    Binder.getCallingUid());

        }
        @Override
        public boolean supportsAidPrefixRegistration() throws RemoteException {
            return mAidCache.supportsAidPrefixRegistration();
        }
    }

    /**
     * This class implements the application-facing APIs and are called
     * from binder. All calls must be permission-checked.
     */
    final class NfcFCardEmulationInterface extends INfcFCardEmulation.Stub {
        @Override
        public String getSystemCodeForService(int userId, ComponentName service)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isNfcFServiceInstalled(userId, service)) {
                return null;
            }
            return mNfcFServicesCache.getSystemCodeForService(
                    userId, Binder.getCallingUid(), service);
        }

        @Override
        public boolean registerSystemCodeForService(int userId, ComponentName service,
                String systemCode)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isNfcFServiceInstalled(userId, service)) {
                return false;
            }
            return mNfcFServicesCache.registerSystemCodeForService(
                    userId, Binder.getCallingUid(), service, systemCode);
        }

        @Override
        public boolean removeSystemCodeForService(int userId, ComponentName service)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isNfcFServiceInstalled(userId, service)) {
                return false;
            }
            return mNfcFServicesCache.removeSystemCodeForService(
                    userId, Binder.getCallingUid(), service);
        }

        @Override
        public String getNfcid2ForService(int userId, ComponentName service)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isNfcFServiceInstalled(userId, service)) {
                return null;
            }
            return mNfcFServicesCache.getNfcid2ForService(
                    userId, Binder.getCallingUid(), service);
        }

        @Override
        public boolean setNfcid2ForService(int userId,
                ComponentName service, String nfcid2) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isNfcFServiceInstalled(userId, service)) {
                return false;
            }
            return mNfcFServicesCache.setNfcid2ForService(
                    userId, Binder.getCallingUid(), service, nfcid2);
        }

        @Override
        public boolean enableNfcFForegroundService(ComponentName service)
                throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            if (isNfcFServiceInstalled(UserHandle.getCallingUserId(), service)) {
                return mEnabledNfcFServices.registerEnabledForegroundService(service,
                        Binder.getCallingUid());
            }
            return false;
        }

        @Override
        public boolean disableNfcFForegroundService() throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            return mEnabledNfcFServices.unregisteredEnabledForegroundService(
                    Binder.getCallingUid());
        }

        @Override
        public List<NfcFServiceInfo> getNfcFServices(int userId)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            return mNfcFServicesCache.getServices(userId);
        }

        @Override
        public int getMaxNumOfRegisterableSystemCodes()
                throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            return NfcService.getInstance().getLfT3tMax();
        }
    }

    @Override
    public void onPreferredPaymentServiceChanged(ComponentName service) {
        mAidCache.onPreferredPaymentServiceChanged(service);
        mHostEmulationManager.onPreferredPaymentServiceChanged(service);
    }

    @Override
    public void onPreferredForegroundServiceChanged(ComponentName service) {
        mAidCache.onPreferredForegroundServiceChanged(service);
        mHostEmulationManager.onPreferredForegroundServiceChanged(service);
    }

    @Override
    public void onEnabledForegroundNfcFServiceChanged(ComponentName service) {
        mT3tIdentifiersCache.onEnabledForegroundNfcFServiceChanged(service);
        mHostNfcFEmulationManager.onEnabledForegroundNfcFServiceChanged(service);
    }

    public void setScreenState(int state) {
        mHostEmulationManager.setScreenState(state);
    }

    public void onRoutingTableChanged() {
        mAidCache.onRoutingTableChanged();
    }

    public Map<String,Integer> getServicesAidCacheSize(int userId, String category) {
        if(category == CardEmulation.CATEGORY_PAYMENT) {
            return null;
        }
        List<NxpApduServiceInfo> nonPaymentServices = new ArrayList<NxpApduServiceInfo>();
        Map<String , Integer> nonPaymentServiceAidCacheSize= new HashMap<String , Integer>();
        Integer serviceAidCacheSize = 0x00;
        String serviceComponent = null;
        NfcPermissions.validateUserId(userId);
        NfcPermissions.enforceUserPermissions(mContext);
        nonPaymentServices = mServiceCache.getServicesForCategory(userId, CardEmulation.CATEGORY_OTHER);
        for(NxpApduServiceInfo serviceinfo : nonPaymentServices) {
            serviceAidCacheSize = 0x00;
            serviceComponent = null;
            if(serviceinfo != null) {
                for(String aid : serviceinfo.getAids()) {
                    if(aid.endsWith("*")) {
                        serviceAidCacheSize += aid.length() - 0x01;
                    } else {
                        serviceAidCacheSize += aid.length();
                    }
                }
                serviceComponent = serviceinfo.getComponent().flattenToString();
                nonPaymentServiceAidCacheSize.put(serviceComponent ,serviceAidCacheSize);
            }
        }
        //Add dynamic non-payment services
        return nonPaymentServiceAidCacheSize;
    }

    public List<NxpApduServiceInfo> getAllServices() {
        int userId = ActivityManager.getCurrentUser();
        return mServiceCache.getServices(userId);
    }

    public int updateServiceState(int userId ,
            Map<String , Boolean> serviceState) {
        NfcPermissions.validateUserId(userId);
        NfcPermissions.enforceUserPermissions(mContext);
        return mServiceCache.updateServiceState(userId ,Binder.getCallingUid() ,serviceState);
    }

    public void updateStatusOfServices(boolean commitStatus) {
        mServiceCache.updateStatusOfServices(commitStatus);
    }
}
