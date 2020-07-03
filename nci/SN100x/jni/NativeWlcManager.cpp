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
#if (NXP_EXTNS == TRUE)
#include "NativeWlcManager.h"
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nfa_wlc_api.h>
#include "nfc_config.h"

extern bool nfc_debug_enabled;
using android::base::StringPrintf;
namespace android {
extern bool isDiscoveryStarted();
extern void startRfDiscovery(bool isStart);
}  // namespace android

static JNINativeMethod jniWlcMethods[] = {
    {"doSendIntfExtStart", "([B)I",
     (void*)NativeWlcManager::wlcManager_sendIntfExtStart},
    {"doSendIntfExtStop", "(BB)I",
     (void*)NativeWlcManager::wlcManager_sendIntfExtStop},
    {"doCheckIsFeatureSupported", "()Z",
     (void*)NativeWlcManager::wlcManager_isFeatureSupported},
    {"doEnable", "(Z)I", (void*)NativeWlcManager::wlcManager_enable},
    {"doDisable", "()I", (void*)NativeWlcManager::wlcManager_disable}};

/*******************************************************************************
**
** Function:        getInstance
**
** Description:     Returns singleton instance of WlcManager
**
** Returns:         reference of the instance
**
*******************************************************************************/
NativeWlcManager& NativeWlcManager::getInstance() {
  static NativeWlcManager sWlcManager;
  return sWlcManager;
}

/*******************************************************************************
 **
 ** Function:        wlcManager_isFeatureSupported
 **
 ** Description:     return true if Device supports WLC feature indicated in
 **                  CORE_INIT_RSP If 0x81:(WLC RF Interface Extension) found
 **                  then, NFCC supports WLC feature
 **                  e: JVM environment.
 **                  o: Java object.
 **
 **Returns:         true if feature supported else false
 **
 ******************************************************************************/
jboolean NativeWlcManager::wlcManager_isFeatureSupported(JNIEnv* e, jobject) {
  return wlcManager.mIsFeatureSupported;
}

/*******************************************************************************
 **
 ** Function:        wlcManager_enable
 **
 ** Description:     Adds WLC specific Polling which will enable WLC Antenna
 **                  for detecting WLC Listeners. Along with DISCOVER_CMD,
 **                  WLC specific SET_CONFIG and DISCOVER_MAP will also
 **                  updated.
 **                  e: JVM environment.
 **                  o: Java object.
 **                  isNfcInitDone:If true then, Discovery will be started
 **                  else, will not be started
 **
 **Returns:         SUCCESS/FAILURE
 **
 ******************************************************************************/
tNFA_STATUS NativeWlcManager::wlcManager_enable(JNIEnv* e, jobject,
                                                jboolean isNfcInitDone) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);
  if (android::isDiscoveryStarted()) {
    android::startRfDiscovery(false);
  }
  NFA_WlcInitSubsystem(&NativeWlcManager::eventHandler);
  tNFA_STATUS status = NFA_WlcUpdateDiscovery(SET);
  if (status != NFA_STATUS_OK)
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: Not able to update WLC Discovery Parameter", __func__);
  if (isNfcInitDone) {
    android::startRfDiscovery(true);
  }
  return status;
}

/*******************************************************************************
 **
 ** Function:        wlcManager_disable
 **
 ** Description:     Sends regular Nfc forum (non WLC) specific SET_CONFIG,
 **                   DISCOVER_MAP and DISCOVER_CMD
 **                  e: JVM environment.
 **                  o: Java object.
 **
 **Returns:         SUCCESS/FAILURE
 **
 ******************************************************************************/
tNFA_STATUS NativeWlcManager::wlcManager_disable(JNIEnv* e, jobject) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", __func__);
  if (android::isDiscoveryStarted()) {
    android::startRfDiscovery(false);
  }
  tNFA_STATUS status = NFA_STATUS_FAILED;
  status = NFA_WlcUpdateDiscovery(RESET);
  if (status != NFA_STATUS_OK)
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: Not able to update WLC Discovery Parameter", __func__);
  android::startRfDiscovery(true);
  NFA_WlcDeInitSubsystem();
  return status;
}

/*******************************************************************************
 **
 ** Function:        notifyTagDetectedOnWlcAntenna
 **
 ** Description:     Sets flag if Tag detected on WLC Antenna
 **                  e: JVM environment.
 **                  o: Java object.
 **
 **Returns:         none
 **
 ******************************************************************************/
void NativeWlcManager::notifyTagDetectedOnWlcAntenna() {
  mIsTagDetectedOnWlcAntenna = true;
}

/*******************************************************************************
 **
 ** Function:        notifyTagDeactivatedOnWlcAntenna
 **
 ** Description:     Resets WLC tag detection flag
 **                  e: JVM environment.
 **                  o: Java object.
 **
 **Returns:         none
 **
 ******************************************************************************/
void NativeWlcManager::notifyTagDeactivatedOnWlcAntenna() {
  mIsTagDetectedOnWlcAntenna = false;
}

