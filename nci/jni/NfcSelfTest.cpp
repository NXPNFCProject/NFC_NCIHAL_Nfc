/******************************************************************************
 *
 *  Copyright 2019 NXP
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

#include "NfcSelfTest.h"

/* Declaration of the singleTone class(static member) */
NfcSelfTest NfcSelfTest::sSelfTestMgr;

nxp_selftest_data gselfTestData;
extern bool nfc_debug_enabled;
extern SyncEvent sNfaEnableDisablePollingEvent;
extern SyncEvent sNfaSetConfigEvent;

using android::base::StringPrintf;
using namespace android;

namespace android {
extern bool isDiscoveryStarted();
extern void startRfDiscovery(bool isStart);
}  // namespace android
/*******************************************************************************
 ** Set the global Self Test status to @param value
 ** @param status- Status to be set
 ** @return None
 *******************************************************************************/
void SetSelfTestCbStatus(tNFA_STATUS status) { gselfTestData.wstatus = status; }

/*******************************************************************************
 ** Get the global Self Test status
 ** @return tNFA_STATUS
 *******************************************************************************/
tNFA_STATUS GetSelfTestCbStatus(void) { return gselfTestData.wstatus; }

/*******************************************************************************
 ** Initialize member variables.
 ** @return None
 *******************************************************************************/
NfcSelfTest::NfcSelfTest() : SelfTestType(TEST_TYPE_NONE) {}

/*******************************************************************************
 ** Release all resources.
 ** @return None
 *******************************************************************************/
NfcSelfTest::~NfcSelfTest() {}

/*******************************************************************************
 ** Get the NfcSeManager singleton object.
 ** @return NfcSeManager object.
 *******************************************************************************/
NfcSelfTest& NfcSelfTest::GetInstance() { return sSelfTestMgr; }

/*******************************************************************************
 ** Function:        NxpResponse_SelfTest_Cb
 **
 ** Description:     Store the value of RF_TRANSITION_CFG and notify the
 **                  Nxp_doResonantFrequency along with updated status
 **
 ** Returns:         void
 *******************************************************************************/
static void NxpResponse_SelfTest_Cb(uint8_t event, uint16_t param_len,
                                    uint8_t* p_param) {
  (void)event;
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s Received length data = 0x%x status = 0x%x", __func__,
                      param_len, p_param[3]);

  if (NFA_STATUS_OK == p_param[3]) {
    if (gselfTestData.copyData) {
      memmove((void*)gselfTestData.prestorerftxcfg, (void*)(p_param + 5),
              (size_t)p_param[4]);
      gselfTestData.copyData = false;
    }
    SetSelfTestCbStatus(NFA_STATUS_OK);
  } else {
    SetSelfTestCbStatus(NFA_STATUS_FAILED);
  }

  SyncEventGuard guard(gselfTestData.NxpSelfTestEvt);
  gselfTestData.NxpSelfTestEvt.notifyOne();
}

/*******************************************************************************
 ** Provides the command buffer for the given command type
 ** @param CmdBuf- for the given command type
 **        aType - is the command type
 ** @return length of the command buffer
 *******************************************************************************/
