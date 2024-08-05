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
package com.android.nfc.service;

import android.content.ComponentName;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.os.Bundle;

public class AccessServiceTurnObserveModeOnProcessApdu extends AccessService {
    public static final ComponentName COMPONENT =
            new ComponentName(
                    "com.android.nfc.emulator",
                    AccessServiceTurnObserveModeOnProcessApdu.class.getName());

    public static final String OBSERVE_MODE_FALSE = "com.android.nfc.service.OBSERVE_MODE_FALSE";

    @Override
    public ComponentName getComponent() {
        return AccessServiceTurnObserveModeOnProcessApdu.COMPONENT;
    }

    @Override
    public byte[] processCommandApdu(byte[] arg0, Bundle arg1) {
        if (mApduIndex == 1) {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);

            if (adapter != null && !adapter.setObserveModeEnabled(true)) {
                sendBroadcast(new Intent(OBSERVE_MODE_FALSE));
            }
        }
        return super.processCommandApdu(arg0, arg1);
    }
}
