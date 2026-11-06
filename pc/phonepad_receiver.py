#!/usr/bin/env python3
"""PhonePad receiver: lets the PhonePad app (Network mode) control this PC.

Windows: needs only Python 3 (no extra packages).  Run:  py phonepad_receiver.py
On other systems it just prints the commands it receives (useful for testing).
Only run it on a network you trust (USB tethering or your own hotspot/router):
anyone who can reach this port can control the PC while it is running.
"""
import socket, socketserver, sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
WIN = sys.platform == "win32"

# ---- HID usage -> Windows virtual-key -------------------------------------------------
VK = {}
for i in range(26): VK[0x04 + i] = 0x41 + i          # a-z
for i in range(9):  VK[0x1E + i] = 0x31 + i          # 1-9
VK[0x27] = 0x30                                      # 0
for i in range(12): VK[0x3A + i] = 0x70 + i          # F1-F12
VK.update({0x28: 0x0D, 0x29: 0x1B, 0x2A: 0x08, 0x2B: 0x09, 0x2C: 0x20,
           0x2D: 0xBD, 0x2E: 0xBB, 0x2F: 0xDB, 0x30: 0xDD, 0x31: 0xDC, 0x33: 0xBA,
           0x34: 0xDE, 0x35: 0xC0, 0x36: 0xBC, 0x37: 0xBE, 0x38: 0xBF,
           0x4A: 0x24, 0x4B: 0x21, 0x4C: 0x2E, 0x4D: 0x23, 0x4E: 0x22,
           0x4F: 0x27, 0x50: 0x25, 0x51: 0x28, 0x52: 0x26})
MODS = [(1, 0xA2), (2, 0xA0), (4, 0xA4), (8, 0x5B)]   # ctrl, shift, alt, win
MEDIA = {1: 0xAF, 2: 0xAE, 4: 0xAD}                   # vol+, vol-, mute
EXT = {0x25, 0x26, 0x27, 0x28, 0x21, 0x22, 0x23, 0x24, 0x2D, 0x2E, 0x5B}

if WIN:
    import ctypes
    from ctypes import wintypes as wt
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    PTR = ctypes.c_size_t

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD),
                    ("dwFlags", wt.DWORD), ("time", wt.DWORD), ("dwExtraInfo", PTR)]
    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD),
                    ("time", wt.DWORD), ("dwExtraInfo", PTR)]
    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [("uMsg", wt.DWORD), ("wParamL", wt.WORD), ("wParamH", wt.WORD)]
    class _U(ctypes.Union):
        _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]
    class INPUT(ctypes.Structure):
        _anonymous_ = ("u",)
        _fields_ = [("type", wt.DWORD), ("u", _U)]
    u32.SendInput.argtypes = (wt.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
    u32.SendInput.restype = wt.UINT

    def _send(i):
        u32.SendInput(1, ctypes.byref(i), ctypes.sizeof(INPUT))

    def mouse_ev(flags, dx=0, dy=0, data=0):
        i = INPUT(type=0)
        i.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
        _send(i)

    def key_ev(vk, up=False):
        i = INPUT(type=1)
        flags = (0x2 if up else 0) | (0x1 if vk in EXT else 0)
        i.ki = KEYBDINPUT(vk, 0, flags, 0, 0)
        _send(i)
else:
    def mouse_ev(flags, dx=0, dy=0, data=0): print("mouse", hex(flags), dx, dy, data)
    def key_ev(vk, up=False): print("key", hex(vk), "up" if up else "down")

BTN = {1: (0x02, 0x04), 2: (0x08, 0x10), 4: (0x20, 0x40)}   # phone bit -> (down, up)
held = 0

def set_buttons(mask):
    global held
    for bit, (dn, up) in BTN.items():
        if mask & bit and not held & bit: mouse_ev(dn)
        if not mask & bit and held & bit: mouse_ev(up)
    held = mask

def press(code, mod):
    mods = [v for b, v in MODS if mod & b]
    for v in mods: key_ev(v)
    vk = VK.get(code)
    if vk: key_ev(vk); key_ev(vk, True)
    for v in reversed(mods): key_ev(v, True)

def dispatch(p):
    c = p[0]
    if c == "M":
        dx, dy, w = int(p[1]), int(p[2]), int(p[3])
        if dx or dy: mouse_ev(0x01, dx, dy)
        if w: mouse_ev(0x800, 0, 0, w * 120)
    elif c == "B": set_buttons(int(p[1]) & 7)
    elif c == "C":
        b = int(p[1])
        if b in BTN: mouse_ev(BTN[b][0]); mouse_ev(BTN[b][1])
    elif c == "K": press(int(p[1]), int(p[2]))
    elif c == "D":
        vk = MEDIA.get(int(p[1]))
        if vk: key_ev(vk); key_ev(vk, True)

class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        print("phone connected:", self.client_address[0], flush=True)
        try:
            for raw in self.rfile:
                p = raw.decode("ascii", "ignore").split()
                if not p: continue
                try: dispatch(p)
                except Exception as e: print("bad command", p, e, flush=True)
        except OSError:
            pass
        finally:
            set_buttons(0)
            print("phone disconnected", flush=True)

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

if __name__ == "__main__":
    ips = set()
    try: ips.update(socket.gethostbyname_ex(socket.gethostname())[2])
    except OSError: pass
    print("PhonePad receiver listening on port %d" % PORT)
    print("Type one of these addresses in the app (Mode: Net -> Connect):")
    for ip in sorted(ips): print("   ", ip)
    print("If Windows asks, allow Python through the firewall (private networks).")
    try:
        with Server(("0.0.0.0", PORT), Handler) as srv:
            srv.serve_forever()
    except KeyboardInterrupt:
        pass
