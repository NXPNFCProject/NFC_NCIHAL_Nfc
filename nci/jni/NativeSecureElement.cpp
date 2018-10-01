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
#include <ScopedPrimitiveArray.h>
#include "JavaClassConstants.h"
#include "NfcTag.h"
#include "PowerSwitch.h"
#include "RoutingManager.h"
#include "SecureElement.h"
#include "phNxpConfig.h"
#include "nfc_config.h"

using android::base::StringPrintf;

extern bool nfc_debug_enabled;
extern bool hold_the_transceive;
extern int dual_mode_current_state;
extern bool ceTransactionPending;
namespace android {

extern void startRfDiscovery(bool isStart);
extern bool isDiscoveryStarted();
extern bool isp2pActivated();
extern void com_android_nfc_NfcManager_disableDiscovery(JNIEnv* e, jobject o);
extern void com_android_nfc_NfcManager_enableDiscovery(JNIEnv* e, jobject o,
                                                       jint mode);
#if (NXP_EXTNS == TRUE)
extern bool isLowRamDevice();
extern int gMaxEERecoveryTimeout;
#endif
Mutex mSPIDwpSyncMutex;
static SyncEvent sNfaVSCResponseEvent;
// static bool sRfEnabled;           /*commented to eliminate warning defined
// but not used*/

// These must match the EE_ERROR_ types in NfcService.java
static const int EE_ERROR_IO = -1;
static const int EE_ERROR_INIT = -3;

/*******************************************************************************
**
** Function:        nativeNfcSecureElement_doOpenSecureElementConnection
**
** Description:     Connect to the secure element.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Handle of secure element.  values < 0 represent failure.
**
*******************************************************************************/
#if (NXP_EXTNS == TRUE)
static jint nativeNfcSecureElement_doOpenSecureElementConnection(
    JNIEnv*, jobject, __attribute__((unused)) jint seId)
#else
static jint nativeNfcSecureElement_doOpenSecureElementConnection(JNIEnv*,
                                                                 jobject)
#endif
{
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
  bool stat = false;
  jint secElemHandle = EE_ERROR_INIT;
  long ret_val = -1;
  NFCSTATUS status = NFCSTATUS_FAILED;
  p61_access_state_t p61_current_state = P61_STATE_INVALID;
  se_apdu_gate_info gateInfo = NO_APDU_GATE;
  SecureElement& se = SecureElement::getInstance();
  android::mSPIDwpSyncMutex.lock();
#if (NXP_EXTNS == TRUE)
  if ((!nfcFL.nfcNxpEse) ||
      (!nfcFL.eseFL._ESE_WIRED_MODE_PRIO && se.isBusy())) {
    goto TheEnd;
  }

  ret_val = NFC_GetP61Status((void*)&p61_current_state);
  if (ret_val < 0) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("NFC_GetP61Status failed");
    goto TheEnd;
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s P61 Status is: %x", __func__, p61_current_state);

  if (((nfcFL.eseFL._NXP_ESE_VER == JCOP_VER_3_1) &&
          (!(p61_current_state & P61_STATE_SPI) &&
           !(p61_current_state & P61_STATE_SPI_PRIO))) ||
      (nfcFL.eseFL._NXP_ESE_VER != JCOP_VER_3_1)) {
    if (p61_current_state & (P61_STATE_SPI) ||
        (p61_current_state & (P61_STATE_SPI_PRIO))) {
      dual_mode_current_state |= SPI_ON;
    }
    if (p61_current_state & (P61_STATE_SPI_PRIO)) {
      hold_the_transceive = true;
    }

    secElemHandle = NFC_ReqWiredAccess((void*)&status);
    if (secElemHandle < 0) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Denying SE open due to NFC_ReqWiredAccess failed");
      goto TheEnd;
    } else {
      if (status != NFCSTATUS_SUCCESS) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("Denying SE open due to SE is being used by SPI");
        secElemHandle = EE_ERROR_IO;
        goto TheEnd;
      } else {
        if (nfcFL.eseFL._ESE_DUAL_MODE_PRIO_SCHEME ==
            nfcFL.eseFL._ESE_UICC_EXCLUSIVE_WIRED_MODE) {
          if (isDiscoveryStarted()) {
            // Stop RF Discovery if we were polling
            startRfDiscovery(false);
            status = NFA_DisableListening();
            if (status == NFCSTATUS_OK) {
              startRfDiscovery(true);
            }
          } else {
            status = NFA_DisableListening();
          }
          se.mlistenDisabled = true;
        }
      }
    }
  } else {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("Denying SE open because SPI is already open");
    goto TheEnd;
  }
