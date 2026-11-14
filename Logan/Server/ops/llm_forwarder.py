
import socket, threading

def rewrite_head(data):
    sep = b"\r\n\r\n"
    i = data.find(sep)
    head = data if i < 0 else data[:i]
    body = b"" if i < 0 else data[i:]
    lines = head.split(b"\r\n")
    rl = lines[0].split(b" ")
    if len(rl) == 3 and rl[1].startswith(b"http"):
        rl[1] = b"/"
        lines[0] = b" ".join(rl)
    out = []
    seen = False
    for ln in lines[1:]:
        if ln.lower().startswith(b"host:"):
            out.append(b"Host: 127.0.0.1:8000"); seen = True
        else:
            out.append(ln)
    if not seen:
        out.insert(0, b"Host: 127.0.0.1:8000")
    return b"\r\n".join([lines[0]] + out) + body

def pipe_to_upstream(c, up):
    try:
        buf = b""
        head_done = False
        while True:
            d = c.recv(65536)
            if not d: break
            if not head_done:
                buf += d
                j = buf.find(b"\r\n\r\n")
                if j >= 0 or len(buf) > 65536:
                    up.sendall(rewrite_head(buf)); buf = b""; head_done = True
            else:
                up.sendall(d)
    except Exception: pass
    finally:
        try: up.shutdown(socket.SHUT_WR)
        except Exception: pass

def pipe_raw(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d: break
            b.sendall(d)
    except Exception: pass
    finally:
        try: b.shutdown(socket.SHUT_WR)
        except Exception: pass

def serve():
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("100.88.73.95", 18300)); s.listen(16)
    while True:
        c, _ = s.accept()
        try:
            up = socket.create_connection(("127.0.0.1", 18010), timeout=60)
        except Exception:
            c.close(); continue
        threading.Thread(target=pipe_to_upstream, args=(c, up), daemon=True).start()
        threading.Thread(target=pipe_raw, args=(up, c), daemon=True).start()

serve()
