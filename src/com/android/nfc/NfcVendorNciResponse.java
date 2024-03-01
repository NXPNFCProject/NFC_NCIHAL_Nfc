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

import java.util.Arrays;

public class NfcVendorNciResponse {
    public byte status;
    public byte[] payload;
    public int gid;
    public int oid;

    public NfcVendorNciResponse(byte status, int gid, int oid, byte[] payload) {
        this.status = status;
        this.gid = gid;
        this.oid = oid;
        this.payload = payload;
    }

    @Override
    public String toString() {
        return "NfcVendorNciResponse{"
                + "status=" + status
                + ", gid=" + gid
                + ", oid=" + oid
                + ", payload=" + Arrays.toString(payload)
                + '}';
    }
}
