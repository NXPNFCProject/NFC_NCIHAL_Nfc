/*
 * Copyright (C) 2015 NXP Semiconductors
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
#include "DwpChannel.h"
#include "SecureElement.h"
#include "RoutingManager.h"
#include <cutils/log.h>
#include "config.h"
#include "phNxpConfig.h"

static const int EE_ERROR_OPEN_FAIL =  -1;

bool IsWiredMode_Enable();
bool eSE_connected = false;
bool dwpChannelForceClose = false;
DwpChannel DwpChannel::sDwpChannel;
namespace android
{
    extern void checkforNfceeConfig();
    extern int gMaxEERecoveryTimeout;
}

/*******************************************************************************
**
** Function:        IsWiredMode_Enable
**
** Description:     Provides the connection status of EE
**
** Returns:         True if ok.
**
*******************************************************************************/
bool IsWiredMode_Enable()
{
    static const char fn [] = "DwpChannel::IsWiredMode_Enable";
    ALOGV("%s: enter", fn);
    SecureElement &se = SecureElement::getInstance();
    tNFA_STATUS stat = NFA_STATUS_FAILED;

    uint8_t mActualNumEe  = nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED;
    uint16_t meSE         = 0x4C0;
    tNFA_EE_INFO EeInfo[mActualNumEe];

#if 0
    if(mIsInit == false)
    {
        ALOGV("%s: JcopOs Dwnld is not initialized", fn);
        goto TheEnd;
    }
#endif
    stat = NFA_EeGetInfo(&mActualNumEe, EeInfo);
    if(stat == NFA_STATUS_OK)
    {
        for(int xx = 0; xx <  mActualNumEe; xx++)
        {
            ALOGI("xx=%d, ee_handle=0x0%x, status=0x0%x", xx, EeInfo[xx].ee_handle,EeInfo[xx].ee_status);
            if (EeInfo[xx].ee_handle == meSE)
            {
                if(EeInfo[xx].ee_status == 0x00)
                {
                    stat = NFA_STATUS_OK;
                    ALOGV("%s: status = 0x%x", fn, stat);
                    break;
                }
                else if(EeInfo[xx].ee_status == 0x01)
                {
                    ALOGE("%s: Enable eSE-mode set ON", fn);
                    se.SecEle_Modeset(0x01);
                    usleep(2000 * 1000);
                    stat = NFA_STATUS_OK;
                    break;
                }
                else
                {
                    stat = NFA_STATUS_FAILED;
                    break;
                }
            }
            else
            {
                stat = NFA_STATUS_FAILED;
            }

        }
    }
//TheEnd: /*commented to eliminate the label defined but not used warning*/
    ALOGV("%s: exit; status = 0x%X", fn, stat);
    if(stat == NFA_STATUS_OK)
        return true;
    else
        return false;
}

/*******************************************************************************
**
** Function:        open
**
** Description:     Opens the DWP channel to eSE
**
** Returns:         True if ok.
**
*******************************************************************************/

int16_t open()
{
    static const char fn [] = "DwpChannel::open";
    bool stat = false;
    int16_t dwpHandle = EE_ERROR_OPEN_FAIL;
    SecureElement &se = SecureElement::getInstance();

    ALOGE("DwpChannel: Sec Element open Enter");
if(nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION) {
    DwpChannel::getInstance().Initialize();
}
    ALOGE("DwpChannel: Sec Element open Enter");
    if (se.isBusy())
    {
        ALOGE("DwpChannel: SE is busy");
        return EE_ERROR_OPEN_FAIL;
    }

    eSE_connected = IsWiredMode_Enable();
    if(eSE_connected != true)
    {
        ALOGE("DwpChannel: Wired mode is not connected");
        return EE_ERROR_OPEN_FAIL;
    }

    /*turn on the sec elem*/
    stat = se.activate(SecureElement::ESE_ID);

    if (stat)
    {
        //establish a pipe to sec elem
        stat = se.connectEE();
        if (!stat)
        {
          se.deactivate (0);
        }else
        {
            dwpChannelForceClose = false;
            dwpHandle = se.mActiveEeHandle;
            if(nfcFL.eseFL._ESE_DUAL_MODE_PRIO_SCHEME ==
                    nfcFL.eseFL._ESE_WIRED_MODE_RESUME) {
                /*NFCC shall keep secure element always powered on ;
                 * however NFCC may deactivate communication link with
                 * secure element.
                 * NOTE: Since open() api does not call
                 * nativeNfcSecureElement_doOpenSecureElementConnection()
                 * and LS application can invoke
                 * open(), POWER_ALWAYS_ON is needed.
                 */
                if(se.setNfccPwrConfig(se.POWER_ALWAYS_ON|se.COMM_LINK_ACTIVE) != NFA_STATUS_OK)
                {
                    ALOGV("%s: power link command failed", __func__);
                }
                se.SecEle_Modeset(0x01);
            }
        }
    }

    ALOGV("%s: Exit. dwpHandle = 0x%02x", fn,dwpHandle);
    return dwpHandle;
}
/*******************************************************************************
**
** Function:        close
**
** Description:     closes the DWP connection with eSE
**
** Returns:         True if ok.
**
*******************************************************************************/

