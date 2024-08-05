
/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.Intent;
import android.nfc.cardemulation.PollingFrame;
import android.util.Log;

import com.android.nfc.utils.HceUtils;
import android.content.ComponentName;

import java.util.ArrayList;
import java.util.List;

public class PollingLoopService2 extends HceService {
    public static final ComponentName COMPONENT =
            new ComponentName(
                    "com.android.nfc.emulator", PollingLoopService2.class.getName());

    public static final String POLLING_FRAME_ACTION =
           "com.android.nfc.service.POLLING_FRAME_ACTION";
    public static final String POLLING_FRAME_EXTRA = "POLLING_FRAME_EXTRA";
    public static final String SERVICE_NAME_EXTRA = "SERVICE_NAME_EXTRA";
    public static final String TAG = "PollingLoopService2";

    public PollingLoopService2() {
        super(
                HceUtils.COMMAND_APDUS_BY_SERVICE.get(PollingLoopService.class.getName()),
                HceUtils.RESPONSE_APDUS_BY_SERVICE.get(PollingLoopService.class.getName()));
    }
    @Override
    public ComponentName getComponent() {
        return PollingLoopService2.COMPONENT;
    }

    @Override
    public void processPollingFrames(List<PollingFrame> frames) {
        Log.d(TAG, "processPollingFrames of size " + frames.size());
        Intent pollingFrameIntent = new Intent(POLLING_FRAME_ACTION);
        pollingFrameIntent.putExtra(EXTRA_COMPONENT, getComponent());
        pollingFrameIntent.putExtra(
                EXTRA_DURATION, System.currentTimeMillis() - mStartTime);
        pollingFrameIntent.putExtra(POLLING_FRAME_EXTRA, new ArrayList<PollingFrame>(frames));
        pollingFrameIntent.putExtra(SERVICE_NAME_EXTRA, this.getClass().getName());
        sendBroadcast(pollingFrameIntent);
    }
}
