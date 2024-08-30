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
 *  Copyright 2018-2024 NXP
 *
 ******************************************************************************/

/*
 *  Tag-reading, tag-writing operations.
 */
#include "NfcTag.h"

#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <log/log.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <statslog_nfc.h>

#include "JavaClassConstants.h"
#include "nfc_brcm_defs.h"
#include "nfc_config.h"
#include "rw_int.h"
#if (NXP_EXTNS == TRUE)
#include "IntervalTimer.h"
#include "NfcTagExtns.h"
#endif

using android::base::StringPrintf;

static void deleteglobaldata(JNIEnv* e);
static jobjectArray sTechPollBytes;
static jobjectArray gtechActBytes;
#if (NXP_EXTNS == TRUE)
int sLastSelectedTagId = 0;
#else
static int sLastSelectedTagId = 0;
#endif

#if (NXP_EXTNS == TRUE)
uint32_t TimeDiff(timespec start, timespec end);
int selectedId = 0;
static bool isP2pDetected = false;
namespace android {
  extern bool nfcManager_isReaderModeEnabled();
}
#endif

/*******************************************************************************
**
** Function:        NfcTag
**
** Description:     Initialize member variables.
**
** Returns:         None
**
*******************************************************************************/
NfcTag::NfcTag()
    : mNumDiscNtf(0),
      mNumTechList(0),
#if (NXP_EXTNS == TRUE)
      mTechListIndex(0),
      mIsMultiProtocolTag(false),
      mCurrentRequestedProtocol(NFC_PROTOCOL_UNKNOWN),
#endif
      mTechnologyTimeoutsTable(MAX_NUM_TECHNOLOGY),
      mNativeData(NULL),
      mIsActivated(false),
      mActivationState(Idle),
      mProtocol(NFC_PROTOCOL_UNKNOWN),
      mtT1tMaxMessageSize(0),
      mReadCompletedStatus(NFA_STATUS_OK),
      mLastKovioUidLen(0),
      mNdefDetectionTimedOut(false),
      mIsDynamicTagId(false),
      mPresenceCheckAlgorithm(NFA_RW_PRES_CHK_DEFAULT),
      mIsFelicaLite(false),
      mNumDiscTechList(0),
      mTechListTail(0) {
  memset(mTechList, 0, sizeof(mTechList));
  memset(mTechHandles, 0, sizeof(mTechHandles));
  memset(mTechLibNfcTypes, 0, sizeof(mTechLibNfcTypes));
  memset(mTechParams, 0, sizeof(mTechParams));
  memset(mLastKovioUid, 0, NFC_KOVIO_MAX_LEN);
#if (NXP_EXTNS == TRUE)
  memset(&mActivationParams_t, 0, sizeof(activationParams_t));
#endif
  memset (&mLastKovioTime, 0, sizeof(timespec));
  mNfcStatsUtil = new NfcStatsUtil();
}

/*******************************************************************************
**
** Function:        getInstance
**
** Description:     Get a reference to the singleton NfcTag object.
**
** Returns:         Reference to NfcTag object.
**
*******************************************************************************/
NfcTag& NfcTag::getInstance() {
  static NfcTag tag;
  return tag;
}

/*******************************************************************************
**
** Function:        initialize
**
** Description:     Reset member variables.
**                  native: Native data.
**
** Returns:         None
**
*******************************************************************************/
void NfcTag::initialize(nfc_jni_native_data* native) {
  mNativeData = native;
  mIsActivated = false;
  mActivationState = Idle;
  mProtocol = NFC_PROTOCOL_UNKNOWN;
#if (NXP_EXTNS == TRUE)
  mNumDiscNtf = 0;
  mNumDiscTechList = 0;
  mTechListIndex = 0;
  isP2pDetected = false;
  NfcTagExtns::getInstance().initialize();
#endif
  mtT1tMaxMessageSize = 0;
  mReadCompletedStatus = NFA_STATUS_OK;
  resetTechnologies();
  if (NfcConfig::hasKey(NAME_PRESENCE_CHECK_ALGORITHM))
    mPresenceCheckAlgorithm =
        NfcConfig::getUnsigned(NAME_PRESENCE_CHECK_ALGORITHM);
}

/*******************************************************************************
**
** Function:        abort
**
** Description:     Unblock all operations.
**
** Returns:         None
**
*******************************************************************************/
void NfcTag::abort() {
  SyncEventGuard g(mReadCompleteEvent);
  mReadCompleteEvent.notifyOne();
}

/*******************************************************************************
**
** Function:        getActivationState
**
** Description:     What is the current state: Idle, Sleep, or Activated.
**
** Returns:         Idle, Sleep, or Activated.
**
*******************************************************************************/
NfcTag::ActivationState NfcTag::getActivationState() {
  return mActivationState;
}

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
void NfcTag::setDeactivationState(tNFA_DEACTIVATED& deactivated) {
  static const char fn[] = "NfcTag::setDeactivationState";
  mActivationState = Idle;
  mNdefDetectionTimedOut = false;
  if (deactivated.type == NFA_DEACTIVATE_TYPE_SLEEP) mActivationState = Sleep;
  LOG(DEBUG) << StringPrintf("%s: state=%u", fn, mActivationState);
}

/*******************************************************************************
**
** Function:        setActivationState
**
** Description:     Set the current state to Active.
**
** Returns:         None.
**
*******************************************************************************/
void NfcTag::setActivationState() {
  static const char fn[] = "NfcTag::setActivationState";
  mNdefDetectionTimedOut = false;
  mActivationState = Active;
  LOG(DEBUG) << StringPrintf("%s: state=%u", fn, mActivationState);
}

/*******************************************************************************
**
** Function:        isActivated
**
** Description:     Is tag activated?
**
** Returns:         True if tag is activated.
**
*******************************************************************************/
bool NfcTag::isActivated() { return mIsActivated; }

/*******************************************************************************
**
** Function:        getProtocol
**
** Description:     Get the protocol of the current tag.
**
** Returns:         Protocol number.
**
*******************************************************************************/
tNFC_PROTOCOL NfcTag::getProtocol() { return mProtocol; }

/*******************************************************************************
**
** Function         TimeDiff
**
** Description      Computes time difference in milliseconds.
**
** Returns          Time difference in milliseconds
**
*******************************************************************************/
uint32_t TimeDiff(timespec start, timespec end) {
  timespec temp;
  if ((end.tv_nsec - start.tv_nsec) < 0) {
    temp.tv_sec = end.tv_sec - start.tv_sec - 1;
    temp.tv_nsec = 1000000000 + end.tv_nsec - start.tv_nsec;
  } else {
    temp.tv_sec = end.tv_sec - start.tv_sec;
    temp.tv_nsec = end.tv_nsec - start.tv_nsec;
  }

  return (temp.tv_sec * 1000) + (temp.tv_nsec / 1000000);
}

/*******************************************************************************
**
** Function:        IsSameKovio
**
** Description:     Checks if tag activate is the same (UID) Kovio tag
*previously
**                  activated.  This is needed due to a problem with some Kovio
**                  tags re-activating multiple times.
**                  activationData: data from activation.
**
** Returns:         true if the activation is from the same tag previously
**                  activated, false otherwise
**
*******************************************************************************/
bool NfcTag::IsSameKovio(tNFA_ACTIVATED& activationData) {
  static const char fn[] = "NfcTag::IsSameKovio";
  LOG(DEBUG) << StringPrintf("%s: enter", fn);
  tNFC_ACTIVATE_DEVT& rfDetail = activationData.activate_ntf;

  if (rfDetail.protocol != NFC_PROTOCOL_KOVIO) return false;

  memcpy(&(mTechParams[0]), &(rfDetail.rf_tech_param),
         sizeof(rfDetail.rf_tech_param));
  if (mTechParams[0].mode != NFC_DISCOVERY_TYPE_POLL_KOVIO) return false;

  struct timespec now;
  clock_gettime(CLOCK_REALTIME, &now);

  bool rVal = false;
  if (mTechParams[0].param.pk.uid_len == mLastKovioUidLen) {
    if (memcmp(mLastKovioUid, &mTechParams[0].param.pk.uid,
               mTechParams[0].param.pk.uid_len) == 0) {
      // same tag
      if (TimeDiff(mLastKovioTime, now) < 500) {
        // same tag within 500 ms, ignore activation
        rVal = true;
      }
    }
  }

  // save Kovio tag info
  if (!rVal) {
    if ((mLastKovioUidLen = mTechParams[0].param.pk.uid_len) >
        NFC_KOVIO_MAX_LEN)
      mLastKovioUidLen = NFC_KOVIO_MAX_LEN;
    memcpy(mLastKovioUid, mTechParams[0].param.pk.uid, mLastKovioUidLen);
  }
  mLastKovioTime = now;
  LOG(DEBUG) << StringPrintf("%s: exit, is same Kovio=%d", fn, rVal);
  return rVal;
}

