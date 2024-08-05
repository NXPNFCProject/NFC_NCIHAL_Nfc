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

import com.android.nfc.service.PrefixPaymentService1;
import com.android.nfc.service.PrefixPaymentService2;

public class PrefixPaymentEmulator2Activity extends BaseEmulatorActivity {
    private static final int STATE_IDLE = 0;
    private static final int STATE_SERVICE1_SETTING_UP = 1;
    private static final int STATE_SERVICE2_SETTING_UP = 2;

    private int mState = STATE_IDLE;

    @Override
    protected void onResume() {
        super.onResume();
        mState = STATE_SERVICE2_SETTING_UP;
        setupServices(PrefixPaymentService2.COMPONENT);
    }

    @Override
    protected void onServicesSetup() {
        if (mState == STATE_SERVICE2_SETTING_UP) {
            mState = STATE_SERVICE1_SETTING_UP;
            setupServices(
                    PrefixPaymentService1.COMPONENT, PrefixPaymentService2.COMPONENT);
            return;
        }
        makeDefaultWalletRoleHolder();
    }

    @Override
    protected void onApduSequenceComplete(ComponentName component, long duration) {
        if (component.equals(PrefixPaymentService1.COMPONENT)) {
            setTestPassed();
        }
    }

    /* Gets preferred service description */
    @Override
    public ComponentName getPreferredServiceComponent() {
        return PrefixPaymentService1.COMPONENT;
    }
}
