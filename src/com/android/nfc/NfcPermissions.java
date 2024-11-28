package com.android.nfc;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.util.List;

public class NfcPermissions {

    private static final String TAG = "NfcPermissions";

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

    /**
     * NFC SET CONTROLLER ALWAYS ON permission
     */
    static final String NFC_SET_CONTROLLER_ALWAYS_ON =
            android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON;
    private static final String NFC_SET_CONTROLLER_ALWAYS_ON_ERROR =
            "NFC_SET_CONTROLLER_ALWAYS_ON permission required";

    private final Context mContext;

    public NfcPermissions(Context context) {
        mContext = context;
    }


    public static void validateUserId(int userId) {
        if (userId != UserHandle.getUserHandleForUid(Binder.getCallingUid()).getIdentifier()) {
            throw new SecurityException("userId passed in is not the calling user.");
        }
    }

    /**
     * Validate whether the profileId belongs to current user
     */
    public static void validateProfileId(Context context, int profileId) {
        // Propagate the state change to all user profiles
        UserManager um = context.createContextAsUser(
                UserHandle.of(ActivityManager.getCurrentUser()), /*flags=*/0)
                .getSystemService(UserManager.class);
        List<UserHandle> luh = um.getEnabledProfiles();

        for (UserHandle uh : luh) {
            if (profileId == uh.getIdentifier()) {
                return;
            }
        }

        throw new SecurityException("profileId passed in does not belong to the calling user.");
    }

    public static void enforceAdminPermissions(Context context) {
        context.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);
    }

    public static boolean checkAdminPermissions(Context context) {
        return context.checkCallingOrSelfPermission(ADMIN_PERM) == PERMISSION_GRANTED;
    }

    public static void enforceUserPermissions(Context context) {
        context.enforceCallingOrSelfPermission(NFC_PERMISSION, NFC_PERM_ERROR);
    }

    public static void enforcePreferredPaymentInfoPermissions(Context context) {
        context.enforceCallingOrSelfPermission(NFC_PREFERRED_PAYMENT_INFO_PERMISSION,
                NFC_PREFERRED_PAYMENT_INFO_PERM_ERROR);
    }

    /**
     * Permission check for android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON
     */
    public static void enforceSetControllerAlwaysOnPermissions(Context context) {
        context.enforceCallingOrSelfPermission(NFC_SET_CONTROLLER_ALWAYS_ON,
                NFC_SET_CONTROLLER_ALWAYS_ON_ERROR);
    }

    /**
     * Returns the DevicePolicyManager from context
     */
    public static DevicePolicyManager retrieveDevicePolicyManagerFromContext(Context context) {
        DevicePolicyManager devicePolicyManager =
                context.getSystemService(DevicePolicyManager.class);
        if (devicePolicyManager == null
                && context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_DEVICE_ADMIN)) {
            Log.w(TAG, "Error retrieving DPM service");
        }
        return devicePolicyManager;
    }

    /**
     * Returns {@code true} if the calling {@code uid} and {@code packageName} is the device owner.
     */
    public boolean isDeviceOwner(int uid, @Nullable String packageName) {
        // Cannot determine if the app is DO/PO if packageName is null. So, will return false to be
        // safe.
        if (packageName == null) {
            Log.e(TAG, "isDeviceOwner: packageName is null, returning false");
            return false;
        }
        DevicePolicyManager devicePolicyManager =
                retrieveDevicePolicyManagerFromUserContext(uid);
        if (devicePolicyManager == null) return false;
        return devicePolicyManager.isDeviceOwnerApp(packageName);
    }

    @Nullable
    private Context createPackageContextAsUser(int uid) {
        Context userContext = null;
        try {
            userContext = mContext.createPackageContextAsUser(mContext.getPackageName(), 0,
                    UserHandle.getUserHandleForUid(uid));
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unknown package name");
            return null;
        }
        if (userContext == null) {
            Log.e(TAG, "Unable to retrieve user context for " + uid);
            return null;
        }
        return userContext;
    }

    private DevicePolicyManager retrieveDevicePolicyManagerFromUserContext(int uid) {
        long ident = Binder.clearCallingIdentity();
        try {
            Context userContext = createPackageContextAsUser(uid);
            if (userContext == null) return null;
            return retrieveDevicePolicyManagerFromContext(userContext);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Returns true if the |callingUid|/|callingPackage| is the profile owner.
     */
    public boolean isProfileOwner(int uid, @Nullable String packageName) {
        // Cannot determine if the app is DO/PO if packageName is null. So, will return false to be
        // safe.
        if (packageName == null) {
            Log.e(TAG, "isProfileOwner: packageName is null, returning false");
            return false;
        }
        DevicePolicyManager devicePolicyManager =
                retrieveDevicePolicyManagerFromUserContext(uid);
        if (devicePolicyManager == null) return false;
        return devicePolicyManager.isProfileOwnerApp(packageName);
    }
}