uint8_t NfcSelfTest::GetCmdBuffer(uint8_t* aCmdBuf, uint8_t aType) {
  uint8_t cmdLen = 0;
  static uint8_t rf_tx_cfg_restore[6][4] = {{0}, {0}, {0}, {0}, {0}, {0}};
  /* TID     :    0x60 0x60 0x60 0x12 0x12 0x12
   * CLIF Reg:    0x4E 0x50 0x4F 0x4E 0x4F 0x50
   */

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Command type is %d", aType);
  switch (aType) {
    case CMD_TYPE_CORE_RESET: {
      uint8_t CMD_CORE_RESET[] = {0x20, 0x00, 0x01, 0x00};
      cmdLen = sizeof(CMD_CORE_RESET);
      memcpy(aCmdBuf, CMD_CORE_RESET, cmdLen);
      break;
    }
    case CMD_TYPE_CORE_INIT: {
      uint8_t CMD_CORE_INIT[] = {0x20, 0x01, 0x02, 0x00, 0x00};
      cmdLen = sizeof(CMD_CORE_INIT);
      memcpy(aCmdBuf, CMD_CORE_INIT, cmdLen);
      break;
    }
    case CMD_TYPE_NXP_PROP_EXT: {
      uint8_t CMD_NXP_PROP_EXT[] = {0x2F, 0x02, 0x00};
      cmdLen = sizeof(CMD_NXP_PROP_EXT);
      memcpy(aCmdBuf, CMD_NXP_PROP_EXT, cmdLen);
      break;
    }
    case CMD_TYPE_NFCC_STANDBY_ON: {
      uint8_t CMD_NFCC_STANDBY_ON[] = {0x2F, 0x00, 0x01, 0x01};
      cmdLen = sizeof(CMD_NFCC_STANDBY_ON);
      memcpy(aCmdBuf, CMD_NFCC_STANDBY_ON, cmdLen);
      break;
    }
    case CMD_TYPE_NFCC_STANDBY_OFF: {
      uint8_t CMD_NFCC_STANDBY_OFF[] = {0x2F, 0x00, 0x01, 0x00};
      cmdLen = sizeof(CMD_NFCC_STANDBY_OFF);
      memcpy(aCmdBuf, CMD_NFCC_STANDBY_OFF, cmdLen);
      break;
    }
    case CMD_TYPE_NFCC_DISC_MAP: {
      uint8_t CMD_NFCC_DISC_MAP[] = {0x21, 0x00, 0x04, 0x01, 0x04, 0x01, 0x02};
      cmdLen = sizeof(CMD_NFCC_DISC_MAP);
      memcpy(aCmdBuf, CMD_NFCC_DISC_MAP, cmdLen);
      break;
    }
    case CMD_TYPE_NFCC_DEACTIVATE: {
      uint8_t CMD_NFCC_DEACTIVATE[] = {0x21, 0x06, 0x01, 0x00};
      cmdLen = sizeof(CMD_NFCC_DEACTIVATE);
      memcpy(aCmdBuf, CMD_NFCC_DEACTIVATE, cmdLen);
      break;
    }
    case CMD_TYPE_RF_ON: {
      uint8_t CMD_RF_ON[] = {0x2F, 0x3D, 0x02, 0x20, 0x01};
      cmdLen = sizeof(CMD_RF_ON);
      memcpy(aCmdBuf, CMD_RF_ON, cmdLen);
      break;
    }
    case CMD_TYPE_RF_OFF: {
      uint8_t CMD_RF_OFF[] = {0x2F, 0x3D, 0x02, 0x20, 0x00};
      cmdLen = sizeof(CMD_RF_OFF);
      memcpy(aCmdBuf, CMD_RF_OFF, cmdLen);
      break;
    }
    case CMD_TYPE_CORE_GET_CONFIG_RFTXCFG0: {
      uint8_t CMD_CORE_GET_CONFIG_RFTXCFG0[] = {0x2F, 0x14, 0x02, 0x60, 0x4E};
      gselfTestData.prestorerftxcfg = rf_tx_cfg_restore[0];
      cmdLen = sizeof(CMD_CORE_GET_CONFIG_RFTXCFG0);
      memcpy(aCmdBuf, CMD_CORE_GET_CONFIG_RFTXCFG0, cmdLen);
      break;
    }
    case CMD_TYPE_CORE_GET_CONFIG_RFTXCFG1: {
      uint8_t CMD_CORE_GET_CONFIG_RFTXCFG1[] = {0x2F, 0x14, 0x02, 0x60, 0x50};
      ;
      gselfTestData.prestorerftxcfg = rf_tx_cfg_restore[1];
      cmdLen = sizeof(CMD_CORE_GET_CONFIG_RFTXCFG1);
      memcpy(aCmdBuf, CMD_CORE_GET_CONFIG_RFTXCFG1, cmdLen);
      break;
    }
    case CMD_TYPE_CORE_GET_CONFIG_RFTXCFG2: {
      uint8_t CMD_CORE_GET_CONFIG_RFTXCFG2[] = {0x2F, 0x14, 0x02, 0x60, 0x4F};
      gselfTestData.prestorerftxcfg = rf_tx_cfg_restore[2];
      cmdLen = sizeof(CMD_CORE_GET_CONFIG_RFTXCFG2);
      memcpy(aCmdBuf, CMD_CORE_GET_CONFIG_RFTXCFG2, cmdLen);
      break;
    }
    case CMD_TYPE_CORE_GET_CONFIG_RFTXCFG3: {
      uint8_t CMD_CORE_GET_CONFIG_RFTXCFG3[] = {0x2F, 0x14, 0x02, 0x12, 0x4E};
      ;
      gselfTestData.prestorerftxcfg = rf_tx_cfg_restore[3];
      cmdLen = sizeof(CMD_CORE_GET_CONFIG_RFTXCFG3);
      memcpy(aCmdBuf, CMD_CORE_GET_CONFIG_RFTXCFG3, cmdLen);
      break;
    }
    case CMD_TYPE_CORE_GET_CONFIG_RFTXCFG4: {
      uint8_t CMD_CORE_GET_CONFIG_RFTXCFG4[] = {0x2F, 0x14, 0x02, 0x12, 0x4F};
      gselfTestData.prestorerftxcfg = rf_tx_cfg_restore[4];
      cmdLen = sizeof(CMD_CORE_GET_CONFIG_RFTXCFG4);
      memcpy(aCmdBuf, CMD_CORE_GET_CONFIG_RFTXCFG4, cmdLen);
      break;
    }
    case CMD_TYPE_CORE_GET_CONFIG_RFTXCFG5: {
      uint8_t CMD_CORE_GET_CONFIG_RFTXCFG5[] = {0x2F, 0x14, 0x02, 0x12, 0x50};
      gselfTestData.prestorerftxcfg = rf_tx_cfg_restore[5];
      cmdLen = sizeof(CMD_CORE_GET_CONFIG_RFTXCFG5);
      memcpy(aCmdBuf, CMD_CORE_GET_CONFIG_RFTXCFG5, cmdLen);
      break;
    }
    case CMD_TYPE_CORE_SET_CONFIG_RFTXCFG: {
      uint8_t CMD_CORE_SET_CONFIG_RFTXCFG[] = {
          0x20, 0x02, 0x37, 0x06, 0xA0, 0x0D, 0x06, 0x60, 0x4E, 0x00,
          0x00, 0x00, 0x00, 0xA0, 0x0D, 0x06, 0x60, 0x50, 0x00, 0x00,
          0x00, 0x00, 0xA0, 0x0D, 0x06, 0x60, 0x4F, 0x00, 0x00, 0x00,
          0x00, 0xA0, 0x0D, 0x06, 0x12, 0x4E, 0x00, 0x00, 0x00, 0x00,
          0xA0, 0x0D, 0x06, 0x12, 0x4F, 0x00, 0x00, 0x00, 0x00, 0xA0,
          0x0D, 0x06, 0x12, 0x50, 0x00, 0x00, 0x00, 0x00};

      if (gselfTestData.isStored && !gselfTestData.fSetResFreq) {
        /* Copy stored RF_TRANSITION_CFG */
        gselfTestData.isStored = false;
        if (gselfTestData.prestorerftxcfg == NULL)
          return cmdLen;  // Failure case
        uint8_t* temp = CMD_CORE_SET_CONFIG_RFTXCFG;
        for (int i = 0x00; i < RF_TXCFG_MAX_NUM_FLAGS; i++) {
          temp += MAX_RF_TX_CFG_OFFSET_VALUE;
          memmove((void*)(temp), (void*)rf_tx_cfg_restore[i],
                  sizeof(rf_tx_cfg_restore[i]));
        }
        gselfTestData.prestorerftxcfg = NULL;
        memset(rf_tx_cfg_restore, 0x00, sizeof(rf_tx_cfg_restore));
      }
      cmdLen = sizeof(CMD_CORE_SET_CONFIG_RFTXCFG);
      memcpy(aCmdBuf, CMD_CORE_SET_CONFIG_RFTXCFG, cmdLen);
      break;
    }
    case CMD_TYPE_NXP_PROP_FLASH_TO_ROM: {
      uint8_t CMD_CORE_GET_CONFIG_RFTXCFG5[] = {0x2F, 0x21, 0x00};
      cmdLen = sizeof(CMD_CORE_GET_CONFIG_RFTXCFG5);
      memcpy(aCmdBuf, CMD_CORE_GET_CONFIG_RFTXCFG5, cmdLen);
      break;
    }
    default:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Command not supported");
      break;
  }
  return cmdLen;
}

