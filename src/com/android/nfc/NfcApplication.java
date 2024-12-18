/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.content.pm.PackageManager;
import android.nfc.Constants;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;

import java.util.Iterator;
import java.util.List;

public class NfcApplication extends Application {

    static final String TAG = "NfcApplication";
    NfcService mNfcService;

    public NfcApplication() {

    }

    @Override
    public void onCreate() {
        super.onCreate();
        PackageManager pm = getApplicationContext().getPackageManager();
        if (!pm.hasSystemFeature(Constants.FEATURE_NFC_ANY)) {
                return;
        }

        boolean isMainProcess = false;
        // We start a service in a separate process to do
        // handover transfer. We don't want to instantiate an NfcService
        // object in those cases, hence check the name of the process
        // to determine whether we're the main NFC service, or the
        // handover process
        ActivityManager am = this.getSystemService(ActivityManager.class);
        List processes = am.getRunningAppProcesses();
        Iterator i = processes.iterator();
        while (i.hasNext()) {
            RunningAppProcessInfo appInfo = (RunningAppProcessInfo)(i.next());
            if (appInfo.pid == Process.myPid()) {
                isMainProcess =  (getPackageName().equals(appInfo.processName));
                break;
            }
        }
        if (UserHandle.myUserId() == 0 && isMainProcess) {
            mNfcService = new NfcService(this, new NfcInjector(this, Looper.myLooper()));
        }
    }
}