/*******************************************************************************
**
** Function:        discoverTechnologies
**
** Description:     Discover the technologies that NFC service needs by
*interpreting
**                  the data structures from the stack.
**                  activationData: data from activation.
**
** Returns:         None
**
*******************************************************************************/
void NfcTag::discoverTechnologies(tNFA_ACTIVATED& activationData) {
  static const char fn[] = "NfcTag::discoverTechnologies (activation)";
  LOG(DEBUG) << StringPrintf("%s: enter", fn);
  tNFC_ACTIVATE_DEVT& rfDetail = activationData.activate_ntf;

  if (mTechListTail < (MAX_NUM_TECHNOLOGY - 1)) {
    mNumTechList = mTechListTail;
   } else {
    LOG(ERROR) << StringPrintf("%s: exceed max=%d", fn, MAX_NUM_TECHNOLOGY);
    android_errorWriteLog(0x534e4554, "189942532");
    goto TheEnd;
  }
  mTechHandles[mNumTechList] = rfDetail.rf_disc_id;
  mTechLibNfcTypes[mNumTechList] = rfDetail.protocol;

  // save the stack's data structure for interpretation later
  memcpy(&(mTechParams[mNumTechList]), &(rfDetail.rf_tech_param),
         sizeof(rfDetail.rf_tech_param));

  if (NFC_PROTOCOL_T1T == rfDetail.protocol) {
    mTechList[mNumTechList] =
        TARGET_TYPE_ISO14443_3A;  // is TagTechnology.NFC_A by Java API
  } else if (NFC_PROTOCOL_T2T == rfDetail.protocol) {
    mTechList[mNumTechList] =
        TARGET_TYPE_ISO14443_3A;  // is TagTechnology.NFC_A by Java API
    // could be MifFare UL or Classic or Kovio
    {
      // need to look at first byte of uid to find Manufacture Byte
      tNFC_RF_TECH_PARAMS tech_params;
      memcpy(&tech_params, &(rfDetail.rf_tech_param),
             sizeof(rfDetail.rf_tech_param));

      if ((tech_params.param.pa.nfcid1[0] == 0x04 &&
           rfDetail.rf_tech_param.param.pa.sel_rsp == 0) ||
          rfDetail.rf_tech_param.param.pa.sel_rsp == 0x18 ||
          rfDetail.rf_tech_param.param.pa.sel_rsp == 0x08) {
        if (rfDetail.rf_tech_param.param.pa.sel_rsp == 0) {
          mNumTechList++;
          mTechHandles[mNumTechList] = rfDetail.rf_disc_id;
          mTechLibNfcTypes[mNumTechList] = rfDetail.protocol;
          // save the stack's data structure for interpretation later
          memcpy(&(mTechParams[mNumTechList]), &(rfDetail.rf_tech_param),
                 sizeof(rfDetail.rf_tech_param));
          mTechList[mNumTechList] =
              TARGET_TYPE_MIFARE_UL;  // is TagTechnology.MIFARE_ULTRALIGHT by
                                      // Java API
        }
      }
    }
  }
#if (NXP_EXTNS == TRUE)
  else if(NFC_PROTOCOL_T3BT == rfDetail.protocol) {
    mTechHandles [mNumTechList] = rfDetail.rf_disc_id;
    mTechLibNfcTypes [mNumTechList] = rfDetail.protocol;
    mTechList [mNumTechList] = TARGET_TYPE_ISO14443_3B; //is TagTechnology.NFC_B by Java API
    //save the stack's data structure for interpretation later
    memcpy (&(mTechParams[mNumTechList]), &(rfDetail.rf_tech_param), sizeof(rfDetail.rf_tech_param));
  }
#endif
  else if (NFC_PROTOCOL_T3T == rfDetail.protocol) {
    uint8_t xx = 0;

    mTechList[mNumTechList] = TARGET_TYPE_FELICA;

    // see if it is Felica Lite.
    while (xx < activationData.params.t3t.num_system_codes) {
      if (activationData.params.t3t.p_system_codes[xx++] ==
          T3T_SYSTEM_CODE_FELICA_LITE) {
        mIsFelicaLite = true;
        break;
      }
    }
  } else if (NFC_PROTOCOL_ISO_DEP == rfDetail.protocol) {
    // type-4 tag uses technology ISO-DEP and technology A or B
    mTechList[mNumTechList] =
        TARGET_TYPE_ISO14443_4;  // is TagTechnology.ISO_DEP by Java API
    if ((NFC_DISCOVERY_TYPE_POLL_A == rfDetail.rf_tech_param.mode) ||
        (NFC_DISCOVERY_TYPE_POLL_A_ACTIVE == rfDetail.rf_tech_param.mode)) {
      uint8_t fwi = rfDetail.intf_param.intf_param.pa_iso.fwi;
      if (fwi >= MIN_FWI && fwi <= MAX_FWI) {
        //2^MIN_FWI * 256 * 16 * 1000 / 13560000 is approximately 618
        int fwt = (1 << (fwi - MIN_FWI)) * 618;
        LOG(DEBUG) << StringPrintf(
            "Setting the transceive timeout = %d, fwi = %0#x", fwt, fwi);
        setTransceiveTimeout(mTechList[mNumTechList], fwt);
      }
    }
#if (NXP_EXTNS == TRUE)
    if (NfcTagExtns::getInstance().checkAndClearNonStdTagState()) {
      LOG(DEBUG) << StringPrintf("%s: non std Tag", fn);
    } else
#endif
        if ((rfDetail.rf_tech_param.mode == NFC_DISCOVERY_TYPE_POLL_A) ||
            (rfDetail.rf_tech_param.mode == NFC_DISCOVERY_TYPE_POLL_A_ACTIVE) ||
            (rfDetail.rf_tech_param.mode == NFC_DISCOVERY_TYPE_LISTEN_A) ||
            (rfDetail.rf_tech_param.mode ==
             NFC_DISCOVERY_TYPE_LISTEN_A_ACTIVE)) {
      mNumTechList++;
      mTechHandles[mNumTechList] = rfDetail.rf_disc_id;
      mTechLibNfcTypes[mNumTechList] = rfDetail.protocol;
      mTechList[mNumTechList] =
          TARGET_TYPE_ISO14443_3A;  // is TagTechnology.NFC_A by Java API
      // save the stack's data structure for interpretation later
      memcpy(&(mTechParams[mNumTechList]), &(rfDetail.rf_tech_param),
             sizeof(rfDetail.rf_tech_param));
    }
#if (NXP_EXTNS == TRUE)
#if (NXP_QTAG == TRUE)
    else if (rfDetail.rf_tech_param.mode == NFC_DISCOVERY_TYPE_POLL_Q) {
      mNumTechList++;
      mTechHandles[mNumTechList] = rfDetail.rf_disc_id;
      mTechLibNfcTypes[mNumTechList] = rfDetail.protocol;
      mTechList[mNumTechList] =
          TARGET_TYPE_ISO14443_3Q;  // is TagTechnology.NFC_Q by Java API
      // save the stack's data structure for interpretation later
      memcpy(&(mTechParams[mNumTechList]), &(rfDetail.rf_tech_param),
             sizeof(rfDetail.rf_tech_param));
    }
#endif
#endif
    else if ((rfDetail.rf_tech_param.mode == NFC_DISCOVERY_TYPE_POLL_B) ||
             (rfDetail.rf_tech_param.mode == NFC_DISCOVERY_TYPE_POLL_B_PRIME) ||
             (rfDetail.rf_tech_param.mode == NFC_DISCOVERY_TYPE_LISTEN_B) ||
             (rfDetail.rf_tech_param.mode ==
              NFC_DISCOVERY_TYPE_LISTEN_B_PRIME)) {
      mNumTechList++;
      mTechHandles[mNumTechList] = rfDetail.rf_disc_id;
      mTechLibNfcTypes[mNumTechList] = rfDetail.protocol;
      mTechList[mNumTechList] =
          TARGET_TYPE_ISO14443_3B;  // is TagTechnology.NFC_B by Java API
      // save the stack's data structure for interpretation later
      memcpy(&(mTechParams[mNumTechList]), &(rfDetail.rf_tech_param),
             sizeof(rfDetail.rf_tech_param));
    }
  } else if (NFC_PROTOCOL_T5T == rfDetail.protocol) {
    // is TagTechnology.NFC_V by Java API
    mTechList[mNumTechList] = TARGET_TYPE_V;
  } else if (NFC_PROTOCOL_KOVIO == rfDetail.protocol) {
    LOG(DEBUG) << StringPrintf("%s: Kovio", fn);
    mTechList[mNumTechList] = TARGET_TYPE_KOVIO_BARCODE;
  } else if (NFC_PROTOCOL_MIFARE == rfDetail.protocol) {
    LOG(DEBUG) << StringPrintf("%s: Mifare Classic", fn);
    mTechList[mNumTechList] =
        TARGET_TYPE_ISO14443_3A;  // is TagTechnology.NFC_A by Java API
    mNumTechList++;
    mTechHandles[mNumTechList] = rfDetail.rf_disc_id;
    mTechLibNfcTypes[mNumTechList] = rfDetail.protocol;
    // save the stack's data structure for interpretation later
    memcpy(&(mTechParams[mNumTechList]), &(rfDetail.rf_tech_param),
           sizeof(rfDetail.rf_tech_param));
    mTechList[mNumTechList] =
        TARGET_TYPE_MIFARE_CLASSIC;  // is TagTechnology.MIFARE_CLASSIC by Java
                                     // API
  } else {
    LOG(ERROR) << StringPrintf("%s: unknown protocol ????", fn);
    mTechList[mNumTechList] = TARGET_TYPE_UNKNOWN;
  }

  mNumTechList++;
  for (int i = 0; i < mNumTechList; i++) {
    LOG(DEBUG) << StringPrintf("%s: index=%d; tech=%d; handle=%d; nfc type=%d",
                               fn, i, mTechList[i], mTechHandles[i],
                               mTechLibNfcTypes[i]);
  }
  mNfcStatsUtil->logNfcTagType(mTechLibNfcTypes[mTechListTail],
                               mTechParams[mTechListTail].mode);
  TheEnd:
  LOG(DEBUG) << StringPrintf("%s: exit", fn);
}

