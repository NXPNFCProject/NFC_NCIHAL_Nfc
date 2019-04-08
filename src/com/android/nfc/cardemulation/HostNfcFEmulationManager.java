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
*  Copyright 2018 NXP
*
******************************************************************************/

package com.android.nfc.cardemulation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.nfc.cardemulation.HostNfcFService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.nfc.NfcService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import android.os.SystemProperties;

public class HostNfcFEmulationManager {
    static final String TAG = "HostNfcFEmulationManager";
    static final boolean DBG = ((SystemProperties.get("persist.nfc.ce_debug").equals("1")) ? true : false);

    static final int STATE_IDLE = 0;
    static final int STATE_W4_SERVICE = 1;
    static final int STATE_XFER = 2;

    /** NFCID2 length */
    static final int NFCID2_LENGTH = 8;

    /** Minimum NFC-F packets including length, command code and NFCID2 */
    static final int MINIMUM_NFCF_PACKET_LENGTH = 10;

    final Context mContext;
    final RegisteredT3tIdentifiersCache mT3tIdentifiersCache;
    final Messenger mMessenger = new Messenger (new MessageHandler());
    final Object mLock;

    // All variables below protected by mLock
    ComponentName mEnabledFgServiceName;

    Messenger mService;
    boolean mServiceBound;
    ComponentName mServiceName;

    // mActiveService denotes the service interface
    // that is the current active one, until a new packet
    // comes in that may be resolved to a different service.
    // On deactivation, mActiveService stops being valid.
    Messenger mActiveService;
    ComponentName mActiveServiceName;

    int mState;
    byte[] mPendingPacket;

    public HostNfcFEmulationManager(Context context,
            RegisteredT3tIdentifiersCache t3tIdentifiersCache) {
        mContext = context;
        mLock = new Object();
        mEnabledFgServiceName = null;
        mT3tIdentifiersCache = t3tIdentifiersCache;
        mState = STATE_IDLE;
    }

    public void onEnabledForegroundNfcFServiceChanged(ComponentName service) {
        synchronized (mLock) {
            mEnabledFgServiceName = service;
            if (service == null) {
                sendDeactivateToActiveServiceLocked(HostNfcFService.DEACTIVATION_LINK_LOSS);
                unbindServiceIfNeededLocked();
            }
        }
    }

    public void onHostEmulationActivated() {
        if (DBG) Log.d(TAG, "notifyHostEmulationActivated");
    }

    public void onHostEmulationData(byte[] data) {
        if (DBG) Log.d(TAG, "notifyHostEmulationData");
        String nfcid2 = findNfcid2(data);
        ComponentName resolvedServiceName = null;
        synchronized (mLock) {
            if (nfcid2 != null) {
                NfcFServiceInfo resolvedService = mT3tIdentifiersCache.resolveNfcid2(nfcid2);
                if (resolvedService != null) {
                    resolvedServiceName = resolvedService.getComponent();
                }
            }
            if (resolvedServiceName == null) {
                if (mActiveServiceName == null) {
                    return;
                }
                resolvedServiceName = mActiveServiceName;
            }
            // Check if resolvedService is actually currently enabled
            if (mEnabledFgServiceName == null ||
                    !mEnabledFgServiceName.equals(resolvedServiceName)) {
                return;
            }
            if (DBG) Log.d(TAG, "resolvedServiceName: " + resolvedServiceName.toString() +
                    "mState: " + String.valueOf(mState));
            switch (mState) {
            case STATE_IDLE:
                Messenger existingService = bindServiceIfNeededLocked(resolvedServiceName);
                if (existingService != null) {
                    Log.d(TAG, "Binding to existing service");
                    mState = STATE_XFER;
                    sendDataToServiceLocked(existingService, data);
                } else {
                    // Waiting for service to be bound
                    Log.d(TAG, "Waiting for new service.");
                    // Queue packet to be used
                    mPendingPacket = data;
                    mState = STATE_W4_SERVICE;
                }
                break;
            case STATE_W4_SERVICE:
                Log.d(TAG, "Unexpected packet in STATE_W4_SERVICE");
                break;
            case STATE_XFER:
                // Regular packet data
                sendDataToServiceLocked(mActiveService, data);
                break;
            }
        }
    }

    public void onHostEmulationDeactivated() {
        if (DBG) Log.d(TAG, "notifyHostEmulationDeactivated");
        synchronized (mLock) {
            sendDeactivateToActiveServiceLocked(HostNfcFService.DEACTIVATION_LINK_LOSS);
            mActiveService = null;
            mActiveServiceName = null;
            unbindServiceIfNeededLocked();
            mState = STATE_IDLE;
        }
    }

    public void onNfcDisabled() {
        synchronized (mLock) {
            sendDeactivateToActiveServiceLocked(HostNfcFService.DEACTIVATION_LINK_LOSS);
            mEnabledFgServiceName = null;
            mActiveService = null;
            mActiveServiceName = null;
            unbindServiceIfNeededLocked();
            mState = STATE_IDLE;
        }
    }

    public void onUserSwitched() {
        synchronized (mLock) {
            sendDeactivateToActiveServiceLocked(HostNfcFService.DEACTIVATION_LINK_LOSS);
            mEnabledFgServiceName = null;
            mActiveService = null;
            mActiveServiceName = null;
            unbindServiceIfNeededLocked();
            mState = STATE_IDLE;
        }
    }

