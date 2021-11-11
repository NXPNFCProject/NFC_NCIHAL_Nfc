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
#include <nativehelper/ScopedLocalRef.h>
#include "NfcJniUtil.h"
#include "SyncEvent.h"
#include "nfa_api.h"

#define extFieldDetectMode (NativeExtFieldDetect::getInstance())

/*EFDM STATUS*/
typedef enum {
  EFDSTATUS_SUCCESS = 0,
  EFDSTATUS_FAILED,
  EFDSTATUS_ERROR_ALREADY_STARTED,
  EFDSTATUS_ERROR_FEATURE_NOT_SUPPORTED,
  EFDSTATUS_ERROR_FEATURE_DISABLED_IN_CONFIG,
  EFDSTATUS_ERROR_NFC_IS_OFF,
  EFDSTATUS_ERROR_UNKNOWN,
  EFDSTATUS_ERROR_NOT_STARTED,
} ext_field_detect_status_t;

class NativeExtFieldDetect {
 public:
  nfc_jni_native_data* mNativeData;
  /*******************************************************************************
  **
  ** Function:        initialize
  **
  ** Description:     Initialize all member variables.
  **
  ** Returns:         void
  **
  *******************************************************************************/
  void initialize(nfc_jni_native_data* native);
  /*******************************************************************************
  **
  ** Function:        deinitialize
  **
  ** Description:     De-Initialize all member variables.
  **
  ** Returns:         void
  **
  *******************************************************************************/
  void deinitialize();
  /*****************************************************************************
  **
  ** Function:        getInstance
  **
  ** Description:     Get the NativeExtFieldDetect singleton object.
  **
  ** Returns:         NativeExtFieldDetect object.
  **
  *******************************************************************************/
  static NativeExtFieldDetect& getInstance();

  /*******************************************************************************
  **
  ** Function:        startExtendedFieldDetectMode
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
  int startExtendedFieldDetectMode(JNIEnv* e, jobject o, jint detectionTimeout);

  /*******************************************************************************
  **
  ** Function:        stopExtendedFieldDetectMode
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
  int stopExtendedFieldDetectMode(JNIEnv* e, jobject o);
  /*******************************************************************************
  **
  ** Function:        startEfdmTimer
  **
  ** Description:     Start Extended Field Timer.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void startEfdmTimer();
  /*******************************************************************************
  **
  ** Function:    isextendedFieldDetectMode
  **
  ** Description: The API returns the field mode detect is true/false
  **
  ** Returns:     true : if Extended field detect is ON else false
  **
  *******************************************************************************/
  bool isextendedFieldDetectMode();
  /*******************************************************************************
  **
  ** Function:        initEfdmNativeStruct
  **
  ** Description:     Used to initialize the Native EFDM notification methods
  **
  ** Returns:         None.
  **
  *******************************************************************************/
  void initEfdmNativeStruct(JNIEnv* e, jobject o);
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
  static void postEfdmTimeoutEvt(union sigval);

 private:
  bool mFirstRffieldON;
  bool mIsefdmStarted;
  static NativeExtFieldDetect sNativeExtFieldDetectInstance;
  int mEfdmTimerValue;
  static const int EFDM_TIMEOUT_EVT = 0xF2;
};
#endif
