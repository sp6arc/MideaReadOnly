# Midea ReadOnly 0.5.0 — Android Kotlin

Read-only Midea M-Thermal R32 (C3) monitoring through Midea SmartHome cloud **and**, independently, the factory Wi-Fi module on the same LAN.

Five screens: Panel, Chmura, Wi-Fi, Porównaj, Opcje. Supports encrypted local login persistence, C3 network discovery (UDP), private-IP manual connection, LAN V2/V3 read-only diagnostics and cloud-vs-LAN comparison. No Home Assistant, Modbus or extra hardware.

LAN telemetry is experimental, not confirmed against the user's specific physical device. TLS V4 and some Midea LAN auth restrictions may prevent diagnostic readings. Unknown values are never substituted or mislabelled. See `docs/ZMIANY_0.5_PL.md`.

Build APK: GitHub Actions workflow `build-android-apk.yml` or `./gradlew :app:assembleDebug` with Android SDK 35 and Java 17.
