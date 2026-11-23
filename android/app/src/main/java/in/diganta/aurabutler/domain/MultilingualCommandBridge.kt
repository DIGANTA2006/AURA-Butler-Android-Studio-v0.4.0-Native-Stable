package `in`.diganta.aurabutler.domain

/**
 * Small deterministic bridge for high-confidence Bengali/Hindi device commands.
 * It intentionally handles only commands with an unambiguous local equivalent;
 * ordinary conversation stays in the user's original language for the AI route.
 */
object MultilingualCommandBridge {
    fun normalizeKnownCommand(raw: String): String {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isBlank()) return raw
        val key = text.lowercase()

        exact[key]?.let { return it }

        Regex("^(?:দয়া করে |প্লিজ )?(.+?) (?:খোলো|খুলে দাও)$").matchEntire(key)?.let { match ->
            val app = bengaliApps[match.groupValues[1]] ?: return@let
            return "open $app"
        }
        Regex("^(?:कृपया |प्लीज )?(.+?) (?:खोलो|खोल दो)$").matchEntire(key)?.let { match ->
            val app = hindiApps[match.groupValues[1]] ?: return@let
            return "open $app"
        }

        Regex("^(?:গভীর গবেষণা|গভীর রিসার্চ|গবেষণা|রিসার্চ)\\s+(.+)$").matchEntire(key)?.let { match ->
            return "deep research ${match.groupValues[1]}"
        }
        Regex("^(?:गहन शोध|गहरी रिसर्च|रिसर्च|शोध)\\s+(.+)$").matchEntire(key)?.let { match ->
            return "deep research ${match.groupValues[1]}"
        }

        return raw
    }

    private val bengaliApps = mapOf(
        "ইউটিউব" to "YouTube",
        "হোয়াটসঅ্যাপ" to "WhatsApp",
        "ক্যামেরা" to "camera",
        "সেটিংস" to "settings",
        "ম্যাপ" to "maps",
        "গুগল ম্যাপ" to "Google Maps"
    )

    private val hindiApps = mapOf(
        "यूट्यूब" to "YouTube",
        "व्हाट्सऐप" to "WhatsApp",
        "कैमरा" to "camera",
        "सेटिंग्स" to "settings",
        "मैप" to "maps",
        "गूगल मैप" to "Google Maps"
    )

    private val exact = mapOf(
        // Bengali
        "টর্চ চালু করো" to "turn on flashlight",
        "ফ্ল্যাশলাইট চালু করো" to "turn on flashlight",
        "টর্চ বন্ধ করো" to "turn off flashlight",
        "ফ্ল্যাশলাইট বন্ধ করো" to "turn off flashlight",
        "ভলিউম বাড়াও" to "volume up",
        "শব্দ বাড়াও" to "volume up",
        "ভলিউম কমাও" to "volume down",
        "শব্দ কমাও" to "volume down",
        "আজকের আবহাওয়া বলো" to "weather today",
        "আবহাওয়া কেমন" to "weather today",
        "আজকের খবর বলো" to "news today",
        "খবর দেখাও" to "news today",
        "ক্যামেরা খোলো" to "open camera",
        "সেটিংস খোলো" to "open settings",
        "ইউটিউব খোলো" to "open YouTube",
        "হোয়াটসঅ্যাপ খোলো" to "open WhatsApp",

        // Hindi
        "टॉर्च चालू करो" to "turn on flashlight",
        "फ्लैशलाइट चालू करो" to "turn on flashlight",
        "टॉर्च बंद करो" to "turn off flashlight",
        "फ्लैशलाइट बंद करो" to "turn off flashlight",
        "वॉल्यूम बढ़ाओ" to "volume up",
        "आवाज़ बढ़ाओ" to "volume up",
        "वॉल्यूम कम करो" to "volume down",
        "आवाज़ कम करो" to "volume down",
        "आज का मौसम बताओ" to "weather today",
        "मौसम कैसा है" to "weather today",
        "आज की खबर बताओ" to "news today",
        "खबर दिखाओ" to "news today",
        "कैमरा खोलो" to "open camera",
        "सेटिंग्स खोलो" to "open settings",
        "यूट्यूब खोलो" to "open YouTube",
        "व्हाट्सऐप खोलो" to "open WhatsApp"
    )
}
