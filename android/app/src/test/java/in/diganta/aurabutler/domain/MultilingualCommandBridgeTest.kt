package `in`.diganta.aurabutler.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MultilingualCommandBridgeTest {
    @Test
    fun `bridges common Bengali commands`() {
        assertEquals("open YouTube", MultilingualCommandBridge.normalizeKnownCommand("ইউটিউব খোলো"))
        assertEquals("turn on flashlight", MultilingualCommandBridge.normalizeKnownCommand("টর্চ চালু করো"))
        assertEquals("weather today", MultilingualCommandBridge.normalizeKnownCommand("আজকের আবহাওয়া বলো"))
        assertEquals("deep research সৌরশক্তি", MultilingualCommandBridge.normalizeKnownCommand("গভীর গবেষণা সৌরশক্তি"))
    }

    @Test
    fun `bridges common Hindi commands`() {
        assertEquals("open camera", MultilingualCommandBridge.normalizeKnownCommand("कैमरा खोलो"))
        assertEquals("volume up", MultilingualCommandBridge.normalizeKnownCommand("वॉल्यूम बढ़ाओ"))
        assertEquals("news today", MultilingualCommandBridge.normalizeKnownCommand("आज की खबर बताओ"))
        assertEquals("deep research बैटरी तकनीक", MultilingualCommandBridge.normalizeKnownCommand("गहन शोध बैटरी तकनीक"))
    }

    @Test
    fun `leaves normal conversation untouched`() {
        val input = "আমার আজকের পড়াশোনার পরিকল্পনা করো"
        assertEquals(input, MultilingualCommandBridge.normalizeKnownCommand(input))
    }
}
