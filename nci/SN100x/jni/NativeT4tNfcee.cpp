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
#if (NXP_EXTNS == TRUE)
#include "NativeT4tNfcee.h"
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include "MposManager.h"
#include "NfcJniUtil.h"
#include "nfa_nfcee_api.h"
#include "nfa_nfcee_int.h"

using android::base::StringPrintf;
extern bool nfc_debug_enabled;

/*Considering NCI response timeout which is 2s, Timeout set 100ms more*/
#define T4TNFCEE_TIMEOUT 2100
#define FILE_ID_LEN 0x02

extern bool gActivated;

namespace android {
extern bool isDiscoveryStarted();
extern void startRfDiscovery(bool isStart);
extern bool nfcManager_isNfcActive();
}  // namespace android

NativeT4tNfcee NativeT4tNfcee::sNativeT4tNfceeInstance;

NativeT4tNfcee::NativeT4tNfcee() {}

/*****************************************************************************
**
** Function:        getInstance
**
** Description:     Get the NativeT4tNfcee singleton object.
**
** Returns:         NativeT4tNfcee object.
**
*******************************************************************************/
NativeT4tNfcee& NativeT4tNfcee::getInstance() {
  return sNativeT4tNfceeInstance;
}

/*******************************************************************************
**
** Function:        t4tWriteData
**
** Description:     Write the data into the T4T file of the specific file ID
**
** Returns:         Return the size of data written
**                  Return negative number of error code
**
*******************************************************************************/
jint NativeT4tNfcee::t4tWriteData(JNIEnv* e, jobject object, jbyteArray fileId,
                                  jbyteArray data, int length) {
  tNFA_STATUS status = NFA_STATUS_FAILED;

  T4TNFCEE_STATUS_t t4tNfceeStatus =
      validatePreCondition(OP_WRITE, fileId, data);
  if (t4tNfceeStatus != STATUS_SUCCESS) return t4tNfceeStatus;

  ScopedByteArrayRO bytes(e, fileId);
  if (bytes.size() < FILE_ID_LEN) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s:Wrong File Id", __func__);
    return ERROR_INVALID_FILE_ID;
  }

  ScopedByteArrayRO bytesData(e, data);
  if (bytesData.size() == 0x00) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s:Empty Data", __func__);
    return ERROR_EMPTY_PAYLOAD;
  }

  if ((int)bytesData.size() != length) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s:Invalid Length", __func__);
    return ERROR_INVALID_LENGTH;
  }

  if (setup() != NFA_STATUS_OK) return ERROR_CONNECTION_FAILED;

  uint8_t* pFileId = NULL;
  pFileId = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));

  uint8_t* pData = NULL;
  pData = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytesData[0]));

  jint t4tWriteReturn = STATUS_FAILED;
  {
    SyncEventGuard g(mT4tNfcEeRWEvent);
    status = NFA_T4tNfcEeWrite(pFileId, pData, bytesData.size());
    if (status == NFA_STATUS_OK) {
      if (mT4tNfcEeRWEvent.wait(T4TNFCEE_TIMEOUT) == false)
        t4tWriteReturn = STATUS_FAILED;
      else {
        if (mWriteStatus == NFA_STATUS_OK) {
          /*if status is success then return length of data written*/
          t4tWriteReturn = mReadData.len;
        } else if (mWriteStatus == NFA_STATUS_REJECTED) {
          t4tWriteReturn = ERROR_NDEF_VALIDATION_FAILED;
        } else
          t4tWriteReturn = STATUS_FAILED;
      }
    }
  }

  /*Close connection and start discovery*/
  cleanup();
  DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf(
      "%s:Exit: Returnig status : %d", __func__, t4tWriteReturn);
  return t4tWriteReturn;
}

