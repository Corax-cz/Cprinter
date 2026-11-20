/*
 * 探针 v2：把 "谁在拒绝" 拆开。用法： usbprobe <node> [root|A|B]
 *
 *   root : 直接用 root 身份测（基线）
 *   A    : 主组设为 1018(usb)，无附加组
 *   B    : 主组 1000 + 附加组 {1018} —— 这是 system_server 的真实形态
 *
 * A 过、B 不过            -> 附加组在 VFS 里没生效
 * A/B 都不过、对照组过      -> 字符设备被 SELinux 拦（普通文件不受同一规则约束）
 * 全不过                  -> 通用 DAC 拒绝
 */
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void show(const char *tag, const char *p) {
    errno = 0;
    int a = access(p, R_OK | W_OK);
    int ae = errno;
    errno = 0;
    int fd = open(p, O_RDWR);
    int oe = errno;
    printf("%-16s access=%-3d(%-18s) open=%-3d(%s)\n", tag, a,
           a == 0 ? "ok" : strerror(ae), fd, fd >= 0 ? "ok" : strerror(oe));
    if (fd >= 0) close(fd);
}

static void dump_ids(const char *tag) {
    gid_t gs[32];
    int n = getgroups(32, gs);
    printf("[%s] uid=%d gid=%d groups=", tag, getuid(), getgid());
    for (int i = 0; i < n; i++) printf("%d ", gs[i]);
    printf("\n");
}

int main(int argc, char **argv) {
    if (argc < 3) { printf("usage: %s <node> root|A|B\n", argv[0]); return 2; }
    const char *p = argv[1];
    const char *mode = argv[2];

    if (!strcmp(mode, "root")) {
        dump_ids("root");
        show("root/dev", p);
        const char *ctrl = "/data/local/tmp/ctrlfile";
        int f = open(ctrl, O_CREAT | O_WRONLY | O_TRUNC, 0660);
        if (f >= 0) { ssize_t w = write(f, "x", 1); (void)w; close(f); }
        chown(ctrl, 0, 1018);
        chmod(ctrl, 0660);
        show("root/ctrl", ctrl);
        return 0;
    }

    if (!strcmp(mode, "A")) {
        gid_t none[1] = {0};
        setgroups(0, none);
        setgid(1018);
        setuid(1000);
    } else if (!strcmp(mode, "B")) {
        gid_t g[1] = {1018};
        setgroups(1, g);
        setgid(1000);
        setuid(1000);
    } else {
        printf("unknown mode\n");
        return 2;
    }

    dump_ids(mode);
    show(mode, p);
    show("ctrl", "/data/local/tmp/ctrlfile");
    return 0;
}
