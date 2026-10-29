# Midea M-Thermal ReadOnly — wersja 0.3.0

Niezależna aplikacja Android / Kotlin do **odczytu** pompy Midea M-Thermal C3 przez chmurę SmartHome i fabryczny moduł Wi-Fi. Nie wymaga Home Assistant, Modbus, lokalnego API, własnego serwera ani dodatkowego sprzętu.

## Co zmieniono od 0.2

- Konto SmartHome i ID ostatnio wybranej pompy są opcjonalnie zapamiętywane. Opcja jest zaznaczona domyślnie.
- Hasło trafia na urządzenie **wyłącznie po udanym logowaniu**, zaszyfrowane symetrycznie kluczem AES-256-GCM w Android Keystore. Nie zapisujemy tokenów sesji ani kluczy komunikacji w preferencjach.
- Po uruchomieniu aplikacja odszyfrowuje lokalny zapis, ponownie loguje się do SmartHome, pobiera listę urządzeń i otwiera ostatnią pompę (jeśli nadal jest na koncie). Jeżeli jest tylko jedna pompa, wybierze ją automatycznie.
- Pierwszy odczyt statusu uruchamiany jest automatycznie po wybraniu pompy. Kolejne są ręczne, z ograniczeniem częstotliwości do jednego na minutę.
- Interfejs ma zakładki **Przegląd**, **Wszystkie dane**, **Ustawienia**. Przegląd grupuje rozpoznane pola w kafelkach na wzór Home Assistant; dane nierozpoznane pozostają widoczne na osobnej zakładce z wyszukiwarką.
- Rozszerzono słownik nazw parametrów C3 na podstawie opisów sensorów w projekcie wuwentao/midea_ac_lan. **To są etykiety kluczy, nie potwierdzona konwersja wartości z chmury.** Wartości są surowe, bez zgadywanych jednostek.
- Nadal możliwe są zrzuty ekranu, eksport JSON wszystkich pól (z redakcją danych prywatnych) i pojedynczy test X10. Błąd 9999 nie usuwa ostatniego udanego odczytu.

## Bezpieczeństwo i ograniczenia

- `AndroidManifest.xml` ma `allowBackup=false`, `usesCleartextTraffic=false`. Przechowywanie danych jest lokalne, w prywatnych preferencjach z szyfrowaniem Android Keystore.
- **Wyloguj i usuń zapisane dane** usuwa zaszyfrowany zapis oraz klucz Keystore.
- Korzysta tylko z listy dozwolonych punktów API w `ReadPolicy.kt`, kontrola modyfikacji stanu urządzenia pozostaje zablokowana.
- Kod chmury `9999` nie oznacza automatycznie alarmu ani usterki pompy.
- Udzielone nazwom pól etykiety (np. T2, T3, `comp_run_freq`) są odniesieniem do C3; konkretne wartości pompy i jednostki **muszą być porównane z fizycznym HMI**.
- Nie ma stałego odpytywania diagnostyki X10 i nie ma zdalnych komend sterujących.
- Opcjonalne zapamiętywanie hasła jest kompromisem wygody i bezpieczeństwa — przy niechęci do zapisu można odznaczyć pole i logować się każdorazowo.

## Zbudowanie APK

1. Otwórz katalog `MideaReadOnly` w Android Studio.
2. Użyj JDK 17 i zainstaluj Android SDK Platform 35.
3. Uruchom `./gradlew assembleDebug` lub `Build > Build APK(s)`.
4. Debug APK powinien pojawić się w `app/build/outputs/apk/debug/`.

Jeżeli wcześniej instalowane było APK podpisane **innym kluczem testowym**, Android może wymagać odinstalowania poprzedniej aplikacji przed instalacją nowego pakietu. Nie zmieniono `applicationId`: `pl.zuza.midea.readonly`.

## Weryfikacja

Źródła wersji 0.3 skompilowano lokalnie do JVM bytecode z testowymi interfejsami Android. **Nie zbudowano ani nie uruchomiono APK 0.3** w środowisku zadania (brak Android SDK i Android Gradle Plugin). Nie przeprowadzono live testu z kontem Midea i prawdziwą pompą. Więcej w `docs/WERYFIKACJA_0.3_PL.md`.

### Inspiracje i źródła nazw

- [Home Assistant — tile card](https://contributors.home-assistant.io/design/dashboards/tile-card/) — wzór prezentacji i grupowania kafelków, bez użycia HA w runtime.
- [wuwentao/midea_ac_lan/doc/C3.md](https://github.com/wuwentao/midea_ac_lan/blob/main/doc/C3.md) — nazwy odczytów C3 (nie silnik komunikacji chmurowej).
- [sususweet/midea_auto_cloud](https://github.com/sususweet/midea_auto_cloud) — inspiracja protokołu i modelu cloud, patrz `licenses/` i `NOTICE.md`.
- [xelhark/midea-cloud-relay](https://github.com/xelhark/midea-cloud-relay) — materiał porównawczy.


## Automatyczne budowanie APK

W projekcie znajduje się workflow GitHub Actions `.github/workflows/build-android-apk.yml`. Wymagane SDK pobierane jest na serwerze GitHub; po udanym przebiegu builda APK jest publikowany jako artefakt. Instrukcja: `docs/JAK_UTWORZYC_APK_PL.md`.


## 0.5.1: naprawa diagnostyki lokalnej

Po pierwszym rzeczywistym teście M-Thermal `171H120F` V3 wykazano poprawną odpowiedź C3 X01 (36 bajtów) mimo braku X10/X0A. Teraz aplikacja najpierw odpytuje X01, dekoduje wyłącznie potwierdzone pola podstawowe dla tego profilu, a następnie próbuje X10 i X0A z dłuższymi limitami czasu. Zachowuje do 88 bajtów każdej odpowiedzi C3 (serial w końcówce ramki jest pomijany), kody typu, długości i kontrolę sumy w eksporcie.

**Ważne:** sukces X01 nie jest sukcesem odczytu częstotliwości sprężarki i temperatur wymienników. Te wymagają X10 lub innego potwierdzonego komunikatu.
