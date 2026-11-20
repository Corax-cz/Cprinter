// SPDX-License-Identifier: GPL-3.0-or-later
// JNI 桥：只做参数搬运与错误上报，编码逻辑全部在 brcore / brlaser。
// 设计原则：JNI 层不含任何像素处理，这样 hostref（Windows）与 .so（Android）
// 走的是完全相同的代码路径。

#include <jni.h>

#include <cstring>
#include <mutex>
#include <new>
#include <set>
#include <string>
#include <vector>

#include "brcore.h"

namespace {

std::string toStdString(JNIEnv *env, jstring s) {
  if (!s) return std::string();
  const char *chars = env->GetStringUTFChars(s, nullptr);
  if (!chars) return std::string();
  std::string result(chars);
  env->ReleaseStringUTFChars(s, chars);
  return result;
}

jbyteArray toByteArray(JNIEnv *env, const std::vector<uint8_t> &data) {
  if (data.empty()) return nullptr;  // 调用方必须按失败处理
  jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
  if (!result) return nullptr;
  env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()),
                          reinterpret_cast<const jbyte *>(data.data()));
  return result;
}

// 把 Java 的 byte[] 拷进 vector。不用 GetPrimitiveArrayCritical：
// 编码过程会大量分配，临界区内做这件事容易长时间阻塞 GC。
bool copyFromByteArray(JNIEnv *env, jbyteArray src, std::vector<uint8_t> *out) {
  if (!src) return false;
  const jsize len = env->GetArrayLength(src);
  if (len <= 0) return false;
  out->resize(static_cast<size_t>(len));
  env->GetByteArrayRegion(src, 0, len, reinterpret_cast<jbyte *>(out->data()));
  return !env->ExceptionCheck();
}

void throwIllegalArgument(JNIEnv *env, const char *msg) {
  jclass clazz = env->FindClass("java/lang/IllegalArgumentException");
  if (clazz) env->ThrowNew(clazz, msg);
}

}  // namespace

