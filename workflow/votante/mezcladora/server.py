#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shlex
import shutil
import socket
import subprocess
import threading
import time
import unicodedata
from datetime import datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
RUNTIME_DIR = Path(os.environ.get("VERIFICATUM_GUI_RUNTIME_DIR", str(BASE_DIR / "runtime"))).resolve()
SESSIONS_DIR = RUNTIME_DIR / "sessions"
STATE_FILE = RUNTIME_DIR / "state.json"
GLOBAL_LOG_FILE = RUNTIME_DIR / "monitor_general.log"
SERVER_INFO_FILE = RUNTIME_DIR / "server_identity.json"
HANDSHAKES_FILE = RUNTIME_DIR / "handshakes.json"

LOCK = threading.Lock()
PROCESS_LOCK = threading.Lock()
PROCESS_REGISTRY: dict[tuple[str, str, int], subprocess.Popen[str]] = {}

TEST_MODE = os.environ.get("VERIFICATUM_GUI_TESTMODE", "").lower() in {"1", "true", "yes"}
CLEAN_START = os.environ.get("VERIFICATUM_GUI_CLEAN_START", "").lower() in {"1", "true", "yes"}

UI_HOST = "127.0.0.1"
BIND_HOST = "0.0.0.0"
BASE_UI_PORT = 7040
MAX_UI_PARTIES = 9
SEQUENTIAL_ACTIONS = ("precomp", "shuffle", "decrypt", "verify")
HANDSHAKE_TTL_SECONDS = int(os.environ.get("VERIFICATUM_GUI_HANDSHAKE_TTL", "90"))
SERVER_IDENTITY: dict = {}


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def load_state() -> dict:
    if not STATE_FILE.exists():
        return {"active_session": None, "sessions": {}}
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {"active_session": None, "sessions": {}}


def save_state(state: dict) -> None:
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, indent=2, ensure_ascii=False), encoding="utf-8")


def slugify(text: str) -> str:
    clean = "".join(ch.lower() if ch.isalnum() else "-" for ch in text.strip())
    compact = "-".join(part for part in clean.split("-") if part)
    return compact or "sesion"


def protocol_token(text: str) -> str:
    normalized = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii")
    token = re.sub(r"[^A-Za-z0-9_ ]+", " ", normalized)
    token = re.sub(r"\s+", " ", token).strip()
    if not token:
        token = "VerificatumSession"
    if not token[0].isalpha():
        token = f"V{token}"
    return token[:255]


def detect_host_ip() -> str:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


def default_session_host() -> str:
    if UI_HOST and UI_HOST not in {"127.0.0.1", "0.0.0.0", "localhost"}:
        return UI_HOST
    return detect_host_ip()


def tail_lines(path: Path, limit: int = 200) -> list[str]:
    if not path.exists():
        return []
    try:
        return path.read_text(encoding="utf-8", errors="replace").splitlines()[-limit:]
    except OSError:
        return []


def read_json(path: Path, fallback=None):
    if not path.exists():
        return fallback
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return fallback


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def write_bytes(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def append_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(content)


def append_event(session_dir: Path, message: str) -> None:
    line = f"[{now_iso()}] {message}\n"
    append_text(session_dir / "events.log", line)
    append_text(GLOBAL_LOG_FILE, line)


def append_global_event(message: str) -> None:
    append_text(GLOBAL_LOG_FILE, f"[{now_iso()}] {message}\n")


def unix_now() -> int:
    return int(time.time())


def build_api_url(path: str = "") -> str:
    base = f"http://{UI_HOST}:{BASE_UI_PORT}"
    return f"{base}{path}" if path else base


def load_or_create_server_identity() -> dict:
    payload = read_json(SERVER_INFO_FILE, {}) or {}
    hostname = socket.gethostname()
    instance_id = payload.get("instance_id") or f"{slugify(hostname)}-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
    created_at = payload.get("created_at") or now_iso()
    payload.update(
        {
            "instance_id": instance_id,
            "hostname": hostname,
            "bind_host": BIND_HOST,
            "public_host": UI_HOST,
            "base_ui_port": BASE_UI_PORT,
            "created_at": created_at,
            "updated_at": now_iso(),
            "version": GUIHandler.server_version if "GUIHandler" in globals() else "VerificatumGUI/2.0",
        }
    )
    write_json(SERVER_INFO_FILE, payload)
    return payload


def load_handshakes() -> dict:
    return read_json(HANDSHAKES_FILE, {"stations": {}}) or {"stations": {}}


def save_handshakes(payload: dict) -> None:
    write_json(HANDSHAKES_FILE, payload)


def prune_handshakes(payload: dict | None = None) -> dict:
    data = payload or load_handshakes()
    stations = data.get("stations", {})
    now_ts = unix_now()
    fresh = {
        station_id: item
        for station_id, item in stations.items()
        if int(item.get("expires_at_unix", 0)) > now_ts
    }
    data["stations"] = fresh
    save_handshakes(data)
    return data


def active_handshakes() -> list[dict]:
    data = prune_handshakes()
    stations = []
    for station_id, payload in sorted(data.get("stations", {}).items()):
        item = dict(payload)
        item["station_id"] = station_id
        stations.append(item)
    return stations


def shell_join(parts: list[str | int]) -> str:
    return " ".join(shlex.quote(str(part)) for part in parts)


def build_party_name(index: int) -> str:
    return f"party{index:02d}"


def operation_status_file(session_dir: Path, op_id: str, party_idx: int) -> Path:
    return session_dir / ".ops" / op_id / f"party{party_idx:02d}.json"


def operation_log_file(session_dir: Path, op_id: str, party_idx: int) -> Path:
    return session_dir / ".logs" / f"{op_id}_{build_party_name(party_idx)}.log"


def common_env(session_dir: Path) -> dict:
    env = os.environ.copy()
    tmp_dir = session_dir / ".tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    env["TMPDIR"] = str(tmp_dir)
    java_options = env.get("_JAVA_OPTIONS", "").strip()
    tmp_option = f"-Djava.io.tmpdir={tmp_dir}"
    env["_JAVA_OPTIONS"] = f"{java_options} {tmp_option}".strip()
    return env


def namespace_hostname(seed: str | None = None) -> str:
    _ = seed
    return "localhost"


def run_with_safe_hostname(command: str, hostname_seed: str | None = None) -> str:
    if shutil.which("unshare") is None:
        return command
    safe_hostname = namespace_hostname(hostname_seed)
    inner = f"hostname {shlex.quote(safe_hostname)} && exec bash -lc {shlex.quote(command)}"
    return f"unshare --user --map-root-user --uts bash -lc {shlex.quote(inner)}"


def run_checked(command: str, cwd: Path, session_dir: Path) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["bash", "-lc", command],
        cwd=str(cwd),
        env=common_env(session_dir),
        text=True,
        capture_output=True,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"Comando falló en {cwd}:\n{command}\n\nSTDOUT:\n{result.stdout}\n\nSTDERR:\n{result.stderr}"
        )
    return result


def java_memory_block() -> str:
    return "\n".join(
        [
            'if command -v free >/dev/null 2>&1; then',
            '  AVAILABLE_MEM=$(free -m | awk \'/^Mem:/{print $7}\')',
            '  if [ -n "$AVAILABLE_MEM" ] && [ "$AVAILABLE_MEM" -gt 0 ] 2>/dev/null; then',
            '    JVM_MEM=$(awk "BEGIN {printf \\"%.0f\\", $AVAILABLE_MEM * 0.85}")',
            '    export VERIFICATUM_VMN_MEM="${JVM_MEM}m"',
            '  fi',
            "fi",
            'export VERIFICATUM_VMN_STACK="8m"',
        ]
    )


def create_session_dirs(session_dir: Path) -> None:
    if session_dir.exists():
        shutil.rmtree(session_dir)
    for rel in [".ops", ".logs", ".tmp"]:
        (session_dir / rel).mkdir(parents=True, exist_ok=True)


def create_mock_party_layout(session: dict) -> None:
    session_dir = Path(session["session_dir"])
    create_session_dirs(session_dir)
    append_event(session_dir, f"[mock] Preparando protocolo local para {session['parties']} parties.")
    for idx in range(1, session["parties"] + 1):
        party_dir = session_dir / build_party_name(idx)
        party_dir.mkdir(parents=True, exist_ok=True)
        write_text(party_dir / "stub.xml", f"<stub>{idx}</stub>\n")
        write_text(party_dir / "privInfo.xml", f"<priv>{idx}</priv>\n")
        write_text(party_dir / f"protInfo{idx:02d}.xml", f"<party>{idx}</party>\n")
        write_text(party_dir / "protInfo.xml", "<global>mock</global>\n")
    append_event(session_dir, "[mock] Protocolo local/global generado.")


def create_real_party_layout(session: dict) -> None:
    session_dir = Path(session["session_dir"])
    create_session_dirs(session_dir)

    parties = session["parties"]
    threshold = session["threshold"]
    host = session["host"]
    pgroup = run_checked("vog -gen ECqPGroup -name 'P-256'", session_dir, session_dir).stdout.strip()

    append_event(session_dir, f"Preparando protocolo local para {parties} parties en host {host}.")

    for idx in range(1, parties + 1):
        party_dir = session_dir / build_party_name(idx)
        party_dir.mkdir(parents=True, exist_ok=True)

        prot_cmd = shell_join(
            [
                "vmni",
                "-prot",
                "-sid",
                session["sid"],
                "-name",
                session["protocol_name"],
                "-nopart",
                parties,
                "-thres",
                threshold,
                "-pgroup",
                pgroup,
            ]
        )
        prot_cmd = run_with_safe_hostname(prot_cmd, host)
        run_checked(prot_cmd, party_dir, session_dir)

        hint_port = session["base_hint_port"] + idx
        http_port = session["base_http_port"] + idx
        party_cmd = "\n".join(
            [
                "vog -rndinit RandomDevice /dev/urandom >/dev/null 2>&1 || true",
                "RAND=$(vog -gen RandomDevice /dev/urandom)",
                " ".join(
                    [
                        "vmni",
                        "-party",
                        "-e",
                        "-name",
                        shlex.quote(build_party_name(idx)),
                        "-hint",
                        shlex.quote(f"{host}:{hint_port}"),
                        "-http",
                        shlex.quote(f"http://{host}:{http_port}"),
                        '-rand "$RAND"',
                    ]
                ),
                f"cp localProtInfo.xml protInfo{idx:02d}.xml",
            ]
        )
        party_cmd = run_with_safe_hostname(party_cmd, host)
        run_checked(party_cmd, party_dir, session_dir)

    for idx in range(1, parties + 1):
        source_dir = session_dir / build_party_name(idx)
        source_info = source_dir / f"protInfo{idx:02d}.xml"
        for target_idx in range(1, parties + 1):
            if idx == target_idx:
                continue
            target_dir = session_dir / build_party_name(target_idx)
            shutil.copy2(source_info, target_dir / source_info.name)

    merge_inputs = " ".join(f"protInfo{idx:02d}.xml" for idx in range(1, parties + 1))
    for idx in range(1, parties + 1):
        party_dir = session_dir / build_party_name(idx)
        merge_cmd = run_with_safe_hostname(f"vmni -merge {merge_inputs} protInfo.xml", host)
        run_checked(merge_cmd, party_dir, session_dir)

    append_event(session_dir, "Protocolo local/global generado y compartido entre todas las parties.")


