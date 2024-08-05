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

import com.android.nfc.utils.HceUtils;
import android.content.ComponentName;

public class LargeNumAidsService  extends HceService {
    public static final ComponentName COMPONENT = new ComponentName(
            "com.android.nfc.emulator", LargeNumAidsService.class.getName()
    );

    public LargeNumAidsService() {
        super(
                HceUtils.COMMAND_APDUS_BY_SERVICE.get(LargeNumAidsService.class.getName()),
                HceUtils.RESPONSE_APDUS_BY_SERVICE.get(LargeNumAidsService.class.getName()));
    }
    @Override
    public ComponentName getComponent() {
        return LargeNumAidsService.COMPONENT;
    }
}
