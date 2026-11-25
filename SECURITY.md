# Security policy

> Applies to v1.2.0. This project is a small, community-maintained Android
> framework. Security work is best-effort; there is no commercial support
> contract and no guaranteed response SLA. See [SUPPORT.md](SUPPORT.md) for the
> general support policy.

## Supported versions

| Version | Status | Security fixes |
| --- | --- | --- |
| **1.2.x** | Current release | Yes — fixes land here first |
| 1.1.x | Previous release | Critical fixes only, best effort |
| < 1.1.0 | Unsupported | No; upgrade to 1.2.x |

Version numbers come from `gradle.properties` (`lvgl.versionName` /
`lvgl.versionCode`); v1.2.0 is versionCode 3. Only the sample app and the
`lvgl-android` library in this repository are covered by this policy.

## Reporting a vulnerability

**Use GitHub's private vulnerability reporting** on this repository: open the
**Security** tab and choose **Report a vulnerability**. Do not open a public
issue for an unpatched vulnerability. If private reporting is unavailable in
your GitHub view, open a normal issue with no technical details and ask for a
private channel.

Include, as far as you can:

- affected version / commit and ABI (arm64-v8a or x86_64);
- component (`lvgl-android` library, JNI/native engine, sample app, build
  scripts);
- impact and threat model (what an attacker can do, and with which
  permissions);
- minimal reproduction (APK, steps, logcat);
- whether you have already reported it upstream.

**Do not** attach personal data or credentials, and do not test against
systems you do not own or have permission to test.

## Response process

1. **Triage** — we aim to acknowledge a private report within 7 days and give
   an initial assessment (confirmed / not a vulnerability / needs more info)
   within 30 days. These are goals, not guarantees; a volunteer maintainer may
   be slower.
2. **Fix** — a confirmed issue is fixed on the current branch, with a unit or
   integration guard canary where that is meaningful. The fix is included in a
   patch/minor release and the CHANGELOG notes it without disclosing the exploit
   before users can upgrade.
3. **Disclosure** — we ask reporters to allow a reasonable window for a fix
   before public disclosure. If a fix is not forthcoming, we will not use that
   request to block disclosure indefinitely.
4. **Credit** — reporters are credited in the release notes if they wish.

## Scope and out of scope

**In scope**

- JNI/native engine memory safety, lifecycle and touch handling in
  `lvgl-android`.
- Sample app attack surface: exported components, permission handling,
  intents, manifest attributes, backup rules.
- Fail-closed build/CI gates that protect the shipped artifact
  (`IntegrationGuardTest`, layout lint, Play compliance, size report) — a gate
  that silently passes on malformed input is a security-relevant defect.
- Release integrity: signed artifacts and `SHA256SUMS.txt`.

**Out of scope**

- Vulnerabilities in LVGL upstream — report those to the
  [LVGL project](https://github.com/lvgl/lvgl); we pin it as a submodule and
  can update the pin once upstream has a fix.
- Your app's Play Console account, signing keys, store listing, data-safety
  declarations and privacy-policy URL. These remain your responsibility.
- Device/OEM platform bugs and third-party libraries you add yourself.
- An issue that requires an already-compromised device or granting a
  dangerous permission the app does not request.

## Security-relevant behaviour to know

- The only dangerous permission requested is the optional `CAMERA`, at runtime;
  denial leaves the app usable.
- Only `MainActivity` is exported in the shipped sample manifest; the other
  sample activities are `exported="false"` and reached through an internal
  extra.
- The app collects no personal data; see [PRIVACY.md](PRIVACY.md). Device facts
  are read locally and never leave the device.
- Play-side requirements are machine-checked by `PlayComplianceGuardTest`, but
  **Play Console forms and the store listing remain manual steps** — the gates
  do not submit or configure anything for you.

This project has not had a formal third-party security audit. If you need one,
commission it against a tagged release and report findings privately as above.
