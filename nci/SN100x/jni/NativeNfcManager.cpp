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
*  Copyright 2018-2019 NXP
*
******************************************************************************/
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <cutils/properties.h>
#include <errno.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <semaphore.h>
#include "HciEventManager.h"
#include "JavaClassConstants.h"
#include "NfcAdaptation.h"
#include "NfcJniUtil.h"
#include "NfcSelfTest.h"
#include "NfcTag.h"
#include "PeerToPeer.h"
#include "Pn544Interop.h"
#include "PowerSwitch.h"
#include "RoutingManager.h"
#include "SyncEvent.h"
#include "nfc_config.h"
#if(NXP_EXTNS == TRUE)
#include "MposManager.h"
#include "SecureElement.h"
#include "DwpChannel.h"
#include "NativeJniExtns.h"
#include "NativeT4tNfcee.h"
#include "nfa_nfcee_int.h"
#endif

#include "ce_api.h"
#include "nfa_api.h"
#include "nfa_ee_api.h"
#include "nfa_p2p_api.h"
#include "nfc_brcm_defs.h"
#include "phNxpExtns.h"
#include "rw_api.h"

using android::base::StringPrintf;
#if(NXP_EXTNS == TRUE)
#define RDR_PASS_THRU_MODE_DISABLE (0x0)
#define RDR_PASS_THRU_MODE_ENABLE (0x1)
#define RDR_PASS_THRU_MODE_XCV (0x2)
#define RDR_PASS_THRU_ENABLE_TIMEOUT (1030)
#define RDR_PASS_THRU_DISABLE_TIMEOUT (1030)
#define RDR_PASS_THRU_XCV_TIMEOUT (1100)

#define RDR_PASS_THRU_MOD_TYPE_A (0x0)
#define RDR_PASS_THRU_MOD_TYPE_B (0x1)
#define RDR_PASS_THRU_MOD_TYPE_V (0x2)
#define RDR_PASS_THRU_MOD_TYPE_LIMIT (0x3)
#define CMD_HDR_SIZE_XCV (0x3)
#define SECURE_ELEMENT_UICC_SLOT_DEFAULT (0x01)
bool isDynamicUiccEnabled;
#endif
extern tNFA_DM_DISC_FREQ_CFG* p_nfa_dm_rf_disc_freq_cfg;  // defined in stack
namespace android {
extern bool gIsTagDeactivating;
extern bool gIsSelectingRfInterface;
extern void nativeNfcTag_doTransceiveStatus(tNFA_STATUS status, uint8_t* buf,
                                            uint32_t buflen);
extern void nativeNfcTag_notifyRfTimeout();
extern void nativeNfcTag_doConnectStatus(jboolean is_connect_ok);
extern void nativeNfcTag_doDeactivateStatus(int status);
extern void nativeNfcTag_doWriteStatus(jboolean is_write_ok);
extern jboolean nativeNfcTag_doDisconnect(JNIEnv*, jobject);
extern void nativeNfcTag_doCheckNdefResult(tNFA_STATUS status,
                                           uint32_t max_size,
                                           uint32_t current_size,
                                           uint8_t flags);
extern void nativeNfcTag_doMakeReadonlyResult(tNFA_STATUS status);
extern void nativeNfcTag_doPresenceCheckResult(tNFA_STATUS status);
extern void nativeNfcTag_formatStatus(bool is_ok);
extern void nativeNfcTag_resetPresenceCheck();
extern void nativeNfcTag_doReadCompleted(tNFA_STATUS status);
extern void nativeNfcTag_setRfInterface(tNFA_INTF_TYPE rfInterface);
extern void nativeNfcTag_abortWaits();
extern void nativeLlcpConnectionlessSocket_abortWait();
extern void nativeNfcTag_registerNdefTypeHandler();
extern void nativeNfcTag_acquireRfInterfaceMutexLock();
extern void nativeNfcTag_releaseRfInterfaceMutexLock();
extern void nativeLlcpConnectionlessSocket_receiveData(uint8_t* data,
                                                       uint32_t len,
                                                       uint32_t remote_sap);
#if(NXP_EXTNS == TRUE)
extern tNFA_STATUS Nxp_doResonantFrequency(bool modeOn);
void handleWiredmode(bool isShutdown);
int nfcManager_doPartialInitialize(JNIEnv* e, jobject o);
int nfcManager_doPartialDeInitialize(JNIEnv* e, jobject o);
extern tNFA_STATUS NxpNfc_Write_Cmd_Common(uint8_t retlen, uint8_t* buffer);
extern void NxpPropCmd_OnResponseCallback(uint8_t event, uint16_t param_len,
                                            uint8_t * p_param);
extern tNFA_STATUS NxpPropCmd_send(uint8_t * pData4Tx, uint8_t dataLen,
                                   uint8_t * rsp_len, uint8_t * rsp_buf,
                                   uint32_t rspTimeout, tHAL_NFC_ENTRY * halMgr);
extern tNFA_STATUS send_flush_ram_to_flash();
extern void nativeNfcTag_checkActivatedProtoParameters(tNFA_ACTIVATED& activationData);
extern bool gIsWaiting4Deact2SleepNtf;
extern bool gGotDeact2IdleNtf;
#endif
}  // namespace android

/*****************************************************************************
**
** public variables and functions
**
*****************************************************************************/
bool gActivated = false;
SyncEvent gDeactivatedEvent;
SyncEvent sNfaSetPowerSubState;
bool legacy_mfc_reader = true;
SyncEvent sChangeDiscTechEvent;
#if(NXP_EXTNS == TRUE)
/*Structure to store  discovery parameters*/
typedef struct discovery_Parameters
{
    int technologies_mask;
    bool enable_lptd;
    bool reader_mode;
    bool enable_p2p;
    bool enable_host_routing;
    bool restart;
}discovery_Parameters_t;
discovery_Parameters_t mDiscParams;
jint nfcManager_getUiccId(jint uicc_slot);
jint nfcManager_getUiccRoute(jint uicc_slot);
uint16_t sCurrentSelectedUICCSlot = SECURE_ELEMENT_UICC_SLOT_DEFAULT;
#endif
namespace android {
jmethodID gCachedNfcManagerNotifyNdefMessageListeners;
jmethodID gCachedNfcManagerNotifyTransactionListeners;
jmethodID gCachedNfcManagerNotifyLlcpLinkActivation;
jmethodID gCachedNfcManagerNotifyLlcpLinkDeactivated;
jmethodID gCachedNfcManagerNotifyLlcpFirstPacketReceived;
jmethodID gCachedNfcManagerNotifyHostEmuActivated;
jmethodID gCachedNfcManagerNotifyHostEmuData;
jmethodID gCachedNfcManagerNotifyHostEmuDeactivated;
jmethodID gCachedNfcManagerNotifyRfFieldActivated;
jmethodID gCachedNfcManagerNotifyRfFieldDeactivated;
const char* gNativeP2pDeviceClassName =
    "com/android/nfc/dhimpl/NativeP2pDevice";
const char* gNativeLlcpServiceSocketClassName =
    "com/android/nfc/dhimpl/NativeLlcpServiceSocket";
const char* gNativeLlcpConnectionlessSocketClassName =
    "com/android/nfc/dhimpl/NativeLlcpConnectionlessSocket";
const char* gNativeLlcpSocketClassName =
    "com/android/nfc/dhimpl/NativeLlcpSocket";
const char* gNativeNfcTagClassName = "com/android/nfc/dhimpl/NativeNfcTag";
const char* gNativeNfcManagerClassName =
    "com/android/nfc/dhimpl/NativeNfcManager";
#if (NXP_EXTNS == TRUE)
const char* gNativeNfcSecureElementClassName =
    "com/android/nfc/dhimpl/NativeNfcSecureElement";
const char*             gNativeNfcMposManagerClassName       =
    "com/android/nfc/dhimpl/NativeNfcMposManager";
const char* gNativeT4tNfceeClassName =
    "com/android/nfc/dhimpl/NativeT4tNfceeManager";
void enableLastRfDiscovery();
void storeLastDiscoveryParams(int technologies_mask, bool enable_lptd,
                              bool reader_mode, bool enable_host_routing,
                              bool enable_p2p, bool restart);
#endif
jmethodID  gCachedNfcManagerNotifySeListenActivated;
jmethodID  gCachedNfcManagerNotifySeListenDeactivated;
jmethodID  gCachedNfcManagerNotifySeInitialized;
void doStartupConfig();
void startStopPolling(bool isStartPolling);
void startRfDiscovery(bool isStart);
bool isDiscoveryStarted();
}  // namespace android

/*****************************************************************************
**
** private variables and functions
**
*****************************************************************************/
namespace android {
static jint sLastError = ERROR_BUFFER_TOO_SMALL;
static SyncEvent sNfaEnableEvent;                // event for NFA_Enable()
static SyncEvent sNfaDisableEvent;               // event for NFA_Disable()
static SyncEvent sNfaEnableDisablePollingEvent;  // event for
                                                 // NFA_EnablePolling(),
                                                 // NFA_DisablePolling()
static SyncEvent sNfaSetConfigEvent;             // event for Set_Config....
static SyncEvent sNfaGetConfigEvent;             // event for Get_Config....
#if(NXP_EXTNS == TRUE)
static SyncEvent sNfaTransitConfigEvent;  // event for NFA_SetTransitConfig()
#endif
static bool sIsNfaEnabled = false;
static bool sDiscoveryEnabled = false;  // is polling or listening
static bool sPollingEnabled = false;    // is polling for tag?
static bool sIsDisabling = false;
static bool sRfEnabled = false;   // whether RF discovery is enabled
static bool sSeRfActive = false;  // whether RF with SE is likely active
static bool sReaderModeEnabled =
    false;  // whether we're only reading tags, not allowing P2p/card emu
static bool sP2pEnabled = false;
static bool sP2pActive = false;  // whether p2p was last active
static bool sAbortConnlessWait = false;
static jint sLfT3tMax = 0;
static bool sRoutingInitialized = false;

#define CONFIG_UPDATE_TECH_MASK (1 << 1)
#define DEFAULT_TECH_MASK                                                  \
  (NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B | NFA_TECHNOLOGY_MASK_F | \
   NFA_TECHNOLOGY_MASK_V | NFA_TECHNOLOGY_MASK_B_PRIME |                   \
   NFA_TECHNOLOGY_MASK_A_ACTIVE | NFA_TECHNOLOGY_MASK_F_ACTIVE |           \
   NFA_TECHNOLOGY_MASK_KOVIO)
#define DEFAULT_DISCOVERY_DURATION 500
#define READER_MODE_DISCOVERY_DURATION 200

#if(NXP_EXTNS == TRUE)
#define DUAL_UICC_FEATURE_NOT_AVAILABLE 0xED;
#define STATUS_UNKNOWN_ERROR 0xEF;
enum { UICC_CONFIGURED, UICC_NOT_CONFIGURED };
typedef enum dual_uicc_error_states {
  DUAL_UICC_ERROR_NFCC_BUSY = 0x02,
  DUAL_UICC_ERROR_SELECT_FAILED,
  DUAL_UICC_ERROR_NFC_TURNING_OFF,
  DUAL_UICC_ERROR_INVALID_SLOT,
  DUAL_UICC_ERROR_STATUS_UNKNOWN
} dual_uicc_error_state_t;

typedef enum {
  FDSTATUS_SUCCESS = 0,
  FDSTATUS_ERROR_NFC_IS_OFF,
  FDSTATUS_ERROR_NFC_BUSY_IN_MPOS,
  FDSTATUS_ERROR_UNKNOWN
} field_detect_status_t;

#endif

static void nfaConnectionCallback(uint8_t event, tNFA_CONN_EVT_DATA* eventData);
static void nfaDeviceManagementCallback(uint8_t event,
                                        tNFA_DM_CBACK_DATA* eventData);
static bool isPeerToPeer(tNFA_ACTIVATED& activated);
static bool isListenMode(tNFA_ACTIVATED& activated);
#if (NXP_EXTNS==TRUE)
static void nfcManager_changeDiscoveryTech(JNIEnv* e, jobject o, jint pollTech, jint listenTech);
#endif
#if (NXP_EXTNS==FALSE)
static void enableDisableLptd(bool enable);
#endif

static tNFA_STATUS stopPolling_rfDiscoveryDisabled();
static tNFA_STATUS startPolling_rfDiscoveryDisabled(
    tNFA_TECHNOLOGY_MASK tech_mask);
static void nfcManager_doSetScreenState(JNIEnv* e, jobject o,
                                        jint screen_state_mask);
#if(NXP_EXTNS == TRUE)
static jint nfcManager_getFwVersion(JNIEnv* e, jobject o);
static jbyteArray nfcManager_readerPassThruMode(JNIEnv *e, jobject o,
                                                jbyte rqst,
                                                jbyte modulationTyp);
static jbyteArray nfcManager_transceiveAppData(JNIEnv *e, jobject o,
                                               jbyteArray data);
static bool nfcManager_isNfccBusy(JNIEnv*, jobject);
static int nfcManager_setTransitConfig(JNIEnv* e, jobject o, jstring config);
static std::string ConvertJavaStrToStdString(JNIEnv * env, jstring s);
static jint nfcManager_getAidTableSize (JNIEnv*, jobject );
static jint nfcManager_getRemainingAidTableSize (JNIEnv* , jobject );
static int nfcManager_doSelectUicc(JNIEnv* e, jobject o, jint uiccSlot);
static int nfcManager_doGetSelectedUicc(JNIEnv* e, jobject o);
static int nfcManager_staticDualUicc_Precondition(int uiccSlot);
static int nfcManager_setPreferredSimSlot(JNIEnv* e, jobject o, jint uiccSlot);
#endif
static uint16_t sCurrentConfigLen;
static uint8_t sConfig[256];
static int NFA_SCREEN_POLLING_TAG_MASK = 0x10;
static bool gIsDtaEnabled = false;
#if (NXP_EXTNS==TRUE)

static bool gsNfaPartialEnabled = false;
#endif
#if (NXP_EXTNS==TRUE)
static int prevScreenState = NFA_SCREEN_STATE_UNKNOWN;
#else
static int prevScreenState = NFA_SCREEN_STATE_OFF_UNLOCKED;
#endif
/////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////

bool nfc_debug_enabled;

namespace {
void initializeGlobalDebugEnabledFlag() {
  nfc_debug_enabled =
      (NfcConfig::getUnsigned(NAME_NFC_DEBUG_ENABLED, 1) != 0) ? true : false;

  char valueStr[PROPERTY_VALUE_MAX] = {0};
  int len = property_get("nfc.debug_enabled", valueStr, "");
  if (len > 0) {
    unsigned debug_enabled = 1;
    // let Android property override .conf variable
    sscanf(valueStr, "%u", &debug_enabled);
    nfc_debug_enabled = (debug_enabled == 0) ? false : true;
  }

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: level=%u", __func__, nfc_debug_enabled);
}
void initializeMfcReaderOption() {
  legacy_mfc_reader =
      (NfcConfig::getUnsigned(NAME_LEGACY_MIFARE_READER, 1) != 0) ? true : false;

  DLOG_IF(INFO, nfc_debug_enabled)
      << __func__ <<": mifare reader option=" << legacy_mfc_reader;

}
}  // namespace

/*******************************************************************************
**
** Function:        getNative
**
** Description:     Get native data
**
** Returns:         Native data structure.
**
*******************************************************************************/
nfc_jni_native_data* getNative(JNIEnv* e, jobject o) {
  static struct nfc_jni_native_data* sCachedNat = NULL;
  if (e) {
    sCachedNat = nfc_jni_get_nat(e, o);
  }
  return sCachedNat;
}

/*******************************************************************************
**
** Function:        handleRfDiscoveryEvent
**
** Description:     Handle RF-discovery events from the stack.
**                  discoveredDevice: Discovered device.
**
** Returns:         None
**
*******************************************************************************/
static void handleRfDiscoveryEvent(tNFC_RESULT_DEVT* discoveredDevice) {

  if (discoveredDevice->more == NCI_DISCOVER_NTF_MORE) {
#if(NXP_EXTNS == TRUE)
    // there is more discovery notification coming
    NfcTag::getInstance ().mNumDiscNtf++;
#endif
    return;
  }
#if(NXP_EXTNS == TRUE)
  NfcTag::getInstance ().mNumDiscNtf++;
  if(NfcTag::getInstance ().mNumDiscNtf > 1) {
    NfcTag::getInstance().mIsMultiProtocolTag = true;
  }
#endif
  bool isP2p = NfcTag::getInstance().isP2pDiscovered();
  if (!sReaderModeEnabled && isP2p) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Select peer device", __FUNCTION__);
    NfcTag::getInstance().selectP2p();
  }
  else {
    NfcTag::getInstance ().mNumDiscNtf--;
    NfcTag::getInstance().selectFirstTag();
  }
}

