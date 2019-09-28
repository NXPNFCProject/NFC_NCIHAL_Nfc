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
 ******************************************************************************/
package com.android.nfc.dhimpl;

import com.android.nfc.DeviceHost;

public class NativeT4tNfceeManager {
  public native int doWriteT4tData(byte[] fileId, byte[] data, int length);

  public native byte[] doReadT4tData(byte[] fileId);

  public native boolean doLockT4tData(boolean lock);

  public native boolean isLockedT4tData();

  public native boolean doClearNdefT4tData();
}