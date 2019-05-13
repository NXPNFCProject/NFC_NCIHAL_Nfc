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
 *  Copyright 2018-2019 NXP
 *
 ******************************************************************************/
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include "MposManager.h"
#include <ScopedLocalRef.h>
#include "SecureElement.h"
#include "TransactionController.h"
#include "nfc_config.h"
#include "phNxpConfig.h"

using namespace android;
using android::base::StringPrintf;

extern bool nfc_debug_enabled;

MposManager::MposManager() { mNativeData = NULL; }

namespace android {
extern tNFA_STATUS EmvCo_dosetPoll(jboolean enable);
extern bool isDiscoveryStarted();
extern void startRfDiscovery(bool isStart);
}  // namespace android

MposManager MposManager::mMposMgr;
int32_t MposManager::mDiscNtfTimeout = 10;
int32_t MposManager::mRdrTagOpTimeout = 20;
jmethodID MposManager::gCachedMposManagerNotifyETSIReaderRequested;
jmethodID MposManager::gCachedMposManagerNotifyETSIReaderRequestedFail;
jmethodID MposManager::gCachedMposManagerNotifyETSIReaderModeStartConfig;
jmethodID MposManager::gCachedMposManagerNotifyETSIReaderModeStopConfig;
jmethodID MposManager::gCachedMposManagerNotifyETSIReaderModeSwpTimeout;
jmethodID MposManager::gCachedMposManagerNotifyETSIReaderRestart;

/*******************************************************************************
**
** Function:        initMposNativeStruct
**
** Description:     Used to initialize the Native MPOS notification methods
**
** Returns:         None.
**
*******************************************************************************/
void MposManager::initMposNativeStruct(JNIEnv* e, jobject o) {
  ScopedLocalRef<jclass> cls(e, e->GetObjectClass(o));

  gCachedMposManagerNotifyETSIReaderRequested =
      e->GetMethodID(cls.get(), "notifyETSIReaderRequested", "(ZZ)V");

  gCachedMposManagerNotifyETSIReaderRequestedFail =
      e->GetMethodID(cls.get(), "notifyETSIReaderRequestedFail", "(I)V");

  gCachedMposManagerNotifyETSIReaderModeStartConfig =
      e->GetMethodID(cls.get(), "notifyonETSIReaderModeStartConfig", "(I)V");

  gCachedMposManagerNotifyETSIReaderModeStopConfig =
      e->GetMethodID(cls.get(), "notifyonETSIReaderModeStopConfig", "(I)V");

  gCachedMposManagerNotifyETSIReaderModeSwpTimeout =
      e->GetMethodID(cls.get(), "notifyonETSIReaderModeSwpTimeout", "(I)V");

  gCachedMposManagerNotifyETSIReaderRestart =
      e->GetMethodID(cls.get(), "notifyonETSIReaderModeRestart", "()V");
}

/*******************************************************************************
**
** Function:        getInstance
**
** Description:     Get the SecureElement singleton object.
**
** Returns:         SecureElement object.
**
*******************************************************************************/
MposManager& MposManager::getInstance() { return mMposMgr; }

