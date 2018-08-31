/*
 * Copyright (C) 2012 The Android Open Source Project
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
/******************************************************************************
 *
 *  The original Work has been changed by NXP Semiconductors.
 *
 *  Copyright (C) 2015-2018 NXP Semiconductors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <errno.h>
#include <semaphore.h>
#include "JavaClassConstants.h"
#include "NfcAdaptation.h"
#include "NfcJniUtil.h"
#include "RoutingManager.h"
#include "SyncEvent.h"
#include "config.h"

#include "nfa_api.h"
#include "nfa_rw_api.h"

using android::base::StringPrintf;

extern bool nfc_debug_enabled;

typedef struct nxp_feature_data {
  SyncEvent NxpFeatureConfigEvt;
  Mutex mMutex;
  tNFA_STATUS wstatus;
  uint8_t rsp_data[255];
  uint8_t rsp_len;
} Nxp_Feature_Data_t;

extern int32_t gActualSeCount;
uint8_t swp_getconfig_status;
#if (NXP_EXTNS == TRUE)
extern uint8_t sSelectedUicc;
#endif
namespace android {
static Nxp_Feature_Data_t gnxpfeature_conf;
void SetCbStatus(tNFA_STATUS status);
tNFA_STATUS GetCbStatus(void);
static void NxpResponse_Cb(uint8_t event, uint16_t param_len, uint8_t* p_param);
static void NxpResponse_SetDhlf_Cb(uint8_t event, uint16_t param_len,
                                   uint8_t* p_param);
static void NxpResponse_SetVenConfig_Cb(uint8_t event, uint16_t param_len,
                                        uint8_t* p_param);
#if (NXP_EXTNS == TRUE)
tNFA_STATUS NxpNfc_Send_CoreResetInit_Cmd(void);
typedef void(tNXP_RSP_CBACK)(uint8_t event, uint16_t param_len,
                             uint8_t* p_param);
tNFA_STATUS NxpNfc_Write_Cmd(uint8_t retlen, uint8_t* buffer,
                             tNXP_RSP_CBACK* p_cback);
tNFA_STATUS NxpNfcUpdateEeprom(uint8_t* param, uint8_t len, uint8_t* val);
#endif
}  // namespace android

namespace android {
void SetCbStatus(tNFA_STATUS status) { gnxpfeature_conf.wstatus = status; }

tNFA_STATUS GetCbStatus(void) { return gnxpfeature_conf.wstatus; }

void NxpPropCmd_OnResponseCallback(uint8_t event, uint16_t param_len,
                                   uint8_t *p_param) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
    "NxpPropCmd_OnResponseCallback: Received length data = 0x%x status = "
        "0x%x", param_len, p_param[3]);
  uint8_t oid = p_param[1];
  uint8_t status = NFA_STATUS_FAILED;

  switch (oid) {
  case (0x1A):
  /*FALL_THRU*/
  case (0x1C):
    status = p_param[3];
    break;
  case (0x1B):
    status = p_param[param_len - 1];
    break;
  default:
    LOG(ERROR) << StringPrintf("Propreitary Rsp: OID is not supported");
    break;
  }

  android::SetCbStatus(status);

  android::gnxpfeature_conf.rsp_len = (uint8_t)param_len;
  memcpy(android::gnxpfeature_conf.rsp_data, p_param, param_len);
  SyncEventGuard guard(android::gnxpfeature_conf.NxpFeatureConfigEvt);
  android::gnxpfeature_conf.NxpFeatureConfigEvt.notifyOne();
}
tNFA_STATUS NxpPropCmd_send(uint8_t *pData4Tx, uint8_t dataLen,
                            uint8_t *rsp_len, uint8_t *rsp_buf,
                            uint32_t rspTimeout, tHAL_NFC_ENTRY *halMgr) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  bool retVal = false;

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: prop cmd being txed", __func__);

  gnxpfeature_conf.mMutex.lock();

  android::SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(android::gnxpfeature_conf.NxpFeatureConfigEvt);

  status =
      NFA_SendRawVsCommand(dataLen, pData4Tx, NxpPropCmd_OnResponseCallback);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Success NFA_SendNxpNciCommand", __func__);

    retVal = android::gnxpfeature_conf.NxpFeatureConfigEvt.wait(
        rspTimeout); /* wait for callback */
    if (retVal == false) {
      android::SetCbStatus(NFA_STATUS_TIMEOUT);
      android::gnxpfeature_conf.rsp_len = 0;
      memset(android::gnxpfeature_conf.rsp_data, 0,
             sizeof(android::gnxpfeature_conf.rsp_data));
    }
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendNxpNciCommand", __func__);
  }
  status = android::GetCbStatus();
  if ((android::gnxpfeature_conf.rsp_len > 3) && (rsp_buf != NULL)) {
    *rsp_len = android::gnxpfeature_conf.rsp_len - 3;
    memcpy(rsp_buf, android::gnxpfeature_conf.rsp_data + 3,
           android::gnxpfeature_conf.rsp_len - 3);
  }
  android::gnxpfeature_conf.mMutex.unlock();
  return status;
}

