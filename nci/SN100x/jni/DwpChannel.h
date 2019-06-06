/******************************************************************************
 *
 *  Copyright 2018-2019 NXP
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
#include "nfa_ee_api.h"
#include "Nxp_Features.h"
class DwpChannel
{
public:

/*******************************************************************************
**
** Function:        DwpChannel's get class instance
**
** Description:     Returns instance object of the class
**
** Returns:         DwpChannel instance.
**
*******************************************************************************/
static DwpChannel& getInstance ();


/*******************************************************************************
**
** Function:        finalize
**
** Description:     Release all resources.
**
** Returns:         None
**
*******************************************************************************/
void finalize();

/*******************************************************************************
**
** Function:        initialize
**
** Description:     Initialize all member variables.
**
** Returns:         None.
**
*******************************************************************************/
void Initialize();


private:

static DwpChannel sDwpChannel;

/*******************************************************************************
**
** Function:        DwpChannel Constructor
**
** Description:     Class constructor
**
** Returns:         None.
**
*******************************************************************************/
DwpChannel () ;

/*******************************************************************************
**
** Function:        DwpChannel Destructor
**
** Description:     Class destructor
**
** Returns:         None.
**
*******************************************************************************/
~DwpChannel () ;

};

/*******************************************************************************
**
** Function:        initialize
**
** Description:     Initialize the channel.
**
** Returns:         True if ok.
**
*******************************************************************************/
int16_t open();
bool close(int16_t mHandle);

bool transceive (uint8_t* xmitBuffer, int32_t xmitBufferSize, uint8_t* recvBuffer,
    int32_t recvBufferMaxSize, int32_t& recvBufferActualSize, int32_t timeoutMillisec);

void doeSE_Reset();
void doeSE_JcopDownLoadReset();
