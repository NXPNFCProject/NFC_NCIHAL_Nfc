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
 *  The original Work has been changed by NXP.
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
 *  Copyright 2018-2024 NXP
 *
 ******************************************************************************/

#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <errno.h>
#include <malloc.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <semaphore.h>
#include <signal.h>
#include <string.h>
#include <time.h>

#include "IntervalTimer.h"
#include "JavaClassConstants.h"
#include "Mutex.h"
#include "NfcJniUtil.h"
#include "NfcTag.h"
#include "ndef_utils.h"
#include "nfa_api.h"
#include "nfa_rw_api.h"
#include "nfc_brcm_defs.h"
#include "nfc_config.h"
#include "rw_api.h"
#if (NXP_EXTNS == TRUE)
#include "NativeJniExtns.h"
#include "NfcTagExtns.h"
#if(NXP_SRD == TRUE)
#include "SecureDigitization.h"
#endif
#include "SecureElement.h"
#endif

using android::base::StringPrintf;

namespace android {
extern nfc_jni_native_data* getNative(JNIEnv* e, jobject o);
extern bool nfcManager_isNfcActive();
}  // namespace android

extern bool gActivated;
extern SyncEvent gDeactivatedEvent;

/*****************************************************************************
**
** public variables and functions
**
*****************************************************************************/
namespace android {
bool gIsTagDeactivating = false;  // flag for nfa callback indicating we are
                                  // deactivating for RF interface switch
bool gIsSelectingRfInterface =
    false;  // flag for nfa callback indicating we are
            // selecting for RF interface switch
#if (NXP_EXTNS == TRUE)
extern bool nfcManager_isNfcActive();
extern bool nfcManager_isNfcDisabling();
void nativeNfcTag_acquireRfInterfaceMutexLock();
void nativeNfcTag_releaseRfInterfaceMutexLock();
#endif
}  // namespace android

