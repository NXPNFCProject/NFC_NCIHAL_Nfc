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
 ******************************************************************************/
#if (NXP_EXTNS == TRUE)
#include "NfcJniUtil.h"
#include "SyncEvent.h"
#include "nfa_api.h"
#define t4tNfcEe (NativeT4tNfcee::getInstance())

typedef enum { OP_READ = 0, OP_WRITE } T4TNFCEE_OPERATIONS_t;

typedef enum {
  STATUS_SUCCESS = 0,
  STATUS_FAILED = -1,
  ERROR_RF_ACTIVATED = -2,
  ERROR_MPOS_ON = -3,
  ERROR_NFC_NOT_ON = -4,
  ERROR_INVALID_FILE_ID = -5,
  ERROR_INVALID_LENGTH = -6,
  ERROR_CONNECTION_FAILED = -7,
  ERROR_EMPTY_PAYLOAD = -8,
  ERROR_NDEF_VALIDATION_FAILED = -9
} T4TNFCEE_STATUS_t;

class NativeT4tNfcee {
 public:
  SyncEvent mT4tNfceeMPOSEvt;
  /*****************************************************************************
  **
  ** Function:        getInstance
  **
  ** Description:     Get the NativeT4tNfcee singleton object.
  **
  ** Returns:         NativeT4tNfcee object.
  **
  *******************************************************************************/
  static NativeT4tNfcee& getInstance();

  /*******************************************************************************
  **
  ** Function:        t4tWriteData
  **
  ** Description:     Write the data into the T4T file of the specific file ID
  **
  ** Returns:         Return the size of data written
  **                  Return negative number of error code
  **
  *******************************************************************************/
  int t4tWriteData(JNIEnv* e, jobject o, jbyteArray fileId, jbyteArray data,
                   int length);

  /*******************************************************************************
  **
  ** Function:        t4tReadData
  **
  ** Description:     Read the data from the T4T file of the specific file ID.
  **
  ** Returns:         byte[] : all the data previously written to the specific
  **                  file ID.
  **                  Return one byte '0xFF' if the data was never written to
  *the
  **                  specific file ID,
  **                  Return null if reading fails.
  **
  *******************************************************************************/
  jbyteArray t4tReadData(JNIEnv* e, jobject o, jbyteArray fileId);

  /*******************************************************************************
  **
  ** Function:        t4tReadComplete
  **
  ** Description:     Updates read data to the waiting READ API
  **
  ** Returns:         none
  **
  *******************************************************************************/
  void t4tReadComplete(tNFA_STATUS status, tNFA_RX_DATA data);

  /*******************************************************************************
   **
   ** Function:        t4tWriteComplete
   **
   ** Description:     Returns write complete information
   **
   ** Returns:         none
   **
   *******************************************************************************/
  void t4tWriteComplete(tNFA_STATUS status, tNFA_RX_DATA data);

  /*******************************************************************************
   **
   ** Function:        isT4tNfceeBusy
   **
   ** Description:     Returns True if T4tNfcee operation is ongoing else false
   **
   ** Returns:         true/false
   **
   *******************************************************************************/
  bool isT4tNfceeBusy(void);

  /*******************************************************************************
  **
  ** Function:        t4tNfceeEventHandler
  **
  ** Description:     Handles callback events received from lower layer
  **
  ** Returns:         none
  **
  *******************************************************************************/
  void eventHandler(uint8_t event, tNFA_CONN_EVT_DATA* eventData);

 private:
  bool mBusy;
  static NativeT4tNfcee sNativeT4tNfceeInstance;
  SyncEvent mT4tNfcEeRWEvent;
  SyncEvent mT4tNfcEeWriteEvent;
  SyncEvent mT4tNfcEeEvent;
  tNFA_RX_DATA mReadData;
  tNFA_STATUS mWriteStatus;
  tNFA_STATUS mT4tNfcEeEventStat = NFA_STATUS_FAILED;
  NativeT4tNfcee();

  /*******************************************************************************
  **
  ** Function:        openConnection
  **
  ** Description:     Open T4T Nfcee Connection
  **
  ** Returns:         Status
  **
  *******************************************************************************/
  tNFA_STATUS openConnection();

  /*******************************************************************************
  **
  ** Function:        closeConnection
  **
  ** Description:     Close T4T Nfcee Connection
  **
  ** Returns:         Status
  **
  *******************************************************************************/
  tNFA_STATUS closeConnection();

/*******************************************************************************
**
** Function:        setup
**
** Description:     stops Discovery and opens T4TNFCEE connection
**
** Returns:         Status
**
*******************************************************************************/
tNFA_STATUS setup(void);

  /*******************************************************************************
  **
  ** Function:        cleanup
  **
  ** Description:     closes connection and starts discovery
  **
  ** Returns:         Status
  **
  *******************************************************************************/
  void cleanup(void);

  /*******************************************************************************
  **
  ** Function:        validatePreCondition
  **
  ** Description:     Runs precondition checks for requested operation
  **
  ** Returns:         Status
  **
  *******************************************************************************/
  T4TNFCEE_STATUS_t validatePreCondition(T4TNFCEE_OPERATIONS_t op,
                                         jbyteArray fileId,
                                         jbyteArray data = nullptr);

  /*******************************************************************************
   **
   ** Function:        setBusy
   **
   ** Description:     Sets busy flag indicating T4T operation is ongoing
   **
   ** Returns:         none
   **
   *******************************************************************************/
  void setBusy();

  /*******************************************************************************
   **
   ** Function:        resetBusy
   **
   ** Description:     Resets busy flag indicating T4T operation is completed
   **
   ** Returns:         none
   **
   *******************************************************************************/
  void resetBusy();
};
#endif