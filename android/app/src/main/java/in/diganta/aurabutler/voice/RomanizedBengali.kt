package `in`.diganta.aurabutler.voice

import java.util.Locale

/**
 * Converts Bengali written in English letters (for example "Bolchi kichu habe nato")
 * into Bengali script, so the Bengali text-to-speech voice pronounces it correctly
 * instead of applying English letter-to-sound rules.
 *
 * Offline and heuristic. A curated word list covers common chat, news and name words;
 * other words use simple phonetic rules. Text that is not romanized Bengali is returned unchanged.
 */
object RomanizedBengali {
    private val TOKEN = Regex("[A-Za-z]+")

    private const val ANUSVARA = "\u0982"
    private const val HASANT = "\u09CD"

    private enum class Prev { NONE, CONSONANT, VOWEL }

    private class Vowel(val latin: String, val independent: String, val matra: String)

    // Longest match first: order matters.
    private val CONSONANTS: List<Pair<String, String>> = listOf(
        "chh" to "\u099B",
        "ch" to "\u099A",
        "kh" to "\u0996",
        "gh" to "\u0998",
        "jh" to "\u099D",
        "th" to "\u09A5",
        "dh" to "\u09A7",
        "ph" to "\u09AB",
        "bh" to "\u09AD",
        "sh" to "\u09B6",
        "ng" to "\u0982",
        "k" to "\u0995",
        "g" to "\u0997",
        "j" to "\u099C",
        "t" to "\u09A4",
        "d" to "\u09A6",
        "n" to "\u09A8",
        "p" to "\u09AA",
        "b" to "\u09AC",
        "m" to "\u09AE",
        "r" to "\u09B0",
        "l" to "\u09B2",
        "s" to "\u09B8",
        "h" to "\u09B9",
        "y" to "\u09AF",
        "w" to "\u0993",
        "v" to "\u09AD",
        "f" to "\u09AB",
        "z" to "\u099C",
        "c" to "\u0995",
        "q" to "\u0995",
        "x" to "\u0995\u09CD\u09B8",
    )

    // Longest match first: order matters.
    private val VOWELS: List<Vowel> = listOf(
        Vowel("aa", "\u0986", "\u09BE"),
        Vowel("ai", "\u0986\u0987", "\u09BE\u0987"),
        Vowel("au", "\u0994", "\u09CC"),
        Vowel("ee", "\u0988", "\u09C0"),
        Vowel("ii", "\u0988", "\u09C0"),
        Vowel("oo", "\u098A", "\u09C2"),
        Vowel("uu", "\u098A", "\u09C2"),
        Vowel("ou", "\u0994", "\u09CC"),
        Vowel("ei", "\u098F\u0987", "\u09C7\u0987"),
        Vowel("oi", "\u0993\u0987", "\u09CB\u0987"),
        Vowel("a", "\u0986", ""),
        Vowel("e", "\u098F", "\u09C7"),
        Vowel("i", "\u0987", "\u09BF"),
        Vowel("o", "\u0993", ""),
        Vowel("u", "\u0989", "\u09C1"),
    )

