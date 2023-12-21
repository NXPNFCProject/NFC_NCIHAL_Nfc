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
 *  Copyright 2018-2021, 2023-2024 NXP
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
#include "MposManager.h"
#include "NativeJniExtns.h"
#include "SecureElement.h"
#endif

using android::base::StringPrintf;

extern bool gActivated;
#if (NXP_EXTNS == TRUE)
namespace android {
extern bool isSeRfActive();
extern void setSeRfActive(bool);
}  // namespace android
#endif
extern SyncEvent gDeactivatedEvent;
extern bool nfc_debug_enabled;

const JNINativeMethod RoutingManager::sMethods[] = {
    {"doGetDefaultRouteDestination", "()I",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetDefaultRouteDestination},
    {"doGetDefaultOffHostRouteDestination", "()I",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetDefaultOffHostRouteDestination},
    {"doGetOffHostUiccDestination", "()[B",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetOffHostUiccDestination},
    {"doGetOffHostEseDestination", "()[B",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetOffHostEseDestination},
    {"doGetAidMatchingMode", "()I",
     (void*)
         RoutingManager::com_android_nfc_cardemulation_doGetAidMatchingMode},
    {"doGetDefaultIsoDepRouteDestination", "()I",
     (void*)RoutingManager::
         com_android_nfc_cardemulation_doGetDefaultIsoDepRouteDestination}};

static const int MAX_NUM_EE = 6;
// SCBR from host works only when App is in foreground
static const uint8_t SYS_CODE_PWR_STATE_HOST = 0x01;
#if (NXP_EXTNS != TRUE)
static const uint16_t DEFAULT_SYS_CODE = 0xFEFE;
#else
static RouteInfo_t gRouteInfo;
extern jint nfcManager_getUiccRoute(jint uicc_slot);
extern jint nfcManager_getUiccId(jint uicc_slot);
extern uint16_t sCurrentSelectedUICCSlot;
extern bool isDynamicUiccEnabled;
#endif

static const uint8_t AID_ROUTE_QUAL_PREFIX = 0x10;
RoutingManager::RoutingManager()
    : mSecureNfcEnabled(false),
      mNativeData(NULL)
#if (NXP_EXTNS != TRUE)
      ,
      mAidRoutingConfigured(false)
#endif
{
  static const char fn[] = "RoutingManager::RoutingManager()";

  mDefaultOffHostRoute =
      NfcConfig::getUnsigned(NAME_DEFAULT_OFFHOST_ROUTE, 0x00);

  if (NfcConfig::hasKey(NAME_OFFHOST_ROUTE_UICC)) {
    mOffHostRouteUicc = NfcConfig::getBytes(NAME_OFFHOST_ROUTE_UICC);
  }

  if (NfcConfig::hasKey(NAME_OFFHOST_ROUTE_ESE)) {
    mOffHostRouteEse = NfcConfig::getBytes(NAME_OFFHOST_ROUTE_ESE);
  }

  mDefaultFelicaRoute = NfcConfig::getUnsigned(NAME_DEFAULT_NFCF_ROUTE, 0x00);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s: Active SE for Nfc-F is 0x%02X", fn, mDefaultFelicaRoute);

  mDefaultEe = NfcConfig::getUnsigned(NAME_DEFAULT_ROUTE, 0x00);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: default route is 0x%02X", fn, mDefaultEe);

  mAidMatchingMode =
      NfcConfig::getUnsigned(NAME_AID_MATCHING_MODE, AID_MATCHING_EXACT_ONLY);

  mDefaultSysCodeRoute =
      NfcConfig::getUnsigned(NAME_DEFAULT_SYS_CODE_ROUTE, 0xC0);

  mDefaultSysCodePowerstate =
      NfcConfig::getUnsigned(NAME_DEFAULT_SYS_CODE_PWR_STATE, 0x19);
#if (NXP_EXTNS != TRUE)
  mDefaultSysCode = DEFAULT_SYS_CODE;
#else
  sRoutingBuffLen = 0;
  sRoutingBuff = NULL;
  mDefaultSysCode = 0x00;
  mHostListnTechMask = 0;
  mUiccListnTechMask = 0;
  mFwdFuntnEnable = 0;
  mDefaultTechASeID = 0;
  mTechSupportedByEse = 0;
  mTechSupportedByUicc1 = 0;
  mTechSupportedByUicc2 = 0;
  mDefaultTechFPowerstate = 0;
  memset (mProtoTableEntries, 0, sizeof(mProtoTableEntries));
  memset (mTechTableEntries, 0, sizeof(mTechTableEntries));
  memset(mLmrtEntries, 0, sizeof(LmrtEntry_t));
#endif

  if (NfcConfig::hasKey(NAME_DEFAULT_SYS_CODE)) {
    std::vector<uint8_t> pSysCode = NfcConfig::getBytes(NAME_DEFAULT_SYS_CODE);
    if (pSysCode.size() == 0x02) {
      mDefaultSysCode = ((pSysCode[0] << 8) | ((int)pSysCode[1] << 0));
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: DEFAULT_SYS_CODE: 0x%02X", __func__, mDefaultSysCode);
    }
  }
#if(NXP_EXTNS == TRUE)
  mSecureNfcEnabled = false;
  memset(&mCbEventData, 0, sizeof(mCbEventData));
#endif
  mOffHostAidRoutingPowerState =
      NfcConfig::getUnsigned(NAME_OFFHOST_AID_ROUTE_PWR_STATE, 0x01);

  mDefaultIsoDepRoute = NfcConfig::getUnsigned(NAME_DEFAULT_ISODEP_ROUTE, 0x0);

  memset(&mEeInfo, 0, sizeof(mEeInfo));
  mReceivedEeInfo = false;
  mSeTechMask = 0x00;
  mIsScbrSupported = false;

  mNfcFOnDhHandle = NFA_HANDLE_INVALID;
  mHostListenTechMask =
      NfcConfig::getUnsigned(NAME_HOST_LISTEN_TECH_MASK,
                             NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_F);

  mOffHostListenTechMask = NfcConfig::getUnsigned(
      NAME_OFFHOST_LISTEN_TECH_MASK,
      NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B | NFA_TECHNOLOGY_MASK_F);
#if(NXP_EXTNS != TRUE)
  mDeinitializing = false;
  mEeInfoChanged = false;
#endif
}

RoutingManager::~RoutingManager() {}

bool RoutingManager::initialize(nfc_jni_native_data* native) {
  static const char fn[] = "RoutingManager::initialize()";
  mNativeData = native;
#if(NXP_EXTNS != TRUE)
  mRxDataBuffer.clear();
#endif
  {
    SyncEventGuard guard(mEeRegisterEvent);
    DLOG_IF(INFO, nfc_debug_enabled) << fn << ": try ee register";
    tNFA_STATUS nfaStat = NFA_EeRegister(nfaEeCallback);
    if (nfaStat != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail ee register; error=0x%X", fn,
                                 nfaStat);
      return false;
    }
    mEeRegisterEvent.wait();
  }

#if (NXP_EXTNS == TRUE)
    memset(&gRouteInfo, 0x00, sizeof(RouteInfo_t));

    mHostListnTechMask = NfcConfig::getUnsigned(NAME_HOST_LISTEN_TECH_MASK, 0x03);

    LOG(INFO) << StringPrintf("%s: mHostListnTechMask=0x%X", fn,mHostListnTechMask);

    mFwdFuntnEnable = NfcConfig::getUnsigned(NAME_FORWARD_FUNCTIONALITY_ENABLE, 0x00);
    LOG(INFO) << StringPrintf("%s: mFwdFuntnEnable=0x%X", fn,mFwdFuntnEnable);

    mDefaultTechFPowerstate = NfcConfig::getUnsigned(NAME_DEFAULT_FELICA_CLT_PWR_STATE, 0x3F);

    mDefaultEe = NfcConfig::getUnsigned(NAME_NXP_DEFAULT_SE, 0x01);

    mUiccListnTechMask = NfcConfig::getUnsigned(NAME_UICC_LISTEN_TECH_MASK, 0x07);

    mDefaultFelicaRoute = NfcConfig::getUnsigned(NAME_DEFAULT_FELICA_CLT_ROUTE, 0x00);

    mDefaultOffHostRoute = NfcConfig::getUnsigned(NAME_DEFAULT_OFFHOST_ROUTE, 0x00);

    mDefaultSysCodePowerstate = NfcConfig::getUnsigned(
        NAME_DEFAULT_SYS_CODE_PWR_STATE,
        (PWR_SWTCH_ON_SCRN_UNLCK_MASK | PWR_SWTCH_ON_SCRN_LOCK_MASK |
         PWR_SWTCH_ON_SCRN_OFF_MASK));

    LOG(INFO) << StringPrintf("%s: >>>> mDefaultFelicaRoute=0x%X", fn, mDefaultFelicaRoute);
    LOG(INFO) << StringPrintf("%s: >>>> mDefaultEe=0x%X", fn, mDefaultEe);
    LOG(INFO) << StringPrintf("%s: >>>> mDefaultOffHostRoute=0x%X", fn, mDefaultOffHostRoute);