/*******************************************************************************
**
** Function:        nfaConnectionCallback
**
** Description:     Receive connection-related events from stack.
**                  connEvent: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
static void nfaConnectionCallback(uint8_t connEvent,
                                  tNFA_CONN_EVT_DATA* eventData) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
#if (NXP_EXTNS == TRUE)
  static uint8_t prev_more_val = 0x00;
  uint8_t cur_more_val = 0x00;
#endif
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: event= %u", __func__, connEvent);
#if (NXP_EXTNS == TRUE)
  NativeJniExtns::getInstance().notifyNfcEvent(
      "nfaConnectionCallback", (void*)&connEvent, (void*)eventData);
#endif
  switch (connEvent) {
    case NFA_POLL_ENABLED_EVT:  // whether polling successfully started
    {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_POLL_ENABLED_EVT: status = %u", __func__, eventData->status);

      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_POLL_DISABLED_EVT:  // Listening/Polling stopped
    {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_POLL_DISABLED_EVT: status = %u", __func__,
                          eventData->status);

      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_RF_DISCOVERY_STARTED_EVT:  // RF Discovery started
    {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_RF_DISCOVERY_STARTED_EVT: status = %u",
                          __func__, eventData->status);

      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_RF_DISCOVERY_STOPPED_EVT:  // RF Discovery stopped event
    {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_RF_DISCOVERY_STOPPED_EVT: status = %u",
                          __func__, eventData->status);

      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_DISC_RESULT_EVT:  // NFC link/protocol discovery notificaiton
      status = eventData->disc_result.status;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_DISC_RESULT_EVT: status = %d", __func__, status);
#if (NXP_EXTNS == TRUE)
      cur_more_val = eventData->disc_result.discovery_ntf.more;
      if((cur_more_val == 0x01) && (prev_more_val != 0x02)) {
        DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_DISC_RESULT_EVT: Failed", __func__);
        status = NFA_STATUS_FAILED;
      } else {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_DISC_RESULT_EVT: Success", __func__);
        status = NFA_STATUS_OK;
        prev_more_val = cur_more_val;
      }
#endif
      if (status != NFA_STATUS_OK) {
#if (NXP_EXTNS == TRUE)
        NfcTag::getInstance ().mNumDiscNtf = 0;
#endif
        LOG(ERROR) << StringPrintf("%s: NFA_DISC_RESULT_EVT error: status = %d",
                                   __func__, status);
      } else {
        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
        handleRfDiscoveryEvent(&eventData->disc_result.discovery_ntf);
      }
      break;

    case NFA_SELECT_RESULT_EVT:  // NFC link/protocol discovery select response
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_SELECT_RESULT_EVT: status = %d, gIsSelectingRfInterface = "
          "%d, "
          "sIsDisabling=%d",
          __func__, eventData->status, gIsSelectingRfInterface, sIsDisabling);

      if (sIsDisabling) break;
      if (eventData->status != NFA_STATUS_OK) {
        if (gIsSelectingRfInterface) {
          nativeNfcTag_doConnectStatus(false);
        }
#if (NXP_EXTNS == TRUE)
        NfcTag::getInstance().mTechListIndex = 0;
#endif
        LOG(ERROR) << StringPrintf(
            "%s: NFA_SELECT_RESULT_EVT error: status = %d", __func__,
            eventData->status);
        NFA_Deactivate(FALSE);
      }
      break;

    case NFA_DEACTIVATE_FAIL_EVT:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_DEACTIVATE_FAIL_EVT: status = %d", __func__,
                          eventData->status);
      break;

    case NFA_ACTIVATED_EVT:  // NFC link/protocol activated
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_ACTIVATED_EVT: gIsSelectingRfInterface=%d, sIsDisabling=%d",
          __func__, gIsSelectingRfInterface, sIsDisabling);
#if (NXP_EXTNS == TRUE)
        if(NFC_PROTOCOL_T5T == NfcTag::getInstance ().mTechLibNfcTypes[0x00]
                && NfcTag::getInstance ().mNumDiscNtf){
          /* T5T doesn't support multiproto detection logic */
          NfcTag::getInstance ().mNumDiscNtf = 0x00;
        }
#endif
      if ((eventData->activated.activate_ntf.protocol !=
           NFA_PROTOCOL_NFC_DEP) &&
          (!isListenMode(eventData->activated))) {
        nativeNfcTag_setRfInterface(
            (tNFA_INTF_TYPE)eventData->activated.activate_ntf.intf_param.type);
      }
      if (EXTNS_GetConnectFlag() == TRUE) {
        NfcTag::getInstance().setActivationState();
        nativeNfcTag_doConnectStatus(true);
        break;
      }
#if(NXP_EXTNS == TRUE)
      if(nfcFL.nfcNxpEse) {
          if(nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
     /*
                     * Handle Reader over SWP START_READER_EVENT
     * */
          if(eventData->activated.activate_ntf.intf_param.type == NCI_INTERFACE_UICC_DIRECT ||
                          eventData->activated.activate_ntf.intf_param.type == NCI_INTERFACE_ESE_DIRECT)
          {
             MposManager::getInstance().setEtsiReaederState(STATE_SE_RDR_MODE_ACTIVATED);
             MposManager::getInstance().notifyEEReaderEvent(ETSI_READER_ACTIVATED);
             break;
          }
        }
     }
#endif
      NfcTag::getInstance().setActive(true);
      if (sIsDisabling || !sIsNfaEnabled) break;
      gActivated = true;

      NfcTag::getInstance().setActivationState();
      if (gIsSelectingRfInterface) {
        nativeNfcTag_doConnectStatus(true);
        nativeNfcTag_checkActivatedProtoParameters(eventData->activated);
        break;
      }

      nativeNfcTag_resetPresenceCheck();
      if (!isListenMode(eventData->activated) &&
          (prevScreenState == NFA_SCREEN_STATE_OFF_LOCKED ||
           prevScreenState == NFA_SCREEN_STATE_OFF_UNLOCKED)) {
        NFA_Deactivate(FALSE);
      }
      if (isPeerToPeer(eventData->activated)) {
        if (sReaderModeEnabled) {
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
              "%s: ignoring peer target in reader mode.", __func__);
          NFA_Deactivate(FALSE);
          break;
        }
        sP2pActive = true;
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_ACTIVATED_EVT; is p2p", __func__);
        if (NFC_GetNCIVersion() == NCI_VERSION_1_0) {
          // Disable RF field events in case of p2p
          uint8_t nfa_disable_rf_events[] = {0x00};
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: Disabling RF field events", __func__);
          status = NFA_SetConfig(NCI_PARAM_ID_RF_FIELD_INFO,
                                 sizeof(nfa_disable_rf_events),
                                 &nfa_disable_rf_events[0]);
          if (status == NFA_STATUS_OK) {
            DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf("%s: Disabled RF field events", __func__);
          } else {
            LOG(ERROR) << StringPrintf("%s: Failed to disable RF field events",
                                       __func__);
          }
        }
      } else if (pn544InteropIsBusy() == false) {
        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
#if (NXP_EXTNS == TRUE)
        if(NfcTag::getInstance ().mNumDiscNtf) {
          NFA_Deactivate (true);
        }
#endif

        // We know it is not activating for P2P.  If it activated in
        // listen mode then it is likely for an SE transaction.
        // Send the RF Event.
        if (isListenMode(eventData->activated)) {
#if(NXP_EXTNS == TRUE)
          SecureElement::getInstance().notifyListenModeState (true);
#endif
          sSeRfActive = true;
        }
      }
      break;

    case NFA_DEACTIVATED_EVT:  // NFC link/protocol deactivated
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_DEACTIVATED_EVT   Type: %u, gIsTagDeactivating: %d",
          __func__, eventData->deactivated.type, gIsTagDeactivating);

#if (NXP_EXTNS == TRUE)
      if(gIsWaiting4Deact2SleepNtf) {
        if(eventData->deactivated.type == NFA_DEACTIVATE_TYPE_IDLE) {
          gGotDeact2IdleNtf = true;
        } else if(eventData->deactivated.type == NFA_DEACTIVATE_TYPE_SLEEP) {
          gIsWaiting4Deact2SleepNtf = false;
        }
      }
#endif
      NfcTag::getInstance().setDeactivationState(eventData->deactivated);
#if (NXP_EXTNS == TRUE)
      if(NfcTag::getInstance ().mNumDiscNtf) {
        NfcTag::getInstance ().mNumDiscNtf--;
        NfcTag::getInstance().selectNextTag();
      }
#endif
      if (eventData->deactivated.type != NFA_DEACTIVATE_TYPE_SLEEP) {
        {
          SyncEventGuard g(gDeactivatedEvent);
          gActivated = false;  // guard this variable from multi-threaded access
          gDeactivatedEvent.notifyOne();
        }
#if (NXP_EXTNS == TRUE)
        NfcTag::getInstance ().mNumDiscNtf = 0;
        NfcTag::getInstance ().mTechListIndex =0;
#endif
        nativeNfcTag_resetPresenceCheck();
#if (NXP_EXTNS == TRUE)
        if (gIsSelectingRfInterface == false) {
          NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
          nativeNfcTag_abortWaits();
        }
#else
        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
        nativeNfcTag_abortWaits();
#endif
        NfcTag::getInstance().abort();
#if (NXP_EXTNS == TRUE)
        NfcTag::getInstance().mIsMultiProtocolTag = false;
#endif
      } else if (gIsTagDeactivating) {
        NfcTag::getInstance().setActive(false);
        nativeNfcTag_doDeactivateStatus(0);
      } else if (EXTNS_GetDeactivateFlag() == TRUE) {
        NfcTag::getInstance().setActive(false);
        nativeNfcTag_doDeactivateStatus(0);
      }

      // If RF is activated for what we think is a Secure Element transaction
      // and it is deactivated to either IDLE or DISCOVERY mode, notify w/event.
      if ((eventData->deactivated.type == NFA_DEACTIVATE_TYPE_IDLE) ||
          (eventData->deactivated.type == NFA_DEACTIVATE_TYPE_DISCOVERY)) {
        if (sSeRfActive) {
          sSeRfActive = false;
#if(NXP_EXTNS == TRUE)
          SecureElement::getInstance().notifyListenModeState (false);
#endif
        } else if (sP2pActive) {
          sP2pActive = false;
          // Make sure RF field events are re-enabled
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: NFA_DEACTIVATED_EVT; is p2p", __func__);
          if (NFC_GetNCIVersion() == NCI_VERSION_1_0) {
            // Disable RF field events in case of p2p
            uint8_t nfa_enable_rf_events[] = {0x01};

            if (!sIsDisabling && sIsNfaEnabled) {
              DLOG_IF(INFO, nfc_debug_enabled)
                  << StringPrintf("%s: Enabling RF field events", __func__);
              status = NFA_SetConfig(NCI_PARAM_ID_RF_FIELD_INFO,
                                     sizeof(nfa_enable_rf_events),
                                     &nfa_enable_rf_events[0]);
              if (status == NFA_STATUS_OK) {
                DLOG_IF(INFO, nfc_debug_enabled)
                    << StringPrintf("%s: Enabled RF field events", __func__);
              } else {
                LOG(ERROR) << StringPrintf(
                    "%s: Failed to enable RF field events", __func__);
              }
            }
          }
        }
      }

      break;

    case NFA_TLV_DETECT_EVT:  // TLV Detection complete
      status = eventData->tlv_detect.status;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_TLV_DETECT_EVT: status = %d, protocol = %d, num_tlvs = %d, "
          "num_bytes = %d",
          __func__, status, eventData->tlv_detect.protocol,
          eventData->tlv_detect.num_tlvs, eventData->tlv_detect.num_bytes);
      if (status != NFA_STATUS_OK) {
        LOG(ERROR) << StringPrintf("%s: NFA_TLV_DETECT_EVT error: status = %d",
                                   __func__, status);
      }
      break;

    case NFA_NDEF_DETECT_EVT:  // NDEF Detection complete;
      // if status is failure, it means the tag does not contain any or valid
      // NDEF data;  pass the failure status to the NFC Service;
      status = eventData->ndef_detect.status;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_NDEF_DETECT_EVT: status = 0x%X, protocol = %u, "
          "max_size = %u, cur_size = %u, flags = 0x%X",
          __func__, status, eventData->ndef_detect.protocol,
          eventData->ndef_detect.max_size, eventData->ndef_detect.cur_size,
          eventData->ndef_detect.flags);
      NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
      nativeNfcTag_doCheckNdefResult(status, eventData->ndef_detect.max_size,
                                     eventData->ndef_detect.cur_size,
                                     eventData->ndef_detect.flags);
      break;

    case NFA_DATA_EVT:  // Data message received (for non-NDEF reads)
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_DATA_EVT: status = 0x%X, len = %d", __func__,
                          eventData->status, eventData->data.len);
      nativeNfcTag_doTransceiveStatus(eventData->status, eventData->data.p_data,
                                      eventData->data.len);
      break;
    case NFA_RW_INTF_ERROR_EVT:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFC_RW_INTF_ERROR_EVT", __func__);
      nativeNfcTag_notifyRfTimeout();
      nativeNfcTag_doReadCompleted(NFA_STATUS_TIMEOUT);
      break;
    case NFA_SELECT_CPLT_EVT:  // Select completed
      status = eventData->status;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_SELECT_CPLT_EVT: status = %d", __func__, status);
      if (status != NFA_STATUS_OK) {
        LOG(ERROR) << StringPrintf("%s: NFA_SELECT_CPLT_EVT error: status = %d",
                                   __func__, status);
      }
      break;

    case NFA_READ_CPLT_EVT:  // NDEF-read or tag-specific-read completed
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_READ_CPLT_EVT: status = 0x%X", __func__, eventData->status);
      nativeNfcTag_doReadCompleted(eventData->status);
      NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
      break;

    case NFA_WRITE_CPLT_EVT:  // Write completed
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_WRITE_CPLT_EVT: status = %d", __func__, eventData->status);
      nativeNfcTag_doWriteStatus(eventData->status == NFA_STATUS_OK);
      break;

    case NFA_SET_TAG_RO_EVT:  // Tag set as Read only
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_SET_TAG_RO_EVT: status = %d", __func__, eventData->status);
      nativeNfcTag_doMakeReadonlyResult(eventData->status);
      break;

    case NFA_CE_NDEF_WRITE_START_EVT:  // NDEF write started
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_CE_NDEF_WRITE_START_EVT: status: %d",
                          __func__, eventData->status);

      if (eventData->status != NFA_STATUS_OK)
        LOG(ERROR) << StringPrintf(
            "%s: NFA_CE_NDEF_WRITE_START_EVT error: status = %d", __func__,
            eventData->status);
      break;

    case NFA_CE_NDEF_WRITE_CPLT_EVT:  // NDEF write completed
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: FA_CE_NDEF_WRITE_CPLT_EVT: len = %u", __func__,
                          eventData->ndef_write_cplt.len);
      break;

    case NFA_LLCP_ACTIVATED_EVT:  // LLCP link is activated
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_LLCP_ACTIVATED_EVT: is_initiator: %d  remote_wks: %d, "
          "remote_lsc: %d, remote_link_miu: %d, local_link_miu: %d",
          __func__, eventData->llcp_activated.is_initiator,
          eventData->llcp_activated.remote_wks,
          eventData->llcp_activated.remote_lsc,
          eventData->llcp_activated.remote_link_miu,
          eventData->llcp_activated.local_link_miu);

      PeerToPeer::getInstance().llcpActivatedHandler(getNative(0, 0),
                                                     eventData->llcp_activated);
      break;

    case NFA_LLCP_DEACTIVATED_EVT:  // LLCP link is deactivated
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_LLCP_DEACTIVATED_EVT", __func__);
      PeerToPeer::getInstance().llcpDeactivatedHandler(
          getNative(0, 0), eventData->llcp_deactivated);
      break;
    case NFA_LLCP_FIRST_PACKET_RECEIVED_EVT:  // Received first packet over llcp
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_LLCP_FIRST_PACKET_RECEIVED_EVT", __func__);
      PeerToPeer::getInstance().llcpFirstPacketHandler(getNative(0, 0));
      break;
    case NFA_PRESENCE_CHECK_EVT:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_PRESENCE_CHECK_EVT", __func__);
      nativeNfcTag_doPresenceCheckResult(eventData->status);
      break;
    case NFA_FORMAT_CPLT_EVT:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_FORMAT_CPLT_EVT: status=0x%X", __func__, eventData->status);
      nativeNfcTag_formatStatus(eventData->status == NFA_STATUS_OK);
      break;

    case NFA_I93_CMD_CPLT_EVT:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_I93_CMD_CPLT_EVT: status=0x%X", __func__, eventData->status);
      break;

    case NFA_CE_UICC_LISTEN_CONFIGURED_EVT:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_CE_UICC_LISTEN_CONFIGURED_EVT : status=0x%X",
                          __func__, eventData->status);
      break;
#if (NXP_EXTNS == TRUE)
    case NFA_LISTEN_ENABLED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_LISTEN_ENABLED_EVT : status=0x%X", __func__,
                          eventData->status);
      SyncEventGuard guard(sChangeDiscTechEvent);
      sChangeDiscTechEvent.notifyOne();
      break;
    }
    case NFA_T4TNFCEE_EVT:
    case NFA_T4TNFCEE_READ_CPLT_EVT:
    case NFA_T4TNFCEE_WRITE_CPLT_EVT:
      t4tNfcEe.eventHandler(connEvent, eventData);
      break;
#endif
    case NFA_SET_P2P_LISTEN_TECH_EVT:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_SET_P2P_LISTEN_TECH_EVT", __func__);
      PeerToPeer::getInstance().connectionEventHandler(connEvent, eventData);
      break;

    default:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: unknown event ????", __func__);
      break;
  }
}

/*******************************************************************************
**
** Function:        nfcManager_initNativeStruc
**
** Description:     Initialize variables.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_initNativeStruc(JNIEnv* e, jobject o) {
  initializeGlobalDebugEnabledFlag();
  initializeMfcReaderOption();
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

  nfc_jni_native_data* nat =
      (nfc_jni_native_data*)malloc(sizeof(struct nfc_jni_native_data));
  if (nat == NULL) {
    LOG(ERROR) << StringPrintf("%s: fail allocate native data", __func__);
    return JNI_FALSE;
  }

  memset(nat, 0, sizeof(*nat));
  e->GetJavaVM(&(nat->vm));
  nat->env_version = e->GetVersion();
  nat->manager = e->NewGlobalRef(o);

  ScopedLocalRef<jclass> cls(e, e->GetObjectClass(o));
  jfieldID f = e->GetFieldID(cls.get(), "mNative", "J");
  e->SetLongField(o, f, (jlong)nat);
#if(NXP_EXTNS == TRUE)
  MposManager::initMposNativeStruct(e, o);
#endif
  /* Initialize native cached references */
  gCachedNfcManagerNotifyNdefMessageListeners =
      e->GetMethodID(cls.get(), "notifyNdefMessageListeners",
                     "(Lcom/android/nfc/dhimpl/NativeNfcTag;)V");
  gCachedNfcManagerNotifyLlcpLinkActivation =
      e->GetMethodID(cls.get(), "notifyLlcpLinkActivation",
                     "(Lcom/android/nfc/dhimpl/NativeP2pDevice;)V");
  gCachedNfcManagerNotifyLlcpLinkDeactivated =
      e->GetMethodID(cls.get(), "notifyLlcpLinkDeactivated",
                     "(Lcom/android/nfc/dhimpl/NativeP2pDevice;)V");
  gCachedNfcManagerNotifyLlcpFirstPacketReceived =
      e->GetMethodID(cls.get(), "notifyLlcpLinkFirstPacketReceived",
                     "(Lcom/android/nfc/dhimpl/NativeP2pDevice;)V");

  gCachedNfcManagerNotifyHostEmuActivated =
      e->GetMethodID(cls.get(), "notifyHostEmuActivated", "(I)V");

  gCachedNfcManagerNotifyHostEmuData =
      e->GetMethodID(cls.get(), "notifyHostEmuData", "(I[B)V");

  gCachedNfcManagerNotifyHostEmuDeactivated =
      e->GetMethodID(cls.get(), "notifyHostEmuDeactivated", "(I)V");

  gCachedNfcManagerNotifyRfFieldActivated =
      e->GetMethodID(cls.get(), "notifyRfFieldActivated", "()V");
  gCachedNfcManagerNotifyRfFieldDeactivated =
      e->GetMethodID(cls.get(), "notifyRfFieldDeactivated", "()V");

  gCachedNfcManagerNotifySeListenActivated =
      e->GetMethodID(cls.get(),"notifySeListenActivated", "()V");
  gCachedNfcManagerNotifySeListenDeactivated =
      e->GetMethodID(cls.get(),"notifySeListenDeactivated", "()V");
  gCachedNfcManagerNotifyTransactionListeners = e->GetMethodID(
      cls.get(), "notifyTransactionListeners", "([B[BLjava/lang/String;)V");
  gCachedNfcManagerNotifySeInitialized =
      e->GetMethodID(cls.get(),"notifySeInitialized", "()V");
  if (nfc_jni_cache_object(e, gNativeNfcTagClassName, &(nat->cached_NfcTag)) ==
      -1) {
    LOG(ERROR) << StringPrintf("%s: fail cache NativeNfcTag", __func__);
    return JNI_FALSE;
  }

  if (nfc_jni_cache_object(e, gNativeP2pDeviceClassName,
                           &(nat->cached_P2pDevice)) == -1) {
    LOG(ERROR) << StringPrintf("%s: fail cache NativeP2pDevice", __func__);
    return JNI_FALSE;
  }
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return JNI_TRUE;
}

