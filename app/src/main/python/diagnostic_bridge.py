"""Read-only, opt-in C3 protocol experiments. Upstream midealan is never edited.

Each query uses the original MessageQuestCustom, PacketBuilder, V3 crypto and
the already authenticated, running upstream device. Responses are observed
at the decoded AA-frame boundary BEFORE upstream's decoder; the decoder is
still invoked unchanged. Only bounded sensor fields (never raw serials,
passwords, tokens or full frames) enter the diagnostic JSON export.
"""
import asyncio
import hashlib
import json
import os
import tempfile
import threading
import time
from datetime import datetime, timezone

import aiohttp
from midealan.cloud import get_midea_cloud
from midealan.const import DeviceType
from midealan.message import MessageQuestCustom, MessageType

_lock = threading.RLock()
_device = None
_state = {}
_inbox = []
_creds = None
_registered = False

METHODS = {
    "X10": {"title": "Standard C3 / X10", "query": bytes([0x10]), "reply": (3, 0x10), "timeout": 20},
    "PUSH": {"title": "Nasluch 04-05 bez zapytan", "query": None, "reply": (4, 5), "timeout": 360},
    "ITS": {"title": "ITS / BTRI 03-02-01", "query": bytes([0x02, 0x01]), "reply": (3, 2), "timeout": 20},
    "PAGE10": {"title": "Strona 2F10", "query": bytes.fromhex("00 2F 10 FF 1C FF FF 00 FF FF"), "reply": (3, 0), "timeout": 20},
    "PAGE12": {"title": "Strona 2F12", "query": bytes.fromhex("00 2F 12 FF 1C FF FF 00 FF FF"), "reply": (3, 0), "timeout": 20},
    "X08": {"title": "Parametry instalatora X08", "query": bytes([8]), "reply": (3, 8), "timeout": 20},
    "X0A": {"title": "Parametry HMI X0A", "query": bytes([10]), "reply": (3, 10), "timeout": 20},
    "X0D": {"title": "Diagnostyka OEM X0D", "query": bytes([13]), "reply": (3, 13), "timeout": 20},
    "CLOUD": {"title": "Cloud: odczyt profilu Lua", "query": None, "reply": None, "timeout": 45},
}


def _now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _be(data, index):
    if len(data) < index + 2:
        raise ValueError("Short body")
    return (data[index] << 8) | data[index + 1]


def _signed(v):
    return v - 256 if v >= 128 else v


def _inspect(name, body):
    """Decode only exact, documented read-only payload layouts."""
    if name == "ITS" and len(body) >= 50 and body[:2] == bytes([2, 1]):
        d = 1
        return {
            "temp_T3_C": body[d+20] - 35, "temp_T4_C": body[d+21] - 35,
            "temp_water_in_C": body[d+24] - 35,
            "temp_water_out_C": body[d+25] - 35,
            "temp_TF_C": body[d+14], "temp_TP_C": body[d+15],
            "water_pressure_raw": body[d+17],
            "water_flow_switch_raw": body[d+18],
            "odu_voltage_raw": body[d+29]
        }
    if name == "PAGE10" and len(body) >= 150 and body[:3] == bytes([0, 0x2f, 0x10]):
        return {
            "comp_run_freq_Hz": body[10],
            "outdoor_T_C": _be(body, 15)/10,
            "water_in_C": _be(body, 19)/10,
            "water_out_C": _be(body, 21)/10,
            "mains_voltage_raw": body[87],
            "dhw_running_raw": body[145],
            "heating_running_raw": body[149],
        }
    if name == "PAGE12" and len(body) >= 21 and body[:3] == bytes([0, 0x2f, 0x12]):
        return {"electrical_power_kW": _be(body, 19)/100}
    if name in ("X10", "PUSH") and len(body) >= 11:
        # Byte offsets from original published 171H120F Lua codec.
        if name == "X10":
            return {
                "comp_run_freq_Hz": body[1],
                "T3_C": _signed(body[7]), "T4_C": _signed(body[8]),
                "TP_C": _signed(body[9]),
                "water_in_C": _signed(body[10]),
                "water_out_C": _signed(body[11]) if len(body)>11 else None,
                **({"pressure_high_raw": _be(body, 43), "pressure_low_raw": _be(body, 45)} if len(body) >= 47 else {}),
            }
        if len(body) >= 8:
            return {
                "comp_run_freq_Hz": body[1],
                "fan_speed_raw": body[2]*10,
                "T3_C": _signed(body[3]),
                "T4_C": _signed(body[4]),
                "TP_C": _signed(body[5]),
                "water_in_C": _signed(body[6]),
                "water_out_C": _signed(body[7]),
                **({"pressure_high_raw": _be(body, 17), "pressure_low_raw": _be(body, 19)} if len(body)>=21 else {}),
            }
    return {}


def _match(method, kind, body):
    if not body:
        return False
    typ, sub = method["reply"]
    if kind != typ or body[0] != sub:
        return False
    if method is METHODS["ITS"]:
        return len(body)>=50 and body[1] == 1
    if method is METHODS["PAGE10"]:
        return len(body)>=150 and body[1:3] == bytes([0x2f, 0x10])
    if method is METHODS["PAGE12"]:
        return len(body)>=21 and body[1:3] == bytes([0x2f, 0x12])
    return True


