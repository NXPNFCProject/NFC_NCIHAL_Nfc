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
#include "MposManager.h"
#include "JavaClassConstants.h"
#include "_OverrideLog.h"

namespace android
{
typedef enum {
  LOW_POWER = 0x00,
  ULTRA_LOW_POWER,
}POWER_MODE;

static const char* covertToString(POWER_MODE mode);
extern void enableRfDiscovery();
extern void startRfDiscovery(bool isStart);
extern void disableRfDiscovery();
/*******************************************************************************
**
** Function:        nfcManager_dosetEtsiReaederState
**
** Description:     Set ETSI reader state
**                  e: JVM environment.
**                  o: Java object.
**                  newState : new state to be set
**
** Returns:         None.
**
*******************************************************************************/
static void nativeNfcMposManager_doSetEtsiReaederState (JNIEnv*, jobject, se_rd_req_state_t newState)
{
  ALOGV("%s: Enter ", __func__);
  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    MposManager::getInstance().setEtsiReaederState(newState);
  } else {
    ALOGE("%s: ETSI_READER not available. Returning", __func__);
  }
}

/*******************************************************************************
**
** Function:        nfcManager_dogetEtsiReaederState
**
** Description:     Get current ETSI reader state
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         State.
**
*******************************************************************************/
static int nativeNfcMposManager_doGetEtsiReaederState (JNIEnv*, jobject)
{
  ALOGV("%s: Enter ", __func__);
  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    return MposManager::getInstance().getEtsiReaederState();
  } else {
    ALOGE("%s: ETSI_READER not available. Returning", __func__);
    return STATE_SE_RDR_MODE_STOPPED;
  }
}

/*******************************************************************************
**
** Function:        nfcManager_doEtsiReaderConfig
**
** Description:     Configuring to Emvco profile
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         None.
**
*******************************************************************************/
static void nativeNfcMposManager_doEtsiReaderConfig (JNIEnv*, jobject, int eeHandle)
{
  tNFC_STATUS status;
  ALOGV("%s: Enter ", __func__);
  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    status = MposManager::getInstance().etsiReaderConfig(eeHandle);
    if (status != NFA_STATUS_OK) {
      ALOGE("%s: etsiReaderConfig Failed ", __func__);
    } else {
      ALOGV("%s: etsiReaderConfig Success ", __func__);
    }
  } else {
    ALOGE("%s: ETSI_READER not available. Returning", __func__);
  }
}

/*******************************************************************************
**
** Function:        nfcManager_doEtsiResetReaderConfig
**
** Description:     Configuring to Nfc forum profile
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         None.
**
*******************************************************************************/
static void nativeNfcMposManager_doEtsiResetReaderConfig (JNIEnv*, jobject)
{
  tNFC_STATUS status;
  ALOGV("%s: Enter ", __func__);
  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    status = MposManager::getInstance().etsiResetReaderConfig();
    if (status != NFA_STATUS_OK) {
      ALOGV("%s: etsiReaderConfig Failed ", __func__);
    } else {
      ALOGV("%s: etsiReaderConfig Success ", __func__);
    }
  } else {
    ALOGE("%s: ETSI_READER not available. Returning", __func__);
  }
}

/*******************************************************************************
**
** Function:        nfcManager_doNotifyEEReaderEvent
**
** Description:     Notify with the Reader event
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         None.
**
*******************************************************************************/
static void nativeNfcMposManager_doNotifyEEReaderEvent (JNIEnv*, jobject, int evt)
{
  ALOGV("%s: Enter ", __func__);

  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    MposManager::getInstance().notifyEEReaderEvent((etsi_rd_event_t)evt);
  } else {
    ALOGE("%s: ETSI_READER not available. Returning", __func__);
  }
}

/*******************************************************************************
**
** Function:        nfcManager_doEtsiInitConfig
**
** Description:     Chnage the ETSI state before start configuration
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         None.
**
*******************************************************************************/
static void nativeNfcMposManager_doEtsiInitConfig (JNIEnv*, jobject, int evt)
{
  ALOGV("%s: Enter ", __func__);
  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    MposManager::getInstance().etsiInitConfig();
  } else {
    ALOGE("%s: ETSI_READER not available. Returning", __func__);
  }
}

