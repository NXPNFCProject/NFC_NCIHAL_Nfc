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
#pragma once

#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <string.h>
#include "SyncEvent.h"
#include "data_types.h"
#include "nfa_api.h"
#include "nfa_rw_api.h"

/**************************************************
 ** MACRO declarations                           **
 *************************************************/
#define NCI_MAX_CMD_BUFFER 258
#define RF_TXCFG_MAX_NUM_FLAGS 0x06
#define RF_TXCFG_MAX_VAL_LEN 0x04
#define MAX_RF_TX_CFG 0x06
#define RF_TX_CFG_OFFSET_TID 0x07
#define MAX_RF_TX_CFG_OFFSET_VALUE 0x09
#define RESONANT_FREQ_CMD_WAIT 1000 /* 1000ms */
#define RESONANT_FREQ_DEFAULT_LISTEN_MASK \
  (NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B | NFA_TECHNOLOGY_MASK_F)
#define RESONANT_FREQ_DEFAULT_POLL_MASK                                    \
  (NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B | NFA_TECHNOLOGY_MASK_F | \
   NFA_TECHNOLOGY_MASK_V | NFA_TECHNOLOGY_MASK_B_PRIME |                   \
   NFA_TECHNOLOGY_MASK_KOVIO | NFA_TECHNOLOGY_MASK_ACTIVE)

#define ONE_SECOND_MS 1000
#define HUNDRED_MS 100
#define ONE_MS 1

/**************************************************
 ** enum declarations                            **
 *************************************************/
enum NFCSELFTESTCMDTYPE {
  CMD_TYPE_CORE_RESET = 0x00,
  CMD_TYPE_CORE_INIT,
  CMD_TYPE_CORE_GET_CONFIG_RFTXCFG0,
  CMD_TYPE_CORE_GET_CONFIG_RFTXCFG1,
  CMD_TYPE_CORE_GET_CONFIG_RFTXCFG2,
  CMD_TYPE_CORE_GET_CONFIG_RFTXCFG3,
  CMD_TYPE_CORE_GET_CONFIG_RFTXCFG4,
  CMD_TYPE_CORE_GET_CONFIG_RFTXCFG5,
  CMD_TYPE_CORE_SET_CONFIG_RFTXCFG,
  CMD_TYPE_NXP_PROP_EXT,
  CMD_TYPE_NXP_PROP_FLASH_TO_ROM,
  CMD_TYPE_NFCC_STANDBY_ON,
  CMD_TYPE_NFCC_STANDBY_OFF,
  CMD_TYPE_NFCC_DISC_MAP,
  CMD_TYPE_NFCC_DEACTIVATE,
  CMD_TYPE_RF_ON,
  CMD_TYPE_RF_OFF,
  CMD_TYPE_PRBS_ON,
  CMD_TYPE_SPC_NTF_EN,
  CMD_TYPE_SPC_BLK1,
  CMD_TYPE_SPC_BLK2,
  CMD_TYPE_SPC_BLK3,
  CMD_TYPE_SPC_START,
  CMD_TYPE_SPC_ROUTE,
  CMD_TYPE_NFCC_ALLOW_CHANGE_PARAM,
};

enum NFCCSELFTESTTYPE {
  TEST_TYPE_RESTORE_RFTXCFG = 0x00,
  TEST_TYPE_SET_RFTXCFG_RESONANT_FREQ,
  TEST_TYPE_RF_ON,
  TEST_TYPE_RF_OFF,
  TEST_TYPE_TRANSAC_A,
  TEST_TYPE_TRANSAC_B,
  TEST_TYPE_PRBS_ON,
  TEST_TYPE_PRBS_OFF,
  TEST_TYPE_SPC,
  TEST_TYPE_NONE = 0xFF
};

/**************************************************
 ** Structure declarations                       **
 *************************************************/
