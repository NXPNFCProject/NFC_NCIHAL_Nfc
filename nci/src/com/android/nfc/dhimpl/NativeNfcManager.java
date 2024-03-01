/*
 * Copyright (C) 2010 The Android Open Source Project
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
*  Copyright 2018-2023 NXP
*
******************************************************************************/
package com.android.nfc.dhimpl;

import android.content.Context;
import android.nfc.cardemulation.HostApduService;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.os.Bundle;
import android.util.Log;
import com.android.nfc.DeviceHost;
import com.android.nfc.NfcDiscoveryParameters;
import com.android.nfc.NfcVendorNciResponse;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;

/** Native interface to the NFC Manager functions */
public class NativeNfcManager implements DeviceHost {
    private static final String TAG = "NativeNfcManager";
    static final String PREF = "NciDeviceHost";

    static final int MODE_DEDICATED = 1;
    static final int MODE_NORMAL = 0;
    static final String DRIVER_NAME = "android-nci";

    static {
        System.loadLibrary("nfc_nci_jni");
    }

    /* Native structure */
    private long mNative;

    private int mIsoDepMaxTransceiveLength;
    private final DeviceHostListener mListener;
    private final NativeNfcMposManager mMposMgr;
    private final NativeT4tNfceeManager mT4tNfceeMgr;
    private final NativeExtFieldDetectManager mExtFieldMgr;
    private final Context mContext;

    private final Object mLock = new Object();
    private final HashMap<Integer, byte[]> mT3tIdentifiers = new HashMap<Integer, byte[]>();

    private static final int MIN_POLLING_FRAME_TLV_SIZE = 5;
    private static final int TAG_FIELD_CHANGE = 0;
    private static final int TAG_NFC_A = 1;
    private static final int TAG_NFC_B = 2;
    private static final int TAG_NFC_F = 3;
    private static final int TAG_NFC_UNKNOWN = 7;

    public NativeNfcManager(Context context, DeviceHostListener listener) {
        mListener = listener;
        initializeNativeStructure();
        mContext = context;
        mMposMgr = new NativeNfcMposManager();
        mT4tNfceeMgr = new NativeT4tNfceeManager();
        mExtFieldMgr = new NativeExtFieldDetectManager();
    }

    public native boolean initializeNativeStructure();

    private native boolean doDownload();

    public native int doGetLastError();

    @Override
    public boolean checkFirmware() {
        return doDownload();
    }
    public native boolean doPartialInitForEseCosUpdate();
    public native boolean doPartialDeinitForEseCosUpdate();

    @Override
    public boolean accessControlForCOSU (int mode) {
        boolean stat = false;
        if(mode == MODE_DEDICATED) {
             stat = doPartialInitForEseCosUpdate();
        }
        else if(mode == MODE_NORMAL) {
             stat = doPartialDeinitForEseCosUpdate();
        }
        return stat;
    }

    private native boolean doInitialize();

    private native int getIsoDepMaxTransceiveLength();

    @Override
    public boolean initialize() {
        boolean ret = doInitialize();
        mIsoDepMaxTransceiveLength = getIsoDepMaxTransceiveLength();
        return ret;
    }

    private native void doEnableDtaMode();

    @Override
    public void enableDtaMode() {
        doEnableDtaMode();
    }

    private native void doDisableDtaMode();

    @Override
    public void disableDtaMode() {
        Log.d(TAG, "disableDtaMode : entry");
        doDisableDtaMode();
    }

    private native void doFactoryReset();

    @Override
    public void factoryReset() {
        doFactoryReset();
    }

    private native boolean doSetPowerSavingMode(boolean flag);

    @Override
    public boolean setPowerSavingMode(boolean flag) {
        return doSetPowerSavingMode(flag);
    }

    private native boolean doSetULPDetMode(boolean flag);

    @Override
    public boolean setULPDetMode(boolean flag) {
        return doSetULPDetMode(flag);
    }

    private native void doShutdown();

    @Override
    public void shutdown() {
        doShutdown();
    }

    private native boolean doDeinitialize();

    @Override
    public boolean isObserveModeSupported() {
        if (!android.nfc.Flags.nfcObserveMode()) {
            return false;
        }

        return mContext.getResources().getBoolean(
            com.android.nfc.R.bool.config_nfcObserveModeSupported);
    }

    @Override
    public native boolean setObserveMode(boolean enabled);

    @Override
    public boolean deinitialize() {
        return doDeinitialize();
    }

    @Override
    public String getName() {
        return DRIVER_NAME;
    }

    @Override
    public native boolean sendRawFrame(byte[] data);

    public native boolean doSetRoutingEntry(int type, int value, int route, int power);
    @Override
    public boolean setRoutingEntry(int type, int value, int route, int power) {
        return(doSetRoutingEntry(type, value, route, power));
    }

