# Jak uzyskać instalacyjny APK 0.3.0

Ten projekt **nie zawiera udawanego pliku APK**. Jest to kompletny kod źródłowy aplikacji. Do wygenerowania pakietu instalacyjnego potrzebne są narzędzia Android SDK, Gradle i kompilator Android (D8/aapt2). Testy Kotlin JVM nie zastępują tych narzędzi.

## Android Studio

1. Otwórz `MideaReadOnly-0.3.0-APK-BUILD` jako projekt Android Studio.
2. Zainstaluj Android SDK Platform 35 oraz Build Tools 35 (Android Studio podpowie brakujące komponenty).
3. Wybierz `Build > Build Bundle(s) / APK(s) > Build APK(s)` albo uruchom `./gradlew :app:assembleDebug`.
4. APK znajduje się w `app/build/outputs/apk/debug/app-debug.apk`.

## Automatycznie w GitHub Actions (bez Android Studio)

1. Umieść **zawartość katalogu projektu** w repozytorium GitHub; plik `.github/workflows/build-android-apk.yml` musi być w głównym katalogu repozytorium.
2. Otwórz zakładkę `Actions` w repozytorium i wybierz workflow `Midea ReadOnly — budowanie APK`.
3. Kliknij `Run workflow` (albo wypchnij zmiany do gałęzi main).
4. Po **pomyślnym** wykonaniu zadania pobierz artefakt `MideaReadOnly-0.3.0-debug-APK` z wyników workflow.
5. Rozpakuj artefakt, a następnie zainstaluj `app-debug.apk` na swoim telefonie.

### Uwaga przy aktualizacji

Wersja debug jest podpisywana domyślnym kluczem debug środowiska wykonawczego. Jeśli poprzednia wersja aplikacji była podpisana **innym kluczem**, Android nie pozwoli na nadpisanie instalacji — trzeba będzie odinstalować starszą wersję, co usuwa jej lokalne dane. Hasła zapamiętane przez Android Keystore nie można po takim odinstalowaniu odtworzyć.

### Weryfikacja

Wyłącznie kompilacja źródeł Kotlin JVM z atrapami Android API nie potwierdza, że `assembleDebug` zakończy się powodzeniem. Działanie chmury Midea i zapamiętywania logowania wymaga testów na telefonie; nie wykonano takich testów bez dostępnego urządzenia i konta.
