package Structs

/**
 * Settings for all known server addresses for Ark. Maps each url to the locked auth key
 * that can be connected to that server.
 */
@kotlinx.serialization.Serializable
data class ConnectionSettings(val cinit : Boolean = false)
{
    /**
     * Map of server urls to locked auth keys.
     * @see LockedAuthKey
     */
    val registeredServers = mutableMapOf<String, String>()
}
