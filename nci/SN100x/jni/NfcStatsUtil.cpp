/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "NfcStatsUtil.h"

#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <log/log.h>
#include <statslog_nfc.h>

#include "nfc_api.h"

using android::base::StringPrintf;

extern bool nfc_debug_enabled;

/*******************************************************************************
**
** Function:        logNfcTagType
**
** Description:     determine Nfc tag type from given protocol and log
** accordingly
**                  protocol: tag protocol
**                  discoveryMode: tag discovery mode
**
** Returns:         None
**
*******************************************************************************/
void NfcStatsUtil::logNfcTagType(int protocol, int discoveryMode) {
  static const char fn[] = "NfcStatsUtil::logNfcTagType";
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: protocol %d, mode %d", fn, protocol, discoveryMode);
  int tagType = nfc::stats::NFC_TAG_TYPE_OCCURRED__TYPE__TAG_UNKNOWN;
  if (protocol == NFC_PROTOCOL_T1T) {
    tagType = nfc::stats::NFC_TAG_TYPE_OCCURRED__TYPE__TAG_TYPE_1;
  } else if (protocol == NFC_PROTOCOL_T2T) {
    tagType = nfc::stats::NFC_TAG_TYPE_OCCURRED__TYPE__TAG_TYPE_2;
  } else if (protocol == NFC_PROTOCOL_T3T) {
    tagType = nfc::stats::NFC_TAG_TYPE_OCCURRED__TYPE__TAG_TYPE_3;
  } else if (protocol == NFC_PROTOCOL_MIFARE) {
    tagType = nfc::stats::NFC_TAG_TYPE_OCCURRED__TYPE__TAG_MIFARE_CLASSIC;
  } else if (protocol == NFC_PROTOCOL_ISO_DEP) {
    if ((discoveryMode == NFC_DISCOVERY_TYPE_POLL_A) ||
        (discoveryMode == NFC_DISCOVERY_TYPE_POLL_A_ACTIVE) ||
        (discoveryMode == NFC_DISCOVERY_TYPE_LISTEN_A) ||
        (discoveryMode == NFC_DISCOVERY_TYPE_LISTEN_A_ACTIVE)) {
      tagType = nfc::stats::NFC_TAG_TYPE_OCCURRED__TYPE__TAG_TYPE_4A;
    } else if ((discoveryMode == NFC_DISCOVERY_TYPE_POLL_B) ||
               (discoveryMode == NFC_DISCOVERY_TYPE_POLL_B_PRIME) ||
               (discoveryMode == NFC_DISCOVERY_TYPE_LISTEN_B) ||
               (discoveryMode == NFC_DISCOVERY_TYPE_LISTEN_B_PRIME)) {
      tagType = nfc::stats::NFC_TAG_TYPE_OCCURRED__TYPE__TAG_TYPE_4B;
    }
  } else if (protocol == NFC_PROTOCOL_T5T) {
    tagType = nfc::stats::NFC_TAG_TYPE_OCCURRED__TYPE__TAG_TYPE_5;
  } else if (protocol == NFC_PROTOCOL_KOVIO) {
    tagType = nfc::stats::NFC_TAG_TYPE_OCCURRED__TYPE__TAG_KOVIO_BARCODE;
  }
  writeNfcStatsTagTypeOccurred(tagType);
}

/*******************************************************************************
**
** Function:        writeNfcStatsTagTypeOccurred
**
** Description:     stats_write TagTypeOccurred atom with provided type
**                  tagType: NfcTagType defined in
** frameworks/proto_logging/stats/enums/nfc/enums.proto
**
** Returns:         None
**
*******************************************************************************/
void NfcStatsUtil::writeNfcStatsTagTypeOccurred(int tagType) {
  static const char fn[] = "NfcStatsUtil::writeNfcStatsTagTypeOccurred";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: %d", fn, tagType);

  nfc::stats::stats_write(nfc::stats::NFC_TAG_TYPE_OCCURRED, tagType);
}
