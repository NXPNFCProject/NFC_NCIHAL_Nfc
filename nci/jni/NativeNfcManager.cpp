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
#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <cutils/properties.h>
#include <errno.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <semaphore.h>

#include "HciEventManager.h"
#include "JavaClassConstants.h"
#include "NfcAdaptation.h"
#ifdef DTA_ENABLED
#include "NfcDta.h"
#endif /* DTA_ENABLED */
#include "NfcJniUtil.h"
#include "NfcTag.h"
#include "PowerSwitch.h"
#include "RoutingManager.h"
#include "SyncEvent.h"
#if(NXP_EXTNS == TRUE)
#include "DwpChannel.h"
#include "MposManager.h"
#include "NativeExtFieldDetect.h"
#include "NativeJniExtns.h"
#include "NativeT4tNfcee.h"
#include "NfcSelfTest.h"
#include "NfcTagExtns.h"
#include "SecureElement.h"
#include "nfa_nfcee_int.h"
#if (NXP_SRD == TRUE)
#include "SecureDigitization.h"
#endif
#endif

#include "android_nfc.h"
#include "ce_api.h"
#include "debug_lmrt.h"
#include "nfa_api.h"
#include "nfa_ee_api.h"
#include "nfc_brcm_defs.h"
#include "nfc_config.h"
#include "rw_api.h"

using android::base::StringPrintf;
#if(NXP_EXTNS == TRUE)
#define SECURE_ELEMENT_UICC_SLOT_DEFAULT (0x01)

bool isDynamicUiccEnabled;
bool isDisconnectNeeded;
bool isCePriorityEnabled;
#endif
extern tNFA_DM_DISC_FREQ_CFG* p_nfa_dm_rf_disc_freq_cfg;  // defined in stack
namespace android {
extern bool gIsTagDeactivating;
extern bool gIsSelectingRfInterface;
extern void nativeNfcTag_doTransceiveStatus(tNFA_STATUS status, uint8_t* buf,
                                            uint32_t buflen);
#if(NXP_EXTNS == TRUE)
extern void nativeNfcTag_notifyRfTimeout(tNFA_STATUS status);
extern jint nfcManager_dodeactivateSeInterface(JNIEnv* e, jobject o);
#else
extern void nativeNfcTag_notifyRfTimeout();
#endif
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
extern void nativeNfcTag_setActivatedRfProtocol(tNFA_INTF_TYPE rfProtocol);
extern void nativeNfcTag_abortWaits();
extern void nativeNfcTag_registerNdefTypeHandler();
extern void nativeNfcTag_acquireRfInterfaceMutexLock();
extern void nativeNfcTag_releaseRfInterfaceMutexLock();
#if(NXP_EXTNS == TRUE)
extern tNFA_STATUS Nxp_doResonantFrequency(bool modeOn);
extern tNFA_STATUS nativeNfcTag_safeDisconnect();
int nfcManager_doPartialInitialize(JNIEnv* e, jobject o, jint mode);
int nfcManager_doPartialDeInitialize(JNIEnv* e, jobject o);
extern tNFA_STATUS NxpNfc_Write_Cmd_Common(uint8_t retlen, uint8_t* buffer);
extern void NxpNfc_GetHwInfo(void);
extern tNFA_STATUS send_flush_ram_to_flash();
extern void nativeNfcTag_abortTagOperations(tNFA_STATUS status);
extern void nfaVSCNtfCallback(uint8_t event, uint16_t param_len, uint8_t *p_param);
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
int recovery_option = 0;
#if (NXP_EXTNS == TRUE)
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
jmethodID gCachedNfcManagerNotifyHostEmuActivated;
jmethodID gCachedNfcManagerNotifyHostEmuData;
jmethodID gCachedNfcManagerNotifyHostEmuDeactivated;
jmethodID gCachedNfcManagerNotifyRfFieldActivated;
jmethodID gCachedNfcManagerNotifyRfFieldDeactivated;
jmethodID gCachedNfcManagerNotifyHwErrorReported;
jmethodID gCachedNfcManagerNotifyPollingLoopFrame;
jmethodID gCachedNfcManagerNotifyVendorSpecificEvent;
jmethodID gCachedNfcManagerNotifyCommandTimeout;
#if(NXP_EXTNS == TRUE)
jmethodID gCachedNfcManagerNotifyLxDebugInfo;
jmethodID gCachedNfcManagerNotifyTagAbortListeners;
jmethodID gCachedNfcManagerNotifyCoreGenericError;
#endif

const char* gNativeNfcTagClassName = "com/android/nfc/dhimpl/NativeNfcTag";
const char* gNativeNfcManagerClassName =
    "com/android/nfc/dhimpl/NativeNfcManager";
const char* gNfcVendorNciResponseClassName =
    "com/android/nfc/NfcVendorNciResponse";
#if (NXP_EXTNS == TRUE)
const char* gNativeNfcSecureElementClassName =
    "com/android/nfc/dhimpl/NativeNfcSecureElement";
const char*             gNativeNfcMposManagerClassName       =
    "com/android/nfc/dhimpl/NativeNfcMposManager";
const char* gNativeT4tNfceeClassName =
    "com/android/nfc/dhimpl/NativeT4tNfceeManager";
const char* gNativeExtFieldDetectClassName =
    "com/android/nfc/dhimpl/NativeExtFieldDetectManager";
void enableLastRfDiscovery();
void storeLastDiscoveryParams(int technologies_mask, bool enable_lptd,
                              bool reader_mode, bool enable_host_routing,
                              bool restart);
#endif
jmethodID  gCachedNfcManagerNotifySeListenActivated;
jmethodID  gCachedNfcManagerNotifySeListenDeactivated;
jmethodID  gCachedNfcManagerNotifyEeUpdated;
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
static SyncEvent sNfaEnableEvent;                // event for NFA_Enable()
static SyncEvent sNfaDisableEvent;               // event for NFA_Disable()
#if(NXP_EXTNS == TRUE)
SyncEvent sNfaEnableDisablePollingEvent;
#else
static SyncEvent sNfaEnableDisablePollingEvent;  // event for
#endif                                           // NFA_EnablePolling(),
                                                 // NFA_DisablePolling()
SyncEvent gNfaSetConfigEvent;                    // event for Set_Config....
SyncEvent gNfaGetConfigEvent;                    // event for Get_Config....
SyncEvent gNfaVsCommand;                         // event for VS commands
SyncEvent gSendRawVsCmdEvent;  // event for NFA_SendRawVsCommand()
#if(NXP_EXTNS == TRUE)
static SyncEvent sNfaTransitConfigEvent;  // event for NFA_SetTransitConfig()
bool suppressLogs = true;
#endif
static bool sIsNfaEnabled = false;
static bool sDiscoveryEnabled = false;  // is polling or listening
static bool sPollingEnabled = false;    // is polling for tag?
static bool sIsDisabling = false;
static bool sRfEnabled = false;   // whether RF discovery is enabled
static bool sSeRfActive = false;  // whether RF with SE is likely active
static bool sReaderModeEnabled =
    false;  // whether we're only reading tags, not allowing card emu
static bool sAbortConnlessWait = false;
static jint sLfT3tMax = 0;
static bool sRoutingInitialized = false;
static bool sIsRecovering = false;
static std::vector<uint8_t> sRawVendorCmdResponse;

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

typedef field_detect_status_t rssi_status_t;

#endif

static void nfaConnectionCallback(uint8_t event, tNFA_CONN_EVT_DATA* eventData);
static void nfaDeviceManagementCallback(uint8_t event,
                                        tNFA_DM_CBACK_DATA* eventData);
static bool isListenMode(tNFA_ACTIVATED& activated);
#if (NXP_EXTNS==FALSE)
static void enableDisableLptd(bool enable);
#endif

static tNFA_STATUS stopPolling_rfDiscoveryDisabled();
static tNFA_STATUS startPolling_rfDiscoveryDisabled(
    tNFA_TECHNOLOGY_MASK tech_mask);
static void nfcManager_doSetScreenState(JNIEnv* e, jobject o,
                                      jint screen_state_mask);
static jboolean nfcManager_doSetPowerSavingMode(JNIEnv* e, jobject o,
                                                bool flag);
static void sendRawVsCmdCallback(uint8_t event, uint16_t param_len,
                                 uint8_t* p_param);
static jbyteArray nfcManager_getProprietaryCaps(JNIEnv* e, jobject o);
#if(NXP_EXTNS == TRUE)
static jint nfcManager_getFwVersion(JNIEnv* e, jobject o);
static bool nfcManager_isNfccBusy(JNIEnv*, jobject);
static int nfcManager_setTransitConfig(JNIEnv* e, jobject o, jstring config);
std::string ConvertJavaStrToStdString(JNIEnv* env, jstring s);
static jint nfcManager_getAidTableSize (JNIEnv*, jobject );
static jint nfcManager_getRemainingAidTableSize (JNIEnv* , jobject );
static int nfcManager_doSelectUicc(JNIEnv* e, jobject o, jint uiccSlot);
static int nfcManager_doGetSelectedUicc(JNIEnv* e, jobject o);
static jint nfcManager_nfcSelfTest(JNIEnv* e, jobject o, jint aType);
static int nfcManager_staticDualUicc_Precondition(int uiccSlot);
static int nfcManager_setPreferredSimSlot(JNIEnv* e, jobject o, jint uiccSlot);
static bool nfcManager_deactivateOnPollDisabled(tNFA_ACTIVATED& activated);
static jint nfcManager_enableDebugNtf(JNIEnv* e, jobject o, jbyte fieldValue);
static void waitIfRfStateActive();
static rssi_status_t nfcManager_doSetRssiMode(bool enable,
                                              int rssiNtfTimeIntervalInMillisec);
static void nfcManager_restartRFDiscovery(JNIEnv* e, jobject o);
#endif


tNFA_STATUS gVSCmdStatus = NFA_STATUS_OK;
uint16_t gCurrentConfigLen;
uint8_t gConfig[256];
std::vector<uint8_t> gCaps(0);
static int NFA_SCREEN_POLLING_TAG_MASK = 0x10;
static bool gIsDtaEnabled = false;
static bool gObserveModeEnabled = false;
#if (NXP_EXTNS==TRUE)

bool gsNfaPartialEnabled = false;
#endif
#if (NXP_EXTNS==TRUE)
static int prevScreenState = NFA_SCREEN_STATE_UNKNOWN;
static bool scrnOnLockedPollDisabled = false;
#else
static int prevScreenState = NFA_SCREEN_STATE_OFF_UNLOCKED;
#endif
/////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////

namespace {
void initializeGlobalDebugEnabledFlag() {
    bool nfc_debug_enabled =
        (NfcConfig::getUnsigned(NAME_NFC_DEBUG_ENABLED, 1) != 0) ||
        property_get_bool("persist.nfc.debug_enabled", true);

    android::base::SetMinimumLogSeverity(
        nfc_debug_enabled ? android::base::DEBUG : android::base::INFO);
}

void initializeRecoveryOption() {
  recovery_option = NfcConfig::getUnsigned(NAME_RECOVERY_OPTION, 0);

  LOG(DEBUG) << __func__ << ": recovery option=" << recovery_option;
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
  NfcTag& natTag = NfcTag::getInstance();

  LOG(DEBUG) << StringPrintf("%s: ", __func__);

  if (discoveredDevice->protocol != NFA_PROTOCOL_NFC_DEP) {
    natTag.setNumDiscNtf(natTag.getNumDiscNtf() + 1);
  }
  if (discoveredDevice->more == NCI_DISCOVER_NTF_MORE) {
    // there is more discovery notification coming
    return;
  }

  if (natTag.getNumDiscNtf() > 1) {
    natTag.setMultiProtocolTagSupport(true);
  }

  natTag.setNumDiscNtf(natTag.getNumDiscNtf() - 1);
  // select the first of multiple tags that is discovered
  natTag.selectFirstTag();
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
  uint8_t activatedProtocol;
#if (NXP_EXTNS == TRUE)
  static uint8_t prev_more_val = 0x00;
  uint8_t cur_more_val = 0x00;
  NfcTagExtns& nfcTagExtns = NfcTagExtns::getInstance();
#endif
  LOG(DEBUG) << StringPrintf("%s: event= %u", __func__, connEvent);
#if (NXP_EXTNS == TRUE)
  NativeJniExtns::getInstance().notifyNfcEvent(
      "nfaConnectionCallback", (void*)&connEvent, (void*)eventData);
#endif
  switch (connEvent) {
    case NFA_LISTEN_ENABLED_EVT:  // whether listening successfully started
    {
      LOG(DEBUG) << StringPrintf("%s: NFA_LISTEN_ENABLED_EVT:status= %u",
                                 __func__, eventData->status);

      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;
    case NFA_POLL_ENABLED_EVT:  // whether polling successfully started
    {
      LOG(DEBUG) << StringPrintf("%s: NFA_POLL_ENABLED_EVT: status = %u",
                                 __func__, eventData->status);

      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_POLL_DISABLED_EVT:  // Listening/Polling stopped
    {
      LOG(DEBUG) << StringPrintf("%s: NFA_POLL_DISABLED_EVT: status = %u",
                                 __func__, eventData->status);

      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_RF_DISCOVERY_STARTED_EVT:  // RF Discovery started
    {
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_RF_DISCOVERY_STARTED_EVT: status = %u", __func__,
          eventData->status);

      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_RF_DISCOVERY_STOPPED_EVT:  // RF Discovery stopped event
    {
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_RF_DISCOVERY_STOPPED_EVT: status = %u", __func__,
          eventData->status);

      gActivated = false;
#if (NXP_EXTNS == TRUE)
      if (SecureElement::getInstance().isRfFieldOn()) {
        SecureElement::getInstance().mRfFieldIsOn= false;
      }
#endif

      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_DISC_RESULT_EVT:  // NFC link/protocol discovery notificaiton
      status = eventData->disc_result.status;
      LOG(DEBUG) << StringPrintf("%s: NFA_DISC_RESULT_EVT: status = %d",
                                 __func__, status);
#if (NXP_EXTNS == TRUE)
      cur_more_val = eventData->disc_result.discovery_ntf.more;
      if((cur_more_val == 0x01) && (prev_more_val != 0x02)) {
        LOG(ERROR) << StringPrintf("%s: NFA_DISC_RESULT_EVT: Failed", __func__);
        status = NFA_STATUS_FAILED;
      } else {
        LOG(DEBUG) << StringPrintf("%s: NFA_DISC_RESULT_EVT: Success",
                                   __func__);
        status = NFA_STATUS_OK;
        prev_more_val = cur_more_val;
      }
#endif
      if (status != NFA_STATUS_OK) {
        NfcTag::getInstance().setNumDiscNtf(0);
        LOG(ERROR) << StringPrintf("%s: NFA_DISC_RESULT_EVT error: status = %d",
                                   __func__, status);
      } else {
        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
#if (NXP_EXTNS == TRUE)
        nfcTagExtns.processNonStdNtfHandler(EVENT_TYPE::NFA_DISC_RESULT_EVENT,
                                            eventData);
#endif
        handleRfDiscoveryEvent(&eventData->disc_result.discovery_ntf);
      }
      break;

    case NFA_SELECT_RESULT_EVT:  // NFC link/protocol discovery select response
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_SELECT_RESULT_EVT: status = %d, gIsSelectingRfInterface = "
          "%d, "
          "sIsDisabling=%d",
          __func__, eventData->status, gIsSelectingRfInterface, sIsDisabling);

      if (sIsDisabling) break;
#if (NXP_EXTNS == TRUE)
      nfcTagExtns.processNonStdNtfHandler(EVENT_TYPE::NFA_SELECT_RESULT_EVENT,
                                          eventData);
#endif
      if (eventData->status != NFA_STATUS_OK) {
        if (gIsSelectingRfInterface) {
          nativeNfcTag_doConnectStatus(false);
        }

        LOG(ERROR) << StringPrintf(
            "%s: NFA_SELECT_RESULT_EVT error: status = %d", __func__,
            eventData->status);
        NFA_Deactivate(FALSE);
      }
      break;

    case NFA_DEACTIVATE_FAIL_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFA_DEACTIVATE_FAIL_EVT: status = %d",
                                 __func__, eventData->status);
#if (NXP_EXTNS == TRUE)
      nfcTagExtns.processNonStdNtfHandler(EVENT_TYPE::NFA_DEACTIVATE_FAIL_EVENT,
                                          eventData);
#endif
      break;

    case NFA_ACTIVATED_EVT:  // NFC link/protocol activated
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_ACTIVATED_EVT: gIsSelectingRfInterface=%d, sIsDisabling=%d",
          __func__, gIsSelectingRfInterface, sIsDisabling);
      activatedProtocol = (tNFA_INTF_TYPE)eventData->activated.activate_ntf.protocol;
      if (NFC_PROTOCOL_T5T == activatedProtocol &&
          NfcTag::getInstance().getNumDiscNtf()) {
        /* T5T doesn't support multiproto detection logic */
        NfcTag::getInstance().setNumDiscNtf(0);
      }
#if (NXP_EXTNS == TRUE)
      nfcTagExtns.resetMfcTransceiveFlag();
      if (NfcSelfTest::GetInstance().SelfTestType != TEST_TYPE_NONE) {
        NfcSelfTest::GetInstance().ActivatedNtf_Cb();
        break;
      }
#endif
      if ((eventData->activated.activate_ntf.protocol !=
           NFA_PROTOCOL_NFC_DEP) &&
          (!isListenMode(eventData->activated))) {
        nativeNfcTag_setRfInterface(
                (tNFA_INTF_TYPE)eventData->activated.activate_ntf.intf_param.type);
        nativeNfcTag_setActivatedRfProtocol(activatedProtocol);
      }
      // If it activated in listen mode then it is likely for an
      // SE transaction. Send the RF Event.
      if (isListenMode(eventData->activated)) {
        sSeRfActive = true;
#if (NXP_EXTNS == TRUE)
        SecureElement::getInstance().notifyListenModeState(true);
#endif
      }
      NfcTag::getInstance().setActive(true);
      if (sIsDisabling || !sIsNfaEnabled) break;
      gActivated = true;

#if (NXP_EXTNS == TRUE)
      nfcTagExtns.processNonStdNtfHandler(EVENT_TYPE::NFA_ACTIVATED_EVENT,
                                          eventData);
#endif
#if (NXP_EXTNS != TRUE)
      NfcTag::getInstance().setActivationState();
#endif
      if (gIsSelectingRfInterface) {
        nativeNfcTag_doConnectStatus(true);
        break;
      }
#if (NXP_EXTNS == TRUE)
      NfcTag::getInstance().setActivationState();
#endif

      nativeNfcTag_resetPresenceCheck();
#if (NXP_EXTNS == TRUE)
      if (nfcManager_deactivateOnPollDisabled(eventData->activated)) break;
#else
      if (!isListenMode(eventData->activated) &&
          (prevScreenState == NFA_SCREEN_STATE_OFF_LOCKED ||
           prevScreenState == NFA_SCREEN_STATE_OFF_UNLOCKED)) {
          NFA_Deactivate(FALSE);
      }
#endif
      NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
      if (NfcTag::getInstance().getNumDiscNtf()) {
        /*If its multiprotocol tag, deactivate tag with current selected
        protocol to sleep . Select tag with next supported protocol after
        deactivation event is received*/
        NFA_Deactivate(true);
      }
      break;
#if (NXP_EXTNS == TRUE)
    case NFA_RF_REMOVAL_DETECTION_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFA_RF_REMOVAL_DETECTION_EVT "
          "status = %d", __func__, eventData->status);
      if (NFA_STATUS_OK != eventData->status) {
        eventData->deactivated.type = NFA_DEACTIVATE_TYPE_DISCOVERY;
        eventData->deactivated.reason = NCI_DEACTIVATE_REASON_RF_TIMEOUT_EXCEPTION;
        LOG(DEBUG) << StringPrintf(
            "%s: falling through NFA_DEACTIVATED_EVT to recover", __func__);
        /* NFCC may be in RF_POLL_ACTIVE or RF_POLL_REMOVAL_DETECTION,
           Anyways, DEACTIVATE_TO_DISCOVER is allowed in both of these states*/
        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
      }
      break;
#endif
    case NFA_DEACTIVATED_EVT:  // NFC link/protocol deactivated
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_DEACTIVATED_EVT   Type: %u, gIsTagDeactivating: %d",
          __func__, eventData->deactivated.type, gIsTagDeactivating);

#if (NXP_EXTNS == TRUE)
      if (NfcSelfTest::GetInstance().SelfTestType != TEST_TYPE_NONE) {
        break;
      }
#endif
      NfcTag::getInstance().setDeactivationState(eventData->deactivated);
      NfcTag::getInstance().selectNextTagIfExists();
#if (NXP_EXTNS == TRUE)
      // can be moved to non-std tag handling
      nfcTagExtns.processNonStdNtfHandler(EVENT_TYPE::NFA_DEACTIVATE_EVENT,
                                          eventData);
#endif
      if (eventData->deactivated.type != NFA_DEACTIVATE_TYPE_SLEEP) {
        {
          SyncEventGuard g(gDeactivatedEvent);
          gActivated = false;  // guard this variable from multi-threaded access
          gDeactivatedEvent.notifyOne();
        }
        nativeNfcTag_resetPresenceCheck();
#if (NXP_EXTNS != TRUE)
        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
        nativeNfcTag_abortWaits();
#endif
        NfcTag::getInstance().abort();
      } else if (gIsTagDeactivating) {
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
        }
      }

