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
 *  Copyright (C) 2015-2020 NXP Semiconductors
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
/*
 *  Communicate with secure elements that are attached to the NFC
 *  controller.
 */
#pragma once
#include "DataQueue.h"
#include "IntervalTimer.h"
#include "NfcJniUtil.h"
#include "RouteDataSet.h"
#include "SyncEvent.h"
#include "nfa_ce_api.h"
#include "nfa_ee_api.h"
#include "nfa_hci_api.h"
#include "nfa_hci_defs.h"
#include "phNxpExtns.h"
#if (NXP_EXTNS == TRUE)
#include "phNfcTypes.h"
#endif
#if (NXP_EXTNS == TRUE)
#define CONNECTIVITY_PIPE_ID_UICC1 0x0A
#define CONNECTIVITY_PIPE_ID_UICC2 0x23

#define SIG_NFC 44
#define SIG_SPI_EVENT_HANDLER 45
#endif
#define SIGNAL_EVENT_SIZE 0x02
typedef enum {
  RESET_TRANSACTION_STATE,
  SET_TRANSACTION_STATE
} transaction_state_t;

typedef enum dual_mode {
  SPI_DWPCL_NOT_ACTIVE = 0x00,
  CL_ACTIVE = 0x01,
  SPI_ON = 0x02,
  SPI_DWPCL_BOTH_ACTIVE = 0x03,
} dual_mode_state;
#if (NXP_EXTNS == TRUE)
typedef enum connectivity_pipe_status {
  PIPE_DELETED,
  PIPE_CLOSED,
  PIPE_OPENED
} pipe_status;

typedef enum {
  UICC_SESSION_NOT_INTIALIZED = 0x00,
  UICC_CLEAR_ALL_PIPE_NTF_RECEIVED = 0x01,
  UICC_SESSION_INTIALIZATION_DONE = 0x02
} nfcee_disc_state;

typedef enum {
  TRANSCEIVE_STATUS_OK,
  TRANSCEIVE_STATUS_FAILED,
  TRANSCEIVE_STATUS_MAX_WTX_REACHED
} eTransceiveStatus;
#endif
typedef enum {
  STATE_IDLE = 0x00,
  STATE_WK_ENBLE = 0x01,
  STATE_WK_WAIT_RSP = 0x02,
  STATE_TIME_OUT = 0x04,
  STATE_DWP_CLOSE = 0x08,
} spiDwpSyncState_t;

typedef enum reset_management {
  TRANS_IDLE = 0x00,
  TRANS_WIRED_ONGOING = 0x01,
  TRANS_CL_ONGOING = 0x02,
  RESET_BLOCKED = 0x04,
} ese_reset_control;

#if (NXP_EXTNS == TRUE)

typedef enum {
  UICC_01_SELECTED_ENABLED = 0x01,
  UICC_01_SELECTED_DISABLED,
  UICC_01_REMOVED,
  UICC_02_SELECTED_ENABLED,
  UICC_02_SELECTED_DISABLED,
  UICC_02_REMOVED,
  UICC_STATUS_UNKNOWN
} uicc_stat_t;
typedef enum {
  SWP_DEFAULT = 0x00,
  SWP1_UICC1 = 0x01,
  SWP2_ESE = 0x02,
  SWP1A_UICC2 = 0x04,
  T4T_NDEFEE = 0x08,
  HCI_ACESS = 0x10
} nfcee_swp_getconfig_status;
#endif

#if (NXP_EXTNS == TRUE)
typedef enum operation {
  STANDBY_TIMER_START,
  STANDBY_TIMER_STOP,
  STANDBY_TIMER_TIMEOUT,
  STANDBY_MODE_ON,       /* standby mode is on */
  STANDBY_MODE_OFF,      /* standby mode is off */
  STANDBY_MODE_SUSPEND,  /* standby timer timed out */
  STANDBY_MODE_TIMER_ON, /* standby timer running */
  STANDBY_ESE_PWR_ACQUIRE

} nfcc_standby_operation_t;

