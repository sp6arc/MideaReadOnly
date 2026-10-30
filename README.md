# MideaRemonte 1.0.0

Independent Android application identity: `pl.zuza.midearemote`; source on dedicated branch `midearemote-initial`.
Current MideaReadOnly on `main` is not modified.

## Features
- Midea C3 UDP discovery (send 6445/20086, listen 7083/15000), manual IP/device ID.
- SmartHome login, list user C3 devices, obtain V3 LAN token/key for device ID.
- Local TCP V2/V3, 8370 handshake, message and transport encryption, heartbeat, retries, partial frame buffering.
- C3 read-only A0, X01, X09, X05, X07, X10, selected X0D and X04/X05/X00 notifications.
- Model-gated 17100007 X00 decoder: 2010, 20B0, 2F10. Legacy X10 decoding for 171H120F.
- Raw shortened frame prefixes, plain all-field listing, diagnostic steps, JSON export.
- Cloud basic status, query_type=16 and transparent X10 read-only diagnostic.
- No controls, no Home Assistant/Modbus, no additional hardware.

## Important limitations
This is a focused Android port of C3 diagnostics, **not a full port** of every appliance and cloud from upstream wuwentao/midea-lan. The current cloud login is SmartHome. X00 notification subscriptions/push generation are firmware-dependent. The 17100007 decoder does not invent missing fields or unsupported queries. Installer APK is produced using GitHub Actions.

Upstream MIT sources: https://github.com/wuwentao/midea-lan (C3 message.py, security.py, device.py), https://github.com/wuwentao/midea-lua.