#endif
  /* Tell the controller to power up to get ready for sec elem operations */
  PowerSwitch::getInstance().setLevel(PowerSwitch::FULL_POWER);
  PowerSwitch::getInstance().setModeOn(PowerSwitch::SE_CONNECTED);
/* If controller is not routing AND there is no pipe connected,
       then turn on the sec elem */
#if (NXP_EXTNS == TRUE)
  if (nfcFL.nfcNxpEse) {
    if (nfcFL.eseFL._ESE_FORCE_ENABLE &&
        (!(p61_current_state & (P61_STATE_SPI | P61_STATE_SPI_PRIO))) &&
        (!(dual_mode_current_state & CL_ACTIVE)))
      stat = se.SecEle_Modeset(0x01);  // Workaround
    usleep(150000); /*provide enough delay if NFCC enter in recovery*/
  }
#endif
  stat = se.activate(
      SecureElement::ESE_ID);  // It is to get the current activated handle.

  if (stat) {
    // establish a pipe to sec elem
    stat = se.connectEE();
    if (stat) {
      secElemHandle = se.mActiveEeHandle;
    } else {
      se.deactivate(0);
    }
  }
#if (NXP_EXTNS == TRUE)
  if (nfcFL.nfcNxpEse && stat) {
    status = NFA_STATUS_OK;
    if (nfcFL.eseFL._NFCC_ESE_UICC_CONCURRENT_ACCESS_PROTECTION) {
      if (!(se.isActivatedInListenMode() || isp2pActivated() ||
            NfcTag::getInstance().isActivated())) {
        se.enablePassiveListen(0x00);
      }
      se.meseUiccConcurrentAccess = true;
    }
    /*Do not send PowerLink and ModeSet If SPI is already open*/
    if ((nfcFL.eseFL._WIRED_MODE_STANDBY && (se.mNfccPowerMode == 1)) &&
        !(p61_current_state & (P61_STATE_SPI | P61_STATE_SPI_PRIO))) {
      status = se.setNfccPwrConfig(se.POWER_ALWAYS_ON | se.COMM_LINK_ACTIVE);
      if (status != NFA_STATUS_OK) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("%s: power link command failed", __func__);
      } else {
        se.SecEle_Modeset(0x01);
      }
    }