void spi_prio_signal_handler(int signum, siginfo_t* info, void* unused);

typedef enum apdu_gate {
  NO_APDU_GATE,
  PROPREITARY_APDU_GATE,
  ETSI_12_APDU_GATE
} se_apdu_gate_info;
#endif
typedef enum nfcee_type { UICC1 = 0x01, UICC2 = 0x02, ESE = 0x04 } nfcee_type_t;
typedef enum { NONE = 0x00, FW_DOWNLOAD, JCOP_DOWNLOAD } Downlaod_mode_t;
namespace android {
extern void startStopPolling(bool isStartPolling);

}  // namespace android

class SecureElement {
 public:
  tNFA_HANDLE mActiveEeHandle;
  bool mActivatedInListenMode;  // whether we're activated in listen mode
#if (NXP_EXTNS == TRUE)
#define MAX_NFCEE 5
  struct mNfceeData {
    tNFA_HANDLE mNfceeHandle[MAX_NFCEE];
    tNFA_EE_STATUS mNfceeStatus[MAX_NFCEE];
    uint8_t mNfceePresent;
  };
  mNfceeData mNfceeData_t;
  uint8_t mHostsPresent;
  uint8_t mETSI12InitStatus;
  uint8_t eSE_Compliancy;
  uint8_t mCreatedPipe;
  uint8_t mDeletePipeHostId;
  uint16_t mWmMaxWtxCount;
  bool meseETSI12Recovery;
  SyncEvent mCreatePipeEvent;
  SyncEvent mPipeOpenedEvent;
  SyncEvent mAbortEvent;
  SyncEvent mPipeStatusCheckEvent;
  bool mAbortEventWaitOk;
  SyncEvent mPipeListEvent;
  tNFA_HANDLE mNfaHciHandle;  // NFA handle to NFA's HCI component
  uint8_t pipeStatus;
  bool IsCmdsentOnOpenDwpSession;
  bool enableDwp(void);
  static const tNFA_HANDLE EE_HANDLE_0xF3 =
      0x4C0;  // 0x401; //handle to secure element in slot 0
  static const tNFA_HANDLE EE_HANDLE_0xF8 =
      0x481;                   // handle to secure element in slot 2
  tNFA_HANDLE EE_HANDLE_0xF4;  // handle to secure element in slot 1
  static const tNFA_HANDLE EE_HANDLE_0xF0 = 0x400;  // NFCEE handle for host
#endif
#define NCI_INTERFACE_UICC_DIRECT_STAT 0x82
#define NCI_INTERFACE_ESE_DIRECT_STAT 0x83
  static const int MAX_NUM_EE = NFA_EE_MAX_EE_SUPPORTED; /*max number of EE's*/

  /*******************************************************************************
  **
  ** Function:        getInstance
  **
  ** Description:     Get the SecureElement singleton object.
  **
  ** Returns:         SecureElement object.
  **
  *******************************************************************************/
  static SecureElement& getInstance();

  /*******************************************************************************
  **
  ** Function:        initialize
  **
  ** Description:     Initialize all member variables.
  **                  native: Native data.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  bool initialize(nfc_jni_native_data* native);

  /*******************************************************************************
  **
  ** Function:        finalize
  **
  ** Description:     Release all resources.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void finalize();

  /*******************************************************************************
  **
  ** Function:        getListOfEeHandles
  **
  ** Description:     Get the list of handles of all execution environments.
  **                  e: Java Virtual Machine.
  **
  ** Returns:         List of handles of all execution environments.
  **
  *******************************************************************************/
  jintArray getListOfEeHandles(JNIEnv* e);

