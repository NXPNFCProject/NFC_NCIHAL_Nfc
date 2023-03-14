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

import android.sysprop.NfcProperties;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Vector;

/**
* Parse the Routing Table from the last backup lmrt cmd and dump it with a clear typography
*/
public class RoutingTableParser {
    static final boolean DBG = NfcProperties.debug_enabled().orElse(false);
    private static final String TAG = "RoutingTableParser";
    private static int sRoutingTableSize = 0;
    private static int sRoutingTableMaxSize = 0;
    private static Vector<RoutingEntryInfo> sRoutingTable = new Vector<RoutingEntryInfo>(0);

    // Entry types
    static final byte TYPE_TECHNOLOGY = 0;
    static final byte TYPE_PROTOCOL = 1;
    static final byte TYPE_AID = 2;
    static final byte TYPE_SYSTEMCODE = 3;
    static final byte TYPE_UNSUPPORTED = 4;

    // Commit status
    static final int STATS_HOST_OK = 0;
    static final int STATS_OFFHOST_OK = 1;
    static final int STATS_NOT_FOUND = 2;

    private interface GetEntryStr {
        String getEntryStr(byte[] entry);
    }

    private GetEntryStr[] mGetEntryStrFuncs = new GetEntryStr[] {
        new GetEntryStr() { public String getEntryStr(byte[] entry) {
                return getTechStr(entry); } },
        new GetEntryStr() { public String getEntryStr(byte[] entry) {
                return getProtoStr(entry); } },
        new GetEntryStr() { public String getEntryStr(byte[] entry) {
                return getAidStr(entry); } },
        new GetEntryStr() { public String getEntryStr(byte[] entry) {
                return getSystemCodeStr(entry); } },
    };

    private String getTechStr(byte[] tech) {
        String[] tech_mask_list = {
            "TECHNOLOGY_A", "TECHNOLOGY_B", "TECHNOLOGY_F", "TECHNOLOGY_V"
        };

        if (tech[0] > tech_mask_list.length) {
            return "UNSUPPORTED_TECH";
        }
        return tech_mask_list[tech[0]];
    }

    private String getProtoStr(byte[] proto) {
        String[] proto_mask_list = {
            "PROTOCOL_UNDETERMINED", "PROTOCOL_T1T", "PROTOCOL_T2T", "PROTOCOL_T3T",
            "PROTOCOL_ISO_DEP", "PROTOCOL_NFC_DEP", "PROTOCOL_T5T", "PROTOCOL_NDEF"
        };
        if (proto[0] > proto_mask_list.length) {
            return "UNSUPPORTED_PROTO";
        }
        return proto_mask_list[proto[0]];
    }

    private String getAidStr(byte[] aid) {
        String aidStr = "";

        for (byte b : aid) {
            aidStr += String.format("%02X", b);
        }

        if (aidStr.length() == 0) {
            return "Empty_AID";
        }
        return "AID_" + aidStr;
    }

    private String getSystemCodeStr(byte[] sc) {
        String systemCodeStr = "";
        for (byte b : sc) {
            systemCodeStr += String.format("%02X", b);
        }
        return "SYSTEMCODE_" + systemCodeStr;
    }

    private String getBlockCtrlStr(byte mask) {
        if ((mask & 0x40) != 0) {
            return "True";
        }
        return "False";
    }