extern "C" {

// 主入口。签名与项目规格书一致，不得更改。
JNIEXPORT jbyteArray JNICALL
Java_com_cawzen_printserver_print_NativeBr_nativeRender(
    JNIEnv *env, jobject /*thiz*/, jbyteArray pixels, jint width, jint height,
    jint bytesPerLine, jint dpi, jboolean duplex, jstring paperSize) {
  if (width <= 0 || height <= 0) {
    throwIllegalArgument(env, "width/height must be positive");
    return nullptr;
  }
  // bytesPerLine 在灰度入口上作为 stride 使用；若调用方传 0 则取 width。
  int stride = bytesPerLine > 0 ? static_cast<int>(bytesPerLine) : static_cast<int>(width);
  if (stride < width) {
    throwIllegalArgument(env, "bytesPerLine (stride) < width");
    return nullptr;
  }

  std::vector<uint8_t> buffer;
  if (!copyFromByteArray(env, pixels, &buffer)) {
    throwIllegalArgument(env, "pixels is empty or unreadable");
    return nullptr;
  }
  const size_t expected = static_cast<size_t>(stride) * static_cast<size_t>(height);
  if (buffer.size() < expected) {
    throwIllegalArgument(env, "pixels shorter than stride*height");
    return nullptr;
  }

  const std::string paper = toStdString(env, paperSize);
  std::vector<uint8_t> stream = brcore::encodeGray(buffer.data(), width, height, stride,
                                                   dpi, duplex == JNI_TRUE, paper);
  if (stream.empty()) {
    throwIllegalArgument(env, "encoding failed (bad dpi, or brlaser rejected the page)");
    return nullptr;
  }
  return toByteArray(env, stream);
}

// 供回归比对用：输入已是 1bpp 打包位图，跳过抖动，直接编码。
// hostref 用同一个函数产生参考输出，因此两者差异只可能来自编码器本身。
JNIEXPORT jbyteArray JNICALL
Java_com_cawzen_printserver_print_NativeBr_nativeRender1bpp(
    JNIEnv *env, jobject /*thiz*/, jbyteArray packed, jint width, jint height,
    jint dpi, jboolean duplex, jstring paperSize) {
  if (width <= 0 || height <= 0) {
    throwIllegalArgument(env, "width/height must be positive");
    return nullptr;
  }
  const int bytesPerLine = (width + 7) / 8;

  std::vector<uint8_t> buffer;
  if (!copyFromByteArray(env, packed, &buffer)) {
    throwIllegalArgument(env, "packed bitmap is empty or unreadable");
    return nullptr;
  }
  if (buffer.size() < static_cast<size_t>(bytesPerLine) * static_cast<size_t>(height)) {
    throwIllegalArgument(env, "packed bitmap shorter than bytesPerLine*height");
    return nullptr;
  }

  const std::string paper = toStdString(env, paperSize);
  std::vector<uint8_t> stream = brcore::encodePacked(buffer.data(), height, bytesPerLine,
                                                     dpi, duplex == JNI_TRUE, paper);
  if (stream.empty()) {
    throwIllegalArgument(env, "encoding failed");
    return nullptr;
  }
  return toByteArray(env, stream);
}

// 灰度 → 1bpp 打包，单独暴露，便于把 hostref 的输入落盘成可复现的 testdata。
JNIEXPORT jbyteArray JNICALL
Java_com_cawzen_printserver_print_NativeBr_nativePack1bpp(
    JNIEnv *env, jobject /*thiz*/, jbyteArray gray, jint width, jint height, jint stride) {
  if (width <= 0 || height <= 0) {
    throwIllegalArgument(env, "width/height must be positive");
    return nullptr;
  }
  const int effectiveStride = stride > 0 ? static_cast<int>(stride) : static_cast<int>(width);

  std::vector<uint8_t> buffer;
  if (!copyFromByteArray(env, gray, &buffer)) {
    throwIllegalArgument(env, "gray buffer is empty or unreadable");
    return nullptr;
  }
  if (buffer.size() < static_cast<size_t>(effectiveStride) * static_cast<size_t>(height)) {
    throwIllegalArgument(env, "gray buffer shorter than stride*height");
    return nullptr;
  }
  std::vector<uint8_t> packed =
      brcore::packGrayTo1bpp(buffer.data(), width, height, effectiveStride);
  if (packed.empty()) {
    throwIllegalArgument(env, "packing failed");
    return nullptr;
  }
  return toByteArray(env, packed);
}

// Stage 1 测试图案（白底 + 居中黑方块），C++ 侧生成以保证与 hostref 输入一致。
JNIEXPORT jbyteArray JNICALL
Java_com_cawzen_printserver_print_NativeBr_nativeTestPatternGray(
    JNIEnv *env, jobject /*thiz*/, jint width, jint height) {
  if (width <= 0 || height <= 0) {
    throwIllegalArgument(env, "width/height must be positive");
    return nullptr;
  }
  std::vector<uint8_t> gray(static_cast<size_t>(width) * static_cast<size_t>(height), 255);
  brcore::makeTestPatternGray(gray.data(), width, height);
  return toByteArray(env, gray);
}

// ── 多页会话 ───────────────────────────────────────────────────────────────
// 双面打印必须把 N 页塞进同一个 PCL job（否则打印机当成 N 个独立作业，双面器不动），
// 而整本 PDF 的位图又不能同时驻留内存，所以用句柄做流式：每页传完即编码、即可释放。
// 活跃句柄登记在集合里，release 后再用会安全返回而不是踩野指针。

namespace {
std::mutex g_jobsMutex;
std::set<brcore::JobSession *> g_jobs;
}  // namespace

JNIEXPORT jlong JNICALL
Java_com_cawzen_printserver_print_NativeBr_nativeJobBegin(
    JNIEnv *env, jobject /*thiz*/, jint dpi, jboolean duplex, jint copies,
    jstring paperSize) {
  auto *session = new (std::nothrow)
      brcore::JobSession(dpi, duplex == JNI_TRUE, copies, toStdString(env, paperSize));
  if (!session) return 0;
  if (!session->valid()) {
    delete session;
    throwIllegalArgument(env, "invalid dpi for job session");
    return 0;
  }
  {
    std::lock_guard<std::mutex> lock(g_jobsMutex);
    g_jobs.insert(session);
  }
  return reinterpret_cast<jlong>(session);
}

JNIEXPORT jboolean JNICALL
Java_com_cawzen_printserver_print_NativeBr_nativeJobAddPageGray(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jbyteArray gray, jint width, jint height,
    jint stride) {
  auto *session = reinterpret_cast<brcore::JobSession *>(handle);
  {
    std::lock_guard<std::mutex> lock(g_jobsMutex);
    if (g_jobs.find(session) == g_jobs.end()) {
      throwIllegalArgument(env, "job handle is not active");
      return JNI_FALSE;
    }
  }
  if (width <= 0 || height <= 0) {
    throwIllegalArgument(env, "width/height must be positive");
    return JNI_FALSE;
  }
  const int effectiveStride = stride > 0 ? static_cast<int>(stride) : static_cast<int>(width);
  if (effectiveStride < width) {
    throwIllegalArgument(env, "stride < width");
    return JNI_FALSE;
  }

  std::vector<uint8_t> buffer;
  if (!copyFromByteArray(env, gray, &buffer)) {
    throwIllegalArgument(env, "gray page is empty or unreadable");
    return JNI_FALSE;
  }
  if (buffer.size() < static_cast<size_t>(effectiveStride) * static_cast<size_t>(height)) {
    throwIllegalArgument(env, "gray page shorter than stride*height");
    return JNI_FALSE;
  }
  return session->addGrayPage(buffer.data(), width, height, effectiveStride) ? JNI_TRUE
                                                                            : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_cawzen_printserver_print_NativeBr_nativeJobPageCount(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
  auto *session = reinterpret_cast<brcore::JobSession *>(handle);
  std::lock_guard<std::mutex> lock(g_jobsMutex);
  if (g_jobs.find(session) == g_jobs.end()) return -1;
  return static_cast<jint>(session->pages());
}

// 正常结束：写 EOJ 并取回完整字节流，句柄随即失效（调用方不必再 abort）。
JNIEXPORT jbyteArray JNICALL
Java_com_cawzen_printserver_print_NativeBr_nativeJobEnd(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
  auto *session = reinterpret_cast<brcore::JobSession *>(handle);
  {
    std::lock_guard<std::mutex> lock(g_jobsMutex);
    auto it = g_jobs.find(session);
    if (it == g_jobs.end()) {
      throwIllegalArgument(env, "job handle is not active");
      return nullptr;
    }
    g_jobs.erase(it);
  }
  std::vector<uint8_t> stream = session->finish();
  delete session;
  if (stream.empty()) {
    throwIllegalArgument(env, "job produced no output (no pages added?)");
    return nullptr;
  }
  return toByteArray(env, stream);
}

// 异常路径：丢弃未完成的作业。可重复调用。
JNIEXPORT void JNICALL
Java_com_cawzen_printserver_print_NativeBr_nativeJobAbort(JNIEnv * /*env*/, jobject /*thiz*/,
                                                         jlong handle) {
  auto *session = reinterpret_cast<brcore::JobSession *>(handle);
  if (!session) return;
  {
    std::lock_guard<std::mutex> lock(g_jobsMutex);
    if (g_jobs.erase(session) == 0) return;
  }
  delete session;
}

}  // extern "C"
