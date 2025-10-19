package Structs.Api

import Structs.LockedAuthKey
import Structs.UserSettings
import Util.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@kotlinx.serialization.Serializable
data class Request(var cinit : Boolean = false)
{
    @kotlinx.serialization.Serializable
    var function: (suspend (json: String, user: UserSettings) -> String )? = null

    @Serializable
    var key: LockedAuthKey? = null

    @Serializable
    var json: String = ""


   inline fun <reified T> makeRequest(noinline function: (suspend (json: String, user: UserSettings) -> String), json: T) : Request
    {
        this.function = function
        //todo: Get user settings from global file. This would only be callable as client anyways.
        this.json = serialize(json)
        return this
    }

}


/**
 * Void request object. Allows for a request with no params to be sent. This is used mainly for sending requests to the
 * client itself such as challenge requests where exposing any api structure is not desired.
 */
data class VoidRequest(val cinit : Boolean = false)
{
    var function : (() -> Unit)? = null
    var key: LockedAuthKey? = null

    /**
     * Creates a void request with the specified function and authentication key.
     * @param function The function to execute
     * @param key The authentication key
     * @return This VoidRequest instance
     */
    inline fun makeRequest(noinline function: (() -> Unit), key: LockedAuthKey) : VoidRequest
    {
        this.function = function
        this.key = key
        return this
    }
}
