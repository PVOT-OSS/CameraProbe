<!--
SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
SPDX-License-Identifier: Apache-2.0
-->

# Privacy Policy — Camera Probe

_Effective 15 November 2026_

Camera Probe (package `org.prauga.cameraprobe`) is a camera diagnostics app published by Saalim
Quadri for PVOT-OSS. This policy explains what the app does with your data.

**In short: the app doesn't collect, send, share or sell any personal data. It has no accounts, no
ads, no analytics and no tracking, and it has no permission to use the internet. Results leave your
phone only when you export or share them yourself.**

## What a run records

When you tap **Run**, the probes you chose record what your phone's camera system offers to apps:

- **Your device's identity**: manufacturer, brand, model, device and product names, hardware, chip,
  Android version, security patch level and build fingerprint.
- **Camera-related system properties**: only those about the camera, the chip, the board and the
  build, not the whole property list.
- **Camera details**: each camera's capabilities, settings, supported formats and sizes, zoom
  ranges, vendor tags and extensions.
- **Libraries**: which shared and native libraries your phone's software makes available to apps.
- **Timings and errors** from opening the cameras and taking test captures.

The app doesn't record your phone number, accounts, contacts, location, serial number or other
identifiers.

## The camera and test photos

Probes marked "Opens the camera" open each camera briefly, so whatever the camera points at is
captured. Most frames are thrown away straight away. The vendor probe keeps a few test photos so the
results can be checked; they are saved with the results.

**Test photos are never included when you export or share the results.**

## Where results are kept

The latest run is kept in the app's own storage (`Android/data/org.prauga.cameraprobe/files/`). Each
new run deletes the previous results and photos. The app also remembers which probes you chose.
Everything is deleted when you clear the app's storage or uninstall it.

## Export and share

- **Export** saves the results as a zip file in the folder you pick in Android's file picker.
- **Share** passes the same zip to the app you pick in Android's share sheet, with read-only access
  to that one file. The app keeps that zip in its cache until you share again.

The zip holds the run's text and JSON files described above, without photos. What happens to it
afterwards is up to you and the app or folder you chose. Nothing is exported or shared unless you
choose to.

## Backups

If you turn on Android's device backup, Android may include the app's stored results, test photos
and remembered probe selection in your device backup or in a transfer to a new device (for example,
Google's backup on most devices), under the terms of that backup service. The app itself never sends
them anywhere. You can turn backup off in your device's settings.

## Permissions

- **Camera** (`android.permission.CAMERA`): to open the cameras during a run. The app asks for it
  when you start a run. Without it, the probes can't run.

The app does not have the `INTERNET` permission.

## Children

The app is not directed at children and collects no data from anyone, children included.

## Changes

If the app starts handling data differently, this policy will be updated before that version is
released, with a new effective date at the top.

## Contact

Questions about this policy: danascape@gmail.com, or open an issue at
https://github.com/PVOT-OSS/CameraProbe/issues.
