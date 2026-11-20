#!/usr/bin/env python3
"""IPP 客户端 / 协议探针（Stage 3 验收工具）。

用途：在没有 CUPS、没有 Windows 打印机队列的情况下，直接构造 IPP 报文
打给手机上的服务器，验证「协议层对不对」这件事——把「协议不通」和
「USB 出纸有问题」两个故障域彻底分开。

用法：
    python ippclient.py attrs  [--host IP] [--port 8631]
    python ippclient.py validate <file.pdf>
    python ippclient.py print  <file.pdf> [--duplex] [--dpi 300|600]
    python ippclient.py airprint <file.urf>      # iOS 的 Create-Job/Send-Document/Close-Job
    python ippclient.py create / send <id> <file> / close <id>
    python ippclient.py job    <job-id>
    python ippclient.py jobs

只依赖标准库（urllib + struct），不需要装任何东西。
"""

import argparse
import socket
import struct
import sys

# ── 常量（与 app 侧 ipp/Ipp.kt 保持一致）────────────────────────────────────
VERSION_1_1 = 0x0101
VERSION_2_0 = 0x0200

OP_PRINT_JOB = 0x0002
OP_PRINT_URI = 0x0003
OP_VALIDATE_JOB = 0x0004
OP_CREATE_JOB = 0x0005
OP_SEND_DOCUMENT = 0x0006
OP_SEND_URI = 0x0007
# Close-Job 是 0x003B，不是 0x0007。0x0007 是 Send-URI。
# 之前这里和服务器一起写错，两边自洽所以测试一直是绿的，但 iOS 发 0x3B 会被拒。
# 同理 Validate/Create/Send-Document 三个曾连着错位一格（0x03/0x04/0x05）——
# 那是更隐蔽的一类：真实客户端按标准值发，服务器按错值分派，整条流程错位，
# 却因为探针工具同步错着而全部「通过」。改动这边的常量必须同时改 Ipp.kt。
OP_CLOSE_JOB = 0x003B
OP_CANCEL_JOB = 0x0008
OP_GET_JOB_ATTRIBUTES = 0x0009
OP_GET_JOBS = 0x000A
OP_GET_PRINTER_ATTRIBUTES = 0x000B

GROUP_OPERATION = 0x01
GROUP_JOB = 0x02
GROUP_END = 0x03
GROUP_PRINTER = 0x04
GROUP_UNSUPPORTED = 0x05

TAG_INTEGER = 0x21
TAG_BOOLEAN = 0x22
TAG_ENUM = 0x23
TAG_OCTET = 0x30
TAG_DATETIME = 0x31
TAG_RESOLUTION = 0x32
TAG_RANGE = 0x33
TAG_BEG_COLLECTION = 0x34
TAG_END_COLLECTION = 0x37
TAG_MEMBER_NAME = 0x4A
TAG_TEXT = 0x41
TAG_NAME = 0x42
TAG_KEYWORD = 0x44
TAG_URI = 0x45
TAG_CHARSET = 0x47
TAG_LANGUAGE = 0x48
TAG_MIME = 0x49

GROUP_NAMES = {
    GROUP_OPERATION: "operation-attributes",
    GROUP_JOB: "job-attributes",
    GROUP_PRINTER: "printer-attributes",
    GROUP_UNSUPPORTED: "unsupported-attributes",
}

TAG_NAMES = {
    TAG_INTEGER: "integer", TAG_BOOLEAN: "boolean", TAG_ENUM: "enum",
    TAG_OCTET: "octetString", TAG_DATETIME: "dateTime", TAG_RESOLUTION: "resolution",
    TAG_RANGE: "rangeOfInteger", TAG_TEXT: "text", TAG_NAME: "name",
    TAG_KEYWORD: "keyword", TAG_URI: "uri", TAG_CHARSET: "charset",
    TAG_LANGUAGE: "naturalLanguage", TAG_MIME: "mimeMediaType",
    TAG_BEG_COLLECTION: "collection", TAG_MEMBER_NAME: "memberAttrName",
    TAG_END_COLLECTION: "endCollection",
}

STATUS_NAMES = {
    0x0000: "successful-ok",
    0x0001: "successful-ok-ignored-or-substituted-attributes",
    0x0400: "client-error-bad-request",
    0x0401: "client-error-forbidden",
    0x0404: "client-error-not-possible",
    0x0406: "client-error-not-found",
    0x040A: "client-error-document-format-not-supported",
    0x040B: "client-error-attributes-or-values-not-supported",
    0x0500: "server-error-internal-error",
    0x0501: "server-error-operation-not-supported",
    0x0503: "server-error-version-not-supported",
    0x0506: "server-error-not-accepting-jobs",
}


