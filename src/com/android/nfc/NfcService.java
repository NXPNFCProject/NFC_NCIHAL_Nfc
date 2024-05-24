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
*  Copyright 2018-2024 NXP
*
******************************************************************************/
package com.android.nfc;

import android.app.ActivityManager;
import android.app.Application;
import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardLockedStateListener;
import android.app.PendingIntent;
import android.app.VrManager;
import android.app.admin.DevicePolicyManager;
import android.app.backup.BackupManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources.NotFoundException;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.nfc.AvailableNfcAntenna;
import android.nfc.Constants;
import android.nfc.NfcFrameworkInitializer;
import android.nfc.NfcServiceManager;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.ErrorCodes;
import android.nfc.FormatException;
import android.nfc.IAppCallback;
import android.nfc.INfcAdapter;
import android.nfc.INfcAdapterExtras;
import android.nfc.INfcCardEmulation;
import android.nfc.INfcControllerAlwaysOnListener;
import android.nfc.INfcVendorNciCallback;
import android.nfc.INfcDta;
import android.nfc.INfcFCardEmulation;
import android.nfc.INfcOemExtensionCallback;
import android.nfc.INfcTag;
import android.nfc.INfcUnlockHandler;
import android.nfc.ITagRemovedCallback;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.NfcAntennaInfo;
import android.nfc.Tag;
import android.nfc.TechListParcel;
import android.nfc.TransceiveResult;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HwBinder;
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
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.se.omapi.ISecureElementService;
import android.se.omapi.SeFrameworkInitializer;
import android.se.omapi.SeServiceManager;
import android.sysprop.NfcProperties;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.nfc.cardemulation.AidRoutingManager;
import com.android.nfc.cardemulation.CardEmulationManager;
import com.android.nfc.cardemulation.util.StatsdUtils;
import com.android.nfc.cardemulation.RegisteredAidCache;
import com.android.nfc.DeviceHost.DeviceHostListener;
import com.android.nfc.DeviceHost.NfcDepEndpoint;
import com.android.nfc.DeviceHost.TagEndpoint;
import com.android.nfc.dhimpl.NativeNfcManager;
import com.android.nfc.dhimpl.NativeNfcSecureElement;
import com.android.nfc.flags.FeatureFlags;
import com.android.nfc.Utils;
import com.android.nfc.handover.HandoverDataParser;
import com.nxp.nfc.INxpNfcAdapter;
import com.nxp.nfc.INxpNfcAdapterExtras;
import com.nxp.nfc.INxpWlcAdapter;
import com.nxp.nfc.INxpWlcCallBack;
import com.nxp.nfc.NfcConstants;
import com.nxp.nfc.NxpNfcAdapter;
import java.io.BufferedReader;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import java.util.concurrent.TimeUnit;
import java.util.TimerTask;
import java.util.Timer;

public class NfcService implements DeviceHostListener, ForegroundUtils.Callback {
    static final boolean DBG = NfcProperties.debug_enabled().orElse(false);
    static final String TAG = "NfcService";
    private static final int APP_INFO_FLAGS_SYSTEM_APP =
            ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;

    public static final String SERVICE_NAME = "nfc";
    private static final String SYSTEM_UI = "com.android.systemui";
    public static final String NXP_PREF = "NfcServiceNxpPrefs";
    public static final String PREF = "NfcServicePrefs";
    private static final String PREF_CUR_SELECTED_UICC_ID = "current_selected_uicc_id";
    private int SECURE_ELEMENT_UICC_SLOT_DEFAULT = 1;
    static final int UICC_CONFIGURED = 0x00;
    static final int UICC_NOT_CONFIGURED = 0x01;
    public static final String PREF_TAG_APP_LIST = "TagIntentAppPreferenceListPrefs";

    static final String PREF_NFC_ON = "nfc_on";
    static final boolean NFC_ON_DEFAULT = true;

    static final String PREF_NFC_READER_OPTION_ON = "nfc_reader_on";
    static final boolean NFC_READER_OPTION_DEFAULT = true;

    static final String PREF_SECURE_NFC_ON = "secure_nfc_on";
    static final boolean SECURE_NFC_ON_DEFAULT = false;
    static final String PREF_FIRST_BOOT = "first_boot";

    static final String PREF_ANTENNA_BLOCKED_MESSAGE_SHOWN = "antenna_blocked_message_shown";
    static final boolean ANTENNA_BLOCKED_MESSAGE_SHOWN_DEFAULT = false;

    public static final int ROUTE_LOC_MASK=8;
    public static final int TECH_TYPE_MASK=11;
    static final String NATIVE_LOG_FILE_PATH = "/data/misc/nfc/logs";
    static final String NATIVE_LOG_FILE_NAME = "native_crash_logs";
    static final int NATIVE_CRASH_FILE_SIZE = 1024 * 1024;
    static final String T4T_NFCEE_AID = "D2760000850101";
    static final int TECH_TYPE_A= 0x01;
    static final int TECH_TYPE_F= 0x04;
    static final int MSG_NDEF_TAG = 0;
    // Previously used: MSG_LLCP_LINK_ACTIVATION = 1
    // Previously used: MSG_LLCP_LINK_DEACTIVATED = 2
    static final int MSG_MOCK_NDEF = 3;
    // Previously used: MSG_LLCP_LINK_FIRST_PACKET = 4
    static final int MSG_ROUTE_AID = 5;
    static final int MSG_UNROUTE_AID = 6;
    static final int MSG_COMMIT_ROUTING = 7;
    // Previously used: MSG_INVOKE_BEAM = 8
    static final int MSG_RF_FIELD_ACTIVATED = 9;
    static final int MSG_RF_FIELD_DEACTIVATED = 10;
    static final int MSG_RESUME_POLLING = 11;
    static final int MSG_REGISTER_T3T_IDENTIFIER = 12;
    static final int MSG_DEREGISTER_T3T_IDENTIFIER = 13;
    static final int MSG_TAG_DEBOUNCE = 14;
    // Previously used: MSG_UPDATE_STATS = 15
    static final int MSG_APPLY_SCREEN_STATE = 16;
    static final int MSG_TRANSACTION_EVENT = 17;
    static final int MSG_PREFERRED_PAYMENT_CHANGED = 18;
    static final int MSG_TOAST_DEBOUNCE_EVENT = 19;
    static final int MSG_DELAY_POLLING = 20;
    static final int MSG_CLEAR_ROUTING_TABLE = 21;
    static final int MSG_UPDATE_ISODEP_PROTOCOL_ROUTE = 22;
    static final int MSG_UPDATE_TECHNOLOGY_AB_ROUTE = 23;
    static final int MSG_CARD_EMULATION = 24;
    static final int MSG_SE_INIT = 59;
    static final int MSG_CLEAR_ROUTING = 62;
    static final int MSG_INIT_WIREDSE = 63;
    static final int MSG_COMPUTE_ROUTING_PARAMS = 64;
    static final int MSG_RESET_AND_UPDATE_ROUTING_PARAMS = 65;
    static final int MSG_DEINIT_WIREDSE = 66;
    static final int MSG_READ_T4TNFCEE = 67;
    static final int MSG_WRITE_T4TNFCEE = 68;
    static final int MSG_TXLDO_OVERCORRENT_RECOVERY = 69;
    private static final int STATE_TXLDO_OVERCORRENT_ERROR = 0xE3;

    // SCR/MPOS constants
    static final int SE_READER_TYPE_INAVLID   = 0;
    static final int SE_READER_TYPE_MPOS      = 1;
    static final int MSG_SCR_START_SUCCESS            = 70;
    static final int MSG_SCR_START_FAIL               = 71;
    static final int MSG_SCR_RESTART                  = 72;
    static final int MSG_SCR_ACTIVATED                = 73;
    static final int MSG_SCR_STOP_SUCCESS             = 74;
    static final int MSG_SCR_STOP_FAIL                = 75;
    static final int MSG_SCR_TIMEOUT                  = 76;
    static final int MSG_SCR_REMOVE_CARD              = 77;
    static final int MSG_SCR_MULTIPLE_TARGET_DETECTED = 78;
    static final int MSG_LX_DATA_RECEIVED             = 79;
    static final int MSG_WLC_ENABLE                   = 80;
    static final int MSG_WLC_DISABLE                  = 81;
    static final int MSG_WLC_IS_LISTENER_DETECTED     = 82;
    public static final int MSG_SRD_EVT_TIMEOUT = 84;
    public static final int MSG_SRD_EVT_FEATURE_NOT_SUPPORT = 85;
    public static final int MSG_EFDM_EVT_TIMEOUT = 86;
    public static final int MSG_TAG_ABORT_OPERATION = 87;
    private int SE_READER_TYPE = SE_READER_TYPE_INAVLID;

    static final String MSG_ROUTE_AID_PARAM_TAG = "power";

    // Negative value for NO polling delay
    static final int NO_POLL_DELAY = -1;

    static final long MAX_POLLING_PAUSE_TIMEOUT = 40000;

    static final int MAX_TOAST_DEBOUNCE_TIME = 10000;

    static final int TASK_ENABLE = 1;
    static final int TASK_DISABLE = 2;
    static final int TASK_BOOT = 3;
    static final int TASK_ENABLE_FOR_ULPDET = 4;

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
    static final int NFC_POLL_Q = 0x100;

    // Return values from NfcEe.open() - these are 1:1 mapped
    // to the thrown EE_EXCEPTION_ exceptions in nfc-extras.
    static final int EE_ERROR_IO = -1;
    static final int EE_ERROR_ALREADY_OPEN = -2;
    static final int EE_ERROR_INIT = -3;
    static final int EE_ERROR_LISTEN_MODE = -4;
    static final int EE_ERROR_EXT_FIELD = -5;
    static final int EE_ERROR_NFC_DISABLED = -6;

    static final public int TECH_ENTRY = 4;
    static final public int PROTOCOL_ENTRY = 2;
    static final public int AID_ENTRY = 1;          // it is dummy values;

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

    // Removal Detection Wait Time Range
    static final int MIN_RF_REMOVAL_DETECTION_TIMEOUT = 0x01;
    static final int MAX_RF_REMOVAL_DETECTION_TIMEOUT = 0x13;

    static final NfcProperties.snoop_log_mode_values NFC_SNOOP_LOG_MODE =
            NfcProperties.snoop_log_mode().orElse(NfcProperties.snoop_log_mode_values.FILTERED);
    static final boolean NFC_VENDOR_DEBUG_ENABLED = NfcProperties.vendor_debug_enabled().orElse(false);

    // RF field events as defined in NFC extras
    public static final String ACTION_RF_FIELD_ON_DETECTED =
            "com.android.nfc_extras.action.RF_FIELD_ON_DETECTED";
    public static final String ACTION_RF_FIELD_OFF_DETECTED =
            "com.android.nfc_extras.action.RF_FIELD_OFF_DETECTED";
    /*SRD EVT Timeout*/
    public static final String ACTION_SRD_EVT_TIMEOUT =
            "com.nxp.nfc_extras.ACTION_SRD_EVT_TIMEOUT";
    /*SRD Feature not supported */
    public static final String ACTION_SRD_EVT_FEATURE_NOT_SUPPORT =
            "com.nxp.nfc_extras.ACTION_SRD_EVT_FEATURE_NOT_SUPPORT";

    public static final String ACTION_EXTENDED_FIELD_TIMEOUT =
            "com.android.nfc.action.ACTION_EXTENDED_FIELD_TIMEOUT";

    public static boolean sIsShortRecordLayout = false;

    public static boolean sIsNfcRestore = false;

    // Default delay used for presence checks in ETSI mode
    static final int ETSI_PRESENCE_CHECK_DELAY = 1000;
    // for use with playSound()
    public static final int SOUND_START = 0;
    public static final int SOUND_END = 1;
    public static final int SOUND_ERROR = 2;

    public static final int NCI_VERSION_2_0 = 0x20;

    public static final int NCI_VERSION_1_0 = 0x10;

    public static final String ACTION_LX_DATA_RECVD =
            "com.android.nfc.action.LX_DATA";

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

    static final int ROUTE_INVALID = 0xFF;
    static int mOverflowDefaultRoute = ROUTE_INVALID;

    public boolean mIsRouteForced = false;

