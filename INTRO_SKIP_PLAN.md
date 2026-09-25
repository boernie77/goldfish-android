# Implementierungsplan: „Vorspann überspringen" (Intro-Skip) im Android-Client

> **Für das ausführende Modell:** Arbeite die Schritte **in der angegebenen Reihenfolge** ab.
> Jeder Schritt nennt Datei, Stelle und den exakten Code. Weiche nicht ab, erfinde keine
> zusätzlichen Endpoints, Dateien oder Einstellungen. Was NICHT angefasst werden darf, steht in
> Abschnitt „Scope-Grenzen". Am Ende: Abschnitt „Test/Verifikationsschritte" ausführen.

---

## 0. Ausgangslage (bereits recherchiert — nicht erneut prüfen)

- Der **Server** erkennt Vorspänne bereits vollständig (`internal/api/introskip.go`) und liefert im
  Item-JSON von **`GET /api/items/{id}`** (Handler `GetItemFor`) zwei zusätzliche Felder:
  - `introStartSec` (float, absolute Sekunde im Video)
  - `introEndSec` (float, absolute Sekunde im Video)
  - Beide fehlen bzw. sind `null`, wenn es keine Erkennung gibt. **Das ist der Normalfall**, kein
    Fehler.
- Die Felder kommen **NUR** aus `GET /api/items/{id}`, **nicht** aus den Listen-Endpoints
  (`/api/items`, `/api/home`, playlists, collections). Der Android-Player lädt dieses
  Einzel-Item ohnehin (`PlayerViewModel.loadItem` → `ItemRepository.getItem`), es ist also **kein
  neuer Netzwerk-Call nötig**.
- **Browser-Verhalten (authoritativ, Datei `internal/webassets/web/player.js`, Funktionen
  `maybeToggleIntroSkip` / `wireIntroSkipOverlayOnce`):**
  1. Ein auffälliger Button/Pill liegt **im Videobild**, nicht in der Steuerleiste.
  2. Sichtbar **genau dann**, wenn `introStartSec != null && introEndSec != null &&
     pos >= introStartSec && pos < introEndSec`. Neu berechnet bei jedem Zeit-Update.
  3. Klick → Sprung auf `introEndSec`; danach verschwindet der Button von selbst, weil die
     Position aus dem Fenster gelaufen ist. **Kein separates „Dismiss"-Flag.**
  4. Bei Transcode-Wiedergabe muss die **absolute** Position verwendet werden
     (Playerposition + virtualOffset), nicht die lokale Playlist-Zeit.
- **Die Android-App hat das Offset-Konzept bereits**: `virtualOffset` in
  `ui/player/PlayerScreen.kt` (Kommentar ab Zeile ~468) und der `wrappedPlayer`
  (`ForwardingPlayer`, ab Zeile ~577) rechnen es in `getCurrentPosition()` und in `seekTo()`
  bereits ein. **Deshalb: immer über `wrappedPlayer` lesen und seeken, NIE direkt über
  `exoPlayer`.** Damit ist der Transcode-Fall ohne neue Rechnerei abgedeckt.
