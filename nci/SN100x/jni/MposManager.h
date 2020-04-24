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
 *  Copyright 2018-2020 NXP
 *
 ******************************************************************************/
#pragma once
#include "Mutex.h"
#include "SyncEvent.h"
#include "IntervalTimer.h"
#include "NfcJniUtil.h"
#include "nfa_api.h"
#include "nfc_api.h"

#define ONE_SECOND_MS 1000

class MposManager
{
public:
  bool mIsMposWaitToStart = false;
  static jmethodID gCachedMposManagerNotifyEvents;

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
  void finalize ();

  /*******************************************************************************
  **
  ** Function:        getReaderType
  **
  ** Description:     This API shall be called to get a valid Reader Type based
  *on i/p.
  **
  ** Parameters:      readerType: a string "MPOS" or "MFC"
  **
  ** Returns:         Equivalent integer value to requested readerType
  **
  *******************************************************************************/
  uint8_t getReaderType(std::string readerType);

  /*******************************************************************************
  **
  ** Function:        setMposReaderMode
  **
  ** Description:     on: Set/reset requested Reader mode.
  **                  readerType: Requested Reader e.g. "MFC", "MPOS"
  **                             If not provided default value is "MPOS"
  **
  ** Returns:         SUCCESS/FAILED/BUSY
  **
  *******************************************************************************/
  tNFA_STATUS setMposReaderMode(bool on, std::string readerType = "MPOS");
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
  ** Function:        isReaderModeAllowed
  **
  ** Description:     This API shall be called to check whether the requested
  **                  reader operation is allowed or not.
  **
  ** Parameters:     on : TRUE: reader mode start is requested
  **                      FALSE: reader mode stop is requested
  **                 rdrType: Requested Reader Type
  **
  ** Returns:         OK/FAILED/REJECTED
  **
  *******************************************************************************/
  uint8_t isReaderModeAllowed(const bool on, uint8_t rdrType);

  /*******************************************************************************
  **
  ** Function:        notifyScrApiEvent
  **
  ** Description:     This API shall be called to notify the mNfaScrApiEvent
  *event.
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
  static void notifyEEReaderEvent (uint8_t evt, uint8_t status);

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
  tNFA_STATUS validateHCITransactionEventParams(uint8_t *aData, int32_t aDatalen);

private:
  MposManager(); // Default Constructor
  nfc_jni_native_data* mNativeData = NULL;
  static const uint8_t EVENT_RF_ERROR   = 0x80;    //HCI_TRANSACTION_EVENT parameter type
  static const uint8_t EVENT_RF_VERSION = 0x00;    //HCI_TRANSACTION_EVENT parameter version
  static const uint8_t EVENT_EMV_POWER_OFF = 0x72; //HCI_TRANSACTION_EVENT parameter power off
  static const uint8_t EVENT_RDR_MODE_RESTART = 0x04; //EVENT to Restart Reader mode
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

  static bool        mIsMposOn;
  static bool        mStartNfcForumPoll; /* It shall be used only in reader stop case */
  SyncEvent          mNfaScrApiEvent;
  static MposManager mMposMgr;
  static uint8_t     mReaderType;
};