/*****************************************************************************
**
** private variables and functions
**
*****************************************************************************/
namespace android {

// Pre-defined tag type values. These must match the values in
// framework Ndef.java for Google public NFC API.
#define NDEF_UNKNOWN_TYPE (-1)
#define NDEF_TYPE1_TAG 1
#define NDEF_TYPE2_TAG 2
#define NDEF_TYPE3_TAG 3
#define NDEF_TYPE4_TAG 4
#define NDEF_MIFARE_CLASSIC_TAG 101
#define STATUS_CODE_TARGET_LOST 146  // this error code comes from the service

static uint32_t sCheckNdefCurrentSize = 0;
static tNFA_STATUS sCheckNdefStatus =
    0;  // whether tag already contains a NDEF message
static bool sCheckNdefCapable = false;  // whether tag has NDEF capability
static tNFA_HANDLE sNdefTypeHandlerHandle = NFA_HANDLE_INVALID;
static tNFA_INTF_TYPE sCurrentRfInterface = NFA_INTERFACE_ISO_DEP;
#if (NXP_EXTNS == TRUE)
SyncEvent sTransceiveEvent;
static tNFA_INTF_TYPE sCurrentActivatedProtocl = NFC_PROTOCOL_UNKNOWN;
#define DEFAULT_PRESENCE_CHECK_TIMEOUT 375
#else
static SyncEvent sTransceiveEvent;
#endif
static std::vector<uint8_t> sRxDataBuffer;
static tNFA_STATUS sRxDataStatus = NFA_STATUS_OK;
static bool sWaitingForTransceive = false;
static bool sTransceiveRfTimeout = false;
static Mutex sRfInterfaceMutex;
static uint32_t sReadDataLen = 0;
static uint8_t* sReadData = NULL;
static bool sIsReadingNdefMessage = false;
static SyncEvent sReadEvent;
static sem_t sWriteSem;
static sem_t sFormatSem;
static SyncEvent sReconnectEvent;
static sem_t sCheckNdefSem;
static SyncEvent sPresenceCheckEvent;
static sem_t sMakeReadonlySem;
static IntervalTimer sSwitchBackTimer;  // timer used to tell us to switch back
                                        // to ISO_DEP frame interface

static jboolean sWriteOk = JNI_FALSE;
static jboolean sWriteWaitingForComplete = JNI_FALSE;
static bool sFormatOk = false;
static jboolean sConnectOk = JNI_FALSE;
static jboolean sConnectWaitingForComplete = JNI_FALSE;
static bool sGotDeactivate = false;
static uint32_t sCheckNdefMaxSize = 0;
static bool sCheckNdefCardReadOnly = false;
static jboolean sCheckNdefWaitingForComplete = JNI_FALSE;
static bool sIsTagPresent = false;
static tNFA_STATUS sMakeReadonlyStatus = NFA_STATUS_FAILED;
static jboolean sMakeReadonlyWaitingForComplete = JNI_FALSE;
static int sCurrentConnectedTargetType = TARGET_TYPE_UNKNOWN;
static int sCurrentConnectedTargetProtocol = NFC_PROTOCOL_UNKNOWN;
static int sCurrentConnectedHandle = 0;
#if (NXP_EXTNS == TRUE)
void nativeNfcTag_doPresenceCheckResult(tNFA_STATUS status);
void retrySelect(tNFA_INTF_TYPE rfInterface);
#endif
static int sIsoDepPresCheckCnt = 0;
static bool sIsoDepPresCheckAlternate = false;
static int sPresCheckErrCnt = 0;

static int sPresCheckStatus = 0;
static int reSelect(tNFA_INTF_TYPE rfInterface, bool fSwitchIfNeeded);
static bool switchRfInterface(tNFA_INTF_TYPE rfInterface);

/*******************************************************************************
**
** Function:        nativeNfcTag_abortWaits
**
** Description:     Unblock all thread synchronization objects.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_abortWaits() {
  LOG(DEBUG) << StringPrintf("%s", __func__);
  {
    SyncEventGuard g(sReadEvent);
    sReadEvent.notifyOne();
  }
  sem_post(&sWriteSem);
  sem_post(&sFormatSem);
  {
    SyncEventGuard g(sTransceiveEvent);
    sTransceiveEvent.notifyOne();
  }
  {
    SyncEventGuard g(sReconnectEvent);
    sReconnectEvent.notifyOne();
  }

  sem_post(&sCheckNdefSem);
#if (NXP_EXTNS == TRUE)
  nativeNfcTag_doPresenceCheckResult(NFA_STATUS_FAILED);
#else
  {
    SyncEventGuard guard(sPresenceCheckEvent);
    sPresenceCheckEvent.notifyOne();
  }
#endif
  sem_post(&sMakeReadonlySem);
  sCurrentRfInterface = NFA_INTERFACE_ISO_DEP;
  sCurrentActivatedProtocl = NFA_INTERFACE_ISO_DEP;
  sCurrentConnectedTargetType = TARGET_TYPE_UNKNOWN;
  sCurrentConnectedTargetProtocol = NFC_PROTOCOL_UNKNOWN;
#if (NXP_EXTNS == TRUE)
  NfcTag& natTag = NfcTag::getInstance();
  natTag.mCurrentRequestedProtocol = NFC_PROTOCOL_UNKNOWN;
  NfcTagExtns::getInstance().abortTagOperation();
#endif
  sIsoDepPresCheckCnt = 0;
  sPresCheckErrCnt = 0;
  sIsoDepPresCheckAlternate = false;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doReadCompleted
**
** Description:     Receive the completion status of read operation.  Called by
**                  NFA_READ_CPLT_EVT.
**                  status: Status of operation.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_doReadCompleted(tNFA_STATUS status) {
  LOG(DEBUG) << StringPrintf("%s: status=0x%X; is reading=%u", __func__, status,
                             sIsReadingNdefMessage);

  if (sIsReadingNdefMessage == false)
    return;  // not reading NDEF message right now, so just return

  if (status != NFA_STATUS_OK) {
    sReadDataLen = 0;
    if (sReadData) free(sReadData);
    sReadData = NULL;
  }
  SyncEventGuard g(sReadEvent);
  sReadEvent.notifyOne();
}

/*******************************************************************************
 **
 ** Function:        nativeNfcTag_setActivatedRfProtocol
 **
 ** Description:     Set rf Activated Protocol.
 **
 ** Returns:         void
 **
 *******************************************************************************/
void nativeNfcTag_setActivatedRfProtocol(tNFA_INTF_TYPE rfProtocol) {
  sCurrentActivatedProtocl = rfProtocol;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_setRfInterface
**
** Description:     Set rf interface.
**
** Returns:         void
**
*******************************************************************************/
void nativeNfcTag_setRfInterface(tNFA_INTF_TYPE rfInterface) {
  sCurrentRfInterface = rfInterface;
}
#if (NXP_EXTNS == TRUE)
/*******************************************************************************
 **
 ** Function:        nativeNfcTag_setTransceiveFlag
 **
 ** Description:     Set transceive state.
 **
 ** Returns:         None
 **
 *******************************************************************************/
void nativeNfcTag_setTransceiveFlag(bool state) {
  sWaitingForTransceive = state;
}
#endif
/*******************************************************************************
**
** Function:        ndefHandlerCallback
**
** Description:     Receive NDEF-message related events from stack.
**                  event: Event code.
**                  p_data: Event data.
**
** Returns:         None
**
*******************************************************************************/
static void ndefHandlerCallback(tNFA_NDEF_EVT event,
                                tNFA_NDEF_EVT_DATA* eventData) {
  LOG(DEBUG) << StringPrintf("%s: event=%u, eventData=%p", __func__, event,
                             eventData);

  switch (event) {
    case NFA_NDEF_REGISTER_EVT: {
      tNFA_NDEF_REGISTER& ndef_reg = eventData->ndef_reg;
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_NDEF_REGISTER_EVT; status=0x%X; h=0x%X", __func__,
          ndef_reg.status, ndef_reg.ndef_type_handle);
      sNdefTypeHandlerHandle = ndef_reg.ndef_type_handle;
    } break;

    case NFA_NDEF_DATA_EVT: {
      LOG(DEBUG) << StringPrintf("%s: NFA_NDEF_DATA_EVT; data_len = %u",
                                 __func__, eventData->ndef_data.len);
      sReadDataLen = eventData->ndef_data.len;
      sReadData = (uint8_t*)malloc(sReadDataLen);
#if (NXP_EXTNS == TRUE)
      if (sReadData != NULL)
#endif
        memcpy(sReadData, eventData->ndef_data.p_data,
               eventData->ndef_data.len);
    } break;

    default:
      LOG(ERROR) << StringPrintf("%s: Unknown event %u ????", __func__, event);
      break;
  }
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doRead
**
** Description:     Read the NDEF message on the tag.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         NDEF message.
**
*******************************************************************************/
static jbyteArray nativeNfcTag_doRead(JNIEnv* e, jobject) {
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  tNFA_STATUS status = NFA_STATUS_FAILED;
  jbyteArray buf = NULL;

#if (NXP_EXTNS == TRUE)
  if (NfcTag::getInstance().isActivated() == false) {
    LOG(DEBUG) << StringPrintf("%s:tag already deactivated", __func__);
    return buf;
  }
#endif
  sReadDataLen = 0;
  if (sReadData != NULL) {
    free(sReadData);
    sReadData = NULL;
  }

  if (sCheckNdefCurrentSize > 0) {
    {
      SyncEventGuard g(sReadEvent);
      sIsReadingNdefMessage = true;
      status = NFA_RwReadNDef();
      sReadEvent.wait();  // wait for NFA_READ_CPLT_EVT
    }
    sIsReadingNdefMessage = false;

    if (sReadDataLen > 0)  // if stack actually read data from the tag
    {
      LOG(DEBUG) << StringPrintf("%s: read %u bytes", __func__, sReadDataLen);
      buf = e->NewByteArray(sReadDataLen);
      e->SetByteArrayRegion(buf, 0, sReadDataLen, (jbyte*)sReadData);
    }
  } else {
    LOG(DEBUG) << StringPrintf("%s: create empty buffer", __func__);
    sReadDataLen = 0;
    sReadData = (uint8_t*)malloc(1);
    buf = e->NewByteArray(sReadDataLen);
    e->SetByteArrayRegion(buf, 0, sReadDataLen, (jbyte*)sReadData);
  }

  if (sReadData) {
    free(sReadData);
    sReadData = NULL;
  }
  sReadDataLen = 0;

  LOG(DEBUG) << StringPrintf("%s: exit: Status = 0x%X", __func__, status);
  return buf;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doWriteStatus
**
** Description:     Receive the completion status of write operation.  Called
**                  by NFA_WRITE_CPLT_EVT.
**                  isWriteOk: Status of operation.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_doWriteStatus(jboolean isWriteOk) {
  if (sWriteWaitingForComplete != JNI_FALSE) {
    sWriteWaitingForComplete = JNI_FALSE;
    sWriteOk = isWriteOk;
    sem_post(&sWriteSem);
  }
}

/*******************************************************************************
**
** Function:        nativeNfcTag_formatStatus
**
** Description:     Receive the completion status of format operation.  Called
**                  by NFA_FORMAT_CPLT_EVT.
**                  isOk: Status of operation.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_formatStatus(bool isOk) {
  sFormatOk = isOk;
  sem_post(&sFormatSem);
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doWrite
**
** Description:     Write a NDEF message to the tag.
**                  e: JVM environment.
**                  o: Java object.
**                  buf: Contains a NDEF message.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nativeNfcTag_doWrite(JNIEnv* e, jobject, jbyteArray buf) {
  jboolean result = JNI_FALSE;
  tNFA_STATUS status = 0;
  const int maxBufferSize = 1024;
  uint8_t buffer[maxBufferSize] = {0};
  uint32_t curDataSize = 0;

  ScopedByteArrayRO bytes(e, buf);
  uint8_t* p_data = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(
      &bytes[0]));  // TODO: const-ness API bug in NFA_RwWriteNDef!

  LOG(DEBUG) << StringPrintf("%s: enter; len = %zu", __func__, bytes.size());

  /* Create the write semaphore */
  if (sem_init(&sWriteSem, 0, 0) == -1) {
    LOG(ERROR) << StringPrintf("%s: semaphore creation failed (errno=0x%08x)",
                               __func__, errno);
    return JNI_FALSE;
  }

  sWriteWaitingForComplete = JNI_TRUE;
  if (sCheckNdefStatus == NFA_STATUS_FAILED) {
    // if tag does not contain a NDEF message
    // and tag is capable of storing NDEF message
    if (sCheckNdefCapable) {
      LOG(DEBUG) << StringPrintf("%s: try format", __func__);
      if (0 != sem_init(&sFormatSem, 0, 0)) {
        LOG(ERROR) << StringPrintf("%s: semaphore creation failed (errno=0x%08x)",
                __func__, errno);
        return JNI_FALSE;
      }
      sFormatOk = false;
      status = NFA_RwFormatTag();
      if (status != NFA_STATUS_OK) {
        LOG(ERROR) << StringPrintf("%s: can't format mifare classic tag",
                                   __func__);
        sem_destroy(&sFormatSem);
        goto TheEnd;
      }
      if (-1 == sem_wait(&sFormatSem)) {
        LOG(ERROR) << StringPrintf("%s: sem_wait failed \n", __func__);
      }
      sem_destroy(&sFormatSem);
      if (sFormatOk == false)  // if format operation failed
        goto TheEnd;
    }
    LOG(DEBUG) << StringPrintf("%s: try write", __func__);
    status = NFA_RwWriteNDef(p_data, bytes.size());
  } else if (bytes.size() == 0) {
    // if (NXP TagWriter wants to erase tag) then create and write an empty ndef
    // message
    NDEF_MsgInit(buffer, maxBufferSize, &curDataSize);
    status = NDEF_MsgAddRec(buffer, maxBufferSize, &curDataSize, NDEF_TNF_EMPTY,
                            NULL, 0, NULL, 0, NULL, 0);
    LOG(DEBUG) << StringPrintf("%s: create empty ndef msg; status=%u; size=%u",
                               __func__, status, curDataSize);
    status = NFA_RwWriteNDef(buffer, curDataSize);
  } else {
    LOG(DEBUG) << StringPrintf("%s: NFA_RwWriteNDef", __func__);
    status = NFA_RwWriteNDef(p_data, bytes.size());
  }

  if (status != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: write/format error=%d", __func__, status);
#if (NXP_EXTNS == TRUE)
    NativeJniExtns::getInstance().notifyNfcEvent("nativeNfcTag_doWrite",
                                                 (void*)&status);
#endif
    goto TheEnd;
  }

  /* Wait for write completion status */
  sWriteOk = false;
  if (sem_wait(&sWriteSem)) {
    LOG(ERROR) << StringPrintf("%s: wait semaphore (errno=0x%08x)", __func__,
                               errno);
    goto TheEnd;
  }

  result = sWriteOk;

TheEnd:
  /* Destroy semaphore */
  if (sem_destroy(&sWriteSem)) {
    LOG(ERROR) << StringPrintf("%s: failed destroy semaphore (errno=0x%08x)",
                               __func__, errno);
  }
  sWriteWaitingForComplete = JNI_FALSE;
  LOG(DEBUG) << StringPrintf("%s: exit; result=%d", __func__, result);
  return result;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doConnectStatus
**
** Description:     Receive the completion status of connect operation.
**                  isConnectOk: Status of the operation.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_doConnectStatus(jboolean isConnectOk) {
  if (sConnectWaitingForComplete != JNI_FALSE) {
    sConnectWaitingForComplete = JNI_FALSE;
    sConnectOk = isConnectOk;
    SyncEventGuard g(sReconnectEvent);
    sReconnectEvent.notifyOne();
  }
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doDeactivateStatus
**
** Description:     Receive the completion status of deactivate operation.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_doDeactivateStatus(int status) {
  sGotDeactivate = (status == 0);

  SyncEventGuard g(sReconnectEvent);
  sReconnectEvent.notifyOne();
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doConnect
**
** Description:     Connect to the tag in RF field.
**                  e: JVM environment.
**                  o: Java object.
**                  targetHandle: Handle of the tag.
**
** Returns:         Must return NXP status code, which NFC service expects.
**
*******************************************************************************/
static jint nativeNfcTag_doConnect(JNIEnv*, jobject, jint targetHandle) {
  LOG(DEBUG) << StringPrintf("%s: targetHandle = %d", __func__, targetHandle);
  int i = targetHandle;
  NfcTag& natTag = NfcTag::getInstance();
  int retCode = NFCSTATUS_SUCCESS;

  sIsoDepPresCheckCnt = 0;
  sPresCheckErrCnt = 0;
  sIsoDepPresCheckAlternate = false;

  if (i >= NfcTag::MAX_NUM_TECHNOLOGY) {
    LOG(ERROR) << StringPrintf("%s: Handle not found", __func__);
    retCode = NFCSTATUS_FAILED;
    goto TheEnd;
  }

  if (natTag.getActivationState() != NfcTag::Active) {
    LOG(ERROR) << StringPrintf("%s: tag already deactivated", __func__);
    retCode = NFCSTATUS_FAILED;
    goto TheEnd;
  }

  sCurrentConnectedTargetType = natTag.mTechList[i];
  sCurrentConnectedTargetProtocol = natTag.mTechLibNfcTypes[i];
  sCurrentConnectedHandle = targetHandle;
#if (NXP_EXTNS == TRUE)
  LOG(ERROR)<< StringPrintf("%s:  doConnect sCurrentConnectedTargetProtocol %x sCurrentConnectedTargetType %x",
            __func__,sCurrentConnectedTargetProtocol,sCurrentConnectedTargetType);
  natTag.mCurrentRequestedProtocol = sCurrentConnectedTargetProtocol;
  NfcTagExtns::getInstance().setCurrentTargetType(sCurrentConnectedTargetType);
  if(sCurrentConnectedTargetProtocol == NFC_PROTOCOL_T3BT) {
    goto TheEnd;
  }
#endif

  if (sCurrentConnectedTargetProtocol != NFC_PROTOCOL_ISO_DEP &&
      sCurrentConnectedTargetProtocol != NFC_PROTOCOL_MIFARE
    ) {
    LOG(DEBUG) << StringPrintf(
        "%s() Nfc type = %d, do nothing for non ISO_DEP here", __func__,
        sCurrentConnectedTargetProtocol);
    retCode = NFCSTATUS_SUCCESS;
    goto TheEnd;
  }

  if (sCurrentConnectedTargetType == TARGET_TYPE_ISO14443_3A ||
      sCurrentConnectedTargetType == TARGET_TYPE_ISO14443_3B) {
#if (NXP_EXTNS != TRUE)
    if (sCurrentConnectedTargetProtocol != NFC_PROTOCOL_MIFARE)
#endif
    {
      LOG(DEBUG) << StringPrintf(
          "%s: switching to tech: %d need to switch rf intf to frame", __func__,
          sCurrentConnectedTargetType);
      retCode = switchRfInterface(NFA_INTERFACE_FRAME) ? NFA_STATUS_OK
                                                       : NFA_STATUS_FAILED;
    }
  } else if(sCurrentConnectedTargetType == TARGET_TYPE_MIFARE_CLASSIC){
    retCode = switchRfInterface(NFA_INTERFACE_MIFARE) ? NFA_STATUS_OK
                                                     : NFA_STATUS_FAILED;
  } else {
    retCode = switchRfInterface(NFA_INTERFACE_ISO_DEP) ? NFA_STATUS_OK
                                                       : NFA_STATUS_FAILED;
  }

TheEnd:
#if (NXP_EXTNS == TRUE)
  NativeJniExtns::getInstance().notifyNfcEvent("nativeNfcTag_doConnect",
                                               (void*)&retCode);
#endif
  LOG(DEBUG) << StringPrintf("%s: exit 0x%X", __func__, retCode);
  return retCode;
}
/*******************************************************************************
**
** Function:        reSelect
**
** Description:     Deactivates the tag and re-selects it with the specified
**                  rf interface.
**
** Returns:         status code, 0 on success, 1 on failure,
**                  146 (defined in service) on tag lost
**
*******************************************************************************/
static int reSelect(tNFA_INTF_TYPE rfInterface, bool fSwitchIfNeeded) {
  LOG(DEBUG) << StringPrintf("%s: enter; rf intf = %d, current intf = %d",
                             __func__, rfInterface, sCurrentRfInterface);

  sRfInterfaceMutex.lock();

  if (fSwitchIfNeeded && (rfInterface == sCurrentRfInterface)) {
    // already in the requested interface
    sRfInterfaceMutex.unlock();
    return 0;  // success
  }
#if (NXP_EXTNS == TRUE)
  tTagStatus tagStat = NfcTagExtns::TAG_STATUS_STANDARD;
  NfcTagExtns& nfcTagExtns = NfcTagExtns::getInstance();
#endif
  NfcTag& natTag = NfcTag::getInstance();

  tNFA_STATUS status = NFA_STATUS_OK;
  int rVal = 1;

  do {
    // if tag has shutdown, abort this method
    if (NfcTag::getInstance().isNdefDetectionTimedOut()) {
      LOG(DEBUG) << StringPrintf("%s: ndef detection timeout; break", __func__);
      rVal = STATUS_CODE_TARGET_LOST;
      break;
    }
    if ((sCurrentRfInterface == NFA_INTERFACE_FRAME) &&
        (NFC_GetNCIVersion() >= NCI_VERSION_2_0)) {
      {
        SyncEventGuard g3(sReconnectEvent);
#if (NXP_EXTNS == TRUE)
        if (nfcTagExtns.processNonStdTagOperation(
                TAG_API_REQUEST::TAG_RESELECT_API,
                TAG_OPERATION::TAG_HALT_PICC_OPERATION) !=
            NfcTagExtns::TAG_STATUS_SUCCESS) {
          LOG(ERROR) << StringPrintf("%s: TAG_HALT_PICC_OPERATION failed",
                                     __func__);
        }
#else
        if(sCurrentConnectedTargetProtocol == NFA_PROTOCOL_T2T) {
          status = NFA_SendRawFrame(RW_TAG_SLP_REQ, sizeof(RW_TAG_SLP_REQ), 0);
        } else if (sCurrentConnectedTargetProtocol == NFA_PROTOCOL_ISO_DEP) {
          status = NFA_SendRawFrame(RW_DESELECT_REQ,
                                    sizeof(RW_DESELECT_REQ), 0);
        }
#endif
        sReconnectEvent.wait(4);
        if (status != NFA_STATUS_OK) {
          LOG(ERROR) << StringPrintf("%s: send error=%d", __func__, status);
          break;
        }
      }
    }

    {
      SyncEventGuard g(sReconnectEvent);
      gIsTagDeactivating = true;
      sGotDeactivate = false;

#if (NXP_EXTNS == TRUE)
      tagStat = nfcTagExtns.processNonStdTagOperation(
          TAG_API_REQUEST::TAG_RESELECT_API,
          TAG_OPERATION::TAG_DEACTIVATE_OPERATION);
      if (tagStat == NfcTagExtns::TAG_STATUS_FAILED ||
          tagStat == NfcTagExtns::TAG_STATUS_LOST) {
        LOG(ERROR) << StringPrintf("%s: TAG_DEACTIVATE_OPERATION = %d",
                                   __func__, tagStat);
        if (tagStat == NfcTagExtns::TAG_STATUS_LOST)
          rVal = STATUS_CODE_TARGET_LOST;
        break;
      }  // Tag is standard
#else
      LOG(DEBUG) << StringPrintf("%s: deactivate to sleep", __func__);
      if (NFA_STATUS_OK !=
          (status = NFA_Deactivate(TRUE))) {  // deactivate to sleep state
        LOG(ERROR) << StringPrintf("%s: deactivate failed, status = %d",
                                   __func__, status);
        break;
      }
#endif
      if (sReconnectEvent.wait(1000) == false)  // if timeout occurred
      {
        LOG(ERROR) << StringPrintf("%s: timeout waiting for deactivate",
                                   __func__);
      }
    }
#if (NXP_EXTNS == TRUE)
    // If multiprotocol then break, else continue..
    tagStat = nfcTagExtns.processNonStdTagOperation(
        TAG_API_REQUEST::TAG_RESELECT_API,
        TAG_OPERATION::TAG_DEACTIVATE_RSP_OPERATION);
    if (tagStat == NfcTagExtns::TAG_STATUS_LOST) {
      rVal = STATUS_CODE_TARGET_LOST;
      break;
    } else if (tagStat == NfcTagExtns::TAG_STATUS_STANDARD) {
#endif
      if (NfcTag::getInstance().getActivationState() != NfcTag::Sleep) {
        LOG(ERROR) << StringPrintf("%s: tag is not in sleep", __func__);
        rVal = STATUS_CODE_TARGET_LOST;
          break;
      }
#if (NXP_EXTNS == TRUE)
    }
#endif

    gIsTagDeactivating = false;

    {
      SyncEventGuard g2(sReconnectEvent);
      sConnectWaitingForComplete = JNI_TRUE;
      gIsSelectingRfInterface = true;
#if (NXP_EXTNS == TRUE)
      // If status failed break, If cashbee skip NFA_Select
      tagStat = nfcTagExtns.processNonStdTagOperation(
          TAG_API_REQUEST::TAG_RESELECT_API,
          TAG_OPERATION::TAG_RECONNECT_OPERATION);
      if (tagStat == NfcTagExtns::TAG_STATUS_FAILED) break;
      if (tagStat == NfcTagExtns::TAG_STATUS_STANDARD) {
#endif

          LOG(DEBUG) << StringPrintf("%s: select interface %u", __func__,
                                     rfInterface);
          if (NFA_STATUS_OK !=
              (status =
                   NFA_Select(natTag.mTechHandles[sCurrentConnectedHandle],
                              natTag.mTechLibNfcTypes[sCurrentConnectedHandle],
                              rfInterface))) {
          LOG(ERROR) << StringPrintf("%s: NFA_Select failed, status = %d",
                                     __func__, status);
          break;
        }
#if (NXP_EXTNS == TRUE)
      }
#endif
      sConnectOk = false;
      if (sReconnectEvent.wait(1000) == false)  // if timeout occured
      {
        LOG(ERROR) << StringPrintf("%s: timeout waiting for select", __func__);
#if (NXP_EXTNS == TRUE)
        if (nfcTagExtns.processNonStdTagOperation(
                TAG_API_REQUEST::TAG_RESELECT_API,
                TAG_OPERATION::TAG_RECONNECT_FAILED_OPERATION) !=
            NfcTagExtns::TAG_STATUS_STANDARD)
          LOG(DEBUG) << StringPrintf("%s: Non-standard reconnect %u", __func__,
                                     rfInterface);
#endif
        break;
      }
    }
#if (NXP_EXTNS == TRUE)
    retrySelect(rfInterface);
#endif
    /*Retry logic in case of core Generic error while selecting a tag*/
    if(sConnectOk == false) {
      LOG(ERROR) << StringPrintf("%s: waiting for Card to be activated", __func__);
      int retry = 0;
      sConnectWaitingForComplete = JNI_TRUE;
#if (NXP_EXTNS == TRUE)
      if (!nfcTagExtns.isNonStdMFCTagDetected()) {
#endif
        do {
          SyncEventGuard reselectEvent(sReconnectEvent);
          if (sReconnectEvent.wait(500) == false) {  // if timeout occured
            LOG(ERROR) << StringPrintf("%s: timeout ", __func__);
          }
          retry++;
          LOG(ERROR) << StringPrintf(
              "%s: waiting for Card to be activated %x %x", __func__, retry,
              sConnectOk);
        } while (sConnectOk == false && retry < 3);
#if (NXP_EXTNS == TRUE)
      }
      if (NfcTag::getInstance().getNumDiscNtf())
        NfcTag::getInstance().setNumDiscNtf(0);
#endif
    }
    LOG(DEBUG) << StringPrintf("%s: select completed; sConnectOk=%d", __func__,
                               sConnectOk);
    if (NfcTag::getInstance().getActivationState() != NfcTag::Active) {
      LOG(ERROR) << StringPrintf("%s: tag is not active", __func__);
#if (NXP_EXTNS == TRUE)
      NfcTag::getInstance().connectionEventHandler(NFA_DEACTIVATED_EVT, NULL);
#endif
      rVal = STATUS_CODE_TARGET_LOST;
      break;
    }

    if (sConnectOk) {
      rVal = 0;  // success
#if (NXP_EXTNS != TRUE)
      sCurrentRfInterface = rfInterface;
#endif
    } else {
      rVal = 1;
    }
  } while (0);

  sConnectWaitingForComplete = JNI_FALSE;
  gIsTagDeactivating = false;
  gIsSelectingRfInterface = false;
#if (NXP_EXTNS == TRUE)
  if (nfcTagExtns.processNonStdTagOperation(
          TAG_API_REQUEST::TAG_RESELECT_API,
          TAG_OPERATION::TAG_CLEAR_STATE_OPERATION) ==
      NfcTagExtns::TAG_STATUS_SUCCESS)
    LOG(DEBUG) << StringPrintf("%s: clear Non-std tag state; status=%d",
                               __func__, rVal);
#endif
  sRfInterfaceMutex.unlock();
  LOG(DEBUG) << StringPrintf("%s: exit; status=%d", __func__, rVal);
  return rVal;
}

#if (NXP_EXTNS == TRUE)
/*******************************************************************************
**
** Function:        retrySelect
**
** Description:     do Tag Reconnect , if previous Select is not success
**                  after MFC classic transceive failed.
**
** Returns:         None
**
*******************************************************************************/
void retrySelect(tNFA_INTF_TYPE rfInterface) {
  NfcTag& natTag = NfcTag::getInstance();
  NfcTagExtns& nfcTagExtns = NfcTagExtns::getInstance();
  uint8_t retryCnt = 0x00;
  uint8_t maxRetryCOunt = 0x02;
  while (!sConnectOk && nfcTagExtns.isMfcTransFailed() &&
         retryCnt < maxRetryCOunt) {
    retryCnt++;
    SyncEventGuard g2(sReconnectEvent);
    sConnectWaitingForComplete = JNI_TRUE;
    gIsSelectingRfInterface = true;
    if (retryCnt == maxRetryCOunt) {
      nfcTagExtns.resetMfcTransceiveFlag();
    }
    if ((NFA_Select(natTag.mTechHandles[sCurrentConnectedHandle],
                    natTag.mTechLibNfcTypes[sCurrentConnectedHandle],
                    rfInterface)) != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: NFA_Select failed.", __func__);
      break;
    }
    if (sReconnectEvent.wait(100) == false) {
      break;
    }
  }
}
#endif
/*******************************************************************************
**
** Function:        switchRfInterface
**
** Description:     Switch controller's RF interface to frame, ISO-DEP, or
*NFC-DEP.
**                  rfInterface: Type of RF interface.
**
** Returns:         True if ok.
**
*******************************************************************************/
static bool switchRfInterface(tNFA_INTF_TYPE rfInterface) {
  NfcTag& natTag = NfcTag::getInstance();

  if (sCurrentConnectedTargetProtocol != NFC_PROTOCOL_ISO_DEP &&
      sCurrentConnectedTargetProtocol != NFC_PROTOCOL_MIFARE) {
    LOG(DEBUG) << StringPrintf("%s: protocol: %d not ISO_DEP, do nothing",
                               __func__, natTag.mTechLibNfcTypes[0]);
    return true;
  }

  LOG(DEBUG) << StringPrintf("%s: new rf intf = %d, cur rf intf = %d", __func__,
                             rfInterface, sCurrentRfInterface);

  return (0 == reSelect(rfInterface, true));
}
#if(NXP_EXTNS == TRUE && NXP_SRD == TRUE)
/*******************************************************************************
**
** Function:        nativeNfcTag_getSrdState
**
** Description:     Get SRD state.
**
** Returns:         SRD state.
**
*******************************************************************************/
static jint nativeNfcTag_getSrdState(JNIEnv*, jobject) {
return SecureDigitization::getInstance().getSrdState();
}
#endif
/*******************************************************************************
**
** Function:        nativeNfcTag_doReconnect
**
** Description:     Re-connect to the tag in RF field.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Status code.
**
*******************************************************************************/
static jint nativeNfcTag_doReconnect(JNIEnv*, jobject) {
LOG(DEBUG) << StringPrintf("%s: enter", __func__);
int retCode = NFCSTATUS_SUCCESS;
NfcTag& natTag = NfcTag::getInstance();

if (natTag.getActivationState() != NfcTag::Active) {
    LOG(ERROR) << StringPrintf("%s: tag already deactivated", __func__);
    retCode = NFCSTATUS_FAILED;
    goto TheEnd;
  }

  // special case for Kovio
  if (sCurrentConnectedTargetProtocol == TARGET_TYPE_KOVIO_BARCODE) {
    LOG(DEBUG) << StringPrintf("%s: fake out reconnect for Kovio", __func__);
    goto TheEnd;
  }

  // this is only supported for type 2 or 4 (ISO_DEP) tags
  if (sCurrentConnectedTargetProtocol == NFA_PROTOCOL_ISO_DEP)
    retCode = reSelect(NFA_INTERFACE_ISO_DEP, false);
  else if (sCurrentConnectedTargetProtocol == NFA_PROTOCOL_T2T)
    retCode = reSelect(NFA_INTERFACE_FRAME, false);
  else if (sCurrentConnectedTargetProtocol == NFC_PROTOCOL_MIFARE)
    retCode = reSelect(NFA_INTERFACE_MIFARE, false);

TheEnd:
  LOG(DEBUG) << StringPrintf("%s: exit 0x%X", __func__, retCode);
  return retCode;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doHandleReconnect
**
** Description:     Re-connect to the tag in RF field.
**                  e: JVM environment.
**                  o: Java object.
**                  targetHandle: Handle of the tag.
**
** Returns:         Status code.
**
*******************************************************************************/
static jint nativeNfcTag_doHandleReconnect(JNIEnv* e, jobject o,
                                           jint targetHandle) {
  LOG(DEBUG) << StringPrintf("%s: targetHandle = %d", __func__, targetHandle);
  return nativeNfcTag_doConnect(e, o, targetHandle);
}
#if (NXP_EXTNS == TRUE)
/*******************************************************************************
**
** Function:        nativeNfcTag_safeDisconnect
**
** Description:     Deactivate the RF field only if NFC stack is up and running
**
** Returns:         True if ok.
**
*******************************************************************************/
tNFA_STATUS nativeNfcTag_safeDisconnect() {
  tNFA_STATUS nfaStat = NFA_STATUS_OK;
  if(nfcManager_isNfcDisabling() || !nfcManager_isNfcActive()) {
    LOG(ERROR) << StringPrintf("%s: Nfc is Off", __func__);
  } else {
    nativeNfcTag_acquireRfInterfaceMutexLock();
    NfcTag::getInstance().setActive(false);
    nfaStat = NFA_Deactivate(FALSE);
    nativeNfcTag_releaseRfInterfaceMutexLock();
  }
  return nfaStat;
}

#endif
/*******************************************************************************
**
** Function:        nativeNfcTag_doDisconnect
**
** Description:     Deactivate the RF field.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if ok.
**
*******************************************************************************/
jboolean nativeNfcTag_doDisconnect(JNIEnv*, jobject) {
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  tNFA_STATUS nfaStat = NFA_STATUS_OK;

  NfcTag::getInstance().resetAllTransceiveTimeouts();

  if (NfcTag::getInstance().getActivationState() != NfcTag::Active) {
    LOG(WARNING) << StringPrintf("%s: tag already deactivated", __func__);
    goto TheEnd;
  }
#if (NXP_EXTNS == TRUE)
  nfaStat = nativeNfcTag_safeDisconnect();
#else
  nfaStat = NFA_Deactivate(FALSE);
#endif
  if (nfaStat != NFA_STATUS_OK)
    LOG(ERROR) << StringPrintf("%s: deactivate failed; error=0x%X", __func__,
                               nfaStat);
TheEnd:
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
  return (nfaStat == NFA_STATUS_OK) ? JNI_TRUE : JNI_FALSE;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doTransceiveStatus
**
** Description:     Receive the completion status of transceive operation.
**                  status: operation status.
**                  buf: Contains tag's response.
**                  bufLen: Length of buffer.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_doTransceiveStatus(tNFA_STATUS status, uint8_t* buf,
                                     uint32_t bufLen) {
  SyncEventGuard g(sTransceiveEvent);
  LOG(DEBUG) << StringPrintf("%s: data len=%d", __func__, bufLen);

  if (!sWaitingForTransceive) {
    LOG(ERROR) << StringPrintf("%s: drop data", __func__);
    return;
  }
  sRxDataStatus = status;
  if (sRxDataStatus == NFA_STATUS_OK || sRxDataStatus == NFC_STATUS_CONTINUE)
    sRxDataBuffer.insert(sRxDataBuffer.end(), buf, buf + bufLen);

  if (sRxDataStatus == NFA_STATUS_OK) sTransceiveEvent.notifyOne();

}
#if(NXP_EXTNS == TRUE)
void nativeNfcTag_notifyRfTimeout(tNFA_STATUS status) {
#else
void nativeNfcTag_notifyRfTimeout() {
#endif
  SyncEventGuard g(sTransceiveEvent);
  LOG(DEBUG) << StringPrintf("%s: waiting for transceive: %d", __func__,
                             sWaitingForTransceive);
  if (!sWaitingForTransceive) return;

#if(NXP_EXTNS == TRUE)
  if(status != NFC_STATUS_RF_PROTOCOL_ERR) sTransceiveRfTimeout = true;
#else
  sTransceiveRfTimeout = true;
#endif

  sTransceiveEvent.notifyOne();
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doTransceive
**
** Description:     Send raw data to the tag; receive tag's response.
**                  e: JVM environment.
**                  o: Java object.
**                  raw: Not used.
**                  statusTargetLost: Whether tag responds or times out.
**
** Returns:         Response from tag.
**
*******************************************************************************/
static jbyteArray nativeNfcTag_doTransceive(JNIEnv* e, jobject o,
                                            jbyteArray data, jboolean raw,
                                            jintArray statusTargetLost) {
  int timeout =
      NfcTag::getInstance().getTransceiveTimeout(sCurrentConnectedTargetType);
  LOG(DEBUG) << StringPrintf("%s: enter; raw=%u; timeout = %d", __func__, raw,
                             timeout);

  bool waitOk = false;
  bool isNack = false;
  jint* targetLost = NULL;
  tNFA_STATUS status;

  if (NfcTag::getInstance().getActivationState() != NfcTag::Active) {
    if (statusTargetLost) {
      targetLost = e->GetIntArrayElements(statusTargetLost, 0);
      if (targetLost)
        *targetLost = 1;  // causes NFC service to throw TagLostException
      e->ReleaseIntArrayElements(statusTargetLost, targetLost, 0);
    }
    LOG(DEBUG) << StringPrintf("%s: tag not active", __func__);
    return NULL;
  }

  NfcTag& natTag = NfcTag::getInstance();
  NfcTagExtns& nfcTagExtns = NfcTagExtns::getInstance();

  // get input buffer and length from java call
  ScopedByteArrayRO bytes(e, data);
  uint8_t* buf = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(
      &bytes[0]));  // TODO: API bug; NFA_SendRawFrame should take const*!
  size_t bufLen = bytes.size();

  if (statusTargetLost) {
    targetLost = e->GetIntArrayElements(statusTargetLost, 0);
    if (targetLost) *targetLost = 0;  // success, tag is still present
  }

  sSwitchBackTimer.kill();
  ScopedLocalRef<jbyteArray> result(e, NULL);
  do {
    {
      SyncEventGuard g(sTransceiveEvent);
      sTransceiveRfTimeout = false;
      sWaitingForTransceive = true;
      sRxDataStatus = NFA_STATUS_OK;
      sRxDataBuffer.clear();

      status = NFA_SendRawFrame(buf, bufLen,
                                NFA_DM_DEFAULT_PRESENCE_CHECK_START_DELAY);
      if (status != NFA_STATUS_OK) {
        LOG(ERROR) << StringPrintf("%s: fail send; error=%d", __func__, status);
        break;
      }
      waitOk = sTransceiveEvent.wait(timeout);
    }

    if (waitOk == false || sTransceiveRfTimeout)  // if timeout occurred
    {
      LOG(ERROR) << StringPrintf("%s: wait response timeout", __func__);
      if (targetLost)
        *targetLost = 1;  // causes NFC service to throw TagLostException
      break;
    }

    if (NfcTag::getInstance().getActivationState() != NfcTag::Active) {
      LOG(ERROR) << StringPrintf("%s: already deactivated", __func__);
      if (targetLost)
        *targetLost = 1;  // causes NFC service to throw TagLostException
      break;
    }

    LOG(DEBUG) << StringPrintf("%s: response %zu bytes", __func__,
                               sRxDataBuffer.size());

    if (
#if (NXP_EXTNS == TRUE && NXP_SRD == TRUE)
        (SecureDigitization::getInstance().getSrdState() != 0x01) &&
#endif
        (natTag.getProtocol() == NFA_PROTOCOL_T2T) &&
        natTag.isT2tNackResponse(sRxDataBuffer.data(), sRxDataBuffer.size())) {
      isNack = true;
    }

    if (sRxDataBuffer.size() > 0) {
      if (isNack) {
        // Some Mifare Ultralight C tags enter the HALT state after it
        // responds with a NACK.  Need to perform a "reconnect" operation
        // to wake it.
        LOG(DEBUG) << StringPrintf("%s: try reconnect", __func__);
        nativeNfcTag_doReconnect(NULL, NULL);
        LOG(DEBUG) << StringPrintf("%s: reconnect finish", __func__);
      } else if (sCurrentConnectedTargetProtocol == NFC_PROTOCOL_MIFARE) {
        uint32_t transDataLen = static_cast<uint32_t>(sRxDataBuffer.size());
        uint8_t* transData = (uint8_t*)sRxDataBuffer.data();
        bool doReconnect = false;

        doReconnect =
            ((transDataLen == 1) && (transData[0] != 0x00)) ? true : false;

        if (doReconnect) {
#if (NXP_EXTNS == TRUE)
          if (nfcTagExtns.processNonStdTagOperation(
                  TAG_API_REQUEST::TAG_DO_TRANSCEIVE_API,
                  TAG_OPERATION::TAG_RECONNECT_OPERATION) !=
              NfcTagExtns::TAG_STATUS_SUCCESS) {
            LOG(ERROR) << StringPrintf(
                "%s: processNonStdTagOperation Operation failed", __func__);
          }
#endif
          nativeNfcTag_doReconnect(e, o);
        }
         else {
          if (transDataLen != 0) {
            result.reset(e->NewByteArray(transDataLen));
            if (result.get() != NULL) {
              e->SetByteArrayRegion(result.get(), 0, transDataLen,
                                  (const jbyte*)transData);
            } else
              LOG(ERROR) << StringPrintf("%s: Failed to allocate java byte array",
                                       __func__);
          }
        }
      } else {
        // marshall data to java for return
        result.reset(e->NewByteArray(sRxDataBuffer.size()));
        if (result.get() != NULL) {
          e->SetByteArrayRegion(result.get(), 0, sRxDataBuffer.size(),
                                (const jbyte*)sRxDataBuffer.data());
        } else
          LOG(ERROR) << StringPrintf("%s: Failed to allocate java byte array",
                                     __func__);
      }  // else a nack is treated as a transceive failure to the upper layers

      sRxDataBuffer.clear();
    }
  } while (0);

  sWaitingForTransceive = false;
  if (targetLost) e->ReleaseIntArrayElements(statusTargetLost, targetLost, 0);

  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
  return result.release();
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doGetNdefType
**
** Description:     Retrieve the type of tag.
**                  e: JVM environment.
**                  o: Java object.
**                  libnfcType: Type of tag represented by JNI.
**                  javaType: Not used.
**
** Returns:         Type of tag represented by NFC Service.
**
*******************************************************************************/
static jint nativeNfcTag_doGetNdefType(JNIEnv*, jobject, jint libnfcType,
                                       jint javaType) {
  LOG(DEBUG) << StringPrintf("%s: enter; libnfc type=%d; java type=%d",
                             __func__, libnfcType, javaType);
  jint ndefType = NDEF_UNKNOWN_TYPE;

  // For NFA, libnfcType is mapped to the protocol value received
  // in the NFA_ACTIVATED_EVT and NFA_DISC_RESULT_EVT event.
  if (NFA_PROTOCOL_T1T == libnfcType) {
    ndefType = NDEF_TYPE1_TAG;
  } else if (NFA_PROTOCOL_T2T == libnfcType) {
    ndefType = NDEF_TYPE2_TAG;
  } else if (NFA_PROTOCOL_T3T == libnfcType) {
    ndefType = NDEF_TYPE3_TAG;
  } else if (NFA_PROTOCOL_ISO_DEP == libnfcType) {
    ndefType = NDEF_TYPE4_TAG;
  } else if (NFC_PROTOCOL_MIFARE == libnfcType) {
    ndefType = NDEF_MIFARE_CLASSIC_TAG;
  } else {
    /* NFA_PROTOCOL_T5T, NFA_PROTOCOL_INVALID and others */
    ndefType = NDEF_UNKNOWN_TYPE;
  }
  LOG(DEBUG) << StringPrintf("%s: exit; ndef type=%d", __func__, ndefType);
  return ndefType;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doCheckNdefResult
**
** Description:     Receive the result of checking whether the tag contains a
*NDEF
**                  message.  Called by the NFA_NDEF_DETECT_EVT.
**                  status: Status of the operation.
**                  maxSize: Maximum size of NDEF message.
**                  currentSize: Current size of NDEF message.
**                  flags: Indicate various states.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_doCheckNdefResult(tNFA_STATUS status, uint32_t maxSize,
                                    uint32_t currentSize, uint8_t flags) {
  // this function's flags parameter is defined using the following macros
  // in nfc/include/rw_api.h;
  //#define RW_NDEF_FL_READ_ONLY  0x01    /* Tag is read only              */
  //#define RW_NDEF_FL_FORMATED   0x02    /* Tag formated for NDEF         */
  //#define RW_NDEF_FL_SUPPORTED  0x04    /* NDEF supported by the tag     */
  //#define RW_NDEF_FL_UNKNOWN    0x08    /* Unable to find if tag is ndef
  // capable/formated/read only */ #define RW_NDEF_FL_FORMATABLE 0x10    /* Tag
  // supports format operation */

  if (!sCheckNdefWaitingForComplete) {
    LOG(ERROR) << StringPrintf("%s: not waiting", __func__);
    return;
  }

  if (flags & RW_NDEF_FL_READ_ONLY)
    LOG(DEBUG) << StringPrintf("%s: flag read-only", __func__);
  if (flags & RW_NDEF_FL_FORMATED)
    LOG(DEBUG) << StringPrintf("%s: flag formatted for ndef", __func__);
  if (flags & RW_NDEF_FL_SUPPORTED)
    LOG(DEBUG) << StringPrintf("%s: flag ndef supported", __func__);
  if (flags & RW_NDEF_FL_UNKNOWN)
    LOG(DEBUG) << StringPrintf("%s: flag all unknown", __func__);
  if (flags & RW_NDEF_FL_FORMATABLE)
    LOG(DEBUG) << StringPrintf("%s: flag formattable", __func__);

  sCheckNdefWaitingForComplete = JNI_FALSE;
  sCheckNdefStatus = status;
  if (sCheckNdefStatus != NFA_STATUS_OK &&
      sCheckNdefStatus != NFA_STATUS_TIMEOUT)
    sCheckNdefStatus = NFA_STATUS_FAILED;
  sCheckNdefCapable = false;  // assume tag is NOT ndef capable
  if (sCheckNdefStatus == NFA_STATUS_OK) {
    // NDEF content is on the tag
    sCheckNdefMaxSize = maxSize;
    sCheckNdefCurrentSize = currentSize;
    sCheckNdefCardReadOnly = flags & RW_NDEF_FL_READ_ONLY;
    sCheckNdefCapable = true;
  } else if (sCheckNdefStatus == NFA_STATUS_FAILED) {
    // no NDEF content on the tag
    sCheckNdefMaxSize = 0;
    sCheckNdefCurrentSize = 0;
    sCheckNdefCardReadOnly = flags & RW_NDEF_FL_READ_ONLY;
    if ((flags & RW_NDEF_FL_UNKNOWN) == 0)  // if stack understands the tag
    {
      if (flags & RW_NDEF_FL_SUPPORTED)  // if tag is ndef capable
        sCheckNdefCapable = true;
    }
  } else {
    LOG(ERROR) << StringPrintf("%s: unknown status=0x%X", __func__, status);
    sCheckNdefMaxSize = 0;
    sCheckNdefCurrentSize = 0;
    sCheckNdefCardReadOnly = false;
  }
  sem_post(&sCheckNdefSem);
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doCheckNdef
**
** Description:     Does the tag contain a NDEF message?
**                  e: JVM environment.
**                  o: Java object.
**                  ndefInfo: NDEF info.
**
** Returns:         Status code; 0 is success.
**
*******************************************************************************/
static jint nativeNfcTag_doCheckNdef(JNIEnv* e, jobject o, jintArray ndefInfo) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  jint* ndef = NULL;
  LOG(DEBUG) << StringPrintf("%s: enter ", __func__);
#if (NXP_EXTNS == TRUE)
  // If tag is already deactivated.
  if (NfcTag::getInstance().isActivated() == false) {
    LOG(DEBUG) << StringPrintf("%s: tag already deactivated...", __func__);
    return NFA_STATUS_FAILED;
  }
  int handle = sCurrentConnectedHandle;
  if (NfcTagExtns::getInstance().processNonStdTagOperation(
          TAG_API_REQUEST::TAG_CHECK_NDEF_API, TAG_OPERATION::TAG_SKIP_NDEF) !=
      NfcTagExtns::TAG_STATUS_STANDARD) {
    ndef = e->GetIntArrayElements (ndefInfo, 0);
    ndef[0] = 0;
    ndef[1] = NDEF_MODE_READ_ONLY;
    e->ReleaseIntArrayElements (ndefInfo, ndef, 0);
    return NFA_STATUS_FAILED;
  }
#endif

  // special case for Kovio
  if (sCurrentConnectedTargetProtocol == TARGET_TYPE_KOVIO_BARCODE) {
    LOG(DEBUG) << StringPrintf("%s: Kovio tag, no NDEF", __func__);
    ndef = e->GetIntArrayElements(ndefInfo, 0);
    ndef[0] = 0;
    ndef[1] = NDEF_MODE_READ_ONLY;
    e->ReleaseIntArrayElements(ndefInfo, ndef, 0);
    return NFA_STATUS_FAILED;
  } else if (sCurrentConnectedTargetProtocol == NFC_PROTOCOL_MIFARE) {
    nativeNfcTag_doReconnect(e, o);
  }

  /* Create the write semaphore */
  if (sem_init(&sCheckNdefSem, 0, 0) == -1) {
    LOG(ERROR) << StringPrintf(
        "%s: Check NDEF semaphore creation failed (errno=0x%08x)", __func__,
        errno);
    return JNI_FALSE;
  }

  if (NfcTag::getInstance().getActivationState() != NfcTag::Active) {
    LOG(ERROR) << StringPrintf("%s: tag already deactivated", __func__);
    goto TheEnd;
  }

  LOG(DEBUG) << StringPrintf("%s: try NFA_RwDetectNDef", __func__);
  sCheckNdefWaitingForComplete = JNI_TRUE;

#if (NXP_EXTNS == TRUE)
  LOG(DEBUG) << StringPrintf(
      "%s: NfcTag::getInstance ().mTechLibNfcTypes[%d]=%d"
      "sCurrentConnectedTargetProtocol = 0x%x",
      __func__, handle, NfcTag::getInstance().mTechLibNfcTypes[handle],
      sCurrentConnectedTargetProtocol);
#endif

  status = NFA_RwDetectNDef();

  if (status != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: NFA_RwDetectNDef failed, status = 0x%X",
                               __func__, status);
    goto TheEnd;
  }

  /* Wait for check NDEF completion status */
  if (sem_wait(&sCheckNdefSem)) {
    LOG(ERROR) << StringPrintf(
        "%s: Failed to wait for check NDEF semaphore (errno=0x%08x)", __func__,
        errno);
    goto TheEnd;
  }

  if (sCheckNdefStatus == NFA_STATUS_OK) {
    // stack found a NDEF message on the tag
    ndef = e->GetIntArrayElements(ndefInfo, 0);
    if (NfcTag::getInstance().getProtocol() == NFA_PROTOCOL_T1T)
      ndef[0] = NfcTag::getInstance().getT1tMaxMessageSize();
    else
      ndef[0] = sCheckNdefMaxSize;
    if (sCheckNdefCardReadOnly)
      ndef[1] = NDEF_MODE_READ_ONLY;
    else
      ndef[1] = NDEF_MODE_READ_WRITE;
    e->ReleaseIntArrayElements(ndefInfo, ndef, 0);
    status = NFA_STATUS_OK;
  } else if (sCheckNdefStatus == NFA_STATUS_FAILED) {
    // stack did not find a NDEF message on the tag;
    ndef = e->GetIntArrayElements(ndefInfo, 0);
    if (NfcTag::getInstance().getProtocol() == NFA_PROTOCOL_T1T)
      ndef[0] = NfcTag::getInstance().getT1tMaxMessageSize();
    else
      ndef[0] = sCheckNdefMaxSize;
    if (sCheckNdefCardReadOnly)
      ndef[1] = NDEF_MODE_READ_ONLY;
    else
      ndef[1] = NDEF_MODE_READ_WRITE;
    e->ReleaseIntArrayElements(ndefInfo, ndef, 0);
    status = NFA_STATUS_FAILED;
  } else {
    LOG(DEBUG) << StringPrintf("%s: unknown status 0x%X", __func__,
                               sCheckNdefStatus);
    status = sCheckNdefStatus;
  }

  /* Reconnect Mifare Classic Tag for furture use */
  if (sCurrentConnectedTargetProtocol == NFC_PROTOCOL_MIFARE) {
    nativeNfcTag_doReconnect(e, o);
  }

TheEnd:
  /* Destroy semaphore */
  if (sem_destroy(&sCheckNdefSem)) {
    LOG(ERROR) << StringPrintf(
        "%s: Failed to destroy check NDEF semaphore (errno=0x%08x)", __func__,
        errno);
  }
  sCheckNdefWaitingForComplete = JNI_FALSE;
  LOG(DEBUG) << StringPrintf("%s: exit; status=0x%X", __func__, status);
  return status;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_resetPresenceCheck
**
** Description:     Reset variables related to presence-check.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_resetPresenceCheck() {
  sIsTagPresent = false;
  sIsoDepPresCheckCnt = 0;
  sPresCheckErrCnt = 0;
  sIsoDepPresCheckAlternate = false;
  sPresCheckStatus = 0;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doPresenceCheckResult
**
** Description:     Receive the result of presence-check.
**                  status: Result of presence-check.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_doPresenceCheckResult(tNFA_STATUS status) {
  SyncEventGuard guard(sPresenceCheckEvent);
  sIsTagPresent = status == NFA_STATUS_OK;
  sPresCheckStatus = status;
  sPresenceCheckEvent.notifyOne();
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doPresenceCheck
**
** Description:     Check if the tag is in the RF field.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if tag is in RF field.
**
*******************************************************************************/
static jboolean nativeNfcTag_doPresenceCheck(JNIEnv*, jobject) {
  LOG(DEBUG) << StringPrintf("%s", __func__);
  tNFA_STATUS status = NFA_STATUS_OK;
  bool isPresent = false;

  // Special case for Kovio.  The deactivation would have already occurred
  // but was ignored so that normal tag opertions could complete.  Now we
  // want to process as if the deactivate just happened.
  if (sCurrentConnectedTargetProtocol == TARGET_TYPE_KOVIO_BARCODE) {
    LOG(DEBUG) << StringPrintf("%s: Kovio, force deactivate handling",
                               __func__);
    tNFA_DEACTIVATED deactivated = {NFA_DEACTIVATE_TYPE_IDLE,
                                    NCI_DEACTIVATE_REASON_DH_REQ};
    {
      SyncEventGuard g(gDeactivatedEvent);
      gActivated = false;  // guard this variable from multi-threaded access
      gDeactivatedEvent.notifyOne();
    }

    NfcTag::getInstance().setDeactivationState(deactivated);
    nativeNfcTag_resetPresenceCheck();
    NfcTag::getInstance().connectionEventHandler(NFA_DEACTIVATED_EVT, NULL);
    nativeNfcTag_abortWaits();
    NfcTag::getInstance().abort();

    return JNI_FALSE;
  }

  if (nfcManager_isNfcActive() == false) {
    LOG(DEBUG) << StringPrintf("%s: NFC is no longer active.", __func__);
    return JNI_FALSE;
  }

  if (!sRfInterfaceMutex.tryLock()) {
    LOG(DEBUG) << StringPrintf(
        "%s: tag is being reSelected assume it is present", __func__);
#if (NXP_EXTNS == TRUE)
    if (nfcManager_isNfcActive() == false)
      return JNI_FALSE;
#endif
    return JNI_TRUE;
  }

  sRfInterfaceMutex.unlock();

  if (NfcTag::getInstance().isActivated() == false) {
    LOG(DEBUG) << StringPrintf("%s: tag already deactivated", __func__);
    return JNI_FALSE;
  }

#if (NXP_EXTNS == TRUE)
  if(NfcTag::getInstance ().mTechLibNfcTypes[0] == NFA_PROTOCOL_T3BT) {
    uint8_t T3btPresenceCheckCmd[] = {0xB2};
    uint8_t bufLen = 0x00;
    bool waitOk = false;

    SyncEventGuard g (sTransceiveEvent);
    sTransceiveRfTimeout = false;
    sWaitingForTransceive = true;
    bufLen = (uint8_t) sizeof(T3btPresenceCheckCmd);
    status = NFA_SendRawFrame (T3btPresenceCheckCmd, bufLen, NFA_DM_DEFAULT_PRESENCE_CHECK_START_DELAY);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail send; error=%d", __func__, status);
    } else
      waitOk = sTransceiveEvent.wait (NfcTag::getInstance().getTransceiveTimeout(TARGET_TYPE_ISO14443_3B));
    if (waitOk == false || sTransceiveRfTimeout) { //if timeout occurred
      return JNI_FALSE;;
    } else {
      return JNI_TRUE;
    }
  }
#endif

  {
    SyncEventGuard guard(sPresenceCheckEvent);
    tNFA_RW_PRES_CHK_OPTION method =
        NfcTag::getInstance().getPresenceCheckAlgorithm();

    if (sCurrentConnectedTargetProtocol == NFC_PROTOCOL_ISO_DEP) {
      if (method == NFA_RW_PRES_CHK_ISO_DEP_NAK) {
        sIsoDepPresCheckCnt++;
      }
      if (sIsoDepPresCheckAlternate == true) {
        method = NFA_RW_PRES_CHK_I_BLOCK;
      }
    }

#if (NXP_EXTNS == TRUE)
    if((sCurrentConnectedTargetProtocol == NFA_PROTOCOL_T2T) &&
       (sCurrentRfInterface == NFA_INTERFACE_FRAME) &&
       (!NfcTag::getInstance().isMifareUltralight())) {
       /* Only applicable for Type2 tag which has SAK value other than 0
        (as defined in NFC Digital Protocol, section 4.8.2(SEL_RES)) */
      uint8_t RW_TAG_SLP_REQ[] = {0x50, 0x00};
      status = NFA_SendRawFrame(RW_TAG_SLP_REQ, sizeof(RW_TAG_SLP_REQ), 0);
      if (status != NFA_STATUS_OK) {
        LOG(ERROR) << StringPrintf(
            "%s: failed to send RW_TAG_SLP_REQ, status=%d", __func__, status);
      }
    }
#endif
    status = NFA_RwPresenceCheck(method);
    if (status == NFA_STATUS_OK) {
      isPresent = sPresenceCheckEvent.wait(2000);

      LOG(DEBUG) << StringPrintf("%s(%d): isPresent = %d", __FUNCTION__,
                                 __LINE__, isPresent);

      if (!sIsTagPresent &&
          (((sCurrentConnectedTargetProtocol == NFC_PROTOCOL_ISO_DEP) &&
            (method == NFA_RW_PRES_CHK_ISO_DEP_NAK)) ||
           ((sPresCheckStatus == NFA_STATUS_RF_FRAME_CORRUPTED) &&
            ((sCurrentConnectedTargetProtocol == NFC_PROTOCOL_T1T) ||
             (sCurrentConnectedTargetProtocol == NFC_PROTOCOL_T2T) ||
             (sCurrentConnectedTargetProtocol == NFC_PROTOCOL_T5T))) ||
           (sCurrentConnectedTargetProtocol == NFC_PROTOCOL_T3T))) {
        sPresCheckErrCnt++;

        int retryCount =
            NfcConfig::getUnsigned(NAME_PRESENCE_CHECK_RETRY_COUNT,
                                   DEFAULT_PRESENCE_CHECK_RETRY_COUNT);
        while (sPresCheckErrCnt <= retryCount) {
          LOG(DEBUG) << StringPrintf(
              "%s(%d): pres check failed, try again (attempt #%d/%d)",
              __FUNCTION__, __LINE__, sPresCheckErrCnt, retryCount);

          status = NFA_RwPresenceCheck(method);

          if (status == NFA_STATUS_OK) {
            isPresent = sPresenceCheckEvent.wait(2000);
            LOG(DEBUG) << StringPrintf("%s(%d): isPresent = %d", __FUNCTION__,
                                       __LINE__, isPresent);

            if (!isPresent) {
              break;
            } else if (isPresent && sIsTagPresent) {
              sPresCheckErrCnt = 0;
              break;
            } else {
              sPresCheckErrCnt++;
            }
          }
        }
      }

      if (isPresent && (sIsoDepPresCheckCnt == 1) && !sIsTagPresent) {
        LOG(DEBUG) << StringPrintf(
            "%s(%d): Try alternate method in case tag does not support RNAK",
            __FUNCTION__, __LINE__);

        method = NFA_RW_PRES_CHK_I_BLOCK;
        sIsoDepPresCheckAlternate = true;
        status = NFA_RwPresenceCheck(method);

        if (status == NFA_STATUS_OK) {
          isPresent = sPresenceCheckEvent.wait(2000);
          LOG(DEBUG) << StringPrintf("%s(%d): isPresent = %d", __FUNCTION__,
                                     __LINE__, isPresent);
        }
      }

      isPresent = isPresent && sIsTagPresent;
    }
  }

  if (!isPresent) {
    LOG(DEBUG) << StringPrintf("%s: tag absent", __func__);

    nativeNfcTag_resetPresenceCheck();
  }

  return isPresent ? JNI_TRUE : JNI_FALSE;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doIsNdefFormatable
**
** Description:     Can tag be formatted to store NDEF message?
**                  e: JVM environment.
**                  o: Java object.
**                  libNfcType: Type of tag.
**                  uidBytes: Tag's unique ID.
**                  pollBytes: Data from activation.
**                  actBytes: Data from activation.
**
** Returns:         True if formattable.
**
*******************************************************************************/
static jboolean nativeNfcTag_doIsNdefFormatable(JNIEnv* e, jobject o,
                                                jint /*libNfcType*/, jbyteArray,
                                                jbyteArray, jbyteArray) {
  jboolean isFormattable = JNI_FALSE;
  tNFC_PROTOCOL protocol = NfcTag::getInstance().getProtocol();
  if (NFA_PROTOCOL_T1T == protocol || NFA_PROTOCOL_T5T == protocol ||
      NFC_PROTOCOL_MIFARE == protocol) {
    isFormattable = JNI_TRUE;
  } else if (NFA_PROTOCOL_T3T == protocol) {
    isFormattable = NfcTag::getInstance().isFelicaLite() ? JNI_TRUE : JNI_FALSE;
  } else if (NFA_PROTOCOL_T2T == protocol) {
    isFormattable = (NfcTag::getInstance().isMifareUltralight() ||
                     NfcTag::getInstance().isInfineonMyDMove() ||
                     NfcTag::getInstance().isKovioType2Tag())
                        ? JNI_TRUE
                        : JNI_FALSE;
  } else if (NFA_PROTOCOL_ISO_DEP == protocol) {
    /**
     * Determines whether this is a formatable IsoDep tag - currectly only NXP
     * DESFire is supported.
     */
    uint8_t cmd[] = {0x90, 0x60, 0x00, 0x00, 0x00};

    if (NfcTag::getInstance().isMifareDESFire()) {
      /* Identifies as DESfire, use get version cmd to be sure */
      jbyteArray versionCmd = e->NewByteArray(5);
      e->SetByteArrayRegion(versionCmd, 0, 5, (jbyte*)cmd);
      jbyteArray respBytes =
          nativeNfcTag_doTransceive(e, o, versionCmd, JNI_TRUE, NULL);
      if (respBytes != NULL) {
        // Check whether the response matches a typical DESfire
        // response.
        // libNFC even does more advanced checking than we do
        // here, and will only format DESfire's with a certain
        // major/minor sw version and NXP as a manufacturer.
        // We don't want to do such checking here, to avoid
        // having to change code in multiple places.
        // A succesful (wrapped) DESFire getVersion command returns
        // 9 bytes, with byte 7 0x91 and byte 8 having status
        // code 0xAF (these values are fixed and well-known).
        int respLength = e->GetArrayLength(respBytes);
        uint8_t* resp = (uint8_t*)e->GetByteArrayElements(respBytes, NULL);
        if (respLength == 9 && resp[7] == 0x91 && resp[8] == 0xAF) {
          isFormattable = JNI_TRUE;
        }
        e->ReleaseByteArrayElements(respBytes, (jbyte*)resp, JNI_ABORT);
      }
    }
  }

  LOG(DEBUG) << StringPrintf("%s: is formattable=%u", __func__, isFormattable);
  return isFormattable;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doIsIsoDepNdefFormatable
**
** Description:     Is ISO-DEP tag formattable?
**                  e: JVM environment.
**                  o: Java object.
**                  pollBytes: Data from activation.
**                  actBytes: Data from activation.
**
** Returns:         True if formattable.
**
*******************************************************************************/
static jboolean nativeNfcTag_doIsIsoDepNdefFormatable(JNIEnv* e, jobject o,
                                                      jbyteArray pollBytes,
                                                      jbyteArray actBytes) {
  uint8_t uidFake[] = {0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0};
  LOG(DEBUG) << StringPrintf("%s", __func__);
  jbyteArray uidArray = e->NewByteArray(8);
  e->SetByteArrayRegion(uidArray, 0, 8, (jbyte*)uidFake);
  return nativeNfcTag_doIsNdefFormatable(e, o, 0, uidArray, pollBytes,
                                         actBytes);
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doNdefFormat
**
** Description:     Format a tag so it can store NDEF message.
**                  e: JVM environment.
**                  o: Java object.
**                  key: Not used.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nativeNfcTag_doNdefFormat(JNIEnv* e, jobject o, jbyteArray) {
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  tNFA_STATUS status = NFA_STATUS_OK;

  // Do not try to format if tag is already deactivated.
  if (NfcTag::getInstance().isActivated() == false) {
    LOG(DEBUG) << StringPrintf("%s: tag already deactivated(no need to format)",
                               __func__);
    return JNI_FALSE;
  }

  if (0 != sem_init(&sFormatSem, 0, 0)) {
   LOG(ERROR) << StringPrintf("%s: semaphore creation failed (errno=0x%08x)",
            __func__, errno);
    return JNI_FALSE;
  }
  sFormatOk = false;
  status = NFA_RwFormatTag();
  if (status == NFA_STATUS_OK) {
    LOG(DEBUG) << StringPrintf("%s: wait for completion", __func__);
#if(NXP_EXTNS == TRUE)
    if (-1 == sem_wait(&sFormatSem)) {
      LOG(ERROR) << StringPrintf("%s: sem_wait failed \n", __func__);
    }
#else
    sem_wait(&sFormatSem);
#endif

    status = sFormatOk ? NFA_STATUS_OK : NFA_STATUS_FAILED;
  } else
    LOG(ERROR) << StringPrintf("%s: error status=%u", __func__, status);
  sem_destroy(&sFormatSem);

  if (sCurrentConnectedTargetProtocol == NFA_PROTOCOL_ISO_DEP) {
    int retCode = NFCSTATUS_SUCCESS;
    retCode = nativeNfcTag_doReconnect(e, o);
    LOG(DEBUG) << StringPrintf("%s Status = 0x%X", __func__, retCode);
  }
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
  return (status == NFA_STATUS_OK) ? JNI_TRUE : JNI_FALSE;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doMakeReadonlyResult
**
** Description:     Receive the result of making a tag read-only. Called by the
**                  NFA_SET_TAG_RO_EVT.
**                  status: Status of the operation.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_doMakeReadonlyResult(tNFA_STATUS status) {
  if (sMakeReadonlyWaitingForComplete != JNI_FALSE) {
    sMakeReadonlyWaitingForComplete = JNI_FALSE;
    sMakeReadonlyStatus = status;

    sem_post(&sMakeReadonlySem);
  }
}

/*******************************************************************************
**
** Function:        nativeNfcTag_doMakeReadonly
**
** Description:     Make the tag read-only.
**                  e: JVM environment.
**                  o: Java object.
**                  key: Key to access the tag.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nativeNfcTag_doMakeReadonly(JNIEnv* e, jobject o, jbyteArray) {
  jboolean result = JNI_FALSE;
  tNFA_STATUS status;

  LOG(DEBUG) << StringPrintf("%s", __func__);

  /* Create the make_readonly semaphore */
  if (sem_init(&sMakeReadonlySem, 0, 0) == -1) {
    LOG(ERROR) << StringPrintf(
        "%s: Make readonly semaphore creation failed (errno=0x%08x)", __func__,
        errno);
    return JNI_FALSE;
  }

  sMakeReadonlyWaitingForComplete = JNI_TRUE;

  // Hard-lock the tag (cannot be reverted)
  status = NFA_RwSetTagReadOnly(TRUE);
  if (status == NFA_STATUS_REJECTED) {
    status = NFA_RwSetTagReadOnly(FALSE);  // try soft lock
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail soft lock, status=%d", __func__,
                                 status);
      goto TheEnd;
    }
  } else if (status != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: fail hard lock, status=%d", __func__,
                               status);
    goto TheEnd;
  }

  /* Wait for check NDEF completion status */
  if (sem_wait(&sMakeReadonlySem)) {
    LOG(ERROR) << StringPrintf(
        "%s: Failed to wait for make_readonly semaphore (errno=0x%08x)",
        __func__, errno);
    goto TheEnd;
  }

  if (sMakeReadonlyStatus == NFA_STATUS_OK) {
    result = JNI_TRUE;
  }

TheEnd:
  /* Destroy semaphore */
  if (sem_destroy(&sMakeReadonlySem)) {
    LOG(ERROR) << StringPrintf(
        "%s: Failed to destroy read_only semaphore (errno=0x%08x)", __func__,
        errno);
  }
  sMakeReadonlyWaitingForComplete = JNI_FALSE;
  return result;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_registerNdefTypeHandler
**
** Description:     Register a callback to receive NDEF message from the tag
**                  from the NFA_NDEF_DATA_EVT.
**
** Returns:         None
**
*******************************************************************************/
// register a callback to receive NDEF message from the tag
// from the NFA_NDEF_DATA_EVT;
void nativeNfcTag_registerNdefTypeHandler() {
  LOG(DEBUG) << StringPrintf("%s", __func__);
  sNdefTypeHandlerHandle = NFA_HANDLE_INVALID;
  NFA_RegisterNDefTypeHandler(TRUE, NFA_TNF_DEFAULT, (uint8_t*)"", 0,
                              ndefHandlerCallback);
}

/*******************************************************************************
**
** Function:        nativeNfcTag_deregisterNdefTypeHandler
**
** Description:     No longer need to receive NDEF message from the tag.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_deregisterNdefTypeHandler() {
  LOG(DEBUG) << StringPrintf("%s", __func__);
  NFA_DeregisterNDefTypeHandler(sNdefTypeHandlerHandle);
  sNdefTypeHandlerHandle = NFA_HANDLE_INVALID;
}

/*******************************************************************************
**
** Function:        nativeNfcTag_acquireRfInterfaceMutexLock
**
** Description:     acquire sRfInterfaceMutex
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_acquireRfInterfaceMutexLock() {
  LOG(DEBUG) << StringPrintf("%s: try to acquire lock", __func__);
  sRfInterfaceMutex.lock();
  LOG(DEBUG) << StringPrintf("%s: sRfInterfaceMutex lock", __func__);
}

/*******************************************************************************
**
** Function:       nativeNfcTag_releaseRfInterfaceMutexLock
**
** Description:    release the sRfInterfaceMutex
**
** Returns:        None
**
*******************************************************************************/
void nativeNfcTag_releaseRfInterfaceMutexLock() {
  sRfInterfaceMutex.unlock();
  LOG(DEBUG) << StringPrintf("%s: sRfInterfaceMutex unlock", __func__);
}

#if (NXP_EXTNS == TRUE)
/*******************************************************************************
**
** Function:        nativeNfcTag_abortTagOperations
**
** Description:     Unblock all thread synchronization objects.
**
** Returns:         None
**
*******************************************************************************/
void nativeNfcTag_abortTagOperations(tNFA_STATUS status) {
  LOG(DEBUG) << StringPrintf("%s, status : 0x%x", __func__, status);
  /*Transcieve timeout will be notify*/
  nativeNfcTag_notifyRfTimeout(status);
  /*Read Event will be notify*/
  nativeNfcTag_doReadCompleted(status);
  /*Write Semaphore will be waiting*/
  nativeNfcTag_doWriteStatus(status == NFA_STATUS_OK);
  /*Format Semaphore will be waiting*/
  nativeNfcTag_formatStatus(status == NFA_STATUS_OK);
  /*Check Ndef Semaphore will be waiting*/
  nativeNfcTag_doCheckNdefResult(status, 0 , 0, RW_NDEF_FL_UNKNOWN);
  /*Notify Presence check Event*/
  nativeNfcTag_doPresenceCheckResult(status);
  /*Read only Ndef Semaphore will be waiting*/
  nativeNfcTag_doMakeReadonlyResult(status);
}
#endif
/*****************************************************************************
**
** JNI functions for Android 4.0.3
**
*****************************************************************************/
static JNINativeMethod gMethods[] = {
    {"doConnect", "(I)I", (void*)nativeNfcTag_doConnect},
    {"doDisconnect", "()Z", (void*)nativeNfcTag_doDisconnect},
    {"doReconnect", "()I", (void*)nativeNfcTag_doReconnect},
    {"doHandleReconnect", "(I)I", (void*)nativeNfcTag_doHandleReconnect},
    {"doTransceive", "([BZ[I)[B", (void*)nativeNfcTag_doTransceive},
    {"doGetNdefType", "(II)I", (void*)nativeNfcTag_doGetNdefType},
    {"doCheckNdef", "([I)I", (void*)nativeNfcTag_doCheckNdef},
    {"doRead", "()[B", (void*)nativeNfcTag_doRead},
    {"doWrite", "([B)Z", (void*)nativeNfcTag_doWrite},
    {"doPresenceCheck", "()Z", (void*)nativeNfcTag_doPresenceCheck},
    {"doIsIsoDepNdefFormatable", "([B[B)Z",
     (void*)nativeNfcTag_doIsIsoDepNdefFormatable},
    {"doNdefFormat", "([B)Z", (void*)nativeNfcTag_doNdefFormat},
    {"doMakeReadonly", "([B)Z", (void*)nativeNfcTag_doMakeReadonly},
#if(NXP_EXTNS == TRUE && NXP_SRD == TRUE)
    {"doGetSrdState", "()I", (void*)nativeNfcTag_getSrdState},
#endif
};

/*******************************************************************************
**
** Function:        register_com_android_nfc_NativeNfcTag
**
** Description:     Regisgter JNI functions with Java Virtual Machine.
**                  e: Environment of JVM.
**
** Returns:         Status of registration.
**
*******************************************************************************/
int register_com_android_nfc_NativeNfcTag(JNIEnv* e) {
  LOG(DEBUG) << StringPrintf("%s", __func__);
  return jniRegisterNativeMethods(e, gNativeNfcTagClassName, gMethods,
                                  NELEM(gMethods));
}

} /* namespace android */
