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

import com.android.nfc.service.PaymentServiceDynamicAids;
import com.android.nfc.utils.HceUtils;

import java.util.ArrayList;

public class DynamicAidEmulatorActivity extends BaseEmulatorActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupServices(PaymentServiceDynamicAids.COMPONENT);
    }

    @Override
    protected void onServicesSetup() {
        ArrayList<String> paymentAids = new ArrayList<String>();
        paymentAids.add(HceUtils.PPSE_AID);
        paymentAids.add(HceUtils.VISA_AID);
        // Register a different set of AIDs for the foreground
        mCardEmulation.registerAidsForService(
                PaymentServiceDynamicAids.COMPONENT,
                CardEmulation.CATEGORY_PAYMENT,
                paymentAids);
        makeDefaultWalletRoleHolder();
    }

    @Override
    protected void onApduSequenceComplete(ComponentName component, long duration) {
        if (component.equals(PaymentServiceDynamicAids.COMPONENT)) {
            setTestPassed();
        }
    }

    @Override
    public ComponentName getPreferredServiceComponent() {
        return PaymentServiceDynamicAids.COMPONENT;
    }
}
