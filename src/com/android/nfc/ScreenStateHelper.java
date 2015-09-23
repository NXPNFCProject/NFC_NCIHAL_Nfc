/******************************************************************************
 *
 *  The original Work has been changed by NXP Semiconductors.
 *
 *  Copyright (C) 2015 NXP Semiconductors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/
package com.android.nfc;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;

/**
 * Helper class for determining the current screen state for NFC activities.
 */
class ScreenStateHelper {

    static final int SCREEN_STATE_UNKNOWN = 0;
    static final int SCREEN_STATE_OFF = 1;
    static final int SCREEN_STATE_ON_LOCKED = 2;
    static final int SCREEN_STATE_ON_UNLOCKED = 3;

    static final int POWER_STATE_ON = 6;
    static final int POWER_STATE_OFF = 7;

    private final PowerManager mPowerManager;
    private final KeyguardManager mKeyguardManager;

    ScreenStateHelper(Context context) {
        mKeyguardManager = (KeyguardManager)
                context.getSystemService(Context.KEYGUARD_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    int checkScreenState() {
        //TODO: fix deprecated api
        if (!mPowerManager.isScreenOn()) {
            return SCREEN_STATE_OFF;
        } else if (mKeyguardManager.isKeyguardLocked()) {
            return SCREEN_STATE_ON_LOCKED;
        } else {
            return SCREEN_STATE_ON_UNLOCKED;
        }
    }

    /**
     * For debugging only - no i18n
     */
    static String screenStateToString(int screenState) {
        switch (screenState) {
            case SCREEN_STATE_OFF:
                return "OFF";
            case SCREEN_STATE_ON_LOCKED:
                return "ON_LOCKED";
            case SCREEN_STATE_ON_UNLOCKED:
                return "ON_UNLOCKED";
            default:
                return "UNKNOWN";
        }
    }
}
