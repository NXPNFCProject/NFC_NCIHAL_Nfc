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
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include "MposManager.h"
#include <nativehelper/ScopedLocalRef.h>
#include "SecureElement.h"
//#include "TransactionController.h"
#include "nfa_scr_api.h"
#include "nfa_scr_int.h"
#include "nfc_config.h"
#include "phNxpConfig.h"
#include "TransactionController.h"

using namespace android;
using android::base::StringPrintf;

extern bool nfc_debug_enabled;

MposManager::MposManager() { mNativeData = NULL; }

namespace android {
extern bool isDiscoveryStarted();
extern void startRfDiscovery(bool isStart);
extern void setDiscoveryStartedCfg(bool isStarted);
}  // namespace android

MposManager MposManager::mMposMgr;
bool MposManager::mIsMposOn = false;
bool MposManager::mStartNfcForumPoll = false;
bool MposManager::isMposEnabled = false;
jmethodID MposManager::gCachedMposManagerNotifyEvents;

/*******************************************************************************
**
** Function:        initMposNativeStruct
**
** Description:     Used to initialize the Native MPOS notification methods
**
** Returns:         None.
**
*******************************************************************************/
void MposManager::initMposNativeStruct(JNIEnv* e, jobject o) {
  ScopedLocalRef<jclass> cls(e, e->GetObjectClass(o));

  gCachedMposManagerNotifyEvents= e->GetMethodID (cls.get(),
          "notifyonMposManagerEvents", "(I)V");
}

/*******************************************************************************
**
** Function:        getInstance
**
** Description:     Get the SecureElement singleton object.
**
** Returns:         SecureElement object.
**
*******************************************************************************/
MposManager& MposManager::getInstance() { return mMposMgr; }

/*******************************************************************************
**
** Function:        initialize
**
** Description:     Initialize all member variables.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool MposManager::initialize(nfc_jni_native_data* native) {
  mNativeData = native;
  mIsMposOn = false;
  mStartNfcForumPoll = false;
  isMposEnabled = (NfcConfig::getUnsigned(NAME_ETSI_READER_ENABLE, 0) == 1) ? true : false;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s:enter, isMposEnabled %d", __FUNCTION__, isMposEnabled);
  return true;
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
void MposManager::finalize() {}

/*******************************************************************************
**
** Function:        setMposReaderMode
**
** Description:     Set/reset mPOS mode.
**
** Returns:         SUCCESS/FAILED/BUSY
**
*******************************************************************************/
tNFA_STATUS MposManager::setMposReaderMode(bool on) {
  tNFA_STATUS status = NFA_STATUS_OK;

  SecureElement& se = SecureElement::getInstance();

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s:enter, Reader Mode %s", __FUNCTION__, on ? "ON" : "OFF");

  if (se.isRfFieldOn() || se.mActivatedInListenMode) {
    DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf(
        "Payment is in progress,"
        "aborting reader mode start");
    return NFA_STATUS_BUSY;
  }
  if (mIsMposOn == on) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s:Operation is not permitted", __func__);
    return NFA_STATUS_BUSY;
  }
  if(on) {
    mIsMposOn = true;
  }
  /*if (t4tNfcEe.isT4tNfceeBusy()) {
    mIsMposWaitToStart = true;
    SyncEventGuard g(t4tNfcEe.mT4tNfceeMPOSEvt);
    t4tNfcEe.mT4tNfceeMPOSEvt.wait(500);
    mIsMposWaitToStart = false;
  }*/

  {
    tNFA_SCR_CBACK* scr_cback = nullptr;
    if(on) {
      if(isDiscoveryStarted()) startRfDiscovery(false);
      scr_cback = mMposMgr.notifyEEReaderEvent;
    }

    SyncEventGuard guard(mNfaScrApiEvent);
    status = NFA_ScrSetReaderMode(on, scr_cback);
    if(NFA_STATUS_OK == status) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: waiting on mNfaScrApiEvent", __func__);
      mNfaScrApiEvent.wait();
      if(on == false && mStartNfcForumPoll) {
        if(!isDiscoveryStarted()) startRfDiscovery(true);
        mStartNfcForumPoll = false;
        mIsMposOn = false;
      }else if(on == true) { /* Clear the flag if Reader Start was requested */
        mStartNfcForumPoll = false;
      }
    } else {
      mIsMposOn = on?false:true;
    }
  }
  if((on == false) && (pTransactionController->getCurTransactionRequestor() == setMposState)) {
    pTransactionController->transactionEnd(TRANSACTION_REQUESTOR(setMposState));
  }

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit Status=%d", __func__, status);
  return status;
}

/*******************************************************************************
**
** Function:        getMposReaderMode
**
** Description:     Get the mPOS mode.
**
** Returns:         True is mPOS mode is On, else False
**
*******************************************************************************/
bool MposManager::getMposReaderMode(void) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:enter", __func__);
  return NFA_ScrGetReaderMode();
}

/*******************************************************************************
**
** Function:        isMposOngoing
**
** Description:     This API shall be used to check if mPOS mode is ongoing.
**
** Returns:         True if mPOS mode is in progress, else False
**
*******************************************************************************/
bool MposManager::isMposOngoing(void) { return mIsMposOn; }