def create_party_layout(session: dict) -> None:
    if TEST_MODE:
        create_mock_party_layout(session)
    else:
        create_real_party_layout(session)


def party_artifacts(session: dict, party_idx: int) -> dict:
    party_dir = Path(session["session_dir"]) / build_party_name(party_idx)
    return {
        "stub": (party_dir / "stub.xml").exists(),
        "privinfo": (party_dir / "privInfo.xml").exists(),
        "protinfo_local": (party_dir / f"protInfo{party_idx:02d}.xml").exists(),
        "protinfo_global": (party_dir / "protInfo.xml").exists(),
        "public_key": (party_dir / "publicKey").exists(),
        "public_key_native": (party_dir / "publicKey_ext").exists(),
        "ciphertexts": (party_dir / "ciphertexts").exists(),
        "ciphertexts_shuffled": (party_dir / "ciphertextsout").exists(),
        "plaintexts_orig": (party_dir / "plaintexts_orig").exists(),
        "plaintexts": (party_dir / "plaintexts").exists(),
    }


def party_file(session: dict, party_idx: int, filename: str) -> Path:
    return Path(session["session_dir"]) / build_party_name(party_idx) / filename


def file_ready(path: Path) -> bool:
    try:
        return path.exists() and path.is_file() and path.stat().st_size > 0
    except OSError:
        return False


def session_event_error(session: dict, message: str) -> None:
    append_event(Path(session["session_dir"]), message)
    raise RuntimeError(message)


def sanitize_auxsid(text: str) -> str:
    normalized = unicodedata.normalize("NFKD", (text or "").strip()).encode("ascii", "ignore").decode("ascii")
    token = re.sub(r"[^A-Za-z0-9]+", "", normalized)
    if not token:
        return "default"
    if not token[0].isalpha():
        token = f"a{token}"
    return token[:128]


def sanitize_station_id(text: str) -> str:
    normalized = unicodedata.normalize("NFKD", (text or "").strip()).encode("ascii", "ignore").decode("ascii")
    token = re.sub(r"[^A-Za-z0-9_]+", "_", normalized).strip("_")
    if not token:
        return "station"
    if not token[0].isalpha():
        token = f"s_{token}"
    return token[:128]


def slot_filename(base: str, auxsid: str) -> str:
    return base if auxsid == "default" else f"{base}_{auxsid}"


def auxsid_from_slot(base: str, filename: str) -> str | None:
    if filename == base:
        return "default"
    prefix = f"{base}_"
    if filename.startswith(prefix):
        suffix = filename[len(prefix):]
        if base == "ciphertexts" and (suffix == "ext" or suffix.startswith("ext_")):
            return None
        if base == "plaintexts" and (suffix == "orig" or suffix.startswith("orig_")):
            return None
        return suffix
    return None


def slot_paths(session: dict, auxsid: str) -> dict[str, str]:
    return {
        "auxsid": auxsid,
        "ciphertexts": slot_filename("ciphertexts", auxsid),
        "ciphertexts_ext": slot_filename("ciphertexts_ext", auxsid),
        "ciphertextsout": slot_filename("ciphertextsout", auxsid),
        "plaintexts_orig": slot_filename("plaintexts_orig", auxsid),
        "plaintexts": slot_filename("plaintexts", auxsid),
    }


def proof_dir(session: dict, auxsid: str, party_idx: int = 1) -> Path:
    return Path(session["session_dir"]) / build_party_name(party_idx) / "dir" / "nizkp" / auxsid


def scan_auxsid_slots(session: dict, base: str, party_idx: int = 1) -> set[str]:
    party_dir = Path(session["session_dir"]) / build_party_name(party_idx)
    if not party_dir.exists():
        return set()
    matches = set()
    for entry in party_dir.iterdir():
        auxsid = auxsid_from_slot(base, entry.name)
        if auxsid:
            matches.add(auxsid)
    return matches


def scan_nizkp_auxsids(session: dict, party_idx: int = 1) -> set[str]:
    root = Path(session["session_dir"]) / build_party_name(party_idx) / "dir" / "nizkp"
    if not root.exists():
        return set()
    names = set()
    for entry in root.iterdir():
        if entry.name.startswith("."):
            continue
        names.add(entry.name)
    return names


def used_mix_auxsids(session: dict) -> set[str]:
    used = set(scan_nizkp_auxsids(session))
    for op in session.get("operations", []):
        auxsid = (op.get("payload") or {}).get("auxsid")
        if auxsid:
            used.add(auxsid)
    return used


def reserved_auxsids(session: dict) -> set[str]:
    reserved = set(used_mix_auxsids(session))
    for base in ("ciphertexts", "ciphertextsout", "plaintexts_orig", "plaintexts"):
        reserved.update(scan_auxsid_slots(session, base))
    return reserved


def unique_auxsid(session: dict, requested: str) -> tuple[str, bool]:
    base = sanitize_auxsid(requested)
    candidate = base
    suffix = 2
    existing = reserved_auxsids(session)
    while candidate in existing:
        candidate = f"{base}{suffix}"
        suffix += 1
    return candidate, candidate != base


def resolved_auxsid_for_write(session: dict, requested: str) -> tuple[str, bool]:
    return unique_auxsid(session, requested or "default")


def current_upload_auxsid(session: dict, requested: str = "default") -> str:
    return resolved_auxsid_for_ciphertext_upload(session, requested)[0]


def auxsid_generation(base: str, candidate: str) -> int | None:
    if candidate == base:
        return 1
    if not candidate.startswith(base):
        return None
    suffix = candidate[len(base):]
    if suffix.isdigit() and int(suffix) >= 2:
        return int(suffix)
    return None


def auxsid_lineage(base: str, candidates: set[str]) -> list[str]:
    indexed: list[tuple[int, str]] = []
    for candidate in candidates:
        generation = auxsid_generation(base, candidate)
        if generation is not None:
            indexed.append((generation, candidate))
    indexed.sort(key=lambda item: (item[0], item[1]))
    return [candidate for _, candidate in indexed]


def latest_lineage_ciphertext_auxsid(session: dict, requested: str) -> str | None:
    lineage = auxsid_lineage(sanitize_auxsid(requested), scan_auxsid_slots(session, "ciphertexts"))
    return lineage[-1] if lineage else None


def latest_open_lineage_auxsid(session: dict, requested: str) -> str | None:
    lineage = auxsid_lineage(sanitize_auxsid(requested), scan_auxsid_slots(session, "ciphertexts"))
    open_candidates = [auxsid for auxsid in lineage if auxsid_accepts_more_ciphertexts(session, auxsid)]
    return open_candidates[-1] if open_candidates else None


def next_pending_auxsid_for_request(session: dict, requested: str) -> str | None:
    shuffled = {op.get("payload", {}).get("auxsid") for op in collect_operations(session) if op["name"] == "shuffle" and op["status"] == "ok"}
    shuffled.discard(None)
    pending = scan_auxsid_slots(session, "ciphertexts") - shuffled
    lineage = auxsid_lineage(sanitize_auxsid(requested), pending)
    return lineage[0] if lineage else None


def auxsid_accepts_more_ciphertexts(session: dict, auxsid: str) -> bool:
    if auxsid in used_mix_auxsids(session):
        return False
    if auxsid in scan_auxsid_slots(session, "ciphertextsout"):
        return False
    if auxsid in scan_auxsid_slots(session, "plaintexts_orig"):
        return False
    if auxsid in scan_auxsid_slots(session, "plaintexts"):
        return False
    return auxsid in scan_auxsid_slots(session, "ciphertexts")


def resolved_auxsid_for_ciphertext_upload(session: dict, requested: str) -> tuple[str, bool, bool, str | None]:
    requested = sanitize_auxsid(requested or "default")
    open_auxsid = latest_open_lineage_auxsid(session, requested)
    if open_auxsid:
        return open_auxsid, open_auxsid != requested, True, open_auxsid
    latest_source = latest_lineage_ciphertext_auxsid(session, requested)
    resolved, changed = resolved_auxsid_for_write(session, requested)
    return resolved, changed, latest_source is not None, latest_source


def next_pending_auxsid(session: dict) -> str | None:
    ciphertexts = scan_auxsid_slots(session, "ciphertexts")
    shuffled = {op.get("payload", {}).get("auxsid") for op in collect_operations(session) if op["name"] == "shuffle" and op["status"] == "ok"}
    shuffled.discard(None)
    candidates = sorted(ciphertexts - shuffled, key=lambda value: (value != "default", value))
    return candidates[0] if candidates else None


def next_decrypt_auxsid(session: dict) -> str | None:
    shuffled = {op.get("payload", {}).get("auxsid") for op in collect_operations(session) if op["name"] == "shuffle" and op["status"] == "ok"}
    decrypted = {op.get("payload", {}).get("auxsid") for op in collect_operations(session) if op["name"] == "decrypt" and op["status"] == "ok"}
    shuffled.discard(None)
    decrypted.discard(None)
    candidates = sorted(shuffled - decrypted, key=lambda value: (value != "default", value))
    return candidates[0] if candidates else None


