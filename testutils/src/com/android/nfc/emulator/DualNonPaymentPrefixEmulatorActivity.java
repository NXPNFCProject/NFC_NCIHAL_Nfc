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
import android.util.Log;

import com.android.nfc.service.TransportService2;
import com.android.nfc.utils.HceUtils;
import com.android.nfc.service.PrefixAccessService;
import com.android.nfc.service.PrefixTransportService1;

import java.util.ArrayList;

public class DualNonPaymentPrefixEmulatorActivity extends BaseEmulatorActivity {
    public static final String TAG = "DualNonPaymentEm";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setupServices(PrefixTransportService1.COMPONENT, PrefixAccessService.COMPONENT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCardEmulation.setPreferredService(
                this, PrefixAccessService.COMPONENT);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mCardEmulation.unsetPreferredService(this);
    }

    @Override
    protected void onServicesSetup() {
        // Do dynamic AID registration
        ArrayList<String> service1_aids = new ArrayList<>();
        service1_aids.add(HceUtils.TRANSPORT_PREFIX_AID + "*");
        ArrayList<String> service2_aids = new ArrayList<>();
        service2_aids.add(HceUtils.ACCESS_PREFIX_AID + "*");
        mCardEmulation.registerAidsForService(
                PrefixTransportService1.COMPONENT,
                CardEmulation.CATEGORY_OTHER,
                service1_aids);
        mCardEmulation.registerAidsForService(
                PrefixAccessService.COMPONENT,
                CardEmulation.CATEGORY_OTHER,
                service2_aids);
    }

    @Override
    protected void onApduSequenceComplete(ComponentName component, long duration) {
        if (component.equals(PrefixAccessService.COMPONENT)) {
            setTestPassed();
        }
    }

    @Override
    public ComponentName getPreferredServiceComponent(){
        return PrefixAccessService.COMPONENT;
    }
}