    private val BENGALI_WORDS: Map<String, String> = mapOf(
        "ami" to "\u0986\u09AE\u09BF",
        "tumi" to "\u09A4\u09C1\u09AE\u09BF",
        "tui" to "\u09A4\u09C1\u0987",
        "apni" to "\u0986\u09AA\u09A8\u09BF",
        "se" to "\u09B8\u09C7",
        "tara" to "\u09A4\u09BE\u09B0\u09BE",
        "ora" to "\u0993\u09B0\u09BE",
        "amra" to "\u0986\u09AE\u09B0\u09BE",
        "tomra" to "\u09A4\u09CB\u09AE\u09B0\u09BE",
        "amar" to "\u0986\u09AE\u09BE\u09B0",
        "tomar" to "\u09A4\u09CB\u09AE\u09BE\u09B0",
        "tar" to "\u09A4\u09BE\u09B0",
        "kichu" to "\u0995\u09BF\u099B\u09C1",
        "kono" to "\u0995\u09CB\u09A8\u09CB",
        "keu" to "\u0995\u09C7\u0989",
        "ke" to "\u0995\u09C7",
        "ki" to "\u0995\u09BF",
        "keno" to "\u0995\u09C7\u09A8",
        "kothay" to "\u0995\u09CB\u09A5\u09BE\u09AF\u09BC",
        "kokhon" to "\u0995\u0996\u09A8",
        "kemon" to "\u0995\u09C7\u09AE\u09A8",
        "ekhon" to "\u098F\u0996\u09A8",
        "ekta" to "\u098F\u0995\u099F\u09BE",
        "ekti" to "\u098F\u0995\u099F\u09BF",
        "ektu" to "\u098F\u0995\u099F\u09C1",
        "eta" to "\u098F\u099F\u09BE",
        "ota" to "\u0993\u099F\u09BE",
        "ei" to "\u098F\u0987",
        "oi" to "\u0993\u0987",
        "ata" to "\u098F\u099F\u09BE",
        "kal" to "\u0995\u09BE\u09B2",
        "aj" to "\u0986\u099C",
        "aaj" to "\u0986\u099C",
        "ebar" to "\u098F\u09AC\u09BE\u09B0",
        "tarpor" to "\u09A4\u09BE\u09B0\u09AA\u09B0",
        "kintu" to "\u0995\u09BF\u09A8\u09CD\u09A4\u09C1",
        "ar" to "\u0986\u09B0",
        "na" to "\u09A8\u09BE",
        "nai" to "\u09A8\u09C7\u0987",
        "nei" to "\u09A8\u09C7\u0987",
        "nato" to "\u09A8\u09BE\u09A4\u09CB",
        "naki" to "\u09A8\u09BE\u0995\u09BF",
        "achhe" to "\u0986\u099B\u09C7",
        "acche" to "\u0986\u099B\u09C7",
        "achi" to "\u0986\u099B\u09BF",
        "acho" to "\u0986\u099B\u09CB",
        "ache" to "\u0986\u099B\u09C7",
        "bhalo" to "\u09AD\u09BE\u09B2\u09CB",
        "valo" to "\u09AD\u09BE\u09B2\u09CB",
        "khub" to "\u0996\u09C1\u09AC",
        "habe" to "\u09B9\u09AC\u09C7",
        "hobe" to "\u09B9\u09AC\u09C7",
        "hbe" to "\u09B9\u09AC\u09C7",
        "hoy" to "\u09B9\u09AF\u09BC",
        "hoye" to "\u09B9\u09AF\u09BC\u09C7",
        "holo" to "\u09B9\u09B2\u09CB",
        "hoyeche" to "\u09B9\u09AF\u09BC\u09C7\u099B\u09C7",
        "bolchi" to "\u09AC\u09B2\u099B\u09BF",
        "bolchhi" to "\u09AC\u09B2\u099B\u09BF",
        "bolcho" to "\u09AC\u09B2\u099B\u09CB",
        "bolbo" to "\u09AC\u09B2\u09AC\u09CB",
        "bolbe" to "\u09AC\u09B2\u09AC\u09C7",
        "bolo" to "\u09AC\u09B2\u09CB",
        "bole" to "\u09AC\u09B2\u09C7",
        "bolche" to "\u09AC\u09B2\u099B\u09C7",
        "diyechi" to "\u09A6\u09BF\u09AF\u09BC\u09C7\u099B\u09BF",
        "diyechhi" to "\u09A6\u09BF\u09AF\u09BC\u09C7\u099B\u09BF",
        "dilam" to "\u09A6\u09BF\u09B2\u09BE\u09AE",
        "dilo" to "\u09A6\u09BF\u09B2\u09CB",
        "kore" to "\u0995\u09B0\u09C7",
        "korchi" to "\u0995\u09B0\u099B\u09BF",
        "korchhi" to "\u0995\u09B0\u099B\u09BF",
        "korbo" to "\u0995\u09B0\u09AC\u09CB",
        "korbe" to "\u0995\u09B0\u09AC\u09C7",
        "korlam" to "\u0995\u09B0\u09B2\u09BE\u09AE",
        "korte" to "\u0995\u09B0\u09A4\u09C7",
        "kora" to "\u0995\u09B0\u09BE",
        "asbe" to "\u0986\u09B8\u09AC\u09C7",
        "ashbe" to "\u0986\u09B8\u09AC\u09C7",
        "asbo" to "\u0986\u09B8\u09AC\u09CB",
        "asche" to "\u0986\u09B8\u099B\u09C7",
        "esechi" to "\u098F\u09B8\u09C7\u099B\u09BF",
        "esho" to "\u098F\u09B8\u09CB",
        "ashi" to "\u0986\u09B8\u09BF",
        "jabe" to "\u09AF\u09BE\u09AC\u09C7",
        "jabo" to "\u09AF\u09BE\u09AC\u09CB",
        "jachhi" to "\u09AF\u09BE\u099A\u09CD\u099B\u09BF",
        "gelam" to "\u0997\u09C7\u09B2\u09BE\u09AE",
        "gechi" to "\u0997\u09C7\u099B\u09BF",
        "gele" to "\u0997\u09C7\u09B2\u09C7",
        "khabo" to "\u0996\u09BE\u09AC\u09CB",
        "khabe" to "\u0996\u09BE\u09AC\u09C7",
        "khacchi" to "\u0996\u09BE\u099A\u09CD\u099B\u09BF",
        "dekho" to "\u09A6\u09C7\u0996\u09CB",
        "dekhi" to "\u09A6\u09C7\u0996\u09BF",
        "dekhbo" to "\u09A6\u09C7\u0996\u09AC\u09CB",
        "shono" to "\u09B6\u09CB\u09A8\u09CB",
        "suno" to "\u09B6\u09CB\u09A8\u09CB",
        "shunchi" to "\u09B6\u09C1\u09A8\u099B\u09BF",
        "bhai" to "\u09AD\u09BE\u0987",
        "vai" to "\u09AD\u09BE\u0987",
        "bon" to "\u09AC\u09CB\u09A8",
        "didi" to "\u09A6\u09BF\u09A6\u09BF",
        "dada" to "\u09A6\u09BE\u09A6\u09BE",
        "baba" to "\u09AC\u09BE\u09AC\u09BE",
        "maa" to "\u09AE\u09BE",
        "khobor" to "\u0996\u09AC\u09B0",
        "gorom" to "\u0997\u09B0\u09AE",
        "thik" to "\u09A0\u09BF\u0995",
        "gare" to "\u0997\u09BE\u09B0\u09C7",
        "chure" to "\u099B\u09C1\u09B0\u09C7",
        "maityr" to "\u09AE\u09BE\u0987\u09A4\u09BF\u09B0",
        "maity" to "\u09AE\u09BE\u0987\u09A4\u09BF",
        "alok" to "\u0986\u09B2\u09CB\u0995",
        "kadra" to "\u0995\u09BE\u09A6\u09B0\u09BE",
        "kolkata" to "\u0995\u09B2\u0995\u09BE\u09A4\u09BE",
        "kolkatar" to "\u0995\u09B2\u0995\u09BE\u09A4\u09BE\u09B0",
        "bangla" to "\u09AC\u09BE\u0982\u09B2\u09BE",
        "banglay" to "\u09AC\u09BE\u0982\u09B2\u09BE\u09AF\u09BC",
        "bangali" to "\u09AC\u09BE\u0982\u09BE\u09B2\u09BF",
        "bengali" to "\u09AC\u09BE\u0982\u09BE\u09B2\u09BF",
        "ebhabe" to "\u098F\u09AD\u09BE\u09AC\u09C7",
        "ekbar" to "\u098F\u0995\u09AC\u09BE\u09B0",
        "tomake" to "\u09A4\u09CB\u09AE\u09BE\u0995\u09C7",
        "amake" to "\u0986\u09AE\u09BE\u0995\u09C7",
        "kothai" to "\u0995\u09CB\u09A5\u09BE\u09AF\u09BC",
        "ekhane" to "\u098F\u0996\u09BE\u09A8\u09C7",
        "okhane" to "\u0993\u0996\u09BE\u09A8\u09C7",
        "bhalobasha" to "\u09AD\u09BE\u09B2\u09CB\u09AC\u09BE\u09B8\u09BE",
        "dhonyobad" to "\u09A7\u09A8\u09CD\u09AF\u09AC\u09BE\u09A6",
        "shuvo" to "\u09B6\u09C1\u09AD",
        "raat" to "\u09B0\u09BE\u09A4",
        "din" to "\u09A6\u09BF\u09A8",
        "mane" to "\u09AE\u09BE\u09A8\u09C7",
        "manush" to "\u09AE\u09BE\u09A8\u09C1\u09B7",
        "ghor" to "\u0998\u09B0",
        "ghore" to "\u0998\u09B0\u09C7",
        "ghar" to "\u0998\u09B0",
        "jol" to "\u099C\u09B2",
        "khabar" to "\u0996\u09BE\u09AC\u09BE\u09B0",
        "cha" to "\u099A\u09BE",
        "kaj" to "\u0995\u09BE\u099C",
        "bhaloi" to "\u09AD\u09BE\u09B2\u09CB\u0987",
    )

