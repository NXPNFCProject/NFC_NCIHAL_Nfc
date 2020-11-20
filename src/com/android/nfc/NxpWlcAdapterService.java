/******************************************************************************
 *
 *  Copyright 2020 NXP
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
 ******************************************************************************/
package com.android.nfc;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;
import com.nxp.nfc.INxpWlcAdapter;
import com.nxp.nfc.INxpWlcCallBack;

final class NxpWlcAdapterService extends INxpWlcAdapter.Stub {
  WlcServiceProxy mWlc;
  Context mContext;
  NfcService mNfcService;
  public NxpWlcAdapterService(Context context, WlcServiceProxy wlcObj) {
    mContext = context;
    mWlc = wlcObj;
    mNfcService = NfcService.getInstance();
  }

  @Override
  public void enableWlc(INxpWlcCallBack wlcCallBack) throws RemoteException {
    NfcPermissions.enforceUserPermissions(mContext);
    if (!mNfcService.isNfcEnabled())
      throw new RemoteException("NFC is not enabled");
    if (mWlc == null || !mWlc.isServiceConnected())
      throw new RemoteException("Wlc feature is not available ");
    mWlc.registerCallBack(wlcCallBack);
    mNfcService.sendMessage(NfcService.MSG_WLC_ENABLE, null);
  }

  @Override
  public void disableWlc(INxpWlcCallBack wlcCallBack) throws RemoteException {
    NfcPermissions.enforceUserPermissions(mContext);
    if (!mNfcService.isNfcEnabled())
      throw new RemoteException("NFC is not enabled");
    if (mWlc == null || !mWlc.isServiceConnected())
      throw new RemoteException("Wlc feature is not available ");
    mWlc.registerCallBack(wlcCallBack);
    mNfcService.sendMessage(NfcService.MSG_WLC_DISABLE, null);
  }

  @Override
  public boolean isWlcEnabled() throws RemoteException {
    NfcPermissions.enforceUserPermissions(mContext);
    if (!mNfcService.isNfcEnabled())
      throw new RemoteException("NFC is not enabled");
    if (mWlc == null || !mWlc.isServiceConnected())
      throw new RemoteException("Wlc feature is not available ");
    return mWlc.isEnabled();
  }
}
