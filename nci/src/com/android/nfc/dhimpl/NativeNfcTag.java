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
*  Copyright 2020-2021, 2024 NXP
*
******************************************************************************/

package com.android.nfc.dhimpl;

import android.annotation.Nullable;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcBarcode;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.nfc.tech.TagTechnology;
import android.os.Bundle;
import android.util.Log;

import com.android.nfc.DeviceHost;
import com.android.nfc.DeviceHost.TagEndpoint;

/** Native interface to the NFC tag functions */
public class NativeNfcTag implements TagEndpoint {
    static final boolean DBG = true;

    static final int STATUS_CODE_TARGET_LOST = 146;

    private int[] mTechList;
    private int[] mTechHandles;
    private int[] mTechLibNfcTypes;
    private Bundle[] mTechExtras;
    private byte[][] mTechPollBytes;
    private byte[][] mTechActBytes;
    private byte[] mUid;
    // Based on flag send T2T tag classification request
    private boolean mClassifyT2T = true;

    // mConnectedHandle stores the *real* libnfc handle
    // that we're connected to.
    private int mConnectedHandle;

    // mConnectedTechIndex stores to which technology
    // the upper layer stack is connected. Note that
    // we may be connected to a libnfchandle without being
    // connected to a technology - technology changes
    // may occur runtime, whereas the underlying handle
    // could stay present. Usually all technologies are on the
    // same handle, with the exception of multi-protocol
    // tags.
    private int mConnectedTechIndex; // Index in mTechHandles

    private final String TAG = "NativeNfcTag";

    private boolean mIsPresent; // Whether the tag is known to be still present

    private PresenceCheckWatchdog mWatchdog;

    private boolean mIsRemovalDetectionModeReq = false;

    class PresenceCheckWatchdog extends Thread {

        private final int watchdogTimeout;
        private DeviceHost.TagDisconnectedCallback tagDisconnectedCallback;

        private boolean isPresent = true;
        private boolean isStopped = false;
        private boolean isPaused = false;
        private boolean doCheck = true;

        PresenceCheckWatchdog(
                int presenceCheckDelay, @Nullable DeviceHost.TagDisconnectedCallback callback) {
            watchdogTimeout = presenceCheckDelay;
            tagDisconnectedCallback = callback;
        }

        public synchronized int getPresenceCheckTimeout() {
            return watchdogTimeout;
        }

        public synchronized void pause() {
            isPaused = true;
            doCheck = false;
            this.notifyAll();
        }

        public synchronized void doResume() {
            isPaused = false;
            // We don't want to resume presence checking immediately,
            // but go through at least one more wait period.
            doCheck = false;
            this.notifyAll();
        }

        public synchronized void end(boolean disableCallback) {
            isStopped = true;
            doCheck = false;
            if (disableCallback) {
                tagDisconnectedCallback = null;
            }
            this.notifyAll();
        }

        @Override
        public void run() {
            synchronized (this) {
                if (DBG) Log.d(TAG, "Starting background presence check");
                while (isPresent && !isStopped) {
                    try {
                        if (!isPaused) {
                            doCheck = true;
                        }
                        this.wait(watchdogTimeout);
                        if (doCheck) {
                            isPresent = doPresenceCheck();
                        } else {
                            // 1) We are paused, waiting for unpause
                            // 2) We just unpaused, do pres check in next iteration
                            //       (after watchdogTimeout ms sleep)
                            // 3) We just set the timeout, wait for this timeout
                            //       to expire once first.
                            // 4) We just stopped, exit loop anyway
                        }
                    } catch (InterruptedException e) {
                        // Activity detected, loop
                    }
                }
            }

            synchronized (NativeNfcTag.this) {
                mIsPresent = false;
            }

            if (!resetIfRemoveDetectionModeReq()) {
                // Restart the polling loop
                Log.d(TAG, "Tag lost, restarting polling loop");
                doDisconnect();
            }
            if (tagDisconnectedCallback != null) {
                tagDisconnectedCallback.onTagDisconnected();
            }
            if (DBG) Log.d(TAG, "Stopping background presence check");
        }
    }

