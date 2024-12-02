/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.annotation.TargetApi;
import android.annotation.FlaggedApi;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.nfc.cardemulation.PollingFrame;
import android.nfc.cardemulation.Utils;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.sysprop.NfcProperties;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.proto.ProtoOutputStream;

import androidx.annotation.VisibleForTesting;

import com.android.nfc.ForegroundUtils;
import com.android.nfc.NfcInjector;
import com.android.nfc.NfcService;
import com.android.nfc.NfcStatsLog;
import com.android.nfc.cardemulation.RegisteredAidCache.AidResolveInfo;
import com.android.nfc.cardemulation.util.StatsdUtils;
import com.android.nfc.flags.Flags;
import com.android.nfc.proto.NfcEventProto;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;


public class HostEmulationManager {
    static final String TAG = "HostEmulationManager";
    static final boolean DBG = NfcProperties.debug_enabled().orElse(true);

    static final int STATE_IDLE = 0;
    static final int STATE_W4_SELECT = 1;
    static final int STATE_W4_SERVICE = 2;
    static final int STATE_W4_DEACTIVATE = 3;
    static final int STATE_XFER = 4;
    static final int STATE_POLLING_LOOP = 5;

    /** Minimum AID length as per ISO7816 */
    static final int MINIMUM_AID_LENGTH = 5;

    /** Length of Select APDU header including length byte */
    static final int SELECT_APDU_HDR_LENGTH = 5;

    static final byte INSTR_SELECT = (byte)0xA4;

    static final String ANDROID_HCE_AID = "A000000476416E64726F6964484345";
    static final String NDEF_V1_AID = "D2760000850100";
    static final String NDEF_V2_AID = "D2760000850101";
    static final byte[] ANDROID_HCE_RESPONSE = {0x14, (byte)0x81, 0x00, 0x00, (byte)0x90, 0x00};

    static final byte[] AID_NOT_FOUND = {0x6A, (byte)0x82};
    static final byte[] UNKNOWN_ERROR = {0x6F, 0x00};

    static final int CE_HCE_PAYMENT =
            NfcStatsLog.NFC_CARDEMULATION_OCCURRED__CATEGORY__HCE_PAYMENT;
    static final int CE_HCE_OTHER =
            NfcStatsLog.NFC_CARDEMULATION_OCCURRED__CATEGORY__HCE_OTHER;
    static final String NFC_PACKAGE = "com.android.nfc";
    static final String DATA_KEY = "data";
    static final int FIELD_OFF_IDLE_DELAY_MS = 2000;
    static final int RE_ENABLE_OBSERVE_MODE_DELAY_MS = 2000;

    final Context mContext;
    final RegisteredAidCache mAidCache;
    final Messenger mMessenger = new Messenger (new MessageHandler());
    final KeyguardManager mKeyguard;
    final Object mLock;
    final PowerManager mPowerManager;
    private final Looper mLooper;

    private final StatsdUtils mStatsdUtils;

    // All variables below protected by mLock

    // Variables below are for a non-payment service,
    // that is typically only bound in the STATE_XFER state.
    Messenger mService;
    boolean mServiceBound = false;
    ComponentName mServiceName = null;
    int mServiceUserId; // The UserId of the non-payment service
    ArrayList<PollingFrame> mPendingPollingLoopFrames = null;
    ArrayList<PollingFrame> mUnprocessedPollingFrames = null;
    Map<ComponentName, ArrayList<PollingFrame>> mPollingFramesToSend = null;
    private Map<Integer, Map<String, List<ApduServiceInfo>>> mPollingLoopFilters;
    private Map<Integer, Map<Pattern, List<ApduServiceInfo>>> mPollingLoopPatternFilters;
    AutoDisableObserveModeRunnable mAutoDisableObserveModeRunnable = null;

    // Variables below are for a payment service,
    // which is typically bound persistently to improve on
    // latency.
    Messenger mPaymentService;
    boolean mPaymentServiceBound = false;

    boolean mEnableObserveModeAfterTransaction = false;
    boolean mEnableObserveModeOnFieldOff = false;
    ComponentName mPaymentServiceName = null;
    int mPaymentServiceUserId; // The userId of the payment service
    ComponentName mLastBoundPaymentServiceName;

    // mActiveService denotes the service interface
    // that is the current active one, until a new SELECT AID
    // comes in that may be resolved to a different service.
    // On deactivation, mActiveService stops being valid.
    Messenger mActiveService;
    ComponentName mActiveServiceName;
    int mActiveServiceUserId; // The UserId of the current active one

    String mLastSelectedAid;
    int mState;
    byte[] mSelectApdu;
    Handler mHandler;


    enum PollingLoopState {
        EVALUATING_POLLING_LOOP,
        FILTER_MATCHED,
        DELIVERING_TO_PREFERRED
    };

    PollingLoopState mPollingLoopState = PollingLoopState.EVALUATING_POLLING_LOOP;