/*******************************************************************************
**
** Function:        nfaDeviceManagementCallback
**
** Description:     Receive device management events from stack.
**                  dmEvent: Device-management event ID.
**                  eventData: Data associated with event ID.
**
** Returns:         None
**
*******************************************************************************/
void nfaDeviceManagementCallback(uint8_t dmEvent,
                                 tNFA_DM_CBACK_DATA* eventData) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter; event=0x%X", __func__, dmEvent);

  switch (dmEvent) {
    case NFA_DM_ENABLE_EVT: /* Result of NFA_Enable */
    {
      SyncEventGuard guard(sNfaEnableEvent);
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_DM_ENABLE_EVT; status=0x%X", __func__, eventData->status);
      sIsNfaEnabled = eventData->status == NFA_STATUS_OK;
      sIsDisabling = false;
      sNfaEnableEvent.notifyOne();
    } break;

    case NFA_DM_DISABLE_EVT: /* Result of NFA_Disable */
    {
      SyncEventGuard guard(sNfaDisableEvent);
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_DM_DISABLE_EVT", __func__);
      sIsNfaEnabled = false;
      sIsDisabling = false;
      sNfaDisableEvent.notifyOne();
    } break;

    case NFA_DM_SET_CONFIG_EVT:  // result of NFA_SetConfig
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_DM_SET_CONFIG_EVT", __func__);
      {
        SyncEventGuard guard(sNfaSetConfigEvent);
        sNfaSetConfigEvent.notifyOne();
      }
      break;

    case NFA_DM_GET_CONFIG_EVT: /* Result of NFA_GetConfig */
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_DM_GET_CONFIG_EVT", __func__);
      {
        SyncEventGuard guard(sNfaGetConfigEvent);
        if (eventData->status == NFA_STATUS_OK &&
            eventData->get_config.tlv_size <= sizeof(sConfig)) {
          sCurrentConfigLen = eventData->get_config.tlv_size;
          memcpy(sConfig, eventData->get_config.param_tlvs,
                 eventData->get_config.tlv_size);
        } else {
          LOG(ERROR) << StringPrintf("%s: NFA_DM_GET_CONFIG failed", __func__);
          sCurrentConfigLen = 0;
        }
        sNfaGetConfigEvent.notifyOne();
      }
      break;

    case NFA_DM_RF_FIELD_EVT:
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_DM_RF_FIELD_EVT; status=0x%X; field status=%u", __func__,
          eventData->rf_field.status, eventData->rf_field.rf_field_status);
#if(NXP_EXTNS == TRUE)
      SecureElement::getInstance().notifyRfFieldEvent (
                    eventData->rf_field.rf_field_status == NFA_DM_RF_FIELD_ON);
#endif
if (!sP2pActive && eventData->rf_field.status == NFA_STATUS_OK) {
        struct nfc_jni_native_data* nat = getNative(NULL, NULL);
        JNIEnv* e = NULL;
        ScopedAttach attach(nat->vm, &e);
        if (e == NULL) {
          LOG(ERROR) << StringPrintf("jni env is null");
          return;
        }
        if (eventData->rf_field.rf_field_status == NFA_DM_RF_FIELD_ON)
          e->CallVoidMethod(nat->manager,
                            android::gCachedNfcManagerNotifyRfFieldActivated);
        else
          e->CallVoidMethod(nat->manager,
                            android::gCachedNfcManagerNotifyRfFieldDeactivated);
      }
      break;

    case NFA_DM_NFCC_TRANSPORT_ERR_EVT:
    case NFA_DM_NFCC_TIMEOUT_EVT: {
      if (dmEvent == NFA_DM_NFCC_TIMEOUT_EVT)
        LOG(ERROR) << StringPrintf("%s: NFA_DM_NFCC_TIMEOUT_EVT; abort",
                                   __func__);
      else if (dmEvent == NFA_DM_NFCC_TRANSPORT_ERR_EVT)
        LOG(ERROR) << StringPrintf("%s: NFA_DM_NFCC_TRANSPORT_ERR_EVT; abort",
                                   __func__);

      nativeNfcTag_abortWaits();
      NfcTag::getInstance().abort();
      sAbortConnlessWait = true;
      nativeLlcpConnectionlessSocket_abortWait();
      {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: aborting  sNfaEnableDisablePollingEvent", __func__);
        SyncEventGuard guard(sNfaEnableDisablePollingEvent);
        sNfaEnableDisablePollingEvent.notifyOne();
      }
      {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: aborting  sNfaEnableEvent", __func__);
        SyncEventGuard guard(sNfaEnableEvent);
        sNfaEnableEvent.notifyOne();
      }
      {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: aborting  sNfaDisableEvent", __func__);
        SyncEventGuard guard(sNfaDisableEvent);
        sNfaDisableEvent.notifyOne();
      }
      sDiscoveryEnabled = false;
      sPollingEnabled = false;
      PowerSwitch::getInstance().abort();

      if (!sIsDisabling && sIsNfaEnabled) {
        EXTNS_Close();
        NFA_Disable(FALSE);
        sIsDisabling = true;
      } else {
        sIsNfaEnabled = false;
        sIsDisabling = false;
      }
      PowerSwitch::getInstance().initialize(PowerSwitch::UNKNOWN_LEVEL);
      LOG(ERROR) << StringPrintf("%s: crash NFC service", __func__);
      //////////////////////////////////////////////
      // crash the NFC service process so it can restart automatically
      abort();
      //////////////////////////////////////////////
    } break;

    case NFA_DM_PWR_MODE_CHANGE_EVT:
      PowerSwitch::getInstance().deviceManagementCallback(dmEvent, eventData);
      break;
#if(NXP_EXTNS == TRUE)
      case NFA_DM_SET_TRANSIT_CONFIG_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("NFA_DM_SET_TRANSIT_CONFIG EVT cback received");
        SyncEventGuard guard(sNfaTransitConfigEvent);
        sNfaTransitConfigEvent.notifyOne();
        break;
      }
      case NFA_DM_GET_ROUTE_CONFIG_REVT: {
          RoutingManager::getInstance().processGetRoutingRsp(eventData);
        break;
      }
#endif
    case NFA_DM_SET_POWER_SUB_STATE_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_DM_SET_POWER_SUB_STATE_EVT; status=0x%X",
                          __FUNCTION__, eventData->power_sub_state.status);
      SyncEventGuard guard(sNfaSetPowerSubState);
      sNfaSetPowerSubState.notifyOne();
    } break;
    default:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: unhandled event", __func__);
      break;
  }
}
#if(NXP_EXTNS == TRUE)
static jintArray nfcManager_getActiveSecureElementList(JNIEnv *e, jobject o)
{
    (void)e;
    (void)o;
    return SecureElement::getInstance().getActiveSecureElementList(e);
}
#endif
/*******************************************************************************
**
** Function:        nfcManager_sendRawFrame
**
** Description:     Send a raw frame.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_sendRawFrame(JNIEnv* e, jobject, jbyteArray data) {
#if (NXP_EXTNS == TRUE)
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: nfcManager_sendRawFrame", __func__);
  size_t bufLen;
  uint8_t* buf = NULL;
  if (data !=NULL) {
    ScopedByteArrayRO bytes(e, data);
    buf = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
    bufLen = bytes.size();
  } else {
    /*Fix for Felica on Host for Empty NCI packet handling*/
    bufLen = 0x00;
  }
#else
  ScopedByteArrayRO bytes(e, data);
  uint8_t* buf =
      const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
  size_t bufLen = bytes.size();
#endif
  tNFA_STATUS status = NFA_SendRawFrame(buf, bufLen, 0);

  return (status == NFA_STATUS_OK);
}

#if(NXP_EXTNS == TRUE)
/*******************************************************************************
**
** Function:        nfcManager_setRoutingEntry
**
** Description:     Set the routing entry in routing table
**                  e: JVM environment.
**                  o: Java object.
**                  type: technology or protocol routing
**                       0x01 - Technology
**                       0x02 - Protocol
**                  value: technology /protocol value
**                  route: routing destination
**                       0x00 : Device Host
**                       0x01 : ESE
**                       0x02 : UICC
**                  power: power state for the routing entry
*******************************************************************************/

static jboolean nfcManager_setRoutingEntry (JNIEnv*, jobject, jint type, jint value, jint route, jint power)
{
    jboolean result = false;

    result = RoutingManager::getInstance().setRoutingEntry(type, value, route, power);
    return result;
}

/*******************************************************************************
**
** Function:        nfcManager_clearRoutingEntry
**
** Description:     Set the routing entry in routing table
**                  e: JVM environment.
**                  o: Java object.
**                  type:technology/protocol/aid clear routing
**
*******************************************************************************/

static jboolean nfcManager_clearRoutingEntry (JNIEnv*, jobject, jint type)
{
    jboolean result = false;
    //checkRecreatePipe(); TODO
    result = RoutingManager::getInstance().clearRoutingEntry(type);
    return result;
}

/*******************************************************************************
**
** Function:        nfcManager_clearRoutingEntry
**
** Description:     Set the routing entry in routing table
**                  e: JVM environment.
**                  o: Java object.
**                  type:technology/protocol/aid clear routing
**
*******************************************************************************/

static void nfcManager_setEmptyAidRoute (JNIEnv*, jobject, jint route)
{
    RoutingManager::getInstance().setEmptyAidEntry(route);
    return;
}

/*******************************************************************************
**
** Function:        nfcFwUpdateStatusCallback
**
** Description:     This callback shall be registered to libnfc-nci by
**                  nfcManager_doDownload
**
** Params:          status: 1 -> FW update start, 2 -> FW update success,
**                                     3 -> FW update failed.
** Returns:         void.
**
*******************************************************************************/
static void nfcFwUpdateStatusCallback(uint8_t status) {
  LOG(INFO) << StringPrintf("nfcFwUpdateStatusCallback Enter status = %u", status);
  NativeJniExtns::getInstance().notifyNfcEvent("nfcFwDwnldStatus", (void *)&status);
}

#endif

/*******************************************************************************
**
** Function:        nfcManager_routeAid
**
** Description:     Route an AID to an EE
**                  e: JVM environment.
**                  aid: aid to be added to routing table.
**                  route: aid route location. i.e. DH/eSE/UICC
**                  aidInfo: prefix or suffix aid.
**
** Returns:         True if aid is accpted by NFA Layer.
**
*******************************************************************************/
#if (NXP_EXTNS == TRUE)
static jboolean nfcManager_routeAid(JNIEnv* e, jobject, jbyteArray aid,
                                    jint route, jint aidInfo, jint power) {
#else
static jboolean nfcManager_routeAid(JNIEnv* e, jobject, jbyteArray aid,
                                    jint route, jint aidInfo) {
#endif
  uint8_t* buf;
  size_t bufLen;
  ScopedByteArrayRO bytes(e);
  if (aid == NULL) {
    buf = NULL;
    bufLen = 0;
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("nfcManager_routeAid:  NULL");
  } else {
    bytes.reset(aid);
    buf = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
    bufLen = bytes.size();
  }
#if (NXP_EXTNS == TRUE)
  SecureElement& se = SecureElement::getInstance();
  if ((!isDynamicUiccEnabled) &&
      (route == se.UICC_ID || route == se.UICC2_ID)) {  // UICC or UICC2 HANDLE
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("sCurrentSelectedUICCSlot:  %d ::: route: %d",
                        sCurrentSelectedUICCSlot, route);
    /* If current slot is 0x01 and UICC_ID is 0x02 then route location should be
     * updated to UICC_ID(0x02) else if current slot is 0x02 and UICC_ID is 0x02
     * then route location should be updated to UICC_ID2(0x04).
     */
    route = (sCurrentSelectedUICCSlot != se.UICC_ID) ? se.UICC_ID : se.UICC2_ID;
  }
    return RoutingManager::getInstance().addAidRouting(buf, bufLen, route,
                                                     aidInfo, power);
#else
  return RoutingManager::getInstance().addAidRouting(buf, bufLen, route,
                                                     aidInfo);
#endif
}

/*******************************************************************************
**
** Function:        nfcManager_unrouteAid
**
** Description:     Remove a AID routing
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_unrouteAid(JNIEnv* e, jobject, jbyteArray aid) {
  uint8_t* buf;
  size_t bufLen;

  if (aid == NULL) {
    buf = NULL;
    bufLen = 0;
  } else {
    ScopedByteArrayRO bytes(e, aid);
    buf = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
    bufLen = bytes.size();
  }
  bool result = RoutingManager::getInstance().removeAidRouting(buf, bufLen);
  return result;
}

/*******************************************************************************
**
** Function:        nfcManager_commitRouting
**
** Description:     Sends the AID routing table to the controller
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_commitRouting(JNIEnv* e, jobject) {
#if (NXP_EXTNS == TRUE)
  bool status = false;
  SecureElement& se = SecureElement::getInstance();

  if (se.isRfFieldOn() || se.mActivatedInListenMode) {
    /* Delay is required to avoid update routing during RF Field session*/
    usleep(1000 * 1000);
  }
  if (sIsDisabling || !sIsNfaEnabled) {
    return status;
  }
  if (sRfEnabled) {
    /*Stop RF discovery to reconfigure*/
    startRfDiscovery(false);
  }
  NativeJniExtns::getInstance().notifyNfcEvent(__func__);
  LOG(ERROR) << StringPrintf("commitRouting here");
  status = RoutingManager::getInstance().commitRouting();
  NativeJniExtns::getInstance().notifyNfcEvent("checkIsodepRouting");

  if (!sRfEnabled) {
    /*Stop RF discovery to reconfigure*/
    startRfDiscovery(true);
  }
  return status;
#else
  return RoutingManager::getInstance().commitRouting();
#endif
}

/*******************************************************************************
**
** Function:        nfcManager_doRegisterT3tIdentifier
**
** Description:     Registers LF_T3T_IDENTIFIER for NFC-F.
**                  e: JVM environment.
**                  o: Java object.
**                  t3tIdentifier: LF_T3T_IDENTIFIER value (10 or 18 bytes)
**
** Returns:         Handle retrieve from RoutingManager.
**
*******************************************************************************/
static jint nfcManager_doRegisterT3tIdentifier(JNIEnv* e, jobject,
                                               jbyteArray t3tIdentifier) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

  ScopedByteArrayRO bytes(e, t3tIdentifier);
  uint8_t* buf =
      const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
  size_t bufLen = bytes.size();
  int handle = RoutingManager::getInstance().registerT3tIdentifier(buf, bufLen);

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: handle=%d", __func__, handle);
  if (handle != NFA_HANDLE_INVALID)
    RoutingManager::getInstance().commitRouting();
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);

  return handle;
}

/*******************************************************************************
**
** Function:        nfcManager_doDeregisterT3tIdentifier
**
** Description:     Deregisters LF_T3T_IDENTIFIER for NFC-F.
**                  e: JVM environment.
**                  o: Java object.
**                  handle: Handle retrieve from libnfc-nci.
**
** Returns:         None
**
*******************************************************************************/
static void nfcManager_doDeregisterT3tIdentifier(JNIEnv*, jobject,
                                                 jint handle) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter; handle=%d", __func__, handle);

  RoutingManager::getInstance().deregisterT3tIdentifier(handle);
  RoutingManager::getInstance().commitRouting();

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
}

/*******************************************************************************
**
** Function:        nfcManager_getLfT3tMax
**
** Description:     Returns LF_T3T_MAX value.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         LF_T3T_MAX value.
**
*******************************************************************************/
static jint nfcManager_getLfT3tMax(JNIEnv*, jobject) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("LF_T3T_MAX=%d", sLfT3tMax);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);

  return sLfT3tMax;
}

