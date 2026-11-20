/*
 * pbm2bmp — 把 P4 (PBM) 位图转成 24 位 BMP，供 PC 侧直接查看。
 *
 * 为什么需要它：brdecode 能把 PCL 流反解回 PBM，但 PBM 是 1bpp 二值格式，
 * PC 上没有现成工具能看。转成 BMP 后可以直接看图，从而在**不依赖打印机**的
 * 前提下确认渲染是否正确（版面、边距、字重、抖动层次）。
 *
 * 用法: pbm2bmp <in.pbm> <out.bmp> [降采样倍数，默认 1]
 *
 * 编译: aarch64-linux-android29-clang -static -O2 pbm2bmp.c -o pbm2bmp
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* 跳过 PBM 头部的空白与注释，读一个整数 */
static int read_int(FILE *f) {
  int c;
  for (;;) {
    c = fgetc(f);
    if (c == '#') {
      while (c != '\n' && c != EOF) c = fgetc(f);
    } else if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
      continue;
    } else if (c == EOF) {
      return -1;
    } else {
      break;
    }
  }
  int v = 0;
  while (c >= '0' && c <= '9') {
    v = v * 10 + (c - '0');
    c = fgetc(f);
  }
  /* 数字读完时读到的是分隔符，必须推回，否则调用方再取一次就会吃掉数据首字节 */
  if (c != EOF) ungetc(c, f);
  return v;
}

static void put32(unsigned char *p, unsigned int v) {
  p[0] = v & 0xFF;
  p[1] = (v >> 8) & 0xFF;
  p[2] = (v >> 16) & 0xFF;
  p[3] = (v >> 24) & 0xFF;
}

int main(int argc, char **argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: %s <in.pbm> <out.bmp> [scale]\n", argv[0]);
    return 2;
  }
  int scale = argc > 3 ? atoi(argv[3]) : 1;
  if (scale < 1) scale = 1;

  FILE *in = fopen(argv[1], "rb");
  if (!in) {
    perror("open in");
    return 1;
  }

  char magic[3] = {0};
  if (fread(magic, 1, 2, in) != 2 || magic[0] != 'P' || magic[1] != '4') {
    fprintf(stderr, "not a P4 PBM\n");
    fclose(in);
    return 1;
  }
  int w = read_int(in);
  int h = read_int(in);
  if (w <= 0 || h <= 0) {
    fprintf(stderr, "bad size %dx%d\n", w, h);
    fclose(in);
    return 1;
  }
  fgetc(in); /* 头部与数据之间的单个空白 */

  const size_t stride = (size_t)((w + 7) / 8);
  unsigned char *bits = malloc(stride * h);
  if (!bits) {
    fprintf(stderr, "oom\n");
    fclose(in);
    return 1;
  }
  if (fread(bits, 1, stride * h, in) != stride * h) {
    fprintf(stderr, "short read\n");
    free(bits);
    fclose(in);
    return 1;
  }
  fclose(in);

  const int ow = (w + scale - 1) / scale;
  const int oh = (h + scale - 1) / scale;
  const size_t rowBytes = (size_t)((ow * 3 + 3) & ~3);
  const size_t imageBytes = rowBytes * oh;
  const unsigned int fileBytes = 54 + (unsigned int)imageBytes;

  unsigned char *out = calloc(1, fileBytes);
  if (!out) {
    fprintf(stderr, "oom\n");
    free(bits);
    return 1;
  }

  /* BMP 文件头 */
  out[0] = 'B';
  out[1] = 'M';
  put32(out + 2, fileBytes);
  put32(out + 10, 54);
  /* DIB 头 */
  put32(out + 14, 40);
  put32(out + 18, (unsigned)ow);
  put32(out + 22, (unsigned)oh); /* 正数 = bottom-up */
  out[26] = 1;
  out[28] = 24;
  put32(out + 34, (unsigned)imageBytes);
  put32(out + 38, 11811); /* 300 dpi ≈ 11811 px/m，仅供参考 */
  put32(out + 42, 11811);

  /*
   * 降采样必须用「块内黑点比例 → 灰度」，不能用「块内有任一黑点就算黑」。
   * 后者会把 40%~80% 密度的抖动区域全部压成纯黑，导致无法评估半色调层次——
   * 而灰度阶梯正是判断抖动质量的唯一目视依据。scale=1 时两者等价。
   */
  for (int y = 0; y < oh; ++y) {
    size_t rowOff = 54 + rowBytes * (size_t)(oh - 1 - y); /* bottom-up */
    for (int x = 0; x < ow; ++x) {
      int black = 0;
      int total = 0;
      for (int dy = 0; dy < scale; ++dy) {
        int sy = y * scale + dy;
        if (sy >= h) break;
        const unsigned char *src = bits + (size_t)sy * stride;
        for (int dx = 0; dx < scale; ++dx) {
          int sx = x * scale + dx;
          if (sx >= w) break;
          ++total;
          if ((src[sx >> 3] >> (7 - (sx & 7))) & 1) ++black;
        }
      }
      unsigned char v = total ? (unsigned char)(255 - 255 * black / total) : 0xFF;
      unsigned char *p = out + rowOff + (size_t)x * 3;
      p[0] = v;
      p[1] = v;
      p[2] = v;
    }
  }

  FILE *fo = fopen(argv[2], "wb");
  if (!fo) {
    perror("open out");
    free(bits);
    free(out);
    return 1;
  }
  fwrite(out, 1, fileBytes, fo);
  fclose(fo);
  fprintf(stderr, "wrote %s  %dx%d (from %dx%d, scale %d)\n", argv[2], ow, oh, w, h, scale);

  free(bits);
  free(out);
  return 0;
}
