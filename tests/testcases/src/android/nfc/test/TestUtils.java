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

import static org.junit.Assume.assumeFalse;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.PollingFrame;
import android.nfc.cardemulation.PollingFrame.PollingFrameType;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.UserManager;
import android.util.Log;
import android.view.KeyEvent;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.compatibility.common.util.CommonTestUtils;
import com.android.compatibility.common.util.SystemUtil;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;

public class TestUtils {
  static boolean supportsHardware() {
    final PackageManager pm = InstrumentationRegistry.getInstrumentation().getContext()
        .getPackageManager();
    return pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
  }

  static Activity createAndResumeActivity() {
    return createAndResumeActivity(NfcFCardEmulationActivity.class);
  }

  static Activity createAndResumeActivity(Class<? extends Activity> activityClass) {
    ensureUnlocked();
    Intent intent = new Intent(ApplicationProvider.getApplicationContext(), activityClass);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
    InstrumentationRegistry.getInstrumentation().callActivityOnResume(activity);

    return activity;
  }

  static void ensurePreferredService(Class serviceClass, Context context) {
    NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
    final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
    int resId =
        serviceClass == CustomHostApduService.class
            ? android.nfc.test.R.string.CustomPaymentService
            : -1;
    final String desc = context.getResources().getString(resId);
    ensurePreferredService(desc, context);
  }

  static void ensureUnlocked() {
    final Context context = InstrumentationRegistry.getInstrumentation().getContext();
    final UserManager userManager = context.getSystemService(UserManager.class);
    assumeFalse(userManager.isHeadlessSystemUserMode());
    final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    final PowerManager pm = context.getSystemService(PowerManager.class);
    final KeyguardManager km = context.getSystemService(KeyguardManager.class);
    try {
      if (pm != null && !pm.isInteractive()) {
        SystemUtil.runShellCommand("input keyevent KEYCODE_WAKEUP");
        CommonTestUtils.waitUntil(
            "Device does not wake up after 5 seconds", 5, () -> pm != null && pm.isInteractive());
      }
      if (km != null && km.isKeyguardLocked()) {
        CommonTestUtils.waitUntil(
            "Device does not unlock after 30 seconds",
            30,
            () -> {
              SystemUtil.runWithShellPermissionIdentity(
                  () -> instrumentation.sendKeyDownUpSync((KeyEvent.KEYCODE_MENU)));
              return km != null && !km.isKeyguardLocked();
            });
      }
    } catch (InterruptedException ie) {
    }
  }

  static void ensurePreferredService(String serviceDesc, Context context) {
    NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
    final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
    try {
      CommonTestUtils.waitUntil(
          "Default service hasn't updated",
          6,
          () -> serviceDesc.equals(cardEmulation.getDescriptionForPreferredPaymentService()));
    } catch (InterruptedException ie) {
    }
  }

  static PollLoopReceiver sCurrentPollLoopReceiver = null;

  static class PollLoopReceiver {
    int mFrameIndex = 0;
    ArrayList<PollingFrame> mFrames;
    String mServiceName;
    ArrayList<PollingFrame> mReceivedFrames;
    String mReceivedServiceName;
    ArrayList<String> mReceivedServiceNames;

    PollLoopReceiver(ArrayList<PollingFrame> frames, String serviceName) {
      mFrames = frames;
      mServiceName = serviceName;
      mReceivedFrames = new ArrayList<PollingFrame>();
      mReceivedServiceNames = new ArrayList<String>();
    }

    void notifyPollingLoop(String className, List<PollingFrame> receivedFrames) {
      if (receivedFrames == null) {
        return;
      }
      mReceivedFrames.addAll(receivedFrames);
      mReceivedServiceName = className;
      mReceivedServiceNames.add(className);
      if (mReceivedFrames.size() < mFrames.size()) {
        return;
      }
      synchronized (this) {
        this.notify();
      }
    }

    void test() {
      if (mReceivedFrames.size() > mFrames.size()) {
        Assert.fail("received more frames than sent");
      } else if (mReceivedFrames.size() < mFrames.size()) {
        Assert.fail("received fewer frames than sent");
      }
      for (PollingFrame receivedFrame : mReceivedFrames) {
        Assert.assertEquals(mFrames.get(mFrameIndex).getType(), receivedFrame.getType());
        Assert.assertEquals(
            mFrames.get(mFrameIndex).getVendorSpecificGain(),
            receivedFrame.getVendorSpecificGain());
        Assert.assertEquals(mFrames.get(mFrameIndex).getTimestamp(), receivedFrame.getTimestamp());
        Assert.assertArrayEquals(mFrames.get(mFrameIndex).getData(), receivedFrame.getData());
        mFrameIndex++;
      }
      if (mServiceName != null) {
        Assert.assertEquals(mServiceName, mReceivedServiceName);
      }
    }
  }

  static List<PollingFrame> notifyPollingLoopAndWait(
      ArrayList<PollingFrame> frames, String serviceName, Context context) {
    NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
    sCurrentPollLoopReceiver = new PollLoopReceiver(frames, serviceName);
    for (PollingFrame frame : frames) {
      adapter.notifyPollingLoop(frame);
    }
    synchronized (sCurrentPollLoopReceiver) {
      try {
        sCurrentPollLoopReceiver.wait(5000);
      } catch (InterruptedException ie) {
        Assert.assertNull(ie);
      }
    }
    sCurrentPollLoopReceiver.test();
    Assert.assertEquals(frames.size(), sCurrentPollLoopReceiver.mFrameIndex);
    List<PollingFrame> receivedFrames = sCurrentPollLoopReceiver.mReceivedFrames;
    sCurrentPollLoopReceiver = null;
    return receivedFrames;
  }

  static PollingFrame createFrame(@PollingFrameType int type) {
    if (type == PollingFrame.POLLING_LOOP_TYPE_ON || type == PollingFrame.POLLING_LOOP_TYPE_OFF) {
      return new PollingFrame(
          type,
          new byte[] {((type == PollingFrame.POLLING_LOOP_TYPE_ON) ? (byte) 0x01 : (byte) 0x00)},
          8,
          0,
          false);
    }
    return new PollingFrame(type, null, 8, 0, false);
  }

  static PollingFrame createFrameWithData(@PollingFrameType int type, byte[] data) {
    return new PollingFrame(type, data, 8, (long) Integer.MAX_VALUE + 1L, false);
  }

  public abstract static class CommandApduProcessor {
    public abstract byte[] processCommandApdu(String serviceName, byte[] apdu, Bundle extras);
  }

  static CommandApduProcessor sCurrentCommandApduProcessor = null;

  public static void killNfcService() {
    Log.w(TAG, "Attempting to kill the NFC service...");

    SystemUtil.runShellCommand("killall com.android.nfc");
  }

  private static final String TAG = "TestUtils";
}
