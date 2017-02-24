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
#if(NXP_ESE_JCOP_DWNLD_PROTECTION == TRUE)
DwpChannel DwpChannel::sDwpChannel;
#endif
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
    ALOGD ("%s: enter", fn);
    SecureElement &se = SecureElement::getInstance();
    tNFA_STATUS stat = NFA_STATUS_FAILED;

    UINT8 mActualNumEe  = SecureElement::MAX_NUM_EE;
    UINT16 meSE         = 0x4C0;
    tNFA_EE_INFO EeInfo[mActualNumEe];

#if 0
    if(mIsInit == false)
    {
        ALOGD ("%s: JcopOs Dwnld is not initialized", fn);
        goto TheEnd;
    }
#endif
    stat = NFA_EeGetInfo(&mActualNumEe, EeInfo);
    if(stat == NFA_STATUS_OK)
    {
        for(int xx = 0; xx <  mActualNumEe; xx++)
        {
            ALOGE("xx=%d, ee_handle=0x0%x, status=0x0%x", xx, EeInfo[xx].ee_handle,EeInfo[xx].ee_status);
            if (EeInfo[xx].ee_handle == meSE)
            {
                if(EeInfo[xx].ee_status == 0x00)
                {
                    stat = NFA_STATUS_OK;
                    ALOGD ("%s: status = 0x%x", fn, stat);
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
    ALOGD("%s: exit; status = 0x%X", fn, stat);
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

INT16 open()
{
    static const char fn [] = "DwpChannel::open";
    bool stat = false;
    INT16 dwpHandle = EE_ERROR_OPEN_FAIL;
    SecureElement &se = SecureElement::getInstance();

    ALOGE("DwpChannel: Sec Element open Enter");
#if(NXP_ESE_JCOP_DWNLD_PROTECTION == TRUE)
    DwpChannel::getInstance().Initialize();
#endif
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
#if(NXP_ESE_DUAL_MODE_PRIO_SCHEME == NXP_ESE_WIRED_MODE_RESUME)
          /*NFCC shall keep secure element always powered on ; however NFCC may deactivate communication link with secure element.
          **NOTE: Since open() api does not call nativeNfcSecureElement_doOpenSecureElementConnection() and LS application can invoke
          **open(), POWER_ALWAYS_ON is needed.
          */
          if(se.setNfccPwrConfig(se.POWER_ALWAYS_ON) != NFA_STATUS_OK)
          {
              ALOGD("%s: power link command failed", __FUNCTION__);
          }
#endif
        }
    }

    ALOGD("%s: Exit. dwpHandle = 0x%02x", fn,dwpHandle);
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

bool close(INT16 mHandle)
{
    static const char fn [] = "DwpChannel::close";
    ALOGD("%s: enter", fn);
    bool stat = false;
    SecureElement &se = SecureElement::getInstance();
#if(NXP_ESE_JCOP_DWNLD_PROTECTION == TRUE)
    DwpChannel::getInstance().finalize();
#endif
    if(mHandle == EE_ERROR_OPEN_FAIL)
    {
        ALOGD("%s: Channel access denied. Returning", fn);
        return stat;
    }
    if(eSE_connected != true)
        return true;

#if((NFC_NXP_ESE == TRUE) && (NXP_EXTNS == TRUE))
    se.NfccStandByOperation(STANDBY_MODE_ON);
#endif

    stat = se.disconnectEE (SecureElement::ESE_ID);

    //if controller is not routing AND there is no pipe connected,
    //then turn off the sec elem
    #if((NFC_NXP_CHIP_TYPE == PN547C2)&&(NFC_NXP_ESE == TRUE))
    if (! se.isBusy())
        se.deactivate (SecureElement::ESE_ID);
    #endif
     return stat;
}

bool transceive (UINT8* xmitBuffer, INT32 xmitBufferSize, UINT8* recvBuffer,
                 INT32 recvBufferMaxSize, INT32& recvBufferActualSize, INT32 timeoutMillisec)
{
    static const char fn [] = "DwpChannel::transceive";
    bool stat = false;
    SecureElement &se = SecureElement::getInstance();
    ALOGD("%s: enter", fn);

    /*When Nfc deinitialization triggered*/
    if(dwpChannelForceClose == true)
        return stat;

#if(NXP_ESE_JCOP_DWNLD_PROTECTION == TRUE)
    if(DwpChannel::getInstance().dwpChannelForceClose)
    {
        ALOGD("%s: exit", fn);
        return stat;
    }
#endif

    stat = se.transceive (xmitBuffer,
                          xmitBufferSize,
                          recvBuffer,
                          recvBufferMaxSize,
                          recvBufferActualSize,
                          timeoutMillisec);
    ALOGD("%s: exit", fn);
    return stat;
}

#if(NXP_ESE_JCOP_DWNLD_PROTECTION == TRUE)
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
    ALOGD("%s: Enter:", fn);
    dwpChannelForceClose = true;
    ALOGD("%s: Exit:", fn);
}
#endif

void doeSE_Reset(void)
{
    static const char fn [] = "DwpChannel::doeSE_Reset";
    SecureElement &se = SecureElement::getInstance();
    RoutingManager &rm = RoutingManager::getInstance();
    ALOGD("%s: enter:", fn);

    rm.mResetHandlerMutex.lock();
    ALOGD("1st mode set calling");
    se.SecEle_Modeset(0x00);
    usleep(100 * 1000);
    ALOGD("1st mode set called");
    ALOGD("2nd mode set calling");

    se.SecEle_Modeset(0x01);
    ALOGD("2nd mode set called");

    usleep(3000 * 1000);
    rm.mResetHandlerMutex.unlock();
#if (NXP_NFCEE_REMOVED_NTF_RECOVERY == TRUE)
    if((RoutingManager::getInstance().is_ee_recovery_ongoing()))
    {
        ALOGE ("%s: is_ee_recovery_ongoing ", fn);
        SyncEventGuard guard (se.mEEdatapacketEvent);
        se.mEEdatapacketEvent.wait(android::gMaxEERecoveryTimeout);
    }
#endif
}
namespace android
{
    void doDwpChannel_ForceExit()
    {
        static const char fn [] = "DwpChannel::doDwpChannel_ForceExit";
        ALOGD("%s: enter:", fn);
        dwpChannelForceClose = true;
        ALOGD("%s: exit", fn);
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
#if ((NXP_ESE_RESET_METHOD == TRUE) && (NXP_ESE_POWER_MODE == TRUE))
    unsigned long int num = 0;
#endif
    ALOGD("%s: enter:", fn);

    rm.mResetHandlerMutex.lock();
#if ((NXP_ESE_RESET_METHOD == TRUE) && (NXP_ESE_POWER_MODE == TRUE))
    if (GetNxpNumValue (NAME_NXP_ESE_POWER_DH_CONTROL, (void*)&num, sizeof(num)) == true)
    {
        if(num ==1)
        {
            ALOGD("1st mode set calling");
            se.SecEle_Modeset(0x00);
            usleep(100 * 1000);
            ALOGD("1st mode set called");
            ALOGD("2nd mode set calling");
            se.SecEle_Modeset(0x01);
            ALOGD("2nd mode set called");
            usleep(3000 * 1000);
        }
        else if(num ==2)
        {
            ALOGD("%s: eSE CHIP reset  on DWP Channel:", fn);
            se.SecEle_Modeset(0x00);
            usleep(100 * 1000);
            se.eSE_Chip_Reset();
            se.SecEle_Modeset(0x01);
            ALOGD("Chip Reset DONE");
            usleep(3000 * 1000);
        }
        else
        {
            ALOGD("%s: Invalid Power scheme:", fn);
        }
        /*
        if( (num == 1) || (num == 2))
        {
            if((se.eSE_Compliancy == se.eSE_Compliancy_ETSI_12)&&(se.mDeletePipeHostId == 0xC0))
            {
                ALOGD("%s: Clear All pipes received.....Create pipe at APDU Gate:", fn);
                se.mDeletePipeHostId = 0x00;
                android ::checkforNfceeConfig();
                SyncEventGuard guard (se.mCreatePipeEvent);
                nfaStat = NFA_HciCreatePipe(NFA_HANDLE_GROUP_HCI,NFA_HCI_ETSI12_APDU_GATE,0xC0,NFA_HCI_ETSI12_APDU_GATE);
                if(nfaStat == NFA_STATUS_OK)
                {
                    se.mCreatePipeEvent.wait();
                    ALOGD("%s: Created pipe at APDU Gate Open the pipe!!!", fn);
                    SyncEventGuard guard (se.mPipeOpenedEvent);
                    nfaStat = NFA_STATUS_FAILED;
                    se.mAbortEventWaitOk = false;
                    nfaStat = NFA_HciOpenPipe(NFA_HANDLE_GROUP_HCI,se.mCreatedPipe);
                    if(nfaStat == NFA_STATUS_OK)
                    {
                        se.mPipeOpenedEvent.wait();
                        ALOGD("%s:Pipe at APDU Gate opened successfully!!!", fn);
                        if(se.mAbortEventWaitOk == false)
                        {
                            SyncEventGuard guard (se.mAbortEvent);
                            se.mAbortEvent.wait();
                        }
                        ALOGD("%s:ATR received successfully!!!", fn);
                    }
                    else
                    {
                        ALOGD ("%s: fail open pipe; error=0x%X", fn, nfaStat);
                    }
                }
                else
                {
                    ALOGE ("%s: fail create pipe; error=0x%X", fn, nfaStat);
                }
            }
        }
        */
    }
#else
    ALOGD("1st mode set calling");
    se.SecEle_Modeset(0x00);
    usleep(100 * 1000);
    ALOGD("1st mode set called");
    ALOGD("2nd mode set calling");

    se.SecEle_Modeset(0x01);
    ALOGD("2nd mode set called");

    usleep(3000 * 1000);
#endif
    rm.mResetHandlerMutex.unlock();

#if (NXP_NFCEE_REMOVED_NTF_RECOVERY == TRUE)
    if((RoutingManager::getInstance().is_ee_recovery_ongoing()))
    {
        SyncEventGuard guard (se.mEEdatapacketEvent);
        se.mEEdatapacketEvent.wait(android::gMaxEERecoveryTimeout);
    }
#endif
}
