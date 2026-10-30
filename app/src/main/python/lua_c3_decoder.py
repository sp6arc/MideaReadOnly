"""Read-only C3 telemetry decoder derived from the owner's 171H120F Lua.

Source: Midea cloud Lua (JSON_VERSION=20, last update 2023-05-06).
Lua dataToJson: 04-04 lines 3796-3965; 03-10 lines 4204-4426;
04-05 lines 4428-4629. Indexes are BODY-relative, with subtype at 0.
No network calls, Lua execution, SET commands or guessed engineering scales.
"""
from datetime import datetime, timezone

SOURCE_LUA_SHA256 = "893adfc1122aff7eb58bcec21273c3719d68d60e5539a1f9cc3fc9be5722b0f5"
SUPPORTED = frozenset(("04-04", "04-05", "03-10"))
SIGNED_TEMPERATURES = frozenset((
    "t4", "tempT3", "tempT4", "tempTp", "tempTwin", "tempTwout",
    "tempT1", "tempTw2", "tempT2", "tempT2b", "tempT5",
    "tempTa", "tempTh", "tank_actual_temp"
))


def decode_frame(frame):
    """Returns only fields present in the received M-Smart frame.

    Transport/protocol validation is owned by original midealan. This
    independent parser reports length discrepancies without claiming the
    frame is valid; it never affects the upstream connection.
    """
    if not isinstance(frame, (bytes, bytearray)) or len(frame) < 13:
        return None
    if frame[0] != 0xAA or frame[2] != 0xC3:
        return None
    family = "%02X-%02X" % (frame[9], frame[10])
    if family not in SUPPORTED:
        return None

    data = {}
    def read(name, offset, width=1, signed=False, multiplier=1):
        start = 10 + offset
        end = start + width
        # Do not read the trailing M-Smart checksum as a sensor byte.
        if offset < 1 or end > len(frame) - 1:
            return
        value = int.from_bytes(frame[start:end], "big", signed=False)
        if signed and width == 1 and value > 127:
            value -= 256
        data[name] = value * multiplier

    def byte(name, offset):
        read(name, offset, signed=name in SIGNED_TEMPERATURES)

    def flags(offset, fields):
        start = 10 + offset
        if start >= len(frame) - 1:
            return
        number = frame[start]
        for bit_index, name in enumerate(fields):
            if name:
                data[name] = bool(number & (1 << bit_index))

    if family == "04-04":
        flags(1, ("isheatrun0", "iscoolrun0", "isdhwrun0", "istbhrun0",
                  "isibhrun0", "issmartgrid0", "ishighprices0", "isbottomprices0"))
        read("totalelectricity0", 2, 4)
        read("totalthermal0", 6, 4)
        for name, pos in (("t4", 10), ("zone1_temp_set", 11),
                          ("zone2_temp_set", 12), ("t5s", 13), ("tas", 14),
                          ("newt1s1", 15), ("newt1s2", 16)):
            byte(name, pos)
        for unit in range(16):
            flags(17 + unit // 8, tuple(
                ("isonline%d" % (unit - unit % 8 + bit)) if
                (unit - unit % 8 + bit) < 16 else None for bit in range(8)
            )) if unit % 8 == 0 else None
        for unit in range(1, 16):
            offset = 19 + 9 * (unit - 1)
            flags(offset, ("isheatrun%d" % unit, "iscoolrun%d" % unit,
                           "isdhwrun%d" % unit, "istbhrun%d" % unit,
                           "isibhrun%d" % unit))
            read("totalelectricity%d" % unit, offset + 1, 4)
            read("totalthermal%d" % unit, offset + 5, 4)

    elif family == "04-05":
        for name, offset in (
            ("compRunFreq",1), ("tempT3",3), ("tempT4",4),
            ("tempTp",5), ("tempTwin",6), ("tempTwout",7),
            ("oduCompCurrent",8), ("tempT1",11), ("tempTw2",12),
            ("tempT2",13), ("tempT2b",14), ("tempT5",15),
            ("tempTa",16), ("tempTh",21), ("oduTargetFre",22),
            ("tempTf",23), ("iduT1s1",24), ("iduT1s2",25),
            ("roomRelHum",32), ("pwmPumpOut",59), ("unitModeRun",60)
        ):
            byte(name, offset)
        read("fanSpeed",2,multiplier=10)  # exactly as Midea Lua
        for name, offset in (
            ("oduVoltage",9), ("pressureHigh",17), ("pressureLow",19),
            ("waterFlow",26), ("currentUnitCapacity",28), ("waterPressure",30),
            ("SysHeatDayCapacity",41), ("SysHeatDayRenewPower",43),
            ("SysHeatDayElecConsum",45), ("SysHeatDayCOPEER",47),
            ("instantPower0",49), ("instantRenewPower0",51),
            ("compRunTotalTime0",57), ("SysInstantHPCapacity",61),
            ("SysInstantRenewPower",63), ("SysInstantPower",65),
            ("SysInstantCopEER",67), ("SysTotalCOPEER",81),
            ("SysHeatInsHPCapacity",83), ("SysHeatInsRenewPower",85),
            ("SysHeatInsPower",87), ("SysHeatInsCopEER",89),
            ("SysHeatCOPEER",103), ("SysCoolInsHPCapacity",105),
            ("SysCoolInsRenewPower",107), ("SysCoolInsPower",109),
            ("SysCoolInsCopEER",111), ("SysCoolCOPEER",125),
            ("SysDhwInsHPCapacity",127), ("SysDhwInsRenewPower",129),
            ("SysDhwInsPower",131), ("SysDhwInsCopEER",133),
            ("SysDhwCOPEER",147),
        ):
            read(name, offset, 2)
        for name, offset in (
            ("totalelectricity0",33), ("totalthermal0",37),
            ("totalRenewPower0",53), ("SysTotalHPCapacity",69),
            ("SysTotalRenewPower",73), ("SysTotalPowerConsum",77),
            ("SysHeatCapacity",91), ("SysHeatRenewPower",95),
            ("SysHeatElecConsum",99), ("SysCoolCapacity",113),
            ("SysCoolRenewPower",117), ("SysCoolElecConsum",121),
            ("SysDhwCapacity",135), ("SysDhwRenewPower",139),
            ("SysDhwElecConsum",143),
        ):
            read(name, offset, 4)
        flags(149, ("SysEnergyAnaEN", "HMIEnergyAnaSetEN"))
        # 04-05: week/month/year for heat; day/week/month/year for
        # cooling and DHW. Four consecutive U16 values per period.
        for system, periods, start in (
            ("Heat", ("Week","Month","Year"), 150),
            ("Cool", ("Day","Week","Month","Year"), 174),
            ("Dhw", ("Day","Week","Month","Year"), 206)
        ):
            for period_index, period in enumerate(periods):
                base = start + period_index * 8
                for field_index, suffix in enumerate(
                    ("Capacity", "RenewPower", "ElecConsum", "COPEER")
                ):
                    read("Sys%s%s%s" % (system,period,suffix),
                         base + field_index * 2, 2)

    else:  # 03-10: passive only; NEVER export serial number regions 96..191.
        for name, offset in (
            ("compRunFreq",1), ("unitModeRun",2), ("machVersion",4),
            ("fgCapacityNeed",5), ("tempT3",7), ("tempT4",8),
            ("tempTp",9), ("tempTwin",10), ("tempTwout",11),
            ("tempTsolar",12), ("hydboxSubtype",13), ("p6ErrCode",16),
            ("oduCompCurrent",17), ("oduModel",22), ("currentCode",24),
            ("tempT1",34), ("tempTw2",35), ("tempT2",36),
            ("tempT2b",37), ("tempT5",38), ("tempTa",39),
            ("tempTbt1",40), ("tempTbt2",41), ("tempTh",47),
            ("oduTargetFre",49), ("dcCurrent",50), ("dcVoltage",51),
            ("tempTf",52), ("iduT1s1",53), ("iduT1s2",54),
            ("roomRelHum",64), ("pwmPumpOut",65),
        ):
            byte(name, offset)
        read("fanSpeed",3,multiplier=10)
        for name, offset in (
            ("oduVoltage",18), ("exvCurrent",20),
            ("pressureHigh",43), ("pressureLow",45), ("waterFlow",55),
            ("currentUnitCapacity",58), ("waterPressure",62),
            ("instantPower0",83), ("instantRenewPower0",85)
        ):
            read(name, offset, 2)
        for name, offset in (
            ("totalelectricity0",67), ("totalthermal0",71),
            ("heatElecTotConsum0",75), ("heatTotCapacity0",79),
            ("totalRenewPower0",87)
        ):
            read(name, offset, 4)
        flags(29, ( "fgIsSlaveUnit","fgDefrost","fgAntiFreezeRun","fgBackOil",
                    "fgRemoteOnOff","fgOutDoorTestMode","fgRoomTherHeatRun",
                    "fgRoomTherCoolRun"))
        flags(31, ("fgFactoryRun","fgFactReqSolarOn","fgFactReqTherCoolOn",
                   "fgCoolRun","fgHeatRun","fgDHWRun","fgFactReqTherHeatOn",
                   "fgEdgeVersionType"))
        flags(33, ("fgIBH1Output","fgIBH2Output","fgTBHOutput","fgPumpIOutput",
                   "fgSV1On","fgSV2On","fgPumpOOn","fgPumpDHWOn"))

    return {
        "family": family,
        "length": len(frame),
        "declaredLength": frame[1] + 1,
        "lengthMatches": frame[1] + 1 == len(frame),
        "values": data,
        "decodedCount": len(data),
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": "Midea cloud Lua JSON_VERSION=20",
        "unscaled": True,
    }
