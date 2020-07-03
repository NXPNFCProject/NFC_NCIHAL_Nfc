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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.nfc.NdefMessage;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import com.android.nfc.dhimpl.NativeWlcManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.NoSuchElementException;
import java.io.IOException;

public class WlcServiceProxy {
  static final String TAG = "WlcServiceProxy";
  static final int WLC_STATUS_SUCCESS = 0x00;
  static final int WLC_STATUS_FAILED = 0x03;
  Context mContext;
  private SharedPreferences mNxpPrefs;
  private Class mClientClass = null;
  private Object mClientObj = null;
  public WlcDeviceHost mWlcDevHostObj = null;

  private Method mClientConnect = null, mClientGetinstance = null, mClientChekIsListener = null,
                 mClientSetCallback = null, mClientEnable = null, mClientActivated = null,
                  mClientStopCharging = null, mClientDeactivated = null,
                 mClientDisable = null, mClientResponse = null;
  private boolean mServiceConnected = false;
  private boolean mIsWlcEnebled = true, mIsWlcSupported = true;
  private WlcServiceCallback mWlcCbacks = null;
  private boolean isFeatureSupported = false;
  static NfcService sService;
  public WlcServiceProxy(Context context, SharedPreferences mPrefs) {
    mContext = context;
    mNxpPrefs = mPrefs;
    if (mWlcCbacks == null)
      mWlcCbacks = new WlcServiceCallback();
    if (mWlcDevHostObj == null)
      mWlcDevHostObj = new NativeWlcManager();
    sService = NfcService.getInstance();
    mapReflectionContext();
  }

  private void mapReflectionContext() {
    try {
      isFeatureSupported = mWlcDevHostObj.isFeatureSupported();
      mClientClass = Class.forName("com.android.nfc.WlcClient");
      mClientGetinstance = mClientClass.getDeclaredMethod("getInstance");
      mClientConnect = mClientClass.getDeclaredMethod("connect", Context.class);
      mClientChekIsListener = mClientClass.getDeclaredMethod("isWlcListener", NdefMessage.class);
      mClientSetCallback = mClientClass.getDeclaredMethod("setCallback", WlcCallback.class);
      mClientEnable = mClientClass.getDeclaredMethod("enable");
      mClientStopCharging = mClientClass.getDeclaredMethod("stopCharging");
      mClientDeactivated = mClientClass.getDeclaredMethod("deactivated");
      mClientDisable = mClientClass.getDeclaredMethod("disable");
      mClientResponse = mClientClass.getDeclaredMethod("notifyResponse", int.class);
      mClientObj = mClientGetinstance.invoke(new Object[] {null});
    } catch (ClassNotFoundException | IllegalAccessException e) {
      Log.e(TAG, "WlcClient Class not found!!");
    } catch (NoSuchElementException | NoSuchMethodException e) {
      Log.i(TAG, "No such Method wlcClient!!");
    } catch (Exception e) {
      Log.e(TAG, "caught Exception during wlcClientInvocation!!");
    }
  }
  public void handleEvent(Message msg) {
    switch (msg.what) {
      case NfcService.MSG_WLC_ENABLE: {
        Log.d(TAG, "MSG_WLC_ENABLE");
        int status = mWlcDevHostObj.enable(sService.isNfcEnabled());
        notifyResponse(status);
      } break;
      case NfcService.MSG_WLC_DISABLE: {
        Log.d(TAG, "MSG_WLC_DISABLE");
        int status = mWlcDevHostObj.disable();
        notifyResponse(status);
      } break;
      case NfcService.MSG_WLC_START_WPT: {
        Log.d(TAG, "MSG_WLC_START_WPT");
        Bundle writeBundle = (Bundle) msg.obj;
        byte[] wlcCap = writeBundle.getByteArray("wlcCap");
        int status = mWlcDevHostObj.sendIntfExtStart(wlcCap);
        notifyResponse(status);
      } break;
      case NfcService.MSG_WLC_STOP_WPT: {
        Log.d(TAG, "MSG_WLC_STOP_WPT");
        Bundle writeBundle = (Bundle) msg.obj;
        byte nextNfceeAction = writeBundle.getByte("nextNfceeAction");
        byte wlcCapWt = writeBundle.getByte("wlcCapWt");
        int status = mWlcDevHostObj.sendIntfExtStop(nextNfceeAction, wlcCapWt);
        notifyResponse(status);
      } break;
      default:
        Log.d(TAG, "UNKNOWN Wlc Event");
    }
  }

  public boolean isToBeEnabled() {
    return mNxpPrefs.getBoolean("PREF_WLC_ENABLE_STATUS", false);
  }

  public void notifyResponse(int status) {
    if (!isServiceConnected())
      return;
    try {
      if (mClientObj != null) {
        mClientResponse.invoke(mClientObj, status);
      }
    } catch (InvocationTargetException e) {
      Log.e(TAG, " InvocationTargetException!!");
    } catch (IllegalAccessException e) {
      Log.e(TAG, " IllegalAccessException!!");
    } catch (Exception e) {
      Log.e(TAG, "Exception invoking enable!!");
    }
  }

