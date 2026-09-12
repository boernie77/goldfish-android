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

## Feature-/Bugfix-Chronik

### Musik-Bibliotheken (kind=music), seit 2026-09-12 — volle Parität zu iOS

Ausgangspunkt: User-Report "Musiktitel-Suche findet nichts" auf iOS/Browser
führte zur Entdeckung, dass Android **gar keine** Musik-Unterstützung hatte
(kein `artist`/`album`/`genre`/`musicAlbumId` auf `Item`, keine Album-Ansicht,
keine Musik-Wiedergabe). User-Entscheidung: volle Parität zu iOS in einem
Rutsch, inkl. echtem Hintergrund-Audio-Player (nicht nur der bestehende
Video-Player wiederverwendet) — siehe Plan
`~/.claude/plans/breezy-coalescing-corbato.md` (Server-Repo-Session) für die
volle Herleitung/Exploration.

- **Modelle** (`Models.kt`): `Item` um `artist/album/genre/trackNo/
  musicAlbumId/year/lastPlayedAt` erweitert (additiv, nullable). Neue
  `MusicAlbum`/`AlbumDetail`. `Playlist`/`CreatePlaylistRequest` um
  `kind: String = "video"` erweitert (Server-Split video/music) —
  bestehender `PlaylistsViewModel` ruft jetzt explizit `kind="video"` auf,
  damit neue Musik-Playlists dort nicht mit auftauchen.
- **API/Repository**: `GoldfishApi` um Album-/Genre-/Metadaten-Endpoints
  + `playback/{id}/start|stop` ergänzt (Activity-Log-Parity, bisher von
  KEINEM Client dieser App genutzt — bewusst NUR für Musik ergänzt, kein
  Risiko für den bestehenden Video-Pfad). Neues `MusicRepository`
  (Alben/Genres/Metadaten-Edit), `ItemRepository` um `genre`-Filter in
  `getItems` + `kind`-Param in `getPlaylists`/`createPlaylist` erweitert.
- **Hintergrund-Audio-Player** (die grösste Neuerung): `MusicPlaybackService
  : MediaSessionService` (Media3 — `media3-session` war seit Langem eine
  ungenutzte Gradle-Dependency) hält EINEN ExoPlayer + EINE MediaSession,
  liefert Lockscreen-/Benachrichtigungs-Controls automatisch aus der
  MediaItem-Metadata. `MusicPlayerController` (App-weites Singleton) haltet
  die `MediaController`-Verbindung, ist die EINZIGE Stelle, die Transport-
  Befehle fürs komplette App auslöst. Manifest: neuer `<service
  foregroundServiceType="mediaPlayback">` + `FOREGROUND_SERVICE_MEDIA_
  PLAYBACK`/`POST_NOTIFICATIONS`/`WAKE_LOCK`-Permissions.
  `POST_NOTIFICATIONS` (API 33+) wird lazy beim ersten Track-Start
  angefragt (`MainActivity`), Wiedergabe blockiert NIE darauf.
- **Mini-Player**: die App hat KEINE Bottom-Nav-Scaffold — `MainActivity`
  rendert `GoldfishNavHost` direkt. `MusicMiniPlayerBar` sitzt deshalb als
  fixer Sibling UNTER dem NavHost in einer `Column` (nicht überlagernd),
  überlebt dadurch jede Navigation ohne Pro-Screen-Wiring. Tap → `Screen.
  NowPlaying` (voller Player: Scrub-Bar, Transport, Shuffle).
  Positions-Anzeige läuft über `MusicPlayerController.pollPosition()`
  (500ms-Timer im UI) — Media3 feuert keinen periodischen Zeit-Callback.
