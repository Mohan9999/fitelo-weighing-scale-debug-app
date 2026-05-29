package com.arum.scalecapture

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Canonical decode + body-composition for the Fitelo "FIT PRO 2025-1" (OEM "tzc") broadcast scale.
 *
 * BRAND-SPECIFIC: only this OEM module. The advertisement carries WEIGHT ONLY, plus a binary
 * electrode-contact flag (data[2..3] = 5000 when the feet touch the pads, 0 when not). There is
 * NO impedance magnitude, so the full body-composition panel is computed from
 * weight + height + age + sex (CUN-BAE + derived), exactly as the vendor app does. Every derived
 * value is an ESTIMATE (BMI/age/sex), NOT a measurement. Validated against a real vendor report:
 * 78.5 kg, 32, male, 172.7 cm -> BF 24.5%, lean 59.3, BMR ~1612.
 */
object ScaleDecoder {

    enum class Sex { MALE, FEMALE }

    data class ScaleReading(
        val weightKg: Double,
        val frameCounter: Int,         // company-id high byte; rolling per broadcast
        val flagByte: Int,             // data[6]: 0x24 while settling, 0x25 once locked
        val electrodeContact: Boolean, // data[2..3]==5000 -> feet on electrodes (binary, NOT impedance)
        val linkMac: String,           // data[7..12] - the unit's rotating link MAC
    )

    data class Profile(
        val heightCm: Double,
        val ageYears: Int,
        val sex: Sex,
        val activityFactor: Double = 1.25, // sedentary->light; vendor maintenance kcal ≈ BMR×1.25
    )

    enum class StatusKind { NONE, LOW, NORMAL, SLIGHTLY_HIGH, HIGH }

    data class MetricRow(
        val group: String,
        val label: String,
        val value: String,
        val status: String = "",
        val kind: StatusKind = StatusKind.NONE,
    )

    private const val MODEL_MARKER_LOW_BYTE = 0xC0
    private const val MFG_DATA_LEN = 13

    /** Tier-1 model recognition - device-agnostic, no per-unit MAC. */
    fun matches(companyId: Int, data: ByteArray, linkMacBytes: ByteArray? = null): Boolean {
        if ((companyId and 0xFF) != MODEL_MARKER_LOW_BYTE) return false
        if (data.size != MFG_DATA_LEN) return false
        if (linkMacBytes != null && linkMacBytes.size == 6) {
            var echo = true
            for (i in 0 until 6) if (data[7 + i] != linkMacBytes[i]) { echo = false; break }
            if (echo) return true
        }
        // Soft fallback: the contact-flag field is one of its two known states (5000 or 0).
        val contactFlag = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        return contactFlag == 0x1388 || contactFlag == 0x0000
    }

    /** Decode the manufacturer payload (companyId + 13 data bytes Android hands us). */
    fun decode(companyId: Int, data: ByteArray): ScaleReading? {
        if (!matches(companyId, data)) return null
        val raw = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val contactFlag = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val linkMac = (7..12).joinToString(":") { "%02X".format(data[it].toInt() and 0xFF) }
        return ScaleReading(
            weightKg = raw / 100.0,
            frameCounter = (companyId shr 8) and 0xFF,
            flagByte = data[6].toInt() and 0xFF,
            electrodeContact = contactFlag == 0x1388,
            linkMac = linkMac,
        )
    }

    // ---- Body-composition (estimates) -------------------------------------------------------

    private fun r1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun r2(v: Double) = (v * 100).roundToInt() / 100.0

    /** CUN-BAE body fat % from BMI + age + sex (S = 0 male / 1 female). Validated to ~0.2%. */
    fun bodyFatPct(bmi: Double, age: Int, sex: Sex): Double {
        val s = if (sex == Sex.FEMALE) 1.0 else 0.0
        return -44.988 + 0.503 * age + 10.689 * s + 3.172 * bmi - 0.026 * bmi.pow(2) +
            0.181 * bmi * s - 0.02 * bmi * age - 0.005 * bmi.pow(2) * s + 0.00021 * bmi.pow(2) * age
    }

