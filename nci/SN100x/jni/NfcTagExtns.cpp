/*
 *  Tagreading, tagwriting operations.
 */
/******************************************************************************
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  Copyright 2022-2024 NXP
 *
 ******************************************************************************/
#include "NfcTagExtns.h"

#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <errno.h>

#include "nfc_config.h"

namespace android {
extern void nativeNfcTag_setTransceiveFlag(bool state);
extern void nativeNfcTag_abortWaits();
extern SyncEvent sTransceiveEvent;
extern bool nfc_debug_enabled;
extern bool gIsSelectingRfInterface;
extern void nativeNfcTag_doConnectStatus(jboolean is_connect_ok);
extern bool isSeRfActive();
}  // namespace android
extern uint32_t TimeDiff(timespec start, timespec end);
extern int sLastSelectedTagId;

NfcTagExtns NfcTagExtns::sTagExtns;

uint8_t RW_TAG_SLP_REQ[] = {0x50, 0x00};
uint8_t RW_DESELECT_REQ[] = {0xC2};
using android::base::StringPrintf;

/******************************************************************************
**
** Function:        getInstance
**
** Description:     Singleton call for NfcTagExtns class.
**
** Returns:         NfcTagExtns object.
**
*******************************************************************************/
NfcTagExtns& NfcTagExtns::getInstance() { return sTagExtns; }

/******************************************************************************
**
** Function:        initialize
**
** Description:     NfcTagExtns Initialization API.
**
** Returns:         None.
**
*******************************************************************************/
void NfcTagExtns::initialize() {
  sTagConnectedTargetType = 0;
  sTagActivatedMode = 0;
  sTagActivatedProtocol = 0;
  isNonStdCardSupported = false;
  isMfcTransceiveFailed = false;
  tagState = 0;  // clear all bit flags related tag operations
  mNonStdCardTimeDiff.push_back(100);
  mNonStdCardTimeDiff.push_back(300);
  if (NfcConfig::hasKey(NAME_NXP_NON_STD_CARD_TIMEDIFF)) {
    vector<uint8_t> timeDiff =
        NfcConfig::getBytes(NAME_NXP_NON_STD_CARD_TIMEDIFF);
    DLOG_IF(INFO, android::nfc_debug_enabled)
        << StringPrintf("%s: Non std card", __func__);
    for (size_t i = 0; i < timeDiff.size(); i++) {
      mNonStdCardTimeDiff.at(i) = timeDiff.at(i) * TIME_MUL_100MS;
      DLOG_IF(INFO, android::nfc_debug_enabled) << StringPrintf(
          "%s: timediff[%zu] = %d", __func__, i, mNonStdCardTimeDiff.at(i));
    }
  } else {
    DLOG_IF(INFO, android::nfc_debug_enabled)
        << StringPrintf("%s: timediff not defined taking default", __func__);
  }
  isNonStdCardSupported =
      (NfcConfig::getUnsigned(NAME_NXP_SUPPORT_NON_STD_CARD, 0) != 0) ? true
                                                                      : false;
  memset(&LastDetectedTime, 0, sizeof(timespec));
  memset(&discovery_ntf, 0, sizeof(discovery_ntf));
  memset(&intf_param, 0, sizeof(intf_param));
}

/******************************************************************************
**
** Function:        processNonStdTagOperation
**
** Description:     Public API for Non-standard TAG handling
**
** Returns:         TAG_STATUS_PROPRIETARY if proprietary else
**                  TAG_STATUS_STANDARD.
**
*******************************************************************************/
tTagStatus NfcTagExtns::processNonStdTagOperation(TAG_API_REQUEST caller,
                                                  TAG_OPERATION operation) {
  tTagStatus status = TAG_STATUS_FAILED;
  DLOG_IF(INFO, android::nfc_debug_enabled)
      << StringPrintf("%s: processNonStdTagOperation caller :%d state :%d",
                      __func__, caller, operation);
  switch (caller) {
    case TAG_API_REQUEST::TAG_RESELECT_API:
      switch (operation) {
        case TAG_OPERATION::TAG_HALT_PICC_OPERATION:
          status = performHaltPICC();
          break;
        case TAG_OPERATION::TAG_DEACTIVATE_OPERATION:
          status = performTagDeactivation();
          break;
        case TAG_OPERATION::TAG_DEACTIVATE_RSP_OPERATION:
          status = updateTagState();
          break;
        case TAG_OPERATION::TAG_RECONNECT_OPERATION:
          status = performTagReconnect();
          break;
        case TAG_OPERATION::TAG_RECONNECT_FAILED_OPERATION:
          status = performTagReconnectFailed();
          break;
        case TAG_OPERATION::TAG_CLEAR_STATE_OPERATION:
          status = clearTagState();
          break;
        default:
          break;
      }
      break;
    case TAG_API_REQUEST::TAG_CHECK_NDEF_API:
      status = checkAndSkipNdef();
      break;
    case TAG_API_REQUEST::TAG_DO_TRANSCEIVE_API:
      processMfcTransFailed();
      status = TAG_STATUS_SUCCESS;
      break;
  }
  return status;
}