    private static final VibrationAttributes HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK);

    private static final int NCI_STATUS_OK = 0x00;
    private static final int NCI_STATUS_REJECTED = 0x01;
    private static final int NCI_STATUS_MESSAGE_CORRUPTED = 0x02;
    private static final int NCI_STATUS_FAILED = 0x03;
    private static final int SEND_VENDOR_CMD_TIMEOUT_MS = 3000;
    private static final int NCI_GID_PROP = 0x0F;
    private static final int NCI_MSG_PROP_ANDROID = 0x0C;
    private static final int NCI_MSG_PROP_ANDROID_POWER_SAVING = 0x01;

    private final UserManager mUserManager;
    private final ActivityManager mActivityManager;

    private static int nci_version = NCI_VERSION_1_0;
    // NFC Execution Environment
    // fields below are protected by this
    public NativeNfcSecureElement mSecureElement;
    public boolean isWiredOpen = false;
    private final boolean mPollingDisableAllowed;
    private HashMap<Integer, ReaderModeDeathRecipient> mPollingDisableDeathRecipients =
            new HashMap<Integer, ReaderModeDeathRecipient>();
    private final ReaderModeDeathRecipient mReaderModeDeathRecipient =
            new ReaderModeDeathRecipient();
    private final SeServiceDeathRecipient mSeServiceDeathRecipient =
            new SeServiceDeathRecipient();
    private final DiscoveryTechDeathRecipient mDiscoveryTechDeathRecipient =
            new DiscoveryTechDeathRecipient();
    private final NfcUnlockManager mNfcUnlockManager;


    private final BackupManager mBackupManager;

    private final SecureRandom mCookieGenerator = new SecureRandom();

    // Tag app preference list for the target UserId.
    HashMap<Integer, HashMap<String, Boolean>> mTagAppPrefList =
            new HashMap<Integer, HashMap<String, Boolean>>();

    // cached version of installed packages requesting Android.permission.NFC_TRANSACTION_EVENTS
    // for current user and profiles. The Integer part is the userId.
    HashMap<Integer, List<String>> mNfcEventInstalledPackages =
            new HashMap<Integer, List<String>>();

    // cached version of installed packages requesting
    // Android.permission.NFC_PREFERRED_PAYMENT_INFO for current user and profiles.
    // The Integer part is the userId.
    HashMap<Integer, List<String>> mNfcPreferredPaymentChangedInstalledPackages =
            new HashMap<Integer, List<String>>();

    // fields below are used in multiple threads and protected by synchronized(this)
    final HashMap<Integer, Object> mObjectMap = new HashMap<Integer, Object>();
    HashSet<String> mSePackages = new HashSet<String>();
    int mScreenState;
    int mPreviousScreenState;
    boolean mInProvisionMode; // whether we're in setup wizard and enabled NFC provisioning
    boolean mIsSecureNfcEnabled;
    boolean mSkipNdefRead;
    NfcDiscoveryParameters mCurrentDiscoveryParameters =
            NfcDiscoveryParameters.getNfcOffParameters();

    ReaderModeParams mReaderModeParams;
    DiscoveryTechParams mDiscoveryTechParams;

    private int mUserId;
    boolean mPollingPaused;
    boolean mNfcStateCheck = true;
    // True if nfc notification message already shown
    boolean mAntennaBlockedMessageShown;
    private static int mDispatchFailedCount;
    private static int mDispatchFailedMax;

    static final int INVALID_NATIVE_HANDLE = -1;
    byte mDebounceTagUid[];
    int mDebounceTagDebounceMs;
    int mDebounceTagNativeHandle = INVALID_NATIVE_HANDLE;
    ITagRemovedCallback mDebounceTagRemovedCallback;

    // Only accessed on one thread so doesn't need locking
    NdefMessage mLastReadNdefMessage;

    ToastHandler mToastHandler;
    // mState is protected by this, however it is only modified in onCreate()
    // and the default AsyncTask thread so it is read unprotected from that
    // thread
    int mState;  // one of NfcAdapter.STATE_ON, STATE_TURNING_ON, etc
    // mAlwaysOnState is protected by this, however it is only modified in onCreate()
    // and the default AsyncTask thread so it is read unprotected from that thread
    int mAlwaysOnState;  // one of NfcAdapter.STATE_ON, STATE_TURNING_ON, etc
    // fields below are final after onCreate()
    private boolean mIsPowerSavingModeEnabled = false;
    boolean mIsReaderOptionEnabled = true;
    boolean mReaderOptionCapable;
    Context mContext;
    private DeviceHost mDeviceHost;
    private SharedPreferences mPrefs;
    private SharedPreferences.Editor mPrefsEditor;
    private SharedPreferences mTagAppPrefListPrefs;

    private PowerManager.WakeLock mRoutingWakeLock;
    private PowerManager.WakeLock mRequireUnlockWakeLock;
    private PowerManager.WakeLock mEeWakeLock;
    private SharedPreferences.Editor mNxpPrefsEditor;
    private SharedPreferences mNxpPrefs;
    int mStartSound;
    int mEndSound;
    int mErrorSound;
    SoundPool mSoundPool; // playback synchronized on this
    TagService mNfcTagService;
    boolean mIsSecureElementOpened = false;
    boolean mSEClientAccessState = false;
    NfcAdapterService mNfcAdapter;
    NfcDtaService mNfcDtaService;
    RoutingTableParser mRoutingTableParser;
    NxpNfcAdapterExtrasService mNxpExtrasService;
    NxpNfcAdapterService mNxpNfcAdapter;
    NxpWlcAdapterService mNxpWlcAdapter;
    boolean mIsDebugBuild;
    boolean mIsHceCapable;
    boolean mIsHceFCapable;
    boolean mIsSecureNfcCapable;
    boolean mIsRequestUnlockShowed;
    boolean mIsRecovering;
    boolean mIsNfcUserRestricted;
    boolean mIsWatchType;

    // polling delay control variables
    private final int mPollDelayTime;
    private final int mPollDelayTimeLong;
    private final int mAppInActivityDetectionTime;
    private final int mTagRemovalDetectionWaitTime;
    private Timer mAppInActivityDetectionTimer;
    private final int mPollDelayCountMax;
    private int mPollDelayCount;
    private boolean mPollDelayed;

    boolean mNotifyDispatchFailed;
    boolean mNotifyReadFailed;

    // for recording the latest Tag object cookie
    long mCookieUpToDate = -1;

    private DeviceConfigFacade mDeviceConfigFacade;
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
    private ISecureElementService mSEService;
    private VrManager mVrManager;

    private ScreenStateHelper mScreenStateHelper;
    private ForegroundUtils mForegroundUtils;

    private static NfcService sService;
    private static boolean sToast_debounce = false;
    private static int sToast_debounce_time_ms = 3000;
    public  static boolean sIsDtaMode = false;

    boolean mIsVrModeEnabled;

    private final boolean mIsTagAppPrefSupported;

    private final boolean mIsAlwaysOnSupported;
    private final Set<INfcControllerAlwaysOnListener> mAlwaysOnListeners =
        Collections.synchronizedSet(new HashSet<>());
    /* WiredSe attributes */
    Class mWiredSeClass;
    Method mWiredSeInitMethod, mWiredSeDeInitMethod;
    Object mWiredSeObj;
    Class mNfcExtnsClass;
    Object  mNfcExtnsObj;

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
    private WlcServiceProxy mWlc = null;
    private int SELFTEST_RESTORE_RFTXCFG = 0x00;
    private int SELFTEST_SET_RFTXCFG = 0x01;
    private int SELFTEST_PRBS = 0x06;
    private int SELFTEST_SWP = 0x07;

    private final FeatureFlags mFeatureFlags = new com.android.nfc.flags.FeatureFlagsImpl();

    private final StatsdUtils mStatsdUtils;

    private  INfcVendorNciCallback mNfcVendorNciCallBack = null;
    private  INfcOemExtensionCallback mNfcOemExtensionCallback = null;

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
            mCardEmulationManager.onHostCardEmulationDeactivated(technology);
        }
    }

    @Override
    public void onLxDebugConfigData(int len, byte[] data) {
        Bundle writeBundle = new Bundle();
        writeBundle.putByteArray("LxDbgData", data);
        writeBundle.putInt("length", len);
        sendMessage(NfcService.MSG_LX_DATA_RECEIVED, writeBundle);
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
    public void onEeUpdated() {
        sendMessage(NfcService.MSG_SE_INIT, null);
    }

    @Override
    public void notifyTagAbort() {
        sendMessage(MSG_TAG_ABORT_OPERATION , null);
    }

    public void onNotifyEfdmEvt(int efdmEvt) {
      Log.e(TAG, " Broadcasting EFDM evt efdmEvt" + efdmEvt);
      int EFDM_TIMEOUT_EVT = 242;
      if(efdmEvt == EFDM_TIMEOUT_EVT) {
        sendMessage(MSG_EFDM_EVT_TIMEOUT, null);
      }
    }

    public void onNotifySrdEvt(int event) {
      Log.e(TAG, " Broadcasting SRD evt" + event);
      int NFA_SRD_EVT_TIMEOUT = 33;
      int NFA_SRD_EVT_FEATURE_NOT_SUPPORT = 34;
        if(event == NFA_SRD_EVT_TIMEOUT) {
          sendMessage(MSG_SRD_EVT_TIMEOUT , null);
        } else if(event == NFA_SRD_EVT_FEATURE_NOT_SUPPORT) {
          sendMessage(MSG_SRD_EVT_FEATURE_NOT_SUPPORT , null);
        }
    }

    @Override
    public void onPollingLoopDetected(Bundle pollingFrame) {
        if (mCardEmulationManager != null) {
            mCardEmulationManager.onPollingLoopDetected(pollingFrame);
        }
    }

    @Override
    public void onNfcTransactionEvent(byte[] aid, byte[] data, String seName) {
        byte[][] dataObj = {aid, data, seName.getBytes()};
        sendMessage(NfcService.MSG_TRANSACTION_EVENT, dataObj);
    }

    @Override
    public void onScrNotifyEvents(int event)
    {
      sendMessage(event , null);
    }

    @Override
    public void onHwErrorReported() {
        try {
            mContext.unregisterReceiver(mReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Failed to unregisterScreenState BroadCastReceiver: " + e);
        }
        mIsRecovering = true;
        new EnableDisableTask().execute(TASK_DISABLE);
        new EnableDisableTask().execute(TASK_ENABLE);
    }

    @Override
    public void onVendorSpecificEvent(int gid, int oid, byte[] payload) {
        sendVendorNciNotification(gid, oid, payload);
    }

    /**
     * Enable or Disable PowerSaving Mode based on flag
     */
    private boolean setPowerSavingMode(boolean flag) {
        synchronized (NfcService.this) {
            if ((flag && mState != NfcAdapter.STATE_ON)
                    || (!flag && mState != NfcAdapter.STATE_OFF)) {
                Log.d(TAG, "Enable Power Saving Mode is allowed in Nfc On state or "
                        + "Disable PowerSaving is allowed only if it is enabled");
                return false;
            }
        }
        Log.d(TAG, "setPowerSavingMode " + flag);
        if (flag) {
            if(mDeviceHost.setPowerSavingMode(flag)) {
                mIsPowerSavingModeEnabled = true;
                new EnableDisableTask().execute(TASK_DISABLE);
                return true;
            }
        } else {
            new EnableDisableTask().execute(TASK_ENABLE);
            return true;
        }
        Log.d(TAG, "Enable PowerSavingMode failed");
        return false;
    }

    @Override
    public void notifyCoreGenericError(int errorCode) {
        if (errorCode == STATE_TXLDO_OVERCORRENT_ERROR) {
            sendMessage(NfcService.MSG_TXLDO_OVERCORRENT_RECOVERY, null);
        }
    }

    final class ReaderModeParams {
        public int flags;
        public IAppCallback callback;
        public int presenceCheckDelay;
        public IBinder binder;
        public int uid;
    }

    final class DiscoveryTechParams {
        public IBinder binder;
        public int uid;
    }

    void saveNfcOnSetting(boolean on) {
        synchronized (NfcService.this) {
            mPrefsEditor.putBoolean(PREF_NFC_ON, on);
            mPrefsEditor.apply();
            mBackupManager.dataChanged();
        }
    }

    boolean getNfcOnSetting() {
        synchronized (NfcService.this) {
            return mPrefs.getBoolean(PREF_NFC_ON, NFC_ON_DEFAULT);
        }
    }

    /**
     * @hide constant copied from {@link Settings.Global}
     * TODO(b/274636414): Migrate to official API in Android V.
     */
    private static final String SETTINGS_SATELLITE_MODE_RADIOS = "satellite_mode_radios";
    /**
     * @hide constant copied from {@link Settings.Global}
     * TODO(b/274636414): Migrate to official API in Android V.
     */
    private static final String SETTINGS_SATELLITE_MODE_ENABLED = "satellite_mode_enabled";

    private boolean isSatelliteModeSensitive() {
        final String satelliteRadios =
                Settings.Global.getString(mContentResolver, SETTINGS_SATELLITE_MODE_RADIOS);
        return satelliteRadios == null || satelliteRadios.contains(Settings.Global.RADIO_NFC);
    }

    /** Returns true if satellite mode is turned on. */
    private boolean isSatelliteModeOn() {
        if (!isSatelliteModeSensitive()) return false;
        return Settings.Global.getInt(mContentResolver, SETTINGS_SATELLITE_MODE_ENABLED, 0) == 1;
    }

    /** Returns true if NFC has user restriction set. */
    private boolean isNfcUserRestricted() {
        return mUserManager.getUserRestrictions().getBoolean(
                UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO);
    }

    boolean shouldEnableNfc() {
        return getNfcOnSetting() && !isSatelliteModeOn() && !isNfcUserRestricted();
    }

    public NfcService(Application nfcApplication) {
        mUserId = ActivityManager.getCurrentUser();
        mContext = nfcApplication;

        mNfcTagService = new TagService();
        mNfcAdapter = new NfcAdapterService();
        mRoutingTableParser = new RoutingTableParser();

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
        mDeviceConfigFacade = new DeviceConfigFacade(mContext, mHandler);

        mNfcDispatcher = new NfcDispatcher(mContext, mHandoverDataParser, mInProvisionMode);

        mSecureElement = new NativeNfcSecureElement(mContext);
        mToastHandler = new ToastHandler(mContext);
        mPrefs = mContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        mPrefsEditor = mPrefs.edit();
        mNxpPrefs = mContext.getSharedPreferences(NXP_PREF, Context.MODE_PRIVATE);
        mNxpPrefsEditor = mNxpPrefs.edit();
        mNfcStateCheck = true;

        mState = NfcAdapter.STATE_OFF;

        mAlwaysOnState = NfcAdapter.STATE_OFF;

        mIsDebugBuild = "userdebug".equals(Build.TYPE) || "eng".equals(Build.TYPE);

        mPowerManager = mContext.getSystemService(PowerManager.class);

        mRoutingWakeLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "NfcService:mRoutingWakeLock");
        mEeWakeLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "NfcService:mEeWakeLock");
        mRequireUnlockWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE, "NfcService:mRequireUnlockWakeLock");
        mKeyguard = mContext.getSystemService(KeyguardManager.class);
        mUserManager = mContext.getSystemService(UserManager.class);
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mVibrator = mContext.getSystemService(Vibrator.class);
        mVibrationEffect = VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE);

        PackageManager pm = mContext.getPackageManager();
        mIsWatchType = pm.hasSystemFeature(PackageManager.FEATURE_WATCH);

        if (pm.hasSystemFeature(PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE) &&
                !mIsWatchType) {
            mVrManager = mContext.getSystemService(VrManager.class);
        } else {
            mVrManager = null;
        }

        mScreenState = mScreenStateHelper.checkScreenState();
        mPreviousScreenState = mScreenState;

        mBackupManager = new BackupManager(mContext);

        mStatsdUtils = mFeatureFlags.statsdCeEventsFlag() ? new StatsdUtils() : null;

        // Intents for all users
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        mContext.registerReceiverForAllUsers(mReceiver, filter, null, null);

        // Listen for work profile adds or removes.
        IntentFilter managedProfileFilter = new IntentFilter();
        managedProfileFilter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        managedProfileFilter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        managedProfileFilter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        managedProfileFilter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        mContext.registerReceiverForAllUsers(mManagedProfileReceiver,
                managedProfileFilter, null, null);

        IntentFilter ownerFilter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        ownerFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        ownerFilter.addAction(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiverForAllUsers(mOwnerReceiver, ownerFilter, null, null);

        ownerFilter = new IntentFilter();
        ownerFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        ownerFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        ownerFilter.addDataScheme("package");
        mContext.registerReceiverForAllUsers(mOwnerReceiver, ownerFilter, null, null);

        addKeyguardLockedStateListener();

        updatePackageCache();

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
        mForegroundUtils = ForegroundUtils.getInstance(mActivityManager);

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

        // Notification message variables
        mDispatchFailedCount = 0;
        if (mDeviceConfigFacade.isAntennaBlockedAlertEnabled() &&
            !mPrefs.getBoolean(PREF_ANTENNA_BLOCKED_MESSAGE_SHOWN, ANTENNA_BLOCKED_MESSAGE_SHOWN_DEFAULT)) {
            mAntennaBlockedMessageShown = false;
            mDispatchFailedMax =
                mContext.getResources().getInteger(R.integer.max_antenna_blocked_failure_count);
        } else {
            mAntennaBlockedMessageShown = true;
        }

        // Polling delay count for switching from stage one to stage two.
        mPollDelayCountMax =
                mContext.getResources().getInteger(R.integer.unknown_tag_polling_delay_count_max);
        // Stage one: polling delay time for the first few unknown tag detections
        mPollDelayTime = mContext.getResources().getInteger(R.integer.unknown_tag_polling_delay);
        // Stage two: longer polling delay time after max_poll_delay_count
        mPollDelayTimeLong =
                mContext.getResources().getInteger(R.integer.unknown_tag_polling_delay_long);
        mNotifyDispatchFailed =
            mContext.getResources().getBoolean(R.bool.enable_notify_dispatch_failed);
        mNotifyReadFailed = mContext.getResources().getBoolean(R.bool.enable_notify_read_failed);

        mPollingDisableAllowed = mContext.getResources().getBoolean(R.bool.polling_disable_allowed);
        mAppInActivityDetectionTime =
            mContext.getResources().getInteger(R.integer.background_low_power_tag_removal_enable_time);
        mTagRemovalDetectionWaitTime =
            mContext.getResources().getInteger(R.integer.tag_removal_detection_max_wait_time);
        // Make sure this is only called when object construction is complete.
        NfcServiceManager manager = NfcFrameworkInitializer.getNfcServiceManager();
        if (manager == null) {
            Log.e(TAG, "NfcServiceManager is null");
            throw new UnsupportedOperationException();
        }
        manager.getNfcManagerServiceRegisterer().register(mNfcAdapter);

        mIsAlwaysOnSupported =
            mContext.getResources().getBoolean(R.bool.nfcc_always_on_allowed);

        mIsTagAppPrefSupported =
            mContext.getResources().getBoolean(R.bool.tag_intent_app_pref_supported);

        Uri uri = Settings.Global.getUriFor(SETTINGS_SATELLITE_MODE_ENABLED);
        if (uri == null) {
            Log.e(TAG, "satellite mode key does not exist in Settings");
        } else {
            mContext.getContentResolver().registerContentObserver(
                    uri,
                    false,
                    new ContentObserver(null) {
                        @Override
                        public void onChange(boolean selfChange) {
                            if (isSatelliteModeSensitive()) {
                                Log.i(TAG, "Satellite mode change detected");
                                if (shouldEnableNfc()) {
                                    new EnableDisableTask().execute(TASK_ENABLE);
                                } else {
                                    new EnableDisableTask().execute(TASK_DISABLE);
                                }
                            }
                        }
                    });
        }

        mReaderOptionCapable =
                mContext.getResources().getBoolean(R.bool.enable_reader_option_support);

        if(mReaderOptionCapable) {
            mIsReaderOptionEnabled =
                mPrefs.getBoolean(PREF_NFC_READER_OPTION_ON, NFC_READER_OPTION_DEFAULT);
        }

        mIsNfcUserRestricted = isNfcUserRestricted();
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (mIsNfcUserRestricted == isNfcUserRestricted()) {
                            return;
                        }
                        Log.i(TAG, "Disallow NFC user restriction changed from "
                            + mIsNfcUserRestricted + " to " + !mIsNfcUserRestricted + ".");
                        mIsNfcUserRestricted = !mIsNfcUserRestricted;
                        if (shouldEnableNfc()) {
                            new EnableDisableTask().execute(TASK_ENABLE);
                        } else {
                            new EnableDisableTask().execute(TASK_DISABLE);
                        }
                    }
                },
                new IntentFilter(UserManager.ACTION_USER_RESTRICTIONS_CHANGED)
        );

        new EnableDisableTask().execute(TASK_BOOT);  // do blocking boot tasks

        if (NFC_SNOOP_LOG_MODE.equals(NfcProperties.snoop_log_mode_values.FULL) ||
            NFC_VENDOR_DEBUG_ENABLED) {
            new NfcDeveloperOptionNotification(mContext).startNotification();
        }

        connectToSeService();
        try {
          mWlc = new WlcServiceProxy(mContext, mNxpPrefs);
          mNxpWlcAdapter = new NxpWlcAdapterService(mContext,mWlc);
        } catch (Exception e) {
          Log.e(TAG, "Error Initializing WLC service module");
        }
    }

    private void initTagAppPrefList() {
        if (!mIsTagAppPrefSupported) return;
        mTagAppPrefList.clear();
        mTagAppPrefListPrefs = mContext.getSharedPreferences(PREF_TAG_APP_LIST,
                Context.MODE_PRIVATE);
        try {
            if (mTagAppPrefListPrefs != null) {
                UserManager um = mContext.createContextAsUser(
                        UserHandle.of(ActivityManager.getCurrentUser()), 0)
                        .getSystemService(UserManager.class);
                List<UserHandle> luh = um.getEnabledProfiles();
                for (UserHandle uh : luh) {
                    HashMap<String, Boolean> map = new HashMap<>();
                    int userId = uh.getIdentifier();
                    String jsonString =
                            mTagAppPrefListPrefs.getString(Integer.toString(userId),
                                    (new JSONObject()).toString());
                    if (jsonString != null) {
                        JSONObject jsonObject = new JSONObject(jsonString);
                        Iterator<String> keysItr = jsonObject.keys();
                        while (keysItr.hasNext()) {
                            String key = keysItr.next();
                            Boolean value = jsonObject.getBoolean(key);
                            map.put(key, value);
                            if (DBG) Log.d(TAG, "uid:" + userId + "key:" + key + ": " + value);
                        }
                    }
                    mTagAppPrefList.put(userId, map);
                }
            } else {
                Log.e(TAG, "Can't get PREF_TAG_APP_LIST");
            }
        } catch (JSONException e) {
            Log.e(TAG, "JSONException: " + e);
        }
    }

    private void storeTagAppPrefList() {
        if (!mIsTagAppPrefSupported) return;
        mTagAppPrefListPrefs = mContext.getSharedPreferences(PREF_TAG_APP_LIST,
                Context.MODE_PRIVATE);
        if (mTagAppPrefListPrefs != null) {
            UserManager um = mContext.createContextAsUser(
                    UserHandle.of(ActivityManager.getCurrentUser()), 0)
                    .getSystemService(UserManager.class);
            List<UserHandle> luh = um.getEnabledProfiles();
            for (UserHandle uh : luh) {
                SharedPreferences.Editor editor = mTagAppPrefListPrefs.edit();
                int userId = uh.getIdentifier();
                HashMap<String, Boolean> map;
                synchronized (NfcService.this) {
                    map = mTagAppPrefList.getOrDefault(userId, new HashMap<>());
                }
                if (map.size() > 0) {
                    String userIdStr = Integer.toString(userId);
                    JSONObject jsonObject = new JSONObject(map);
                    String jsonString = jsonObject.toString();
                    editor.remove(userIdStr).putString(userIdStr, jsonString).apply();
                }
            }
        } else {
            Log.e(TAG, "Can't get PREF_TAG_APP_LIST");
        }
    }
    private boolean isPackageInstalled(String pkgName, int userId) {
        final PackageInfo info;
        try {
            info = mContext.createContextAsUser(UserHandle.of(userId), 0)
                    .getPackageManager().getPackageInfo(pkgName, PackageManager.MATCH_ALL);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return info != null;
    }
    // Remove obsolete entries
    // return true if the preference list changed.
    private boolean renewTagAppPrefList() {
        if (!mIsTagAppPrefSupported) return false;
        boolean changed = false;
        UserManager um = mContext.createContextAsUser(
                UserHandle.of(ActivityManager.getCurrentUser()), 0)
                .getSystemService(UserManager.class);
        List<UserHandle> luh = um.getEnabledProfiles();
        for (UserHandle uh : luh) {
            int userId = uh.getIdentifier();
            synchronized (NfcService.this) {
                changed = mTagAppPrefList.getOrDefault(userId, new HashMap<>())
                        .keySet().removeIf(k2 -> !isPackageInstalled(k2, userId));
            }
        }
        if (DBG) Log.d(TAG, "TagAppPreference changed " + changed);
        return changed;
    }

    private boolean isSEServiceAvailable() {
        if (mSEService == null) {
            connectToSeService();
        }
        return (mSEService != null);
    }

    private void connectToSeService() {
        try {
            SeServiceManager manager = SeFrameworkInitializer.getSeServiceManager();
            if (manager == null) {
                Log.e(TAG, "SEServiceManager is null");
                return;
            }
            mSEService = ISecureElementService.Stub.asInterface(
                    manager.getSeManagerServiceRegisterer().get());
            if (mSEService != null) {
                IBinder seServiceBinder = mSEService.asBinder();
                seServiceBinder.linkToDeath(mSeServiceDeathRecipient, 0);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error Registering SE service to linktoDeath : " + e);
        }
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
        UserManager um = mContext.createContextAsUser(
                UserHandle.of(ActivityManager.getCurrentUser()), /*flags=*/0)
                .getSystemService(UserManager.class);
        List<UserHandle> luh = um.getEnabledProfiles();

        synchronized (this) {
            mNfcEventInstalledPackages.clear();
            mNfcPreferredPaymentChangedInstalledPackages.clear();
            for (UserHandle uh : luh) {
                if (um.isQuietModeEnabled(uh)) continue;

                PackageManager pm;
                try {
                    pm = mContext.createContextAsUser(uh, /*flags=*/0).getPackageManager();
                } catch (IllegalStateException e) {
                    Log.d(TAG, "Fail to get PackageManager for user: " + uh);
                    continue;
                }

                List<PackageInfo> packagesNfcEvents = pm.getPackagesHoldingPermissions(
                        new String[] {android.Manifest.permission.NFC_TRANSACTION_EVENT},
                        PackageManager.GET_ACTIVITIES);
                List<PackageInfo> packagesNfcPreferredPaymentChanged =
                        pm.getPackagesHoldingPermissions(
                        new String[] {android.Manifest.permission.NFC_PREFERRED_PAYMENT_INFO},
                        PackageManager.GET_ACTIVITIES);
                List<String> packageListNfcEvent = new ArrayList<String>();
                for (int i = 0; i < packagesNfcEvents.size(); i++) {
                    packageListNfcEvent.add(packagesNfcEvents.get(i).packageName);
                }
                mNfcEventInstalledPackages.put(uh.getIdentifier(), packageListNfcEvent);

                List<String> packageListNfcPreferredPaymentChanged = new ArrayList<String>();
                for (int i = 0; i < packagesNfcPreferredPaymentChanged.size(); i++) {
                    packageListNfcPreferredPaymentChanged.add(
                            packagesNfcPreferredPaymentChanged.get(i).packageName);
                }
                mNfcPreferredPaymentChangedInstalledPackages.put(
                        uh.getIdentifier(), packageListNfcPreferredPaymentChanged);
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

    boolean doReset(int handle) {
       return mSecureElement.doReset(handle);
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
            // Quick check mState
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
                    if (sIsNfcRestore && mIsTagAppPrefSupported) {
                        synchronized (NfcService.this) {
                            initTagAppPrefList();
                            sIsNfcRestore = false;
                        }
                    }
                    break;
                case TASK_DISABLE:
                    disableInternal();
                    break;
                case TASK_ENABLE_FOR_ULPDET:
                    enableInternalForULPDet();
                    break;
                case TASK_BOOT:
                    boolean initialized;
                    if (mPrefs.getBoolean(PREF_FIRST_BOOT, true)) {
                        Log.i(TAG, "First Boot");
                        mPrefsEditor.putBoolean(PREF_FIRST_BOOT, false);
                        mPrefsEditor.apply();
                        mDeviceHost.factoryReset();
                        setPaymentForegroundPreference(mUserId);
                    }
                    Log.d(TAG, "checking on firmware download");
                    if (shouldEnableNfc()) {
                        Log.d(TAG, "NFC is on. Doing normal stuff");
                        initialized = enableInternal();
                    } else {
                        Log.d(TAG, "NFC is off.  Checking firmware version");
                        initialized = mDeviceHost.checkFirmware();
                    }

                    if (initialized) {
                        // TODO(279846422) The system property will be temporary
                        // available for vendors that depend on it.
                        // Remove this code when a replacement API is added.
                        NfcProperties.initialized(true);
                    }

                    if (mIsTagAppPrefSupported) {
                        synchronized (NfcService.this) {
                            initTagAppPrefList();
                        }
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
            synchronized (NfcService.this) {
                if (mState == NfcAdapter.STATE_ON || mState == NfcAdapter.STATE_TURNING_ON) {
                    return true;
                }
                if(mState == NfcAdapter.STATE_TURNING_OFF) {
                    return false;
                }
                Log.i(TAG, "Enabling NFC");
                NfcStatsLog.write(NfcStatsLog.NFC_STATE_CHANGED,
                    mIsSecureNfcEnabled ? NfcStatsLog.NFC_STATE_CHANGED__STATE__ON_LOCKED :
                    NfcStatsLog.NFC_STATE_CHANGED__STATE__ON);
                updateState(NfcAdapter.STATE_TURNING_ON);
            }
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
                    if (mRoutingWakeLock.isHeld()) {
                        mRoutingWakeLock.release();
                    }
                }
            } finally {
                watchDog.cancel();
            }

            try {
              if (mWlc.isToBeEnabled())
                mWlc.enable(WlcServiceProxy.PersistStatus.IGNORE);
            } catch (Exception e) {
              Log.e(TAG, "Error enabling WlcService");
            }

            int uiccSlot = 0;
            uiccSlot = mPrefs.getInt(PREF_CUR_SELECTED_UICC_ID, SECURE_ELEMENT_UICC_SLOT_DEFAULT);
            mDeviceHost.setPreferredSimSlot(uiccSlot);
            mOverflowDefaultRoute = ROUTE_INVALID;
            if (mIsHceCapable) {
                // Generate the initial card emulation routing table
                mCardEmulationManager.onNfcEnabled();
                if (getLastCommitRoutingStatus() == false) {
                    computeRoutingParameters();
                }
            }

            mSkipNdefRead = NfcProperties.skipNdefRead().orElse(false);
            nci_version = getNciVersion();
            Log.d(TAG, "NCI_Version: " + nci_version);

            synchronized (NfcService.this) {
                mObjectMap.clear();

                onPreferredPaymentChanged(NfcAdapter.PREFERRED_PAYMENT_LOADED);
            }

            initSoundPool();

            mScreenState = mScreenStateHelper.checkScreenState();
            int screen_state_mask = (mNfcUnlockManager.isLockscreenPollingEnabled()) ?
                             (ScreenStateHelper.SCREEN_POLLING_TAG_MASK | mScreenState) : mScreenState;
            /*
             * Avoid mState checking in applyRouting
             * as precondition mState check already covered
             */
            SetNfcStateCheck(false);
            if(mNfcUnlockManager.isLockscreenPollingEnabled())
                applyRouting(false);

            mDeviceHost.doSetScreenState(screen_state_mask);
            mPreviousScreenState = mScreenState;

            sToast_debounce = false;

            /* Start polling loop */
            applyRouting(true);
            /*
             * Perfrom mState checking in applyRouting
             * requests from hereon
             */
            SetNfcStateCheck(true);
            commitRouting();
            /* WiredSe Init after ESE is discovered and initialised */
            initWiredSe();
            synchronized (NfcService.this) {
                updateState(NfcAdapter.STATE_ON);
            }

            if(mIsPowerSavingModeEnabled) {
                mDeviceHost.setPowerSavingMode(false);
                mIsPowerSavingModeEnabled = false;
            }

            return true;
        }

        boolean enableInternalForULPDet() {
            synchronized (NfcService.this) {
                Log.i(TAG, "Disabling NFC requires NFCC to be out of ULPDET mode."
                        + " Enabling NFC stack.");
                mState = NfcAdapter.STATE_TURNING_ON;
            }
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

            synchronized (NfcService.this) {
                mState = NfcAdapter.STATE_ON;
            }
            return true;
        }

        /**
         * Disable all NFC adapter functions.
         * Does not toggle preferences.
         */
        boolean disableInternal() {
            synchronized (NfcService.this) {
                if (mState == NfcAdapter.STATE_OFF || mState == NfcAdapter.STATE_TURNING_OFF) {
                    return true;
                }
                if (mState == NfcAdapter.STATE_TURNING_ON) {
                    return false;
                }
                Log.i(TAG, "Disabling NFC ");
                NfcStatsLog.write(NfcStatsLog.NFC_STATE_CHANGED, NfcStatsLog.NFC_STATE_CHANGED__STATE__OFF);
                updateState(NfcAdapter.STATE_TURNING_OFF);
            }
            try {
              mWlc.disable(WlcServiceProxy.PersistStatus.IGNORE);
              mWlc.deRegisterCallBack();
            } catch (Exception e) {
              Log.e(TAG, "Error disabling WlcService");
            }

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

            // Stop watchdog if tag present
            // A convenient way to stop the watchdog properly consists of
            // disconnecting the tag. The polling loop shall be stopped before
            // to avoid the tag being discovered again.
            maybeDisconnectTarget();

            synchronized (NfcService.this) {
                // Disable delay polling when disabling
                mPollDelayed = false;
                mPollDelayCount = 0;
                mHandler.removeMessages(MSG_DELAY_POLLING);
                mPollingDisableDeathRecipients.clear();
                mReaderModeParams = null;
                mDiscoveryTechParams = null;
            }
            mNfcDispatcher.setForegroundDispatch(null, null, null);


            boolean result = mDeviceHost.deinitialize();
            if (DBG) Log.d(TAG, "mDeviceHost.deinitialize() = " + result);
            isWiredOpen = false;
            watchDog.cancel();

            synchronized (NfcService.this) {
                mCurrentDiscoveryParameters = NfcDiscoveryParameters.getNfcOffParameters();
                if (mReaderModeParams != null) {
                    mReaderModeParams = null;
                }
                updateState(NfcAdapter.STATE_OFF);
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

    public void playSound(int sound) {
        synchronized (this) {
            if (mSoundPool == null) {
                Log.w(TAG, "Not playing sound when NFC is disabled");
                return;
            }

            if (mVrManager != null && mVrManager.isVrModeEnabled()) {
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

    private void resetReaderModeParams() {
        synchronized (NfcService.this) {
            if (mPollingDisableDeathRecipients.size() == 0) {
                Log.d(TAG, "Disabling reader mode because app died or moved to background");
                mReaderModeParams = null;
                StopPresenceChecking();
                if (isNfcEnabled()) {
                    applyRouting(false);
                }
            }
        }
    }

    @Override
    public void onUidToBackground(int uid) {
        Log.i(TAG, "Uid " + uid + " switch to background.");
        synchronized (NfcService.this) {
            if (mReaderModeParams != null && mReaderModeParams.uid == uid) {
                mReaderModeParams.binder.unlinkToDeath(mReaderModeDeathRecipient, 0);
                resetReaderModeParams();
            }
        }
        synchronized (NfcService.this) {
            if (mDiscoveryTechParams != null && mDiscoveryTechParams.uid == uid) {
                mDiscoveryTechParams.binder.unlinkToDeath(mDiscoveryTechDeathRecipient, 0);
                mDeviceHost.resetDiscoveryTech();
                mDiscoveryTechParams = null;
                if (isNfcEnabled()) {
                  applyRouting(true);
                }
            }
        }
    }

    public boolean isSecureNfcEnabled() {
        return mIsSecureNfcEnabled;
    }

    final class NfcAdapterService extends INfcAdapter.Stub {
        @Override
        public boolean enable() throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);

            saveNfcOnSetting(true);

            if (shouldEnableNfc()) {
                new EnableDisableTask().execute(TASK_ENABLE);
            }

            if (mIsRecovering) {
              // Intents for all users
              IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
              filter.addAction(Intent.ACTION_SCREEN_ON);
              filter.addAction(Intent.ACTION_USER_PRESENT);
              filter.addAction(Intent.ACTION_USER_SWITCHED);
              filter.addAction(Intent.ACTION_USER_ADDED);
              mContext.registerReceiverForAllUsers(mReceiver, filter, null, null);
              mIsRecovering = false;
            }

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
            NfcPermissions.enforceAdminPermissions(mContext);

            if (saveState) {
                saveNfcOnSetting(false);
            }

            new EnableDisableTask().execute(TASK_DISABLE);

            return true;
        }

        @Override
        public boolean isObserveModeSupported() {
            long token = Binder.clearCallingIdentity();
            try {
                if (!android.nfc.Flags.nfcObserveMode()) {
                    return false;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return mDeviceHost.isObserveModeSupported();
        }

        @Override
        public boolean setObserveMode(boolean enable) {
            if (!isNfcEnabled()) {
                Log.e(TAG, "NFC is not enabled.");
                return false;
            }
            long token = Binder.clearCallingIdentity();
            try {
                if (!android.nfc.Flags.nfcObserveMode()) {
                    return false;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            boolean privilegedCaller = false;
            int callingUid = Binder.getCallingUid();
            UserHandle user = Binder.getCallingUserHandle();
            // Allow non-foreground callers with system uid or default payment service.
            String packageName = getPackageNameFromUid(callingUid);
            if (packageName != null) {
                String defaultPaymentService = Settings.Secure.getString(
                    mContext.createContextAsUser(user, 0).getContentResolver(),
                Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT);
                if (defaultPaymentService != null) {
                    String defaultPaymentPackage =
                        ComponentName.unflattenFromString(defaultPaymentService).getPackageName();
                    privilegedCaller = (callingUid == Process.SYSTEM_UID
                            || packageName.equals(defaultPaymentPackage));
                }
            } else {
                privilegedCaller = (callingUid == Process.SYSTEM_UID);
            }
            if (!privilegedCaller) {
                NfcPermissions.enforceUserPermissions(mContext);
                if (!mForegroundUtils.isInForeground(Binder.getCallingUid())) {
                    Log.e(TAG, "setObserveMode: Caller not in foreground.");
                    return false;
                }
            }
            return mDeviceHost.setObserveMode(enable);
        }

        @Override
        public void pausePolling(int timeoutInMs) {
            NfcPermissions.enforceAdminPermissions(mContext);

            checkAndHandleRemovalDetectionMode(false);
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
            if (DBG) Log.d(TAG, "Polling is resumed");
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
                if (mIsSecureNfcEnabled == enable) {
                    Log.e(TAG, "setNfcSecure error, can't apply the same state twice!");
                    return false;
                }
                Log.i(TAG, "setting Secure NFC " + enable);
                mPrefsEditor.putBoolean(PREF_SECURE_NFC_ON, enable);
                mPrefsEditor.apply();
                mIsSecureNfcEnabled = enable;
                mBackupManager.dataChanged();
                mDeviceHost.setNfcSecure(enable);
                computeAndSetRoutingParameters();
                if (mIsHceCapable) {
                    // update HCE/HCEF routing and commitRouting if Nfc is enabled
                    mCardEmulationManager.onSecureNfcToggled();
                } else if (isNfcEnabled()) {
                    // commit only tech/protocol route without HCE support
                    mDeviceHost.commitRouting();
                }
            }
            NfcStatsLog.write(NfcStatsLog.NFC_STATE_CHANGED,
                    mIsSecureNfcEnabled ? NfcStatsLog.NFC_STATE_CHANGED__STATE__ON_LOCKED :
                    NfcStatsLog.NFC_STATE_CHANGED__STATE__ON);
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
        public void updateDiscoveryTechnology(IBinder binder, int pollTech, int listenTech)
                throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            int callingUid = Binder.getCallingUid();
            boolean privilegedCaller = false;
            // Allow non-foreground callers with system uid or systemui
            String packageName = getPackageNameFromUid(callingUid);
            if (packageName != null) {
                privilegedCaller = (callingUid == Process.SYSTEM_UID
                        || packageName.equals(SYSTEM_UI));
            } else {
                privilegedCaller = (callingUid == Process.SYSTEM_UID);
            }
            if (!privilegedCaller
                    && !mForegroundUtils.registerUidToBackgroundCallback(
                            NfcService.this, callingUid)) {
                Log.e(TAG,
                  "updateDiscoveryTechnology: Caller shall be in foreground or a system process.");
                return;
            }
            checkAndHandleRemovalDetectionMode(false);
            synchronized (NfcService.this) {
                if (!isNfcEnabled()) {
                    Log.d(TAG, "updateDiscoveryTechnology: NFC is not enabled.");
                    return;
                }

                Log.d(TAG, "updateDiscoveryTechnology: pollTech: 0x" +
                        Integer.toHexString(pollTech) +
                        ", listenTech: 0x" + Integer.toHexString(listenTech));
                if (pollTech == NfcAdapter.FLAG_USE_ALL_TECH &&
                        listenTech == NfcAdapter.FLAG_USE_ALL_TECH &&
                        mDiscoveryTechParams != null) {
                    try {
                        binder.unlinkToDeath(mDiscoveryTechDeathRecipient, 0);
                        mDeviceHost.resetDiscoveryTech();
                        mDiscoveryTechParams = null;
                    } catch (NoSuchElementException e) {
                        Log.e(TAG, "Change Tech Binder was never registered.");
                    }
                } else {
                    try {
                        mDeviceHost.setDiscoveryTech(pollTech, listenTech);
                        mDiscoveryTechParams = new DiscoveryTechParams();
                        mDiscoveryTechParams.uid = callingUid;
                        mDiscoveryTechParams.binder = binder;
                        binder.linkToDeath(mDiscoveryTechDeathRecipient, 0);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Remote binder has already died.");
                        return;
                    }
                }

                applyRouting(true);
            }
        }

        @Override
        public void setReaderMode(IBinder binder, IAppCallback callback, int flags, Bundle extras)
                throws RemoteException {
            boolean privilegedCaller = false;
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            // Allow non-foreground callers with system uid or systemui
            String packageName = getPackageNameFromUid(callingUid);
            if (packageName != null) {
                privilegedCaller = (callingUid == Process.SYSTEM_UID
                       || packageName.equals(SYSTEM_UI));
            } else {
                privilegedCaller = (callingUid == Process.SYSTEM_UID);
            }
            Log.d(TAG, "setReaderMode: uid=" + callingUid + ", packageName: "
                    + packageName + ", flags: " + flags);
            if (!privilegedCaller
                    && !mForegroundUtils.registerUidToBackgroundCallback(
                            NfcService.this, callingUid)) {
                Log.e(TAG, "setReaderMode: Caller is not in foreground and is not system process.");
                return;
            }
            boolean disablePolling = flags != 0 && getReaderModeTechMask(flags) == 0;
            // Only allow to disable polling for specific callers
            if (disablePolling && !(privilegedCaller && mPollingDisableAllowed)) {
                Log.e(TAG, "setReaderMode() called with invalid flag parameter.");
                return;
            }
            synchronized (NfcService.this) {
                if (!isNfcEnabled() && !privilegedCaller) {
                    Log.e(TAG, "setReaderMode() called while NFC is not enabled.");
                    return;
                }
                if (flags != 0) {
                    try {
                        if (disablePolling) {
                            ReaderModeDeathRecipient pollingDisableDeathRecipient =
                                    new ReaderModeDeathRecipient();
                            binder.linkToDeath(pollingDisableDeathRecipient, 0);
                            mPollingDisableDeathRecipients.put(
                                    callingPid, pollingDisableDeathRecipient);
                        } else {
                            if (mPollingDisableDeathRecipients.size() != 0) {
                                Log.e(TAG, "active polling is forced to disable now.");
                                return;
                            }
                            binder.linkToDeath(mReaderModeDeathRecipient, 0);
                        }
                        if (mPollDelayed) {
                            mHandler.removeMessages(MSG_DELAY_POLLING);
                            mPollDelayCount = 0;
                            mPollDelayed = false;
                            mDeviceHost.startStopPolling(true);
                            if (DBG) Log.d(TAG, "setReaderMode() polling is started");
                        }
                        updateReaderModeParams(callback, flags, extras, binder, callingUid);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Remote binder has already died.");
                        return;
                    }
                } else {
                    try {
                        ReaderModeDeathRecipient pollingDisableDeathRecipient =
                                mPollingDisableDeathRecipients.get(callingPid);
                        mPollingDisableDeathRecipients.remove(callingPid);

                        if (mPollingDisableDeathRecipients.size() == 0) {
                            mReaderModeParams = null;
                            StopPresenceChecking();
                        }

                        if (pollingDisableDeathRecipient != null) {
                            binder.unlinkToDeath(pollingDisableDeathRecipient, 0);
                        } else {
                            binder.unlinkToDeath(mReaderModeDeathRecipient, 0);
                        }
                    } catch (NoSuchElementException e) {
                        Log.e(TAG, "Reader mode Binder was never registered.");
                    }
                }
                if (isNfcEnabled()) {
                    applyRouting(false);
                }
            }
            checkAndHandleRemovalDetectionMode(true);
        }

        @Override
        public INfcAdapterExtras getNfcAdapterExtrasInterface(String pkg) throws RemoteException {
            // nfc-extras implementation is no longer present in AOSP.
            return null;
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
            if (TextUtils.isEmpty(sku) || !Utils.arrayContains(skuList, sku)) {
                return false;
            }
            return true;
        }

        @Override
        public NfcAntennaInfo getNfcAntennaInfo() {
            int positionX[] = mContext.getResources().getIntArray(
                    R.array.antenna_x);
            int positionY[] = mContext.getResources().getIntArray(
                    R.array.antenna_y);
            if(positionX.length != positionY.length){
                return null;
            }
            int width = mContext.getResources().getInteger(R.integer.device_width);
            int height = mContext.getResources().getInteger(R.integer.device_height);
            List<AvailableNfcAntenna> availableNfcAntennas = new ArrayList<>();
            for(int i = 0; i < positionX.length; i++){
                if(positionX[i] >= width | positionY[i] >= height){
                    return null;
                }
                availableNfcAntennas.add(new AvailableNfcAntenna(positionX[i], positionY[i]));
            }
            return new NfcAntennaInfo(
                    width,
                    height,
                    mContext.getResources().getBoolean(R.bool.device_foldable),
                    availableNfcAntennas);
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
            } else if (vendor.equalsIgnoreCase("wlc")){
                return (IBinder) mNxpWlcAdapter;
            } else {
                return null;
            }
        }

        private int getReaderModeTechMask(int flags) {
            int techMask = 0;
            if ((flags & NfcAdapter.FLAG_READER_NFC_A) != 0) {
                techMask |= NFC_POLL_A;
            }
            if ((flags & NfcAdapter.FLAG_READER_NFC_B) != 0) {
                techMask |= NFC_POLL_B;
            }
            if ((flags & NfcAdapter.FLAG_READER_NFC_F) != 0) {
                techMask |= NFC_POLL_F;
            }
            if ((flags & NfcAdapter.FLAG_READER_NFC_V) != 0) {
                techMask |= NFC_POLL_V;
            }
            if ((flags & NfcAdapter.FLAG_READER_NFC_BARCODE) != 0) {
                techMask |= NFC_POLL_KOVIO;
            }
            if ((flags & NxpNfcAdapter.FLAG_READER_NFC_Q) != 0) {
                techMask |= NFC_POLL_Q;
            }

            return techMask;
        }

        private String getPackageNameFromUid(int uid) {
            PackageManager packageManager = mContext.getPackageManager();
            if (packageManager != null) {
                String[] packageName = packageManager.getPackagesForUid(uid);
                if (packageName != null && packageName.length > 0) {
                    return packageName[0];
                }
            }
            return null;
        }

        private void updateReaderModeParams(
                IAppCallback callback, int flags, Bundle extras, IBinder binder, int uid) {
            synchronized (NfcService.this) {
                mReaderModeParams = new ReaderModeParams();
                mReaderModeParams.callback = callback;
                mReaderModeParams.flags = flags;
                mReaderModeParams.presenceCheckDelay = extras != null
                        ? (extras.getInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY,
                                DEFAULT_PRESENCE_CHECK_DELAY))
                        : DEFAULT_PRESENCE_CHECK_DELAY;
                mReaderModeParams.binder = binder;
                mReaderModeParams.uid = uid;
            }
        }

        private int setTagAppPreferenceInternal(int userId, String pkg, boolean allow) {
            if (!isPackageInstalled(pkg, userId)) {
                return NfcAdapter.TAG_INTENT_APP_PREF_RESULT_PACKAGE_NOT_FOUND;
            }
            if (DBG) Log.i(TAG, "UserId:" + userId + " pkg:" + pkg + ":" + allow);
            synchronized (NfcService.this) {
                mTagAppPrefList.computeIfAbsent(userId, key -> new HashMap<String, Boolean>())
                        .put(pkg, allow);
            }
            storeTagAppPrefList();
            return NfcAdapter.TAG_INTENT_APP_PREF_RESULT_SUCCESS;
        }

        @Override
        public boolean setControllerAlwaysOn(boolean value) throws RemoteException {
            NfcPermissions.enforceSetControllerAlwaysOnPermissions(mContext);
            if (!mIsAlwaysOnSupported) {
                return false;
            }
            return true;
        }

        @Override
        public boolean isControllerAlwaysOn() throws RemoteException {
            NfcPermissions.enforceSetControllerAlwaysOnPermissions(mContext);
            return mIsAlwaysOnSupported && mAlwaysOnState == NfcAdapter.STATE_ON;
        }

        @Override
        public boolean isControllerAlwaysOnSupported() throws RemoteException {
            NfcPermissions.enforceSetControllerAlwaysOnPermissions(mContext);
            return mIsAlwaysOnSupported;
        }

        @Override
        public void registerControllerAlwaysOnListener(
            INfcControllerAlwaysOnListener listener) throws RemoteException {
            NfcPermissions.enforceSetControllerAlwaysOnPermissions(mContext);
            if (!mIsAlwaysOnSupported) return;

            mAlwaysOnListeners.add(listener);
        }

        @Override
        public void unregisterControllerAlwaysOnListener(
            INfcControllerAlwaysOnListener listener) throws RemoteException {
            NfcPermissions.enforceSetControllerAlwaysOnPermissions(mContext);
            if (!mIsAlwaysOnSupported) return;

            mAlwaysOnListeners.remove(listener);
        }
        @Override
        public boolean isTagIntentAppPreferenceSupported() throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            return mIsTagAppPrefSupported;
        }
        @Override
        public Map getTagIntentAppPreferenceForUser(int userId) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (!mIsTagAppPrefSupported) throw new UnsupportedOperationException();
            synchronized (NfcService.this) {
                return mTagAppPrefList.getOrDefault(userId, new HashMap<>());
            }
        }
        @Override
        public int setTagIntentAppPreferenceForUser(int userId,
                String pkg, boolean allow) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (!mIsTagAppPrefSupported) throw new UnsupportedOperationException();
            return setTagAppPreferenceInternal(userId, pkg, allow);
        }

        @Override
        public boolean enableReaderOption(boolean enable) {
            Log.d(TAG, "enableReaderOption enabled=" + enable);
            if (!mReaderOptionCapable) return false;
            NfcPermissions.enforceAdminPermissions(mContext);
            synchronized (NfcService.this) {
                mPrefsEditor.putBoolean(PREF_NFC_READER_OPTION_ON, enable);
                mPrefsEditor.apply();
                mIsReaderOptionEnabled = enable;
                mBackupManager.dataChanged();
            }
            applyRouting(true);
            return true;
        }

        @Override
        public boolean isReaderOptionSupported() {
            return mReaderOptionCapable;
        }

        @Override
        public boolean isReaderOptionEnabled() {
            return mIsReaderOptionEnabled;
        }

        private static boolean isPowerSavingModeCmd(int gid, int oid, byte[] payload) {
            return gid == NCI_GID_PROP && oid == NCI_MSG_PROP_ANDROID && payload.length > 0
                    && payload[0] == NCI_MSG_PROP_ANDROID_POWER_SAVING;
        }

        @Override
        public synchronized int sendVendorNciMessage(int mt, int gid, int oid, byte[] payload)
                throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if ((!isNfcEnabled() && !mIsPowerSavingModeEnabled)) {
                Log.e(TAG, "sendRawVendor : Nfc is not enabled");
                return NCI_STATUS_FAILED;
            }

            FutureTask<Integer> sendVendorCmdTask = new FutureTask<>(
                () -> {
                   if(isPowerSavingModeCmd(gid, oid, payload)) {
                        boolean status = setPowerSavingMode(payload[1] == 0x01 ? true : false);
                        return status? NCI_STATUS_OK : NCI_STATUS_FAILED;
                    } else {
                       NfcVendorNciResponse response =
                               mDeviceHost.sendRawVendorCmd(mt, gid, oid, payload);
                       if (response.status == NCI_STATUS_OK) {
                           sendVendorNciResponse(response.gid, response.oid, response.payload);
                       }
                       return Integer.valueOf(response.status);
                   }
                });
            int status = NCI_STATUS_FAILED;
            try {
                status = runTaskOnSingleThreadExecutor(sendVendorCmdTask,
                        SEND_VENDOR_CMD_TIMEOUT_MS);
            } catch (TimeoutException e) {
                Log.e(TAG, "Failed to send vendor command - status : TIMEOUT", e);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
            return status;
        }

        @Override
        public void registerVendorExtensionCallback(INfcVendorNciCallback callbacks)
                throws RemoteException {
            if (DBG) Log.i(TAG, "Register the callback");
            NfcPermissions.enforceAdminPermissions(mContext);
            mNfcVendorNciCallBack = callbacks;
        }

        @Override
        public void unregisterVendorExtensionCallback(INfcVendorNciCallback callbacks)
                throws RemoteException {
            if (DBG) Log.i(TAG, "Unregister the callback");
            NfcPermissions.enforceAdminPermissions(mContext);
            mNfcVendorNciCallBack = null;
        }

        @Override
        public void registerOemExtensionCallback(INfcOemExtensionCallback callbacks)
                throws RemoteException {
            if (DBG) Log.i(TAG, "Register the oem extension callback");
            NfcPermissions.enforceAdminPermissions(mContext);
            mNfcOemExtensionCallback = callbacks;
        }

        @Override
        public void unregisterOemExtensionCallback(INfcOemExtensionCallback callbacks)
                throws RemoteException {
            if (DBG) Log.i(TAG, "Unregister the oem extension callback");
            NfcPermissions.enforceAdminPermissions(mContext);
            mNfcOemExtensionCallback = null;
        }

        @Override
        public void clearPreference() throws RemoteException {
            if (DBG) Log.i(TAG, "clearPreference");
            NfcPermissions.enforceAdminPermissions(mContext);
            // TODO: Implement this.
        }

        private synchronized void sendVendorNciResponse(int gid, int oid, byte[] payload) {
            if (DBG) Log.i(TAG, "onVendorNciResponseReceived");
            if (mNfcVendorNciCallBack != null) {
                try {
                    mNfcVendorNciCallBack.onVendorResponseReceived(gid, oid, payload);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to send vendor response", e);
                }
            }
        }

        private synchronized void sendVendorNciNotification(int gid, int oid, byte[] payload) {
            if (DBG) Log.i(TAG, "sendVendorNciNotification");
            if (mNfcVendorNciCallBack != null) {
                try {
                    mNfcVendorNciCallBack.onVendorNotificationReceived(gid, oid, payload);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to send vendor notification", e);
                }
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
          /*
           * Bit position for power configuration and route location
           * bit pos 1 (Full power) = Phone ON
           * bit pos 2 (Low poewer) = Phone off
           * bit pos 3 (No Power)   = Battery Off
           * bit pos 4              = Screen Off
           * bit pos 5              = Screen ON Lock
           * bit pos 6              = Screen ON UnLock
           * bit pos 7 & 8          = RFU
           * bit pos 9  (Route Loc) = eSE
           * bit pos 10 (Route Loc) = UICC
           * bit pos 11 (Route Loc) = UICC2
           * If bit position 9,10 & 11 set to 0 means route location is host*/

          NfcPermissions.enforceUserPermissions(mContext);

          if (routeLoc == NfcConstants.UICC2_ID_TYPE) {
            throw new RemoteException("UICC2 is not supported");
          }
          int protoRouteEntry = 0;
          /*UICC2 ID-4(fromApp) mapped to 3 (JNI)*/
          protoRouteEntry = ((routeLoc & 0x07) == 0x04) ? (0x03 << ROUTE_LOC_MASK) : /*UICC2*/
                            ((routeLoc & 0x07) == 0x02) ? (0x02 << ROUTE_LOC_MASK) : /*UICC1*/
                            ((routeLoc & 0x07) == 0x01) ? (0x01 << ROUTE_LOC_MASK) : /*eSE*/
                            0x00;
          {
              int powerState = 0x39; /*Default set it to NCI 2.0*/
              int routEntry = 0x11; /*Default set it to NCI 2.0*/
              if(nci_version == NCI_VERSION_1_0) {
                  powerState = 0x1F;
                  routEntry = 0xE9;
              }

              protoRouteEntry |=
                  ((fullPower ? (mDeviceHost.getDefaultDesfirePowerState() & powerState) | 0x01 : 0)
                      | (lowPower ? 0x01 << 1 : 0) | (noPower ? 0x01 << 2 : 0));

              if (routeLoc == 0x00) {
                  /*
                  bit pos 1 = Power Off
                  bit pos 2 = Battery Off
                  bit pos 4 = Screen Off
                  Set these bits to 0 because in case routeLoc = HOST it can not work on POWER_OFF,
                  BATTERY_OFF and SCREEN_OFF*/
                  protoRouteEntry &= routEntry;
              }
          }

          Log.i(TAG, "MifareDesfireRouteSet : " + protoRouteEntry);
          mNxpPrefsEditor = mNxpPrefs.edit();
          mNxpPrefsEditor.putInt("PREF_MIFARE_DESFIRE_PROTO_ROUTE_ID", protoRouteEntry);
          mNxpPrefsEditor.commit();
          Log.i(TAG, "MifareDesfireRouteSet function in");
        }
        @Override
        public void MifareCLTRouteSet(int routeLoc, boolean fullPower, boolean lowPower,
            boolean noPower) throws RemoteException {
           /*
           * Bit position for power configuration and route location
           * bit pos 1 (Full power) = Phone ON
           * bit pos 2 (Low poewer) = Phone off
           * bit pos 3 (No Power)   = Battery Off
           * bit pos 4              = Screen Off
           * bit pos 5              = Screen ON Lock
           * bit pos 6              = Screen ON UnLock
           * bit pos 7 & 8          = RFU
           * bit pos 9  (Route Loc) = eSE
           * bit pos 10 (Route Loc) = UICC
           * bit pos 11 (Route Loc) = UICC2
           * If bit position 9,10 & 11 set to 0 means route location is host*/

          NfcPermissions.enforceUserPermissions(mContext);

          if (routeLoc == NfcConstants.UICC2_ID_TYPE) {
            throw new RemoteException("UICC2 is not supported");
          }

          int techRouteEntry = 0;
          techRouteEntry =  ((routeLoc & 0x07) == 0x04) ? (0x03 << ROUTE_LOC_MASK) : /*UICC2*/
                            ((routeLoc & 0x07) == 0x02) ? (0x02 << ROUTE_LOC_MASK) : /*UICC1*/
                            ((routeLoc & 0x07) == 0x01) ? (0x01 << ROUTE_LOC_MASK) : /*eSE*/
                            0x00;
          {
             int powerState = 0x39; /*Default set it to NCI 2.0*/
             if(nci_version == NCI_VERSION_1_0) {
               powerState = 0x1F;
             }
             techRouteEntry |=
                ((fullPower ? (mDeviceHost.getDefaultMifareCLTPowerState() & powerState) | 0x01 : 0)
                    | (lowPower ? 0x01 << 1 : 0) | (noPower ? 0x01 << 2 : 0));
          }

          Log.i(TAG, "MifareCLTRouteSet : " + techRouteEntry);
          mNxpPrefsEditor = mNxpPrefs.edit();
          mNxpPrefsEditor.putInt("PREF_MIFARE_CLT_ROUTE_ID", techRouteEntry);
          mNxpPrefsEditor.commit();
        }
        @Override
        public void NfcFRouteSet(int routeLoc, boolean fullPower, boolean lowPower,
            boolean noPower) throws RemoteException {
          /*
           * Bit position for power configuration and route location
           * bit pos 1 (Full power) = Phone ON
           * bit pos 2 (Low poewer) = Phone off
           * bit pos 3 (No Power)   = Battery Off
           * bit pos 4              = Screen Off
           * bit pos 5              = Screen ON Lock
           * bit pos 6              = Screen ON UnLock
           * bit pos 7 & 8          = RFU
           * bit pos 9  (Route Loc) = eSE
           * bit pos 10 (Route Loc) = UICC
           * bit pos 11 (Route Loc) = UICC2
           * If bit position 9,10 & 11 set to 0 means route location is host*/

          NfcPermissions.enforceUserPermissions(mContext);

          if (routeLoc == NfcConstants.UICC2_ID_TYPE) {
            throw new RemoteException("UICC2 is not supported");
          }

          int techRouteEntry = 0;
          techRouteEntry =  ((routeLoc & 0x07) == 0x04) ? (0x03 << ROUTE_LOC_MASK) : /*UICC2*/
                            ((routeLoc & 0x07) == 0x02) ? (0x02 << ROUTE_LOC_MASK) : /*UICC1*/
                            ((routeLoc & 0x07) == 0x01) ? (0x01 << ROUTE_LOC_MASK) : /*eSE*/
                            0x00;
          {
             int powerState = 0x39; /*Default set it to NCI 2.0*/
             if(nci_version == NCI_VERSION_1_0) {
               powerState = 0x1F;
             }
             techRouteEntry |=
                ((fullPower ? (mDeviceHost.getDefaultMifareCLTPowerState() & powerState) | 0x01 : 0)
                    | (lowPower ? 0x01 << 1 : 0) | (noPower ? 0x01 << 2 : 0));
          }

          Log.i(TAG, "NfcFRouteSet : " + techRouteEntry);
          mNxpPrefsEditor = mNxpPrefs.edit();
          mNxpPrefsEditor.putInt("PREF_FELICA_CLT_ROUTE_ID", techRouteEntry);
          mNxpPrefsEditor.commit();
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
          if((readerType == null) || (readerType.isEmpty())) {
            /* Invalid Secure Reader Type received. */
          } else if(readerType.equals("MPOS")) {
            reader =  SE_READER_TYPE_MPOS;
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

                mDeviceHost.setDiscoveryTech(pollTech, listenTech);

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
            int status = 0xFF;
            Method mNfcSelfTestMethod;
            Log.i(TAG,"doNfcSelfTest type Enter : " + type);
            synchronized(NfcService.this) {
              try {
                 if(type == SELFTEST_PRBS || type == SELFTEST_SWP){
                     if (mNfcExtnsObj!=null) {
                         mNfcSelfTestMethod = mNfcExtnsClass.getDeclaredMethod(
                             "doNfcSelfTest", int.class);
                         mNfcSelfTestMethod.invoke(mNfcExtnsObj,type);
                         status = 0x00;
                     } else {
                         Log.i(TAG,"doNfcSelfTest: " + type + " isn't supported");
                         return status;
                     }
                 } else if(type == SELFTEST_RESTORE_RFTXCFG || type == SELFTEST_SET_RFTXCFG) {
                    mNfcAdapter.resonantFrequency(type);
                    status = 0x00;
                 } else {
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
                throws RemoteException {
            /*
             * Bit position for power configuration and route location
             * bit pos 1 (Full power) = Phone ON
             * bit pos 2 (Low poewer) = Phone off
             * bit pos 3 (No Power)   = Battery Off
             * bit pos 4              = Screen Off
             * bit pos 5              = Screen ON Lock
             * bit pos 6              = Screen ON UnLock
             * bit pos 7 & 8          = RFU
             * bit pos 9  (Route Loc) = eSE
             * bit pos 10 (Route Loc) = UICC
             * bit pos 11 (Route Loc) = UICC2
             * If bit position 9,10 & 11 set to 0 means route location is host*/

            NfcPermissions.enforceUserPermissions(mContext);

            if(routeLoc == NfcConstants.UICC2_ID_TYPE) {
                throw new RemoteException("UICC2 is not supported");
            }
            if (mIsHceCapable) {
                int protoRouteEntry = 0;
                protoRouteEntry=((routeLoc & 0x07) == 0x04) ? (0x03 << ROUTE_LOC_MASK) : /*UICC2*/
                                ((routeLoc & 0x07) == 0x02) ? (0x02 << ROUTE_LOC_MASK) : /*UICC1*/
                                ((routeLoc & 0x07) == 0x01) ? (0x01 << ROUTE_LOC_MASK) : /*eSE*/
                                0x00;
                {
                    int powerState = 0x39; /*Default set it to NCI 2.0*/
                    int routEntry = 0x11; /*Default set it to NCI 2.0*/
                    if(nci_version == NCI_VERSION_1_0) {
                        powerState = 0x1F;
                        routEntry = 0xE9;
                    }
                    protoRouteEntry |=
                        ((fullPower ? (mDeviceHost.getDefaultAidPowerState() & powerState) | 0x01 : 0)
                            | (lowPower ? 0x01 << 1 : 0) | (noPower ? 0x01 << 2 : 0));

                    if(routeLoc == NfcConstants.HOST_ID_TYPE) {
                        /*
                        bit pos 1 = Power Off
                        bit pos 2 = Battery Off
                        bit pos 4 = Screen Off
                        Set these bits to 0 because in case routeLoc = HOST it can not work on
                        POWER_OFF, BATTERY_OFF and SCREEN_OFF*/
                        protoRouteEntry &= routEntry;
                    }
                }
                Log.i(TAG,"DefaultRouteSet : " + protoRouteEntry);
                int defaultRoute = mNxpPrefs.getInt("PREF_SET_DEFAULT_ROUTE_ID", GetDefaultRouteEntry());
                if(defaultRoute != protoRouteEntry) {
                    mNxpPrefsEditor = mNxpPrefs.edit();
                    mNxpPrefsEditor.putInt("PREF_SET_DEFAULT_ROUTE_ID", protoRouteEntry );
                    mNxpPrefsEditor.commit();
                    if (!isNfcEnabled()) {
                        return;
                    }
                    mIsRouteForced = true;
                    mAidRoutingManager.onNfccRoutingTableCleared();
                    mDeviceHost.clearRoutingEntry(AID_ENTRY);
                    mCardEmulationManager.onRoutingTableChanged();
                    mIsRouteForced = false;
                }
            } else {
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
            FileWriter fw = null;
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
                    fw = new FileWriter(newTextFile);
                    fw.write(configs);
                    Log.e(TAG, "File Written to libnfc-nxpTransit.conf successfully" );
                }
                newTextFile = null;
                mDeviceHost.setTransitConfig(configs);
            } catch (Exception e) {
                e.printStackTrace();
                return TRANSIT_SETCONFIG_STAT_FAILED;
            } finally {
              if (fw != null) {
                try {
                  fw.close();
                } catch (Exception e) {
                  e.printStackTrace();
                  return TRANSIT_SETCONFIG_STAT_FAILED;
                }
              }
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
        public int startRssiMode(int rssiNtfTimeIntervalInMillisec) {
          NfcPermissions.enforceUserPermissions(mContext);
          return mDeviceHost.doStartRssiMode(rssiNtfTimeIntervalInMillisec);
        }

        @Override
        public int stopRssiMode() {
          NfcPermissions.enforceUserPermissions(mContext);
          return mDeviceHost.doStopRssiMode();
        }

        @Override
        public boolean isRssiEnabled() {
          NfcPermissions.enforceUserPermissions(mContext);
          return mDeviceHost.isRssiEnabled();
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

        public int enableDebugNtf(byte fieldValue) {
          NfcPermissions.enforceUserPermissions(mContext);
          return mDeviceHost.doEnableDebugNtf(fieldValue);
        }

        @Override
        public int startExtendedFieldDetectMode(int detectionTimeout) {
          NfcPermissions.enforceUserPermissions(mContext);
          return mDeviceHost.startExtendedFieldDetectMode(detectionTimeout);
        }

        @Override
        public int stopExtendedFieldDetectMode() {
          NfcPermissions.enforceUserPermissions(mContext);
          return mDeviceHost.stopExtendedFieldDetectMode();
        }

        @Override
        public int startCardEmulation() {
          NfcPermissions.enforceUserPermissions(mContext);
          return mDeviceHost.startCardEmulation();
        }

    }

    private void sendVendorNciNotification(int gid, int oid, byte[] payload) {
        if (DBG) Log.i(TAG, "sendVendorNciNotification");
        if (mNfcVendorNciCallBack != null) {
            try {
                mNfcVendorNciCallBack.onVendorNotificationReceived(gid, oid, payload);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send vendor notification", e);
            }
        }
    }

    final class SeServiceDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            synchronized (NfcService.this) {
                Log.i(TAG, "SE Service died");
                mSEService = null;
            }
        }
    }

    final class ReaderModeDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            synchronized (NfcService.this) {
                if (mReaderModeParams != null) {
                    mPollingDisableDeathRecipients.values().remove(this);
                    resetReaderModeParams();
                }
            }
        }
    }

    final class DiscoveryTechDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            if (DBG) Log.d(TAG, "setDiscoveryTech death recipient");
            synchronized (NfcService.this) {
                if (isNfcEnabled() && mDiscoveryTechParams != null) {
                    mDeviceHost.resetDiscoveryTech();
                    mDiscoveryTechParams = null;
                }
            }
            applyRouting(true);
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

            if (!isReaderOptionEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                throw new RemoteException("Removal detection state, Restarted discovery..");
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

            if (!isReaderOptionEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                throw new RemoteException("Removal detection state, Restarted discovery..");
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

            if (!isReaderOptionEnabled()) {
                return null;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                throw new RemoteException("Removal detection state, Restarted discovery..");
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

            if (!isReaderOptionEnabled()) {
                return false;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                throw new RemoteException("Removal detection state, Restarted discovery..");
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

            if (!isReaderOptionEnabled()) {
                return false;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                throw new RemoteException("Removal detection state, Restarted discovery..");
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

            if (!isReaderOptionEnabled()) {
                return null;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                throw new RemoteException("Removal detection state, Restarted discovery..");
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

            if (!isReaderOptionEnabled()) {
                return null;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                throw new RemoteException("Removal detection state, Restarted discovery..");
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

            if (!isReaderOptionEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                throw new RemoteException("Removal detection state, Restarted discovery..");
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
            if (checkAndHandleRemovalDetectionMode(true)) {
                throw new RemoteException("Removal detection state, Restarted discovery..");
            }
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

            if (!isReaderOptionEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                throw new RemoteException("Removal detection state, Restarted discovery..");
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

            if (!isReaderOptionEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                throw new RemoteException("Removal detection state, Restarted discovery..");
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

            if (!isReaderOptionEnabled()) {
                return null;
            }

            if (checkAndHandleRemovalDetectionMode(true)) {
                throw new RemoteException("Removal detection state, Restarted discovery..");
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
                    /* Avoid setting mCookieUpToDate to negative values */
                    mCookieUpToDate = mCookieGenerator.nextLong() >>> 1;
                    Tag newTag = new Tag(tag.getUid(), tag.getTechList(),
                            tag.getTechExtras(), tag.getHandle(), mCookieUpToDate, this);
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

        @Override
        public boolean isTagUpToDate(long cookie) throws RemoteException {
            if (mCookieUpToDate != -1 && mCookieUpToDate == cookie) {
                if (DBG) Log.d(TAG, "Tag " + Long.toString(cookie) + " is up to date");
                return true;
            }

            if (DBG) Log.d(TAG, "Tag " + Long.toString(cookie) + " is out of date");
            EventLog.writeEvent(0x534e4554, "199291025", -1,
                    "The obsolete tag was attempted to be accessed");
            return false;
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
            return false;
        }

        public void disableServer() throws RemoteException {
        }

        public boolean enableClient(String serviceName, int miu, int rwSize,
                int testCaseId) throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            return false;
        }

        public void disableClient() throws RemoteException {
            return;
        }

        public boolean registerMessageService(String msgServiceName)
                throws RemoteException {
            NfcPermissions.enforceAdminPermissions(mContext);
            if(msgServiceName == null)
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
        return mSecureElement.doGetAtr(NfcConstants.EE_HANDLE_0xF3);
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

    public boolean isNfcEnabledOrEnabling() {
        synchronized (this) {
            return (mState == NfcAdapter.STATE_ON || mState == NfcAdapter.STATE_TURNING_ON);
        }
    }

    boolean isReaderOptionEnabled() {
        synchronized (this) {
            return mIsReaderOptionEnabled || mReaderModeParams != null;
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
            NfcStatsLog.write(NfcStatsLog.NFC_STATE_CHANGED, NfcStatsLog.NFC_STATE_CHANGED__STATE__CRASH_RESTART);
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
     * Update flag(precondtion check) for mState required or not
     * in applyRouting
     */
    void SetNfcStateCheck(boolean force) {
        synchronized (this) {
            mNfcStateCheck = force;
        }
    }

    private void addKeyguardLockedStateListener() {
        try {
            mKeyguard.addKeyguardLockedStateListener(mContext.getMainExecutor(),
                    mIKeyguardLockedStateListener);
        } catch (Exception e) {
            Log.e(TAG, "Exception in addKeyguardLockedStateListener " + e);
        }
    }

    /**
     * Receives KeyGuard lock state updates
     */
    private KeyguardLockedStateListener mIKeyguardLockedStateListener =
            new KeyguardLockedStateListener() {
        @Override
        public void onKeyguardLockedStateChanged(boolean isKeyguardLocked) {
            applyScreenState(mScreenStateHelper.checkScreenState());
        }
    };

    /**
     * Read mScreenState and apply NFC-C polling and NFC-EE routing
     */
    void applyRouting(boolean force) {
        Log.d(TAG, "applyRouting enter");
        synchronized (this) {
            if (mNfcStateCheck && !isNfcEnabledOrShuttingDown()) {
                return;
            }
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
        if (screenState >= NFC_POLLING_MODE && isReaderOptionEnabled()) {
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
                if ((mReaderModeParams.flags & NxpNfcAdapter.FLAG_READER_NFC_Q) != 0)
                    techMask |= NFC_POLL_Q;

                paramsBuilder.setTechMask(techMask);
                paramsBuilder.setEnableReaderMode(true);
                if (mReaderModeParams.flags != 0 && techMask == 0) {
                    paramsBuilder.setEnableHostRouting(true);
                }
            } else {
                paramsBuilder.setTechMask(NfcDiscoveryParameters.NFC_POLL_DEFAULT);
                paramsBuilder.setEnableP2p(false);
            }
        } else if (screenState == ScreenStateHelper.SCREEN_STATE_ON_LOCKED && mInProvisionMode) {
            paramsBuilder.setTechMask(NfcDiscoveryParameters.NFC_POLL_DEFAULT);
            // enable P2P for MFM/EDU/Corp provisioning
            paramsBuilder.setEnableP2p(false);
        } else if (screenState == ScreenStateHelper.SCREEN_STATE_ON_LOCKED &&
            mNfcUnlockManager.isLockscreenPollingEnabled() && isReaderOptionEnabled()) {
            int techMask = 0;
            if (mNfcUnlockManager.isLockscreenPollingEnabled())
                techMask |= mNfcUnlockManager.getLockscreenPollMask();
            paramsBuilder.setTechMask(techMask);
            paramsBuilder.setEnableLowPowerDiscovery(false);
            paramsBuilder.setEnableP2p(false);
        }

        if (mIsHceCapable && mReaderModeParams == null) {
            // Host routing is always enabled, provided we aren't in reader mode
            paramsBuilder.setEnableHostRouting(true);
        }

        return paramsBuilder.build();
    }

    private void computeAndSetRoutingParameters()
    {
        int protoRoute = mNxpPrefs.getInt("PREF_MIFARE_DESFIRE_PROTO_ROUTE_ID", GetDefaultMifareDesfireRouteEntry());
        int defaultRoute = getConfiguredDefaultRouteEntry();
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

        /* Routing for Technology */
        TechSeId = ((techRoute >> ROUTE_LOC_MASK) & 0x07);
        TechFSeId = ((techfRoute >> ROUTE_LOC_MASK) & 0x07);
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
        synchronized (this) {
            for (Object object : mObjectMap.values()) {
                if (object instanceof TagEndpoint) {
                    return ((TagEndpoint) object).isPresent();
                }
            }
            return false;
        }
    }

    private void StopPresenceChecking() {
        synchronized (this) {
            Object[] objectValues = mObjectMap.values().toArray();
            for (Object object : objectValues) {
                if (object instanceof TagEndpoint) {
                    TagEndpoint tag = (TagEndpoint)object;
                    ((TagEndpoint) object).stopPresenceChecking();
                }
            }
        }
    }

    private boolean IsPresenceCheckStopped() {
        boolean isStopped = false;
        synchronized (this) {
            Object[] objectValues = mObjectMap.values().toArray();
            for (Object object : objectValues) {
                if (object instanceof TagEndpoint) {
                    TagEndpoint tag = (TagEndpoint)object;
                    if (((TagEndpoint) object).isPresenceCheckStopped()) {
                        isStopped = true;
                    }
                }
            }
        }
        return isStopped;
    }

    /**
     * Stops the Presence check thread without calling
     * Disconnect API and onTagDisconnect callback
     */
    private void PrepareForRemovalDetectionMode() {
        synchronized (this) {
            Object[] objectValues = mObjectMap.values().toArray();
            for (Object object : objectValues) {
                if (object instanceof TagEndpoint) {
                    TagEndpoint tag = (TagEndpoint)object;
                    ((TagEndpoint) object).prepareForRemovalDetectionMode();
                }
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
        clearAppInactivityDetectionContext();
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

    public int getAidRoutingTableSize ()
    {
        int aidTableSize = 0x00;
        aidTableSize =  mDeviceHost.getAidTableSize();
        return aidTableSize;
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

        Bundle aidPowerState = new Bundle();
        aidPowerState.putInt(MSG_ROUTE_AID_PARAM_TAG, power);
        msg.setData(aidPowerState);

        mHandler.sendMessage(msg);
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
    }
    /**
     * get default Aid route entry from shared preference
     */
    public int GetDefaultRouteLocSharedPref() {
        int defaultRouteLocSharedPref = mNxpPrefs.getInt("PREF_SET_DEFAULT_ROUTE_ID", ROUTE_INVALID);
        if (defaultRouteLocSharedPref != ROUTE_INVALID)
            defaultRouteLocSharedPref = (defaultRouteLocSharedPref >> ROUTE_LOC_MASK);
        Log.d(TAG, "defaultRouteLocSharedPref  :" + defaultRouteLocSharedPref);
        return defaultRouteLocSharedPref;
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

    /*Returns Default Route based on priority. OverFlow > Shared_Pref > conf file*/
    public int getConfiguredDefaultRouteEntry() {
        return (mOverflowDefaultRoute != ROUTE_INVALID) ? mOverflowDefaultRoute
                                                     : GetDefaultRouteEntry();
    }

    public int GetDefaultRouteEntry()
    {
        int route = mNxpPrefs.getInt("PREF_SET_DEFAULT_ROUTE_ID", ROUTE_INVALID);
        if (route != ROUTE_INVALID)
            return route;
        int routeLoc = mDeviceHost.getDefaultAidRoute();
        int defaultAidRoute = ((mDeviceHost.getDefaultAidPowerState() & 0x3F) | (routeLoc << ROUTE_LOC_MASK));
        if(routeLoc == 0x00) {
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

    /**
     * get default T4TNfcee power state supported
     */
    public int GetT4TNfceePowerState() {
        int powerState = mDeviceHost.getT4TNfceePowerState();
        synchronized (NfcService.this) {
            if (mIsSecureNfcEnabled) {
            /* Secure nfc on,Setting power state screen on unlocked */
                powerState=0x01;
            }
        }
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

    public void clearRoutingTable(int clearFlags) {
        sendMessage(MSG_CLEAR_ROUTING_TABLE, clearFlags);
    }

    public void setIsoDepProtocolRoute(int route) {
        sendMessage(MSG_UPDATE_ISODEP_PROTOCOL_ROUTE, route);
    }

    public void setTechnologyABRoute(int route) {
        sendMessage(MSG_UPDATE_TECHNOLOGY_AB_ROUTE, route);
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

    /**
     * Send require device unlock for NFC intent to system UI.
     */
    public void sendRequireUnlockIntent() {
        if (!mIsRequestUnlockShowed && mKeyguard.isKeyguardLocked()) {
            if (DBG) Log.d(TAG, "Request unlock");
            mIsRequestUnlockShowed = true;
            mRequireUnlockWakeLock.acquire();
            Intent requireUnlockIntent =
                    new Intent(NfcAdapter.ACTION_REQUIRE_UNLOCK_FOR_NFC);
            requireUnlockIntent.setPackage(SYSTEM_UI);
            mContext.sendBroadcast(requireUnlockIntent);
            mRequireUnlockWakeLock.release();
        }
    }

    final class NfcServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ROUTE_AID: {
                    int route   = msg.arg1;
                    int aidInfo = msg.arg2;
                    String aid = (String) msg.obj;

                    int power = 0x00;
                    Bundle bundle = msg.getData();
                    if (bundle != null) {
                        power = bundle.getInt(MSG_ROUTE_AID_PARAM_TAG);
                    }

                    mDeviceHost.routeAid(hexStringToBytes(aid), route, aidInfo, power);
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
                case MSG_COMMIT_ROUTING: {
                    synchronized (NfcService.this) {
                        if (mState == NfcAdapter.STATE_OFF
                                || mState == NfcAdapter.STATE_TURNING_OFF) {
                            Log.d(TAG, "Skip commit routing when NFCC is off or turning off");
                            return;
                        }
                    }
                    Log.d(TAG, "commitRouting >>>");
                    int defaultRoute = getConfiguredDefaultRouteEntry();
                    mDeviceHost.setEmptyAidRoute(defaultRoute);
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
                    /* Avoid setting mCookieUpToDate to negative values */
                    mCookieUpToDate = mCookieGenerator.nextLong() >>> 1;
                    Tag tag = Tag.createMockTag(new byte[]{0x00},
                            new int[]{TagTechnology.NDEF},
                            new Bundle[]{extras}, mCookieUpToDate);
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

                    clearAppInactivityDetectionContext();

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
                                public void onTagDisconnected() {
                                    mCookieUpToDate = -1;
                                    clearAppInactivityDetectionContext();
                                    if((mScreenState > ScreenStateHelper.SCREEN_STATE_ON_LOCKED)) {
                                        applyRouting(false);
                                    }
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

                        if (mIsDebugBuild && mSkipNdefRead) {
                            if (DBG) Log.d(TAG, "Only NDEF detection in reader mode");
                            tag.findNdef();
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
                        if (readerParams == null) {
                            scheduleAppInactivityDetectionTask();
                        }
                        break;
                    }
                    NdefMessage ndefMsg = tag.findAndReadNdef();

                    if (ndefMsg == null) {
                        // First try to see if this was a bad tag read
                        if (!tag.reconnect()) {
                            tag.disconnect();
                            if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                                if (!sToast_debounce && mNotifyReadFailed) {
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
                    if (mWlc.isWlcListenerDetected(ndefMsg)) {
                        break;
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
                    if (readerParams == null) {
                        scheduleAppInactivityDetectionTask();
                    }
                    break;

                case MSG_RF_FIELD_ACTIVATED:
                    Intent fieldOnIntent = new Intent(ACTION_RF_FIELD_ON_DETECTED);
                    sendNfcPermissionProtectedBroadcast(fieldOnIntent);
                    if (mIsSecureNfcEnabled) {
                        sendRequireUnlockIntent();
                    }
                    break;
                case MSG_RF_FIELD_DEACTIVATED:
                    Intent fieldOffIntent = new Intent(ACTION_RF_FIELD_OFF_DETECTED);
                    sendNfcPermissionProtectedBroadcast(fieldOffIntent);
                    break;
                case MSG_SRD_EVT_TIMEOUT:
                    Intent srdTimeoutIntent = new Intent(ACTION_SRD_EVT_TIMEOUT);
                    sendNfcPermissionProtectedBroadcast(srdTimeoutIntent);
                    break;
                case MSG_SRD_EVT_FEATURE_NOT_SUPPORT:
                    Intent srdFeatureNotSupported = new Intent(ACTION_SRD_EVT_FEATURE_NOT_SUPPORT);
                    sendNfcPermissionProtectedBroadcast(srdFeatureNotSupported);
                   break;
                case MSG_EFDM_EVT_TIMEOUT:
                    Intent efdmTimeoutIntent = new Intent(ACTION_EXTENDED_FIELD_TIMEOUT);
                    sendNfcPermissionProtectedBroadcast(efdmTimeoutIntent);
                    break;
                case MSG_TAG_ABORT_OPERATION:
                    Log.d(TAG, "Tag disconnect requested by Remote NFC End Point");
                    maybeDisconnectTarget();
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

                case MSG_APPLY_SCREEN_STATE:
                    mScreenState = (Integer)msg.obj;
                    Log.d(TAG, "MSG_APPLY_SCREEN_STATE " + mScreenState);

                    synchronized (NfcService.this) {
                        // Disable delay polling when screen state changed
                        mPollDelayed = false;
                        mHandler.removeMessages(MSG_DELAY_POLLING);
                        // If NFC is turning off, we shouldn't need any changes here
                        if (mState == NfcAdapter.STATE_TURNING_OFF || mState == NfcAdapter.STATE_OFF)
                            return;
                    }
                    mRoutingWakeLock.acquire();
                    try {
                        if (nci_version == NCI_VERSION_1_0) {
                            if (mScreenState == mPreviousScreenState) {
                                Log.d(TAG,
                                    "Current:" + mScreenState + " and previous:" + mPreviousScreenState
                                        + " screen states are same. No need to update");
                                break;
                            }
                            mDeviceHost.disableDiscovery();
                            mDeviceHost.doSetScreenState(mScreenState);
                            NfcDiscoveryParameters params = computeDiscoveryParameters(mScreenState);
                            boolean shouldRestart = mCurrentDiscoveryParameters.shouldEnableDiscovery();
                            mDeviceHost.enableDiscovery(params, shouldRestart);
                            mPreviousScreenState = mScreenState;
                            break;
                        }
                        if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                            applyRouting(false);
                            mIsRequestUnlockShowed = false;
                        }
                        int screen_state_mask = (mNfcUnlockManager.isLockscreenPollingEnabled()) ?
                                (ScreenStateHelper.SCREEN_POLLING_TAG_MASK | mScreenState) : mScreenState;

                       if (mNfcUnlockManager.isLockscreenPollingEnabled())
                           applyRouting(false);

                       mDeviceHost.doSetScreenState(screen_state_mask);
                   } finally {
                      mRoutingWakeLock.release();
                   }
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

                case MSG_DELAY_POLLING:
                  synchronized (NfcService.this) {
                    if (!mPollDelayed) {
                      return;
                    }
                    mPollDelayed = false;
                    mDeviceHost.startStopPolling(true);
                  }
                  if (DBG)
                    Log.d(TAG, "Polling is started");
                  break;

                case MSG_CLEAR_ROUTING_TABLE:
                  if (DBG) Log.d(TAG, "Clear routing table");
                  int clearFlags = (Integer)msg.obj;
                  mDeviceHost.clearRoutingEntry(clearFlags);
                  break;

                case MSG_UPDATE_ISODEP_PROTOCOL_ROUTE:
                  if (DBG) Log.d(TAG, "Update IsoDep Protocol Route");
                  mDeviceHost.setIsoDepProtocolRoute((Integer)msg.obj);
                  break;
                case MSG_UPDATE_TECHNOLOGY_AB_ROUTE:
                  if (DBG) Log.d(TAG, "Update technology A&B route");
                  mDeviceHost.setTechnologyABRoute((Integer)msg.obj);
                  break;

                case MSG_SE_INIT:
                  Log.e(TAG, "msg se init");

                  try {
                    if (isNfcEnabledOrEnabling() && mIsHceCapable) {
                        // Dynamic routing Table update
                        Log.d(TAG, "Update routing table");
                        mAidRoutingManager.onNfccRoutingTableCleared();
                        mDeviceHost.clearRoutingEntry(AID_ENTRY);
                        mDeviceHost.clearRoutingEntry(TECH_ENTRY);
                        mDeviceHost.clearRoutingEntry(PROTOCOL_ENTRY);
                        // Generate the initial card emulation routing table
                        computeRoutingParameters();
                        // If Nfc is in ON state then onNfcEnabled()
                        // internally triggers the commitRouting()
                        mCardEmulationManager.onNfcEnabled();
                        if (getLastCommitRoutingStatus() == false) {
                          commitRouting();
                        }
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
               case MSG_LX_DATA_RECEIVED: {
                 /* Send broadcast ordered */
                 Bundle writeBundle = (Bundle) msg.obj;
                 byte[] lxDbgCfgsData = writeBundle.getByteArray("LxDbgData");
                 int lxDbgDataLen = writeBundle.getInt("length");
                 Intent lxDataRecvdIntent = new Intent();
                 lxDataRecvdIntent.putExtra("LxDebugCfgs",lxDbgCfgsData);
                 lxDataRecvdIntent.putExtra("lxDbgDataLen",lxDbgDataLen);
                 lxDataRecvdIntent.setAction(ACTION_LX_DATA_RECVD);
                 mContext.sendBroadcast(lxDataRecvdIntent);
                 break;
               }
               case MSG_WLC_ENABLE:
                 mWlc.enable(WlcServiceProxy.PersistStatus.UPDATE);
                 break;
               case MSG_WLC_DISABLE:
                mWlc.disable(WlcServiceProxy.PersistStatus.UPDATE);
                break;
                case MSG_TXLDO_OVERCORRENT_RECOVERY:
                    mDeviceHost.restartRFDiscovery();
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
            String reader = "";
            int uid = -1;
            int offhostCategory = NfcStatsLog.NFC_CARDEMULATION_OCCURRED__CATEGORY__OFFHOST;
            try {
                StringBuilder aidString = new StringBuilder(aid.length);
                for (byte b : aid) {
                    aidString.append(String.format("%02X", b));
                }

                String aidCategory = mCardEmulationManager
                        .getRegisteredAidCategory(aidString.toString());
                if (DBG) Log.d(TAG, "aid cateogry: " + aidCategory);
                if (mStatsdUtils != null) {
                    mStatsdUtils.setCardEmulationEventCategory(aidCategory);
                } else {
                    switch (aidCategory) {
                        case CardEmulation.CATEGORY_PAYMENT:
                            offhostCategory = NfcStatsLog
                                  .NFC_CARDEMULATION_OCCURRED__CATEGORY__OFFHOST_PAYMENT;
                            break;
                        case CardEmulation.CATEGORY_OTHER:
                            offhostCategory = NfcStatsLog
                                    .NFC_CARDEMULATION_OCCURRED__CATEGORY__OFFHOST_OTHER;
                            break;
                        default:
                            offhostCategory = NfcStatsLog
                                .NFC_CARDEMULATION_OCCURRED__CATEGORY__OFFHOST;
                    };
                }

                reader = new String(readerByteArray, "UTF-8");

                if (!isSEServiceAvailable() || mNfcEventInstalledPackages.isEmpty()) {
                    return;
                }

                for (int userId : mNfcEventInstalledPackages.keySet()) {
                    List<String> packagesOfUser = mNfcEventInstalledPackages.get(userId);
                    String[] installedPackages = new String[packagesOfUser.size()];
                    boolean[] nfcAccess = mSEService.isNfcEventAllowed(reader, aid,
                            packagesOfUser.toArray(installedPackages), userId);
                    if (nfcAccess == null) {
                        continue;
                    }
                    Intent intent = new Intent(NfcAdapter.ACTION_TRANSACTION_DETECTED);
                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.putExtra(NfcAdapter.EXTRA_AID, aid);
                    intent.putExtra(NfcAdapter.EXTRA_DATA, data);
                    intent.putExtra(NfcAdapter.EXTRA_SECURE_ELEMENT_NAME, reader);
                    String url =
                            new String("nfc://secure:0/" + reader + "/" + aidString.toString());
                    intent.setData(Uri.parse(url));

                    final BroadcastOptions options = BroadcastOptions.makeBasic();
                    options.setBackgroundActivityStartsAllowed(true);

                    Map<String, Integer> hasIntentPackages = mContext
                            .getPackageManager()
                            .queryBroadcastReceiversAsUser(intent, 0, UserHandle.of(userId))
                            .stream()
                            .collect(Collectors.toMap(
                                      activity -> activity.activityInfo.applicationInfo.packageName,
                                      activity -> activity.activityInfo.applicationInfo.uid,
                                      (packageName1, packageName2) -> {
                                          if (DBG) {
                                              Log.d(TAG,
                                                      "queryBroadcastReceiversAsUser duplicate: " +
                                                      packageName1 + ", " + packageName2);
                                          }
                                          return packageName1;
                                      }));
                    if (DBG) {
                        String[] packageNames = hasIntentPackages
                                .keySet().toArray(new String[hasIntentPackages.size()]);
                        Log.d(TAG,
                                "queryBroadcastReceiversAsUser: " + Arrays.toString(packageNames));
                    }

                    boolean foundFirstPackage = false;
                    for (int i = 0; i < nfcAccess.length; i++) {
                        if (nfcAccess[i]) {
                            String packageName = packagesOfUser.get(i);
                            if (DBG) {
                                Log.d(TAG, "sendOffHostTransactionEvent to " + packageName);
                            }
                            if (!foundFirstPackage && hasIntentPackages.containsKey(packageName)) {
                                uid = hasIntentPackages.get(packageName);
                                if (mStatsdUtils != null) {
                                    mStatsdUtils.setCardEmulationEventUid(uid);
                                }
                                foundFirstPackage = true;
                            }
                            intent.setPackage(packagesOfUser.get(i));
                            mContext.sendBroadcastAsUser(intent, UserHandle.of(userId), null,
                                    options.toBundle());
                        }
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error in isNfcEventAllowed() " + e);
            } catch (UnsupportedEncodingException e) {
                Log.e(TAG, "Incorrect format for Secure Element name" + e);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error " + e);
            } finally {
                if (mStatsdUtils != null) {
                    mStatsdUtils.logCardEmulationOffhostEvent(reader);
                } else {
                    NfcStatsLog.write(NfcStatsLog.NFC_CARDEMULATION_OCCURRED,
                            offhostCategory,
                            reader,
                            uid);
                }
            }
        }

        private void sendNfcPermissionProtectedBroadcast(Intent intent) {
            if (mNfcEventInstalledPackages.isEmpty()) {
                return;
            }
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            for (int userId : mNfcEventInstalledPackages.keySet()) {
                for (String packageName : mNfcEventInstalledPackages.get(userId)) {
                    intent.setPackage(packageName);
                    mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
                }
            }
        }

        /* Returns the list of packages request for nfc preferred payment service changed and
         * have access to NFC Events on any SE */
        private ArrayList<String> getNfcPreferredPaymentChangedSEAccessAllowedPackages(int userId) {
            if (!isSEServiceAvailable()
                    || mNfcPreferredPaymentChangedInstalledPackages.get(userId).isEmpty()) {
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
            List<String> packagesOfUser = mNfcPreferredPaymentChangedInstalledPackages.get(userId);
            String[] installedPackages = new String[packagesOfUser.size()];

            for (String reader : readers) {
                try {
                    boolean[] accessList = mSEService.isNfcEventAllowed(reader, null,
                            packagesOfUser.toArray(installedPackages), userId
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
                    Log.e(TAG, "Error in isNfcEventAllowed() " + e);
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
                    packages.add(packagesOfUser.get(i));
                }
            }
            return packages;
        }

        private boolean isSystemApp(ApplicationInfo applicationInfo) {
             return ((applicationInfo.flags & APP_INFO_FLAGS_SYSTEM_APP) != 0);
        }

        private void sendPreferredPaymentChangedEvent(Intent intent) {
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            // Resume app switches so the receivers can start activities without delay
            mNfcDispatcher.resumeAppSwitches();
            synchronized (this) {
                for (int userId : mNfcPreferredPaymentChangedInstalledPackages.keySet()) {
                    ArrayList<String> SEPackages =
                            getNfcPreferredPaymentChangedSEAccessAllowedPackages(userId);
                    UserHandle userHandle = UserHandle.of(userId);
                    if (SEPackages != null && !SEPackages.isEmpty()) {
                        for (String packageName : SEPackages) {
                            intent.setPackage(packageName);
                            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                            mContext.sendBroadcastAsUser(intent, userHandle);
                        }
                    }
                    PackageManager pm;
                    try {
                        pm = mContext.createContextAsUser(userHandle, /*flags=*/0)
                                .getPackageManager();
                    } catch (IllegalStateException e) {
                        Log.d(TAG, "Fail to get PackageManager for user: " + userHandle);
                        continue;
                    }
                    for (String pkgName :
                            mNfcPreferredPaymentChangedInstalledPackages.get(userId)) {
                        try {
                            PackageInfo info = pm.getPackageInfo(pkgName, 0);
                            if (SEPackages != null && SEPackages.contains(pkgName)) {
                                continue;
                            }
                            if (info.applicationInfo != null
                                    && (isSystemApp(info.applicationInfo)
                                    || info.applicationInfo.isPrivilegedApp())) {
                                intent.setPackage(pkgName);
                                intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                                mContext.sendBroadcastAsUser(intent, userHandle);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception in getPackageInfo " + e);
                        }
                    }
                }
            }
        }

        private void dispatchTagEndpoint(TagEndpoint tagEndpoint, ReaderModeParams readerParams) {
            try {
                /* Avoid setting mCookieUpToDate to negative values */
                mCookieUpToDate = mCookieGenerator.nextLong() >>> 1;
                Tag tag = new Tag(tagEndpoint.getUid(), tagEndpoint.getTechList(),
                        tagEndpoint.getTechExtras(), tagEndpoint.getHandle(),
                        mCookieUpToDate, mNfcTagService);
                registerTagObject(tagEndpoint);
                if (readerParams != null) {
                    try {
                        if ((readerParams.flags & NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS) == 0) {
                            mVibrator.vibrate(mVibrationEffect,
                                    HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES);
                            playSound(SOUND_END);
                        }
                        if (readerParams.callback != null) {
                            if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                                mPowerManager.userActivity(SystemClock.uptimeMillis(),
                                        PowerManager.USER_ACTIVITY_EVENT_OTHER, 0);
                            }
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
                if (dispatchResult == NfcDispatcher.DISPATCH_FAIL && !mInProvisionMode
                        && !isEndPointRemovalDetectionSupported()) {
                    if (DBG) Log.d(TAG, "Tag dispatch failed");
                    unregisterObject(tagEndpoint.getHandle());
                    if (mPollDelayTime > NO_POLL_DELAY) {
                        tagEndpoint.stopPresenceChecking();
                        synchronized (NfcService.this) {
                            if (!mPollDelayed) {
                                int delayTime = mPollDelayTime;
                                mPollDelayed = true;
                                mDeviceHost.startStopPolling(false);
                                if (mPollDelayCount < mPollDelayCountMax) {
                                    mPollDelayCount++;
                                } else {
                                    delayTime = mPollDelayTimeLong;
                                }
                                if (DBG) Log.d(TAG, "Polling delayed " + delayTime);
                                mHandler.sendMessageDelayed(
                                        mHandler.obtainMessage(MSG_DELAY_POLLING), delayTime);
                            } else {
                                if (DBG) Log.d(TAG, "Keep waiting for polling delay");
                            }
                        }
                    } else {
                        Log.d(TAG, "Keep presence checking.");
                    }
                    if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED && mNotifyDispatchFailed) {
                        if (!sToast_debounce) {
                            Toast.makeText(mContext, R.string.tag_dispatch_failed,
                                           Toast.LENGTH_SHORT).show();
                            sToast_debounce = true;
                            mHandler.sendEmptyMessageDelayed(MSG_TOAST_DEBOUNCE_EVENT,
                                                             sToast_debounce_time_ms);
                        }
                        playSound(SOUND_ERROR);
                    }
                    if (!mAntennaBlockedMessageShown && mDispatchFailedCount++ > mDispatchFailedMax) {
                        new NfcBlockedNotification(mContext).startNotification();
                        synchronized (NfcService.this) {
                            mPrefsEditor.putBoolean(PREF_ANTENNA_BLOCKED_MESSAGE_SHOWN, true);
                            mPrefsEditor.apply();
                        }
                        mBackupManager.dataChanged();
                        mAntennaBlockedMessageShown = true;
                        mDispatchFailedCount = 0;
                        if (DBG) Log.d(TAG, "Tag dispatch failed notification");
                    }
                } else if (dispatchResult == NfcDispatcher.DISPATCH_SUCCESS) {
                    synchronized (NfcService.this) {
                        mPollDelayCount = 0;
                    }
                    if (mScreenState == ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED) {
                        mPowerManager.userActivity(SystemClock.uptimeMillis(),
                                PowerManager.USER_ACTIVITY_EVENT_OTHER, 0);
                    }
                    mDispatchFailedCount = 0;
                    mVibrator.vibrate(mVibrationEffect, HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES);
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

    boolean isEndPointRemovalDetectionSupported() {
        if(!mDeviceHost.isRemovalDetectionInPollModeSupported()) {
            Log.d(TAG, "Removal Detection mode is not supported");
            return false;
        }
        if (!(mAppInActivityDetectionTime >= MIN_RF_REMOVAL_DETECTION_TIMEOUT &&
                (mTagRemovalDetectionWaitTime >= MIN_RF_REMOVAL_DETECTION_TIMEOUT &&
                 mTagRemovalDetectionWaitTime <= MAX_RF_REMOVAL_DETECTION_TIMEOUT))) {
            Log.d(TAG, "Unexpected Removal Detection wait time");
            return false;
        }
        return true;
    }

    void scheduleAppInactivityDetectionTask() {
        if (isEndPointRemovalDetectionSupported()) {
            clearAppInactivityDetectionContext();
            mAppInActivityDetectionTimer = new Timer();
            AppInActivityHandlerTask task = new AppInActivityHandlerTask();
            mAppInActivityDetectionTimer.schedule(task,mAppInActivityDetectionTime);
            Log.d(TAG, "App Inactivity detection task is scheduled");
        }
    }

    boolean checkAndHandleRemovalDetectionMode(boolean isDisconnectNeeded) {
        if (mAppInActivityDetectionTimer != null) {
            if (IsPresenceCheckStopped()) {
                Log.d(TAG, "Removal detection state");
                if (isDisconnectNeeded) {
                    Log.d(TAG, "Restarting discovery..");
                    maybeDisconnectTarget();
                    return true;
                }
            } else {
                Log.d(TAG, "Clearing Removal Detection Timer Context");
                clearAppInactivityDetectionContext();
            }
        }
        return false;
    }

    class AppInActivityHandlerTask extends TimerTask {
        public void run()
        {
            boolean isTagInProximity = false;
            Log.d(TAG, "App Inactivity detected, Requesting to Start Removal Detection Procedure");
            if (isTagPresent()) {
                PrepareForRemovalDetectionMode();
                mToastHandler.showToast("No activity over reader mode, RF removal detection procedure started", 10);
                /* Request JNI to start remove detection procedure */
                mDeviceHost.startRemovalDetectionProcedure(mTagRemovalDetectionWaitTime);
            } else {
                clearAppInactivityDetectionContext();
            }
        }
    }

    void clearAppInactivityDetectionContext() {
        if (mAppInActivityDetectionTimer != null) {
            mAppInActivityDetectionTimer.cancel();
            mAppInActivityDetectionTimer = null;
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
                if (action.equals(Intent.ACTION_SCREEN_ON)) {
                    synchronized (NfcService.this) {
                        mPollDelayCount = 0;
                    }
                }
                applyScreenState(mScreenStateHelper.checkScreenState());
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                mUserId = userId;
                updatePackageCache();
                if (mIsHceCapable) {
                    mCardEmulationManager.onUserSwitched(getUserId());
                }
                applyScreenState(mScreenStateHelper.checkScreenState());

                if (NFC_SNOOP_LOG_MODE.equals(NfcProperties.snoop_log_mode_values.FULL) ||
                        NFC_VENDOR_DEBUG_ENABLED) {
                    new NfcDeveloperOptionNotification(mContext.createContextAsUser(
                            UserHandle.of(ActivityManager.getCurrentUser()), /*flags=*/0))
                            .startNotification();
                }
            } else if (action.equals(Intent.ACTION_USER_ADDED)) {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                setPaymentForegroundPreference(userId);

                if (NFC_SNOOP_LOG_MODE.equals(NfcProperties.snoop_log_mode_values.FULL) ||
                        NFC_VENDOR_DEBUG_ENABLED) {
                    new NfcDeveloperOptionNotification(mContext.createContextAsUser(
                            UserHandle.of(ActivityManager.getCurrentUser()), /*flags=*/0))
                            .startNotification();
                }
            }
        }
    };

    private final BroadcastReceiver mManagedProfileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);

            // User should be filled for below intents, check the existence.
            if (user == null) {
                Log.d(TAG, intent.getAction() + " broadcast without EXTRA_USER.");
                return;
            }

            if (mCardEmulationManager == null) {
                return;
            }
            if (action.equals(Intent.ACTION_MANAGED_PROFILE_ADDED) ||
                    action.equals(Intent.ACTION_MANAGED_PROFILE_AVAILABLE) ||
                    action.equals(Intent.ACTION_MANAGED_PROFILE_REMOVED) ||
                    action.equals(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)) {
                mCardEmulationManager.onManagedProfileChanged();
                setPaymentForegroundPreference(user.getIdentifier());
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
                if (action.equals(Intent.ACTION_PACKAGE_REMOVED)
                        && renewTagAppPrefList()) storeTagAppPrefList();
            } else if (action.equals(Intent.ACTION_SHUTDOWN)) {
                if (DBG) Log.d(TAG, "Shutdown received with UserId: " +
                                 getSendingUser().getIdentifier());
                if (!getSendingUser().equals(UserHandle.ALL)) {
                    return;
                }
                if (DBG) Log.d(TAG, "Device is shutting down.");
                if (isNfcEnabled()) {
                    mDeviceHost.shutdown();
                }
            }
        }
    };

    private void applyScreenState(int screenState) {
        if(mFeatureFlags.reduceStateTransition() &&
                mIsWatchType && !mCardEmulationManager.isRequiresScreenOnServiceExist()) {
            if (screenState == ScreenStateHelper.SCREEN_STATE_OFF_LOCKED) {
                screenState = ScreenStateHelper.SCREEN_STATE_ON_LOCKED;
            } else if (screenState == ScreenStateHelper.SCREEN_STATE_OFF_UNLOCKED) {
                screenState = ScreenStateHelper.SCREEN_STATE_ON_UNLOCKED;
            }
        }
        if (mScreenState != screenState) {
            if (nci_version != NCI_VERSION_2_0) {
                new ApplyRoutingTask().execute(Integer.valueOf(screenState));
            }
            sendMessage(NfcService.MSG_APPLY_SCREEN_STATE, screenState);
        }
    }

    private void setPaymentForegroundPreference(int user) {
        Context uc = mContext.createContextAsUser(UserHandle.of(user), 0);
        try {
            // Check whether the Settings.Secure.NFC_PAYMENT_FOREGROUND exists or not.
            Settings.Secure.getInt(uc.getContentResolver(),
                    Constants.SETTINGS_SECURE_NFC_PAYMENT_FOREGROUND);
        } catch (SettingNotFoundException e) {
            boolean foregroundPreference =
                    mContext.getResources().getBoolean(R.bool.payment_foreground_preference);
            Settings.Secure.putInt(uc.getContentResolver(),
                    Constants.SETTINGS_SECURE_NFC_PAYMENT_FOREGROUND, foregroundPreference ? 1 : 0);
            Log.d(TAG, "Set NFC_PAYMENT_FOREGROUND preference:" + foregroundPreference);
        }
    }

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

    static int stateToProtoEnum(int state) {
        switch (state) {
            case NfcAdapter.STATE_OFF:
                return NfcServiceDumpProto.STATE_OFF;
            case NfcAdapter.STATE_TURNING_ON:
                return NfcServiceDumpProto.STATE_TURNING_ON;
            case NfcAdapter.STATE_ON:
                return NfcServiceDumpProto.STATE_ON;
            case NfcAdapter.STATE_TURNING_OFF:
                return NfcServiceDumpProto.STATE_TURNING_OFF;
            default:
                return NfcServiceDumpProto.STATE_UNKNOWN;
        }
    }

    private void copyNativeCrashLogsIfAny(PrintWriter pw) {
      try {
          File file = new File(NATIVE_LOG_FILE_PATH, NATIVE_LOG_FILE_NAME);
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

    public void storeNativeCrashLogs() {
      FileOutputStream fos = null;
      try {
        File file = new File(NATIVE_LOG_FILE_PATH, NATIVE_LOG_FILE_NAME);
        if (file.length() >= NATIVE_CRASH_FILE_SIZE) {
          file.createNewFile();
        }

        fos = new FileOutputStream(file, true);
        mDeviceHost.dump(fos.getFD());
        fos.flush();
      } catch (IOException e) {
        Log.e(TAG, "Exception in storeNativeCrashLogs " + e);
      } finally {
        if (fos != null) {
          try {
            fos.close();
          } catch (IOException e) {
            Log.e(TAG, "Exception in storeNativeCrashLogs " + e);
          }
        }
      }
    }

    private void dumpTagAppPreference(PrintWriter pw) {
        pw.println("mIsTagAppPrefSupported =" + mIsTagAppPrefSupported);
        if (mIsTagAppPrefSupported) {
            pw.println("TagAppPreference:");
            UserManager um = mContext.createContextAsUser(
                    UserHandle.of(ActivityManager.getCurrentUser()), 0)
                    .getSystemService(UserManager.class);
            List<UserHandle> luh = um.getEnabledProfiles();
            for (UserHandle uh : luh) {
                int userId = uh.getIdentifier();
                HashMap<String, Boolean> map;
                synchronized (NfcService.this) {
                    map = mTagAppPrefList.getOrDefault(userId, new HashMap<>());
                }
                if (map.size() > 0) pw.println("userId=" + userId);
                for (Map.Entry<String, Boolean> entry : map.entrySet()) {
                    pw.println("pkg: " + entry.getKey() + " : " + entry.getValue());
                }
            }
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

        for (String arg : args) {
            if ("--proto".equals(arg)) {
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(fd);
                    ProtoOutputStream proto = new ProtoOutputStream(fos);
                    synchronized (this) {
                        dumpDebug(proto);
                    }
                    proto.flush();
                } catch (Exception e) {
                    Log.e(TAG, "Exception in dump nfc --proto " + e);
                } finally {
                    if (fos != null) {
                        try { fos.close(); }
                        catch(IOException e) {
                        Log.e(TAG, "Exception in storeNativeCrashLogs " + e);
                        }
                    }
                }
                return;
            }
        }

        synchronized (this) {
            pw.println("mState=" + stateToString(mState));
            pw.println("mScreenState=" + ScreenStateHelper.screenStateToString(mScreenState));
            pw.println("mIsSecureNfcEnabled=" + mIsSecureNfcEnabled);
            pw.println("mIsReaderOptionEnabled=" + mIsReaderOptionEnabled);
            pw.println("mIsAlwaysOnSupported=" + mIsAlwaysOnSupported);
            pw.println("SnoopLogMode=" + NFC_SNOOP_LOG_MODE);
            pw.println("VendorDebugEnabled=" + NFC_VENDOR_DEBUG_ENABLED);
            pw.println("mIsPowerSavingModeEnabled=" + mIsPowerSavingModeEnabled);
            pw.println(mCurrentDiscoveryParameters);
            if (mIsHceCapable) {
                mCardEmulationManager.dump(fd, pw, args);
            }
            mNfcDispatcher.dump(fd, pw, args);
            if (mState == NfcAdapter.STATE_ON) {
                mRoutingTableParser.dump(mDeviceHost, pw);
            }
            dumpTagAppPreference(pw);
            copyNativeCrashLogsIfAny(pw);
            pw.flush();
            mDeviceHost.dump(fd);
        }
    }

    public void updateDefaultAidRoute(int routeLoc) {
        Log.d(TAG, "updateDefaultAidRoute routeLoc:" + routeLoc);
        boolean isOverflow = (routeLoc != (GetDefaultRouteEntry() >> ROUTE_LOC_MASK));

        if (!isOverflow)
            mOverflowDefaultRoute = ROUTE_INVALID;
        else {
            mOverflowDefaultRoute =
                ((mDeviceHost.getDefaultAidPowerState() & 0x3F) | (routeLoc << ROUTE_LOC_MASK));
            if (routeLoc == 0x00) {
                /*
                bit pos 1 = Power Off
                bit pos 2 = Battery Off
                bit pos 4 = Screen Off
                Set these bits to 0 because in case routeLoc = HOST it can not work on POWER_OFF,
                BATTERY_OFF and SCREEN_OFF*/
                mOverflowDefaultRoute &= 0xF9;
            }
        }
        mHandler.sendEmptyMessage(MSG_RESET_AND_UPDATE_ROUTING_PARAMS);
    }

    public void addT4TNfceeAid() {
      Log.i(TAG, "Add T4T Nfcee AID");

      routeAids(T4T_NFCEE_AID, ROUTE_ID_T4T_NFCEE,
              AID_MATCHING_EXACT_ONLY,
              GetT4TNfceePowerState());
    }

    /**
     * Dump debugging information as a NfcServiceDumpProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/nfc_service.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    private void dumpDebug(ProtoOutputStream proto) {
        proto.write(NfcServiceDumpProto.STATE, stateToProtoEnum(mState));
        proto.write(NfcServiceDumpProto.IN_PROVISION_MODE, mInProvisionMode);
        proto.write(NfcServiceDumpProto.SCREEN_STATE,
                ScreenStateHelper.screenStateToProtoEnum(mScreenState));
        proto.write(NfcServiceDumpProto.SECURE_NFC_ENABLED, mIsSecureNfcEnabled);
        proto.write(NfcServiceDumpProto.POLLING_PAUSED, mPollingPaused);
        proto.write(NfcServiceDumpProto.HCE_CAPABLE, mIsHceCapable);
        proto.write(NfcServiceDumpProto.HCE_F_CAPABLE, mIsHceFCapable);
        proto.write(NfcServiceDumpProto.SECURE_NFC_CAPABLE, mIsSecureNfcCapable);
        proto.write(NfcServiceDumpProto.VR_MODE_ENABLED,
                (mVrManager != null) ? mVrManager.isVrModeEnabled() : false);

        long token = proto.start(NfcServiceDumpProto.DISCOVERY_PARAMS);
        mCurrentDiscoveryParameters.dumpDebug(proto);
        proto.end(token);

        if (mIsHceCapable) {
            token = proto.start(NfcServiceDumpProto.CARD_EMULATION_MANAGER);
            mCardEmulationManager.dumpDebug(proto);
            proto.end(token);
        }

        token = proto.start(NfcServiceDumpProto.NFC_DISPATCHER);
        mNfcDispatcher.dumpDebug(proto);
        proto.end(token);

        // Dump native crash logs if any
        File file = new File(NATIVE_LOG_FILE_PATH, NATIVE_LOG_FILE_NAME);
        if (!file.exists()) {
            return;
        }
        try {
            String logs = Files.lines(file.toPath()).collect(Collectors.joining("\n"));
            proto.write(NfcServiceDumpProto.NATIVE_CRASH_LOGS, logs);
        } catch (IOException e) {
            Log.e(TAG, "IOException in dumpDebug(ProtoOutputStream): " + e);
        }
    }

    private int runTaskOnSingleThreadExecutor(FutureTask<Integer> task, int timeoutMs)
            throws InterruptedException, TimeoutException, ExecutionException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            executor.shutdownNow();
            throw e;
        }
    }
}
