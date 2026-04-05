#!/usr/bin/env python3
"""
Bridge local para la app nativa iOS en simulador.

Motivacion:
- el runtime nativo iOS puede arrancar y hacer bootstrap contra la mezcladora
- el JDK del host macOS puede fallar con "No route to host" hacia la misma mezcladora
- la app iOS usa 127.0.0.1:8798 como fallback para emitir el voto en simulador

Este bridge evita ese bloqueo:
1. recibe el formulario de voto en /api/ballot/submit
2. consulta discovery/handshake/public-key con Python
3. invoca el cifrador host macOS (jar + dylib)
4. remite ciphertexts_ext a la mezcladora
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from uuid import uuid4


PROJECT_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_HOST = os.environ.get("IOS_SIM_BRIDGE_HOST", "127.0.0.1")
DEFAULT_PORT = int(os.environ.get("IOS_SIM_BRIDGE_PORT", "8798"))
DEFAULT_SERVICE_BASE_URL = os.environ.get("SERVICE_BASE_URL", "http://192.168.0.120:7040").rstrip("/")
DEFAULT_STATION_ID = os.environ.get("IOS_SIM_BRIDGE_STATION_ID", "mesa-047612")
DEFAULT_AUXSID = os.environ.get("IOS_SIM_BRIDGE_AUXSID", "default")
TIMEOUT_SECONDS = int(os.environ.get("IOS_SIM_BRIDGE_TIMEOUT", "15"))
SCHEMA_VERSION = "1.0.0-test"
VERIFICATUM_WIDTH = 1

JAVA_BIN = Path(os.environ.get("IOS_SIM_BRIDGE_JAVA_BIN", PROJECT_ROOT / ".tools/jdk-21/bin/java"))
JAR_PATH = Path(
    os.environ.get(
        "IOS_SIM_BRIDGE_JAR_PATH",
        PROJECT_ROOT / "dist/ios/library/Cifrador/app/ElGamalCipher-1.1.0.jar",
    )
)
LIB_DIR = Path(
    os.environ.get(
        "IOS_SIM_BRIDGE_LIB_DIR",
        PROJECT_ROOT / "dist/ios/library/Cifrador/libs/macos-arm64",
    )
)
RUNTIME_DIR = Path(
    os.environ.get(
        "IOS_SIM_BRIDGE_RUNTIME_DIR",
        PROJECT_ROOT / "workflow/votante/ios/runtime/fallback-bridge",
    )
)


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def event(message: str) -> str:
    return f"{now_iso()} | {message}"


def first_non_blank(*values: str | None) -> str:
    for value in values:
        if value is not None:
            trimmed = value.strip()
            if trimmed:
                return trimmed
    return ""


def trim_trailing_slash(value: str) -> str:
    trimmed = value.strip()
    while trimmed.endswith("/"):
        trimmed = trimmed[:-1]
    return trimmed


def normalize_optional_code(value: str | None) -> str:
    trimmed = "" if value is None else value.strip()
    if not trimmed:
        return "00"
    if len(trimmed) != 2 or not trimmed.isdigit():
        raise ValueError(f"Codigo invalido, se esperaban 2 digitos: {trimmed}")
    return trimmed


def normalize_required_code(field: str, value: str | None) -> str:
    normalized = normalize_optional_code(value)
    if normalized == "00":
        raise ValueError(f"Campo obligatorio faltante: {field}")
    return normalized


def build_bundle(form: dict[str, str]) -> str:
    district_code = normalize_required_code("districtCode", form.get("districtCode"))
    presidential_party = normalize_required_code("presidentialParty", form.get("presidentialParty"))
    senators_national_party = normalize_required_code(
        "senatorsNationalParty", form.get("senatorsNationalParty")
    )
    senators_national_pv1 = normalize_optional_code(form.get("senatorsNationalPv1"))
    senators_national_pv2 = normalize_optional_code(form.get("senatorsNationalPv2"))
    senators_regional_party = normalize_required_code(
        "senatorsRegionalParty", form.get("senatorsRegionalParty")
    )
    senators_regional_pv1 = normalize_optional_code(form.get("senatorsRegionalPv1"))
    deputies_party = normalize_required_code("deputiesParty", form.get("deputiesParty"))
    deputies_pv1 = normalize_optional_code(form.get("deputiesPv1"))
    deputies_pv2 = normalize_optional_code(form.get("deputiesPv2"))
    andean_party = normalize_required_code("andeanParty", form.get("andeanParty"))
    andean_pv1 = normalize_optional_code(form.get("andeanPv1"))
    andean_pv2 = normalize_optional_code(form.get("andeanPv2"))

    if senators_national_pv1 != "00" and senators_national_pv1 == senators_national_pv2:
        senators_national_pv2 = "00"
    if deputies_pv1 != "00" and deputies_pv1 == deputies_pv2:
        deputies_pv2 = "00"
    if andean_pv1 != "00" and andean_pv1 == andean_pv2:
        andean_pv2 = "00"

    lines = [
        f"0100{presidential_party}0000",
        f"0200{senators_national_party}{senators_national_pv1}{senators_national_pv2}",
        f"03{district_code}{senators_regional_party}{senators_regional_pv1}00",
        f"04{district_code}{deputies_party}{deputies_pv1}{deputies_pv2}",
        f"0500{andean_party}{andean_pv1}{andean_pv2}",
    ]
    return "\n".join(lines) + "\n"


def encode_form_payload(form: dict[str, str]) -> str:
    return urllib.parse.urlencode(form)


def parse_form_payload(raw: bytes) -> dict[str, str]:
    parsed = urllib.parse.parse_qs(raw.decode("utf-8"), keep_blank_values=True)
    return {key: values[-1] if values else "" for key, values in parsed.items()}


def http_get_json(url: str) -> dict[str, Any]:
    with urllib.request.urlopen(url, timeout=TIMEOUT_SECONDS) as response:
        return json.loads(response.read().decode("utf-8"))


def http_post_json(url: str, payload: dict[str, Any]) -> dict[str, Any]:
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        return json.loads(response.read().decode("utf-8"))


def run_cipher(public_key_file: Path, plain_votes_file: Path, ciphertexts_file: Path) -> subprocess.CompletedProcess[str]:
    cmd = [
        str(JAVA_BIN),
        "-jar",
        str(JAR_PATH),
        str(public_key_file),
        str(plain_votes_file),
        str(ciphertexts_file),
        "-sw",
    ]
    env = dict(os.environ)
    env["DYLD_LIBRARY_PATH"] = str(LIB_DIR)
    return subprocess.run(
        cmd,
        cwd=PROJECT_ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )


def ensure_runtime_layout() -> None:
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def write_bytes(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def bool_from_receipt(receipt: dict[str, Any]) -> bool:
    return bool(receipt.get("ok")) and bool(receipt.get("validated"))


def emit_vote(form: dict[str, str]) -> dict[str, Any]:
    ensure_runtime_layout()
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid4().hex[:8]
    submission_dir = RUNTIME_DIR / run_id
    submission_dir.mkdir(parents=True, exist_ok=True)
    events: list[str] = [event(f"Inicio de emision via ios-sim fallback. runId={run_id}")]

    request_form = encode_form_payload(form)
    write_text(submission_dir / "request-form.txt", request_form)

    service_base_url = trim_trailing_slash(first_non_blank(form.get("serviceBaseUrl"), DEFAULT_SERVICE_BASE_URL))
    requested_auxsid = first_non_blank(form.get("auxsid"), DEFAULT_AUXSID)
    station_id = first_non_blank(form.get("stationId"), DEFAULT_STATION_ID)
    bundle_text = build_bundle(form)
    write_text(submission_dir / "plain_votes.txt", bundle_text)
    events.append(event(f"Mezcladora seleccionada: {service_base_url}"))

    discovery = http_get_json(f"{service_base_url}/api/discovery")
    session = discovery.get("session") or {}
    handshake_payload = {
        "station_id": station_id,
        "session_id": first_non_blank(session.get("session_id")),
        "session_name": first_non_blank(session.get("session_name")),
        "auxsid": requested_auxsid,
    }
    handshake = http_post_json(f"{service_base_url}/api/handshake", handshake_payload)
    canonical_station_id = first_non_blank(handshake.get("station_id"), station_id)
    lease_id = first_non_blank(handshake.get("lease_id"))
    expires_at = first_non_blank(handshake.get("expires_at"))
    events.append(
        event(
            "Handshake activo => station_id="
            + canonical_station_id
            + " lease_id="
            + lease_id
            + " expires_at="
            + expires_at
        )
    )

    emission_context = http_get_json(
        f"{service_base_url}/api/emission-context?auxsid={urllib.parse.quote(requested_auxsid)}"
    )
    resolved_auxsid = first_non_blank(
        emission_context.get("resolved_auxsid"),
        emission_context.get("auxsid"),
        requested_auxsid,
        "default",
    )
    events.append(event(f"Auxsid operativo resuelto: {resolved_auxsid}"))

    public_key_payload = http_get_json(f"{service_base_url}/api/public-key?format=native")
    public_key_bytes = bytes.fromhex(first_non_blank(public_key_payload.get("content")))
    write_bytes(submission_dir / "publicKey", public_key_bytes)
    events.append(event(f"Llave publica nativa descargada. bytes={len(public_key_bytes)}"))

    write_text(
        submission_dir / "emission-context.json",
        json.dumps(
            {
                "service_base_url": service_base_url,
                "station_id": canonical_station_id,
                "lease_id": lease_id,
                "requested_auxsid": requested_auxsid,
                "resolved_auxsid": resolved_auxsid,
                "session_id": first_non_blank(
                    public_key_payload.get("session_id"), session.get("session_id")
                ),
                "session_name": first_non_blank(
                    public_key_payload.get("session_name"), session.get("session_name")
                ),
                "session_label": first_non_blank(
                    public_key_payload.get("session_label"),
                    session.get("session_label"),
                    session.get("session_name"),
                ),
            },
            ensure_ascii=True,
            indent=2,
        ),
    )

    events.append(event("Invocando cifrador host macOS."))
    cipher_process = run_cipher(
        submission_dir / "publicKey",
        submission_dir / "plain_votes.txt",
        submission_dir / "ciphertexts_ext",
    )
    write_text(submission_dir / "ios-cifrador.stdout.log", cipher_process.stdout)
    write_text(submission_dir / "ios-cifrador.stderr.log", cipher_process.stderr)
    events.append(event(f"Proceso de cifrado finalizado. exitCode={cipher_process.returncode}"))
    if cipher_process.returncode != 0 or not (submission_dir / "ciphertexts_ext").is_file():
        raise RuntimeError("No se genero ciphertexts_ext en el fallback local.")

    ciphertexts_text = (submission_dir / "ciphertexts_ext").read_text(encoding="utf-8")
    ciphertext_count = sum(1 for line in ciphertexts_text.splitlines() if line.strip())
    events.append(event(f"Voto cifrado generado. registros={ciphertext_count}"))

    receipt_payload = {
        "station_id": canonical_station_id,
        "lease_id": lease_id,
        "session_id": first_non_blank(public_key_payload.get("session_id"), session.get("session_id")),
        "session_name": first_non_blank(public_key_payload.get("session_name"), session.get("session_name")),
        "auxsid": resolved_auxsid,
        "format": "native",
        "ciphertexts_ext": ciphertexts_text,
        "width": VERIFICATUM_WIDTH,
    }
    events.append(event(f"POST {service_base_url}/api/ciphertexts"))
    receipt = http_post_json(f"{service_base_url}/api/ciphertexts", receipt_payload)
    write_text(
        submission_dir / "receipt.json",
        json.dumps(receipt, ensure_ascii=True, indent=2),
    )
    events.append(
        event(
            "Servicio remoto respondio ok="
            + str(bool_from_receipt(receipt)).lower()
            + " auxsid="
            + resolved_auxsid
        )
    )
    write_text(submission_dir / "monitor.log", "\n".join(events) + "\n")

    service_session_id = first_non_blank(receipt.get("session_id"), session.get("session_id"))
    service_session_name = first_non_blank(receipt.get("session_name"), session.get("session_name"))
    service_session_label = first_non_blank(
        receipt.get("session_label"),
        (receipt.get("session") or {}).get("label") if isinstance(receipt.get("session"), dict) else "",
        public_key_payload.get("session_label"),
        session.get("session_label"),
        service_session_name,
    )
    receipt_raw = json.dumps(receipt, ensure_ascii=True)

    return {
        "runId": run_id,
        "auxsid": resolved_auxsid,
        "serviceResolvedAuxsid": resolved_auxsid,
        "serviceSessionId": service_session_id,
        "serviceSessionName": service_session_name,
        "serviceSessionLabel": service_session_label,
        "serviceAccumulated": False,
        "serviceAccumulatedFromAuxsid": "",
        "receiptAccepted": bool_from_receipt(receipt),
        "submissionDir": str(submission_dir),
        "receiptRaw": receipt_raw,
        "events": events,
        "monitorText": "\n".join(events),
        "width": VERIFICATUM_WIDTH,
        "publicKeyFormat": "native",
        "ciphertextsFormat": "native",
        "voteSchemaVersion": SCHEMA_VERSION,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "IOSSimFallbackBridge/1.0"

    def do_GET(self) -> None:
        if self.path == "/api/health":
            self.respond_json(
                200,
                {
                    "status": "UP",
                    "bridge": "ios-sim-fallback",
                    "serviceBaseUrl": DEFAULT_SERVICE_BASE_URL,
                    "serverTime": now_iso(),
                },
            )
            return
        self.respond_json(404, {"status": 404, "error": "NotFound", "message": f"Ruta no soportada: {self.path}"})

    def do_POST(self) -> None:
        if self.path != "/api/ballot/submit":
            self.respond_json(404, {"status": 404, "error": "NotFound", "message": f"Ruta no soportada: {self.path}"})
            return
        content_length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(content_length)
        try:
            form = parse_form_payload(raw_body)
            payload = emit_vote(form)
            self.respond_json(200, payload)
        except Exception as exc:  # noqa: BLE001
            self.respond_json(
                500,
                {
                    "status": 500,
                    "error": exc.__class__.__name__,
                    "message": str(exc) or "Fallo interno en bridge local.",
                },
            )

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write(f"[ios-sim-fallback] {self.address_string()} - {fmt % args}\n")

    def respond_json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=True).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> int:
    ensure_runtime_layout()
    if not JAVA_BIN.is_file():
        raise SystemExit(f"No se encontro JAVA_BIN: {JAVA_BIN}")
    if not JAR_PATH.is_file():
        raise SystemExit(f"No se encontro JAR_PATH: {JAR_PATH}")
    if not LIB_DIR.is_dir():
        raise SystemExit(f"No se encontro LIB_DIR: {LIB_DIR}")
    server = ThreadingHTTPServer((DEFAULT_HOST, DEFAULT_PORT), Handler)
    print(
        f"[ios-sim-fallback] escuchando en http://{DEFAULT_HOST}:{DEFAULT_PORT} -> {DEFAULT_SERVICE_BASE_URL}",
        flush=True,
    )
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
