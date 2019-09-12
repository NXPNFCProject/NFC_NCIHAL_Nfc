/*
 * Copyright (C) 2015-2019 NXP Semiconductors
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

#include "HciRFParams.h"
#include "SecureElement.h"

#define VAL_START_IDX 4
#define MAX_AID_SIZE 10
#define MAX_APP_DATA_SIZE 15
#define MAX_HIGHER_LAYER_RSP_SIZE 15

#if (NXP_EXTNS == true)
bool IsEseCeDisabled;
#endif

HciRFParams HciRFParams::sHciRFParams;

/*******************************************************************************
 **
 ** Function:        HciRFParams
 **
 ** Description:     Initialize member variables.
 **
 ** Returns:         None
 **
 *******************************************************************************/
HciRFParams::HciRFParams() {
  memset(aATQA_CeA, 0, sizeof(aATQA_CeA));
  memset(aATQB_CeB, 0, sizeof(aATQB_CeB));
  memset(aApplicationData_CeA, 0, sizeof(aApplicationData_CeA));
  memset(aDataRateMax_CeA, 0, sizeof(aDataRateMax_CeA));
  memset(aDataRateMax_CeB, 0, sizeof(aDataRateMax_CeB));
  memset(aHighLayerRsp_CeB_CeB, 0, sizeof(aHighLayerRsp_CeB_CeB));
  memset(aPupiReg_CeB, 0, sizeof(aPupiReg_CeB));
  memset(aUidReg_CeA, 0, sizeof(aUidReg_CeA));
  memset(&get_config, 0, sizeof(tNFA_GET_CONFIG));
  bMode_CeA = 0;
  bUidRegSize_CeA = 0;
  bSak_CeA = 0;
  bApplicationDataSize_CeA = 0;
  bFWI_SFGI_CeA = 0;
  bCidSupport_CeA = 0;
  bCltSupport_CeA = 0;
  bPipeStatus_CeA = 0;
  bPipeStatus_CeB = 0;
  bMode_CeB = 0;
  aPupiRegDataSize_CeB = 0;
  bAfi_CeB = 0;
  bHighLayerRspSize_CeB = 0;
  mIsInit = false;
}

/*******************************************************************************
 **
 ** Function:        ~HciRFParams
 **
 ** Description:     Release all resources.
 **
 ** Returns:         None
 **
 *******************************************************************************/
HciRFParams::~HciRFParams() {}

/*******************************************************************************
 **
 ** Function:        getInstance
 **
 ** Description:     Get the HciRFParams singleton object.
 **
 ** Returns:         HciRFParams object.
 **
 *******************************************************************************/
HciRFParams& HciRFParams::getInstance() { return sHciRFParams; }

/*******************************************************************************
 **
 ** Function:        initialize
 **
 ** Description:     Initialize all member variables.
 **                  native: Native data.
 **
 ** Returns:         True if ok.
 **
 *******************************************************************************/
bool HciRFParams::initialize() {
  static const char fn[] = "HciRFParams::initialize";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", fn);

  tNFA_PMID param_ids[] = {0xA0, 0xF0};
  {
    SyncEventGuard guard(android::sNfaGetConfigEvent);
    tNFA_STATUS stat = NFA_GetConfig(0x01, param_ids);
    //        NFA_GetConfig(0x01,param_ids);
    if (stat == NFA_STATUS_OK) {
      android::sNfaGetConfigEvent.wait();
    } else {
      return false;
    }
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: status %x", __func__, get_config.status);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: tlv_size %d", __func__, get_config.tlv_size);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: param_tlvs %x", __func__, get_config.param_tlvs[0]);

#if (NXP_EXTNS == true)
  if ((get_config.param_tlvs[1] == 0xA0 &&
       get_config.param_tlvs[2] == 0xF0) &&
      (get_config.param_tlvs[5] == 0xFF ||
       get_config.param_tlvs[43] == 0xFF) &&
      SecureElement::getInstance().getEeStatus(ESE_HANDLE) ==
          NFA_EE_STATUS_ACTIVE) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: CE with ESE is disabled", __func__);
    IsEseCeDisabled = true;
  }
