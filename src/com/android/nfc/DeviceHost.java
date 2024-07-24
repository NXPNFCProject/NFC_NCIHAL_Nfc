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
*  Copyright 2018-2024 NXP
*
******************************************************************************/
package com.android.nfc;

import android.annotation.Nullable;
import android.nfc.NdefMessage;
import android.os.Bundle;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.List;

public interface DeviceHost {
    public interface DeviceHostListener {
        public void onRemoteEndpointDiscovered(TagEndpoint tag);

        /**
         */
        public void onHostCardEmulationActivated(int technology);
        public void onHostCardEmulationData(int technology, byte[] data);
        public void onHostCardEmulationDeactivated(int technology);
        /**
         * Notifies that the SE has been activated in listen mode
         */
        public void onSeListenActivated();

        /**
         * Notifies that the SE has been deactivated
         */
        public void onSeListenDeactivated();

        /**
         * Notifies SRD event
         */
        public void onNotifySrdEvt(int event);

        public void onNotifyEfdmEvt(int efdmEvt);

        public void onRemoteFieldActivated();

        public void onRemoteFieldDeactivated();

        public void onEeUpdated();

        public void onHwErrorReported();

        public void onPollingLoopDetected(List<Bundle> pollingFrames);

        public void onVendorSpecificEvent(int gid, int oid, byte[] payload);
        /**
         * Notifies SWP Reader Events.
         */
        public void onScrNotifyEvents(int event);

        public void onNfcTransactionEvent(byte[] aid, byte[] data, String seName);

        public void onLxDebugConfigData(int len, byte[] data);

        public void notifyTagAbort();

        /**
         * Notifies core generic error notification
         */
        void notifyCoreGenericError(int errorCode);
    }

    public interface TagEndpoint {
        boolean connect(int technology);
        boolean reconnect();
        boolean disconnect();

        boolean presenceCheck();
        boolean isPresent();
        void startPresenceChecking(int presenceCheckDelay,
                                   @Nullable TagDisconnectedCallback callback);
        void stopPresenceChecking();
        boolean isPresenceCheckStopped();
        void prepareForRemovalDetectionMode();

        int[] getTechList();
        void removeTechnology(int tech); // TODO remove this one
        Bundle[] getTechExtras();
        byte[] getUid();
        int getHandle();

        byte[] transceive(byte[] data, boolean raw, int[] returnCode);

        boolean checkNdef(int[] out);
        byte[] readNdef();
        boolean writeNdef(byte[] data);
        NdefMessage findAndReadNdef();
        boolean formatNdef(byte[] key);
        boolean isNdefFormatable();
        boolean makeReadOnly();

        int getConnectedTechnology();

        /**
         * Find Ndef only
         * As per NFC forum test specification ndef write test expects only
         * ndef detection followed by ndef write. System property
         * nfc.dta.skipNdefRead added to skip default ndef read before tag
         * dispatch. This system property is valid only in reader mode.
         */
        void findNdef();
    }

    public interface TagDisconnectedCallback {
        void onTagDisconnected();
    }

    public interface NfceeEndpoint {
        // TODO flesh out multi-EE and use this
    }

    public interface NfcDepEndpoint {
        /**
         * Invalid target mode
         */
        public static final short MODE_INVALID = 0xff;

        public byte[] receive();

        public boolean send(byte[] data);

        public boolean connect();

        public boolean disconnect();

        public byte[] transceive(byte[] data);

        public int getHandle();

        public int getMode();

        public byte[] getGeneralBytes();
    }

    /**
     * Called at boot if NFC is disabled to give the device host an opportunity
     * to check the firmware version to see if it needs updating. Normally the firmware version
     * is checked during {@link #initialize(boolean enableScreenOffSuspend)},
     * but the firmware may need to be updated after an OTA update.
     *
     * <p>This is called from a thread
     * that may block for long periods of time during the update process.
     */
    public boolean checkFirmware();

    public boolean initialize();

    public boolean deinitialize();

    public String getName();

    public void enableDiscovery(NfcDiscoveryParameters params, boolean restart);

    public void disableDiscovery();

    public int[] doGetActiveSecureElementList();
    public boolean sendRawFrame(byte[] data);

