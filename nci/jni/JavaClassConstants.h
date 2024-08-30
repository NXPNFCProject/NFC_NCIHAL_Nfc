/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/******************************************************************************
*
*  The original Work has been changed by NXP.
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
*  Copyright 2018-2021 NXP
*
******************************************************************************/
#pragma once
#include "NfcJniUtil.h"

namespace android {
extern jmethodID gCachedNfcManagerNotifyNdefMessageListeners;
extern jmethodID gCachedNfcManagerNotifyTransactionListeners;

/*
 * host-based card emulation
 */
extern jmethodID gCachedNfcManagerNotifyHostEmuActivated;
extern jmethodID gCachedNfcManagerNotifyHostEmuData;
extern jmethodID gCachedNfcManagerNotifyHostEmuDeactivated;
extern jmethodID gCachedNfcManagerNotifyEeUpdated;

extern const char* gNativeNfcTagClassName;
extern const char* gNativeNfcManagerClassName;
#if (NXP_EXTNS == TRUE)
extern const char* gNativeNfcSecureElementClassName;
extern jmethodID gCachedNfcManagerNotifySeListenDeactivated;
extern jmethodID gCachedNfcManagerNotifySeListenActivated;
extern jmethodID gCachedNfcManagerNotifyRfFieldDeactivated;
extern jmethodID gCachedNfcManagerNotifyRfFieldActivated;
extern const char* gNativeNfcMposManagerClassName;
extern const char* gNativeT4tNfceeClassName;
extern const char* gNativeExtFieldDetectClassName;
extern jmethodID gCachedNfcManagerNotifyLxDebugInfo;
extern jmethodID  gCachedNfcManagerNotifySrdEvt;
extern jmethodID gCachedNfcManagerNotifyEfdmEvt;
extern jmethodID gCachedNfcManagerNotifyTagAbortListeners;
#endif
}  // namespace android
