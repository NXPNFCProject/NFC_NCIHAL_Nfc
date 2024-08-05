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

package com.android.nfc;

import static com.android.nfc.NfcEventLog.FORMATTER;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.os.test.TestLooper;
import android.util.AtomicFile;

import com.android.nfc.proto.NfcEventProto;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayDeque;

@RunWith(AndroidJUnit4.class)
public class NfcEventLogTest {
    @Mock Context mContext;
    @Mock NfcInjector mNfcInjector;
    @Mock AtomicFile mLogFile;
    @Mock Resources mResources;
    @Mock FileOutputStream mFileOutputStream;
    TestLooper mLooper;
    NfcEventLog mNfcEventLog;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getInteger(R.integer.max_event_log_num)).thenReturn(10);
        when(mLogFile.startWrite()).thenReturn(mFileOutputStream);
        mNfcEventLog = new NfcEventLog(mContext, mNfcInjector, mLooper.getLooper(), mLogFile);
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testLogEvent() throws Exception {
        NfcEventProto.EventType eventType =
                NfcEventProto.EventType.newBuilder()
                        .setBootupState(NfcEventProto.NfcBootupState.newBuilder()
                                .setEnabled(true)
                                .build())
                        .build();
        LocalDateTime localDateTime = LocalDateTime.MIN;
        when(mNfcInjector.getLocalDateTime()).thenReturn(localDateTime);
        mNfcEventLog.logEvent(eventType);
        mLooper.dispatchAll();

        NfcEventProto.Event expectedEvent = NfcEventProto.Event.newBuilder()
                .setTimestamp(localDateTime.format(FORMATTER))
                .setEventType(eventType)
                .build();
        NfcEventProto.EventList expectedEventList =
                NfcEventProto.EventList.newBuilder().addEvents(expectedEvent).build();
        verify(mFileOutputStream).write(AdditionalMatchers.aryEq(expectedEventList.toByteArray()));
    }

    @Test
    public void testMultipleLogEvents() throws Exception {
        NfcEventProto.EventType eventType =
                NfcEventProto.EventType.newBuilder()
                        .setBootupState(NfcEventProto.NfcBootupState.newBuilder()
                                .setEnabled(true)
                                .build())
                        .build();
        LocalDateTime localDateTime = LocalDateTime.MIN;
        when(mNfcInjector.getLocalDateTime()).thenReturn(localDateTime);

        // Log the event twice.
        mNfcEventLog.logEvent(eventType);
        mLooper.dispatchAll();

        mNfcEventLog.logEvent(eventType);
        mLooper.dispatchAll();

        NfcEventProto.Event expectedEvent = NfcEventProto.Event.newBuilder()
                .setTimestamp(localDateTime.format(FORMATTER))
                .setEventType(eventType)
                .build();
        NfcEventProto.EventList expectedEventList =
                NfcEventProto.EventList.newBuilder()
                        .addEvents(expectedEvent)
                        .addEvents(expectedEvent)
                        .build();
        verify(mFileOutputStream).write(AdditionalMatchers.aryEq(expectedEventList.toByteArray()));
    }

    @Test
    public void testReadEventsFromLogFile() throws Exception {
        LocalDateTime localDateTime = LocalDateTime.MIN;
        NfcEventProto.EventType eventType =
                NfcEventProto.EventType.newBuilder()
                        .setBootupState(NfcEventProto.NfcBootupState.newBuilder()
                                .setEnabled(true)
                                .build())
                        .build();
        NfcEventProto.Event event = NfcEventProto.Event.newBuilder()
                .setTimestamp(localDateTime.format(FORMATTER))
                .setEventType(eventType)
                .build();
        NfcEventProto.EventList eventList =
                NfcEventProto.EventList.newBuilder()
                        .addEvents(event)
                        .addEvents(event)
                        .build();

        when(mLogFile.readFully()).thenReturn(eventList.toByteArray());
        // Recreate the instance to simulate reading the log file.
        mNfcEventLog = new NfcEventLog(mContext, mNfcInjector, mLooper.getLooper(), mLogFile);
        mLooper.dispatchAll();

        ArrayDeque<NfcEventProto.Event> expectedEventList = new ArrayDeque<NfcEventProto.Event>();
        expectedEventList.add(event);
        expectedEventList.add(event);

        ArrayDeque<NfcEventProto.Event> retrievedEventsList = mNfcEventLog.getEventsList();
        assertThat(retrievedEventsList.toString()).isEqualTo(expectedEventList.toString());

    }
}
