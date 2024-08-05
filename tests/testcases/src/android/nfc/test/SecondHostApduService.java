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

package android.nfc.test;

import static android.nfc.test.TestUtils.sCurrentCommandApduProcessor;
import static android.nfc.test.TestUtils.sCurrentPollLoopReceiver;

import android.nfc.cardemulation.*;
import android.nfc.cardemulation.PollingFrame;
import android.os.Bundle;
import java.util.List;

public class SecondHostApduService extends HostApduService {
  static final String POLLING_LOOP_RECEIVED_ACTION = "CTS_NFC_POLLING_LOOP";
  static final String SERVICE_NAME_EXTRA = "CTS_NFC_SERVICE_NAME_EXTRA";
  static final String POLLING_FRAMES_EXTRA = "CTS_NFC_POLLING_FRAMES_EXTRA";

  public void ctsNotifyUnhandled() {
    return;
  }

  @Override
  public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
    if (sCurrentCommandApduProcessor != null) {
      return sCurrentCommandApduProcessor.processCommandApdu(this.getClass().getName(),
          apdu, extras);
    }
    return new byte[0];
  }

  @Override
  public void onDeactivated(int reason) {
    return;
  }

  @Override
  public void processPollingFrames(List<PollingFrame> frames) {
    if (sCurrentPollLoopReceiver != null) {
      sCurrentPollLoopReceiver.notifyPollingLoop(this.getClass().getName(), frames);
    }
  }
}
