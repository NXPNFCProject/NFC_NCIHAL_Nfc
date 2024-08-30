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

/*
 *  Tag-reading, tag-writing operations.
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
 *  Copyright 2020-2024 NXP
 *
 ******************************************************************************/
#pragma once
#include <vector>
#include "NfcJniUtil.h"
#include "NfcStatsUtil.h"
#include "SyncEvent.h"
#include "nfa_rw_api.h"

#define MIN_FWI (11)
#define MAX_FWI (14)
#if (NXP_EXTNS == TRUE)
#define NON_STD_CARD_SAK (0x13)
#define TIME_MUL_100MS 100

typedef struct activationParams {
  int mTechParams;
  int mTechLibNfcTypes;
} activationParams_t;
#endif

class NfcTag {
  friend class NfcTagTest;

 public:
  enum ActivationState { Idle, Sleep, Active };
  static const int MAX_NUM_TECHNOLOGY =
      11;  // max number of technologies supported by one or more tags
#if (NXP_EXTNS == TRUE)
  int mTechLibNfcTypesDiscData[MAX_NUM_TECHNOLOGY];  // array of detailed tag
                                                     // types ( RF Protocol)
                                                     // received from
                                                     // RF_DISC_NTF
  int mNumDiscNtf;
  activationParams_t mActivationParams_t;
#endif
  int mTechList[MAX_NUM_TECHNOLOGY];  // array of NFC technologies according to
                                      // NFC service
  int mTechHandles[MAX_NUM_TECHNOLOGY];  // array of tag handles (RF DISC ID)
                                         // according to NFC service received
                                         // from RF_INTF_ACTIVATED NTF
  int mTechLibNfcTypes[MAX_NUM_TECHNOLOGY];  // array of detailed tag types (RF
                                             // Protocol) according to NFC
                                             // service received from
                                             // RF_INTF_ACTIVATED NTF
  int mNumTechList;  // current number of NFC technologies in the list
#if (NXP_EXTNS == TRUE)
  int mTechListIndex;
  bool mIsMultiProtocolTag;
  int  mCurrentRequestedProtocol;
  uint8_t mNfcID0[4];
#endif
  NfcStatsUtil* mNfcStatsUtil;

  /*******************************************************************************
  **
  ** Function:        NfcTag
  **
  ** Description:     Initialize member variables.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  NfcTag();

  /*******************************************************************************
  **
  ** Function:        getInstance
  **
  ** Description:     Get a reference to the singleton NfcTag object.
  **
  ** Returns:         Reference to NfcTag object.
  **
  *******************************************************************************/
  static NfcTag& getInstance();

  /*******************************************************************************
  **
  ** Function:        initialize
  **
  ** Description:     Reset member variables.
  **                  native: Native data.
  ** Returns:         None
  **
  *******************************************************************************/
  void initialize(nfc_jni_native_data* native);

  /*******************************************************************************
  **
  ** Function:        abort
  **
  ** Description:     Unblock all operations.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void abort();

  /*******************************************************************************
  **
  ** Function:        connectionEventHandler
  **
  ** Description:     Handle connection-related events.
  **                  event: event code.
  **                  data: pointer to event data.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void connectionEventHandler(uint8_t event, tNFA_CONN_EVT_DATA* data);

  /*******************************************************************************
  **
  ** Function:        isActivated
  **
  ** Description:     Is tag activated?
  **
  ** Returns:         True if tag is activated.
  **
  *******************************************************************************/
  bool isActivated();

  /*******************************************************************************
  **
  ** Function:        getActivationState
  **
  ** Description:     What is the current state: Idle, Sleep, or Activated.
  **
  ** Returns:         Idle, Sleep, or Activated.
  **
  *******************************************************************************/
  ActivationState getActivationState();

  /*******************************************************************************
  **
  ** Function:        setDeactivationState
  **
  ** Description:     Set the current state: Idle or Sleep.
  **                  deactivated: state of deactivation.
  **
  ** Returns:         None.
  **
  *******************************************************************************/
  void setDeactivationState(tNFA_DEACTIVATED& deactivated);

  /*******************************************************************************
  **
  ** Function:        setActivationState
  **
  ** Description:     Set the current state to Active.
  **
  ** Returns:         None.
  **
  *******************************************************************************/
  void setActivationState();
#if (NXP_EXTNS == TRUE)

  /*******************************************************************************
   **
   ** Function:        notifyNfcAbortTagops()
   **
   ** Description:     Notify service to abort TAG ops.
   **
   ** Returns:         None
   **
   *******************************************************************************/
  static void notifyNfcAbortTagops(tNFC_DEACT_REASON reason);