    private val LOANWORDS: Map<String, String> = mapOf(
        "current" to "\u0995\u09BE\u09B0\u09C7\u09A8\u09CD\u099F",
        "public" to "\u09AA\u09BE\u09AC\u09B2\u09BF\u0995",
        "news" to "\u09A8\u09BF\u0989\u099C",
        "police" to "\u09AA\u09C1\u09B2\u09BF\u09B6",
        "college" to "\u0995\u09B2\u09C7\u099C",
        "message" to "\u09AE\u09C7\u09B8\u09C7\u099C",
        "group" to "\u0997\u09CD\u09B0\u09C1\u09AA",
        "study" to "\u09B8\u09CD\u099F\u09BE\u09A1\u09BF",
        "exam" to "\u098F\u0995\u09CD\u09B8\u09BE\u09AE",
        "result" to "\u09B0\u09C7\u099C\u09BE\u09B2\u09CD\u099F",
        "online" to "\u0985\u09A8\u09BE\u0987\u09A8",
        "call" to "\u0995\u09B2",
        "ok" to "\u0993\u0995\u09C7",
        "sir" to "\u09B8\u09CD\u09AF\u09BE\u09B0",
        "madam" to "\u09AE\u09CD\u09AF\u09BE\u09A1\u09BE\u09AE",
        "class" to "\u0995\u09CD\u09B2\u09BE\u09B8",
        "teacher" to "\u099F\u09BF\u099A\u09BE\u09B0",
        "hostel" to "\u09B9\u09B8\u09CD\u099F\u09C7\u09B2",
        "signature" to "\u09B8\u09BF\u0997\u09A8\u09C7\u099A\u09BE\u09B0",
        "sign" to "\u09B8\u09BE\u0987\u09A8",
        "bus" to "\u09AC\u09BE\u09B8",
        "train" to "\u099F\u09CD\u09B0\u09C7\u09A8",
        "phone" to "\u09AB\u09CB\u09A8",
        "mobile" to "\u09AE\u09CB\u09AC\u09BE\u0987\u09B2",
        "app" to "\u0985\u09CD\u09AF\u09BE\u09AA",
        "video" to "\u09AD\u09BF\u09A1\u09BF\u0993",
        "song" to "\u0997\u09BE\u09A8",
        "movie" to "\u09AE\u09C1\u09AD\u09BF",
        "time" to "\u099F\u09BE\u0987\u09AE",
        "today" to "\u099F\u09C1\u09A1\u09C7",
        "tomorrow" to "\u099F\u09AE\u09B0\u09CB",
        "update" to "\u0986\u09AA\u09A1\u09C7\u099F",
        "file" to "\u09AB\u09BE\u0987\u09B2",
        "market" to "\u09AE\u09BE\u09B0\u09CD\u0995\u09C7\u099F",
    )

