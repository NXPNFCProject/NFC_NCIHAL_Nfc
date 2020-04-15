package com.android.nfc;


import android.content.Context;
import android.os.UserHandle;

public class NfcPermissions {

    /**
     * NFC ADMIN permission - only for system apps
     */
    private static final String ADMIN_PERM = android.Manifest.permission.WRITE_SECURE_SETTINGS;
    private static final String ADMIN_PERM_ERROR = "WRITE_SECURE_SETTINGS permission required";

    /**
     * Regular NFC permission
     */
    static final String NFC_PERMISSION = android.Manifest.permission.NFC;
    private static final String NFC_PERM_ERROR = "NFC permission required";

    /**
     * NFC PREFERRED PAYMENT INFO permission
     */
    static final String NFC_PREFERRED_PAYMENT_INFO_PERMISSION =
            android.Manifest.permission.NFC_PREFERRED_PAYMENT_INFO;
    private static final String NFC_PREFERRED_PAYMENT_INFO_PERM_ERROR =
            "NFC_PREFERRED_PAYMENT_INFO permission required";

    public static void validateUserId(int userId) {
        if (userId != UserHandle.getCallingUserId()) {
            throw new SecurityException("userId passed in is not the calling user.");
        }
    }

    public static void enforceAdminPermissions(Context context) {
        context.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);
    }


    public static void enforceUserPermissions(Context context) {
        context.enforceCallingOrSelfPermission(NFC_PERMISSION, NFC_PERM_ERROR);
    }

    public static void enforcePreferredPaymentInfoPermissions(Context context) {
        context.enforceCallingOrSelfPermission(NFC_PREFERRED_PAYMENT_INFO_PERMISSION,
                NFC_PREFERRED_PAYMENT_INFO_PERM_ERROR);
    }
}
