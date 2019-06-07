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
 *
 ******************************************************************************/
#pragma once
#include <jni.h>
#include "Mutex.h"
#include "nfa_api.h"
#define MAX_STAG_RETRY 0x03
#define STAG_RETRY_DELAY 70000

#define NXP_STAG_START_AUTH_DEFAULT_TIMEOUT 30
#define NXP_STAG_TRANS1_DEFAULT_TIMEOUT 100
#define NXP_STAG_TRANS2_DEFAULT_TIMEOUT 1000
#define NXP_STAG_DEFAULT_CMD_TIMEOUT 100

#define NXP_STAG_TIMEOUT_BUF_LEN 0x04

#define NXP_STAG_CMD_TIMEOUT_DEFAULT_SCALING_FACTOR \
  10 /*S-tag command timeout scaling factor of 10 in ms*/

#define NFC_STAG_STATUS_BYTE_0 0xB0
#define NFC_STAG_STATUS_BYTE_1 0xB1
#define NFC_STAG_STATUS_BYTE_2 0xB2
#define AUTH_STATUS_EFD_ON 0x1E

#define AUTH_CMD_TIMEOUT 2 /* S-tag command timeout in secs*/
#define AUTH_START_OID 0x3C
#define AUTH_STOP_OID 0x3A
#define AUTH_TRANS_OID 0x3B
#define AUTH_RAW_OID 0x39

typedef struct stag_cmd_timeout {
  uint16_t start_poll_timeout;
  uint16_t trans1_cmd_timeout;
  uint16_t trans2_cmd_timeout;
  uint16_t default_cmd_timeout;
} stag_cmd_timeout_t;

typedef enum ExtFieldDetectState {
  STATE_STOPPED,
  STATE_PAUSED,
  STATE_RUNNING,
} ExtFieldDetectState_t;

typedef enum {
  STATUS_STARTED = 0x00,
  STATUS_REJECTED,
  STATUS_FIELD_DETECTED = 0xE0,
  STATUS_NOT_STARTED = 0xFF
} tSTAGStatus;

typedef enum {
  startPoll = 0x00,
  startPollA,
  stopPoll,
  Transcieve,
  RawExchange
} tAuthCmdType;

extern jmethodID gCachedNfcManagerNotifyExternalFieldDetected;

class StartExtFieldDetect;

class STAG {
  STAG();
  static STAG mInstance;
  tSTAGStatus sCoverAuthSessionOpened;
  bool stagNfcOff;
  void resumeFieldDetect();
  StartExtFieldDetect* mStartFieldDetectObject;
  void startFieldDetect();
  void pauseFieldDetect();
  void stopFieldDetect();
  void get_STAG_timeout_values();
  Mutex stagAuthMutex;

 public:
  int timeout;
  static STAG& getInstance();
  void initializeSTAG();
  jbyteArray startCoverAuth(JNIEnv* e, jobject o);
  bool stopCoverAuth(JNIEnv* e, jobject o);
  jbyteArray transceiveAuthData(JNIEnv* e, jobject o, jbyteArray data);
  tNFA_STATUS Send_CoverAuthPropCmd(tAuthCmdType type,
                                    std::vector<uint8_t> xmitBuffer,
                                    std::vector<uint8_t>& rspBuffer);
  ExtFieldDetectState_t getThreadState();
};

class StartExtFieldDetect {
  ExtFieldDetectState_t mState;

 public:
  StartExtFieldDetect();
  void setThreadState(ExtFieldDetectState_t value);
  ExtFieldDetectState_t getThreadState();
};