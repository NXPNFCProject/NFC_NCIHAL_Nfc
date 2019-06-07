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
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <vector>
#include "NfcExtnsFeature.h"
#include "NfcJniUtil.h"
#include "STAG.h"
#include "SecureElement.h"
namespace android {
void startRfDiscovery(bool isStart);
bool isDiscoveryStarted();
int nfcManager_doPartialInitialize(JNIEnv* e, jobject o);
int nfcManager_doPartialDeInitialize(JNIEnv* e, jobject o);
bool nfcManager_isNfcActive();
extern tNFA_STATUS NxpPropCmd_send(uint8_t* pData4Tx, uint8_t dataLen,
                                   uint8_t* rsp_len, uint8_t* rsp_buf,
                                   uint32_t rspTimeout, tHAL_NFC_ENTRY* halMgr);

extern nfc_jni_native_data* getNative(JNIEnv* e, jobject o);
}  // namespace android

using android::base::StringPrintf;
extern bool nfc_debug_enabled;

void* detectExtField(void* arg);
STAG STAG::mInstance;
stag_cmd_timeout_t gstag_cmd_timeout;
/************************************************************************
STAGFieldDetect Implementation
*************************************************************************/
StartExtFieldDetect::StartExtFieldDetect() {
  const char fn[] = "StartExtFieldDetect::StartExtFieldDetect";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:enter", fn);
}

/*******************************************************************************
 **
 ** Function:        setThreadState
 **
 ** Description:     API to update thread state
 **
 ** Returns:         None
 **
 *******************************************************************************/
void StartExtFieldDetect::setThreadState(ExtFieldDetectState_t value) {
  mState = value;
}

/*******************************************************************************
 **
 ** Function:        getThreadState()
 **
 ** Description:     API to retrieve the thread state
 **
 ** Returns:         STAG External Field detection thread state
 **
 *******************************************************************************/
ExtFieldDetectState_t StartExtFieldDetect::getThreadState() { return mState; }

/*******************************************************************************
 **
 ** Function:        getThreadState()
 **
 ** Description:     API to retrieve the thread state
 **
 ** Returns:         STAG External Field detection thread state
 **
 *******************************************************************************/
ExtFieldDetectState_t STAG::getThreadState() {
  return mStartFieldDetectObject->getThreadState();
}

/*******************************************************************************
 **
 ** Function:        detectExtField()
 **
 ** Description:     API to detect external field.
 **                  This thread function will reun unitl the state is updated
 *to STATE_STOPPED.
 **                  If the RF field is detected, then  STAG mode is stopped.
 **
 ** Returns:         None
 **
 *******************************************************************************/
void* detectExtField(void*) {
  static const char fn[] = "detectExtField";
  std::vector<uint8_t> RawExchg = {0x2F, 0x39, 0x00};
  tNFA_STATUS status = NFA_STATUS_FAILED;
  tAuthCmdType cmd = RawExchange;

  std::vector<uint8_t> cmdBuffer = RawExchg;
  std::vector<uint8_t> rspBuffer(1, 0xFF);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:enter", fn);
  unsigned long timeout = STAG_RETRY_DELAY;
  if (GetNxpNumValue(NAME_NXP_POLL_FOR_EFD_TIMEDELAY, &timeout,
                     sizeof(timeout)) == false) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s:default value set", __func__);
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s:stag delay  %lx", __func__, timeout);

  while (STAG::getInstance().getThreadState() != STATE_STOPPED) {
    if (STAG::getInstance().getThreadState() == STATE_RUNNING) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: thread running ", fn);
      cmd = RawExchange;
      status =
          STAG::getInstance().Send_CoverAuthPropCmd(cmd, cmdBuffer, rspBuffer);
      if (rspBuffer[0] == AUTH_STATUS_EFD_ON) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: External field detected-stopping S-tag", fn);
        STAG::getInstance().stopCoverAuth(NULL, NULL);
        struct nfc_jni_native_data* nat = android::getNative(NULL, NULL);
        JNIEnv* e = NULL;
        ScopedAttach attach(nat->vm, &e);
        if (e != NULL) {
          // e->CallVoidMethod
          // (gNativeData->manager,gCachedNfcManagerNotifyExternalFieldDetected);
          if (e->ExceptionCheck()) {
            e->ExceptionClear();
            DLOG_IF(ERROR, nfc_debug_enabled)
                << StringPrintf("%s: fail notify", fn);
          }
        }
        break;
      }
    }
    usleep(timeout * 1000);
  }
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit thread", fn);
  pthread_exit(NULL);
  return NULL;
}

