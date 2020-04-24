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
*  Copyright 2018-2020 NXP
*
******************************************************************************/
package com.android.nfc;

import android.app.ActivityManager;
import android.app.Application;
import android.app.backup.BackupManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources.NotFoundException;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.nfc.BeamShareData;
import android.nfc.ErrorCodes;
import android.nfc.FormatException;
import android.nfc.IAppCallback;
import android.nfc.INfcAdapter;
import android.nfc.INfcAdapterExtras;
import android.nfc.INfcCardEmulation;
import android.nfc.INfcDta;
import android.nfc.INfcFCardEmulation;
import android.nfc.INfcTag;
import android.nfc.INfcUnlockHandler;
import android.nfc.ITagRemovedCallback;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TechListParcel;
import android.nfc.TransceiveResult;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.HwBinder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.ArrayUtils;
import com.android.nfc.DeviceHost.DeviceHostListener;
import com.android.nfc.DeviceHost.LlcpConnectionlessSocket;
import com.android.nfc.DeviceHost.LlcpServerSocket;
import com.android.nfc.DeviceHost.LlcpSocket;
import com.android.nfc.DeviceHost.NfcDepEndpoint;
import com.android.nfc.DeviceHost.TagEndpoint;
import com.android.nfc.cardemulation.CardEmulationManager;
import com.android.nfc.dhimpl.NativeNfcManager;
import com.android.nfc.handover.HandoverDataParser;
import com.android.nfc.dhimpl.NativeNfcSecureElement;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.TimerTask;
import java.util.Timer;
import java.io.IOException;
import android.widget.Toast;
import com.nxp.nfc.INxpNfcAdapter;
import com.nxp.nfc.INxpNfcAdapterExtras;
import java.util.HashSet;
import com.android.nfc.cardemulation.AidRoutingManager;
import com.android.nfc.cardemulation.RegisteredAidCache;
import com.nxp.nfc.NfcConstants;
import android.se.omapi.ISecureElementService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import com.nxp.nfc.NfcAidServiceInfo;
import android.util.StatsLog;

public class NfcService implements DeviceHostListener {
    static final boolean DBG = true;
    static final String TAG = "NfcService";

    public static final String SERVICE_NAME = "nfc";
    public static final String NXP_PREF = "NfcServiceNxpPrefs";
    public static final String PREF = "NfcServicePrefs";
    private static final String PREF_CUR_SELECTED_UICC_ID = "current_selected_uicc_id";
    private int SECURE_ELEMENT_UICC_SLOT_DEFAULT = 1;
    static final int UICC_CONFIGURED = 0x00;
    static final int UICC_NOT_CONFIGURED = 0x01;
    static final String PREF_NFC_ON = "nfc_on";
    static final boolean NFC_ON_DEFAULT = true;
    static final String PREF_NDEF_PUSH_ON = "ndef_push_on";
    static final boolean NDEF_PUSH_ON_DEFAULT = true;
    static final String PREF_SECURE_NFC_ON = "secure_nfc_on";
    static final boolean SECURE_NFC_ON_DEFAULT = false;
    static final String PREF_FIRST_BEAM = "first_beam";
    static final String PREF_FIRST_BOOT = "first_boot";
    public static final int ROUTE_LOC_MASK=8;
    public static final int TECH_TYPE_MASK=11;
    static final String TRON_NFC_CE = "nfc_ce";
    static final String TRON_NFC_P2P = "nfc_p2p";
    static final String TRON_NFC_TAG = "nfc_tag";
    static final String NATIVE_LOG_FILE_NAME = "native_logs";
    static final String T4T_NFCEE_AID = "D2760000850101";
    static final int TECH_TYPE_A= 0x01;
    static final int TECH_TYPE_F= 0x04;
    static final int MSG_NDEF_TAG = 0;
    static final int MSG_LLCP_LINK_ACTIVATION = 1;
    static final int MSG_LLCP_LINK_DEACTIVATED = 2;
    static final int MSG_MOCK_NDEF = 3;
    static final int MSG_LLCP_LINK_FIRST_PACKET = 4;
    static final int MSG_ROUTE_AID = 5;
    static final int MSG_UNROUTE_AID = 6;
    static final int MSG_COMMIT_ROUTING = 7;
    static final int MSG_INVOKE_BEAM = 8;
    static final int MSG_RF_FIELD_ACTIVATED = 9;
    static final int MSG_RF_FIELD_DEACTIVATED = 10;
    static final int MSG_RESUME_POLLING = 11;
    static final int MSG_REGISTER_T3T_IDENTIFIER = 12;
    static final int MSG_DEREGISTER_T3T_IDENTIFIER = 13;
    static final int MSG_TAG_DEBOUNCE = 14;
    static final int MSG_UPDATE_STATS = 15;
    static final int MSG_APPLY_SCREEN_STATE = 16;
    static final int MSG_TRANSACTION_EVENT = 17;
    static final int MSG_PREFERRED_PAYMENT_CHANGED = 18;
    static final int MSG_TOAST_DEBOUNCE_EVENT = 19;
    static final int MSG_CARD_EMULATION = 21;
    static final int MSG_SE_INIT = 59;
    static final int MSG_CLEAR_ROUTING = 62;
    static final int MSG_INIT_WIREDSE = 63;
    static final int MSG_COMPUTE_ROUTING_PARAMS = 64;
    static final int MSG_RESET_AND_UPDATE_ROUTING_PARAMS = 65;
    static final int MSG_DEINIT_WIREDSE = 66;
    static final int MSG_READ_T4TNFCEE = 67;
    static final int MSG_WRITE_T4TNFCEE = 68;

    // SCR/MPOS constants
    static final int SE_READER_TYPE_INAVLID   = 0;
    static final int SE_READER_TYPE_MPOS      = 1;
    static final int SE_READER_TYPE_MFC       = 2;
    static final int MSG_SCR_START_SUCCESS            = 70;
    static final int MSG_SCR_START_FAIL               = 71;
    static final int MSG_SCR_RESTART                  = 72;
    static final int MSG_SCR_ACTIVATED                = 73;
    static final int MSG_SCR_STOP_SUCCESS             = 74;
    static final int MSG_SCR_STOP_FAIL                = 75;
    static final int MSG_SCR_TIMEOUT                  = 76;
    static final int MSG_SCR_REMOVE_CARD              = 77;
    static final int MSG_SCR_MULTIPLE_TARGET_DETECTED = 78;
    private int SE_READER_TYPE = SE_READER_TYPE_INAVLID;

    // Update stats every 4 hours
    static final long STATS_UPDATE_INTERVAL_MS = 4 * 60 * 60 * 1000;
    static final long MAX_POLLING_PAUSE_TIMEOUT = 40000;

    static final int MAX_TOAST_DEBOUNCE_TIME = 10000;

    static final int TASK_ENABLE = 1;
    static final int TASK_DISABLE = 2;
    static final int TASK_BOOT = 3;

    // Listen Protocol
    public static final int NFC_LISTEN_PROTO_ISO_DEP = 0x01;    // This values is need to move from this to CardEmulationManager
    public static final int NFC_LISTEN_PROTO_NFC_DEP = 0x02;    // This values is need to move from this to CardEmulationManager
    public static final int NFC_LISTEN_PROTO_T3T = 0x04;
    public static final int NFC_LISTEN_PROTO_ISO7816 = 0x20;

    public static final int NFC_LISTEN_TECH_A = 0x01;   // This values is need to move from this to CardEmulationManager
    public static final int NFC_LISTEN_TECH_B = 0x02;   // This values is need to move from this to CardEmulationManager
    public static final int NFC_LISTEN_TECH_F = 0x04;   // This values is need to move from this to CardEmulationManager


    // Polling technology masks
    static final int NFC_POLL_A = 0x01;
    static final int NFC_POLL_B = 0x02;
    static final int NFC_POLL_F = 0x04;
    static final int NFC_POLL_V = 0x08;
    static final int NFC_POLL_B_PRIME = 0x10;
    static final int NFC_POLL_KOVIO = 0x20;

    // Return values from NfcEe.open() - these are 1:1 mapped
    // to the thrown EE_EXCEPTION_ exceptions in nfc-extras.
    static final int EE_ERROR_IO = -1;
    static final int EE_ERROR_ALREADY_OPEN = -2;
    static final int EE_ERROR_INIT = -3;
    static final int EE_ERROR_LISTEN_MODE = -4;
    static final int EE_ERROR_EXT_FIELD = -5;
    static final int EE_ERROR_NFC_DISABLED = -6;

    static final public int TECH_ENTRY = 1;
    static final public int PROTOCOL_ENTRY = 2;
    static final public int AID_ENTRY = 4;          // it is dummy values;

    // minimum screen state that enables NFC polling
    static final int NFC_POLLING_MODE = ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;

    // Time to wait for NFC controller to initialize before watchdog
    // goes off. This time is chosen large, because firmware download
    // may be a part of initialization.
    static final int INIT_WATCHDOG_MS = 90000;

    // Time to wait for routing to be applied before watchdog
    // goes off
    static final int ROUTING_WATCHDOG_MS = 10000;

    // Default delay used for presence checks
    static final int DEFAULT_PRESENCE_CHECK_DELAY = 125;

    // The amount of time we wait before manually launching
    // the Beam animation when called through the share menu.
    static final int INVOKE_BEAM_DELAY_MS = 1000;

    // RF field events as defined in NFC extras
    public static final String ACTION_RF_FIELD_ON_DETECTED =
            "com.android.nfc_extras.action.RF_FIELD_ON_DETECTED";
    public static final String ACTION_RF_FIELD_OFF_DETECTED =
            "com.android.nfc_extras.action.RF_FIELD_OFF_DETECTED";

    public static boolean sIsShortRecordLayout = false;
    // Default delay used for presence checks in ETSI mode
    static final int ETSI_PRESENCE_CHECK_DELAY = 1000;
    // for use with playSound()
    public static final int SOUND_START = 0;
    public static final int SOUND_END = 1;
    public static final int SOUND_ERROR = 2;

    public static final int NCI_VERSION_2_0 = 0x20;

    public static final int NCI_VERSION_1_0 = 0x10;

    public static final String ACTION_LLCP_UP =
            "com.android.nfc.action.LLCP_UP";

    public static final String ACTION_LLCP_DOWN =
            "com.android.nfc.action.LLCP_DOWN";
    //ETSI Reader Events
    public static final int ETSI_READER_START_SUCCESS   = 0;
    public static final int ETSI_READER_START_FAIL  = 1;
    public static final int ETSI_READER_ACTIVATED   = 2;
    public static final int ETSI_READER_STOP        = 3;

    //ETSI Reader Req States
    public static final int STATE_SE_RDR_MODE_INVALID = 0x00;
    public static final int STATE_SE_RDR_MODE_START_CONFIG = 0x01;
    public static final int STATE_SE_RDR_MODE_START_IN_PROGRESS = 0x02;
    public static final int STATE_SE_RDR_MODE_STARTED = 0x03;
    public static final int STATE_SE_RDR_MODE_ACTIVATED = 0x04;
    public static final int STATE_SE_RDR_MODE_STOP_CONFIG = 0x05;
    public static final int STATE_SE_RDR_MODE_STOP_IN_PROGRESS = 0x06;
    public static final int STATE_SE_RDR_MODE_STOPPED = 0x07;

    //Transit setconfig status
    public static final int TRANSIT_SETCONFIG_STAT_SUCCESS = 0x00;
    public static final int TRANSIT_SETCONFIG_STAT_FAILED  = 0xFF;

    // Timeout to re-apply routing if a tag was present and we postponed it
    private static final int APPLY_ROUTING_RETRY_TIMEOUT_MS = 5000;

    // these states are for making enable and disable nfc atomic
    private int NXP_NFC_STATE_OFF = 0;
    private int NXP_NFC_STATE_TURNING_ON = 1;
    private int NXP_NFC_STATE_ON = 2;
    private int NXP_NFC_STATE_TURNING_OFF = 3;

    // eSE handle
    public static final int EE_HANDLE_0xF3 = 0x4C0;

    /**
     * SMART MX ID to be able to select it as the default Secure Element
     */
    public static final int SMART_MX_ID_TYPE = 1;

    /**
     * UICC ID to be able to select it as the default Secure Element
     */
    public static final int UICC_ID_TYPE = 2;

    /**
     * UICC2 ID to be able to select it as the default Secure Element
     */
    public static final int UICC2_ID_TYPE = 4;

    public boolean mIsRouteForced;

    private final UserManager mUserManager;

    private static int nci_version = NCI_VERSION_1_0;
    // NFC Execution Environment
    // fields below are protected by this
    public NativeNfcSecureElement mSecureElement;
    public boolean isWiredOpen = false;
    private final ReaderModeDeathRecipient mReaderModeDeathRecipient =
            new ReaderModeDeathRecipient();
    private final NfcUnlockManager mNfcUnlockManager;


    private final BackupManager mBackupManager;
    // cached version of installed packages requesting Android.permission.NFC_TRANSACTION_EVENTS
    List<String> mNfcEventInstalledPackages = new ArrayList<String>();

    // cached version of installed packages requesting
    // Android.permission.NFC_PREFERRED_PAYMENT_INFO
    List<String> mNfcPreferredPaymentChangedInstalledPackages = new ArrayList<String>();

    // fields below are used in multiple threads and protected by synchronized(this)
    final HashMap<Integer, Object> mObjectMap = new HashMap<Integer, Object>();
    HashSet<String> mSePackages = new HashSet<String>();
    int mScreenState;
    boolean mInProvisionMode; // whether we're in setup wizard and enabled NFC provisioning
    boolean mIsNdefPushEnabled;
    boolean mIsSecureNfcEnabled;
    NfcDiscoveryParameters mCurrentDiscoveryParameters =
            NfcDiscoveryParameters.getNfcOffParameters();

    ReaderModeParams mReaderModeParams;

    private int mUserId;
    boolean mPollingPaused;

    static final int INVALID_NATIVE_HANDLE = -1;
    byte mDebounceTagUid[];
    int mDebounceTagDebounceMs;
    int mDebounceTagNativeHandle = INVALID_NATIVE_HANDLE;
    ITagRemovedCallback mDebounceTagRemovedCallback;

    // Only accessed on one thread so doesn't need locking
    NdefMessage mLastReadNdefMessage;

    // Metrics
    AtomicInteger mNumTagsDetected;
    AtomicInteger mNumP2pDetected;
    AtomicInteger mNumHceDetected;
    ToastHandler mToastHandler;
    // mState is protected by this, however it is only modified in onCreate()
    // and the default AsyncTask thread so it is read unprotected from that
    // thread
    int mState;  // one of NfcAdapter.STATE_ON, STATE_TURNING_ON, etc
    int mNxpNfcState = NXP_NFC_STATE_OFF;

    // fields below are final after onCreate()
    Context mContext;
    private DeviceHost mDeviceHost;
    private SharedPreferences mPrefs;
    private SharedPreferences.Editor mPrefsEditor;
    private PowerManager.WakeLock mRoutingWakeLock;
    private PowerManager.WakeLock mEeWakeLock;
    private SharedPreferences.Editor mNxpPrefsEditor;
    private SharedPreferences mNxpPrefs;
    int mStartSound;
    int mEndSound;
    int mErrorSound;
    SoundPool mSoundPool; // playback synchronized on this
    P2pLinkManager mP2pLinkManager;
    TagService mNfcTagService;
    boolean mIsSecureElementOpened = false;
    boolean mSEClientAccessState = false;
    NfcAdapterService mNfcAdapter;
    NfcDtaService mNfcDtaService;
    NxpNfcAdapterExtrasService mNxpExtrasService;
    NxpNfcAdapterService mNxpNfcAdapter;
    boolean mIsDebugBuild;
    boolean mIsHceCapable;
    boolean mIsHceFCapable;
    boolean mIsBeamCapable;
    boolean mIsSecureNfcCapable;

    private NfcDispatcher mNfcDispatcher;
    private PowerManager mPowerManager;
    private KeyguardManager mKeyguard;
    private HandoverDataParser mHandoverDataParser;
    private ContentResolver mContentResolver;
    private CardEmulationManager mCardEmulationManager;
    private AidRoutingManager mAidRoutingManager;
    private RegisteredAidCache mAidCache;
    private Vibrator mVibrator;
    private VibrationEffect mVibrationEffect;

    private ScreenStateHelper mScreenStateHelper;
    private ForegroundUtils mForegroundUtils;

    private static NfcService sService;
    private static boolean sToast_debounce = false;
    private static int sToast_debounce_time_ms = 3000;
    public  static boolean sIsDtaMode = false;

    private IVrManager vrManager;
    boolean mIsVrModeEnabled;
    private ISecureElementService mSEService;
    /* WiredSe attributes */
    Class mWiredSeClass;
    Method mWiredSeInitMethod, mWiredSeDeInitMethod;
    Object mWiredSeObj;
    Class mNfcExtnsClass;
    Object  mNfcExtnsObj;
    Class mNfcExtraClass;
    Object mNfcExtraObj;

    private int ROUTE_ID_HOST  = 0x00;
    private int ROUTE_ID_SMX   = 0x01;
    private int ROUTE_ID_UICC  = 0x02;
    private int ROUTE_ID_UICC2 = 0x04;
    private int ROUTE_ID_T4T_NFCEE = 0x7F;
    private int DEFAULT_ROUTE_ID_DEFAULT = 0x00;
    private int AID_MATCHING_EXACT_ONLY = 0x02;

    public static final int T4TNFCEE_STATUS_FAILED = -1;
    private Object mT4tNfcEeObj = new Object();
    private Bundle mT4tNfceeReturnBundle = new Bundle();

    public static NfcService getInstance() {
        return sService;
    }

    public int getRemainingAidTableSize() {
        return mDeviceHost.getRemainingAidTableSize();
    }


    public boolean getLastCommitRoutingStatus() {
        return mAidRoutingManager.getLastCommitRoutingStatus();
    }

    public AidRoutingManager getAidRoutingCache() {
        return mAidRoutingManager;
    }

    @Override
    public void onRemoteEndpointDiscovered(TagEndpoint tag) {
        sendMessage(NfcService.MSG_NDEF_TAG, tag);
    }

    /**
     * Notifies transaction
     */
    @Override
    public void onHostCardEmulationActivated(int technology) {
        if (mCardEmulationManager != null) {
            mCardEmulationManager.onHostCardEmulationActivated(technology);
        }
    }
    @Override
    public void onSeListenActivated() {
        if (mIsHceCapable) {
            mCardEmulationManager.onHostCardEmulationActivated(TagTechnology.NFC_A);
        }
    }

    @Override
    public void onSeListenDeactivated() {
        if( mIsHceCapable) {
            mCardEmulationManager.onHostCardEmulationDeactivated(TagTechnology.NFC_A);
        }
    }
    @Override
    public void onHostCardEmulationData(int technology, byte[] data) {
        if (mCardEmulationManager != null) {
            mCardEmulationManager.onHostCardEmulationData(technology, data);
        }
    }

