# Midea ReadOnly 0.5.0 — chmura + Wi-Fi

Wersja integruje dotychczasowy dostęp przez chmurę Midea SmartHome z niezależnym połączeniem lokalnym z fabrycznym modułem Wi‑Fi pompy C3. **Nie wymaga Home Assistant, Modbus ani dodatkowego sprzętu.** Podtrzymano zapisane logowanie i wybór urządzenia, zrzuty ekranu, odczyt bez możliwości sterowania.

## Zakładki

- Panel: czytelny status SmartHome, CWU i obiegi grzewcze — nastawy nie są udawane jako rzeczywiste pomiary sprężarki.
- Chmura: wszystkie pola Lua i eksperymentalne zapytanie `query_type=16`, wraz z X10 przez `transparent/send`.
- Wi-Fi: wykrywanie Midea C3 UDP 6445/20086 (odpowiedzi mogą dotrzeć również przez 7083/15000), ręczne lokalne IPv4, diagnoza obsługi TCP V2/V3, odczyty tylko `X10`, `X0A`, `X01`.
- Porównaj: zestawienie wszystkich dostępnych odczytów z chmury i Wi-Fi. Nieznany pomiar pokazuje `—`, nigdy 0.
- Opcje: zapisane konto, wybrana pompa, eksport, wylogowanie.

## Zabezpieczenia

Połączenie lokalne dotyczy tylko prywatnego IPv4 i identyfikatora wybranej pompy. Chmurowe klucze V3 (token/key) są pobierane z `/v1/iot/secure/getToken` w trakcie testu, wykorzystywane tylko w RAM i nigdy nie są zapisywane do raportów. Wykorzystuje się niekontrolujące zapytania o stan C3; aplikacja nie wysyła komend ustawiania temperatury lub włączania urządzenia. Ograniczamy odpytywanie do co najmniej 30 sekund lokalnie oraz 60 sekund w chmurze.

## Ważne ograniczenia

Komunikacja lokalna jest eksperymentalna: urządzenie może odmówić autoryzacji V3, używać protokołu V4/TLS lub nie odpowiadać na X10. W takim przypadku aplikacja zapisze i pokaże etap niepowodzenia, ale nie wymyśli danych. Wykrycie urządzenia nie gwarantuje, że moduł przyjmuje starszy protokół TCP. Tylko fizyczny test w sieci użytkownika rozstrzygnie dostępność czujników.

Źródła protokołu: https://github.com/wuwentao/midea-lan (MIT), https://github.com/xelhark/midea-cloud-relay (MIT) oraz własne testy statusu SmartHome.
