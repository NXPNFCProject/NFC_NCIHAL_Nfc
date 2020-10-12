/******************************************************************************
 *
 *  Copyright 2020 NXP
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

#pragma once
#include "nfa_hci_api.h"
#include "NfcJniUtil.h"

#if (NXP_EXTNS == TRUE && NXP_SRD == TRUE)
class SecureDigitization {
 private:
  static SecureDigitization sSecSrd;

 public:
  nfc_jni_native_data* mNativeData;
  jmethodID gCachedNfcManagerNotifySrdEvt;

  /*******************************************************************************
  **
  ** Function:        getInstance
  **
  ** Description:     Get the SecureDigitization singleton object.
  **
  ** Returns:         SecureDigitization object.
  **
  *******************************************************************************/
  static SecureDigitization& getInstance();
  /*******************************************************************************
  **
  ** Function:        initSrdNativeStruct
  **
  ** Description:     Used to initialize the Native SRD notification methods
  **
  ** Returns:         None.
  **
  *******************************************************************************/
  void initSrdNativeStruct(JNIEnv* e, jobject o);
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
  ** Function:        getSrdState
  **
  ** Description:     Get SRD state.
  **
  ** Returns:         SRD state.
  **
  *******************************************************************************/
  int getSrdState();
  /*******************************************************************************
  **
  ** Function:        notifySrdEvt
  **
  ** Description:     Notify SRD events to application.
  **
  ** Returns:         Void
  **
  *******************************************************************************/
  void notifySrdEvt(int event);
};
#endif
