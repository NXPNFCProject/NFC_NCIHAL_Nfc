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
 *  The original Work has been changed by NXP Semiconductors.
 *
 *  Copyright (C) 2015-2018 NXP Semiconductors
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
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <cutils/properties.h>
#include <errno.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <pthread.h>
#include <semaphore.h>
#include <sys/time.h>
#include "HciEventManager.h"
#include "HciRFParams.h"
#include "JavaClassConstants.h"
#include "NfcAdaptation.h"
#include "NfcJniUtil.h"
#include "NfcTag.h"
#include "PeerToPeer.h"
#include "Pn544Interop.h"
#include "PowerSwitch.h"
#include "RoutingManager.h"
#include "SecureElement.h"
#include "SyncEvent.h"
#include "nfc_config.h"
#if (NXP_EXTNS == TRUE)
#include <cutils/properties.h>
#include <signal.h>
#include <sys/types.h>
#include "MposManager.h"
#endif
#include <fcntl.h>
#include "DwpChannel.h"
#include "JcopManager.h"
#include "TransactionController.h"
#include "ce_api.h"
#include "nfa_api.h"
#include "nfa_ee_api.h"
#include "nfa_p2p_api.h"
#include "nfc_api.h"
#include "nfc_brcm_defs.h"
#include "phNxpConfig.h"
#include "phNxpExtns.h"
#include "rw_api.h"

using android::base::StringPrintf;

#define SAK_VALUE_AT 17
extern tNFA_DM_DISC_FREQ_CFG* p_nfa_dm_rf_disc_freq_cfg;
bool sHCEEnabled = true;
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
#if (NXP_EXTNS == TRUE)
#define RETRY_COUNT 0x10
#define DEFAULT_COUNT 0x03
#define ENABLE_DISCOVERY 0x01
#define DISABLE_DISCOVERY 0x02
#define ENABLE_P2P 0x04
#define T3T_CONFIGURE 0x08
#define RE_ROUTING 0x10
#define CLEAR_ENABLE_DISABLE_PARAM 0xFC
/* Delay to wait for SE intialization */
#define SE_INIT_DELAY 50 * 1000
#define NFCEE_DISC_TIMEOUT_SEC 2
#define JCOP_INFO_PATH "/data/nfc/jcop_info.txt"
#define OSU_NOT_STARTED 00
#define OSU_COMPLETE 03
#define NFC_PIPE_STATUS_OFFSET 4
#define MAX_JCOP_TIMEOUT_VALUE 60000 /*Maximum Jcop OSU timeout value*/
#define MAX_WAIT_TIME_FOR_RETRY 8    /*Maximum wait for retry in usec*/
#define ESE_PWR_MGMT_PROP "ro.config.low_ram"
#define IS_ESE_RF_CE_PARAM_FETCHED()             \
  (pEvtData->get_config.param_tlvs[1] == 0xA0 && \
   pEvtData->get_config.param_tlvs[2] == 0xF0)
#define IS_ESE_CE_MODE_DISABLED()                \
  (pEvtData->get_config.param_tlvs[5] == 0xFF || \
   pEvtData->get_config.param_tlvs[43] == 0xFF)


extern nfcee_disc_state sNfcee_disc_state;
extern bool recovery;
extern uint8_t swp_getconfig_status;
extern int gUICCVirtualWiredProtectMask;
extern int gEseVirtualWiredProtectMask;
extern bool IsEseCeDisabled;
static int32_t gNfcInitTimeout;
int32_t gdisc_timeout;
int32_t gSeDiscoverycount = 0;
int32_t gActualSeCount = 0;
uint16_t sCurrentSelectedUICCSlot = 1;
SyncEvent gNfceeDiscCbEvent;
uint8_t sSelectedUicc = 0;
static bool sIsLowRamDevice = false;
int32_t gSelfTestType = TEST_TYPE_NONE;
bool nfcManager_getTransanctionRequest(int t3thandle, bool registerRequest);
extern bool createSPIEvtHandlerThread();
extern void releaseSPIEvtHandlerThread();
#endif
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
extern void doDwpChannel_ForceExit();
extern void nativeLlcpConnectionlessSocket_abortWait();
extern tNFA_STATUS EmvCo_dosetPoll(jboolean enable);
extern void nativeNfcTag_registerNdefTypeHandler();
extern void nativeLlcpConnectionlessSocket_receiveData(uint8_t* data,
                                                       uint32_t len,
                                                       uint32_t remote_sap);
extern tNFA_STATUS SetScreenState(int state);
extern tNFA_STATUS SendAutonomousMode(int state, uint8_t num);
// Factory Test Code --start
extern tNFA_STATUS Nxp_SelfTest(uint8_t testcase, uint8_t* param);
extern void SetCbStatus(tNFA_STATUS status);
extern tNFA_STATUS GetCbStatus(void);
static void nfaNxpSelfTestNtfTimerCb(union sigval);
extern tNFA_STATUS ResetEseSession();
// Factory Test Code --end
extern bool getReconnectState(void);
extern tNFA_STATUS SetVenConfigValue(jint nfcMode);
extern tNFA_STATUS SetHfoConfigValue(void);
extern tNFA_STATUS SetUICC_SWPBitRate(bool);
extern tNFA_STATUS GetNumNFCEEConfigured(void);
extern void nativeNfcTag_acquireRfInterfaceMutexLock();
extern void nativeNfcTag_releaseRfInterfaceMutexLock();
extern tNFA_STATUS NxpNfc_Write_Cmd_Common(uint8_t retlen, uint8_t* buffer);
#if (NXP_EXTNS == TRUE)
extern bool gIsWaiting4Deact2SleepNtf;
extern bool gGotDeact2IdleNtf;
bool nfcManager_isTransanctionOnGoing(bool isInstallRequest);
bool nfcManager_isRequestPending(void);
extern tNFA_STATUS enableSWPInterface();
extern tNFA_STATUS NxpNfc_Send_CoreResetInit_Cmd(void);
jmethodID gCachedNfcManagerNotifyFwDwnldRequested;
extern tNFA_STATUS SendAGCDebugCommand();
extern tNFA_STATUS Set_EERegisterValue(uint16_t RegAddr, uint8_t bitVal);
extern void nativeNfcTag_cacheNonNciCardDetection();
extern void nativeNfcTag_handleNonNciCardDetection(
    tNFA_CONN_EVT_DATA* eventData);
extern void nativeNfcTag_handleNonNciMultiCardDetection(
    uint8_t connEvent, tNFA_CONN_EVT_DATA* eventData);
extern tNFA_STATUS NxpNfcUpdateEeprom(uint8_t* param, uint8_t len,
                                      uint8_t* val);
extern uint8_t checkTagNtf;
extern uint8_t checkCmdSent;
#endif
}  // namespace android

/*****************************************************************************
**
** public variables and functions
**
*****************************************************************************/
bool gActivated = false;
SyncEvent gDeactivatedEvent;

namespace android {
int gGeneralPowershutDown = 0;
jmethodID gCachedNfcManagerNotifyNdefMessageListeners;
jmethodID gCachedNfcManagerNotifyTransactionListeners;
jmethodID gCachedNfcManagerNotifyConnectivityListeners;
jmethodID gCachedNfcManagerNotifyEmvcoMultiCardDetectedListeners;
jmethodID gCachedNfcManagerNotifyLlcpLinkActivation;
jmethodID gCachedNfcManagerNotifyLlcpLinkDeactivated;
jmethodID gCachedNfcManagerNotifyLlcpFirstPacketReceived;
jmethodID gCachedNfcManagerNotifySeFieldActivated;
jmethodID gCachedNfcManagerNotifySeFieldDeactivated;
jmethodID gCachedNfcManagerNotifySeListenActivated;
jmethodID gCachedNfcManagerNotifySeListenDeactivated;
jmethodID gCachedNfcManagerNotifyHostEmuActivated;
jmethodID gCachedNfcManagerNotifyHostEmuData;
jmethodID gCachedNfcManagerNotifyHostEmuDeactivated;
jmethodID gCachedNfcManagerNotifyRfFieldActivated;
jmethodID gCachedNfcManagerNotifyRfFieldDeactivated;
jmethodID gCachedNfcManagerNotifyAidRoutingTableFull;
#if (NXP_EXTNS == TRUE)
int gMaxEERecoveryTimeout = MAX_EE_RECOVERY_TIMEOUT;
jmethodID gCachedNfcManagerNotifyUiccStatusEvent;
jmethodID gCachedNfcManagerNotifyT3tConfigure;
jmethodID gCachedNfcManagerNotifyJcosDownloadInProgress;
jmethodID gCachedNfcManagerNotifyReRoutingEntry;
#endif
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
const char* gNativeNfcSecureElementClassName =
    "com/android/nfc/dhimpl/NativeNfcSecureElement";
const char* gNativeNfcAlaClassName = "com/android/nfc/dhimpl/NativeNfcAla";
const char* gNativeNfcMposManagerClassName =
    "com/android/nfc/dhimpl/NativeNfcMposManager";
void doStartupConfig();
void startStopPolling(bool isStartPolling);
void startRfDiscovery(bool isStart);
void setUiccIdleTimeout(bool enable);
bool isDiscoveryStarted();
void requestFwDownload();
#if (NXP_EXTNS == TRUE)
void enableRfDiscovery();
void disableRfDiscovery();
void storeLastDiscoveryParams(int technologies_mask, bool enable_lptd,
                              bool reader_mode, bool enable_p2p, bool restart);
static tNFA_STATUS configureListening(uint8_t aListenMask);
static tNFA_STATUS configurePolling(uint8_t aPollMask);
static tNFA_STATUS performRFTest(bool on);
static tNFA_STATUS performTransacAB(uint8_t aType);
static void activatedNtf_Cb();
#endif
}  // namespace android

/*****************************************************************************
**
** private variables and functions
**
*****************************************************************************/
namespace android {
static jint sLastError = ERROR_BUFFER_TOO_SMALL;
static jmethodID sCachedNfcManagerNotifySeApduReceived;
static jmethodID sCachedNfcManagerNotifySeMifareAccess;
static jmethodID sCachedNfcManagerNotifySeEmvCardRemoval;
static jmethodID sCachedNfcManagerNotifyTargetDeselected;
static SyncEvent sNfaEnableEvent;         // event for NFA_Enable()
static SyncEvent sNfaDisableEvent;        // event for NFA_Disable()
static SyncEvent sNfaTransitConfigEvent;  // event for NFA_SetTransitConfig()
SyncEvent sNfaEnableDisablePollingEvent;  // event for NFA_EnablePolling(),
                                          // NFA_DisablePolling()
SyncEvent sNfaSetConfigEvent;             // event for Set_Config....
SyncEvent sNfaGetConfigEvent;             // event for Get_Config....
SyncEvent sExecPendingRegEvent;

static bool sIsExecPendingReq = false;
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

static uint8_t sIsSecElemSelected = 0;  // has NFC service selected a sec elem
static uint8_t sIsSecElemDetected = 0;  // has NFC service deselected a sec elem
static bool sDiscCmdwhleNfcOff = false;
static uint8_t sAutonomousSet = 0;

#define CONFIG_UPDATE_TECH_MASK (1 << 1)
#define TRANSACTION_TIMER_VALUE 50
#define DEFAULT_TECH_MASK                                                  \
  (NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B | NFA_TECHNOLOGY_MASK_F | \
   NFA_TECHNOLOGY_MASK_V | NFA_TECHNOLOGY_MASK_B_PRIME |                   \
   NFA_TECHNOLOGY_MASK_A_ACTIVE | NFA_TECHNOLOGY_MASK_F_ACTIVE |           \
   NFA_TECHNOLOGY_MASK_KOVIO)
#define DEFAULT_DISCOVERY_DURATION 500
#define READER_MODE_DISCOVERY_DURATION 200
#if (NXP_EXTNS == TRUE)
static bool gsNfaPartialEnabled = false;
static uint16_t sEnableStatus = false;
SyncEvent sNfaGetRoutingEvent;  // event for Get_Routing....
static SyncEvent mSelfTestTransacAB;  // Event for Get activation
static bool sProvisionMode = false;
SyncEvent sNfceeHciCbEnableEvent;
SyncEvent sNfceeHciCbDisableEvent;
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
static tNFA_STATUS nfcManagerEnableNfc(NfcAdaptation& theInstance);
static int nfcManager_setTransitConfig(JNIEnv* e, jobject o, jstring config);
#endif

static int screenstate = NFA_SCREEN_STATE_OFF_LOCKED;
static bool pendingScreenState = false;
static void nfcManager_doSetScreenState(JNIEnv* e, jobject o,
                                        jint screen_state_mask);
static jint nfcManager_doGetNciVersion(JNIEnv*, jobject);
static int NFA_SCREEN_POLLING_TAG_MASK = 0x10;
static void nfcManager_doSetScreenOrPowerState(JNIEnv* e, jobject o,
                                               jint state);
static void StoreScreenState(int state);
int getScreenState();
static void nfaConnectionCallback(uint8_t event, tNFA_CONN_EVT_DATA* eventData);
static void nfaDeviceManagementCallback(uint8_t event,
                                        tNFA_DM_CBACK_DATA* eventData);
static bool isPeerToPeer(tNFA_ACTIVATED& activated);
static bool isListenMode(tNFA_ACTIVATED& activated);
static tNFA_STATUS stopPolling_rfDiscoveryDisabled();
static tNFA_STATUS startPolling_rfDiscoveryDisabled(
    tNFA_TECHNOLOGY_MASK tech_mask);

static int nfcManager_getChipVer(JNIEnv* e, jobject o);
static std::string ConvertJavaStrToStdString(JNIEnv* env, jstring s);
static int nfcManager_getNfcInitTimeout(JNIEnv* e, jobject o);
static int nfcManager_doJcosDownload(JNIEnv* e, jobject o);
static void nfcManager_doCommitRouting(JNIEnv* e, jobject o);
static jint nfcManager_getSecureElementTechList(JNIEnv* e, jobject o);
static void nfcManager_setSecureElementListenTechMask(JNIEnv* e, jobject o,
                                                      jint tech_mask);
static void notifyPollingEventwhileNfcOff();

static uint8_t getJCOPOS_UpdaterState();
void DWPChannel_init(IChannel_t* DWP);
IChannel_t Dwp;
static uint16_t sCurrentConfigLen;
static uint8_t sConfig[256];
static int prevScreenState = NFA_SCREEN_STATE_OFF_LOCKED;
#if (NXP_EXTNS == TRUE)
static uint8_t sNfcState = NFC_OFF;
bool isp2pActivated();
static void nfcManager_doSetNfcMode(JNIEnv* e, jobject o, jint nfcMode);
typedef struct {
  uint8_t sUicc1Cntx[256];
  uint16_t sUicc1CntxLen;
  uint8_t sUicc2Cntx[256];
  uint16_t sUicc2CntxLen;
  uint8_t sUicc1TechCapblty[12];
  uint8_t sUicc2TechCapblty[12];
  uint8_t sUicc1SessionId[8];
  uint8_t sUicc2SessionId[8];
  uint8_t sUicc1SessionIdLen;
  uint8_t sUicc2SessionIdLen;
  uint8_t uiccActivStat = 0;
  uint8_t uiccConfigStat = 0;
  unsigned long dualUiccEnable = 0;
} dual_uicc_info_t;
dual_uicc_info_t dualUiccInfo;
typedef enum {
  UICC_CONNECTED_0,
  UICC_CONNECTED_1,
  UICC_CONNECTED_2
} uicc_enumeration_t;

#endif
static uint8_t sLongGuardTime[] = {0x00, 0x20};
static uint8_t sDefaultGuardTime[] = {0x00, 0x11};
#if (NXP_EXTNS == TRUE)
Mutex gTransactionMutex;
const char* cur_transaction_handle = NULL;
/*Proprietary cmd sent to HAL to send reader mode flag
 * Last byte of sProprietaryCmdBuf contains ReaderMode flag */
#define PROPRIETARY_CMD_FELICA_READER_MODE 0xFE
static uint8_t sProprietaryCmdBuf[] = {0xFE, 0xFE, 0xFE, 0x00};
uint8_t felicaReader_Disc_id;
static void NxpResponsePropCmd_Cb(uint8_t event, uint16_t param_len,
                                  uint8_t* p_param);
static int sTechMask = 0;  // Copy of Tech Mask used in doEnableReaderMode
static SyncEvent sRespCbEvent;
bool rfActivation = false;
static void* pollT3TThread(void* arg);
static bool switchP2PToT3TRead(uint8_t disc_id);
static bool isActivatedTypeF(tNFA_ACTIVATED& activated);
typedef enum felicaReaderMode_state {
  STATE_IDLE = 0x00,
  STATE_NFCDEP_ACTIVATED_NFCDEP_INTF,
  STATE_DEACTIVATED_TO_SLEEP,
  STATE_FRAMERF_INTF_SELECTED,
} eFelicaReaderModeState_t;
static eFelicaReaderModeState_t gFelicaReaderState = STATE_IDLE;

uint16_t sRoutingBuffLen;
static uint8_t sRoutingBuff[MAX_GET_ROUTING_BUFFER_SIZE];
static uint8_t sNfceeConfigured;
static uint8_t sCheckNfceeFlag;
void checkforNfceeBuffer();
static uint32_t eSEPhyIntfInResponsive(tNFA_DM_CBACK_DATA* pEvtData);
static void recoverEseConnectivity();
void checkforNfceeConfig(uint8_t type);
static void performHCIInitialization(JNIEnv* e, jobject o);
void performNfceeETSI12Config();
tNFA_STATUS getUICC_RF_Param_SetSWPBitRate();
// self test start
static IntervalTimer
    nfaNxpSelfTestNtfTimer;           // notification timer for swp self test
static IntervalTimer uiccEventTimer;  // notification timer for uicc select
static void notifyUiccEvent(union sigval);
static SyncEvent sNfaNxpNtfEvent;
static SyncEvent sNfaSetPowerSubState;  // event for power substate
static void nfaNxpSelfTestNtfTimerCb(union sigval);
static int nfcManager_setPreferredSimSlot(JNIEnv* e, jobject o, jint uiccSlot);
static void nfcManager_doSetEEPROM(JNIEnv* e, jobject o, jbyteArray val);
static jint nfcManager_getFwVersion(JNIEnv* e, jobject o);
static jint nfcManager_SWPSelfTest(JNIEnv* e, jobject o, jint ch);
static void nfcManager_doPrbsOff(JNIEnv* e, jobject o);
static void nfcManager_doPrbsOn(JNIEnv* e, jobject o, jint prbs, jint hw_prbs,
                                jint tech, jint rate);
static void nfcManager_Enablep2p(JNIEnv* e, jobject o, jboolean p2pFlag);
// self test end
static void nfcManager_setProvisionMode(JNIEnv* e, jobject o,
                                        jboolean provisionMode);
static bool nfcManager_doPartialDeInitialize();
static int nfcManager_doSelectUicc(JNIEnv* e, jobject o, jint uiccSlot);
static void restartUiccListen(jint uiccSlot);
static int nfcManager_doGetSelectedUicc(JNIEnv* e, jobject o);
static void getUiccContext(int uiccSlot);
static void update_uicc_context_info();
static int getUiccSession();
static void read_uicc_context(uint8_t* uiccContext, uint16_t uiccContextLen,
                              uint8_t* uiccTechCap, uint16_t uiccTechCapLen,
                              uint8_t block, uint8_t slotnum);
static void write_uicc_context(uint8_t* uiccContext, uint16_t uiccContextLen,
                               uint8_t* uiccTechCap, uint16_t uiccTechCapLen,
                               uint8_t block, uint8_t slotnum);
static uint16_t calc_crc16(uint8_t* pBuff, uint16_t wLen);

static int nfcManager_staticDualUicc_Precondition(int uiccSlot);
void checkforESERemoval();
bool nfcManager_sendEmptyDataMsg();
bool gIsEmptyRspSentByHceFApk = false;

static int nfcManager_doGetSeInterface(JNIEnv* e, jobject o, jint type);

extern bool scoreGenericNtf;
tNFC_FW_VERSION get_fw_version();
bool isNfcInitializationDone();
static uint16_t discDuration = 0x00;
uint16_t getrfDiscoveryDuration();
typedef struct enableAGC_debug {
  long enableAGC;        // config param
  bool AGCdebugstarted;  // flag to indicate agc ongoing
  bool AGCdebugrunning;  // flag to indicate agc running or stopped.
} enableAGC_debug_t;
static enableAGC_debug_t menableAGC_debug_t;
void* enableAGCThread(void* arg);
static void nfcManagerEnableAGCDebug(uint8_t connEvent);
void set_AGC_process_state(bool state);
bool get_AGC_process_state();

void checkforTranscation(uint8_t connEvent, void* eventData);
void sig_handler(int signo);
void cleanup_timer();
tNFA_STATUS updateEeprom(uint8_t* param, uint8_t len, uint8_t* val);
/* Transaction Events in order */
static IntervalTimer scleanupTimerProc_rffield;
typedef enum transcation_events {
  NFA_TRANS_DEFAULT = 0x00,
  NFA_TRANS_ACTIVATED_EVT,
  NFA_TRANS_EE_ACTION_EVT,
  NFA_TRANS_DM_RF_FIELD_EVT,
  NFA_TRANS_DM_RF_FIELD_EVT_ON,
  NFA_TRANS_DM_RF_TRANS_START,
  NFA_TRANS_DM_RF_FIELD_EVT_OFF,
  NFA_TRANS_DM_RF_TRANS_PROGRESS,
  NFA_TRANS_DM_RF_TRANS_END,
  NFA_TRANS_MIFARE_ACT_EVT,
  NFA_TRANS_CE_ACTIVATED = 0x18,
  NFA_TRANS_CE_DEACTIVATED = 0x19,
} eTranscation_events_t;

typedef enum se_client {
  DEFAULT = 0x00,
  LDR_SRVCE,
  JCOP_SRVCE,
  LTSM_SRVCE
} seClient_t;

/*Structure to store  discovery parameters*/
typedef struct discovery_Parameters {
  int technologies_mask;
  bool enable_lptd;
  bool reader_mode;
  bool enable_p2p;
  bool restart;
} discovery_Parameters_t;

discovery_Parameters_t mDiscParams;
/*Structure to store transcation result*/
typedef struct Transcation_Check {
  bool trans_in_progress;
  char last_request;
  struct nfc_jni_native_data* transaction_nat;
  eScreenState_t last_screen_state_request;
  eTranscation_events_t current_transcation_state;
  discovery_Parameters_t discovery_params;
  int t3thandle;
  bool isInstallRequest;
#endif
}  // namespace android
Transcation_Check_t;
static struct nfc_jni_native_data* gNativeData = NULL;
#if (NXP_EXTNS == TRUE)
static bool sRfFieldOff = true;
static bool gsRouteUpdated = false;
/***P2P-Prio Logic for Multiprotocol***/
static uint8_t multiprotocol_flag = 1;
static uint8_t multiprotocol_detected = 0;
void* p2p_prio_logic_multiprotocol(void* arg);
static IntervalTimer multiprotocol_timer;
pthread_t multiprotocol_thread;
void reconfigure_poll_cb(union sigval);
void clear_multiprotocol();
void multiprotocol_clear_flag(union sigval);
bool update_transaction_stat(const char* req_handle,
                             transaction_state_t req_state);
bool isLowRamDevice();
#endif
extern tNFA_INTF_TYPE sCurrentRfInterface;
static Transcation_Check_t transaction_data;
static void nfcManager_enableDiscovery(JNIEnv* e, jobject o,
                                       jint technologies_mask,
                                       jboolean enable_lptd,
                                       jboolean reader_mode,
                                       jboolean enable_p2p, jboolean restart);
void nfcManager_disableDiscovery(JNIEnv*, jobject);
static char get_last_request(void);
static void set_last_request(char status, struct nfc_jni_native_data* nat);
static eScreenState_t get_lastScreenStateRequest(void);
static void set_lastScreenStateRequest(eScreenState_t status);
void* enableThread(void* arg);
static IntervalTimer scleanupTimerProc_transaction;
static bool gIsDtaEnabled = false;

static void nfcManager_getFeatureList();
static void register_signal_handler();
static jbyteArray nfcManager_readerPassThruMode(JNIEnv *e, jobject o,
                                                jbyte rqst,
                                                jbyte modulationTyp);
static jbyteArray nfcManager_transceiveAppData(JNIEnv *e, jobject o,
                                               jbyteArray data);
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

static void nfcManager_doShutdown(JNIEnv*, jobject) {
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  theInstance.DeviceShutdown();
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
  int thread_ret;

  if (discoveredDevice->more == NCI_DISCOVER_NTF_MORE) {
    // there is more discovery notification coming
    NfcTag::getInstance().mNumDiscNtf++;
    return;
  }

  NfcTag::getInstance().mNumDiscNtf++;
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Total Notifications - %d ", __FUNCTION__,
                      NfcTag::getInstance().mNumDiscNtf);

  if (NfcTag::getInstance().mNumDiscNtf > 1) {
    NfcTag::getInstance().mIsMultiProtocolTag = true;
  }

  bool isP2p = NfcTag::getInstance().isP2pDiscovered();

  if (!sReaderModeEnabled && isP2p) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Select peer device", __FUNCTION__);
#if (NXP_EXTNS == TRUE)
    if (multiprotocol_detected == 1) {
      multiprotocol_timer.kill();
    }
#endif
    NfcTag::getInstance().selectP2p();
  }
#if (NXP_EXTNS == TRUE)
  else if (!sReaderModeEnabled && multiprotocol_flag) {
    NfcTag::getInstance().mNumDiscNtf = 0x00;
    multiprotocol_flag = 0;
    multiprotocol_detected = 1;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: starting p2p prio logic for multiprotocol tags", __FUNCTION__);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    thread_ret = pthread_create(&multiprotocol_thread, &attr,
                                p2p_prio_logic_multiprotocol, NULL);
    if (thread_ret != 0)
      LOG(ERROR) << StringPrintf("%s: unable to create the thread",
                                 __FUNCTION__);
    pthread_attr_destroy(&attr);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: starting timer for reconfigure default polling callback",
        __FUNCTION__);
    multiprotocol_timer.set(300, reconfigure_poll_cb);
  }
#endif
  else {
    multiprotocol_flag = 1;
#if (NXP_EXTNS == TRUE)
    NfcTag::getInstance().mNumDiscNtf--;
#endif
    NfcTag::getInstance().selectFirstTag();
  }
}

#if (NXP_EXTNS == TRUE)
void* p2p_prio_logic_multiprotocol(void* arg) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  tNFA_TECHNOLOGY_MASK tech_mask = 0x00;

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __FUNCTION__);
  /* Do not need if it is already in screen off state */
  if (!(getScreenState() &
        (NFA_SCREEN_STATE_OFF_LOCKED | NFA_SCREEN_STATE_OFF_UNLOCKED))) {
    /* Stop polling */
    if (sRfEnabled) {
      startRfDiscovery(false);
    }

    {
      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      status = NFA_DisablePolling();
      if (status == NFA_STATUS_OK) {
        sNfaEnableDisablePollingEvent.wait();
      } else
        LOG(ERROR) << StringPrintf("%s: Failed to disable polling; error=0x%X",
                                   __FUNCTION__, status);
    }

    if (multiprotocol_detected) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: configure polling to tech F only", __FUNCTION__);
      tech_mask = NFA_TECHNOLOGY_MASK_F;
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: re-configure polling to default", __FUNCTION__);
      if (NfcConfig::hasKey(NAME_POLLING_TECH_MASK)) {
        tech_mask = NfcConfig::getUnsigned(NAME_POLLING_TECH_MASK);
      } else {
        tech_mask = DEFAULT_TECH_MASK;
      }
    }

    {
      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      status = NFA_EnablePolling(tech_mask);
      if (status == NFA_STATUS_OK) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: wait for enable event", __FUNCTION__);
        sNfaEnableDisablePollingEvent.wait();
      } else {
        LOG(ERROR) << StringPrintf("%s: fail enable polling; error=0x%X",
                                   __FUNCTION__, status);
      }
    }

    /* start polling */
    if (!sRfEnabled) {
      startRfDiscovery(true);
    }
  }
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __FUNCTION__);
  return NULL;
}

void reconfigure_poll_cb(union sigval) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("Prio_Logic_multiprotocol timer expire");
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("CallBack Reconfiguring the POLL to Default");
  clear_multiprotocol();
  multiprotocol_timer.set(300, multiprotocol_clear_flag);
}

void clear_multiprotocol() {
  int thread_ret;

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("clear_multiprotocol");
  multiprotocol_detected = 0;
  pthread_attr_t attr;
  pthread_attr_init(&attr);
  pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
  thread_ret = pthread_create(&multiprotocol_thread, &attr,
                              p2p_prio_logic_multiprotocol, NULL);
  if (thread_ret != 0)
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("unable to create the thread");
  pthread_attr_destroy(&attr);
}

void multiprotocol_clear_flag(union sigval) {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("multiprotocol_clear_flag");
  multiprotocol_flag = 1;
}
#endif

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
  static uint8_t prev_more_val = 0x00;
  uint8_t cur_more_val = 0x00;

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Connection Event = %u", __func__, connEvent);

  switch (connEvent) {
    case NFA_POLL_ENABLED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_POLL_ENABLED_EVT: status = 0x%0X", __func__,
                          eventData->status);
      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_POLL_DISABLED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_POLL_DISABLED_EVT: status = 0x%0X", __func__,
                          eventData->status);
      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_RF_DISCOVERY_STARTED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_RF_DISCOVERY_STARTED_EVT: status = 0x%0X",
                          __func__, eventData->status);
      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_RF_DISCOVERY_STOPPED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_RF_DISCOVERY_STOPPED_EVT: status = 0x%0X",
                          __func__, eventData->status);
      notifyPollingEventwhileNfcOff();
      if (getReconnectState() == true) {
        eventData->deactivated.type = NFA_DEACTIVATE_TYPE_SLEEP;
        NfcTag::getInstance().setDeactivationState(eventData->deactivated);
        if (gIsTagDeactivating) {
          NfcTag::getInstance().setActive(false);
          nativeNfcTag_doDeactivateStatus(0);
        }
      }
      /* sNfaEnableDisablePollingEvent shall be notified in all cases
       * otherwise RF stop activity will block wait */
      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    } break;

    case NFA_DISC_RESULT_EVT: {
      status = eventData->disc_result.status;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_DISC_RESULT_EVT: status = 0x%0X", __func__, status);
      cur_more_val = eventData->disc_result.discovery_ntf.more;
      if ((cur_more_val == 0x01) && (prev_more_val != 0x02)) {
        LOG(ERROR) << StringPrintf("%s: NFA_DISC_RESULT_EVT: Failed", __func__);
        status = NFA_STATUS_FAILED;
      } else {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_DISC_RESULT_EVT: Success", __func__);
        status = NFA_STATUS_OK;
        prev_more_val = cur_more_val;
      }
#if (NXP_EXTNS == TRUE)
#if (NFC_NXP_NON_STD_CARD == TRUE)
      if (gIsSelectingRfInterface) {
        LOG(ERROR) << StringPrintf(
            "%s: NFA_DISC_RESULT_EVT: reSelect function didn't save the "
            "modification",
            __func__);
        if (cur_more_val == 0x00) {
          LOG(ERROR) << StringPrintf(
              "%s: NFA_DISC_RESULT_EVT: error, select any one tag", __func__);
          multiprotocol_flag = 0;
        }
      }
#endif
#endif
      if (status != NFA_STATUS_OK) {
        LOG(ERROR) << StringPrintf(
            "%s: NFA_DISC_RESULT_EVT: error, status = 0x%0X", __func__, status);
        NfcTag::getInstance().mNumDiscNtf = 0;
      } else {
        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
        handleRfDiscoveryEvent(&eventData->disc_result.discovery_ntf);
      }
    } break;

    case NFA_SELECT_RESULT_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_SELECT_RESULT_EVT: status = 0x%0X, gIsSelectingRfInterface "
          "= %d, sIsDisabling = %d",
          __func__, eventData->status, gIsSelectingRfInterface, sIsDisabling);

      if (sIsDisabling) break;

      if (eventData->status != NFA_STATUS_OK) {
        if (gIsSelectingRfInterface) {
#if (NXP_EXTNS == TRUE)
#if (NFC_NXP_NON_STD_CARD == TRUE)
          nativeNfcTag_cacheNonNciCardDetection();
#endif
#endif
          nativeNfcTag_doConnectStatus(false);
        }
#if (NXP_EXTNS == TRUE)
        NfcTag::getInstance().selectCompleteStatus(false);
        NfcTag::getInstance().mNumDiscNtf = 0x00;
#endif
        NfcTag::getInstance().mTechListIndex = 0;
        LOG(ERROR) << StringPrintf(
            "%s: NFA_SELECT_RESULT_EVT: error, status = 0x%0X", __func__,
            eventData->status);
        NFA_Deactivate(false);
      }
#if (NXP_EXTNS == TRUE)
      else if (sReaderModeEnabled &&
               (gFelicaReaderState == STATE_DEACTIVATED_TO_SLEEP)) {
        SyncEventGuard g(sRespCbEvent);
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: Sending Sem Post for Select Event", __func__);
        sRespCbEvent.notifyOne();
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_SELECT_RESULT_EVT: Frame RF Interface Selected", __func__);
        gFelicaReaderState = STATE_FRAMERF_INTF_SELECTED;
      }
#endif
    } break;

    case NFA_DEACTIVATE_FAIL_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NFA_DEACTIVATE_FAIL_EVT: status = 0x%0X",
                          __func__, eventData->status);
      {
        SyncEventGuard guard(gDeactivatedEvent);
        gActivated = false;
        gDeactivatedEvent.notifyOne();
      }
      {
        SyncEventGuard guard(sNfaEnableDisablePollingEvent);
        sNfaEnableDisablePollingEvent.notifyOne();
      }
    } break;

    case NFA_ACTIVATED_EVT: {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFA_ACTIVATED_EVT: gIsSelectingRfInterface=%d, sIsDisabling=%d",
          __func__, gIsSelectingRfInterface, sIsDisabling);
#if (NXP_EXTNS == TRUE)
      if (gSelfTestType != NFC_CMD_TYPE_TYPE_NONE) {
        activatedNtf_Cb();
        break;
      }
      rfActivation = true;

      checkforTranscation(NFA_ACTIVATED_EVT, (void*)eventData);

      NfcTag::getInstance().selectCompleteStatus(true);

      /***P2P-Prio Logic for Multiprotocol***/
      if ((eventData->activated.activate_ntf.protocol ==
           NFA_PROTOCOL_NFC_DEP) &&
          (multiprotocol_detected == 1)) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("Prio_Logic_multiprotocol stop timer");
        multiprotocol_timer.kill();
      }

      if ((eventData->activated.activate_ntf.protocol == NFA_PROTOCOL_T3T) &&
          (multiprotocol_detected == 1)) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("Prio_Logic_multiprotocol stop timer");
        multiprotocol_timer.kill();
        clear_multiprotocol();
      }
