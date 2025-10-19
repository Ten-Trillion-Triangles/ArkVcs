package Structs

import Enums.LogLevel
import Enums.Permissions
import kotlinx.serialization.Serializable

/**
 * Global settings file for the ark server. Most settings will be applied only at startup, but some settings may be
 * changed at runtime such as the ip address and port.
 */
@Serializable
data class GlobalSettings(var init : Boolean = false)
{
    var ip = "0.0.0.0"
    var port = 8888

    var requestTimeout = 1000 //In seconds. Locks out account access if invalid requests are made too fast.
    var maxBadRequestLimit = 50 //Max number of bad requests allowed before account is locked
    var ddosLockoutLimit = 500 //Max number of bad requests allowed before system goes into a full lockout state.

    var maxFileSockets = 1000 //Maximum upload/download connections allowed per server.
    var fileSocketPortRange = "7000-8000" //Default port range for file transfer sockets.

    //Default permissions for new users. This will be ignored for the first user ever which will always be admin.
    var defaultNewUserPermissionLevel = Permissions.Write

    //If true use file compression on all stored files on the ark server.
    var useFileCompression = true

    /**
     * Number of milliseconds between taskGraph sweeps to clean up dead tasks.
     * @see taskGraph
     */
    var taskGraphSweepInterval = 5000

    //Default log verbosity level. Anything below this level will not be logged.
    var logVerbosityLevel = LogLevel.Error

    /**
     * Allows hello world ping to ark. Ark will respond with the data using its initial encryption key.
     */
    var allowStatusPing = true


}