  /*******************************************************************************
  **
  ** Function:        getActiveSecureElementList
  **
  ** Description:     Get the list of handles of all execution environments.
  **                  e: Java Virtual Machine.
  **
  ** Returns:         List of handles of all execution environments.
  **
  *******************************************************************************/
  jintArray getActiveSecureElementList(JNIEnv* e);

  /*******************************************************************************
  **
  ** Function:        activate
  **
  ** Description:     Turn on the secure element.
  **                  seID: ID of secure element.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  bool activate(jint seID);

  /*******************************************************************************
  **
  ** Function:        deactivate
  **
  ** Description:     Turn off the secure element.
  **                  seID: ID of secure element.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  bool deactivate(jint seID);

  /*******************************************************************************
  **
  ** Function:        connectEE
  **
  ** Description:     Connect to the execution environment.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  bool connectEE();

  bool activateAllNfcee();
  /*******************************************************************************
  **
  ** Function:        disconnectEE
  **
  ** Description:     Disconnect from the execution environment.
  **                  seID: ID of secure element.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  bool disconnectEE(jint seID);

/*******************************************************************************
**
** Function:        transceive
**
** Description:     Send data to the secure element; read it's response.
**                  xmitBuffer: Data to transmit.
**                  xmitBufferSize: Length of data.
**                  recvBuffer: Buffer to receive response.
**                  recvBufferMaxSize: Maximum size of buffer.
**                  recvBufferActualSize: Actual length of response.
**                  timeoutMillisec: timeout in millisecond
**
** Returns:         True if ok.
**
*******************************************************************************/
#if (NXP_EXTNS == TRUE)
  eTransceiveStatus transceive(uint8_t* xmitBuffer, int32_t xmitBufferSize,
                               uint8_t* recvBuffer, int32_t recvBufferMaxSize,
                               int32_t& recvBufferActualSize,
                               int32_t timeoutMillisec);
#else
  bool transceive(uint8_t* xmitBuffer, int32_t xmitBufferSize,
                  uint8_t* recvBuffer, int32_t recvBufferMaxSize,
                  int32_t& recvBufferActualSize, int32_t timeoutMillisec);
#endif

  void notifyModeSet(tNFA_HANDLE eeHandle, bool success,
                     tNFA_EE_STATUS eeStatus);

