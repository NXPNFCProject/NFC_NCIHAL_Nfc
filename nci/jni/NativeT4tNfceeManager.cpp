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
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include "JavaClassConstants.h"
//#include "NativeT4tNfcee.h"
#include "NfcJniUtil.h"
extern bool nfc_debug_enabled;
using android::base::StringPrintf;
namespace android {
/*******************************************************************************
 **
 ** Function:        nfcManager_doWriteT4tData
 **
 ** Description:     Write the data into the T4T file of the specific file ID
 **
 ** Returns:         Return the size of data written
 **                  Return negative number of error code
 **
 *******************************************************************************/
jint t4tNfceeManager_doWriteT4tData(JNIEnv* e, jobject o, jbyteArray fileId,
                                    jbyteArray data, jint length) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  return 0;

  //return t4tNfcEe.t4tWriteData(e, o, fileId, data, length);
}
/*******************************************************************************
**
** Function:        nfcManager_doReadT4tData
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
jbyteArray t4tNfceeManager_doReadT4tData(JNIEnv* e, jobject o,
                                         jbyteArray fileId) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  return NULL;
  //return t4tNfcEe.t4tReadData(e, o, fileId);
}

/*****************************************************************************
 **
 ** Description:     JNI functions
 **
 *****************************************************************************/
static JNINativeMethod gMethods[] = {
    {"doWriteT4tData", "([B[BI)I", (void*)t4tNfceeManager_doWriteT4tData},
    {"doReadT4tData", "([B)[B", (void*)t4tNfceeManager_doReadT4tData},
};

/*******************************************************************************
 **
 ** Function:        register_com_android_nfc_NativeT4tNfcee
 **
 ** Description:     Regisgter JNI functions with Java Virtual Machine.
 **                  e: Environment of JVM.
 **
 ** Returns:         Status of registration.
 **
 *******************************************************************************/
int register_com_android_nfc_NativeT4tNfcee(JNIEnv* e) {
  return jniRegisterNativeMethods(e, gNativeT4tNfceeClassName, gMethods,
                                  NELEM(gMethods));
}
}  // namespace android
#endif