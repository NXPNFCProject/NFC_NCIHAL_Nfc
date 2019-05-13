/******************************************************************************
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
 *  Copyright 2018-2019 NXP
 *
 ******************************************************************************/
#pragma once
#include "IntervalTimer.h"
#include "Mutex.h"
#include "NfcJniUtil.h"
#include "SyncEvent.h"

#include "nfa_api.h"
#include "nfa_ee_api.h"
#include "nfc_api.h"

typedef struct {
  tNFA_HANDLE src;
  tNFA_TECHNOLOGY_MASK tech_mask;
  bool reCfg;
} rd_swp_req_t;

typedef enum {
  MPOS_READER_MODE_INVALID = 0x00,
  MPOS_READER_MODE_START,
  MPOS_READER_MODE_START_SUCCESS,
  MPOS_READER_MODE_RESTART,
  MPOS_READER_MODE_STOP,
  MPOS_READER_MODE_STOP_SUCCESS,
  MPOS_READER_MODE_TIMEOUT,
  MPOS_READER_MODE_REMOVE_CARD,
  MPOS_READER_MODE_RECOVERY,
  MPOS_READER_MODE_RECOVERY_COMPLETE,
  MPOS_READER_MODE_RECOVERY_TIMEOUT,
  MPOS_READER_MODE_FAIL
} mpos_rd_state_t;

typedef enum {
  STATE_SE_RDR_MODE_INVALID = 0x00,
  STATE_SE_RDR_MODE_START_CONFIG,
  STATE_SE_RDR_MODE_START_IN_PROGRESS,
  STATE_SE_RDR_MODE_STARTED,
  STATE_SE_RDR_MODE_ACTIVATED,
  STATE_SE_RDR_MODE_STOP_CONFIG,
  STATE_SE_RDR_MODE_STOP_IN_PROGRESS,
  STATE_SE_RDR_MODE_STOPPED,
} se_rd_req_state_t;

typedef enum {
  /* Reader over SWP Events*/
  ETSI_READER_START_SUCCESS = 0x00,
  ETSI_READER_START_FAIL,
  ETSI_READER_ACTIVATED,
  ETSI_READER_STOP,
} etsi_rd_event_t;

typedef struct {
  rd_swp_req_t swp_rd_req_info;
  rd_swp_req_t swp_rd_req_current_info;
  se_rd_req_state_t swp_rd_state;
  Mutex mMutex;
} Rdr_req_ntf_info_t;

#define NFC_NUM_INTERFACE_MAP 3
#define NFC_SWP_RD_NUM_INTERFACE_MAP 1

class MposManager {
 public:
  MposManager();
  Rdr_req_ntf_info_t swp_rdr_req_ntf_info;
  static jmethodID gCachedMposManagerNotifyETSIReaderRequested;
  static jmethodID gCachedMposManagerNotifyETSIReaderRequestedFail;
  static jmethodID gCachedMposManagerNotifyETSIReaderModeStartConfig;
  static jmethodID gCachedMposManagerNotifyETSIReaderModeStopConfig;
  static jmethodID gCachedMposManagerNotifyETSIReaderModeSwpTimeout;
  static jmethodID gCachedMposManagerNotifyETSIReaderRestart;

  /*******************************************************************************
  **
  ** Function:        initMposNativeStruct
  **
  ** Description:     Used to initialize the Native MPOS notification methods
  **
  ** Returns:         None.
  **
  *******************************************************************************/
  static void initMposNativeStruct(JNIEnv* e, jobject o);

  /*******************************************************************************
  **
  ** Function:        getInstance
  **
  ** Description:     Get the SecureElement singleton object.
  **
  ** Returns:         MposManager object.
  **
  *******************************************************************************/
  static MposManager& getInstance();

  /*******************************************************************************
  **
  ** Function:        initialize
  **
  ** Description:     Initialize all member variables.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  bool initialize(nfc_jni_native_data* native);

  /*******************************************************************************
  **
  ** Function:        initializeReaderInfo
  **
  ** Description:     Initialize all MPOS member variables.
  **
  ** Returns:         none.
  **
  *******************************************************************************/
  void initializeReaderInfo();

  /*******************************************************************************
  **
  ** Function:        finalize
  **
  ** Description:     Release all resources.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void finalize();

  /*******************************************************************************
  **
  ** Function:        setDedicatedReaderMode
  **
  ** Description:     Set/reset MPOS dedicated mode.
  **
  ** Returns:         SUCCESS/FAILED/BUSY
  **
  *******************************************************************************/
  tNFA_STATUS setDedicatedReaderMode(bool on);

  /*******************************************************************************
  **
  ** Function:        setEtsiReaederState
  **
  ** Description:     Set the current ETSI Reader state
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void setEtsiReaederState(se_rd_req_state_t newState);

  /*******************************************************************************
  **
  ** Function:        getEtsiReaederState
  **
  ** Description:     Get the current ETSI Reader state
  **
  ** Returns:         Current ETSI state
  **
  *******************************************************************************/
  se_rd_req_state_t getEtsiReaederState();

