# Changelog

## 5.7.6 - 2026-10-09
- Telemetry measures what the user perceives: decision-to-speaker delay per
  alert edge (`AudioTrack.getTimestamp`), audio route / performance mode /
  buffer / underruns / alarm volume, decision-to-draw time, UI stalls > 100 ms,
  BLE lines vs packets vs shown readings and longest gap.
- Long-press on the live screen logs `USER MARK` (vibrate + toast).
- Diag log, lifecycle, settings on resume, demo stop, power save, updates and
  telemetry send failures are logged.

## 5.7.5 - 2026-10-09
- Per-install id and editable device name in settings, sent with every
  telemetry batch, to tell phones apart.

## 5.7.4 - 2026-10-09
- Field log to Supabase (`telemetry` table, insert-only key).

## 5.7.3 - 2026-10-09
- Fix: no-echo (`-1`) readings cleared the screen while the sound kept its
  state - the reported 1-2 s sound/screen lag. Sound and screen now change
  together; `-1` after a reading below 30 cm = blind zone = alert.
- Low-latency audio: native rate, low-latency mode, ~20 ms queue,
  `USAGE_ALARM`.
- Demo emits `-1` below 22 cm like the real sensor.
- Per-packet diagnostic logging limited to the first packets.

## 5.7.1 - 5.7.2 - 2026-10-07
- Test releases for the self-update (title text only).

## 5.7 - 2026-10-07
- First release with in-app self-update from GitHub Releases and versioning
  (`version.properties`, `release.sh`); version shown in the app.
- Five selectable alert sounds (steady, fast beeps, hi-lo, yelp, triple beep),
  loud; tone frequency setting removed.
- Alert threshold range 0.1-2 m (5 cm steps).

## 5.6 and earlier
- Original source (single continuous tone, frequency setting, 0.2-6 m range).
