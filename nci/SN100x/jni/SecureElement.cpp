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
*  Copyright 2018-2022 NXP
*
******************************************************************************/

#include "SecureElement.h"
#include <nativehelper/ScopedLocalRef.h>
#include "JavaClassConstants.h"
#include "NfcJniUtil.h"
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <semaphore.h>
#include <errno.h>
#include "config.h"
#include "nfc_config.h"
#include "RoutingManager.h"
#include "HciEventManager.h"
#include "MposManager.h"
#include "SyncEvent.h"
#if (NXP_SRD == TRUE)
#include "SecureDigitization.h"
#endif
using android::base::StringPrintf;

SecureElement SecureElement::sSecElem;
const char* SecureElement::APP_NAME = "nfc_jni";
extern bool nfc_debug_enabled;
extern bool isDynamicUiccEnabled;
#define ONE_SECOND_MS 1000

namespace android
{
extern void startRfDiscovery (bool isStart);
extern tNFA_STATUS NxpNfc_Write_Cmd_Common(uint8_t retlen, uint8_t* buffer);
extern bool nfcManager_isNfcDisabling();
}
uint8_t  SecureElement::mStaticPipeProp;
/*******************************************************************************
**
** Function:        SecureElement
**
** Description:     Initialize member variables.
**
** Returns:         None
**
*******************************************************************************/
SecureElement::SecureElement() :
    mActiveEeHandle(NFA_HANDLE_INVALID),
    mNewPipeId (0),
    mIsWiredModeOpen (false),
    mIsSeIntfActivated(false),
    SmbTransceiveTimeOutVal(0),
    mErrorRecovery(false),
    EE_HANDLE_0xF4(0),
    muicc2_selected(0),
    mNativeData(NULL),
    mthreadnative(NULL),
    mbNewEE (true),
    mIsInit (false),
    mTransceiveWaitOk (false),
    mGetAtrRspwait (false),
    mAbortEventWaitOk (false),
    mNewSourceGate (0),
    mAtrStatus (0),
    mAtrRespLen (0),
    mNumEePresent (0),
    mCreatedPipe (0),
    mRfFieldIsOn(false),
    mActivatedInListenMode (false)
{
    mPwrCmdstatus = NFA_STATUS_FAILED;
    mModeSetNtfstatus = NFA_STATUS_FAILED;
    mNfccPowerMode = 0;
    mTransceiveStatus = NFA_STATUS_FAILED;
    mCommandStatus = NFA_STATUS_FAILED;
    mNfaHciHandle = NFA_HANDLE_INVALID;
    mActualResponseSize = 0;
    mAtrInfolen = 0;
    mActualNumEe = 0;
    memset (&mEeInfo, 0, nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED *sizeof(tNFA_EE_INFO));
    memset (mAidForEmptySelect, 0, sizeof(mAidForEmptySelect));
    memset (mVerInfo, 0, sizeof(mVerInfo));
    memset (mAtrInfo, 0, sizeof(mAtrInfo));
    memset (mResponseData, 0, sizeof(mResponseData));
    memset (mAtrRespData, 0, sizeof(mAtrRespData));
    memset (&mHciCfg, 0, sizeof(mHciCfg));
    memset (&mLastRfFieldToggle, 0, sizeof(mLastRfFieldToggle));
    memset (&mNfceeData_t, 0, sizeof(mNfceeData));
}
/*******************************************************************************
**
** Function:        getInstance
**
** Description:     Get the SecureElement singleton object.
**
** Returns:         SecureElement object.
**
*******************************************************************************/
SecureElement& SecureElement::getInstance() { return sSecElem; }

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
bool SecureElement::initialize(nfc_jni_native_data* native) {
    static const char fn [] = "SecureElement::initialize";
    tNFA_STATUS nfaStat;

    LOG(INFO) << StringPrintf("%s: enter", fn);


    mActiveEeHandle = NFA_HANDLE_INVALID;
    mNfaHciHandle = NFA_HANDLE_INVALID;

    mNativeData     = native;
    mthreadnative    = native;
    mActualNumEe    = nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED;
    mbNewEE         = true;
    mNewPipeId      = 0;
    mIsSeIntfActivated = false;
    mNewSourceGate  = 0;
    memset (mEeInfo, 0, sizeof(mEeInfo));
    memset (&mHciCfg, 0, sizeof(mHciCfg));
    memset(mAidForEmptySelect, 0, sizeof(mAidForEmptySelect));
    mActivatedInListenMode = false;
    muicc2_selected = NfcConfig::getUnsigned(NAME_NXP_DEFAULT_UICC2_SELECT, UICC2_ID);

    SmbTransceiveTimeOutVal = NfcConfig::getUnsigned(NAME_NXP_SMB_TRANSCEIVE_TIMEOUT, WIRED_MODE_TRANSCEIVE_TIMEOUT);

    if(SmbTransceiveTimeOutVal < WIRED_MODE_TRANSCEIVE_TIMEOUT)
    {
        SmbTransceiveTimeOutVal = WIRED_MODE_TRANSCEIVE_TIMEOUT;
    }

    mErrorRecovery = NfcConfig::getUnsigned(NAME_NXP_SMB_ERROR_RETRY, 0x00);

    LOG(INFO) << StringPrintf("%s: SMB transceive timeout %d SMB Error recovery %d", fn, SmbTransceiveTimeOutVal, mErrorRecovery);

    initializeEeHandle();

    // Get Fresh EE info.
    if (! getEeInfo())
        return (false);

    // If the controller has an HCI Network, register for that
    //for (size_t xx = 0; xx < mActualNumEe; xx++)
    for (size_t xx = 0; xx < MAX_NUM_EE; xx++)
    {

        if((mEeInfo[xx].ee_handle != EE_HANDLE_0xF4)
           || ((((mEeInfo[xx].ee_interface[0] == NCI_NFCEE_INTERFACE_HCI_ACCESS)
                 && (mEeInfo[xx].ee_status == NFC_NFCEE_STATUS_ACTIVE)) || (NFA_GetNCIVersion() == NCI_VERSION_2_0))))
            {
                LOG(INFO) << StringPrintf("%s: Found HCI network, try hci register", fn);

                SyncEventGuard guard (mHciRegisterEvent);

                nfaStat = NFA_HciRegister (const_cast<char*>(APP_NAME), nfaHciCallback, true);
                if (nfaStat != NFA_STATUS_OK)
                {
                    LOG(ERROR) << StringPrintf("%s: fail hci register; error=0x%X", fn, nfaStat);
                    return (false);
                }
                mHciRegisterEvent.wait();
                break;
            }
    }

    mIsInit = true;
    LOG(INFO) << StringPrintf("%s: exit", fn);
    return (true);
}
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
bool SecureElement::isActivatedInListenMode() { return mActivatedInListenMode; }
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
jint SecureElement::getGenericEseId(tNFA_HANDLE handle) {
    jint ret = 0xFF;
    static const char fn [] = "SecureElement::getGenericEseId";
    LOG(INFO) << StringPrintf("%s: enter; ESE-Handle = 0x%X", fn, handle);

    //Map the actual handle to generic id
    if(handle == (EE_HANDLE_0xF3 & ~NFA_HANDLE_GROUP_EE) ) //ESE - 0xC0
    {
        ret = ESE_ID;
    }
    else if(handle ==  (SecureElement::getInstance().EE_HANDLE_0xF4 & ~NFA_HANDLE_GROUP_EE) ) //UICC - 0x02
    {
        ret = UICC_ID;
    }
    if(handle ==  (EE_HANDLE_0xF8 & ~NFA_HANDLE_GROUP_EE)) //UICC2 - 0x04
    {
        ret = UICC2_ID;
    }
    else if (handle == (EE_HANDLE_0xF9 & ~NFA_HANDLE_GROUP_EE)) //UICC2 - 0x04
    {
        ret = UICC3_ID;
    }
    LOG(INFO) << StringPrintf("%s: exit; ESE-Generic-ID = 0x%02X", fn, ret);
    return ret;
}
/*******************************************************************************
**
** Function         TimeDiff
**
** Description      Computes time difference in milliseconds.
**
** Returns          Time difference in milliseconds
**
*******************************************************************************/
static uint32_t TimeDiff(timespec start, timespec end)
{
    end.tv_sec -= start.tv_sec;
    end.tv_nsec -= start.tv_nsec;

    if (end.tv_nsec < 0) {
        end.tv_nsec += 10e8;
        end.tv_sec -=1;
    }

    return (end.tv_sec * 1000) + (end.tv_nsec / 10e5);
}
/*******************************************************************************
**
** Function:        isRfFieldOn
**
** Description:     Can be used to determine if the SE is in an RF field
**
** Returns:         True if the SE is activated in an RF field
**
*******************************************************************************/
bool SecureElement::isRfFieldOn() {
    AutoMutex mutex(mMutex);
    if (mRfFieldIsOn) {
        return true;
    }
    struct timespec now;
    int ret = clock_gettime(CLOCK_MONOTONIC, &now);
    if (ret == -1) {
        DLOG_IF(ERROR, nfc_debug_enabled)
                << StringPrintf("isRfFieldOn(): clock_gettime failed");
        return false;
    }
    if (TimeDiff(mLastRfFieldToggle, now) < 50) {
        // If it was less than 50ms ago that RF field
        // was turned off, still return ON.
        return true;
    } else {
        return false;
    }
}
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
void SecureElement::notifyListenModeState (bool isActivated) {
    static const char fn [] = "SecureElement::notifyListenMode";

    DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf("%s: enter; listen mode active=%u", fn, isActivated);

    JNIEnv* e = NULL;
    if (mNativeData == NULL)
    {
        DLOG_IF(ERROR, nfc_debug_enabled)
                << StringPrintf("%s: mNativeData is null", fn);
        return;
    }

    ScopedAttach attach(mNativeData->vm, &e);
    if (e == NULL)
    {
        DLOG_IF(ERROR, nfc_debug_enabled)
                << StringPrintf("%s: jni env is null", fn);
        return;
    }

    mActivatedInListenMode = isActivated;

    if (mNativeData != NULL) {
        if (isActivated) {
            e->CallVoidMethod (mNativeData->manager, android::gCachedNfcManagerNotifySeListenActivated);
        }
        else {
            e->CallVoidMethod (mNativeData->manager, android::gCachedNfcManagerNotifySeListenDeactivated);
        }
    }

    if (e->ExceptionCheck())
    {
        e->ExceptionClear();
        DLOG_IF(ERROR, nfc_debug_enabled)
                << StringPrintf("%s: fail notify", fn);
    }

    DLOG_IF(INFO, nfc_debug_enabled)
                << StringPrintf("%s: exit", fn);
}

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
int SecureElement::decodeBerTlvLength(uint8_t* data, int index,
                                          int data_length) {
    int decoded_length = -1;
    int length = 0;
    int temp = data[index] & 0xff;

    LOG(INFO) << StringPrintf("decodeBerTlvLength index= %d data[index+0]=0x%x data[index+1]=0x%x len=%d",index, data[index], data[index+1], data_length);

    if (temp < 0x80) {
        decoded_length = temp;
    } else if (temp == 0x81) {
        if( index < data_length ) {
            length = data[index+1] & 0xff;
            if (length < 0x80) {
                LOG(ERROR) << StringPrintf("Invalid TLV length encoding!");
                goto TheEnd;
            }
            if (data_length < length + index) {
                LOG(ERROR) << StringPrintf("Not enough data provided!");
                goto TheEnd;
            }
        } else {
            LOG(ERROR) << StringPrintf("Index %d out of range! [0..[%d",index, data_length);
            goto TheEnd;
        }
        decoded_length = length;
    } else if (temp == 0x82) {
        if( (index + 1)< data_length ) {
            length = ((data[index] & 0xff) << 8)
                    | (data[index + 1] & 0xff);
        } else {
            LOG(ERROR) << StringPrintf("Index out of range! [0..[%d" , data_length);
            goto TheEnd;
        }
        index += 2;
        if (length < 0x100) {
            LOG(ERROR) << StringPrintf("Invalid TLV length encoding!");
            goto TheEnd;
        }
        if (data_length < length + index) {
            LOG(ERROR) << StringPrintf("Not enough data provided!");
            goto TheEnd;
        }
        decoded_length = length;
    } else if (temp == 0x83) {
        if( (index + 2)< data_length ) {
            length = ((data[index] & 0xff) << 16)
                    | ((data[index + 1] & 0xff) << 8)
                    | (data[index + 2] & 0xff);
        } else {
            LOG(ERROR) << StringPrintf("Index out of range! [0..[%d", data_length);
            goto TheEnd;
        }
        index += 3;
        if (length < 0x10000) {
            LOG(ERROR) << StringPrintf("Invalid TLV length encoding!");
            goto TheEnd;
        }
        if (data_length < length + index) {
            LOG(ERROR) << StringPrintf("Not enough data provided!");
            goto TheEnd;
        }
        decoded_length = length;
    } else {
        LOG(ERROR) << StringPrintf("Unsupported TLV length encoding!");
    }
TheEnd:
    LOG(INFO) << StringPrintf("decoded_length = %d", decoded_length);

    return decoded_length;
}
/*******************************************************************************
**
** Function:        notifyRfFieldEvent
**
** Description:     Notify the NFC service about RF field events from the stack.
**                  isActive: Whether any secure element is activated.
**
** Returns:         None
**
*******************************************************************************/
void SecureElement::notifyRfFieldEvent (bool isActive)
{
    static const char fn [] = "SecureElement::notifyRfFieldEvent";
    DLOG_IF(ERROR, nfc_debug_enabled)
                << StringPrintf("%s: enter; is active=%u", fn, isActive);


    mMutex.lock();
    JNIEnv* e = NULL;
    if (mNativeData == NULL) {
        DLOG_IF(ERROR, nfc_debug_enabled)
                << StringPrintf("%s: mNativeData is null", fn);
        mMutex.unlock();
        return;
    }
    ScopedAttach attach(mNativeData->vm, &e);
    if (e == NULL) {
      LOG(ERROR) << StringPrintf("jni env is null");
      mMutex.unlock();
      return;
    }
    int ret = clock_gettime (CLOCK_MONOTONIC, &mLastRfFieldToggle);
    if (ret == -1) {
        DLOG_IF(ERROR, nfc_debug_enabled)
                << StringPrintf("%s: clock_gettime failed", fn);
        // There is no good choice here...
    }
    if (isActive) {
        mRfFieldIsOn = true;
        e->CallVoidMethod(mNativeData->manager,
                                android::gCachedNfcManagerNotifyRfFieldActivated);
    } else {
        mRfFieldIsOn = false;
        e->CallVoidMethod(mNativeData->manager,
                          android::gCachedNfcManagerNotifyRfFieldDeactivated);
    }
    mMutex.unlock();
    DLOG_IF(ERROR, nfc_debug_enabled)
                << StringPrintf("%s: exit", fn);
}
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
void SecureElement::nfaHciCallback(tNFA_HCI_EVT event,
                                   tNFA_HCI_EVT_DATA* eventData) {
    static const char fn [] = "SecureElement::nfaHciCallback";
    LOG(INFO) << StringPrintf("%s: event=0x%X", fn, event);

    switch (event)
    {
    case NFA_HCI_REGISTER_EVT:
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_REGISTER_EVT; status=0x%X; handle=0x%X", fn,
                    eventData->hci_register.status, eventData->hci_register.hci_handle);
            SyncEventGuard guard (sSecElem.mHciRegisterEvent);
            sSecElem.mNfaHciHandle = eventData->hci_register.hci_handle;
            sSecElem.mHciRegisterEvent.notifyOne();
        }
        break;

    case NFA_HCI_ALLOCATE_GATE_EVT:
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_ALLOCATE_GATE_EVT; status=0x%X; gate=0x%X", fn, eventData->status, eventData->allocated.gate);
            SyncEventGuard guard (sSecElem.mAllocateGateEvent);
            sSecElem.mCommandStatus = eventData->status;
            sSecElem.mNewSourceGate = (eventData->allocated.status == NFA_STATUS_OK) ? eventData->allocated.gate : 0;
            sSecElem.mAllocateGateEvent.notifyOne();
        }
        break;

    case NFA_HCI_DEALLOCATE_GATE_EVT:
        {
            tNFA_HCI_DEALLOCATE_GATE& deallocated = eventData->deallocated;
            LOG(INFO) << StringPrintf("%s: NFA_HCI_DEALLOCATE_GATE_EVT; status=0x%X; gate=0x%X", fn, deallocated.status, deallocated.gate);
            SyncEventGuard guard (sSecElem.mDeallocateGateEvent);
            sSecElem.mDeallocateGateEvent.notifyOne();
        }
        break;

    case NFA_HCI_GET_GATE_PIPE_LIST_EVT:
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_GET_GATE_PIPE_LIST_EVT; status=0x%X; num_pipes: %u  num_gates: %u", fn,
                    eventData->gates_pipes.status, eventData->gates_pipes.num_pipes, eventData->gates_pipes.num_gates);
            SyncEventGuard guard (sSecElem.mPipeListEvent);
            sSecElem.mCommandStatus = eventData->gates_pipes.status;
            sSecElem.mHciCfg = eventData->gates_pipes;
            sSecElem.mPipeListEvent.notifyOne();
        }
        break;

    case NFA_HCI_CREATE_PIPE_EVT:
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_CREATE_PIPE_EVT; status=0x%X; pipe=0x%X; src gate=0x%X; dest host=0x%X; dest gate=0x%X", fn,
                    eventData->created.status, eventData->created.pipe, eventData->created.source_gate, eventData->created.dest_host, eventData->created.dest_gate);
            SyncEventGuard guard (sSecElem.mCreatePipeEvent);
            sSecElem.mCommandStatus = eventData->created.status;
            if(eventData->created.dest_gate == 0xF0)
            {
                LOG(ERROR) << StringPrintf("Pipe=0x%x is created and updated for se transcieve", eventData->created.pipe);
                sSecElem.mNewPipeId = eventData->created.pipe;
            }
            sSecElem.mCreatedPipe = eventData->created.pipe;
            LOG(INFO) << StringPrintf("%s: NFA_HCI_CREATE_PIPE_EVT; pipe=0x%X", fn, eventData->created.pipe);
            sSecElem.mCreatePipeEvent.notifyOne();
        }
        break;
    case NFA_HCI_OPEN_PIPE_EVT:
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_OPEN_PIPE_EVT; status=0x%X; pipe=0x%X", fn, eventData->opened.status, eventData->opened.pipe);
            SyncEventGuard guard (sSecElem.mPipeOpenedEvent);
            sSecElem.mCommandStatus = eventData->opened.status;
            sSecElem.mPipeOpenedEvent.notifyOne();
        }
        break;

    case NFA_HCI_EVENT_SENT_EVT:
        {
          SyncEventGuard guard(sSecElem.mHciSendEvent);
          sSecElem.mHciSendEvent.notifyOne();
          LOG(INFO) << StringPrintf("%s: NFA_HCI_EVENT_SENT_EVT; status=0x%X", fn, eventData->evt_sent.status);
        }
        break;

    case NFA_HCI_RSP_RCVD_EVT: //response received from secure element
        {
            tNFA_HCI_RSP_RCVD& rsp_rcvd = eventData->rsp_rcvd;
            LOG(INFO) << StringPrintf("%s: NFA_HCI_RSP_RCVD_EVT; status: 0x%X; code: 0x%X; pipe: 0x%X; len: %u", fn,
                    rsp_rcvd.status, rsp_rcvd.rsp_code, rsp_rcvd.pipe, rsp_rcvd.rsp_len);
        }
        break;
    case NFA_HCI_RSP_APDU_RCVD_EVT:
        {
            if(eventData->apdu_rcvd.apdu_len > 0)
                {
                    sSecElem.mTransceiveWaitOk = true;
                    sSecElem.mActualResponseSize = (eventData->apdu_rcvd.apdu_len > MAX_RESPONSE_SIZE) ? MAX_RESPONSE_SIZE : eventData->apdu_rcvd.apdu_len;
                }
            sSecElem.mTransceiveStatus = eventData->apdu_rcvd.status;
            SyncEventGuard guard(sSecElem.mTransceiveEvent);
            sSecElem.mTransceiveEvent.notifyOne ();
            break;
        }
    case NFA_HCI_APDU_ABORTED_EVT:
        {
            if(eventData->apdu_aborted.atr_len > 0)
            {
                sSecElem.mAbortEventWaitOk = true;
                memcpy(sSecElem.mAtrInfo, eventData->apdu_aborted.p_atr, eventData->apdu_aborted.atr_len);
                sSecElem.mAtrInfolen = eventData->apdu_aborted.atr_len;
                sSecElem.mAtrStatus = eventData->apdu_aborted.status;
            }
            else
            {
                sSecElem.mAbortEventWaitOk = false;
                sSecElem.mAtrStatus = eventData->apdu_aborted.status;
            }
            SyncEventGuard guard(sSecElem.mAbortEvent);
            sSecElem.mAbortEvent.notifyOne();
            break;
        }
    case NFA_HCI_GET_REG_RSP_EVT :
        LOG(INFO) << StringPrintf("%s: NFA_HCI_GET_REG_RSP_EVT; status: 0x%X; pipe: 0x%X, len: %d", fn,
                eventData->registry.status, eventData->registry.pipe, eventData->registry.data_len);
        if(sSecElem.mGetAtrRspwait == true)
        {
            /*GetAtr response*/
            sSecElem.mGetAtrRspwait = false;
            SyncEventGuard guard (sSecElem.mGetRegisterEvent);
            memcpy(sSecElem.mAtrInfo, eventData->registry.reg_data, eventData->registry.data_len);
            sSecElem.mAtrInfolen = eventData->registry.data_len;
            sSecElem.mAtrStatus = eventData->registry.status;
            sSecElem.mGetRegisterEvent.notifyOne();
        }
        else if (eventData->registry.data_len >= 19 && ((eventData->registry.pipe == mStaticPipeProp) || (eventData->registry.pipe == STATIC_PIPE_0x71)))
        {
            SyncEventGuard guard (sSecElem.mVerInfoEvent);
            // Oberthur OS version is in bytes 16,17, and 18
            sSecElem.mVerInfo[0] = eventData->registry.reg_data[16];
            sSecElem.mVerInfo[1] = eventData->registry.reg_data[17];
            sSecElem.mVerInfo[2] = eventData->registry.reg_data[18];
            sSecElem.mVerInfoEvent.notifyOne ();
        }
        break;

    case NFA_HCI_EVENT_RCVD_EVT:
        LOG(INFO) << StringPrintf("%s: NFA_HCI_EVENT_RCVD_EVT; code: 0x%X; pipe: 0x%X; data len: %u", fn,
                eventData->rcvd_evt.evt_code, eventData->rcvd_evt.pipe, eventData->rcvd_evt.evt_len);
        if(eventData->rcvd_evt.pipe == 0x0A) //UICC
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_EVENT_RCVD_EVT; source UICC",fn);
            SecureElement::getInstance().getGenericEseId(SecureElement::getInstance().EE_HANDLE_0xF4 & ~NFA_HANDLE_GROUP_EE); //UICC
        }
        else if(eventData->rcvd_evt.pipe == 0x16) //ESE
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_EVENT_RCVD_EVT; source ESE",fn);
            SecureElement::getInstance().getGenericEseId(EE_HANDLE_0xF3 & ~NFA_HANDLE_GROUP_EE); //ESE
        }
        else if(eventData->rcvd_evt.pipe == CONNECTIVITY_PIPE_ID_UICC3) //UICC3
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_EVENT_RCVD_EVT; source UICC3",fn);
            SecureElement::getInstance().getGenericEseId(SecureElement::getInstance().EE_HANDLE_0xF9 & ~NFA_HANDLE_GROUP_EE); //UICC
        }
        else if (((eventData->rcvd_evt.evt_code == NFA_HCI_EVT_ATR))
                &&(eventData->rcvd_evt.pipe == mStaticPipeProp))
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_EVENT_RCVD_EVT: NFA_HCI_ABORT; status:0x%X, pipe:0x%X, len:%d", fn,\
                eventData->rcvd_evt.status, eventData->rcvd_evt.pipe, eventData->rcvd_evt.evt_len);
            if(eventData->rcvd_evt.evt_len > 0)
            {
                sSecElem.mAbortEventWaitOk = true;
                SyncEventGuard guard(sSecElem.mAbortEvent);
                memcpy(sSecElem.mAtrInfo, eventData->rcvd_evt.p_evt_buf, eventData->rcvd_evt.evt_len);
                sSecElem.mAtrInfolen = eventData->rcvd_evt.evt_len;
                sSecElem.mAtrStatus = eventData->rcvd_evt.status;
                sSecElem.mAbortEvent.notifyOne();
            }
            else
            {
                sSecElem.mAbortEventWaitOk = false;
                SyncEventGuard guard(sSecElem.mAbortEvent);
                sSecElem.mAbortEvent.notifyOne();
            }
        }
        if (eventData->rcvd_evt.evt_code == NFA_HCI_EVT_POST_DATA)
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_EVENT_RCVD_EVT; NFA_HCI_EVT_POST_DATA", fn);
            SyncEventGuard guard (sSecElem.mTransceiveEvent);
            sSecElem.mActualResponseSize = (eventData->rcvd_evt.evt_len > MAX_RESPONSE_SIZE) ? MAX_RESPONSE_SIZE : eventData->rcvd_evt.evt_len;
            sSecElem.mTransceiveEvent.notifyOne ();
        }
        else if (eventData->rcvd_evt.evt_code == NFA_HCI_EVT_TRANSACTION)
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_EVENT_RCVD_EVT; NFA_HCI_EVT_TRANSACTION", fn);
            // If we got an AID, notify any listeners
            if ((eventData->rcvd_evt.evt_len > 3) && (eventData->rcvd_evt.p_evt_buf[0] == 0x81) )
            {
                int aidlen = eventData->rcvd_evt.p_evt_buf[1];
                uint8_t* data = NULL;
                int32_t datalen = 0;
                uint8_t dataStartPosition = 0;
                if((eventData->rcvd_evt.evt_len > 2+aidlen) && (eventData->rcvd_evt.p_evt_buf[2+aidlen] == 0x82))
                {
                    //BERTLV decoding here, to support extended data length for params.
                    datalen = SecureElement::decodeBerTlvLength((uint8_t *)eventData->rcvd_evt.p_evt_buf, 2+aidlen+1, eventData->rcvd_evt.evt_len);
                }
                if(datalen >= 0)
                {
                    /* Over 128 bytes data of transaction can not receive on PN547, Ref. BER-TLV length fields in ISO/IEC 7816 */
                    if ( datalen < 0x80)
                    {
                        dataStartPosition = 2+aidlen+2;
                    }
                    else if ( datalen < 0x100)
                    {
                        dataStartPosition = 2+aidlen+3;
                    }
                    else if ( datalen < 0x10000)
                    {
                        dataStartPosition = 2+aidlen+4;
                    }
                    else if ( datalen < 0x1000000)
                    {
                        dataStartPosition = 2+aidlen+5;
                    }
                    data  = &eventData->rcvd_evt.p_evt_buf[dataStartPosition];

                    if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE)
                    {
                        if(MposManager::getInstance().validateHCITransactionEventParams(data, datalen) == NFA_STATUS_OK)
                            HciEventManager::getInstance().nfaHciCallback(event, eventData);
                    }
                    else
                            HciEventManager::getInstance().nfaHciCallback(event, eventData);
                }
                else
                {
                    LOG(ERROR) << StringPrintf("Event data TLV length encoding Unsupported!");
                }
            }
        }
        else if(eventData->rcvd_evt.evt_code == NFA_HCI_EVT_INIT_COMPLETED) {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_EVT_INIT_COMPLETED; received", fn);
        }
        else if (eventData->rcvd_evt.evt_code == NFA_HCI_EVT_CONNECTIVITY)
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_EVENT_RCVD_EVT; NFA_HCI_EVT_CONNECTIVITY", fn);
        }
        else
        {
            LOG(INFO) << StringPrintf("%s: NFA_HCI_EVENT_RCVD_EVT; ################################### eventData->rcvd_evt.evt_code = 0x%x , NFA_HCI_EVT_CONNECTIVITY = 0x%x", fn, eventData->rcvd_evt.evt_code, NFA_HCI_EVT_CONNECTIVITY);

            LOG(INFO) << StringPrintf("%s: NFA_HCI_EVENT_RCVD_EVT; ################################### ", fn);

        }
        break;

    case NFA_HCI_SET_REG_RSP_EVT: //received response to write registry command
        {
            tNFA_HCI_REGISTRY& registry = eventData->registry;
            LOG(INFO) << StringPrintf("%s: NFA_HCI_SET_REG_RSP_EVT; status=0x%X; pipe=0x%X", fn, registry.status, registry.pipe);
            SyncEventGuard guard (sSecElem.mRegistryEvent);
            sSecElem.mRegistryEvent.notifyOne ();
            break;
        }
    case NFA_HCI_INIT_COMPLETED:
        {
          LOG(INFO) << StringPrintf("%s: NFA_HCI_INIT_COMPLETED; received", fn);
          SyncEventGuard guard (sSecElem.mEERecoveryComplete);
          sSecElem.mEERecoveryComplete.notifyOne();
          SecureElement::getInstance().notifySeInitialized();
          break;
        }