/*******************************************************************************
**
** Function:        discoverTechnologies
**
** Description:     Discover the technologies that NFC service needs by
*interpreting
**                  the data structures from the stack.
**                  discoveryData: data from discovery events(s).
**
** Returns:         None
**
*******************************************************************************/
void NfcTag::discoverTechnologies(tNFA_DISC_RESULT& discoveryData) {
  static const char fn[] = "NfcTag::discoverTechnologies (discovery)";
  tNFC_RESULT_DEVT& discovery_ntf = discoveryData.discovery_ntf;
  uint8_t index = mNumDiscNtf;

  LOG(DEBUG) << StringPrintf(
      "%s: enter: rf disc. id=%u; protocol=%u, mNumDiscTechList=%u", fn,
      discovery_ntf.rf_disc_id, discovery_ntf.protocol, mNumDiscTechList);
  if (index >= MAX_NUM_TECHNOLOGY) {
    LOG(ERROR) << StringPrintf("%s: exceed max=%d", fn, MAX_NUM_TECHNOLOGY);
    goto TheEnd;
  }
  mTechHandlesDiscData[index] = discovery_ntf.rf_disc_id;
  mTechLibNfcTypesDiscData[index] = discovery_ntf.protocol;
#if (NXP_EXTNS == TRUE)
  memcpy(&(mTechParams[index]), &(discovery_ntf.rf_tech_param),
         sizeof(discovery_ntf.rf_tech_param));
#endif
  if (mNumDiscTechList < MAX_NUM_TECHNOLOGY) {
    mNumDiscTechList++;

  }
  if (discovery_ntf.more != NCI_DISCOVER_NTF_MORE) {
    for (int i = 0; i < mNumDiscTechList; i++) {
      LOG(DEBUG) << StringPrintf("%s: index=%d; handle=%d; nfc type=%d", fn, i,
                                 mTechHandlesDiscData[i],
                                 mTechLibNfcTypesDiscData[i]);
    }
  }
  LOG(DEBUG) << StringPrintf("%s; mNumDiscTechList=%x", fn, mNumDiscTechList);

TheEnd:
  LOG(DEBUG) << StringPrintf("%s: exit", fn);
}

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
void NfcTag::notifyNfcAbortTagops(tNFC_DEACT_REASON reason) {
  LOG(DEBUG) << StringPrintf("%s: %d", __func__, reason);

  if (NfcTagExtns::getInstance().isNonStdMFCTagDetected() ||
      reason == NCI_DEACTIVATE_REASON_RF_REMOTE_ENDPOINT_REMOVED ||
      reason == NCI_DEACTIVATE_REASON_RF_TIMEOUT_EXCEPTION) {
    NfcTag& nTag = NfcTag::getInstance();
    JNIEnv* e = NULL;
    ScopedAttach attach(nTag.mNativeData->vm, &e);
    if (e == NULL) {
      LOG(ERROR) << StringPrintf("%s: jni env is null", __func__);
      return;
    }

    e->CallVoidMethod(nTag.mNativeData->manager,
                      android::gCachedNfcManagerNotifyTagAbortListeners);

    CHECK(!e->ExceptionCheck());
  } else {
    LOG(DEBUG) << StringPrintf("%s:Standard card is Detected", __func__);
  }
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
}
#endif

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
void NfcTag::createNativeNfcTag(tNFA_ACTIVATED& activationData) {
  static const char fn[] = "NfcTag::createNativeNfcTag";
  LOG(DEBUG) << StringPrintf("%s: enter", fn);

  if (mNativeData == NULL) {
    LOG(ERROR) << StringPrintf("%s: mNativeData is null", fn);
    return;
  }

  JNIEnv* e = NULL;
  ScopedAttach attach(mNativeData->vm, &e);
  if (e == NULL) {
    LOG(ERROR) << StringPrintf("%s: jni env is null", fn);
    return;
  }

  ScopedLocalRef<jclass> tag_cls(e,
                                 e->GetObjectClass(mNativeData->cached_NfcTag));
  if (e->ExceptionCheck()) {
    e->ExceptionClear();
    LOG(ERROR) << StringPrintf("%s: failed to get class", fn);
    return;
  }

  // create a new Java NativeNfcTag object
  jmethodID ctor = e->GetMethodID(tag_cls.get(), "<init>", "()V");
  ScopedLocalRef<jobject> tag(e, e->NewObject(tag_cls.get(), ctor));

  // fill NativeNfcTag's mProtocols, mTechList, mTechHandles, mTechLibNfcTypes
  fillNativeNfcTagMembers1(e, tag_cls.get(), tag.get());

  // fill NativeNfcTag's members: mHandle, mConnectedTechnology
  fillNativeNfcTagMembers2(e, tag_cls.get(), tag.get(), activationData);

  // fill NativeNfcTag's members: mTechPollBytes
  fillNativeNfcTagMembers3(e, tag_cls.get(), tag.get(), activationData);

  // fill NativeNfcTag's members: mTechActBytes
  fillNativeNfcTagMembers4(e, tag_cls.get(), tag.get(), activationData);

  // fill NativeNfcTag's members: mUid
  fillNativeNfcTagMembers5(e, tag_cls.get(), tag.get(), activationData);

  if (mNativeData->tag != NULL) {
    e->DeleteGlobalRef(mNativeData->tag);
  }
  mNativeData->tag = e->NewGlobalRef(tag.get());
  LOG(DEBUG) << StringPrintf("%s; mNumDiscNtf=%x", fn, mNumDiscNtf);
  /*  if(isNfcCombiCard() || !mNumDiscNtf ||
   * NfcTag::getInstance().checkNextValidProtocol() == -1) {*/

  if (isNfcCombiCard() || !mNumDiscNtf) {
    // notify NFC service about this new tag
#if (NXP_EXTNS == TRUE)
    storeActivationParams();
#endif

    LOG(DEBUG) << StringPrintf("%s: try notify nfc service", fn);
    e->CallVoidMethod(mNativeData->manager,
                      android::gCachedNfcManagerNotifyNdefMessageListeners,
                      tag.get());
    if (e->ExceptionCheck()) {
      e->ExceptionClear();
      LOG(ERROR) << StringPrintf("%s: fail notify nfc service", fn);
    }
    deleteglobaldata(e);
  } else {
    LOG(DEBUG) << StringPrintf("%s: Selecting next tag", fn);
  }

  LOG(DEBUG) << StringPrintf("%s: exit", fn);
}

#if (NXP_EXTNS == TRUE)
/*******************************************************************************
**
** Function:        isNfcCombiCard
**
** Description:     checks if NFCDEP combi card detected
**
** Returns:         True if tag is activated.
**
*******************************************************************************/
bool NfcTag::isNfcCombiCard() {
  if ((android::nfcManager_isReaderModeEnabled() && isP2pDetected &&
       mNumDiscNtf == 1)) {
    isP2pDetected = false;
    return true;
  } else
    return false;
}

/*******************************************************************************
**
** Function:        storeActivationParams
**
** Description:     stores tag activation parameters for backup
**
** Returns:         None
**
*******************************************************************************/
void NfcTag::storeActivationParams() {
  static const char fn [] = "NfcTag::storeActivationParams";
  LOG(DEBUG) << StringPrintf("%s: Mode %d Types %d", fn, mTechParams[0].mode,
                             mTechLibNfcTypes[0]);
  mActivationParams_t.mTechParams = mTechParams[0].mode;
  mActivationParams_t.mTechLibNfcTypes = mTechLibNfcTypes [0];
}
#endif

/*******************************************************************************
**
** Function:        deleteglobaldata
**
** Description:     Deletes the global data reference after notifying to service
**                  e: JVM environment.
**
** Returns:         None
**
*******************************************************************************/
static void deleteglobaldata(JNIEnv* e) {
  static const char fn[] = "deleteglobaldata";
  LOG(DEBUG) << StringPrintf("%s: enter", fn);
  if (sTechPollBytes != NULL) {
    e->DeleteGlobalRef(sTechPollBytes);
  }
  if (gtechActBytes != NULL) {
    e->DeleteGlobalRef(gtechActBytes);
  }
  LOG(DEBUG) << StringPrintf("%s: exit", fn);
}

