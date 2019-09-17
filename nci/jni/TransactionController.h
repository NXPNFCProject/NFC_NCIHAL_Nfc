/******************************************************************************
 *
 *  Copyright (C) 2015-2019 NXP Semiconductors
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
#include <NfcJniUtil.h>
#include <semaphore.h>
#include "IntervalTimer.h"
#include "nfa_api.h"

#define TRANSACTION_REQUESTOR(name) name
#define TRANSACTION_ATTEMPT_FOR_SECONDS(seconds) (seconds)
#define pTransactionController (transactionController::getInstance())

typedef enum transactionId {
  NO_REQUESTOR = 0,
  AppletLoadApplet = 1,
  lsExecuteScript,
  lsGetVersion,
  RF_FIELD_EVT,
  setDefaultRoute,
  commitRouting,
  enablep2p,
  enableDiscovery,
  disableDiscovery,
  NFA_ACTIVATED_EVENT,
  NFA_EE_ACTION_EVENT,
  NFA_TRANS_CE_ACTIVATED_EVENT,
  etsiReader,
  setScreenState,
  staticDualUicc,
  getTransanctionRequest,
  isTransanctionOnGoing,
  exec_pending_req,
  TAG_PRESENCE_CHECK,
  setMposState,
  /* add new requestors here in capital letters*/
} eTransactionId;

/* Transaction Events in order */
typedef enum transcation_events {
  NFA_TRANS_DEFAULT = 0x00,
  NFA_TRANS_ACTIVATED_EVT,
  NFA_TRANS_EE_ACTION_EVT,
  NFA_TRANS_DM_RF_FIELD_EVT,
  NFA_TRANS_DM_RF_FIELD_EVT_ON,
  NFA_TRANS_DM_RF_TRANS_START,
  NFA_TRANS_DM_RF_FIELD_EVT_OFF,
  NFA_TRANS_DM_RF_TRANS_PROGRESS,
  NFA_TRANS_DM_RF_TRANS_END,
  NFA_TRANS_MIFARE_ACT_EVT,
  NFA_TRANS_CE_ACTIVATED = 0x18,
  NFA_TRANS_CE_DEACTIVATED = 0x19,
} eTranscation_events_t;
/*Structure to store  discovery parameters*/
typedef struct discovery_Parameters {
  int technologies_mask;
  bool enable_lptd;
  bool reader_mode;
  bool enable_p2p;
  bool restart;
} discovery_Parameters_t;
/*Structure to store transcation result*/
typedef struct Transcation_Check {
  bool trans_in_progress;
  char last_request;
  struct nfc_jni_native_data* transaction_nat;
  eScreenState_t last_screen_state_request;
  eTranscation_events_t current_transcation_state;
  discovery_Parameters_t discovery_params;
#if (NXP_EXTNS == TRUE)
#if (NXP_NFCC_HCE_F == TRUE)
  int t3thandle;
  bool isInstallRequest;
#endif
#endif
} Transcation_Check_t;

class transactionController {
 private:
  static transactionController* pInstance;  // Reference to controller
  sem_t mBarrier;  // barrier: Guard for controlling access to NFCC when profile
                  // switch happening
  IntervalTimer
      mAbortTimer;  // abortTimer: Used for aborting a stuck transaction
  IntervalTimer mPendingTransHandleTimer;  // pendingTransHandleTimer: Used to
                                           // schedule pending transaction
                                           // handler thread
  Transcation_Check_t*
      mPTransactionDetail;    // transactionDetail: holds last transaction detail
  eTransactionId mRequestor;  // requestor: Identifier of transaction trigger

  transactionController(void);  // Constructor
  bool transactionLiveLockable(eTransactionId transactionRequestor);

 public:
  void lastRequestResume(void);
  bool transactionAttempt(eTransactionId transactionRequestor,
                          unsigned int timeoutInMsec);
  bool transactionAttempt(eTransactionId transactionRequestor);
  bool transactionTerminate(eTransactionId transactionRequestor);
  void transactionEnd(eTransactionId transactionRequestor);
  bool transactionInProgress(void);
  void killAbortTimer(void);
  void setAbortTimer(unsigned int msec);
  static void transactionAbortTimerCb(union sigval);
  static void transactionHandlePendingCb(union sigval);
  static transactionController* controller(void);
  static transactionController* getInstance(void);
  eTransactionId getCurTransactionRequestor();
};
