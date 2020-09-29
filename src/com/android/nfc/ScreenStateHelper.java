package com.android.nfc;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;

/**
 * Helper class for determining the current screen state for NFC activities.
 */
class ScreenStateHelper {

    static final int SCREEN_STATE_UNKNOWN = 0x00;
    static final int SCREEN_STATE_OFF_UNLOCKED = 0x01;
    static final int SCREEN_STATE_OFF_LOCKED = 0x02;
    static final int SCREEN_STATE_ON_LOCKED = 0x04;
    static final int SCREEN_STATE_ON_UNLOCKED = 0x08;

    //Polling mask
    static final int SCREEN_POLLING_TAG_MASK = 0x10;
    static final int SCREEN_POLLING_P2P_MASK = 0x20;
    static final int SCREEN_POLLING_READER_MASK = 0x40;

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
            if(mKeyguardManager.isKeyguardLocked()) {
                return SCREEN_STATE_OFF_LOCKED;
            } else {
                return SCREEN_STATE_OFF_UNLOCKED;
            }
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
            case SCREEN_STATE_OFF_LOCKED:
                return "OFF_LOCKED";
            case SCREEN_STATE_ON_LOCKED:
                return "ON_LOCKED";
            case SCREEN_STATE_ON_UNLOCKED:
                return "ON_UNLOCKED";
            case SCREEN_STATE_OFF_UNLOCKED:
                return "OFF_UNLOCKED";
            default:
                return "UNKNOWN";
        }
    }

    static int screenStateToProtoEnum(int screenState) {
        switch (screenState) {
            case SCREEN_STATE_OFF_LOCKED:
                return NfcServiceDumpProto.SCREEN_STATE_OFF_LOCKED;
            case SCREEN_STATE_ON_LOCKED:
                return NfcServiceDumpProto.SCREEN_STATE_ON_LOCKED;
            case SCREEN_STATE_ON_UNLOCKED:
                return NfcServiceDumpProto.SCREEN_STATE_ON_UNLOCKED;
            case SCREEN_STATE_OFF_UNLOCKED:
                return NfcServiceDumpProto.SCREEN_STATE_OFF_UNLOCKED;
            default:
                return NfcServiceDumpProto.SCREEN_STATE_UNKNOWN;
        }
    }
}