/******************************************************************************
**
** Function:        processNonStdNtfHandler
**
** Description:     connection events/notification handling.
**
** Returns:         TAG_STATUS_PROPRIETARY if proprietary else
**                  TAG_STATUS_STANDARD.
**
*******************************************************************************/
void NfcTagExtns::processNonStdNtfHandler(EVENT_TYPE event,
                                          tNFA_CONN_EVT_DATA* eventDat) {
  DLOG_IF(INFO, android::nfc_debug_enabled) << StringPrintf(
      "%s: processNonStdNtfHandler event :%d ", __func__, event);

  switch (event) {
    case EVENT_TYPE::NFA_SELECT_RESULT_EVENT:
      processtagSelectEvent(eventDat);
      break;
    case EVENT_TYPE::NFA_DEACTIVATE_EVENT:
    case EVENT_TYPE::NFA_DEACTIVATE_FAIL_EVENT:
      processDeactivateEvent(eventDat, event);
      break;
    case EVENT_TYPE::NFA_DISC_RESULT_EVENT:
      processDiscoveryNtf(eventDat);
      break;
    case EVENT_TYPE::NFA_ACTIVATED_EVENT:
      processActivatedNtf(eventDat);
      break;
  }
  return;
}

/******************************************************************************
**
** Function:        isNonStdMFCTagDetected
**
** Description:     Check if tag is Non-standard MF.
**
** Returns:         True(Non-Std MFC)/FALSE(otherwise).
**
*******************************************************************************/
bool NfcTagExtns::isNonStdMFCTagDetected() {
  return (tagState & TAG_MFC_NON_STD_TYPE);
}

/******************************************************************************
**
** Function:        processDeactivateEvent
**
** Description:     RF_DEACTIVATE operation for Non-standard tag
**
** Returns:         None.
**
*******************************************************************************/
void NfcTagExtns::processDeactivateEvent(tNFA_CONN_EVT_DATA* eventData,
                                         EVENT_TYPE event) {
  NfcTag& nfcTag = NfcTag::getInstance();
  if (event == EVENT_TYPE::NFA_DEACTIVATE_EVENT) {
    if (tagState & TAG_DEACTIVATE_TO_SLEEP && (eventData != NULL)) {
      if (eventData->deactivated.type == NFA_DEACTIVATE_TYPE_IDLE) {
        tagState |= TAG_DEACTIVATE_TO_IDLE;
      } else if (eventData->deactivated.type == NFA_DEACTIVATE_TYPE_SLEEP) {
        tagState &= ~TAG_DEACTIVATE_TO_SLEEP;
      }
    }
    if ((eventData != NULL) &&
        eventData->deactivated.type != NFA_DEACTIVATE_TYPE_SLEEP) {
      nfcTag.setMultiProtocolTagSupport(false);
      // resetTechnologies does'nt clears mActivationParams_t
      // In multi protocol tag this parameter contains previous technology Info
      // So In case of Deactivate to discovery it shall not clear the same
      // Since resetTechnologies don't have deactivate type info.
      // Required to clear as part of new method. In future if required can
      // clear other required flags also.
      nfcTag.setNumDiscNtf(0);
      nfcTag.mTechListIndex = 0;
      if (android::gIsSelectingRfInterface == false) {
        if (eventData->deactivated.type != NFA_DEACTIVATE_TYPE_DISCOVERY)
          memset(&nfcTag.mActivationParams_t, 0, sizeof(activationParams_t));
        nfcTag.connectionEventHandler(NFA_DEACTIVATED_EVT, eventData);
        android::nativeNfcTag_abortWaits();
      }
    }
  } else if (event == EVENT_TYPE::NFA_DEACTIVATE_FAIL_EVENT) {
    if (eventData->status == NFC_DEACTIVATE_REASON_DH_REQ_FAILED) {
      tagState |= TAG_ISODEP_DEACTIVATE_FAIL;
      nfcTag.setNumDiscNtf(0);
      LOG(ERROR) << StringPrintf("%s: NFA_DEACTIVATE_FAIL_EVT", __func__);
      if (nfcTag.mIsMultiProtocolTag) {
        storeNonStdTagData();
      }
    }
    if (tagState & TAG_DEACTIVATE_TO_SLEEP) {
      tagState &= ~TAG_DEACTIVATE_TO_SLEEP;
    }
  } else {
    // do nothing
    LOG(ERROR) << StringPrintf("%s: Unknown event type", __func__);
  }
}