      break;

    case NFA_TLV_DETECT_EVT:  // TLV Detection complete
      status = eventData->tlv_detect.status;
      LOG(DEBUG) << StringPrintf(
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
      LOG(DEBUG) << StringPrintf(
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
      LOG(DEBUG) << StringPrintf("%s: NFA_DATA_EVT: status = 0x%X, len = %d",
                                 __func__, eventData->status,
                                 eventData->data.len);
      nativeNfcTag_doTransceiveStatus(eventData->status, eventData->data.p_data,
                                      eventData->data.len);
      break;
    case NFA_RW_INTF_ERROR_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFC_RW_INTF_ERROR_EVT", __func__);
#if(NXP_EXTNS == TRUE)
      nativeNfcTag_abortTagOperations(eventData->status);
#else
      nativeNfcTag_notifyRfTimeout();
      nativeNfcTag_doReadCompleted(NFA_STATUS_TIMEOUT);
#endif
      break;
    case NFA_SELECT_CPLT_EVT:  // Select completed
      status = eventData->status;
      LOG(DEBUG) << StringPrintf("%s: NFA_SELECT_CPLT_EVT: status = %d",
                                 __func__, status);
      if (status != NFA_STATUS_OK) {
        LOG(ERROR) << StringPrintf("%s: NFA_SELECT_CPLT_EVT error: status = %d",
                                   __func__, status);
      }
      break;

    case NFA_READ_CPLT_EVT:  // NDEF-read or tag-specific-read completed
      LOG(DEBUG) << StringPrintf("%s: NFA_READ_CPLT_EVT: status = 0x%X",
                                 __func__, eventData->status);
      nativeNfcTag_doReadCompleted(eventData->status);
      NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
      break;

    case NFA_WRITE_CPLT_EVT:  // Write completed
      LOG(DEBUG) << StringPrintf("%s: NFA_WRITE_CPLT_EVT: status = %d",
                                 __func__, eventData->status);
      nativeNfcTag_doWriteStatus(eventData->status == NFA_STATUS_OK);
      break;

    case NFA_SET_TAG_RO_EVT:  // Tag set as Read only
      LOG(DEBUG) << StringPrintf("%s: NFA_SET_TAG_RO_EVT: status = %d",
                                 __func__, eventData->status);
      nativeNfcTag_doMakeReadonlyResult(eventData->status);
      break;

    case NFA_CE_NDEF_WRITE_START_EVT:  // NDEF write started
      LOG(DEBUG) << StringPrintf("%s: NFA_CE_NDEF_WRITE_START_EVT: status: %d",
                                 __func__, eventData->status);

      if (eventData->status != NFA_STATUS_OK)
        LOG(ERROR) << StringPrintf(
            "%s: NFA_CE_NDEF_WRITE_START_EVT error: status = %d", __func__,
            eventData->status);
      break;

    case NFA_CE_NDEF_WRITE_CPLT_EVT:  // NDEF write completed
      LOG(DEBUG) << StringPrintf("%s: FA_CE_NDEF_WRITE_CPLT_EVT: len = %u",
                                 __func__, eventData->ndef_write_cplt.len);
      break;

    case NFA_PRESENCE_CHECK_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFA_PRESENCE_CHECK_EVT", __func__);
      nativeNfcTag_doPresenceCheckResult(eventData->status);
      break;
    case NFA_FORMAT_CPLT_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFA_FORMAT_CPLT_EVT: status=0x%X",
                                 __func__, eventData->status);
      nativeNfcTag_formatStatus(eventData->status == NFA_STATUS_OK);
      break;

    case NFA_I93_CMD_CPLT_EVT:
      LOG(DEBUG) << StringPrintf("%s: NFA_I93_CMD_CPLT_EVT: status=0x%X",
                                 __func__, eventData->status);
      break;

    case NFA_CE_UICC_LISTEN_CONFIGURED_EVT:
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_CE_UICC_LISTEN_CONFIGURED_EVT : status=0x%X", __func__,
          eventData->status);
      break;
#if (NXP_EXTNS == TRUE)
    case NFA_T4TNFCEE_EVT:
    case NFA_T4TNFCEE_READ_CPLT_EVT:
    case NFA_T4TNFCEE_WRITE_CPLT_EVT:
    case NFA_T4TNFCEE_CLEAR_CPLT_EVT:
      t4tNfcEe.eventHandler(connEvent, eventData);
      break;
    case NFA_CORE_GENERIC_ERROR_EVT:
      if (NfcSelfTest::GetInstance().SelfTestType != TEST_TYPE_NONE &&
          eventData->status == NCI_DISCOVERY_TARGET_ACTIVATION_FAILED) {
        // For SelfTest both Activation success/Failure marks end of test loop.
        NfcSelfTest::GetInstance().ActivatedNtf_Cb();
      }
      break;
#endif

    default:
      LOG(DEBUG) << StringPrintf("%s: unknown event (%d) ????", __func__,
                                 connEvent);
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
  initializeRecoveryOption();
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);

  nfc_jni_native_data* nat =
      (nfc_jni_native_data*)malloc(sizeof(struct nfc_jni_native_data));
  if (nat == NULL) {
    LOG(ERROR) << StringPrintf("%s: fail allocate native data", __func__);
    return JNI_FALSE;
  }
#if(NXP_EXTNS == TRUE)
  memset(nat, 0, sizeof(struct nfc_jni_native_data));
#else
  memset(nat, 0, sizeof(*nat));
#endif
  e->GetJavaVM(&(nat->vm));
  nat->env_version = e->GetVersion();
  nat->manager = e->NewGlobalRef(o);

  ScopedLocalRef<jclass> cls(e, e->GetObjectClass(o));
  jfieldID f = e->GetFieldID(cls.get(), "mNative", "J");
  e->SetLongField(o, f, (jlong)nat);
#if(NXP_EXTNS == TRUE)
  MposManager::initMposNativeStruct(e, o);
  NativeExtFieldDetect::getInstance().initEfdmNativeStruct(e, o);
#if (NXP_SRD == TRUE)
  SecureDigitization::getInstance().initSrdNativeStruct(e, o);