/*******************************************************************************
**
** Function:        initialize
**
** Description:     Initialize all member variables.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool MposManager::initialize(nfc_jni_native_data* native) {
  mNativeData = native;
  initializeReaderInfo();
  mDiscNtfTimeout = NfcConfig::getUnsigned(NAME_NFA_DM_DISC_NTF_TIMEOUT);
  if (NfcConfig::hasKey(NAME_NXP_SWP_RD_TAG_OP_TIMEOUT)) {
    mRdrTagOpTimeout = NfcConfig::getUnsigned(NAME_NXP_SWP_RD_TAG_OP_TIMEOUT);
  }
  return true;
}

/*******************************************************************************
**
** Function:        initializeReaderInfo
**
** Description:     Initialize all MPOS member variables.
**
** Returns:         none.
**
*******************************************************************************/
void MposManager::initializeReaderInfo() {
  swp_rdr_req_ntf_info.mMutex.lock();
  memset(&(swp_rdr_req_ntf_info.swp_rd_req_info), 0x00, sizeof(rd_swp_req_t));
  memset(&(swp_rdr_req_ntf_info.swp_rd_req_current_info), 0x00,
         sizeof(rd_swp_req_t));
  swp_rdr_req_ntf_info.swp_rd_req_current_info.src = NFA_HANDLE_INVALID;
  swp_rdr_req_ntf_info.swp_rd_req_info.src = NFA_HANDLE_INVALID;
  swp_rdr_req_ntf_info.swp_rd_state = STATE_SE_RDR_MODE_STOPPED;
  swp_rdr_req_ntf_info.mMutex.unlock();
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
void MposManager::finalize() {}

/*******************************************************************************
**
** Function:        setDedicatedReaderMode
**
** Description:     Set/reset MPOS dedicated mode.
**
** Returns:         SUCCESS/FAILED/BUSY
**
*******************************************************************************/
tNFA_STATUS MposManager::setDedicatedReaderMode(bool on) {
  tNFA_STATUS status = NFA_STATUS_OK;
  bool rfStat = false;
  int32_t state;
  SecureElement& se = SecureElement::getInstance();

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
      "%s:enter, Reader Mode %s", __FUNCTION__, on ? "ON" : "OFF");
  if (se.isRfFieldOn() || se.isActivatedInListenMode()) {
    status = NFA_STATUS_BUSY;
  }

  if (status == NFA_STATUS_OK) {
    state = getEtsiReaederState();
    rfStat = isDiscoveryStarted();
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%x, rfStat=%x", on, rfStat);
    if (on) {
      if (rfStat) {
        startRfDiscovery(false);
      }
    } else {
      if (state != STATE_SE_RDR_MODE_STOPPED) {
        setEtsiReaederState(STATE_SE_RDR_MODE_STOP_CONFIG);
        etsiInitConfig();
        startRfDiscovery(false);
        status = etsiResetReaderConfig();
        setEtsiReaederState(STATE_SE_RDR_MODE_STOPPED);
      }
      initializeReaderInfo();
    }
  } else {
    LOG(ERROR) << StringPrintf(
        "Payment is in progress, aborting reader mode start");
  }
  return status;
}
/*******************************************************************************
**
** Function:        setEtsiReaederState
**
** Description:     Set the current ETSI Reader state
**
** Returns:         None
**
*******************************************************************************/
void MposManager::setEtsiReaederState(se_rd_req_state_t newState) {
  swp_rdr_req_ntf_info.mMutex.lock();
  if (newState == STATE_SE_RDR_MODE_STOPPED) {
    swp_rdr_req_ntf_info.swp_rd_req_current_info.tech_mask &=
        ~NFA_TECHNOLOGY_MASK_A;
    swp_rdr_req_ntf_info.swp_rd_req_current_info.tech_mask &=
        ~NFA_TECHNOLOGY_MASK_B;

    // If all the requested tech are removed, set the hande to invalid , so that
    // next time poll add request can be handled

    swp_rdr_req_ntf_info.swp_rd_req_current_info.src = NFA_HANDLE_INVALID;
    swp_rdr_req_ntf_info.swp_rd_req_info =
        swp_rdr_req_ntf_info.swp_rd_req_current_info;
  }
  swp_rdr_req_ntf_info.swp_rd_state = newState;
  swp_rdr_req_ntf_info.mMutex.unlock();
}

/*******************************************************************************
**
** Function:        getEtsiReaederState
**
** Description:     Get the current ETSI Reader state
**
** Returns:         Current ETSI state
**
*******************************************************************************/
se_rd_req_state_t MposManager::getEtsiReaederState() {
  return swp_rdr_req_ntf_info.swp_rd_state;
}

/*******************************************************************************
**
** Function:        etsiInitConfig
**
** Description:     Chnage the ETSI state before start configuration
**
** Returns:         None
**
*******************************************************************************/
void MposManager::etsiInitConfig() {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);
  swp_rdr_req_ntf_info.mMutex.lock();

  if ((swp_rdr_req_ntf_info.swp_rd_state == STATE_SE_RDR_MODE_START_CONFIG) &&
      ((swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask &
        NFA_TECHNOLOGY_MASK_A) ||
       (swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask &
        NFA_TECHNOLOGY_MASK_B))) {
    if ((swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask &
         NFA_TECHNOLOGY_MASK_A)) {
      swp_rdr_req_ntf_info.swp_rd_req_current_info.tech_mask |=
          NFA_TECHNOLOGY_MASK_A;
    }

    if ((swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask &
         NFA_TECHNOLOGY_MASK_B)) {
      swp_rdr_req_ntf_info.swp_rd_req_current_info.tech_mask |=
          NFA_TECHNOLOGY_MASK_B;
    }

    swp_rdr_req_ntf_info.swp_rd_req_current_info.src =
        swp_rdr_req_ntf_info.swp_rd_req_info.src;
    swp_rdr_req_ntf_info.swp_rd_state = STATE_SE_RDR_MODE_START_IN_PROGRESS;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: new ETSI state : STATE_SE_RDR_MODE_START_IN_PROGRESS", __func__);
  } else if ((swp_rdr_req_ntf_info.swp_rd_state ==
              STATE_SE_RDR_MODE_STOP_CONFIG) &&
             (swp_rdr_req_ntf_info.swp_rd_req_current_info.src ==
              swp_rdr_req_ntf_info.swp_rd_req_info.src)) {
    // pTransactionController->transactionEnd(TRANSACTION_REQUESTOR(etsiReader));
    swp_rdr_req_ntf_info.swp_rd_state = STATE_SE_RDR_MODE_STOP_IN_PROGRESS;
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: new ETSI state : STATE_SE_RDR_MODE_STOP_IN_PROGRESS", __func__);
  }
  swp_rdr_req_ntf_info.mMutex.unlock();
}

