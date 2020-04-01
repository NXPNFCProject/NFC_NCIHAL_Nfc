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
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include "DwpChannel.h"
#include <cutils/log.h>
#include "RoutingManager.h"
#include "SecureElement.h"
#include "config.h"
#include "phNxpConfig.h"
#include "nfc_config.h"
using android::base::StringPrintf;
static const int EE_ERROR_OPEN_FAIL = -1;

extern bool nfc_debug_enabled;

bool IsWiredMode_Enable();
bool eSE_connected = false;
bool dwpChannelForceClose = false;
DwpChannel DwpChannel::sDwpChannel;
namespace android {
extern void checkforNfceeConfig();
extern int gMaxEERecoveryTimeout;
}  // namespace android

/*******************************************************************************
**
** Function:        IsWiredMode_Enable
**
** Description:     Provides the connection status of EE
**
** Returns:         True if ok.
**
*******************************************************************************/
bool IsWiredMode_Enable() {
  static const char fn[] = "DwpChannel::IsWiredMode_Enable";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", fn);
  SecureElement& se = SecureElement::getInstance();
  tNFA_STATUS stat = NFA_STATUS_FAILED;

  uint8_t mActualNumEe = nfcFL.nfccFL._NFA_EE_MAX_EE_SUPPORTED;
  uint16_t meSE = 0x4C0;
  tNFA_EE_INFO EeInfo[mActualNumEe];

#if 0
    if(mIsInit == false)
    {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: JcopOs Dwnld is not initialized", fn);
        goto TheEnd;
    }
#endif
  stat = NFA_EeGetInfo(&mActualNumEe, EeInfo);
  if (stat == NFA_STATUS_OK) {
    for (int xx = 0; xx < mActualNumEe; xx++) {
      ALOGI("xx=%d, ee_handle=0x0%x, status=0x0%x", xx, EeInfo[xx].ee_handle,
            EeInfo[xx].ee_status);
      if (EeInfo[xx].ee_handle == meSE) {
        if (EeInfo[xx].ee_status == 0x00) {
          stat = NFA_STATUS_OK;
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: status = 0x%x", fn, stat);
          break;
        } else if (EeInfo[xx].ee_status == 0x01) {
          LOG(ERROR) << StringPrintf("%s: Enable eSE-mode set ON", fn);
          se.SecEle_Modeset(0x01);
          usleep(2000 * 1000);
          stat = NFA_STATUS_OK;
          break;
        } else {
          stat = NFA_STATUS_FAILED;
          break;
        }
      } else {
        stat = NFA_STATUS_FAILED;
      }
    }
  }
  // TheEnd: /*commented to eliminate the label defined but not used warning*/
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: exit; status = 0x%X", fn, stat);
  if (stat == NFA_STATUS_OK)
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

int16_t open() {
  static const char fn[] = "DwpChannel::open";
  bool stat = false;
  int16_t dwpHandle = EE_ERROR_OPEN_FAIL;
  SecureElement& se = SecureElement::getInstance();

  LOG(ERROR) << StringPrintf("DwpChannel: Sec Element open Enter");
  if (nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION) {
    DwpChannel::getInstance().Initialize();
  }
  LOG(ERROR) << StringPrintf("DwpChannel: Sec Element open Enter");
  if (se.isBusy()) {
    LOG(ERROR) << StringPrintf("DwpChannel: SE is busy");
    return EE_ERROR_OPEN_FAIL;
  }

  eSE_connected = IsWiredMode_Enable();
  if (eSE_connected != true) {
    LOG(ERROR) << StringPrintf("DwpChannel: Wired mode is not connected");
    return EE_ERROR_OPEN_FAIL;
  }

  /*turn on the sec elem*/
  stat = se.activate(SecureElement::ESE_ID);

  if (stat) {
    // establish a pipe to sec elem
    stat = se.connectEE();
    if (!stat) {
      se.deactivate(0);
    } else {
      dwpChannelForceClose = false;
      dwpHandle = se.mActiveEeHandle;
      if (nfcFL.eseFL._ESE_DUAL_MODE_PRIO_SCHEME ==
          nfcFL.eseFL._ESE_WIRED_MODE_RESUME) {
        /*NFCC shall keep secure element always powered on ;
         * however NFCC may deactivate communication link with
         * secure element.
         * NOTE: Since open() api does not call
         * nativeNfcSecureElement_doOpenSecureElementConnection()
         * and LS application can invoke
         * open(), POWER_ALWAYS_ON is needed.
         */
        if (se.setNfccPwrConfig(se.POWER_ALWAYS_ON | se.COMM_LINK_ACTIVE) !=
            NFA_STATUS_OK) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: power link command failed", __func__);
        }
        se.SecEle_Modeset(0x01);
      }
    }
  }

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Exit. dwpHandle = 0x%02x", fn, dwpHandle);
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

bool close(int16_t mHandle) {
  static const char fn[] = "DwpChannel::close";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", fn);
  bool stat = false;
  SecureElement& se = SecureElement::getInstance();
  if (nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION) {
    DwpChannel::getInstance().finalize();
  }
  if (mHandle == EE_ERROR_OPEN_FAIL) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: Channel access denied. Returning", fn);
    return stat;
  }
  if (eSE_connected != true) return true;

#if (NXP_EXTNS == TRUE)
  if (nfcFL.nfcNxpEse) {
    se.NfccStandByOperation(STANDBY_MODE_ON);
  }
#endif

  stat = se.disconnectEE(SecureElement::ESE_ID);

  // if controller is not routing AND there is no pipe connected,
  // then turn off the sec elem
  if (((nfcFL.chipType == pn547C2) && (nfcFL.nfcNxpEse)) && (!se.isBusy())) {
    se.deactivate(SecureElement::ESE_ID);
  }
  return stat;
}

