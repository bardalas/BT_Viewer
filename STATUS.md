# Status - 2026-10-16

Current release: **5.7.9**. Customer reports the sound/screen lag is gone.

## Next
- **5.7.8 released (2 m alarm fix, strict alert zone).** 5.7.7 was never
  published. Optional: reflash the bridge firmware (5.7.7 firmware change).
- **GitHub Action `release`** needs the repository secret `KEYSTORE_B64`
  (base64 of btviewer.keystore) - not set, so every push of
  `version.properties` gives a failed run. Add the secret or remove the
  workflow; `./release.sh` from this PC works either way.
- **Airplane-mode test by the customer (week of 2026-10-12).** Bluetooth must
  be turned back on after enabling airplane mode. Afterwards export the
  `telemetry` table to CSV for analysis; separate our phone from the
  customer's by the `device` column (`[installId]` / name).

## Open decisions
- **Persist telemetry to a file** so a whole offline flight survives (today:
  memory only, last ~18 min, lost if the app is closed before signal returns).
- **Unused Make webhook** "BTViewer telemetry" (eu1.make.com, hook 3869861)
  from an abandoned approach - can be deleted.
- Rows from test runs in `telemetry` (`sid = test`, device `emulator-test`)
  can be deleted.

## Notes
- `btviewer.keystore` is not in git; keep a backup. Without it no update can be
  released to installed apps.
- The Supabase project URL and publishable key are in `Telemetry.java`
  (public by design; insert-only RLS).
