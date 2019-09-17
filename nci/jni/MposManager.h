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
#include "nfc_api.h"

class MposManager {
 public:
  MposManager();
  static jmethodID gCachedMposManagerNotifyEvents;
  static bool isMposEnabled;

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
  ** Function:        setMposReaderMode
  **
  ** Description:     Set/reset mPOS mode.
  **
  ** Returns:         SUCCESS/FAILED/BUSY
  **
  *******************************************************************************/
  tNFA_STATUS setMposReaderMode(bool on);

  /*******************************************************************************
  **
  ** Function:        getMposReaderMode
  **
  ** Description:     Get the mPOS mode.
  **
  ** Returns:         True is mPOS mode is On, else False
  **
  *******************************************************************************/
  bool getMposReaderMode(void);

  /*******************************************************************************
  **
  ** Function:        isMposOngoing
  **
  ** Description:     This API shall be used to check if mPOS mode is ongoing.
  **
  ** Returns:         True if mPOS mode is in progress, else False
  **
  *******************************************************************************/
  bool isMposOngoing(void);

  /*******************************************************************************
  **
  ** Function:        notifyScrApiEvent
  **
  ** Description:     This API shall be called to notify the mNfaScrApiEvent event.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void notifyScrApiEvent ();

  /*******************************************************************************
  **
  ** Function:        notifyEEReaderEvent
  **
  ** Description:     Notify with the Reader event
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void notifyEEReaderEvent(uint8_t evt, uint8_t status);

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

 private:
  nfc_jni_native_data* mNativeData = NULL;
  static const uint8_t EVENT_RF_ERROR =
      0x80;  // HCI_TRANSACTION_EVENT parameter type
  static const uint8_t EVENT_RF_VERSION =
      0x00;  // HCI_TRANSACTION_EVENT parameter version
  static const uint8_t EVENT_EMV_POWER_OFF =
      0x72;  // HCI_TRANSACTION_EVENT parameter power off
  static const uint8_t EVENT_RDR_MODE_RESTART =
      0x04;  // EVENT to Restart Reader mode

  /* Events to be posted to the NFC service */
  static const uint8_t MSG_SCR_INVALID            = 0x00;
  static const uint8_t MSG_SCR_START_SUCCESS_EVT  = 70;
  static const uint8_t MSG_SCR_START_FAIL_EVT     = 71;
  static const uint8_t MSG_SCR_RESTART_EVT        = 72;
  static const uint8_t MSG_SCR_ACTIVATED_EVT      = 73;
  static const uint8_t MSG_SCR_STOP_SUCCESS_EVT   = 74;
  static const uint8_t MSG_SCR_STOP_FAIL_EVT      = 75;
  static const uint8_t MSG_SCR_TIMEOUT_EVT        = 76;
  static const uint8_t MSG_SCR_REMOVE_CARD_EVT    = 77;
  static const uint8_t MSG_SCR_MULTIPLE_TARGET_DETECTED_EVT = 78;

  static bool         mIsMposOn;
  static bool         mStartNfcForumPoll; /* It shall be used only in reader stop case */
  SyncEvent           mNfaScrApiEvent;
  static MposManager  mMposMgr;
};