- **UI**: `MusicLibraryScreen` (Album-Grid/-Liste + "Alle Titel"-Umschalter,
  Genre-Filter, Sortierung Künstler/Album/Jahr + Richtung, "zuletzt
  abgespielt zuerst" nur in Alle-Titel), `AlbumDetailScreen` (Play/Shuffle,
  Track-Favorit vs. Album-Favorit — **zwei getrennte Server-Konzepte**, nie
  auf denselben Button/Zustand mappen), eigener `MusicPlaylistsScreen`
  (getrennt vom bestehenden Video-`PlaylistsScreen`, um den funktionierenden
  Pfad nicht anzufassen), `EditTrackMetadataDialog`/`EditAlbumMetadataDialog`
  (admin-only, eigene `music-metadata`/`albums/{id}/metadata`-Endpoints —
  NICHT der TMDB-`metadata-manual`-Pfad für Filme/Serien). Neue wiederverwendete
  Primitiven: `AlbumCard` (quadratisches Cover, nicht `VideoCard`) und
  `MusicTrackRow` (EIN Zeilen-Renderer für Alle-Titel/Suche/Album-Tracklist/
  Playlist-Inhalt).
- **Suche fixiert**: `SearchScreen` zeigte Musik-Treffer bisher generisch
  (kein Bug, da Android nie Album-Bündelung hatte) — rendert Musik-Items
  jetzt über `MusicTrackRow` und spielt den Treffer direkt ab
  (`SearchViewModel.playMusicSearchResult`), statt einen nicht-existenten
  Musik-Detail-Screen zu öffnen.
- **Navigation**: `HomeScreen`s `onNavigateToLibrary`-Callsite branched jetzt
  auf `library.kind == "music"` → eigene `Screen.MusicLibrary`-Route (statt
  die grosse, bereits komplexe `LibraryViewModel`/`LibraryScreen`
  movies/tv/private-Statemaschine um eine 4. Verzweigung zu erweitern).
- **Bewusst NICHT gebaut** (Scope-Grenze, User informiert): kein Offline-
  Sync für Musik (der bestehende `OfflineRepository`/Room-Offline-Pfad ist
  movies/tv/private-spezifisch) — reine Online-Wiedergabe für's Erste.
- **Noch nicht auf echtem Gerät getestet** (kein Emulator/Device in dieser
  Session verfügbar) — `./gradlew :app:assembleDebug` läuft grün, aber
  Lockscreen-Controls/Benachrichtigung/Hintergrund-Persistenz sollten vor
  einem Release manuell auf einem echten Gerät verifiziert werden.

### Stand 1.2.67

- **Bibliotheken zusammenlegen (vC 98 Server+App, vC 99 lokale Libs):** In
  den Einstellungen können je 2 Bibliotheken gleichen Typs virtuell
  zusammengelegt werden (Server+Server ODER Lokal+Lokal). Im HomeScreen
  erscheinen separate „🔗 Lib1 + Lib2"-Kacheln; wenn eine Lib nicht
  verfügbar ist, ausgegraut (kein Fehler). Zufallsplay, Sortierung etc.
  funktionieren über beide. Server: `ItemFilter.LibraryIDs []int64` +
  `IN (…)`-Klausel in `ListItems`/`randomItem`; API akzeptiert mehrere
  `?libraryId=` Query-Params. App: `mergedServerLibraryIds` +
  `mergedLocalLibraryIds` in SettingsDataStore; `GoldfishApi`/
  `ItemRepository` mit `List<Int>?`; `LibraryViewModel.loadMerged()`,
  `LocalLibraryViewModel.loadMerged()` (Items aus beiden Libs kombiniert
  flach); Routen `MergedLibrary`, `PlayerRandomMerged`,
  `MergedLocalLibrary`. Settings-UI in zwei Gruppen (📡 Server, 📁 Lokal).
- **Online-Lib „Zuletzt gespielt" — App rief /played nie (vC 96):** Server
  trackt `user_item_state.last_played_at` NUR via
  `POST /api/items/{id}/played` (`TouchLastPlayed`); Resume/Watched setzen
  es NICHT. Der **Browser** ruft das beim Player-Open (player.js), die
  **App tat es nicht** → in der App gespielte Videos erschienen nie in der
  Online-Sortierung „Zuletzt gespielt". Fix: `GoldfishApi.markPlayed` +
  `ItemRepository.markPlayed` (invalidiert items-/home-Cache),
  `PlayerViewModel.load` ruft es fire-and-forget beim Öffnen.