    @Override
    public native boolean routeAid(byte[] aid, int route, int aidInfo, int power);

    @Override
    public native boolean unrouteAid(byte[] aid);

    @Override
    public native int getAidTableSize();

    @Override
    public native int   getDefaultAidRoute();

    @Override
    public native int   getDefaultDesfireRoute();

    @Override
    public native int   getT4TNfceePowerState();

    @Override
    public native int   getDefaultMifareCLTRoute();

    @Override
    public native int   getDefaultFelicaCLTRoute();

    @Override
    public native int   getDefaultAidPowerState();

    @Override
    public native int   getDefaultDesfirePowerState();

    @Override
    public native int   getDefaultMifareCLTPowerState();

    @Override
    public native int   getDefaultFelicaCLTPowerState();

    @Override
    public native boolean commitRouting();

    @Override
    public native void setEmptyAidRoute(int deafultAidroute);

    @Override
    public native int[] doGetActiveSecureElementList();

    @Override
    public native int doSetFieldDetectMode(boolean mode);

    @Override
    public native boolean isFieldDetectEnabled();

    @Override
    public native int doStartRssiMode(int rssiNtfTimeIntervalInMillisec);

    @Override
    public native int doStopRssiMode();

    @Override
    public native boolean isRssiEnabled();

    public native int doRegisterT3tIdentifier(byte[] t3tIdentifier);

    @Override
    public native int doNfcSelfTest(int type);

    @Override
    public void registerT3tIdentifier(byte[] t3tIdentifier) {
        synchronized (mLock) {
            int handle = doRegisterT3tIdentifier(t3tIdentifier);
            if (handle != 0xffff) {
                mT3tIdentifiers.put(Integer.valueOf(handle), t3tIdentifier);
            }
        }
    }

    public native void doDeregisterT3tIdentifier(int handle);

    @Override
    public void deregisterT3tIdentifier(byte[] t3tIdentifier) {
        synchronized (mLock) {
            Iterator<Integer> it = mT3tIdentifiers.keySet().iterator();
            while (it.hasNext()) {
                int handle = it.next().intValue();
                byte[] value = mT3tIdentifiers.get(handle);
                if (Arrays.equals(value, t3tIdentifier)) {
                    doDeregisterT3tIdentifier(handle);
                    mT3tIdentifiers.remove(handle);
                    break;
                }
            }
        }
    }

    @Override
    public void clearT3tIdentifiersCache() {
        synchronized (mLock) {
            mT3tIdentifiers.clear();
        }
    }

    @Override
    public native int getLfT3tMax();

    @Override
    public native void doSetScreenState(int screen_state_mask);

    @Override
    public native void doResonantFrequency(boolean isResonantFreq);

    @Override
    public native int getNciVersion();

    private native void doEnableDiscovery(
            int techMask,
            boolean enableLowPowerPolling,
            boolean enableReaderMode,
            boolean enableHostRouting,
            boolean enableP2p,
            boolean restart);
    @Override
    public void enableDiscovery(NfcDiscoveryParameters params, boolean restart) {
        doEnableDiscovery(
                params.getTechMask(),
                params.shouldEnableLowPowerDiscovery(),
                params.shouldEnableReaderMode(),
                params.shouldEnableHostRouting(),
                params.shouldEnableP2p(),
                restart);
    }
    @Override
    public void stopPoll(int mode) {
        mMposMgr.doStopPoll(mode);
    }

    @Override
    public void startPoll() {
        mMposMgr.doStartPoll();
    }
    @Override
    public native void disableDiscovery();

    @Override
    public int mposSetReaderMode(boolean on) {
        return mMposMgr.doMposSetReaderMode(on);
    }

    @Override
    public boolean mposGetReaderMode() {
        return mMposMgr.doMposGetReaderMode();
    }

    @Override
    public native int doEnableDebugNtf (byte fieldValue);

    @Override
    public int doWriteT4tData(byte[] fileId, byte[] data, int length) {
      return mT4tNfceeMgr.doWriteT4tData(fileId, data, length);
    }

    @Override
    public byte[] doReadT4tData(byte[] fileId) {
      return mT4tNfceeMgr.doReadT4tData(fileId);
    }
    @Override
    public int startExtendedFieldDetectMode(int detectionTimeout) {
      return mExtFieldMgr.startExtendedFieldDetectMode(detectionTimeout);
    }
    @Override
    public int stopExtendedFieldDetectMode() {
      return mExtFieldMgr.stopExtendedFieldDetectMode();
    }
    @Override
    public int startCardEmulation() {
      return mExtFieldMgr.startCardEmulation();
    }
    @Override
    public boolean doLockT4tData(boolean lock) {
      return mT4tNfceeMgr.doLockT4tData(lock);
    }

