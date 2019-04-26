/*
 * Copyright (C) 2018 The Android Open Source Project
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
#include "HciEventManager.h"
#include "SecureElement.h"
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <nativehelper/ScopedLocalRef.h>
#include "JavaClassConstants.h"
#include "NfcJniUtil.h"
#include "nfc_config.h"

extern bool nfc_debug_enabled;
const char* APP_NAME = "NfcNci";
uint8_t HciEventManager::sEsePipe;
uint8_t HciEventManager::sSim1Pipe;
uint8_t HciEventManager::sSim2Pipe;

using android::base::StringPrintf;

HciEventManager::HciEventManager() : mNativeData(nullptr) {}

HciEventManager& HciEventManager::getInstance() {
  static HciEventManager sHciEventManager;
  return sHciEventManager;
}

void HciEventManager::initialize(nfc_jni_native_data* native) {
  mNativeData = native;
  tNFA_HCI_GET_GATE_PIPE_LIST gatePipeInfo;
  gatePipeInfo.status = NFA_STATUS_FAILED;
  bool isEseAvailable = false, isUicc1Avaialble = false,
       isUicc2Avaialble = false;
  tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
  uint8_t ActualNumEe = nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED;
  tNFA_EE_INFO mEeInfo[ActualNumEe];

  if ((nfaStat = NFA_AllEeGetInfo(&ActualNumEe, mEeInfo)) != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: fail get info; error=0x%X", __func__,
                               nfaStat);
    ActualNumEe = 0;
  } else {
    for (int xx = 0; xx < ActualNumEe; xx++) {
      if ((mEeInfo[xx].ee_handle == SecureElement::EE_HANDLE_0xF3) &&
          (mEeInfo[xx].ee_status == 0x00)) {
        isEseAvailable = true;
      } else if ((mEeInfo[xx].ee_handle ==
                  SecureElement::getInstance().EE_HANDLE_0xF4) &&
                 (mEeInfo[xx].ee_status == 0x00)) {
        isUicc1Avaialble = true;
      } else if ((mEeInfo[xx].ee_handle == SecureElement::EE_HANDLE_0xF8) &&
                 (mEeInfo[xx].ee_status == 0x00)) {
        isUicc2Avaialble = true;
      }
    }
  }

  gatePipeInfo = SecureElement::getInstance().getGateAndPipeInfo();

  if (gatePipeInfo.status == NFA_STATUS_OK) {
    for (uint8_t xx = 0; xx < gatePipeInfo.num_uicc_created_pipes; xx++) {
      if (isEseAvailable &&
          (gatePipeInfo.uicc_created_pipe[xx].dest_host ==
           (SecureElement::EE_HANDLE_0xF3 & ~NFA_HANDLE_GROUP_EE))) {
        sEsePipe = gatePipeInfo.uicc_created_pipe[xx].pipe_id;
      } else if (gatePipeInfo.uicc_created_pipe[xx].dest_host == 0x02) {
        if (isUicc1Avaialble &&
            gatePipeInfo.uicc_created_pipe[xx].pipe_id == 0x0A) {
          sSim1Pipe = gatePipeInfo.uicc_created_pipe[xx].pipe_id;
        } else if (isUicc2Avaialble &&
                   gatePipeInfo.uicc_created_pipe[xx].pipe_id == 0x23) {
          sSim2Pipe = gatePipeInfo.uicc_created_pipe[xx].pipe_id;
        }
      }
    }
  }

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s: sEsePipe : 0x%0x   sSim1Pipe : 0x%0x  sSim2Pipe : 0x%0x", __func__,
      sEsePipe, sSim1Pipe, sSim2Pipe);
}

void HciEventManager::notifyTransactionListenersOfAid(std::vector<uint8_t> aid,
                                                      std::vector<uint8_t> data,
                                                      std::string evtSrc) {
  if (aid.empty()) {
    return;
  }

  JNIEnv* e = NULL;
  ScopedAttach attach(mNativeData->vm, &e);
  CHECK(e);

  ScopedLocalRef<jobject> aidJavaArray(e, e->NewByteArray(aid.size()));
  CHECK(aidJavaArray.get());
  e->SetByteArrayRegion((jbyteArray)aidJavaArray.get(), 0, aid.size(),
                        (jbyte*)&aid[0]);
  CHECK(!e->ExceptionCheck());

  ScopedLocalRef<jobject> srcJavaString(e, e->NewStringUTF(evtSrc.c_str()));
  CHECK(srcJavaString.get());

  if (data.size() > 0) {
    ScopedLocalRef<jobject> dataJavaArray(e, e->NewByteArray(data.size()));
    CHECK(dataJavaArray.get());
    e->SetByteArrayRegion((jbyteArray)dataJavaArray.get(), 0, data.size(),
                          (jbyte*)&data[0]);
    CHECK(!e->ExceptionCheck());
    e->CallVoidMethod(mNativeData->manager,
                      android::gCachedNfcManagerNotifyTransactionListeners,
                      aidJavaArray.get(), dataJavaArray.get(),
                      srcJavaString.get());
  } else {
    e->CallVoidMethod(mNativeData->manager,
                      android::gCachedNfcManagerNotifyTransactionListeners,
                      aidJavaArray.get(), NULL, srcJavaString.get());
  }
}

/**
 * BerTlv has the following format:
 *
 * byte1 byte2 byte3 byte4 byte5 byte6
 * 00-7F   -    -     -     -     -
 * 81    00-FF  -     -     -     -
 * 82    0000-FFFF    -     -     -
 * 83      000000-FFFFFF    -     -
 * 84      00000000-FFFFFFFF      -
 */
