// SPDX-License-Identifier: GPL-3.0-or-later
// hostref —— 参考实现（黄金标准生成器）
//
// 它和 .so 共享同一份 brcore.cpp / brlaser/*.cc，唯一区别是不经过 JNI。
// 因此 hostref 与 APK 的输出若不一致，问题必然在桥接层（参数顺序、类型宽度、
// 缓冲区长度），而不是编码器本身。这就是"字节级一致"的可执行判据。
//
// 用法：hostref <outdir> [width] [height] [dpi] [duplex]

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "brcore.h"

namespace {

bool writeFile(const std::string &path, const std::vector<uint8_t> &data) {
  FILE *f = std::fopen(path.c_str(), "wb");
  if (!f) {
    std::fprintf(stderr, "cannot open %s\n", path.c_str());
    return false;
  }
  const size_t written = data.empty() ? 0 : std::fwrite(data.data(), 1, data.size(), f);
  std::fclose(f);
  if (written != data.size()) {
    std::fprintf(stderr, "short write on %s\n", path.c_str());
    return false;
  }
  return true;
}

std::string hexPrefix(const std::vector<uint8_t> &data, size_t n) {
  std::string out;
  char buf[4];
  for (size_t i = 0; i < n && i < data.size(); ++i) {
    std::snprintf(buf, sizeof(buf), "%02x ", data[i]);
    out += buf;
  }
  return out;
}

// FNV-1a 64 位：只为在没有 sha256 工具的环境里快速比对，不作安全用途。
uint64_t fnv1a(const std::vector<uint8_t> &data) {
  uint64_t h = 1469598103934665603ull;
  for (uint8_t b : data) {
    h ^= b;
    h *= 1099511628211ull;
  }
  return h;
}

}  // namespace

int main(int argc, char **argv) {
  const std::string outdir = argc > 1 ? argv[1] : ".";
  const int width = argc > 2 ? std::atoi(argv[2]) : 200;
  const int height = argc > 3 ? std::atoi(argv[3]) : 200;
  const int dpi = argc > 4 ? std::atoi(argv[4]) : 600;
  const bool duplex = argc > 5 && std::strcmp(argv[5], "duplex") == 0;
  const std::string paper = "A4";

  if (width <= 0 || height <= 0) {
    std::fprintf(stderr, "bad size\n");
    return 1;
  }

  std::vector<uint8_t> gray(static_cast<size_t>(width) * static_cast<size_t>(height), 255);
  brcore::makeTestPatternGray(gray.data(), width, height);

  std::vector<uint8_t> packed = brcore::packGrayTo1bpp(gray.data(), width, height, width);
  if (packed.empty()) {
    std::fprintf(stderr, "pack failed\n");
    return 1;
  }

  const int bytesPerLine = (width + 7) / 8;
  std::vector<uint8_t> viaPacked =
      brcore::encodePacked(packed.data(), height, bytesPerLine, dpi, duplex, paper);
  std::vector<uint8_t> viaGray =
      brcore::encodeGray(gray.data(), width, height, width, dpi, duplex, paper);

  if (viaPacked.empty() || viaGray.empty()) {
    std::fprintf(stderr, "encode failed\n");
    return 1;
  }
  const bool identical = viaPacked.size() == viaGray.size() &&
      std::memcmp(viaPacked.data(), viaGray.data(), viaPacked.size()) == 0;

  std::printf("size        : %d x %d @ %d dpi, duplex=%s\n", width, height, dpi,
              duplex ? "true" : "false");
  std::printf("gray bytes  : %zu\n", gray.size());
  std::printf("packed bytes: %zu (expect %d)\n", packed.size(), bytesPerLine * height);
  std::printf("stream bytes: %zu\n", viaGray.size());
  std::printf("fnv1a64     : %016llx\n", static_cast<unsigned long long>(fnv1a(viaGray)));
  std::printf("head        : %s\n", hexPrefix(viaGray, 64).c_str());
  std::printf("tail        : %s\n",
              hexPrefix(std::vector<uint8_t>(viaGray.end() - (viaGray.size() > 32 ? 32 : viaGray.size()),
                                             viaGray.end()), 32).c_str());
  std::printf("gray==packed: %s\n", identical ? "OK" : "MISMATCH");

  const std::string g = outdir + "/pattern_" + std::to_string(width) + "x" +
      std::to_string(height) + "_gray.bin";
  const std::string p = outdir + "/pattern_" + std::to_string(width) + "x" +
      std::to_string(height) + "_packed.bin";
  const std::string s = outdir + "/pattern_" + std::to_string(width) + "x" +
      std::to_string(height) + "_stream.bin";

  if (!writeFile(g, gray) || !writeFile(p, packed) || !writeFile(s, viaGray)) return 1;
  std::printf("written     : %s\n              %s\n              %s\n", g.c_str(), p.c_str(), s.c_str());

  return identical ? 0 : 2;
}
