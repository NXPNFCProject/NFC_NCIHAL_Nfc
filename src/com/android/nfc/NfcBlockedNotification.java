/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import com.android.nfc.R;

public class NfcBlockedNotification extends Activity {
    private static final String NFC_NOTIFICATION_CHANNEL = "nfc_notification_channel";
    private NotificationChannel mNotificationChannel;
    public static final int NOTIFICATION_ID_NFC = -1000001;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent infoIntent;
        if (TextUtils.isEmpty(getString(R.string.antenna_blocked_alert_link))) {
            // Do nothing after user click the notification if antenna_blocked_alert_link is empty
            infoIntent = new Intent();
        } else {
            // Open the link after user click the notification
            infoIntent = new Intent(Intent.ACTION_VIEW);
            infoIntent.setData(Uri.parse(getString(R.string.antenna_blocked_alert_link)));
        }
        Notification.Builder builder = new Notification.Builder(this, NFC_NOTIFICATION_CHANNEL);
        builder.setContentTitle(getString(R.string.nfc_blocking_alert_title))
               .setContentText(getString(R.string.nfc_blocking_alert_message))
               .setSmallIcon(android.R.drawable.stat_sys_warning)
               .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
               .setAutoCancel(true)
               .setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, infoIntent,
                       PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE));
        mNotificationChannel = new NotificationChannel(NFC_NOTIFICATION_CHANNEL,
            getString(R.string.nfcUserLabel), NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager =
            getApplicationContext().getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(mNotificationChannel);
        notificationManager.notify(NOTIFICATION_ID_NFC, builder.build());
    }
}
