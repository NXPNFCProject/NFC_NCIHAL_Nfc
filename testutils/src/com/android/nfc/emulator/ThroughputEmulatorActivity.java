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

package com.android.nfc.emulator;

import android.content.ComponentName;
import android.os.Bundle;
import android.util.Log;

import com.android.nfc.utils.HceUtils;
import com.android.nfc.service.ThroughputService;

public class ThroughputEmulatorActivity extends BaseEmulatorActivity {
    private static final String TAG = "ThroughputEm";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        setupServices(ThroughputService.COMPONENT);
    }

    @Override
    public ComponentName getPreferredServiceComponent(){
        return ThroughputService.COMPONENT;
    }

    @Override
    public void onApduSequenceComplete(ComponentName component, long duration) {
        if (component.equals(ThroughputService.COMPONENT)) {
            if (duration < 1000) {
                setTestPassed();
            } else {
                long timePerApdu =
                        duration
                                / HceUtils.COMMAND_APDUS_BY_SERVICE.get(
                                                ThroughputService.class.getName())
                                        .length;
                Log.e(
                        TAG,
                        "\"Test Failed. Requires <= 60 ms per APDU round trip. Received \"  "
                                + timePerApdu
                                + " ms per round trip.\"");
            }
        }
    }
}