/*******************************************************************************
**
** Function:        fillNativeNfcTagMembers1
**
** Description:     Fill NativeNfcTag's members: mProtocols, mTechList,
*mTechHandles, mTechLibNfcTypes.
**                  e: JVM environment.
**                  tag_cls: Java NativeNfcTag class.
**                  tag: Java NativeNfcTag object.
**
** Returns:         None
**
*******************************************************************************/
void NfcTag::fillNativeNfcTagMembers1(JNIEnv* e, jclass tag_cls, jobject tag) {
  static const char fn[] = "NfcTag::fillNativeNfcTagMembers1";
  LOG(DEBUG) << StringPrintf("%s", fn);

  // create objects that represent NativeNfcTag's member variables
  ScopedLocalRef<jintArray> techList(e, e->NewIntArray(mNumTechList));
  ScopedLocalRef<jintArray> handleList(e, e->NewIntArray(mNumTechList));
  ScopedLocalRef<jintArray> typeList(e, e->NewIntArray(mNumTechList));

  {
    ScopedIntArrayRW technologies(e, techList.get());
    ScopedIntArrayRW handles(e, handleList.get());
    ScopedIntArrayRW types(e, typeList.get());
    for (int i = 0; i < mNumTechList; i++) {
      mNativeData->tProtocols[i] = mTechLibNfcTypes[i];
      mNativeData->handles[i] = mTechHandles[i];
      technologies[i] = mTechList[i];
      handles[i] = mTechHandles[i];
      types[i] = mTechLibNfcTypes[i];
    }
  }

  jfieldID f = NULL;

  f = e->GetFieldID(tag_cls, "mTechList", "[I");
  e->SetObjectField(tag, f, techList.get());

  f = e->GetFieldID(tag_cls, "mTechHandles", "[I");
  e->SetObjectField(tag, f, handleList.get());

  f = e->GetFieldID(tag_cls, "mTechLibNfcTypes", "[I");
  e->SetObjectField(tag, f, typeList.get());
}

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
void NfcTag::fillNativeNfcTagMembers2(JNIEnv* e, jclass tag_cls, jobject tag,
                                      tNFA_ACTIVATED& /*activationData*/) {
  static const char fn[] = "NfcTag::fillNativeNfcTagMembers2";
  LOG(DEBUG) << StringPrintf("%s", fn);
  jfieldID f = e->GetFieldID(tag_cls, "mConnectedTechIndex", "I");
  e->SetIntField(tag, f, (jint)0);
}

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
void NfcTag::fillNativeNfcTagMembers3(JNIEnv* e, jclass tag_cls, jobject tag,
                                      tNFA_ACTIVATED& activationData) {
  static const char fn[] = "NfcTag::fillNativeNfcTagMembers3";
  ScopedLocalRef<jbyteArray> pollBytes(e, e->NewByteArray(0));
  ScopedLocalRef<jclass> byteArrayClass(e, e->GetObjectClass(pollBytes.get()));
  ScopedLocalRef<jobjectArray> techPollBytes(
      e, e->NewObjectArray(mNumTechList, byteArrayClass.get(), 0));
  int len = 0;
  if (mTechListTail == 0) {
    sTechPollBytes =
        reinterpret_cast<jobjectArray>(e->NewGlobalRef(techPollBytes.get()));
  } else {
    /* Add previously activated tag's tech poll bytes also in the
    list for multiprotocol tag*/
    jobject techPollBytesObject;
    for (int j = 0; j < mTechListTail; j++) {
      techPollBytesObject = e->GetObjectArrayElement(sTechPollBytes, j);
      e->SetObjectArrayElement(techPollBytes.get(), j, techPollBytesObject);
    }
  }

  for (int i = mTechListTail; i < mNumTechList; i++) {
    LOG(DEBUG) << StringPrintf("%s: index=%d; rf tech params mode=%u", fn, i,
                               mTechParams[i].mode);
    if (NFC_DISCOVERY_TYPE_POLL_A == mTechParams[i].mode ||
        NFC_DISCOVERY_TYPE_POLL_A_ACTIVE == mTechParams[i].mode ||
        NFC_DISCOVERY_TYPE_LISTEN_A == mTechParams[i].mode ||
        NFC_DISCOVERY_TYPE_LISTEN_A_ACTIVE == mTechParams[i].mode
#if (NXP_EXTNS == TRUE)
        || NFC_DISCOVERY_TYPE_POLL_WLC == mTechParams[i].mode
#endif
    ) {
      LOG(DEBUG) << StringPrintf("%s: tech A", fn);
      pollBytes.reset(e->NewByteArray(2));
      e->SetByteArrayRegion(pollBytes.get(), 0, 2,
                            (jbyte*)mTechParams[i].param.pa.sens_res);
    }
#if (NXP_EXTNS == TRUE)
#if (NXP_QTAG == TRUE)
    else if (NFC_DISCOVERY_TYPE_POLL_Q == mTechParams[i].mode) {
      LOG(DEBUG) << StringPrintf("%s: tech Q", fn);
      pollBytes.reset(e->NewByteArray(2));
      e->SetByteArrayRegion(pollBytes.get(), 0, 2,
                            (jbyte*)mTechParams[i].param.pq.sens_res);
    }
#endif
#endif
    else if (NFC_DISCOVERY_TYPE_POLL_B == mTechParams[i].mode ||
             NFC_DISCOVERY_TYPE_POLL_B_PRIME == mTechParams[i].mode ||
             NFC_DISCOVERY_TYPE_LISTEN_B == mTechParams[i].mode ||
             NFC_DISCOVERY_TYPE_LISTEN_B_PRIME == mTechParams[i].mode) {
      if (mTechList[i] ==
          TARGET_TYPE_ISO14443_3B)  // is TagTechnology.NFC_B by Java API
      {
        /*****************
        see NFC Forum Digital Protocol specification; section 5.6.2;
        in SENSB_RES response, byte 6 through 9 is Application Data, byte 10-12
        or 13 is Protocol Info; used by public API: NfcB.getApplicationData(),
        NfcB.getProtocolInfo();
        *****************/
        LOG(DEBUG) << StringPrintf("%s: tech B; TARGET_TYPE_ISO14443_3B", fn);
        len = mTechParams[i].param.pb.sensb_res_len;
        if (len >= NFC_NFCID0_MAX_LEN) {
          // subtract 4 bytes for NFCID0 at byte 2 through 5
          len = len - NFC_NFCID0_MAX_LEN;
        } else {
          android_errorWriteLog(0x534e4554, "124940143");
          LOG(ERROR) << StringPrintf("%s: sensb_res_len error", fn);
          len = 0;
        }
        pollBytes.reset(e->NewByteArray(len));
        e->SetByteArrayRegion(pollBytes.get(), 0, len,
                              (jbyte*)(mTechParams[i].param.pb.sensb_res + 4));
      } else {
        pollBytes.reset(e->NewByteArray(0));
      }
    } else if (NFC_DISCOVERY_TYPE_POLL_F == mTechParams[i].mode ||
               NFC_DISCOVERY_TYPE_POLL_F_ACTIVE == mTechParams[i].mode ||
               NFC_DISCOVERY_TYPE_LISTEN_F == mTechParams[i].mode ||
               NFC_DISCOVERY_TYPE_LISTEN_F_ACTIVE == mTechParams[i].mode) {
      /****************
      see NFC Forum Type 3 Tag Operation Specification; sections 2.3.2, 2.3.1.2;
      see NFC Forum Digital Protocol Specification; sections 6.6.2;
      PMm: manufacture parameter; 8 bytes;
      System Code: 2 bytes;
      ****************/
      LOG(DEBUG) << StringPrintf("%s: tech F", fn);
      uint8_t result[10];  // return result to NFC service
      memset(result, 0, sizeof(result));
      len = 10;

      /****
      for (int ii = 0; ii < mTechParams [i].param.pf.sensf_res_len; ii++)
      {
          LOG(DEBUG) << StringPrintf("%s: tech F,
      sendf_res[%d]=%d (0x%x)", fn, ii, mTechParams
      [i].param.pf.sensf_res[ii],mTechParams [i].param.pf.sensf_res[ii]);
      }
      ***/
      memcpy(result, mTechParams[i].param.pf.sensf_res + 8, 8);  // copy PMm
      if (activationData.params.t3t.num_system_codes >
          0)  // copy the first System Code
      {
        uint16_t systemCode = *(activationData.params.t3t.p_system_codes);
        result[8] = (uint8_t)(systemCode >> 8);
        result[9] = (uint8_t)systemCode;
        LOG(DEBUG) << StringPrintf("%s: tech F; sys code=0x%X 0x%X", fn,
                                   result[8], result[9]);
      }
      pollBytes.reset(e->NewByteArray(len));
      e->SetByteArrayRegion(pollBytes.get(), 0, len, (jbyte*)result);
    } else if (NFC_DISCOVERY_TYPE_POLL_V == mTechParams[i].mode ||
               NFC_DISCOVERY_TYPE_LISTEN_ISO15693 == mTechParams[i].mode) {
      LOG(DEBUG) << StringPrintf("%s: tech iso 15693", fn);
      // iso 15693 response flags: 1 octet
      // iso 15693 Data Structure Format Identifier (DSF ID): 1 octet
      // used by public API: NfcV.getDsfId(), NfcV.getResponseFlags();
      uint8_t data[2] = {activationData.params.i93.afi,
                         activationData.params.i93.dsfid};
      pollBytes.reset(e->NewByteArray(2));
      e->SetByteArrayRegion(pollBytes.get(), 0, 2, (jbyte*)data);
    } else {
      LOG(ERROR) << StringPrintf("%s: tech unknown ????", fn);
      pollBytes.reset(e->NewByteArray(0));
    }  // switch: every type of technology
    e->SetObjectArrayElement(techPollBytes.get(), i, pollBytes.get());
  }  // for: every technology in the array
  if (sTechPollBytes != NULL && mTechListTail != 0) {
    /* Save tech poll bytes of all activated tags of a multiprotocol tag in
     * sTechPollBytes*/
    e->DeleteGlobalRef(sTechPollBytes);
    sTechPollBytes =
        reinterpret_cast<jobjectArray>(e->NewGlobalRef(techPollBytes.get()));
  }
  jfieldID f = e->GetFieldID(tag_cls, "mTechPollBytes", "[[B");
  e->SetObjectField(tag, f, techPollBytes.get());
}

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
void NfcTag::fillNativeNfcTagMembers4(JNIEnv* e, jclass tag_cls, jobject tag,
                                      tNFA_ACTIVATED& activationData) {
  static const char fn[] = "NfcTag::fillNativeNfcTagMembers4";
  ScopedLocalRef<jbyteArray> actBytes(e, e->NewByteArray(0));
  ScopedLocalRef<jclass> byteArrayClass(e, e->GetObjectClass(actBytes.get()));
  ScopedLocalRef<jobjectArray> techActBytes(
      e, e->NewObjectArray(mNumTechList, byteArrayClass.get(), 0));
  jobject gtechActBytesObject;
  // Restore previously selected tag information from the gtechActBytes to
  // techActBytes.
  for (int j = 0; j < mTechListTail; j++) {
    gtechActBytesObject = e->GetObjectArrayElement(gtechActBytes, j);
    e->SetObjectArrayElement(techActBytes.get(), j, gtechActBytesObject);
  }

  // merging sak for combi tag
  if (activationData.activate_ntf.protocol &
      (NFC_PROTOCOL_T1T | NFC_PROTOCOL_T2T | NFC_PROTOCOL_MIFARE |
       NFC_PROTOCOL_ISO_DEP)) {
    uint8_t merge_sak = 0;
    for (int i = 0; i < mNumTechList; i++) {
      merge_sak = (merge_sak | mTechParams[i].param.pa.sel_rsp);
    }
    for (int i = 0; i < mNumTechList; i++) {
      mTechParams[i].param.pa.sel_rsp = merge_sak;
      actBytes.reset(e->NewByteArray(1));
      e->SetByteArrayRegion(actBytes.get(), 0, 1,
                            (jbyte*)&mTechParams[i].param.pa.sel_rsp);
      e->SetObjectArrayElement(techActBytes.get(), i, actBytes.get());
    }
  }

  if (mTechListTail == 0) {
    // Keep the backup of the selected tag information to restore back with
    // multi selection.
    gtechActBytes =
        reinterpret_cast<jobjectArray>(e->NewGlobalRef(techActBytes.get()));
  }

  for (int i = mTechListTail; i < mNumTechList; i++) {
    LOG(DEBUG) << StringPrintf("%s: index=%d", fn, i);
    if (NFC_PROTOCOL_T1T == mTechLibNfcTypes[i] ||
        NFC_PROTOCOL_T2T == mTechLibNfcTypes[i]) {
      if (mTechLibNfcTypes[i] == NFC_PROTOCOL_T1T)
        LOG(DEBUG) << StringPrintf("%s: T1T; tech A", fn);
      else if (mTechLibNfcTypes[i] == NFC_PROTOCOL_T2T)
        LOG(DEBUG) << StringPrintf("%s: T2T; tech A", fn);
      actBytes.reset(e->NewByteArray(1));
      e->SetByteArrayRegion(actBytes.get(), 0, 1,
                            (jbyte*)&mTechParams[i].param.pa.sel_rsp);
    } else if (NFC_PROTOCOL_T3T == mTechLibNfcTypes[i]) {
      // felica
      LOG(DEBUG) << StringPrintf("%s: T3T; felica; tech F", fn);
      // really, there is no data
      actBytes.reset(e->NewByteArray(0));
    } else if (NFC_PROTOCOL_MIFARE == mTechLibNfcTypes[i]) {
      LOG(DEBUG) << StringPrintf("%s: Mifare Classic; tech A", fn);
      actBytes.reset(e->NewByteArray(1));
      e->SetByteArrayRegion(actBytes.get(), 0, 1,
                            (jbyte*)&mTechParams[i].param.pa.sel_rsp);
    }
#if (NXP_EXTNS == TRUE)
    else if (NFC_PROTOCOL_T3BT == mTechLibNfcTypes[i]) {
      LOG(DEBUG) << StringPrintf("%s: tech T3BT; chinaId card", fn);
      actBytes.reset(e->NewByteArray(0));
    }
#endif
    else if (NFC_PROTOCOL_ISO_DEP == mTechLibNfcTypes[i]) {
      // t4t
      if (mTechList[i] ==
          TARGET_TYPE_ISO14443_4)  // is TagTechnology.ISO_DEP by Java API
      {
        if ((mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_A) ||
            (mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_A_ACTIVE) ||
            (mTechParams[i].mode == NFC_DISCOVERY_TYPE_LISTEN_A) ||
            (mTechParams[i].mode == NFC_DISCOVERY_TYPE_LISTEN_A_ACTIVE)) {
          // see NFC Forum Digital Protocol specification, section 11.6.2, "RATS
          // Response"; search for "historical bytes";  copy historical bytes
          // into Java object;  the public API, IsoDep.getHistoricalBytes(),
          // returns this data;
          if (activationData.activate_ntf.intf_param.type ==
              NFC_INTERFACE_ISO_DEP) {
            tNFC_INTF_PA_ISO_DEP& pa_iso =
                activationData.activate_ntf.intf_param.intf_param.pa_iso;
            LOG(DEBUG) << StringPrintf(
                "%s: T4T; ISO_DEP for tech A; copy historical bytes; len=%u",
                fn, pa_iso.his_byte_len);
            actBytes.reset(e->NewByteArray(pa_iso.his_byte_len));
            if (pa_iso.his_byte_len > 0)
              e->SetByteArrayRegion(actBytes.get(), 0, pa_iso.his_byte_len,
                                    (jbyte*)(pa_iso.his_byte));
          } else {
            LOG(ERROR) << StringPrintf(
                "%s: T4T; ISO_DEP for tech A; wrong interface=%u", fn,
                activationData.activate_ntf.intf_param.type);
            actBytes.reset(e->NewByteArray(0));
          }
        }
#if (NXP_EXTNS == TRUE)
#if (NXP_QTAG == TRUE)
        else if (mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_Q) {
          if (activationData.activate_ntf.intf_param.type ==
              NFC_INTERFACE_ISO_DEP) {
            tNFC_INTF_PQ_ISO_DEP& pq_iso =
                activationData.activate_ntf.intf_param.intf_param.pq_iso;
            LOG(DEBUG) << StringPrintf(
                "%s: T4T; ISO_DEP for tech Q; copy historical bytes; len=%u",
                fn, pq_iso.his_byte_len);
            actBytes.reset(e->NewByteArray(pq_iso.his_byte_len));
            if (pq_iso.his_byte_len > 0)
              e->SetByteArrayRegion(actBytes.get(), 0, pq_iso.his_byte_len,
                                    (jbyte*)(pq_iso.his_byte));
          } else {
            LOG(ERROR) << StringPrintf(
                "%s: T4T; ISO_DEP for tech Q; wrong interface=%u", fn,
                activationData.activate_ntf.intf_param.type);
            actBytes.reset(e->NewByteArray(0));
          }
        }
#endif
#endif
        else if ((mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_B) ||
                 (mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_B_PRIME) ||
                 (mTechParams[i].mode == NFC_DISCOVERY_TYPE_LISTEN_B) ||
                 (mTechParams[i].mode == NFC_DISCOVERY_TYPE_LISTEN_B_PRIME)) {
          // see NFC Forum Digital Protocol specification, section 12.6.2,
          // "ATTRIB Response";  copy higher-layer response bytes into Java
          // object;  the public API, IsoDep.getHiLayerResponse(), returns this
          // data;
          if (activationData.activate_ntf.intf_param.type ==
              NFC_INTERFACE_ISO_DEP) {
            tNFC_INTF_PB_ISO_DEP& pb_iso =
                activationData.activate_ntf.intf_param.intf_param.pb_iso;
            LOG(DEBUG) << StringPrintf(
                "%s: T4T; ISO_DEP for tech B; copy response bytes; len=%u", fn,
                pb_iso.hi_info_len);
            actBytes.reset(e->NewByteArray(pb_iso.hi_info_len));
            if (pb_iso.hi_info_len > 0)
              e->SetByteArrayRegion(actBytes.get(), 0, pb_iso.hi_info_len,
                                    (jbyte*)(pb_iso.hi_info));
          } else {
            LOG(ERROR) << StringPrintf(
                "%s: T4T; ISO_DEP for tech B; wrong interface=%u", fn,
                activationData.activate_ntf.intf_param.type);
            actBytes.reset(e->NewByteArray(0));
          }
        }
      } else if (mTechList[i] ==
                 TARGET_TYPE_ISO14443_3A)  // is TagTechnology.NFC_A by Java API
      {
        LOG(DEBUG) << StringPrintf("%s: T4T; tech A", fn);
        actBytes.reset(e->NewByteArray(1));
        e->SetByteArrayRegion(actBytes.get(), 0, 1,
                              (jbyte*)&mTechParams[i].param.pa.sel_rsp);
      } else {
        actBytes.reset(e->NewByteArray(0));
      }
    }  // case NFC_PROTOCOL_ISO_DEP: //t4t
    else if (NFC_PROTOCOL_T5T == mTechLibNfcTypes[i]) {
      LOG(DEBUG) << StringPrintf("%s: tech iso 15693", fn);
      // iso 15693 response flags: 1 octet
      // iso 15693 Data Structure Format Identifier (DSF ID): 1 octet
      // used by public API: NfcV.getDsfId(), NfcV.getResponseFlags();
      uint8_t data[2] = {activationData.params.i93.afi,
                         activationData.params.i93.dsfid};
      actBytes.reset(e->NewByteArray(2));
      e->SetByteArrayRegion(actBytes.get(), 0, 2, (jbyte*)data);
    } else {
      LOG(DEBUG) << StringPrintf("%s: tech unknown ????", fn);
      actBytes.reset(e->NewByteArray(0));
    }
    e->SetObjectArrayElement(techActBytes.get(), i, actBytes.get());
  }  // for: every technology in the array of current selected tag
  if (gtechActBytes != NULL && mTechListTail != 0) {
    e->DeleteGlobalRef(gtechActBytes);
    gtechActBytes =
        reinterpret_cast<jobjectArray>(e->NewGlobalRef(techActBytes.get()));
  }
  jfieldID f = e->GetFieldID(tag_cls, "mTechActBytes", "[[B");
  e->SetObjectField(tag, f, techActBytes.get());
}

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
void NfcTag::fillNativeNfcTagMembers5(JNIEnv* e, jclass tag_cls, jobject tag,
                                      tNFA_ACTIVATED& activationData) {
  static const char fn[] = "NfcTag::fillNativeNfcTagMembers5";
  int len = 0;
  ScopedLocalRef<jbyteArray> uid(e, NULL);

  if (NFC_DISCOVERY_TYPE_POLL_KOVIO == mTechParams[0].mode) {
    LOG(DEBUG) << StringPrintf("%s: Kovio", fn);
    len = mTechParams[0].param.pk.uid_len;
    uid.reset(e->NewByteArray(len));
    e->SetByteArrayRegion(uid.get(), 0, len,
                          (jbyte*)&mTechParams[0].param.pk.uid);
  } else if (NFC_DISCOVERY_TYPE_POLL_A == mTechParams[0].mode ||
             NFC_DISCOVERY_TYPE_POLL_A_ACTIVE == mTechParams[0].mode ||
             NFC_DISCOVERY_TYPE_LISTEN_A == mTechParams[0].mode ||
             NFC_DISCOVERY_TYPE_LISTEN_A_ACTIVE == mTechParams[0].mode) {
    LOG(DEBUG) << StringPrintf("%s: tech A", fn);
    len = mTechParams[0].param.pa.nfcid1_len;
    uid.reset(e->NewByteArray(len));
    e->SetByteArrayRegion(uid.get(), 0, len,
                          (jbyte*)&mTechParams[0].param.pa.nfcid1);
    // a tag's NFCID1 can change dynamically at each activation;
    // only the first byte (0x08) is constant; a dynamic NFCID1's length
    // must be 4 bytes (see NFC Digitial Protocol,
    // section 4.7.2 SDD_RES Response, Requirements 20).
    mIsDynamicTagId = (mTechParams[0].param.pa.nfcid1_len == 4) &&
                      (mTechParams[0].param.pa.nfcid1[0] == 0x08);
  }
#if (NXP_EXTNS == TRUE)
#if (NXP_QTAG == TRUE)
  else if (NFC_DISCOVERY_TYPE_POLL_Q == mTechParams[0].mode) {
    LOG(DEBUG) << StringPrintf("%s: tech Q", fn);
    len = mTechParams[0].param.pq.nfcid1_len;
    uid.reset(e->NewByteArray(len));
    e->SetByteArrayRegion(uid.get(), 0, len,
                          (jbyte*)&mTechParams[0].param.pq.nfcid1);
  }
#endif
#endif
  else if (NFC_DISCOVERY_TYPE_POLL_B == mTechParams[0].mode ||
           NFC_DISCOVERY_TYPE_POLL_B_PRIME == mTechParams[0].mode ||
           NFC_DISCOVERY_TYPE_LISTEN_B == mTechParams[0].mode ||
           NFC_DISCOVERY_TYPE_LISTEN_B_PRIME == mTechParams[0].mode) {
#if (NXP_EXTNS == TRUE)
    if(activationData.activate_ntf.protocol != NFA_PROTOCOL_T3BT) {
#endif
      LOG(DEBUG) << StringPrintf("%s: tech B", fn);
      uid.reset(e->NewByteArray(NFC_NFCID0_MAX_LEN));
      e->SetByteArrayRegion(uid.get(), 0, NFC_NFCID0_MAX_LEN,
                            (jbyte*)&mTechParams[0].param.pb.nfcid0);
#if (NXP_EXTNS == TRUE)
    } else {
      LOG(DEBUG) << StringPrintf("%s: chinaId card", fn);
      LOG(DEBUG) << StringPrintf("%s: pipi_id[0]=%x", fn,
                                 mTechParams[0].param.pb.pupiid[0]);
      uid.reset(e->NewByteArray(NFC_PUPIID_MAX_LEN));
      e->SetByteArrayRegion(uid.get(), 0, NFC_PUPIID_MAX_LEN,
              (jbyte*) &mTechParams [0].param.pb.pupiid);
    }
#endif
  } else if (NFC_DISCOVERY_TYPE_POLL_F == mTechParams[0].mode ||
             NFC_DISCOVERY_TYPE_POLL_F_ACTIVE == mTechParams[0].mode ||
             NFC_DISCOVERY_TYPE_LISTEN_F == mTechParams[0].mode ||
             NFC_DISCOVERY_TYPE_LISTEN_F_ACTIVE == mTechParams[0].mode) {
    uid.reset(e->NewByteArray(NFC_NFCID2_LEN));
    e->SetByteArrayRegion(uid.get(), 0, NFC_NFCID2_LEN,
                          (jbyte*)&mTechParams[0].param.pf.nfcid2);
    LOG(DEBUG) << StringPrintf("%s: tech F", fn);
  } else if (NFC_DISCOVERY_TYPE_POLL_V == mTechParams[0].mode ||
             NFC_DISCOVERY_TYPE_LISTEN_ISO15693 == mTechParams[0].mode) {
    LOG(DEBUG) << StringPrintf("%s: tech iso 15693", fn);
    jbyte data[I93_UID_BYTE_LEN];               // 8 bytes
    for (int i = 0; i < I93_UID_BYTE_LEN; ++i)  // reverse the ID
      data[i] = activationData.params.i93.uid[I93_UID_BYTE_LEN - i - 1];
    uid.reset(e->NewByteArray(I93_UID_BYTE_LEN));
    e->SetByteArrayRegion(uid.get(), 0, I93_UID_BYTE_LEN, data);
  } else {
    LOG(ERROR) << StringPrintf("%s: tech unknown ????", fn);
    uid.reset(e->NewByteArray(0));
  }
  jfieldID f = e->GetFieldID(tag_cls, "mUid", "[B");
  e->SetObjectField(tag, f, uid.get());
  mTechListTail = mNumTechList;
  if (mNumDiscNtf == 0) mTechListTail = 0;
  LOG(DEBUG) << StringPrintf("%s;mTechListTail=%x", fn, mTechListTail);
}