- **Lokale Lib: Sort „Zuletzt gespielt" (vC 91):** lokale Bibliotheken
  haben jetzt auch den Sort `LOCAL_SORT_PLAYED` — flache library-weite
  Liste der zuletzt im lokalen Player geöffneten Items. Neue Spalte
  `local_items.lastPlayedAt` (LocalAppDatabase v5, MIGRATION_4_5), beim
  `LocalPlayerViewModel.load` gestempelt (gilt für ExoPlayer + VLC), beim
  Re-Scan erhalten.
  **Fix vC 92:** Nach Player-Rückkehr wurde die lokale Liste NICHT neu
  geladen (Idempotenz-Guard in `load()` blockt Refresh zum Scroll-Schutz).
  vC 92 (LifecycleResumeEffect → `refreshCurrent()`) reichte NICHT
  zuverlässig.
  **Fix vC 93:** `LocalLibraryRepository` hat jetzt einen
  `itemMutated`-SharedFlow, der nach jedem `updateItem` emittiert;
  `LocalLibraryViewModel` beobachtet ihn im `init` und ruft
  `refreshCurrent()` (stilles force-Reload).
  **ECHTE Ursache + Fix vC 94:** vC 92/93 reichten nicht, weil es KEIN
  Refresh-Problem war — der `lastPlayedAt`-Stempel wurde wieder
  überschrieben. `recoverMissingThumbnails` UND `LocalEnricher.applyHit`
  schrieben Items per `update(item.copy(...))` als GANZE Zeile aus einem
  VERALTETEN Snapshot zurück → clobberten den parallel gesetzten
  lastPlayedAt (und watched/resume) auf 0. Fix: gezielte
  Einzelspalten-Updates `LocalItemDao.setLastPlayed`/`setThumbnailPath`;
  Stempel via `repository.markLocalPlayed` (statt updateItem(copy));
  recoverMissingThumbnails nutzt setThumbnailPath; applyHit liest das Item
  frisch (getItem) bevor es die Zeile schreibt. **LEHRE: Hintergrund-Jobs
  NIE eine ganze Entity-Zeile aus einem alten Snapshot zurückschreiben —
  gezielte Spalten-Updates nutzen.**
- **Player Favorit + Löschen (vC 89):** Im Server-Player oben rechts ein
  Favorit-Toggle (♥, optimistisch) und — Admin-only — ein Lösch-Button (🗑)
  mit Bestätigungsdialog (`DELETE /api/items/{id}?deleteFile=true`); nach
  Erfolg `state.deleted=true` → Screen navigiert zurück.
- **Flache library-weite Sort-Modi (vC 89):** „Zuletzt abgespielt"/
  „Zuletzt hinzugefügt"/„Laufzeit" zeigen jetzt — wie im Browser — die
  Top-Videos der GANZEN Library, unabhängig von Ordner/Staffel.
  `isFlatSortMode()` in LibraryViewModel. **Offline (vC 90):** auch
  `doReloadOffline` behandelt die drei Modi via
  `OfflineRepository.itemsSortedFlat` aus Room. „Zuletzt abgespielt" nutzt
  ein NEUES lokales `downloads.lastPlayedAt` (DB v6), das
  `PlayerViewModel` beim Abspielen eines Downloads via
  `DownloadRepository.markPlayed` stempelt.
- **Drilldown-Toggle (vC 64):** Long-Press auf eine Folder-Kachel
  (Admin-only) öffnet Bestätigungsdialog "Unterordner als Ebene
  anzeigen?". Pendant zum Hover-⚙ im Browser.