#endif
  if ((mDefaultOffHostRoute != 0) || (mDefaultFelicaRoute != 0)) {
    // Wait for EE info if needed
    SyncEventGuard guard(mEeInfoEvent);
    if (!mReceivedEeInfo) {
      LOG(INFO) << fn << "Waiting for EE info";
      mEeInfoEvent.wait();
    }
  }
#if (NXP_EXTNS != TRUE)
  mSeTechMask = updateEeTechRouteSetting();
#endif
#if (NXP_EXTNS == TRUE)
  if (mHostListnTechMask) {
#endif
    // Set the host-routing Tech
  tNFA_STATUS nfaStat = NFA_CeSetIsoDepListenTech(
      mHostListenTechMask & (NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B));

    if (nfaStat != NFA_STATUS_OK)
      LOG(ERROR) << StringPrintf("Failed to configure CE IsoDep technologies");

    // Register a wild-card for AIDs routed to the host
    nfaStat = NFA_CeRegisterAidOnDH(NULL, 0, stackCallback);
    if (nfaStat != NFA_STATUS_OK)
      LOG(ERROR) << fn << "Failed to register wildcard AID for DH";
#if (NXP_EXTNS == TRUE)
  }
#endif
  updateDefaultRoute();
#if (NXP_EXTNS != TRUE)
  updateDefaultProtocolRoute();
#endif
  return true;

}

RoutingManager& RoutingManager::getInstance() {
  static RoutingManager manager;
  return manager;
}

void RoutingManager::enableRoutingToHost() {
  static const char fn[] = "RoutingManager::enableRoutingToHost()";
  tNFA_STATUS nfaStat;
  SyncEventGuard guard(mRoutingEvent);

  // Default routing for T3T protocol
  if (!mIsScbrSupported && mDefaultEe == NFC_DH_ID) {
    nfaStat = NFA_EeSetDefaultProtoRouting(NFC_DH_ID, NFA_PROTOCOL_MASK_T3T, 0,
                                           0, 0, 0, 0);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << fn << "Fail to set default proto routing for T3T";
  }

  // Default routing for IsoDep protocol
  tNFA_PROTOCOL_MASK protoMask = NFA_PROTOCOL_MASK_ISO_DEP;
  if (mDefaultIsoDepRoute == NFC_DH_ID) {
    nfaStat = NFA_EeSetDefaultProtoRouting(
        NFC_DH_ID, protoMask, 0, 0, mSecureNfcEnabled ? 0 : protoMask, 0, 0);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << fn << "Fail to set default proto routing for IsoDep";
  }

  // Route Nfc-A to host if we don't have a SE
  tNFA_TECHNOLOGY_MASK techMask = NFA_TECHNOLOGY_MASK_A;
  if ((mHostListenTechMask & NFA_TECHNOLOGY_MASK_A) &&
      (mSeTechMask & NFA_TECHNOLOGY_MASK_A) == 0) {
    nfaStat = NFA_EeSetDefaultTechRouting(
        NFC_DH_ID, techMask, 0, 0, mSecureNfcEnabled ? 0 : techMask,
        mSecureNfcEnabled ? 0 : techMask, mSecureNfcEnabled ? 0 : techMask);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << fn << "Fail to set default tech routing for Nfc-A";
  }

  // Route Nfc-B to host if we don't have a SE
  techMask = NFA_TECHNOLOGY_MASK_B;
  if ((mHostListenTechMask & NFA_TECHNOLOGY_MASK_B) &&
      (mSeTechMask & NFA_TECHNOLOGY_MASK_B) == 0) {
    nfaStat = NFA_EeSetDefaultTechRouting(
        NFC_DH_ID, techMask, 0, 0, mSecureNfcEnabled ? 0 : techMask,
        mSecureNfcEnabled ? 0 : techMask, mSecureNfcEnabled ? 0 : techMask);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << fn << "Fail to set default tech routing for Nfc-B";
  }

  // Route Nfc-F to host if we don't have a SE
  techMask = NFA_TECHNOLOGY_MASK_F;
  if ((mHostListenTechMask & NFA_TECHNOLOGY_MASK_F) &&
      (mSeTechMask & NFA_TECHNOLOGY_MASK_F) == 0) {
    nfaStat = NFA_EeSetDefaultTechRouting(
        NFC_DH_ID, techMask, 0, 0, mSecureNfcEnabled ? 0 : techMask,
        mSecureNfcEnabled ? 0 : techMask, mSecureNfcEnabled ? 0 : techMask);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << fn << "Fail to set default tech routing for Nfc-F";
  }
}

void RoutingManager::disableRoutingToHost() {
  static const char fn[] = "RoutingManager::disableRoutingToHost()";
  tNFA_STATUS nfaStat;
  SyncEventGuard guard(mRoutingEvent);

  // Clear default routing for IsoDep protocol
  if (mDefaultIsoDepRoute == NFC_DH_ID) {
    nfaStat =
        NFA_EeClearDefaultProtoRouting(NFC_DH_ID, NFA_PROTOCOL_MASK_ISO_DEP);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << fn << "Fail to clear default proto routing for IsoDep";
  }

  // Clear default routing for Nfc-A technology if we don't have a SE
  if ((mHostListenTechMask & NFA_TECHNOLOGY_MASK_A) &&
      (mSeTechMask & NFA_TECHNOLOGY_MASK_A) == 0) {
    nfaStat = NFA_EeClearDefaultTechRouting(NFC_DH_ID, NFA_TECHNOLOGY_MASK_A);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << fn << "Fail to clear default tech routing for Nfc-A";
  }

  // Clear default routing for Nfc-B technology if we don't have a SE
  if ((mHostListenTechMask & NFA_TECHNOLOGY_MASK_B) &&
      (mSeTechMask & NFA_TECHNOLOGY_MASK_B) == 0) {
    nfaStat = NFA_EeClearDefaultTechRouting(NFC_DH_ID, NFA_TECHNOLOGY_MASK_B);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << fn << "Fail to clear default tech routing for Nfc-B";
  }

  // Clear default routing for Nfc-F technology if we don't have a SE
  if ((mHostListenTechMask & NFA_TECHNOLOGY_MASK_F) &&
      (mSeTechMask & NFA_TECHNOLOGY_MASK_F) == 0) {
    nfaStat = NFA_EeClearDefaultTechRouting(NFC_DH_ID, NFA_TECHNOLOGY_MASK_F);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << fn << "Fail to clear default tech routing for Nfc-F";
  }

  // Clear default routing for T3T protocol
  if (!mIsScbrSupported && mDefaultEe == NFC_DH_ID) {
    nfaStat = NFA_EeClearDefaultProtoRouting(NFC_DH_ID, NFA_PROTOCOL_MASK_T3T);
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << fn << "Fail to clear default proto routing for T3T";
  }
}

/*******************************************************************************
 **
 ** Function:        isTypeATypeBTechSupportedInEe
 **
 ** Description:     receive eeHandle
 **
 ** Returns:         true  : if EE support protocol type A/B
 **                  false : if EE doesn't protocol type A/B
 **
 *******************************************************************************/
bool RoutingManager::isTypeATypeBTechSupportedInEe(tNFA_HANDLE eeHandle) {
  static const char fn[] = "RoutingManager::isTypeATypeBTechSupportedInEe";
  bool status = false;
  uint8_t mActualNumEe = MAX_NUM_EE;
  tNFA_EE_INFO eeInfo[mActualNumEe];
  memset(&eeInfo, 0, mActualNumEe * sizeof(tNFA_EE_INFO));
  tNFA_STATUS nfaStat = NFA_EeGetInfo(&mActualNumEe, eeInfo);
  DLOG_IF(INFO, nfc_debug_enabled) << fn;
  if (nfaStat != NFA_STATUS_OK) {
    return status;
  }
  for (auto i = 0; i < mActualNumEe; i++) {
    if (eeHandle == eeInfo[i].ee_handle) {
      if (eeInfo[i].la_protocol || eeInfo[i].lb_protocol) {
        status = true;
        break;
      }
    }
  }
  return status;
}