/******************************************************************************
**
** Function:        processActivatedNtf
**
** Description:     Handle RF_INTF_ACTIVATED_NTF for Non-standard tag
**
** Returns:         None.
**
*******************************************************************************/
void NfcTagExtns::processActivatedNtf(tNFA_CONN_EVT_DATA* data) {
  bool isTagOpertion = false;
  if ((data->activated.activate_ntf.protocol != NFA_PROTOCOL_NFC_DEP) &&
      (!isListenMode(data->activated))) {
    setRfProtocol((tNFA_INTF_TYPE)data->activated.activate_ntf.protocol,
                  data->activated.activate_ntf.rf_tech_param.mode);
    if (getActivatedMode() == TARGET_TYPE_ISO14443_3B) {
      DLOG_IF(INFO, android::nfc_debug_enabled) << StringPrintf(
          "%s: NFA_ACTIVATED_EVT: received typeB NFCID0", __func__);
      updateNfcID0Param(
          data->activated.activate_ntf.rf_tech_param.param.pb.nfcid0);
    }
    isTagOpertion = true;
  }
  if (android::gIsSelectingRfInterface) {
    if (checkActivatedProtoParameters(data->activated)) {
      NfcTag::getInstance().setActivationState();
    }
  } else {
    if (isTagOpertion) {
      tNFA_ACTIVATED& activated = data->activated;
      // In case activated tag is a multiprotocol tag then store
      // activated tag data because sometimes sleep may not supported by
      // non standard tag during multiprotocol tag detection.
      if (NfcTag::getInstance().mIsMultiProtocolTag &&
          data->activated.activate_ntf.protocol == NFA_PROTOCOL_ISO_DEP) {
        clearNonStdTagData();
        memcpy(&(discovery_ntf.rf_tech_param),
               &(activated.activate_ntf.rf_tech_param),
               sizeof(tNFC_RF_TECH_PARAMS));
        memcpy(&intf_param, &(activated.activate_ntf.intf_param),
               sizeof(tNFC_INTF_PARAMS));
      }
    }
  }
  tagState &= ~TAG_ISODEP_DEACTIVATE_FAIL;
  /*clear NonStdMfcTag state if a non-multiprotocol tag is activated*/
  if (!NfcTag::getInstance().mIsMultiProtocolTag &&
      (tagState & TAG_MFC_NON_STD_TYPE)) {
    clearNonStdMfcState();
  }
}

/*******************************************************************************
**
** Function:        isMfcTransFailed
**
** Description:     Returns Miafare Transceive is Fail or not.
**
**
** Returns:         true if Mifare Transceive fail flag set, else false.
**
*******************************************************************************/
bool NfcTagExtns::isMfcTransFailed() { return isMfcTransceiveFailed; }

/*******************************************************************************
**
** Function:        resetMfcTransceiveFlag
**
** Description:     reset isMfcTransceiveFailed flag to false.
**
** Returns:         None
**
*******************************************************************************/
void NfcTagExtns::resetMfcTransceiveFlag() {
  if (!isNonStdCardSupported) {
    DLOG_IF(INFO, android::nfc_debug_enabled)
        << StringPrintf("%s:Non standard support disabled", __func__);
    return;
  }
  isMfcTransceiveFailed = false;
}

/*******************************************************************************
**
** Function:        processMfcTransFailed
**
** Description:     set isMfcTransceiveFailed flag , if connected tag is Multi-
**                  protocol tag with MFC support & current selected interface
**                  is Mifare.
**
**
** Returns:         None
**
*******************************************************************************/
void NfcTagExtns::processMfcTransFailed() {
  if (!isNonStdCardSupported) {
    DLOG_IF(INFO, android::nfc_debug_enabled)
        << StringPrintf("%s:Non standard support disabled", __func__);
    return;
  }
  if (IS_MULTIPROTO_MFC_TAG()) {
    isMfcTransceiveFailed = true;
  }
}

/*******************************************************************************
**
** Function:        checkActivatedProtoParameters
**
** Description:     Check whether tag activated params are same.If different it
**                  will restart rf discovery.
**
**
** Returns:         true(same protocol)/false(different protocol)
**
*******************************************************************************/
bool NfcTagExtns::checkActivatedProtoParameters(
    tNFA_ACTIVATED& activationData) {
  bool status = false;
  NfcTag& natTag = NfcTag::getInstance();
  tNFC_ACTIVATE_DEVT& rfDetail = activationData.activate_ntf;
  if (natTag.mCurrentRequestedProtocol != NFC_PROTOCOL_UNKNOWN &&
      rfDetail.protocol != natTag.mCurrentRequestedProtocol) {
    NFA_Deactivate(FALSE);
  } else {
    status = true;
  }
  DLOG_IF(INFO, android::nfc_debug_enabled) << StringPrintf(
      "%s: mCurrentRequestedProtocol %x rfDetail.protocol %x", __func__,
      natTag.mCurrentRequestedProtocol, rfDetail.protocol);
  return status;
}

