/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.nfc.emulator;


import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObjectNotFoundException;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;

import com.android.nfc.service.AccessServiceTurnObserveModeOnProcessApdu;
import com.android.nfc.utils.CommandApdu;
import com.android.nfc.utils.HceUtils;
import com.android.nfc.utils.NfcSnippet;

import com.google.android.mobly.snippet.rpc.AsyncRpc;
import com.google.android.mobly.snippet.rpc.Rpc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NfcEmulatorDeviceSnippet extends NfcSnippet {

    static String sRfOnAction = "com.android.nfc_extras.action.RF_FIELD_ON_DETECTED";
    private BaseEmulatorActivity mActivity;

    private static final long TIMEOUT_MS = 10_000L;

    /**
     * Starts emulator activity for simple multidevice tests
     *
     * @param serviceClassNames - service class names to enable
     * @param testPassClassName - class name of service that should handle the APDUs
     * @param isPaymentActivity - whether or not it is a payment activity
     */
    @Rpc(description = "Start simple emulator activity")
    public void startSimpleEmulatorActivity(
            String[] serviceClassNames, String testPassClassName, boolean isPaymentActivity) {
        Intent intent =
                buildSimpleEmulatorActivityIntent(
                        serviceClassNames, testPassClassName, null, isPaymentActivity);
        mActivity =
                (SimpleEmulatorActivity)
                        InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
    }

    /**
     * Starts emulator activity for simple multidevice tests
     *
     * @param serviceClassNames - services to enable
     * @param testPassClassName - service that should handle the APDU
     * @param preferredServiceClassName - preferred service to set
     * @param isPaymentActivity - whether or not this is a payment activity
     */
    @Rpc(description = "Start simple emulator activity with preferred service")
    public void startSimpleEmulatorActivityWithPreferredService(
            String[] serviceClassNames,
            String testPassClassName,
            String preferredServiceClassName,
            boolean isPaymentActivity) {
        Intent intent =
                buildSimpleEmulatorActivityIntent(
                        serviceClassNames,
                        testPassClassName,
                        preferredServiceClassName,
                        isPaymentActivity);
        mActivity =
                (SimpleEmulatorActivity)
                        InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
    }

    @Rpc(description = "Opens emulator activity with Access Service that turns on observe mode")
    public void startAccessServiceObserveModeEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(),
                AccessServiceTurnObserveModeOnProcessApduEmulatorActivity.class.getName());

        mActivity =
                (AccessServiceTurnObserveModeOnProcessApduEmulatorActivity)
                        instrumentation.startActivitySync(intent);
    }

    /** Opens dynamic AID emulator activity */
    @Rpc(description = "Opens dynamic AID emulator activity")
    public void startDynamicAidEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(), DynamicAidEmulatorActivity.class.getName());

        mActivity = (DynamicAidEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens prefix payment emulator activity */
    @Rpc(description = "Opens prefix payment emulator activity")
    public void startPrefixPaymentEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(), PrefixPaymentEmulatorActivity.class.getName());

        mActivity = (PrefixPaymentEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens prefix payment emulator 2 activity */
    @Rpc(description = "Opens prefix payment emulator 2 activity")
    public void startPrefixPaymentEmulator2Activity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(), PrefixPaymentEmulator2Activity.class.getName());

        mActivity = (PrefixPaymentEmulator2Activity) instrumentation.startActivitySync(intent);
    }

    /** Opens dual non payment activity */
    @Rpc(description = "Opens dual non-payment prefix emulator activity")
    public void startDualNonPaymentPrefixEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(),
                DualNonPaymentPrefixEmulatorActivity.class.getName());

        mActivity =
                (DualNonPaymentPrefixEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens off host emulator activity */
    @Rpc(description = "Open off host emulator activity")
    public void startOffHostEmulatorActivity(boolean enableObserveMode) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(), OffHostEmulatorActivity.class.getName());
        intent.putExtra(OffHostEmulatorActivity.EXTRA_ENABLE_OBSERVE_MODE, enableObserveMode);

        mActivity = (OffHostEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens screen on only off host emulator activity */
    @Rpc(description = "Open screen-on only off host emulator activity")
    public void startScreenOnOnlyOffHostEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(),
                ScreenOnOnlyOffHostEmulatorActivity.class.getName());

        mActivity = (ScreenOnOnlyOffHostEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens on and off host emulator activity */
    @Rpc(description = "Open on and off host emulator activity")
    public void startOnAndOffHostEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(), OnAndOffHostEmulatorActivity.class.getName());

        mActivity = (OnAndOffHostEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens throughput emulator activity */
    @Rpc(description = "Opens throughput emulator activity")
    public void startThroughputEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(), ThroughputEmulatorActivity.class.getName());

        mActivity = (ThroughputEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens large num AIDs emulator activity */
    @Rpc(description = "Opens large num AIDs emulator activity")
    public void startLargeNumAidsEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(), LargeNumAidsEmulatorActivity.class.getName());

        mActivity = (LargeNumAidsEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens screen off emulator activity */
    @Rpc(description = "Opens screen off emulator activity")
    public void startScreenOffPaymentEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(),
                ScreenOffPaymentEmulatorActivity.class.getName());

        mActivity = (ScreenOffPaymentEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens conflicting non-payment prefix emulator activity */
    @Rpc(description = "Opens conflicting non-payment prefix emulator activity")
    public void startConflictingNonPaymentPrefixEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(),
                ConflictingNonPaymentPrefixEmulatorActivity.class.getName());
        mActivity =
                (ConflictingNonPaymentPrefixEmulatorActivity)
                        instrumentation.startActivitySync(intent);
    }

    /** Opens protocol params emulator activity */
    @Rpc(description = "Opens protocol params emulator activity")
    public void startProtocolParamsEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(), ProtocolParamsEmulatorActivity.class.getName());

        mActivity = (ProtocolParamsEmulatorActivity) instrumentation.startActivitySync(intent);
    }


    @Rpc(description = "Returns if observe mode is supported.")
    public boolean isObserveModeSupported() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        if (adapter == null) {
            return false;
        }
        return adapter.isObserveModeSupported();
    }

    @Rpc(description = "Returns if observe mode is enabled.")
    public boolean isObserveModeEnabled() {
        return mActivity.isObserveModeEnabled();
    }

    @Rpc(description = "Set observe mode.")
    public boolean setObserveModeEnabled(boolean enable) {
        if (mActivity != null && isObserveModeSupported()) {
            return mActivity.setObserveModeEnabled(enable);
        }
        return false;
    }

    /** Open polling and off host emulator activity */
    @Rpc(description = "Open polling and off host emulator activity")
    public void startPollingAndOffHostEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(),
                PollingAndOffHostEmulatorActivity.class.getName());
        intent.putExtra(PollingLoopEmulatorActivity.NFC_TECH_KEY, NfcAdapter.FLAG_READER_NFC_A);
        mActivity = (PollingAndOffHostEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Open polling loop emulator activity for Type A */
    @Rpc(description = "Open polling loop emulator activity for polling loop A test")
    public void startPollingLoopAEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildPollingLoopEmulatorIntent(instrumentation, NfcAdapter.FLAG_READER_NFC_A);
        mActivity = (PollingLoopEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Open polling loop emulator activity for Type B */
    @Rpc(description = "Open polling loop emulator activity for polling loop B test")
    public void startPollingLoopBEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildPollingLoopEmulatorIntent(instrumentation, NfcAdapter.FLAG_READER_NFC_B);
        mActivity = (PollingLoopEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Open polling loop emulator activity for Type A and B */
    @Rpc(description = "Open polling loop emulator activity for polling loop A/B test")
    public void startPollingLoopABEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildPollingLoopEmulatorIntent(
                        instrumentation,
                        NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B);
        mActivity = (PollingLoopEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    /** Open polling loop emulator activity for Type A and B */
    @Rpc(description = "Open polling loop emulator activity for custom polling frame test")
    public void startCustomPollingFrameEmulatorActivity(String customFrame) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildPollingLoopEmulatorIntent(
                        instrumentation,
                        NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B);
        intent.putExtra(PollingLoopEmulatorActivity.NFC_CUSTOM_FRAME_KEY, customFrame);
        mActivity = (PollingLoopEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    @Rpc(description = "Open two polling frame emulator activity for two readers test\"")
    public void startTwoPollingFrameEmulatorActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(),
                TwoPollingFrameEmulatorActivity.class.getName());

        mActivity = (TwoPollingFrameEmulatorActivity) instrumentation.startActivitySync(intent);
    }

    @Rpc(description = "Opens PN532 Activity\"")
    public void startPN532Activity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(),
                PN532Activity.class.getName());

        mActivity = (PN532Activity) instrumentation.startActivitySync(intent);
    }

    /** Registers receiver that waits for RF field broadcast */
    @AsyncRpc(description = "Waits for RF field detected broadcast")
    public void asyncWaitForRfOnBroadcast(String callbackId, String eventName) {
        registerSnippetBroadcastReceiver(callbackId, eventName, sRfOnAction);
    }

    /** Registers receiver that waits for RF field broadcast */
    @AsyncRpc(description = "Waits for RF field detected broadcast")
    public void asyncWaitsForTagDiscovered(String callbackId, String eventName) {
        registerSnippetBroadcastReceiver(
                callbackId, eventName, PN532Activity.ACTION_TAG_DISCOVERED);
    }

    @Rpc(description = "Enable reader mode with given flags")
    public void enableReaderMode(int flags) {
        if (mActivity == null || !(mActivity instanceof PN532Activity)) {
            return;
        }
        ((PN532Activity) mActivity).enableReaderMode(flags);
    }

    /** Registers receiver for polling loop action */
    @AsyncRpc(description = "Waits for seen correct polling loop")
    public void asyncWaitsForSeenCorrectPollingLoop(String callbackId, String eventName) {
        registerSnippetBroadcastReceiver(
                callbackId,
                eventName,
                PollingLoopEmulatorActivity.SEEN_CORRECT_POLLING_LOOP_ACTION);
    }

    /** Registers receiver for Test Pass event */
    @AsyncRpc(description = "Waits for Test Pass event")
    public void asyncWaitForTestPass(String callbackId, String eventName) {
        registerSnippetBroadcastReceiver(
                callbackId, eventName, BaseEmulatorActivity.ACTION_TEST_PASSED);
    }

    /** Registers receiver for Role Held event */
    @AsyncRpc(description = "Waits for Role Held event")
    public void asyncWaitForRoleHeld(String callbackId, String eventName) {
        registerSnippetBroadcastReceiver(
                callbackId, eventName, BaseEmulatorActivity.ACTION_ROLE_HELD);
    }

    /** Registers receiver for Screen Off event */
    @AsyncRpc(description = "Waits for Screen Off event")
    public void asyncWaitForScreenOff(String callbackId, String eventName) {
        registerSnippetBroadcastReceiver(callbackId, eventName, Intent.ACTION_SCREEN_OFF);
    }

    /** Registers receiver for Screen On event */
    @AsyncRpc(description = "Waits for Screen On event")
    public void asyncWaitForScreenOn(String callbackId, String eventName) {
        registerSnippetBroadcastReceiver(callbackId, eventName, Intent.ACTION_SCREEN_ON);
    }

    @AsyncRpc(description = "Waits for Observe Mode False")
    public void asyncWaitForObserveModeFalse(String callbackId, String eventName) {
        registerSnippetBroadcastReceiver(
                callbackId,
                eventName,
                AccessServiceTurnObserveModeOnProcessApdu.OBSERVE_MODE_FALSE);
    }

    /** Sets the listen tech for the active emulator activity */
    @Rpc(description = "Set the listen tech for the emulator")
    public void setListenTech(Integer listenTech) {
        if (mActivity == null) {
            Log.e(TAG, "Activity is null.");
            return;
        }
        mActivity.setListenTech(listenTech);
    }

    /** Resets the listen tech for the active emulator activity */
    @Rpc(description = "Reset the listen tech for the emulator")
    public void resetListenTech() {
        if (mActivity == null) {
            Log.e(TAG, "Activity is null.");
            return;
        }
        mActivity.resetListenTech();
    }

    /** Automatically selects TransportService2 from list of services. */
    @Rpc(description = "Automatically selects TransportService2 from list of services.")
    public void selectItem() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        String text = instrumentation.getTargetContext().getString(R.string.transportService2);
        Log.d(TAG, text);
        try {
            UiScrollable listView = new UiScrollable(new UiSelector());
            listView.scrollTextIntoView(text);
            listView.waitForExists(TIMEOUT_MS);
            UiObject listViewItem =
                    listView.getChildByText(
                            new UiSelector().className(android.widget.TextView.class.getName()),
                            "" + text + "");
            if (listViewItem.exists()) {
                listViewItem.click();
                Log.d(TAG, text + " ListView item was clicked.");
            } else {
                Log.e(TAG, "UI Object does not exist.");
            }
        } catch (UiObjectNotFoundException e) {
            Log.e(TAG, "Ui Object not found.");
        }
    }

    /** Closes emulator activity */
    @Rpc(description = "Close activity if one was opened.")
    public void closeActivity() {
        if (mActivity != null) {
            mActivity.finish();
        }
    }

    /** Wait for preferred service to be set */
    @Rpc(description = "Waits for preferred service to be set")
    public void waitForPreferredService() {
        if (mActivity != null) {
            mActivity.waitForPreferredService();
        }
    }

    /** Wait for preferred service to be set */
    @Rpc(description = "Waits for preferred service to be set")
    public void waitForService(String serviceName) {
        if (mActivity != null) {
            mActivity.waitForService(
                    new ComponentName(HceUtils.EMULATOR_PACKAGE_NAME, serviceName));
        }
    }

    @Rpc(description = "Gets command apdus")
    public String[] getCommandApdus(String serviceClassName) {
        CommandApdu[] commandApdus = HceUtils.COMMAND_APDUS_BY_SERVICE.get(serviceClassName);
        return Arrays.stream(commandApdus)
                .map(commandApdu -> new String(commandApdu.getApdu()))
                .toArray(String[]::new);
    }

    @Rpc(description = "Gets response apdus")
    public String[] getResponseApdus(String serviceClassName) {
        return HceUtils.RESPONSE_APDUS_BY_SERVICE.get(serviceClassName);
    }

    /** Builds intent to launch polling loop emulators */
    private Intent buildPollingLoopEmulatorIntent(Instrumentation instrumentation, int nfcTech) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(), PollingLoopEmulatorActivity.class.getName());
        intent.putExtra(PollingLoopEmulatorActivity.NFC_TECH_KEY, nfcTech);
        return intent;
    }

    /** Builds intent to launch simple emulator activity */
    private Intent buildSimpleEmulatorActivityIntent(
            String[] serviceClassNames,
            String expectedServiceClassName,
            String preferredServiceClassName,
            boolean isPaymentActivity) {

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                SimpleEmulatorActivity.class.getName());

        if (serviceClassNames != null && serviceClassNames.length > 0) {
            List<ComponentName> services =
                    Arrays.stream(serviceClassNames)
                            .map(cls -> new ComponentName(HceUtils.EMULATOR_PACKAGE_NAME, cls))
                            .toList();
            intent.putExtra(SimpleEmulatorActivity.EXTRA_SERVICES, new ArrayList<>(services));
        }

        if (expectedServiceClassName != null) {
            intent.putExtra(
                    SimpleEmulatorActivity.EXTRA_EXPECTED_SERVICE,
                    new ComponentName(HceUtils.EMULATOR_PACKAGE_NAME, expectedServiceClassName));
        }

        if (preferredServiceClassName != null) {
            intent.putExtra(
                    SimpleEmulatorActivity.EXTRA_PREFERRED_SERVICE,
                    new ComponentName(HceUtils.EMULATOR_PACKAGE_NAME, preferredServiceClassName));
        }

        intent.putExtra(SimpleEmulatorActivity.EXTRA_IS_PAYMENT_ACTIVITY, isPaymentActivity);

        return intent;
    }
}
