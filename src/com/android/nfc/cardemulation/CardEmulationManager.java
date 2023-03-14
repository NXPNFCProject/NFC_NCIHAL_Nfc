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
*  Copyright 2018-2022 NXP
*
******************************************************************************/
package com.android.nfc.cardemulation;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.nfc.INfcCardEmulation;
import android.nfc.INfcFCardEmulation;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.AidGroup;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.os.Binder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.sysprop.NfcProperties;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import java.util.Map;
import java.util.HashMap;

import com.android.nfc.NfcPermissions;
import com.android.nfc.NfcService;

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
    static final boolean DBG = NfcProperties.debug_enabled().orElse(false);

    static final int NFC_HCE_APDU = 0x01;
    static final int NFC_HCE_NFCF = 0x04;
    /** Minimum AID length as per ISO7816 */
    static final int MINIMUM_AID_LENGTH = 5;
    /** Length of Select APDU header including length byte */
    static final int SELECT_APDU_HDR_LENGTH = 5;
    /** Length of the NDEF Tag application AID */
    static final int NDEF_AID_LENGTH = 7;
    /** AID of the NDEF Tag application Mapping Version 1.0 */
    static final byte[] NDEF_AID_V1 =
            new byte[] {(byte) 0xd2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x00};
    /** AID of the NDEF Tag application Mapping Version 2.0 */
    static final byte[] NDEF_AID_V2 =
            new byte[] {(byte) 0xd2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01};
    /** Select APDU header */
    static final byte[] SELECT_AID_HDR = new byte[] {0x00, (byte) 0xa4, 0x04, 0x00};

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
    final NfcFCardEmulationInterface mNfcFCardEmulationInterface;
    final PowerManager mPowerManager;
    boolean mNotSkipAid;

    public CardEmulationManager(Context context) {
        mContext = context;
        mCardEmulationInterface = new CardEmulationInterface();
        mNfcFCardEmulationInterface = new NfcFCardEmulationInterface();
        mAidCache = new RegisteredAidCache(context);
        mT3tIdentifiersCache = new RegisteredT3tIdentifiersCache(context);
        mHostEmulationManager = new HostEmulationManager(context, mAidCache);
        mHostNfcFEmulationManager = new HostNfcFEmulationManager(context, mT3tIdentifiersCache);
        mServiceCache = new RegisteredServicesCache(context, this);
        mNfcFServicesCache = new RegisteredNfcFServicesCache(context, this);
        mPreferredServices = new PreferredServices(context, mServiceCache, mAidCache, this);
        mEnabledNfcFServices = new EnabledNfcFServices(
                context, mNfcFServicesCache, mT3tIdentifiersCache, this);
        mServiceCache.initialize();
        mNfcFServicesCache.initialize();
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    public INfcCardEmulation getNfcCardEmulationInterface() {
        return mCardEmulationInterface;
    }

    public INfcFCardEmulation getNfcFCardEmulationInterface() {
        return mNfcFCardEmulationInterface;
    }
        // To get Object of RegisteredAidCache to get the Default Offhost service.
    public RegisteredAidCache getRegisteredAidCache() {
        return mAidCache;
    }

    public void onHostCardEmulationActivated(int technology) {
        if (mPowerManager != null) {
            // Use USER_ACTIVITY_FLAG_INDIRECT to applying power hints without resets
            // the screen timeout
            mPowerManager.userActivity(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_TOUCH,
                    PowerManager.USER_ACTIVITY_FLAG_INDIRECT);
        }
        if (technology == NFC_HCE_APDU) {
            mHostEmulationManager.onHostEmulationActivated();
            mPreferredServices.onHostEmulationActivated();
            mNotSkipAid = false;
        } else if (technology == NFC_HCE_NFCF) {
            mHostNfcFEmulationManager.onHostEmulationActivated();
            mNfcFServicesCache.onHostEmulationActivated();
            mEnabledNfcFServices.onHostEmulationActivated();
        }
    }

    public void onHostCardEmulationData(int technology, byte[] data) {
        if (technology == NFC_HCE_APDU) {
            mHostEmulationManager.onHostEmulationData(data);
        } else if (technology == NFC_HCE_NFCF) {
            mHostNfcFEmulationManager.onHostEmulationData(data);
        }
        // Don't trigger userActivity if it's selecting NDEF AID
        if (mPowerManager != null && !(technology == NFC_HCE_APDU && isSkipAid(data))) {
            // Caution!! USER_ACTIVITY_EVENT_TOUCH resets the screen timeout
            mPowerManager.userActivity(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
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
        // for HCE
        mServiceCache.onUserSwitched();
        mPreferredServices.onUserSwitched(userId);
        // for HCE-F
        mHostNfcFEmulationManager.onUserSwitched();
        mT3tIdentifiersCache.onUserSwitched();
        mEnabledNfcFServices.onUserSwitched(userId);
        mNfcFServicesCache.onUserSwitched();
    }

    public void onManagedProfileChanged() {
        // for HCE
        mServiceCache.onManagedProfileChanged();
        // for HCE-F
        mNfcFServicesCache.onManagedProfileChanged();
    }

    public void onNfcEnabled() {
        // for HCE
        mAidCache.onNfcEnabled();
        // for HCE-F
        mT3tIdentifiersCache.onNfcEnabled();
    }

    public void onNfcDisabled() {
        // for HCE
        mAidCache.onNfcDisabled();
        // for HCE-F
        mHostNfcFEmulationManager.onNfcDisabled();
        mNfcFServicesCache.onNfcDisabled();
        mT3tIdentifiersCache.onNfcDisabled();
        mEnabledNfcFServices.onNfcDisabled();
    }

    public void onSecureNfcToggled() {
        mAidCache.onSecureNfcToggled();
        mT3tIdentifiersCache.onSecureNfcToggled();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mServiceCache.dump(fd, pw, args);
        mNfcFServicesCache.dump(fd, pw ,args);
        mPreferredServices.dump(fd, pw, args);
        mEnabledNfcFServices.dump(fd, pw, args);
        mAidCache.dump(fd, pw, args);
        mT3tIdentifiersCache.dump(fd, pw, args);
        mHostEmulationManager.dump(fd, pw, args);
        mHostNfcFEmulationManager.dump(fd, pw, args);
    }

    /**
     * Dump debugging information as a CardEmulationManagerProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/card_emulation.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    public void dumpDebug(ProtoOutputStream proto) {
        long token = proto.start(CardEmulationManagerProto.REGISTERED_SERVICES_CACHE);
        mServiceCache.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.REGISTERED_NFC_F_SERVICES_CACHE);
        mNfcFServicesCache.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.PREFERRED_SERVICES);
        mPreferredServices.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.ENABLED_NFC_F_SERVICES);
        mEnabledNfcFServices.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.AID_CACHE);
        mAidCache.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.T3T_IDENTIFIERS_CACHE);
        mT3tIdentifiersCache.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.HOST_EMULATION_MANAGER);
        mHostEmulationManager.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.HOST_NFC_F_EMULATION_MANAGER);
        mHostNfcFEmulationManager.dumpDebug(proto);
        proto.end(token);
    }

    @Override
    public void onServicesUpdated(int userId, List<ApduServiceInfo> services,
            boolean validateInstalled) {
        // Verify defaults are still the same
        verifyDefaults(userId, services, validateInstalled);
        // Update the AID cache
        mAidCache.onServicesUpdated(userId, services);
        // Update the preferred services list
        mPreferredServices.onServicesUpdated();

        NfcService.getInstance().onPreferredPaymentChanged(NfcAdapter.PREFERRED_PAYMENT_UPDATED);
    }

    @Override
    public void onNfcFServicesUpdated(int userId, List<NfcFServiceInfo> services) {
        // Update the T3T identifier cache
        mT3tIdentifiersCache.onServicesUpdated(userId, services);
        // Update the enabled services list
        mEnabledNfcFServices.onServicesUpdated();
    }

    void verifyDefaults(int userId, List<ApduServiceInfo> services, boolean validateInstalled) {
        UserManager um = mContext.createContextAsUser(
                UserHandle.of(userId), /*flags=*/0).getSystemService(UserManager.class);
        List<UserHandle> luh = um.getEnabledProfiles();

        ComponentName defaultPaymentService = null;
        int numDefaultPaymentServices = 0;
        int userIdDefaultPaymentService = userId;

        for (UserHandle uh : luh) {
            ComponentName paymentService = getDefaultServiceForCategory(uh.getIdentifier(),
                    CardEmulation.CATEGORY_PAYMENT,
                    validateInstalled && (uh.getIdentifier() == userId));
            if (DBG) Log.d(TAG, "default: " + paymentService + " for user:" + uh);
            if (paymentService != null) {
                numDefaultPaymentServices++;
                defaultPaymentService = paymentService;
                userIdDefaultPaymentService = uh.getIdentifier();
            }
        }
        if (numDefaultPaymentServices > 1) {
            Log.e(TAG, "Current default is not aligned across multiple users");
            // leave default unset
            for (UserHandle uh : luh) {
                setDefaultServiceForCategoryChecked(uh.getIdentifier(), null,
                        CardEmulation.CATEGORY_PAYMENT);
            }
        } else {
            if (DBG) {
                Log.d(TAG, "Current default: " + defaultPaymentService + " for user:"
                        + userIdDefaultPaymentService);
            }
        }
        if (defaultPaymentService == null) {
            // A payment service may have been removed, leaving only one;
            // in that case, automatically set that app as default.
            int numPaymentServices = 0;
            ComponentName lastFoundPaymentService = null;
            PackageManager pm;
            try {
                pm = mContext.createPackageContextAsUser("android", /*flags=*/0,
                    new UserHandle(userId)).getPackageManager();
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Could not create user package context");
                return;
            }

            for (ApduServiceInfo service : services) {
                if (service.hasCategory(CardEmulation.CATEGORY_PAYMENT)
                            && wasServicePreInstalled(pm, service.getComponent())) {
                    numPaymentServices++;
                    lastFoundPaymentService = service.getComponent();
                }
            }
            if (numPaymentServices > 1) {
                // More than one service left, leave default unset
                if (DBG) Log.d(TAG, "No default set, more than one service left.");
                setDefaultServiceForCategoryChecked(userId, null, CardEmulation.CATEGORY_PAYMENT);
            } else if (numPaymentServices == 1) {
                // Make single found payment service the default
                if (DBG) Log.d(TAG, "No default set, making single service default.");
                setDefaultServiceForCategoryChecked(userId, lastFoundPaymentService,
                        CardEmulation.CATEGORY_PAYMENT);
            } else {
                // No payment services left, leave default at null
                if (DBG) Log.d(TAG, "No default set, last payment service removed.");
                setDefaultServiceForCategoryChecked(userId, null, CardEmulation.CATEGORY_PAYMENT);
            }
        }
    }

    boolean wasServicePreInstalled(PackageManager packageManager, ComponentName service) {
        try {
            ApplicationInfo ai = packageManager
                    .getApplicationInfo(service.getPackageName(), /*flags=*/0);
            if ((ApplicationInfo.FLAG_SYSTEM & ai.flags) != 0) {
                if (DBG) Log.d(TAG, "Service was pre-installed on the device");
                return true;
            }
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Service is not currently installed on the device.");
            return false;
        }
        if (DBG) Log.d(TAG, "Service was not pre-installed on the device");
        return false;
    }

    ComponentName getDefaultServiceForCategory(int userId, String category,
             boolean validateInstalled) {
        if (!CardEmulation.CATEGORY_PAYMENT.equals(category)) {
            Log.e(TAG, "Not allowing defaults for category " + category);
            return null;
        }
        // Load current payment default from settings
        String name = Settings.Secure.getString(
                mContext.createContextAsUser(UserHandle.of(userId), 0).getContentResolver(),
                Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT);
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
            Settings.Secure.putString(mContext
                    .createContextAsUser(UserHandle.of(userId), 0).getContentResolver(),
                    Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                    service != null ? service.flattenToString() : null);
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
            mServiceCache.invalidateCache(userId, true);
        }
        return mServiceCache.hasService(userId, service);
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

    /**
     * Returns true if it's not selecting NDEF AIDs
     * It's used to skip userActivity if it only selects NDEF AIDs
     */
    boolean isSkipAid(byte[] data) {
        if (mNotSkipAid || data == null
                || data.length < SELECT_APDU_HDR_LENGTH + MINIMUM_AID_LENGTH
                || !Arrays.equals(SELECT_AID_HDR, 0, SELECT_AID_HDR.length,
                        data, 0, SELECT_AID_HDR.length)) {
            return false;
        }
        int aidLength = Byte.toUnsignedInt(data[SELECT_APDU_HDR_LENGTH - 1]);
        if (data.length >= SELECT_APDU_HDR_LENGTH + NDEF_AID_LENGTH
                && aidLength == NDEF_AID_LENGTH) {
            if (Arrays.equals(data, SELECT_APDU_HDR_LENGTH,
                        SELECT_APDU_HDR_LENGTH + NDEF_AID_LENGTH,
                        NDEF_AID_V1, 0, NDEF_AID_LENGTH)) {
                if (DBG) Log.d(TAG, "Skip for NDEF_V1");
                return true;
            } else if (Arrays.equals(data, SELECT_APDU_HDR_LENGTH,
                        SELECT_APDU_HDR_LENGTH + NDEF_AID_LENGTH,
                        NDEF_AID_V2, 0, NDEF_AID_LENGTH)) {
                if (DBG) Log.d(TAG, "Skip for NDEF_V2");
                return true;
            }
        }
        // The data payload is not selecting the skip AID.
        mNotSkipAid = true;
        return false;
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
     * This class implements the application-facing APIs and are called
     * from binder. All calls must be permission-checked.
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
            NfcPermissions.validateProfileId(mContext, userId);
            NfcPermissions.enforceAdminPermissions(mContext);
            if (service != null && !isServiceRegistered(userId, service)) {
                return false;
            }
            return mPreferredServices.setDefaultForNextTap(userId, service);
        }

        @Override
        public boolean registerAidGroupForService(int userId,
                ComponentName service, AidGroup aidGroup) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            if (!mServiceCache.registerAidGroupForService(userId, Binder.getCallingUid(), service,
                    aidGroup)) {
                return false;
            }
            NfcService.getInstance().onPreferredPaymentChanged(
                    NfcAdapter.PREFERRED_PAYMENT_UPDATED);
            return true;
        }

        @Override
        public boolean setOffHostForService(int userId, ComponentName service, String offHostSE) {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            if (!mServiceCache.setOffHostSecureElement(userId, Binder.getCallingUid(), service,
                    offHostSE)) {
                return false;
            }
            NfcService.getInstance().onPreferredPaymentChanged(
                    NfcAdapter.PREFERRED_PAYMENT_UPDATED);
            return true;
        }

        @Override
        public boolean unsetOffHostForService(int userId, ComponentName service) {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            if (!mServiceCache.unsetOffHostSecureElement(userId, Binder.getCallingUid(), service)) {
                return false;
            }
            NfcService.getInstance().onPreferredPaymentChanged(
                    NfcAdapter.PREFERRED_PAYMENT_UPDATED);
            return true;
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
            if (!mServiceCache.removeAidGroupForService(userId, Binder.getCallingUid(), service,
                    category)) {
                return false;
            }
            NfcService.getInstance().onPreferredPaymentChanged(
                    NfcAdapter.PREFERRED_PAYMENT_UPDATED);
            return true;
        }

        @Override
        public List<ApduServiceInfo> getServices(int userId, String category)
                throws RemoteException {
            NfcPermissions.validateProfileId(mContext, userId);
            NfcPermissions.enforceAdminPermissions(mContext);
            return mServiceCache.getServicesForCategory(userId, category);
        }

        @Override
        public boolean setPreferredService(ComponentName service)
                throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered( UserHandle.getUserHandleForUid(
                    Binder.getCallingUid()).getIdentifier(), service)) {
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

        @Override
        public ApduServiceInfo getPreferredPaymentService(int userId) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            NfcPermissions.enforcePreferredPaymentInfoPermissions(mContext);
            if((mServiceCache.getService(userId, mAidCache.getPreferredService())) != null) {
                return mServiceCache.getService(userId, mAidCache.getPreferredService());
            } else {
                return null;
            }
        }

        @Override
        public boolean isDefaultPaymentRegistered() throws RemoteException {
            String defaultComponent = Settings.Secure.getString(mContext.getContentResolver(),
                    Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT);
            return defaultComponent != null ? true : false;
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
            if (isNfcFServiceInstalled(UserHandle.getUserHandleForUid(
                    Binder.getCallingUid()).getIdentifier(), service)) {
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
            NfcPermissions.validateProfileId(mContext, userId);
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
    public void onPreferredPaymentServiceChanged(int userId, ComponentName service) {
        mAidCache.onPreferredPaymentServiceChanged(userId, service);
        mHostEmulationManager.onPreferredPaymentServiceChanged(userId, service);

        NfcService.getInstance().onPreferredPaymentChanged(
                NfcAdapter.PREFERRED_PAYMENT_CHANGED);
    }

    @Override
    public void onPreferredForegroundServiceChanged(int userId, ComponentName service) {
        mAidCache.onPreferredForegroundServiceChanged(userId, service);
        mHostEmulationManager.onPreferredForegroundServiceChanged(userId, service);

        NfcService.getInstance().onPreferredPaymentChanged(
                NfcAdapter.PREFERRED_PAYMENT_CHANGED);
    }

    public void onRoutingTableChanged() {
        mAidCache.onRoutingTableChanged();
    }

    @Override
    public void onEnabledForegroundNfcFServiceChanged(int userId, ComponentName service) {
        mT3tIdentifiersCache.onEnabledForegroundNfcFServiceChanged(userId, service);
        mHostNfcFEmulationManager.onEnabledForegroundNfcFServiceChanged(userId, service);
    }

    public String getRegisteredAidCategory(String aid) {
        RegisteredAidCache.AidResolveInfo resolvedInfo = mAidCache.resolveAid(aid);
        if (resolvedInfo != null) {
            return resolvedInfo.category;
        }
        return "";
    }
}