/*******************************************************************************
**
** Function:        isP2pDiscovered
**
** Description:     Does the peer support P2P?
**
** Returns:         True if the peer supports P2P.
**
*******************************************************************************/
bool NfcTag::isP2pDiscovered() {
  static const char fn[] = "NfcTag::isP2pDiscovered";
  bool retval = false;

  for (int i = 0; i < mNumDiscTechList; i++) {
    if (mTechLibNfcTypesDiscData[i] == NFA_PROTOCOL_NFC_DEP) {
      // if remote device supports P2P
      LOG(DEBUG) << StringPrintf("%s: discovered P2P", fn);
      retval = true;
      break;
    }
  }
#if (NXP_EXTNS == TRUE)
  if (!isP2pDetected) isP2pDetected = retval;
#endif

  LOG(DEBUG) << StringPrintf("%s: return=%u", fn, retval);
  return retval;
}

/*******************************************************************************
**
** Function:        selectP2p
**
** Description:     Select the preferred P2P technology if there is a choice.
**
** Returns:         None
**
*******************************************************************************/
void NfcTag::selectP2p() {
  static const char fn[] = "NfcTag::selectP2p";
  uint8_t rfDiscoveryId = 0;

  for (int i = 0; i < mNumDiscTechList; i++) {
    // if remote device does not support P2P, just skip it
    if (mTechLibNfcTypesDiscData[i] != NFA_PROTOCOL_NFC_DEP) continue;

    // if remote device supports tech F;
    // tech F is preferred because it is faster than tech A
    if ((mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_F) ||
        (mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_F_ACTIVE)) {
      rfDiscoveryId = mTechHandlesDiscData[i];
      break;  // no need to search further
    } else if ((mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_A) ||
               (mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_A_ACTIVE)) {
      // only choose tech A if tech F is unavailable
      if (rfDiscoveryId == 0) rfDiscoveryId = mTechHandlesDiscData[i];
    }
  }

  if (rfDiscoveryId > 0) {
    LOG(DEBUG) << StringPrintf("%s: select P2P; target rf discov id=0x%X", fn,
                               rfDiscoveryId);
    tNFA_STATUS stat =
        NFA_Select(rfDiscoveryId, NFA_PROTOCOL_NFC_DEP, NFA_INTERFACE_NFC_DEP);
    if (stat != NFA_STATUS_OK)
      LOG(ERROR) << StringPrintf("%s: fail select P2P; error=0x%X", fn, stat);
  } else
    LOG(ERROR) << StringPrintf("%s: cannot find P2P", fn);
  resetTechnologies();
}

