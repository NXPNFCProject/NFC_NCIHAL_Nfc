/******************************************************************************
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
 *  Copyright 2018-2020 NXP
 *
 ******************************************************************************/
#include "MposManager.h"
#include "JavaClassConstants.h"
//#include "_OverrideLog.h"
#include <base/logging.h>
#include <android-base/stringprintf.h>
#include <nativehelper/ScopedPrimitiveArray.h>
extern bool nfc_debug_enabled;
using android::base::StringPrintf;
namespace android
{
typedef enum {
  LOW_POWER = 0x00,
  ULTRA_LOW_POWER,
}POWER_MODE;

static const char* covertToString(POWER_MODE mode);
extern void enableLastRfDiscovery();
extern void startRfDiscovery(bool isStart);
extern void nfcManager_disableDiscovery(JNIEnv* e, jobject o);
extern std::string ConvertJavaStrToStdString(JNIEnv* env, jstring s);

/*******************************************************************************
**
** Function:        nativeNfcMposManage_doMposSetReaderMode
**
** Description:     Set/Reset the MPOS reader mode
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         SUCCESS/FAILED/BUSY/REJECTED.
**
*******************************************************************************/
static int nativeNfcMposManage_doMposSetReaderMode(JNIEnv*, jobject, bool on)
{
  tNFA_STATUS status = NFA_STATUS_REJECTED;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:enter", __func__);
  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    status = MposManager::getInstance().setMposReaderMode(on);
  } else {
    DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: ETSI_READER not available. Returning", __func__);
  }
  return status;
}

/*******************************************************************************
**
** Function:        nativeNfcMposManage_doMposGetReaderMode
**
** Description:     Provides the state of the reader mode on/off
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         TRUE/FALSE.
**
*******************************************************************************/
static bool nativeNfcMposManager_doMposGetReaderMode(JNIEnv*, jobject)
{
  bool isEnabled = false;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:enter", __func__);
  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    isEnabled = MposManager::getInstance().getMposReaderMode();
  } else {
    DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: ETSI_READER not available. Returning", __func__);
  }
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("isEnabled =%x", isEnabled);
  return isEnabled;
}

/*******************************************************************************
**
** Function:        nativeNfcMcrManage_doConfigureSecureReaderMode
**
** Description:     e: JVM environment.
**                  o: Java object.
**                  on: Set/Reset the MPOS reader mode
**                  rdrType: Requested Reader Type. e.g. "MFC"
**
** Returns:         SUCCESS/FAILED/BUSY/REJECTED.
**
*******************************************************************************/
static int nativeNfcMcrManage_doConfigureSecureReaderMode(JNIEnv* e, jobject, bool on,
                                                    jstring rdrType) {
  tNFA_STATUS status = NFA_STATUS_REJECTED;
  std::string readertype = ConvertJavaStrToStdString(e, rdrType);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:enter", __func__);
#ifdef FEATURE_SECURE_READER
  /* The functionality of Mifare Classic reader over eSE is some what same as
   * MPOS. Hence, reusing the existing code */
  status = MposManager::getInstance().setMposReaderMode(on, readertype);
#endif
  return status;
}

/*******************************************************************************
**
** Function:        nativeNfcMposManage_doStopPoll
**
** Description:     Enables the specific power mode
**                  e: JVM environment.
**                  o: Java object.
**                  mode: LOW/ULTRA LOW POWER
**
** Returns:         None.
**
*******************************************************************************/
static void nativeNfcMposManage_doStopPoll(JNIEnv* e, jobject, int mode)
{
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s:enter - %s mode", __func__, covertToString((POWER_MODE)mode));
  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    switch (mode) {
    case LOW_POWER:
      nfcManager_disableDiscovery(NULL,NULL);
      break;
    case ULTRA_LOW_POWER:
      startRfDiscovery(false);
      break;
    default:
      break;
    }
  } else {
    DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: ETSI_READER not available. Returning", __func__);
  }
}

/*******************************************************************************
**
** Function:        nativeNfcMposManage_doStartPoll
**
** Description:     Enables the NFC RF discovery
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         None.
**
*******************************************************************************/
static void nativeNfcMposManage_doStartPoll(JNIEnv*, jobject)
{
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s:enter", __func__);
  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    enableLastRfDiscovery();
  } else {
    DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: ETSI_READER not available. Returning", __func__);
  }
}

/*******************************************************************************
**
** Function:        covertToString
**
** Description:     Converts power mode type int to string
**
** Returns:         String.
**
*******************************************************************************/
static const char* covertToString(POWER_MODE mode)
{
  switch(mode)
  {
  case LOW_POWER:
    return "LOW_POWER";
  case ULTRA_LOW_POWER:
    return "ULTRA_LOW_POWER";
  default:
    return "INVALID";
  }
}

/*****************************************************************************
**
** Description:     JNI functions
**
*****************************************************************************/
static JNINativeMethod gMethods[] = {
    {"doMposSetReaderMode", "(Z)I",
     (void*)nativeNfcMposManage_doMposSetReaderMode},
    {"doMposGetReaderMode", "()Z",
     (void*)nativeNfcMposManager_doMposGetReaderMode},
    {"doConfigureSecureReaderMode", "(ZLjava/lang/String;)I",
     (void*)nativeNfcMcrManage_doConfigureSecureReaderMode},

    {"doStopPoll", "(I)V", (void*)nativeNfcMposManage_doStopPoll},

    {"doStartPoll", "()V", (void*)nativeNfcMposManage_doStartPoll},
};

/*******************************************************************************
**
** Function:        register_com_android_nfc_NativeNfcSecureElement
**
** Description:     Regisgter JNI functions with Java Virtual Machine.
**                  e: Environment of JVM.
**
** Returns:         Status of registration.
**
*******************************************************************************/
int register_com_android_nfc_NativeNfcMposManager(JNIEnv *e)
{
  return jniRegisterNativeMethods(e, gNativeNfcMposManagerClassName, gMethods,
      NELEM(gMethods));
}

} // namespace android

