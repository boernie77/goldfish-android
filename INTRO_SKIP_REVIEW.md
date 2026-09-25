# Review: „Vorspann überspringen" (Intro-Skip) — Umsetzung des `INTRO_SKIP_PLAN.md`

**Datum:** 2026-09-25 · **Branch:** `feature/intro-skip-clients` · **Reviewer:** Opus 5 (Code-Review,
kein Commit, kein Push)

## Status

**OK mit kleinen Fixes.**

Der Plan wurde vollständig, an den vorgesehenen Stellen und ohne Scope-Creep umgesetzt. Zwei kleine
UI-Korrektheitsprobleme habe ich selbst behoben (siehe unten). Es gibt keine gefundenen Architektur-
oder Funktionsfehler. **Ein echter Gradle-Build steht noch aus** (siehe „Offene Punkte").

---

## Geprüfter Umfang

`git status` / `git diff` zeigen genau die vier im Plan vorgesehenen Dateien plus die Plandatei:

| Datei | Änderung |
|---|---|
| `app/build.gradle.kts` | `versionCode 109 → 110`, `versionName "1.3.3" → "1.3.4"` |
| `data/model/Models.kt` | `introStartSec` / `introEndSec` an `Item` (nullable, Default `null`) |
| `ui/player/PlayerViewModel.kt` | `PlayerState`-Felder, Ableitung + Reset in `loadItem`, Reset in `switchToNextEpisode` |
| `ui/player/PlayerScreen.kt` | Positions-Ticker, Seek-Kanal, Overlay-Bedingung, `IntroSkipButton` |
| `INTRO_SKIP_PLAN.md` | untracked (Plandatei, erwartet) |

`data/api/GoldfishApi.kt` ist unverändert — kein neuer Endpoint, die drei Server-API-Quirks aus
`AGENTS.md` bleiben unangetastet (Resume kommt weiterhin aus dem separaten `/resume`-Endpoint,
`getItem` liefert den Body unverändert durch, also gehen die neuen Felder nicht verloren).

### Akzeptanzkriterien des Plans (1–12)

| # | Kriterium | Ergebnis |
|---|---|---|
| 1 | Modellfelder nullable mit Default, `GoldfishApi.kt` unverändert | erfüllt |
| 2 | `PlayerState.introStartMs/introEndMs` | erfüllt |
| 3 | Befüllung in **beiden** Erfolgszweigen + Reset in `loadItem`/`switchToNextEpisode`, Plausibilität `end > start` | erfüllt |
| 4 | Sichtbarkeit `pos >= start && pos < end`, kein Dismiss-Flag, keine Kopplung an die ControlBar | erfüllt |
| 5 | Position aus `wrappedPlayer.currentPosition` (inkl. `virtualOffset`) | erfüllt |
| 6 | Klick setzt nur den Seek-Wunsch, Ausführung über `wrappedPlayer.seekTo`, kein `play()`/`pause()` | erfüllt |
| 7 | Ticker nur bei `state.introEndMs != null` | erfüllt |
| 8 | Text/Icon/Farbe/Position wie vorgegeben | erfüllt (`Icons.Filled.SkipNext` ist verfügbar — `material-icons-extended` ist in `app/build.gradle.kts:102` eingebunden) |
| 9 | „Nächste Folge"-Overlay wird **nach** dem Intro-Button gerendert, liegt also darüber | erfüllt |
| 10 | Kein Scope-Creep | erfüllt — keine Änderung an Repositories, Room, `strings.xml`, LocalPlayer, Musik |
| 11 | `versionCode = 110`, `versionName = "1.3.4"` | erfüllt |
| 12 | Hinweis auf das FireTV-Repo | **war offen** — hier nachgeholt, siehe „Offene Punkte" |

### Weitere geprüfte Punkte ohne Befund

- **Imports:** `SkipNext` und `kotlinx.coroutines.delay` neu und tatsächlich benötigt; `PaddingValues`,
  `navigationBarsPadding`, `RoundedCornerShape`, `FontWeight`, `Color`, `rememberUpdatedState` sind
  über die bestehenden Wildcard-/Einzelimports abgedeckt. Keine doppelten Importe.
- **`Modifier.align(Alignment.BottomEnd)`** ist gültig: die Aufrufstelle liegt im `when`-Block
  innerhalb des äusseren `Box` (gleiche `BoxScope` wie der bestehende Back-Button).
- **Item-Modell:** Anhängen zweier Parameter mit Default bricht weder bestehende Aufrufer (es gibt
  keine positionsbasierten `Item(...)`-Konstruktionen) noch die in Room als JSON abgelegten Items.
  Keine Room-Migration nötig — korrekt, `Item` ist keine Entity.
- **Namenskonvention Moshi:** `Item` benutzt camelCase ohne `@Json`, ausser wo der Server abweicht
  (`runtimeMin`, `continue`). `introStartSec`/`introEndSec` passen in dieses Muster.
- **Threading:** Ticker und Seek laufen im `LaunchedEffect` auf dem Main-Dispatcher — ExoPlayer wird
  also vom richtigen Thread bedient.
- **`changeQuality`** lädt das Item nicht neu und nullt das Fenster nicht → der Button funktioniert
  nach einem Qualitätswechsel weiter. Korrekt.
- **Recompose-Kosten:** Der 500-ms-Tick rekomponiert `PlayerScreen`/`GoldfishPlayer`. Das ist billig,
  weil der `AndroidView` **keinen** `update`-Block hat (nur `factory`) und alle Player-Objekte
  `remember`t sind — es wird nichts neu aufgesetzt.
- **Secrets:** `git diff | grep -Ei "192\.168|10\.[0-9]+\.|@gmail|password|token|\.jks"` → keine Treffer.
  Keine echten Namen, Hostnamen oder Adressen im Diff (Repo ist öffentlich).

---

## Gefundene Probleme und was ich behoben habe

### 1. Button blitzte beim Resume auf, wenn der Vorspann bei Sekunde 0 beginnt (behoben)

`absolutePositionMs` startete mit `0L` und wurde bei jedem Folgenwechsel wieder auf `0L` gesetzt.
`0` ist aber eine **gültige** Position: liefert der Server ein Fenster ab Sekunde 0 (Folge ohne
Cold Open — plausibel), war die Bedingung `pos >= introStartMs` schon erfüllt, **bevor** der erste
echte Tick eintraf. Beim Fortsetzen mitten im Film erschien der Button dann für bis zu 500 ms,
obwohl die Wiedergabe längst hinter dem Vorspann liegt.

**Fix** (`PlayerScreen.kt`): `absolutePositionMs` ist jetzt `Long?` und startet/resettet auf `null`
(= „noch kein Tick"), die Render-Bedingung verlangt zusätzlich `introPosMs != null`. Vor dem ersten
gemeldeten Tick gibt es damit garantiert keinen Button. Verhalten danach unverändert.

### 2. Button blieb nach dem Klick bis zu 500 ms stehen (behoben)

Nach dem Klick sprang das Bild sofort, der Button verschwand aber erst mit dem nächsten Tick — bis
zu einer halben Sekunde später, was wie ein hängender Button aussieht (und zu einem zweiten,
unnötigen Klick einlädt).

**Fix** (`PlayerScreen.kt`): Der `onClick` zieht `absolutePositionMs` optimistisch auf `introEndMs`
mit; der nächste echte Tick korrigiert den Wert ohnehin. Keine zusätzliche Zustandsvariable, kein
„weggeklickt"-Flag — die Browser-Parität aus dem Plan bleibt erhalten.

*Beide Fixes sind rein lokal in `PlayerScreen.kt` (Deklaration + Render-Bedingung), keine
Signatur-, ViewModel- oder Modelländerung.*

---

## Bewusst **nicht** geänderte Beobachtungen

1. **Kein Clamp von `introEndMs` auf die Item-Dauer.** Meldete der Server ein Fensterende jenseits
   des Filmendes, würde beim Transcode eine ffmpeg-Session hinter dem Ende gestartet. Der Server
   liefert solche Fenster nach Plan-Lage nicht; ein Clamp wäre zusätzliche, ungeplante Logik. Nur
   als Restrisiko notiert.
2. **Ticker läuft über den ganzen Film**, nicht nur bis zum Fensterende. Kosten sind
   vernachlässigbar (siehe oben), ein Abschalten wäre ungeplante Zusatzlogik.
3. **`mutableStateOf<Long?>` statt `mutableLongStateOf`** — die Compose-Lint-Regel
   `AutoboxingStateCreation` ist eine Warnung, nicht ein Fehler; das Projekt hat weder
   `allWarningsAsErrors` noch eine strengere Lint-Konfiguration. Durch den `Long?`-Typ ist
   `mutableLongStateOf` hier ohnehin nicht anwendbar.
4. **Pending-Seek beim Verlassen des Player-Zweigs.** Klickt man den Button und wechselt im selben
   Frame die Qualität (`isLoading = true` tauscht den `when`-Zweig, der `GoldfishPlayer` wird
   entsorgt), könnte ein noch nicht quittierter Seek-Wunsch beim Wiedereintritt erneut feuern. Das
   Zeitfenster ist ein Frame und das Ergebnis identisch (Sprung ans Vorspannende) — eine
   Absicherung wäre mehr Risiko als Nutzen.

---

## Offene Punkte für die menschliche Abnahme

1. **Gradle-Build steht aus.** In dieser Sandbox ist keine JDK-/Android-SDK-Toolchain vorhanden
   (`java`/`javac` fehlen, keine `local.properties`), ein Build war nicht möglich. Die Prüfung war
   rein statisch (Typen, Nullability, Kotlin-Syntax, Imports, Scopes). **Bitte nachholen:**
   ```bash
   ./gradlew :app:assembleDebug
   ```
   Bei Moshi-Fehlern zu `ItemJsonAdapter`: `./gradlew :app:clean :app:assembleDebug`.
2. **Server-Feldnamen verifizieren.** Das Server-Repo (`~/Projekte/Videoplayer/`) war hier nicht
   erreichbar, die JSON-Namen `introStartSec`/`introEndSec` konnten also **nicht** gegen
   `internal/api/introskip.go` bzw. den `GetItemFor`-Handler geprüft werden. Weichen die Go-JSON-Tags
   ab (z. B. snake_case), bleiben die Felder still `null` und der Button erscheint nie — genau der in
   `AGENTS.md` beschriebene stille Bruch. Einmal ein `GET /api/items/{id}` gegen den echten Server
   ansehen genügt.
3. **Gerätetests (nur am Tablet/Emulator möglich):**
   - Folge **mit** erkanntem Vorspann: Button erscheint pünktlich, Klick springt ans Fensterende,
     Button verschwindet und kommt nicht zurück.
   - Dieselbe Folge in **Transcode**-Qualität, vorher vor den Vorspann seeken: Button an derselben
     **absoluten** Filmposition (Test für `virtualOffset`). Dabei speziell darauf achten, ob der
     Button nach dem Sprung wieder auftaucht — bei HLS kann ein Seek je nach Segmentgrenze leicht
     vor dem Ziel landen.
   - Video **ohne** Erkennung: kein Button, Verhalten unverändert.
   - Pausieren während der Button sichtbar ist: Button bleibt, Klick seekt, bleibt pausiert.
   - Autoplay der nächsten Folge: der Button der neuen Folge erscheint erst zu deren eigenem
     Fenster, nie sofort.
   - Optik/Ergonomie: Sitzt die Pille unten rechts gut und überdeckt sie die Media3-Steuerleiste
     nicht störend (aktuell `bottom = 96.dp` + `navigationBarsPadding()`)?
4. **FireTV-Repo nachziehen** (Akzeptanzkriterium 12, bewusst nicht Teil dieser Umsetzung):
   `introStartSec`/`introEndSec` müssen in `data/model/Models.kt` von
   `github.com/boernie77/goldfish-firetv` separat ergänzt werden — der Client-Code ist 1:1 kopiert
   und synchronisiert sich nicht automatisch. Gleiches gilt bei Bedarf für Apple
   (`GoldfishCore/GoldfishClient.swift`) und Linux (`goldfish_linux/api.py`).
5. **Release:** Es wurde nichts committet, nichts gepusht, kein `bundleRelease` gebaut und nichts in
   die Play Console geladen. `versionCode 110` ist bereits gesetzt — bei einer weiteren AAB aus
   diesem Stand muss er erneut hochgezählt werden.
