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
package com.android.nfc;
import com.nxp.nfc.INxpNfcExtras;
import com.android.nfc.dhimpl.NativeNfcfeatManager;
import android.content.Context;
public class NfcExtnsService extends INxpNfcExtras.Stub {
    NativeNfcfeatManager mNfcManager;
    public static final String TAG = "NfcExtnsService";
    static final boolean DBG = true;
    Context mContext;
    NfcExtnsService(Context mContext)
    {
        mNfcManager = new NativeNfcfeatManager(mContext);
        this.mContext = mContext;
    }

    @Override
    public byte[] startCoverAuth() {
        return mNfcManager.startCoverAuth();
    }

    @Override
    public boolean stopCoverAuth() {
        return mNfcManager.stopCoverAuth();
    }

    @Override
    public byte[] transceiveAuthData(byte[] data) {
        return mNfcManager.transceiveAuthData(data);
    }
};