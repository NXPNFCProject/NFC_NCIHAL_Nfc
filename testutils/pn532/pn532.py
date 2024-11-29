#  Copyright (C) 2024 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

# Lint as: python3

import logging
import serial
from . import tag
from binascii import hexlify
from serial.tools.list_ports import comports
from mobly import logger as mobly_logger

GET_FIRMWARE_VERSION = 0x02
SAM_CONFIGURATION = 0x14
RF_CONFIGURATION = 0x32
IN_DATA_EXCHANGE = 0x40
IN_COMMUNICATE_THRU = 0x42
IN_LIST_PASSIVE_TARGET = 0x4A
WRITE_REGISTER = 0x08
LONG_PREAMBLE = bytearray(20)
TG_INIT_AS_TARGET = 0x8C

def crc16a(data):
    w_crc = 0x6363
    for byte in data:
        byte = byte ^ (w_crc & 0x00FF)
        byte = (byte ^ (byte << 4)) & 0xFF
        w_crc = ((w_crc >> 8) ^ (byte << 8) ^ (byte << 3) ^ (byte >> 4)) & 0xFFFF
    return bytes([w_crc & 0xFF, (w_crc >> 8) & 0xFF])


def with_crc16a(data):
    return bytes(data) + crc16a(data)


class PN532:

    def __init__(self, path):
        """Initializes device on path, or first available serial port if none is provided."""
        if len(comports()) == 0:
            raise IndexError(
                "Could not find device on serial port, make sure reader is plugged in."
            )
        if len(path) == 0:
            path = comports()[0].device
        self.log = mobly_logger.PrefixLoggerAdapter(
            logging.getLogger(),
            {
                mobly_logger.PrefixLoggerAdapter.EXTRA_KEY_LOG_PREFIX: (
                    f"[PN532|{path}]"
                )
            },
        )
        self.log.debug("Serial port: %s", path)
        self.device = serial.Serial(path, 115200, timeout=0.5)

        self.device.flush()
        self.device.write(LONG_PREAMBLE + bytearray.fromhex("0000ff00ff00"))
        self.device.flushInput()
        if not self.verify_firmware_version():
            raise RuntimeError("Could not verify PN532 firmware on serial path " + path)
        rsp = self.send_frame(
            LONG_PREAMBLE + self.construct_frame([SAM_CONFIGURATION, 0x01, 0x00]),
            1,
            )
        if not rsp:
            raise RuntimeError("No response for SAM configuration.")

        # Disable retries
        self.device.flushInput()
        rsp = self.send_frame(
            self.construct_frame(
                [
                    RF_CONFIGURATION,
                    0x05,
                    0x00,  # MxRtyATR
                    0x00,  # MxRtyPSL
                    0x00,  # MxRtyPassiveActivation
                ]
            ),
            1,
        )
        if not rsp:
            raise RuntimeError("No response for RF configuration.")

    def verify_firmware_version(self):
        """Verifies we are talking to a PN532."""
        self.log.debug("Checking firmware version")
        rsp = self.send_frame(
            LONG_PREAMBLE + self.construct_frame([GET_FIRMWARE_VERSION])
        )

        if not rsp:
            raise RuntimeError("No response for GetFirmwareVersion")

        if rsp[0] != GET_FIRMWARE_VERSION + 1 or len(rsp) != 5:
            self.log.error("Got unexpected response for GetFirmwareVersion")
            return False

        return rsp[1] == 0x32

    def poll_a(self):
        """Attempts to detect target for NFC type A."""
        self.log.debug("Polling A")
        rsp = self.send_frame(
            self.construct_frame([IN_LIST_PASSIVE_TARGET, 0x01, 0x00])
        )
        if not rsp:
            raise RuntimeError("No response for send poll_a frame.")

        if rsp[0] != IN_LIST_PASSIVE_TARGET + 1:
            self.log.error("Got unexpected command code in response")
        del rsp[0]

        num_targets = rsp[0]
        if num_targets == 0:
            return None
        del rsp[0]

        target_id = rsp[0]
        del rsp[0]

        sense_res = rsp[0:2]
        del rsp[0:2]

        sel_res = rsp[0]
        self.log.debug("Got tag, SEL_RES is %02x", sel_res)
        del rsp[0]

        nfcid_len = rsp[0]
        del rsp[0]
        nfcid = rsp[0:nfcid_len]
        del rsp[0:nfcid_len]

        ats_len = rsp[0]
        del rsp[0]
        ats = rsp[0 : ats_len - 1]
        del rsp[0 : ats_len - 1]

        return tag.TypeATag(self, target_id, sense_res, sel_res, nfcid, ats)

    def initialize_target_mode(self):
        """Configures the PN532 as target."""
        self.log.debug("Initializing target mode")
        self.send_frame(
            self.construct_frame([TG_INIT_AS_TARGET,
                                  0x05, #Mode
                                  0x04, #SENS_RES (2 bytes)
                                  0x00,
                                  0x12, #nfcid1T (3 BYTES)
                                  0x34,
                                  0x56,
                                  0x20, #SEL_RES
                                  0x00, #FeliCAParams[] (18 bytes)
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,#NFCID3T[] (10 bytes)
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00,
                                  0x00, #LEN Gt
                                  0x00, #LEN Tk
                                  ]))

    def poll_b(self):
        """Attempts to detect target for NFC type B."""
        self.log.debug("Polling B")
        rsp = self.send_frame(
            self.construct_frame([IN_LIST_PASSIVE_TARGET, 0x01, 0x03, 0x00])
        )
        if not rsp:
            raise RuntimeError("No response for send poll_b frame.")

        if rsp[0] != IN_LIST_PASSIVE_TARGET + 1:
            self.log.error("Got unexpected command code in response")
        del rsp[0]

        afi = rsp[0]

        deselect_command = 0xC2
        self.send_broadcast(bytearray(deselect_command))

        wupb_command = [0x05, afi, 0x08]
        self.send_frame(
            self.construct_frame([WRITE_REGISTER, 0x63, 0x3D, 0x00])
        )
        rsp = self.send_frame(
            self.construct_frame(
                [IN_COMMUNICATE_THRU] + list(with_crc16a(wupb_command))
            )
        )
        if not rsp:
            raise RuntimeError("No response for WUPB command")

        return tag.TypeBTag(self, 0x03, rsp)

    def send_broadcast(self, broadcast):
        """Emits broadcast frame with CRC. This should be called after poll_a()."""
        self.log.debug("Sending broadcast %s", hexlify(broadcast).decode())

        # Adjust bit framing so all bytes are transmitted
        self.send_frame(self.construct_frame([WRITE_REGISTER, 0x63, 0x3D, 0x00]))
        rsp = self.send_frame(
            self.construct_frame([IN_COMMUNICATE_THRU] + list(with_crc16a(broadcast)))
        )
        if not rsp:
            raise RuntimeError("No response for send broadcast.")

    def transceive(self, data):
        """Sends data to device and returns response."""
        self.log.debug("Transceive")
        rsp = self.send_frame(self.construct_frame([IN_DATA_EXCHANGE] + list(data)), 5)

        if not rsp:
            return None

        if rsp[0] != IN_DATA_EXCHANGE + 1:
            self.log.error("Got unexpected command code in response")
        del rsp[0]

        if rsp[0] != 0:
            self.log.error("Got error exchanging data")
            return None
        del rsp[0]

        return rsp

    def mute(self):
        """Turns off device's RF antenna."""
        self.log.debug("Muting")
        self.send_frame(self.construct_frame([RF_CONFIGURATION, 0x01, 0x02]))

    def construct_frame(self, data):
        """Construct a data fram to be sent to the PN532."""
        # Preamble, start code, length, length checksum, TFI
        frame = [
            0x00,
            0x00,
            0xFF,
            (len(data) + 1) & 0xFF,
            ((~(len(data) + 1) & 0xFF) + 0x01) & 0xFF,
            0xD4,
            ]
        data_sum = 0xD4

        # Add data to frame
        for b in data:
            data_sum += b
            frame.append(b)
        frame.append(((~data_sum & 0xFF) + 0x01) & 0xFF)  # Data checksum

        frame.append(0x00)  # Postamble
        self.log.debug("Constructed frame " + hexlify(bytearray(frame)).decode())

        return bytearray(frame)

    def send_frame(self, frame, timeout=0.5):
        """
        Writes a frame to the device and returns the response.
        """
        self.device.write(frame)
        return self.get_device_response(timeout)

    def reset_buffers(self):
        self.device.reset_input_buffer()
        self.device.reset_output_buffer()

    def get_device_response(self, timeout=0.5):
        """
        Confirms we get an ACK frame from device, reads response frame, and writes ACK.
        """
        self.device.timeout = timeout
        frame = bytearray(self.device.read(6))

        if (len(frame)) == 0:
            self.log.error("Did not get response from PN532")
            return None

        if hexlify(frame).decode() != "0000ff00ff00":
            self.log.error("Did not get ACK frame, got %s", hexlify(frame).decode())

        frame = bytearray(self.device.read(6))

        if (len(frame)) == 0:
            return None

        if hexlify(frame[0:3]).decode() != "0000ff":
            self.log.error(
                "Unexpected start to frame, got %s", hexlify(frame[0:3]).decode()
            )

        data_len = frame[3]
        length_checksum = frame[4]
        if (length_checksum + data_len) & 0xFF != 0:
            self.log.error("Frame failed length checksum")
            return None

        tfi = frame[5]
        if tfi != 0xD5:
            self.log.error(
                "Unexpected TFI byte when performing read, got %02x", frame[5]
            )
            return None

        data_packet = bytearray(
            self.device.read(data_len - 1)
        )  # subtract one since length includes TFI byte.
        data_checksum = bytearray(self.device.read(1))[0]
        if (tfi + sum(data_packet) + data_checksum) & 0xFF != 0:
            self.log.error("Frame failed data checksum")

        postamble = bytearray(self.device.read(1))[0]
        if postamble != 0x00:
            if tfi != 0xD5:
                self.log.error(
                    "Unexpected postamble byte when performing read, got %02x", frame[4]
                )

        self.device.timeout = 0.5
        self.device.write(
            bytearray.fromhex("0000ff00ff00")
        )  # send ACK frame, there is no response.

        self.log.debug(
            "Received frame %s%s",
            hexlify(frame).decode(),
            hexlify(data_packet).decode(),
        )

        return data_packet
