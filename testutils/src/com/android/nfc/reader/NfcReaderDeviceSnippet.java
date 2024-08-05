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
package com.android.nfc.reader;


import android.app.Instrumentation;
import android.content.Intent;
import android.os.RemoteException;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.android.nfc.utils.CommandApdu;
import com.android.nfc.utils.HceUtils;
import com.android.nfc.utils.NfcSnippet;
import com.android.nfc.service.AccessService;
import com.android.nfc.service.LargeNumAidsService;
import com.android.nfc.service.OffHostService;
import com.android.nfc.service.PaymentService1;
import com.android.nfc.service.PaymentService2;
import com.android.nfc.service.PaymentServiceDynamicAids;
import com.android.nfc.service.PrefixAccessService;
import com.android.nfc.service.PrefixPaymentService1;
import com.android.nfc.service.PrefixTransportService1;
import com.android.nfc.service.PrefixTransportService2;
import com.android.nfc.service.ScreenOffPaymentService;
import com.android.nfc.service.ScreenOnOnlyOffHostService;
import com.android.nfc.service.ThroughputService;
import com.android.nfc.service.TransportService1;
import com.android.nfc.service.TransportService2;

import com.google.android.mobly.snippet.rpc.AsyncRpc;
import com.google.android.mobly.snippet.rpc.Rpc;

public class NfcReaderDeviceSnippet extends NfcSnippet {
    protected static final String TAG = "NfcSnippet";

    private BaseReaderActivity mActivity;

