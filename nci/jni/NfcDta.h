/*
 * Copyright 2022 NXP
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
#include <unordered_set>
#include <vector>

#include "nfa_api.h"

class NfcDta {
 public:
  /*******************************************************************************
  **
  ** Function:        getInstance
  **
  ** Description:     Get a reference to the singleton NfcDta object.
  **
  ** Returns:         Reference to NfcDta object.
  **
  *******************************************************************************/
  static NfcDta& getInstance();

  /*******************************************************************************
  **
  ** Function:        setNfccConfigParams
  **
  ** Description:     Set NCI config params from nfc.configTLV System Property.
  **
  ** Returns:         None.
  **
  *******************************************************************************/
  void setNfccConfigParams();

 private:
  std::vector<uint8_t> mDefaultTlv;
  std::unordered_set<uint8_t> mUpdatedParamIds;

  /*******************************************************************************
  **
  ** Function:        NfcDta
  **
  ** Description:     Initialize member variables.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  NfcDta();

  /*******************************************************************************
  **
  ** Function:        parseConfigParams
  **
  ** Description:     Parse config TLV from the String
  **
  ** Returns:         uint8_t vector
  **
  *******************************************************************************/
  std::vector<uint8_t> parseConfigParams(std::string configParams);

  /*******************************************************************************
  **
  ** Function:        getNonupdatedConfigParamIds
  **
  ** Description:     Get config parameter Ids whose values are not modified.
  **
  ** Returns:         config Parameter ids vector.
  **
  *******************************************************************************/
  std::vector<uint8_t> getNonupdatedConfigParamIds(
      std::vector<uint8_t> configTlv);

  /*******************************************************************************
  **
  ** Function:        getConfigParamValues
  **
  ** Description:     Read the current config parameter values.
  **
  ** Returns:         None.
  **
  *******************************************************************************/
  tNFA_STATUS getConfigParamValues(std::vector<uint8_t> paramIds);

  /*******************************************************************************
  **
  ** Function:        setConfigParams
  **
  ** Description:     Set config param with new value.
  **
  ** Returns:         NFA_STATUS_OK if success
  **
  *******************************************************************************/
  tNFA_STATUS setConfigParams(std::vector<uint8_t> configTlv);
};