#endif

  uint8_t* params = get_config.param_tlvs;
  params += VAL_START_IDX;

  bPipeStatus_CeA = *params++;
  bMode_CeA = *params++;
  bUidRegSize_CeA = *params++;

  memcpy(aUidReg_CeA, params, bUidRegSize_CeA);
  params += MAX_AID_SIZE;

  bSak_CeA = *params++;

  aATQA_CeA[0] = *params++;
  aATQA_CeA[1] = *params++;
  bApplicationDataSize_CeA = *params++;

  memcpy(aApplicationData_CeA, params, bApplicationDataSize_CeA);
  params += MAX_APP_DATA_SIZE;

  bFWI_SFGI_CeA = *params++;
  bCidSupport_CeA = *params++;
  bCltSupport_CeA = *params++;

  memcpy(aDataRateMax_CeA, params, 0x03);
  params += 3;

  bPipeStatus_CeB = *params++;
  bMode_CeB = *params++;

  if (nfcFL.chipType != pn547C2) {
    aPupiRegDataSize_CeB = *params++;
  }
  aPupiRegDataSize_CeB = 4;

  memcpy(aPupiReg_CeB, params, aPupiRegDataSize_CeB);
  params += aPupiRegDataSize_CeB;

  bAfi_CeB = *params++;

  memcpy(aATQB_CeB, params, 0x04);
  params += 4;

  bHighLayerRspSize_CeB = *params++;

  memcpy(aHighLayerRsp_CeB_CeB, params, bHighLayerRspSize_CeB);
  params += MAX_HIGHER_LAYER_RSP_SIZE;

  memcpy(aDataRateMax_CeB, params, 0x03);
  //    aDataRateMax_CeB[3];

  mIsInit = true;

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", fn);
  return (true);
}

/*******************************************************************************
 **
 ** Function:        finalize
 **
 ** Description:     Release all resources.
 **
 ** Returns:         None
 **
 *******************************************************************************/
void HciRFParams::finalize() {
  static const char fn[] = "HciRFParams::finalize";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", fn);

  mIsInit = false;

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", fn);
}

void HciRFParams::connectionEventHandler(uint8_t event,
                                         tNFA_DM_CBACK_DATA* eventData) {
  //    static const char fn [] = "HciRFParams::connectionEventHandler";
  //    /*commented to eliminate unused variable warning*/

  switch (event) {
    case NFA_DM_GET_CONFIG_EVT: {
      // get_config = (tNFA_GET_CONFIG*)eventData;
       memcpy(&get_config, eventData, sizeof(tNFA_GET_CONFIG));
      //        SyncEventGuard guard (android::sNfaGetConfigEvent);
      //        android::sNfaGetConfigEvent.notifyOne ();
    } break;
  }
}

void HciRFParams::getESeUid(uint8_t* uidbuff, uint8_t* uidlen) {
  if (false == mIsInit || *uidlen < bUidRegSize_CeA ||
      (uint8_t)NULL == *uidbuff) {
    *uidlen = 0x00;
    *uidbuff = (uint8_t)NULL;
  }

  memcpy(uidbuff, aUidReg_CeA, bUidRegSize_CeA);
  *uidlen = bUidRegSize_CeA;
}

uint8_t HciRFParams::getESeSak() {
  if (false == mIsInit) {
    return 0x00;
  }

  return bSak_CeA;
}

bool HciRFParams::isTypeBSupported() {
  bool status = false;

  if (false == mIsInit) {
    return 0x00;
  }

  if (bPipeStatus_CeB == 0x02 && bMode_CeB == 0x02) {
    status = true;
  }
  return status;
}

#if (NXP_EXTNS == TRUE)
bool HciRFParams::isCeWithEseDisabled() {
  static const char fn[] = "HciRFParams::isCeWithEseDisabled";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", fn);
  bool status = false;

  tNFA_PMID param_ids[] = {0xA0, 0xF0};
  {
    SyncEventGuard guard(android::sNfaGetConfigEvent);
    tNFA_STATUS stat = NFA_GetConfig(0x01, param_ids);
    if (stat == NFA_STATUS_OK) {
      android::sNfaGetConfigEvent.wait(500);
    } else {
      LOG(ERROR) << StringPrintf("%s: Get config is failed", __func__);
      return status;
    }
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: status %x", __func__, get_config.status);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: tlv_size %d", __func__, get_config.tlv_size);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: param_tlvs %x", __func__, get_config.param_tlvs[0]);

  if ((get_config.param_tlvs[1] == 0xA0 &&
       get_config.param_tlvs[2] == 0xF0) &&
      (get_config.param_tlvs[5] == 0xFF ||
       get_config.param_tlvs[43] == 0xFF) &&
      SecureElement::getInstance().getEeStatus(ESE_HANDLE) ==
          NFA_EE_STATUS_ACTIVE) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: CE with ESE is disabled", __func__);
    status = true;
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Exit status =%d", __func__, status);
  return status;
}
#endif
