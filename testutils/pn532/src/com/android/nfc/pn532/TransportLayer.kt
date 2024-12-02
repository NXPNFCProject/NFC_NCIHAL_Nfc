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

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import android.util.Log

/** TransportLayer - handles reads/write to USB device */
class TransportLayer(val device: UsbDevice, val connection: UsbDeviceConnection) {

    lateinit var endpointIn: UsbEndpoint
    lateinit var endpointOut: UsbEndpoint
    val dataRequest: UsbRequest = UsbRequest()

    init {
        for (i in 0 until device.interfaceCount) {
            val ui = device.getInterface(i)
            for (j in 0 until ui.endpointCount) {
                val endPoint = ui.getEndpoint(j)
                when (endPoint.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                        connection.claimInterface(ui, true)
                        if (endPoint.direction == UsbConstants.USB_DIR_IN) {
                            endpointIn = endPoint
                            dataRequest.initialize(connection, endpointIn)
                        } else {
                            endpointOut = endPoint
                        }
                    }
                }
            }
        }
    }

    fun read(timeout: Long, numBytes: Int = 255): ByteArray? {
        if (numBytes < 0) return null
        val buffer = ByteArray(numBytes)

        val size = connection.bulkTransfer(endpointIn, buffer, buffer.size, timeout.toInt())
        Log.d(TAG, "Got $size bytes back from reading.")
        if (size > 0) {
            val ret = ByteArray(size)
            System.arraycopy(buffer, 0, ret, 0, size)
            return ret
        } else {
            Log.e(TAG, "Got no data back. Response: " + size)
        }
        return null
    }

    fun write(bytes: ByteArray): Boolean {
        val size = connection.bulkTransfer(endpointOut, bytes, bytes.size, endpointOut.interval)
        if (size > 0) {
            return true
        }
        Log.e(TAG, "Unsuccessful write")
        return false
    }

    fun write(hexString: String): Boolean {
        return write(hexStringToBytes(hexString))
    }

    companion object {
        private const val TAG: String = "PN532"

        fun bytesToString(bytes: ByteArray): String {
            val sb = StringBuilder()
            for (b: Byte in bytes) {
                sb.append(String.format("%02X ", b))
            }

            return sb.toString()
        }

        fun hexStringToBytes(hexString: String): ByteArray {
            return hexString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
}