bool RoutingManager::addAidRouting(const uint8_t* aid, uint8_t aidLen,
                                   int route, int aidInfo, int power) {
  static const char fn[] = "RoutingManager::addAidRouting";
  DLOG_IF(INFO, nfc_debug_enabled) << fn << ": enter";
  uint8_t powerState = 0x01;
  #if(NXP_EXTNS == TRUE)
  int seId = SecureElement::getInstance().getEseHandleFromGenericId(route);
  if (seId  == NFA_HANDLE_INVALID)
  {
    return false;
  }
  SyncEventGuard guard(mAidAddRemoveEvent);
  if (!mSecureNfcEnabled) {
    /*masking lower 8 bits as power states will be available only in that
     * region*/
    power &= 0xFF;

    if (route == SecureElement::DH_ID) {
      power &= ~(PWR_SWTCH_OFF_MASK | PWR_BATT_OFF_MASK);
    }
    if (power == 0x00) {
      powerState = (route != SecureElement::DH_ID)
                       ? mOffHostAidRoutingPowerState
                       : HOST_PWR_STATE;
    } else {
      powerState = (route != SecureElement::DH_ID)
                       ? mOffHostAidRoutingPowerState & power
                       : power;
    }
  }
  tNFA_STATUS nfaStat =
      NFA_EeAddAidRouting(seId, aidLen, (uint8_t*)aid, powerState, aidInfo);
  #else

  if (!mSecureNfcEnabled) {
    if (power == 0x00) {
      powerState = (route != 0x00) ? mOffHostAidRoutingPowerState : 0x11;
    } else {
      powerState =
          (route != 0x00) ? mOffHostAidRoutingPowerState & power : power;
    }
  }
  SyncEventGuard guard(mRoutingEvent);
  mAidRoutingConfigured = false;
  tNFA_STATUS nfaStat =
      NFA_EeAddAidRouting(route, aidLen, (uint8_t*)aid, powerState, aidInfo);
   #endif
  if (nfaStat == NFA_STATUS_OK) {
#if(NXP_EXTNS == TRUE)
    DLOG_IF(INFO, nfc_debug_enabled) << fn << ": routed AID";
    mAidAddRemoveEvent.wait();
#else
    mRoutingEvent.wait();
  }
  if (mAidRoutingConfigured) {
    DLOG_IF(INFO, nfc_debug_enabled) << fn << ": routed AID";
#endif
    return true;
  } else {
    LOG(ERROR) << fn << ": failed to route AID";
    return false;
  }
}


bool RoutingManager::removeAidRouting(const uint8_t* aid, uint8_t aidLen) {
  static const char fn[] = "RoutingManager::removeAidRouting";
  DLOG_IF(INFO, nfc_debug_enabled) << fn << ": enter";
#if(NXP_EXTNS == TRUE)
  SyncEventGuard guard(mAidAddRemoveEvent);
#else
  SyncEventGuard guard(mRoutingEvent);
  mAidRoutingConfigured = false;
#endif
  tNFA_STATUS nfaStat = NFA_EeRemoveAidRouting(aidLen, (uint8_t*)aid);
  if (nfaStat == NFA_STATUS_OK) {
#if(NXP_EXTNS != TRUE)
    mRoutingEvent.wait();
  }
  if (mAidRoutingConfigured) {
#endif
    DLOG_IF(INFO, nfc_debug_enabled) << fn << ": removed AID";
#if(NXP_EXTNS == TRUE)
    mAidAddRemoveEvent.wait();
#endif
    return true;
  } else {
    LOG(WARNING) << fn << ": failed to remove AID";
    return false;
  }
}

bool RoutingManager::commitRouting() {
  static const char fn[] = "RoutingManager::commitRouting";
  tNFA_STATUS nfaStat = 0;
  DLOG_IF(INFO, nfc_debug_enabled) << fn;
#if(NXP_EXTNS != TRUE)
  if(mEeInfoChanged) {
    mSeTechMask = updateEeTechRouteSetting();
    mEeInfoChanged = false;
  }
#endif
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

  if (mDefaultOffHostRoute == 0x00 && mDefaultFelicaRoute == 0x00) return;

  tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
  uint8_t actualNumEe = MAX_NUM_EE;
  tNFA_EE_INFO eeInfo[MAX_NUM_EE];
#if(NXP_EXTNS != TRUE)
  mDeinitializing = true;
#endif

  memset(&eeInfo, 0, sizeof(eeInfo));
  if ((nfaStat = NFA_EeGetInfo(&actualNumEe, eeInfo)) != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: fail get info; error=0x%X", fn, nfaStat);
    return;
  }
  if (actualNumEe != 0) {
    for (uint8_t xx = 0; xx < actualNumEe; xx++) {
      if (
#if(NXP_EXTNS != TRUE)
          (eeInfo[xx].num_interface != 0) &&
#endif
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
          LOG(ERROR) << fn << "Failed to set EE inactive";
        }
      }
    }
  } else {
    DLOG_IF(INFO, nfc_debug_enabled) << fn << ": No active EEs found";
  }
}

void RoutingManager::notifyActivated(uint8_t technology) {
  JNIEnv* e = NULL;
  ScopedAttach attach(mNativeData->vm, &e);
  if (e == NULL) {
    LOG(ERROR) << "jni env is null";
    return;
  }

  e->CallVoidMethod(mNativeData->manager,
                    android::gCachedNfcManagerNotifyHostEmuActivated,
                    (int)technology);
  if (e->ExceptionCheck()) {
    e->ExceptionClear();
    LOG(ERROR) << "fail notify";
  }
}

