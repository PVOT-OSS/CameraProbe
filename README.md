<!--
SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
SPDX-License-Identifier: Apache-2.0
-->

# CameraProbe

An Android app that finds out what a phone's camera stack lets a **regular, third-party app**
actually use. It covers:
- the HAL: streams, vendor tags, sensor modes, metadata
- the OEM shared libraries and accelerators the ROM ships
- the extensions

Stock camera apps use all of these, while generic camera apps leave them untouched.

It runs as a normal `untrusted_app` with only the CAMERA permission, so every result reflects what a
Play/F-Droid camera app could do on that device. Pure Camera2, no dependencies, Android 9 (API 28)
and newer. Probes that need a newer API report themselves as skipped.

## Run

Connect one device over adb, then:

```sh
scripts/run.sh                                         # build, install, run every probe, collect
scripts/run.sh -p env,keys,libraries                   # only some probes
scripts/run.sh -s SERIAL -o ~/camera-research          # pick a device and an output root
scripts/run.sh --images                                # also keep the captured JPEGs (private!)
```

**Where the results go:** `results/<manufacturer>-<device>-<buildId>/<timestamp>/`.

**What a run contains:**
- `report.txt`, the main report
- `run.json`, the device identity
- `props.txt`
- per-camera `keys-<id>.txt`, `vendor-keys.txt`, `shared-libraries.txt` and `public-native-libraries.txt`
- `vendor-results-*.txt`
- `hal-log.txt`: only camera-related logcat lines (from our tag, the camera service, the camera
  provider, crashes, and the active profile's HAL tags)
- `dumpsys-media.camera.txt`: static metadata only, with the other-app history cut off

**Before running:** point the camera at a lit scene a few metres away. Close or dark subjects make
some HALs stay on one lens and skew the zoom and multi-resolution probes. Keep the screen unlocked
during the run.

## Probes

| probe | finds out |
|---|---|
| `env` | device, SoC, build, camera-related system properties, cameras (logical and physical), hardware level, capabilities, vendor key families, active profiles |
| `keys` | every characteristics, request, result, session and physical-request key per camera; vendor characteristic values; the HAL's default value of every vendor request key per template |
| `extensions` | the OEM extensions (Night/HDR/Bokeh/…) offered through Camera2, with sizes, keys, postview and progress |
| `libraries` | all shared libraries the ROM declares (discovery), the ones linked into us, the public native libraries, whether each declared native library loads, and which accelerator/codec device nodes we can open |
| `vendor` | profile-driven: A/B session flags, single vendor-tag request experiments (accepted? echoed?) and unadvertised result tags |
| `streams` | for every advertised format at its largest size, plus max-resolution mode, each physical lens and pair of lenses, PDAF/dual-pixel depth streams and vendor stream use cases: does the session configure, and does each stream actually deliver? Also multi-resolution RAW across zoom |
| `burst` | full-res RAW10/RAW16 30-frame manual burst: sustained fps and per-frame calibration metadata (noise profile, shading map, black/white level, OIS) |
| `zoom` | the lens-switch map of each logical multi-camera (zoom ratio → active physical camera) |
| `jpeg` | real full-size JPEG latency per camera |
| `reprocess` | advertised reprocessing inputs only, plus `CONTROL_ENABLE_ZSL` |

## Teaching it about a new OEM

1. **Run it once** on the device. Then read the run's files:
   - `vendor-keys.txt` and `keys-*.txt` show the vendor tags and their default values.
   - `shared-libraries.txt` and `public-native-libraries.txt` show which libraries exist.
2. **Libraries:** add the interesting names to `AndroidManifest.xml` as `<uses-library>` or
   `<uses-native-library>` with `required="false"`. A library can only be linked once it is declared,
   and `required="false"` keeps the APK installable everywhere.
3. **Vendor tags:** add a profile `app/src/main/assets/profiles/<oem>.json`. Copy
   `google-pixel.json` as the template.
   - `tagPrefixes` decides when the profile is active.
   - `halLogTags` lists which logcat tags the host script keeps.
   - The rest are `abFlags`, `requestExperiments` and `resultProbes`.
   - Tag types follow the HAL vendor tag table: `byte`, `int`, `long`, `float`, `double`, and their
     `[]` array forms.

## Safety

- **Never probe unadvertised reprocess inputs or exotic session shapes in the default run.** A RAW10
  reprocess input aborted the Pixel 9 camera provider (SIGABRT, recovered after about 2 s). New
  risky probes must be opt-in.
- **Captured photos and full logcats can show the user's surroundings and other apps.** They are
  not collected unless `--images` is given, and `hal-log.txt` is filtered.

## License

Apache-2.0, see [LICENSE.md](LICENSE.md). The repository follows REUSE (`REUSE.toml`).