/*******************************************************************************
**
** Function:        nfcManager_doInitialize
**
** Description:     Turn on NFC.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_doInitialize(JNIEnv* e, jobject o) {
#if (NXP_EXTNS == TRUE)
  tNFA_MW_VERSION mwVer;
#endif
  initializeGlobalDebugEnabledFlag();
  tNFA_STATUS stat = NFA_STATUS_OK;

  PowerSwitch& powerSwitch = PowerSwitch::getInstance();

  if (sIsNfaEnabled) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: already enabled", __func__);
    goto TheEnd;
  }
#if (NXP_EXTNS == TRUE)
    mwVer=  NFA_GetMwVersion();
    LOG(ERROR) << StringPrintf(
        "%s:  MW Version: NFC_AR_%02X_%04X_%02d.%02x.%02x_RC%0x", __func__,
        mwVer.cust_id, mwVer.validation, mwVer.android_version,
        mwVer.major_version, mwVer.minor_version,mwVer.rc_version);

    if (NfcConfig::hasKey(NAME_NXP_DUAL_UICC_ENABLE)) {
      isDynamicUiccEnabled = NfcConfig::getUnsigned(NAME_NXP_DUAL_UICC_ENABLE);
      isDynamicUiccEnabled = (isDynamicUiccEnabled == 0x01 ? true : false);
    } else
      isDynamicUiccEnabled = true;

#endif
  powerSwitch.initialize(PowerSwitch::FULL_POWER);

  {

    NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
    theInstance.Initialize();  // start GKI, NCI task, NFC task

    {
      SyncEventGuard guard(sNfaEnableEvent);
      tHAL_NFC_ENTRY* halFuncEntries = theInstance.GetHalEntryFuncs();

      NFA_Init(halFuncEntries);
#if (NXP_EXTNS == TRUE)
      NativeJniExtns::getInstance().initializeNativeData(getNative(e, o));
      stat = theInstance.DownloadFirmware(nfcFwUpdateStatusCallback, true);
#endif
      stat = NFA_Enable(nfaDeviceManagementCallback, nfaConnectionCallback);
      if (stat == NFA_STATUS_OK) {
        sNfaEnableEvent.wait();  // wait for NFA command to finish
      }
      EXTNS_Init(nfaDeviceManagementCallback, nfaConnectionCallback);
    }

    if (stat == NFA_STATUS_OK) {
      // sIsNfaEnabled indicates whether stack started successfully
      if (sIsNfaEnabled) {
#if (NXP_EXTNS == TRUE)
        SecureElement::getInstance().initialize (getNative(e, o));
#endif
        sRoutingInitialized =
            RoutingManager::getInstance().initialize(getNative(e, o));
        nativeNfcTag_registerNdefTypeHandler();
        NfcTag::getInstance().initialize(getNative(e, o));
        PeerToPeer::getInstance().initialize();
        PeerToPeer::getInstance().handleNfcOnOff(true);
        HciEventManager::getInstance().initialize(getNative(e, o));
#if(NXP_EXTNS == TRUE)
        MposManager::getInstance().initialize(getNative(e, o));
#endif
        /////////////////////////////////////////////////////////////////////////////////
        // Add extra configuration here (work-arounds, etc.)

        if (gIsDtaEnabled == true) {
          uint8_t configData = 0;
          configData = 0x01; /* Poll NFC-DEP : Highest Available Bit Rates */
          NFA_SetConfig(NCI_PARAM_ID_BITR_NFC_DEP, sizeof(uint8_t),
                        &configData);
          configData = 0x0B; /* Listen NFC-DEP : Waiting Time */
          NFA_SetConfig(NFC_PMID_WT, sizeof(uint8_t), &configData);
          configData = 0x0F; /* Specific Parameters for NFC-DEP RF Interface */
          NFA_SetConfig(NCI_PARAM_ID_NFC_DEP_OP, sizeof(uint8_t), &configData);
        }

        struct nfc_jni_native_data* nat = getNative(e, o);
        if (nat) {
          nat->tech_mask =
              NfcConfig::getUnsigned(NAME_POLLING_TECH_MASK, DEFAULT_TECH_MASK);
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
              "%s: tag polling tech mask=0x%X", __func__, nat->tech_mask);
        }

        // if this value exists, set polling interval.
        nat->discovery_duration = NfcConfig::getUnsigned(
            NAME_NFA_DM_DISC_DURATION_POLL, DEFAULT_DISCOVERY_DURATION);

        NFA_SetRfDiscoveryDuration(nat->discovery_duration);

        // get LF_T3T_MAX
        {
          SyncEventGuard guard(sNfaGetConfigEvent);
          tNFA_PMID configParam[1] = {NCI_PARAM_ID_LF_T3T_MAX};
          stat = NFA_GetConfig(1, configParam);
          if (stat == NFA_STATUS_OK) {
            sNfaGetConfigEvent.wait();
            if (sCurrentConfigLen >= 4 ||
                sConfig[1] == NCI_PARAM_ID_LF_T3T_MAX) {
              DLOG_IF(INFO, nfc_debug_enabled)
                  << StringPrintf("%s: lfT3tMax=%d", __func__, sConfig[3]);
              sLfT3tMax = sConfig[3];
            }
          }
        }

#if (NXP_EXTNS==TRUE)
        prevScreenState = NFA_SCREEN_STATE_UNKNOWN;
#else
        prevScreenState = NFA_SCREEN_STATE_OFF_LOCKED;
#endif

        // Do custom NFCA startup configuration.
        doStartupConfig();
        goto TheEnd;
      }
    }

    LOG(ERROR) << StringPrintf("%s: fail nfa enable; error=0x%X", __func__,
                               stat);

    if (sIsNfaEnabled) {
      EXTNS_Close();
      stat = NFA_Disable(FALSE /* ungraceful */);
    }

    theInstance.Finalize();
  }

TheEnd:
  if (sIsNfaEnabled)
    PowerSwitch::getInstance().setLevel(PowerSwitch::LOW_POWER);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return sIsNfaEnabled ? JNI_TRUE : JNI_FALSE;
}

static void nfcManager_doEnableDtaMode(JNIEnv*, jobject) {
  gIsDtaEnabled = true;
}

static void nfcManager_doDisableDtaMode(JNIEnv*, jobject) {
  gIsDtaEnabled = false;
}

static void nfcManager_doFactoryReset(JNIEnv*, jobject) {
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  theInstance.FactoryReset();
}

static void nfcManager_doShutdown(JNIEnv*, jobject) {
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
#if (NXP_EXTNS == TRUE)
  handleWiredmode(true); /* Device off*/
#endif
  theInstance.DeviceShutdown();
}
/*******************************************************************************
**
** Function:        nfcManager_enableDiscovery
**
** Description:     Start polling and listening for devices.
**                  e: JVM environment.
**                  o: Java object.
**                  technologies_mask: the bitmask of technologies for which to
*enable discovery
**                  enable_lptd: whether to enable low power polling (default:
*false)
**
** Returns:         None
**
*******************************************************************************/
static void nfcManager_enableDiscovery(JNIEnv* e, jobject o,
                                       jint technologies_mask,
                                       jboolean enable_lptd,
                                       jboolean reader_mode,
                                       jboolean enable_host_routing,
                                       jboolean enable_p2p, jboolean restart) {
  tNFA_TECHNOLOGY_MASK tech_mask = DEFAULT_TECH_MASK;
  struct nfc_jni_native_data* nat = getNative(e, o);
#if(NXP_EXTNS == TRUE)
  storeLastDiscoveryParams(technologies_mask, enable_lptd,
        reader_mode, enable_host_routing ,enable_p2p, restart);
  tNFA_STATUS status = NFA_STATUS_OK;
  tNFA_TECHNOLOGY_MASK etsi_tech_mask = 0;
    if((nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) &&
            (MposManager::getInstance().getEtsiReaederState() == STATE_SE_RDR_MODE_STARTED)) {
        DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter STATE_SE_RDR_MODE_START_CONFIG", __func__);
        Rdr_req_ntf_info_t mSwp_info = MposManager::getInstance().getSwpRrdReqInfo();
        {
            SyncEventGuard guard (android::sNfaEnableDisablePollingEvent);
            DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: disable polling", __func__);
            status = NFA_DisablePolling ();
            if (status == NFA_STATUS_OK)
            {
                android::sNfaEnableDisablePollingEvent.wait (); //wait for NFA_POLL_DISABLED_EVT
            }
            else
            {
                DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: fail disable polling; error=0x%X", __func__, status);
            }
        }

        if(mSwp_info.swp_rd_req_info.tech_mask & NFA_TECHNOLOGY_MASK_A)
            etsi_tech_mask |= NFA_TECHNOLOGY_MASK_A;
        if(mSwp_info.swp_rd_req_info.tech_mask & NFA_TECHNOLOGY_MASK_B)
            etsi_tech_mask |= NFA_TECHNOLOGY_MASK_B;

        {
            SyncEventGuard guard (android::sNfaEnableDisablePollingEvent);
            status = NFA_EnablePolling (etsi_tech_mask);
            if (status == NFA_STATUS_OK)
            {
                DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: wait for enable event", __func__);
                android::sNfaEnableDisablePollingEvent.wait (); //wait for NFA_POLL_ENABLED_EVT
            }
            else
            {
                DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: fail enable polling; error=0x%X", __func__, status);
            }
        }
        startRfDiscovery (true);
        return;
    }
#endif
  if (technologies_mask == -1 && nat)
    tech_mask = (tNFA_TECHNOLOGY_MASK)nat->tech_mask;
  else if (technologies_mask != -1)
    tech_mask = (tNFA_TECHNOLOGY_MASK)technologies_mask;

#if (NXP_EXTNS == TRUE)
  uint8_t default_tech_mask =
      NfcConfig::getUnsigned(NAME_POLLING_TECH_MASK, DEFAULT_TECH_MASK);
  tech_mask &= default_tech_mask;
#endif

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter; tech_mask = %02x", __func__, tech_mask);

  if (sDiscoveryEnabled && !restart) {
    LOG(ERROR) << StringPrintf("%s: already discovering", __func__);
    return;
  }

  PowerSwitch::getInstance().setLevel(PowerSwitch::FULL_POWER);

  if (sRfEnabled) {
    // Stop RF discovery to reconfigure
    startRfDiscovery(false);
  }

  // Check polling configuration
  if (tech_mask != 0) {
    stopPolling_rfDiscoveryDisabled();
#if (NXP_EXTNS==FALSE)
    enableDisableLptd(enable_lptd);
#endif
    startPolling_rfDiscoveryDisabled(tech_mask);

    // Start P2P listening if tag polling was enabled
    if (sPollingEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: Enable p2pListening", __func__);

      if (enable_p2p && !sP2pEnabled) {
        sP2pEnabled = true;
        PeerToPeer::getInstance().enableP2pListening(true);
        NFA_ResumeP2p();
      } else if (!enable_p2p && sP2pEnabled) {
        sP2pEnabled = false;
        PeerToPeer::getInstance().enableP2pListening(false);
        NFA_PauseP2p();
      }

      if (reader_mode && !sReaderModeEnabled) {
        sReaderModeEnabled = true;
        NFA_DisableListening();
        NFA_SetRfDiscoveryDuration(READER_MODE_DISCOVERY_DURATION);
      } else if (!reader_mode && sReaderModeEnabled) {
        struct nfc_jni_native_data* nat = getNative(e, o);
        sReaderModeEnabled = false;
        NFA_EnableListening();
        NFA_SetRfDiscoveryDuration(nat->discovery_duration);
      }
    }
  } else {
    // No technologies configured, stop polling
    stopPolling_rfDiscoveryDisabled();
  }

  // Check listen configuration

#if (NXP_EXTNS != TRUE)
  if (enable_host_routing) {
    RoutingManager::getInstance().enableRoutingToHost();
    RoutingManager::getInstance().commitRouting();
  } else {
    RoutingManager::getInstance().disableRoutingToHost();
    RoutingManager::getInstance().commitRouting();
  }
#endif
  // Actually start discovery.
  startRfDiscovery(true);
  sDiscoveryEnabled = true;

  PowerSwitch::getInstance().setModeOn(PowerSwitch::DISCOVERY);

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
}

/*******************************************************************************
**
** Function:        nfcManager_disableDiscovery
**
** Description:     Stop polling and listening for devices.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         None
**
*******************************************************************************/
void nfcManager_disableDiscovery(JNIEnv* e, jobject o) {
  tNFA_STATUS status = NFA_STATUS_OK;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter;", __func__);

  pn544InteropAbortNow();
#if(NXP_EXTNS == TRUE)
    if(nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
        if(MposManager::getInstance().getEtsiReaederState() == STATE_SE_RDR_MODE_START_IN_PROGRESS)
        {
            Rdr_req_ntf_info_t mSwp_info = MposManager::getInstance().getSwpRrdReqInfo();
            //        if(android::isDiscoveryStarted() == true)
            android::startRfDiscovery(false);
            goto TheEnd;
        }
        else if(MposManager::getInstance().getEtsiReaederState() == STATE_SE_RDR_MODE_STOP_IN_PROGRESS)
        {
            android::startRfDiscovery(false);
            goto TheEnd;
        }
    }
#endif
  if (sDiscoveryEnabled == false) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: already disabled", __func__);
    goto TheEnd;
  }

  // Stop RF Discovery.
  startRfDiscovery(false);

  if (sPollingEnabled) status = stopPolling_rfDiscoveryDisabled();

  PeerToPeer::getInstance().enableP2pListening(false);
  sP2pEnabled = false;
  sDiscoveryEnabled = false;
  // if nothing is active after this, then tell the controller to power down
  if (!PowerSwitch::getInstance().setModeOff(PowerSwitch::DISCOVERY))
    PowerSwitch::getInstance().setLevel(PowerSwitch::LOW_POWER);
TheEnd:
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
}
#if (NXP_EXTNS==FALSE)
void enableDisableLptd(bool enable) {
  // This method is *NOT* thread-safe. Right now
  // it is only called from the same thread so it's
  // not an issue.
  static bool sCheckedLptd = false;
  static bool sHasLptd = false;

  tNFA_STATUS stat = NFA_STATUS_OK;
  if (!sCheckedLptd) {
    sCheckedLptd = true;
    SyncEventGuard guard(sNfaGetConfigEvent);
    tNFA_PMID configParam[1] = {NCI_PARAM_ID_TAGSNIFF_CFG};
    stat = NFA_GetConfig(1, configParam);
    if (stat != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: NFA_GetConfig failed", __func__);
      return;
    }
    sNfaGetConfigEvent.wait();
    if (sCurrentConfigLen < 4 || sConfig[1] != NCI_PARAM_ID_TAGSNIFF_CFG) {
      LOG(ERROR) << StringPrintf(
          "%s: Config TLV length %d returned is too short", __func__,
          sCurrentConfigLen);
      return;
    }
    if (sConfig[3] == 0) {
      LOG(ERROR) << StringPrintf(
          "%s: LPTD is disabled, not enabling in current config", __func__);
      return;
    }
    sHasLptd = true;
  }
  // Bail if we checked and didn't find any LPTD config before
  if (!sHasLptd) return;
  uint8_t enable_byte = enable ? 0x01 : 0x00;

  SyncEventGuard guard(sNfaSetConfigEvent);

  stat = NFA_SetConfig(NCI_PARAM_ID_TAGSNIFF_CFG, 1, &enable_byte);
  if (stat == NFA_STATUS_OK)
    sNfaSetConfigEvent.wait();
  else
    LOG(ERROR) << StringPrintf("%s: Could not configure LPTD feature",
                               __func__);
  return;
}
#endif
/*******************************************************************************
**
** Function:        nfcManager_doCreateLlcpServiceSocket
**
** Description:     Create a new LLCP server socket.
**                  e: JVM environment.
**                  o: Java object.
**                  nSap: Service access point.
**                  sn: Service name
**                  miu: Maximum information unit.
**                  rw: Receive window size.
**                  linearBufferLength: Max buffer size.
**
** Returns:         NativeLlcpServiceSocket Java object.
**
*******************************************************************************/
static jobject nfcManager_doCreateLlcpServiceSocket(JNIEnv* e, jobject,
                                                    jint nSap, jstring sn,
                                                    jint miu, jint rw,
                                                    jint linearBufferLength) {
  PeerToPeer::tJNI_HANDLE jniHandle =
      PeerToPeer::getInstance().getNewJniHandle();

  ScopedUtfChars serviceName(e, sn);

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s: enter: sap=%i; name=%s; miu=%i; rw=%i; buffLen=%i", __func__, nSap,
      serviceName.c_str(), miu, rw, linearBufferLength);

  /* Create new NativeLlcpServiceSocket object */
  jobject serviceSocket = NULL;
  if (nfc_jni_cache_object_local(e, gNativeLlcpServiceSocketClassName,
                                 &(serviceSocket)) == -1) {
    LOG(ERROR) << StringPrintf("%s: Llcp socket object creation error",
                               __func__);
    return NULL;
  }

  /* Get NativeLlcpServiceSocket class object */
  ScopedLocalRef<jclass> clsNativeLlcpServiceSocket(
      e, e->GetObjectClass(serviceSocket));
  if (e->ExceptionCheck()) {
    e->ExceptionClear();
    LOG(ERROR) << StringPrintf("%s: Llcp Socket get object class error",
                               __func__);
    return NULL;
  }

  if (!PeerToPeer::getInstance().registerServer(jniHandle,
                                                serviceName.c_str())) {
    LOG(ERROR) << StringPrintf("%s: RegisterServer error", __func__);
    return NULL;
  }

  jfieldID f;

  /* Set socket handle to be the same as the NfaHandle*/
  f = e->GetFieldID(clsNativeLlcpServiceSocket.get(), "mHandle", "I");
  e->SetIntField(serviceSocket, f, (jint)jniHandle);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: socket Handle = 0x%X", __func__, jniHandle);

  /* Set socket linear buffer length */
  f = e->GetFieldID(clsNativeLlcpServiceSocket.get(),
                    "mLocalLinearBufferLength", "I");
  e->SetIntField(serviceSocket, f, (jint)linearBufferLength);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: buffer length = %d", __func__, linearBufferLength);

  /* Set socket MIU */
  f = e->GetFieldID(clsNativeLlcpServiceSocket.get(), "mLocalMiu", "I");
  e->SetIntField(serviceSocket, f, (jint)miu);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: MIU = %d", __func__, miu);

  /* Set socket RW */
  f = e->GetFieldID(clsNativeLlcpServiceSocket.get(), "mLocalRw", "I");
  e->SetIntField(serviceSocket, f, (jint)rw);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s:  RW = %d", __func__, rw);

  sLastError = 0;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return serviceSocket;
}

/*******************************************************************************
**
** Function:        nfcManager_doGetLastError
**
** Description:     Get the last error code.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Last error code.
**
*******************************************************************************/
static jint nfcManager_doGetLastError(JNIEnv*, jobject) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: last error=%i", __func__, sLastError);
  return sLastError;
}

/*******************************************************************************
**
** Function:        nfcManager_doDeinitialize
**
** Description:     Turn off NFC.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_doDeinitialize(JNIEnv*, jobject) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

#if (NXP_EXTNS == TRUE)
  NativeJniExtns::getInstance().notifyNfcEvent(__func__);
  handleWiredmode(false); /* Nfc Off*/
#endif
  sIsDisabling = true;

  pn544InteropAbortNow();
  RoutingManager::getInstance().onNfccShutdown();
  PowerSwitch::getInstance().initialize(PowerSwitch::UNKNOWN_LEVEL);
  HciEventManager::getInstance().finalize();
#if (NXP_EXTNS == TRUE)
  SecureElement::getInstance().releasePendingTransceive();
#endif
  if (sIsNfaEnabled) {
    SyncEventGuard guard(sNfaDisableEvent);
    EXTNS_Close();
    tNFA_STATUS stat = NFA_Disable(TRUE /* graceful */);
    if (stat == NFA_STATUS_OK) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: wait for completion", __func__);
      sNfaDisableEvent.wait();  // wait for NFA command to finish
      PeerToPeer::getInstance().handleNfcOnOff(false);
    } else {
      LOG(ERROR) << StringPrintf("%s: fail disable; error=0x%X", __func__,
                                 stat);
    }
  }
  nativeNfcTag_abortWaits();
  NfcTag::getInstance().abort();
  sAbortConnlessWait = true;
  nativeLlcpConnectionlessSocket_abortWait();
  sIsNfaEnabled = false;
  sDiscoveryEnabled = false;
  sPollingEnabled = false;
  sIsDisabling = false;
  sP2pEnabled = false;
  gActivated = false;
  sLfT3tMax = 0;

  {
    // unblock NFA_EnablePolling() and NFA_DisablePolling()
    SyncEventGuard guard(sNfaEnableDisablePollingEvent);
    sNfaEnableDisablePollingEvent.notifyOne();
  }
#if (NXP_EXTNS == TRUE)
  /*Disable Field Detect Mode if enabled*/
  if (NFA_IsFieldDetectEnabled()) NFA_SetFieldDetectMode(false);
  SecureElement::getInstance().finalize ();
#endif
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  theInstance.Finalize();

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return JNI_TRUE;
}