void RoutingManager::notifyDeactivated(uint8_t technology) {
#if (NXP_EXTNS == TRUE)
  SecureElement::getInstance().notifyListenModeState (false);
#endif
  mRxDataBuffer.clear();
  JNIEnv* e = NULL;
  ScopedAttach attach(mNativeData->vm, &e);
  if (e == NULL) {
    LOG(ERROR) << "jni env is null";
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
    LOG(ERROR) << "RoutingManager::handleData: read data fail";
    goto TheEnd;
  }

  {
    JNIEnv* e = NULL;
    ScopedAttach attach(mNativeData->vm, &e);
    if (e == NULL) {
      LOG(ERROR) << "jni env is null";
      goto TheEnd;
    }

    ScopedLocalRef<jobject> dataJavaArray(
        e, e->NewByteArray(mRxDataBuffer.size()));
    if (dataJavaArray.get() == NULL) {
      LOG(ERROR) << "fail allocate array";
      goto TheEnd;
    }

    e->SetByteArrayRegion((jbyteArray)dataJavaArray.get(), 0,
                          mRxDataBuffer.size(), (jbyte*)(&mRxDataBuffer[0]));
    if (e->ExceptionCheck()) {
      e->ExceptionClear();
      LOG(ERROR) << "fail fill array";
      goto TheEnd;
    }

    e->CallVoidMethod(mNativeData->manager,
                      android::gCachedNfcManagerNotifyHostEmuData,
                      (int)technology, dataJavaArray.get());
    if (e->ExceptionCheck()) {
      e->ExceptionClear();
      LOG(ERROR) << "fail notify";
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
#if (NXP_EXTNS == TRUE)
      if (android::isSeRfActive()) {
        android::setSeRfActive(false);
      }
#endif
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

void RoutingManager::updateRoutingTable() {
#if(NXP_EXTNS != TRUE)
  updateEeTechRouteSetting();
  updateDefaultProtocolRoute();
#endif
  updateDefaultRoute();
}

void RoutingManager::updateDefaultProtocolRoute() {
  static const char fn[] = "RoutingManager::updateDefaultProtocolRoute";

  // Default Routing for ISO-DEP
  tNFA_PROTOCOL_MASK protoMask = NFA_PROTOCOL_MASK_ISO_DEP;
  tNFA_STATUS nfaStat;
  if (mDefaultIsoDepRoute != NFC_DH_ID &&
      isTypeATypeBTechSupportedInEe(mDefaultIsoDepRoute |
                                    NFA_HANDLE_GROUP_EE)) {
    nfaStat = NFA_EeClearDefaultProtoRouting(mDefaultIsoDepRoute, protoMask);
    nfaStat = NFA_EeSetDefaultProtoRouting(
        mDefaultIsoDepRoute, protoMask, mSecureNfcEnabled ? 0 : protoMask, 0,
        mSecureNfcEnabled ? 0 : protoMask, mSecureNfcEnabled ? 0 : protoMask,
        mSecureNfcEnabled ? 0 : protoMask);
  } else {
    nfaStat = NFA_EeClearDefaultProtoRouting(NFC_DH_ID, protoMask);
    nfaStat = NFA_EeSetDefaultProtoRouting(
        NFC_DH_ID, protoMask, 0, 0, mSecureNfcEnabled ? 0 : protoMask, 0, 0);
  }
  if (nfaStat == NFA_STATUS_OK)
    DLOG_IF(INFO, nfc_debug_enabled)
        << fn << ": Succeed to register default ISO-DEP route";
  else
    LOG(ERROR) << fn << ": failed to register default ISO-DEP route";

  // Default routing for T3T protocol
  if (!mIsScbrSupported) {
    SyncEventGuard guard(mRoutingEvent);
    tNFA_PROTOCOL_MASK protoMask = NFA_PROTOCOL_MASK_T3T;
    if (mDefaultEe == NFC_DH_ID) {
      nfaStat =
          NFA_EeSetDefaultProtoRouting(NFC_DH_ID, protoMask, 0, 0, 0, 0, 0);
    } else {
      nfaStat = NFA_EeClearDefaultProtoRouting(mDefaultEe, protoMask);
      nfaStat = NFA_EeSetDefaultProtoRouting(
          mDefaultEe, protoMask, 0, 0, mSecureNfcEnabled ? 0 : protoMask,
          mSecureNfcEnabled ? 0 : protoMask, mSecureNfcEnabled ? 0 : protoMask);
    }
    if (nfaStat == NFA_STATUS_OK)
      mRoutingEvent.wait();
    else
      LOG(ERROR) << fn << "Fail to set default proto routing for T3T";
  }
}

void RoutingManager::updateDefaultRoute() {
  static const char fn[] = "RoutingManager::updateDefaultRoute";
  if (NFC_GetNCIVersion() != NCI_VERSION_2_0) return;

#if (NXP_EXTNS == TRUE)
  tNFA_HANDLE routeLoc = getNfaHandle(mDefaultSysCodeRoute);

  if (mDefaultSysCodeRoute == SecureElement::DH_ID) {
    mDefaultSysCodePowerstate &= ~(PWR_SWTCH_OFF_MASK | PWR_BATT_OFF_MASK);
  }
#endif

  // Register System Code for routing
  SyncEventGuard guard(mRoutingEvent);
#if (NXP_EXTNS == TRUE)
  tNFA_STATUS nfaStat = NFA_EeAddSystemCodeRouting(
      mDefaultSysCode, routeLoc,
      mSecureNfcEnabled ? (mDefaultSysCodePowerstate & 0x01) : mDefaultSysCodePowerstate);
#else
  tNFA_STATUS nfaStat = NFA_EeAddSystemCodeRouting(
      mDefaultSysCode, mDefaultSysCodeRoute,
      mSecureNfcEnabled ? 0x01 : mDefaultSysCodePowerstate);
#endif

  if (nfaStat == NFA_STATUS_NOT_SUPPORTED) {
    mIsScbrSupported = false;
    LOG(ERROR) << fn << ": SCBR not supported";
  } else if (nfaStat == NFA_STATUS_OK) {
    mIsScbrSupported = true;
    mRoutingEvent.wait();
    DLOG_IF(INFO, nfc_debug_enabled)
        << fn << ": Succeed to register system code";
  } else {
    LOG(ERROR) << fn << ": Fail to register system code";
    //still support SCBR routing for other NFCEEs
    mIsScbrSupported = true;
  }
#if (NXP_EXTNS != TRUE)
  // Register zero lengthy Aid for default Aid Routing
  if (mDefaultEe != mDefaultIsoDepRoute) {
    if ((mDefaultEe != NFC_DH_ID) &&
        (!isTypeATypeBTechSupportedInEe(mDefaultEe | NFA_HANDLE_GROUP_EE))) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << fn << ": mDefaultEE Doesn't support either Tech A/B. Returning...";
      return;
    }
    uint8_t powerState = 0x01;
    if (!mSecureNfcEnabled)
      powerState = (mDefaultEe != 0x00) ? mOffHostAidRoutingPowerState : 0x11;
    nfaStat = NFA_EeAddAidRouting(mDefaultEe, 0, NULL, powerState,
                                  AID_ROUTE_QUAL_PREFIX);
    if (nfaStat == NFA_STATUS_OK)
      DLOG_IF(INFO, nfc_debug_enabled)
          << fn << ": Succeed to register zero length AID";
    else
      LOG(ERROR) << fn << ": failed to register zero length AID";
  }
#endif
}

tNFA_TECHNOLOGY_MASK RoutingManager::updateEeTechRouteSetting() {
  static const char fn[] = "RoutingManager::updateEeTechRouteSetting";
  tNFA_TECHNOLOGY_MASK allSeTechMask = 0x00;

#if(NXP_EXTNS == TRUE)
  int handleDefaultOffHost = SecureElement::getInstance().getEseHandleFromGenericId(mDefaultOffHostRoute);
  int handleDefaultFelicaRoute = SecureElement::getInstance().getEseHandleFromGenericId(mDefaultFelicaRoute);
#endif

  if (mDefaultOffHostRoute == 0 && mDefaultFelicaRoute == 0)
    return allSeTechMask;

  DLOG_IF(INFO, nfc_debug_enabled)
      << fn << ": Number of EE is " << (int)mEeInfo.num_ee;

  tNFA_STATUS nfaStat;
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

    if ((mDefaultOffHostRoute != 0) &&
#if(NXP_EXTNS != TRUE)
        (eeHandle == (mDefaultOffHostRoute | NFA_HANDLE_GROUP_EE))) {
#else
        (eeHandle == handleDefaultOffHost)) {
#endif
      if (mEeInfo.ee_disc_info[i].la_protocol != 0)
        seTechMask |= NFA_TECHNOLOGY_MASK_A;
      if (mEeInfo.ee_disc_info[i].lb_protocol != 0)
        seTechMask |= NFA_TECHNOLOGY_MASK_B;
    }
    if ((mDefaultFelicaRoute != 0) &&
#if(NXP_EXTNS != TRUE)
        (eeHandle == (mDefaultFelicaRoute | NFA_HANDLE_GROUP_EE))) {
#else
        (eeHandle == handleDefaultFelicaRoute)) {
#endif
      if (mEeInfo.ee_disc_info[i].lf_protocol != 0)
        seTechMask |= NFA_TECHNOLOGY_MASK_F;
    }

    // If OFFHOST_LISTEN_TECH_MASK exists,
    // filter out the unspecified technologies
    seTechMask &= mOffHostListenTechMask;

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: seTechMask[%u]=0x%02x", fn, i, seTechMask);

    if (seTechMask != 0x00) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "Configuring tech mask 0x%02x on EE 0x%04x", seTechMask, eeHandle);

      nfaStat = NFA_CeConfigureUiccListenTech(eeHandle, seTechMask);
      if (nfaStat != NFA_STATUS_OK)
        LOG(ERROR) << fn << "Failed to configure UICC listen technologies.";

      // clear previous before setting new power state
      nfaStat = NFA_EeClearDefaultTechRouting(eeHandle, seTechMask);
      if (nfaStat != NFA_STATUS_OK)
        LOG(ERROR) << fn << "Failed to clear EE technology routing.";

      nfaStat = NFA_EeSetDefaultTechRouting(
          eeHandle, seTechMask, mSecureNfcEnabled ? 0 : seTechMask, 0,
          mSecureNfcEnabled ? 0 : seTechMask,
          mSecureNfcEnabled ? 0 : seTechMask,
          mSecureNfcEnabled ? 0 : seTechMask);
      if (nfaStat != NFA_STATUS_OK)
        LOG(ERROR) << fn << "Failed to configure UICC technology routing.";

      allSeTechMask |= seTechMask;
    }
  }

  // Clear DH technology route on NFC-A
  if ((mHostListenTechMask & NFA_TECHNOLOGY_MASK_A) &&
      (allSeTechMask & NFA_TECHNOLOGY_MASK_A) != 0) {
    nfaStat = NFA_EeClearDefaultTechRouting(NFC_DH_ID, NFA_TECHNOLOGY_MASK_A);
    if (nfaStat != NFA_STATUS_OK)
      LOG(ERROR) << "Failed to clear DH technology routing on NFC-A.";
  }

  // Clear DH technology route on NFC-B
  if ((mHostListenTechMask & NFA_TECHNOLOGY_MASK_B) &&
      (allSeTechMask & NFA_TECHNOLOGY_MASK_B) != 0) {
    nfaStat = NFA_EeClearDefaultTechRouting(NFC_DH_ID, NFA_TECHNOLOGY_MASK_B);
    if (nfaStat != NFA_STATUS_OK)
      LOG(ERROR) << "Failed to clear DH technology routing on NFC-B.";
  }

  // Clear DH technology route on NFC-F
  if ((mHostListenTechMask & NFA_TECHNOLOGY_MASK_F) &&
      (allSeTechMask & NFA_TECHNOLOGY_MASK_F) != 0) {
    nfaStat = NFA_EeClearDefaultTechRouting(NFC_DH_ID, NFA_TECHNOLOGY_MASK_F);
    if (nfaStat != NFA_STATUS_OK)
      LOG(ERROR) << "Failed to clear DH technology routing on NFC-F.";
  }
  return allSeTechMask;
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
  if (!eventData) {
    LOG(ERROR) << "eventData is null";
    return;
  }
  routingManager.mCbEventData = *eventData;
  SecureElement& se = SecureElement::getInstance();
#else
  if (eventData) routingManager.mCbEventData = *eventData;
#endif

  switch (event) {
    case NFA_EE_REGISTER_EVT: {
      SyncEventGuard guard(routingManager.mEeRegisterEvent);
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_REGISTER_EVT; status=%u", fn, eventData->ee_register);
      routingManager.mEeRegisterEvent.notifyOne();
    } break;

    case NFA_EE_DEREGISTER_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_DEREGISTER_EVT; status=0x%X", fn, eventData->status);
      routingManager.mReceivedEeInfo = false;
#if(NXP_EXTNS != TRUE)
      routingManager.mDeinitializing = false;
#endif
    } break;

    case NFA_EE_MODE_SET_EVT: {
      SyncEventGuard guard(routingManager.mEeSetModeEvent);
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_MODE_SET_EVT; status: 0x%04X  handle: 0x%04X  ", fn,
          eventData->mode_set.status, eventData->mode_set.ee_handle);
      routingManager.mEeSetModeEvent.notifyOne();
