VOB_COMPONENTS := system/nfc/SN100x/src
NFA := $(VOB_COMPONENTS)/nfa
NFC := $(VOB_COMPONENTS)/nfc

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
include $(call all-makefiles-under,$(LOCAL_PATH))

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
    packages/apps/Nfc/nci/SN100x/jni/extns/pn54x/inc \
    packages/apps/Nfc/nci/SN100x/jni/extns/pn54x/src/utils \
    packages/apps/Nfc/nci/SN100x/jni \
    packages/apps/Nfc/nci/SN100x/jni/extns/pn54x/inc \
    packages/apps/Nfc/nci/SN100x/jni/extns/pn54x/src/common \
    $(LOCAL_PATH)/src/stag \
    $(LOCAL_PATH)/inc \
    hardware/nxp/nfc/SN100x/extns/impl
LOCAL_SHARED_LIBRARIES := \
    libnativehelper \
    libcutils \
    libutils \
    liblog \
    libchrome \
    libbase \
    libnfc_nci_jni

LOCAL_STATIC_LIBRARIES := libxml2

LOCAL_SRC_FILES := $(call all-subdir-cpp-files)
LOCAL_CFLAGS += -DNXP_EXTNS=TRUE
LOCAL_CFLAGS += -DDCHECK_ALWAYS_ON=TRUE
LOCAL_CFLAGS += -Wall -Wextra -Wno-unused-parameter -Werror
LOCAL_MODULE := libnfc_jni_extns

#LOCAL_STATIC_LIBRARIES := libxml2
include $(BUILD_SHARED_LIBRARY)
