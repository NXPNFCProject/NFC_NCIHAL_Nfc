/*
 * Copyright 2022 NXP
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

#include "NfcDta.h"

#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <cutils/properties.h>

#include "SyncEvent.h"

using android::base::StringPrintf;

extern bool nfc_debug_enabled;

namespace android {
extern SyncEvent gNfaSetConfigEvent;
extern SyncEvent gNfaGetConfigEvent;
extern uint16_t gCurrentConfigLen;
extern uint8_t gConfig[256];
}  // namespace android

using namespace android;

/*******************************************************************************
**
** Function:        NfcDta
**
** Description:     Initialize member variables.
**
** Returns:         None
**
*******************************************************************************/
NfcDta::NfcDta() {
  mDefaultTlv.clear();
  mUpdatedParamIds.clear();
}

/*******************************************************************************
**
** Function:        getInstance
**
** Description:     Get a reference to the singleton NfcDta object.
**
** Returns:         Reference to NfcDta object.
**
*******************************************************************************/
NfcDta& NfcDta::getInstance() {
  static NfcDta dtaInstance;
  return dtaInstance;
}

/*******************************************************************************
**
** Function:        parseConfigParams
**
** Description:     Parse config TLV from the String
**
** Returns:         uint8_t vector
**
*******************************************************************************/
std::vector<uint8_t> NfcDta::parseConfigParams(std::string configParams) {
  std::vector<uint8_t> configTlv;
  uint16_t index = 0;
  while (index < configParams.size()) {
    // Parsing tag
    configTlv.push_back(
        (uint8_t)strtol(configParams.substr(index, 2).c_str(), NULL, 16));
    // Parsing length
    index += 2;
    uint16_t len =
        (uint8_t)strtol(configParams.substr(index, 2).c_str(), NULL, 16);
    configTlv.push_back(len);
    // Parsing value
    for (uint16_t i = 0; i < len; i++) {
      index += 2;
      configTlv.push_back(
          (uint8_t)strtol(configParams.substr(index, 2).c_str(), NULL, 16));
    }
    index += 2;
    if (configParams[index] == '_') {
      index++;
    }
  }
  return configTlv;
}

/*******************************************************************************
**
** Function:        getNonupdatedConfigParamIds
**
** Description:     Get config parameter Ids whose values are not modified.
**
** Returns:         config Parameter ids vector.
**
*******************************************************************************/
std::vector<uint8_t> NfcDta::getNonupdatedConfigParamIds(
    std::vector<uint8_t> configTlv) {
  std::vector<uint8_t> paramIds;
  uint16_t index = 0;
  uint8_t len = 0;
  uint8_t paramId = 0;
  while (index < configTlv.size()) {
    paramId = configTlv[index++];
    std::unordered_set<uint8_t>::const_iterator found =
        mUpdatedParamIds.find(paramId);
    if (found == mUpdatedParamIds.end()) {
      mUpdatedParamIds.insert(paramId);
      paramIds.push_back(paramId);
    }
    len = configTlv[index++];
    index += len;
  }
  return paramIds;
}

/*******************************************************************************
**
** Function:        getConfigParamValues
**
** Description:     Read the current config parameter values.
**
** Returns:         None.
**
*******************************************************************************/
tNFA_STATUS NfcDta::getConfigParamValues(std::vector<uint8_t> paramIds) {
  tNFA_STATUS status = NFA_STATUS_OK;
  if (!paramIds.empty()) {
    SyncEventGuard guard(gNfaGetConfigEvent);
    status = NFA_GetConfig(paramIds.size(), paramIds.data());
    if (status == NFA_STATUS_OK) {
      gNfaGetConfigEvent.wait();
      // gCurrentConfigLen contains number of bytes without NCI header length.
      // i.e., status(one byte) + config tag count(one byte) + NCI config
      // Tag(one byte) + length(one byte) + value(bytes). So valid getConfig
      // response length should be greater than 4.
      if (gCurrentConfigLen > 4) {
        // Config tlv length = gCurrentConfigLen - status byte - tag count byte
        uint16_t len = gCurrentConfigLen - 2;
        // First Config TLV starts from index 1 of gConfig
        uint16_t index = 1;
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: default_config len: %d", __func__, len);
        while (index <= len) {
          mDefaultTlv.push_back(gConfig[index++]);
        }
      } else {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: getConfig failed len: %d", __func__, gCurrentConfigLen);
        status = NFA_STATUS_FAILED;
      }
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: getConfig failed", __func__);
    }
  }
  return status;
}

/*******************************************************************************
**
** Function:        setConfigParams
**
** Description:     Set config param with new value.
**
** Returns:         NFA_STATUS_OK if success
**
*******************************************************************************/
tNFA_STATUS NfcDta::setConfigParams(std::vector<uint8_t> configTlv) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint16_t index = 0;
  uint8_t paramId = 0;
  uint8_t len = 0;
  while (index < configTlv.size()) {
    paramId = configTlv[index++];
    len = configTlv[index++];
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: Param Id: %02X, Length: %02X", __func__, paramId, len);
    SyncEventGuard guard(gNfaSetConfigEvent);
    status = NFA_SetConfig(paramId, len, &configTlv[index]);
    if (status == NFA_STATUS_OK) {
      gNfaSetConfigEvent.wait();
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: setConfig failed", __func__);
      break;
    }
    index += len;
  }
  return status;
}

/*******************************************************************************
**
** Function:        setNfccConfigParams
**
** Description:     Set NCI config params from nfc.configTLV System Property.
**
** Returns:         None.
**
*******************************************************************************/
void NfcDta::setNfccConfigParams() {
  char sysPropTlvs[256];
  property_get("nfc.dta.configTLV", sysPropTlvs, "");
  std::string configTlvs(sysPropTlvs);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s: SysProperty nfc.configTLV: %s", __func__, configTlvs.c_str());
  if (configTlvs.empty() && mDefaultTlv.empty()) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Config TLVs not available", __func__);
    return;
  }
  tNFA_STATUS status = NFA_STATUS_FAILED;
  if (configTlvs.empty() && !mDefaultTlv.empty()) {
    status = setConfigParams(mDefaultTlv);
    if (status == NFA_STATUS_OK) {
      mDefaultTlv.clear();
      mUpdatedParamIds.clear();
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: Restored default config params", __func__);
    }
    return;
  }

  std::vector<uint8_t> tlv = parseConfigParams(configTlvs);
  std::vector<uint8_t> paramIds = getNonupdatedConfigParamIds(tlv);
  status = getConfigParamValues(paramIds);
  if (status == NFA_STATUS_OK) {
    setConfigParams(tlv);
  }
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Exit", __func__);
}