#endif
      if (nfcFL.nfcNxpEse) {
        if (nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
          /*
           * Handle Reader over SWP START_READER_EVENT
           * */
          if (eventData->activated.activate_ntf.intf_param.type ==
                  NCI_INTERFACE_UICC_DIRECT_STAT ||
              eventData->activated.activate_ntf.intf_param.type ==
                  NCI_INTERFACE_ESE_DIRECT_STAT) {
            MposManager::getInstance().setEtsiReaederState(
                STATE_SE_RDR_MODE_ACTIVATED);
            MposManager::getInstance().notifyEEReaderEvent(
                ETSI_READER_ACTIVATED);
            break;
          }
        }
      }
      if ((eventData->activated.activate_ntf.protocol !=
           NFA_PROTOCOL_NFC_DEP) &&
          (!isListenMode(eventData->activated))) {
        nativeNfcTag_setRfInterface(
            (tNFA_INTF_TYPE)eventData->activated.activate_ntf.intf_param.type);
      }

      if (EXTNS_GetConnectFlag() == true) {
        NfcTag::getInstance().setActivationState();
        nativeNfcTag_doConnectStatus(true);
        break;
      }

      NfcTag::getInstance().setActive(true);

      if (sIsDisabling || !sIsNfaEnabled) break;

      gActivated = true;

      NfcTag::getInstance().setActivationState();

      if (gIsSelectingRfInterface) {
        nativeNfcTag_doConnectStatus(true);
        if (NfcTag::getInstance().isCashBeeActivated() == true ||
            NfcTag::getInstance().isEzLinkTagActivated() == true) {
          NfcTag::getInstance().connectionEventHandler(NFA_ACTIVATED_UPDATE_EVT,
                                                       eventData);
        }
        break;
      }

      nativeNfcTag_resetPresenceCheck();

      if (isPeerToPeer(eventData->activated)) {
        if (sReaderModeEnabled) {
#if (NXP_EXTNS == TRUE)
          /* If last transaction is complete or prev state is idle
           * then proceed to next state*/
          if (isActivatedTypeF(eventData->activated) &&
              (sTechMask & NFA_TECHNOLOGY_MASK_F) &&
              ((gFelicaReaderState == STATE_IDLE) ||
               (gFelicaReaderState == STATE_FRAMERF_INTF_SELECTED))) {
            DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf("%s: Activating Reader Mode in P2P ", __func__);
            gFelicaReaderState = STATE_NFCDEP_ACTIVATED_NFCDEP_INTF;
            switchP2PToT3TRead(eventData->activated.activate_ntf.rf_disc_id);
          } else {
            DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf("%s: Invalid FelicaReaderState : %d  ",
                                __func__, gFelicaReaderState);
            gFelicaReaderState = STATE_IDLE;
#endif
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                "%s: Ignoring P2P target in reader mode.", __func__);
            NFA_Deactivate(false);
#if (NXP_EXTNS == TRUE)
          }
#endif
          break;
        }
        sP2pActive = true;
#if (NXP_EXTNS == FALSE)
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

#endif
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
              "%s: NFA_ACTIVATED_EVT: P2P is activated", __func__);
          if ((nfcFL.nfcNxpEse &&
               nfcFL.eseFL._NFCC_ESE_UICC_CONCURRENT_ACCESS_PROTECTION) &&
              (SecureElement::getInstance().mIsWiredModeOpen &&
               SecureElement::getInstance().mPassiveListenEnabled == true)) {
            SecureElement::getInstance().mPassiveListenTimer.kill();
          }

          /* For Secure Element, consider the field to be on while P2P is active
           */
          SecureElement::getInstance().notifyRfFieldEvent(true);
#if (NXP_EXTNS == TRUE)
          if (nfcFL.nfcNxpEse && (nfcFL.eseFL._ESE_DUAL_MODE_PRIO_SCHEME !=
                                  nfcFL.eseFL._ESE_WIRED_MODE_RESUME)) {
            SecureElement::getInstance().setDwpTranseiveState(
                false, NFCC_ACTIVATED_NTF);
          }
#endif
        } else if (pn544InteropIsBusy() == false) {
#if (NXP_EXTNS == TRUE && NFC_NXP_NON_STD_CARD == TRUE)
          nativeNfcTag_handleNonNciMultiCardDetection(connEvent, eventData);
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
              "%s: scoreGenericNtf = 0x%x", __func__, scoreGenericNtf);
          if (scoreGenericNtf == true) {
            if ((eventData->activated.activate_ntf.intf_param.type ==
                 NFC_INTERFACE_ISO_DEP) &&
                (eventData->activated.activate_ntf.protocol ==
                 NFC_PROTOCOL_ISO_DEP)) {
              nativeNfcTag_handleNonNciCardDetection(eventData);
            }
            scoreGenericNtf = false;
          }
#else
        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);

        if (NfcTag::getInstance().mNumDiscNtf) {
          NFA_Deactivate(true);
        }
#endif
          /* We know it is not activating for P2P.  If it activated in
           * listen mode then it is likely for an SE transaction.
           * Send the RF Event */
          if (isListenMode(eventData->activated)) {
            sSeRfActive = true;
            SecureElement::getInstance().notifyListenModeState(true);
#if (NXP_EXTNS == TRUE)
            if ((nfcFL.nfcNxpEse &&
                 nfcFL.eseFL._NFCC_ESE_UICC_CONCURRENT_ACCESS_PROTECTION) &&
                (SecureElement::getInstance().mIsWiredModeOpen &&
                 SecureElement::getInstance().mPassiveListenEnabled == true)) {
              SecureElement::getInstance().mPassiveListenTimer.kill();
            }
#endif
          }
        }
      }
      break;

      case NFA_DEACTIVATED_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_DEACTIVATED_EVT: Type=%u, gIsTagDeactivating=%d", __func__,
            eventData->deactivated.type, gIsTagDeactivating);
#if (NXP_EXTNS == TRUE)
        rfActivation = false;
        if ((nfcFL.chipType == pn547C2) &&
            (eventData->deactivated.type == NFA_DEACTIVATE_TYPE_IDLE)) {
          checkforTranscation(NFA_DEACTIVATED_EVT, (void*)eventData);
        }
#if (NFC_NXP_NON_STD_CARD == TRUE)
        if (checkCmdSent == 1 && eventData->deactivated.type == 0) {
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
              "%s: NFA_DEACTIVATED_EVT: Setting check flag  to one", __func__);
          checkTagNtf = 1;
        }
#endif
#endif

        notifyPollingEventwhileNfcOff();

        if (true == getReconnectState()) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("Reconnect in progress : Do nothing");
          break;
        }

#if (NXP_EXTNS == TRUE)
        /* P2P-priority logic for multiprotocol tags */
        if ((multiprotocol_detected == 1) && (sP2pActive == 1)) {
          NfcTag::getInstance().mNumDiscNtf = 0;
          clear_multiprotocol();
          multiprotocol_flag = 1;
        }
        if (gIsWaiting4Deact2SleepNtf) {
          if (eventData->deactivated.type == NFA_DEACTIVATE_TYPE_IDLE) {
            gGotDeact2IdleNtf = true;
          } else if (eventData->deactivated.type == NFA_DEACTIVATE_TYPE_SLEEP) {
            gIsWaiting4Deact2SleepNtf = false;
          }
        }
#endif
        NfcTag::getInstance().setDeactivationState(eventData->deactivated);

        if (NfcTag::getInstance().mNumDiscNtf) {
          NfcTag::getInstance().mNumDiscNtf--;
          NfcTag::getInstance().selectNextTag();
        }

        if (eventData->deactivated.type != NFA_DEACTIVATE_TYPE_SLEEP) {
          {
            SyncEventGuard guard(gDeactivatedEvent);
            gActivated = false;
            gDeactivatedEvent.notifyOne();
          }
#if (NXP_EXTNS == TRUE)
          if ((nfcFL.nfcNxpEse &&
               nfcFL.eseFL._NFCC_ESE_UICC_CONCURRENT_ACCESS_PROTECTION) &&
              (SecureElement::getInstance().mIsWiredModeOpen &&
               SecureElement::getInstance().mPassiveListenEnabled)) {
            SecureElement::getInstance().startThread(0x00);
          }
#endif
          NfcTag::getInstance().mNumDiscNtf = 0;
          NfcTag::getInstance().mTechListIndex = 0;
          nativeNfcTag_resetPresenceCheck();
          NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
          nativeNfcTag_abortWaits();
          NfcTag::getInstance().abort();
          NfcTag::getInstance().mIsMultiProtocolTag = false;
        } else if (gIsTagDeactivating) {
          NfcTag::getInstance().setActive(false);
          nativeNfcTag_doDeactivateStatus(0);
        } else if (EXTNS_GetDeactivateFlag() == true) {
          NfcTag::getInstance().setActive(false);
          nativeNfcTag_doDeactivateStatus(0);
        }

        /* If RF is activated for what we think is a Secure Element transaction
         * and it is deactivated to either IDLE or DISCOVERY mode, notify wait
         * event */
        if ((eventData->deactivated.type == NFA_DEACTIVATE_TYPE_IDLE) ||
            (eventData->deactivated.type == NFA_DEACTIVATE_TYPE_DISCOVERY)) {
#if (NXP_EXTNS == TRUE)
          if (nfcFL.nfccFL._NFCEE_REMOVED_NTF_RECOVERY &&
              RoutingManager::getInstance().is_ee_recovery_ongoing()) {
            recovery = false;
            SyncEventGuard guard(
                SecureElement::getInstance().mEEdatapacketEvent);
            SecureElement::getInstance().mEEdatapacketEvent.notifyOne();
          }
#endif
          if (sSeRfActive) {
            sSeRfActive = false;
            if (!sIsDisabling && sIsNfaEnabled)
              SecureElement::getInstance().notifyListenModeState(false);
          } else if (sP2pActive) {
            sP2pActive = false;
#if (NXP_EXTNS == FALSE)
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
#endif
            SecureElement::getInstance().notifyRfFieldEvent(false);
            DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf("%s: NFA_DEACTIVATED_EVT: is p2p", __func__);
          }
        }
#if (NXP_EXTNS == TRUE)
        if (sReaderModeEnabled &&
            (eventData->deactivated.type == NFA_DEACTIVATE_TYPE_SLEEP)) {
          if (gFelicaReaderState == STATE_NFCDEP_ACTIVATED_NFCDEP_INTF) {
            SyncEventGuard g(sRespCbEvent);
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                "%s: Sending Sem Post for Deactivated", __func__);
            sRespCbEvent.notifyOne();
            DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf("Switching to T3T\n");
            gFelicaReaderState = STATE_DEACTIVATED_TO_SLEEP;
          } else {
            DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf("%s: FelicaReaderState Invalid", __func__);
            gFelicaReaderState = STATE_IDLE;
          }
        }
#endif
      } break;

      case NFA_TLV_DETECT_EVT: {
        status = eventData->tlv_detect.status;
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_TLV_DETECT_EVT status = 0x%0X, protocol = %d, num_tlvs = "
            "%d, num_bytes = %d",
            __func__, status, eventData->tlv_detect.protocol,
            eventData->tlv_detect.num_tlvs, eventData->tlv_detect.num_bytes);
        if (status != NFA_STATUS_OK) {
          LOG(ERROR) << StringPrintf(
              "%s: NFA_TLV_DETECT_EVT error: status = 0x%0X", __func__, status);
        }
      } break;

      case NFA_NDEF_DETECT_EVT: {
        /* NDEF Detection procedure is completed,
         * if status is failure, it means the tag does not contain any or valid
         * NDEF data
         * pass the failure status to the NFC Service */
        status = eventData->ndef_detect.status;
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_NDEF_DETECT_EVT status = 0x%0X, protocol = %u, "
            "max_size = %u, cur_size = %u, flags = 0x%X",
            __func__, status, eventData->ndef_detect.protocol,
            eventData->ndef_detect.max_size, eventData->ndef_detect.cur_size,
            eventData->ndef_detect.flags);
        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
        nativeNfcTag_doCheckNdefResult(status, eventData->ndef_detect.max_size,
                                       eventData->ndef_detect.cur_size,
                                       eventData->ndef_detect.flags);
      } break;

      case NFA_DATA_EVT: {
        /* Data message received (for non-NDEF reads) */
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_DATA_EVT: status = 0x%X, len = %d",
                            __func__, eventData->status, eventData->data.len);
        nativeNfcTag_doTransceiveStatus(
            eventData->status, eventData->data.p_data, eventData->data.len);
      } break;

      case NFA_RW_INTF_ERROR_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_RW_INTF_ERROR_EVT", __func__);
        nativeNfcTag_notifyRfTimeout();
        nativeNfcTag_doReadCompleted(NFA_STATUS_TIMEOUT);
      } break;

      case NFA_SELECT_CPLT_EVT: {
        status = eventData->status;
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_SELECT_CPLT_EVT: status = 0x%0X", __func__, status);
        if (status != NFA_STATUS_OK) {
          LOG(ERROR) << StringPrintf(
              "%s: NFA_SELECT_CPLT_EVT error: status = 0x%0X", __func__,
              status);
        }
      } break;

      case NFA_READ_CPLT_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_READ_CPLT_EVT: status = 0x%0X", __func__,
                            eventData->status);
        nativeNfcTag_doReadCompleted(eventData->status);
        NfcTag::getInstance().connectionEventHandler(connEvent, eventData);
      } break;

      case NFA_WRITE_CPLT_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_WRITE_CPLT_EVT: status = 0x%0X", __func__,
                            eventData->status);
        nativeNfcTag_doWriteStatus(eventData->status == NFA_STATUS_OK);
      } break;

      case NFA_SET_TAG_RO_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_SET_TAG_RO_EVT: status = 0x%0X", __func__,
                            eventData->status);
        nativeNfcTag_doMakeReadonlyResult(eventData->status);
      } break;

      case NFA_CE_NDEF_WRITE_START_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_CE_NDEF_WRITE_START_EVT: status: 0x%0X",
                            __func__, eventData->status);
        if (eventData->status != NFA_STATUS_OK)
          LOG(ERROR) << StringPrintf(
              "%s: NFA_CE_NDEF_WRITE_START_EVT error: status = 0x%0X", __func__,
              eventData->status);
      } break;

      case NFA_CE_NDEF_WRITE_CPLT_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_CE_NDEF_WRITE_CPLT_EVT: len = %u",
                            __func__, eventData->ndef_write_cplt.len);
      } break;

      case NFA_LLCP_ACTIVATED_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_LLCP_ACTIVATED_EVT: is_initiator: %d  remote_wks: %d, "
            "remote_lsc: %d, remote_link_miu: %d, local_link_miu: %d",
            __func__, eventData->llcp_activated.is_initiator,
            eventData->llcp_activated.remote_wks,
            eventData->llcp_activated.remote_lsc,
            eventData->llcp_activated.remote_link_miu,
            eventData->llcp_activated.local_link_miu);
        PeerToPeer::getInstance().llcpActivatedHandler(
            getNative(0, 0), eventData->llcp_activated);
      } break;

      case NFA_LLCP_DEACTIVATED_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_LLCP_DEACTIVATED_EVT", __func__);
        PeerToPeer::getInstance().llcpDeactivatedHandler(
            getNative(0, 0), eventData->llcp_deactivated);
      } break;

      case NFA_LLCP_FIRST_PACKET_RECEIVED_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_LLCP_FIRST_PACKET_RECEIVED_EVT", __func__);
        PeerToPeer::getInstance().llcpFirstPacketHandler(getNative(0, 0));
      } break;

      case NFA_PRESENCE_CHECK_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_PRESENCE_CHECK_EVT", __func__);
        nativeNfcTag_doPresenceCheckResult(eventData->status);
      } break;

      case NFA_FORMAT_CPLT_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_FORMAT_CPLT_EVT: status = 0x%0X", __func__,
                            eventData->status);
        nativeNfcTag_formatStatus(eventData->status == NFA_STATUS_OK);
      } break;

      case NFA_I93_CMD_CPLT_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_I93_CMD_CPLT_EVT: status = 0x%0X",
                            __func__, eventData->status);
      } break;

      case NFA_CE_UICC_LISTEN_CONFIGURED_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_CE_UICC_LISTEN_CONFIGURED_EVT : status = 0x%0X", __func__,
            eventData->status);
        SecureElement::getInstance().connectionEventHandler(connEvent,
                                                            eventData);
      } break;

      case NFA_CE_ESE_LISTEN_CONFIGURED_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_CE_ESE_LISTEN_CONFIGURED_EVT : status = 0x%0X", __func__,
            eventData->status);
        SecureElement::getInstance().connectionEventHandler(connEvent,
                                                            eventData);
      } break;

      case NFA_SET_P2P_LISTEN_TECH_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_SET_P2P_LISTEN_TECH_EVT", __func__);
        PeerToPeer::getInstance().connectionEventHandler(connEvent, eventData);
      } break;

      case NFA_CE_LOCAL_TAG_CONFIGURED_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_CE_LOCAL_TAG_CONFIGURED_EVT", __func__);
      } break;
#if (NXP_EXTNS == TRUE)
      case NFA_RECOVERY_EVT: {
        if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
              "%s: NFA_RECOVERY_EVT: Discovery Started in lower layer:Updating "
              "status in JNI",
              __func__);
          if (MposManager::getInstance().getEtsiReaederState() ==
              STATE_SE_RDR_MODE_STOP_IN_PROGRESS) {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                "%s: Reset the ETSI Reader State to STATE_SE_RDR_MODE_STOPPED",
                __func__);
            MposManager::getInstance().setEtsiReaederState(
                STATE_SE_RDR_MODE_STOPPED);
          }
        }
      } break;
#endif
#if (NXP_EXTNS == TRUE)
      case NFA_PASSIVE_LISTEN_DISABLED_EVT: {
        if (nfcFL.nfcNxpEse &&
            nfcFL.eseFL._NFCC_ESE_UICC_CONCURRENT_ACCESS_PROTECTION) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: NFA_PASSIVE_LISTEN_DISABLED_EVT", __func__);
          SyncEventGuard g(SecureElement::getInstance().mPassiveListenEvt);
          SecureElement::getInstance().mPassiveListenEvt.notifyOne();
        }
      } break;

      case NFA_LISTEN_ENABLED_EVT: {
        if (nfcFL.nfcNxpEse &&
            nfcFL.eseFL._NFCC_ESE_UICC_CONCURRENT_ACCESS_PROTECTION) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: NFA_LISTEN_ENABLED_EVT", __func__);
          SyncEventGuard g(SecureElement::getInstance().mPassiveListenEvt);
          SecureElement::getInstance().mPassiveListenEvt.notifyOne();
        }
      } break;
#endif
      default:
        LOG(ERROR) << StringPrintf("%s: unknown event ????", __func__);
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
  static jboolean nfcManager_initNativeStruc(JNIEnv * e, jobject o) {
    initializeGlobalDebugEnabledFlag();
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
#if (NXP_EXTNS == TRUE)
    nfc_jni_native_data* nat =
        (nfc_jni_native_data*)malloc(sizeof(struct nfc_jni_native_data));
/*JCOP OS Download protection case to be handled*/
#else
  nfc_jni_native_data* nat =
      (nfc_jni_native_data*)malloc(sizeof(struct nfc_jni_native_data));
#endif

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

    MposManager::initMposNativeStruct(e, o);
    /* Initialize native cached references */
    gCachedNfcManagerNotifyNdefMessageListeners =
        e->GetMethodID(cls.get(), "notifyNdefMessageListeners",
                       "(Lcom/android/nfc/dhimpl/NativeNfcTag;)V");
    gCachedNfcManagerNotifyConnectivityListeners =
        e->GetMethodID(cls.get(), "notifyConnectivityListeners", "(I)V");
    gCachedNfcManagerNotifyEmvcoMultiCardDetectedListeners = e->GetMethodID(
        cls.get(), "notifyEmvcoMultiCardDetectedListeners", "()V");
    gCachedNfcManagerNotifyLlcpLinkActivation =
        e->GetMethodID(cls.get(), "notifyLlcpLinkActivation",
                       "(Lcom/android/nfc/dhimpl/NativeP2pDevice;)V");
    gCachedNfcManagerNotifyLlcpLinkDeactivated =
        e->GetMethodID(cls.get(), "notifyLlcpLinkDeactivated",
                       "(Lcom/android/nfc/dhimpl/NativeP2pDevice;)V");
    gCachedNfcManagerNotifyLlcpFirstPacketReceived =
        e->GetMethodID(cls.get(), "notifyLlcpLinkFirstPacketReceived",
                       "(Lcom/android/nfc/dhimpl/NativeP2pDevice;)V");
    sCachedNfcManagerNotifyTargetDeselected =
        e->GetMethodID(cls.get(), "notifyTargetDeselected", "()V");
    gCachedNfcManagerNotifySeFieldActivated =
        e->GetMethodID(cls.get(), "notifySeFieldActivated", "()V");
    gCachedNfcManagerNotifySeFieldDeactivated =
        e->GetMethodID(cls.get(), "notifySeFieldDeactivated", "()V");
    gCachedNfcManagerNotifySeListenActivated =
        e->GetMethodID(cls.get(), "notifySeListenActivated", "()V");
    gCachedNfcManagerNotifySeListenDeactivated =
        e->GetMethodID(cls.get(), "notifySeListenDeactivated", "()V");

    gCachedNfcManagerNotifyHostEmuActivated =
        e->GetMethodID(cls.get(), "notifyHostEmuActivated", "(I)V");

    gCachedNfcManagerNotifyAidRoutingTableFull =
        e->GetMethodID(cls.get(), "notifyAidRoutingTableFull", "()V");

    gCachedNfcManagerNotifyHostEmuData =
        e->GetMethodID(cls.get(), "notifyHostEmuData", "(I[B)V");

    gCachedNfcManagerNotifyHostEmuDeactivated =
        e->GetMethodID(cls.get(), "notifyHostEmuDeactivated", "(I)V");

    gCachedNfcManagerNotifyRfFieldActivated =
        e->GetMethodID(cls.get(), "notifyRfFieldActivated", "()V");
    gCachedNfcManagerNotifyRfFieldDeactivated =
        e->GetMethodID(cls.get(), "notifyRfFieldDeactivated", "()V");

    sCachedNfcManagerNotifySeApduReceived =
        e->GetMethodID(cls.get(), "notifySeApduReceived", "([B)V");

    sCachedNfcManagerNotifySeMifareAccess =
        e->GetMethodID(cls.get(), "notifySeMifareAccess", "([B)V");

    sCachedNfcManagerNotifySeEmvCardRemoval =
        e->GetMethodID(cls.get(), "notifySeEmvCardRemoval", "()V");

    gCachedNfcManagerNotifyTransactionListeners = e->GetMethodID(
        cls.get(), "notifyTransactionListeners", "([B[BLjava/lang/String;)V");

#if (NXP_EXTNS == TRUE)
    gCachedNfcManagerNotifyReRoutingEntry =
        e->GetMethodID(cls.get(), "notifyReRoutingEntry", "()V");

    gCachedNfcManagerNotifyUiccStatusEvent =
        e->GetMethodID(cls.get(), "notifyUiccStatusEvent", "(I)V");

#if (NXP_NFCC_HCE_F == TRUE)
    gCachedNfcManagerNotifyT3tConfigure =
        e->GetMethodID(cls.get(), "notifyT3tConfigure", "()V");
#endif
    gCachedNfcManagerNotifyJcosDownloadInProgress =
        e->GetMethodID(cls.get(), "notifyJcosDownloadInProgress", "(I)V");

    gCachedNfcManagerNotifyFwDwnldRequested =
        e->GetMethodID(cls.get(), "notifyFwDwnldRequested", "()V");

#endif
    if (nfc_jni_cache_object(e, gNativeNfcTagClassName,
                             &(nat->cached_NfcTag)) == -1) {
      LOG(ERROR) << StringPrintf("%s: fail cache NativeNfcTag", __func__);
      free(nat);
      return JNI_FALSE;
    }

    if (nfc_jni_cache_object(e, gNativeP2pDeviceClassName,
                             &(nat->cached_P2pDevice)) == -1) {
      LOG(ERROR) << StringPrintf("%s: fail cache NativeP2pDevice", __func__);
      free(nat);
      return JNI_FALSE;
    }

    gNativeData = getNative(e, o);
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
                                   tNFA_DM_CBACK_DATA * eventData) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: enter; event=0x%X", __func__, dmEvent);

    switch (dmEvent) {
      case NFA_DM_ENABLE_EVT: /* Result of NFA_Enable */
      {
        SyncEventGuard guard(sNfaEnableEvent);
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_DM_ENABLE_EVT; status=0x%X", __func__, eventData->status);
        sIsNfaEnabled = eventData->status == NFA_STATUS_OK;
#if (NXP_EXTNS == TRUE)
        sEnableStatus = eventData->status;
#endif
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
          HciRFParams::getInstance().connectionEventHandler(dmEvent, eventData);
          SyncEventGuard guard(sNfaGetConfigEvent);
          if (eventData->status == NFA_STATUS_OK &&
              eventData->get_config.tlv_size <= sizeof(sConfig)) {
            sCurrentConfigLen = eventData->get_config.tlv_size;
            memcpy(sConfig, eventData->get_config.param_tlvs,
                   eventData->get_config.tlv_size);

#if (NXP_EXTNS == TRUE)
            if (sCheckNfceeFlag) checkforNfceeBuffer();

            if (eSEPhyIntfInResponsive(eventData)) recoverEseConnectivity();
#endif
          } else {
            LOG(ERROR) << StringPrintf("%s: NFA_DM_GET_CONFIG failed",
                                       __func__);
            sCurrentConfigLen = 0;
          }
          sNfaGetConfigEvent.notifyOne();
        }
        break;

      case NFA_DM_RF_FIELD_EVT:
        checkforTranscation(NFA_TRANS_DM_RF_FIELD_EVT, (void*)eventData);
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: NFA_DM_RF_FIELD_EVT; status=0x%X; field status=%u", __func__,
            eventData->rf_field.status, eventData->rf_field.rf_field_status);
        if (sIsDisabling || !sIsNfaEnabled) break;

        if (!sP2pActive && eventData->rf_field.status == NFA_STATUS_OK) {
          SecureElement::getInstance().notifyRfFieldEvent(
              eventData->rf_field.rf_field_status == NFA_DM_RF_FIELD_ON);
          struct nfc_jni_native_data* nat = getNative(NULL, NULL);
          JNIEnv* e = NULL;
          ScopedAttach attach(nat->vm, &e);
          if (e == NULL) {
            LOG(ERROR) << StringPrintf("jni env is null");
            return;
          }
          if (eventData->rf_field.rf_field_status == NFA_DM_RF_FIELD_ON) {
            if (!pTransactionController->transactionAttempt(
                    TRANSACTION_REQUESTOR(RF_FIELD_EVT))) {
              DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                  "%s: RF field on evnt Not allowing to set", __FUNCTION__);
            }
            sRfFieldOff = false;
            e->CallVoidMethod(nat->manager,
                              android::gCachedNfcManagerNotifyRfFieldActivated);
          } else {
            /*In case of if Field On is not received before activation, consider
             NFA_ACTIVATED_EVENT for
             locking the transaction and use Field Off received while removing
             the reader from proximity to end the lock*/
            if (pTransactionController->getCurTransactionRequestor() ==
                TRANSACTION_REQUESTOR(NFA_ACTIVATED_EVENT)) {
              pTransactionController->transactionEnd(
                  TRANSACTION_REQUESTOR(NFA_ACTIVATED_EVENT));
            } else {
              pTransactionController->transactionEnd(
                  TRANSACTION_REQUESTOR(RF_FIELD_EVT));
            }
            sRfFieldOff = true;
            e->CallVoidMethod(
                nat->manager,
                android::gCachedNfcManagerNotifyRfFieldDeactivated);
          }
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
        if (nfcFL.eseFL._JCOP_WA_ENABLE) {
          NFA_HciW4eSETransaction_Complete(Wait);
        }
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
          NFA_Disable(false);
          sIsDisabling = true;
        } else {
          sIsNfaEnabled = false;
          sIsDisabling = false;
        }
        PowerSwitch::getInstance().initialize(PowerSwitch::UNKNOWN_LEVEL);
#if (NXP_EXTNS == TRUE)
        if (eventData->status == NFA_STATUS_FAILED) {
          LOG(ERROR) << StringPrintf("%s: Disabling NFC service", __func__);
        } else {
#endif
          LOG(ERROR) << StringPrintf("%s: crash NFC service", __func__);
          //////////////////////////////////////////////
          // crash the NFC service process so it can restart automatically
          abort();
//////////////////////////////////////////////
#if (NXP_EXTNS == TRUE)
        }
#endif
      } break;

      case NFA_DM_PWR_MODE_CHANGE_EVT:
        PowerSwitch::getInstance().deviceManagementCallback(dmEvent, eventData);
        break;

#if (NXP_EXTNS == TRUE)
      case NFA_DM_SET_ROUTE_CONFIG_REVT:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: NFA_DM_SET_ROUTE_CONFIG_REVT; status=0x%X",
                            __func__, eventData->status);
        if (eventData->status != NFA_STATUS_OK) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("AID Routing table configuration Failed!!!");
        } else {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("AID Routing Table configured.");
        }
        RoutingManager::getInstance().mLmrtEvent.notifyOne();
        break;

      case NFA_DM_GET_ROUTE_CONFIG_REVT: {
        RoutingManager::getInstance().processGetRoutingRsp(eventData,
                                                           sRoutingBuff);
        if (eventData->status == NFA_STATUS_OK) {
          SyncEventGuard guard(sNfaGetRoutingEvent);
          sNfaGetRoutingEvent.notifyOne();
        }
        break;
      }
      case NFA_DM_EE_HCI_DISABLE: {
        if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("NFA_DM_EE_HCI_DISABLE wait releasing");
          SyncEventGuard guard(sNfceeHciCbDisableEvent);
          sNfceeHciCbDisableEvent.notifyOne();
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("NFA_DM_EE_HCI_DISABLE wait released");
        }
        break;
      }
      case NFA_DM_EE_HCI_ENABLE: {
        if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("NFA_DM_EE_HCI_ENABLE wait releasing");
          SyncEventGuard guard(sNfceeHciCbEnableEvent);
          sNfceeHciCbEnableEvent.notifyOne();
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("NFA_DM_EE_HCI_ENABLE wait released");
        }
        break;
      }
      case NFA_DM_SET_TRANSIT_CONFIG_EVT: {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("NFA_DM_SET_TRANSIT_CONFIG EVT cback received");
        SyncEventGuard guard(sNfaTransitConfigEvent);
        sNfaTransitConfigEvent.notifyOne();
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
      case NFA_DM_EMVCO_PCD_COLLISION_EVT:
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "STATUS_EMVCO_PCD_COLLISION - Multiple card detected");
        SecureElement::getInstance().notifyEmvcoMultiCardDetectedListeners();
        break;

      default:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: unhandled event", __func__);
        break;
    }
  }

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
  static jboolean nfcManager_sendRawFrame(JNIEnv * e, jobject,
                                          jbyteArray data) {
    size_t bufLen = 0x00;
    uint8_t* buf = NULL;
    tNFA_STATUS status;
    if (data != NULL) {
      ScopedByteArrayRO bytes(e, data);
      buf = const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
      bufLen = bytes.size();

#if (NXP_EXTNS == TRUE)
      if (nfcFL.nfccFL._NXP_NFCC_EMPTY_DATA_PACKET) {
        RoutingManager::getInstance().mNfcFRspTimer.kill();
        if (bufLen == 0) {
          gIsEmptyRspSentByHceFApk = true;
        }
      }
#endif
      DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("nfcManager_sendRawFrame(): bufLen:%lu", bufLen);
      status = NFA_SendRawFrame(buf, bufLen, 0);
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("data is NULL ..Returning..");
      status = NFA_STATUS_FAILED;
    }
    return (status == NFA_STATUS_OK);
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_routeAid
  **
  ** Description:     Route an AID to an EE
  **                  e: JVM environment.
  **                  aid: aid to be added to routing table.
  **                  route: aid route location. i.e. DH/eSE/UICC
  **                  power: power state
  **                  aidInfo: prefix or suffix aid.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  static jboolean nfcManager_routeAid(JNIEnv * e, jobject, jbyteArray aid,
                                      jint route, jint power, jint aidInfo) {
    ScopedByteArrayRO bytes(e, aid);
    uint8_t* buf =
        const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
    size_t bufLen = bytes.size();

#if (NXP_EXTNS == TRUE)
    if ((nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) &&
        (route == 2 || route == 4)) {  // UICC or UICC2 HANDLE
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "sCurrentSelectedUICCSlot:  %d", sCurrentSelectedUICCSlot);
      route = (sCurrentSelectedUICCSlot != 0x02) ? 0x02 : 0x04;
    }
    /*In case of 66T/67T field on is observed as last field event once reader
     is removed from proximity, which will hold the transaction lock
     unnecessarily
     In such cases end the lock as it is not required*/
    if (((nfcFL.chipType == pn548C2) || (nfcFL.chipType == pn551)) &&
        pTransactionController->getCurTransactionRequestor() ==
            TRANSACTION_REQUESTOR(RF_FIELD_EVT)) {
      pTransactionController->transactionEnd(
          TRANSACTION_REQUESTOR(RF_FIELD_EVT));
    }
    bool result = RoutingManager::getInstance().addAidRouting(
        buf, bufLen, route, power, aidInfo);
#else
  bool result =
      RoutingManager::getInstance().addAidRouting(buf, bufLen, route, aidInfo);

#endif
    return result;
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
  static jboolean nfcManager_unrouteAid(JNIEnv * e, jobject, jbyteArray aid) {
    ScopedByteArrayRO bytes(e, aid);
    uint8_t* buf =
        const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
    size_t bufLen = bytes.size();
    bool result = RoutingManager::getInstance().removeAidRouting(buf, bufLen);
    return result;
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_routeApduPattern
  **
  ** Description:     Route an APDU and APDU mask to an EE
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  static jboolean nfcManager_routeApduPattern(
      JNIEnv * e, jobject, jint route, jint powerState, jbyteArray apduData,
      jbyteArray apduMask) {
    ScopedByteArrayRO bytes(e, apduData);
    uint8_t* apdu =
        const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
    size_t apduLen = bytes.size();
    ScopedByteArrayRO bytes2(e, apduMask);
    uint8_t* mask =
        const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes2[0]));
    size_t maskLen = bytes2.size();
#if (NXP_EXTNS == TRUE)
    if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) {
      if (route == 2 || route == 4) {  // UICC or UICC2 HANDLE
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "sCurrentSelectedUICCSlot:  %d", sCurrentSelectedUICCSlot);
        route = (sCurrentSelectedUICCSlot != 0x02) ? 0x02 : 0x04;
      }
    }
    if (nfcManager_isTransanctionOnGoing(true)) {
      return false;
    }
#endif
    return RoutingManager::getInstance().addApduRouting(route, powerState, apdu,
                                                        apduLen, mask, maskLen);
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_unrouteApduPattern
  **
  ** Description:     Remove a APDU and APDU mask routing
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  static jboolean nfcManager_unrouteApduPattern(JNIEnv * e, jobject,
                                                jbyteArray apduData) {
#if (NXP_EXTNS == TRUE)
    if (nfcManager_isTransanctionOnGoing(true)) {
      return false;
    }
#endif
    ScopedByteArrayRO bytes(e, apduData);
    uint8_t* apdu =
        const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
    size_t apduLen = bytes.size();
    return RoutingManager::getInstance().removeApduRouting(apduLen, apdu);
  }

