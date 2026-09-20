# GoldfishAndroid — Projektregeln

> **`CLAUDE.md` ist absichtlich nur der Import-Shim `@AGENTS.md` — nicht beschreiben.**
> Regeln, die in jeder Session gelten, gehören in diese Datei; Detailwissen in einen
> Themenskill unter `.claude/skills/<thema>/SKILL.md`. Ein Wächter-Hook
> (`~/.hermes/hooks/claude_md_shim_guard.py`, registriert in `~/.claude/settings.json`)
> lehnt Schreibzugriffe auf `CLAUDE.md` ab und setzt sie bei Drift automatisch zurück —
> auch nach Änderungen per Editor oder Skript. Claude-Code-Spezifisches, das Hermes
> bewusst nicht sehen soll, gehört nach `.claude/rules/`.

**Diese Datei wird bei jedem Agentenstart vollständig geladen — deshalb kurz halten.**
Detailwissen liegt in den Skills unter `.claude/skills/` (Tabelle unten), nicht hier. Neue
Erkenntnisse gehören in den passenden Themenskill, nicht in diese Datei; sie ist bewusst unter
20.000 Zeichen (harte Ladegrenze in Hermes) und unter der von Anthropic empfohlenen
200-Zeilen-Marke.
Volltext der früheren Sammel-`CLAUDE.md` (21.683 Zeichen, Stand 2026-09-20): Skill
`goldfishandroid-full-archive`. `CLAUDE.md` ist nur noch der Ein-Zeilen-Shim `@AGENTS.md` —
Claude Code und Hermes Agent lesen damit denselben Inhalt.

---

## Produkt & Stack

**GoldfishAndroid** ist der native Android-Client (Kotlin/Jetpack Compose) für den
Goldfish-Server (separates Repo `github.com/boernie77/goldfish`, lokal
`~/Projekte/Videoplayer/`). Eigenes Git-Repo seit 2026-08-19:
`github.com/boernie77/goldfish-android` — **öffentlich** (GPLv3; Sichtbarkeit am 2026-09-20 über
die GitHub-API geprüft, nicht aus einer Doku-Zeile übernommen).
Verteilung läuft über den **Internal-Testing-Track** der Google Play Console (NICHT öffentlich im
Play Store). Zielgeräte: ein Samsung-Tablet des Users und der Pixel-Tablet-Emulator.

- Sprache/UI: Kotlin + Compose, Navigation-Compose, Material 3
- Wiedergabe: Media3/ExoPlayer für Video **und** Hintergrund-Audio
  (`MusicPlaybackService : MediaSessionService`), VLC als Alternative für lokale Dateien
- DI: Hilt · Persistenz: Room (Server-Offline-Cache **und** eigene DB der lokalen SAF-Libs)
- Netzwerk: Retrofit/OkHttp + Moshi (`@Json(name=…)`), Coil (1 GB Disk-Cache),
  in-Memory-`ApiCache` mit TTL pro Endpoint
- Lokale Bibliotheken: SAF-Ordner + Room, NameParser-Port, MediaProbe via
  `MediaMetadataRetriever`, TMDB-Anreicherung über den Server-Proxy, Frame-Thumbnails
- Build: Gradle Kotlin DSL, `app/build.gradle.kts`, Version aus `BuildConfig.VERSION_NAME`

## Harte Verbote

- **Den Keystore NIEMALS committen.** `goldfish-release.jks` und `keystore.properties`
  (Klartext-Passwort) liegen nur lokal; `.gitignore` schließt `*.jks`, `*.keystore` und
  `keystore.properties` aus. `app/build.gradle.kts` liest die Credentials aus
  `keystore.properties` (Struktur siehe `keystore.properties.example`). Der Keystore signiert
  **alle** Play-Store-Updates — er darf nie ins Repo, nie in einen Commit, nie in ein Log.
- **Dieses Repo ist ÖFFENTLICH**: vor jeder Änderung prüfen, ob Code oder Kommentare echte
  Namen, E-Mails, interne IPs, Hostnamen, Tokens oder Secrets enthalten. Genau das ist schon
  passiert (im Server-Repo stand einen Monat lang die LAN-Adresse des Heimservers in
  `network_security_config.xml`, bis 2026-09-18) — **Sichtbarkeit nie aus einer Doku-Zeile
  übernehmen**, sondern nachsehen: `gh repo view boernie77/goldfish-android --json visibility`.
