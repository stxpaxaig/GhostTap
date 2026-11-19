package com.ghosttap.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the v3.2 TV additions to the device classifier
 * ([DeviceClassifier]): TV names and the Bluetooth audio/video
 * device class, plus the guards that keep other gadgets from being
 * called TVs.
 *
 * Pure Kotlin, JUnit 4 — no Android framework.
 */
class DeviceClassifierTest {

    @Test
    fun `a tv by name is a tv`() {
        val idn = DeviceClassifier.identify(DeviceSignals(name = "Living Room TV"))
        assertEquals("TV", idn.label)
        assertEquals(Confidence.MEDIUM, idn.confidence)
    }

    @Test
    fun `bravia is a tv`() {
        val idn = DeviceClassifier.identify(DeviceSignals(name = "Sony Bravia"))
        assertEquals("TV", idn.label)
        assertEquals(Confidence.MEDIUM, idn.confidence)
    }

    @Test
    fun `a tv brand on its own is only a weak tv hint`() {
        val idn = DeviceClassifier.identify(DeviceSignals(name = "Samsung QN90A"))
        assertEquals("TV", idn.label)
        assertEquals(Confidence.LOW, idn.confidence)
    }

    @Test
    fun `sony headphones are headphones not a tv`() {
        // The headphones rule runs before the TV brand rule.
        val idn = DeviceClassifier.identify(DeviceSignals(name = "Sony WH-1000XM5"))
        assertEquals("HEADPHONES", idn.label)
    }

    @Test
    fun `philips hue is smart home not a tv`() {
        // Philips is deliberately NOT in the TV brand list: Hue bulbs
        // are far more common on Bluetooth than Philips TVs, and a
        // Philips TV advertises "TV" in its name anyway.
        val idn = DeviceClassifier.identify(DeviceSignals(name = "Philips Hue Bridge"))
        assertEquals("SMART HOME DEVICE", idn.label)
    }

    @Test
    fun `audio-video device class set-top box is a tv`() {
        // BluetoothClass major 0x400 (audio/video), minor 36 (set-top box).
        val idn = DeviceClassifier.identify(DeviceSignals(deviceClassFull = 0x400 + 36))
        assertEquals("TV", idn.label)
        assertEquals(Confidence.MEDIUM, idn.confidence)
    }
}
