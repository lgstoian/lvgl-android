# Privacy policy

Last updated: 2026-11-04

## Summary

This application does not collect, store or transmit any personal data. It does
not use analytics, advertising, crash reporting or any third-party SDK that
collects data.

## Information the app reads

The app reads a small amount of **device information locally** to render the
device dashboard:

- SoC / hardware platform name
- Total memory
- Total internal storage
- Display resolution and density
- Android version
- Camera and sensor counts

This information is never sent anywhere; it is used only to draw the dashboard
and is discarded when the app is closed.

The **launcher** sample lists the applications installed on the device. The list
is read locally from the system package manager and is never transmitted.

The **camera** sample uses the camera only while the sample is open, and only
after you grant the `CAMERA` permission. Frames are processed locally and are
never stored or transmitted.

## Permissions

- `CAMERA` — optional. Used only by the camera sample. The app is fully usable
  without it.
- Package visibility — used by the launcher sample to list launchable apps.

## Data storage and backup

The app stores no user data. It opts out of cloud backup and device-to-device
transfer (`backup_rules.xml` and `data_extraction_rules.xml`).

## Children

The app is not directed at children and does not collect any information from
anyone.

## Changes

If this policy changes, the updated date above will be revised.

## Contact

For privacy questions, open an issue in the project repository.
