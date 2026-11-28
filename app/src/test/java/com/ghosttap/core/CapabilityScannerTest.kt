package com.ghosttap.core

import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityScannerTest {

    class RegisteredOwner {
        fun probe() = Unit
    }

    class UnregisteredOwner {
        fun probe() = Unit
    }

    class PropertyOwner {
        val ready: Boolean = true
    }

    @Test
    fun sameNamedMethodOnDifferentOwnerRemainsCandidate() {
        CapabilityRegistry.register(
            IntelCapability(
                id = "test.scanner.registered-owner",
                title = "Scanner test probe",
                detail = "Test-only scanner registration.",
                category = "TEST",
                readOnly = true,
                riskLevel = "READ",
                sourceMethod = "RegisteredOwner.probe",
                run = { OpResult(true, "ok") }
            )
        )

        val report = CapabilityScanner.scan(
            listOf(RegisteredOwner(), UnregisteredOwner()),
            CapabilityRegistry
        )

        assertTrue(report.registered.any { it.startsWith("RegisteredOwner.probe(") })
        assertTrue(report.candidates.any { it.startsWith("UnregisteredOwner.probe(") })
    }

    class SecondaryOwner {
        fun status() = Unit
    }

    @Test
    fun operationCanDeclareMultipleConcreteSourceMethods() {
        CapabilityRegistry.register(
            IntelCapability(
                id = "test.scanner.multiple-sources",
                title = "Scanner test multiple sources",
                detail = "Test-only operation with multiple concrete sources.",
                category = "TEST",
                readOnly = true,
                riskLevel = "READ",
                sourceMethod = "RegisteredOwner.probe;SecondaryOwner.status",
                run = { OpResult(true, "ok") }
            )
        )

        val report = CapabilityScanner.scan(
            listOf(RegisteredOwner(), SecondaryOwner()),
            CapabilityRegistry
        )

        assertTrue(report.registered.any { it.startsWith("RegisteredOwner.probe(") })
        assertTrue(report.registered.any { it.startsWith("SecondaryOwner.status(") })
        assertTrue(report.candidates.none { it.startsWith("SecondaryOwner.status(") })
    }

    @Test
    fun kotlinPropertyAccessorMatchesItsRegisteredPropertyName() {
        CapabilityRegistry.register(
            IntelCapability(
                id = "test.scanner.property-owner",
                title = "Scanner test property",
                detail = "Test-only scanner registration.",
                category = "TEST",
                readOnly = true,
                riskLevel = "READ",
                sourceMethod = "PropertyOwner.ready",
                run = { OpResult(true, "ok") }
            )
        )

        val report = CapabilityScanner.scan(listOf(PropertyOwner()), CapabilityRegistry)

        assertTrue(report.registered.any { it.startsWith("PropertyOwner.getReady(") })
        assertTrue(report.candidates.none { it.startsWith("PropertyOwner.getReady(") })
    }
    class OverloadedOwner {
        fun inspect() = Unit
        fun inspect(value: Int) = value
    }

    @Test
    fun scannerPreservesEveryOverloadedMethodSignature() {
        val report = CapabilityScanner.scan(listOf(OverloadedOwner()), CapabilityRegistry)

        assertTrue(report.candidates.any { it == "OverloadedOwner.inspect()" })
        assertTrue(report.candidates.any { it == "OverloadedOwner.inspect(Int)" })
    }
}
