# Midea M-Thermal R32 — weryfikacja wykonalności

Stan analizy: 29 października 2026. Analiza kodu publicznego, bez konta i bez połączenia z pompą użytkowniczki.

## Wniosek

**Samodzielny klient Kotlin → chmura SmartHome → fabryczne Wi-Fi jest wykonalny. Pobieranie pełnej diagnostyki konkretnej M-Thermal R32 nie zostało jeszcze potwierdzone.**

Nie należy utożsamiać obsługi typu 0xC3 z obsługą każdego modelu R32. Sam czynnik R32 nie określa protokołu, podtypu sterownika, SN8 ani firmware. Nie należy też utożsamiać danych LetsLink z dostępnością tych samych danych na konsumenckim koncie SmartHome.

## Porównanie trzech projektów

| Projekt | Co faktycznie implementuje | Znaczenie dla aplikacji |
|---|---|---|
| sususweet/midea_auto_cloud | Logowanie SmartHome z routingiem regionu, podpisy HMAC, szyfrowanie, lista urządzeń, serwerowy odczyt Lua, pobranie protokołu, transparent relay | Najlepszy punkt wyjścia dla bezpośredniego odczytu chmurowego; framework HA nie jest potrzebny w Kotlin |
| xelhark/midea-cloud-relay | Usługa udająca lokalne urządzenie V2, przekazująca ramki do chmury przez transparent/send | Przydatny format pakietu i sesji; nie uruchamiamy serwera, Dockera ani emulacji LAN |
| wuwentao/midea-lan | Lokalny transport i parsery C3, w tym X10, powiadomienia X05 i warianty zależne od modelu | Źródło wiedzy o ramkach i polach; działanie LAN nie dowodzi dostępności tych ramek w chmurze |

### midea_auto_cloud

Przeanalizowany commit: `eb1d710243891f8f1eaf4cdd0f3fcd614a8e65d7`.

Istotne pliki:
- `custom_components/midea_auto_cloud/core/cloud.py`: klasa MSmartHomeCloud, `_re_route`, `login`, `list_appliances`, `get_device_status`, `send_cloud`.
- `core/security.py`: MSmartCloudSecurity, haszowanie haseł, HMAC i klucze AES.
- `core/device.py`: `refresh_status`, obsługa wyniku statusu i błędów Lua.
- `device_mapping/T0xC3.py`: profile C3.

Odczyt `/v1/device/status/lua/get` wysyła `command: {query: {}}` oraz identyfikatory modelu i zaszyfrowany numer seryjny. Dekodowanie odbywa się po stronie chmury — nie trzeba uruchamiać Lua na Androidzie.

Profile domyślny oraz 17100003 stosują pusty query. W sprawdzonym mapowaniu nie ma dowodu obsługi ciśnień czy częstotliwości sprężarki. Wspierane są m.in. `t4` i `tank_actual_temp`. Zapytanie `query_type: 2` należy do profilu basenowego 171000AU / subtype 513, dlatego NIE przeniesiono go automatycznie do M-Thermal.

Integracja potrafi otrzymać więcej pól niż mapuje na encje, ale ich zestaw musi zostać sprawdzony na konkretnym koncie i urządzeniu. Istnieją błędy 1014 (Lua analysis) oraz przypadki braku odpowiedzi asynchronicznej.

### midea-cloud-relay

Przeanalizowany commit: `90dafea030ac594a0ef033b1fad27ca7e194d30e`.

Przejrzano README i `relay.py`, szczególnie RelayCloud.login, `_build_relay_packet` i `_relay_send`. Transport:
1. Ramka C3 jest umieszczana w pakiecie 5A5A z ID urządzenia i sumą MD5.
2. Pakiet staje się tekstem CSV bajtów, szyfrowanym AES-CBC kluczami sesji.
3. Chmura otrzymuje `applianceCode` i `order` na `/v1/appliance/transparent/send`.
4. Odpowiedź jest odszyfrowywana i rozpakowywana.

Autor wprost opisuje błąd **3176 — async reply does not exist** dla zapytania o parametry jednostki. To istotna przeszkoda dla danych priorytetowych. Nie znaleziono w tym repo implementacji subskrypcji push, która usuwałaby ten problem. Sama nazwa endpointu „send” nie oznacza zapisu nastaw: znaczenie określa wysłana ramka. Aplikacja dopuszcza tylko stałą ramkę query X10.

