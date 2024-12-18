/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.backup.BackupAgentHelper;
import android.app.backup.SharedPreferencesBackupHelper;
import android.content.Context;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;

public class NfcBackupAgent extends BackupAgentHelper {
    // Backup identifier
    static final String SHARED_PREFS_BACKUP_KEY = "shared_prefs";

    @Override
    public void onCreate() {
        SharedPreferencesBackupHelper helper =
                new SharedPreferencesBackupHelper(
                        this, NfcService.PREF, NfcService.PREF_TAG_APP_LIST);
        addHelper(SHARED_PREFS_BACKUP_KEY, helper);
    }

    @Override
    public void onRestoreFinished() {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        NfcService.sIsNfcRestore = true;
        DeviceConfigFacade deviceConfigFacade = NfcInjector.getInstance().getDeviceConfigFacade();

        if (nfcAdapter != null) {
            SharedPreferences prefs = getSharedPreferences(NfcService.PREF,
                Context.MODE_MULTI_PROCESS);
            if (prefs.getBoolean(NfcService.PREF_NFC_ON,
                    deviceConfigFacade.getNfcDefaultState())) {
                nfcAdapter.enable();
            } else {
                nfcAdapter.disable();
            }

            if (prefs.getBoolean(NfcService.PREF_NFC_READER_OPTION_ON,
                    deviceConfigFacade.getDefaultReaderOption())) {
                nfcAdapter.enableReaderOption(true);
            } else {
                nfcAdapter.enableReaderOption(false);
            }

            if (prefs.getBoolean(NfcService.PREF_SECURE_NFC_ON, deviceConfigFacade.getDefaultSecureNfcState())
                    && nfcAdapter.isSecureNfcSupported()) {
                nfcAdapter.enableSecureNfc(true);
            } else {
                nfcAdapter.enableSecureNfc(false);
            }
        }
    }
}

