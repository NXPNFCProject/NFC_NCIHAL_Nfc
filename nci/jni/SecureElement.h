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
*  Copyright 2018 NXP
*
******************************************************************************/

#pragma once
#include "nfa_hci_api.h"
#include "nfa_hci_defs.h"
#include "NfcJniUtil.h"
#include "nfc_api.h"
#include "config.h"
#include "phNxpConfig.h"
#include "SyncEvent.h"
#include "nfa_ee_api.h"
#include "nfa_hci_api.h"
#include "nfa_hci_defs.h"
#include "nfa_ce_api.h"
#include "phNxpExtns.h"
#include "phNfcTypes.h"

#define MAX_NFCEE 5
#define WIRED_MODE_TRANSCEIVE_TIMEOUT 120000
#define CONNECTIVITY_PIPE_ID_UICC1 0x0A
#define CONNECTIVITY_PIPE_ID_UICC2 0x23
#define CONNECTIVITY_PIPE_ID_UICC3 0x31
#define NFA_EE_TAG_HCI_HOST_ID 0xA0 /* HCI host ID */
#define SMX_PIPE_ID 0x19

class SecureElement {
public:
  tNFA_HANDLE  mActiveEeHandle;

  static const uint8_t UICC_ID = 0x02;
  static const uint8_t UICC2_ID = 0x04;
  static const uint8_t UICC3_ID = 0x08;
  static const uint8_t ESE_ID = 0x01;
  static const uint8_t DH_ID = 0x00;
  static const uint8_t NFCC_DECIDES     = 0x00;     //NFCC decides
  static const uint8_t POWER_ALWAYS_ON  = 0x01;     //NFCEE Power Supply always On
  static const uint8_t COMM_LINK_ACTIVE = 0x02;     //NFCC to NFCEE Communication link always active when the NFCEE  is powered on.
  static const uint8_t EVT_END_OF_APDU_TRANSFER = 0x61;
  tNFA_STATUS  mPwrCmdstatus;     //completion status of the power link control command
  uint8_t      mNfccPowerMode;
  uint8_t mNewPipeId;

  bool mIsWiredModeOpen;


  SyncEvent   mPwrLinkCtrlEvent;
  tNFA_HANDLE EE_HANDLE_0xF4;   //handle to secure element in slot 1
  static const tNFA_HANDLE EE_HANDLE_0xF3 = 0x4C0;//0x401; //handle to secure element in slot 0
  static const tNFA_HANDLE EE_HANDLE_0xF8 = 0x481; //handle to secure element in slot 2
  static const tNFA_HANDLE EE_HANDLE_0xF9 = 0x482; //handle to secure element in slot 3
  static const tNFA_HANDLE EE_HANDLE_0xF0 = 0x400;//NFCEE handle for host
  static const uint8_t EE_APP_HANLDE_ESE   = 0xF3;
  static const uint8_t EE_APP_HANLDE_UICC  = 0xF4;
  static const uint8_t EE_APP_HANLDE_UICC2 = 0xF8;
  static const uint8_t EE_APP_HANLDE_UICC3 = 0xF9;
  uint8_t muicc2_selected;    /* UICC2 or UICC3 selected from config file*/
/*******************************************************************************
**
** Function:        getGenericEseId
**
** Description:     Whether controller is routing listen-mode events to
**                  secure elements or a pipe is connected.
**
** Returns:         Return the generic SE id ex:- 00,01,02,04
**
*******************************************************************************/
jint getGenericEseId(tNFA_HANDLE handle);

jint getSETechnology(tNFA_HANDLE eeHandle);

void getEeHandleList(tNFA_HANDLE *list, uint8_t* count);