#if (NXP_EXTNS == TRUE)
      se.mModeSetNtfstatus = eventData->mode_set.status;
      se.notifyModeSet(eventData->mode_set.ee_handle, !(eventData->mode_set.status),eventData->mode_set.ee_status );
#endif
    } break;
#if (NXP_EXTNS == TRUE)
    case NFA_EE_PWR_AND_LINK_CTRL_EVT:
    {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: NFA_EE_PWR_AND_LINK_CTRL_EVT; status: 0x%04X ", fn,
      eventData->status);
      se.mPwrCmdstatus = eventData->status;
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

    case NFA_EE_CLEAR_TECH_CFG_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_CLEAR_TECH_CFG_EVT; status=0x%X", fn, eventData->status);
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
    } break;

    case NFA_EE_SET_PROTO_CFG_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_SET_PROTO_CFG_EVT; status=0x%X", fn, eventData->status);
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
    } break;

    case NFA_EE_CLEAR_PROTO_CFG_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_CLEAR_PROTO_CFG_EVT; status=0x%X", fn, eventData->status);
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
#if (NXP_EXTNS == TRUE)
      {
#endif
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_EE_ACTION_EVT; h=0x%X; unknown trigger (0x%X)", fn,
            action.ee_handle, action.trigger);
#if (NXP_EXTNS == TRUE)
        NativeJniExtns::getInstance().notifyNfcEvent("updateNfceeActionNtf",
                                                     (void*)&action);
      }
#endif
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
#else
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mAidRoutingConfigured =
          (eventData->status == NFA_STATUS_OK);
      routingManager.mRoutingEvent.notifyOne();
#endif
    } break;

    case NFA_EE_ADD_SYSCODE_EVT: {
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_ADD_SYSCODE_EVT  status=%u", fn, eventData->status);
    } break;

    case NFA_EE_REMOVE_SYSCODE_EVT: {
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_REMOVE_SYSCODE_EVT  status=%u", fn, eventData->status);
    } break;

    case NFA_EE_REMOVE_AID_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_EE_REMOVE_AID_EVT  status=%u", fn, eventData->status);
#if(NXP_EXTNS == TRUE)
      SyncEventGuard guard(routingManager.mAidAddRemoveEvent);
      routingManager.mAidAddRemoveEvent.notifyOne();
#else
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mAidRoutingConfigured =
          (eventData->status == NFA_STATUS_OK);
      routingManager.mRoutingEvent.notifyOne();
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
      << fn << ": Start to register NFC-F system on DH";

  if (t3tIdLen != (2 + NCI_RF_F_UID_LEN + NCI_T3T_PMM_LEN)) {
    LOG(ERROR) << fn << ": Invalid length of T3T Identifier";
    return NFA_HANDLE_INVALID;
  }

  mNfcFOnDhHandle = NFA_HANDLE_INVALID;

  uint16_t systemCode;
  uint8_t nfcid2[NCI_RF_F_UID_LEN];
  uint8_t t3tPmm[NCI_T3T_PMM_LEN];

  systemCode = (((int)t3tId[0] << 8) | ((int)t3tId[1] << 0));
  memcpy(nfcid2, t3tId + 2, NCI_RF_F_UID_LEN);
  memcpy(t3tPmm, t3tId + 10, NCI_T3T_PMM_LEN);
  {
    SyncEventGuard guard(mRoutingEvent);
    tNFA_STATUS nfaStat = NFA_CeRegisterFelicaSystemCodeOnDH(
        systemCode, nfcid2, t3tPmm, nfcFCeCallback);
    if (nfaStat == NFA_STATUS_OK) {
      mRoutingEvent.wait();
    } else {
      LOG(ERROR) << fn << ": Fail to register NFC-F system on DH";
      return NFA_HANDLE_INVALID;
    }
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << fn << ": Succeed to register NFC-F system on DH";

  // Register System Code for routing
  if (mIsScbrSupported) {
    SyncEventGuard guard(mRoutingEvent);
    tNFA_STATUS nfaStat = NFA_EeAddSystemCodeRouting(systemCode, NCI_DH_ID,
                                                     SYS_CODE_PWR_STATE_HOST);
    if (nfaStat == NFA_STATUS_OK) {
      mRoutingEvent.wait();
    }
    if ((nfaStat != NFA_STATUS_OK) || (mCbEventData.status != NFA_STATUS_OK)) {
      LOG(ERROR) << StringPrintf("%s: Fail to register system code on DH", fn);
      return NFA_HANDLE_INVALID;
    }
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Succeed to register system code on DH", fn);
    // add handle and system code pair to the map
    mMapScbrHandle.emplace(mNfcFOnDhHandle, systemCode);
  } else {
    LOG(ERROR) << StringPrintf("%s: SCBR Not supported", fn);
  }

  return mNfcFOnDhHandle;
}

void RoutingManager::deregisterT3tIdentifier(int handle) {
  static const char fn[] = "RoutingManager::deregisterT3tIdentifier";

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Start to deregister NFC-F system on DH", fn);
  {
    SyncEventGuard guard(mRoutingEvent);
    tNFA_STATUS nfaStat = NFA_CeDeregisterFelicaSystemCodeOnDH(handle);
    if (nfaStat == NFA_STATUS_OK) {
      mRoutingEvent.wait();
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: Succeeded in deregistering NFC-F system on DH", fn);
    } else {
      LOG(ERROR) << StringPrintf("%s: Fail to deregister NFC-F system on DH",
                                 fn);
    }
  }
  if (mIsScbrSupported) {
    map<int, uint16_t>::iterator it = mMapScbrHandle.find(handle);
    // find system code for given handle
    if (it != mMapScbrHandle.end()) {
      uint16_t systemCode = it->second;
      mMapScbrHandle.erase(handle);
      if (systemCode != 0) {
        SyncEventGuard guard(mRoutingEvent);
        tNFA_STATUS nfaStat = NFA_EeRemoveSystemCodeRouting(systemCode);
        if (nfaStat == NFA_STATUS_OK) {
          mRoutingEvent.wait();
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
              "%s: Succeeded in deregistering system Code on DH", fn);
        } else {
          LOG(ERROR) << StringPrintf("%s: Fail to deregister system Code on DH",
                                     fn);
        }
      }
    }
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
          << StringPrintf("%s: registered event notified", fn);
      routingManager.mNfcFOnDhHandle = eventData->ce_registered.handle;
      SyncEventGuard guard(routingManager.mRoutingEvent);
      routingManager.mRoutingEvent.notifyOne();
    } break;
    case NFA_CE_DEREGISTERED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: deregistered event notified", fn);
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

bool RoutingManager::setNfcSecure(bool enable) {
  mSecureNfcEnabled = enable;
  DLOG_IF(INFO, true) << "setNfcSecure NfcService " << enable;
  return true;
}

void RoutingManager::deinitialize() {
  onNfccShutdown();
  NFA_EeDeregister(nfaEeCallback);
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
  return getInstance().mDefaultOffHostRoute;
}

jbyteArray
RoutingManager::com_android_nfc_cardemulation_doGetOffHostUiccDestination(
    JNIEnv* e) {
  std::vector<uint8_t> uicc = getInstance().mOffHostRouteUicc;
  if (uicc.size() == 0) {
    return NULL;
  }
  CHECK(e);
  jbyteArray uiccJavaArray = e->NewByteArray(uicc.size());
  CHECK(uiccJavaArray);
  e->SetByteArrayRegion(uiccJavaArray, 0, uicc.size(), (jbyte*)&uicc[0]);
  return uiccJavaArray;
}

jbyteArray
RoutingManager::com_android_nfc_cardemulation_doGetOffHostEseDestination(
    JNIEnv* e) {
  std::vector<uint8_t> ese = getInstance().mOffHostRouteEse;
  if (ese.size() == 0) {
    return NULL;
  }
  CHECK(e);
  jbyteArray eseJavaArray = e->NewByteArray(ese.size());
  CHECK(eseJavaArray);
  e->SetByteArrayRegion(eseJavaArray, 0, ese.size(), (jbyte*)&ese[0]);
  return eseJavaArray;
}

int RoutingManager::com_android_nfc_cardemulation_doGetAidMatchingMode(
    JNIEnv*) {
  return getInstance().mAidMatchingMode;
}

