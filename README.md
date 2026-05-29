# Fitelo Weighing Scale — BLE Debug & Decode App

Reverse-engineering notes, a working **Android** capture/readings app, and a **cross-platform
(iOS + Android)** decode + body-composition core for the **Fitelo "FIT PRO 2025‑1"** smart
body-composition weighing scale.

> **TL;DR** — This scale is **broadcast-only over BLE** and the broadcast carries **only the
> weight** (plus a 1‑bit electrode-contact flag). The "18 body-composition parameters" the vendor
> app shows are **not measured** — they are **calculated** from `weight + height + age + sex` using
> standard formulas (CUN‑BAE for body fat, then derived). This repo documents exactly how the scale
> works and reproduces those results so any developer can build on it.

**License:** MIT — see [LICENSE](LICENSE). Use the findings and the code freely.

---

## The device

| | |
|---|---|
| Product | Fitelo "Advance Body Composition Scale" (4 electrodes, advertised "18 parameters") |
| Model number | **FIT PRO 2025‑1** |
| OEM module | Zhejiang Tiansheng Electronic Co. Ltd — which is why the BLE local name is **`tzc`** |
| Capacity / resolution | 3–180 kg; 50 g steps (≤100 kg), 100 g (100–180 kg) |
| BLE behaviour | **Advertising broadcast only — NOT connectable** (no GATT server) |
| Battery | 3 V, 2× AAA |

## Compatibility — which scales this works with

The app matches on the broadcast's **structure**, not a hardcoded MAC, so it works for *any unit*
that speaks this exact format — not just the one I tested.

- ✅ **This Fitelo FIT PRO 2025‑1, and rebrands of the same OEM "tzc" module.** The guts are the
  generic module from Zhejiang Tiansheng, which is sold under several cheap-scale brand names. Any
  unit that advertises the same shape — `0xC0` company‑id marker, 13‑byte payload, weight as
  `uint16_BE / 100`, and the self‑MAC echo — is matched and decoded with **no changes**.
- ❌ **Other brands / protocols (Xiaomi, Renpho, Omron, standard GATT scales, etc.) — not as‑is.**
  They use a different company id, byte layout, and weight encoding, so they're simply ignored
  (and would decode wrong even if forced). Each different protocol needs its own decoder.

In short: this covers **one broadcast protocol (and its rebrands)**, not every broadcasting scale.
To investigate an unknown scale, capture its raw advertisements (the app logs them — see
[`samples/`](samples/)) and write a decoder for its layout; the matcher and decoder are small and
easy to adapt.

## How the scale actually works

1. **It never accepts a connection.** Trying to connect over GATT times out (Android
   `UNKNOWN_STATUS_CODE` / status 133). The vendor app only **scans advertisements** — it never
   connects. So all we can read is what the scale broadcasts.
2. **Everything is in the BLE advertising "manufacturer specific data".** Each broadcast contains
   the complete local name `tzc` and a 16‑byte manufacturer block.
3. **Two MAC addresses exist** and both are explained:
   - The OS-level **link MAC** (e.g. `FE:FA:00:3F:40:BC`) **rotates on every power cycle** — useless
     as a stable key.
   - The manufacturer payload **echoes that same link MAC** in its last 6 bytes.

### Decoded advertisement byte layout

The advertising data (little-endian on the wire):

```
04 09 74 7A 63                                    ; len=4, type=0x09 complete local name "tzc"
10 FF  C0 <ctr>  d0 d1  d2 d3  00 02  d6  m0 m1 m2 m3 m4 m5
   |__ type 0xFF, 16-byte manufacturer-specific block __|
        |__ company id (LE) = 0x<ctr>C0 __|
                 ^ low byte 0xC0 is a fixed marker; high byte <ctr> is a rolling frame counter
```

After the 2-byte company id, the **13 data bytes** decode as:

| Bytes | Field | Meaning |
|---|---|---|
| `d0 d1` | **Weight** | `uint16_big_endian(d0,d1) / 100` kg — **the only real measurement** |
| `d2 d3` | **Electrode-contact flag** | `0x1388` (5000) when feet contact the electrode pads, `0x0000` when not. **Binary — not an impedance value.** |
| `d4 d5` | constant `00 02` | unknown / fixed |
| `d6` | status / checksum | `0x24` while the weight is settling, `0x25` once locked |
| `m0..m5` | echoed link MAC | the unit's current (rotating) BLE address |

### Key finding about the electrodes (verified by A/B test)

Two captures at the **same locked weight (78.5 kg)** — one **with** bare-foot electrode contact,
one **without** — differ in exactly one field:

```
WITH contact : 1E AA  13 88  00 02  25 ...   -> d2d3 = 5000
NO  contact  : 1E AA  00 00  00 02  25 ...   -> d2d3 = 0
```

Across the full run, `d2d3` was `5000` for **all 373** contact packets and `0` for **all 447**
no-contact packets — a perfect 1:1 correlation. So the electrodes **do** register, but only as a
**binary "contact: yes/no" flag**. The scale **never transmits an impedance magnitude**, so it
cannot provide a real bioimpedance body-composition reading. (Sample logs in [`samples/`](samples/).)

## How the body-composition results are calculated

