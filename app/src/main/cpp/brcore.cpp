// SPDX-License-Identifier: GPL-3.0-or-later
#include "brcore.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>

#include "brlaser/job.h"

namespace brcore {
namespace {

// 8x8 Bayer 有序抖动矩阵。0..63 表示"该位置的阈值档位"。
// 选有序抖动而非误差扩散：一维扫描 + 可复现，且编码器逐行回流，误差扩散需要跨行状态，
// 会让"同色块压缩"退化成大量细微差异，1030 压缩比明显变差。
const uint8_t kBayer8[8][8] = {
    { 0, 32,  8, 40,  2, 34, 10, 42},
    {48, 16, 56, 24, 50, 18, 58, 26},
    {12, 44,  4, 36, 14, 46,  6, 38},
    {60, 28, 52, 20, 62, 30, 54, 22},
    { 3, 35, 11, 43,  1, 33,  9, 41},
    {51, 19, 59, 27, 49, 17, 57, 25},
    {15, 47,  7, 39, 13, 45,  5, 37},
    {63, 31, 55, 23, 61, 29, 53, 21},
};

bool validDpi(int dpi) {
  return dpi == 300 || dpi == 600 || dpi == 1200;
}

}  // namespace

// ── JobSession：多页流式编码 ────────────────────────────────────────────────
struct JobSession::Impl {
  job *encoder = nullptr;
  FILE *sink = nullptr;
  char *buffer = nullptr;
  size_t length = 0;
  page_params params = {};
  int pages = 0;
  int pageHeight = 0;
  int pageBytesPerLine = 0;
  bool finished = false;

