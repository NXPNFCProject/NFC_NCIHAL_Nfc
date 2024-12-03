/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.nfc;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.backup.BackupManager;
import android.content.ApexEnvironment;
import android.content.Context;
import android.content.res.Resources;
import android.nfc.Constants;
import android.nfc.NfcFrameworkInitializer;
import android.nfc.NfcServiceManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.se.omapi.ISecureElementService;
import android.se.omapi.SeFrameworkInitializer;
import android.se.omapi.SeServiceManager;
import android.util.AtomicFile;
import android.util.Log;

import com.android.nfc.cardemulation.util.StatsdUtils;
import com.android.nfc.dhimpl.NativeNfcManager;
import com.android.nfc.flags.FeatureFlags;
import com.android.nfc.handover.HandoverDataParser;

import java.io.File;
import java.time.LocalDateTime;

/**
 * To be used for dependency injection (especially helps mocking static dependencies).
 * TODO: Migrate more classes to injector to resolve circular dependencies in the NFC stack.
 */
public class NfcInjector {
    private static final String TAG = "NfcInjector";
    private static final String APEX_NAME = "com.android.nfcservices";
    private static final String NFC_DATA_DIR = "/data/nfc";
    private static final String EVENT_LOG_FILE_NAME = "event_log.binpb";

    private final Context mContext;
    private final Looper mMainLooper;
    private final NfcEventLog mNfcEventLog;
    private final RoutingTableParser mRoutingTableParser;
    private final ScreenStateHelper mScreenStateHelper;
    private final NfcUnlockManager mNfcUnlockManager;
    private final HandoverDataParser mHandoverDataParser;
    private final DeviceConfigFacade mDeviceConfigFacade;
    private final NfcDispatcher mNfcDispatcher;
    private final VibrationEffect mVibrationEffect;
    private final BackupManager mBackupManager;
    private final FeatureFlags mFeatureFlags;
    private final StatsdUtils mStatsdUtils;
    private final ForegroundUtils mForegroundUtils;
    private final NfcDiagnostics mNfcDiagnostics;
    private final NfcServiceManager.ServiceRegisterer mNfcManagerRegisterer;
    private final NfcWatchdog mNfcWatchdog;
    private static NfcInjector sInstance;

    public static NfcInjector getInstance() {
        if (sInstance == null) throw new IllegalStateException("Nfc injector instance null");
        return sInstance;
    }


    public NfcInjector(@NonNull Context context, @NonNull Looper mainLooper) {
        if (sInstance != null) throw new IllegalStateException("Nfc injector instance not null");

        mContext = context;
        mMainLooper = mainLooper;
        mRoutingTableParser = new RoutingTableParser();
        mScreenStateHelper = new ScreenStateHelper(mContext);
        mNfcUnlockManager = NfcUnlockManager.getInstance();
        mHandoverDataParser = new HandoverDataParser();
        mDeviceConfigFacade = new DeviceConfigFacade(mContext, new Handler(mainLooper));
        mNfcDispatcher =
            new NfcDispatcher(mContext, mHandoverDataParser, this, isInProvisionMode());
        mVibrationEffect = VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE);
        mBackupManager = new BackupManager(mContext);
        mFeatureFlags = new com.android.nfc.flags.FeatureFlagsImpl();
        mStatsdUtils = mFeatureFlags.statsdCeEventsFlag() ? new StatsdUtils() : null;
        mForegroundUtils =
                ForegroundUtils.getInstance(mContext.getSystemService(ActivityManager.class));
        mNfcDiagnostics = new NfcDiagnostics(mContext);

        NfcServiceManager manager = NfcFrameworkInitializer.getNfcServiceManager();
        if (manager == null) {
            Log.e(TAG, "NfcServiceManager is null");
            throw new UnsupportedOperationException();
        }
        mNfcManagerRegisterer = manager.getNfcManagerServiceRegisterer();

