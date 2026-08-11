#!/usr/bin/env python3
"""LAN discovery hub + static console for Realtime Debug.

Same Wi-Fi, many phones → distinguish by UID.
Console stays up; enter UID to resolve ip:17890 and connect.

Discovery:
1) UDP beacons from phones (port 17891)
2) Active TCP scan of local /24 for WS :17890, read hello.uid
3) On-demand scan when looking up a UID
"""

from __future__ import annotations

import base64
import json
import os
import shutil
import socket
import struct
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent
HTTP_PORT = 8765
BEACON_PORT = 17891
WS_PORT = 17890
USB_LOCAL_PORT_BASE = 17890
DEVICE_TTL_SEC = 45.0
CACHE_FILE = ROOT / ".devices-cache.json"
SCAN_INTERVAL_SEC = 6.0

_devices: dict[str, dict] = {}
_known_ips: set[str] = set()
_lock = threading.Lock()
_scan_lock = threading.Lock()
_last_scan_at = 0.0
_adb_path: str | None = None
_usb_forwards: dict[str, int] = {}  # serial -> local port


def _load_cache() -> None:
    try:
        if not CACHE_FILE.exists():
            return
        data = json.loads(CACHE_FILE.read_text(encoding="utf-8"))
        for item in data.get("devices") or []:
            uid = str(item.get("uid") or "").strip()
            ip = str(item.get("ip") or "").strip()
            if uid and ip:
                item = dict(item)
                item["source"] = item.get("source") or "cache"
                item["lastSeen"] = time.time() - DEVICE_TTL_SEC + 8  # soft-alive briefly
                with _lock:
                    _devices[uid] = item
                    _known_ips.add(ip)
    except Exception:
        pass


