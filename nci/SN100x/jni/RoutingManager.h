/*
 * Copyright (C) 2013 The Android Open Source Project
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

/*
 *  Manage the listen-mode routing table.
 */
/******************************************************************************
*
*  The original Work has been changed by NXP.
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
*  Copyright 2018-2020 NXP
*
******************************************************************************/
#pragma once
#include <vector>
#include "NfcJniUtil.h"
#include "RouteDataSet.h"
#include "SyncEvent.h"

#include <map>
#include "nfa_api.h"
#include "nfa_ee_api.h"
#if(NXP_EXTNS == TRUE)
#include "SecureElement.h"
#define TYPE_LENGTH_SIZE 0x02
#define TECHNOLOGY_BASED_ROUTING 0x00
#define PROTOCOL_BASED_ROUTING 0x01
#define AID_BASED_ROUTING 0x02
#define ROUTE_UICC AID_BASED_ROUTING
#endif
using namespace std;
#if(NXP_EXTNS == TRUE)
typedef struct
{
    uint8_t protocol;
    uint16_t routeLoc;
    uint8_t power;
    uint8_t enable;
} protoEntry_t;

typedef struct
{
    uint8_t technology;
    uint16_t routeLoc;
    uint8_t power;
    uint8_t enable;
} techEntry_t;

typedef struct
{
    uint16_t nfceeID;//ID for the route location
    tNFA_TECHNOLOGY_MASK    tech_switch_on;     /* default routing - technologies switch_on  */
    tNFA_TECHNOLOGY_MASK    tech_switch_off;    /* default routing - technologies switch_off */
    tNFA_TECHNOLOGY_MASK    tech_battery_off;   /* default routing - technologies battery_off*/
    tNFA_TECHNOLOGY_MASK    tech_screen_lock;    /* default routing - technologies screen_lock*/
    tNFA_TECHNOLOGY_MASK    tech_screen_off;   /* default routing - technologies screen_off*/
    tNFA_TECHNOLOGY_MASK    tech_screen_off_lock;  /* default routing - technologies screen_off_lock*/
    tNFA_PROTOCOL_MASK      proto_switch_on;    /* default routing - protocols switch_on     */
    tNFA_PROTOCOL_MASK      proto_switch_off;   /* default routing - protocols switch_off    */
    tNFA_PROTOCOL_MASK      proto_battery_off;  /* default routing - protocols battery_off   */
    tNFA_PROTOCOL_MASK      proto_screen_lock;   /* default routing - protocols screen_lock    */
    tNFA_PROTOCOL_MASK      proto_screen_off;  /* default routing - protocols screen_off  */
    tNFA_PROTOCOL_MASK      proto_screen_off_lock;  /* default routing - protocols screen_off_lock  */

} LmrtEntry_t;

typedef struct protoroutInfo {
    uint16_t ee_handle;
    tNFA_PROTOCOL_MASK  protocols_switch_on;
    tNFA_PROTOCOL_MASK  protocols_switch_off;
    tNFA_PROTOCOL_MASK  protocols_battery_off;
    tNFA_PROTOCOL_MASK  protocols_screen_lock;
    tNFA_PROTOCOL_MASK  protocols_screen_off;
    tNFA_PROTOCOL_MASK  protocols_screen_off_lock;
}ProtoRoutInfo_t;

typedef struct routeInfo {
    uint8_t num_entries;
    ProtoRoutInfo_t protoInfo[4];
}RouteInfo_t;
#endif
class RoutingManager {
 public:
#if(NXP_EXTNS == TRUE)
  uint32_t mDefaultGsmaPowerState;
  static const uint8_t HOST_PWR_STATE = 0x11;
#endif
  static RoutingManager& getInstance();
  bool initialize(nfc_jni_native_data* native);
  void deinitialize();
  void enableRoutingToHost();
  void disableRoutingToHost();
#if(NXP_EXTNS != TRUE)
  bool addAidRouting(const uint8_t* aid, uint8_t aidLen, int route,
                     int aidInfo);
#endif
  bool removeAidRouting(const uint8_t* aid, uint8_t aidLen);
  bool commitRouting();
  int registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen);
  void deregisterT3tIdentifier(int handle);
  void onNfccShutdown();
  int registerJniFunctions(JNIEnv* e);
  bool setNfcSecure(bool enable);
  void updateRoutingTable();
