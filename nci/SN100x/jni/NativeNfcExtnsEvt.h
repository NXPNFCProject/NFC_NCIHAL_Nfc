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
/*This class describes the interface object and functions to be
notified to external JNI about JNI events*/
#include "string"
#pragma once
class NativeNfcExtnsEvt {
 public:
  virtual int notifyNfcEvt(std::string evt, void* evt_data = NULL);
  virtual ~NativeNfcExtnsEvt();
};