def _capture(msg):
    if len(msg) < 12 or msg[0] != 0xAA or msg[2] != 0xC3:
        return
    typ = int(msg[9])
    body = bytes(msg[10:-1])
    if not body:
        return
    now = time.monotonic()
    evt = {"time":_now(), "messageType":f"{typ:02X}",
           "bodyType":f"{body[0]:02X}", "bodyBytes":len(body)}
    # No complete frame or serial-bearing bytes are recorded.
    with _lock:
        _inbox.append(evt)
        if len(_inbox)>80:
            del _inbox[:-80]
        for name, state in _state.items():
            if name=="CLOUD" or state.get("status") != "waiting":
                continue
            method = METHODS[name]
            if now>state.get("_deadline",0):
                continue
            if _match(method, typ, body):
                state["status"]="received"
                state["response"]=evt
                fields=_inspect(name,body)
                state["values"]=fields
                state["note"] = ("Odpowiedz odebrana i rozpoznana"
                                 if fields else
                                 "Odebrano zgodny typ ramki; brak bezpiecznego dekodera dla tego wariantu.")
            elif name=="PUSH" and typ==4 and body[0] == 4:
                state["x04Seen"] = state.get("x04Seen",0)+1


def attach(dev):
    """Observe inbound AA frames; retain original upstream decoding unchanged."""
    global _device
    with _lock:
        _device=dev
        _state.clear()
        _inbox.clear()
    original=dev.process_message
    def observe(msg):
        try:
            _capture(msg)
        except Exception:
            pass
        return original(msg)
    dev.process_message=observe


def set_cloud(cloud_name, username, password, appliance_id):
    global _creds
    with _lock:
        _creds=(cloud_name,username,password,int(appliance_id))


def clear():
    global _device, _creds
    with _lock:
        _device=None
        _creds=None
        _state.clear()
        _inbox.clear()


def probe(name):
    if name not in METHODS:
        raise ValueError("Nieznana metoda odczytu")
    method=METHODS[name]
    now=time.monotonic()
    with _lock:
        if any(v.get("status")=="waiting" and v.get("_deadline",0)>now for k,v in _state.items() if k != "PUSH"):
            raise ValueError("Inne zapytanie jest w toku. Poczekaj na jego wynik.")
        dev=_device
        if name!="CLOUD" and (dev is None or not dev.available):
            raise ValueError("Najpierw uruchom i polacz bazowy silnik LAN")
        if name=="CLOUD" and not _creds:
            raise ValueError("Najpierw uruchom silnik i zaloguj SmartHome")
        state={"title":method["title"],"started":_now(),"status":"waiting",
               "_deadline":now+method["timeout"],"timeoutSec":method["timeout"],
               "values":{},"note":"Weryfikacja tylko do odczytu"}
        _state[name]=state
    if name=="CLOUD":
        try:
            result=asyncio.run(_cloud_lua())
            with _lock:
                state.update(status="received",values=result,
                    note="Odczytano metadane/chmurowy profil, NIE biezaca telemetrie")
        except Exception as err:
            with _lock:
                state.update(status="error",note=f"Cloud Lua: {type(err).__name__}" + (f": {err}" if type(err) is ValueError else " (szczegoly chmury ukryte)"))
        return "Cloud Lua: sprawdzono"
    if name=="PUSH":
        return "Nasluch 04-05 aktywny na 360 s; bez wysylania polecen"
    # Single bounded, documented QUERY only. Never SET/CONTROL (0x02).
    from midealan.message import MessageQuestCustom
    query=MessageQuestCustom(DeviceType.C3, dev._message_protocol_version,
                              MessageType.query, bytearray(method["query"]))
    try:
        dev.build_send(query, query=False)
    except Exception as err:
        with _lock:
            state.update(status="error",note=f"{type(err).__name__} przy wysylaniu")
        raise
    return f"Wyslano 1x QUERY 03: {method['title']}"


async def _cloud_lua():
    from midealan.cloud import get_midea_cloud
    with _lock:
        cloud_name, username, password, appliance_id=_creds
    async with aiohttp.ClientSession() as client:
        cloud=get_midea_cloud(cloud_name,client,username,password)
        if not await cloud.login():
            raise ValueError("Login odrzucony")
        appliances=await cloud.list_appliances(home_id=None) or {}
        dev=appliances.get(appliance_id)
        if not dev:
            matches=[x for x in appliances.values() if int(x.get("type") or 0)==0xC3]
            if len(matches)!=1:
                raise ValueError("Brak jednoznacznej pompy C3 na koncie")
            dev=matches[0]
        sn=dev.get("sn") or ""
        if not sn or len(sn)<8:
            raise ValueError("Brak numeru seryjnego z chmury")
        with tempfile.TemporaryDirectory(prefix="midea-lua-") as folder:
            result=await cloud.download_lua(folder,0xC3,sn,
                  model_number=dev.get("model"),
                  manufacturer_code=dev.get("manufacturer_code") or "0000")
            if not result:
                raise ValueError("Chmura nie zwrocila Lua dla modelu")
            if not os.path.isfile(result):
                raise ValueError("Odpowiedz chmury nie jest plikiem")
            with open(result,"rb") as infile:
                data=infile.read(2*1024*1024+1)
            if len(data)>2*1024*1024:
                raise ValueError("Lua jest za duzy")
        # No serial, full Lua or cloud credentials in exported diagnostics.
        return {"luaReceived":True,"luaSize":len(data),
                "luaSha256":hashlib.sha256(data).hexdigest(),
                "model":str(dev.get("sn8") or dev.get("model") or "")[:16]}


def snapshot():
    now=time.monotonic()
    with _lock:
        for name, state in _state.items():
            if state.get("status")=="waiting" and now>=state["_deadline"]:
                state["status"] = "no_response"
                state["note"] = ("Nie zaobserwowano 04-05 w 360 s"
                    if name=="PUSH" else "Brak pasujacej odpowiedzi w oknie czasowym")
        result={}
        for name,state in _state.items():
            result[name]={k:v for k,v in state.items() if not k.startswith("_")}
        return json.dumps({"tests":result, "recentFrames":list(_inbox)[-30:],
                           "upstreamUnmodified":True},ensure_ascii=False)
