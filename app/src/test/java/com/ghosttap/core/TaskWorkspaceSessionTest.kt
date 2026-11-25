package com.ghosttap.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskWorkspaceSessionTest {
    private fun session(route: String = "target/ble-42", targetId: String = "ble-42") =
        TaskWorkspaceSession(
            taskId = "task-1",
            objective = "Connect to device",
            targetKind = "BLE",
            targetId = targetId,
            targetTitle = "Test device",
            route = route,
            connectionState = "CONNECTED"
        )

    @Test
    fun `matches concrete destination route`() {
        assertTrue(session("network_tools").matchesDestination("network_tools"))
    }

    @Test
    fun `matches child destination under function route`() {
        assertTrue(session("target/ble-42").matchesDestination("target/ble-42/details"))
    }

    @Test
    fun `matches parameterized device destination by target id`() {
        assertTrue(session().matchesDestination("target/{id}", "ble-42"))
    }

    @Test
    fun `does not match another device on same parameterized route`() {
        assertFalse(session().matchesDestination("target/{id}", "ble-99"))
    }

    @Test
    fun `does not match unrelated destination`() {
        assertFalse(session().matchesDestination("visual_intel"))
    }

    @Test
    fun `blank route never matches`() {
        assertFalse(session(route = "").matchesDestination("home"))
    }

    @Test
    fun `continuation prompt carries live session identity controls and evidence`() {
        val active = session().copy(
            controls = listOf("read characteristic", "write characteristic"),
            evidence = listOf("GATT service discovered", "characteristic responded"),
            events = listOf("Connected", "Capabilities mapped"),
            lastResult = "Device acknowledged the last request"
        )
        val prompt = active.continuationPrompt("Read the current status")
        assertTrue(prompt.contains("Do not restart discovery or reconnect"))
        assertTrue(prompt.contains("Test device (BLE, id=ble-42)"))
        assertTrue(prompt.contains("read characteristic | write characteristic"))
        assertTrue(prompt.contains("characteristic responded"))
        assertTrue(prompt.contains("Read the current status"))
    }

    @Test
    fun `context-only task exists before a transport is available`() {
        val task = createContextTaskSession(
            taskId = "task-context-1",
            objective = "Investigate the unknown controller",
            subject = "Unknown controller"
        )
        assertEquals("TASK", task.targetKind)
        assertEquals("CONTEXT_READY", task.connectionState)
        assertEquals("home", task.route)
        assertEquals("Investigate the unknown controller", task.objective)
        assertEquals("Unknown controller", task.targetTitle)
    }

    @Test
    fun `context-only task uses useful fallback labels`() {
        val task = createContextTaskSession("task-context-2", "", "")
        assertEquals("Ghost Tap task", task.objective)
        assertEquals("Current objective", task.targetTitle)
    }

    @Test
    fun `continuation prompt carries grounded next actions`() {
        val active = session().copy(nextActions = listOf("Read device status", "Inspect available services"))
        val prompt = active.continuationPrompt("Proceed")
        assertTrue(prompt.contains("Suggested next actions: Read device status | Inspect available services"))
    }

    @Test
    fun `routes selected capability families to their existing workspaces`() {
        assertEquals("network_tools", workspaceRouteForCategories(listOf("SYSTEM", "NETWORK")))
        assertEquals("nfc_tools", workspaceRouteForCategories(listOf("NFC")))
        assertEquals("usb_uart", workspaceRouteForCategories(listOf("USB")))
        assertEquals("vesp32", workspaceRouteForCategories(listOf("ESP32")))
        assertEquals("visual_intel", workspaceRouteForCategories(listOf("VISUAL")))
        assertEquals("investigations", workspaceRouteForCategories(listOf("BLE")))
    }

    @Test
    fun `generic or unknown capability categories do not force navigation`() {
        assertEquals(null, workspaceRouteForCategories(listOf("SYSTEM", "UNKNOWN")))
    }

    @Test
    fun `first concrete category in the plan determines the workspace`() {
        assertEquals("nfc_tools", workspaceRouteForCategories(listOf("SYSTEM", "NFC", "NETWORK")))
    }

    @Test
    fun `context ready label distinguishes NFC from restored physical sessions`() {
        assertEquals(
            "Task context retained · connection needs verification",
            workspaceConnectionLabel(session("network_tools").copy(connectionState = "CONTEXT_READY"))
        )
        assertEquals(
            "NFC tag not present · context retained",
            workspaceConnectionLabel(session("nfc_tools").copy(targetKind = "NFC", connectionState = "CONTEXT_READY"))
        )
    }

    @Test
    fun `connected label reports a verified live connection`() {
        assertEquals("Live connection", workspaceConnectionLabel(session().copy(connectionState = "CONNECTED")))
    }

    @Test
    fun `restored physical task resumes its last function workspace`() {
        assertEquals("network_tools", workspaceResumeDestination(session("network_tools")))
        assertEquals("target/ble-42", workspaceResumeDestination(session()))
    }

    @Test
    fun `context task on home does not trigger redundant navigation`() {
        assertEquals(null, workspaceResumeDestination(createContextTaskSession(
            taskId = "task-home",
            objective = "Research a device",
            subject = "Unknown device"
        )))
    }

    @Test
    fun `unknown restored route does not trigger invalid navigation`() {
        assertEquals(null, workspaceResumeDestination(session("removed_screen")))
        assertEquals(null, workspaceResumeDestination(session("target/")))
    }

    @Test
    fun `restored target route is trimmed and preserved`() {
        assertEquals("target/ble-42", workspaceResumeDestination(session(" target/ble-42 ")))
    }

    @Test
    fun `task workspace survives serialization with controls evidence and next actions`() {
        val original = session("network_tools").copy(
            controls = listOf("scan", "inspect host"),
            evidence = listOf("host responded"),
            events = listOf("investigation completed"),
            lastResult = "Host is reachable",
            nextActions = listOf("Inspect services")
        )
        val restored = Json.decodeFromString<TaskWorkspaceSession>(
            Json.encodeToString(original)
        )
        assertEquals(original, restored)
    }
}
