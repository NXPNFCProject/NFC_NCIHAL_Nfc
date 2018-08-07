LOCAL_PATH:= $(call my-dir)

########################################
# NCI Configuration
########################################
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := \
        $(call all-java-files-under, src)

LOCAL_SRC_FILES += \
        $(call all-java-files-under, nci)

LOCAL_PACKAGE_NAME := NfcNci
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform

LOCAL_JNI_SHARED_LIBRARIES := libnfc_nci_jni

LOCAL_JAVA_LIBRARIES := com.nxp.nfc

LOCAL_STATIC_JAVA_LIBRARIES := android-support-core-utils
LOCAL_STATIC_JAVA_LIBRARIES +=android.hardware.secure_element-V1.0-java
LOCAL_STATIC_JAVA_LIBRARIES +=vendor.nxp.nxpwiredse-V1.0-java

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
