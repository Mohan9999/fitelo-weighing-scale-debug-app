/**
 * Fitelo "FIT PRO 2025-1" (OEM "tzc") smart scale - cross-platform decode + body-composition core.
 *
 * Dependency-free TypeScript. Runs unchanged on:
 *   - React Native / Expo (iOS + Android) with react-native-ble-plx for BLE advertisement scanning,
 *   - the web (Web Bluetooth, where available),
 *   - Node tooling/tests.
 * It is also a 1:1 reference for native ports (Kotlin / Swift).
 *
 * IMPORTANT: this scale broadcasts WEIGHT only (plus a binary electrode-contact flag). All
 * body-composition values are STATISTICAL ESTIMATES from weight + height + age + sex (CUN-BAE),
 * not measurements. See README.md for the full reverse-engineering writeup.
 *
 * Brand-specific: only this OEM module. Other scale brands use different formats and need their
 * own decoder.
 */

export type Sex = 'male' | 'female';

export interface ScaleReading {
  /** uint16 big-endian of manufacturer data[0..1] / 100. The only measured value. */
  weightKg: number;
  /** company-id high byte; a rolling frame counter that increments each broadcast. */
  frameCounter: number;
  /** true when the electrode-contact flag (data[2..3]) reads 5000; binary, NOT an impedance value. */
  electrodeContact: boolean;
  /** data[6]: 0x24 while settling, 0x25 once the weight is locked. */
  statusByte: number;
  /** data[7..12]: the unit's link MAC (rotates per power cycle), e.g. "FE:FA:00:3F:40:BC". */
  linkMac: string;
}

const MODEL_MARKER_LOW_BYTE = 0xc0;
const MFG_DATA_LEN = 13;
const CONTACT_FLAG = 0x1388; // 5000 when the electrodes are touched, 0x0000 when not

/**
 * Device-agnostic model recognition - no hardcoded per-unit MAC.
 * A packet is one of these scales if the manufacturer block has the 0xC0 marker, a 13-byte
 * payload, and (when known) its last 6 bytes echo the unit's own link MAC.
 *
 * @param companyId    BLE manufacturer "company id" (low byte 0xC0; high byte is a frame counter)
 * @param data         the 13 manufacturer bytes AFTER the company id
 * @param linkMacBytes optional: the unit's own link-address bytes, to verify the self-MAC echo
 */
export function matchesFiteloTzc(companyId: number, data: Uint8Array, linkMacBytes?: Uint8Array): boolean {
  if ((companyId & 0xff) !== MODEL_MARKER_LOW_BYTE) return false;
  if (data.length !== MFG_DATA_LEN) return false;
  if (linkMacBytes && linkMacBytes.length === 6) {
    let echo = true;
    for (let i = 0; i < 6; i++) {
      if (data[7 + i] !== linkMacBytes[i]) { echo = false; break; }
    }
    if (echo) return true;
  }
  // Soft fallback: the contact-flag field is one of the two known states.
  const flag = (data[2] << 8) | data[3];
  return flag === CONTACT_FLAG || flag === 0x0000;
}

/** Decode the manufacturer payload (company id + 13 data bytes). Returns null if not this scale. */
export function decodeFiteloTzc(companyId: number, data: Uint8Array): ScaleReading | null {
  if (!matchesFiteloTzc(companyId, data)) return null;
  const weightKg = ((data[0] << 8) | data[1]) / 100;
  const contactFlag = (data[2] << 8) | data[3];
  const linkMac = Array.from(data.slice(7, 13))
    .map((b) => b.toString(16).padStart(2, '0').toUpperCase())
    .join(':');
  return {
    weightKg,
    frameCounter: (companyId >> 8) & 0xff,
    electrodeContact: contactFlag === CONTACT_FLAG,
    statusByte: data[6],
    linkMac,
  };
}

/**
 * Parse a whole raw BLE advertisement (length-type-value records) and decode it if it is this
 * scale. Use when your scanner hands you the raw blob; with react-native-ble-plx you usually
 * already have the parsed manufacturer map, so call decodeFiteloTzc() directly.
 */
export function decodeAdvertisement(adv: Uint8Array): ScaleReading | null {
  let i = 0;
  while (i < adv.length) {
    const len = adv[i];
    if (!len) break;
    const type = adv[i + 1];
    if (type === 0xff && len >= 3) {
      const companyId = adv[i + 2] | (adv[i + 3] << 8); // little-endian
      const data = adv.slice(i + 4, i + 1 + len);
      const reading = decodeFiteloTzc(companyId, data);
      if (reading) return reading;
    }
    i += len + 1;
  }
  return null;
}