/*******************************************************************************
**
** Function:        t4tReadData
**
** Description:     Read the data from the T4T file of the specific file ID.
**
** Returns:         byte[] : all the data previously written to the specific
**                  file ID.
**                  Return one byte '0xFF' if the data was never written to the
**                  specific file ID,
**                  Return null if reading fails.
**
*******************************************************************************/
jbyteArray NativeT4tNfcee::t4tReadData(JNIEnv* e, jobject object,
                                       jbyteArray fileId) {
  tNFA_STATUS status = NFA_STATUS_FAILED;

  T4TNFCEE_STATUS_t t4tNfceeStatus = validatePreCondition(OP_READ, fileId);
  if (t4tNfceeStatus != STATUS_SUCCESS) return NULL;

  ScopedByteArrayRO bytes(e, fileId);
  if (bytes.size() < FILE_ID_LEN) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s:Wrong File Id", __func__);
    return NULL;
  }

  if (setup() != NFA_STATUS_OK) return NULL;

  uint8_t* pFileId = NULL;
  pFileId = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));

  { /*syncEvent code section*/
    SyncEventGuard g(mT4tNfcEeRWEvent);
    status = NFA_T4tNfcEeRead(pFileId);
    if ((status != NFA_STATUS_OK) ||
        (mT4tNfcEeRWEvent.wait(T4TNFCEE_TIMEOUT) == false)) {
      DLOG_IF(ERROR, nfc_debug_enabled)
          << StringPrintf("%s:Read Failed, status = 0x%X", __func__, status);
      cleanup();
      return NULL;
    }
  }

  jbyteArray buf = NULL;
  if (mReadData.len > 0x00) {
    /*. Set JNI variables for sending response to application*/
    buf = e->NewByteArray(mReadData.len);
    e->SetByteArrayRegion(buf, 0, mReadData.len, (jbyte*)mReadData.p_data);
    if (mReadData.p_data != nullptr) free(mReadData.p_data);
  } else {
    char data[1] = {0xFF};
    buf = e->NewByteArray(0x01);
    e->SetByteArrayRegion(buf, 0, 0x01, (jbyte*)data);
  }
  /*Close connection and start discovery*/
  cleanup();
  return buf;
}

/*******************************************************************************
**
** Function:        openConnection
**
** Description:     Open T4T Nfcee Connection
**
** Returns:         Status
**
*******************************************************************************/
tNFA_STATUS NativeT4tNfcee::openConnection() {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);
  SyncEventGuard g(mT4tNfcEeEvent);
  status = NFA_T4tNfcEeOpenConnection();
  if (status == NFA_STATUS_OK) {
    if (mT4tNfcEeEvent.wait(T4TNFCEE_TIMEOUT) == false)
      status = NFA_STATUS_FAILED;
    else
      status = mT4tNfcEeEventStat;
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Exit status = 0x%02x", __func__, status);
  return status;
}

/*******************************************************************************
**
** Function:        closeConnection
**
** Description:     Close T4T Nfcee Connection
**
** Returns:         Status
**
*******************************************************************************/
tNFA_STATUS NativeT4tNfcee::closeConnection() {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);
  {
    SyncEventGuard g(mT4tNfcEeEvent);
    status = NFA_T4tNfcEeCloseConnection();
    if (status == NFA_STATUS_OK) {
      if (mT4tNfcEeEvent.wait(T4TNFCEE_TIMEOUT) == false)
        status = NFA_STATUS_FAILED;
      else
        status = mT4tNfcEeEventStat;
    }
  }
  if (MposManager::getInstance().mIsMposWaitToStart) {
    /**Notify MPOS if MPOS is waiting for T4t Operation to complete*/
    SyncEventGuard g(mT4tNfceeMPOSEvt);
    mT4tNfceeMPOSEvt.notifyOne();
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Exit status = 0x%02x", __func__, status);
  return status;
}

/*******************************************************************************
**
** Function:        setup
**
** Description:     stops Discovery and opens T4TNFCEE connection
**
** Returns:         Status
**
*******************************************************************************/
tNFA_STATUS NativeT4tNfcee::setup(void) {
  tNFA_STATUS status = NFA_STATUS_FAILED;

  if (android::isDiscoveryStarted()) {
    android::startRfDiscovery(false);
  }
  setBusy();
  status = openConnection();
  if (status != NFA_STATUS_OK) {
    DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf(
        "%s: openConnection Failed, status = 0x%X", __func__, status);
    if (!android::isDiscoveryStarted()) android::startRfDiscovery(true);
    resetBusy();
  }
  return status;
}
/*******************************************************************************
**
** Function:        cleanup
**
** Description:     closes connection and starts discovery
**
** Returns:         Status
**
*******************************************************************************/
void NativeT4tNfcee::cleanup(void) {
  if (closeConnection() != NFA_STATUS_OK) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s: closeConnection Failed", __func__);
  }
  resetBusy();
  if (!android::isDiscoveryStarted()) android::startRfDiscovery(true);
}

