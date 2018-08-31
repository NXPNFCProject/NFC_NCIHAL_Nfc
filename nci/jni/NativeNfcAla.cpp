/*
 * Copyright (C) 2015-2018 NXP Semiconductors
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
#include <ScopedPrimitiveArray.h>
#include "DwpChannel.h"
#include "JavaClassConstants.h"
#include "JcopManager.h"
#include "NfcJniUtil.h"
#include "PowerSwitch.h"
#include "SecureElement.h"
#include "TransactionController.h"
#include "phNxpConfig.h"
#include "nfc_config.h"

using android::base::StringPrintf;

extern bool nfc_debug_enabled;
#define LS_DEFAULT_VERSION 0x20
namespace android {
extern SyncEvent sNfaVSCResponseEvent;
extern void startRfDiscovery(bool isStart);
extern bool isDiscoveryStarted();
extern void nfaVSCCallback(uint8_t event, uint16_t param_len, uint8_t* p_param);
extern bool isLowRamDevice();
}  // namespace android

namespace android {

static bool sRfEnabled;
jbyteArray nfcManager_lsExecuteScript(JNIEnv* e, jobject o, jstring src,
                                      jstring dest, jbyteArray);
jbyteArray nfcManager_lsGetVersion(JNIEnv* e, jobject o);
int nfcManager_doAppletLoadApplet(JNIEnv* e, jobject o, jstring choice,
                                  jbyteArray);
int nfcManager_getLoaderServiceConfVersion(JNIEnv* e, jobject o);
int nfcManager_GetAppletsList(JNIEnv* e, jobject o, jobjectArray list);
jbyteArray nfcManager_GetCertificateKey(JNIEnv* e, jobject);
jbyteArray nfcManager_lsGetStatus(JNIEnv* e, jobject);
jbyteArray nfcManager_lsGetAppletStatus(JNIEnv* e, jobject);
extern void DWPChannel_init(IChannel_t* DWP);
extern IChannel_t Dwp;

/*******************************************************************************
**
** Function:        nfcManager_GetListofApplets
**
** Description:     provides the list of applets present in the directory.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         None.
**
*******************************************************************************/
int nfcManager_GetAppletsList(JNIEnv* e, jobject o, jobjectArray list) {
  (void)e;
  (void)o;
  (void)list;
  uint8_t xx = 0;
#if (NXP_LDR_SVC_VER_2 == FALSE)
  if (nfcFL.nfcNxpEse) {
    char* name[10];
    uint8_t num = 0;
    uint8_t list_len = e->GetArrayLength(list);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

    sRfEnabled = isDiscoveryStarted();
    if (sRfEnabled) {
      // Stop RF Discovery if we were polling
      startRfDiscovery(false);
    }
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: list_len=0x%x", __func__, list_len);
    pJcopMgr->AlaGetlistofApplets(name, &num);

    if ((num != 0) && (list_len >= num)) {
      while (num > 0) {
        jstring tmp = e->NewStringUTF(name[xx]);
        e->SetObjectArrayElement(list, xx, tmp);
        if (name[xx] != NULL) {
          free(name[xx]);
        }
        xx++;
        num--;
      }
    } else {
      LOG(ERROR) << StringPrintf("%s: No applets found", __func__);
    }
    startRfDiscovery(true);
  } else {
    xx = -1;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: No p61", __func__);
  }
#endif
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: exit; num_applets =0x%X", __func__, xx);
  return xx;
}

/*******************************************************************************
**
** Function:        nfcManager_doAppletLoadApplet
**
** Description:     start jcos download.
**                  e: JVM environment.
**                  o: Java object.
**                  name: path of the ALA script file located
**                  data: SHA encrypted package name
**
** Returns:         True if ok.
**
*******************************************************************************/

