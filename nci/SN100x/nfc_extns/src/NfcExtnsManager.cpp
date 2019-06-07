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
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include "NativeNfcExtnsEvt.h"
#include "NfcExtnsFeature.h"
#include "RoutingManager.h"
#include "STAG.h"
#include "phNxpConfig.h"
/*****************************************************************************
**
** public variables and functions
**
*****************************************************************************/

using android::base::StringPrintf;
const char* gNativeNfcExtnsJavaClassName =
    "com/android/nfc/dhimpl/NativeNfcfeatManager";

bool nfc_debug_enabled = true;
int registerJniNatives(JNIEnv* e);
static jint nfcManager_getSecureElementTechList(JNIEnv* e, jobject o);
jbyteArray nfcManager_startCoverAuth(JNIEnv* e, jobject o);
bool nfcManager_stopCoverAuth(JNIEnv*, jobject);
jbyteArray nfcManager_transceiveAuthData(JNIEnv* e, jobject, jbyteArray);
NativeNfcExtnsImpl gNfcExtnsImplInstance;
namespace android {
bool nfcManager_isNfcActive();
extern tNFA_STATUS NxpNfc_Write_Cmd_Common(uint8_t retlen, uint8_t* buffer);
}  // namespace android
extern void registerNfcNotifier(NativeNfcExtnsEvt* obj);
extern bool checkIsodepRouting();
extern jint nfaManager_getSESupportTech(JNIEnv* e, jobject o);
jmethodID gCachedNfcManagerNotifyExternalFieldDetected;
/*******************************************************************************
 **
 ** Function:        nfc_initNativeFunctions()
 **
 ** Description:     Initializes native variables and functions.
 **                  Register JNI native functions to java layer
 **
 ** Returns:         int
 **
 *******************************************************************************/
int nfc_initNativeFunctions(JNIEnv* e) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);
  registerJniNatives(e);
  registerNfcNotifier(&gNfcExtnsImplInstance);
  STAG::getInstance().initializeSTAG();
  return 0;
}

/*******************************************************************************
 **
 ** Function:        checkAndStopCoverAuth
 **
 ** Description:     Stop led cover if DWP wired mode/commit
 **                  routing/discovery
 **
 ** Returns:         void
 **
 *******************************************************************************/
void checkAndStopPropMode(void) {
  STAG::getInstance().stopCoverAuth(NULL, NULL);
}

static JNINativeMethod jniExtnsMethods[] = {
    {"startCoverAuth", "()[B", (void*)nfcManager_startCoverAuth},
    {"stopCoverAuth", "()Z", (void*)nfcManager_stopCoverAuth},
    {"transceiveAuthData", "([B)[B", (void*)nfcManager_transceiveAuthData},
    {"doGetSecureElementTechList", "()I",
     (void*)nfcManager_getSecureElementTechList},
};

static jint nfcManager_getSecureElementTechList(JNIEnv* e, jobject o) {
  if (android::nfcManager_isNfcActive() == false) {
    return 0x00;
  }

  return (ESE_TECH_BYTE_MASK & nfaManager_getSESupportTech(e, o));
}
/*******************************************************************************
 **
 ** Function:        nfc_startCoverAuth()
 **
 ** Description:     Sends the proprietary authentication command
 **
 ** Returns:         byte[] - contains the tag details
 **
 *******************************************************************************/
jbyteArray nfcManager_startCoverAuth(JNIEnv* e, jobject o) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);
  ScopedLocalRef<jclass> cls(e, e->GetObjectClass(o));
  gCachedNfcManagerNotifyExternalFieldDetected =
      e->GetMethodID(cls.get(), "notifyRfFieldDetectedPropTAG", "()V");
  return STAG::getInstance().startCoverAuth(e, o);
}

/*******************************************************************************
 **
 ** Function:        nfcManager_stopCoverAuth()
 **
 ** Description:     Sends the proprietary authentication command
 **
 ** Returns:         byte[] - contains the tag details
 **
 *******************************************************************************/
bool nfcManager_stopCoverAuth(JNIEnv* e, jobject o) {
  return STAG::getInstance().stopCoverAuth(e, o);
}
/*******************************************************************************
 **
 ** Function:        nfcManager_transceiveAuthData()
 **
 ** Description:     Sends the proprietary authentication command
 **
 ** Returns:         byte[] - contains the tag details
 **
 *******************************************************************************/
jbyteArray nfcManager_transceiveAuthData(JNIEnv* e, jobject o,
                                         jbyteArray data) {
  return STAG::getInstance().transceiveAuthData(e, o, data);
}
/*******************************************************************************
 **
 ** Function:        registerJniNatives()
 **
 ** Description:     This will register function to JAVA layer.
 **
 ** Returns:         SUCCESS/FAILURE
 **
 *******************************************************************************/
int registerJniNatives(JNIEnv* e) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: registerJniNatives", __func__);
  jniRegisterNativeMethods(e, gNativeNfcExtnsJavaClassName, jniExtnsMethods,
                           NELEM(jniExtnsMethods));
  return 0;
}

/*******************************************************************************
 **
 ** Function:        notifyNfcEvt()
 **
 ** Description:     This function will receive the EVT from JNI library.
 **
 ** Returns:         SUCCESS/FAILURE
 **
 *******************************************************************************/
int NativeNfcExtnsImpl::notifyNfcEvt(std::string evtString) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: evtString : %s", __func__, evtString.c_str());
  if (!strcmp(evtString.c_str(), "nfcManager_CommitRouting") ||
      !strcmp(evtString.c_str(), "nfcManager_doDeinitialize") ||
      !strcmp(evtString.c_str(), "nfcManager_doSetScreenState") ||
      !strcmp(evtString.c_str(),
              "nativeNfcSecureElement_doOpenSecureElementConnection")) {
    checkAndStopPropMode();
  } else if (!strcmp(evtString.c_str(), "checkIsodepRouting")) {
     checkIsodepRouting();
  }
  return 0;
}

/*******************************************************************************
 **
 ** Function:        NativeNfcExtnsImpl()
 **
 ** Description:     NativeNfcExtnsImpl destructor called.
 **
 ** Returns:         SUCCESS/FAILURE
 **
 *******************************************************************************/
NativeNfcExtnsImpl::~NativeNfcExtnsImpl() {}
/*******************************************************************************
 **
 ** Function:        NativeNfcExtnsEvt()
 **
 ** Description:     This function will be called during NativeNfcExtnsEvt
 *object destroy.
 **
 ** Returns:         SUCCESS/FAILURE
 **
 *******************************************************************************/
NativeNfcExtnsEvt::~NativeNfcExtnsEvt() {}

NativeNfcExtnsImpl::NativeNfcExtnsImpl() {}