/*******************************************************************************
**
** Function:        isListenMode
**
** Description:     Indicates whether the activation data indicates it is
**                  listen mode.
**
** Returns:         True if this listen mode.
**
*******************************************************************************/
bool NfcTagExtns::isListenMode(tNFA_ACTIVATED& activated) {
  return (
      (NFC_DISCOVERY_TYPE_LISTEN_A ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_B ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_F ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_A_ACTIVE ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_F_ACTIVE ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_ISO15693 ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_B_PRIME ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_INTERFACE_EE_DIRECT_RF == activated.activate_ntf.intf_param.type));
}

/******************************************************************************
**
** Function:        checkAndClearNonStdTagState
**
** Description:     Clears the proprietary tag state
**
** Returns:         true if proprietary else false.
*
**
*******************************************************************************/
bool NfcTagExtns::checkAndClearNonStdTagState() {
  bool ret = false;
  if (tagState & TAG_NON_STD_SAK_TYPE) {
    tagState &= ~TAG_NON_STD_SAK_TYPE;
    DLOG_IF(INFO, android::nfc_debug_enabled)
        << StringPrintf("%s: non std Tag", __func__);
    ret = true;
  }
  return ret;
}

/*******************************************************************************
**
** Function         storeNonStdTagData
**
** Description      Store non standard tag data
**
** Returns          None
**
*******************************************************************************/
void NfcTagExtns::storeNonStdTagData() {
  int ret = clock_gettime(CLOCK_MONOTONIC, &LastDetectedTime);
  if (ret == -1) {
    DLOG_IF(ERROR, android::nfc_debug_enabled)
        << StringPrintf("Log : clock_gettime failed");
    clearNonStdTagData();
  } else {
    tNFC_RESULT_DEVT& nonStdTagInfo = discovery_ntf;
    nonStdTagInfo.rf_disc_id =
        NfcTag::getInstance().mTechHandles[sLastSelectedTagId];
    nonStdTagInfo.protocol =
        NfcTag::getInstance().mTechLibNfcTypes[sLastSelectedTagId];
    DLOG_IF(INFO, android::nfc_debug_enabled)
        << StringPrintf("%s: %u is stored", __func__, nonStdTagInfo.rf_disc_id);
  }
}

/*******************************************************************************
 **
 ** Function:        processtagSelectEvent
 **
 ** Description:     Update Time in case Mifare activation failed.
 **
 ** Returns:         None
 **
 *******************************************************************************/
void NfcTagExtns::processtagSelectEvent(tNFA_CONN_EVT_DATA* data) {
  if (!isNonStdCardSupported) {
    DLOG_IF(INFO, android::nfc_debug_enabled)
        << StringPrintf("%s:Non standard support disabled", __func__);
    return;
  }

  if (isMfcTransFailed() && data->status != NFA_STATUS_OK) {
    /* If Mifare Transcieve failed && observed Core Generic Error NTF. */
    data->status = NFA_STATUS_OK;
    android::nativeNfcTag_doConnectStatus(JNI_FALSE);
  } else if (data->status != NFA_STATUS_OK) {
    NfcTag::getInstance().mTechListIndex = 0;
    if (IS_MULTIPROTO_MFC_TAG()) {
      tagState |= TAG_MFC_NON_STD_TYPE;

      DLOG_IF(INFO, android::nfc_debug_enabled)
          << StringPrintf("%s: Non STD MFC sequence1", __func__);
      int ret = clock_gettime(CLOCK_MONOTONIC, &LastDetectedTime);
      if (ret == -1) {
        DLOG_IF(ERROR, android::nfc_debug_enabled)
            << StringPrintf("Log : clock_gettime failed");
      }
    }
  }
}

/*******************************************************************************
 **
 ** Function:        processDiscoveryNtf
 **
 ** Description:     Handle RF_DISCOVER_NTF for proprietary tag
 **
 ** Returns:         TAG_STATUS_PROPRIETARY if proprietary else
 *                   TAG_STATUS_STANDARD.
 **
 *******************************************************************************/
