/******************************************************************************
 *
 *  Copyright 2020-2021 NXP
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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.nfc.NdefMessage;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import com.nxp.nfc.INxpWlcCallBack;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.NoSuchElementException;
/*******************************************************************************
*WlcServiceProxy maps WlcService functions to NfcService module through
*reflection and acts as interface between NfcService and WlcService to access
*Wlc functionalities.
*If WlcService module is not accessible through reflection (WlcService aar not
*included indicates feature not supported in runtime), it can return appropriate
*error status to application.
*******************************************************************************/
public class WlcServiceProxy {
  /************************private members starts******************************/
  private static final String TAG = "WlcServiceProxy";
  private static final int WLC_STATUS_SUCCESS = 0x00;
  private static final int WLC_STATUS_FAILED = 0x03;
  private Context mContext;
  private SharedPreferences mNxpPrefs;
  private SharedPreferences.Editor mNxpPrefsEditor;

  private INxpWlcCallBack mWlcCback;
  final Object mResponseEvent = new Object();
  private Class mClientClass = null;
  private Object mClientObj = null;
  private int mStatus = WLC_STATUS_FAILED;
  private Method mClientConnect = null, mClientGetinstance = null, mClientChekIsListener = null,
                 mClientEnable = null, mClientIsEnabled = null, mClientActivated = null,
                 mClientStopCharging = null, mClientDeactivated = null, mClientDisable = null;
  private boolean mServiceConnected = false;
  private boolean mIsWlcEnebled = true, mIsWlcSupported = true;
  private boolean isFeatureSupported = false;
  private static NfcService sService;

  private void mapReflectionContext() {
    try {
      mClientClass = Class.forName("com.android.nfc.wlc.WlcClient");
      mClientGetinstance = mClientClass.getDeclaredMethod("getInstance");
      mClientConnect = mClientClass.getDeclaredMethod("connect", Context.class);
      mClientChekIsListener = mClientClass.getDeclaredMethod("isWlcListener", NdefMessage.class);
      mClientEnable = mClientClass.getDeclaredMethod("enable");
      mClientIsEnabled = mClientClass.getDeclaredMethod("isEnabled");
      mClientStopCharging = mClientClass.getDeclaredMethod("stopCharging");
      mClientDeactivated = mClientClass.getDeclaredMethod("deactivated");
      mClientDisable = mClientClass.getDeclaredMethod("disable");
      mClientObj = mClientGetinstance.invoke(new Object[] {null});
      Log.d(TAG, "Successfully mapped wlc library methods");
    } catch (Exception e) {
      Log.e(TAG, "caught Exception during wlcClientInvocation!! " + e);
    }
  }

  private void notifyStatus(int status) {
    try {
      if (mWlcCback != null)
        mWlcCback.updateStatus(status);
    } catch (Exception e) {
      Log.e(TAG, "caught Exception during notifyStatus!! " + e);
    }
  }

  private void updateWlcEnablePersistStatus(boolean status) {
    mNxpPrefsEditor = mNxpPrefs.edit();
    mNxpPrefsEditor.putBoolean("PREF_WLC_ENABLE_STATUS", status);
    mNxpPrefsEditor.commit();
  }

  /************************private members ends********************************/

  /*********************public members starts here*****************************/
  enum PersistStatus { UPDATE, IGNORE }

  public WlcServiceProxy(Context context, SharedPreferences mPrefs) {
    Log.e(TAG, "WlcServiceProxy constructor enter");
    mContext = context;
    mNxpPrefs = mPrefs;
    mWlcCback = null;
    sService = NfcService.getInstance();
    mapReflectionContext();
  }

  public void registerCallBack(INxpWlcCallBack wlcCallBack) {
    mWlcCback = wlcCallBack;
  }

  public void deRegisterCallBack() {
    mWlcCback = null;
  }

  public boolean isToBeEnabled() {
    return mNxpPrefs.getBoolean("PREF_WLC_ENABLE_STATUS", false);
  }

  public void enable(PersistStatus instruction) {
    if (!isServiceConnected() || (mClientObj == null) || (mClientEnable == null)) {
      notifyStatus(WLC_STATUS_FAILED);
      return;
    }
    int status = WLC_STATUS_FAILED;
    try {
      status = (int) mClientEnable.invoke(mClientObj);
      if ((instruction == PersistStatus.UPDATE) && (status == WLC_STATUS_SUCCESS))
        updateWlcEnablePersistStatus(true);
    } catch (Exception e) {
      Log.e(TAG, " Failed!! Cause : " + e.toString());
      status = WLC_STATUS_FAILED;
    } finally {
      notifyStatus(status);
    }
  }

  public boolean isEnabled() {
    if (!isServiceConnected() || (mClientObj == null) || (mClientIsEnabled == null))
      return false;
    try {
      return (boolean) mClientIsEnabled.invoke(mClientObj);
    } catch (Exception e) {
      Log.e(TAG, " Failed!! Cause : " + e.toString());
      return false;
    }
  }

  public void disable(PersistStatus instruction) {
    if (!isServiceConnected() || (mClientObj == null) || (mClientDisable == null)) {
      notifyStatus(WLC_STATUS_FAILED);
      return;
    }
    int status = WLC_STATUS_FAILED;
    try {
      status = (int) mClientDisable.invoke(mClientObj);
      if ((instruction == PersistStatus.UPDATE) && (status == WLC_STATUS_SUCCESS))
        updateWlcEnablePersistStatus(false);
    } catch (Exception e) {
      Log.e(TAG, " Failed!! Cause : " + e.toString());
      status = WLC_STATUS_FAILED;
    } finally {
      notifyStatus(status);
    }
  }

  public boolean isWlcListenerDetected(NdefMessage ndefMsg) {
    if (!isServiceConnected() || (mClientObj == null) || (mClientChekIsListener == null))
      return false;
    int status = WLC_STATUS_FAILED;
    try {
      status = (int) mClientChekIsListener.invoke(mClientObj, ndefMsg);
    } catch (Exception e) {
      Log.e(TAG, " Failed!! Cause : " + e.toString());
      status = WLC_STATUS_FAILED;
    } finally {
      return (status == WLC_STATUS_SUCCESS);
    }
  }

  public void stopCharging() throws RemoteException {
    if (!isServiceConnected() || (mClientObj == null) || (mClientStopCharging == null))
      return;
    try {
      if (mClientObj != null) {
        mClientStopCharging.invoke(mClientObj);
      }
    } catch (Exception e) {
      Log.e(TAG, "Exception invoking stopCharging!! " + e);
      throw new RemoteException();
    }
  }

  public void deactivated() throws RemoteException {
    if (!isServiceConnected() || (mClientObj == null) || (mClientDeactivated == null))
      return;
    try {
      if (mClientObj != null) {
        mClientDeactivated.invoke(mClientObj);
      }
    } catch (Exception e) {
      Log.e(TAG, "Exception invoking deactivate!! " + e);
      throw new RemoteException();
    }
  }

  public boolean isServiceConnected() {
    if ((mClientObj == null) || (mClientConnect == null))
      return false;
    try {
      if (mServiceConnected)
        return true;
      mServiceConnected = (boolean) mClientConnect.invoke(mClientObj, mContext);
      return mServiceConnected;
    } catch (IllegalAccessException | InvocationTargetException e) {
      return false;
    }
  }
  /***********************public members ends**********************************/
}
