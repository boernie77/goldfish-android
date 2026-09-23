---
name: goldfishandroid-app
description: "Use when working on the Goldfish Android client (GoldfishAndroid) - app identity, keystore/release rules, the three server-API compatibility quirks and the browser-is-authoritative rule."
---

# goldfishandroid-app

App-Identität, Keystore-/Release-Regeln, der API-Prüfpfad, die drei Server-API-Quirks und die
Browser-Authoritativ-Regel. Originaltext der früheren `CLAUDE.md` (Stand 2026-09-20), verbatim.

## Harte Regeln

- `versionCode` in `app/build.gradle.kts` **bei JEDER neuen AAB erhöhen** (nicht einmal pro
  Session), `versionName` parallel; Release immer in den Play-Console-Internal-Testing-Track.
- **Keystore NIEMALS committen**: `goldfish-release.jks` + `keystore.properties` sind
  git-ignoriert und bleiben lokal — sie signieren alle Play-Store-Updates.
- Das Repo ist **öffentlich** (GPLv3): keine echten Namen, E-Mails, internen IPs, Hostnamen oder
  Secrets in Code, Kommentaren oder Doku; Sichtbarkeit per `gh repo view … --json visibility`
  prüfen statt aus einer Doku-Zeile übernehmen.
- Die drei Server-API-Quirks (`/resume` separat, `/api/download/{id}`, Cast über `metadata_id`)
  sind fest verdrahtet und werden **nicht** „bereinigt" — sonst stille App-Bugs.
- **Browser ist immer authoritativ:** existiert ein Feature im Browser, wird es 1:1 nachgebaut,
  nachdem das Browser-Verhalten geprüft wurde.

---

# GoldfishAndroid — Android-Client für Goldfish

Native Android-App (Kotlin/Compose) für den Goldfish-Server (separates Repo
`github.com/boernie77/goldfish`, lokal `~/Projekte/Videoplayer/`). Aktuell
im **Internal-Testing-Track** der Google Play Console verteilt (NICHT
öffentlich im Play Store), läuft auf einem Samsung-Tablet des Users sowie
im Pixel-Tablet-Emulator. Eigenes Git-Repo seit 2026-08-19:
`github.com/boernie77/goldfish-android` (privat).

Die App ist NICHT mitversioniert mit dem Server — eine geänderte
Server-API-Antwort kann die App stillschweigend brechen
(Moshi-Parse-Error → leere Listen).

**Release-Signing-Credentials** (`goldfish-release.jks` + Passwort) liegen
NICHT im Repo — `app/build.gradle.kts` liest sie aus `keystore.properties`
(git-ignoriert, nur lokal, siehe `keystore.properties.example` für die
Struktur). **Diesen Keystore NIE committen** — er signiert alle
Play-Store-Updates.

**⚠ Memory-Hinweis:** Detail-Memories (`project_android_app.md`,
`project_feature_local_libraries.md` u. a.) wurden in Sessions angelegt,
die im Server-Repo (`~/Projekte/Videoplayer/`) liefen — pro
Arbeitsverzeichnis gespeichert, eine Session, die nur in diesem Repo
arbeitet, sieht sie NICHT automatisch. Die volle Feature-/Bugfix-Chronik
ist deshalb unten in dieser CLAUDE.md dupliziert (vorher nur als Skill
`android-feature-history` im Server-Repo, das ebenfalls
projekt-lokal/nicht global ist).

## VOR jeder Server-API-Änderung prüfen

- Pfad geändert? → App-Repo
  `app/src/main/kotlin/com/goldfish/android/data/api/GoldfishApi.kt`
- JSON-Feld umbenannt oder Typ geändert? → `data/model/Models.kt` (Moshi
  `@Json(name=…)`)
- Neuer Endpoint? Optional, App kann ihn ignorieren.

**Wenn du etwas brichst:** versionCode in `app/build.gradle.kts` erhöhen
(bei JEDER neuen AAB, nicht nur einmal pro Session), neue AAB bauen
(`./gradlew bundleRelease`), in Play Console Internal-Testing-Track
hochladen. Dauert ~3 Min Build + 5 Min Play-Console-Prozessierung.

## Server-API-Quirks, die die App kennt (NICHT brechen)

