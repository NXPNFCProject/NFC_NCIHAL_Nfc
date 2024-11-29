/*
 * Copyright (C) 2024 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.nfc;
import android.nfc.NdefRecord;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import java.io.UnsupportedEncodingException;
import java.util.Locale;

/** Unit tests for {@link NdefRecord}. */
@RunWith(JUnit4.class)
public class NdefRecordTest {

    private static final byte[] PAYLOAD = new byte[] { 0x01, 0x02, 0x03 };
    private static final String LANGUAGE_CODE = "en";
    private static final String TEXT = "Hello, world!";

    String getLanguageCode(NdefRecord record) {
        byte len = record.getPayload()[0];
        return new String(record.getPayload(), 1, len);
    }
    String getText(NdefRecord record) {
        byte langLen = record.getPayload()[0];
        int bufLen = record.getPayload().length;
        return new String(record.getPayload(), langLen + 1, bufLen - langLen - 1);
    }

    @Test
    public void testCreateRecord() throws UnsupportedEncodingException {
        NdefRecord record = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        assertEquals(NdefRecord.TNF_WELL_KNOWN, record.getTnf());
        assertEquals(LANGUAGE_CODE, getLanguageCode(record));
        assertEquals(TEXT, getText(record));
    }

    @Test
    public void testCreateRecordWithEmptyPayload() throws UnsupportedEncodingException {
        NdefRecord record = NdefRecord.createTextRecord(LANGUAGE_CODE, "");
        assertEquals(NdefRecord.TNF_WELL_KNOWN, record.getTnf());
        assertEquals(LANGUAGE_CODE, getLanguageCode(record));
        assertEquals(3, record.getPayload().length);
    }

    @Test
    public void testCreateRecordWithNullLanguageCode() throws UnsupportedEncodingException {
        NdefRecord record = NdefRecord.createTextRecord(null, TEXT);
        assertEquals(NdefRecord.TNF_WELL_KNOWN, record.getTnf());
        assertEquals(Locale.getDefault().getLanguage(), getLanguageCode(record));
        assertEquals(TEXT, getText(record));
    }

    @Test
    public void testCreateRecordWithInvalidTnf() {
        assertThrows(IllegalArgumentException.class, () -> {
            NdefRecord record = new NdefRecord((short) 21, null, null, PAYLOAD);
            assertNotNull(record);
        });
    }

    @Test
    public void testCreateRecordWithNullPayload() {
        assertThrows(NullPointerException.class, () -> {
            NdefRecord record = NdefRecord.createTextRecord( null, null);
            assertNotNull(record);
        });
    }

    @Test
    public void testEquals() {
        NdefRecord record1 = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        NdefRecord record2 = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        assertEquals(record1, record2);
    }

    @Test
    public void testEqualsWithDifferentTnf() {
        NdefRecord record1 = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        NdefRecord record2 = NdefRecord.createTextRecord(null, new String(PAYLOAD));
        assertNotEquals(record1, record2);
    }

    @Test
    public void testEqualsWithDifferentLanguageCode() {
        NdefRecord record1 = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        NdefRecord record2 = NdefRecord.createTextRecord("fr", TEXT);
        assertNotEquals(record1, record2);
    }

    @Test
    public void testEqualsWithDifferentPayload() {
        NdefRecord record1 = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        NdefRecord record2 = NdefRecord.createTextRecord(LANGUAGE_CODE, "Goodbye, world!");
        assertNotEquals(record1, record2);
    }

    @Test
    public void testHashCode() {
        NdefRecord record1 = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        NdefRecord record2 = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        assertEquals(record1.hashCode(), record2.hashCode());
    }

    @Test
    public void testHashCodeWithDifferentTnf() {
        NdefRecord record1 = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        NdefRecord record2 = NdefRecord.createTextRecord(null, new String(PAYLOAD));
        assertNotEquals(record1.hashCode(), record2.hashCode());
    }

    @Test
    public void testHashCodeWithDifferentLanguageCode() {
        NdefRecord record1 = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        NdefRecord record2 = NdefRecord.createTextRecord("fr", TEXT);
        assertNotEquals(record1.hashCode(), record2.hashCode());
    }

    @Test
    public void testHashCodeWithDifferentPayload() {
        NdefRecord record1 = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        NdefRecord record2 = NdefRecord.createTextRecord(LANGUAGE_CODE, "Goodbye, world!");
        assertNotEquals(record1.hashCode(), record2.hashCode());
    }

    @Test
    public void testToString() {
        NdefRecord record = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        assertEquals("NdefRecord tnf=1 type=54 payload=02656E48656C6C6F2C20776F726C6421",
                record.toString());
    }

    @Test
    public void testToStringWithNullLanguageCode() {
        NdefRecord record = NdefRecord.createTextRecord(null, TEXT);
        assertEquals(Locale.getDefault().getLanguage(), getLanguageCode(record));
        assertEquals(TEXT, getText(record));
    }

    @Test
    public void testParcelable() {
        NdefRecord record = NdefRecord.createTextRecord(LANGUAGE_CODE, TEXT);
        Parcel parcel = Parcel.obtain();
        record.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        NdefRecord newRecord = NdefRecord.CREATOR.createFromParcel(parcel);
        assertEquals(record, newRecord);
    }
}