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

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log

/**
 * Handles communication with PN532 given a UsbDevice and UsbDeviceConnection object. Relevant
 * protocols of device are located at https://www.nxp.com/docs/en/user-guide/141520.pdf
 */
class PN532(val device: UsbDevice, val connection: UsbDeviceConnection) {
    private val transportLayer: TransportLayer

    init {
        Log.d(TAG, "Starting initialization")
        transportLayer = TransportLayer(device, connection)

        // Wake up device and send initial configs
        transportLayer.write(LONG_PREAMBLE + ACK)
        sendFrame(constructFrame(byteArrayOf(SAM_CONFIGURATION, 0x01, 0x00)))
        sendFrame(constructFrame(byteArrayOf(RF_CONFIGURATION, 0x05, 0x01, 0x00, 0x01)))
    }

    /** Polls for NFC Type-A. Returns tag if discovered. */
    fun pollA(): TypeATag? {
        Log.d(TAG, "Polling A")
        val rsp =
            sendFrame(constructFrame(byteArrayOf(IN_LIST_PASSIVE_TARGET, 0X01, 0X00)), timeout = 5000)
        if (rsp == null || rsp.size == 0) return null
        if (rsp[0] != (IN_LIST_PASSIVE_TARGET + 1).toByte()) {
            Log.e(TAG, "Got unexpected command code in response")
            return null
        }
        val targetData = rsp.drop(1).toByteArray()
        if (targetData.size < 6 || targetData[0] == 0.toByte()) {
            Log.d(TAG, "No tag found")
            return null
        }
        Log.d(TAG, "Tag found. Response: " + rsp.toHex())

        // Page 116 of https://www.nxp.com/docs/en/user-guide/141520.pdf for response format info
        val targetId = targetData[1]
        val senseRes = targetData.slice(2..3).toByteArray()
        val selRes = targetData[4]
        val nfcIdLen = targetData[5]

        if (6 + nfcIdLen > targetData.size) {
            Log.e(
                TAG,
                "Corrupt data - expected remaining size of list to be at least: " +
                        nfcIdLen +
                        " , but was " +
                        (targetData.size - 6),
            )
            return null
        }

        val nfcIdList = targetData.slice(6..6 + nfcIdLen - 1).toByteArray()
        val ats = targetData.drop(6 + nfcIdLen).toByteArray()
        if (ats.size == 0) {
            Log.e(TAG, "Corrupt data - expected ATS information")
            return null
        }
        val atsLen = ats[0]
        val atsList = ats.drop(1).toByteArray()
        if (atsList.size != atsLen.toInt() - 1) {
            Log.e(
                TAG,
                "Corrupt data - expected list of size " +
                        (atsLen.toInt() - 1) +
                        " , but was " +
                        (atsList.size),
            )
            return null
        }

        return TypeATag(this, targetId, senseRes, selRes, nfcIdList, atsList)
    }

    /** Polls for NFC Type-B */
    fun pollB() {
        Log.d(TAG, "Polling B")
        sendFrame(constructFrame(byteArrayOf(IN_LIST_PASSIVE_TARGET, 0x01, 0x03, 0x00)))
    }

    /** Emits broadcast frame with CRC. Call this after pollA() to send a custom frame */
    fun sendBroadcast(broadcast: ByteArray) {
        Log.d(TAG, "sendBroadcast: " + broadcast.toHex())
        sendFrame(constructFrame(byteArrayOf(WRITE_REGISTER, 0X63, 0X3D, 0X00)))
        sendFrame(constructFrame(byteArrayOf(IN_COMMUNICATE_THRU) + withCrc16a(broadcast)))
    }

    /** Send command to PN-532 and receive response. */
    fun transceive(data: ByteArray): ByteArray? {
        Log.d(TAG, "Transceiving: " + data.toHex())
        var response = sendFrame(constructFrame(byteArrayOf(IN_DATA_EXCHANGE) + data))
        if (response == null) return null
        Log.d(TAG, "Response: " + response.toHex())

        if (response[0] != (IN_DATA_EXCHANGE + 1).toByte()) {
            Log.e(TAG, "Got unexpected command code in response")
        }
        if (response[1] != 0.toByte()) {
            Log.e(TAG, "Got error exchanging data")
            return null
        }
        return response.drop(2).toByteArray()
    }

    /** Mute reader. Should be called after each polling loop. */
    fun mute() {
        Log.d(TAG, "Muting PN532")
        sendFrame(constructFrame(byteArrayOf(RF_CONFIGURATION, 0x01, 0x02)))
    }

    private fun sendFrame(frame: ByteArray, timeout: Long = 500.toLong()): ByteArray? {
        transportLayer.write(frame)
        return getDeviceResponse(timeout)
    }

    private fun isAckFrame(frame: ByteArray): Boolean {
        return frame.toHex().contentEquals(ACK.toHex())
    }

