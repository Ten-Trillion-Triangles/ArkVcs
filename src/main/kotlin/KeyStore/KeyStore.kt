package KeyStore

import Global.serverEnv
import Structs.LockedAuthKey
import Structs.PortableAuthKey
import Structs.UserSettings
import Util.deserialize
import Util.encryptString
import Util.getClientKey
import Util.serialize
import kotlinx.coroutines.sync.Mutex

/**
 * Container class for storing a user's locked and portable auth keys.
 *
 * @param username The username of the user this key set belongs to.
 * @param lockedKeys A map of locked auth keys for this user. The key is the hwid of the device that the key is locked to.
 * @param portableKeys A map of portable auth keys for this user.
 */
@kotlinx.serialization.Serializable
data class KeySet(var cinit: Boolean = false)
{
    var username: String = ""
    private var lockedKeys = mutableListOf<LockedAuthKey>()
    private var portableKeys = mutableListOf<PortableAuthKey>()

//============================================== Functions ===========================================================//

    /**
     * Finds a locked auth key by its hwid.
     *
     * @param hwid The hwid of the locked auth key to find.
     * @return The locked auth key if found, otherwise null.
     */
    fun findLockedKey(hwid: String): LockedAuthKey?
    {
        return lockedKeys.find { it.hwid == hwid }
    }

    /**
     * Finds a portable auth key by its unique key identifier.
     *
     * @param id The unique key identifier of the portable auth key to find.
     * @return The portable auth key if found, otherwise null.
     */
    fun findPortableKey(id: String): PortableAuthKey?
    {
        return portableKeys.find { it.uniqueKeyId == id }
    }

    /**
     * Gets a portable auth key from the key set by its unique key identifier.
     *
     * @param key The portable auth key to find.
     * @return The portable auth key if found, otherwise null.
     */
    fun getPortableKey(key: PortableAuthKey): PortableAuthKey?
    {
        return findPortableKey(key.uniqueKeyId)
    }

    /**
     * Gets a locked auth key from the key set by its hwid.
     *
     * @param key The locked auth key to find.
     * @return The locked auth key if found, otherwise null.
     */
    fun getLockedKey(key: LockedAuthKey): LockedAuthKey?
    {
        return findLockedKey(key.hwid)
    }

    /**
     * Inserts a locked auth key into the key set, or updates it if already present.
     *
     * @param key The locked auth key to insert or update.
     */
    fun emplaceLockedKey(key: LockedAuthKey)
    {
        val index = lockedKeys.indexOf(key)
        if(index != -1)
        {
            lockedKeys[index] = key
        }

        lockedKeys.add(key)
    }

    /**
     * Replace a key if it's in the set.
     *
     * @param key The portable auth key to insert or update.
     */
    fun emplacePortableKey(key: PortableAuthKey)
    {
        val index = portableKeys.indexOf(key)
        if(index != -1)
        {
            portableKeys[index] = key
        }

        portableKeys.add(key)
    }


    /**
     * Retrieves a list of all locked auth keys in the key set.
     *
     * @return A list containing all locked auth keys.
     */
    fun getLockedKeys(): List<LockedAuthKey> = lockedKeys


    /**
     * Retrieves a list of all portable auth keys in the key set.
     *
     * @return A list containing all portable auth keys.
     */
    fun getPortableKeys(): List<PortableAuthKey> = portableKeys


    fun removeLockedKey(key: LockedAuthKey)
    {
        lockedKeys.remove(key)
    }

    fun removePortableKey(key: PortableAuthKey)
    {
        portableKeys.remove(key)
    }


}

@kotlinx.serialization.Serializable
data class KeyStoreObj(var cinit: Boolean = false)
{

    private var portableKeyIDs = mutableListOf<String>() //List of all valid key IDs
    private var discardedKeyIDs = mutableListOf<String>() //List of all discarded key IDs
    private var keySets = mutableMapOf<String, KeySet>() //Map of all key sets. The key is the username of the user.