  /*******************************************************************************
  **
  ** Function:        etsiInitConfig
  **
  ** Description:     Chnage the ETSI state before start configuration
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void etsiInitConfig();

  /*******************************************************************************
  **
  ** Function:        etsiReaderConfig
  **
  ** Description:     Configuring to Emvco Profile
  **
  ** Returns:         Status - NFA_STATUS_FAILED
  **                           NFA_STATUS_OK
  **                           NFA_STATUS_INVALID_PARAM
  **
  *******************************************************************************/
  tNFA_STATUS etsiReaderConfig(int32_t eeHandle);

  /*******************************************************************************
  **
  ** Function:        etsiResetReaderConfig
  **
  ** Description:     Configuring from Emvco profile to Nfc forum profile
  **
  ** Returns:         Status - NFA_STATUS_FAILED
  **                           NFA_STATUS_OK
  **                           NFA_STATUS_INVALID_PARAM
  **
  *******************************************************************************/
  tNFA_STATUS etsiResetReaderConfig();

  /*******************************************************************************
  **
  ** Function:        notifyEEReaderEvent
  **
  ** Description:     Notify with the Reader event
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void notifyEEReaderEvent(etsi_rd_event_t evt);

  /*******************************************************************************
  **
  ** Function:        notifyMPOSReaderEvent
  **
  ** Description:     Notify the Reader current status event to NFC service
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void notifyMPOSReaderEvent(mpos_rd_state_t aEvent);

  /*******************************************************************************
  **
  ** Function:        hanldeEtsiReaderReqEvent
  **
  ** Description:     Handler for reader request add and remove notifications
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void hanldeEtsiReaderReqEvent(tNFA_EE_DISCOVER_REQ* aInfo);

  /*******************************************************************************
  **
  ** Function:        getSwpRrdReqInfo
  **
  ** Description:     get swp_rdr_req_ntf_info
  **
  ** Returns:         swp_rdr_req_ntf_info
  **
  *******************************************************************************/
  Rdr_req_ntf_info_t getSwpRrdReqInfo();

  /*******************************************************************************
  **
  ** Function:        validateHCITransactionEventParams
  **
  ** Description:     Decodes the HCI_TRANSACTION_EVT to check for
  **                  reader restart and POWER_OFF evt
  **
  ** Returns:         OK/FAILED.
  **
  *******************************************************************************/
  tNFA_STATUS validateHCITransactionEventParams(uint8_t* aData,
                                                int32_t aDatalen);

  /*******************************************************************************
  **
  ** Function:        convertMposEventToString
  **
  ** Description:     Converts the MPOS status events to String format
  **
  ** Returns:         Name of the event
  **
  *******************************************************************************/
  const char* convertMposEventToString(mpos_rd_state_t aEvent);

  /*******************************************************************************
  **
  ** Function:        convertRdrStateToString
  **
  ** Description:     Converts the MPOS state to String format
  **
  ** Returns:         Name of the event
  **
  *******************************************************************************/
  const char* convertRdrStateToString(se_rd_req_state_t aState);

 private:
  nfc_jni_native_data* mNativeData;
  static MposManager mMposMgr;
  static int32_t mDiscNtfTimeout;
  static int32_t mRdrTagOpTimeout;
  static const uint8_t EVENT_RF_ERROR =
      0x80;  // HCI_TRANSACTION_EVENT parameter type
  static const uint8_t EVENT_RF_VERSION =
      0x00;  // HCI_TRANSACTION_EVENT parameter version
  static const uint8_t EVENT_EMV_POWER_OFF =
      0x72;  // HCI_TRANSACTION_EVENT parameter power off
  static const uint8_t EVENT_RDR_MODE_RESTART =
      0x04;  // EVENT to Restart Reader mode
  static const unsigned short rdr_req_handling_timeout = 50;

  IntervalTimer mSwpReaderTimer; /*timer swp reader timeout*/
  IntervalTimer mSwpRdrReqTimer;

  SyncEvent mDiscMapEvent;

  /*******************************************************************************
  **
  ** Function:        discoveryMapCb
  **
  ** Description:     Callback for discovery map command
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void discoveryMapCb(tNFC_DISCOVER_EVT event, tNFC_DISCOVER* p_data);

  /*******************************************************************************
  **
  ** Function:        readerReqEventNtf
  **
  ** Description:     This is used to send the reader start or stop request
  **                  event to service
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void readerReqEventNtf(union sigval);

  /*******************************************************************************
  **
  ** Function:        startStopSwpReaderProc
  **
  ** Description:     Notify the reader timeout
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void startStopSwpReaderProc(union sigval);

  /*******************************************************************************
  **
  ** Function:        etsiReaderReStart
  **
  ** Description:     Notify's the mPOS restart event
  **
  ** Returns:         void.
  **
  *******************************************************************************/
  void etsiReaderReStart();
};
