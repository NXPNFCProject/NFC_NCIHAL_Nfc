/******************************************************************************
 *
 *  Copyright 2019 NXP
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
 *
 ******************************************************************************/

#include <stdint.h>
#include <base/logging.h>
#include <android-base/stringprintf.h>
#include "RoutingManager.h"
#include "phNxpConfig.h"
#include <sys/stat.h>

#include "NfcExtnsFeature.h"


#define MAX_GET_ROUTING_BUFFER_SIZE 1024

#define NXP_NFCC_DECIDE_MERGE_SAK (0x00)   /* NFCC decides value of  merge SAK*/
#define NXP_DH_DECIDE_MERGE_SAK   (0x02)   /* DH decides value of merge SAK*/

static const char* nxprfconf = "/libnfc-nxp_RF.conf";
static const char* nxpFW = "/libsn100u_fw.so";
extern uint16_t sCurrentSelectedUICCSlot;
using android::base::StringPrintf;
extern bool nfc_debug_enabled;

enum
{
     ESE_TECH_A = 0x01,
     ESE_TECH_B = 0x02,
     ESE_TECH_F = 0x04,
     ESE_MIFARE = 0x08,
     UICC_TECH_A= 0x10,
     UICC_TECH_B= 0x20,
     UICC_TYPE_F= 0x40,
     UICC_MIFARE= 0x80
};

namespace android {
    extern bool nfcManager_isNfcActive();
    extern tNFA_STATUS getConfig(uint16_t*len , uint8_t* configValue, uint8_t numParam, tNFA_PMID* param);
    extern tNFA_STATUS NxpNfc_Write_Cmd_Common(uint8_t retlen, uint8_t* buffer);
    extern tNFA_STATUS NxpPropCmd_send(uint8_t * pData4Tx, uint8_t dataLen,
                                   uint8_t * rsp_len, uint8_t * rsp_buf,
                                   uint32_t rspTimeout, tHAL_NFC_ENTRY * halMgr);
}
typedef struct
{
    uint8_t bPipeStatus_CeA;
    uint8_t bMode_CeA;
    uint8_t bUidRegSize_CeA;
    uint8_t aUidReg_CeA[10];
    uint8_t bSak_CeA;
    uint8_t aATQA_CeA[2];
    uint8_t bApplicationDataSize_CeA;
    uint8_t aApplicationData_CeA[15];
    uint8_t bFWI_SFGI_CeA;
    uint8_t bCidSupport_CeA;
    uint8_t bCltSupport_CeA;
    uint8_t aDataRateMax_CeA[3];
    uint8_t bPipeStatus_CeB;
    uint8_t bMode_CeB;
    uint8_t aPupiRegDataSize_CeB;
    uint8_t aPupiReg_CeB[4];
    uint8_t bAfi_CeB;
    uint8_t aATQB_CeB[4];
    uint8_t bHighLayerRspSize_CeB;
    uint8_t aHighLayerRsp_CeB_CeB[15];
    uint8_t aDataRateMax_CeB[3];
}hci_parameter_t;

hci_parameter_t hci_parameter_uicc;
hci_parameter_t hci_parameter_eSE;

/*******************************************************************************
 **
 ** Function:        isFilesPresent()
 **
 ** Description:     Checks the file present in specified location or not
 **
 ** Returns:         true/false
 **
 *******************************************************************************/
static bool isFilesPresent(const char* file1, const char* file2)
{
    bool found = false;
    struct stat st;
    if(file1 == NULL || file2 == NULL)
    {
        DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf("isFilesPresent - invalid filepath");
    }
    else
    {
        if(stat(file1, &st) == 0)
        {
            if(stat(file2, &st) == 0)
            {
                found = true;
            }
        }
    }
    return found;
}

/*******************************************************************************
 **
 ** Function:        updateNxpRfConfpath()
 **
 ** Description:     Updates the RF configurations file path for reading
 **                  RF blocks and proprietary configurations
 **
 ** Returns:         void
 **
 *******************************************************************************/