#if (NXP_EXTNS == TRUE)
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

  static jint nfcManager_nfcSelfTest(JNIEnv * e, jobject o, jint aType) {
    tNFA_STATUS status = NFA_STATUS_FAILED;

    if (!sIsNfaEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NFC does not enabled!! returning...");
      return status;
    }
    gSelfTestType = aType;
    if (sDiscoveryEnabled) {
      startRfDiscovery(false);
    }

    switch (aType) {
      case TEST_TYPE_RF_ON:
        status = performRFTest(true);
        break;
      case TEST_TYPE_RF_OFF:
        status = performRFTest(false);
        break;
      case TEST_TYPE_TRANSAC_A:
        status = performTransacAB(NFC_CMD_TYPE_TRANSAC_A);
        break;
      case TEST_TYPE_TRANSAC_B:
        status = performTransacAB(NFC_CMD_TYPE_TRANSAC_B);
        break;
      default:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("nfcManager_nfcSelfTest Invalid Parameter!!");
        break;
    }
    gSelfTestType = TEST_TYPE_NONE;
    return status;
  }

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

  static jboolean nfcManager_setRoutingEntry(
      JNIEnv*, jobject, jint type, jint value, jint route, jint power) {
    jboolean result = false;

    result = RoutingManager::getInstance().setRoutingEntry(type, value, route,
                                                           power);
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

  static jboolean nfcManager_clearRoutingEntry(JNIEnv*, jobject, jint type) {
    jboolean result = false;

    result = RoutingManager::getInstance().clearRoutingEntry(type);
    return result;
  }
#endif

  /*******************************************************************************
  **
  ** Function:        nfcManager_setDefaultRoute
  **
  ** Description:     Set the default route in routing table
  **                  e: JVM environment.
  **                  o: Java object.
  **
  *******************************************************************************/

  static jboolean nfcManager_setDefaultRoute(
      JNIEnv*, jobject, jint defaultRouteEntry, jint defaultProtoRouteEntry,
      jint defaultTechRouteEntry) {
    jboolean result = false;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s : enter", __func__);
#if (NXP_EXTNS == TRUE)
    if (!pTransactionController->transactionAttempt(
            TRANSACTION_REQUESTOR(setDefaultRoute))) {
      LOG(ERROR) << StringPrintf(
          "%s : Transaction in progress, Store the request", __FUNCTION__);
      set_last_request(RE_ROUTING, NULL);
      return result;
    }
#endif
    if (sRfEnabled) {
      // Stop RF discovery to reconfigure
      startRfDiscovery(false);
    }

#if (NXP_EXTNS == TRUE)
    result = RoutingManager::getInstance().setDefaultRoute(
        defaultRouteEntry, defaultProtoRouteEntry, defaultTechRouteEntry);
    if (result)
      result = RoutingManager::getInstance().commitRouting();
    else
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s : Commit routing failed ", __func__);
    gsRouteUpdated = true;
#else
  result = RoutingManager::getInstance().setDefaultRouting();
#endif

    startRfDiscovery(true);
#if (NXP_EXTNS == TRUE)
    pTransactionController->transactionEnd(
        TRANSACTION_REQUESTOR(setDefaultRoute));
#endif
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s : exit", __func__);
    return result;
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
  ** Function:        nfcManager_getRemainingAidTableSize
  ** Description:     Get the remaining size of AID routing table.
  **
  **                  e: JVM environment.
  **                  o: Java object.
  **
  *******************************************************************************/
  static jint nfcManager_getRemainingAidTableSize(JNIEnv*, jobject) {
    return NFA_GetRemainingAidTableSize();
  }
  /*******************************************************************************
  **
  ** Function:        nfcManager_clearAidTable
  **
  ** Description:     Clean all AIDs in routing table
  **                  e: JVM environment.
  **                  o: Java object.
  **
  *******************************************************************************/
  static bool nfcManager_clearAidTable(JNIEnv*, jobject) {
#if (NXP_EXTNS == TRUE)
    /*In case of 66T/67T field on is observed as last field event once reader
     is removed from proximity, which will hold the transaction lock
     unnecessarily
     In such cases end the lock as it is not required*/
    if (((nfcFL.chipType == pn548C2) || (nfcFL.chipType == pn551)) &&
        pTransactionController->getCurTransactionRequestor() ==
            TRANSACTION_REQUESTOR(RF_FIELD_EVT)) {
      pTransactionController->transactionEnd(
          TRANSACTION_REQUESTOR(RF_FIELD_EVT));
    }
#endif
    return RoutingManager::getInstance().clearAidTable();
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
  static jint nfcManager_doRegisterT3tIdentifier(JNIEnv * e, jobject,
                                                 jbyteArray t3tIdentifier) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    ScopedByteArrayRO bytes(e, t3tIdentifier);
    uint8_t* buf =
        const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
    size_t bufLen = bytes.size();
    int handle =
        RoutingManager::getInstance().registerT3tIdentifier(buf, bufLen);
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
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
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
  static jint nfcManager_getLfT3tMax(JNIEnv*, jobject) { return sLfT3tMax; }

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
  static jboolean nfcManager_doInitialize(JNIEnv * e, jobject o) {
    initializeGlobalDebugEnabledFlag();
    tNFA_MW_VERSION mwVer;
    gSeDiscoverycount = 0;
    gActualSeCount = 0;

    uint8_t switchToUiccSlot = 0;
#if (NXP_EXTNS == TRUE)
    rfActivation = false;
    tNFA_PMID ven_config_addr[] = {0xA0, 0x07};
    tNFA_PMID pollProfileAddr[] = {0xA0, 0x44};
    uint8_t pollProfileVal[] = {NFC_FORUM_POLL};
    bool isSuccess = false;
    sNfcee_disc_state = UICC_SESSION_NOT_INTIALIZED;
    IsEseCeDisabled = false;
    /* NFC initialization in progress */
    if (NFC_OFF == sNfcState) sNfcState = NFC_INITIALIZING_IN_PROGRESS;

    char propBuf[PROPERTY_VALUE_MAX] = {'\0'};
    int len = property_get(ESE_PWR_MGMT_PROP, propBuf, "");
    if (len != 0) {
      sIsLowRamDevice = (propBuf[0] != '\0')
                            ? ((strcmp(propBuf, "true") == 0) ? true : false)
                            : true;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "isLowRamDevice %s", sIsLowRamDevice ? "true" : "false");
    }
    NFA_SetLowRamDevice(sIsLowRamDevice);
#endif
    mwVer = NFA_GetMwVersion();
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s:  MW Version: NFC_NCIHALx_AR%X.%x.%x.%x", __func__,
                        mwVer.validation, mwVer.android_version,
                        mwVer.major_version, mwVer.minor_version);

    tNFA_STATUS stat = NFA_STATUS_OK;
    NfcTag::getInstance().mNfcDisableinProgress = false;
    PowerSwitch& powerSwitch = PowerSwitch::getInstance();
    if (sIsNfaEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: already enabled", __func__);
      goto TheEnd;
    }
#if (NXP_EXTNS == TRUE)
    if (gsNfaPartialEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: already  partial enable calling deinitialize", __func__);
      nfcManager_doPartialDeInitialize();
    }
#endif
    powerSwitch.initialize(PowerSwitch::FULL_POWER);
    register_signal_handler();
    {

      NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
      theInstance.Initialize();  // start GKI, NCI task, NFC task
#if (NXP_EXTNS == TRUE)
      int state = getJCOPOS_UpdaterState();
      if ((state != OSU_COMPLETE) && (state != OSU_NOT_STARTED)) {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("JCOP is in OSU mode");
        NFA_SetBootMode(NFA_OSU_BOOT_MODE);
      } else {
        NFA_SetBootMode(NFA_NORMAL_BOOT_MODE);
      }
#endif
      stat = nfcManagerEnableNfc(theInstance);
      nfcManager_getFeatureList();
      if (nfcFL.nfcNxpEse) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("ESE Present Loading p61-jcop-lib");
        pJcopMgr->JcopInitialize();
      } else
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("ESE Not Present");
      EXTNS_Init(nfaDeviceManagementCallback, nfaConnectionCallback);

      if (stat == NFA_STATUS_OK) {
        // sIsNfaEnabled indicates whether stack started successfully
        if (sIsNfaEnabled) {
          SecureElement::getInstance().initialize(getNative(e, o));
          RoutingManager::getInstance().initialize(getNative(e, o));
          HciRFParams::getInstance().initialize();
          MposManager::getInstance().initialize(getNative(e, o));
          sIsSecElemSelected =
              (SecureElement::getInstance().getActualNumEe() - 1);
          sIsSecElemDetected = sIsSecElemSelected;
          nativeNfcTag_registerNdefTypeHandler();
          NfcTag::getInstance().initialize(getNative(e, o));
          PeerToPeer::getInstance().initialize();
          PeerToPeer::getInstance().handleNfcOnOff(true);
          HciEventManager::getInstance().initialize(getNative(e, o));
#if (NXP_EXTNS == TRUE)
          if (NfcConfig::hasKey(NAME_NXP_DEFAULT_NFCEE_DISC_TIMEOUT)) {
            gdisc_timeout =
                NfcConfig::getUnsigned(NAME_NXP_DEFAULT_NFCEE_DISC_TIMEOUT);
          } else {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                "NAME_NXP_DEFAULT_NFCEE_DISC_TIMEOUT not found");
            gdisc_timeout =
                NFCEE_DISC_TIMEOUT_SEC; /*Default nfcee discover timeout*/
          }
          gdisc_timeout = gdisc_timeout * 1000;
          if (NFA_STATUS_OK == GetNumNFCEEConfigured()) {
            DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf(" gSeDiscoverycount = %d gActualSeCount=%d",
                                gSeDiscoverycount, gActualSeCount);
            if (gSeDiscoverycount < gActualSeCount) {
              DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                  "Wait for ESE to discover, gdisc_timeout = %d",
                  gdisc_timeout);
              SyncEventGuard g(gNfceeDiscCbEvent);
              if (gNfceeDiscCbEvent.wait(gdisc_timeout) == false) {
                LOG(ERROR) << StringPrintf(
                    "%s: timeout waiting for nfcee dis event", __func__);
              }
              DLOG_IF(INFO, nfc_debug_enabled)
                  << StringPrintf("gSeDiscoverycount  = %d gActualSeCount=%d",
                                  gSeDiscoverycount, gActualSeCount);
            } else {
              DLOG_IF(INFO, nfc_debug_enabled)
                  << StringPrintf("All ESE are discovered ");
            }
          }
          // Create transaction controller
          (void)transactionController::controller();
          if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
            checkforESERemoval();

            if (NfcConfig::hasKey(NAME_NXP_DUAL_UICC_ENABLE)) {
              dualUiccInfo.dualUiccEnable =
                  NfcConfig::getUnsigned(NAME_NXP_DUAL_UICC_ENABLE);
            }
            if (dualUiccInfo.dualUiccEnable == 0x01) {
              checkforNfceeConfig(UICC1 | UICC2 | ESE);
              dualUiccInfo.uiccActivStat = 0x00;
              if (SecureElement::getInstance().getEeStatus(
                      SecureElement::getInstance().EE_HANDLE_0xF4) !=
                  NFC_NFCEE_STATUS_REMOVED) {
                dualUiccInfo.uiccActivStat = (sSelectedUicc & 0x0F);
              }
              switchToUiccSlot = ((sSelectedUicc & 0x0F) == 0x01) ? 0x02 : 0x01;
              nfcManager_doSelectUicc(e, o, switchToUiccSlot);
              if (SecureElement::getInstance().getEeStatus(
                      SecureElement::getInstance().EE_HANDLE_0xF4) !=
                  NFC_NFCEE_STATUS_REMOVED) {
                dualUiccInfo.uiccActivStat |= (sSelectedUicc & 0x0F);
              }
              uiccEventTimer.set(1, notifyUiccEvent);
            }
          } else
            SecureElement::getInstance().updateEEStatus();
          if (nfcFL.eseFL._JCOP_WA_ENABLE) {
            RoutingManager::getInstance().handleSERemovedNtf();
          }
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("Discovered se count %d", gSeDiscoverycount);
          /*Check for ETSI12 Configuration for SEs detected in the HCI Network*/
          performNfceeETSI12Config();
          if (nfcFL.eseFL._ESE_ETSI12_PROP_INIT &&
              (swp_getconfig_status & SWP2_ESE)) {
            performHCIInitialization(e, o);
          }
          SecureElement::getInstance().getSETechnology(
              SecureElement::EE_HANDLE_0xF3);
          checkforNfceeConfig(UICC1 | UICC2 | ESE);
          /*Pending clear all pipe handling*/
          if (sNfcee_disc_state == UICC_CLEAR_ALL_PIPE_NTF_RECEIVED) {
            DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf("Perform pending UICC clear all pipe handling");
            sNfcee_disc_state = UICC_SESSION_INTIALIZATION_DONE;
            /*Handle UICC clear all pipe notification*/
            checkforNfceeConfig(UICC1 | UICC2);
          }
          sNfcee_disc_state = UICC_SESSION_INTIALIZATION_DONE;
#endif
/////////////////////////////////////////////////////////////////////////////////
// Add extra configuration here (work-arounds, etc.)
#if (NXP_EXTNS == TRUE)
          if (nfcFL.nfcNxpEse) {
            if (nfcFL.eseFL._ESE_SVDD_SYNC ||
                nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION ||
                nfcFL.nfccFL._NFCC_SPI_FW_DOWNLOAD_SYNC ||
                nfcFL.eseFL._ESE_DWP_SPI_SYNC_ENABLE) {
              isSuccess = createSPIEvtHandlerThread();
            }
            if (nfcFL.eseFL._ESE_DWP_SPI_SYNC_ENABLE && isSuccess) {
              SecureElement::getInstance().enableDwp();
            } else if (!isSuccess) {
              DLOG_IF(INFO, nfc_debug_enabled)
                  << StringPrintf("Failed to start SPI Event Handler Thread");
            }
          }
#endif
          pendingScreenState = false;
          {
            SyncEventGuard guard(android::sNfaGetConfigEvent);
            stat = NFA_GetConfig(0x01, ven_config_addr);
            if (stat == NFA_STATUS_OK) {
              android::sNfaGetConfigEvent.wait();
            }
            /*sCurrentConfigLen should be > 4 (num_tlv:1 + addr:2 + value:1) and
             *pos 4 gives the current eeprom value*/
            if ((sCurrentConfigLen > 4) && (sConfig[4] == 0x03)) {
              DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                  "%s: No need to update VEN_CONFIG. Already set to 0x%02x",
                  __func__, sConfig[4]);
            } else {
              SetVenConfigValue(NFC_MODE_ON);
              if (stat != NFA_STATUS_OK) {
                LOG(ERROR) << StringPrintf(
                    "%s: fail enable SetVenConfigValue; error=0x%X", __func__,
                    stat);
              }
            }
            gGeneralPowershutDown = 0;
          }
          updateEeprom(pollProfileAddr, sizeof(pollProfileVal), pollProfileVal);
          if (gIsDtaEnabled == true) {
            uint8_t configData = 0;
            configData =
                0x01; /**< Poll NFC-DEP : Highest Available Bit Rates */
            NFA_SetConfig(NCI_PARAM_ID_BITR_NFC_DEP, sizeof(uint8_t),
                          &configData);
            configData = 0x0B; /**< Listen NFC-DEP : Waiting Time */
            NFA_SetConfig(NFC_PMID_WT, sizeof(uint8_t), &configData);
            configData =
                0x0F; /**< Specific Parameters for NFC-DEP RF Interface */
            NFA_SetConfig(NCI_PARAM_ID_NFC_DEP_OP, sizeof(uint8_t),
                          &configData);
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
#if (NXP_EXTNS == TRUE)
          discDuration = nat->discovery_duration;
#endif
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
          unsigned long num = 0;
          if (NfcConfig::hasKey(NAME_NXP_CE_ROUTE_STRICT_DISABLE)) {
            num = NfcConfig::getUnsigned(NAME_NXP_CE_ROUTE_STRICT_DISABLE);
          } else {
            num = 0x01;
          }

          // TODO: Check this in L_OSP_EXT[PN547C2]
          //                NFA_SetCEStrictDisable(num);
          RoutingManager::getInstance().setCeRouteStrictDisable(num);
          prevScreenState = NFA_SCREEN_STATE_OFF_LOCKED;
#if (NXP_EXTNS != TRUE)
          // Do custom NFCA startup configuration.
          doStartupConfig();
#else
        if (HciRFParams::getInstance().isCeWithEseDisabled()) {
          recoverEseConnectivity();
        }
#endif
          goto TheEnd;
        }
      }

      LOG(ERROR) << StringPrintf("%s: fail nfa enable; error=0x%X", __func__,
                                 stat);

      if (sIsNfaEnabled) {
        EXTNS_Close();
        stat = NFA_Disable(false /* ungraceful */);
      }

      theInstance.Finalize();
    }

  TheEnd:
    if (sIsNfaEnabled)
      PowerSwitch::getInstance().setLevel(PowerSwitch::LOW_POWER);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
    return sIsNfaEnabled ? JNI_TRUE : JNI_FALSE;
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_doEnableDtaMode
  **
  ** Description:     Enable the DTA mode in NFC service.
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManager_doEnableDtaMode(JNIEnv * e, jobject o) {
    gIsDtaEnabled = true;
  }

#if (NXP_EXTNS == TRUE)
  /*******************************************************************************
  **
  ** Function:        nfcManagerEnableNfc
  **
  ** Description:     Enables Nfc submodules in libnfc
  **
  ** Returns:         Status
  **
  *******************************************************************************/
  static tNFA_STATUS nfcManagerEnableNfc(NfcAdaptation & theInstance) {
    uint8_t retryCount = 0;
    tNFA_STATUS stat = NFA_STATUS_OK;
    sEnableStatus = NFA_STATUS_FAILED;
    do {
      SyncEventGuard guard(sNfaEnableEvent);
      tHAL_NFC_ENTRY* halFuncEntries = theInstance.GetHalEntryFuncs();
      NFA_Init(halFuncEntries);
      stat = NFA_Enable(nfaDeviceManagementCallback, nfaConnectionCallback);
      if (stat == NFA_STATUS_OK) {
        sNfaEnableEvent.wait();  // wait for NFA command to finish
      }
      retryCount++;
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: sEnableStatus =0x%X", __func__, sEnableStatus);
    } while ((sEnableStatus == NFA_STATUS_NOT_INITIALIZED) && (retryCount < 3));

    return sEnableStatus;
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
  bool nfcManager_checkNfcStateBusy() {
    bool status = false;

    if (NFA_checkNfcStateBusy() == true) status = true;

    return status;
  }
  /*******************************************************************************
   **
   ** Function:        requestFwDownload
   **
   ** Description      This function is to complete the FW Dwnld to complete the
   **                  the pending request due to SPI session ongoing .
   **
   ** Returns          void.
   **
   *******************************************************************************/
  void requestFwDownload() {
    JNIEnv* e = NULL;
    uint8_t fwDnldRequest = false;
    int status = NFA_STATUS_OK;

    NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
    status = theInstance.HalGetFwDwnldFlag(&fwDnldRequest);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "Enter: %s fwDnldRequest = %d", __func__, fwDnldRequest);
    if (status == NFA_STATUS_OK) {
      if (fwDnldRequest == true) {
        ScopedAttach attach(RoutingManager::getInstance().mNativeData->vm, &e);
        if (e == NULL) {
          LOG(ERROR) << StringPrintf("Exit:%s jni env is null", __func__);
          return;
        }
        if (nfcFL.nfccFL._NFCC_SPI_FW_DOWNLOAD_SYNC) {
          e->CallVoidMethod(gNativeData->manager,
                            android::gCachedNfcManagerNotifyFwDwnldRequested);
        }
        if (e->ExceptionCheck()) {
          e->ExceptionClear();
          LOG(ERROR) << StringPrintf("Exit:%s fail notify", __func__);
        }

      } else {
        LOG(ERROR) << StringPrintf("Exit:%s Firmware download request:%d ",
                                   __func__, fwDnldRequest);
      }
    } else {
      LOG(ERROR) << StringPrintf("Exit:%s HalGetFwDwnldFlag status:%d ",
                                 __func__, status);
    }
  }

  /*******************************************************************************
  **
  ** Function:        updateEeprom
  **
  ** Description:     Used to send the EXTENDED SET CONFIG command to update
  **                  the EEPROM values
  **                  *param Address of the eeprom
  **                  len of the eeprom address
  **                  val to be updated
  **
  ** Returns:         NFA_STATUS_OK/NFA_STATUS_FAILED
  **
  *******************************************************************************/
  tNFA_STATUS updateEeprom(uint8_t * param, uint8_t len, uint8_t * val) {
    tNFA_STATUS status = NFA_STATUS_OK;
    uint8_t valOffset = 4;
    SyncEventGuard guard(sNfaGetConfigEvent);
    status = NFA_GetConfig(0x01, param);
    if (status == NFA_STATUS_OK) {
      status = sNfaGetConfigEvent.wait(2 * ONE_SECOND_MS) ? NFA_STATUS_OK
                                                          : NFA_STATUS_FAILED;
    }

    if (status == NFA_STATUS_OK) {
      /*sCurrentConfigLen should be > 4 (num_tlv:1 + addr:2 + value:1) and
       *pos 4 gives the current eeprom value*/
      if ((sCurrentConfigLen > PROPSETCONFIGMINLEN) &&
          (sConfig[SETCONFIGLENPOS] == len)) {
        if (memcmp(&sConfig[valOffset], val, len)) {
          status = NxpNfcUpdateEeprom(param, len, val);
        } else {
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Already same");
        }
      } else {
        status = NFA_STATUS_FAILED;
      }
    }
    return status;
  }
#endif

  /*******************************************************************************
  **
  ** Function:        nfcManager_doDisableDtaMode
  **
  ** Description:     Disable the DTA mode in NFC service.
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManager_doDisableDtaMode(JNIEnv * e, jobject o) {
    gIsDtaEnabled = false;
  }

static void nfcManager_doFactoryReset(JNIEnv*, jobject) {
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  theInstance.FactoryReset();
}

#if (NXP_EXTNS == TRUE)
  /*******************************************************************************
   **
   ** Function:        nfcManager_Enablep2p
   **
   ** Description:     enable P2P
   **                  e: JVM environment.
   **                  o: Java object.
   **
   ** Returns:         None.
   **
   *******************************************************************************/
  static void nfcManager_Enablep2p(JNIEnv * e, jobject o, jboolean p2pFlag) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("Enter :%s  p2pFlag = %d", __func__, p2pFlag);
    /* if another transaction is already in progress, store this request */
    if (!pTransactionController->transactionAttempt(
            TRANSACTION_REQUESTOR(enablep2p))) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Transaction in progress, Store the request");
      set_last_request(ENABLE_P2P, NULL);
      transaction_data.discovery_params.enable_p2p = p2pFlag;
      return;
    }
    if (sRfEnabled && p2pFlag) {
      /* Stop discovery if already ON */
      startRfDiscovery(false);
    }

    /* if already Polling, change to listen Mode */
    if (sPollingEnabled) {
      if (p2pFlag && !sP2pEnabled) {
        /* enable P2P listening, if we were not already listening */
        sP2pEnabled = true;
        PeerToPeer::getInstance().enableP2pListening(true);
      }
    }
    /* Beam ON - Discovery ON */
    if (p2pFlag) {
      NFA_ResumeP2p();
      startRfDiscovery(p2pFlag);
    }
    pTransactionController->transactionEnd(TRANSACTION_REQUESTOR(enablep2p));
  }

  void enableRfDiscovery() {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __FUNCTION__);
    nfcManager_enableDiscovery(NULL, NULL, mDiscParams.technologies_mask,
                               mDiscParams.enable_lptd, mDiscParams.reader_mode,
                               mDiscParams.enable_p2p, mDiscParams.restart);
  }

  void disableRfDiscovery() { nfcManager_disableDiscovery(NULL, NULL); }

  void storeLastDiscoveryParams(int technologies_mask, bool enable_lptd,
                                bool reader_mode, bool enable_p2p,
                                bool restart) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __FUNCTION__);
    mDiscParams.technologies_mask = technologies_mask;
    mDiscParams.enable_lptd = enable_lptd;
    mDiscParams.reader_mode = reader_mode;
    mDiscParams.enable_p2p = enable_p2p;
    mDiscParams.restart = restart;
  }
#endif
  /*******************************************************************************
  **
  ** Function:        nfcManager_enableDiscovery
  **
  ** Description:     Start polling and listening for devices.
  **                  e: JVM environment.
  **                  o: Java object.
  **                  technologies_mask: the bitmask of technologies for which
  *to enable discovery
  **                  enable_lptd: whether to enable low power polling (default:
  *false)
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManager_enableDiscovery(
      JNIEnv * e, jobject o, jint technologies_mask, jboolean enable_lptd,
      jboolean reader_mode, jboolean enable_p2p, jboolean restart) {
    tNFA_STATUS status = NFA_STATUS_OK;
    tNFA_STATUS stat = NFA_STATUS_OK;
    tNFA_TECHNOLOGY_MASK tech_mask = DEFAULT_TECH_MASK;
    unsigned long num = 0;

    tNFA_HANDLE handle = NFA_HANDLE_INVALID;
    struct nfc_jni_native_data* nat = NULL;

#if (NXP_EXTNS == TRUE)
    tNFA_TECHNOLOGY_MASK etsi_tech_mask = 0;
    p61_access_state_t p61_current_state = P61_STATE_INVALID;
    long ret_val = -1;
#endif
    if (e == NULL && o == NULL) {
      nat = transaction_data.transaction_nat;
    } else {
      nat = getNative(e, o);
    }

    storeLastDiscoveryParams(technologies_mask, enable_lptd, reader_mode,
                             enable_p2p, restart);
#if (NXP_EXTNS == TRUE)
    if (!pTransactionController->transactionAttempt(
            TRANSACTION_REQUESTOR(enableDiscovery))) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Transaction is in progress store the request");
      set_last_request(ENABLE_DISCOVERY, nat);
      transaction_data.discovery_params.technologies_mask = technologies_mask;
      transaction_data.discovery_params.enable_lptd = enable_lptd;
      transaction_data.discovery_params.reader_mode = reader_mode;
      transaction_data.discovery_params.enable_p2p = enable_p2p;
      transaction_data.discovery_params.restart = restart;
      goto TheEnd;
    }
#endif

#if (NXP_EXTNS == TRUE)
    if ((nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) &&
        (MposManager::getInstance().getEtsiReaederState() ==
         STATE_SE_RDR_MODE_STARTED)) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: enter STATE_SE_RDR_MODE_START_CONFIG", __func__);
      Rdr_req_ntf_info_t mSwp_info =
          MposManager::getInstance().getSwpRrdReqInfo();
      {
        SyncEventGuard guard(android::sNfaEnableDisablePollingEvent);
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: disable polling", __func__);
        status = NFA_DisablePolling();
        if (status == NFA_STATUS_OK) {
          android::sNfaEnableDisablePollingEvent
              .wait();  // wait for NFA_POLL_DISABLED_EVT
        } else {
          LOG(ERROR) << StringPrintf("%s: fail disable polling; error=0x%X",
                                     __func__, status);
        }
      }

      if (mSwp_info.swp_rd_req_info.tech_mask & NFA_TECHNOLOGY_MASK_A)
        etsi_tech_mask |= NFA_TECHNOLOGY_MASK_A;
      if (mSwp_info.swp_rd_req_info.tech_mask & NFA_TECHNOLOGY_MASK_B)
        etsi_tech_mask |= NFA_TECHNOLOGY_MASK_B;

      {
        SyncEventGuard guard(android::sNfaEnableDisablePollingEvent);
        status = NFA_EnablePolling(etsi_tech_mask);
        if (status == NFA_STATUS_OK) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: wait for enable event", __func__);
          android::sNfaEnableDisablePollingEvent
              .wait();  // wait for NFA_POLL_ENABLED_EVT
        } else {
          LOG(ERROR) << StringPrintf("%s: fail enable polling; error=0x%X",
                                     __func__, status);
        }
      }
      startRfDiscovery(true);
      pTransactionController->transactionEnd(
          TRANSACTION_REQUESTOR(enableDiscovery));

      if (!pTransactionController->transactionAttempt(
              TRANSACTION_REQUESTOR(etsiReader))) {
        LOG(ERROR) << StringPrintf("%s: transaction attempt failed",
                                   __FUNCTION__);
      }
      goto TheEnd;
    }
#endif

    if (technologies_mask == -1 && nat)
      tech_mask = (tNFA_TECHNOLOGY_MASK)nat->tech_mask;
    else if (technologies_mask != -1)
      tech_mask = (tNFA_TECHNOLOGY_MASK)technologies_mask;
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: enter; tech_mask = %02x", __func__, tech_mask);

    if (sDiscoveryEnabled && !restart) {
      LOG(ERROR) << StringPrintf("%s: already discovering", __func__);
#if (NXP_EXTNS == TRUE)
      goto TheEnd;
#else
    return;
#endif
    }

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: sIsSecElemSelected=%u", __func__, sIsSecElemSelected);
    PowerSwitch::getInstance().setLevel(PowerSwitch::FULL_POWER);

    if (sRfEnabled) {
      // Stop RF discovery to reconfigure
      startRfDiscovery(false);
    }

    if (NfcConfig::hasKey(NAME_UICC_LISTEN_TECH_MASK)) {
      num = NfcConfig::getUnsigned(NAME_UICC_LISTEN_TECH_MASK);
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s:UICC_LISTEN_MASK=0x0%lu;", __func__, num);
    }
    // Check polling configuration
    if (tech_mask != 0) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: Disable p2pListening", __func__);
      PeerToPeer::getInstance().enableP2pListening(false);
      stopPolling_rfDiscoveryDisabled();
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
#if (NXP_EXTNS == TRUE)
          NFA_SetReaderMode(true, 0);
          /*Send the state of readmode flag to Hal using proprietary command*/
          sProprietaryCmdBuf[3] = 0x01;
          status |=
              NFA_SendRawVsCommand(sizeof(sProprietaryCmdBuf),
                                   sProprietaryCmdBuf, NxpResponsePropCmd_Cb);
          if (status == NFA_STATUS_OK) {
            SyncEventGuard guard(sNfaNxpNtfEvent);
            sNfaNxpNtfEvent.wait(500);  // wait for callback
          } else {
            LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand",
                                       __func__);
          }
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: FRM Enable", __func__);
#endif
          NFA_DisableListening();
#if (NXP_EXTNS == TRUE)
          sTechMask = tech_mask;

          discDuration = READER_MODE_DISCOVERY_DURATION;
#endif
          NFA_SetRfDiscoveryDuration(READER_MODE_DISCOVERY_DURATION);
        } else if (!reader_mode && sReaderModeEnabled) {
          struct nfc_jni_native_data* nat = getNative(e, o);
          sReaderModeEnabled = false;
#if (NXP_EXTNS == TRUE)
          NFA_SetReaderMode(false, 0);
          gFelicaReaderState = STATE_IDLE;
          /*Send the state of readmode flag to Hal using proprietary command*/
          sProprietaryCmdBuf[3] = 0x00;
          status |=
              NFA_SendRawVsCommand(sizeof(sProprietaryCmdBuf),
                                   sProprietaryCmdBuf, NxpResponsePropCmd_Cb);
          if (status == NFA_STATUS_OK) {
            SyncEventGuard guard(sNfaNxpNtfEvent);
            sNfaNxpNtfEvent.wait(500);  // wait for callback
          } else {
            LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand",
                                       __func__);
          }
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: FRM Disable", __func__);
#endif
          if ((nfcFL.eseFL._ESE_DUAL_MODE_PRIO_SCHEME ==
               nfcFL.eseFL._ESE_EXCLUSIVE_WIRED_MODE) ||
              nfcFL.eseFL._ESE_UICC_EXCLUSIVE_WIRED_MODE) {
            if (!SecureElement::getInstance().mlistenDisabled) {
              NFA_EnableListening();
            }
          } else {
            NFA_EnableListening();
          }

#if (NXP_EXTNS == TRUE)
          discDuration = nat->discovery_duration;
#endif
          NFA_SetRfDiscoveryDuration(nat->discovery_duration);
        } else {
          {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                "%s: restart UICC listen mode (%02lX)", __func__, (num & 0xC7));
            handle = (sCurrentSelectedUICCSlot!=0x02)?
                SecureElement::getInstance().getEseHandleFromGenericId(SecureElement::UICC_ID):
                SecureElement::getInstance().getEseHandleFromGenericId(SecureElement::UICC2_ID);
            SyncEventGuard guard(SecureElement::getInstance().mUiccListenEvent);
            stat = NFA_CeConfigureUiccListenTech(handle, 0x00);
            if (stat == NFA_STATUS_OK) {
              SecureElement::getInstance().mUiccListenEvent.wait();
            } else
              LOG(ERROR) << StringPrintf("fail to stop UICC listen");
          }
          {
            SyncEventGuard guard(SecureElement::getInstance().mUiccListenEvent);
            stat = NFA_CeConfigureUiccListenTech(handle, (num & 0xC7));
            if (stat == NFA_STATUS_OK) {
              SecureElement::getInstance().mUiccListenEvent.wait();
            } else
              LOG(ERROR) << StringPrintf("fail to start UICC listen");
          }
        }
      }
      NFC_SetNfcServicePid();
    } else {
      // No technologies configured, stop polling
      stopPolling_rfDiscoveryDisabled();
    }

    // Start P2P listening if tag polling was enabled or the mask was 0.
    if (sDiscoveryEnabled || (tech_mask == 0)) {
      handle = SecureElement::getInstance().getEseHandleFromGenericId(
          SecureElement::UICC_ID);

#if (NXP_EXTNS == TRUE)
      if ((getScreenState() == (NFA_SCREEN_STATE_ON_UNLOCKED)) ||
          sProvisionMode) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: Enable p2pListening", __func__);
        PeerToPeer::getInstance().enableP2pListening(true);
      } else {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: Disable p2pListening", __func__);
        PeerToPeer::getInstance().enableP2pListening(false);
      }
#endif

      {
        SyncEventGuard guard(SecureElement::getInstance().mUiccListenEvent);
        stat = NFA_CeConfigureUiccListenTech(handle, 0x00);
        if (stat == NFA_STATUS_OK) {
          SecureElement::getInstance().mUiccListenEvent.wait();
        } else
          LOG(ERROR) << StringPrintf("fail to start UICC listen");
      }

      {
        SyncEventGuard guard(SecureElement::getInstance().mUiccListenEvent);
        stat = NFA_CeConfigureUiccListenTech(handle, (num & 0xC7));
        if (stat == NFA_STATUS_OK) {
          SecureElement::getInstance().mUiccListenEvent.wait();
        } else
          LOG(ERROR) << StringPrintf("fail to start UICC listen");
      }
    }
    // Actually start discovery.
    startRfDiscovery(true);
    sDiscoveryEnabled = true;

    PowerSwitch::getInstance().setModeOn(PowerSwitch::DISCOVERY);