static void NxpResponse_Cb(uint8_t event, uint16_t param_len,
                           uint8_t* p_param) {
  (void)event;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "NxpResponse_Cb Received length data = 0x%x status = 0x%x", param_len,
      p_param[3]);

  if (p_param[3] == 0x00) {
    SetCbStatus(NFA_STATUS_OK);
  } else {
    SetCbStatus(NFA_STATUS_FAILED);
  }
  gnxpfeature_conf.rsp_len = (uint8_t)param_len;
  if (param_len > 0 && p_param != NULL) {
    memcpy(gnxpfeature_conf.rsp_data, p_param, param_len);
  }
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  gnxpfeature_conf.NxpFeatureConfigEvt.notifyOne();
}
static void NxpResponse_SetDhlf_Cb(uint8_t event, uint16_t param_len,
                                   uint8_t* p_param) {
  (void)event;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "NxpResponse_SetDhlf_Cb Received length data = 0x%x status = 0x%x",
      param_len, p_param[3]);

  if (p_param[3] == 0x00) {
    SetCbStatus(NFA_STATUS_OK);
  } else {
    SetCbStatus(NFA_STATUS_FAILED);
  }

  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  gnxpfeature_conf.NxpFeatureConfigEvt.notifyOne();
}

static void NxpResponse_SetVenConfig_Cb(uint8_t event, uint16_t param_len,
                                        uint8_t* p_param) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "NxpResponse_SetVenConfig_Cb Received length data = 0x%x status = 0x%x",
      param_len, p_param[3]);
  if (p_param[3] == 0x00) {
    SetCbStatus(NFA_STATUS_OK);
  } else {
    SetCbStatus(NFA_STATUS_FAILED);
  }
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  gnxpfeature_conf.NxpFeatureConfigEvt.notifyOne();
}

static void NxpResponse_SetSWPBitRate_Cb(uint8_t event, uint16_t param_len,
                                         uint8_t* p_param) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "NxpResponse_SetSWPBitRate_CbReceived length data = 0x%x status = 0x%x",
      param_len, p_param[3]);
  if (p_param[3] == 0x00) {
    SetCbStatus(NFA_STATUS_OK);
  } else {
    SetCbStatus(NFA_STATUS_FAILED);
  }
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  gnxpfeature_conf.NxpFeatureConfigEvt.notifyOne();
}

#if (NXP_EXTNS == TRUE)
/*******************************************************************************
 **
 ** Function:        NxpResponse_SwitchUICC_Cb
 **
 ** Description:     Callback for siwtch UICC is handled
 **                  Notifies NxpFeatureConfigEvt
 **
 ** Returns:         None
 **
 *******************************************************************************/