/*******************************************************************************
**
** Function:        resetTechnologies
**
** Description:     Clear all data related to the technology, protocol of the
**                  tag.
**
** Returns:         None
**
*******************************************************************************/
void NfcTag::resetTechnologies() {
  static const char fn[] = "NfcTag::resetTechnologies";
  LOG(DEBUG) << StringPrintf("%s", fn);
  mNumTechList = 0;
  mNumDiscNtf = 0;
  mNumDiscTechList = 0;
  mTechListTail = 0;
  mIsMultiProtocolTag = false;
#if (NXP_EXTNS == TRUE)
  mTechListIndex = 0;
  isP2pDetected = false;
#endif
  memset(mTechList, 0, sizeof(mTechList));
  memset(mTechHandles, 0, sizeof(mTechHandles));
  memset(mTechLibNfcTypes, 0, sizeof(mTechLibNfcTypes));
  memset(mTechParams, 0, sizeof(mTechParams));
  mIsDynamicTagId = false;
  mIsFelicaLite = false;
  resetAllTransceiveTimeouts();
#if (NXP_EXTNS == TRUE)
  /* reset KOVIO uidLen on disconnect/presence
   * check failed/DEACTIVATED_NTF to enable
   * thus isSameKovio returns false
   * */
  mLastKovioUidLen = 0;
#endif
}