#if (NXP_EXTNS == TRUE)
  TheEnd:
    pTransactionController->transactionEnd(
        TRANSACTION_REQUESTOR(enableDiscovery));
    /* Set this state only during initialization and in all the other cases
    NfcState should remain at NFC_ON  except during Nfc deinit */
    if (NFC_INITIALIZING_IN_PROGRESS == sNfcState) {
      sNfcState = NFC_ON;
      ret_val = NFC_GetP61Status((void*)&p61_current_state);
      if (ret_val < 0) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("NFC_GetP61Status failed");
      }
      if (!(p61_current_state & (P61_STATE_SPI) &&
            !(p61_current_state & (P61_STATE_SPI_PRIO)))) {
        SecureElement::getInstance().setNfccPwrConfig(
            SecureElement::getInstance().NFCC_DECIDES);
      }
      NFC_SetNfcServicePid();
    }

#endif
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
  void nfcManager_disableDiscovery(JNIEnv * e, jobject o) {
    (void)e;
    (void)o;
    tNFA_STATUS status = NFA_STATUS_OK;
    unsigned long num = 0;
    unsigned long p2p_listen_mask = 0;
    tNFA_HANDLE handle = NFA_HANDLE_INVALID;
#if (NXP_EXTNS == TRUE)
    p61_access_state_t p61_current_state = P61_STATE_INVALID;
    long ret_val = -1;
#endif
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter;", __func__);
#if (NXP_EXTNS == TRUE)
    if (!pTransactionController->transactionAttempt(
            TRANSACTION_REQUESTOR(disableDiscovery))) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Transaction in progress, Store the request");
      set_last_request(DISABLE_DISCOVERY, NULL);
      return;
    }
#endif
    pn544InteropAbortNow();
#if (NXP_EXTNS == TRUE)
    if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
      if (MposManager::getInstance().getEtsiReaederState() ==
          STATE_SE_RDR_MODE_START_IN_PROGRESS) {
        Rdr_req_ntf_info_t mSwp_info =
            MposManager::getInstance().getSwpRrdReqInfo();
        //        if(android::isDiscoveryStarted() == true)
        android::startRfDiscovery(false);
        PeerToPeer::getInstance().enableP2pListening(false);
        {
          if (mSwp_info.swp_rd_req_info.src == 0x4C0) {
            SyncEventGuard guard(SecureElement::getInstance().mEseListenEvent);
            status = NFA_CeConfigureEseListenTech(handle, 0x00);
            if (status == NFA_STATUS_OK)
              SecureElement::getInstance().mEseListenEvent.wait();
          } else {
            SyncEventGuard guard(SecureElement::getInstance().mUiccListenEvent);
            status = NFA_CeConfigureUiccListenTech(handle, 0x00);
            if (status == NFA_STATUS_OK)
              SecureElement::getInstance().mUiccListenEvent.wait();
          }
          if (status != NFA_STATUS_OK) {
            LOG(ERROR) << StringPrintf("fail to reset UICC/eSE listen");
          }
        }
        goto TheEnd;
      } else if (MposManager::getInstance().getEtsiReaederState() ==
                 STATE_SE_RDR_MODE_STOP_IN_PROGRESS) {
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
    sDiscoveryEnabled = false;

    if (NfcConfig::hasKey(NAME_UICC_LISTEN_TECH_MASK)) {
      num = NfcConfig::getUnsigned(NAME_UICC_LISTEN_TECH_MASK);
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s:UICC_LISTEN_MASK=0x0%lu;", __func__, num);
    }

    if (NfcConfig::hasKey(NAME_P2P_LISTEN_TECH_MASK)) {
      p2p_listen_mask = NfcConfig::getUnsigned(NAME_P2P_LISTEN_TECH_MASK);
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s:P2P_LISTEN_MASK=0x0%lu;", __func__, p2p_listen_mask);
    }
    PeerToPeer::getInstance().enableP2pListening(false);
    NFA_PauseP2p();

    if (sIsSecElemSelected) {
      SecureElement& se = SecureElement::getInstance();
      tNFA_HANDLE ee_handleList[nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED];
      uint8_t count;
      se.getEeHandleList(ee_handleList, &count);
      for (int i = 0; i < count; i++) {
        handle =
            se.getEseHandleFromGenericId(se.getGenericEseId(ee_handleList[i]));
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s:Registering for active handle id=0x%x;", __func__, handle);

        if (handle == 0x4C0) {
          SyncEventGuard guard(SecureElement::getInstance().mEseListenEvent);
          status = NFA_CeConfigureEseListenTech(handle, 0x00);
          if (status == NFA_STATUS_OK)
            SecureElement::getInstance().mEseListenEvent.wait();
        } else {
          SyncEventGuard guard(SecureElement::getInstance().mUiccListenEvent);
          status = NFA_CeConfigureUiccListenTech(handle, 0x00);
          if (status == NFA_STATUS_OK)
            SecureElement::getInstance().mUiccListenEvent.wait();
        }
        if (status != NFA_STATUS_OK) {
          LOG(ERROR) << StringPrintf("fail to reset UICC/eSE listen");
        }

        if (handle == 0x4C0) {
          SyncEventGuard guard(SecureElement::getInstance().mEseListenEvent);
          status = NFA_CeConfigureEseListenTech(handle, (num & 0x07));
          if (status == NFA_STATUS_OK)
            SecureElement::getInstance().mEseListenEvent.wait();
        } else {
          SyncEventGuard guard(SecureElement::getInstance().mUiccListenEvent);
          status = NFA_CeConfigureUiccListenTech(handle, (num & 0x07));
          if (status == NFA_STATUS_OK)
            SecureElement::getInstance().mUiccListenEvent.wait();
        }
        if (status != NFA_STATUS_OK) {
          LOG(ERROR) << StringPrintf("fail to start UICC/eSE listen");
        }
      }

      PeerToPeer::getInstance().enableP2pListening(false);
      startRfDiscovery(true);
    }

    sP2pEnabled = false;
    // if nothing is active after this, then tell the controller to power down
    // if (! PowerSwitch::getInstance ().setModeOff (PowerSwitch::DISCOVERY))
    // PowerSwitch::getInstance ().setLevel (PowerSwitch::LOW_POWER);

    // We may have had RF field notifications that did not cause
    // any activate/deactive events. For example, caused by wireless
    // charging orbs. Those may cause us to go to sleep while the last
    // field event was indicating a field. To prevent sticking in that
    // state, always reset the rf field status when we disable discovery.
    SecureElement::getInstance().resetRfFieldStatus();
  TheEnd:
#if (NXP_EXTNS == TRUE)
    pTransactionController->transactionEnd(
        TRANSACTION_REQUESTOR(disableDiscovery));
    /* Set this state only during initialization and in all the other cases
        NfcState should remain at NFC_ON  except during Nfc deinit */
    if (NFC_INITIALIZING_IN_PROGRESS == sNfcState) {
      if (nfcFL.eseFL._WIRED_MODE_STANDBY) {
        sNfcState = NFC_ON;
        ret_val = NFC_GetP61Status((void*)&p61_current_state);
        if (ret_val < 0) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("NFC_GetP61Status failed");
        }
        if (!(p61_current_state & (P61_STATE_SPI) &&
              !(p61_current_state & (P61_STATE_SPI_PRIO)))) {
          SecureElement::getInstance().setNfccPwrConfig(
              SecureElement::getInstance().NFCC_DECIDES);
        }
      }

      NFC_SetNfcServicePid();
    }
#endif
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  }

  void enableDisableLongGuardTime(bool enable) {
    // TODO
    // This is basically a work-around for an issue
    // in BCM20791B5: if a reader is configured as follows
    // 1) Only polls for NFC-A
    // 2) Cuts field between polls
    // 3) Has a short guard time (~5ms)
    // the BCM20791B5 doesn't wake up when such a reader
    // is polling it. Unfortunately the default reader
    // mode configuration on Android matches those
    // criteria. To avoid the issue, increase the guard
    // time when in reader mode.
    //
    // Proper fix is firmware patch for B5 controllers.
    SyncEventGuard guard(sNfaSetConfigEvent);
    tNFA_STATUS stat =
        NFA_SetConfig(NCI_PARAM_ID_T1T_RDR_ONLY, 2,
                      enable ? sLongGuardTime : sDefaultGuardTime);
    if (stat == NFA_STATUS_OK)
      sNfaSetConfigEvent.wait();
    else
      LOG(ERROR) << StringPrintf("%s: Could not configure longer guard time",
                                 __func__);
    return;
  }

  void setUiccIdleTimeout(bool enable) {
    // This method is *NOT* thread-safe. Right now
    // it is only called from the same thread so it's
    // not an issue.
    tNFA_STATUS stat = NFA_STATUS_OK;
    uint8_t swp_cfg_byte0 = 0x00;
    {
      SyncEventGuard guard(sNfaGetConfigEvent);
      tNFA_PMID configParam[1] = {0xC2};
      stat = NFA_GetConfig(1, configParam);
      if (stat != NFA_STATUS_OK) {
        LOG(ERROR) << StringPrintf("%s: NFA_GetConfig failed", __func__);
        return;
      }
      sNfaGetConfigEvent.wait();
      if (sCurrentConfigLen < 4 || sConfig[1] != 0xC2) {
        LOG(ERROR) << StringPrintf(
            "%s: Config TLV length %d returned is too short", __func__,
            sCurrentConfigLen);
        return;
      }
      swp_cfg_byte0 = sConfig[3];
    }
    SyncEventGuard guard(sNfaSetConfigEvent);
    if (enable)
      swp_cfg_byte0 |= 0x01;
    else
      swp_cfg_byte0 &= ~0x01;

    stat = NFA_SetConfig(0xC2, 1, &swp_cfg_byte0);
    if (stat == NFA_STATUS_OK)
      sNfaSetConfigEvent.wait();
    else
      LOG(ERROR) << StringPrintf(
          "%s: Could not configure UICC idle timeout feature", __func__);
    return;
  }

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
  static jobject nfcManager_doCreateLlcpServiceSocket(
      JNIEnv * e, jobject, jint nSap, jstring sn, jint miu, jint rw,
      jint linearBufferLength) {
    PeerToPeer::tJNI_HANDLE jniHandle =
        PeerToPeer::getInstance().getNewJniHandle();

    ScopedUtfChars serviceName(e, sn);
    if (serviceName.c_str() == NULL) {
      LOG(ERROR) << StringPrintf("%s: service name can not be null error",
                                 __func__);
      return NULL;
    }

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
  static jboolean nfcManager_doDeinitialize(JNIEnv * e, jobject obj) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    {
      SyncEventGuard guard (sExecPendingRegEvent);
      sIsDisabling = true;
      if(sIsExecPendingReq) {
        //waiting for enableThread exit
        sExecPendingRegEvent.wait();
      }
    }

#if (NXP_EXTNS == TRUE)
    if (nfcFL.nfcNxpEse &&
        (nfcFL.eseFL._ESE_DWP_SPI_SYNC_ENABLE || nfcFL.eseFL._ESE_SVDD_SYNC ||
         nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION ||
         nfcFL.nfccFL._NFCC_SPI_FW_DOWNLOAD_SYNC)) {
      /* NFC state is put to NFC_OFF, no more request on NFC accepted(no signal
       * events)*/
      sNfcState = NFC_OFF;
      NFC_ResetNfcServicePid();
      releaseSPIEvtHandlerThread();
    }

    if (nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION &&
        (SecureElement::getInstance().mDownloadMode == JCOP_DOWNLOAD)) {
      if (e != NULL) {
        unsigned long maxTimeout = 0;
        unsigned long elapsedTimeout = 0;
        if (NfcConfig::hasKey(NAME_OS_DOWNLOAD_TIMEOUT_VALUE)) {
          maxTimeout = NfcConfig::getUnsigned(NAME_OS_DOWNLOAD_TIMEOUT_VALUE);
        } else {
          maxTimeout = MAX_JCOP_TIMEOUT_VALUE;
          LOG(ERROR) << StringPrintf("%s: Failed to get timeout value = %ld",
                                     __func__, maxTimeout);
        }

        while (SecureElement::getInstance().mDownloadMode == JCOP_DOWNLOAD) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: timeout waiting for Os Dowload", __func__);
          usleep(MAX_WAIT_TIME_FOR_RETRY * 1000000);
          e->CallVoidMethod(
              gNativeData->manager,
              android::gCachedNfcManagerNotifyJcosDownloadInProgress, true);
          if (e->ExceptionCheck()) {
            e->ExceptionClear();
            DwpChannel::getInstance().forceClose();
            LOG(ERROR) << StringPrintf("%s: fail notify", __func__);
          }
          elapsedTimeout += MAX_WAIT_TIME_FOR_RETRY;
          if (elapsedTimeout * 1000 > maxTimeout) {
            DwpChannel::getInstance().forceClose();
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                "%s: Time elapsed force close DWP channel", __func__);
          }
        }
      } else {
        DwpChannel::getInstance().forceClose();
        LOG(ERROR) << StringPrintf(
            "%s: Force close DWP channel as JNIEnv is null", __func__);
      }
    }

    if (isLowRamDevice()) {
      SecureElement::getInstance().NfccStandByOperation(
          STANDBY_ESE_PWR_RELEASE);
    }

    if (nfcFL.eseFL._JCOP_WA_ENABLE) {
      rfActivation = false;
    }
#endif
    doDwpChannel_ForceExit();
    if (nfcFL.eseFL._JCOP_WA_ENABLE) {
      NFA_HciW4eSETransaction_Complete(Wait);
    }
    if (nfcFL.nfcNxpEse) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("De-Initializing p61-jcop-lib library");
      pJcopMgr->JcopDeInitialize();
      pJcopMgr->deleteInstance();
    }
    pn544InteropAbortNow();

    RoutingManager::getInstance().onNfccShutdown();
    SecureElement::getInstance().finalize();
    PowerSwitch::getInstance().initialize(PowerSwitch::UNKNOWN_LEVEL);
    HciEventManager::getInstance().finalize();
    // Stop the discovery before calling NFA_Disable.
    if (sRfEnabled) startRfDiscovery(false);
    tNFA_STATUS stat = NFA_STATUS_OK;

    if (sIsNfaEnabled) {
      /*
       During device Power-Off while Nfc-On, Nfc mode will be NFC_MODE_ON
       NFC_MODE_OFF indicates Nfc is turning off and only in this case reset the
       venConfigValue
       */
      if (gGeneralPowershutDown == NFC_MODE_OFF) {
        stat = SetVenConfigValue(NFC_MODE_OFF);

        if (stat != NFA_STATUS_OK) {
          LOG(ERROR) << StringPrintf(
              "%s: fail enable SetVenConfigValue; error=0x%X", __func__, stat);
        }
      }
      SyncEventGuard guard(sNfaDisableEvent);
      EXTNS_Close();
      stat = NFA_Disable(true /* graceful */);
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
    NfcTag::getInstance().mNfcDisableinProgress = true;
    nativeNfcTag_abortWaits();
    NfcTag::getInstance().abort();
    sAbortConnlessWait = true;
    nativeLlcpConnectionlessSocket_abortWait();
    sIsNfaEnabled = false;
    sDiscoveryEnabled = false;
    sIsDisabling = false;
    sPollingEnabled = false;
    //    sIsSecElemSelected = false;
    sIsSecElemSelected = 0;
    gActivated = false;
    sP2pEnabled = false;
#if (NXP_EXTNS == TRUE)
    gsRouteUpdated = false;
#endif
    sLfT3tMax = 0;
    {
      // unblock NFA_EnablePolling() and NFA_DisablePolling()
      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    }
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
  static jobject nfcManager_doCreateLlcpSocket(JNIEnv * e, jobject, jint nSap,
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
  static jobject nfcManager_doCreateLlcpConnectionlessSocket(
      JNIEnv*, jobject, jint nSap, jstring /*sn*/) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: nSap=0x%X", __func__, nSap);
    return NULL;
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_doGetSecureElementList
  **
  ** Description:     Get a list of secure element handles.
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         List of secure element handles.
  **
  *******************************************************************************/
  static jintArray nfcManager_doGetSecureElementList(JNIEnv * e, jobject) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", __func__);
    return SecureElement::getInstance().getListOfEeHandles(e);
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_doSelectSecureElement
  **
  ** Description:     NFC controller starts routing data in listen mode.
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManager_doSelectSecureElement(JNIEnv * e, jobject o,
                                               jint seId) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    bool stat = true;

    if (sIsSecElemSelected >= sIsSecElemDetected) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: already selected", __func__);
      goto TheEnd;
    }

    PowerSwitch::getInstance().setLevel(PowerSwitch::FULL_POWER);

    if (sRfEnabled) {
      // Stop RF Discovery if we were polling
      startRfDiscovery(false);
    }

    stat = SecureElement::getInstance().activate(seId);
    if (stat) {
      SecureElement::getInstance().routeToSecureElement();
      sIsSecElemSelected++;
    }

    startRfDiscovery(true);
    PowerSwitch::getInstance().setModeOn(PowerSwitch::SE_ROUTING);
  TheEnd:
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_activateSecureElement
  **
  ** Description:     This function shall activate the SE as per given
  *identifier.
  **                  e: JVM environment.
  **                  o: Java object.
  **                  seId: Secure element identifier
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManager_activateSecureElement(JNIEnv * e, jobject o,
                                               jint seId) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    bool stat;
    int maxRetryCount = 3;

    SecureElement::getInstance().deactivate(seId);
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Deactivated", __func__);
    do {
      stat = SecureElement::getInstance().activate(seId);
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: Activate status %d", __func__, stat);
    } while (!stat && maxRetryCount-- > 0);

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_doSetSEPowerOffState
  **
  ** Description:     NFC controller enable/disabe card emulation in power off
  **                  state from EE.
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManager_doSetSEPowerOffState(JNIEnv * e, jobject o, jint seId,
                                              jboolean enable) {
    (void)e;
    (void)o;
    tNFA_HANDLE ee_handle;
    uint8_t power_state_mask = ~NFA_EE_PWR_STATE_SWITCH_OFF;

    if (enable == true) {
      power_state_mask = NFA_EE_PWR_STATE_SWITCH_OFF;
    }

    ee_handle = SecureElement::getInstance().getEseHandleFromGenericId(seId);

    if (sRfEnabled) {
      // Stop RF Discovery if we were polling
      startRfDiscovery(false);
    }

    tNFA_STATUS status = NFA_AddEePowerState(ee_handle, power_state_mask);

    // Commit the routing configuration
    status |= NFA_EeUpdateNow();

    if (status != NFA_STATUS_OK)
      LOG(ERROR) << StringPrintf("Failed to commit routing configuration");

    startRfDiscovery(true);

    //    TheEnd:                   /*commented to eliminate warning label
    //    defined but not used*/
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_GetDefaultSE
  **
  ** Description:     Get default Secure Element.
  **
  **
  ** Returns:         Returns 0.
  **
  *******************************************************************************/
  static jint nfcManager_GetDefaultSE(JNIEnv * e, jobject o) {
    (void)e;
    (void)o;
    unsigned long num = 0;
    if (NfcConfig::hasKey(NAME_NXP_DEFAULT_SE)) {
      num = NfcConfig::getUnsigned(NAME_NXP_DEFAULT_SE);
    }
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%lu: nfcManager_GetDefaultSE", num);
    return num;
  }

  static jint nfcManager_getSecureElementTechList(JNIEnv * e, jobject o) {
    (void)e;
    (void)o;
    uint8_t sak;
    jint tech = 0x00;
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("nfcManager_getSecureElementTechList -Enter");
    sak = HciRFParams::getInstance().getESeSak();
    bool isTypeBPresent = HciRFParams::getInstance().isTypeBSupported();

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "nfcManager_getSecureElementTechList - sak is %0x", sak);

    if (sak & 0x08) {
      tech |= TARGET_TYPE_MIFARE_CLASSIC;
    }

    if (sak & 0x20) {
      tech |= NFA_TECHNOLOGY_MASK_A;
    }

    if (isTypeBPresent == true) {
      tech |= NFA_TECHNOLOGY_MASK_B;
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "nfcManager_getSecureElementTechList - tech is %0x", tech);
    return tech;
  }

  static jintArray nfcManager_getActiveSecureElementList(JNIEnv * e,
                                                         jobject o) {
    (void)e;
    (void)o;
    return SecureElement::getInstance().getActiveSecureElementList(e);
  }

  static void nfcManager_setSecureElementListenTechMask(JNIEnv * e, jobject o,
                                                        jint tech_mask) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: ENTER", __func__);
    //    tNFA_STATUS status;                   /*commented to eliminate unused
    //    variable warning*/

    if (sRfEnabled) {
      // Stop RF Discovery if we were polling
      startRfDiscovery(false);
    }
    SecureElement::getInstance().setEseListenTechMask(tech_mask);

    startRfDiscovery(true);

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: EXIT", __func__);
  }

  static jbyteArray nfcManager_getSecureElementUid(JNIEnv * e, jobject o) {
    jbyteArray jbuff = NULL;
    uint8_t bufflen = 0;
    uint8_t buf[16] = {
        0,
    };

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("nfcManager_getSecureElementUid -Enter");
    HciRFParams::getInstance().getESeUid(&buf[0], &bufflen);
    if (bufflen > 0) {
      jbuff = e->NewByteArray(bufflen);
      e->SetByteArrayRegion(jbuff, 0, bufflen, (jbyte*)buf);
    }
    return jbuff;
  }

  static tNFA_STATUS nfcManager_setEmvCoPollProfile(
      JNIEnv * e, jobject o, jboolean enable, jint route) {
    tNFA_STATUS status = NFA_STATUS_FAILED;
    tNFA_TECHNOLOGY_MASK tech_mask = 0;

    LOG(ERROR) << StringPrintf(
        "In nfcManager_setEmvCoPollProfile enable = 0x%x route = 0x%x", enable,
        route);
    /* Stop polling */
    if (isDiscoveryStarted()) {
      // Stop RF discovery to reconfigure
      startRfDiscovery(false);
    }

    status = EmvCo_dosetPoll(enable);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail enable polling; error=0x%X",
                                 __func__, status);
      goto TheEnd;
    }

    if (enable) {
      if (route == 0x00) {
        /* DH enable polling for A and B*/
        tech_mask = NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B;
      } else if (route == 0x01) {
        /* UICC is end-point at present not supported by FW */
        /* TBD : Get eeinfo (use handle appropirately, depending up
         * on it enable the polling */
      } else if (route == 0x02) {
        /* ESE is end-point at present not supported by FW */
        /* TBD : Get eeinfo (use handle appropirately, depending up
         * on it enable the polling */
      } else {
      }
    } else {
      if (NfcConfig::hasKey(NAME_POLLING_TECH_MASK)) {
        tech_mask = NfcConfig::getUnsigned(NAME_POLLING_TECH_MASK);
      }
    }

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: enable polling", __func__);
    {
      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      status = NFA_EnablePolling(tech_mask);
      if (status == NFA_STATUS_OK) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: wait for enable event", __func__);
        sNfaEnableDisablePollingEvent.wait();  // wait for NFA_POLL_ENABLED_EVT
      } else {
        LOG(ERROR) << StringPrintf("%s: fail enable polling; error=0x%X",
                                   __func__, status);
      }
    }

  TheEnd:
    /* start polling */
    if (!isDiscoveryStarted()) {
      // Start RF discovery to reconfigure
      startRfDiscovery(true);
    }
    return status;
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_doDeselectSecureElement
  **
  ** Description:     NFC controller stops routing data in listen mode.
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManager_doDeselectSecureElement(JNIEnv * e, jobject o,
                                                 jint seId) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    bool stat = false;
    bool bRestartDiscovery = false;

    if (!sIsSecElemSelected) {
      LOG(ERROR) << StringPrintf("%s: already deselected", __func__);
      goto TheEnd2;
    }

    if (PowerSwitch::getInstance().getLevel() == PowerSwitch::LOW_POWER) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: do not deselect while power is OFF", __func__);
      //        sIsSecElemSelected = false;
      sIsSecElemSelected--;
      goto TheEnd;
    }

    if (sRfEnabled) {
      // Stop RF Discovery if we were polling
      startRfDiscovery(false);
      bRestartDiscovery = true;
    }
    // sIsSecElemSelected = false;
    // sIsSecElemSelected--;

    // if controller is not routing to sec elems AND there is no pipe connected,
    // then turn off the sec elems
    if (SecureElement::getInstance().isBusy() == false) {
      // SecureElement::getInstance().deactivate (0xABCDEF);
      stat = SecureElement::getInstance().deactivate(seId);
      if (stat) {
        sIsSecElemSelected--;
        //            RoutingManager::getInstance().commitRouting();
      }
    }

  TheEnd:
    /*
     * conditional check is added to avoid multiple dicovery cmds
     * at the time of NFC OFF in progress
     */
    if ((gGeneralPowershutDown != NFC_MODE_OFF) && bRestartDiscovery)
      startRfDiscovery(true);

    // if nothing is active after this, then tell the controller to power down
    if (!PowerSwitch::getInstance().setModeOff(PowerSwitch::SE_ROUTING))
      PowerSwitch::getInstance().setLevel(PowerSwitch::LOW_POWER);

  TheEnd2:
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
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
  static jint nfcManager_getDefaultAidRoute(JNIEnv * e, jobject o) {
    unsigned long num = 0;
#if (NXP_EXTNS == TRUE)
    if (NfcConfig::hasKey(NAME_DEFAULT_AID_ROUTE)) {
      num = NfcConfig::getUnsigned(NAME_DEFAULT_AID_ROUTE);
    }
#endif
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
  static jint nfcManager_getDefaultDesfireRoute(JNIEnv * e, jobject o) {
    unsigned long num = 0;
#if (NXP_EXTNS == TRUE)
    if (NfcConfig::hasKey(NAME_DEFAULT_ROUTE))
      num = NfcConfig::getUnsigned(NAME_DEFAULT_ROUTE);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: enter; NAME_DEFAULT_ROUTE = %02lx", __func__, num);
#endif
    return num;
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
  static jint nfcManager_getDefaultMifareCLTRoute(JNIEnv * e, jobject o) {
    unsigned long num = 0;
#if (NXP_EXTNS == TRUE)
    if (NfcConfig::hasKey(NAME_DEFAULT_OFFHOST_ROUTE))
      num = NfcConfig::getUnsigned(NAME_DEFAULT_OFFHOST_ROUTE);
#endif
    return num;
  }
#if (NXP_EXTNS == TRUE)
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
  static jint nfcManager_getDefaultAidPowerState(JNIEnv * e, jobject o) {
    unsigned long num = 0;
    if (NfcConfig::hasKey(NAME_DEFAULT_AID_PWR_STATE)) {
      num = NfcConfig::getUnsigned(NAME_DEFAULT_AID_PWR_STATE);
    }
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
  static jint nfcManager_getDefaultDesfirePowerState(JNIEnv * e, jobject o) {
    unsigned long num = 0;
    if (NfcConfig::hasKey(NAME_DEFAULT_ROUTE_PWR_STATE)) {
      num = NfcConfig::getUnsigned(NAME_DEFAULT_ROUTE_PWR_STATE);
    }
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
  static jint nfcManager_getDefaultMifareCLTPowerState(JNIEnv * e, jobject o) {
    unsigned long num = 0;
    if (NfcConfig::hasKey(NAME_DEFAULT_OFFHOST_PWR_STATE)) {
      num = NfcConfig::getUnsigned(NAME_DEFAULT_OFFHOST_PWR_STATE);
    }
    return num;
  }
  /*******************************************************************************
  **
  ** Function:        nfcManager_setDefaultTechRoute
  **
  ** Description:     Setting Default Technology Routing
  **                  e:  JVM environment.
  **                  o:  Java object.
  **                  seId:  SecureElement Id
  **                  tech_swithon:  technology switch_on
  **                  tech_switchoff:  technology switch_off
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManager_setDefaultTechRoute(JNIEnv * e, jobject o, jint seId,
                                             jint tech_switchon,
                                             jint tech_switchoff) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: ENTER", __func__);
    //    tNFA_STATUS status;                   /*commented to eliminate unused
    //    variable warning*/

    if (sRfEnabled) {
      // Stop RF Discovery if we were polling
      startRfDiscovery(false);
    }
    RoutingManager::getInstance().setDefaultTechRouting(seId, tech_switchon,
                                                        tech_switchoff);
    // start discovery.
    startRfDiscovery(true);
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_setDefaultProtoRoute
  **
  ** Description:     Setting Default Protocol Routing
  **
  **                  e:  JVM environment.
  **                  o:  Java object.
  **                  seId:  SecureElement Id
  **                  proto_swithon:  Protocol switch_on
  **                  proto_switchoff:  Protocol switch_off
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManager_setDefaultProtoRoute(JNIEnv * e, jobject o, jint seId,
                                              jint proto_switchon,
                                              jint proto_switchoff) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: ENTER", __func__);
    //    tNFA_STATUS status;                   /*commented to eliminate unused
    //    variable warning*/
    //    if (sRfEnabled) {
    //        // Stop RF Discovery if we were polling
    //        startRfDiscovery (false);
    //    }
    RoutingManager::getInstance().setDefaultProtoRouting(seId, proto_switchon,
                                                         proto_switchoff);
    // start discovery.
    //    startRfDiscovery (true);
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
  static int nfcManager_setPreferredSimSlot(JNIEnv * e, jobject o,
                                            jint uiccSlot) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s : uiccslot : %d : enter", __func__, uiccSlot);

    tNFA_STATUS status = NFA_STATUS_OK;
    if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) {
      sCurrentSelectedUICCSlot = uiccSlot;
      NFA_SetPreferredUiccId(
          (uiccSlot == 2) ? (SecureElement::getInstance().EE_HANDLE_0xF8 &
                             ~NFA_HANDLE_GROUP_EE)
                          : (SecureElement::getInstance().EE_HANDLE_0xF4 &
                             ~NFA_HANDLE_GROUP_EE));
    }
    return status;
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_isVzwFeatureEnabled
  **
  ** Description:     Check vzw feature is enabled or not
  **
  ** Returns:         True if the VZW_FEATURE_ENABLE is set.
  **
  *******************************************************************************/
  static bool nfcManager_isVzwFeatureEnabled(JNIEnv * e, jobject o) {
    unsigned int num = 0;
    bool mStat = false;

    if (NfcConfig::hasKey("VZW_FEATURE_ENABLE")) {
      mStat = NfcConfig::getUnsigned("VZW_FEATURE_ENABLE");
      if (num == 0x01) {
        mStat = true;
      } else {
        mStat = false;
      }
    } else {
      mStat = false;
    }
    return mStat;
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
    if (SecureElement::getInstance().isBusy()) {
      LOG(ERROR) << StringPrintf("%s:FAIL  SE wired-mode : busy", __func__);
      statBusy = true;
    } else if (rfActivation) {
      LOG(ERROR) << StringPrintf("%s:FAIL  RF session ongoing", __func__);
      statBusy = true;
    } else if (transaction_data.trans_in_progress) {
      LOG(ERROR) << StringPrintf("%s: FAIL Transaction in progress", __func__);
      statBusy = true;
    }

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Exit statBusy : 0x%02x", __func__, statBusy);
    return statBusy;
  }
#endif
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
  static bool isPeerToPeer(tNFA_ACTIVATED & activated) {
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
  static bool isListenMode(tNFA_ACTIVATED & activated) {
    return (
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
         activated.activate_ntf.rf_tech_param.mode) ||
        (NFC_INTERFACE_EE_DIRECT_RF == activated.activate_ntf.intf_param.type));
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

  static jboolean nfcManager_doCheckJcopDlAtBoot(JNIEnv * e, jobject o) {
    unsigned int num = 0;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", __func__);
    if (NfcConfig::hasKey(NAME_NXP_JCOPDL_AT_BOOT_ENABLE)) {
      num = NfcConfig::getUnsigned(NAME_NXP_JCOPDL_AT_BOOT_ENABLE);
      if (num == 0x01) {
        return JNI_TRUE;
      } else {
        return JNI_FALSE;
      }
    } else {
      return JNI_FALSE;
    }
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
  static void nfcManager_doAbort(JNIEnv * e, jobject, jstring msg) {
    ScopedUtfChars message = {e, msg};
    e->FatalError(message.c_str());
    abort();  // <-- Unreachable
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_doDownload
  **
  ** Description:     Download firmware patch files.  Do not turn on NFC.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  static jboolean nfcManager_doDownload(JNIEnv*, jobject) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    NfcAdaptation& theInstance = NfcAdaptation::GetInstance();

    theInstance.Initialize();  // start GKI, NCI task, NFC task
    theInstance.DownloadFirmware();
    theInstance.Finalize();
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
    return JNI_TRUE;
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
  static bool nfcManager_doSetTimeout(JNIEnv*, jobject, jint tech,
                                      jint timeout) {
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
  static void nfcManager_doDump(JNIEnv * e, jobject obj, jobject fdobj) {
    int fd = jniGetFDFromFileDescriptor(e, fdobj);
    if (fd < 0) return;

    NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
    theInstance.Dump(fd);
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
  static void nfcManager_doSetP2pInitiatorModes(JNIEnv * e, jobject o,
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

#if (NXP_EXTNS == TRUE)
  /*******************************************************************************
  **
  ** Function:        nfcManager_getRouting
  **
  ** Description:     Get Routing Table information.
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         Current routing Settings.
  **
  *******************************************************************************/
  static jbyteArray nfcManager_getRouting(JNIEnv * e, jobject o) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s : Enter", __func__);
    jbyteArray jbuff = NULL;
    if (sRfEnabled) {
      // Stop RF Discovery if we were polling
      startRfDiscovery(false);
    }
    SyncEventGuard guard(sNfaGetRoutingEvent);
    sRoutingBuffLen = 0;
    RoutingManager::getInstance().getRouting();
    sNfaGetRoutingEvent.wait();
    if (sRoutingBuffLen > 0) {
      jbuff = e->NewByteArray(sRoutingBuffLen);
      e->SetByteArrayRegion(jbuff, 0, sRoutingBuffLen, (jbyte*)sRoutingBuff);
    }

    startRfDiscovery(true);
    return jbuff;
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_getNfcInitTimeout
  **
  ** Description:     Gets the chip version.
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         timeout in seconds
  **
  *******************************************************************************/
  static int nfcManager_getNfcInitTimeout(JNIEnv * e, jobject o) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    unsigned long disc_timeout = 0;
    unsigned long session_id_timeout = 0;
    disc_timeout = 0;
    gNfcInitTimeout = 0;
    gdisc_timeout = 0;

    if (NfcConfig::hasKey(NAME_NXP_DEFAULT_NFCEE_DISC_TIMEOUT)) {
      disc_timeout =
          NfcConfig::getUnsigned(NAME_NXP_DEFAULT_NFCEE_DISC_TIMEOUT);
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NAME_NXP_DEFAULT_NFCEE_DISC_TIMEOUT not found");
      disc_timeout = 0;
    }

    if (NfcConfig::hasKey(NAME_NXP_DEFAULT_NFCEE_TIMEOUT)) {
      session_id_timeout =
          NfcConfig::getUnsigned(NAME_NXP_DEFAULT_NFCEE_TIMEOUT);
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NAME_NXP_DEFAULT_NFCEE_TIMEOUT not found");
      session_id_timeout = 0;
    }

    gNfcInitTimeout = (disc_timeout + session_id_timeout) * 1000;
    gdisc_timeout = disc_timeout * 1000;

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        " gNfcInitTimeout = %d: gdisc_timeout = %d "
        "nfcManager_getNfcInitTimeout",
        gNfcInitTimeout, gdisc_timeout);
    return gNfcInitTimeout;
  }

#endif

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

  static void nfcManager_doEnableScreenOffSuspend(JNIEnv * e, jobject o) {
    PowerSwitch::getInstance().setScreenOffPowerState(
        PowerSwitch::POWER_STATE_FULL);
  }

  static void nfcManager_doDisableScreenOffSuspend(JNIEnv * e, jobject o) {
    PowerSwitch::getInstance().setScreenOffPowerState(
        PowerSwitch::POWER_STATE_OFF);
  }
#if (NXP_EXTNS == TRUE)
  /*******************************************************************************
  **
  ** Function:        nfcManager_doUpdateScreenState
  **
  ** Description:     Update If any Pending screen state is present
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         None.
  **
  *******************************************************************************/
  static void nfcManager_doUpdateScreenState(JNIEnv * e, jobject o) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter ", __func__);
    if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
      eScreenState_t last_screen_state_request;

      if (pendingScreenState == true) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: pendingScreenState = true ", __func__);
        pendingScreenState = false;
        last_screen_state_request = get_lastScreenStateRequest();
        nfcManager_doSetScreenState(NULL, NULL, last_screen_state_request);
      } else {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: pendingScreenState = false ", __func__);
      }
    }
  }
/*******************************************************************************
 **
 ** Function:        restartUiccListen()
 **
 ** Description:     ConfigureUICC Listen techmask according to uiccslot.
 **
 **
 ** Returns:         null
 **
 *******************************************************************************/
static void restartUiccListen(jint uiccSlot) {
  tNFA_HANDLE handle = NFA_HANDLE_INVALID;
  unsigned long int num = 0;
  if (NfcConfig::hasKey(NAME_UICC_LISTEN_TECH_MASK)) {
  num = NfcConfig::getUnsigned(NAME_UICC_LISTEN_TECH_MASK);
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s:UICC_LISTEN_MASK=0x0%lu;", __func__, num);
  }
  {
    SyncEventGuard guard(sNfaEnableDisablePollingEvent);
    tNFA_STATUS status = NFA_StopRfDiscovery();
    if (status == NFA_STATUS_OK) {
      sNfaEnableDisablePollingEvent.wait();
    } else
      LOG(ERROR) << StringPrintf("%s: Failed to disable polling; error=0x%X",
                                 __FUNCTION__, status);
  }
  {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: restart UICC listen mode (%02lX)", __func__, (num & 0xC7));
    handle = (uiccSlot!=0x02)?
        SecureElement::getInstance().getEseHandleFromGenericId(SecureElement::UICC_ID):
        SecureElement::getInstance().getEseHandleFromGenericId(SecureElement::UICC2_ID);
    SyncEventGuard guard(SecureElement::getInstance().mUiccListenEvent);
    tNFA_STATUS stat = NFA_CeConfigureUiccListenTech(handle, 0x00);
    if (stat == NFA_STATUS_OK) {
      SecureElement::getInstance().mUiccListenEvent.wait();
    } else
      LOG(ERROR) << StringPrintf("fail to stop UICC listen");
  }
  {
    SyncEventGuard guard(SecureElement::getInstance().mUiccListenEvent);
    tNFA_STATUS stat = NFA_CeConfigureUiccListenTech(handle, (num & 0xC7));
    if (stat == NFA_STATUS_OK) {
      SecureElement::getInstance().mUiccListenEvent.wait();
    } else
      LOG(ERROR) << StringPrintf("fail to start UICC listen");
  }
}
  /*******************************************************************************
   **
   ** Function:        nfcManager_doSelectUicc()
   **
   ** Description:     Issue any single TLV set config command as per input
   ** register values and bit values
   **
   ** Returns:         success/failure
   **
   *******************************************************************************/
  static int nfcManager_doSelectUicc(JNIEnv * e, jobject o, jint uiccSlot) {
    (void)e;
    (void)o;
    uint8_t retStat = STATUS_UNKNOWN_ERROR;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
      tNFA_STATUS status = NFC_STATUS_FAILED;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: sUicc1CntxLen : 0x%02x  sUicc2CntxLen : 0x%02x", __func__,
          dualUiccInfo.sUicc1CntxLen, dualUiccInfo.sUicc2CntxLen);
      uint16_t RegAddr = 0xA0EC;
      uint8_t bitVal;
      eScreenState_t last_screen_state_request;
      dualUiccInfo.uiccConfigStat = UICC_NOT_CONFIGURED;

      RoutingManager& routingManager = RoutingManager::getInstance();
      SecureElement& se = SecureElement::getInstance();

      retStat = nfcManager_staticDualUicc_Precondition(uiccSlot);

      if (retStat != UICC_NOT_CONFIGURED) {
        goto endSwitch;
      }

      if (sRfEnabled) {
        startRfDiscovery(false);
      }
      restartUiccListen(uiccSlot);

      bitVal = ((0x10) | uiccSlot);

      getUiccContext(uiccSlot);

      if ((dualUiccInfo.sUicc1CntxLen != 0) ||
          (dualUiccInfo.sUicc2CntxLen != 0)) {
        if ((bitVal == 0x11) && (dualUiccInfo.sUicc1CntxLen != 0)) {
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
              "%s : update uicc1 context information ", __func__);
          uint8_t cfg[256] = {0x20, 0x02};

          memcpy(cfg + 3, dualUiccInfo.sUicc1Cntx, dualUiccInfo.sUicc1CntxLen);
          cfg[2] = dualUiccInfo.sUicc1CntxLen - 1;
          status = NxpNfc_Write_Cmd_Common(dualUiccInfo.sUicc1CntxLen + 2, cfg);

          memcpy(cfg + 3, dualUiccInfo.sUicc1TechCapblty, 10);
          cfg[2] = 9;
          status = NxpNfc_Write_Cmd_Common(12, cfg);

        } else if ((bitVal == 0x12) && (dualUiccInfo.sUicc2CntxLen != 0)) {
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
              "%s : update uicc2 context information", __func__);
          uint8_t cfg[256] = {0x20, 0x02};
          memcpy(cfg + 3, dualUiccInfo.sUicc2Cntx, dualUiccInfo.sUicc2CntxLen);
          cfg[2] = dualUiccInfo.sUicc2CntxLen - 1;
          status = NxpNfc_Write_Cmd_Common(dualUiccInfo.sUicc2CntxLen + 2, cfg);

          memcpy(cfg + 3, dualUiccInfo.sUicc2TechCapblty, 10);
          cfg[2] = 9;
          status = NxpNfc_Write_Cmd_Common(12, cfg);
        }
      }

      /*Update NFCC SWIO line accordingly*/
      if ((Set_EERegisterValue(RegAddr, bitVal) != NFCSTATUS_OK)) {
        retStat = DUAL_UICC_ERROR_SELECT_FAILED;
        LOG(ERROR) << StringPrintf("%s : Set_EERegisterValue Failed", __func__);
        goto endSwitch;
      }

      /*Mode Set Off for UICC*/
      {
        SyncEventGuard guard(routingManager.mEeSetModeEvent);
        if ((NFA_EeModeSet(0x02, NFA_EE_MD_DEACTIVATE)) == NFA_STATUS_OK) {
          routingManager.mEeSetModeEvent.wait();  // wait for
                                                  // NFA_EE_MODE_SET_EVT
        } else {
          LOG(ERROR) << StringPrintf("%s : Failed to set EE inactive",
                                     __func__);
          goto endSwitch;
        }
      }
      gSeDiscoverycount = 0;
      /*Perform HAL re-initialisation
       * NFA EE and HCI Subsystem de-init*/
      {
        SyncEventGuard guard(sNfceeHciCbDisableEvent);
        NFA_EE_HCI_Control(false);
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("sNfceeHciCbDisableEvent waiting ......");
        if (sNfceeHciCbDisableEvent.wait(500) == false) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("sNfceeHciCbDisableEvent.wait Timeout happened");
        } else {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("sNfceeHciCbDisableEvent.wait success");
        }
      }

      /*Reset Nfcc*/
      status = NFA_ResetNfcc();
      /*Perform NFA EE and HCI Subsystem initialisation*/
      {
        SyncEventGuard guard(sNfceeHciCbEnableEvent);
        NFA_EE_HCI_Control(true);
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("sNfceeHciCbEnableEvent waiting ......");
        if (sNfceeHciCbEnableEvent.wait(500) == false) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("sNfceeHciCbEnableEvent.wait Timeout happened");
        } else {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("sNfceeHciCbEnableEvent.wait success");
        }
      }

      {
        se.updateEEStatus();
        routingManager.initialize(getNative(e, o));
        HciRFParams::getInstance().initialize();
        sIsSecElemSelected = (se.getActualNumEe() - 1);
        sIsSecElemDetected = sIsSecElemSelected;
      }

      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s : gSeDiscoverycount = %d", __func__, gSeDiscoverycount);
      {
        SyncEventGuard g(gNfceeDiscCbEvent);
        /*Get the SWP1 and SWP2 lines status*/
        if (NFA_STATUS_OK == GetNumNFCEEConfigured()) {
          /*The SWP lines enabled and SE's discovered*/
          if (gSeDiscoverycount < gActualSeCount) {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                "%s : Wait for ESE to discover, gdisc_timeout = %d", __func__,
                gdisc_timeout);
            if (gNfceeDiscCbEvent.wait(gdisc_timeout) == false) {
              LOG(ERROR) << StringPrintf(
                  "%s: timeout waiting for nfcee dis event", __func__);
            }
          } else {
            DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf("%s : All ESE are discovered ", __func__);
          }
        }
      }
      /*Get the eSE and UICC parameters for RF*/
      checkforNfceeConfig(UICC1 | UICC2 | ESE);

      if (se.getEeStatus(se.EE_HANDLE_0xF4) == NFC_NFCEE_STATUS_REMOVED) {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s : UICC 0x%02x status : NFC_NFCEE_STATUS_REMOVED. Clearing "
            "buffer",
            __func__, sSelectedUicc);
        if ((sSelectedUicc == 0x01) && (dualUiccInfo.sUicc1CntxLen != 0x00)) {
          memset(dualUiccInfo.sUicc1Cntx, 0x00,
                 sizeof(dualUiccInfo.sUicc1Cntx));
          memset(dualUiccInfo.sUicc1TechCapblty, 0x00, 10);
          dualUiccInfo.sUicc1CntxLen = 0x00;
          write_uicc_context(
              dualUiccInfo.sUicc1Cntx, dualUiccInfo.sUicc1CntxLen,
              dualUiccInfo.sUicc1TechCapblty, 10, 1, sSelectedUicc);
        } else if ((sSelectedUicc == 0x02) &&
                   (dualUiccInfo.sUicc2CntxLen != 0x00)) {
          memset(dualUiccInfo.sUicc2Cntx, 0x00,
                 sizeof(dualUiccInfo.sUicc2Cntx));
          memset(dualUiccInfo.sUicc2TechCapblty, 0x00, 10);
          dualUiccInfo.sUicc2CntxLen = 0x00;
          write_uicc_context(
              dualUiccInfo.sUicc2Cntx, dualUiccInfo.sUicc2CntxLen,
              dualUiccInfo.sUicc2TechCapblty, 10, 1, sSelectedUicc);
        }
      }

      retStat = dualUiccInfo.uiccConfigStat;

    endSwitch:
      if ((retStat == UICC_CONFIGURED) || (retStat == UICC_NOT_CONFIGURED)) {
        pTransactionController->transactionEnd(
            TRANSACTION_REQUESTOR(staticDualUicc));
        /*Apply screen state if pending*/
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: Apply screen state if pending", __func__);
        if (pendingScreenState == true) {
          pendingScreenState = false;
          last_screen_state_request = get_lastScreenStateRequest();
          nfcManager_doSetScreenState(NULL, NULL, last_screen_state_request);
        } else {
          LOG(ERROR) << StringPrintf("%s: Can not reset transaction state",
                                     __func__);
        }
      }

      /*If retStat is success then routing table will be reconfigured from
       * NfcService
       * As a part of commitRouting startRfDiscovery will be called.
       * If retStat is failed then NfcService will not reconfigured routing
       * table
       * So do startRfDiscovery here*/
      if ((retStat != UICC_CONFIGURED) && (retStat != UICC_NOT_CONFIGURED) &&
          (!sRfEnabled)) {
        startRfDiscovery(true);
      }

    } else if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) {
      retStat = nfcManager_staticDualUicc_Precondition(uiccSlot);

      if (retStat != UICC_NOT_CONFIGURED) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("staticDualUicc_Precondition failed.");
        return retStat;
      }

      restartUiccListen(uiccSlot);
      nfcManager_setPreferredSimSlot(NULL, NULL, uiccSlot);
      retStat = UICC_CONFIGURED;
      RoutingManager::getInstance().cleanRouting();
      pTransactionController->transactionEnd(
          TRANSACTION_REQUESTOR(staticDualUicc));
    } else {
      retStat = DUAL_UICC_FEATURE_NOT_AVAILABLE;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: Dual uicc not supported retStat = %d", __func__, retStat);
    }
    DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: exit retStat = %d", __func__, retStat);
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
    if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter ", __func__);
      uicc_stat = SecureElement::getInstance().getUiccStatus(sSelectedUicc);
    } else if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) {
      uicc_stat =
          SecureElement::getInstance().getUiccStatus(sCurrentSelectedUICCSlot);
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: dual uicc not supported ", __func__);
      uicc_stat = DUAL_UICC_FEATURE_NOT_AVAILABLE;
    }
    return uicc_stat;
  }
