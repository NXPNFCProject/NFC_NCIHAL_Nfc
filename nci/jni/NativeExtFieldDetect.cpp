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
#include "NativeExtFieldDetect.h"
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <string.h>
#include "IntervalTimer.h"
#include "nfa_api.h"
#include "nfc_config.h"

using android::base::StringPrintf;
using namespace std;

extern bool nfc_debug_enabled;

NativeExtFieldDetect NativeExtFieldDetect::sNativeExtFieldDetectInstance;
IntervalTimer mEfdmTimer;

namespace android {
extern jint nfcManager_enableDebugNtf(JNIEnv* e, jobject o, jbyte fieldValue);
extern void startRfDiscovery(bool isStart);
extern bool isDiscoveryStarted();
extern tNFA_STATUS NxpNfc_Write_Cmd_Common(uint8_t retlen, uint8_t* buffer);
extern bool nfcManager_isNfcActive();
extern bool nfcManager_isNfcDisabling();
jmethodID gCachedNfcManagerNotifyEfdmEvt;
}  // namespace android

/*****************************************************************************
**
** Function:        getInstance
**
** Description:     Get the NativeExtFieldDetect singleton object.
**
** Returns:         NativeExtFieldDetect object.
**
*******************************************************************************/
NativeExtFieldDetect& NativeExtFieldDetect::getInstance() {
  return sNativeExtFieldDetectInstance;
}

/*******************************************************************************
**
** Function:        startExtendedFieldDetectMode
**
** Description:     This API performs to start extended field detect mode.
**
** Returns:         0x00 :EFDSTATUS_SUCCESS
**                  0x01 :EFDSTATUS_FAILED
**                  0x02 :EFDSTATUS_ERROR_ALREADY_STARTED
**                  0x03 :EFDSTATUS_ERROR_FEATURE_NOT_SUPPORTED
**                  0x04 :EFDSTATUS_ERROR_FEATURE_DISABLED_IN_CONFIG
**                  0x05 :EFDSTATUS_ERROR_NFC_IS_OFF
**                  0x06 :EFDSTATUS_ERROR_UNKNOWN
**
*******************************************************************************/
int NativeExtFieldDetect::startExtendedFieldDetectMode(JNIEnv* e, jobject o,
                                                       jint detectionTimeout) {
  return 0x03;
}
/*******************************************************************************
**
** Function:        stopExtendedFieldDetectMode
**
** Description:     This API performs to stop extended field detect mode.
**
** Returns:         0x00 :EFDSTATUS_SUCCESS
**                  0x01 :EFDSTATUS_FAILED
**                  0x05 :EFDSTATUS_ERROR_NFC_IS_OFF
**                  0x06 :EFDSTATUS_ERROR_UNKNOWN
**                  0x07 :EFDSTATUS_ERROR_NOT_STARTED
**
*******************************************************************************/
int NativeExtFieldDetect::stopExtendedFieldDetectMode(JNIEnv* e, jobject o) {
  return 0x06;
}
/*******************************************************************************
**
** Function:        startEfdmTimer
**
** Description:     Start Extended Field Timer.
**
** Returns:         None
**
*******************************************************************************/
void NativeExtFieldDetect::startEfdmTimer() {
  return ;
}
/*******************************************************************************
**
** Function:        postEfdmTimeoutEvt
**
** Description:     Notifies Efdm timeout event to service
**                  sigval: signal
**
** Returns:         None
**
*******************************************************************************/
void NativeExtFieldDetect::postEfdmTimeoutEvt(union sigval) {
  return ;
}

/*******************************************************************************
**
** Function:      isextendedFieldDetectMode
**
** Description:   The API returns the field mode detect is true/false
**
** Returns:       true : if Extended field detect is ON else false
**
*******************************************************************************/
bool NativeExtFieldDetect::isextendedFieldDetectMode() { return false; }

/*******************************************************************************
**
** Function:        initEfdmNativeStruct
**
** Description:     Used to initialize the Native Efdm notification methods
**
** Returns:         None.
**
*******************************************************************************/
void NativeExtFieldDetect::initEfdmNativeStruct(JNIEnv* e, jobject o) {
  return ;
}
/*******************************************************************************
**
** Function:        initialize
**
** Description:     Initialize all member variables.
**
** Returns:         void
**
*******************************************************************************/
void NativeExtFieldDetect::initialize(nfc_jni_native_data* native) {
  return ;
}
/*******************************************************************************
**
** Function:        deinitialize
**
** Description:     De-Initialize all member variables.
**
** Returns:         void
**
*******************************************************************************/
void NativeExtFieldDetect::deinitialize() {
 return ;
}