/*******************************************************************************
**
** Function:        notifyScrApiEvent
**
** Description:     This API shall be called to notify the mNfaScrApiEvent event.
**
** Returns:         None
**
*******************************************************************************/
void MposManager::notifyScrApiEvent () {
  /* unblock the api here */
  SyncEventGuard guard(mMposMgr.mNfaScrApiEvent);
  mMposMgr.mNfaScrApiEvent.notifyOne();
}
/*******************************************************************************
**
** Function:        notifyEEReaderEvent
**
** Description:     Notify with the Reader event
**
** Returns:         None
**
*******************************************************************************/
void MposManager::notifyEEReaderEvent(uint8_t evt, uint8_t status) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter; event=%x status=%u", __func__, evt, status);
  JNIEnv* e = NULL;

  ScopedAttach attach(mMposMgr.mNativeData->vm, &e);
  if (e == NULL) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: jni env is null", __FUNCTION__);
    return;
  }
  uint8_t msg = MSG_SCR_INVALID;
  switch (evt) {
    case NFA_SCR_SET_READER_MODE_EVT: {
      if(status == NFA_STATUS_OK) {
        mStartNfcForumPoll = true;
      }
      mMposMgr.notifyScrApiEvent();
      break;
    }
    case NFA_SCR_START_SUCCESS_EVT:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: NFA_SCR_START_SUCCESS_EVT", __func__);
      msg = MSG_SCR_START_SUCCESS_EVT;
      if (pTransactionController->transactionAttempt(TRANSACTION_REQUESTOR(setMposState)) == 0) {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: transaction attempt failed", __func__);
      }
      break;
    case NFA_SCR_START_FAIL_EVT:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: NFA_SCR_START_FAIL_EVT", __func__);
      msg = MSG_SCR_START_FAIL_EVT;
      pTransactionController->transactionEnd(TRANSACTION_REQUESTOR(setMposState));
      break;
    case NFA_SCR_RESTART_EVT:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: NFA_SCR_RESTART_EVT", __func__);
      msg = MSG_SCR_RESTART_EVT;
      break;
    case NFA_SCR_TARGET_ACTIVATED_EVT:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: NFA_SCR_TARGET_ACTIVATED_EVT", __func__);
      msg = MSG_SCR_ACTIVATED_EVT;
      break;
    case NFA_SCR_STOP_SUCCESS_EVT:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: NFA_SCR_STOP_SUCCESS_EVT", __func__);
      msg = MSG_SCR_STOP_SUCCESS_EVT;
      break;
    case NFA_SCR_STOP_FAIL_EVT:
      DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: NFA_SCR_STOP_FAIL_EVT", __func__);
      msg = MSG_SCR_STOP_FAIL_EVT;
      mMposMgr.notifyScrApiEvent();
      pTransactionController->transactionEnd(TRANSACTION_REQUESTOR(setMposState));
      break;
    case NFA_SCR_TIMEOUT_EVT:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: NFA_SCR_TIMEOUT_EVT", __func__);
      msg = MSG_SCR_TIMEOUT_EVT;
      pTransactionController->transactionEnd(TRANSACTION_REQUESTOR(setMposState));
      break;
    case NFA_SCR_REMOVE_CARD_EVT:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: NFA_SCR_REMOVE_CARD_EVT", __func__);
      msg = MSG_SCR_REMOVE_CARD_EVT;
      break;
    case NFA_SCR_MULTI_TARGET_DETECTED_EVT:
      DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: NFA_SCR_MULTIPLE_TARGET_DETECTED_EVT", __func__);
      msg = MSG_SCR_MULTIPLE_TARGET_DETECTED_EVT;
      break;
    default:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Invalid event received", __func__);
      break;
  }
  if (msg != MSG_SCR_INVALID) {
    e->CallVoidMethod(mMposMgr.mNativeData->manager, gCachedMposManagerNotifyEvents,(int)msg);
  }

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
}

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
tNFA_STATUS MposManager::validateHCITransactionEventParams(uint8_t* aData,
                                                           int32_t aDatalen) {
  tNFA_STATUS status = NFA_STATUS_OK;
  uint8_t Event, Version, Code;
  if (aData != NULL && aDatalen >= 3) {
    Event = *aData++;
    Version = *aData++;
    Code = *aData;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s -> Event:%d, Version:%d, Code:%d", __func__, Event, Version, Code);
    if (Event == EVENT_RF_ERROR && Version == EVENT_RF_VERSION) {
      if (Code == EVENT_RDR_MODE_RESTART) {
        status = NFA_STATUS_FAILED;
        notifyEEReaderEvent(NFA_SCR_RESTART_EVT, NFA_STATUS_OK);
      } else {
      }
    }
  } else if (aData != NULL && aDatalen == 0x01 &&
             *aData == EVENT_EMV_POWER_OFF) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("Power off procedure to be triggered");
    unsigned long num;
    if (NfcConfig::hasKey(NAME_NFA_CONFIG_FORMAT)) {
      num = NfcConfig::getUnsigned(NAME_NFA_CONFIG_FORMAT);
      if (num == 0x05) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("Power off procedure is triggered");
        NFA_Deactivate(false);
      } else {
        // DO nothing
      }
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NAME_NFA_CONFIG_FORMAT not found");
    }
  } else {
    // DO nothing
  }
  return status;
}
