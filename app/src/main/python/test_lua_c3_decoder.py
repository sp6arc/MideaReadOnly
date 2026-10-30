"""Deterministic offline checks for owner-supplied Lua byte mappings."""
import unittest
from lua_c3_decoder import decode_frame, SOURCE_LUA_SHA256


def frame(msg_type, sub, last_body_index):
    raw = bytearray(12 + last_body_index)
    raw[0], raw[1], raw[2] = 0xAA, len(raw) - 1, 0xC3
    raw[9], raw[10] = msg_type, sub
    return raw


def set_uint(raw, offset, value, size=1):
    raw[10 + offset:10 + offset + size] = value.to_bytes(size, "big")


class LuaDecodeTests(unittest.TestCase):
    def test_source_digest(self):
        self.assertEqual(len(SOURCE_LUA_SHA256), 64)

    def test_04_04_short_live_171H120F(self):
        raw = frame(4, 4, 14)
        set_uint(raw, 1, 1)
        set_uint(raw, 2, 43712, 4)
        set_uint(raw, 6, 84941, 4)
        set_uint(raw, 10, 0xFA)
        set_uint(raw, 11, 40)
        set_uint(raw, 13, 48)
        set_uint(raw, 14, 46)
        result = decode_frame(raw)
        self.assertEqual(result["family"], "04-04")
        self.assertTrue(result["lengthMatches"])
        self.assertEqual(result["values"]["totalelectricity0"], 43712)
        self.assertEqual(result["values"]["totalthermal0"], 84941)
        self.assertEqual(result["values"]["t4"], -6)
        self.assertEqual(result["values"]["tas"], 46)  # Lua does NOT scale this
        self.assertTrue(result["values"]["isheatrun0"])
        self.assertNotIn("isonline0", result["values"])
        self.assertNotIn("compRunFreq", result["values"])

    def test_04_04_full_multizone(self):
        raw = frame(4, 4, 153)
        set_uint(raw, 17, 0x03)
        set_uint(raw, 19, 0x05)
        set_uint(raw, 20, 501, 4)
        set_uint(raw, 24, 999, 4)
        r=decode_frame(raw)["values"]
        self.assertTrue(r["isonline0"])
        self.assertTrue(r["isonline1"])
        self.assertFalse(r["isonline2"])
        self.assertTrue(r["isheatrun1"])
        self.assertTrue(r["isdhwrun1"])
        self.assertEqual(r["totalelectricity1"], 501)
        self.assertEqual(r["totalthermal1"], 999)

    def test_04_05_full_service_and_energy(self):
        raw = frame(4, 5, 237)
        set_uint(raw,1,42)
        set_uint(raw,2,75)
        set_uint(raw,3,253)
        set_uint(raw,17,1234,2)
        set_uint(raw,19,450,2)
        set_uint(raw,26,345,2)
        set_uint(raw,65,456,2)
        set_uint(raw,67,317,2)
        set_uint(raw,149,3)
        set_uint(raw,236,412,2)
        r=decode_frame(raw)
        self.assertTrue(r["lengthMatches"])
        self.assertEqual(r["length"],249)
        v=r["values"]
        self.assertEqual(v["compRunFreq"],42)
        self.assertEqual(v["fanSpeed"],750)
        self.assertEqual(v["tempT3"],-3)
        self.assertEqual(v["pressureHigh"],1234)
        self.assertEqual(v["pressureLow"],450)
        self.assertEqual(v["waterFlow"],345)
        self.assertEqual(v["SysInstantPower"],456)
        self.assertEqual(v["SysInstantCopEER"],317)
        self.assertTrue(v["HMIEnergyAnaSetEN"])
        self.assertEqual(v["SysDhwYearCOPEER"],412)
        self.assertGreaterEqual(r["decodedCount"],100)

    def test_03_10_never_exposes_serial(self):
        raw=frame(3,16,191)
        set_uint(raw,1,61)
        set_uint(raw,29,2)
        set_uint(raw,43,321,2)
        set_uint(raw,96,ord("X"))
        r=decode_frame(raw)
        self.assertEqual(r["values"]["compRunFreq"],61)
        self.assertTrue(r["values"]["fgDefrost"])
        self.assertEqual(r["values"]["pressureHigh"],321)
        self.assertFalse(any("SNCode" in k for k in r["values"]))

    def test_unknown_and_short_frames(self):
        self.assertIsNone(decode_frame(bytes([0xAA,0x10,0xC3])))
        raw=frame(4,8,14)
        self.assertIsNone(decode_frame(raw))
        self.assertIsNone(decode_frame(bytes([0] * 20)))


if __name__ == "__main__":
    unittest.main()