- **Keine eigene Interpretation des Browser-Verhaltens.** Der Browser ist authoritativ (eigene
  Regel unten) — vor dem Bauen/Fixen eines Features erst das Browser-Verhalten prüfen.
- **Die drei Server-API-Quirks nicht „bereinigen"** — die App ist auf sie fest verdrahtet.
- **Kein `git push`, kein Play-Store-Upload, kein Release ohne ausdrückliche Anweisung.**

## Versionierung & Release (Pflicht)

- `versionCode` in `app/build.gradle.kts` **bei JEDER neuen AAB erhöhen** — nicht nur einmal pro
  Session; `versionName` läuft parallel weiter (Stand im Repo: vC 109 / 1.3.3).
- Release-Weg: `./gradlew bundleRelease` → AAB in die Play Console,
  **Internal-Testing-Track**, hochladen. Dauer ~3 Min Build + ~5 Min Play-Console-Prozessierung.
- Debug-Build für Zwischenstände: `./gradlew :app:assembleDebug`.
- Der Server versioniert unabhängig davon (Server-Repo-CLAUDE.md) — App- und Server-Version
  laufen bewusst auseinander.

## API-Kompatibilität zum Server (stille Brüche)

Die App ist **NICHT** mit dem Server mitversioniert. Eine geänderte Server-Antwort bricht die App
still (Moshi-Parse-Error → leere Listen). Diese drei Quirks sind fest verdrahtet:

1. **`resumePosSec` ist NICHT in der `getItem`-Antwort** — eigener Endpoint
   `GET /api/items/{id}/resume` → `{positionSec: float}`; die App holt beide Calls und merget.
2. **Download-Endpoint heißt `/api/download/{id}`**, NICHT `/api/items/{id}/download`
   (letzteres existiert nicht → 404).
3. **Cast läuft über `metadata_id`, nicht `item_id`**: `GET /api/metadata/{id}/cast`
   (bei Episoden liefert der Server Show-Hauptcast + Episoden-Gäste).

Vor Server-API-Änderungen und bei jedem Umbau gegenprüfen:

- hier: `app/src/main/kotlin/com/goldfish/android/data/api/GoldfishApi.kt` (Pfade) und
  `data/model/Models.kt` (Feldnamen/Typen, Moshi-`@Json`)
- Fire TV: `data/api/GoldfishApi.kt` + `data/model/Models.kt` in
  `github.com/boernie77/goldfish-firetv` — **1:1 kopierter Client-Code, kein automatischer
  Sync**, Änderungen müssen in beiden Android-Repos nachgezogen werden
- Apple: `GoldfishCore/GoldfishClient.swift` · Linux: `goldfish_linux/api.py`

## Code-Konventionen

- **Room-Migrationen additiv und in `LocalAppDatabase` versioniert** (v5/v6-Muster:
  `MIGRATION_4_5`, neue Spalte + Migration, beim Re-Scan Werte erhalten).
- **Hintergrund-Jobs schreiben NIE eine ganze Entity-Zeile aus einem alten Snapshot zurück.**
  Immer gezielte Einzelspalten-Updates (`setLastPlayed`, `setThumbnailPath`), sonst clobbern sie
  parallel gesetzte Felder (watched/resume/lastPlayedAt) auf 0.
- Newest-first-Sortierung lokaler Libs über `releasedAtMs ?: modifiedTime`.
- Formeln wie Auflösungs-Buckets sind in dieser Codebasis bewusst mehrfach dupliziert — kein
  gemeinsamer Helper; beim Ändern alle Fundstellen suchen.
- Long-Press-Aktionen (Delete, Drilldown-Toggle) sind Admin-gated; die App hat **kein**
  Admin-UI außer zwei schmalen Metadaten-Dialogen (Track/Album).

## Feedback-Regel: der Browser ist immer authoritativ