  /*******************************************************************************
  **
  ** Function         clearNonStdMfcState
  **
  ** Description      Clear Non standard MFC states
  **
  ** Returns          None
  **
  *******************************************************************************/
  void clearNonStdMfcState();

#endif
  /*******************************************************************************
  **
  ** Function:        getProtocol
  **
  ** Description:     Get the protocol of the current tag.
  **
  ** Returns:         Protocol number.
  **
  *******************************************************************************/
  tNFC_PROTOCOL getProtocol();

  /*******************************************************************************
  **
  ** Function:        selectFirstTag
  **
  ** Description:     When multiple tags are discovered, just select the first
  *one to activate.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void selectFirstTag();

#if (NXP_EXTNS == TRUE)
  /*******************************************************************************
  **
  ** Function:        isCashBeeActivated
  **
  ** Description:     checks if cashbee tag is detected
  **
  ** Returns:         True if tag is activated.
  **
  *******************************************************************************/
  bool isCashBeeActivated();

  /*******************************************************************************
  **
  ** Function:        isNfcCombiCard
  **
  ** Description:     checks if NFCDEP combi card detected
  **
  ** Returns:         True if tag is activated.
  **
  *******************************************************************************/
  bool isNfcCombiCard();
#endif

  /*******************************************************************************
  **
  ** Function:        selectNextTagIfExists
  **
  ** Description:     When multiple tags are discovered, selects the Next one to
  **                  activate.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void selectNextTagIfExists();

  /*******************************************************************************
  **
  ** Function:        getT1tMaxMessageSize
  **
  ** Description:     Get the maximum size (octet) that a T1T can store.
  **
  ** Returns:         Maximum size in octets.
  **
  *******************************************************************************/
  int getT1tMaxMessageSize();

/*******************************************************************************
**
** Function:        isNfcForumT2T
**
** Description:     Whether tag is Nfc-Forum based and uses read command for
**                  presence check.
**
** Returns:         True if tag is isNfcForumT2T.
**
*******************************************************************************/
bool isNfcForumT2T();

  /*******************************************************************************
  **
  ** Function:        isMifareUltralight
  **
  ** Description:     Whether the currently activated tag is Mifare Ultralight.
  **
  ** Returns:         True if tag is Mifare Ultralight.
  **
  *******************************************************************************/
  bool isMifareUltralight();

  /*******************************************************************************
  **
  ** Function:        isMifareDESFire
  **
  ** Description:     Whether the currently activated tag is Mifare DESFire.
  **
  ** Returns:         True if tag is Mifare DESFire.
  **
  *******************************************************************************/
  bool isMifareDESFire();

  /*******************************************************************************
  **
  ** Function:        isFelicaLite
  **
  ** Description:     Whether the currently activated tag is Felica Lite.
  **
  ** Returns:         True if tag is Felica Lite.
  **
  *******************************************************************************/
  bool isFelicaLite();

  /*******************************************************************************
  **
  ** Function:        isT2tNackResponse
  **
  ** Description:     Whether the response is a T2T NACK response.
  **                  See NFC Digital Protocol Technical Specification
  *(2010-11-17).
  **                  Chapter 9 (Type 2 Tag Platform), section 9.6 (READ).
  **                  response: buffer contains T2T response.
  **                  responseLen: length of the response.
  **
  ** Returns:         True if the response is NACK
  **
  *******************************************************************************/
  bool isT2tNackResponse(const uint8_t* response, uint32_t responseLen);

  /*******************************************************************************
  **
  ** Function:        isNdefDetectionTimedOut
  **
  ** Description:     Whether NDEF-detection algorithm has timed out.
  **
  ** Returns:         True if NDEF-detection algorithm timed out.
  **
  *******************************************************************************/
  bool isNdefDetectionTimedOut();

  /*******************************************************************************
  **
  ** Function         setActive
  **
  ** Description      Sets the active state for the object
  **
  ** Returns          None.
  **
  *******************************************************************************/
  void setActive(bool active);

  /*******************************************************************************
  **
  ** Function:        isDynamicTagId
  **
  ** Description:     Whether a tag has a dynamic tag ID.
  **
  ** Returns:         True if ID is dynamic.
  **
  *******************************************************************************/
  bool isDynamicTagId();

  /*******************************************************************************
  **
  ** Function:        resetAllTransceiveTimeouts
  **
  ** Description:     Reset all timeouts for all technologies to default values.
  **
  ** Returns:         none
  **
  *******************************************************************************/
  void resetAllTransceiveTimeouts();

  /*******************************************************************************
  **
  ** Function:        isDefaultTransceiveTimeout
  **
  ** Description:     Is the timeout value for a technology the default value?
  **                  techId: one of the values in TARGET_TYPE_* defined in
  *NfcJniUtil.h.
  **                  timeout: Check this value against the default value.
  **
  ** Returns:         True if timeout is equal to the default value.
  **
  *******************************************************************************/
  bool isDefaultTransceiveTimeout(int techId, int timeout);

