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

import android.nfc.NfcAdapter;
import android.nfc.NfcAdapter.ReaderCallback;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.NfcA;
import android.util.Log;

import com.android.nfc.utils.HceUtils;

import java.io.IOException;

public class ProtocolParamsReaderActivity extends BaseReaderActivity implements ReaderCallback {
    public static final String TAG = "ProtocolParamsReaderActivity";

    @Override
    protected void onResume() {
        super.onResume();
        mAdapter.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_A
                        | NfcAdapter.FLAG_READER_NFC_BARCODE
                        | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null);
    }

    /** Parses protocol params from tag */
    public boolean parseProtocolParameters(
            StringBuilder sb, byte[] uid, short sak, byte[] atqa, byte[] ats) {

        boolean success = true;

        sb.append("UID: " + HceUtils.getHexBytes(null, uid) + "\n\n");
        sb.append("SAK: 0x" + Integer.toHexString(sak & 0xFF) + "\n");

        if ((sak & 0x20) != 0) {
            sb.append("    (OK) ISO-DEP bit (0x20) is set.\n");
        } else {
            success = false;
            sb.append("    (FAIL) ISO-DEP bit (0x20) is NOT set.\n");
        }

        if ((sak & 0x40) != 0) {
            sb.append("    (OK) P2P bit (0x40) is set.\n");
        } else {
            sb.append("    (WARN) P2P bit (0x40) is NOT set.\n");
        }

        sb.append("\n");
        sb.append("ATQA: " + HceUtils.getHexBytes(null, atqa) + "\n");
        sb.append("\n");

        sb.append("ATS: " + HceUtils.getHexBytes(null, ats) + "\n");
        sb.append("    TL: 0x" + Integer.toHexString(ats[0] & 0xFF) + "\n");
        sb.append("    T0: 0x" + Integer.toHexString(ats[1] & 0xFF) + "\n");

        boolean ta_present = false;
        boolean tb_present = false;
        boolean tc_present = false;
        int atsIndex = 1;
        if ((ats[atsIndex] & 0x40) != 0) {
            sb.append("        (OK) T(C) is present (bit 7 is set).\n");
            tc_present = true;
        } else {
            success = false;
            sb.append("        (FAIL) T(C) is not present (bit 7 is NOT set).\n");
        }
        if ((ats[atsIndex] & 0x20) != 0) {
            sb.append("        (OK) T(B) is present (bit 6 is set).\n");
            tb_present = true;
        } else {
            success = false;
            sb.append("        (FAIL) T(B) is not present (bit 6 is NOT set).\n");
        }
        if ((ats[atsIndex] & 0x10) != 0) {
            sb.append("        (OK) T(A) is present (bit 5 is set).\n");
            ta_present = true;
        } else {
            success = false;
            sb.append("        (FAIL) T(A) is not present (bit 5 is NOT set).\n");
        }
        int fsc = ats[atsIndex] & 0x0F;
        if (fsc > 8) {
            success = false;
            sb.append("        (FAIL) FSC " + fsc + " is > 8\n");
        } else if (fsc < 2) {
            sb.append("        (FAIL EMVCO) FSC " + fsc + " is < 2\n");
        } else {
            sb.append("        (OK) FSC = " + fsc + "\n");
        }

        atsIndex++;
        if (ta_present) {
            sb.append("    TA: 0x" + Integer.toHexString(ats[atsIndex] & 0xff) + "\n");
            if ((ats[atsIndex] & 0x80) != 0) {
                sb.append("        (OK) bit 8 set, indicating only same bit rate divisor.\n");
            } else {
                sb.append(
                        "        (FAIL EMVCO) bit 8 NOT set, indicating support for asymmetric "
                                + "bit rate divisors. EMVCo requires bit 8 set.\n");
            }
            if ((ats[atsIndex] & 0x70) != 0) {
                sb.append("        (FAIL EMVCO) EMVCo requires bits 7 to 5 set to 0.\n");
            } else {
                sb.append("        (OK) bits 7 to 5 indicating only 106 kbit/s L->P supported.\n");
            }
            if ((ats[atsIndex] & 0x7) != 0) {
                sb.append("        (FAIL EMVCO) EMVCo requires bits 3 to 1 set to 0.\n");
            } else {
                sb.append("        (OK) bits 3 to 1 indicating only 106 kbit/s P->L supported.\n");
            }
            atsIndex++;
        }

        if (tb_present) {
            sb.append("    TB: 0x" + Integer.toHexString(ats[3] & 0xFF) + "\n");
            int fwi = (ats[atsIndex] & 0xF0) >> 4;
            if (fwi > 8) {
                success = false;
                sb.append("        (FAIL) FWI=" + fwi + ", should be <= 8\n");
            } else if (fwi == 8) {
                sb.append("        (FAIL EMVCO) FWI=" + fwi + ", EMVCo requires <= 7\n");
            } else {
                sb.append("        (OK) FWI=" + fwi + "\n");
            }
            int sfgi = ats[atsIndex] & 0x0F;
            if (sfgi > 8) {
                success = false;
                sb.append("        (FAIL) SFGI=" + sfgi + ", should be <= 8\n");
            } else {
                sb.append("        (OK) SFGI=" + sfgi + "\n");
            }
            atsIndex++;
        }

        if (tc_present) {
            sb.append("    TC: 0x" + Integer.toHexString(ats[atsIndex] & 0xFF) + "\n");
            boolean nadSupported = (ats[atsIndex] & 0x01) != 0;
            if (nadSupported) {
                success = false;
                sb.append("        (FAIL) NAD bit is not allowed to be set.\n");
            } else {
                sb.append("        (OK) NAD bit is not set.\n");
            }
            atsIndex++;
            // See if there's any bytes left for general bytes
            if (atsIndex + 1 < ats.length) {
                int bytesToCopy = ats.length - atsIndex;
                byte[] historical_bytes = new byte[bytesToCopy];
                System.arraycopy(ats, atsIndex, historical_bytes, 0, bytesToCopy);
                sb.append(
                        "\n(OK) Historical bytes: " + HceUtils.getHexBytes(null, historical_bytes));
            }
        }
        return success;
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        final StringBuilder sb = new StringBuilder();
        IsoDep isoDep = IsoDep.get(tag);
        NfcA nfcA = NfcA.get(tag);
        boolean success = false;
        if (nfcA == null || isoDep == null) {
            return;
        }
        try {
            nfcA.connect();
            byte[] ats = nfcA.transceive(new byte[] {(byte) 0xE0, (byte) 0xF0});
            success = parseProtocolParameters(sb, tag.getId(), nfcA.getSak(), nfcA.getAtqa(), ats);
        } catch (IOException e) {
            sb.insert(0, "Test failed. IOException (did you keep the devices in range?)\n\n.");
        } finally {
            if (success) {
                Log.d(TAG, "Success:\n" + sb);
                setTestPassed();
            } else {
                Log.e(TAG, "Test Failed:\n" + sb);
            }
            try {
                nfcA.transceive(new byte[] {(byte) 0xC2});
                nfcA.close();
                isoDep.connect();
            } catch (IOException e) {
                Log.e(TAG, "IO Exception", e);
            }
        }
    }
}