/*******************************************************************************
 ** Executes NFC self-test requests from service.
 ** @param  aType denotes type of self-test
 ** @return status SUCCESS or FAILED.
 *******************************************************************************/
tNFA_STATUS NfcSelfTest::doNfccSelfTest(int aType) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Self-Test Type %d", aType);
  SelfTestType = aType;

  switch (aType) {
    case TEST_TYPE_RESTORE_RFTXCFG:
      status = PerformResonantFreq(false);
      break;
    case TEST_TYPE_SET_RFTXCFG_RESONANT_FREQ:
      status = PerformResonantFreq(true);
      break;
    case TEST_TYPE_RF_ON:
      status = PerformRFTest(true);
      break;
    case TEST_TYPE_RF_OFF:
      status = PerformRFTest(false);
      break;
    case TEST_TYPE_TRANSAC_A:
      status = PerformTransacAB(TEST_TYPE_TRANSAC_A);
      break;
    case TEST_TYPE_TRANSAC_B:
      status = PerformTransacAB(TEST_TYPE_TRANSAC_B);
      break;
    default:
      DLOG_IF(ERROR, nfc_debug_enabled)
          << StringPrintf("Self-test type invalid/not supported");
      SelfTestType = TEST_TYPE_NONE;
      break;
  }
  SelfTestType = TEST_TYPE_NONE;
  return status;
}

