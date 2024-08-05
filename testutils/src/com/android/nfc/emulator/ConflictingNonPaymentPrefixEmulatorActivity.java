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

import com.android.nfc.utils.HceUtils;
import com.android.nfc.service.PrefixTransportService1;
import com.android.nfc.service.PrefixTransportService2;

import java.util.ArrayList;

public class ConflictingNonPaymentPrefixEmulatorActivity extends BaseEmulatorActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupServices(
                PrefixTransportService1.COMPONENT, PrefixTransportService2.COMPONENT);
    }

    @Override
    protected void onServicesSetup() {
        // Do dynamic AID registration
        ArrayList<String> service_aids = new ArrayList<>();
        service_aids.add(HceUtils.TRANSPORT_PREFIX_AID + "*");
        mCardEmulation.registerAidsForService(
                PrefixTransportService1.COMPONENT,
                CardEmulation.CATEGORY_OTHER,
                service_aids);
        mCardEmulation.registerAidsForService(
                PrefixTransportService2.COMPONENT,
                CardEmulation.CATEGORY_OTHER,
                service_aids);
    }

    @Override
    public void onApduSequenceComplete(ComponentName component, long duration) {
        if (component.equals(PrefixTransportService2.COMPONENT)) {
            setTestPassed();
        }
    }

    @Override
    public ComponentName getPreferredServiceComponent(){
        return PrefixTransportService2.COMPONENT;
    }
}