void NfcTagExtns::processDiscoveryNtf(tNFA_CONN_EVT_DATA* data) {
  // tNFA_DISC_RESULT& disc_result = data->disc_result;
  tNFC_RESULT_DEVT& discovery_ntf = data->disc_result.discovery_ntf;

  if (!isNonStdCardSupported) {
    DLOG_IF(INFO, android::nfc_debug_enabled)
        << StringPrintf("%s:Non standard support disabled", __func__);
    return;
  }
  DLOG_IF(INFO, android::nfc_debug_enabled)
      << StringPrintf("%s:Non standard support enabled", __func__);
  if (discovery_ntf.rf_tech_param.param.pa.sel_rsp == NON_STD_CARD_SAK) {
    // Non Standard Transit => ISO-DEP
    DLOG_IF(INFO, android::nfc_debug_enabled) << StringPrintf(
        "%s:Non standard Transit => change to ISO-DEP", __func__);
    // Shall be updated as part of callback
    NfcTag::getInstance()
        .mTechLibNfcTypesDiscData[NfcTag::getInstance().mNumDiscNtf] =
        NFC_PROTOCOL_ISO_DEP;
    tagState |= TAG_NON_STD_SAK_TYPE;
  } else {
    updateNonStdTagState(discovery_ntf.protocol, discovery_ntf.more);
  }
  return;
}

/*******************************************************************************
**
** Function         shouldSkipProtoActivate
**
** Description      Check whether tag activation should be skipped or not. If
**                  activation is skipped then send fake activate event.
**
** Returns:         True if tag activation is skipped.
**
*******************************************************************************/
bool NfcTagExtns::shouldSkipProtoActivate(tNFC_PROTOCOL protocol) {
  bool status = false;
  if ((protocol == NFA_PROTOCOL_ISO_DEP) &&
      (tagState & TAG_SKIP_ISODEP_ACT_TYPE)) {
    NfcTag& natTag = NfcTag::getInstance();
    tNFA_CONN_EVT_DATA evt_data;
    tNFC_ACTIVATE_DEVT& act_ntf = evt_data.activated.activate_ntf;
    tNFC_RESULT_DEVT& nonStdTagInfo = discovery_ntf;
    act_ntf.rf_disc_id = nonStdTagInfo.rf_disc_id;
    act_ntf.protocol = nonStdTagInfo.protocol;
    memcpy(&(act_ntf.rf_tech_param), &(nonStdTagInfo.rf_tech_param),
           sizeof(tNFC_RF_TECH_PARAMS));
    memcpy(&(act_ntf.intf_param), &(intf_param), sizeof(tNFC_INTF_PARAMS));
    DLOG_IF(INFO, android::nfc_debug_enabled)
        << StringPrintf("%s: %u is skipped", __func__, act_ntf.rf_disc_id);
    natTag.connectionEventHandler(NFA_ACTIVATED_EVT, &evt_data);
    // Shall be handled as part of NfcTag
    natTag.setNumDiscNtf((natTag.getNumDiscNtf() - 1));
    status = true;
  }
  return status;
}
/*******************************************************************************
**
** Function         isTagDetectedInRefTime
**
** Description      Computes time difference in milliseconds and compare it
**                  with the reference provided.
**
** Returns          TRUE(time diff less than reference)/FALSE(Otherwise)
**
*******************************************************************************/
bool NfcTagExtns::isTagDetectedInRefTime(uint32_t reference) {
  bool isNonStdCard = false;
  struct timespec end;
  uint32_t timediff;
  int ret = clock_gettime(CLOCK_MONOTONIC, &end);
  if (ret == -1) {
    DLOG_IF(ERROR, android::nfc_debug_enabled)
        << StringPrintf("%s : clock_gettime failed", __func__);
    return false;
  }
  timediff = TimeDiff(LastDetectedTime, end);
  if (timediff < reference) {
    DLOG_IF(INFO, android::nfc_debug_enabled) << StringPrintf(
        "%s: Non standard MFC tag detected, sequence-2", __func__);
    isNonStdCard = true;
  }
  return isNonStdCard;
}