    @Override
    public void onHostCardEmulationDeactivated(int technology) {
        if (mCardEmulationManager != null) {
            // Do metrics here so we don't slow the CE path down
            mNumHceDetected.incrementAndGet();
            mCardEmulationManager.onHostCardEmulationDeactivated(technology);
        }
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    @Override
    public void onLlcpLinkActivated(NfcDepEndpoint device) {
        if (!mIsBeamCapable) return;
        sendMessage(NfcService.MSG_LLCP_LINK_ACTIVATION, device);
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    @Override
    public void onLlcpLinkDeactivated(NfcDepEndpoint device) {
        if (!mIsBeamCapable) return;
        sendMessage(NfcService.MSG_LLCP_LINK_DEACTIVATED, device);
    }

    /**
     * Notifies P2P Device detected, first packet received over LLCP link
     */
    @Override
    public void onLlcpFirstPacketReceived(NfcDepEndpoint device) {
        if (!mIsBeamCapable) return;
        mNumP2pDetected.incrementAndGet();
        sendMessage(NfcService.MSG_LLCP_LINK_FIRST_PACKET, device);
    }

    @Override
    public void onRemoteFieldActivated() {
        sendMessage(NfcService.MSG_RF_FIELD_ACTIVATED, null);
    }

    @Override
    public void onRemoteFieldDeactivated() {
        sendMessage(NfcService.MSG_RF_FIELD_DEACTIVATED, null);
    }

    @Override
    public void onSeInitialized() {
        sendMessage(NfcService.MSG_SE_INIT, null);
    }

    @Override
    public void onNfcTransactionEvent(byte[] aid, byte[] data, String seName) {
        byte[][] dataObj = {aid, data, seName.getBytes()};
        sendMessage(NfcService.MSG_TRANSACTION_EVENT, dataObj);
        StatsLog.write(StatsLog.NFC_CARDEMULATION_OCCURRED, StatsLog.NFC_CARDEMULATION_OCCURRED__CATEGORY__OFFHOST, seName);
    }

    @Override
    public void onScrNotifyEvents(int event)
    {
      sendMessage(event , null);
    }

    final class ReaderModeParams {
        public int flags;
        public IAppCallback callback;
        public int presenceCheckDelay;
    }

    public NfcService(Application nfcApplication) {
        mUserId = ActivityManager.getCurrentUser();
        mContext = nfcApplication;

        mNfcTagService = new TagService();
        mNfcAdapter = new NfcAdapterService();

        mNxpNfcAdapter = new NxpNfcAdapterService();
        mNxpExtrasService = new NxpNfcAdapterExtrasService();
        Log.i(TAG, "Starting NFC service");

        try {
            mWiredSeClass = Class.forName("com.android.nfc.WiredSeService");
            mWiredSeObj = mWiredSeClass.newInstance();
        } catch (ClassNotFoundException | IllegalAccessException e){
            Log.e(TAG, "WiredSeService Class not found");
        } catch (InstantiationException e) {
            Log.e(TAG, "WiredSeService object Instantiation failed");
        }
        sService = this;

        mScreenStateHelper = new ScreenStateHelper(mContext);
        mContentResolver = mContext.getContentResolver();
        mDeviceHost = new NativeNfcManager(mContext, this);

        try {
            Object[] objargs = new Object[] {mContext};
            mNfcExtnsClass = Class.forName("com.android.nfc.NfcExtnsService");
            Constructor mNfcConstr = mNfcExtnsClass.getDeclaredConstructor(Context.class);
            mNfcExtnsObj   = mNfcConstr.newInstance(objargs);
        } catch(ClassNotFoundException | IllegalAccessException e) {
            Log.d(TAG, "NfcExtnsService not found");
        } catch (InstantiationException e) {
            Log.e(TAG, "NfcExtnsService object Instantaiation failed");
        }   catch (NoSuchMethodException e ) {
            Log.e(TAG, " NoSuchMethodException");
        }  catch (InvocationTargetException e) {
            Log.e(TAG, " InvocationTargetException");
        }
        Object[] args = new Object[] {mDeviceHost, mContext};
        try {
          mNfcExtraClass = Class.forName("com.android.nfc.NfcAdapterExtrasService");
          Constructor mNfcExtraConstr = mNfcExtraClass.getDeclaredConstructor(DeviceHost.class, Context.class);
          mNfcExtraObj = mNfcExtraConstr.newInstance(args);
        } catch (NoSuchMethodException e ) {
          Log.e(TAG, "NfcAdapterExtrasService NoSuchMethodException");
          e.printStackTrace();
        } catch (InvocationTargetException e) {
          Log.e(TAG, "NfcAdapterExtrasService InvocationTargetException");
          e.printStackTrace();
        } catch (ClassNotFoundException | IllegalAccessException e){
          Log.e(TAG, "NfcAdapterExtrasService Class not found");
          e.printStackTrace();
        } catch (InstantiationException e) {
          Log.e(TAG, "NfcAdapterExtrasService object Instantiation failed");
          e.printStackTrace();
        }

        mNfcUnlockManager = NfcUnlockManager.getInstance();

        mHandoverDataParser = new HandoverDataParser();
        boolean isNfcProvisioningEnabled = false;
        try {
            isNfcProvisioningEnabled = mContext.getResources().getBoolean(
                    R.bool.enable_nfc_provisioning);
        } catch (NotFoundException e) {
        }

        if (isNfcProvisioningEnabled) {
            mInProvisionMode = Settings.Global.getInt(mContentResolver,
                    Settings.Global.DEVICE_PROVISIONED, 0) == 0;
        } else {
            mInProvisionMode = false;
        }

        mNfcDispatcher = new NfcDispatcher(mContext, mHandoverDataParser, mInProvisionMode);

        mSecureElement = new NativeNfcSecureElement(mContext);
        mToastHandler = new ToastHandler(mContext);
        mPrefs = mContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        mPrefsEditor = mPrefs.edit();
        mNxpPrefs = mContext.getSharedPreferences(NXP_PREF, Context.MODE_PRIVATE);
        mNxpPrefsEditor = mNxpPrefs.edit();

        mState = NfcAdapter.STATE_OFF;

        mIsDebugBuild = "userdebug".equals(Build.TYPE) || "eng".equals(Build.TYPE);

        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        mRoutingWakeLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "NfcService:mRoutingWakeLock");
        mEeWakeLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "NfcService:mEeWakeLock");
        mKeyguard = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mVibrationEffect = VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE);

        mScreenState = mScreenStateHelper.checkScreenState();

        mNumTagsDetected = new AtomicInteger();
        mNumP2pDetected = new AtomicInteger();
        mNumHceDetected = new AtomicInteger();

        mBackupManager = new BackupManager(mContext);