#endif

    if ((status == NFA_STATUS_OK) && (se.mIsIntfRstEnabled)) {
      gateInfo = se.getApduGateInfo();
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: GateInfo %d", __func__, gateInfo);
      if (gateInfo == ETSI_12_APDU_GATE) {
        se.NfccStandByOperation(STANDBY_TIMER_STOP);
        status = se.SecElem_sendEvt_Abort();
        if (status != NFA_STATUS_OK) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: EVT_ABORT failed", __func__);
          se.sendEvent(SecureElement::EVT_END_OF_APDU_TRANSFER);
        }
      }
    }
    if (status == NFA_STATUS_OK) {
      bool ret = false;
      ret = se.checkPipeStatusAndRecreate();
      if (!ret) status = NFCSTATUS_FAILED;
    }
    if (status != NFA_STATUS_OK) {
      if (nfcFL.eseFL._WIRED_MODE_STANDBY && (se.mNfccPowerMode == 1) &&
          !(p61_current_state & (P61_STATE_SPI | P61_STATE_SPI_PRIO))) {
        se.setNfccPwrConfig(se.NFCC_DECIDES);
      }
      se.disconnectEE(secElemHandle);
      secElemHandle = EE_ERROR_INIT;

      ret_val = NFC_RelWiredAccess((void*)&status);
      if (ret_val < 0)
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "Denying SE release due to NFC_RelWiredAccess failure");
      else if (status != NFCSTATUS_SUCCESS)
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "Denying SE close, since SE is not released by PN54xx driver");
    } else {
      se.mIsWiredModeOpen = true;
    }
  }
  // if code fails to connect to the secure element, and nothing is active, then
  // tell the controller to power down
  if ((!stat) &&
      (!PowerSwitch::getInstance().setModeOff(PowerSwitch::SE_CONNECTED))) {
    PowerSwitch::getInstance().setLevel(PowerSwitch::LOW_POWER);
  }
#if (NXP_EXTNS == TRUE)
  if (isLowRamDevice()) {
    se.NfccStandByOperation(STANDBY_ESE_PWR_ACQUIRE);
  }
#endif

TheEnd:
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: exit; return handle=0x%X", __func__, secElemHandle);
  android::mSPIDwpSyncMutex.unlock();
  return secElemHandle;
}  // namespace android

/*******************************************************************************
**
** Function:        nativeNfcSecureElement_doDisconnectSecureElementConnection
**
** Description:     Disconnect from the secure element.
**                  e: JVM environment.
**                  o: Java object.
**                  handle: Handle of secure element.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nativeNfcSecureElement_doDisconnectSecureElementConnection(
    JNIEnv*, jobject, jint handle) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter; handle=0x%04x", __func__, handle);
  bool stat = false;
  long ret_val = -1;
  p61_access_state_t p61_current_state = P61_STATE_INVALID;
  android::mSPIDwpSyncMutex.lock();
  ret_val = NFC_GetP61Status((void*)&p61_current_state);
  if (ret_val < 0) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("NFC_GetP61Status failed");
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s P61 Status is: %x", __func__, p61_current_state);
  if (p61_current_state & (P61_STATE_SPI) ||
      (p61_current_state & (P61_STATE_SPI_PRIO))) {
    dual_mode_current_state |= SPI_ON;
  }
  if ((p61_current_state & (P61_STATE_WIRED)) &&
      (p61_current_state & (P61_STATE_SPI | P61_STATE_SPI_PRIO))) {
    dual_mode_current_state |= SPI_DWPCL_BOTH_ACTIVE;
  }

#if (NXP_EXTNS == TRUE)
  NFCSTATUS status = NFCSTATUS_FAILED;

  SecureElement& se = SecureElement::getInstance();
  se.NfccStandByOperation(STANDBY_TIMER_STOP);
  if (isLowRamDevice()) {
    se.NfccStandByOperation(STANDBY_ESE_PWR_RELEASE);
  }
#endif

#if (NXP_EXTNS == TRUE)
  if (nfcFL.nfcNxpEse) {
    if (handle == (SecureElement::EE_HANDLE_0xF8 || se.EE_HANDLE_0xF4)) {
      stat = SecureElement::getInstance().disconnectEE(handle);
      se.mIsWiredModeOpen = false;
      if (nfcFL.eseFL._ESE_EXCLUSIVE_WIRED_MODE) {
        se.mIsExclusiveWiredMode = false;
        if (se.mlistenDisabled) {
          if (isDiscoveryStarted()) {
          // Stop RF Discovery if we were polling
            startRfDiscovery(false);
            status = NFA_EnableListening();
            startRfDiscovery(true);
          } else {
             status = NFA_EnableListening();
          }
          se.mlistenDisabled = false;
        }
      }
      goto TheEnd;
    }

    // Send the EVT_END_OF_APDU_TRANSFER event at the end of wired mode
    // session.
    se.NfccStandByOperation(STANDBY_MODE_ON);
  }
#endif

  stat = SecureElement::getInstance().disconnectEE(handle);

  /* if nothing is active after this, then tell the controller to power down */
  if (!PowerSwitch::getInstance().setModeOff(PowerSwitch::SE_CONNECTED))
    PowerSwitch::getInstance().setLevel(PowerSwitch::LOW_POWER);