/*******************************************************************************
**
** Function:        etsiReaderConfig
**
** Description:     Configuring to Emvco Profile
**
** Returns:         Status - NFA_STATUS_FAILED
**                           NFA_STATUS_OK
**                           NFA_STATUS_INVALID_PARAM
**
*******************************************************************************/
tNFA_STATUS MposManager::etsiReaderConfig(int32_t eeHandle) {
  tNFC_STATUS status = NFA_STATUS_FAILED;
  SecureElement& se = SecureElement::getInstance();
  jboolean enable = true;
  const tNCI_DISCOVER_MAPS
      nfc_interface_mapping_uicc[NFC_SWP_RD_NUM_INTERFACE_MAP] = {
          /* Protocols that use Frame Interface do not need to be included in
             the interface mapping */
          {NCI_PROTOCOL_ISO_DEP, NCI_INTERFACE_MODE_POLL,
           NCI_INTERFACE_UICC_DIRECT_STAT}};

  const tNCI_DISCOVER_MAPS
      nfc_interface_mapping_ese[NFC_SWP_RD_NUM_INTERFACE_MAP] = {
          /* Protocols that use Frame Interface do not need to be included in
             the interface mapping */
          {NCI_PROTOCOL_ISO_DEP, NCI_INTERFACE_MODE_POLL,
           NCI_INTERFACE_ESE_DIRECT_STAT}};

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: Enter; eeHandle : 0x%4x", __func__, eeHandle);
  /* Setting up the emvco poll profile*/
  status = EmvCo_dosetPoll(enable);
  if (status != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: fail enable polling; error=0x%X", __func__,
                               status);
    return status;
  }

  if (eeHandle == se.EE_HANDLE_0xF4)  // UICC
  {
    SyncEventGuard guard(mDiscMapEvent);
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: mapping intf for UICC", __func__);
    status = NFC_DiscoveryMap(NFC_SWP_RD_NUM_INTERFACE_MAP,
                              (tNCI_DISCOVER_MAPS*)nfc_interface_mapping_uicc,
                              MposManager::discoveryMapCb);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail intf mapping for UICC; error=0x%X",
                                 __func__, status);
      return status;
    }
    status =
        mDiscMapEvent.wait(NFC_CMD_TIMEOUT) ? NFA_STATUS_OK : NFA_STATUS_FAILED;
  } else if (eeHandle == SecureElement::EE_HANDLE_0xF3) {  // ESE
    SyncEventGuard guard(mDiscMapEvent);
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: mapping intf for ESE", __func__);
    status = NFC_DiscoveryMap(NFC_SWP_RD_NUM_INTERFACE_MAP,
                              (tNCI_DISCOVER_MAPS*)nfc_interface_mapping_ese,
                              MposManager::discoveryMapCb);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail intf mapping for ESE; error=0x%X",
                                 __func__, status);
      return status;
    }
    status =
        mDiscMapEvent.wait(NFC_CMD_TIMEOUT) ? NFA_STATUS_OK : NFA_STATUS_FAILED;
  } else {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: UNKNOWN SOURCE!!! ", __func__);
    return NFA_STATUS_FAILED;
  }

  return NFA_STATUS_OK;
}

