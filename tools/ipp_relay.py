import socket, threading, sys

TARGET = ("192.168.123.6", 8631)
LISTEN = ("0.0.0.0", 8631)

def pipe(a, b):
    try:
        while True:
            data = a.recv(65536)
            if not data:
                break
            b.sendall(data)
    except Exception:
        pass
    finally:
        try:
            b.shutdown(socket.SHUT_WR)
        except Exception:
            pass

def handle(client):
    try:
        upstream = socket.create_connection(TARGET, timeout=10)
    except Exception as e:
        print("upstream fail", e, flush=True)
        client.close()
        return
    t1 = threading.Thread(target=pipe, args=(client, upstream), daemon=True)
    t2 = threading.Thread(target=pipe, args=(upstream, client), daemon=True)
    t1.start(); t2.start(); t1.join(); t2.join()
    client.close(); upstream.close()

srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(LISTEN)
srv.listen(16)
print("relay listening on", LISTEN, "->", TARGET, flush=True)
while True:
    c, addr = srv.accept()
    print("conn from", addr, flush=True)
    threading.Thread(target=handle, args=(c,), daemon=True).start()