/*******************************************************************************
**
** Function:        nfcManager_doCreateLlcpSocket
**
** Description:     Create a LLCP connection-oriented socket.
**                  e: JVM environment.
**                  o: Java object.
**                  nSap: Service access point.
**                  miu: Maximum information unit.
**                  rw: Receive window size.
**                  linearBufferLength: Max buffer size.
**
** Returns:         NativeLlcpSocket Java object.
**
*******************************************************************************/
static jobject nfcManager_doCreateLlcpSocket(JNIEnv* e, jobject, jint nSap,
                                             jint miu, jint rw,
                                             jint linearBufferLength) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter; sap=%d; miu=%d; rw=%d; buffer len=%d",
                      __func__, nSap, miu, rw, linearBufferLength);

  PeerToPeer::tJNI_HANDLE jniHandle =
      PeerToPeer::getInstance().getNewJniHandle();
  PeerToPeer::getInstance().createClient(jniHandle, miu, rw);

  /* Create new NativeLlcpSocket object */
  jobject clientSocket = NULL;
  if (nfc_jni_cache_object_local(e, gNativeLlcpSocketClassName,
                                 &(clientSocket)) == -1) {
    LOG(ERROR) << StringPrintf("%s: fail Llcp socket creation", __func__);
    return clientSocket;
  }

  /* Get NativeConnectionless class object */
  ScopedLocalRef<jclass> clsNativeLlcpSocket(e,
                                             e->GetObjectClass(clientSocket));
  if (e->ExceptionCheck()) {
    e->ExceptionClear();
    LOG(ERROR) << StringPrintf("%s: fail get class object", __func__);
    return clientSocket;
  }

  jfieldID f;

  /* Set socket SAP */
  f = e->GetFieldID(clsNativeLlcpSocket.get(), "mSap", "I");
  e->SetIntField(clientSocket, f, (jint)nSap);

  /* Set socket handle */
  f = e->GetFieldID(clsNativeLlcpSocket.get(), "mHandle", "I");
  e->SetIntField(clientSocket, f, (jint)jniHandle);

  /* Set socket MIU */
  f = e->GetFieldID(clsNativeLlcpSocket.get(), "mLocalMiu", "I");
  e->SetIntField(clientSocket, f, (jint)miu);

  /* Set socket RW */
  f = e->GetFieldID(clsNativeLlcpSocket.get(), "mLocalRw", "I");
  e->SetIntField(clientSocket, f, (jint)rw);

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return clientSocket;
}

/*******************************************************************************
**
** Function:        nfcManager_doCreateLlcpConnectionlessSocket
**
** Description:     Create a connection-less socket.
**                  e: JVM environment.
**                  o: Java object.
**                  nSap: Service access point.
**                  sn: Service name.
**
** Returns:         NativeLlcpConnectionlessSocket Java object.
**
*******************************************************************************/
static jobject nfcManager_doCreateLlcpConnectionlessSocket(JNIEnv*, jobject,
                                                           jint nSap,
                                                           jstring /*sn*/) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: nSap=0x%X", __func__, nSap);
  return NULL;
}
/*******************************************************************************
**
** Function:        nfcManager_getDefaultAidRoute
**
** Description:     Get the default Aid Route Entry.
**                  e: JVM environment.
**                  o: Java object.
**                  mode: Not used.
**
** Returns:         None
**
*******************************************************************************/
static jint nfcManager_getDefaultAidRoute(JNIEnv* e, jobject o) {
  unsigned long num = 0;
  if (NfcConfig::hasKey(NAME_DEFAULT_AID_ROUTE))
    num = NfcConfig::getUnsigned(NAME_DEFAULT_AID_ROUTE);

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: num %lx", __func__, num);
  if(num != SecureElement::DH_ID) {
    tNFA_HANDLE defaultRouteHandle = SecureElement::getInstance().getEseHandleFromGenericId(num) & ~NFA_HANDLE_GROUP_EE;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: defaultRouteHandle %hx", __func__, defaultRouteHandle);
    bool  found = false;
    tNFA_HANDLE ee_handleList[nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED];
    uint8_t count = 0;
    SecureElement::getInstance().getEeHandleList(ee_handleList, &count);
    for (int  i = 0; i < count; i++) {
      if(ee_handleList[i] == defaultRouteHandle) {
        DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: Default AID Route handle found 0x %hx", __func__, defaultRouteHandle);
        found = true;
        break;
      }
    }
    if(!found) {
      DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf(" Default AID Route handle not found , DH ");
      num = SecureElement::DH_ID;
    }
  }

  DLOG_IF(INFO, nfc_debug_enabled)
    << StringPrintf("%s route = %lx",__func__, num);
  return num;
}
/*******************************************************************************
**
** Function:        nfcManager_getDefaultDesfireRoute
**
** Description:     Get the default Desfire Route Entry.
**                  e: JVM environment.
**                  o: Java object.
**                  mode: Not used.
**
** Returns:         None
**
*******************************************************************************/
  static jint nfcManager_getDefaultDesfireRoute(JNIEnv* e, jobject o) {
    unsigned long num = 0;
    if (NfcConfig::hasKey(NAME_DEFAULT_ISODEP_ROUTE))
      num = NfcConfig::getUnsigned(NAME_DEFAULT_ISODEP_ROUTE);
    return num;
  }

/*******************************************************************************
 **
 ** Function:        getConfig
 **
 ** Description:     read the config values from NFC controller.
 **
 ** Returns:         SUCCESS/FAILURE
 **
 *******************************************************************************/
tNFA_STATUS getConfig(uint16_t* rspLen, uint8_t* configValue, uint8_t numParam,
                      tNFA_PMID* param) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  if (rspLen == NULL || configValue == NULL || param == NULL)
    return NFA_STATUS_FAILED;
  SyncEventGuard guard(sNfaGetConfigEvent);
  status = NFA_GetConfig(numParam, param);
  if (status == NFA_STATUS_OK) {
    if (sNfaGetConfigEvent.wait(WIRED_MODE_TRANSCEIVE_TIMEOUT) == false) {
      *rspLen = 0;
    } else {
      *rspLen = sCurrentConfigLen;
      memcpy(configValue, sConfig, sCurrentConfigLen);
    }
  } else {
    *rspLen = 0;
  }
  return status;
}
/*******************************************************************************
**
** Function:        nfcManager_getDefaultMifareCLTRoute
**
** Description:     Get the default mifare CLT Route Entry.
**                  e: JVM environment.
**                  o: Java object.
**                  mode: Not used.
**
** Returns:         None
**
*******************************************************************************/
  static jint nfcManager_getDefaultMifareCLTRoute(JNIEnv* e, jobject o) {
    unsigned long num = 0;
    if (NfcConfig::hasKey(NAME_DEFAULT_MIFARE_CLT_ROUTE))
      num = NfcConfig::getUnsigned(NAME_DEFAULT_MIFARE_CLT_ROUTE);
    return num;
  }
#if(NXP_EXTNS == TRUE)
/*******************************************************************************
**
** Function:        nfcManager_getDefaultFelicaCLTPowerState
**
** Description:     Get the default mifare CLT Power States.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Power State
**
*******************************************************************************/
  static jint nfcManager_getDefaultFelicaCLTPowerState(JNIEnv* e, jobject o) {
    unsigned long num = 0;
    if (NfcConfig::hasKey(NAME_DEFAULT_FELICA_CLT_PWR_STATE))
      num = NfcConfig::getUnsigned(NAME_DEFAULT_FELICA_CLT_PWR_STATE);
    return num;
  }
/*******************************************************************************
**
** Function:        nfcManager_getDefaultFelicaCLTRoute
**
** Description:     Get the default mifare CLT Route Entry.
**                  e: JVM environment.
**                  o: Java object.
**                  mode: Not used.
**
** Returns:         None
**
*******************************************************************************/
  static jint nfcManager_getDefaultFelicaCLTRoute(JNIEnv* e, jobject o) {
    unsigned long num = 0;
    if (NfcConfig::hasKey(NAME_DEFAULT_FELICA_CLT_ROUTE))
      num = NfcConfig::getUnsigned(NAME_DEFAULT_FELICA_CLT_ROUTE);
    return num;
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_SetFieldDetectMode
  **
  ** Description:     Updates field detect mode ENABLE/DISABLE
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         Update status
  **
  *******************************************************************************/
  static field_detect_status_t nfcManager_SetFieldDetectMode(JNIEnv*, jobject,
                                                             jboolean mode) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);

    se_rd_req_state_t state = MposManager::getInstance().getEtsiReaederState();
    if (!sIsNfaEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: Nfc is not Enabled. Returning", __func__);
      return FDSTATUS_ERROR_NFC_IS_OFF;
    }

    if ((state != STATE_SE_RDR_MODE_STOPPED) &&
        (state != STATE_SE_RDR_MODE_INVALID)) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: MPOS is ongoing.. Returning", __func__);
      return FDSTATUS_ERROR_NFC_BUSY_IN_MPOS;
    }

    if (NFA_IsFieldDetectEnabled() == mode) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: Already %s", __func__, ((mode) ? "ENABLED" : "DISABLED"));
      return FDSTATUS_SUCCESS;
    }

    if (sRfEnabled) {
      // Stop RF Discovery
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: stop discovery", __func__);
      startRfDiscovery(false);
    }
    NFA_SetFieldDetectMode(mode);
    // start discovery
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: reconfigured start discovery", __func__);
    startRfDiscovery(true);
    return FDSTATUS_ERROR_UNKNOWN;
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_IsFieldDetectEnabled
  **
  ** Description:     Returns current status of field detect mode
  **                  e: JVM environment.
  **                  o: Java object.
  **
 ** Returns:         true/false
  **
  *******************************************************************************/
  static jboolean nfcManager_IsFieldDetectEnabled(JNIEnv*, jobject) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);
    return NFA_IsFieldDetectEnabled();
  }
#endif

/*******************************************************************************
**
** Function:        nfcManager_getDefaultAidPowerState
**
** Description:     Get the default Desfire Power States.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Power State
**
*******************************************************************************/
  static jint nfcManager_getDefaultAidPowerState(JNIEnv* e, jobject o) {
    unsigned long num = 0;
#if (NXP_EXTNS == TRUE)
    if (NfcConfig::hasKey(NAME_DEFAULT_AID_PWR_STATE))
      num = NfcConfig::getUnsigned(NAME_DEFAULT_AID_PWR_STATE);
#endif
    return num;
  }

/*******************************************************************************
**
** Function:        nfcManager_getDefaultDesfirePowerState
**
** Description:     Get the default Desfire Power States.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Power State
**
*******************************************************************************/
  static jint nfcManager_getDefaultDesfirePowerState(JNIEnv* e, jobject o) {
    unsigned long num = 0;
#if (NXP_EXTNS == TRUE)
    if (NfcConfig::hasKey(NAME_DEFAULT_DESFIRE_PWR_STATE))
      num = NfcConfig::getUnsigned(NAME_DEFAULT_DESFIRE_PWR_STATE);
#endif
    return num;
  }

static int nfcManager_getDefaulGsmaPowerState(JNIEnv* e, jobject o)
{
  return RoutingManager::getInstance().mDefaultGsmaPowerState;
}
/*******************************************************************************
**
** Function:        nfcManager_getDefaultMifareCLTPowerState
**
** Description:     Get the default mifare CLT Power States.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Power State
**
*******************************************************************************/
static jint nfcManager_getDefaultMifareCLTPowerState(JNIEnv* e, jobject o) {
  unsigned long num = 0;
#if (NXP_EXTNS == TRUE)
  if (NfcConfig::hasKey(NAME_DEFAULT_MIFARE_CLT_PWR_STATE))
    num = NfcConfig::getUnsigned(NAME_DEFAULT_MIFARE_CLT_PWR_STATE);
#endif
  return num;
}
/*******************************************************************************
**
** Function:        isPeerToPeer
**
** Description:     Whether the activation data indicates the peer supports
*NFC-DEP.
**                  activated: Activation data.
**
** Returns:         True if the peer supports NFC-DEP.
**
*******************************************************************************/
static bool isPeerToPeer(tNFA_ACTIVATED& activated) {
  return activated.activate_ntf.protocol == NFA_PROTOCOL_NFC_DEP;
}

/*******************************************************************************
**
** Function:        isListenMode
**
** Description:     Indicates whether the activation data indicates it is
**                  listen mode.
**
** Returns:         True if this listen mode.
**
*******************************************************************************/
static bool isListenMode(tNFA_ACTIVATED& activated) {
  return ((NFC_INTERFACE_EE_DIRECT_RF ==
          activated.activate_ntf.intf_param.type) ||
          (NFC_DISCOVERY_TYPE_LISTEN_A ==
           activated.activate_ntf.rf_tech_param.mode) ||
          (NFC_DISCOVERY_TYPE_LISTEN_B ==
           activated.activate_ntf.rf_tech_param.mode) ||
          (NFC_DISCOVERY_TYPE_LISTEN_F ==
           activated.activate_ntf.rf_tech_param.mode) ||
          (NFC_DISCOVERY_TYPE_LISTEN_A_ACTIVE ==
           activated.activate_ntf.rf_tech_param.mode) ||
          (NFC_DISCOVERY_TYPE_LISTEN_F_ACTIVE ==
           activated.activate_ntf.rf_tech_param.mode) ||
          (NFC_DISCOVERY_TYPE_LISTEN_ISO15693 ==
           activated.activate_ntf.rf_tech_param.mode) ||
          (NFC_DISCOVERY_TYPE_LISTEN_B_PRIME ==
           activated.activate_ntf.rf_tech_param.mode));
}

/*******************************************************************************
**
** Function:        nfcManager_doCheckLlcp
**
** Description:     Not used.
**
** Returns:         True
**
*******************************************************************************/
static jboolean nfcManager_doCheckLlcp(JNIEnv*, jobject) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", __func__);
  return JNI_TRUE;
}

/*******************************************************************************
**
** Function:        nfcManager_doActivateLlcp
**
** Description:     Not used.
**
** Returns:         True
**
*******************************************************************************/
static jboolean nfcManager_doActivateLlcp(JNIEnv*, jobject) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", __func__);
  return JNI_TRUE;
}

/*******************************************************************************
**
** Function:        nfcManager_doAbort
**
** Description:     Not used.
**
** Returns:         None
**
*******************************************************************************/
static void nfcManager_doAbort(JNIEnv* e, jobject, jstring msg) {
  ScopedUtfChars message = {e, msg};
  e->FatalError(message.c_str());
  abort();  // <-- Unreachable
}
#if(NXP_EXTNS == TRUE)
/*******************************************************************************
 **
 ** Function:        handleWiredmode
 **
 ** Description: The function will close the wired mode if it is open.
 **              It shall be called in the NFC and Device off cases.
 ** Returns:     void
 **
 *******************************************************************************/
void handleWiredmode(bool isShutdown)
{
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter, isShutdown %d", __func__, isShutdown);
    SecureElement &se = SecureElement::getInstance();
    if(se.mIsWiredModeOpen) {
      se.setNfccPwrConfig(SecureElement::POWER_ALWAYS_ON);
      se.sendEvent(SecureElement::EVT_END_OF_APDU_TRANSFER);
      usleep(10 * 1000);
    }
    if(!isShutdown) {
      se. SecEle_Modeset(SecureElement::NFCEE_DISABLE);
    }
}
/*******************************************************************************
**
** Function:        nfcManager_doPartialInitForEseCosUpdate
**
** Description:     Partial Init for card OS update
**
** Returns:         NFA_STATUS_OK
**
*******************************************************************************/
static jboolean nfcManager_doPartialInitForEseCosUpdate(JNIEnv* e, jobject o) {
  /* Dummy API return always true.No need to initlize nfc mw
   * as jcop update is done over spi interface.This api is
   * maintained sothat customer app does not break. */
  return true;
}
/*******************************************************************************
**
** Function:        nfcManager_doPartialDeinitForEseCosUpdate
**
** Description:     Partial Deinit for card OS Update
**
** Returns:         NFA_STATUS_OK
**
*******************************************************************************/
static jboolean nfcManager_doPartialDeinitForEseCosUpdate(JNIEnv* e,
                                                          jobject o) {
  /* Dummy API return always true.No need to initlize nfc mw
   * as jcop update is done over spi interface.This api is
   * maintained sothat customer app does not break. */
  return true;
}

/*******************************************************************************
 **
 ** Function:        nfcManager_doResonantFrequency
 **
 ** Description:     factory mode to measure resonance frequency
 **
 ** Returns:         void
 **
 *******************************************************************************/
static void nfcManager_doResonantFrequency(JNIEnv* e, jobject o,
                                               jboolean modeOn) {
  (void)e;
  (void)o;
  tNFA_STATUS status = NFA_STATUS_FAILED;
  jint pollTech, uiccListenTech;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "startResonantFrequency : mode[%s]", modeOn == true ? "ON" : "OFF");

  if (!sIsNfaEnabled) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("startResonantFrequency :NFC is not enabled!!");
    return;
  } else if (modeOn && gselfTestData.isStored) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("startResonantFrequency: Already ON!!");
    return;
  } else if (!modeOn && !gselfTestData.isStored) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("startResonantFrequency: already OFF!!");
    return;
  }
  /* Read the Polling and Listen Tech Mask from the config file */
  pollTech = NfcConfig::getUnsigned(NAME_POLLING_TECH_MASK,
                                    RESONANT_FREQ_DEFAULT_POLL_MASK);
  uiccListenTech = NfcConfig::getUnsigned(NAME_UICC_LISTEN_TECH_MASK,
                                          RESONANT_FREQ_DEFAULT_LISTEN_MASK);
  /* Stop RF Discovery */
  if (android::isDiscoveryStarted()) android::startRfDiscovery(false);
  /* Perform the Requested Test */
  status = NfcSelfTest::GetInstance().doNfccSelfTest(
      modeOn ? TEST_TYPE_SET_RFTXCFG_RESONANT_FREQ : TEST_TYPE_RESTORE_RFTXCFG);

  if (NFA_STATUS_OK == status) {
    nfcManager_doDeinitialize(NULL, NULL);
    usleep(1000 * 1000);
    nfcManager_doInitialize(NULL, NULL);
    usleep(1000 * 2000);

    if (modeOn)        /* TEST_TYPE_SET_RFTXCFG_RESONANT_FREQ */
      pollTech = 0x00; /* Activate only Card Emulation Mode */

    { /* Change the discovery tech mask as per the test */
      SyncEventGuard guard(sChangeDiscTechEvent);
      status = NFA_ChangeDiscoveryTech(pollTech, uiccListenTech);
      if (NFA_STATUS_OK == status) {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: waiting for nfcManager_changeDiscoveryTech", __func__);
        sChangeDiscTechEvent.wait();
      } else {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: nfcManager_changeDiscoveryTech failed", __func__);
      }
    }
  }
  /* Restart the discovery
   * CASE1: TEST_TYPE_SET_RFTXCFG_RESONANT_FREQ --> Only CARD EMULATION
   * CASE2: TEST_TYPE_RESTORE_RFTXCFG --> Both the READER & CE  mode
   * CASE3: FAILURE OF TEST -->  Restart last discovery*/
  android::startRfDiscovery(true);
}

