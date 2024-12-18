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

package com.android.nfc.handover;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;

import com.android.nfc.NfcInjector;
import com.android.nfc.R;

public class ConfirmConnectActivity extends Activity {
    static final String TAG = "ConfirmConnectActivity";
    BluetoothDevice mDevice;
    AlertDialog mAlert = null;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        AlertDialog.Builder builder = new AlertDialog.Builder(this,
                R.style.DialogAlertDayNight);
        Intent launchIntent = getIntent();
        mDevice = launchIntent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (mDevice == null) finish();
        Resources res = getResources();
        String btExtraName = launchIntent.getStringExtra(BluetoothDevice.EXTRA_NAME);
        String confirmString = String.format(res.getString(R.string.confirm_pairing),
                "\"" + btExtraName.replaceAll("\\r|\\n", "") + "\"");
        builder.setMessage(confirmString)
               .setCancelable(false)
               .setPositiveButton(res.getString(R.string.pair_yes),
                       new DialogInterface.OnClickListener() {
                   public void onClick(DialogInterface dialog, int id) {
                        Intent allowIntent = new Intent(BluetoothPeripheralHandover.ACTION_ALLOW_CONNECT);
                        allowIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
                        allowIntent.setPackage(NfcInjector.getInstance().getNfcPackageName());
                        sendBroadcast(allowIntent);
                        ConfirmConnectActivity.this.mAlert = null;
                        ConfirmConnectActivity.this.finish();
                   }
               })
               .setNegativeButton(res.getString(R.string.pair_no),
                       new DialogInterface.OnClickListener() {
                   public void onClick(DialogInterface dialog, int id) {
                       Intent denyIntent = new Intent(BluetoothPeripheralHandover.ACTION_DENY_CONNECT);
                       denyIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
                       denyIntent.setPackage(NfcInjector.getInstance().getNfcPackageName());
                       sendBroadcast(denyIntent);
                       ConfirmConnectActivity.this.mAlert = null;
                       ConfirmConnectActivity.this.finish();
                   }
               });
        mAlert = builder.create();
        mAlert.show();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothPeripheralHandover.ACTION_TIMEOUT_CONNECT);
        filter.addAction(BluetoothPeripheralHandover.ACTION_CANCEL_CONNECT);
        registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mReceiver);
        if (mAlert != null) {
            mAlert.dismiss();
            Intent denyIntent = new Intent(BluetoothPeripheralHandover.ACTION_DENY_CONNECT);
            denyIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
            denyIntent.setPackage(NfcInjector.getInstance().getNfcPackageName());
            sendBroadcast(denyIntent);
            mAlert = null;
        }
        super.onDestroy();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothPeripheralHandover.ACTION_TIMEOUT_CONNECT.equals(intent.getAction())) {
                finish();
            }
            /*
              if Bluetooth turned off from Notification Panel, finish this activity.
              Also, sendBroadcast(ACTION_DENY_CONNECT) because otherwise when Bluetooth
              is later turned On, headset seen as paired.
            */
            else if (BluetoothPeripheralHandover.ACTION_CANCEL_CONNECT
                    .equals(intent.getAction())) {
                Log.i(TAG, "Received ACTION_CANCEL_CONNECT action.");
                Intent denyIntent =
                        new Intent(BluetoothPeripheralHandover.ACTION_DENY_CONNECT);
                denyIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
                denyIntent.setPackage(NfcInjector.getInstance().getNfcPackageName());
                context.sendBroadcast(denyIntent);
                finish();
            }
        }
    };
}
