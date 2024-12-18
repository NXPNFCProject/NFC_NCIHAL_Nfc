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
/******************************************************************************
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
*  Copyright 2019-2020, 2023 NXP
*
******************************************************************************/
#include "HciEventManager.h"

#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <log/log.h>
#include <nativehelper/ScopedLocalRef.h>

#include "JavaClassConstants.h"
#include "NfcJniUtil.h"
#include "nfc_config.h"

const char* APP_NAME = "NfcNci";
uint8_t HciEventManager::sEsePipe;
std::vector<uint8_t> HciEventManager::sSimPipeIds;
#if(NXP_EXTNS == TRUE)
uint8_t HciEventManager::sSim2Pipe;
uint8_t HciEventManager::sESimPipe;
uint8_t HciEventManager::sESim2Pipe;
#endif

using android::base::StringPrintf;

HciEventManager::HciEventManager() : mNativeData(nullptr) {}

HciEventManager& HciEventManager::getInstance() {
  static HciEventManager sHciEventManager;
  return sHciEventManager;
}

void HciEventManager::initialize(nfc_jni_native_data* native) {
  mNativeData = native;
#if(NXP_EXTNS == FALSE)
  tNFA_STATUS nfaStat = NFA_HciRegister(const_cast<char*>(APP_NAME),
                                        (tNFA_HCI_CBACK*)&nfaHciCallback, true);
  if (nfaStat != NFA_STATUS_OK) {
    LOG(ERROR) << "HCI registration failed; status=" << nfaStat;
  }
#else
  sSim2Pipe = (uint8_t)NfcConfig::getUnsigned(NAME_OFF_HOST_SIM2_PIPE_ID,
          OFF_HOST_DEFAULT_PIPE_ID);
  sESimPipe = NfcConfig::getUnsigned(NAME_OFF_HOST_ESIM_PIPE_ID, 0x2B);
  sESim2Pipe = NfcConfig::getUnsigned(NAME_OFF_HOST_ESIM2_PIPE_ID, 0x2F);
#endif
  sEsePipe = NfcConfig::getUnsigned(NAME_OFF_HOST_ESE_PIPE_ID, 0x16);
  // Backward compatibility or For vendor supporting only single sim pipe ID
  if (!NfcConfig::hasKey(NAME_OFF_HOST_SIM_PIPE_IDS)) {
    uint8_t simPipeId = NfcConfig::getUnsigned(NAME_OFF_HOST_SIM_PIPE_ID, 0x0A);
    sSimPipeIds = {simPipeId};
  } else {
    sSimPipeIds = NfcConfig::getBytes(NAME_OFF_HOST_SIM_PIPE_IDS);
  }
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
  LOG(DEBUG) << "decodeBerTlv: berTlv[0]=" << berTlv[0];

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
#if(NXP_EXTNS == TRUE)
    size_t length = ((size_t)(berTlv[1] << 24) | (size_t)(berTlv[2] << 16) |
                     (size_t)(berTlv[3] << 8) | (size_t)berTlv[4]);
#else
    size_t length =
        (berTlv[1] << 24) | (berTlv[2] << 16) | (berTlv[3] << 8) | berTlv[4];
#endif
    if ((length + 5) == berTlv.size()) {
      return std::vector<uint8_t>(berTlv.begin() + 5, berTlv.end());
    }
  }
  LOG(ERROR) << "Error in TLV length encoding!";
  return std::vector<uint8_t>();
}

void HciEventManager::nfaHciCallback(tNFA_HCI_EVT event,
                                     tNFA_HCI_EVT_DATA* eventData) {
  if (eventData == nullptr) {
    return;
  }

  LOG(DEBUG) << StringPrintf(
      "event=%d code=%d pipe=%d len=%d", event, eventData->rcvd_evt.evt_code,
      eventData->rcvd_evt.pipe, eventData->rcvd_evt.evt_len);

  std::string evtSrc;
  if (eventData->rcvd_evt.pipe == sEsePipe) {
    evtSrc = "eSE1";
#if(NXP_EXTNS == TRUE)
  } else if (eventData->rcvd_evt.pipe == sSim2Pipe) {
    evtSrc = "SIM2";
  } else if (eventData->rcvd_evt.pipe == sESimPipe) {
    evtSrc = "SIM3";
  } else if (eventData->rcvd_evt.pipe == sESim2Pipe) {
    evtSrc = "SIM4";
#endif
  } else {
    LOG(WARNING) << "Incorrect Pipe Id";
    return;
    bool isSimPipeId = false;
    for (size_t i = 0; i < (size_t)sSimPipeIds.size(); i++) {
      if (eventData->rcvd_evt.pipe == sSimPipeIds[i]) {
        evtSrc = "SIM" + std::to_string(i + 1);
        isSimPipeId = true;
        break;
      }
    }

    if (!isSimPipeId) {
      LOG(WARNING) << "Incorrect Pipe Id";
      return;
    }
  }

  // Check the event and check if it contains the AID
  uint8_t* event_buff = eventData->rcvd_evt.p_evt_buf;
  uint32_t event_buff_len = eventData->rcvd_evt.evt_len;
  if (event != NFA_HCI_EVENT_RCVD_EVT ||
      eventData->rcvd_evt.evt_code != NFA_HCI_EVT_TRANSACTION ||
      event_buff_len <= 3 || event_buff == nullptr || event_buff[0] != 0x81) {
    LOG(WARNING) << "Invalid event";
    return;
  }

  uint32_t aid_len = event_buff[1];
  if (aid_len >= (event_buff_len - 1)) {
    android_errorWriteLog(0x534e4554, "181346545");
    LOG(ERROR) << StringPrintf("error: aidlen(%d) is too big", aid_len);
    return;
  }

  std::vector<uint8_t> aid(event_buff + 2, event_buff + aid_len + 2);
  int32_t berTlvStart = aid_len + 2 + 1;
  int32_t berTlvLen = event_buff_len - berTlvStart;
  std::vector<uint8_t> data;
  if (berTlvLen > 0 && event_buff[2 + aid_len] == 0x82) {
    std::vector<uint8_t> berTlv(event_buff + berTlvStart,
                                event_buff + event_buff_len);
    // BERTLV decoding here, to support extended data length for params.
    data = getInstance().getDataFromBerTlv(berTlv);
  }

  getInstance().notifyTransactionListenersOfAid(aid, data, evtSrc);
}

void HciEventManager::finalize() { mNativeData = NULL; }
