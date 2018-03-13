/*
 * Copyright (C) 2013 The Android Open Source Project
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
 *  Manage the listen-mode routing table.
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
*  Copyright 2018 NXP
*
******************************************************************************/
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>

#include "JavaClassConstants.h"
#include "RoutingManager.h"
#include "nfa_ce_api.h"
#include "nfa_ee_api.h"
#include "nfc_config.h"
#if (NXP_EXTNS == TRUE)
#include "SecureElement.h"
#endif

using android::base::StringPrintf;

extern bool gActivated;
extern SyncEvent gDeactivatedEvent;
extern bool nfc_debug_enabled;

const JNINativeMethod RoutingManager::sMethods[] = {
    {"doGetDefaultRouteDestination", "()I",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetDefaultRouteDestination},
    {"doGetDefaultOffHostRouteDestination", "()I",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetDefaultOffHostRouteDestination},
    {"doGetAidMatchingMode", "()I",
     (void*)
         RoutingManager::com_android_nfc_cardemulation_doGetAidMatchingMode}};

static const int MAX_NUM_EE = 5;

RoutingManager::RoutingManager() {
  static const char fn[] = "RoutingManager::RoutingManager()";

  // Get the active SE
  mActiveSe = NfcConfig::getUnsigned("ACTIVE_SE", 0x00);

  // Get the active SE for Nfc-F
  mActiveSeNfcF = NfcConfig::getUnsigned("ACTIVE_SE_NFCF", 0x00);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Active SE for Nfc-F is 0x%02X", fn, mActiveSeNfcF);

  // Get the "default" route
  mDefaultEe = NfcConfig::getUnsigned("DEFAULT_ISODEP_ROUTE", 0x00);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: default route is 0x%02X", fn, mDefaultEe);

  // Get the "default" route for Nfc-F
  mDefaultEeNfcF = NfcConfig::getUnsigned("DEFAULT_NFCF_ROUTE", 0x00);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s: default route for Nfc-F is 0x%02X", fn, mDefaultEeNfcF);

  // Get the default "off-host" route.  This is hard-coded at the Java layer
  // but we can override it here to avoid forcing Java changes.
  mOffHostEe = NfcConfig::getUnsigned("DEFAULT_OFFHOST_ROUTE", 0xf4);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: mOffHostEe=0x%02X", fn, mOffHostEe);

  mAidMatchingMode =
      NfcConfig::getUnsigned("AID_MATCHING_MODE", AID_MATCHING_EXACT_ONLY);

  memset(&mEeInfo, 0, sizeof(mEeInfo));
  mReceivedEeInfo = false;
  mSeTechMask = 0x00;

  mNfcFOnDhHandle = NFA_HANDLE_INVALID;
}

RoutingManager::~RoutingManager() { NFA_EeDeregister(nfaEeCallback); }

bool RoutingManager::initialize(nfc_jni_native_data* native) {
  static const char fn[] = "RoutingManager::initialize()";
  mNativeData = native;

  tNFA_STATUS nfaStat;
  {
    SyncEventGuard guard(mEeRegisterEvent);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: try ee register", fn);
    nfaStat = NFA_EeRegister(nfaEeCallback);
    if (nfaStat != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail ee register; error=0x%X", fn,
                                 nfaStat);
      return false;
    }
    mEeRegisterEvent.wait();
  }

  mRxDataBuffer.clear();
#if (NXP_EXTNS == TRUE)
//FIX THIS
    unsigned long tech = 0;
    unsigned long num = 0;
    if ((GetNxpNumValue(NAME_HOST_LISTEN_TECH_MASK, &tech, sizeof(tech))))
        mHostListnTechMask = tech;
    else
        mHostListnTechMask = 0x03;
          LOG(ERROR) << StringPrintf("%s: mHostListnTechMask=0x%X", fn,mHostListnTechMask);
    if (GetNxpNumValue (NAME_DEFAULT_FELICA_CLT_PWR_STATE, (void*)&num, sizeof(num)))
       mDefaultTechFPowerstate = num;
    else
       mDefaultTechFPowerstate = 0x3F;
    if (GetNxpNumValue (NAME_NXP_DEFAULT_SE, (void*)&num, sizeof(num)))
        mDefaultEe = num;
    else
        mDefaultEe = 0x02;
    mUiccListnTechMask = NfcConfig::getUnsigned("NAME_UICC_LISTEN_TECH_MASK", 0x07);
#endif
  if ((mActiveSe != 0) || (mActiveSeNfcF != 0)) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Technology Routing (NfcASe:0x%02x, NfcFSe:0x%02x)",
                        fn, mActiveSe, mActiveSeNfcF);
    {
      // Wait for EE info if needed
      SyncEventGuard guard(mEeInfoEvent);
      if (!mReceivedEeInfo) {
        LOG(INFO) << StringPrintf("Waiting for EE info");
        mEeInfoEvent.wait();
      }
    }

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Number of EE is %d", fn, mEeInfo.num_ee);
    for (uint8_t i = 0; i < mEeInfo.num_ee; i++) {
      tNFA_HANDLE eeHandle = mEeInfo.ee_disc_info[i].ee_handle;
      tNFA_TECHNOLOGY_MASK seTechMask = 0;

      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s   EE[%u] Handle: 0x%04x  techA: 0x%02x  techB: "
          "0x%02x  techF: 0x%02x  techBprime: 0x%02x",
          fn, i, eeHandle, mEeInfo.ee_disc_info[i].la_protocol,
          mEeInfo.ee_disc_info[i].lb_protocol,
          mEeInfo.ee_disc_info[i].lf_protocol,
          mEeInfo.ee_disc_info[i].lbp_protocol);
      if ((mActiveSe != 0) && (eeHandle == (mActiveSe | NFA_HANDLE_GROUP_EE))) {
        if (mEeInfo.ee_disc_info[i].la_protocol != 0)
          seTechMask |= NFA_TECHNOLOGY_MASK_A;
      }
      if ((mActiveSeNfcF != 0) &&
          (eeHandle == (mActiveSeNfcF | NFA_HANDLE_GROUP_EE))) {
        if (mEeInfo.ee_disc_info[i].lf_protocol != 0)
          seTechMask |= NFA_TECHNOLOGY_MASK_F;
      }

      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: seTechMask[%u]=0x%02x", fn, i, seTechMask);
      if (seTechMask != 0x00) {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "Configuring tech mask 0x%02x on EE 0x%04x", seTechMask, eeHandle);

        nfaStat = NFA_CeConfigureUiccListenTech(eeHandle, seTechMask);
        if (nfaStat != NFA_STATUS_OK)
          LOG(ERROR) << StringPrintf(
              "Failed to configure UICC listen technologies.");

        // Set technology routes to UICC if it's there
        nfaStat = NFA_EeSetDefaultTechRouting(eeHandle, seTechMask, seTechMask,
                                              seTechMask
#if(NXP_EXTNS == TRUE)
                                              ,seTechMask, seTechMask, seTechMask
#endif
                                              );
        if (nfaStat != NFA_STATUS_OK)
          LOG(ERROR) << StringPrintf(
              "Failed to configure UICC technology routing.");

        mSeTechMask |= seTechMask;
      }
    }
  }

  // Tell the host-routing to only listen on Nfc-A
  nfaStat = NFA_CeSetIsoDepListenTech(NFA_TECHNOLOGY_MASK_A);
  if (nfaStat != NFA_STATUS_OK)
    LOG(ERROR) << StringPrintf("Failed to configure CE IsoDep technologies");

  // Register a wild-card for AIDs routed to the host
  nfaStat = NFA_CeRegisterAidOnDH(NULL, 0, stackCallback);
  if (nfaStat != NFA_STATUS_OK)
    LOG(ERROR) << StringPrintf("Failed to register wildcard AID for DH");

  return true;
}

RoutingManager& RoutingManager::getInstance() {
  static RoutingManager manager;
  return manager;
}

void RoutingManager::enableRoutingToHost() {

  tNFA_STATUS nfaStat;
  tNFA_TECHNOLOGY_MASK techMask;
  tNFA_PROTOCOL_MASK protoMask;
  SyncEventGuard guard(mRoutingEvent);

  // Set default routing at one time when the NFCEE IDs for Nfc-A and Nfc-F are
  // same
  if (mDefaultEe == mDefaultEeNfcF) {
    // Route Nfc-A/Nfc-F to host if we don't have a SE
    techMask = (mSeTechMask ^ (NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_F));
    if (techMask != 0) {
      nfaStat = NFA_EeSetDefaultTechRouting(mDefaultEe, techMask, 0, 0
#if(NXP_EXTNS == TRUE)
      	,0,0,0
#endif
      	);
      if (nfaStat == NFA_STATUS_OK)
        mRoutingEvent.wait();
      else
        LOG(ERROR) << StringPrintf(
            "Fail to set default tech routing for Nfc-A/Nfc-F");
    }
    // Default routing for IsoDep and T3T protocol
    protoMask = (NFA_PROTOCOL_MASK_ISO_DEP | NFA_PROTOCOL_MASK_T3T);
    nfaStat = NFA_EeSetDefaultProtoRouting(mDefaultEe, protoMask, 0, 0
#if(NXP_EXTNS == TRUE)
    	,0,0,0
#endif
    	);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << StringPrintf(
          "Fail to set default proto routing for IsoDep and T3T");
  } else {
    // Route Nfc-A to host if we don't have a SE
    techMask = NFA_TECHNOLOGY_MASK_A;
    if ((mSeTechMask & NFA_TECHNOLOGY_MASK_A) == 0) {
      nfaStat = NFA_EeSetDefaultTechRouting(mDefaultEe, techMask, 0, 0
#if(NXP_EXTNS == TRUE)
      	,0,0,0
#endif
      	);
      if (nfaStat == NFA_STATUS_OK)
        mRoutingEvent.wait();
      else
        LOG(ERROR) << StringPrintf(
            "Fail to set default tech routing for Nfc-A");
    }
    // Default routing for IsoDep protocol
    protoMask = NFA_PROTOCOL_MASK_ISO_DEP;
    nfaStat = NFA_EeSetDefaultProtoRouting(mDefaultEe, protoMask, 0, 0
#if(NXP_EXTNS == TRUE)
        ,0,0,0
#endif
    	);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << StringPrintf(
          "Fail to set default proto routing for IsoDep");

    // Route Nfc-F to host if we don't have a SE
    techMask = NFA_TECHNOLOGY_MASK_F;
    if ((mSeTechMask & NFA_TECHNOLOGY_MASK_F) == 0) {
      nfaStat = NFA_EeSetDefaultTechRouting(mDefaultEeNfcF, techMask, 0, 0
#if(NXP_EXTNS == TRUE)
      ,0,0,0
#endif
      	);
      if (nfaStat == NFA_STATUS_OK)
        mRoutingEvent.wait();
      else
        LOG(ERROR) << StringPrintf(
            "Fail to set default tech routing for Nfc-F");
    }
    // Default routing for T3T protocol
    protoMask = NFA_PROTOCOL_MASK_T3T;
    nfaStat = NFA_EeSetDefaultProtoRouting(mDefaultEeNfcF, protoMask, 0, 0
#if(NXP_EXTNS == TRUE)
    	,0,0,0
#endif
    	);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << StringPrintf("Fail to set default proto routing for T3T");
  }
}

