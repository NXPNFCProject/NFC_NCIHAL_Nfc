/*
* Copyright (C) 2018 NXP Semiconductors
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

package com.android.nfc;

import java.util.ArrayList;
import android.util.Log;
import android.os.Handler;
import android.os.HwBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import java.io.IOException;
import java.util.NoSuchElementException;

import vendor.nxp.nxpwiredse.V1_0.INxpWiredSe;
import vendor.nxp.nxpwiredse.V1_0.INxpWiredSeHalCallback;



public class WiredSeService {

    static final String TAG = "WiredSeService";

    private static WiredSeService sService ;

    private static final int EVENT_GET_HAL = 1;
    private static final int MAX_GET_HAL_RETRY = 5;
    private static final int GET_SERVICE_DELAY_MILLIS = 200;
    private static int sNfcWiredSeHandle = 0;
    private static int sWiredSeGetHalRetry = 0;
    INxpWiredSe mWiredSEHal = null;


    public static WiredSeService getInstance() {
        if (sService == null) {
            sService = new WiredSeService();
        }
        return sService;
    }

    private HwBinder.DeathRecipient mWiredSeDeathRecipient = new WiredSeDeathRecipient();

    private
    INxpWiredSeHalCallback.Stub mWiredSeCallback = new INxpWiredSeHalCallback.Stub() {
     private
      ArrayList<Byte> byteArrayToArrayList(byte[] array) {
        ArrayList<Byte> list = new ArrayList<Byte>();
        for (Byte b : array) {
          list.add(b);
        }
        return list;
      }

     private
      byte[] arrayListToByteArray(ArrayList<Byte> list) {
        Byte[] byteArray = list.toArray(new Byte[list.size()]);
        int i = 0;
        byte[] result = new byte[list.size()];
        for (Byte b : byteArray) {
          result[i++] = b.byteValue();
        }
        return result;
      }

      @Override public int openWiredSe() {
                Log.d(TAG, "WiredSe: openWiredSe");
        sNfcWiredSeHandle = NfcService.getInstance().doOpenSecureElementConnection(0xF3);
        if (sNfcWiredSeHandle <= 0) {
          Log.e(TAG, "WiredSe: open secure element failed.");
          sNfcWiredSeHandle = 0;
        } else {
          Log.d(TAG, "WiredSe: open secure element success.");
        }
        return sNfcWiredSeHandle;
      }

      @Override public ArrayList<Byte> transmit(ArrayList<Byte> data, int wiredSeHandle) {
        Log.d(TAG, "WiredSe: transmit");
        if (wiredSeHandle <= 0) {
          Log.d(TAG, "WiredSe: Secure Element handle NULL");
          return null;
        } else {
          byte[] resp = NfcService.getInstance().doTransceive(wiredSeHandle, arrayListToByteArray(data));
          if (resp != null) {
            Log.d(TAG, "WiredSe: response is received");
          }
          return byteArrayToArrayList(resp);
        }
      }

      @Override public ArrayList<Byte> getAtr(int wiredSeHandle)
          throws android.os.RemoteException {
        Log.d(TAG, "WiredSe: getAtr");
        synchronized(WiredSeService.this) {
            return byteArrayToArrayList(NfcService.getInstance().mSecureElement.doGetAtr(wiredSeHandle));
        }
      }

      @Override public int closeWiredSe(int wiredSeHandle) {
                Log.d(TAG, "WiredSe: closeWiredSe");
        NfcService.getInstance().doDisconnect(wiredSeHandle);
        sNfcWiredSeHandle = 0;
        return 0;
      }
    };

    public void wiredSeInitialize() throws NoSuchElementException, RemoteException {
        Log.e(TAG, "wiredSeInitialize Enter");
        if (mWiredSEHal == null) {
          mWiredSEHal = INxpWiredSe.getService();
        }
        if (mWiredSEHal == null) {
          throw new NoSuchElementException("No HAL is provided for WiredSe");
        }
        mWiredSEHal.setWiredSeCallback(mWiredSeCallback);
        mWiredSEHal.linkToDeath(mWiredSeDeathRecipient, 0);
    }

    public void wiredSeDeInitialize() throws NoSuchElementException, RemoteException {
        Log.e(TAG, "wiredSeDeInitialize Enter");
        mWiredSEHal.setWiredSeCallback(null);
    }

    class WiredSeDeathRecipient implements HwBinder.DeathRecipient {
      @Override
      public void serviceDied(long cookie) {
        try{
          Log.d(TAG, "WiredSe: serviceDied !!");
          if(sNfcWiredSeHandle > 0) {
            mWiredSeCallback.closeWiredSe(sNfcWiredSeHandle);
            sNfcWiredSeHandle = 0;
          }
           mWiredSEHal.unlinkToDeath(mWiredSeDeathRecipient);
           mWiredSEHal = null;
           mWiredSeHandler.sendMessageDelayed(mWiredSeHandler.obtainMessage(EVENT_GET_HAL, 0),
                                         GET_SERVICE_DELAY_MILLIS);
        }catch(Exception e) {
            e.printStackTrace();
        }
      }
    }

    private Handler mWiredSeHandler = new Handler(Looper.getMainLooper()) {
      @Override
      public void handleMessage(Message message) {
        switch (message.what) {
          case EVENT_GET_HAL:
            try {
              if(sWiredSeGetHalRetry > MAX_GET_HAL_RETRY) {
                Log.e(TAG, "WiredSe GET_HAL retry failed");
                sWiredSeGetHalRetry = 0;
                break;
              }
              wiredSeInitialize();
              sWiredSeGetHalRetry = 0;
            } catch (Exception e) {
                Log.e(TAG, " could not get the service. trying again");
                sWiredSeGetHalRetry++;
                sendMessageDelayed(obtainMessage(EVENT_GET_HAL, 0),
                                     GET_SERVICE_DELAY_MILLIS);
            }
            break;
          default:
            break;
        }
      }
    };
}
