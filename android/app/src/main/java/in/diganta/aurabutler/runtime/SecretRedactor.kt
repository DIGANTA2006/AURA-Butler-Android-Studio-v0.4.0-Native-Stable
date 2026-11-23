package `in`.diganta.aurabutler.runtime

import java.util.Locale

/**
 * Prevents accidental credential leakage without treating ordinary programming
 * vocabulary (for example `apiKey`, `PIN`, or `authorization`) as a secret by itself.
 */
object SecretRedactor {
    private val sensitiveLabels = listOf(
        "password",
        "passcode",
        "pin",
        "otp",
        "cvv",
        "card number",
        "bank account",
        "upi pin",
        "api key",
        "secret key",
        "bearer token",
        "authorization",
        "aadhaar",
        "aadhar",
        "pan number",
        "private key",
        "recovery phrase"
    )

    private val highConfidenceSecretPatterns = listOf(
        Regex("""(?i)\b(?:gsk_|hf_)[A-Za-z0-9_-]{16,}\b"""),
        Regex("""\bsk-[A-Za-z0-9_-]{16,}\b"""),
        Regex("""\bAIza[0-9A-Za-z_-]{25,}\b"""),
        Regex("""\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        Regex("""(?<!\d)\d{12,19}(?!\d)"""),
        Regex(
            """(?i)\b(?:password|passcode|otp|cvv|upi\s*pin)\s*(?:is|=|:)\s*[\"']?(?!YOUR_|EXAMPLE|PLACEHOLDER|REPLACE_|<|\$\{)[^\s\"']{6,}[\"']?"""
        ),
        Regex(
            """(?i)\b(?:api[_ -]?key|secret[_ -]?key|bearer(?:\s+token)?)\s*(?:is|=|:)\s*[\"']?(?!YOUR_|EXAMPLE|PLACEHOLDER|REPLACE_|<|\$\{|System\.getenv|os\.getenv|BuildConfig\.)[A-Za-z0-9._-]{16,}[\"']?"""
        )
    )

    fun containsSensitive(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return sensitiveLabels.any(lower::contains)
    }

    /** True only when the text appears to contain an actual credential/identifier. */
    fun containsLikelySecret(value: String): Boolean =
        highConfidenceSecretPatterns.any { it.containsMatchIn(value) }

    fun redact(value: String, maxChars: Int = 2_000): String {
        var safe = value.take(maxChars)
        highConfidenceSecretPatterns.forEach { pattern ->
            safe = pattern.replace(safe, "[REDACTED SECRET]")
        }
        return safe
    }

    fun redactMap(values: Map<String, Any?>): Map<String, Any?> =
        values.mapValues { (key, value) ->
            if (containsSensitive(key)) {
                "[REDACTED]"
            } else {
                when (value) {
                    null -> null
                    is String -> redact(value, 500)
                    is Number, is Boolean -> value
                    else -> redact(value.toString(), 500)
                }
            }
        }
}