/*******************************************************************************
**
** Function         updateNonStdTagState
**
** Description      Update Non standard Tag state based on RF_DISC_NTF or
**                  INTF_ACTIVATED_NTF
**
** Returns          None
**
*******************************************************************************/
void NfcTagExtns::updateNonStdTagState(uint8_t protocol,
                                       uint8_t more_disc_ntf) {
  if (protocol == NFC_PROTOCOL_MIFARE) {
    /*If NonStd MFC tag is detected*/
    if ((tagState & TAG_MFC_NON_STD_TYPE) &&
        isTagDetectedInRefTime(mNonStdCardTimeDiff[MFC])) {
      tagState |= TAG_SKIP_NDEF_TYPE;
    } else {
      clearNonStdMfcState();
    }
    /*If WA flag is true but no non standard MFC detected in next iteration
     * clear the WA flag*/
  } else if (protocol == NFC_PROTOCOL_ISO_DEP) {
    if ((tagState & TAG_ISODEP_DEACTIVATE_FAIL) &&
        isTagDetectedInRefTime(mNonStdCardTimeDiff[ISO_DEP])) {
      tagState |= TAG_SKIP_ISODEP_ACT_TYPE;
    } else {
      clearNonStdTagData();
    }
  } else if (more_disc_ntf == NCI_DISCOVER_NTF_LAST) {
    bool isMFCDetected = false;
    for (int i = 0; i < NfcTag::getInstance().mNumTechList; i++) {
      if (NfcTag::getInstance().mTechLibNfcTypes[i] == NFC_PROTOCOL_MIFARE) {
        isMFCDetected = true;
      }
    }
    if (!isMFCDetected) {
      clearNonStdMfcState();
    }
  }
  /*retain the status*/
  else {
  }
  return;
}

/*******************************************************************************
**
** Function         clearNonStdMfcState
**
** Description      Clear Non standard MFC states
**
** Returns          None
**
*******************************************************************************/
void NfcTagExtns::clearNonStdMfcState() {
  tagState &= ~TAG_SKIP_NDEF_TYPE;
  tagState &= ~TAG_MFC_NON_STD_TYPE;
  memset(&LastDetectedTime, 0, sizeof(timespec));
}

/*******************************************************************************
**
** Function         clearNonStdTagData
**
** Description      Clear non standard tag data
**
** Returns          None
**
*******************************************************************************/
void NfcTagExtns::clearNonStdTagData() {
  DLOG_IF(INFO, android::nfc_debug_enabled) << StringPrintf("%s", __func__);
  memset(&discovery_ntf, 0, sizeof(discovery_ntf));
  memset(&intf_param, 0, sizeof(intf_param));
  tagState &= ~TAG_SKIP_ISODEP_ACT_TYPE;
}

/*******************************************************************************
**
** Function:        performHaltPICC()
**
** Description:     Issue HALT as per the current activated protocol & mode
**
** Returns:         True if ok.
**
*******************************************************************************/
tTagStatus NfcTagExtns::performHaltPICC() {
  tNFA_STATUS status = NFA_STATUS_OK;
  tTagStatus ret = TAG_STATUS_SUCCESS;
#if (NXP_SRD == TRUE)
  static const uint8_t ENABLE = 0x01;
  if (SecureDigitization::getInstance().getSrdState() == ENABLE) {
    return ret;
  }
#endif
  if (getActivatedProtocol() == NFA_PROTOCOL_T2T ||
      (getActivatedProtocol() == NFA_PROTOCOL_ISO_DEP &&
       getActivatedMode() == TARGET_TYPE_ISO14443_3A)) {
    status = NFA_SendRawFrame(RW_TAG_SLP_REQ, sizeof(RW_TAG_SLP_REQ), 0);
    usleep(10 * 1000);
  } else if (getActivatedProtocol() == NFA_PROTOCOL_ISO_DEP &&
             getActivatedMode() == TARGET_TYPE_ISO14443_3B) {
    uint8_t halt_b[5] = {0x50, 0, 0, 0, 0};
    memcpy(&halt_b[1], mNfcID0, 4);
    android::nativeNfcTag_setTransceiveFlag(true);
    SyncEventGuard g(android::sTransceiveEvent);
    status = NFA_SendRawFrame(halt_b, sizeof(halt_b), 0);
    if (status != NFA_STATUS_OK) {
      DLOG_IF(ERROR, android::nfc_debug_enabled)
          << StringPrintf("%s: fail send; error=%d", __func__, status);
      ret = TAG_STATUS_FAILED;
    } else {
      if (android::sTransceiveEvent.wait(100) == false) {
        ret = TAG_STATUS_FAILED;
        DLOG_IF(ERROR, android::nfc_debug_enabled)
            << StringPrintf("%s: timeout on HALTB", __func__);
      }
    }
    android::nativeNfcTag_setTransceiveFlag(false);
  }
  return ret;
}

