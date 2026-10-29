# HISTORYCZNE WYNIKI 0.1.0 — nie dotyczą 0.2.0

Szczegóły nowych zmian i lokalnego testu źródeł: [ZMIANY_0.2_PL.md](ZMIANY_0.2_PL.md).

# Weryfikacja paczki — 29.10.2026

## Wykonano

- Kompilacja wszystkich źródeł aplikacji: Kotlin 2.0.21, JVM target 17, Android SDK 35 — OK.
- Pełne uruchomienie dołączonego `tools/build_manual.py` — OK.
- 9 testów JUnit 4.13.2 — wszystkie zakończone powodzeniem.
- Niezależne wektory haszowania i HMAC wyliczone Pythonem; blok AES porównany z wektorem NIST.
- Testy odmowy endpointów sterujących, niepustego query i każdej jednobajtowej modyfikacji dozwolonej ramki.
- Testy regionalnego routingu przeciwko nieautoryzowanym hostom i HTTP.
- Testy sum, ID urządzenia, uszkodzonych ramek i niewspieranego profilu.
- Test zachowania liczbowego 0 i braku danych oraz filtrowania sekretów z eksportu.
- Test integracyjny klienta z atrapą HTTPS: logowanie → lista urządzeń → status → błąd 3176.
- Kompilacja zasobów aapt2 i bytecode D8, wyrównanie zipalign — OK.
- Weryfikacja podpisu APK przez apksigner: poprawny podpis v2 i v3.
- Inspekcja manifestu: package pl.zuza.midea.readonly, launcher pl.zuza.midea.MainActivity, min SDK 26, target SDK 35, uprawnienie INTERNET.

## Niewykonane / ograniczenia

- Nie uruchomiono APK na fizycznym telefonie ani emulatorze.
- Nie testowano prawdziwego logowania, odczytu chmury ani połączenia z pompą użytkowniczki.
- Nie potwierdzono dostępności parametrów priorytetowych dla jej modelu.
- Nie potwierdzono fizycznych jednostek ani skal surowych pól diagnostycznych.
- Nie ukończono kompilacji przez AGP/Gradle ani Android Lint w tym środowisku: proces Gradle nie miał dostępu sieciowego do repozytoriów. Wrapper Gradle utworzono poprawnie. Zamiast tego skutecznie wykonano cały dołączony alternatywny proces budowania.
- Testy z atrapą nie dowodzą aktualnej zgodności z produkcyjnym API Midea.

## APK

Nazwa: MideaReadOnly-test.apk

SHA-256: `029982c6618ed101190f7603df4bd8e45aa98e092247b497fc05feb3f215d73a`

Wersja: 0.1.0-probe. Podpis testowy. Nie jest to wydanie produkcyjne ani aplikacja oficjalna Midea.