  public int enable() throws IOException {
    if (!isServiceConnected())
      throw new IOException("WlcService Not connected");
    int status = WLC_STATUS_FAILED;
    try {
      if (mClientObj != null) {
        status = (int) mClientEnable.invoke(mClientObj);
      }
    } catch (InvocationTargetException e) {
      Log.e(TAG, " InvocationTargetException!!");
    } catch (IllegalAccessException e) {
      Log.e(TAG, " IllegalAccessException!!");
    } finally {
      return status;
    }
  }

  public void stopCharging() {
    if (!isServiceConnected())
      return;
    try {
      if (mClientObj != null) {
        mClientStopCharging.invoke(mClientObj);
      }
    } catch (InvocationTargetException e) {
      Log.e(TAG, " InvocationTargetException!!");
    } catch (IllegalAccessException e) {
      Log.e(TAG, " IllegalAccessException!!");
    } catch (Exception e) {
      Log.e(TAG, "Exception invoking stopCharging!!");
    }
  }

  public void deactivated() {
    if (!isServiceConnected())
      return;
    try {
      if (mClientObj != null) {
        mClientDeactivated.invoke(mClientObj);
      }
    } catch (InvocationTargetException e) {
      Log.e(TAG, " InvocationTargetException!!");
    } catch (IllegalAccessException e) {
      Log.e(TAG, " IllegalAccessException!!");
    } catch (Exception e) {
      Log.e(TAG, "Exception invoking deactivate!!");
    }
  }

  public int disable() throws IOException {
    if (!isServiceConnected())
      throw new IOException("WlcService Not connected");
    if (!isServiceConnected())
      return WLC_STATUS_FAILED;
    int status = WLC_STATUS_FAILED;
    try {
      if (mClientObj != null) {
        status = (int) mClientDisable.invoke(mClientObj);
      }
    } catch (InvocationTargetException e) {
      Log.e(TAG, " InvocationTargetException!!");
    } catch (IllegalAccessException e) {
      Log.e(TAG, " IllegalAccessException!!");
    } finally {
      return status;
    }
  }

  public boolean isWlcListenerDetected(NdefMessage ndefMsg) {
    if ((ndefMsg == null) || (ndefMsg.getByteArrayLength() == 0) || !isServiceConnected())
      return false;

    boolean isWlcListener = false;
    try {
      if (mClientObj != null) {
        isWlcListener = (boolean) mClientChekIsListener.invoke(mClientObj, ndefMsg);
        return isWlcListener;
      }
    } catch (InvocationTargetException e) {
      Log.e(TAG, " InvocationTargetException!!");
    } catch (IllegalAccessException e) {
      Log.e(TAG, " IllegalAccessException!!");
    } catch (Exception e) {
      Log.e(TAG, "Exception invoking isWlcListener!!");
    } finally {
      return isWlcListener;
    }
  }

  private boolean isServiceConnected() {
    if (mServiceConnected)
      return true;
    try {
      if (mClientObj != null) {
        mServiceConnected = (boolean) mClientConnect.invoke(mClientObj, mContext);
        if (mServiceConnected)
          mClientSetCallback.invoke(mClientObj, mWlcCbacks);
      }
    } catch (InvocationTargetException e) {
      Log.e(TAG, " InvocationTargetException!!");
    } catch (IllegalAccessException e) {
      Log.e(TAG, " IllegalAccessException!!");
    } catch (Exception e) {
      Log.e(TAG, "WLC Service init failed ");
    } finally {
      return mServiceConnected;
    }
  }
}

class WlcServiceCallback implements com.android.nfc.WlcCallback {
  static final String TAG = "WlcServiceCallback";
  static NfcService sService = NfcService.getInstance();

  @Override
  public void enable() {
    Log.d(TAG, "WlcServiceCallback enable");
    sService.sendMessage(NfcService.MSG_WLC_ENABLE, null);
  }

  @Override
  public void disable() {
    Log.d(TAG, "WlcServiceCallback disable");
    sService.sendMessage(NfcService.MSG_WLC_DISABLE, null);
  }

  @Override
  public void sendIntfExtStart(byte[] wlcCap) {
    Log.d(TAG, "WlcServiceCallback ExtStart");
    Bundle writeBundle = new Bundle();
    writeBundle.putByteArray("wlcCap", wlcCap);
    sService.sendMessage(NfcService.MSG_WLC_START_WPT, writeBundle);
  }

  @Override
  public void sendIntfExtStop(byte nextNfceeAction, byte wlcCapWt) {
    Log.d(TAG, "WlcServiceCallback sendIntfExtStop");
    Bundle writeBundle = new Bundle();
    writeBundle.putByte("nextNfceeAction", nextNfceeAction);
    writeBundle.putByte("wlcCapWt", wlcCapWt);
    sService.sendMessage(NfcService.MSG_WLC_STOP_WPT, writeBundle);
  }
}