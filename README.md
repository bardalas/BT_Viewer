# BT Viewer

Ground-proximity gauge. Arduino Nano + JSN-SR04T ultrasonic + HC-08 BLE bridge,
reporting to an Android app with a single crossing threshold and a selectable
alert sound. The app updates itself from GitHub Releases and sends a field log
to Supabase.

See [CHANGELOG.md](CHANGELOG.md) for what changed per version and
[STATUS.md](STATUS.md) for open items.

## Build the APK

No Gradle, no Android Studio project.

```bash
./build.sh          # -> /tmp/btviewer-out/BTViewer-debug.apk
```

- **Windows (Git Bash):** uses the local Android SDK
  (`%LOCALAPPDATA%\Android\Sdk`, newest build-tools and platform) and Android
  Studio's bundled JDK. Nothing is downloaded. Needs `python` on PATH.
- **Linux/macOS:** needs a JDK; build-tools and `android.jar` are fetched from
  GitHub into `.toolchain/` on first run.
- Output goes to `/tmp/btviewer-out` (override with `OUT=`). Building inside the
  Google Drive folder broke once: Drive locked a freshly deleted `out/`.
- Version comes from `version.properties`.

## Keep btviewer.keystore

Every build must be signed with the same key or Android refuses to install the
new APK over the old one - and the in-app updater fails the same way.
`build.sh` refuses to mint a throwaway key. The keystore is **not in git** (the
repo is public): keep a backup of it outside this folder.

## Releasing an update

```bash
./release.sh          # patch: 5.7.6 -> 5.7.7
./release.sh minor    # 5.7.6 -> 5.8
./release.sh 6.0      # exact
```

Bumps `version.properties` and the manifest, builds, commits, tags `vX.Y`,
pushes, and creates the GitHub release with the APK attached. Needs `gh`
logged in and `btviewer.keystore` present. Commit your source changes first.

Installed apps pick the release up on their next open (`Updater.java`): they
read `releases/latest` of `bardalas/BT_Viewer`, and if the tag is newer than the
installed version they stream the `.apk` into a `PackageInstaller` session.
Android installs silently only when the app is its own installer of record
(Android 12+), so the first self-update after a manual install shows one
system confirmation; later ones are silent. The installed version is shown
under the title and in settings.

## Field log (telemetry)

`Telemetry.java` posts a batch every minute and when the app goes to the
background, as one row of the Supabase table `telemetry` (create it with
[supabase/telemetry.sql](supabase/telemetry.sql)). The key in the app is the
public/publishable key; row-level security allows INSERT only, so it cannot
read anything. Read the rows in the Supabase Table Editor (or export CSV).

Columns: `sid` (random per app session), `seq` (batch number), `app`
(version), `device` (`<name> [installId] model / Android n`), `t_ms`, `log`.

`log` lines:

- `t cm alert lag` - one per reading. `t` = ms since session start, `cm` = -1
  for no echo, `alert` 0/1, `lag` = ms the reading waited between the radio and
  the main thread.
- `# t ...` - events, including:
  - `ALERT ON` / `alert off` with height and app-side audio queue
  - `audio[main] ON audible +Nms (ts)` - decision to speaker, measured with
    `AudioTrack.getTimestamp` (`est` = estimate when unavailable)
  - `screen RED drawn +Nms` - decision to the frame that draws it
  - `ui stall Nms` - main thread blocked over 100 ms
  - `stats ble lines=.. packets=.. maxGap=.. shown=.. audio[main] q=.. underruns=.. route=.. vol=..` - every minute
  - `audio[...] start ...` / `route -> ...`, `ble state=`, `watchdog: ...`,
    `resume cfg ...`, `pause`, `stop`, `demo start/stop`, `update a -> b`,
    `diag: ...` (the on-screen diagnostic log), `telemetry send failed: ...`
  - `USER MARK` - the user long-pressed the live screen ("it just happened");
    the phone vibrates and the batch is sent 10 s later.

Telling phones apart: each install has a fixed 6-hex `installId`; an optional
name can be typed in settings ("שם המכשיר (ללוגים)").

Offline (no signal, airplane mode): sends fail quietly and retry each minute;
lines stay in memory, capped at ~400k chars (about the last 18 minutes at
20 Hz). Lines not yet sent are lost if the app is closed or killed.

