"""监听 Windows 侧发起打印机搜索时用到的发现协议。

同时听两路：
  5353   mDNS/DNS-SD —— Win11 的「添加设备」会用它列举 IPP Everywhere 打印机
  3702   WS-Discovery —— Win10 的「添加设备」主要靠这个列 WSD 设备

只被动收，不发任何包，避免干扰判断。
"""
import socket
import struct
import sys
import threading
import time

LOCAL_IP = "192.168.123.66"
DURATION = float(sys.argv[1]) if len(sys.argv) > 1 else 180.0
START = time.time()
LOCK = threading.Lock()


def log(msg):
    with LOCK:
        print(f"{time.strftime('%H:%M:%S')} {msg}", flush=True)


def read_name(b, i):
    labs = []
    while True:
        if i >= len(b):
            return ".".join(labs), i
        length = b[i]
        if length == 0:
            return ".".join(labs), i + 1
        if length & 0xC0 == 0xC0:
            ptr = struct.unpack(">H", b[i:i + 2])[0] & 0x3FFF
            n2, _ = read_name(b, ptr)
            if n2:
                labs.append(n2)
            return ".".join(labs), i + 2
        labs.append(b[i + 1:i + 1 + length].decode("utf-8", "replace"))
        i += 1 + length


TYPES = {1: "A", 12: "PTR", 16: "TXT", 28: "AAAA", 33: "SRV"}


def watch_mdns():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", 5353))
    s.setsockopt(
        socket.IPPROTO_IP,
        socket.IP_ADD_MEMBERSHIP,
        socket.inet_aton("224.0.0.251") + socket.inet_aton(LOCAL_IP),
    )
    s.settimeout(1.0)
    while time.time() - START < DURATION:
        try:
            data, addr = s.recvfrom(9000)
        except socket.timeout:
            continue
        if len(data) < 12:
            continue
        tid, flags, qd, an, ns, ar = struct.unpack(">HHHHHH", data[:12])
        is_query = not (flags & 0x8000)
        names = []
        i = 12
        if is_query:
            try:
                for _ in range(qd):
                    nm, i = read_name(data, i)
                    qtype = struct.unpack(">H", data[i:i + 2])[0]
                    i += 4
                    names.append(f"{nm}({TYPES.get(qtype, qtype)})")
            except Exception:
                pass
        tag = "查询" if is_query else f"应答 AN={an} AR={ar}"
        same = " ★本机" if addr[0] == LOCAL_IP else ""
        log(f"[5353] {addr[0]}:{addr[1]} {tag} {' | '.join(names) if names else ''}{same}")


def watch_wsd():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("0.0.0.0", 3702))
    except OSError as e:
        log(f"[3702] 绑定失败：{e}")
        return
    s.setsockopt(
        socket.IPPROTO_IP,
        socket.IP_ADD_MEMBERSHIP,
        socket.inet_aton("239.255.255.250") + socket.inet_aton(LOCAL_IP),
    )
    s.settimeout(1.0)
    while time.time() - START < DURATION:
        try:
            data, addr = s.recvfrom(9000)
        except socket.timeout:
            continue
        body = data.decode("utf-8", "replace")
        action = ""
        for key in ("Probe", "Resolve", "GetMetadata", "Get"):
            if f"Action" in body and key in body:
                action = key
                break
        types = [t for t in ("wprt:PrintDeviceType", "wsdp:Device", "wscn:ScanDeviceType") if t in body]
        log(f"[3702] {addr[0]}:{addr[1]} {len(data)}B {action} 类型={'/'.join(types) or '?'}")


log(f"开始监听 {DURATION:.0f} 秒（5353 + 3702）")
threading.Thread(target=watch_mdns, daemon=True).start()
threading.Thread(target=watch_wsd, daemon=True).start()
time.sleep(DURATION)
log("监听结束")
