MSurvivalAuth - plugin logowania dla Paper / Java 25

Komendy gracza:
/register <haslo> <powtorz_haslo>
/login <haslo>
/changepassword <stare_haslo> <nowe_haslo>

Komendy administratora:
/authadmin reload
/authadmin reset <nick>

Jak zbudowac przez GitHub Actions:
1. Utworz repozytorium na GitHubie.
2. Wgraj zawartosc folderu MSurvivalAuthPlugin, nie caly folder.
3. Dodaj plik .github/workflows/build.yml z poradnika od ChatGPT.
4. Wejdz w Actions i pobierz artefakt .jar.
5. Wrzuć .jar do folderu plugins na serwerze.
6. Zrestartuj serwer.

Dane graczy zapisują się w plugins/MSurvivalAuth/users.yml
Hasla sa hashowane PBKDF2, nie sa zapisywane zwyklym tekstem.