#endif

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

    {"doRouteAid", "([BIII)Z", (void*)nfcManager_routeAid},

    {"doUnrouteAid", "([B)Z", (void*)nfcManager_unrouteAid},

    {"doSetRoutingEntry", "(IIII)Z", (void*)nfcManager_setRoutingEntry},

    {"doClearRoutingEntry", "(I)Z", (void*)nfcManager_clearRoutingEntry},

    {"clearAidTable", "()Z", (void*)nfcManager_clearAidTable},

    {"setDefaultRoute", "(III)Z", (void*)nfcManager_setDefaultRoute},

    {"getAidTableSize", "()I", (void*)nfcManager_getAidTableSize},

    {"getRemainingAidTableSize", "()I",
     (void*)nfcManager_getRemainingAidTableSize},

    {"getDefaultAidRoute", "()I", (void*)nfcManager_getDefaultAidRoute},

    {"getDefaultDesfireRoute", "()I", (void*)nfcManager_getDefaultDesfireRoute},

    {"getDefaultMifareCLTRoute", "()I",
     (void*)nfcManager_getDefaultMifareCLTRoute},
    {"readerPassThruMode", "(BB)[B", (void*)nfcManager_readerPassThruMode},
    {"transceiveAppData", "([B)[B", (void*)nfcManager_transceiveAppData},
#if (NXP_EXTNS == TRUE)
    {"getDefaultAidPowerState", "()I",
     (void*)nfcManager_getDefaultAidPowerState},

    {"getDefaultDesfirePowerState", "()I",
     (void*)nfcManager_getDefaultDesfirePowerState},

    {"getDefaultMifareCLTPowerState", "()I",
     (void*)nfcManager_getDefaultMifareCLTPowerState},
#endif
    {"doRegisterT3tIdentifier", "([B)I",
     (void*)nfcManager_doRegisterT3tIdentifier},

    {"doDeregisterT3tIdentifier", "(I)V",
     (void*)nfcManager_doDeregisterT3tIdentifier},

    {"getLfT3tMax", "()I", (void*)nfcManager_getLfT3tMax},

    {"doEnableDiscovery", "(IZZZZ)V", (void*)nfcManager_enableDiscovery},

    {"doGetSecureElementList", "()[I",
     (void*)nfcManager_doGetSecureElementList},

    {"doSelectSecureElement", "(I)V", (void*)nfcManager_doSelectSecureElement},

    {"doActivateSecureElement", "(I)V",
     (void*)nfcManager_activateSecureElement},

    {"doDeselectSecureElement", "(I)V",
     (void*)nfcManager_doDeselectSecureElement},

    {"doSetSEPowerOffState", "(IZ)V", (void*)nfcManager_doSetSEPowerOffState},
    {"setDefaultTechRoute", "(III)V", (void*)nfcManager_setDefaultTechRoute},

    {"setDefaultProtoRoute", "(III)V", (void*)nfcManager_setDefaultProtoRoute},

    {"GetDefaultSE", "()I", (void*)nfcManager_GetDefaultSE},

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

    {"doDisableScreenOffSuspend", "()V",
     (void*)nfcManager_doDisableScreenOffSuspend},

    {"doDump", "(Ljava/io/FileDescriptor;)V", (void*)nfcManager_doDump},

    {"getChipVer", "()I", (void*)nfcManager_getChipVer},

    {"JCOSDownload", "()I", (void*)nfcManager_doJcosDownload},
    {"doCommitRouting", "()V", (void*)nfcManager_doCommitRouting},
#if (NXP_EXTNS == TRUE)
    {"doSetNfcMode", "(I)V", (void*)nfcManager_doSetNfcMode},

    {"setTransitConfig", "(Ljava/lang/String;)I",
     (void*)nfcManager_setTransitConfig},

#endif
    {"doGetSecureElementTechList", "()I",
     (void*)nfcManager_getSecureElementTechList},

    {"doGetActiveSecureElementList", "()[I",
     (void*)nfcManager_getActiveSecureElementList},

    {"doGetSecureElementUid", "()[B", (void*)nfcManager_getSecureElementUid},

    {"setEmvCoPollProfile", "(ZI)I", (void*)nfcManager_setEmvCoPollProfile},

    {"doSetSecureElementListenTechMask", "(I)V",
     (void*)nfcManager_setSecureElementListenTechMask},
    {"getNciVersion", "()I", (void*)nfcManager_doGetNciVersion},
    {"doSetScreenState", "(I)V", (void*)nfcManager_doSetScreenState},
    {"doSetScreenOrPowerState", "(I)V",
     (void*)nfcManager_doSetScreenOrPowerState},
    // Factory Test Code
    {"doPrbsOn", "(IIII)V", (void*)nfcManager_doPrbsOn},
    {"doPrbsOff", "()V", (void*)nfcManager_doPrbsOff},
    // SWP self test
    {"SWPSelfTest", "(I)I", (void*)nfcManager_SWPSelfTest},
    // check firmware version
    {"getFWVersion", "()I", (void*)nfcManager_getFwVersion},
#if (NXP_EXTNS == TRUE)
    {"updateScreenState", "()V", (void*)nfcManager_doUpdateScreenState},
    {"doEnablep2p", "(Z)V", (void*)nfcManager_Enablep2p},
    {"doSetProvisionMode", "(Z)V", (void*)nfcManager_setProvisionMode},
    {"doGetRouting", "()[B", (void*)nfcManager_getRouting},
    {"getNfcInitTimeout", "()I", (void*)nfcManager_getNfcInitTimeout},
    {"isVzwFeatureEnabled", "()Z", (void*)nfcManager_isVzwFeatureEnabled},
    {"isNfccBusy", "()Z", (void*)nfcManager_isNfccBusy},
#endif
    {"doSetEEPROM", "([B)V", (void*)nfcManager_doSetEEPROM},
    {"doGetSeInterface", "(I)I", (void*)nfcManager_doGetSeInterface},
    // Factory Test Code
    {"doCheckJcopDlAtBoot", "()Z", (void*)nfcManager_doCheckJcopDlAtBoot},
    {"doEnableDtaMode", "()V", (void*)nfcManager_doEnableDtaMode},
    {"doDisableDtaMode", "()V", (void*)nfcManager_doDisableDtaMode},
    {"doFactoryReset", "()V", (void*)nfcManager_doFactoryReset},
    {"doShutdown", "()V", (void*)nfcManager_doShutdown},
    {"getIsoDepMaxTransceiveLength", "()I",
     (void*)nfcManager_getIsoDepMaxTransceiveLength}
#if (NXP_EXTNS == TRUE)
    ,
    {"doselectUicc", "(I)I", (void*)nfcManager_doSelectUicc},
    {"doGetSelectedUicc", "()I", (void*)nfcManager_doGetSelectedUicc},
    {"setPreferredSimSlot", "(I)I", (void*)nfcManager_setPreferredSimSlot},
    {"routeApduPattern", "(II[B[B)Z", (void*)nfcManager_routeApduPattern},
    {"unrouteApduPattern", "([B)Z", (void*)nfcManager_unrouteApduPattern},
    {"doNfcSelfTest", "(I)I", (void*)nfcManager_nfcSelfTest}
#endif
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
  int register_com_android_nfc_NativeNfcManager(JNIEnv * e) {
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
#if (NXP_EXTNS == TRUE)
    tNFA_STATUS status = NFA_STATUS_FAILED;
    if (sAutonomousSet == 1) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "Autonomous mode set don't start RF disc %d", isStart);
      return;
    }
    if ((!gsRouteUpdated) && isStart) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: Routing table update pending.Can not start RF disc. Returning..",
          __FUNCTION__);
      return;
    }
    if (isStart == sRfEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s Already in RF state: %d", __FUNCTION__, isStart);
      return;
    }
#endif
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: is start=%d", __func__, isStart);
    nativeNfcTag_acquireRfInterfaceMutexLock();
    SyncEventGuard guard(sNfaEnableDisablePollingEvent);
    status = isStart ? NFA_StartRfDiscovery() : NFA_StopRfDiscovery();
    if (status == NFA_STATUS_OK) {
      if (gGeneralPowershutDown == NFC_MODE_OFF) sDiscCmdwhleNfcOff = true;
      se_rd_req_state_t state =
          MposManager::getInstance().getEtsiReaederState();
      if (state == STATE_SE_RDR_MODE_STOP_IN_PROGRESS ||
          state == STATE_SE_RDR_MODE_ACTIVATED) {
        sNfaEnableDisablePollingEvent
            .wait();  // wait for NFA_RF_DISCOVERY_xxxx_EVT
      } else {
        sNfaEnableDisablePollingEvent.wait(
            NFC_CMD_TIMEOUT);  // wait for NFA_RF_DISCOVERY_xxxx_EVT
      }
      sRfEnabled = isStart;
      sDiscCmdwhleNfcOff = false;
    } else {
      LOG(ERROR) << StringPrintf(
          "%s: Failed to start/stop RF discovery; error=0x%X", __func__,
          status);
    }
    nativeNfcTag_releaseRfInterfaceMutexLock();
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: is exit=%d", __func__, isStart);
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
  ** Function:        notifyPollingEventwhileNfcOff
  **
  ** Description:     Notifies sNfaEnableDisablePollingEvent if tag operations
  **                  is in progress at the time Nfc Off is in progress to avoid
  **                  NFC off thread infinite block.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void notifyPollingEventwhileNfcOff() {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: sDiscCmdwhleNfcOff=%x", __func__, sDiscCmdwhleNfcOff);
    if (sDiscCmdwhleNfcOff == true) {
      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      sNfaEnableDisablePollingEvent.notifyOne();
    }
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
  ** Description:     Used externaly to determine if NFC is deinit is ongoing or
  *not.
  **
  ** Returns:         'true' if the NFC deinit is running, else 'false'.
  **
  *******************************************************************************/
  bool nfcManager_isNfcDisabling() { return sIsDisabling; }

  /*******************************************************************************
  **
  ** Function:        nfcManager_getNfcState
  **
  ** Description:     Used internally and externally to check the current NFC
  *state
  **
  ** Returns:         Returns the current state of NFC.
  **
  *******************************************************************************/
  uint8_t nfcManager_getNfcState() {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: sNfcState = %d", __func__, sNfcState);
    return sNfcState;
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
      LOG(ERROR) << StringPrintf("%s: fail enable polling; error=0x%X",
                                 __func__, stat);
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
      LOG(ERROR) << StringPrintf("%s: fail disable polling; error=0x%X",
                                 __func__, stat);
    }

    nativeNfcTag_releaseRfInterfaceMutexLock();

    return stat;
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_getChipVer
  **
  ** Description:     Gets the chip version.
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         None      0x00
  **                  PN547C2   0x01
  **                  PN65T     0x02 .
  **
  *******************************************************************************/
  static int nfcManager_getChipVer(JNIEnv * e, jobject o) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    unsigned long num = 0;

    if (NfcConfig::hasKey(NAME_NXP_NFC_CHIP)) {
      num = NfcConfig::getUnsigned(NAME_NXP_NFC_CHIP);
    }
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%ld: nfcManager_getChipVer", num);
    return num;
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_setTransitConfig
  **
  ** Description:     Set Transit Configuration
  **                  e: JVM environment.
  **                  o: Java object.
  **                  config: Jstring.
  **
  ** Returns:         Success      0x00
  **                  Failed       0x01
  **
  *******************************************************************************/
  static int nfcManager_setTransitConfig(JNIEnv * e, jobject o,
                                         jstring config) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    std::string transitConfig = ConvertJavaStrToStdString(e, config);
    SyncEventGuard guard(sNfaTransitConfigEvent);
    int stat = NFA_SetTransitConfig(transitConfig);
    sNfaTransitConfigEvent.wait(10 * ONE_SECOND_MS);
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
  ** Function:        DWPChannel_init
  **
  ** Description:     Initializes the DWP channel functions.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  void DWPChannel_init(IChannel_t * DWP) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    if (nfcFL.nfcNxpEse) {
      DWP->open = open;
      DWP->close = close;
      DWP->transceive = transceive;
      DWP->doeSE_Reset = doeSE_Reset;
      DWP->doeSE_JcopDownLoadReset = doeSE_JcopDownLoadReset;
    }
  }
  /*******************************************************************************
  **
  ** Function:        nfcManager_doJcosDownload
  **
  ** Description:     start jcos download.
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  static int nfcManager_doJcosDownload(JNIEnv * e, jobject o) {
    (void)e;
    (void)o;
    tNFA_STATUS status = NFA_STATUS_FAILED;
#if (NXP_EXTNS == TRUE)
    if (nfcFL.nfcNxpEse) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
      bool stat = false;
      int ret_val = -1;
      NFCSTATUS ese_status = NFA_STATUS_FAILED;
      p61_access_state_t p61_current_state = P61_STATE_INVALID;
      eScreenState_t last_screen_state_request = get_lastScreenStateRequest();
      SecureElement& se = SecureElement::getInstance();

      if (nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION) {
        if (sIsDisabling || !sIsNfaEnabled || nfcManager_checkNfcStateBusy()) {
          return NFA_STATUS_FAILED;
        }

        ret_val = NFC_GetP61Status((void*)&p61_current_state);
        if (ret_val < 0) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("NFC_GetP61Status failed");
          return NFA_STATUS_FAILED;
        }
        if (p61_current_state & P61_STATE_JCP_DWNLD ||
            p61_current_state & P61_STATE_WIRED ||
            p61_current_state & P61_STATE_SPI ||
            p61_current_state & P61_STATE_SPI_PRIO) {
          return NFA_STATUS_BUSY;
        }

        if (sIsDisabling || !sIsNfaEnabled || nfcManager_checkNfcStateBusy()) {
          return NFA_STATUS_FAILED;
        }

        LOG(ERROR) << StringPrintf("%s: start JcopOs_Download 0x%X", __func__,
                                   p61_current_state);

        ret_val = NFC_SetP61Status((void*)&ese_status, JCP_DWNLD_START);
        if (ret_val < 0) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("NFC_SetP61Status failed");
        } else {
          if (ese_status != NFCSTATUS_SUCCESS) {
            DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf("Denying to set Jcop OS Download state");
          } else {
            if (!pTransactionController->transactionAttempt(
                    TRANSACTION_REQUESTOR(jcosDownload))) {
              LOG(ERROR) << StringPrintf(
                  "%s: Transaction in progress. Returning", __func__);
              return NFA_STATUS_FAILED;
            }
          }
        }
      }
      if (sRfEnabled) {
        // Stop RF Discovery if we were polling
        startRfDiscovery(false);
      }
      DWPChannel_init(&Dwp);
      status = pJcopMgr->JCDnldInit(&Dwp);
      if (status != NFA_STATUS_OK) {
        LOG(ERROR) << StringPrintf("%s: JCDND initialization failed", __func__);
      } else {
        LOG(ERROR) << StringPrintf("%s: start JcopOs_Download", __func__);
        se.mDownloadMode = JCOP_DOWNLOAD;
        status = pJcopMgr->JCDnldStartDownload();
      }
      if (nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION) {
        ret_val = NFC_SetP61Status((void*)&ese_status, JCP_DWP_DWNLD_COMPLETE);
        if (ret_val < 0) {
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
              "NFC_SetP61Status failed Deinit and starting discovery");
        } else {
          if (ese_status != NFCSTATUS_SUCCESS) {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                "Denying to set Jcop OS Download complete state");
            status = ese_status;
          }
        }
      }
      stat = pJcopMgr->JCDnldDeInit();
      if (nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION) {
        pTransactionController->transactionEnd(
            TRANSACTION_REQUESTOR(jcosDownload));
        if (pendingScreenState == true) {
          pendingScreenState = false;
          last_screen_state_request = get_lastScreenStateRequest();
          nfcManager_doSetScreenState(NULL, NULL, last_screen_state_request);
        }
      }
      startRfDiscovery(true);
      se.mDownloadMode = NONE;
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: exit; status =0x%X", __func__, status);
    } else {
      status = 0x0F;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: No p61", __func__);
    }
#endif
    return status;
  }

#if (NXP_EXTNS == TRUE)
  uint8_t getJCOPOS_UpdaterState() {
    static const char fn[] = "getJCOPOS_UpdaterState";
    if (!nfcFL.nfcNxpEse) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: nfcNxpEse not Enabled. Returning.", fn);
      return OSU_NOT_STARTED;
    }
    FILE* fp;
    unsigned int val = 0;
    uint8_t state = 0;
    int32_t result = 0;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", fn);

    fp = fopen(JCOP_INFO_PATH, "r");
    if (fp != NULL) {
      result = fscanf(fp, "%u", &val);
      if (result != 0) {
        state = (uint8_t)(val & 0x000000FF);
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("JcopOsState %d", state);
      }
      fclose(fp);
    } else {
      LOG(ERROR) << StringPrintf("file <%s> not exits for reading-: %s",
                                 JCOP_INFO_PATH, strerror(errno));
    }
    return state;
  }
#endif

  static void nfcManager_doCommitRouting(JNIEnv * e, jobject o) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    PowerSwitch::getInstance().setLevel(PowerSwitch::FULL_POWER);
    PowerSwitch::getInstance().setModeOn(PowerSwitch::HOST_ROUTING);
#if (NXP_EXTNS == TRUE && NXP_NFCC_HCE_F == TRUE)
    if (!pTransactionController->transactionAttempt(
            TRANSACTION_REQUESTOR(commitRouting))) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: Not allowing to commit the routing", __func__);
    } else {
#endif
      if (sRfEnabled) {
        /*Stop RF discovery to reconfigure*/
        startRfDiscovery(false);
      }
      RoutingManager::getInstance().commitRouting();
      startRfDiscovery(true);
#if (NXP_EXTNS == TRUE && NXP_NFCC_HCE_F == TRUE)
      pTransactionController->transactionEnd(
          TRANSACTION_REQUESTOR(commitRouting));
    }
#endif
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  }
#if (NXP_EXTNS == TRUE)
  static void nfcManager_doSetNfcMode(JNIEnv * e, jobject o, jint nfcMode) {
    /* Store the shutdown state */
    gGeneralPowershutDown = nfcMode;
  }
