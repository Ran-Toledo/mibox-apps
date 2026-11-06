# Screensaver concepts: Weather and Now Playing

Proposed layouts for two new screensaver modes (not implemented yet). Drawn at 1280×720 (16:9; ×1.5 for a 1080p TV),
Roboto throughout to match the built-in font picker. Live canvas (private to the owner):
https://claude.ai/artifact/9jCjz1mnUkxwvKLTFcFAEa

The `.dc.html` files and `canvas.json` are the canvas's source, saved here for reference; they need the design canvas
runtime to render and are not part of the APK.

| File | Concept |
|---|---|
| `Main.dc.html` | **Weather A, forecast board**: thin 200 sp temperature and conditions center-left, small clock top right, next 3 days with temperature range bars on the right, next 6 hours with rain chance along the bottom |
| `WeatherGlance.dc.html` | **Weather B, clock first**: today's big clock and date, plus one weather line (icon, temperature, "Rain from 16:00", high/low) |
| `NowPlaying.dc.html` | **Now Playing A, album focus**: large cover left; title, artist, album, progress and "Up next" right; background tinted from the cover, darkened |
| `NowPlayingRibbon.dc.html` | **Now Playing B, clock + ribbon**: big clock stays primary; slim bottom ribbon with small cover, track, progress |

## Implementation notes
- **Default to B** for both, with A as an option: B stays close to the current look and degrades well when weather is
  stale or nothing is playing.
- **Burn-in**: dark backgrounds, the existing drift moves the whole layout, dim bright elements (cover, progress bar)
  after a few minutes.
- **Weather data**: Open-Meteo (no API key); refresh every 15–30 min; location set in settings (city or lat/long).
  Needs the `INTERNET` permission.
- **Now Playing data**: `MediaSessionManager.getActiveSessions` with a `NotificationListenerService`; grant notification
  access once with `adb shell cmd notification allow_listener dev.mibox.screensaver/<ListenerClass>`. Fall back to the
  plain clock when no session is playing.
