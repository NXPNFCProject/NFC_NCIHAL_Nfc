/*
 * Copyright (C) 2024 The Android Open Source Project
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
#include <map>
#include <string>
#include <vector>

#include "NfcJniUtil.h"
#include "nfa_ee_api.h"

using namespace std;

#define MAX_NUM_NFCEE 0x06

struct mNfceeData {
  uint16_t mNfceeID[MAX_NUM_NFCEE];
  tNFA_EE_STATUS mNfceeStatus[MAX_NUM_NFCEE];
  uint8_t mNfceePresent;
};

/*****************************************************************************
**
**  Name:           NfceeManager
**
**  Description:    Manages NFC Execution Environments (NFCEE) by providing
**                  methods to initialize JNI elements,retrieve active NFCEE
**                  lists, and fetch NFCEE information from the NFC stack.
**
*****************************************************************************/
class NfceeManager {
 public:
  /*******************************************************************************
  **
  ** Function:        NfceeManager
  **
  ** Description:     Initialize member variables.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  NfceeManager();

  /*******************************************************************************
  **
  ** Function:        ~NfceeManager
  **
  ** Description:     Release all resources.
  **
  ** Returns:         None
  **
  *******************************************************************************/
  ~NfceeManager();

  /*******************************************************************************
  **
  ** Function:        getInstance
  **
  ** Description:     Get the singleton of this object.
  **
  ** Returns:         Reference to this object.
  **
  *******************************************************************************/
  static NfceeManager& getInstance();

  /*******************************************************************************
  **
  ** Function:        getActiveNfceeList
  **
  ** Description:     Get the list of Activated NFCEE.
  **                  e: Java Virtual Machine.
  **
  ** Returns:         List of Activated NFCEE.
  **
  *******************************************************************************/
  jobject getActiveNfceeList(JNIEnv* e);

  /*******************************************************************************
  **
  ** Function:        getNFCEeInfo
  **
  ** Description:     Get latest information about execution environments from
  *stack.
  **
  ** Returns:         True if at least 1 EE is available.
  **
  *******************************************************************************/
  bool getNFCEeInfo();

 private:
  static NfceeManager sNfceeManager;
  string eseName;
  string uiccName;
  tNFA_EE_INFO mEeInfo[MAX_NUM_NFCEE];
  uint8_t mNumEePresent;
  uint8_t mActualNumEe;
  mNfceeData mNfceeData_t;
  const char* mArrayListClassName = "java/util/ArrayList";
};