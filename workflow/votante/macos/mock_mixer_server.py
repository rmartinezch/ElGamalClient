#!/usr/bin/env python3
import argparse
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


def to_hex(path: Path) -> str:
    return path.read_bytes().hex()


class MockMixerHandler(BaseHTTPRequestHandler):
    key_hex = ""
    base_url = ""
    session_id = "sesion-mock-001"
    session_name = "Sesion Mock"
    session_label = "Sesion Mock"
    election_name = "Eleccion Mock"

    def _json(self, payload, status=200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        params = parse_qs(parsed.query)
        requested_auxsid = (params.get("auxsid", ["default"])[0] or "default").strip() or "default"

        if path == "/api/discovery":
            self._json(
                {
                    "ok": True,
                    "server": {
                        "instance_id": "mock-mixer",
                        "hostname": "localhost",
                        "public_host": "127.0.0.1",
                        "api_url": self.base_url,
                        "handshake_url": f"{self.base_url}/api/handshake",
                        "public_key_url": f"{self.base_url}/api/public-key",
                        "ciphertexts_url": f"{self.base_url}/api/ciphertexts",
                        "emission_context_url": f"{self.base_url}/api/emission-context",
                        "has_active_session": True,
                        "keygen_ready": True,
                        "accepting_votes": True,
                        "current_operation": None,
                        "handshake_ttl_seconds": 90,
                    },
                    "has_active_session": True,
                    "keygen_ready": True,
                    "accepting_votes": True,
                    "session": {
                        "session_id": self.session_id,
                        "session_name": self.session_name,
                        "session_label": self.session_label,
                        "election_name": self.election_name,
                    },
                    "session_id": self.session_id,
                    "session_name": self.session_name,
                    "session_label": self.session_label,
                    "election_name": self.election_name,
                }
            )
            return

        if path == "/api/emission-context":
            self._json(
                {
                    "ok": True,
                    "session_id": self.session_id,
                    "session_name": self.session_name,
                    "session_label": self.session_label,
                    "election_name": self.election_name,
                    "requested_auxsid": requested_auxsid,
                    "resolved_auxsid": requested_auxsid,
                    "auxsid": requested_auxsid,
                    "auxsid_changed": False,
                    "accumulated": False,
                    "accumulated_from_auxsid": "",
                    "accepting_votes": True,
                    "service_busy": False,
                }
            )
            return

        if path == "/api/state":
            self._json(
                {
                    "ok": True,
                    "session_id": self.session_id,
                    "session_name": self.session_name,
                    "session_label": self.session_label,
                    "accepting_votes": True,
                }
            )
            return

        if path == "/api/auxsids":
            self._json({"ok": True, "suggested_auxsid": requested_auxsid})
            return

        if path == "/api/public-key":
            self._json(
                {
                    "ok": True,
                    "session_id": self.session_id,
                    "session_name": self.session_name,
                    "session_label": self.session_label,
                    "election_name": self.election_name,
                    "content": self.key_hex,
                }
            )
            return

        self._json({"ok": False, "error": "not-found", "path": path}, status=404)

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        raw = self.rfile.read(int(self.headers.get("Content-Length", "0") or "0"))
        payload = json.loads(raw.decode("utf-8") or "{}") if raw else {}

        if path == "/api/handshake":
            requested_auxsid = (payload.get("auxsid") or "default").strip() or "default"
            self._json(
                {
                    "ok": True,
                    "accepted": True,
                    "reason": "ok",
                    "station_id": payload.get("station_id", "mesa-mock"),
                    "requested_auxsid": requested_auxsid,
                    "lease_id": "mock-lease-001",
                    "expires_at": "2099-01-01T00:00:00Z",
                    "session_id": self.session_id,
                    "session_name": self.session_name,
                    "session_label": self.session_label,
                    "election_name": self.election_name,
                }
            )
            return

        if path == "/api/ciphertexts":
            self._json(
                {
                    "ok": True,
                    "accepted": True,
                    "auxsid": payload.get("auxsid", "default"),
                    "resolved_auxsid": payload.get("auxsid", "default"),
                    "accumulated": False,
                    "accumulated_from_auxsid": "",
                    "receipt": {
                        "accepted": True,
                        "validated": True,
                        "party_validated": "party01",
                        "format_resolved": payload.get("format", "native"),
                        "session_id": self.session_id,
                        "session_name": self.session_name,
                        "session_label": self.session_label,
                        "election_name": self.election_name,
                        "written_files": ["ciphertexts_ext"],
                        "replicated_to": ["party01"],
                    },
                }
            )
            return

        self._json({"ok": False, "error": "not-found", "path": path}, status=404)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=7040)
    parser.add_argument("--public-key", default="recursos/publicKey")
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parents[3]
    key_path = (project_root / args.public_key).resolve()

    if not key_path.exists():
        raise SystemExit(f"No se encontro la llave publica fixture: {key_path}")

    MockMixerHandler.key_hex = to_hex(key_path)
    MockMixerHandler.base_url = f"http://{args.host}:{args.port}"

    server = HTTPServer((args.host, args.port), MockMixerHandler)
    print(f"[mock-mixer] escuchando en {MockMixerHandler.base_url}")
    server.serve_forever()


if __name__ == "__main__":
    main()