/*******************************************************************************
 **
 ** Function:        getInstance()
 **
 ** Description:     API to retrieve static instance of STAG implementatation.
 **
 ** Returns:         Instance of STAG.
 **
 *******************************************************************************/
STAG& STAG::getInstance() { return mInstance; }

/*******************************************************************************
 **
 ** Function:        STAG()
 **
 ** Description:     STAG constructor.
 **
 ** Returns:         None.
 **
 *******************************************************************************/
STAG::STAG() { mStartFieldDetectObject = new StartExtFieldDetect(); }

/*******************************************************************************
 **
 ** Function:        initializeSTAG()
 **
 ** Description:     This will initiialize STAG class context .
 **
 ** Returns:         None.
 **
 *******************************************************************************/
void STAG::initializeSTAG() {
  get_STAG_timeout_values();
  sCoverAuthSessionOpened = STATUS_NOT_STARTED;
  stagNfcOff = false;
}
/*******************************************************************************
 **
 ** Function:        Send_CoverAuthPropCmd()
 **
 ** Description:     Sends all the Auth related commands
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS STAG::Send_CoverAuthPropCmd(tAuthCmdType type,
                                        std::vector<uint8_t> xmitBuffer,
                                        std::vector<uint8_t>& rspBuffer) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint32_t authTimeout = AUTH_CMD_TIMEOUT * 1000;
  std::vector<uint8_t> transCmd = {0x2F, 0x3B};
  std::vector<uint8_t> startPollCmd = {0x2F, 0x3C, 0x01, 0x00};
  std::vector<uint8_t> startPollACmd = {0x2F, 0x3C, 0x01, 0x01};
  std::vector<uint8_t> stopPollCmd = {0x2F, 0x3A, 0x00};
  stagAuthMutex.lock();
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: After acquiring lock=%x", __func__, type);
  std::vector<uint8_t> cmdBuffer;

  uint8_t rsplen = rspBuffer.size();
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: cmd_type=%x", __func__, type);
  switch (type) {
    case startPoll:
#if (NXP_AUTH_V1_9_SUPPORT == TRUE)
      authTimeout =
          gauth_cmd_timeout.start_poll_timeout + AUTH_CMD_TIMEOUT_OFFSET * 1000;
#endif
      cmdBuffer = startPollCmd;
      break;
    case startPollA:
#if (NXP_AUTH_V1_9_SUPPORT == TRUE)
      authTimeout =
          gauth_cmd_timeout.start_poll_timeout + AUTH_CMD_TIMEOUT_OFFSET * 1000;
#endif
      cmdBuffer = startPollACmd;
      break;
    case stopPoll:
      cmdBuffer = stopPollCmd;
      break;
    case Transcieve:
      cmdBuffer = transCmd;

#if (NXP_AUTH_V1_9_SUPPORT == TRUE)
      if (buffer[4] == AUTH_TRANS1_ID) {
        auth_cmd_timeout = gauth_cmd_timeout.trans1_cmd_timeout +
                           AUTH_CMD_TIMEOUT_OFFSET * 1000;
      } else if (buffer[4] == AUTH_TRANS2_ID) {
        auth_cmd_timeout = gauth_cmd_timeout.trans2_cmd_timeout +
                           AUTH_CMD_TIMEOUT_OFFSET * 1000;
      } else {
        auth_cmd_timeout = gauth_cmd_timeout.default_cmd_timeout +
                           AUTH_CMD_TIMEOUT_OFFSET * 1000;
      }
#endif
      cmdBuffer.push_back(xmitBuffer.size());
      cmdBuffer.insert(cmdBuffer.end(), xmitBuffer.begin(), xmitBuffer.end());
      break;
    case RawExchange:
      cmdBuffer = xmitBuffer;
      break;
    default:
      break;
  }

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: auth timeout %d ", __func__, authTimeout);

  status = android::NxpPropCmd_send(cmdBuffer.data(), cmdBuffer.size(), &rsplen,
                                    rspBuffer.data(), authTimeout, NULL);
  if (status == NFA_STATUS_OK)
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
  else
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s:  rsp len %zx  ", __func__, rspBuffer.size());
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: rsp value %x", __func__, rspBuffer[0]);
  stagAuthMutex.unlock();
  return status;
}

/*******************************************************************************
 **
 ** Function:        startCoverAuth()
 **
 ** Description:     Sends the proprietary authentication command
 **
 ** Returns:         byte[] - contains the tag details
 **
 *******************************************************************************/