void updateNxpRfConfpath()
{
    long retlen = 0;
    char nxp_flash_loc[120];
    char rffilename[256], fwfilename[256];
    char rffilename2[256], fwfilename2[256];
    char RF_ver_sys[2] = {00, 00};
    char RF_ver_user[2] = {00, 00};

    memset (rffilename, 0, sizeof(rffilename));
    memset (fwfilename, 0, sizeof(fwfilename));
    memset (rffilename2, 0, sizeof(rffilename));
    memset (fwfilename2, 0, sizeof(fwfilename));


     /*Get the file location and name defined in the configuration file*/
    if(GetNxpStrValue(NAME_RF_STORAGE, nxp_flash_loc, sizeof(nxp_flash_loc)))
    {
        strcpy(rffilename2, nxp_flash_loc);
        strncat(rffilename2, nxprfconf, sizeof(rffilename)-strlen(rffilename)-1);
    }
    memset(nxp_flash_loc, 0, sizeof(nxp_flash_loc));
    if(GetNxpStrValue(NAME_FW_STORAGE, nxp_flash_loc, sizeof(nxp_flash_loc)))
    {
        strcpy(fwfilename2, nxp_flash_loc);
        strncat(fwfilename2, nxpFW, sizeof(fwfilename)-strlen(fwfilename)-1);
    }

    if(isFilesPresent(rffilename, fwfilename))
    {
        resetNxpConfig();
        setNxpRfConfigPath(rffilename);
        GetNxpByteArrayValue(NAME_NXP_RF_FILE_VERSION_INFO, RF_ver_user, sizeof(RF_ver_user), &retlen);
        if(retlen == -1)
        {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("NXP_RF_FILE_VERSION_INFO not found; No update possibile");
            RF_ver_user[0] = 0x00;
            RF_ver_user[1] = 0x00;
        }
    }
    else
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("No RF file present in USER space");

    if(isFilesPresent(rffilename2, fwfilename2))
    {
        resetNxpConfig();
        setNxpRfConfigPath(rffilename2);
        GetNxpByteArrayValue(NAME_NXP_RF_FILE_VERSION_INFO, RF_ver_sys, sizeof(RF_ver_sys), &retlen);
        if(retlen == -1)
        {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("NXP_RF_FILE_VERSION_INFO not found; No update possibile");
            RF_ver_sys[0] = 0x00;
            RF_ver_sys[1] = 0x00;
        }
    }
    else
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("No RF file present in SYSTEM space");

    if((RF_ver_sys[0] == 0)&&(RF_ver_sys[1] == 0))
    {
        if((RF_ver_user[0] == 0)&&(RF_ver_user[1] == 0))
        {
            strcpy(rffilename, rffilename2);
            strcpy(fwfilename, fwfilename2);
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("RF Version in SYS Space is latest");
        }
        else
        {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("RF Version in User Space is latest");
        }
    }
    else if((RF_ver_user[0] < RF_ver_sys[0]) ||
       ((RF_ver_user[0] == RF_ver_sys[0]) && (RF_ver_user[1] < RF_ver_sys[1])))
    {
        strcpy(rffilename, rffilename2);
        strcpy(fwfilename, fwfilename2);

        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("RF Version in SYS Space is latest");
    }
    else
    {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("RF Version in User Space is latest");
    }
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("rffilename=%s, fwfilename=%s", rffilename, fwfilename);
    setNxpRfConfigPath(rffilename);
}
/*******************************************************************************
 **
 ** Function:        find_nci_value_from_config_file
 **
 ** Description:     This function is used to read the  nfcc config
 **                  from config file.
 **
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/

tNFA_STATUS find_nci_value_from_config_file(uint8_t param, uint8_t *value, uint8_t *valuelen)
{
    tNFA_STATUS status = NFCSTATUS_FAILED;
    long retlen = 0;
    long bufflen = 260;
    int isfound;
    uint8_t *buffer = NULL;

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);
    buffer = (uint8_t*) malloc(bufflen*sizeof(uint8_t));
    if(NULL == buffer)
    {
        return status;
    }

    updateNxpRfConfpath();/*Need to see the impact*/

    isfound =  GetNxpByteArrayValue(NAME_NXP_CORE_CONF,(char *)buffer,bufflen,&retlen);
    if(retlen > 0)
    {
        uint8_t i=4, entry=0;
        uint8_t valuebuf[255];

        memset(valuebuf, 0xFF, sizeof(valuebuf));
        entry = buffer[3];
        while(entry)
        {
            if(buffer[i] == param)
            {
                *valuelen = buffer[i+1];
                memcpy(valuebuf, (uint8_t *)&buffer[i+2], buffer[i+1]);
                break;
            }
            else
            {
                entry--;
                if(entry == 0) break;
                else i = (i + buffer[i+1] + 2);
            }
        }
        if(valuebuf[0] != 0xFF)
        {
            memcpy(value, valuebuf, *valuelen);
            status = NFCSTATUS_SUCCESS;
        }
    }

    if(buffer != NULL)
        free(buffer);

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
    return status;
}

