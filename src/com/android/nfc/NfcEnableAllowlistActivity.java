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

package com.android.nfc;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static com.android.nfc.NfcService.APP_NAME_ENABLING_NFC;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

public class NfcEnableAllowlistActivity extends Activity implements View.OnClickListener{

    static final String TAG = "NfcEnableAllowListActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Intent intent = getIntent();
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        CharSequence appName = intent.getStringExtra(APP_NAME_ENABLING_NFC);
        String formatString = getString(R.string.title_package_enabling_nfc);
        AlertDialog mAlertDialog = new AlertDialog.Builder(this, R.style.DialogAlertDayNight)
                .setTitle(String.format(formatString, appName))
                .setNegativeButton(R.string.enable_nfc_no, null)
                .setPositiveButton(R.string.enable_nfc_yes, null)
                .create();

        mAlertDialog.show();
        super.onCreate(savedInstanceState);
        mAlertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(this);
        mAlertDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(
                v -> {
                    Log.i(TAG, "Nfc is disallowed by user for app: " + appName);
                    finish();
                });
    }

    @Override
    public void onClick(View v) {
        NfcService sService = NfcService.getInstance();
        sService.enableNfc();
        finish();
    }
}