/*******************************************************************************
**
** Function:        etsiResetReaderConfig
**
** Description:     Configuring from Emvco profile to Nfc forum profile
**
** Returns:         Status
**
*******************************************************************************/
tNFA_STATUS MposManager::etsiResetReaderConfig() {
  tNFC_STATUS status = NFA_STATUS_FAILED;
  const tNCI_DISCOVER_MAPS
      nfc_interface_mapping_default[NFC_NUM_INTERFACE_MAP] = {
          /* Protocols that use Frame Interface do not need to be included in
             the interface mapping */
          {NCI_PROTOCOL_ISO_DEP, NCI_INTERFACE_MODE_POLL_N_LISTEN,
           NCI_INTERFACE_ISO_DEP},
          {NCI_PROTOCOL_NFC_DEP, NCI_INTERFACE_MODE_POLL_N_LISTEN,
           NCI_INTERFACE_NFC_DEP},
          {NCI_PROTOCOL_T3T, NCI_INTERFACE_MODE_LISTEN, NCI_INTERFACE_FRAME}};
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Enter", __func__);

  status = EmvCo_dosetPoll(false);
  if (status != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: fail enable polling; error=0x%X", __func__,
                               status);
    status = NFA_STATUS_FAILED;
  } else {
    SyncEventGuard guard(mDiscMapEvent);
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: mapping intf for DH", __func__);
    status =
        NFC_DiscoveryMap(NFC_NUM_INTERFACE_MAP,
                         (tNCI_DISCOVER_MAPS*)nfc_interface_mapping_default,
                         MposManager::discoveryMapCb);
    if (status != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: fail intf mapping for ESE; error=0x%X",
                                 __func__, status);
      return status;
    }
    status =
        mDiscMapEvent.wait(NFC_CMD_TIMEOUT) ? NFA_STATUS_OK : NFA_STATUS_FAILED;
  }
  return status;
}

/*******************************************************************************
**
** Function:        notifyEEReaderEvent
**
** Description:     Notify with the Reader event
**
** Returns:         None
**
*******************************************************************************/
void MposManager::notifyEEReaderEvent(etsi_rd_event_t evt) {
  SecureElement& se = SecureElement::getInstance();
  struct timespec mLastRfFieldToggle = se.getLastRfFiledToggleTime();

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter; event=%x", __func__, evt);

  swp_rdr_req_ntf_info.mMutex.lock();
  int ret = clock_gettime(CLOCK_MONOTONIC, &mLastRfFieldToggle);
  if (ret == -1) {
    LOG(ERROR) << StringPrintf("%s: clock_gettime failed", __func__);
    // There is no good choice here...
  }
  switch (evt) {
    case ETSI_READER_START_SUCCESS:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: ETSI_READER_START_SUCCESS", __func__);
      {
        mSwpReaderTimer.kill();
        /*
         * This is to give user a specific time window to wait for card to be
         *found and
         * Notify to user if no card found within the give interval of timeout.
         *
         *  configuring timeout.
         * */
        if (mRdrTagOpTimeout > 0)
          mSwpReaderTimer.set(ONE_SECOND_MS * mRdrTagOpTimeout,
                              MposManager::startStopSwpReaderProc);
      }
      break;
    case ETSI_READER_ACTIVATED:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: ETSI_READER_ACTIVATED", __func__);
      break;
    case ETSI_READER_STOP:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: ETSI_READER_STOP", __func__);
      pTransactionController->transactionEnd(TRANSACTION_REQUESTOR(etsiReader));

      if (se.mIsWiredModeOpen) se.NfccStandByOperation(STANDBY_TIMER_START);
      break;
    default:
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("%s: UNKNOWN EVENT ??", __func__);
      break;
  }

  swp_rdr_req_ntf_info.mMutex.unlock();

  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: exit", __func__);
}

/*******************************************************************************
**
** Function:        notifyMPOSReaderEvent
**
** Description:     Notify the Reader current status event to NFC service
**
** Returns:         None
**
*******************************************************************************/
void MposManager::notifyMPOSReaderEvent(mpos_rd_state_t aEvent) {
  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: enter; event type is %s", __FUNCTION__,
                      convertMposEventToString(aEvent));
  JNIEnv* e = NULL;

  ScopedAttach attach(mNativeData->vm, &e);
  if (e == NULL) {
    LOG(ERROR) << StringPrintf("%s: jni env is null", __FUNCTION__);
    return;
  }

  switch (aEvent) {
    case MPOS_READER_MODE_START:
      e->CallVoidMethod(mNativeData->manager,
                        gCachedMposManagerNotifyETSIReaderModeStartConfig,
                        (uint16_t)swp_rdr_req_ntf_info.swp_rd_req_info.src);
      break;
    case MPOS_READER_MODE_STOP:
      mSwpReaderTimer.kill();
      e->CallVoidMethod(mNativeData->manager,
                        gCachedMposManagerNotifyETSIReaderModeStopConfig,
                        mDiscNtfTimeout);
      break;
    case MPOS_READER_MODE_TIMEOUT:
      e->CallVoidMethod(mNativeData->manager,
                        gCachedMposManagerNotifyETSIReaderModeSwpTimeout,
                        mDiscNtfTimeout);
      break;
    case MPOS_READER_MODE_RESTART:
      e->CallVoidMethod(mNativeData->manager,
                        gCachedMposManagerNotifyETSIReaderRestart);
      break;
    default:

      break;
  }
}