static void NxpResponse_SwitchUICC_Cb(uint8_t event, uint16_t param_len,
                                      uint8_t* p_param) {
  if (!nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s STAT_DUAL_UICC_EXT_SWITCH not available. Returning", __func__);
    return;
  }
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "NxpResponse_SwitchUICC_Cb length data = 0x%x status = 0x%x", param_len,
      p_param[3]);
  if (p_param[3] == 0x00) {
    SetCbStatus(NFA_STATUS_OK);
  } else {
    SetCbStatus(NFA_STATUS_FAILED);
  }
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  gnxpfeature_conf.NxpFeatureConfigEvt.notifyOne();
}
/*******************************************************************************
 **
 ** Function:        NxpResponse_EnableAGCDebug_Cb()
 **
 ** Description:     Cb to handle the response of AGC command
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
static void NxpResponse_EnableAGCDebug_Cb(uint8_t event, uint16_t param_len,
                                          uint8_t* p_param) {
  if (nfcFL.chipType == pn547C2) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s chipType : pn547C2. Not allowed. Returning", __func__);
    return;
  }

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "NxpResponse_EnableAGCDebug_Cb Received length data = 0x%x", param_len);
  SetCbStatus(NFA_STATUS_FAILED);
  if (param_len > 0) {
    gnxpfeature_conf.rsp_len = param_len;
    memcpy(gnxpfeature_conf.rsp_data, p_param, gnxpfeature_conf.rsp_len);
    SetCbStatus(NFA_STATUS_OK);
  }
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  gnxpfeature_conf.NxpFeatureConfigEvt.notifyOne();
}
/*******************************************************************************
 **
 ** Function:        printDataByte()
 **
 ** Description:     Prints the AGC values
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
static void printDataByte(uint16_t param_len, uint8_t* p_param) {
  if (nfcFL.chipType == pn547C2) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s chipType : pn547C2. Not allowed. Returning", __func__);
    return;
  }
  char print_buffer[param_len * 3 + 1];
  memset(print_buffer, 0, sizeof(print_buffer));
  for (int i = 0; i < param_len; i++) {
    snprintf(&print_buffer[i * 2], 3, "%02X", p_param[i]);
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("AGC Dynamic RSSI values  = %s", print_buffer);
}
/*******************************************************************************
 **
 ** Function:        SendAGCDebugCommand()
 **
 ** Description:     Sends the AGC Debug command.This enables dynamic RSSI
 **                  look up table filling for different "TX RF settings" and
 *enables
 **                  MWdebug prints.
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS SendAGCDebugCommand() {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  if (nfcFL.chipType == pn547C2) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s chipType : pn547C2. Not allowed. Returning", __func__);
    return NFA_STATUS_FAILED;
  }

  uint8_t cmd_buf[] = {0x2F, 0x33, 0x04, 0x40, 0x00, 0x40, 0xD8};

  uint8_t cmd_buf2[] = {0x2F, 0x32, 0x01, 0x01};
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  SetCbStatus(NFA_STATUS_FAILED);
  gnxpfeature_conf.rsp_len = 0;
  memset(gnxpfeature_conf.rsp_data, 0, 50);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  if (nfcFL.chipType == pn547C2 || nfcFL.chipType == pn551)
    status = NFA_SendRawVsCommand(sizeof(cmd_buf), cmd_buf,
                                  NxpResponse_EnableAGCDebug_Cb);
  else if (nfcFL.chipType == pn553 || nfcFL.chipType == pn557)
    status = NFA_SendRawVsCommand(sizeof(cmd_buf2), cmd_buf2,
                                  NxpResponse_EnableAGCDebug_Cb);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(1000); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }
  status = GetCbStatus();
  if (status == NFA_STATUS_OK && gnxpfeature_conf.rsp_len > 0) {
    printDataByte(gnxpfeature_conf.rsp_len, gnxpfeature_conf.rsp_data);
  }
  return status;
}
/*******************************************************************************
 **
 ** Function:        EmvCo_dosetPoll
 **
 ** Description:     Enable/disable Emv Co polling
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS EmvCo_dosetPoll(jboolean enable) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t cmd_buf[] = {0x20, 0x02, 0x05, 0x01, 0xA0, 0x44, 0x01, 0x00};

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  if (enable) {
    NFA_SetEmvCoState(true);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("EMV-CO polling profile");
    cmd_buf[7] = 0x01; /*EMV-CO Poll*/
  } else {
    NFA_SetEmvCoState(false);
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("NFC forum polling profile");
  }
  status = NFA_SendRawVsCommand(sizeof(cmd_buf), cmd_buf, NxpResponse_Cb);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }

  status = GetCbStatus();
  return status;
}