#if (NXP_SRD == TRUE)
    case NFA_SRD_EVT_TIMEOUT:
    case NFA_SRD_FEATURE_NOT_SUPPORT_EVT:
        {
          SecureDigitization::getInstance().notifySrdEvt(event);
          break;
        }
#endif
    default:
      LOG(ERROR) << StringPrintf("%s: unknown event code=0x%X ????", fn, event);
      break;
    }
}

bool SecureElement::notifySeInitialized() {
    JNIEnv* e = NULL;
    static const char fn [] = "SecureElement::notifySeInitialized";
    if (NULL == mNativeData) {
      return false;
    }
    ScopedAttach attach(mNativeData->vm, &e);
    if (e == NULL)
    {
        DLOG_IF(ERROR, nfc_debug_enabled)
            << StringPrintf("%s: jni env is null", fn);
        return false;
    }
    e->CallVoidMethod (mNativeData->manager, android::gCachedNfcManagerNotifyEeUpdated);
    CHECK(!e->ExceptionCheck());
    return true;
}
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
bool SecureElement::transceive (uint8_t* xmitBuffer, int32_t xmitBufferSize, uint8_t* recvBuffer,
        int32_t recvBufferMaxSize, int32_t& recvBufferActualSize, int32_t timeoutMillisec)
{


    static const char fn [] = "SecureElement::transceive";
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    bool isSuccess = false;
    mTransceiveWaitOk = false;
    mTransceiveStatus = NFA_STATUS_OK;
    uint8_t newSelectCmd[NCI_MAX_AID_LEN + 10];
    isSuccess                  = false;

    // Check if we need to replace an "empty" SELECT command.
    // 1. Has there been a AID configured, and
    // 2. Is that AID a valid length (i.e 16 bytes max), and
    // 3. Is the APDU at least 4 bytes (for header), and
    // 4. Is INS == 0xA4 (SELECT command), and
    // 5. Is P1 == 0x04 (SELECT by AID), and
    // 6. Is the APDU len 4 or 5 bytes.
    //
    // Note, the length of the configured AID is in the first
    //   byte, and AID starts from the 2nd byte.
    if (mAidForEmptySelect[0]                           // 1
                           && (mAidForEmptySelect[0] <= NCI_MAX_AID_LEN)   // 2
                           && (xmitBufferSize >= 4)                        // 3
                           && (xmitBuffer[1] == 0xA4)                      // 4
                           && (xmitBuffer[2] == 0x04)                      // 5
                           && (xmitBufferSize <= 5))                       // 6
    {
        uint8_t idx = 0;

        // Copy APDU command header from the input buffer.
        memcpy(&newSelectCmd[0], &xmitBuffer[0], 4);
        idx = 4;

        // Set the Lc value to length of the new AID
        newSelectCmd[idx++] = mAidForEmptySelect[0];

        // Copy the AID
        memcpy(&newSelectCmd[idx], &mAidForEmptySelect[1], mAidForEmptySelect[0]);
        idx += mAidForEmptySelect[0];

        // If there is an Le (5th byte of APDU), add it to the end.
        if (xmitBufferSize == 5)
            newSelectCmd[idx++] = xmitBuffer[4];

        // Point to the new APDU
        xmitBuffer = &newSelectCmd[0];
        xmitBufferSize = idx;

        LOG(INFO) << StringPrintf("%s: Empty AID SELECT cmd detected, substituting AID from config file, new length=%d", fn, idx);
    }
    {
        SyncEventGuard guard (mTransceiveEvent);
        if (!mIsWiredModeOpen) {
            return isSuccess;
        }
        mActualResponseSize = 0;
        memset (mResponseData, 0, sizeof(mResponseData));
        nfaStat = NFA_HciSendApdu (mNfaHciHandle, mActiveEeHandle, xmitBufferSize, xmitBuffer, sizeof(mResponseData), mResponseData, timeoutMillisec);

        if (nfaStat == NFA_STATUS_OK)
        {
            mTransceiveEvent.wait ();
        }
        else
        {
            LOG(ERROR) << StringPrintf("%s: fail send data; error=0x%X", fn, nfaStat);
            goto TheEnd;
        }
    }
    if(mTransceiveStatus == NFA_STATUS_HCI_WTX_TIMEOUT)
    {
        LOG(ERROR) << StringPrintf("%s:timeout 1 %x",fn ,mTransceiveStatus);
        mAbortEventWaitOk = false;
        SyncEventGuard guard (mAbortEvent);
        nfaStat = NFA_HciAbortApdu(mNfaHciHandle,mActiveEeHandle,timeoutMillisec);
        if (nfaStat == NFA_STATUS_OK)
        {
            mAbortEvent.wait();
        }
        if ((mAbortEventWaitOk == false) && mIsWiredModeOpen &&
            (android::nfcManager_isNfcDisabling() != true)) {
          handleTransceiveTimeout(NFCC_DECIDES);

          LOG(INFO) << StringPrintf("%s: ABORT no response; power cycle  ", fn);
        }
    } else if(mTransceiveStatus == NFA_STATUS_TIMEOUT)
    {
        LOG(ERROR) << StringPrintf("%s: timeout 2 %x",fn ,mTransceiveStatus);
        //Try Mode Set on/off
        handleTransceiveTimeout(POWER_ALWAYS_ON);
        sendEvent(SecureElement::EVT_END_OF_APDU_TRANSFER);
        {
            tNFA_EE_INFO *pEE = findEeByHandle (EE_HANDLE_0xF3);
            uint8_t eeStatus = 0x00;
            if (pEE)
            {
              eeStatus = pEE->ee_status;
              LOG(INFO) << StringPrintf("%s: NFA_EE_MODE_SET_EVT reset status; (0x%04x)", fn, pEE->ee_status);
              if(eeStatus != NFA_EE_STATUS_ACTIVE)
              {
                  handleTransceiveTimeout(NFCC_DECIDES);
                  LOG(INFO) << StringPrintf("%s: NFA_EE_MODE_SET_EVT; power cycle complete ", fn);
              }
            }
        }
    }
        if (mActualResponseSize > recvBufferMaxSize)
            recvBufferActualSize = recvBufferMaxSize;
        else
            recvBufferActualSize = mActualResponseSize;

        memcpy (recvBuffer, mResponseData, recvBufferActualSize);
     isSuccess = true;
        TheEnd:
     return (isSuccess);
}