    private String getPrefixSubsetStr(byte mask, byte type) {
        if (type != TYPE_AID) {
            return "";
        }

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

    private class RoutingEntryInfo {
        public final byte mQualifier;
        public final byte mType;
        public final byte mNfceeId;
        public final byte mPowerState;
        public final byte[] mEntry;

        private RoutingEntryInfo(byte qualifier, byte type, byte eeId, byte pwrState,
                byte[] entry) {
            mQualifier = qualifier;
            mType = type;
            mNfceeId = eeId;
            mPowerState = pwrState;
            mEntry = entry;
        }

        private void dump(PrintWriter pw) {
            String blkCtrl = getBlockCtrlStr(mQualifier);
            String eeId = String.format("0x%02X", mNfceeId);
            String pwrState = String.format("0x%02X", mPowerState);
            String entry = mGetEntryStrFuncs[mType].getEntryStr(mEntry);
            String extra = getPrefixSubsetStr(mQualifier, mType);

            pw.println(formatRow(entry, eeId, pwrState, blkCtrl, extra));
        }
    }

    private boolean validateEntryInfo(byte type, byte[] entry) {
        switch(type) {
            case TYPE_TECHNOLOGY:
                if (entry.length != 1) return false;
                break;
            case TYPE_PROTOCOL:
                if (entry.length != 1) return false;
                break;
            case TYPE_AID:
                if (entry.length > 16) return false;
                break;
            case TYPE_SYSTEMCODE:
                if (entry.length != 2) return false;
                break;
            default:
                return false;
        }
        return true;
    }

    /**
    * Check commit status by inputting type and entry
    */
    @VisibleForTesting
    public int getCommitStatus(byte type, byte[] entry) {
        if (!validateEntryInfo(type, entry)) return STATS_NOT_FOUND;

        for (RoutingEntryInfo routingEntry : sRoutingTable) {
            if (routingEntry.mType != type) {
                continue;
            }
            if (Arrays.equals(routingEntry.mEntry, entry)) {
                return routingEntry.mNfceeId == 0x00 ? STATS_HOST_OK : STATS_OFFHOST_OK;
            }
            if (routingEntry.mType != TYPE_AID) {
                continue;
            }
            if ((routingEntry.mQualifier & 0x10) != 0
                    && entry.length > routingEntry.mEntry.length) {
                int ptr = 0;
                while (entry[ptr] == routingEntry.mEntry[ptr]) {
                    ptr += 1;
                }
                if (ptr == routingEntry.mEntry.length) {
                    return routingEntry.mNfceeId == 0x00 ? STATS_HOST_OK : STATS_OFFHOST_OK;
                }
            }
            if ((routingEntry.mQualifier & 0x20) != 0
                    && entry.length < routingEntry.mEntry.length) {
                int ptr = 0;
                while (entry[ptr] == routingEntry.mEntry[ptr]) {
                    ptr += 1;
                }
                if (ptr == entry.length) {
                    return routingEntry.mNfceeId == 0x00 ? STATS_HOST_OK : STATS_OFFHOST_OK;
                }
            }
        }
        return STATS_NOT_FOUND;
    }

    private void addRoutingEntry(byte[] rt, int offset) {
        if (offset + 1 >= rt.length) return;
        int valueLength = Byte.toUnsignedInt(rt[offset + 1]);

        // Qualifier-Type(1 byte) + Length(1 byte) + Value(valueLength bytes)
        if (offset + 2 + valueLength > rt.length) return;

        byte qualifier = (byte) (rt[offset] & 0xF0);
        byte type = (byte) (rt[offset] & 0x0F);
        byte eeId = rt[offset + 2];
        byte pwrState = rt[offset + 3];
        byte[] entry = new byte[valueLength - 2];
        for (int i = 0; i < valueLength - 2; i++) {
            entry[i] = rt[offset + 4 + i];
        }

        if (type == TYPE_SYSTEMCODE && (entry.length & 1) == 0 && entry.length <= 64) {
            for (int i = 0; i < entry.length; i += 2) {
                byte[] sc_entry = {entry[i], entry[i + 1]};
                sRoutingTable.add(new RoutingEntryInfo(qualifier, type, eeId, pwrState, sc_entry));
            }
        } else if (validateEntryInfo(type, entry)) {
            sRoutingTable.add(new RoutingEntryInfo(qualifier, type, eeId, pwrState, entry));
        }
    }

    /**
    * Parse the raw data of routing table
    */
    public void parse(byte[] rt) {
        int offset = 0;

        logRoutingTableRawData(rt);

        sRoutingTable.clear();
        while (offset < rt.length) {
            byte type = (byte) (rt[offset] & 0x0F);
            if (type >= TYPE_UNSUPPORTED) {
                // Unrecognizable entry type
                Log.e(TAG, String.format("Unrecognizable entry type: 0x%02X, stop parsing", type));
                return;
            }
            if (offset + 1 >= rt.length) {
                // Buffer overflow
                Log.e(TAG, String.format("Wrong tlv length, stop parsing"));
                return;
            }
            // Qualifier-Type(1 byte) + Length(1 byte) + Value(valueLength bytes)
            int tlvLength = Byte.toUnsignedInt(rt[offset + 1]) + 2;

            addRoutingEntry(rt, offset);

            offset += tlvLength;
        }
    }

    /**
    * Get Routing Table from the last backup lmrt cmd and parse it
    */
    public void update(DeviceHost dh) {
        sRoutingTableMaxSize = dh.getMaxRoutingTableSize();
        byte[] rt = dh.getRoutingTable();
        sRoutingTableSize = rt.length;
        parse(rt);
    }

    /**
    * Get Routing Table from the last backup lmrt cmd and dump it
    */
    public void dump(DeviceHost dh, PrintWriter pw) {
        update(dh);

        pw.println("--- dumpRoutingTable: start ---");
        pw.println(String.format(Locale.US, "RoutingTableSize: %d/%d",
                sRoutingTableSize, sRoutingTableMaxSize));
        pw.println(formatRow("Entry", "NFCEE_ID", "Power State", "Block Ctrl", "Extra Info"));

        for (RoutingEntryInfo routingEntry : sRoutingTable) {
            routingEntry.dump(pw);
        }

        pw.println("--- dumpRoutingTable:  end  ---");
    }

    private void logRoutingTableRawData(byte[] lmrt_cmd) {
        if (!DBG) return;
        String lmrt_str = "";

        for (byte b : lmrt_cmd) {
            lmrt_str += String.format("%02X ", b);
        }
        Log.i(TAG, String.format("RoutingTableSize: %d", lmrt_cmd.length));
        Log.i(TAG, String.format("RoutingTable: %s", lmrt_str));
    }
}
