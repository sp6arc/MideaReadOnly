# Midea ReadOnly 0.2.0 — poprawki po teście na Midea M-Thermal R32

Data: 29.10.2026. Poprzednia wersja 0.1.0 pozwoliła na zalogowanie do Midea SmartHome i otrzymała odpowiedź `status/lua/get` z 48 polami. Eksport zawierał `values: {}` pomimo udanego żądania; oddzielne zapytanie X10 dostało kod chmury 9999. Zachowujemy ten sam bezpośredni transport SmartHome przez fabryczne Wi-Fi, bez Home Assistant, Modbus i zewnętrznej bramki.

## Naprawione w kodzie

1. Usunięto `WindowManager.LayoutParams.FLAG_SECURE` i dodano `window.clearFlags(...)` — zrzuty ekranu są dozwolone.
2. Status chmury jest przechowywany jako kompletne, zagnieżdżone drzewo JSON z redakcją potencjalnie wrażliwych identyfikatorów. Wszystkie pola mają pokazane nazwy i wartości, także nierozpoznane.
3. Na ekranie jest liczba wszystkich pól, liczba rozpoznanych, nierozpoznanych i ukrytych. Pola rozpoznane mają etykiety surowych odczytów; bez dowolnego przeliczania na °C, Hz, bar lub MPa.
4. Eksport zawiera `values` (kompletny oczyszczony JSON), `fields` (ścieżki, wartości, rozpoznanie) i informacje o źródle oraz ostatnim błędzie. Brakujące pola nie są wstawiane jako zero.
5. Błąd 9999 z `/v1/appliance/transparent/send` jest pokazywany wraz z endpointem i czasem; **nie jest klasyfikowany jako alarm pompy**.
6. Nieudany odczyt X10 nie wymazuje wcześniejszego statusu Lua. Utrzymano ręczne odświeżanie i minimum 60 s pomiędzy zapytaniami tego samego typu.
7. Jeśli serwer zwróci prawidłową odpowiedź binarną X10, udostępniamy jej surowy hex; na profilu innym niż 171H120F nie używamy niepotwierdzonych offsetów.

## Istotne ograniczenia

- Kod 9999 jest odpowiedzią chmury i nie można go usunąć samą zmianą UI. Nie ma potwierdzenia, że konto SmartHome może pobrać dane LetsLink (Hz, ciśnienia, temperatury wymienników).
- Odczyt 48 pól jest zgłoszeniem z poprzedniego testu użytkowniczki; nie dysponujemy ich **oryginalną zawartością**, ponieważ 0.1.0 eksportowała wyłącznie odfiltrowany, pusty obiekt.
- 0.2.0 nie może odtworzyć wcześniejszych 48 wartości. Trzeba wykonać nowy odczyt statusu i wyeksportować JSON.
- Dane w czasie rzeczywistym nie są gwarantowane: `fetchedAt` oznacza moment pobrania, a nie pomiaru.
- Implementacja celowo nie wysyła komend zmieniających stan lub nastawy.

## Jak sprawdzić aplikację

1. Zainstaluj APK **zbudowany z kodu 0.2.0**. Nie instaluj starego `MideaReadOnly-test.apk` z 0.1.0 w przekonaniu, że ma poprawki.
2. Zaloguj się do SmartHome, wybierz M-Thermal, kliknij `1. Pobierz status chmury`. Sprawdź liczbę odebranych pól oraz widoczne wartości. Zrób screenshot.
3. Kliknij `Zapisz PEŁNY raport diagnostyczny JSON`. Raport powinien zawierać obiekt `values` z odebranymi polami i tablicę `fields` ze ścieżkami.
4. Dopiero potem kliknij `2. Sprawdź diagnostykę X10`. Jeśli pojawi się 9999, poprzednie pola powinny pozostać widoczne, a eksport powinien zawierać `lastCloudError:9999` oraz `errorEndpoint`.
5. Wyeksportuj raport i porównaj część nazw z rzeczywistym ekranem HMI. Dopiero wtedy dodajemy sprawdzone mapowania, nie zgadujemy jednostek.

## Budowa

Android Studio (JDK 17, Android SDK Platform 35); import folderu `MideaReadOnly`, następnie `Build > Build APK(s)`. Albo `./gradlew testDebugUnitTest assembleDebug`. Pakiet: `pl.zuza.midea.readonly`, `versionCode=2`, `versionName=0.2.0-raw-fields`. Klucz i podpis testowego APK w kolejnych kompilacjach mogą różnić się od starego APK, co wymaga odinstalowania poprzedniego wydania.

## Stan sprawdzenia w tym środowisku

Kod 0.2.0 pomyślnie przeszedł kompilację składniową Kotlin/JVM 1.9 ze stubami Android/JSON (nie zastępują Android SDK) oraz testy dymne: 48 nierozpoznanych pól, zagnieżdżone wartości, rozpoznawanie nazw, redakcja pól prywatnych, raport z kodem 9999. Brak tu Android SDK, build-tools i Gradle, więc nie przygotowano ani nie przetestowano instalowalnego APK 0.2.0 w emulatorze/telefonie.
