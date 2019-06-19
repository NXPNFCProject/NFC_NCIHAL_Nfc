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

#pragma once
/*Singleton class for handling Native JNI Extns Init
  and loading jni extns library and initilaizing the functions in it*/

#ifndef NULL
#define NULL 0
#endif

#include "NativeNfcExtnsEvt.h"
#include "NfcJniUtil.h"
#include "jni.h"
#include "string"
typedef int (*fp_InitNative_t)(JNIEnv* e);
struct fpRegisterNfcExtns {
  fp_InitNative_t initNativeJni;
};

class NativeJniExtns {
  const char* extns_jni_path;
  void* lib_handle;
  fpRegisterNfcExtns regNfcExtnsFunc;
  NativeJniExtns();
  static NativeJniExtns nativeExtnsObj;
  bool loadExtnsLibrary();
  bool unloadExtnsLibrary();

 public:
  static NativeJniExtns& getInstance();
  void notifyNfcEvent(std::string evt, void* evt_data = NULL);
  void initialize(JNIEnv* e);
  bool isExtensionPresent();
  nfc_jni_native_data* gNativeData;
  void initializeNativeData(nfc_jni_native_data* native);
  ~NativeJniExtns();
};
