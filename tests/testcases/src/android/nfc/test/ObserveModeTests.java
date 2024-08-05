/*
 * Copyright 2024 The Android Open Source Project
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

import static android.nfc.test.TestUtils.createAndResumeActivity;
import static android.nfc.test.TestUtils.createFrame;
import static android.nfc.test.TestUtils.createFrameWithData;
import static android.nfc.test.TestUtils.notifyPollingLoopAndWait;
import static android.nfc.test.TestUtils.supportsHardware;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.PollingFrame;
import android.os.Bundle;
import android.os.RemoteException;
import android.platform.test.annotations.RequiresFlagsEnabled;
import androidx.test.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ObserveModeTests {
  private Context mContext;
  final int STRESS_TEST_DURATION = 10000;

  @Before
  public void setUp() throws NoSuchFieldException, RemoteException {
    assumeTrue(supportsHardware());
    mContext = InstrumentationRegistry.getContext();
  }

  @Test(timeout = 20000)
  @RequiresFlagsEnabled(android.nfc.Flags.FLAG_NFC_OBSERVE_MODE)
  public void testObserveModeStress() throws InterruptedException {
    Assert.assertNotNull(mContext);
    final NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
    Assert.assertNotNull(adapter);
    assumeTrue(adapter.isObserveModeSupported());
    CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
    try {
      Activity activity = createAndResumeActivity();
      cardEmulation.setShouldDefaultToObserveModeForService(
          new ComponentName(mContext, CustomHostApduService.class), true);
      Assert.assertTrue(
          cardEmulation.setPreferredService(
              activity, new ComponentName(mContext, CustomHostApduService.class)));
      TestUtils.ensurePreferredService(CustomHostApduService.class, mContext);
      long stop = System.currentTimeMillis() + STRESS_TEST_DURATION;
      Thread thread1 =
          new Thread() {
            @Override
            public void run() {
              while (System.currentTimeMillis() < stop) {
                Assert.assertTrue(adapter.setObserveModeEnabled(true));
              }
            }
          };

      Thread thread2 =
          new Thread() {
            @Override
            public void run() {
              while (System.currentTimeMillis() < stop) {
                Assert.assertTrue(adapter.setObserveModeEnabled(false));
              }
            }
          };
      thread1.start();
      thread2.start();
      thread1.join();
      thread2.join();

    } finally {
      cardEmulation.setShouldDefaultToObserveModeForService(
          new ComponentName(mContext, CustomHostApduService.class), false);
    }
  }

  @Test
  @RequiresFlagsEnabled(android.nfc.Flags.FLAG_NFC_OBSERVE_MODE)
  public void testInterleavePlfAndAid() {
    Assert.assertNotNull(mContext);
    final NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
    Assert.assertNotNull(adapter);
    assumeTrue(adapter.isObserveModeSupported());
    adapter.notifyHceDeactivated();
    CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
    try {
      Activity activity = createAndResumeActivity();
      cardEmulation.setShouldDefaultToObserveModeForService(
          new ComponentName(mContext, CustomHostApduService.class), true);
      Assert.assertTrue(
          cardEmulation.setPreferredService(
              activity, new ComponentName(mContext, CustomHostApduService.class)));
      TestUtils.ensurePreferredService(CustomHostApduService.class, mContext);
      ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(6);
      frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
      frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
      frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
      frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
      frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
      frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
      notifyPollingLoopAndWait(
          new ArrayList<PollingFrame>(frames), CustomHostApduService.class.getName());
      byte[] selectAidCmd =
          new byte[] {
            0x00, (byte) 0xa4, 0x04, 0x00, (byte) 0xA0, 0x00, 0x00, 0x00, 0x04, 0x10, 0x10, 0x18
          };

      TestUtils.sCurrentCommandApduProcessor =
          new TestUtils.CommandApduProcessor() {
            @Override
            public byte[] processCommandApdu(String serviceName, byte[] apdu, Bundle extras) {
              Assert.assertEquals(serviceName, CustomHostApduService.class.getName());
              Assert.assertArrayEquals(apdu, selectAidCmd);
              return new byte[0];
            }
          };
      adapter.notifyTestHceData(1, selectAidCmd);
      notifyPollingLoopAndWait(
          new ArrayList<PollingFrame>(frames), CustomHostApduService.class.getName());

      byte[] nextCommandApdu = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
      TestUtils.sCurrentCommandApduProcessor =
          new TestUtils.CommandApduProcessor() {
            @Override
            public byte[] processCommandApdu(String serviceName, byte[] apdu, Bundle extras) {
              Assert.assertEquals(serviceName, CustomHostApduService.class.getName());
              Assert.assertArrayEquals(apdu, nextCommandApdu);
              return new byte[0];
            }
          };
      adapter.notifyTestHceData(1, nextCommandApdu);
    } finally {
      cardEmulation.setShouldDefaultToObserveModeForService(
          new ComponentName(mContext, CustomHostApduService.class), false);
      adapter.notifyHceDeactivated();
    }
  }

  @Test
  @RequiresFlagsEnabled(android.nfc.Flags.FLAG_NFC_OBSERVE_MODE)
  public void testInterleavePlfSecondServiceAndAid() {
    Assert.assertNotNull(mContext);
    final NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
    Assert.assertNotNull(adapter);
    assumeTrue(adapter.isObserveModeSupported());
    adapter.notifyHceDeactivated();
    CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
    try {
      Activity activity = createAndResumeActivity();
      cardEmulation.setShouldDefaultToObserveModeForService(
          new ComponentName(mContext, CustomHostApduService.class), true);
      Assert.assertTrue(
          cardEmulation.setPreferredService(
              activity, new ComponentName(mContext, CustomHostApduService.class)));
      TestUtils.ensurePreferredService(CustomHostApduService.class, mContext);
      ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(6);
      frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
      frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
      frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
      frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
      frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
      frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
      notifyPollingLoopAndWait(
          new ArrayList<PollingFrame>(frames), CustomHostApduService.class.getName());
      byte[] selectAidCmd =
          new byte[] {
            0x00, (byte) 0xa4, 0x04, 0x00, (byte) 0xA0, 0x00, 0x00, 0x00, 0x04, 0x10, 0x10, 0x18
          };

      TestUtils.sCurrentCommandApduProcessor =
          new TestUtils.CommandApduProcessor() {
            @Override
            public byte[] processCommandApdu(String serviceName, byte[] apdu, Bundle extras) {
              Assert.assertEquals(serviceName, CustomHostApduService.class.getName());
              Assert.assertArrayEquals(apdu, selectAidCmd);
              return new byte[0];
            }
          };
      adapter.notifyTestHceData(1, selectAidCmd);
      ArrayList<PollingFrame> oneFrame = new ArrayList<PollingFrame>(6);
      oneFrame.add(
          createFrameWithData(
              PollingFrame.POLLING_LOOP_TYPE_UNKNOWN, new byte[] {0x48, 0x29, 0x40, 0x18}));
      notifyPollingLoopAndWait(
          new ArrayList<PollingFrame>(oneFrame), SecondHostApduService.class.getName());

      byte[] nextCommandApdu = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
      TestUtils.sCurrentCommandApduProcessor =
          new TestUtils.CommandApduProcessor() {
            @Override
            public byte[] processCommandApdu(String serviceName, byte[] apdu, Bundle extras) {
              Assert.assertEquals(serviceName, CustomHostApduService.class.getName());
              Assert.assertArrayEquals(apdu, nextCommandApdu);
              return new byte[0];
            }
          };
      adapter.notifyTestHceData(1, nextCommandApdu);
    } finally {
      cardEmulation.setShouldDefaultToObserveModeForService(
          new ComponentName(mContext, CustomHostApduService.class), false);
      adapter.notifyHceDeactivated();
    }
  }

  List<PollingFrame> notifyPollingLoopAndWait(ArrayList<PollingFrame> frames, String serviceName) {
    return TestUtils.notifyPollingLoopAndWait(frames, serviceName, mContext);
  }
}
