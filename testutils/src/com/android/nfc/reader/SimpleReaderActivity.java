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

package com.android.nfc.reader;

import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.NfcAdapter.ReaderCallback;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Parcelable;
import android.util.Log;

import com.android.nfc.utils.CommandApdu;
import com.android.nfc.utils.HceUtils;

import java.io.IOException;
import java.util.Arrays;

/** Basic reader activity that sends and receives APDUs to tag when discovered. */
public class SimpleReaderActivity extends BaseReaderActivity implements ReaderCallback {
    public static final String EXTRA_APDUS = "apdus";
    public static final String EXTRA_RESPONSES = "responses";

    private static final String TAG = "SimpleReaderActivity";
    private static final String EXTRA_NFC_TECH = "nfc_tech";
    public static final int NFC_TECH_A_POLLING_ON =
            NfcAdapter.FLAG_READER_NFC_A
                    | NfcAdapter.FLAG_READER_NFC_BARCODE
                    | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;


    private CommandApdu[] mApdus;
    private String[] mResponses;

    @Override
    protected void onResume() {
        super.onResume();
        Intent intent = getIntent();
        setIntent(intent);
        int nfcTech = intent.getIntExtra(EXTRA_NFC_TECH, NFC_TECH_A_POLLING_ON);
        mAdapter.enableReaderMode(this, this, nfcTech, null);
        Parcelable[] apdus = intent.getParcelableArrayExtra(EXTRA_APDUS);
        if (apdus != null) {
            mApdus = new CommandApdu[apdus.length];
            for (int i = 0; i < apdus.length; i++) {
                mApdus[i] = (CommandApdu) apdus[i];
            }
        } else {
            mApdus = null;
        }

        mResponses = intent.getStringArrayExtra(EXTRA_RESPONSES);
    }

    // Override the default setPollTech for this case since we have a specific reader activity.
    @Override
    public void setPollTech(int pollTech) {
        Log.d(TAG, "setting polltech to " + pollTech);
        mAdapter.enableReaderMode(this, this, pollTech, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        Log.d(TAG, "onTagDiscovered");
        final StringBuilder sb = new StringBuilder();
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            return;
        }

        boolean success = true;
        long startTime = System.currentTimeMillis();
        try {
            isoDep.connect();
            isoDep.setTimeout(5000);
            int count = 0;

            for (CommandApdu apdu : mApdus) {
                sb.append("Request APDU:\n");
                sb.append(apdu.getApdu()).append("\n\n");
                long apduStartTime = System.currentTimeMillis();
                byte[] response = isoDep.transceive(HceUtils.hexStringToBytes(apdu.getApdu()));
                long apduEndTime = System.currentTimeMillis();
                sb.append("Response APDU (in ")
                        .append(apduEndTime - apduStartTime)
                        .append(" ms):\n");
                sb.append(HceUtils.getHexBytes(null, response));

                sb.append("\n\n\n");
                boolean wildCard = "*".equals(mResponses[count]);
                byte[] expectedResponse = HceUtils.hexStringToBytes(mResponses[count]);
                Log.d(TAG, HceUtils.getHexBytes("APDU response: ", response));
                if (!wildCard && !Arrays.equals(response, expectedResponse)) {
                    Log.d(TAG, "Unexpected APDU response: " + HceUtils.getHexBytes("", response)
                            + " expected: " + mResponses[count]);
                    success = false;
                    break;
                }
                count++;
            }
        } catch (IOException e) {
            sb.insert(
                    0,
                    "Error while reading: (did you keep the devices in range?)\nPlease try "
                            + "again\n.");
            Log.e(TAG, sb.toString());
        }
        if (success) {
            sb.insert(
                    0,
                    "Total APDU exchange time: "
                            + (System.currentTimeMillis() - startTime)
                            + " ms.\n\n");
            Log.d(TAG, sb.toString());
            setTestPassed();
        } else {
            sb.insert(
                    0,
                    "FAIL. Total APDU exchange time: "
                            + (System.currentTimeMillis() - startTime)
                            + " ms.\n\n");
            Log.w(TAG, sb.toString());
        }
    }
}
