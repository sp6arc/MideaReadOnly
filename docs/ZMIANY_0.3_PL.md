# Nowości 0.3.0 — panel inspirowany Home Assistant

- Nowy ekran logowania i automatyczne ponowne logowanie SmartHome.
- `SecretsStore.kt`: AES/GCM z kluczem przechowywanym w Android Keystore, opt-in, bez zapisywania tokenów; można wylogować i usunąć konto.
- Zapamiętany identyfikator C3 z listy zostaje powtórnie wybierany po starcie.
- Panel z kartami grupowanymi według znaczenia klucza, zakładkami, wyszukiwarką danych, statystykami surowych odczytów i wyświetlaniem błędów.
- Automatycznie wykonywany jest wyłącznie pierwszy odczyt statusu, bez okresowego wysyłania żądań X10.
- Rozbudowany słownik kluczy C3 na podstawie dokumentacji midea_ac_lan. Surowe wartości i zasady eksportu z 0.2 pozostają bez zmiany.
- Brak modyfikacji `CloudClient.kt`, `C3.kt` i `ReadPolicy.kt`.

Zwróć uwagę: układ **inspirowany**, a nie kopia lub komponent Home Assistant. Nie jest z nim łączony i nie wymaga jego instalacji.
