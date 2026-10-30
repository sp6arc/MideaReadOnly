"""Android adapter only. The upstream midealan package is copied UNMODIFIED at build time.

Protocol, query ordering, decryptors, cloud and C3 decoding are provided by
wuwentao/midea-lan. This adapter only converts calls/results to JSON.
"""
import asyncio
import json
import logging
import threading
import time
from enum import Enum
from datetime import datetime, timezone

import aiohttp

from midealan.cloud import get_default_cloud, get_midea_cloud, SUPPORTED_CLOUDS
from midealan.const import ProtocolVersion
from midealan.discover import discover as upstream_discover
from midealan.devices import device_selector
from midealan.version import __version__ as UPSTREAM_VERSION

_lock = threading.RLock()
_device = None
_events = []
_changed_at = {}
_last_values = {}
_last_error = ""
_session = 0
_max_events = 350


def _event(msg):
    with _lock:
        _events.append(datetime.now(timezone.utc).strftime("%H:%M:%SZ ") + str(msg))
        if len(_events) > _max_events:
            del _events[:-_max_events]


def _display(value):
    if isinstance(value, Enum):
        return value.name
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    if isinstance(value, bytes):
        return f"[binary {len(value)} bytes]"
    if isinstance(value, (list, tuple)):
        return [_display(x) for x in value]
    if isinstance(value, dict):
        return {str(k): _display(v) for k, v in value.items()}
    return str(value)


def _on_update(update):
    stamp = datetime.now(timezone.utc).isoformat()
    with _lock:
        for k, v in update.items():
            _last_values[str(k)] = _display(v)
            _changed_at[str(k)] = stamp
    _event(f"Zaktualizowano {len(update)} pól przez oryginalny dekoder")


class _BridgeLog(logging.Handler):
    def emit(self, record):
        # No secrets, passwords, tokens, raw frames, or serials from cloud debug.
        if record.levelno < logging.WARNING:
            return
        text = record.getMessage()
        sensitive = ("token", "password", "secret", "credential", "authorization",
                     "apikey", "access_token", "sign", "device id")
        if any(x in text.lower() for x in sensitive):
            text = f"{record.name}: {record.levelname} (szczegóły wrażliwe ukryte)"
        _event(f"UPSTREAM {record.levelname}: {text[:200]}")


_logger = logging.getLogger("midealan")
_logger.addHandler(_BridgeLog())
_logger.setLevel(logging.WARNING)


def info():
    import sys
    from midealan.devices.c3 import MideaC3Device
    return json.dumps({
        "engine": "ORIGINAL wuwentao/midea-lan",
        "upstreamVersion": UPSTREAM_VERSION,
        "python": sys.version.split()[0],
        "clouds": list(SUPPORTED_CLOUDS),
        "c3Implementation": MideaC3Device.__module__,
    }, ensure_ascii=False)


def scan(host=""):
    """Call ORIGINAL midealan.discover.discover, no custom UDP packets."""
    _event("Oryginalny discover() — wysyłanie UDP i nasłuch")
    found = upstream_discover(ip_address=host if host else None)
    pumps = []
    for item in found.values():
        if int(item.get("type", 0)) != 0xC3:
            continue
        pumps.append({
            "id": str(item["device_id"]),
            "host": str(item["ip_address"]),
            "port": int(item.get("port") or 6444),
            "model": str(item.get("model") or ""),
            "protocol": int(item.get("protocol") or 3),
            "type": int(item["type"]),
        })
    _event(f"Oryginalne discover() znalazło {len(pumps)} pomp C3")
    return json.dumps(pumps, ensure_ascii=False)


async def _retrieve_keys(appliance_id, cloud_name, username, password):
    if cloud_name not in SUPPORTED_CLOUDS:
        raise ValueError("Nieobsługiwana chmura: " + cloud_name)
    async with aiohttp.ClientSession() as client_session:
        cloud = get_midea_cloud(cloud_name, client_session, username, password)
        default_keys = await cloud.get_default_keys()
        if not username or not password:
            raise ValueError("Podaj dane własnego konta chmury dla autoryzacji LAN V3")
        logged = await cloud.login()
        if not logged:
            raise ValueError("Logowanie do chmury nieudane")
        cloud_keys = await cloud.get_cloud_keys(appliance_id)
        # Same dict merge as original MideaCLI._get_keys() in upstream cli.py.
        return {**cloud_keys, **default_keys}


def stop():
    global _device
    with _lock:
        old = _device
        _device = None
    if old is not None:
        _event("Zatrzymuję oryginalny wątek MideaDevice")
        old.close()
    return "OK"