/*******************************************************************************
**
** Function:        nfcManager_doPartialInitialize
**
** Description:     Partial Initalize of NFC
**
** Returns:         True if ok.
**
*******************************************************************************/
int nfcManager_doPartialInitialize(JNIEnv* e, jobject o) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    tNFA_STATUS stat = NFA_STATUS_OK;
    NfcAdaptation& theInstance = NfcAdaptation::GetInstance();

    theInstance.Initialize();
    tHAL_NFC_ENTRY* halFuncEntries = theInstance.GetHalEntryFuncs ();

    if(NULL == halFuncEntries)
    {
        theInstance.Finalize();
        gsNfaPartialEnabled = false;
        return NFA_STATUS_FAILED;
    }
    theInstance.NFA_SetBootMode(NFA_FAST_BOOT_MODE);
    NFA_Init (halFuncEntries);
    DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s: calling enable", __func__);

    stat = NFA_Enable (nfaDeviceManagementCallback, nfaConnectionCallback);
    if (stat == NFA_STATUS_OK)
    {
        SyncEventGuard guard (sNfaEnableEvent);
        sNfaEnableEvent.wait(); //wait for NFA command to finish
    }

    if (sIsNfaEnabled)
    {
        gsNfaPartialEnabled = true;
        sIsNfaEnabled = false;
    }
    else
    {
        NFA_Disable (false /* ungraceful */);
        theInstance.Finalize();
        gsNfaPartialEnabled = false;
    }
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return NFA_STATUS_OK;
}
/*******************************************************************************
**
** Function:        nfcManager_doPartialDeInitialize
**
** Description:     Partial De-Initalize of NFC
**
** Returns:         True if ok.
**
*******************************************************************************/
int nfcManager_doPartialDeInitialize(JNIEnv*, jobject) {

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    tNFA_STATUS stat = NFA_STATUS_OK;
    NfcAdaptation& theInstance = NfcAdaptation::GetInstance();

    if(!gsNfaPartialEnabled)
    {
        DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s: cannot deinitialize NFC , not partially initilaized", __func__);
        return NFA_STATUS_FAILED;
    }
    DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s:enter", __func__);
    stat = NFA_Disable (true /* graceful */);
    if (stat == NFA_STATUS_OK)
    {
        DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s: wait for completion", __func__);
        SyncEventGuard guard (sNfaDisableEvent);
        sNfaDisableEvent.wait (); //wait for NFA command to finish
    }
    else
    {
        DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf("%s: fail disable; error=0x%X", __func__, stat);
    }
    theInstance.NFA_SetBootMode(NFA_NORMAL_BOOT_MODE);
    theInstance.Finalize();
    gsNfaPartialEnabled = false;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);

  return NFA_STATUS_OK;
}
#endif
/*******************************************************************************
**
** Function:        nfcManager_doDownload
**
** Description:     Download firmware patch files.  Do not turn on NFC.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nfcManager_doDownload(JNIEnv* e, jobject o) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  bool result = JNI_FALSE;
  theInstance.Initialize();  // start GKI, NCI task, NFC task
#if (NXP_EXTNS == TRUE)
  NativeJniExtns::getInstance().initializeNativeData(getNative(e, o));
  result = theInstance.DownloadFirmware(nfcFwUpdateStatusCallback, false);
#else
  result = theInstance.DownloadFirmware();
#endif
  theInstance.Finalize();
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return result;
}

/*******************************************************************************
**
** Function:        nfcManager_doResetTimeouts
**
** Description:     Not used.
**
** Returns:         None
**
*******************************************************************************/
static void nfcManager_doResetTimeouts(JNIEnv*, jobject) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", __func__);
  NfcTag::getInstance().resetAllTransceiveTimeouts();
}

/*******************************************************************************
**
** Function:        nfcManager_doSetTimeout
**
** Description:     Set timeout value.
**                  e: JVM environment.
**                  o: Java object.
**                  tech: technology ID.
**                  timeout: Timeout value.
**
** Returns:         True if ok.
**
*******************************************************************************/
static bool nfcManager_doSetTimeout(JNIEnv*, jobject, jint tech, jint timeout) {
  if (timeout <= 0) {
    LOG(ERROR) << StringPrintf("%s: Timeout must be positive.", __func__);
    return false;
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: tech=%d, timeout=%d", __func__, tech, timeout);
  NfcTag::getInstance().setTransceiveTimeout(tech, timeout);
  return true;
}

/*******************************************************************************
**
** Function:        nfcManager_doGetTimeout
**
** Description:     Get timeout value.
**                  e: JVM environment.
**                  o: Java object.
**                  tech: technology ID.
**
** Returns:         Timeout value.
**
*******************************************************************************/
static jint nfcManager_doGetTimeout(JNIEnv*, jobject, jint tech) {
  int timeout = NfcTag::getInstance().getTransceiveTimeout(tech);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: tech=%d, timeout=%d", __func__, tech, timeout);
  return timeout;
}

/*******************************************************************************
**
** Function:        nfcManager_doDump
**
** Description:     Get libnfc-nci dump
**                  e: JVM environment.
**                  obj: Java object.
**                  fdobj: File descriptor to be used
**
** Returns:         Void
**
*******************************************************************************/
static void nfcManager_doDump(JNIEnv* e, jobject obj, jobject fdobj) {
  int fd = jniGetFDFromFileDescriptor(e, fdobj);
  if (fd < 0) return;

  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  theInstance.Dump(fd);
}

static jint nfcManager_doGetNciVersion(JNIEnv*, jobject) {
  return NFC_GetNCIVersion();
}

static void nfcManager_doSetScreenState(JNIEnv* e, jobject o,
                                        jint screen_state_mask) {
  tNFA_STATUS status = NFA_STATUS_OK;
  uint8_t state = (screen_state_mask & NFA_SCREEN_STATE_MASK);
  uint8_t discovry_param =
      NCI_LISTEN_DH_NFCEE_ENABLE_MASK | NCI_POLLING_DH_ENABLE_MASK;
#if (NXP_EXTNS == TRUE)
  if(prevScreenState == state) {
    LOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("Screen state is not changed.");
    return;
  }
  NativeJniExtns::getInstance().notifyNfcEvent(__func__);
#endif
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: state = %d prevScreenState= %d, discovry_param = %d",
                      __FUNCTION__, state, prevScreenState, discovry_param);

  if (sIsDisabling || !sIsNfaEnabled ||
      (NFC_GetNCIVersion() != NCI_VERSION_2_0))  {
    prevScreenState = state;
    return;
  }
  if (
#if (NXP_EXTNS == TRUE)
      prevScreenState == NFA_SCREEN_STATE_UNKNOWN ||
#endif
      prevScreenState == NFA_SCREEN_STATE_OFF_LOCKED ||
      prevScreenState == NFA_SCREEN_STATE_OFF_UNLOCKED ||
      prevScreenState == NFA_SCREEN_STATE_ON_LOCKED) {
    SyncEventGuard guard(sNfaSetPowerSubState);
    status = NFA_SetPowerSubStateForScreenState(state);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail enable SetScreenState; error=0x%X",
                                 __FUNCTION__, status);
      return;
    } else {
      sNfaSetPowerSubState.wait();
    }
  }

  if (state == NFA_SCREEN_STATE_OFF_LOCKED ||
      state == NFA_SCREEN_STATE_OFF_UNLOCKED) {
    // disable poll, and DH-NFCEE considered as enabled 0x00
    discovry_param =
        NCI_POLLING_DH_DISABLE_MASK | NCI_LISTEN_DH_NFCEE_ENABLE_MASK;
  }

  if (state == NFA_SCREEN_STATE_ON_LOCKED) {
    // disable poll and enable listen on DH 0x00
    discovry_param =
        (screen_state_mask & NFA_SCREEN_POLLING_TAG_MASK)
            ? (NCI_LISTEN_DH_NFCEE_ENABLE_MASK | NCI_POLLING_DH_ENABLE_MASK)
            : (NCI_POLLING_DH_DISABLE_MASK | NCI_LISTEN_DH_NFCEE_ENABLE_MASK);
  }

  if (state == NFA_SCREEN_STATE_ON_UNLOCKED) {
    // enable both poll and listen on DH 0x01
    discovry_param =
        NCI_LISTEN_DH_NFCEE_ENABLE_MASK | NCI_POLLING_DH_ENABLE_MASK;
  }

  SyncEventGuard guard(sNfaSetConfigEvent);
  status = NFA_SetConfig(NCI_PARAM_ID_CON_DISCOVERY_PARAM,
                         NCI_PARAM_LEN_CON_DISCOVERY_PARAM, &discovry_param);
  if (status == NFA_STATUS_OK) {
    sNfaSetConfigEvent.wait();
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed to update CON_DISCOVER_PARAM",
                               __FUNCTION__);
    return;
  }

  if (prevScreenState == NFA_SCREEN_STATE_ON_UNLOCKED) {
    SyncEventGuard guard(sNfaSetPowerSubState);
    status = NFA_SetPowerSubStateForScreenState(state);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail enable SetScreenState; error=0x%X",
                                 __FUNCTION__, status);
    } else {
      sNfaSetPowerSubState.wait();
    }
  }
  if ((state == NFA_SCREEN_STATE_OFF_LOCKED ||
       state == NFA_SCREEN_STATE_OFF_UNLOCKED) &&
      prevScreenState == NFA_SCREEN_STATE_ON_UNLOCKED) {
    // screen turns off, disconnect tag if connected
#if (NXP_EXTNS == TRUE)
    if(sReaderModeEnabled || sP2pActive){
        nativeNfcTag_doDisconnect(NULL, NULL);
    }else{
      //CardEmulation: Shouldn't take an action.
    }
#else
    nativeNfcTag_doDisconnect(NULL, NULL);
#endif
  }

  prevScreenState = state;
}
/*******************************************************************************
**
** Function:        nfcManager_doSetP2pInitiatorModes
**
** Description:     Set P2P initiator's activation modes.
**                  e: JVM environment.
**                  o: Java object.
**                  modes: Active and/or passive modes.  The values are
*specified
**                          in external/libnfc-nxp/inc/phNfcTypes.h.  See
**                          enum phNfc_eP2PMode_t.
**
** Returns:         None.
**
*******************************************************************************/
static void nfcManager_doSetP2pInitiatorModes(JNIEnv* e, jobject o,
                                              jint modes) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: modes=0x%X", __func__, modes);
  struct nfc_jni_native_data* nat = getNative(e, o);

  tNFA_TECHNOLOGY_MASK mask = 0;
  if (modes & 0x01) mask |= NFA_TECHNOLOGY_MASK_A;
  if (modes & 0x02) mask |= NFA_TECHNOLOGY_MASK_F;
  if (modes & 0x04) mask |= NFA_TECHNOLOGY_MASK_F;
  if (modes & 0x08) mask |= NFA_TECHNOLOGY_MASK_A_ACTIVE;
  if (modes & 0x10) mask |= NFA_TECHNOLOGY_MASK_F_ACTIVE;
  if (modes & 0x20) mask |= NFA_TECHNOLOGY_MASK_F_ACTIVE;
  nat->tech_mask = mask;
}

/*******************************************************************************
**
** Function:        nfcManager_doSetP2pTargetModes
**
** Description:     Set P2P target's activation modes.
**                  e: JVM environment.
**                  o: Java object.
**                  modes: Active and/or passive modes.
**
** Returns:         None.
**
*******************************************************************************/
static void nfcManager_doSetP2pTargetModes(JNIEnv*, jobject, jint modes) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: modes=0x%X", __func__, modes);
  // Map in the right modes
  tNFA_TECHNOLOGY_MASK mask = 0;
  if (modes & 0x01) mask |= NFA_TECHNOLOGY_MASK_A;
  if (modes & 0x02) mask |= NFA_TECHNOLOGY_MASK_F;
  if (modes & 0x04) mask |= NFA_TECHNOLOGY_MASK_F;
  if (modes & 0x08)
    mask |= NFA_TECHNOLOGY_MASK_A_ACTIVE | NFA_TECHNOLOGY_MASK_F_ACTIVE;

  PeerToPeer::getInstance().setP2pListenMask(mask);
}

static void nfcManager_doEnableScreenOffSuspend(JNIEnv* e, jobject o) {
  PowerSwitch::getInstance().setScreenOffPowerState(
      PowerSwitch::POWER_STATE_FULL);
}

static void nfcManager_doDisableScreenOffSuspend(JNIEnv* e, jobject o) {
  PowerSwitch::getInstance().setScreenOffPowerState(
      PowerSwitch::POWER_STATE_OFF);
}

/*******************************************************************************
**
** Function:        nfcManager_getIsoDepMaxTransceiveLength
**
** Description:     Get maximum ISO DEP Transceive Length supported by the NFC
**                  chip. Returns default 261 bytes if the property is not set.
**
** Returns:         max value.
**
*******************************************************************************/
static jint nfcManager_getIsoDepMaxTransceiveLength(JNIEnv*, jobject) {
  /* Check if extended APDU is supported by the chip.
   * If not, default value is returned.
   * The maximum length of a default IsoDep frame consists of:
   * CLA, INS, P1, P2, LC, LE + 255 payload bytes = 261 bytes
   */
  return NfcConfig::getUnsigned(NAME_ISO_DEP_MAX_TRANSCEIVE, 261);
}

static jboolean nfcManager_doSetNfcSecure(JNIEnv* e, jobject o,
                                          jboolean enable) {
  RoutingManager& routingManager = RoutingManager::getInstance();
  routingManager.setNfcSecure(enable);
#if(NXP_EXTNS != TRUE)
  bool rfEnabled = sRfEnabled;
#endif
  if (sRoutingInitialized) {
#if(NXP_EXTNS != TRUE)
      routingManager.disableRoutingToHost();
      if (rfEnabled) startRfDiscovery(false);
      routingManager.updateRoutingTable();
      routingManager.enableRoutingToHost();
      routingManager.commitRouting();
      if (rfEnabled) startRfDiscovery(true);
#else
      routingManager.updateRoutingTable();
#endif
  }
  return true;
}

/*****************************************************************************
**
** JNI functions for android-4.0.1_r1
**
*****************************************************************************/
static JNINativeMethod gMethods[] = {
    {"doDownload", "()Z", (void*)nfcManager_doDownload},

    {"initializeNativeStructure", "()Z", (void*)nfcManager_initNativeStruc},

    {"doInitialize", "()Z", (void*)nfcManager_doInitialize},

    {"doDeinitialize", "()Z", (void*)nfcManager_doDeinitialize},

    {"sendRawFrame", "([B)Z", (void*)nfcManager_sendRawFrame},

    {"routeAid", "([BIII)Z", (void*)nfcManager_routeAid},

    {"unrouteAid", "([B)Z", (void*)nfcManager_unrouteAid},

    {"doSetRoutingEntry", "(IIII)Z",
            (void*)nfcManager_setRoutingEntry},

    {"doClearRoutingEntry", "(I)Z",
            (void*)nfcManager_clearRoutingEntry},

    {"commitRouting", "()Z", (void*)nfcManager_commitRouting},

    {"setEmptyAidRoute", "(I)V", (void*)nfcManager_setEmptyAidRoute},

    {"doRegisterT3tIdentifier", "([B)I",
     (void*)nfcManager_doRegisterT3tIdentifier},

    {"doDeregisterT3tIdentifier", "(I)V",
     (void*)nfcManager_doDeregisterT3tIdentifier},

    {"getLfT3tMax", "()I", (void*)nfcManager_getLfT3tMax},

    {"doEnableDiscovery", "(IZZZZZ)V", (void*)nfcManager_enableDiscovery},

    {"doCheckLlcp", "()Z", (void*)nfcManager_doCheckLlcp},

    {"doActivateLlcp", "()Z", (void*)nfcManager_doActivateLlcp},

    {"doCreateLlcpConnectionlessSocket",
     "(ILjava/lang/String;)Lcom/android/nfc/dhimpl/"
     "NativeLlcpConnectionlessSocket;",
     (void*)nfcManager_doCreateLlcpConnectionlessSocket},

    {"doCreateLlcpServiceSocket",
     "(ILjava/lang/String;III)Lcom/android/nfc/dhimpl/NativeLlcpServiceSocket;",
     (void*)nfcManager_doCreateLlcpServiceSocket},

    {"doCreateLlcpSocket", "(IIII)Lcom/android/nfc/dhimpl/NativeLlcpSocket;",
     (void*)nfcManager_doCreateLlcpSocket},

    {"doGetLastError", "()I", (void*)nfcManager_doGetLastError},

    {"disableDiscovery", "()V", (void*)nfcManager_disableDiscovery},

    {"doSetTimeout", "(II)Z", (void*)nfcManager_doSetTimeout},

    {"doGetTimeout", "(I)I", (void*)nfcManager_doGetTimeout},

    {"doResetTimeouts", "()V", (void*)nfcManager_doResetTimeouts},

    {"doAbort", "(Ljava/lang/String;)V", (void*)nfcManager_doAbort},

    {"doSetP2pInitiatorModes", "(I)V",
     (void*)nfcManager_doSetP2pInitiatorModes},

    {"doSetP2pTargetModes", "(I)V", (void*)nfcManager_doSetP2pTargetModes},

    {"doEnableScreenOffSuspend", "()V",
     (void*)nfcManager_doEnableScreenOffSuspend},

    {"doSetScreenState", "(I)V", (void*)nfcManager_doSetScreenState},

    {"doDisableScreenOffSuspend", "()V",
     (void*)nfcManager_doDisableScreenOffSuspend},

    {"doDump", "(Ljava/io/FileDescriptor;)V", (void*)nfcManager_doDump},

    {"getNciVersion", "()I", (void*)nfcManager_doGetNciVersion},
    {"doEnableDtaMode", "()V", (void*)nfcManager_doEnableDtaMode},
    {"doDisableDtaMode", "()V", (void*)nfcManager_doDisableDtaMode},
    {"doFactoryReset", "()V", (void*)nfcManager_doFactoryReset},
    {"doShutdown", "()V", (void*)nfcManager_doShutdown},

    {"getIsoDepMaxTransceiveLength", "()I",
     (void*)nfcManager_getIsoDepMaxTransceiveLength},

    {"getDefaultAidRoute", "()I",
            (void*) nfcManager_getDefaultAidRoute},

    {"getDefaultDesfireRoute", "()I",
            (void*) nfcManager_getDefaultDesfireRoute},

    {"getDefaultMifareCLTRoute", "()I",
            (void*) nfcManager_getDefaultMifareCLTRoute},

    {"getDefaultAidPowerState", "()I",
            (void*) nfcManager_getDefaultAidPowerState},

    {"getDefaultDesfirePowerState", "()I",
            (void*) nfcManager_getDefaultDesfirePowerState},

    {"getDefaultMifareCLTPowerState", "()I",
            (void*) nfcManager_getDefaultMifareCLTPowerState},
#if(NXP_EXTNS == TRUE)
    {"getGsmaPwrState", "()I",
            (void*) nfcManager_getDefaulGsmaPowerState}
     ,{"getDefaultFelicaCLTPowerState", "()I",
            (void*) nfcManager_getDefaultFelicaCLTPowerState},
      {"getDefaultFelicaCLTRoute", "()I",
            (void*) nfcManager_getDefaultFelicaCLTRoute},
     {"doGetActiveSecureElementList", "()[I",
            (void *)nfcManager_getActiveSecureElementList},
     {"doChangeDiscoveryTech", "(II)V",
             (void *)nfcManager_changeDiscoveryTech},
    {"doPartialInitForEseCosUpdate", "()Z",
             (void*)nfcManager_doPartialInitForEseCosUpdate},
    {"doPartialDeinitForEseCosUpdate", "()Z",
             (void*)nfcManager_doPartialDeinitForEseCosUpdate},

    {"doResonantFrequency", "(Z)V",
              (void *)nfcManager_doResonantFrequency},
     {"doSetFieldDetectMode", "(Z)I",
     (void*)nfcManager_SetFieldDetectMode},
     {"isFieldDetectEnabled", "()Z",
     (void*)nfcManager_IsFieldDetectEnabled},
#endif
#if(NXP_EXTNS == TRUE)
    // check firmware version
    {"getFWVersion", "()I", (void*)nfcManager_getFwVersion},
    {"readerPassThruMode", "(BB)[B", (void*)nfcManager_readerPassThruMode},
    {"transceiveAppData", "([B)[B", (void*)nfcManager_transceiveAppData},
    {"isNfccBusy", "()Z", (void*)nfcManager_isNfccBusy},
    {"setTransitConfig", "(Ljava/lang/String;)I",
                  (void*)nfcManager_setTransitConfig},
    {"getAidTableSize", "()I",
            (void*) nfcManager_getAidTableSize},
    {"getRemainingAidTableSize", "()I",
            (void*) nfcManager_getRemainingAidTableSize},
    {"doselectUicc", "(I)I", (void*)nfcManager_doSelectUicc},
    {"doGetSelectedUicc", "()I", (void*)nfcManager_doGetSelectedUicc},
    {"setPreferredSimSlot", "(I)I", (void*)nfcManager_setPreferredSimSlot},
#endif
    {"doSetNfcSecure", "(Z)Z", (void*)nfcManager_doSetNfcSecure},
};