def _save_cache() -> None:
    try:
        with _lock:
            items = list(_devices.values())
        CACHE_FILE.write_text(
            json.dumps({"devices": items}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    except Exception:
        pass


def _upsert_device(payload: dict) -> None:
    uid = str(payload.get("uid") or "").strip()
    ip = str(payload.get("ip") or "").strip()
    if not uid or not ip or ip.startswith("127.") or ip == "0.0.0.0":
        return
    try:
        port = int(payload.get("port") or WS_PORT)
    except (TypeError, ValueError):
        port = WS_PORT
    item = {
        "uid": uid,
        "ip": ip,
        "port": port,
        "kind": payload.get("kind") or "device",
        "model": payload.get("model") or "",
        "ws": f"ws://{ip}:{port}",
        "lastSeen": time.time(),
        "ts": payload.get("ts"),
        "source": payload.get("source") or "beacon",
    }
    with _lock:
        # drop stale uid sharing same IP
        stale = [k for k, v in _devices.items() if v.get("ip") == ip and k != uid]
        for k in stale:
            del _devices[k]
        _devices[uid] = item
        _known_ips.add(ip)


def _alive_devices() -> list[dict]:
    now = time.time()
    with _lock:
        dead = [k for k, v in _devices.items() if now - v.get("lastSeen", 0) > DEVICE_TTL_SEC]
        for k in dead:
            del _devices[k]
        items = list(_devices.values())
    items.sort(key=lambda d: d.get("uid") or "")
    return items


def _match_uid(uid_query: str, alive: list[dict]) -> dict | None:
    q = (uid_query or "").strip()
    if not q:
        return None
    for d in alive:
        if d["uid"] == q:
            return d
    matches = [d for d in alive if q in d["uid"] or d["uid"].endswith(q)]
    if len(matches) == 1:
        return matches[0]
    return None


def _find_device(uid_query: str) -> dict | None:
    return _match_uid(uid_query, _alive_devices())


def _local_ipv4s() -> list[str]:
    ips: list[str] = []
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127."):
            ips.append(ip)
    except Exception:
        pass
    out: list[str] = []
    for ip in ips:
        if ip not in out:
            out.append(ip)
    return out


def _ws_hello_uid(ip: str, port: int = WS_PORT, timeout: float = 2.0) -> dict | None:
    """Minimal WebSocket handshake; read frames until hello JSON appears."""
    try:
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        req = (
            f"GET / HTTP/1.1\r\n"
            f"Host: {ip}:{port}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n"
            f"\r\n"
        ).encode("ascii")
        sock = socket.create_connection((ip, port), timeout=timeout)
        sock.settimeout(timeout)
        sock.sendall(req)
        buf = b""
        while b"\r\n\r\n" not in buf and len(buf) < 8192:
            chunk = sock.recv(1024)
            if not chunk:
                break
            buf += chunk
        if b"101" not in buf.split(b"\r\n", 1)[0]:
            sock.close()
            return None
        rest = buf.split(b"\r\n\r\n", 1)[1]
        deadline = time.time() + timeout
        while time.time() < deadline:
            while len(rest) < 2:
                rest += sock.recv(1024)
            b1, b2 = rest[0], rest[1]
            opcode = b1 & 0x0F
            masked = (b2 & 0x80) != 0
            length = b2 & 0x7F
            idx = 2
            if length == 126:
                while len(rest) < idx + 2:
                    rest += sock.recv(1024)
                length = struct.unpack("!H", rest[idx:idx + 2])[0]
                idx += 2
            elif length == 127:
                while len(rest) < idx + 8:
                    rest += sock.recv(1024)
                length = struct.unpack("!Q", rest[idx:idx + 8])[0]
                idx += 8
            if masked:
                while len(rest) < idx + 4:
                    rest += sock.recv(1024)
                mask = rest[idx:idx + 4]
                idx += 4
            else:
                mask = None
            while len(rest) < idx + length:
                rest += sock.recv(4096)
            payload = bytearray(rest[idx:idx + length])
            rest = rest[idx + length:]
            if mask:
                for i in range(len(payload)):
                    payload[i] ^= mask[i % 4]
            if opcode == 0x1:
                try:
                    data = json.loads(bytes(payload).decode("utf-8"))
                except Exception:
                    continue
                if data.get("type") == "hello":
                    sock.close()
                    uid = str(data.get("uid") or "").strip()
                    if not uid:
                        uid = f"ip-{ip.replace('.', '-')}"
                    phone_ip = str(data.get("ip") or "").strip()
                    if not phone_ip or phone_ip.startswith("127.") or phone_ip == "0.0.0.0":
                        phone_ip = ip
                    return {
                        "uid": uid,
                        "ip": phone_ip,
                        "port": int(data.get("port") or port),
                        "kind": data.get("kind") or "scan",
                        "model": data.get("model") or "",
                        "source": "scan",
                        "ts": int(time.time() * 1000),
                    }
            elif opcode == 0x8:
                break
        sock.close()
        return None
    except Exception:
        return None


def _port_open(ip: str, port: int = WS_PORT, timeout: float = 0.2) -> bool:
    try:
        s = socket.create_connection((ip, port), timeout=timeout)
        s.close()
        return True
    except Exception:
        return False


def _arp_ips() -> list[str]:
    """Windows/Linux ARP table — catches phones on /23+ that share L2 with us."""
    out: list[str] = []
    try:
        import subprocess
        raw = subprocess.check_output(["arp", "-a"], text=True, errors="ignore", timeout=5)
        for token in raw.replace("\t", " ").split():
            parts = token.split(".")
            if len(parts) == 4 and all(p.isdigit() and 0 <= int(p) <= 255 for p in parts):
                ip = token.strip()
                if not ip.startswith("127.") and ip not in out:
                    out.append(ip)
    except Exception:
        pass
    return out


def _prefixes_for_ip(ip: str) -> list[str]:
    """Own /24 plus neighbors — enterprise Wi‑Fi often uses /23 so phone may be .2.x while PC is .0.x."""
    parts = ip.split(".")
    if len(parts) != 4:
        return []
    try:
        third = int(parts[2])
    except ValueError:
        return []
    prefixes = []
    for delta in (0, -1, 1, -2, 2):
        n = third + delta
        if 0 <= n <= 255:
            p = f"{parts[0]}.{parts[1]}.{n}"
            if p not in prefixes:
                prefixes.append(p)
    return prefixes


def _candidate_hosts() -> list[str]:
    with _lock:
        known = list(_known_ips)
    bases = _local_ipv4s()
    candidates: list[str] = []
    # known + ARP first (fast path)
    candidates.extend(known)
    candidates.extend(_arp_ips())
    prefixes: list[str] = []
    for base in bases:
        for p in _prefixes_for_ip(base):
            if p not in prefixes:
                prefixes.append(p)
    for prefix in prefixes:
        for i in range(1, 255):
            candidates.append(f"{prefix}.{i}")
    seen: set[str] = set()
    hosts: list[str] = []
    for ip in candidates:
        if ip not in seen and not ip.startswith("127."):
            seen.add(ip)
            hosts.append(ip)
    return hosts


def _probe_host(ip: str) -> dict | None:
    if not _port_open(ip):
        return None
    return _ws_hello_uid(ip)


def _scan_once(prefer_uid: str | None = None) -> list[dict]:
    """Scan LAN; if prefer_uid set, stop early once that uid is found."""
    global _last_scan_at
    if not _scan_lock.acquire(blocking=False):
        # another scan in progress — wait briefly for registry update
        time.sleep(1.5)
        return _alive_devices()
    try:
        hosts = _candidate_hosts()
        found_uid = False
        with ThreadPoolExecutor(max_workers=48) as pool:
            futures = {pool.submit(_probe_host, ip): ip for ip in hosts}
            for fut in as_completed(futures, timeout=max(12.0, len(hosts) * 0.05)):
                try:
                    hello = fut.result()
                except Exception:
                    hello = None
                if not hello:
                    continue
                _upsert_device(hello)
                if prefer_uid and (
                    hello.get("uid") == prefer_uid
                    or prefer_uid in str(hello.get("uid") or "")
                    or str(hello.get("uid") or "").endswith(prefer_uid)
                ):
                    found_uid = True
                    break
            if found_uid:
                # cancel remaining
                for f in futures:
                    f.cancel()
        _last_scan_at = time.time()
        _save_cache()
        return _alive_devices()
    except Exception as e:
        print("[warn] scan error:", e)
        return _alive_devices()
    finally:
        _scan_lock.release()


def _scan_loop() -> None:
    print("[ok] LAN scanner for :17890 started")
    while True:
        try:
            _scan_once()
            n = len(_alive_devices())
            if n:
                print(f"[ok] online devices: {n}")
        except Exception as e:
            print("[warn] scan loop:", e)
        time.sleep(SCAN_INTERVAL_SEC)


def _beacon_loop() -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", BEACON_PORT))
    except OSError as e:
        print(f"[warn] bind UDP {BEACON_PORT} failed: {e}")
        return
    print(f"[ok] UDP discovery listening on :{BEACON_PORT}")
    while True:
        try:
            data, _addr = sock.recvfrom(4096)
            payload = json.loads(data.decode("utf-8", errors="ignore"))
            if payload.get("type") == "nsdebug":
                payload["source"] = "beacon"
                _upsert_device(payload)
                _save_cache()
        except Exception:
            continue


def _find_adb() -> str | None:
    global _adb_path
    if _adb_path and Path(_adb_path).exists():
        return _adb_path
    candidates = [
        os.environ.get("ADB") or "",
        r"D:\Tools\platform-tools\adb.exe",
        shutil.which("adb") or "",
    ]
    for c in candidates:
        if c and Path(c).exists():
            _adb_path = c
            return c
        if c == "adb" or (c and shutil.which(c)):
            _adb_path = c
            return c
    which = shutil.which("adb")
    if which:
        _adb_path = which
        return which
    return None


def _adb_run(args: list[str], timeout: float = 20.0) -> tuple[int, str, str]:
    adb = _find_adb()
    if not adb:
        return 127, "", "adb not found"
    try:
        r = subprocess.run(
            [adb, *args],
            capture_output=True,
            text=True,
            timeout=timeout,
            encoding="utf-8",
            errors="ignore",
        )
        return r.returncode, r.stdout or "", r.stderr or ""
    except Exception as e:
        return 1, "", str(e)


def _list_adb_devices() -> list[dict]:
    code, out, err = _adb_run(["devices", "-l"])
    if code != 0:
        return []
    devices: list[dict] = []
    for line in out.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], parts[1]
        model = ""
        product = ""
        for p in parts[2:]:
            if p.startswith("model:"):
                model = p.split(":", 1)[1].replace("_", " ")
            elif p.startswith("product:"):
                product = p.split(":", 1)[1]
        devices.append({
            "serial": serial,
            "state": state,
            "model": model or product or serial,
            "localPort": _usb_forwards.get(serial),
            "ws": f"ws://127.0.0.1:{_usb_forwards[serial]}" if serial in _usb_forwards else None,
        })
    return devices


def _alloc_usb_port(serial: str) -> int:
    if serial in _usb_forwards:
        return _usb_forwards[serial]
    used = set(_usb_forwards.values())
    port = USB_LOCAL_PORT_BASE
    while port in used or port == BEACON_PORT:
        port += 10
    _usb_forwards[serial] = port
    return port


def _usb_setup(serial: str | None = None) -> dict:
    adb = _find_adb()
    if not adb:
        return {"ok": False, "error": "adb_not_found", "hint": "未找到 adb，请安装 platform-tools 或设置环境变量 ADB"}
    devices = [d for d in _list_adb_devices() if d["state"] == "device"]
    if not devices:
        return {"ok": False, "error": "no_device", "hint": "未检测到 USB 设备，请开开发者模式并授权调试"}
    if serial:
        target = next((d for d in devices if d["serial"] == serial), None)
        if not target:
            return {"ok": False, "error": "serial_not_found", "serial": serial, "devices": devices}
    else:
        if len(devices) > 1 and not serial:
            return {"ok": False, "error": "need_serial", "hint": "多台 USB 设备，请选择一台", "devices": devices}
        target = devices[0]
    serial = target["serial"]
    local_port = _alloc_usb_port(serial)
    # clear old forward on this local port then recreate
    _adb_run(["-s", serial, "forward", "--remove", f"tcp:{local_port}"], timeout=8)
    code, out, err = _adb_run(
        ["-s", serial, "forward", f"tcp:{local_port}", f"tcp:{WS_PORT}"],
        timeout=15,
    )
    if code != 0:
        return {
            "ok": False,
            "error": "forward_failed",
            "hint": (err or out or "adb forward 失败").strip(),
            "serial": serial,
            "localPort": local_port,
        }
    # probe hello through the tunnel
    hello = _ws_hello_uid("127.0.0.1", local_port, timeout=2.5)
    ws = f"ws://127.0.0.1:{local_port}"
    result = {
        "ok": True,
        "mode": "usb",
        "serial": serial,
        "model": target.get("model") or serial,
        "localPort": local_port,
        "remotePort": WS_PORT,
        "ws": ws,
        "adb": adb,
        "hello": hello,
        "uid": (hello or {}).get("uid") if hello else None,
        "devices": _list_adb_devices(),
    }
    if not hello:
        result["warn"] = "通道已建立，但未读到 hello（确认装的是 dev 包且 App 已打开）"
    return result


def _usb_status() -> dict:
    adb = _find_adb()
    devices = _list_adb_devices() if adb else []
    return {
        "ok": True,
        "adb": adb,
        "devices": devices,
        "forwards": dict(_usb_forwards),
    }


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def log_message(self, fmt: str, *args) -> None:
        if self.path.startswith("/api/"):
            return
        super().log_message(fmt, *args)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/devices":
            body = json.dumps({"devices": _alive_devices()}, ensure_ascii=False).encode("utf-8")
            self._json(body)
            return
        if parsed.path == "/api/device":
            qs = parse_qs(parsed.query or "")
            uid = (qs.get("uid") or [""])[0]
            refresh = (qs.get("refresh") or ["0"])[0] in ("1", "true", "yes")
            device = _find_device(uid)
            if (not device or refresh) and uid.strip():
                _scan_once(prefer_uid=uid.strip())
                device = _find_device(uid)
            if not device:
                body = json.dumps(
                    {"ok": False, "error": "not_found", "uid": uid},
                    ensure_ascii=False,
                ).encode("utf-8")
                self._json(body, status=404)
                return
            body = json.dumps({"ok": True, "device": device}, ensure_ascii=False).encode("utf-8")
            self._json(body)
            return
        if parsed.path == "/api/scan":
            devices = _scan_once()
            body = json.dumps({"ok": True, "devices": devices}, ensure_ascii=False).encode("utf-8")
            self._json(body)
            return
        if parsed.path == "/api/usb/status":
            body = json.dumps(_usb_status(), ensure_ascii=False).encode("utf-8")
            self._json(body)
            return
        if parsed.path == "/api/usb/setup":
            qs = parse_qs(parsed.query or "")
            serial = (qs.get("serial") or [""])[0].strip() or None
            result = _usb_setup(serial)
            status = 200 if result.get("ok") else 400
            body = json.dumps(result, ensure_ascii=False).encode("utf-8")
            self._json(body, status=status)
            return
        if parsed.path in ("/", ""):
            self.path = "/index.html"
        return super().do_GET()

    def _json(self, body: bytes, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    _load_cache()
    threading.Thread(target=_beacon_loop, daemon=True).start()
    threading.Thread(target=_scan_loop, daemon=True).start()
    server = ThreadingHTTPServer(("127.0.0.1", HTTP_PORT), Handler)
    print(f"[ok] Realtime Debug Console  http://127.0.0.1:{HTTP_PORT}/")
    print("     模式：局域网(UID) / USB(adb forward)")
    adb = _find_adb()
    print(f"     adb: {adb or '未找到'}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
