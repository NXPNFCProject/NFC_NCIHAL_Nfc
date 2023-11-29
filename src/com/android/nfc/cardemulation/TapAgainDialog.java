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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.widget.Toolbar;

import com.android.nfc.cardemulation.util.AlertActivity;

public class TapAgainDialog extends AlertActivity implements DialogInterface.OnClickListener {
    public static final String ACTION_CLOSE =
            "com.android.nfc.cardemulation.action.CLOSE_TAP_DIALOG";
    public static final String EXTRA_APDU_SERVICE = "apdu_service";

    public static final String EXTRA_CATEGORY = "category";

    // Variables below only accessed on the main thread
    private CardEmulation mCardEmuManager;
    private boolean mClosedOnRequest = false;
    final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mClosedOnRequest = true;
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTheme(com.android.nfc.R.style.TapAgainDayNight);

        final NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        mCardEmuManager = CardEmulation.getInstance(adapter);
        Intent intent = getIntent();
        String category = intent.getStringExtra(EXTRA_CATEGORY);
        ApduServiceInfo serviceInfo = intent.getParcelableExtra(EXTRA_APDU_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_CLOSE);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mReceiver, filter);

        View view = getLayoutInflater().inflate(com.android.nfc.R.layout.tapagain, null);
        Toolbar toolbar = (Toolbar) view.findViewById(com.android.nfc.R.id.tap_again_toolbar);
        toolbar.setNavigationIcon(getDrawable(com.android.nfc.R.drawable.ic_close));
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });

        PackageManager pm = getPackageManager();
        ImageView iv = (ImageView) view.findViewById(com.android.nfc.R.id.tap_again_appicon);
        Drawable icon = pm.getUserBadgedIcon(serviceInfo.loadIcon(pm),
                UserHandle.getUserHandleForUid(serviceInfo.getUid()));

        iv.setImageDrawable(icon);

        mAlertBuilder.setTitle("");
        mAlertBuilder.setView(view);

        setupAlert();
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!mClosedOnRequest) {
            mCardEmuManager.setDefaultForNextTap(null);
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        finish();
    }
}