  /*******************************************************************************
  **
  ** Function:        notifyListenModeState
  **
  ** Description:     Notify the NFC service about whether the SE was activated
  **                  in listen mode.
  **                  isActive: Whether the secure element is activated.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void notifyListenModeState(bool isActivated);

  /*******************************************************************************
  **
  ** Function:        notifyRfFieldEvent
  **
  ** Description:     Notify the NFC service about RF field events from the
  *stack.
  **                  isActive: Whether any secure element is activated.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void notifyRfFieldEvent(bool isActive);

#if (NXP_EXTNS == TRUE)
  /*******************************************************************************
  **
  ** Function:        initializeEeHandle
  **
  ** Description:     Set NFCEE handle.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  bool initializeEeHandle();
#endif

  /*******************************************************************************
  **
  ** Function:        resetRfFieldStatus ();
  **
  ** Description:     Resets the field status.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void resetRfFieldStatus();

  /*******************************************************************************
  **
  ** Function:        storeUiccInfo
  **
  ** Description:     Store a copy of the execution environment information from
  *the stack.
  **                  info: execution environment information.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void storeUiccInfo(tNFA_EE_DISCOVER_REQ& info);

  /*******************************************************************************
  **
  ** Function:        getUiccId
  **
  ** Description:     Get the ID of the secure element.
  **                  eeHandle: Handle to the secure element.
  **                  uid: Array to receive the ID.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  bool getUiccId(tNFA_HANDLE eeHandle, jbyteArray& uid);

  /*******************************************************************************
  **
  ** Function:        notifyTransactionListenersOfAid
  **
  ** Description:     Notify the NFC service about a transaction event from
  *secure element.
  **                  aid: Buffer contains application ID.
  **                  aidLen: Length of application ID.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void notifyTransactionListenersOfAid(const uint8_t* aid, uint8_t aidLen,
                                       const uint8_t* data, uint32_t dataLen,
                                       uint32_t evtSrc);

  /*******************************************************************************
  **
  ** Function:        notifyTransactionListenersOfTlv
  **
  ** Description:     Notify the NFC service about a transaction event from
  *secure element.
  **                  The type-length-value contains AID and parameter.
  **                  tlv: type-length-value encoded in Basic Encoding Rule.
  **                  tlvLen: Length tlv.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void notifyTransactionListenersOfTlv(const uint8_t* tlv, uint8_t tlvLen);

  /*******************************************************************************
  **
  ** Function:        connectionEventHandler
  **
  ** Description:     Receive card-emulation related events from stack.
  **                  event: Event code.
  **                  eventData: Event data.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void connectionEventHandler(uint8_t event, tNFA_CONN_EVT_DATA* eventData);

  /*******************************************************************************
  **
  ** Function:        applyRoutes
  **
  ** Description:     Read route data from XML and apply them again
  **                  to every secure element.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void applyRoutes();

  /*******************************************************************************
  **
  ** Function:        setActiveSeOverride
  **
  ** Description:     Specify which secure element to turn on.
  **                  activeSeOverride: ID of secure element
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void setActiveSeOverride(uint8_t activeSeOverride);

  bool SecEle_Modeset(uint8_t type);
  /*******************************************************************************
  **
  ** Function:        routeToSecureElement
  **
  ** Description:     Adjust controller's listen-mode routing table so
  *transactions
  **                  are routed to the secure elements as specified in
  *route.xml.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  bool routeToSecureElement();

  /*******************************************************************************
  **
  ** Function:        isBusy
  **
  ** Description:     Whether NFC controller is routing listen-mode events or a
  *pipe is connected.
  **
  ** Returns:         True if either case is true.
  **
  *******************************************************************************/
  bool isBusy();

  /*******************************************************************************
  **
  ** Function         getActualNumEe
  **
  ** Description      Returns number of secure elements we know about.
  **
  ** Returns          Number of secure elements we know about.
  **
  *******************************************************************************/
  uint8_t getActualNumEe();

  /*******************************************************************************
  **
  ** Function         getSeVerInfo
  **
  ** Description      Gets version information and id for a secure element.  The
  **                  seIndex parmeter is the zero based index of the secure
  **                  element to get verion info for.  The version infommation
  **                  is returned as a string int the verInfo parameter.
  **
  ** Returns          ture on success, false on failure
  **
  *******************************************************************************/
  bool getSeVerInfo(int seIndex, char* verInfo, int verInfoSz, uint8_t* seid);

  /*******************************************************************************
  **
  ** Function:        isActivatedInListenMode
  **
  ** Description:     Can be used to determine if the SE is activated in listen
  *mode
  **
  ** Returns:         True if the SE is activated in listen mode
  **
  *******************************************************************************/
  bool isActivatedInListenMode();

  /*******************************************************************************
  **
  ** Function:        isRfFieldOn
  **
  ** Description:     Can be used to determine if the SE is in an RF field
  **
  ** Returns:         True if the SE is activated in an RF field
  **
  *******************************************************************************/
  bool isRfFieldOn();

  /*******************************************************************************
  **
  ** Function:        setEseListenTechMask
  **
  ** Description:     Can be used to force ESE to only listen the specific
  **                  Technologies.
  **                  NFA_TECHNOLOGY_MASK_A       0x01
  **                  NFA_TECHNOLOGY_MASK_B       0x02
  **
  ** Returns:         True if listening is configured.
  **
  *******************************************************************************/
  bool setEseListenTechMask(uint8_t tech_mask);

