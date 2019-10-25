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
#include "NativeJniExtns.h"
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <dlfcn.h>
#include "SecureElement.h"
#include "nfa_api.h"
using android::base::StringPrintf;

/*Static Variables */
NativeJniExtns NativeJniExtns::nativeExtnsObj;
NativeNfcExtnsEvt* gNfcExtnsImplInstance = NULL;
void registerNfcNotifier(NativeNfcExtnsEvt* obj);
/*****************************************************************************
**
** public variables and functions
**
*****************************************************************************/
extern bool nfc_debug_enabled;
namespace android {
extern tNFA_STATUS NxpPropCmd_send(uint8_t* pData4Tx, uint8_t dataLen,
                                   uint8_t* rsp_len, uint8_t* rsp_buf,
                                   uint32_t rspTimeout, tHAL_NFC_ENTRY* halMgr);
extern bool isDiscoveryStarted();
extern void startRfDiscovery(bool isStart);
extern int nfcManager_doPartialInitialize(JNIEnv* e, jobject o, jint mode);
extern int nfcManager_doPartialDeInitialize(JNIEnv*, jobject);
extern bool nfcManager_isNfcActive();
}  // namespace android

NativeJniExtns& NativeJniExtns::getInstance() { return nativeExtnsObj; }

/*******************************************************************************
 **
 ** Function:        NativeJniExtns
 **
 ** Description:     This is called during init
 **
 ** Returns:         None
 **
 *******************************************************************************/
NativeJniExtns::NativeJniExtns() : lib_handle(NULL) {
  memset(&regNfcExtnsFunc, 0, sizeof(fpRegisterNfcExtns));
  gNativeData = NULL;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);
  extns_jni_path = "/system/lib64/libnfc_jni_extns.so";
}

/*******************************************************************************
 **
 ** Function:        loadExtnsLibrary
 **
 ** Description:     This api will load the jni extns library
 **                  and initialize the native functions and variables in it.
 **
 ** Returns:         None
 **
 *******************************************************************************/
bool NativeJniExtns::loadExtnsLibrary() {
  DLOG_IF(INFO, true) << StringPrintf("%s: Enter", __func__);
  char* error;
  /*Clear the previous dlerrors if any*/
  if ((error = dlerror()) != NULL) {
    DLOG_IF(ERROR, nfc_debug_enabled)
      << StringPrintf("%s: Clear previous dlerror message = %s",
           __func__, error);
  }
  lib_handle = dlopen("/system/lib64/libnfc_jni_extns.so", RTLD_NOW);

  if (lib_handle == NULL) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s: Unable to load library ", __func__);
    return false;
  }
  if ((error = dlerror()) != NULL) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s: Unable to load library %s", __func__, error);
    return false;
  }

  regNfcExtnsFunc.initNativeJni =
      (fp_InitNative_t)dlsym(lib_handle, "nfc_initNativeFunctions");
  if ((error = dlerror()) != NULL) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s: Unable to link the symbol %s", __func__, error);
    dlclose(lib_handle);
    lib_handle = NULL;
    return false;
  }
  return true;
}

/*******************************************************************************
 **
 ** Function:        unloadExtnsLibrary
 **
 ** Description:     This api will close  the opened jni extns library
 **                  and initialize the native functions and variables in it.
 **
 ** Returns:         None
 **
 *******************************************************************************/
bool NativeJniExtns::unloadExtnsLibrary() {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);
  if (lib_handle != NULL) {
    dlclose(lib_handle);
    lib_handle = NULL;
  }
  return true;
}
/*******************************************************************************
 **
 ** Function:        initialize
 **
 ** Description:     This will load the library and  call method to register
 **                  functions from extns JNI.
 **
 ** Returns:         None
 **
 *******************************************************************************/
void NativeJniExtns::initialize(JNIEnv* e) {
  if (loadExtnsLibrary()) {
    DLOG_IF(INFO, true) << StringPrintf("%s: Calling library initialize",
                                        __func__);
    (*regNfcExtnsFunc.initNativeJni)(e);
  }
}
/*******************************************************************************
 **
 ** Function:        isExtensionPresent
 **
 ** Description:     Used externally to determine if extension is present or not.
 **
 ** Returns:         'true' if extension is Present, else 'false'.
 **
 *******************************************************************************/
bool NativeJniExtns::isExtensionPresent() {
  return (lib_handle != NULL) ? true : false;
}
/*******************************************************************************
**
** Function:        initializeNativeData
**
** Description:     initialize native data
**
** Returns:         None
**
******************************************************************************/
void NativeJniExtns::initializeNativeData(nfc_jni_native_data* native) {
  gNativeData = native;
}
/*******************************************************************************
 **
 ** Function:        NativeJniExtns
 **
 ** Description:     Free variables.
 **
 ** Returns:         None
 **
 *******************************************************************************/
NativeJniExtns::~NativeJniExtns() { unloadExtnsLibrary(); };

/*******************************************************************************
**
** Function:        notifyNfcEvent
**
** Description:     Notify NFC event notifier to registered extns client
**                  library.
**
** Returns:         None
**
*******************************************************************************/
void NativeJniExtns::notifyNfcEvent(std::string evt, void* evt_data,
                                    void* evt_code) {
  if (gNfcExtnsImplInstance != NULL)
    gNfcExtnsImplInstance->notifyNfcEvt(evt, evt_data, evt_code);
}

/*******************************************************************************
**
** Function:        registerNfcNotifier
**
** Description:     Register NFC event notifier to receive events from JNI
**                  to extns library.
**
** Returns:         Native data structure.
**
*******************************************************************************/
void registerNfcNotifier(NativeNfcExtnsEvt* obj) {
  gNfcExtnsImplInstance = obj;
}
