package `in`.diganta.aurabutler.maintenance

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkSafetyCheckerTest {
    private fun levelOf(link: String) = LinkSafetyChecker.check(link).level

    @Test
    fun officialHttpsLinkIsSafe() {
        assertEquals(LinkSafetyChecker.Level.SAFE, levelOf("https://www.google.com/search?q=weather"))
    }

    @Test
    fun officialBankSubdomainIsSafe() {
        assertEquals(LinkSafetyChecker.Level.SAFE, levelOf("https://www.sbi.co.in"))
    }

    @Test
    fun lookalikeLoginLinkIsHighRisk() {
        assertEquals(LinkSafetyChecker.Level.HIGH_RISK, levelOf("http://paypal-secure-login.xyz/verify"))
    }

    @Test
    fun rawIpLoginLinkIsHighRisk() {
        assertEquals(LinkSafetyChecker.Level.HIGH_RISK, levelOf("http://192.168.1.10/login"))
    }

    @Test
    fun credentialsInAddressAreHighRisk() {
        assertEquals(LinkSafetyChecker.Level.HIGH_RISK, levelOf("https://user:pass@evil.com"))
    }

    @Test
    fun shortenedLinkIsCaution() {
        assertEquals(LinkSafetyChecker.Level.CAUTION, levelOf("bit.ly/3abc"))
    }

    @Test
    fun plainTextIsInvalid() {
        assertEquals(LinkSafetyChecker.Level.INVALID, levelOf("not a link"))
    }
}