// ---- Body composition (ESTIMATES from weight + height + age + sex) --------------------------

export interface BodyCompositionInput {
  weightKg: number;
  heightCm: number;
  ageYears: number;
  sex: Sex;
  /** 1.2 sedentary … 1.725 very active. Default 1.25 (maintenance kcal ≈ BMR × 1.25). */
  activityFactor?: number;
}

export interface BodyComposition {
  bmi: number;
  bodyFatPct: number;
  fatMassKg: number;
  leanMassKg: number;
  muscleMassKg: number;
  boneMassKg: number;
  bodyWaterPct: number;
  waterWeightKg: number;
  proteinPct: number;
  proteinMassKg: number;
  bmrKcal: number;
  metabolicAgeYears: number;
  visceralFatIndex: number;
  subcutaneousFatPct: number;
  standardWeightKg: number;
  weightControlKg: number;
  maintenanceKcal: number;
  recommendedProteinG: number;
  /** Marks the whole panel as a BMI/age/sex estimate (weight excepted). */
  basis: 'estimate-bmi-age-sex';
}

/** CUN-BAE body-fat % from BMI + age + sex (S = 0 male / 1 female). Validated to ~0.2%. */
export function cunBaeBodyFatPct(bmi: number, ageYears: number, sex: Sex): number {
  const s = sex === 'female' ? 1 : 0;
  return (
    -44.988 + 0.503 * ageYears + 10.689 * s + 3.172 * bmi - 0.026 * bmi ** 2 +
    0.181 * bmi * s - 0.02 * bmi * ageYears - 0.005 * bmi ** 2 * s + 0.00021 * bmi ** 2 * ageYears
  );
}

const round1 = (v: number): number => Math.round(v * 10) / 10;

/** Compute the full estimated body-composition panel from a measured weight + profile. */
export function computeBodyComposition(input: BodyCompositionInput): BodyComposition {
  const { weightKg, heightCm, ageYears, sex } = input;
  const activityFactor = input.activityFactor ?? 1.25;
  const hM = heightCm / 100;
  const bmi = hM > 0 ? weightKg / (hM * hM) : 0;
  const bodyFatPct = Math.min(70, Math.max(3, cunBaeBodyFatPct(bmi, ageYears, sex)));
  const fatMassKg = (bodyFatPct / 100) * weightKg;
  const leanMassKg = weightKg - fatMassKg;
  const waterWeightKg = 0.73 * leanMassKg;
  const muscleMassKg = 0.71 * leanMassKg;
  const male = sex === 'male';
  const boneMassKg = male
    ? weightKg > 75 ? 3.0 : weightKg > 60 ? 2.9 : 2.5
    : weightKg > 60 ? 2.4 : weightKg > 45 ? 2.2 : 1.8;
  const proteinMassKg = Math.max(0, leanMassKg - waterWeightKg - boneMassKg);
  const bmrKcal = Math.round(370 + 21.6 * leanMassKg); // Katch-McArdle
  const standardWeightKg = 22 * hM * hM;
  const weightControlKg = weightKg - standardWeightKg;
  const subcutaneousFatPct = bodyFatPct * 0.89; // approximation
  const visceralFatIndex = Math.max(1, Math.round(0.4 * bmi + 0.05 * ageYears - 1)); // approximation
  const metabolicAgeYears = Math.max(ageYears - 5, Math.round(ageYears + (bmi - 22) * 1.3)); // approximation
  const maintenanceKcal = Math.round(bmrKcal * activityFactor);
  const recommendedProteinG = Math.round(weightKg * 1.07);

  return {
    bmi: round1(bmi),
    bodyFatPct: round1(bodyFatPct),
    fatMassKg: round1(fatMassKg),
    leanMassKg: round1(leanMassKg),
    muscleMassKg: round1(muscleMassKg),
    boneMassKg: round1(boneMassKg),
    bodyWaterPct: round1(weightKg > 0 ? (waterWeightKg / weightKg) * 100 : 0),
    waterWeightKg: round1(waterWeightKg),
    proteinPct: round1(weightKg > 0 ? (proteinMassKg / weightKg) * 100 : 0),
    proteinMassKg: round1(proteinMassKg),
    bmrKcal,
    metabolicAgeYears,
    visceralFatIndex,
    subcutaneousFatPct: round1(subcutaneousFatPct),
    standardWeightKg: round1(standardWeightKg),
    weightControlKg: round1(weightControlKg),
    maintenanceKcal,
    recommendedProteinG,
    basis: 'estimate-bmi-age-sex',
  };
}