/*******************************************************************************
**
** Function:        register_com_android_nfc_NativeNfcManager
**
** Description:     Regisgter JNI functions with Java Virtual Machine.
**                  e: Environment of JVM.
**
** Returns:         Status of registration.
**
*******************************************************************************/
int register_com_android_nfc_NativeNfcManager(JNIEnv* e) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  PowerSwitch::getInstance().initialize(PowerSwitch::UNKNOWN_LEVEL);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return jniRegisterNativeMethods(e, gNativeNfcManagerClassName, gMethods,
                                  NELEM(gMethods));
}

/*******************************************************************************
**
** Function:        startRfDiscovery
**
** Description:     Ask stack to start polling and listening for devices.
**                  isStart: Whether to start.
**
** Returns:         None
**
*******************************************************************************/
void startRfDiscovery(bool isStart) {
  tNFA_STATUS status = NFA_STATUS_FAILED;

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: is start=%d", __func__, isStart);
  nativeNfcTag_acquireRfInterfaceMutexLock();
  SyncEventGuard guard(sNfaEnableDisablePollingEvent);
  status = isStart ? NFA_StartRfDiscovery() : NFA_StopRfDiscovery();
  if (status == NFA_STATUS_OK) {
    sNfaEnableDisablePollingEvent.wait (); //wait for NFA_RF_DISCOVERY_xxxx_EVT
    sRfEnabled = isStart;
  } else {
    LOG(ERROR) << StringPrintf(
        "%s: Failed to start/stop RF discovery; error=0x%X", __func__, status);
  }
  nativeNfcTag_releaseRfInterfaceMutexLock();
}

/*******************************************************************************
**
** Function:        isDiscoveryStarted
**
** Description:     Indicates whether the discovery is started.
**
** Returns:         True if discovery is started
**
*******************************************************************************/
bool isDiscoveryStarted() { return sRfEnabled; }

/*******************************************************************************
**
** Function:        doStartupConfig
**
** Description:     Configure the NFC controller.
**
** Returns:         None
**
*******************************************************************************/
void doStartupConfig() {
#if (NXP_EXTNS == FALSE)
 struct nfc_jni_native_data* nat = getNative(0, 0);
  tNFA_STATUS stat = NFA_STATUS_FAILED;

   //If polling for Active mode, set the ordering so that we choose Active over
   //Passive mode first.
  if (nat && (nat->tech_mask &
              (NFA_TECHNOLOGY_MASK_A_ACTIVE | NFA_TECHNOLOGY_MASK_F_ACTIVE))) {
    uint8_t act_mode_order_param[] = {0x01};
    SyncEventGuard guard(sNfaSetConfigEvent);
    stat = NFA_SetConfig(NCI_PARAM_ID_ACT_ORDER, sizeof(act_mode_order_param),
                         &act_mode_order_param[0]);
    if (stat == NFA_STATUS_OK) sNfaSetConfigEvent.wait();
  }
#endif
  // configure RF polling frequency for each technology
  static tNFA_DM_DISC_FREQ_CFG nfa_dm_disc_freq_cfg;
  // values in the polling_frequency[] map to members of nfa_dm_disc_freq_cfg
  std::vector<uint8_t> polling_frequency;
  if (NfcConfig::hasKey(NAME_POLL_FREQUENCY))
    polling_frequency = NfcConfig::getBytes(NAME_POLL_FREQUENCY);
  if (polling_frequency.size() == 8) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: polling frequency", __func__);
    memset(&nfa_dm_disc_freq_cfg, 0, sizeof(nfa_dm_disc_freq_cfg));
    nfa_dm_disc_freq_cfg.pa = polling_frequency[0];
    nfa_dm_disc_freq_cfg.pb = polling_frequency[1];
    nfa_dm_disc_freq_cfg.pf = polling_frequency[2];
    nfa_dm_disc_freq_cfg.pi93 = polling_frequency[3];
    nfa_dm_disc_freq_cfg.pbp = polling_frequency[4];
    nfa_dm_disc_freq_cfg.pk = polling_frequency[5];
    nfa_dm_disc_freq_cfg.paa = polling_frequency[6];
    nfa_dm_disc_freq_cfg.pfa = polling_frequency[7];
    p_nfa_dm_rf_disc_freq_cfg = &nfa_dm_disc_freq_cfg;
  }

  // configure NFCC_CONFIG_CONTROL- NFCC allowed to manage RF configuration.
  if (NFC_GetNCIVersion() != NCI_VERSION_1_0) {
    uint8_t nfa_set_config[] = {0x01};
    tNFA_STATUS status = NFA_SetConfig(NCI_PARAM_ID_NFCC_CONFIG_CONTROL,
        sizeof(nfa_set_config), &nfa_set_config[0]);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << __func__ << ": Failed to configure NFCC_CONFIG_CONTROL";
    }
#if (NXP_EXTNS == TRUE)
    send_flush_ram_to_flash();
#endif
  }
}

/*******************************************************************************
**
** Function:        nfcManager_isNfcActive
**
** Description:     Used externaly to determine if NFC is active or not.
**
** Returns:         'true' if the NFC stack is running, else 'false'.
**
*******************************************************************************/
bool nfcManager_isNfcActive() { return sIsNfaEnabled; }

/*******************************************************************************
**
** Function:        startStopPolling
**
** Description:     Start or stop polling.
**                  isStartPolling: true to start polling; false to stop
*polling.
**
** Returns:         None.
**
*******************************************************************************/
void startStopPolling(bool isStartPolling) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter; isStart=%u", __func__, isStartPolling);
  startRfDiscovery(false);

  if (isStartPolling)
    startPolling_rfDiscoveryDisabled(0);
  else
    stopPolling_rfDiscoveryDisabled();

  startRfDiscovery(true);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
}

static tNFA_STATUS startPolling_rfDiscoveryDisabled(
    tNFA_TECHNOLOGY_MASK tech_mask) {
  tNFA_STATUS stat = NFA_STATUS_FAILED;

  if (tech_mask == 0)
    tech_mask =
        NfcConfig::getUnsigned(NAME_POLLING_TECH_MASK, DEFAULT_TECH_MASK);

  nativeNfcTag_acquireRfInterfaceMutexLock();
  SyncEventGuard guard(sNfaEnableDisablePollingEvent);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enable polling", __func__);
  stat = NFA_EnablePolling(tech_mask);
  if (stat == NFA_STATUS_OK) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: wait for enable event", __func__);
    sPollingEnabled = true;
    sNfaEnableDisablePollingEvent.wait();  // wait for NFA_POLL_ENABLED_EVT
  } else {
    LOG(ERROR) << StringPrintf("%s: fail enable polling; error=0x%X", __func__,
                               stat);
  }
  nativeNfcTag_releaseRfInterfaceMutexLock();

  return stat;
}

static tNFA_STATUS stopPolling_rfDiscoveryDisabled() {
  tNFA_STATUS stat = NFA_STATUS_FAILED;

  nativeNfcTag_acquireRfInterfaceMutexLock();
  SyncEventGuard guard(sNfaEnableDisablePollingEvent);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: disable polling", __func__);
  stat = NFA_DisablePolling();
  if (stat == NFA_STATUS_OK) {
    sPollingEnabled = false;
    sNfaEnableDisablePollingEvent.wait();  // wait for NFA_POLL_DISABLED_EVT
  } else {
    LOG(ERROR) << StringPrintf("%s: fail disable polling; error=0x%X", __func__,
                               stat);
  }
  nativeNfcTag_releaseRfInterfaceMutexLock();

  return stat;
}

#if(NXP_EXTNS == TRUE)
/*******************************************************************************
**
** Function:        nfcManager_changeDiscoveryTech
**
** Description:     set listen mode
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         None.
**
*******************************************************************************/
static void nfcManager_changeDiscoveryTech(JNIEnv* e, jobject o, jint pollTech, jint listenTech)
{
    DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("Enter :%s  pollTech = 0x%x, listenTech = 0x%x", __func__, pollTech, listenTech);

    NFA_ChangeDiscoveryTech(pollTech, listenTech);
}

/*******************************************************************************
 **
 ** Function:        nfcManager_checkNfcStateBusy()
 **
 ** Description      This function returns whether NFC process is busy or not.
 **
 ** Returns          if Nfc state busy return true otherwise false.
 **
 *******************************************************************************/
bool nfcManager_checkNfcStateBusy()
{
    bool status = false;

    if(NFA_checkNfcStateBusy() == true)
        status = true;

    return status;
}

void enableLastRfDiscovery()
{
    DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter", __FUNCTION__);
    RoutingManager::getInstance().configureOffHostNfceeTechMask();
    nfcManager_enableDiscovery(NULL, NULL,
        mDiscParams.technologies_mask,
        mDiscParams.enable_lptd,
        mDiscParams.reader_mode,
        mDiscParams.enable_host_routing,
        mDiscParams.enable_p2p,
        true);
}

void storeLastDiscoveryParams(int technologies_mask, bool enable_lptd,
    bool reader_mode, bool enable_host_routing ,bool enable_p2p, bool restart)
{
    DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter", __FUNCTION__);
    mDiscParams.technologies_mask = technologies_mask;
    mDiscParams.enable_lptd = enable_lptd;
    mDiscParams.reader_mode = reader_mode;
    mDiscParams.enable_p2p = enable_p2p;
    mDiscParams.enable_host_routing = enable_host_routing;
    mDiscParams.restart = restart;
}
#if(NXP_EXTNS == TRUE)
/**********************************************************************************
**
** Function:        nfcManager_getFwVersion
**
** Description:     To get the FW Version
**
** Returns:         int fw version as below four byte format
**                  [0x00  0xROM_CODE_V  0xFW_MAJOR_NO  0xFW_MINOR_NO]
**
**********************************************************************************/
static jint nfcManager_getFwVersion(JNIEnv * e, jobject o) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    tNFA_STATUS status = NFA_STATUS_FAILED;
    //    bool stat = false;                        /*commented to eliminate
    //    unused variable warning*/
    jint version = 0, temp = 0;
    tNFC_FW_VERSION nfc_native_fw_version;

    if (!sIsNfaEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NFC does not enabled!!");
      return status;
    }
    memset(&nfc_native_fw_version, 0, sizeof(nfc_native_fw_version));

    nfc_native_fw_version = nfc_ncif_getFWVersion();
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "FW Version: %x.%x.%x", nfc_native_fw_version.rom_code_version,
        nfc_native_fw_version.major_version,
        nfc_native_fw_version.minor_version);

    temp = nfc_native_fw_version.rom_code_version;
    version = temp << 16;
    temp = nfc_native_fw_version.major_version;
    version |= temp << 8;
    version |= nfc_native_fw_version.minor_version;

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit; version =0x%X", __func__, version);
    return version;
}

  /*******************************************************************************
   **
   ** Class:       Command
   **
   ** Description: This class serves as base class for all propreitary
   **              commands for special FW mode of operation.
   **              It also holds reference to the HAL entry point functions.
   **              This class provides template for methods which can operate on
   **              command frames.It has default implementation for send() API
   **              which can be shared by all subclasses.
   **              This class shall not be instantiated.
   **              It should be treated as an abstracted class.
  *******************************************************************************/
  class Command {
  public:
    /*Response wait time*/
    uint32_t rspTimeout;
    /*Retrial count limit to handle failure rsp*/
    int32_t retryCount;
    /*Name of the command*/
    char *name;
    /*Reference to NFC adapter instance*/
    NfcAdaptation &nfcAdapter;
    /*Reference to HAL manager*/
    tHAL_NFC_ENTRY *halMgr;

    /*Constructor*/
    Command() : nfcAdapter(NfcAdaptation::GetInstance()) {
      rspTimeout = 0;
      retryCount = 0;
      name = NULL;
      /*Retreive reference to HAL function manager*/
      halMgr = nfcAdapter.GetHalEntryFuncs();
    }
    /*Overidable method list*/
    virtual tNFA_STATUS preProcessor()
    { return 0;}
    virtual tNFA_STATUS send(uint8_t *rsp_len, uint8_t *rsp_buf) {
      return NFA_STATUS_OK;
    }
    virtual tNFA_STATUS send(uint8_t *pData4Tx, uint8_t dataLen, uint8_t *rsp_len,
                             uint8_t *rsp_buf) {
      return NxpPropCmd_send(pData4Tx, dataLen, rsp_len, rsp_buf, rspTimeout,
                             halMgr);
    }
    virtual tNFA_STATUS postProcessor(uint8_t rsp_len, uint8_t *rsp_buf) {
      return NFA_STATUS_OK;
    }
    virtual ~Command() {}
  };
  /*******************************************************************************
   **
   ** Class:        cmdEnablePassThru
   **
   ** Description: This child class provides command frame. It also provides the
   **              API for validity check(preprocessor), sending command and
   **              post processing the response.
   **
  *******************************************************************************/
  class cmdEnablePassThru : public Command {
  private:
    uint8_t cmd_buf[5];

  public:
    /*Constructor*/
    cmdEnablePassThru(uint8_t modulationTyp, uint32_t timeoutVal,
                      uint32_t retryCnt) {
      /*Set the command specific parameters*/
      name = (char *)"RDR_PASS_THRU_MODE_ENABLE";
      retryCount = retryCnt;
      rspTimeout = timeoutVal;

      /*Create the command frame*/
      cmd_buf[0] = 0x2F;
      cmd_buf[1] = 0x1C;
      cmd_buf[2] = 0x02;
      cmd_buf[3] = modulationTyp;
      cmd_buf[4] = 0x00;
    }
    tNFA_STATUS send(uint8_t *rsp_len, uint8_t *rsp_buf) {
      return Command::send(cmd_buf, sizeof(cmd_buf), rsp_len, rsp_buf);
    }
    tNFA_STATUS preProcessor() {
      tNFA_STATUS status = NFA_STATUS_OK;
      SecureElement &se = SecureElement::getInstance();

      /*Reject request if secure element is listening*/
      if (se.isActivatedInListenMode()) {
        LOG(ERROR) << StringPrintf("%s: Denying access due to SE listen mode active", __func__);
        status = NFA_STATUS_REJECTED;
      }
      /*Reject request if wired mode is ongoing*/
      if (se.mIsWiredModeOpen == true) {
        LOG(ERROR) << StringPrintf("%s: Denying access due to Wired session is ongoing", __func__);
        status = NFA_STATUS_REJECTED;
      }
      if (android::sRfEnabled) {
        /* Call the NFA_StopRfDiscovery to synchronize JNI, stack and FW*/
        android::startRfDiscovery(false);
        /*Stop RF discovery to reconfigure*/
        /* [START]poll*/
        {
          uint8_t enable_discover[] = {0x21, 0x03, 0x03, 0x01, 0x00, 0x01};
          uint8_t disable_discover[] = {0x21, 0x06, 0x01, 0x00};
          status = android::NxpNfc_Write_Cmd_Common(sizeof(enable_discover),
                                                    enable_discover);
          if (status == NFA_STATUS_OK)
            status = android::NxpNfc_Write_Cmd_Common(sizeof(disable_discover),
                                                      disable_discover);
        }
        /*[END]poll*/
      }
      return status;
    }
  };
  /*******************************************************************************
   **
   ** Class:       cmdDisablePassThru
   **
   ** Description: This child class provides command frame. It also provides the
   **              API for validity check(preprocessor), sending command and
   **              post processing the response.
   **
  *******************************************************************************/
  class cmdDisablePassThru : public Command {
  private:
    uint8_t cmd_buf[3];

  public:
    /*Constructor*/
    cmdDisablePassThru(uint32_t timeoutVal, uint32_t retryCnt) {
      /*Set the command specific parameters*/
      name = (char *)"RDR_PASS_THRU_MODE_DISABLE";
      retryCount = retryCnt;
      rspTimeout = timeoutVal;

      /*Create the command frame*/
      cmd_buf[0] = 0x2F;
      cmd_buf[1] = 0x1A;
      cmd_buf[2] = 0x00;
    }

    tNFA_STATUS send(uint8_t *rsp_len, uint8_t *rsp_buf) {
      return Command::send(cmd_buf, sizeof(cmd_buf), rsp_len, rsp_buf);
    }

    tNFA_STATUS preProcessor() {
      /*Reject request if NFA layer is not enabled*/
      if (!android::sIsNfaEnabled
          ) {
        LOG(ERROR) << StringPrintf("%s: Denying request due to disabled NFA layer", __func__);
        return NFA_STATUS_REJECTED;
      }
      /*Stop RF discovery in case RF is enabled*/
      if (android::sRfEnabled && android::sIsNfaEnabled) {
        // Stop RF discovery to reconfigure
        android::startRfDiscovery(false);
      }
      return NFA_STATUS_OK;
    }

    tNFA_STATUS postProcessor(uint8_t rsp_len, uint8_t *rsp_buf) {

      /*Start RF discovery in case RF is enabled*/
      if (!android::sRfEnabled && android::sIsNfaEnabled)
        android::startRfDiscovery(true);
      return NFA_STATUS_OK;
    }
  };
  /*******************************************************************************
   **
   ** Class:        cmdTransceive
   **
   ** Description:  Sends the iCode proprietary transcieve app command.
   **               This child class provides command frame. It also provides the
   **               API for validity check(preprocessor), sending command and
   **               post processing the response.
   **
   **
  *******************************************************************************/
  class cmdTransceive : public Command {
  private:
    uint8_t cmd_buf[258];

  public:
    /*Constructor*/
    cmdTransceive(uint32_t timeoutVal, uint32_t retryCnt) {
      /*Set the command specific parameters*/
      name = (char *)"RDR_PASS_THRU_MODE_XCV";
      retryCount = retryCnt;
      rspTimeout = timeoutVal;

      /*Create the command frame*/
      cmd_buf[0] = 0x2F;
      cmd_buf[1] = 0x1B;
    }

    tNFA_STATUS send(uint8_t *pData4Tx, uint8_t dataLen, uint8_t *rsp_len,
                     uint8_t *rsp_buf) {
      cmd_buf[2] = dataLen;
      memcpy(&cmd_buf[3], pData4Tx, dataLen);
      return Command::send(cmd_buf, (dataLen + CMD_HDR_SIZE_XCV), rsp_len,
                           rsp_buf);
    }

    tNFA_STATUS preProcessor() {
      tNFA_STATUS status = NFA_STATUS_OK;
      SecureElement &se = SecureElement::getInstance();

      if (!android::sIsNfaEnabled
          ) {
        LOG(ERROR) << StringPrintf("%s: Denying access due to SE listen mode active", __func__);
        return NFA_STATUS_REJECTED;
      }
      /*Reject request if wired mode is ongoing*/
      if (se.mIsWiredModeOpen == true) {
        LOG(ERROR) << StringPrintf("%s: Denying access due to Wired session is ongoing", __func__);
        status = NFA_STATUS_REJECTED;
      }
      return status;
    }
  };
  /*******************************************************************************
   **
   ** Class:        rdrPassThru
   **
   ** Description: This is a factory class which creates command instances
   **              It creates transceive commands only when it is in busy mode.
   **
   ** Returns:         byte[]
   **
  *******************************************************************************/
  class rdrPassThru {
  private:
    uint32_t state;
    typedef enum { IDLE, BUSY } STATE_e;

  public:
    rdrPassThru() { state = IDLE; }

    Command *createCommand(uint32_t rqst, uint32_t modulationType) {

      switch (rqst) {
      case RDR_PASS_THRU_MODE_ENABLE:
        state = BUSY;
        return new cmdEnablePassThru(modulationType, RDR_PASS_THRU_ENABLE_TIMEOUT, 3);
      case RDR_PASS_THRU_MODE_DISABLE:
        state = BUSY;
        return new cmdDisablePassThru(RDR_PASS_THRU_DISABLE_TIMEOUT, 3);
      case RDR_PASS_THRU_MODE_XCV:
        state = BUSY;
        return new cmdTransceive(RDR_PASS_THRU_DISABLE_TIMEOUT, 0);
      default:
        return NULL;
      }
      return NULL;
    }

tNFA_STATUS condOfUseSatisfied(uint32_t rqst, uint32_t modulationTyp) {
    if (rqst > RDR_PASS_THRU_MODE_XCV ||
        modulationTyp > RDR_PASS_THRU_MOD_TYPE_LIMIT) {
        LOG(ERROR) << StringPrintf("%s: Denying access due to Incorrect parameters", __func__);
        return NFA_STATUS_REJECTED;
    }
    if (rqst == RDR_PASS_THRU_MODE_XCV && state == IDLE) {
        LOG(ERROR) << StringPrintf("%s: Denying access due to Incorrect parameters", __func__);
        return NFA_STATUS_REJECTED;
    }
    return NFA_STATUS_OK;
}
void destroy(Command *pCmdInstance) { delete pCmdInstance; }
void destroy(uint32_t rqst, Command *pCmdInstance) {
    if (rqst == RDR_PASS_THRU_MODE_DISABLE)
        state = IDLE;
     delete pCmdInstance;
    }
};
rdrPassThru rdrPassThruMgr;
/*******************************************************************************
**
** Function:        nfcManager_readerPassThruMode()
**
** Description:     Sends  proprietary command to NFC controller to manage
**                  special FW mode
**
** Returns:         byte[]
**
*******************************************************************************/
static jbyteArray nfcManager_readerPassThruMode(
    JNIEnv * e, jobject o, jbyte rqst, jbyte modulationTyp) {
    tNFA_STATUS ret_stat = NFA_STATUS_OK;
    uint8_t rsp_buf[255];
    uint8_t rsp_len = 0;
    jbyteArray buf = NULL;
    static const char fn[] = "nfcManager_readerPassThruMode";
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:enter", fn);

    /*1. Check if the criteria for sending the command is satisfied*/
    if ((ret_stat = rdrPassThruMgr.condOfUseSatisfied(rqst, modulationTyp)) ==
        NFA_STATUS_OK) {

      /*1.1.  Retreive the requested command instance from reader pass through
       * manager (command factory)*/
      Command *pCommand = rdrPassThruMgr.createCommand(rqst, modulationTyp);

      /*1.2. Check valid command instance and command specific criteria for use */
      if (pCommand != NULL && pCommand->preProcessor() == NFA_STATUS_OK) {
        do {
          /*1.2.1. Send the command to NFC controller*/
          ret_stat = pCommand->send(&rsp_len, rsp_buf);

          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:Invoked", pCommand->name);

          /*1.2.2. Incase of failure retry till the retrial limit is met*/
        } while (ret_stat != NFA_STATUS_OK && pCommand->retryCount-- > 0);

        /*1.2.3. Invoke command post processor*/
        if (ret_stat == NFA_STATUS_OK)
          ret_stat = pCommand->postProcessor(rsp_len, rsp_buf);

        /*1.2.4. Delete the command instance*/
        rdrPassThruMgr.destroy(rqst, pCommand);
      }
    } else {
      /*2. If the criteria for sending the command is not satisfied; frame error
       * rsp*/
      rsp_len = sizeof(tNFA_STATUS);
      rsp_buf[0] = NFA_STATUS_FAILED;
    }
    /*3. Set JNI variables for sending response to application*/
    buf = e->NewByteArray(rsp_len);
    e->SetByteArrayRegion(buf, 0, rsp_len, (jbyte *)rsp_buf);
    return buf;
}