    // Runnable to return to an IDLE_STATE and reset preferred service. This should be run after we
    // have left a field and gone a period of time without any HCE or polling frame data.
    Runnable mReturnToIdleStateRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                Log.d(TAG, "Have been outside field, returning to idle state");
                returnToIdleStateLocked();
            }
        }
    };

    // Runnable to re-enable observe mode after a transaction. This should be delayed after
    // HCE is deactivated to ensure we don't receive another select AID.
    Runnable mEnableObserveModeAfterTransactionRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
              Log.d(TAG, "re-enabling observe mode after transaction.");
              mEnableObserveModeAfterTransaction = false;
              mEnableObserveModeOnFieldOff = false;
            }
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
            if (adapter == null) {
                Log.e(TAG, "adapter is null, returning");
                return;
            }
            adapter.setObserveModeEnabled(true);
        }
    };

    public HostEmulationManager(Context context, Looper looper, RegisteredAidCache aidCache) {
        this(context, looper, aidCache, new StatsdUtils(StatsdUtils.SE_NAME_HCE));
    }

    @VisibleForTesting
    HostEmulationManager(Context context, Looper looper, RegisteredAidCache aidCache,
                         StatsdUtils statsdUtils) {
        mContext = context;
        mLooper = looper;
        mHandler = new Handler(looper);
        mLock = new Object();
        mAidCache = aidCache;
        mState = STATE_IDLE;
        mPollingLoopState = PollingLoopState.EVALUATING_POLLING_LOOP;
        mKeyguard = context.getSystemService(KeyguardManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mStatsdUtils = Flags.statsdCeEventsFlag() ? statsdUtils : null;
        mPollingLoopFilters = new HashMap<Integer, Map<String, List<ApduServiceInfo>>>();
        mPollingLoopPatternFilters = new HashMap<Integer, Map<Pattern, List<ApduServiceInfo>>>();
    }

    /**
     *  Preferred payment service changed
     */
    public void onPreferredPaymentServiceChanged(int userId, final ComponentName service) {
        mHandler.post(() -> {
            synchronized (mLock) {
                if (!isHostCardEmulationActivated()) {
                    Log.d(TAG, "onPreferredPaymentServiceChanged, resetting active service");
                    resetActiveService();
                }
                if (service != null) {
                    bindPaymentServiceLocked(userId, service);
                } else {
                    unbindPaymentServiceLocked();
                }
            }
        });
    }

    private Messenger getForegroundServiceOrDefault() {
        Pair<Messenger, ComponentName> pair = getForegroundServiceAndNameOrDefault();
        if (pair == null) {
            return null;
        }
        return pair.first;
    }

    private Pair<Messenger, ComponentName> getForegroundServiceAndNameOrDefault() {
        Pair<Integer, ComponentName> preferredService = mAidCache.getPreferredService();
        int preferredServiceUserId = preferredService.first != null ?
                preferredService.first : -1;
        ComponentName preferredServiceName = preferredService.second;

        if (preferredServiceName == null || preferredServiceUserId < 0) {
            return null;
        }

        return new Pair<>(bindServiceIfNeededLocked(preferredServiceUserId, preferredServiceName),
            preferredServiceName);
    }


    @TargetApi(35)
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_OBSERVE_MODE)
    public void updateForShouldDefaultToObserveMode(boolean enabled) {
        synchronized (mLock) {
            if (isHostCardEmulationActivated()) {
                mEnableObserveModeAfterTransaction = enabled;
                return;
            }
        }
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.setObserveModeEnabled(enabled);
    }


    @TargetApi(35)
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public void updatePollingLoopFilters(int userId, List<ApduServiceInfo> services) {
        HashMap<String, List<ApduServiceInfo>> pollingLoopFilters =
                new HashMap<String, List<ApduServiceInfo>>();
        HashMap<Pattern, List<ApduServiceInfo>> pollingLoopPatternFilters =
                new HashMap<Pattern, List<ApduServiceInfo>>();
        for (ApduServiceInfo serviceInfo : services) {
            for (String plf : serviceInfo.getPollingLoopFilters()) {
                List<ApduServiceInfo> list =
                        pollingLoopFilters.getOrDefault(plf, new ArrayList<ApduServiceInfo>());
                list.add(serviceInfo);
                pollingLoopFilters.putIfAbsent(plf, list);

            }
            for (Pattern plpf : serviceInfo.getPollingLoopPatternFilters()) {
                List<ApduServiceInfo> list =
                        pollingLoopPatternFilters.getOrDefault(plpf,
                        new ArrayList<ApduServiceInfo>());
                list.add(serviceInfo);
                pollingLoopPatternFilters.putIfAbsent(plpf, list);

            }
        }
        mPollingLoopFilters.put(Integer.valueOf(userId), pollingLoopFilters);
        mPollingLoopPatternFilters.put(Integer.valueOf(userId), pollingLoopPatternFilters);
    }

    public void onObserveModeStateChange(boolean enabled) {
        synchronized(mLock) {
            if (android.nfc.Flags.nfcEventListener()) {
                Messenger service = getForegroundServiceOrDefault();
                if (service != null) {
                    Message msg = Message.obtain(null, HostApduService.MSG_OBSERVE_MODE_CHANGE);
                    msg.arg1 = enabled ? 1 : 0;
                    msg.replyTo = mMessenger;
                    try {
                        service.send(msg);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Remote service has died", e);
                    }
                }
            }
            if (!enabled && mAutoDisableObserveModeRunnable != null) {
                mHandler.removeCallbacks(mAutoDisableObserveModeRunnable);
                mAutoDisableObserveModeRunnable = null;
            }
        }
    }

    class AutoDisableObserveModeRunnable implements Runnable {
        Set<String> mServicePackageNames;
        AutoDisableObserveModeRunnable(ComponentName componentName) {
            mServicePackageNames = new ArraySet<>(1);
            addServiceToList(componentName);
        }

        @Override
        public void run() {
            synchronized(mLock) {
                NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
                if (!adapter.isObserveModeEnabled()) {
                    return;
                }
                if (arePackagesInForeground()) {
                    return;
                }
                Log.w(TAG, "Observe mode not disabled and no application from the following " +
                    "packages are in the foreground: " + String.join(", ", mServicePackageNames));
                allowOneTransaction();
            }
        }


        void addServiceToList(ComponentName service) {
            mServicePackageNames.add(service.getPackageName());
        }

        boolean arePackagesInForeground() {
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am == null) {
                return false;
            }
            ForegroundUtils foregroundUtils = ForegroundUtils.getInstance(am);
            if (foregroundUtils == null) {
                return false;
            }
            PackageManager packageManager = mContext.getPackageManager();
            if (packageManager == null) {
                return false;
            }
            for (Integer uid : foregroundUtils.getForegroundUids()) {
                for (String packageName :  packageManager.getPackagesForUid(uid)) {
                    if (packageName != null) {
                        for (String servicePackageName : mServicePackageNames) {
                            if (Objects.equals(servicePackageName, packageName)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }
    }

    private void sendFrameToServiceLocked(Messenger service, ComponentName name,
        PollingFrame frame) {
        sendFramesToServiceLocked(service, name, Arrays.asList(frame));
    }

    private void sendFramesToServiceLocked(Messenger service, ComponentName name,
            List<PollingFrame> frames) {
        if (service != null) {
            sendPollingFramesToServiceLocked(service, new ArrayList<>(frames));
        } else {
            mUnprocessedPollingFrames = new ArrayList<PollingFrame>();
            if (mPollingFramesToSend == null) {
                mPollingFramesToSend = new HashMap<ComponentName, ArrayList<PollingFrame>>();
            }
            if (mPollingFramesToSend.containsKey(name)) {
                mPollingFramesToSend.get(name).addAll(frames);
            } else {
                mPollingFramesToSend.put(name, new ArrayList<>(frames));
            }
        }
        if (Flags.autoDisableObserveMode()) {
            if (mAutoDisableObserveModeRunnable == null) {
                mAutoDisableObserveModeRunnable = new AutoDisableObserveModeRunnable(name);
                mHandler.postDelayed(mAutoDisableObserveModeRunnable, 3000);
            } else {
                mAutoDisableObserveModeRunnable.addServiceToList(name);
            }
        }
    }

    @TargetApi(35)
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public void onPollingLoopDetected(List<PollingFrame> pollingFrames) {
        Log.d(TAG, "onPollingLoopDetected, size: " + pollingFrames.size());
        synchronized (mLock) {
            mHandler.removeCallbacks(mReturnToIdleStateRunnable);
            // We need to have this check here in addition to the one in onFieldChangeDetected,
            // because we can receive an OFF frame after the field change is detected.
            if (!pollingFrames.isEmpty()
                    && pollingFrames.getLast().getType() == PollingFrame.POLLING_LOOP_TYPE_OFF) {
                mHandler.postDelayed(mReturnToIdleStateRunnable, FIELD_OFF_IDLE_DELAY_MS);
            }

            if (mState == STATE_IDLE) {
                mState = STATE_POLLING_LOOP;
            }
            int onCount = 0;
            int offCount = 0;
            int aCount = 0;
            int bCount = 0;
            if (mPendingPollingLoopFrames == null) {
                mPendingPollingLoopFrames = new ArrayList<PollingFrame>(1);
            }
            for (PollingFrame pollingFrame : pollingFrames) {
                if (mUnprocessedPollingFrames != null) {
                    mUnprocessedPollingFrames.add(pollingFrame);
                } else if (pollingFrame.getType()
                        == PollingFrame.POLLING_LOOP_TYPE_F) {
                    Pair<Messenger, ComponentName> serviceAndName =
                        getForegroundServiceAndNameOrDefault();
                    if (serviceAndName != null) {
                        sendFrameToServiceLocked(serviceAndName.first, serviceAndName.second,
                            pollingFrame);
                    }
                } else if (pollingFrame.getType()
                        == PollingFrame.POLLING_LOOP_TYPE_UNKNOWN) {
                    byte[] data = pollingFrame.getData();
                    String dataStr = HexFormat.of().formatHex(data).toUpperCase(Locale.ROOT);
                    List<ApduServiceInfo> serviceInfos =
                            mPollingLoopFilters.get(ActivityManager.getCurrentUser()).get(dataStr);
                    Map<Pattern, List<ApduServiceInfo>> patternMappingForUser =
                            mPollingLoopPatternFilters.get(ActivityManager.getCurrentUser());
                    Set<Pattern> patternSet = patternMappingForUser.keySet();
                    List<Pattern> matchedPatterns = patternSet.stream()
                            .filter(p -> p.matcher(dataStr).matches()).toList();
                    if (!matchedPatterns.isEmpty()) {
                        if (serviceInfos == null) {
                            serviceInfos = new ArrayList<ApduServiceInfo>();
                        }
                        for (Pattern matchedPattern : matchedPatterns) {
                            serviceInfos.addAll(patternMappingForUser.get(matchedPattern));
                        }
                    }
                    if (serviceInfos != null && serviceInfos.size() > 0) {
                        ApduServiceInfo serviceInfo;
                        if (serviceInfos.size() == 1) {
                            serviceInfo = serviceInfos.get(0);
                        } else {
                            serviceInfo = mAidCache.resolvePollingLoopFilterConflict(serviceInfos);
                            if (serviceInfo == null) {
                                /*  If neither the foreground or payments service can handle the plf,
                                *  pick the first in the list. */
                                serviceInfo = serviceInfos.get(0);
                            }
                        }
                        if (serviceInfo.getShouldAutoTransact(dataStr)) {
                            allowOneTransaction();
                            pollingFrame.setTriggeredAutoTransact(true);
                        }
                        UserHandle user = UserHandle.getUserHandleForUid(serviceInfo.getUid());
                        if (serviceInfo.isOnHost()) {
                            Messenger service = bindServiceIfNeededLocked(user.getIdentifier(),
                                    serviceInfo.getComponent());
                            mPollingLoopState = PollingLoopState.FILTER_MATCHED;
                            sendFrameToServiceLocked(service, serviceInfo.getComponent(),
                                pollingFrame);
                        }
                    } else {
                        Pair<Messenger, ComponentName> serviceAndName =
                                getForegroundServiceAndNameOrDefault();
                        if (serviceAndName != null) {
                            sendFrameToServiceLocked(serviceAndName.first, serviceAndName.second,
                                pollingFrame);
                        }
                    }

                    if (mStatsdUtils != null) {
                        mStatsdUtils.tallyPollingFrame(dataStr, pollingFrame);
                    }
                } else {
                    mPendingPollingLoopFrames.add(pollingFrame);
                }
                if (mStatsdUtils != null) {
                    mStatsdUtils.logPollingFrames();
                }
            }

            if (mPollingLoopState == PollingLoopState.EVALUATING_POLLING_LOOP) {
                if (mPendingPollingLoopFrames.size() >= 3) {
                    for (PollingFrame frame : mPendingPollingLoopFrames) {
                        int type = frame.getType();
                        switch (type) {
                            case PollingFrame.POLLING_LOOP_TYPE_A:
                                aCount++;
                                if (aCount > 3) {
                                    mPollingLoopState = PollingLoopState.DELIVERING_TO_PREFERRED;
                                }
                                break;
                            case PollingFrame.POLLING_LOOP_TYPE_B:
                                bCount++;
                                if (bCount > 3) {
                                    mPollingLoopState = PollingLoopState.DELIVERING_TO_PREFERRED;
                                }
                                break;
                            case PollingFrame.POLLING_LOOP_TYPE_ON:
                                onCount++;
                                break;
                            case PollingFrame.POLLING_LOOP_TYPE_OFF:
                                // Send the loop data if we've seen at least one on before an off.
                                offCount++;
                                if (onCount >= 2 && offCount >=2) {
                                    mPollingLoopState = PollingLoopState.DELIVERING_TO_PREFERRED;
                                }
                                break;
                            default:
                        }
                        if (mPollingLoopState != PollingLoopState.EVALUATING_POLLING_LOOP) {
                            break;
                        }
                    }
                }
            }

            if (mPollingLoopState == PollingLoopState.DELIVERING_TO_PREFERRED) {
                Pair<Messenger, ComponentName> serviceAndName =
                        getForegroundServiceAndNameOrDefault();
                if (serviceAndName != null) {
                    sendFramesToServiceLocked(serviceAndName.first, serviceAndName.second,
                        mPendingPollingLoopFrames);
                    mPendingPollingLoopFrames = null;
                } else {
                    Log.i(TAG, "No preferred service to deliver polling frames to,"
                    + " allowing transaction.");
                    allowOneTransaction();
                }
            }
        }
    }

    private void allowOneTransaction() {
        Log.d(TAG, "disabling observe mode for one transaction.");
        mEnableObserveModeAfterTransaction = true;
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        mHandler.post(() -> adapter.setObserveModeEnabled(false));
    }

    /**
     *  Preferred foreground service changed
     */
    public void onPreferredForegroundServiceChanged(int userId, ComponentName service) {
        synchronized (mLock) {
            if (android.nfc.Flags.nfcEventListener()) {
                Pair<Integer, ComponentName> oldServiceAndUser = mAidCache.getPreferredService();
                Messenger oldPreferredService = null;
                if (oldServiceAndUser != null && oldServiceAndUser.second != null) {
                    if (mPaymentServiceName != null
                        && mPaymentServiceName.equals(oldServiceAndUser.second)
                        && mPaymentServiceUserId == oldServiceAndUser.first) {
                        oldPreferredService = mPaymentService;
                    } else if (mServiceName != null && mServiceName.equals(oldServiceAndUser.second)
                            && mServiceUserId == oldServiceAndUser.first) {
                        oldPreferredService = mService;
                    } else {
                        Log.w(TAG, oldServiceAndUser.second +
                            " is no longer the preferred NFC service but isn't bound");
                    }
                    if (oldPreferredService != null) {
                        Message msg =
                        Message.obtain(null, HostApduService.MSG_PREFERRED_SERVICE_CHANGED);
                        msg.arg1 = 0;
                        msg.replyTo = mMessenger;
                        try {
                            oldPreferredService.send(msg);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Remote service has died", e);
                        }
                    }
                } else {
                    Log.i(TAG, "old service is null");
                }
            }

            mAidCache.onPreferredForegroundServiceChanged(userId, service);

            if (!isHostCardEmulationActivated()) {
                Log.d(TAG, "onPreferredForegroundServiceChanged, resetting active service");
                resetActiveService();
            }
            if (service != null) {
                bindServiceIfNeededLocked(userId, service);
            } else {
                unbindServiceIfNeededLocked();
            }
         }
     }

    public void onFieldChangeDetected(boolean fieldOn) {
        mHandler.removeCallbacks(mReturnToIdleStateRunnable);
        if (!fieldOn) {
            mHandler.postDelayed(mReturnToIdleStateRunnable, FIELD_OFF_IDLE_DELAY_MS);
        }
        if (!fieldOn && mEnableObserveModeOnFieldOff && mEnableObserveModeAfterTransaction) {
            Log.d(TAG, "Field off detected, will re-enable observe mode.");
            mHandler.postDelayed(mEnableObserveModeAfterTransactionRunnable,
                RE_ENABLE_OBSERVE_MODE_DELAY_MS);
        }
    }

    public void onHostEmulationActivated() {
        Log.d(TAG, "notifyHostEmulationActivated");
        synchronized (mLock) {
            mHandler.removeCallbacks(mReturnToIdleStateRunnable);
            // Regardless of what happens, if we're having a tap again
            // activity up, close it
            Intent intent = new Intent(TapAgainDialog.ACTION_CLOSE);
            intent.setPackage(NFC_PACKAGE);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            if (mState != STATE_IDLE && mState != STATE_POLLING_LOOP) {
                Log.e(TAG, "Got activation event in non-idle state");
            }
            mState = STATE_W4_SELECT;
        }
    }

    static private class UnroutableAidBugReportRunnable implements Runnable {
        List<String> mUnroutedAids;

        UnroutableAidBugReportRunnable(String aid) {
            mUnroutedAids = new ArrayList<String>(1);
            mUnroutedAids.add(aid);
        }

        void addAid(String aid) {
            mUnroutedAids.add(aid);
        }
        @Override
        public void run() {
            NfcService.getInstance().mNfcDiagnostics.takeBugReport(
                    "NFC tap failed."
                        + " (If you weren't using NFC, "
                        + "no need to submit this report.)",
                    "Couldn't route " + String.join(", ", mUnroutedAids));
        }
    }

    UnroutableAidBugReportRunnable mUnroutableAidBugReportRunnable = null;

    public void onHostEmulationData(byte[] data) {
        Log.d(TAG, "notifyHostEmulationData");
        mHandler.removeCallbacks(mReturnToIdleStateRunnable);
        mHandler.removeCallbacks(mEnableObserveModeAfterTransactionRunnable);
        String selectAid = findSelectAid(data);
        ComponentName resolvedService = null;
        ApduServiceInfo resolvedServiceInfo = null;
        AidResolveInfo resolveInfo = null;
        synchronized (mLock) {
            if (mState == STATE_IDLE) {
                Log.e(TAG, "Got data in idle state.");
                return;
            } else if (mState == STATE_W4_DEACTIVATE) {
                Log.e(TAG, "Dropping APDU in STATE_W4_DECTIVATE");
                return;
            }
            if (selectAid != null) {
                if (selectAid.equals(ANDROID_HCE_AID)) {
                    NfcService.getInstance().sendData(ANDROID_HCE_RESPONSE);
                    return;
                }
                resolveInfo = mAidCache.resolveAid(selectAid);
                if (resolveInfo == null || resolveInfo.services.size() == 0) {
                    if (selectAid.equals(NDEF_V1_AID) || selectAid.equals(NDEF_V2_AID)) {
                        Log.w(TAG, "Can't route NDEF AID, sending AID_NOT_FOUND");
                    } else if (!mPowerManager.isScreenOn()) {
                      Log.i(TAG,
                          "Screen is off, sending AID_NOT_FOUND, but not triggering bug report");
                    } else {
                        Log.w(TAG, "Can't handle AID " + selectAid + " sending AID_NOT_FOUND");
                        if (mUnroutableAidBugReportRunnable != null) {
                            mUnroutableAidBugReportRunnable.addAid(selectAid);
                        } else {
                            mUnroutableAidBugReportRunnable =
                                    new UnroutableAidBugReportRunnable(selectAid);
                            /* Wait 1s to see if there is an alternate AID we can route before
                             * taking a bug report */
                            mHandler.postDelayed(mUnroutableAidBugReportRunnable, 1000);
                        }
                    }
                    NfcInjector.getInstance().getNfcEventLog().logEvent(
                            NfcEventProto.EventType.newBuilder()
                                    .setCeUnroutableAid(
                                        NfcEventProto.NfcCeUnroutableAid.newBuilder()
                                            .setAid(selectAid)
                                            .build())
                                    .build());
                    // Tell the remote we don't handle this AID
                    NfcService.getInstance().sendData(AID_NOT_FOUND);
                    return;
                } else if (mUnroutableAidBugReportRunnable != null) {
                    /* If there is a pending bug report runnable, cancel it. */
                    mHandler.removeCallbacks(mUnroutableAidBugReportRunnable);
                    mUnroutableAidBugReportRunnable = null;
                }
                mLastSelectedAid = selectAid;
                if (resolveInfo.defaultService != null) {
                    // Resolve to default
                    // Check if resolvedService requires unlock
                    ApduServiceInfo defaultServiceInfo = resolveInfo.defaultService;
                    if (mStatsdUtils != null) {
                        mStatsdUtils.setCardEmulationEventCategory(resolveInfo.category);
                        mStatsdUtils.setCardEmulationEventUid(defaultServiceInfo.getUid());
                    }

                    if ((defaultServiceInfo.requiresUnlock()
                            || NfcService.getInstance().isSecureNfcEnabled())
                          && mKeyguard.isKeyguardLocked()) {
                        NfcService.getInstance().sendRequireUnlockIntent();
                        NfcService.getInstance().sendData(AID_NOT_FOUND);
                        if (DBG) Log.d(TAG, "requiresUnlock()! show toast");
                        if (mStatsdUtils != null) {
                            mStatsdUtils.logCardEmulationWrongSettingEvent();
                        }
                        launchTapAgain(resolveInfo.defaultService, resolveInfo.category);
                        return;
                    }
                    if (defaultServiceInfo.requiresScreenOn() && !mPowerManager.isScreenOn()) {
                        NfcService.getInstance().sendData(AID_NOT_FOUND);
                        if (DBG) Log.d(TAG, "requiresScreenOn()!");
                        if (mStatsdUtils != null) {
                            mStatsdUtils.logCardEmulationWrongSettingEvent();
                        }
                        return;
                    }
                    // In no circumstance should this be an OffHostService -
                    // we should never get this AID on the host in the first place
                    if (!defaultServiceInfo.isOnHost()) {
                        Log.e(TAG, "AID that was meant to go off-host was routed to host." +
                                " Check routing table configuration.");
                        NfcService.getInstance().sendData(AID_NOT_FOUND);
                        if (mStatsdUtils != null) {
                            mStatsdUtils.logCardEmulationNoRoutingEvent();
                        }
                        return;
                    }
                    resolvedService = defaultServiceInfo.getComponent();
                    resolvedServiceInfo = defaultServiceInfo;
                } else if (mActiveServiceName != null) {
                    for (ApduServiceInfo serviceInfo : resolveInfo.services) {
                        if (mActiveServiceName.equals(serviceInfo.getComponent())) {
                            resolvedService = mActiveServiceName;
                            resolvedServiceInfo = serviceInfo;
                            break;
                        }
                    }
                }
                if (resolvedService == null) {
                    // We have no default, and either one or more services.
                    // Ask the user to confirm.
                    // Just ignore all future APDUs until we resolve to only one
                    mState = STATE_W4_DEACTIVATE;
                    NfcStatsLog.write(NfcStatsLog.NFC_AID_CONFLICT_OCCURRED, selectAid);
                    if (mStatsdUtils != null) {
                        mStatsdUtils.setCardEmulationEventCategory(CardEmulation.CATEGORY_OTHER);
                        mStatsdUtils.logCardEmulationWrongSettingEvent();
                    }
                    launchResolver((ArrayList<ApduServiceInfo>)resolveInfo.services, null,
                            resolveInfo.category);
                    return;
                }
            }
            switch (mState) {
                case STATE_W4_SELECT:
                    if (selectAid != null) {
                        int uid = resolvedServiceInfo.getUid();
                        if (mStatsdUtils != null) {
                            mStatsdUtils.setCardEmulationEventUid(uid);
                            mStatsdUtils.setCardEmulationEventCategory(resolveInfo.category);
                        }
                        UserHandle user =
                                UserHandle.getUserHandleForUid(uid);
                        Messenger existingService =
                                bindServiceIfNeededLocked(user.getIdentifier(), resolvedService);
                        if (existingService != null) {
                            Log.d(TAG, "Binding to existing service");
                            sendDataToServiceLocked(existingService, data);
                        } else {
                            // Waiting for service to be bound
                            Log.d(TAG, "Waiting for new service.");
                            // Queue SELECT APDU to be used
                            mSelectApdu = data;
                            mState = STATE_W4_SERVICE;
                        }
                        if (mStatsdUtils != null) {
                            mStatsdUtils.notifyCardEmulationEventWaitingForResponse();
                        } else {
                            int statsdCategory =
                                    resolveInfo.category.equals(CardEmulation.CATEGORY_PAYMENT)
                                            ? CE_HCE_PAYMENT
                                            : CE_HCE_OTHER;
                            NfcStatsLog.write(
                                    NfcStatsLog.NFC_CARDEMULATION_OCCURRED,
                                    statsdCategory,
                                    "HCE",
                                    uid);
                        }
                    } else {
                        Log.d(TAG, "Dropping non-select APDU in STATE_W4_SELECT");
                        NfcService.getInstance().sendData(UNKNOWN_ERROR);
                    }
                    break;
                case STATE_W4_SERVICE:
                    Log.d(TAG, "Unexpected APDU in STATE_W4_SERVICE");
                    break;
                case STATE_XFER:
                    if (selectAid != null) {
                        UserHandle user =
                                UserHandle.getUserHandleForUid(resolvedServiceInfo.getUid());
                        Messenger existingService =
                                bindServiceIfNeededLocked(user.getIdentifier(), resolvedService);
                        if (existingService != null) {
                            sendDataToServiceLocked(existingService, data);
                        } else {
                            // Waiting for service to be bound
                            mSelectApdu = data;
                            mState = STATE_W4_SERVICE;
                        }
                    } else if (mActiveService != null) {
                        // Regular APDU data
                        sendDataToServiceLocked(mActiveService, data);
                    } else {
                        // No SELECT AID and no active service.
                        Log.d(TAG, "Service no longer bound, dropping APDU");
                    }
                    break;
            }
        }
    }

    public void onHostEmulationDeactivated() {
        Log.d(TAG, "notifyHostEmulationDeactivated");
        synchronized (mLock) {
            if (mState == STATE_IDLE) {
                Log.e(TAG, "Got deactivation event while in idle state");
            }
            sendDeactivateToActiveServiceLocked(HostApduService.DEACTIVATION_LINK_LOSS);
            unbindServiceIfNeededLocked();
            returnToIdleStateLocked();

            if (mAutoDisableObserveModeRunnable != null) {
                mHandler.removeCallbacks(mAutoDisableObserveModeRunnable);
                mAutoDisableObserveModeRunnable = null;
            }

            if (mEnableObserveModeAfterTransaction) {
                Log.d(TAG, "HCE deactivated, will re-enable observe mode.");
                mHandler.postDelayed(mEnableObserveModeAfterTransactionRunnable,
                    RE_ENABLE_OBSERVE_MODE_DELAY_MS);
            }

            if (mStatsdUtils != null) {
                mStatsdUtils.logCardEmulationDeactivatedEvent();
            }
        }
    }

    public boolean isHostCardEmulationActivated() {
        synchronized (mLock) {
            return mState != STATE_IDLE && mState != STATE_POLLING_LOOP;
        }
    }

    public void onOffHostAidSelected() {
        Log.d(TAG, "notifyOffHostAidSelected");
        synchronized (mLock) {
            mHandler.removeCallbacks(mReturnToIdleStateRunnable);
            mHandler.removeCallbacks(mEnableObserveModeAfterTransactionRunnable);
            if (mState != STATE_XFER || mActiveService == null) {
                // Don't bother telling, we're not bound to any service yet
            } else {
                sendDeactivateToActiveServiceLocked(HostApduService.DEACTIVATION_DESELECTED);
            }
            if (mEnableObserveModeAfterTransaction) {
                Log.i(TAG, "OffHost AID selected, waiting for Field off to reenable observe mode");
                mEnableObserveModeOnFieldOff = true;
            }
            resetActiveService();
            unbindServiceIfNeededLocked();
            mState = STATE_W4_SELECT;

            //close the TapAgainDialog
            Intent intent = new Intent(TapAgainDialog.ACTION_CLOSE);
            intent.setPackage(NFC_PACKAGE);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    Messenger bindServiceIfNeededLocked(int userId, ComponentName service) {
        if (service == null) {
            Log.e(TAG, "service ComponentName is null");
            return null;
        }

        Pair<Integer, ComponentName> preferredPaymentService =
                mAidCache.getPreferredPaymentService();
        int preferredPaymentUserId = preferredPaymentService.first  != null ?
                preferredPaymentService.first : -1;
        ComponentName preferredPaymentServiceName = preferredPaymentService.second;

        if (mPaymentServiceName != null && mPaymentServiceName.equals(service)
                && mPaymentServiceUserId == userId) {
            Log.d(TAG, "Service already bound as payment service.");
            return mPaymentService;
        } else if (!mPaymentServiceBound && preferredPaymentServiceName != null
                && preferredPaymentServiceName.equals(service)
                && preferredPaymentUserId == userId) {
            Log.d(TAG, "Service should be bound as payment service but is not, binding now");
            bindPaymentServiceLocked(userId, preferredPaymentServiceName);
            return null;
        } else if (mServiceName != null && mServiceName.equals(service)
                && mServiceUserId == userId) {
            Log.d(TAG, "Service already bound as regular service.");
            return mService;
        } else {
            Log.d(TAG, "Binding to service " + service + " for userid:" + userId);
            if (mStatsdUtils != null) {
                mStatsdUtils.notifyCardEmulationEventWaitingForService();
            }
            unbindServiceIfNeededLocked();
            Intent aidIntent = new Intent(HostApduService.SERVICE_INTERFACE);
            aidIntent.setComponent(service);
            try {
                mServiceBound = mContext.bindServiceAsUser(aidIntent, mConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                        UserHandle.of(userId));
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

    void sendDataToServiceLocked(Messenger service, byte[] data) {
        mState = STATE_XFER;
        if (service != mActiveService) {
            sendDeactivateToActiveServiceLocked(HostApduService.DEACTIVATION_DESELECTED);
            mActiveService = service;
            if (service.equals(mPaymentService)) {
                mActiveServiceName = mPaymentServiceName;
                mActiveServiceUserId = mPaymentServiceUserId;
            } else {
                mActiveServiceName = mServiceName;
                mActiveServiceUserId = mServiceUserId;
            }
        }
        Message msg = Message.obtain(null, HostApduService.MSG_COMMAND_APDU);
        Bundle dataBundle = new Bundle();
        dataBundle.putByteArray(DATA_KEY, data);
        msg.setData(dataBundle);
        msg.replyTo = mMessenger;
        try {
            mActiveService.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote service " + mActiveServiceName + " has died, dropping APDU", e);
        }
    }

    void sendPollingFramesToServiceLocked(Messenger service,
            ArrayList<PollingFrame> pollingFrames) {
        if (!Objects.equals(service, mActiveService)) {
            sendDeactivateToActiveServiceLocked(HostApduService.DEACTIVATION_DESELECTED);
            mActiveService = service;
            if (service.equals(mPaymentService)) {
                mActiveServiceName = mPaymentServiceName;
                mActiveServiceUserId = mPaymentServiceUserId;
            } else {
                mActiveServiceName = mServiceName;
                mActiveServiceUserId = mServiceUserId;
            }
        }
        Message msg = Message.obtain(null, HostApduService.MSG_POLLING_LOOP);
        Bundle msgData = new Bundle();
        msgData.putParcelableArrayList(HostApduService.KEY_POLLING_LOOP_FRAMES_BUNDLE,
                pollingFrames);
        msg.setData(msgData);
        msg.replyTo = mMessenger;
        if (mState == STATE_IDLE) {
            mState = STATE_POLLING_LOOP;
        }
        try {
            mActiveService.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote service " + mActiveServiceName + " has died, dropping frames", e);
            allowOneTransaction();
        }
    }

    void sendDeactivateToActiveServiceLocked(int reason) {
        if (mActiveService == null) return;
        Message msg = Message.obtain(null, HostApduService.MSG_DEACTIVATED);
        msg.arg1 = reason;
        try {
            mActiveService.send(msg);
        } catch (RemoteException e) {
            // Don't care
        }
    }

    void unbindPaymentServiceLocked() {
        if (android.nfc.Flags.nfcEventListener() &&
            mPaymentService != null) {
            Message msg = Message.obtain(null, HostApduService.MSG_PREFERRED_SERVICE_CHANGED);
            msg.arg1 = 0;
            msg.replyTo = mMessenger;
            try {
                mPaymentService.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote service has died", e);
            }
        }
        Log.d(TAG, "Unbinding payment service");
        if (mPaymentServiceBound) {
            try {
                mContext.unbindService(mPaymentConnection);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unbind payment service: " + mPaymentServiceName, e);
            }
            mPaymentServiceBound = false;
        }

        mPaymentService = null;
        mPaymentServiceName = null;
        mPaymentServiceUserId = -1;
    }

    void bindPaymentServiceLocked(int userId, ComponentName service) {
        unbindPaymentServiceLocked();

        Log.d(TAG, "Binding to payment service " + service + " for userid:" + userId);
        Intent intent = new Intent(HostApduService.SERVICE_INTERFACE);
        intent.setComponent(service);
        try {
            if (mContext.bindServiceAsUser(intent, mPaymentConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                    UserHandle.of(userId))) {
                mPaymentServiceBound = true;
                mPaymentServiceUserId = userId;
                mLastBoundPaymentServiceName = service;
            } else {
                Log.e(TAG, "Could not bind (persistent) payment service.");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Could not bind service due to security exception.");
        }
    }

    void unbindServiceIfNeededLocked() {
        if (mServiceBound) {
            Log.d(TAG, "Unbinding from service " + mServiceName);
            try {
                mContext.unbindService(mConnection);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unbind service " + mServiceName, e);
            }
            mServiceBound = false;
        }

        mService = null;
        mServiceName = null;
        mServiceUserId = -1;
    }

    void launchTapAgain(ApduServiceInfo service, String category) {
        Intent dialogIntent = new Intent(mContext, TapAgainDialog.class);
        dialogIntent.putExtra(TapAgainDialog.EXTRA_CATEGORY, category);
        dialogIntent.putExtra(TapAgainDialog.EXTRA_APDU_SERVICE, service);
        dialogIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivityAsUser(dialogIntent,
                UserHandle.getUserHandleForUid(service.getUid()));
    }

    void launchResolver(ArrayList<ApduServiceInfo> services, ComponentName failedComponent,
            String category) {
        Intent intent = new Intent(mContext, AppChooserActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putParcelableArrayListExtra(AppChooserActivity.EXTRA_APDU_SERVICES, services);
        intent.putExtra(AppChooserActivity.EXTRA_CATEGORY, category);
        if (failedComponent != null) {
            intent.putExtra(AppChooserActivity.EXTRA_FAILED_COMPONENT, failedComponent);
        }
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    String findSelectAid(byte[] data) {
        if (data == null || data.length < SELECT_APDU_HDR_LENGTH + MINIMUM_AID_LENGTH) {
            if (DBG) Log.d(TAG, "Data size too small for SELECT APDU");
            return null;
        }
        // To accept a SELECT AID for dispatch, we require the following:
        // Class byte must be 0x00: logical channel set to zero, no secure messaging, no chaining
        // Instruction byte must be 0xA4: SELECT instruction
        // P1: must be 0x04: select by application identifier
        // P2: File control information is only relevant for higher-level application,
        //     and we only support "first or only occurrence".
        if (data[0] == 0x00 && data[1] == INSTR_SELECT && data[2] == 0x04) {
            if (data[3] != 0x00) {
                Log.d(TAG, "Selecting next, last or previous AID occurrence is not supported");
            }
            int aidLength = Byte.toUnsignedInt(data[4]);
            if (data.length < SELECT_APDU_HDR_LENGTH + aidLength) {
                return null;
            }
            return bytesToString(data, SELECT_APDU_HDR_LENGTH, aidLength);
        }
        return null;
    }

    private void returnToIdleStateLocked() {
        mPendingPollingLoopFrames = null;
        mPollingFramesToSend = null;
        mUnprocessedPollingFrames = null;
        resetActiveService();
        mPollingLoopState = PollingLoopState.EVALUATING_POLLING_LOOP;
        mState = STATE_IDLE;
    }

    private void resetActiveService() {
        mActiveService = null;
        mActiveServiceName = null;
        mActiveServiceUserId = -1;
    }

    private ServiceConnection mPaymentConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                /* Preferred Payment Service has been changed. */
                if (!mLastBoundPaymentServiceName.equals(name)) {
                    Log.i(TAG, "Ignoring bound payment service, " + name + " != "
                            + mLastBoundPaymentServiceName);
                    return;
                }
                mPaymentServiceName = name;
                mPaymentService = new Messenger(service);
                Log.i(TAG, "Payment service bound: " + name);
                if (android.nfc.Flags.nfcEventListener() &&
                    mPaymentService != null) {
                    Message msg =
                        Message.obtain(null, HostApduService.MSG_PREFERRED_SERVICE_CHANGED);
                    msg.arg1 = 1;
                    msg.replyTo = mMessenger;
                    try {
                        mPaymentService.send(msg);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Remote service has died", e);
                    }
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "Payment service disconnected: " + name);
            synchronized (mLock) {
                mPaymentService = null;
                mPaymentServiceName = null;
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.i(TAG, "Payment service died: " + name);
            synchronized (mLock) {
                if (mPaymentServiceUserId >= 0) {
                    bindPaymentServiceLocked(mPaymentServiceUserId, mLastBoundPaymentServiceName);
                }
            }
        }
    };

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                Pair<Integer, ComponentName> preferredUserAndService =
                    mAidCache.getPreferredService();
                ComponentName preferredServiceName =
                    preferredUserAndService == null ? null : preferredUserAndService.second;
                /* Service is already deactivated and not preferred, don't bind */
                if (mState == STATE_IDLE && !name.equals(preferredServiceName)) {
                  return;
                }
                mService = new Messenger(service);
                mServiceName = name;
                mServiceBound = true;
                Log.d(TAG, "Service bound: " + name);
                if (android.nfc.Flags.nfcEventListener() &&
                    name.equals(preferredServiceName) &&
                    mService != null) {
                    Message msg =
                        Message.obtain(null, HostApduService.MSG_PREFERRED_SERVICE_CHANGED);
                    msg.arg1 = 1;
                    msg.replyTo = mMessenger;
                    try {
                        mService.send(msg);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Remote service has died", e);
                    }
                }
                // Send pending select APDU
                if (mSelectApdu != null) {
                    if (mStatsdUtils != null) {
                        mStatsdUtils.notifyCardEmulationEventServiceBound();
                    }
                    sendDataToServiceLocked(mService, mSelectApdu);
                    mSelectApdu = null;
                } else if (mPollingFramesToSend != null && mPollingFramesToSend.containsKey(name)) {
                    sendPollingFramesToServiceLocked(mService, mPollingFramesToSend.get(name));
                    mPollingFramesToSend.remove(name);
                    if (android.nfc.Flags.nfcReadPollingLoop()
                        && mUnprocessedPollingFrames != null) {
                        ArrayList unprocessedPollingFrames = mUnprocessedPollingFrames;
                        mUnprocessedPollingFrames = null;
                        onPollingLoopDetected(unprocessedPollingFrames);
                    }
                } else {
                    Log.d(TAG, "bound with nothing to send");
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                Log.d(TAG, "Service unbound: " + name);
                mService = null;
                mServiceName = null;
                mServiceBound = false;
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
            if (msg.what == HostApduService.MSG_RESPONSE_APDU) {
                Bundle dataBundle = msg.getData();
                if (dataBundle == null) {
                    return;
                }
                byte[] data = dataBundle.getByteArray(DATA_KEY);
                if (data == null || data.length == 0) {
                    Log.e(TAG, "Dropping empty R-APDU");
                    return;
                }
                int state;
                synchronized(mLock) {
                    state = mState;
                }
                if (state == STATE_XFER) {
                    Log.d(TAG, "Sending data");
                    NfcService.getInstance().sendData(data);
                    if (mStatsdUtils != null) {
                        mStatsdUtils.notifyCardEmulationEventResponseReceived();
                    }
                } else {
                    Log.d(TAG, "Dropping data, wrong state " + Integer.toString(state));
                }
            } else if (msg.what == HostApduService.MSG_UNHANDLED) {
                synchronized (mLock) {
                    Log.d(TAG, "Received MSG_UNHANDLED");
                    AidResolveInfo resolveInfo = mAidCache.resolveAid(mLastSelectedAid);
                    boolean isPayment = false;
                    if (resolveInfo.services.size() > 0) {
                        NfcStatsLog.write(NfcStatsLog.NFC_AID_CONFLICT_OCCURRED, mLastSelectedAid);
                        launchResolver((ArrayList<ApduServiceInfo>)resolveInfo.services,
                                mActiveServiceName, resolveInfo.category);
                    }
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

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Bound HCE-A/HCE-B services: ");
        if (mPaymentServiceBound) {
            pw.println("    payment: " + mPaymentServiceName);
        }
        if (mServiceBound) {
            pw.println("    other: " + mServiceName);
        }
    }

    /**
     * Dump debugging information as a HostEmulationManagerProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/card_emulation.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    void dumpDebug(ProtoOutputStream proto) {
        if (mPaymentServiceBound) {
            Utils.dumpDebugComponentName(
                    mPaymentServiceName, proto, HostEmulationManagerProto.PAYMENT_SERVICE_NAME);
        }
        if (mServiceBound) {
            Utils.dumpDebugComponentName(
                    mServiceName, proto, HostEmulationManagerProto.SERVICE_NAME);
        }
    }

    @VisibleForTesting
    public int getState(){
        return mState;
    }

    @VisibleForTesting
    public ServiceConnection getServiceConnection(){
        return mConnection;
    }

    @VisibleForTesting
    public ServiceConnection getPaymentConnection(){
        return mPaymentConnection;
    }

    @VisibleForTesting
    public IBinder getMessenger(){
        if (mActiveService != null) {
            return mActiveService.getBinder();
        }
        return null;
    }

    @VisibleForTesting
    public Messenger getLocalMessenger() {
        return mMessenger;
    }

    @VisibleForTesting
    public ComponentName getServiceName(){
        return mLastBoundPaymentServiceName;
    }

    @VisibleForTesting
    public Boolean isServiceBounded(){
        return mServiceBound;
    }

    @VisibleForTesting
    public Map<Integer, Map<String, List<ApduServiceInfo>>> getPollingLoopFilters() {
        return mPollingLoopFilters;
    }

    @VisibleForTesting
    public Map<Integer, Map<Pattern, List<ApduServiceInfo>>> getPollingLoopPatternFilters() {
        return mPollingLoopPatternFilters;
    }
}