Diese drei Quirks sind in der App fest verdrahtet und gelten als „bekannte
Konvention" — nicht ändern, sonst stille App-Bugs:

1. **`resumePosSec` ist NICHT in der `getItem`-Antwort.** Es gibt einen
   separaten Endpoint `GET /api/items/{id}/resume` → `{positionSec:
   float}`. Die App holt beide Calls und merget.
2. **Download-Endpoint heißt `/api/download/{id}`**, NICHT
   `/api/items/{id}/download`. Letzteres existiert nicht (404).
3. **Cast-Endpoint via `metadata_id`, nicht `item_id`**:
   `GET /api/metadata/{id}/cast`. Bei Episoden liefert der Server
   automatisch Show-Hauptcast + Episoden-Gäste.

## Was die App NICHT hat

- Kein OIDC. Login direkt per Email/Passwort (Cookie-Persistenz in
  SharedPrefs). Die App profitiert NICHT vom Authentik-SSO im Browser.
- Kein Cast/AirPlay (die Buttons im Player sind browser-only).
- Kein Admin (User-Verwaltung, Library-Manager, Scan, NFO-Bulk,
  Whisper-UI etc.) — **Ausnahme seit der Musik-Bibliothek** (s.u.): zwei
  schmale, admin-gated Metadaten-Edit-Dialoge (Track/Album), sonst
  unverändert kein Admin-UI.

## Feedback-Regel: Browser ist immer authoritativ

**Wenn ein Feature im Browser existiert, baut die Android-App es 1:1
nach.** Eigene Interpretationen führen zu Frust und Doppelarbeit — vor dem
Implementieren/Fixen eines Features immer erst das Browser-Verhalten
prüfen (Server-Repo-CLAUDE.md + bei Bedarf `app.js`/`grid.js`/etc.
nachlesen). Beispiele: Buchstaben-Sidebar sucht in Folders+Items bei
Title-Sort; Resume-Position kommt aus dem separaten `/resume`-Endpoint,
nicht aus dem Item-JSON; Untertitel-Track-Codes (`webvtt-generated` für
Whisper, `subrip`/`mov_text` für Text, `pgssub` für Bilder) müssen 1:1
erkannt werden.

## Gesehen-Haken der Kachel live nachziehen (alle Listen)

Wird ein Item außerhalb der Liste umgeschaltet (Player-Ende ab 90 % Laufzeit, Detail-Ansicht,
Staffel-Bulk), muß die Kachel in Bibliothek/Ordner/Staffel/Suche **sofort** grün werden — nicht
erst beim Verlassen und erneuten Betreten der Ansicht. Der Browser macht das über `player.js` →
`markWatchedNow` → `silentlyRefreshItem` (ersetzt genau diese eine Kachel im DOM).

Die App hat dafür den Bus `ItemRepository.itemUpdated`: `setWatched`/`setFavorite` invalidieren
ihre Caches und senden die Item-ID. Jedes ViewModel mit einer Item-Liste muß ihn abonnieren und
das betroffene Item ersetzen — `DetailViewModel` und `HomeViewModel` taten das schon,
`LibraryViewModel` (Kacheln in Bibliothek, Ordner und Staffel über `items`/`episodeItems`) und
`SearchViewModel` fehlten (User-Report 2026-09-23: „im Infofeld wird der Haken gesetzt, die
Kachel bleibt grau").

- **Kein voller `reload()` als Reaktion**: der setzt `isLoading = true` und die
  Bibliotheksansicht zeigt dann einen Vollbild-Ladekreis — beim Filmende ein sichtbares
  Flackern. Stattdessen `getItem(id)` (Cache ist bereits invalidiert) holen und das Item in
  `items`/`episodeItems` bzw. `serverResults`/`offlineResults` ersetzen.
- Mit `debounce(200)` sammeln, sonst löst eine Staffel-Bulk-Aktion einen Abruf je Folge aus
  (gleiches Muster wie `HomeViewModel`).
- Lokale (SAF-)Bibliotheken laufen getrennt und sind abgedeckt:
  `LocalLibraryRepository.itemMutated` → `LocalLibraryViewModel.refreshCurrent()`.
- **Beim Bauen neuer Listen darauf prüfen:** eine Kachel, die ihren `Item`-Schnappschuß vom
  Ladezeitpunkt festhält, ist nach jeder Mutation veraltet.
