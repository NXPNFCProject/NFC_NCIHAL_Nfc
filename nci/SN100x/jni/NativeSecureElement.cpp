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

#include <android-base/stringprintf.h>
#include <base/logging.h>
#include "PowerSwitch.h"
#include "RoutingManager.h"
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <nativehelper/ScopedLocalRef.h>
#include "JavaClassConstants.h"
#include "NfcJniUtil.h"
#include "config.h"
#include "SecureElement.h"
#include "NfcAdaptation.h"
#include "NativeJniExtns.h"
#include "nfc_config.h"

using android::base::StringPrintf;

namespace android
{
#define INVALID_LEN_SW1 0x64
#define INVALID_LEN_SW2 0xFF

#define ESE_RESET_PROTECTION_ENABLE  0x14
#define ESE_RESET_PROTECTION_DISABLE  0x15
#define NFA_ESE_HARD_RESET  0x13
static const int EE_ERROR_INIT = -3;
static void NxpNfc_ParsePlatformID(const uint8_t*);
extern bool nfcManager_isNfcActive();
extern bool nfcManager_isNfcDisabling();
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
static jint nativeNfcSecureElement_doOpenSecureElementConnection (JNIEnv*, jobject)
{
    LOG(INFO) << StringPrintf("%s: Enter; ", __func__);
    if (nfcManager_isNfcDisabling()) {
      LOG(INFO) << StringPrintf(
          "%s: Nfc is Disabling. Can not open SE connection. Line: %d",
          __func__, __LINE__);
      return EE_ERROR_INIT;
    }
    bool stat = false;
    const int32_t recvBufferMaxSize = 1024;
    uint8_t recvBuffer [recvBufferMaxSize];
    int32_t recvBufferActualSize = 0;

    jint secElemHandle = EE_ERROR_INIT;
    NFCSTATUS status = NFCSTATUS_FAILED;
    SecureElement &se = SecureElement::getInstance();
    se.mModeSetNtfstatus = NFA_STATUS_FAILED;

    NativeJniExtns::getInstance().notifyNfcEvent(__func__);
    /* Tell the controller to power up to get ready for sec elem operations */
    PowerSwitch::getInstance ().setLevel (PowerSwitch::FULL_POWER);
    PowerSwitch::getInstance ().setModeOn (PowerSwitch::SE_CONNECTED);

    /* If controller is not routing AND there is no pipe connected,
           then turn on the sec elem */
    stat = se.activate(SecureElement::ESE_ID); // It is to get the current activated handle.

    if((stat) && (nfcFL.eseFL._NCI_NFCEE_PWR_LINK_CMD))
    {
       status = se.setNfccPwrConfig(se.POWER_ALWAYS_ON|se.COMM_LINK_ACTIVE);
    }
    if(status != NFA_STATUS_OK)
    {
      LOG(INFO) << StringPrintf("%s: power link command failed", __func__);
      stat =false;
    }
    else
    {
       stat = se.SecEle_Modeset(se.NFCEE_ENABLE);
       if(se.mModeSetNtfstatus != NFA_STATUS_OK)
       {
         stat = false;
         LOG(INFO) << StringPrintf("%s: Mode set ntf STATUS_FAILED", __func__);
         if (nfcManager_isNfcDisabling()) {
           LOG(INFO) << StringPrintf(
               "%s: Nfc is Disabling. Can not open SE connection. Line : %d ",
               __func__, __LINE__);
           return EE_ERROR_INIT;
         }
         SyncEventGuard guard (se.mEERecoveryComplete);
         {
           se.mEERecoveryComplete.wait();
           LOG(INFO) << StringPrintf("%s: Recovery complete", __func__);
         }
         if(se.mErrorRecovery)
         {
           stat = true;
         }
       }
       if(stat == true)
       {
         se.mIsWiredModeOpen = true;
         stat = se.apduGateReset(se.mActiveEeHandle, recvBuffer, &recvBufferActualSize);
        if (stat)
        {
            secElemHandle = se.mActiveEeHandle;
        }
       }
    }

    /* if code fails to connect to the secure element, and nothing is active, then
     * tell the controller to power down
     */
    if ((!stat) && (! PowerSwitch::getInstance ().setModeOff (PowerSwitch::SE_CONNECTED)))
    {
        LOG(INFO) << StringPrintf("%s: stat fails; ", __func__);
        PowerSwitch::getInstance ().setLevel (PowerSwitch::LOW_POWER);
        se.deactivate(SecureElement::ESE_ID);
        se.mIsWiredModeOpen = false;
        stat = false;
    }
    LOG(INFO) << StringPrintf("%s: exit; return handle=0x%X", __func__, secElemHandle);
    return secElemHandle;
}


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
static jboolean nativeNfcSecureElement_doDisconnectSecureElementConnection (JNIEnv*, jobject, jint handle)
{
    LOG(INFO) << StringPrintf("%s: enter; handle=0x%04x", __func__, handle);
    bool stat = false;
    NFCSTATUS status = NFCSTATUS_FAILED;
    SecureElement &se = SecureElement::getInstance();

    if(!se.mIsWiredModeOpen)
         return false;

    se.mIsWiredModeOpen = false;
     /* release any pending transceive wait */
    se.releasePendingTransceive();

    status = se.setNfccPwrConfig(se.POWER_ALWAYS_ON);
    if(status != NFA_STATUS_OK)
    {
        LOG(INFO) << StringPrintf("%s: power link command failed", __func__);
    }
    else
    {
        status = se.sendEvent(SecureElement::EVT_END_OF_APDU_TRANSFER);
        if(status == NFA_STATUS_OK)
            stat = true;
    }
    /* if nothing is active after this, then tell the controller to power down */
    if (! PowerSwitch::getInstance ().setModeOff (PowerSwitch::SE_CONNECTED))
        PowerSwitch::getInstance ().setLevel (PowerSwitch::LOW_POWER);
    LOG(INFO) << StringPrintf("%s: exit", __func__);
    return stat ? JNI_TRUE : JNI_FALSE;
}
/*******************************************************************************
**
** Function:        nativeNfcSecureElement_doResetForEseCosUpdate
**
** Description:     Reset the secure element.
**                  e: JVM environment.
**                  o: Java object.
**                  handle: Handle of secure element.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nativeNfcSecureElement_doResetForEseCosUpdate(JNIEnv*, jobject,
                                                              jint handle) {
  bool stat = false;
  int ret = -1;
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  tHAL_NFC_ENTRY* halFuncEntries = theInstance.GetHalEntryFuncs ();

  LOG(INFO) << StringPrintf("%s: Entry", __func__);
  if(NULL == halFuncEntries) {
    LOG(INFO) << StringPrintf("%s: halFuncEntries is NULL", __func__);
  } else {
    if(handle == ESE_RESET_PROTECTION_ENABLE ||
        handle == ESE_RESET_PROTECTION_DISABLE)
      ret = theInstance.resetEse((uint64_t)handle);
    else
      ret = theInstance.resetEse((uint64_t)NFA_ESE_HARD_RESET);
    if(ret == 0) {
      LOG(INFO) << StringPrintf("%s: reset IOCTL failed", __func__);
    } else {
      stat = true;
    }
  }
  LOG(INFO) << StringPrintf("%s: exit", __func__);
  return stat;
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
static jbyteArray nativeNfcSecureElement_doGetAtr (JNIEnv* e, jobject, jint handle)
{
    bool stat = false;
    const int32_t recvBufferMaxSize = 1024;
    uint8_t recvBuffer [recvBufferMaxSize];
    int32_t recvBufferActualSize = 0;
    LOG(INFO) << StringPrintf("%s: enter; handle=0x%04x", __func__, handle);
    SecureElement &se = SecureElement::getInstance();

    stat = se.getAtr(recvBuffer, &recvBufferActualSize);

    //copy results back to java
    jbyteArray result = e->NewByteArray(recvBufferActualSize);
    if (result != NULL) {
        e->SetByteArrayRegion(result, 0, recvBufferActualSize, (jbyte *) recvBuffer);
    }

    LOG(INFO) << StringPrintf("%s: exit: Status = 0x%X: recv len=%d", __func__,
                              stat, recvBufferActualSize);

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
static jbyteArray nativeNfcSecureElement_doTransceive (JNIEnv* e, jobject, jint handle, jbyteArray data)
{
    const int32_t recvBufferMaxSize = 0x800B;//32k(8000) datasize + 10b Protocol Header Size + 1b support neg testcase
    std::unique_ptr<uint8_t> recvBuffer(new uint8_t[recvBufferMaxSize]);;
    int32_t recvBufferActualSize = 0;
    ScopedByteArrayRW bytes(e, data);
    LOG(INFO) << StringPrintf("%s: enter; handle=0x%X; buf len=%zu", __func__, handle, bytes.size());
    if(bytes.size() > recvBufferMaxSize) {
        LOG(ERROR) << StringPrintf("%s: datasize not supported", __func__);
        uint8_t respBuf[] = {INVALID_LEN_SW1, INVALID_LEN_SW2};
        jbyteArray resp = e->NewByteArray(sizeof(respBuf));
        if (resp != NULL) {
          e->SetByteArrayRegion(resp, 0, sizeof(respBuf), (jbyte *) respBuf);
        }
        return resp;
    }

    SecureElement &se = SecureElement::getInstance();
    if(!se.mIsWiredModeOpen)
        return NULL;

    se.transceive(reinterpret_cast<uint8_t*>(&bytes[0]), bytes.size(), recvBuffer.get(), recvBufferMaxSize, recvBufferActualSize, se.SmbTransceiveTimeOutVal);

    //copy results back to java
    jbyteArray result = e->NewByteArray(recvBufferActualSize);
    if (result != NULL)
    {
        e->SetByteArrayRegion(result, 0, recvBufferActualSize, (jbyte *) recvBuffer.get());
    }

    LOG(INFO) << StringPrintf("%s: exit: recv len=%d", __func__, recvBufferActualSize);
    return result;
}
/*******************************************************************************
**
** Function:        nfcManager_doactivateSeInterface
**
** Description:     Activate SecureElement Interface
**
** Returns:         Success/Failure
**                  Success = 0x00
**                  Failure = 0x03
**
*******************************************************************************/
static jint nfcManager_doactivateSeInterface(JNIEnv* e, jobject o) {
  jint ret = NFA_STATUS_FAILED;
  tNFA_STATUS status = NFA_STATUS_FAILED;
  SecureElement& se = SecureElement::getInstance();
  se.mModeSetNtfstatus = NFA_STATUS_FAILED;

  LOG(INFO) << StringPrintf("%s: enter", __func__);

  if (!nfcManager_isNfcActive() || (!nfcFL.eseFL._NCI_NFCEE_PWR_LINK_CMD)) {
    LOG(INFO) << StringPrintf("%s: Not supported", __func__);
    return ret;
  }
  if (se.mIsSeIntfActivated) {
    LOG(INFO) << StringPrintf("%s: Already activated", __func__);
    return NFA_STATUS_OK;
  }
  status = se.setNfccPwrConfig(se.POWER_ALWAYS_ON | se.COMM_LINK_ACTIVE);
  if (status == NFA_STATUS_OK) {
    status = se.SecEle_Modeset(se.NFCEE_ENABLE);
    if (se.mModeSetNtfstatus != NFA_STATUS_OK) {
      LOG(INFO) << StringPrintf("%s: Mode set ntf STATUS_FAILED", __func__);
      SyncEventGuard guard(se.mEERecoveryComplete);
      if (se.mEERecoveryComplete.wait(NFC_CMD_TIMEOUT)) {
        LOG(INFO) << StringPrintf("%s: Recovery complete", __func__);
        ret = NFA_STATUS_OK;
      }
    } else {
      ret = NFA_STATUS_OK;
    }
  } else {
    LOG(INFO) << StringPrintf("%s: power link command failed", __func__);
  }

  if (ret == NFA_STATUS_OK) se.mIsSeIntfActivated = true;
  LOG(INFO) << StringPrintf("%s: Exit", __func__);
  return ret;
}
/*******************************************************************************
**
** Function:        nfcManager_dodeactivateSeInterface
**
** Description:     Deactivate SecureElement Interface
**
** Returns:         Success/Failure
**                  Success = 0x00
**                  Failure = 0x03
**
*******************************************************************************/
jint nfcManager_dodeactivateSeInterface(JNIEnv* e, jobject o) {
  jint ret = NFA_STATUS_FAILED;
  tNFA_STATUS status = NFA_STATUS_FAILED;
  SecureElement& se = SecureElement::getInstance();
  LOG(INFO) << StringPrintf("%s: enter", __func__);

  if (!nfcManager_isNfcActive() || (!nfcFL.eseFL._NCI_NFCEE_PWR_LINK_CMD)) {
    LOG(INFO) << StringPrintf("%s: Not supported", __func__);
    return ret;
  }
  if (!se.mIsSeIntfActivated) {
    LOG(INFO) << StringPrintf("%s: Already Deactivated or call activate first",
                              __func__);
    return NFA_STATUS_OK;
  }

  status = se.setNfccPwrConfig(se.POWER_ALWAYS_ON);
  if (status == NFA_STATUS_OK) {
    LOG(INFO) << StringPrintf("%s: power link command success", __func__);
    se.mIsSeIntfActivated = false;
    ret = NFA_STATUS_OK;
  }
  LOG(INFO) << StringPrintf("%s: Exit, status =0x02%u", __func__, status);
  return ret;
}
/*****************************************************************************
**
** Description:     JNI functions
**
*****************************************************************************/
static JNINativeMethod gMethods[] = {
    {"doNativeOpenSecureElementConnection", "()I",
     (void*)nativeNfcSecureElement_doOpenSecureElementConnection},
    {"doNativeDisconnectSecureElementConnection", "(I)Z",
     (void*)nativeNfcSecureElement_doDisconnectSecureElementConnection},
    {"doResetForEseCosUpdate", "(I)Z",
     (void*)nativeNfcSecureElement_doResetForEseCosUpdate},
    {"doTransceive", "(I[B)[B", (void*)nativeNfcSecureElement_doTransceive},
    {"doNativeGetAtr", "(I)[B", (void*)nativeNfcSecureElement_doGetAtr},
    {"doactivateSeInterface", "()I", (void*)nfcManager_doactivateSeInterface},
    {"dodeactivateSeInterface", "()I",
     (void*)nfcManager_dodeactivateSeInterface},
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
int register_com_android_nfc_NativeNfcSecureElement(JNIEnv *e)
{
    return jniRegisterNativeMethods(e, gNativeNfcSecureElementClassName,
            gMethods, NELEM(gMethods));
}
/*******************************************************************************
**
** Function:        NxpNfc_GetHwInfo
**
** Description:     Read the JCOP platform Identifier data
**
** Returns:         None
**
*******************************************************************************/
void NxpNfc_GetHwInfo() {
  LOG(INFO) << StringPrintf("%s: Enter; ", __func__);
  if(!NfcConfig::getUnsigned(NAME_NXP_GET_HW_INFO_LOG, 0))
    return;
  const int32_t recvBufferMaxSize = 1024;
  uint8_t recvBuffer[recvBufferMaxSize];
  int32_t recvBufferActualSize = 0;
  bool stat = false;
  NFCSTATUS status = NFCSTATUS_FAILED;
  jint secElemHandle = EE_ERROR_INIT;
  uint8_t CmdBuffer[] = {0x80, 0xCA, 0x00, 0xFE, 0x02, 0xDF, 0x20};
  const uint8_t SUCCESS_SW1 = 0x90;
  const uint8_t SUCCESS_SW2 = 0x00;
  SecureElement& se = SecureElement::getInstance();

  /* open SecureElement connection */
  secElemHandle =
      nativeNfcSecureElement_doOpenSecureElementConnection(NULL, NULL);
  if (secElemHandle != EE_ERROR_INIT) {
    /* Transmit command */
    status = se.transceive(CmdBuffer, sizeof(CmdBuffer), recvBuffer,
                           recvBufferMaxSize, recvBufferActualSize,
                           se.SmbTransceiveTimeOutVal);

    if (status) {
      if ((recvBufferActualSize > 2) &&
          (recvBuffer[recvBufferActualSize - 2] == SUCCESS_SW1) &&
          (recvBuffer[recvBufferActualSize - 1] == SUCCESS_SW2)) {
        /* null termination */
        recvBuffer[recvBufferActualSize - 2] = '\0';
        NxpNfc_ParsePlatformID(recvBuffer);
      } else {
        LOG(ERROR) << StringPrintf(
            "%s: Get Platform Identifier command fail; exit; ", __func__);
      }
    } else {
      LOG(ERROR) << StringPrintf("%s: trannsceive fail; exit; ", __func__);
    }
  }

  stat = nativeNfcSecureElement_doDisconnectSecureElementConnection(
      NULL, NULL, secElemHandle);
  if (stat) {
    LOG(INFO) << StringPrintf("%s: exit", __func__);
  } else {
    LOG(INFO) << StringPrintf("%s: Disconnect SecureElement fail", __func__);
  }
}

/*******************************************************************************
**
** Function:        NxpNfc_ParsePlatformID
**
** Description:     Parse the PlatformID data to map the hardware.
**
** Returns:         None
**
*******************************************************************************/
static void NxpNfc_ParsePlatformID(const uint8_t* data) {
  const uint8_t PLATFORMID_OFFSET = 5;
  const uint8_t PBYTES_SIZE = 3;
  const uint8_t MAX_STRING_SIZE = 25;
  const uint8_t PlatfType[][PBYTES_SIZE] = {{0x4A, 0x35, 0x55},
                                            {0x4E, 0x35, 0x43}};
  uint8_t PlatfStrings[][MAX_STRING_SIZE] = {
      "NFCC HW is SN100", "NFCC HW is SN110", "Not SN1xx NFCC"};

  size_t count = 0;

#define MAX_PLATFTYPE_ELEMENTS (sizeof(PlatfType) / sizeof(PlatfType[0]))
#define MAX_STRINGS (sizeof(PlatfStrings) / sizeof(PlatfStrings[0]))
#define DATA_SIZE (sizeof(PlatfType[0]))

  LOG(INFO) << StringPrintf("PlatformId: %s", &data[PLATFORMID_OFFSET]);
  for (; count < MAX_PLATFTYPE_ELEMENTS; count++) {
    if (!memcmp(&PlatfType[count][0], &data[PLATFORMID_OFFSET], DATA_SIZE)) {
      LOG(INFO) << StringPrintf("PlatformId: %s", PlatfStrings[count]);
      break;
    }
  }

  if (count == MAX_PLATFTYPE_ELEMENTS) {
    LOG(INFO) << StringPrintf("PlatformId: %s", PlatfStrings[count]);
  }
#undef MAX_PLATFTYPE_ELEMENTS
#undef MAX_STRINGS
#undef DATA_SIZE
}

} // namespace android