    @Override
    public boolean isLockedT4tData() {
      return mT4tNfceeMgr.isLockedT4tData();
    }

    @Override
    public boolean doClearNdefT4tData() {
      return mT4tNfceeMgr.doClearNdefT4tData();
    }

    private native void doResetTimeouts();

    @Override
    public void resetTimeouts() {
        doResetTimeouts();
    }

    @Override
    public native void doAbort(String msg);

    private native boolean doSetTimeout(int tech, int timeout);

    @Override
    public boolean setTimeout(int tech, int timeout) {
        return doSetTimeout(tech, timeout);
    }

    private native int doGetTimeout(int tech);

    @Override
    public int getTimeout(int tech) {
        return doGetTimeout(tech);
    }

    @Override
    public boolean canMakeReadOnly(int ndefType) {
        return (ndefType == Ndef.TYPE_1 || ndefType == Ndef.TYPE_2);
    }

    @Override
    public int getMaxTransceiveLength(int technology) {
        switch (technology) {
            case (TagTechnology.NFC_A):
            case (TagTechnology.MIFARE_CLASSIC):
            case (TagTechnology.MIFARE_ULTRALIGHT):
                return 253; // PN544 RF buffer = 255 bytes, subtract two for CRC
            case (TagTechnology.NFC_B):
                /////////////////////////////////////////////////////////////////
                // Broadcom: Since BCM2079x supports this, set NfcB max size.
                // return 0; // PN544 does not support transceive of raw NfcB
                return 253; // PN544 does not support transceive of raw NfcB
            case (TagTechnology.NFC_V):
                return 253; // PN544 RF buffer = 255 bytes, subtract two for CRC
            case (TagTechnology.ISO_DEP):
                return mIsoDepMaxTransceiveLength;
            case (TagTechnology.NFC_F):
                return 255;
            default:
                return 0;
        }

    }

    private native void doSetP2pInitiatorModes(int modes);

    @Override
    public void setP2pInitiatorModes(int modes) {
        doSetP2pInitiatorModes(modes);
    }

    private native void doSetP2pTargetModes(int modes);

    @Override
    public void setP2pTargetModes(int modes) {
        doSetP2pTargetModes(modes);
    }

    @Override
    public boolean getExtendedLengthApdusSupported() {
        /* 261 is the default size if extended length frames aren't supported */
        if (getMaxTransceiveLength(TagTechnology.ISO_DEP) > 261) return true;
        return false;
    }

    private native void doDump(FileDescriptor fd);

    @Override
    public void dump(FileDescriptor fd) {
        doDump(fd);
    }

    private native void doEnableScreenOffSuspend();

    @Override
    public boolean enableScreenOffSuspend() {
        doEnableScreenOffSuspend();
        return true;
    }

    private native void doDisableScreenOffSuspend();

    @Override
    public boolean disableScreenOffSuspend() {
        doDisableScreenOffSuspend();
        return true;
    }

    private native void doRestartRFDiscovery();

    @Override
    public void restartRFDiscovery() {
        doRestartRFDiscovery();
    }

    private native boolean doSetNfcSecure(boolean enable);

    @Override
    public boolean setNfcSecure(boolean enable) {
        return doSetNfcSecure(enable);
    }

    @Override
    public native String getNfaStorageDir();

    private native void doStartStopPolling(boolean start);

    @Override
    public void startStopPolling(boolean start) {
        doStartStopPolling(start);
    }

    @Override
    public native byte[] getRoutingTable();

    @Override
    public native int getMaxRoutingTableSize();


    private native NfcVendorNciResponse nativeSendRawVendorCmd(
            int mt, int gid, int oid, byte[] payload);

    @Override
    public NfcVendorNciResponse sendRawVendorCmd(int mt, int gid, int oid, byte[] payload) {
        NfcVendorNciResponse res= nativeSendRawVendorCmd(mt, gid, oid, payload);
        return res;
    }

    /** Notifies Ndef Message (TODO: rename into notifyTargetDiscovered) */
    private void notifyNdefMessageListeners(NativeNfcTag tag) {
        mListener.onRemoteEndpointDiscovered(tag);
    }
    private void notifySeListenActivated() {
        mListener.onSeListenActivated();
    }

    private void notifySeListenDeactivated() {
        mListener.onSeListenDeactivated();
    }

    private void notifySrdEvt(int event) {
        mListener.onNotifySrdEvt(event);
    }

    private void notifyEfdmEvt(int efdmEvt) {
        mListener.onNotifyEfdmEvt(efdmEvt);
    }

    /* Reader over SWP/SCR listeners*/
    private void notifyonMposManagerEvents(int event) {
        mListener.onScrNotifyEvents(event);
    }

    private void notifyHostEmuActivated(int technology) {
        mListener.onHostCardEmulationActivated(technology);
    }

