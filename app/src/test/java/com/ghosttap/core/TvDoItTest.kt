package com.ghosttap.core

import com.ghosttap.data.ScanTarget
import com.ghosttap.data.TargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the v3.2 TV do-it flow ([isTvCommand],
 * [findTvCandidates], [mayAutoConnect]).
 *
 * Pure Kotlin, JUnit 4 — no Android framework. ScanTarget fakes
 * carry only what a real scan provides: name, the classifier's
 * `likely` label + confidence, and signal strength.
 */
class TvDoItTest {

    private fun target(
        id: String,
        name: String,
        likely: String? = null,
        confidence: String? = null,
        rssi: Int? = null
    ) = ScanTarget(
        id = id,
        name = name,
        type = TargetType.BLE,
        rssi = rssi,
        likely = likely,
        confidence = confidence
    )

    // ----- isTvCommand -----

    @Test
    fun `the exact words from the field test are a tv command`() {
        assertTrue(isTvCommand("find me a tv close by to connect to"))
    }

    @Test
    fun `tv words in other phrasings are tv commands`() {
        assertTrue(isTvCommand("turn on the tv"))
        assertTrue(isTvCommand("connect to the roku"))
        assertTrue(isTvCommand("find my chromecast"))
        assertTrue(isTvCommand("is there a television nearby"))
    }

    @Test
    fun `non-tv words are not tv commands`() {
        assertFalse(isTvCommand("find me a printer"))
        assertFalse(isTvCommand("check the network"))
        assertFalse(isTvCommand("what bluetooth devices are near me"))
        assertFalse(isTvCommand("turn on the lights"))
    }

    // ----- findTvCandidates -----

    @Test
    fun `a device classified as TV is a candidate`() {
        val found = findTvCandidates(
            listOf(target("ble:AA:BB:CC:DD:EE:01", "Living Room TV", likely = "TV", confidence = "Medium", rssi = -70))
        )
        assertEquals(1, found.size)
        assertEquals("Living Room TV", found[0].name)
        assertEquals(Confidence.MEDIUM, found[0].confidence)
        assertEquals("looks like a TV", found[0].confidenceWords)
    }

    @Test
    fun `a high-confidence TV ranks first`() {
        val found = findTvCandidates(
            listOf(
                target("ble:02", "Samsung QN90A", likely = null, rssi = -60),
                target("ble:01", "Bedroom TV", likely = "TV", confidence = "High", rssi = -80)
            )
        )
        assertEquals(2, found.size)
        assertEquals("Bedroom TV", found[0].name)
        assertEquals("Samsung QN90A", found[1].name)
        assertEquals("might be a TV", found[1].confidenceWords)
    }

    @Test
    fun `a tv brand name alone is only a weak hint`() {
        val found = findTvCandidates(
            listOf(target("ble:03", "Samsung QN90A", likely = null, rssi = -60))
        )
        assertEquals(1, found.size)
        assertEquals(Confidence.LOW, found[0].confidence)
    }

    @Test
    fun `a device already identified as something else is never a tv`() {
        // Sony makes headphones too — the classifier said HEADPHONES,
        // so the brand name must not overrule it.
        val found = findTvCandidates(
            listOf(
                target("ble:04", "Sony WH-1000XM5", likely = "HEADPHONES", confidence = "Medium", rssi = -50),
                target("ble:05", "My Speaker", likely = "SPEAKER", confidence = "Medium", rssi = -55)
            )
        )
        assertTrue("headphones and speakers must not be TV candidates", found.isEmpty())
    }

    @Test
    fun `unknown devices with no tv name are not candidates`() {
        val found = findTvCandidates(
            listOf(target("ble:06", "BLE-AA:BB:CC:DD:EE:06", likely = "UNKNOWN BLE DEVICE", confidence = "Low", rssi = -70))
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `closer tv wins among equal confidence`() {
        val found = findTvCandidates(
            listOf(
                target("ble:07", "Far TV", likely = "TV", confidence = "Medium", rssi = -85),
                target("ble:08", "Near TV", likely = "TV", confidence = "Medium", rssi = -55)
            )
        )
        assertEquals("Near TV", found[0].name)
        assertEquals("Far TV", found[1].name)
    }

    // ----- mayAutoConnect -----

    @Test
    fun `one clear tv may connect itself`() {
        val one = listOf(
            TvCandidate("ble:01", "Bedroom TV", Confidence.MEDIUM, "looks like a TV")
        )
        assertTrue(mayAutoConnect(one))
    }

    @Test
    fun `one weak hint must not connect itself`() {
        val one = listOf(
            TvCandidate("ble:02", "Samsung QN90A", Confidence.LOW, "might be a TV")
        )
        assertFalse("a LOW-confidence hint needs the user to pick", mayAutoConnect(one))
    }

    @Test
    fun `several tvs must not connect themselves`() {
        val two = listOf(
            TvCandidate("ble:01", "Bedroom TV", Confidence.MEDIUM, "looks like a TV"),
            TvCandidate("ble:02", "Living Room TV", Confidence.MEDIUM, "looks like a TV")
        )
        assertFalse(mayAutoConnect(two))
    }

    @Test
    fun `none found means nothing to connect`() {
        assertFalse(mayAutoConnect(emptyList()))
    }
}