- **Bugfix lokale-Lib-Thumbnails (vC 64):** Frame-Vorschaubilder lagen in
  `cacheDir/local-thumbs`, das Android unter Speicherdruck löscht. Jetzt
  `filesDir/local-thumbs` plus `recoverMissingThumbnails`-Background-Job.
- **Lokale Bibliotheken (SAF)** mit eigener Room-DB, NameParser-Port,
  MediaProbe via MediaMetadataRetriever, TMDB-Anreicherung via
  Server-Proxy, Frame-Thumbnails als Fallback, Show→Staffel→Folge-
  Navigation, Show-Re-Match-Dialog, Zufallswiedergabe pro Lib/Folder,
  Long-Press-Delete (SAF + DB), ansicht-scoped Suchfeld in der Lib.
- **Privat-Libs gruppieren nach Channel-Folder**, Items sortiert nach
  `releasedAtMs ?: modifiedTime` DESC (neueste oben); Container-DATE-Tag
  (yt-dlp MKV-DATE `YYYYMMDD`, mp4 creation_time) per MediaProbe
  ausgelesen + in `local_items.releasedAtMs` persistiert.
- **Offline-Verbesserungen**: `library_folder_cache` +
  `library_seasons_cache` cachen Show-Poster + komplette SeasonResponse
  persistent; Offline-Mode filtert Staffel-Ansicht auf owned-Episoden.
- **Re-Match-Sync-Button (↻)** im Show-Header von Server-Libs nach
  Browser-seitiger Korrektur (`?refresh=true`).
- **Globale Suche** (🔍 in der HomeScreen-Topbar) über Server-Libs +
  Offline-Downloads + lokale Libs.
- **Long-Press-Delete für Downloads** in jeder Lib-Ansicht + Settings-
  Button „Alle Downloads entfernen" mit bulk-File-Cleanup.
- **`channelLabelOnTop`-Toggle** aus Server respektiert.
- **HomeScreen reagiert auf Item-Mutations** (`itemUpdated`-SharedFlow,
  300ms debounce) → „Fortsetzen"/„Als nächstes"-Strips refreshen nach
  watched/favorite/resume statt stale-Items zu behalten.
- Setup-Wizard (Server-URL + Login), Persistent-Auth, Cast/AirPlay aus
  Browser-only (bewusst nicht in der App).

### Snapshot Stand 1.1.3 (Basis)

- Library-Grid (adaptive Spalten Tablet/Phone), Filter (Sort/Watched/
  Favorit/Auflösung/Rating/Flach/Staffeln/Zufall/Auswahl), Detail-Screen
  mit Cast-Strip.
- **Buchstaben-Sidebar** rechts bei Sort=Title und ≥10 Kacheln — sucht
  zuerst in Show-Folders (TV-Lib), dann in Items (wie im Browser).
- **Sortier-Richtung** wird explizit als `dir=asc|desc` gesendet, nicht
  client-side gereverst.
- Player: Media3/ExoPlayer, eigenes Compose-Settings-Zahnrad oben rechts
  (synced mit ControlBar), Quality-Auswahl, **Untertitel-Dropdown**
  (Text-VTT, Whisper-VTT, PGS bei Direct Play via ExoPlayer-PgsParser),
  Trickplay-Hover beim Scrub-Drag, Resume-Dialog (Daten aus separatem
  `/resume`-Endpoint).
- Show-Header in der Staffel-Übersicht mit Beschreibung.
- Episoden-Grid mit Auflösung-Badges und Offline-Indikator.
- Download via SAF-Picker („Ordner auswählen…") in Settings,
  Application-Scope-Coroutine, Progress-Ring auf der Kachel + grünes
  CloudDone nach Abschluss.
- Performance: in-Memory ApiCache (TTL pro Endpoint), Coil 1 GB
  Disk-Cache, OkHttp HTTP-Cache (User-konfigurierbar).
- Adaptive Launcher-Icon (Goldfisch CC-BY 4.0 Twemoji).
- Versionsnummer aus `BuildConfig.VERSION_NAME` im Settings-Screen
  sichtbar.

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
