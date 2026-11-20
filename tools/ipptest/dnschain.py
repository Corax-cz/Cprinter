"""从局域网侧走一遍完整的 DNS-SD 解析链，并把每一步的原始字段打出来。

用途：判断「客户端能不能把服务名字变成可连接的 IP」。
PTR（有哪些实例）→ SRV（端口 + 主机名）→ A/AAAA（主机名 → IP）→ TXT（能力）
任何一环断掉，打印机的名字就不会出现在列表里，而界面上往往只说「没找到」。

必须从 5353 发查询：从临时端口发会命中 RFC 6762 的「单播应答」分支，
测不到真实组播路径下的行为。
"""
import random
import socket
import struct
import sys
import time

LOCAL_IP = sys.argv[1] if len(sys.argv) > 1 else "192.168.123.66"
TARGET_IP = sys.argv[2] if len(sys.argv) > 2 else "192.168.123.60"

TYPES = {1: "A", 12: "PTR", 16: "TXT", 28: "AAAA", 33: "SRV"}


def enc(name):
    out = b""
    for part in name.split("."):
        if part:
            out += bytes([len(part)]) + part.encode()
    return out + b"\x00"


def read_name(b, i):
    labs = []
    end = None
    while True:
        if i >= len(b):
            return ".".join(labs), end if end is not None else i
        length = b[i]
        if length == 0:
            return ".".join(labs), end if end is not None else i + 1
        if length & 0xC0 == 0xC0:
            ptr = struct.unpack(">H", b[i:i + 2])[0] & 0x3FFF
            if end is None:
                end = i + 2
            i = ptr
            continue
        labs.append(b[i + 1:i + 1 + length].decode("utf-8", "replace"))
        i += 1 + length


_sock = None


def sock():
    global _sock
    if _sock is not None:
        return _sock
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", 5353))
    s.setsockopt(
        socket.IPPROTO_IP,
        socket.IP_ADD_MEMBERSHIP,
        socket.inet_aton("224.0.0.251") + socket.inet_aton(LOCAL_IP),
    )
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(LOCAL_IP))
    _sock = s
    return s


def query(qname, qtype, timeout=2.0):
    """发一次查询，返回 (记录列表, 响应包数)。"""
    s = sock()
    tid = random.randint(1, 0xFFFF)
    pkt = struct.pack(">HHHHHH", tid, 0, 1, 0, 0, 0) + enc(qname) + struct.pack(">HH", qtype, 1)
    s.sendto(pkt, ("224.0.0.251", 5353))
    recs = []
    packets = 0
    deadline = time.time() + timeout
    while time.time() < deadline:
        s.settimeout(max(0.05, deadline - time.time()))
        try:
            data, addr = s.recvfrom(9000)
        except socket.timeout:
            break
        if addr[0] != TARGET_IP:
            continue
        packets += 1
        qd, an, ar = struct.unpack(">HHH", data[4:10])
        i = 12
        for _ in range(qd):
            _, i = read_name(data, i)
            i += 4
        for k in range(an + ar):
            owner, i = read_name(data, i)
            if i + 10 > len(data):
                break
            rtype, rclass, ttl, rlen = struct.unpack(">HHIH", data[i:i + 10])
            i += 10
            rd = i
            i += rlen
            recs.append({
                "section": "answer" if k < an else "additional",
                "owner": owner,
                "type": rtype,
                "class": rclass,
                "ttl": ttl,
                "rd": data[rd:rd + rlen],
                "rd_off": rd,
                "pkt": data,
            })
    return recs, packets


def main():
    print(f"本机 {LOCAL_IP} → 目标 {TARGET_IP}\n")

    # ── 1. 枚举实例 ────────────────────────────────────────────────────────
    recs, n = query("_ipp._tcp.local", 12)
    print(f"[1] 查 _ipp._tcp.local PTR：{n} 个响应包，{len(recs)} 条记录")
    instances = []
    for r in recs:
        if r["type"] == 12:
            name, _ = read_name(r["pkt"], r["rd_off"])
            print(f"    PTR ttl={r['ttl']:4d} class={r['class']:#06x} → {name}")
            if "_ipp._tcp.local" in name:
                instances.append(name)
    if not instances:
        print("    ✗ 没有实例，链路在第一步就断了")
        return

    inst = instances[0]

    # ── 2. 取 SRV / TXT ───────────────────────────────────────────────────
    print(f"\n[2] 查实例 {inst}")
    recs, n = query(inst, 255)
    srv = None
    txt_items = None
    for r in recs:
        if r["type"] == 33 and r["owner"].lower() == inst.lower():
            pri, weight, port = struct.unpack(">HHH", r["rd"][:6])
            target, _ = read_name(r["pkt"], r["rd_off"] + 6)
            print(f"    SRV ttl={r['ttl']:4d} 端口={port} 目标={target!r}")
            srv = (port, target)
        elif r["type"] == 16 and r["owner"].lower() == inst.lower():
            j = 0
            items = []
            while j < len(r["rd"]):
                length = r["rd"][j]
                items.append(r["rd"][j + 1:j + 1 + length].decode("utf-8", "replace"))
                j += 1 + length
            txt_items = items
            print(f"    TXT ttl={r['ttl']:4d} {len(items)} 项 / {len(r['rd'])} 字节")
    if srv is None:
        print("    ✗ 没有 SRV，客户端不知道端口，链路断在第二步")
        return

    # ── 3. 解析 SRV 目标主机名 ────────────────────────────────────────────
    port, target = srv
    print(f"\n[3] 解析 SRV 目标 {target}（A 记录）")
    recs, n = query(target, 1)
    got = []
    for r in recs:
        if r["type"] == 1 and r["owner"].lower() == target.lower():
            ip = ".".join(str(x) for x in r["rd"])
            print(f"    A   ttl={r['ttl']:4d} class={r['class']:#06x} → {ip}")
            got.append(ip)
    if not got:
        print(f"    ✗ 解析不出来。客户端拿到端口 {port} 却连不上主机，")
        print(f"      表现就是「打印机不显示」，而且没有任何报错。")
    else:
        print(f"    ✓ 可解析 → {got[0]}:{port}")

    # ── 4. AirPrint 子类型 ────────────────────────────────────────────────
    print("\n[4] 查 AirPrint 子类型 _universal._sub._ipp._tcp.local")
    recs, n = query("_universal._sub._ipp._tcp.local", 12, timeout=1.5)
    found = False
    for r in recs:
        if r["type"] == 12:
            name, _ = read_name(r["pkt"], r["rd_off"])
            print(f"    PTR ttl={r['ttl']:4d} → {name}")
            found = True
    if not found:
        print("    ✗ 没有子类型应答，iOS 的打印面板不会出现这台机器")

    if txt_items:
        print("\n[5] TXT 关键键")
        for key in ("rp", "ty", "pdl", "URF", "UUID", "txtvers", "qtotal"):
            hit = next((t for t in txt_items if t.startswith(key + "=")), None)
            print(f"    {key:8s} {'✓ ' + hit if hit else '✗ 缺失'}")


main()
