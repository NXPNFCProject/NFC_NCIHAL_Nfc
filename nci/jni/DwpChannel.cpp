/******************************************************************************
 *
 *  Copyright 2018 NXP
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
#include "DwpChannel.h"
#include "SecureElement.h"

using android::base::StringPrintf;

static const int EE_ERROR_OPEN_FAIL =  -1;

bool IsWiredMode_Enable();
bool eSE_connected = false;
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
    LOG(ERROR)
      << StringPrintf("%s: enter", fn);
    SecureElement &se = SecureElement::getInstance();
    tNFA_STATUS stat = NFA_STATUS_FAILED;

    uint8_t mActualNumEe  = nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED;
    uint16_t meSE         = 0x4C0;
    tNFA_EE_INFO EeInfo[mActualNumEe];

    stat = NFA_EeGetInfo(&mActualNumEe, EeInfo);
    if(stat == NFA_STATUS_OK)
    {
        for(int xx = 0; xx <  mActualNumEe; xx++)
        {
            LOG(INFO) << StringPrintf
                ("xx=%d, ee_handle=0x0%x, status=0x0%x", xx, EeInfo[xx].ee_handle,EeInfo[xx].ee_status);
            if (EeInfo[xx].ee_handle == meSE)
            {
                if(EeInfo[xx].ee_status == 0x00)
                {
                    stat = NFA_STATUS_OK;
                    LOG(ERROR)
                      << StringPrintf("%s: status = 0x%x", fn, stat);
                    break;
                }
                else if(EeInfo[xx].ee_status == 0x01)
                {
                    LOG(INFO) << StringPrintf("%s: Enable eSE-mode set ON", fn);
                    se.SecEle_Modeset(se.NFCEE_ENABLE);
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
    LOG(INFO) << StringPrintf("%s: exit; status = 0x%X", fn, stat);
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

    LOG(INFO) << StringPrintf("DwpChannel: Sec Element open Enter");
    if(nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION) {
        DwpChannel::getInstance().Initialize();
    }
    eSE_connected = IsWiredMode_Enable();
    if(eSE_connected != true)
    {
        LOG(ERROR) << StringPrintf("DwpChannel: Wired mode is not connected");
        return EE_ERROR_OPEN_FAIL;
    }

    /*turn on the sec elem*/
    stat = se.activate(SecureElement::ESE_ID);

    if (stat)
    {
       if(se.setNfccPwrConfig(se.POWER_ALWAYS_ON|se.COMM_LINK_ACTIVE) != NFA_STATUS_OK)
        {
            LOG(ERROR) << StringPrintf("%s: power link command failed", __func__);
        }
        se.SecEle_Modeset(se.NFCEE_ENABLE);
        dwpHandle = se.mActiveEeHandle; 
    }
    LOG(INFO) << StringPrintf("%s: Exit. dwpHandle = 0x%02x", fn,dwpHandle);
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
    LOG(INFO) << StringPrintf("%s: enter", fn);
    bool stat = false;
    if(nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION) {
        DwpChannel::getInstance().finalize();
    }
    if(mHandle == EE_ERROR_OPEN_FAIL)
    {
        LOG(ERROR)
        << StringPrintf("%s: Channel access denied. Returning", fn);
        return stat;
    }
    if(eSE_connected != true)
        return true;
    return stat;
}
/*******************************************************************************
**
** Function:        transceive
**
** Description:     send APDU data over APDU gate
**
** Returns:         True if ok.
**
*******************************************************************************/
bool transceive (uint8_t* xmitBuffer, int32_t xmitBufferSize, uint8_t* recvBuffer,
                 int32_t recvBufferMaxSize, int32_t& recvBufferActualSize, int32_t timeoutMillisec)
{
    static const char fn [] = "DwpChannel::transceive";
    bool stat = false;
    SecureElement &se = SecureElement::getInstance();
    LOG(INFO) << StringPrintf("%s: enter", fn);

    stat = se.transceive (xmitBuffer,
                          xmitBufferSize,
                          recvBuffer,
                          recvBufferMaxSize,
                          recvBufferActualSize,
                          timeoutMillisec);
    LOG(INFO) << StringPrintf("%s: exit %x", fn,stat);
    return (stat);
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
}

/*******************************************************************************
**
** Function:        doeSE_Reset
**
** Description:     reset the secure element.
**
** Returns:         None.
**
*******************************************************************************/
void doeSE_Reset(void)
{
    static const char fn [] = "DwpChannel::doeSE_Reset";
    SecureElement &se = SecureElement::getInstance();
    LOG(INFO) << StringPrintf("%s: enter:", fn);

    LOG(INFO) << StringPrintf("1st mode set calling");
    se.SecEle_Modeset(se.NFCEE_DISABLE);
    usleep(100 * 1000);
    LOG(INFO) << StringPrintf("1st mode set called");
    LOG(INFO) << StringPrintf("2nd mode set calling");
    se.SecEle_Modeset(se.NFCEE_ENABLE);
    LOG(INFO) << StringPrintf("2nd mode set called");
    usleep(3000 * 1000);
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
    SecureElement &se = SecureElement::getInstance();

    LOG(INFO) << StringPrintf("%s: enter:", fn);
    se.setNfccPwrConfig(se.NFCC_DECIDES);
    LOG(INFO) << StringPrintf("1st mode set");
    se.SecEle_Modeset(se.NFCEE_DISABLE);
    usleep(100 * 1000);
    se.setNfccPwrConfig(se.POWER_ALWAYS_ON|se.COMM_LINK_ACTIVE);
    se.SecEle_Modeset(se.NFCEE_ENABLE);
    LOG(INFO) << StringPrintf("2nd mode set");
    usleep(3000 * 1000);
}