## Behaviour worth knowing

- **Alert:** on at `cm <= threshold`, off only above `threshold + hysteresis`
  (default 8 cm). In a slow climb the sound outlasts the line crossing by
  hysteresis / speed. Threshold range 0.1-2 m.
- **No echo (`-1`):** sound and screen always change together. After a reading
  below 30 cm, `-1` means the sensor is blind near the ground: alert on, screen
  shows `<0.3`. Otherwise the last state is held; after 1.5 s without an echo
  both clear. No data at all for 1.5 s silences the alert.
- **Audio:** five sounds (steady, fast beeps, hi-lo, yelp, triple beep), device
  native rate, low-latency mode, ~20 ms queue, `USAGE_ALARM` (alarm volume).
- **Demo mode** (settings) runs the same parse/alert/audio path and emits `-1`
  below 22 cm like the real sensor.
- **Airplane mode** usually also turns Bluetooth off - turn it back on, or the
  sensor link is gone.

## Layout

| file | role |
|---|---|
| `BleLink.java` | scan, connect, subscribe, line assembly, link stats |
| `Alerter.java` | threshold state, drives the sound |
| `Beeper.java` | audio synthesis, five alert sounds, latency measurement |
| `AltitudeView.java` | fixed ground, moving aircraft, threshold line |
| `MainActivity.java` | device list, live screen, watchdog, user mark |
| `ConfigActivity.java` | threshold, hysteresis, sound, demo, device name |
| `Config.java` | model and persistence (incl. installId) |
| `DemoSource.java` | simulated readings, no hardware needed |
| `Diag.java` | on-screen log (also mirrored to telemetry) |
| `Telemetry.java` | field log to Supabase |
| `Updater.java`, `UpdateReceiver.java` | self-update from GitHub Releases |
| `Ui.java` | palette, fonts, metrics |
| `firmware/` | the Arduino sketches |
| `supabase/telemetry.sql` | telemetry table and insert-only policy |
| `web-demo/index.html` | browser mock of the live screen (demo profile, sounds) |
| `build.sh`, `release.sh`, `version.properties` | build and release |

## Hard-won details

**Wiring.** The HC-08 is on D2/D3, not the hardware UART. `Serial.println()`
only ever leaves pins 0/1, so the sketch must use `SoftwareSerial ble(2, 3)`.
Printing to `Serial` sends the module nothing at all, and every symptom of that
looks like a radio fault.

**Uploads.** This Nano clone needs Tools -> Processor -> *ATmega328P (Old
Bootloader)*. Anything else gives `not in sync`.

**Wire format.** A bare integer and a newline: `210\n`. `-1` means no echo. The
parser has a fast path for digit-only packets and falls back to newline
splitting; anything with letters or units is rejected.

**Scanning.** `startScan` takes `null` for no filtering. An empty
`List<ScanFilter>` registers a filter that matches nothing and returns zero
results in silence.

**Permissions.** `BLUETOOTH_SCAN` without `neverForLocation` makes the platform
gate scan delivery on `ACCESS_FINE_LOCATION`, which must therefore be declared
without a `maxSdkVersion` and requested at runtime. Otherwise the scan succeeds
and returns nothing.

**Notifications.** Subscribing needs the CCCD (`0x2902`) write, not just
`setCharacteristicNotification`. Watch for `cccd result ... status=0 OK`.

**Diagnostics stay out of the data path.** An earlier version logged from the
GATT callback straight into a View, which threw off the binder thread and
destroyed every incoming packet - while the log itself still looked healthy.

**The 1-2 s sound/screen lag (fixed in 5.7.3/5.7.4).** Only with the real
sensor, never in demo. `-1` cleared the picture while the tone kept its old
state, and demo never sent `-1`. Fixed by deciding sound and picture together;
demo now emits `-1` too.

## Firmware

`firmware/AJ_SR04M_D3D4/` matches this wiring (SoftwareSerial on D2/D3). `CYCLE_MS 50`
keeps the sensor inside its 50 ms datasheet cycle; faster overruns the echo and
produces readings that stick. The sensor is blind below roughly 20-25 cm.
