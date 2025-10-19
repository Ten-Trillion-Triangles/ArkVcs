package Structs

/**
 * Ark user manifest. Holds all registered users, and the admin account.
 */
@kotlinx.serialization.Serializable
data class UserManifest(val cinit: Boolean = false)
{
    val registeredUsers = mutableMapOf<String, UserSettings>()
    val deactivatedUsers = mutableListOf<String>()
    val adminAccount = UserSettings()
}