void RoutingManager::disableRoutingToHost() {
  tNFA_STATUS nfaStat;
  tNFA_TECHNOLOGY_MASK techMask;
  SyncEventGuard guard(mRoutingEvent);

  // Set default routing at one time when the NFCEE IDs for Nfc-A and Nfc-F are
  // same
  if (mDefaultEe == mDefaultEeNfcF) {
    // Default routing for Nfc-A/Nfc-F technology if we don't have a SE
    techMask = (mSeTechMask ^ (NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_F));
    if (techMask != 0) {
      nfaStat = NFA_EeSetDefaultTechRouting(mDefaultEe, 0, 0, 0
#if(NXP_EXTNS == TRUE)
      ,0,0,0
#endif
      	);
      if (nfaStat == NFA_STATUS_OK)
        mRoutingEvent.wait();
      else
        LOG(ERROR) << StringPrintf(
            "Fail to set default tech routing for Nfc-A/Nfc-F");
    }
    // Default routing for IsoDep and T3T protocol
    nfaStat = NFA_EeSetDefaultProtoRouting(mDefaultEe, 0, 0, 0
#if(NXP_EXTNS == TRUE)
    ,0,0,0
#endif
    	);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << StringPrintf(
          "Fail to set default proto routing for IsoDep and T3T");
  } else {
    // Default routing for Nfc-A technology if we don't have a SE
    if ((mSeTechMask & NFA_TECHNOLOGY_MASK_A) == 0) {
      nfaStat = NFA_EeSetDefaultTechRouting(mDefaultEe, 0, 0, 0
#if(NXP_EXTNS == TRUE)
      ,0,0,0
#endif
      	);
      if (nfaStat == NFA_STATUS_OK)
        mRoutingEvent.wait();
      else
        LOG(ERROR) << StringPrintf(
            "Fail to set default tech routing for Nfc-A");
    }
    // Default routing for IsoDep protocol
    nfaStat = NFA_EeSetDefaultProtoRouting(mDefaultEe, 0, 0, 0
#if(NXP_EXTNS == TRUE)
    ,0,0,0
#endif
    	);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << StringPrintf(
          "Fail to set default proto routing for IsoDep");

    // Default routing for Nfc-F technology if we don't have a SE
    if ((mSeTechMask & NFA_TECHNOLOGY_MASK_F) == 0) {
      nfaStat = NFA_EeSetDefaultTechRouting(mDefaultEeNfcF, 0, 0, 0
#if(NXP_EXTNS == TRUE)
      	,0,0,0
#endif
      	);
      if (nfaStat == NFA_STATUS_OK)
        mRoutingEvent.wait();
      else
        LOG(ERROR) << StringPrintf(
            "Fail to set default tech routing for Nfc-F");
    }
    // Default routing for T3T protocol
    nfaStat = NFA_EeSetDefaultProtoRouting(mDefaultEeNfcF, 0, 0, 0
#if(NXP_EXTNS == TRUE)
    	,0,0,0
#endif
    	);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << StringPrintf("Fail to set default proto routing for T3T");
  }
}
#if(NXP_EXTNS == TRUE)
bool RoutingManager::addAidRouting(const uint8_t* aid, uint8_t aidLen,
                                   int route, int aidInfo, int power) {
#else
bool RoutingManager::addAidRouting(const uint8_t* aid, uint8_t aidLen,
                                   int route, int aidInfo) {
#endif
  static const char fn[] = "RoutingManager::addAidRouting";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", fn);
  #if(NXP_EXTNS == TRUE)
  int seId = SecureElement::getInstance().getEseHandleFromGenericId(route);
  if (seId  == NFA_HANDLE_INVALID)
  {
    return false;
  }
  SyncEventGuard guard(mAidAddRemoveEvent);
  tNFA_STATUS nfaStat =
      NFA_EeAddAidRouting(seId, aidLen, (uint8_t*)aid, power, aidInfo);
  #else
  tNFA_STATUS nfaStat =
      NFA_EeAddAidRouting(route, aidLen, (uint8_t*)aid, 0x01, aidInfo);
   #endif
  if (nfaStat == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: routed AID", fn);
#if(NXP_EXTNS == TRUE)
    mAidAddRemoveEvent.wait();
#endif
    return true;
  } else {
    LOG(ERROR) << StringPrintf("%s: failed to route AID", fn);
    return false;
  }
}

bool RoutingManager::removeAidRouting(const uint8_t* aid, uint8_t aidLen) {
  static const char fn[] = "RoutingManager::removeAidRouting";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", fn);
#if(NXP_EXTNS == TRUE) 
  SyncEventGuard guard(mAidAddRemoveEvent);
#endif
  tNFA_STATUS nfaStat = NFA_EeRemoveAidRouting(aidLen, (uint8_t*)aid);
  if (nfaStat == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: removed AID", fn);
#if(NXP_EXTNS == TRUE)    
    mAidAddRemoveEvent.wait(); 
#endif   
    return true;
  } else {
    LOG(ERROR) << StringPrintf("%s: failed to remove AID", fn);
    return false;
  }
}

bool RoutingManager::commitRouting() {
  static const char fn[] = "RoutingManager::commitRouting";
  tNFA_STATUS nfaStat = 0;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", fn);
  {
    SyncEventGuard guard(mEeUpdateEvent);
    nfaStat = NFA_EeUpdateNow();
    if (nfaStat == NFA_STATUS_OK) {
      mEeUpdateEvent.wait();  // wait for NFA_EE_UPDATED_EVT
    }
  }
  return (nfaStat == NFA_STATUS_OK);
}

void RoutingManager::onNfccShutdown() {
  static const char fn[] = "RoutingManager:onNfccShutdown";
  if (mActiveSe == 0x00) return;

  tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
  uint8_t actualNumEe = MAX_NUM_EE;
  tNFA_EE_INFO eeInfo[MAX_NUM_EE];

  memset(&eeInfo, 0, sizeof(eeInfo));
  if ((nfaStat = NFA_EeGetInfo(&actualNumEe, eeInfo)) != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: fail get info; error=0x%X", fn, nfaStat);
    return;
  }
  if (actualNumEe != 0) {
    for (uint8_t xx = 0; xx < actualNumEe; xx++) {
      if ((eeInfo[xx].num_interface != 0) &&
          (eeInfo[xx].ee_interface[0] != NCI_NFCEE_INTERFACE_HCI_ACCESS) &&
          (eeInfo[xx].ee_status == NFA_EE_STATUS_ACTIVE)) {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: Handle: 0x%04x Change Status Active to Inactive", fn,
            eeInfo[xx].ee_handle);
        SyncEventGuard guard(mEeSetModeEvent);
        if ((nfaStat = NFA_EeModeSet(eeInfo[xx].ee_handle,
                                     NFA_EE_MD_DEACTIVATE)) == NFA_STATUS_OK) {
          mEeSetModeEvent.wait();  // wait for NFA_EE_MODE_SET_EVT
        } else {
          LOG(ERROR) << StringPrintf("Failed to set EE inactive");
        }
      }
    }
  } else {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: No active EEs found", fn);
  }
}

void RoutingManager::notifyActivated(uint8_t technology) {
  JNIEnv* e = NULL;
  ScopedAttach attach(mNativeData->vm, &e);
  if (e == NULL) {
    LOG(ERROR) << StringPrintf("jni env is null");
    return;
  }

  e->CallVoidMethod(mNativeData->manager,
                    android::gCachedNfcManagerNotifyHostEmuActivated,
                    (int)technology);
  if (e->ExceptionCheck()) {
    e->ExceptionClear();
    LOG(ERROR) << StringPrintf("fail notify");
  }
}

void RoutingManager::notifyDeactivated(uint8_t technology) {
  mRxDataBuffer.clear();
  JNIEnv* e = NULL;
  ScopedAttach attach(mNativeData->vm, &e);
  if (e == NULL) {
    LOG(ERROR) << StringPrintf("jni env is null");
    return;
  }

  e->CallVoidMethod(mNativeData->manager,
                    android::gCachedNfcManagerNotifyHostEmuDeactivated,
                    (int)technology);
  if (e->ExceptionCheck()) {
    e->ExceptionClear();
    LOG(ERROR) << StringPrintf("fail notify");
  }
}

