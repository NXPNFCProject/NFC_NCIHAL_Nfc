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
*  Copyright 2018 NXP
*
******************************************************************************/
package com.android.nfc.dhimpl;

import android.content.Context;
import android.nfc.ErrorCodes;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.util.Log;

import com.android.nfc.DeviceHost;
import com.android.nfc.LlcpException;
import com.android.nfc.NfcDiscoveryParameters;

import java.io.FileDescriptor;
import java.util.Arrays;
import java.util.Iterator;
import java.util.HashMap;

/**
 * Native interface to the NFC Manager functions
 */
public class NativeNfcManager implements DeviceHost {
    private static final String TAG = "NativeNfcManager";
    static final String PREF = "NciDeviceHost";

    static final int DEFAULT_LLCP_MIU = 1980;
    static final int DEFAULT_LLCP_RWSIZE = 2;

    static final String DRIVER_NAME = "android-nci";

    static {
        System.loadLibrary("nfc_nci_jni");
    }

    /* Native structure */
    private long mNative;

    private int mIsoDepMaxTransceiveLength;
    private final DeviceHostListener mListener;
    private final NativeNfcMposManager mMposMgr;
    private final Context mContext;

    private final Object mLock = new Object();
    private final HashMap<Integer, byte[]> mT3tIdentifiers = new HashMap<Integer, byte[]>();

    public NativeNfcManager(Context context, DeviceHostListener listener) {
        mListener = listener;
        initializeNativeStructure();
        mContext = context;
        mMposMgr = new NativeNfcMposManager();
    }

    public native boolean initializeNativeStructure();

    private native boolean doDownload();

    public native int doGetLastError();

    @Override
    public void checkFirmware() {
        doDownload();
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
        Log.d(TAG,"disableDtaMode : entry");
        doDisableDtaMode();
    }

    private native void doFactoryReset();

    @Override
    public void factoryReset() {
        doFactoryReset();
    }

    private native void doShutdown();

    @Override
    public void shutdown() {
        doShutdown();
    }

    private native boolean doDeinitialize();

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

    @Override
    public native boolean routeAid(byte[] aid, int route, int aidInfo, int powerState);

    @Override
    public native boolean unrouteAid(byte[] aid);

    @Override
    public native boolean routeApduPattern(int route, int powerState, byte[] apduData, byte[] apduMask);

    @Override
    public native int getAidTableSize();

    @Override
    public native int   getDefaultAidRoute();

    @Override
    public native int   getDefaultDesfireRoute();

    @Override
    public native int   getDefaultMifareCLTRoute();

    @Override
    public native int   getDefaultAidPowerState();

    @Override
    public native int   getDefaultDesfirePowerState();

    @Override
    public native int   getDefaultMifareCLTPowerState();

    @Override
    public native boolean commitRouting();

    @Override
    public native boolean unrouteApduPattern(byte[] apduData);
     @Override
    public native void doChangeDiscoveryTech(int pollTech, int listenTech);

    @Override
    public native boolean setDefaultRoute(int defaultRouteEntry, int defaultProtoRouteEntry, int defaultTechRouteEntry);

    @Override
    public native int[] doGetActiveSecureElementList();

    public native int doRegisterT3tIdentifier(byte[] t3tIdentifier);

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
    public native int getNciVersion();

    private native void doEnableDiscovery(int techMask,
                                          boolean enableLowPowerPolling,
                                          boolean enableReaderMode,
                                          boolean enableHostRouting,
                                          boolean enableP2p,
                                          boolean restart);
    @Override
    public void enableDiscovery(NfcDiscoveryParameters params, boolean restart) {
        doEnableDiscovery(params.getTechMask(), params.shouldEnableLowPowerDiscovery(),
                params.shouldEnableReaderMode(), params.shouldEnableHostRouting(),
                params.shouldEnableP2p(), restart);
    }

    @Override
    public native void disableDiscovery();

   @Override
    public void setEtsiReaederState(int newState) {
        mMposMgr.doSetEtsiReaederState(newState);
    }

    @Override
    public int getEtsiReaederState() {
        int state;
        state = mMposMgr.doGetEtsiReaederState();
        return state;
    }

    @Override
    public void etsiReaderConfig(int eeHandle) {
        mMposMgr.doEtsiReaderConfig(eeHandle);
    }

    @Override
    public void notifyEEReaderEvent(int evt) {
        mMposMgr.doNotifyEEReaderEvent(evt);
    }

    @Override
    public void etsiInitConfig() {
        mMposMgr.doEtsiInitConfig();
    }