#if(NXP_EXTNS == TRUE)
    void getRouting(uint16_t* routeLen, uint8_t* routingBuff);
    void processGetRoutingRsp(tNFA_DM_CBACK_DATA* eventData);
    uint16_t getUiccRouteLocId(const int route);
    static const int NFA_SET_AID_ROUTING = 4;
    static const int NFA_SET_TECHNOLOGY_ROUTING = 1;
    static const int NFA_SET_PROTOCOL_ROUTING = 2;
    // Fixed power states masks
    static const int PWR_SWTCH_ON_SCRN_UNLCK_MASK = 0x01;
    static const int PWR_SWTCH_OFF_MASK = 0x02;
    static const int PWR_BATT_OFF_MASK = 0x04;
    static const int PWR_SWTCH_ON_SCRN_LOCK_MASK = 0x10;
    static const int PWR_SWTCH_ON_SCRN_OFF_MASK = 0x08;
    static const int PWR_SWTCH_ON_SCRN_OFF_LOCK_MASK = 0x20;
    static const int POWER_STATE_MASK = 0xFF;
    static const int HOST_SCREEN_STATE_MASK = 0x09;
    void registerProtoRouteEnrty(tNFA_HANDLE ee_handle,
                                 tNFA_PROTOCOL_MASK  protocols_switch_on,
                                 tNFA_PROTOCOL_MASK  protocols_switch_off,
                                 tNFA_PROTOCOL_MASK  protocols_battery_off,
                                 tNFA_PROTOCOL_MASK  protocols_screen_lock,
                                 tNFA_PROTOCOL_MASK  protocols_screen_off,
                                 tNFA_PROTOCOL_MASK  protocols_screen_off_lock
                                 );
    bool setRoutingEntry(int type, int value, int route, int power);
    bool clearRoutingEntry(int type);
    bool clearAidTable ();
    void setEmptyAidEntry(int route);
    void processTechEntriesForFwdfunctionality(void);
    void configureOffHostNfceeTechMask(void);
    void configureEeRegister(bool eeReg);
    void dumpTables(int);
    tNFA_HANDLE checkAndUpdateAltRoute(int& routeLoc);

    uint32_t getUicc2selected();
    bool addAidRouting(const uint8_t* aid, uint8_t aidLen,
                                   int route, int aidInfo, int power);
    bool checkAndUpdatePowerState(int& power);
    bool isNfceeActive(int routeLoc, tNFA_HANDLE& ActDevHandle);
    uint16_t sRoutingBuffLen;
    uint8_t* sRoutingBuff;
    SyncEvent       sNfaGetRoutingEvent;
    SyncEvent       mAidAddRemoveEvent;
#endif
 private:
  RoutingManager();
  ~RoutingManager();
  RoutingManager(const RoutingManager&);
  RoutingManager& operator=(const RoutingManager&);

  void handleData(uint8_t technology, const uint8_t* data, uint32_t dataLen,
                  tNFA_STATUS status);
  void notifyActivated(uint8_t technology);
  void notifyDeactivated(uint8_t technology);
  tNFA_TECHNOLOGY_MASK updateEeTechRouteSetting();
  void updateDefaultProtocolRoute();
  void updateDefaultRoute();

  // See AidRoutingManager.java for corresponding
  // AID_MATCHING_ constants

  // Every routing table entry is matched exact (BCM20793)
  static const int AID_MATCHING_EXACT_ONLY = 0x00;
  // Every routing table entry can be matched either exact or prefix
  static const int AID_MATCHING_EXACT_OR_PREFIX = 0x01;
  // Every routing table entry is matched as a prefix
  static const int AID_MATCHING_PREFIX_ONLY = 0x02;

  static void nfaEeCallback(tNFA_EE_EVT event, tNFA_EE_CBACK_DATA* eventData);
  static void stackCallback(uint8_t event, tNFA_CONN_EVT_DATA* eventData);
  static void nfcFCeCallback(uint8_t event, tNFA_CONN_EVT_DATA* eventData);

  static int com_android_nfc_cardemulation_doGetDefaultRouteDestination(
      JNIEnv* e);
  static int com_android_nfc_cardemulation_doGetDefaultOffHostRouteDestination(
      JNIEnv* e);
  static jbyteArray com_android_nfc_cardemulation_doGetOffHostUiccDestination(
      JNIEnv* e);
  static jbyteArray com_android_nfc_cardemulation_doGetOffHostEseDestination(
      JNIEnv* e);
  static int com_android_nfc_cardemulation_doGetAidMatchingMode(JNIEnv* e);
  static int com_android_nfc_cardemulation_doGetDefaultIsoDepRouteDestination(
      JNIEnv* e);
  std::vector<uint8_t> mRxDataBuffer;
  map<int, uint16_t> mMapScbrHandle;
  bool mSecureNfcEnabled;

  // Fields below are final after initialize()
  nfc_jni_native_data* mNativeData;
  int mDefaultOffHostRoute;
  vector<uint8_t> mOffHostRouteUicc;
  vector<uint8_t> mOffHostRouteEse;
  int mDefaultFelicaRoute;
  int mDefaultEe;
  int mDefaultIsoDepRoute;
  int mAidMatchingMode;
  int mNfcFOnDhHandle;
  bool mIsScbrSupported;
  uint16_t mDefaultSysCode;
  uint16_t mDefaultSysCodeRoute;
  uint8_t mDefaultSysCodePowerstate;
  bool mReceivedEeInfo;
  tNFA_EE_CBACK_DATA mCbEventData;
  tNFA_EE_DISCOVER_REQ mEeInfo;
  tNFA_TECHNOLOGY_MASK mSeTechMask;
  static const JNINativeMethod sMethods[];
  SyncEvent mEeRegisterEvent;
  SyncEvent mRoutingEvent;
  SyncEvent mEeUpdateEvent;
  SyncEvent mEeInfoEvent;
  SyncEvent mEeSetModeEvent;
