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

/******************************************************************************
 *
 *  Copyright 2019 NXP
 *
 *  NOT A CONTRIBUTION
 *
 ******************************************************************************/
#include "NfcSelfTest.h"

/* Declaration of the singleTone class(static member) */
NfcSelfTest NfcSelfTest::sSelfTestMgr;

nxp_selftest_data gselfTestData;
extern bool nfc_debug_enabled;

using android::base::StringPrintf;

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
    default:
      DLOG_IF(ERROR, nfc_debug_enabled)
          << StringPrintf("Self-test type invalid/not supported");
      SelfTestType = TEST_TYPE_NONE;
      break;
  }
  SelfTestType = TEST_TYPE_NONE;
  return status;
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
 ** Function:        NxpResponse_SelfTest_Cb
 **
 ** Description:     Store the value of RF_TRANSITION_CFG and notify the
 **                  Nxp_doResonantFrequency along with updated status
 **
 ** Returns:         void
 *******************************************************************************/
void NxpResponse_SelfTest_Cb(uint8_t event, uint16_t param_len,
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
