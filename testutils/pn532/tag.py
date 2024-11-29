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

from binascii import hexlify

class Tag:
    def __init__(self, target_id: int):
        self.target_id = target_id

    def transact(self, command_apdus, response_apdus):
        self.log.debug("Starting transaction with %d commands", len(command_apdus))
        for i in range(len(command_apdus)):
            rsp = self.pn532.transceive(bytearray([self.target_id]) + command_apdus[i])
            if response_apdus[i] != "*" and rsp != response_apdus[i]:
                received_apdu = hexlify(rsp).decode() if type(rsp) is bytes else "None"
                self.log.error(
                    "Unexpected APDU: received %s, expected %s",
                    received_apdu,
                    hexlify(response_apdus[i]).decode(),
                )
                return False

        return True


class TypeATag(Tag):

    def __init__(
            self,
            pn532: "PN532",
            target_id: int,
            sense_res: bytearray,
            sel_res: int,
            nfcid: bytearray,
            ats: bytearray,
    ):
        self.pn532 = pn532
        self.target_id = target_id
        self.sense_res = sense_res
        self.sel_res = sel_res
        self.nfcid = nfcid
        self.ats = ats

        self.log = pn532.log

class TypeBTag(Tag):

    def __init__(
            self,
            pn532: "PN532",
            target_id: int,
            sensb_res: bytearray,
    ):
        self.pn532 = pn532
        self.target_id = target_id
        self.sensb_res = sensb_res

        self.log = pn532.log