### midea-lan

Przeanalizowany commit: `898c12d46256ac3a6006a5e7c58d130caf06945c`.

Przejrzano `midealan/devices/c3/message.py`, `__init__.py`, bazowy `midealan/message.py` i testy `tests/devices/c3/message_c3_test.py`.

Parser X10 zawiera `comp_run_freq`, `odu_target_fre`, `odu_comp_current`, `temp_t2`, `temp_t2b`, `temp_t3`, `temp_tp`, `temp_th`, `pressure_high`, `pressure_low`. Istnieje też inny układ push X05 i warianty X00; nie wolno stosować do nich offsetów X10. Część testów odnosi się do fizycznych odczytów i profilu 171H120F, ale po lokalnym transporcie.

Pierwszy dekoder aplikacji ograniczono do X10 i profilu 171H120F. Pokazuje surowe wartości; jednostki/skale, sentinel i kodowanie temperatur ujemnych należy zweryfikować dla konkretnego firmware przed interpretacją fizyczną. W szczególności nie oznaczamy surowego ciśnienia jako bar ani MPa.

## Macierz dowodów

| Dane | Parser w midea-lan | Dowód odczytu przez chmurę dla pompy użytkowniczki |
|---|---|---|
| Temperatura zewnętrzna / zbiornika | Tak; istnieją również mapowania statusu chmurowego | Brak testu live |
| Częstotliwość i prąd sprężarki | Tak, X10 / niektóre powiadomienia | Niepotwierdzone; relay może zwrócić 3176 |
| T2, T2B, T3, TP, TH | Tak, zależnie od profilu | Niepotwierdzone |
| Ciśnienie wysokie / niskie | Tak, zależnie od profilu | Niepotwierdzone, również skala wymaga weryfikacji |
| Dostęp asynchroniczny identyczny z LetsLink | Nie ustalono w tych trzech projektach | Niepotwierdzony |

## Co realizuje projekt Kotlin

- Bezpośrednie HTTPS SmartHome, regionalny routing tylko w domenie appsmb.com; brak automatycznego podążania za przekierowaniami.
- Logowanie, lista C3, odczyt domyślnego statusu Lua.
- Osobny, ręczny test zapytania X10; brak arbitralnych komend.
- Walidacja pakietu, długości, ID pompy, sumy MD5 oraz ramki C3 i jej sumy kontrolnej.
- Jawne rozróżnienie braku danych od liczbowego zera.
- Minimum 60 sekund między powtórzeniami danego rodzaju odczytu; brak automatycznych prób w pętli.
- Sesja tylko w RAM, brak zapisu hasła i logowania odpowiedzi z danymi konta.
- Raport JSON przez systemowy wybór pliku, z listą dozwolonych pól i bez sekretów.

## Co wymaga dalszego testu lub implementacji

Nie wykonano logowania na koncie użytkowniczki ani odczytu jej pompy. Nie zaimplementowano push/MQTT, pobierania i uruchamiania Lua, zapytań dowolnych, harmonogramu w tle, historii ani automatycznego odnawiania sesji. Nie obsługujemy LetsLink, captcha/SSO ani hostów regionów spoza appsmb.com. Błąd 3176 nie uruchamia obchodzenia zabezpieczeń ani przejścia na sterowanie.

Jeżeli chmura nie zwraca priorytetowych danych ani przez status Lua, ani przez X10, w ramach tych implementacji nie ma jeszcze potwierdzonej drogi do pełnego monitorowania. Kolejnym krokiem jest zidentyfikowanie modelowego protokołu/push SmartHome, a nie obietnica że istniejący parser rozwiązuje problem.

## Źródła

- https://github.com/sususweet/midea_auto_cloud/tree/eb1d710243891f8f1eaf4cdd0f3fcd614a8e65d7
- https://github.com/xelhark/midea-cloud-relay/tree/90dafea030ac594a0ef033b1fad27ca7e194d30e
- https://github.com/wuwentao/midea-lan/tree/898c12d46256ac3a6006a5e7c58d130caf06945c
- https://github.com/sususweet/midea_auto_cloud/issues/147 — przykład konieczności właściwego mapowania temperatur C3.
- https://github.com/mill1000/midea-ac-py/pull/417 — opis testów relay i ograniczeń odpowiedzi asynchronicznych.