int nfcManager_doAppletLoadApplet(JNIEnv* e, jobject o, jstring name,
                                  jbyteArray data) {
  (void)e;
  (void)o;
  (void)name;
  tNFA_STATUS wStatus = NFA_STATUS_FAILED;
#if (NXP_LDR_SVC_VER_2 == FALSE)
  if (nfcFL.nfcNxpEse) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    IChannel_t Dwp;
    bool stat = false;
    const char* choice = NULL;

    sRfEnabled = isDiscoveryStarted();

    if (!pTransactionController->transactionAttempt(
            TRANSACTION_REQUESTOR(AppletLoadApplet),
            TRANSACTION_ATTEMPT_FOR_SECONDS(5))) {
      LOG(ERROR) << StringPrintf(
          "%s ERROR: attempt to start transaction has failed", __FUNCTION__);
      return wStatus;
    }
    if (sRfEnabled) {
      // Stop RF Discovery if we were polling
      startRfDiscovery(false);
    }
    DWPChannel_init(&Dwp);
    wStatus = pJcopMgr->AlaInitialize(&Dwp);
    if (wStatus != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: ALA initialization failed", __func__);
    } else {
      LOG(ERROR) << StringPrintf("%s: start Applet load applet", __func__);
      choice = e->GetStringUTFChars(name, 0);
      LOG(ERROR) << StringPrintf("choice= %s", choice);
      ScopedByteArrayRO bytes(e, data);
      uint8_t* buf =
          const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
      size_t bufLen = bytes.size();
      wStatus = pJcopMgr->AlaStart(choice, buf, bufLen);
    }
    stat = pJcopMgr->AlaDeInitialize();
    if (choice != NULL) e->ReleaseStringUTFChars(name, choice);

    if (dwpChannelForceClose == false) startRfDiscovery(true);

    pTransactionController->transactionEnd(
        TRANSACTION_REQUESTOR(AppletLoadApplet));

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit; wStatus =0x%X", __func__, wStatus);
  } else {
    wStatus = 0x0F;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: No p61", __func__);
  }
#endif
  return wStatus;
}

