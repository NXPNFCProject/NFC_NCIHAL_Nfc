/*
 * Copyright (C) 2017 NXP Semiconductors
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
#include "JcopManager.h"
#include <dlfcn.h>

using android::base::StringPrintf;

extern bool nfc_debug_enabled;

JcopManager* JcopManager::mJcpMgr = NULL;
static phJcop_Context_t g_mPhJcpCtxt;
static phJcop_Context_t* pg_mPhJcpCtxt = &g_mPhJcpCtxt;
static void* Pgpx_Jcop_handle = NULL;
#define DL_STATUS_OK 0x00

JcopManager::JcopManager() {}

JcopManager::~JcopManager() {}

/*******************************************************************************
**
** Function:        getInstance
**
** Description:     Get the JcopManager singleton object.
**
** Returns:         JcopManager object.
**
*******************************************************************************/
JcopManager* JcopManager::getInstance() {
  if (mJcpMgr == NULL) {
    mJcpMgr = new JcopManager();
  }
  return mJcpMgr;
}

/*******************************************************************************
**
** Function:        deleteInstance
**
** Description:     Delete the JcopManager singleton object.
**
*******************************************************************************/
void JcopManager::deleteInstance() {
  if (mJcpMgr != NULL) {
    delete mJcpMgr;
    mJcpMgr = NULL;
  }
}

/*******************************************************************************
**
** Function:        JcopInitialize
**
** Description:     Load the library and initialize the symbols and variables.
**
** Returns:         STATUS_OK if Success.
**                  STATUS_FAILED if unSuccessfull
**
*******************************************************************************/
tNFA_STATUS JcopManager::JcopInitialize() {
  tNFA_STATUS wStatus = NFA_STATUS_OK;
  // Getting pointer to JCOP module
  Pgpx_Jcop_handle = dlopen("system/lib64/libp61-jcop-kit.so", RTLD_NOW);
  if (Pgpx_Jcop_handle == NULL) {
    LOG(ERROR) << StringPrintf(
        "%s: Error : opening (system/lib64/libp61-jcop-kit.so) !!", __func__);
    return NFA_STATUS_FAILED;
  }
  // Getting pointer to ALA_Init function
  if ((pg_mPhJcpCtxt->ala_init =
           (tJCOP_INIT_CBACK*)dlsym(Pgpx_Jcop_handle, "ALA_Init")) == NULL) {
    LOG(ERROR) << StringPrintf(
        "%s: Error while linking JCOP context (ALA_Init) !!", __func__);
    return NFA_STATUS_FAILED;
  }
  // Getting pointer to ALA_Start function
  if ((pg_mPhJcpCtxt->ala_start =
           (tALA_START_CBACK*)dlsym(Pgpx_Jcop_handle, "ALA_Start")) == NULL) {
    wStatus = NFA_STATUS_FAILED;
    LOG(ERROR) << StringPrintf(
        "%s: Error while linking JCOP context (ALA_Start) !!", __func__);
  }

#if (NXP_LDR_SVC_VER_2 == FALSE)
  // Getting pointer to ALA_GetlistofApplets function
  if ((pg_mPhJcpCtxt->ala_applets_list = (tALA_APPLET_LIST_CBACK*)dlsym(
           Pgpx_Jcop_handle, "ALA_GetlistofApplets")) == NULL) {
    wStatus = NFA_STATUS_FAILED;
    LOG(ERROR) << StringPrintf(
        "%s: Error while linking JCOP context (ALA_GetlistofApplets) !!",
        __func__);
  }
  // Getting pointer to ALA_GetCertificateKey function
  if ((pg_mPhJcpCtxt->ala_get_certkey = (tALA_GET_CERTKEY_CBACK*)dlsym(
           Pgpx_Jcop_handle, "ALA_GetCertificateKey")) == NULL) {
    wStatus = NFA_STATUS_FAILED;
    LOG(ERROR) << StringPrintf(
        "%s: Error while linking JCOP context (ALA_GetCertificateKey) !!",
        __func__);
  }
#endif

  // Getting pointer to ALA_DeInit function
  if ((pg_mPhJcpCtxt->ala_deinit = (tJCOP_DEINIT_CBACK*)dlsym(
           Pgpx_Jcop_handle, "ALA_DeInit")) == NULL) {
    wStatus = NFA_STATUS_FAILED;
    LOG(ERROR) << StringPrintf(
        "%s: Error while linking JCOP context (ALA_DeInit) !!", __func__);
  }
  // Getting pointer to ALA_lsGetVersion function
  if ((pg_mPhJcpCtxt->ala_lsgetversion = (tALA_LS_CBACK*)dlsym(
           Pgpx_Jcop_handle, "ALA_lsGetVersion")) == NULL) {
    wStatus = NFA_STATUS_FAILED;
    LOG(ERROR) << StringPrintf(
        "%s: Error while linking JCOP context (ALA_lsGetVersion) !!", __func__);
  }
  // Getting pointer to ALA_lsGetStatus function
  if ((pg_mPhJcpCtxt->ala_lsgetstatus = (tALA_LS_CBACK*)dlsym(
           Pgpx_Jcop_handle, "ALA_lsGetStatus")) == NULL) {
    wStatus = NFA_STATUS_FAILED;
    LOG(ERROR) << StringPrintf(
        "%s: Error while linking JCOP context (ALA_lsGetStatus) !!", __func__);
  }
  // Getting pointer to ALA_lsGetAppletStatus function
  if ((pg_mPhJcpCtxt->ala_lsgetappletstatus = (tALA_LS_CBACK*)dlsym(
           Pgpx_Jcop_handle, "ALA_lsGetAppletStatus")) == NULL) {
    wStatus = NFA_STATUS_FAILED;
    LOG(ERROR) << StringPrintf(
        "%s: Error while linking JCOP context (ALA_lsGetAppletStatus) !!",
        __func__);
  }
  // Getting pointer to JCDNLD_Init function
  if ((pg_mPhJcpCtxt->jcop_init =
           (tJCOP_INIT_CBACK*)dlsym(Pgpx_Jcop_handle, "JCDNLD_Init")) == NULL) {
    wStatus = NFA_STATUS_FAILED;
    LOG(ERROR) << StringPrintf(
        "%s: Error while linking JCOP context (JCDNLD_Init) !!", __func__);
  }
  // Getting pointer to JCDNLD_StartDownload function
  if ((pg_mPhJcpCtxt->jcdnld_startdnld = (tJCDNLD_DWLD_CBACK*)dlsym(
           Pgpx_Jcop_handle, "JCDNLD_StartDownload")) == NULL) {
    wStatus = NFA_STATUS_FAILED;
    LOG(ERROR) << StringPrintf(
        "%s: Error while linking JCOP context (JCDNLD_StartDownload) !!",
        __func__);
  }
  // Getting pointer to JCDNLD_DeInit function
  if ((pg_mPhJcpCtxt->jcdnld_deinit = (tJCOP_DEINIT_CBACK*)dlsym(
           Pgpx_Jcop_handle, "JCDNLD_DeInit")) == NULL) {
    wStatus = NFA_STATUS_FAILED;
    LOG(ERROR) << StringPrintf(
        "%s: Error while linking JCOP context (JCDNLD_DeInit) !!", __func__);
  }
  return wStatus;
}