void MposManager::hanldeEtsiReaderReqEvent(tNFA_EE_DISCOVER_REQ* aInfo) {
  /* Handle Reader over SWP.
   * 1. Check if the event is for Reader over SWP.
   * 2. IF yes than send this info(READER_REQUESTED_EVENT) till FWK level.
   * 3. Stop the discovery.
   * 4. MAP the proprietary interface for Reader over SWP.NFC_DiscoveryMap,
   *nfc_api.h
   * 5. start the discovery with reader req, type and DH configuration.
   *
   * 6. IF yes than send this info(STOP_READER_EVENT) till FWK level.
   * 7. MAP the DH interface for Reader over SWP. NFC_DiscoveryMap, nfc_api.h
   * 8. start the discovery with DH configuration.
   */
  swp_rdr_req_ntf_info.mMutex.lock();
  for (unsigned char xx = 0; xx < aInfo->num_ee; xx++) {
    // for each technology (A, B, F, B'), print the bit field that shows
    // what protocol(s) is support by that technology
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "EE[%u] Handle: 0x%04x  PA: 0x%02x  PB: 0x%02x", xx,
        aInfo->ee_disc_info[xx].ee_handle, aInfo->ee_disc_info[xx].pa_protocol,
        aInfo->ee_disc_info[xx].pb_protocol);

    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "swp_rd_state is %s",
        convertRdrStateToString(swp_rdr_req_ntf_info.swp_rd_state));
    if ((aInfo->ee_disc_info[xx].ee_req_op == NFC_EE_DISC_OP_ADD) &&
        (swp_rdr_req_ntf_info.swp_rd_state == STATE_SE_RDR_MODE_STOPPED ||
         swp_rdr_req_ntf_info.swp_rd_state == STATE_SE_RDR_MODE_START_CONFIG ||
         swp_rdr_req_ntf_info.swp_rd_state == STATE_SE_RDR_MODE_STOP_CONFIG) &&
        (aInfo->ee_disc_info[xx].pa_protocol == NCI_PROTOCOL_ISO_DEP ||
         aInfo->ee_disc_info[xx].pb_protocol == NCI_PROTOCOL_ISO_DEP)) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "NFA_RD_SWP_READER_REQUESTED  EE[%u] Handle: 0x%04x  PA: 0x%02x  PB: "
          "0x%02x",
          xx, aInfo->ee_disc_info[xx].ee_handle,
          aInfo->ee_disc_info[xx].pa_protocol,
          aInfo->ee_disc_info[xx].pb_protocol);

      swp_rdr_req_ntf_info.swp_rd_req_info.src =
          aInfo->ee_disc_info[xx].ee_handle;
      swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask = 0;
      swp_rdr_req_ntf_info.swp_rd_req_info.reCfg = false;

      if (!(swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask &
            NFA_TECHNOLOGY_MASK_A)) {
        if (aInfo->ee_disc_info[xx].pa_protocol == NCI_PROTOCOL_ISO_DEP) {
          swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask |=
              NFA_TECHNOLOGY_MASK_A;
          swp_rdr_req_ntf_info.swp_rd_req_info.reCfg = true;
        }
      }

      if (!(swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask &
            NFA_TECHNOLOGY_MASK_B)) {
        if (aInfo->ee_disc_info[xx].pb_protocol == NCI_PROTOCOL_ISO_DEP) {
          swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask |=
              NFA_TECHNOLOGY_MASK_B;
          swp_rdr_req_ntf_info.swp_rd_req_info.reCfg = true;
        }
      }

      if (swp_rdr_req_ntf_info.swp_rd_req_info.reCfg) {
        mSwpRdrReqTimer.kill();
        if (swp_rdr_req_ntf_info.swp_rd_state !=
            STATE_SE_RDR_MODE_STOP_CONFIG) {
          if (swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask !=
              (NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B)) {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                "swp_rd_state is %s  evt: NFA_RD_SWP_READER_REQUESTED "
                "mSwpRdrReqTimer start",
                convertRdrStateToString(swp_rdr_req_ntf_info.swp_rd_state));
            mSwpRdrReqTimer.set(rdr_req_handling_timeout, readerReqEventNtf);
            swp_rdr_req_ntf_info.swp_rd_state = STATE_SE_RDR_MODE_START_CONFIG;
          }
        }
        /*RestartReadermode procedure special case should not de-activate*/
        else if (swp_rdr_req_ntf_info.swp_rd_state ==
                 STATE_SE_RDR_MODE_STOP_CONFIG) {
          swp_rdr_req_ntf_info.swp_rd_state = STATE_SE_RDR_MODE_STARTED;
          /*RFDEACTIVATE_DISCOVERY*/
          NFA_Deactivate(false);
        }
        swp_rdr_req_ntf_info.swp_rd_req_info.reCfg = false;
      }
      break;
    } else if ((aInfo->ee_disc_info[xx].ee_req_op == NFC_EE_DISC_OP_REMOVE) &&
               ((swp_rdr_req_ntf_info.swp_rd_state ==
                 STATE_SE_RDR_MODE_STARTED) ||
                (swp_rdr_req_ntf_info.swp_rd_state ==
                 STATE_SE_RDR_MODE_START_CONFIG) ||
                (swp_rdr_req_ntf_info.swp_rd_state ==
                 STATE_SE_RDR_MODE_STOP_CONFIG) ||
                (swp_rdr_req_ntf_info.swp_rd_state ==
                 STATE_SE_RDR_MODE_ACTIVATED)) &&
               (aInfo->ee_disc_info[xx].pa_protocol == 0xFF ||
                aInfo->ee_disc_info[xx].pb_protocol == 0xFF)) {
      DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
          "NFA_RD_SWP_READER_STOP  EE[%u] Handle: 0x%04x  PA: 0x%02x  PB: "
          "0x%02x",
          xx, aInfo->ee_disc_info[xx].ee_handle,
          aInfo->ee_disc_info[xx].pa_protocol,
          aInfo->ee_disc_info[xx].pb_protocol);

      if (swp_rdr_req_ntf_info.swp_rd_req_info.src ==
          aInfo->ee_disc_info[xx].ee_handle) {
        if (aInfo->ee_disc_info[xx].pa_protocol == 0xFF) {
          if (swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask &
              NFA_TECHNOLOGY_MASK_A) {
            swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask &=
                ~NFA_TECHNOLOGY_MASK_A;
            swp_rdr_req_ntf_info.swp_rd_req_info.reCfg = true;
          }
        }

        if (aInfo->ee_disc_info[xx].pb_protocol == 0xFF) {
          if (swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask &
              NFA_TECHNOLOGY_MASK_B) {
            swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask &=
                ~NFA_TECHNOLOGY_MASK_B;
            swp_rdr_req_ntf_info.swp_rd_req_info.reCfg = true;
          }
        }

        if (swp_rdr_req_ntf_info.swp_rd_req_info.reCfg) {
          swp_rdr_req_ntf_info.swp_rd_state = STATE_SE_RDR_MODE_STOP_CONFIG;
          mSwpRdrReqTimer.kill();
          if (swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask) {
            DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
                "swp_rd_state is %s  evt: NFA_RD_SWP_READER_STOP "
                "mSwpRdrReqTimer start",
                convertRdrStateToString(swp_rdr_req_ntf_info.swp_rd_state));
            mSwpRdrReqTimer.set(rdr_req_handling_timeout, readerReqEventNtf);
          }
          swp_rdr_req_ntf_info.swp_rd_req_info.reCfg = false;
        }
      }
      break;
    }
  }
  swp_rdr_req_ntf_info.mMutex.unlock();
  if ((swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask == 0) ||
      (swp_rdr_req_ntf_info.swp_rd_req_info.tech_mask ==
       (NFA_TECHNOLOGY_MASK_A | NFA_TECHNOLOGY_MASK_B))) {
    if (swp_rdr_req_ntf_info.swp_rd_state == STATE_SE_RDR_MODE_STOP_CONFIG) {
      notifyMPOSReaderEvent(MPOS_READER_MODE_STOP);
    } else if (swp_rdr_req_ntf_info.swp_rd_state ==
               STATE_SE_RDR_MODE_START_CONFIG) {
      notifyMPOSReaderEvent(MPOS_READER_MODE_START);
    }
  }
}