    /** Opens NFC reader for single non-payment test */
    @Rpc(description = "Open simple reader activity for single non-payment test")
    public void startSingleNonPaymentReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(TransportService1.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(TransportService1.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open simple reader activity for single non-payment test */
    @Rpc(description = "Open simple reader activity for single non-payment test")
    public void startSinglePaymentReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(PaymentService1.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(PaymentService1.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens simple reader activity for dual payment services test */
    @Rpc(description = "Opens simple reader activity for dual payment services test")
    public void startDualPaymentReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(PaymentService1.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(PaymentService1.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens simple reader activity for foreground payment test */
    @Rpc(description = "Opens simple reader activity for foreground payment test")
    public void startForegroundPaymentReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(PaymentService2.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(PaymentService2.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens simple reader activity for dynamic AID test */
    @Rpc(description = "Opens simple reader activity for dynamic AID test")
    public void startDynamicAidReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(
                                PaymentServiceDynamicAids.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(
                                PaymentServiceDynamicAids.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens simple reader activity for prefix payment test */
    @Rpc(description = "Opens simple reader activity for prefix payment test")
    public void startPrefixPaymentReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(
                                PrefixPaymentService1.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(
                                PrefixPaymentService1.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens simple reader activity for prefix payment 2 test */
    @Rpc(description = "Opens simple reader activity for prefix payment 2 test")
    public void startPrefixPaymentReader2Activity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(
                                PrefixPaymentService1.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(
                                PrefixPaymentService1.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Opens simple reader activity for non-payment prefix test */
    @Rpc(description = "Opens simple reader activity for non-payment prefix test.")
    public void startDualNonPaymentPrefixReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        CommandApdu[] prefixTransportService1Commands =
                HceUtils.COMMAND_APDUS_BY_SERVICE.get(PrefixTransportService1.class.getName());
        CommandApdu[] prefixAccessServiceCommands =
                HceUtils.COMMAND_APDUS_BY_SERVICE.get(PrefixAccessService.class.getName());

        // Combine command/response APDU arrays
        CommandApdu[] combinedCommands =
                new CommandApdu
                        [prefixTransportService1Commands.length
                                + prefixAccessServiceCommands.length];
        System.arraycopy(
                prefixTransportService1Commands,
                0,
                combinedCommands,
                0,
                prefixTransportService1Commands.length);
        System.arraycopy(
                prefixAccessServiceCommands,
                0,
                combinedCommands,
                prefixTransportService1Commands.length,
                prefixAccessServiceCommands.length);

        String[] prefixTransportService1Responses =
                HceUtils.RESPONSE_APDUS_BY_SERVICE.get(PrefixTransportService1.class.getName());
        String[] prefixAccessServiceResponses =
                HceUtils.RESPONSE_APDUS_BY_SERVICE.get(PrefixAccessService.class.getName());

        String[] combinedResponses =
                new String
                        [prefixTransportService1Responses.length
                                + prefixAccessServiceResponses.length];
        System.arraycopy(
                prefixTransportService1Responses,
                0,
                combinedResponses,
                0,
                prefixTransportService1Responses.length);
        System.arraycopy(
                prefixAccessServiceResponses,
                0,
                combinedResponses,
                prefixTransportService1Responses.length,
                prefixAccessServiceResponses.length);
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation, combinedCommands, combinedResponses);
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open simple reader activity for off host test. */
    @Rpc(description = "Open simple reader activity for off host test")
    public void startOffHostReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(OffHostService.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(OffHostService.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open simple reader activity for on and off host test. */
    @Rpc(description = "Open simple reader activity for off host test")
    public void startOnAndOffHostReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        // Merge command/response APDU sequences.
        CommandApdu[] offHostCommands = HceUtils.COMMAND_APDUS_BY_SERVICE.get(
                OffHostService.class.getName());
        CommandApdu[] onHostCommands = HceUtils.COMMAND_APDUS_BY_SERVICE.get(
                TransportService1.class.getName());
        CommandApdu[] combinedCommands =
                new CommandApdu[offHostCommands.length + onHostCommands.length];
        System.arraycopy(offHostCommands, 0, combinedCommands, 0, offHostCommands.length);
        System.arraycopy(onHostCommands, 0, combinedCommands, offHostCommands.length,
                onHostCommands.length);

        String[] offHostResponses = HceUtils.RESPONSE_APDUS_BY_SERVICE.get(
                OffHostService.class.getName());
        String[] onHostResponses = HceUtils.RESPONSE_APDUS_BY_SERVICE.get(
                TransportService1.class.getName());
        String[] combinedResponses = new String[offHostResponses.length + onHostResponses.length];

        System.arraycopy(offHostResponses, 0, combinedResponses, 0, offHostResponses.length);
        System.arraycopy(onHostResponses, 0, combinedResponses, offHostResponses.length,
                onHostResponses.length);

        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        combinedCommands,
                        combinedResponses);
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open simple reader activity for dual non-payment test */
    @Rpc(description = "Open simple reader activity for dual non-payment test")
    public void startDualNonPaymentReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        CommandApdu[] transportService2CommandApdus =
                HceUtils.COMMAND_APDUS_BY_SERVICE.get(TransportService2.class.getName());
        String[] transportService2ResponseApdus =
                HceUtils.RESPONSE_APDUS_BY_SERVICE.get(TransportService2.class.getName());

        CommandApdu[] accessServiceCommandApdus =
                HceUtils.COMMAND_APDUS_BY_SERVICE.get(AccessService.class.getName());
        String[] accessServiceResponseApdus =
                HceUtils.RESPONSE_APDUS_BY_SERVICE.get(AccessService.class.getName());
        // Combine command/response APDU arrays
        CommandApdu[] commandSequences =
                new CommandApdu
                        [transportService2CommandApdus.length + accessServiceCommandApdus.length];
        System.arraycopy(
                transportService2CommandApdus,
                0,
                commandSequences,
                0,
                transportService2CommandApdus.length);
        System.arraycopy(
                accessServiceCommandApdus,
                0,
                commandSequences,
                transportService2CommandApdus.length,
                accessServiceCommandApdus.length);
        String[] responseSequences =
                new String
                        [transportService2ResponseApdus.length + accessServiceResponseApdus.length];
        System.arraycopy(
                transportService2ResponseApdus,
                0,
                responseSequences,
                0,
                transportService2ResponseApdus.length);
        System.arraycopy(
                accessServiceResponseApdus,
                0,
                responseSequences,
                transportService2ResponseApdus.length,
                accessServiceResponseApdus.length);

        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation, commandSequences, responseSequences);
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open simple reader activity for foreground non-payment test */
    @Rpc(description = "Open simple reader activity for foreground non-payment test")
    public void startForegroundNonPaymentReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(TransportService2.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(TransportService2.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open simple reader activity for throughput test */
    @Rpc(description = "Open simple reader activity for throughput test")
    public void startThroughputReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(ThroughputService.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(ThroughputService.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open simple reader activity for tap test */
    @Rpc(description = "Open simple reader activity for tap test")
    public void startTapTestReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(TransportService1.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(TransportService1.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open simple reader activity for large num AIDs test */
    @Rpc(description = "Open simple reader activity for large num AIDs Test")
    public void startLargeNumAidsReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(LargeNumAidsService.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(
                                LargeNumAidsService.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open simple reader activity for screen off payment test */
    @Rpc(description = "Open simple reader activity for screen off payment Test")
    public void startScreenOffPaymentReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(
                                ScreenOffPaymentService.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(
                                ScreenOffPaymentService.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open protocol params reader activity */
    @Rpc(description = "Open protocol params reader activity")
    public void startProtocolParamsReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(), ProtocolParamsReaderActivity.class.getName());
        mActivity = (ProtocolParamsReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open simple reader activity for conflicting non-payment test */
    @Rpc(description = "Open simple reader activity for conflicting non-payment Test")
    public void startConflictingNonPaymentReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(TransportService2.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(TransportService2.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open simple reader activity for conflicting non-payment prefix test */
    @Rpc(description = "Open simple reader activity for conflicting non-payment prefix Test")
    public void startConflictingNonPaymentPrefixReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(
                                PrefixTransportService2.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(
                                PrefixTransportService2.class.getName()));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Open simple reader activity for screen on only off-host service test */
    @Rpc(description = "Open simple reader activity for screen on only off-host service Test")
    public void startScreenOnOnlyOffHostReaderActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                buildReaderIntentWithApduSequence(
                        instrumentation,
                        HceUtils.COMMAND_APDUS_BY_SERVICE.get(
                                ScreenOnOnlyOffHostService.class.getName()),
                        HceUtils.RESPONSE_APDUS_BY_SERVICE.get(
                                ScreenOnOnlyOffHostService.class.getName()
                        ));
        mActivity = (SimpleReaderActivity) instrumentation.startActivitySync(intent);
    }

    /** Registers receiver for Test Pass event */
    @AsyncRpc(description = "Waits for Test Pass event")
    public void asyncWaitForTestPass(String callbackId, String eventName) {
        registerSnippetBroadcastReceiver(
                callbackId, eventName, BaseReaderActivity.ACTION_TEST_PASSED);
    }

    /** Sets the poll tech for the active reader activity */
    @Rpc(description = "Set the listen tech for the emulator")
    public void setPollTech(Integer pollTech) {
        if (mActivity == null) {
            Log.e(TAG, "Activity is null.");
            return;
        }
        mActivity.setPollTech(pollTech);
    }

    /** Resets the poll tech for the active reader activity */
    @Rpc(description = "Reset the listen tech for the emulator")
    public void resetPollTech() {
        if (mActivity == null) {
            Log.e(TAG, "Activity is null.");
            return;
        }
        mActivity.resetPollTech();
    }

    /** Closes reader activity between tests */
    @Rpc(description = "Close activity if one was opened.")
    public void closeActivity() {
        if (mActivity != null) {
            mActivity.finish();
        }
    }

    private Intent buildReaderIntentWithApduSequence(
            Instrumentation instrumentation, CommandApdu[] commandApdus, String[] responseApdus) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(
                instrumentation.getTargetContext(), SimpleReaderActivity.class.getName());
        intent.putExtra(SimpleReaderActivity.EXTRA_APDUS, commandApdus);
        intent.putExtra(SimpleReaderActivity.EXTRA_RESPONSES, responseApdus);
        return intent;
    }
}