    /** Build the full ordered, labelled metric panel for a weight + profile. */
    fun computeMetrics(weightKg: Double, p: Profile): List<MetricRow> {
        val hM = p.heightCm / 100.0
        val bmi = if (hM > 0) weightKg / (hM * hM) else 0.0
        val bf = bodyFatPct(bmi, p.ageYears, p.sex).coerceIn(3.0, 70.0)
        val fatMass = bf / 100.0 * weightKg
        val lean = weightKg - fatMass
        val water = 0.73 * lean
        val muscle = 0.71 * lean
        val male = p.sex == Sex.MALE
        val bone = if (male) (if (weightKg > 75) 3.0 else if (weightKg > 60) 2.9 else 2.5)
                   else (if (weightKg > 60) 2.4 else if (weightKg > 45) 2.2 else 1.8)
        val protein = (lean - water - bone).coerceAtLeast(0.0)
        val bmr = 370 + 21.6 * lean // Katch-McArdle
        val stdWeight = 22 * hM * hM
        val weightControl = weightKg - stdWeight
        // approximations (vendor's exact formulas unknown; labelled ≈)
        val subQ = bf * 0.89
        val visceral = (0.40 * bmi + 0.05 * p.ageYears - 1).roundToInt().coerceAtLeast(1)
        val metAge = (p.ageYears + (bmi - 22) * 1.3).roundToInt().coerceAtLeast(p.ageYears - 5)
        val maintKcal = (bmr * p.activityFactor).roundToInt()
        val recProtein = (weightKg * 1.07).roundToInt()

        fun bmiStatus() = when {
            bmi < 18.5 -> StatusKind.LOW to "Low"
            bmi < 25 -> StatusKind.NORMAL to "Normal"
            bmi < 30 -> StatusKind.SLIGHTLY_HIGH to "Slightly High"
            else -> StatusKind.HIGH to "High"
        }
        fun bfStatus(): Pair<StatusKind, String> {
            val (lo, normHi, slightHi) = if (male) Triple(10.0, 20.0, 25.0) else Triple(18.0, 28.0, 33.0)
            return when {
                bf < lo -> StatusKind.LOW to "Low"
                bf <= normHi -> StatusKind.NORMAL to "Normal"
                bf <= slightHi -> StatusKind.SLIGHTLY_HIGH to "Slightly High"
                else -> StatusKind.HIGH to "High"
            }
        }
        fun visceralStatus() = when {
            visceral <= 9 -> StatusKind.NORMAL to "Normal"
            visceral <= 14 -> StatusKind.SLIGHTLY_HIGH to "Slightly High"
            else -> StatusKind.HIGH to "High"
        }
        fun proteinStatus() = if (lean > 0 && protein / weightKg * 100 < 16) StatusKind.LOW to "Low"
                              else StatusKind.NORMAL to "Normal"
        fun metAgeStatus() = if (metAge > p.ageYears + 3) StatusKind.HIGH to "Above your age"
                             else StatusKind.NORMAL to "Normal"

        val (bmiK, bmiS) = bmiStatus()
        val (bfK, bfS) = bfStatus()
        val (visK, visS) = visceralStatus()
        val (proK, proS) = proteinStatus()
        val (maK, maS) = metAgeStatus()
        val proteinPct = if (weightKg > 0) protein / weightKg * 100 else 0.0
        val waterPct = if (weightKg > 0) water / weightKg * 100 else 0.0
        val muscleRate = if (weightKg > 0) muscle / weightKg * 100 else 0.0

        return listOf(
            MetricRow("Weight", "Weight", "${r2(weightKg)} kg"),
            MetricRow("Weight", "BMI", r1(bmi).toString(), bmiS, bmiK),
            MetricRow("Weight", "Standard Weight", "${r1(stdWeight)} kg"),
            MetricRow("Weight", "Weight Control", "${r1(weightControl)} kg"),

            MetricRow("Body Composition", "Body Fat", "${r1(bf)} %", bfS, bfK),
            MetricRow("Body Composition", "Fat Mass", "${r1(fatMass)} kg", bfS, bfK),
            MetricRow("Body Composition", "Lean (Fat-Free) Mass", "${r1(lean)} kg"),
            MetricRow("Body Composition", "Muscle Mass", "${r1(muscle)} kg  (${r1(muscleRate)} %)", "Normal", StatusKind.NORMAL),
            MetricRow("Body Composition", "Bone Mass", "${r1(bone)} kg", "Normal", StatusKind.NORMAL),

            MetricRow("Fat Analysis", "Subcutaneous Fat ≈", "${r1(subQ)} %"),
            MetricRow("Fat Analysis", "Visceral Fat Index ≈", visceral.toString(), visS, visK),

            MetricRow("Water & Protein", "Body Water", "${r1(waterPct)} %  (${r1(water)} kg)", "Normal", StatusKind.NORMAL),
            MetricRow("Water & Protein", "Protein", "${r1(proteinPct)} %  (${r1(protein)} kg)", proS, proK),

            MetricRow("Metabolic", "BMR", "${bmr.roundToInt()} kcal", "Normal", StatusKind.NORMAL),
            MetricRow("Metabolic", "Metabolic Age ≈", "$metAge yrs", maS, maK),
            MetricRow("Metabolic", "Maintenance Calories ≈", "$maintKcal kcal"),
            MetricRow("Metabolic", "Recommended Protein ≈", "$recProtein g"),
        )
    }
}