#if (NXP_EXTNS == TRUE)
  if (nfcFL.nfcNxpEse) {
    ret_val = NFC_RelWiredAccess((void*)&status);
    if (ret_val < 0) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "Denying SE Release due to NFC_RelWiredAccess failed");
      goto TheEnd;
    } else {
      if (status != NFCSTATUS_SUCCESS) {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
            "Denying SE close due to SE is not being released by Pn54x driver");
        stat = false;
      }
      if (nfcFL.eseFL._NFCC_ESE_UICC_CONCURRENT_ACCESS_PROTECTION) {
        se.enablePassiveListen(0x01);
        SecureElement::getInstance().mPassiveListenTimer.kill();
        se.meseUiccConcurrentAccess = false;
      }
      se.mIsWiredModeOpen = false;
      if ((nfcFL.eseFL._ESE_DUAL_MODE_PRIO_SCHEME ==
           nfcFL.eseFL._ESE_UICC_EXCLUSIVE_WIRED_MODE) &&
          se.mlistenDisabled) {
        if (isDiscoveryStarted()) {
          // Stop RF Discovery if we were polling
          startRfDiscovery(false);
          status = NFA_EnableListening();
          startRfDiscovery(true);
        } else {
          status = NFA_EnableListening();
        }
        se.mlistenDisabled = false;
      }
    }
  }
#endif
TheEnd:
#if (NXP_EXTNS == TRUE)
  if (nfcFL.nfcNxpEse) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit stat = %d", __func__, stat);
  } else {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  }
#else
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
#endif
  android::mSPIDwpSyncMutex.unlock();
  return stat ? JNI_TRUE : JNI_FALSE;
}
#if (NXP_EXTNS == TRUE)
static int checkP61Status(void) {
  if (!nfcFL.nfcNxpEse) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s : nfcNxpEse not available. Returning", __func__);
    return -1;
  }
  jint ret_val = -1;
  p61_access_state_t p61_current_state = P61_STATE_INVALID;
  ret_val = NFC_GetP61Status((void*)&p61_current_state);
  if (ret_val < 0) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("NFC_GetP61Status failed");
    return -1;
  }
  if (p61_current_state & (P61_STATE_SPI) ||
      (p61_current_state & (P61_STATE_SPI_PRIO))) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("No gpio change");
    ret_val = 0;
  } else {
    ret_val = -1;
  }
  return ret_val;
}
#endif
/*******************************************************************************
**
** Function:        nativeNfcSecureElement_doResetSecureElement
**
** Description:     Reset the secure element.
**                  e: JVM environment.
**                  o: Java object.
**                  handle: Handle of secure element.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nativeNfcSecureElement_doResetSecureElement(JNIEnv*, jobject,
                                                            jint handle) {
  bool stat = false;
  if (nfcFL.nfcNxpEse) {
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    SecureElement& se = SecureElement::getInstance();

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: enter; handle=0x%04x", __func__, handle);
    if (!se.mIsWiredModeOpen) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("wired mode is not open");
      return stat;
    }
    if (nfcFL.eseFL._ESE_DWP_SPI_SYNC_ENABLE &&
        nfcFL.eseFL._WIRED_MODE_STANDBY && !checkP61Status()) {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Reset is not allowed while SPI ON");
      return stat;
    }
    if (nfcFL.eseFL._WIRED_MODE_STANDBY && (se.mNfccPowerMode == 1)) {
      nfaStat = se.setNfccPwrConfig(se.NFCC_DECIDES);
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s Power Mode is Legacy", __func__);
    }
    {
      stat = se.SecEle_Modeset(0x00);
      if (handle == SecureElement::EE_HANDLE_0xF3) {
        if (checkP61Status()) se.NfccStandByOperation(STANDBY_GPIO_LOW);
      }
      usleep(100 * 1000);
      if (handle == SecureElement::EE_HANDLE_0xF3) {
        if (checkP61Status() && (se.mIsWiredModeOpen == true))
          se.NfccStandByOperation(STANDBY_GPIO_HIGH);
      }

      if (nfcFL.eseFL._WIRED_MODE_STANDBY && (se.mNfccPowerMode == 1)) {
        uint8_t status = se.setNfccPwrConfig(se.POWER_ALWAYS_ON | se.COMM_LINK_ACTIVE);
        if (status != NFA_STATUS_OK) {
          DLOG_IF(INFO, nfc_debug_enabled)
              << StringPrintf("%s: power link command failed", __func__);
        }
      }
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s Power Mode is Legacy", __func__);
    }
    usleep(2000 * 1000);
    stat = se.SecEle_Modeset(0x01);
  }
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return stat ? JNI_TRUE : JNI_FALSE;
}

/*******************************************************************************
 **
 ** Function:        nativeNfcSecureElement_doeSEChipResetSecureElement
 **
 ** Description:     Reset the secure element.
 **                  e: JVM environment.
 **                  o: Java object.
 **                  handle: Handle of secure element.
 **
 ** Returns:         True if ok.
 **
 *******************************************************************************/