jbyteArray STAG::startCoverAuth(JNIEnv* e, jobject /*o */) {
  char fn[] = "STAG::startCoverAuth";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", fn);
  tNFA_STATUS status = NFA_STATUS_OK;
  jbyteArray buf = NULL;
  tAuthCmdType cmd = startPoll;

  std::vector<uint8_t> cmdBuffer;
  std::vector<uint8_t> rspBuffer(1, STATUS_REJECTED);
  STAG::getInstance().get_STAG_timeout_values();
  /* STAG is not allowed in this particular condition
      1.CE_ACTIVATED is Activated.
      2.WIRED MODE ongoing.
      3.already STAG start auth is ongoing */

  if (SecureElement::getInstance().isActivatedInListenMode())
    status = STATUS_FIELD_DETECTED;
  else if (sCoverAuthSessionOpened == STATUS_STARTED)
    status = STATUS_REJECTED;
  else if (SecureElement::getInstance().isWiredModeOpen())
    status = STATUS_REJECTED;

  if (status == NFA_STATUS_OK) {
    // if NFC OFF initialize it
    // stop RF discovery
    if (!android::nfcManager_isNfcActive()) {
      if (android::nfcManager_doPartialInitialize(NULL, NULL) == NFA_STATUS_OK)
        stagNfcOff = true;
      else
        status = NFA_STATUS_FAILED;
    }

    if (status == NFA_STATUS_OK) {
      if (android::isDiscoveryStarted()) android::startRfDiscovery(false);
      status = NFA_STATUS_FAILED;
      for (uint8_t retryCount = 0; retryCount < MAX_STAG_RETRY; retryCount++) {
        cmd = startPoll;
        status = Send_CoverAuthPropCmd(cmd, cmdBuffer, rspBuffer);
        if (rspBuffer.size() > 0) status = rspBuffer[0];
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: Retry cnt=%x status =%x", __func__, retryCount, status);

        if ((status == NFC_STAG_STATUS_BYTE_0 ||
             status == NFC_STAG_STATUS_BYTE_1 ||
             status == NFC_STAG_STATUS_BYTE_2)) {
          cmd = stopPoll;
          Send_CoverAuthPropCmd(cmd, cmdBuffer, rspBuffer);
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: Retry cnt=%x", __func__, retryCount);
        } else if(status == AUTH_STATUS_EFD_ON) {
          DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf(
              "%s: Retry cnt=%x status =%x", __func__, retryCount, status);
          cmd = stopPoll;
          Send_CoverAuthPropCmd(cmd, cmdBuffer, rspBuffer);
          break;
        } else {
          DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf(
              "%s: Retry cnt=%x status =%x", __func__, retryCount, status);
          break;
        }
      }
    }
  } else {
    rspBuffer.clear();
    rspBuffer.push_back(status);
  }

  if (status == STATUS_STARTED) {
    sCoverAuthSessionOpened = (tSTAGStatus)status;
    if (stagNfcOff == false) startFieldDetect();
  }
  if (status == STATUS_REJECTED) {
    // do nothing
  } else if (status != STATUS_STARTED) {
    if (android::isDiscoveryStarted() == false &&
        android::nfcManager_isNfcActive())
      android::startRfDiscovery(true);
    if (stagNfcOff) {
      // close the channel
      android::nfcManager_doPartialDeInitialize(NULL, NULL);
      stagNfcOff = false;
    }
  }

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: notifying application; status=%x, rsp_len=%zx", fn,
                      status, rspBuffer.size());
  buf = e->NewByteArray(rspBuffer.size());
  e->SetByteArrayRegion(buf, 0, rspBuffer.size(), (jbyte*)rspBuffer.data());
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: start cover auth Exit ", fn);
  return buf;
}

/*******************************************************************************
 **
 ** Function:        stopCoverAuth()
 **
 ** Description:     Sends the proprietary stop authentication command
 **
 ** Returns:         byte[] - contains the tag details
 **
 *******************************************************************************/
