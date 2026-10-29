# Midea ReadOnly 0.4.0

Samodzielna aplikacja Android (Kotlin) korzystająca z oryginalnego Wi-Fi i chmury Midea SmartHome; wyłącznie odczyt.

## Nowy panel

Karta startowa inspirowana stylistyką Midea, kafelki podobne do Home Assistant: ogrzewanie, CWU, obiegi, ustawienia komfortu. Zaawansowana diagnostyka w osobnej zakładce, bez ukrywania nierozpoznanych pól. Zachowane bezpieczne logowanie i wybór pompy.

[Opis zmian wersji 0.4.0](docs/ZMIANY_0.4_PL.md)

## Build APK

GitHub Actions → **Midea ReadOnly — budowanie APK** → [najświeższy przebieg](../../actions) → **Artifacts** → `MideaReadOnly-0.4.0-debug-APK`.

## Ograniczenia

- `run_mode_set=heat` = zadany tryb, nie pomiar pracy sprężarki.
- `tank_actual_temp` = podana temperatura zasobnika; `dhw_temp_set` = nastawa.
- `dhw_enable` != `dhw_power_state`.
- Błąd `9999` dotyczy chmury i zapytania X10, nie kodu alarmu pompy.
- Wartości °C oraz jednostki nie zostały niezależnie potwierdzone na HMI.
- Bez Home Assistant/Modbus/dodatkowych urządzeń; brak sterowania nastawami.

## Eksperymentalny odczyt Lua 0x10

W sekcji diagnostycznej dodano oddzielne zapytanie `POST /v1/device/status/lua/get` z
`command: {"query":{"query_type":"16"}}`. Konstrukcja wynika bezpośrednio z oryginalnego
Lua `T_0000_C3_171H120F_2023062601.lua` (`MSG_TYPE_QUERY_UNITPARA=0x10`).
Nie ma gwarancji, że SmartHome zwróci parametry serwisowe; brak wyników jest
ważną informacją diagnostyczną. Zachowujemy status podstawowy i całą odpowiedź Lua
w osobnych strukturach; eksport redaguje wrażliwe dane.

Źródło: https://github.com/wuwentao/midea-lua/blob/main/lua/0xC3/T_0000_C3_171H120F_2023062601.lua