static jboolean nativeNfcSecureElement_doeSEChipResetSecureElement(JNIEnv*,
                                                                   jobject) {
  bool stat = false;
  NFCSTATUS status = NFCSTATUS_FAILED;
  unsigned long num = 0x01;
#if (NXP_EXTNS == TRUE)
  SecureElement& se = SecureElement::getInstance();
  if (nfcFL.nfcNxpEse) {
    if (NfcConfig::hasKey("NXP_ESE_POWER_DH_CONTROL")) {
      num = NfcConfig::getUnsigned("NXP_ESE_POWER_DH_CONTROL");
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("Power schemes enabled in config file is %ld", num);
    }
    if (num == 0x02) {
      status = se.eSE_Chip_Reset();
      if (status == NFCSTATUS_SUCCESS) {
        stat = true;
      }
    }
  }
#endif
  return stat ? JNI_TRUE : JNI_FALSE;
}

/*******************************************************************************
**
** Function:        nativeNfcSecureElement_doGetAtr
**
** Description:     GetAtr from the connected eSE.
**                  e: JVM environment.
**                  o: Java object.
**                  handle: Handle of secure element.
**
** Returns:         Buffer of received data.
**
*******************************************************************************/
static jbyteArray nativeNfcSecureElement_doGetAtr(JNIEnv* e, jobject,
                                                  jint handle) {
  bool stat = false;
  const int32_t recvBufferMaxSize = 1024;
  uint8_t recvBuffer[recvBufferMaxSize];
  int32_t recvBufferActualSize = 0;
  if (nfcFL.nfcNxpEse) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: enter; handle=0x%04x", __func__, handle);

    stat = SecureElement::getInstance().getAtr(handle, recvBuffer,
                                               &recvBufferActualSize);

    // copy results back to java
  }
  jbyteArray result = e->NewByteArray(recvBufferActualSize);
  if (result != NULL) {
    e->SetByteArrayRegion(result, 0, recvBufferActualSize, (jbyte*)recvBuffer);
  }

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: exit: recv len=%d", __func__, recvBufferActualSize);

  return result;
}

