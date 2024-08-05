/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.BugreportManager;
import android.os.BugreportParams;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * A class to trigger bugreport and other logs for Nfc related failures
 */
public class NfcDiagnostics {
    private static final String TAG = "NfcDiagnostics";
    private static final int MS_IN_HOUR = 60 * 60 * 1000;
    public static final int DEFAULT_BUG_REPORT_MIN_INTERVAL_MS = 24 * MS_IN_HOUR;

    private final Context mContext;
    private final SystemBuildProperties mSystemBuildProperties;
    private long mLastBugReportTimeMs;

    static class SystemBuildProperties {
        /** @return if it is an eng build. */
        public boolean isEngBuild() {
            return Build.TYPE.equals("eng");
        }

        /** @return if it is an userdebug build. */
        public boolean isUserdebugBuild() {
            return Build.TYPE.equals("userdebug");
        }

        /** @return if it is a normal user build. */
        public boolean isUserBuild() {
            return Build.TYPE.equals("user");
        }
    }

    public NfcDiagnostics(Context context) {
        mContext = context;
        mSystemBuildProperties = new SystemBuildProperties();
    }

    /**
     * Take a bug report if it is in user debug build and there is no recent bug
     * report
     */
    public void takeBugReport(String bugTitle, String description) {
        if (!mSystemBuildProperties.isUserdebugBuild()) {
            Log.d(TAG, "Skip bugreport because it can be triggered only in userDebug build");
            return;
        }
        long currentTimeMs = getElapsedSinceBootMillis();
        long timeSinceLastUploadMs = currentTimeMs - mLastBugReportTimeMs;

        if (timeSinceLastUploadMs < DEFAULT_BUG_REPORT_MIN_INTERVAL_MS
                && mLastBugReportTimeMs > 0) {
            Log.d(TAG, "Bugreport was filed recently, Skip " + bugTitle);
            return;
        }

        if (!takeBugreportThroughBetterBug(bugTitle, description)) {
            takeBugreportThroughBugreportManager(bugTitle, description);
        }
    }

    private boolean takeBugreportThroughBetterBug(String bugTitle, String description) {
        Intent launchBetterBugIntent = new BetterBugIntentBuilder()
                .setIssueTitle(bugTitle)
                .setHappenedTimestamp(System.currentTimeMillis())
                .setAdditionalComment(description)
                .build();
        boolean isIntentUnSafe = mContext
                .getPackageManager().queryIntentActivities(launchBetterBugIntent, 0)
                .isEmpty();
        if (isIntentUnSafe) {
            Log.d(TAG, "intent is unsafe and skip bugreport from betterBug: " + bugTitle);
            return false;
        }

        long identity = Binder.clearCallingIdentity();
        try {
            launchBetterBugIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            mContext.startActivity(launchBetterBugIntent);
            Log.d(TAG, "Taking the bugreport through betterBug: " + bugTitle);
            mLastBugReportTimeMs = getElapsedSinceBootMillis();
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Error taking bugreport: " + e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private long getElapsedSinceBootMillis() {
        return SystemClock.elapsedRealtime();
    }

    private boolean takeBugreportThroughBugreportManager(String bugTitle, String description) {
        BugreportManager bugreportManager = mContext.getSystemService(BugreportManager.class);
        BugreportParams params = new BugreportParams(BugreportParams.BUGREPORT_MODE_FULL);
        try {
            bugreportManager.requestBugreport(params, bugTitle, description);
            Log.d(TAG, "Taking the bugreport through bugreportManager: " + bugTitle);
            mLastBugReportTimeMs = getElapsedSinceBootMillis();
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Error taking bugreport: ", e);
            return false;
        }
    }

    /**
     * @return the last time when the bug report is taken
     */
    long getLastBugReportTimeMs() {
        return mLastBugReportTimeMs;
    }

    /**
     * Builder for communicating with the betterbug.
     */
    private static class BetterBugIntentBuilder {

        private static final String ACTION_FILE_BUG_DEEPLINK =
                "com.google.android.apps.betterbug.intent.FILE_BUG_DEEPLINK";
        private static final boolean DEFAULT_AUTO_UPLOAD_ENABLED = false;
        private static final boolean DEFAULT_BUGREPORT_REQUIRED = true;
        private static final String DEFAULT_BUG_ASSIGNEE = "android-nfc-team@google.com";
        private static final long DEFAULT_COMPONENT_ID = 48448L;

        private static final String EXTRA_DEEPLINK = "EXTRA_DEEPLINK";
        private static final String EXTRA_ISSUE_TITLE = "EXTRA_ISSUE_TITLE";
        private static final String EXTRA_DEEPLINK_SILENT = "EXTRA_DEEPLINK_SILENT";
        private static final String EXTRA_ADDITIONAL_COMMENT = "EXTRA_ADDITIONAL_COMMENT";
        private static final String EXTRA_REQUIRE_BUGREPORT = "EXTRA_REQUIRE_BUGREPORT";
        private static final String EXTRA_HAPPENED_TIME = "EXTRA_HAPPENED_TIME";
        private static final String EXTRA_BUG_ASSIGNEE = "EXTRA_BUG_ASSIGNEE";
        private static final String EXTRA_COMPONENT_ID = "EXTRA_COMPONENT_ID";

        private final Intent mBetterBugIntent;

        BetterBugIntentBuilder() {
            mBetterBugIntent = new Intent().setAction(ACTION_FILE_BUG_DEEPLINK)
                    .putExtra(EXTRA_DEEPLINK, true);
            setAutoUpload(DEFAULT_AUTO_UPLOAD_ENABLED);
            setBugreportRequired(DEFAULT_BUGREPORT_REQUIRED);
            setBugAssignee(DEFAULT_BUG_ASSIGNEE);
            setComponentId(DEFAULT_COMPONENT_ID);
        }

        public BetterBugIntentBuilder setIssueTitle(String title) {
            mBetterBugIntent.putExtra(EXTRA_ISSUE_TITLE, title);
            return this;
        }

        public BetterBugIntentBuilder setAutoUpload(boolean autoUploadEnabled) {
            mBetterBugIntent.putExtra(EXTRA_DEEPLINK_SILENT, autoUploadEnabled);
            return this;
        }

        public BetterBugIntentBuilder setComponentId(long componentId) {
            mBetterBugIntent.putExtra(EXTRA_COMPONENT_ID, componentId);
            return this;
        }

        public BetterBugIntentBuilder setBugreportRequired(boolean isBugreportRequired) {
            mBetterBugIntent.putExtra(EXTRA_REQUIRE_BUGREPORT, isBugreportRequired);
            return this;
        }

        public BetterBugIntentBuilder setHappenedTimestamp(long happenedTimeSinceEpochMs) {
            mBetterBugIntent.putExtra(EXTRA_HAPPENED_TIME, happenedTimeSinceEpochMs);
            return this;
        }

       public BetterBugIntentBuilder setAdditionalComment(String additionalComment) {
            mBetterBugIntent.putExtra(EXTRA_ADDITIONAL_COMMENT, additionalComment);
            return this;
        }

        public BetterBugIntentBuilder setBugAssignee(String assignee) {
            mBetterBugIntent.putExtra(EXTRA_BUG_ASSIGNEE, assignee);
            return this;
        }

        public Intent build() {
            return mBetterBugIntent;
        }
    }
}
