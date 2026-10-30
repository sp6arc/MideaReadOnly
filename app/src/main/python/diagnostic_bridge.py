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
import lua_c3_decoder
from midealan.cloud import get_midea_cloud

_lock = threading.RLock()
_frame_stats = {}
_recent = []
_cloud_state = {"status": "not_checked"}
_cloud_credentials = None
_lua_values = {}
_lua_times = {}
_lua_sources = {}
_lua_packets = []
_raw_packets = []
_ALLOWED_REQUESTS = frozenset((
    "MessageQueryBasic", "MessageQuerySilence", "MessageQueryECO"
))
_ALLOWED_FRAMES = frozenset(("03-01", "03-05", "03-07", "03-10", "04-03", "04-04", "04-05"))


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
        # Passive tap runs BEFORE upstream's parser, which may ignore or reject
        # 04-05. Never touch the V3 transport, original bytes or polling.
        try:
            if len(message) >= 13 and message[0] == 0xAA and message[2] == 0xC3:
                family = f"{message[9]:02X}-{message[10]:02X}"
                if family in _ALLOWED_FRAMES:
                    at = _now()
                    decoded = lua_c3_decoder.decode_frame(message)
                    with _lock:
                        entry = _frame_stats.setdefault(family, {"count": 0})
                        entry["count"] += 1
                        entry["lastSeen"] = at
                        entry["lastBytes"] = len(message)
                        _recent.append({"at": at, "frame": family,
                                        "bytes": len(message),
                                        "declaredBytes": int(message[1]) + 1})
                        del _recent[:-100]
                        if decoded is not None:
                            _lua_packets.append({
                                "at": at, "frame": family,
                                "decodedCount": decoded["decodedCount"],
                                "bytes": decoded["length"],
                                "lengthMatches": decoded["lengthMatches"],
                            })
                            del _lua_packets[:-100]
                            for name, value in decoded["values"].items():
                                _lua_values[name] = value
                                _lua_times[name] = at
                                _lua_sources[name] = family
                            # These two 04 reports contain no serial-number
                            # fields in the supplied Lua mapping. Intentionally
                            # do NOT export raw 03-10 (SN at body 96..191).
                            if family in ("04-04", "04-05"):
                                _raw_packets.append({
                                    "at": at, "frame": family,
                                    "bytes": len(message),
                                    "hex": bytes(message).hex(),
                                    "declaredBytes": int(message[1]) + 1,
                                })
                                del _raw_packets[:-30]
        except Exception:
            # Diagnostics are observational only and must never interrupt
            # original authentication, decoder, heartbeat or reconnect.
            pass
        return original_process_message(message)
    dev.process_message = observe


def clear():
    global _cloud_credentials
    with _lock:
        _frame_stats.clear()
        _recent.clear()
        _lua_values.clear()
        _lua_times.clear()
        _lua_sources.clear()
        _lua_packets.clear()
        _raw_packets.clear()
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
            "luaDecoded": {
                "values": dict(_lua_values),
                "updatesAt": dict(_lua_times),
                "sources": dict(_lua_sources),
                "sourceLuaSha256": lua_c3_decoder.SOURCE_LUA_SHA256,
                "measurementUnitsVerified": False,
                "packets": list(_lua_packets[-20:]),
            },
            "rawFramesStored": len(_raw_packets),
            "upstreamPackageModified": False,
        }, ensure_ascii=False)


def export_capture():
    """Bounded full hex frames ONLY for passive 04-04 and 04-05.

    No 03-10 full frames: original unit responses contain SN fields.
    Captures may still reveal technical details of the installation; users
    should review a report before publishing it publicly.
    """
    with _lock:
        return {
            "rawFrames": list(_raw_packets),
            "luaDecodedPackets": list(_lua_packets),
            "capturePolicy": "04-04/04-05 only; 03-10 raw excluded",
            "frameBytesPosition": "AA header, byte 9 type, byte 10 subtype",
        }
