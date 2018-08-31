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
 *  The original Work has been changed by NXP Semiconductors.
 *
 *  Copyright (C) 2015-2018 NXP Semiconductors
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
package com.android.nfc.dhimpl;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.content.SharedPreferences;
import android.nfc.ErrorCodes;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.util.Log;
import java.io.File;

import java.util.HashMap;
import java.util.Map;
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
    private static final long FIRMWARE_MODTIME_DEFAULT = -1;
    static final String PREF = "NciDeviceHost";

    static final int DEFAULT_LLCP_MIU = 1980;
    static final int DEFAULT_LLCP_RWSIZE = 2;
    static final int PN547C2_ID = 1;
    static final int PN65T_ID = 2;
    static final int PN548C2_ID = 3;
    static final int PN66T_ID = 4;
    static final int PN551_ID = 5;
    static final int PN67T_ID = 6;
    static final int PN553_ID = 7;
    static final int PN80T_ID = 8;

    static final String DRIVER_NAME = "android-nci";

    private static final byte[][] EE_WIPE_APDUS = {};

    static {
        System.loadLibrary("nfc_nci_jni");
    }

    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String INTERNAL_TARGET_DESELECTED_ACTION = "com.android.nfc.action.INTERNAL_TARGET_DESELECTED";

    /* Native structure */
    private long mNative;

    private int mIsoDepMaxTransceiveLength;
    private final DeviceHostListener mListener;
    private final NativeNfcMposManager mMposMgr;
    private final Context mContext;
    private Map<String, Integer> mNfcid2ToHandle;
    private final Object mLock = new Object();
    private final HashMap<Integer, byte[]> mT3tIdentifiers = new HashMap<Integer, byte[]>();
    public NativeNfcManager(Context context, DeviceHostListener listener) {
        mListener = listener;
        initializeNativeStructure();
        mContext = context;
        mNfcid2ToHandle = new HashMap<String, Integer>();
        mMposMgr = new NativeNfcMposManager();
    }

    public native boolean initializeNativeStructure();

    private native boolean doDownload();
    @Override
    public boolean download() {
        return doDownload();
    }

    public native int doGetLastError();

    @Override
    public void checkFirmware() {
        if(doDownload()) {
            Log.d(TAG,"FW Download Success");
        }
    else {
            Log.d(TAG,"FW Download Failed");
        }
    }

    private native boolean doInitialize();

    private native int getIsoDepMaxTransceiveLength();

    @Override
    public boolean initialize() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        if (prefs.getBoolean(NativeNfcSecureElement.PREF_SE_WIRED, false)) {
            try {
                Thread.sleep (12000);
                editor.putBoolean(NativeNfcSecureElement.PREF_SE_WIRED, false);
                editor.apply();
            } catch (InterruptedException e) { }
        }

        boolean ret = doInitialize();
        mIsoDepMaxTransceiveLength = getIsoDepMaxTransceiveLength();
        return ret;
    }

    private native void doEnableDtaMode();

    @Override
    public void enableDtaMode() {
        Log.d(TAG,"enableDtaMode : entry");
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
        SharedPreferences prefs = mContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        editor.putBoolean(NativeNfcSecureElement.PREF_SE_WIRED, false);
        editor.apply();

        return doDeinitialize();
    }

    @Override
    public String getName() {
        return DRIVER_NAME;
    }

    @Override
    public native boolean sendRawFrame(byte[] data);

    @Override
    public boolean routeAid(byte[] aid, int route, int powerState, int aidInfo) {

        boolean status = true;
        //if(mIsAidFilterSupported) {
            //Prepare a cache of AIDs, and only send when vzwSetFilterList is called.
          //  mAidFilter.addAppAidToCache(aid, route, powerState);
       // } else {

            status = doRouteAid(aid, route, powerState, aidInfo);

        //}

        return status;
    }

    public native boolean doRouteAid(byte[] aid, int route, int powerState, int aidInfo);

    @Override
    public native boolean routeApduPattern(int route, int powerState, byte[] apduData, byte[] apduMask);

    @Override
    public native boolean setDefaultRoute(int defaultRouteEntry, int defaultProtoRouteEntry, int defaultTechRouteEntry);

    @Override
    public boolean unrouteAid(byte[] aid) {
    //    if(mIsAidFilterSupported) {
            //Remove AID entry from cache.
    //         mAidFilter.removeAppAidToCache(aid);
    //    }

        return doUnrouteAid(aid);
    }

    public native boolean doUnrouteAid(byte[] aid);

    public native boolean clearAidTable();

    @Override
    public native void doSetProvisionMode(boolean provisionMode);

    @Override
    public native int getRemainingAidTableSize();

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
    public native int   doNfcSelfTest(int type);

    @Override
    public native int   getDefaultDesfirePowerState();

    @Override
    public native int   getDefaultMifareCLTPowerState();

    @Override
    public native boolean unrouteApduPattern(byte[] apduData);

    @Override
    public native void doSetScreenOrPowerState(int state);

    @Override
    public native void doSetScreenState(int screen_state_mask);

    @Override
    public native void doEnablep2p(boolean p2pFlag);

    public native boolean doSetRoutingEntry(int type, int value, int route, int power);
    @Override
    public boolean setRoutingEntry(int type, int value, int route, int power) {
        return(doSetRoutingEntry(type, value, route, power));
    }
    public native boolean doClearRoutingEntry(int type );

    @Override
    public boolean clearRoutingEntry( int type ) {
        return(doClearRoutingEntry( type ));
    }

    @Override
    public native void doSetSecureElementListenTechMask(int tech_mask);

    @Override
    public native int getNciVersion();

    private native void doEnableDiscovery(int techMask,
                                          boolean enableLowPowerPolling,
                                          boolean enableReaderMode,
                                          boolean enableP2p,
                                          boolean restart);

    @Override
    public void enableDiscovery(NfcDiscoveryParameters params, boolean restart) {
        doEnableDiscovery(params.getTechMask(), params.shouldEnableLowPowerDiscovery(),
                params.shouldEnableReaderMode(), params.shouldEnableP2p(), restart);
    }

    @Override
    public native void disableDiscovery();

    @Override
    public native int[] doGetSecureElementList();

    @Override
    public native void doSelectSecureElement(int seID);

    @Override
    public native void doActivateSecureElement(int seID);

    @Override
    public native void doDeselectSecureElement(int seID);

    @Override
    public native void doSetSEPowerOffState(int seID, boolean enable);

    @Override
    public native void setDefaultTechRoute(int seID, int tech_switchon, int tech_switchoff);

    @Override
    public native void setDefaultProtoRoute(int seID, int proto_switchon, int proto_switchoff);

    @Override
    public native int getChipVer();

    @Override
    public native int setTransitConfig(String configs);


    @Override
    public native int getNfcInitTimeout();

    @Override
    public native int JCOSDownload();

    @Override
    public native void doSetNfcMode(int nfcMode);

    @Override
    public native int GetDefaultSE();

    @Override
    public native boolean isVzwFeatureEnabled();

    @Override
    public native boolean isNfccBusy();

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

    @Override
    public native void updateScreenState();

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
    public native boolean doCheckJcopDlAtBoot();

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
        return (ndefType == Ndef.TYPE_1 || ndefType == Ndef.TYPE_2 ||
                ndefType == Ndef.TYPE_MIFARE_CLASSIC);
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

    @Override
    public native int setEmvCoPollProfile(boolean enable, int route);

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
        if (getMaxTransceiveLength(TagTechnology.ISO_DEP) > 261) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public byte[][] getWipeApdus() {
        return EE_WIPE_APDUS;
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

    //private native void doEnableReaderMode(int technologies);
    //@Override
    //public boolean enableScreenOffSuspend() {
      //  doEnableScreenOffSuspend();
        //return true;
    //}

    //private native void doDisableScreenOffSuspend();
    //@Override
    //public boolean disableScreenOffSuspend() {
     //   doDisableScreenOffSuspend();
       // return true;
    //}


    private native void doCommitRouting();

    @Override
    public native int doGetSecureElementTechList();

    public native int doRegisterT3tIdentifier(byte[] t3tIdentifier);

    @Override
    public void registerT3tIdentifier(byte[] t3tIdentifier) {
         Log.d(TAG, " registerT3tIdentifier entry");
        synchronized (mLock) {
            int handle = doRegisterT3tIdentifier(t3tIdentifier);
            if (handle != 0xffff) {
                mT3tIdentifiers.put(Integer.valueOf(handle), t3tIdentifier);
            }
        }
        Log.d(TAG, "registerT3tIdentifier exit");
    }

    public native void doDeregisterT3tIdentifier(int handle);

    @Override
    public void deregisterT3tIdentifier(byte[] t3tIdentifier) {
        Log.d(TAG, "deregisterT3tIdentifier entry");
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
        Log.d(TAG, "deregisterT3tIdentifier exit");
    }

    @Override
    public void clearT3tIdentifiersCache() {
        Log.d(TAG, "clearT3tIdentifiersCache entry");
        synchronized (mLock) {
            mT3tIdentifiers.clear();
        }
        Log.d(TAG, "clearT3tIdentifiersCache exit");
    }

    @Override
    public native int getLfT3tMax();


    @Override
    public native int[] doGetActiveSecureElementList();


    public native byte[] doGetSecureElementUid();

    @Override
    public byte[] getSecureElementUid()
    {
        byte[] buff;
        buff = doGetSecureElementUid();
        if(buff==null)
        {
            //Avoiding Null pointer Exception creating new byte array
            buff =  new byte[0];
            Log.d(TAG,"buff : " + buff);
        }
        return buff;
    }
    @Override
    public void commitRouting() {
        doCommitRouting();
    }

    @Override
    public native void doPrbsOn(int prbs, int hw_prbs, int tech, int rate);

    @Override
    public native void doPrbsOff();

    @Override
    public native int SWPSelfTest(int ch);

    @Override
    public native int getFWVersion();

    @Override
    public native void doSetEEPROM(byte[] val);

    @Override
    public native byte[] doGetRouting();

    @Override
    public native int doGetSeInterface(int type);

    @Override
    public native int doselectUicc(int uiccSlot);

    @Override
    public native int doGetSelectedUicc();

    /**
     * This api internally used to set preferred sim slot to select UICC
     */
    @Override
    public native int setPreferredSimSlot(int uiccSlot);

    @Override
    public native byte[] readerPassThruMode(byte status, byte modulationTyp);

    @Override public native byte[] transceiveAppData(byte[] data);

    /**
     * Notifies Ndef Message (TODO: rename into notifyTargetDiscovered)
     */
    private void notifyNdefMessageListeners(NativeNfcTag tag) {
        mListener.onRemoteEndpointDiscovered(tag);
    }

    /**
     * Notifies transaction
     */
    private void notifyTargetDeselected() {
        mListener.onCardEmulationDeselected();
    }

    /**
     * Notifies transaction
     */
    private void notifyConnectivityListeners(int evtSrc) {
        mListener.onConnectivityEvent(evtSrc);
    }

    /**
     * Notifies transaction
     */
    private void notifyEmvcoMultiCardDetectedListeners() {
        mListener.onEmvcoMultiCardDetectedEvent();
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

    private void notifySeFieldActivated() {
        mListener.onRemoteFieldActivated();
    }

    private void notifySeFieldDeactivated() {
        mListener.onRemoteFieldDeactivated();
    }

    private void notifyJcosDownloadInProgress(int enable) {
        mListener.onRestartWatchDog(enable);
    }

    private void notifyFwDwnldRequested() {
        mListener.onFwDwnldReqRestartNfc();
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

    private void notifyonETSIReaderModeSwpTimeout(int disc_ntf_timeout) {
        mListener.onETSIReaderModeSwpTimeout(disc_ntf_timeout);
    }

    private void notifyonETSIReaderModeRestart() {
        mListener.onETSIReaderModeRestart();
    }

    private void notifySeListenActivated() {
        mListener.onSeListenActivated();
    }

    private void notifySeListenDeactivated() {
        mListener.onSeListenDeactivated();
    }

    private void notifySeApduReceived(byte[] apdu) {
        mListener.onSeApduReceived(apdu);
    }

    private void notifySeEmvCardRemoval() {
        mListener.onSeEmvCardRemoval();
    }

    private void notifySeMifareAccess(byte[] block) {
        mListener.onSeMifareAccess(block);
    }

    private void notifyHostEmuActivated(int technology) {
        mListener.onHostCardEmulationActivated(technology);
    }

    private void notifyT3tConfigure() {
        mListener.onNotifyT3tConfigure();
    }

    private void notifyReRoutingEntry() {
        mListener.onNotifyReRoutingEntry();
    }

    private void notifyHostEmuData(int technology, byte[] data) {
        mListener.onHostCardEmulationData(technology, data);
    }

    private void notifyHostEmuDeactivated(int technology) {
        mListener.onHostCardEmulationDeactivated(technology);
    }

    private void notifyAidRoutingTableFull() {
        mListener.onAidRoutingTableFull();
    }

    private void notifyRfFieldActivated() {
        mListener.onRemoteFieldActivated();
    }

    private void notifyRfFieldDeactivated() {
        mListener.onRemoteFieldDeactivated();
    }

   private void notifyUiccStatusEvent(int uiccStat) {
       mListener.onUiccStatusEvent(uiccStat);
   }

    private void notifyTransactionListeners(byte[] aid, byte[] data, String evtSrc) {
        mListener.onNfcTransactionEvent(aid, data, evtSrc);
    }

    static String toHexString(byte[] buffer, int offset, int length) {
        final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
        char[] chars = new char[2 * length];
        for (int j = offset; j < offset + length; ++j) {
            chars[2 * (j - offset)] = HEX_CHARS[(buffer[j] & 0xF0) >>> 4];
            chars[2 * (j - offset) + 1] = HEX_CHARS[buffer[j] & 0x0F];
        }
        return new String(chars);
    }

}