std::vector<uint8_t> HciEventManager::getDataFromBerTlv(
    std::vector<uint8_t> berTlv) {
  if (berTlv.empty()) {
    return std::vector<uint8_t>();
  }
  size_t lengthTag = berTlv[0];
  DLOG_IF(INFO, nfc_debug_enabled) << "decodeBerTlv: berTlv[0]=" << berTlv[0];

  /* As per ISO/IEC 7816, read the first byte to determine the length and
   * the start index accordingly
   */
  if (lengthTag < 0x80 && berTlv.size() == (lengthTag + 1)) {
    return std::vector<uint8_t>(berTlv.begin() + 1, berTlv.end());
  } else if (lengthTag == 0x81 && berTlv.size() > 2) {
    size_t length = berTlv[1];
    if ((length + 2) == berTlv.size()) {
      return std::vector<uint8_t>(berTlv.begin() + 2, berTlv.end());
    }
  } else if (lengthTag == 0x82 && berTlv.size() > 3) {
    size_t length = ((berTlv[1] << 8) | berTlv[2]);
    if ((length + 3) == berTlv.size()) {
      return std::vector<uint8_t>(berTlv.begin() + 3, berTlv.end());
    }
  } else if (lengthTag == 0x83 && berTlv.size() > 4) {
    size_t length = (berTlv[1] << 16) | (berTlv[2] << 8) | berTlv[3];
    if ((length + 4) == berTlv.size()) {
      return std::vector<uint8_t>(berTlv.begin() + 4, berTlv.end());
    }
  } else if (lengthTag == 0x84 && berTlv.size() > 5) {
    size_t length = (size_t)((berTlv[1] << 24) | (berTlv[2] << 16) | (berTlv[3] << 8) | berTlv[4]);
    if ((length + 5) == berTlv.size()) {
      return std::vector<uint8_t>(berTlv.begin() + 5, berTlv.end());
    }
  }
  LOG(ERROR) << "Error in TLV length encoding!";
  return std::vector<uint8_t>();
}

void HciEventManager::nfaHciEvtHandler(tNFA_HCI_EVT event,
                                       tNFA_HCI_EVT_DATA* eventData) {
  if (eventData == nullptr) {
    return;
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("event=%d code=%d pipe=%d len=%d ", event,
                      eventData->rcvd_evt.evt_code, eventData->rcvd_evt.pipe,
                      eventData->rcvd_evt.evt_len);

  std::string evtSrc;
  if (eventData->rcvd_evt.pipe == sEsePipe) {
    evtSrc = "eSE1";
  } else if (eventData->rcvd_evt.pipe == sSim1Pipe) {
    evtSrc = "SIM1";
  } else if (eventData->rcvd_evt.pipe == sSim2Pipe) {
    evtSrc = "SIM2";
  }else {
    LOG(ERROR) << "Incorrect Pipe Id";
    return;
  }

  uint8_t* buff = eventData->rcvd_evt.p_evt_buf;
  uint32_t buffLength = eventData->rcvd_evt.evt_len;
  std::vector<uint8_t> event_buff(buff, buff + buffLength);
  // Check the event and check if it contains the AID
  if (event == NFA_HCI_EVENT_RCVD_EVT &&
      eventData->rcvd_evt.evt_code == NFA_HCI_EVT_TRANSACTION &&
      buffLength > 3 && event_buff[0] == 0x81) {
    int aidlen = event_buff[1];
    std::vector<uint8_t> aid(event_buff.begin() + 2,
                             event_buff.begin() + aidlen + 2);

    int32_t berTlvStart = aidlen + 2 + 1;
    int32_t berTlvLen = buffLength - berTlvStart;
    std::vector<uint8_t> data;
    if (berTlvLen > 0 && event_buff[2 + aidlen] == 0x82) {
      std::vector<uint8_t> berTlv(event_buff.begin() + berTlvStart,
                                  event_buff.end());
      // BERTLV decoding here, to support extended data length for params.
      data = getInstance().getDataFromBerTlv(berTlv);
    }
    getInstance().notifyTransactionListenersOfAid(aid, data, evtSrc);
  }
}

void HciEventManager::finalize() { mNativeData = NULL; }
