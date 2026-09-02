# Goldfish Android — Third-Party Notices

This app is distributed under the **GNU General Public License v3.0** (see
`LICENSE`) — a direct consequence of linking `nextlib-media3ext`/
`nextlib-mediainfo` (GPL-3.0, see below). If you redistribute this app or a
modified version, you must make the complete corresponding source code
available to recipients, under GPL-3.0 or a GPL-3.0-compatible license.

## Codec/decoder libraries

| Component | License | Upstream |
|-----------|---------|----------|
| `nextlib-media3ext` / `nextlib-mediainfo` | **GPL-3.0** | <https://github.com/anilbeesetti/nextlib> |
| `libvlc-all` (VideoLAN) | LGPL-2.1 | <https://code.videolan.org/videolan/vlc-android> |

`libvlc-all` is VideoLAN's official Android AAR. Per VideoLAN's own LGPL
guidance for platforms without dynamic re-linking (Android apps are
statically packaged), attribution + inclusion of the license text (this
file) satisfies LGPL-2.1 compliance — see
<https://wiki.videolan.org/LibVLC_licensing/>. Do not use the "VLC" name or
cone-logo as this app's own branding (trademark, not covered by the LGPL).

## AndroidX / Jetpack (all Apache-2.0)

Compose (UI, Material3, Navigation), Lifecycle, Activity, Window,
DocumentFile, Room, DataStore, WorkManager, Media3 (ExoPlayer, HLS, UI,
Session, OkHttp-Datasource) — all Apache License 2.0,
<https://source.android.com/setup/start/licenses>.

## Other libraries (all Apache-2.0)

| Component | Upstream |
|-----------|----------|
| Hilt (DI) | <https://github.com/google/dagger> |
| OkHttp / OkHttp Logging-Interceptor | <https://square.github.io/okhttp/> |
| Retrofit + Moshi-Converter | <https://square.github.io/retrofit/> |
| Moshi | <https://github.com/square/moshi> |
| Coil (Compose + OkHttp) | <https://coil-kt.github.io/coil/> |

## TMDB API

This app uses metadata proxied through the Goldfish server, which in turn
uses the TMDB API but is not endorsed or certified by TMDB. See
<https://www.themoviedb.org/about/logos-attribution>.

---

If you find a license or attribution error, please open an issue.
