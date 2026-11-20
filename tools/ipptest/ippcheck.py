"""IPP 自述一致性检查器。

存在的理由：这个项目里反复出现同一类 bug —— 服务器和自测工具用同一份错误
理解，两边自洽，测试永远是绿的，而真实客户端（iOS）一句话不说就放弃。
已经踩过的：操作码整体错位、urf-supported 类型错、printer-input-tray 当成
collection、同一属性发两遍、默认值不在支持集里。
最大的一个：「1setOf 的每个值都写全名」—— 真正的规则是**第 2..n 个值写空名**
（RFC 8010 §3.1.5）。这个检查器当初也按错的那条判，于是它和服务端一起错，
全绿了三个月；代价是 Android 默认打印服务的 canPrintPDF 恒为 0，打印机在
系统列表里根本不存在。所以现在 check_repeated_full_name 是硬性失败项。

这个脚本只做一件事：把 Get-Printer-Attributes 的响应当成**陌生人写的报文**
来审，逐条查「自述是否自洽」，不看任何自家常量。

用法：
    python tools/ipptest/ippcheck.py 192.168.123.60
退出码 0 = 全部通过。
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from ippclient import (  # noqa: E402
    Writer, base_operation, post, printer_uri,
    VERSION_2_0, OP_GET_PRINTER_ATTRIBUTES,
)
from iosprobe import TAGS  # noqa: E402

INT_TAGS = {0x21, 0x23, 0x22}       # integer / enum / boolean
STR_TAGS = {0x41, 0x42, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49}


def parse_strict(buf):
    """自己解，不借别人的格式化结果 —— 要的是原始字节。

    返回 [(group, tag, name, valueBytes)]；collection 内部成员的名字前缀 `·`。
    任何越界、截断、错位都抛异常：宁可在这里炸，也不要让客户端静默丢弃。
    """
    if len(buf) < 8:
        raise ValueError(f"响应过短：{len(buf)}")
    out = []
    p = 8
    group = "?"
    depth = 0
    while p < len(buf):
        tag = buf[p]
        if tag == 0x03:                       # end-of-attributes
            out.append((group, tag, "", b""))
            p += 1
            if p != len(buf):
                raise ValueError(f"end-of-attributes 之后还有 {len(buf) - p} 字节")
            return out
        if tag < 0x10:                        # 组分隔符
            group = {0x01: "operation", 0x02: "job", 0x04: "printer"}.get(
                tag, f"delim 0x{tag:02X}")
            out.append((group, tag, "", b""))
            depth = 0
            p += 1
            continue
        nlen = int.from_bytes(buf[p + 1:p + 3], "big")
        name = buf[p + 3:p + 3 + nlen].decode("utf-8", "replace")
        p += 3 + nlen
        vlen = int.from_bytes(buf[p:p + 2], "big")
        p += 2
        value = buf[p:p + vlen]
        if len(value) != vlen:
            raise ValueError(f"{name} 的值被截断：要 {vlen} 实到 {len(value)}")
        p += vlen
        # begCollection 的名字属于**外层**，所以要按自增前的深度记录；
        # endCollection 同理要按自减后的深度记录。搞反了会把 media-col-database
        # 当成 collection 成员，顶层就查不到它了。
        if tag == 0x34:
            out.append((group, f"{tag:02X}:{depth}", name, b""))
            depth += 1
            continue
        if tag == 0x37:
            depth -= 1
            out.append((group, f"{tag:02X}:{depth}", name, b""))
            continue
        out.append((group, f"{tag:02X}:{depth}", name, value))
    raise ValueError("报文没有 end-of-attributes 结尾")


def raw_tag(tagfield):
    """parse_strict 把 tag 存成 `0xNN:depth`，这里取回 tag 与深度。"""
    t, _, d = str(tagfield).partition(":")
    return int(t, 16), int(d or 0)


def as_value(tag, value):
    """把 value 归一成可比较的 Python 标量。"""
    if tag in INT_TAGS:
        return int.from_bytes(value, "big", signed=(tag == 0x21))
    if tag == 0x33:                                   # rangeOfInteger: 两个 4 字节
        return (int.from_bytes(value[0:4], "big", signed=True),
                int.from_bytes(value[4:8], "big", signed=True))
    if tag == 0x32:                                   # resolution: 9 字节
        return (
            int.from_bytes(value[0:4], "big"),
            int.from_bytes(value[4:8], "big"),
            value[8] if len(value) > 8 else 0,
        )
    if tag in STR_TAGS:
        return value.decode("utf-8", "replace")
    return value


problems = []
notes = []


def bad(msg):
    problems.append(msg)


def note(msg):
    notes.append(msg)


def fetch(host, port):
    w = base_operation(
        Writer(VERSION_2_0, OP_GET_PRINTER_ATTRIBUTES, 1), printer_uri(host, port),
    )
    # 刻意**不带** requested-attributes：要全量，才能审到每一条。
    _, resp = post(host, port, w.done())
    return resp


def collect(rows):
    """按属性名聚合成 {name: [(tag, value), ...]}，只收打印机组的**顶层**属性。

    只取 depth=0 的条目：collection 成员的重复是正常的（每条 media-col 都有
    自己的 media-size），把它们混进来会把「重复」误判成错误。

    顶层**空名**条目是 `1setOf` 的第 2..n 个值（RFC 8010 §3.1.5），要挂回上一个
    有名字的属性上。以前这里直接跳过它们，于是「多值」永远只数到 1 个 —— 服务端
    把多值写成重复全名（非法）时，这个检查器也恰好只认第一个，两边一起错，
    测试永远是绿的。Android 默认打印服务因此三年看不到这台机器。
    """
    out = {}
    group = "?"
    last = None
    for g, tagfield, name, value in rows:
        tag, depth = raw_tag(tagfield)
        if tag <= 0x0F:
            group = g
            last = None
            continue
        if group != "printer" or depth != 0:
            continue
        if not name:
            # 续值：归属到上一个属性。首个值就没有名字才是真错误，交给
            # check_not_empty_name 判。endCollection(0x37) 是 collection 的
            # 收尾标记，不是「第 n 个值」，算进去会让多值计数翻倍。
            if last is not None and tag != 0x37:
                out[last].append((tag, value))
            continue
        last = name
        out.setdefault(name, []).append((tag, value))
    return out



def check_duplicates(attrs):
    for name, vals in attrs.items():
        if len(vals) > 1:
            tags = {t for t, _ in vals}
            # 多值是合法的 1setOf；只有「同一属性重复且只有一个值该出现」才算问题。
            # 判据：多值属性的取值集合必须来自同一 tag，且属性本身允许 1setOf。
            if len(tags) != 1:
                bad(f"{name}: 同名属性用了 {len(tags)} 种不同类型 {sorted(tags)}")
            note(f"{name}: {len(vals)} 个值（1setOf）")


def check_not_empty_name(rows):
    """顶层空名只允许出现在**已有上一个属性**之后；开头就空名才是写坏了。

    RFC 8010 §3.1.5 明确要求 `1setOf` 的第 2..n 个值把 name-length 写成 0，
    所以「顶层出现空属性名」本身不是错。真正会害人的是两种：
      · 一口气第一个属性就没名字（无从归属，客户端整份作废）；
      · 同一个属性用了重复的全名（客户端只认第一个，见 check_duplicates 之外的
        单独一项：`check_repeated_full_name`）。
    """
    seen_named = False
    for g, tagfield, name, value in rows:
        tag, depth = raw_tag(tagfield)
        if tag <= 0x0F:
            seen_named = False
            continue
        if tag in (0x34, 0x37, 0x4A) or depth != 0:
            continue
        if name:
            seen_named = True
        elif not seen_named:
            bad(f"顶层第一个属性就是空名（tag=0x{tag:02X}），客户端无法归属，整份响应作废")


def check_repeated_full_name(rows):
    """同一个属性名连续出现两次（都带全名）是错写法，客户端只认第一个值。

    这条正是让 Android 默认打印服务看不到打印机的那个 bug 的判据：
    服务端把 `document-format-supported` 的 5 个值全写成带名字的记录，
    CUPS 的解析器只留下第一个 `image/urf`，于是 `canPrintPDF=0`。
    """
    prev = None
    for g, tagfield, name, value in rows:
        tag, depth = raw_tag(tagfield)
        if tag <= 0x0F:
            prev = None
            continue
        if depth != 0 or tag in (0x34, 0x37, 0x4A):
            continue
        if name and prev is not None and name == prev:
            bad(f"{name}: 连续两次写全名 —— 第 2..n 个值必须写空名（RFC 8010 §3.1.5），否则客户端只认第一个")
        prev = name if name else prev


def check_uri_triple(attrs):
    uris = attrs.get("printer-uri-supported", [])
    sec = attrs.get("uri-security-supported", [])
    auth = attrs.get("uri-authentication-supported", [])
    if not uris:
        bad("printer-uri-supported 缺失（RFC 8011 强制）")
        return
    if len(sec) != len(uris):
        bad(f"uri-security-supported 有 {len(sec)} 个值，printer-uri-supported 有 {len(uris)} 个 —— 必须相等")
    if len(auth) != len(uris):
        bad(f"uri-authentication-supported 有 {len(auth)} 个值，printer-uri-supported 有 {len(uris)} 个 —— 必须相等")


def check_defaults_in_supported(attrs):
    """所有 *-default 必须在对应的 *-supported 里 —— 这是「自述矛盾」最常见的一种。"""
    for name, vals in attrs.items():
        if not name.endswith("-default"):
            continue
        # collection 型的默认值（media-col-default）没法跟 media-col-supported
        # 比 "值属于集合" —— 后者列的是成员名，不是取值域。跳过。
        if any(t == 0x34 for t, _ in vals):
            continue
        base = name[: -len("-default")]
        sup_name = base + "-supported"
        sup = attrs.get(sup_name)
        if not sup:
            continue
        for t, v in vals:
            got = as_value(t, v)
            ok = False
            for st, sv in sup:
                if st == 0x33:                # rangeOfInteger：判区间
                    lo, hi = as_value(st, sv)
                    if lo <= got <= hi:
                        ok = True
                        break
                elif as_value(st, sv) == got:
                    ok = True
                    break
            if not ok:
                shown = [as_value(st, sv) for st, sv in sup]
                bad(f"{name} = {got!r} 不在 {sup_name} {shown} 里")


def check_types(attrs):
    """几处容易写错的类型（历史上真写错过）。"""
    expect = {
        "printer-input-tray": 0x30,
        "printer-output-tray": 0x30,
        "printer-supply": 0x30,
        "printer-icons": 0x45,
        "printer-strings-uri": 0x45,
        "printer-uuid": 0x45,
        "printer-state": 0x23,
        "printer-is-accepting-jobs": 0x22,
        "printer-state-reasons": 0x44,
        "urf-supported": 0x44,
        "ipp-features-supported": 0x44,
        "printer-kind": 0x44,
        "media-col-database": 0x34,
        "media-col-ready": 0x34,
        "media-col-default": 0x34,
        "printer-strings-languages-supported": 0x48,
    }
    for name, want in expect.items():
        vals = attrs.get(name)
        if not vals:
            continue
        for t, _ in vals:
            if t != want:
                bad(f"{name} 的 tag 是 0x{t:02X}（{TAGS.get(t, '?')}），应为 0x{want:02X}（{TAGS.get(want)}）")


def check_required_present(attrs):
    """IPP Everywhere 里「缺了就没法无驱动打印」的那几项。"""
    required = [
        "printer-name", "printer-state", "printer-state-reasons",
        "printer-is-accepting-jobs", "printer-uri-supported",
        "uri-security-supported", "uri-authentication-supported",
        "document-format-supported", "document-format-default",
        "media-supported", "media-default", "media-ready",
        "media-col-database", "media-col-ready", "media-col-default",
        "printer-resolution-supported", "printer-resolution-default",
        "print-color-mode-supported", "print-color-mode-default",
        "sides-supported", "sides-default",
        "print-quality-supported", "print-quality-default",
        "copies-supported", "copies-default",
        "operations-supported", "ipp-versions-supported",
        "ipp-features-supported", "printer-kind",
        "charset-configured", "charset-supported",
        "natural-language-configured", "generated-natural-language-supported",
        "printer-input-tray", "media-source-supported", "media-source-default",
        "printer-uuid", "printer-icons", "printer-strings-uri",
    ]
    for name in required:
        if name not in attrs:
            bad(f"缺少 {name}（IPP Everywhere / RFC 8011 要求）")


def check_operations(attrs):
    vals = attrs.get("operations-supported")
    if not vals:
        return
    ops = [as_value(t, v) for t, v in vals]
    if ops != sorted(ops):
        bad(f"operations-supported 未升序：{ops}")
    for need, label in ((2, "Print-Job"), (4, "Validate-Job"), (5, "Create-Job"),
                        (6, "Send-Document"), (9, "Get-Job-Attributes"),
                        (11, "Get-Printer-Attributes")):
        if need not in ops:
            bad(f"operations-supported 缺 {label}（{need}）")
    if 0x3B not in ops and 59 not in ops:
        bad("operations-supported 缺 Close-Job（0x003B）")


def check_urf_tokens(attrs):
    vals = attrs.get("urf-supported")
    if not vals:
        return
    # 每个值必须是一个**独立的** capability token。整串塞在一个值里（带逗号）
    # 是最隐蔽的一种错：客户端拿到的是一整个未知 keyword。
    for t, v in vals:
        s = v.decode("utf-8", "replace")
        if "," in s:
            bad(f"urf-supported 的某个值含逗号（{s!r}）—— 1setOf 必须拆成多个值")


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "192.168.123.60"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8631

    resp = fetch(host, port)
    print(f"响应 {len(resp)} 字节")
    rows = parse_strict(resp)
    # 结构自检：解析必须正好走到结尾
    attrs = collect(rows)
    print(f"打印机组顶层属性 {len(attrs)} 个\n")

    check_not_empty_name(rows)
    check_repeated_full_name(rows)
    check_duplicates(attrs)
    check_uri_triple(attrs)
    check_types(attrs)
    check_defaults_in_supported(attrs)
    check_required_present(attrs)
    check_operations(attrs)
    check_urf_tokens(attrs)

    if notes:
        print("── 多值属性 ──")
        for n in notes:
            print(f"  {n}")
        print()

    if problems:
        print(f"── 发现 {len(problems)} 个问题 ──")
        for p in problems:
            print(f"  ✗ {p}")
        return 1
    print("── 全部通过 ──")
    return 0


if __name__ == "__main__":
    sys.exit(main())