/*******************************************************************************
**
** Function:        JcopDeInitialize
**
** Description:     De-initialize the library
**
** Returns:         STATUS_OK if Success.
**                  STATUS_FAILED if unSuccessfull
**
*******************************************************************************/
tNFA_STATUS JcopManager::JcopDeInitialize() {
  if (Pgpx_Jcop_handle != NULL) {
    if ((dlclose(Pgpx_Jcop_handle)) != DL_STATUS_OK) {
      LOG(ERROR) << StringPrintf(
          "%s: Error : closing (system/lib64/libp61-jcop-kit.so) !!", __func__);
      return NFA_STATUS_FAILED;
    }
  } else {
    LOG(ERROR) << StringPrintf("%s: Invalid handle !!", __func__);
  }
  return NFA_STATUS_OK;
}

/*******************************************************************************
**
** Function:        AlaInitialize
**
** Description:     Initializes the ALA library and opens the DWP communication
*channel
**
** Returns:         STATUS_OK if Success.
**                  STATUS_FAILED if unSuccessfull
**
*******************************************************************************/
tNFC_JBL_STATUS JcopManager::AlaInitialize(IChannel_t* channel) {
  if (channel == NULL) {
    LOG(ERROR) << StringPrintf("%s: Invalid handle !!", __func__);
    return NFA_STATUS_FAILED;
  }
  return pg_mPhJcpCtxt->ala_init(channel);
}

/*******************************************************************************
**
** Function:        AlaDeInitialize
**
** Description:     Deinitializes the ALA Lib
**
** Returns:         True if ok.
**
*******************************************************************************/
bool JcopManager::AlaDeInitialize() { return pg_mPhJcpCtxt->ala_deinit(); }

/*******************************************************************************
**
** Function:        AlaStart
**
** Description:     Starts the ALA over DWP
**
** Returns:         STATUS_OK if Success.
**                  STATUS_FAILED if unSuccessfull
**
*******************************************************************************/
#if (NXP_LDR_SVC_VER_2 == TRUE)
tNFC_JBL_STATUS JcopManager::AlaStart(const char* name, const char* dest,
                                      uint8_t* pdata, uint16_t len,
                                      uint8_t* respSW) {
  if (!name || !dest || !pdata || !respSW) {
    LOG(ERROR) << StringPrintf("%s: Invalid Params !!", __func__);
    return NFA_STATUS_FAILED;
  }
  return pg_mPhJcpCtxt->ala_start(name, dest, pdata, len, respSW);
}
#else
tNFC_JBL_STATUS JcopManager::AlaStart(const char* name, uint8_t* pdata,
                                      uint16_t len) {
  if (!name || !pdata) {
    LOG(ERROR) << StringPrintf("%s: Invalid Params !!", __func__);
    return NFA_STATUS_FAILED;
  }
  return pg_mPhJcpCtxt->ala_start(name, dest, pdata, len, respSW);
}
#endif

