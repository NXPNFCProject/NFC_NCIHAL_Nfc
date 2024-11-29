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

def parse_protocol_params(sak, ats):
    """
    Helper function to check whether protocol parameters are properly set.
    :param sak: SAK byte
    :param ats: ATS byte
    :return: whether bits are set correctly, message to print
    """
    msg = ""
    success = True
    msg += "SAK:\n"
    if sak & 0x20 != 0:
        msg += "    (OK) ISO-DEP bit (0x20) is set.\n"
    else:
        success = False
        msg += "    (FAIL) ISO-DEP bit (0x20) is NOT set.\n"
    if sak & 0x40 != 0:
        msg += "    (OK) P2P bit (0x40) is set.\n"
    else:
        msg += "    (WARN) P2P bit (0x40) is NOT set.\n"

    ta_present = False
    tb_present = False
    tc_present = False
    atsIndex = 0
    if ats[atsIndex] & 0x40 != 0:
        msg += "        (OK) T(C) is present (bit 7 is set).\n"
        tc_present = True
    else:
        success = False
        msg += "        (FAIL) T(C) is not present (bit 7 is NOT set).\n"
    if ats[atsIndex] and 0x20 != 0:
        msg += "        (OK) T(B) is present (bit 6 is set).\n"
        tb_present = True
    else:
        success = False
        msg += "        (FAIL) T(B) is not present (bit 6 is NOT set).\n"
    if ats[atsIndex] and 0x10 != 0:
        msg += "        (OK) T(A) is present (bit 5 is set).\n"
        ta_present = True
    else:
        success = False
        msg += "        (FAIL) T(A) is not present (bit 5 is NOT set).\n"
    fsc = ats[atsIndex] & 0x0F
    if fsc > 8:
        success = False
        msg += "        (FAIL) FSC " + str(fsc) + " is > 8\n"
    elif fsc < 2:
        msg += "        (FAIL EMVCO) FSC " + str(fsc) + " is < 2\n"
    else:
        msg += "        (OK) FSC = " + str(fsc) + "\n"

    atsIndex += 1
    if ta_present:
        msg += "    TA: 0x" + str(ats[atsIndex] & 0xff) + "\n"
        if ats[atsIndex] & 0x80 != 0:
            msg += "        (OK) bit 8 set, indicating only same bit rate divisor.\n"
        else:
            msg += "        (FAIL EMVCO) bit 8 NOT set, indicating support for asymmetric bit rate divisors. EMVCo requires bit 8 set.\n"
        if ats[atsIndex] & 0x70 != 0:
            msg += "        (FAIL EMVCO) EMVCo requires bits 7 to 5 set to 0.\n"
        else:
            msg += "        (OK) bits 7 to 5 indicating only 106 kbit/s L->P supported.\n"
        if ats[atsIndex] & 0x7 != 0:
            msg += "        (FAIL EMVCO) EMVCo requires bits 3 to 1 set to 0.\n"
        else:
            msg += "        (OK) bits 3 to 1 indicating only 106 kbit/s P->L supported.\n"
        atsIndex += 1

    if tb_present:
        msg += "    TB: 0x" + str(ats[3] & 0xFF) + "\n"
        fwi = (ats[atsIndex] & 0xF0) >> 4
        if fwi > 8:
            msg += "        (FAIL) FWI=" + str(fwi) + ", should be <= 8\n"
        elif fwi == 8:
            msg += "        (FAIL EMVCO) FWI=" + str(fwi) + ", EMVCo requires <= 7\n"
        else:
            msg += "        (OK) FWI=" + str(fwi) + "\n"
        sfgi = ats[atsIndex] & 0x0F
        if sfgi > 8:
            success = False
            msg += "        (FAIL) SFGI=" + str(sfgi) + ", should be <= 8\n"
        else:
            msg += "        (OK) SFGI=" + str(sfgi) + "\n"
        atsIndex += 1
    if tc_present:
        msg += "    TC: 0x" + str(ats[atsIndex] & 0xFF) + "\n"
        nadSupported = ats[atsIndex] & 0x01 != 0
        if nadSupported:
            success = False
            msg += "        (FAIL) NAD bit is not allowed to be set.\n"
        else:
            msg += "        (OK) NAD bit is not set.\n"
        atsIndex += 1
        if atsIndex + 1 < len(ats):
            historical_bytes = len(ats) - atsIndex
            msg +=  "\n(OK) Historical bytes: " + hexlify(historical_bytes).decode()
    return success, msg

def get_apdus(nfc_emulator, service_name):
    """
    Gets apdus for a given service.
    :param nfc_emulator: emulator snippet.
    :param service_name: Service name of APDU sequence to fetch.
    :return: [command APDU byte array, response APDU byte array]
    """
    command_apdus = nfc_emulator.getCommandApdus(service_name)
    response_apdus = nfc_emulator.getResponseApdus(service_name)
    return [bytearray.fromhex(apdu) for apdu in command_apdus], [
        (bytearray.fromhex(apdu) if apdu != "*" else apdu) for apdu in response_apdus]

def to_byte_array(apdu_array):
    return [bytearray.fromhex(apdu) for apdu in apdu_array]