  bool sendEvent(uint8_t event);
  /*******************************************************************************
  **
  ** Function:        getAtr
  **
  ** Description:     Can be used to get the ATR response from connected eSE
  **
  ** Returns:         True if ATR response is returned successfully
  **
  *******************************************************************************/
  bool getAtr(jint seID, uint8_t* recvBuffer, int32_t* recvBufferSize);
#if (NXP_EXTNS == TRUE)
  bool getNfceeHostTypeList(void);
  bool configureNfceeETSI12();
  bool checkPipeStatusAndRecreate();
  void eSE_ClearAllPipe_handler(uint8_t host);
  /**********************************************************************************
   **
   ** Function:        getEeStatus
   **
   ** Description:     get the status of EE
   **
   ** Returns:         EE status .
   **
   **********************************************************************************/
  uint16_t getEeStatus(uint16_t eehandle);

  /**********************************************************************************
   **
   ** Function:        getUiccStatus
   **
   ** Description:     get the status of EE
   **
   ** Returns:         EE status .
   **
   **********************************************************************************/
  uicc_stat_t getUiccStatus(uint8_t selected_uicc);

  /*******************************************************************************
   **
   ** Function:        updateEEStatus
   **
   ** Description:     updateEEStatus
   **                  Reads EE related information from libnfc
   **                  and updates in JNI
   **
   ** Returns:         True if ok.
   **
   *******************************************************************************/
  bool updateEEStatus();