#endif
  bool isNfcInitializationDone() {
    if (nfcFL.nfccFL._NFCEE_REMOVED_NTF_RECOVERY) {
      return sIsNfaEnabled;
    } else {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: NFCEE_REMOVED_NTF_RECOVERY not enabled. Returning", __func__);
      return false;
    }
  }
  /*******************************************************************************
  **
  ** Function:        StoreScreenState
  **
  ** Description:     Sets  screen state
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void StoreScreenState(int state) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    screenstate = state;
    nfc_ncif_storeScreenState(state);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  }

  /*******************************************************************************
  **
  ** Function:        getScreenState
  **
  ** Description:     returns screen state
  **
  ** Returns:         int
  **
  *******************************************************************************/
  int getScreenState() { return screenstate; }

  /*******************************************************************************
  **
  ** Function:        isp2pActivated
  **
  ** Description:     returns p2pActive state
  **
  ** Returns:         bool
  **
  *******************************************************************************/
  bool isp2pActivated() {
    if (nfcFL.nfcNxpEse) {
      return sP2pActive;
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: nfcNxpEse not set. Returning", __func__);
      return false;
    }
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_doGetNciVersion
  **
  ** Description:     get the nci version.
  **
  ** Returns:         int
  **
  *******************************************************************************/
  static jint nfcManager_doGetNciVersion(JNIEnv*, jobject) {
    return NFC_GetNCIVersion();
  }
  /*******************************************************************************
  **
  ** Function:        nfcManager_doSetScreenState
  **
  ** Description:     Set screen state
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManager_doSetScreenState(JNIEnv * e, jobject o,
                                          jint screen_state_mask) {
    tNFA_STATUS status = NFA_STATUS_OK;
    unsigned long auto_num = 0;
    uint8_t discovry_param =
        NCI_LISTEN_DH_NFCEE_ENABLE_MASK | NCI_POLLING_DH_ENABLE_MASK;
    uint8_t state = (screen_state_mask & NFA_SCREEN_STATE_MASK);

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Enter state = %d", __func__, state);

    if (sIsDisabling || !sIsNfaEnabled) {
      return;
    }

#if (NXP_EXTNS == TRUE)
    if (!pTransactionController->transactionAttempt(
            TRANSACTION_REQUESTOR(setScreenState))) {
      LOG(ERROR) << StringPrintf("Payment is in progress!!!");
      if (state != getScreenState()) {
        set_lastScreenStateRequest((eScreenState_t)state);
        pendingScreenState = true;
      }
      return;
    }
#endif
    pendingScreenState = false;
    int prevScreenState = getScreenState();
    if (prevScreenState == state &&
        get_lastScreenStateRequest() == NFA_SCREEN_STATE_UNKNOWN) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Screen state is not changed. ");
#if (NXP_EXTNS == TRUE)
      pTransactionController->transactionEnd(
          TRANSACTION_REQUESTOR(setScreenState));
#endif
      return;
    }
    if (NFC_GetNCIVersion() == NCI_VERSION_2_0) {
      if (prevScreenState == NFA_SCREEN_STATE_OFF_LOCKED ||
          prevScreenState == NFA_SCREEN_STATE_OFF_UNLOCKED ||
          prevScreenState == NFA_SCREEN_STATE_ON_LOCKED) {
        SyncEventGuard guard(sNfaSetPowerSubState);
        status = NFA_SetPowerSubStateForScreenState(state);
        if (status != NFA_STATUS_OK) {
          LOG(ERROR) << StringPrintf(
              "%s: fail enable SetScreenState; error=0x%X", __FUNCTION__,
              status);
        } else {
          sNfaSetPowerSubState.wait();
        }
      }

      if (state == NFA_SCREEN_STATE_OFF_LOCKED ||
          state == NFA_SCREEN_STATE_OFF_UNLOCKED) {
        // disable both poll and listen on DH 0x02
        discovry_param =
            NCI_POLLING_DH_DISABLE_MASK | NCI_LISTEN_DH_NFCEE_DISABLE_MASK;
      }

      if (state == NFA_SCREEN_STATE_ON_LOCKED) {
        // disable poll and enable listen on DH 0x00
        discovry_param =
            (screen_state_mask & NFA_SCREEN_POLLING_TAG_MASK)
                ? (NCI_LISTEN_DH_NFCEE_ENABLE_MASK | NCI_POLLING_DH_ENABLE_MASK)
                : (NCI_POLLING_DH_DISABLE_MASK |
                   NCI_LISTEN_DH_NFCEE_ENABLE_MASK);
      }

      if (state == NFA_SCREEN_STATE_ON_UNLOCKED) {
        // enable both poll and listen on DH 0x01
        discovry_param =
            NCI_LISTEN_DH_NFCEE_ENABLE_MASK | NCI_POLLING_DH_ENABLE_MASK;
      }

      SyncEventGuard guard(sNfaSetConfigEvent);
      status =
          NFA_SetConfig(NCI_PARAM_ID_CON_DISCOVERY_PARAM,
                        NCI_PARAM_LEN_CON_DISCOVERY_PARAM, &discovry_param);
      if (status == NFA_STATUS_OK) {
        sNfaSetConfigEvent.wait();
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: Disabled RF field events", __FUNCTION__);
      } else {
        LOG(ERROR) << StringPrintf("%s: Failed to disable RF field events",
                                   __FUNCTION__);
        return;
      }

      if (prevScreenState == NFA_SCREEN_STATE_ON_UNLOCKED) {
        SyncEventGuard guard(sNfaSetPowerSubState);
        status = NFA_SetPowerSubStateForScreenState(state);
        if (status != NFA_STATUS_OK) {
          LOG(ERROR) << StringPrintf(
              "%s: fail enable SetScreenState; error=0x%X", __FUNCTION__,
              status);
        } else {
          sNfaSetPowerSubState.wait();
        }
      }
      if ((state == NFA_SCREEN_STATE_OFF_LOCKED ||
            state == NFA_SCREEN_STATE_OFF_UNLOCKED) &&
            prevScreenState == NFA_SCREEN_STATE_ON_UNLOCKED) {
            // screen turns off, disconnect tag if connected
          nativeNfcTag_doDisconnect(NULL, NULL);
      }

      StoreScreenState(state);
#if (NXP_EXTNS == TRUE)
      pTransactionController->transactionEnd(
          TRANSACTION_REQUESTOR(setScreenState));
#endif
      return;
    }
    if (state) {
      if (sRfEnabled) {
        // Stop RF discovery to reconfigure
        startRfDiscovery(false);
      }

      if (state == NFA_SCREEN_STATE_OFF_UNLOCKED ||
          state == NFA_SCREEN_STATE_OFF_LOCKED ||
          state == NFA_SCREEN_STATE_ON_LOCKED) {
        SyncEventGuard guard(sNfaEnableDisablePollingEvent);
        status = NFA_DisablePolling();
        if (status == NFA_STATUS_OK) {
          sNfaEnableDisablePollingEvent
              .wait();  // wait for NFA_POLL_DISABLED_EVT
        } else
          LOG(ERROR) << StringPrintf(
              "%s: Failed to disable polling; error=0x%X", __func__, status);
      }

      if (NfcConfig::hasKey(NAME_NXP_CORE_SCRN_OFF_AUTONOMOUS_ENABLE)) {
        auto_num =
            NfcConfig::getUnsigned(NAME_NXP_CORE_SCRN_OFF_AUTONOMOUS_ENABLE);
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s: enter; NAME_NXP_CORE_SCRN_OFF_AUTONOMOUS_ENABLE = %02lx",
            __func__, auto_num);
      }

      status = SetScreenState(state);
      if (status != NFA_STATUS_OK) {
        LOG(ERROR) << StringPrintf("%s: fail enable SetScreenState; error=0x%X",
                                   __func__, status);
      } else {
        if (((prevScreenState == NFA_SCREEN_STATE_OFF_LOCKED ||
              prevScreenState == NFA_SCREEN_STATE_OFF_UNLOCKED) &&
             state == NFA_SCREEN_STATE_ON_LOCKED) ||
#if (NXP_EXTNS == TRUE)
            (prevScreenState == NFA_SCREEN_STATE_ON_LOCKED &&
             state == NFA_SCREEN_STATE_ON_UNLOCKED && sProvisionMode) ||
#endif
            (prevScreenState == NFA_SCREEN_STATE_ON_LOCKED &&
             (state == NFA_SCREEN_STATE_OFF_LOCKED ||
              state == NFA_SCREEN_STATE_OFF_UNLOCKED) &&
             sIsSecElemSelected)) {
          if (auto_num != 0x01) {
            DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf("Start RF discovery");
            startRfDiscovery(true);
          }
        }
        StoreScreenState(state);
      }
      if (sAutonomousSet == 1) {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Send Core reset");
        NxpNfc_Send_CoreResetInit_Cmd();
      }
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: auto_num : %lu  sAutonomousSet : %d  sRfFieldOff : %d", __func__,
          auto_num, sAutonomousSet, sRfFieldOff);
      if ((auto_num == 0x01) && (sAutonomousSet != 1) &&
          (sRfFieldOff == true) && (state == NFA_SCREEN_STATE_OFF_LOCKED ||
                                    state == NFA_SCREEN_STATE_OFF_UNLOCKED)) {

        status = SendAutonomousMode(state, 0x01);
        sAutonomousSet = 1;
      } else {
        sAutonomousSet = 0;
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "Not sending AUTONOMOUS command state is %d", state);
        if (!sRfEnabled) {
          // Start RF discovery if not
          startRfDiscovery(true);
        }
      }
    }
#if (NXP_EXTNS == TRUE)
    pTransactionController->transactionEnd(
        TRANSACTION_REQUESTOR(setScreenState));
#endif
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Exit", __func__);
  }
#if (NXP_EXTNS == TRUE)
  /*******************************************************************************
  **
  ** Function:        nfcManager_doSetScreenOrPowerState
  **                  This function combines both screen state and power
  *state(ven power) values.
  **
  ** Description:     Set screen or power state
  **                  e: JVM environment.
  **                  o: Java object.
  **                  state:represents power or screen state (0-3 screen
  *state),6 (power on),7(power off)
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManager_doSetScreenOrPowerState(JNIEnv * e, jobject o,
                                                 jint state) {
    LOG(ERROR) << StringPrintf("%s: Enter", __func__);
    if ((state & NFA_SCREEN_STATE_MASK) <=
        NFA_SCREEN_STATE_ON_UNLOCKED)  // SCREEN_STATE
      nfcManager_doSetScreenState(e, o, state);
    else if ((state & NFA_SCREEN_STATE_MASK) ==
             VEN_POWER_STATE_ON)  // POWER_ON NFC_OFF
    {
      nfcManager_doSetNfcMode(e, o, NFC_MODE_OFF);
    } else if ((state & NFA_SCREEN_STATE_MASK) ==
               VEN_POWER_STATE_OFF)  // POWER_OFF
    {
      if (sIsNfaEnabled) {
        if (nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION &&
            (SecureElement::getInstance().mDownloadMode == JCOP_DOWNLOAD)) {
          DwpChannel::getInstance().forceClose();
        }
        nfcManager_doSetNfcMode(e, o, NFC_MODE_ON);  // POWER_OFF NFC_ON
      } else {
        nfcManager_doSetNfcMode(e, o, NFC_MODE_OFF);  // POWER_OFF NFC_OFF
      }
    } else
      LOG(ERROR) << StringPrintf("%s: unknown screen or power state. state=%d",
                                 __func__, state);
  }
  /*******************************************************************************
   **
   ** Function:        nfcManager_isRequestPending()
   **
   ** Description:     Checks If any pending request
   **
   ** Returns:         true if any request pending else false
   **
   *******************************************************************************/
  bool nfcManager_isRequestPending(void) {
    bool isPending = false;
    if ((transaction_data.current_transcation_state !=
         NFA_TRANS_DM_RF_TRANS_END) &&
        ((pendingScreenState == true) || (get_last_request() != 0x00))) {
      isPending = true;
    }
    return isPending;
  }
#endif
  /*******************************************************************************
   **
   ** Function:       get_last_request
   **
   ** Description:    returns the last enable/disable discovery event
   **
   ** Returns:        last request (char) .
   **
   *******************************************************************************/
  static char get_last_request() { return (transaction_data.last_request); }
  /*******************************************************************************
   **
   ** Function:       set_last_request
   **
   ** Description:    stores the last enable/disable discovery event
   **
   ** Returns:        None .
   **
   *******************************************************************************/
  static void set_last_request(char status, struct nfc_jni_native_data* nat) {
    if ((status == ENABLE_DISCOVERY) || (status == DISABLE_DISCOVERY)) {
      transaction_data.last_request &= CLEAR_ENABLE_DISABLE_PARAM;
    }
#if (NXP_EXTNS == TRUE && NXP_NFCC_HCE_F == TRUE)
    transaction_data.last_request |= status;
#else
  transaction_data.last_request = status;
#endif
    if (nat != NULL) {
      transaction_data.transaction_nat = nat;
    }
  }
  /*******************************************************************************
   **
   ** Function:       get_lastScreenStateRequest
   **
   ** Description:    returns the last screen state request
   **
   ** Returns:        last screen state request event (eScreenState_t) .
   **
   *******************************************************************************/
  static eScreenState_t get_lastScreenStateRequest() {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: %d", __func__, transaction_data.last_screen_state_request);
    return (transaction_data.last_screen_state_request);
  }

  /*******************************************************************************
   **
   ** Function:       set_lastScreenStateRequest
   **
   ** Description:    stores the last screen state request
   **
   ** Returns:        None .
   **
   *******************************************************************************/
  static void set_lastScreenStateRequest(eScreenState_t status) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: current=%d, new=%d", __func__,
                        transaction_data.last_screen_state_request, status);
    transaction_data.last_screen_state_request = status;
  }

/*******************************************************************************
**
** Function:        switchBackTimerProc_transaction
**
** Description:     Callback function for interval timer.
**
** Returns:         None
**
*******************************************************************************/
#if 0
static void cleanupTimerProc_transaction(union sigval)
{
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Inside cleanupTimerProc");
    cleanup_timer();
}

void cleanup_timer()
{
DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Inside cleanup");
    pthread_t transaction_thread;
    int irret = -1;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", __func__);

    /* Transcation is done process the last request*/
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    irret = pthread_create(&transaction_thread, &attr, enableThread, NULL);
    if(irret != 0)
    {
        LOG(ERROR) << StringPrintf("Unable to create the thread");
    }
    pthread_attr_destroy(&attr);
    transaction_data.current_transcation_state = NFA_TRANS_DM_RF_TRANS_END;
}
#endif

#if (NXP_EXTNS == TRUE)
/*******************************************************************************
 **
 ** Function:       update_transaction_stat
 **
 ** Description:    updates the transaction status set/reset
 **                 req_handle : Requesting handle - Module name
 **                 req_state : SET_TRANSACTION_STATE / RESET_TRANSACTION_STATE
 **
 ** Returns:        update status
 **                 ret_stat : true/false
 **
 *******************************************************************************/
#if 0
bool update_transaction_stat(const char * req_handle, transaction_state_t req_state)
{
    bool ret_stat = false;

    gTransactionMutex.lock();
    /*Check if it is initialisation*/
    if((req_handle == NULL)&&(req_state == RESET_TRANSACTION_STATE))
    {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Initialisation. Resetting transaction data", __func__);
        transaction_data.trans_in_progress = false;
        cur_transaction_handle = NULL;
        ret_stat = true;
    }
    else
    {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter. Requested by : %s   Requested state : %s", __func__,req_handle,(req_state?"SET":"RESET"));
    }

    /*Check if no transaction is currently ongoing*/
    if(!transaction_data.trans_in_progress)
    {
        if(req_state == SET_TRANSACTION_STATE)
        {
            transaction_data.trans_in_progress = req_state;
            cur_transaction_handle = req_handle;
            ret_stat = true;
            /*Using a backup reset procedure as a timer to reset Transaction state,
             *in case Transaction state is set but not reset because of some reason
             *
             *Also timer should not be started for below handles as these may take
             *more time to reset depending on the transaction duration
             **/
            if(strcmp(req_handle,"NFA_ACTIVATED_EVT") &&
                    strcmp(req_handle,"NFA_EE_ACTION_EVT") &&
                    strcmp(req_handle,"NFA_TRANS_CE_ACTIVATED") &&
                    strcmp(req_handle,"RF_FIELD_EVT") )
            {
                scleanupTimerProc_transaction.set (10000, cleanupTimerProc_transaction);
            }

        }
        else
        {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:Transaction state is already free. Returning", __func__);
            cur_transaction_handle = NULL;
            ret_stat = true;
            scleanupTimerProc_transaction.kill ();
        }
    }
    else
    {
        /*If transaction_stat is already set (transaction is ongoing) it can not be set again*/
        if(req_state == SET_TRANSACTION_STATE)
        {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:Transaction is in progress by : %s . Returning", __func__,cur_transaction_handle);
            ret_stat = false;
        }
        else
        {
            /*If transaction_stat is already set only authorised module can reset it
             *It should be either cur_transaction_handle (which has set transaction_stat) or
             *exec_pending_req*/
            if(cur_transaction_handle != NULL)
            {
                if(!strcmp(cur_transaction_handle,req_handle) || !strcmp(req_handle,"exec_pending_req"))
                {
                    transaction_data.trans_in_progress = req_state;
                    cur_transaction_handle = NULL;
                    ret_stat = true;
                    scleanupTimerProc_transaction.kill ();
                }
                else
                {
                    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:Handle Mismatch. Returning ..cur_transaction_handle : %s   Requested handle  : %s ", __func__,cur_transaction_handle,req_handle);
                    ret_stat = false;
                }
            }
            else
            {
                DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: No cur_transaction_handle. Allowing requested handle  : %s ", __func__,req_handle);
                transaction_data.trans_in_progress = req_state;
                cur_transaction_handle = req_handle;
                ret_stat = true;
                scleanupTimerProc_transaction.kill ();
            }
        }
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Exit.  Requested by : %s   Requested state : %s  status : %s", __func__,req_handle,(req_state?"SET":"RESET"), (ret_stat?"SUCCESS":"FAILED"));
    gTransactionMutex.unlock();
    return ret_stat;
}
#endif
#endif
  /*******************************************************************************
   **
   ** Function:        checkforTranscation
   **
   ** Description:     Receive connection-related events from stack.
   **                  connEvent: Event code.
   **                  eventData: Event data.
   **
   ** Returns:         None
   **
   *******************************************************************************/
  void checkforTranscation(uint8_t connEvent, void* eventData) {
    tNFA_CONN_EVT_DATA* eventAct_Data = (tNFA_CONN_EVT_DATA*)eventData;
    tNFA_DM_CBACK_DATA* eventDM_Conn_data = (tNFA_DM_CBACK_DATA*)eventData;
    tNFA_EE_CBACK_DATA* ee_action_data = (tNFA_EE_CBACK_DATA*)eventData;
    tNFA_EE_ACTION& action = ee_action_data->action;

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: enter; event=0x%X transaction_data.current_transcation_state = "
        "0x%x",
        __func__, connEvent, transaction_data.current_transcation_state);
    switch (connEvent) {
      case NFA_ACTIVATED_EVT:
        if ((eventAct_Data->activated.activate_ntf.protocol !=
             NFA_PROTOCOL_NFC_DEP) &&
            (isListenMode(eventAct_Data->activated))) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("ACTIVATED_EVT setting flag");
          transaction_data.current_transcation_state = NFA_TRANS_ACTIVATED_EVT;
#if (NXP_EXTNS == TRUE)
          if (!pTransactionController->transactionAttempt(
                  TRANSACTION_REQUESTOR(NFA_ACTIVATED_EVENT))) {
            LOG(ERROR) << StringPrintf(
                "%s: Transaction in progress. Can not set", __func__);
          }
#endif
        } else {
          //            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("other
          //            event clearing flag ");
          //            memset(&transaction_data, 0x00,
          //            sizeof(Transcation_Check_t));
        }
        break;
      case NFA_EE_ACTION_EVT:
        if (transaction_data.current_transcation_state == NFA_TRANS_DEFAULT ||
            transaction_data.current_transcation_state ==
                NFA_TRANS_ACTIVATED_EVT) {
          if (getScreenState() == NFA_SCREEN_STATE_OFF_LOCKED ||
              getScreenState() == NFA_SCREEN_STATE_OFF_UNLOCKED) {
            if (!sP2pActive &&
                eventDM_Conn_data->rf_field.status == NFA_STATUS_OK)
              SecureElement::getInstance().notifyRfFieldEvent(true);
          }
          if (nfcFL.chipType == pn547C2) {
            if ((action.param.technology == NFC_RF_TECHNOLOGY_A) &&
                ((getScreenState() == NFA_SCREEN_STATE_OFF_UNLOCKED ||
                  getScreenState() == NFA_SCREEN_STATE_ON_LOCKED ||
                  getScreenState() == NFA_SCREEN_STATE_OFF_LOCKED))) {
              transaction_data.current_transcation_state =
                  NFA_TRANS_MIFARE_ACT_EVT;
#if (NXP_EXTNS == TRUE)
              if (!pTransactionController->transactionAttempt(
                      TRANSACTION_REQUESTOR(NFA_EE_ACTION_EVENT))) {
                LOG(ERROR) << StringPrintf(
                    "%s: Transaction in progress. Can not set", __func__);
              }
#endif
            }
          } else {
            transaction_data.current_transcation_state =
                NFA_TRANS_EE_ACTION_EVT;
#if (NXP_EXTNS == TRUE)
            if (!pTransactionController->transactionAttempt(
                    TRANSACTION_REQUESTOR(NFA_EE_ACTION_EVENT))) {
              LOG(ERROR) << StringPrintf(
                  "%s: Transaction in progress. Can not set", __func__);
            }
#endif
          }
        }
        break;
      case NFA_TRANS_CE_ACTIVATED:
        if (transaction_data.current_transcation_state == NFA_TRANS_DEFAULT ||
            transaction_data.current_transcation_state ==
                NFA_TRANS_ACTIVATED_EVT) {
          if (getScreenState() == NFA_SCREEN_STATE_OFF_LOCKED ||
              getScreenState() == NFA_SCREEN_STATE_OFF_UNLOCKED) {
            if (!sP2pActive &&
                eventDM_Conn_data->rf_field.status == NFA_STATUS_OK)
              SecureElement::getInstance().notifyRfFieldEvent(true);
          }
          transaction_data.current_transcation_state = NFA_TRANS_CE_ACTIVATED;
#if (NXP_EXTNS == TRUE)
          if (!pTransactionController->transactionAttempt(
                  TRANSACTION_REQUESTOR(NFA_TRANS_CE_ACTIVATED_EVENT))) {
            LOG(ERROR) << StringPrintf(
                "%s: Transaction in progress. Can not set", __func__);
          }
#endif
        }
        break;
      case NFA_TRANS_CE_DEACTIVATED:
        rfActivation = false;
#if (NXP_EXTNS == TRUE)
        if (transaction_data.current_transcation_state ==
            NFA_TRANS_CE_ACTIVATED) {
          transaction_data.current_transcation_state = NFA_TRANS_CE_DEACTIVATED;
        }
#endif
        gActivated = false;
        break;
      case NFA_DEACTIVATED_EVT:
        if (nfcFL.chipType == pn547C2) {
          if (transaction_data.current_transcation_state ==
              NFA_TRANS_MIFARE_ACT_EVT) {
            pTransactionController->lastRequestResume();
          }
        }
        break;
      case NFA_TRANS_DM_RF_FIELD_EVT:
        if (eventDM_Conn_data->rf_field.status == NFA_STATUS_OK &&
            (transaction_data.current_transcation_state ==
                 NFA_TRANS_EE_ACTION_EVT ||
             transaction_data.current_transcation_state ==
                 NFA_TRANS_CE_DEACTIVATED ||
             transaction_data.current_transcation_state ==
                 NFA_TRANS_CE_ACTIVATED ||
             transaction_data.current_transcation_state ==
                 NFA_TRANS_ACTIVATED_EVT) &&
            eventDM_Conn_data->rf_field.rf_field_status == 0) {
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("start_timer");
          if (nfcFL.chipType != pn547C2) {
            set_AGC_process_state(false);
          }
          transaction_data.current_transcation_state =
              NFA_TRANS_DM_RF_FIELD_EVT_OFF;
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("Start guard RF ON timer");
          pTransactionController->setAbortTimer(50 /*msec*/);
        } else if (eventDM_Conn_data->rf_field.status == NFA_STATUS_OK &&
                   transaction_data.current_transcation_state ==
                       NFA_TRANS_DM_RF_FIELD_EVT_OFF &&
                   eventDM_Conn_data->rf_field.rf_field_status == 1) {
          if (nfcFL.chipType != pn547C2) {
            nfcManagerEnableAGCDebug(connEvent);
          }
          transaction_data.current_transcation_state =
              NFA_TRANS_DM_RF_FIELD_EVT_ON;
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
              "Payment is in progress hold the screen on/off request ");
          transaction_data.current_transcation_state =
              NFA_TRANS_DM_RF_TRANS_START;
          pTransactionController->killAbortTimer();

        } else if (eventDM_Conn_data->rf_field.status == NFA_STATUS_OK &&
                   transaction_data.current_transcation_state ==
                       NFA_TRANS_DM_RF_TRANS_START &&
                   eventDM_Conn_data->rf_field.rf_field_status == 0) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("Transcation is done");
          pTransactionController->transactionEnd(
              TRANSACTION_REQUESTOR(RF_FIELD_EVT));
          if (nfcFL.chipType != pn547C2) {
            set_AGC_process_state(false);
          }
          transaction_data.current_transcation_state =
              NFA_TRANS_DM_RF_TRANS_PROGRESS;
          pTransactionController->lastRequestResume();
        } else if (eventDM_Conn_data->rf_field.status == NFA_STATUS_OK &&
                   transaction_data.current_transcation_state ==
                       NFA_TRANS_ACTIVATED_EVT &&
                   eventDM_Conn_data->rf_field.rf_field_status == 0) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("No transaction done cleaning up the variables");
          pTransactionController->lastRequestResume();
        }
        break;
      default:
        break;
    }

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: exit; event=0x%X transaction_data.current_transcation_state = "
        "0x%x",
        __func__, connEvent, transaction_data.current_transcation_state);
  }

  /*******************************************************************************
   **
   ** Function:       enableThread
   **
   ** Description:    thread to trigger enable/disable discovery related events
   **
   ** Returns:        None .
   **
   *******************************************************************************/
  void* enableThread(void* arg) {
    (void)arg;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    char last_request = get_last_request();
    eScreenState_t last_screen_state_request = get_lastScreenStateRequest();
    if (nfcFL.chipType != pn547C2) {
      set_AGC_process_state(false);
    }

    bool screen_lock_flag = false;
    bool disable_discovery = false;

    {
      SyncEventGuard guard (sExecPendingRegEvent);
      if(sIsNfaEnabled != true || sIsDisabling == true) goto TheEnd;

      sIsExecPendingReq = true;
    }

    if (last_screen_state_request != NFA_SCREEN_STATE_UNKNOWN) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "update last screen state request: %d", last_screen_state_request);
      nfcManager_doSetScreenState(NULL, NULL, last_screen_state_request);
      set_lastScreenStateRequest(NFA_SCREEN_STATE_UNKNOWN);
      if (last_screen_state_request == NFA_SCREEN_STATE_ON_LOCKED)
        screen_lock_flag = true;
    } else {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("No request pending");
    }

    if (last_request & ENABLE_DISCOVERY) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("send the last request enable");
      sDiscoveryEnabled = false;
      sPollingEnabled = false;

      transaction_data.last_request &= ~(ENABLE_DISCOVERY);
      nfcManager_enableDiscovery(
          NULL, NULL, transaction_data.discovery_params.technologies_mask,
          transaction_data.discovery_params.enable_lptd,
          transaction_data.discovery_params.reader_mode,
          transaction_data.discovery_params.enable_p2p,
          transaction_data.discovery_params.restart);
    }

    if (last_request & DISABLE_DISCOVERY) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("send the last request disable");
      transaction_data.last_request &= ~(DISABLE_DISCOVERY);
      nfcManager_disableDiscovery(NULL, NULL);
      disable_discovery = true;
    }
#if (NXP_EXTNS == TRUE)
    if (last_request & ENABLE_P2P) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("send the last request to enable P2P ");
      nfcManager_Enablep2p(NULL, NULL,
                           transaction_data.discovery_params.enable_p2p);
      transaction_data.last_request &= ~(ENABLE_P2P);
    }
#if (NXP_NFCC_HCE_F == TRUE)
    if (last_request & T3T_CONFIGURE) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          " transaction_data.t3thandle %d ", transaction_data.t3thandle);
      if (transaction_data.t3thandle != 0) {
        RoutingManager::getInstance().deregisterT3tIdentifier(
            transaction_data.t3thandle);
      }
      transaction_data.last_request &= ~(T3T_CONFIGURE);
      RoutingManager::getInstance().notifyT3tConfigure();
    }
#endif
    if (last_request & RE_ROUTING) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf(" transaction_data.isInstallRequest %d ",
                          transaction_data.isInstallRequest);
      if (!transaction_data.isInstallRequest) {
        RoutingManager::getInstance().clearAidTable();
        // nfcManager_doCommitRouting(NULL,NULL);
      }
      transaction_data.last_request &= ~(RE_ROUTING);
      RoutingManager::getInstance().notifyReRoutingEntry();
    }
#endif
    if (screen_lock_flag && disable_discovery) {
      startRfDiscovery(true);
    }
    screen_lock_flag = false;
    disable_discovery = false;
    last_screen_state_request = transaction_data.last_screen_state_request;
    last_request = transaction_data.last_request;
    memset(&transaction_data, 0x00, sizeof(Transcation_Check_t));

    transaction_data.last_request = last_request;
    set_lastScreenStateRequest(last_screen_state_request);

    if (nfcFL.chipType != pn547C2) {
      memset(&menableAGC_debug_t, 0x00, sizeof(enableAGC_debug_t));
    }

    {
      SyncEventGuard guard (sExecPendingRegEvent);
      sExecPendingRegEvent.notifyOne();
      sIsExecPendingReq = false;
    }
  TheEnd:
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
    pthread_exit(NULL);
    return NULL;
  }
  /*******************************************************************************
  **
  ** Function         sig_handler
  **
  ** Description      This function is used to handle the different types of
  **                  signal events.
  **
  ** Returns          None
  **
  *******************************************************************************/
  void sig_handler(int signo) {
    if (!nfcFL.eseFL._JCOP_WA_ENABLE) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("JCOP_WA_ENABLE not available..Returning");
      return;
    }
    switch (signo) {
      case SIGINT:
        LOG(ERROR) << StringPrintf("received SIGINT\n");
        break;
      case SIGABRT:
        LOG(ERROR) << StringPrintf("received SIGABRT\n");
#if (NXP_EXTNS == TRUE)
        if (nfcFL.nfccFL._NFCC_MW_RCVRY_BLK_FW_DNLD) {
          NFA_MW_Fwdnlwd_Recovery(true);
        }
#endif
        NFA_HciW4eSETransaction_Complete(Wait);
        break;
      case SIGSEGV:
        LOG(ERROR) << StringPrintf("received SIGSEGV\n");
        break;
      case SIGHUP:
        LOG(ERROR) << StringPrintf("received SIGHUP\n");
        break;
    }
  }

  /*******************************************************************************
  **
  ** Function         nfcManager_doGetSeInterface
  **
  ** Description      This function is used to get the eSE Client interfaces.
  **
  ** Returns          integer - Physical medium
  **
  *******************************************************************************/
  static int nfcManager_doGetSeInterface(JNIEnv * e, jobject o, jint type) {
    unsigned long num = 0;
    switch (type) {
      case LDR_SRVCE:

        if (NfcConfig::hasKey(NAME_NXP_P61_LS_DEFAULT_INTERFACE)) {
          num = NfcConfig::getUnsigned(NAME_NXP_P61_LS_DEFAULT_INTERFACE);
        } else {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("NAME_NXP_P61_LS_DEFAULT_INTERFACE not found");
          num = 1;
        }
        break;
      case JCOP_SRVCE:
        if (NfcConfig::hasKey(NAME_NXP_P61_JCOP_DEFAULT_INTERFACE)) {
          num = NfcConfig::getUnsigned(NAME_NXP_P61_JCOP_DEFAULT_INTERFACE);
        } else {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("NAME_NXP_P61_JCOP_DEFAULT_INTERFACE not found");
          num = 1;
        }
        break;
      case LTSM_SRVCE:
        if (NfcConfig::hasKey(NAME_NXP_P61_LTSM_DEFAULT_INTERFACE)) {
          num = NfcConfig::getUnsigned(NAME_NXP_P61_LTSM_DEFAULT_INTERFACE);
        } else {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("NAME_NXP_P61_LTSM_DEFAULT_INTERFACE not found");
          num = 1;
        }
        break;
      default:
        break;
    }
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%ld: nfcManager_doGetSeInterface", num);
    return num;
  }