/*******************************************************************************
 **
 ** Function:        SetScreenState
 **
 ** Description:     set/clear SetScreenState
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS SetScreenState(jint state) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t screen_off_state_cmd_buff[] = {0x2F, 0x15, 0x01, 0x01};

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  if (state == NFA_SCREEN_STATE_OFF_UNLOCKED ||
      state == NFA_SCREEN_STATE_OFF_LOCKED) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Set Screen OFF");
    screen_off_state_cmd_buff[3] = 0x01;
  } else if (state == NFA_SCREEN_STATE_ON_LOCKED) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Screen ON-locked");
    screen_off_state_cmd_buff[3] = 0x02;
  } else if (state == NFA_SCREEN_STATE_ON_UNLOCKED) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Screen ON-Unlocked");
    screen_off_state_cmd_buff[3] = 0x00;
  } else {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Invalid screen state");
  }
  status =
      NFA_SendRawVsCommand(sizeof(screen_off_state_cmd_buff),
                           screen_off_state_cmd_buff, NxpResponse_SetDhlf_Cb);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }

  status = GetCbStatus();
  return status;
}

/*******************************************************************************
 **
 ** Function:        SendAutonomousMode
 **
 ** Description:     set/clear SetDHListenFilter
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS SendAutonomousMode(jint state, uint8_t num) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t autonomos_cmd_buff[] = {0x2F, 0x00, 0x01, 0x00};
  uint8_t core_standby = 0x0;

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  if (state == NFA_SCREEN_STATE_OFF_UNLOCKED ||
      state == NFA_SCREEN_STATE_OFF_LOCKED) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Set Screen OFF");
    /*Standby mode is automatically set with Autonomous mode
     * Value of core_standby will not be considering when state is in SCREEN_OFF
     * Mode*/
    autonomos_cmd_buff[3] = 0x02;
  } else if (state == NFA_SCREEN_STATE_ON_UNLOCKED) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Screen ON-Unlocked");
    core_standby = num;
    autonomos_cmd_buff[3] = 0x00 | core_standby;
  } else if (state == NFA_SCREEN_STATE_ON_LOCKED) {
    core_standby = num;
    autonomos_cmd_buff[3] = 0x00 | core_standby;
  } else {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Invalid screen state");
    return NFA_STATUS_FAILED;
  }
  status = NFA_SendRawVsCommand(sizeof(autonomos_cmd_buff), autonomos_cmd_buff,
                                NxpResponse_SetDhlf_Cb);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }

  status = GetCbStatus();
  return status;
}
// Factory Test Code --start
/*******************************************************************************
 **
 ** Function:        Nxp_SelfTest
 **
 ** Description:     SelfTest SWP, PRBS
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS Nxp_SelfTest(uint8_t testcase, uint8_t* param) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t swp_test[] = {0x2F, 0x3E, 0x01, 0x00};  // SWP SelfTest
  uint8_t prbs_test[] = {0x2F, 0x30, 0x06, 0x00, 0x00,
                         0x00, 0x00, 0x01, 0xFF};  // PRBS SelfTest
  uint8_t cmd_buf[9] = {
      0,
  };
  uint8_t prbs_test_stat[] = {0x2F, 0x30, 0x04, 0x00,
                              0x00, 0x01, 0xFF};  // PRBS SelfTest
  uint8_t cmd_buf_stat[7] = {
      0,
  };
  // Factory Test Code for PRBS STOP --/
  //    uint8_t prbs_stop[] ={0x2F, 0x30, 0x04, 0x53, 0x54, 0x4F, 0x50};
  //    //STOP!!    /*commented to eliminate unused variable warning*/
  uint8_t rst_cmd[] = {0x20, 0x00, 0x01, 0x00};     // CORE_RESET_CMD
  uint8_t init_cmd[] = {0x20, 0x01, 0x00};          // CORE_INIT_CMD
  uint8_t prop_ext_act_cmd[] = {0x2F, 0x02, 0x00};  // CORE_INIT_CMD
  uint8_t cmd_nfcc_standby_off[] = {0x2F, 0x00, 0x01, 0x00};
  uint8_t cmd_rf_on[] = {0x2F, 0x3D, 0x02, 0x20, 0x01};
  uint8_t cmd_rf_off[] = {0x2F, 0x3D, 0x02, 0x20, 0x00};
  uint8_t cmd_nfcc_standby_on[] = {0x2F, 0x00, 0x01, 0x01};
  uint8_t cmd_nfcc_disc_map[] = {0x21, 0x00, 0x04, 0x01, 0x04, 0x01, 0x02};
  uint8_t cmd_nfcc_deactivate[] = {0x21, 0x06, 0x01, 0x00};
  // Factory Test Code for PRBS STOP --/
  uint8_t cmd_len = 0;

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  tHAL_NFC_ENTRY* halFuncEntries = theInstance.GetHalEntryFuncs();

  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  if (nfcFL.chipType != pn547C2) {
    memset(cmd_buf, 0x00, sizeof(cmd_buf));
  } else {
    memset(cmd_buf_stat, 0x00, sizeof(cmd_buf_stat));
  }
  switch (testcase) {
    case NFC_CMD_TYPE_SWP:  // SWP Self-Test
      cmd_len = sizeof(swp_test);
      swp_test[3] = param[0];  // select channel 0x00:UICC(SWP1) 0x01:eSE(SWP2)
      if (nfcFL.chipType != pn547C2) {
        memcpy(cmd_buf, swp_test, 4);
      } else {
        memcpy(cmd_buf_stat, swp_test, 4);
      }
      break;

    case NFC_CMD_TYPE_PRBS_START:  // PRBS Test start
      if (nfcFL.chipType != pn547C2) {
        cmd_len = sizeof(prbs_test);
        // Technology to stream 0x00:TypeA 0x01:TypeB 0x02:TypeF
        // Bitrate                       0x00:106kbps 0x01:212kbps 0x02:424kbps
        // 0x03:848kbps
        memcpy(&prbs_test[3], param, (cmd_len - 5));
        memcpy(cmd_buf, prbs_test, cmd_len);
      } else {
        cmd_len = sizeof(prbs_test_stat);
        // Technology to stream 0x00:TypeA 0x01:TypeB 0x02:TypeF
        // Bitrate                       0x00:106kbps 0x01:212kbps 0x02:424kbps
        // 0x03:848kbps
        memcpy(&prbs_test_stat[3], param, (cmd_len - 5));
        memcpy(cmd_buf_stat, prbs_test_stat, cmd_len);
      }

      break;

    // Factory Test Code
    case NFC_CMD_TYPE_PRBS_STOP:  // step1. PRBS Test stop : VEN RESET
      halFuncEntries->power_cycle();
      return NFCSTATUS_SUCCESS;
      break;

    case NFC_CMD_TYPE_CORE_RESET:  // step2. PRBS Test stop : CORE RESET
      cmd_len = sizeof(rst_cmd);
      if (nfcFL.chipType != pn547C2) {
        memcpy(cmd_buf, rst_cmd, 4);
      } else {
        memcpy(cmd_buf_stat, rst_cmd, 4);
      }
      break;

    case NFC_CMD_TYPE_CORE_INIT:  // step3. PRBS Test stop : CORE_INIT
      cmd_len = sizeof(init_cmd);
      if (nfcFL.chipType != pn547C2) {
        memcpy(cmd_buf, init_cmd, cmd_len);
      } else {
        memcpy(cmd_buf_stat, init_cmd, cmd_len);
      }
      break;
    // Factory Test Code

    case NFC_CMD_TYPE_ACT_PROP_EXTN:  // step5. : NXP_ACT_PROP_EXTN
      cmd_len = sizeof(prop_ext_act_cmd);
      if (nfcFL.chipType != pn547C2) {
        memcpy(cmd_buf, prop_ext_act_cmd, 3);
      } else {
        memcpy(cmd_buf_stat, prop_ext_act_cmd, 3);
      }
      break;
    case NFC_CMD_TYPE_RF_ON:
      cmd_len = sizeof(cmd_rf_on);
      memcpy(cmd_buf, cmd_rf_on, cmd_len);
      break;

    case NFC_CMD_TYPE_RF_OFF:
      cmd_len = sizeof(cmd_rf_off);
      memcpy(cmd_buf, cmd_rf_off, cmd_len);
      break;

    case NFC_CMD_TYPE_DISC_MAP:
      cmd_len = sizeof(cmd_nfcc_disc_map);
      memcpy(cmd_buf, cmd_nfcc_disc_map, cmd_len);
      break;

    case NFC_CMD_TYPE_DEACTIVATE:
      cmd_len = sizeof(cmd_nfcc_deactivate);
      memcpy(cmd_buf, cmd_nfcc_deactivate, cmd_len);
      break;

    case NFC_CMD_TYPE_NFCC_STANDBY_ON:
      cmd_len = sizeof(cmd_nfcc_standby_on);
      memcpy(cmd_buf, cmd_nfcc_standby_on, cmd_len);
      break;

    case NFC_CMD_TYPE_NFCC_STANDBY_OFF:
      cmd_len = sizeof(cmd_nfcc_standby_off);
      memcpy(cmd_buf, cmd_nfcc_standby_off, cmd_len);
      break;

    default:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NXP_SelfTest Invalid Parameter!!");
      return status;
  }

  if (nfcFL.chipType != pn547C2) {
    status = NFA_SendRawVsCommand(cmd_len, cmd_buf, NxpResponse_SetDhlf_Cb);
  } else {
    status =
        NFA_SendRawVsCommand(cmd_len, cmd_buf_stat, NxpResponse_SetDhlf_Cb);
  }

  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }

  status = GetCbStatus();
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: exit status = 0x%02X", __func__, status);
  return status;
}
// Factory Test Code --end

