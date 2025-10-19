package Structs

import Global.env
import Global.serverEnv
import Util.decryptString
import Util.deserialize
import Util.encryptString
import Util.getServerKey
import Util.serialize
import org.jetbrains.annotations.NotNull

/**
 * Portable auth key class used by the client to authorize a new device and install of Ark.
 * If sent to the server, this key will be extracted, then discarded. Using the key to validate this
 * request was made for a new device, the server will request the hwid of the device to be registered.
 * Using, the hwid, the server will issue a challenge and encrypt it with a programmatic key seeded using the
 * hwid. The client must then decrypt it using the same method of creating the programmatic key. Once decrypted,
 * the client must find the matching value on it's keyCheck value. The server will then check if the value
 * matches the keyCheck on the server, and if so, the server will generate a LockedAuthKey and return it to the client.
 * The client will then use the locked key to request the auth key from the server.
 *
 * @see LockedAuthKey
 */
@kotlinx.serialization.Serializable
data class PortableAuthKey(val cinit: Boolean = true)
{
    var uniqueKeyId = ""
    var arkServerId = ""
    var userId = ""

    /**
     * Encrypt a portable key using the server's unique id as the value to create a programmatic key.
     * @return returns the key serialized to json, and then encrypted to a string using XSalsa20Poly
     */
    fun encrypt() : String
    {
        val json = serialize(this) //Convert to json.
        val serverId = serverEnv.get().getAuthSettings().masterUserKey //Grab the server id from global settings.
        val key = getServerKey(serverId) //Create programmatic key.
        return encryptString(json, key) //Encrypt portable key using the programmatic key.
    }

    /**
     * Decrypt portable key using the Ark server's unique server id.
     * @param value The encrypted string that comprises this key.
     */
    fun decrypt(value: String)
    {
        val key = getServerKey(serverEnv.get().getAuthSettings().masterUserKey)//Get decryption key.
        val json = decryptString(value, key) //Decrypt back to json.
        val newKeyObj = deserialize<PortableAuthKey>(json) ?: PortableAuthKey() //Convert back to valid key.

        //Write values back to this object.
        uniqueKeyId = newKeyObj.uniqueKeyId
        arkServerId = newKeyObj.arkServerId
        userId = newKeyObj.userId
    }


    fun isValid() : Boolean
    {
        if(uniqueKeyId != "" && userId != "" && arkServerId != "")
        {
            return true
        }

        return false
    }

}