/*******************************************************************************
**
** Function:        nfcManager_lsExecuteScript
**
** Description:     start jcos download.
**                  e: JVM environment.
**                  o: Java object.
**                  name: path of the ALA script file located
**                  data: SHA encrypted package name
**
** Returns:         True if ok.
**
*******************************************************************************/
jbyteArray nfcManager_lsExecuteScript(JNIEnv* e, jobject o, jstring name,
                                      jstring dest, jbyteArray data) {
  (void)e;
  (void)o;
  (void)name;
  (void)dest;
  const char* destpath = NULL;
  const uint8_t lsExecuteResponseSize = 4;
  uint8_t resSW[4] = {0x4e, 0x02, 0x69, 0x87};
  jbyteArray result = e->NewByteArray(0);
#if (NXP_LDR_SVC_VER_2 == TRUE)
  if (nfcFL.nfcNxpEse) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    tNFA_STATUS wStatus, status;
    IChannel_t Dwp;
    bool stat = false;
    const char* choice = NULL;

    if (isLowRamDevice()) {
      SecureElement& se = SecureElement::getInstance();
      if (se.isBusy() || (!pTransactionController->transactionAttempt(
                             TRANSACTION_REQUESTOR(lsExecuteScript),
                             TRANSACTION_ATTEMPT_FOR_SECONDS(5)))) {
        LOG(ERROR) << StringPrintf(
            " %s: Failed:SE Access Blocked by another process", __func__);
        resSW[2] = 0x6F;
        resSW[3] = 0x89;
        // copy results back to java
        result = e->NewByteArray(lsExecuteResponseSize);
        if (result != NULL) {
          e->SetByteArrayRegion(result, 0, lsExecuteResponseSize,
                                (jbyte*)resSW);
        }
        return result;
      }
    }

    sRfEnabled = isDiscoveryStarted();
    wStatus = status = NFA_STATUS_FAILED;

    if (!isLowRamDevice() && !pTransactionController->transactionAttempt(
                                 TRANSACTION_REQUESTOR(lsExecuteScript),
                                 TRANSACTION_ATTEMPT_FOR_SECONDS(5))) {
      LOG(ERROR) << StringPrintf(
          "%s ERROR: Attempt to start transaction failed", __FUNCTION__);
      return result;
    }
    if (sRfEnabled) {
      // Stop RF Discovery if we were polling
      startRfDiscovery(false);
    }
    DWPChannel_init(&Dwp);
    wStatus = pJcopMgr->AlaInitialize(&Dwp);
    if (wStatus != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: ALA initialization failed", __func__);
    } else {
      // Commented the Disabling standby
      /* uint8_t param[] = {0x00}; //Disable standby
  SyncEventGuard guard (sNfaVSCResponseEvent);
  status = NFA_SendVsCommand (0x00,0x01,param,nfaVSCCallback);
  if(NFA_STATUS_OK == status)
  {
      sNfaVSCResponseEvent.wait(); //wait for NFA VS command to finish
      LOG(ERROR) << StringPrintf("%s: start Applet load applet", __func__);
      choice = e->GetStringUTFChars(name, 0);
      LOG(ERROR) << StringPrintf("choice= %s", choice);
      wStatus = ALA_Start(choice);
  }*/
      destpath = e->GetStringUTFChars(dest, 0);
      LOG(ERROR) << StringPrintf("destpath= %s", destpath);
      LOG(ERROR) << StringPrintf("%s: start Applet load applet", __func__);
      choice = e->GetStringUTFChars(name, 0);
      LOG(ERROR) << StringPrintf("choice= %s", choice);
      ScopedByteArrayRO bytes(e, data);
      uint8_t* buf =
          const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(&bytes[0]));
      size_t bufLen = bytes.size();
      wStatus = pJcopMgr->AlaStart(choice, destpath, buf, bufLen, resSW);

      // copy results back to java
      result = e->NewByteArray(lsExecuteResponseSize);
      if (result != NULL) {
        e->SetByteArrayRegion(result, 0, lsExecuteResponseSize, (jbyte*)resSW);
      }
    }

    // Commented the Enabling standby
    /* uint8_t param[] = {0x01}; //Enable standby
SyncEventGuard guard (sNfaVSCResponseEvent);
status = NFA_SendVsCommand (0x00,0x01,param,nfaVSCCallback);
if(NFA_STATUS_OK == status)
{
    sNfaVSCResponseEvent.wait(); //wait for NFA VS command to finish
}*/

    stat = pJcopMgr->AlaDeInitialize();
    if (choice != NULL) e->ReleaseStringUTFChars(name, choice);

    if (dwpChannelForceClose == false) startRfDiscovery(true);

    pTransactionController->transactionEnd(
        TRANSACTION_REQUESTOR(lsExecuteScript));

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit; status =0x%X", __func__, wStatus);
  } else {
    if (destpath != NULL) e->ReleaseStringUTFChars(dest, destpath);

    result = e->NewByteArray(0);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: No p61", __func__);
  }
#endif
  return result;
}

/*******************************************************************************
**
** Function:        nfcManager_GetCertificateKey
**
** Description:     It is ued to get the reference certificate key
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         key value.
**
*******************************************************************************/

jbyteArray nfcManager_GetCertificateKey(JNIEnv* e, jobject) {
  jbyteArray result = e->NewByteArray(0);
#if (NXP_LDR_SVC_VER_2 == FALSE)
  if (nfcFL.nfcNxpEse) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    tNFA_STATUS wStatus = NFA_STATUS_FAILED;
    IChannel_t Dwp;
    bool stat = false;
    const int32_t recvBufferMaxSize = 256;
    uint8_t recvBuffer[recvBufferMaxSize];
    int32_t recvBufferActualSize = 0;

    sRfEnabled = isDiscoveryStarted();

    if (sRfEnabled) {
      // Stop RF Discovery if we were polling
      startRfDiscovery(false);
    }
    DWPChannel_init(&Dwp);
    wStatus = pJcopMgr->AlaInitialize(&Dwp);
    if (wStatus != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: ALA initialization failed", __func__);
    } else {
      LOG(ERROR) << StringPrintf("%s: start Get reference Certificate Key",
                                 __func__);
      wStatus =
          pJcopMgr->AlaGetCertificateKey(recvBuffer, &recvBufferActualSize);
    }

    // copy results back to java
    result = e->NewByteArray(recvBufferActualSize);
    if (result != NULL) {
      e->SetByteArrayRegion(result, 0, recvBufferActualSize,
                            (jbyte*)recvBuffer);
    }

    stat = pJcopMgr->AlaDeInitialize();
    startRfDiscovery(true);

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: exit: recv len=%ld", __func__, recvBufferActualSize);
  } else {
    result = e->NewByteArray(0);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: No p61", __func__);
  }