# ── 编码 ────────────────────────────────────────────────────────────────────
class Writer:
    def __init__(self, version=VERSION_2_0, op_or_status=0, request_id=0):
        self.buf = bytearray()
        self.u8(version >> 8)
        self.u8(version & 0xFF)
        self.u16(op_or_status)
        self.u32(request_id)

    def u8(self, v):
        self.buf += struct.pack(">B", v & 0xFF)

    def u16(self, v):
        self.buf += struct.pack(">H", v & 0xFFFF)

    def u32(self, v):
        self.buf += struct.pack(">I", v & 0xFFFFFFFF)

    def group(self, tag):
        self.u8(tag)
        return self

    def attr(self, tag, name, value: bytes):
        n = name.encode("utf-8")
        self.u8(tag)
        self.u16(len(n))
        self.buf += n
        self.u16(len(value))
        self.buf += value
        return self

    def integer(self, name, v):
        return self.attr(TAG_INTEGER, name, struct.pack(">i", v))

    def enumv(self, name, v):
        return self.attr(TAG_ENUM, name, struct.pack(">i", v))

    def boolean(self, name, v):
        return self.attr(TAG_BOOLEAN, name, b"\x01" if v else b"\x00")

    def keyword(self, name, v):
        return self.attr(TAG_KEYWORD, name, v.encode("utf-8"))

    def text(self, name, v):
        return self.attr(TAG_TEXT, name, v.encode("utf-8"))

    def namev(self, name, v):
        return self.attr(TAG_NAME, name, v.encode("utf-8"))

    def uri(self, name, v):
        return self.attr(TAG_URI, name, v.encode("utf-8"))

    def charset(self, name, v):
        return self.attr(TAG_CHARSET, name, v.encode("utf-8"))

    def language(self, name, v):
        return self.attr(TAG_LANGUAGE, name, v.encode("utf-8"))

    def mime(self, name, v):
        return self.attr(TAG_MIME, name, v.encode("utf-8"))

    def resolution(self, name, x, y):
        return self.attr(TAG_RESOLUTION, name, struct.pack(">iiB", x, y, 3))

    def done(self):
        self.u8(GROUP_END)
        return bytes(self.buf)


# ── 解码 ────────────────────────────────────────────────────────────────────
def _read_record(raw: bytes, p: int):
    """读一条属性/成员记录，返回 (tag, name, value, 新位置)。"""
    tag = raw[p]
    p += 1
    if p + 2 > len(raw):
        return tag, "", b"", len(raw)
    (name_len,) = struct.unpack(">H", raw[p:p + 2])
    p += 2
    name = raw[p:p + name_len].decode("utf-8", "replace") if name_len else ""
    p += name_len
    if p + 2 > len(raw):
        return tag, name, b"", len(raw)
    (val_len,) = struct.unpack(">H", raw[p:p + 2])
    p += 2
    value = raw[p:p + val_len]
    p += val_len
    return tag, name, value, p


def _read_members(raw: bytes, p: int, depth: int = 0):
    """读集合成员直到 endCollection，返回 (成员字典, 新位置, 是否正常收敛)。

    集合里成员名用 0x4A(memberAttrName) 单独一条记录，紧跟其后的那条记录
    才是成员的值；值本身也可能是嵌套集合（如 media-col 里的 media-size）。
    """
    members = {}
    pending = ""
    while p < len(raw):
        if depth > 8:                      # 病态报文兜底，别递归到栈溢出
            return members, p, False
        tag, name, val, p = _read_record(raw, p)
        if tag == TAG_END_COLLECTION:
            return members, p, True
        if tag < 0x10:                     # 撞到组标签 = 集合没闭合
            return members, p, False
        if tag == TAG_MEMBER_NAME:
            pending = val.decode("utf-8", "replace")
            continue
        if tag == TAG_BEG_COLLECTION:
            sub, p, _ok = _read_members(raw, p, depth + 1)
            entry = (TAG_BEG_COLLECTION, sub)
        else:
            entry = (tag, val)
        members.setdefault(pending, []).append(entry)
        pending = ""
    return members, p, False