/*******************************************************************************
**
** Function:        nativeNfcMposManage_doMposSetReaderMode
**
** Description:     Set/Reset the MPOS reader mode
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         STATUS_OK/FAILED.
**
*******************************************************************************/
static int nativeNfcMposManage_doMposSetReaderMode(JNIEnv*, jobject, bool on)
{
  tNFA_STATUS status = NFA_STATUS_REJECTED;
  ALOGV("%s:enter", __func__);
  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    status = MposManager::getInstance().setDedicatedReaderMode(on);
  } else {
    ALOGE("%s: ETSI_READER not available. Returning", __func__);
  }
  return status;
}

/*******************************************************************************
**
** Function:        nativeNfcMposManage_doStopPoll
**
** Description:     Enables the specific power mode
**                  e: JVM environment.
**                  o: Java object.
**                  mode: LOW/ULTRA LOW POWER
**
** Returns:         None.
**
*******************************************************************************/
static void nativeNfcMposManage_doStopPoll(JNIEnv*, jobject, int mode)
{
  ALOGV("%s:enter - %s mode", __func__, covertToString((POWER_MODE)mode));
  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    switch (mode) {
    case LOW_POWER:
      disableRfDiscovery();
      break;
    case ULTRA_LOW_POWER:
      startRfDiscovery(false);
      break;
    default:
      break;
    }
  } else {
    ALOGE("%s: ETSI_READER not available. Returning", __func__);
  }
}

/*******************************************************************************
**
** Function:        nativeNfcMposManage_doStartPoll
**
** Description:     Enables the NFC RF discovery
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         None.
**
*******************************************************************************/
static void nativeNfcMposManage_doStartPoll(JNIEnv*, jobject)
{
  ALOGV("%s:enter", __func__);
  if (nfcFL.nfcNxpEse && nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    enableRfDiscovery();
  } else {
    ALOGE("%s: ETSI_READER not available. Returning", __func__);
  }
}

/*******************************************************************************
**
** Function:        covertToString
**
** Description:     Converts power mode type int to string
**
** Returns:         String.
**
*******************************************************************************/
static const char* covertToString(POWER_MODE mode)
{
  switch(mode)
  {
  case LOW_POWER:
    return "LOW_POWER";
  case ULTRA_LOW_POWER:
    return "ULTRA_LOW_POWER";
  default:
    return "INVALID";
  }
}

/*****************************************************************************
**
** Description:     JNI functions
**
*****************************************************************************/
static JNINativeMethod gMethods[] =
{
    {"doSetEtsiReaederState", "(I)V",
        (void *)nativeNfcMposManager_doSetEtsiReaederState},

    {"doGetEtsiReaederState", "()I",
        (void *)nativeNfcMposManager_doGetEtsiReaederState},

    {"doEtsiReaderConfig", "(I)V",
            (void *)nativeNfcMposManager_doEtsiReaderConfig},

    {"doEtsiResetReaderConfig", "()V",
            (void *)nativeNfcMposManager_doEtsiResetReaderConfig},

    {"doNotifyEEReaderEvent", "(I)V",
            (void *)nativeNfcMposManager_doNotifyEEReaderEvent},

    {"doEtsiInitConfig", "()V",
            (void *)nativeNfcMposManager_doEtsiInitConfig},

    {"doMposSetReaderMode", "(Z)I",
            (void *)nativeNfcMposManage_doMposSetReaderMode},

    {"doStopPoll", "(I)V",
            (void *)nativeNfcMposManage_doStopPoll},

    {"doStartPoll", "()V",
            (void *)nativeNfcMposManage_doStartPoll}
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
int register_com_android_nfc_NativeNfcMposManager(JNIEnv *e)
{
  return jniRegisterNativeMethods(e, gNativeNfcMposManagerClassName, gMethods,
      NELEM(gMethods));
}

} // namespace android
