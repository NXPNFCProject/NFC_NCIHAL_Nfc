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

package com.android.nfc.cardemulation.util;

import static android.nfc.cardemulation.PollingFrame.POLLING_LOOP_TYPE_UNKNOWN;

import static com.android.nfc.NfcStatsLog.NFC_POLLING_LOOP_NOTIFICATION_REPORTED__PROPRIETARY_FRAME_TYPE__ECP_V1;
import static com.android.nfc.NfcStatsLog.NFC_POLLING_LOOP_NOTIFICATION_REPORTED__PROPRIETARY_FRAME_TYPE__ECP_V2;
import static com.android.nfc.NfcStatsLog.NFC_POLLING_LOOP_NOTIFICATION_REPORTED__PROPRIETARY_FRAME_TYPE__PROPRIETARY_FRAME_UNKNOWN;

import static com.google.common.truth.Truth.assertThat;

import android.nfc.cardemulation.PollingFrame;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.HexFormat;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class StatsdUtilsTest {
    private final StatsdUtils mStatsdUtils = spy(new StatsdUtils());

    @Test
    public void testGetFrameType() {
        assertThat(StatsdUtils.getFrameType(ECP_V1_PAYMENT)).isEqualTo(
                NFC_POLLING_LOOP_NOTIFICATION_REPORTED__PROPRIETARY_FRAME_TYPE__ECP_V1);

        assertThat(StatsdUtils.getFrameType(ECP_V2_TRANSIT_MBTA)).isEqualTo(
                NFC_POLLING_LOOP_NOTIFICATION_REPORTED__PROPRIETARY_FRAME_TYPE__ECP_V2);

        assertThat(StatsdUtils.getFrameType(UNKNOWN_FRAME)).isEqualTo(
                NFC_POLLING_LOOP_NOTIFICATION_REPORTED__PROPRIETARY_FRAME_TYPE__PROPRIETARY_FRAME_UNKNOWN);
    }

    @Test
    public void testLogPollingFrame_ecp1Once() {
        PollingFrame frameData =
                new PollingFrame(POLLING_LOOP_TYPE_UNKNOWN, ECP_V1_PAYMENT, -1, 0, false);

        mStatsdUtils.tallyPollingFrame(ECP_V1_PAYMENT_KEY, frameData);
        mStatsdUtils.logPollingFrames();

        StatsdUtils.PollingFrameLog expectedFrame = new StatsdUtils.PollingFrameLog(ECP_V1_PAYMENT);
        expectedFrame.repeatCount = 1;

        verify(mStatsdUtils).writeToStatsd(expectedFrame);
    }

    @Test
    public void testLogPollingFrame_ecp1TwiceInTwoWrites() {
        PollingFrame frameData =
                new PollingFrame(POLLING_LOOP_TYPE_UNKNOWN, ECP_V1_PAYMENT, -1, 0, false);

        mStatsdUtils.tallyPollingFrame(ECP_V1_PAYMENT_KEY, frameData);
        mStatsdUtils.logPollingFrames();
        mStatsdUtils.tallyPollingFrame(ECP_V1_PAYMENT_KEY, frameData);
        mStatsdUtils.logPollingFrames();

        StatsdUtils.PollingFrameLog expectedFrame = new StatsdUtils.PollingFrameLog(ECP_V1_PAYMENT);
        expectedFrame.repeatCount = 1;

        verify(mStatsdUtils, times(2)).tallyPollingFrame(any(), any());
        verify(mStatsdUtils, times(2)).logPollingFrames();
        verify(mStatsdUtils, times(2)).writeToStatsd(expectedFrame);
        verifyNoMoreInteractions(mStatsdUtils);
    }

    @Test
    public void testLogPollingFrame_ecp2Repeated() {
        PollingFrame frameData =
                new PollingFrame(POLLING_LOOP_TYPE_UNKNOWN, ECP_V2_TRANSIT_MBTA, -1, 0, false);

        mStatsdUtils.tallyPollingFrame(ECP_V2_TRANSIT_MBTA_KEY, frameData);
        mStatsdUtils.tallyPollingFrame(ECP_V2_TRANSIT_MBTA_KEY, frameData);
        mStatsdUtils.tallyPollingFrame(ECP_V2_TRANSIT_MBTA_KEY, frameData);

        mStatsdUtils.logPollingFrames();

        StatsdUtils.PollingFrameLog expectedFrame =
                new StatsdUtils.PollingFrameLog(ECP_V2_TRANSIT_MBTA);
        expectedFrame.repeatCount = 3;
        verify(mStatsdUtils).writeToStatsd(expectedFrame);
    }

    @Test
    public void testLogPollingFrame_ecp2RepeatedTwoTypes() {
        PollingFrame frame1Data =
                new PollingFrame(POLLING_LOOP_TYPE_UNKNOWN, UNKNOWN_FRAME, -1, 0, false);

        PollingFrame frame2Data =
                new PollingFrame(POLLING_LOOP_TYPE_UNKNOWN, ECP_V2_TRANSIT_MBTA, -1, 0, false);

        mStatsdUtils.tallyPollingFrame(UNKNOWN_FRAME_KEY, frame1Data);
        mStatsdUtils.tallyPollingFrame(ECP_V2_TRANSIT_MBTA_KEY, frame2Data);
        mStatsdUtils.tallyPollingFrame(UNKNOWN_FRAME_KEY, frame1Data);
        mStatsdUtils.logPollingFrames();

        StatsdUtils.PollingFrameLog expectedFrame = new StatsdUtils.PollingFrameLog(UNKNOWN_FRAME);
        expectedFrame.repeatCount = 2;
        verify(mStatsdUtils).writeToStatsd(expectedFrame);

        expectedFrame = new StatsdUtils.PollingFrameLog(ECP_V2_TRANSIT_MBTA);
        expectedFrame.repeatCount = 1;

        verify(mStatsdUtils, times(3)).tallyPollingFrame(any(), any());
        verify(mStatsdUtils).logPollingFrames();
        verify(mStatsdUtils).writeToStatsd(expectedFrame);
        verifyNoMoreInteractions(mStatsdUtils);
    }

    @Test
    public void testFieldGain() {
        PollingFrame frame1Data =
                new PollingFrame(POLLING_LOOP_TYPE_UNKNOWN, UNKNOWN_FRAME, GAIN_1, 0, false);

        PollingFrame frame2Data =
                new PollingFrame(POLLING_LOOP_TYPE_UNKNOWN, UNKNOWN_FRAME, GAIN_1, 0, false);

        PollingFrame frame3Data =
                new PollingFrame(POLLING_LOOP_TYPE_UNKNOWN, UNKNOWN_FRAME, GAIN_2, 0, false);

        mStatsdUtils.tallyPollingFrame(UNKNOWN_FRAME_KEY, frame1Data);
        mStatsdUtils.tallyPollingFrame(UNKNOWN_FRAME_KEY, frame2Data);
        mStatsdUtils.tallyPollingFrame(UNKNOWN_FRAME_KEY, frame3Data);
        mStatsdUtils.logPollingFrames();

        verify(mStatsdUtils, times(3)).tallyPollingFrame(any(), any());
        verify(mStatsdUtils).logPollingFrames();
        verify(mStatsdUtils, times(1)).logFieldChanged(true, GAIN_1);
        verify(mStatsdUtils, times(1)).logFieldChanged(true, GAIN_2);
        verify(mStatsdUtils).writeToStatsd(any());
        verifyNoMoreInteractions(mStatsdUtils);
    }


    private static final int GAIN_1 = 42;
    private static final int GAIN_2 = 25;
    private static final byte[] ECP_V1_PAYMENT = new byte[]{0x6a, 0x01, 0x00, 0x00, 0x00};
    private static final String ECP_V1_PAYMENT_KEY =
            HexFormat.of().formatHex(ECP_V1_PAYMENT).toUpperCase(Locale.ROOT);
    private static final byte[] ECP_V2_TRANSIT_MBTA =
            new byte[]{0x6a, 0x02, (byte) 0xc8, 0x01, 0x00, 0x03, 0x00, 0x03, 0x7f, 0x00, 0x00,
                    0x00, 0x00, 0x71, (byte) 0xe7};
    private static final String ECP_V2_TRANSIT_MBTA_KEY =
            HexFormat.of().formatHex(ECP_V2_TRANSIT_MBTA).toUpperCase(Locale.ROOT);

    private static final byte[] UNKNOWN_FRAME =
            new byte[]{0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x20, 0x41, 0x6e, 0x64, 0x72, 0x6f, 0x69, 0x64,
                    0x21};
    private static final String UNKNOWN_FRAME_KEY =
            HexFormat.of().formatHex(UNKNOWN_FRAME).toUpperCase(Locale.ROOT);
}