    private val DETECT_EXCLUDE: Set<String> = setOf("ache", "ar", "bon", "cha", "chure", "din", "ei", "eta", "gare", "ghar", "ghor", "hoy", "jol", "kal", "ke", "na", "oi", "ora", "ota", "raat", "se", "tar")

    private val DETECT: Set<String> = BENGALI_WORDS.keys
        .filter { it !in DETECT_EXCLUDE && it.length >= 3 }
        .toSet() + "ki"

    /** Returns Bengali script for romanized Bengali text; any other text is returned as-is. */
    fun prepareForSpeech(text: String): String {
        if (!isRomanizedBengali(text)) return text
        return TOKEN.replace(text) { match -> convertWord(match.value) }
    }

    /**
     * Two or more Bengali-specific words, making up at least a quarter of the Latin words.
     * A single word counts only when it is the whole text, so English sentences stay English.
     */
    fun isRomanizedBengali(text: String): Boolean {
        val words = TOKEN.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()
        if (words.isEmpty()) return false
        val hits = words.count { it in DETECT }
        return if (words.size == 1) hits == 1 else hits >= 2 && hits * 4 >= words.size
    }

    private fun convertWord(word: String): String {
        val key = word.lowercase(Locale.ROOT)
        return BENGALI_WORDS[key] ?: LOANWORDS[key] ?: phonetic(key)
    }

    private fun phonetic(word: String): String {
        val out = StringBuilder()
        var index = 0
        var prev = Prev.NONE
        while (index < word.length) {
            val vowel = VOWELS.firstOrNull { word.startsWith(it.latin, index) }
            if (vowel != null) {
                out.append(if (prev == Prev.CONSONANT) vowel.matra else vowel.independent)
                index += vowel.latin.length
                prev = Prev.VOWEL
                continue
            }
            val consonant = CONSONANTS.firstOrNull { word.startsWith(it.first, index) }
            if (consonant != null) {
                val latin = consonant.first
                if (latin == "ng") {
                    out.append(ANUSVARA)
                    prev = Prev.VOWEL
                } else {
                    out.append(consonant.second)
                    val doubled = latin.length == 1 && index > 0 &&
                        index + 1 < word.length && word[index + 1] == latin[0]
                    if (doubled) out.append(HASANT)
                    prev = Prev.CONSONANT
                }
                index += latin.length
                continue
            }
            out.append(word[index])
            prev = Prev.NONE
            index += 1
        }
        return out.toString()
    }
}