/*******************************************************************************
 **
 ** Function:        set_nfcc_config_default_value
 **
 ** Description:     This function is used to read the  nfcc config
 **                  from config file and update it.
 **
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS set_nfcc_config_default_value(void)
{
    tNFA_STATUS status = NFCSTATUS_SUCCESS;
    long retlen = 0;
    long bufflen = 260;
    int isfound;
    uint8_t *buffer = NULL;

    /*set HCI param as default.*/

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

    buffer = (uint8_t*) malloc(bufflen*sizeof(uint8_t));
    if(NULL == buffer)
    {
        status = NFCSTATUS_FAILED;
        return status;
    }

    updateNxpRfConfpath();/*Need to see the impact*/

    isfound =  GetNxpByteArrayValue(NAME_NXP_CORE_CONF,(char *)buffer,bufflen,&retlen);
    if (retlen > 0)
    {
        tNFA_STATUS retval;
        uint8_t value[2] = {0,},valuelen = 0, bufoffset = 0;
        bufoffset = buffer[2] + 3;
        buffer[2] = buffer[2] + 3;
        buffer[3]++;
        buffer[bufoffset++] = NCI_PARAM_ID_LB_SFGI;
        buffer[bufoffset++] = 1;
        retval = find_nci_value_from_config_file(NCI_PARAM_ID_LB_SFGI, value, &valuelen);
        if(retval == NFA_STATUS_OK)
            buffer[bufoffset] = value[0];
        else
            buffer[bufoffset] = 0x00; /*defalut value*/
        retlen += 3;
        status = android::NxpNfc_Write_Cmd_Common(retlen, buffer);
        if(status != NFCSTATUS_SUCCESS)
        {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: write set command failed!", __func__);
            status = NFCSTATUS_FAILED;
        }
    }

    if(buffer != NULL)
        free(buffer);

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);

    return status;
}

/*******************************************************************************
 **
 ** Function:        set_nfcc_config_control
 **
 ** Description:     This function is used to set the config control
 **                  for merge sak.
 **                  enable with value 0 sets to 0x02
 **                  enable with value 1 sets to 0x00
 **
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS set_nfcc_config_control(uint8_t enable)
{
    tNFA_STATUS status = NFCSTATUS_SUCCESS;
    uint8_t set_nfcc_config[] = {0x20, 0x02, 0x04, 0x01, NXP_NFC_PARAM_ID_NFCC_RF_CONFIG, 0x01, 0x00};

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:  enter enable=0x%x", __func__,enable);

    if(enable)
        set_nfcc_config[6] = NXP_DH_DECIDE_MERGE_SAK;
    else
        set_nfcc_config[6] = NXP_NFCC_DECIDE_MERGE_SAK;

    status = android::NxpNfc_Write_Cmd_Common(sizeof(set_nfcc_config), set_nfcc_config);
    if(status != NFCSTATUS_SUCCESS)
    {
        DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: write set command failed!", __func__);
        status = NFCSTATUS_FAILED;
    }

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);

    return status;
}
/*******************************************************************************
 **
 ** Function:        get_nfcc_config_control
 **
 ** Description:     This function is used to read the  nfcc config control
 **                  for merge sak.
 **
 **
 ** Returns:         success/failure
 **
 *******************************************************************************/
tNFA_STATUS get_nfcc_config_control(void)
{
    tNFA_STATUS status = NFCSTATUS_SUCCESS;
    uint8_t get_nfcc_config[] = {0x20, 0x03, 0x02, 0x01, NXP_NFC_PARAM_ID_NFCC_RF_CONFIG};
    uint8_t get_nfcc_config_rsp[255];
    uint8_t get_nfcc_config_rsp_len = 255;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: enter", __func__);

    status = android::NxpPropCmd_send(get_nfcc_config,sizeof(get_nfcc_config),
        &get_nfcc_config_rsp_len,get_nfcc_config_rsp,2000,NULL);
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: status %d get_nfcc_config_rsp_len %x", __func__,status,get_nfcc_config_rsp_len);
    if(get_nfcc_config_rsp_len > 0)
    {
        if(!get_nfcc_config_rsp[4])
        {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:  gnxpfeature_conf.rsp_data[8] = 0x%x", __func__,get_nfcc_config_rsp[5]);
            /*already disabled.*/
            status = NFCSTATUS_FAILED;
        }
    }

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: status exit", __func__);

    return status;
}

/*******************************************************************************
 **
 ** Function:        nfaManager_getSESupportTech()
 **
 ** Description:     Get All Supported Technology of SE.
 **
 ** Returns:         supported tech byte.
 **
 **                  bit7 : UICC Mifare
 **                  bit6 : UICC TypeF
 **                  bit5 : UICC TypeB
 **                  bit4 : UICC TypeA
 **                  bit3 : ESE Mifare
 **                  bit2 : RFU
 **                  bit1 : ESE TypeB
 **                  bit0 : ESE TypeA
 **
 *******************************************************************************/