/*******************************************************************************
**
** Function:        performTagDeactivation()
**
** Description:     Perform deactivation of proprietary tag types
**
** Returns:         TAG_STATUS_PROPRIETARY if proprietary else
**                  TAG_STATUS_STANDARD.
**
*******************************************************************************/
tTagStatus NfcTagExtns::performTagDeactivation() {
  tNFA_STATUS status = NFA_STATUS_OK;
  tTagStatus ret = TAG_STATUS_STANDARD;

  if (tagState & TAG_CASHBEE_TYPE) {
    ret = TAG_STATUS_PROPRIETARY;
    DLOG_IF(INFO, android::nfc_debug_enabled)
        << StringPrintf("%s: Deactivate to IDLE", __func__);
    if (NFA_STATUS_OK != (status = NFA_StopRfDiscovery())) {
      LOG(ERROR) << StringPrintf("%s: Deactivate failed, status = 0x%0X",
                                 __func__, status);
      ret = TAG_STATUS_FAILED;
    }
  } else {
    if (android::isSeRfActive()) {
      tNFA_DEACTIVATED deactivated = {NFA_DEACTIVATE_TYPE_IDLE,
                                      NCI_DEACTIVATE_REASON_DH_REQ};
      NfcTag::getInstance().setDeactivationState(deactivated);
      DLOG_IF(INFO, android::nfc_debug_enabled)
          << StringPrintf("%s: card emulation on priotiy", __func__);
      ret = TAG_STATUS_LOST;
    } else {
      DLOG_IF(INFO, android::nfc_debug_enabled)
          << StringPrintf("%s: deactivate to sleep", __func__);
      if (NFA_STATUS_OK !=
          (status = NFA_Deactivate(TRUE))) {  // deactivate to sleep state
        LOG(ERROR) << StringPrintf("%s: deactivate failed, status = %d",
                                   __func__, status);
        ret = TAG_STATUS_FAILED;
      }
    }
  }

  if ((ret == TAG_STATUS_STANDARD) &&
      NfcTag::getInstance().mIsMultiProtocolTag) {
    tagState |= TAG_DEACTIVATE_TO_SLEEP;
  }
  return ret;
}

/*******************************************************************************
**
** Function:        updateTagState()
**
** Description:     Update tag state after receiving RF_DEACTIVATE_NTF
**
** Returns:         TAG_STATUS_PROPRIETARY if proprietary else
**                  TAG_STATUS_STANDARD.
**
*******************************************************************************/
tTagStatus NfcTagExtns::updateTagState() {
  tTagStatus ret = TAG_STATUS_STANDARD;

  if ((tagState & TAG_DEACTIVATE_TO_SLEEP) &&
      (tagState & TAG_DEACTIVATE_TO_IDLE)) {
    LOG(ERROR) << StringPrintf("%s: wrong deactivate ntf; break", __func__);
    tagState &= ~TAG_DEACTIVATE_TO_SLEEP;
    tagState &= ~TAG_DEACTIVATE_TO_IDLE;
    return TAG_STATUS_LOST;
  }
  if (NfcTag::getInstance().getActivationState() == NfcTag::Idle) {
    DLOG_IF(INFO, android::nfc_debug_enabled)
        << StringPrintf("%s: Tag is in IDLE state", __func__);

    if ((NfcTag::getInstance().mActivationParams_t.mTechLibNfcTypes ==
         NFC_PROTOCOL_ISO_DEP) &&
        (NfcTag::getInstance().mActivationParams_t.mTechParams ==
         NFC_DISCOVERY_TYPE_POLL_A)) {
      tagState |= TAG_CASHBEE_TYPE;
      DLOG_IF(INFO, android::nfc_debug_enabled)
          << StringPrintf("%s: CashBee Detected", __func__);
      ret = TAG_STATUS_PROPRIETARY;
    }
  }
  return ret;
}

/*******************************************************************************
**
** Function:        performTagReconnect()
**
** Description:     Perform proprietary tag connect operation
**
** Returns:         TAG_STATUS_PROPRIETARY if proprietary else
**                  TAG_STATUS_STANDARD.
**
*******************************************************************************/
tTagStatus NfcTagExtns::performTagReconnect() {
  tTagStatus ret = TAG_STATUS_STANDARD;
  tNFA_STATUS status = NFA_STATUS_OK;
  if (tagState & TAG_CASHBEE_TYPE) {
    ret = TAG_STATUS_PROPRIETARY;
    DLOG_IF(INFO, android::nfc_debug_enabled)
        << StringPrintf("%s: Start RF discovery", __func__);
    if (!(tagState & TAG_ISODEP_DEACTIVATE_FAIL) &&
        NFA_STATUS_OK != (status = NFA_StartRfDiscovery())) {
      LOG(ERROR) << StringPrintf("%s: deactivate failed, status = 0x%0X",
                                 __func__, status);
      ret = TAG_STATUS_FAILED;
    }
  }
  return ret;
}

/*******************************************************************************
**
** Function:        performTagReconnectFailed()
**
** Description:     Proprietary Tag handling on connection failure
**
** Returns:         TAG_STATUS_PROPRIETARY if proprietary else
**                  TAG_STATUS_STANDARD.
**
*******************************************************************************/
tTagStatus NfcTagExtns::performTagReconnectFailed() {
  tTagStatus ret = TAG_STATUS_STANDARD;
  tNFA_STATUS status = NFA_STATUS_OK;
  if (!(tagState & TAG_CASHBEE_TYPE)) {
    status = NFA_Deactivate(false);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: deactivate failed; error status = 0x%X",
                                 __func__, status);
      ret = TAG_STATUS_FAILED;
    }
  }
  return ret;
}