/*******************************************************************************
**
** Function:        getActiveSecureElementList
**
** Description:     Get the list of Activated Secure elements.
**                  e: Java Virtual Machine.
**
** Returns:         List of Activated Secure elements.
**
*******************************************************************************/
jintArray SecureElement::getActiveSecureElementList (JNIEnv* e)
{
    uint8_t num_of_nfcee_present = 0;
    tNFA_HANDLE nfcee_handle[MAX_NFCEE];
    tNFA_EE_STATUS nfcee_status[MAX_NFCEE];
    jint seId = 0;
    int cnt = 0;
    int i;

    if (! getEeInfo())
        return (NULL);

    num_of_nfcee_present = mNfceeData_t.mNfceePresent;

    jintArray list = e->NewIntArray (num_of_nfcee_present); //allocate array

    for(i = 0; i< num_of_nfcee_present ; i++)
    {
        nfcee_handle[i] = mNfceeData_t.mNfceeHandle[i];
        nfcee_status[i] = mNfceeData_t.mNfceeStatus[i];

        if(nfcee_handle[i] == EE_HANDLE_0xF3 && nfcee_status[i] == NFC_NFCEE_STATUS_ACTIVE)
        {
            seId = getGenericEseId(EE_HANDLE_0xF3 & ~NFA_HANDLE_GROUP_EE);
        }

        if(nfcee_handle[i] == EE_HANDLE_0xF4 && nfcee_status[i] == NFC_NFCEE_STATUS_ACTIVE)
        {
            seId = getGenericEseId(EE_HANDLE_0xF4 & ~NFA_HANDLE_GROUP_EE);
        }

        if(nfcee_handle[i] == EE_HANDLE_0xF8 && nfcee_status[i] == NFC_NFCEE_STATUS_ACTIVE)
        {
            seId = getGenericEseId(EE_HANDLE_0xF8 & ~NFA_HANDLE_GROUP_EE);
        }

        e->SetIntArrayRegion (list, cnt++, 1, &seId);
    }

    return list;
}
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
bool SecureElement::activate (jint seID)
{
    static const char fn [] = "SecureElement::activate";
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    int numActivatedEe = 0;

    tNFA_HANDLE handle = getEseHandleFromGenericId(seID);

    LOG(INFO) << StringPrintf("%s: enter handle=0x%X, seID=0x%X", fn, handle,seID);

    // Get Fresh EE info if needed.
    if (! getEeInfo())
    {
        LOG(ERROR) << StringPrintf("%s: no EE info", fn);
        return false;
    }
    if(SecureElement::getInstance().getGateAndPipeList() != SMX_PIPE_ID)
      return false;

    //activate every discovered secure element
    for (int index=0; index < mActualNumEe; index++)
    {
        tNFA_EE_INFO& eeItem = mEeInfo[index];

        if (eeItem.ee_handle == EE_HANDLE_0xF3)
        {
            if (eeItem.ee_status != NFC_NFCEE_STATUS_INACTIVE)
            {
                LOG(INFO) << StringPrintf("%s: h=0x%X already activated", fn, eeItem.ee_handle);
                numActivatedEe++;
                continue;
            }

            {
                LOG(INFO) << StringPrintf("%s: set EE mode activate; h=0x%X", fn, eeItem.ee_handle);
                if ((nfaStat = SecElem_EeModeSet (eeItem.ee_handle, NFA_EE_MD_ACTIVATE)) == NFA_STATUS_OK)
                {
                    if (eeItem.ee_status == NFC_NFCEE_STATUS_ACTIVE)
                        numActivatedEe++;
                    if(eeItem.ee_handle == EE_HANDLE_0xF3)
                    {
                        SyncEventGuard guard (SecureElement::getInstance().mModeSetNtf);
                        if(SecureElement::getInstance().mModeSetNtf.wait(500) == false)
                        {
                            LOG(ERROR) << StringPrintf("%s: timeout waiting for setModeNtf", __func__);
                        }
                    }
                }
                else
                    LOG(ERROR) << StringPrintf("%s: NFA_EeModeSet failed; error=0x%X", fn, nfaStat);
            }
        }
    } //for

    mActiveEeHandle = getActiveEeHandle(handle);

    if (mActiveEeHandle == NFA_HANDLE_INVALID)
        LOG(ERROR) << StringPrintf("%s: ee handle not found", fn);

    LOG(INFO) << StringPrintf("%s: exit; active ee h=0x%X", fn, mActiveEeHandle);
    return mActiveEeHandle != NFA_HANDLE_INVALID;
}
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
bool SecureElement::deactivate (jint seID)
{
    static const char fn [] = "SecureElement::deactivate";
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    bool retval = false;

    LOG(INFO) << StringPrintf("%s: enter; seID=0x%X, mActiveEeHandle=0x%X", fn, seID, mActiveEeHandle);

    tNFA_HANDLE handle = getEseHandleFromGenericId(seID);

    LOG(INFO) << StringPrintf("%s: handle=0x%X", fn, handle);

    if (!mIsInit)
    {
        LOG(ERROR) << StringPrintf("%s: not init", fn);
        goto TheEnd;
    }

    if (seID == NFA_HANDLE_INVALID)
    {
        LOG(ERROR) << StringPrintf("%s: invalid EE handle", fn);
        goto TheEnd;
    }

    mActiveEeHandle = NFA_HANDLE_INVALID;

    //deactivate secure element
    for (int index=0; index < mActualNumEe; index++)
    {
        tNFA_EE_INFO& eeItem = mEeInfo[index];

        if (eeItem.ee_handle == handle &&
                ((eeItem.ee_handle == EE_HANDLE_0xF3) || (eeItem.ee_handle == EE_HANDLE_0xF4) ||
                        (eeItem.ee_handle == EE_HANDLE_0xF8)||
                        (eeItem.ee_handle == EE_HANDLE_0xF9))) {
            if (eeItem.ee_status == NFC_NFCEE_STATUS_INACTIVE)
            {
                LOG(INFO) << StringPrintf("%s: h=0x%X already deactivated", fn, eeItem.ee_handle);
                break;
            }

            {
                LOG(INFO) << StringPrintf("%s: set EE mode activate; h=0x%X", fn, eeItem.ee_handle);
                if ((nfaStat = SecElem_EeModeSet (eeItem.ee_handle, NFA_EE_MD_DEACTIVATE)) == NFA_STATUS_OK)
                {
                    LOG(INFO) << StringPrintf("%s: eeItem.ee_status =0x%X  NFC_NFCEE_STATUS_INACTIVE = %x", fn, eeItem.ee_status, NFC_NFCEE_STATUS_INACTIVE);
                    if (eeItem.ee_status == NFC_NFCEE_STATUS_INACTIVE)
                    {
                        LOG(ERROR) << StringPrintf("%s: NFA_EeModeSet success; status=0x%X", fn, nfaStat);
                        retval = true;
                    }
                }
                else
                    LOG(ERROR) << StringPrintf("%s: NFA_EeModeSet failed; error=0x%X", fn, nfaStat);
            }
        }
    } //for

TheEnd:
    LOG(INFO) << StringPrintf("%s: exit; ok=%u", fn, retval);
    return retval;
}
/*******************************************************************************
 **
 ** Function:       SecElem_EeModeSet
 **
 ** Description:    Perform SE mode set ON/OFF based on mode type
 **
 ** Returns:        NFA_STATUS_OK/NFA_STATUS_FAILED.
 **
 *******************************************************************************/