- **Strings:** `app/src/main/res/values/strings.xml` enthält nur `app_name`. Die gesamte App hat
  deutschsprachige **hartkodierte** Compose-Texte (z. B. „Jetzt abspielen", „Abbrechen",
  „Qualität"). → Der neue Text wird ebenfalls hartkodiert: **„Vorspann überspringen"**.
  **Keine** neuen `strings.xml`-Einträge, **keine** Lokalisierung anlegen.
- **Vorbild-Feature für Machart und Verdrahtung:** „Nächste Folge automatisch starten"
  (`PlayerState`-Felder + Rendering als Overlay im `Box` von `PlayerScreen`, Kommentarstil mit
  Browser-Referenz). Daran orientiert sich dieser Plan.

---

## Schritt 1 — Modell: `introStartSec` / `introEndSec` an `Item` ergänzen

**Datei:** `app/src/main/kotlin/com/goldfish/android/data/model/Models.kt`
**Stelle:** `data class Item(…)`, beginnt Zeile 53. Das letzte Feld ist
`val lastPlayedAt: String? = null` (Zeile 92), direkt danach folgt `)` und der Body mit
`displayTitle`.

**Änderung:** Nach `lastPlayedAt` (also als letzte Konstruktor-Parameter) einfügen — Komma nach
`lastPlayedAt` nicht vergessen:

```kotlin
    val lastPlayedAt: String? = null,
    // --- Vorspann-Erkennung (Server-Repo: internal/api/introskip.go) ---
    // Absolute Sekunden im Video. Kommen NUR aus GET api/items/{id}
    // (Server-Handler GetItemFor), NICHT aus den Listen-Endpoints — in Items
    // aus Grid/Home/Playlists sind sie deshalb IMMER null. Null bedeutet
    // "keine Erkennung" und ist der Normalfall, kein Fehler.
    val introStartSec: Double? = null,
    val introEndSec: Double? = null
```

**Warum das sicher ist:** Beide Felder sind nullable mit Default `null`. Alle bestehenden
`Item(...)`-Aufrufe (z. B. `OfflineRepository.minimalItem`) und alle gecachten Item-JSONs in Room
(`downloads.itemJson`) bleiben gültig; Moshi-Codegen erzeugt den Adapter neu. **Keine
Room-Migration nötig** — `Item` ist keine Room-Entity, sondern wird als JSON-String gespeichert.

**Nicht ändern:** `data/api/GoldfishApi.kt` — der Endpoint `getItem` existiert bereits
(Zeile 48/49) und liefert die neuen Felder automatisch mit. **Keinen neuen Endpoint anlegen.**

---

## Schritt 2 — `PlayerState`: Vorspann-Fenster in Millisekunden aufnehmen

**Datei:** `app/src/main/kotlin/com/goldfish/android/ui/player/PlayerViewModel.kt`
**Stelle:** `data class PlayerState(…)`, Zeilen 31–64. Letztes Feld ist
`val lastPlaybackProfile: String? = null` (Zeile 63).

**Änderung:** Nach `lastPlaybackProfile` ergänzen (Komma davor setzen):

```kotlin
    val lastPlaybackProfile: String? = null,
    // --- "Vorspann überspringen" (Server: internal/api/introskip.go) ---
    // Absolutes Zeitfenster des erkannten Vorspanns in Millisekunden.
    // Beide null = keine Erkennung für dieses Item → es gibt keinen Button.
    // Wird bei JEDEM loadItem() zuerst geleert, damit beim In-Place-Wechsel
    // zur nächsten Folge nie das Fenster der Vorfolge stehen bleibt.
    val introStartMs: Long? = null,
    val introEndMs: Long? = null
```

---

## Schritt 3 — `PlayerViewModel.loadItem()`: Fenster befüllen und zurücksetzen

**Datei:** `app/src/main/kotlin/com/goldfish/android/ui/player/PlayerViewModel.kt`
**Funktion:** `private suspend fun loadItem(itemId: Int, profile: String?, skipResume: Boolean)`
(ab Zeile 149).

### 3a) Zurücksetzen beim Laden (Zeile 153)

Vorher:

```kotlin
        _state.update { it.copy(isLoading = true, errorMessage = null, trickplayFrames = emptyList(), isAdmin = admin) }
```

Nachher:

```kotlin
        _state.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                trickplayFrames = emptyList(),
                isAdmin = admin,
                // Vorspann-Fenster der vorigen Folge sofort verwerfen.
                introStartMs = null,
                introEndMs = null
            )
        }
```

### 3b) Fenster aus dem Item ableiten

Direkt **nach** dem Null-Check des Items (nach dem `if (item == null) { … return }`-Block, der bei
Zeile 177–182 endet) und **vor** `viewModelScope.launch { itemRepository.markPlayed(itemId) }`
einfügen:

```kotlin
        // "Vorspann überspringen" (Browser-Parität: maybeToggleIntroSkip in
        // player.js). Fenster NUR übernehmen, wenn der Server BEIDE Felder
        // liefert und das Fenster plausibel ist (Ende > Start) — sonst bleibt
        // es null und es erscheint kein Button. Offline/aus dem Cache
        // geladene Items haben die Felder nicht (Listen-Endpoints liefern sie
        // nicht) → dort gibt es die Funktion schlicht nicht.
        var introStartMs: Long? = null
        var introEndMs: Long? = null
        val introStartSec = item.introStartSec
        val introEndSec = item.introEndSec
        if (introStartSec != null && introEndSec != null &&
            introEndSec > introStartSec && introEndSec > 0.0
        ) {
            introStartMs = (introStartSec * 1000).toLong().coerceAtLeast(0L)
            introEndMs = (introEndSec * 1000).toLong()
        }
```

### 3c) Fenster in **beide** Erfolgs-Zweige schreiben

**Zweig 1 — lokaler Download** (`if (download != null) { … }`, `_state.update` bei Zeile 202–209).
Ergänze die beiden Felder in der `copy(...)`:

```kotlin
            _state.update {
                it.copy(
                    isLoading = false,
                    item = item,
                    localFilePath = download.localPath,
                    resumePositionMs = resumeMs,
                    introStartMs = introStartMs,
                    introEndMs = introEndMs
                )
            }
```

**Zweig 2 — Server-Stream** (`is Result.Success ->` in `when (val pbResult = …)`, `_state.update`
bei Zeile 226–235). Ergänze dort ebenfalls:

```kotlin
                _state.update {
                    it.copy(
                        isLoading = false,
                        item = item,
                        playbackInfo = info,
                        resumePositionMs = resumeMs,
                        subtitleOptions = subOptions,
                        selectedSubtitleKey = "off",
                        introStartMs = introStartMs,
                        introEndMs = introEndMs
                    )
                }
```

Den Fehlerzweig (`is Result.Error`) **nicht** anfassen — dort bleibt das Fenster aus 3a bei `null`.

### 3d) In-Place-Folgenwechsel: Reset mitnehmen

**Funktion:** `private suspend fun switchToNextEpisode(next: Item)` (ab Zeile 433), der
`_state.update { it.copy( … ) }`-Block bei Zeile 438–449. Ergänze dort in der `copy(...)`:

```kotlin
                errorMessage = null,
                introStartMs = null,
                introEndMs = null
```

(Redundant zu 3a, aber gewollt: der Zustand ist damit auch in dem kurzen Moment zwischen
Folgenende und Neuladen sauber.)

**Sonst nichts** im `PlayerViewModel` ändern — insbesondere keine neuen Repository-Methoden, keine
neuen Coroutines, kein neuer Server-Call.

---

## Schritt 4 — `GoldfishPlayer`: Positions-Ticker + Seek-Kanal

**Datei:** `app/src/main/kotlin/com/goldfish/android/ui/player/PlayerScreen.kt`
**Composable:** `private fun GoldfishPlayer(...)` ab Zeile 441.

Media3 liefert **keinen** periodischen Zeit-Callback (gleiche Situation wie beim Musik-Player,
`MusicPlayerController.pollPosition()`), deshalb wird die Position gepollt. Der Poll läuft **nur**,
wenn es für das Item überhaupt ein Vorspann-Fenster gibt.

### 4a) Neue Parameter an der Signatur

In der Parameterliste von `GoldfishPlayer` (Zeilen 441–457), **vor** `modifier: Modifier =
Modifier`, ergänzen:

```kotlin
    // Positions-Ticker für das "Vorspann überspringen"-Overlay. Läuft nur,
    // wenn das Item ein Vorspann-Fenster hat — sonst kein Poll, kein Aufwand.
    positionTickEnabled: Boolean = false,
    onAbsolutePositionTick: (Long) -> Unit = {},
    // Seek-Wunsch aus der Compose-UI (absolute ms). Wird ausgeführt und dann
    // über onSeekRequestHandled quittiert, damit der Aufrufer ihn auf null
    // zurücksetzen kann.
    seekRequestMs: Long? = null,
    onSeekRequestHandled: () -> Unit = {},
```

### 4b) Ticker und Seek-Effekt einbauen

**Stelle:** direkt **nach** dem `DisposableEffect(exoPlayer) { … }`-Block (endet Zeile 705) und
**vor** dem `LaunchedEffect(selectedSubtitleKey, subtitleOptions)`-Block (Zeile 709).
Zu diesem Zeitpunkt ist `wrappedPlayer` bereits definiert (Zeile 577).

```kotlin
    // Absolute Wiedergabeposition im 500-ms-Takt nach oben melden — Grundlage
    // für das "Vorspann überspringen"-Overlay (Browser: timeupdate-Handler
    // maybeToggleIntroSkip in player.js). Gelesen wird über wrappedPlayer:
    // dessen getCurrentPosition() rechnet den virtualOffset der laufenden
    // Transcode-Session bereits ein, liefert also die ABSOLUTE Filmposition.
    val currentOnTick by rememberUpdatedState(onAbsolutePositionTick)
    LaunchedEffect(positionTickEnabled) {
        if (!positionTickEnabled) return@LaunchedEffect
        while (true) {
            currentOnTick(wrappedPlayer.currentPosition)
            delay(500L)
        }
    }

    // Seek-Wunsch aus der Compose-UI (aktuell nur "Vorspann überspringen").
    // Bewusst über wrappedPlayer.seekTo: dort steckt die komplette
    // Transcode-Logik (lokaler Seek, wenn das Ziel im schon produzierten
    // HLS-Material liegt, sonst ffmpeg-Neustart samt virtualOffset).
    // Es wird NICHT play() gerufen — wer pausiert hat, bleibt pausiert
    // (Browser setzt ebenfalls nur currentTime).
    LaunchedEffect(seekRequestMs) {
        val target = seekRequestMs ?: return@LaunchedEffect
        wrappedPlayer.seekTo(target)
        onSeekRequestHandled()
    }
```

### 4c) Import ergänzen

Oben in `PlayerScreen.kt` bei den Imports hinzufügen (die Datei importiert `delay` bisher nicht):

```kotlin
import kotlinx.coroutines.delay
```

sowie für das Icon des Buttons (Schritt 6):

```kotlin
import androidx.compose.material.icons.filled.SkipNext
```

`Icons.Filled.SkipNext` ist verfügbar — wird bereits in
`ui/music/NowPlayingScreen.kt:107` benutzt. `androidx.compose.foundation.layout.*` ist schon per
Wildcard importiert (Zeile 12), `PaddingValues` und `navigationBarsPadding()` brauchen also keinen
zusätzlichen Import.

---

## Schritt 5 — `PlayerScreen`: State, Verdrahtung, Overlay

**Datei:** `app/src/main/kotlin/com/goldfish/android/ui/player/PlayerScreen.kt`
**Composable:** `fun PlayerScreen(...)` ab Zeile 56.

### 5a) Lokaler UI-State

Nach den vorhandenen Trickplay-State-Zeilen (Zeilen 80–82, `scrubPositionMs` / `timeBarBounds`)
einfügen:

```kotlin
    // "Vorspann überspringen": absolute Wiedergabeposition (ms), vom
    // GoldfishPlayer im 500-ms-Takt gemeldet, plus der offene Seek-Wunsch.
    // Bewusst lokaler Compose-State und NICHT im PlayerState — eine
    // 2x/Sekunde aktualisierte Position im StateFlow würde den ganzen Screen
    // rekomponieren lassen.
    var absolutePositionMs by remember { mutableStateOf(0L) }
    var introSeekRequestMs by remember { mutableStateOf<Long?>(null) }

    // Folgenwechsel (In-Place-Autoplay) → Position sofort verwerfen, damit der
    // Button nicht kurz mit der Position der Vorfolge auftaucht.
    LaunchedEffect(state.item?.id) {
        absolutePositionMs = 0L
        introSeekRequestMs = null
    }
```

### 5b) Aufruf von `GoldfishPlayer` erweitern

**Stelle:** Zeilen 117–133. Ergänze die vier neuen Argumente (vor `modifier = …`):

```kotlin
                GoldfishPlayer(
                    context = context,
                    playbackUrl = playbackUrl,
                    isHls = isHls,
                    resumePositionMs = resumeMs,
                    totalDurationMs = totalDurationMs,
                    subtitleOptions = state.subtitleOptions,
                    selectedSubtitleKey = state.selectedSubtitleKey,
                    onPositionChanged = viewModel::saveResumePosition,
                    onEnded = { viewModel.onPlaybackEnded() },
                    onNextRandom = onNextRandom,
                    onSettingsClick = { showQualityMenu = true },
                    onControllerVisibilityChanged = { controllerVisible = it },
                    onScrubPositionChanged = { scrubPositionMs = it },
                    onTimeBarBoundsChanged = { l, t, w -> timeBarBounds = Triple(l, t, w) },
                    positionTickEnabled = state.introEndMs != null,
                    onAbsolutePositionTick = { absolutePositionMs = it },
                    seekRequestMs = introSeekRequestMs,
                    onSeekRequestHandled = { introSeekRequestMs = null },
                    modifier = Modifier.fillMaxSize()
                )
```

### 5c) Das Overlay rendern

**Stelle:** Innerhalb des Zweigs `(state.playbackInfo != null || state.localFilePath != null) -> {`
**nach** dem Quality-Menu-Block (endet Zeile 352) und **vor** dem Kommentarblock/`if
(state.showNextEpisodeOverlay)` (ab Zeile 354). Die Reihenfolge ist wichtig: das
„Nächste Folge"-Overlay ist ein Vollbild-Dimmer und muss weiterhin ganz oben liegen.

```kotlin
                // "Vorspann überspringen" (Server: internal/api/introskip.go;
                // Browser: maybeToggleIntroSkip / wireIntroSkipOverlayOnce in
                // player.js). Sichtbar GENAU solange die absolute Position im
                // erkannten Fenster liegt — absichtlich unabhängig davon, ob
                // die Media3-ControlBar gerade eingeblendet ist: im Browser
                // liegt der Pill ebenfalls frei im Videobild.
                // Klick springt auf das Fensterende; danach verschwindet der
                // Button von selbst, weil die Position aus dem Fenster läuft
                // (kein eigenes "weggeklickt"-Flag, exakt wie im Browser).
                val introStartMs = state.introStartMs
                val introEndMs = state.introEndMs
                if (introStartMs != null && introEndMs != null &&
                    absolutePositionMs >= introStartMs && absolutePositionMs < introEndMs
                ) {
                    IntroSkipButton(
                        onClick = { introSeekRequestMs = introEndMs },
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
```

---

## Schritt 6 — Der Button selbst (neues privates Composable)

**Datei:** `app/src/main/kotlin/com/goldfish/android/ui/player/PlayerScreen.kt`
**Stelle:** auf Top-Level der Datei, direkt **nach** dem Composable `NextEpisodeOverlay`
(endet Zeile 438) und **vor** `private fun GoldfishPlayer(` (Zeile 440/441).

```kotlin
/**
 * „Vorspann überspringen"-Pille im Videobild — Pendant zum Intro-Skip-Button
 * in player.js. Bewusst gross, mit Goldfish-Orange hinterlegt und unten rechts
 * über der Steuerleiste platziert (bequemes Touch-Ziel auf dem Tablet) und
 * NICHT in der Media3-ControlBar: die blendet sich nach wenigen Sekunden aus,
 * der Hinweis muss aber das ganze Vorspann-Fenster über sichtbar bleiben.
 */
@Composable
private fun IntroSkipButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(end = 24.dp, bottom = 96.dp)
            .navigationBarsPadding()
    ) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = GoldfishOrange,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
        ) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = null,
                tint = Color.Black
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Vorspann überspringen",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}
```

Gestaltungsvorgaben, die **nicht** verändert werden dürfen:
- Text exakt `Vorspann überspringen` (hartkodiert, Deutsch — wie alle anderen Player-Texte).
- Farbe `GoldfishOrange` (bereits importiert, Zeile 53) mit schwarzer Schrift/Icon.
- Position: unten rechts (`Alignment.BottomEnd`), `bottom = 96.dp` hält ihn über der
  Media3-Steuerleiste.

---

## Schritt 7 — Versionierung

**Datei:** `app/build.gradle.kts`, Zeilen 25/26.

```kotlin
        versionCode = 110
        versionName = "1.3.4"
```

(Projektregel aus `AGENTS.md`: `versionCode` bei jeder neuen AAB erhöhen; `versionName` läuft
parallel mit.) **Kein `git push`, kein `bundleRelease`-Upload, kein Play-Store-Schritt** — das
entscheidet der User separat.

---

## Randfälle — und wie dieser Plan sie abdeckt

| Fall | Verhalten | Wo im Plan |
|---|---|---|
| Server liefert die Felder gar nicht (alter Server) / `null` | `introStartMs`/`introEndMs` bleiben `null` → kein Button, kein Positions-Poll | Schritt 3b + `positionTickEnabled` in 5b |
| Unplausibles Fenster (`end <= start`) | wird verworfen → kein Button | Schritt 3b |
| Offline-Wiedergabe / lokaler Download | Item kommt aus dem Room-Cache und hat die Felder nicht → kein Button. Liegt trotzdem ein frisches Server-Item vor, funktioniert der Button auch beim Download (Fenster wird in beiden Zweigen gesetzt) | Schritt 3c |
| Nutzer pausiert, während der Button sichtbar ist | Position ändert sich nicht → Button bleibt stehen; Klick seekt und lässt pausiert (kein `play()`) | Schritt 4b |
| Nutzer seekt manuell in/aus dem Fenster | nächster 500-ms-Tick blendet korrekt ein bzw. aus | Schritt 4b |
| Transcode/HLS mit ffmpeg-Startoffset | Position kommt aus `wrappedPlayer.currentPosition` (inkl. `virtualOffset`), Seek über `wrappedPlayer.seekTo` (lokaler Seek oder ffmpeg-Neustart) | Schritt 4b |
| Resume startet mitten im Vorspann | Button erscheint sofort — Browser-Parität | ergibt sich aus 5c |
| Automatischer Wechsel zur nächsten Folge (In-Place) | Fenster wird in `loadItem` und in `switchToNextEpisode` genullt, Position auf 0 zurückgesetzt | Schritt 3a/3d + 5a |
| Verlassen/Neu-Öffnen des Players | neue ViewModel-Instanz, neuer Compose-State → alles frisch | ergibt sich |
| „Nächste Folge"-Overlay erscheint | liegt weiterhin oben; das Vorspann-Fenster kollidiert damit ohnehin nicht | Reihenfolge in 5c |
| Doppelklick auf den Button | zweiter Seek auf dieselbe Zielposition, harmlos | — |

---

## Scope-Grenzen — das hier NICHT anfassen

1. **Kein anderes Repo.** `GoldfishFireTV` (`github.com/boernie77/goldfish-firetv`) hat
   `data/api/GoldfishApi.kt` und `data/model/Models.kt` **1:1 kopiert**; dort müssen
   `introStartSec`/`introEndSec` in `Item` **separat nachgezogen** werden — das ist ein eigener
   Auftrag in einem anderen Arbeitsverzeichnis und **nicht Teil dieser Umsetzung**. Gleiches gilt
   für Apple (`GoldfishCore/GoldfishClient.swift`) und Linux (`goldfish_linux/api.py`) sowie für
   das Server-Repo.
2. **Kein neuer Endpoint, keine Änderung an `GoldfishApi.kt`.**
3. **Kein Repository-Umbau**: `ItemRepository.getItem` bleibt wie es ist (inkl. `ApiCache`-TTL).
4. **Keine Room-Migration**, keine neue Entity, keine neue Spalte.
5. **Keine Einstellung/kein Schalter** für das Feature (der Browser hat auch keinen).
6. **Keine `strings.xml`-Einträge**, keine Lokalisierung, keine neuen Ressourcen.
7. **Kein Anfassen** von `ui/locallib/LocalPlayerScreen.kt`, `LocalPlayerViewModel.kt`,
   `LocalVlcPlayerScreen.kt` (lokale SAF-Bibliotheken kennen keinen Server-Vorspann),
   `DetailScreen`, `LibraryScreen`, Musik-Player oder `MusicPlaybackService`.
8. **Nicht umbauen**: der `wrappedPlayer`/`virtualOffset`/`DurationOverrideTimeline`-Mechanismus in
   `PlayerScreen.kt` wird **nur benutzt**, nicht verändert. Insbesondere `seekTo`,
   `getCurrentPosition` und `handleAbsoluteSeek` bleiben unangetastet.
9. **Keine „Aufräumarbeiten"** an bestehenden Kommentaren, Formeln oder Duplikaten.
10. **Kein `git push`, kein Release, kein Play-Store-Upload, kein `bundleRelease`.** Keine echten
    Namen, E-Mails, IPs, Hostnamen oder Tokens in Code oder Kommentare schreiben — das Repo ist
    öffentlich.

---

## Akzeptanzkriterien (Prüfliste für den Reviewer)

1. **Modell:** `Item` in `data/model/Models.kt` hat `introStartSec: Double? = null` und
   `introEndSec: Double? = null`, beide nullable, beide mit Default. `GoldfishApi.kt` ist
   unverändert.
2. **State:** `PlayerState` hat `introStartMs: Long?` und `introEndMs: Long?` (Default `null`).
3. **Befüllung:** `PlayerViewModel.loadItem` setzt die beiden Felder in **beiden** Erfolgszweigen
   (lokaler Download **und** Server-Stream) und setzt sie beim Start jedes `loadItem` sowie in
   `switchToNextEpisode` auf `null`. Das Fenster wird nur übernommen, wenn beide Server-Felder
   vorhanden sind und `introEndSec > introStartSec` gilt.
4. **Sichtbarkeit:** Der Button wird genau dann gerendert, wenn
   `pos >= introStartMs && pos < introEndMs` — dieselbe Bedingung wie im Browser. Kein
   zusätzliches Flag, keine Kopplung an die Sichtbarkeit der ControlBar.
5. **Position:** Die verglichene Position stammt aus `wrappedPlayer.currentPosition` (absolut,
   inkl. `virtualOffset`), **nicht** aus `exoPlayer.currentPosition`.
6. **Seek:** Der Klick setzt nur `introSeekRequestMs`; ausgeführt wird der Sprung über
   `wrappedPlayer.seekTo(introEndMs)`. Es wird **kein** `play()`/`pause()` aufgerufen.
7. **Poll-Kosten:** Der 500-ms-Ticker läuft nur, wenn `state.introEndMs != null`
   (`positionTickEnabled`). Bei Items ohne Erkennung ist das Verhalten der App bitgleich zu vorher.
8. **Text/Optik:** Text exakt „Vorspann überspringen", Icon `Icons.Filled.SkipNext`, Hintergrund
   `GoldfishOrange`, abgerundete Pille, unten rechts im Videobild über der Steuerleiste.
9. **Z-Reihenfolge:** Das „Nächste Folge"-Overlay wird weiterhin **nach** dem Intro-Button
   gerendert und liegt damit darüber.
10. **Scope:** Keine Änderungen ausserhalb von `Models.kt`, `PlayerViewModel.kt`,
    `PlayerScreen.kt`, `app/build.gradle.kts` (+ dieser Plandatei). `git status` zeigt keine
    weiteren Dateien.
11. **Versionierung:** `versionCode = 110`, `versionName = "1.3.4"`.
12. **Hinweis an den User** in der Abschlussmeldung: dieselbe Feld-Ergänzung muss im
    **FireTV-Repo** (`data/model/Models.kt`) separat nachgezogen werden.

---

## Test/Verifikationsschritte

Alle Befehle aus dem Repo-Root ausführen.

1. **Kompilieren (Pflicht, muss grün sein):**
   ```bash
   ./gradlew :app:assembleDebug
   ```
   Erwartet: `BUILD SUCCESSFUL`. Bei Moshi-Fehlern zu `ItemJsonAdapter`: sicherstellen, dass die
   neuen `Item`-Felder nullable mit Default sind, dann
   ```bash
   ./gradlew :app:clean :app:assembleDebug
   ```

2. **Nur die Änderung prüfen:**
   ```bash
   git status --short
   git diff
   ```
   Erwartet: ausschliesslich `app/src/main/kotlin/com/goldfish/android/data/model/Models.kt`,
   `app/src/main/kotlin/com/goldfish/android/ui/player/PlayerViewModel.kt`,
   `app/src/main/kotlin/com/goldfish/android/ui/player/PlayerScreen.kt`, `app/build.gradle.kts`
   (und `INTRO_SKIP_PLAN.md`).

3. **Keine Secrets/keine internen Adressen** im Diff (Repo ist öffentlich):
   ```bash
   git diff | grep -nEi "192\.168|10\.[0-9]+\.|@gmail|password|token|\.jks"
   ```
   Erwartet: keine Treffer.

4. **Manueller Gerätetest** (Samsung-Tablet oder Pixel-Tablet-Emulator), falls verfügbar:
   ```bash
   ./gradlew :app:installDebug
   ```
   - Eine Serienfolge öffnen, für die der Server einen Vorspann erkannt hat: Button erscheint
     pünktlich zum Vorspann-Beginn, Klick springt ans Vorspann-Ende, Button verschwindet.
   - Dieselbe Folge in **Transcode**-Qualität (Zahnrad → Transcode, z. B. 1080p) öffnen und
     vorher an eine Stelle vor dem Vorspann seeken: Button erscheint an derselben **absoluten**
     Filmposition (Test für `virtualOffset`).
   - Ein Video **ohne** Erkennung öffnen: kein Button, Wiedergabe unverändert.
   - Während der Button sichtbar ist pausieren: Button bleibt; Klick seekt, Wiedergabe bleibt
     pausiert.
   - Mit aktivem „Nächste Folge automatisch starten" eine Folge zu Ende laufen lassen: Wechsel in
     die nächste Folge; deren Vorspann-Button erscheint erst zu deren eigenem Fenster, nie sofort.

5. **Nicht ausführen** (nur auf ausdrückliche Anweisung des Users): `./gradlew bundleRelease`,
   `git push`, Play-Console-Upload.