void MposManager::discoveryMapCb(tNFC_DISCOVER_EVT event,
                                 tNFC_DISCOVER* p_data) {
  (void)event;
  (void)p_data;
  SyncEventGuard guard(mMposMgr.mDiscMapEvent);
  mMposMgr.mDiscMapEvent.notifyOne();
}

/*******************************************************************************
**
** Function:        getSwpRrdReqInfo
**
** Description:     get swp_rdr_req_ntf_info
**
** Returns:         swp_rdr_req_ntf_info
**
*******************************************************************************/
Rdr_req_ntf_info_t MposManager::getSwpRrdReqInfo() {
  LOG(ERROR) << StringPrintf("%s Enter", __func__);
  if (!nfcFL.nfcNxpEse || !nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s : nfcNxpEse or ETSI_READER not avaialble.Returning", __func__);
  }
  return swp_rdr_req_ntf_info;
}

/*******************************************************************************
**
** Function:        readerReqEventNtf
**
** Description:     This is used to send the reader start or stop request
**                  event to service
**
** Returns:         None
**
*******************************************************************************/
void MposManager::readerReqEventNtf(union sigval) {
  if (!nfcFL.nfcNxpEse || !nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: nfcNxpEse or ETSI_READER not available. Returning", __func__);
    return;
  }
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s:  ", __func__);
  JNIEnv* e = NULL;

  ScopedAttach attach(mMposMgr.mNativeData->vm, &e);
  if (e == NULL) {
    LOG(ERROR) << StringPrintf("%s: jni env is null", __func__);
    return;
  }

  Rdr_req_ntf_info_t mSwp_info = mMposMgr.getSwpRrdReqInfo();

  DLOG_IF(INFO, nfc_debug_enabled)
      << StringPrintf("%s: swp_rdr_req_ntf_info.swp_rd_req_info.src = 0x%4x ",
                      __func__, mSwp_info.swp_rd_req_info.src);

  if (mMposMgr.getEtsiReaederState() == STATE_SE_RDR_MODE_START_CONFIG) {
    e->CallVoidMethod(
        mMposMgr.mNativeData->manager,
        mMposMgr.gCachedMposManagerNotifyETSIReaderModeStartConfig,
        (uint16_t)mSwp_info.swp_rd_req_info.src);
  } else if (mMposMgr.getEtsiReaederState() == STATE_SE_RDR_MODE_STOP_CONFIG) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("%s: mSwpReaderTimer.kill() ", __func__);
    mMposMgr.mSwpReaderTimer.kill();
    e->CallVoidMethod(mMposMgr.mNativeData->manager,
                      mMposMgr.gCachedMposManagerNotifyETSIReaderModeStopConfig,
                      mDiscNtfTimeout);
  }
}

