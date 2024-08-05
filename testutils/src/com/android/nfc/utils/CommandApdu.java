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
package com.android.nfc.utils;

import android.os.Parcel;
import android.os.Parcelable;

public class CommandApdu implements Parcelable {
    public static final Parcelable.Creator<CommandApdu> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public CommandApdu createFromParcel(Parcel source) {
                    String apdu = source.readString();
                    boolean reachable = source.readInt() != 0;
                    return new CommandApdu(apdu, reachable);
                }

                @Override
                public CommandApdu[] newArray(int size) {
                    return new CommandApdu[size];
                }
            };
    private String mApdu;
    private boolean mReachable;

    public CommandApdu(String apdu, boolean reachable) {
        mApdu = apdu;
        mReachable = reachable;
    }

    public boolean isReachable() {
        return mReachable;
    }

    public String getApdu() {
        return mApdu;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mApdu);
        dest.writeInt(mReachable ? 1 : 0);
    }
}
