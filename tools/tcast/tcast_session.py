#!/usr/bin/env python3
"""Keep one T-Cast connection for install, screenshots and remote keys; never uses ADB.

Commands are JSON lines on stdin, e.g. {"action":"screenshot","name":"before"},
{"action":"install"}, {"action":"keys","codes":[14,15]},
{"action":"send","message":"223>>GET"}, {"action":"quit"}.
Protocol reference: https://github.com/pengchujin/aerial-tv/blob/main/docs/tcast-protocol.md
"""
import argparse
import hashlib
import http.server
import json
import queue
import re
import socket
import struct
import sys
import threading
import time
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tv")
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, default=Path("build/tcast-session"))
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--host-ip", help="LAN address used to serve the APK (avoids a VPN adapter address)")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    apk = args.apk.resolve(strict=True)
    route = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    route.connect((args.tv, 6553))
    host = args.host_ip or route.getsockname()[0]
    route.close()
    downloads = 0

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            nonlocal downloads
            if self.path.split("?", 1)[0] != "/Novum.apk":
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(apk.stat().st_size))
            self.end_headers()
            try:
                with apk.open("rb") as source:
                    while chunk := source.read(1024 * 1024):
                        self.wfile.write(chunk)
                downloads += 1
                print(f"HTTP completed APK transfer to {self.client_address[0]}", flush=True)
            except (BrokenPipeError, ConnectionResetError):
                print("HTTP APK transfer interrupted", flush=True)

        def log_message(self, *unused):
            pass

    server = http.server.ThreadingHTTPServer((host, args.port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    commands = queue.Queue()

    def read_commands():
        for line in sys.stdin:
            try:
                commands.put(json.loads(line))
            except ValueError as error:
                print(f"Invalid command: {error}", flush=True)

    threading.Thread(target=read_commands, daemon=True).start()
    sock = socket.create_connection((args.tv, 6553), timeout=5, source_address=(host, 0))
    sock.settimeout(0.25)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    def send(message):
        body = message.encode("utf-8")
        sock.sendall(struct.pack(">i", len(body)) + body)
        if message != "150>>":
            print("SEND", message, flush=True)

    send("159>>1>>0")
    url = f"http://{host}:{args.port}/Novum.apk"
    print(f"READY no-ADB T-Cast session, APK URL={url}", flush=True)
    buffer = b""
    last_beat = time.monotonic()
    screenshot_name = "screen"
    try:
        while True:
            while not commands.empty():
                command = commands.get_nowait()
                action = command.get("action")
                if action == "quit":
                    return 0
                if action == "send":
                    send(command["message"])
                elif action == "keys":
                    for code in command["codes"]:
                        send(f"149>>{int(code)}")
                        time.sleep(0.3)
                elif action == "screenshot":
                    screenshot_name = re.sub(r"[^A-Za-z0-9_-]", "_", command.get("name", "screen"))
                    send("225>>")
                elif action == "install":
                    install_url = command.get("url", url)
                    path_md5 = hashlib.md5(install_url.encode()).hexdigest()
                    send(">>".join(["223", "INSTALL", "com.novum.tv", "Novum", install_url,
                                     "2", "1", str(max(1, apk.stat().st_size // (1024 * 1024))),
                                     "", "", "", path_md5]))
            if time.monotonic() - last_beat >= 4:
                send("150>>")
                last_beat = time.monotonic()
            try:
                chunk = sock.recv(65536)
                if not chunk:
                    print("TV disconnected", flush=True)
                    return 1
                buffer += chunk
            except socket.timeout:
                continue
            while len(buffer) >= 4:
                length = struct.unpack(">i", buffer[:4])[0]
                if not 0 <= length < 1048576:
                    raise ValueError(f"Invalid frame length {length}")
                if len(buffer) < length + 4:
                    break
                message = buffer[4:length + 4].decode("utf-8", "replace")
                buffer = buffer[length + 4:]
                if message.startswith("150>>"):
                    continue
                if message.startswith("223>>GET"):
                    (args.output / "apps.txt").write_text(message, encoding="utf-8")
                    print("APPS saved; Novum installed:", "com.novum.tv::" in message, flush=True)
                else:
                    print("RECV", message[:450], flush=True)
                if message.startswith("225>>"):
                    match = re.search(r"https?://[^>\s]+", message)
                    if match:
                        path = args.output / (screenshot_name + ".png")
                        try:
                            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                            with opener.open(match.group(), timeout=10) as response, path.open("wb") as output:
                                output.write(response.read())
                            print("SCREENSHOT", path.resolve(), flush=True)
                        except OSError as error:
                            print("SCREENSHOT error", str(error), flush=True)
    finally:
        sock.close()
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    sys.exit(main())
