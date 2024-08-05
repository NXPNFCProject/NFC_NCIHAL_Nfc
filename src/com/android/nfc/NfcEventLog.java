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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.nfc.proto.NfcEventProto;

import libcore.util.HexEncoding;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;

/**
 * Used to store important NFC event logs persistently for debugging purposes.
 */
public final class NfcEventLog {
    private static final String TAG = "NfcEventLog";
    @VisibleForTesting
    public static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final Context mContext;
    private final NfcInjector mNfcInjector;
    private final Handler mHander;
    private final int mMaxEventNum;
    private final AtomicFile mLogFile;
    private final ArrayDeque<NfcEventProto.Event> mEventList;

    public NfcEventLog(Context context, NfcInjector nfcInjector, Looper looper,
            AtomicFile logFile) {
        mContext = context;
        mNfcInjector = nfcInjector;
        mMaxEventNum = context.getResources().getInteger(R.integer.max_event_log_num);
        mEventList = new ArrayDeque<>(0);
        mHander = new Handler(looper);
        mLogFile = logFile;
        mHander.post(() -> readListFromLogFile());
    }

    private byte[] readLogFile() {
        byte[] bytes;
        try {
            bytes = mLogFile.readFully();
        } catch (IOException e) {
            return null;
        }
        return bytes;
    }

    private void writeLogFile(byte[] bytes) throws IOException {
        FileOutputStream out = null;
        try {
            out = mLogFile.startWrite();
            out.write(bytes);
            mLogFile.finishWrite(out);
        } catch (IOException e) {
            if (out != null) {
                mLogFile.failWrite(out);
            }
            throw e;
        }
    }

    private void readListFromLogFile() {
        byte[] bytes = readLogFile();
        if (bytes == null) {
            Log.i(TAG, "No NFC events found in log file");
            return;
        }
        NfcEventProto.EventList eventList;
        try {
            eventList = NfcEventProto.EventList.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            Log.e(TAG, "Failed to deserialize events from log file", e);
            return;
        }
        synchronized (mEventList) {
            for (NfcEventProto.Event event : eventList.getEventsList()) {
                mEventList.add(event);
            }
        }
    }

    private void writeListToLogFile() {
        NfcEventProto.EventList.Builder eventListBuilder =
                NfcEventProto.EventList.newBuilder();
        synchronized (mEventList) {
            for (NfcEventProto.Event event:  mEventList) {
                eventListBuilder.addEvents(event);
            }
        }
        byte[] bytes = eventListBuilder.build().toByteArray();
        Log.d(TAG, "writeListToLogFile: " + HexEncoding.encodeToString(bytes));
        try {
            writeLogFile(bytes);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write to log file", e);
        }
    }

    private void addAndWriteListToLogFile(NfcEventProto.Event event) {
        synchronized (mEventList) {
            // Trim the list to MAX_EVENTS.
            if (mEventList.size() == mMaxEventNum) {
                mEventList.remove();
            }
            mEventList.add(event);
            writeListToLogFile();
        }
    }

    /**
     * Log NFC event
     * Does not block the main NFC thread for logging, posts it to the logging thraead.
     */
    public void logEvent(NfcEventProto.EventType eventType) {
        mHander.post(() -> {
            NfcEventProto.Event event = NfcEventProto.Event.newBuilder()
                    .setTimestamp(mNfcInjector.getLocalDateTime().format(FORMATTER))
                    .setEventType(eventType)
                    .build();
            addAndWriteListToLogFile(event);
        });
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("===== Nfc Event Log =====");
        synchronized (mEventList) {
            for (NfcEventProto.Event event: mEventList) {
                // Cleanup the proto string output to make it more readable.
                String eventTypeString = event.getEventType().toString()
                    .replaceAll("# com.android.nfc.proto.*", "")
                    .replaceAll("\n", "");
                pw.println(event.getTimestamp() + ": " + eventTypeString);
            }
        }
        pw.println("===== Nfc Event Log =====");
    }

    @VisibleForTesting
    public ArrayDeque<NfcEventProto.Event> getEventsList() {
        return mEventList;
    }
}