def parse(raw: bytes):
    if len(raw) < 8:
        raise ValueError("报文太短")
    version, status, req_id = struct.unpack(">HHI", raw[:8])
    groups, cur_tag, cur, last_name = [], None, {}, ""
    p = 8
    while p < len(raw):
        tag = raw[p]
        if tag == GROUP_END:
            if cur_tag is not None:
                groups.append((cur_tag, cur))
            break
        if tag < 0x10:
            p += 1
            if cur_tag is not None:
                groups.append((cur_tag, cur))
            cur_tag, cur, last_name = tag, {}, ""
            continue
        tag, name, value, p = _read_record(raw, p)
        key = name or last_name
        if tag == TAG_BEG_COLLECTION:
            members, p, _ok = _read_members(raw, p)
            cur.setdefault(key, []).append((TAG_BEG_COLLECTION, members))
        else:
            cur.setdefault(key, []).append((tag, value))
        last_name = key
    return version, status, req_id, groups


def render(tag, value) -> str:
    if tag == TAG_BEG_COLLECTION:
        return "(collection)"
    if tag in (TAG_INTEGER, TAG_ENUM):
        return str(struct.unpack(">i", value)[0])
    if tag == TAG_BOOLEAN:
        return "true" if value and value[0] else "false"
    if tag == TAG_RESOLUTION and len(value) >= 9:
        x, y, u = struct.unpack(">iiB", value[:9])
        return f"{x}x{y} {'dpi' if u == 3 else 'dpcm'}"
    if tag == TAG_RANGE and len(value) >= 8:
        lo, hi = struct.unpack(">ii", value[:8])
        return f"{lo}..{hi}"
    return value.decode("utf-8", "replace")


def _dump_members(members, indent: int):
    pad = " " * indent
    for mname, mvalues in members.items():
        for tag, val in mvalues:
            if tag == TAG_BEG_COLLECTION:
                print(f"{pad}{mname} (collection)")
                _dump_members(val, indent + 2)
            else:
                print(f"{pad}{mname} = {render(tag, val)}   ({TAG_NAMES.get(tag, hex(tag))})")


def dump(groups):
    for gtag, attrs in groups:
        print(f"  [{GROUP_NAMES.get(gtag, hex(gtag))}]")
        for name, values in attrs.items():
            if values[0][0] == TAG_BEG_COLLECTION:
                print(f"    {name} (1setOf collection × {len(values)})")
                for i, (_, members) in enumerate(values):
                    print(f"      [{i}]")
                    _dump_members(members, 8)
                continue
            shown = ", ".join(render(t, v) for t, v in values)
            tname = TAG_NAMES.get(values[0][0], hex(values[0][0]))
            print(f"    {name} = {shown}   ({tname})")


# ── 传输 ────────────────────────────────────────────────────────────────────
def post(host, port, body: bytes, timeout=60):
    """手写 HTTP，不用 urllib：要精确控制头，并且要能看到服务端是否按
    Content-Length 回复（用 urllib 会顺手纠正一些格式问题）。"""
    s = socket.create_connection((host, port), timeout=timeout)
    try:
        head = (
            f"POST /ipp/print HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            f"Content-Type: application/ipp\r\n"
            f"Content-Length: {len(body)}\r\n"
            f"Connection: close\r\n\r\n"
        ).encode()
        s.sendall(head + body)
        chunks = []
        while True:
            b = s.recv(65536)
            if not b:
                break
            chunks.append(b)
        resp = b"".join(chunks)
    finally:
        s.close()

    sep = resp.find(b"\r\n\r\n")
    if sep < 0:
        raise RuntimeError(f"响应没有头部：{resp[:120]!r}")
    headers = resp[:sep].decode("latin-1")
    payload = resp[sep + 4:]
    return headers, payload


def base_operation(w: Writer, printer_uri: str):
    w.group(GROUP_OPERATION)
    w.charset("attributes-charset", "utf-8")
    w.language("attributes-natural-language", "en")
    w.uri("printer-uri", printer_uri)
    return w


def printer_uri(host, port):
    return f"ipp://{host}:{port}/ipp/print"


def show(headers, payload):
    line = headers.splitlines()[0] if headers else "(无)"
    print(f"HTTP: {line}")
    if not payload:
        print("  (无 IPP 报文)")
        return None
    version, status, req_id, groups = parse(payload)
    print(
        f"IPP: 版本 {version >> 8}.{version & 0xFF}  "
        f"状态 0x{status:04X} {STATUS_NAMES.get(status, '?')}  request-id={req_id}"
    )
    dump(groups)
    return status, groups


