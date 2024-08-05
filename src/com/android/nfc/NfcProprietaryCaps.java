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

package com.android.nfc;

import android.util.Log;

import java.util.Arrays;

public class NfcProprietaryCaps {
    private static final String TAG = "NfcProprietaryCaps";
    private static final int PASSIVE_OBSERVE_MODE = 0;
    private static final int POLLING_FRAME_NTF = 1;
    private static final int POWER_SAVING_MODE = 2;
    private static final int AUTOTRANSACT_POLLING_LOOP_FILTER = 3;
    private final PassiveObserveMode mPassiveObserveMode;
    private final boolean mIsPollingFrameNotificationSupported;
    private final boolean mIsPowerSavingModeSupported;
    private final boolean mIsAutotransactPollingLoopFilterSupported;

    public enum PassiveObserveMode {
        NOT_SUPPORTED,
        SUPPORT_WITH_RF_DEACTIVATION,
        SUPPORT_WITHOUT_RF_DEACTIVATION,
    }

    public PassiveObserveMode getPassiveObserveMode() {
        return mPassiveObserveMode;
    }

    public boolean isPollingFrameNotificationSupported() {
        return mIsPollingFrameNotificationSupported;
    }

    public boolean isPowerSavingModeSupported() {
        return mIsPowerSavingModeSupported;
    }

    public boolean isAutotransactPollingLoopFilterSupported() {
        return mIsAutotransactPollingLoopFilterSupported;
    }

    public NfcProprietaryCaps(PassiveObserveMode passiveObserveMode,
            boolean isPollingFrameNotificationSupported, boolean isPowerSavingModeSupported,
            boolean isAutotransactPollingLoopFilterSupported) {
        mPassiveObserveMode = passiveObserveMode;
        mIsPollingFrameNotificationSupported = isPollingFrameNotificationSupported;
        mIsPowerSavingModeSupported = isPowerSavingModeSupported;
        mIsAutotransactPollingLoopFilterSupported = isAutotransactPollingLoopFilterSupported;
    }

    public static NfcProprietaryCaps createFromByteArray(byte[] caps) {
        Log.i(TAG, "parsing proprietary caps: " + Arrays.toString(caps));
        PassiveObserveMode passiveObserveMode = PassiveObserveMode.NOT_SUPPORTED;
        boolean isPollingFrameNotificationSupported = false;
        boolean isPowerSavingModeSupported = false;
        boolean isAutotransactPollingLoopFilterSupported  = false;
        int offset = 0;
        while ((offset + 2) < caps.length) {
            int id = caps[offset++];
            int value_len = caps[offset++];
            int value_offset = offset;
            offset += value_len;

            // value bounds check
            // all caps have minimum length of 1, check this bound
            // here to simplify match cases.
            if (value_len < 1 || offset > caps.length) {
                break;
            }
            switch (id) {
                case PASSIVE_OBSERVE_MODE:
                    passiveObserveMode = switch (caps[value_offset]) {
                        case 0 -> PassiveObserveMode.NOT_SUPPORTED;
                        case 1 -> PassiveObserveMode.SUPPORT_WITH_RF_DEACTIVATION;
                        case 2 -> PassiveObserveMode.SUPPORT_WITHOUT_RF_DEACTIVATION;
                        default -> passiveObserveMode;
                    };
                    break;
                case POLLING_FRAME_NTF:
                    isPollingFrameNotificationSupported = caps[value_offset] == 0x1;
                    break;
                case POWER_SAVING_MODE:
                    isPowerSavingModeSupported = caps[value_offset] == 0x1;
                    break;
                case AUTOTRANSACT_POLLING_LOOP_FILTER:
                    isAutotransactPollingLoopFilterSupported = caps[value_offset] == 0x1;
                    break;
            }
        }
        return new NfcProprietaryCaps(passiveObserveMode, isPollingFrameNotificationSupported,
                isPowerSavingModeSupported, isAutotransactPollingLoopFilterSupported);
    }

    @Override
    public String toString() {
        return "NfcProprietaryCaps{"
                + "passiveObserveMode="
                + mPassiveObserveMode
                + ", isPollingFrameNotificationSupported="
                + mIsPollingFrameNotificationSupported
                + ", isPowerSavingModeSupported="
                + mIsPowerSavingModeSupported
                + ", isAutotransactPollingLoopFilterSupported="
                + mIsAutotransactPollingLoopFilterSupported
                + '}';
    }
}
