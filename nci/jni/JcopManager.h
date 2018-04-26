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

#pragma once

#include "nfa_api.h"

typedef struct IChannel {
  int16_t (*open)();              /* Initialize the channel. */
  bool (*close)(int16_t mHandle); /* Close the channel. */
  bool (*transceive)(
      uint8_t* xmitBuffer, int32_t xmitBufferSize, uint8_t* recvBuffer,
      int32_t recvBufferMaxSize, int32_t& recvBufferActualSize,
      int32_t timeoutMillisec); /* Send/receive data to the secure element*/
  void (*doeSE_Reset)();        /* Power OFF and ON to eSE */
  void (*doeSE_JcopDownLoadReset)(); /* Power OFF and ON to eSE during JCOP
                                        Update */
} IChannel_t;

#define pJcopMgr (JcopManager::getInstance())
typedef unsigned char(tJCOP_INIT_CBACK)(IChannel_t* channel);
#if (NXP_LDR_SVC_VER_2 == TRUE)
typedef unsigned char(tALA_START_CBACK)(const char* name, const char* dest,
                                        uint8_t* pdata, uint16_t len,
                                        uint8_t* respSW);
#else
typedef unsigned char(tALA_START_CBACK)(const char* name, uint8_t* pdata,
                                        uint16_t len);
typedef unsigned char(tALA_GET_CERTKEY_CBACK)(uint8_t* pKey, int32_t* pKeylen);
typedef void(tALA_APPLET_LIST_CBACK)(char* list[], uint8_t* num);
#endif
typedef unsigned char(tALA_LS_CBACK)(uint8_t* pVersion);
typedef unsigned char(tJCDNLD_DWLD_CBACK)();
typedef bool(tJCOP_DEINIT_CBACK)();

typedef struct phJcop_Context {
  /* Call backs */
  tJCOP_INIT_CBACK* jcop_init;
  tJCOP_INIT_CBACK* ala_init;
  tALA_START_CBACK* ala_start;
#if (NXP_LDR_SVC_VER_2 == FALSE)
  tALA_APPLET_LIST_CBACK* ala_applets_list;
  tALA_GET_CERTKEY_CBACK* ala_get_certkey;
#endif
  tALA_LS_CBACK* ala_lsgetversion;
  tALA_LS_CBACK* ala_lsgetstatus;
  tALA_LS_CBACK* ala_lsgetappletstatus;
  tJCDNLD_DWLD_CBACK* jcdnld_startdnld;
  tJCOP_DEINIT_CBACK* ala_deinit;
  tJCOP_DEINIT_CBACK* jcdnld_deinit;
} phJcop_Context_t;
typedef unsigned char tNFC_JBL_STATUS;

class JcopManager {
 private:
  static JcopManager* mJcpMgr;
  /*******************************************************************************
  **
  ** Function:        JcopManager
  **
  ** Description:     Initialize member variables.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  JcopManager();

  /*******************************************************************************
  **
  ** Function:        ~JcopManager
  **
  ** Description:     Release all resources.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  ~JcopManager();

 public:
  /*******************************************************************************
  **
  ** Function:        getInstance
  **
  ** Description:     Get the JcopManager singleton object.
  **
  ** Returns:         JcopManager object.
  **
  *******************************************************************************/
  static JcopManager* getInstance();

  /*******************************************************************************
  **
  ** Function:        deleteInstance
  **
  ** Description:     Delete the JcopManager singleton object.
  **
  *******************************************************************************/
  void deleteInstance();

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
  tNFA_STATUS JcopInitialize();

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
  tNFA_STATUS JcopDeInitialize();

  /*******************************************************************************
  **
  ** Function:        AlaInitialize
  **
  ** Description:     Initializes the ALA library and opens the DWP
  *communication channel
  **
  ** Returns:         STATUS_OK if Success.
  **                  STATUS_FAILED if unSuccessfull
  **
  *******************************************************************************/
  tNFC_JBL_STATUS AlaInitialize(IChannel_t* channel);

  /*******************************************************************************
  **
  ** Function:        AlaDeInitialize
  **
  ** Description:     Deinitializes the ALA Lib
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  bool AlaDeInitialize();

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
  tNFC_JBL_STATUS AlaStart(const char* name, const char* dest, uint8_t* pdata,
                           uint16_t len, uint8_t* respSW);
#else
  tNFC_JBL_STATUS AlaStart(const char* name, uint8_t* pdata, uint16_t len);
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
  tNFC_JBL_STATUS AlaLsGetVersion(uint8_t* pVersion);

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
  tNFC_JBL_STATUS AlaLsGetAppletStatus(uint8_t* pVersion);

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
  tNFC_JBL_STATUS AlaLsGetStatus(uint8_t* pVersion);

#if (NXP_LDR_SVC_VER_2 == FALSE)
  /*******************************************************************************
  **
  ** Function:        AlaGetlistofApplets
  **
  ** Description:     list all the applets.
  **
  *******************************************************************************/

  void AlaGetlistofApplets(char* list[], uint8_t* num);

  /*******************************************************************************
  **
  ** Function:        AlaGetCertificateKey
  **
  ** Description:     Get the certification key
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  tNFC_JBL_STATUS AlaGetCertificateKey(uint8_t* pKey, int32_t* pKeylen);
#endif

  /*******************************************************************************
  **
  ** Function:        JCDnldInit
  **
  ** Description:     Initializes the JCOP library and opens the DWP
  *communication channel
  **
  ** Returns:         STATUS_OK if Success.
  **                  STATUS_FAILED if unSuccessfull
  **
  *******************************************************************************/
  tNFC_JBL_STATUS JCDnldInit(IChannel_t* channel);

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
  tNFC_JBL_STATUS JCDnldStartDownload();

  /*******************************************************************************
  **
  ** Function:        JCDnldDeInit
  **
  ** Description:     De-initializes the JCOP Library
  **
  ** Returns:         True if ok.
  **
  *******************************************************************************/
  bool JCDnldDeInit();
};