tNFA_STATUS NfcSelfTest::PerformRFTest(bool on) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t* pp = NULL;
  uint8_t RFTestCmdSeq[5] = {
      CMD_TYPE_CORE_RESET,
      CMD_TYPE_CORE_INIT,
      CMD_TYPE_NXP_PROP_EXT,
  };
  /* Stop RF Discovery */
  if (isDiscoveryStarted()) startRfDiscovery(false);

  pp = RFTestCmdSeq + 2 + 1;
  if (on) {
    *pp++ = CMD_TYPE_NFCC_STANDBY_OFF;
    *pp = CMD_TYPE_RF_ON;
  } else {
    *pp++ = CMD_TYPE_RF_OFF;
    *pp = CMD_TYPE_NFCC_STANDBY_ON;
  }

  status = executeCmdSeq(RFTestCmdSeq, sizeof(RFTestCmdSeq));
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("status=%u", status);
  return status;
}

tNFA_STATUS NfcSelfTest::PerformTransacAB(uint8_t aType) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t tech_mask = 0;
  uint8_t readerProfileSelCfg[] = {0x20, 0x02, 0x09, 0x02, 0xA0, 0x3F,
                                   0x01, 0x03, 0xA0, 0x44, 0x01, 0x63};
  uint8_t val85[] = {0x01};
  uint8_t NFCInitCmdSeq[3] = {CMD_TYPE_CORE_RESET, CMD_TYPE_CORE_INIT,
                              CMD_TYPE_NXP_PROP_EXT};

  /* Stop RF Discovery */
  if (isDiscoveryStarted()) startRfDiscovery(false);

  {
    SyncEventGuard gaurd(sNfaEnableDisablePollingEvent);
    NFA_DisableListening();
    status = NFA_DisablePolling();
    if (status == NFA_STATUS_OK)
      sNfaEnableDisablePollingEvent.wait(2 * ONE_SECOND_MS);
  }

  if (aType == TEST_TYPE_TRANSAC_A) {
    tech_mask = NFA_TECHNOLOGY_MASK_A;
  } else {
    readerProfileSelCfg[11] = 0x43;
    tech_mask = NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B;
  }

  status = executeCmdSeq(NFCInitCmdSeq, sizeof(NFCInitCmdSeq));
  if (status == NFA_STATUS_OK) {
    {
      SyncEventGuard guard(sNfaSetConfigEvent);
      status = NFA_SetConfig(0x85, 1, val85);
      if (status == NFA_STATUS_OK) sNfaSetConfigEvent.wait(2 * ONE_SECOND_MS);
    }
    {
      SyncEventGuard guard(gselfTestData.NxpSelfTestEvt);
      status =
          NFA_SendRawVsCommand(sizeof(readerProfileSelCfg), readerProfileSelCfg,
                               NxpResponse_SelfTest_Cb);
      if (status == NFA_STATUS_OK)
        gselfTestData.NxpSelfTestEvt.wait(2 * ONE_SECOND_MS);
    }
  } else {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("failed in to reset and init NFCC");
  }

  if (status == NFA_STATUS_OK) {
    uint8_t discMapCmd[] = {CMD_TYPE_NFCC_DISC_MAP};
    status = executeCmdSeq(discMapCmd, sizeof(discMapCmd));
  }

  if (status == NFA_STATUS_OK) {
    NFA_SetEmvCoState(TRUE);
    {
      SyncEventGuard gaurd(sNfaEnableDisablePollingEvent);
      status = NFA_EnablePolling(tech_mask);
      if (status == NFA_STATUS_OK) {
        sNfaEnableDisablePollingEvent.wait(2 * ONE_SECOND_MS);
      }
    }
    if (status == NFA_STATUS_OK) {
      startRfDiscovery(true);
      {
        SyncEventGuard gaurd(mSelfTestTransacAB);
        mSelfTestTransacAB.wait(30 * ONE_SECOND_MS);
      }
    }
  } else {
    {
      SyncEventGuard gaurd(sNfaEnableDisablePollingEvent);
      status = NFA_EnablePolling(tech_mask);
      if (status == NFA_STATUS_OK)
        sNfaEnableDisablePollingEvent.wait(2 * ONE_SECOND_MS);
    }
    startRfDiscovery(true);
  }

  NFA_SetEmvCoState(false);
  startRfDiscovery(false);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("exiting status=%u", status);
  return status;
}