        // Intents for all users
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, null);

        IntentFilter ownerFilter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        ownerFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        ownerFilter.addAction(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiver(mOwnerReceiver, ownerFilter);

        ownerFilter = new IntentFilter();
        ownerFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        ownerFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        ownerFilter.addDataScheme("package");
        mContext.registerReceiver(mOwnerReceiver, ownerFilter);

        IntentFilter policyFilter = new IntentFilter(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mContext.registerReceiverAsUser(mPolicyReceiver, UserHandle.ALL, policyFilter, null, null);

        updatePackageCache();

        PackageManager pm = mContext.getPackageManager();
        mIsBeamCapable = pm.hasSystemFeature(PackageManager.FEATURE_NFC_BEAM);
        mIsNdefPushEnabled =
            mPrefs.getBoolean(PREF_NDEF_PUSH_ON, NDEF_PUSH_ON_DEFAULT) &&
            mIsBeamCapable;
        if (mIsBeamCapable) {
            mP2pLinkManager = new P2pLinkManager(
                mContext, mHandoverDataParser, mDeviceHost.getDefaultLlcpMiu(),
                mDeviceHost.getDefaultLlcpRwSize());
        }
        enforceBeamShareActivityPolicy(mContext, new UserHandle(mUserId));

        mIsHceCapable =
                pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) ||
                pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF);
        mIsHceFCapable =
                pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF);
        if (mIsHceCapable) {
            mAidRoutingManager = new AidRoutingManager();
            mCardEmulationManager = new CardEmulationManager(mContext);
            mAidCache = mCardEmulationManager.getRegisteredAidCache();
        }
        mForegroundUtils = ForegroundUtils.getInstance();

        mIsSecureNfcCapable = mNfcAdapter.deviceSupportsNfcSecure();
        mIsSecureNfcEnabled =
            // To be reverted once device support added for secure NFC.
            //mPrefs.getBoolean(PREF_SECURE_NFC_ON, SECURE_NFC_ON_DEFAULT) &&
            //mIsSecureNfcCapable;
            mPrefs.getBoolean(PREF_SECURE_NFC_ON, SECURE_NFC_ON_DEFAULT);
        mDeviceHost.setNfcSecure(mIsSecureNfcEnabled);

        sToast_debounce_time_ms =
                mContext.getResources().getInteger(R.integer.toast_debounce_time_ms);
        if(sToast_debounce_time_ms > MAX_TOAST_DEBOUNCE_TIME) {
            sToast_debounce_time_ms = MAX_TOAST_DEBOUNCE_TIME;
        }

        // Make sure this is only called when object construction is complete.
        ServiceManager.addService(SERVICE_NAME, mNfcAdapter);
          new EnableDisableTask().execute(TASK_BOOT);  // do blocking boot tasks

        mHandler.sendEmptyMessageDelayed(MSG_UPDATE_STATS, STATS_UPDATE_INTERVAL_MS);

        IVrManager mVrManager = IVrManager.Stub.asInterface(ServiceManager.getService(
                mContext.VR_SERVICE));
        if (mVrManager != null) {
            try {
                mVrManager.registerListener(mVrStateCallbacks);
                mIsVrModeEnabled = mVrManager.getVrModeState();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to register VR mode state listener: " + e);
            }
        }
        mSEService = ISecureElementService.Stub.asInterface(ServiceManager.getService(
                Context.SECURE_ELEMENT_SERVICE));
    }

    private boolean isSEServiceAvailable() {
        if (mSEService == null) {
            mSEService = ISecureElementService.Stub.asInterface(ServiceManager.getService(
                    Context.SECURE_ELEMENT_SERVICE));
        }
        return (mSEService != null);
    }

    void initSoundPool() {
        synchronized (this) {
            if (mSoundPool == null) {
                mSoundPool = new SoundPool.Builder()
                        .setMaxStreams(1)
                        .setAudioAttributes(
                                new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .build())
                        .build();
                mStartSound = mSoundPool.load(mContext, R.raw.start, 1);
                mEndSound = mSoundPool.load(mContext, R.raw.end, 1);
                mErrorSound = mSoundPool.load(mContext, R.raw.error, 1);
            }
        }
    }

    void releaseSoundPool() {
        synchronized (this) {
            if (mSoundPool != null) {
                mSoundPool.release();
                mSoundPool = null;
            }
        }
    }

    void updatePackageCache() {
        PackageManager pm = mContext.getPackageManager();
        List<PackageInfo> packagesNfcEvents = pm.getPackagesHoldingPermissions(
                new String[] {android.Manifest.permission.NFC_TRANSACTION_EVENT},
                PackageManager.GET_ACTIVITIES);
        List<PackageInfo> packagesNfcPreferredPaymentChanged = pm.getPackagesHoldingPermissions(
                new String[] {android.Manifest.permission.NFC_PREFERRED_PAYMENT_INFO},
                PackageManager.GET_ACTIVITIES);
        synchronized (this) {
            mNfcEventInstalledPackages.clear();
            for (int i = 0; i < packagesNfcEvents.size(); i++) {
                mNfcEventInstalledPackages.add(packagesNfcEvents.get(i).packageName);
            }
            mNfcPreferredPaymentChangedInstalledPackages.clear();
            for (int i = 0; i < packagesNfcPreferredPaymentChanged.size(); i++) {
                mNfcPreferredPaymentChangedInstalledPackages.add(
                        packagesNfcPreferredPaymentChanged.get(i).packageName);
            }
        }
    }

    int doOpenSecureElementConnection() {
        mEeWakeLock.acquire();
        try {
            return mSecureElement.doOpenSecureElementConnection();
        } finally {
            mEeWakeLock.release();
        }
    }

    void doDisconnect(int handle) {
        mEeWakeLock.acquire();
        try {
            mSecureElement.doDisconnect(handle);
        } finally {
            mEeWakeLock.release();
        }
    }

    byte[] doTransceive(int handle, byte[] cmd) {
          mEeWakeLock.acquire();
        try {
            return doTransceiveNoLock(handle, cmd);
        } finally {
            mEeWakeLock.release();
        }
    }

    byte[] doTransceiveNoLock(int handle, byte[] cmd) {
        return mSecureElement.doTransceive(handle, cmd);
    }

    /**
     * Manages tasks that involve turning on/off the NFC controller.
     * <p/>
     * <p>All work that might turn the NFC adapter on or off must be done
     * through this task, to keep the handling of mState simple.
     * In other words, mState is only modified in these tasks (and we
     * don't need a lock to read it in these tasks).
     * <p/>
     * <p>These tasks are all done on the same AsyncTask background
     * thread, so they are serialized. Each task may temporarily transition
     * mState to STATE_TURNING_OFF or STATE_TURNING_ON, but must exit in
     * either STATE_ON or STATE_OFF. This way each task can be guaranteed
     * of starting in either STATE_OFF or STATE_ON, without needing to hold
     * NfcService.this for the entire task.
     * <p/>
     * <p>AsyncTask's are also implicitly queued. This is useful for corner
     * cases like turning airplane mode on while TASK_ENABLE is in progress.
     * The TASK_DISABLE triggered by airplane mode will be correctly executed
     * immediately after TASK_ENABLE is complete. This seems like the most sane
     * way to deal with these situations.
     * <p/>
     * <p>{@link #TASK_ENABLE} enables the NFC adapter, without changing
     * preferences
     * <p>{@link #TASK_DISABLE} disables the NFC adapter, without changing
     * preferences
     * <p>{@link #TASK_BOOT} does first boot work and may enable NFC
     */
    class EnableDisableTask extends AsyncTask<Integer, Void, Void> {
        @Override
        protected Void doInBackground(Integer... params) {
            // Sanity check mState
            switch (mState) {
                case NfcAdapter.STATE_TURNING_OFF:
                case NfcAdapter.STATE_TURNING_ON:
                    Log.e(TAG, "Processing EnableDisable task " + params[0] + " from bad state " +
                            mState);
                    return null;
            }

            /* AsyncTask sets this thread to THREAD_PRIORITY_BACKGROUND,
             * override with the default. THREAD_PRIORITY_BACKGROUND causes
             * us to service software I2C too slow for firmware download
             * with the NXP PN544.
             * TODO: move this to the DAL I2C layer in libnfc-nxp, since this
             * problem only occurs on I2C platforms using PN544
             */
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);

            switch (params[0].intValue()) {
                case TASK_ENABLE:
                    enableInternal();
                    break;
                case TASK_DISABLE:
                    disableInternal();
                    break;
                case TASK_BOOT:
                    boolean initialized;
                    if (mPrefs.getBoolean(PREF_FIRST_BOOT, true)) {
                        Log.i(TAG, "First Boot");
                        mPrefsEditor.putBoolean(PREF_FIRST_BOOT, false);
                        mPrefsEditor.apply();
                        mDeviceHost.factoryReset();
                    }
                    Log.d(TAG, "checking on firmware download");
                    if (mPrefs.getBoolean(PREF_NFC_ON, NFC_ON_DEFAULT)) {
                        Log.d(TAG, "NFC is on. Doing normal stuff");
                        initialized = enableInternal();
                    } else {
                        Log.d(TAG, "NFC is off.  Checking firmware version");
                        initialized = mDeviceHost.checkFirmware();
                    }
                    if (initialized) {
                        SystemProperties.set("nfc.initialized", "true");
                    }
                    break;
            }

            // Restore default AsyncTask priority
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            return null;
        }

        /**
         * Enable NFC adapter functions.
         * Does not toggle preferences.
         */
        boolean enableInternal() {
            if (mState == NfcAdapter.STATE_ON) {
                return true;
            }
            Log.i(TAG, "Enabling NFC");
            StatsLog.write(StatsLog.NFC_STATE_CHANGED, StatsLog.NFC_STATE_CHANGED__STATE__ON);
            updateState(NfcAdapter.STATE_TURNING_ON);

            WatchDogThread watchDog = new WatchDogThread("enableInternal", INIT_WATCHDOG_MS);
            watchDog.start();
            try {
                mRoutingWakeLock.acquire();
                try {
                    if (!mDeviceHost.initialize()) {
                        Log.w(TAG, "Error enabling NFC");
                        updateState(NfcAdapter.STATE_OFF);
                        return false;
                    }
                } finally {
                    mRoutingWakeLock.release();
                }
            } finally {
                watchDog.cancel();
            }
            int uiccSlot = 0;
            uiccSlot = mPrefs.getInt(PREF_CUR_SELECTED_UICC_ID, SECURE_ELEMENT_UICC_SLOT_DEFAULT);
            mDeviceHost.setPreferredSimSlot(uiccSlot);
            nci_version = getNciVersion();
            Log.d(TAG, "NCI_Version: " + nci_version);
            mNxpPrefsEditor.remove("PREF_SET_DEFAULT_ROUTE_ID").commit();
            if (mIsHceCapable) {
                // Generate the initial card emulation routing table
                mCardEmulationManager.onNfcEnabled();
                computeRoutingParameters();
            }

            synchronized (NfcService.this) {
                mObjectMap.clear();
                if (mIsBeamCapable) {
                    mP2pLinkManager.enableDisable(mIsNdefPushEnabled, true);
                }
                updateState(NfcAdapter.STATE_ON);

                onPreferredPaymentChanged(NfcAdapter.PREFERRED_PAYMENT_LOADED);
            }

            initSoundPool();

            mScreenState = mScreenStateHelper.checkScreenState();
            int screen_state_mask = (mNfcUnlockManager.isLockscreenPollingEnabled()) ?
                             (ScreenStateHelper.SCREEN_POLLING_TAG_MASK | mScreenState) : mScreenState;

            if(mNfcUnlockManager.isLockscreenPollingEnabled())
                applyRouting(false);

            mDeviceHost.doSetScreenState(screen_state_mask);

            sToast_debounce = false;

            /* Start polling loop */
            applyRouting(true);
            commitRouting();
            /* WiredSe Init after ESE is discovered and initialised */
            initWiredSe();
            synchronized (NfcService.this) {
                mNxpNfcState = NXP_NFC_STATE_ON;
            }
            return true;
        }

        /**
         * Disable all NFC adapter functions.
         * Does not toggle preferences.
         */
        boolean disableInternal() {
            if (mState == NfcAdapter.STATE_OFF) {
                return true;
            }
            Log.i(TAG, "Disabling NFC");
            StatsLog.write(StatsLog.NFC_STATE_CHANGED, StatsLog.NFC_STATE_CHANGED__STATE__OFF);
            updateState(NfcAdapter.STATE_TURNING_OFF);
            deInitWiredSe();
            /* Sometimes mDeviceHost.deinitialize() hangs, use a watch-dog.
             * Implemented with a new thread (instead of a Handler or AsyncTask),
             * because the UI Thread and AsyncTask thread-pools can also get hung
             * when the NFC controller stops responding */
            WatchDogThread watchDog = new WatchDogThread("disableInternal", ROUTING_WATCHDOG_MS);
            Log.d(TAG, "New Watchdog: WatchDog Thread ID is "+ watchDog.getId());
            watchDog.start();

            if (mIsHceCapable) {
                mCardEmulationManager.onNfcDisabled();
            }

            if (mIsBeamCapable) {
                mP2pLinkManager.enableDisable(false, false);
            }

            // Stop watchdog if tag present
            // A convenient way to stop the watchdog properly consists of
            // disconnecting the tag. The polling loop shall be stopped before
            // to avoid the tag being discovered again.
            maybeDisconnectTarget();

            mNfcDispatcher.setForegroundDispatch(null, null, null);


            boolean result = mDeviceHost.deinitialize();
            if (DBG) Log.d(TAG, "mDeviceHost.deinitialize() = " + result);
            isWiredOpen = false;
            watchDog.cancel();

            synchronized (NfcService.this) {
                mCurrentDiscoveryParameters = NfcDiscoveryParameters.getNfcOffParameters();
                updateState(NfcAdapter.STATE_OFF);
                mNxpNfcState = NXP_NFC_STATE_OFF;
            }
            releaseSoundPool();
            return result;
        }

        void updateState(int newState) {
            synchronized (NfcService.this) {
                if (newState == mState) {
                    return;
                }
                mState = newState;
                Intent intent = new Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
                intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(NfcAdapter.EXTRA_ADAPTER_STATE, mState);
                mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            }
        }
    }

    void saveNfcOnSetting(boolean on) {
        synchronized (NfcService.this) {
            mPrefsEditor.putBoolean(PREF_NFC_ON, on);
            mPrefsEditor.apply();
            mBackupManager.dataChanged();
        }
    }

    public void playSound(int sound) {
        synchronized (this) {
            if (mSoundPool == null) {
                Log.w(TAG, "Not playing sound when NFC is disabled");
                return;
            }

            if (mIsVrModeEnabled) {
                Log.d(TAG, "Not playing NFC sound when Vr Mode is enabled");
                return;
            }
            switch (sound) {
                case SOUND_START:
                    mSoundPool.play(mStartSound, 1.0f, 1.0f, 0, 0, 1.0f);
                    break;
                case SOUND_END:
                    mSoundPool.play(mEndSound, 1.0f, 1.0f, 0, 0, 1.0f);
                    break;
                case SOUND_ERROR:
                    mSoundPool.play(mErrorSound, 1.0f, 1.0f, 0, 0, 1.0f);
                    break;
            }
        }
    }

    synchronized int getUserId() {
        return mUserId;
    }

    void enforceBeamShareActivityPolicy(Context context, UserHandle uh) {
        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        IPackageManager mIpm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        boolean isGlobalEnabled = mIsNdefPushEnabled;
        boolean isActiveForUser =
            (!um.hasUserRestriction(UserManager.DISALLOW_OUTGOING_BEAM, uh)) &&
            isGlobalEnabled && mIsBeamCapable;
        if (DBG) {
            Log.d(TAG, "Enforcing a policy change on user: " + uh.toString() +
                    ", isActiveForUser = " + isActiveForUser);
        }
        try {
            mIpm.setComponentEnabledSetting(new ComponentName(
                    BeamShareActivity.class.getPackageName$(),
                    BeamShareActivity.class.getName()),
                    isActiveForUser ?
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP,
                    uh.getIdentifier());
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to change Beam status for user " + uh);
        }
    }

    final class NfcAdapterService extends INfcAdapter.Stub {
        @Override
        public boolean enable() throws RemoteException {
            synchronized (NfcService.this) {
                if (mNxpNfcState != NXP_NFC_STATE_OFF) {
                    Log.e(TAG, "mNxpNfcStateis not equal to NXP_NFC_STATE_OFF."
                                + " Enable NFC Rejected.");
                    return false;
                }
                mNxpNfcState = NXP_NFC_STATE_TURNING_ON;
            }
            NfcPermissions.enforceAdminPermissions(mContext);

            saveNfcOnSetting(true);

            new EnableDisableTask().execute(TASK_ENABLE);

            return true;
        }

       public void resonantFrequency(int isResonantFreq)
       {
            Log.d(TAG, "resonantFrequency");
            if(0x00 != isResonantFreq)
                mDeviceHost.doResonantFrequency(true);
            else
                mDeviceHost.doResonantFrequency(false);
       }
        @Override
        public boolean disable(boolean saveState) throws RemoteException {
          synchronized (NfcService.this) {
            if (mNxpNfcState != NXP_NFC_STATE_ON) {
              Log.e(TAG, "mNxpNfcStateis not equal to NXP_NFC_STATE_ON."
                + " Disable NFC Rejected.");
              return false;
            }
            mNxpNfcState = NXP_NFC_STATE_TURNING_OFF;
          }
          NfcPermissions.enforceAdminPermissions(mContext);

          if (saveState) {
            saveNfcOnSetting(false);
          }

          new EnableDisableTask().execute(TASK_DISABLE);

          return true;
        }

        @Override
        public void pausePolling(int timeoutInMs) {
            NfcPermissions.enforceAdminPermissions(mContext);

            if (timeoutInMs <= 0 || timeoutInMs > MAX_POLLING_PAUSE_TIMEOUT) {
                Log.e(TAG, "Refusing to pause polling for " + timeoutInMs + "ms.");
                return;
            }

            synchronized (NfcService.this) {
                mPollingPaused = true;
                mDeviceHost.disableDiscovery();
                mHandler.sendMessageDelayed(
                        mHandler.obtainMessage(MSG_RESUME_POLLING), timeoutInMs);
            }
        }

        @Override
        public void resumePolling() {
            NfcPermissions.enforceAdminPermissions(mContext);

            synchronized (NfcService.this) {
                if (!mPollingPaused) {
                    return;
                }

                mHandler.removeMessages(MSG_RESUME_POLLING);
                mPollingPaused = false;
                new ApplyRoutingTask().execute();
            }
        }

        @Override
        public boolean isNdefPushEnabled() throws RemoteException {
            synchronized (NfcService.this) {
                return mState == NfcAdapter.STATE_ON && mIsNdefPushEnabled;
            }
        }

        @Override
        public boolean enableNdefPush() throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            synchronized (NfcService.this) {
                if (mIsNdefPushEnabled || !mIsBeamCapable) {
                    return true;
                }
                Log.i(TAG, "enabling NDEF Push");
                mPrefsEditor.putBoolean(PREF_NDEF_PUSH_ON, true);
                mPrefsEditor.apply();
                mIsNdefPushEnabled = true;
                // Propagate the state change to all user profiles
                UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
                List <UserHandle> luh = um.getUserProfiles();
                for (UserHandle uh : luh){
                    enforceBeamShareActivityPolicy(mContext, uh);
                }
                enforceBeamShareActivityPolicy(mContext, new UserHandle(mUserId));
                if (isNfcEnabled()) {
                    mP2pLinkManager.enableDisable(true, true);
                }
                mBackupManager.dataChanged();
            }
            return true;
        }

       @Override
        public boolean isNfcSecureEnabled() throws RemoteException {
            synchronized (NfcService.this) {
                return mIsSecureNfcEnabled;
            }
        }

        @Override
        public boolean setNfcSecure(boolean enable) {
            NfcPermissions.enforceAdminPermissions(mContext);
            if(mKeyguard.isKeyguardLocked() && !enable) {
                Log.i(TAG, "KeyGuard need to be unlocked before setting Secure NFC OFF");
                return false;
            }

            synchronized (NfcService.this) {
                Log.i(TAG, "setting Secure NFC " + enable);
                mPrefsEditor.putBoolean(PREF_SECURE_NFC_ON, enable);
                mPrefsEditor.apply();
                mIsSecureNfcEnabled = enable;
                mBackupManager.dataChanged();
                mDeviceHost.setNfcSecure(enable);
                computeAndSetRoutingParameters();
            }
            if (mIsHceCapable) {
                mCardEmulationManager.onSecureNfcToggled();
            }
            if (enable)
                StatsLog.write(StatsLog.NFC_STATE_CHANGED, StatsLog.NFC_STATE_CHANGED__STATE__ON_LOCKED);
            else
                StatsLog.write(StatsLog.NFC_STATE_CHANGED, StatsLog.NFC_STATE_CHANGED__STATE__ON);
            return true;
        }

        @Override
        public boolean disableNdefPush() throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            synchronized (NfcService.this) {
                if (!mIsNdefPushEnabled || !mIsBeamCapable) {
                    return true;
                }
                Log.i(TAG, "disabling NDEF Push");
                mPrefsEditor.putBoolean(PREF_NDEF_PUSH_ON, false);
                mPrefsEditor.apply();
                mIsNdefPushEnabled = false;
                // Propagate the state change to all user profiles
                UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
                List <UserHandle> luh = um.getUserProfiles();
                for (UserHandle uh : luh){
                    enforceBeamShareActivityPolicy(mContext, uh);
                }
                enforceBeamShareActivityPolicy(mContext, new UserHandle(mUserId));
                if (isNfcEnabled()) {
                    mP2pLinkManager.enableDisable(false, true);
                }
                mBackupManager.dataChanged();
            }
            return true;
        }

        @Override
        public void setForegroundDispatch(PendingIntent intent,
                IntentFilter[] filters, TechListParcel techListsParcel) {
            NfcPermissions.enforceUserPermissions(mContext);
            if (!mForegroundUtils.isInForeground(Binder.getCallingUid())) {
                Log.e(TAG, "setForegroundDispatch: Caller not in foreground.");
                return;
            }
            // Short-cut the disable path
            if (intent == null && filters == null && techListsParcel == null) {
                mNfcDispatcher.setForegroundDispatch(null, null, null);
                return;
            }

            // Validate the IntentFilters
            if (filters != null) {
                if (filters.length == 0) {
                    filters = null;
                } else {
                    for (IntentFilter filter : filters) {
                        if (filter == null) {
                            throw new IllegalArgumentException("null IntentFilter");
                        }
                    }
                }
            }

            // Validate the tech lists
            String[][] techLists = null;
            if (techListsParcel != null) {
                techLists = techListsParcel.getTechLists();
            }

            mNfcDispatcher.setForegroundDispatch(intent, filters, techLists);
        }


        @Override
        public void setAppCallback(IAppCallback callback) {
            NfcPermissions.enforceUserPermissions(mContext);

            // don't allow Beam for managed profiles, or devices with a device owner or policy owner
            UserInfo userInfo = mUserManager.getUserInfo(UserHandle.getCallingUserId());
            if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_OUTGOING_BEAM,
                        userInfo.getUserHandle()) &&
                    mIsBeamCapable) {
                mP2pLinkManager.setNdefCallback(callback, Binder.getCallingUid());
            } else if (DBG) {
                Log.d(TAG, "Disabling default Beam behavior");
            }
        }

        @Override
        public boolean ignore(int nativeHandle, int debounceMs, ITagRemovedCallback callback)
                throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            if (debounceMs == 0 && mDebounceTagNativeHandle != INVALID_NATIVE_HANDLE
                && nativeHandle == mDebounceTagNativeHandle) {
              // Remove any previous messages and immediately debounce.
              mHandler.removeMessages(MSG_TAG_DEBOUNCE);
              mHandler.sendEmptyMessage(MSG_TAG_DEBOUNCE);
              return true;
            }

            TagEndpoint tag = (TagEndpoint) findAndRemoveObject(nativeHandle);
            if (tag != null) {
                // Store UID and params
                int uidLength = tag.getUid().length;
                synchronized (NfcService.this) {
                    mDebounceTagDebounceMs = debounceMs;
                    mDebounceTagNativeHandle = nativeHandle;
                    mDebounceTagUid = new byte[uidLength];
                    mDebounceTagRemovedCallback = callback;
                    System.arraycopy(tag.getUid(), 0, mDebounceTagUid, 0, uidLength);
                }

                // Disconnect from this tag; this should resume the normal
                // polling loop (and enter listen mode for a while), before
                // we pick up any tags again.
                tag.disconnect();
                mHandler.sendEmptyMessageDelayed(MSG_TAG_DEBOUNCE, debounceMs);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public void verifyNfcPermission() {
            NfcPermissions.enforceUserPermissions(mContext);
        }

        @Override
        public void invokeBeam() {
            if (!mIsBeamCapable) {
                return;
            }
            NfcPermissions.enforceUserPermissions(mContext);

            if (mForegroundUtils.isInForeground(Binder.getCallingUid())) {
                mP2pLinkManager.onManualBeamInvoke(null);
            } else {
                Log.e(TAG, "Calling activity not in foreground.");
            }
        }

        @Override
        public void invokeBeamInternal(BeamShareData shareData) {
            NfcPermissions.enforceAdminPermissions(mContext);
            Message msg = Message.obtain();
            msg.what = MSG_INVOKE_BEAM;
            msg.obj = shareData;
            // We have to send this message delayed for two reasons:
            // 1) This is an IPC call from BeamShareActivity, which is
            //    running when the user has invoked Beam through the
            //    share menu. As soon as BeamShareActivity closes, the UI
            //    will need some time to rebuild the original Activity.
            //    Waiting here for a while gives a better chance of the UI
            //    having been rebuilt, which means the screenshot that the
            //    Beam animation is using will be more accurate.
            // 2) Similarly, because the Activity that launched BeamShareActivity
            //    with an ACTION_SEND intent is now in paused state, the NDEF
            //    callbacks that it has registered may no longer be valid.
            //    Allowing the original Activity to resume will make sure we
            //    it has a chance to re-register the NDEF message / callback,
            //    so we share the right data.
            //
            //    Note that this is somewhat of a hack because the delay may not actually
            //    be long enough for 2) on very slow devices, but there's no better
            //    way to do this right now without additional framework changes.
            mHandler.sendMessageDelayed(msg, INVOKE_BEAM_DELAY_MS);
        }

        @Override
        public INfcTag getNfcTagInterface() throws RemoteException {
            return mNfcTagService;
        }

        @Override
        public INfcCardEmulation getNfcCardEmulationInterface() {
            if (mIsHceCapable) {
                return mCardEmulationManager.getNfcCardEmulationInterface();
            } else {
                return null;
            }
        }

        @Override
        public INfcFCardEmulation getNfcFCardEmulationInterface() {
            if (mIsHceFCapable) {
                return mCardEmulationManager.getNfcFCardEmulationInterface();
            } else {
                return null;
            }
        }

        @Override
        public int getState() throws RemoteException {
            synchronized (NfcService.this) {
                return mState;
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            NfcService.this.dump(fd, pw, args);
        }

        @Override
        public void dispatch(Tag tag) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            mNfcDispatcher.dispatchTag(tag);
        }

        @Override
        public void setP2pModes(int initiatorModes, int targetModes) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            mDeviceHost.setP2pInitiatorModes(initiatorModes);
            mDeviceHost.setP2pTargetModes(targetModes);
            applyRouting(true);
        }

        @Override
        public void setReaderMode(IBinder binder, IAppCallback callback, int flags, Bundle extras)
                throws RemoteException {
            int callingUid = Binder.getCallingUid();
            if (callingUid != Process.SYSTEM_UID && !mForegroundUtils.isInForeground(callingUid)) {
                Log.e(TAG, "setReaderMode: Caller is not in foreground and is not system process.");
                return;
            }
            synchronized (NfcService.this) {
                if (!isNfcEnabled()) {
                    Log.e(TAG, "setReaderMode() called while NFC is not enabled.");
                    return;
                }
                if (flags != 0) {
                    try {
                        mReaderModeParams = new ReaderModeParams();
                        mReaderModeParams.callback = callback;
                        mReaderModeParams.flags = flags;
                        mReaderModeParams.presenceCheckDelay = extras != null
                                ? (extras.getInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY,
                                        DEFAULT_PRESENCE_CHECK_DELAY))
                                : DEFAULT_PRESENCE_CHECK_DELAY;
                        binder.linkToDeath(mReaderModeDeathRecipient, 0);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Remote binder has already died.");
                        return;
                    }
                } else {
                    try {
                        mReaderModeParams = null;
                        StopPresenceChecking();
                        binder.unlinkToDeath(mReaderModeDeathRecipient, 0);
                    } catch (NoSuchElementException e) {
                        Log.e(TAG, "Reader mode Binder was never registered.");
                    }
                }
                applyRouting(false);
            }
        }

        @Override
        public INfcAdapterExtras getNfcAdapterExtrasInterface(String pkg) throws RemoteException {
            return (INfcAdapterExtras) mNfcExtraObj;
        }

        @Override
        public INfcDta getNfcDtaInterface(String pkg) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (mNfcDtaService == null) {
                mNfcDtaService = new NfcDtaService();
            }
            return mNfcDtaService;
        }

        @Override
        public void addNfcUnlockHandler(INfcUnlockHandler unlockHandler, int[] techList) {
            NfcPermissions.enforceAdminPermissions(mContext);

            int lockscreenPollMask = computeLockscreenPollMask(techList);
            synchronized (NfcService.this) {
                mNfcUnlockManager.addUnlockHandler(unlockHandler, lockscreenPollMask);
            }

            applyRouting(false);
        }

        @Override
        public void removeNfcUnlockHandler(INfcUnlockHandler token) throws RemoteException {
            synchronized (NfcService.this) {
                mNfcUnlockManager.removeUnlockHandler(token.asBinder());
            }

            applyRouting(false);
        }

        @Override
        public boolean deviceSupportsNfcSecure() {
            String skuList[] = mContext.getResources().getStringArray(
                R.array.config_skuSupportsSecureNfc);
            String sku = SystemProperties.get("ro.boot.hardware.sku");
            if (TextUtils.isEmpty(sku) || !ArrayUtils.contains(skuList, sku)) {
                return false;
            }
            return true;
        }

        private int computeLockscreenPollMask(int[] techList) {

            Map<Integer, Integer> techCodeToMask = new HashMap<Integer, Integer>();

            techCodeToMask.put(TagTechnology.NFC_A, NfcService.NFC_POLL_A);
            techCodeToMask.put(TagTechnology.NFC_B, NfcService.NFC_POLL_B);
            techCodeToMask.put(TagTechnology.NFC_V, NfcService.NFC_POLL_V);
            techCodeToMask.put(TagTechnology.NFC_F, NfcService.NFC_POLL_F);
            techCodeToMask.put(TagTechnology.NFC_BARCODE, NfcService.NFC_POLL_KOVIO);

            int mask = 0;

            for (int i = 0; i < techList.length; i++) {
                if (techCodeToMask.containsKey(techList[i])) {
                    mask |= techCodeToMask.get(techList[i]).intValue();
                }
            }

            return mask;
        }
        /**
         * An interface for nxp extensions
         */
        @Override
        public IBinder getNfcAdapterVendorInterface(String vendor) {
            if(vendor.equalsIgnoreCase("nxp")) {
                return (IBinder) mNxpNfcAdapter;
            } else {
                return null;
            }
        }
    }

    final class NxpNfcAdapterService extends INxpNfcAdapter.Stub {
        @Override
        public INxpNfcAdapterExtras getNxpNfcAdapterExtrasInterface() throws RemoteException {
            return mNxpExtrasService;
        }
        @Override
        public void MifareDesfireRouteSet(int routeLoc, boolean fullPower, boolean lowPower,
            boolean noPower) throws RemoteException {
          if (routeLoc == UICC2_ID_TYPE) {
            throw new RemoteException("UICC2 is not supported");
          }
          int protoRouteEntry = 0;
          /*UICC2 ID-4(fromApp) mapped to 3 (JNI)*/
          protoRouteEntry = ((routeLoc & 0x07) == 0x04) ? (0x03 << ROUTE_LOC_MASK) : /*UICC2*/
                            ((routeLoc & 0x07) == 0x02) ? (0x02 << ROUTE_LOC_MASK) : /*UICC1*/
                            ((routeLoc & 0x07) == 0x01) ? (0x01 << ROUTE_LOC_MASK) : /*eSE*/
                            0x00;
          protoRouteEntry |=
              ((fullPower ? (mDeviceHost.getDefaultDesfirePowerState() & 0x1F) | 0x01 : 0)
                  | (lowPower ? 0x01 << 1 : 0) | (noPower ? 0x01 << 2 : 0));

          if (routeLoc == 0x00) {
            /*
            bit pos 1 = Power Off
            bit pos 2 = Battery Off
            bit pos 4 = Screen Off
            Set these bits to 0 because in case routeLoc = HOST it can not work on POWER_OFF,
            BATTERY_OFF and SCREEN_OFF*/
            protoRouteEntry &= 0xE9;
          }

          Log.i(TAG, "MifareDesfireRouteSet : " + protoRouteEntry);
          mNxpPrefsEditor = mNxpPrefs.edit();
          mNxpPrefsEditor.putInt("PREF_MIFARE_DESFIRE_PROTO_ROUTE_ID", protoRouteEntry);
          mNxpPrefsEditor.commit();
          Log.i(TAG, "MifareDesfireRouteSet function in");
          commitRouting();
        }
        @Override
        public void MifareCLTRouteSet(int routeLoc, boolean fullPower, boolean lowPower,
            boolean noPower) throws RemoteException {
          if (routeLoc == UICC2_ID_TYPE) {
            throw new RemoteException("UICC2 is not supported");
          }

          int techRouteEntry = 0;
          techRouteEntry =  ((routeLoc & 0x07) == 0x04) ? (0x03 << ROUTE_LOC_MASK) : /*UICC2*/
                            ((routeLoc & 0x07) == 0x02) ? (0x02 << ROUTE_LOC_MASK) : /*UICC1*/
                            ((routeLoc & 0x07) == 0x01) ? (0x01 << ROUTE_LOC_MASK) : /*eSE*/
                            0x00;
          techRouteEntry |=
              ((fullPower ? (mDeviceHost.getDefaultMifareCLTPowerState() & 0x1F) | 0x01 : 0)
                  | (lowPower ? 0x01 << 1 : 0) | (noPower ? 0x01 << 2 : 0));
          techRouteEntry |= (TECH_TYPE_A << TECH_TYPE_MASK);

          Log.i(TAG, "MifareCLTRouteSet : " + techRouteEntry);
          mNxpPrefsEditor = mNxpPrefs.edit();
          mNxpPrefsEditor.putInt("PREF_MIFARE_CLT_ROUTE_ID", techRouteEntry);
          mNxpPrefsEditor.commit();
          commitRouting();
        }
        @Override
        public void NfcFRouteSet(int routeLoc, boolean fullPower, boolean lowPower,
            boolean noPower) throws RemoteException {
          NfcPermissions.enforceUserPermissions(mContext);
          if (routeLoc == UICC2_ID_TYPE) {
            throw new RemoteException("UICC2 is not supported");
          }

          int techRouteEntry = 0;
          techRouteEntry =  ((routeLoc & 0x07) == 0x04) ? (0x03 << ROUTE_LOC_MASK) : /*UICC2*/
                            ((routeLoc & 0x07) == 0x02) ? (0x02 << ROUTE_LOC_MASK) : /*UICC1*/
                            ((routeLoc & 0x07) == 0x01) ? (0x01 << ROUTE_LOC_MASK) : /*eSE*/
                            0x00;
          techRouteEntry |=
              ((fullPower ? (mDeviceHost.getDefaultMifareCLTPowerState() & 0x1F) | 0x01 : 0)
                  | (lowPower ? 0x01 << 1 : 0) | (noPower ? 0x01 << 2 : 0));
          techRouteEntry |= (TECH_TYPE_F << TECH_TYPE_MASK);

          Log.i(TAG, "NfcFRouteSet : " + techRouteEntry);
          mNxpPrefsEditor = mNxpPrefs.edit();
          mNxpPrefsEditor.putInt("PREF_FELICA_CLT_ROUTE_ID", techRouteEntry);
          mNxpPrefsEditor.commit();
          commitRouting();
        }

        @Override
        public int[] getActiveSecureElementList(String pkg) throws RemoteException {
          int[] list = null;
          if (isNfcEnabled()) {
            list = mDeviceHost.doGetActiveSecureElementList();
          }
          if (list == null) {
            Log.e(TAG, "Array List is null.");
            return null;
          }
          for (int i = 0; i < list.length; i++) {
            Log.d(TAG, "Active element = " + list[i]);
          }
          return list;
        }

        public int getReaderMode (String readerType) {
          int reader = SE_READER_TYPE_INAVLID;
          if(readerType.equals("MPOS")) {
            reader =  SE_READER_TYPE_MPOS;
          } else if (readerType.equals("MFC")) {
            reader = SE_READER_TYPE_MFC;
          } else {
             /* Invalid Secure Reader Type received. */
          }
          return reader;
        }

        public int setReaderMode (boolean on, String readerType) {
            int status = NfcConstants.SCR_STATUS_REJECTED;
            // Check if NFC is enabled
            if (!isNfcEnabled()) {
              return status;
            }
            synchronized(NfcService.this) {
              int reader = SE_READER_TYPE_INAVLID;
              reader = getReaderMode(readerType);
              switch(reader) {
              case SE_READER_TYPE_MPOS:
                status = mDeviceHost.mposSetReaderMode(on);
              break;
              case SE_READER_TYPE_MFC:
                status = mDeviceHost.configureSecureReaderMode(on, readerType);
              break;
              default :
                Log.e(TAG, "Invalid Secure Reader Type received.");
              }
              if (status == NfcConstants.SCR_STATUS_SUCCESS) {
                if(on) {
                  SE_READER_TYPE = reader;
                } else {
                  SE_READER_TYPE = SE_READER_TYPE_INAVLID;
                  if(nci_version != NCI_VERSION_2_0) {
                    applyRouting(true);
                  } else if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED
                          || mNfcUnlockManager.isLockscreenPollingEnabled()) {
                    applyRouting(false);
                  }
               }
             }
             return status;
          }
        }

        @Override
        public int mPOSSetReaderMode (String pkg, boolean on) {
          return setReaderMode(on, "MPOS");
        }

        @Override
        public int configureSecureReader (boolean on, String readerType) {
            return setReaderMode(on, readerType);
        }

        @Override
        public boolean mPOSGetReaderMode (String pkg) {
            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return false;
            }

            boolean status = false;
            synchronized(NfcService.this) {
                status = mDeviceHost.mposGetReaderMode();
            }
            return status;
        }
        @Override
        public void changeDiscoveryTech(IBinder binder, int pollTech, int listenTech)
               throws RemoteException {

            synchronized (NfcService.this) {
            if (!(mState == NfcAdapter.STATE_ON)) {
               if (DBG) Log.d(TAG, "changeDiscoveryTech. NFC is not enabled");
                  return;
            }

            if (DBG) Log.d(TAG, "changeDiscoveryTech. pollTech : 0x" + Integer.toHexString(pollTech) + ", listenTech : 0x" + Integer.toHexString(listenTech));

            //In case both parameters are set to 0xFF, which means that original poll, listen techs are applied.
            if (pollTech == 0xFF && listenTech == 0xFF) {
                //Recover to previous state.
                try {
                    if (!mIsNdefPushEnabled) {
                        if (DBG) Log.d(TAG, "changeDiscoveryTech. Android Beam was temporarily enabled, so disable this.");
                        mP2pLinkManager.enableDisable(false, true);
                    }
                    mDeviceHost.doChangeDiscoveryTech(pollTech, listenTech);
                   } catch(NoSuchElementException e) {
                    Log.e(TAG, "Change Tech Binder was never registered.");
                }
            } else {
                //Change discovery tech.
                    if (!mIsNdefPushEnabled) {
                        if (DBG) Log.d(TAG, "changeDiscoveryTech. Android Beam is disabled, so enable this temporarily.");
                        mP2pLinkManager.enableDisable(true, true);
                    }
                    mDeviceHost.doChangeDiscoveryTech(pollTech, listenTech);
                  }

            if (DBG) Log.d(TAG, "applyRouting #15");
               applyRouting(true);
            }
        }

        @Override
        public void stopPoll(String pkg, int mode) {
            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return;
            }

            synchronized(NfcService.this) {
                mDeviceHost.stopPoll(mode);
            }
        }
        @Override
        public void startPoll(String pkg) {
           // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return;
            }

            synchronized(NfcService.this) {
                mDeviceHost.startPoll();
            }
        }

        @Override
        public int nfcSelfTest(int type) {
            NfcPermissions.enforceUserPermissions(mContext);
            NfcPermissions.enforceAdminPermissions(mContext);
            int status = 0xFF, prbs_test = 0x06, swpSelf_test = 0x07;
            Method mNfcSelfTestMethod;
            Log.i(TAG,"doNfcSelfTest type ENter : " + type);
            synchronized(NfcService.this) {
              try {
                 if(mNfcExtnsObj!=null && (type == prbs_test || type == swpSelf_test)){
                    mNfcSelfTestMethod = mNfcExtnsClass.getDeclaredMethod("doNfcSelfTest", int.class);
                    mNfcSelfTestMethod.invoke(mNfcExtnsObj,type);
                 }else {
                    status = mDeviceHost.doNfcSelfTest(type);
                 }
              } catch (NoSuchMethodException e ) {
                  Log.e(TAG, " NoSuchMethodException");
              } catch (InvocationTargetException e) {
                  Log.e(TAG, " InvocationTargetException");
              }catch (IllegalAccessException e) {
                  Log.e(TAG, " IllegalAccessException");
              }
            }
            return status;
        }

        @Override
        public void DefaultRouteSet(int routeLoc, boolean fullPower, boolean lowPower, boolean noPower)
                throws RemoteException
        {
            if(routeLoc == UICC2_ID_TYPE) {
                throw new RemoteException("UICC2 is not supported");
            }
            if (mIsHceCapable) {
                int protoRouteEntry = 0;
                protoRouteEntry=((routeLoc & 0x07) == 0x04) ? (0x03 << ROUTE_LOC_MASK) : /*UICC2*/
                                ((routeLoc & 0x07) == 0x02) ? (0x02 << ROUTE_LOC_MASK) : /*UICC1*/
                                ((routeLoc & 0x07) == 0x01) ? (0x01 << ROUTE_LOC_MASK) : /*eSE*/
                                0x00;
                protoRouteEntry |=
                    ((fullPower ? (mDeviceHost.getDefaultAidPowerState() & 0x1F) | 0x01 : 0)
                        | (lowPower ? 0x01 << 1 : 0) | (noPower ? 0x01 << 2 : 0));

                if(routeLoc == 0x00)
                {
                    /*
                    bit pos 1 = Power Off
                    bit pos 2 = Battery Off
                    bit pos 4 = Screen Off
                    Set these bits to 0 because in case routeLoc = HOST it can not work on
                    POWER_OFF, BATTERY_OFF and SCREEN_OFF*/
                    protoRouteEntry &= 0xE9;
                }
                Log.i(TAG,"DefaultRouteSet : " + protoRouteEntry);
                if(GetDefaultRouteLoc() != routeLoc)
                {
                    mNxpPrefsEditor = mNxpPrefs.edit();
                    mNxpPrefsEditor.putInt("PREF_SET_DEFAULT_ROUTE_ID", protoRouteEntry );
                    mNxpPrefsEditor.commit();
                    mIsRouteForced = true;
                    if (mIsHceCapable) {
                        mAidRoutingManager.onNfccRoutingTableCleared();
                        mCardEmulationManager.onRoutingTableChanged();
                    }
                    mIsRouteForced = false;
                }
            }
            else{
                Log.i(TAG,"DefaultRoute can not be set. mIsHceCapable = flase");
            }
        }

        @Override
        public byte[] getFWVersion()
        {
            byte[] buf = new byte[3];
            Log.i(TAG, "Starting getFwVersion");
            int fwver = mDeviceHost.getFWVersion();
            buf[0] = (byte)((fwver&0xFF00)>>8);
            buf[1] = (byte)((fwver&0xFF));
            buf[2] = (byte)((fwver&0xFF0000)>>16);
            Log.i(TAG, "Firmware version is 0x"+ buf[0]+" 0x"+buf[1]);
            return buf;
        }

        private void WaitForAdapterChange(int state) {
            while (true) {
                synchronized(NfcService.this) {
                    if(mState == state) {
                        break;
                    }
                }
                try {
                    Thread.sleep(100);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return;
        }

        @Override
        public byte[] readerPassThruMode(byte status, byte modulationTyp)
            throws RemoteException {

          Log.i(TAG, "Reader pass through mode request: 0x" + status +
                         " with modulation: 0x" + modulationTyp);
          return mDeviceHost.readerPassThruMode(status, modulationTyp);
        }

        @Override
        public byte[] transceiveAppData(byte[] data) throws RemoteException {

          Log.i(TAG, "Transceive requested on reader pass through mode");
          return mDeviceHost.transceiveAppData(data);
        }
        @Override
        public int setConfig(String configs , String pkg) {
            Log.e(TAG, "Setting configs for Transit" );
            /*Check permissions*/
            NfcPermissions.enforceAdminPermissions(mContext);
            /*Check if any NFC transactions are ongoing*/
            if(mDeviceHost.isNfccBusy())
            {
                Log.e(TAG, "NFCC is busy.." );
                return TRANSIT_SETCONFIG_STAT_FAILED;
            }
            /*check if format of configs is fine*/
            /*Save configurations to file*/
            try {
                File newTextFile = new File("/data/nfc/libnfc-nxpTransit.conf");
                if(configs == null)
                {
                    if(newTextFile.delete()){
                        Log.e(TAG, "Removing transit config file. Taking default Value" );
                    }else{
                        System.out.println("Error taking defualt value");
                    }
                }
                else
                {
                    FileWriter fw = new FileWriter(newTextFile);
                    fw.write(configs);
                    fw.close();
                    fw = null;
                    Log.e(TAG, "File Written to libnfc-nxpTransit.conf successfully" );
                }
                newTextFile = null;
                mDeviceHost.setTransitConfig(configs);
            } catch (Exception e) {
                e.printStackTrace();
                return TRANSIT_SETCONFIG_STAT_FAILED;
            }

            /*restart NFC service*/
            try {
                mNfcAdapter.disable(true);
                WaitForAdapterChange(NfcAdapter.STATE_OFF);
                mNfcAdapter.enable();
                WaitForAdapterChange(NfcAdapter.STATE_ON);
            } catch (Exception e) {
                Log.e(TAG, "Unable to restart NFC Service");
                e.printStackTrace();
                return TRANSIT_SETCONFIG_STAT_FAILED;
            }
            return TRANSIT_SETCONFIG_STAT_SUCCESS;
        }
        @Override
        public int selectUicc(int uiccSlot) throws RemoteException {
            synchronized(NfcService.this) {
                if (!isNfcEnabled()) {
                    throw new RemoteException("NFC is not enabled");
                }
                int status =  mDeviceHost.doselectUicc(uiccSlot);
                Log.i(TAG, "Update routing table");
                /*In case of UICC connected and Enabled or Removed ,
                 *Reconfigure the routing table based on current UICC parameters
                 **/
                if((status == UICC_CONFIGURED)||(status == UICC_NOT_CONFIGURED))
                {
                    mPrefsEditor.putInt(PREF_CUR_SELECTED_UICC_ID, uiccSlot);
                    mPrefsEditor.apply();
                    if((mAidRoutingManager != null) && (mCardEmulationManager != null))
                    {
                        Log.i(TAG, "Update routing table");
                        mAidRoutingManager.onNfccRoutingTableCleared();
                        mDeviceHost.clearRoutingEntry(AID_ENTRY);
                        mDeviceHost.clearRoutingEntry(TECH_ENTRY);
                        mDeviceHost.clearRoutingEntry(PROTOCOL_ENTRY);
                        computeRoutingParameters();
                        mCardEmulationManager.onNfcEnabled();
                        if (getLastCommitRoutingStatus() == false) {
                          commitRouting();
                        }
                    }
                    else
                    {
                        Log.i(TAG, "Update only Mifare and Desfire route");
                        applyRouting(false);
                    }
                }
                return status;
            }
        }
        @Override
        public List<NfcAidServiceInfo> getServicesAidInfo(int userId, String category){
            return mCardEmulationManager.getServicesAidInfo(userId, category);
        }

        @Override
        public int updateServiceState(int userId , Map serviceState) {
            return mCardEmulationManager.updateServiceState(userId ,serviceState);
        }

        @Override
        public int getMaxAidRoutingTableSize() throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            return getAidRoutingTableSize();
        }

        @Override
        public int getCommittedAidRoutingTableSize() throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            return (getAidRoutingTableSize() - getRemainingAidTableSize());
        }

        @Override
        public int getSelectedUicc() throws RemoteException {
            if (!isNfcEnabled()) {
                throw new RemoteException("NFC is not enabled");
            }
            return mDeviceHost.doGetSelectedUicc();
        }
        /*
        * Activate the SecureElement Interface
        * @return: success/failure
        */
        @Override
        public int activateSeInterface(){
            synchronized (NfcService.this) {
                return mSecureElement.activateSeInterface();
            }
        }

        /*
        * Deactivate the SecureElement Interface
        * @return: success/failure
        */
        @Override
        public int deactivateSeInterface(){
            synchronized (NfcService.this) {
                return mSecureElement.deactivateSeInterface();
            }
        }

        @Override
        public int setFieldDetectMode(boolean mode) {
          NfcPermissions.enforceUserPermissions(mContext);
          return mDeviceHost.doSetFieldDetectMode(mode);
        }

        @Override
        public boolean isFieldDetectEnabled() {
          NfcPermissions.enforceUserPermissions(mContext);
          return mDeviceHost.isFieldDetectEnabled();
        }

        @Override
        public int doWriteT4tData(byte[] fileId, byte[] data, int length) {
          NfcPermissions.enforceUserPermissions(mContext);
          Bundle writeBundle = new Bundle();
          writeBundle.putByteArray("fileId", fileId);
          writeBundle.putByteArray("writeData", data);
          writeBundle.putInt("length", length);
          try {
            sendMessage(NfcService.MSG_WRITE_T4TNFCEE, writeBundle);
            synchronized (mT4tNfcEeObj) {
              mT4tNfcEeObj.wait(1000);
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
          /*return T4TNFCEE_STATUS_FAILED(-1) if readData not found.
         This can happen in case of mT4tNfcEeObj timeout*/
          int status = mT4tNfceeReturnBundle.getInt("writeStatus", T4TNFCEE_STATUS_FAILED);
          mT4tNfceeReturnBundle.clear();
          return status;
        }

        @Override
        public byte[] doReadT4tData(byte[] fileId) {
          NfcPermissions.enforceUserPermissions(mContext);
          Bundle readBundle = new Bundle();
          readBundle.putByteArray("fileId", fileId);
          try {
            sendMessage(NfcService.MSG_READ_T4TNFCEE, readBundle);
            synchronized (mT4tNfcEeObj) {
              mT4tNfcEeObj.wait(1000);
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
          /*getByteArray returns null if readData not found.
          This can happen in case of mT4tNfcEeObj timeout*/
          byte[] readData = mT4tNfceeReturnBundle.getByteArray("readData");
          mT4tNfceeReturnBundle.clear();
          return readData;
        }
}

    final class ReaderModeDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            synchronized (NfcService.this) {
                if (mReaderModeParams != null) {
                    mReaderModeParams = null;
                    applyRouting(false);
                }
            }
        }
    }

    final class TagService extends INfcTag.Stub {
        @Override
        public int connect(int nativeHandle, int technology) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag = null;

            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_DISCONNECT;
            }

            if (!tag.isPresent()) {
                return ErrorCodes.ERROR_DISCONNECT;
            }

            // Note that on most tags, all technologies are behind a single
            // handle. This means that the connect at the lower levels
            // will do nothing, as the tag is already connected to that handle.
            if (tag.connect(technology)) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_DISCONNECT;
            }
        }

        @Override
        public int reconnect(int nativeHandle) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                if (tag.reconnect()) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_DISCONNECT;
                }
            }
            return ErrorCodes.ERROR_DISCONNECT;
        }

        @Override
        public int[] getTechList(int nativeHandle) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            /* find the tag in the hmap */
            TagEndpoint tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                return tag.getTechList();
            }
            return null;
        }

        @Override
        public boolean isPresent(int nativeHandle) throws RemoteException {
            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return false;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return false;
            }

            return tag.isPresent();
        }

        @Override
        public boolean isNdef(int nativeHandle) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return false;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            int[] ndefInfo = new int[2];
            if (tag == null) {
                return false;
            }
            return tag.checkNdef(ndefInfo);
        }

        @Override
        public TransceiveResult transceive(int nativeHandle, byte[] data, boolean raw)
                throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag = null;
            byte[] response;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                // Check if length is within limits
                if (data.length > getMaxTransceiveLength(tag.getConnectedTechnology())) {
                    return new TransceiveResult(TransceiveResult.RESULT_EXCEEDED_LENGTH, null);
                }
                int[] targetLost = new int[1];
                response = tag.transceive(data, raw, targetLost);
                int result;
                if (response != null) {
                    result = TransceiveResult.RESULT_SUCCESS;
                } else if (targetLost[0] == 1) {
                    result = TransceiveResult.RESULT_TAGLOST;
                } else {
                    result = TransceiveResult.RESULT_FAILURE;
                }
                return new TransceiveResult(result, response);
            }
            return null;
        }

        @Override
        public NdefMessage ndefRead(int nativeHandle) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                byte[] buf = tag.readNdef();
                if (buf == null) {
                    return null;
                }

                /* Create an NdefMessage */
                try {
                    return new NdefMessage(buf);
                } catch (FormatException e) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public int ndefWrite(int nativeHandle, NdefMessage msg) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (msg == null) return ErrorCodes.ERROR_INVALID_PARAM;

            if (tag.writeNdef(msg.toByteArray())) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }

        }

        @Override
        public boolean ndefIsWritable(int nativeHandle) throws RemoteException {
            throw new UnsupportedOperationException();
        }

        @Override
        public int ndefMakeReadOnly(int nativeHandle) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.makeReadOnly()) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public int formatNdef(int nativeHandle, byte[] key) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.formatNdef(key)) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public Tag rediscover(int nativeHandle) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                // For now the prime usecase for rediscover() is to be able
                // to access the NDEF technology after formatting without
                // having to remove the tag from the field, or similar
                // to have access to NdefFormatable in case low-level commands
                // were used to remove NDEF. So instead of doing a full stack
                // rediscover (which is poorly supported at the moment anyway),
                // we simply remove these two technologies and detect them
                // again.
                tag.removeTechnology(TagTechnology.NDEF);
                tag.removeTechnology(TagTechnology.NDEF_FORMATABLE);
                tag.findAndReadNdef();
                // Build a new Tag object to return
                try {
                    Tag newTag = new Tag(tag.getUid(), tag.getTechList(),
                            tag.getTechExtras(), tag.getHandle(), this);
                    return newTag;
                } catch (Exception e) {
                    Log.e(TAG, "Tag creation exception.", e);
                    return null;
                }
            }
            return null;
        }

        @Override
        public int setTimeout(int tech, int timeout) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            boolean success = mDeviceHost.setTimeout(tech, timeout);
            if (success) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }
        }

        @Override
        public int getTimeout(int tech) throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            return mDeviceHost.getTimeout(tech);
        }

        @Override
        public void resetTimeouts() throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);

            mDeviceHost.resetTimeouts();
        }

        @Override
        public boolean canMakeReadOnly(int ndefType) throws RemoteException {
            return mDeviceHost.canMakeReadOnly(ndefType);
        }

        @Override
        public int getMaxTransceiveLength(int tech) throws RemoteException {
            return mDeviceHost.getMaxTransceiveLength(tech);
        }

        @Override
        public boolean getExtendedLengthApdusSupported() throws RemoteException {
            return mDeviceHost.getExtendedLengthApdusSupported();
        }
    }

    final class NfcDtaService extends INfcDta.Stub {
        public void enableDta() throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if(!sIsDtaMode) {
                mDeviceHost.enableDtaMode();
                sIsDtaMode = true;
                Log.d(TAG, "DTA Mode is Enabled ");
            }
        }

        public void disableDta() throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if(sIsDtaMode) {
                mDeviceHost.disableDtaMode();
                sIsDtaMode = false;
            }
        }

        public boolean enableServer(String serviceName, int serviceSap, int miu,
                int rwSize,int testCaseId) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);

            if (serviceName.equals(null) || !mIsBeamCapable)
                return false;

            mP2pLinkManager.enableExtDtaSnepServer(serviceName, serviceSap, miu, rwSize,testCaseId);
            return true;
        }

        public void disableServer() throws RemoteException {
            if (!mIsBeamCapable)
                return;
            NfcPermissions.enforceAdminPermissions(mContext);
            mP2pLinkManager.disableExtDtaSnepServer();
        }

        public boolean enableClient(String serviceName, int miu, int rwSize,
                int testCaseId) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);

            if (testCaseId == 0 || !mIsBeamCapable)
                return false;

            if (testCaseId>20){
                sIsShortRecordLayout=true;
                testCaseId=testCaseId-20;
            } else {
                sIsShortRecordLayout=false;
            }
            Log.d("testCaseId", ""+testCaseId);
            mP2pLinkManager.enableDtaSnepClient(serviceName, miu, rwSize, testCaseId);
            return true;
        }

        public void disableClient() throws RemoteException {
          if (!mIsBeamCapable)
              return;
          NfcPermissions.enforceAdminPermissions(mContext);
          mP2pLinkManager.disableDtaSnepClient();
        }

        public boolean registerMessageService(String msgServiceName)
                throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if(msgServiceName.equals(null))
                return false;

            DtaServiceConnector.setMessageService(msgServiceName);
            return true;
        }

    };

    final class NxpNfcAdapterExtrasService extends INxpNfcAdapterExtras.Stub {
    private Bundle writeNoException() {
        Bundle p = new Bundle();
        p.putInt("e", 0);
        return p;
    }

    private Bundle writeEeException(int exceptionType, String message) {
        Bundle p = new Bundle();
        p.putInt("e", exceptionType);
        p.putString("m", message);
        return p;
    }
     /*
     * Enable or disable eSE COS patch dedicated mode
     * @param mode 1:dedicated mode 0:normal mode
     * @return 0:success 1:SWP is already in use -1:error
     */
    @Override
    public boolean accessControlForCOSU (int mode)
    {
        return mDeviceHost.accessControlForCOSU(mode);
    }

    @Override
    public boolean reset(String pkg) throws RemoteException {
        Bundle result;
        boolean stat = false;
        try {
            stat = _nfcEeReset();
            result = writeNoException();
        } catch (IOException e) {
            result = writeEeException(EE_ERROR_IO, e.getMessage());
        }
        Log.d(TAG,"reset" + stat);
        return stat;
    }

    boolean _nfcEeReset() throws IOException {
        synchronized (NfcService.this) {
          return mSecureElement.doReset(EE_HANDLE_0xF3);
        }
     }

    @Override
    public Bundle getAtr(String pkg) throws RemoteException {

        Bundle result;
        byte[] out;
        try {
            out = _getAtr();
            result = writeNoException();
            result.putByteArray("out", out);
        } catch (IOException e) {
            result = writeEeException(EE_ERROR_IO, e.getMessage());
        }
        Log.d(TAG,"getAtr result " + result);
        return result;
    }

    private byte[] _getAtr() throws IOException {
        synchronized(NfcService.this) {
            if (!isNfcEnabled()) {
                throw new IOException("NFC is not enabled");
            }
        }
        return mSecureElement.doGetAtr(EE_HANDLE_0xF3);
    }

}

    boolean isNfcEnabledOrShuttingDown() {
        synchronized (this) {
            return (mState == NfcAdapter.STATE_ON || mState == NfcAdapter.STATE_TURNING_OFF);
        }
    }

    public boolean isNfcEnabled() {
        synchronized (this) {
            return mState == NfcAdapter.STATE_ON;
        }
    }

    class WatchDogThread extends Thread {
        final Object mCancelWaiter = new Object();
        final int mTimeout;
        boolean mCanceled = false;

        public WatchDogThread(String threadName, int timeout) {
            super(threadName);
            mTimeout = timeout;
        }

        @Override
        public void run() {
            try {
                synchronized (mCancelWaiter) {
                    mCancelWaiter.wait(mTimeout);
                    if (mCanceled) {
                        return;
                    }
                }
            } catch (InterruptedException e) {
                // Should not happen; fall-through to abort.
                Log.w(TAG, "Watchdog thread interruped.");
                interrupt();
            }
            if(mRoutingWakeLock.isHeld()){
                Log.e(TAG, "Watchdog triggered, release lock before aborting.");
                mRoutingWakeLock.release();
            }
            Log.e(TAG, "Watchdog triggered, aborting.");
            StatsLog.write(StatsLog.NFC_STATE_CHANGED, StatsLog.NFC_STATE_CHANGED__STATE__CRASH_RESTART);
            storeNativeCrashLogs();
            mDeviceHost.doAbort(getName());
        }

        public synchronized void cancel() {
            synchronized (mCancelWaiter) {
                mCanceled = true;
                mCancelWaiter.notify();
            }
        }
    }

    static byte[] hexStringToBytes(String s) {
        if (s == null || s.length() == 0) return null;
        int len = s.length();
        if (len % 2 != 0) {
            s = '0' + s;
            len++;
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Read mScreenState and apply NFC-C polling and NFC-EE routing
     */
    void applyRouting(boolean force) {
        Log.d(TAG, "applyRouting enter");
        if (!isNfcEnabledOrShuttingDown()) {
            return;
        }
        synchronized (this) {
            WatchDogThread watchDog = new WatchDogThread("applyRouting", ROUTING_WATCHDOG_MS);
            if (mInProvisionMode) {
                mInProvisionMode = Settings.Global.getInt(mContentResolver,
                        Settings.Global.DEVICE_PROVISIONED, 0) == 0;
                if (!mInProvisionMode) {
                    // Notify dispatcher it's fine to dispatch to any package now
                    // and allow handover transfers.
                    mNfcDispatcher.disableProvisioningMode();
                }
            }
            // Special case: if we're transitioning to unlocked state while
            // still talking to a tag, postpone re-configuration.
            if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED && isTagPresent()) {
                Log.d(TAG, "Not updating discovery parameters, tag connected.");
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_RESUME_POLLING),
                        APPLY_ROUTING_RETRY_TIMEOUT_MS);
                return;
            }

            try {
                watchDog.start();
                // Compute new polling parameters
                NfcDiscoveryParameters newParams = computeDiscoveryParameters(mScreenState);
                if (force || !newParams.equals(mCurrentDiscoveryParameters)) {
                    if (newParams.shouldEnableDiscovery()) {
                        boolean shouldRestart = mCurrentDiscoveryParameters.shouldEnableDiscovery();
                        mDeviceHost.enableDiscovery(newParams, shouldRestart);
                    } else {
                        mDeviceHost.disableDiscovery();
                    }
                    mCurrentDiscoveryParameters = newParams;
                } else {
                    Log.d(TAG, "Discovery configuration equal, not updating.");
                }
            } finally {
                watchDog.cancel();
            }
        }
    }

    private NfcDiscoveryParameters computeDiscoveryParameters(int screenState) {
        // Recompute discovery parameters based on screen state
        NfcDiscoveryParameters.Builder paramsBuilder = NfcDiscoveryParameters.newBuilder();
        // Polling
        if (screenState >= NFC_POLLING_MODE) {
            // Check if reader-mode is enabled
            if (mReaderModeParams != null) {
                int techMask = 0;
                if ((mReaderModeParams.flags & NfcAdapter.FLAG_READER_NFC_A) != 0)
                    techMask |= NFC_POLL_A;
                if ((mReaderModeParams.flags & NfcAdapter.FLAG_READER_NFC_B) != 0)
                    techMask |= NFC_POLL_B;
                if ((mReaderModeParams.flags & NfcAdapter.FLAG_READER_NFC_F) != 0)
                    techMask |= NFC_POLL_F;
                if ((mReaderModeParams.flags & NfcAdapter.FLAG_READER_NFC_V) != 0)
                    techMask |= NFC_POLL_V;
                if ((mReaderModeParams.flags & NfcAdapter.FLAG_READER_NFC_BARCODE) != 0)
                    techMask |= NFC_POLL_KOVIO;

                paramsBuilder.setTechMask(techMask);
                paramsBuilder.setEnableReaderMode(true);
            } else {
                paramsBuilder.setTechMask(NfcDiscoveryParameters.NFC_POLL_DEFAULT);
                paramsBuilder.setEnableP2p(mIsBeamCapable);
            }
        } else if (screenState == ScreenStateHelper.SCREEN_STATE_ON_LOCKED && mInProvisionMode) {
            paramsBuilder.setTechMask(NfcDiscoveryParameters.NFC_POLL_DEFAULT);
            // enable P2P for MFM/EDU/Corp provisioning
            paramsBuilder.setEnableP2p(mIsBeamCapable);
        } else if (screenState == ScreenStateHelper.SCREEN_STATE_ON_LOCKED &&
            mNfcUnlockManager.isLockscreenPollingEnabled()) {
            int techMask = 0;
            if (mNfcUnlockManager.isLockscreenPollingEnabled())
                techMask |= mNfcUnlockManager.getLockscreenPollMask();
            paramsBuilder.setTechMask(techMask);
            paramsBuilder.setEnableLowPowerDiscovery(false);
            paramsBuilder.setEnableP2p(false);
        }

        if (mIsHceCapable && mScreenState >= ScreenStateHelper.SCREEN_STATE_ON_LOCKED && mReaderModeParams == null) {
            // Host routing is always enabled at lock screen or later, provided we aren't in reader mode
            paramsBuilder.setEnableHostRouting(true);
        }

        return paramsBuilder.build();
    }

    private void computeAndSetRoutingParameters()
    {
        int protoRoute = mNxpPrefs.getInt("PREF_MIFARE_DESFIRE_PROTO_ROUTE_ID", GetDefaultMifareDesfireRouteEntry());
        int defaultRoute=mNxpPrefs.getInt("PREF_SET_DEFAULT_ROUTE_ID", GetDefaultRouteEntry());
        int techRoute=mNxpPrefs.getInt("PREF_MIFARE_CLT_ROUTE_ID", GetDefaultMifateCLTRouteEntry());
        int techfRoute=mNxpPrefs.getInt("PREF_FELICA_CLT_ROUTE_ID", GetDefaultFelicaCLTRouteEntry());
        int TechSeId,TechFSeId;
        int TechRoute = 0x00;
        if (DBG) Log.d(TAG, "Set Routing Entry");
        /* Routing for Protocol */
        if (getNciVersion() == NCI_VERSION_1_0) {
          mDeviceHost.setRoutingEntry(
              PROTOCOL_ENTRY, NFC_LISTEN_PROTO_ISO7816, ((defaultRoute >> ROUTE_LOC_MASK) & 0x07), defaultRoute & 0x3F);
          mDeviceHost.setRoutingEntry(PROTOCOL_ENTRY, NFC_LISTEN_PROTO_T3T, 0x00, 0x01);
        }
        mDeviceHost.setRoutingEntry(PROTOCOL_ENTRY, NFC_LISTEN_PROTO_ISO_DEP, ((protoRoute >> ROUTE_LOC_MASK) & 0x07), protoRoute & 0x3F);
        mDeviceHost.setRoutingEntry(PROTOCOL_ENTRY, NFC_LISTEN_PROTO_NFC_DEP, 0x00, 0x01);

        /* Routing for Technology */
        TechSeId = (techRoute >> ROUTE_LOC_MASK);
        TechFSeId = (techfRoute >> ROUTE_LOC_MASK);
        /* Technology types are masked internally depending on the capability of SE */
        if(techRoute == techfRoute)
        {
           TechRoute = 0x07;
           mDeviceHost.setRoutingEntry(TECH_ENTRY,TechRoute, TechSeId, techRoute & 0x3F);
        }
        else {
          TechRoute = 0x03;
          mDeviceHost.setRoutingEntry(TECH_ENTRY,TechRoute, TechSeId, techRoute & 0x3F);
          TechRoute = 0x04;
          Log.d(TAG, "Set Routing Entry" + TechRoute +  "" + TechFSeId + "" + techfRoute);
          mDeviceHost.setRoutingEntry(TECH_ENTRY,TechRoute, TechFSeId, techfRoute & 0x3F);
        }
    }
    public void computeRoutingParameters() {
        Log.d(TAG, "computeRoutingParameters >>>");
        mHandler.sendEmptyMessage(MSG_COMPUTE_ROUTING_PARAMS);
    }
    private boolean isTagPresent() {
        for (Object object : mObjectMap.values()) {
            if (object instanceof TagEndpoint) {
                return ((TagEndpoint) object).isPresent();
            }
        }
        return false;
    }

    private void StopPresenceChecking() {
        Object[] objectValues = mObjectMap.values().toArray();
        for (Object object : objectValues) {
            if (object instanceof TagEndpoint) {
                TagEndpoint tag = (TagEndpoint)object;
                ((TagEndpoint) object).stopPresenceChecking();
            }
        }
    }

    /**
     * Disconnect any target if present
     */
    void maybeDisconnectTarget() {
        if (!isNfcEnabledOrShuttingDown()) {
            return;
        }
        Object[] objectsToDisconnect;
        synchronized (this) {
            Object[] objectValues = mObjectMap.values().toArray();
            // Copy the array before we clear mObjectMap,
            // just in case the HashMap values are backed by the same array
            objectsToDisconnect = Arrays.copyOf(objectValues, objectValues.length);
            mObjectMap.clear();
        }
        for (Object o : objectsToDisconnect) {
            if (DBG) Log.d(TAG, "disconnecting " + o.getClass().getName());
            if (o instanceof TagEndpoint) {
                // Disconnect from tags
                TagEndpoint tag = (TagEndpoint) o;
                tag.disconnect();
            } else if (o instanceof NfcDepEndpoint) {
                // Disconnect from P2P devices
                NfcDepEndpoint device = (NfcDepEndpoint) o;
                if (device.getMode() == NfcDepEndpoint.MODE_P2P_TARGET) {
                    // Remote peer is target, request disconnection
                    device.disconnect();
                } else {
                    // Remote peer is initiator, we cannot disconnect
                    // Just wait for field removal
                }
            }
        }
    }

    Object findObject(int key) {
        synchronized (this) {
            Object device = mObjectMap.get(key);
            if (device == null) {
                Log.w(TAG, "Handle not found");
            }
            return device;
        }
    }

    Object findAndRemoveObject(int handle) {
        synchronized (this) {
            Object device = mObjectMap.get(handle);
            if (device == null) {
                Log.w(TAG, "Handle not found");
            } else {
                mObjectMap.remove(handle);
            }
            return device;
        }
    }

    void registerTagObject(TagEndpoint tag) {
        synchronized (this) {
            mObjectMap.put(tag.getHandle(), tag);
        }
    }

    void unregisterObject(int handle) {
        synchronized (this) {
            mObjectMap.remove(handle);
        }
    }

    /**
     * For use by code in this process
     */
    public LlcpSocket createLlcpSocket(int sap, int miu, int rw, int linearBufferLength)
            throws LlcpException {
        return mDeviceHost.createLlcpSocket(sap, miu, rw, linearBufferLength);
    }

    /**
     * For use by code in this process
     */
    public LlcpConnectionlessSocket createLlcpConnectionLessSocket(int sap, String sn)
            throws LlcpException {
        return mDeviceHost.createLlcpConnectionlessSocket(sap, sn);
    }

    /**
     * For use by code in this process
     */
    public LlcpServerSocket createLlcpServerSocket(int sap, String sn, int miu, int rw,
            int linearBufferLength) throws LlcpException {
        return mDeviceHost.createLlcpServerSocket(sap, sn, miu, rw, linearBufferLength);
    }

    public void sendMockNdefTag(NdefMessage msg) {
        sendMessage(MSG_MOCK_NDEF, msg);
    }

    public void notifyRoutingTableFull()
    {
        mToastHandler.showToast("Last installed NFC Service is not enabled due to limited resources. To enable this service, " +
                "please disable other servives in Settings Menu", 20);
        Log.d(TAG, "notify aid routing table full to the user here");

        mNxpPrefsEditor = mNxpPrefs.edit();
        mNxpPrefsEditor.putInt("PREF_SET_AID_ROUTING_TABLE_FULL",0x01);
        mNxpPrefsEditor.commit();
        //broadcast Aid Routing Table Full intent to the user
        Intent aidTableFull = new Intent();
        aidTableFull.setAction(NfcConstants.ACTION_ROUTING_TABLE_FULL);
        if (DBG) {
            Log.d(TAG, "notify aid routing table full to the user");
        }
        mContext.sendBroadcastAsUser(aidTableFull, UserHandle.CURRENT);
    }

    public void routeAids(String aid, int route, int aidInfo, int power) {
        Message msg = mHandler.obtainMessage();
        msg.what = MSG_ROUTE_AID;
        msg.arg1 = route;
        msg.obj = aid;
        msg.arg2 = aidInfo;
        Bundle aidbundle = new Bundle();
        aidbundle.putInt("power",power);
        msg.setData(aidbundle);
        mHandler.sendMessage(msg);
    }

    public int getAidRoutingTableSize ()
    {
        //return 18;
        return mDeviceHost.getAidTableSize();
    }

    /**
     * set default  Aid route entry in case application does not configure this route entry
     */
    public void setDefaultAidRouteLoc( int routeLoc)
    {
        mNxpPrefsEditor = mNxpPrefs.edit();
        Log.d(TAG, "writing to preferences setDefaultAidRouteLoc  :" + routeLoc);

        int defaultAidRoute = ((mDeviceHost.getDefaultAidPowerState() & 0x3F) | (routeLoc << ROUTE_LOC_MASK));

        mNxpPrefsEditor.putInt("PREF_SET_DEFAULT_ROUTE_ID", defaultAidRoute);
        mNxpPrefsEditor.commit();
        int defaultRoute=mNxpPrefs.getInt("PREF_SET_DEFAULT_ROUTE_ID",0xFF);
        Log.d(TAG, "reading preferences from user  :" + defaultRoute);
    }

    public void unrouteAids(String aid) {
        sendMessage(MSG_UNROUTE_AID, aid);
    }

    public int getNciVersion() {
        return mDeviceHost.getNciVersion();
    }

    private byte[] getT3tIdentifierBytes(String systemCode, String nfcId2, String t3tPmm) {
        ByteBuffer buffer = ByteBuffer.allocate(2 + 8 + 8); /* systemcode + nfcid2 + t3tpmm */
        buffer.put(hexStringToBytes(systemCode));
        buffer.put(hexStringToBytes(nfcId2));
        buffer.put(hexStringToBytes(t3tPmm));
        byte[] t3tIdBytes = new byte[buffer.position()];
        buffer.position(0);
        buffer.get(t3tIdBytes);

        return t3tIdBytes;
    }

    public void registerT3tIdentifier(String systemCode, String nfcId2, String t3tPmm) {
        Log.d(TAG, "request to register LF_T3T_IDENTIFIER");

        byte[] t3tIdentifier = getT3tIdentifierBytes(systemCode, nfcId2, t3tPmm);
        sendMessage(MSG_REGISTER_T3T_IDENTIFIER, t3tIdentifier);
    }

    public void deregisterT3tIdentifier(String systemCode, String nfcId2, String t3tPmm) {
        Log.d(TAG, "request to deregister LF_T3T_IDENTIFIER");

        byte[] t3tIdentifier = getT3tIdentifierBytes(systemCode, nfcId2, t3tPmm);
        sendMessage(MSG_DEREGISTER_T3T_IDENTIFIER, t3tIdentifier);
    }

    public void clearT3tIdentifiersCache() {
        Log.d(TAG, "clear T3t Identifiers Cache");
        mDeviceHost.clearT3tIdentifiersCache();
    }

    public int getLfT3tMax() {
        return mDeviceHost.getLfT3tMax();
    }

    public void commitRouting() {
        Log.d(TAG, "commitRouting >>>");
        mHandler.sendEmptyMessage(MSG_COMMIT_ROUTING);
    }
    public void initWiredSe() {
        Log.d(TAG, "Init wired Se");
        mHandler.sendEmptyMessage(MSG_INIT_WIREDSE);
    }
    public void deInitWiredSe() {
        Log.d(TAG, "DeInit wired Se");
        mHandler.sendEmptyMessage(MSG_DEINIT_WIREDSE);
    }
    /**
     * get default Aid route entry in case application does not configure this route entry
     */
    public int GetDefaultRouteLoc()
    {
        int defaultRouteLoc = mNxpPrefs.getInt("PREF_SET_DEFAULT_ROUTE_ID", GetDefaultRouteEntry()) >> ROUTE_LOC_MASK;
        Log.d(TAG, "GetDefaultRouteLoc  :" + defaultRouteLoc);
        return defaultRouteLoc ;
    }
    /**
     * get default MifareDesfireRoute route entry in case application does not configure this route entry
     */
    public int GetDefaultMifareDesfireRouteEntry()
    {
        int routeLoc = mDeviceHost.getDefaultDesfireRoute();
        int defaultMifareDesfireRoute = ((mDeviceHost.getDefaultDesfirePowerState() & 0x3F) | (routeLoc << ROUTE_LOC_MASK));
        if(routeLoc == 0x00)
        {
            /*
            bit pos 1 = Power Off
            bit pos 2 = Battery Off
            bit pos 4 = Screen Off
            Set these bits to 0 because in case routeLoc = HOST it can not work on POWER_OFF, BATTERY_OFF and SCREEN_OFF*/
            defaultMifareDesfireRoute &= 0xF9;
        }
        if (DBG) Log.d(TAG, "defaultMifareDesfireRoute : " + defaultMifareDesfireRoute);
        return defaultMifareDesfireRoute;
    }
    /**
     * set default Aid route entry in case application does not configure this route entry
     */

    public int GetDefaultRouteEntry()
    {
        int routeLoc = mDeviceHost.getDefaultAidRoute();
        int defaultAidRoute = ((mDeviceHost.getDefaultAidPowerState() & 0x3F) | (routeLoc << ROUTE_LOC_MASK));
        if(routeLoc == 0x00)
        {
            /*
            bit pos 1 = Power Off
            bit pos 2 = Battery Off
            bit pos 4 = Screen Off
            Set these bits to 0 because in case routeLoc = HOST it can not work on POWER_OFF, BATTERY_OFF and SCREEN_OFF*/
            defaultAidRoute &= 0xF9;
        }
        if (DBG) Log.d(TAG, "defaultAidRoute : " + defaultAidRoute);
        return defaultAidRoute;
    }

    /**
     * get default MifateCLT route entry in case application does not configure this route entry
     */
    public int GetDefaultMifateCLTRouteEntry()
    {
        int routeLoc = mDeviceHost.getDefaultMifareCLTRoute();
        int defaultMifateCLTRoute = ((mDeviceHost.getDefaultMifareCLTPowerState() & 0x3F) | (mDeviceHost.getDefaultMifareCLTRoute() << ROUTE_LOC_MASK)) ;
        if (DBG) Log.d(TAG, "defaultMifateCLTRoute : " + defaultMifateCLTRoute);
        return defaultMifateCLTRoute;
    }
    /**
     * get default FelicaCLT route entry in case application does not configure this route entry
     */
    public int GetDefaultFelicaCLTRouteEntry()
    {
        int routeLoc = mDeviceHost.getDefaultFelicaCLTRoute();
        int defaultFelicaCLTRoute = ((mDeviceHost.getDefaultFelicaCLTPowerState() & 0x3F) | (mDeviceHost.getDefaultFelicaCLTRoute() << ROUTE_LOC_MASK)) ;
        if (DBG) Log.d(TAG, "defaultFelicaCLTRoute : " + defaultFelicaCLTRoute);
        return defaultFelicaCLTRoute;
    }

    public int getGsmaPwrState()
    {
        return mDeviceHost.getGsmaPwrState();
    }
    /**
     * get default T4TNfcee power state supported
     */
    public int GetT4TNfceePowerState() {
        int powerState = mDeviceHost.getT4TNfceePowerState();
        if (DBG) Log.d(TAG, "T4TNfceePowerState : " + powerState);
        return powerState;
    }
    public int getAidRoutingTableStatus() {
        int aidTableStatus = 0x00;
        aidTableStatus = mNxpPrefs.getInt("PREF_SET_AID_ROUTING_TABLE_FULL",0x00);
        return aidTableStatus;
    }
    public boolean sendData(byte[] data) {
        return mDeviceHost.sendRawFrame(data);
    }

    public void onPreferredPaymentChanged(int reason) {
        sendMessage(MSG_PREFERRED_PAYMENT_CHANGED, reason);
    }

    void sendMessage(int what, Object obj) {
        Message msg = mHandler.obtainMessage();
        msg.what = what;
        msg.obj = obj;
        mHandler.sendMessage(msg);
    }

    public void updateLastScreenState()
    {
        Log.d(TAG, "updateLastScreenState");
        int screen_state_mask = (mNfcUnlockManager.isLockscreenPollingEnabled()) ?
                (ScreenStateHelper.SCREEN_POLLING_TAG_MASK | mScreenState) : mScreenState;
        mDeviceHost.doSetScreenState(screen_state_mask);
    }

    public boolean isNfcExtnsPresent() {
       return (mNfcExtnsObj != null);
    }

    final class NfcServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ROUTE_AID: {
                    int route   = msg.arg1;
                    int aidInfo = msg.arg2;
                    int power   = 0x00;
                    Bundle dataBundle = msg.getData();
                    if (dataBundle != null)
                        power = dataBundle.getInt("power");
                    String aid  = (String) msg.obj;
                    mDeviceHost.routeAid(hexStringToBytes(aid), route, aidInfo,power);
                    // Restart polling config
                    break;
                }
                case MSG_UNROUTE_AID: {
                    String aid = (String) msg.obj;
                    mDeviceHost.unrouteAid(hexStringToBytes(aid));
                    break;
                }
                case MSG_REGISTER_T3T_IDENTIFIER: {
                    Log.d(TAG, "message to register LF_T3T_IDENTIFIER");
                    mDeviceHost.disableDiscovery();

                    byte[] t3tIdentifier = (byte[]) msg.obj;
                    mDeviceHost.registerT3tIdentifier(t3tIdentifier);

                    NfcDiscoveryParameters params = computeDiscoveryParameters(mScreenState);
                    boolean shouldRestart = mCurrentDiscoveryParameters.shouldEnableDiscovery();
                    mDeviceHost.enableDiscovery(params, shouldRestart);
                    break;
                }
                case MSG_DEREGISTER_T3T_IDENTIFIER: {
                    Log.d(TAG, "message to deregister LF_T3T_IDENTIFIER");
                    mDeviceHost.disableDiscovery();

                    byte[] t3tIdentifier = (byte[]) msg.obj;
                    mDeviceHost.deregisterT3tIdentifier(t3tIdentifier);

                    NfcDiscoveryParameters params = computeDiscoveryParameters(mScreenState);
                    boolean shouldRestart = mCurrentDiscoveryParameters.shouldEnableDiscovery();
                    mDeviceHost.enableDiscovery(params, shouldRestart);
                    break;
                }
                case MSG_INVOKE_BEAM: {
                    mP2pLinkManager.onManualBeamInvoke((BeamShareData)msg.obj);
                    break;
                }
                case MSG_COMMIT_ROUTING: {
                    Log.d(TAG, "commitRouting >>>");
                    int defaultRoute=mNxpPrefs.getInt("PREF_SET_DEFAULT_ROUTE_ID", GetDefaultRouteEntry());
                    mDeviceHost.setEmptyAidRoute(defaultRoute >> ROUTE_LOC_MASK);
                    mDeviceHost.commitRouting();
                    break;
                }
                case MSG_RESET_AND_UPDATE_ROUTING_PARAMS: {
                  mDeviceHost.clearRoutingEntry(TECH_ENTRY);
                  mDeviceHost.clearRoutingEntry(PROTOCOL_ENTRY);
                }
                /*fallThrough to compute routing params*/
                case MSG_COMPUTE_ROUTING_PARAMS:
                    Log.d(TAG, "computeRoutingParameters >>>");
                    synchronized (NfcService.this) {
                    computeAndSetRoutingParameters();
                    }
                    break;

                case MSG_MOCK_NDEF: {
                    NdefMessage ndefMsg = (NdefMessage) msg.obj;
                    Bundle extras = new Bundle();
                    extras.putParcelable(Ndef.EXTRA_NDEF_MSG, ndefMsg);
                    extras.putInt(Ndef.EXTRA_NDEF_MAXLENGTH, 0);
                    extras.putInt(Ndef.EXTRA_NDEF_CARDSTATE, Ndef.NDEF_MODE_READ_ONLY);
                    extras.putInt(Ndef.EXTRA_NDEF_TYPE, Ndef.TYPE_OTHER);
                    Tag tag = Tag.createMockTag(new byte[]{0x00},
                            new int[]{TagTechnology.NDEF},
                            new Bundle[]{extras});
                    Log.d(TAG, "mock NDEF tag, starting corresponding activity");
                    Log.d(TAG, tag.toString());
                    int dispatchStatus = mNfcDispatcher.dispatchTag(tag);
                    if (dispatchStatus == NfcDispatcher.DISPATCH_SUCCESS) {
                        playSound(SOUND_END);
                    } else if (dispatchStatus == NfcDispatcher.DISPATCH_FAIL) {
                        playSound(SOUND_ERROR);
                    }
                    break;
                }

                case MSG_NDEF_TAG:
                    if (DBG) Log.d(TAG, "Tag detected, notifying applications");
                    if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                        mPowerManager.userActivity(SystemClock.uptimeMillis(),
                                PowerManager.USER_ACTIVITY_EVENT_OTHER, 0);
                    }
                    mNumTagsDetected.incrementAndGet();
                    TagEndpoint tag = (TagEndpoint) msg.obj;
                    byte[] debounceTagUid;
                    int debounceTagMs;
                    ITagRemovedCallback debounceTagRemovedCallback;
                    synchronized (NfcService.this) {
                        debounceTagUid = mDebounceTagUid;
                        debounceTagMs = mDebounceTagDebounceMs;
                        debounceTagRemovedCallback = mDebounceTagRemovedCallback;
                    }
                    ReaderModeParams readerParams = null;
                    int presenceCheckDelay = DEFAULT_PRESENCE_CHECK_DELAY;
                    DeviceHost.TagDisconnectedCallback callback =
                            new DeviceHost.TagDisconnectedCallback() {
                                @Override
                                public void onTagDisconnected(long handle) {
                                    applyRouting(false);
                                }
                            };
                    synchronized (NfcService.this) {
                        readerParams = mReaderModeParams;
                    }
                    if (readerParams != null) {
                        presenceCheckDelay = readerParams.presenceCheckDelay;
                        if ((readerParams.flags & NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK) != 0) {
                            if (DBG) Log.d(TAG, "Skipping NDEF detection in reader mode");
                            tag.startPresenceChecking(presenceCheckDelay, callback);
                            dispatchTagEndpoint(tag, readerParams);
                            break;
                        }
                    }
                    if (tag.getConnectedTechnology() == TagTechnology.NFC_BARCODE) {
                        // When these tags start containing NDEF, they will require
                        // the stack to deal with them in a different way, since
                        // they are activated only really shortly.
                        // For now, don't consider NDEF on these.
                        if (DBG) Log.d(TAG, "Skipping NDEF detection for NFC Barcode");
                        tag.startPresenceChecking(presenceCheckDelay, callback);
                        dispatchTagEndpoint(tag, readerParams);
                        break;
                    }
                    NdefMessage ndefMsg = tag.findAndReadNdef();
                    if (ndefMsg == null) {
                        // First try to see if this was a bad tag read
                        if (!tag.reconnect()) {
                            tag.disconnect();
                            if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                                if (!sToast_debounce) {
                                    Toast.makeText(mContext, R.string.tag_read_error,
                                                   Toast.LENGTH_SHORT).show();
                                    sToast_debounce = true;
                                    mHandler.sendEmptyMessageDelayed(MSG_TOAST_DEBOUNCE_EVENT,
                                                                     sToast_debounce_time_ms);
                                }
                            }
                            break;
                        }
                    }

                    if (debounceTagUid != null) {
                        // If we're debouncing and the UID or the NDEF message of the tag match,
                        // don't dispatch but drop it.
                        if (Arrays.equals(debounceTagUid, tag.getUid()) ||
                                (ndefMsg != null && ndefMsg.equals(mLastReadNdefMessage))) {
                            mHandler.removeMessages(MSG_TAG_DEBOUNCE);
                            mHandler.sendEmptyMessageDelayed(MSG_TAG_DEBOUNCE, debounceTagMs);
                            tag.disconnect();
                            return;
                        } else {
                            synchronized (NfcService.this) {
                                mDebounceTagUid = null;
                                mDebounceTagRemovedCallback = null;
                                mDebounceTagNativeHandle = INVALID_NATIVE_HANDLE;
                            }
                            if (debounceTagRemovedCallback != null) {
                                try {
                                    debounceTagRemovedCallback.onTagRemoved();
                                } catch (RemoteException e) {
                                    // Ignore
                                }
                            }
                        }
                    }

                    mLastReadNdefMessage = ndefMsg;

                    tag.startPresenceChecking(presenceCheckDelay, callback);
                    dispatchTagEndpoint(tag, readerParams);
                    break;
                case MSG_LLCP_LINK_ACTIVATION:
                    mPowerManager.userActivity(SystemClock.uptimeMillis(),
                            PowerManager.USER_ACTIVITY_EVENT_OTHER, 0);
                    if (mIsDebugBuild) {
                        Intent actIntent = new Intent(ACTION_LLCP_UP);
                        mContext.sendBroadcast(actIntent);
                    }
                    llcpActivated((NfcDepEndpoint) msg.obj);
                    break;

                case MSG_LLCP_LINK_DEACTIVATED:
                    if (mIsDebugBuild) {
                        Intent deactIntent = new Intent(ACTION_LLCP_DOWN);
                        mContext.sendBroadcast(deactIntent);
                    }
                    NfcDepEndpoint device = (NfcDepEndpoint) msg.obj;
                    boolean needsDisconnect = false;

                    Log.d(TAG, "LLCP Link Deactivated message. Restart polling loop.");
                    synchronized (NfcService.this) {
                        /* Check if the device has been already unregistered */
                        if (mObjectMap.remove(device.getHandle()) != null) {
                            /* Disconnect if we are initiator */
                            if (device.getMode() == NfcDepEndpoint.MODE_P2P_TARGET) {
                                if (DBG) Log.d(TAG, "disconnecting from target");
                                needsDisconnect = true;
                            } else {
                                if (DBG) Log.d(TAG, "not disconnecting from initiator");
                            }
                        }
                    }
                    if (needsDisconnect) {
                        device.disconnect();  // restarts polling loop
                    }

                    mP2pLinkManager.onLlcpDeactivated();
                    break;
                case MSG_LLCP_LINK_FIRST_PACKET:
                    mP2pLinkManager.onLlcpFirstPacketReceived();
                    break;
                case MSG_RF_FIELD_ACTIVATED:
                    Intent fieldOnIntent = new Intent(ACTION_RF_FIELD_ON_DETECTED);
                    sendNfcEeAccessProtectedBroadcast(fieldOnIntent);
                    break;
                case MSG_RF_FIELD_DEACTIVATED:
                    Intent fieldOffIntent = new Intent(ACTION_RF_FIELD_OFF_DETECTED);
                    sendNfcEeAccessProtectedBroadcast(fieldOffIntent);
                    break;
                case MSG_RESUME_POLLING:
                    mNfcAdapter.resumePolling();
                    break;
                case MSG_TAG_DEBOUNCE:
                    // Didn't see the tag again, tag is gone
                    ITagRemovedCallback tagRemovedCallback;
                    synchronized (NfcService.this) {
                        mDebounceTagUid = null;
                        tagRemovedCallback = mDebounceTagRemovedCallback;
                        mDebounceTagRemovedCallback = null;
                        mDebounceTagNativeHandle = INVALID_NATIVE_HANDLE;
                    }
                    if (tagRemovedCallback != null) {
                        try {
                            tagRemovedCallback.onTagRemoved();
                        } catch (RemoteException e) {
                            // Ignore
                        }
                    }
                    break;
                case MSG_UPDATE_STATS:
                    if (mNumTagsDetected.get() > 0) {
                        MetricsLogger.count(mContext, TRON_NFC_TAG, mNumTagsDetected.get());
                        mNumTagsDetected.set(0);
                    }
                    if (mNumHceDetected.get() > 0) {
                        MetricsLogger.count(mContext, TRON_NFC_CE, mNumHceDetected.get());
                        mNumHceDetected.set(0);
                    }
                    if (mNumP2pDetected.get() > 0) {
                        MetricsLogger.count(mContext, TRON_NFC_P2P, mNumP2pDetected.get());
                        mNumP2pDetected.set(0);
                    }
                    removeMessages(MSG_UPDATE_STATS);
                    sendEmptyMessageDelayed(MSG_UPDATE_STATS, STATS_UPDATE_INTERVAL_MS);
                    break;

                case MSG_APPLY_SCREEN_STATE:
                    mScreenState = (Integer)msg.obj;
                    Log.d(TAG, "MSG_APPLY_SCREEN_STATE " + mScreenState);

                    // If NFC is turning off, we shouldn't need any changes here
                    synchronized (NfcService.this) {
                        if (mState == NfcAdapter.STATE_TURNING_OFF)
                            return;
                    }

                    if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                      applyRouting(false);
                    }
                    int screen_state_mask = (mNfcUnlockManager.isLockscreenPollingEnabled()) ?
                                (ScreenStateHelper.SCREEN_POLLING_TAG_MASK | mScreenState) : mScreenState;

                   if (mNfcUnlockManager.isLockscreenPollingEnabled())
                        applyRouting(false);

                    mDeviceHost.doSetScreenState(screen_state_mask);
                    break;
                case MSG_TRANSACTION_EVENT:
                    if (mCardEmulationManager != null) {
                        mCardEmulationManager.onOffHostAidSelected();
                    }
                    byte[][] data = (byte[][]) msg.obj;
                    sendOffHostTransactionEvent(data[0], data[1], data[2]);
                    break;

                case MSG_PREFERRED_PAYMENT_CHANGED:
                    Intent preferredPaymentChangedIntent =
                            new Intent(NfcAdapter.ACTION_PREFERRED_PAYMENT_CHANGED);
                    preferredPaymentChangedIntent.putExtra(
                            NfcAdapter.EXTRA_PREFERRED_PAYMENT_CHANGED_REASON, (int)msg.obj);
                    sendPreferredPaymentChangedEvent(preferredPaymentChangedIntent);
                    break;

                case MSG_TOAST_DEBOUNCE_EVENT:
                    sToast_debounce = false;
                    break;

                case MSG_SE_INIT:
                    Log.e(TAG, "msg se init");

                    try {
                    if (mIsHceCapable) {
                        // Generate the initial card emulation routing table
                        computeRoutingParameters();
                        commitRouting();
                    }

                    /* TODO Call WiredSe HAL to notify */

                    } catch (Exception e) {
                        Log.e(TAG, "mSecureElementclientCallback.onStateChange");
                    }

                    break;

                case MSG_INIT_WIREDSE: {
                     try {
                       mWiredSeInitMethod = mWiredSeClass.getDeclaredMethod("wiredSeInitialize");
                       mWiredSeInitMethod.invoke(mWiredSeObj);
                     } catch (NoSuchElementException | NoSuchMethodException e) {
                       Log.i(TAG, "No such Method WiredSeInitialize");
                     } catch (RuntimeException | IllegalAccessException | InvocationTargetException e) {
                       Log.e(TAG, "Error in invoking wiredSeInitialize invocation");
                     } catch (Exception e) {
                       Log.e(TAG, "caught Exception during wiredSeInitialize");
                       e.printStackTrace();
                     }
                    break;
                }
                case MSG_DEINIT_WIREDSE: {
                    try {
                      mWiredSeInitMethod = mWiredSeClass.getDeclaredMethod("wiredSeDeInitialize");
                      mWiredSeInitMethod.invoke(mWiredSeObj);
                    } catch (NoSuchElementException | NoSuchMethodException e) {
                      Log.i(TAG, "No such Method wiredSeDeInitialize");
                    } catch (RuntimeException | IllegalAccessException | InvocationTargetException e) {
                      Log.e(TAG, "Error in invoking wiredSeDeInitialize invocation");
                    } catch (Exception e) {
                      Log.e(TAG, "caught Exception during wiredSeDeInitialize");
                      e.printStackTrace();
                    }
                   break;
               }
               case MSG_WRITE_T4TNFCEE: {
                 Bundle writeBundle = (Bundle) msg.obj;
                 byte[] fileId = writeBundle.getByteArray("fileId");
                 byte[] writeData = writeBundle.getByteArray("writeData");
                 int length = writeBundle.getInt("length");
                 int status = mDeviceHost.doWriteT4tData(fileId, writeData, length);
                 mT4tNfceeReturnBundle.putInt("writeStatus", status);
                 synchronized (mT4tNfcEeObj) {
                   mT4tNfcEeObj.notify();
                 }
                 break;
               }
               case MSG_READ_T4TNFCEE: {
                 Bundle readBundle = (Bundle) msg.obj;
                 byte[] fileId = readBundle.getByteArray("fileId");
                 byte[] readData = mDeviceHost.doReadT4tData(fileId);
                 mT4tNfceeReturnBundle.putByteArray("readData", readData);
                 synchronized (mT4tNfcEeObj) {
                   mT4tNfcEeObj.notify();
                 }
                 break;
               }
               case MSG_SCR_START_SUCCESS:
               case MSG_SCR_START_FAIL:
               case MSG_SCR_RESTART:
               case MSG_SCR_ACTIVATED:
               case MSG_SCR_STOP_SUCCESS:
               case MSG_SCR_STOP_FAIL:
               case MSG_SCR_TIMEOUT:
               case MSG_SCR_REMOVE_CARD:
               case MSG_SCR_MULTIPLE_TARGET_DETECTED:
                 sendScrEvent(msg.what);
                 break;
               default:
                 Log.e(TAG, "Unknown message received");
                 break;
            }
        }

        private void sendScrEvent(int msg) {
            switch (msg) {
                case MSG_SCR_START_SUCCESS: {
                    /* Send broadcast ordered */
                    Intent scrStartSuccessIntent = new Intent();
                    if(SE_READER_TYPE == SE_READER_TYPE_MPOS) {
                        scrStartSuccessIntent.setAction(
                                NfcConstants.ACTION_NFC_MPOS_READER_MODE_START_SUCCESS);
                    } else {
                        scrStartSuccessIntent.setAction(
                                NfcConstants.ACTION_NFC_SECURE_READER_MODE_START_SUCCESS);
                    }
                    if (DBG) {
                        Log.d(TAG, "SWP READER - START SUCCESS");
                    }
                    mContext.sendBroadcast(scrStartSuccessIntent);
                    break;
                }
                case MSG_SCR_START_FAIL: {
                    /* Send broadcast ordered */
                    Intent scrStartFailIntent = new Intent();
                    if(SE_READER_TYPE == SE_READER_TYPE_MPOS) {
                        scrStartFailIntent.setAction(
                                NfcConstants.ACTION_NFC_MPOS_READER_MODE_START_FAIL);
                    } else {
                        scrStartFailIntent.setAction(
                                NfcConstants.ACTION_NFC_SECURE_READER_MODE_START_FAIL);
                    }
                    if (DBG) {
                        Log.d(TAG, "SWP READER - START_FAIL");
                    }
                    mContext.sendBroadcast(scrStartFailIntent);
                    break;
                }
                case MSG_SCR_RESTART: {
                    /* Send broadcast ordered */
                    Intent scrRestartIntent = new Intent();

                    if(SE_READER_TYPE == SE_READER_TYPE_MPOS) {
                        scrRestartIntent.setAction(
                                NfcConstants.ACTION_NFC_MPOS_READER_MODE_RESTART);
                    } else {
                        scrRestartIntent.setAction(
                                NfcConstants.ACTION_NFC_SECURE_READER_MODE_RESTART);
                    }
                    if (DBG) {
                        Log.d(TAG, "SWP READER - RESTART");
                    }
                    mContext.sendBroadcast(scrRestartIntent);
                    break;
                }
                case MSG_SCR_ACTIVATED: {
                    /* Send broadcast ordered */
                    Intent scrActivateIntent = new Intent();
                    if(SE_READER_TYPE == SE_READER_TYPE_MPOS) {
                        scrActivateIntent.setAction(
                                NfcConstants.ACTION_NFC_MPOS_READER_MODE_ACTIVATED);
                    } else {
                        scrActivateIntent.setAction(
                                NfcConstants.ACTION_NFC_SECURE_READER_MODE_TARGET_ACTIVATED);
                    }
                    if (DBG) {
                        Log.d(TAG, "SWP READER - ACTIVATED");
                    }
                    mContext.sendBroadcast(scrActivateIntent);
                    break;
                }
                case MSG_SCR_STOP_SUCCESS: {
                    /* Send broadcast ordered */
                    Intent scrStopSuccessIntent = new Intent();

                    if(SE_READER_TYPE == SE_READER_TYPE_MPOS) {
                        scrStopSuccessIntent.setAction(
                                NfcConstants.ACTION_NFC_MPOS_READER_MODE_STOP_SUCCESS);
                    } else {
                        scrStopSuccessIntent.setAction(
                                NfcConstants.ACTION_NFC_SECURE_READER_MODE_STOP_SUCCESS);
                    }
                    if (DBG) {
                        Log.d(TAG, "SWP READER - STOP_SUCCESS");
                    }
                    mContext.sendBroadcast(scrStopSuccessIntent);
                    break;
                }
                case MSG_SCR_STOP_FAIL: {
                    /* Send broadcast ordered */
                    Intent scrStopFailIntent = new Intent();
                    if(SE_READER_TYPE == SE_READER_TYPE_MPOS) {
                        scrStopFailIntent.setAction(
                                NfcConstants.ACTION_NFC_MPOS_READER_MODE_STOP_FAIL);
                    } else {
                        scrStopFailIntent.setAction(
                                NfcConstants.ACTION_NFC_SECURE_READER_MODE_STOP_FAIL);
                    }
                    if (DBG) {
                        Log.d(TAG, "SWP READER - REQUESTED_FAIL");
                    }
                    mContext.sendBroadcast(scrStopFailIntent);
                    break;
                }
                case MSG_SCR_TIMEOUT: {
                    /* Send broadcast ordered */
                    Intent scrRdrTimeoutIntent = new Intent();
                    if(SE_READER_TYPE == SE_READER_TYPE_MPOS) {
                        scrRdrTimeoutIntent.setAction(
                            NfcConstants.ACTION_NFC_MPOS_READER_MODE_TIMEOUT);
                    } else {
                        scrRdrTimeoutIntent.setAction(
                            NfcConstants.ACTION_NFC_SECURE_READER_MODE_TIMEOUT);
                    }
                    if (DBG) {
                        Log.d(TAG, "SWP READER - Timeout");
                    }
                    mContext.sendBroadcast(scrRdrTimeoutIntent);
                    break;
                }
                case MSG_SCR_REMOVE_CARD: {
                    /* Send broadcast ordered */
                    Intent scrRmCardIntent = new Intent();
                    if(SE_READER_TYPE == SE_READER_TYPE_MPOS) {
                        scrRmCardIntent.setAction(
                            NfcConstants.ACTION_NFC_MPOS_READER_MODE_REMOVE_CARD);
                    }
                    if (DBG) {
                        Log.d(TAG, "SWP READER - REMOVE_CARD");
                    }
                    mContext.sendBroadcast(scrRmCardIntent);
                    break;
                }
                case MSG_SCR_MULTIPLE_TARGET_DETECTED: {
                    /* Send broadcast ordered */
                    Intent scrMultiTargetDetectIntent = new Intent();
                    if(SE_READER_TYPE == SE_READER_TYPE_MPOS) {
                        scrMultiTargetDetectIntent.setAction(
                            NfcConstants.ACTION_NFC_MPOS_READER_MODE_MULTIPLE_TARGET_DETECTED);
                    }
                    if (DBG) {
                        Log.d(TAG, "SWP READER - MULTIPLE_TARGET_DETECTED");
                    }
                    mContext.sendBroadcast(scrMultiTargetDetectIntent);
                    break;
                }
                default: {
                    Log.e(TAG, "Unknown message received");
                    break;
                }
            }
        }

        private void sendOffHostTransactionEvent(byte[] aid, byte[] data, byte[] readerByteArray) {
            if (!isSEServiceAvailable() || mNfcEventInstalledPackages.isEmpty()) {
                return;
            }

            try {
                String reader = new String(readerByteArray, "UTF-8");
                String[] installedPackages = new String[mNfcEventInstalledPackages.size()];
                boolean[] nfcAccess = mSEService.isNFCEventAllowed(reader, aid,
                        mNfcEventInstalledPackages.toArray(installedPackages));
                if (nfcAccess == null) {
                    return;
                }
                ArrayList<String> packages = new ArrayList<String>();
                Intent intent = new Intent(NfcAdapter.ACTION_TRANSACTION_DETECTED);
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(NfcAdapter.EXTRA_AID, aid);
                intent.putExtra(NfcAdapter.EXTRA_DATA, data);
                intent.putExtra(NfcAdapter.EXTRA_SECURE_ELEMENT_NAME, reader);
                StringBuilder aidString = new StringBuilder(aid.length);
                for (byte b : aid) {
                    aidString.append(String.format("%02X", b));
                }
                String url = new String ("nfc://secure:0/" + reader + "/" + aidString.toString());
                intent.setData(Uri.parse(url));

                final BroadcastOptions options = BroadcastOptions.makeBasic();
                options.setBackgroundActivityStartsAllowed(true);
                for (int i = 0; i < nfcAccess.length; i++) {
                    if (nfcAccess[i]) {
                        intent.setPackage(mNfcEventInstalledPackages.get(i));
                        mContext.sendBroadcast(intent, null, options.toBundle());
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error in isNFCEventAllowed() " + e);
            } catch (UnsupportedEncodingException e) {
                Log.e(TAG, "Incorrect format for Secure Element name" + e);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error " + e);
            }
        }

        /* Returns the list of packages that have access to NFC Events on any SE */
        private ArrayList<String> getSEAccessAllowedPackages() {
            if (!isSEServiceAvailable() || mNfcEventInstalledPackages.isEmpty()) {
                return null;
            }
            String[] readers = null;
            try {
                readers = mSEService.getReaders();
            } catch (RemoteException e) {
                Log.e(TAG, "Error in getReaders() " + e);
                return null;
            }

            if (readers == null || readers.length == 0) {
                return null;
            }
            boolean[] nfcAccessFinal = null;
            String[] installedPackages = new String[mNfcEventInstalledPackages.size()];
            for (String reader : readers) {
                try {
                    boolean[] accessList = mSEService.isNFCEventAllowed(reader, null,
                            mNfcEventInstalledPackages.toArray(installedPackages));
                    if (accessList == null) {
                        continue;
                    }
                    if (nfcAccessFinal == null) {
                        nfcAccessFinal = accessList;
                    }
                    for (int i = 0; i < accessList.length; i++) {
                        if (accessList[i]) {
                            nfcAccessFinal[i] = true;
                        }
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Error in isNFCEventAllowed() " + e);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Error " + e);
                }
            }
            if (nfcAccessFinal == null) {
                return null;
            }
            ArrayList<String> packages = new ArrayList<String>();
            for (int i = 0; i < nfcAccessFinal.length; i++) {
                if (nfcAccessFinal[i]) {
                    packages.add(mNfcEventInstalledPackages.get(i));
                }
            }
            return packages;
        }

        private void sendNfcEeAccessProtectedBroadcast(Intent intent) {
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            // Resume app switches so the receivers can start activites without delay
            mNfcDispatcher.resumeAppSwitches();
            ArrayList<String> matchingPackages = new ArrayList<String>();
            ArrayList<String> preferredPackages = new ArrayList<String>();
            synchronized (this) {
                ArrayList<String> SEPackages = getSEAccessAllowedPackages();
                if (SEPackages!= null && !SEPackages.isEmpty()) {
                    for (String packageName : SEPackages) {
                        intent.setPackage(packageName);
                        mContext.sendBroadcast(intent);
                    }
                }
                PackageManager pm = mContext.getPackageManager();
                for (String packageName : mNfcEventInstalledPackages) {
                    try {
                        PackageInfo info = pm.getPackageInfo(packageName, 0);
                        if (SEPackages != null && SEPackages.contains(packageName)) {
                            continue;
                        }
                        if (info.applicationInfo != null &&
                                ((info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                                (info.applicationInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0)) {
                            intent.setPackage(packageName);
                            mContext.sendBroadcast(intent);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception in getPackageInfo " + e);
                    }
                }
            }
        }

        /* Returns the list of packages request for nfc preferred payment service changed and
         * have access to NFC Events on any SE */
        private ArrayList<String> getNfcPreferredPaymentChangedSEAccessAllowedPackages() {
            if (!isSEServiceAvailable() || mNfcPreferredPaymentChangedInstalledPackages.isEmpty()) {
                return null;
            }
            String[] readers = null;
            try {
                readers = mSEService.getReaders();
            } catch (RemoteException e) {
                Log.e(TAG, "Error in getReaders() " + e);
                return null;
            }

            if (readers == null || readers.length == 0) {
                return null;
            }
            boolean[] nfcAccessFinal = null;
            String[] installedPackages =
                    new String[mNfcPreferredPaymentChangedInstalledPackages.size()];
            for (String reader : readers) {
                try {
                    boolean[] accessList = mSEService.isNFCEventAllowed(reader, null,
                            mNfcPreferredPaymentChangedInstalledPackages.toArray(installedPackages)
                            );
                    if (accessList == null) {
                        continue;
                    }
                    if (nfcAccessFinal == null) {
                        nfcAccessFinal = accessList;
                    }
                    for (int i = 0; i < accessList.length; i++) {
                        if (accessList[i]) {
                            nfcAccessFinal[i] = true;
                        }
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Error in isNFCEventAllowed() " + e);
                }
            }
            if (nfcAccessFinal == null) {
                return null;
            }
            ArrayList<String> packages = new ArrayList<String>();
            for (int i = 0; i < nfcAccessFinal.length; i++) {
                if (nfcAccessFinal[i]) {
                    packages.add(mNfcPreferredPaymentChangedInstalledPackages.get(i));
                }
            }
            return packages;
        }

        private void sendPreferredPaymentChangedEvent(Intent intent) {
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            // Resume app switches so the receivers can start activities without delay
            mNfcDispatcher.resumeAppSwitches();
            synchronized (this) {
                ArrayList<String> SEPackages =
                        getNfcPreferredPaymentChangedSEAccessAllowedPackages();
                if (SEPackages!= null && !SEPackages.isEmpty()) {
                    for (String packageName : SEPackages) {
                        intent.setPackage(packageName);
                        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                        mContext.sendBroadcast(intent);
                    }
                }
                PackageManager pm = mContext.getPackageManager();
                for (String packageName : mNfcPreferredPaymentChangedInstalledPackages) {
                    try {
                        PackageInfo info = pm.getPackageInfo(packageName, 0);
                        if (SEPackages != null && SEPackages.contains(packageName)) {
                            continue;
                        }
                        if (info.applicationInfo != null &&
                                ((info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                                (info.applicationInfo.privateFlags &
                                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0)) {
                            intent.setPackage(packageName);
                            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                            mContext.sendBroadcast(intent);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception in getPackageInfo " + e);
                    }
                }
            }
        }


        private boolean llcpActivated(NfcDepEndpoint device) {
            Log.d(TAG, "LLCP Activation message");

            if (device.getMode() == NfcDepEndpoint.MODE_P2P_TARGET) {
                if (DBG) Log.d(TAG, "NativeP2pDevice.MODE_P2P_TARGET");
                if (device.connect()) {
                    /* Check LLCP compliancy */
                    if (mDeviceHost.doCheckLlcp()) {
                        /* Activate LLCP Link */
                        if (mDeviceHost.doActivateLlcp()) {
                            if (DBG) Log.d(TAG, "Initiator Activate LLCP OK");
                            synchronized (NfcService.this) {
                                // Register P2P device
                                mObjectMap.put(device.getHandle(), device);
                            }
                            mP2pLinkManager.onLlcpActivated(device.getLlcpVersion());
                            return true;
                        } else {
                            /* should not happen */
                            Log.w(TAG, "Initiator LLCP activation failed. Disconnect.");
                            device.disconnect();
                        }
                    } else {
                        if (DBG) Log.d(TAG, "Remote Target does not support LLCP. Disconnect.");
                        device.disconnect();
                    }
                } else {
                    if (DBG) Log.d(TAG, "Cannot connect remote Target. Polling loop restarted.");
                    /*
                     * The polling loop should have been restarted in failing
                     * doConnect
                     */
                }
            } else if (device.getMode() == NfcDepEndpoint.MODE_P2P_INITIATOR) {
                if (DBG) Log.d(TAG, "NativeP2pDevice.MODE_P2P_INITIATOR");
                /* Check LLCP compliancy */
                if (mDeviceHost.doCheckLlcp()) {
                    /* Activate LLCP Link */
                    if (mDeviceHost.doActivateLlcp()) {
                        if (DBG) Log.d(TAG, "Target Activate LLCP OK");
                        synchronized (NfcService.this) {
                            // Register P2P device
                            mObjectMap.put(device.getHandle(), device);
                        }
                        mP2pLinkManager.onLlcpActivated(device.getLlcpVersion());
                        return true;
                    }
                } else {
                    Log.w(TAG, "checkLlcp failed");
                }
            }

            return false;
        }

        private void dispatchTagEndpoint(TagEndpoint tagEndpoint, ReaderModeParams readerParams) {
            try {
                Tag tag = new Tag(tagEndpoint.getUid(), tagEndpoint.getTechList(),
                        tagEndpoint.getTechExtras(), tagEndpoint.getHandle(), mNfcTagService);
                registerTagObject(tagEndpoint);
                if (readerParams != null) {
                    try {
                        if ((readerParams.flags & NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS) == 0) {
                            mVibrator.vibrate(mVibrationEffect);
                            playSound(SOUND_END);
                        }
                        if (readerParams.callback != null) {
                            readerParams.callback.onTagDiscovered(tag);
                            return;
                        } else {
                            // Follow normal dispatch below
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Reader mode remote has died, falling back.", e);
                        // Intentional fall-through
                    } catch (Exception e) {
                        // Catch any other exception
                        Log.e(TAG, "App exception, not dispatching.", e);
                        return;
                    }
                }
                int dispatchResult = mNfcDispatcher.dispatchTag(tag);
                if (dispatchResult == NfcDispatcher.DISPATCH_FAIL && !mInProvisionMode) {
                    unregisterObject(tagEndpoint.getHandle());
                    if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED &&
                        mContext.getResources().getBoolean(R.bool.enable_notify_dispatch_failed)) {
                        if (!sToast_debounce) {
                            Toast.makeText(mContext, R.string.tag_dispatch_failed,
                                           Toast.LENGTH_SHORT).show();
                            sToast_debounce = true;
                            mHandler.sendEmptyMessageDelayed(MSG_TOAST_DEBOUNCE_EVENT,
                                                             sToast_debounce_time_ms);
                        }
                    }
                    playSound(SOUND_ERROR);
                } else if (dispatchResult == NfcDispatcher.DISPATCH_SUCCESS) {
                    mVibrator.vibrate(mVibrationEffect);
                    playSound(SOUND_END);
                }
            } catch (Exception e) {
                Log.e(TAG, "Tag creation exception, not dispatching.", e);
                return;
            }
        }
    }

    /* For Toast from background process*/

    public class ToastHandler
    {
        // General attributes
        private Context mContext;
        private Handler mHandler;

        public ToastHandler(Context _context)
        {
        this.mContext = _context;
        this.mHandler = new Handler();
        }

        /**
         * Runs the <code>Runnable</code> in a separate <code>Thread</code>.
         *
         * @param _runnable
         *            The <code>Runnable</code> containing the <code>Toast</code>
         */
        private void runRunnable(final Runnable _runnable)
        {
        Thread thread = new Thread()
        {
            public void run()
            {
            mHandler.post(_runnable);
            }
        };

        thread.start();
        thread.interrupt();
        thread = null;
        }

        public void showToast(final CharSequence _text, final int _duration)
        {
        final Runnable runnable = new Runnable()
        {
            @Override
            public void run()
            {
            Toast.makeText(mContext, _text, _duration).show();
            }
        };

        runRunnable(runnable);
        }
    }

    private NfcServiceHandler mHandler = new NfcServiceHandler();

    class ApplyRoutingTask extends AsyncTask<Integer, Void, Void> {
        @Override
        protected Void doInBackground(Integer... params) {
            synchronized (NfcService.this) {
                if (params == null || params.length != 1) {
                    // force apply current routing
                    applyRouting(true);
                    return null;
                }
                mScreenState = params[0].intValue();

                mRoutingWakeLock.acquire();
                try {
                    applyRouting(false);
                } finally {
                    mRoutingWakeLock.release();
                }
                return null;
            }
        }
    }

    class TagRemoveTaskTimer extends TimerTask {
        public void run()
        {
            Intent swpReaderTagRemoveIntent = new Intent();
            swpReaderTagRemoveIntent.setAction(NfcConstants.ACTION_NFC_MPOS_READER_MODE_REMOVE_CARD);
            if (DBG) {
                Log.d(TAG, "SWP READER - Tag Remove");
            }
            mContext.sendBroadcast(swpReaderTagRemoveIntent);
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_ON)
                    || action.equals(Intent.ACTION_SCREEN_OFF)
                    || action.equals(Intent.ACTION_USER_PRESENT)) {
                // Perform applyRouting() in AsyncTask to serialize blocking calls
                int screenState = mScreenStateHelper.checkScreenState();
                if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                     if (mScreenState != ScreenStateHelper.SCREEN_STATE_OFF_LOCKED) {
                        screenState = mKeyguard.isKeyguardLocked() ?
                        ScreenStateHelper.SCREEN_STATE_OFF_LOCKED : ScreenStateHelper.SCREEN_STATE_OFF_UNLOCKED;
                     }
                } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                    screenState = mKeyguard.isKeyguardLocked()
                            ? ScreenStateHelper.SCREEN_STATE_ON_LOCKED
                            : ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;
                } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                    screenState = ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;
                }
                if (nci_version != NCI_VERSION_2_0) {
                    new ApplyRoutingTask().execute(Integer.valueOf(screenState));
                }
                sendMessage(NfcService.MSG_APPLY_SCREEN_STATE, screenState);
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (mIsBeamCapable) {
                    int beamSetting =
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
                    try {
                        IPackageManager mIpm = IPackageManager.Stub.asInterface(
                            ServiceManager.getService("package"));
                        beamSetting = mIpm.getComponentEnabledSetting(new ComponentName(
                                BeamShareActivity.class.getPackageName$(),
                                BeamShareActivity.class.getName()),
                                userId);
                    } catch(RemoteException e) {
                        Log.e(TAG, "Error int getComponentEnabledSetting for BeamShareActivity");
                    }
                    synchronized (this) {
                        mUserId = userId;
                        if (beamSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                           mIsNdefPushEnabled = false;
                        } else {
                           mIsNdefPushEnabled = true;
                        }                        // Propagate the state change to all user profiles
                        UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
                        List <UserHandle> luh = um.getUserProfiles();
                        for (UserHandle uh : luh){
                            enforceBeamShareActivityPolicy(mContext, uh);
                        }
                        enforceBeamShareActivityPolicy(mContext, new UserHandle(mUserId));
                    }
                    mP2pLinkManager.onUserSwitched(getUserId());
                }
                if (mIsHceCapable) {
                    mCardEmulationManager.onUserSwitched(getUserId());
                }
                int screenState = mScreenStateHelper.checkScreenState();
                if (screenState != mScreenState) {
                    new ApplyRoutingTask().execute(Integer.valueOf(screenState));
                }
            }
        }
    };


    private final BroadcastReceiver mOwnerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_PACKAGE_REMOVED) ||
                    action.equals(Intent.ACTION_PACKAGE_ADDED) ||
                    action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE) ||
                    action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)) {
                updatePackageCache();
            } else if (action.equals(Intent.ACTION_SHUTDOWN)) {
                if (DBG) Log.d(TAG, "Device is shutting down.");
                if (isNfcEnabled()) {
                    mDeviceHost.shutdown();
                }
            }
        }
    };

    private final BroadcastReceiver mPolicyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent){
            String action = intent.getAction();
            if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
                        .equals(action) &&
                    mIsBeamCapable) {
                enforceBeamShareActivityPolicy(
                    context, new UserHandle(getSendingUserId()));
            }
        }
    };

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            synchronized (this) {
                mIsVrModeEnabled = enabled;
            }
        }
    };

    /**
     * for debugging only - no i18n
     */
    static String stateToString(int state) {
        switch (state) {
            case NfcAdapter.STATE_OFF:
                return "off";
            case NfcAdapter.STATE_TURNING_ON:
                return "turning on";
            case NfcAdapter.STATE_ON:
                return "on";
            case NfcAdapter.STATE_TURNING_OFF:
                return "turning off";
            default:
                return "<error>";
        }
    }

    private void copyNativeCrashLogsIfAny(PrintWriter pw) {
      try {
          File file = new File(mContext.getFilesDir(), NATIVE_LOG_FILE_NAME);
          if (!file.exists()) {
            return;
          }
          pw.println("---BEGIN: NATIVE CRASH LOG----");
          Scanner sc = new Scanner(file);
          while(sc.hasNextLine()) {
              String s = sc.nextLine();
              pw.println(s);
          }
          pw.println("---END: NATIVE CRASH LOG----");
          sc.close();
      } catch (IOException e) {
          Log.e(TAG, "Exception in copyNativeCrashLogsIfAny " + e);
      }
    }

    private void storeNativeCrashLogs() {
      try {
          File file = new File(mContext.getFilesDir(), NATIVE_LOG_FILE_NAME);
          if (!file.exists()) {
              file.createNewFile();
          }

          FileOutputStream fos = new FileOutputStream(file);
          mDeviceHost.dump(fos.getFD());
          fos.flush();
          fos.close();
      } catch (IOException e) {
          Log.e(TAG, "Exception in storeNativeCrashLogs " + e);
      }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump nfc from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }

        synchronized (this) {
            pw.println("mState=" + stateToString(mState));
            pw.println("mIsZeroClickRequested=" + mIsNdefPushEnabled);
            pw.println("mScreenState=" + ScreenStateHelper.screenStateToString(mScreenState));
            pw.println("mIsSecureNfcEnabled=" + mIsSecureNfcEnabled);
            pw.println(mCurrentDiscoveryParameters);
            if (mIsBeamCapable)
                mP2pLinkManager.dump(fd, pw, args);
            if (mIsHceCapable) {
                mCardEmulationManager.dump(fd, pw, args);
            }
            mNfcDispatcher.dump(fd, pw, args);
            copyNativeCrashLogsIfAny(pw);
            pw.flush();
            mDeviceHost.dump(fd);
        }
    }

    public void updateDefaultAidRoute(int routeLoc) {
      mNxpPrefsEditor = mNxpPrefs.edit();
      Log.d(TAG, "writing to preferences setDefaultAidRouteLoc  :" + routeLoc);
      if (mNxpPrefs.getInt("PREF_SET_DEFAULT_ROUTE_ID", 0xFF) == routeLoc) {
        Log.d(TAG, "DefaultRoute :" + routeLoc + " is not changed. Returning.");
        return;
      }
      int defaultAidRoute =
          ((mDeviceHost.getDefaultAidPowerState() & 0x3F) | (routeLoc << ROUTE_LOC_MASK));
      if (routeLoc == 0x00) {
        /*
        bit pos 1 = Power Off
        bit pos 2 = Battery Off
        bit pos 3 = Screen Off
        Set these bits to 0 because in case routeLoc = HOST it can not work on POWER_OFF,
        BATTERY_OFF and SCREEN_OFF*/

        defaultAidRoute &= 0x11;
      }
      mNxpPrefsEditor.putInt("PREF_SET_DEFAULT_ROUTE_ID", defaultAidRoute);
      mNxpPrefsEditor.commit();
      int defaultRoute = mNxpPrefs.getInt("PREF_SET_DEFAULT_ROUTE_ID", 0xFF);
      Log.d(TAG, "Reading updated preference  :" + defaultRoute);
      mHandler.sendEmptyMessage(MSG_RESET_AND_UPDATE_ROUTING_PARAMS);
    }
    public void addT4TNfceeAid() {
      Log.i(TAG, "Add T4T Nfcee AID");

      routeAids(T4T_NFCEE_AID, ROUTE_ID_T4T_NFCEE,
              AID_MATCHING_EXACT_ONLY,
              GetT4TNfceePowerState());
    }
}
