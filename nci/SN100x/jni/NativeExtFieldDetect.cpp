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
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

  mEfdmTimerValue = detectionTimeout;

  if (!android::nfcManager_isNfcActive() ||
      android::nfcManager_isNfcDisabling()) {
    return EFDSTATUS_ERROR_NFC_IS_OFF;
  } else if (mIsefdmStarted) {
    return EFDSTATUS_ERROR_ALREADY_STARTED;
  }
  int efdStatus = EFDSTATUS_FAILED;
  uint8_t num = 0x00;
  const uint8_t EXTENDED_FIELD_TAG_ENABLE = 0x01;
  if (NfcConfig::hasKey(NAME_NXP_EXTENDED_FIELD_DETECT_MODE)) {
    num = NfcConfig::getUnsigned(NAME_NXP_EXTENDED_FIELD_DETECT_MODE);
    if (num != EXTENDED_FIELD_TAG_ENABLE) {
      return EFDSTATUS_ERROR_FEATURE_DISABLED_IN_CONFIG;
    }
  } else {
    return EFDSTATUS_ERROR_FEATURE_NOT_SUPPORTED;
  }

  /*Stop Rf discovery*/
  if (android::isDiscoveryStarted()) {
    android::startRfDiscovery(false);
  }

  /*Configure to keep desired Poll Phases (as before) but only add
   * FieldDetect Mode for Listen Phase */
  NFA_SetFieldDetectMode(true);
  if (NFA_DisableListening() == NFA_STATUS_OK) {
    efdStatus = EFDSTATUS_SUCCESS;
  }

  if (efdStatus == EFDSTATUS_SUCCESS) {
    mIsefdmStarted = true;
  }

  /*Start Rf discovery*/
  if (!android::isDiscoveryStarted()) {
    android::startRfDiscovery(true);
  }

  mFirstRffieldON = true;

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Exit 0x%2x", __func__, efdStatus);

  return efdStatus;
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
  int efdStatus = EFDSTATUS_FAILED;

  if (!android::nfcManager_isNfcActive() ||
      android::nfcManager_isNfcDisabling()) {
    return EFDSTATUS_ERROR_NFC_IS_OFF;
  } else if (!mIsefdmStarted) {
    return EFDSTATUS_ERROR_NOT_STARTED;
  }

  /*Kill the timer if running*/
  mEfdmTimer.kill();

  /*Stop Rf discovery*/
  if (android::isDiscoveryStarted()) {
    android::startRfDiscovery(false);
  }

  /*Configure to keep desired Poll Phases (as before) but only add
   * FieldDetect Mode for Listen Phase */
  NFA_SetFieldDetectMode(false);
  if (NFA_EnableListening() == NFA_STATUS_OK) {
    efdStatus = EFDSTATUS_SUCCESS;
  }

  /*Start RF discovery*/
  if (!android::isDiscoveryStarted()) {
    android::startRfDiscovery(true);
  }

  if (mIsefdmStarted == true) {
    mIsefdmStarted = false;
  }

  return efdStatus;
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
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

  JNIEnv* e = NULL;

  if (NULL == mNativeData) {
    return;
  }
  ScopedAttach attach(mNativeData->vm, &e);
  if (e == NULL) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s: jni env is null", __func__);
    return;
  }

  if (mFirstRffieldON) {
    /* Start efdm timer only for 1st RF ON*/
       mFirstRffieldON = false;
    /* Posting efdm timeout event to application */
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: Interval Timer started..0x%2x", __func__, mEfdmTimerValue);
    mEfdmTimer.set(mEfdmTimerValue, NativeExtFieldDetect::postEfdmTimeoutEvt);
  }

  CHECK(!e->ExceptionCheck());
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
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
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

  NativeExtFieldDetect& nEfdm = NativeExtFieldDetect::getInstance();
  JNIEnv* e = NULL;

  if (NULL == nEfdm.mNativeData) {
    return;
  }
  ScopedAttach attach(nEfdm.mNativeData->vm, &e);
  if (e == NULL) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s: jni env is null", __func__);
    return;
  }

  /*Stop Rf discovery*/
  if (android::isDiscoveryStarted()) {
    android::startRfDiscovery(false);
  }

  e->CallVoidMethod(nEfdm.mNativeData->manager,
                    android::gCachedNfcManagerNotifyEfdmEvt,
                    EFDM_TIMEOUT_EVT);

  CHECK(!e->ExceptionCheck());
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
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
bool NativeExtFieldDetect::isextendedFieldDetectMode() { return mIsefdmStarted; }

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
  ScopedLocalRef<jclass> cls(e, e->GetObjectClass(o));

  android::gCachedNfcManagerNotifyEfdmEvt =
      e->GetMethodID(cls.get(), "notifyEfdmEvt", "(I)V");
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
  mNativeData = native;
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
  if (isextendedFieldDetectMode()) {
    if (stopExtendedFieldDetectMode(NULL, NULL) != EFDSTATUS_SUCCESS) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s : Failed Switching to Normal mode.", __func__);
    }
  }
}
#endif