void RoutingManager::handleData(uint8_t technology, const uint8_t* data,
                                uint32_t dataLen, tNFA_STATUS status) {
  if (status == NFC_STATUS_CONTINUE) {
    if (dataLen > 0) {
      mRxDataBuffer.insert(mRxDataBuffer.end(), &data[0],
                           &data[dataLen]);  // append data; more to come
    }
    return;  // expect another NFA_CE_DATA_EVT to come
  } else if (status == NFA_STATUS_OK) {
    if (dataLen > 0) {
      mRxDataBuffer.insert(mRxDataBuffer.end(), &data[0],
                           &data[dataLen]);  // append data
    }
    // entire data packet has been received; no more NFA_CE_DATA_EVT
  } else if (status == NFA_STATUS_FAILED) {
    LOG(ERROR) << StringPrintf("RoutingManager::handleData: read data fail");
    goto TheEnd;
  }

  {
    JNIEnv* e = NULL;
    ScopedAttach attach(mNativeData->vm, &e);
    if (e == NULL) {
      LOG(ERROR) << StringPrintf("jni env is null");
      goto TheEnd;
    }

    ScopedLocalRef<jobject> dataJavaArray(
        e, e->NewByteArray(mRxDataBuffer.size()));
    if (dataJavaArray.get() == NULL) {
      LOG(ERROR) << StringPrintf("fail allocate array");
      goto TheEnd;
    }

    e->SetByteArrayRegion((jbyteArray)dataJavaArray.get(), 0,
                          mRxDataBuffer.size(), (jbyte*)(&mRxDataBuffer[0]));
    if (e->ExceptionCheck()) {
      e->ExceptionClear();
      LOG(ERROR) << StringPrintf("fail fill array");
      goto TheEnd;
    }

    e->CallVoidMethod(mNativeData->manager,
                      android::gCachedNfcManagerNotifyHostEmuData,
                      (int)technology, dataJavaArray.get());
    if (e->ExceptionCheck()) {
      e->ExceptionClear();
      LOG(ERROR) << StringPrintf("fail notify");
    }
  }
TheEnd:
  mRxDataBuffer.clear();
}

void RoutingManager::stackCallback(uint8_t event,
                                   tNFA_CONN_EVT_DATA* eventData) {
  static const char fn[] = "RoutingManager::stackCallback";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: event=0x%X", fn, event);
  RoutingManager& routingManager = RoutingManager::getInstance();

  switch (event) {
    case NFA_CE_REGISTERED_EVT: {
      tNFA_CE_REGISTERED& ce_registered = eventData->ce_registered;
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_CE_REGISTERED_EVT; status=0x%X; h=0x%X", fn,
                          ce_registered.status, ce_registered.handle);
    } break;

    case NFA_CE_DEREGISTERED_EVT: {
      tNFA_CE_DEREGISTERED& ce_deregistered = eventData->ce_deregistered;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_CE_DEREGISTERED_EVT; h=0x%X", fn, ce_deregistered.handle);
    } break;

    case NFA_CE_ACTIVATED_EVT: {
      routingManager.notifyActivated(NFA_TECHNOLOGY_MASK_A);
    } break;

    case NFA_DEACTIVATED_EVT:
    case NFA_CE_DEACTIVATED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_DEACTIVATED_EVT, NFA_CE_DEACTIVATED_EVT", fn);
      routingManager.notifyDeactivated(NFA_TECHNOLOGY_MASK_A);
      SyncEventGuard g(gDeactivatedEvent);
      gActivated = false;  // guard this variable from multi-threaded access
      gDeactivatedEvent.notifyOne();
    } break;

    case NFA_CE_DATA_EVT: {
      tNFA_CE_DATA& ce_data = eventData->ce_data;
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_CE_DATA_EVT; stat=0x%X; h=0x%X; data len=%u",
                          fn, ce_data.status, ce_data.handle, ce_data.len);
      getInstance().handleData(NFA_TECHNOLOGY_MASK_A, ce_data.p_data,
                               ce_data.len, ce_data.status);
    } break;
  }
}
/*******************************************************************************
**
** Function:        nfaEeCallback
**
** Description:     Receive execution environment-related events from stack.
**                  event: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::nfaEeCallback(tNFA_EE_EVT event,
                                   tNFA_EE_CBACK_DATA* eventData) {
  static const char fn[] = "RoutingManager::nfaEeCallback";

  RoutingManager& routingManager = RoutingManager::getInstance();
#if (NXP_EXTNS == TRUE)
  SecureElement& se = SecureElement::getInstance();
#endif

  switch (event) {
    case NFA_EE_REGISTER_EVT: {
      SyncEventGuard guard(routingManager.mEeRegisterEvent);
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_REGISTER_EVT; status=%u", fn, eventData->ee_register);
      routingManager.mEeRegisterEvent.notifyOne();
    } break;

    case NFA_EE_MODE_SET_EVT: {
      SyncEventGuard guard(routingManager.mEeSetModeEvent);
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_MODE_SET_EVT; status: 0x%04X  handle: 0x%04X  ", fn,
          eventData->mode_set.status, eventData->mode_set.ee_handle);
      routingManager.mEeSetModeEvent.notifyOne();
#if (NXP_EXTNS == TRUE)
      se.notifyModeSet(eventData->mode_set.ee_handle, !(eventData->mode_set.status),eventData->mode_set.ee_status );
#endif
    } break;
#if (NXP_EXTNS == TRUE)
    case NFA_EE_PWR_LINK_CTRL_EVT:
    {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: NFA_EE_PWR_LINK_CTRL_EVT; status: 0x%04X ", fn,
      eventData->pwr_lnk_ctrl.status);
      se.mPwrCmdstatus = eventData->pwr_lnk_ctrl.status;
      SyncEventGuard guard (se.mPwrLinkCtrlEvent);
      se.mPwrLinkCtrlEvent.notifyOne();
    }
    break;
#endif
    case NFA_EE_SET_TECH_CFG_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_SET_TECH_CFG_EVT; status=0x%X", fn, eventData->status);
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
    } break;

    case NFA_EE_SET_PROTO_CFG_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_SET_PROTO_CFG_EVT; status=0x%X", fn, eventData->status);
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
    } break;

    case NFA_EE_ACTION_EVT: {
      tNFA_EE_ACTION& action = eventData->action;
      if (action.trigger == NFC_EE_TRIG_SELECT)
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_EE_ACTION_EVT; h=0x%X; trigger=select (0x%X)", fn,
            action.ee_handle, action.trigger);
      else if (action.trigger == NFC_EE_TRIG_APP_INIT) {
        tNFC_APP_INIT& app_init = action.param.app_init;
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_EE_ACTION_EVT; h=0x%X; trigger=app-init "
            "(0x%X); aid len=%u; data len=%u",
            fn, action.ee_handle, action.trigger, app_init.len_aid,
            app_init.len_data);
      } else if (action.trigger == NFC_EE_TRIG_RF_PROTOCOL)
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_EE_ACTION_EVT; h=0x%X; trigger=rf protocol (0x%X)", fn,
            action.ee_handle, action.trigger);
      else if (action.trigger == NFC_EE_TRIG_RF_TECHNOLOGY)
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_EE_ACTION_EVT; h=0x%X; trigger=rf tech (0x%X)", fn,
            action.ee_handle, action.trigger);
      else
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_EE_ACTION_EVT; h=0x%X; unknown trigger (0x%X)", fn,
            action.ee_handle, action.trigger);
    } break;

    case NFA_EE_DISCOVER_REQ_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_DISCOVER_REQ_EVT; status=0x%X; num ee=%u", __func__,
          eventData->discover_req.status, eventData->discover_req.num_ee);
      SyncEventGuard guard(routingManager.mEeInfoEvent);
      memcpy(&routingManager.mEeInfo, &eventData->discover_req,
             sizeof(routingManager.mEeInfo));
      routingManager.mReceivedEeInfo = true;
      routingManager.mEeInfoEvent.notifyOne();
    } break;

    case NFA_EE_NO_CB_ERR_EVT:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_NO_CB_ERR_EVT  status=%u", fn, eventData->status);
      break;

    case NFA_EE_ADD_AID_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_ADD_AID_EVT  status=%u", fn, eventData->status);
    #if(NXP_EXTNS == TRUE)
        SyncEventGuard guard(routingManager.mAidAddRemoveEvent);
        routingManager.mAidAddRemoveEvent.notifyOne();
    #endif
    } break;

    case NFA_EE_REMOVE_AID_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_REMOVE_AID_EVT  status=%u", fn, eventData->status);
    #if(NXP_EXTNS == TRUE)
        SyncEventGuard guard(routingManager.mAidAddRemoveEvent);
        routingManager.mAidAddRemoveEvent.notifyOne();
    #endif
    } break;

    case NFA_EE_NEW_EE_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_NEW_EE_EVT  h=0x%X; status=%u", fn,
          eventData->new_ee.ee_handle, eventData->new_ee.ee_status);
    } break;

    case NFA_EE_UPDATED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_EE_UPDATED_EVT", fn);
      SyncEventGuard guard(routingManager.mEeUpdateEvent);
      routingManager.mEeUpdateEvent.notifyOne();
    } break;

    default:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: unknown event=%u ????", fn, event);
      break;
  }
}

int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
  static const char fn[] = "RoutingManager::registerT3tIdentifier";

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Start to register NFC-F system on DH", fn);

  if (t3tIdLen != (2 + NCI_RF_F_UID_LEN + NCI_T3T_PMM_LEN)) {
    LOG(ERROR) << StringPrintf("%s: Invalid length of T3T Identifier", fn);
    return NFA_HANDLE_INVALID;
  }

  SyncEventGuard guard(mRoutingEvent);
  mNfcFOnDhHandle = NFA_HANDLE_INVALID;

  int systemCode;
  uint8_t nfcid2[NCI_RF_F_UID_LEN];
  uint8_t t3tPmm[NCI_T3T_PMM_LEN];

  systemCode = (((int)t3tId[0] << 8) | ((int)t3tId[1] << 0));
  memcpy(nfcid2, t3tId + 2, NCI_RF_F_UID_LEN);
  memcpy(t3tPmm, t3tId + 10, NCI_T3T_PMM_LEN);

  tNFA_STATUS nfaStat = NFA_CeRegisterFelicaSystemCodeOnDH(
      systemCode, nfcid2, t3tPmm, nfcFCeCallback);
  if (nfaStat == NFA_STATUS_OK) {
    mRoutingEvent.wait();
  } else {
    LOG(ERROR) << StringPrintf("%s: Fail to register NFC-F system on DH", fn);
    return NFA_HANDLE_INVALID;
  }

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Succeed to register NFC-F system on DH", fn);

  return mNfcFOnDhHandle;
}

void RoutingManager::deregisterT3tIdentifier(int handle) {
  static const char fn[] = "RoutingManager::deregisterT3tIdentifier";

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Start to deregister NFC-F system on DH", fn);

  SyncEventGuard guard(mRoutingEvent);
  tNFA_STATUS nfaStat = NFA_CeDeregisterFelicaSystemCodeOnDH(handle);
  if (nfaStat == NFA_STATUS_OK) {
    mRoutingEvent.wait();
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: Succeeded in deregistering NFC-F system on DH", fn);
  } else {
    LOG(ERROR) << StringPrintf("%s: Fail to deregister NFC-F system on DH", fn);
  }
}

void RoutingManager::nfcFCeCallback(uint8_t event,
                                    tNFA_CONN_EVT_DATA* eventData) {
  static const char fn[] = "RoutingManager::nfcFCeCallback";
  RoutingManager& routingManager = RoutingManager::getInstance();

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: 0x%x", __func__, event);

  switch (event) {
    case NFA_CE_REGISTERED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: registerd event notified", fn);
      routingManager.mNfcFOnDhHandle = eventData->ce_registered.handle;
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
    } break;
    case NFA_CE_DEREGISTERED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: deregisterd event notified", fn);
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
    } break;
    case NFA_CE_ACTIVATED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: activated event notified", fn);
      routingManager.notifyActivated(NFA_TECHNOLOGY_MASK_F);
    } break;
    case NFA_CE_DEACTIVATED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: deactivated event notified", fn);
      routingManager.notifyDeactivated(NFA_TECHNOLOGY_MASK_F);
    } break;
    case NFA_CE_DATA_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: data event notified", fn);
      tNFA_CE_DATA& ce_data = eventData->ce_data;
      routingManager.handleData(NFA_TECHNOLOGY_MASK_F, ce_data.p_data,
                                ce_data.len, ce_data.status);
    } break;
    default: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: unknown event=%u ????", fn, event);
    } break;
  }
}

int RoutingManager::registerJniFunctions(JNIEnv* e) {
  static const char fn[] = "RoutingManager::registerJniFunctions";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", fn);
  return jniRegisterNativeMethods(
      e, "com/android/nfc/cardemulation/AidRoutingManager", sMethods,
      NELEM(sMethods));
}

int RoutingManager::com_android_nfc_cardemulation_doGetDefaultRouteDestination(
    JNIEnv*) {
  return getInstance().mDefaultEe;
}

int RoutingManager::
    com_android_nfc_cardemulation_doGetDefaultOffHostRouteDestination(JNIEnv*) {
  return getInstance().mOffHostEe;
}

int RoutingManager::com_android_nfc_cardemulation_doGetAidMatchingMode(
    JNIEnv*) {
  return getInstance().mAidMatchingMode;
}

#if(NXP_EXTNS == TRUE)
/*******************************************************************************
 **
 ** Function:        getUiccRoute
 **
 ** Description:     returns EE Id corresponding to slot number
 **
 ** Returns:         route location
 **
 *******************************************************************************/