def next_verify_auxsid(session: dict) -> str | None:
    decrypted = {op.get("payload", {}).get("auxsid") for op in collect_operations(session) if op["name"] == "decrypt" and op["status"] == "ok"}
    verified = {op.get("payload", {}).get("auxsid") for op in collect_operations(session) if op["name"] == "verify" and op["status"] == "ok"}
    decrypted.discard(None)
    verified.discard(None)
    candidates = sorted(decrypted - verified, key=lambda value: (value != "default", value))
    return candidates[0] if candidates else None


def copy_text_to_all_parties(session: dict, filename: str, content: str) -> list[str]:
    session_dir = Path(session["session_dir"])
    written = []
    for idx in range(1, session["parties"] + 1):
        target = session_dir / build_party_name(idx) / filename
        write_text(target, content)
        written.append(str(target))
    return written


def copy_bytes_to_all_parties(session: dict, filename: str, content: bytes) -> list[str]:
    session_dir = Path(session["session_dir"])
    written = []
    for idx in range(1, session["parties"] + 1):
        target = session_dir / build_party_name(idx) / filename
        write_bytes(target, content)
        written.append(str(target))
    return written


def read_text_file(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def read_bytes_file(path: Path) -> bytes:
    return path.read_bytes()


def merge_text_payload(existing: str, incoming: str) -> str:
    if not existing:
        return incoming
    if not incoming:
        return existing
    if existing.endswith("\n") or incoming.startswith("\n"):
        return existing + incoming
    return existing + "\n" + incoming


def merge_binary_payload(existing: bytes, incoming: bytes) -> bytes:
    if not existing:
        return incoming
    if not incoming:
        return existing
    if existing.endswith(b"\n") or incoming.startswith(b"\n"):
        return existing + incoming
    return existing + b"\n" + incoming


def clone_ciphertext_slot(session: dict, source_auxsid: str, target_auxsid: str) -> dict[str, list[str]]:
    source = slot_paths(session, source_auxsid)
    target = slot_paths(session, target_auxsid)
    source_raw = party_file(session, 1, source["ciphertexts"])
    if not file_ready(source_raw):
        raise RuntimeError(f"No se puede reutilizar AuxSID {source_auxsid}: falta {source_raw}.")

    replicated: dict[str, list[str]] = {}
    replicated[target["ciphertexts"]] = copy_bytes_to_all_parties(session, target["ciphertexts"], read_bytes_file(source_raw))

    source_ext = party_file(session, 1, source["ciphertexts_ext"])
    if file_ready(source_ext):
        replicated[target["ciphertexts_ext"]] = copy_bytes_to_all_parties(session, target["ciphertexts_ext"], read_bytes_file(source_ext))
    else:
        replicated[target["ciphertexts_ext"]] = []

    return replicated


def remove_if_exists(path: Path) -> None:
    try:
        if path.exists():
            path.unlink()
    except OSError:
        pass


def collect_operations(session: dict) -> list[dict]:
    session_dir = Path(session["session_dir"])
    operations = []
    for op in session.get("operations", []):
        op_copy = dict(op)
        party_states = []
        for idx in range(1, session["parties"] + 1):
            payload = read_json(operation_status_file(session_dir, op["id"], idx), {}) or {}
            payload.setdefault("party", build_party_name(idx))
            payload.setdefault("status", "pending")
            party_states.append(payload)

        statuses = [item["status"] for item in party_states]
        if any(status == "error" for status in statuses):
            aggregate = "error"
        elif any(status == "running" for status in statuses):
            aggregate = "running"
        elif all(status == "pending" for status in statuses):
            aggregate = "pending"
        elif any(status == "pending" for status in statuses):
            aggregate = "running"
        elif any(status == "ok" for status in statuses):
            aggregate = "ok"
        else:
            aggregate = "pending"

        op_copy["status"] = aggregate
        op_copy["party_states"] = party_states
        operations.append(op_copy)
    return operations


def party_idx_from_name(name: str) -> int:
    return int(name.replace("party", ""))


def next_pending_party_idx(op: dict) -> int | None:
    for item in op.get("party_states", []):
        if item.get("status") == "pending":
            return party_idx_from_name(item["party"])
    return None


def current_active_operation(session: dict) -> dict | None:
    for op in reversed(collect_operations(session)):
        if op["status"] in {"pending", "running"}:
            return op
    return None


def completed_operation_names(session: dict) -> set[str]:
    return {op["name"] for op in collect_operations(session) if op["status"] == "ok"}


def all_parties_have_file(session: dict, filename: str) -> bool:
    return all(file_ready(party_file(session, idx, filename)) for idx in range(1, session["parties"] + 1))


def prepare_sequential_operation(session: dict, name: str, payload: dict | None = None) -> dict:
    op = register_operation(session, name, "distributed-sequential", payload)
    session_dir = Path(session["session_dir"])
    for idx in range(1, session["parties"] + 1):
        write_status(session_dir, op["id"], idx, "pending", 0)
    append_event(session_dir, f"Fase {name} preparada. Turno inicial: party01.")
    return op


def maybe_finalize_operation(session: dict, op: dict) -> None:
    states = []
    session_dir = Path(session["session_dir"])
    for idx in range(1, session["parties"] + 1):
        states.append(read_json(operation_status_file(session_dir, op["id"], idx), {}) or {})

    statuses = [item.get("status", "pending") for item in states]
    if any(status in {"pending", "running"} for status in statuses):
        return
    if any(status == "error" for status in statuses):
        append_event(session_dir, f"Fase {op['name']} finalizó con errores.")
    else:
        append_event(session_dir, f"Fase {op['name']} completada en todas las parties.")


def start_mock_party_for_operation(session: dict, op: dict, party_idx: int) -> None:
    session_dir = Path(session["session_dir"])
    write_status(session_dir, op["id"], party_idx, "running", 0)

    def runner() -> None:
        time.sleep(0.2)
        complete_mock_artifacts(session, op, [party_idx])
        log_path = operation_log_file(session_dir, op["id"], party_idx)
        append_text(log_path, "\n".join(mock_log_lines(op, party_idx)) + "\n")
        write_status(session_dir, op["id"], party_idx, "ok", 0)
        append_event(session_dir, f"{build_party_name(party_idx)} completó {op['name']}.")
        maybe_finalize_operation(session, op)

    threading.Thread(target=runner, daemon=True).start()


def start_sequential_party(session: dict, op: dict, party_idx: int, command: str) -> dict:
    session_dir = Path(session["session_dir"])
    append_event(session_dir, f"{build_party_name(party_idx)} inició {op['name']}.")
    if TEST_MODE:
        start_mock_party_for_operation(session, op, party_idx)
        return op
    spawn_party_process(session, op, party_idx, command)
    return op


def control_state(enabled: bool, reason: str) -> dict:
    return {"enabled": enabled, "reason": reason}


def party_controls(session: dict, party_idx: int) -> dict:
    controls = {name: control_state(False, "No disponible.") for name in SEQUENTIAL_ACTIONS}
    if not keygen_ready(session):
        return {
            "turn_party": None,
            "active_operation": "keygen",
            "controls": {name: control_state(False, "Esperando que termine keygen.") for name in SEQUENTIAL_ACTIONS},
        }

    active = current_active_operation(session)
    if active:
        if active["name"] in SEQUENTIAL_ACTIONS and active["mode"] == "distributed-sequential":
            next_idx = next_pending_party_idx(active)
            turn_party = build_party_name(next_idx) if next_idx else None
            for name in SEQUENTIAL_ACTIONS:
                if name != active["name"]:
                    controls[name] = control_state(False, f"Operación en curso: {active['name']}.")
                elif next_idx is None:
                    controls[name] = control_state(False, "Esperando a que terminen las parties ya iniciadas.")
                elif party_idx == next_idx:
                    controls[name] = control_state(True, f"Turno de {build_party_name(party_idx)} para iniciar {name}.")
                elif party_idx < next_idx:
                    controls[name] = control_state(False, f"{build_party_name(party_idx)} ya inició {name}.")
                else:
                    controls[name] = control_state(False, f"Esperando que {build_party_name(next_idx)} inicie {name}.")
            return {"turn_party": turn_party, "active_operation": active["name"], "controls": controls}

        return {
            "turn_party": None,
            "active_operation": active["name"],
            "controls": {name: control_state(False, f"Esperando que termine {active['name']}.") for name in SEQUENTIAL_ACTIONS},
        }

    turn_party = build_party_name(1)
    precomp_completed = any(op["name"] == "precomp" and op["status"] == "ok" for op in collect_operations(session))
    available_ciphertexts = sorted(scan_auxsid_slots(session, "ciphertexts"), key=lambda value: (value != "default", value))
    next_shuffle = next_pending_auxsid(session)
    next_decrypt = next_decrypt_auxsid(session)
    next_verify = next_verify_auxsid(session)

    controls["precomp"] = control_state(
        (not precomp_completed) and party_idx == 1,
        "Turno de party01 para iniciar precomp." if not precomp_completed else "Precomputación ya completada u omitida."
    )
    controls["shuffle"] = control_state(
        (next_shuffle is not None or bool(available_ciphertexts)) and party_idx == 1,
        (
            f"Turno de party01 para iniciar shuffle con AuxSID {next_shuffle}."
            if next_shuffle
            else (
                f"Turno de party01 para remezclar un AuxSID existente. Disponibles: {', '.join(available_ciphertexts)}."
                if available_ciphertexts
                else "No hay ciphertexts pendientes para mezclar."
            )
        )
    )
    controls["decrypt"] = control_state(
        next_decrypt is not None and party_idx == 1,
        f"Turno de party01 para iniciar decrypt con AuxSID {next_decrypt}." if next_decrypt else "No hay mezclas pendientes de descifrar."
    )
    controls["verify"] = control_state(
        next_verify is not None and party_idx == 1,
        f"Turno de party01 para iniciar verify con AuxSID {next_verify}." if next_verify else "No hay descifrados pendientes de verificación."
    )

    if party_idx != 1:
        for name, meta in controls.items():
            if meta["enabled"]:
                controls[name] = control_state(False, "Esperando que party01 inicie esta fase.")
            elif name == "precomp" and not precomp_completed:
                controls[name] = control_state(False, "Esperando que party01 inicie precomp.")
            elif name == "shuffle" and next_shuffle:
                controls[name] = control_state(False, f"Esperando que party01 inicie shuffle para {next_shuffle}.")
            elif name == "shuffle" and available_ciphertexts:
                controls[name] = control_state(False, "Esperando que party01 inicie una nueva mezcla o remezcla.")
            elif name == "decrypt" and next_decrypt:
                controls[name] = control_state(False, f"Esperando que party01 inicie decrypt para {next_decrypt}.")
            elif name == "verify" and next_verify:
                controls[name] = control_state(False, f"Esperando que party01 inicie verify para {next_verify}.")

    return {"turn_party": turn_party, "active_operation": None, "controls": controls}


def keygen_ready(session: dict) -> bool:
    enriched = enrich_session(session)
    if not enriched["party_details"]:
        return False
    return all(item["artifacts"]["public_key_native"] for item in enriched["party_details"])


def get_active_session() -> dict | None:
    state = load_state()
    active = state.get("active_session")
    if not active:
        return None
    return state.get("sessions", {}).get(active)


def session_accepting_votes(session: dict | None) -> bool:
    if not session:
        return False
    if not keygen_ready(session):
        return False
    return current_active_operation(session) is None


def mixer_status_payload(session: dict | None) -> dict:
    active = current_active_operation(session) if session else None
    handshakes = active_handshakes()
    return {
        "instance_id": SERVER_IDENTITY.get("instance_id"),
        "hostname": SERVER_IDENTITY.get("hostname", socket.gethostname()),
        "public_host": UI_HOST,
        "bind_host": BIND_HOST,
        "base_ui_port": BASE_UI_PORT,
        "ui_url": build_api_url("/"),
        "api_url": build_api_url(),
        "discovery_url": build_api_url("/api/discovery"),
        "handshake_url": build_api_url("/api/handshake"),
        "emission_context_url": build_api_url("/api/emission-context"),
        "public_key_url": build_api_url("/api/public-key"),
        "ciphertexts_url": build_api_url("/api/ciphertexts"),
        "has_active_session": bool(session),
        "keygen_ready": keygen_ready(session) if session else False,
        "accepting_votes": session_accepting_votes(session),
        "current_operation": active["name"] if active else None,
        "handshake_ttl_seconds": HANDSHAKE_TTL_SECONDS,
        "registered_stations": handshakes,
        "registered_station_count": len(handshakes),
    }


def discovery_payload(session: dict | None) -> dict:
    payload = {
        "ok": True,
        "server": mixer_status_payload(session),
        "session": None,
    }
    if session:
        payload["session"] = {
            "session_id": session["id"],
            "session_name": session["label"],
            "session_label": session["label"],
            "election_name": session["election_name"],
            "sid": session["sid"],
            "parties": session["parties"],
            "threshold": session["threshold"],
            "workspace": session["session_dir"],
        }
    return payload


def station_id_from_payload(payload: dict, client_ip: str) -> str:
    raw = (
        payload.get("station_id")
        or payload.get("client_id")
        or payload.get("terminal_id")
        or payload.get("hostname")
        or client_ip
        or "station"
    )
    return sanitize_station_id(raw)


def register_handshake(payload: dict, client_ip: str) -> dict:
    session = get_active_session()
    station_id = station_id_from_payload(payload, client_ip)
    requested_session_id = (payload.get("session_id") or payload.get("required_session_id") or "").strip()
    requested_session_name = (payload.get("session_name") or payload.get("required_session_name") or "").strip()
    requested_auxsid = sanitize_auxsid(payload.get("auxsid") or payload.get("preferred_auxsid") or "default")

    accepted = True
    reason = "ok"
    if not session:
        accepted = False
        reason = "No hay una sesión activa."
    elif requested_session_id and requested_session_id != session["id"]:
        accepted = False
        reason = f"La sesión activa es {session['id']}, no {requested_session_id}."
    elif requested_session_name and requested_session_name != session["label"]:
        accepted = False
        reason = f"La sesión activa es '{session['label']}', no '{requested_session_name}'."
    elif not keygen_ready(session):
        accepted = False
        reason = "La llave pública todavía no está disponible."
    elif current_active_operation(session):
        accepted = False
        reason = f"La mezcladora está ocupada con {current_active_operation(session)['name']}."

    response = {
        "ok": True,
        "accepted": accepted,
        "reason": reason,
        "station_id": station_id,
        "requested_auxsid": requested_auxsid,
        "server": mixer_status_payload(session),
        "session": None,
        "lease_id": None,
        "expires_at": None,
    }

    if session:
        response["session"] = {
            "session_id": session["id"],
            "session_name": session["label"],
            "session_label": session["label"],
            "election_name": session["election_name"],
            "sid": session["sid"],
        }

    if not accepted:
        response["active_stations"] = active_handshakes()
        return response

    handshakes = prune_handshakes()
    expires_at_unix = unix_now() + HANDSHAKE_TTL_SECONDS
    lease_id = f"{SERVER_IDENTITY.get('instance_id', 'mixer')}:{station_id}"
    handshakes.setdefault("stations", {})[station_id] = {
        "lease_id": lease_id,
        "client_ip": client_ip,
        "requested_auxsid": requested_auxsid,
        "session_id": session["id"],
        "session_name": session["label"],
        "created_at": handshakes.get("stations", {}).get(station_id, {}).get("created_at", now_iso()),
        "last_seen_at": now_iso(),
        "expires_at": datetime.fromtimestamp(expires_at_unix).astimezone().isoformat(timespec="seconds"),
        "expires_at_unix": expires_at_unix,
    }
    save_handshakes(handshakes)
    append_global_event(f"Handshake aceptado para {station_id} en la sesión {session['id']}.")
    response["lease_id"] = lease_id
    response["expires_at"] = handshakes["stations"][station_id]["expires_at"]
    response["active_stations"] = active_handshakes()
    return response


def validate_handshake(payload: dict, session: dict) -> tuple[bool, str | None, dict | None]:
    station_id = (payload.get("station_id") or "").strip()
    lease_id = (payload.get("lease_id") or payload.get("handshake_id") or "").strip()
    if not station_id and not lease_id:
        return False, None, None

    handshakes = prune_handshakes()
    if station_id:
        station = handshakes.get("stations", {}).get(sanitize_station_id(station_id))
    else:
        station = next((item for item in handshakes.get("stations", {}).values() if item.get("lease_id") == lease_id), None)
        station_id = next(
            (
                key
                for key, item in handshakes.get("stations", {}).items()
                if item.get("lease_id") == lease_id
            ),
            "",
        )

    if not station:
        raise RuntimeError("El handshake informado ya no está activo o no existe en esta mezcladora.")
    if lease_id and station.get("lease_id") != lease_id:
        raise RuntimeError(f"El lease_id no coincide con el station_id {station_id}.")
    if station.get("session_id") != session["id"]:
        raise RuntimeError(
            f"El handshake de {station_id} corresponde a la sesión {station.get('session_id')}, pero la sesión activa es {session['id']}."
        )

    station["last_seen_at"] = now_iso()
    station["expires_at_unix"] = unix_now() + HANDSHAKE_TTL_SECONDS
    station["expires_at"] = datetime.fromtimestamp(station["expires_at_unix"]).astimezone().isoformat(timespec="seconds")
    handshakes["stations"][station_id] = station
    save_handshakes(handshakes)
    return True, station_id, station


def persist_session(session: dict) -> None:
    with LOCK:
        state = load_state()
        state["sessions"][session["id"]] = session
        state["active_session"] = session["id"]
        save_state(state)


def get_operation_for_party(session: dict, party_idx: int) -> dict | None:
    for op in reversed(collect_operations(session)):
        for item in op["party_states"]:
            if item["party"] == build_party_name(party_idx) and item["status"] != "n/a":
                return op
    return None


def latest_log_for_party(session: dict, party_idx: int) -> tuple[Path | None, dict | None]:
    session_dir = Path(session["session_dir"])
    for op in reversed(session.get("operations", [])):
        path = operation_log_file(session_dir, op["id"], party_idx)
        if path.exists():
            return path, op
    return None, None


def enrich_session(session: dict) -> dict:
    session_copy = dict(session)
    session_dir = Path(session["session_dir"])
    operations = collect_operations(session)
    current_op = operations[-1] if operations else None
    details = []
    for idx in range(1, session["parties"] + 1):
        status = "pending"
        if current_op:
            for item in current_op["party_states"]:
                if item["party"] == build_party_name(idx):
                    status = item["status"]
                    break
        details.append(
            {
                "id": idx,
                "name": build_party_name(idx),
                "ui_port": BASE_UI_PORT + idx,
                "hint_port": session["base_hint_port"] + idx,
                "http_port": session["base_http_port"] + idx,
                "path": str(session_dir / build_party_name(idx)),
                "artifacts": party_artifacts(session, idx),
                "current_status": status,
            }
        )
    session_copy["party_details"] = details
    session_copy["workspace"] = str(session_dir)
    session_copy["key_locations"] = [
        {
            "party": build_party_name(idx),
            "public_key": str(session_dir / build_party_name(idx) / "publicKey"),
            "public_key_native": str(session_dir / build_party_name(idx) / "publicKey_ext"),
        }
        for idx in range(1, session["parties"] + 1)
    ]
    session_copy["events"] = tail_lines(session_dir / "events.log")
    session_copy["operations"] = operations
    session_copy["current_operation"] = current_op
    return session_copy


def has_running_operation(session: dict) -> bool:
    return any(op["status"] == "running" for op in collect_operations(session))


def register_operation(session: dict, name: str, mode: str, payload: dict | None = None) -> dict:
    op_id = f"{name}-{datetime.now().strftime('%Y%m%d%H%M%S')}"
    op = {
        "id": op_id,
        "name": name,
        "mode": mode,
        "payload": payload or {},
        "started_at": now_iso(),
    }
    session.setdefault("operations", []).append(op)
    persist_session(session)
    return op


def write_status(session_dir: Path, op_id: str, party_idx: int, status: str, returncode: int = 0) -> None:
    write_json(
        operation_status_file(session_dir, op_id, party_idx),
        {
            "party": build_party_name(party_idx),
            "status": status,
            "returncode": returncode,
            "timestamp": now_iso(),
        },
    )


def build_shell_command(command: str) -> str:
    return "\n".join([java_memory_block(), command])


def spawn_party_process(session: dict, op: dict, party_idx: int, command: str) -> None:
    session_dir = Path(session["session_dir"])
    party_dir = session_dir / build_party_name(party_idx)
    log_path = operation_log_file(session_dir, op["id"], party_idx)
    write_status(session_dir, op["id"], party_idx, "running", 0)

    append_text(log_path, f"$ {command}\n")

    def runner() -> None:
        env = common_env(session_dir)
        process = subprocess.Popen(
            ["bash", "-lc", build_shell_command(command)],
            cwd=str(party_dir),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        key = (session["id"], op["id"], party_idx)
        with PROCESS_LOCK:
            PROCESS_REGISTRY[key] = process

        try:
            if process.stdout:
                with log_path.open("a", encoding="utf-8") as handle:
                    for line in process.stdout:
                        handle.write(line)
                        handle.flush()
            rc = process.wait()
        finally:
            with PROCESS_LOCK:
                PROCESS_REGISTRY.pop(key, None)

        if rc == 0:
            write_status(session_dir, op["id"], party_idx, "ok", 0)
            append_event(session_dir, f"{op['name']} completó en {build_party_name(party_idx)}.")
        else:
            write_status(session_dir, op["id"], party_idx, "error", rc)
            append_event(session_dir, f"{op['name']} falló en {build_party_name(party_idx)} con código {rc}.")
        maybe_finalize_operation(session, op)

    threading.Thread(target=runner, daemon=True).start()


def mock_log_lines(op: dict, party_idx: int) -> list[str]:
    prefix = build_party_name(party_idx)
    op_name = op["name"]
    payload = op.get("payload") or {}
    auxsid = payload.get("auxsid", "default")
    slots = slot_paths({}, auxsid)
    mapping = {
        "keygen": [f"{prefix}> vmn -keygen -e publicKey", f"{prefix}> vmnc -pkey -outi native protInfo.xml publicKey publicKey_ext"],
        "precomp": [f"{prefix}> vmn -precomp -e -maxciph 100", f"{prefix}> precomputation ready"],
        "ciphertexts": [f"{prefix}> vmnd -ciphs -e -i native -width 1 publicKey_ext 100 {slots['ciphertexts_ext']}", f"{prefix}> vmnc -ciphs -sloppy -ini native -width 1 protInfo.xml {slots['ciphertexts_ext']} {slots['ciphertexts']}"],
        "shuffle": [f"{prefix}> vmn -shuffle privInfo.xml protInfo.xml {slots['ciphertexts']} {slots['ciphertextsout']}", f"{prefix}> shuffled ciphertexts written"],
        "decrypt": [f"{prefix}> vmn -decrypt privInfo.xml protInfo.xml {slots['ciphertextsout']} {slots['plaintexts_orig']}", f"{prefix}> vmnc -plain -outi native protInfo.xml {slots['plaintexts_orig']} {slots['plaintexts']}"],
        "verify": [f"{prefix}> vmnv -v -e -mix protInfo.xml {auxsid}", f"{prefix}> verification ok"],
    }
    if op_name == "shuffle" and auxsid != "default":
        mapping["shuffle"][0] = f"{prefix}> vmn -shuffle privInfo.xml protInfo.xml {slots['ciphertexts']} {slots['ciphertextsout']} -auxsid {auxsid}"
    if op_name == "decrypt" and auxsid != "default":
        mapping["decrypt"][0] = f"{prefix}> vmn -decrypt privInfo.xml protInfo.xml {slots['ciphertextsout']} {slots['plaintexts_orig']} -auxsid {auxsid}"
    if op_name == "verify" and auxsid != "default":
        mapping["verify"][0] = f"{prefix}> vmnv -v -e -mix protInfo.xml {auxsid} -auxsid {auxsid}"
    return mapping.get(op_name, [f"{prefix}> {op_name}"])


def complete_mock_artifacts(session: dict, op: dict, participants: list[int]) -> None:
    session_dir = Path(session["session_dir"])
    op_name = op["name"]
    payload = op.get("payload") or {}
    auxsid = payload.get("auxsid", "default")
    slots = slot_paths(session, auxsid)
    if op_name == "keygen":
        for idx in participants:
            party_dir = session_dir / build_party_name(idx)
            write_text(party_dir / "publicKey", "mock-public-key\n")
            write_text(party_dir / "publicKey_ext", "mock-public-key-native\n")
    elif op_name == "precomp":
        for idx in participants:
            write_text(session_dir / build_party_name(idx) / "precomp.ok", "ok\n")
    elif op_name == "ciphertexts":
        for idx in range(1, session["parties"] + 1):
            party_dir = session_dir / build_party_name(idx)
            write_text(party_dir / slots["ciphertexts_ext"], "mock-ciphertexts-ext\n")
            write_text(party_dir / slots["ciphertexts"], "mock-ciphertexts\n")
    elif op_name == "shuffle":
        for idx in participants:
            party_dir = session_dir / build_party_name(idx)
            write_text(party_dir / slots["ciphertextsout"], "mock-ciphertexts-out\n")
            write_text(party_dir / "dir" / "nizkp" / auxsid / "proof.bt", "mock-proof\n")
    elif op_name == "decrypt":
        for idx in participants:
            party_dir = session_dir / build_party_name(idx)
            write_text(party_dir / slots["plaintexts_orig"], "mock-plaintexts-orig\n")
            write_text(party_dir / slots["plaintexts"], "mock-plaintexts\n")
    elif op_name == "verify":
        party_dir = session_dir / build_party_name(participants[0])
        write_text(party_dir / "export" / "protInfo.xml", "<global>mock</global>\n")
        write_text(party_dir / f"verify_{auxsid}.ok", "ok\n")


def spawn_mock_operation(session: dict, op: dict, participants: list[int]) -> None:
    session_dir = Path(session["session_dir"])
    for idx in range(1, session["parties"] + 1):
        write_status(session_dir, op["id"], idx, "running" if idx in participants else "n/a", 0)

    def runner() -> None:
        time.sleep(0.2)
        complete_mock_artifacts(session, op, participants)
        for idx in participants:
            log_path = operation_log_file(session_dir, op["id"], idx)
            append_text(log_path, "\n".join(mock_log_lines(op, idx)) + "\n")
            write_status(session_dir, op["id"], idx, "ok", 0)
        append_event(session_dir, f"[mock] Fase {op['name']} completada.")
        maybe_finalize_operation(session, op)

    threading.Thread(target=runner, daemon=True).start()


def launch_distributed(session: dict, name: str, command_builder, payload: dict | None = None) -> dict:
    op = register_operation(session, name, "distributed", payload)
    session_dir = Path(session["session_dir"])
    append_event(session_dir, f"Iniciando fase distribuida: {name}.")
    participants = list(range(1, session["parties"] + 1))

    if TEST_MODE:
        spawn_mock_operation(session, op, participants)
        return op

    for idx in participants:
        spawn_party_process(session, op, idx, command_builder(idx))
    return op


def launch_single_party(session: dict, name: str, party_idx: int, command: str, payload: dict | None = None) -> dict:
    op = register_operation(session, name, "single", payload)
    session_dir = Path(session["session_dir"])
    append_event(session_dir, f"Iniciando fase local {name} desde {build_party_name(party_idx)}.")

    if TEST_MODE:
        spawn_mock_operation(session, op, [party_idx])
        return op

    for idx in range(1, session["parties"] + 1):
        write_status(session_dir, op["id"], idx, "n/a" if idx != party_idx else "running", 0)
    spawn_party_process(session, op, party_idx, command)
    return op


def setup_session(payload: dict) -> dict:
    active = get_active_session()
    if active and has_running_operation(active):
        raise RuntimeError("No se puede crear una nueva sesión mientras la sesión activa todavía tiene una operación en ejecución.")

    parties = int(payload.get("parties", 3))
    threshold = int(payload.get("threshold", 2 if parties > 1 else 1))
    threshold = max(1, min(threshold, parties))

    session = {
        "id": f"{slugify((payload.get('label') or payload.get('election_name') or 'servidor-1').strip())}-{datetime.now().strftime('%Y%m%d-%H%M%S')}",
        "label": (payload.get("label") or "Servidor 1").strip(),
        "sid": (payload.get("sid") or "ONPE").strip(),
        "election_name": (payload.get("election_name") or "Elección Verificatum").strip(),
        "protocol_name": protocol_token((payload.get("election_name") or "EleccionVerificatum").strip()),
        "host": (payload.get("host") or default_session_host()).strip(),
        "parties": parties,
        "threshold": threshold,
        "base_hint_port": 4040,
        "base_http_port": 8040,
        "max_ciph": int(payload.get("max_ciph", 100)),
        "width": int(payload.get("width", 1)),
        "session_dir": str(SESSIONS_DIR / f"{slugify((payload.get('label') or payload.get('election_name') or 'servidor-1').strip())}-{datetime.now().strftime('%Y%m%d-%H%M%S')}"),
        "created_at": now_iso(),
        "operations": [],
    }
    session["session_dir"] = str(SESSIONS_DIR / session["id"])

    append_global_event(f"Creando nueva sesión {session['id']} en {session['session_dir']}.")
    try:
        create_party_layout(session)
    except Exception as exc:
        append_global_event(f"Fallo al preparar la sesión {session['id']}: {exc}")
        raise
    save_handshakes({"stations": {}})
    append_global_event(f"Se reinició el registro de estaciones para la nueva sesión {session['id']}.")
    persist_session(session)
    launch_distributed(
        session,
        "keygen",
        lambda _: "rm -f publicKey publicKey_ext && vmn -keygen -e publicKey && vmnc -pkey -outi native protInfo.xml publicKey publicKey_ext",
        {"auto_started": True},
    )
    append_event(Path(session["session_dir"]), "Etapa preliminar completada. La generación de llave está corriendo desde la interfaz web.")
    return enrich_session(session)


def handle_operation(payload: dict, party_idx: int | None = None) -> dict:
    session = get_active_session()
    if not session:
        raise RuntimeError("No hay una sesión activa. Cree primero la etapa preliminar.")

    action = payload.get("action", "").strip()
    session_dir = Path(session["session_dir"])
    max_ciph = int(payload.get("max_ciph", session["max_ciph"]))
    width = int(payload.get("width", session["width"]))
    verify_session = (payload.get("verify_session") or "default").strip()
    requested_auxsid = sanitize_auxsid(payload.get("auxsid") or "default")
    party_idx = party_idx or 1
    resolved_auxsid = None
    auxsid_changed = False
    cloned_from_auxsid = None

    active = current_active_operation(session)

    if action in SEQUENTIAL_ACTIONS:
        if active:
            if active["name"] not in SEQUENTIAL_ACTIONS or active["mode"] != "distributed-sequential":
                raise RuntimeError(f"No se puede iniciar {action}: primero debe terminar {active['name']}.")
            if active["name"] != action:
                raise RuntimeError(f"La fase activa es {active['name']}. Debe continuar esa fase antes de iniciar {action}.")
            next_idx = next_pending_party_idx(active)
            if next_idx is None:
                raise RuntimeError(f"La fase {action} ya fue iniciada en todas las parties. Espere a que termine.")
            if party_idx != next_idx:
                raise RuntimeError(f"Turno inválido. Debe iniciar {action} desde {build_party_name(next_idx)}.")
            resolved_auxsid = (active.get("payload") or {}).get("auxsid")
        else:
            if party_idx != 1:
                raise RuntimeError(f"Debe iniciar {action} desde party01.")

            if action == "precomp":
                if any(op["name"] == "precomp" and op["status"] == "ok" for op in collect_operations(session)):
                    raise RuntimeError("La precomputación ya fue completada u omitida en esta sesión.")
            elif action == "shuffle":
                available_ciphertexts = scan_auxsid_slots(session, "ciphertexts")
                shuffled_auxsids = {op.get("payload", {}).get("auxsid") for op in collect_operations(session) if op["name"] == "shuffle" and op["status"] == "ok"}
                shuffled_auxsids.discard(None)
                if requested_auxsid in available_ciphertexts and requested_auxsid not in shuffled_auxsids:
                    resolved_auxsid = requested_auxsid
                else:
                    pending_requested_auxsid = next_pending_auxsid_for_request(session, requested_auxsid)
                    if pending_requested_auxsid:
                        resolved_auxsid = pending_requested_auxsid
                        auxsid_changed = pending_requested_auxsid != requested_auxsid
                    elif requested_auxsid in available_ciphertexts:
                        resolved_auxsid, auxsid_changed = resolved_auxsid_for_write(session, requested_auxsid)
                        cloned_from_auxsid = requested_auxsid
                    else:
                        resolved_auxsid = next_pending_auxsid(session)
                if not resolved_auxsid:
                    raise RuntimeError("No hay ciphertexts pendientes para mezclar.")
                auxsid_changed = auxsid_changed or resolved_auxsid != requested_auxsid
            elif action == "decrypt":
                resolved_auxsid = requested_auxsid if requested_auxsid == next_decrypt_auxsid(session) else next_decrypt_auxsid(session)
                if not resolved_auxsid:
                    raise RuntimeError("No hay mezclas pendientes de descifrar.")
                auxsid_changed = resolved_auxsid != requested_auxsid
            elif action == "verify":
                resolved_auxsid = requested_auxsid if requested_auxsid == next_verify_auxsid(session) else next_verify_auxsid(session)
                if not resolved_auxsid:
                    raise RuntimeError("No hay mezclas pendientes de verificación.")
                auxsid_changed = resolved_auxsid != requested_auxsid

    if action == "precomp":
        op = active if active else prepare_sequential_operation(session, "precomp", {"max_ciph": max_ciph})
        start_sequential_party(session, op, party_idx, f"vmn -precomp -e -maxciph {max_ciph}")
        append_event(session_dir, f"{build_party_name(party_idx)} lanzó precomputación para {max_ciph} ciphertexts.")
    elif action == "ciphertexts":
        if active:
            raise RuntimeError(f"No se puede generar ciphertexts demo mientras {active['name']} sigue en curso.")
        resolved_auxsid, auxsid_changed = resolved_auxsid_for_write(session, requested_auxsid)
        slots = slot_paths(session, resolved_auxsid)
        public_key_native = party_file(session, 1, "publicKey_ext")
        if not file_ready(public_key_native):
            session_event_error(session, f"No se puede generar ciphertexts de prueba: falta {public_key_native}.")
        copy_block = "for dest in ../party*; do [ \"$dest\" = \"../party01\" ] && continue; cp -f ciphertexts ciphertexts_ext \"$dest\"/; done"
        command = "\n".join(
            [
                f"rm -f {slots['ciphertexts']} {slots['ciphertexts_ext']}",
                f"vmnd -ciphs -e -i native -width {width} publicKey_ext {max_ciph} {slots['ciphertexts_ext']}",
                f"vmnc -ciphs -sloppy -ini native -width {width} protInfo.xml {slots['ciphertexts_ext']} {slots['ciphertexts']}",
                f"for dest in ../party*; do [ \"$dest\" = \"../party01\" ] && continue; cp -f {slots['ciphertexts']} {slots['ciphertexts_ext']} \"$dest\"/; done",
            ]
        )
        launch_single_party(session, "ciphertexts", 1, command, {"max_ciph": max_ciph, "width": width, "auxsid": resolved_auxsid})
        change_note = f" AuxSID autoajustado a {resolved_auxsid}." if auxsid_changed else ""
        append_event(session_dir, f"Generación de ciphertexts ficticios lanzada desde party01 para {max_ciph} votos con AuxSID {resolved_auxsid}.{change_note}")
    elif action == "shuffle":
        if cloned_from_auxsid:
            clone_ciphertext_slot(session, cloned_from_auxsid, resolved_auxsid)
        slots = slot_paths(session, resolved_auxsid or "default")
        missing = [build_party_name(idx) for idx in range(1, session["parties"] + 1) if not file_ready(party_file(session, idx, slots["ciphertexts"]))]
        if missing:
            session_event_error(
                session,
                "No se puede mezclar: faltan ciphertexts válidos en "
                + ", ".join(missing)
                + f". Cargue los ciphertexts reales o genere ciphertexts de prueba para AuxSID {resolved_auxsid} desde party01.",
            )
        shuffle_parts = ["vmn", "-shuffle", "privInfo.xml", "protInfo.xml", slots["ciphertexts"], slots["ciphertextsout"]]
        if resolved_auxsid != "default":
            shuffle_parts.extend(["-auxsid", resolved_auxsid])
        op = active if active else prepare_sequential_operation(session, "shuffle", {"width": width, "auxsid": resolved_auxsid, "slots": slots})
        start_sequential_party(session, op, party_idx, f"rm -f {slots['ciphertextsout']} && {shell_join(shuffle_parts)}")
        change_note = f" AuxSID autoajustado a {resolved_auxsid}." if auxsid_changed else ""
        clone_note = f" Reutilizando ciphertexts desde AuxSID {cloned_from_auxsid}." if cloned_from_auxsid else ""
        append_event(session_dir, f"{build_party_name(party_idx)} inició la fase de mezclado para AuxSID {resolved_auxsid}.{change_note}{clone_note}")
    elif action == "decrypt":
        slots = slot_paths(session, resolved_auxsid or "default")
        missing = [build_party_name(idx) for idx in range(1, session["parties"] + 1) if not file_ready(party_file(session, idx, slots["ciphertextsout"]))]
        if missing:
            session_event_error(
                session,
                "No se puede descifrar: faltan ciphertextsout válidos en "
                + ", ".join(missing)
                + f". Ejecute primero la mezcla de AuxSID {resolved_auxsid} y confirme que terminó sin errores.",
            )
        decrypt_parts = ["vmn", "-decrypt", "privInfo.xml", "protInfo.xml", slots["ciphertextsout"], slots["plaintexts_orig"]]
        if resolved_auxsid != "default":
            decrypt_parts.extend(["-auxsid", resolved_auxsid])
        op = active if active else prepare_sequential_operation(session, "decrypt", {"auxsid": resolved_auxsid, "slots": slots})
        command = f"rm -f {slots['plaintexts_orig']} {slots['plaintexts']} && {shell_join(decrypt_parts)} && vmnc -plain -outi native protInfo.xml {slots['plaintexts_orig']} {slots['plaintexts']}"
        start_sequential_party(session, op, party_idx, command)
        change_note = f" AuxSID autoajustado a {resolved_auxsid}." if auxsid_changed else ""
        append_event(session_dir, f"{build_party_name(party_idx)} inició la fase de desencriptación para AuxSID {resolved_auxsid}.{change_note}")
    elif action == "verify":
        protinfo = party_file(session, 1, "protInfo.xml")
        if not file_ready(protinfo):
            session_event_error(session, f"No se puede verificar: falta {protinfo}.")
        slots = slot_paths(session, resolved_auxsid or "default")
        proof_source = f"../dir/nizkp/{resolved_auxsid}"
        verify_parts = ["vmnv", "-v", "-e", "-mix", "protInfo.xml", resolved_auxsid]
        if resolved_auxsid != "default":
            verify_parts.extend(["-auxsid", resolved_auxsid])
        verify_cmd = "\n".join(
            [
                "rm -rf export",
                "mkdir -p export",
                "cd export",
                f"cp -R {proof_source} .",
                "cp ../protInfo.xml .",
                shell_join(verify_parts),
            ]
        )
        op = active if active else prepare_sequential_operation(session, "verify", {"verify_session": verify_session, "auxsid": resolved_auxsid, "slots": slots})
        start_sequential_party(session, op, party_idx, verify_cmd)
        change_note = f" AuxSID autoajustado a {resolved_auxsid}." if auxsid_changed else ""
        append_event(session_dir, f"{build_party_name(party_idx)} inició verificación para AuxSID {resolved_auxsid}.{change_note}")
    else:
        raise RuntimeError(f"Operación no soportada: {action}")

    return {
        "session": enrich_session(get_active_session()),
        "resolved_auxsid": resolved_auxsid,
        "auxsid_changed": auxsid_changed,
    }


def party_payload(session: dict | None, party_idx: int) -> dict:
    payload = {
        "ui_port": BASE_UI_PORT + party_idx,
        "party_index": party_idx,
        "party_name": build_party_name(party_idx),
        "active_session": None,
        "party": None,
        "console_lines": [],
        "latest_operation": None,
        "controls": None,
    }
    if not session or party_idx > session["parties"]:
        return payload

    enriched = enrich_session(session)
    detail = next(item for item in enriched["party_details"] if item["id"] == party_idx)
    log_path, op = latest_log_for_party(session, party_idx)
    payload["active_session"] = {
        "id": enriched["id"],
        "label": enriched["label"],
        "parties": enriched["parties"],
        "host": enriched["host"],
        "sid": enriched["sid"],
        "election_name": enriched["election_name"],
        "workspace": enriched["workspace"],
        "events": enriched["events"],
    }
    payload["party"] = detail
    payload["all_parties"] = enriched["party_details"]
    payload["latest_operation"] = get_operation_for_party(session, party_idx)
    payload["operations"] = enriched["operations"]
    payload["console_lines"] = tail_lines(log_path, 300) if log_path else []
    payload["latest_log_operation"] = op["name"] if op else None
    payload["controls"] = party_controls(session, party_idx)
    payload["auxsids"] = {
        "used": sorted(used_mix_auxsids(session), key=lambda value: (value != "default", value)),
        "pending_ciphertexts": sorted(scan_auxsid_slots(session, "ciphertexts"), key=lambda value: (value != "default", value)),
        "next_shuffle": next_pending_auxsid(session),
        "next_decrypt": next_decrypt_auxsid(session),
        "next_verify": next_verify_auxsid(session),
        "suggested": current_upload_auxsid(session, "default"),
    }
    return payload


def public_key_payload(session: dict, native: bool = True) -> dict:
    filename = "publicKey_ext" if native else "publicKey"
    path = party_file(session, 1, filename)
    if not file_ready(path):
        raise RuntimeError(f"La llave pública {filename} todavía no está disponible.")
    return {
        "session_id": session["id"],
        "session_name": session["label"],
        "session_label": session["label"],
        "election_name": session["election_name"],
        "native": native,
        "filename": filename,
        "path": str(path),
        "content": read_text_file(path),
    }


def auxsid_api_payload(session: dict) -> dict:
    return {
        "used_auxsids": sorted(used_mix_auxsids(session), key=lambda value: (value != "default", value)),
        "reserved_auxsids": sorted(reserved_auxsids(session), key=lambda value: (value != "default", value)),
        "pending_ciphertexts": sorted(scan_auxsid_slots(session, "ciphertexts"), key=lambda value: (value != "default", value)),
        "next_shuffle": next_pending_auxsid(session),
        "next_decrypt": next_decrypt_auxsid(session),
        "next_verify": next_verify_auxsid(session),
        "suggested_auxsid": current_upload_auxsid(session, "default"),
        "nizkp_root": str(Path(session["session_dir"]) / build_party_name(1) / "dir" / "nizkp"),
    }


def emission_context_payload(session: dict, requested_auxsid: str = "default") -> dict:
    requested = sanitize_auxsid(requested_auxsid or "default")
    resolved, auxsid_changed, accumulated, accumulated_from_auxsid = resolved_auxsid_for_ciphertext_upload(session, requested)
    return {
        "session_id": session["id"],
        "session_name": session["label"],
        "session_label": session["label"],
        "election_name": session["election_name"],
        "sid": session["sid"],
        "requested_auxsid": requested,
        "resolved_auxsid": resolved,
        "auxsid": resolved,
        "auxsid_changed": auxsid_changed,
        "accumulated": accumulated,
        "accumulated_from_auxsid": accumulated_from_auxsid,
        "accepting_votes": session_accepting_votes(session),
    }


def plaintext_payload(session: dict, auxsid: str, party_idx: int, native: bool = True) -> dict:
    slots = slot_paths(session, auxsid)
    filename = slots["plaintexts"] if native else slots["plaintexts_orig"]
    path = party_file(session, party_idx, filename)
    if not file_ready(path):
        raise RuntimeError(f"Los plaintexts {filename} todavía no están disponibles en {build_party_name(party_idx)}.")
    return {
        "session_id": session["id"],
        "session_name": session["label"],
        "session_label": session["label"],
        "election_name": session["election_name"],
        "party": build_party_name(party_idx),
        "auxsid": auxsid,
        "native": native,
        "filename": filename,
        "path": str(path),
        "content": read_text_file(path),
    }


def upload_ciphertexts(payload: dict) -> dict:
    session = get_active_session()
    if not session:
        raise RuntimeError("No hay una sesión activa. Cree primero la etapa preliminar.")
    active = current_active_operation(session)
    if active:
        raise RuntimeError(f"No se pueden cargar ciphertexts mientras {active['name']} sigue en curso.")

    requested_session_id = (payload.get("session_id") or "").strip()
    requested_session_name = (payload.get("session_name") or payload.get("session_label") or "").strip()
    if requested_session_id and requested_session_id != session["id"]:
        raise RuntimeError(f"La carga corresponde a la sesión {requested_session_id}, pero la sesión activa es {session['id']}.")
    if requested_session_name and requested_session_name != session["label"]:
        raise RuntimeError(f"La carga corresponde a la sesión '{requested_session_name}', pero la sesión activa es '{session['label']}'.")
    handshake_validated, handshake_station_id, handshake_station = validate_handshake(payload, session)

    requested_auxsid = sanitize_auxsid(payload.get("auxsid") or "default")
    resolved_auxsid, auxsid_changed, accumulated, accumulation_source_auxsid = resolved_auxsid_for_ciphertext_upload(session, requested_auxsid)
    slots = slot_paths(session, resolved_auxsid)
    width = int(payload.get("width", session["width"]))
    session_dir = Path(session["session_dir"])

    ciphertexts = payload.get("ciphertexts")
    ciphertexts_b64 = payload.get("ciphertexts_b64")
    ciphertexts_ext = payload.get("ciphertexts_ext")
    ciphertexts_ext_b64 = payload.get("ciphertexts_ext_b64")
    data_format = (payload.get("format") or ("native" if ciphertexts_ext is not None or ciphertexts_ext_b64 is not None else "native")).strip().lower()

    if not any([ciphertexts, ciphertexts_b64, ciphertexts_ext, ciphertexts_ext_b64]):
        raise RuntimeError("Debe enviar 'ciphertexts' o 'ciphertexts_ext' en el cuerpo JSON.")

    ciphertexts_bytes = base64.b64decode(ciphertexts_b64) if ciphertexts_b64 else (ciphertexts.encode("utf-8") if ciphertexts is not None else b"")
    ciphertexts_ext_bytes = base64.b64decode(ciphertexts_ext_b64) if ciphertexts_ext_b64 else (ciphertexts_ext.encode("utf-8") if ciphertexts_ext is not None else b"")

    written_files = []
    party1_dir = Path(session["session_dir"]) / build_party_name(1)
    party1_raw = party1_dir / slots["ciphertexts"]
    party1_native = party1_dir / slots["ciphertexts_ext"]
    temp_raw = party1_dir / f".incoming_{slots['ciphertexts']}"
    temp_native = party1_dir / f".incoming_{slots['ciphertexts_ext']}"
    validated_files: list[str] = []
    replicated_files: dict[str, list[str]] = {
        slots["ciphertexts"]: [],
        slots["ciphertexts_ext"]: [],
    }
    input_fields: list[str] = []
    source_slots = slot_paths(session, accumulation_source_auxsid) if accumulation_source_auxsid else slots
    source_raw = party1_dir / source_slots["ciphertexts"]
    source_native = party1_dir / source_slots["ciphertexts_ext"]
    previous_raw = read_bytes_file(source_raw) if accumulated and source_raw.exists() else b""
    previous_native = read_bytes_file(source_native) if accumulated and source_native.exists() else b""
    remove_if_exists(temp_raw)
    remove_if_exists(temp_native)

    try:
        if data_format in {"native", "ext"}:
            native_payload = ciphertexts_ext_bytes if ciphertexts_ext is not None or ciphertexts_ext_b64 else ciphertexts_bytes
            native_payload = merge_binary_payload(previous_native, native_payload) if accumulated else native_payload
            input_fields = ["ciphertexts_ext"] if ciphertexts_ext is not None or ciphertexts_ext_b64 is not None else ["ciphertexts"]
            write_bytes(temp_native, native_payload)
            validated_files.append(str(temp_native))
            if TEST_MODE:
                raw_payload = merge_binary_payload(previous_raw, b"mock-ciphertexts-from-ext\n") if accumulated else b"mock-ciphertexts-from-ext\n"
                write_bytes(temp_raw, raw_payload)
                validated_files.append(str(temp_raw))
            else:
                try:
                    run_checked(
                        f"vmnc -ciphs -sloppy -ini native -width {width} protInfo.xml {temp_native.name} {temp_raw.name}",
                        party1_dir,
                        session_dir,
                    )
                except Exception as exc:  # noqa: BLE001
                    raise RuntimeError(
                        "Los votos cifrados recibidos por la API no son validos en formato native. "
                        "El endpoint rechazo la carga antes de replicarla."
                    ) from exc
                validated_files.append(str(temp_raw))
        elif data_format in {"raw", "verificatum"}:
            raw_payload = merge_binary_payload(previous_raw, ciphertexts_bytes) if accumulated else ciphertexts_bytes
            input_fields = ["ciphertexts"]
            write_bytes(temp_raw, raw_payload)
            validated_files.append(str(temp_raw))
            if TEST_MODE:
                native_payload = merge_binary_payload(previous_native, b"mock-ciphertexts-ext-from-raw\n") if accumulated else b"mock-ciphertexts-ext-from-raw\n"
                write_bytes(temp_native, native_payload)
                validated_files.append(str(temp_native))
            else:
                try:
                    run_checked(
                        f"vmnc -ciphs -sloppy -outi native -width {width} protInfo.xml {temp_raw.name} {temp_native.name}",
                        party1_dir,
                        session_dir,
                    )
                except Exception as exc:  # noqa: BLE001
                    raise RuntimeError(
                        "Los ciphertexts recibidos por la API no tienen un formato válido para Verificatum. "
                        "Si está enviando datos nativos, use 'format': 'native' o el campo 'ciphertexts_ext'. "
                        "El endpoint rechazo la carga antes de replicarla."
                    ) from exc
                validated_files.append(str(temp_native))
        else:
            raise RuntimeError("Formato no soportado. Use 'native', 'ext', 'raw' o 'verificatum'.")

        ext_written = copy_bytes_to_all_parties(session, slots["ciphertexts_ext"], read_bytes_file(temp_native))
        raw_written = copy_bytes_to_all_parties(session, slots["ciphertexts"], read_bytes_file(temp_raw))
        replicated_files[slots["ciphertexts_ext"]] = ext_written
        replicated_files[slots["ciphertexts"]] = raw_written
        written_files.extend(ext_written)
        written_files.extend(raw_written)
    finally:
        remove_if_exists(temp_raw)
        remove_if_exists(temp_native)

    change_note = f" AuxSID autoajustado a {resolved_auxsid}." if auxsid_changed else ""
    source_note = f" desde AuxSID {accumulation_source_auxsid}" if accumulated and accumulation_source_auxsid else ""
    accumulation_note = f" Carga acumulada sobre ciphertexts previos{source_note}." if accumulated else ""
    append_event(session_dir, f"Ciphertexts validados y cargados vía API para AuxSID {resolved_auxsid} con formato {data_format}.{change_note}{accumulation_note}")
    return {
        "ok": True,
        "validated": True,
        "format_received": data_format,
        "format_resolved": "native" if data_format in {"native", "ext"} else "raw",
        "input_fields": input_fields,
        "session_id": session["id"],
        "session_name": session["label"],
        "resolved_auxsid": resolved_auxsid,
        "auxsid_changed": auxsid_changed,
        "accumulated": accumulated,
        "accumulated_from_auxsid": accumulation_source_auxsid,
        "handshake_validated": handshake_validated,
        "station_id": handshake_station_id,
        "lease_id": handshake_station.get("lease_id") if handshake_station else None,
        "slots": slots,
        "validated_files": validated_files,
        "replicated_files": replicated_files,
        "replicated_to": [build_party_name(idx) for idx in range(1, session["parties"] + 1)],
        "party_validated": build_party_name(1),
        "width": width,
        "written_files": written_files,
        "session": enrich_session(session),
    }


class GUIHandler(BaseHTTPRequestHandler):
    server_version = "VerificatumGUI/2.0"

    def _cors_headers(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Station-Id, X-Mixer-Handshake, X-Session-Id")

    def _json(self, payload: dict, status: int = 200) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self._cors_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _send_file(self, path: Path, content_type: str, download_name: str | None = None) -> None:
        if not path.exists():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        data = path.read_bytes()
        self.send_response(HTTPStatus.OK)
        self._cors_headers()
        self.send_header("Content-Type", content_type)
        if download_name:
            self.send_header("Content-Disposition", f'attachment; filename="{download_name}"')
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self) -> None:
        self.send_response(HTTPStatus.NO_CONTENT)
        self._cors_headers()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        route = parsed.path
        session = get_active_session()

        if route == "/":
            if getattr(self.server, "view_mode", "admin") == "admin":
                self._send_file(STATIC_DIR / "admin.html", "text/html; charset=utf-8")
            else:
                self._send_file(STATIC_DIR / "party.html", "text/html; charset=utf-8")
            return

        if route in {"/static/styles.css", "/static/admin.js", "/static/party.js"}:
            content_type = "text/css; charset=utf-8" if route.endswith(".css") else "application/javascript; charset=utf-8"
            self._send_file(STATIC_DIR / route.split("/")[-1], content_type)
            return

        if route == "/api/state":
            payload = {
                "server": {
                    **mixer_status_payload(session),
                    "ui_host": UI_HOST,
                    "max_ui_parties": MAX_UI_PARTIES,
                    "view_mode": getattr(self.server, "view_mode", "admin"),
                    "party_index": getattr(self.server, "party_index", None),
                },
                "defaults": {
                    "label": "Servidor 1",
                    "election_name": "Elección Verificatum",
                    "sid": "ONPE",
                    "host": default_session_host(),
                    "parties": 3,
                    "threshold": 2,
                },
                "general_events": tail_lines(GLOBAL_LOG_FILE, 400),
                "active_session": enrich_session(session) if session else None,
                "keygen_ready": keygen_ready(session) if session else False,
            }
            self._json(payload)
            return

        if route == "/api/discovery":
            self._json(discovery_payload(session))
            return

        if route == "/api/public-key":
            if not session:
                self._json({"error": "No hay una sesión activa."}, 400)
                return
            native = parse_qs(parsed.query).get("format", ["native"])[0] != "verificatum"
            try:
                self._json({"ok": True, **public_key_payload(session, native=native)})
            except Exception as exc:  # noqa: BLE001
                self._json({"error": str(exc)}, 400)
            return

        if route == "/api/emission-context":
            if not session:
                self._json({"error": "No hay una sesión activa."}, 400)
                return
            params = parse_qs(parsed.query)
            requested_auxsid = params.get("auxsid", ["default"])[0]
            try:
                self._json({"ok": True, **emission_context_payload(session, requested_auxsid)})
            except Exception as exc:  # noqa: BLE001
                self._json({"error": str(exc)}, 400)
            return

        if route in {"/api/plaintexts", "/api/plaintexts/download"}:
            if not session:
                self._json({"error": "No hay una sesión activa."}, 400)
                return
            params = parse_qs(parsed.query)
            auxsid = sanitize_auxsid(params.get("auxsid", ["default"])[0])
            native = params.get("format", ["native"])[0] != "verificatum"
            default_party = getattr(self.server, "party_index", None) or 1
            party_idx = int(params.get("party", [str(default_party)])[0])
            try:
                payload = plaintext_payload(session, auxsid, party_idx, native=native)
                if route == "/api/plaintexts/download":
                    self._send_file(Path(payload["path"]), "text/plain; charset=utf-8", payload["filename"])
                else:
                    self._json({"ok": True, **payload})
            except Exception as exc:  # noqa: BLE001
                self._json({"error": str(exc)}, 400)
            return

        if route == "/api/auxsids":
            if not session:
                self._json({"error": "No hay una sesión activa."}, 400)
                return
            try:
                self._json({"ok": True, **auxsid_api_payload(session)})
            except Exception as exc:  # noqa: BLE001
                self._json({"error": str(exc)}, 400)
            return

        if route == "/api/handshakes":
            self._json({"ok": True, "server": mixer_status_payload(session), "stations": active_handshakes()})
            return

        if route == "/api/party-state":
            party_idx = getattr(self.server, "party_index", None)
            if party_idx is None:
                params = parse_qs(parsed.query)
                party_idx = int(params.get("party", ["1"])[0])
            self._json(party_payload(session, int(party_idx)))
            return

        self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError:
            self._json({"error": "JSON inválido."}, 400)
            return

        try:
            if self.path == "/api/setup":
                self._json({"ok": True, "session": setup_session(payload)})
                return
            if self.path == "/api/handshake":
                client_ip = self.client_address[0] if self.client_address else ""
                self._json(register_handshake(payload, client_ip))
                return
            if self.path == "/api/ciphertexts":
                self._json(upload_ciphertexts(payload))
                return
            if self.path == "/api/operation":
                result = handle_operation(payload, getattr(self.server, "party_index", None))
                self._json({"ok": True, **result})
                return
            self.send_error(HTTPStatus.NOT_FOUND)
        except Exception as exc:  # noqa: BLE001
            append_global_event(f"Error atendiendo {self.path}: {exc}")
            self._json({"error": str(exc)}, 400)


def start_server(host: str, port: int, view_mode: str, party_index: int | None = None) -> tuple[ThreadingHTTPServer, threading.Thread]:
    server = ThreadingHTTPServer((host, port), GUIHandler)
    server.view_mode = view_mode
    server.party_index = party_index
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread


def main() -> None:
    global UI_HOST, BIND_HOST, BASE_UI_PORT, MAX_UI_PARTIES, SERVER_IDENTITY

    parser = argparse.ArgumentParser(description="GUI local para Verificatum sin xterm")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--public-host", default=None)
    parser.add_argument("--base-port", type=int, default=7040)
    parser.add_argument("--max-parties", type=int, default=9)
    args = parser.parse_args()

    BIND_HOST = args.host
    UI_HOST = args.public_host or (detect_host_ip() if args.host in {"0.0.0.0", "::"} else args.host)
    BASE_UI_PORT = args.base_port
    MAX_UI_PARTIES = args.max_parties

    if CLEAN_START and RUNTIME_DIR.exists():
        shutil.rmtree(RUNTIME_DIR)
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    SESSIONS_DIR.mkdir(parents=True, exist_ok=True)
    if GLOBAL_LOG_FILE.exists():
        GLOBAL_LOG_FILE.unlink()
    state = load_state()
    state["active_session"] = None
    save_state(state)
    SERVER_IDENTITY = load_or_create_server_identity()
    prune_handshakes({"stations": {}})
    append_global_event("Servidor GUI iniciado.")

    servers: list[ThreadingHTTPServer] = []
    admin_server, _ = start_server(args.host, args.base_port, "admin")
    servers.append(admin_server)
    for idx in range(1, args.max_parties + 1):
        server, _ = start_server(args.host, args.base_port + idx, "party", idx)
        servers.append(server)

    print(f"Panel de control: http://{UI_HOST}:{args.base_port}")
    for idx in range(1, args.max_parties + 1):
        print(f"Ventana {idx}: http://{UI_HOST}:{args.base_port + idx}")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