/*******************************************************************************
**
** Function:        selectFirstTag
**
** Description:     When multiple tags are discovered, just select the first one
*to activate.
**
** Returns:         None
**
*******************************************************************************/
void NfcTag::selectFirstTag() {
  static const char fn[] = "NfcTag::selectFirstTag";
  int foundIdx = -1;
  tNFA_INTF_TYPE rf_intf = NFA_INTERFACE_FRAME;

  for (int i = 0; i < mNumDiscTechList; i++) {
    LOG(DEBUG) << StringPrintf("%s: nfa target idx=%d h=0x%X; protocol=0x%X",
                               fn, i, mTechHandlesDiscData[i],
                               mTechLibNfcTypesDiscData[i]);
    if (mTechLibNfcTypesDiscData[i] != NFA_PROTOCOL_NFC_DEP) {
#if (NXP_EXTNS == TRUE)
      if (NfcTagExtns::getInstance().shouldSkipProtoActivate(
              mTechLibNfcTypesDiscData[i]))
        break;  // Non-standard tag detected
#endif
        sLastSelectedTagId = i;
        foundIdx = i;
        break;
    }
  }

  if (foundIdx != -1) {
    if (mTechLibNfcTypesDiscData[foundIdx] == NFA_PROTOCOL_ISO_DEP) {
      rf_intf = NFA_INTERFACE_ISO_DEP;
    } else if (mTechLibNfcTypesDiscData[foundIdx] == NFC_PROTOCOL_MIFARE) {
      rf_intf = NFA_INTERFACE_MIFARE;
    } else
      rf_intf = NFA_INTERFACE_FRAME;

    tNFA_STATUS stat = NFA_Select(mTechHandlesDiscData[foundIdx],
                                  mTechLibNfcTypesDiscData[foundIdx], rf_intf);
    if (stat != NFA_STATUS_OK)
      LOG(ERROR) << StringPrintf("%s: fail select; error=0x%X", fn, stat);
  } else
    LOG(ERROR) << StringPrintf("%s: only found NFC-DEP technology.", fn);
}

/*******************************************************************************
**
** Function:        selectNextTagIfExists
**
** Description:     When multiple tags are discovered, selects the next tag to
**                  activate.
**
** Returns:         None
**
*******************************************************************************/
void NfcTag::selectNextTagIfExists() {
  static const char fn[] = "NfcTag::selectNextTagIfExists";
  int foundIdx = -1;
  tNFA_INTF_TYPE rf_intf = NFA_INTERFACE_FRAME;
  tNFA_STATUS stat = NFA_STATUS_FAILED;

  if (mNumDiscNtf == 0) {
    return;
  }
  mNumDiscNtf--;
  LOG(DEBUG) << StringPrintf("%s: enter, mNumDiscTechList=%x", fn,
                             mNumDiscTechList);
  for (int i = 0; i < mNumDiscTechList; i++) {
    LOG(DEBUG) << StringPrintf("%s: nfa target idx=%dh=0x%X; protocol=0x%X", fn,
                               i, mTechHandlesDiscData[i],
                               mTechLibNfcTypesDiscData[i]);
    if (((mTechHandlesDiscData[sLastSelectedTagId] !=
          mTechHandlesDiscData[i]) ||
         (mTechLibNfcTypesDiscData[sLastSelectedTagId] !=
          mTechLibNfcTypesDiscData[i])) &&
        (mTechLibNfcTypesDiscData[i] != NFA_PROTOCOL_NFC_DEP)) {
      sLastSelectedTagId = i;
      foundIdx = i;
      break;
    }
  }

  if (foundIdx != -1) {
    if (mTechLibNfcTypesDiscData[foundIdx] == NFA_PROTOCOL_ISO_DEP) {
      rf_intf = NFA_INTERFACE_ISO_DEP;
    } else if (mTechLibNfcTypesDiscData[foundIdx] == NFC_PROTOCOL_MIFARE) {
      rf_intf = NFA_INTERFACE_MIFARE;
    } else {
      rf_intf = NFA_INTERFACE_FRAME;
    }

    stat = NFA_Select(mTechHandlesDiscData[foundIdx],
                      mTechLibNfcTypesDiscData[foundIdx], rf_intf);
    if (stat == NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: Select Success, wait for activated ntf",
                                 fn);
    } else {
      LOG(ERROR) << StringPrintf("%s: fail select; error=0x%X", fn, stat);
    }
  } else {
    LOG(ERROR) << StringPrintf("%s: only found NFC-DEP technology.", fn);
  }
}

/*******************************************************************************
**
** Function:        getT1tMaxMessageSize
**
** Description:     Get the maximum size (octet) that a T1T can store.
**
** Returns:         Maximum size in octets.
**
*******************************************************************************/
int NfcTag::getT1tMaxMessageSize() {
  static const char fn[] = "NfcTag::getT1tMaxMessageSize";

  if (mProtocol != NFC_PROTOCOL_T1T) {
    LOG(ERROR) << StringPrintf("%s: wrong protocol %u", fn, mProtocol);
    return 0;
  }
  return mtT1tMaxMessageSize;
}

/*******************************************************************************
**
** Function:        calculateT1tMaxMessageSize
**
** Description:     Calculate type-1 tag's max message size based on header ROM
*bytes.
**                  activate: reference to activation data.
**
** Returns:         None
**
*******************************************************************************/
void NfcTag::calculateT1tMaxMessageSize(tNFA_ACTIVATED& activate) {
  static const char fn[] = "NfcTag::calculateT1tMaxMessageSize";

  // make sure the tag is type-1
  if (activate.activate_ntf.protocol != NFC_PROTOCOL_T1T) {
    mtT1tMaxMessageSize = 0;
    return;
  }

  // examine the first byte of header ROM bytes
  switch (activate.params.t1t.hr[0]) {
    case RW_T1T_IS_TOPAZ96:
      mtT1tMaxMessageSize = 90;
      break;
    case RW_T1T_IS_TOPAZ512:
      mtT1tMaxMessageSize = 462;
      break;
    default:
      LOG(ERROR) << StringPrintf("%s: unknown T1T HR0=%u", fn,
                                 activate.params.t1t.hr[0]);
      mtT1tMaxMessageSize = 0;
      break;
  }
}

/*******************************************************************************
**
** Function:        isMifareUltralight
**
** Description:     Whether the currently activated tag is Mifare Ultralight.
**
** Returns:         True if tag is Mifare Ultralight.
**
*******************************************************************************/
bool NfcTag::isMifareUltralight() {
  static const char fn[] = "NfcTag::isMifareUltralight";
  bool retval = false;

  for (int i = 0; i < mNumTechList; i++) {
    if (mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_A) {
      // see NFC Digital Protocol, section 4.6.3 (SENS_RES); section 4.8.2
      // (SEL_RES).  see "MF0ICU1 Functional specification MIFARE Ultralight",
      // Rev. 3.4 - 4 February 2008,  section 6.7.
      if ((mTechParams[i].param.pa.sens_res[0] == 0x44) &&
          (mTechParams[i].param.pa.sens_res[1] == 0) &&
          ((mTechParams[i].param.pa.sel_rsp == 0) ||
           (mTechParams[i].param.pa.sel_rsp == 0x04)) &&
          (mTechParams[i].param.pa.nfcid1[0] == 0x04)) {
        retval = true;
      }
      break;
    }
  }
  LOG(DEBUG) << StringPrintf("%s: return=%u", fn, retval);
  return retval;
}

/*******************************************************************************
**
** Function:        isMifareDESFire
**
** Description:     Whether the currently activated tag is Mifare DESFire.
**
** Returns:         True if tag is Mifare DESFire.
**
*******************************************************************************/
bool NfcTag::isMifareDESFire() {
  static const char fn[] = "NfcTag::isMifareDESFire";
  bool retval = false;

  for (int i = 0; i < mNumTechList; i++) {
    if ((mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_A) ||
        (mTechParams[i].mode == NFC_DISCOVERY_TYPE_LISTEN_A) ||
        (mTechParams[i].mode == NFC_DISCOVERY_TYPE_LISTEN_A_ACTIVE)) {
      /* DESfire has one sak byte and 2 ATQA bytes */
      if ((mTechParams[i].param.pa.sens_res[0] == 0x44) &&
          (mTechParams[i].param.pa.sens_res[1] == 0x03) &&
          (mTechParams[i].param.pa.sel_rsp == 0x20)) {
        retval = true;
      }
      break;
    }
  }

  LOG(DEBUG) << StringPrintf("%s: return=%u", fn, retval);
  return retval;
}

/*******************************************************************************
**
** Function:        isFelicaLite
**
** Description:     Whether the currently activated tag is Felica Lite.
**
** Returns:         True if tag is Felica Lite.
**
*******************************************************************************/