/*******************************************************************************
 **
 ** Function:        SetVenConfigValue
 **
 ** Description:     setting the Ven Config Value
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS SetVenConfigValue(jint nfcMode) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t cmd_buf[] = {0x20, 0x02, 0x05, 0x01, 0xA0, 0x07, 0x01, 0x03};
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  if (nfcMode == NFC_MODE_OFF) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("Setting the VEN_CFG to 2, Disable ESE events");
    cmd_buf[7] = 0x02;
  } else if (nfcMode == NFC_MODE_ON) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("Setting the VEN_CFG to 3, Make ");
    cmd_buf[7] = 0x03;
  } else {
    LOG(ERROR) << StringPrintf("Wrong VEN_CFG Value");
    return status;
  }
  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  status = NFA_SendRawVsCommand(sizeof(cmd_buf), cmd_buf,
                                NxpResponse_SetVenConfig_Cb);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }
  status = GetCbStatus();
  return status;
}

static void NxpResponse_GetNumNFCEEValueCb(uint8_t event, uint16_t param_len,
                                           uint8_t* p_param) {
  uint8_t cfg_param_offset = 0x05;
  swp_getconfig_status = SWP_DEFAULT;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "NxpResponse_GetNumNFCEEValueCb length data = 0x%x status = 0x%x",
      param_len, p_param[3]);

  if (p_param != NULL && param_len > 0x00 && p_param[3] == NFA_STATUS_OK &&
      p_param[2] > 0x00) {
    while (cfg_param_offset < param_len) {
      if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH &&
          (p_param[5] == 0xA0 && p_param[6] == 0xEC)) {
        sSelectedUicc = (p_param[8] & 0x0F);
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("Selected Uicc:%d", sSelectedUicc);
      }

      if (p_param[cfg_param_offset] == NXP_NFC_SET_CONFIG_PARAM_EXT &&
          p_param[cfg_param_offset + 1] == NXP_NFC_PARAM_ID_SWP1) {
        if (p_param[cfg_param_offset + 3] != NXP_FEATURE_DISABLED) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("SWP1 Interface is enabled");
          swp_getconfig_status |= SWP1_UICC1;
          gActualSeCount++;
        }
      } else if (p_param[cfg_param_offset] == NXP_NFC_SET_CONFIG_PARAM_EXT &&
                 p_param[cfg_param_offset + 1] == NXP_NFC_PARAM_ID_SWP2) {
        if (p_param[cfg_param_offset + 3] != NXP_FEATURE_DISABLED) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("SWP2 Interface is enabled");
          swp_getconfig_status |= SWP2_ESE;
          gActualSeCount++;
        }
      } else if (p_param[cfg_param_offset] == NXP_NFC_SET_CONFIG_PARAM_EXT &&
                 p_param[cfg_param_offset + 1] == NXP_NFC_PARAM_ID_SWP1A) {
        if (p_param[cfg_param_offset + 3] != NXP_FEATURE_DISABLED) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("SWP1A Interface is enabled");
          swp_getconfig_status |= SWP1A_UICC2;
          gActualSeCount++;
        }
      }
      cfg_param_offset += 0x04;
    }
  } else {
    /* for fail case assign max no of smx */
    gActualSeCount = 3;
  }
  SetCbStatus(NFA_STATUS_OK);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  gnxpfeature_conf.NxpFeatureConfigEvt.notifyOne();
}

