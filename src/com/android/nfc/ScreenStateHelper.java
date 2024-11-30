package com.android.nfc;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.view.Display;

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
    static final int SCREEN_POLLING_READER_MASK = 0x40;

    private final PowerManager mPowerManager;
    private final KeyguardManager mKeyguardManager;
    private final DisplayManager mDisplayManager;

    ScreenStateHelper(Context context) {
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
    }

    private boolean isDisplayOn() {
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        return display.getState() == Display.STATE_ON;
    }

    int checkScreenState(boolean checkDisplayState) {
        if (!mPowerManager.isInteractive() || (checkDisplayState && !isDisplayOn())) {
            if (mKeyguardManager.isKeyguardLocked()) {
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

    int checkScreenStateProvisionMode() {
        if (!mPowerManager.isInteractive()) {
            if (mKeyguardManager.isDeviceLocked()) {
                return SCREEN_STATE_OFF_LOCKED;
            } else {
                return SCREEN_STATE_OFF_UNLOCKED;
            }
        } else if (mKeyguardManager.isDeviceLocked()) {
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
