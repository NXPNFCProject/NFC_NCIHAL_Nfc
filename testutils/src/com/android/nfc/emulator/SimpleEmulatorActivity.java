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

import java.util.List;
import java.util.Objects;

public class SimpleEmulatorActivity extends BaseEmulatorActivity {
    protected static final String TAG = "SimpleEmulatorActivity";

    public static final String EXTRA_SERVICES = "EXTRA_SERVICES";
    public static final String EXTRA_IS_PAYMENT_ACTIVITY = "EXTRA_IS_PAYMENT_ACTIVITY";
    public static final String EXTRA_PREFERRED_SERVICE = "EXTRA_PREFERRED_SERVICE";
    public static final String EXTRA_EXPECTED_SERVICE = "EXTRA_EXPECTED_SERVICE";

    private ComponentName mPreferredService = null;

    @Override
    protected void onResume() {
        super.onResume();

        List<ComponentName> components =
                getIntent().getExtras().getParcelableArrayList(EXTRA_SERVICES, ComponentName.class);
        if (components != null) {
            setupServices(components.toArray(new ComponentName[0]));
        }

        if (getIntent().getBooleanExtra(EXTRA_IS_PAYMENT_ACTIVITY, false)) {
            makeDefaultWalletRoleHolder();
        }

        mPreferredService =
                getIntent().getExtras().getParcelable(EXTRA_PREFERRED_SERVICE, ComponentName.class);

        if (mPreferredService != null) {
            mCardEmulation.setPreferredService(this, mPreferredService);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mCardEmulation.unsetPreferredService(this);
    }

    @Override
    protected void onApduSequenceComplete(ComponentName component, long duration) {
        if (component.equals(
                Objects.requireNonNull(getIntent().getExtras())
                        .getParcelable(EXTRA_EXPECTED_SERVICE, ComponentName.class))) {
            setTestPassed();
        }
    }

    @Override
    public ComponentName getPreferredServiceComponent() {
        return mPreferredService;
    }
}