static jint getUiccRoute(jint uicc_slot)
{
    LOG(ERROR) << StringPrintf("%s: Enter slot num = %d", __func__,uicc_slot);
    if((uicc_slot == 0x00) || (uicc_slot == 0x01))
    {
        return SecureElement::getInstance().EE_HANDLE_0xF4;
    }
    else if(uicc_slot == 0x02)
    {
        return (RoutingManager::getInstance().getUicc2selected());
    }
    else
    {
        return 0xFF;
    }
}
/*This function takes the default AID route, protocol(ISO-DEP) route and Tech(A&B) route as arguments in following format
* -----------------------------------------------------------------------------------------------------------
* | RFU(TechA/B) | RouteLocBit1 | RouteLocBit0 | ScreenOff | ScreenLock | BatteryOff | SwitchOff | SwitchOn |
* -----------------------------------------------------------------------------------------------------------
* Route location is set as below
* ----------------------------------------------
* | RouteLocBit1 | RouteLocBit0 | RouteLocation|
* ----------------------------------------------
* |       0      |      0       |    Host      |
* ----------------------------------------------
* |       0      |      1       |    eSE       |
* ----------------------------------------------
* |       1      |      0       |    Uicc1     |
* ----------------------------------------------
* |       1      |      1       |    Uicc2     | => Valid if DYNAMIC_DUAL_UICC is enabled
* ----------------------------------------------
* Based on these parameters, this function creates the protocol route entries/ technology route entries
* which are required to be pushed to listen mode routing table using NFA_EeSetDefaultProtoRouting/TechRouting
*/
bool RoutingManager::setDefaultRoute(const int defaultRoute, const int protoRoute, const int techRoute)
{
    static const char fn []   = "RoutingManager::setDefaultRoute";
    tNFA_STATUS       nfaStat = NFA_STATUS_FAILED;

    DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: enter; defaultRoute:0x%2X protoRoute:0x%2X TechRoute:0x%2X HostListenMask:0x%X", fn, defaultRoute, protoRoute, techRoute, mHostListnTechMask);
    extractRouteLocationAndPowerStates(defaultRoute,protoRoute,techRoute);


    if(NFA_GetNCIVersion() == NCI_VERSION_2_0) {
        setEmptyAidEntry();
    }

    if (mHostListnTechMask)
    {
       nfaStat = NFA_CeSetIsoDepListenTech(mHostListnTechMask & 0xB);
       if (nfaStat != NFA_STATUS_OK)
         LOG(ERROR) << StringPrintf("Failed to configure CE IsoDep technologies");
       nfaStat = NFA_CeRegisterAidOnDH (NULL, 0, stackCallback);
       if (nfaStat != NFA_STATUS_OK)
         LOG(ERROR) << StringPrintf("Failed to register wildcard AID for DH");
    }

    checkProtoSeID();

    initialiseTableEntries ();

    compileProtoEntries ();

    consolidateProtoEntries ();

    setProtoRouting ();

    compileTechEntries ();

    consolidateTechEntries ();

    setTechRouting ();

    configureOffHostNfceeTechMask();

    DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: exit", fn);
    return true;
}

/* extract route location and power states in defaultRoute,protoRoute & techRoute in the following format
 * -----------------------------------------------------------------------------------------------------------
 * |  |  | ScreenOffLock | ScreenOff | ScreenLock | BatteryOff | SwitchOff | SwitchOn |
 * -----------------------------------------------------------------------------------------------------------
 *  * -----------------------------------------------------------------------------------------------------------
 * |  |  |  | |  | RFU(TechA/B) | RouteLocBit1 | RouteLocBit0
 * -----------------------------------------------------------------------------------------------------------
 * to mDefaultIso7816SeID & mDefaultIso7816Powerstate
 *    mDefaultIsoDepSeID  & mDefaultIsoDepPowerstate
 *    mDefaultTechASeID   & mDefaultTechAPowerstate
 */
void RoutingManager::extractRouteLocationAndPowerStates(const int defaultRoute, const int protoRoute, const int techRoute)
{
    static const char fn []   = "RoutingManager::extractRouteLocationAndPowerStates";
    LOG(INFO) << StringPrintf("%s:mDefaultIso7816SeID:0x%2X mDefaultIsoDepSeID:0x%X mDefaultTechASeID 0x%X", fn, defaultRoute & 0x0300, protoRoute & 0x0300,techRoute & 0x0300);
    mDefaultIso7816SeID = ((((defaultRoute & 0x0300) >> 8) == 0x00) ? ROUTE_LOC_HOST_ID : ((((defaultRoute & 0x0300)>>8 )== 0x01 ) ? ROUTE_LOC_ESE_ID : getUiccRouteLocId(defaultRoute)));
    mDefaultIso7816Powerstate = defaultRoute & 0x3F;
    LOG(INFO) << StringPrintf("%s:mDefaultIso7816SeID:0x%2X mDefaultIso7816Powerstate:0x%X", fn, mDefaultIso7816SeID, mDefaultIso7816Powerstate);
    mDefaultIsoDepSeID = ((((protoRoute & 0x0300) >> 8) == 0x00) ? ROUTE_LOC_HOST_ID : ((((protoRoute & 0x0300)>>8 )== 0x01 ) ? ROUTE_LOC_ESE_ID : getUiccRouteLocId(protoRoute)));
    mDefaultIsoDepPowerstate = protoRoute & 0x3F;
    LOG(INFO) << StringPrintf("%s:mDefaultIsoDepSeID:0x%2X mDefaultIsoDepPowerstate:0x%2X", fn, mDefaultIsoDepSeID,mDefaultIsoDepPowerstate);
    mDefaultTechASeID = ((((techRoute & 0x0300) >> 8) == 0x00) ? ROUTE_LOC_HOST_ID : ((((techRoute & 0x0300)>>8 )== 0x01 ) ? ROUTE_LOC_ESE_ID : getUiccRouteLocId(techRoute)));
    mDefaultTechAPowerstate = techRoute & 0x3F;
    LOG(INFO) << StringPrintf("%s:mDefaultTechASeID:0x%2X mDefaultTechAPowerstate:0x%2X", fn, mDefaultTechASeID,mDefaultTechAPowerstate);
}

/*
 * In NCI2.0 Protocol 7816 routing is replaced with empty AID
 * Routing entry Format :
 *  Type   = [0x12]
 *  Length = 2 [0x02]
 *  Value  = [Route_loc, Power_state]
 * */
void RoutingManager::setEmptyAidEntry() {

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter",__func__);
    uint16_t routeLoc;
    uint8_t power;

    routeLoc = mDefaultIso7816SeID;

    power    = mCeRouteStrictDisable ? mDefaultIso7816Powerstate : (mDefaultIso7816Powerstate & POWER_STATE_MASK);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: route %x",__func__,routeLoc);
    if(routeLoc == 0x400) power &= 0x11;
    if (routeLoc  == NFA_HANDLE_INVALID)
    {
        LOG(ERROR) << StringPrintf("%s: Invalid routeLoc. Return.", __func__);
        return;
    }

    tNFA_STATUS nfaStat = NFA_EeAddAidRouting(routeLoc, 0, NULL, power, 0x10);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Status :0x%2x", __func__, nfaStat);
}

/* To check whether the route location for ISO-DEP protocol defined by user in config file is actually connected or not
 * If not connected then set it to HOST by default*/