bool close(int16_t mHandle)
{
    static const char fn [] = "DwpChannel::close";
    ALOGV("%s: enter", fn);
    bool stat = false;
    SecureElement &se = SecureElement::getInstance();
    if(nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION) {
        DwpChannel::getInstance().finalize();
    }
    if(mHandle == EE_ERROR_OPEN_FAIL)
    {
        ALOGV("%s: Channel access denied. Returning", fn);
        return stat;
    }
    if(eSE_connected != true)
        return true;

#if(NXP_EXTNS == TRUE)
    if(nfcFL.nfcNxpEse) {
        se.NfccStandByOperation(STANDBY_MODE_ON);
    }
#endif

    stat = se.disconnectEE (SecureElement::ESE_ID);

    //if controller is not routing AND there is no pipe connected,
    //then turn off the sec elem
    if(((nfcFL.chipType == pn547C2) && (nfcFL.nfcNxpEse)) &&
            (! se.isBusy())) {
        se.deactivate (SecureElement::ESE_ID);
    }
     return stat;
}

bool transceive (uint8_t* xmitBuffer, int32_t xmitBufferSize, uint8_t* recvBuffer,
                 int32_t recvBufferMaxSize, int32_t& recvBufferActualSize, int32_t timeoutMillisec)
{
    static const char fn [] = "DwpChannel::transceive";
    eTransceiveStatus stat = TRANSCEIVE_STATUS_FAILED;
    SecureElement &se = SecureElement::getInstance();
    ALOGV("%s: enter", fn);

    /*When Nfc deinitialization triggered*/
    if(dwpChannelForceClose == true)
        return stat;

    if(nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION && DwpChannel::getInstance().dwpChannelForceClose)
    {
        ALOGV("%s: exit", fn);
        return stat;
    }

    stat = se.transceive (xmitBuffer,
                          xmitBufferSize,
                          recvBuffer,
                          recvBufferMaxSize,
                          recvBufferActualSize,
                          timeoutMillisec);
    ALOGV("%s: exit", fn);
    return ((stat == TRANSCEIVE_STATUS_OK) ? true : false);
}

/*******************************************************************************
**
** Function:        DwpChannel Constructor
**
** Description:     Class constructor
**
** Returns:         None.
**
*******************************************************************************/
DwpChannel::DwpChannel ()
    :dwpChannelForceClose(false)
{
}

/*******************************************************************************
**
** Function:        DwpChannel Destructor
**
** Description:     Class destructor
**
** Returns:         None.
**
*******************************************************************************/
DwpChannel::~DwpChannel ()
{
}

