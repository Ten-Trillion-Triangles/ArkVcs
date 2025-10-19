package Util

import Enums.Permissions
import Log.arkLog
import Structs.UserSettings
import Tasks.Enums.TaskCategory

/**
 * Exception thrown when a user lacks the required permissions for an action.
 */
class InsufficientPermissionsException(message: String) : Exception(message)

/**
 * Checks if a user has the required permission level and throws an exception if not.
 * 
 * @param user The user to check permissions for
 * @param requiredPermission The minimum permission level required
 * @param action Description of the action being attempted (for error message)
 * @throws InsufficientPermissionsException if user lacks required permissions
 */
fun requirePermission(user: UserSettings, requiredPermission: Permissions, action: String) {
    if (!hasPermission(user.permissions, requiredPermission)) {
        arkLog(TaskCategory.Security, "Invalid user permissions ${user.username} for ${requiredPermission.toString()}")
        throw InsufficientPermissionsException("User '${user.username}' lacks ${requiredPermission.name} permission for: $action")
    }
}

/**
 * Checks if the user's permission level meets or exceeds the required level.
 * 
 * @param userPermission The user's current permission level
 * @param requiredPermission The minimum required permission level
 * @return true if user has sufficient permissions, false otherwise
 */
private fun hasPermission(userPermission: Permissions, requiredPermission: Permissions): Boolean {
    val permissionHierarchy = listOf(Permissions.None, Permissions.ReadOnly, Permissions.Write, Permissions.Maintainer, Permissions.Admin)
    val userLevel = permissionHierarchy.indexOf(userPermission)
    val requiredLevel = permissionHierarchy.indexOf(requiredPermission)
    return userLevel >= requiredLevel
}