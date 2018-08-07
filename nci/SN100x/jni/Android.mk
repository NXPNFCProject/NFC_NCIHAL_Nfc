VOB_COMPONENTS := system/nfc/SN100x/src
NFA := $(VOB_COMPONENTS)/nfa
NFC := $(VOB_COMPONENTS)/nfc

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
include $(call all-makefiles-under,$(LOCAL_PATH))

ifneq ($(NCI_VERSION),)
LOCAL_CFLAGS += -DNCI_VERSION=$(NCI_VERSION) -O0 -g
endif
# set SRC_FILES_LIST to all the .cpp files in $(LOCAL_PATH)
SRC_FILES_LIST += $(wildcard $(LOCAL_PATH)/*.cpp $(LOCAL_PATH)/extns/pn54x/src/mifare/*.cpp)
SRC_FILES_LIST += $(wildcard $(LOCAL_PATH)/extns/pn54x/src/*.cpp $(LOCAL_PATH)/extns/pn54x/src/log/*.cpp)
SRC_FILES_LIST += $(wildcard $(LOCAL_PATH)/extns/pn54x/src/utils/*.cpp $(LOCAL_PATH)/extns/pn54x/src/common/*.cpp)

NXP_EXTNS:=TRUE
ifeq ($(NXP_EXTNS),TRUE)
 LOCAL_CFLAGS += -DNXP_EXTNS=TRUE
 LOCAL_SRC_FILES := $(call all-subdir-cpp-files) $(call all-subdir-c-files)
else
# exclude $(LOCAL_PATH)
LOCAL_CFLAGS += -DNXP_EXTNS=FALSE
SRC_FILES_LIST := $(filter-out $(LOCAL_PATH)/NativeSecureElement.cpp, $(SRC_FILES_LIST))
SRC_FILES_LIST := $(filter-out $(LOCAL_PATH)/SecureElement.cpp, $(SRC_FILES_LIST))
SRC_FILES_LIST := $(filter-out $(LOCAL_PATH)/DwpChannel.cpp, $(SRC_FILES_LIST))
SRC_FILES_LIST := $(filter-out $(LOCAL_PATH)/MposManager.cpp, $(SRC_FILES_LIST))
SRC_FILES_LIST := $(filter-out $(LOCAL_PATH)/NativeMposManager.cpp, $(SRC_FILES_LIST))
LOCAL_SRC_FILES := $(SRC_FILES_LIST:$(LOCAL_PATH)/%=%)
endif

NFC_NXP_ESE:= TRUE
LOCAL_CFLAGS += -DDCHECK_ALWAYS_ON=TRUE
LOCAL_CFLAGS += -Wall -Wextra -Wno-unused-parameter -Werror

LOCAL_C_INCLUDES += \
    external/libxml2/include \
    frameworks/native/include \
    $(NFA)/include \
    $(NFA)/brcm \
    $(NFC)/include \
    $(NFC)/brcm \
    $(NFC)/int \
    $(VOB_COMPONENTS)/hal/include \
    $(VOB_COMPONENTS)/hal/int \
    $(VOB_COMPONENTS)/include \
    $(VOB_COMPONENTS)/gki/ulinux \
    $(VOB_COMPONENTS)/gki/common \
    $(VOB_COMPONENTS) \
    system/nfc/SN100x/utils/include \
    hardware/nxp/nfc/extns/impl\

ifeq ($(NFC_NXP_ESE),TRUE)
LOCAL_C_INCLUDES +=external/p61-jcop-kit/include
endif

LOCAL_SHARED_LIBRARIES := \
    libicuuc \
    libnativehelper \
    libcutils \
    libutils \
    liblog \
    libnfc-nci \
    libchrome \
    libbase \

ifeq ($(NFC_NXP_ESE),TRUE)
LOCAL_SHARED_LIBRARIES += libp61-jcop-kit
endif

LOCAL_STATIC_LIBRARIES := libxml2

LOCAL_MODULE := libnfc_nci_jni

include $(BUILD_SHARED_LIBRARY)
