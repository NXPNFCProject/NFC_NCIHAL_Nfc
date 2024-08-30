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
*  Copyright 2018-2021,2023 NXP
*
******************************************************************************/

package com.android.nfc.cardemulation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostNfcFService;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.nfc.cardemulation.Utils;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.sysprop.NfcProperties;
import android.os.UserHandle;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.nfc.NfcService;
import com.android.nfc.NfcStatsLog;
import com.android.nfc.cardemulation.util.StatsdUtils;
import com.android.nfc.flags.Flags;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import androidx.annotation.VisibleForTesting;

public class HostNfcFEmulationManager {
    static final String TAG = "HostNfcFEmulationManager";
    static final boolean DBG = NfcProperties.debug_enabled().orElse(false);

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

    private final StatsdUtils mStatsdUtils;

    // All variables below protected by mLock
    ComponentName mEnabledFgServiceName;
    int mEnabledFgServiceUserId;

    Messenger mService;
    boolean mServiceBound;
    ComponentName mServiceName;
    int mServiceUserId;

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
        mStatsdUtils =
                Flags.statsdCeEventsFlag() ? new StatsdUtils(StatsdUtils.SE_NAME_HCEF) : null;
    }

    /**
     * Enabled Foreground NfcF service changed
     */
    public void onEnabledForegroundNfcFServiceChanged(int userId, ComponentName service) {
        synchronized (mLock) {
            mEnabledFgServiceUserId = userId;
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
        NfcFServiceInfo resolvedService = null;
        synchronized (mLock) {
            if (nfcid2 != null) {
                resolvedService = mT3tIdentifiersCache.resolveNfcid2(nfcid2);
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
                if (mStatsdUtils != null) {
                    mStatsdUtils.logCardEmulationWrongSettingEvent();
                }
                return;
            }
            if (DBG) Log.d(TAG, "resolvedServiceName: " + resolvedServiceName.toString() +
                    "mState: " + String.valueOf(mState));
            switch (mState) {
                case STATE_IDLE:
                    int userId;
                    int uid = resolvedService != null ? resolvedService.getUid() : -1;
                    if (resolvedService == null) {
                        userId = mEnabledFgServiceUserId;
                    } else {
                        userId = UserHandle.getUserHandleForUid(uid)
                                .getIdentifier();
                    }
                    Messenger existingService =
                            bindServiceIfNeededLocked(userId, resolvedServiceName);
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

                    if (mStatsdUtils != null) {
                        mStatsdUtils.setCardEmulationEventUid(uid);
                        mStatsdUtils.notifyCardEmulationEventWaitingForResponse();
                    } else {
                        NfcStatsLog.write(NfcStatsLog.NFC_CARDEMULATION_OCCURRED,
                                NfcStatsLog.NFC_CARDEMULATION_OCCURRED__CATEGORY__HCE_PAYMENT,
                                "HCEF",
                                uid);
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
            if (mStatsdUtils != null) {
                mStatsdUtils.logCardEmulationDeactivatedEvent();
            }
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
        if (mActiveService == null) {
            return;
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

    Messenger bindServiceIfNeededLocked(int userId, ComponentName service) {
        if (DBG) Log.d(TAG, "bindServiceIfNeededLocked");
        if (mServiceBound && mServiceName.equals(service) && mServiceUserId == userId) {
            Log.d(TAG, "Service already bound.");
            return mService;
        } else {
            Log.d(TAG, "Binding to service " + service);
            if (mStatsdUtils != null) {
                mStatsdUtils.notifyCardEmulationEventWaitingForService();
            }
            unbindServiceIfNeededLocked();
            Intent bindIntent = new Intent(HostNfcFService.SERVICE_INTERFACE);
            bindIntent.setComponent(service);
            try {
                mServiceBound = mContext.bindServiceAsUser(bindIntent, mConnection,
                        Context.BIND_AUTO_CREATE, UserHandle.of(userId));
                if (!mServiceBound) {
                    Log.e(TAG, "Could not bind service.");
                } else {
                    mServiceUserId = userId;
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Could not bind service due to security exception.");
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
            mServiceUserId = -1;
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
                    if (mStatsdUtils != null) {
                        mStatsdUtils.notifyCardEmulationEventServiceBound();
                    }
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
                if (data == null) {
                    Log.e(TAG, "Data is null");
                    return;
                }
                if (data.length != 0 && (data.length != (data[0] & 0xff))) {
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
                    if (mStatsdUtils != null) {
                        mStatsdUtils.notifyCardEmulationEventResponseReceived();
                    }
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

    /**
     * Dump debugging information as a HostNfcFEmulationManagerProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/card_emulation.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    void dumpDebug(ProtoOutputStream proto) {
        if (mServiceBound) {
            Utils.dumpDebugComponentName(
                    mServiceName, proto, HostNfcFEmulationManagerProto.SERVICE_NAME);
        }
    }
    @VisibleForTesting
    public String getEnabledFgServiceName() {
        if (mEnabledFgServiceName != null) {
            return mEnabledFgServiceName.getPackageName();
        }
        return null;
    }

    @VisibleForTesting
    public boolean isUserSwitched() {
        if (mEnabledFgServiceName == null && mActiveService == null && mState == STATE_IDLE)
            return true;
        return false;
    }

    @VisibleForTesting
    public int getServiceUserId() {
        return mServiceUserId;
    }

    @VisibleForTesting
    public ServiceConnection getServiceConnection() {
        return mConnection;
    }

}