    private fun getDeviceResponse(timeoutMs: Long = 500.toLong()): ByteArray? {
        // First response from device should be ACK frame
        var data = transportLayer.read(timeoutMs, numBytes = 255)
        if (data == null || data.size < 6) return null

        val firstFrame = data.slice(0..5).toByteArray()
        if (!isAckFrame(firstFrame)) {
            Log.w(TAG, "Did not get ack frame - got " + firstFrame.toHex())
            return null
        } else {
            Log.d(TAG, "Got ack frame")
        }

        // Response will either be appended to first read, or will require an additional read
        var responseFrame: ByteArray? = data.drop(6).toByteArray()

        // Some instances require a second read of data
        var secondRead = false
        if (responseFrame?.size == 0) {
            responseFrame = transportLayer.read(timeoutMs, numBytes = 255)
            secondRead = true
        }
        if (responseFrame == null || responseFrame.size == 0) {
            Log.d(TAG, "No additional data")
            return null
        }
        if (responseFrame.size < 6) {
            Log.w(TAG, "Expected at least 6 bytes of response data. Got " + responseFrame.size)
            return null
        }

        if (isAckFrame(responseFrame)) {
            Log.d(TAG, "Got another ack frame")
            return null
        }

        if (!responseFrame.slice(0..2).toByteArray().toHex().contentEquals("0000ff")) {
            Log.e(
                TAG,
                "Unexpected start to frame - got " +
                        responseFrame.slice(0..2).toByteArray().toHex() +
                        ", expected " +
                        "0000ff",
            )
        } else {
            Log.d(TAG, "Correct start to frame")
        }

        val dataLength = responseFrame[3]
        val lengthChecksum = responseFrame[4]

        if ((lengthChecksum + dataLength) and 0xFF != 0) {
            Log.e(
                TAG,
                "Frame failed length checksum. lengthChecksum: " +
                        lengthChecksum +
                        ", dataLength: " +
                        dataLength +
                        ", responseFrame: " +
                        responseFrame.toHex() +
                        ", data: " +
                        data,
            )
        }

        val tfi = responseFrame[5]
        if (tfi != 0xD5.toByte()) {
            Log.e(TAG, "Unexpected TFI Byte: Got " + tfi + ", expected 0xD5")
        }

        var dataPacket: ByteArray?
        var dataCheckSum: Byte
        var postAmble: Byte
        if (secondRead) {
            dataPacket = responseFrame.slice(6..responseFrame.size - 3).toByteArray()
            dataCheckSum = responseFrame[responseFrame.size - 2]
            postAmble = responseFrame[responseFrame.size - 1]
        } else {
            dataPacket = data.slice(12..data.size - 3).toByteArray()
            dataCheckSum = data[data.size - 2]
            postAmble = data[data.size - 1]
        }

        if (dataPacket.size != 0 && dataPacket.size != dataLength.toInt() - 1) {
            Log.e(
                TAG,
                "Unexpected data packet size: Got " +
                        dataPacket.size +
                        ", expected " +
                        (dataLength.toInt() - 1).toString() +
                        ",",
            )
        }

        val sum = dataPacket.sum()

        if ((tfi + sum + dataCheckSum) and 0xFF != 0) {
            Log.e(
                TAG,
                "Frame failed data checksum. TFI: " +
                        tfi +
                        ", sum: " +
                        sum +
                        ", secondFrame: " +
                        responseFrame.toHex(),
            )
        }

        if (postAmble != 0x00.toByte()) {
            if (tfi != 0xD5.toByte()) {
                Log.e(TAG, "Unexpected postamble byte when performing read - got " + responseFrame[4])
            }
            return null
        }

        transportLayer.write(ACK)
        Log.d(TAG, "Received frame - " + responseFrame.toHex() + ", dataPacket: " + dataPacket.toHex())

        return dataPacket
    }

    fun ByteArray.sum(): Byte {
        var sum = 0
        for (byte in this) {
            sum += byte
        }
        return sum.toByte()
    }

    private fun crc16a(data: ByteArray): ByteArray {
        var w_crc = 0x6363
        for (byte in data) {
            var newByte = byte.toInt() xor (w_crc and 0xFF).toInt()
            newByte = (newByte xor newByte shl 4) and 0xFF
            w_crc = ((w_crc shr 8) xor (newByte shl 8) xor (newByte shl 3) xor (newByte shr 4)) and 0xFF
        }

        return byteArrayOf((w_crc and 0xFF).toByte(), ((w_crc shr 8) and 0xFF).toByte())
    }

    private fun withCrc16a(data: ByteArray): ByteArray {
        return data + crc16a(data)
    }

    private fun constructFrame(data: ByteArray): ByteArray {
        var frame: ByteArray =
            byteArrayOf(
                0x00,
                0x00,
                0xFF.toByte(),
                (data.size + 1).toByte(),
                ((data.size + 1 and 0xFF).inv() + 0x01).toByte(),
                0xD4.toByte(),
            )
        var sum = 0xD4
        for (byte in data) {
            sum += byte
        }
        frame += (data)
        frame += ((sum.inv() and 0xFF) + 0x01).toByte()
        frame += (0x00).toByte()

        return frame
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    companion object {
        private const val TAG = "PN532"
        val ACK: ByteArray =
            byteArrayOf(
                0x00.toByte(),
                0x00.toByte(),
                0xFF.toByte(),
                0x00.toByte(),
                0xFF.toByte(),
                0x00.toByte(),
            )
        private const val SAM_CONFIGURATION = 0x14.toByte()
        private const val IN_LIST_PASSIVE_TARGET = 0x4A.toByte()
        private const val RF_CONFIGURATION = 0x32.toByte()
        private const val WRITE_REGISTER = 0X08.toByte()
        private const val IN_COMMUNICATE_THRU = 0x42.toByte()
        private const val IN_DATA_EXCHANGE = 0x40.toByte()
        private val LONG_PREAMBLE = ByteArray(20)
    }
}