/*******************************************************************************
**
** Function:        startStopSwpReaderProc
**
** Description:     Notify the reader timeout
**
** Returns:         None
**
*******************************************************************************/
void MposManager::startStopSwpReaderProc(union sigval) {
  if (!nfcFL.nfcNxpEse || !nfcFL.eseFL._ESE_ETSI_READER_ENABLE) {
    DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(
        "%s: nfcNxpEse or ETSI_READER not enabled. Returning", __func__);
    return;
  }
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf("%s: Timeout!!!", __func__);

  mMposMgr.notifyMPOSReaderEvent(MPOS_READER_MODE_TIMEOUT);
}

/*******************************************************************************
**
** Function:        etsiReaderReStart
**
** Description:     Notify's the mPOS restart event
**
** Returns:         void.
**
*******************************************************************************/
void MposManager::etsiReaderReStart() {
  DLOG_IF(INFO, nfc_debug_enabled) << StringPrintf(" %s: Enter", __FUNCTION__);
  pTransactionController->transactionEnd(TRANSACTION_REQUESTOR(etsiReader));
  notifyMPOSReaderEvent(MPOS_READER_MODE_RESTART);
}

/*******************************************************************************
**
** Function:        validateHCITransactionEventParams
**
** Description:     Decodes the HCI_TRANSACTION_EVT to check for
**                  reader restart and POWER_OFF evt
**
** Returns:         OK/FAILED.
**
*******************************************************************************/
tNFA_STATUS MposManager::validateHCITransactionEventParams(uint8_t* aData,
                                                           int32_t aDatalen) {
  tNFA_STATUS status = NFA_STATUS_OK;
  uint8_t Event, Version, Code;
  if (aData != NULL && aDatalen >= 3) {
    Event = *aData++;
    Version = *aData++;
    Code = *aData;
    if (Event == EVENT_RF_ERROR && Version == EVENT_RF_VERSION) {
      if (Code == EVENT_RDR_MODE_RESTART) {
        status = NFA_STATUS_FAILED;
        etsiReaderReStart();
      } else {
      }
    }
  } else if (aData != NULL && aDatalen == 0x01 &&
             *aData == EVENT_EMV_POWER_OFF) {
    DLOG_IF(INFO, nfc_debug_enabled)
        << StringPrintf("Power off procedure to be triggered");

    if (NfcConfig::hasKey(NAME_NFA_CONFIG_FORMAT)) {
      unsigned long num = NfcConfig::getUnsigned(NAME_NFA_CONFIG_FORMAT);
      if (num == 0x05) {
        DLOG_IF(INFO, nfc_debug_enabled)
            << StringPrintf("Power off procedure is triggered");
        NFA_Deactivate(false);
      } else {
        // DO nothing
      }
    } else {
      DLOG_IF(INFO, nfc_debug_enabled)
          << StringPrintf("NAME_NFA_CONFIG_FORMAT not found");
    }
  } else {
  }
  return status;
}

