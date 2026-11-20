#!/usr/bin/env python3
"""发一条 mDNS 查询并打印收到的应答（标准库，无依赖）。

为什么需要这个工具：AirPrint 的入口是「带 `_universal` 子类型的 PTR」，
而这台服务器是自己在 5353 上收发 DNS 报文。要验证它，必须能在电脑上
真的问一次 —— 只看手机上「已广播」的日志是不够的，报文可能编码错、
压缩指针可能指歪、缓存刷新位可能放错，这些只有解析端能发现。

用法：
    python tools/ipptest/mdnsquery.py                 # 查 _ipp._tcp（默认）
    python tools/ipptest/mdnsquery.py --subtype       # 查 _universal 子类型（AirPrint）
    python tools/ipptest/mdnsquery.py --name _services._dns-sd._udp.local.
    python tools/ipptest/mdnsquery.py --type SRV --name WPrint-LJ2600D._ipp._tcp.local.

注意：从随机端口发出的查询，服务端会**单播**回应（RFC 6762 §6.7），
所以这里只需要普通的 UDP socket，不必占 5353。
"""

import argparse
import socket
import struct
import sys
import time

GROUP = "224.0.0.251"
PORT = 5353

TYPES = {"A": 1, "PTR": 12, "TXT": 16, "AAAA": 28, "SRV": 33, "ANY": 255}
TYPE_NAMES = {v: k for k, v in TYPES.items()}


def encode_name(name: str) -> bytes:
    out = b""
    for label in name.rstrip(".").split("."):
        raw = label.encode("utf-8")
        out += bytes([len(raw)]) + raw
    return out + b"\x00"


def build_query(name: str, qtype: int) -> bytes:
    header = struct.pack("!HHHHHH", 0, 0, 1, 0, 0, 0)
    return header + encode_name(name) + struct.pack("!HH", qtype, 1)


def read_name(data: bytes, off: int):
    """解域名，支持压缩指针。返回 (名字, 下一个字节偏移)。"""
    labels = []
    end = off
    jumped = False
    guard = 0
    while True:
        if off >= len(data):
            return ".".join(labels), end
        length = data[off]
        if length == 0:
            off += 1
            break
        if length & 0xC0 == 0xC0:
            if off + 1 >= len(data):
                return ".".join(labels), end
            ptr = ((length & 0x3F) << 8) | data[off + 1]
            if not jumped:
                end = off + 2
                jumped = True
            off = ptr
            guard += 1
            if guard > 64:
                break
            continue
        off += 1
        labels.append(data[off:off + length].decode("utf-8", "replace"))
        off += length
    return ".".join(labels), (end if jumped else off)


def parse_message(data: bytes):
    """返回 (header, questions, records)。

    records 里存的是 rdata 在**整条报文**里的绝对偏移，不是切片 —— PTR/SRV 的
    rdata 是域名，可能带压缩指针，而指针偏移永远是相对报文开头的。切片之后
    就再也解不出来了。
    """
    if len(data) < 12:
        return None, [], []
    msg_id, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", data[:12])
    header = {
        "id": msg_id, "flags": flags, "qd": qd, "an": an, "ns": ns, "ar": ar,
        "response": bool(flags & 0x8000),
        "authoritative": bool(flags & 0x0400),
    }
    off = 12
    questions = []
    for _ in range(qd):
        name, off = read_name(data, off)
        if off + 4 > len(data):
            break
        qtype, qclass = struct.unpack("!HH", data[off:off + 4])
        off += 4
        questions.append((name, qtype, qclass))
    records = []
    for count, section in ((an, "answer"), (ns, "authority"), (ar, "additional")):
        for _ in range(count):
            name, off = read_name(data, off)
            if off + 10 > len(data):
                return header, questions, records
            rtype, rclass, ttl, rdlen = struct.unpack("!HHIH", data[off:off + 10])
            off += 10
            if off + rdlen > len(data):
                return header, questions, records
            records.append((section, name, rtype, rclass, ttl, off, rdlen))
            off += rdlen
    return header, questions, records


def describe(data, name, rtype, rclass, ttl, rd_off, rd_len):
    flush = " cache-flush" if rclass & 0x8000 else ""
    kind = TYPE_NAMES.get(rtype, str(rtype))
    rdata = data[rd_off:rd_off + rd_len]
    if rtype == TYPES["PTR"]:
        value, _ = read_name(data, rd_off)
    elif rtype == TYPES["SRV"]:
        if rd_len >= 6:
            pri, weight, port = struct.unpack("!HHH", rdata[:6])
            target, _ = read_name(data, rd_off + 6)
            value = f"pri={pri} weight={weight} port={port} target={target}"
        else:
            value = rdata.hex()
    elif rtype == TYPES["TXT"]:
        parts, p = [], 0
        while p < len(rdata):
            n = rdata[p]
            p += 1
            parts.append(rdata[p:p + n].decode("utf-8", "replace"))
            p += n
        value = " | ".join(parts)
    elif rtype == TYPES["A"]:
        value = ".".join(str(b) for b in rdata)
    else:
        value = rdata.hex()
    return f"{kind:4s} {name}  ttl={ttl}{flush}  →  {value}"