 private:
  static SecureElement sSecElem;
  static const char* APP_NAME;
  static const tNFA_HANDLE EE_HANDLE_ESE = 0x4C0;
  static const tNFA_HANDLE EE_HANDLE_UICC = 0x480;
  static const uint8_t NFCEE_ID_ESE = 0x01;
  static const uint8_t NFCEE_ID_UICC = 0x02;
  static const int MAX_NUM_EE = NFA_EE_MAX_EE_SUPPORTED;    /*max number of EE's*/
  static const unsigned int MAX_RESPONSE_SIZE = 0x8800;//1024; //34K
  static const uint8_t STATIC_PIPE_0x71 = 0x71; //Broadcom's proprietary static pipe
  static const uint8_t EVT_ABORT_MAX_RSP_LEN = 40;
  static const uint8_t EVT_ABORT = 0x11;  //ETSI12
  static const uint8_t STATIC_PIPE_UICC = 0x20; //UICC's proprietary static pipe
  const uint16_t ACTIVE_SE_USE_ANY = 0xFFFF;

  nfc_jni_native_data* mNativeData;
  nfc_jni_native_data* mthreadnative;

  bool    mbNewEE;
  bool    mIsInit;                // whether EE is initialized
  bool    mTransceiveWaitOk;
  bool    mGetAtrRspwait;
  bool    mAbortEventWaitOk;
  bool    mIsPiping;              //is a pipe connected to the controller?

  tNFA_HCI_GET_GATE_PIPE_LIST mHciCfg;
  tNFA_STATUS mCommandStatus;     //completion status of the last command
  tNFA_HANDLE     mNfaHciHandle;          //NFA handle to NFA's HCI component
  tNFA_EE_INFO mEeInfo [MAX_NUM_EE];  //actual size stored in mActualNumEe

  SyncEvent   mCreatePipeEvent;
  SyncEvent   mAllocateGateEvent;
  SyncEvent   mDeallocateGateEvent;
  SyncEvent   mPipeListEvent;
  SyncEvent   mHciRegisterEvent;
  SyncEvent   mPipeOpenedEvent;
  SyncEvent   mTransceiveEvent;
  SyncEvent   mEeSetModeEvent;
  SyncEvent   mAbortEvent;
  SyncEvent   mVerInfoEvent;
  SyncEvent   mGetRegisterEvent;
  SyncEvent   mRegistryEvent;
  SyncEvent   mWiredModeHoldEvent;
  SyncEvent   mModeSetNtf;

  int     mActualResponseSize;         //number of bytes in the response received from secure element
  int     mAtrInfolen;