#if(NXP_EXTNS == TRUE)
    //Currently 4 protocols supported namely T3T, ISO-DEP, ISO-7816, NFC-DEP(taken care internally by the libnfc stack)
    static const int MAX_PROTO_ENTRIES = 0x03;
    static const int PROTO_T3T_IDX     = 0x00;
    static const int PROTO_ISODEP_IDX  = 0x01;
    static const int PROTO_ISO7816_IDX = 0x02;
    //Currently 3 Technologies supported namely A,B,F
    static const int MAX_TECH_ENTRIES  = 0x03;
    static const int TECH_A_IDX        = 0x00;
    static const int TECH_B_IDX        = 0x01;
    static const int TECH_F_IDX        = 0x02;
    //Fixed number of Lmrt entries
    static const int MAX_ROUTE_LOC_ENTRIES  = 0x04;
    //Fixed route location Lmrt index
    static const int ROUTE_LOC_HOST_ID_IDX  = 0x00;
    static const int ROUTE_LOC_ESE_ID_IDX   = 0x01;
    static const int ROUTE_LOC_UICC1_ID_IDX = 0x02;
    static const int ROUTE_LOC_UICC2_ID_IDX = 0x03;
    //Fixed route location Lmrt entries
    static const int ROUTE_LOC_HOST_ID      = 0x400;
    //FIX THIS:static const int ROUTE_LOC_ESE_ID       = SecureElement::EE_HANDLE_0xF3;
    static const int ROUTE_LOC_ESE_ID       = 0x4C0;
    static const int ROUTE_LOC_UICC1_ID     = 0x402;
    static const int ROUTE_LOC_UICC1_ID_NCI2_0 = 0x480;
    //FIX THIS:static const int ROUTE_LOC_UICC2_ID     = SecureElement::EE_HANDLE_0xF8;
    //FIX THIS:static const int ROUTE_LOC_UICC3_ID     = SecureElement::EE_HANDLE_0xF9;
    static const int ROUTE_LOC_UICC2_ID     = 0x481;
    static const int ROUTE_LOC_UICC3_ID     = 0x482;
    static const int ROUTE_DISABLE          = 0x00;
    static const int ROUTE_DH               = 0x01;
    static const int ROUTE_ESE              = 0x02;
    int mHostListnTechMask;
    int mUiccListnTechMask;
    int mFwdFuntnEnable;
    uint32_t mDefaultIso7816SeID;
    uint32_t mDefaultIso7816Powerstate;
    uint32_t mDefaultTechASeID;
    uint32_t mDefaultTechFPowerstate;
    protoEntry_t mProtoTableEntries[MAX_PROTO_ENTRIES];
    techEntry_t mTechTableEntries[MAX_TECH_ENTRIES];
    LmrtEntry_t mLmrtEntries[MAX_ROUTE_LOC_ENTRIES];
    uint32_t mCeRouteStrictDisable;
    uint32_t mTechSupportedByEse;
    uint32_t mTechSupportedByUicc1;
    uint32_t mTechSupportedByUicc2;
    uint8_t mOffHostAidRoutingPowerState;
    uint8_t mHostListenTechMask;

#endif
};