/*******************************************************************************
**
** Function:        DwpChannel's get class instance
**
** Description:     Returns instance object of the class
**
** Returns:         DwpChannel instance.
**
*******************************************************************************/
DwpChannel& DwpChannel::getInstance()
{
    return sDwpChannel;
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
void DwpChannel::finalize()
{
    dwpChannelForceClose = false;
}

/*******************************************************************************
**
** Function:        initialize
**
** Description:     Initialize all member variables.
**
** Returns:         None.
**
*******************************************************************************/
void DwpChannel::Initialize()
{
    dwpChannelForceClose = false;
}
/*******************************************************************************
**
** Function:        DwpChannel's force exit
**
** Description:     Force exit of DWP channel
**
** Returns:         None.
**
*******************************************************************************/
void DwpChannel::forceClose()
{
    static const char fn [] = "DwpChannel::doDwpChannel_ForceExit";
    ALOGV("%s: Enter:", fn);
    if(!nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION) {
        ALOGV("%s: ESE_JCOP_DWNLD_PROTECTION not available. Returning", fn);
        return;
    }
    dwpChannelForceClose = true;
    ALOGV("%s: Exit:", fn);
}

void doeSE_Reset(void)
{
    static const char fn [] = "DwpChannel::doeSE_Reset";
    SecureElement &se = SecureElement::getInstance();
    RoutingManager &rm = RoutingManager::getInstance();
    ALOGV("%s: enter:", fn);

    rm.mResetHandlerMutex.lock();
    ALOGV("1st mode set calling");
    se.SecEle_Modeset(0x00);
    usleep(100 * 1000);
    ALOGV("1st mode set called");
    ALOGV("2nd mode set calling");

    se.SecEle_Modeset(0x01);
    ALOGV("2nd mode set called");

    usleep(3000 * 1000);
    rm.mResetHandlerMutex.unlock();
    if((nfcFL.nfccFL._NFCEE_REMOVED_NTF_RECOVERY) &&
            (RoutingManager::getInstance().is_ee_recovery_ongoing()))
    {
        ALOGE("%s: is_ee_recovery_ongoing ", fn);
        SyncEventGuard guard (se.mEEdatapacketEvent);
        se.mEEdatapacketEvent.wait(android::gMaxEERecoveryTimeout);
    }
}
namespace android
{
    void doDwpChannel_ForceExit()
    {
        static const char fn [] = "DwpChannel::doDwpChannel_ForceExit";
        ALOGV("%s: enter:", fn);
        dwpChannelForceClose = true;
        ALOGV("%s: exit", fn);
    }
}
/*******************************************************************************
**
** Function:        doeSE_JcopDownLoadReset
**
** Description:     Performs a reset to eSE during JCOP OS update depending on
**                  Power schemes configuered
**
** Returns:         void.
**
*******************************************************************************/
void doeSE_JcopDownLoadReset(void)
{
    static const char fn [] = "DwpChannel::JcopDownLoadReset";
    /*tNFA_STATUS nfaStat = NFA_STATUS_FAILED;*/
    SecureElement &se = SecureElement::getInstance();
    RoutingManager &rm = RoutingManager::getInstance();
    ALOGV("%s: enter:", fn);

    rm.mResetHandlerMutex.lock();
if (nfcFL.eseFL._ESE_RESET_METHOD && nfcFL.eseFL._ESE_POWER_MODE) {
    unsigned long int num = 0;
    if (GetNxpNumValue (NAME_NXP_ESE_POWER_DH_CONTROL, (void*)&num, sizeof(num)) == true)
    {
        if(num ==1)
        {
            if(NFA_GetNCIVersion() == NCI_VERSION_2_0)
            {
                se.setNfccPwrConfig(se.NFCC_DECIDES);
            }

            ALOGV("1st mode set calling");
            se.SecEle_Modeset(0x00);
            usleep(100 * 1000);
            ALOGV("1st mode set called");
            ALOGV("2nd mode set calling");

            if(NFA_GetNCIVersion() == NCI_VERSION_2_0)
            {
                se.setNfccPwrConfig(se.POWER_ALWAYS_ON|se.COMM_LINK_ACTIVE);
            }

            se.SecEle_Modeset(0x01);
            ALOGV("2nd mode set called");
            usleep(3000 * 1000);
        }
        else if(num ==2)
        {
            ALOGV("%s: eSE CHIP reset  on DWP Channel:", fn);
            se.SecEle_Modeset(0x00);
            usleep(100 * 1000);
            se.eSE_Chip_Reset();
            se.SecEle_Modeset(0x01);
            ALOGV("Chip Reset DONE");
            usleep(3000 * 1000);
        }
        else
        {
            ALOGV("%s: Invalid Power scheme:", fn);
        }
        /*
        if( (num == 1) || (num == 2))
        {
            if((se.eSE_Compliancy == se.eSE_Compliancy_ETSI_12)&&(se.mDeletePipeHostId == 0xC0))
            {
                ALOGV("%s: Clear All pipes received.....Create pipe at APDU Gate:", fn);
                se.mDeletePipeHostId = 0x00;
                android ::checkforNfceeConfig();
                SyncEventGuard guard (se.mCreatePipeEvent);
                nfaStat = NFA_HciCreatePipe(NFA_HANDLE_GROUP_HCI,NFA_HCI_ETSI12_APDU_GATE,0xC0,NFA_HCI_ETSI12_APDU_GATE);
                if(nfaStat == NFA_STATUS_OK)
                {
                    se.mCreatePipeEvent.wait();
                    ALOGV("%s: Created pipe at APDU Gate Open the pipe!!!", fn);
                    SyncEventGuard guard (se.mPipeOpenedEvent);
                    nfaStat = NFA_STATUS_FAILED;
                    se.mAbortEventWaitOk = false;
                    nfaStat = NFA_HciOpenPipe(NFA_HANDLE_GROUP_HCI,se.mCreatedPipe);
                    if(nfaStat == NFA_STATUS_OK)
                    {
                        se.mPipeOpenedEvent.wait();
                        ALOGV("%s:Pipe at APDU Gate opened successfully!!!", fn);
                        if(se.mAbortEventWaitOk == false)
                        {
                            SyncEventGuard guard (se.mAbortEvent);
                            se.mAbortEvent.wait();
                        }
                        ALOGV("%s:ATR received successfully!!!", fn);
                    }
                    else
                    {
                        ALOGV("%s: fail open pipe; error=0x%X", fn, nfaStat);
                    }
                }
                else
                {
                    ALOGE("%s: fail create pipe; error=0x%X", fn, nfaStat);
                }
            }
        }
             */
        }
    }
    else {
        ALOGV("1st mode set calling");
        se.SecEle_Modeset(0x00);
        usleep(100 * 1000);
        ALOGV("1st mode set called");
        ALOGV("2nd mode set calling");

        se.SecEle_Modeset(0x01);
        ALOGV("2nd mode set called");

        usleep(3000 * 1000);
    }
    rm.mResetHandlerMutex.unlock();

    if(nfcFL.nfccFL._NFCEE_REMOVED_NTF_RECOVERY && (RoutingManager::getInstance().is_ee_recovery_ongoing()))
    {
        SyncEventGuard guard (se.mEEdatapacketEvent);
        se.mEEdatapacketEvent.wait(android::gMaxEERecoveryTimeout);
    }
}
