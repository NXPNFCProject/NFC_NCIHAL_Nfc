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

_NUM_POLLING_LOOPS = 50

def create_select_apdu(aid_hex):
    """Creates a select APDU command for the given AID"""
    aid_bytes = bytearray.fromhex(aid_hex)
    return bytearray.fromhex("00A40400") + bytearray([len(aid_bytes)]) + aid_bytes

def poll_and_transact(pn532, command_apdus, response_apdus, custom_frame = None):
    """Polls for an NFC Type-A tag 50 times. If tag is found, performs a transaction.

    :param pn532: PN532 device
    :param command_apdus: Command APDUs in transaction
    :param response_apdus: Response APDUs in transaction
    :param custom_frame: A custom frame to send as part of te polling loop other than pollA().

    :return: [if tag is found, if transaction was successful]
    """
    transacted = False
    tag = None
    for i in range(_NUM_POLLING_LOOPS):
        tag = pn532.poll_a()
        if tag is not None:
            transacted = tag.transact(command_apdus, response_apdus)
            pn532.mute()
            break
        if custom_frame is not None:
            pn532.send_broadcast(bytearray.fromhex(custom_frame))
        pn532.mute()
    return tag is not None, transacted