/*******************************************************************************
 **
 ** Function:        GetNumNFCEEConfigured
 **
 ** Description:     Get the no of NFCEE configured
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS GetNumNFCEEConfigured(void) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  gActualSeCount = 1; /* default HCI present */
  uint8_t cmd_buf[255] = {0x20,
                          0x03,
                          0x05,
                          0x02,
                          NXP_NFC_SET_CONFIG_PARAM_EXT,
                          NXP_NFC_PARAM_ID_SWP1,
                          NXP_NFC_SET_CONFIG_PARAM_EXT,
                          NXP_NFC_PARAM_ID_SWP2};
  uint8_t cmd_buf_len = 0x08;
  uint8_t num_config_params = 0x02;
  uint8_t config_param_len = 0x05;
  uint8_t buf_offset = 0x08;
  if (nfcFL.nfccFL._NFCC_DYNAMIC_DUAL_UICC) {
    cmd_buf[buf_offset++] = NXP_NFC_SET_CONFIG_PARAM_EXT;
    cmd_buf[buf_offset++] = NXP_NFC_PARAM_ID_SWP1A;
    cmd_buf_len += 0x02;
    num_config_params++;
    config_param_len += 0x02;
  }
  cmd_buf[2] = config_param_len;
  cmd_buf[3] = num_config_params;

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

  if (NFA_GetNCIVersion() == NCI_VERSION_2_0) gActualSeCount = 0;

  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  status = NFA_SendRawVsCommand(cmd_buf_len, cmd_buf,
                                NxpResponse_GetNumNFCEEValueCb);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }
  status = GetCbStatus();
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s : gActualSeCount = %d", __func__, gActualSeCount);
  return status;
}

#if (NFC_NXP_HFO_SETTINGS == true)
/*******************************************************************************
 **
 ** Function:        SetHfoConfigValue
 **
 ** Description:    Configuring the HFO clock in case of phone power off
 **                       to make CE works in phone off.
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS SetHfoConfigValue(void) {
  /* set 4 RF registers for phone off
   *
  # A0, 0D, 06, 06, 83, 10, 10, 40, 00 RF_CLIF_CFG_TARGET CLIF_DPLL_GEAR_REG
  # A0, 0D, 06, 06, 82, 13, 14, 17, 00 RF_CLIF_CFG_TARGET CLIF_DPLL_INIT_REG
  # A0, 0D, 06, 06, 84, AA, 85, 00, 00 RF_CLIF_CFG_TARGET
  CLIF_DPLL_INIT_FREQ_REG
  # A0, 0D, 06, 06, 81, 63, 02, 00, 00 RF_CLIF_CFG_TARGET CLIF_DPLL_CONTROL_REG
  */
  /* default value of four registers in nxp-ALMSL.conf need to set in full power
  on
  # A0, 0D, 06, 06, 83, 55, 2A, 04, 00 RF_CLIF_CFG_TARGET CLIF_DPLL_GEAR_REG
  # A0, 0D, 06, 06, 82, 33, 14, 17, 00 RF_CLIF_CFG_TARGET CLIF_DPLL_INIT_REG
  # A0, 0D, 06, 06, 84, AA, 85, 00, 80 RF_CLIF_CFG_TARGET
  CLIF_DPLL_INIT_FREQ_REG
  # A0, 0D, 06, 06, 81, 63, 00, 00, 00 RF_CLIF_CFG_TARGET CLIF_DPLL_CONTROL_REG

  */
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t cmd_buf[] = {0x20, 0x02, 0x29, 0x05, 0xA0, 0x03, 0x01, 0x06, 0xA0,
                       0x0D, 0x06, 0x06, 0x83, 0x10, 0x10, 0x40, 0x00, 0xA0,
                       0x0D, 0x06, 0x06, 0x82, 0x13, 0x14, 0x17, 0x00, 0xA0,
                       0x0D, 0x06, 0x06, 0x84, 0xAA, 0x85, 0x00, 0x00, 0xA0,
                       0x0D, 0x06, 0x06, 0x81, 0x63, 0x02, 0x00, 0x00};
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  status = NFA_SendRawVsCommand(sizeof(cmd_buf), cmd_buf,
                                NxpResponse_SetVenConfig_Cb);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }
  status = GetCbStatus();
  if (NFA_STATUS_OK == status) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: HFO Settinng Success", __func__);
    // TBD write value in temp file in /data/nfc
    // At next boot hal will read this file and re-apply the
    // Default Clock setting
  }
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return status;
}
#endif