  /*******************************************************************************
  **
  ** Function:        getTransceiveTimeout
  **
  ** Description:     Get the timeout value for one technology.
  **                  techId: one of the values in TARGET_TYPE_* defined in
  **                  NfcJniUtil.h
  **
  ** Returns:         Timeout value in millisecond.
  **
  *******************************************************************************/
  int getTransceiveTimeout(int techId);

  /*******************************************************************************
  **
  ** Function:        setTransceiveTimeout
  **
  ** Description:     Set the timeout value for one technology.
  **                  techId: one of the values in TARGET_TYPE_* defined in
  *NfcJniUtil.h
  **                  timeout: timeout value in millisecond.
  **
  ** Returns:         Timeout value.
  **
  *******************************************************************************/
  void setTransceiveTimeout(int techId, int timeout);

  /*******************************************************************************
  **
  ** Function:        getPresenceCheckAlgorithm
  **
  ** Description:     Get presence-check algorithm from .conf file.
  **
  ** Returns:         Presence-check algorithm.
  **
  *******************************************************************************/
  tNFA_RW_PRES_CHK_OPTION getPresenceCheckAlgorithm();

  /*******************************************************************************
  **
  ** Function:        isInfineonMyDMove
  **
  ** Description:     Whether the currently activated tag is Infineon My-D Move.
  **
  ** Returns:         True if tag is Infineon My-D Move.
  **
  *******************************************************************************/
  bool isInfineonMyDMove();

  /*******************************************************************************
  **
  ** Function:        isKovioType2Tag
  **
  ** Description:     Whether the currently activated tag is Kovio 2Kb RFID tag.
  **                  It is a NFC Forum type-2 tag.
  **
  ** Returns:         True if tag is Kovio 2Kb RFID tag.
  **
  *******************************************************************************/
  bool isKovioType2Tag();

  /*******************************************************************************
  **
  ** Function:        setMultiProtocolTagSupport
  **
  ** Description:     Update mIsMultiProtocolTag
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void setMultiProtocolTagSupport(bool isMultiProtocolSupported);

  /*******************************************************************************
  **
  ** Function:        setNumDiscNtf
  **
  ** Description:     Update mNumDiscNtf
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void setNumDiscNtf(int numDiscNtfValue);

  /*******************************************************************************
  **
  ** Function:        getNumDiscNtf
  **
  ** Description:     number of discovery notifications received from NFCC after
  **                  last RF DISCOVERY state
  **
  ** Returns:         number of discovery notifications received from NFCC
  **
  *******************************************************************************/
  int getNumDiscNtf();

 private:
  std::vector<int> mTechnologyTimeoutsTable;
  std::vector<int> mTechnologyDefaultTimeoutsTable;
  nfc_jni_native_data* mNativeData;
  bool mIsActivated;
  ActivationState mActivationState;
  tNFC_PROTOCOL mProtocol;
  int mtT1tMaxMessageSize;  // T1T max NDEF message size
  tNFA_STATUS mReadCompletedStatus;
  int mLastKovioUidLen;         // len of uid of last Kovio tag activated
  bool mNdefDetectionTimedOut;  // whether NDEF detection algorithm timed out
  tNFC_RF_TECH_PARAMS
      mTechParams[MAX_NUM_TECHNOLOGY];  // array of technology parameters
  SyncEvent mReadCompleteEvent;
  struct timespec mLastKovioTime;  // time of last Kovio tag activation
  uint8_t mLastKovioUid[NFC_KOVIO_MAX_LEN];  // uid of last Kovio tag activated
  bool mIsDynamicTagId;  // whether the tag has dynamic tag ID
  tNFA_RW_PRES_CHK_OPTION mPresenceCheckAlgorithm;
  bool mIsFelicaLite;
  int mTechHandlesDiscData[MAX_NUM_TECHNOLOGY];      // array of tag handles (RF
                                                     // DISC ID) received from
                                                     // RF_DISC_NTF
#if (NXP_EXTNS == FALSE)
  int mTechLibNfcTypesDiscData[MAX_NUM_TECHNOLOGY];  // array of detailed tag
                                                     // types ( RF Protocol)
                                                     // received from
                                                     // RF_DISC_NTF
  int mNumDiscNtf;
#endif
  int mNumDiscTechList;
  int mTechListTail;  // Index of Last added entry in mTechList
  /*******************************************************************************
  **
  ** Function:        IsSameKovio
  **
  ** Description:     Checks if tag activate is the same (UID) Kovio tag
  *previously
  **                  activated.  This is needed due to a problem with some
  *Kovio
  **                  tags re-activating multiple times.
  **                  activationData: data from activation.
  **
  ** Returns:         true if the activation is from the same tag previously
  **                  activated, false otherwise
  **
  *******************************************************************************/
  bool IsSameKovio(tNFA_ACTIVATED& activationData);