bool STAG::stopCoverAuth(JNIEnv* /*e*/, jobject /*o*/) {
  static const char fn[] = "nfcManager_stopCoverAuth";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);
  tNFA_STATUS status = NFA_STATUS_FAILED;
  bool isSuccess = false;
  tAuthCmdType cmd = stopPoll;
  std::vector<uint8_t> cmdBuffer;
  std::vector<uint8_t> rspBuffer(1, STATUS_REJECTED);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter %x", fn, sCoverAuthSessionOpened);
  if (sCoverAuthSessionOpened != STATUS_STARTED) return false;
  if (!android::nfcManager_isNfcActive() && !stagNfcOff) {
    return false;
  }

  stopFieldDetect();

  if (android::isDiscoveryStarted() && android::nfcManager_isNfcActive())
    android::startRfDiscovery(false);

  status = Send_CoverAuthPropCmd(cmd, cmdBuffer, rspBuffer);
  status = rspBuffer[0];
  if (status == NFA_STATUS_OK || status == NFA_STATUS_SEMANTIC_ERROR) {
    isSuccess = true;
    sCoverAuthSessionOpened = STATUS_NOT_STARTED;
    // close the channel
    if (android::isDiscoveryStarted() == false &&
        android::nfcManager_isNfcActive())
      android::startRfDiscovery(true);
  }

  if (stagNfcOff) {
    // close the channel
    android::nfcManager_doPartialDeInitialize(NULL, NULL);
    stagNfcOff = false;
  }

  return isSuccess;
}
/*******************************************************************************
 **
 ** Function:        transceiveAuthData()
 **
 ** Description:     Sends the proprietary transcieve auth command
 **
 ** Returns:         byte[] - contains the tag details
 **
 *******************************************************************************/
jbyteArray STAG::transceiveAuthData(JNIEnv* e, jobject /*o*/, jbyteArray data) {
  static const char fn[] = "nfcManager_TransceiveLedCover";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", fn);

  tNFA_STATUS status = NFA_STATUS_OK;
  jbyteArray buf = NULL;
  uint8_t* Cmd_buf = NULL;
  uint16_t Cmd_len = 0;
  uint8_t retryCnt = 0;
  tAuthCmdType cmd = Transcieve;
  std::vector<uint8_t> rspBuffer(255, 0);
  /*  Transceive  STAG is not allowed in this particular condition
      1.CE LSITEN MODE is Activated
      2.WIRED MODE activated
      3.open is unsucessful*/

  if (SecureElement::getInstance().isActivatedInListenMode())
    status = NFA_STATUS_REJECTED;
  else if (sCoverAuthSessionOpened != STATUS_STARTED)
    status = NFA_STATUS_REJECTED;
  else if (SecureElement::getInstance().isWiredModeOpen())
    status = NFA_STATUS_REJECTED;
  if (status == NFA_STATUS_OK) {
    pauseFieldDetect();
    cmd = Transcieve;
    std::vector<uint8_t> cmdBuf;
    ScopedByteArrayRW bytes(e, data);
    Cmd_buf = const_cast<uint8_t*>(reinterpret_cast<uint8_t*>(&bytes[0]));
    Cmd_len = bytes.size();

    for (uint8_t i = 0; i < Cmd_len; i++) cmdBuf.push_back(*Cmd_buf++);
    for (uint8_t xx = 0; xx < cmdBuf.size(); xx++)
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: transceive  status=%d, res_len=%x", fn, status, cmdBuf[xx]);
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Delay 70ms and size=%zx", fn, bytes.size());
    for (uint8_t retryCount = 0; retryCount < MAX_STAG_RETRY; retryCount++) {
      status = Send_CoverAuthPropCmd(cmd, cmdBuf, rspBuffer);
      if ((stagNfcOff != true) &&
          ((status == 0xB0 || status == 0xB1 || status == 0xB2))) {
        usleep(STAG_RETRY_DELAY);
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: Delay 70ms and Retry cnt=%x", fn, retryCnt);
      } else {
        break;
      }
    }
    resumeFieldDetect();
  } else {
    rspBuffer.push_back(status);
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: notifying application; status=%d, res_len=%zx", fn,
                      status, rspBuffer.size());
  buf = e->NewByteArray(rspBuffer.size());
  e->SetByteArrayRegion(buf, 0, rspBuffer.size(), (jbyte*)rspBuffer.data());
  return buf;
}