#if (NXP_EXTNS == TRUE)
  /**********************************************************************************
  **
  ** Function:       pollT3TThread
  **
  ** Description:    This thread sends commands to switch from P2P to T3T
  **                 When ReaderMode is enabled, When P2P is detected,Switch to
  *T3T
  **                 with Frame RF interface and Poll for T3T
  **
  ** Returns:         None.
  **
  **********************************************************************************/
  static void* pollT3TThread(void* arg) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    bool status = false;

    if (sReaderModeEnabled && (sTechMask & NFA_TECHNOLOGY_MASK_F)) {
      /*Deactivate RF to go to W4_HOST_SELECT state
       *Send Select Command to Switch to FrameRF interface from NFCDEP
       *interface
       *After NFC-DEP activation with FrameRF Intf, invoke T3T Polling Cmd*/
      {
        SyncEventGuard g(sRespCbEvent);
        if (NFA_STATUS_OK !=
            (status = NFA_Deactivate(true)))  // deactivate to sleep state
        {
          LOG(ERROR) << StringPrintf("%s: deactivate failed, status = %d",
                                     __func__, status);
        }
        if (sRespCbEvent.wait(2000) == false)  // if timeout occurred
        {
          LOG(ERROR) << StringPrintf("%s: timeout waiting for deactivate",
                                     __func__);
        }
      }
      {
        SyncEventGuard g2(sRespCbEvent);
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "Switching RF Interface from NFC-DEP to FrameRF for T3T\n");
        if (NFA_STATUS_OK !=
            (status = NFA_Select(*((uint8_t*)arg), NFA_PROTOCOL_T3T,
                                 NFA_INTERFACE_FRAME))) {
          LOG(ERROR) << StringPrintf("%s: NFA_Select failed, status = %d",
                                     __func__, status);
        }
        if (sRespCbEvent.wait(2000) == false)  // if timeout occured
        {
          LOG(ERROR) << StringPrintf("%s: timeout waiting for select",
                                     __func__);
        }
      }
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
    pthread_exit(NULL);
    return NULL;
  }

  /**********************************************************************************
  **
  ** Function:       switchP2PToT3TRead
  **
  ** Description:    Create a thread to change the RF interface by Deactivating
  *to Sleep
  **
  ** Returns:         None.
  **
  **********************************************************************************/
  static bool switchP2PToT3TRead(uint8_t disc_id) {
    pthread_t pollT3TThreadId;
    int irret = -1;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:entry", __func__);
    felicaReader_Disc_id = disc_id;

    /* Transcation is done process the last request*/
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    irret = pthread_create(&pollT3TThreadId, &attr, pollT3TThread,
                           (void*)&felicaReader_Disc_id);
    if (irret != 0) {
      LOG(ERROR) << StringPrintf("Unable to create the thread");
    }
    pthread_attr_destroy(&attr);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:exit", __func__);
    return irret;
  }

  static void NxpResponsePropCmd_Cb(uint8_t event, uint16_t param_len,
                                    uint8_t * p_param) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "NxpResponsePropCmd_Cb Received length data = 0x%x status = 0x%x",
        param_len, p_param[3]);
    SyncEventGuard guard(sNfaNxpNtfEvent);
    sNfaNxpNtfEvent.notifyOne();
  }

  /*******************************************************************************
  **
  ** Function:        nfcManager_setProvisionMode
  **
  ** Description:     set/reset provision mode
  **                  e: JVM environment.
  **                  o: Java object.
  **
  ** Returns:         None.
  **
  *******************************************************************************/
  static void nfcManager_setProvisionMode(JNIEnv * e, jobject o,
                                          jboolean provisionMode) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "Enter :%s  provisionMode = %d", __func__, provisionMode);
    sProvisionMode = provisionMode;
    NFA_setProvisionMode(provisionMode);
    // When disabling provisioning mode, make sure configuration of routing
    // table is also updated
    // this is required to make sure p2p is blocked during locked screen
    if (!provisionMode) {
      RoutingManager::getInstance().commitRouting();
    }
  }

  /*******************************************************************************
   **
   ** Function:        isActivatedTypeF
   **
   ** Description:     Indicates whether the activation data indicates it is
   **                  TypeF technology.
   **
   ** Returns:         True if activated technology is TypeF.
   **
   *******************************************************************************/
  static bool isActivatedTypeF(tNFA_ACTIVATED & activated) {
    return ((NFC_DISCOVERY_TYPE_POLL_F ==
             activated.activate_ntf.rf_tech_param.mode) ||
            (NFC_DISCOVERY_TYPE_POLL_F_ACTIVE ==
             activated.activate_ntf.rf_tech_param.mode) ||
            (NFC_DISCOVERY_TYPE_LISTEN_F ==
             activated.activate_ntf.rf_tech_param.mode) ||
            (NFC_DISCOVERY_TYPE_LISTEN_F_ACTIVE ==
             activated.activate_ntf.rf_tech_param.mode));
  }
  /**********************************************************************************
   **
   ** Function:        eSEPhyIntfInResponsive
   **
   ** Description:    checking inactivity of eSE physical interface
   **
   ** Returns:         1(if true)/0 (if false) .
   **
   **********************************************************************************/
  static uint32_t eSEPhyIntfInResponsive(tNFA_DM_CBACK_DATA * pEvtData) {
    LOG(ERROR) << StringPrintf("%s: param_tlvs %x", __func__,
                               pEvtData->get_config.param_tlvs[5]);
    if (nfcFL.chipType != pn553)
      return (IS_ESE_RF_CE_PARAM_FETCHED() && IS_ESE_CE_MODE_DISABLED() &&
              SecureElement::getInstance().getEeStatus(ESE_HANDLE) ==
                  NFA_EE_STATUS_ACTIVE);
    else
      return 0;
  }
  /**********************************************************************************
  **
  ** Function:        recoverEseConnectivity
  **
  ** Description:     reset eSE connection for recovering communication
  **
  ** Returns:         None
  **
  **********************************************************************************/
  static void recoverEseConnectivity() {
    tNFA_STATUS stat = ResetEseSession();
    if (stat == NFA_STATUS_OK) {
      SecureElement::getInstance().SecEle_Modeset(0x00);
      usleep(50 * 1000);
      SecureElement::getInstance().SecEle_Modeset(0x01);
    }
  }
  /**********************************************************************************
   **
   ** Function:        checkforNfceeBuffer
   **
   ** Description:    checking for the Nfcee Buffer (GetConfigs for
   *SWP_INT_SESSION_ID (EA and EB))
   **
   ** Returns:         None .
   **
   **********************************************************************************/
  void checkforNfceeBuffer() {
    int i, count = 0;

    for (i = 4; i < 12; i++) {
      if (sConfig[i] == 0xff) count++;
    }

    if (count >= 8) {
      /*If session ID received all 0xff for UICC and dual UICC feature is
       * enabled then
       * clear the corresponding buffer (invalid session ID)
       * */
      if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH &&
          ((sConfig[1] == 0xA0) && (sConfig[2] == 0xEA) &&
           (dualUiccInfo.dualUiccEnable == 0x01))) {
        if (sSelectedUicc == 0x01) {
          memset(dualUiccInfo.sUicc1SessionId, 0x00,
                 sizeof(dualUiccInfo.sUicc1SessionId));
          dualUiccInfo.sUicc1SessionIdLen = 0;
        } else {
          memset(dualUiccInfo.sUicc2SessionId, 0x00,
                 sizeof(dualUiccInfo.sUicc2SessionId));
          dualUiccInfo.sUicc2SessionIdLen = 0;
        }
      }
      sNfceeConfigured = 1;
    } else {
      if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
        if ((sConfig[1] == 0xA0) && (sConfig[2] == 0xEA) &&
            (dualUiccInfo.dualUiccEnable == 0x01)) {
          sNfceeConfigured = getUiccSession();
        } else {
          sNfceeConfigured = 0;
        }
      } else {
        sNfceeConfigured = 0;
      }
    }

    memset(sConfig, 0, sizeof(sConfig));
  }
  /**********************************************************************************
   **
   ** Function:        checkforNfceeConfig
   **
   ** Description:    checking for the Nfcee is configured or not (GetConfigs
   *for SWP_INT_SESSION_ID (EA and EB))
   **
   ** Returns:         None .
   **
   **********************************************************************************/
  void checkforNfceeConfig(uint8_t type) {
    uint8_t uicc_flag = 0, ese_flag = 0;
    uint8_t uicc2_flag = 0; /*For Dynamic Dual UICC*/
    unsigned long timeout_buff_val = 0, check_cnt = 0, retry_cnt = 0;

    tNFA_STATUS status;
    tNFA_PMID param_ids_UICC[] = {0xA0, 0xEA};
    tNFA_PMID param_ids_eSE[] = {0xA0, 0xEB};
    tNFA_PMID param_uicc1[] = {0xA0, 0x24};
    tNFA_PMID param_ids_UICC2[] = {0xA0, 0x1E};
    tNFA_PMID param_uicc2[] = {0xA0, 0xE9};

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: enter, type=%x", __func__, type);
    uint8_t pipeId = 0;
    SecureElement::getInstance().updateEEStatus();
    bool configureuicc1 = false;
    bool configureuicc2 = false;

    if (NfcConfig::hasKey(NAME_NXP_DEFAULT_NFCEE_TIMEOUT)) {
      timeout_buff_val = NfcConfig::getUnsigned(NAME_NXP_DEFAULT_NFCEE_TIMEOUT);
      check_cnt = timeout_buff_val * RETRY_COUNT;
    } else {
      check_cnt = DEFAULT_COUNT * RETRY_COUNT;
    }

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("NAME_DEFAULT_NFCEE_TIMEOUT = %lu", check_cnt);

    if (SecureElement::getInstance().getEeStatus(
            SecureElement::EE_HANDLE_0xF3) == NFC_NFCEE_STATUS_ACTIVE) {
      ese_flag = 0x01;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("eSE_flag SET");
    }
    if (SecureElement::getInstance().getEeStatus(
            SecureElement::getInstance().EE_HANDLE_0xF4) ==
        NFC_NFCEE_STATUS_ACTIVE) {
      uicc_flag = 0x01;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("uicc_flag SET");
    }
    if (nfcFL.nfccFL._NFCC_DYNAMIC_DUAL_UICC) {
      if (SecureElement::getInstance().getEeStatus(
              SecureElement::EE_HANDLE_0xF8) == NFC_NFCEE_STATUS_ACTIVE) {
        uicc2_flag = 0x01;
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("uicc2_flag SET");
      }
    } else if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
      if (dualUiccInfo.dualUiccEnable == 0x01) {
        if (sSelectedUicc == 0x01) {
          memset(dualUiccInfo.sUicc1SessionId, 0x00,
                 sizeof(dualUiccInfo.sUicc1SessionId));
          dualUiccInfo.sUicc1SessionIdLen = 0;
        } else {
          memset(dualUiccInfo.sUicc2SessionId, 0x00,
                 sizeof(dualUiccInfo.sUicc2SessionId));
          dualUiccInfo.sUicc2SessionIdLen = 0;
        }
      }
    }
    if ((ese_flag == 0x01) || (uicc_flag == 0x01) || (uicc2_flag == 0x01)) {
      if (nfcFL.nfcNxpEse && (ese_flag && ((type & ESE) == ESE))) {
        sCheckNfceeFlag = 1;
        {
          SyncEventGuard guard(android::sNfaGetConfigEvent);
          while (check_cnt > retry_cnt) {
            status = NFA_GetConfig(0x01, param_ids_eSE);
            if (status == NFA_STATUS_OK) {
              android::sNfaGetConfigEvent.wait();
            }
            if (sNfceeConfigured == 1) {
              SecureElement::getInstance().meSESessionIdOk = false;
              DLOG_IF(INFO, nfc_debug_enabled)
                  << StringPrintf("eSE Not Configured");
            } else {
              SecureElement::getInstance().meSESessionIdOk = true;
              DLOG_IF(INFO, nfc_debug_enabled)
                  << StringPrintf("eSE Configured");
              break;
            }

            usleep(100000);
            retry_cnt++;
          }
        }
        if (check_cnt <= retry_cnt)
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("eSE Not Configured");
        retry_cnt = 0;
      }

      if (nfcFL.nfccFL._NFCC_DYNAMIC_DUAL_UICC &&
          (uicc2_flag && ((type & UICC2) == UICC2))) {
        sCheckNfceeFlag = 1;
        {
          SyncEventGuard guard(android::sNfaGetConfigEvent);
          while (check_cnt > retry_cnt) {
            status = NFA_GetConfig(0x01, param_ids_UICC2);
            if (status == NFA_STATUS_OK) {
              android::sNfaGetConfigEvent.wait();
            }

            if (sNfceeConfigured == 1) {
              DLOG_IF(INFO, nfc_debug_enabled)
                  << StringPrintf("UICC2 Not Configured");
            } else {
              DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                  "UICC2 Configured connectivity pipeId = %x", pipeId);
              configureuicc2 = true;
              if (nfcFL.nfccFL._UICC_CREATE_CONNECTIVITY_PIPE != true) {
                break;
              }
            }
            if (nfcFL.nfccFL._UICC_CREATE_CONNECTIVITY_PIPE) {
              if ((configureuicc2 == true) || (check_cnt == retry_cnt)) {
                configureuicc2 = false;
                pipeId = SecureElement::getInstance().getUiccGateAndPipeList(
                    SecureElement::getInstance().EE_HANDLE_0xF8 &
                    ~NFA_HANDLE_GROUP_EE);
                if (pipeId == 0) {
                  DLOG_IF(INFO, nfc_debug_enabled)
                      << StringPrintf("Add pipe information");
                  sCheckNfceeFlag = 0;
                  status = NFA_GetConfig(0x01, param_uicc2);
                  pipeId = 0x23;

                  if (status == NFA_STATUS_OK) {
                    android::sNfaGetConfigEvent.wait();
                  }
                  sCheckNfceeFlag = 1;
                  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                      "UICC2 connectivity gate present = %s",
                      (sConfig[NFC_PIPE_STATUS_OFFSET] ? "true" : "false"));
                  /*If pipe is present and opened update MW status*/
                  if (sConfig[NFC_PIPE_STATUS_OFFSET] > PIPE_DELETED) {
                    SyncEventGuard guard(
                        SecureElement::getInstance().mHciAddStaticPipe);
                    status = NFA_HciAddStaticPipe(
                        SecureElement::getInstance().getHciHandleInfo(), 0x81,
                        NFA_HCI_CONNECTIVITY_GATE, pipeId);
                    if (status == NFA_STATUS_OK) {
                      SecureElement::getInstance().mHciAddStaticPipe.wait(500);
                    }
                  }
                }
                break;
              }
            }
            usleep(100000);
            retry_cnt++;
          }
        }

        if (check_cnt <= retry_cnt)
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("UICC2 Not Configured");
        retry_cnt = 0;
        pipeId = 0;
      }
      if (uicc_flag && ((type & UICC1) == UICC1)) {
        sCheckNfceeFlag = 1;
        {
          SyncEventGuard guard(android::sNfaGetConfigEvent);
          while (check_cnt > retry_cnt) {
            status = NFA_GetConfig(0x01, param_ids_UICC);
            if (status == NFA_STATUS_OK) {
              android::sNfaGetConfigEvent.wait();
            }

            if (sNfceeConfigured == 1) {
              DLOG_IF(INFO, nfc_debug_enabled)
                  << StringPrintf("UICC Not Configured");
            } else {
              if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH)
                dualUiccInfo.uiccConfigStat = UICC_CONFIGURED;
              DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                  "UICC1 Configured connectivity pipeId = %x", pipeId);
              configureuicc1 = true;
              if (nfcFL.nfccFL._UICC_CREATE_CONNECTIVITY_PIPE == false) {
                break;
              }
            }
            if (nfcFL.nfccFL._UICC_CREATE_CONNECTIVITY_PIPE) {
              if ((configureuicc1 == true) || (check_cnt == retry_cnt)) {
                configureuicc1 = false;
                pipeId = SecureElement::getInstance().getUiccGateAndPipeList(
                    SecureElement::getInstance().EE_HANDLE_0xF4 &
                    ~NFA_HANDLE_GROUP_EE);
                if (pipeId == 0) {
                  DLOG_IF(INFO, nfc_debug_enabled)
                      << StringPrintf("Add pipe information");
                  sCheckNfceeFlag = 0;
                  status = NFA_GetConfig(0x01, param_uicc1);
                  pipeId = 0x0A;
                  if (status == NFA_STATUS_OK) {
                    android::sNfaGetConfigEvent.wait();
                  }
                  sCheckNfceeFlag = 1;
                  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                      "UICC1 connectivity gate present = %s",
                      (sConfig[NFC_PIPE_STATUS_OFFSET] ? "true" : "false"));
                  /*If pipe is present and opened update MW status*/
                  if (sConfig[NFC_PIPE_STATUS_OFFSET] > PIPE_DELETED) {
                    SyncEventGuard guard(
                        SecureElement::getInstance().mHciAddStaticPipe);
                    status = NFA_HciAddStaticPipe(
                        SecureElement::getInstance().getHciHandleInfo(), 0x02,
                        NFA_HCI_CONNECTIVITY_GATE, pipeId);
                    if (status == NFA_STATUS_OK) {
                      SecureElement::getInstance().mHciAddStaticPipe.wait();
                    }
                  }
                }
                break;
              }
            }
            usleep(100000);
            retry_cnt++;
          }
        }

        if (check_cnt <= retry_cnt)
          LOG(ERROR) << StringPrintf("UICC Not Configured");
        retry_cnt = 0;
        if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
          sCheckNfceeFlag = 0;
        }
      }
    }

    sCheckNfceeFlag = 0;

    if (nfcFL.eseFL._JCOP_WA_ENABLE) {
      RoutingManager::getInstance().handleSERemovedNtf();
    }
  }
#endif

#if (NXP_EXTNS == TRUE)
  static void nfaNxpSelfTestNtfTimerCb(union sigval) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", __func__);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "NXP SWP SelfTest : Can't get a notification about SWP Status!!");
    SyncEventGuard guard(sNfaNxpNtfEvent);
    sNfaNxpNtfEvent.notifyOne();
    SetCbStatus(NFA_STATUS_FAILED);
  }

  /**********************************************************************************
   **
   ** Function:        performNfceeETSI12Config
   **
   ** Description:    checking for Nfcee ETSI 12 Compliancy and configure if
   *compliant
   **
   ** Returns:         None .
   **
   **********************************************************************************/
  void performNfceeETSI12Config() {
    bool status;
    tNFA_STATUS configstatus = NFA_STATUS_FAILED;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", __func__);

    SecureElement::getInstance().setNfccPwrConfig(
        SecureElement::getInstance().POWER_ALWAYS_ON |
        SecureElement::getInstance().COMM_LINK_ACTIVE);

    status = SecureElement::getInstance().configureNfceeETSI12();
    if (status == true) {
      {
        SyncEventGuard guard(SecureElement::getInstance().mNfceeInitCbEvent);
        if (SecureElement::getInstance().mNfceeInitCbEvent.wait(4000) ==
            false) {
          LOG(ERROR) << StringPrintf(
              "%s:     timeout waiting for Nfcee Init event", __func__);
        }
      }
      if (SecureElement::getInstance().mETSI12InitStatus != NFA_STATUS_OK) {
        // check for recovery
        configstatus = ResetEseSession();
        if (configstatus == NFA_STATUS_OK) {
          SecureElement::getInstance().meseETSI12Recovery = true;
          SecureElement::getInstance().SecEle_Modeset(0x00);
          usleep(50 * 1000);
          SecureElement::getInstance().SecEle_Modeset(0x01);
          SecureElement::getInstance().meseETSI12Recovery = false;
        }
        checkforNfceeConfig(ESE);
      }
    }
  }

  /**********************************************************************************
   **
   ** Function:       performHCIInitialization
   **
   ** Description:    Performs HCI and SWP interface Initialization
   **
   ** Returns:         None .
   **
   **********************************************************************************/
  static void performHCIInitialization(JNIEnv * e, jobject o) {
    NFCSTATUS status = NFA_STATUS_FAILED;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", __func__);
    GetNumNFCEEConfigured();
    status = android::enableSWPInterface();
    if (status == NFA_STATUS_OK) {
      RoutingManager::getInstance().nfaEEDisconnect();
      usleep(1000 * 1000);
      android::NxpNfc_Send_CoreResetInit_Cmd();
      /*Update Actual SE count gActualSeCount*/
      GetNumNFCEEConfigured();
      RoutingManager::getInstance().nfaEEConnect();
      SecureElement::getInstance().activateAllNfcee();
      sIsSecElemSelected = (SecureElement::getInstance().getActualNumEe() - 1);
      sIsSecElemDetected = sIsSecElemSelected;
    } else {
      LOG(ERROR) << StringPrintf(
          "No UICC update required/failed to enable SWP interfaces");
    }
  }

  void checkforESERemoval() {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("checkforESERemoval enter");

    bool nfaEseRemovedNtf = true;
    uint8_t numNfceePresent =
        SecureElement::getInstance().mNfceeData_t.mNfceePresent;
    tNFA_HANDLE nfceeHandle[MAX_NFCEE];
    tNFA_EE_STATUS nfceeStatus[MAX_NFCEE];

    uint8_t retry = 0;
    uint8_t mActualNumEe = SecureElement::MAX_NUM_EE;
    tNFA_EE_INFO mEeInfo[mActualNumEe];
    uint8_t eseDetected = 0;

    for (int i = 1; i <= numNfceePresent; i++) {
      nfceeHandle[i] =
          SecureElement::getInstance().mNfceeData_t.mNfceeHandle[i];
      nfceeStatus[i] =
          SecureElement::getInstance().mNfceeData_t.mNfceeHandle[i];

      if (nfceeHandle[i] == (SecureElement::EE_HANDLE_0xF3) &&
          nfceeStatus[i] == 0x0) {
        nfaEseRemovedNtf = false;
        break;
      }
    }
    if (nfaEseRemovedNtf) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("nfaEseRemovedNtf true");
      tNFA_STATUS configstatus = NFA_STATUS_FAILED;
      do {
        if ((configstatus = NFA_AllEeGetInfo(&mActualNumEe, mEeInfo)) !=
            NFA_STATUS_OK) {
          LOG(ERROR) << StringPrintf("unable to get the EE status");
        } else {
          for (int xx = 0; xx < mActualNumEe; xx++) {
            LOG(ERROR) << StringPrintf("xx=%d, ee_handle=0x0%x, status=0x0%x",
                                       xx, mEeInfo[xx].ee_handle,
                                       mEeInfo[xx].ee_status);
            if (mEeInfo[xx].ee_handle == 0x4C0) {
              if (mEeInfo[xx].ee_status == 0x02) {
                configstatus = ResetEseSession();
                RoutingManager::getInstance().nfaEEDisconnect();
                usleep(1000 * 1000);
                android::NxpNfc_Send_CoreResetInit_Cmd();
                RoutingManager::getInstance().nfaEEConnect();
                usleep(1000 * 1000);
              } else {
                eseDetected = 0x01;
              }
              break;
            }
          }
        }
      } while ((eseDetected == 0x00) && (retry++ < 1));
    }
    usleep(1000 * 1000);
  }

  /**********************************************************************************
   **
   ** Function:        getUiccContext
   **
   ** Description:     Read and store UICC context values
   **                  Respective context will be applied during next switching
   **
   ** Returns:         None
   **
   **********************************************************************************/
  static void getUiccContext(int uiccSlot) {
    if (!nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: STAT_DUAL_UICC_EXT_SWITCH not available. Returning", __func__);
      return;
    }
    uint8_t i;
    tNFA_STATUS status;
    tNFA_PMID param_ids_UICC_getContext[] = {0xA0, 0xF4};

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);

    SyncEventGuard guard(android::sNfaGetConfigEvent);
    status = NFA_GetConfig(0x01, param_ids_UICC_getContext);
    if (status == NFA_STATUS_OK) {
      android::sNfaGetConfigEvent.wait();
    }

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: UICC context Info : Len = %x", __func__, sCurrentConfigLen);
    /*If the session ID is changed or uicc changed*/

    if ((dualUiccInfo.sUicc1CntxLen != 0) && (sSelectedUicc == 0x01)) {
      for (i = 0; i < dualUiccInfo.sUicc1CntxLen; i++) {
        if (sConfig[i] != dualUiccInfo.sUicc1Cntx[i]) break;
      }
      if (i != dualUiccInfo.sUicc1CntxLen) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: copying UICC1 info", __func__);
        update_uicc_context_info();
      }
    }
    /*If the session ID is changed or uicc changed*/
    if ((dualUiccInfo.sUicc2CntxLen != 0) && (sSelectedUicc == 0x02)) {
      for (i = 0; i < dualUiccInfo.sUicc2CntxLen; i++) {
        if (sConfig[i] != dualUiccInfo.sUicc2Cntx[i]) break;
      }
      if (i != dualUiccInfo.sUicc1CntxLen) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: copying UICC2 info", __func__);
        update_uicc_context_info();
      }
    }

    /*For the first power cycle for uicc1*/
    if ((dualUiccInfo.sUicc1CntxLen == 0) && (sSelectedUicc == 0x01)) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s:  power cycle storing UICC1 info", __func__);
      dualUiccInfo.sUicc1CntxLen = sCurrentConfigLen;
      for (i = 5; i < 13; i++) {
        if (sConfig[i] != (uint8_t)0xFF) break;
      }
      if (i == 13) {
        dualUiccInfo.sUicc1CntxLen = 0;
      } else {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: copying UICC1 info", __func__);
        update_uicc_context_info();
      }
    }
    /*For the first power cycle for uicc2*/
    else if ((dualUiccInfo.sUicc2CntxLen == 0) && (sSelectedUicc == 0x02)) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s:  power cycle storing UICC2 info", __func__);
      dualUiccInfo.sUicc2CntxLen = sCurrentConfigLen;
      for (i = 5; i < 13; i++) {
        if (sConfig[i] != (uint8_t)0xFF) break;
      }
      if (i == 13) {
        dualUiccInfo.sUicc2CntxLen = 0;
      } else {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: copying UICC2 info", __func__);
        update_uicc_context_info();
      }
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: UICC info are already stored..", __func__);
    }

    if ((uiccSlot == 0x01) && (dualUiccInfo.sUicc1CntxLen == 0x00)) {
      read_uicc_context(dualUiccInfo.sUicc1Cntx, dualUiccInfo.sUicc1CntxLen,
                        dualUiccInfo.sUicc1TechCapblty,
                        sizeof(dualUiccInfo.sUicc1TechCapblty), 1, uiccSlot);
    } else if ((uiccSlot == 0x02) && (dualUiccInfo.sUicc2CntxLen == 0x00)) {
      read_uicc_context(dualUiccInfo.sUicc2Cntx, dualUiccInfo.sUicc2CntxLen,
                        dualUiccInfo.sUicc2TechCapblty,
                        sizeof(dualUiccInfo.sUicc2TechCapblty), 1, uiccSlot);
    }

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Exit", __func__);
  }

  /**********************************************************************************
   **
   ** Function:        update_uicc_context_info
   **
   ** Description:     updates UICC context related info to buffere and file
   **
   ** Returns:         none
   **
   **********************************************************************************/
  static void update_uicc_context_info() {
    if (!nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: STAT_DUAL_UICC_EXT_SWITCH not available. Returning", __func__);
      return;
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);
    tNFA_STATUS status = NFA_STATUS_FAILED;
    tNFA_PMID param_ids_UICC_getOtherContext[] = {0xA0, 0xF5};
    if (sSelectedUicc == 0x01) {
      memcpy(dualUiccInfo.sUicc1Cntx, sConfig, sCurrentConfigLen);
      status = NFA_GetConfig(0x01, param_ids_UICC_getOtherContext);
      if (status == NFA_STATUS_OK) {
        android::sNfaGetConfigEvent.wait();
      }
      memcpy(dualUiccInfo.sUicc1TechCapblty, sConfig, sCurrentConfigLen);
      write_uicc_context(dualUiccInfo.sUicc1Cntx, dualUiccInfo.sUicc1CntxLen,
                         dualUiccInfo.sUicc1TechCapblty, 10, 1, sSelectedUicc);
    } else if (sSelectedUicc == 0x02) {
      memcpy(dualUiccInfo.sUicc2Cntx, sConfig, sCurrentConfigLen);
      status = NFA_GetConfig(0x01, param_ids_UICC_getOtherContext);
      if (status == NFA_STATUS_OK) {
        android::sNfaGetConfigEvent.wait();
      }
      memcpy(dualUiccInfo.sUicc2TechCapblty, sConfig, sCurrentConfigLen);
      write_uicc_context(dualUiccInfo.sUicc2Cntx, dualUiccInfo.sUicc2CntxLen,
                         dualUiccInfo.sUicc2TechCapblty, 10, 1, sSelectedUicc);
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Exit", __func__);
  }

  /**********************************************************************************
   **
   ** Function:        write_uicc_context
   **
   ** Description:     write UICC context to file
   **
   ** Returns:         none
   **
   **********************************************************************************/
  void write_uicc_context(uint8_t * uiccContext, uint16_t uiccContextLen,
                          uint8_t * uiccTechCap, uint16_t uiccTechCapLen,
                          uint8_t block, uint8_t slotnum) {
    if (!nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: STAT_DUAL_UICC_EXT_SWITCH not available. Returning", __func__);
      return;
    }
    char filename[256], filename2[256];
    uint8_t cntx_len = 128;
    uint8_t techCap = 10;
    uint8_t* frameByte;
    uint16_t crcVal = 0;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s : enter", __func__);

    memset(filename, 0, sizeof(filename));
    memset(filename2, 0, sizeof(filename2));
    strcpy(filename2, "/data/nfc");
    strncat(filename2, "/nxpStorage.bin",
            sizeof(filename2) - strlen(filename2) - 1);

    if (strlen(filename2) > 200) {
      LOG(ERROR) << StringPrintf("%s: filename too long", __func__);
      return;
    }
    sprintf(filename, "%s%u", filename2, block);
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: bytes=%u; file=%s slotnum=%d", __func__,
                        uiccContextLen, filename, slotnum);

    int fileStream = 0;

    fileStream = open(filename, O_RDWR | O_CREAT, S_IRUSR | S_IWUSR);
    if (fileStream >= 0) {
      size_t actualWrittenCntx = 0;
      size_t actualWrittenCrc = 0;
      size_t actualWrittenTechCap = 0;
      size_t actualWrittenCntxLen = 0;

      if (slotnum == 1) {
        lseek(fileStream, 0, SEEK_SET);
      } else if (slotnum == 2) {
        lseek(fileStream,
              sizeof(dualUiccInfo.sUicc1Cntx) +
                  sizeof(dualUiccInfo.sUicc1TechCapblty),
              SEEK_SET);
      }

      actualWrittenCntxLen = write(fileStream, &uiccContextLen, 1);
      if (uiccContextLen > 0x00) {
        cntx_len = uiccContextLen;
        techCap = uiccTechCapLen;
        crcVal = calc_crc16(uiccContext, cntx_len);
      }

      frameByte = (uint8_t*)&crcVal;
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s:CRC calculated %02x %02x", __func__, frameByte[0], frameByte[1]);

      actualWrittenCntx = write(fileStream, uiccContext, cntx_len);
      actualWrittenCrc = write(fileStream, frameByte, sizeof(crcVal));
      actualWrittenTechCap = write(fileStream, uiccTechCap, techCap);

      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: %u bytes written", __func__, cntx_len);
      if ((actualWrittenCntx == cntx_len) &&
          (actualWrittenTechCap == techCap)) {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("Write Success!");
      } else {
        LOG(ERROR) << StringPrintf("%s: fail to write", __func__);
      }
      close(fileStream);
    } else {
      LOG(ERROR) << StringPrintf("%s: fail to open, error = %d", __func__,
                                 errno);
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s : exit", __func__);
  }

  /**********************************************************************************
   **
   ** Function:        read_uicc_context
   **
   ** Description:     read UICC context from file
   **
   ** Returns:         none
   **
   **********************************************************************************/
  void read_uicc_context(uint8_t * uiccContext, uint16_t uiccContextLen,
                         uint8_t * uiccTechCap, uint16_t uiccTechCapLen,
                         uint8_t block, uint8_t slotnum) {
    if (!nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: STAT_DUAL_UICC_EXT_SWITCH not available. Returning", __func__);
      return;
    }
    char filename[256], filename2[256];
    uint8_t* readCrc = NULL;
    uint8_t* frameByte = NULL;
    uint16_t crcVal;
    uint8_t cmpStat;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s : enter", __func__);

    memset(filename, 0, sizeof(filename));
    memset(filename2, 0, sizeof(filename2));
    strcpy(filename2, "/data/nfc");
    strncat(filename2, "/nxpStorage.bin",
            sizeof(filename2) - strlen(filename2) - 1);
    if (strlen(filename2) > 200) {
      LOG(ERROR) << StringPrintf("%s: filename too long", __func__);
      return;
    }
    sprintf(filename, "%s%u", filename2, block);

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: buffer len=%u; file=%s, slotnum=%d", __func__,
                        uiccContextLen, filename, slotnum);
    int fileStream = open(filename, O_RDONLY);
    if (fileStream >= 0) {
      size_t actualReadCntx = 0;
      size_t actualReadCntxLen = 0;
      size_t actualReadCrc = 0;
      size_t actualReadTechCap = 0;
      uint8_t readCntxLen = 0;

      if (slotnum == 1) {
        lseek(fileStream, 0, SEEK_SET);
      } else if (slotnum == 2) {
        lseek(fileStream,
              sizeof(dualUiccInfo.sUicc1Cntx) +
                  sizeof(dualUiccInfo.sUicc1TechCapblty),
              SEEK_SET);
      }
      actualReadCntxLen = read(fileStream, &readCntxLen, 1);
      if (readCntxLen > 0x00) {
        actualReadCntx = read(fileStream, uiccContext, readCntxLen);
        readCrc = (uint8_t*)malloc(2 * sizeof(uint8_t));
        actualReadCrc = read(fileStream, readCrc, sizeof(crcVal));
        crcVal = calc_crc16(uiccContext, readCntxLen);
        frameByte = (uint8_t*)&crcVal;
        actualReadTechCap = read(fileStream, uiccTechCap, uiccTechCapLen);

        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "%s:CRC calculated %02x %02x -- CRC read %02x %02x", __func__,
            frameByte[0], frameByte[1], readCrc[0], readCrc[1]);
        cmpStat = memcmp(readCrc, frameByte, sizeof(crcVal));
        if (cmpStat == 0) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s:CRC check result - success", __func__);
        } else {
          DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
              "%s:CRC check result - failed. Resetting buffer", __func__);
          memset(uiccContext, 0x00, 128);
          memset(uiccTechCap, 0x00, 10);
          write_uicc_context(uiccContext, 0, uiccTechCap, 10, 1, slotnum);
        }
        free(readCrc);
      } else {
        memset(uiccContext, 0x00, 128);
        memset(uiccTechCap, 0x00, 10);
      }

      if (slotnum == 1)
        dualUiccInfo.sUicc1CntxLen = readCntxLen;
      else if (slotnum == 2)
        dualUiccInfo.sUicc2CntxLen = readCntxLen;

      close(fileStream);
      if (actualReadCntx > 0) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: data size=%zu", __func__, actualReadCntx);
      } else {
        LOG(ERROR) << StringPrintf("%s: fail to read", __func__);
      }
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: fail to open", __func__);
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s : exit", __func__);
  }

  /*******************************************************************************
  **
  ** Function         calc_crc16
  **
  ** Description      Calculates CRC16 for the frame buffer
  **
  ** Parameters       pBuff - CRC16 calculation input buffer
  **                  wLen  - input buffer length
  **
  ** Returns          wCrc  - computed 2 byte CRC16 value
  **
  *******************************************************************************/
  uint16_t calc_crc16(uint8_t * pBuff, uint16_t wLen) {
    if (!nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: STAT_DUAL_UICC_EXT_SWITCH not available. Returning", __func__);
      return 0xFF;
    }
    uint16_t wTmp;
    uint16_t wValue;
    uint16_t wCrc = 0xffff;
    uint32_t i;
    uint16_t aCrcTab[256] = {
        0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50a5, 0x60c6, 0x70e7, 0x8108,
        0x9129, 0xa14a, 0xb16b, 0xc18c, 0xd1ad, 0xe1ce, 0xf1ef, 0x1231, 0x0210,
        0x3273, 0x2252, 0x52b5, 0x4294, 0x72f7, 0x62d6, 0x9339, 0x8318, 0xb37b,
        0xa35a, 0xd3bd, 0xc39c, 0xf3ff, 0xe3de, 0x2462, 0x3443, 0x0420, 0x1401,
        0x64e6, 0x74c7, 0x44a4, 0x5485, 0xa56a, 0xb54b, 0x8528, 0x9509, 0xe5ee,
        0xf5cf, 0xc5ac, 0xd58d, 0x3653, 0x2672, 0x1611, 0x0630, 0x76d7, 0x66f6,
        0x5695, 0x46b4, 0xb75b, 0xa77a, 0x9719, 0x8738, 0xf7df, 0xe7fe, 0xd79d,
        0xc7bc, 0x48c4, 0x58e5, 0x6886, 0x78a7, 0x0840, 0x1861, 0x2802, 0x3823,
        0xc9cc, 0xd9ed, 0xe98e, 0xf9af, 0x8948, 0x9969, 0xa90a, 0xb92b, 0x5af5,
        0x4ad4, 0x7ab7, 0x6a96, 0x1a71, 0x0a50, 0x3a33, 0x2a12, 0xdbfd, 0xcbdc,
        0xfbbf, 0xeb9e, 0x9b79, 0x8b58, 0xbb3b, 0xab1a, 0x6ca6, 0x7c87, 0x4ce4,
        0x5cc5, 0x2c22, 0x3c03, 0x0c60, 0x1c41, 0xedae, 0xfd8f, 0xcdec, 0xddcd,
        0xad2a, 0xbd0b, 0x8d68, 0x9d49, 0x7e97, 0x6eb6, 0x5ed5, 0x4ef4, 0x3e13,
        0x2e32, 0x1e51, 0x0e70, 0xff9f, 0xefbe, 0xdfdd, 0xcffc, 0xbf1b, 0xaf3a,
        0x9f59, 0x8f78, 0x9188, 0x81a9, 0xb1ca, 0xa1eb, 0xd10c, 0xc12d, 0xf14e,
        0xe16f, 0x1080, 0x00a1, 0x30c2, 0x20e3, 0x5004, 0x4025, 0x7046, 0x6067,
        0x83b9, 0x9398, 0xa3fb, 0xb3da, 0xc33d, 0xd31c, 0xe37f, 0xf35e, 0x02b1,
        0x1290, 0x22f3, 0x32d2, 0x4235, 0x5214, 0x6277, 0x7256, 0xb5ea, 0xa5cb,
        0x95a8, 0x8589, 0xf56e, 0xe54f, 0xd52c, 0xc50d, 0x34e2, 0x24c3, 0x14a0,
        0x0481, 0x7466, 0x6447, 0x5424, 0x4405, 0xa7db, 0xb7fa, 0x8799, 0x97b8,
        0xe75f, 0xf77e, 0xc71d, 0xd73c, 0x26d3, 0x36f2, 0x0691, 0x16b0, 0x6657,
        0x7676, 0x4615, 0x5634, 0xd94c, 0xc96d, 0xf90e, 0xe92f, 0x99c8, 0x89e9,
        0xb98a, 0xa9ab, 0x5844, 0x4865, 0x7806, 0x6827, 0x18c0, 0x08e1, 0x3882,
        0x28a3, 0xcb7d, 0xdb5c, 0xeb3f, 0xfb1e, 0x8bf9, 0x9bd8, 0xabbb, 0xbb9a,
        0x4a75, 0x5a54, 0x6a37, 0x7a16, 0x0af1, 0x1ad0, 0x2ab3, 0x3a92, 0xfd2e,
        0xed0f, 0xdd6c, 0xcd4d, 0xbdaa, 0xad8b, 0x9de8, 0x8dc9, 0x7c26, 0x6c07,
        0x5c64, 0x4c45, 0x3ca2, 0x2c83, 0x1ce0, 0x0cc1, 0xef1f, 0xff3e, 0xcf5d,
        0xdf7c, 0xaf9b, 0xbfba, 0x8fd9, 0x9ff8, 0x6e17, 0x7e36, 0x4e55, 0x5e74,
        0x2e93, 0x3eb2, 0x0ed1, 0x1ef0};

    if ((NULL == pBuff) || (0 == wLen)) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: Invalid Params supplied", __func__);
    } else {
      /* Perform CRC calculation according to ccitt with a initial value of
       * 0x1d0f */
      for (i = 0; i < wLen; i++) {
        wValue = 0x00ffU & (uint16_t)pBuff[i];
        wTmp = (wCrc >> 8U) ^ wValue;
        wCrc = (wCrc << 8U) ^ aCrcTab[wTmp];
      }
    }

    return wCrc;
  }

  /**********************************************************************************
   **
   ** Function:        getUiccSession
   **
   ** Description:     Read and store UICC session values
   **
   ** Returns:         UICC Configured status
   **                  1 : failed
   **                  0 : success
   **
   **********************************************************************************/
  static int getUiccSession() {
    if (!nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: STAT_DUAL_UICC_EXT_SWITCH not available. Returning", __func__);
      return 0;
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);

    int cmpStat = 0, sUiccConfigured = 1;
    /*techInfo will be set if any DISCOVERY_REQ_NTF is received for current UICC
     *It will be used to validate received session id belongs to current
     *selected UICC or not
     * */
    bool techInfo = SecureElement::getInstance().isTeckInfoReceived(
        SecureElement::getInstance().EE_HANDLE_0xF4);
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: techInfo 0x%02x", __func__, techInfo);

    /* sConfig will have session ID received
     * If received different from previous UICC save it in corresponding UICC
     * buffer
     * If same, reset the UICC buffer
     * */
    if (sSelectedUicc == 0x01) {
      if (dualUiccInfo.sUicc2SessionIdLen != 0) {
        cmpStat = memcmp(sConfig + 4, dualUiccInfo.sUicc2SessionId,
                         dualUiccInfo.sUicc2SessionIdLen);
        if ((cmpStat == 0) || (!techInfo)) {
          memset(dualUiccInfo.sUicc1SessionId, 0x00,
                 sizeof(dualUiccInfo.sUicc1SessionId));
          dualUiccInfo.sUicc1SessionIdLen = 0;
          sUiccConfigured = 1;
        } else {
          memcpy(dualUiccInfo.sUicc1SessionId, sConfig + 4, 8);
          dualUiccInfo.sUicc1SessionIdLen = 8;
          sUiccConfigured = 0;
        }
      } else if (techInfo) {
        memcpy(dualUiccInfo.sUicc1SessionId, sConfig + 4, 8);
        dualUiccInfo.sUicc1SessionIdLen = 8;
        sUiccConfigured = 0;
      }
    } else if (sSelectedUicc == 0x02) {
      if (dualUiccInfo.sUicc1SessionIdLen != 0) {
        cmpStat = memcmp(sConfig + 4, dualUiccInfo.sUicc1SessionId,
                         dualUiccInfo.sUicc1SessionIdLen);
        if ((cmpStat == 0) || (!techInfo)) {
          memset(dualUiccInfo.sUicc2SessionId, 0x00,
                 sizeof(dualUiccInfo.sUicc2SessionId));
          dualUiccInfo.sUicc2SessionIdLen = 0;
          sUiccConfigured = 1;
        } else {
          memcpy(dualUiccInfo.sUicc2SessionId, sConfig + 4, 8);
          dualUiccInfo.sUicc2SessionIdLen = 8;
          sUiccConfigured = 0;
        }
      } else if (techInfo) {
        memcpy(dualUiccInfo.sUicc2SessionId, sConfig + 4, 8);
        dualUiccInfo.sUicc2SessionIdLen = 8;
        sUiccConfigured = 0;
      }
    }
    return sUiccConfigured;
  }
  /**********************************************************************************
   **
   ** Function:        notifyUiccEvent
   **
   ** Description:     Notifies UICC event sto Service
   **                  Possible values:
   **                  UICC_CONNECTED_0 - 0 UICC connected
   **                  UICC_CONNECTED_1 - 1 UICC connected
   **                  UICC_CONNECTED_2 - 2 UICCs connected
   **
   ** Returns:         None
   **
   **********************************************************************************/
  static void notifyUiccEvent(union sigval) {
    if (!nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: STAT_DUAL_UICC_EXT_SWITCH not available. Returning", __func__);
      return;
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", __func__);
    struct nfc_jni_native_data* nat = getNative(NULL, NULL);
    JNIEnv* e;
    ScopedAttach attach(nat->vm, &e);
    if (e == NULL) {
      LOG(ERROR) << StringPrintf("jni env is null");
      return;
    }
    if (dualUiccInfo.uiccActivStat == 0x00) /*No UICC Detected*/
    {
      e->CallVoidMethod(nat->manager,
                        android::gCachedNfcManagerNotifyUiccStatusEvent,
                        UICC_CONNECTED_0);
    } else if ((dualUiccInfo.uiccActivStat == 0x01) ||
               (dualUiccInfo.uiccActivStat == 0x02)) /*One UICC Detected*/
    {
      e->CallVoidMethod(nat->manager,
                        android::gCachedNfcManagerNotifyUiccStatusEvent,
                        UICC_CONNECTED_1);
    } else if (dualUiccInfo.uiccActivStat == 0x03) /*Two UICC Detected*/
    {
      e->CallVoidMethod(nat->manager,
                        android::gCachedNfcManagerNotifyUiccStatusEvent,
                        UICC_CONNECTED_2);
    }
  }

  static int nfcManager_staticDualUicc_Precondition(int uiccSlot) {
    if (!nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH &&
        !nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_WO_EXT_SWITCH) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s:Dual UICC feature not available . Returning", __func__);
      return DUAL_UICC_FEATURE_NOT_AVAILABLE;
    }
    unsigned long uicc_active_state = 0;

    uint8_t retStat = UICC_NOT_CONFIGURED;
    if (nfcFL.nfccFL._NFC_NXP_STAT_DUAL_UICC_EXT_SWITCH) {
      if (NfcConfig::hasKey(NAME_NXP_DUAL_UICC_ENABLE)) {
        uicc_active_state = NfcConfig::getUnsigned(NAME_NXP_DUAL_UICC_ENABLE);
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "NXP_DUAL_UICC_ENABLE  : 0x%02lx", uicc_active_state);
      } else {
        LOG(ERROR) << StringPrintf(
            "NXP_DUAL_UICC_ENABLE Not found taking default value 0x00");
        uicc_active_state = 0x00;
      }

      if (uicc_active_state != 0x01) {
        LOG(ERROR) << StringPrintf("%s:FAIL Dual UICC feature not available",
                                   __func__);
        retStat = DUAL_UICC_FEATURE_NOT_AVAILABLE;
      }
    }

    if (sIsDisabling) {
      LOG(ERROR) << StringPrintf(
          "%s:FAIL Nfc is Disabling : Switch UICC not allowed", __func__);
      retStat = DUAL_UICC_ERROR_NFC_TURNING_OFF;
    } else if (SecureElement::getInstance().isBusy()) {
      LOG(ERROR) << StringPrintf("%s:FAIL  SE wired-mode : busy", __func__);
      retStat = DUAL_UICC_ERROR_NFCC_BUSY;
    } else if (rfActivation) {
      LOG(ERROR) << StringPrintf("%s:FAIL  RF session ongoing", __func__);
      retStat = DUAL_UICC_ERROR_NFCC_BUSY;
    } else if ((uiccSlot != 0x01) && (uiccSlot != 0x02)) {
      LOG(ERROR) << StringPrintf("%s: Invalid slot id", __func__);
      retStat = DUAL_UICC_ERROR_INVALID_SLOT;
    } else if (SecureElement::getInstance().isRfFieldOn()) {
      LOG(ERROR) << StringPrintf("%s:FAIL  RF field on", __func__);
      retStat = DUAL_UICC_ERROR_NFCC_BUSY;
    } else if (sDiscoveryEnabled || sRfEnabled) {
      if (!pTransactionController->transactionAttempt(
              TRANSACTION_REQUESTOR(staticDualUicc))) {
        LOG(ERROR) << StringPrintf("%s: Transaction in progress. Can not set",
                                   __func__);
        retStat = DUAL_UICC_ERROR_NFCC_BUSY;
      } else {
        LOG(ERROR) << StringPrintf("%s: Transaction state enabled", __func__);
      }
    }
    return retStat;
  }

  static void nfaNxpSelfTestNtfCallback(uint8_t event, uint16_t param_len,
                                        uint8_t * p_param) {
    (void)event;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s", __func__);

    if (param_len == 0x05 &&
        p_param[3] == 00)  // p_param[4]  0x00:SWP Link OK 0x03:SWP link dead.
    {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NXP SWP SelfTest : SWP Link OK ");
      SetCbStatus(NFA_STATUS_OK);
    } else {
      if (p_param[3] == 0x03)
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("NXP SWP SelfTest : SWP Link dead ");
      SetCbStatus(NFA_STATUS_FAILED);
    }

    switch (p_param[4]) {  // information of PMUVCC.
      case 0x00:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("NXP SWP SelfTest : No PMUVCC ");
        break;
      case 0x01:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("NXP SWP SelfTest : PMUVCC = 1.8V ");
        break;
      case 0x02:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("NXP SWP SelfTest : PMUVCC = 3.3V ");
        break;
      case 0x03:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("NXP SWP SelfTest : PMUVCC = undetermined ");
        break;
      default:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("NXP SWP SelfTest : unknown PMUVCC ");
        break;
    }

    SyncEventGuard guard(sNfaNxpNtfEvent);
    sNfaNxpNtfEvent.notifyOne();
  }

  static void nfcManager_doPrbsOn(JNIEnv * e, jobject o, jint prbs,
                                  jint hw_prbs, jint tech, jint rate) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    tNFA_STATUS status = NFA_STATUS_FAILED;
    //    bool stat = false;                    /*commented to eliminate unused
    //    variable warning*/

    if (!sIsNfaEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NFC does not enabled!!");
      return;
    }

    if (sDiscoveryEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Discovery must not be enabled for SelfTest");
      return;
    }

    if (tech < 0 || tech > 2) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Invalid tech! please choose A or B or F");
      return;
    }

    if (rate < 0 || rate > 3) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "Invalid bitrate! please choose 106 or 212 or 424 or 848");
      return;
    }

    // Technology to stream          0x00:TypeA 0x01:TypeB 0x02:TypeF
    // Bitrate                       0x00:106kbps 0x01:212kbps 0x02:424kbps
    // 0x03:848kbps
    // prbs and hw_prbs              0x00 or 0x01 two extra parameters included
    // in case of pn548AD
    uint8_t param[4];
    memset(param, 0x00, sizeof(param));
    if (nfcFL.chipType != pn547C2) {
      param[0] = prbs;
      param[1] = hw_prbs;
      param[2] = tech;  // technology
      param[3] = rate;  // bitrate
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "phNxpNciHal_getPrbsCmd: PRBS = %d  HW_PRBS = %d", prbs, hw_prbs);
    } else {
      param[0] = tech;
      param[1] = rate;
    }
    switch (tech) {
      case 0x00:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("phNxpNciHal_getPrbsCmd - NFC_RF_TECHNOLOGY_A");
        break;
      case 0x01:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("phNxpNciHal_getPrbsCmd - NFC_RF_TECHNOLOGY_B");
        break;
      case 0x02:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("phNxpNciHal_getPrbsCmd - NFC_RF_TECHNOLOGY_F");
        break;
      default:
        break;
    }
    switch (rate) {
      case 0x00:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("phNxpNciHal_getPrbsCmd - NFC_BIT_RATE_106");
        break;
      case 0x01:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("phNxpNciHal_getPrbsCmd - NFC_BIT_RATE_212");
        break;
      case 0x02:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("phNxpNciHal_getPrbsCmd - NFC_BIT_RATE_424");
        break;
      case 0x03:
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("phNxpNciHal_getPrbsCmd - NFC_BIT_RATE_848");
        break;
    }
    // step2. PRBS Test stop : CORE RESET_CMD
    status = Nxp_SelfTest(NFC_CMD_TYPE_CORE_RESET, param);  // CORE_RESET_CMD
    if (NFA_STATUS_OK != status) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: CORE RESET_CMD Fail!", __func__);
      status = NFA_STATUS_FAILED;
      goto TheEnd;
    }
    // step3. PRBS Test stop : CORE_INIT_CMD
    status = Nxp_SelfTest(NFC_CMD_TYPE_CORE_INIT, param);  // CORE_INIT_CMD
    if (NFA_STATUS_OK != status) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: CORE_INIT_CMD Fail!", __func__);
      status = NFA_STATUS_FAILED;
      goto TheEnd;
    }
    // step4. : NXP_ACT_PROP_EXTN
    status =
        Nxp_SelfTest(NFC_CMD_TYPE_ACT_PROP_EXTN, param);  // NXP_ACT_PROP_EXTN
    if (NFA_STATUS_OK != status) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: NXP_ACT_PROP_EXTN Fail!", __func__);
      status = NFA_STATUS_FAILED;
      goto TheEnd;
    }

    status = Nxp_SelfTest(NFC_CMD_TYPE_PRBS_START, param);
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit; status =0x%X", __func__, status);

  TheEnd:
    // Factory Test Code
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit; status =0x%X", __func__, status);
    return;
  }

  static void nfcManager_doPrbsOff(JNIEnv * e, jobject o) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    tNFA_STATUS status = NFA_STATUS_FAILED;
    //    bool stat = false;                    /*commented to eliminate unused
    //    variable warning*/
    uint8_t param;

    if (!sIsNfaEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NFC does not enabled!!");
      return;
    }

    if (sDiscoveryEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Discovery must not be enabled for SelfTest");
      return;
    }

    // Factory Test Code
    // step1. PRBS Test stop : VEN RESET
    status = Nxp_SelfTest(NFC_CMD_TYPE_PRBS_STOP, &param);  // VEN RESET
    if (NFA_STATUS_OK != status) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("step1. PRBS Test stop : VEN RESET Fail!");
      status = NFA_STATUS_FAILED;
      goto TheEnd;
    }

  TheEnd:
    // Factory Test Code
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit; status =0x%X", __func__, status);

    return;
  }

  static jint nfcManager_SWPSelfTest(JNIEnv * e, jobject o, jint ch) {
    (void)e;
    (void)o;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    tNFA_STATUS status = NFA_STATUS_FAILED;
    tNFA_STATUS regcb_stat = NFA_STATUS_FAILED;
    uint8_t param[1];

    if (!sIsNfaEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NFC does not enabled!!");
      return status;
    }

    if (sDiscoveryEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Discovery must not be enabled for SelfTest");
      return status;
    }

    if (ch < 0 || ch > 1) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Invalid channel!! please choose 0 or 1");
      return status;
    }

    // step1.  : CORE RESET_CMD
    status = Nxp_SelfTest(NFC_CMD_TYPE_CORE_RESET, param);  // CORE_RESET_CMD
    if (NFA_STATUS_OK != status) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("step2. PRBS Test stop : CORE RESET_CMD Fail!");
      status = NFA_STATUS_FAILED;
      goto TheEnd;
    }

    // step2. : CORE_INIT_CMD
    status = Nxp_SelfTest(NFC_CMD_TYPE_CORE_INIT, param);  // CORE_INIT_CMD
    if (NFA_STATUS_OK != status) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("step3. PRBS Test stop : CORE_INIT_CMD Fail!");
      status = NFA_STATUS_FAILED;
      goto TheEnd;
    }

    // step3. : NXP_ACT_PROP_EXTN
    status =
        Nxp_SelfTest(NFC_CMD_TYPE_ACT_PROP_EXTN, param);  // NXP_ACT_PROP_EXTN
    if (NFA_STATUS_OK != status) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("step: NXP_ACT_PROP_EXTN Fail!");
      status = NFA_STATUS_FAILED;
      goto TheEnd;
    }

    regcb_stat = NFA_RegVSCback(
        true, nfaNxpSelfTestNtfCallback);  // Register CallBack for NXP NTF
    if (NFA_STATUS_OK != regcb_stat) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("To Regist Ntf Callback is Fail!");
      goto TheEnd;
    }

    param[0] = ch;  // SWP channel 0x00 : SWP1(UICC) 0x01:SWP2(eSE)
    status = Nxp_SelfTest(NFC_CMD_TYPE_SWP, param);
    if (NFA_STATUS_OK != status) {
      status = NFA_STATUS_FAILED;
      goto TheEnd;
    }

    {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NFC NXP SelfTest wait for Notificaiton");
      nfaNxpSelfTestNtfTimer.set(1000, nfaNxpSelfTestNtfTimerCb);
      SyncEventGuard guard(sNfaNxpNtfEvent);
      sNfaNxpNtfEvent.wait();  // wait for NXP Self NTF to come
    }

    status = GetCbStatus();
    if (NFA_STATUS_OK != status) {
      status = NFA_STATUS_FAILED;
    }

  TheEnd:
    if (NFA_STATUS_OK == regcb_stat) {
      regcb_stat = NFA_RegVSCback(
          false, nfaNxpSelfTestNtfCallback);  // DeRegister CallBack for NXP NTF
    }
    nfaNxpSelfTestNtfTimer.kill();
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit; status =0x%X", __func__, status);
    return status;
  }

  /*******************************************************************************
   **
   ** Function:       nfcManager_doPartialDeInitialize
   **
   ** Description:    DeInitializes the NFC partially if it is partially
   *initialized.
   **
   ** Returns:        true/false .
   **
   *******************************************************************************/
  static bool nfcManager_doPartialDeInitialize() {
    tNFA_STATUS stat = NFA_STATUS_OK;
    if (!gsNfaPartialEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s: cannot deinitialize NFC , not partially initilaized", __func__);
      return true;
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:enter", __func__);
    stat = NFA_Disable(true /* graceful */);
    if (stat == NFA_STATUS_OK) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: wait for completion", __func__);
      SyncEventGuard guard(sNfaDisableEvent);
      sNfaDisableEvent.wait();  // wait for NFA command to finish
    } else {
      LOG(ERROR) << StringPrintf("%s: fail disable; error=0x%X", __func__,
                                 stat);
    }
    NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
    theInstance.Finalize();
    NFA_SetBootMode(NFA_NORMAL_BOOT_MODE);
    gsNfaPartialEnabled = false;
    return true;
  }

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

  static void nfcManager_doSetEEPROM(JNIEnv * e, jobject o, jbyteArray val) {
    (void)e;
    (void)o;
    (void)val;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    tNFA_STATUS status = NFA_STATUS_FAILED;
    //    bool stat = false;                        /*commented to eliminate
    //    unused variable warning*/
    //    uint8_t param;                              /*commented to eliminate
    //    unused variable warning*/

    if (!sIsNfaEnabled) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NFC does not enabled!!");
      return;
    }

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit; status =0x%X", __func__, status);

    return;
  }

  /*******************************************************************************
   **
   ** Function:        getUICC_RF_Param_SetSWPBitRate()
   **
   ** Description:     Get All UICC Parameters and set SWP bit rate
   **
   ** Returns:         success/failure
   **
   *******************************************************************************/
  tNFA_STATUS getUICC_RF_Param_SetSWPBitRate() {
    tNFA_STATUS status = NFA_STATUS_FAILED;
    tNFA_PMID rf_params_NFCEE_UICC[] = {0xA0, 0xEF};
    uint8_t sakValue = 0x00;
    bool isMifareSupported;

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

    SyncEventGuard guard(android::sNfaGetConfigEvent);
    status = NFA_GetConfig(0x01, rf_params_NFCEE_UICC);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: NFA_GetConfig failed", __func__);
      return status;
    }
    android::sNfaGetConfigEvent.wait();
    sakValue = sConfig[SAK_VALUE_AT];
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("SAK Value =0x%X", sakValue);
    if ((sakValue & 0x08) == 0x00) {
      isMifareSupported = false;
    } else {
      isMifareSupported = true;
    }
    status = SetUICC_SWPBitRate(isMifareSupported);

    return status;
  }

  /*******************************************************************************
  **
  ** Function:        nfcManagerEnableAGCDebug
  **
  ** Description:     Enable/Disable Dynamic RSSI feature.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfcManagerEnableAGCDebug(uint8_t connEvent) {
    if (nfcFL.chipType == pn547C2) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s ,chipType : pn547C2. Not allowed. Returning", __func__);
      return;
    }
    unsigned long enableAGCDebug = 0;
    int retvalue = 0xFF;

    if (NfcConfig::hasKey(NAME_NXP_AGC_DEBUG_ENABLE)) {
      enableAGCDebug = NfcConfig::getUnsigned(NAME_NXP_AGC_DEBUG_ENABLE);
    }
    menableAGC_debug_t.enableAGC = enableAGCDebug;
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s ,%lu:", __func__, enableAGCDebug);
    if (sIsNfaEnabled != true || sIsDisabling == true) return;
    if (!menableAGC_debug_t.enableAGC) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s AGCDebug not enabled", __func__);
      return;
    }
    if (connEvent == NFA_TRANS_DM_RF_FIELD_EVT &&
        menableAGC_debug_t.AGCdebugstarted == false) {
      pthread_t agcThread;
      pthread_attr_t attr;
      pthread_attr_init(&attr);
      pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
      retvalue = pthread_create(&agcThread, &attr, enableAGCThread, NULL);
      pthread_attr_destroy(&attr);
      if (retvalue == 0) {
        menableAGC_debug_t.AGCdebugstarted = true;
        set_AGC_process_state(true);
      }
    }
  }

  void* enableAGCThread(void* arg) {
    if (nfcFL.chipType == pn547C2) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s ,chipType : pn547C2. Not allowed. Returning", __func__);
      return NULL;
    }
    tNFA_STATUS status = NFA_STATUS_FAILED;
    while (menableAGC_debug_t.AGCdebugstarted == true) {
      if (get_AGC_process_state() == false) {
        sleep(10000);
        continue;
      }

      if (sIsNfaEnabled != true || sIsDisabling == true) {
        menableAGC_debug_t.AGCdebugstarted = false;
        set_AGC_process_state(false);
        break;
      }

      status = SendAGCDebugCommand();
      if (status == NFA_STATUS_OK) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s:  enable success exit", __func__);
      }
      usleep(500000);
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
    pthread_exit(NULL);
    return NULL;
  }
  /*******************************************************************************
   **
   ** Function:       set_AGC_process_state
   **
   ** Description:    sets the AGC process to stop
   **
   ** Returns:        None .
   **
   *******************************************************************************/
  void set_AGC_process_state(bool state) {
    if (nfcFL.chipType == pn547C2) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s ,chipType : pn547C2. Not allowed. Returning", __func__);
      return;
    }
    menableAGC_debug_t.AGCdebugrunning = state;
  }

  /*******************************************************************************
   **
   ** Function:       get_AGC_process_state
   **
   ** Description:    returns the AGC process state.
   **
   ** Returns:        true/false .
   **
   *******************************************************************************/
  bool get_AGC_process_state() {
    if (nfcFL.chipType == pn547C2) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s ,chipType : pn547C2. Not allowed. Returning", __func__);
      return false;
    }
    return menableAGC_debug_t.AGCdebugrunning;
  }

  /*******************************************************************************
   **
   ** Function:        getrfDiscoveryDuration()
   **
   ** Description:     gets the current rf discovery duration.
   **
   ** Returns:         uint16_t
   **
   *******************************************************************************/
  uint16_t getrfDiscoveryDuration() { return discDuration; }

