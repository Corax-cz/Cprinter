// SPDX-License-Identifier: GPL-3.0-or-later
// wprint — 无 CUPS 的 brlaser 编码核心（可在 Android .so 与 Windows hostref 中共用）
//
// 这一层刻意不依赖 JNI：JNI 只做参数搬运（bridge.cpp），真正的编码逻辑在这里。
// 好处是 PC 侧的参考实现（tools/hostref）可以链接同一份 .cpp，于是"字节级一致"
// 不是靠对齐，而是靠同一份代码 + 同一份输入。

#ifndef WPRINT_BRCORE_H
#define WPRINT_BRCORE_H

#include <cstdint>
#include <string>
#include <vector>

namespace brcore {

// 把外部纸张名归一到 brlaser 认识的 PJL 名（A4 / LETTER / LEGAL ...）。
// 不认识的一律回落 A4，不抛异常——打印链路不该因为一个字符串崩掉。
std::string normalizePaperSize(const std::string &in);

// 灰度（0=黑, 255=白）→ 1bpp 打包位图，bit=1 表示上墨。
// 使用 8x8 Bayer 有序抖动。行按 MSB-first 打包，行尾补零到字节边界。
// stride: 输入每行字节数（通常 == width）；输出每行宽度 = (width + 7) / 8。
std::vector<uint8_t> packGrayTo1bpp(const uint8_t *gray, int width, int height, int stride);

// 1bpp 打包位图 → Brother/PCL 字节流（PJL + PCL5 + 1030 压缩）。
// bytesPerLine 必须等于 (width + 7) / 8，且 <= 10000（与 brlaser 的校验一致）。
// 参数非法时返回空 vector，调用方必须当作失败处理。
std::vector<uint8_t> encodePacked(const uint8_t *packed, int height, int bytesPerLine,
                                  int dpi, bool duplex, const std::string &paperSize);

// 灰度 → 编码，一步到位（抖动 + 编码），供 JNI 主入口使用。
std::vector<uint8_t> encodeGray(const uint8_t *gray, int width, int height, int stride,
                                int dpi, bool duplex, const std::string &paperSize);

// 多页流式编码会话。
//
// 为什么需要这个：brlaser 的 job 只在「页面参数变化」时重发 PCL 头（job.cc 里
// `if (!(page_params_ == page_params)) write_page_header()`），页与页之间只有
// `1030M\f`。把两页当成两个独立字节流拼接，第二页会带上 128 个 0 + PJL 头 +
// `ESC E` 复位，打印机会把它当新作业——双面器根本不会启动。所以真正的双面打印
// 必须在同一个 job 内 encode_page 多次。
//
// 做成会话而不是「一次性传 N 页数组」，是为了 Stage 2：整本 PDF 的位图不可能
// 同时驻留内存（A4@600 一页灰度就 34MB）。每页用完即弃，峰值只有一页。
class JobSession {
 public:
  // 参数非法时 valid() 为 false，此时不要调用 addPage。
  JobSession(int dpi, bool duplex, int copies, const std::string &paperSize);
  ~JobSession();

  JobSession(const JobSession &) = delete;
  JobSession &operator=(const JobSession &) = delete;

  bool valid() const;

  // 追加一页（灰度输入，内部抖动 → 1bpp → 编码）。页尺寸必须与首保持一致。
  bool addGrayPage(const uint8_t *gray, int width, int height, int stride);
  bool addPackedPage(const uint8_t *packed, int width, int height);

  int pages() const;

  // 结束作业（写 EOJ）并取回完整字节流。调用后会话失效。
  std::vector<uint8_t> finish();

 private:
  struct Impl;
  Impl *impl_ = nullptr;
};

// Stage 1 黄金测试图案：白底 + 居中黑方块（边长 = min(w,h) * 3 / 10）。
// 放在 C++ 里是为了让 hostref 与 APK 生成逐字节相同的输入，排除"图案不一致"这个干扰项。
void makeTestPatternGray(uint8_t *out, int width, int height);

}  // namespace brcore

#endif  // WPRINT_BRCORE_H
