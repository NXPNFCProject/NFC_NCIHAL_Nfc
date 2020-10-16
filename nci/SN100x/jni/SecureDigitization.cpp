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
#include "SecureDigitization.h"
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <nativehelper/ScopedLocalRef.h>
#include "JavaClassConstants.h"
#include "nfa_srd_int.h"
#if (NXP_EXTNS == TRUE && NXP_SRD == TRUE)
using android::base::StringPrintf;

extern bool nfc_debug_enabled;
SecureDigitization SecureDigitization::sSecSrd;

/*******************************************************************************
**
** Function:        getInstance
**
** Description:     Get the SecureDigitization singleton object.
**
** Returns:         SecureDigitization object.
**
*******************************************************************************/
SecureDigitization& SecureDigitization::getInstance() { return sSecSrd; }

/*******************************************************************************
**
** Function:        notifySrdEvt
**
** Description:     Notify SRD events to application.
**
** Returns:         Void
**
*******************************************************************************/
void SecureDigitization::notifySrdEvt(int event) {

  LOG(INFO) << StringPrintf("%s: event=0x%X", __func__, event);
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
  e->CallVoidMethod(mNativeData->manager, gCachedNfcManagerNotifySrdEvt,
                    (int)event);
  CHECK(!e->ExceptionCheck());
}
/*******************************************************************************
**
** Function:        initSrdNativeStruct
**
** Description:     Used to initialize the Native Srd notification methods
**
** Returns:         None.
**
*******************************************************************************/
void SecureDigitization::initSrdNativeStruct(JNIEnv* e, jobject o) {
  ScopedLocalRef<jclass> cls(e, e->GetObjectClass(o));

  gCachedNfcManagerNotifySrdEvt =
      e->GetMethodID(cls.get(), "notifySrdEvt", "(I)V");
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
void SecureDigitization::initialize(nfc_jni_native_data* native) {
  mNativeData = native;
}
/*******************************************************************************
**
** Function:        getSrdState
**
** Description:     Get SRD state.
**
** Returns:         SRD state.
**
*******************************************************************************/
int SecureDigitization::getSrdState() { return nfa_srd_get_state(); }
#endif