/*******************************************************************************
**
** Function:        validatePreCondition
**
** Description:     Runs precondition checks for requested operation
**
** Returns:         Status
**
*******************************************************************************/
T4TNFCEE_STATUS_t NativeT4tNfcee::validatePreCondition(T4TNFCEE_OPERATIONS_t op,
                                                       jbyteArray fileId,
                                                       jbyteArray data) {
  T4TNFCEE_STATUS_t t4tNfceeStatus = STATUS_SUCCESS;
  if (!android::nfcManager_isNfcActive()) {
    t4tNfceeStatus = ERROR_NFC_NOT_ON;
  } else if (gActivated) {
    t4tNfceeStatus = ERROR_RF_ACTIVATED;
  } else if (MposManager::getInstance().isMposOngoing()) {
    t4tNfceeStatus = ERROR_MPOS_ON;
  } else if (fileId == NULL) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s:Invalid File Id", __func__);
    t4tNfceeStatus = ERROR_INVALID_FILE_ID;
  }

  switch (op) {
    case OP_READ:
      break;
    case OP_WRITE:
      if (data == NULL) {
        DLOG_IF(ERROR, nfc_debug_enabled)
            << StringPrintf("%s:Empty data", __func__);
        t4tNfceeStatus = ERROR_EMPTY_PAYLOAD;
      }
      break;
    default:
      break;
  }
  return t4tNfceeStatus;
}

/*******************************************************************************
**
** Function:        t4tReadComplete
**
** Description:     Updates read data to the waiting READ API
**
** Returns:         none
**
*******************************************************************************/
void NativeT4tNfcee::t4tReadComplete(tNFA_STATUS status, tNFA_RX_DATA data) {
  mReadData.len = 0x00;
  if (mReadData.p_data != nullptr) free(mReadData.p_data);
  mReadData.p_data = nullptr;

  if (status == NFA_STATUS_OK) {
    mReadData.len = data.len;
    if (mReadData.len > 0) {
      mReadData.p_data = (uint8_t*)malloc(sizeof(uint8_t) * mReadData.len);
      memcpy(mReadData.p_data, data.p_data, data.len);
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: Read Data len: %d ", __func__, mReadData.len);
    }
  }
  SyncEventGuard g(mT4tNfcEeRWEvent);
  mT4tNfcEeRWEvent.notifyOne();
}

/*******************************************************************************
 **
 ** Function:        t4tWriteComplete
 **
 ** Description:     Returns write complete information
 **
 ** Returns:         none
 **
 *******************************************************************************/
void NativeT4tNfcee::t4tWriteComplete(tNFA_STATUS status, tNFA_RX_DATA data) {
  mReadData.len = 0x00;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);
  if (status == NFA_STATUS_OK) mReadData.len = data.len;
  mWriteStatus = status;
  SyncEventGuard g(mT4tNfcEeRWEvent);
  mT4tNfcEeRWEvent.notifyOne();
}

/*******************************************************************************
**
** Function:        t4tNfceeEventHandler
**
** Description:     Handles callback events received from lower layer
**
** Returns:         none
**
*******************************************************************************/
void NativeT4tNfcee::eventHandler(uint8_t event,
                                  tNFA_CONN_EVT_DATA* eventData) {
  switch (event) {
    case NFA_T4TNFCEE_EVT:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_T4TNFCEE_EVT", __func__);
      {
        SyncEventGuard guard(mT4tNfcEeEvent);
        mT4tNfcEeEventStat = eventData->status;
        mT4tNfcEeEvent.notifyOne();
      }
      break;

    case NFA_T4TNFCEE_READ_CPLT_EVT:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_T4TNFCEE_READ_CPLT_EVT", __func__);
      t4tReadComplete(eventData->status, eventData->data);
      break;

    case NFA_T4TNFCEE_WRITE_CPLT_EVT:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_T4TNFCEE_WRITE_CPLT_EVT", __func__);
      t4tWriteComplete(eventData->status, eventData->data);
      break;

    default:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: unknown Event", __func__);
      break;
  }
}

/*******************************************************************************
 **
 ** Function:        isT4tNfceeBusy
 **
 ** Description:     Returns True if T4tNfcee operation is ongoing else false
 **
 ** Returns:         true/false
 **
 *******************************************************************************/
bool NativeT4tNfcee::isT4tNfceeBusy(void) { return mBusy; }

/*******************************************************************************
 **
 ** Function:        setBusy
 **
 ** Description:     Sets busy flag indicating T4T operation is ongoing
 **
 ** Returns:         none
 **
 *******************************************************************************/
void NativeT4tNfcee::setBusy() { mBusy = true; }

/*******************************************************************************
 **
 ** Function:        resetBusy
 **
 ** Description:     Resets busy flag indicating T4T operation is completed
 **
 ** Returns:         none
 **
 *******************************************************************************/
void NativeT4tNfcee::resetBusy() { mBusy = false; }
#endif