# ── 子命令 ──────────────────────────────────────────────────────────────────
def cmd_attrs(args):
    w = base_operation(Writer(VERSION_2_0, OP_GET_PRINTER_ATTRIBUTES, 1), printer_uri(args.host, args.port))
    headers, payload = post(args.host, args.port, w.done())
    return show(headers, payload)


def guess_format(data: bytes) -> str:
    """按魔数猜 document-format。

    客户端本来应该自己知道在发什么，但这里刻意做一次嗅探：
    一是方便用同一个命令测 PCL 直通路径，二是提醒「声明的格式可能不可信」——
    服务端同样只看字节。
    """
    if data.startswith(b"%PDF"):
        return "application/pdf"
    if data.startswith(b"UNIRAST"):
        return "image/urf"
    if data.startswith(b"RaS2"):
        return "image/pwg-raster"
    head = data[:1024]
    if head.startswith(b"\x1b%-12345X") or b"@PJL" in head or head.startswith(b"\x1bE"):
        return "application/vnd.hp-PCL"
    if head.startswith(b"%!PS"):
        return "application/postscript"
    return "application/octet-stream"


def cmd_print(args, op):
    data = open(args.pdf, "rb").read()
    fmt = args.format or guess_format(data)
    if fmt == "application/octet-stream":
        print(f"警告：无法识别 {args.pdf} 的类型（头 {data[:8]!r}），按 octet-stream 发送；"
              f"服务端会拒收未知类型", file=sys.stderr)
    print(f"发送：{args.pdf}  {len(data)} 字节  document-format={fmt}")

    w = Writer(VERSION_1_1, op, 42)
    base_operation(w, printer_uri(args.host, args.port))
    w.namev("requesting-user-name", "ippclient")
    w.namev("job-name", args.name or __import__("os").path.basename(args.pdf))
    w.mime("document-format", fmt)
    if args.duplex:
        w.keyword("sides", "two-sided-long-edge")
    if args.dpi:
        w.resolution("printer-resolution", args.dpi, args.dpi)
    if getattr(args, "copies", None):
        w.integer("copies", args.copies)
    headers, payload = post(args.host, args.port, w.done() + data)
    return show(headers, payload)


def cmd_job(args):
    w = base_operation(Writer(VERSION_1_1, OP_GET_JOB_ATTRIBUTES, 7), printer_uri(args.host, args.port))
    w.integer("job-id", args.id)
    headers, payload = post(args.host, args.port, w.done())
    return show(headers, payload)


def create_job(host, port, name, fmt, duplex=False, req_id=50):
    """Create-Job，返回 (job-id 或 None, status)。"""
    w = Writer(VERSION_1_1, OP_CREATE_JOB, req_id)
    base_operation(w, printer_uri(host, port))
    w.namev("requesting-user-name", "ippclient")
    w.namev("job-name", name)
    if fmt:
        w.mime("document-format", fmt)
    if duplex:
        w.keyword("sides", "two-sided-long-edge")
    headers, payload = post(host, port, w.done())
    status, groups = show(headers, payload)
    job_id = None
    for gtag, attrs in groups:
        if gtag == GROUP_JOB and "job-id" in attrs:
            job_id = struct.unpack(">i", attrs["job-id"][0][1])[0]
    return job_id, status


def send_document(host, port, job_id, data, fmt, req_id=51):
    w = Writer(VERSION_1_1, OP_SEND_DOCUMENT, req_id)
    base_operation(w, printer_uri(host, port))
    w.namev("requesting-user-name", "ippclient")
    w.integer("job-id", job_id)
    w.mime("document-format", fmt)
    w.boolean("last-document", True)
    headers, payload = post(host, port, w.done() + data)
    return show(headers, payload)


def close_job(host, port, job_id, req_id=52):
    w = Writer(VERSION_1_1, OP_CLOSE_JOB, req_id)
    base_operation(w, printer_uri(host, port))
    w.integer("job-id", job_id)
    headers, payload = post(host, port, w.done())
    return show(headers, payload)


def cmd_create(args):
    job_id, _ = create_job(args.host, args.port, args.name or "create-test", args.format)
    print(f"job-id = {job_id}")
    return 0 if job_id else 1


