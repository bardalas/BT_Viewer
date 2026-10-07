# BT Viewer

Ground-proximity gauge. Arduino Nano + JSN-SR04T ultrasonic + HC-08 BLE bridge,
reporting to an Android app with a single crossing threshold and a continuous
alert tone.

## Build the APK

No Gradle, no Android Studio, no Google servers. The toolchain is fetched from
GitHub on first run.

```bash
./build.sh          # -> out/BTViewer-debug.apk
```

Needs a JDK on PATH. Everything else downloads itself into `.toolchain/`.

## Keep btviewer.keystore

Every build must be signed with the same key or Android refuses to install the
new APK over the old one — and it fails quietly, so the update simply never
runs and every fix looks like it did nothing. `build.sh` refuses to mint a
throwaway key rather than let that happen silently.

## Layout

| file | role |
|---|---|
| `BleLink.java` | scan, connect, subscribe, line assembly |
| `Alerter.java` | threshold state, drives the tone |
| `Beeper.java` | audio synthesis - the siren |
| `AltitudeView.java` | fixed ground, moving aircraft, threshold line |
| `MainActivity.java` | device list and live screen |
| `ConfigActivity.java` | the three settings |
| `Config.java` | model and persistence |
| `DemoSource.java` | simulated readings, no hardware needed |
| `Diag.java` | on-screen log |
| `Ui.java` | palette, fonts, metrics |
| `firmware/` | the Arduino sketches |

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

## Firmware

`firmware/AJ_SR04M_D3D4/` matches this wiring (SoftwareSerial on D2/D3). `CYCLE_MS 50`
keeps the sensor inside its 50 ms datasheet cycle; faster overruns the echo and
produces readings that stick.
