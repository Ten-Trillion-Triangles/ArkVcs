package Structs

import Enums.EncryptionRequirements
import kotlinx.serialization.Serializable

/**
 * Class that defines global auth settings for Ark.
 *
 * @param masterUserKey Ark master user key. This is used to encrypt all locked auth keys and sign portable auth keys.
 *
 * @param encryptionLevel Default level of encryption for the locked and portable auth keys, as well as for
 * communication between the server and client. Defaults to XSalsa, but may support AES and bcrypt in the future.
 *
 * @param requireLockedKey If true, the user will need to provide a locked key to decrypt their files. Otherwise,
 * any signed auth key will work and not require a locking to the user's hwid.
 *
 * @param minimumAccountCreationPermission The minimum permission level required to create an account. Defaults to
 * Admin, but can be changed by the system administrator to be a lower permission level depending on deployment
 * and security requirements.
 *
 * @param allowUserPortableAuthKeys If true, the user will be allowed to request a portable auth key. Otherwise,
 * only an authorized user may request a portable auth key for any given user. Defaulted to false based on Ark
 * standard security settings.
 *
 * @param initialKey Initial key for first contact with the server. This key is the default encryption key for
 * communication between the server and client. If non-empty, this key will be used for the first communication
 * with the server. Unlike all other ark settings and systems, this value must be changed here in the source code
 * and recompiled to take effect.
 *
 * @see PortableAuthKey
 * @see LockedAuthKey
 */
@Serializable
data class AuthSettings(var init : Boolean = false)
{
    var masterUserKey = "" //Master decryption key for user files.
    var cachedKey = ""  //Server encryption key after being built using the argon2 hashing mechanism.
    var initialKey = "" //Initial key for first contact with the server.
    var cachedInitialKey = "" //Initial key having been already hashed by argon2 during server bootup.
    var encryptionLevel = EncryptionRequirements.XSalsa //Default to XSalsa but allow AES and bcrypt in the future.
    var requireLockedKey = true //If true, the user will need to provide a locked key to decrypt their files.
    var minimumAccountCreationPermission = Enums.Permissions.Admin
    var allowUserPortableAuthKeys = false
}
