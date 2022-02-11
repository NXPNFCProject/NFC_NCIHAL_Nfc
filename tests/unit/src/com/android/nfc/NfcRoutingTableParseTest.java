/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class NfcRoutingTableParseTest {
    private static final String TAG = NfcRoutingTableParseTest.class.getSimpleName();
    private RoutingTableParser mRoutingTableParser;

    // NFCEE-ID
    static final byte EE_ID_HOST = (byte) 0x00;
    static final byte EE_ID_UICC = (byte) 0x81;
    static final byte EE_ID_ESE = (byte) 0x86;

    // Power State Mask
    static final byte APPLY_ALL = (byte) 0x3F;
    static final byte SWITCH_ON_SUB_3 = (byte) 0x20;
    static final byte SWITCH_ON_SUB_2 = (byte) 0x10;
    static final byte SWITCH_ON_SUB_1 = (byte) 0x08;
    static final byte BATTERY_OFF = (byte) 0x04;
    static final byte SWITCH_OFF = (byte) 0x02;
    static final byte SWITCH_ON = (byte) 0x01;

    @Before
    public void setUp() {
        mRoutingTableParser = new RoutingTableParser();
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testParseValidTechnologyEntry() {
        /**
         * set qualifier = 0x00 to indicates the routing is allowed for the power modes
         * where it is not supported
         */
        byte qualifier = (byte) 0x00;
        byte type = RoutingTableParser.TYPE_TECHNOLOGY;
        byte eeId = EE_ID_HOST;
        byte pwrState = (byte) (SWITCH_ON_SUB_3 | SWITCH_ON_SUB_2 | SWITCH_ON_SUB_1 | SWITCH_ON);
        byte[] entry = hexStrToByteArray("01");
        byte[] rt = generateRoutingEntry(qualifier, type, eeId, pwrState, entry);
        mRoutingTableParser.parse(rt);

        int ret = mRoutingTableParser.getCommitStatus(type, entry);

        assertThat(ret).isEqualTo(mRoutingTableParser.STATS_HOST_OK);
    }

    @Test
    public void testParseInvalidTechnologyEntry() {
        /**
         * set qualifier = 0x00 to indicates the routing is allowed for the power modes
         * where it is not supported
         */
        byte qualifier = (byte) 0x00;
        byte type = RoutingTableParser.TYPE_TECHNOLOGY;
        byte eeId = EE_ID_HOST;
        byte pwrState = (byte) (SWITCH_ON_SUB_3 | SWITCH_ON_SUB_2 | SWITCH_ON_SUB_1 | SWITCH_ON);
        byte[] entry = hexStrToByteArray("0001");
        byte[] rt = generateRoutingEntry(qualifier, type, eeId, pwrState, entry);
        mRoutingTableParser.parse(rt);

        int ret = mRoutingTableParser.getCommitStatus(type, entry);

        assertThat(ret).isEqualTo(mRoutingTableParser.STATS_NOT_FOUND);
    }

    @Test
    public void testParseValidProtocolEntry() {
        /**
         * set qualifier = 0x00 to indicates the routing is allowed for the power modes
         * where it is not supported
         */
        byte qualifier = (byte) 0x00;
        byte type = RoutingTableParser.TYPE_PROTOCOL;
        byte eeId = EE_ID_HOST;
        byte pwrState = SWITCH_ON;
        byte[] entry = hexStrToByteArray("04");
        byte[] rt = generateRoutingEntry(qualifier, type, eeId, pwrState, entry);
        mRoutingTableParser.parse(rt);

        int ret = mRoutingTableParser.getCommitStatus(type, entry);

        assertThat(ret).isEqualTo(mRoutingTableParser.STATS_HOST_OK);
    }

    @Test
    public void testParseInvalidProtocolEntry() {
        /**
         * set qualifier = 0x00 to indicates the routing is allowed for the power modes
         * where it is not supported
         */
        byte qualifier = (byte) 0x00;
        byte type = RoutingTableParser.TYPE_PROTOCOL;
        byte eeId = EE_ID_HOST;
        byte pwrState = SWITCH_ON;
        byte[] entry = hexStrToByteArray("0405");
        byte[] rt = generateRoutingEntry(qualifier, type, eeId, pwrState, entry);
        mRoutingTableParser.parse(rt);

        int ret = mRoutingTableParser.getCommitStatus(type, entry);

        assertThat(ret).isEqualTo(mRoutingTableParser.STATS_NOT_FOUND);
    }

    @Test
    public void testParseValidAidEntry() {
        /**
         * set qualifier = 0x40 to indicates the routing is blocked for the power modes
         * where it is not supported
         */
        byte qualifier = (byte) 0x40;
        byte type = RoutingTableParser.TYPE_AID;
        byte eeId = EE_ID_UICC;
        byte pwrState = (byte) (APPLY_ALL ^ BATTERY_OFF);
        byte[] entry = hexStrToByteArray("6E6663746573743031");
        byte[] rt = generateRoutingEntry(qualifier, type, eeId, pwrState, entry);
        mRoutingTableParser.parse(rt);

        int ret = mRoutingTableParser.getCommitStatus(type, entry);

        assertThat(ret).isEqualTo(mRoutingTableParser.STATS_OFFHOST_OK);
    }

    @Test
    public void testParseInvalidAidEntry() {
        /**
         * set qualifier = 0x40 to indicates the routing is blocked for the power modes
         * where it is not supported
         */
        byte qualifier = (byte) 0x40;
        byte type = RoutingTableParser.TYPE_AID;
        byte eeId = EE_ID_UICC;
        byte pwrState = (byte) (APPLY_ALL ^ BATTERY_OFF);
        byte[] entry = hexStrToByteArray("6E66637465737430316E6663746573743031");
        byte[] rt = generateRoutingEntry(qualifier, type, eeId, pwrState, entry);
        mRoutingTableParser.parse(rt);

        int ret = mRoutingTableParser.getCommitStatus(type, entry);

        assertThat(ret).isEqualTo(mRoutingTableParser.STATS_NOT_FOUND);
    }

    @Test
    public void testParseValidSystemCodeEntry() {
        /**
         * set qualifier = 0x40 to indicates the routing is blocked for the power modes
         * where it is not supported
         */
        byte qualifier = (byte) 0x40;
        byte type = RoutingTableParser.TYPE_SYSTEMCODE;
        byte eeId = EE_ID_ESE;
        byte pwrState = (byte) (APPLY_ALL ^ BATTERY_OFF);
        byte[] entry = hexStrToByteArray("FEFE");
        byte[] rt = generateRoutingEntry(qualifier, type, eeId, pwrState, entry);
        mRoutingTableParser.parse(rt);

        int ret = mRoutingTableParser.getCommitStatus(type, entry);

        assertThat(ret).isEqualTo(mRoutingTableParser.STATS_OFFHOST_OK);
    }

    @Test
    public void testParseSeveralValidSystemCodeEntry() {
        /**
         * set qualifier = 0x40 to indicates the routing is blocked for the power modes
         * where it is not supported
         */
        byte qualifier = (byte) 0x40;
        byte type = RoutingTableParser.TYPE_SYSTEMCODE;
        byte eeId = EE_ID_ESE;
        byte pwrState = (byte) (APPLY_ALL ^ BATTERY_OFF);
        byte[] entry1 = hexStrToByteArray("FEFE");
        byte[] entry2 = hexStrToByteArray("EEEE");
        byte[] entryAll = hexStrToByteArray("FEFEEEEE");
        byte[] rt = generateRoutingEntry(qualifier, type, eeId, pwrState, entryAll);
        mRoutingTableParser.parse(rt);

        int ret1 = mRoutingTableParser.getCommitStatus(type, entry1);
        int ret2 = mRoutingTableParser.getCommitStatus(type, entry2);

        assertThat(ret1).isEqualTo(mRoutingTableParser.STATS_OFFHOST_OK);
        assertThat(ret2).isEqualTo(mRoutingTableParser.STATS_OFFHOST_OK);
    }

    @Test
    public void testParseInvalidSystemCodeEntry() {
        /**
         * set qualifier = 0x40 to indicates the routing is blocked for the power modes
         * where it is not supported
         */
        byte qualifier = (byte) 0x40;
        byte type = RoutingTableParser.TYPE_SYSTEMCODE;
        byte eeId = EE_ID_ESE;
        byte pwrState = (byte) (APPLY_ALL ^ BATTERY_OFF);
        byte[] entry = hexStrToByteArray("FEFEFE");
        byte[] rt = generateRoutingEntry(qualifier, type, eeId, pwrState, entry);
        mRoutingTableParser.parse(rt);

        int ret = mRoutingTableParser.getCommitStatus(type, entry);

        assertThat(ret).isEqualTo(mRoutingTableParser.STATS_NOT_FOUND);
    }

    private byte[] generateRoutingEntry(byte qualifier, byte type, byte eeId, byte pwrState,
            byte[] entry) {
        int length = 2 + entry.length;
        byte[] rt = new byte[length + 2];
        rt[0] = (byte) (qualifier | type);
        rt[1] = (byte) length;
        rt[2] = eeId;
        rt[3] = pwrState;

        for (int i = 0; i < entry.length; i++) {
            rt[i + 4] = entry[i];
        }

        return rt;
    }

    private byte[] hexStrToByteArray(String hexStr) {
        if (hexStr.length() % 2 != 0) {
            return new byte[0];
        }

        char[] hex = hexStr.toCharArray();
        int length = hexStr.length() / 2;
        byte[] byteArr = new byte[length];
        for (int i = 0; i < length; i++) {
            int high = Character.digit(hex[i * 2], 16);
            int low = Character.digit(hex[i * 2 + 1], 16);
            int value = (high << 4) | low;

            if (value > 127) {
                value -= 256;
            }
            byteArr [i] = (byte) value;
        }

        return byteArr;
    }
}