int RoutingManager::
    com_android_nfc_cardemulation_doGetDefaultIsoDepRouteDestination(JNIEnv*) {
  return getInstance().mDefaultIsoDepRoute;
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

void RoutingManager::registerProtoRouteEnrty(tNFA_HANDLE     ee_handle,
                                         tNFA_PROTOCOL_MASK  protocols_switch_on,
                                         tNFA_PROTOCOL_MASK  protocols_switch_off,
                                         tNFA_PROTOCOL_MASK  protocols_battery_off,
                                         tNFA_PROTOCOL_MASK  protocols_screen_lock,
                                         tNFA_PROTOCOL_MASK  protocols_screen_off,
                                         tNFA_PROTOCOL_MASK  protocols_screen_off_lock
                                         )
{
  tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
  {
    SyncEventGuard guard(mRoutingEvent);
    nfaStat = NFA_EeSetDefaultProtoRouting(
        ee_handle, protocols_switch_on,
        mSecureNfcEnabled ? 0 :protocols_switch_off,
        mSecureNfcEnabled ? 0 :protocols_battery_off,
        mSecureNfcEnabled ? 0 :protocols_screen_lock,
        mSecureNfcEnabled ? 0 :protocols_screen_off,
        mSecureNfcEnabled ? 0 :protocols_screen_off_lock);
    if (nfaStat == NFA_STATUS_OK) {
      mRoutingEvent.wait();
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("proto routing SUCCESS");
    } else {
      DLOG_IF(ERROR, nfc_debug_enabled)
          << StringPrintf("Fail to set proto tech routing");
    }
  }
}
/*******************************************************************************
 **
 ** Function:        configureEeRegister
 **
 ** Description:     EE register & de-register can be done.
 **
 ** Returns:         None
 **
 *******************************************************************************/
void RoutingManager::configureEeRegister(bool eeReg)
{
    static const char fn [] = "RoutingManager::configureEeRegister";
    tNFA_STATUS nfaStat;
    if(eeReg)
    {
        SyncEventGuard guard (mEeRegisterEvent);
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: try ee register", fn);
        nfaStat = NFA_EeRegister (nfaEeCallback);
        if (nfaStat != NFA_STATUS_OK)
        {
            DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf("%s: fail ee register; error=0x%X", fn, nfaStat);
        }
        mEeRegisterEvent.wait ();
    } else {
         NFA_EeDeregister (nfaEeCallback);
    }
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
    else if (isDynamicUiccEnabled &&
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

bool RoutingManager::setRoutingEntry(int type, int value, int route, int power)
{
    static const char fn [] = "RoutingManager::setRoutingEntry";
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter, >>>>>>>> type:0x%x value =0x%x route:%x power:0x%x", fn, type, value ,route, power);
    unsigned long max_tech_mask = 0x03;
    unsigned long uiccListenTech = 0;

    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    tNFA_HANDLE ee_handle = NFA_HANDLE_INVALID;
    uint8_t switch_on_mask = 0x00;
    uint8_t switch_off_mask   = 0x00;
    uint8_t battery_off_mask = 0x00;
    uint8_t screen_lock_mask = 0x00;
    uint8_t screen_off_mask = 0x00;
    uint8_t screen_off_lock_mask = 0x00;
    uint8_t protocol_mask = 0x00;
    ee_handle = getNfaHandle((uint16_t)route);
    if(ee_handle == NFA_HANDLE_INVALID )
    {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter, handle:%x invalid", fn, ee_handle);
        return nfaStat;
    }

    ee_handle = checkAndUpdateAltRoute(route);
    /*masking lower 8 bits as power states will be available only in that
     * region*/
    power &= 0xFF;

    if ((ee_handle == ROUTE_LOC_HOST_ID) &&
        (NFA_SET_PROTOCOL_ROUTING == type)) {
      power &= ~(PWR_SWTCH_OFF_MASK | PWR_BATT_OFF_MASK);
    }

    max_tech_mask = SecureElement::getInstance().getSETechnology(ee_handle);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter,max_tech_mask :%lx", fn, max_tech_mask);
    if(NFA_SET_TECHNOLOGY_ROUTING == type)
    {
      /*  Masking with available SE Technologies */
      value &= max_tech_mask;
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: enter >>>> max_tech_mask :%lx value :0x%x", fn,
                          max_tech_mask, value);
      switch_on_mask = (power & 0x01) ? value : 0;
      switch_off_mask = (power & 0x02) ? value : 0;
      battery_off_mask = (power & 0x04) ? value : 0;
      screen_off_mask = (power & 0x08) ? value : 0;
      screen_lock_mask = (power & 0x10) ? value : 0;
      screen_off_lock_mask = (power & 0x20) ? value : 0;

      if ((max_tech_mask != 0x01) && (max_tech_mask == 0x02) &&
          value)  // type B only
      {
        switch_on_mask &= ~NFA_TECHNOLOGY_MASK_A;
        switch_off_mask &= ~NFA_TECHNOLOGY_MASK_A;
        battery_off_mask &= ~NFA_TECHNOLOGY_MASK_A;
        screen_off_mask &= ~NFA_TECHNOLOGY_MASK_A;
        screen_lock_mask &= ~NFA_TECHNOLOGY_MASK_A;
        screen_off_lock_mask &= ~NFA_TECHNOLOGY_MASK_A;
      } else if ((max_tech_mask == 0x01) && (max_tech_mask != 0x02) &&
                 value)  // type A only
      {
        switch_on_mask &= ~NFA_TECHNOLOGY_MASK_B;
        switch_off_mask &= ~NFA_TECHNOLOGY_MASK_B;
        battery_off_mask &= ~NFA_TECHNOLOGY_MASK_B;
        screen_off_mask &= ~NFA_TECHNOLOGY_MASK_B;
        screen_lock_mask &= ~NFA_TECHNOLOGY_MASK_B;
        screen_off_lock_mask &= ~NFA_TECHNOLOGY_MASK_B;
      }

        if ((mHostListnTechMask) && (mFwdFuntnEnable)) {
          if ((max_tech_mask != 0x01) && (max_tech_mask == 0x02) && value) {
            {
              SyncEventGuard guard(mRoutingEvent);
              if (mSecureNfcEnabled) {
                nfaStat = NFA_EeSetDefaultTechRouting(
                    0x400, NFA_TECHNOLOGY_MASK_A, 0, 0, 0, 0, 0);
              } else {
                nfaStat = NFA_EeSetDefaultTechRouting(
                    0x400, (mFwdFuntnEnable & 0x01) ? NFA_TECHNOLOGY_MASK_A : 0,
                    0, 0, (mFwdFuntnEnable & 0x10) ? NFA_TECHNOLOGY_MASK_A : 0,
                    (mFwdFuntnEnable & 0x08) ? NFA_TECHNOLOGY_MASK_A : 0,
                    (mFwdFuntnEnable & 0x20) ? NFA_TECHNOLOGY_MASK_A : 0);
              }
              if (nfaStat == NFA_STATUS_OK)
                mRoutingEvent.wait();
              else {
                DLOG_IF(ERROR, nfc_debug_enabled)
                    << StringPrintf("Fail to set tech routing");
              }
            }
          } else if ((max_tech_mask == 0x01) && (max_tech_mask != 0x02) &&
                     value) {
            {
              SyncEventGuard guard(mRoutingEvent);
              if (mSecureNfcEnabled) {
                nfaStat = NFA_EeSetDefaultTechRouting(
                    0x400, NFA_TECHNOLOGY_MASK_B, 0, 0, 0, 0, 0);
              } else {
                nfaStat = NFA_EeSetDefaultTechRouting(
                    0x400, (mFwdFuntnEnable & 0x01) ? NFA_TECHNOLOGY_MASK_B : 0,
                    0, 0, (mFwdFuntnEnable & 0x10) ? NFA_TECHNOLOGY_MASK_B : 0,
                    (mFwdFuntnEnable & 0x08) ? NFA_TECHNOLOGY_MASK_B : 0,
                    (mFwdFuntnEnable & 0x20) ? NFA_TECHNOLOGY_MASK_B : 0);
              }
              if (nfaStat == NFA_STATUS_OK)
                mRoutingEvent.wait();
              else {
                DLOG_IF(ERROR, nfc_debug_enabled)
                    << StringPrintf("Fail to set tech routing");
              }
            }
          }
        }
        {
            SyncEventGuard guard (mRoutingEvent);
            nfaStat = NFA_EeSetDefaultTechRouting (ee_handle, switch_on_mask,
                                                   mSecureNfcEnabled ? 0 : switch_off_mask,
                                                   mSecureNfcEnabled ? 0 : battery_off_mask,
                                                   mSecureNfcEnabled ? 0 : screen_lock_mask,
                                                   mSecureNfcEnabled ? 0 : screen_off_mask,
                                                   mSecureNfcEnabled ? 0 : screen_off_lock_mask);
            if(nfaStat == NFA_STATUS_OK){
                mRoutingEvent.wait ();
                DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("tech routing SUCCESS");
            }
            else{
                DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf("Fail to set default tech routing");
            }
        }
    }else if(NFA_SET_PROTOCOL_ROUTING == type)
    {
        value &= ~(0xF0);
        while(value)
        {
            if( value & 0x01)
            {
                protocol_mask = NFA_PROTOCOL_MASK_ISO_DEP;
                value &= ~(0x01);
            }
            else if( value & 0x02)
            {
                protocol_mask = NFA_PROTOCOL_MASK_NFC_DEP;
                value &= ~(0x02);
            }
            else if( value & 0x04)
            {
                protocol_mask = NFA_PROTOCOL_MASK_T3T;
                value &= ~(0x04);
            }
            else if( value & 0x08)
            {
                protocol_mask = NFC_PROTOCOL_MASK_ISO7816;
                value &= ~(0x08);
            }

            /*if NFCEE doesn't support tech A/B don't configure ISO-DEP/ISO7816 proto
             * route */
            if ((protocol_mask &
                 (NFA_PROTOCOL_MASK_ISO_DEP | NFC_PROTOCOL_MASK_ISO7816)) &&
                (ee_handle != NFA_EE_HANDLE_DH) && ((max_tech_mask & 0x03) == 0)) {
              DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                  "%s: Proto Entry rejected. ee_handle 0x%x doesn't support proto "
                  "mask 0x%x.",
                  fn, ee_handle, protocol_mask);
              return nfaStat;
            }

            if(protocol_mask)
            {
                switch_on_mask     = (power & 0x01) ? protocol_mask : 0;
                switch_off_mask    = (power & 0x02) ? protocol_mask : 0;
                battery_off_mask   = (power & 0x04) ? protocol_mask : 0;
                screen_lock_mask   = (power & 0x10) ? protocol_mask : 0;
                screen_off_mask    = (power & 0x08) ? protocol_mask : 0;
                screen_off_lock_mask = (power & 0x20) ? protocol_mask : 0;
                DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter >>>> ee_handle:%x %x %x %x %x %x %x", fn, ee_handle,
                                                                    switch_on_mask,
                                                                    switch_off_mask,
                                                                    battery_off_mask,
                                                                    screen_lock_mask,
                                                                    screen_off_mask,
                                                                    screen_off_lock_mask);

                registerProtoRouteEnrty(ee_handle, switch_on_mask, switch_off_mask,
                    battery_off_mask, screen_lock_mask, screen_off_mask, screen_off_lock_mask);
                protocol_mask = 0;
            }
        }
    }

    uiccListenTech = NfcConfig::getUnsigned(NAME_UICC_LISTEN_TECH_MASK, 0x07);
    if((ee_handle != NFA_HANDLE_INVALID)  &&  (0 != uiccListenTech))
    {
         {
               //SyncEventGuard guard (SecureElement::getInstance().mUiccListenEvent);
               nfaStat = NFA_CeConfigureUiccListenTech (ee_handle, (uiccListenTech & 0x07));
               if(nfaStat == NFA_STATUS_OK)
               {
                     //SecureElement::getInstance().mUiccListenEvent.wait ();
               }
               else
                     DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf("fail to start UICC listen");
         }
    }
    return nfaStat;
}