        // Create UWB event log thread.
        HandlerThread eventLogThread = new HandlerThread("NfcEventLog");
        eventLogThread.start();
        mNfcEventLog = new NfcEventLog(mContext, this, eventLogThread.getLooper(),
                new AtomicFile(new File(NFC_DATA_DIR, EVENT_LOG_FILE_NAME)));
        mNfcWatchdog = new NfcWatchdog(mContext);
        sInstance = this;
    }

    public Context getContext() {
        return mContext;
    }

    public Looper getMainLooper() {
        return mMainLooper;
    }

    public NfcEventLog getNfcEventLog() {
        return mNfcEventLog;
    }

    public ScreenStateHelper getScreenStateHelper() {
        return mScreenStateHelper;
    }

    public RoutingTableParser getRoutingTableParser() {
        return mRoutingTableParser;
    }

    public NfcUnlockManager getNfcUnlockManager() {
        return mNfcUnlockManager;
    }

    public HandoverDataParser getHandoverDataParser() {
        return mHandoverDataParser;
    }

    public DeviceConfigFacade getDeviceConfigFacade() {
        return mDeviceConfigFacade;
    }

    public NfcDispatcher getNfcDispatcher() {
        return mNfcDispatcher;
    }

    public VibrationEffect getVibrationEffect() {
        return mVibrationEffect;
    }

    public BackupManager getBackupManager() {
        return mBackupManager;
    }

    public FeatureFlags getFeatureFlags() {
        return mFeatureFlags;
    }

    public StatsdUtils getStatsdUtils() {
        return mStatsdUtils;
    }

    public ForegroundUtils getForegroundUtils() {
        return mForegroundUtils;
    }

    public NfcDiagnostics getNfcDiagnostics() {
        return mNfcDiagnostics;
    }

    public NfcServiceManager.ServiceRegisterer getNfcManagerRegisterer() {
        return mNfcManagerRegisterer;
    }

    public NfcWatchdog getNfcWatchdog() {
        return mNfcWatchdog;
    }

    public DeviceHost makeDeviceHost(DeviceHost.DeviceHostListener listener) {
        return new NativeNfcManager(mContext, listener);
    }

    /**
     * NFC apex DE folder.
     */
    public static File getDeviceProtectedDataDir() {
        return ApexEnvironment.getApexEnvironment(APEX_NAME)
                .getDeviceProtectedDataDir();
    }

    public LocalDateTime getLocalDateTime() {
        return LocalDateTime.now();
    }

    public boolean isInProvisionMode() {
        boolean isNfcProvisioningEnabled = false;
        try {
            isNfcProvisioningEnabled = mContext.getResources().getBoolean(
                    R.bool.enable_nfc_provisioning);
        } catch (Resources.NotFoundException e) {
        }

        if (isNfcProvisioningEnabled) {
            return Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0) == 0;
        } else {
            return false;
        }
    }

    public ISecureElementService connectToSeService() throws RemoteException {
        SeServiceManager manager = SeFrameworkInitializer.getSeServiceManager();
        if (manager == null) {
            Log.e(TAG, "SEServiceManager is null");
            return null;
        }
        return ISecureElementService.Stub.asInterface(
                manager.getSeManagerServiceRegisterer().get());
    }

    /**
     * Kill the NFC stack.
     */
    public void killNfcStack() {
        System.exit(0);
    }

    public boolean isSatelliteModeSensitive() {
        final String satelliteRadios =
                Settings.Global.getString(mContext.getContentResolver(),
                        Constants.SETTINGS_SATELLITE_MODE_RADIOS);
        return satelliteRadios == null || satelliteRadios.contains(Settings.Global.RADIO_NFC);
    }

    /** Returns true if satellite mode is turned on. */
    public boolean isSatelliteModeOn() {
        if (!isSatelliteModeSensitive()) return false;
        return Settings.Global.getInt(
                mContext.getContentResolver(), Constants.SETTINGS_SATELLITE_MODE_ENABLED, 0) == 1;
    }


    /**
     * Get the current time of the clock in milliseconds.
     *
     * @return Current time in milliseconds.
     */
    public long getWallClockMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Returns milliseconds since boot, including time spent in sleep.
     *
     * @return Current time since boot in milliseconds.
     */
    public long getElapsedSinceBootMillis() {
        return SystemClock.elapsedRealtime();
    }

    /**
     * Returns nanoseconds since boot, including time spent in sleep.
     *
     * @return Current time since boot in milliseconds.
     */
    public long getElapsedSinceBootNanos() {
        return SystemClock.elapsedRealtimeNanos();
    }

    /**
     * Temporary location to store nfc properties being added in Android 16 for OEM convergence.
     * Will move all of these together to libsysprop later to avoid multiple rounds of API reviews.
     */
    public static final class NfcProperties {
        private static final String NFC_EUICC_SUPPORTED_PROP_KEY = "ro.nfc.euicc_supported";

        public static boolean isEuiccSupported() {
            return SystemProperties.getBoolean(NFC_EUICC_SUPPORTED_PROP_KEY, true);
        }

    }
}