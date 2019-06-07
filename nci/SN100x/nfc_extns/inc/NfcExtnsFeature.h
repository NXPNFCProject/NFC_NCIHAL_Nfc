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
 *
 ******************************************************************************/

/*This will describe the functions and variables which can be loaded by
 libraries. This will also expose  utility API */

#pragma once
#include <jni.h>
#include "NativeNfcExtnsEvt.h"
#define LOG_TAG "libnfc_jni_extns"
#define NAME_NXP_POLL_FOR_EFD_TIMEDELAY "NXP_POLL_FOR_EFD_TIMEDELAY"
#define NAME_NXP_NFCC_MERGE_SAK_ENABLE "NXP_NFCC_MERGE_SAK_ENABLE"
#define NAME_NXP_STAG_TIMEOUT_CFG "NXP_STAG_TIMEOUT_CFG"
#define NAME_NXP_RF_FILE_VERSION_INFO "NXP_RF_FILE_VERSION_INFO"
#define NAME_RF_STORAGE "RF_STORAGE"

#define UICC_TECH_BYTE_MASK 0xF0
#define ESE_TECH_BYTE_MASK 0x0F

extern "C" {
int nfc_initNativeFunctions(JNIEnv* e);
}

class NativeNfcExtnsImpl : public NativeNfcExtnsEvt {
 public:
  NativeNfcExtnsImpl();
  int notifyNfcEvt(std::string evtString);
  ~NativeNfcExtnsImpl();
};