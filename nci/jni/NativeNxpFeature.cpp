/******************************************************************************
 *
 *  Copyright 2015-2024 NXP
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
#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <errno.h>
#include <semaphore.h>

#include "JavaClassConstants.h"
#include "NfcAdaptation.h"
#include "NfcJniUtil.h"
#include "RoutingManager.h"
#include "SyncEvent.h"
#include "config.h"
#include "nfa_api.h"
#include "nfa_rw_api.h"
#include "nfc_config.h"

#if (NXP_EXTNS == TRUE)
using android::base::StringPrintf;

typedef enum {
  INVALID_LOG_LEVEL = -1,
  LOG_TRACE_DISABLED,
  LOG_TRACE_ENABLED,
} logTraceLevel_t;

typedef struct nxp_feature_data {
  SyncEvent NxpFeatureConfigEvt;
  Mutex mMutex;
  tNFA_STATUS wstatus;
  uint8_t rsp_data[255];
  uint8_t rsp_len;
  uint8_t prev_trace_level = INVALID_LOG_LEVEL;
} Nxp_Feature_Data_t;

typedef enum {
  NCI_OID_SYSTEM_DEBUG_STATE_L1_MESSAGE = 0x35,
  NCI_OID_SYSTEM_DEBUG_STATE_L2_MESSAGE,
  NCI_OID_SYSTEM_DEBUG_STATE_L3_MESSAGE,
} eNciSystemPropOpcodeIdentifier_t;

namespace android {
extern nfc_jni_native_data* getNative(JNIEnv* e, jobject o);
static Nxp_Feature_Data_t gnxpfeature_conf;
void SetCbStatus(tNFA_STATUS status);
tNFA_STATUS GetCbStatus(void);
static void NxpResponse_Cb(uint8_t event, uint16_t param_len, uint8_t* p_param);
}  // namespace android

namespace android {
extern bool suppressLogs;
void SetCbStatus(tNFA_STATUS status) { gnxpfeature_conf.wstatus = status; }

tNFA_STATUS GetCbStatus(void) { return gnxpfeature_conf.wstatus; }

static void NxpResponse_Cb(uint8_t event, uint16_t param_len,
                           uint8_t* p_param) {
  (void)event;
  LOG(DEBUG) << StringPrintf(
      "NxpResponse_Cb Received length data = 0x%x status = 0x%x", param_len,
      p_param[3]);
  if (p_param != NULL) {
    if (p_param[3] == 0x00) {
      SetCbStatus(NFA_STATUS_OK);
    } else {
      SetCbStatus(NFA_STATUS_FAILED);
    }
    gnxpfeature_conf.rsp_len = (uint8_t)param_len;
    if (param_len > 0) {
      memcpy(gnxpfeature_conf.rsp_data, p_param, param_len);
    }
    SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
    gnxpfeature_conf.NxpFeatureConfigEvt.notifyOne();
  }
}


/*******************************************************************************
 **
 ** Function:        NxpNfc_Write_Cmd()
 **
 ** Description:     Writes the command to NFCC
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS NxpNfc_Write_Cmd_Common(uint8_t retlen, uint8_t* buffer) {
  tNFA_STATUS status = NFA_STATUS_FAILED;
  SetCbStatus(NFA_STATUS_FAILED);
  SyncEventGuard guard(gnxpfeature_conf.NxpFeatureConfigEvt);
  status = NFA_SendRawVsCommand(retlen, buffer, NxpResponse_Cb);
  if (status == NFA_STATUS_OK) {
    LOG(DEBUG) << StringPrintf("%s: Success NFA_SendRawVsCommand", __func__);
    gnxpfeature_conf.NxpFeatureConfigEvt.wait(); /* wait for callback */
  } else {
    LOG(ERROR) << StringPrintf("%s: Failed NFA_SendRawVsCommand", __func__);
  }
  status = GetCbStatus();
  return status;
}
/*******************************************************************************
 **
 ** Function:        getNumValue()
 **
 ** Description:     get the value from th config file.
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
uint32_t getNumValue(const char* key ,uint32_t value) {
  return NfcConfig::getUnsigned(key, value);
}

/*******************************************************************************
 **
 ** Function:        send_flush_ram_to_flash
 **
 ** Description:     This is used to update ram to flash command to NFCC.
 **                  This will write the contents of RAM to FLASH.This will
 **                  be sent only one time after NFC init.
 **
 ** Returns:         NFA_STATUS_OK on success
 **www
 *******************************************************************************/