    private void notifyHostEmuData(int technology, byte[] data) {
        mListener.onHostCardEmulationData(technology, data);
    }

    private void notifyNfcDebugInfo(int len, byte[] data) {
        mListener.onLxDebugConfigData(len, data);
    }

    private void notifyHostEmuDeactivated(int technology) {
        mListener.onHostCardEmulationDeactivated(technology);
    }

    private void notifyRfFieldActivated() {
        mListener.onRemoteFieldActivated();
    }

    private void notifyRfFieldDeactivated() {
        mListener.onRemoteFieldDeactivated();
    }

    private void notifyHwErrorReported() {
        mListener.onHwErrorReported();
    }

    private void notifyCoreGenericError(int errorCode) {
        mListener.notifyCoreGenericError(errorCode);
    }

    private void notifyTransactionListeners(byte[] aid, byte[] data, String evtSrc) {
        mListener.onNfcTransactionEvent(aid, data, evtSrc);
    }

    private void notifyEeUpdated() {
        mListener.onEeUpdated();
    }

    private void notifyPollingLoopFrame(int data_len, byte[] p_data) {
        if (data_len < MIN_POLLING_FRAME_TLV_SIZE) {
            return;
        }
        Bundle frame = new Bundle();
        final int header_len = 2;
        int pos = header_len;
        final int TLV_len_offset = 0;
        final int TLV_type_offset = 2;
        final int TLV_timestamp_offset = 3;
        final int TLV_gain_offset = 7;
        final int TLV_data_offset = 8;
        while (pos + TLV_len_offset < data_len) {
        int type = p_data[pos + TLV_type_offset];
        int length = p_data[pos + TLV_len_offset];
        if (pos + length + 1 > data_len) {
            // Frame is bigger than buffer.
            Log.e(TAG, "Polling frame data is longer than buffer data length.");
            break;
        }
        switch (type) {
            case TAG_FIELD_CHANGE:
                frame.putChar(
                    HostApduService.POLLING_LOOP_TYPE_KEY,
                    p_data[pos + TLV_data_offset] != 0x00
                        ? HostApduService.POLLING_LOOP_TYPE_ON
                        : HostApduService.POLLING_LOOP_TYPE_OFF);
                break;
            case TAG_NFC_A:
                frame.putChar(HostApduService.POLLING_LOOP_TYPE_KEY,
                    HostApduService.POLLING_LOOP_TYPE_A);
                break;
            case TAG_NFC_B:
                frame.putChar(HostApduService.POLLING_LOOP_TYPE_KEY,
                    HostApduService.POLLING_LOOP_TYPE_B);
                break;
            case TAG_NFC_F:
                frame.putChar(HostApduService.POLLING_LOOP_TYPE_KEY,
                    HostApduService.POLLING_LOOP_TYPE_F);
                break;
            case TAG_NFC_UNKNOWN:
                frame.putChar(
                    HostApduService.POLLING_LOOP_TYPE_KEY,
                    HostApduService.POLLING_LOOP_TYPE_UNKNOWN);
                frame.putByteArray(
                    HostApduService.POLLING_LOOP_DATA_KEY,
                    Arrays.copyOfRange(
                        p_data, pos + TLV_data_offset, pos + TLV_timestamp_offset + length));
                break;
            default:
                Log.e(TAG, "Unknown polling loop tag type.");
        }
        if (pos + TLV_gain_offset <= data_len) {
            byte gain = p_data[pos + TLV_gain_offset];
            frame.putByte(HostApduService.POLLING_LOOP_GAIN_KEY, gain);
        }
        if (pos + TLV_timestamp_offset + 3 < data_len) {
            int timestamp = ByteBuffer.wrap(p_data, pos + TLV_timestamp_offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            frame.putInt(HostApduService.POLLING_LOOP_TIMESTAMP_KEY, timestamp);
        }
        pos += (length + 2);
        }
        mListener.onPollingLoopDetected(frame);
    }

    @Override
    public native void setDiscoveryTech(int pollTech, int listenTech);

    @Override
    public native void resetDiscoveryTech();

    @Override
    public native void clearRoutingEntry(int clearFlags);

    @Override
    public native void setIsoDepProtocolRoute(int route);

    @Override
    public native void setTechnologyABRoute(int route);

    /**
     * Notifies Tag abort operation
     */
    private void notifyTagAbort() {
        mListener.notifyTagAbort();
    }
/* NXP extension are here */
    @Override
    public native int getFWVersion();
    @Override
    public native boolean isNfccBusy();
    @Override
    public native int setTransitConfig(String configs);
    @Override
    public native int getRemainingAidTableSize();
    @Override
    public native int doselectUicc(int uiccSlot);
    @Override
    public native int doGetSelectedUicc();
    @Override
    public native int setPreferredSimSlot(int uiccSlot);
}