void RoutingManager::checkProtoSeID(void)
{
    uint8_t           isDefaultIsoDepSeIDPresent    = 0;
    uint8_t           isDefaultAidRoutePresent      = 0;
    tNFA_HANDLE       ActDevHandle                  = NFA_HANDLE_INVALID;
    unsigned long     check_default_proto_se_id_req = 0;
    static const char fn []   = "RoutingManager::checkProtoSeID";
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter",__func__);
//FIX THIS
    /*
    if (GetNxpNumValue(NAME_CHECK_DEFAULT_PROTO_SE_ID, &check_default_proto_se_id_req, sizeof(check_default_proto_se_id_req)))
    {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: CHECK_DEFAULT_PROTO_SE_ID - 0x%2lX ",fn,check_default_proto_se_id_req);
    }
    else
    {
        LOG(ERROR) << StringPrintf("%s: CHECK_DEFAULT_PROTO_SE_ID not defined. Taking default value - 0x%2lX",fn,check_default_proto_se_id_req);
    }
*/
    if(check_default_proto_se_id_req == 0x01)
    {
        uint8_t count,seId=0;
        tNFA_HANDLE ee_handleList[nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED];
        SecureElement::getInstance().getEeHandleList(ee_handleList, &count);
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: count : %d", fn, count);
        for (int  i = 0; ((count != 0 ) && (i < count)); i++)
        {
            seId = SecureElement::getInstance().getGenericEseId(ee_handleList[i]);
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: seId : %d", fn, seId);
            ActDevHandle = SecureElement::getInstance().getEseHandleFromGenericId(seId);
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: ActDevHandle : 0x%X", fn, ActDevHandle);
            if (mDefaultIsoDepSeID == ActDevHandle)
            {
                isDefaultIsoDepSeIDPresent = 1;
            }
            if (mDefaultIso7816SeID == ActDevHandle)
            {
                isDefaultAidRoutePresent = 1;
            }
            if(isDefaultIsoDepSeIDPresent && isDefaultAidRoutePresent)
            {
                break;
            }
        }

        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:isDefaultIsoDepSeIDPresent:0x%X", fn, isDefaultIsoDepSeIDPresent);
        if(!isDefaultIsoDepSeIDPresent)
        {
            mDefaultIsoDepSeID = ROUTE_LOC_HOST_ID;
            mDefaultIsoDepPowerstate = PWR_SWTCH_ON_SCRN_UNLCK_MASK | PWR_SWTCH_ON_SCRN_LOCK_MASK;
        }
        if(!isDefaultAidRoutePresent)
        {
            mDefaultIso7816SeID = ROUTE_LOC_HOST_ID;
            mDefaultIso7816Powerstate = PWR_SWTCH_ON_SCRN_UNLCK_MASK | PWR_SWTCH_ON_SCRN_LOCK_MASK;
        }
    }

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", fn);
}

void RoutingManager::configureOffHostNfceeTechMask(void)
{
    //static const char fn []           = "RoutingManager::configureOffHostNfceeTechMask";
    tNFA_STATUS       nfaStat         = NFA_STATUS_FAILED;
    uint8_t           seId            = 0x00;
    uint8_t           count           = 0x00;
    tNFA_HANDLE       preferredHandle = SecureElement::getInstance().EE_HANDLE_0xF4;
    tNFA_HANDLE       defaultHandle   = NFA_HANDLE_INVALID;
    tNFA_HANDLE       ee_handleList[nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED];

    //ALOGV("%s: enter", fn);

    if (mDefaultEe & SecureElement::ESE_ID) //eSE
    {
        preferredHandle = ROUTE_LOC_ESE_ID;
    }
    else if (mDefaultEe & SecureElement::UICC_ID) //UICC
    {
        preferredHandle = SecureElement::getInstance().EE_HANDLE_0xF4;
    }
    else if (nfcFL.nfccFL._NFCC_DYNAMIC_DUAL_UICC &&
            nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH &&
            (mDefaultEe & SecureElement::UICC2_ID)) //UICC
    {
        preferredHandle = getUicc2selected();
    }

    SecureElement::getInstance().getEeHandleList(ee_handleList, &count);

    for (uint8_t i = 0; ((count != 0 ) && (i < count)); i++)
    {
        seId = SecureElement::getInstance().getGenericEseId(ee_handleList[i]);
        defaultHandle = SecureElement::getInstance().getEseHandleFromGenericId(seId);
        //ALOGV("%s: ee_handleList[%d] : 0x%X", fn, i,ee_handleList[i]);
        if (preferredHandle == defaultHandle)
        {
            break;
        }
        defaultHandle   = NFA_HANDLE_INVALID;
    }

    if((defaultHandle != NFA_HANDLE_INVALID)  &&  (0 != mUiccListnTechMask))
    {
        {
            //SyncEventGuard guard (SecureElement::getInstance().mUiccListenEvent);
            nfaStat = NFA_CeConfigureUiccListenTech (defaultHandle, 0x00);
            if (nfaStat == NFA_STATUS_OK)
            {
                 //SecureElement::getInstance().mUiccListenEvent.wait ();
            }
            else
                 LOG(ERROR) << StringPrintf("fail to start UICC listen");
        }
        {
            //SyncEventGuard guard (SecureElement::getInstance().mUiccListenEvent);
            nfaStat = NFA_CeConfigureUiccListenTech (defaultHandle, (mUiccListnTechMask & 0x07));
            if(nfaStat == NFA_STATUS_OK)
            {
                 //SecureElement::getInstance().mUiccListenEvent.wait ();
            }
            else
                 LOG(ERROR) << StringPrintf("fail to start UICC listen");
        }
    }

    //ALOGV("%s: exit", fn);
}

void RoutingManager::initialiseTableEntries(void)
{
    static const char fn [] = "RoutingManager::initialiseTableEntries";

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", fn);

    /* Defined Protocol Masks
    * T1T      0x01
    * T2T      0x02
    * T3T      0x04
    * ISO-DEP  0x08
    * NFC-DEP  0x10
    * ISO-7816 0x20
    */

    mProtoTableEntries[PROTO_T3T_IDX].protocol     = NFA_PROTOCOL_MASK_T3T;
    mProtoTableEntries[PROTO_ISODEP_IDX].protocol  = NFA_PROTOCOL_MASK_ISO_DEP;
    if(NFA_GetNCIVersion() == NCI_VERSION_1_0) {
        mProtoTableEntries[PROTO_ISO7816_IDX].protocol = NFC_PROTOCOL_MASK_ISO7816;
    }

    mTechTableEntries[TECH_A_IDX].technology       = NFA_TECHNOLOGY_MASK_A;
    mTechTableEntries[TECH_B_IDX].technology       = NFA_TECHNOLOGY_MASK_B;
    mTechTableEntries[TECH_F_IDX].technology       = NFA_TECHNOLOGY_MASK_F;


    for(int xx = 0; xx < AVAILABLE_PROTO_ENTRIES(); xx++)
    {
        mProtoTableEntries[xx].routeLoc = mTechTableEntries[xx].routeLoc = 0x00;
        mProtoTableEntries[xx].power    = mTechTableEntries[xx].power    = 0x00;
        mProtoTableEntries[xx].enable   = mTechTableEntries[xx].enable   = false;
    }

    mLmrtEntries[ROUTE_LOC_HOST_ID_IDX].nfceeID    = ROUTE_LOC_HOST_ID;
    mLmrtEntries[ROUTE_LOC_ESE_ID_IDX].nfceeID     = ROUTE_LOC_ESE_ID;
    mLmrtEntries[ROUTE_LOC_UICC1_ID_IDX].nfceeID   = SecureElement::getInstance().EE_HANDLE_0xF4;
    mLmrtEntries[ROUTE_LOC_UICC2_ID_IDX].nfceeID   = getUicc2selected();
    /*Initialize the table for all route location nfceeID*/
    for(int xx=0;xx<MAX_ROUTE_LOC_ENTRIES;xx++)
    {
        mLmrtEntries[xx].proto_switch_on   = mLmrtEntries[xx].tech_switch_on   = 0x00;
        mLmrtEntries[xx].proto_switch_off  = mLmrtEntries[xx].tech_switch_off  = 0x00;
        mLmrtEntries[xx].proto_battery_off = mLmrtEntries[xx].tech_battery_off = 0x00;
        mLmrtEntries[xx].proto_screen_lock = mLmrtEntries[xx].tech_screen_lock = 0x00;
        mLmrtEntries[xx].proto_screen_off  = mLmrtEntries[xx].tech_screen_off  = 0x00;
        mLmrtEntries[xx].proto_screen_off_lock  = mLmrtEntries[xx].tech_screen_off_lock  = 0x00;
    }
    /*Get all the technologies supported by all the execution environments*/
     mTechSupportedByEse   = SecureElement::getInstance().getSETechnology(ROUTE_LOC_ESE_ID);
     mTechSupportedByUicc1 = SecureElement::getInstance().getSETechnology(SecureElement::getInstance().EE_HANDLE_0xF4);
     mTechSupportedByUicc2 = SecureElement::getInstance().getSETechnology(getUicc2selected());
 
     LOG(INFO) << StringPrintf("%s: exit; mTechSupportedByEse:0x%0X mTechSupportedByUicc1:0x%0X mTechSupportedByUicc2:0x%0X", fn, mTechSupportedByEse, mTechSupportedByUicc1, mTechSupportedByUicc2);
     DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit; mTechSupportedByEse:0x%0X mTechSupportedByUicc1:0x%0X mTechSupportedByUicc2:0x%0X", fn, mTechSupportedByEse, mTechSupportedByUicc1, mTechSupportedByUicc2);

}

/* Compilation of Proto Table entries strictly based on config file parameters
 * Each entry in proto table consistes of route location, protocol and power state
 * */