jint nfaManager_getSESupportTech(JNIEnv *e, jobject o)
{
    jint supportTech;
    tNFA_STATUS status = NFA_STATUS_FAILED;
    tNFA_PMID rf_params_NFCEE_UICC[] = {NXP_NFC_SET_CONFIG_PARAM_EXT, NXP_NFC_PARAM_ID_RF_PARAM_UICC};
    tNFA_PMID rf_params_NFCEE_eSE[] = {NXP_NFC_SET_CONFIG_PARAM_EXT, NXP_NFC_PARAM_ID_RF_PARAM_ESE};
    tNFA_PMID rf_params_NFCEE_UICC2[] = {NXP_NFC_SET_CONFIG_PARAM_EXT, NXP_NFC_PARAM_ID_RF_PARAM_UICC2};
    //tNFA_EE_INFO eeinfo[SecureElement::MAX_NUM_EE];
    //unsigned char actualNumEe = SecureElement::MAX_NUM_EE;
    unsigned char uiccDetected = 0, eseDetected = 0;
    int tech = 0;
    int uiccHandle = SecureElement::getInstance().EE_HANDLE_0xF4;
    DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s: enter", __func__);
    uint16_t sCurrentConfigLen = 0;
    uint8_t sConfig[256]={ };
    if(android::nfcManager_isNfcActive() == false)
    {
        DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s NFC is not initialized ",__func__);
        return 0xFF;
    }
        DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s merge sak 1",__func__);
    if(sCurrentSelectedUICCSlot == 0x02)
    {
       uiccHandle = SecureElement::EE_HANDLE_0xF8;
    }
    if(sCurrentSelectedUICCSlot == 0x01) {
        status = android::getConfig(&sCurrentConfigLen, sConfig , 0x01, rf_params_NFCEE_UICC);
    }
    else if (sCurrentSelectedUICCSlot == 0x02) {
        status = android::getConfig(&sCurrentConfigLen, sConfig , 0x01, rf_params_NFCEE_UICC2);
    }
    if(sConfig[5] == 0x02)  /*TypeA Mode Enabled flag */
    {
        if(sConfig[17] & 0x20) tech |= UICC_TECH_A; /*TypeA ISO-DEP*/
        if(sConfig[17] & 0x08) tech |= UICC_MIFARE; /*Mifare*/
    }
        DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s merge sak 3",__func__);
    if(sConfig[43] == 0x02) tech |= UICC_TECH_B;
    SecureElement::getInstance().getEeInfo();
    tNFA_EE_INFO *pEE = SecureElement::getInstance().findEeByHandle (uiccHandle);
    if(pEE != NULL && sCurrentConfigLen >= 5) /*UICC/UICC2 handle*/
    {
        if(pEE->lf_protocol != 0x00)
            tech |= UICC_TYPE_F; /*Type F support */
        uiccDetected = 1;
        memset((uint8_t *)&hci_parameter_uicc, 0x00, sizeof(hci_parameter_uicc));
        memcpy((uint8_t *)&hci_parameter_uicc, (uint8_t *)&sConfig[4], sCurrentConfigLen - 5);
    }

    memset(sConfig, 0x00, sizeof(sConfig));
    sCurrentConfigLen = 0;
    {
        status = android::getConfig(&sCurrentConfigLen, sConfig , 0x01, rf_params_NFCEE_eSE);//NFA_GetConfig(0x01, rf_params_NFCEE_eSE);
        if (status != NFA_STATUS_OK)
        {
            DLOG_IF(ERROR, nfc_debug_enabled) << StringPrintf("%s: NFA_GetConfig failed", __func__);
            return 0xFF;
        }
    }

    if(sConfig[5] == 0x02)  //TypeA Mode Enabled flag.
    {
            if(sConfig[17] & 0x20) tech |= ESE_TECH_A; //TypeA ISO-DEP
            if(sConfig[17] & 0x08) tech |= ESE_MIFARE; // Mifare
    }

    if(sConfig[43] == 0x02) tech |= ESE_TECH_B;
    pEE = SecureElement::getInstance().findEeByHandle (SecureElement::EE_HANDLE_0xF3);

    if(pEE != NULL && sCurrentConfigLen >= 5) //ESE
    {
        if(pEE->lf_protocol != 0x00)
            tech |= ESE_TECH_F; /*Type F support */
        eseDetected = 1;
        memset((uint8_t *)&hci_parameter_eSE, 0x00, sizeof(hci_parameter_eSE));
        memcpy((uint8_t *)&hci_parameter_eSE, (uint8_t *)&sConfig[4], sCurrentConfigLen - 5);
    }
    DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s: exit eseDetected: %x,uiccDetected %x", __func__,eseDetected,uiccDetected);
    if(!uiccDetected) tech &= ESE_TECH_BYTE_MASK;
    if(!eseDetected) tech &= UICC_TECH_BYTE_MASK;

    supportTech = tech;
    DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s: exit supportTech: %x", __func__,supportTech);
    return supportTech;
}
/*******************************************************************************
 **
 ** Function:        checkIsodepRouting
 **
 ** Description:     This function is used to read the RF parameters
 **                  and merge the RF parameters such as SAK .
 **
 **
 ** Returns:         true/false
 **
 *******************************************************************************/
