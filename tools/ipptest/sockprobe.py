"""探针：看服务端在回应之后是干净地 FIN 关闭，还是 RST。

为什么需要这个：iOS 的 CFNetwork 对「响应收到一半连接被重置」非常敏感，
会丢掉已经到手的响应并按新 request-id 重试。表现就是日志里几十次
Get-Printer-Attributes、零 Create-Job，而自家 Python 客户端一切正常
—— 因为 Python 读完 Content-Length 就不再碰 socket，永远看不到 RST。

用法：
    python tools/ipptest/sockprobe.py <host> [port]
"""
import socket
import struct
import sys


def ipp_get_attrs(request_id=1, attrs=("printer-state-reasons",)):
    """构造一个 Get-Printer-Attributes 请求体。"""
    body = bytearray()
    body += bytes([0x02, 0x00])                  # version 2.0
    body += struct.pack(">H", 0x000B)            # Get-Printer-Attributes
    body += struct.pack(">I", request_id)
    body += bytes([0x01])                        # operation-attributes-tag

    def add(tag, name, value):
        n = name.encode()
        v = value.encode() if isinstance(value, str) else value
        body.extend(bytes([tag]))
        body.extend(struct.pack(">H", len(n)))
        body.extend(n)
        body.extend(struct.pack(">H", len(v)))
        body.extend(v)

    add(0x47, "attributes-charset", "utf-8")
    add(0x48, "attributes-natural-language", "en")
    add(0x45, "printer-uri", "ipp://probe.local:8631/ipp/print")
    for a in attrs:
        add(0x44, "requested-attributes", a)
    body += bytes([0x03])
    return bytes(body)


def read_response(sock):
    """读一个完整 HTTP 响应，返回 (状态行, 头字典, 体, 是否完整)。"""
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            return None, {}, b"", False
        buf += chunk
    head, _, rest = buf.partition(b"\r\n\r\n")
    lines = head.decode("latin-1").split("\r\n")
    status = lines[0]
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, _, v = line.partition(":")
            headers[k.strip().lower()] = v.strip()
    want = int(headers.get("content-length", 0))
    while len(rest) < want:
        chunk = sock.recv(4096)
        if not chunk:
            return status, headers, rest, False
        rest += chunk
    return status, headers, rest[:want], True


def classify_close(sock):
    """响应读完之后连接怎么收场：clean-FIN / RST / 还有数据。"""
    sock.settimeout(3.0)
    try:
        extra = sock.recv(1)
        if extra == b"":
            return "clean-FIN"
        return f"还有多余数据 {extra!r}"
    except ConnectionResetError:
        return "RST（连接被重置）"
    except socket.timeout:
        return "超时未关（服务端没关连接）"
    except OSError as e:
        return f"OSError: {e}"


def case_chunked(host, port):
    """模拟 iOS：Transfer-Encoding: chunked + PrintKit UA。"""
    print("── 用例 1：chunked 编码 + PrintKit UA ──")
    body = ipp_get_attrs(1)
    header = (
        "POST /ipp/print HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Content-Type: application/ipp\r\n"
        "Transfer-Encoding: chunked\r\n"
        "User-Agent: com.apple.PrintKit.PrinterTool\r\n"
        "Accept: */*\r\n"
        "Accept-Encoding: gzip, deflate\r\n"
        "Connection: keep-alive\r\n"
        "\r\n"
    ).encode()
    chunked = b"%X\r\n" % len(body) + body + b"\r\n0\r\n\r\n"
    with socket.create_connection((host, port), timeout=5) as s:
        s.sendall(header + chunked)
        status, headers, resp, complete = read_response(s)
        print(f"  状态行      : {status}")
        print(f"  头          : {headers}")
        print(f"  响应体      : {len(resp)} 字节，{'完整' if complete else '不完整'}")
        print(f"  关闭方式    : {classify_close(s)}")


def case_content_length(host, port):
    """对照组：Content-Length + curl 风格 UA。"""
    print("── 用例 2：Content-Length ──")
    body = ipp_get_attrs(2)
    header = (
        "POST /ipp/print HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Content-Type: application/ipp\r\n"
        f"Content-Length: {len(body)}\r\n"
        "User-Agent: curl/7.81\r\n"
        "\r\n"
    ).encode()
    with socket.create_connection((host, port), timeout=5) as s:
        s.sendall(header + body)
        status, headers, resp, complete = read_response(s)
        print(f"  状态行      : {status}")
        print(f"  响应体      : {len(resp)} 字节，{'完整' if complete else '不完整'}")
        print(f"  关闭方式    : {classify_close(s)}")


def case_trailing_byte(host, port):
    """服务端少读一个字节时会不会 RST：故意在体后面多塞一个字节。

    chunked 编码的收尾是 `0\\r\\n\\r\\n`。如果服务端只读到 `0\\r\\n` 就返回，
    剩下的字节会让 close() 变成 RST。这里额外再塞一个换行放大这个问题。
    """
    print("── 用例 3：chunked 收尾多一个空行（考验服务端是否读干净）──")
    body = ipp_get_attrs(3)
    header = (
        "POST /ipp/print HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Content-Type: application/ipp\r\n"
        "Transfer-Encoding: chunked\r\n"
        "User-Agent: com.apple.PrintKit.PrinterTool\r\n"
        "Connection: keep-alive\r\n"
        "\r\n"
    ).encode()
    chunked = b"%X\r\n" % len(body) + body + b"\r\n0\r\n\r\n"
    with socket.create_connection((host, port), timeout=5) as s:
        s.sendall(header + chunked)
        status, headers, resp, complete = read_response(s)
        # 响应读完了，再补一个字节进去，看服务端 close 时是否变成 RST
        try:
            s.sendall(b"\r\n")
        except OSError:
            pass
        print(f"  状态行      : {status}")
        print(f"  关闭方式    : {classify_close(s)}")


def case_keepalive(host, port):
    """同一条连接上连发两个请求：服务端支持 keep-alive 吗。"""
    print("── 用例 4：同一连接连发两个请求 ──")
    header = (
        "POST /ipp/print HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Content-Type: application/ipp\r\n"
        "User-Agent: probe\r\n"
        "\r\n"
    ).encode()
    with socket.create_connection((host, port), timeout=5) as s:
        for rid in (10, 11):
            body = ipp_get_attrs(rid)
            s.sendall(header.replace(b"\r\n\r\n",
                                     f"Content-Length: {len(body)}\r\n\r\n".encode()) + body)
            status, headers, resp, complete = read_response(s)
            if status is None:
                print(f"  第 2 个请求（id={rid}）没有响应 —— 服务端一次连接只处理一个请求")
                return
            conn = headers.get("connection", "-")
            print(f"  id={rid}: {status}  Connection: {conn}  {len(resp)} 字节")


if __name__ == "__main__":
    h = sys.argv[1] if len(sys.argv) > 1 else "192.168.123.60"
    p = int(sys.argv[2]) if len(sys.argv) > 2 else 8631
    for fn in (case_chunked, case_content_length, case_trailing_byte, case_keepalive):
        try:
            fn(h, p)
        except Exception as e:
            print(f"  失败：{type(e).__name__}: {e}")
        print()