void RoutingManager::compileProtoEntries(void)
{
    static const char fn[] = "RoutingManager::compileProtoEntries";

    LOG(INFO) << StringPrintf("%s: enter", fn);

    /*Populate the entries on  protocol table*/
    mProtoTableEntries[PROTO_T3T_IDX].routeLoc = ROUTE_LOC_HOST_ID;//T3T Proto always to HOST. For other EE used Tech F routing
    mProtoTableEntries[PROTO_T3T_IDX].power    = PWR_SWTCH_ON_SCRN_UNLCK_MASK; //Only Screen ON UNLOCK allowed
    mProtoTableEntries[PROTO_T3T_IDX].enable   = ((mHostListnTechMask & 0x04) != 0x00) ? true : false;

    mProtoTableEntries[PROTO_ISODEP_IDX].routeLoc = mDefaultIsoDepSeID;
    mProtoTableEntries[PROTO_ISODEP_IDX].power    = mCeRouteStrictDisable ? mDefaultIsoDepPowerstate : (mDefaultIsoDepPowerstate & POWER_STATE_MASK);
    mProtoTableEntries[PROTO_ISODEP_IDX].enable   = ((mHostListnTechMask & 0x03) != 0x00) ? true : false;

    if(NFA_GetNCIVersion() == NCI_VERSION_1_0) {
    mProtoTableEntries[PROTO_ISO7816_IDX].routeLoc = mDefaultIso7816SeID;
    mProtoTableEntries[PROTO_ISO7816_IDX].power    = mCeRouteStrictDisable ? mDefaultIso7816Powerstate : (mDefaultIso7816Powerstate & POWER_STATE_MASK);
    mProtoTableEntries[PROTO_ISO7816_IDX].enable   = (mDefaultIso7816SeID == ROUTE_LOC_HOST_ID) ? (((mHostListnTechMask & 0x03) != 0x00) ? true : false):(true);
    }
    dumpTables(1);

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", fn);
}

/* libnfc-nci takes protocols for each power-state for single route location
 * The previous protocols set will be overwritten by new protocols set by NFA_EeSetDefaultProtoRouting
 * So consolidate all the protocols/power state for a given NFCEE ID's
 * For example:
 * When PROTOCOL(ISO-DEP) and  AID default route(ISO7816) set to same EE then set (ISO-DEP | ISO-7816) to that EE.
 */
void RoutingManager::consolidateProtoEntries(void)
{
    static const char fn [] = "RoutingManager::consolidateProtoEntries";

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", fn);

    int index = -1;

    for(int xx=0;xx<AVAILABLE_PROTO_ENTRIES();xx++)
    {
        if(mProtoTableEntries[xx].enable)
        {
            switch(mProtoTableEntries[xx].routeLoc)
            {
                case ROUTE_LOC_HOST_ID:
                        index = ROUTE_LOC_HOST_ID_IDX;
                    break;
                case ROUTE_LOC_ESE_ID:
                        index = ROUTE_LOC_ESE_ID_IDX;
                    break;
                case ROUTE_LOC_UICC1_ID:
                case ROUTE_LOC_UICC1_ID_NCI2_0:
                        index = ROUTE_LOC_UICC1_ID_IDX;
                    break;
                case ROUTE_LOC_UICC2_ID:
                case ROUTE_LOC_UICC3_ID:
                        index = ROUTE_LOC_UICC2_ID_IDX;
                    break;
            }
            if(index != -1)
            {
                mLmrtEntries[index].proto_switch_on    = (mLmrtEntries[index].proto_switch_on)   |
                                                         ((mProtoTableEntries[xx].power & PWR_SWTCH_ON_SCRN_UNLCK_MASK) ? mProtoTableEntries[xx].protocol:0);
                mLmrtEntries[index].proto_switch_off   = (mLmrtEntries[index].proto_switch_off)  |
                                                         ((mProtoTableEntries[xx].power & PWR_SWTCH_OFF_MASK) ? mProtoTableEntries[xx].protocol:0);
                mLmrtEntries[index].proto_battery_off  = (mLmrtEntries[index].proto_battery_off) |
                                                         ((mProtoTableEntries[xx].power & PWR_BATT_OFF_MASK) ? mProtoTableEntries[xx].protocol:0);
                mLmrtEntries[index].proto_screen_lock  = (mLmrtEntries[index].proto_screen_lock) |
                                                         ((mProtoTableEntries[xx].power & PWR_SWTCH_ON_SCRN_LOCK_MASK) ? mProtoTableEntries[xx].protocol:0);
                mLmrtEntries[index].proto_screen_off   = (mLmrtEntries[index].proto_screen_off)  |
                                                         ((mProtoTableEntries[xx].power & PWR_SWTCH_ON_SCRN_OFF_MASK) ? mProtoTableEntries[xx].protocol:0);
                mLmrtEntries[index].proto_screen_off_lock   = (mLmrtEntries[index].proto_screen_off_lock)  |
                                                         ((mProtoTableEntries[xx].power & PWR_SWTCH_ON_SCRN_OFF_LOCK_MASK) ? mProtoTableEntries[xx].protocol:0);
            }
        }
    }

    dumpTables(2);

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", fn);
}

void RoutingManager::setProtoRouting()
{
    static const char fn [] = "RoutingManager::setProtoRouting";
    tNFA_STATUS nfaStat     = NFA_STATUS_FAILED;

    LOG(INFO) << StringPrintf("%s: enter", fn);
    SyncEventGuard guard (mRoutingEvent);
    for(int xx=0;xx<MAX_ROUTE_LOC_ENTRIES;xx++)
    {
        LOG(INFO) << StringPrintf("%s: nfceeID:0x%X", fn, mLmrtEntries[xx].nfceeID);
        if( mLmrtEntries[xx].nfceeID           &&
           (mLmrtEntries[xx].proto_switch_on   ||
            mLmrtEntries[xx].proto_switch_off  ||
            mLmrtEntries[xx].proto_battery_off ||
            mLmrtEntries[xx].proto_screen_lock ||
            mLmrtEntries[xx].proto_screen_off  ||
            mLmrtEntries[xx].proto_screen_off_lock) )
        {
            /*Clear protocols for NFCEE ID control block */
            //ALOGV("%s: Clear Proto Routing Entries for nfceeID:0x%X", fn, mLmrtEntries[xx].nfceeID);
            nfaStat = NFA_EeSetDefaultProtoRouting(mLmrtEntries[xx].nfceeID,0,0,0,0,0,0);
            if(nfaStat == NFA_STATUS_OK)
            {
                mRoutingEvent.wait ();
            }
            else
            {
                LOG(ERROR) << StringPrintf("Fail to clear proto routing to 0x%X",mLmrtEntries[xx].nfceeID);           
            }
            /*Set Required protocols for NFCEE ID control block in libnfc-nci*/
            nfaStat = NFA_EeSetDefaultProtoRouting(mLmrtEntries[xx].nfceeID,
                                                   mLmrtEntries[xx].proto_switch_on,
                                                   mLmrtEntries[xx].proto_switch_off,
                                                   mLmrtEntries[xx].proto_battery_off,
                                                   mLmrtEntries[xx].proto_screen_lock,
                                                   mLmrtEntries[xx].proto_screen_off,
                                                   mLmrtEntries[xx].proto_screen_off_lock);
            if(nfaStat == NFA_STATUS_OK)
            {
                mRoutingEvent.wait ();
            }
            else
            {
                LOG(ERROR) << StringPrintf("Fail to set proto routing to 0x%X",mLmrtEntries[xx].nfceeID);            
            }
        }
    }
    LOG(INFO) << StringPrintf("%s: exit", fn);
}

/* Compilation of Tech Table entries strictly based on config file parameters
 * Each entry in tech table consistes of route location, technology and power state
 * */
void RoutingManager::compileTechEntries(void)
{
    static const char fn []          = "RoutingManager::compileTechEntries";
    uint32_t techSupportedBySelectedEE = 0;
    unsigned long num = 0;
    LOG(INFO) << StringPrintf("%s: enter", fn);

    /*Check technologies supported by EE selected in conf file*/
    if(mDefaultTechASeID == SecureElement::getInstance().EE_HANDLE_0xF4)
        techSupportedBySelectedEE = mTechSupportedByUicc1;
    else if(mDefaultTechASeID == getUicc2selected())
        techSupportedBySelectedEE = mTechSupportedByUicc2;
    else if(mDefaultTechASeID == ROUTE_LOC_ESE_ID)
        techSupportedBySelectedEE = mTechSupportedByEse;
    else
        techSupportedBySelectedEE = 0; /*For Host, no tech based route supported as Host always reads protocol data*/

    /*Populate the entries on  tech route table*/
    mTechTableEntries[TECH_A_IDX].routeLoc = mDefaultTechASeID;
    mTechTableEntries[TECH_A_IDX].power    = mCeRouteStrictDisable ? mDefaultTechAPowerstate : (mDefaultTechAPowerstate & POWER_STATE_MASK);
    mTechTableEntries[TECH_A_IDX].enable   = (techSupportedBySelectedEE & NFA_TECHNOLOGY_MASK_A)? true : false;

    /*Reuse the same power state and route location used for A*/
    mTechTableEntries[TECH_B_IDX].routeLoc = mDefaultTechASeID;
    mTechTableEntries[TECH_B_IDX].power    = mCeRouteStrictDisable ? mDefaultTechAPowerstate : (mDefaultTechAPowerstate & POWER_STATE_MASK);
    mTechTableEntries[TECH_B_IDX].enable   = (techSupportedBySelectedEE & NFA_TECHNOLOGY_MASK_B)? true : false;

    /*Update Tech F Route in case there is switch between uicc's*/
    if(nfcFL.eseFL._ESE_FELICA_CLT) {
        if (GetNxpNumValue (NAME_DEFAULT_FELICA_CLT_ROUTE, (void*)&num, sizeof(num)))
        {
            if(nfcFL.nfccFL._NFCC_DYNAMIC_DUAL_UICC && nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) {
                if((num == 0x02 || num == 0x03) && sCurrentSelectedUICCSlot)
                {
                    mDefaultTechFSeID = getUiccRoute(sCurrentSelectedUICCSlot);
                }
                else
                {
                    mDefaultTechFSeID = ( (num == 0x01) ? ROUTE_LOC_ESE_ID : ((num == 0x02) ? SecureElement::getInstance().EE_HANDLE_0xF4 : getUicc2selected()) );
                }
            }else{
                mDefaultTechFSeID = ( (num == 0x01) ? ROUTE_LOC_ESE_ID : ((num == 0x02) ? SecureElement::getInstance().EE_HANDLE_0xF4 : getUicc2selected()) );
            }
        }
        else
        {
            mDefaultTechFSeID = getUiccRoute(sCurrentSelectedUICCSlot);
        }
    } else {
        mDefaultTechFSeID = SecureElement::getInstance().EE_HANDLE_0xF4;
    }
    /*Check technologies supported by EE selected in conf file - For TypeF*/
    if(mDefaultTechFSeID == SecureElement::getInstance().EE_HANDLE_0xF4)
        techSupportedBySelectedEE = mTechSupportedByUicc1;
    else if(mDefaultTechFSeID == getUicc2selected())
        techSupportedBySelectedEE = mTechSupportedByUicc2;
    else if(mDefaultTechFSeID == ROUTE_LOC_ESE_ID)
        techSupportedBySelectedEE = mTechSupportedByEse;
    else
        techSupportedBySelectedEE = 0;/*For Host, no tech based route supported as Host always reads protocol data*/

    mTechTableEntries[TECH_F_IDX].routeLoc = mDefaultTechFSeID;
    mTechTableEntries[TECH_F_IDX].power    = mCeRouteStrictDisable ? mDefaultTechFPowerstate : (mDefaultTechFPowerstate & POWER_STATE_MASK);
    mTechTableEntries[TECH_F_IDX].enable   = (techSupportedBySelectedEE & NFA_TECHNOLOGY_MASK_F)? true : false;

    dumpTables(3);
    if(((mHostListnTechMask) && (mHostListnTechMask != 0X04)) && (mFwdFuntnEnable == true))
    {
        processTechEntriesForFwdfunctionality();
    }
    //ALOGV("%s: exit", fn);
}