    @kotlinx.serialization.Transient
    private var lockedKeyCache = mutableMapOf<String, ByteArray>() //Maps username + hwid to the encryption key. Allows us to avoid the costly argon2 hash each time we need to decrypt a user's data.

    fun getKeySet(username: String): KeySet?
    {
        return keySets[username] ?: KeySet()
    }

    fun emplaceKeySet(keySet: KeySet)
    {
        keySets[keySet.username] = keySet
    }

    fun removeKeySet(keySet: KeySet)
    {
        keySets.remove(keySet.username)
    }

    /**
     * Generates a new unique ID that doesn't exist in either portableKeyIDs or discardedKeyIDs lists.
     * The generated ID follows the format "key_" followed by a UUID.
     *
     * @return A unique ID string that is not present in either ID list.
     */
    fun newId(): String {
        var newId: String
        do {
            newId = "key_" + java.util.UUID.randomUUID().toString()
        } while (portableKeyIDs.contains(newId) || discardedKeyIDs.contains(newId))

        portableKeyIDs.add(newId)
        return newId
    }

    /**
     * Marks a portable key ID as discarded. This adds the ID to the list of discarded IDs
     * and removes it from the list of valid IDs.
     *
     * @param id The ID of the portable key to discard.
     */
    fun discardId(id: String) {
        if (portableKeyIDs.contains(id)) {
            portableKeyIDs.remove(id)
            discardedKeyIDs.add(id)
        }
    }

    fun findId(id: String) : Boolean
    {
        if(portableKeyIDs.contains(id))
        {
            return true
        }

        return false
    }

    fun isDiscarded(id: String) : Boolean
    {
        return discardedKeyIDs.contains(id)
    }

    /**
     * Add encryption key to the cache to allow us to avoid having to run the argon2 programatic key generation
     * per call and create a massive performance bottleneck.
     */
    fun addToKeyCache(hwidCombo : String, encryptionKey : ByteArray)
    {
        lockedKeyCache[hwidCombo] = encryptionKey
    }

    /**
     * Fully obliterate a cached key. This must find the full combo of key and pair so that kotlin doesn't
     * leave the ecnryption key itself behind during any seralization events.
     */
    fun removeFromKeyCache(key: LockedAuthKey)
    {
        val pairValue = lockedKeyCache[key.userId + key.hwid]
        lockedKeyCache.remove(key.userId + key.hwid, pairValue)
    }

    /**
     * Attempt to find the cached key that's connected to a locked key on the server.
     */
    fun findCachedEncryptionKey(username: String, hwid: String) : ByteArray?
    {
        return lockedKeyCache[username + hwid]
    }
}


object keyStore
{
    @Volatile
    private var data = KeyStoreObj()
    val keyStoreMutex = Mutex() //Required because we need to have safe access to modifying the keystore by multiple threads.

    /**
     * Generate, and issue a new portable key for a given user. This will handle automatically creating
     * the new key id, and ensuring it is not an already existing one, or a discarded one. The key will then
     * be saved to the keyStore and encrypted to be sent back to the client who can use the encrypted key
     * to gain a one time authorization to issue a new locked key to authorize a new device.
     *
     * This function assumes all required security checks and validation has already occurred. Under no circumstances
     * should this be called prior to authorizing that a portable key can be issued to a user.
     *
     * @param username The username of the Ark user to issue the key for.
     *
     * @return The portable key fully encrypted and ready for transit back to the client's machine.
     */
    fun issuePortableKey(username : String) : String
    {
        val keySet = data.getKeySet(username) ?: KeySet() //Retrieve the user's keys or create a new object if not found.
        val portableKey = PortableAuthKey() //Create new PortableAuthKey.
        val newId = data.newId() //Generate new valid uuid for the auth key and bless it.
        portableKey.uniqueKeyId = newId //Save new id to the key.
        portableKey.arkServerId = serverEnv.get().getAuthSettings().masterUserKey //Save server id to the key.
        portableKey.userId = username //Save username to the key.
        keySet.emplacePortableKey(portableKey) //Replace the key in the keySet.

        data.emplaceKeySet(keySet) //Replace the keyset in the data of our keyStore.

        val encryptedKey = portableKey.encrypt() //Encrypt the portable key for transit back to the client.
        return encryptedKey
    }