void NfcSelfTest::ActivatedNtf_Cb() {
  SyncEventGuard gaurd(mSelfTestTransacAB);
  mSelfTestTransacAB.notifyOne();
}

/*******************************************************************************
 ** Executes: 1. Save the current value of the NFCC's RF_TRANSITION_CFG.
 **           2. Sets the RF_TRANSITION_CFG value to generate Resonant
 *Frequency.
 ** @param  None
 ** @return status SUCCESS or FAILED.
 *******************************************************************************/
tNFA_STATUS NfcSelfTest::setResonantFreq() {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t resFreqCmdSeq[] = {
      CMD_TYPE_CORE_GET_CONFIG_RFTXCFG0, CMD_TYPE_CORE_GET_CONFIG_RFTXCFG1,
      CMD_TYPE_CORE_GET_CONFIG_RFTXCFG2, CMD_TYPE_CORE_GET_CONFIG_RFTXCFG3,
      CMD_TYPE_CORE_GET_CONFIG_RFTXCFG4, CMD_TYPE_CORE_GET_CONFIG_RFTXCFG5,
      CMD_TYPE_CORE_SET_CONFIG_RFTXCFG,  CMD_TYPE_NXP_PROP_FLASH_TO_ROM};

  gselfTestData.fSetResFreq = true;
  status = executeCmdSeq(resFreqCmdSeq, sizeof(resFreqCmdSeq));
  if (NFA_STATUS_OK == status)
    gselfTestData.isStored =
        true; /* flag should be cleared once RFTXCFGs are restored */
  gselfTestData.fSetResFreq = false;
  return status;
}

