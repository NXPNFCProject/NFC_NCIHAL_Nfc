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

import android.os.SystemProperties;
import java.util.Locale;
import android.util.Log;

import java.io.PrintWriter;

/**
* Parse the Routing Table from the last backup lmrt cmd and dump it with a clear typography
*/
public class RoutingTableParser {
    static final boolean DBG = SystemProperties.getBoolean("persist.nfc.debug_enabled", false);
    private static final String TAG = "RoutingTableParser";

    private String getTechStr(int value) {
        String[] tech_mask_list = {
            "TECHNOLOGY_A", "TECHNOLOGY_B", "TECHNOLOGY_F", "TECHNOLOGY_V"
        };

        if (value > tech_mask_list.length) {
            return "UNSUPPORTED_TECH";
        }
        return tech_mask_list[value];
    }

    private String getProtoStr(int value) {
        String[] proto_mask_list = {
            "PROTOCOL_UNDETERMINED", "PROTOCOL_T1T", "PROTOCOL_T2T", "PROTOCOL_T3T",
            "PROTOCOL_ISO_DEP", "PROTOCOL_NFC_DEP", "PROTOCOL_T5T", "PROTOCOL_NDEF"
        };
        if (value > proto_mask_list.length) {
            return "UNSUPPORTED_PROTO";
        }
        return proto_mask_list[value];
    }

    private String getAidStr(byte[] rt, int offset, int aidLen) {
        String Aid = "";
        for (int i = 0; i < aidLen; i++) {
            Aid += String.format("%02X", rt[offset + i]);
        }

        if (Aid.length() == 0) {
            return "Empty_AID";
        }
        return "AID_" + Aid;
    }

    private String getSystemCodeStr(byte[] rt, int offset, int scLen) {
        String SystemCode = "";
        for (int i = 0; i < scLen; i++) {
            SystemCode += String.format("%02X", rt[offset + i]);
        }
        return "SYSTEMCODE_" + SystemCode;
    }

    private String getBlockCtrlStr(int mask) {
        if ((mask & 0x40) != 0) {
            return "True";
        }
        return "False";
    }

    private String getPrefixSubsetStr(int mask) {
        String prefix_subset_str = "";
        if ((mask & 0x10) != 0) {
            prefix_subset_str += "Prefix ";
        }
        if ((mask & 0x20) != 0) {
            prefix_subset_str += "Subset";
        }
        if (prefix_subset_str.equals("")){
            return "Exact";
        }
        return prefix_subset_str;
    }

    private String formatRow(String entry, String eeId,
            String pwrState, String blkCtrl, String extra) {
        String fmt = "\t%-36s\t%8s\t%-11s\t%-10s\t%-10s";
        return String.format(fmt, entry, eeId, pwrState, blkCtrl, extra);
    }

    private void dumpTechEntry(byte[] rt, int rtSize, PrintWriter pw, int offset) {
        if (offset + 4 >= rtSize) return;

        String blkCtrl = getBlockCtrlStr(rt[offset] & 0xF0);
        String eeId = String.format("0x%02X", rt[offset + 2]);
        String pwrState = String.format("0x%02X", rt[offset + 3]);
        String entry = getTechStr(rt[offset + 4]);

        pw.println(formatRow(entry, eeId, pwrState, blkCtrl, ""));
    }

    private void dumpProtoEntry(byte[] rt, int rtSize, PrintWriter pw, int offset) {
        if (offset + 4 >= rtSize) return;

        String blkCtrl = getBlockCtrlStr(rt[offset] & 0xF0);
        String eeId = String.format("0x%02X", rt[offset + 2]);
        String pwrState = String.format("0x%02X", rt[offset + 3]);
        String entry = getProtoStr(rt[offset + 4]);

        pw.println(formatRow(entry, eeId, pwrState, blkCtrl, ""));
    }

    private void dumpAidEntry(byte[] rt, int rtSize, PrintWriter pw, int offset) {
        if (offset + 4 + rt[offset + 1] - 2 >= rtSize) return;

        String blkCtrl = getBlockCtrlStr(rt[offset] & 0xF0);
        String extra = getPrefixSubsetStr(rt[offset] & 0xF0);
        String eeId = String.format("0x%02X", rt[offset + 2]);
        String pwrState = String.format("0x%02X", rt[offset + 3]);
        String entry = getAidStr(rt, offset + 4, rt[offset + 1] - 2);

        pw.println(formatRow(entry, eeId, pwrState, blkCtrl, extra));
    }

    private void dumpSystemEntry(byte[] rt, int rtSize, PrintWriter pw, int offset) {
        if (offset + 4 + rt[offset + 1] - 2 >= rtSize) return;

        String blkCtrl = getBlockCtrlStr(rt[offset] & 0xF0);
        String eeId = String.format("0x%02X", rt[offset + 2]);
        String pwrState = String.format("0x%02X", rt[offset + 3]);
        String entry = getSystemCodeStr(rt, offset + 4, rt[offset + 1] - 2);

        pw.println(formatRow(entry, eeId, pwrState, blkCtrl, ""));
    }

    /**
    * Get Routing Table from the last backup lmrt cmd and dump it
    */
    public void dump(DeviceHost dh, PrintWriter pw) {
        int offset = 0;
        byte[] rt = dh.getRoutingTable();
        int maxSize = dh.getMaxRoutingTableSize();

        logRoutingTableRawData(rt);

        pw.println("--- dumpRoutingTable: start ---");
        pw.println(String.format(Locale.US, "RoutingTableSize: %d/%d", rt.length, maxSize));
        pw.println(formatRow("Entry", "NFCEE_ID", "Power State", "Block Ctrl", "Extra Info"));
        while (offset < rt.length) {
            int type = rt[offset] & 0x0F;
            if (type == 0x00) {
                // Technology-based routing entry
                dumpTechEntry(rt, rt.length, pw, offset);
            } else if (type == 0x01) {
                // Protocol-based routing entry
                dumpProtoEntry(rt, rt.length, pw, offset);
            } else if (type == 0x02) {
                // AID-based routing entry
                dumpAidEntry(rt, rt.length, pw, offset);
            } else if (type == 0x03) {
                // System Code-based routing entry
                dumpSystemEntry(rt, rt.length, pw, offset);
            } else {
                // Unrecognizable entry type
                Log.d(TAG, String.format("Unrecognizable entry type: 0x%02X, stop parsing", type));
                break;
            }
            offset += rt[offset+1] + 2;
        }

        pw.println("--- dumpRoutingTable:  end  ---");
    }

    private void logRoutingTableRawData(byte[] lmrt_cmd) {
        if (!DBG) return;
        String lmrt_str = "";
        for (int i = 0; i < lmrt_cmd.length; i++) {
            lmrt_str += String.format("%02X ", lmrt_cmd[i]);
        }
        Log.d(TAG, String.format("RoutingTableSize: %d", lmrt_cmd.length));
        Log.d(TAG, String.format("RoutingTable: %s", lmrt_str));
    }
}