    /**
     * Issue a locked key that can be used for connecting to Ark by a client.
     * This function assumes all security checks have been passed for key issuance and is valid to allow the provision
     * of the key. As such, this function should never be calleed outside the dedicated task object that handles
     * key creation.
     *
     * @see LockedAuthKey
     */
    fun issueLockedKey(username: String, hwid: String) : String
    {
        //Can't proceed if either is not valid.
        if(username == "" || hwid == "") return ""

        val keySet = data.getKeySet(username) ?: KeySet() //Retrieve the keyset for the user or make a new one if we can't.
        val lockedKey = LockedAuthKey() //Create new lockedKey object.
        lockedKey.userId = username //Store username.
        lockedKey.hwid = hwid //Store hwid.
        keySet.emplaceLockedKey(lockedKey) //Update the key set.
        data.emplaceKeySet(keySet) //Update the keystore object.

        val json = serialize(lockedKey) //Serialize locked key.
        val programmaticKey = getClientKey(username, hwid) //Generate key using the client's username and hwid.

        val hwidCombo = "${username}${hwid}"
        data.addToKeyCache(hwidCombo, programmaticKey) //Add to key cache to reduce api call overhead.
        return encryptString(json, programmaticKey) //Encrypt the key ready for use and transit back to the client.
    }

    fun discardLockedKey(key: LockedAuthKey)
    {
        val username = key.userId
        val keySet = findUser(username)
        keySet?.removeLockedKey(key)
    }


    /**
     * Find the username's keyset that holds all of their key data.
     */
    fun findUser(username: String): KeySet?
    {
        return data.getKeySet(username)
    }

    fun findPortableKey(id: String): String
    {
        if(data.findId(id))
        {
            return id
        }
        return ""
    }

    fun isDiscarded(id: String): Boolean
    {
        return data.isDiscarded(id)
    }

    fun discardId(id: String)
    {
        data.discardId(id)
    }

    /***
     * Attempt to locate the data for a registered ark user by extracting the user id from a decrypted LockedAuthKey.
     * @param key LockedAuthKey that has been decrypted and is in a valid object form.
     */
    fun findUserFromLockedKey(key: LockedAuthKey) : UserSettings?
    {
        val username = key.userId
        return serverEnv.get().getUserManifest().registeredUsers[username]
    }

    fun validateLockedKey(key: LockedAuthKey) : UserSettings?
    {
        val keySet = findUser(key.userId)

        if(keySet != null)
        {
           val validKey = keySet.getLockedKey(key)
            if(validKey == key)
            {
                return findUserFromLockedKey(key)
            }
        }

        return null
    }

    /**
     * Cache a user's encryption key that they use to communicate with Ark after the initial handshake.
     * This allows us to avoid cache misses that are very expensive and require us to recreate the programatic key
     * using Argon2.
     *
     * @param lockedKey LockedAuthKey that would be likely recieved via an api call as the authorization bearer.
     * This gets used to generate the hwid combo string which maps the ready to be used XSalsa key that can encrypt
     * and decrypt the Ark user's traffic.
     *
     * @param encyptionKey The encryption key that the user will be using to encrypt and decrypt their data. This would
     * be created from getClientKey() in Crypto.kt
     *
     * @see Main/Api/Crypto.kt
     * @see getClientKey
     */
    fun cacheUserEncryptionKey(lockedKey: LockedAuthKey, encyptionKey: ByteArray)
    {
        data.addToKeyCache(lockedKey.userId + lockedKey.hwid, encyptionKey)
    }

    /**
     * Locate the cached user key if able. This allows us to avoid an argon2 hash to generate the programatic key
     * that the user will be using for encryption. If this misses the ApiRoute system will have to generate the key
     * and then cache it once more.
     */
    fun findCachedUserEncryptionKey(lockedKey: LockedAuthKey) : ByteArray?
    {
        return data.findCachedEncryptionKey(lockedKey.userId, lockedKey.hwid)
    }

}