    public boolean routeAid(byte[] aid, int route, int aidInfo, int power);

    public boolean unrouteAid(byte[] aid);

    public boolean setRoutingEntry(int type, int value, int route, int power);

    public int getDefaultAidRoute();

    public int getDefaultDesfireRoute();

    public int getT4TNfceePowerState();

    public int getDefaultMifareCLTRoute();

    public int getDefaultFelicaCLTRoute();

    public int getDefaultAidPowerState();

    public int getDefaultDesfirePowerState();

    public int getDefaultMifareCLTPowerState();

    public int getDefaultFelicaCLTPowerState();

    public boolean commitRouting();

    public void setEmptyAidRoute(int defaultAidRoute);

    public void registerT3tIdentifier(byte[] t3tIdentifier);

    public void deregisterT3tIdentifier(byte[] t3tIdentifier);

    public void clearT3tIdentifiersCache();

    public int getLfT3tMax();

    public void resetTimeouts();

    public boolean setTimeout(int technology, int timeout);

    public int getTimeout(int technology);

    public void doAbort(String msg);

    boolean canMakeReadOnly(int technology);

    int getMaxTransceiveLength(int technology);

    public int getAidTableSize();

    boolean getExtendedLengthApdusSupported();

    void dump(FileDescriptor fd);

    public void doSetScreenState(int screen_state_mask);

    public void doResonantFrequency(boolean isResonantFreq);

    void stopPoll(int mode);

    void startPoll();

    int mposSetReaderMode(boolean on);

    boolean mposGetReaderMode();

    public int doNfcSelfTest(int type);

    public int getNciVersion();

    public void enableDtaMode();

    public void disableDtaMode();

    public void factoryReset();

    public void shutdown();

    /**
    * Set NFCC power state by sending NFCEE_POWER_AND_LINK_CNTRL_CMD
    */
    void setNfceePowerAndLinkCtrl(boolean enable);

    public boolean setNfcSecure(boolean enable);

    public boolean isObserveModeSupported();

    public boolean setObserveMode(boolean enable);

    public boolean isObserveModeEnabled();

    /**
    * Get the committed listen mode routing configuration
    */
    byte[] getRoutingTable();

    /**
    * Get the Max Routing Table size from cache
    */
    int getMaxRoutingTableSize();

    /**
     * Start or stop RF polling
     */
    void startStopPolling(boolean enable);

    void setIsoDepProtocolRoute(int route);
    void setTechnologyABRoute(int route);
    void clearRoutingEntry(int clearFlags);

    /**
    * Set NFCC discovery technology for polling and listening
    */
    void setDiscoveryTech(int pollTech, int listenTech);
    void resetDiscoveryTech();
    /**
    * Sends Vendor NCI command
    */
    NfcVendorNciResponse sendRawVendorCmd(int mt, int gid, int oid, byte[] payload);

    /* NXP extension are here */
    public boolean accessControlForCOSU (int mode);

    public int getFWVersion();
    boolean isNfccBusy();
    int setTransitConfig(String configs);
    public int getRemainingAidTableSize();
    public int doselectUicc(int uiccSlot);
    public int doGetSelectedUicc();
    public int setPreferredSimSlot(int uiccSlot);
    public int doSetFieldDetectMode(boolean mode);
    public boolean isFieldDetectEnabled();
    public int doStartRssiMode(int rssiNtfTimeIntervalInMillisec);
    public int doStopRssiMode();
    public boolean isRssiEnabled();
    public int doWriteT4tData(byte[] fileId, byte[] data, int length);
    public byte[] doReadT4tData(byte[] fileId);
    public boolean doLockT4tData(boolean lock);
    public boolean isLockedT4tData();
    public boolean doClearNdefT4tData();
    public int doEnableDebugNtf(byte fieldValue);
    public int startExtendedFieldDetectMode(int detectionTimeout);
    public int stopExtendedFieldDetectMode();
    public int startCardEmulation();
    /**
     * Restarts RF Discovery
     */
    void restartRFDiscovery();

    /**
     * Enable or Disable the Power Saving Mode based on flag
     */
    boolean setPowerSavingMode(boolean flag);

    public boolean isRemovalDetectionInPollModeSupported();
    public void startRemovalDetectionProcedure(int waitTimeout);
}