/*******************************************************************************
 **
 ** Function:        get_STAG_timeout_values()
 **
 ** Description:      reads the STAG values
 **
 ** Returns:
 **
 *******************************************************************************/
void STAG::get_STAG_timeout_values() {
#if (NXP_AUTH_V1_9_SUPPORT == TRUE)
  uint8_t stag_timeout_buffer[NXP_STAG_TIMEOUT_BUF_LEN];
  long bufflen = NXP_STAG_TIMEOUT_BUF_LEN;
  long retlen = 0;
  bool isfound = false;
  isfound = GetNxpByteArrayValue(NAME_NXP_STAG_TIMEOUT_CFG,
                                 (char*)stag_timeout_buffer, bufflen, &retlen);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s:stag_timeout_buffer %x", __func__, stag_timeout_buffer[0]);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s:stag_timeout_buffer %x", __func__, stag_timeout_buffer[1]);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s:stag_timeout_buffer %x", __func__, stag_timeout_buffer[2]);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s:stag_timeout_buffer %x", __func__, stag_timeout_buffer[3]);

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s:retlen %lx", __func__, retlen);

  if (retlen > 0) {
    gstag_cmd_timeout.start_poll_timeout =
        (stag_timeout_buffer[0] * NXP_STAG_CMD_TIMEOUT_DEFAULT_SCALING_FACTOR);
    gstag_cmd_timeout.trans1_cmd_timeout =
        (stag_timeout_buffer[1] * NXP_STAG_CMD_TIMEOUT_DEFAULT_SCALING_FACTOR);
    gstag_cmd_timeout.trans2_cmd_timeout =
        (stag_timeout_buffer[2] * NXP_STAG_CMD_TIMEOUT_DEFAULT_SCALING_FACTOR);
    gstag_cmd_timeout.default_cmd_timeout =
        (stag_timeout_buffer[3] * NXP_STAG_CMD_TIMEOUT_DEFAULT_SCALING_FACTOR);
  } else {
    gstag_cmd_timeout.start_poll_timeout = NXP_STAG_START_AUTH_DEFAULT_TIMEOUT;
    gstag_cmd_timeout.trans1_cmd_timeout = NXP_STAG_TRANS1_DEFAULT_TIMEOUT;
    gstag_cmd_timeout.trans2_cmd_timeout = NXP_STAG_TRANS2_DEFAULT_TIMEOUT;
    gstag_cmd_timeout.default_cmd_timeout = NXP_STAG_DEFAULT_CMD_TIMEOUT;
  }
#endif
}
/*******************************************************************************
 **
 ** Function:        startFieldDetect()
 **
 ** Description:     This will start field detect thread.
 **
 ** Returns:         None
 **
 *******************************************************************************/
void STAG::startFieldDetect() {
  static const char fn[] = "StartExtFieldDetect::detectExtField";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:enter", fn);

  mStartFieldDetectObject->setThreadState(STATE_RUNNING);
  pthread_t EfdThread;
  pthread_attr_t attr;
  DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf("%s:enter", fn);

  pthread_attr_init(&attr);
  pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
  if (pthread_create(&EfdThread, &attr, detectExtField, NULL) < 0) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("EfdThread creation failed");
    mStartFieldDetectObject->setThreadState(STATE_STOPPED);
  } else {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("EfdThread creation success");
  }
  pthread_attr_destroy(&attr);
  DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf("%s:exit", fn);
}

/*******************************************************************************
 **
 ** Function:        stopFieldDetect()
 **
 ** Description:     This will stop field detect thread.
 **
 ** Returns:         None
 **
 *******************************************************************************/
void STAG::stopFieldDetect() {
  mStartFieldDetectObject->setThreadState(STATE_STOPPED);
}

/*******************************************************************************
 **
 ** Function:        stopFieldDetect()
 **
 ** Description:     This will pause field detect thread.
 **
 ** Returns:         None
 **
 *******************************************************************************/
void STAG::pauseFieldDetect() {
  mStartFieldDetectObject->setThreadState(STATE_PAUSED);
}

/*******************************************************************************
 **
 ** Function:        resumeFieldDetect()
 **
 ** Description:     Restart field detect thread if stopped.
 **
 ** Returns:         None
 **
 *******************************************************************************/
void STAG::resumeFieldDetect() {
  mStartFieldDetectObject->setThreadState(STATE_RUNNING);
}
