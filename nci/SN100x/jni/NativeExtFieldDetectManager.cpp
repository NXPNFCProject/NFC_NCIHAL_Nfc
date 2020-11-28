/******************************************************************************
 *
 *  Copyright 2021 NXP
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
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include "JavaClassConstants.h"
#include "NativeExtFieldDetect.h"
#include "NfcJniUtil.h"
extern bool nfc_debug_enabled;
using android::base::StringPrintf;

namespace android {
/*******************************************************************************
**
** Function:        nativeFieldMgr_startExtendedFieldDetectMode
**
** Description:     This API performs to start extended field detect mode.
**
** Returns:         0x00 : EFDSTATUS_SUCCESS
**                  0x01 :EFDSTATUS_FAILED
**                  0x02 :EFDSTATUS_ERROR_ALREADY_STARTED
**                  0x03 :EFDSTATUS_ERROR_FEATURE_NOT_SUPPORTED
**                  0x04 :EFDSTATUS_ERROR_FEATURE_DISABLED_IN_CONFIG
**                  0x05 :EFDSTATUS_ERROR_NFC_IS_OFF
**                  0x06 :EFDSTATUS_ERROR_UNKNOWN
**
*******************************************************************************/
jint nativeFieldMgr_startExtendedFieldDetectMode(JNIEnv* e, jobject o,
                                                 jint detectionTimeout) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  return extFieldDetectMode.startExtendedFieldDetectMode(e, o,
                                                         detectionTimeout);
}
/*******************************************************************************
**
** Function:        nativeFieldMgr_stopExtendedFieldDetectMode
**
** Description:     This API performs to stop extended field detect mode.
**
** Returns:         0x00 : EFDSTATUS_SUCCESS
**                  0x01 :EFDSTATUS_FAILED
**                  0x05 :EFDSTATUS_ERROR_NFC_IS_OFF
**                  0x06 :EFDSTATUS_ERROR_UNKNOWN
**                  0x07 :EFDSTATUS_ERROR_NOT_STARTED
**
*******************************************************************************/
jint nativeFieldMgr_stopExtendedFieldDetectMode(JNIEnv* e, jobject o) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  return extFieldDetectMode.stopExtendedFieldDetectMode(e, o);
}
/*****************************************************************************
 **
 ** Description:     JNI functions
 **
 *****************************************************************************/
static JNINativeMethod gMethods[] = {
    {"startExtendedFieldDetectMode", "(I)I",
     (void*)nativeFieldMgr_startExtendedFieldDetectMode},
    {"stopExtendedFieldDetectMode", "()I",
     (void*)nativeFieldMgr_stopExtendedFieldDetectMode},
};

/*******************************************************************************
 **
 ** Function:        register_com_android_nfc_NativeExtFieldDetect
 **
 ** Description:     Regisgter JNI functions with Java Virtual Machine.
 **                  e: Environment of JVM.
 **
 ** Returns:         Status of registration.
 **
 *******************************************************************************/
int register_com_android_nfc_NativeExtFieldDetect(JNIEnv* e) {
  return jniRegisterNativeMethods(e, gNativeExtFieldDetectClassName, gMethods,
                                  NELEM(gMethods));
}
}  // namespace android
#endif