/*******************************************************************************
**
** Function:        wlcManager_sendIntfExtStart
**
** Description:     Sends WLC specific Interface Extension Start command which
**                  will trigger FW to start WPT
**                  e: JVM environment.
**                  o: Java object.
**                  wlcCap: WLC capability found in Ndef read done previously
**                  on WLC tag. This will be sent as a part of send extension
**                  start command
**
**Returns:         SUCCESS/FAILURE
**
*******************************************************************************/
tNFA_STATUS NativeWlcManager::wlcManager_sendIntfExtStart(JNIEnv* e, jobject,
                                                          jbyteArray wlcCap) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  ScopedByteArrayRO bytesData(e, wlcCap);
  uint8_t startParam[6] = {0};
  if (bytesData.size() == 0x00) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s:Empty wlcCap", __func__);
    return NFA_STATUS_FAILED;
  }
  if (bytesData.size() != 5) {
    DLOG_IF(ERROR, nfc_debug_enabled)
        << StringPrintf("%s:Wrong wlcCap length.", __func__);
    return NFA_STATUS_FAILED;
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s:wlcCap size : %zu", __func__, bytesData.size());
  /*Mode Autonomous:0x01 NonAutonomous:0x00*/
  uint8_t mode = 0x01;
  if (NfcConfig::hasKey(NAME_NXP_WLC_MODE))
    mode = (uint8_t)NfcConfig::getUnsigned(NAME_NXP_WLC_MODE);
  startParam[0] = mode;
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t* pWlcCap =
      const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytesData[0]));
  memcpy((startParam + 1), pWlcCap, bytesData.size());

  tNFA_RF_INTF_EXT_PARAMS rfIntfExtParams;
  rfIntfExtParams.rfIntfExtType = 0x81;
  rfIntfExtParams.data = startParam;
  rfIntfExtParams.dataLen = sizeof(startParam);
  SyncEventGuard g(wlcManager.mWlcEvent);
  status = NFA_WlcRfIntfExtStart(&rfIntfExtParams);
  if (status == NFA_STATUS_OK) {
    if (!wlcManager.mWlcEvent.wait(500)) {
      DLOG_IF(ERROR, nfc_debug_enabled)
          << StringPrintf("%s:Wlc Start response wait timeout", __func__);
      return NFA_STATUS_FAILED;
    }
    status = wlcManager.evtStatus;
  }
  return status;
}

/*******************************************************************************
 **
 ** Function:        wlcManager_sendIntfExtStop
 **
 ** Description:     Sends WLC specific Interface Extension Stop command which
 **                  will stop ongoing WPT
 **                  e: JVM environment.
 **                  o: Java object.
 **                  nextNfceeAction:
 **                  0x00: NFCC shall conclude the WLC session without removal
 **                  detection.
 **                  0x01: NFCC shall start Removal Detection If NFCC is in
 **                  WPT phase, it shall abort the WPT phase
 **                  0x02: NFCC shall remain in RST_POLL_ACTIVE and wait for
 **                  further RF frames on data channel
 **                  wlcCapWt:If Stop Parameter is 0x01, Timeout to be used
 **                  during removal detection
 **
 **Returns:         SUCCESS/FAILURE
 **
 ******************************************************************************/
tNFA_STATUS NativeWlcManager::wlcManager_sendIntfExtStop(JNIEnv* e, jobject,
                                                         jbyte nextNfceeAction,
                                                         jbyte wlcCapWt) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t stopParam[2] = {0};
  stopParam[0] = nextNfceeAction;
  stopParam[1] = wlcCapWt;
  tNFA_RF_INTF_EXT_PARAMS rfIntfExtParams;
  rfIntfExtParams.rfIntfExtType = 0x81;
  rfIntfExtParams.data = stopParam;
  rfIntfExtParams.dataLen = sizeof(stopParam);
  SyncEventGuard g(wlcManager.mWlcEvent);
  status = NFA_WlcRfIntfExtStop(&rfIntfExtParams);
  if (status == NFA_STATUS_OK) {
    if (!wlcManager.mWlcEvent.wait(500)) {
      DLOG_IF(ERROR, nfc_debug_enabled)
          << StringPrintf("%s:Wlc Start response wait timeout", __func__);
      return NFA_STATUS_FAILED;
    }
    status = wlcManager.evtStatus;
  }
  return status;
}

/*******************************************************************************
 **
 ** Function:        eventHandler
 **
 ** Description:     Handles events received from libnfc
 **                  e: JVM environment.
 **                  o: Java object.
 **
 **Returns:         none
 **
 ******************************************************************************/
void NativeWlcManager::eventHandler(uint8_t event,
                                    tNFA_CONN_EVT_DATA* eventData) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s: event : 0x%02x status : 0x%02x", __func__, event, eventData->status);
  wlcManager.evtStatus = eventData->status;
  switch (event) {
    case WLC_FEATURE_SUPPORTED_EVT:
      wlcManager.mIsFeatureSupported = true;
      break;
    case WLC_RF_INTF_EXT_START_EVT:
    case WLC_RF_INTF_EXT_STOP_EVT: {
      SyncEventGuard g(wlcManager.mWlcEvent);
      wlcManager.mWlcEvent.notifyOne();
    } break;
    default: {
      SyncEventGuard g(wlcManager.mWlcEvent);
      wlcManager.mWlcEvent.notifyOne();
    }
  }
}

/*******************************************************************************
**
** Function:        registerNatives
**
** Description:     Registers native implementations with JAVA
**                  e: JVM environment.
**
** Returns:         0x00 if success else otherwise
**
*******************************************************************************/
int NativeWlcManager::registerNatives(JNIEnv* e) {
  return jniRegisterNativeMethods(e, gNativeWlcJavaClassName, jniWlcMethods,
                                  NELEM(jniWlcMethods));
}
#endif