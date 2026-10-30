# MideaRemonte 2.2.0 — tylko działające protokoły

Projekt Android do monitorowania Midea M-Thermal R32, urządzenie typu C3,
moduł Wi-Fi 171H120F. Zbudowany z oryginalnej, niemodyfikowanej biblioteki
[wuwentao/midea-lan](https://github.com/wuwentao/midea-lan),
commit `061df03f27a409bddaa5f5f50e371de5c00098b5`.

## Co odczytujemy

- X01 (03-01): cyklicznie ustawienia stref, CWU, stan włączenia, tryby i błędy
- X05 (03-05): cyklicznie tryb cichy
- X07 (03-07): cyklicznie ECO
- 04-04 (spontaniczny raport): stan ogrzewania, temperatura zewnętrzna,
  sumaryczne liczniki energii — **nie musi przychodzić cyklicznie**.
- Cloud Lua: pobranie od Midea pliku protokołu dla przypisanego C3,
  tylko metadane długość/SHA256/model, bez pobierania temperatur w chmurze.

## Wyłączone

Znane nieodpowiadające X09 (dezynfekcja) i X10 (UnitPara) **nie są wysyłane**
ani przy wstępnej autoryzacji, ani w odświeżaniu cyklicznym. Filtr
`diagnostic_bridge.attach()` ogranicza `device.build_query()` do klas
`MessageQueryBasic`, `MessageQuerySilence` i `MessageQueryECO`.
Oryginalny `midealan/` pozostaje bez zmian (szyfrowanie, autoryzacja
V3, dekodowanie, reconnect, heartbeat). Zapytanie identyfikacji urządzenia
podczas handshaku pozostaje aktywne.

Z aplikacji usunięto: 03-02-01 (ITS), strony 2F10/2F12, X08/X0A/X0D,
ręczny X10 i 6-minutowy nasłuch diagnostyczny 04-05.
Nie ma funkcji SET ani zmian konfiguracji urządzenia.

## Gromadzenie danych

W widoku głównym kafelki z ostatnimi odczytami i wiekiem ramki.
Dane z 04-04 są oznaczone jako potencjalnie nieaktualne. Na ekranie
widać liczniki i ostatni czas ramek 03-01/03-05/03-07/04-04.

Eksport JSON zawiera:
- ostatnie potwierdzone wartości i osobne znaczniki `updatesAt`;
- do 800 ostatnich próbek dekodowanych odpowiedzi, z czasem i typem ramki;
- statystyki ramek i metadane profilu Lua, jeśli odczytany;
- brak haseł, tokenów, numerów seryjnych i pełnych ramek.

Liczniki energii są przedstawiane **bez jednostki**, dopóki nie
potwierdzimy skali i poprawności. `status_heating` to ostatni raport
04-04, a nie dowód bieżącej pracy sprężarki.

## Instalacja i budowa

Testowe APK ma `applicationId=pl.zuza.midearemote.diagnostics`
i aktualizuje MideaRemonte TESTY 2.1.0; może współistnieć
z MideaRemonte 2.0.1. Nie uruchamiaj obu monitorów naraz.

```sh
bash scripts/fetch-upstream.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

W GitHub Actions wymagane są JDK 17, Python 3.12, Android SDK 35,
Gradle i Chaquopy.