    @Override
    public void etsiResetReaderConfig() {
        mMposMgr.doEtsiResetReaderConfig();
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
    public int mposSetReaderMode(boolean on) {
        return mMposMgr.doMposSetReaderMode(on);
    }

    @Override
    public boolean mposGetReaderMode() {
        return mMposMgr.doMposGetReaderMode();
    }

    private native NativeLlcpConnectionlessSocket doCreateLlcpConnectionlessSocket(int nSap,
            String sn);

    @Override
    public LlcpConnectionlessSocket createLlcpConnectionlessSocket(int nSap, String sn)
            throws LlcpException {
        LlcpConnectionlessSocket socket = doCreateLlcpConnectionlessSocket(nSap, sn);
        if (socket != null) {
            return socket;
        } else {
            /* Get Error Status */
            int error = doGetLastError();

            Log.d(TAG, "failed to create llcp socket: " + ErrorCodes.asString(error));

            switch (error) {
                case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                    throw new LlcpException(error);
                default:
                    throw new LlcpException(ErrorCodes.ERROR_SOCKET_CREATION);
            }
        }
    }

    private native NativeLlcpServiceSocket doCreateLlcpServiceSocket(int nSap, String sn, int miu,
            int rw, int linearBufferLength);
    @Override
    public LlcpServerSocket createLlcpServerSocket(int nSap, String sn, int miu,
            int rw, int linearBufferLength) throws LlcpException {
        LlcpServerSocket socket = doCreateLlcpServiceSocket(nSap, sn, miu, rw, linearBufferLength);
        if (socket != null) {
            return socket;
        } else {
            /* Get Error Status */
            int error = doGetLastError();

            Log.d(TAG, "failed to create llcp socket: " + ErrorCodes.asString(error));

            switch (error) {
                case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                    throw new LlcpException(error);
                default:
                    throw new LlcpException(ErrorCodes.ERROR_SOCKET_CREATION);
            }
        }
    }

    private native NativeLlcpSocket doCreateLlcpSocket(int sap, int miu, int rw,
            int linearBufferLength);
    @Override
    public LlcpSocket createLlcpSocket(int sap, int miu, int rw,
            int linearBufferLength) throws LlcpException {
        LlcpSocket socket = doCreateLlcpSocket(sap, miu, rw, linearBufferLength);
        if (socket != null) {
            return socket;
        } else {
            /* Get Error Status */
            int error = doGetLastError();

            Log.d(TAG, "failed to create llcp socket: " + ErrorCodes.asString(error));

            switch (error) {
                case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                    throw new LlcpException(error);
                default:
                    throw new LlcpException(ErrorCodes.ERROR_SOCKET_CREATION);
            }
        }
    }

    @Override
    public native boolean doCheckLlcp();

    @Override
    public native boolean doActivateLlcp();

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
                //return 0; // PN544 does not support transceive of raw NfcB
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
        if (getMaxTransceiveLength(TagTechnology.ISO_DEP) > 261)
            return true;
        return false;
    }

    @Override
    public int getDefaultLlcpMiu() {
        return DEFAULT_LLCP_MIU;
    }

    @Override
    public int getDefaultLlcpRwSize() {
        return DEFAULT_LLCP_RWSIZE;
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

    /**
     * Notifies Ndef Message (TODO: rename into notifyTargetDiscovered)
     */
    private void notifyNdefMessageListeners(NativeNfcTag tag) {
        mListener.onRemoteEndpointDiscovered(tag);
    }
    private void notifySeListenActivated() {
        mListener.onSeListenActivated();
    }

    private void notifySeListenDeactivated() {
        mListener.onSeListenDeactivated();
    }

    private void notifySeInitialized() {
        mListener.onSeInitialized();
    }
    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    private void notifyLlcpLinkActivation(NativeP2pDevice device) {
        mListener.onLlcpLinkActivated(device);
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    private void notifyLlcpLinkDeactivated(NativeP2pDevice device) {
        mListener.onLlcpLinkDeactivated(device);
    }

    /**
     * Notifies first packet received from remote LLCP
     */
    private void notifyLlcpLinkFirstPacketReceived(NativeP2pDevice device) {
        mListener.onLlcpFirstPacketReceived(device);
    }

    /* Reader over SWP listeners*/
    private void notifyETSIReaderRequested(boolean istechA, boolean istechB) {
        mListener.onETSIReaderRequestedEvent(istechA, istechB);
    }

    private void notifyETSIReaderRequestedFail(int FailureCause) {
        mListener.onETSIReaderRequestedFail(FailureCause);
    }

    private void notifyonETSIReaderModeStartConfig(int eeHandle) {
        mListener.onETSIReaderModeStartConfig(eeHandle);
    }

    private void notifyonETSIReaderModeStopConfig(int disc_ntf_timeout) {
        mListener.onETSIReaderModeStopConfig(disc_ntf_timeout);
    }

    private void notifyHostEmuActivated(int technology) {
        mListener.onHostCardEmulationActivated(technology);
    }

    private void notifyonETSIReaderModeSwpTimeout(int disc_ntf_timeout) {
        mListener.onETSIReaderModeSwpTimeout(disc_ntf_timeout);
    }

    private void notifyonETSIReaderModeRestart() {
        mListener.onETSIReaderModeRestart();
    }

    private void notifyHostEmuData(int technology, byte[] data) {
        mListener.onHostCardEmulationData(technology, data);
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

    private void notifyTransactionListeners(byte[] aid, byte[] data, String evtSrc) {
        mListener.onNfcTransactionEvent(aid, data, evtSrc);
    }
/* NXP extension are here */
    @Override
    public native boolean doCheckJcopDlAtBoot();
    @Override
    public native int JCOSDownload();
}