    private synchronized boolean resetIfRemoveDetectionModeReq() {
        if (mIsRemovalDetectionModeReq) {
            Log.d(TAG, "Poll Removal Detection Mode Requested");
            mIsRemovalDetectionModeReq = false;
            return true;
        } else {
            return false;
        }
    }

    private native int doConnect(int handle);

    public synchronized int connectWithStatus(int technology) {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        int status = -1;
        for (int i = 0; i < mTechList.length; i++) {
            if (mTechList[i] == technology) {
                // Get the handle and connect, if not already connected
                if (mConnectedHandle != mTechHandles[i]) {
                    // We're not yet connected to this handle, there are
                    // a few scenario's here:
                    // 1) We are not connected to anything yet - allow
                    // 2) We are connected to a technology which has
                    //    a different handle (multi-protocol tag); we support
                    //    switching to that.
                    if (mConnectedHandle == -1) {
                        // Not connected yet
                        // status = doConnect(mTechHandles[i]);
                        status = doConnect(i);
                    } else {
                        // Connect to a tech with a different handle
                        Log.d(TAG, "Connect to a tech with a different handle");
                        // status = reconnectWithStatus(mTechHandles[i]);
                        status = reconnectWithStatus(i);
                    }
                    if (status == 0) {
                        mConnectedHandle = mTechHandles[i];
                        mConnectedTechIndex = i;
                    }
                } else {
                    // 1) We are connected to a technology which has the same
                    //    handle; we do not support connecting at a different
                    //    level (libnfc auto-activates to the max level on
                    //    any handle).
                    // 2) We are connecting to the ndef technology - always
                    //    allowed.
                    if ((technology == TagTechnology.NDEF)
                            || (technology == TagTechnology.NDEF_FORMATABLE)) {
                        // special case for NDEF, this will cause switch to ISO_DEP frame intf
                        i = 0;
                        // status = 0;
                    }
                    status = reconnectWithStatus(i);
                    if (status == 0) {
                        mConnectedTechIndex = i;
                        // Handle was already identical
                    }
                }
                break;
            }
        }
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return status;
    }

    @Override
    public synchronized boolean connect(int technology) {
        return connectWithStatus(technology) == 0;
    }

    @Override
    public boolean isPresenceCheckStopped() {
        PresenceCheckWatchdog watchdog;

        synchronized (this) {
            watchdog = mWatchdog;
        }
        if (watchdog == null) {
           return true;
        }
        return false;
    }

    @Override
    public void prepareForRemovalDetectionMode() {
        synchronized (this) {
            mIsRemovalDetectionModeReq = true;
        }
        doTerminatePresenceCheckThread(true);
        clearConnectedContext();
    }

    @Override
    public synchronized void stopPresenceChecking() {
        mIsPresent = false;
        if (mWatchdog != null) {
            mWatchdog.end(true);
        }
    }

    @Override
    public synchronized void startPresenceChecking(
            int presenceCheckDelay, DeviceHost.TagDisconnectedCallback callback) {
        // Once we start presence checking, we allow the upper layers
        // to know the tag is in the field.
        mIsPresent = true;
        if (mWatchdog == null) {
            mWatchdog = new PresenceCheckWatchdog(presenceCheckDelay, callback);
            mWatchdog.start();
        }
    }

    @Override
    public synchronized boolean isPresent() {
        // Returns whether the tag is still in the field to the best
        // of our knowledge.
        return mIsPresent;
    }

    native boolean doDisconnect();

    private boolean doTerminatePresenceCheckThread(boolean disableCallback) {

        PresenceCheckWatchdog watchdog;
        synchronized (this) {
            mIsPresent = false;
            watchdog = mWatchdog;
        }
        if (watchdog != null) {
            // Watchdog has already disconnected or will do it
            watchdog.end(disableCallback);
            try {
                watchdog.join();
            } catch (InterruptedException e) {
                // Should never happen.
            }
            synchronized (this) {
                mWatchdog = null;
            }
            return true;
        }
        return false;
    }
    private void clearConnectedContext() {
        mConnectedTechIndex = -1;
        mConnectedHandle = -1;
        mClassifyT2T = true;
    }