#endif
  return result;
}

/*******************************************************************************
**
** Function:        nfcManager_lsGetVersion
**
** Description:     It is ued to get version of Loader service client & Applet
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         version of Loder service.
**
*******************************************************************************/
jbyteArray nfcManager_lsGetVersion(JNIEnv* e, jobject) {
  jbyteArray result;
#if (NXP_LDR_SVC_VER_2 == TRUE)
  if (nfcFL.nfcNxpEse) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    tNFA_STATUS wStatus = NFA_STATUS_FAILED;
    IChannel_t Dwp;
    bool stat = false;
    const int32_t recvBufferMaxSize = 4;
    uint8_t recvBuffer[recvBufferMaxSize];
    result = e->NewByteArray(0);
    sRfEnabled = isDiscoveryStarted();

    if (!pTransactionController->transactionAttempt(
            TRANSACTION_REQUESTOR(lsGetVersion),
            TRANSACTION_ATTEMPT_FOR_SECONDS(5))) {
      LOG(ERROR) << StringPrintf(
          "%s ERRROR: Attempt to start transaction, failed", __FUNCTION__);
      return result;
    }
    if (sRfEnabled) {
      // Stop RF Discovery if we were polling
      startRfDiscovery(false);
    }
    DWPChannel_init(&Dwp);
    wStatus = pJcopMgr->AlaInitialize(&Dwp);
    if (wStatus != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: ALA initialization failed", __func__);
    } else {
      LOG(ERROR) << StringPrintf("%s: start Get reference Certificate Key",
                                 __func__);
      wStatus = pJcopMgr->AlaLsGetVersion(recvBuffer);
    }

    // copy results back to java
    result = e->NewByteArray(recvBufferMaxSize);
    if (result != NULL) {
      e->SetByteArrayRegion(result, 0, recvBufferMaxSize, (jbyte*)recvBuffer);
    }

    stat = pJcopMgr->AlaDeInitialize();
    if (dwpChannelForceClose == false) startRfDiscovery(true);

    pTransactionController->transactionEnd(TRANSACTION_REQUESTOR(lsGetVersion));

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit: recv len=%d", __func__, recvBufferMaxSize);
  } else {
    result = e->NewByteArray(0);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: No p61", __func__);
  }
#endif
  return result;
}

/*******************************************************************************
**
** Function:        nfcManager_lsGetAppletStatus
**
** Description:     It is ued to get LS Applet status SUCCESS/FAIL
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         LS Previous execution Applet status .
**
*******************************************************************************/
jbyteArray nfcManager_lsGetAppletStatus(JNIEnv* e, jobject) {
  jbyteArray result = e->NewByteArray(0);
#if (NXP_LDR_SVC_VER_2 == TRUE)
  if (nfcFL.nfcNxpEse) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    tNFA_STATUS wStatus = NFA_STATUS_FAILED;
    bool stat = false;
    const int32_t recvBufferMaxSize = 2;
    uint8_t recvBuffer[recvBufferMaxSize] = {0x63, 0x40};
    IChannel_t Dwp;
    sRfEnabled = isDiscoveryStarted();

    if (sRfEnabled) {
      // Stop RF Discovery if we were polling
      startRfDiscovery(false);
    }
    DWPChannel_init(&Dwp);
    wStatus = pJcopMgr->AlaInitialize(&Dwp);
    if (wStatus != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: ALA initialization failed", __func__);
    } else {
      LOG(ERROR) << StringPrintf("%s: start Get reference Certificate Key",
                                 __func__);
      wStatus = pJcopMgr->AlaLsGetAppletStatus(recvBuffer);
    }

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: lsGetAppletStatus values %x %x", __func__,
                        recvBuffer[0], recvBuffer[1]);
    // copy results back to java
    result = e->NewByteArray(recvBufferMaxSize);
    if (result != NULL) {
      e->SetByteArrayRegion(result, 0, recvBufferMaxSize, (jbyte*)recvBuffer);
    }
    stat = pJcopMgr->AlaDeInitialize();

    if (dwpChannelForceClose == false) startRfDiscovery(true);

    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit: recv len=%d", __func__, recvBufferMaxSize);
  } else {
    result = e->NewByteArray(0);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: No p61", __func__);
  }