**Wenn ein Feature im Browser existiert, baut die Android-App es 1:1 nach.** Eigene
Interpretationen führen zu Frust und Doppelarbeit — vor dem Implementieren/Fixen erst das
Browser-Verhalten prüfen (Server-Repo-CLAUDE.md, bei Bedarf `app.js`/`grid.js`/etc. nachlesen).
Beispiele: Buchstaben-Sidebar sucht in Folders+Items bei Title-Sort; die Resume-Position kommt
aus dem separaten `/resume`-Endpoint, nicht aus dem Item-JSON; Untertitel-Track-Codes
(`webvtt-generated` für Whisper, `subrip`/`mov_text` für Text, `pgssub` für Bilder) müssen 1:1
erkannt werden.

## Projekt-Memory

Detail-Memories früherer Sessions (`project_android_app.md`,
`project_feature_local_libraries.md` u. a.) wurden in Sessions im **Server-Repo**
(`~/Projekte/Videoplayer/`) angelegt und sind pro Arbeitsverzeichnis gespeichert — eine Session,
die nur in diesem Repo arbeitet, sieht sie **NICHT** automatisch. Die komplette
Feature-/Bugfix-Chronik liegt deshalb in diesem Repo: Skill
`goldfishandroid-feature-history`.

## Wo das Detailwissen liegt

| Skill | Wofür |
|---|---|
| `goldfishandroid-app` | App-Identität, Keystore/Release-Regeln, API-Prüfpfad, die drei Server-API-Quirks, was die App bewusst NICHT hat, Browser-Authoritativ-Regel |
| `goldfishandroid-feature-history` | Feature-/Bugfix-Chronik: Autoplay nächste Folge, Playback-Start/-Stop-Reports, Download-Auflösung/-Größe, Musik-Bibliotheken + Parität, Stand 1.2.67, Snapshot 1.1.3 |
| `goldfishandroid-full-archive` | Vollständiges Original der früheren `CLAUDE.md` (21.683 Zeichen, 2026-09-20), verbatim — Fallback, wenn in den Themenskills etwas fehlt |

Alle Skills liegen unter `.claude/skills/` und sind über den Symlink `.hermes/skills/` auch für
Hermes Agent sichtbar (`.hermes/` ist git-ignoriert; Hermes liest `.claude/skills` nicht direkt).
Claude Code liest sie aus `.claude/skills/`, Hermes nach `hermes skills trust <repo>` ebenfalls —
eine Datei, zwei Agenten.

## Wo im Code was liegt (Orientierung)

- `data/api/GoldfishApi.kt` — alle Server-Endpoints; `data/model/Models.kt` — alle Moshi-Modelle
  (`@Json(name=…)`), inkl. `Item`, `MusicAlbum`, `AlbumDetail`, `Playlist`
- `data/repository/` — `ItemRepository`, `MusicRepository`, `OfflineRepository`,
  `DownloadRepository`, `LocalLibraryRepository` (SAF-Scan/-Match, `itemMutated`-SharedFlow)
- `ui/` — `LibraryScreen`/`LibraryViewModel` (Server), `LocalLibraryScreen`/`LocalLibraryViewModel`
  (SAF-Libs), `DetailScreen`, `SearchScreen`, `HomeScreen` (Strips + Topbar-Suche)
- Player: `PlayerViewModel` (Video, Resume/Quality/Untertitel), `MusicPlaybackService`
  + `MusicPlayerController` (Musik, `MediaSessionService`/MediaController),
  `NowPlayingScreen`/`MusicMiniPlayerBar`
- Persistenz: `LocalAppDatabase` (lokale Libs) und die Room-DB der Downloads/des Offline-Caches
- Navigation: `GoldfishNavHost` in `MainActivity` (nur Material-Compose, kein XML-Layout,
  kein Bottom-Nav-Scaffold); private/Serien-Ansichten laufen über die `LibraryViewModel`-Zweige

## Regel für neue Erkenntnisse

Neue dokumentationswürdige Erkenntnisse gehören **in den passenden Themenskill**, nicht in diese
Datei. Was hier steht, muss bei jedem einzelnen Start relevant sein — alles andere kostet nur
Kontext und senkt die Befolgungsrate.