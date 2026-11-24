#!/usr/bin/env python3
# 通过雷鸟/TCL 自带「多屏互动」(com.tcl.MultiScreenInteraction_TV) 直接推送安装 APK。
# 协议逆向自 aerial-tv / Tigris 项目，针对 tcl_mt5879_cn / mt9653 平台（与本项目电视一致）。
# 原理：adb reverse 把电视的 127.0.0.1:8765 映射到本机 -> 本机起 HTTP 服务喂 APK ->
#       通过 TCP 6553 的 T-Cast 私有协议下发 INSTALL 指令 -> 电视弹「同意」-> 系统身份静默安装
#       （绕过 PackageInstaller 的 INSTALL_FAILED_VERIFICATION_FAILURE）。

import socket, struct, hashlib, sys, time, threading, subprocess, http.server, re, os
from pathlib import Path

TV_IP      = sys.argv[1] if len(sys.argv) > 1 else "192.168.5.25"
APK        = sys.argv[2] if len(sys.argv) > 2 else str(
    Path(__file__).resolve().parents[2] / "app/build/outputs/apk/release/app-release.apk")
PACKAGE    = os.environ.get("TV_PKG", "com.novum.tv")
# 电视端的安装确认弹窗会显示这个名称，必须和应用名一致（app_name = Novum）
LABEL      = os.environ.get("TV_LABEL", "Novum")
VERSION    = "1"
PORT       = 6553
SERVE_PORT = 8765
ADB        = os.environ.get("ADB_PATH", r"D:/Novum-sdk/platform-tools/adb.exe")

if not os.path.exists(APK):
    print(f"[x] 找不到 APK: {APK}", flush=True); sys.exit(2)

size_mb = max(1, int(os.path.getsize(APK) / 1024 / 1024))

def adb_run(args, timeout=30):
    # 弹窗 XML 含非 GBK 字节，GBK 控制台下 text=True 解码会崩——强制 utf-8 + 容错
    return subprocess.run(
        [ADB, *args], capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=timeout,
    )

def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80)); return s.getsockname()[0]
    finally:
        s.close()

def ensure_reverse():
    """先 connect 让同一个 adb 守护进程记住设备，再 reverse（否则 reverse 时设备会丢失）。"""
    for _ in range(4):
        adb_run(["connect", f"{TV_IP}:5555"])
        dev = adb_run(["devices"]).stdout
        if f"{TV_IP}:5555\tdevice" in dev:
            rr = adb_run(["reverse", f"tcp:{SERVE_PORT}", f"tcp:{SERVE_PORT}"])
            if rr.returncode == 0:
                print(f"[*] adb reverse 已建立 (TV {TV_IP}:{SERVE_PORT} -> 本机)", flush=True)
                return True
            print(f"[!] reverse 返回: {rr.stderr.strip()}", flush=True)
        time.sleep(1.5)
    print("[!] 无法建立 adb reverse", flush=True)
    return False

# ---- 本机 HTTP 服务：把 APK 喂给电视（绑 0.0.0.0，兼容 reverse 与局域网兜底）----
class _H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            data = open(APK, "rb").read()
        except Exception as e:
            self.send_error(500, str(e)); return
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def log_message(self, *a): pass

_httpd = http.server.HTTPServer(("0.0.0.0", SERVE_PORT), _H)
threading.Thread(target=_httpd.serve_forever, daemon=True).start()

# 决定电视下载地址：优先 reverse(127.0.0.1)，否则退化用本机局域网地址
url = f"http://127.0.0.1:{SERVE_PORT}/app-release.apk"
if ensure_reverse():
    pass
else:
    url = f"http://{lan_ip()}:{SERVE_PORT}/app-release.apk"
    print(f"[!] 改用局域网地址 {url}（需电视可访问、防火墙放行 8765）", flush=True)
