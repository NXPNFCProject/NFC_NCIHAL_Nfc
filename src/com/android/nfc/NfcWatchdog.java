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
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** @hide */
public class NfcWatchdog extends BroadcastReceiver {
    static final String TAG = "NfcWatchdog";
    static final long NFC_SERVICE_TIMEOUT_MS = 1000;
    static final long NFC_MONITOR_INTERVAL = 60_000;
    static final String ACTION_WATCHDOG = "android.nfc.intent.action.WATCHDOG";

    CountDownLatch mCountDownLatch;
    private Intent mWatchdogIntent = new Intent(ACTION_WATCHDOG);

    NfcWatchdog(Context context) {
        if (android.nfc.Flags.nfcWatchdog()) {
            PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(
                            context, 0, mWatchdogIntent, PendingIntent.FLAG_IMMUTABLE);
            context.registerReceiver(this, new IntentFilter(ACTION_WATCHDOG),
                    Context.RECEIVER_EXPORTED);
            AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
            alarmManager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + NFC_MONITOR_INTERVAL,
                    NFC_MONITOR_INTERVAL,
                    pendingIntent);
        }
    }

    synchronized void  notifyHasReturned() {
        if (mCountDownLatch != null) {
            mCountDownLatch.countDown();
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_WATCHDOG.equals(intent.getAction())) {
            monitor();
        }
    }

    public synchronized void monitor() {
        if (mCountDownLatch != null) {
            return;
        }

        mCountDownLatch = new CountDownLatch(1);

        Thread testThread = new TestThread();
        new MonitorThread(testThread).start();
        testThread.start();
    }

    void killNfcProcess() {
        Log.i(TAG, "Killing nfc process.");
        Process.killProcess(Process.myPid());
    }

    class TestThread extends Thread {

        @Override
        public void run() {
            final NfcService nfcService = NfcService.getInstance();
            if (nfcService != null) {
                synchronized (nfcService) {
                    nfcService.sendMessage(NfcService.MSG_WATCHDOG_PING, NfcWatchdog.this);
                }
            }
        }
    }

    class MonitorThread extends Thread {
        Thread mTestThread;

        MonitorThread(@NonNull Thread testThread) {
            mTestThread = testThread;
        }

        @Override
        public void run() {
            try {
                if (!mCountDownLatch.await(NFC_SERVICE_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS)) {
                    killNfcProcess();
                }
                synchronized (NfcWatchdog.this) {
                    mCountDownLatch = null;
                }
            } catch (InterruptedException e) {
                Log.wtf(TAG, e);
            }
        }
    }
}