tNFA_STATUS SecureElement::SecElem_EeModeSet(uint16_t handle, uint8_t mode)
{
    tNFA_STATUS stat = NFA_STATUS_FAILED;
    LOG(INFO) << StringPrintf("%s:Enter mode = %d", __func__, mode);

    SyncEventGuard guard (sSecElem.mEeSetModeEvent);
    stat =  NFA_EeModeSet(handle, mode);
    if(stat == NFA_STATUS_OK)
    {
      sSecElem.mEeSetModeEvent.wait ();
    }

    return stat;
}
/*******************************************************************************
**
** Function:        getSETechnology
**
** Description:     return the technologies suported by se.
**                  eeHandle: Handle to execution environment.
**
** Returns:         Information about an execution environment.
**
*******************************************************************************/
jint SecureElement::getSETechnology(tNFA_HANDLE eeHandle)
{
    int tech_mask = 0x00;
    //static const char fn [] = "SecureElement::getSETechnology";
    // Get Fresh EE info.
    if (! getEeInfo())
    {
        //ALOGE("%s: No updated eeInfo available", fn);
    }

    tNFA_EE_INFO* eeinfo = findEeByHandle(eeHandle);

    if(eeinfo!=NULL){
        if(eeinfo->la_protocol != 0x00)
        {
            tech_mask |= 0x01;
        }

        if(eeinfo->lb_protocol != 0x00)
        {
            tech_mask |= 0x02;
        }

        if(eeinfo->lf_protocol != 0x00)
        {
            tech_mask |= 0x04;
        }
    }

    return tech_mask;
}
/*******************************************************************************
 **
 ** Function:       notifyModeSet
 **
 ** Description:    Perform SE mode set ON/OFF based on mode type
 **
 ** Returns:        NFA_STATUS_OK/NFA_STATUS_FAILED.
 **
 *******************************************************************************/