#if (NXP_NFCC_HCE_F == TRUE)
  /*******************************************************************************
   **
   ** Function:       nfcManager_getTransanctionRequest
   **
   ** Description:    returns the payment check for HCE-F
   **
   ** Returns:        true/false .
   **
   *******************************************************************************/
  bool nfcManager_getTransanctionRequest(int t3thandle, bool registerRequest) {
    bool stat = false;

    if (!pTransactionController->transactionAttempt(
            TRANSACTION_REQUESTOR(getTransanctionRequest))) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Transcation is in progress store the requst %d %d",
                          t3thandle, registerRequest);
      set_last_request(T3T_CONFIGURE, NULL);
      if (!registerRequest) transaction_data.t3thandle = t3thandle;
      stat = true;
    } else {
      pTransactionController->transactionEnd(
          TRANSACTION_REQUESTOR(getTransanctionRequest));
    }
    return stat;
  }
#endif

  /*******************************************************************************
   **
   ** Function:        nfcManager_isTransanctionOnGoing(bool isInstallRequest)
   **
   ** Description:     Base on the input parameter.It update the parameter for
   **                  install/uninstall request.
   **
   ** Returns:         success/failure
   **
   *******************************************************************************/
  bool nfcManager_isTransanctionOnGoing(bool isInstallRequest) {
    if (!pTransactionController->transactionAttempt(
            TRANSACTION_REQUESTOR(isTransanctionOnGoing))) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf(" Transcation is in progress store the requst");
      set_last_request(RE_ROUTING, NULL);
      if (!isInstallRequest)
        transaction_data.isInstallRequest = isInstallRequest;
      return true;
    } else {
      pTransactionController->transactionEnd(
          TRANSACTION_REQUESTOR(isTransanctionOnGoing));
    }
    return false;
  }

  /*******************************************************************************
   **
   ** Function:        nfcManager_sendEmptyDataMsg()
   **
   ** Description:     Sends Empty Data packet
   **
   ** Returns:         True/False
   **
   *******************************************************************************/
  bool nfcManager_sendEmptyDataMsg() {
    if (!nfcFL.nfccFL._NXP_NFCC_EMPTY_DATA_PACKET) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "%s : NXP_NFCC_EMPTY_DATA_PACKET not available."
          "Returning",
          __func__);
      return false;
    }
    tNFA_STATUS status = NFA_STATUS_FAILED;
    size_t bufLen = 0;
    uint8_t* buf = NULL;
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("nfcManager_sendEmptyRawFrame");

    status = NFA_SendRawFrame(buf, bufLen, 0);

    return (status == NFA_STATUS_OK);
  }
  /*******************************************************************************
   **
   ** Function:        nfcManager_transactionDetail()
   **
   ** Description:     Provides transaction detail data reference
   **
   ** Returns:         Pointer to transaction data
   **
   *******************************************************************************/
  Transcation_Check_t* nfcManager_transactionDetail(void) {
    return &android::transaction_data;
  }
  /*******************************************************************************
   **
   ** Function:        nfcManager_getFeatureList()
   **
   ** Description:     Get the Chipe type & Configure Chip type macros
   **
   ** Returns:         None
   **
   *******************************************************************************/
  void nfcManager_getFeatureList() {
    tNFC_chipType chipType;  // = pn553;
    chipType = NFC_GetChipType();
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s : chipType", __func__);
    CONFIGURE_FEATURELIST(chipType);
  }
  /*******************************************************************************
   **
   ** Function:        register_signal_handler()
   **
   ** Description:     Register Signal handlers
   **
   ** Returns:         None .
   **
   *******************************************************************************/
  void register_signal_handler() {
    struct sigaction sig;

    memset(&sig, 0, sizeof(struct sigaction));
    sig.sa_sigaction = spi_prio_signal_handler;
    sig.sa_flags = SA_SIGINFO;
    if (sigaction(SIG_NFC, &sig, NULL) < 0) {
      LOG(ERROR) << StringPrintf(
          "Failed to register spi prio session signal handler");
    }
    if ((signal(SIGABRT, sig_handler) == SIG_ERR) &&
        (signal(SIGSEGV, sig_handler) == SIG_ERR)) {
      LOG(ERROR) << StringPrintf("Failed to register signal handler");
    }
  }

  extern void NxpPropCmd_OnResponseCallback(uint8_t event, uint16_t param_len,
                                            uint8_t * p_param);
  extern tNFA_STATUS NxpPropCmd_send(
      uint8_t * pData4Tx, uint8_t dataLen, uint8_t * rsp_len, uint8_t * rsp_buf,
      uint32_t rspTimeout, tHAL_NFC_ENTRY * halMgr);
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
      /*Retreive reference to HAL function manager*/
      halMgr = nfcAdapter.GetHalEntryFuncs();
    }
    /*Overidable method list*/
    virtual tNFA_STATUS preProcessor();
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
   ** Function:        isLowRamDevice()
   **
   ** Description:     Provides whether device is low ram enabled or not
   **
   ** Returns:         None .
   **
   *******************************************************************************/
  bool isLowRamDevice() { return sIsLowRamDevice; }

  /*
   * This API mainly used for Self-Test
   * */
  /*******************************************************************************
   **
   ** Function:        configureListening
   **
   ** Description:     Configure listen technologies
   **
   ** Returns:         success/failure
   **
   *******************************************************************************/
  tNFA_STATUS configureListening(uint8_t aListenMask) {
    tNFA_STATUS stat = NFA_STATUS_FAILED;
    SecureElement& se = SecureElement::getInstance();
    {
      SyncEventGuard guard(se.mUiccListenEvent);
      stat = NFA_CeConfigureUiccListenTech(se.mActiveEeHandle, aListenMask);
      if (stat == NFA_STATUS_OK) {
        se.mUiccListenEvent.wait();
        if (!aListenMask) stat = NFA_DisableListening();
      } else {
        LOG(ERROR) << StringPrintf("fail to start UICC listen");
      }
    }
    return stat;
  }

  /*******************************************************************************
   **
   ** Function:        configurePolling
   **
   ** Description:     Configure polling technologies
   **
   ** Returns:         success/failure
   **
   *******************************************************************************/
  tNFA_STATUS configurePolling(uint8_t aPollMask) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: enter poll mask=0x%02X", __func__, aPollMask);
    tNFA_STATUS stat = NFA_STATUS_FAILED;

    if (aPollMask > 0) {
      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("enable polling");
      stat = NFA_EnablePolling(aPollMask);
      if (stat == NFA_STATUS_OK) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("wait for enable event");
        sPollingEnabled = true;
        sNfaEnableDisablePollingEvent.wait();  // Wait for NFA_POLL_ENABLED_EVT.
      } else {
        LOG(ERROR) << StringPrintf("NFA_EnablePolling fail, error=0x%X", stat);
      }
    } else {
      SyncEventGuard guard(sNfaEnableDisablePollingEvent);
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("disable polling");
      stat = NFA_DisablePolling();
      if (stat == NFA_STATUS_OK) {
        sPollingEnabled = false;
        sNfaEnableDisablePollingEvent
            .wait();  // Wait for NFA_POLL_DISABLED_EVT.
      } else {
        LOG(ERROR) << StringPrintf("NFA_DisablePolling fail, error=0x%X", stat);
      }
    }
    if (aPollMask > 0) startRfDiscovery(true);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
    return stat;
  }

  /*******************************************************************************
   **
   ** Function:        performRFTest
   **
   ** Description:     SelfTest, Analog RF ON/OFF commands
   **
   ** Returns:         success/failure
   **
   *******************************************************************************/
  tNFA_STATUS performRFTest(bool on) {
    tNFA_STATUS status = NFA_STATUS_FAILED;
    uint8_t aNumOfCmds = 0;
    uint8_t count = 0;
    uint8_t NFCInitCmdSeq[5] = {NFC_CMD_TYPE_CORE_RESET, NFC_CMD_TYPE_CORE_INIT,
                                NFC_CMD_TYPE_ACT_PROP_EXTN};
    aNumOfCmds = sizeof(NFCInitCmdSeq);
    if (on) {
      NFCInitCmdSeq[3] = NFC_CMD_TYPE_NFCC_STANDBY_OFF;
      NFCInitCmdSeq[4] = NFC_CMD_TYPE_RF_ON;
    } else {
      NFCInitCmdSeq[3] = NFC_CMD_TYPE_RF_OFF;
      NFCInitCmdSeq[4] = NFC_CMD_TYPE_NFCC_STANDBY_ON;
    }

    do {
      status = Nxp_SelfTest(NFCInitCmdSeq[count], 0x00);
    } while ((status == NFA_STATUS_OK) && (++count < aNumOfCmds));

    return status;
  }

  /*******************************************************************************
   **
   ** Function:        performTransacAB
   **
   ** Description:     SelfTest SWP, PRBS
   **
   ** Returns:         success/failure
   **
   *******************************************************************************/
  tNFA_STATUS performTransacAB(uint8_t aType) {
    tNFA_STATUS status = NFA_STATUS_FAILED;
    uint8_t val[][1] = {{0x03}, {0x63}};
    uint8_t len[] = {0x01, 0x01};
    uint8_t tech_mask = 0;
    uint8_t count = 0, addCnt = 0;
    tNFA_PMID addBuf[][2] = {{0xA0, 0x3F}, {0xA0, 0x44}};
    uint8_t aNumOfCmds = 0;
    uint8_t NFCInitCmdSeq[3] = {NFC_CMD_TYPE_CORE_RESET, NFC_CMD_TYPE_CORE_INIT,
                                NFC_CMD_TYPE_ACT_PROP_EXTN};
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

    configureListening(0x00);  // Disable listen phase
    configurePolling(
        0x00);  // This is remove previous poll config as part of Nfc-ON
    if (aType == NFC_CMD_TYPE_TRANSAC_A) {
      tech_mask = NFA_TECHNOLOGY_MASK_A;
    } else {
      *val[1] = 0x43;
      tech_mask = NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B;
    }

    aNumOfCmds = sizeof(NFCInitCmdSeq);
    do {
      status = Nxp_SelfTest(NFCInitCmdSeq[count], 0x00);
    } while ((status == NFA_STATUS_OK) && (++count < aNumOfCmds));
    count = 0;
    if (status == NFA_STATUS_OK) {
      addCnt = sizeof(addBuf) / sizeof(addBuf[0]);
      while ((status == NFA_STATUS_OK) && (count < addCnt)) {
        status = NxpNfcUpdateEeprom(addBuf[count], len[count], val[count]);
        count++;
      }
    } else {
      LOG(ERROR) << StringPrintf("failed in to reset and init NFCC");
    }
    if (status == NFA_STATUS_OK) {
      uint8_t val68[] = {0x00};
      SyncEventGuard guard(sNfaSetConfigEvent);
      status = NFA_SetConfig(NXP_NFC_NCI_PACM_BIT_RATE, 1, val68);
      if (status == NFA_STATUS_OK) {
        if (sNfaSetConfigEvent.wait(2 * ONE_SECOND_MS)) {
          status = Nxp_SelfTest(NFC_CMD_TYPE_DISC_MAP, 0x00);
        } else {
          LOG(ERROR) << StringPrintf("Wait timeout");
          status = NFA_STATUS_FAILED;
        }
      } else {
        LOG(ERROR) << StringPrintf("failed to send set config command");
      }
    }
    if (status == NFA_STATUS_OK) {
      if ((status = configurePolling(tech_mask)) == NFA_STATUS_OK) {
        SyncEventGuard gaurd(mSelfTestTransacAB);
        if (mSelfTestTransacAB.wait(30 * ONE_SECOND_MS)) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("Received activated ntf");
          status = Nxp_SelfTest(NFC_CMD_TYPE_DEACTIVATE, 0x00);
        }
      } else {
        // Do nothing
      }
    } else {
      // Do nothing, enter in to normal polling mode
    }
    startRfDiscovery(true);
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit status = 0X%02X", __func__, status);
    return status;
  }

  /*******************************************************************************
   **
   ** Function:        activatedNtf_Cb
   **
   ** Description:     Receive activation notification from
   *nfaConnectionCallback
   **
   ** Returns:         None
   **
   *******************************************************************************/
  void activatedNtf_Cb() {
    SyncEventGuard gaurd(mSelfTestTransacAB);
    mSelfTestTransacAB.notifyOne();
  }
#endif
}

/* namespace android */
