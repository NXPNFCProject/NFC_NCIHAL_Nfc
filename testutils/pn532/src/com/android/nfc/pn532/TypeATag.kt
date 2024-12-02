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
package com.android.nfc.pn532

import android.util.Log

/** TypeA Tag - used to transact a specific command and response sequence */
class TypeATag(
    val pn532: PN532,
    val targetId: Byte,
    val senseRes: ByteArray,
    val selRes: Byte,
    val nfcId: ByteArray,
    val ats: ByteArray,
) {

    /**
     * Completes a transaction of APDUs between reader and emulator, with command APDUs and expected
     * response APDUs passed in as parameters. Returns true if transaction is successful
     */
    fun transact(commandApdus: Array<String>, responseApdus: Array<String>): Boolean {
        if (commandApdus.size != responseApdus.size) {
            Log.e(TAG, "Command and response APDU size mismatch")
            return false
        }

        Log.d(TAG, "Transacting with a TypeATag - targetId: " + targetId + ", senseRes: " +
        senseRes + ", selRes: " + selRes + ", nfcId: " + nfcId + ", ats: " + ats)

        var success = true
        for (i in 0 until commandApdus.size) {
            val rsp = pn532.transceive(byteArrayOf(targetId) + commandApdus[i].decodeHex())
            if (responseApdus[i] != "*" && !rsp.contentEquals(responseApdus[i].decodeHex())) {
                Log.e(
                    TAG,
                    "Unexpected APDU: received " + rsp + ", expected " + responseApdus[i].decodeHex(),
                )
                success = false
            }
        }
        return success
    }

    fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }

        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun ByteArray.toHex(): String =
        joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

    companion object {
        private const val TAG = "TypeATag"
    }
}
