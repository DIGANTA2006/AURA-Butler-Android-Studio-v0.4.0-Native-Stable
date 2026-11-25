package `in`.diganta.aurabutler.maintenance

import java.net.URI

/**
 * Offline check for pasted or received links. It looks for common phishing and
 * malware-delivery patterns. It cannot prove that a link is safe, so treat the
 * result as a warning, not a guarantee.
 */
object LinkSafetyChecker {
    enum class Level { SAFE, CAUTION, HIGH_RISK, INVALID }

    data class Verdict(val level: Level, val host: String, val reasons: List<String>)

    private val officialDomains = setOf(
        "google.com", "gmail.com", "youtube.com", "whatsapp.com", "facebook.com",
        "instagram.com", "amazon.com", "amazon.in", "netflix.com", "apple.com",
        "microsoft.com", "paypal.com", "paytm.com", "phonepe.com", "sbi.co.in",
        "hdfcbank.com", "icicibank.com", "axisbank.com", "wikipedia.org",
        "github.com", "linkedin.com", "telegram.org"
    )
    private val shorteners = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "cutt.ly", "rb.gy",
        "is.gd", "shorturl.at", "ow.ly", "tiny.cc", "rebrand.ly"
    )
    private val riskyTlds = setOf(
        "zip", "mov", "xyz", "top", "click", "icu", "tk", "gq", "ml", "cf", "ga",
        "work", "loan", "country", "download"
    )
    private val lureWords = listOf(
        "verify", "secure", "login", "signin", "account", "update", "confirm",
        "wallet", "bonus", "prize", "reward", "kyc", "otp", "unlock", "suspend"
    )
    private val brandWords = listOf(
        "paypal", "google", "gmail", "whatsapp", "facebook", "instagram", "amazon",
        "netflix", "apple", "microsoft", "sbi", "hdfc", "icici", "axis", "paytm", "phonepe"
    )
    private val riskyFileEndings = listOf(
        ".apk", ".exe", ".scr", ".zip", ".rar", ".bat", ".msi", ".vbs", ".ps1"
    )
    private val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    fun check(raw: String): Verdict {
        val input = raw.trim()
        if (input.isEmpty()) {
            return Verdict(Level.INVALID, "", listOf("Paste a link to check."))
        }
        val candidate = if (input.contains("://")) input else "https://$input"
        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return Verdict(Level.INVALID, "", listOf("This does not look like a web address."))
        val host = uri.host?.lowercase()
            ?: return Verdict(Level.INVALID, "", listOf("No website name was found in this link."))
        val path = (uri.path ?: "").lowercase()
        val scheme = (uri.scheme ?: "").lowercase()
        val labels = host.split('.')
        val official = officialDomains.any { host == it || host.endsWith(".$it") }

        var points = 0
        val reasons = mutableListOf<String>()

        if (scheme != "https" && scheme != "http") {
            points += 4
            reasons += "Uses an unusual protocol ($scheme)."
        } else if (scheme == "http") {
            points += 1
            reasons += "Not encrypted (http instead of https)."
        }
        if (uri.rawUserInfo != null) {
            points += 4
            reasons += "Hides a login inside the address (user@site), a common trick."
        }
        if (uri.port != -1 && uri.port != 80 && uri.port != 443) {
            points += 1
            reasons += "Uses an unusual port (${uri.port})."
        }
        if (ipv4.matches(host) || host.contains(':')) {
            points += 3
            reasons += "Uses a raw IP address instead of a website name."
        }
        if (labels.any { it.startsWith("xn--") }) {
            points += 2
            reasons += "Uses encoded letters that can imitate other sites."
        }
        if (host in shorteners) {
            points += 1
            reasons += "Shortened link: the real destination is hidden."
        }
        if (labels.size >= 5) {
            points += 1
            reasons += "Many nested subdomains."
        }
        if (host.count { it == '-' } >= 3) {
            points += 1
            reasons += "Long hyphenated name, common in fake sites."
        }
        if (!official) {
            val tld = labels.last()
            if (tld in riskyTlds) {
                points += 1
                reasons += "The .$tld ending is often used for throwaway sites."
            }
            val brand = brandWords.firstOrNull { b ->
                labels.any { label -> label.split('-').contains(b) }
            }
            if (brand != null) {
                points += 2
                reasons += "Mentions $brand but is not that company's official domain."
            }
            val lure = lureWords.firstOrNull { w -> host.contains(w) || path.contains(w) }
            if (lure != null) {
                points += 1
                reasons += "Uses urgent or login wording ('$lure')."
            }
            if (riskyFileEndings.any { path.endsWith(it) }) {
                points += 3
                reasons += "Downloads a file type that can install or run software."
            }
        }

        val level = when {
            points >= 4 -> Level.HIGH_RISK
            points >= 1 -> Level.CAUTION
            else -> Level.SAFE
        }
        if (reasons.isEmpty()) {
            reasons += if (official) {
                "Official domain. No warning signs found."
            } else {
                "No common warning signs found. This is not a guarantee."
            }
        }
        return Verdict(level, host, reasons)
    }
}