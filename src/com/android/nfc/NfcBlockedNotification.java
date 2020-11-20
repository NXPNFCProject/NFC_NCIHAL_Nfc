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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.android.nfc.R;

/**
 * This class handles the Notification Manager for the antenna blocked notification
 */

public class NfcBlockedNotification {
    private static final String NFC_NOTIFICATION_CHANNEL = "nfc_notification_channel";
    private NotificationChannel mNotificationChannel;
    public static final int NOTIFICATION_ID_NFC = -1000001;
    Context mContext;

    /**
     * Constructor
     *
     * @param ctx The context to use to obtain access to the resources
     */
    public NfcBlockedNotification(Context ctx) {
        mContext = ctx;
    }

    /**
     * Start the notification.
     */
    public void startNotification() {
        Intent infoIntent;
        if (TextUtils.isEmpty(mContext.getString(R.string.antenna_blocked_alert_link))) {
            // Do nothing after user click the notification if antenna_blocked_alert_link is empty
            infoIntent = new Intent();
        } else {
            // Open the link after user click the notification
            infoIntent = new Intent(Intent.ACTION_VIEW);
            infoIntent.setData(Uri.parse(mContext.getString(R.string.antenna_blocked_alert_link)));
        }
        Notification.Builder builder = new Notification.Builder(mContext, NFC_NOTIFICATION_CHANNEL);
        builder.setContentTitle(mContext.getString(R.string.nfc_blocking_alert_title))
                .setContentText(mContext.getString(R.string.nfc_blocking_alert_message))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(mContext, 0, infoIntent,
                      PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE));
        mNotificationChannel = new NotificationChannel(NFC_NOTIFICATION_CHANNEL,
                mContext.getString(R.string.nfcUserLabel), NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager =
                mContext.getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(mNotificationChannel);
        notificationManager.notify(NOTIFICATION_ID_NFC, builder.build());
    }
}

