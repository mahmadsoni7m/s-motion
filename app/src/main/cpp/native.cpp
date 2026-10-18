// C++ (Android NDK): audio band analysis (Goertzel) + Vulkan capability probe.
#include <jni.h>
#include <vulkan/vulkan.h>
#include <cmath>
#include <vector>
#include <string>
#include <algorithm>

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_soni_motionstudio_Native_analyze(JNIEnv* env, jobject, jshortArray pcm, jint sr, jint fps, jint bands) {
    const jsize n = env->GetArrayLength(pcm);
    std::vector<jshort> d(n);
    env->GetShortArrayRegion(pcm, 0, n, d.data());
    const int hop = std::max(1, sr / fps);
    const int frames = n / hop;
    std::vector<float> out((size_t)std::max(frames, 1) * bands, 0.f);
    const int win = hop * 2;
    std::vector<float> hann(win);
    for (int i = 0; i < win; i++) hann[i] = 0.5f - 0.5f * std::cos(2.f * (float)M_PI * i / (win - 1));
    std::vector<float> coeff(bands);
    for (int b = 0; b < bands; b++) {
        float f = 40.f * std::pow(200.f, (float)b / (bands - 1));
        if (f > sr * 0.45f) f = sr * 0.45f;
        coeff[b] = 2.f * std::cos(2.f * (float)M_PI * f / sr);
    }
    std::vector<float> peak(bands, 1e-6f);
    for (int fr = 0; fr < frames; fr++) {
        const int start = fr * hop;
        const int len = std::min(win, n - start);
        for (int b = 0; b < bands; b++) {
            float s1 = 0, s2 = 0;
            for (int i = 0; i < len; i++) {
                float x = d[start + i] / 32768.f * hann[i];
                float s0 = x + coeff[b] * s1 - s2;
                s2 = s1; s1 = s0;
            }
            float power = s1 * s1 + s2 * s2 - coeff[b] * s1 * s2;
            float mag = std::sqrt(std::max(power, 0.f)) / len;
            out[(size_t)fr * bands + b] = mag;
            peak[b] = std::max(peak[b], mag);
        }
    }
    for (int fr = 0; fr < frames; fr++)
        for (int b = 0; b < bands; b++) {
            float& v = out[(size_t)fr * bands + b];
            v = std::pow(std::min(1.f, v / peak[b]), 0.8f);
        }
    jfloatArray res = env->NewFloatArray((jsize)out.size());
    env->SetFloatArrayRegion(res, 0, (jsize)out.size(), out.data());
    return res;
}

// Capability probe only: reports whether a Vulkan device exists. Rendering uses OpenGL ES.
extern "C" JNIEXPORT jstring JNICALL
Java_com_soni_motionstudio_Native_vulkanInfo(JNIEnv* env, jobject) {
    VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    ai.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &ai;
    VkInstance inst = VK_NULL_HANDLE;
    if (vkCreateInstance(&ci, nullptr, &inst) != VK_SUCCESS) return env->NewStringUTF("Vulkan: not available");
    uint32_t cnt = 0;
    vkEnumeratePhysicalDevices(inst, &cnt, nullptr);
    std::string s = "Vulkan: " + std::to_string(cnt) + " device(s)";
    if (cnt > 0) {
        std::vector<VkPhysicalDevice> devs(cnt);
        vkEnumeratePhysicalDevices(inst, &cnt, devs.data());
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(devs[0], &p);
        s += std::string(" - ") + p.deviceName;
    }
    vkDestroyInstance(inst, nullptr);
    return env->NewStringUTF(s.c_str());
}
