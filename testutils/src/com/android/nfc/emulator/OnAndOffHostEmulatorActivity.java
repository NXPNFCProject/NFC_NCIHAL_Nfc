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

import com.android.nfc.service.OffHostService;
import com.android.nfc.service.TransportService1;
import com.android.nfc.service.TransportService2;

public class OnAndOffHostEmulatorActivity extends BaseEmulatorActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupServices(OffHostService.COMPONENT, TransportService1.COMPONENT);
    }

    @Override
    public void onApduSequenceComplete(ComponentName component, long duration) {}

    @Override
    public ComponentName getPreferredServiceComponent(){
        return TransportService1.COMPONENT;
    }
}