/*******************************************************************************
 **
 ** Function:        ResetEseSession
 **
 ** Description:     Resets the Ese session identity to FF
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS ResetEseSession() {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  static uint8_t cmd_buf[] = {0x20, 0x02, 0x0C, 0x01, 0xA0, 0xEB, 0x08, 0xFF,
                              0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

  SetCbStatus(NFA_STATUS_FAILED);
  {
    SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
    status = NFA_SendRawVsCommand(sizeof(cmd_buf), cmd_buf, NxpResponse_Cb);
    if (status == NFA_STATUS_OK) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
      gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
    } else {
      LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
    }
  }
  status = GetCbStatus();

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return status;
}

/*******************************************************************************
 **
 ** Function:        enableSWPInterface
 **
 ** Description:     Enables SWP1 and SWP1A interfaces
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS enableSWPInterface() {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  static uint8_t get_eeprom_data[6] = {0x20, 0x03, 0x03, 0x01, 0xA0, 0x14};
  uint8_t dual_uicc_cmd_buf[] = {0x20, 0x02, 0x09, 0x02, 0xA0, 0xEC,
                                 0x01, 0x00, 0xA0, 0xD4, 0x01, 0x00};
  uint8_t cmd_buf[] = {0x20, 0x02, 0x05, 0x01, 0xA0, 0xEC, 0x01, 0x00};
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

  status = NxpNfc_Write_Cmd(sizeof(get_eeprom_data), get_eeprom_data,
                            NxpResponse_Cb);
  if ((status == NFA_STATUS_OK) && (gnxpfeature_conf.rsp_len > 8)) {
    if (gnxpfeature_conf.rsp_data[8] == 0x01 &&
        !(swp_getconfig_status & SWP1_UICC1))  // SWP status read
    {
      if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) {
        dual_uicc_cmd_buf[7] = 0x01;
      } else {
        cmd_buf[7] = 0x01;
      }
    }
    if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) {
      if (gnxpfeature_conf.rsp_data[9] == 0x01 &&
          !(swp_getconfig_status & SWP1A_UICC2))  // SWP1A status read
      {
        dual_uicc_cmd_buf[11] = 0x01;
      }
    }
    if (((!nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) &&
         (cmd_buf[7] == 0x00)) ||
        ((nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) &&
         (dual_uicc_cmd_buf[7] == 0x00 && dual_uicc_cmd_buf[11] == 0x00))) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: No mismatch in UICC SWP and configuration set", __func__);
      status = NFA_STATUS_FAILED;
    } else {
      SetCbStatus(NFA_STATUS_FAILED);
      {
        SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
        if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) {
          status = NFA_SendRawVsCommand(sizeof(dual_uicc_cmd_buf),
                                        dual_uicc_cmd_buf, NxpResponse_Cb);
        } else {
          status =
              NFA_SendRawVsCommand(sizeof(cmd_buf), cmd_buf, NxpResponse_Cb);
        }

        if (status == NFA_STATUS_OK) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
          gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
        } else {
          LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand",
                                     __func__);
        }
      }
      status = GetCbStatus();
      if (NFA_STATUS_OK == status) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: GetCbStatus():%d", __func__, status);
      }
    }
  }

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return status;
}

/*******************************************************************************
 **
 ** Function:        SetUICC_SWPBitRate()
 **
 ** Description:     Get All UICC Parameters and set SWP bit rate
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS SetUICC_SWPBitRate(bool isMifareSupported) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t cmd_buf[] = {0x20, 0x02, 0x05, 0x01, 0xA0, 0xC0, 0x01, 0x03};

  if (isMifareSupported) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("Setting the SWP_BITRATE_INT1 to 0x06 (1250 kb/s)");
    cmd_buf[7] = 0x06;
  } else {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("Setting the SWP_BITRATE_INT1 to 0x04 (910 kb/s)");
    cmd_buf[7] = 0x04;
  }

  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  status = NFA_SendRawVsCommand(sizeof(cmd_buf), cmd_buf,
                                NxpResponse_SetSWPBitRate_Cb);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }
  status = GetCbStatus();
  return status;
}
/*******************************************************************************
 **
 ** Function:        NxpNfc_Write_Cmd()
 **
 ** Description:     Writes the command to NFCC
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS NxpNfc_Write_Cmd(uint8_t retlen, uint8_t* buffer,
                             tNXP_RSP_CBACK* p_cback) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  status = NFA_SendRawVsCommand(retlen, buffer, p_cback);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }
  status = GetCbStatus();
  return status;
}
void start_timer_msec(struct timeval* start_tv) {
  gettimeofday(start_tv, NULL);
}

long stop_timer_getdifference_msec(struct timeval* start_tv,
                                   struct timeval* stop_tv) {
  gettimeofday(stop_tv, NULL);
  return ((long)(stop_tv->tv_sec - start_tv->tv_sec) * 1000L +
          (long)(stop_tv->tv_usec - start_tv->tv_usec) / 1000L);
}

#endif
/*******************************************************************************
 **
 ** Function:        Set_EERegisterValue()
 **
 ** Description:     Prepare NCI_SET_CONFIG command with configuration parameter
 **                  value, masking bits and bit value
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS Set_EERegisterValue(uint16_t RegAddr, uint8_t bitVal) {
  if (!nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s STAT_DUAL_UICC_EXT_SWITCH not available. Returning", __func__);
    return NFC_STATUS_FAILED;
  }
  tNFA_STATUS status = NFC_STATUS_FAILED;
  uint8_t swp1conf[] = {0x20, 0x02, 0x05, 0x01, 0x00, 0x00, 0x01, 0x00};
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("Enter: Prepare SWP1 configurations");
  swp1conf[4] = (uint8_t)((RegAddr & 0xFF00) >> 8);
  swp1conf[5] = (uint8_t)(RegAddr & 0x00FF);
  swp1conf[7] = (uint8_t)(0xFF & bitVal);
  // swp1conf[7] = 0x01;
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("Exit: Prepare SWP1 configurations");

  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);

  status = NFA_SendRawVsCommand(sizeof(swp1conf), swp1conf,
                                NxpResponse_SwitchUICC_Cb);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }
  status = GetCbStatus();
  return status;
}

#if (NXP_EXTNS == TRUE)
/*******************************************************************************
 **
 ** Function:        NxpNfc_Write_Cmd()
 **
 ** Description:     Writes the command to NFCC
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS NxpNfc_Write_Cmd_Common(uint8_t retlen, uint8_t* buffer) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  status = NFA_SendRawVsCommand(retlen, buffer, NxpResponse_Cb);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }
  status = GetCbStatus();
  return status;
}

/*******************************************************************************
 **
 ** Function:        NxpNfc_Send_CoreResetInit_Cmd()
 **
 ** Description:     Sends Core Reset and Init command to NFCC
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS NxpNfc_Send_CoreResetInit_Cmd(void) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t* p;

  status = (tNFA_STATUS)NFA_Send_Core_Reset();

  if (status == NFA_STATUS_OK) {
    NFA_Send_Core_Init(&p);
  }
  return status;
}

/*******************************************************************************
 **
 ** Function:        NxpNfcUpdateEeprom()
 **
 ** Description:     Sends extended nxp set config parameter
 **
 ** Returns:         NFA_STATUS_FAILED/NFA_STATUS_OK
 **
 *******************************************************************************/
