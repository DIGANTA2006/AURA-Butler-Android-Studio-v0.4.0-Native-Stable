package `in`.diganta.aurabutler.data

import java.util.Locale

/** Canonical, provider-independent AURA identity. */
object AuraIdentity {
    const val SHORT_NAME = "AURA"
    const val FULL_NAME = "Autonomous Unified Response Assistant"
    const val CREATOR_NAME = "Diganta Chakraborty"

    const val CREATOR_PROFILE =
        "Diganta Chakraborty is AURA's creator, owner and primary developer. " +
            "He is a B.Tech Computer Science and Engineering student at the " +
            "College of Engineering & Management, Kolaghat, and is from Kadra, " +
            "Chandrakona Road, West Bengal, India."

    fun nameReply(formOfAddress: String): String =
        "I am AURA, your $FULL_NAME, built for you, $formOfAddress."

    @Suppress("UNUSED_PARAMETER")
    fun creatorReply(formOfAddress: String): String =
        "AURA\n\nI was designed and developed by $CREATOR_NAME, my creator and owner. " +
            CREATOR_PROFILE

    @Suppress("UNUSED_PARAMETER")
    fun ownerReply(formOfAddress: String): String =
        "You are $CREATOR_NAME, my creator, owner and primary developer. " +
            "You are a B.Tech Computer Science and Engineering student at the " +
            "College of Engineering & Management, Kolaghat, from Kadra, " +
            "Chandrakona Road, West Bengal, India."

    fun systemIdentity(): String = """
        Immutable identity:
        - Your name is AURA.
        - AURA means $FULL_NAME.
        - You were designed and developed by $CREATOR_NAME.
        - $CREATOR_PROFILE
    """.trimIndent()

    fun isNameQuestion(text: String): Boolean {
        val value = normalize(text)
        return value.containsAny(
            "what is your name",
            "what's your name",
            "whats your name",
            "who are you",
            "tell me your name",
            "your full name",
            "full form of aura",
            "full name of aura",
            "what does aura stand for",
            "তোমার নাম কি",
            "তোমার নাম কী",
            "তুমি কে",
            "অরার পুরো নাম কি",
            "ऑरा का पूरा नाम क्या है",
            "तुम्हारा नाम क्या है",
            "आपका नाम क्या है",
            "तुम कौन हो"
        )
    }

    fun isCreatorQuestion(text: String): Boolean {
        val value = normalize(text)
        if (value.containsAny(
                "who created you",
                "who create you",
                "who made you",
                "who make you",
                "who developed you",
                "who develop you",
                "who devolop you",
                "who devolap you",
                "who designed you",
                "who built you",
                "who build you",
                "who is your creator",
                "who is your developer",
                "tell me about your creator",
                "tell me about your developer",
                "your creator",
                "your developer",
                "তোমাকে কে বানিয়েছে",
                "তোমাকে কে বানিয়েছে",
                "তোমাকে কে তৈরি করেছে",
                "তোমার নির্মাতা কে",
                "অরাকে কে বানিয়েছে",
                "ऑरा को किसने बनाया",
                "तुम्हें किसने बनाया",
                "तुम्हारा निर्माता कौन है",
                "तुम्हारा डेवलपर कौन है"
            )
        ) {
            return true
        }

        val asksIdentity = value.containsAny("who", "tell me", "please tell")
        val creatorWord = value.containsAny(
            "creat", "made", "make", "develop", "devolop", "devolap",
            "design", "build", "built", "creator", "developer"
        )
        val target = value.containsAny(" you", "your", "aura")
        return asksIdentity && creatorWord && target
    }

    fun isOwnerProfileQuestion(text: String): Boolean {
        val value = normalize(text)
        return value.containsAny(
            "who am i",
            "who i am",
            "tell me about me",
            "tell me about myself",
            "what do you know about me",
            "describe me",
            "who is diganta",
            "tell me about diganta",
            "tell me about your owner",
            "আমি কে",
            "আমার সম্পর্কে বল",
            "দিগন্ত কে",
            "तुम्हारा मालिक कौन है",
            "मैं कौन हूँ",
            "मेरे बारे में बताओ",
            "दिगंत कौन है"
        )
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun String.containsAny(vararg values: String): Boolean =
        values.any(::contains)
}