#endif
#endif
  /* Initialize native cached references */
  gCachedNfcManagerNotifyNdefMessageListeners =
      e->GetMethodID(cls.get(), "notifyNdefMessageListeners",
                     "(Lcom/android/nfc/dhimpl/NativeNfcTag;)V");

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
  gCachedNfcManagerNotifyEeUpdated =
      e->GetMethodID(cls.get(),"notifyEeUpdated", "()V");
  gCachedNfcManagerNotifyHwErrorReported =
      e->GetMethodID(cls.get(), "notifyHwErrorReported", "()V");
  gCachedNfcManagerNotifyPollingLoopFrame =
      e->GetMethodID(cls.get(), "notifyPollingLoopFrame", "(I[B)V");

  gCachedNfcManagerNotifyVendorSpecificEvent =
      e->GetMethodID(cls.get(), "notifyVendorSpecificEvent", "(II[B)V");

  gCachedNfcManagerNotifyCommandTimeout =
      e->GetMethodID(cls.get(), "notifyCommandTimeout", "()V");
#if(NXP_EXTNS == TRUE)
  gCachedNfcManagerNotifyLxDebugInfo =
      e->GetMethodID(cls.get(), "notifyNfcDebugInfo", "(I[B)V");

  gCachedNfcManagerNotifySeListenActivated =
      e->GetMethodID(cls.get(),"notifySeListenActivated", "()V");
  gCachedNfcManagerNotifySeListenDeactivated =
      e->GetMethodID(cls.get(),"notifySeListenDeactivated", "()V");
  gCachedNfcManagerNotifyTagAbortListeners =
      e->GetMethodID(cls.get(), "notifyTagAbort", "()V");
  gCachedNfcManagerNotifyCoreGenericError =
      e->GetMethodID(cls.get(), "notifyCoreGenericError", "(I)V");
#endif
  gCachedNfcManagerNotifyTransactionListeners = e->GetMethodID(
      cls.get(), "notifyTransactionListeners", "([B[BLjava/lang/String;)V");
  if (nfc_jni_cache_object(e, gNativeNfcTagClassName, &(nat->cached_NfcTag)) ==
      -1) {
    LOG(ERROR) << StringPrintf("%s: fail cache NativeNfcTag", __func__);
    return JNI_FALSE;
  }

  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
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
  LOG(DEBUG) << StringPrintf("%s: enter; event=0x%X", __func__, dmEvent);
#if (NXP_EXTNS == TRUE)
  NativeJniExtns::getInstance().notifyNfcEvent(
      "nfaDeviceManagementCallback", (void*)&dmEvent, (void*)eventData);
#endif

  switch (dmEvent) {
    case NFA_DM_ENABLE_EVT: /* Result of NFA_Enable */
    {
      SyncEventGuard guard(sNfaEnableEvent);
      LOG(DEBUG) << StringPrintf("%s: NFA_DM_ENABLE_EVT; status=0x%X", __func__,
                                 eventData->status);
      sIsNfaEnabled = eventData->status == NFA_STATUS_OK;
      sIsDisabling = false;
      sNfaEnableEvent.notifyOne();
    } break;

    case NFA_DM_DISABLE_EVT: /* Result of NFA_Disable */
    {
      SyncEventGuard guard(sNfaDisableEvent);
      LOG(DEBUG) << StringPrintf("%s: NFA_DM_DISABLE_EVT", __func__);
      sIsNfaEnabled = false;
      sIsDisabling = false;
      sNfaDisableEvent.notifyOne();
    } break;

    case NFA_DM_SET_CONFIG_EVT:  // result of NFA_SetConfig
      LOG(DEBUG) << StringPrintf("%s: NFA_DM_SET_CONFIG_EVT", __func__);
      {
        SyncEventGuard guard(gNfaSetConfigEvent);
        gNfaSetConfigEvent.notifyOne();
      }
      break;

    case NFA_DM_GET_CONFIG_EVT: /* Result of NFA_GetConfig */
      LOG(DEBUG) << StringPrintf("%s: NFA_DM_GET_CONFIG_EVT", __func__);
      {
        SyncEventGuard guard(gNfaGetConfigEvent);
        if (eventData->status == NFA_STATUS_OK &&
            eventData->get_config.tlv_size <= sizeof(gConfig)) {
          gCurrentConfigLen = eventData->get_config.tlv_size;
          memcpy(gConfig, eventData->get_config.param_tlvs,
                 eventData->get_config.tlv_size);
        } else {
          LOG(ERROR) << StringPrintf("%s: NFA_DM_GET_CONFIG failed", __func__);
          gCurrentConfigLen = 0;
        }
        gNfaGetConfigEvent.notifyOne();
      }
      break;

    case NFA_DM_RF_FIELD_EVT:
      LOG(DEBUG) << StringPrintf(
          "%s: NFA_DM_RF_FIELD_EVT; status=0x%X; field status=%u", __func__,
          eventData->rf_field.status, eventData->rf_field.rf_field_status);
#if(NXP_EXTNS == TRUE)
      if (extFieldDetectMode.isextendedFieldDetectMode() &&
          (eventData->rf_field.rf_field_status == NFA_DM_RF_FIELD_ON)) {
        extFieldDetectMode.startEfdmTimer();
      }
      SecureElement::getInstance().notifyRfFieldEvent (
                    eventData->rf_field.rf_field_status == NFA_DM_RF_FIELD_ON);
#else
if (eventData->rf_field.status == NFA_STATUS_OK) {
        struct nfc_jni_native_data* nat = getNative(NULL, NULL);
        if (!nat) {
          LOG(ERROR) << StringPrintf("cached nat is null");
          return;
        }
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
#endif
      break;

    case NFA_DM_NFCC_TRANSPORT_ERR_EVT:
    case NFA_DM_NFCC_TIMEOUT_EVT: {
      if (dmEvent == NFA_DM_NFCC_TIMEOUT_EVT)
        LOG(ERROR) << StringPrintf("%s: NFA_DM_NFCC_TIMEOUT_EVT; abort",
                                   __func__);
      else if (dmEvent == NFA_DM_NFCC_TRANSPORT_ERR_EVT)
        LOG(ERROR) << StringPrintf("%s: NFA_DM_NFCC_TRANSPORT_ERR_EVT; abort",
                                   __func__);
      struct nfc_jni_native_data* nat = getNative(NULL, NULL);
      JNIEnv* e = NULL;
      ScopedAttach attach(nat->vm, &e);
      if (e == NULL) {
        LOG(ERROR) << StringPrintf("jni env is null");
        return;
      }
      if (recovery_option && nat != NULL) {
        LOG(ERROR) << StringPrintf("%s: toggle NFC state to recovery nfc",
                                   __func__);
        sIsRecovering = true;
        e->CallVoidMethod(nat->manager,
                          android::gCachedNfcManagerNotifyHwErrorReported);
        {
          LOG(DEBUG) << StringPrintf(
              "%s: aborting  sNfaEnableDisablePollingEvent", __func__);
          SyncEventGuard guard(sNfaEnableDisablePollingEvent);
          sNfaEnableDisablePollingEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting  sNfaEnableEvent", __func__);
          SyncEventGuard guard(sNfaEnableEvent);
          sNfaEnableEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting  sNfaDisableEvent",
                                     __func__);
          SyncEventGuard guard(sNfaDisableEvent);
          sNfaDisableEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting  sNfaSetPowerSubState",
                                     __func__);
          SyncEventGuard guard(sNfaSetPowerSubState);
          sNfaSetPowerSubState.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting gNfaSetConfigEvent",
                                     __func__);
          SyncEventGuard guard(gNfaSetConfigEvent);
          gNfaSetConfigEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting gNfaGetConfigEvent",
                                     __func__);
          SyncEventGuard guard(gNfaGetConfigEvent);
          gNfaGetConfigEvent.notifyOne();
        }

      } else {
        nativeNfcTag_abortWaits();
        NfcTag::getInstance().abort();
        sAbortConnlessWait = true;
        {
          LOG(DEBUG) << StringPrintf(
              "%s: aborting  sNfaEnableDisablePollingEvent", __func__);
          SyncEventGuard guard(sNfaEnableDisablePollingEvent);
          sNfaEnableDisablePollingEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting  sNfaEnableEvent", __func__);
          SyncEventGuard guard(sNfaEnableEvent);
          sNfaEnableEvent.notifyOne();
        }
        {
          LOG(DEBUG) << StringPrintf("%s: aborting  sNfaDisableEvent",
                                     __func__);
          SyncEventGuard guard(sNfaDisableEvent);
          sNfaDisableEvent.notifyOne();
        }
        sDiscoveryEnabled = false;
        sPollingEnabled = false;
        PowerSwitch::getInstance().abort();

        if (!sIsDisabling && sIsNfaEnabled) {
          NFA_Disable(FALSE);
          sIsDisabling = true;
        } else {
          sIsNfaEnabled = false;
          sIsDisabling = false;
        }
        PowerSwitch::getInstance().initialize(PowerSwitch::UNKNOWN_LEVEL);
        LOG(ERROR) << StringPrintf("%s: crash NFC service", __func__);
        e->CallVoidMethod(nat->manager,
                          android::gCachedNfcManagerNotifyCommandTimeout);
        //////////////////////////////////////////////
        // crash the NFC service process so it can restart automatically
        abort();
        //////////////////////////////////////////////
      }
    } break;

    case NFA_DM_PWR_MODE_CHANGE_EVT:
      PowerSwitch::getInstance().deviceManagementCallback(dmEvent, eventData);
      break;
#if(NXP_EXTNS == TRUE)
      case NFA_DM_SET_TRANSIT_CONFIG_EVT: {
      LOG(DEBUG) << StringPrintf(
          "NFA_DM_SET_TRANSIT_CONFIG EVT cback received");
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
        LOG(DEBUG) << StringPrintf(
            "%s: NFA_DM_SET_POWER_SUB_STATE_EVT; status=0x%X", __FUNCTION__,
            eventData->power_sub_state.status);
        SyncEventGuard guard(sNfaSetPowerSubState);
        sNfaSetPowerSubState.notifyOne();
    } break;
#if(NXP_EXTNS == TRUE)
    case NFA_DM_GEN_ERROR_REVT: {
      struct nfc_jni_native_data* nat = getNative(NULL, NULL);
      JNIEnv* e = NULL;
      ScopedAttach attach(nat->vm, &e);
      if (e == NULL) {
        LOG(ERROR) << StringPrintf("jni env is null");
        return;
      }

      e->CallVoidMethod(nat->manager,
                        android::gCachedNfcManagerNotifyCoreGenericError,
                        eventData->status);
    } break;
#endif
    default:
      LOG(DEBUG) << StringPrintf("%s: unhandled event", __func__);
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

bool isSeRfActive() { return sSeRfActive; }

void setSeRfActive(bool seRfActive) { sSeRfActive = seRfActive; }

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
  ScopedByteArrayRO bytes(e, data);
  uint8_t* buf =
      const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
  size_t bufLen = bytes.size();
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
    if (sIsDisabling || !sIsNfaEnabled) {
      LOG(ERROR) << StringPrintf("%s: sIsNfaEnabled or sIsDisabling", __func__);
      return result;
    }

    result = RoutingManager::getInstance().setRoutingEntry(type, value, route, power);
    return result;
}

/*******************************************************************************
**
** Function:        nfcManager_setEmptyAidRoute
**
** Description:     Set the routing entry in routing table
**                  e: JVM environment.
**                  o: Java object.
**                  route: empty/default AID route.
**
*******************************************************************************/