#if (NXP_LDR_SVC_VER_2 == FALSE)
/*******************************************************************************
**
** Function:        AlaGetlistofApplets
**
** Description:     list all the applets.
**
*******************************************************************************/
void JcopManager::AlaGetlistofApplets(char* list[], uint8_t* num) {
  if (!list || !num) {
    LOG(ERROR) << StringPrintf("%s: Invalid Params !!", __func__);
    return;
  }
  pg_mPhJcpCtxt->ala_applets_list(list, num);
}

/*******************************************************************************
**
** Function:        AlaGetCertificateKey
**
** Description:     Get the certification key
**
** Returns:         True if ok.
**
*******************************************************************************/
tNFC_JBL_STATUS JcopManager::AlaGetCertificateKey(uint8_t* pKey,
                                                  int32_t* pKeylen) {
  if (!pKey || !pKeylen) {
    LOG(ERROR) << StringPrintf("%s: Invalid Params !!", __func__);
    return NFA_STATUS_FAILED;
  }
  return pg_mPhJcpCtxt->ala_get_certkey(pKey, pKeylen);
}
#endif

/*******************************************************************************
**
** Function:        AlaLsGetVersion
**
** Description:     Get the version of the loader version
**
** Returns:         STATUS_OK if Success.
**                  STATUS_FAILED if unSuccessfull
**
*******************************************************************************/
tNFC_JBL_STATUS JcopManager::AlaLsGetVersion(uint8_t* pVersion) {
  if (!pVersion) {
    LOG(ERROR) << StringPrintf("%s: Invalid Params !!", __func__);
    return NFA_STATUS_FAILED;
  }
  return pg_mPhJcpCtxt->ala_lsgetversion(pVersion);
}

/*******************************************************************************
**
** Function:        AlaLsGetAppletStatus
**
** Description:     Get the status of the loader service version
**
** Returns:         STATUS_OK if Success.
**                  STATUS_FAILED if unSuccessfull
**
*******************************************************************************/
tNFC_JBL_STATUS JcopManager::AlaLsGetAppletStatus(uint8_t* pVersion) {
  if (!pVersion) {
    LOG(ERROR) << StringPrintf("%s: Invalid Params !!", __func__);
    return NFA_STATUS_FAILED;
  }
  return pg_mPhJcpCtxt->ala_lsgetappletstatus(pVersion);
}

/*******************************************************************************
**
** Function:        AlaLsGetAppletStatus
**
** Description:     Get the status of the loader service
**
** Returns:         STATUS_OK if Success.
**                  STATUS_FAILED if unSuccessfull
**
*******************************************************************************/
tNFC_JBL_STATUS JcopManager::AlaLsGetStatus(uint8_t* pVersion) {
  if (!pVersion) {
    LOG(ERROR) << StringPrintf("%s: Invalid Params !!", __func__);
    return NFA_STATUS_FAILED;
  }
  return pg_mPhJcpCtxt->ala_lsgetstatus(pVersion);
}

/*******************************************************************************
**
** Function:        JCDnldInit
**
** Description:     Initializes the JCOP library and opens the DWP communication
*channel
**
** Returns:         STATUS_OK if Success.
**                  STATUS_FAILED if unSuccessfull
**
*******************************************************************************/
tNFC_JBL_STATUS JcopManager::JCDnldInit(IChannel_t* channel) {
  if (!channel) {
    LOG(ERROR) << StringPrintf("%s: Invalid Params !!", __func__);
    return NFA_STATUS_FAILED;
  }
  return pg_mPhJcpCtxt->jcop_init(channel);
}

/*******************************************************************************
**
** Function:        JCDnldStartDownload
**
** Description:     Starts the JCOP update
**
** Returns:         STATUS_OK if Success.
**                  STATUS_FAILED if unSuccessfull
**
*******************************************************************************/
tNFC_JBL_STATUS JcopManager::JCDnldStartDownload() {
  return pg_mPhJcpCtxt->jcdnld_startdnld();
}

/*******************************************************************************
**
** Function:        JCDnldDeInit
**
** Description:     De-initializes the JCOP Library
**
** Returns:         True if ok.
**
*******************************************************************************/
bool JcopManager::JCDnldDeInit() { return pg_mPhJcpCtxt->jcdnld_deinit(); }