/*******************************************************************************
**
** Function:        nfcManager_transceiveAppData()
**
** Description:     Sends the proprietary transcieve app command
**
** Returns:         byte[]
**
*******************************************************************************/
static jbyteArray nfcManager_transceiveAppData(JNIEnv * e, jobject o,
                                                 jbyteArray data) {
    tNFA_STATUS ret_stat = NFA_STATUS_OK;
    uint8_t rsp_buf[255];
    uint8_t rsp_len = 0;
    uint8_t *dataBuf = NULL;
    uint16_t dataLen = 0;
    jbyteArray buf = NULL;
    static const char fn[] = "nfcManager_transceiveAppData";
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:enter", fn);

    /*1. Check if the criteria for sending the command is satisfied*/
    if ((ret_stat = rdrPassThruMgr.condOfUseSatisfied(RDR_PASS_THRU_MODE_XCV,
                                                      0)) == NFA_STATUS_OK) {

      /*1.1.  Retreive the requested command instance from reader pass through
       * manager (command factory)*/
      Command *pCommand = rdrPassThruMgr.createCommand(RDR_PASS_THRU_MODE_XCV, 0);

      /*1.2. Check valid command instance and command specific criteria for use */
      if (pCommand != NULL && pCommand->preProcessor() == NFA_STATUS_OK) {

        /*1.2.1.  Determine application data length */
        ScopedByteArrayRW bytes(e, data);
        dataBuf = const_cast<uint8_t *>(reinterpret_cast<uint8_t *>(&bytes[0]));
        dataLen = bytes.size();

        do {
          /*1.2.2. Send the command to NFC controller*/
          ret_stat = pCommand->send(dataBuf, dataLen, &rsp_len, rsp_buf);

          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:Invoked", pCommand->name);

          /*1.2.3. Incase of failure retry till the retrial limit is met*/
        } while (ret_stat != NFA_STATUS_OK && pCommand->retryCount-- > 0);

        /*1.2.4. Invoke command post processor*/
        if (ret_stat == NFA_STATUS_OK)
          ret_stat = pCommand->postProcessor(rsp_len, rsp_buf);

        /*1.2.5. Delete the command instance*/
        rdrPassThruMgr.destroy(pCommand);
      }
    } else {
      /*2. If the criteria for sending the command is not satisfied; frame error
       * rsp*/
      rsp_len = sizeof(tNFA_STATUS);
      rsp_buf[0] = NFA_STATUS_FAILED;
    }
    /*3. Set JNI variables for sending response to application*/

    buf = e->NewByteArray(rsp_len);
    e->SetByteArrayRegion(buf, 0, rsp_len, (jbyte *)rsp_buf);

    return buf;
}

/*******************************************************************************
**
** Function:        nfcManager_isNfccBusy
**
** Description:     Check If NFCC is busy
**
** Returns:         True if NFCC is busy.
**
*******************************************************************************/
static bool nfcManager_isNfccBusy(JNIEnv*, jobject) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: ENTER", __func__);
    bool statBusy = false;
   if (sSeRfActive || gActivated ) {
      LOG(ERROR) << StringPrintf("%s:FAIL  RF session ongoing", __func__);
      statBusy = true;
    }
    return statBusy;
}
static int nfcManager_setTransitConfig(JNIEnv * e, jobject o,
                                         jstring config) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    std::string transitConfig = ConvertJavaStrToStdString(e, config);
    SyncEventGuard guard(sNfaTransitConfigEvent);
    int stat = NFA_SetTransitConfig(transitConfig);
    if (stat != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: NFA_SetTransitConfig failed", __func__);
    } else {
      if(sNfaTransitConfigEvent.wait(10 * ONE_SECOND_MS) == false) {
        LOG(ERROR) << StringPrintf("Nfa transitConfig Event has terminated");
      }
    }
    return stat;
}

/*******************************************************************************
**
** Function:        ConvertJavaStrToStdString
**
** Description:     Convert Jstring to string
**                  e: JVM environment.
**                  o: Java object.
**                  s: Jstring.
**
** Returns:         std::string
**
*******************************************************************************/
static std::string ConvertJavaStrToStdString(JNIEnv * env, jstring s) {
    if (!s) return "";

    const jclass strClass = env->GetObjectClass(s);
    const jmethodID getBytes =
        env->GetMethodID(strClass, "getBytes", "(Ljava/lang/String;)[B");
    const jbyteArray strJbytes = (jbyteArray)env->CallObjectMethod(
        s, getBytes, env->NewStringUTF("UTF-8"));

    size_t length = (size_t)env->GetArrayLength(strJbytes);
    jbyte* pBytes = env->GetByteArrayElements(strJbytes, NULL);

    std::string ret = std::string((char*)pBytes, length);
    env->ReleaseByteArrayElements(strJbytes, pBytes, JNI_ABORT);

    env->DeleteLocalRef(strJbytes);
    env->DeleteLocalRef(strClass);
    return ret;
}

/*******************************************************************************
**
** Function:        nfcManager_getAidTableSize
** Description:     Get the maximum supported size for AID routing table.
**
**                  e: JVM environment.
**                  o: Java object.
**
*******************************************************************************/
static jint nfcManager_getAidTableSize (JNIEnv*, jobject )
{
    return NFA_GetAidTableSize();
}

/*******************************************************************************
**
** Function:        nfcManager_getRemainingAidTableSize
** Description:     Get the remaining size of AID routing table.
**
**                  e: JVM environment.
**                  o: Java object.
**
*******************************************************************************/
static jint nfcManager_getRemainingAidTableSize (JNIEnv* , jobject )
{
    return NFA_GetRemainingAidTableSize();
}

/*******************************************************************************
**
** Function:        nfcManager_doSelectUicc()
**
** Description:     Select the preferred UICC slot
**
** Returns:        Returns status as below
**                 DUAL_UICC_ERROR_STATUS_UNKNOWN when error status not defined.
**                 DUAL_UICC_ERROR_NFC_TURNING_OFF when Nfc is Disabling,
**                 DUAL_UICC_ERROR_INVALID_SLOT when slot id mismatch,
**                 DUAL_UICC_ERROR_NFCC_BUSY when RF session is ongoing
**                 DUAL_UICC_FEATURE_NOT_AVAILABLE when feature not available
**                 UICC_NOT_CONFIGURED when UICC is not configured.
*******************************************************************************/
static int nfcManager_doSelectUicc(JNIEnv* e, jobject o, jint uiccSlot) {
  (void)e;
  (void)o;
  int retStat = DUAL_UICC_ERROR_STATUS_UNKNOWN;
  tNFA_STATUS status = NFA_STATUS_FAILED;
  NativeJniExtns& jniExtns = NativeJniExtns::getInstance();
  if (!isDynamicUiccEnabled) {
    if (!jniExtns.isExtensionPresent()) {
      retStat = nfcManager_staticDualUicc_Precondition(uiccSlot);

      if (sSeRfActive || SecureElement::getInstance().isRfFieldOn()) {
        LOG(ERROR) << StringPrintf("%s:FAIL  RF session ongoing", __func__);
        retStat = DUAL_UICC_ERROR_NFCC_BUSY;
      }

      if (retStat != UICC_NOT_CONFIGURED) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("staticDualUicc_Precondition failed.");
        return retStat;
      }
    }
    status = nfcManager_setPreferredSimSlot(NULL, NULL, uiccSlot);
    if (status == NFA_STATUS_OK) retStat = UICC_CONFIGURED;
  } else {
    retStat = DUAL_UICC_FEATURE_NOT_AVAILABLE;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: Dual uicc not supported retStat = %d", __func__, retStat);
  }
  return retStat;
}

/*******************************************************************************
**
** Function:        nfcManager_doGetSelectedUicc()
**
** Description:     get the current selected active UICC
**
** Returns:         UICC id
**
*******************************************************************************/
static int nfcManager_doGetSelectedUicc(JNIEnv * e, jobject o) {
  uint8_t uicc_stat = STATUS_UNKNOWN_ERROR;
  if (!isDynamicUiccEnabled) {
    uicc_stat =
        SecureElement::getInstance().getUiccStatus(sCurrentSelectedUICCSlot);
  } else {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: dual uicc not supported ", __func__);
    uicc_stat = DUAL_UICC_FEATURE_NOT_AVAILABLE;
  }
  return uicc_stat;
}
/**********************************************************************************
**
** Function:        nfcManager_staticDualUicc_Precondition
**
** Description:    Performs precondition checks before switching UICC
**
** Returns:        Returns status as below
**                 DUAL_UICC_ERROR_NFC_TURNING_OFF when Nfc is Disabling,
**                 DUAL_UICC_ERROR_INVALID_SLOT when slot id mismatch,
**                 DUAL_UICC_FEATURE_NOT_AVAILABLE when feature not available,
**                 UICC_NOT_CONFIGURED when UICC is not configured.
**********************************************************************************/
static int nfcManager_staticDualUicc_Precondition(int uiccSlot) {
  if (isDynamicUiccEnabled) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s:Dual UICC feature not available . Returning", __func__);
    return DUAL_UICC_FEATURE_NOT_AVAILABLE;
  }

  int retStat = UICC_NOT_CONFIGURED;

  if (sIsDisabling) {
    LOG(ERROR) << StringPrintf(
        "%s:FAIL Nfc is Disabling : Switch UICC not allowed", __func__);
    retStat = DUAL_UICC_ERROR_NFC_TURNING_OFF;
  } else if ((uiccSlot != 0x01) && (uiccSlot != 0x02)) {
    LOG(ERROR) << StringPrintf("%s: Invalid slot id", __func__);
    retStat = DUAL_UICC_ERROR_INVALID_SLOT;
  }
  return retStat;
}

/*******************************************************************************
**
** Function:        nfcManager_setPreferredSimSlot()
**
** Description:     This api is used to select a particular UICC slot.
**
**
** Returns:         success/failure
**
*******************************************************************************/
static int nfcManager_setPreferredSimSlot(JNIEnv* e, jobject o,
                                          jint uiccSlot) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s : uiccslot : %d : enter", __func__, uiccSlot);

  int retStat = UICC_NOT_CONFIGURED;
  NativeJniExtns& jniExtns = NativeJniExtns::getInstance();
  if (!isDynamicUiccEnabled) {
    if (jniExtns.isExtensionPresent()) {
      retStat = nfcManager_staticDualUicc_Precondition(uiccSlot);

      if (retStat != UICC_NOT_CONFIGURED) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("staticDualUicc_Precondition failed.");
        return NFA_STATUS_FAILED;
      }
    }
    sCurrentSelectedUICCSlot = uiccSlot;
    NFA_SetPreferredUiccId(
        (uiccSlot == 2) ? (SecureElement::getInstance().EE_HANDLE_0xF8 &
                           ~NFA_HANDLE_GROUP_EE)
                        : (SecureElement::getInstance().EE_HANDLE_0xF4 &
                           ~NFA_HANDLE_GROUP_EE));
  }
  return NFA_STATUS_OK;
}
#endif
} /* namespace android */
/* namespace android */
/*******************************************************************************
 **
 ** Function:        nfcManager_getUiccId()
 **
 ** Description:
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
jint nfcManager_getUiccId(jint uicc_slot)
{

    if((uicc_slot == 0x00) || (uicc_slot == 0x01))
    {
        return 0x02;
    }
    else if(uicc_slot == 0x02)
    {
        return 0x04;
    }
    else
    {
        return 0xFF;
    }
}

jint nfcManager_getUiccRoute(jint uicc_slot)
{

    if(uicc_slot == 0x01)
    {
        return 0x480;
    }
    else if(uicc_slot == 0x02)
    {
        return 0x481;
    }
    else
    {
        return 0xFF;
    }
}
#endif

