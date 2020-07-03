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
#pragma once
#include <nfa_api.h>
#include "NativeJniExtns.h"
#include "SyncEvent.h"
#define wlcManager (NativeWlcManager::getInstance())

class NativeWlcManager {
 private:
  tNFA_STATUS evtStatus;
  const char* gNativeWlcJavaClassName =
      "com/android/nfc/dhimpl/NativeWlcManager";
  bool mIsFeatureSupported = false;
  bool mIsTagDetectedOnWlcAntenna = true;
  SyncEvent mWlcEvent;

  /*****************************************************************************
  **
  ** Function:        NativeWlcManager
  **
  ** Description:     Private constructor to enforce singleton
  **
  ** Returns:         None
  **
  *****************************************************************************/
  NativeWlcManager(){};

  /*****************************************************************************
  **
  ** Function:        prepareSetConfigs
  **
  ** Description:     Creates TLVs to update WLC specific setconfig values
  **                  configs: Getconfg TLVs. This vector gives the values which
  **                  are currently set in NFCC. This helps to avoid overwriting
  **                 if the values are already as per WLC requirement
  **
  ** Returns:         None
  **
  *****************************************************************************/
  void prepareSetConfigs(vector<uint8_t>& configs);

  /*****************************************************************************
  **
  ** Function:        sendSetConfig
  **
  ** Description:     Prepares setconfig command by adding payload passed to it
  **                  as argument and sends
  **
  ** Returns:         SUCCESS/FAILURE
  **
  *****************************************************************************/
  tNFA_STATUS sendSetConfig(const vector<uint8_t>& configs);

 public:
  /*****************************************************************************
  **
  ** Function:        getInstance
  **
  ** Description:     Returns singleton instance of WlcManager
  **
  ** Returns:         reference of the instance
  **
  *****************************************************************************/
  static NativeWlcManager& getInstance();

  /*****************************************************************************
  **
  ** Function:        registerNatives
  **
  ** Description:     Registers native implementations with JAVA
  **                  e: JVM environment.
  **
  ** Returns:         0x00 if success else otherwise
  **
  *****************************************************************************/
  int registerNatives(JNIEnv* e);

  /*****************************************************************************
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
  **Returns:          SUCCESS/FAILURE
  **
  *****************************************************************************/
  static tNFA_STATUS wlcManager_sendIntfExtStart(JNIEnv* e, jobject,
                                                 jbyteArray wlcCap);

  /*****************************************************************************
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
   **Returns:          SUCCESS/FAILURE
   **
   ****************************************************************************/
  static tNFA_STATUS wlcManager_sendIntfExtStop(JNIEnv* e, jobject,
                                                jbyte nextNfceeAction,
                                                jbyte wlcCapWt);

  /*****************************************************************************
   **
   ** Function:        wlcManager_isFeatureSupported
   **
   ** Description:     return true if Device supports WLC feature indicated in
   **                  CORE_INIT_RSP If 0x81:(WLC RF Interface Extension) found
   **                  then, NFCC supports WLC feature
   **                  e: JVM environment.
   **                  o: Java object.
   **
   **Returns:          true if feature supported else false
   **
   ****************************************************************************/
  static jboolean wlcManager_isFeatureSupported(JNIEnv* e, jobject);

  /*****************************************************************************
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
   **Returns:          SUCCESS/FAILURE
   **
   ****************************************************************************/
  static tNFA_STATUS wlcManager_enable(JNIEnv* e, jobject,
                                       jboolean isNfcInitDone);

  /*****************************************************************************
   **
   ** Function:        wlcManager_disable
   **
   ** Description:     Sends regular Nfc forum (non WLC) specific SET_CONFIG,
   **                   DISCOVER_MAP and DISCOVER_CMD
   **                  e: JVM environment.
   **                  o: Java object.
   **
   **Returns:          SUCCESS/FAILURE
   **
   ****************************************************************************/
  static tNFA_STATUS wlcManager_disable(JNIEnv* e, jobject);

  /*****************************************************************************
   **
   ** Function:        eventHandler
   **
   ** Description:     Handles events received from libnfc
   **                  e: JVM environment.
   **                  o: Java object.
   **
   **Returns:          none
   **
   ****************************************************************************/
  static void eventHandler(uint8_t event, tNFA_CONN_EVT_DATA* eventData);

  /*****************************************************************************
   **
   ** Function:        notifyTagDetectedOnWlcAntenna
   **
   ** Description:     Sets flag if Tag detected on WLC Antenna
   **                  e: JVM environment.
   **                  o: Java object.
   **
   **Returns:         none
   **
   ****************************************************************************/
  void notifyTagDetectedOnWlcAntenna();

  /*****************************************************************************
   **
   ** Function:        notifyTagDeactivatedOnWlcAntenna
   **
   ** Description:     Resets WLC tag detection flag
   **                  e: JVM environment.
   **                  o: Java object.
   **
   **Returns:         none
   **
   ****************************************************************************/
  void notifyTagDeactivatedOnWlcAntenna();
};
#endif