package com.ghosttap.core

/**
 * Shared, navigation-independent record for the task currently being worked.
 * Transport owners keep their real sockets/GATT/USB handles; this record
 * carries identity, verified health, discovered controls and task history
 * between function workspaces without pretending to own those transports.
 */
data class TaskWorkspaceSession(
    val taskId: String,
    val objective: String,
    val targetKind: String,
    val targetId: String,
    val targetTitle: String,
    val route: String,
    val connectionState: String,
    val controls: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    val lastResult: String = "",
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Matches a task's concrete destination against Navigation Compose's
 * destination route. Parameterized routes are exposed as patterns, so
 * the target id must be checked separately.
 */
fun TaskWorkspaceSession.matchesDestination(
    destinationRoute: String?,
    destinationTargetId: String? = null
): Boolean {
    if (route.isBlank() || destinationRoute.isNullOrBlank()) return false
    if (destinationRoute == route || destinationRoute.startsWith("$route/")) return true
    return destinationRoute == "target/{id}" &&
        destinationTargetId != null &&
        destinationTargetId == targetId &&
        route == "target/$targetId"
}
