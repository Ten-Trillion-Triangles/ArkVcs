package Structs

import Util.decryptString
import Util.deserialize
import Util.getClientKey

/**
 * Auth key class used by Ark to authenticate users. Consists of the ark username and their hwid.
 * This key will be encrypted by the Ark server and then sent to the client. The client will not
 * have the decryption key.
 *
 * When a user attempts to request any service from Ark, the client will have to send this key to the server.
 * The server will then decrypt it and check if the user it holds exists. If it does, then the server will
 * check to see if the user has any hardware registered that has a matching hwid. If a match is found, Ark
 * will use the hwid to generate a programmatic key for the user. The user must then decrypt the message
 * and return the matching value that's connected to the message key sent. Ark will match the value to it's
 * keyCheck value and allow the user to use the service.
 *
 *
 */
@kotlinx.serialization.Serializable
data class LockedAuthKey(val cinit: Boolean = true)
{
     var userId = ""
     var hwid = ""


     /**
      * Create a new instance of LockedAuthKey. This will assing the userId and hwid values, and then create
      * the programmatic key that will be used to encrypt the message and return the matching value.
      *
      * @param userId The user's ark username.
      *
      * @param hwid The hardware identifier of the device or system that is requesting the key. This is used to
      * seed the programmatic key that will be used to encrypt the message and return the matching value. Typically,
      * this will be used for the client to server's communication and unlocking of the LockedAuthKey.
      *
      * @return The generated programmatic key
      */
     fun createKey(userId: String, hwid: String): String
     {
          this.userId = userId
          this.hwid = hwid

          val key = getClientKey(userId, hwid)
          val keyString = key.toString(Charsets.UTF_8)

          return keyString
     }


     /**
      * Encrypt the LockedAuthKey using the provided key.
      *
      * @param key The key to encrypt the LockedAuthKey with.
      *
      * @return The encrypted LockedAuthKey
      */
     fun encrypt(key: String) : String
     {
          val json = Util.serialize(this)

          if(json.isNotEmpty())
          {
               val encryptedAuthKey = Util.encryptString(json, key)
               return encryptedAuthKey
          }

          return ""
     }


     fun decrypt(value: String, username : String, hwid : String) : Boolean
     {
          val xsalsaKey = getClientKey(username, hwid)

          if(xsalsaKey.isNotEmpty())
          {
               val json = decryptString(value, xsalsaKey)
               if(json.isNotEmpty())
               {
                    val newLockedKey = deserialize<LockedAuthKey>(json)
                    if(newLockedKey != null)
                    {
                         this.userId = newLockedKey.userId
                         this.hwid = newLockedKey.hwid
                         return true
                    }
               }
          }

          return false
     }

    /**
     * Required because we use class body objects so == won't work
     * in the default state since it only checks the constructor.
     */
    override fun equals(other: Any?): Boolean {

        if(other !is LockedAuthKey)
        {
            return false
        }

        if(this.userId == other.userId && this.hwid == other.hwid)
        {
            return true
        }

        return false
    }

}
