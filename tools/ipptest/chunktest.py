#!/usr/bin/env python3
"""验证服务器能吃 `Transfer-Encoding: chunked` 的 IPP 请求。

iOS 的 Send-Document 常走 chunked（它不保证事先知道光栅化后的大小），
而服务器如果按 Content-Length 的思路实现，就只会在真机上失败、在 PC 的
探针里看不出问题 —— 因为探针一直用 Content-Length。

用法：
    python chunktest.py <file> [--host IP] [--port 8631] [--chunk 16384]
"""

import argparse
import socket
import struct
import sys

from ippclient import (
    OP_PRINT_JOB,
    VERSION_1_1,
    Writer,
    base_operation,
    guess_format,
    printer_uri,
)

TYPE_TAG_END = 0x03
STATUS_NAMES = {0x0000: "successful-ok", 0x0400: "client-error-bad-request",
                0x0401: "client-error-forbidden", 0x040A: "document-format-not-supported",
                0x0500: "server-error-internal-error", 0x0501: "operation-not-supported"}


def chunked(items, size):
    for i in range(0, len(items), size):
        piece = items[i:i + size]
        yield b"%X\r\n" % len(piece) + piece + b"\r\n"
    yield b"0\r\n\r\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--host", default="192.168.123.60")
    ap.add_argument("--port", type=int, default=8631)
    ap.add_argument("--chunk", type=int, default=16384, help="每块字节数")
    ap.add_argument("--name", default="chunked-job")
    a = ap.parse_args()

    with open(a.file, "rb") as f:
        doc = f.read()
    fmt = guess_format(doc)

    w = Writer(VERSION_1_1, OP_PRINT_JOB, 900)
    base_operation(w, printer_uri(a.host, a.port))
    w.namev("requesting-user-name", "chunktest")
    w.namev("job-name", a.name)
    w.mime("document-format", fmt)
    body = w.done() + doc

    req = (
        f"POST /ipp/print HTTP/1.1\r\n"
        f"Host: {a.host}:{a.port}\r\n"
        f"Content-Type: application/ipp\r\n"
        f"Transfer-Encoding: chunked\r\n"
        f"User-Agent: chunktest/1.0\r\n"
        f"Connection: close\r\n\r\n"
    ).encode()

    print(f"发送 {len(doc)} 字节文档（{fmt}），按 {a.chunk} 字节分块，共 "
          f"{(len(body) + a.chunk - 1) // a.chunk} 块")

    s = socket.create_connection((a.host, a.port), timeout=60)
    s.sendall(req)
    for c in chunked(body, a.chunk):
        s.sendall(c)

    resp = b""
    while True:
        part = s.recv(65536)
        if not part:
            break
        resp += part
    s.close()

    head, _, payload = resp.partition(b"\r\n\r\n")
    print(head.decode("latin-1").splitlines()[0])
    if len(payload) < 8:
        print("响应体为空")
        return 1
    status = struct.unpack("!H", payload[2:4])[0]
    print(f"IPP 状态 0x{status:04X} {STATUS_NAMES.get(status, '')}")
    return 0 if status == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