tNFA_STATUS NxpNfcUpdateEeprom(uint8_t* param, uint8_t len, uint8_t* val) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t* cmdBuf = NULL;
  uint8_t setCfgCmdLen = 0;  // Memory field len 1bytes
  uint8_t setCfgCmdHdr[7] = {
      0x20,     0x02,  // set_cfg header
      0x00,            // len of following value
      0x01,            // Num Param
      param[0],        // First byte of Address
      param[1],        // Second byte of Address
      len              // Data len
  };
  setCfgCmdLen = sizeof(setCfgCmdHdr) + len;
  setCfgCmdHdr[2] = setCfgCmdLen - SETCONFIGLENPOS;
  cmdBuf = (uint8_t*)malloc(setCfgCmdLen);
  if (cmdBuf == NULL) {
    LOG(ERROR) << StringPrintf("memory allocation failed");
    return status;
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("setCfgCmdLen=%u", setCfgCmdLen);
  memset(cmdBuf, 0, setCfgCmdLen);
  memcpy(cmdBuf, setCfgCmdHdr, sizeof(setCfgCmdHdr));
  memcpy(cmdBuf + sizeof(setCfgCmdHdr), val, len);

  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  status = NFA_SendRawVsCommand(setCfgCmdLen, cmdBuf, NxpResponse_Cb);
  if (status == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("Success NFA_SendRawVsCommand");
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(
        2 * ONE_SECOND_MS); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("Failed NFA_SendRawVsCommand");
  }

  if (cmdBuf) {
    free(cmdBuf);
    cmdBuf = NULL;
  }

  status = GetCbStatus();
  return status;
}

#endif
} /*namespace android*/