def cmd_send(args):
    data = open(args.file, "rb").read()
    fmt = args.format or guess_format(data)
    return send_document(args.host, args.port, args.id, data, fmt)


def cmd_close(args):
    return close_job(args.host, args.port, args.id)


def cmd_airprint(args):
    """完整走一遍 iOS 的流程：Create-Job → Send-Document → Close-Job。

    这三步任一步不支持，iPhone 上看到的现象都是「选了打印机、点打印没反应」，
    所以这里逐步打印，一眼就能定位断在哪。
    """
    data = open(args.file, "rb").read()
    fmt = args.format or guess_format(data)
    print(f"文档：{args.file}  {len(data)} 字节  document-format={fmt}")

    print("\n[1/3] Create-Job")
    job_id, status = create_job(
        args.host, args.port, args.name or __import__("os").path.basename(args.file), fmt, args.duplex,
    )
    if job_id is None or status >= 0x0100:
        print("Create-Job 失败，后面的步骤没有意义")
        return 1

    print(f"\n[2/3] Send-Document（job-id={job_id}）")
    status, _ = send_document(args.host, args.port, job_id, data, fmt)
    if status >= 0x0100:
        print("Send-Document 失败")
        return 1

    print(f"\n[3/3] Close-Job（job-id={job_id}）")
    status, _ = close_job(args.host, args.port, job_id)
    print(f"\n完成。用 `ippclient.py job {job_id}` 查最终状态。")
    return 0 if status < 0x0100 else 1


def cmd_jobs(args):
    w = base_operation(Writer(VERSION_1_1, OP_GET_JOBS, 8), printer_uri(args.host, args.port))
    w.keyword("which-jobs", "all")
    headers, payload = post(args.host, args.port, w.done())
    return show(headers, payload)


def main():
    ap = argparse.ArgumentParser(description="WPrint IPP 客户端探针")
    ap.add_argument("--host", default="192.168.123.60")
    ap.add_argument("--port", type=int, default=8631)
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("attrs", help="Get-Printer-Attributes").set_defaults(func=cmd_attrs)

    p = sub.add_parser("validate", help="Validate-Job")
    p.add_argument("pdf")
    p.add_argument("--name")
    p.add_argument("--duplex", action="store_true")
    p.add_argument("--dpi", type=int, choices=[300, 600])
    p.add_argument("--copies", type=int, help="份数（PCL 层 ESC&l#X）")
    p.add_argument("--format", help="覆盖 document-format")
    p.set_defaults(func=lambda a: cmd_print(a, OP_VALIDATE_JOB))

    p = sub.add_parser("print", help="Print-Job（真的会出纸）")
    p.add_argument("pdf")
    p.add_argument("--name")
    p.add_argument("--duplex", action="store_true")
    p.add_argument("--dpi", type=int, choices=[300, 600])
    p.add_argument("--copies", type=int, help="份数（PCL 层 ESC&l#X）")
    p.add_argument("--format", help="覆盖 document-format")
    p.set_defaults(func=lambda a: cmd_print(a, OP_PRINT_JOB))

    p = sub.add_parser("job", help="Get-Job-Attributes")
    p.add_argument("id", type=int)
    p.set_defaults(func=cmd_job)

    # AirPrint 的三步流程，以及一个一键跑完的 airprint 命令
    p = sub.add_parser("create", help="Create-Job")
    p.add_argument("--name")
    p.add_argument("--format")
    p.set_defaults(func=cmd_create)

    p = sub.add_parser("send", help="Send-Document")
    p.add_argument("id", type=int)
    p.add_argument("file")
    p.add_argument("--format")
    p.set_defaults(func=cmd_send)

    p = sub.add_parser("close", help="Close-Job")
    p.add_argument("id", type=int)
    p.set_defaults(func=cmd_close)

    p = sub.add_parser("airprint", help="Create-Job → Send-Document → Close-Job（iOS 的流程）")
    p.add_argument("file")
    p.add_argument("--name")
    p.add_argument("--duplex", action="store_true")
    p.add_argument("--format", help="覆盖 document-format")
    p.set_defaults(func=cmd_airprint)

    sub.add_parser("jobs", help="Get-Jobs").set_defaults(func=cmd_jobs)

    args = ap.parse_args()
    result = args.func(args)
    if isinstance(result, tuple):
        status, _ = result
        sys.exit(0 if status < 0x0100 else 1)
    if isinstance(result, int):
        sys.exit(result)


if __name__ == "__main__":
    main()
