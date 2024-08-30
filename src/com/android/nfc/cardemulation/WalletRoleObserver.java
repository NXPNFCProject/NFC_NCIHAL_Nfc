/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.nfc.cardemulation;

import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.Context;
import android.os.Binder;
import android.os.UserHandle;
import android.permission.flags.Flags;
import android.text.TextUtils;

import java.util.List;

public class WalletRoleObserver {

    public interface Callback {
        void onWalletRoleHolderChanged(String holder, int userId);
    }
    private Context mContext;
    private RoleManager mRoleManager;
    private OnRoleHoldersChangedListener mOnRoleHoldersChangedListener;
    private Callback mCallback;

    public WalletRoleObserver(Context context, RoleManager roleManager,
            Callback callback) {
        this.mContext = context;
        this.mRoleManager = roleManager;
        this.mCallback = callback;
        this.mOnRoleHoldersChangedListener = (roleName, user) -> {
            if (!roleName.equals(RoleManager.ROLE_WALLET)) {
                return;
            }
            List<String> roleHolders = roleManager.getRoleHolders(RoleManager.ROLE_WALLET);
            String roleHolder = roleHolders.isEmpty() ? null : roleHolders.get(0);
            callback.onWalletRoleHolderChanged(roleHolder, user.getIdentifier());
        };
        this.mRoleManager.addOnRoleHoldersChangedListenerAsUser(context.getMainExecutor(),
                mOnRoleHoldersChangedListener, UserHandle.ALL);
    }

    public String getDefaultWalletRoleHolder(int userId) {
        final long token = Binder.clearCallingIdentity();
        try {
            if (!mRoleManager.isRoleAvailable(RoleManager.ROLE_WALLET)) {
                return null;
            }
            List<String> roleHolders = mRoleManager.getRoleHoldersAsUser(RoleManager.ROLE_WALLET,
                    UserHandle.of(userId));
            return roleHolders.isEmpty() ? null : roleHolders.get(0);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

     boolean isWalletRoleFeatureEnabled() {
        final long token = Binder.clearCallingIdentity();
        try {
            return Flags.walletRoleEnabled();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void onUserSwitched(int userId) {
        String roleHolder = getDefaultWalletRoleHolder(userId);
        mCallback.onWalletRoleHolderChanged(roleHolder, userId);
    }
}