static void nfcManager_setEmptyAidRoute (JNIEnv*, jobject, jint route)
{
    if (sIsDisabling || !sIsNfaEnabled) {
      LOG(ERROR) << StringPrintf("%s: sIsNfaEnabled or sIsDisabling", __func__);
      return;
    }
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
static jboolean nfcManager_routeAid(JNIEnv* e, jobject, jbyteArray aid,
                                    jint route, jint aidInfo, jint power) {
  uint8_t* buf;
  size_t bufLen;
  if (sIsDisabling || !sIsNfaEnabled) {
    return false;
  }
#if (NXP_EXTNS == TRUE)
  static int sT4tPowerState = 0;
  if (aid == NULL)
    RoutingManager::getInstance().checkAndUpdateAltRoute(route);
  SecureElement& se = SecureElement::getInstance();
  if ((!isDynamicUiccEnabled) &&
      (route == se.UICC_ID || route == se.UICC2_ID)) {  // UICC or UICC2 HANDLE
    LOG(DEBUG) << StringPrintf("sCurrentSelectedUICCSlot:  %d ::: route: %d",
                               sCurrentSelectedUICCSlot, route);
    /* If current slot is 0x01 and UICC_ID is 0x02 then route location should be
     * updated to UICC_ID(0x02) else if current slot is 0x02 and UICC_ID is 0x02
     * then route location should be updated to UICC_ID2(0x04).
     */
    route = (sCurrentSelectedUICCSlot != se.UICC_ID) ? se.UICC_ID : se.UICC2_ID;
  }
#endif
  if (aid == NULL) {
    buf = NULL;
    bufLen = 0;
    LOG(DEBUG) << StringPrintf("nfcManager_routeAid:  NULL");
    return RoutingManager::getInstance().addAidRouting(buf, bufLen, route,
                                                       aidInfo, power);
  }
  ScopedByteArrayRO bytes(e);
  bytes.reset(aid);
  buf = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
  bufLen = bytes.size();
#if (NXP_EXTNS == TRUE)
  if (route == SecureElement::getInstance().T4T_NFCEE_ID) {
    NativeT4tNfcee::getInstance().checkAndUpdateT4TAid(buf, (uint8_t*)&bufLen);
    if (sT4tPowerState != power) {
      sT4tPowerState = power;
      RoutingManager::getInstance().removeAidRouting(buf, bufLen);
    }
  }
#endif
  return RoutingManager::getInstance().addAidRouting(buf, bufLen, route,
                                                     aidInfo, power);
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
  if (sIsDisabling || !sIsNfaEnabled) {
    return false;
  }
  uint8_t* buf;
  size_t bufLen;

  if (aid == NULL) {
    buf = NULL;
    bufLen = 0;
    return RoutingManager::getInstance().removeAidRouting(buf, bufLen);
  }
  ScopedByteArrayRO bytes(e);
  bytes.reset(aid);
  buf = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
  bufLen = bytes.size();
  return RoutingManager::getInstance().removeAidRouting(buf, bufLen);
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

  waitIfRfStateActive();

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

void static nfaVSCallback(uint8_t event, uint16_t param_len, uint8_t* p_param) {
  switch (event & NCI_OID_MASK) {
    case NCI_MSG_PROP_ANDROID: {
      uint8_t android_sub_opcode = p_param[3];
      switch (android_sub_opcode) {
        case NCI_ANDROID_PASSIVE_OBSERVE: {
          gVSCmdStatus = p_param[4];
          LOG(INFO) << StringPrintf("Observe mode RSP: status: %x",
                                    gVSCmdStatus);
          SyncEventGuard guard(gNfaVsCommand);
          gNfaVsCommand.notifyOne();
        } break;
        case NCI_ANDROID_GET_CAPS: {
          gVSCmdStatus = p_param[4];
          u_int16_t android_version = *(u_int16_t*)&p_param[5];
          u_int8_t len = p_param[7];
          gCaps.assign(p_param + 8, p_param + 8 + len);
        } break;
        case NCI_ANDROID_POLLING_FRAME_NTF: {
          struct nfc_jni_native_data* nat = getNative(NULL, NULL);
          if (!nat) {
            LOG(ERROR) << StringPrintf("cached nat is null");
            return;
          }
          JNIEnv* e = NULL;
          ScopedAttach attach(nat->vm, &e);
          if (e == NULL) {
            LOG(ERROR) << StringPrintf("jni env is null");
            return;
          }
          ScopedLocalRef<jobject> dataJavaArray(e, e->NewByteArray(param_len));
          if (dataJavaArray.get() == NULL) {
            LOG(ERROR) << "fail allocate array";
            return;
          }
          e->SetByteArrayRegion((jbyteArray)dataJavaArray.get(), 0, param_len,
                                (jbyte*)(p_param));
          if (e->ExceptionCheck()) {
            e->ExceptionClear();
            LOG(ERROR) << "failed to fill array";
            return;
          }
          e->CallVoidMethod(nat->manager,
                            android::gCachedNfcManagerNotifyPollingLoopFrame,
                            (jint)param_len, dataJavaArray.get());

        } break;
        default:
          LOG(DEBUG) << StringPrintf("Unknown Android sub opcode %x",
                                     android_sub_opcode);
      }
    } break;
    default: {
      struct nfc_jni_native_data* nat = getNative(NULL, NULL);
      if (!nat) {
        LOG(ERROR) << StringPrintf("%s: cached nat is null", __FUNCTION__);
        return;
      }
      JNIEnv* e = NULL;
      ScopedAttach attach(nat->vm, &e);
      if (e == NULL) {
        LOG(ERROR) << StringPrintf("%s: jni env is null", __FUNCTION__);
      return;
      }
      ScopedLocalRef<jobject> dataJavaArray(e, e->NewByteArray(param_len));
      if (dataJavaArray.get() == NULL) {
        LOG(ERROR) << StringPrintf("%s: fail allocate array", __FUNCTION__);
        return;
      }
      e->SetByteArrayRegion((jbyteArray)dataJavaArray.get(), 0, param_len, (jbyte*)(p_param));
      if (e->ExceptionCheck()) {
        e->ExceptionClear();
        LOG(ERROR) << StringPrintf("%s failed to fill array", __FUNCTION__);
        return;
      }
      e->CallVoidMethod(nat->manager, android::gCachedNfcManagerNotifyVendorSpecificEvent,
                        (jint)event, (jint)param_len, dataJavaArray.get());
    } break;
  }
}

static jboolean isObserveModeSupported(JNIEnv* e, jobject o) {
  ScopedLocalRef<jclass> cls(e, e->GetObjectClass(o));
  jmethodID isSupported =
      e->GetMethodID(cls.get(), "isObserveModeSupported", "()Z");
  return e->CallBooleanMethod(o, isSupported);
}

static jboolean nfcManager_isObserveModeEnabled(JNIEnv* e, jobject o) {
  if (isObserveModeSupported(e, o) == JNI_FALSE) {
    return false;
  }
  LOG(DEBUG) << StringPrintf(
      "%s: returning %s", __FUNCTION__,
      (gObserveModeEnabled != JNI_FALSE ? "TRUE" : "FALSE"));
  return gObserveModeEnabled;
}

static void nfaSendRawVsCmdCallback(uint8_t event, uint16_t param_len,
                                    uint8_t* p_param) {
  if (param_len == 5) {
    gVSCmdStatus = p_param[4];
  } else {
    gVSCmdStatus = NFA_STATUS_FAILED;
  }
  SyncEventGuard guard(gNfaVsCommand);
  gNfaVsCommand.notifyOne();
}

static jboolean nfcManager_setObserveMode(JNIEnv* e, jobject o,
                                          jboolean enable) {
  if (isObserveModeSupported(e, o) == JNI_FALSE) {
    return false;
  }

  if ((enable != JNI_FALSE) ==
      (nfcManager_isObserveModeEnabled(e, o) != JNI_FALSE)) {
    LOG(DEBUG) << StringPrintf(
        "%s: called with %s but it is already %s, returning early",
        __FUNCTION__, (enable != JNI_FALSE ? "TRUE" : "FALSE"),
        (gObserveModeEnabled != JNI_FALSE ? "TRUE" : "FALSE"));
    return true;
  }
  bool reenbleDiscovery = false;
  if (sRfEnabled) {
    startRfDiscovery(false);
    reenbleDiscovery = true;
  }
  uint8_t cmd[] = {
      (NCI_MT_CMD << NCI_MT_SHIFT) | NCI_GID_PROP, NCI_MSG_PROP_ANDROID,
      NCI_ANDROID_PASSIVE_OBSERVE_PARAM_SIZE, NCI_ANDROID_PASSIVE_OBSERVE,
      static_cast<uint8_t>(enable != JNI_FALSE
                               ? NCI_ANDROID_PASSIVE_OBSERVE_PARAM_ENABLE
                               : NCI_ANDROID_PASSIVE_OBSERVE_PARAM_DISABLE)};
  tNFA_STATUS status = NFA_SendRawVsCommand(sizeof(cmd), cmd, nfaVSCallback);

  SyncEventGuard guard(gNfaVsCommand);
  if (status == NFA_STATUS_OK) {
    if (!gNfaVsCommand.wait(1000)) {
      LOG(ERROR) << StringPrintf(
          "%s: Timed out waiting for a response to set observe mode ",
          __FUNCTION__);
      gVSCmdStatus = NFA_STATUS_FAILED;
    }
  } else {
    LOG(DEBUG) << StringPrintf("%s: Failed to set observe mode ", __FUNCTION__);
    gVSCmdStatus = NFA_STATUS_FAILED;
  }
  if (reenbleDiscovery) {
    startRfDiscovery(true);
  }

  if (gVSCmdStatus == NFA_STATUS_OK) {
    gObserveModeEnabled = enable;
  } else {
    gObserveModeEnabled = nfcManager_isObserveModeEnabled(e, o);
  }

  LOG(DEBUG) << StringPrintf(
      "%s: Set observe mode to %s with result %x, observe mode is now %s.",
      __FUNCTION__, (enable != JNI_FALSE ? "TRUE" : "FALSE"), gVSCmdStatus,
      (gObserveModeEnabled ? "enabled" : "disabled"));
  return gObserveModeEnabled == enable;
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
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);

  ScopedByteArrayRO bytes(e, t3tIdentifier);
  uint8_t* buf =
      const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
  size_t bufLen = bytes.size();
  int handle = RoutingManager::getInstance().registerT3tIdentifier(buf, bufLen);

  LOG(DEBUG) << StringPrintf("%s: handle=%d", __func__, handle);
  if (handle != NFA_HANDLE_INVALID)
    RoutingManager::getInstance().commitRouting();
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);

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
  LOG(DEBUG) << StringPrintf("%s: enter; handle=%d", __func__, handle);

  RoutingManager::getInstance().deregisterT3tIdentifier(handle);
  RoutingManager::getInstance().commitRouting();

  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
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
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  LOG(DEBUG) << StringPrintf("LF_T3T_MAX=%d", sLfT3tMax);
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);

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
  sIsRecovering = false;

  PowerSwitch& powerSwitch = PowerSwitch::getInstance();

  if (sIsNfaEnabled) {
    LOG(DEBUG) << StringPrintf("%s: already enabled", __func__);
    goto TheEnd;
  }
#if (NXP_EXTNS == TRUE)
    mwVer=  NFA_GetMwVersion();
    LOG(ERROR) << StringPrintf(
        "%s:  MW Version: NFC_AR_%02X_%05X_%02d.%02x.%02x", __func__,
        mwVer.cust_id, mwVer.validation, mwVer.android_version,
        mwVer.major_version, mwVer.minor_version);

    if (NfcConfig::hasKey(NAME_NXP_DUAL_UICC_ENABLE)) {
      isDynamicUiccEnabled = NfcConfig::getUnsigned(NAME_NXP_DUAL_UICC_ENABLE);
      isDynamicUiccEnabled = (isDynamicUiccEnabled == 0x01 ? true : false);
    } else
      isDynamicUiccEnabled = true;
    if (NfcConfig::hasKey(NAME_NXP_DISCONNECT_TAG_IN_SCRN_OFF)) {
      isDisconnectNeeded = NfcConfig::getUnsigned(NAME_NXP_DISCONNECT_TAG_IN_SCRN_OFF);
      isDisconnectNeeded = (isDisconnectNeeded == 0x01 ? true : false);
    } else
      isDisconnectNeeded = false;
    if (NfcConfig::hasKey(NAME_NXP_CE_PRIORITY_ENABLED)) {
      isCePriorityEnabled =
          (NfcConfig::getUnsigned(NAME_NXP_CE_PRIORITY_ENABLED) == 0x01
               ? true
               : false);
    } else
      isCePriorityEnabled = false;

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
      NativeJniExtns::getInstance().notifyNfcEvent("nfcManager_setPropertyInfo");
      stat = theInstance.DownloadFirmware(nfcFwUpdateStatusCallback, true);
#endif
      stat = NFA_Enable(nfaDeviceManagementCallback, nfaConnectionCallback);
      if (stat == NFA_STATUS_OK) {
        sNfaEnableEvent.wait();  // wait for NFA command to finish
      }
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
        HciEventManager::getInstance().initialize(getNative(e, o));
#if(NXP_EXTNS == TRUE)
        MposManager::getInstance().initialize(getNative(e, o));
        NativeT4tNfcee::getInstance().initialize();
        NativeExtFieldDetect::getInstance().initialize(getNative(e, o));
#if (NXP_SRD == TRUE)
        SecureDigitization::getInstance().initialize(getNative(e, o));
#endif
        if(NFA_STATUS_OK != NFA_RegVSCback (true,nfaVSCNtfCallback)) { //Register CallBack for Lx Debug notifications
          LOG(ERROR) << StringPrintf("%s:  nfaVSCNtfCallback resgister failed..!", __func__);
        }
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
          LOG(DEBUG) << StringPrintf("%s: tag polling tech mask=0x%X",
                                     __func__, nat->tech_mask);
          // if this value exists, set polling interval.
          nat->discovery_duration = NfcConfig::getUnsigned(
              NAME_NFA_DM_DISC_DURATION_POLL, DEFAULT_DISCOVERY_DURATION);
          NFA_SetRfDiscoveryDuration(nat->discovery_duration);
        } else {
          LOG(ERROR) << StringPrintf("nat is null");
        }

        // get LF_T3T_MAX
        {
          SyncEventGuard guard(gNfaGetConfigEvent);
          tNFA_PMID configParam[1] = {NCI_PARAM_ID_LF_T3T_MAX};
          stat = NFA_GetConfig(1, configParam);
          if (stat == NFA_STATUS_OK) {
            gNfaGetConfigEvent.wait();
            if (gCurrentConfigLen >= 4 ||
                gConfig[1] == NCI_PARAM_ID_LF_T3T_MAX) {
              LOG(DEBUG) << StringPrintf("%s: lfT3tMax=%d", __func__,
                                         gConfig[3]);
              sLfT3tMax = gConfig[3];
            }
          }
        }

#if (NXP_EXTNS==TRUE)
        if (NfcConfig::hasKey(NAME_NXP_ENABLE_DISABLE_LOGS))
          suppressLogs =
              NfcConfig::getUnsigned(NAME_NXP_ENABLE_DISABLE_LOGS, 1);
        prevScreenState = NFA_SCREEN_STATE_UNKNOWN;
#else
        prevScreenState = NFA_SCREEN_STATE_OFF_LOCKED;
#endif

        // Do custom NFCA startup configuration.
        doStartupConfig();
#ifdef DTA_ENABLED
        NfcDta::getInstance().setNfccConfigParams();
#endif /* DTA_ENABLED */
        goto TheEnd;
      }
    }

    LOG(ERROR) << StringPrintf("%s: fail nfa enable; error=0x%X", __func__,
                               stat);

    if (sIsNfaEnabled) {
      stat = NFA_Disable(FALSE /* ungraceful */);
    }
    theInstance.Finalize();
  }

TheEnd:
  if (sIsNfaEnabled) {
    PowerSwitch::getInstance().setLevel(PowerSwitch::LOW_POWER);
    if (android_nfc_nfc_read_polling_loop() || android_nfc_nfc_vendor_cmd()) {
      NFA_RegVSCback(true, &nfaVSCallback);
    }
#if (NXP_EXTNS == TRUE)
  NxpNfc_GetHwInfo();
#endif
  }
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
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
  NativeT4tNfcee::getInstance().onNfccShutdown();
  sIsDisabling = false;
  sIsNfaEnabled = false;
#endif
  theInstance.DeviceShutdown();
}

