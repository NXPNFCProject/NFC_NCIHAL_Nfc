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
package com.android.nfc.dhimpl;

import com.android.nfc.WlcDeviceHost;
public class NativeWlcManager implements WlcDeviceHost {
  public native int doEnable(boolean isNfcInitDone);
  public int enable(boolean isNfcInitDone) {
    return doEnable(isNfcInitDone);
  }

  public native int doDisable();
  public int disable() {
    return doDisable();
  }
  public native boolean doCheckIsFeatureSupported();
  @Override
  public boolean isFeatureSupported() {
    return doCheckIsFeatureSupported();
  }
  public native int doSendIntfExtStart(byte[] wlcCap);
  @Override
  public int sendIntfExtStart(byte[] wlcCap) {
    return doSendIntfExtStart(wlcCap);
  }

  public native int doSendIntfExtStop(byte nextNfceeAction, byte wlcCapWt);
  @Override
  public int sendIntfExtStop(byte nextNfceeAction, byte wlcCapWt) {
    return doSendIntfExtStop(nextNfceeAction, wlcCapWt);
  }
}