/******************************************************************************
 *
 *  Copyright 2019 NXP
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
 *
 ******************************************************************************/
package com.android.nfc.dhimpl;
import android.util.Log;
import android.content.Intent;
import android.content.Context;
public class NativeNfcfeatManager {
    public static final String TAG = "NativeNfcfeatManager";
    public static final String ACTION_EXT_FILED_DETECTED =
            "com.android.nfc_extras.action.ACTION_EXT_FILED_DETECTED";
    static final boolean DBG = true;
    Context mContext;
    public native byte[] startCoverAuth();


    public native boolean stopCoverAuth();


    public native byte[] transceiveAuthData(byte[] data);

    public native int doGetSecureElementTechList();

    public NativeNfcfeatManager(Context context) {
        mContext = context;
    }
    private void notifyRfFieldDetectedPropTAG()
    {
        Log.e(TAG, "External filed detected when auth in use");
        Intent flashIntent = new Intent();
        flashIntent.setAction(ACTION_EXT_FILED_DETECTED);
        if (DBG) Log.d(TAG, "Broadcasting " + ACTION_EXT_FILED_DETECTED);
        mContext.sendBroadcast(flashIntent);
    }
}