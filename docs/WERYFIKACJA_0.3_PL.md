# Stan weryfikacji 0.3.0, 29 października 2026

## Potwierdzono

- Analiza istniejącego projektu Kotlin 0.2 i dwóch oryginalnych raportów diagnostycznych.
- Zachowano kompatybilny `CloudClient`, `C3` i `ReadPolicy` (wyłącznie ścieżki odczytu).
- Dodano szyfrowane przechowywanie konta, hasła i identyfikatora pompy przy użyciu Android Keystore AES-256-GCM.
- Dodano automatyczne logowanie i autowybór zapamiętanej pompy oraz automatyczny pierwszy odczyt statusu.
- Zaimplementowano trzy zakładki: Przegląd (kafelki C3), Wszystkie dane (wyszukiwarka i filtr), Ustawienia (pompa, konto, usuwanie zapisu).
- Zweryfikowano kompilację **wszystkich plików źródłowych Kotlin** przez `kotlinc 1.9.0 -jvm-target 17` z syntetycznymi interfejsami Java Android API. Wynik: brak błędów kompilacji; ostrzeżenia o deprecacji konstruktora URL i zbędnych rzutowaniach.
- Uruchomiono lokalny test JVM: zgodność słownika kluczy C3, wektor HMAC i integralność ramki X10 — **PASS**. Test nie dotyka chmury ani Android Keystore.

## Nie potwierdzono

- Nie uruchomiono oficjalnego kompilatora Android / Android SDK 35 w tym środowisku. Nie stworzono rzeczywistego APK 0.3.
- Kompilacja z atrapami API potwierdza spójność składniową, ale **nie zastępuje** pełnego Gradle Build, lint, uruchomienia aplikacji ani testów interfejsu na telefonie.
- Nie wykonano testu Android Keystore na fizycznym urządzeniu.
- Nie wykonano integracyjnego testu z rzeczywistym API Midea.
- Nie potwierdzono zgodności słownika parametrów C3 z polami zwracanymi przez status/lua/get na konkretnym urządzeniu.

## Plan testu na telefonie

1. Pierwsze uruchomienie: zaloguj się, pozostaw opcję Zapamiętaj, wybierz pompę.
2. Zamknij aplikację i uruchom ponownie — powinna zalogować się i wybrać tę samą pompę automatycznie.
3. Sprawdź wyświetlenie wszystkich pól, filtr nierozpoznanych i wyszukiwarkę.
4. Sprawdź eksport JSON i zrzut ekranu.
5. Wyloguj się — ponowne uruchomienie powinno wymagać logowania.
6. Porównaj `temp_t2`, `comp_run_freq` i `pressure_high` na panelu tylko gdy te klucze wystąpią. Nie interpretuj surowych skal bez sprawdzenia.