    void sendDataToServiceLocked(Messenger service, byte[] data) {
        if (DBG) Log.d(TAG, "sendDataToServiceLocked");
        if (DBG) {
            Log.d(TAG, "service: " +
                    (service != null ? service.toString() : "null"));
            Log.d(TAG, "mActiveService: " +
                    (mActiveService != null ? mActiveService.toString() : "null"));
        }
        if (service != mActiveService) {
            sendDeactivateToActiveServiceLocked(HostNfcFService.DEACTIVATION_LINK_LOSS);
            mActiveService = service;
            mActiveServiceName = mServiceName;
        }
        Message msg = Message.obtain(null, HostNfcFService.MSG_COMMAND_PACKET);
        Bundle dataBundle = new Bundle();
        dataBundle.putByteArray("data", data);
        msg.setData(dataBundle);
        msg.replyTo = mMessenger;
        try {
            Log.d(TAG, "Sending data to service");
            if (DBG) Log.d(TAG, "data: " + getByteDump(data));
            mActiveService.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote service has died, dropping packet");
        }
    }

    void sendDeactivateToActiveServiceLocked(int reason) {
        if (DBG) Log.d(TAG, "sendDeactivateToActiveServiceLocked");
        if (mActiveService == null) return;
        Message msg = Message.obtain(null, HostNfcFService.MSG_DEACTIVATED);
        msg.arg1 = reason;
        try {
            mActiveService.send(msg);
        } catch (RemoteException e) {
            // Don't care
        }
    }

    Messenger bindServiceIfNeededLocked(ComponentName service) {
        if (DBG) Log.d(TAG, "bindServiceIfNeededLocked");
        if (mServiceBound && mServiceName.equals(service)) {
            Log.d(TAG, "Service already bound.");
            return mService;
        } else {
            Log.d(TAG, "Binding to service " + service);
            unbindServiceIfNeededLocked();
            Intent bindIntent = new Intent(HostNfcFService.SERVICE_INTERFACE);
            bindIntent.setComponent(service);
            if (mContext.bindServiceAsUser(bindIntent, mConnection,
                    Context.BIND_AUTO_CREATE, UserHandle.CURRENT)) {
            } else {
                Log.e(TAG, "Could not bind service.");
            }
            return null;
        }
    }

    void unbindServiceIfNeededLocked() {
        if (DBG) Log.d(TAG, "unbindServiceIfNeededLocked");
        if (mServiceBound) {
            Log.d(TAG, "Unbinding from service " + mServiceName);
            mContext.unbindService(mConnection);
            mServiceBound = false;
            mService = null;
            mServiceName = null;
        }
    }

    String findNfcid2(byte[] data) {
        if (DBG) Log.d(TAG, "findNfcid2");
        if (data == null || data.length < MINIMUM_NFCF_PACKET_LENGTH) {
            if (DBG) Log.d(TAG, "Data size too small");
            return null;
        }
        int nfcid2Offset = 2;
        return bytesToString(data, nfcid2Offset, NFCID2_LENGTH);
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mService = new Messenger(service);
                mServiceBound = true;
                mServiceName = name;
                Log.d(TAG, "Service bound");
                mState = STATE_XFER;
                // Send pending packet
                if (mPendingPacket != null) {
                    sendDataToServiceLocked(mService, mPendingPacket);
                    mPendingPacket = null;
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                Log.d(TAG, "Service unbound");
                mService = null;
                mServiceBound = false;
                mServiceName = null;
            }
        }
    };

    class MessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            synchronized(mLock) {
                if (mActiveService == null) {
                    Log.d(TAG, "Dropping service response message; service no longer active.");
                    return;
                } else if (!msg.replyTo.getBinder().equals(mActiveService.getBinder())) {
                    Log.d(TAG, "Dropping service response message; service no longer bound.");
                    return;
                }
            }
            if (msg.what == HostNfcFService.MSG_RESPONSE_PACKET) {
                Bundle dataBundle = msg.getData();
                if (dataBundle == null) {
                    return;
                }
                byte[] data = dataBundle.getByteArray("data");
                /* this piece of code is commented to allow the application to send an empty
                   data packet */
                /*if (data == null) {
                    Log.e(TAG, "Data is null");
                    return;
                }
                if (data.length == 0) {
                    Log.e(TAG, "Invalid response packet");
                    return;
                }*/
                if (data != null && (data.length != (data[0] & 0xff))) {
                    Log.e(TAG, "Invalid response packet");
                    return;
                }
                int state;
                synchronized(mLock) {
                    state = mState;
                }
                if (state == STATE_XFER) {
                    Log.d(TAG, "Sending data");
                    if (DBG) Log.d(TAG, "data:" + getByteDump(data));
                    NfcService.getInstance().sendData(data);
                } else {
                    Log.d(TAG, "Dropping data, wrong state " + Integer.toString(state));
                }
            }
        }
    }

    static String bytesToString(byte[] bytes, int offset, int length) {
        final char[] hexChars = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] chars = new char[length * 2];
        int byteValue;
        for (int j = 0; j < length; j++) {
            byteValue = bytes[offset + j] & 0xFF;
            chars[j * 2] = hexChars[byteValue >>> 4];
            chars[j * 2 + 1] = hexChars[byteValue & 0x0F];
        }
        return new String(chars);
    }

    private String getByteDump(final byte[] cmd) {
        StringBuffer str = new StringBuffer("");
        int letters = 8;
        int i = 0;

        if (cmd == null) {
            str.append(" null\n");
            return str.toString();
        }

        for (; i < cmd.length; i++) {
            str.append(String.format(" %02X", cmd[i]));
            if ((i % letters == letters - 1) || (i + 1 == cmd.length)) {
                str.append("\n");
            }
        }

        return str.toString();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Bound HCE-F services: ");
        if (mServiceBound) {
            pw.println("    service: " + mServiceName);
        }
    }
}