  /*******************************************************************************
  **
  ** Function:        discoverTechnologies
  **
  ** Description:     Discover the technologies that NFC service needs by
  **                  interpreting the data strucutures from the stack.
  **                  activationData: data from activation.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void discoverTechnologies(tNFA_ACTIVATED& activationData);

  /*******************************************************************************
  **
  ** Function:        discoverTechnologies
  **
  ** Description:     Discover the technologies that NFC service needs by
  **                  interpreting the data strucutures from the stack.
  **                  discoveryData: data from discovery events(s).
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void discoverTechnologies(tNFA_DISC_RESULT& discoveryData);

  /*******************************************************************************
  **
  ** Function:        createNativeNfcTag
  **
  ** Description:     Create a brand new Java NativeNfcTag object;
  **                  fill the objects's member variables with data;
  **                  notify NFC service;
  **                  activationData: data from activation.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void createNativeNfcTag(tNFA_ACTIVATED& activationData);

#if (NXP_EXTNS == TRUE)
  /*******************************************************************************
  **
  ** Function:        storeActivationParams
  **
  ** Description:     stores tag activation parameters for backup
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void storeActivationParams();
#endif

  /*******************************************************************************
  **
  ** Function:        fillNativeNfcTagMembers1
  **
  ** Description:     Fill NativeNfcTag's members: mProtocols, mTechList,
  **                  mTechHandles, mTechLibNfcTypes.
  **                  e: JVM environment.
  **                  tag_cls: Java NativeNfcTag class.
  **                  tag: Java NativeNfcTag object.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void fillNativeNfcTagMembers1(JNIEnv* e, jclass tag_cls, jobject tag);

  /*******************************************************************************
  **
  ** Function:        fillNativeNfcTagMembers2
  **
  ** Description:     Fill NativeNfcTag's members: mConnectedTechIndex or
  *mConnectedTechnology.
  **                  The original Google's implementation is in
  *set_target_pollBytes(
  **                  in com_android_nfc_NativeNfcTag.cpp;
  **                  e: JVM environment.
  **                  tag_cls: Java NativeNfcTag class.
  **                  tag: Java NativeNfcTag object.
  **                  activationData: data from activation.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void fillNativeNfcTagMembers2(JNIEnv* e, jclass tag_cls, jobject tag,
                                tNFA_ACTIVATED& activationData);

  /*******************************************************************************
  **
  ** Function:        fillNativeNfcTagMembers3
  **
  ** Description:     Fill NativeNfcTag's members: mTechPollBytes.
  **                  The original Google's implementation is in
  *set_target_pollBytes(
  **                  in com_android_nfc_NativeNfcTag.cpp;
  **                  e: JVM environment.
  **                  tag_cls: Java NativeNfcTag class.
  **                  tag: Java NativeNfcTag object.
  **                  activationData: data from activation.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void fillNativeNfcTagMembers3(JNIEnv* e, jclass tag_cls, jobject tag,
                                tNFA_ACTIVATED& activationData);

  /*******************************************************************************
  **
  ** Function:        fillNativeNfcTagMembers4
  **
  ** Description:     Fill NativeNfcTag's members: mTechActBytes.
  **                  The original Google's implementation is in
  *set_target_activationBytes()
  **                  in com_android_nfc_NativeNfcTag.cpp;
  **                  e: JVM environment.
  **                  tag_cls: Java NativeNfcTag class.
  **                  tag: Java NativeNfcTag object.
  **                  activationData: data from activation.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void fillNativeNfcTagMembers4(JNIEnv* e, jclass tag_cls, jobject tag,
                                tNFA_ACTIVATED& activationData);

  /*******************************************************************************
  **
  ** Function:        fillNativeNfcTagMembers5
  **
  ** Description:     Fill NativeNfcTag's members: mUid.
  **                  The original Google's implementation is in
  *nfc_jni_Discovery_notification_callback()
  **                  in com_android_nfc_NativeNfcManager.cpp;
  **                  e: JVM environment.
  **                  tag_cls: Java NativeNfcTag class.
  **                  tag: Java NativeNfcTag object.
  **                  activationData: data from activation.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void fillNativeNfcTagMembers5(JNIEnv* e, jclass tag_cls, jobject tag,
                                tNFA_ACTIVATED& activationData);

  /*******************************************************************************
  **
  ** Function:        resetTechnologies
  **
  ** Description:     Clear all data related to the technology, protocol of the
  *tag.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void resetTechnologies();

  /*******************************************************************************
  **
  ** Function:        calculateT1tMaxMessageSize
  **
  ** Description:     Calculate type-1 tag's max message size based on header
  *ROM bytes.
  **                  activate: reference to activation data.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void calculateT1tMaxMessageSize(tNFA_ACTIVATED& activate);
};
