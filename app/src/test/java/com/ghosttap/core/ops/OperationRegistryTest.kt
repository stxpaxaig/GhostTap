package com.ghosttap.core.ops

import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.OpResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unit tests for the operation registry and its CapabilityRegistry
 * bridge. Pure Kotlin, JUnit 4 — no Android framework, no ViewModels.
 * Test ids use the "test.pkg0." prefix so they can never collide with
 * real capability ids in the shared CapabilityRegistry singleton.
 */
class OperationRegistryTest {

    private fun testOp(
        id: String,
        area: OperationArea = OperationArea.SYSTEM,
        route: String? = null
    ): Operation = Operation(
        id = id,
        title = "Test: $id",
        description = "A test operation that always succeeds.",
        area = area,
        execute = { OpResult(true, "did it", "test evidence") },
        route = route
    )

    @Before
    fun setUp() {
        OperationRegistry.clear()
    }

    @After
    fun tearDown() {
        OperationRegistry.clear()
    }

    @Test
    fun concurrentRegistrationAndCapabilityPublicationUseStableSnapshots() {
        val workers = 8
        val perWorker = 20
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val tasks = (0 until workers).map { worker ->
                pool.submit {
                    start.await()
                    repeat(perWorker) { index ->
                        val id = "test.pkg0.concurrent.$worker.$index"
                        OperationRegistry.register(
                            testOp(
                                id,
                                if (index % 2 == 0) OperationArea.NETWORK else OperationArea.BLE
                            )
                        )
                        // Exercise reads while other threads are still registering.
                        OperationRegistry.all()
                        OperationRegistry.byArea(OperationArea.NETWORK)
                        OperationRegistry.areas()
                        CapabilityRegistry.all()
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(15, TimeUnit.SECONDS) }

            // Publish once after all writers finish, then verify every operation
            // is visible through the same capability registry used by VNIC.
            OperationRegistry.publishToCapabilityRegistry(OperationContext())
            val expected = workers * perWorker
            val operations = OperationRegistry.all().filter { it.id.startsWith("test.pkg0.concurrent.") }
            val published = CapabilityRegistry.all().filter { it.id.startsWith("test.pkg0.concurrent.") }
            assertEquals(expected, operations.size)
            assertEquals(expected, operations.map { it.id }.toSet().size)
            assertEquals(expected, published.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun registerAndLookupById() {
        val op = testOp("test.pkg0.lookup")
        OperationRegistry.register(op)
        assertEquals(op, OperationRegistry.get("test.pkg0.lookup"))
    }

    @Test
    fun lookupUnknownIdReturnsNull() {
        assertNull(OperationRegistry.get("test.pkg0.nope"))
    }

    @Test
    fun listReturnsAllInRegistrationOrder() {
        val first = testOp("test.pkg0.first")
        val second = testOp("test.pkg0.second")
        OperationRegistry.register(first)
        OperationRegistry.register(second)
        assertEquals(listOf(first, second), OperationRegistry.all())
    }

    @Test
    fun registeringSameIdKeepsLatest() {
        OperationRegistry.register(testOp("test.pkg0.dup"))
        val updated = testOp("test.pkg0.dup").copy(title = "Updated title")
        OperationRegistry.register(updated)
        assertEquals("Updated title", OperationRegistry.get("test.pkg0.dup")?.title)
        assertEquals(1, OperationRegistry.all().size)
    }

    @Test
    fun listByAreaFilters() {
        OperationRegistry.register(testOp("test.pkg0.ble", OperationArea.BLE))
        OperationRegistry.register(testOp("test.pkg0.net", OperationArea.NETWORK))
        OperationRegistry.register(testOp("test.pkg0.ble2", OperationArea.BLE))
        assertEquals(
            listOf("test.pkg0.ble", "test.pkg0.ble2"),
            OperationRegistry.byArea(OperationArea.BLE).map { it.id }
        )
        assertEquals(
            listOf("test.pkg0.net"),
            OperationRegistry.byArea(OperationArea.NETWORK).map { it.id }
        )
    }

    @Test
    fun areasListsFirstSeenOrder() {
        OperationRegistry.register(testOp("test.pkg0.a", OperationArea.NFC))
        OperationRegistry.register(testOp("test.pkg0.b", OperationArea.BLE))
        OperationRegistry.register(testOp("test.pkg0.c", OperationArea.NFC))
        assertEquals(
            listOf(OperationArea.NFC, OperationArea.BLE),
            OperationRegistry.areas()
        )
    }

    @Test
    fun asCapabilityKeepsIdTitleDetailAndCategory() {
        val op = testOp("test.pkg0.cap", OperationArea.NETWORK)
        val cap = op.asCapability(OperationContext())
        assertEquals("test.pkg0.cap", cap.id)
        assertEquals(op.title, cap.title)
        assertEquals(op.description, cap.detail)
        assertEquals("NETWORK", cap.category)
        assertTrue(cap.readOnly)
        assertEquals("READ", cap.riskLevel)
        assertFalse(cap.verified)
    }

    @Test
    fun asCapabilityForScreenOpNamesItsScreen() {
        val op = testOp("test.pkg0.screen", OperationArea.USB, route = "usb_uart")
        val cap = op.asCapability(OperationContext())
        assertTrue(cap.sourceMethod.contains("usb_uart"))
    }

    @Test
    fun publishMakesOperationsVisibleToCapabilityRegistry() {
        val op = testOp("test.pkg0.published", OperationArea.VISUAL)
        OperationRegistry.register(op)
        OperationRegistry.publishToCapabilityRegistry(OperationContext())
        val cap = CapabilityRegistry.get("test.pkg0.published")
        assertNotNull(cap)
        assertEquals("VISUAL", cap?.category)
        assertEquals(op.title, cap?.title)
    }

    @Test
    fun publishedCapabilityRunsTheOperation() = runBlocking {
        val op = testOp("test.pkg0.runnable")
        OperationRegistry.register(op)
        OperationRegistry.publishToCapabilityRegistry(OperationContext())
        val cap = CapabilityRegistry.get("test.pkg0.runnable")
        assertNotNull(cap)
        val result = cap!!.run(emptyMap())
        assertTrue(result.ok)
        assertEquals("test evidence", result.data)
    }

    @Test
    fun asCapabilitySeedsPendingChoiceFromStepArgs() = runBlocking {
        // Piece 3: a plan step's args reach the operation through the
        // context's choice channel — this is how the intelligence hands
        // a composed command to termux.run-command.
        val context = OperationContext()
        val op = Operation(
            id = "test.pkg0.choice",
            title = "Test: choice echo",
            description = "Echoes the pending choice back.",
            area = OperationArea.SYSTEM,
            execute = { ctx ->
                val choice = ctx.pendingChoice ?: "(none)"
                OpResult(true, "choice was: $choice")
            }
        )
        val cap = op.asCapability(context)
        val result = cap.run(mapOf("command" to "ping -c 3 192.168.1.42"))
        assertTrue(result.ok)
        assertEquals("choice was: ping -c 3 192.168.1.42", result.summary)
    }

    @Test
    fun asCapabilitySeedsScriptArgsFromArgPrefixedKeys() = runBlocking {
        // Piece 3: "arg.<name>" step args become the operation's named
        // script arguments — how a plan hands a host to a script.
        val context = OperationContext()
        val op = Operation(
            id = "test.pkg0.scriptargs",
            title = "Test: script args echo",
            description = "Echoes the pending args back.",
            area = OperationArea.SYSTEM,
            execute = { ctx ->
                OpResult(true, "args were: ${ctx.pendingArgs}")
            }
        )
        val cap = op.asCapability(context)
        val result = cap.run(
            mapOf("scriptTitle" to "Check the network", "arg.host" to "example.com")
        )
        assertTrue(result.ok)
        assertEquals("args were: {host=example.com}", result.summary)
        // The channel is restored after the run — nothing leaks into
        // the next operation on the same context.
        assertTrue(context.pendingArgs.isEmpty())
        assertNull(context.pendingChoice)
    }

    @Test
    fun asCapabilityRestoresAPreExistingPendingChoice() = runBlocking {
        // A manual run that already had a pending choice (set by the
        // entry screen) is never clobbered by a seeded step arg.
        val context = OperationContext()
        context.pendingChoice = "picked by hand"
        val op = Operation(
            id = "test.pkg0.restore",
            title = "Test: restore",
            description = "Reads the choice, then succeeds.",
            area = OperationArea.SYSTEM,
            execute = { ctx -> OpResult(true, "saw: ${ctx.pendingChoice}") }
        )
        val cap = op.asCapability(context)
        val result = cap.run(mapOf("command" to "ping -c 3 192.168.1.42"))
        assertTrue(result.ok)
        assertEquals("saw: ping -c 3 192.168.1.42", result.summary)
        assertEquals("picked by hand", context.pendingChoice)
    }

    @Test
    fun asCapabilityWithNoChoiceArgsLeavesTheChannelAlone() = runBlocking {
        // Steps for operations that take no choice (host, targetId,
        // …) must not touch pendingChoice.
        val context = OperationContext()
        val op = testOp("test.pkg0.nochoice")
        val cap = op.asCapability(context)
        val result = cap.run(mapOf("host" to "192.168.1.42"))
        assertTrue(result.ok)
        assertNull(context.pendingChoice)
    }
}