def listen(a):
    """只监听 5353 的组播流量。

    用途：Windows 的「公用网络」防火墙规则会把入站的单播应答挡掉，
    这时查询-应答看起来完全不通，但对端其实正常在公告。监听组播能区分
    「对端没发」和「本机没收到」—— 顺带也能验证公告里的记录集合。
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    bind_ip = a.iface or "0.0.0.0"
    s.bind((bind_ip, PORT))
    mreq = socket.inet_aton(a.group) + socket.inet_aton(bind_ip)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    s.settimeout(0.5)
    print(f"监听 {bind_ip}:{PORT} 组 {a.group}，{a.wait:.0f} 秒…")

    deadline = time.time() + a.wait
    count = 0
    while time.time() < deadline:
        try:
            data, addr = s.recvfrom(9000)
        except socket.timeout:
            continue
        header, questions, records = parse_message(data)
        if header is None:
            continue
        if not header["response"]:
            continue                      # 别的设备的查询，跳过
        count += 1
        print(f"\n来自 {addr[0]}:{addr[1]}  {len(data)} 字节  an={header['an']} ar={header['ar']}")
        for section, rname, rtype, rclass, ttl, rd_off, rd_len in records:
            print(f"  [{section}] " + describe(data, rname, rtype, rclass, ttl, rd_off, rd_len))
    s.close()
    print(f"\n共收到 {count} 个公告报文")
    return 0 if count else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--subtype", action="store_true", help="查 _universal 子类型（AirPrint 入口）")
    ap.add_argument("--name", help="要查的名字，默认按 --subtype 推断")
    ap.add_argument("--type", default="PTR", choices=sorted(TYPES))
    ap.add_argument("--group", default=GROUP)
    ap.add_argument("--iface", help="从这个本机地址发组播（多网卡机器必填，否则可能走错网卡）")
    ap.add_argument("--listen", action="store_true",
                    help="只监听 5353 上的组播（等对端主动公告），不发查询")
    ap.add_argument("--from-port", type=int,
                    help="用这个源端口发查询。填 5353 就是「像 iOS 那样」从 5353 发，"
                         "对端会按 RFC 6762 回组播，绕开 Windows 防火墙挡单播应答的问题")
    ap.add_argument("--wait", type=float, default=2.0, help="等应答的秒数")
    a = ap.parse_args()

    if a.listen:
        return listen(a)

    if a.name:
        name = a.name
    elif a.subtype:
        name = "_universal._sub._ipp._tcp.local."
    else:
        name = "_ipp._tcp.local."

    qtype = TYPES[a.type]
    query = build_query(name, qtype)
    print(f"查询 {name} {a.type}  →  {a.group}:{PORT}")

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.settimeout(0.3)
    # 组播 TTL=255：mDNS 要求，且跨路由器时不会被误转发到远处。
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
    if a.iface:
        # Windows 上装了 VMware/Hyper-V 的机器常有多个网卡，不指定出口的话
        # 组播会从虚拟网卡发出去，对端根本收不到。
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(a.iface))
        print(f"出口网卡：{a.iface}")

    if a.from_port:
        # 从 5353 发，并且加入组播组：这样对端回的是组播，我们自己也能收到。
        # 真实客户端（iOS/macOS）就是这么发的。
        s.bind((a.iface or "0.0.0.0", a.from_port))
        mreq = socket.inet_aton(a.group) + socket.inet_aton(a.iface or "0.0.0.0")
        s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        print(f"源端口：{a.from_port}（已加入 {a.group}）")
    elif a.iface:
        s.bind((a.iface, 0))

    s.sendto(query, (a.group, PORT))

    deadline = time.time() + a.wait
    seen = 0
    raw_seen = 0
    while time.time() < deadline:
        try:
            data, addr = s.recvfrom(9000)
        except socket.timeout:
            continue
        raw_seen += 1
        result = parse_message(data)
        if not result:
            print(f"\n来自 {addr[0]}:{addr[1]} 的 {len(data)} 字节无法解析：{data[:32].hex()}")
            continue
        header, questions, records = result
        if not header["response"] or not records:
            # 这也是有用的信息：说明收到了包（说明网络通），只是内容不符预期。
            print(f"\n来自 {addr[0]}:{addr[1]}  {len(data)} 字节 "
                  f"response={header['response']} 记录数={len(records)}")
            continue
        seen += 1
        print(f"\n来自 {addr[0]}:{addr[1]}  报文 {len(data)} 字节  "
              f"flags=0x{header['flags']:04X}  an={header['an']} ar={header['ar']}")
        for section, rname, rtype, rclass, ttl, rd_off, rd_len in records:
            print(f"  [{section}] " + describe(data, rname, rtype, rclass, ttl, rd_off, rd_len))
        break

    s.close()
    if seen == 0:
        print("\n没有任何应答。检查：手机是否在同一个局域网、服务是否在运行、"
              "路由器是否屏蔽了组播（很多「AP 隔离」会屏蔽）。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
