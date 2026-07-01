LOCAL_PATH := $(call my-dir)

# 预构建静态库
include $(CLEAR_VARS)
LOCAL_MODULE := aubio-static
LOCAL_SRC_FILES := $(TARGET_ARCH_ABI)/libaubio.a
include $(PREBUILT_STATIC_LIBRARY)

# 构建 pitch 模块
include $(CLEAR_VARS)
LOCAL_MODULE := pitch
LOCAL_SRC_FILES := pitch.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_STATIC_LIBRARIES := aubio-static
LOCAL_LDLIBS := -llog -lm
include $(BUILD_SHARED_LIBRARY)