#endif
  return result;
}

/*******************************************************************************
**
** Function:        nfcManager_lsGetStatus
**
** Description:     It is ued to get LS Client status SUCCESS/FAIL
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         version of Loder service.
**
*******************************************************************************/
jbyteArray nfcManager_lsGetStatus(JNIEnv* e, jobject) {
  jbyteArray result = e->NewByteArray(0);
#if (NXP_LDR_SVC_VER_2 == TRUE)
  if (nfcFL.nfcNxpEse) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    tNFA_STATUS wStatus = NFA_STATUS_FAILED;
    const int32_t recvBufferMaxSize = 2;
    uint8_t recvBuffer[recvBufferMaxSize] = {0x63, 0x40};

    wStatus = pJcopMgr->AlaLsGetStatus(recvBuffer);

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: lsGetStatus values %x %x", __func__, recvBuffer[0], recvBuffer[1]);
    // copy results back to java
    result = e->NewByteArray(recvBufferMaxSize);
    if (result != NULL) {
      e->SetByteArrayRegion(result, 0, recvBufferMaxSize, (jbyte*)recvBuffer);
    }
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: exit: recv len=%d", __func__, recvBufferMaxSize);
  } else {
    result = e->NewByteArray(0);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: No p61", __func__);
  }
#endif
  return result;
}

/*******************************************************************************
**
** Function:        nfcManager_getLoaderServiceConfVersion
**
** Description:     Get current Loader service version from config file.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Void.
**
*******************************************************************************/
int nfcManager_getLoaderServiceConfVersion(JNIEnv* e, jobject o) {
  unsigned long num = 0;
  uint8_t ls_version = LS_DEFAULT_VERSION;
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
#if (NXP_LDR_SVC_VER_2 == TRUE)
  if (nfcFL.nfcNxpEse) {
    if (NfcConfig::hasKey(NAME_NXP_LOADER_SERICE_VERSION)) {
      num = NfcConfig::getUnsigned(NAME_NXP_LOADER_SERICE_VERSION);
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("LOADER_SERVICE_VERSION not found");
      num = 0;
    }
    /*If LS version exists in config file*/
    if (num != 0) {
      ls_version = num;
    }
  } else {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: No P61", __func__);
  }
#endif
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
  return ls_version;
}

/*****************************************************************************
 **
 ** Description:     JNI functions
 **
 *****************************************************************************/
static JNINativeMethod gMethods[] = {
    {"doLsExecuteScript", "(Ljava/lang/String;Ljava/lang/String;[B)[B",
     (void*)nfcManager_lsExecuteScript},
    {"doLsGetVersion", "()[B", (void*)nfcManager_lsGetVersion},
    {"doLsGetStatus", "()[B", (void*)nfcManager_lsGetStatus},
    {"doLsGetAppletStatus", "()[B", (void*)nfcManager_lsGetAppletStatus},
    {"doGetLSConfigVersion", "()I",
     (void*)nfcManager_getLoaderServiceConfVersion},

    {"GetAppletsList", "([Ljava/lang/String;)I",
     (void*)nfcManager_GetAppletsList},

    {"doAppletLoadApplet", "(Ljava/lang/String;[B)I",
     (void*)nfcManager_doAppletLoadApplet},

    {"GetCertificateKey", "()[B", (void*)nfcManager_GetCertificateKey},
};

/*******************************************************************************
 **
 ** Function:        register_com_android_nfc_NativeNfcAla
 **
 ** Description:     Regisgter JNI functions with Java Virtual Machine.
 **                  e: Environment of JVM.
 **
 ** Returns:         Status of registration.
 **
 *******************************************************************************/
int register_com_android_nfc_NativeNfcAla(JNIEnv* e) {
  return jniRegisterNativeMethods(e, gNativeNfcAlaClassName, gMethods,
                                  NELEM(gMethods));
}
} /*namespace android*/