bool NfcTag::isFelicaLite() { return mIsFelicaLite; }

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
bool NfcTag::isT2tNackResponse(const uint8_t* response, uint32_t responseLen) {
  static const char fn[] = "NfcTag::isT2tNackResponse";
  bool isNack = false;

  if (responseLen == 1) {
    if (response[0] == 0xA)
      isNack = false;  // an ACK response, so definitely not a NACK
    else
      isNack = true;  // assume every value is a NACK
  }
  LOG(DEBUG) << StringPrintf("%s: return %u", fn, isNack);
  return isNack;
}

/*******************************************************************************
**
** Function:        isNdefDetectionTimedOut
**
** Description:     Whether NDEF-detection algorithm timed out.
**
** Returns:         True if NDEF-detection algorithm timed out.
**
*******************************************************************************/
bool NfcTag::isNdefDetectionTimedOut() { return mNdefDetectionTimedOut; }

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
void NfcTag::connectionEventHandler(uint8_t event, tNFA_CONN_EVT_DATA* data) {
  static const char fn[] = "NfcTag::connectionEventHandler";

  switch (event) {
    case NFA_DISC_RESULT_EVT: {
      tNFA_DISC_RESULT& disc_result = data->disc_result;
      if (disc_result.status == NFA_STATUS_OK) {
        discoverTechnologies(disc_result);
      }
    } break;

    case NFA_ACTIVATED_EVT:
      // Only do tag detection if we are polling and it is not 'EE Direct RF'
      // activation (which may happen when we are activated as a tag).
      if (data->activated.activate_ntf.rf_tech_param.mode <
              NCI_DISCOVERY_TYPE_LISTEN_A &&
          data->activated.activate_ntf.intf_param.type !=
              NFC_INTERFACE_EE_DIRECT_RF) {
        tNFA_ACTIVATED& activated = data->activated;
        if (IsSameKovio(activated)) break;
        mIsActivated = true;
        mProtocol = activated.activate_ntf.protocol;
        calculateT1tMaxMessageSize(activated);
        discoverTechnologies(activated);
        createNativeNfcTag(activated);
      }
      break;
#if (NXP_EXTNS == TRUE)
    case NFA_RF_REMOVAL_DETECTION_FAIL_EVT:
          [[fallthrough]];
#endif
    case NFA_DEACTIVATED_EVT:
 #if (NXP_EXTNS == TRUE)
     if (!mIsActivated) {
        LOG(ERROR) << StringPrintf("%s: Already deactivated", fn);
        break;
     }
 #endif
      mIsActivated = false;
      mProtocol = NFC_PROTOCOL_UNKNOWN;
      resetTechnologies();
#if (NXP_EXTNS == TRUE)
      notifyNfcAbortTagops(data->deactivated.reason);
#endif
      break;

    case NFA_READ_CPLT_EVT: {
      SyncEventGuard g(mReadCompleteEvent);
      mReadCompletedStatus = data->status;
      mNdefDetectionTimedOut = data->status != NFA_STATUS_OK;
      if (mNdefDetectionTimedOut)
        LOG(ERROR) << StringPrintf("%s: NDEF detection timed out", fn);
      mReadCompleteEvent.notifyOne();
    } break;

    case NFA_NDEF_DETECT_EVT: {
      tNFA_NDEF_DETECT& ndef_detect = data->ndef_detect;
      mNdefDetectionTimedOut = ndef_detect.status == NFA_STATUS_TIMEOUT;
      if (mNdefDetectionTimedOut)
        LOG(ERROR) << StringPrintf("%s: NDEF detection timed out", fn);

      break;
    }
  }
}

/*******************************************************************************
**
** Function         setActive
**
** Description      Sets the active state for the object
**
** Returns          None.
**
*******************************************************************************/
void NfcTag::setActive(bool active) { mIsActivated = active; }

/*******************************************************************************
**
** Function:        isDynamicTagId
**
** Description:     Whether a tag has a dynamic tag ID.
**
** Returns:         True if ID is dynamic.
**
*******************************************************************************/
bool NfcTag::isDynamicTagId() {
  return mIsDynamicTagId &&
         (mTechList[0] == TARGET_TYPE_ISO14443_4) &&  // type-4 tag
         (mTechList[1] == TARGET_TYPE_ISO14443_3A);   // tech A
}

/*******************************************************************************
**
** Function:        resetAllTransceiveTimeouts
**
** Description:     Reset all timeouts for all technologies to default values.
**
** Returns:         none
**
*******************************************************************************/
void NfcTag::resetAllTransceiveTimeouts() {
  mTechnologyTimeoutsTable[TARGET_TYPE_ISO14443_3A] = 618;   // NfcA
  mTechnologyTimeoutsTable[TARGET_TYPE_ISO14443_3B] = 1000;  // NfcB
  mTechnologyTimeoutsTable[TARGET_TYPE_ISO14443_4] = 618;    // ISO-DEP
  mTechnologyTimeoutsTable[TARGET_TYPE_FELICA] = 255;        // Felica
  mTechnologyTimeoutsTable[TARGET_TYPE_V] = 1000;            // NfcV
  mTechnologyTimeoutsTable[TARGET_TYPE_NDEF] = 1000;
  mTechnologyTimeoutsTable[TARGET_TYPE_NDEF_FORMATABLE] = 1000;
  mTechnologyTimeoutsTable[TARGET_TYPE_MIFARE_CLASSIC] = 618;  // MifareClassic
  mTechnologyTimeoutsTable[TARGET_TYPE_MIFARE_UL] = 618;  // MifareUltralight
  mTechnologyTimeoutsTable[TARGET_TYPE_KOVIO_BARCODE] = 1000;  // NfcBarcode
}

/*******************************************************************************
**
** Function:        getTransceiveTimeout
**
** Description:     Get the timeout value for one technology.
**                  techId: one of the values in TARGET_TYPE_* defined in
*NfcJniUtil.h
**
** Returns:         Timeout value in millisecond.
**
*******************************************************************************/
int NfcTag::getTransceiveTimeout(int techId) {
  static const char fn[] = "NfcTag::getTransceiveTimeout";
  int retval = 1000;
  if ((techId > 0) && (techId < (int)mTechnologyTimeoutsTable.size()))
    retval = mTechnologyTimeoutsTable[techId];
  else
    LOG(ERROR) << StringPrintf("%s: invalid tech=%d", fn, techId);
  return retval;
}

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
void NfcTag::setTransceiveTimeout(int techId, int timeout) {
  static const char fn[] = "NfcTag::setTransceiveTimeout";
  if ((techId >= 0) && (techId < (int)mTechnologyTimeoutsTable.size()))
    mTechnologyTimeoutsTable[techId] = timeout;
  else
    LOG(ERROR) << StringPrintf("%s: invalid tech=%d", fn, techId);
}

/*******************************************************************************
**
** Function:        getPresenceCheckAlgorithm
**
** Description:     Get presence-check algorithm from .conf file.
**
** Returns:         Presence-check algorithm.
**
*******************************************************************************/
tNFA_RW_PRES_CHK_OPTION NfcTag::getPresenceCheckAlgorithm() {
  return mPresenceCheckAlgorithm;
}

/*******************************************************************************
**
** Function:        isInfineonMyDMove
**
** Description:     Whether the currently activated tag is Infineon My-D Move.
**
** Returns:         True if tag is Infineon My-D Move.
**
*******************************************************************************/
bool NfcTag::isInfineonMyDMove() {
  static const char fn[] = "NfcTag::isInfineonMyDMove";
  bool retval = false;

  for (int i = 0; i < mNumTechList; i++) {
    if (mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_A) {
      // see Infineon my-d move, my-d move NFC, SLE 66R01P, SLE 66R01PN,
      // Short Product Information, 2011-11-24, section 3.5
      if (mTechParams[i].param.pa.nfcid1[0] == 0x05) {
        uint8_t highNibble = mTechParams[i].param.pa.nfcid1[1] & 0xF0;
        if (highNibble == 0x30) retval = true;
      }
      break;
    }
  }
  LOG(DEBUG) << StringPrintf("%s: return=%u", fn, retval);
  return retval;
}

/*******************************************************************************
**
** Function:        isKovioType2Tag
**
** Description:     Whether the currently activated tag is Kovio Type-2 tag.
**
** Returns:         True if tag is Kovio Type-2 tag.
**
*******************************************************************************/
bool NfcTag::isKovioType2Tag() {
  static const char fn[] = "NfcTag::isKovioType2Tag";
  bool retval = false;

  for (int i = 0; i < mNumTechList; i++) {
    if (mTechParams[i].mode == NFC_DISCOVERY_TYPE_POLL_A) {
      // Kovio 2Kb RFID Tag, Functional Specification,
      // March 2, 2012, v2.0, section 8.3.
      if (mTechParams[i].param.pa.nfcid1[0] == 0x37) retval = true;
      break;
    }
  }
  LOG(DEBUG) << StringPrintf("%s: return=%u", fn, retval);
  return retval;
}

/*******************************************************************************
**
** Function:        setMultiProtocolTagSupport
**
** Description:     Update mIsMultiProtocolTag
**
** Returns:         None
**
*******************************************************************************/

void NfcTag::setMultiProtocolTagSupport(bool isMultiProtocolSupported) {
  mIsMultiProtocolTag = isMultiProtocolSupported;
}

/*******************************************************************************
**
** Function:        setNumDiscNtf
**
** Description:     Update number of Discovery NTF received
**
** Returns:         None
**
*******************************************************************************/
void NfcTag::setNumDiscNtf(int numDiscNtfValue) {
  if (numDiscNtfValue < MAX_NUM_TECHNOLOGY) {
    mNumDiscNtf = numDiscNtfValue;
  }
}

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
int NfcTag::getNumDiscNtf() { return mNumDiscNtf; }