static void nfcManager_configNfccConfigControl(bool flag) {
  // configure NFCC_CONFIG_CONTROL- NFCC allowed to manage RF configuration.
  if (NFC_GetNCIVersion() != NCI_VERSION_1_0) {
    uint8_t nfa_set_config[] = { 0x00 };
    nfa_set_config[0] = (flag == true ? 1 : 0);

    tNFA_STATUS status = NFA_SetConfig(NCI_PARAM_ID_NFCC_CONFIG_CONTROL, sizeof(nfa_set_config),
            &nfa_set_config[0]);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << __func__  << ": Failed to configure NFCC_CONFIG_CONTROL";
    }
  }
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
                                       jboolean restart) {
  tNFA_TECHNOLOGY_MASK tech_mask = DEFAULT_TECH_MASK;
  struct nfc_jni_native_data* nat = getNative(e, o);
#if(NXP_EXTNS == TRUE)
  // If Nfc is disabling or disabled shall return
  if (sIsDisabling || !sIsNfaEnabled)
    return;

  waitIfRfStateActive();
  storeLastDiscoveryParams(technologies_mask, enable_lptd,
        reader_mode, enable_host_routing, restart);
#endif
  if (technologies_mask == -1 && nat)
    tech_mask = (tNFA_TECHNOLOGY_MASK)nat->tech_mask;
  else if (technologies_mask != -1)
    tech_mask = (tNFA_TECHNOLOGY_MASK)technologies_mask;

#if (NXP_EXTNS == TRUE)
#if (NXP_QTAG == TRUE)
  uint16_t default_tech_mask =
      NfcConfig::getUnsigned(NAME_POLLING_TECH_MASK, DEFAULT_TECH_MASK);
  default_tech_mask |= NFA_TECHNOLOGY_MASK_Q;
#else
  uint8_t default_tech_mask =
      NfcConfig::getUnsigned(NAME_POLLING_TECH_MASK, DEFAULT_TECH_MASK);
#endif
  tech_mask &= default_tech_mask;
#endif

  LOG(DEBUG) << StringPrintf("%s: enter; tech_mask = %02x", __func__,
                             tech_mask);

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

    if (sPollingEnabled) {
      if (reader_mode && !sReaderModeEnabled) {
        sReaderModeEnabled = true;
        NFA_DisableListening();
#if (NXP_EXTNS == FALSE)
        // configure NFCC_CONFIG_CONTROL- NFCC not allowed to manage RF configuration.
        nfcManager_configNfccConfigControl(false);
#endif
        NFA_SetRfDiscoveryDuration(READER_MODE_DISCOVERY_DURATION);
      } else if (!reader_mode && sReaderModeEnabled) {
        struct nfc_jni_native_data* nat = getNative(e, o);
        sReaderModeEnabled = false;
        NFA_EnableListening();

        // configure NFCC_CONFIG_CONTROL- NFCC allowed to manage RF configuration.
        nfcManager_configNfccConfigControl(true);

        if (nat) {
          NFA_SetRfDiscoveryDuration(nat->discovery_duration);
        } else {
          LOG(ERROR) << StringPrintf("nat is null");
        }
      }
    }
  } else {
    if (!reader_mode && sReaderModeEnabled) {
      LOG(DEBUG) << StringPrintf(
          "%s: if reader mode disable, enable listen again", __func__);
      struct nfc_jni_native_data* nat = getNative(e, o);
      sReaderModeEnabled = false;
      NFA_EnableListening();

      // configure NFCC_CONFIG_CONTROL- NFCC allowed to manage RF configuration.
      nfcManager_configNfccConfigControl(true);

      if (nat) {
        NFA_SetRfDiscoveryDuration(nat->discovery_duration);
      } else {
        LOG(ERROR) << StringPrintf("nat is null");
      }
    }
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

  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
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
  LOG(DEBUG) << StringPrintf("%s: enter;", __func__);

#if(NXP_EXTNS == TRUE)
  // If Nfc is disabling or disabled shall return
  if (sIsDisabling || !sIsNfaEnabled)
    return;
#endif

  if (sDiscoveryEnabled == false) {
    LOG(DEBUG) << StringPrintf("%s: already disabled", __func__);
    goto TheEnd;
  }

  // Stop RF Discovery.
  startRfDiscovery(false);
  sDiscoveryEnabled = false;
  if (sPollingEnabled) status = stopPolling_rfDiscoveryDisabled();

  // if nothing is active after this, then tell the controller to power down
  if (!PowerSwitch::getInstance().setModeOff(PowerSwitch::DISCOVERY))
    PowerSwitch::getInstance().setLevel(PowerSwitch::LOW_POWER);
TheEnd:
  LOG(DEBUG) << StringPrintf("%s: exit: Status = 0x%X", __func__, status);
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
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  sIsDisabling = true;

#if (NXP_EXTNS == TRUE)
  NativeExtFieldDetect::getInstance().deinitialize();
  NativeJniExtns::getInstance().notifyNfcEvent(__func__);
  if (SecureElement::getInstance().mIsSeIntfActivated) {
    nfcManager_dodeactivateSeInterface(NULL, NULL);
  }

  if(NFA_STATUS_OK != NFA_RegVSCback (false,nfaVSCNtfCallback)) { //De-Register Lx Debug CallBack
    LOG(ERROR) << StringPrintf("%s:  nfaVSCNtfCallback Deresgister failed..!", __func__);
  }
  NativeT4tNfcee::getInstance().onNfccShutdown();
#endif
  if (!recovery_option || !sIsRecovering) {
    RoutingManager::getInstance().onNfccShutdown();
  }
  PowerSwitch::getInstance().initialize(PowerSwitch::UNKNOWN_LEVEL);
  HciEventManager::getInstance().finalize();
  if (sIsNfaEnabled) {
    SyncEventGuard guard(sNfaDisableEvent);
    tNFA_STATUS stat = NFA_Disable(TRUE /* graceful */);
    if (stat == NFA_STATUS_OK) {
      LOG(DEBUG) << StringPrintf("%s: wait for completion", __func__);
      sNfaDisableEvent.wait();  // wait for NFA command to finish
    } else {
      LOG(ERROR) << StringPrintf("%s: fail disable; error=0x%X", __func__,
                                 stat);
    }
  }
  nativeNfcTag_abortWaits();
  NfcTag::getInstance().abort();
  sAbortConnlessWait = true;
  sIsNfaEnabled = false;
  sRoutingInitialized = false;
  sDiscoveryEnabled = false;
  sPollingEnabled = false;
  sIsDisabling = false;
  sReaderModeEnabled = false;
  gActivated = false;
  sLfT3tMax = 0;

  {
    // unblock NFA_EnablePolling() and NFA_DisablePolling()
    SyncEventGuard guard(sNfaEnableDisablePollingEvent);
    sNfaEnableDisablePollingEvent.notifyOne();
  }
#if (NXP_EXTNS == TRUE)
  sSeRfActive = false;
  /*Disable Field Detect Mode if enabled*/
  if (NFA_IsFieldDetectEnabled()) NFA_SetFieldDetectMode(false);
  RoutingManager::getInstance().notifyAllEvents();
  SecureElement::getInstance().finalize ();
  MposManager::getInstance().finalize();
#endif
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  theInstance.Finalize();

  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
  return JNI_TRUE;
}

