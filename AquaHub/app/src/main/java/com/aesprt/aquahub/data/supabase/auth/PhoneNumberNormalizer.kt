package com.aesprt.aquahub.data.supabase.auth

/** Canonical E.164 normalization for Philippine phone authentication. */
object PhoneNumberNormalizer {
    fun normalizePhilippines(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "Phone number is required." }
        val digits = value.filter(Char::isDigit)
        val normalized = when {
            value.startsWith("+") && digits.startsWith("63") -> "+$digits"
            digits.startsWith("63") -> "+$digits"
            digits.startsWith("09") -> "+63${digits.drop(1)}"
            else -> throw IllegalArgumentException("Enter a valid Philippine mobile number.")
        }
        require(normalized.matches(Regex("\\+639\\d{9}"))) {
            "Enter a valid Philippine mobile number."
        }
        return normalized
    }

}