Because only weight is measured, the body-composition panel is computed from
`weight + height + age + sex`. These formulas were validated to within ~0.2% against the vendor
app's own output for an example profile (**78.5 kg, age 32, male, 172.7 cm**):

| Metric | Formula |
|---|---|
| BMI | `weight_kg / height_m²` |
| **Body fat %** | **CUN‑BAE** (below) |
| Fat mass | `bodyFat% × weight` |
| Lean (fat-free) mass | `weight − fatMass` |
| Total body water | `0.73 × leanMass` |
| Muscle mass | `≈ 0.71 × leanMass` |
| Bone mass | sex/weight bracket (≈ 2.9 kg for a ~78 kg male) |
| Protein mass | `≈ leanMass − water − bone` |
| BMR | Katch‑McArdle: `370 + 21.6 × leanMass` |
| Standard (ideal) weight | `22 × height_m²` |
| Weight control | `weight − standardWeight` |
| Subcutaneous fat % / Visceral index / Metabolic age | BMI/age approximations (≈) |
| Maintenance calories | `BMR × activityFactor` |
| Recommended protein | `≈ weight × 1.07 g` |

**CUN‑BAE body fat %** (`S = 0` male, `1` female):

```
bodyFat% = -44.988 + 0.503·age + 10.689·S + 3.172·BMI − 0.026·BMI²
           + 0.181·BMI·S − 0.02·BMI·age − 0.005·BMI²·S + 0.00021·BMI²·age
```

> ⚠️ Every value **except weight** is a **statistical estimate from BMI/age/sex — not a
> measurement**. Present it that way; do not label it as measured or diagnostic.

## Open question — help wanted: the impedance

I could **not find any impedance value in the received broadcast data.** Across thousands of
captured packets (idle, settling, locked, with and without electrode contact), the only fields
that change are the **weight** and the **binary electrode-contact flag** (`d2d3` = 5000 / 0). No
field carries a continuous impedance/bioimpedance reading — which is why the body-composition
numbers can only be *computed* from the profile, not measured.

If you can find an impedance (or any additional body-composition data) in this scale's output —
in a different advertising frame, a scan response, a payload condition I didn't trigger, or via
any other channel — **please open an issue or PR.** Raw sample logs are in [`samples/`](samples/)
to get you started. Findings shared back help the whole community.

## What's in this repo

```
app/                         Native Android app (Kotlin) — passive BLE scan, decode, live readings UI
  └─ .../ScaleDecoder.kt     Decode + body-composition (Android reference implementation)
  └─ .../MainActivity.kt     Scan, model-signature match (no hardcoded MAC), readings screen
shared/
  └─ scale-core.ts           Cross-platform (iOS + Android) decode + body-composition core (TypeScript)
samples/                     Raw capture logs used to derive/verify the findings
LICENSE                      MIT
```

## Cross-platform: iOS + Android

The decode + math is plain, dependency-free logic and is provided in [`shared/scale-core.ts`](shared/scale-core.ts).

- **One codebase, both platforms (recommended):** use `shared/scale-core.ts` in a
  **React Native / Expo** app with [`react-native-ble-plx`](https://github.com/dotintent/react-native-ble-plx),
  which does passive BLE advertisement scanning on **both iOS and Android**. Feed the manufacturer
  company id + bytes into `decodeFiteloTzc()`, then `computeBodyComposition()`.
- **Android native:** the Kotlin app in [`app/`](app/) is the reference (`ScaleDecoder.kt`).
- **iOS native:** scan advertisements with **CoreBluetooth** (`CBCentralManager` →
  `didDiscoverPeripheral` → `CBAdvertisementDataManufacturerDataKey`) and port the ~30-line
  algorithm from `shared/scale-core.ts` to Swift (the math is identical).

### Quick use (TypeScript)

```ts
import { decodeFiteloTzc, computeBodyComposition } from './shared/scale-core';

// companyId + 13 manufacturer-data bytes from your BLE scanner:
const reading = decodeFiteloTzc(companyId, dataBytes);
if (reading && reading.weightKg > 0) {
  const body = computeBodyComposition({
    weightKg: reading.weightKg, heightCm: 173, ageYears: 32, sex: 'male',
  });
  console.log(reading.weightKg, body.bodyFatPct, body.bmrKcal);
}
```

## Build & run the Android app

Requirements: JDK 17+, Android SDK with `platforms;android-36` and `build-tools;36.0.0`.
Create `local.properties` (git-ignored) pointing `sdk.dir` at your Android SDK.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: AGP 9.2.0 · Gradle 9.4.1 · Kotlin (AGP built-in) · compileSdk/targetSdk 36 · minSdk 26 ·
applicationId `com.arum.scalecapture`.

In the app: set Height / Age / Sex, tap **Start**, step on the scale. The weight is read live from
the broadcast; the body-composition estimates are computed and shown, with a footnote stating that
everything except weight is an estimate.

## Disclaimer

This is independent reverse-engineering for interoperability and educational purposes. "Fitelo" and
"FIT PRO" are trademarks of their respective owners; this project is not affiliated with or endorsed
by them. The body-composition values are statistical estimates, not medical measurements.

## Author

**Mohan Narayana Mondi**

Released under the MIT License.
