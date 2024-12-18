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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.nfc.R;

/**
 * This class handles the Notification Manager for the nfc developer setting notification
 */

public class NfcDeveloperOptionNotification {
    private static final String NFC_NOTIFICATION_CHANNEL = "nfc_logging_channel";
    private NotificationChannel mNotificationChannel;
    public static final int NOTIFICATION_ID_NFC = -1000002;
    Context mContext;

    /**
     * Constructor
     *
     * @param ctx The context to use to obtain access to the resources
     */
    public NfcDeveloperOptionNotification(Context ctx) {
        mContext = ctx;
    }

    /**
     * Start the notification.
     */
    public void startNotification() {
        Intent settingIntent;
        // Go to developer option after user click the notification
        settingIntent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        settingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        Notification.Builder builder = new Notification.Builder(mContext, NFC_NOTIFICATION_CHANNEL);
        builder.setContentTitle(mContext.getString(R.string.nfc_logging_alert_title))
                .setContentText(mContext.getString(R.string.nfc_logging_alert_message))
                .setSmallIcon(R.drawable.nfc_icon)
                .setPriority(NotificationManager.IMPORTANCE_HIGH)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(PendingIntent.getActivity(mContext, 0, settingIntent,
                        PendingIntent.FLAG_IMMUTABLE));
        mNotificationChannel = new NotificationChannel(NFC_NOTIFICATION_CHANNEL,
                mContext.getString(R.string.nfcUserLabel), NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager =
                mContext.getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(mNotificationChannel);
        notificationManager.notify(NOTIFICATION_ID_NFC, builder.build());
    }
}

