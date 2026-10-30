# MideaRemonte 2.3.0 — działające protokoły + eksport oryginalnego Lua

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
- Cloud Lua: pobranie od Midea pliku protokołu przypisanego do C3.
  Przycisk `Pobierz oryginalny plik Lua i ZAPISZ .lua` otwiera
  systemowy dokumentowy dialog Android i kopiuje *dokładnie oryginalne bajty*
  do wskazanego przez użytkownika folderu, np. Pobrane.
  Zapis jest weryfikowany przez SHA-256 zgodne z pobranym plikiem.
  Nie wykonujemy kodu Lua, nie dodajemy żadnych danych logowania.
  **Sam Lua może zawierać identyfikatory protokołu lub dane producenta**;
  przed publikacją publiczną warto go sprawdzić.
  Zachowany przycisk metadanych: rozmiar/SHA256/model.

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

APK 2.3.0 ma osobny `applicationId=pl.zuza.midearemote.luaexport`,
aby uniknąć konfliktów podpisu debugowego. Może współistnieć ze starą
MideaRemonte 2.0.1 i TESTY 2.1.0. Starszą TESTY można odinstalować;
nie uruchamiaj więcej niż jednego monitora naraz.

```sh
bash scripts/fetch-upstream.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

W GitHub Actions wymagane są JDK 17, Python 3.12, Android SDK 35,
Gradle i Chaquopy.