  bool addPacked(const uint8_t *packed, int height, int bytesPerLine) {
    if (!encoder || bytesPerLine <= 0 || bytesPerLine > 10000) return false;
    // 页尺寸变化会让 job 重发头（并可能打乱双面配对），因此显式拒绝。
    if (pages == 0) {
      pageHeight = height;
      pageBytesPerLine = bytesPerLine;
    } else if (height != pageHeight || bytesPerLine != pageBytesPerLine) {
      return false;
    }

    int row = 0;
    encoder->encode_page(
        params, height, bytesPerLine,
        [&](std::vector<uint8_t> &line) -> bool {
          if (row >= height) return false;
          if (line.size() != static_cast<size_t>(bytesPerLine)) line.resize(bytesPerLine);
          std::memcpy(line.data(),
                      packed + static_cast<size_t>(row) * static_cast<size_t>(bytesPerLine),
                      static_cast<size_t>(bytesPerLine));
          ++row;
          return true;
        });

    if (encoder->pages() != pages + 1) return false;
    ++pages;
    return true;
  }
};

JobSession::JobSession(int dpi, bool duplex, int copies, const std::string &paperSize) {
  impl_ = new Impl();
  if (!validDpi(dpi)) {
    delete impl_;
    impl_ = nullptr;
    return;
  }

  // brlaser 在页头写 `ESC&l%dX`，由打印机自己重复整个作业 —— 数据量不随份数增长。
  // 下限 1：0 和负数在 PCL 里没有意义，上游也只是 std::max(1, n) 兜一下。
  impl_->params.num_copies = copies < 1 ? 1 : copies;
  impl_->params.resolution = dpi;
  impl_->params.duplex = duplex;
  impl_->params.economode = false;
  impl_->params.sourcetray = "AUTO";
  impl_->params.mediatype = "PLAIN";
  impl_->params.papersize = normalizePaperSize(paperSize);

  impl_->sink = ::open_memstream(&impl_->buffer, &impl_->length);
  if (!impl_->sink) {
    delete impl_;
    impl_ = nullptr;
    return;
  }
  // job 在析构时写 EOJ，所以必须等 finish() 才销毁。
  impl_->encoder = new job(impl_->sink, "wprint");
}

JobSession::~JobSession() {
  if (!impl_) return;
  // 未 finish 就析构（异常路径）也要把资源收干净，否则 memstream 泄漏。
  delete impl_->encoder;
  if (impl_->sink) ::fclose(impl_->sink);
  std::free(impl_->buffer);
  delete impl_;
}

bool JobSession::valid() const {
  return impl_ && impl_->encoder && impl_->sink;
}

bool JobSession::addPackedPage(const uint8_t *packed, int width, int height) {
  if (!valid() || !packed || width <= 0 || height <= 0) return false;
  return impl_->addPacked(packed, height, (width + 7) / 8);
}

bool JobSession::addGrayPage(const uint8_t *gray, int width, int height, int stride) {
  if (!valid() || !gray || width <= 0 || height <= 0) return false;
  if (stride < width) return false;
  std::vector<uint8_t> packed = packGrayTo1bpp(gray, width, height, stride);
  if (packed.empty()) return false;
  return impl_->addPacked(packed.data(), height, (width + 7) / 8);
}

int JobSession::pages() const {
  return impl_ ? impl_->pages : 0;
}

std::vector<uint8_t> JobSession::finish() {
  if (!valid() || impl_->finished) return {};
  impl_->finished = true;

  delete impl_->encoder;  // 写 EOJ
  impl_->encoder = nullptr;
  if (::fclose(impl_->sink) != 0) {
    impl_->sink = nullptr;
    return {};
  }
  impl_->sink = nullptr;

  std::vector<uint8_t> result;
  if (impl_->buffer && impl_->length > 0) {
    result.assign(reinterpret_cast<const uint8_t *>(impl_->buffer),
                  reinterpret_cast<const uint8_t *>(impl_->buffer) + impl_->length);
  }
  std::free(impl_->buffer);
  impl_->buffer = nullptr;
  impl_->length = 0;

  // ── 短边双面 → 长边双面 ──────────────────────────────────────────────
  //
  // 上游 brlaser 在 duplex 时发 `ESC&l2S`，PCL 里 2 是「双面、短边装订」。
  // HL-2240D / LJ2600D 这类入门双面机的双面单元**只有长边翻页**，收到短边
  // 命令既不报错也不走异常，直接当单面打 —— 纸面现象是「选了双面，出来
  // 两张单面」：纸出来了、页数也对，只有装订方式是错的。
  //
  // 这和「白纸」是同一类失败：机芯静默丢弃自己不认识的设置。区别是白纸
  // 一眼能看出来，这个看起来像成功了。
  //
  // job.cc 是上游文件（约束：保持原样），所以在收尾处改字节。
  // 只替换第一处：那是 write_page_header() 发的页头命令；后面若出现同样的
  // 五字节，是 1030 压缩图像数据里的巧合，改了会毁掉那张图。
  if (impl_->params.duplex) {
    static const uint8_t kShortEdge[] = {'\033', '&', 'l', '2', 'S'};
    static const uint8_t kLongEdge[]  = {'\033', '&', 'l', '1', 'S'};
    auto it = std::search(result.begin(), result.end(),
                          kShortEdge, kShortEdge + sizeof(kShortEdge));
    if (it != result.end()) {
      std::copy(kLongEdge, kLongEdge + sizeof(kLongEdge), it);
    }
  }
  return result;
}

std::string normalizePaperSize(const std::string &in) {
  static const std::map<std::string, std::string> kAliases = {
      {"A4", "A4"},
      {"A5", "A5"},
      {"A6", "A6"},
      {"B5", "B5"},
      {"B6", "B6"},
      {"LETTER", "LETTER"},
      {"LEGAL", "LEGAL"},
      {"EXECUTIVE", "EXECUTIVE"},
      {"ENVC5", "C5"},
      {"C5", "C5"},
      {"ENVDL", "DL"},
      {"DL", "DL"},
      {"ENVMONARCH", "MONARCH"},
      {"MONARCH", "MONARCH"},
      {"ENVPRC5", "DL"},
  };
  std::string key;
  key.reserve(in.size());
  for (char c : in) {
    if (c == ' ' || c == '-' || c == '_') continue;
    key.push_back(static_cast<char>(::toupper(static_cast<unsigned char>(c))));
  }
  auto it = kAliases.find(key);
  return it == kAliases.end() ? std::string("A4") : it->second;
}

std::vector<uint8_t> packGrayTo1bpp(const uint8_t *gray, int width, int height, int stride) {
  if (!gray || width <= 0 || height <= 0 || stride < width) return {};

  const int bytesPerLine = (width + 7) / 8;
  std::vector<uint8_t> out(static_cast<size_t>(bytesPerLine) * static_cast<size_t>(height), 0);

  for (int y = 0; y < height; ++y) {
    const uint8_t *src = gray + static_cast<size_t>(y) * static_cast<size_t>(stride);
    uint8_t *dst = out.data() + static_cast<size_t>(y) * static_cast<size_t>(bytesPerLine);
    const uint8_t *bayer_row = kBayer8[y & 7];
    for (int x = 0; x < width; ++x) {
      // 阈值 2,6,10,...,254：把 64 档均匀铺满 0..255
      const int threshold = static_cast<int>(bayer_row[x & 7]) * 4 + 2;
      if (static_cast<int>(src[x]) < threshold) {
        dst[x >> 3] |= static_cast<uint8_t>(0x80u >> (x & 7));
      }
    }
  }
  return out;
}

std::vector<uint8_t> encodePacked(const uint8_t *packed, int height, int bytesPerLine,
                                  int dpi, bool duplex, const std::string &paperSize) {
  if (!packed || height <= 0 || bytesPerLine <= 0 || bytesPerLine > 10000) return {};
  if (!validDpi(dpi)) return {};

  page_params params = {};
  params.num_copies = 1;
  params.resolution = dpi;
  params.duplex = duplex;
  params.economode = false;
  params.sourcetray = "AUTO";
  params.mediatype = "PLAIN";
  params.papersize = normalizePaperSize(paperSize);

  char *buffer = nullptr;
  size_t length = 0;
  FILE *sink = ::open_memstream(&buffer, &length);
  if (!sink) return {};

  {
    // 关键：job 的写入目标是内存 FILE*，编码路径与桌面 brlaser 完全一致。
    job encoder(sink, "wprint");

    int row = 0;
    encoder.encode_page(
        params, height, bytesPerLine,
        [&](std::vector<uint8_t> &line) -> bool {
          if (row >= height) return false;
          if (line.size() != static_cast<size_t>(bytesPerLine)) line.resize(bytesPerLine);
          std::memcpy(line.data(),
                      packed + static_cast<size_t>(row) * static_cast<size_t>(bytesPerLine),
                      static_cast<size_t>(bytesPerLine));
          ++row;
          return true;
        });
    if (encoder.pages() != 1) {
      ::fclose(sink);
      std::free(buffer);
      return {};
    }
  }

  // job 析构时已写入 EOJ，这里再关闭流使 buffer 有效。
  if (::fclose(sink) != 0) {
    std::free(buffer);
    return {};
  }

  std::vector<uint8_t> result;
  if (buffer && length > 0) {
    result.assign(reinterpret_cast<const uint8_t *>(buffer),
                  reinterpret_cast<const uint8_t *>(buffer) + length);
  }
  std::free(buffer);
  return result;
}

std::vector<uint8_t> encodeGray(const uint8_t *gray, int width, int height, int stride,
                                int dpi, bool duplex, const std::string &paperSize) {
  std::vector<uint8_t> packed = packGrayTo1bpp(gray, width, height, stride);
  if (packed.empty()) return {};
  const int bytesPerLine = (width + 7) / 8;
  return encodePacked(packed.data(), height, bytesPerLine, dpi, duplex, paperSize);
}

void makeTestPatternGray(uint8_t *out, int width, int height) {
  if (!out || width <= 0 || height <= 0) return;
  std::memset(out, 255, static_cast<size_t>(width) * static_cast<size_t>(height));

  const int side = std::min(width, height) * 3 / 10;
  const int left = (width - side) / 2;
  const int top = (height - side) / 2;
  if (side <= 0) return;

  for (int y = 0; y < side; ++y) {
    std::memset(out + static_cast<size_t>(top + y) * static_cast<size_t>(width) + left, 0,
                static_cast<size_t>(side));
  }
}

}  // namespace brcore