/*******************************************************************************
**
** Function:        clearTagState()
**
** Description:     Proprietary Tag state cleanup
**
** Returns:         TAG_STATUS_PROPRIETARY if proprietary else
**                  TAG_STATUS_STANDARD.
**
*******************************************************************************/
tTagStatus NfcTagExtns::clearTagState() {
  tagState &= ~TAG_CASHBEE_TYPE;
  tagState &= ~TAG_DEACTIVATE_TO_SLEEP;
  tagState &= ~TAG_DEACTIVATE_TO_IDLE;
  return TAG_STATUS_SUCCESS;
}

/*******************************************************************************
**
** Function:        checkAndSkipNdef()
**
** Description:     Proprietary Tag handling on NDEF check API request
**
** Returns:         TAG_STATUS_PROPRIETARY if proprietary else
**                  TAG_STATUS_STANDARD.
**
*******************************************************************************/
tTagStatus NfcTagExtns::checkAndSkipNdef() {
#define SKIP_NDEF_NONSTD_MFC() \
  (IS_MULTIPROTO_MFC_TAG() && (tagState & TAG_SKIP_NDEF_TYPE))

  if (NfcTag::getInstance().mCurrentRequestedProtocol == NFA_PROTOCOL_T3BT ||
      SKIP_NDEF_NONSTD_MFC()) {
    clearNonStdMfcState();
    return TAG_STATUS_PROPRIETARY;
  }
  return TAG_STATUS_STANDARD;
}

/*******************************************************************************
 **
 ** Function:        setRfProtocol
 **
 ** Description:     Set rf Activated Protocol.
 **
 ** Returns:         void
 **
 *******************************************************************************/
void NfcTagExtns::setRfProtocol(tNFA_INTF_TYPE rfProtocol, uint8_t mode) {
  sTagActivatedProtocol = rfProtocol;
  if (mode == NFC_DISCOVERY_TYPE_POLL_A ||
      mode == NFC_DISCOVERY_TYPE_POLL_A_ACTIVE)
    sTagActivatedMode = TARGET_TYPE_ISO14443_3A;
  else if (mode == NFC_DISCOVERY_TYPE_POLL_B ||
           mode == NFC_DISCOVERY_TYPE_POLL_B_PRIME)
    sTagActivatedMode = TARGET_TYPE_ISO14443_3B;
  else
    sTagActivatedMode = sTagConnectedTargetType;
}

/*******************************************************************************
 **
 ** Function:        getActivatedProtocol
 **
 ** Description:     Get Activated protocol.
 **
 ** Returns:         Returns protocol
 **
 *******************************************************************************/
uint8_t NfcTagExtns::getActivatedProtocol() { return sTagActivatedProtocol; }

/*******************************************************************************
 **
 ** Function:        getActivatedMode
 **
 ** Description:     Get rf Activated Mode.
 **
 ** Returns:         Returns Tech and mode parameter
 **
 *******************************************************************************/
uint8_t NfcTagExtns::getActivatedMode() { return sTagActivatedMode; }

/*******************************************************************************
 **
 ** Function:        setCurrentTargetType
 **
 ** Description:     Set target handle request for connection
 **
 ** Returns:         None
 **
 *******************************************************************************/
void NfcTagExtns::setCurrentTargetType(int type) {
  sTagConnectedTargetType = type;
}

/*******************************************************************************
 **
 ** Function:        abortTagOperation
 **
 ** Description:     Clear all tag state data.
 **
 ** Returns:         None
 **
 *******************************************************************************/
void NfcTagExtns::abortTagOperation() {
  sTagConnectedTargetType = 0;
  sTagActivatedMode = 0;
  sTagActivatedProtocol = 0;
  tagState &= ~TAG_NON_STD_SAK_TYPE;
  resetMfcTransceiveFlag();
}

/******************************************************************************
**
** Function:        updateNfcID0Param
**
** Description:     Update TypeB NCIID0 from interface activated ntf.
**
** Returns:         None.
**
*******************************************************************************/
void NfcTagExtns::updateNfcID0Param(uint8_t* nfcID0) {
  DLOG_IF(INFO, android::nfc_debug_enabled)
      << StringPrintf("%s: nfcID0 =%X%X%X%X", __func__, nfcID0[0], nfcID0[1],
                      nfcID0[2], nfcID0[3]);
  memcpy(mNfcID0, nfcID0, 4);
}