def start_monitor(host, port, id_text, model, protocol, cloud_name, username, password):
    """Start ORIGINAL MideaDevice.run() thread (connect+auth+refresh_status(True)).
    No Kotlin X10, no custom retries, no altered query schedule.
    """
    global _device, _session, _last_error
    stop()
    with _lock:
        _session += 1
        session = _session
        _last_values.clear()
        _changed_at.clear()
        _events.clear()
        _last_error = ""
    _event("Start: oryginalna biblioteka Python " + UPSTREAM_VERSION)
    if len(model.strip()) < 6 or model.strip() == str(port).strip():
        raise ValueError("Nieprawidłowy model/SN8: użyj odczytu discover(), np. 171H120F, a nie portu TCP")
    appliance_id = int(id_text)
    transport = ProtocolVersion(int(protocol))
    if transport == ProtocolVersion.V3:
        _event("MideaCloud.login() → get_cloud_keys() oryginalnego autora")
        candidates = asyncio.run(_retrieve_keys(appliance_id, cloud_name, username, password))
    else:
        candidates = {0: {"token": "", "key": ""}}
    if not candidates:
        raise ValueError("Autor nie otrzymał żadnych kluczy/tokenów")
    _event(f"Przygotowano {len(candidates)} zestawów kluczy; bez ujawniania ich wartości")

    # Faithful copy of MideaCLI.discover():
    # test candidate auth keys with dev.connect(), then refresh_status(True)
    # before entering the original 30s refresh/heartbeat/reconnect thread.
    # All protocol behavior remains inside the original MideaDevice.
    from midealan.device import NoSupportedProtocol, AuthException
    for index, candidate in enumerate(candidates.values(), start=1):
        dev = device_selector(
            name="MideaRemonte C3",
            device_id=appliance_id,
            device_type=0xC3,
            ip_address=host,
            port=int(port),
            token=candidate["token"],
            key=candidate["key"],
            device_protocol=transport,
            model=model,
            subtype=0,
            customize="",
            cloud_name=cloud_name
        )
        if dev is None:
            raise RuntimeError("Oryginalny device_selector nie obsługuje C3")
        dev.register_update(_on_update)
        _event(f"Próba autoryzacji LAN {index}/{len(candidates)} — oryginalny connect(check_protocol=True)")
        # This is the exact method used by the upstream MideaDevice._connect_loop.
        # It performs refresh_status(True) AND set_available(connected).
        # Calling connect() and refresh_status(True) separately left
        # 'available' permanently false despite valid incoming updates.
        if not dev.connect(check_protocol=True):
            _event("Oryginalny connect(check_protocol=True): brak potwierdzenia; kolejny klucz")
            continue
        _event("Oryginalny connect(check_protocol=True): sprzęt online; etapy diagnostyki zakończone")
        with _lock:
            if _session != session:
                dev.close_socket()
                return "CANCELLED"
            _device = dev
        _event("Oryginalny MideaDevice.open(): heartbeat/refresh/reconnect autora")
        dev.open()
        return "OK"
    raise RuntimeError("Nie udało się uwierzytelnić i odpytać oryginalnego klienta")


def snapshot():
    with _lock:
        dev = _device
        events = list(_events)
        times = dict(_changed_at)
        error = _last_error
    if dev is None:
        return json.dumps({"running":False, "events": events, "values":{}, "error":error},
                          ensure_ascii=False)
    # Values are not synthesized: read EXACT device.attributes from upstream.
    values = {str(k): _display(v) for k, v in dev.attributes.items()}
    # Only fields confirmed by real process_message -> update_all callbacks.
    # The upstream constructor initializes many bools to False, and those
    # must NOT be presented as measured values before a response arrives.
    present = {k: values[k] for k in times if k in values and values[k] is not None}
    status = {
        "running": bool(dev.is_alive()),
        "available": bool(dev.available),
        "model": dev.model,
        "messageProtocol": int(dev._message_protocol_version),
        "transportProtocol": int(dev._device_protocol_version),
        "deviceVersion": UPSTREAM_VERSION,
        "unsupportedQueries": list(dev._unsupported_protocol),
        "presentCount": len(present),
        "allCount": len(values),
        "values":present,
        "updatesAt":times,
        "events":events,
        "error":error
    }
    return json.dumps(status, ensure_ascii=False)


def selfcheck():
    # Check that requests/decoders/transport exist in the ACTUAL upstream code.
    from midealan.device import MideaDevice
    from midealan.devices.c3.message import MessageQueryUnitPara
    from midealan.packet_builder import PacketBuilder
    from midealan.security import LocalSecurity
    query = MessageQueryUnitPara(0).serialize().hex()
    return json.dumps({
        "version":UPSTREAM_VERSION,
        "x10":query,
        "upstreamMideaDevice": MideaDevice.__module__,
        "upstreamPacketBuilder":PacketBuilder.__module__,
        "upstreamLocalSecurity":LocalSecurity.__module__,
        "doesNotPatchUpstream":True
    })
