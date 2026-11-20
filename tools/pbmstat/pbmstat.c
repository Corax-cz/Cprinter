/*
 * pbmstat：统计 P4 位图的黑色包围盒与像素数。
 * 用来验证 brdecode 解出来的页面里，黑方块的位置和尺寸是否符合预期几何。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s file.pbm\n", argv[0]); return 2; }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror("fopen"); return 1; }

    char magic[3] = {0};
    int w = 0, h = 0;
    if (fscanf(f, "%2s", magic) != 1 || strcmp(magic, "P4") != 0) {
        fprintf(stderr, "not a P4 pbm\n");
        return 1;
    }
    if (fscanf(f, "%d %d", &w, &h) != 2) { fprintf(stderr, "bad header\n"); return 1; }
    /* 头部之后恰好一个空白字符 */
    fgetc(f);

    int stride = (w + 7) / 8;
    unsigned char *row = malloc(stride);
    if (!row) return 1;

    long count = 0;
    int minX = w, maxX = -1, minY = h, maxY = -1;
    for (int y = 0; y < h; y++) {
        if (fread(row, 1, stride, f) != (size_t)stride) { fprintf(stderr, "short read at row %d\n", y); return 1; }
        for (int x = 0; x < w; x++) {
            int bit = (row[x >> 3] >> (7 - (x & 7))) & 1;
            if (bit) {
                count++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
    }
    printf("size = %d x %d\n", w, h);
    printf("black pixels = %ld\n", count);
    if (count > 0) {
        printf("bbox x[%d..%d] y[%d..%d]\n", minX, maxX, minY, maxY);
        printf("bbox w x h = %d x %d\n", maxX - minX + 1, maxY - minY + 1);
        printf("bbox center = (%d, %d)\n", (minX + maxX) / 2, (minY + maxY) / 2);
    } else {
        printf("NO BLACK PIXELS - page is blank\n");
    }
    free(row);
    fclose(f);
    return 0;
}
