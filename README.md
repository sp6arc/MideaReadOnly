# MideaRemonte 2.1.0 – 9 OSOBNYCH KART DIAGNOSTYCZNYCH

Dostarczamy oryginalny, NIEMODYFIKOWANY pakiet Python `midealan` z repozytorium
https://github.com/wuwentao/midea-lan

Punkt kontrolny upstream:
`061df03f27a409bddaa5f5f50e371de5c00098b5` (2026-10-30)

CI pobiera ten konkretny commit, kopiuje `midealan/` bez zmian i pakuje przez
Chaquopy 17.2.0/Python 3.12. `app/src/main/python/midea_bridge.py` wyłącznie
wywołuje oryginalne publiczne metody pakietu i konwertuje wyniki do JSON;
`MainActivity.kt` tylko pokazuje wartości.

Wykorzystywane metody autora:
- `midealan.discover.discover` – UDP bez własnych ramek
- `midealan.cloud.get_midea_cloud`, `login`, `get_cloud_keys`
- `midealan.devices.device_selector`
- `MideaDevice.connect(check_protocol=True), potem MideaDevice.open` -> `run` -> `connect(check_protocol=True)`
- `refresh_status(True)`, oryginalna kolejność zapytań, timeouts, heartbeat,
  ponowne połączenie, oryginalne dekodery C3/X10/X00.

W wersji 2.1.0 dodano eksperymentalne osobne karty diagnostyczne:
- X10: standard 03-10
- 04-05: pasywny nasłuch 360 s
- ITS/BTRI: pojedyncze 03-02-01
- Strony 2F10, 2F12: pojedyncze 03-00-2F10/2F12
- X08, X0A, X0D: osobne zapytania odczytowe
- Cloud Lua: sprawdzenie i pobranie profilu Lua z konta, niezaleznie od LAN (NIE jest live telemetry).
Testy tylko wysylają MessageType.query 0x03, bez zadnego 0x02 SET.
Nie uzywaja nowych tokenow, modyfikacji konfiguracji, anonimowych
skanow ID lub losowego brute force. Oryginalny pakiet Python pozostaje
niemodyfikowany. Odpowiedzi rozpoznaje adapter Python obserwujacy
zdekodowane ramki AA przed przekazaniem do oryginalnego dekodera.
Logi nie zawieraja numerow seryjnych ani pelnych ramek.

W aplikacji pokazujemy wyłącznie atrybuty zarejestrowane przez prawdziwe
odpowiedzi/notify. Żadnych sztucznie wypełnianych pól.

## Budowanie
`./scripts/fetch-upstream.sh`, potem `./gradlew :app:testDebugUnitTest :app:assembleDebug`
przy zainstalowanym Android SDK 35, Python 3.12 i JDK 17.
GitHub Actions wykonuje wszystko automatycznie.

## Ograniczenia
APK nie jest dowodem na kompatybilność firmware 171H120F z X10.
Zgłoszenie autora: https://github.com/wuwentao/midea_ac_lan/issues/1137
Wystąpienie tego samego błędu na niezmienionym silniku Python jest istotnym
wynikiem diagnostycznym, a nie dowodem na brak telemetrii w LetsLink.

Implementacja Android zachowuje WYŁĄCZNIE funkcje odczytowe w UI.
Pakiet autora zawiera również metody zapisu/sterowania, ale nie są one
wywoływane. Upstream nie jest biblioteką serwisową LetsLink.