/* Forward Functionality is to handle either technology which is supported by UICC
 * We are handling it by setting the alternate technology(A/B) to HOST
 * */
void RoutingManager::processTechEntriesForFwdfunctionality(void)
{
    //static const char fn []    = "RoutingManager::processTechEntriesForFwdfunctionality";
    uint32_t techSupportedByUICC = mTechSupportedByUicc1;
    if(nfcFL.nfccFL._NFCC_DYNAMIC_DUAL_UICC && nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) {
        techSupportedByUICC = (getUiccRoute(sCurrentSelectedUICCSlot) == SecureElement::getInstance().EE_HANDLE_0xF4)?
                mTechSupportedByUicc1 : mTechSupportedByUicc2;
    }
    else if (nfcFL.nfccFL._NFCC_DYNAMIC_DUAL_UICC) {
        techSupportedByUICC = (mDefaultTechASeID == SecureElement::getInstance().EE_HANDLE_0xF4)?
                mTechSupportedByUicc1:mTechSupportedByUicc2;
    }
    //ALOGV("%s: enter", fn);

    switch(mHostListnTechMask)
    {
    case 0x01://Host wants to listen ISO-DEP in A tech only then following cases will arises:-
        //i.Tech A only UICC present(Dont route Tech B to HOST),
        //ii.Tech B only UICC present(Route Tech A to HOST),
        //iii.Tech AB UICC present(Dont route any tech to HOST)
        if(((mTechTableEntries[TECH_B_IDX].routeLoc == SecureElement::getInstance().EE_HANDLE_0xF4) || (mTechTableEntries[TECH_B_IDX].routeLoc == getUicc2selected())) &&
                ((((techSupportedByUICC & NFA_TECHNOLOGY_MASK_B) == 0) && (techSupportedByUICC & NFA_TECHNOLOGY_MASK_A) != 0)))//Tech A only supported UICC
        {
            //Tech A will goto UICC according to previous table
            //Disable Tech B entry as host wants to listen A only
            mTechTableEntries[TECH_B_IDX].enable   = false;
        }
        if(((mTechTableEntries[TECH_A_IDX].routeLoc == SecureElement::getInstance().EE_HANDLE_0xF4) || (mTechTableEntries[TECH_A_IDX].routeLoc == getUicc2selected())) &&
                ((((techSupportedByUICC & NFA_TECHNOLOGY_MASK_A) == 0) && (techSupportedByUICC & NFA_TECHNOLOGY_MASK_B) != 0)))//Tech B only supported UICC
        {
            //Tech B will goto UICC according to previous table
            //Route Tech A to HOST as Host wants to listen A only
            mTechTableEntries[TECH_A_IDX].routeLoc = ROUTE_LOC_HOST_ID;
            /*Allow only (screen On+unlock) and (screen On+lock) power state when routing to HOST*/
            mTechTableEntries[TECH_A_IDX].power    = (mTechTableEntries[TECH_A_IDX].power & HOST_SCREEN_STATE_MASK);
            mTechTableEntries[TECH_A_IDX].enable   = true;
        }
        if((techSupportedByUICC & 0x03) == 0x03)//AB both supported UICC
        {
            //Do Nothing
            //Tech A and Tech B will goto according to previous table
            //HCE A only / HCE-B only functionality wont work in this case
        }
        break;
    case 0x02://Host wants to listen ISO-DEP in B tech only then if Cases: Tech A only UICC present(Route Tech B to HOST), Tech B only UICC present(Dont route Tech A to HOST), Tech AB UICC present(Dont route any tech to HOST)
        if(((mTechTableEntries[TECH_B_IDX].routeLoc == SecureElement::getInstance().EE_HANDLE_0xF4) || (mTechTableEntries[TECH_B_IDX].routeLoc == getUicc2selected())) &&
                ((((techSupportedByUICC & NFA_TECHNOLOGY_MASK_B) == 0) && (techSupportedByUICC & NFA_TECHNOLOGY_MASK_A) != 0)))//Tech A only supported UICC
        {
            //Tech A will goto UICC according to previous table
            //Route Tech B to HOST as host wants to listen B only
            mTechTableEntries[TECH_B_IDX].routeLoc = ROUTE_LOC_HOST_ID;
            /*Allow only (screen On+unlock) and (screen On+lock) power state when routing to HOST*/
            mTechTableEntries[TECH_B_IDX].power    = (mTechTableEntries[TECH_A_IDX].power & HOST_SCREEN_STATE_MASK);
            mTechTableEntries[TECH_B_IDX].enable   = true;
        }
        if(((mTechTableEntries[TECH_A_IDX].routeLoc == SecureElement::getInstance().EE_HANDLE_0xF4) || (mTechTableEntries[TECH_A_IDX].routeLoc == getUicc2selected())) &&
                ((((techSupportedByUICC & NFA_TECHNOLOGY_MASK_A) == 0) && (techSupportedByUICC & NFA_TECHNOLOGY_MASK_B) != 0)))//Tech B only supported UICC
        {
            //Tech B will goto UICC according to previous table
            //Disable Tech A to HOST as host wants to listen B only
            mTechTableEntries[TECH_A_IDX].enable   = false;
        }
        if((techSupportedByUICC & 0x03) == 0x03)//AB both supported UICC
        {
            //Do Nothing
            //Tech A and Tech B will goto UICC
            //HCE A only / HCE-B only functionality wont work in this case
        }
        break;
    case 0x03:
    case 0x07://Host wants to listen ISO-DEP in AB both tech then if Cases: Tech A only UICC present(Route Tech B to HOST), Tech B only UICC present(Route Tech A to HOST), Tech AB UICC present(Dont route any tech to HOST)
        /*If selected EE is UICC and it supports Bonly , then Set Tech A to Host */
        /*Host doesn't support Tech Routing, To enable FWD functionality enabling tech route to Host.*/
        if(((mTechTableEntries[TECH_A_IDX].routeLoc == SecureElement::getInstance().EE_HANDLE_0xF4) || (mTechTableEntries[TECH_A_IDX].routeLoc == getUicc2selected())) &&
                ((((techSupportedByUICC & NFA_TECHNOLOGY_MASK_A) == 0) && (techSupportedByUICC & NFA_TECHNOLOGY_MASK_B) != 0)))
        {
            mTechTableEntries[TECH_A_IDX].routeLoc = ROUTE_LOC_HOST_ID;
            /*Allow only (screen On+unlock) and (screen On+lock) power state when routing to HOST*/
            mTechTableEntries[TECH_A_IDX].power    = (mTechTableEntries[TECH_A_IDX].power & HOST_SCREEN_STATE_MASK);
            mTechTableEntries[TECH_A_IDX].enable   = true;
        }
        /*If selected EE is UICC and it supports Aonly , then Set Tech B to Host*/
        if(((mTechTableEntries[TECH_B_IDX].routeLoc == SecureElement::getInstance().EE_HANDLE_0xF4) || (mTechTableEntries[TECH_B_IDX].routeLoc == getUicc2selected())) &&
                ((((techSupportedByUICC & NFA_TECHNOLOGY_MASK_B) == 0) && (techSupportedByUICC & NFA_TECHNOLOGY_MASK_A) != 0)))
        {
            mTechTableEntries[TECH_B_IDX].routeLoc = ROUTE_LOC_HOST_ID;
            /*Allow only (screen On+unlock) and (screen On+lock) power state when routing to HOST*/
            mTechTableEntries[TECH_B_IDX].power    = (mTechTableEntries[TECH_A_IDX].power & HOST_SCREEN_STATE_MASK);
            mTechTableEntries[TECH_B_IDX].enable   = true;
        }
        if((techSupportedByUICC & 0x03) == 0x03)//AB both supported UICC
        {
            //Do Nothing
            //Tech A and Tech B will goto UICC
            //HCE A only / HCE-B only functionality wont work in this case
        }
        break;
    }
    dumpTables(3);
    //ALOGV("%s: exit", fn);
}

/* libnfc-nci takes technologies for each power-state for single route location
 * The previous technologies set will be overwritten by new technologies set by NFA_EeSetDefaultTechRouting
 * So consolidate all the techs/power state for a given NFCEE ID's
 * For example:
 * When Tech A and Tech F set to same EE then set (TechA | Tech F) to that EE.
 */