path_md5 = hashlib.md5(url.encode()).hexdigest()
print(f"[*] 本机 HTTP 服务已起，APK {size_mb}MB，电视将下载: {url}", flush=True)

def send(sock, text):
    data = text.encode("utf-8")
    sock.sendall(struct.pack(">i", len(data)) + data)

def tap_agree():
    """电视弹窗后自动点「同意」；失败则提示手动。"""
    for attempt in range(3):
        time.sleep(2.0)
        try:
            adb_run(["shell", "uiautomator", "dump", "/sdcard/win.xml"], timeout=25)
            xml = adb_run(["shell", "cat", "/sdcard/win.xml"], timeout=25).stdout
            cand = re.search(r'id="com\.tcl\.MultiScreenInteraction_TV:id/btn_positive"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml) \
                or re.search(r'text="同意"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
            if cand:
                x = (int(cand.group(1)) + int(cand.group(3))) // 2
                y = (int(cand.group(2)) + int(cand.group(4))) // 2
                adb_run(["shell", "input", "tap", str(x), str(y)])
                print(f"[auto] 已自动点击「同意」({x},{y})", flush=True)
                return
        except Exception as e:
            print(f"[auto] 第{attempt+1}次尝试失败: {e}", flush=True)
    print("[手动] 请在电视遥控器上选择「同意」（焦点默认在「拒绝」，先按右键再按 OK）", flush=True)

def cleanup():
    try: _httpd.shutdown()
    except Exception: pass
    adb_run(["reverse", "--remove", f"tcp:{SERVE_PORT}"])

def main():
    sock = socket.create_connection((TV_IP, PORT), timeout=5)
    sock.settimeout(1)
    send(sock, "159>>1>>0")                 # 握手（不加密）
    time.sleep(0.5)
    install = ">>".join(["223", "INSTALL", PACKAGE, LABEL, url, "2", VERSION, str(size_mb), "", "", "", path_md5])
    send(sock, install)                     # 下发安装指令
    print(f"[*] 已下发 INSTALL 指令 -> {TV_IP}:{PORT}", flush=True)

    buf = b""
    last_beat = time.time()
    deadline = time.time() + 220
    tapped = False
    try:
        while time.time() < deadline:
            if time.time() - last_beat > 4:
                send(sock, "150>>"); last_beat = time.time()   # 心跳保活
            try:
                d = sock.recv(4096)
            except socket.timeout:
                continue
            if not d:
                print("[!] 电视断开连接", flush=True); break
            buf += d
            while len(buf) >= 4:
                n = struct.unpack(">i", buf[:4])[0]
                if len(buf) < 4 + n: break
                msg = buf[4:4+n].decode("utf-8", "replace")
                buf = buf[4+n:]
                print("<<", msg[:200], flush=True)
                p = msg.split(">>")
                if p[0] == "223" and p[1] == "INSTALL":
                    status = p[4] if len(p) > 4 else ""
                    if status == "confirm_pending" and not tapped:
                        tapped = True
                        threading.Thread(target=tap_agree, daemon=True).start()
                    elif status == "denied":
                        print("[x] 用户拒绝了安装", flush=True); return
                elif p[0] == "223" and p[1] == "PROGRESS":
                    stage = p[4] if len(p) > 4 else ""
                    if stage == "1":   print("[..] 电视正在下载 APK", flush=True)
                    elif stage == "3":
                        print("[..] 电视正在安装", flush=True)
                        if not tapped:
                            tapped = True
                            threading.Thread(target=tap_agree, daemon=True).start()
                    elif stage == "5":
                        print(f"✅ 安装完成！可在电视应用列表找到 {LABEL}", flush=True); return
                    elif stage == "2":
                        print("[x] 安装失败（可能是下载地址不可达或签名被拦）", flush=True); return
        print("[!] 超时未收到安装结果", flush=True)
    finally:
        try: sock.close()
        finally: cleanup()

if __name__ == "__main__":
    main()