/********************************************************************************
 ** Executes Restore the RF_TRANSITION_CFG values, if stored by the
 *setResonantFreq()
 ** @param  None
 ** @return status SUCCESS or FAILED.
 *******************************************************************************/
tNFA_STATUS NfcSelfTest::restoreRfTxCfg() {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t rfTestCmdSeq[] = {CMD_TYPE_CORE_SET_CONFIG_RFTXCFG,
                            CMD_TYPE_NXP_PROP_FLASH_TO_ROM};

  status = executeCmdSeq(rfTestCmdSeq, sizeof(rfTestCmdSeq));

  return status;
}

/*******************************************************************************
 ** Executes: Updates the RF_TRANSITION_CFG as per the command type
 ** @param  on denotes
 **         TRUE  - setResonantFreq()
 **         FALSE - restoreRfTxCfg()
 ** @return status SUCCESS or FAILED.
 *******************************************************************************/
tNFA_STATUS NfcSelfTest::PerformResonantFreq(bool on) {
  tNFA_STATUS status;

  if (on)
    status = setResonantFreq();
  else
    status = restoreRfTxCfg();

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf(" PerformResonantFreq status=%u", status);

  return status;
}

/*******************************************************************************
 ** Writes sequence of commands provided to NFCC
 ** @param *aCmdType- pointer for the list of command types
 **        aNumOfCmds- is the number of command types in the list
 ** @return status SUCCESS or FAILED.
 *******************************************************************************/
tNFA_STATUS NfcSelfTest::executeCmdSeq(uint8_t* aCmdType, uint8_t aNumOfCmds) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t count = 0, cmdLen = 0;
  uint8_t cmdBuf[NCI_MAX_CMD_BUFFER] = {0x00};
  do {
    cmdLen = GetCmdBuffer(cmdBuf, aCmdType[count]);
    if (cmdLen == 0) {
      status = NFA_STATUS_FAILED;
      DLOG_IF(ERROR, nfc_debug_enabled)
          << StringPrintf("Failed to get command buffer");
    } else {
      if (gselfTestData.fSetResFreq &&
          (CMD_TYPE_CORE_GET_CONFIG_RFTXCFG0 <= aCmdType[count] &&
           CMD_TYPE_CORE_GET_CONFIG_RFTXCFG5 >= aCmdType[count])) {
        gselfTestData.copyData = true;
      }

      SyncEventGuard guard(gselfTestData.NxpSelfTestEvt);
      status = NFA_SendRawVsCommand(cmdLen, cmdBuf, NxpResponse_SelfTest_Cb);
      if (status == NFA_STATUS_OK &&
          gselfTestData.NxpSelfTestEvt.wait(RESONANT_FREQ_CMD_WAIT)) {
        if (aCmdType[count] == CMD_TYPE_CORE_RESET) {
          usleep(1000 * 100);
        }
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Command Success");
      } else {
        SetSelfTestCbStatus(NFA_STATUS_FAILED);
        status = NFA_STATUS_FAILED; /* Response Timeout: break the loop */
        DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf("Command Failed");
      }
    }
    /*Loop Break: 1. GetCmdBuffer() failed to get command.
     *            2. NFA_SendRawVsCommand() Failed to send command
     *            3. Response timeout 4. Response STATUS_FAILED
     *            5. Command sequence is over
     *            */
  } while ((status == NFA_STATUS_OK) &&
           (NFA_STATUS_OK == GetSelfTestCbStatus()) && (++count < aNumOfCmds));
  return GetSelfTestCbStatus();
}