tNFA_STATUS send_flush_ram_to_flash() {
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  tNFA_STATUS status = NFA_STATUS_OK;
  const uint8_t FW_ROM_VERSION_PN557 = 0x12;
  tNFC_FW_VERSION fw_version = nfc_ncif_getFWVersion();
  // Flash Sync command not applicable for PN557 , disable command only for
  // PN557
  if (fw_version.rom_code_version == FW_ROM_VERSION_PN557) {
    LOG(DEBUG) << StringPrintf("%s: Skipping Flash sync cmd for PN557 chipset",
                               __func__);
    return status;
  }
  uint8_t cmd[] = {0x2F, 0x21, 0x00};

  status = NxpNfc_Write_Cmd_Common(sizeof(cmd), cmd);
  if(status != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: send_flush_ram_to_flash sending status %x",
                               __func__, status);
  }
  return status;
}
/*******************************************************************************
 **
 ** Function:        enableDisableLog(bool type)
 **
 ** Description:     This function is used to enable/disable the
 **                  logging module for cmd/data exchanges.
 **
 ** Returns:         None
 **
 *******************************************************************************/
void enableDisableLog(bool type) {
  bool nfc_debug_enabled = NfcConfig::getUnsigned(NAME_NFC_DEBUG_ENABLED, 1);
  if (gnxpfeature_conf.prev_trace_level == (uint8_t)INVALID_LOG_LEVEL)
    gnxpfeature_conf.prev_trace_level = (uint8_t)nfc_debug_enabled;
  NfcAdaptation& theInstance = NfcAdaptation::GetInstance();
  if (android::suppressLogs) {
    if ((uint8_t)type != gnxpfeature_conf.prev_trace_level) {
      if (true == type) {
          nfc_debug_enabled = (uint8_t)LOG_TRACE_ENABLED;
          theInstance.HalSetProperty("nfc.debug_enabled", "1");
      } else if (false == type) {
        if ((uint8_t)LOG_TRACE_DISABLED != nfc_debug_enabled) {
          nfc_debug_enabled = (uint8_t)LOG_TRACE_DISABLED;
          theInstance.HalSetProperty("nfc.debug_enabled", "0");
        }
      }
      gnxpfeature_conf.prev_trace_level = nfc_debug_enabled;
    }
  }
}

/*******************************************************************************
**
** Function:        nfaVSCNtfCallback
**
** Description:     Receives LxDebug events from stack.
**                  Event: for which the callback is invoked
**                  param_len: Len of the Parameters passed
**                  p_param: Pointer to the event param
**
** Returns:         None
**
*******************************************************************************/
void nfaVSCNtfCallback(uint8_t event, uint16_t param_len, uint8_t *p_param) {
  (void)event;
  LOG(DEBUG) << StringPrintf("%s: event = 0x%02X", __func__, event);
  uint8_t op_code = (event & ~NCI_NTF_BIT);
  uint32_t len;
  uint8_t nciHdrLen = 3;

  if(!p_param || param_len <= nciHdrLen) {
    LOG(ERROR) << "Invalid Params. returning...";
    return;
  }

  switch(op_code) {
    case NCI_OID_SYSTEM_DEBUG_STATE_L1_MESSAGE:
    break;

    case NCI_OID_SYSTEM_DEBUG_STATE_L2_MESSAGE:
      len = param_len - nciHdrLen;
    {
      struct nfc_jni_native_data* mNativeData = getNative(NULL, NULL);
      JNIEnv* e = NULL;
      ScopedAttach attach(mNativeData->vm, &e);
      if (e == NULL) {
        LOG(ERROR) << "jni env is null";
        return;
      }

      jbyteArray retArray = e->NewByteArray(len);

      if((uint32_t)e->GetArrayLength(retArray) != len)
      {
        e->DeleteLocalRef(retArray);
        retArray = e->NewByteArray(len);
      }
      e->SetByteArrayRegion(retArray, 0, len, (jbyte*)(p_param + nciHdrLen));

      e->CallVoidMethod(mNativeData->manager,
                      android::gCachedNfcManagerNotifyLxDebugInfo,
                      (int)len, retArray);
      if (e->ExceptionCheck()) {
        e->ExceptionClear();
        LOG(ERROR) << "fail notify";
      }
    }
    break;

    case NCI_OID_SYSTEM_DEBUG_STATE_L3_MESSAGE:
    break;

    default:
    LOG(DEBUG) << StringPrintf("%s: unknown event ????", __func__);
    break;
  }
  LOG(DEBUG) << StringPrintf("%s: Exit", __func__);
}


} /*namespace android*/

#endif