bool checkIsodepRouting(void) {

    bool option;
    //SAK MERGE
    uint16_t sRoutingBuffLen = 0;
    uint8_t* sRoutingBuff = NULL;
    int isfound = 0;
    isfound = GetNxpNumValue(NAME_NXP_NFCC_MERGE_SAK_ENABLE, &option, sizeof(option));
    if((!isfound || !option)) {
        return false;
    }
    unsigned int seek = 0;
    static int uiccsupporttech = 0;

    uint8_t proto_isodep_destination = 0xFF;
    uint8_t tech_a_destination = 0xFF;
    bool aid_uicc_exist = false;
    bool aid_ese_exist = false;
    bool aid_hce_exist = false;

    uint8_t p_sakmerge = 0x00;

    long bufflen = 260;
    uint8_t buffer[260];
    uint8_t bufoffset = 0;
    uint8_t entryoffset = 0;
    int uiccHandle = SecureElement::getInstance().EE_HANDLE_0xF4;
    DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s: enter", __func__);

    memset(buffer, 0x00, sizeof(buffer));
    memcpy(buffer, "\x20\x02", 2);
    bufoffset = 0x03;

    uiccsupporttech = nfaManager_getSESupportTech(0,0);
    DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s: uiccsupporttech = %x", __func__,uiccsupporttech);
    /* UICC doesnt support MIFARE*/

    if((uiccsupporttech & 0x90) != 0x80)
    {
        if(NFCSTATUS_SUCCESS == get_nfcc_config_control())
        {
            if(NFCSTATUS_SUCCESS == set_nfcc_config_default_value())
            {
                if(set_nfcc_config_control(NXP_FEATURE_DISABLED) == NFCSTATUS_SUCCESS)
                    return true;
                else
                    return false;
            }
            else
            {
                DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s: set_nfcc_config_default_value failed", __func__);
                return false;
            }
        }
        else
        {
            /*already disabled. nothing to do*/
            DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s: nothing to do", __func__);
            return true;
        }
    }

    {
        sRoutingBuffLen = 0;
        sRoutingBuff = (uint8_t*)malloc(1024);
        if(sRoutingBuff == NULL)
            return false;
        memset(sRoutingBuff, 0, sizeof(sRoutingBuff));
        RoutingManager::getInstance().getRouting(&sRoutingBuffLen,sRoutingBuff);
    }

    DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s: seek : %d, sRoutingBuffLen : %d", __func__, seek, sRoutingBuffLen);

    if(sRoutingBuffLen > 0) {

        while(seek < sRoutingBuffLen)
        {
            DLOG_IF(INFO, nfc_debug_enabled)<< StringPrintf("%s: seek : %d, sRoutingBuff[seek] : 0x%02X, (sRoutingBuff[seek+4] : 0x%02X", __func__, seek, sRoutingBuff[seek], sRoutingBuff[seek+4]);

            if((sRoutingBuff[seek] == PROTOCOL_BASED_ROUTING) &&/*protocol - isodep*/
                (sRoutingBuff[seek+1] >= 3) &&
                ((sRoutingBuff[seek+4] == NFA_PROTOCOL_ISO_DEP)))
                proto_isodep_destination = sRoutingBuff[seek+2];
            else if((sRoutingBuff[seek] == TECHNOLOGY_BASED_ROUTING) &&/*Tech A*/
                (sRoutingBuff[seek+1] >= 3) &&
                ((sRoutingBuff[seek+4] == NFC_RF_TECHNOLOGY_A)))
                tech_a_destination = sRoutingBuff[seek+2];
            else if((sRoutingBuff[seek] == AID_BASED_ROUTING) &&/*aid to DH*/
                (sRoutingBuff[seek+1] >= 3) &&
                ((sRoutingBuff[seek+2] == 0)))
                aid_hce_exist = true;
            else if((sRoutingBuff[seek] == AID_BASED_ROUTING) &&/*aid to ese*/
                (sRoutingBuff[seek+1] >= 3) &&
                ((sRoutingBuff[seek+2] == ESE_HOST)))
                aid_ese_exist = true;
            else if((sRoutingBuff[seek] == AID_BASED_ROUTING) &&/*aid to UICC*/
                (sRoutingBuff[seek+1] >= 3) &&
                ((sRoutingBuff[seek+2] == uiccHandle)))
                aid_uicc_exist = true;

            seek += (sRoutingBuff[seek+1] + 2);
        }
        if(sRoutingBuff != NULL) {
            free(sRoutingBuff);
            sRoutingBuff = NULL;
        }
/*decide sak merge*/
        if(tech_a_destination == uiccHandle)
        {
            if(proto_isodep_destination == ESE_HOST || proto_isodep_destination == DH_HOST ||
                aid_hce_exist == true || aid_ese_exist == true)
            {
                p_sakmerge = 1;
                if(hci_parameter_uicc.bMode_CeA)
                {
                    bufoffset++;
                    entryoffset++;
                    buffer[bufoffset++] = NCI_PARAM_ID_LA_SEL_INFO;
                    buffer[bufoffset++] = 1;
                    buffer[bufoffset] = (hci_parameter_uicc.bSak_CeA | 0x20);
                }
            }
            else
            {
                if(NFCSTATUS_SUCCESS == get_nfcc_config_control())
                {
                    if(NFCSTATUS_SUCCESS == set_nfcc_config_default_value())
                    {
                        if(set_nfcc_config_control(NXP_FEATURE_DISABLED) == NFCSTATUS_SUCCESS)
                            return true;
                        else
                            return false;
                    }
                    else
                        return false;
                }
                else
                {
                    /*already disabled. nothing to do.*/
                    return true;
                }
            }
        }
        else
        {
            if(NFCSTATUS_SUCCESS == get_nfcc_config_control())
            {
                if(NFCSTATUS_SUCCESS == set_nfcc_config_default_value())
                {
                   if(set_nfcc_config_control(NXP_FEATURE_DISABLED) == NFCSTATUS_SUCCESS)
                       return true;
                   else
                       return false;
                }
                else
                    return false;
            }
            else
            {
                /*already disabled. nothing to do.*/
                return true;
            }
        }


/*decide atqa*/
        if(tech_a_destination == uiccHandle)
        {
            if(hci_parameter_uicc.bMode_CeA)
            {
                bufoffset++;
                entryoffset++;
                buffer[bufoffset++] = NCI_PARAM_ID_LA_BIT_FRAME_SDD;
                buffer[bufoffset++] = 1;
                buffer[bufoffset++] = hci_parameter_uicc.aATQA_CeA[0];
                entryoffset++;
                buffer[bufoffset++] = NCI_PARAM_ID_LA_PLATFORM_CONFIG;
                buffer[bufoffset++] = 1;
                buffer[bufoffset++] = hci_parameter_uicc.aATQA_CeA[1];
                entryoffset++;
                buffer[bufoffset++] = NCI_PARAM_ID_LA_NFCID1;
                buffer[bufoffset++] = hci_parameter_uicc.bUidRegSize_CeA;
                memcpy((uint8_t *)&buffer[bufoffset], (uint8_t *)&hci_parameter_uicc.aUidReg_CeA[0], hci_parameter_uicc.bUidRegSize_CeA);
                bufoffset += hci_parameter_uicc.bUidRegSize_CeA;
                bufoffset--;
            }
        }
        else if(tech_a_destination == ESE_HOST)
        {
            if(hci_parameter_eSE.bMode_CeA)
            {
                bufoffset++;
                entryoffset++;
                buffer[bufoffset++] = NCI_PARAM_ID_LA_BIT_FRAME_SDD;
                buffer[bufoffset++] = 1;
                buffer[bufoffset++] = hci_parameter_eSE.aATQA_CeA[0];
                entryoffset++;
                buffer[bufoffset++] = NCI_PARAM_ID_LA_PLATFORM_CONFIG;
                buffer[bufoffset++] = 1;
                buffer[bufoffset++] = hci_parameter_eSE.aATQA_CeA[1];
                entryoffset++;
                buffer[bufoffset++] = NCI_PARAM_ID_LA_NFCID1;
                buffer[bufoffset++] = hci_parameter_eSE.bUidRegSize_CeA;
                memcpy((uint8_t *)&buffer[bufoffset], (uint8_t *)&hci_parameter_eSE.aUidReg_CeA[0], hci_parameter_eSE.bUidRegSize_CeA);
                bufoffset += hci_parameter_eSE.bUidRegSize_CeA;
                bufoffset--;
            }
        }
/*decide ats*/

        if(tech_a_destination == uiccHandle)
        {
            if(proto_isodep_destination == DH_HOST)
            {
                if(p_sakmerge == 1)
                {
                    tNFA_STATUS retval = NFA_STATUS_FAILED;
                    uint8_t *value = NULL;
                    uint8_t valuelen = 0;

                    value = (uint8_t*) malloc(bufflen*sizeof(uint8_t));
                    if(NULL == value) return false;

                    bufoffset++;
                    entryoffset++;
                    buffer[bufoffset++] = NCI_PARAM_ID_FWI;
                    buffer[bufoffset++] = 1;
                    retval = find_nci_value_from_config_file(NCI_PARAM_ID_FWI, value, &valuelen);
                    if(retval == NFA_STATUS_OK) buffer[bufoffset++] = value[0];
                    else                        buffer[bufoffset++] = 0x07; /*defalut value*/
                    entryoffset++;
                    buffer[bufoffset++] = NCI_PARAM_ID_LB_SFGI;
                    buffer[bufoffset++] = 1;
                    retval = find_nci_value_from_config_file(NCI_PARAM_ID_LB_SFGI, value, &valuelen);
                    if(retval == NFA_STATUS_OK) buffer[bufoffset++] = value[0];
                    else                        buffer[bufoffset++] = 0x00; /*defalut value*/
                    entryoffset++;
                    buffer[bufoffset++] = 0x5C; /*NCI_PARAM_ID_LA_RATS_RESP_TC1*/
                    buffer[bufoffset++] = 1;
                    retval = find_nci_value_from_config_file(0x5C, value, &valuelen);

                    if(retval == NFA_STATUS_OK) buffer[bufoffset++] = value[0];
                    else                        buffer[bufoffset++] = 0x00; /*defalut value*/
                    entryoffset++;
                    buffer[bufoffset++] = NCI_PARAM_ID_LA_HIST_BY;
                    retval = find_nci_value_from_config_file(NCI_PARAM_ID_LA_HIST_BY, value, &valuelen);
                    if(retval == NFA_STATUS_OK)
                    {
                        buffer[bufoffset++] = valuelen;
                        memcpy((uint8_t *)&buffer[bufoffset], value, valuelen);
                        bufoffset += valuelen;
                        bufoffset--;
                    }
                    else
                    {
                        buffer[bufoffset] = 0x00; /*defalut value*/
                    }

                    if(value != NULL) free(value);
                }

            }
            else if(proto_isodep_destination == ESE_HOST)
            {
                bufoffset++;
                entryoffset++;
                buffer[bufoffset++] = NCI_PARAM_ID_FWI;
                buffer[bufoffset++] = 1;
                buffer[bufoffset++] = ((hci_parameter_eSE.bFWI_SFGI_CeA & 0xF0) >> 4);
                entryoffset++;
                buffer[bufoffset++] = NCI_PARAM_ID_LB_SFGI;
                buffer[bufoffset++] = 1;
                buffer[bufoffset++] = (hci_parameter_eSE.bFWI_SFGI_CeA & 0x0F);
                entryoffset++;
                buffer[bufoffset++] = 0x5C; /*NCI_PARAM_ID_LA_RATS_RESP_TC1*/
                buffer[bufoffset++] = 1;
                if(hci_parameter_eSE.bCidSupport_CeA == 0x01) buffer[bufoffset++] = 0x02;  /*CID Support*/
                else buffer[bufoffset++] = hci_parameter_eSE.bCidSupport_CeA;
                entryoffset++;
                buffer[bufoffset++] = NCI_PARAM_ID_LA_HIST_BY;
                buffer[bufoffset++] = hci_parameter_eSE.bApplicationDataSize_CeA;
                memcpy((uint8_t *)&buffer[bufoffset], (uint8_t *)&hci_parameter_eSE.aApplicationData_CeA[0], hci_parameter_eSE.bApplicationDataSize_CeA);
                bufoffset += hci_parameter_eSE.bApplicationDataSize_CeA;
                bufoffset--;
            }
            else
            {
                if(aid_ese_exist == true)
                {
                    bufoffset++;
                    entryoffset++;
                    buffer[bufoffset++] = NCI_PARAM_ID_FWI;
                    buffer[bufoffset++] = 1;
                    buffer[bufoffset++] = ((hci_parameter_eSE.bFWI_SFGI_CeA & 0xF0) >> 4);
                    entryoffset++;
                    buffer[bufoffset++] = NCI_PARAM_ID_LB_SFGI;
                    buffer[bufoffset++] = 1;
                    buffer[bufoffset++] = (hci_parameter_eSE.bFWI_SFGI_CeA & 0x0F);
                    entryoffset++;
                    buffer[bufoffset++] = 0x5C; /*NCI_PARAM_ID_LA_RATS_RESP_TC1*/
                    buffer[bufoffset++] = 1;
                    if(hci_parameter_eSE.bCidSupport_CeA == 0x01) buffer[bufoffset++] = 0x02;  /*CID Support*/
                    else buffer[bufoffset++] = hci_parameter_eSE.bCidSupport_CeA;
                    entryoffset++;
                    buffer[bufoffset++] = NCI_PARAM_ID_LA_HIST_BY;
                    buffer[bufoffset++] = hci_parameter_eSE.bApplicationDataSize_CeA;
                    memcpy((uint8_t *)&buffer[bufoffset], (uint8_t *)&hci_parameter_eSE.aApplicationData_CeA[0], hci_parameter_eSE.bApplicationDataSize_CeA);
                    bufoffset += hci_parameter_eSE.bApplicationDataSize_CeA;
                    bufoffset--;
                }
                else if(aid_hce_exist == true)
                {
                    if(p_sakmerge == 1)
                    {
                        tNFA_STATUS retval = NFA_STATUS_FAILED;
                        uint8_t *value = NULL;
                        uint8_t valuelen = 0;

                        value = (uint8_t*) malloc(bufflen*sizeof(uint8_t));
                        if(NULL == value) return false;

                        bufoffset++;
                        entryoffset++;
                        buffer[bufoffset++] = NCI_PARAM_ID_FWI;
                        buffer[bufoffset++] = 1;
                        retval = find_nci_value_from_config_file(NCI_PARAM_ID_FWI, value, &valuelen);
                        if(retval == NFA_STATUS_OK) buffer[bufoffset++] = value[0];
                        else                        buffer[bufoffset++] = 0x07; /*defalut value*/
                        entryoffset++;
                        buffer[bufoffset++] = NCI_PARAM_ID_LB_SFGI;
                        buffer[bufoffset++] = 1;
                        retval = find_nci_value_from_config_file(NCI_PARAM_ID_LB_SFGI, value, &valuelen);
                        if(retval == NFA_STATUS_OK) buffer[bufoffset++] = value[0];
                        else                        buffer[bufoffset++] = 0x00; /*defalut value*/
                        entryoffset++;
                        buffer[bufoffset++] = 0x5C; /*NCI_PARAM_ID_LA_RATS_RESP_TC1*/
                        buffer[bufoffset++] = 1;
                        retval = find_nci_value_from_config_file(0x5C, value, &valuelen);
                        if(retval == NFA_STATUS_OK) buffer[bufoffset++] = value[0];
                        else                        buffer[bufoffset++] = 0x00; /*defalut value*/
                        entryoffset++;
                        buffer[bufoffset++] = NCI_PARAM_ID_LA_HIST_BY;
                        retval = find_nci_value_from_config_file(NCI_PARAM_ID_LA_HIST_BY, value, &valuelen);
                        if(retval == NFA_STATUS_OK)
                        {
                            buffer[bufoffset++] = valuelen;
                            memcpy((uint8_t *)&buffer[bufoffset], value, valuelen);
                            bufoffset += valuelen;
                            bufoffset--;
                        }
                        else
                        {
                            buffer[bufoffset] = 0x00; /*defalut value*/
                        }

                        if(value != NULL) free(value);
                    }
                }
            }
        }
        else if(tech_a_destination == ESE_HOST)
        {
            bufoffset++;
            entryoffset++;
            buffer[bufoffset++] = NCI_PARAM_ID_FWI;
            buffer[bufoffset++] = 1;
            buffer[bufoffset++] = (hci_parameter_eSE.bFWI_SFGI_CeA & 0xF0);
            entryoffset++;
            buffer[bufoffset++] = NCI_PARAM_ID_LB_SFGI;
            buffer[bufoffset++] = 1;
            buffer[bufoffset++] = (hci_parameter_eSE.bFWI_SFGI_CeA & 0x0F);
            entryoffset++;
            buffer[bufoffset++] = 0x5C; /*NCI_PARAM_ID_LA_RATS_RESP_TC1*/
            buffer[bufoffset++] = 1;
            if(hci_parameter_eSE.bCidSupport_CeA == 0x01) buffer[bufoffset++] = 0x02;  /*CID Support*/
            else buffer[bufoffset++] = hci_parameter_eSE.bCidSupport_CeA;
            entryoffset++;
            buffer[bufoffset++] = NCI_PARAM_ID_LA_HIST_BY;
            buffer[bufoffset++] = hci_parameter_eSE.bApplicationDataSize_CeA;
            memcpy((uint8_t *)&buffer[bufoffset], (uint8_t *)&hci_parameter_eSE.aApplicationData_CeA[0], hci_parameter_eSE.bApplicationDataSize_CeA);
            bufoffset += hci_parameter_eSE.bApplicationDataSize_CeA;
            bufoffset--;
        }
    } else {
        if(sRoutingBuff != NULL) {
            free(sRoutingBuff);
            sRoutingBuff = NULL;
        }
    }
    return false;
}