bool transceive(uint8_t* xmitBuffer, int32_t xmitBufferSize,
                uint8_t* recvBuffer, int32_t recvBufferMaxSize,
                int32_t& recvBufferActualSize, int32_t timeoutMillisec) {
  static const char fn[] = "DwpChannel::transceive";
  eTransceiveStatus stat = TRANSCEIVE_STATUS_FAILED;
  SecureElement& se = SecureElement::getInstance();
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", fn);

  /*When Nfc deinitialization triggered*/
  if (dwpChannelForceClose == true) return stat;

  if (nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION &&
      DwpChannel::getInstance().dwpChannelForceClose) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", fn);
    return stat;
  }

  stat =
      se.transceive(xmitBuffer, xmitBufferSize, recvBuffer, recvBufferMaxSize,
                    recvBufferActualSize, timeoutMillisec);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", fn);
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
DwpChannel::DwpChannel() : dwpChannelForceClose(false) {}

/*******************************************************************************
**
** Function:        DwpChannel Destructor
**
** Description:     Class destructor
**
** Returns:         None.
**
*******************************************************************************/
DwpChannel::~DwpChannel() {}

/*******************************************************************************
**
** Function:        DwpChannel's get class instance
**
** Description:     Returns instance object of the class
**
** Returns:         DwpChannel instance.
**
*******************************************************************************/
DwpChannel& DwpChannel::getInstance() { return sDwpChannel; }

/*******************************************************************************
**
** Function:        finalize
**
** Description:     Release all resources.
**
** Returns:         None
**
*******************************************************************************/
void DwpChannel::finalize() { dwpChannelForceClose = false; }

/*******************************************************************************
**
** Function:        initialize
**
** Description:     Initialize all member variables.
**
** Returns:         None.
**
*******************************************************************************/
void DwpChannel::Initialize() { dwpChannelForceClose = false; }
/*******************************************************************************
**
** Function:        DwpChannel's force exit
**
** Description:     Force exit of DWP channel
**
** Returns:         None.
**
*******************************************************************************/
void DwpChannel::forceClose() {
  static const char fn[] = "DwpChannel::doDwpChannel_ForceExit";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter:", fn);
  if (!nfcFL.eseFL._ESE_JCOP_DWNLD_PROTECTION) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: ESE_JCOP_DWNLD_PROTECTION not available. Returning", fn);
    return;
  }
  dwpChannelForceClose = true;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Exit:", fn);
}

void doeSE_Reset(void) {
  static const char fn[] = "DwpChannel::doeSE_Reset";
  SecureElement& se = SecureElement::getInstance();
  RoutingManager& rm = RoutingManager::getInstance();
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter:", fn);

  rm.mResetHandlerMutex.lock();
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("1st mode set calling");
  se.SecEle_Modeset(0x00);
  usleep(100 * 1000);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("1st mode set called");
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("2nd mode set calling");

  se.SecEle_Modeset(0x01);
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("2nd mode set called");

  usleep(3000 * 1000);
  rm.mResetHandlerMutex.unlock();
  if ((nfcFL.nfccFL._NFCEE_REMOVED_NTF_RECOVERY) &&
      (RoutingManager::getInstance().is_ee_recovery_ongoing())) {
    LOG(ERROR) << StringPrintf("%s: is_ee_recovery_ongoing ", fn);
    SyncEventGuard guard(se.mEEdatapacketEvent);
    se.mEEdatapacketEvent.wait(android::gMaxEERecoveryTimeout);
  }
}
namespace android {
void doDwpChannel_ForceExit() {
  static const char fn[] = "DwpChannel::doDwpChannel_ForceExit";
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter:", fn);
  dwpChannelForceClose = true;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", fn);
}
}  // namespace android