void SecureElement::notifyModeSet (tNFA_HANDLE eeHandle, bool success, tNFA_EE_STATUS eeStatus)
{
    static const char* fn = "SecureElement::notifyModeSet";
                LOG(INFO) << StringPrintf("NFA_EE_MODE_SET_EVT; (0x%04x)",  eeHandle);
    tNFA_EE_INFO *pEE = sSecElem.findEeByHandle (eeHandle);
    if (pEE)
    {
        pEE->ee_status = eeStatus;
        LOG(INFO) << StringPrintf("%s: NFA_EE_MODE_SET_EVT; (0x%04x)", fn, pEE->ee_status);
    }
    else
        LOG(INFO) << StringPrintf("%s: NFA_EE_MODE_SET_EVT; EE: 0x%04x not found.  mActiveEeHandle: 0x%04x", fn, eeHandle, sSecElem.mActiveEeHandle);
    SyncEventGuard guard (sSecElem.mEeSetModeEvent);
    sSecElem.mEeSetModeEvent.notifyOne();
}
/*******************************************************************************
**
** Function:        getAtr
**
** Description:     GetAtr response from the connected eSE
**
** Returns:         Returns True if success
**
*******************************************************************************/
bool SecureElement::apduGateReset(jint seID, uint8_t* recvBuffer, int32_t *recvBufferSize)
{
    static const char fn[] = "SecureElement::getAtr";
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    uint8_t retry = 0;

    LOG(INFO) << StringPrintf("%s: enter; seID=0x%X", fn, seID);
    if(nfcFL.nfcNxpEse) {

    /*ETSI 12 Gate Info ATR */
    do {
      mAbortEventWaitOk = false;
      mAtrStatus = NFA_STATUS_FAILED;
      {
        SyncEventGuard guard (mAbortEvent);
        nfaStat = NFA_HciAbortApdu(mNfaHciHandle,mActiveEeHandle,SmbTransceiveTimeOutVal);
        if (nfaStat == NFA_STATUS_OK)
        {
          mAbortEvent.wait();
        }
      }
      retry++;
    } while((retry < 3) && (mAtrStatus == NFA_STATUS_INVALID_PARAM));

    if(mAbortEventWaitOk == false)
    {
      LOG(INFO) << StringPrintf("%s (EVT_ABORT) failed to receive EVT_ATR", fn);
      if(mAtrStatus == NFA_STATUS_TIMEOUT)
      {
        LOG(INFO) << StringPrintf("%s (EVT_ABORT) response timeout", fn);
        LOG(INFO) << StringPrintf("%s Perform NFCEE recovery", fn);
        if(!doNfcee_Session_Reset())
        {
          nfaStat = NFA_STATUS_FAILED;
          LOG(INFO) << StringPrintf("%s recovery failed", fn);
        }
        else
        {
          LOG(INFO) << StringPrintf("%s recovery success", fn);
          nfaStat = NFA_STATUS_OK;
        }
      }
      else if(mAtrStatus == NFA_STATUS_HCI_UNRECOVERABLE_ERROR)
      {
        LOG(INFO) << StringPrintf("%s EE discovery in progress", fn);
        SyncEventGuard guard (mEERecoveryComplete);
        mEERecoveryComplete.wait();
        nfaStat = NFA_STATUS_OK;
      }
      else if(mAtrStatus == NFA_STATUS_HCI_WTX_TIMEOUT)
      {
        LOG(INFO) << StringPrintf("%s MAX_WTX limit reached, eSE power recycle", fn);
        setNfccPwrConfig(NFCC_DECIDES);
        SecEle_Modeset(NFCEE_DISABLE);
        usleep(50 * 1000);
        setNfccPwrConfig(POWER_ALWAYS_ON|COMM_LINK_ACTIVE);
        SecEle_Modeset(NFCEE_ENABLE);
        nfaStat = NFA_STATUS_OK;
      }
      if((nfaStat == NFA_STATUS_OK) && (mErrorRecovery == true))
      {
        SyncEventGuard guard (mAbortEvent);
        nfaStat = NFA_HciAbortApdu(mNfaHciHandle,mActiveEeHandle,SmbTransceiveTimeOutVal);
        if (nfaStat == NFA_STATUS_OK)
        {
          mAbortEvent.wait();
        }
      }
      else
      {
        LOG(INFO) << StringPrintf("%s SMB Recovery disabled, application shall retry", fn);
        mAbortEventWaitOk = false;
      }
    }

    if(mAbortEventWaitOk)
    {
      *recvBufferSize = mAtrInfolen;
      mAtrRespLen = mAtrInfolen;
      memcpy(recvBuffer, mAtrInfo, mAtrInfolen);
      memset(mAtrRespData, 0, EVT_ABORT_MAX_RSP_LEN);
      memcpy(mAtrRespData, mAtrInfo, mAtrInfolen);
    }
  }
  return mAbortEventWaitOk;
}