  uint8_t mNewSourceGate;
  uint8_t mActualNumEe;           // actual number of EE's reported by the stack
  uint8_t mAidForEmptySelect[NCI_MAX_AID_LEN+1];
  uint8_t mDestinationGate;       //destination gate of the UICC
  uint8_t mAtrStatus;
  uint8_t mVerInfo [3];
  uint8_t mAtrInfo[40];
  uint8_t mResponseData [MAX_RESPONSE_SIZE];
  uint8_t mNumEePresent;          // actual number of usable EE's
  uint8_t     mCreatedPipe;
  static uint8_t mStaticPipeProp;


struct mNfceeData
{
    tNFA_HANDLE mNfceeHandle[MAX_NFCEE];
    tNFA_EE_STATUS mNfceeStatus[MAX_NFCEE];
    uint8_t mNfceePresent;
};
mNfceeData  mNfceeData_t;
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
** Function:        decodeBerTlvLength
**
** Description:     Decodes BER TLV length from the data provided
**                  data : array of data to be processed
**                  index : offset from which to consider processing
**                  data_length : length of data to be processed
**
** Returns:         decoded_length
**
*******************************************************************************/
static int decodeBerTlvLength(uint8_t* data, int index, int data_length);

/*******************************************************************************
**
** Function:        notifyTransactionListenersOfAid
**
** Description:     Notify the NFC service about a transaction event from
**                  secure element.
**                  aid: Buffer contains application ID.
**                  aidLen: Length of application ID.
**
** Returns:         None
**
*******************************************************************************/
void notifyTransactionListenersOfAid(const uint8_t* aidBuffer,
                                       uint8_t aidBufferLen,
                                       const uint8_t* dataBuffer,
                                       uint32_t dataBufferLen, uint32_t evtSrc);

/*******************************************************************************
**
** Function:        nfaHciCallback
**
** Description:     Receive HCI-related events from stack.
**                  event: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
static void nfaHciCallback(tNFA_HCI_EVT event, tNFA_HCI_EVT_DATA* eventData);

public:
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
** Function:        connectEE
**
** Description:     Connect to the execution environment.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool connectEE ();
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
bool disconnectEE (jint seID);
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
**                  timeoutMillisec: timeout in millisecond.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool transceive (uint8_t* xmitBuffer, int32_t xmitBufferSize, uint8_t* recvBuffer,
       int32_t recvBufferMaxSize, int32_t& recvBufferActualSize, int32_t timeoutMillisec);
/*******************************************************************************
**
** Function:        activate
**
** Description:     Turn on the secure element.
**                  seID: ID of secure element; 0xF3 or 0xF4.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool activate (jint seID);
/*******************************************************************************
**
** Function:        deactivate
**
** Description:     Turn off the secure element.
**                  seID: ID of secure element; 0xF3 or 0xF4.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool deactivate (jint seID);
/*******************************************************************************
**
** Function:       SecElem_EeModeSet
**
** Description:    Perform SE mode set ON/OFF based on mode type
**
** Returns:        NFA_STATUS_OK/NFA_STATUS_FAILED.
**
*******************************************************************************/
tNFA_STATUS SecElem_EeModeSet(uint16_t handle, uint8_t mode);
/*******************************************************************************
**
** Function:        getAtr
**
** Description:     GetAtr response from the connected eSE
**
** Returns:         Returns True if success
**
*******************************************************************************/
bool getAtr(jint seID, uint8_t* recvBuffer, int32_t *recvBufferSize);
/*******************************************************************************
 **
 ** Function:       SecElem_EeModeSet
 **
 ** Description:    Perform SE mode set ON/OFF based on mode type
 **
 ** Returns:        NFA_STATUS_OK/NFA_STATUS_FAILED.
 **
 *******************************************************************************/
bool SecEle_Modeset(uint8_t type);
/*******************************************************************************
**
** Function:        initializeEeHandle
**
** Description:     Set NFCEE handle.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool initializeEeHandle ();
/*******************************************************************************
**
** Function:        getEseHandleFromGenericId
**
** Description:     Whether controller is routing listen-mode events to
**                  secure elements or a pipe is connected.
**
** Returns:         Returns Secure element Handle ex:- 402, 4C0, 481
**
*******************************************************************************/
tNFA_HANDLE getEseHandleFromGenericId(jint eseId);
/*******************************************************************************
**
** Function:        getEeInfo
**
** Description:     Get latest information about execution environments from stack.
**
** Returns:         True if at least 1 EE is available.
**
*******************************************************************************/
bool getEeInfo ();
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
tNFA_EE_INFO *findEeByHandle (tNFA_HANDLE eeHandle);
/*******************************************************************************
**
** Function:        getActiveEeHandle
**
** Description:     Get the handle of the active execution environment.
**
** Returns:         Handle to the execution environment.
**
*******************************************************************************/
tNFA_HANDLE getActiveEeHandle (tNFA_HANDLE eeHandle);
/*******************************************************************************
**
** Function         setNfccPwrConfig
**
** Description      sends the link cntrl command to eSE with the value passed
**
** Returns          status
**
*******************************************************************************/
tNFA_STATUS setNfccPwrConfig(uint8_t value);
/*******************************************************************************
**
** Function         sendEvent
**
** Description      sends the HCI event
**
** Returns          status
**
*******************************************************************************/
bool sendEvent(uint8_t event);
/*******************************************************************************
 **
 ** Function:       notifyModeSet
 **
 ** Description:    Perform SE mode set ON/OFF based on mode type
 **
 ** Returns:        NFA_STATUS_OK/NFA_STATUS_FAILED.
 **
 *******************************************************************************/
void notifyModeSet (tNFA_HANDLE eeHandle, bool success, tNFA_EE_STATUS eeStatus);
/*******************************************************************************
**
** Function:        getGateAndPipeList
**
** Description:     Get the gate and pipe list.
**
** Returns:         None
**
*******************************************************************************/
uint8_t getGateAndPipeList();
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
};
