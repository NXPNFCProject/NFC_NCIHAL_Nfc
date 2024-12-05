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

import static android.content.Context.MODE_PRIVATE;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Used to migrate shared preferences files stored by
 * {@link com.android.nfc.NfcService} from AOSP stack to NFC mainline
 * module.
 */
public class SharedPreferencesMigration {
    private static final String TAG = "SharedPreferencesMigration";
    private static final String PREF = "NfcServicePrefs";
    public static final String PREF_TAG_APP_LIST = "TagIntentAppPreferenceListPrefs";
    private static final String PREF_NFC_ON = "nfc_on";
    private static final String PREF_SECURE_NFC_ON = "secure_nfc_on";
    private static final String PREF_NFC_READER_OPTION_ON = "nfc_reader_on";
    private static final String PREF_MIGRATION_TO_MAINLINE_COMPLETE = "migration_to_mainline_complete";

    private final SharedPreferences mSharedPreferences;
    private SharedPreferences mTagAppPrefListPreferences;
    private final Context mContext;
    private final NfcAdapter mNfcAdapter;

    public SharedPreferencesMigration(Context context) {
        mContext = context;
        mNfcAdapter = NfcAdapter.getDefaultAdapter(context);
        if (mNfcAdapter == null) {
            throw new IllegalStateException("Failed to get NFC adapter");
        }
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREF, MODE_PRIVATE);
        SharedPreferences tagAppPrefListPreferences =
                context.getSharedPreferences(PREF_TAG_APP_LIST, MODE_PRIVATE);
        // Check both CE & DE directory for migration.
        if (sharedPreferences.getAll().isEmpty() && tagAppPrefListPreferences.getAll().isEmpty()) {
            Log.d(TAG, "Searching for NFC preferences in CE directory");
            Context ceContext = context.createCredentialProtectedStorageContext();
            sharedPreferences = ceContext.getSharedPreferences(PREF, MODE_PRIVATE);
            tagAppPrefListPreferences =
                    ceContext.getSharedPreferences(PREF_TAG_APP_LIST, MODE_PRIVATE);
        }
        mSharedPreferences = sharedPreferences;
        mTagAppPrefListPreferences = tagAppPrefListPreferences;
    }

    public boolean hasAlreadyMigrated() {
        return mSharedPreferences.getAll().isEmpty() ||
                mSharedPreferences.getBoolean(PREF_MIGRATION_TO_MAINLINE_COMPLETE, false);
    }

    private List<Integer> getEnabledUserIds() {
        List<Integer> userIds = new ArrayList<Integer>();
        UserManager um =
                mContext.createContextAsUser(UserHandle.of(ActivityManager.getCurrentUser()), 0)
                        .getSystemService(UserManager.class);
        List<UserHandle> luh = um.getEnabledProfiles();
        for (UserHandle uh : luh) {
            userIds.add(uh.getIdentifier());
        }
        return userIds;
    }
    public void handleMigration() {
        Log.i(TAG, "Migrating preferences: " + mSharedPreferences.getAll()
                + ", " + mTagAppPrefListPreferences.getAll());
        if (mSharedPreferences.contains(PREF_NFC_ON)) {
            if (mSharedPreferences.getBoolean(PREF_NFC_ON, false)) {
                mNfcAdapter.enable();
            } else {
                mNfcAdapter.disable();
            }
        }
        if (mSharedPreferences.contains(PREF_SECURE_NFC_ON)) {
            mNfcAdapter.enableSecureNfc(mSharedPreferences.getBoolean(PREF_SECURE_NFC_ON, false));
        }
        if (mSharedPreferences.contains(PREF_NFC_READER_OPTION_ON)) {
            mNfcAdapter.enableReaderOption(
                    mSharedPreferences.getBoolean(PREF_NFC_READER_OPTION_ON, false));
        }
        if (mTagAppPrefListPreferences != null) {
            try {
                for (Integer userId : getEnabledUserIds()) {
                    String jsonString =
                            mTagAppPrefListPreferences.getString(Integer.toString(userId),
                                    (new JSONObject()).toString());
                    if (jsonString != null) {
                        JSONObject jsonObject = new JSONObject(jsonString);
                        Iterator<String> keysItr = jsonObject.keys();
                        while (keysItr.hasNext()) {
                            String pkg = keysItr.next();
                            Boolean allow = jsonObject.getBoolean(pkg);
                            mNfcAdapter.setTagIntentAppPreferenceForUser(userId, pkg, allow);
                        }
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "JSONException: " + e);
            }
        }
        mSharedPreferences.edit().putBoolean(PREF_MIGRATION_TO_MAINLINE_COMPLETE, true).apply();
    }

}