bool RoutingManager::clearAidTable ()
{
    static const char fn [] = "RoutingManager::clearAidTable";
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", fn);
    SyncEventGuard guard(RoutingManager::getInstance().mAidAddRemoveEvent);
    tNFA_STATUS nfaStat = NFA_EeRemoveAidRouting(NFA_REMOVE_ALL_AID_LEN, (uint8_t*) NFA_REMOVE_ALL_AID);
    if (nfaStat == NFA_STATUS_OK)
    {
        RoutingManager::getInstance().mAidAddRemoveEvent.wait();
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: removed AID", fn);
        return true;
    } else
    {
        DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf("%s: failed to remove AID", fn);
        return false;
    }
}

bool RoutingManager::clearRoutingEntry(int type)
{
    DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: Enter . Clear Routing Type = %d", __func__, type);
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    tNFA_HANDLE ee_handleList[nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED];
    uint8_t i, count;
    SyncEventGuard guard(mRoutingEvent);
    SecureElement::getInstance().getEeHandleList(ee_handleList, &count);
    if (count > nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED) {
      count = nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED;
      DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf(
              "Count is more than SecureElement::MAX_NUM_EE,Forcing to "
              "SecureElement::MAX_NUM_EE");
    }

    if(NFA_SET_TECHNOLOGY_ROUTING & type)
    {
      for (i = 0; i < count; i++) {
        nfaStat = NFA_EeClearDefaultTechRouting(
                ee_handleList[i], (NFA_TECHNOLOGY_MASK_A |
                NFA_TECHNOLOGY_MASK_B | NFA_TECHNOLOGY_MASK_F));
        if (nfaStat == NFA_STATUS_OK) {
          mRoutingEvent.wait();
        }
      }
      nfaStat = NFA_EeClearDefaultTechRouting(
              NFA_EE_HANDLE_DH, (NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B |
              NFA_TECHNOLOGY_MASK_F));
      if (nfaStat == NFA_STATUS_OK) {
        mRoutingEvent.wait();
      }
    }
    if(NFA_SET_PROTOCOL_ROUTING & type)
    {
      for (i = 0; i < count; i++) {
        nfaStat = NFA_EeClearDefaultProtoRouting( ee_handleList[i],
                (NFA_PROTOCOL_MASK_ISO_DEP | NFC_PROTOCOL_MASK_ISO7816));
        if (nfaStat == NFA_STATUS_OK) {
          mRoutingEvent.wait();
        }
      }
      nfaStat = NFA_EeClearDefaultProtoRouting( NFA_EE_HANDLE_DH,
              (NFA_PROTOCOL_MASK_ISO_DEP | NFC_PROTOCOL_MASK_ISO7816));
      if (nfaStat == NFA_STATUS_OK) {
        mRoutingEvent.wait();
      }
    }

    if (NFA_SET_AID_ROUTING & type)
    {
        clearAidTable();
    }
    return ((nfaStat == NFA_STATUS_OK)? true: false);
}

/*
 * In NCI2.0 Protocol 7816 routing is replaced with empty AID
 * Routing entry Format :
 *  Type   = [0x12]
 *  Length = 2 [0x02]
 *  Value  = [Route_loc, Power_state]
 * */
void RoutingManager::setEmptyAidEntry(int routeAndPowerState) {

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter",__func__);
    /* uint32_t routeAndPowerState = (uint16_t)routeLoc : (uint16_t)power state */
    uint16_t routeLoc = ((routeAndPowerState >> 8) & 0xFF);
    uint8_t power = (routeAndPowerState & 0xFF);
    int max_tech_mask = 0;
    if (routeLoc  == NFA_HANDLE_INVALID)
    {
        LOG(ERROR) << StringPrintf("%s: Invalid routeLoc. Return.", __func__);
        return;
    }

    routeLoc = getNfaHandle(routeLoc);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: route %x",__func__,routeLoc);
    max_tech_mask = SecureElement::getInstance().getSETechnology(routeLoc);
    /* If Route Location Doesn't support Tech A / Tech B, Don't add empty AID route*/
    if ((routeLoc != ROUTE_LOC_HOST_ID) && ((max_tech_mask & 0x03) == 0)) {
      return;
    }

    if(routeLoc == ROUTE_LOC_HOST_ID) {
      power &= ~(PWR_SWTCH_OFF_MASK | PWR_BATT_OFF_MASK);
    }

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: power %x",__func__,power);
    if(power){
      tNFA_STATUS nfaStat = NFA_EeAddAidRouting(
          routeLoc, 0, NULL, mSecureNfcEnabled ? 0x01 : power,
          AID_ROUTE_QUAL_PREFIX);
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: Status :0x%2x", __func__, nfaStat);
    }else{
        LOG(ERROR) << StringPrintf("%s:Invalid Power State" ,__func__);
    }
}

/*******************************************************************************
 **
 ** Function:        checkAndUpdateAltRoute
 **
 ** Description:     checks input Nfcee is active, if not updates alternate
 **                  route based on config option
 **
 ** Returns:         route location
 **
 *******************************************************************************/
tNFA_HANDLE RoutingManager::checkAndUpdateAltRoute(int& routeLoc) {
  tNFA_HANDLE ActDevHandle = NFA_HANDLE_INVALID;
  bool isSeActive = false;
  unsigned long fallBackOption = ROUTE_DISABLE;

  if (routeLoc != SecureElement::DH_ID) {
    isSeActive = isNfceeActive(routeLoc, ActDevHandle);

    if (!isSeActive) {
      fallBackOption =
          NfcConfig::getUnsigned(NAME_CHECK_DEFAULT_PROTO_SE_ID, ROUTE_ESE);
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: fallBackOption - 0x%lX  routeLoc = 0x%X",
            __func__, fallBackOption, routeLoc);
    }
    if ((fallBackOption == ROUTE_ESE) && ((routeLoc == ROUTE_LOC_UICC1_ID_IDX)
            || (routeLoc == ROUTE_LOC_UICC2_ID_IDX)
            || (routeLoc == SecureElement::EUICC_ID)
            || (routeLoc == SecureElement::EUICC2_ID))) {
      DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("Default route not available");
      /*check if eSE exist*/
      isSeActive = isNfceeActive(ROUTE_LOC_ESE_ID_IDX, ActDevHandle);
      if(isSeActive) {
        routeLoc = SecureElement::ESE_ID;
      }
    }

    if (!isSeActive && (fallBackOption == ROUTE_ESE || fallBackOption == ROUTE_DH)) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: changing the destination to DH", __func__);
      routeLoc = SecureElement::DH_ID;
      ActDevHandle =
          SecureElement::getInstance().getEseHandleFromGenericId(routeLoc);
    }
  } else {
    ActDevHandle = SecureElement::getInstance().getEseHandleFromGenericId(
        SecureElement::DH_ID);
  }
  return ActDevHandle;
}