  /*******************************************************************************
   **
   ** Function:        isTeckInfoReceived
   **
   ** Description:     isTeckInfoReceived
   **                  Checks if discovery_req_ntf received
   **                  for a given EE
   **
   ** Returns:         True if ok.
   **
   *******************************************************************************/
  bool isTeckInfoReceived(uint16_t eeHandle);

#endif

#if (NXP_EXTNS == TRUE)
  /*******************************************************************************
  **
  ** Function:        enablePassiveListen
  **
  ** Description:     Enable or disable listening to Passive A/B
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  uint16_t enablePassiveListen(uint8_t event);

  uint16_t startThread(uint8_t thread_arg);

  bool mPassiveListenEnabled;
  bool meseUiccConcurrentAccess;
  IntervalTimer mPassiveListenTimer;
  uint32_t mPassiveListenTimeout;  // Retry timout value for passive listen
                                   // enable timer
  uint8_t mPassiveListenCnt;       // Retry cnt for passive listen enable timer
  SyncEvent mPassiveListenEvt;
  Mutex mPassiveListenMutex;
  Mutex mNfccStandbyMutex;
#endif
  jint getSETechnology(tNFA_HANDLE eeHandle);
  static const uint8_t UICC_ID = 0x02;
  static const uint8_t UICC2_ID = 0x03;
  static const uint8_t ESE_ID = 0x01;
  static const uint8_t DH_ID = 0x00;
#if (NXP_EXTNS == TRUE)
  static const uint8_t eSE_Compliancy_ETSI_9 = 9;
  static const uint8_t eSE_Compliancy_ETSI_12 = 12;
#endif

  void getEeHandleList(tNFA_HANDLE* list, uint8_t* count);

  tNFA_HANDLE getEseHandleFromGenericId(jint eseId);

  jint getGenericEseId(tNFA_HANDLE handle);
  uint8_t mDownloadMode;
#if (NXP_EXTNS == TRUE)

  bool meSESessionIdOk;
  SyncEvent mRfFieldOffEvent;
  void NfccStandByOperation(nfcc_standby_operation_t value);
  NFCSTATUS eSE_Chip_Reset(void);
  tNFA_STATUS SecElem_sendEvt_Abort();
  tNFA_STATUS reconfigureEseHciInit();
#endif
  bool checkForWiredModeAccess();
#if (NXP_EXTNS == TRUE)
  se_apdu_gate_info getApduGateInfo();
#endif
  SyncEvent mRoutingEvent;
  SyncEvent mAidAddRemoveEvent;
  SyncEvent mUiccListenEvent;
  SyncEvent mEseListenEvent;
  SyncEvent mEeSetModeEvent;
  SyncEvent mModeSetNtf;
  SyncEvent mHciAddStaticPipe;
#if ((NXP_EXTNS == TRUE))
  SyncEvent mPwrLinkCtrlEvent;
#endif

#if (NXP_EXTNS == TRUE)
  uint8_t getUiccGateAndPipeList(uint8_t uiccNo);
  /*******************************************************************************
  **
  ** Function:        updateNfceeDiscoverInfo
  **
  ** Description:     Update the gSeDiscoverycount based on new NFCEE handle
  **                  discovered
  **
  ** Returns:         Count of NFCEE discovered.
  **
  *******************************************************************************/
  uint8_t updateNfceeDiscoverInfo();
  tNFA_HANDLE getHciHandleInfo();
  SyncEvent mNfceeInitCbEvent;
  tNFA_STATUS SecElem_EeModeSet(uint16_t handle, uint8_t mode);
  SyncEvent mEEdatapacketEvent;
  SyncEvent mTransceiveEvent;
  static const uint8_t EVT_END_OF_APDU_TRANSFER = 0x21;  // NXP Propritory
  bool mIsWiredModeOpen;
  bool mlistenDisabled;
  bool mIsExclusiveWiredMode;
  bool mIsAllowWiredInDesfireMifareCE;
  static const uint8_t EVT_ABORT = 0x11;  // ETSI12
  static const uint8_t EVT_ABORT_MAX_RSP_LEN = 40;
  bool mIsWiredModeBlocked; /* for wired mode resume feature support */
  IntervalTimer mRfFieldEventTimer;
  uint32_t mRfFieldEventTimeout;
  tNFA_STATUS mModeSetInfo;                      /*Mode set info status*/
  static const uint8_t NFCC_DECIDES = 0x00;      // NFCC decides
  static const uint8_t POWER_ALWAYS_ON = 0x01;   // NFCEE Power Supply always On
  static const uint8_t COMM_LINK_ACTIVE = 0x02;  // NFCC to NFCEE Communication
                                                 // link always active when the
                                                 // NFCEE  is powered on.
  static const uint8_t EVT_SUSPEND_APDU_TRANSFER = 0x31;
  tNFA_STATUS
      mPwrCmdstatus;  // completion status of the power link control command
  uint8_t mNfccPowerMode;
  tNFA_STATUS setNfccPwrConfig(uint8_t value);
  tNFA_HCI_GET_GATE_PIPE_LIST getGateAndPipeInfo();
  bool mIsIntfRstEnabled;
  void setCLState(bool mState);
  void setDwpTranseiveState(bool state, tNFCC_EVTS_NTF action);

  /*******************************************************************************
  **
  ** Function         getLastRfFiledToggleTime
  **
  ** Description      Provides the last RF filed toggile timer
  **
  ** Returns          timespec
  **
  *******************************************************************************/
  struct timespec getLastRfFiledToggleTime(void);
#endif

 private:
  static uint8_t mStaticPipeProp;
  static const unsigned int MAX_RESPONSE_SIZE = 0x8800;  // 1024; //34K
  enum RouteSelection { NoRoute, DefaultRoute, SecElemRoute };
  static const uint8_t STATIC_PIPE_0x71 =
      0x71;  // Broadcom's proprietary static pipe
  static const uint8_t EVT_SEND_DATA = 0x10;  // see specification ETSI TS 102
                                              // 622 v9.0.0 (Host Controller
                                              // Interface); section 9.3.3.3
#if (NXP_EXTNS == TRUE)
  static const uint8_t STATIC_PIPE_UICC =
      0x20;  // UICC's proprietary static pipe
  static const tNFA_HANDLE EE_HANDLE_HCI = 0x401;
  static const tNFA_HANDLE EE_HANDLE_NDEFEE = 0x410;
#else
  static const tNFA_HANDLE EE_HANDLE_0xF3 =
      0x4F3;  // handle to secure element in slot 0
  static const tNFA_HANDLE EE_HANDLE_0xF4 =
      0x4F4;  // handle to secure element in slot 1
#endif