#if (NXP_EXTNS == TRUE)
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
  int num = 0;
  if (NfcConfig::hasKey(NAME_DEFAULT_AID_ROUTE))
    num = (int)NfcConfig::getUnsigned(NAME_DEFAULT_AID_ROUTE);
  else if (NfcConfig::hasKey(NAME_DEFAULT_ROUTE))
    num = (int)NfcConfig::getUnsigned(NAME_DEFAULT_ROUTE);
  else
    return NFA_HANDLE_INVALID;

  LOG(DEBUG) << StringPrintf("%s: num %x", __func__, num);

  RoutingManager::getInstance().checkAndUpdateAltRoute(num);
  LOG(DEBUG) << StringPrintf("%s route = %x", __func__, num);
  return num;
}
#endif

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
** Function:        nfcManager_getT4TNfceePowerState
**
** Description:     Get the T4T Nfcee power state supported.
**                  e: JVM environment.
**                  o: Java object.
**                  mode: Not used.
**
** Returns:         None
**
*******************************************************************************/
static jint nfcManager_getT4TNfceePowerState(JNIEnv* e, jobject o) {
  RoutingManager& routingManager = RoutingManager::getInstance();
  int defaultPowerState = ~(routingManager.PWR_SWTCH_OFF_MASK |
          routingManager.PWR_BATT_OFF_MASK);

  return NfcConfig::getUnsigned(NAME_DEFAULT_T4TNFCEE_AID_POWER_STATE,
          defaultPowerState);
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
  SyncEventGuard guard(gNfaGetConfigEvent);
  status = NFA_GetConfig(numParam, param);
  if (status == NFA_STATUS_OK) {
    if (gNfaGetConfigEvent.wait(WIRED_MODE_TRANSCEIVE_TIMEOUT) == false) {
      *rspLen = 0;
    } else {
      *rspLen = gCurrentConfigLen;
      memcpy(configValue, gConfig, gCurrentConfigLen);
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
    LOG(DEBUG) << StringPrintf("%s: Enter", __func__);

    if (!sIsNfaEnabled) {
      LOG(DEBUG) << StringPrintf("%s: Nfc is not Enabled. Returning", __func__);
      return FDSTATUS_ERROR_NFC_IS_OFF;
    }

    if (MposManager::getInstance().isMposOngoing()) {
      LOG(DEBUG) << StringPrintf("%s: MPOS is ongoing.. Returning", __func__);
      return FDSTATUS_ERROR_NFC_BUSY_IN_MPOS;
    }

    if (NFA_IsFieldDetectEnabled() == mode) {
      LOG(DEBUG) << StringPrintf("%s: Already %s", __func__,
                                 ((mode) ? "ENABLED" : "DISABLED"));
      return FDSTATUS_SUCCESS;
    }

    if (sRfEnabled) {
      // Stop RF Discovery
      LOG(DEBUG) << StringPrintf("%s: stop discovery", __func__);
      startRfDiscovery(false);
    }
    NFA_SetFieldDetectMode(mode);
    // start discovery
    LOG(DEBUG) << StringPrintf("%s: reconfigured start discovery", __func__);
    startRfDiscovery(true);
    return FDSTATUS_SUCCESS;
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
    LOG(DEBUG) << StringPrintf("%s: Enter", __func__);
    return NFA_IsFieldDetectEnabled();
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_StartRssiMode
  **
  ** Description:     Updates RSSI mode ENABLE/DISABLE
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         Update status
  **
  *******************************************************************************/
  static rssi_status_t nfcManager_StartRssiMode(JNIEnv*, jobject,
                                                jint rssiNtfTimeIntervalInMillisec) {
    return nfcManager_doSetRssiMode(true, rssiNtfTimeIntervalInMillisec);
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_StopRssiMode
  **
  ** Description:     Updates RSSI mode ENABLE/DISABLE
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         Update status
  **
  *******************************************************************************/
  static rssi_status_t nfcManager_StopRssiMode(JNIEnv*, jobject) {
    return nfcManager_doSetRssiMode(false, 0);
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_IsRssiEnabled
  **
  ** Description:     Returns current status of RSSI mode
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         true/false
  **
  *******************************************************************************/
  static jboolean nfcManager_IsRssiEnabled(JNIEnv*, jobject) {
    LOG(DEBUG) << StringPrintf("%s: Enter", __func__);
    return NFA_IsRssiEnabled();
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

#if (NXP_EXTNS == TRUE)
  /*******************************************************************************
  **
  ** Function:        nfcManager_isRemovalDetectionSupported
  **
  ** Description:     Check if the Removal Detection in Poll mode is supported.
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         True if supports 'Removal Detection Mode'
  **
  *******************************************************************************/
  static jboolean nfcManager_isRemovalDetectionSupported(JNIEnv* e, jobject o) {
    return NFA_IsRfRemovalDetectionSupported();
  }
  /*******************************************************************************
  **
  ** Function:        nfcManager_startRemovalDetectionProcedure
  **
  ** Description:     Request NFCC to start the Removal Detection Procedure.
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManager_startRemovalDetectionProcedure(JNIEnv* e, jobject o,
                                                        jint waitTimeout) {
  if (NfcTag::getInstance().isActivated()) {
    if (NFA_SendRemovalDetectionCmd(waitTimeout) != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: failed", __func__);
    }
  } else {
    LOG(ERROR) << StringPrintf("%s: Tag is deactivated", __func__);
  }
  return;
}
#endif

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
** Function:        isListenMode
**
** Description:     Indicates whether the activation data indicates it is
**                  listen mode.
**
** Returns:         True if this listen mode.
**
*******************************************************************************/
static bool isListenMode(tNFA_ACTIVATED& activated) {
  return (
      (NFC_DISCOVERY_TYPE_LISTEN_A ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_B ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_F ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_ISO15693 ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_DISCOVERY_TYPE_LISTEN_B_PRIME ==
       activated.activate_ntf.rf_tech_param.mode) ||
      (NFC_INTERFACE_EE_DIRECT_RF == activated.activate_ntf.intf_param.type));
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
  LOG(DEBUG) << StringPrintf("startResonantFrequency : mode[%s]",
                             modeOn == true ? "ON" : "OFF");

  if (!sIsNfaEnabled) {
    LOG(DEBUG) << StringPrintf("startResonantFrequency :NFC is not enabled!!");
    return;
  } else if (modeOn && gselfTestData.isStored) {
    LOG(DEBUG) << StringPrintf("startResonantFrequency: Already ON!!");
    return;
  } else if (!modeOn && !gselfTestData.isStored) {
    LOG(DEBUG) << StringPrintf("startResonantFrequency: already OFF!!");
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

  if (modeOn)        /* TEST_TYPE_SET_RFTXCFG_RESONANT_FREQ */
    pollTech = 0x00; /* Activate only Card Emulation Mode */

  { /* Change the discovery tech mask as per the test */
    SyncEventGuard guard(sNfaEnableDisablePollingEvent);
    status = NFA_ChangeDiscoveryTech(pollTech, uiccListenTech, false, false);
    if (NFA_STATUS_OK == status) {
      LOG(DEBUG) << StringPrintf(
          "%s: waiting for nfcManager_changeDiscoveryTech", __func__);
      sNfaEnableDisablePollingEvent.wait();
    } else {
      LOG(DEBUG) << StringPrintf("%s: nfcManager_changeDiscoveryTech failed",
                                 __func__);
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
int nfcManager_doPartialInitialize(JNIEnv* e, jobject o, jint mode) {
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  tNFA_STATUS stat = NFA_STATUS_OK;
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();

  theInstance.Initialize();
  tHAL_NFC_ENTRY* halFuncEntries = theInstance.GetHalEntryFuncs();

  if (NULL == halFuncEntries) {
    theInstance.Finalize();
    gsNfaPartialEnabled = false;
    return NFA_STATUS_FAILED;
  }

  theInstance.NFA_SetBootMode(mode);

  NFA_Init(halFuncEntries);
  LOG(DEBUG) << StringPrintf("%s: calling enable", __func__);
  NativeJniExtns::getInstance().notifyNfcEvent("nfcManager_setPropertyInfo");
  stat = NFA_Enable(nfaDeviceManagementCallback, nfaConnectionCallback);
  if (stat == NFA_STATUS_OK) {
    SyncEventGuard guard(sNfaEnableEvent);
    sNfaEnableEvent.wait();  // wait for NFA command to finish
  }

  if (sIsNfaEnabled) {
    gsNfaPartialEnabled = true;
  } else {
    NFA_Disable(false /* ungraceful */);
    theInstance.Finalize();
    gsNfaPartialEnabled = false;
  }
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
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
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  tNFA_STATUS stat = NFA_STATUS_OK;
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();

  if (!gsNfaPartialEnabled) {
    LOG(DEBUG) << StringPrintf(
        "%s: cannot deinitialize NFC , not partially initilaized", __func__);
    return NFA_STATUS_FAILED;
    }
    LOG(DEBUG) << StringPrintf("%s:enter", __func__);
    stat = NFA_Disable (true /* graceful */);
    if (stat == NFA_STATUS_OK)
    {
    LOG(DEBUG) << StringPrintf("%s: wait for completion", __func__);
    SyncEventGuard guard(sNfaDisableEvent);
    sNfaDisableEvent.wait();  // wait for NFA command to finish
    }
    else
    {
    LOG(ERROR) << StringPrintf("%s: fail disable; error=0x%X", __func__, stat);
    }
    theInstance.NFA_SetBootMode(NFA_NORMAL_BOOT_MODE);
    theInstance.Finalize();
    gsNfaPartialEnabled = false;
    LOG(DEBUG) << StringPrintf("%s: exit", __func__);

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
    LOG(DEBUG) << StringPrintf("%s: enter", __func__);
    NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
    bool result = JNI_FALSE;
    theInstance.Initialize();  // start GKI, NCI task, NFC task
#if (NXP_EXTNS == TRUE)
  NativeJniExtns::getInstance().initializeNativeData(getNative(e, o));
  NativeJniExtns::getInstance().notifyNfcEvent("nfcManager_setPropertyInfo");
  result = theInstance.DownloadFirmware(nfcFwUpdateStatusCallback, false);
#else
  result = theInstance.DownloadFirmware();
#endif
  theInstance.Finalize();
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
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
  LOG(DEBUG) << StringPrintf("%s", __func__);
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
  LOG(DEBUG) << StringPrintf("%s: tech=%d, timeout=%d", __func__, tech,
                             timeout);
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
  LOG(DEBUG) << StringPrintf("%s: tech=%d, timeout=%d", __func__, tech,
                             timeout);
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
    LOG(INFO) << StringPrintf("Screen state is not changed.");
    return;
  }
  scrnOnLockedPollDisabled = false;

  if (gsNfaPartialEnabled == false) {
    NativeJniExtns::getInstance().notifyNfcEvent(__func__);
  } else {
    LOG(ERROR) << StringPrintf(
        "%s: PartialInit mode Screen state change not required", __FUNCTION__);
    return;
  }
#endif
  LOG(DEBUG) << StringPrintf(
      "%s: state = %d prevScreenState= %d, discovry_param = %d", __FUNCTION__,
      state, prevScreenState, discovry_param);

  if (sIsDisabling || !sIsNfaEnabled ||
      (NFC_GetNCIVersion() != NCI_VERSION_2_0))  {
    prevScreenState = state;
    return;
  }

  // skip remaining SetScreenState tasks when trying to silent recover NFCC
  if (recovery_option && sIsRecovering) {
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

  // skip remaining SetScreenState tasks when trying to silent recover NFCC
  if (recovery_option && sIsRecovering) {
    prevScreenState = state;
    return;
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
#if (NXP_EXTNS == TRUE)
    if (!(screen_state_mask & NFA_SCREEN_POLLING_TAG_MASK)) {
      scrnOnLockedPollDisabled = true;
    }
#endif
  }

  // skip remaining SetScreenState tasks when trying to silent recover NFCC
  if (recovery_option && sIsRecovering) {
    prevScreenState = state;
    return;
  }

  if (state == NFA_SCREEN_STATE_ON_UNLOCKED) {
    // enable both poll and listen on DH 0x01
    discovry_param =
        NCI_LISTEN_DH_NFCEE_ENABLE_MASK | NCI_POLLING_DH_ENABLE_MASK;
  }

  SyncEventGuard guard(gNfaSetConfigEvent);
  status = NFA_SetConfig(NCI_PARAM_ID_CON_DISCOVERY_PARAM,
                         NCI_PARAM_LEN_CON_DISCOVERY_PARAM, &discovry_param);
  if (status == NFA_STATUS_OK) {
    gNfaSetConfigEvent.wait();
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed to update CON_DISCOVER_PARAM",
                               __FUNCTION__);
    return;
  }

  // skip remaining SetScreenState tasks when trying to silent recover NFCC
  if (recovery_option && sIsRecovering) {
    prevScreenState = state;
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

  // skip remaining SetScreenState tasks when trying to silent recover NFCC
  if (recovery_option && sIsRecovering) {
    prevScreenState = state;
    return;
  }

  if ((state > NFA_SCREEN_STATE_UNKNOWN &&
       state <= NFA_SCREEN_STATE_ON_LOCKED) &&
      (prevScreenState == NFA_SCREEN_STATE_ON_UNLOCKED ||
       prevScreenState == NFA_SCREEN_STATE_ON_LOCKED) &&
      (!sSeRfActive)) {
    // screen turns off, disconnect tag if connected
#if (NXP_EXTNS == TRUE)
    if(isDisconnectNeeded && gActivated){
        nativeNfcTag_safeDisconnect();
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
/*******************************************************************************
**
** Function:        nfcManager_getAidTableSize
** Description:     Get the maximum supported size for AID routing table.
**
**                  e: JVM environment.
**                  o: Java object.
**
*******************************************************************************/
static jint nfcManager_getAidTableSize(JNIEnv*, jobject) {
  return NFA_GetAidTableSize();
}

/*******************************************************************************
**
** Function:        nfcManager_doStartStopPolling
**
** Description:     Start or stop NFC RF polling
**                  e: JVM environment.
**                  o: Java object.
**                  start: start or stop RF polling
**
** Returns:         None
**
*******************************************************************************/
static void nfcManager_doStartStopPolling(JNIEnv* e, jobject o,
                                          jboolean start) {
  startStopPolling(start);
}

static jboolean nfcManager_doSetNfcSecure(JNIEnv* e, jobject o,
                                          jboolean enable) {
  RoutingManager& routingManager = RoutingManager::getInstance();
  routingManager.setNfcSecure(enable);
  if (sRoutingInitialized) {
#if(NXP_EXTNS != TRUE)
      routingManager.disableRoutingToHost();
      routingManager.updateRoutingTable();
      routingManager.enableRoutingToHost();
#else
      routingManager.updateRoutingTable();
#endif
  }
  return true;
}

/*******************************************************************************
**
** Function:        nfcManager_doGetMaxRoutingTableSize
**
** Description:     Retrieve the max routing table size from cache
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Max Routing Table size
**
*******************************************************************************/
static jint nfcManager_doGetMaxRoutingTableSize(JNIEnv* e, jobject o) {
  return lmrt_get_max_size();
}

/*******************************************************************************
**
** Function:        nfcManager_doGetRoutingTable
**
** Description:     Retrieve the committed listen mode routing configuration
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Committed listen mode routing configuration
**
*******************************************************************************/
static jbyteArray nfcManager_doGetRoutingTable(JNIEnv* e, jobject o) {
  std::vector<uint8_t>* routingTable = lmrt_get_tlvs();

  CHECK(e);
  jbyteArray rtJavaArray = e->NewByteArray((*routingTable).size());
  CHECK(rtJavaArray);
  e->SetByteArrayRegion(rtJavaArray, 0, (*routingTable).size(),
                        (jbyte*)&(*routingTable)[0]);

  return rtJavaArray;
}

static void nfcManager_clearRoutingEntry(JNIEnv* e, jobject o,
                                         jint clearFlags) {
#if(NXP_EXTNS == TRUE)
  if (sIsDisabling || !sIsNfaEnabled) {
      LOG(ERROR) << StringPrintf("%s: sIsNfaEnabled or sIsDisabling", __func__);
      return;
  }
#endif
  LOG(DEBUG) << StringPrintf("%s: clearFlags=0x%X", __func__, clearFlags);
  RoutingManager::getInstance().disableRoutingToHost();
  RoutingManager::getInstance().clearRoutingEntry(clearFlags);
}

static void nfcManager_updateIsoDepProtocolRoute(JNIEnv* e, jobject o,
                                                 jint route) {
  LOG(DEBUG) << StringPrintf("%s: clearFlags=0x%X", __func__, route);
  RoutingManager::getInstance().updateIsoDepProtocolRoute(route);
}

static void nfcManager_updateTechnologyABRoute(JNIEnv* e, jobject o,
                                               jint route) {
  LOG(DEBUG) << StringPrintf("%s: clearFlags=0x%X", __func__, route);
  RoutingManager::getInstance().updateTechnologyABRoute(route);
}

/*******************************************************************************
**
** Function:        nfcManager_setDiscoveryTech
**
** Description:     Temporarily changes the RF parameter
**                  pollTech: RF tech parameters for poll mode
**                  listenTech: RF tech parameters for listen mode
**
** Returns:         None.
**
*******************************************************************************/
static void nfcManager_setDiscoveryTech(JNIEnv* e, jobject o, jint pollTech,
                                        jint listenTech) {
  tNFA_STATUS nfaStat;
  bool isRevertPoll = false;
  bool isRevertListen = false;
  LOG(DEBUG) << StringPrintf("%s  pollTech = 0x%x, listenTech = 0x%x", __func__,
                             pollTech, listenTech);

  if (pollTech < 0) isRevertPoll = true;
  if (listenTech < 0) isRevertListen = true;

  nativeNfcTag_acquireRfInterfaceMutexLock();
  SyncEventGuard guard(sNfaEnableDisablePollingEvent);

  nfaStat = NFA_ChangeDiscoveryTech(pollTech, listenTech, isRevertPoll,
                                    isRevertListen);

  if (nfaStat == NFA_STATUS_OK) {
    // wait for NFA_LISTEN_DISABLED_EVT
    sNfaEnableDisablePollingEvent.wait();
  } else {
    LOG(ERROR) << StringPrintf("%s: fail disable polling; error=0x%X", __func__,
                               nfaStat);
  }
  nativeNfcTag_releaseRfInterfaceMutexLock();
}

/*******************************************************************************
**
** Function:        nfcManager_resetDiscoveryTech
**
** Description:     Restores the RF tech to the state before
**                  nfcManager_setDiscoveryTech was called
**
** Returns:         None.
**
*******************************************************************************/
static void nfcManager_resetDiscoveryTech(JNIEnv* e, jobject o) {
  tNFA_STATUS nfaStat;
  LOG(DEBUG) << StringPrintf("%s : enter", __func__);

  nativeNfcTag_acquireRfInterfaceMutexLock();
  SyncEventGuard guard(sNfaEnableDisablePollingEvent);

  nfaStat = NFA_ChangeDiscoveryTech(0xFF, 0xFF, true, true);

  if (nfaStat == NFA_STATUS_OK) {
    // wait for NFA_LISTEN_DISABLED_EVT
    sNfaEnableDisablePollingEvent.wait();
  } else {
    LOG(ERROR) << StringPrintf("%s: fail disable polling; error=0x%X", __func__,
                               nfaStat);
  }
  nativeNfcTag_releaseRfInterfaceMutexLock();
}
static jobject nfcManager_nativeSendRawVendorCmd(JNIEnv* env, jobject o,
                                                 jint mt, jint gid, jint oid,
                                                 jbyteArray payload) {
  LOG(DEBUG) << StringPrintf("%s : enter", __func__);
  ScopedByteArrayRO payloaBytes(env, payload);
  ScopedLocalRef<jclass> cls(env,
                             env->FindClass(gNfcVendorNciResponseClassName));
  jmethodID responseConstructor =
      env->GetMethodID(cls.get(), "<init>", "(BII[B)V");

  jbyte mStatus = NFA_STATUS_FAILED;
  jint resGid = 0;
  jint resOid = 0;
  jbyteArray resPayload = nullptr;

  sRawVendorCmdResponse.clear();

  std::vector<uint8_t> command;
  command.push_back((uint8_t)((mt << NCI_MT_SHIFT) | gid));
  command.push_back((uint8_t)oid);
  if (payloaBytes.size() > 0) {
    command.push_back((uint8_t)payloaBytes.size());
    command.insert(command.end(), &payloaBytes[0],
                   &payloaBytes[payloaBytes.size()]);
  } else {
    return env->NewObject(cls.get(), responseConstructor, mStatus, resGid,
                          resOid, resPayload);
  }

  SyncEventGuard guard(gSendRawVsCmdEvent);
  mStatus = NFA_SendRawVsCommand(command.size(), command.data(),
                                 sendRawVsCmdCallback);
  if (mStatus == NFA_STATUS_OK) {
    if (gSendRawVsCmdEvent.wait(2000) == false) {
      mStatus = NFA_STATUS_FAILED;
      LOG(ERROR) << StringPrintf("%s: timeout ", __func__);
    }

    if (mStatus == NFA_STATUS_OK && sRawVendorCmdResponse.size() > 2) {
      resGid = sRawVendorCmdResponse[0] & NCI_GID_MASK;
      resOid = sRawVendorCmdResponse[1];
      const jsize len = static_cast<jsize>(sRawVendorCmdResponse[2]);
      if (sRawVendorCmdResponse.size() >= (sRawVendorCmdResponse[2] + 3)) {
        resPayload = env->NewByteArray(len);
        std::vector<uint8_t> payloadVec(sRawVendorCmdResponse.begin() + 3,
                                        sRawVendorCmdResponse.end());
        env->SetByteArrayRegion(
            resPayload, 0, len,
            reinterpret_cast<const jbyte*>(payloadVec.data()));
      } else {
        mStatus = NFA_STATUS_FAILED;
        LOG(ERROR) << StringPrintf("%s: invalid payload data", __func__);
      }
    } else {
      mStatus = NFA_STATUS_FAILED;
    }
  }

  LOG(DEBUG) << StringPrintf("%s : exit", __func__);
  return env->NewObject(cls.get(), responseConstructor, mStatus, resGid, resOid,
                        resPayload);
}

static void sendRawVsCmdCallback(uint8_t event, uint16_t param_len,
                                 uint8_t* p_param) {
  sRawVendorCmdResponse = std::vector<uint8_t>(p_param, p_param + param_len);

  SyncEventGuard guard(gSendRawVsCmdEvent);
  gSendRawVsCmdEvent.notifyOne();
} /* namespace android */

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

    {"doSetRoutingEntry", "(IIII)Z", (void*)nfcManager_setRoutingEntry},

    {"commitRouting", "()Z", (void*)nfcManager_commitRouting},

    {"setEmptyAidRoute", "(I)V", (void*)nfcManager_setEmptyAidRoute},

    {"doRegisterT3tIdentifier", "([B)I",
     (void*)nfcManager_doRegisterT3tIdentifier},

    {"doDeregisterT3tIdentifier", "(I)V",
     (void*)nfcManager_doDeregisterT3tIdentifier},

    {"getLfT3tMax", "()I", (void*)nfcManager_getLfT3tMax},

    {"doEnableDiscovery", "(IZZZZ)V", (void*)nfcManager_enableDiscovery},

    {"doStartStopPolling", "(Z)V", (void*)nfcManager_doStartStopPolling},

    {"disableDiscovery", "()V", (void*)nfcManager_disableDiscovery},

    {"doSetTimeout", "(II)Z", (void*)nfcManager_doSetTimeout},

    {"doGetTimeout", "(I)I", (void*)nfcManager_doGetTimeout},

    {"doResetTimeouts", "()V", (void*)nfcManager_doResetTimeouts},

    {"doAbort", "(Ljava/lang/String;)V", (void*)nfcManager_doAbort},

    {"doSetScreenState", "(I)V", (void*)nfcManager_doSetScreenState},

    {"doDump", "(Ljava/io/FileDescriptor;)V", (void*)nfcManager_doDump},

    {"getNciVersion", "()I", (void*)nfcManager_doGetNciVersion},
    {"doEnableDtaMode", "()V", (void*)nfcManager_doEnableDtaMode},
    {"doDisableDtaMode", "()V", (void*)nfcManager_doDisableDtaMode},
    {"doFactoryReset", "()V", (void*)nfcManager_doFactoryReset},
    {"doShutdown", "()V", (void*)nfcManager_doShutdown},

    {"getIsoDepMaxTransceiveLength", "()I",
     (void*)nfcManager_getIsoDepMaxTransceiveLength},

    {"getAidTableSize", "()I", (void*)nfcManager_getAidTableSize},

    {"getDefaultAidRoute", "()I", (void*)nfcManager_getDefaultAidRoute},

    {"getDefaultDesfireRoute", "()I", (void*)nfcManager_getDefaultDesfireRoute},

    {"getDefaultMifareCLTRoute", "()I",
     (void*)nfcManager_getDefaultMifareCLTRoute},

    {"getDefaultAidPowerState", "()I",
     (void*)nfcManager_getDefaultAidPowerState},

    {"getDefaultDesfirePowerState", "()I",
     (void*)nfcManager_getDefaultDesfirePowerState},

    {"getDefaultMifareCLTPowerState", "()I",
     (void*)nfcManager_getDefaultMifareCLTPowerState},
#if(NXP_EXTNS == TRUE)
    {"getT4TNfceePowerState", "()I", (void*)nfcManager_getT4TNfceePowerState},
    {"getDefaultFelicaCLTPowerState", "()I",
     (void*)nfcManager_getDefaultFelicaCLTPowerState},
    {"getDefaultFelicaCLTRoute", "()I",
     (void*)nfcManager_getDefaultFelicaCLTRoute},
    {"doGetActiveSecureElementList", "()[I",
     (void*)nfcManager_getActiveSecureElementList},
    {"doPartialInitForEseCosUpdate", "()Z",
     (void*)nfcManager_doPartialInitForEseCosUpdate},
    {"doPartialDeinitForEseCosUpdate", "()Z",
     (void*)nfcManager_doPartialDeinitForEseCosUpdate},

    {"doResonantFrequency", "(Z)V", (void*)nfcManager_doResonantFrequency},
    {"doSetFieldDetectMode", "(Z)I", (void*)nfcManager_SetFieldDetectMode},
    {"isFieldDetectEnabled", "()Z", (void*)nfcManager_IsFieldDetectEnabled},
    {"doStartRssiMode", "(I)I", (void*)nfcManager_StartRssiMode},
    {"doStopRssiMode", "()I", (void*)nfcManager_StopRssiMode},
    {"isRssiEnabled", "()Z", (void*)nfcManager_IsRssiEnabled},
    // check firmware version
    {"getFWVersion", "()I", (void*)nfcManager_getFwVersion},
    {"isNfccBusy", "()Z", (void*)nfcManager_isNfccBusy},
    {"setTransitConfig", "(Ljava/lang/String;)I",
     (void*)nfcManager_setTransitConfig},
    {"getRemainingAidTableSize", "()I",
     (void*)nfcManager_getRemainingAidTableSize},
    {"doselectUicc", "(I)I", (void*)nfcManager_doSelectUicc},
    {"doGetSelectedUicc", "()I", (void*)nfcManager_doGetSelectedUicc},
    {"setPreferredSimSlot", "(I)I", (void*)nfcManager_setPreferredSimSlot},
    {"doNfcSelfTest", "(I)I", (void*)nfcManager_nfcSelfTest},
    {"doEnableDebugNtf", "(B)I", (void*)nfcManager_enableDebugNtf},
    {"doRestartRFDiscovery", "()V", (void*)nfcManager_restartRFDiscovery},
    {"isRemovalDetectionInPollModeSupported", "()Z",
     (void*)nfcManager_isRemovalDetectionSupported},
    {"startRemovalDetectionProcedure", "(I)V",
     (void*)nfcManager_startRemovalDetectionProcedure},
#endif
    {"doSetNfcSecure", "(Z)Z", (void*)nfcManager_doSetNfcSecure},
    {"doSetPowerSavingMode", "(Z)Z", (void*)nfcManager_doSetPowerSavingMode},
    {"getRoutingTable", "()[B", (void*)nfcManager_doGetRoutingTable},

    {"getMaxRoutingTableSize", "()I",
     (void*)nfcManager_doGetMaxRoutingTableSize},

    {"setObserveMode", "(Z)Z", (void*)nfcManager_setObserveMode},

    {"isObserveModeEnabled", "()Z", (void*)nfcManager_isObserveModeEnabled},

    {"clearRoutingEntry", "(I)V", (void*)nfcManager_clearRoutingEntry},

    {"setIsoDepProtocolRoute", "(I)V",
     (void*)nfcManager_updateIsoDepProtocolRoute},

    {"setTechnologyABRoute", "(I)V", (void*)nfcManager_updateTechnologyABRoute},

    {"setDiscoveryTech", "(II)V", (void*)nfcManager_setDiscoveryTech},

    {"resetDiscoveryTech", "()V", (void*)nfcManager_resetDiscoveryTech},
    {"nativeSendRawVendorCmd", "(III[B)Lcom/android/nfc/NfcVendorNciResponse;",
     (void*)nfcManager_nativeSendRawVendorCmd},

    {"getProprietaryCaps", "()[B", (void*)nfcManager_getProprietaryCaps},
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
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  PowerSwitch::getInstance().initialize(PowerSwitch::UNKNOWN_LEVEL);
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
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

  LOG(DEBUG) << StringPrintf("%s: is start=%d", __func__, isStart);
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
bool isDiscoveryStarted() {
#if(NXP_EXTNS == TRUE)
  bool rfEnabled;
  nativeNfcTag_acquireRfInterfaceMutexLock();
  rfEnabled = sRfEnabled;
  nativeNfcTag_releaseRfInterfaceMutexLock();
  return rfEnabled;
#else
  return sRfEnabled;
#endif
}
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
  // configure RF polling frequency for each technology
  static tNFA_DM_DISC_FREQ_CFG nfa_dm_disc_freq_cfg;
  // values in the polling_frequency[] map to members of nfa_dm_disc_freq_cfg
  std::vector<uint8_t> polling_frequency;
  if (NfcConfig::hasKey(NAME_POLL_FREQUENCY))
    polling_frequency = NfcConfig::getBytes(NAME_POLL_FREQUENCY);
  if (polling_frequency.size() == 8) {
    LOG(DEBUG) << StringPrintf("%s: polling frequency", __func__);
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
  nfcManager_configNfccConfigControl(true);
#if (NXP_EXTNS == TRUE)
    send_flush_ram_to_flash();
#endif

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
#if (NXP_EXTNS == TRUE)
/*******************************************************************************
**
** Function:        nfcManager_isNfcDisabling
**
** Description:     Used externally to determine if NFC is being turned off.
**
** Returns:         'true' if the NFC stack is turning off, else 'false'.
**
*******************************************************************************/
bool nfcManager_isNfcDisabling() { return sIsDisabling; }

/*******************************************************************************
**
** Function:        nfcManager_isReaderModeEnabled
**
** Description:     Used externally to determine if reader mode is Enabled.
**
** Returns:         'true' if reader mode enabled, else 'false'.
**
*******************************************************************************/
bool nfcManager_isReaderModeEnabled() { return sReaderModeEnabled; }

/*******************************************************************************
**
** Function:        nfcManager_isNfcPartialEnabled
**
** Description:     Used externally to determine if NFC is being Enabled partially.
**
** Returns:         'true' if the NFC Partially enabled, else 'false'.
**
*******************************************************************************/
bool nfcManager_isNfcPartialEnabled() { return gsNfaPartialEnabled; }

/*******************************************************************************
**
** Function:        nfcManager_deactivateOnPollDisabled
**
** Description:     Perform deactivate when not in listen mode & polling is
**                  disabled then return true otherwise false.
**
** Returns:         'true' if the NFC stack is turning off, else 'false'.
**
*******************************************************************************/
static bool nfcManager_deactivateOnPollDisabled(tNFA_ACTIVATED& activated) {
  if (!isListenMode(activated) &&
      (prevScreenState == NFA_SCREEN_STATE_OFF_LOCKED ||
       prevScreenState == NFA_SCREEN_STATE_OFF_UNLOCKED || scrnOnLockedPollDisabled)) {
    LOG(DEBUG) << StringPrintf("%s: RF DEACTIVATE to discovery.....", __func__);
    nativeNfcTag_safeDisconnect();
    return true;
  }
  return false;
}
#endif
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
  tNFA_STATUS status = NFA_STATUS_FAILED;
  uint8_t discovry_param = 0;
  LOG(DEBUG) << StringPrintf("%s: enter; isStart=%u", __func__, isStartPolling);

  if (NFC_GetNCIVersion() >= NCI_VERSION_2_0) {
    SyncEventGuard guard(gNfaSetConfigEvent);
    if (isStartPolling) {
      discovry_param =
          NCI_LISTEN_DH_NFCEE_ENABLE_MASK | NCI_POLLING_DH_ENABLE_MASK;
    } else {
      discovry_param =
          NCI_LISTEN_DH_NFCEE_ENABLE_MASK | NCI_POLLING_DH_DISABLE_MASK;
    }
    status = NFA_SetConfig(NCI_PARAM_ID_CON_DISCOVERY_PARAM,
                           NCI_PARAM_LEN_CON_DISCOVERY_PARAM, &discovry_param);
    if (status == NFA_STATUS_OK) {
      gNfaSetConfigEvent.wait();
    } else {
      LOG(ERROR) << StringPrintf("%s: Failed to update CON_DISCOVER_PARAM",
                                 __FUNCTION__);
    }
  } else {
    startRfDiscovery(false);
    if (isStartPolling)
      startPolling_rfDiscoveryDisabled(0);
    else
      stopPolling_rfDiscoveryDisabled();
    startRfDiscovery(true);
  }
  LOG(DEBUG) << StringPrintf("%s: exit", __func__);
}

static tNFA_STATUS startPolling_rfDiscoveryDisabled(
    tNFA_TECHNOLOGY_MASK tech_mask) {
  tNFA_STATUS stat = NFA_STATUS_FAILED;

  if (tech_mask == 0)
    tech_mask =
        NfcConfig::getUnsigned(NAME_POLLING_TECH_MASK, DEFAULT_TECH_MASK);

  nativeNfcTag_acquireRfInterfaceMutexLock();
  SyncEventGuard guard(sNfaEnableDisablePollingEvent);
  LOG(DEBUG) << StringPrintf("%s: enable polling", __func__);
  stat = NFA_EnablePolling(tech_mask);
  if (stat == NFA_STATUS_OK) {
    LOG(DEBUG) << StringPrintf("%s: wait for enable event", __func__);
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
  LOG(DEBUG) << StringPrintf("%s: disable polling", __func__);
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

static jboolean nfcManager_doSetPowerSavingMode(JNIEnv* e, jobject o,
                                                bool flag) {
  LOG(DEBUG) << StringPrintf("%s: enter; ", __func__);
  uint8_t cmd[] = {(NCI_MT_CMD << NCI_MT_SHIFT) | NCI_GID_PROP,
                   NCI_MSG_PROP_ANDROID, NCI_ANDROID_POWER_SAVING_PARAM_SIZE,
                   NCI_ANDROID_POWER_SAVING,
                   NCI_ANDROID_POWER_SAVING_PARAM_DISABLE};
  cmd[4] = flag ? NCI_ANDROID_POWER_SAVING_PARAM_ENABLE
                : NCI_ANDROID_POWER_SAVING_PARAM_DISABLE;
  SyncEventGuard guard(gNfaVsCommand);
  tNFA_STATUS status =
      NFA_SendRawVsCommand(sizeof(cmd), cmd, nfaSendRawVsCmdCallback);
  if (status == NFA_STATUS_OK) {
    gNfaVsCommand.wait();
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed to set power-saving mode", __func__);
    gVSCmdStatus = NFA_STATUS_FAILED;
  }
  return gVSCmdStatus == NFA_STATUS_OK;
}

static jbyteArray nfcManager_getProprietaryCaps(JNIEnv* e, jobject o) {
  LOG(DEBUG) << StringPrintf("%s: enter; ", __func__);
  uint8_t cmd[] = {(NCI_MT_CMD << NCI_MT_SHIFT) | NCI_GID_PROP,
                   NCI_MSG_PROP_ANDROID, 0, NCI_ANDROID_GET_CAPS};
  SyncEventGuard guard(gNfaVsCommand);
  tNFA_STATUS status =
      NFA_SendRawVsCommand(sizeof(cmd), cmd, nfaSendRawVsCmdCallback);
  if (status == NFA_STATUS_OK) {
    gNfaVsCommand.wait();
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed to get caps", __func__);
    gVSCmdStatus = NFA_STATUS_FAILED;
  }
  CHECK(e);
  jbyteArray rtJavaArray = e->NewByteArray(gCaps.size());
  CHECK(rtJavaArray);
  e->SetByteArrayRegion(rtJavaArray, 0, gCaps.size(), (jbyte*)gCaps.data());
  return rtJavaArray;
}

#if(NXP_EXTNS == TRUE)
void setPollingEnabled(bool isPollingEnabled) {
  sPollingEnabled = isPollingEnabled;
}

void setReaderModeEnabled(bool isReaderModeEnabled) {
  sReaderModeEnabled = isReaderModeEnabled;
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
    LOG(DEBUG) << StringPrintf("%s: enter", __FUNCTION__);
    RoutingManager::getInstance().configureOffHostNfceeTechMask();
    nfcManager_enableDiscovery(NULL, NULL,
        mDiscParams.technologies_mask,
        mDiscParams.enable_lptd,
        mDiscParams.reader_mode,
        mDiscParams.enable_host_routing,
        true);
}

void storeLastDiscoveryParams(int technologies_mask, bool enable_lptd,
    bool reader_mode, bool enable_host_routing, bool restart)
{
    LOG(DEBUG) << StringPrintf("%s: enter", __FUNCTION__);
    mDiscParams.technologies_mask = technologies_mask;
    mDiscParams.enable_lptd = enable_lptd;
    mDiscParams.reader_mode = reader_mode;
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
    LOG(DEBUG) << StringPrintf("%s: enter", __func__);
    jint version = 0, temp = 0;
    tNFC_FW_VERSION nfc_native_fw_version;

    memset(&nfc_native_fw_version, 0, sizeof(nfc_native_fw_version));

    nfc_native_fw_version = nfc_ncif_getFWVersion();
    LOG(DEBUG) << StringPrintf("FW Version: %x.%x.%x",
                               nfc_native_fw_version.rom_code_version,
                               nfc_native_fw_version.major_version,
                               nfc_native_fw_version.minor_version);

    temp = nfc_native_fw_version.rom_code_version;
    version = temp << 16;
    temp = nfc_native_fw_version.major_version;
    version |= temp << 8;
    version |= nfc_native_fw_version.minor_version;
    NativeJniExtns::getInstance().notifyNfcEvent("nfcManager_updateRfRegInfo");

    LOG(DEBUG) << StringPrintf("%s: exit; version =0x%X", __func__, version);
    return version;
}

/*******************************************************************************
**
** Function:        nfcManager_restartRFDiscovery
** Description:     Restarts RF discovery
**
**                  e: JVM environment.
**                  o: Java object.
**
*******************************************************************************/
static void nfcManager_restartRFDiscovery(JNIEnv*, jobject) {
  if (sRfEnabled) {
    android::startRfDiscovery(false);
  }
  android::startRfDiscovery(true);
}

  /*******************************************************************************
*******************************************************************************
**
** Function:        nfcManager_isNfccBusy
**
** Description:     Check If NFCC is busy
**
** Returns:         True if NFCC is busy.
**
*******************************************************************************/
static bool nfcManager_isNfccBusy(JNIEnv*, jobject) {
  LOG(DEBUG) << StringPrintf("%s: ENTER", __func__);
  bool statBusy = false;
  if (sSeRfActive || gActivated) {
    LOG(ERROR) << StringPrintf("%s:FAIL  RF session ongoing", __func__);
    statBusy = true;
    }
    return statBusy;
}
static int nfcManager_setTransitConfig(JNIEnv * e, jobject o,
                                         jstring config) {
    (void)e;
    (void)o;
    LOG(DEBUG) << StringPrintf("%s: enter", __func__);
    std::string transitConfig = ConvertJavaStrToStdString(e, config);
    SyncEventGuard guard(sNfaTransitConfigEvent);
    int stat = NFA_SetTransitConfig(std::move(transitConfig));
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
std::string ConvertJavaStrToStdString(JNIEnv* env, jstring s) {
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
        LOG(DEBUG) << StringPrintf("staticDualUicc_Precondition failed.");
        return retStat;
      }
    }
    status = nfcManager_setPreferredSimSlot(NULL, NULL, uiccSlot);
    if (status == NFA_STATUS_OK) retStat = UICC_CONFIGURED;
  } else {
    retStat = DUAL_UICC_FEATURE_NOT_AVAILABLE;
    LOG(DEBUG) << StringPrintf("%s: Dual uicc not supported retStat = %d",
                               __func__, retStat);
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
    LOG(DEBUG) << StringPrintf("%s: dual uicc not supported ", __func__);
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
    LOG(DEBUG) << StringPrintf("%s:Dual UICC feature not available . Returning",
                               __func__);
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

static rssi_status_t nfcManager_doSetRssiMode(
    bool enable, int rssiNtfTimeIntervalInMillisec) {
  LOG(DEBUG) << StringPrintf("%s: Enter rssiNtfTimeIntervalInMillisec = %d ",
                             __func__, rssiNtfTimeIntervalInMillisec);

  if (!sIsNfaEnabled) {
    LOG(ERROR) << StringPrintf("%s: Nfc is not Enabled. Returning", __func__);
    return FDSTATUS_ERROR_NFC_IS_OFF;
  }

  if (MposManager::getInstance().isMposOngoing()) {
    LOG(ERROR) << StringPrintf("%s: MPOS is ongoing.. Returning", __func__);
    return FDSTATUS_ERROR_NFC_BUSY_IN_MPOS;
  }

  if (NFA_IsRssiEnabled() == enable) {
    LOG(DEBUG) << StringPrintf("%s: Already %s", __func__,
                               ((enable) ? "ENABLED" : "DISABLED"));
    return FDSTATUS_SUCCESS;
  }

  uint8_t rssiNtfTimeInterval =
      (rssiNtfTimeIntervalInMillisec / 10) +
      ((rssiNtfTimeIntervalInMillisec % 10 != 0x00) ? 0x01 : 0x00);
  if (enable && (rssiNtfTimeInterval < 0x01 || rssiNtfTimeInterval > 0xFF)) {
    LOG(ERROR) << StringPrintf(
        "%s: Rssi Notification timeout interval should be in between 10 to "
        "2550 Millisec",
        __func__);
    return FDSTATUS_ERROR_UNKNOWN;
  }

  if (sRfEnabled) {
    // Stop RF Discovery
    LOG(DEBUG) << StringPrintf("%s: stop discovery", __func__);
    startRfDiscovery(false);
  }
  NFA_SetFieldDetectMode(enable);

  tNFA_STATUS status = NFA_STATUS_REJECTED;
  uint8_t cmd_rssi[] = {0x20, 0x02, 0x06, 0x01, 0xA1, 0x55, 0x02, 0x00, 0x00};

  cmd_rssi[7] = (uint8_t)enable;  // To enable/disable RSSI
  cmd_rssi[8] =
      (uint8_t)rssiNtfTimeInterval;  // RSSI NTF time Interval in value * 10 ms
  status = android::NxpNfc_Write_Cmd_Common(sizeof(cmd_rssi), cmd_rssi);

  if (status != FDSTATUS_SUCCESS) {
    NFA_SetFieldDetectMode(false);
    // start discovery
    LOG(DEBUG) << StringPrintf("%s: reconfigured start discovery Line:%d",
                               __func__, __LINE__);
    startRfDiscovery(true);
    return FDSTATUS_ERROR_UNKNOWN;
  }

  NFA_SetRssiMode(enable);
  // start discovery
  LOG(DEBUG) << StringPrintf("%s: reconfigured start discovery Line:%d",
                             __func__, __LINE__);
  startRfDiscovery(true);
  return FDSTATUS_SUCCESS;
}

/**********************************************************************************
**
** Function:       nfcManager_enableDebugNtf
**
** Description:    Enable & disable the Lx debug notifications
** Byte 0:
**  b7|b6|b5|b4|b3|b2|b1|b0|
**    |  |x |  |  |  |  |  |    Modulation Detected
**    |  |  |X |  |  |  |  |    Enable L1 Events (ISO14443-4, ISO18092)
**    |  |  |  |X |  |  |  |    Enable L2 Reader Events(ROW specific)
**    |  |  |  |  |X |  |  |    Enable Felica SystemCode
**    |  |  |  |  |  |X |  |    Enable Felica RF (all Felica CM events)
**    |  |  |  |  |  |  |X |    Enable L2 Events CE (ISO14443-3, RF Field ON/OFF)
** Byte 1: Reserved for future use, shall always be 0x00.
**
** Returns:        returns 0x00 in success case, 0x03 in failure case,
**                 0x01 is Nfc is off
**********************************************************************************/
static jint nfcManager_enableDebugNtf(JNIEnv* e, jobject o, jbyte fieldValue) {
  uint8_t cmd_lxdebug[] = { 0x20, 0x02, 0x06, 0x01, 0xA0, 0x1D, 0x02, 0x00, 0x00 };
  tNFA_STATUS status = NFA_STATUS_REJECTED;
  LOG(DEBUG) << StringPrintf("%s : enter", __func__);

  if (!sIsNfaEnabled || sIsDisabling) { return status; }

  if (sRfEnabled) { startRfDiscovery(false); }
  /* As of now, bit0, bit4 and bit5 is allowed by this API */
  cmd_lxdebug[7] = (uint8_t)(fieldValue & L2_DEBUG_BYTE0_MASK); /* Lx debug ntfs */
  status = android::NxpNfc_Write_Cmd_Common(sizeof(cmd_lxdebug),cmd_lxdebug);

  if (status) { status = NFA_STATUS_FAILED; }

  startRfDiscovery(true);
  return status;
}

/**********************************************************************************
**
** Function:       waitIfRfStateActive
**
** Description:    This api is used to wait/delay if RF session is active.
**
** Returns:        None
**
**********************************************************************************/
static void waitIfRfStateActive() {
  uint16_t delayLoopCount = 0;
  uint16_t delayInMs = 50;
  const uint16_t maxDelayInMs = 1000;
  uint16_t maxDelayLoopCount = maxDelayInMs/delayInMs;

  LOG(INFO) << StringPrintf("isCePriorityEnabled :%d", isCePriorityEnabled);
  if (!isCePriorityEnabled) return;

  SecureElement& se = SecureElement::getInstance();

  while (se.isRfFieldOn() || sSeRfActive) {
    /* Delay is required to avoid update routing during RF Field session*/
    usleep(delayInMs * 1000);
    delayLoopCount++;
    if (delayLoopCount > maxDelayLoopCount) {
      break;
    }
  }
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
  LOG(DEBUG) << StringPrintf("%s : uiccslot : %d : enter", __func__, uiccSlot);

  int retStat = UICC_NOT_CONFIGURED;
  NativeJniExtns& jniExtns = NativeJniExtns::getInstance();
  if (!isDynamicUiccEnabled) {
    if (jniExtns.isExtensionPresent()) {
      retStat = nfcManager_staticDualUicc_Precondition(uiccSlot);

      if (retStat != UICC_NOT_CONFIGURED) {
        LOG(DEBUG) << StringPrintf("staticDualUicc_Precondition failed.");
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

/*******************************************************************************
 **
 ** Function:        nfcManager_nfcSelfTest
 **
 ** Description:     Function to perform different types of analog tests
 **                  i'e RF ON, RF OFF, Transc A, Transc B.
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/

static jint nfcManager_nfcSelfTest(JNIEnv* e, jobject o, jint aType)
{
    return NfcSelfTest::GetInstance().doNfccSelfTest(aType);
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

