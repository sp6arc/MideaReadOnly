"""Working-only Midea C3 LAN capture and cloud Lua metadata.

No protocol probing, configuration changes, or raw credential/serial exports.
The upstream 'midealan' package remains byte-for-byte unchanged. The adapter
filters its outgoing C3 polling list to the three queries verified on this
171H120F: X01, X05 and X07. Its standard V3 auth, discovery, transport,
heartbeat, decoder and reconnect loop are preserved.
"""
import asyncio
import hashlib
import json
import os
import tempfile
import threading
from datetime import datetime, timezone

import aiohttp
from midealan.cloud import get_midea_cloud

_lock = threading.RLock()
_frame_stats = {}
_recent = []
_cloud_state = {"status": "not_checked"}
_cloud_credentials = None
_ALLOWED_REQUESTS = frozenset((
    "MessageQueryBasic", "MessageQuerySilence", "MessageQueryECO"
))
_ALLOWED_FRAMES = frozenset(("03-01", "03-05", "03-07", "04-04"))


def _now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def attach(dev):
    """Filter unsupported C3 polls and passively count valid decoded frames."""
    original_build_query = dev.build_query
    def working_queries():
        queries = original_build_query()
        return [q for q in queries if type(q).__name__ in _ALLOWED_REQUESTS]
    dev.build_query = working_queries

    original_process_message = dev.process_message
    def observe(message):
        result = original_process_message(message)
        try:
            if len(message) >= 12 and message[0] == 0xAA and message[2] == 0xC3:
                key = f"{message[9]:02X}-{message[10]:02X}"
                if key in _ALLOWED_FRAMES:
                    at = _now()
                    with _lock:
                        entry = _frame_stats.setdefault(key, {"count": 0})
                        entry["count"] += 1
                        entry["lastSeen"] = at
                        _recent.append({"at": at, "frame": key})
                        if len(_recent) > 100:
                            del _recent[:-100]
        except Exception:
            pass  # Monitoring never interferes with the original parser.
        return result
    dev.process_message = observe


def clear():
    global _cloud_credentials
    with _lock:
        _frame_stats.clear()
        _recent.clear()
        _cloud_state.clear()
        _cloud_state["status"] = "not_checked"
        _cloud_credentials = None


def set_cloud(cloud_name, username, password, device_id):
    global _cloud_credentials
    with _lock:
        _cloud_credentials = (cloud_name, username, password, int(device_id))


async def _cloud_lua(include_bytes=False):
    with _lock:
        credentials = _cloud_credentials
    if credentials is None:
        raise ValueError("Podaj dane konta SmartHome oraz ID pompy")
    name, user, password, device_id = credentials
    async with aiohttp.ClientSession() as client:
        cloud = get_midea_cloud(name, client, user, password)
        if not await cloud.login():
            raise ValueError("Logowanie chmurowe nie powiodlo sie")
        appliances = await cloud.list_appliances(home_id=None) or {}
        matching = appliances.get(device_id)
        if matching is None:
            same_type = [a for a in appliances.values() if int(a.get("type") or 0) == 0xC3]
            if len(same_type) != 1:
                raise ValueError("Nie znaleziono jednoznacznej pompy C3 na koncie")
            matching = same_type[0]
        sn = matching.get("sn") or ""
        if len(sn) < 8:
            raise ValueError("Brak poprawnego numeru seryjnego z chmury")
        with tempfile.TemporaryDirectory(prefix="midea-lua-") as folder:
            path = await cloud.download_lua(
                folder, 0xC3, sn,
                model_number=matching.get("model"),
                manufacturer_code=matching.get("manufacturer_code") or "0000",
            )
            if not path or not os.path.isfile(path):
                raise ValueError("Chmura nie zwrocila pliku Lua")
            if os.path.commonpath((os.path.realpath(path), os.path.realpath(folder))) != os.path.realpath(folder):
                raise ValueError("Niedozwolona sciezka pliku Lua")
            with open(path, "rb") as source:
                content = source.read(2 * 1024 * 1024 + 1)
            if len(content) > 2 * 1024 * 1024:
                raise ValueError("Plik Lua przekracza limit 2 MiB")
    metadata = {
        "luaReceived": True,
        "luaSize": len(content),
        "luaSha256": hashlib.sha256(content).hexdigest(),
        "model": str(matching.get("sn8") or matching.get("model") or "")[:16],
    }
    return (metadata, content) if include_bytes else metadata


def download_lua_file(cache_dir):
    """Download EXACT cloud bytes into app-private cache, never into diagnostic JSON.

    Android's Storage Access Framework will save these bytes to the destination
    explicitly selected by the owner. Nothing from the authenticated session is
    prepended or appended to the .lua. This module does NOT run downloaded Lua.
    """
    if not cache_dir or not os.path.isabs(cache_dir):
        raise ValueError("Wymagany katalog cache aplikacji")
    destination = os.path.join(os.path.realpath(cache_dir), "Midea_171H120F_cloud_protocol.lua")
    partial = destination + ".part"
    try:
        metadata, content = asyncio.run(_cloud_lua(include_bytes=True))
        if not content:
            raise ValueError("Otrzymano pusty plik Lua")
        os.makedirs(cache_dir, mode=0o700, exist_ok=True)
        with open(partial, "wb") as stream:
            stream.write(content)
        os.replace(partial, destination)
    except Exception:
        if os.path.exists(partial):
            os.remove(partial)
        raise
    with _lock:
        _cloud_state.clear()
        _cloud_state.update({
            "status": "received",
            "values": metadata,
            "receivedAt": _now(),
            "note": "Pobrano oryginalny plik Lua; gotowy do zapisu w pamieci telefonu",
        })
    # Path stays inside this app; it is NEVER added to JSON diagnostic export.
    return json.dumps({"path": destination, "metadata": metadata}, ensure_ascii=False)


def cloud_profile():
    with _lock:
        _cloud_state.clear()
        _cloud_state.update({"status": "checking", "started": _now()})
    try:
        metadata = asyncio.run(_cloud_lua())
    except Exception as error:
        # Do not export remote error strings; they can contain account IDs.
        message = str(error) if type(error) is ValueError else "Szczegoly chmury ukryte"
        with _lock:
            _cloud_state.update({"status": "error",
                                 "note": (type(error).__name__ + ": " + message)[:160]})
        return "ERROR"
    with _lock:
        _cloud_state.update({"status": "received", "values": metadata,
                             "receivedAt": _now(),
                             "note": "Profil Lua, nie pomiar na zywo"})
    return "OK"


def snapshot():
    with _lock:
        return json.dumps({
            "outgoingQueries": ["X01", "X05", "X07"],
            "automaticUnsupportedQueriesDisabled": ["X09", "X10"],
            "frameStats": {k: dict(v) for k, v in _frame_stats.items()},
            "recentFrames": list(_recent[-30:]),
            "cloud": dict(_cloud_state),
            "upstreamPackageModified": False,
        }, ensure_ascii=False)