  static SecureElement sSecElem;
  static const char* APP_NAME;

  uint8_t mDestinationGate;   // destination gate of the UICC
  nfc_jni_native_data* mNativeData;
  bool mIsInit;           // whether EE is initialized
  uint8_t mActualNumEe;   // actual number of EE's reported by the stack
  bool mbNewEE;
  uint8_t mNewPipeId;
  uint8_t mNewSourceGate;
  uint16_t mActiveSeOverride;  // active "enable" seid, 0 means activate all SEs
  tNFA_STATUS mCommandStatus;  // completion status of the last command
  bool mIsPiping;              // is a pipe connected to the controller?
  RouteSelection mCurrentRouteSelection;
  int mActualResponseSize;  // number of bytes in the response received from
                            // secure element
  int mAtrInfolen;
  uint8_t mAtrStatus;
  bool mUseOberthurWarmReset;         // whether to use warm-reset command
  uint8_t mOberthurWarmResetCommand;  // warm-reset command byte
  tNFA_EE_INFO mEeInfo[MAX_NUM_EE];   // actual size stored in mActualNumEe
  tNFA_EE_DISCOVER_REQ mUiccInfo;
  tNFA_HCI_GET_GATE_PIPE_LIST mHciCfg;
  SyncEvent mEeRegisterEvent;
  SyncEvent mHciRegisterEvent;
  SyncEvent mResetEvent;
  SyncEvent mResetOngoingEvent;
#if (NXP_EXTNS != TRUE)
  SyncEvent mCreatePipeEvent;
  SyncEvent mPipeOpenedEvent;
#endif
  SyncEvent mAllocateGateEvent;
  SyncEvent mDeallocateGateEvent;
  //    SyncEvent       mRoutingEvent;
  SyncEvent mUiccInfoEvent;
  //    SyncEvent       mAidAddRemoveEvent;
  SyncEvent mGetRegisterEvent;
  SyncEvent mVerInfoEvent;
  SyncEvent mRegistryEvent;
  uint8_t mVerInfo[3];
  uint8_t mAtrInfo[40];
  bool mGetAtrRspwait;
  RouteDataSet mRouteDataSet;          // routing data
  std::vector<std::string> mUsedAids;  // AID's that are used in current routes
  uint8_t mAidForEmptySelect[NCI_MAX_AID_LEN + 1];
  Mutex mMutex;                        // protects fields below
  bool mRfFieldIsOn;                   // last known RF field state
  struct timespec mLastRfFieldToggle;  // last time RF field went off
  IntervalTimer mTransceiveTimer;
  bool mTransceiveWaitOk;

#if (NXP_EXTNS == TRUE)
#define WIRED_MODE_TRANSCEIVE_TIMEOUT 120000
#endif
  /*******************************************************************************
  **
  ** Function:        SecureElement
  **
  ** Description:     Initialize member variables.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  SecureElement();

  /*******************************************************************************
  **
  ** Function:        ~SecureElement
  **
  ** Description:     Release all resources.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  ~SecureElement();

  /*******************************************************************************
  **
  ** Function:        handleClearAllPipe
  **
  ** Description:     To handle clear all pipe event received from HCI based on
  *the
  **                  deleted host
  **                  eventData: Event data.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void handleClearAllPipe(tNFA_HCI_EVT_DATA* eventData);

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
  static void nfaEeCallback(tNFA_EE_EVT event, tNFA_EE_CBACK_DATA* eventData);

  /*******************************************************************************
  **
  ** Function:        nfaHciCallback
  **
  ** Description:     Receive Host Controller Interface-related events from
  *stack.
  **                  event: Event code.
  **                  eventData: Event data.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static void nfaHciCallback(tNFA_HCI_EVT event, tNFA_HCI_EVT_DATA* eventData);

  /*******************************************************************************
  **
  ** Function:        findEeByHandle
  **
  ** Description:     Find information about an execution environment.
  **                  eeHandle: Handle to execution environment.
  **
  ** Returns:         Information about an execution environment.
  **
  *******************************************************************************/
  tNFA_EE_INFO* findEeByHandle(tNFA_HANDLE eeHandle);