typedef struct nxp_selftest_data {
  SyncEvent NxpSelfTestEvt;
  uint8_t* prestorerftxcfg;  // pointer to the
  // uint8_t index;
  bool isStored;
  bool copyData;
  bool fSetResFreq;
  uint8_t prbsTech;
  uint8_t prbsRate;
  tNFA_STATUS wstatus;
} nxp_selftest_data;

/**************************************************
 ** Global Variables                             **
 *************************************************/
extern nxp_selftest_data gselfTestData;

/**************************************************
 ** Class Declaration                            **
 *************************************************/
class NfcSelfTest {
 public:
  /**
   * Get the NfcSeManager singleton object.
   * @return NfcSeManager object.
   */
  static NfcSelfTest& GetInstance();

  /**
   * Executes NFC self-test requests from service.
   * @param  aType denotes type of self-test
   * @return status SUCCESS or FAILED.
   */
  tNFA_STATUS doNfccSelfTest(int aType);

  /**
   * Notifies whenever INTF_ACTIVATED_NTF is received
   * @return None
   */
  void ActivatedNtf_Cb();

  int32_t SelfTestType;

 private:
  static NfcSelfTest sSelfTestMgr;

  /**
   * Initialize member variables.
   * @return None
   */
  NfcSelfTest();

  /**
   * Release all resources.
   * @return None
   */
  ~NfcSelfTest();

  /**
   * Executes NFC self-test for RF ON and OFF.
   * @param  on denotes
   *         TRUE  - RF ON
   *         FALSE - RF OFF
   * @return status SUCCESS or FAILED.
   */
  tNFA_STATUS PerformRFTest(bool on);

  /**
   * To verify analog parameters of A and B
   * @param  aType denotes
   *         TEST_TYPE_TRANSAC_A
   *         TEST_TYPE_TRANSAC_B
   * @return status SUCCESS or FAILED.
   */
  tNFA_STATUS PerformTransacAB(uint8_t aType);

  /**
   * Executes: 1. Save the current value of the NFCC's RF_TRANSITION_CFG.
   *           2. Sets the RF_TRANSITION_CFG value to generate Resonant
   * Frequency.
   * @param  None
   * @return status SUCCESS or FAILED.
   */
  tNFA_STATUS setResonantFreq();

  /**
   * Executes Restore the RF_TRANSITION_CFG values, if stored by the
   * setResonantFreq()
   * @param  None
   * @return status SUCCESS or FAILED.
   */
  tNFA_STATUS restoreRfTxCfg();

  /**
   * Executes: Updates the RF_TRANSITION_CFG as per the command type
   * @param  on denotes
   *         TRUE  - setResonantFreq()
   *         FALSE - restoreRfTxCfg()
   * @return status SUCCESS or FAILED.
   */
  tNFA_STATUS PerformResonantFreq(bool on);

  /**
   * Executes: Configures the FW and starts the SPC algorithm to save the customer
   *           phase offset into RF_CUST_PHASE_COMPENSATION.
   * @param    None
   * @return status SUCCESS or FAILED.
   */
  tNFA_STATUS PerformSPCTest();
  /*******************************************************************************
   ** Executes: Perform Prbs
   ** @param  on denotes
   **         TRUE  - prbs start()
   **         FALSE - prbs stop()
   ** @return status SUCCESS or FAILED.
   *******************************************************************************/
  tNFA_STATUS PerformPrbs(bool on);
  /**
   * Provides the command buffer for the given command type
   * @param CmdBuf- for the given command type
   *        aType - is the command type
   * @return length of the command buffer
   */
  uint8_t GetCmdBuffer(uint8_t* CmdBuf, uint8_t aType);

  /**
   * Writes sequence of commands provided to NFCC
   * @param *aCmdType- pointer for the list of command types
   *        aNumOfCmds- is the number of command types in the list
   * @return status SUCCESS or FAILED.
   */
  tNFA_STATUS executeCmdSeq(uint8_t* aCmdType, uint8_t aNumOfCmds);
  SyncEvent mSelfTestTransacAB;
};
