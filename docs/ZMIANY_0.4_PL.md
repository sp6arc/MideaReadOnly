# Midea ReadOnly 0.4.0 — panel pompy na podstawie 48 pól

Zmiany w porównaniu z wersją 0.3.0:

- Panel w stylistyce Midea: niebieska karta główna, tryb pracy, okrągła wizualizacja **nastawy** temperatury obiegu 1 i status online z listy urządzeń.
- Podział na: **Pulpit**, **Diagnostyka**, **Ustawienia**.
- Oddzielne kafle CWU: rzeczywista temperatura zgłoszona przez SmartHome i zadana temperatura, z rozróżnieniem `dhw_enable` (funkcja dostępna) i `dhw_power_state` (włączona/wyłączona).
- Obieg 1 i 2: osobno włączenie oraz nastawa. `double_zone_enable=off` oznacza wyłączoną funkcję dwóch obiegów, więc zachowana wartość 46 obiegu 2 nie jest odczytem temperatury wody.
- Napis `OGRZEWANIE` oznacza **ustawiony tryb** (`run_mode_set=heat`), a nie dowód pracy sprężarki.
- `room_temp_set` jest nastawą, nie pomiarem temperatury pomieszczenia.
- Rozszerzenie opisów statusu o pola zgłoszone w raporcie z 29.10.2026; nie usuwa żadnych pozostałych pól.
- Diagnostyka X10 i błąd API `9999` przeniesione do sekcji technicznej.
- Przycisk ręcznego odświeżania z dotychczasowym limitem 60 sekund i eksport JSON.
- Zachowane zapamiętywanie logowania i pompy, bez Home Assistant, Modbus, LAN i poleceń sterujących.

## Co nadal nie jest dostępne w tym odczycie

Częstotliwość i prąd sprężarki, ciśnienia, T2/T2B/T3/TP/TH, przepływ wody i rzeczywista temperatura zasilania/powrotu. Samo `status/lua/get` nie zawiera tych pól.

Wartości wyświetlane jako °C odpowiadają polom temperatur wg semantyki Midea. Skala/jednostki muszą być skonfrontowane z fabrycznym HMI.

Dane prywatne i plik diagnostyczny nie są publikowane w repozytorium.

## Dodatkowe badanie z października 2026
- Opcjonalny test diagnostyki poprzez cloud Lua, query_type=16 (0x10) dla SN8 171H120F.
- Odpowiedź w osobnym panelu i raporcie JSON; żadnego sterowania urządzeniem.
- Zmieniono listę dozwolonych żądań, aby dopuszczała tylko query={} i query_type=16.
- Jednostki, skale i wartości wymagają weryfikacji na fabrycznym HMI.