void RoutingManager::consolidateTechEntries(void)
{
    static const char fn [] = "RoutingManager::consolidateTechEntries";
    LOG(INFO) << StringPrintf("%s: enter", fn);
    int index=-1;
    for(int xx=0;xx<MAX_TECH_ENTRIES;xx++)
    {
        if(mTechTableEntries[xx].enable)
        {
            switch(mTechTableEntries[xx].routeLoc)
            {
                case ROUTE_LOC_HOST_ID:
                        index = ROUTE_LOC_HOST_ID_IDX;
                    break;
                case ROUTE_LOC_ESE_ID:
                        index = ROUTE_LOC_ESE_ID_IDX;
                    break;
                case ROUTE_LOC_UICC1_ID:
                case ROUTE_LOC_UICC1_ID_NCI2_0:
                        index = ROUTE_LOC_UICC1_ID_IDX;
                    break;
                case ROUTE_LOC_UICC2_ID:
                case ROUTE_LOC_UICC3_ID:
                        index = ROUTE_LOC_UICC2_ID_IDX;
                    break;
            }
            if(index != -1)
            {
                mLmrtEntries[index].tech_switch_on    = mLmrtEntries[index].tech_switch_on |
                                                        ((mTechTableEntries[xx].power & PWR_SWTCH_ON_SCRN_UNLCK_MASK)? mTechTableEntries[xx].technology:0);
                mLmrtEntries[index].tech_switch_off   = mLmrtEntries[index].tech_switch_off |
                                                        ((mTechTableEntries[xx].power & PWR_SWTCH_OFF_MASK)? mTechTableEntries[xx].technology:0);
                mLmrtEntries[index].tech_battery_off  = mLmrtEntries[index].tech_battery_off |
                                                        ((mTechTableEntries[xx].power & PWR_BATT_OFF_MASK)? mTechTableEntries[xx].technology:0);
                mLmrtEntries[index].tech_screen_lock  = mLmrtEntries[index].tech_screen_lock |
                                                        ((mTechTableEntries[xx].power & PWR_SWTCH_ON_SCRN_LOCK_MASK)? mTechTableEntries[xx].technology:0);
                mLmrtEntries[index].tech_screen_off   = mLmrtEntries[index].tech_screen_off |
                                                        ((mTechTableEntries[xx].power & PWR_SWTCH_ON_SCRN_OFF_MASK)? mTechTableEntries[xx].technology:0);
                mLmrtEntries[index].tech_screen_off_lock   = mLmrtEntries[index].tech_screen_off_lock |
                                                        ((mTechTableEntries[xx].power & PWR_SWTCH_ON_SCRN_OFF_LOCK_MASK)? mTechTableEntries[xx].technology:0);
            }
        }
    }
    dumpTables(4);
    //ALOGV("%s: exit", fn);
}

void RoutingManager::setTechRouting(void)
{
    static const char fn [] = "RoutingManager::setTechRouting";
    tNFA_STATUS nfaStat     = NFA_STATUS_FAILED;
    LOG(INFO) << StringPrintf("%s: enter", fn);
    SyncEventGuard guard (mRoutingEvent);
    for(int xx=0;xx<MAX_ROUTE_LOC_ENTRIES;xx++)
   {
       if( mLmrtEntries[xx].nfceeID          &&
           (mLmrtEntries[xx].tech_switch_on   ||
            mLmrtEntries[xx].tech_switch_off  ||
            mLmrtEntries[xx].tech_battery_off ||
            mLmrtEntries[xx].tech_screen_lock ||
            mLmrtEntries[xx].tech_screen_off  ||
            mLmrtEntries[xx].tech_screen_off_lock) )
        {
            /*Clear technologies for NFCEE ID control block */
            //ALOGV("%s: Clear Routing Entries for nfceeID:0x%X", fn, mLmrtEntries[xx].nfceeID);
            nfaStat = NFA_EeSetDefaultTechRouting(mLmrtEntries[xx].nfceeID, 0, 0, 0, 0, 0,0);
            if(nfaStat == NFA_STATUS_OK)
            {
                mRoutingEvent.wait ();
            }
            else
            {
                LOG(ERROR) << StringPrintf("Fail to clear tech routing to 0x%x",mLmrtEntries[xx].nfceeID);            
            }

            /*Set Required technologies for NFCEE ID control block */
            nfaStat = NFA_EeSetDefaultTechRouting(mLmrtEntries[xx].nfceeID,
                                                  mLmrtEntries[xx].tech_switch_on,
                                                  mLmrtEntries[xx].tech_switch_off,
                                                  mLmrtEntries[xx].tech_battery_off,
                                                  mLmrtEntries[xx].tech_screen_lock,
                                                  mLmrtEntries[xx].tech_screen_off,
                                                  mLmrtEntries[xx].tech_screen_off_lock);
            if(nfaStat == NFA_STATUS_OK)
            {
                mRoutingEvent.wait ();
            }
            else
            {
                LOG(ERROR) << StringPrintf("Fail to set tech routing to 0x%x",mLmrtEntries[xx].nfceeID);            
            }
        }
    }
    //ALOGV("%s: exit", fn);
}

void RoutingManager::dumpTables(int xx)
{


    switch(xx)
    {
    case 1://print only proto table
        LOG(ERROR) << StringPrintf("--------------------Proto Table Entries------------------" );
        for(int xx=0;xx<AVAILABLE_PROTO_ENTRIES();xx++)
        {
            LOG(ERROR) << StringPrintf("|Index=%d|RouteLoc=0x%03X|Proto=0x%02X|Power=0x%02X|Enable=0x%01X|",
                    xx,mProtoTableEntries[xx].routeLoc,
                    mProtoTableEntries[xx].protocol,
                    mProtoTableEntries[xx].power,
                    mProtoTableEntries[xx].enable);
        }
        //ALOGV("---------------------------------------------------------" );
        break;
    case 2://print Lmrt proto table
        LOG(ERROR) << StringPrintf("----------------------------------------Lmrt Proto Entries------------------------------------" );
        for(int xx=0;xx<AVAILABLE_PROTO_ENTRIES();xx++)
        {
            LOG(ERROR) << StringPrintf("|Index=%d|nfceeID=0x%03X|SWTCH-ON=0x%02X|SWTCH-OFF=0x%02X|BAT-OFF=0x%02X|SCRN-LOCK=0x%02X|SCRN-OFF=0x%02X|SCRN-OFF_LOCK=0x%02X",
                    xx,
                    mLmrtEntries[xx].nfceeID,
                    mLmrtEntries[xx].proto_switch_on,
                    mLmrtEntries[xx].proto_switch_off,
                    mLmrtEntries[xx].proto_battery_off,
                    mLmrtEntries[xx].proto_screen_lock,
                    mLmrtEntries[xx].proto_screen_off,
                    mLmrtEntries[xx].proto_screen_off_lock);
        }
        //ALOGV("----------------------------------------------------------------------------------------------" );
        break;
    case 3://print only tech table
        LOG(ERROR) << StringPrintf("--------------------Tech Table Entries------------------" );
        for(int xx=0;xx<MAX_TECH_ENTRIES;xx++)
        {
            LOG(ERROR) << StringPrintf("|Index=%d|RouteLoc=0x%03X|Tech=0x%02X|Power=0x%02X|Enable=0x%01X|",
                   xx,
                    mTechTableEntries[xx].routeLoc,
                    mTechTableEntries[xx].technology,
                    mTechTableEntries[xx].power,
                    mTechTableEntries[xx].enable);
        }
        //ALOGV("--------------------------------------------------------" );
        break;
    case 4://print Lmrt tech table
        LOG(ERROR) << StringPrintf("-----------------------------------------Lmrt Tech Entries------------------------------------" );
        for(int xx=0;xx<MAX_TECH_ENTRIES;xx++)
        {
            LOG(ERROR) << StringPrintf("|Index=%d|nfceeID=0x%03X|SWTCH-ON=0x%02X|SWTCH-OFF=0x%02X|BAT-OFF=0x%02X|SCRN-LOCK=0x%02X|SCRN-OFF=0x%02X|SCRN-OFF_LOCK=0x%02X",
                xx,
                mLmrtEntries[xx].nfceeID,
                mLmrtEntries[xx].tech_switch_on,
                mLmrtEntries[xx].tech_switch_off,
                mLmrtEntries[xx].tech_battery_off,
                mLmrtEntries[xx].tech_screen_lock,
                mLmrtEntries[xx].tech_screen_off,
                mLmrtEntries[xx].tech_screen_off_lock);
        }
        //ALOGV("----------------------------------------------------------------------------------------------" );
        break;
    }
}
/* Based on the features enabled :- NXP_NFCC_DYNAMIC_DUAL_UICC, NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH & NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH,
 * Calculate the UICC route location ID.
 * For DynamicDualUicc,Route location is based on the user configuration(6th & 7th bit) of route
 * For StaticDualUicc without External Switch(with DynamicDualUicc enabled), Route location is based on user selection from selectUicc() API
 * For StaticDualUicc(With External Switch), Route location is always ROUTE_LOC_UICC1_ID
 */
uint16_t RoutingManager::getUiccRouteLocId(const int route)
{
	LOG(ERROR) << StringPrintf(" getUiccRouteLocId route %X",
                   route);
    if(nfcFL.nfccFL._NFCC_DYNAMIC_DUAL_UICC && nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH)
        return getUiccRoute(sCurrentSelectedUICCSlot);
    else if(nfcFL.nfccFL._NFCC_DYNAMIC_DUAL_UICC)
        return ((((route & 0x0300)>>8 )== 0x02 ) ? SecureElement::getInstance().EE_HANDLE_0xF4 : getUicc2selected());
    else /*#if (NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH == true)*/
        return SecureElement::getInstance().EE_HANDLE_0xF4;
}
/*******************************************************************************
 **
 ** Function:        getUicc2selected
 **
 ** Description:     returns UICC2 selected in config file
 **
 ** Returns:         route location
 **
 *******************************************************************************/
uint32_t RoutingManager:: getUicc2selected()
{
	LOG(ERROR) << StringPrintf(" getUicc2selected route");
    return (SecureElement::getInstance().muicc2_selected == SecureElement::UICC2_ID)?
                 SecureElement::getInstance().EE_HANDLE_0xF8:
                    SecureElement::getInstance().EE_HANDLE_0xF9;
}
#endif