    @Override
    public boolean disconnect() {
        boolean result = false;
        result = doTerminatePresenceCheckThread(false);
        if (!result) {
            result = doDisconnect();
        }
        clearConnectedContext();
        return result;
    }

    native int doGetSrdState();
    public int getSrdState() {
      return doGetSrdState();
    }

    native int doReconnect();

    public synchronized int reconnectWithStatus() {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        int status = doReconnect();
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return status;
    }

    @Override
    public synchronized boolean reconnect() {
        return reconnectWithStatus() == 0;
    }

    native int doHandleReconnect(int handle);

    public synchronized int reconnectWithStatus(int handle) {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        int status = doHandleReconnect(handle);
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return status;
    }

    private native byte[] doTransceive(byte[] data, boolean raw, int[] returnCode);

    @Override
    public synchronized byte[] transceive(byte[] data, boolean raw, int[] returnCode) {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        byte[] result = doTransceive(data, raw, returnCode);
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    private native int doCheckNdef(int[] ndefinfo);

    private synchronized int checkNdefWithStatus(int[] ndefinfo) {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        int status = doCheckNdef(ndefinfo);
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return status;
    }

    @Override
    public synchronized boolean checkNdef(int[] ndefinfo) {
        boolean status = false;
        if (hasTech(TagTechnology.NDEF)) {
            status = true;
        } else {
            status = checkNdefWithStatus(ndefinfo) == 0;
        }
        return status;
    }

    private native byte[] doRead();

    @Override
    public synchronized byte[] readNdef() {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        byte[] result = doRead();
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    private native boolean doWrite(byte[] buf);

    @Override
    public synchronized boolean writeNdef(byte[] buf) {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = doWrite(buf);
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native boolean doPresenceCheck();

    @Override
    public synchronized boolean presenceCheck() {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = doPresenceCheck();
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native boolean doNdefFormat(byte[] key);

    @Override
    public synchronized boolean formatNdef(byte[] key) {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = doNdefFormat(key);
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native boolean doMakeReadonly(byte[] key);

    @Override
    public synchronized boolean makeReadOnly() {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result;
        if (hasTech(TagTechnology.MIFARE_CLASSIC)) {
            result = doMakeReadonly(MifareClassic.KEY_DEFAULT);
        } else {
            // No key needed for other technologies
            result = doMakeReadonly(new byte[] {});
        }
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native boolean doIsIsoDepNdefFormatable(byte[] poll, byte[] act);

    @Override
    public synchronized boolean isNdefFormatable() {
        // Let native code decide whether the currently activated tag
        // is formatable.  Although the name of the JNI function refers
        // to ISO-DEP, the JNI function checks all tag types.
        return doIsIsoDepNdefFormatable(mTechPollBytes[0], mTechActBytes[0]);
    }

    @Override
    public int getHandle() {
        // This is just a handle for the clients; it can simply use the first
        // technology handle we have.
        if (mTechHandles.length > 0) {
            return mTechHandles[0];
        } else {
            return 0;
        }
    }

    @Override
    public byte[] getUid() {
        return mUid;
    }

    @Override
    public int[] getTechList() {
        return mTechList;
    }

    private int getConnectedHandle() {
        return mConnectedHandle;
    }

    private int getConnectedLibNfcType() {
        if (mConnectedTechIndex != -1 && mConnectedTechIndex < mTechLibNfcTypes.length) {
            return mTechLibNfcTypes[mConnectedTechIndex];
        } else {
            return 0;
        }
    }

    @Override
    public int getConnectedTechnology() {
        if (mConnectedTechIndex != -1 && mConnectedTechIndex < mTechList.length) {
            return mTechList[mConnectedTechIndex];
        } else {
            return 0;
        }
    }

    native int doGetNdefType(int libnfctype, int javatype);

    private int getNdefType(int libnfctype, int javatype) {
        return doGetNdefType(libnfctype, javatype);
    }

    private void addTechnology(int tech, int handle, int libnfctype) {
        int[] mNewTechList = new int[mTechList.length + 1];
        System.arraycopy(mTechList, 0, mNewTechList, 0, mTechList.length);
        mNewTechList[mTechList.length] = tech;
        mTechList = mNewTechList;

        int[] mNewHandleList = new int[mTechHandles.length + 1];
        System.arraycopy(mTechHandles, 0, mNewHandleList, 0, mTechHandles.length);
        mNewHandleList[mTechHandles.length] = handle;
        mTechHandles = mNewHandleList;

        int[] mNewTypeList = new int[mTechLibNfcTypes.length + 1];
        System.arraycopy(mTechLibNfcTypes, 0, mNewTypeList, 0, mTechLibNfcTypes.length);
        mNewTypeList[mTechLibNfcTypes.length] = libnfctype;
        mTechLibNfcTypes = mNewTypeList;
    }

    @Override
    public void removeTechnology(int tech) {
        synchronized (this) {
            int techIndex = getTechIndex(tech);
            if (techIndex != -1) {
                int[] mNewTechList = new int[mTechList.length - 1];
                System.arraycopy(mTechList, 0, mNewTechList, 0, techIndex);
                System.arraycopy(
                        mTechList,
                        techIndex + 1,
                        mNewTechList,
                        techIndex,
                        mTechList.length - techIndex - 1);
                mTechList = mNewTechList;

                int[] mNewHandleList = new int[mTechHandles.length - 1];
                System.arraycopy(mTechHandles, 0, mNewHandleList, 0, techIndex);
                System.arraycopy(
                        mTechHandles,
                        techIndex + 1,
                        mNewTechList,
                        techIndex,
                        mTechHandles.length - techIndex - 1);
                mTechHandles = mNewHandleList;

                int[] mNewTypeList = new int[mTechLibNfcTypes.length - 1];
                System.arraycopy(mTechLibNfcTypes, 0, mNewTypeList, 0, techIndex);
                System.arraycopy(
                        mTechLibNfcTypes,
                        techIndex + 1,
                        mNewTypeList,
                        techIndex,
                        mTechLibNfcTypes.length - techIndex - 1);
                mTechLibNfcTypes = mNewTypeList;

                // The technology must be removed from the mTechExtras array,
                // just like the above arrays.
                // Remove the specified element from the array,
                // then shift the remaining elements by one.
                if (mTechExtras != null) {
                    Bundle[] mNewTechExtras = new Bundle[mTechExtras.length - 1];
                    System.arraycopy(mTechExtras, 0, mNewTechExtras, 0, techIndex);
                    System.arraycopy(
                            mTechExtras,
                            techIndex + 1,
                            mNewTechExtras,
                            techIndex,
                            mTechExtras.length - techIndex - 1);
                    mTechExtras = mNewTechExtras;
                }
            }
        }
    }

    public void addNdefFormatableTechnology(int handle, int libnfcType) {
        synchronized (this) {
            addTechnology(TagTechnology.NDEF_FORMATABLE, handle, libnfcType);
        }
    }

    /**
     * This method exists to "patch in" the ndef technologies, which is done inside Java instead of
     * the native JNI code. To not create some nasty dependencies on the order on which things are
     * called (most notably getTechExtras()), it needs some additional checking.
     */
    public void addNdefTechnology(
            NdefMessage msg,
            int handle,
            int libnfcType,
            int javaType,
            int maxLength,
            int cardState) {
        synchronized (this) {
            addTechnology(TagTechnology.NDEF, handle, libnfcType);

            Bundle extras = new Bundle();
            extras.putParcelable(Ndef.EXTRA_NDEF_MSG, msg);
            extras.putInt(Ndef.EXTRA_NDEF_MAXLENGTH, maxLength);
            extras.putInt(Ndef.EXTRA_NDEF_CARDSTATE, cardState);
            extras.putInt(Ndef.EXTRA_NDEF_TYPE, getNdefType(libnfcType, javaType));

            if (mTechExtras == null) {
                // This will build the tech extra's for the first time,
                // including a NULL ref for the NDEF tech we generated above.
                Bundle[] builtTechExtras = getTechExtras();
                builtTechExtras[builtTechExtras.length - 1] = extras;
            } else {
                // Tech extras were built before, patch the NDEF one in
                Bundle[] oldTechExtras = getTechExtras();
                Bundle[] newTechExtras = new Bundle[oldTechExtras.length + 1];
                System.arraycopy(oldTechExtras, 0, newTechExtras, 0, oldTechExtras.length);
                newTechExtras[oldTechExtras.length] = extras;
                mTechExtras = newTechExtras;
            }
        }
    }

    private int getTechIndex(int tech) {
        int techIndex = -1;
        for (int i = 0; i < mTechList.length; i++) {
            if (mTechList[i] == tech) {
                techIndex = i;
                break;
            }
        }
        return techIndex;
    }

    private boolean hasTech(int tech) {
        boolean hasTech = false;
        for (int i = 0; i < mTechList.length; i++) {
            if (mTechList[i] == tech) {
                hasTech = true;
                break;
            }
        }
        return hasTech;
    }

    private boolean hasTechOnHandle(int tech, int handle) {
        boolean hasTech = false;
        for (int i = 0; i < mTechList.length; i++) {
            if (mTechList[i] == tech && mTechHandles[i] == handle) {
                hasTech = true;
                break;
            }
        }
        return hasTech;
    }

    private boolean isUltralightC() {
        /* Make a best-effort attempt at classifying ULTRALIGHT
         * vs ULTRALIGHT-C (based on NXP's public AN1303).
         * The memory layout is as follows:
         *   Page # BYTE1  BYTE2  BYTE3  BYTE4
         *   2      INT1   INT2   LOCK   LOCK
         *   3      OTP    OTP    OTP    OTP  (NDEF CC if NDEF-formatted)
         *   4      DATA   DATA   DATA   DATA (version info if factory-state)
         *
         * Read four blocks from page 2, which will get us both
         * the lock page, the OTP page and the version info.
         */
        int ENABLE = 0x01;
        if(getSrdState() == ENABLE) {
          /* Ignore to send read cmd incase of SRD is enabled.*/
          return false;
        }
        boolean isUltralightC = false;
        byte[] readCmd = {0x30, 0x02};
        int[] retCode = new int[2];
        byte[] respData = transceive(readCmd, false, retCode);
        if (respData != null && respData.length == 16) {
            // Check the lock bits (last 2 bytes in page2)
            // and the OTP bytes (entire page 3)
            if (respData[2] == 0
                    && respData[3] == 0
                    && respData[4] == 0
                    && respData[5] == 0
                    && respData[6] == 0
                    && respData[7] == 0) {
                // Very likely to be a blank card, look at version info
                // in page 4.
                if ((respData[8] == (byte) 0x02) && respData[9] == (byte) 0x00) {
                    // This is Ultralight-C
                    isUltralightC = true;
                } else {
                    // 0xFF 0xFF would indicate Ultralight, but we also use Ultralight
                    // as a fallback if it's anything else
                    isUltralightC = false;
                }
            } else {
                // See if we can find the NDEF CC in the OTP page and if it's
                // smaller than major version two
                if (respData[4] == (byte) 0xE1 && ((respData[5] & 0xff) < 0x20)) {
                    // OK, got NDEF. Technically we'd have to search for the
                    // NDEF TLV as well. However, this would add too much
                    // time for discovery and we can make already make a good guess
                    // with the data we have here. Byte 2 of the OTP page
                    // indicates the size of the tag - 0x06 is UL, anything
                    // above indicates UL-C.
                    if ((respData[6] & 0xff) > 0x06) {
                        isUltralightC = true;
                    }
                } else {
                    // Fall back to ultralight
                    isUltralightC = false;
                }
            }
        }
        return isUltralightC;
    }

    @Override
    public Bundle[] getTechExtras() {
        synchronized (this) {
            if (mTechExtras != null) return mTechExtras;
            mTechExtras = new Bundle[mTechList.length];
            for (int i = 0; i < mTechList.length; i++) {
                Bundle extras = new Bundle();
                switch (mTechList[i]) {
                    case TagTechnology.NFC_A:
                        if ((mTechActBytes[i] != null) && (mTechActBytes[i].length > 0)) {
                            extras.putShort(NfcA.EXTRA_SAK,
                                    (short) (mTechActBytes[i][0] & (short) 0xFF));
                        } else {
                            // Unfortunately Jewel doesn't have act bytes,
                            // ignore this case.
                        }
                        extras.putByteArray(NfcA.EXTRA_ATQA, mTechPollBytes[i]);
                        break;

                    case TagTechnology.NFC_B:

                        // What's returned from the PN544 is actually:
                        // 4 bytes app data
                        // 3 bytes prot info
                        byte[] appData = new byte[4];
                        byte[] protInfo = new byte[3];
                        if (mTechPollBytes[i].length >= 7) {
                            System.arraycopy(mTechPollBytes[i], 0, appData, 0, 4);
                            System.arraycopy(mTechPollBytes[i], 4, protInfo, 0, 3);

                            extras.putByteArray(NfcB.EXTRA_APPDATA, appData);
                            extras.putByteArray(NfcB.EXTRA_PROTINFO, protInfo);
                        }
                        break;

                    case TagTechnology.NFC_F:
                        byte[] pmm = new byte[8];
                        byte[] sc = new byte[2];
                        if (mTechPollBytes[i].length >= 8) {
                            // At least pmm is present
                            System.arraycopy(mTechPollBytes[i], 0, pmm, 0, 8);
                            extras.putByteArray(NfcF.EXTRA_PMM, pmm);
                        }
                        if (mTechPollBytes[i].length == 10) {
                            System.arraycopy(mTechPollBytes[i], 8, sc, 0, 2);
                            extras.putByteArray(NfcF.EXTRA_SC, sc);
                        }
                        break;

                    case TagTechnology.ISO_DEP:
                        if (hasTech(TagTechnology.NFC_A)) {
                            extras.putByteArray(IsoDep.EXTRA_HIST_BYTES, mTechActBytes[i]);
                        } else if (hasTech(TagTechnology.NFC_Q)) {
                            extras.putByteArray(IsoDep.EXTRA_HIST_BYTES, mTechActBytes[i]);
                        } else {
                            extras.putByteArray(IsoDep.EXTRA_HI_LAYER_RESP, mTechActBytes[i]);
                        }
                        break;

                    case TagTechnology.NFC_V:
                        // First byte response flags, second byte DSFID
                        if (mTechPollBytes[i] != null && mTechPollBytes[i].length >= 2) {
                            extras.putByte(NfcV.EXTRA_RESP_FLAGS, mTechPollBytes[i][0]);
                            extras.putByte(NfcV.EXTRA_DSFID, mTechPollBytes[i][1]);
                        }
                        break;

                    case TagTechnology.MIFARE_ULTRALIGHT:
                        if (mClassifyT2T) {
                            boolean isUlc = isUltralightC();
                            extras.putBoolean(MifareUltralight.EXTRA_IS_UL_C, isUlc);
                        }
                        break;

                    case TagTechnology.MIFARE_CLASSIC:
                        if ((mTechActBytes[i] != null) && (mTechActBytes[i].length > 0)) {
                            extras.putShort(NfcA.EXTRA_SAK,
                                    (short) (mTechActBytes[i][0] & (short) 0xFF));
                        }
                        extras.putByteArray(NfcA.EXTRA_ATQA, mTechPollBytes[i]);
                        break;

                    case TagTechnology.NFC_BARCODE:
                        // hard code this for now, this is the only valid type
                        extras.putInt(NfcBarcode.EXTRA_BARCODE_TYPE, NfcBarcode.TYPE_KOVIO);
                        break;

                    default:

                        // Leave the entry in the array null
                        continue;
                }
                mTechExtras[i] = extras;
            }
            return mTechExtras;
        }
    }

    @Override
    public NdefMessage findAndReadNdef() {
        // Try to find NDEF on any of the technologies.
        int[] technologies = getTechList();
        int[] handles = mTechHandles;
        NdefMessage ndefMsg = null;
        boolean foundFormattable = false;
        int formattableHandle = 0;
        int formattableLibNfcType = 0;
        int status;

        for (int techIndex = 0; techIndex < technologies.length; techIndex++) {
            // have we seen this handle before?
            for (int i = 0; i < techIndex; i++) {
                if (handles[i] == handles[techIndex]) {
                    continue; // don't check duplicate handles
                }
            }

            status = connectWithStatus(technologies[techIndex]);
            if (status != 0) {
                Log.d(TAG, "Connect Failed - status = " + status);
                if (status == STATUS_CODE_TARGET_LOST) {
                    break;
                }
                continue; // try next handle
            }
            // Check if this type is NDEF formatable
            if (!foundFormattable) {
                if (isNdefFormatable()) {
                    foundFormattable = true;
                    formattableHandle = getConnectedHandle();
                    formattableLibNfcType = getConnectedLibNfcType();
                    // We'll only add formattable tech if no ndef is
                    // found - this is because libNFC refuses to format
                    // an already NDEF formatted tag.
                }
                reconnect();
            }

            int[] ndefinfo = new int[2];
            status = checkNdefWithStatus(ndefinfo);
            if (status != 0) {
                Log.d(TAG, "Check NDEF Failed - status = " + status);
                if (status == STATUS_CODE_TARGET_LOST) {
                    break;
                }
                continue; // try next handle
            }

            // found our NDEF handle
            boolean generateEmptyNdef = false;

            int supportedNdefLength = ndefinfo[0];
            int cardState = ndefinfo[1];
            byte[] buff = readNdef();
            if (buff != null && buff.length > 0) {
                try {
                    ndefMsg = new NdefMessage(buff);
                    addNdefTechnology(
                            ndefMsg,
                            getConnectedHandle(),
                            getConnectedLibNfcType(),
                            getConnectedTechnology(),
                            supportedNdefLength,
                            cardState);
                    reconnect();
                } catch (FormatException e) {
                    // Create an intent anyway, without NDEF messages
                    generateEmptyNdef = true;
                }
            } else if (buff != null) {
                // Empty buffer, unformatted tags fall into this case
                generateEmptyNdef = true;
            }

            if (generateEmptyNdef) {
                ndefMsg = null;
                addNdefTechnology(
                        null,
                        getConnectedHandle(),
                        getConnectedLibNfcType(),
                        getConnectedTechnology(),
                        supportedNdefLength,
                        cardState);
                foundFormattable = false;
                reconnect();
            }
            break;
        }

        if (ndefMsg == null && foundFormattable) {
            // Tag is not NDEF yet, and found a formattable target,
            // so add formattable tech to tech list.
            addNdefFormatableTechnology(formattableHandle, formattableLibNfcType);
        }

        return ndefMsg;
    }

    @Override
    public void findNdef() {
        int[] technologies = getTechList();
        int[] handles = mTechHandles;
        int currHandle = 0;
        mClassifyT2T = !hasTech(TagTechnology.MIFARE_ULTRALIGHT);

        for (int techIndex = 0; techIndex < technologies.length; techIndex++) {
            if (currHandle != handles[techIndex]) {
                currHandle = handles[techIndex];
                int status = connectWithStatus(technologies[techIndex]);
                if (status != 0) {
                    Log.d(TAG, "Connect Failed - status = " + status);
                    if (status == STATUS_CODE_TARGET_LOST) {
                        break;
                    }
                    continue; // try next handle
                }

                int[] ndefinfo = new int[2];
                status = checkNdefWithStatus(ndefinfo);
                if (status != 0) {
                    Log.d(TAG, "findNdef: Check NDEF Failed - status = "
                            + status);
                    if (status == STATUS_CODE_TARGET_LOST) {
                        break;
                    }
                    continue; // try next handle
                } else {
                    int supportedNdefLength = ndefinfo[0];
                    int cardState = ndefinfo[1];
                    addNdefTechnology(
                            null,
                            getConnectedHandle(),
                            getConnectedLibNfcType(),
                            getConnectedTechnology(),
                            supportedNdefLength,
                            cardState);
                    break;
                }
            } else {
                Log.d(TAG, "findNdef: Duplicate techIndex = " + techIndex);
            }
        }
    }
}