  /*******************************************************************************
  **
  ** Function:        findUiccByHandle
  **
  ** Description:     Find information about an execution environment.
  **                  eeHandle: Handle of the execution environment.
  **
  ** Returns:         Information about the execution environment.
  **
  *******************************************************************************/
  tNFA_EE_DISCOVER_INFO* findUiccByHandle(tNFA_HANDLE eeHandle);

  /*******************************************************************************
  **
  ** Function:        getDefaultEeHandle
  **
  ** Description:     Get the handle to the execution environment.
  **
  ** Returns:         Handle to the execution environment.
  **
  *******************************************************************************/
  tNFA_HANDLE getDefaultEeHandle();

#if (NXP_EXTNS == TRUE)
  /*******************************************************************************
  **
  ** Function:        getActiveEeHandle
  **
  ** Description:     Get the handle of the active execution environment.
  **
  ** Returns:         Handle to the execution environment.
  **
  *******************************************************************************/
  tNFA_HANDLE getActiveEeHandle(tNFA_HANDLE eeHandle);

#endif
  /*******************************************************************************
  **
  ** Function:        adjustRoutes
  **
  ** Description:     Adjust routes in the controller's listen-mode routing
  *table.
  **                  selection: which set of routes to configure the
  *controller.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void adjustRoutes(RouteSelection selection);

  /*******************************************************************************
  **
  ** Function:        adjustProtocolRoutes
  **
  ** Description:     Adjust default routing based on protocol in NFC listen
  *mode.
  **                  isRouteToEe: Whether routing to EE (true) or host (false).
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void adjustProtocolRoutes(RouteDataSet::Database* db,
                            RouteSelection routeSelection);

  /*******************************************************************************
  **
  ** Function:        adjustTechnologyRoutes
  **
  ** Description:     Adjust default routing based on technology in NFC listen
  *mode.
  **                  isRouteToEe: Whether routing to EE (true) or host (false).
  **
  ** Returns:         None
  **
  *******************************************************************************/
  void adjustTechnologyRoutes(RouteDataSet::Database* db,
                              RouteSelection routeSelection);

  /*******************************************************************************
  **
  ** Function:        getEeInfo
  **
  ** Description:     Get latest information about execution environments from
  *stack.
  **
  ** Returns:         True if at least 1 EE is available.
  **
  *******************************************************************************/
  bool getEeInfo();

  /*******************************************************************************
  **
  ** Function:        eeStatusToString
  **
  ** Description:     Convert status code to status text.
  **                  status: Status code
  **
  ** Returns:         None
  **
  *******************************************************************************/
  static const char* eeStatusToString(uint8_t status);

  /*******************************************************************************
  **
  ** Function:        encodeAid
  **
  ** Description:     Encode AID in type-length-value using Basic Encoding Rule.
  **                  tlv: Buffer to store TLV.
  **                  tlvMaxLen: TLV buffer's maximum length.
  **                  tlvActualLen: TLV buffer's actual length.
  **                  aid: Buffer of Application ID.
  **                  aidLen: Aid buffer's actual length.
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  bool encodeAid(uint8_t* tlv, uint16_t tlvMaxLen, uint16_t& tlvActualLen,
                 const uint8_t* aid, uint8_t aidLen);

  static int decodeBerTlvLength(uint8_t* data, int index, int data_length);
};
