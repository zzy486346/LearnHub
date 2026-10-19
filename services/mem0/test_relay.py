"""仅隔离验收使用的固定目标 HTTP 入口；不读取或保存任何服务/供应商密钥。"""
import http.client
import json
import os
import re
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

MAX_BODY = 16 * 1024
MAX_RESPONSE = 64 * 1024
TIMEOUT = 10
PREFERENCE_PATH = re.compile(r"^/preferences/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
TARGET_HOST = os.environ.get("MEMORY_RELAY_TARGET_HOST", "memory-api")
TARGET_PORT = os.environ.get("MEMORY_RELAY_TARGET_PORT", "8000")
if TARGET_HOST != "memory-api" or TARGET_PORT != "8000":
    raise RuntimeError("Relay target must be the fixed private memory-api:8000 endpoint")


class RelayServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 4

    def __init__(self, address):
        self.slots = threading.BoundedSemaphore(4)
        super().__init__(address, Relay)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()

    def handle_error(self, request, address):
        # 不输出 Authorization、正文、请求路径或带连接配置的 traceback。
        pass


class Relay(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        self.connection.settimeout(TIMEOUT)

    def log_message(self, *args):
        pass

    def answer(self, status, payload, content_type="application/json; charset=utf-8"):
        self.close_connection = True
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def reject(self, status):
        self.answer(status, json.dumps({"detail": "MEMORY_TEST_RELAY_REJECTED"}).encode())

    def send_error(self, code, message=None, explain=None):
        self.reject(code)

    def forward(self):
        upstream = None
        try:
            target = urlsplit(self.path)
            if (len(self.path) > 1024 or target.scheme or target.netloc or target.fragment
                    or any(ord(char) < 32 or ord(char) == 127 for char in self.path)):
                self.reject(400)
                return
            allowed = {"GET": {"/health", "/stats", "/preferences"},
                       "POST": {"/preferences/search"}}
            path_ok = target.path in allowed.get(self.command, set())
            if self.command in ("PUT", "DELETE") and PREFERENCE_PATH.fullmatch(target.path):
                path_ok = True
            if not path_ok or (target.query and not (self.command == "GET" and target.path == "/preferences")):
                self.reject(404)
                return
            lengths = self.headers.get_all("Content-Length", [])
            if self.headers.get_all("Transfer-Encoding", []) or len(lengths) > 1:
                self.reject(400)
                return
            if lengths and not re.fullmatch(r"[0-9]{1,8}", lengths[0]):
                self.reject(400)
                return
            size = int(lengths[0]) if lengths else 0
            if size > MAX_BODY:
                self.reject(413)
                return
            if self.command == "GET" and size:
                self.reject(400)
                return
            authorization = self.headers.get_all("Authorization", [])
            if len(authorization) > 1:
                self.reject(400)
                return
            body = self.rfile.read(size) if size else b""
            if len(body) != size:
                self.reject(400)
                return
            headers = {"Connection": "close", "Accept": "application/json"}
            if authorization:
                headers["Authorization"] = authorization[0]
            if size:
                headers["Content-Type"] = self.headers.get("Content-Type", "application/json")
            # 固定 host/port + origin-form path，拒绝任意目的 URL，不使用系统代理。
            upstream = http.client.HTTPConnection(TARGET_HOST, int(TARGET_PORT), timeout=TIMEOUT)
            upstream.request(self.command, self.path, body=body, headers=headers)
            response = upstream.getresponse()
            data = response.read(MAX_RESPONSE + 1)
            if len(data) > MAX_RESPONSE or 300 <= response.status < 400:
                self.reject(502)
                return
            self.answer(response.status, data, response.getheader("Content-Type", "application/json"))
        except (TimeoutError, socket.timeout):
            self.reject(504)
        except (OSError, ValueError, http.client.HTTPException):
            self.reject(502)
        finally:
            if upstream is not None:
                upstream.close()

    do_GET = forward
    do_PUT = forward
    do_POST = forward
    do_DELETE = forward


if __name__ == "__main__":
    with RelayServer(("0.0.0.0", 8080)) as server:
        server.serve_forever(poll_interval=0.5)
