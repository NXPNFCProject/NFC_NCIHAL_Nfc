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
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;

import com.android.nfc.service.TransportService2;
import com.android.nfc.utils.HceUtils;
import com.android.nfc.service.LargeNumAidsService;

import java.util.ArrayList;

public class LargeNumAidsEmulatorActivity extends BaseEmulatorActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onApduSequenceComplete(ComponentName component, long duration) {
        if (component.equals(LargeNumAidsService.COMPONENT)) {
            setTestPassed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupServices(LargeNumAidsService.COMPONENT);
    }

    @Override
    protected void onServicesSetup() {
        ArrayList<String> aids = new ArrayList<String>();
        for (int i = 0; i < 256; i++) {
            aids.add(
                    HceUtils.LARGE_NUM_AIDS_PREFIX
                            + String.format("%02X", i)
                            + HceUtils.LARGE_NUM_AIDS_POSTFIX);
        }
        mCardEmulation.registerAidsForService(
                LargeNumAidsService.COMPONENT,
                CardEmulation.CATEGORY_OTHER,
                aids);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mCardEmulation.unsetPreferredService(this);
    }

    @Override
    public ComponentName getPreferredServiceComponent(){
        return LargeNumAidsService.COMPONENT;
    }
}