/*******************************************************************************
**
** Function:        convertMposEventToString
**
** Description:     Converts the MPOS status events to String format
**
** Returns:         Name of the event
**
*******************************************************************************/
const char* MposManager::convertMposEventToString(mpos_rd_state_t aEvent) {
  switch (aEvent) {
    case MPOS_READER_MODE_INVALID:
      return "MPOS_READER_MODE_INVALID";
    case MPOS_READER_MODE_START:
      return "MPOS_READER_MODE_START";
    case MPOS_READER_MODE_START_SUCCESS:
      return "MPOS_READER_MODE_START_SUCCESS";
    case MPOS_READER_MODE_RESTART:
      return "MPOS_READER_MODE_RESTART";
    case MPOS_READER_MODE_STOP:
      return "MPOS_READER_MODE_STOP";
    case MPOS_READER_MODE_STOP_SUCCESS:
      return "MPOS_READER_MODE_STOP_SUCCESS";
    case MPOS_READER_MODE_TIMEOUT:
      return "MPOS_READER_MODE_TIMEOUT";
    case MPOS_READER_MODE_REMOVE_CARD:
      return "MPOS_READER_MODE_REMOVE_CARD";
    case MPOS_READER_MODE_RECOVERY:
      return "MPOS_READER_MODE_RECOVERY";
    case MPOS_READER_MODE_FAIL:
      return "MPOS_READER_MODE_FAIL";

    default:
      return "UNKNOWN";
  }
}

/*******************************************************************************
**
** Function:        convertRdrStateToString
**
** Description:     Converts the MPOS state to String format
**
** Returns:         Name of the event
**
*******************************************************************************/
const char* MposManager::convertRdrStateToString(se_rd_req_state_t aState) {
  switch (aState) {
    case STATE_SE_RDR_MODE_INVALID:
      return "STATE_SE_RDR_MODE_INVALID";
    case STATE_SE_RDR_MODE_START_CONFIG:
      return "STATE_SE_RDR_MODE_START_CONFIG";
    case STATE_SE_RDR_MODE_START_IN_PROGRESS:
      return "STATE_SE_RDR_MODE_START_IN_PROGRESS";
    case STATE_SE_RDR_MODE_STARTED:
      return "STATE_SE_RDR_MODE_STARTED";
    case STATE_SE_RDR_MODE_ACTIVATED:
      return "STATE_SE_RDR_MODE_ACTIVATED";
    case STATE_SE_RDR_MODE_STOP_CONFIG:
      return "STATE_SE_RDR_MODE_STOP_CONFIG";
    case STATE_SE_RDR_MODE_STOP_IN_PROGRESS:
      return "STATE_SE_RDR_MODE_STOP_IN_PROGRESS";
    case STATE_SE_RDR_MODE_STOPPED:
      return "STATE_SE_RDR_MODE_STOPPED";

    default:
      return "UNKNOWN";
  }
}