/*******************************************************************************
**
** Function:        nativeNfcSecureElement_doTransceive
**
** Description:     Send data to the secure element; retrieve response.
**                  e: JVM environment.
**                  o: Java object.
**                  handle: Secure element's handle.
**                  data: Data to send.
**
** Returns:         Buffer of received data.
**
*******************************************************************************/
static jbyteArray nativeNfcSecureElement_doTransceive(JNIEnv* e, jobject,
                                                      jint handle,
                                                      jbyteArray data) {
  const int32_t recvBufferMaxSize = 0x8800;  // 1024; 34k
  uint8_t recvBuffer[recvBufferMaxSize];
  int32_t recvBufferActualSize = 0;
  eTransceiveStatus tranStatus = TRANSCEIVE_STATUS_FAILED;

  ScopedByteArrayRW bytes(e, data);
#if (NXP_EXTNS == TRUE)
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s: enter; handle=0x%X; buf len=%zu", __func__, handle, bytes.size());
  tranStatus = SecureElement::getInstance().transceive(
      reinterpret_cast<uint8_t*>(&bytes[0]), bytes.size(), recvBuffer,
      recvBufferMaxSize, recvBufferActualSize, WIRED_MODE_TRANSCEIVE_TIMEOUT);
  if (tranStatus == TRANSCEIVE_STATUS_MAX_WTX_REACHED) {
    LOG(ERROR) << StringPrintf("%s: Wired Mode Max WTX count reached",
                               __FUNCTION__);
    jbyteArray result = e->NewByteArray(0);
    nativeNfcSecureElement_doResetSecureElement(e, NULL, handle);
    return result;
  }

  // copy results back to java
  jbyteArray result = e->NewByteArray(recvBufferActualSize);
  if (result != NULL) {
    e->SetByteArrayRegion(result, 0, recvBufferActualSize, (jbyte*)recvBuffer);
  }
  if (nfcFL.nfcNxpEse &&
      nfcFL.eseFL._NFCC_ESE_UICC_CONCURRENT_ACCESS_PROTECTION &&
      (SecureElement::getInstance().mIsWiredModeBlocked == true)) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("APDU Transceive CE wait");
    SecureElement::getInstance().startThread(0x01);
  }
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: exit: recv len=%d", __func__, recvBufferActualSize);
  return result;
#else
  jbyteArray result = e->NewByteArray(0);
  return result;
#endif
}

/*****************************************************************************
**
** Description:     JNI functions
**
*****************************************************************************/
static JNINativeMethod gMethods[] = {
#if (NXP_EXTNS == TRUE)
    {"doNativeOpenSecureElementConnection", "(I)I",
     (void*)nativeNfcSecureElement_doOpenSecureElementConnection},
#else
    {"doNativeOpenSecureElementConnection", "()I",
     (void*)nativeNfcSecureElement_doOpenSecureElementConnection},
#endif
    {"doNativeDisconnectSecureElementConnection", "(I)Z",
     (void*)nativeNfcSecureElement_doDisconnectSecureElementConnection},
    {"doNativeResetSecureElement", "(I)Z",
     (void*)nativeNfcSecureElement_doResetSecureElement},
    {"doNativeeSEChipResetSecureElement", "()Z",
     (void*)nativeNfcSecureElement_doeSEChipResetSecureElement},
    {"doTransceive", "(I[B)[B", (void*)nativeNfcSecureElement_doTransceive},
    {"doNativeGetAtr", "(I)[B", (void*)nativeNfcSecureElement_doGetAtr},
};

/*******************************************************************************
**
** Function:        register_com_android_nfc_NativeNfcSecureElement
**
** Description:     Regisgter JNI functions with Java Virtual Machine.
**                  e: Environment of JVM.
**
** Returns:         Status of registration.
**
*******************************************************************************/
int register_com_android_nfc_NativeNfcSecureElement(JNIEnv* e) {
  return jniRegisterNativeMethods(e, gNativeNfcSecureElementClassName, gMethods,
                                  NELEM(gMethods));
}

}  // namespace android
