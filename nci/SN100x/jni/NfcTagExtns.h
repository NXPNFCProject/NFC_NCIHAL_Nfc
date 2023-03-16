/*
 *  Tag-reading, tag-writing operations.
 */
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
 *  Copyright 2022-2023 NXP
 *
 ******************************************************************************/
#pragma once

#include <NfcTag.h>
#include <SecureDigitization.h>
#include <SecureElement.h>
#include <time.h>

#include <vector>

#include "nfa_api.h"
#include "nfa_rw_api.h"

#define IS_MULTIPROTO_MFC_TAG()                 \
  (NfcTag::getInstance().mIsMultiProtocolTag && \
   NfcTag::getInstance().mCurrentRequestedProtocol == NFC_PROTOCOL_MIFARE)

typedef uint32_t tTagStatus;

enum class TAG_API_REQUEST {
  TAG_RESELECT_API = 1,
  TAG_CHECK_NDEF_API,
  TAG_DO_TRANSCEIVE_API,
};

enum class TAG_OPERATION {
  TAG_UNKNOWN_OPERATION = 0,
  TAG_HALT_PICC_OPERATION = 1,
  TAG_DEACTIVATE_OPERATION,
  TAG_DEACTIVATE_RSP_OPERATION,
  TAG_RECONNECT_OPERATION,
  TAG_RECONNECT_FAILED_OPERATION,
  TAG_CLEAR_STATE_OPERATION,
  TAG_SKIP_NDEF,
};

enum class EVENT_TYPE {
  NFA_SELECT_RESULT_EVENT = 1,
  NFA_DEACTIVATE_EVENT,
  NFA_DEACTIVATE_FAIL_EVENT,
  NFA_DISC_RESULT_EVENT,
  NFA_ACTIVATED_EVENT,
};

class NfcTagExtns {
  tNFC_RESULT_DEVT
      discovery_ntf; /* Non-standard RF discovery notification details */
  tNFC_INTF_PARAMS intf_param;  /* Non-standard Interface type and parameters */
  static NfcTagExtns sTagExtns; /* NfcTagExts static object*/
  bool isNonStdCardSupported;   /* Configuration file option */
  struct timespec LastDetectedTime; /* Stores Non-Standard tag detection time */
  vector<uint32_t>
      mNonStdCardTimeDiff; /* Predefined reference time within which same tag
                              detected consecutively*/
  tNFA_INTF_TYPE sTagActivatedProtocol; /* current activated protocol */
  uint8_t sTagActivatedMode;            /* current activated mode */
  int sTagConnectedTargetType; /* Tag technology requested through connect API*/

  /*
   * Index for Non-standard tag type
   * into mNonStdCardTimeDiff array*/
  static const int MFC = 0;
  static const int ISO_DEP = 1;

  uint8_t mNfcID0[4]; /* ISO-DEP TypeB NfcID value*/
  uint32_t tagState;  /* Current state in NfcTagExtns being processed*/

  bool isMfcTransceiveFailed;

  /**
   * Non-standard tag state as per the API request
   * or event/notifications received.
   */
  static const uint32_t TAG_SKIP_ISODEP_ACT_TYPE = 1 << 0;
  static const uint32_t TAG_MFC_NON_STD_TYPE = 1 << 1;
  static const uint32_t TAG_SKIP_NDEF_TYPE = 1 << 2;
  static const uint32_t TAG_NON_STD_SAK_TYPE = 1 << 3;
  static const uint32_t TAG_CASHBEE_TYPE = 1 << 4;
  static const uint32_t TAG_DEACTIVATE_TO_SLEEP = 1 << 5;
  static const uint32_t TAG_DEACTIVATE_TO_IDLE = 1 << 6;
  static const uint32_t TAG_ISODEP_DEACTIVATE_FAIL = 1 << 7;

  /*
   * API invocation based handling for
   * Non-standard tag
   * */
  tTagStatus performHaltPICC();
  tTagStatus performTagDeactivation();
  tTagStatus updateTagState();
  tTagStatus performTagReconnect();
  tTagStatus performTagReconnectFailed();
  tTagStatus clearTagState();
  tTagStatus checkAndSkipNdef();

  /*
   * Connection callback event based handling for
   * Non-standard tag
   * */
  void processDeactivateEvent(tNFA_CONN_EVT_DATA* eventData, EVENT_TYPE event);
  void processtagSelectEvent(tNFA_CONN_EVT_DATA* data);
  void processDiscoveryNtf(tNFA_CONN_EVT_DATA* data);
  void processActivatedNtf(tNFA_CONN_EVT_DATA* data);
  void processMfcTransFailed();

  // Support methods for Non-standard tag handling
  void storeNonStdTagData();
  tNFA_STATUS isTagDetectedInRefTime();
  void updateNonStdTagState(uint8_t protocol, uint8_t more_disc_ntf);
  void clearNonStdMfcState();
  void clearNonStdTagData();
  bool isTagDetectedInRefTime(uint32_t reference);
  void updateNfcID0Param(uint8_t* nfcID0);
  bool isListenMode(tNFA_ACTIVATED& activated);
  bool checkActivatedProtoParameters(tNFA_ACTIVATED& activationData);

 public:
  /**
   * Public constants for Non-standard tag handling
   * status values.
   */
  static const int TAG_STATUS_UNKNOWN = -1;  // Default unknown status
  static const int TAG_STATUS_SUCCESS = 0;   // Normal/No-Error operation
  static const int TAG_STATUS_STANDARD = 1;  // Identified as Standard TAG
  static const int TAG_STATUS_PROPRIETARY =
      2;                                   // Identified as Non-standard TAG
  static const int TAG_STATUS_LOST = 3;    // Tag detection failed/lost
  static const int TAG_STATUS_FAILED = 4;  // Failed while processing

  static NfcTagExtns& getInstance();
  void initialize();
  tTagStatus processNonStdTagOperation(TAG_API_REQUEST caller,
                                       TAG_OPERATION operation);
  void processNonStdNtfHandler(EVENT_TYPE event, tNFA_CONN_EVT_DATA* eventDat);
  bool isNonStdMFCTagDetected();
  bool checkAndClearNonStdTagState();
  uint8_t getActivatedMode();
  uint8_t getActivatedProtocol();
  void setRfProtocol(tNFA_INTF_TYPE rfProtocol, uint8_t mode);
  void setCurrentTargetType(int type);
  void abortTagOperation();
  bool shouldSkipProtoActivate(tNFC_PROTOCOL protocol);
  bool isMfcTransFailed();
  void resetMfcTransceiveFlag();
};