/*******************************************************************************
 **
 ** Function:        isNfceeActive
 **
 ** Description:     checks whether route(Nfcee) is active or not
 **
 ** Returns:         TRUE(Active present)/FALSE(Not present)
 **
 *******************************************************************************/
bool RoutingManager::isNfceeActive(int routeLoc, tNFA_HANDLE& ActDevHandle) {
  bool isSeIDPresent = false;
  tNFA_HANDLE seHandle =
      SecureElement::getInstance().getEseHandleFromGenericId(routeLoc);

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: seHandle : %x", __func__, seHandle);
  if(SecureElement::getInstance().getSETechnology(seHandle) != 0) {
    ActDevHandle = seHandle;
    isSeIDPresent = true;
    DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Default AID route handle active");
  }
  return isSeIDPresent;
}

/* Forward Functionality is to handle either technology which is supported by UICC
 * We are handling it by setting the alternate technology(A/B) to HOST
 * */
void RoutingManager::processTechEntriesForFwdfunctionality(void)
{
    //static const char fn []    = "RoutingManager::processTechEntriesForFwdfunctionality";
    uint32_t techSupportedByUICC = mTechSupportedByUicc1;
    if(!isDynamicUiccEnabled) {
        techSupportedByUICC = (getUiccRoute(sCurrentSelectedUICCSlot) == SecureElement::getInstance().EE_HANDLE_0xF4)?
                mTechSupportedByUicc1 : mTechSupportedByUicc2;
    }
    else {
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

void RoutingManager::dumpTables(int xx)
{


    switch(xx)
    {
    case 1://print only proto table
        LOG(ERROR) << StringPrintf("--------------------Proto Table Entries------------------" );
        for (int xx = 0; xx < MAX_PROTO_ENTRIES; xx++) {
          LOG(ERROR) << StringPrintf(
              "|Index=%d|RouteLoc=0x%03X|Proto=0x%02X|Power=0x%02X|Enable=0x%"
              "01X|",
              xx, mProtoTableEntries[xx].routeLoc,
              mProtoTableEntries[xx].protocol, mProtoTableEntries[xx].power,
              mProtoTableEntries[xx].enable);
        }
        //ALOGV("---------------------------------------------------------" );
        break;
    case 2://print Lmrt proto table
        LOG(ERROR) << StringPrintf("----------------------------------------Lmrt Proto Entries------------------------------------" );
        for (int xx = 0; xx < MAX_ROUTE_LOC_ENTRIES; xx++) {
          LOG(ERROR) << StringPrintf(
              "|Index=%d|nfceeID=0x%03X|SWTCH-ON=0x%02X|SWTCH-OFF=0x%02X|BAT-"
              "OFF=0x%02X|SCRN-LOCK=0x%02X|SCRN-OFF=0x%02X|SCRN-OFF_LOCK=0x%"
              "02X",
              xx, mLmrtEntries[xx].nfceeID, mLmrtEntries[xx].proto_switch_on,
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
/* Based on the features enabled :- nfcFL.nfccFL._NFCC_DYNAMIC_DUAL_UICC, nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH & NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH,
 * Calculate the UICC route location ID.
 * For DynamicDualUicc,Route location is based on the user configuration(6th & 7th bit) of route
 * For StaticDualUicc without External Switch(with DynamicDualUicc enabled), Route location is based on user selection from selectUicc() API
 * For StaticDualUicc(With External Switch), Route location is always ROUTE_LOC_UICC1_ID
 */
uint16_t RoutingManager::getUiccRouteLocId(const int route)
{
	LOG(ERROR) << StringPrintf(" getUiccRouteLocId route %X",
                   route);
    if((route != 0x02 ) &&(route != 0x03))
      return NFA_HANDLE_INVALID;

    if(!isDynamicUiccEnabled)
        return getUiccRoute(sCurrentSelectedUICCSlot);
    else if(isDynamicUiccEnabled)
        return ((route == 0x02 ) ? SecureElement::getInstance().EE_HANDLE_0xF4 : getUicc2selected());
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

/*******************************************************************************
**
** Function:        getNfaHandle
**
** Description:     Returns EE handle from Generic EE ID
**
** Returns:         EE Handle
**
*******************************************************************************/
tNFA_HANDLE RoutingManager::getNfaHandle(uint16_t routeLoc) {
    tNFA_HANDLE genHandle = NFA_HANDLE_INVALID;
    switch (routeLoc) {
        case SecureElement::DH_ID:
        genHandle = ROUTE_LOC_HOST_ID;
        break;
        case SecureElement::ESE_ID:
        genHandle = ROUTE_LOC_ESE_ID;
        break;
        case SecureElement::EUICC_ID:
        genHandle = ROUTE_LOC_EUICC_ID;
        break;
        case SecureElement::EUICC2_ID:
        genHandle = ROUTE_LOC_EUICC2_ID;
        break;
        default:
        genHandle = getUiccRouteLocId(routeLoc);
    }
    return genHandle;
}

/*******************************************************************************
**
** Function:        getRouting
**
** Description:     Send GET_LISTEN_MODE_ROUTING command
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::getRouting(uint16_t* routeLen, uint8_t* routingBuff) {
  tNFA_STATUS nfcStat = NFA_STATUS_FAILED;
  if (routingBuff == NULL || routeLen == NULL) return;
  sRoutingBuff = routingBuff;
  SyncEventGuard guard(sNfaGetRoutingEvent);
  nfcStat = NFC_GetRouting();
  if (nfcStat == NFA_STATUS_OK) {
    if(sNfaGetRoutingEvent.wait(NFC_CMD_TIMEOUT) == false) {
      LOG(ERROR) << StringPrintf("Routing Event has terminated");
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("status=0x0%x", nfcStat);
    *routeLen = sRoutingBuffLen;
  } else {
    *routeLen = 0x00;
  }
}

/*******************************************************************************
**
** Function:        processGetRouting
**
** Description:     Process the eventData(current routing info) received during
**                  getRouting
**                  eventData : eventData
**                  sRoutingBuff : Array containing processed data
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::processGetRoutingRsp(tNFA_DM_CBACK_DATA* eventData) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s : Enter", __func__);
  uint8_t xx = 0, numTLVs = 0, currPos = 0, curTLVLen = 0;
  uint8_t sRoutingCurrent[256];
  if (eventData == NULL) return;
  numTLVs = *(eventData->get_routing.param_tlvs + 1);
  /*Copying only routing Entries.
  Skipping fields,
  More                  : 1Byte
  No of Routing Entries : 1Byte*/
  memcpy(sRoutingCurrent, eventData->get_routing.param_tlvs + 2,
         eventData->get_routing.tlv_size - 2);

  while (xx < numTLVs) {
    curTLVLen = *(sRoutingCurrent + currPos + 1);
    /*Filtering out Routing Entry corresponding to PROTOCOL_NFC_DEP*/
    if ((*(sRoutingCurrent + currPos) == PROTOCOL_BASED_ROUTING) &&
        (*(sRoutingCurrent + currPos + (curTLVLen + 1)) ==
         NFA_PROTOCOL_NFC_DEP)) {
      currPos = currPos + curTLVLen + TYPE_LENGTH_SIZE;
    } else {
      memcpy(sRoutingBuff + sRoutingBuffLen, sRoutingCurrent + currPos,
             curTLVLen + TYPE_LENGTH_SIZE);
      currPos = currPos + curTLVLen + TYPE_LENGTH_SIZE;
      sRoutingBuffLen = sRoutingBuffLen + curTLVLen + TYPE_LENGTH_SIZE;
    }
    xx++;
  }
  if (eventData->status != NFA_STATUS_CONTINUE) {
    SyncEventGuard guard(sNfaGetRoutingEvent);
    sNfaGetRoutingEvent.notifyOne();
  }
}

/*******************************************************************************
**
** Function:        notifyAllEvents
**
** Description:     Unblocks function waiting on syncEvent, if any.
**
** Returns:         void
**
*******************************************************************************/
void RoutingManager::notifyAllEvents() {
  LOG(INFO) << StringPrintf("%s: Enter", __func__);
  {
    SyncEventGuard guard(mAidAddRemoveEvent);
    mAidAddRemoveEvent.notifyOne();
  }
  {
    SyncEventGuard guard(mEeUpdateEvent);
    mEeUpdateEvent.notifyOne();
  }
  {
    SyncEventGuard guard(mRoutingEvent);
    mRoutingEvent.notifyOne();
  }
  LOG(INFO) << StringPrintf("%s: Exit", __func__);
}
#endif