/*******************************************************************************
**
** Function:        doNfcee_Session_Reset
**
** Description:     Perform NFcee session reset & recovery
**
** Returns:         Returns True if success
**
*******************************************************************************/
bool SecureElement::doNfcee_Session_Reset()
{
  tNFA_STATUS status = NFA_STATUS_FAILED;
  static const char fn[] = " SecureElement::doNfcee_Session_Reset";
  uint8_t reset_nfcee_session[] = { 0x20, 0x02, 0x0C, 0x01, 0xA0, 0xEB, 0x08,
  0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

  android::startRfDiscovery(false);

  if(android::NxpNfc_Write_Cmd_Common(sizeof(reset_nfcee_session),
  reset_nfcee_session) == NFA_STATUS_OK)
  {
    LOG(INFO) << StringPrintf("%s Nfcee session reset success", fn);

    if(setNfccPwrConfig(POWER_ALWAYS_ON) == NFA_STATUS_OK)
    {
      LOG(INFO) << StringPrintf("%s Nfcee session PWRLNK 01", fn);
      if(SecEle_Modeset(NFCEE_DISABLE))
      {
        LOG(INFO) << StringPrintf("%s Nfcee session mode set off", fn);
        usleep(100 * 1000);
        if(setNfccPwrConfig(POWER_ALWAYS_ON|COMM_LINK_ACTIVE) == NFA_STATUS_OK)
        {
          LOG(INFO) << StringPrintf("%s Nfcee session PWRLNK 03", fn);
          if(SecEle_Modeset(NFCEE_ENABLE))
          {
            usleep(100 * 1000);
            LOG(INFO) << StringPrintf("%s Nfcee session mode set on", fn);
            status = NFA_STATUS_OK;
          }
        }
      }
      else
        status = NFA_STATUS_FAILED;
    }
    else
      status = NFA_STATUS_FAILED;
  }
  else
    status = NFA_STATUS_FAILED;

  android::startRfDiscovery(true);
  return (status == NFA_STATUS_OK) ? true: false;
}

/*******************************************************************************
**
** Function:        getAtrData
**
** Description:     Stored GetAtr response
**
** Returns:         Returns True if success
**
*******************************************************************************/
bool SecureElement::getAtr(uint8_t* recvBuffer, int32_t *recvBufferSize)
{
    static const char fn[] = "SecureElement::getAtrData";
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;

    LOG(INFO) << StringPrintf("%s: enter;", fn);

    if(nfcFL.nfcNxpEse && mAtrRespLen != 0) {
      *recvBufferSize = mAtrRespLen;
      memcpy(recvBuffer, mAtrRespData, mAtrRespLen);
      nfaStat = NFA_STATUS_OK;
    }

    return (nfaStat == NFA_STATUS_OK)?true:false;
}

/*******************************************************************************
**
** Function:        SecEle_Modeset
**
** Description:     reSet NFCEE.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool SecureElement::SecEle_Modeset(uint8_t type)
{
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    bool retval = true;

    LOG(INFO) << StringPrintf("set EE mode = 0x%X", type);
    nfaStat = SecElem_EeModeSet (EE_HANDLE_0xF3, type);
    if ( nfaStat == NFA_STATUS_OK)
    {
        LOG(INFO) << StringPrintf("SecEle_Modeset: Success");
    }
    else
    {
        retval = false;
    }
    return retval;
}

/*******************************************************************************
**
** Function:        initializeEeHandle
**
** Description:     Set NFCEE handle.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool SecureElement::initializeEeHandle ()
{
    if(NFA_GetNCIVersion() == NCI_VERSION_2_0)
        EE_HANDLE_0xF4 = 0x480;
    else
        EE_HANDLE_0xF4 = 0x402;
    return true;
}
/*******************************************************************************
**
** Function:        getEeInfo
**
** Description:     Get latest information about execution environments from stack.
**
** Returns:         True if at least 1 EE is available.
**
*******************************************************************************/
bool SecureElement::getEeInfo()
{
    static const char fn [] = "SecureElement::getEeInfo";
    LOG(INFO) << StringPrintf("%s: enter; mbNewEE=%d, mActualNumEe=%d", fn, mbNewEE, mActualNumEe);
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;

    /*Reading latest eEinfo  incase it is updated*/
    mbNewEE = true;
    mNumEePresent = 0;

    if (mbNewEE)
    {

        memset (&mNfceeData_t, 0, sizeof (mNfceeData_t));

        mActualNumEe = nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED;
        if ((nfaStat = NFA_EeGetInfo(&mActualNumEe, mEeInfo)) != NFA_STATUS_OK)
        {
            LOG(ERROR) << StringPrintf("%s: fail get info; error=0x%X", fn, nfaStat);
            mActualNumEe = 0;
        }
        else
        {
            mbNewEE = false;

            LOG(ERROR) << StringPrintf("%s: num EEs discovered: %u", fn, mActualNumEe);
            if (mActualNumEe != 0)
            {
                for (uint8_t xx = 0; xx < mActualNumEe; xx++)
                {
                    if (mEeInfo[xx].ee_interface[0] != NCI_NFCEE_INTERFACE_HCI_ACCESS)
                        mNumEePresent++;

                    mNfceeData_t.mNfceeHandle[xx] = mEeInfo[xx].ee_handle;
                    mNfceeData_t.mNfceeStatus[xx] = mEeInfo[xx].ee_status;
                }
            }
        }
    }
    LOG(INFO) << StringPrintf("%s: exit; mActualNumEe=%d, mNumEePresent=%d", fn, mActualNumEe,mNumEePresent);

    mNfceeData_t.mNfceePresent = mNumEePresent;

    return (mActualNumEe != 0);
}
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
tNFA_EE_INFO *SecureElement::findEeByHandle (tNFA_HANDLE eeHandle)
{
    for (uint8_t xx = 0; xx < mActualNumEe; xx++)
    {
        if (mEeInfo[xx].ee_handle == eeHandle)
            return (&mEeInfo[xx]);
    }
    return (NULL);
}
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
tNFA_HANDLE SecureElement::getEseHandleFromGenericId(jint eseId)
{
    uint16_t handle = NFA_HANDLE_INVALID;
    RoutingManager& rm = RoutingManager::getInstance();
    static const char fn [] = "SecureElement::getEseHandleFromGenericId";
    LOG(INFO) << StringPrintf("%s: enter; ESE-ID = 0x%02X", fn, eseId);

    //Map the generic id to actual handle
    if(eseId == ESE_ID || eseId == EE_APP_HANLDE_ESE) //ESE
    {
        handle = EE_HANDLE_0xF3; //0x4C0;
    }
    else if(eseId == UICC_ID || eseId == UICC2_ID)
    {
      handle = rm.getUiccRouteLocId(eseId);
    }
    else if(eseId == T4T_NFCEE_ID) //T4T NFCEE
    {
      handle = SecureElement::getInstance().EE_HANDLE_0xFE;  // 0x410;
    }
    else if(eseId == EE_APP_HANLDE_UICC) //UICC
    {
      handle = SecureElement::getInstance().EE_HANDLE_0xF4;  // 0x402;
    }
    else if(eseId == EE_APP_HANLDE_UICC2) //UICC
    {
        handle = RoutingManager::getInstance().getUicc2selected(); //0x402;
    }
    else if(eseId == UICC3_ID || eseId == EE_APP_HANLDE_UICC3) //UICC
    {
        handle = SecureElement::getInstance().EE_HANDLE_0xF9; //0x482;
    }
    else if(eseId == DH_ID) //Host
    {
        handle = SecureElement::getInstance().EE_HANDLE_0xF0; //0x400;
    }
    else if(eseId == EE_HANDLE_0xF3 || eseId == EE_HANDLE_0xF4 || eseId == EE_HANDLE_0xF9)
    {
        handle = eseId;
    }
    LOG(INFO) << StringPrintf("%s: enter; ESE-Handle = 0x%03X", fn, handle);
    return handle;
}
/*******************************************************************************
**
** Function:        getActiveEeHandle
**
** Description:     Get the handle to the execution environment.
**
** Returns:         Handle to the execution environment.
**
*******************************************************************************/
tNFA_HANDLE SecureElement::getActiveEeHandle (tNFA_HANDLE handle)
{
    static const char fn [] = "SecureElement::getActiveEeHandle";
    LOG(INFO) << StringPrintf("%s: - Enter", fn);

    for (uint8_t xx = 0; xx < mActualNumEe; xx++)
    {
         if (mEeInfo[xx].ee_handle == EE_HANDLE_0xF3)
         {
             return (mEeInfo[xx].ee_handle);
         }

    }
    return NFA_HANDLE_INVALID;
}
/*******************************************************************************
**
** Function         setNfccPwrConfig
**
** Description      sends the link cntrl command to eSE with the value passed
**
** Returns          status
**
*******************************************************************************/
tNFA_STATUS SecureElement::setNfccPwrConfig(uint8_t value)
{
    static const char fn [] = "SecureElement::setNfccPwrConfig()";
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    LOG(INFO) << StringPrintf("%s: Enter: config= 0x%X", fn, value);
    SyncEventGuard guard (mPwrLinkCtrlEvent);
    nfaStat = NFA_EePowerAndLinkCtrl((uint8_t)EE_HANDLE_0xF3, value);
    if(nfaStat ==  NFA_STATUS_OK) {
        if (mPwrLinkCtrlEvent.wait(NFC_CMD_TIMEOUT) == false) {
            LOG(ERROR) << StringPrintf("mPwrLinkCtrlEvent has terminated");
        }
    }
    LOG(INFO) << StringPrintf("%s: Exit: Status= 0x%X", fn, mPwrCmdstatus);
    return mPwrCmdstatus;
}
bool SecureElement::sendEvent(uint8_t event)
{
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    bool retval = true;
    SyncEventGuard guard(mHciSendEvent);
    nfaStat = NFA_HciSendEvent (mNfaHciHandle, mNewPipeId, event, 0x00, NULL, 0x00,NULL, 0);

    if(nfaStat != NFA_STATUS_OK)
        retval = false;
    else
        mHciSendEvent.wait();

    return retval;
}

/*******************************************************************************
**
** Function:        getEeHandleList
**
** Description:     Get default Secure Element handle.
**                  isHCEEnabled: whether host routing is enabled or not.
**
** Returns:         Returns Secure Element list and count.
**
*******************************************************************************/
void SecureElement::getEeHandleList(tNFA_HANDLE *list, uint8_t* count)
{
    tNFA_HANDLE handle;
    int i;
    static const char fn [] = "SecureElement::getEeHandleList";
    *count = 0;
    for ( i = 0; i < mActualNumEe; i++)
    {
        LOG(INFO) << StringPrintf("%s: %d = 0x%X", fn, i, mEeInfo[i].ee_handle);
        if ((mEeInfo[i].ee_handle == 0x401) || (mEeInfo[i].ee_interface[0] == NCI_NFCEE_INTERFACE_HCI_ACCESS) ||
            (mEeInfo[i].ee_status == NFC_NFCEE_STATUS_INACTIVE))
        {
            LOG(INFO) << StringPrintf("%s: %u = 0x%X", fn, i, mEeInfo[i].ee_handle);
            continue;
        }

        handle = mEeInfo[i].ee_handle & ~NFA_HANDLE_GROUP_EE;
        list[*count] = handle;
        *count = *count + 1 ;
        LOG(INFO) << StringPrintf("%s: Handle %d = 0x%X", fn, i, handle);
    }
}

/*******************************************************************************
**
** Function:        getGateAndPipeList
**
** Description:     Get the gate and pipe list.
**
** Returns:         None
**
*******************************************************************************/
uint8_t SecureElement::getGateAndPipeList()
{
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    static const char fn [] = "SecureElement::getActiveEeHandle";
    //uint8_t destHost = (EE_HANDLE_0xF3 & ~NFA_HANDLE_GROUP_EE);

    // Get a list of existing gates and pipes
    LOG(INFO) << StringPrintf("%s: get gate, pipe list", fn);
        SyncEventGuard guard (mPipeListEvent);
        nfaStat = NFA_HciGetGateAndPipeList (mNfaHciHandle);
        if (nfaStat == NFA_STATUS_OK)
        {
            mPipeListEvent.wait();
            if (mHciCfg.status == NFA_STATUS_OK)
            {
                 mNewPipeId = 0x19;
                /*WA: Not updated the pipe id from libnfc-nci
                for (uint8_t xx = 0; xx < mHciCfg.num_pipes; xx++)
                {
                    if ( (mHciCfg.pipe[xx].dest_host == destHost))
                    {
                        mNewSourceGate = mHciCfg.pipe[xx].local_gate;
                        mNewPipeId     = mHciCfg.pipe[xx].pipe_id;

                        LOG(INFO) << StringPrintf("%s: found configured gate: 0x%02x  pipe: 0x%02x", fn, mNewSourceGate, mNewPipeId);
                        break;
                    }
                }
                */
            }
        }
        return mNewPipeId;
}
/*******************************************************************************
**
** Function         getLastRfFiledToggleTime
**
** Description      Provides the last RF filed toggile timer
**
** Returns          timespec
**
*******************************************************************************/
struct timespec SecureElement::getLastRfFiledToggleTime(void)
{
  return mLastRfFieldToggle;
}
/*******************************************************************************
**
** Function:        finalize
**
** Description:     Release all resources.
**
** Returns:         None
**
*******************************************************************************/
void SecureElement::finalize() {
  mIsInit     = false;
  mNativeData = NULL;
#if(NXP_EXTNS == TRUE)
  mRfFieldIsOn = false;
  memset (&mLastRfFieldToggle, 0, sizeof(mLastRfFieldToggle));
#endif
}

/*******************************************************************************
**
** Function:        releasePendingTransceive
**
** Description:     release any pending transceive wait.
**
** Returns:         None.
**
*******************************************************************************/
void SecureElement::releasePendingTransceive()
{
    AutoMutex mutex(mTimeoutHandleMutex);
    static const char fn [] = "SecureElement::releasePendingTransceive";
    LOG(INFO) << StringPrintf("%s: Entered", fn);
    SyncEventGuard guard (mTransceiveEvent);
    mTransceiveEvent.notifyOne();
    SyncEventGuard guard_Abort(mAbortEvent);
    mAbortEvent.notifyOne();
    LOG(INFO) << StringPrintf("%s: Exit", fn);
}

/*******************************************************************************
**
** Function:        isWiredModeOpen
**
** Description:     Wired Mode open status
**
** Returns:         0 if wired mode closed.
**
*******************************************************************************/
int SecureElement::isWiredModeOpen() {
  if (mIsWiredModeOpen) return 1;
  return 0;
}
/**********************************************************************************
 **
 ** Function:        getUiccStatus
 **
 ** Description:     get the status of EE
 **
 ** Returns:         UICC Status
 **
 **********************************************************************************/
uicc_stat_t SecureElement::getUiccStatus(uint8_t selected_uicc) {
  uint16_t ee_stat = NFA_EE_STATUS_REMOVED;

  if(!isDynamicUiccEnabled) {
    if (selected_uicc == 0x01)
      ee_stat = getEeStatus(EE_HANDLE_0xF4);
    else if (selected_uicc == 0x02)
      ee_stat = getEeStatus(EE_HANDLE_0xF8);
  }

  uicc_stat_t uicc_stat = UICC_STATUS_UNKNOWN;

  if (selected_uicc == 0x01) {
    switch (ee_stat) {
      case 0x00:
        uicc_stat = UICC_01_SELECTED_ENABLED;
        break;
      case 0x01:
        uicc_stat = UICC_01_SELECTED_DISABLED;
        break;
      case 0x02:
        uicc_stat = UICC_01_REMOVED;
        break;
    }
  } else if (selected_uicc == 0x02) {
    switch (ee_stat) {
      case 0x00:
        uicc_stat = UICC_02_SELECTED_ENABLED;
        break;
      case 0x01:
        uicc_stat = UICC_02_SELECTED_DISABLED;
        break;
      case 0x02:
        uicc_stat = UICC_02_REMOVED;
        break;
    }
  }
  return uicc_stat;
}

/**********************************************************************************
 **
 ** Function:        getEeStatus
 **
 ** Description:     get the status of EE
 **
 ** Returns:         EE status
 **
 **********************************************************************************/
uint16_t SecureElement::getEeStatus(uint16_t eehandle) {
  int i;
  uint16_t ee_status = NFA_EE_STATUS_REMOVED;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s  num_nfcee_present = %d", __func__, mNfceeData_t.mNfceePresent);

  for (i = 0; i <= mNfceeData_t.mNfceePresent; i++) {
    if (mNfceeData_t.mNfceeHandle[i] == eehandle) {
      ee_status = mNfceeData_t.mNfceeStatus[i];
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s  EE is detected 0x%02x  status = 0x%02x",
                          __func__, eehandle, ee_status);
      break;
    }
  }
  return ee_status;
}

/*******************************************************************************
**
** Function:        handleTransceiveTimeout
**
** Description:     Reset eSE via power link & Mode set command
**                  after Transceive Timed out.
**
** Returns:         None
**
*******************************************************************************/
void SecureElement::handleTransceiveTimeout(uint8_t powerConfigValue) {
    AutoMutex mutex(mTimeoutHandleMutex);
    SyncEvent sTimeOutDelaySyncEvent;
    if (!mIsWiredModeOpen) return;
    setNfccPwrConfig(powerConfigValue);

    SecEle_Modeset(NFCEE_DISABLE);
    {
        SyncEventGuard gaurd(sTimeOutDelaySyncEvent);
        sTimeOutDelaySyncEvent.wait(1 * ONE_SECOND_MS);
    }


    setNfccPwrConfig(POWER_ALWAYS_ON | COMM_LINK_ACTIVE);
    SecEle_Modeset(NFCEE_ENABLE);
    {
        SyncEventGuard gaurd(sTimeOutDelaySyncEvent);
        sTimeOutDelaySyncEvent.wait(200);
    }
}